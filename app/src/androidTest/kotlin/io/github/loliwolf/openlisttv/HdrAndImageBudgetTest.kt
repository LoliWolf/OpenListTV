package io.github.loliwolf.openlisttv

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Gainmap
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.loliwolf.openlisttv.core.*
import io.github.loliwolf.openlisttv.data.HttpClients
import io.github.loliwolf.openlisttv.media.HdrSupport
import io.github.loliwolf.openlisttv.media.ImagePipeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@UnstableApi
@RunWith(AndroidJUnit4::class)
class HdrAndImageBudgetTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        return ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle(); it.toByteArray() }
    }
    /** Streams fixture bytes; never allocates a 128 MiB test body in the app heap. */
    private class StreamImage(private val prefix: ByteArray, private val length: Long, private val chunked: Boolean = false, private val headerLength: Long = length) : AutoCloseable {
        private val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        private val active = AtomicReference<Socket?>()
        val url = "http://127.0.0.1:${server.localPort}/picture.png"
        private val worker = thread(name = "image-budget-fixture", isDaemon = true) {
            try {
                server.accept().use { socket ->
                    active.set(socket); val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val output = socket.getOutputStream().buffered(65536)
                    val size = if (chunked) "Transfer-Encoding: chunked\r\n" else "Content-Length: $headerLength\r\n"
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\n${size}Connection: close\r\n\r\n".toByteArray())
                    fun send(bytes: ByteArray, n: Int) {
                        if (chunked) output.write("${n.toString(16)}\r\n".toByteArray())
                        output.write(bytes, 0, n); if (chunked) output.write("\r\n".toByteArray())
                    }
                    send(prefix, prefix.size); var remaining = length - prefix.size; val zeros = ByteArray(32768)
                    while (remaining > 0) { val n = minOf(remaining, zeros.size.toLong()).toInt(); send(zeros, n); remaining -= n }
                    if (chunked) output.write("0\r\n\r\n".toByteArray()); output.flush()
                }
            } catch (_: java.io.IOException) { /* Rejection and cancellation intentionally close the socket. */ }
            finally { active.set(null) }
        }
        override fun close() { active.getAndSet(null)?.close(); server.close(); worker.join(5000) }
    }
    private fun source(url: String) = MediaSource(Entry("/picture.png", "picture.png", false), url)
    private fun clean() = assertTrue("Temporary image input leaked", context.cacheDir.resolve("images").listFiles().orEmpty().isEmpty())

    @Test fun acceptsFileLargerThan32MiBWithoutAllocatingOriginalPixels() = runBlocking {
        StreamImage(png(), 40L * 1024 * 1024).use { server ->
            val image = ImagePipeline(context, HttpClients.media(true)).load(source(server.url), 1920, 1080, slideshow = true)
            assertEquals(32, image.width); assertEquals(18, image.height); assertTrue(image.allocationByteCount < 4096); image.recycle()
        }; clean()
    }
    @Test fun acceptsExactly128MiBWithUnknownChunkedLength() = runBlocking {
        StreamImage(png(), Budgets.IMAGE_DISK_BYTES, chunked = true).use { server ->
            val image = ImagePipeline(context, HttpClients.media(true)).load(source(server.url), 1920, 1080, slideshow = true)
            assertEquals(32, image.width); image.recycle()
        }; clean()
    }
    @Test fun oversizedHeaderAndStreamArePerFileFailures() = runBlocking {
        val prefix = png()
        StreamImage(prefix, prefix.size.toLong(), headerLength = Budgets.IMAGE_DISK_BYTES + 1).use { server ->
            try { ImagePipeline(context, HttpClients.media(true)).load(source(server.url), 1920, 1080); fail() }
            catch (e: ClientFailure) { assertEquals(FailureKind.RESOURCE_LIMIT, e.kind); assertFalse(PlaybackFailures.requiresPause(e.kind)) }
        }; clean()
        StreamImage(prefix, 2L * 1024 * 1024, chunked = true).use { server ->
            try { ImagePipeline(context, HttpClients.media(true), inputLimit = 1024L * 1024).load(source(server.url), 1920, 1080); fail() }
            catch (e: ClientFailure) { assertEquals(FailureKind.RESOURCE_LIMIT, e.kind) }
        }; clean()
    }
    @Test fun actualDiskCapacityIsStillAGlobalPause() = runBlocking {
        try { ImagePipeline(context, HttpClients.media(true), availableSpace = { 1L }).load(source("http://127.0.0.1:1/not-requested"), 1920, 1080); fail() }
        catch (e: ClientFailure) { assertEquals(FailureKind.CAPACITY, e.kind); assertTrue(PlaybackFailures.requiresPause(e.kind)) }
        clean()
    }
    @Test fun hdrMetadataAndDisplayCapabilityAreRecognized() {
        fun format(transfer: Int) = Format.Builder().setSampleMimeType("video/hevc").setColorInfo(ColorInfo.Builder().setColorTransfer(transfer).build()).build()
        assertEquals(HdrSupport.Range.HDR10, HdrSupport.range(format(C.COLOR_TRANSFER_ST2084)))
        assertEquals(HdrSupport.Range.HLG, HdrSupport.range(format(C.COLOR_TRANSFER_HLG)))
        assertEquals(HdrSupport.Range.DOLBY_VISION, HdrSupport.range(Format.Builder().setSampleMimeType("video/dolby-vision").build()))
        assertTrue(HdrSupport.supports(HdrSupport.Range.HDR10, setOf(2)))
        assertTrue(HdrSupport.supports(HdrSupport.Range.HDR10, setOf(4)))
        assertFalse(HdrSupport.supports(HdrSupport.Range.HLG, setOf(2)))
        assertTrue(HdrSupport.supports(HdrSupport.Range.SDR, emptySet()))
    }
    @Test fun ultraHdrGainmapIsPreservedRotatedAndCountedInBudget() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        if (Build.VERSION.SDK_INT >= 34) {
            val base = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(180, 100, 60)) }
            val map = Bitmap.createBitmap(80, 45, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
            base.setGainmap(Gainmap(map).apply { setRatioMax(4f, 4f, 4f); setDisplayRatioForFullHdr(4f) })
            val file = File.createTempFile("ultra-hdr-test-", ".jpg", context.cacheDir)
            try {
                file.outputStream().use { base.compress(Bitmap.CompressFormat.JPEG, 95, it) }; base.recycle()
                ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
                val bytes = file.readBytes()
                StreamImage(bytes, bytes.size.toLong()).use { server ->
                    val image = ImagePipeline(context, HttpClients.media(true)).apply { hdrEnabled = true }.load(source(server.url), 1920, 1080, slideshow = true)
                    assertTrue(image.hasGainmap()); assertEquals(180, image.width); assertEquals(320, image.height)
                    assertTrue(HdrSupport.bitmapBytes(image) > image.allocationByteCount); assertTrue(HdrSupport.bitmapBytes(image) <= Budgets.SLIDESHOW_IMAGE_BYTES / 2); image.recycle()
                }
                StreamImage(bytes, bytes.size.toLong()).use { server ->
                    val image = ImagePipeline(context, HttpClients.media(true)).load(source(server.url), 1920, 1080)
                    assertFalse(image.hasGainmap()); image.recycle()
                }
            } finally { file.delete(); map.recycle() }
        }; clean()
    }
}

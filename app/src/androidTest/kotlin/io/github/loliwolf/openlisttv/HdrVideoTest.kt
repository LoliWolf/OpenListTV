package io.github.loliwolf.openlisttv

import android.content.Intent
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.loliwolf.openlisttv.core.*
import io.github.loliwolf.openlisttv.data.HttpClients
import io.github.loliwolf.openlisttv.media.MediaEngine
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Optional upstream sample fixtures; provision explicitly, never silently claim a skipped sample. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class HdrVideoTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Test fun hdr10StreamsThroughThePlatformRenderer() = play("hdr10-720p.mp4", C.COLOR_TRANSFER_ST2084)
    @Test fun hlgStreamsThroughThePlatformRenderer() = play("hlg-1080p.mp4", C.COLOR_TRANSFER_HLG)
    private fun play(name: String, transfer: Int) {
        val file = File(instrumentation.targetContext.getExternalFilesDir("hdr-fixtures"), name)
        assumeTrue("Provision the official HDR sample before running this test", file.isFile)
        val hardwareNames = MediaCodecSelector.DEFAULT.getDecoderInfos("video/hevc", false, false).filter { it.hardwareAccelerated }.map { it.name }.toSet()
        val hevc = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals("video/hevc", true) } }
        val capabilities = hevc.map { info -> info.name to info.getCapabilitiesForType("video/hevc").profileLevels.map { it.profile }.toSet() }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "HEVC profiles: $capabilities; eligible hardware: $hardwareNames\n") })
        val main10 = capabilities.any { (name, profiles) -> name in hardwareNames && profiles.any { it in setOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10, if (Build.VERSION.SDK_INT >= 29) MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus else -1) } }
        assumeTrue("This test device has no hardware HEVC Main10 decoder; run HDR samples on the target HDR device", main10)
        val bytes = file.readBytes()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val offset = request.getHeader("Range")?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                    if (offset !in bytes.indices) return MockResponse().setResponseCode(416)
                    return MockResponse().setResponseCode(if (offset > 0) 206 else 200)
                        .setHeader("Content-Type", "video/mp4").setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Range", "bytes $offset-${bytes.lastIndex}/${bytes.size}")
                        .setBody(Buffer().write(bytes, offset, bytes.size - offset))
                }
            }
            server.start()
            val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            var engine: MediaEngine? = null; var failure: String? = null; var rendered = false; var observedTransfer: Int? = null
            try {
                instrumentation.runOnMainSync {
                    val view = PlayerView(activity).apply { useController = false }; activity.setContentView(view)
                    engine = MediaEngine(activity, HttpClients.media(true), object : MediaEngine.Events {
                        override fun ready() = Unit
                        override fun ended() = Unit
                        override fun qualityChanged(message: String) = Unit
                        override fun failed(message: String, sourceExpired: Boolean, kind: FailureKind) { failure = "$kind: $message" }
                    }).also { view.player = it.player }
                    engine!!.play(MediaSource(Entry("/$name", name, false), server.url("/$name").toString()))
                }
                val until = android.os.SystemClock.elapsedRealtime() + 15000
                while (android.os.SystemClock.elapsedRealtime() < until) {
                    instrumentation.runOnMainSync {
                        observedTransfer = engine!!.player.videoFormat?.colorInfo?.colorTransfer
                        rendered = (engine!!.player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0
                    }
                    if (rendered || failure != null) break
                    android.os.SystemClock.sleep(50)
                }
                assertNull("HDR rejected / hardware decode failed on this device", failure)
                assertTrue("No HDR video frame was rendered", rendered)
                assertEquals(transfer, observedTransfer)
            } finally { instrumentation.runOnMainSync { engine?.release(); activity.finish() } }
        }
    }
}

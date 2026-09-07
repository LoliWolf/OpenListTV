package io.github.loliwolf.openlisttv.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.media3.common.util.UnstableApi
import androidx.exifinterface.media.ExifInterface
import io.github.loliwolf.openlisttv.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@UnstableApi
class ImagePipeline(context: Context, private val http: OkHttpClient, private val inputLimit: Long = Budgets.IMAGE_DISK_BYTES, private val availableSpace: (() -> Long)? = null) {
    private val decodeMutex = Mutex()
    private val directory = File(context.cacheDir, "images").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
    private val localDownloads = File(context.filesDir, "downloads").canonicalFile
    @Volatile var lowMemory = false
    @Volatile var hdrEnabled = false
    init { require(inputLimit in 1..Budgets.IMAGE_DISK_BYTES) }
    suspend fun load(source: MediaSource, screenWidth: Int, screenHeight: Int, slideshow: Boolean = false): Bitmap {
        // withContext can drop a successfully decoded return value on prompt cancellation.
        // Keep ownership outside that dispatcher boundary until the caller receives it.
        val owned = AtomicReference<Bitmap?>()
        try {
            val result = decodeMutex.withLock { withContext(Dispatchers.IO) {
                val local = if (source.url.startsWith("file:")) {
                    val file = runCatching { File(java.net.URI(source.url)).canonicalFile }.getOrNull()
                    if (file == null || file.parentFile != localDownloads || !file.isFile) throw ClientFailure(FailureKind.ACCESS, "本机下载文件不可访问")
                    if (file.length() > inputLimit) throw oversized()
                    file
                } else null
                if (local == null) requireSpace(0)
                val temp = local ?: diskIo { File.createTempFile("image-", ".tmp", directory) }
                try {
                    if (local == null) download(source.url, temp)
                    ensureActive()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(temp.path, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ClientFailure(FailureKind.UNSUPPORTED, "设备无法解码此图片格式")
                    val runtime = Runtime.getRuntime()
                    val available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
                    val pixels = if (slideshow) ImageBudget.slideshowMaxPixels(screenWidth, screenHeight, available, runtime.maxMemory(), lowMemory)
                        else ImageBudget.maxPixels(screenWidth, screenHeight, available, lowMemory)
                    var sample = ImageBudget.sampleSize(bounds.outWidth, bounds.outHeight, pixels)
                    val byteBudget = if (slideshow) minOf(Budgets.SLIDESHOW_IMAGE_BYTES, runtime.maxMemory() / 4) / 2 else available / 4
                    var bitmap: Bitmap? = null
                    repeat(3) {
                        if (bitmap == null) {
                            ensureActive()
                            try {
                                bitmap = BitmapFactory.decodeFile(temp.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }); owned.set(bitmap)
                                bitmap?.let { decoded ->
                                    if (android.os.Build.VERSION.SDK_INT >= 34 && decoded.hasGainmap() && (!hdrEnabled || lowMemory)) decoded.setGainmap(null)
                                    // The gainmap owns pixels too; never account only for its SDR base.
                                    if (HdrSupport.bitmapBytes(decoded) > byteBudget) { decoded.recycle(); bitmap = null; owned.set(null); sample *= 2 }
                                }
                            }
                            catch (_: OutOfMemoryError) { sample *= 2 }
                        }
                    }
                    var decoded = bitmap ?: throw ClientFailure(FailureKind.UNSUPPORTED, "图片无法在当前内存预算内显示")
                    val orientation = runCatching { ExifInterface(temp).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
                    val matrix = Matrix()
                    when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
                        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
                    }
                    if (!matrix.isIdentity) {
                        try { val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true); owned.set(rotated); if (rotated !== decoded) decoded.recycle(); decoded = rotated }
                        catch (_: OutOfMemoryError) { decoded.recycle(); throw ClientFailure(FailureKind.UNSUPPORTED, "当前内存不足以调整图片方向") }
                    }
                    if (!isActive) { decoded.recycle(); ensureActive() }
                    decoded
                } finally { if (local == null) temp.delete() }
            } }
            owned.set(null)
            return result
        } finally { owned.getAndSet(null)?.takeUnless { it.isRecycled }?.recycle() }
    }
    private fun oversized() = ClientFailure(FailureKind.RESOURCE_LIMIT, "图片超过 ${inputLimit / 1024 / 1024} MiB 单文件上限")
    private fun requireSpace(bytes: Long) {
        val free = availableSpace?.invoke() ?: directory.usableSpace
        if (free < bytes.coerceAtLeast(0) + Budgets.DISK_RESERVE_BYTES) throw ClientFailure(FailureKind.CAPACITY, "本机缓存空间不足，请清理空间后重试")
    }
    private inline fun <T> diskIo(block: () -> T): T = try { block() } catch (e: IOException) { throw ClientFailure(FailureKind.CAPACITY, "无法写入本机图片缓存，请检查可用空间", e) }
    private suspend fun download(url: String, target: File): Unit = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(Request.Builder().url(url).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(ClientFailure(FailureKind.NETWORK, "图片下载失败", e)) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) throw ClientFailure(when (it.code) {
                            401, 403 -> FailureKind.STALE_LINK
                            404 -> FailureKind.SERVER
                            429 -> FailureKind.RATE_LIMIT
                            else -> FailureKind.NETWORK
                        }, "图片不可访问（${it.code}）")
                        val length = it.body.contentLength()
                        if (length > inputLimit) throw oversized()
                        requireSpace(length.coerceAtLeast(0))
                        it.body.byteStream().use { input ->
                            val output = diskIo { target.outputStream() }
                            try {
                                val buffer = ByteArray(32 * 1024); var total = 0L; var nextSpaceCheck = 1024L * 1024
                                while (true) {
                                    if (!continuation.isActive) throw IOException("cancelled")
                                    val read = input.read(buffer); if (read < 0) break
                                    total += read; if (total > inputLimit) throw oversized()
                                    if (total >= nextSpaceCheck) { requireSpace(read.toLong()); nextSpaceCheck = total + 1024L * 1024 }
                                    diskIo { output.write(buffer, 0, read) }
                                }
                            } finally { diskIo { output.close() } }
                        }
                    }
                    if (continuation.isActive) continuation.resume(Unit)
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(if (e is ClientFailure) e else ClientFailure(FailureKind.NETWORK, "图片下载失败")) }
                finally { if (call.isCanceled()) target.delete() }
            }
        })
    }
}

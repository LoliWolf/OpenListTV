package io.github.loliwolf.openlisttv.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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

class ImagePipeline(context: Context, private val http: OkHttpClient) {
    private val decodeMutex = Mutex()
    private val directory = File(context.cacheDir, "images").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
    @Volatile var lowMemory = false
    suspend fun load(source: MediaSource, screenWidth: Int, screenHeight: Int, slideshow: Boolean = false): Bitmap {
        // withContext can drop a successfully decoded return value on prompt cancellation.
        // Keep ownership outside that dispatcher boundary until the caller receives it.
        val owned = AtomicReference<Bitmap?>()
        try {
            val result = decodeMutex.withLock { withContext(Dispatchers.IO) {
                val temp = File.createTempFile("image-", ".tmp", directory)
                try {
                    download(source.url, temp)
                    ensureActive()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(temp.path, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ClientFailure(FailureKind.UNSUPPORTED, "设备无法解码此图片格式")
                    val runtime = Runtime.getRuntime()
                    val available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
                    val pixels = if (slideshow) ImageBudget.slideshowMaxPixels(screenWidth, screenHeight, available, runtime.maxMemory(), lowMemory)
                        else ImageBudget.maxPixels(screenWidth, screenHeight, available, lowMemory)
                    var sample = ImageBudget.sampleSize(bounds.outWidth, bounds.outHeight, pixels)
                    var bitmap: Bitmap? = null
                    repeat(3) {
                        if (bitmap == null) {
                            ensureActive()
                            try { bitmap = BitmapFactory.decodeFile(temp.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }); owned.set(bitmap) }
                            catch (_: OutOfMemoryError) { sample *= 2 }
                        }
                    }
                    var decoded = bitmap ?: throw ClientFailure(FailureKind.UNSUPPORTED, "图片无法在当前内存预算内显示")
                    if (android.os.Build.VERSION.SDK_INT >= 34 && decoded.hasGainmap()) decoded.setGainmap(null)
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
                } finally { temp.delete() }
            } }
            owned.set(null)
            return result
        } finally { owned.getAndSet(null)?.takeUnless { it.isRecycled }?.recycle() }
    }
    private suspend fun download(url: String, target: File): Unit = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(Request.Builder().url(url).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(ClientFailure(FailureKind.NETWORK, "图片下载失败")) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) throw ClientFailure(when (it.code) {
                            401, 403 -> FailureKind.STALE_LINK
                            404 -> FailureKind.SERVER
                            429 -> FailureKind.RATE_LIMIT
                            else -> FailureKind.NETWORK
                        }, "图片不可访问（${it.code}）")
                        if (it.body.contentLength() > Budgets.IMAGE_DISK_BYTES) throw ClientFailure(FailureKind.CAPACITY, "图片文件超过 32 MiB 临时缓存预算")
                        it.body.byteStream().use { input -> target.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024); var total = 0L
                            while (true) {
                                if (!continuation.isActive) throw IOException("cancelled")
                                val read = input.read(buffer); if (read < 0) break
                                total += read
                                if (total > Budgets.IMAGE_DISK_BYTES) throw ClientFailure(FailureKind.CAPACITY, "图片文件超过 32 MiB 临时缓存预算")
                                output.write(buffer, 0, read)
                            }
                        } }
                    }
                    if (continuation.isActive) continuation.resume(Unit)
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(if (e is ClientFailure) e else ClientFailure(FailureKind.NETWORK, "图片下载失败")) }
                finally { if (call.isCanceled()) target.delete() }
            }
        })
    }
}

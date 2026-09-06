package io.github.loliwolf.openlisttv.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import io.github.loliwolf.openlisttv.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import okhttp3.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One browser's current + next page only. No viewer bitmaps, URLs or credentials on disk. */
class ThumbnailLoader(
    context: Context,
    private val http: OkHttpClient,
    parent: CoroutineScope,
    private val resolve: suspend (Entry, Boolean) -> String,
    private val onReady: (Entry) -> Unit,
    private val onProblem: (String) -> Unit,
) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val directory = File(context.cacheDir, "thumbnails/${UUID.randomUUID()}").apply { mkdirs() }
    private val originals = Mutex()
    private val memory = object : LruCache<String, Bitmap>(Budgets.THUMBNAIL_MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    @Volatile private var allowed = emptyMap<String, Entry>()
    @Volatile private var visible = emptySet<String>()
    private val jobs = LinkedHashMap<String, Job>()
    private val failed = mutableSetOf<String>()
    private val completed = mutableSetOf<String>()
    private var paused = false
    private var closed = false
    private var blocked = false
    val cachedBytes: Int get() = memory.size()
    val allowedCount: Int get() = allowed.size

    fun key(entry: Entry): String = MessageDigest.getInstance("SHA-256")
        .digest("${entry.path}\n${entry.modified}\n${entry.size}\n${entry.thumbnail}".toByteArray())
        .let { bytes -> buildString(64) { for (b in bytes) { val n = b.toInt() and 255; append("0123456789abcdef"[n shr 4]); append("0123456789abcdef"[n and 15]) } } }
    fun bitmap(entry: Entry): Bitmap? = memory.get(key(entry))

    fun window(entries: List<Entry>, visibleEntries: List<Entry>) {
        if (closed) return
        allowed = entries.filter { it.kind == MediaKind.IMAGE }.take(Budgets.PAGE * 2).associateBy(::key)
        val previousVisible = visible
        visible = visibleEntries.filter { it.kind == MediaKind.IMAGE }.map(::key).toSet()
        failed.removeAll(visible - previousVisible)
        jobs.filterKeys { it !in allowed }.values.toList().forEach { it.cancel() }
        failed.retainAll(allowed.keys)
        completed.retainAll(allowed.keys)
        memory.snapshot().keys.filter { it !in allowed }.forEach(memory::remove)
        scope.launch(Dispatchers.IO) { directory.listFiles()?.forEach { if (it.extension == "png" && it.nameWithoutExtension !in allowed) it.delete() } }
        pump()
    }
    fun pause(releaseBitmaps: Boolean = true) {
        paused = true
        jobs.values.toList().forEach { it.cancel() }
        if (releaseBitmaps) memory.evictAll()
    }
    fun resume() { if (!closed) { paused = false; pump() } }
    fun close() {
        if (closed) return
        closed = true; paused = true; scope.cancel(); memory.evictAll(); allowed = emptyMap()
        // Runs only once all cancelled child downloads have closed their files.
        scope.coroutineContext[Job]!!.invokeOnCompletion {
            directory.listFiles()?.forEach { it.delete() }; directory.delete()
        }
    }
    private fun pump() {
        if (closed || paused || blocked) return
        while (jobs.size < 2) {
            val cached = memory.snapshot().keys
            val candidate = allowed.entries.asSequence()
                .filter { (k, e) -> k !in jobs && k !in failed && k !in cached && (k !in completed || k in visible) && (e.thumbnail.isNotBlank() || k in visible) }
                .sortedBy { if (it.key in visible) 0 else 1 }.firstOrNull() ?: break
            val (key, entry) = candidate
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val bitmap = load(entry, key)
                    if (!closed && !paused && key in allowed) { completed += key; memory.put(key, bitmap); onReady(entry) }
                } catch (e: CancellationException) { throw e }
                catch (_: OutOfMemoryError) {
                    memory.evictAll(); blocked = true
                    onProblem("缩略图已暂停以节省内存，可继续浏览和播放")
                    jobs.filterKeys { it != key }.values.toList().forEach { it.cancel() }
                }
                catch (e: Exception) {
                    failed += key
                    if (e is ClientFailure && e.kind in setOf(FailureKind.AUTH, FailureKind.RATE_LIMIT, FailureKind.CHALLENGE)) {
                        blocked = true; onProblem("缩略图暂停：${e.message}")
                        jobs.filterKeys { it != key }.values.toList().forEach { it.cancel() }
                    }
                } finally { jobs.remove(key); pump() }
            }
            jobs[key] = job; job.start()
        }
    }
    private suspend fun load(entry: Entry, key: String): Bitmap {
        val cache = File(directory, "$key.png")
        return withContext(Dispatchers.IO) {
            if (cache.isFile) BitmapFactory.decodeFile(cache.path)?.let { return@withContext it }
            val temp = File.createTempFile("fetch-", ".tmp", directory)
            var originalLocked = false
            suspend fun original() {
                originals.lock(); originalLocked = true
                ensureActive()
                if (key !in visible || key !in allowed) throw CancellationException("Preview is no longer visible")
                if (entry.size > Budgets.THUMBNAIL_SOURCE_BYTES) throw IOException("Original is too large for a list preview")
                fetch(resolve(entry, false), temp, Budgets.THUMBNAIL_SOURCE_BYTES)
            }
            try {
                if (entry.thumbnail.isNotBlank()) {
                    try { fetch(resolve(entry, true), temp, 2L * 1024 * 1024) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        if (e is ClientFailure && e.kind in setOf(FailureKind.AUTH, FailureKind.RATE_LIMIT, FailureKind.CHALLENGE)) throw e
                        // Don't fetch hundreds of originals merely to warm a next-page preview.
                        if (key !in visible) throw e
                        original()
                    }
                } else original()
                ensureActive()
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(temp.path, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("Unsupported thumbnail")
                val sample = ImageBudget.sampleSize(options.outWidth, options.outHeight, 160L * 100)
                var bitmap = BitmapFactory.decodeFile(temp.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: throw IOException("Thumbnail decode failed")
                if (android.os.Build.VERSION.SDK_INT >= 34 && bitmap.hasGainmap()) bitmap.setGainmap(null)
                val orientation = runCatching { ExifInterface(temp).rotationDegrees }.getOrDefault(0)
                val flipped = runCatching { ExifInterface(temp).isFlipped }.getOrDefault(false)
                if (orientation != 0 || flipped) {
                    val matrix = Matrix().apply { postRotate(orientation.toFloat()); if (flipped) postScale(-1f, 1f) }
                    val adjusted = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    if (adjusted !== bitmap) bitmap.recycle(); bitmap = adjusted
                }
                if (!isActive) { bitmap.recycle(); ensureActive() }
                cache.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val files = directory.listFiles()?.filter { it.extension == "png" }?.sortedBy { it.lastModified() }.orEmpty()
                var bytes = files.sumOf { it.length() }
                for (file in files) { if (bytes <= Budgets.THUMBNAIL_DISK_BYTES) break; bytes -= file.length(); file.delete() }
                bitmap
            } finally { temp.delete(); if (originalLocked) originals.unlock() }
        }
    }
    private suspend fun fetch(url: String, target: File, max: Long): Unit = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(Request.Builder().url(url).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (it.code == 429) throw ClientFailure(FailureKind.RATE_LIMIT, "服务端限流")
                        if (!it.isSuccessful || it.body.contentLength() > max) throw IOException("Thumbnail unavailable")
                        it.body.byteStream().use { input -> target.outputStream().use { output ->
                            val buffer = ByteArray(16 * 1024); var count = 0L
                            while (true) {
                                if (!continuation.isActive) throw IOException("Cancelled")
                                val read = input.read(buffer); if (read < 0) break
                                count += read; if (count > max) throw IOException("Thumbnail source too large")
                                output.write(buffer, 0, read)
                            }
                        } }
                    }
                    if (continuation.isActive) continuation.resume(Unit)
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
}

package io.github.loliwolf.openlisttv.data

import io.github.loliwolf.openlisttv.core.*
import kotlinx.coroutines.*
import okhttp3.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** One stream with a fixed 64 KiB buffer. The caller owns the single-task queue/lifecycle. */
class FileDownloader(private val http: OkHttpClient, private val availableSpace: ((File) -> Long) = { it.parentFile!!.usableSpace }) {
    data class Resume(val etag: String = "", val total: Long = -1, val sourceKey: String = "")
    private data class Outcome(val restart: Boolean, val bytes: Long = 0)

    suspend fun download(url: String, target: File, resume: Resume = Resume(),
        metadata: (Long, String, String) -> Unit = { _, _, _ -> }, progress: (Long, Long) -> Unit = { _, _ -> }): Long {
        var position = target.length().takeIf { strongEtag(resume.etag) && resume.sourceKey.isNotEmpty() } ?: 0L
        repeat(2) {
            val request = Request.Builder().url(url).header("Accept-Encoding", "identity").apply {
                if (position > 0) { header("Range", "bytes=$position-"); header("If-Range", resume.etag) }
            }.build()
            val outcome = transfer(request) { response ->
                if (response.code == 416 && position > 0) return@transfer Outcome(true)
                if (!response.isSuccessful) throw ClientFailure(when (response.code) {
                    401, 403 -> FailureKind.STALE_LINK
                    429 -> FailureKind.RATE_LIMIT
                    404 -> FailureKind.SERVER
                    else -> FailureKind.NETWORK
                }, "文件下载失败（${response.code}）")
                val contentType = response.header("Content-Type").orEmpty().substringBefore(';').lowercase()
                if (contentType in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl", "application/dash+xml")) throw ClientFailure(FailureKind.UNSUPPORTED, "暂不支持离线打包 HLS / DASH 分片流，请在线播放")
                if (response.header("Content-Encoding").orEmpty().let { it.isNotEmpty() && !it.equals("identity", true) }) throw ClientFailure(FailureKind.UNSUPPORTED, "服务端未提供可安全保存的原始文件流")
                val length = response.body.contentLength()
                val validator = response.header("ETag").orEmpty().takeIf(::strongEtag).orEmpty()
                val sourceKey = sourceKey(response.request.url.toString())
                var offset = 0L
                val total = when (response.code) {
                    200 -> length
                    206 -> {
                        val range = contentRange(response.header("Content-Range"))
                        if (position == 0L) throw ClientFailure(FailureKind.SERVER, "服务端意外返回部分文件，请稍后重试")
                        if (range == null || range.first != position || validator != resume.etag || sourceKey != resume.sourceKey || resume.total > 0 && range.third != resume.total) return@transfer Outcome(true)
                        if (range.second != range.third - 1 || length >= 0 && length != range.second - range.first + 1) throw ClientFailure(FailureKind.SERVER, "服务端续传范围不完整，已保留现有下载")
                        offset = position; range.third
                    }
                    else -> throw ClientFailure(FailureKind.SERVER, "服务端未返回完整文件流")
                }
                // A full restart reclaims this task's old partial file. Do not count
                // both the obsolete partial and its full replacement against free space.
                val needed = if (total >= 0) total - offset else 0
                if (offset > 0 || target.length() == 0L) requireSpace(target, needed)
                val job = currentCoroutineContext()
                job.ensureActive()
                var bytes = offset; var nextSpaceCheck = offset; var lastProgress = 0L
                response.body.byteStream().use { input ->
                    disk { FileOutputStream(target, offset > 0) }.use { output ->
                        requireSpace(target, needed)
                        // Truncate a replaced representation BEFORE checkpointing its new
                        // validator, so an open/disk failure cannot relabel old partial bytes.
                        metadata(total, validator, sourceKey)
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            job.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (total >= 0 && read.toLong() > total - bytes) throw ClientFailure(FailureKind.SERVER, "服务端文件长度变化，下载未完成")
                            if (bytes >= nextSpaceCheck) { requireSpace(target, read.toLong()); nextSpaceCheck = bytes + 1024 * 1024 }
                            disk { output.write(buffer, 0, read) }; bytes += read
                            val now = System.nanoTime()
                            if (now - lastProgress >= 500_000_000L) { progress(bytes, total); lastProgress = now }
                        }
                        disk { output.fd.sync() }
                    }
                }
                job.ensureActive()
                if (total >= 0 && bytes != total) throw ClientFailure(FailureKind.NETWORK, "连接提前结束，下载未完成，可重试")
                progress(bytes, if (total < 0) bytes else total)
                Outcome(false, bytes)
            }
            if (!outcome.restart) return outcome.bytes
            position = 0 // Unsafe/mismatched ranges never get appended to old bytes.
        }
        throw ClientFailure(FailureKind.SERVER, "服务端不支持可靠续传，请重新下载")
    }
    private suspend fun <T> transfer(request: Request, consume: suspend (Response) -> T): T = coroutineScope {
        val call = http.newCall(request)
        val worker = async(Dispatchers.IO) {
            try { call.execute().use { consume(it) } }
            catch (e: Exception) {
                // Closing a cancelled socket is expected; do not promote it to an error
                // that cancels the parent with a non-cancellation exception.
                currentCoroutineContext().ensureActive()
                if (e is IOException) throw ClientFailure(FailureKind.NETWORK, "下载连接中断，可重试", e)
                throw e
            }
        }
        try { worker.await() }
        finally {
            // Cancellation must interrupt socket reads AND join the writer before a new
            // transfer/playback can start or the partial file can be deleted.
            call.cancel()
            withContext(NonCancellable) { worker.join() }
        }
    }
    private fun requireSpace(file: File, needed: Long) {
        if (availableSpace(file) - RESERVE_BYTES < needed.coerceAtLeast(0)) throw ClientFailure(FailureKind.CAPACITY, "本机下载空间不足，需保留至少 64 MiB 空闲空间")
    }
    private inline fun <T> disk(block: () -> T): T = try { block() } catch (e: IOException) { throw ClientFailure(FailureKind.CAPACITY, "无法写入本机下载，请检查存储空间", e) }
    companion object {
        const val RESERVE_BYTES = 64L * 1024 * 1024
        // ETags are scoped to a resource, not globally unique across redirect targets.
        // Retain only a hash of the final URL, never its signed query or credentials.
        fun sourceKey(url: String): String = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        fun strongEtag(value: String): Boolean = value.length in 2..1024 && value.first() == '"' && value.last() == '"' && value.substring(1, value.lastIndex).none { it == '"' || it.code < 0x21 || it.code == 0x7f }
        fun contentRange(value: String?): Triple<Long, Long, Long>? {
            val m = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(value.orEmpty()) ?: return null
            val values = m.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
            val (start, end, total) = values
            return if (start <= end && end < total) Triple(start, end, total) else null
        }
    }
}

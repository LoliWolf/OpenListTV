package io.github.loliwolf.openlisttv

import android.content.Context
import android.net.Uri
import io.github.loliwolf.openlisttv.core.*
import io.github.loliwolf.openlisttv.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/** Process-wide single slot, stopped by Activity.onStop; no service or work scheduler. */
class DownloadController(private val context: Context, private val scope: CoroutineScope, val store: DownloadStore) {
    enum class Phase(val label: String) { IDLE("空闲"), PREPARING("准备下载"), DOWNLOADING("下载中"), EXPORTING("导出中"), PAUSED("已暂停"), COMPLETE("已完成"), FAILED("需重试") }
    data class Snapshot(val id: String? = null, val name: String = "", val phase: Phase = Phase.IDLE, val bytes: Long = 0, val total: Long = -1, val message: String = "", val revision: Long = 0)
    private val mutableState = MutableStateFlow(Snapshot())
    val state = mutableState.asStateFlow()
    @Volatile private var task: Job? = null
    private var pauseReason = "已暂停，可手动继续"
    val busy get() = task != null // A cancelling writer still owns the slot until joined.

    fun pause(reason: String = "已暂停，可手动继续") { pauseReason = reason; task?.cancel() }
    suspend fun pauseAndJoin(reason: String = "已暂停，可手动继续") { val old = task; pause(reason); old?.join() }
    fun changed() { mutableState.value = if (busy) mutableState.value.copy(revision = mutableState.value.revision + 1) else Snapshot(message = "下载列表已更新", revision = mutableState.value.revision + 1) }

    fun start(repository: FileRepository, entry: Entry, saved: SavedDownload? = null): Boolean {
        if (busy) return false
        pauseReason = "已暂停，可手动继续"
        task = scope.launch(start = CoroutineStart.LAZY) {
            var record: SavedDownload? = null
            var finalPhase = Phase.FAILED
            var message = "下载未完成"
            var finished = false
            try {
                if (entry.directory) throw ClientFailure(FailureKind.UNSUPPORTED, "请选择单个文件，不支持整目录下载")
                if (entry.name.substringAfterLast('.', "").lowercase() in setOf("m3u8", "mpd")) throw ClientFailure(FailureKind.UNSUPPORTED, "暂不支持离线打包 HLS / DASH 分片流，请在线播放")
                val candidate = withContext(Dispatchers.IO) { saved?.let { store.get(it.id) } }
                if (saved != null && (candidate == null || candidate.identity != DownloadStore.identity(repository.session.site) || candidate.status == DownloadStatus.COMPLETE || candidate.entry != entry)) throw ClientFailure(FailureKind.ACCESS, "请登录原站点账号后继续未完成的下载")
                record = candidate ?: withContext(Dispatchers.IO) { store.create(repository.session.site, entry) }
                val item = checkNotNull(record)
                mutableState.value = Snapshot(item.id, entry.name, Phase.PREPARING, item.bytes, item.total, revision = mutableState.value.revision + 1)
                val downloader = FileDownloader(HttpClients.media(repository.session.site.allowHttp))
                var refreshed = false
                while (true) {
                    // Resolve afresh; a signed raw_url is never persisted in the task database.
                    val source = repository.resolve(entry)
                    val resume = withContext(Dispatchers.IO) { store.get(item.id) ?: item }
                    try {
                        downloader.download(source.url, store.file(item.id, true), FileDownloader.Resume(resume.etag, resume.total, resume.sourceKey),
                            metadata = { total, etag, key -> store.metadata(item.id, total, etag, key) },
                            progress = { bytes, total -> mutableState.value = Snapshot(item.id, entry.name, Phase.DOWNLOADING, bytes, total, revision = mutableState.value.revision) })
                        break
                    } catch (e: ClientFailure) { if (e.kind != FailureKind.STALE_LINK || refreshed) throw e; refreshed = true }
                }
                withContext(Dispatchers.IO) {
                    ensureActive()
                    val final = store.file(item.id, false)
                    if (final.exists() || !store.file(item.id, true).renameTo(final)) throw ClientFailure(FailureKind.CAPACITY, "下载完成但无法保存，请检查本机存储")
                    finished = true
                }
                finalPhase = Phase.COMPLETE; message = "文件已保存在本机下载"
            } catch (e: CancellationException) { finalPhase = Phase.PAUSED; message = pauseReason }
            catch (e: Exception) { message = if (e is ClientFailure) e.message ?: "下载失败" else "下载失败，请检查网络与本机存储后重试" }
            finally {
                // Keep the return from IO non-cancellable as well: otherwise prompt
                // cancellation can skip releasing the writer slot and publishing PAUSED.
                withContext(NonCancellable) {
                  var bytes = mutableState.value.bytes
                  withContext(Dispatchers.IO) {
                    record?.let { item ->
                        if (finished) { finalPhase = Phase.COMPLETE; message = "文件已保存在本机下载" }
                        try {
                            store.settle(item.id, if (finished) DownloadStatus.COMPLETE else if (finalPhase == Phase.PAUSED) DownloadStatus.PAUSED else DownloadStatus.FAILED, message)
                            bytes = store.file(item.id, !finished).length()
                        } catch (_: Exception) { finalPhase = Phase.FAILED; message = "无法保存下载状态，请检查本机存储空间" }
                    }
                  }
                  task = null
                  mutableState.value = Snapshot(record?.id, entry.name, finalPhase, bytes, mutableState.value.total, message, mutableState.value.revision + 1)
                }
            }
        }.also { it.start() }
        return true
    }

    fun export(record: SavedDownload, uri: Uri): Boolean {
        if (busy) return false
        pauseReason = "导出已中断；目标位置可能有未完成文件，请检查后重新导出"
        task = scope.launch(start = CoroutineStart.LAZY) {
            var phase = Phase.FAILED
            var message = "导出失败；请检查目标位置的可用空间"
            mutableState.value = Snapshot(record.id, record.entry.name, Phase.EXPORTING, 0, record.bytes, revision = mutableState.value.revision + 1)
            try {
                if (uri.scheme != "content") throw ClientFailure(FailureKind.ACCESS, "请选择系统提供的保存位置")
                withContext(Dispatchers.IO) {
                    val file = store.completedFile(record)
                    val output = context.contentResolver.openOutputStream(uri, "w") ?: throw IOException("destination unavailable")
                    output.use { out -> file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024); var bytes = 0L; var last = 0L
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer); if (read < 0) break
                            out.write(buffer, 0, read); bytes += read
                            val now = System.nanoTime()
                            if (now - last >= 500_000_000L) { mutableState.value = mutableState.value.copy(bytes = bytes); last = now }
                        }
                    } }
                    ensureActive()
                }
                phase = Phase.COMPLETE; message = "导出完成，本机原文件仍保留"
            } catch (e: CancellationException) { phase = Phase.PAUSED; message = "导出已中断；目标位置可能有未完成文件，请检查后重新导出" }
            catch (_: Exception) { message = "导出失败，请检查目标空间和授权；目标位置可能有未完成文件" }
            finally {
                task = null
                mutableState.value = mutableState.value.copy(phase = phase, bytes = if (phase == Phase.COMPLETE) record.bytes else mutableState.value.bytes, message = message, revision = mutableState.value.revision + 1)
            }
        }.also { it.start() }
        return true
    }
}

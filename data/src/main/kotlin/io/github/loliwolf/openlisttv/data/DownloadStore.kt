package io.github.loliwolf.openlisttv.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.loliwolf.openlisttv.core.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class DownloadStatus(val label: String) { PAUSED("已暂停"), RUNNING("下载中"), COMPLETE("已完成"), FAILED("需重试") }
data class SavedDownload(val id: String, val identity: String, val siteLabel: String, val entry: Entry,
    val status: DownloadStatus, val bytes: Long, val total: Long, val etag: String, val message: String, val sourceKey: String = "")

/** Explicit user downloads, not disposable cache. Never store tokens or signed URLs here. */
class DownloadStore(context: Context, name: String = "downloads.db", val directory: File = File(context.filesDir, "downloads")) : SQLiteOpenHelper(context.applicationContext, name, null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE downloads(id TEXT PRIMARY KEY,identity TEXT,site_label TEXT,path TEXT,name TEXT,size INTEGER,modified TEXT,status TEXT,bytes INTEGER,total INTEGER,etag TEXT,message TEXT,created INTEGER,source_key TEXT NOT NULL DEFAULT '')")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { if (oldVersion < 2) db.execSQL("ALTER TABLE downloads ADD COLUMN source_key TEXT NOT NULL DEFAULT ''") }
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // A killed process cannot still own a transfer. Resuming always needs a user action.
        db.execSQL("UPDATE downloads SET status='PAUSED',message='传输已中断，可手动继续' WHERE status='RUNNING'")
        // Recover the narrow crash window after a verified file was atomically renamed
        // but before SQLite recorded completion. Never infer completion from a .part.
        db.rawQuery("SELECT id FROM downloads WHERE status!='COMPLETE' LIMIT 100", null).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0); val saved = file(id, false)
                if (saved.isFile) db.update("downloads", ContentValues().apply { put("status", "COMPLETE"); put("bytes", saved.length()); put("total", saved.length()); put("message", "本机文件已保存") }, "id=?", arrayOf(id))
            }
        }
    }
    @Synchronized fun list(): List<SavedDownload> = readableDatabase.rawQuery("SELECT * FROM downloads ORDER BY created DESC,id LIMIT 100", null).use { c -> buildList { while (c.moveToNext()) add(c.record()) } }
    @Synchronized fun get(id: String): SavedDownload? = readableDatabase.rawQuery("SELECT * FROM downloads WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.record() else null }
    @Synchronized fun create(site: Site, entry: Entry): SavedDownload {
        require(!entry.directory)
        if (list().size >= 100) throw ClientFailure(FailureKind.CAPACITY, "最多保留 100 条下载记录，请在本机下载中删除不需要的项目")
        if (!directory.isDirectory && !directory.mkdirs()) throw ClientFailure(FailureKind.CAPACITY, "无法创建本机下载目录")
        val id = UUID.randomUUID().toString()
        writableDatabase.insertOrThrow("downloads", null, ContentValues().apply {
            put("id", id); put("identity", identity(site)); put("site_label", site.label)
            put("path", entry.path); put("name", entry.name); put("size", entry.size); put("modified", entry.modified)
            put("status", DownloadStatus.PAUSED.name); put("bytes", 0); put("total", -1); put("etag", ""); put("message", "等待下载"); put("created", System.currentTimeMillis())
        })
        return checkNotNull(get(id))
    }
    @Synchronized fun metadata(id: String, total: Long, etag: String, sourceKey: String = "") {
        writableDatabase.update("downloads", ContentValues().apply { put("total", total); put("etag", etag); put("source_key", sourceKey); put("status", DownloadStatus.RUNNING.name); put("message", "") }, "id=?", arrayOf(id))
    }
    @Synchronized fun settle(id: String, status: DownloadStatus, message: String = "") {
        val file = file(id, partial = status != DownloadStatus.COMPLETE)
        writableDatabase.update("downloads", ContentValues().apply { put("status", status.name); put("bytes", file.length()); if (status == DownloadStatus.COMPLETE) put("total", file.length()); put("message", message.take(240)) }, "id=?", arrayOf(id))
    }
    fun file(id: String, partial: Boolean): File {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false))
        return File(directory, "$id.${if (partial) "part" else "media"}")
    }
    fun completedFile(record: SavedDownload): File {
        val file = file(record.id, false)
        if (record.status != DownloadStatus.COMPLETE || !file.isFile || file.length() != record.bytes) throw ClientFailure(FailureKind.ACCESS, "本机下载文件丢失或已变化，请重新下载")
        return file
    }
    @Synchronized fun remove(id: String) {
        // Only called after the user confirms this record and the writer is joined.
        listOf(file(id, true), file(id, false)).forEach { if (it.exists() && !it.delete()) throw ClientFailure(FailureKind.CAPACITY, "无法删除本机文件，请检查存储") }
        writableDatabase.delete("downloads", "id=?", arrayOf(id))
    }
    private fun Cursor.record(): SavedDownload {
        fun str(name: String) = getString(getColumnIndexOrThrow(name))
        fun number(name: String) = getLong(getColumnIndexOrThrow(name))
        return SavedDownload(str("id"), str("identity"), str("site_label"), Entry(str("path"), str("name"), false, number("size"), str("modified")), DownloadStatus.valueOf(str("status")), number("bytes"), number("total"), str("etag"), str("message"), str("source_key"))
    }
    companion object {
        fun identity(site: Site): String = MessageDigest.getInstance("SHA-256").digest(listOf(site.id, site.url, site.username).joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

/** Local playback reuses the same bounded browser/player contracts, without an API call. */
class LocalDownloadGateway(private val store: DownloadStore) : FileGateway {
    override suspend fun list(session: Session, path: String, password: String, page: Int, perPage: Int): Listing = withContext(Dispatchers.IO) {
        val all = store.list().filter { it.status == DownloadStatus.COMPLETE }.map { it.entry.copy(path = "/${it.id}", size = it.bytes) }
        Listing(all.drop((page - 1) * perPage).take(perPage), all.size)
    }
    override suspend fun resolve(session: Session, entry: Entry, password: String): MediaSource = withContext(Dispatchers.IO) {
        val record = store.get(entry.path.removePrefix("/")) ?: throw ClientFailure(FailureKind.ACCESS, "本机下载记录不存在")
        MediaSource(entry, store.completedFile(record).toURI().toString())
    }
}

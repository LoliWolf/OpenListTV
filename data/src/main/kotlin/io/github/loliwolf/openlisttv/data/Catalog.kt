package io.github.loliwolf.openlisttv.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.loliwolf.openlisttv.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import kotlin.random.Random

class Catalog(context: Context, name: String = "catalog.db") : SQLiteOpenHelper(context.applicationContext, name, null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE directories(site TEXT,path TEXT,complete INTEGER,total INTEGER,updated INTEGER,PRIMARY KEY(site,path))")
        db.execSQL("CREATE TABLE entries(site TEXT,path TEXT,parent TEXT,name TEXT,directory INTEGER,kind TEXT,size INTEGER,modified TEXT,modified_at INTEGER,sign TEXT,ordinal INTEGER,sort_name TEXT,thumbnail TEXT NOT NULL DEFAULT '',PRIMARY KEY(site,path))")
        db.execSQL("CREATE INDEX dir_entries ON entries(site,parent,sort_name)")
        db.execSQL("CREATE TABLE scan_queue(path TEXT PRIMARY KEY,done INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE scan_items(path TEXT PRIMARY KEY,shuffle_key INTEGER,round INTEGER DEFAULT 0)")
        db.execSQL("CREATE INDEX shuffle_items ON scan_items(round,shuffle_key)")
        db.execSQL("CREATE TABLE progress(site TEXT,path TEXT,modified TEXT,position INTEGER,updated INTEGER,PRIMARY KEY(site,path))")
    }
    override fun onConfigure(db: SQLiteDatabase) { db.setMaximumSize(Budgets.INDEX_BYTES) }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE entries ADD COLUMN thumbnail TEXT NOT NULL DEFAULT ''")
    }
    @Synchronized fun complete(site: String, path: String): Boolean = readableDatabase.rawQuery("SELECT complete FROM directories WHERE site=? AND path=?", arrayOf(site, path)).use { it.moveToFirst() && it.getInt(0) == 1 }
    @Synchronized fun begin(site: String, path: String) {
        writableDatabase.delete("entries", "site=? AND parent=?", arrayOf(site, path))
        writableDatabase.insertWithOnConflict("directories", null, ContentValues().apply { put("site", site); put("path", path); put("complete", 0); put("total", 0); put("updated", System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun insert(site: String, parent: String, entries: List<Entry>) {
        val db = writableDatabase
        if (count("SELECT COUNT(*) FROM entries", emptyArray()) + entries.size > Budgets.MAX_ENTRIES) throw ClientFailure(FailureKind.CAPACITY, "本地索引达到 10 万条上限，请清缓存或缩小目录范围")
        db.beginTransaction()
        try {
            entries.forEach { e ->
                db.insertOrThrow("entries", null, ContentValues().apply {
                    put("site", site); put("path", e.path); put("parent", parent); put("name", e.name); put("directory", if (e.directory) 1 else 0)
                    put("kind", e.kind.name); if (e.size >= 0 && !e.directory) put("size", e.size)
                    put("modified", e.modified); runCatching { Instant.parse(e.modified).toEpochMilli() }.getOrNull()?.let { put("modified_at", it) }
                    put("sign", e.sign); put("ordinal", e.ordinal); put("sort_name", NaturalOrder.key(e.name))
                    put("thumbnail", e.thumbnail)
                })
            }
            db.setTransactionSuccessful()
        } catch (e: android.database.sqlite.SQLiteFullException) { throw ClientFailure(FailureKind.CAPACITY, "索引达到 64 MiB 上限，请清缓存或缩小范围") }
        catch (e: android.database.sqlite.SQLiteConstraintException) { throw ClientFailure(FailureKind.SERVER, "目录在读取时发生变化，请重新加载") }
        finally { db.endTransaction() }
    }
    @Synchronized fun finish(site: String, path: String, total: Int) { writableDatabase.update("directories", ContentValues().apply { put("complete", 1); put("total", total); put("updated", System.currentTimeMillis()) }, "site=? AND path=?", arrayOf(site, path)) }
    @Synchronized fun page(site: String, path: String, offset: Int, limit: Int, sort: SortOrder, filter: EntryFilter? = null): Listing {
        val extra = when (filter) { EntryFilter.FOLDERS -> " AND directory=1"; EntryFilter.MEDIA -> " AND directory=0 AND kind!='OTHER' AND substr(name,1,2)!='._'"; EntryFilter.IMAGES -> " AND kind='IMAGE' AND substr(name,1,2)!='._'"; null -> "" }
        val where = "site=? AND parent=?$extra"
        val args = arrayOf(site, path)
        val total = count("SELECT COUNT(*) FROM entries WHERE $where", args)
        val direction = if (sort.descending) "DESC" else "ASC"
        val primary = when (sort.field) { SortField.NAME -> "sort_name $direction"; SortField.MODIFIED -> "modified_at IS NULL, modified_at $direction"; SortField.SIZE -> "size IS NULL, size $direction"; SortField.SERVER -> "ordinal ASC" }
        val order = (if (sort.foldersFirst) "directory DESC," else "") + "$primary,sort_name ASC,path ASC"
        val entries = readableDatabase.rawQuery("SELECT * FROM entries WHERE $where ORDER BY $order LIMIT ? OFFSET ?", args + arrayOf(limit.coerceIn(1, 100).toString(), offset.coerceAtLeast(0).toString())).use { c -> buildList { while (c.moveToNext()) add(c.entry()) } }
        return Listing(entries, total)
    }
    @Synchronized fun clear(site: String? = null, clearHistory: Boolean = false) {
        val db = writableDatabase
        if (site == null) { db.delete("entries", null, null); db.delete("directories", null, null) }
        else { db.delete("entries", "site=?", arrayOf(site)); db.delete("directories", "site=?", arrayOf(site)); if (clearHistory) db.delete("progress", "site=?", arrayOf(site)) }
        db.delete("scan_items", null, null); db.delete("scan_queue", null, null)
        if (site == null) db.execSQL("VACUUM")
    }
    @Synchronized fun startScan(root: String) { writableDatabase.delete("scan_items", null, null); writableDatabase.delete("scan_queue", null, null); enqueue(root) }
    @Synchronized fun enqueue(path: String) { writableDatabase.insertWithOnConflict("scan_queue", null, ContentValues().apply { put("path", path); put("done", 0) }, SQLiteDatabase.CONFLICT_IGNORE) }
    @Synchronized fun nextDirectory(): String? = readableDatabase.rawQuery("SELECT path FROM scan_queue WHERE done=0 LIMIT 1", null).use { if (it.moveToFirst()) it.getString(0) else null }
    @Synchronized fun markDirectory(path: String) { writableDatabase.update("scan_queue", ContentValues().apply { put("done", 1) }, "path=?", arrayOf(path)) }
    @Synchronized fun addScanned(e: Entry) { writableDatabase.insertWithOnConflict("scan_items", null, ContentValues().apply { put("path", e.path); put("shuffle_key", Random.nextLong()); put("round", 0) }, SQLiteDatabase.CONFLICT_IGNORE) }
    @Synchronized fun scannedCount() = count("SELECT COUNT(*) FROM scan_items", emptyArray())
    @Synchronized fun shuffled(site: String, round: Int, scanning: Boolean): Entry? {
        val where = if (scanning) "" else " WHERE s.round<>$round"
        val order = if (scanning) "random()" else "s.shuffle_key"
        val e = readableDatabase.rawQuery("SELECT e.* FROM scan_items s JOIN entries e ON e.path=s.path AND e.site=?$where ORDER BY $order LIMIT 1", arrayOf(site)).use { if (it.moveToFirst()) it.entry() else null }
        if (e != null && !scanning) writableDatabase.update("scan_items", ContentValues().apply { put("round", round); put("shuffle_key", Random.nextLong()) }, "path=?", arrayOf(e.path))
        return e
    }
    @Synchronized fun position(site: String, e: Entry): Long = readableDatabase.rawQuery("SELECT position,modified FROM progress WHERE site=? AND path=?", arrayOf(site, e.path)).use { if (it.moveToFirst() && it.getString(1) == e.modified) it.getLong(0) else 0L }
    @Synchronized fun subtitles(site: String, parent: String): List<Entry> = readableDatabase.rawQuery("SELECT * FROM entries WHERE site=? AND parent=? AND (lower(name) LIKE '%.srt' OR lower(name) LIKE '%.vtt') ORDER BY sort_name LIMIT 32", arrayOf(site, parent)).use { c -> buildList { while (c.moveToNext()) add(c.entry()) } }
    @Synchronized fun savePosition(site: String, e: Entry, position: Long) {
        val db = writableDatabase
        if (position <= 0) db.delete("progress", "site=? AND path=?", arrayOf(site, e.path))
        else db.insertWithOnConflict("progress", null, ContentValues().apply { put("site", site); put("path", e.path); put("modified", e.modified); put("position", position); put("updated", System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_REPLACE)
        db.execSQL("DELETE FROM progress WHERE rowid NOT IN (SELECT rowid FROM progress ORDER BY updated DESC LIMIT 100)")
    }
    private fun count(sql: String, args: Array<String>) = readableDatabase.rawQuery(sql, args).use { it.moveToFirst(); it.getInt(0) }
    private fun Cursor.entry() = Entry(getString(getColumnIndexOrThrow("path")), getString(getColumnIndexOrThrow("name")), getInt(getColumnIndexOrThrow("directory")) == 1, getColumnIndexOrThrow("size").let { if (isNull(it)) -1 else getLong(it) }, getString(getColumnIndexOrThrow("modified")), getString(getColumnIndexOrThrow("sign")), getInt(getColumnIndexOrThrow("ordinal")), getString(getColumnIndexOrThrow("thumbnail")))
}

class FileRepository(val session: Session, private val gateway: FileGateway, val catalog: Catalog) {
    private val mutex = Mutex()
    private val passwords = object : LinkedHashMap<String, String>(16, .75f, true) { override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 128 }
    @Synchronized fun password(path: String): String = passwords.keys.filter { Paths.inside(it, path) }.maxByOrNull { it.length }?.let { passwords[it] }.orEmpty()
    @Synchronized fun remember(path: String, password: String) { passwords[path] = password }
    suspend fun ensure(path: String, force: Boolean = false, progress: (Int, Int) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!force && catalog.complete(session.site.id, path)) return@withLock
            catalog.begin(session.site.id, path)
            var page = 1; var read = 0; var total: Int? = null
            while (true) {
                currentCoroutineContext().ensureActive()
                val listing = gateway.list(session, path, password(path), page, Budgets.PAGE)
                if (total != null && total != listing.total) throw ClientFailure(FailureKind.SERVER, "目录内容已变化，请重新加载")
                total = listing.total
                catalog.insert(session.site.id, path, listing.entries)
                read += listing.entries.size
                progress(read, total)
                if (read >= total) {
                    if (read != total) throw ClientFailure(FailureKind.SERVER, "目录总数与返回内容不一致")
                    catalog.finish(session.site.id, path, total); break
                }
                if (listing.entries.isEmpty() || page >= Budgets.MAX_ENTRIES / Budgets.PAGE) throw ClientFailure(FailureKind.SERVER, "分页响应异常，请重新加载")
                page++
            }
        }
    }
    suspend fun page(path: String, offset: Int, sort: SortOrder, limit: Int = Budgets.PAGE, filter: EntryFilter? = null): Listing = withContext(Dispatchers.IO) { catalog.page(session.site.id, path, offset, limit, sort, filter) }
    suspend fun remotePage(path: String, offset: Int): Listing = gateway.list(session, path, password(path), offset / Budgets.PAGE + 1, Budgets.PAGE)
    fun reader(sort: SortOrder): DirectoryReader = object : DirectoryReader {
        override suspend fun page(path: String, offset: Int, filter: EntryFilter): Listing { ensure(path); return this@FileRepository.page(path, offset, sort, 1, filter) }
    }
    suspend fun resolve(e: Entry) = gateway.resolve(session, e, password(Paths.parent(e.path)))
    suspend fun subtitles(entry: Entry): List<Entry> { ensure(Paths.parent(entry.path)); return withContext(Dispatchers.IO) { catalog.subtitles(session.site.id, Paths.parent(entry.path)) } }
}

class ShuffleTraversal(private val root: String, private val repository: FileRepository, private val options: SlideshowOptions, parent: CoroutineScope) : TraversalSource {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + Dispatchers.IO)
    private var started = false
    @Volatile private var scanning = true
    @Volatile private var failure: ClientFailure? = null
    @Volatile private var discovered = 0
    private var round = 1
    override val description get() = "全树随机 · 已发现 $discovered 项" + if (scanning) "（扫描中，可能重复）" else "（扫描完成）"
    override suspend fun next(): Entry? {
        if (!started) {
            started = true
            withContext(Dispatchers.IO) { repository.catalog.startScan(root) }
            scope.launch {
                try {
                    while (true) {
                        ensureActive()
                        val path = repository.catalog.nextDirectory() ?: break
                        try {
                            repository.ensure(path)
                            var offset = 0
                            while (true) {
                                ensureActive()
                                val page = repository.page(path, offset, SortOrder(SortField.SERVER, foldersFirst = false))
                                for (entry in page.entries) {
                                    if (entry.directory && options.recursive) {
                                        if (Paths.depth(root, entry.path) >= Budgets.MAX_DEPTH) throw ClientFailure(FailureKind.CAPACITY, "扫描达到目录深度上限，请缩小范围")
                                        repository.catalog.enqueue(entry.path)
                                    } else if (options.accepts(entry)) repository.catalog.addScanned(entry)
                                }
                                offset += page.entries.size
                                discovered = repository.catalog.scannedCount()
                                if (offset >= page.total || page.entries.isEmpty()) break
                            }
                        } catch (e: ClientFailure) { if (e.kind != FailureKind.ACCESS) throw e }
                        repository.catalog.markDirectory(path)
                        delay(30)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failure = e as? ClientFailure ?: ClientFailure(FailureKind.CAPACITY, "扫描无法继续，请清理索引或缩小范围") }
                finally { scanning = false }
            }
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            failure?.let { throw it }
            val e = withContext(Dispatchers.IO) { repository.catalog.shuffled(repository.session.site.id, round, scanning) }
            if (e != null) return e
            if (!scanning) {
                if (repository.catalog.scannedCount() == 0) return null
                round++
            } else delay(100)
        }
    }
    override fun close() { scope.cancel() }
}

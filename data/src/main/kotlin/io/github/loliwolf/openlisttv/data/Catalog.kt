package io.github.loliwolf.openlisttv.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteDiskIOException
import io.github.loliwolf.openlisttv.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.onTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.time.Instant
import kotlin.random.Random

internal class IndexBudgetReached(message: String) : Exception(message)

class Catalog(context: Context, name: String = "catalog.db", private val entryLimit: Int = Budgets.MAX_ENTRIES,
    private val byteLimit: Long = Budgets.INDEX_BYTES) : SQLiteOpenHelper(context.applicationContext, name, null, 4) {
    private val databaseFile = context.applicationContext.getDatabasePath(name)
    private var entryCount: Int? = null
    private val paging = CatalogPaging()
    private val scans = ScanIndex({ writableDatabase }, entryLimit) { site, root, bytes, work ->
        if (bytes > 0) {
            val needed = minOf(2L * 1024 * 1024, byteLimit / 4) + bytes
            while (indexFreeBytes() < needed) {
                if (databaseFile.parentFile!!.usableSpace < Budgets.DISK_RESERVE_BYTES) throw ClientFailure(FailureKind.CAPACITY, "本机存储空间不足，请释放空间后继续")
                if (!evictColdDirectory(site, root)) throw IndexBudgetReached("达到本地索引磁盘预算")
            }
        }
        withIndexSpace(site, root, work)
    }
    private val counts = object : LinkedHashMap<List<String>, Int>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<String>, Int>?) = size > 128
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE directories(site TEXT,path TEXT,complete INTEGER,total INTEGER,updated INTEGER,PRIMARY KEY(site,path))")
        db.execSQL("CREATE TABLE entries(site TEXT,path TEXT,parent TEXT,name TEXT,directory INTEGER,kind TEXT,size INTEGER,modified TEXT,modified_at INTEGER,sign TEXT,ordinal INTEGER,sort_name TEXT,thumbnail TEXT NOT NULL DEFAULT '',PRIMARY KEY(site,path))")
        db.execSQL("CREATE INDEX dir_entries ON entries(site,parent,sort_name)")
        db.execSQL("CREATE TABLE scan_queue(path TEXT PRIMARY KEY,done INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE scan_items(path TEXT PRIMARY KEY,shuffle_key INTEGER,round INTEGER DEFAULT 0,size INTEGER DEFAULT -1,modified TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE INDEX shuffle_items ON scan_items(round,shuffle_key)")
        db.execSQL("CREATE TABLE progress(site TEXT,path TEXT,modified TEXT,position INTEGER,updated INTEGER,PRIMARY KEY(site,path))")
        ScanIndex.create(db)
    }
    override fun onConfigure(db: SQLiteDatabase) { db.setMaximumSize(byteLimit) }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE entries ADD COLUMN thumbnail TEXT NOT NULL DEFAULT ''")
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE scan_items ADD COLUMN size INTEGER DEFAULT -1")
            db.execSQL("ALTER TABLE scan_items ADD COLUMN modified TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 4) ScanIndex.create(db)
    }
    @Synchronized fun complete(site: String, path: String): Boolean = readableDatabase.rawQuery("SELECT complete FROM directories WHERE site=? AND path=?", arrayOf(site, path)).use { it.moveToFirst() && it.getInt(0) == 1 }
    @Synchronized fun begin(site: String, path: String) {
        entryCount = null; counts.clear(); paging.invalidate()
        writableDatabase.delete("entries", "site=? AND parent=?", arrayOf(site, path))
        writableDatabase.insertWithOnConflict("directories", null, ContentValues().apply { put("site", site); put("path", path); put("complete", 0); put("total", 0); put("updated", System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun insert(site: String, parent: String, entries: List<Entry>) {
        val db = writableDatabase
        if (count("SELECT COUNT(*) FROM directories WHERE site=? AND path=?", arrayOf(site, parent)) == 0)
            throw ClientFailure(FailureKind.SERVER, "目录索引已释放，请重新加载")
        while ((entryCount ?: count("SELECT COUNT(*) FROM entries", emptyArray()).also { entryCount = it }) + entries.size > entryLimit) {
            if (!evictColdDirectory(site, parent)) throw ClientFailure(FailureKind.CAPACITY, "当前目录超出本地索引预算；全树随机可分批读取元数据")
        }
        try {
            withIndexSpace(site, parent) {
                transaction {
                    db.compileStatement("INSERT INTO entries(site,path,parent,name,directory,kind,size,modified,modified_at,sign,ordinal,sort_name,thumbnail) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)").use { statement ->
                        entries.forEach { e ->
                            statement.clearBindings()
                            statement.bindString(1, site); statement.bindString(2, e.path); statement.bindString(3, parent); statement.bindString(4, e.name)
                            statement.bindLong(5, if (e.directory) 1 else 0); statement.bindString(6, e.kind.name)
                            if (e.size >= 0 && !e.directory) statement.bindLong(7, e.size)
                            statement.bindString(8, e.modified)
                            if (e.modified.isNotEmpty()) runCatching { Instant.parse(e.modified).toEpochMilli() }.getOrNull()?.let { statement.bindLong(9, it) }
                            statement.bindString(10, e.sign); statement.bindLong(11, e.ordinal.toLong())
                            statement.bindString(12, NaturalOrder.key(e.name)); statement.bindString(13, e.thumbnail)
                            statement.executeInsert()
                        }
                    }
                }
            }
            entryCount = entryCount?.plus(entries.size); counts.clear(); paging.invalidate()
        } catch (e: IndexBudgetReached) { throw ClientFailure(FailureKind.CAPACITY, "当前目录的完整排序索引超出预算；可使用服务器顺序或全树随机", e) }
        catch (e: android.database.sqlite.SQLiteConstraintException) { throw ClientFailure(FailureKind.SERVER, "目录在读取时发生变化，请重新加载") }
    }
    @Synchronized fun finish(site: String, path: String, total: Int) { writableDatabase.update("directories", ContentValues().apply { put("complete", 1); put("total", total); put("updated", System.currentTimeMillis()) }, "site=? AND path=?", arrayOf(site, path)) }
    @Synchronized fun page(site: String, path: String, offset: Int, limit: Int, sort: SortOrder, filter: EntryFilter? = null, query: BrowserQuery = BrowserQuery()): Listing {
        val extra = when (filter) { EntryFilter.FOLDERS -> " AND directory=1"; EntryFilter.MEDIA -> " AND directory=0 AND kind!='OTHER' AND substr(name,1,2)!='._'"; EntryFilter.IMAGES -> " AND kind='IMAGE' AND substr(name,1,2)!='._'"; null -> "" }
        val type = when (query.type) {
            BrowserType.ALL -> ""
            BrowserType.FOLDER -> " AND directory=1"
            else -> " AND directory=0 AND kind=?"
        }
        // instr treats %, _, quotes and backslashes literally; no SQL interpolation.
        val search = if (query.text.isEmpty()) "" else " AND instr(lower(name),lower(?))>0"
        val where = "site=? AND parent=?$extra$type$search"
        val args = buildList {
            add(site); add(path)
            if (query.type !in listOf(BrowserType.ALL, BrowserType.FOLDER)) add(query.type.name)
            if (query.text.isNotEmpty()) add(query.text)
        }.toTypedArray()
        val countKey = listOf(where) + args
        val total = counts.getOrPut(countKey) { count("SELECT COUNT(*) FROM entries WHERE $where", args) }
        val entries = paging.select(writableDatabase, where, args, offset, limit, sort)
        return Listing(entries, total)
    }
    internal val lastPageUsedCursor get() = paging.lastUsedCursor
    @Synchronized fun clear(site: String? = null, clearHistory: Boolean = false, preserveScanSnapshot: Boolean = false) {
        if (preserveScanSnapshot && !clearHistory) scans.detach() else scans.clear()
        entryCount = null; counts.clear(); paging.reset()
        val db = writableDatabase
        if (site == null) { db.delete("entries", null, null); db.delete("directories", null, null) }
        else { db.delete("entries", "site=?", arrayOf(site)); db.delete("directories", "site=?", arrayOf(site)); if (clearHistory) db.delete("progress", "site=?", arrayOf(site)) }
        db.delete("scan_items", null, null); db.delete("scan_queue", null, null)
        if (site == null) db.execSQL("VACUUM")
    }
    @Synchronized fun reserveScan(): Long = scans.reserve()
    @Synchronized fun startScan(root: String, site: String = "", generation: Long = reserveScan()): Long {
        scans.open(generation, site, root, null, SlideshowOptions(), System.currentTimeMillis())
        return generation
    }
    @Synchronized internal fun openScan(generation: Long, root: String, site: String, key: String?, options: SlideshowOptions, now: Long = System.currentTimeMillis()) = scans.open(generation, site, root, key, options, now)
    @Synchronized internal fun scanView(generation: Long) = scans.info(generation)
    @Synchronized internal fun finishScan(generation: Long, limited: String?, now: Long = System.currentTimeMillis()) = scans.finish(generation, limited, now)
    @Synchronized internal fun failedScan(generation: Long, message: String? = null) = scans.failed(generation, message)
    @Synchronized internal fun refreshScan(generation: Long) = scans.refresh(generation)
    @Synchronized internal fun pickScanned(generation: Long) = scans.pick(generation)
    @Synchronized fun invalidateScanSnapshot() = scans.invalidate()
    @Synchronized fun cancelScan(generation: Long) = scans.cancel(generation)
    @Synchronized fun nextDirectory(generation: Long): String? = scans.nextDirectory(generation)
    @Synchronized fun markDirectory(generation: Long, path: String) = scans.markDirectory(generation, path)
    @Synchronized fun appendScan(generation: Long, site: String, root: String, entries: List<Entry>, options: SlideshowOptions): Boolean = scans.append(generation, entries, options)
    @Synchronized fun scannedCount(generation: Long? = null): Int = if (generation == null) scans.view.count else scans.info(generation).count
    @Synchronized fun shuffled(site: String, round: Int, scanning: Boolean, generation: Long? = null): Entry? = scans.pickRound(site, round, scanning, generation)
    private fun transaction(block: () -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    private fun indexFreeBytes(): Long {
        fun pragma(name: String) = readableDatabase.rawQuery("PRAGMA $name", null).use { it.moveToFirst(); it.getLong(0) }
        return (pragma("max_page_count") - pragma("page_count") + pragma("freelist_count")) * pragma("page_size")
    }
    private fun evictColdDirectory(site: String, protectedPath: String): Boolean {
        val oldest = readableDatabase.rawQuery("SELECT site,path FROM directories WHERE NOT(site=? AND path=?) ORDER BY updated LIMIT 1", arrayOf(site, protectedPath))
            .use { if (it.moveToFirst()) arrayOf(it.getString(0), it.getString(1)) else null } ?: return false
        transaction {
            writableDatabase.delete("entries", "site=? AND parent=?", oldest)
            writableDatabase.delete("directories", "site=? AND path=?", oldest)
        }
        entryCount = null; counts.clear(); paging.invalidate()
        return true
    }
    private fun withIndexSpace(site: String, protectedPath: String, block: () -> Unit) {
        while (true) {
            try { block(); return }
            catch (e: SQLiteFullException) {
                entryCount = null; counts.clear(); paging.invalidate()
                if (databaseFile.parentFile!!.usableSpace < Budgets.DISK_RESERVE_BYTES) throw ClientFailure(FailureKind.CAPACITY, "本机存储空间不足，请释放空间后继续", e)
                if (!evictColdDirectory(site, protectedPath)) throw IndexBudgetReached("达到本地索引磁盘预算")
            }
            catch (e: SQLiteDiskIOException) { throw ClientFailure(FailureKind.CAPACITY, "无法读写本机索引，请检查存储状态", e) }
        }
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
    private fun Cursor.entry() = catalogEntry()
}

class FileRepository(val session: Session, private val gateway: FileGateway, val catalog: Catalog) {
    private val mutex = Mutex()
    private val passwords = object : LinkedHashMap<String, String>(16, .75f, true) { override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 128 }
    @Synchronized fun password(path: String): String = passwords.keys.filter { Paths.inside(it, path) }.maxByOrNull { it.length }?.let { passwords[it] }.orEmpty()
    @Synchronized fun remember(path: String, password: String) { passwords[path] = password }
    @Synchronized internal fun scanIdentity(root: String, options: SlideshowOptions): String? {
        // Password-protected directory access is session-only: never persist a password hash
        // or reuse its candidate snapshot in a later session lacking that password.
        if (passwords.values.any { it.isNotEmpty() }) return null
        val parts = listOf(session.site.id, session.site.url, session.site.username, session.basePath, root, options.mode.name, options.recursive.toString(), options.imageOnly.toString())
        return java.security.MessageDigest.getInstance("SHA-256").digest(parts.joinToString("") { "${it.length}:$it" }.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    suspend fun ensure(path: String, force: Boolean = false, progress: (Int, Int) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (force) catalog.invalidateScanSnapshot()
            if (!force && catalog.complete(session.site.id, path)) return@withLock
            catalog.begin(session.site.id, path)
            var read = 0
            scanPages(path) { listing ->
                if (listing.total > Budgets.MAX_ENTRIES) throw ClientFailure(FailureKind.CAPACITY, "当前目录超过 10 万条完整排序预算；可使用服务器顺序或全树随机")
                catalog.insert(session.site.id, path, listing.entries)
                read += listing.entries.size
                progress(read, listing.total)
                if (read == listing.total) catalog.finish(session.site.id, path, listing.total)
            }
        }
    }
    /** Stream only one metadata page. No browser-index writes and no tree-sized collection. */
    internal suspend fun scanPages(path: String, consume: suspend (Listing) -> Unit) {
        var page = 1; var read = 0; var total: Int? = null; var pageSize = Budgets.INDEX_PAGE
        while (true) {
            currentCoroutineContext().ensureActive()
            val listing = try { gateway.list(session, path, password(path), page, pageSize) }
            catch (e: ClientFailure) {
                if (e.kind != FailureKind.RESOURCE_LIMIT || pageSize <= Budgets.PAGE) throw e
                pageSize = Budgets.PAGE; page = read / pageSize + 1; continue
            }
            // Some compatible servers clamp per_page. Retry page 1 before consuming anything.
            if (pageSize > Budgets.PAGE && listing.entries.size < pageSize && read + listing.entries.size < listing.total) {
                pageSize = Budgets.PAGE; page = read / pageSize + 1; continue
            }
            if (listing.total < 0 || listing.entries.size > pageSize || total != null && total != listing.total ||
                read.toLong() + listing.entries.size > listing.total || listing.entries.size < pageSize && read + listing.entries.size < listing.total)
                throw ClientFailure(FailureKind.SERVER, "目录内容变化或分页响应不完整，请重新加载")
            total = listing.total
            consume(listing)
            read += listing.entries.size
            if (read == total) return
            page++
            // Give foreground image decoding/network work an opportunity between batches.
            yield()
        }
    }
    suspend fun page(path: String, offset: Int, sort: SortOrder, limit: Int = Budgets.PAGE, filter: EntryFilter? = null, query: BrowserQuery = BrowserQuery()): Listing = withContext(Dispatchers.IO) { catalog.page(session.site.id, path, offset, limit, sort, filter, query) }
    suspend fun remotePage(path: String, offset: Int): Listing = gateway.list(session, path, password(path), offset / Budgets.PAGE + 1, Budgets.PAGE)
    fun reader(sort: SortOrder): DirectoryReader = object : DirectoryReader {
        private val windows = object : LinkedHashMap<Pair<String, EntryFilter>, Pair<Int, Listing>>(4, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, EntryFilter>, Pair<Int, Listing>>?) = size > 2
        }
        override suspend fun page(path: String, offset: Int, filter: EntryFilter): Listing {
            val key = path to filter
            val start = offset / Budgets.PAGE * Budgets.PAGE
            val cached = windows[key]?.takeIf { it.first == start }
            val listing = cached?.second ?: run {
                ensure(path)
                this@FileRepository.page(path, start, sort, Budgets.PAGE, filter).also { windows[key] = start to it }
            }
            return Listing(listing.entries.getOrNull(offset - start)?.let(::listOf).orEmpty(), listing.total)
        }
    }
    suspend fun resolve(e: Entry) = gateway.resolve(session, e, password(Paths.parent(e.path)))
    suspend fun subtitles(entry: Entry): List<Entry> { ensure(Paths.parent(entry.path)); return withContext(Dispatchers.IO) { catalog.subtitles(session.site.id, Paths.parent(entry.path)) } }
}

class ShuffleTraversal(private val root: String, private val repository: FileRepository, private val options: SlideshowOptions, parent: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val refreshIntervalMillis: Long = options.autoRefreshMinutes * 60_000L) : RefreshableTraversal {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + Dispatchers.IO)
    private val nextLock = Mutex()
    private var reservedPick: Entry? = null
    @Volatile private var started = false
    private val refreshRequests = Channel<Unit>(1)
    private val busy = AtomicBoolean(true)
    @Volatile private var automaticPaused = false
    @Volatile private var failure: ClientFailure? = null
    @Volatile private var view = ScanView(0, 0, true, false, false, options.mode == TraversalMode.RESERVOIR)
    @Volatile private var generation: Long? = null
    @Volatile private var closed = false
    override val indexStatus get() = view.let { v -> IndexStatus(v.count, v.seen, v.scanning, v.refreshing,
        v.scannedCount, v.scannedSeen, v.directories, v.updatedAt, v.reused, v.limited, v.message, automaticPaused) }
    override fun requestRefresh(): RefreshRequest {
        if (closed || !started || failure != null) return RefreshRequest.UNAVAILABLE
        if (!busy.compareAndSet(false, true)) return RefreshRequest.BUSY
        if (!refreshRequests.trySend(Unit).isSuccess) { busy.set(false); return RefreshRequest.UNAVAILABLE }
        return RefreshRequest.STARTED
    }
    override val warning get() = view.limited?.let { "$it；继续轮播 ${view.count} 个候选，未确认全部范围" }
    override val description get(): String {
        val v = view
        val label = if (v.sampling) "公平抽样 · 候选 ${v.count} / 已读可播 ${v.seen} 项（可能重复，不保证全部播到）" else "全树随机 · 已发现 ${v.count} 项"
        return label + when {
            v.refreshing -> " · 正在前台重扫，先播旧快照"
            v.limited != null -> "（${v.limited}；未覆盖全部范围）"
            v.scanning -> "（扫描中，仅已读范围）"
            v.reused -> "（复用快照）"
            else -> "（扫描完成）"
        }
    }
    override suspend fun next(): Entry? = nextLock.withLock { takeNext() }
    private suspend fun takeNext(): Entry? {
        currentCoroutineContext().ensureActive()
        if (closed) throw CancellationException("Closed traversal")
        reservedPick?.let { reservedPick = null; return it }
        if (!started) {
            // Store ownership inside IO, so prompt cancellation cannot lose the lease.
            val reserved = generation ?: repository.catalog.reserveScan().also { generation = it }
            try { withContext(Dispatchers.IO) { view = repository.catalog.openScan(reserved, root, repository.session.site.id, repository.scanIdentity(root, options), options, clock()) } }
            catch (e: IndexBudgetReached) { throw ClientFailure(FailureKind.CAPACITY, "索引预算不足，尚未发现可播放资源；请选择更具体的目录或清理索引", e) }
            if (closed) { generation?.let(repository.catalog::cancelScan); throw CancellationException("Closed traversal") }
            val lease = checkNotNull(generation)
            started = true
            scope.launch { coordinateRefresh(lease) }
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            failure?.let { throw it }
            // Keep a selected/round-marked item across prompt cancellation at the IO boundary.
            val observed = withContext(Dispatchers.IO) {
                synchronized(repository.catalog) {
                    reservedPick = repository.catalog.pickScanned(checkNotNull(generation))
                    updateView(checkNotNull(generation))
                }
            }
            if (closed) throw CancellationException("Closed traversal")
            failure?.let { throw it }
            reservedPick?.let { reservedPick = null; return it }
            if (!observed.scanning) {
                observed.limited?.let { throw ClientFailure(FailureKind.CAPACITY, "$it，尚未发现可播放资源；请选择更具体的目录") }
                return null
            }
            delay(50)
        }
    }
    private fun updateView(lease: Long): ScanView = synchronized(repository.catalog) {
        repository.catalog.scanView(lease).also { view = it }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun coordinateRefresh(lease: Long) {
        var firstWait = !view.scanning
        if (view.scanning) scan(lease, view.refreshing)
        busy.set(false)
        if (failure != null) return
        while (currentCoroutineContext().isActive) {
            // One cancellable monotonic delay, never a background service or frequent poll.
            val interval = refreshIntervalMillis.takeIf { it > 0 && !automaticPaused }
            val wait = if (firstWait && interval != null) {
                val age = view.updatedAt?.let { (clock() - it).coerceAtLeast(0) } ?: 0
                (interval - age).coerceAtLeast(1)
            } else interval
            firstWait = false
            val manual = if (wait == null) { refreshRequests.receive(); true }
                else select { refreshRequests.onReceive { true }; onTimeout(wait) { false } }
            // A click racing the timeout owns the same single slot: consume its signal,
            // never launch a second scan or leave a redundant queued refresh behind.
            if (!manual && !busy.compareAndSet(false, true)) refreshRequests.receive()
            automaticPaused = false
            try {
                if (repository.catalog.refreshScan(lease)) {
                    updateView(lease)
                    scan(lease, true)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { refreshFailed(lease, scanFailure(e)) }
            finally { busy.set(false) }
        }
    }
    private suspend fun scan(lease: Long, refreshing: Boolean) {
        var limit: String? = null
        var problem: ClientFailure? = null
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val path = repository.catalog.nextDirectory(lease) ?: break
                try {
                    repository.scanPages(path) { page ->
                        currentCoroutineContext().ensureActive()
                        val reached = repository.catalog.appendScan(lease, repository.session.site.id, root, page.entries, options)
                        updateView(lease)
                        if (reached) throw IndexBudgetReached("达到候选或目录数量预算")
                        delay(if (refreshing) 40 else 10)
                    }
                } catch (e: ClientFailure) {
                    // A denied root is not an authoritative empty library. During refresh,
                    // inaccessible subtrees also prevent replacing a useful old snapshot.
                    if (e.kind != FailureKind.ACCESS || refreshing || path == root) throw e
                }
                repository.catalog.markDirectory(lease, path)
                updateView(lease)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: IndexBudgetReached) { limit = e.message }
        catch (e: Exception) { problem = scanFailure(e) }
        try {
            if (problem == null) repository.catalog.finishScan(lease, limit, clock())
            else if (refreshing) { refreshFailed(lease, problem); return }
            else { failure = problem; repository.catalog.failedScan(lease) }
            updateView(lease)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (refreshing) refreshFailed(lease, scanFailure(e)) else failure = scanFailure(e)
        }
    }
    private fun refreshFailed(lease: Long, error: ClientFailure) {
        automaticPaused = error.kind in setOf(FailureKind.AUTH, FailureKind.ACCESS, FailureKind.CHALLENGE, FailureKind.RATE_LIMIT, FailureKind.CAPACITY)
        val reason = when (error.kind) {
            FailureKind.AUTH, FailureKind.ACCESS, FailureKind.CHALLENGE -> "鉴权或目录权限异常"
            FailureKind.RATE_LIMIT -> "服务器限流"
            FailureKind.NETWORK -> "网络异常"
            FailureKind.CAPACITY -> "本机索引存储异常"
            else -> "目录变化、响应异常或索引预算不足"
        }
        // No raw server text/URLs; failure of an optional refresh is not playback failure.
        repository.catalog.failedScan(lease, "刷新失败：$reason；保留旧索引")
        updateView(lease)
    }
    private fun scanFailure(e: Exception) = e as? ClientFailure ?: when (e) {
        is SQLiteFullException, is SQLiteDiskIOException -> ClientFailure(FailureKind.CAPACITY, "本机索引读写失败，请检查存储空间", e)
        else -> ClientFailure(FailureKind.SERVER, "目录扫描失败，请重新加载后重试", e)
    }
    override fun close() { closed = true; scope.cancel(); refreshRequests.cancel(); generation?.let(repository.catalog::cancelScan) }
}

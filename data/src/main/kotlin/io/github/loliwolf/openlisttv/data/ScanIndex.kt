package io.github.loliwolf.openlisttv.data

import android.database.sqlite.SQLiteDatabase
import io.github.loliwolf.openlisttv.core.*
import kotlinx.coroutines.CancellationException
import kotlin.random.Random

internal data class ScanView(val count: Int, val seen: Long, val scanning: Boolean, val reused: Boolean, val refreshing: Boolean,
    val sampling: Boolean, val limited: String? = null, val complete: Boolean = false)

/** Two fixed disk banks: the last completed snapshot and one staged refresh. Catalog owns
 * synchronization and the shared 64MiB quota. Never publish cancelled/failed partial work. */
internal class ScanIndex(private val database: () -> SQLiteDatabase, private val entryLimit: Int,
    private val space: (String, String, Long, () -> Unit) -> Unit) {
    private var generation = 0L
    private var site = ""
    private var root = "/"
    private var identity: String? = null
    private var options = SlideshowOptions()
    private var playing = 0
    private var writing: Int? = null
    private var workingCount = 0
    private var workingSeen = 0L
    private var queueCount = 0
    private var playRound = 1
    private var sampler: ReservoirSampler? = null
    private var snapshotEligible = false
    var view = ScanView(0, 0, false, false, false, false)
        private set

    fun reserve() = ++generation
    fun cancel(lease: Long) { if (generation == lease) generation++ }
    private fun requireLease(lease: Long) { if (generation != lease) throw CancellationException("Superseded scan") }
    fun clear() {
        generation++; view = ScanView(0, 0, false, false, false, false)
        val db = database()
        db.delete("scan_snapshot", null, null)
        for (bank in 0..1) db.delete(table(bank), null, null)
        db.delete("scan_queue", null, null)
    }
    fun invalidate() { database().delete("scan_snapshot", null, null) }
    fun open(lease: Long, site: String, root: String, key: String?, options: SlideshowOptions, now: Long): ScanView {
        requireLease(lease)
        this.site = site; this.root = root; this.identity = key; this.options = options
        workingCount = 0; workingSeen = 0; queueCount = 0; playRound = 1
        snapshotEligible = key != null && options.reuseIndex
        val db = database()
        val cached = db.rawQuery("SELECT identity,bank,created,count,seen,limited,complete FROM scan_snapshot WHERE id=1", null).use { c ->
            if (c.moveToFirst()) Snapshot(c.getString(0), c.getInt(1), c.getLong(2), c.getInt(3), c.getLong(4), if (c.isNull(5)) null else c.getString(5), c.getInt(6)==1) else null
        }
        val age = cached?.let { now - it.created }
        val eligible = cached != null && snapshotEligible && !options.forceRescan && cached.identity == key && age != null && age in 0..STALE_MS &&
            cached.bank in 0..1 && cached.count in 0..entryLimit && count(table(cached.bank)) == cached.count
        val fresh = eligible && age!! <= FRESH_MS
        playing = if (eligible) cached!!.bank else 1 - (cached?.bank?.takeIf { it in 0..1 } ?: 1)
        writing = if (fresh) null else if (eligible) 1-playing else playing
        view = if (eligible) ScanView(cached!!.count, cached.seen, !fresh, true, !fresh, options.mode == TraversalMode.RESERVOIR, cached.limited, cached.complete)
            else ScanView(0, 0, true, false, false, options.mode == TraversalMode.RESERVOIR)
        sampler = if (options.mode == TraversalMode.RESERVOIR) ReservoirSampler(minOf(SAMPLE_SIZE, entryLimit)) else null
        if (!eligible || options.forceRescan) invalidate()
        if (writing != null) space(site, root, 0) { transaction {
            db.delete(table(writing!!), null, null); db.delete("scan_queue", null, null)
            if (!eligible) db.delete(table(1-writing!!), null, null)
            // Retire only obsolete ephemeral v3 scan payloads, never user config/history.
            db.delete("scan_items", null, null)
            db.execSQL("INSERT INTO scan_queue(path,done) VALUES(?,0)", arrayOf(root))
        } }.also { queueCount = 1 }
        if (eligible) playRound = minimumRound(playing) + 1
        return view
    }
    fun nextDirectory(lease: Long): String? {
        requireLease(lease)
        return database().rawQuery("SELECT path FROM scan_queue WHERE done=0 LIMIT 1", null).use { if (it.moveToFirst()) it.getString(0) else null }
    }
    fun markDirectory(lease: Long, path: String) { requireLease(lease); database().delete("scan_queue", "path=?", arrayOf(path)) }
    fun append(lease: Long, entries: List<Entry>, filterOptions: SlideshowOptions = options): Boolean {
        requireLease(lease)
        val bank = writing ?: throw CancellationException("No active scan")
        if (entries.map { it.path }.toSet().size != entries.size) throw ClientFailure(FailureKind.SERVER, "目录分页包含重复路径，请重新扫描")
        val media = entries.filter(filterOptions::accepts)
        val directories = entries.filter { it.directory && filterOptions.recursive }
        if (directories.any { Paths.depth(root, it.path) >= Budgets.MAX_DEPTH }) throw IndexBudgetReached("达到 128 层目录深度预算")
        data class Write(val slot: Int, val entry: Entry, val insert: Boolean)
        val writes = ArrayList<Write>()
        var nextCount = workingCount
        var reached = false
        for (entry in media) {
            workingSeen++
            val slot = sampler?.nextSlot() ?: if (sampler != null) continue else {
                if (nextCount >= entryLimit) { reached = true; continue }
                nextCount + 1
            }
            val insert = slot > nextCount
            writes.add(Write(slot, entry, insert))
            if (insert) nextCount++
        }
        val queued = directories.take((entryLimit - queueCount).coerceAtLeast(0))
        val needed = (writes.map { it.entry } + queued).sumOf { (it.path.length.toLong()*4 + it.modified.length*4 + 128)*2 }
        if (writes.isNotEmpty() || queued.isNotEmpty()) space(site, root, needed) { transaction {
            val db = database()
            db.compileStatement("INSERT INTO ${table(bank)}(rowid,path,shuffle_key,round,size,modified) VALUES(?,?,?,0,?,?)").use { insert ->
                db.compileStatement("UPDATE ${table(bank)} SET path=?,shuffle_key=?,round=0,size=?,modified=? WHERE rowid=?").use { replace ->
                    for (write in writes) {
                        val s = if (write.insert) insert else replace
                        s.clearBindings()
                        val base = if (write.insert) { s.bindLong(1, write.slot.toLong()); 1 } else 0
                        s.bindString(base+1, write.entry.path); s.bindLong(base+2, Random.nextLong())
                        s.bindLong(base+3, write.entry.size); s.bindString(base+4, write.entry.modified)
                        if (write.insert) s.executeInsert() else { s.bindLong(5, write.slot.toLong()); check(s.executeUpdateDelete() == 1) }
                    }
                }
            }
            db.compileStatement("INSERT INTO scan_queue(path,done) VALUES(?,0)").use { statement -> queued.forEach { statement.bindString(1, it.path); statement.executeInsert() } }
        } }
        workingCount = nextCount; queueCount += queued.size
        if (playing == bank) view = view.copy(count = workingCount, seen = workingSeen)
        return reached || queued.size < directories.size
    }
    fun finish(lease: Long, limited: String?, now: Long) {
        requireLease(lease)
        val bank = writing ?: return
        if (view.refreshing && limited != null) {
            // A quota-limited rebuild must not replace a useful completed cache with a
            // smaller prefix, nor extend the old snapshot's freshness timestamp.
            view = view.copy(scanning = false, refreshing = false, limited = "刷新受限：$limited；继续旧索引，目录变化尚未确认")
            writing = null; return
        }
        transaction {
            if (snapshotEligible) database().execSQL("INSERT OR REPLACE INTO scan_snapshot(id,identity,bank,created,count,seen,limited,complete) VALUES(1,?,?,?,?,?,?,?)",
                arrayOf(identity, bank, now, workingCount, workingSeen, limited, if (limited == null) 1 else 0))
            else invalidate()
        }
        playing = bank; writing = null; playRound = 1
        view = ScanView(workingCount, workingSeen, false, false, false, options.mode == TraversalMode.RESERVOIR, limited, limited == null)
    }
    fun failed(lease: Long) { requireLease(lease); view = view.copy(scanning = false, refreshing = false, complete = false) }
    fun info(lease: Long): ScanView { requireLease(lease); return view }
    fun pick(lease: Long): Entry? {
        requireLease(lease)
        if (view.count == 0) return null
        val random = view.sampling || view.scanning && !view.refreshing
        var item = select(playRound, random)
        if (item == null && !random) { playRound = minimumRound(playing) + 1; item = select(playRound, false) }
        return item
    }
    fun pickRound(site: String, round: Int, random: Boolean, lease: Long?): Entry? {
        lease?.let(::requireLease)
        if (this.site.isNotEmpty() && this.site != site) return null
        return select(round, random)
    }
    private fun select(round: Int, random: Boolean): Entry? {
        if (view.count == 0) return null
        val table = table(playing)
        val sql = if (random) "SELECT path,size,modified FROM $table WHERE rowid=?" else "SELECT path,size,modified FROM $table WHERE round=? ORDER BY shuffle_key LIMIT 1"
        val key = if (random) Random.nextInt(view.count) + 1 else round-1
        val result = database().rawQuery(sql, arrayOf(key.toString())).use { c ->
            if (c.moveToFirst()) Entry(c.getString(0), c.getString(0).substringAfterLast('/'), false, c.getLong(1), c.getString(2)) else null
        }
        if (result != null && !random) database().execSQL("UPDATE $table SET round=?,shuffle_key=? WHERE path=?", arrayOf(round, Random.nextLong(), result.path))
        return result
    }
    private fun count(table: String) = database().rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }
    private fun minimumRound(bank: Int) = database().rawQuery("SELECT MIN(round) FROM ${table(bank)}", null).use { it.moveToFirst(); if (it.isNull(0)) 0 else it.getInt(0) }
    private fun transaction(block: () -> Unit) { val db=database(); db.beginTransaction(); try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() } }
    private data class Snapshot(val identity: String, val bank: Int, val created: Long, val count: Int, val seen: Long, val limited: String?, val complete: Boolean)
    companion object {
        const val FRESH_MS = 6 * 60 * 60_000L
        const val STALE_MS = 24 * 60 * 60_000L
        const val SAMPLE_SIZE = 10_000
        fun table(bank: Int): String { require(bank in 0..1); return "candidate_$bank" }
        fun create(db: SQLiteDatabase) {
            for (bank in 0..1) {
                db.execSQL("CREATE TABLE ${table(bank)}(path TEXT PRIMARY KEY,shuffle_key INTEGER,round INTEGER DEFAULT 0,size INTEGER,modified TEXT NOT NULL DEFAULT '')")
                db.execSQL("CREATE INDEX candidate_${bank}_order ON ${table(bank)}(round,shuffle_key)")
            }
            db.execSQL("CREATE TABLE scan_snapshot(id INTEGER PRIMARY KEY CHECK(id=1),identity TEXT NOT NULL,bank INTEGER NOT NULL,created INTEGER NOT NULL,count INTEGER NOT NULL,seen INTEGER NOT NULL,limited TEXT,complete INTEGER NOT NULL)")
        }
    }
}

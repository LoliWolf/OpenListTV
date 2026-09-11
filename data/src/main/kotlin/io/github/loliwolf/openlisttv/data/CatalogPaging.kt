package io.github.loliwolf.openlisttv.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import io.github.loliwolf.openlisttv.core.*

/** Called only while holding Catalog's monitor. Anchors are keys, never bitmaps or URLs. */
internal class CatalogPaging {
    private data class Key(val sql: String, val descending: Boolean = false) {
        val parameter get() = if (sql.endsWith(" IS NULL")) "CAST(? AS INTEGER)" else "?"
        fun value(c: Cursor): String? {
            val missing = sql.endsWith(" IS NULL")
            val index = c.getColumnIndexOrThrow(sql.removeSuffix(" IS NULL"))
            return if (missing) if (c.isNull(index)) "1" else "0" else if (c.isNull(index)) null else c.getString(index)
        }
    }
    private data class Anchor(val query: List<String>, val offset: Int)
    private val anchors = object : LinkedHashMap<Anchor, List<String?>>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Anchor, List<String?>>?) = size > 256
    }
    private var attemptedIndex: String? = null
    internal var lastUsedCursor = false
        private set
    fun invalidate() { anchors.clear() }
    fun reset() { invalidate(); attemptedIndex = null }
    private fun keys(sort: SortOrder) = buildList {
        if (sort.foldersFirst) add(Key("directory", true))
        when (sort.field) {
            SortField.NAME -> add(Key("sort_name", sort.descending))
            SortField.SIZE -> { add(Key("size IS NULL")); add(Key("size", sort.descending)); add(Key("sort_name")) }
            SortField.MODIFIED -> { add(Key("modified_at IS NULL")); add(Key("modified_at", sort.descending)); add(Key("sort_name")) }
            SortField.SERVER -> { add(Key("ordinal")); add(Key("sort_name")) }
        }
        add(Key("path"))
    }
    fun select(db: SQLiteDatabase, where: String, args: Array<String>, offset: Int, limit: Int, sort: SortOrder): List<Entry> {
        val keys = keys(sort)
        val order = keys.joinToString(",") { "${it.sql} ${if (it.descending) "DESC" else "ASC"}" }
        ensureIndex(db, order)
        val query = listOf(where, order) + args
        val at = offset.coerceAtLeast(0); val size = limit.coerceIn(1, Budgets.PAGE)
        val anchor = anchors[Anchor(query, at)]
        val result = ArrayList<Entry>(size)
        var last: List<String?>? = null
        fun read(extra: String, parameters: Array<String>, skip: Int = 0) {
            val sql = "SELECT * FROM entries WHERE $where$extra ORDER BY $order LIMIT ?" + if (skip > 0) " OFFSET ?" else ""
            db.rawQuery(sql, args + parameters + arrayOf((size-result.size).toString()) + if (skip > 0) arrayOf(skip.toString()) else emptyArray()).use { c ->
                while (c.moveToNext()) { result.add(c.catalogEntry()); last = keys.map { it.value(c) } }
            }
        }
        lastUsedCursor = anchor != null && at > 0
        if (anchor == null || at == 0) read("", emptyArray(), at)
        else {
            // Disjoint lexicographic ranges, most-specific first. Uses equality prefixes
            // and one index range; works on API23 SQLite without row-value syntax.
            for (i in keys.indices.reversed()) {
                if (result.size == size) break
                val value = anchor[i] ?: continue // NULLs are ordered by the explicit missing key.
                val conditions = ArrayList<String>(); val parameters = ArrayList<String>()
                for (j in 0 until i) {
                    if (anchor[j] == null) conditions.add("${keys[j].sql} IS NULL")
                    else { conditions.add("(${keys[j].sql})=${keys[j].parameter}"); parameters.add(anchor[j]!!) }
                }
                conditions.add("(${keys[i].sql})${if (keys[i].descending) "<" else ">"}${keys[i].parameter}"); parameters.add(value)
                read(" AND " + conditions.joinToString(" AND "), parameters.toTypedArray())
            }
        }
        last?.let { anchors[Anchor(query, at + result.size)] = it }
        return result
    }
    private fun ensureIndex(db: SQLiteDatabase, order: String) {
        if (attemptedIndex == order) return
        attemptedIndex = order
        val sql = "CREATE INDEX browse_seek ON entries(site,parent,$order)"
        val current = db.rawQuery("SELECT sql FROM sqlite_master WHERE type='index' AND name='browse_seek'", null).use { if (it.moveToFirst()) it.getString(0) else null }
        if (current == sql) return
        // One adaptive index, not one index per every sort combination. No eager rebuild
        // during schema migration. A quota failure keeps the previous index via rollback.
        db.beginTransaction()
        try {
            db.execSQL("DROP INDEX IF EXISTS browse_seek")
            db.execSQL(sql)
            fun pragma(name: String) = db.rawQuery("PRAGMA $name",null).use { it.moveToFirst(); it.getLong(0) }
            val pageSize=pragma("page_size")
            val available=(pragma("max_page_count")-pragma("page_count")+pragma("freelist_count"))*pageSize
            // Do not spend the working headroom needed by later slideshow round updates.
            if (available < minOf(2L*1024*1024,db.maximumSize/4)) return
            db.setTransactionSuccessful()
        } catch (_: SQLiteFullException) {
            // Correct OFFSET/keyset fallback still works within the original disk budget.
        } catch (e: SQLiteException) {
            // Expression indexes arrived in SQLite 3.9; API23 may have an older runtime.
            val version = db.rawQuery("SELECT sqlite_version()", null).use { it.moveToFirst(); it.getString(0).split('.').take(2).map(String::toInt) }
            if (!(order.contains(" IS NULL") && version[0] == 3 && version[1] < 9)) throw e
        } finally { db.endTransaction() }
    }
}

internal fun Cursor.catalogEntry() = Entry(getString(getColumnIndexOrThrow("path")), getString(getColumnIndexOrThrow("name")), getInt(getColumnIndexOrThrow("directory")) == 1,
    getColumnIndexOrThrow("size").let { if (isNull(it)) -1 else getLong(it) }, getString(getColumnIndexOrThrow("modified")), getString(getColumnIndexOrThrow("sign")),
    getInt(getColumnIndexOrThrow("ordinal")), getString(getColumnIndexOrThrow("thumbnail")))

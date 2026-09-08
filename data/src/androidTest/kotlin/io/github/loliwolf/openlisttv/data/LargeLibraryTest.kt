package io.github.loliwolf.openlisttv.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import io.github.loliwolf.openlisttv.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class LargeLibraryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val session = Session(Site("large", "fixture", "https://example.invalid", "fixture", false), "test-only")
    private fun fixture(limit: Int = Budgets.MAX_ENTRIES, bytes: Long = Budgets.INDEX_BYTES, block: (Catalog) -> Unit) {
        val name = "large-fixture-${UUID.randomUUID()}.db"
        try { Catalog(context, name, limit, bytes).use(block) } finally { context.deleteDatabase(name) }
    }
    private fun image(path: String, i: Int) = Entry("$path/$i.jpg", "$i.jpg", false, 8_000_000, "2026-09-09T00:00:00Z", "unused-sign", i, "https://unused.invalid/thumb?credential=fixture")
    private open class Gateway : FileGateway {
        override suspend fun list(session: Session, path: String, password: String, page: Int, perPage: Int): Listing = error("Override fixture")
        override suspend fun resolve(session: Session, entry: Entry, password: String): MediaSource = error("No media network in index test")
    }
    @Test fun firstPageCanPlayBeforeLargeDirectoryCompletes() = fixture { catalog -> runBlocking {
        val release = CompletableDeferred<Unit>(); val requests = AtomicInteger()
        val gateway = object : Gateway() {
            override suspend fun list(session: Session, path: String, password: String, page: Int, perPage: Int): Listing {
                requests.incrementAndGet(); assertEquals(500, perPage)
                if (page > 1) release.await()
                return Listing((0 until perPage).map { image(path, (page - 1) * perPage + it) }, 30_000)
            }
        }
        val source = ShuffleTraversal("/large", FileRepository(session, gateway, catalog), SlideshowOptions(TraversalMode.SHUFFLE), this)
        try {
            assertNotNull(withTimeout(6000) { source.next() })
            assertFalse(release.isCompleted); assertTrue(requests.get() <= 2)
            assertEquals(500, catalog.scannedCount())
            assertFalse(catalog.complete(session.site.id, "/large"))
            assertEquals(0, catalog.page(session.site.id, "/large", 0, 100, SortOrder()).total)
        } finally { source.close(); release.complete(Unit) }
    } }
    @Test fun hundredTwentyThousandFilesStayBoundedAndKeepPlayingAfterAdmissionLimit() = fixture { catalog -> runBlocking {
        val requests = AtomicInteger()
        val gateway = object : Gateway() {
            override suspend fun list(session: Session, path: String, password: String, page: Int, perPage: Int): Listing {
                requests.incrementAndGet()
                if (path == "/") return Listing((1..4).map { Entry("/d$it", "d$it", true) }, 4)
                val start = (page - 1) * perPage
                return Listing((start until minOf(start + perPage, 30_000)).map { image(path, it) }, 30_000)
            }
        }
        val source = ShuffleTraversal("/", FileRepository(session, gateway, catalog), SlideshowOptions(TraversalMode.SHUFFLE), this)
        val began = SystemClock.elapsedRealtime()
        try {
            val first = withTimeout(6000) { source.next() }
            val firstMs = SystemClock.elapsedRealtime() - began
            assertNotNull(first)
            withTimeout(60_000) { while (source.warning == null) delay(25) }
            assertEquals(100_000, catalog.scannedCount())
            assertTrue(source.description.contains("未覆盖全部范围"))
            assertTrue(requests.get() <= 203) // 500 metadata/page, not 1,200 calls at 100/page.
            val paths = (1..200).map { withTimeout(2000) { source.next() }!!.path }
            assertEquals(200, paths.toSet().size)
            val db = catalog.readableDatabase
            val pageSize = db.rawQuery("PRAGMA page_size", null).use { it.moveToFirst(); it.getLong(0) }
            val size = db.rawQuery("PRAGMA page_count", null).use { it.moveToFirst(); it.getLong(0) * pageSize }
            assertTrue(size <= Budgets.INDEX_BYTES)
            val plans = listOf("SELECT path,size,modified FROM scan_items WHERE rowid=10", "SELECT path,size,modified FROM scan_items WHERE round=0 ORDER BY shuffle_key LIMIT 1")
            plans.forEach { sql ->
                val plan = db.rawQuery("EXPLAIN QUERY PLAN $sql", null).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }.joinToString() }
                assertFalse(plan, plan.contains("TEMP B-TREE")); assertTrue(plan, plan.contains("SEARCH"))
            }
            println("LARGE_LIBRARY firstMs=$firstMs totalMs=${SystemClock.elapsedRealtime()-began} requests=${requests.get()} bytes=$size candidates=${catalog.scannedCount()}")
        } finally { source.close() }
    } }
    @Test fun compactCandidatesSurviveBrowserCacheEvictionAndRoundBoundaries() = fixture(limit = 1000) { catalog ->
        val options = SlideshowOptions(TraversalMode.SHUFFLE)
        val lease = catalog.startScan("/a", session.site.id)
        assertFalse(catalog.appendScan(lease, session.site.id, "/a", (1..100).map { image("/a", it) }, options))
        catalog.begin(session.site.id, "/old"); catalog.insert(session.site.id, "/old", (1..800).map { image("/old", it) }); catalog.finish(session.site.id, "/old", 800)
        val remembered = image("/old", 1); catalog.savePosition(session.site.id, remembered, 123000)
        catalog.begin(session.site.id, "/new"); catalog.insert(session.site.id, "/new", (1..500).map { image("/new", it) }); catalog.finish(session.site.id, "/new", 500)
        assertFalse(catalog.complete(session.site.id, "/old")); assertTrue(catalog.complete(session.site.id, "/new"))
        assertEquals(123000L, catalog.position(session.site.id, remembered))
        repeat(3) { round ->
            val selected = (1..100).map { catalog.shuffled(session.site.id, round + 1, false, lease)!! }
            assertEquals(100, selected.map { it.path }.toSet().size)
            assertTrue(selected.all { it.thumbnail.isEmpty() && it.sign.isEmpty() && it.size == 8_000_000L })
            assertNull(catalog.shuffled(session.site.id, round + 1, false, lease))
        }
        assertNull(catalog.shuffled("another-site", 4, false, lease))
    }
    @Test fun diskIndexBudgetStopsAdmissionWithoutStoppingExistingCandidates() = fixture(bytes = 2L * 1024 * 1024) { catalog -> runBlocking {
        val gateway = object : Gateway() {
            override suspend fun list(session: Session, path: String, password: String, page: Int, perPage: Int): Listing {
                val start = (page - 1) * perPage
                return Listing((start until start + perPage).map { image(path, it).copy(modified = "x".repeat(60)) }, 20_000)
            }
        }
        val source = ShuffleTraversal("/large", FileRepository(session, gateway, catalog), SlideshowOptions(TraversalMode.SHUFFLE), this)
        try {
            assertNotNull(withTimeout(6000) { source.next() })
            withTimeout(20_000) { while (source.warning == null) delay(25) }
            assertTrue(source.warning!!.contains("磁盘预算")); assertTrue(catalog.scannedCount() in 1..19_999)
            repeat(100) { assertNotNull(withTimeout(2000) { source.next() }) }
        } finally { source.close() }
    } }
    @Test fun supersededScansCannotClearOrWriteIntoNewSession() = fixture { catalog ->
        val old = catalog.reserveScan()
        val current = catalog.startScan("/new", session.site.id)
        val entries = listOf(image("/new", 1))
        catalog.appendScan(current, session.site.id, "/new", entries, SlideshowOptions())
        assertThrows(CancellationException::class.java) { catalog.startScan("/old", session.site.id, old) }
        assertThrows(CancellationException::class.java) { catalog.appendScan(old, session.site.id, "/old", entries, SlideshowOptions()) }
        catalog.cancelScan(old)
        assertEquals(1, catalog.scannedCount(current)); assertEquals("/new/1.jpg", catalog.shuffled(session.site.id, 1, false, current)!!.path)
    }
    @Test fun serverPageClampFallsBackBeforeCommittingAndChangedTotalFails() = fixture { catalog -> runBlocking {
        val requested = mutableListOf<Int>()
        val gateway = object : Gateway() {
            override suspend fun list(session: Session, path: String, password: String, page: Int, perPage: Int): Listing {
                requested += perPage
                val size = minOf(perPage, 100); val start = (page - 1) * size
                return Listing((start until minOf(start + size, 251)).map { image(path, it) }, 251)
            }
        }
        val repository = FileRepository(session, gateway, catalog)
        repository.ensure("/large")
        assertEquals(listOf(500, 100, 100, 100), requested)
        assertEquals(251, catalog.page(session.site.id, "/large", 0, 100, SortOrder()).total)
        val changing = object : Gateway() {
            override suspend fun list(session: Session, path: String, password: String, page: Int, perPage: Int) =
                Listing((0 until perPage).map { image(path, (page - 1) * perPage + it) }, if (page == 1) 1000 else 1001)
        }
        try { FileRepository(session, changing, catalog).ensure("/changing"); fail("Changing directory accepted") }
        catch (e: ClientFailure) { assertEquals(FailureKind.SERVER, e.kind) }
        assertFalse(catalog.complete(session.site.id, "/changing"))
    } }
    @Test fun authAndNetworkErrorsAreNotMisreportedAsIndexBudget() = fixture { catalog -> runBlocking {
        for (kind in listOf(FailureKind.AUTH, FailureKind.RATE_LIMIT, FailureKind.NETWORK)) {
            val gateway = object : Gateway() {
                override suspend fun list(session: Session, path: String, password: String, page: Int, perPage: Int): Listing = throw ClientFailure(kind, "fixture failure")
            }
            val source = ShuffleTraversal("/", FileRepository(session, gateway, catalog), SlideshowOptions(TraversalMode.SHUFFLE), this)
            try { withTimeout(3000) { source.next() }; fail("Failure suppressed") }
            catch (e: ClientFailure) { assertEquals(kind, e.kind); assertNull(source.warning) }
            finally { source.close() }
        }
    } }
    @Test fun oversizedLaterMetadataPageRetriesAtExactOffsetWithoutDuplicates() = fixture { catalog -> runBlocking {
        val requested = mutableListOf<Pair<Int, Int>>()
        val gateway = object : Gateway() {
            override suspend fun list(session: Session, path: String, password: String, page: Int, perPage: Int): Listing {
                requested += page to perPage
                if (perPage == 500 && page == 2) throw ClientFailure(FailureKind.RESOURCE_LIMIT, "fixture metadata budget")
                val start = (page - 1) * perPage
                return Listing((start until minOf(start + perPage, 1001)).map { image(path, it) }, 1001)
            }
        }
        FileRepository(session, gateway, catalog).ensure("/large")
        assertEquals(listOf(1 to 500, 2 to 500, 6 to 100), requested.take(3))
        assertEquals(1001, catalog.page(session.site.id, "/large", 0, 100, SortOrder()).total)
        assertTrue(catalog.complete(session.site.id, "/large"))
    } }
}

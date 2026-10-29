package io.github.loliwolf.openlisttv.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.loliwolf.openlisttv.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class IndexRefreshTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val session = Session(Site("refresh", "fixture", "https://example.invalid", "fixture", false), "unused")
    private val options = SlideshowOptions(mode = TraversalMode.SHUFFLE, recursive = false)
    private fun entry(i: Int) = Entry("/$i.jpg", "$i.jpg", false)
    private class Gateway : FileGateway {
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        @Volatile var entries = listOf(Entry("/1.jpg", "1.jpg", false))
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var error: FailureKind? = null
        override suspend fun list(session: Session, path: String, password: String, page: Int, perPage: Int): Listing {
            calls.incrementAndGet(); peak.updateAndGet { maxOf(it, active.incrementAndGet()) }
            try {
                gate?.await(); error?.let { throw ClientFailure(it, "private server details must not be shown") }
                return Listing(entries, entries.size)
            } finally { active.decrementAndGet() }
        }
        override suspend fun resolve(session: Session, entry: Entry, password: String): MediaSource = error("Not used")
    }
    private fun fixture(block: suspend CoroutineScope.(Catalog, Gateway, FileRepository) -> Unit) {
        val name = "refresh-${UUID.randomUUID()}.db"
        try { Catalog(context, name).use { c -> runBlocking {
            val gateway = Gateway(); block(c, gateway, FileRepository(session, gateway, c))
        } } } finally { context.deleteDatabase(name) }
    }
    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(5000) { while (!condition()) delay(10) }
    private suspend fun ready(source: ShuffleTraversal) {
        assertNotNull(source.next()); awaitCondition { !source.indexStatus.scanning }
        // Publication precedes the coordinator releasing its single-flight slot.
        delay(30)
    }
    @Test fun manualRefreshIsSingleFlightAndPublishesOnlyAfterCompleteWithoutDroppingReservedEntry() = fixture { c, g, repo ->
        val source = ShuffleTraversal("/", repo, options.copy(reuseIndex = false), this)
        try {
            ready(source); val old = source.next()!!; val timestamp = source.indexStatus.updatedAt
            g.entries = listOf(entry(2), entry(3)); g.gate = CompletableDeferred()
            assertEquals(RefreshRequest.STARTED, source.requestRefresh())
            awaitCondition { source.indexStatus.refreshing && g.active.get() == 1 }
            repeat(30) { assertEquals(RefreshRequest.BUSY, source.requestRefresh()); assertEquals(old, source.next()) }
            assertEquals(timestamp, source.indexStatus.updatedAt)
            g.gate!!.complete(Unit); awaitCondition { !source.indexStatus.scanning }
            val found = (1..2).map { source.next()!!.path }.toSet()
            assertEquals(setOf("/2.jpg", "/3.jpg"), found); assertEquals("/1.jpg", old.path)
            assertEquals(1, g.peak.get()); assertEquals(2, g.calls.get())
            assertEquals(0, c.readableDatabase.rawQuery("SELECT COUNT(*) FROM scan_snapshot", null).use { it.moveToFirst(); it.getInt(0) })
        } finally { source.close() }
    }
    @Test fun refreshFailuresKeepOldTimestampAndPauseAutomaticRetriesUntilManualRetry() = fixture { _, g, repo ->
        val source = ShuffleTraversal("/", repo, options, this, refreshIntervalMillis = 150)
        try {
            ready(source); val timestamp = source.indexStatus.updatedAt
            g.error = FailureKind.RATE_LIMIT
            awaitCondition { source.indexStatus.automaticPaused }
            val calls = g.calls.get(); delay(400); assertEquals(calls, g.calls.get())
            assertEquals(timestamp, source.indexStatus.updatedAt); assertEquals("/1.jpg", source.next()!!.path)
            assertFalse(source.indexStatus.message!!.contains("private"))
            g.error = null; g.entries = listOf(entry(9))
            assertEquals(RefreshRequest.STARTED, source.requestRefresh())
            awaitCondition { source.indexStatus.updatedAt != timestamp }
            assertEquals("/9.jpg", source.next()!!.path); assertFalse(source.indexStatus.automaticPaused)
        } finally { source.close() }
    }
    @Test fun automaticRefreshDoesNotWaitSixHoursAndCloseCancelsNetworkAndTimer() = fixture { _, g, repo ->
        var source = ShuffleTraversal("/", repo, options, this)
        ready(source); source.close()
        val before = g.calls.get()
        g.entries = listOf(entry(5)); g.gate = CompletableDeferred()
        source = ShuffleTraversal("/", repo, options, this, refreshIntervalMillis = 100)
        try {
            assertEquals("/1.jpg", source.next()!!.path) // Reuse must not wait for the blocked refresh.
            assertTrue(source.indexStatus.reused)
            awaitCondition { g.calls.get() > before }
            assertEquals("/1.jpg", source.next()!!.path)
            source.close(); awaitCondition { g.active.get() == 0 }
            val after = g.calls.get(); delay(300); assertEquals(after, g.calls.get())
            assertEquals(RefreshRequest.UNAVAILABLE, source.requestRefresh())
        } finally { source.close() }
    }
    @Test fun ordinaryNetworkFailureRetriesAtIntervalAndManualAutomaticRaceStaysSingleFlight() = fixture { _, g, repo ->
        val source = ShuffleTraversal("/", repo, options, this, refreshIntervalMillis = 80)
        try {
            ready(source); g.error = FailureKind.NETWORK
            awaitCondition { source.indexStatus.message != null }
            assertFalse(source.indexStatus.automaticPaused)
            val before = g.calls.get(); awaitCondition { g.calls.get() > before }
            g.error = null
            repeat(40) { source.requestRefresh(); delay(7) }
            awaitCondition { !source.indexStatus.scanning && source.indexStatus.message == null }
            assertEquals(1, g.peak.get()); assertEquals("/1.jpg", source.next()!!.path)
        } finally { source.close() }
    }
    @Test fun disabledAutomaticRefreshDoesNotPollAndEmptySuccessfulRefreshIsAuthoritative() = fixture { _, g, repo ->
        val source = ShuffleTraversal("/", repo, options, this)
        try {
            ready(source); delay(250); assertEquals(1, g.calls.get())
            g.entries = emptyList(); assertEquals(RefreshRequest.STARTED, source.requestRefresh())
            awaitCondition { g.calls.get() == 2 && !source.indexStatus.scanning }
            assertEquals(0, source.indexStatus.count); assertNull(source.next())
        } finally { source.close() }
    }
    @Test fun repeatedBankSwitchBudgetFailureAndCancellationPreserveSnapshot() = fixture { c, _, _ ->
        val lease = c.reserveScan()
        c.openScan(lease, "/", "refresh", "key", options, 1000)
        c.appendScan(lease, "refresh", "/", listOf(entry(1)), options); c.finishScan(lease, null, 1000)
        repeat(4) { round ->
            assertTrue(c.refreshScan(lease)); assertFalse(c.refreshScan(lease))
            c.appendScan(lease, "refresh", "/", listOf(entry(round + 2)), options)
            assertEquals("/${round + 1}.jpg", c.pickScanned(lease)!!.path)
            c.finishScan(lease, null, 2000L + round)
        }
        c.refreshScan(lease); c.appendScan(lease, "refresh", "/", listOf(entry(99)), options)
        c.finishScan(lease, "budget fixture", 9000)
        assertEquals("/5.jpg", c.pickScanned(lease)!!.path); assertEquals(2003L, c.scanView(lease).updatedAt)
        assertNotNull(c.scanView(lease).message)
        c.refreshScan(lease); c.appendScan(lease, "refresh", "/", listOf(entry(100)), options); c.cancelScan(lease)
        val reopen = c.reserveScan(); c.openScan(reopen, "/", "refresh", "key", options, 3000)
        assertEquals("/5.jpg", c.pickScanned(reopen)!!.path); assertEquals(2003L, c.scanView(reopen).updatedAt)
    }
    @Test fun newPreferenceDefaultsOffAndRoundTripsWithoutPersistingOneShotFlag() {
        val store = SiteStore(context); val previous = store.slideshow()
        try {
            store.setSlideshow(options.copy(autoRefreshMinutes = 30, forceRescan = true))
            assertEquals(30, SiteStore(context).slideshow().autoRefreshMinutes)
            assertFalse(SiteStore(context).slideshow().forceRescan)
        } finally { store.setSlideshow(previous) }
    }
}

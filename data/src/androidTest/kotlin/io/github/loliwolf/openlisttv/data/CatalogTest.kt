package io.github.loliwolf.openlisttv.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.loliwolf.openlisttv.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var catalog: Catalog
    @Before fun setup() { context.deleteDatabase("catalog-instrumented-test.db"); catalog = Catalog(context, "catalog-instrumented-test.db") }
    @After fun cleanup() { catalog.close(); context.deleteDatabase("catalog-instrumented-test.db") }
    @Test fun completeDirectorySortCrossesPagesAndIsolatesSites() {
        catalog.begin("a", "/")
        (1..1001).reversed().chunked(100).forEach { chunk -> catalog.insert("a", "/", chunk.map { Entry("/$it.jpg", "$it.jpg", false, it.toLong()) }) }
        catalog.finish("a", "/", 1001)
        assertTrue(catalog.complete("a", "/"))
        assertEquals("1.jpg", catalog.page("a", "/", 0, 100, SortOrder()).entries.first().name)
        assertEquals("101.jpg", catalog.page("a", "/", 100, 100, SortOrder()).entries.first().name)
        assertEquals("1001.jpg", catalog.page("a", "/", 0, 100, SortOrder(SortField.SIZE, true)).entries.first().name)
        assertEquals(0, catalog.page("b", "/", 0, 100, SortOrder()).total)
    }
    @Test fun completedShuffleDoesNotRepeatWithinRound() {
        catalog.begin("a", "/")
        val entries = (1..50).map { Entry("/$it.jpg", "$it.jpg", false) }
        catalog.insert("a", "/", entries); catalog.finish("a", "/", 50)
        catalog.startScan("/"); entries.forEach(catalog::addScanned)
        val first = (1..50).map { catalog.shuffled("a", 1, false)!!.path }
        assertEquals(50, first.toSet().size); assertNull(catalog.shuffled("a", 1, false))
        assertNotNull(catalog.shuffled("a", 2, false))
    }
}

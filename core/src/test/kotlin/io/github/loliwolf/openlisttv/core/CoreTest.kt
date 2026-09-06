package io.github.loliwolf.openlisttv.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    @Test fun sessionDebugStringDoesNotExposeCredentialsOrPrivatePaths() {
        val s = Session(Site("id", "test", "https://example.com/", "person"), "never-log-this-token", "/private")
        assertFalse(s.toString().contains(s.token)); assertFalse(s.toString().contains(s.basePath))
    }
    @Test fun naturalOrderingHandlesLargeNumbersAndZeroes() {
        val names = listOf("10.jpg", "2.jpg", "1.jpg", "100000000000000000000000000000.jpg")
        assertEquals(listOf("1.jpg", "2.jpg", "10.jpg", "100000000000000000000000000000.jpg"), names.sortedWith(NaturalOrder.comparator))
        assertEquals(NaturalOrder.key("02.JPG"), NaturalOrder.key("2.jpg"))
    }
    @Test fun pathsPreserveSpecialCharactersAndRejectTraversal() {
        assertEquals("/家庭/a #+%😀.jpg", Paths.child("/家庭", "a #+%😀.jpg"))
        assertFalse(Paths.inside("/abc", "/abcd/private"))
        for (bad in listOf("..", ".", "a/b", "")) try { Paths.child("/", bad); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun bitmapBudgetIncludesScratchAndNeverUsesFileSize() {
        assertEquals(3840L * 2160, ImageBudget.maxPixels(3840, 2160, 256L * 1024 * 1024, false))
        val max = ImageBudget.maxPixels(3840, 2160, 32L * 1024 * 1024, true)
        assertTrue(max <= 1280L * 720)
        assertEquals(4, ImageBudget.sampleSize(6000, 4000, 1920L * 1080))
    }
    @Test fun historyIsBoundedAndSupportsForwardAfterBack() {
        val h = PlaybackHistory(3)
        repeat(6) { h.add(Entry("/$it", "$it", false)) }
        assertEquals(3, h.size)
        assertEquals("/4", h.previous()?.path)
        assertEquals("/5", h.forward()?.path)
    }
    @Test fun orderedTraversalLoopsWithoutCollectingWholeTree() = runTest {
        val root = Entry("/a.jpg", "a.jpg", false)
        val folder = Entry("/child", "child", true)
        val child = Entry("/child/b.mp3", "b.mp3", false)
        val reader = object : DirectoryReader {
            override suspend fun page(path: String, offset: Int, filter: EntryFilter): Listing {
                val all = when { path == "/" && filter == EntryFilter.FOLDERS -> listOf(folder); filter == EntryFilter.FOLDERS -> emptyList(); path == "/" -> listOf(root); else -> listOf(child) }
                return Listing(all.drop(offset).take(1), all.size)
            }
        }
        val source = OrderedTraversal("/", reader, SlideshowOptions())
        assertEquals(listOf(root.path, child.path, root.path, child.path), List(4) { source.next()!!.path })
    }
    @Test fun emptyDirectoryTerminates() = runTest {
        val reader = object : DirectoryReader { override suspend fun page(path: String, offset: Int, filter: EntryFilter) = Listing(emptyList(), 0) }
        assertNull(OrderedTraversal("/", reader, SlideshowOptions()).next())
    }
}

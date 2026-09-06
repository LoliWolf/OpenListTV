package io.github.loliwolf.openlisttv.core

import org.junit.Assert.*
import org.junit.Test

class PageWindowTest {
    @Test fun onlyCurrentAndNextPagesAreAllowed() {
        val w = PageWindow.at(0, 1001)
        assertEquals(setOf(0, 1), w.pages)
        assertTrue(w.contains(199)); assertFalse(w.contains(200)); assertFalse(w.contains(-1))
    }
    @Test fun scrollingMovesAndDropsTheOldWindow() {
        val w = PageWindow.at(100, 1001)
        assertEquals(setOf(1, 2), w.pages)
        assertFalse(w.contains(99)); assertTrue(w.contains(100)); assertTrue(w.contains(299)); assertFalse(w.contains(300))
    }
    @Test fun finalPageDoesNotRequestAnExtraPage() {
        assertEquals(setOf(10), PageWindow.at(1000, 1001).pages)
        assertEquals(setOf(0), PageWindow.at(0, 1).pages)
        assertNull(PageWindow.at(0, 0).next)
    }
}

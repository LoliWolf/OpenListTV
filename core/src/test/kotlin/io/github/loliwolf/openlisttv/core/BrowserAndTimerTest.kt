package io.github.loliwolf.openlisttv.core

import org.junit.Assert.*
import org.junit.Test

class BrowserAndTimerTest {
    @Test fun queriesAreBoundedAndIndependentFromTraversal() {
        assertFalse(BrowserQuery().active)
        assertTrue(BrowserQuery(type = BrowserType.IMAGE).active)
        assertEquals("搜索：%_ · 视频", BrowserQuery("%_", BrowserType.VIDEO).description)
        assertThrows(IllegalArgumentException::class.java) { BrowserQuery("x".repeat(129)) }
        assertTrue(SlideshowOptions().accepts(Entry("/a.wav", "a.wav", false)))
    }
    @Test fun deadlineIsMonotonicAndDoesNotResetAtSlideBoundariesOrPause() {
        val timer = SlideshowDeadline()
        assertNull(timer.remaining(100))
        timer.set(60_000, 100)
        assertEquals(30_000L, timer.remaining(30_100))
        assertFalse(timer.expired(60_099)); assertTrue(timer.expired(60_100))
        assertEquals(0L, timer.remaining(90_100))
        timer.set(0, 90_100); assertNull(timer.remaining(999_999))
    }
    @Test fun timerSettingPreservesExistingDefaultsAndValidatesBounds() {
        assertEquals(0, SlideshowOptions().stopAfterMinutes)
        assertEquals(1440, SlideshowOptions(stopAfterMinutes = 1440).stopAfterMinutes)
        assertThrows(IllegalArgumentException::class.java) { SlideshowOptions(stopAfterMinutes = -1) }
        assertThrows(IllegalArgumentException::class.java) { SlideshowOptions(stopAfterMinutes = 1441) }
    }
}

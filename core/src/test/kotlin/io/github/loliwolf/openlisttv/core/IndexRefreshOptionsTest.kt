package io.github.loliwolf.openlisttv.core

import org.junit.Assert.*
import org.junit.Test

class IndexRefreshOptionsTest {
    @Test fun refreshIsOptInAndBounded() {
        assertEquals(0, SlideshowOptions().autoRefreshMinutes)
        for (value in listOf(0, 5, 30, 1440)) assertEquals(value, SlideshowOptions(autoRefreshMinutes = value).autoRefreshMinutes)
        for (value in listOf(-1, 1, 4, 1441, Int.MAX_VALUE)) assertTrue(runCatching { SlideshowOptions(autoRefreshMinutes = value) }.isFailure)
    }
}

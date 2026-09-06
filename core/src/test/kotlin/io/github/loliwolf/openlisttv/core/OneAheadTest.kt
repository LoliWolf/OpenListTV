package io.github.loliwolf.openlisttv.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OneAheadTest {
    @Test fun onlyOneJobAndTakeTransfersOnce() = runTest {
        var starts = 0; val released = mutableListOf<Int>()
        val slot = OneAhead<Int>(this) { released += it }
        repeat(10) { slot.start { starts++; delay(10); 7 } }
        advanceUntilIdle(); slot.await()
        assertEquals(1, starts); assertEquals(7, slot.take()); assertNull(slot.take())
        slot.cancel(); assertTrue(released.isEmpty())
    }
    @Test fun cancellingCompletedPreloadReleasesIt() = runTest {
        val released = mutableListOf<Int>(); val slot = OneAhead<Int>(this) { released += it }
        slot.start { 7 }; runCurrent(); slot.cancel(); slot.cancel()
        assertEquals(listOf(7), released); assertNull(slot.take())
    }
    @Test fun cancelledLateResultCannotReplaceNewSlot() = runTest {
        val released = mutableListOf<Int>(); val slot = OneAhead<Int>(this) { released += it }
        slot.start { withContext(NonCancellable) { delay(30); 7 } }; runCurrent(); slot.cancel()
        slot.start { 8 }; advanceUntilIdle()
        assertEquals(listOf(7), released); assertEquals(8, slot.take())
    }
    @Test fun errorsAreDeferredUntilConsumptionAndSlotCanRecover() = runTest {
        val slot = OneAhead<Int>(this) { }
        slot.start { throw ClientFailure(FailureKind.UNSUPPORTED, "bad image") }; advanceUntilIdle(); slot.await()
        assertTrue(isActive)
        try { slot.take(); fail() } catch (e: ClientFailure) { assertEquals(FailureKind.UNSUPPORTED, e.kind) }
        slot.start { 9 }; advanceUntilIdle(); assertEquals(9, slot.take())
    }
    @Test fun peekingHistoryDoesNotAdvanceTheCursor() {
        val a = Entry("/a.png", "a.png", false); val b = Entry("/b.png", "b.png", false)
        val history = PlaybackHistory(); history.add(a); history.add(b); history.previous()
        assertEquals(b, history.peekForward()); assertEquals(b, history.peekForward()); assertEquals(b, history.forward()); assertNull(history.peekForward())
    }
    @Test fun dualImageBudgetScalesWithHeapAndMemoryPressure() {
        val generous = ImageBudget.slideshowMaxPixels(3840, 2160, 256L shl 20, 256L shl 20, false)
        assertEquals(3840L * 2160, generous); assertTrue(generous * 8 <= Budgets.SLIDESHOW_IMAGE_BYTES)
        val small = ImageBudget.slideshowMaxPixels(3840, 2160, 48L shl 20, 64L shl 20, false)
        assertTrue(small * 8 <= (64L shl 20) / 4)
        assertTrue(ImageBudget.slideshowMaxPixels(3840, 2160, 128L shl 20, 256L shl 20, true) <= 1280L * 720)
    }
}

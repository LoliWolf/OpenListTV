package io.github.loliwolf.openlisttv.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackControlsTest {
    @Test fun tapAndHoldAreTimeBasedAndAccelerate() {
        val tap = AcceleratingSeek(60_000, 7_200_000, 1, 100)
        assertEquals(70_000L, tap.advance(200)); assertEquals(70_000L, tap.advance(449))
        assertEquals(94_750L, tap.advance(2100)); assertEquals(15, tap.rate(1100)); assertEquals(45, tap.rate(2100))
        val first = tap.target; tap.advance(5100); assertTrue(tap.target - first > 100_000)
        assertEquals(120, tap.rate(6100)); assertEquals(300, tap.rate(10100))
    }
    @Test fun repeatFrequencyCannotChangeTheTarget() {
        val few = AcceleratingSeek(1_000_000, 7_200_000, -1, 0)
        val many = AcceleratingSeek(1_000_000, 7_200_000, -1, 0)
        for (t in 0L..8000 step 25) many.advance(t)
        assertEquals(few.advance(8000), many.target)
    }
    @Test fun boundsDirectionReversalAndNonMonotonicEventsAreSafe() {
        val seek = AcceleratingSeek(5000, 60_000, -1, 100)
        assertEquals(0, seek.target); seek.advance(10_000); assertEquals(0, seek.target)
        seek.reverse(1, 10_000); assertEquals(10_000, seek.target); assertEquals(0, seek.rate(10_000))
        seek.advance(30_000); assertEquals(60_000, seek.target)
        val same = seek.target; seek.advance(10_000); assertEquals(same, seek.target)
    }
    @Test fun imageRotationZoomAndPanStayWithinTheViewport() {
        val t = PictureTransform()
        val fit = t.layout(400, 200, 1000, 1000); assertEquals(2.5, fit.scale, 0.0)
        t.rotate(1); val rotated = t.layout(400, 200, 1000, 1000); assertEquals(90, rotated.rotation); assertEquals(2.5, rotated.scale, 0.0)
        t.scaleBy(100.0); assertEquals(4.0, t.zoom, 0.0)
        t.pan(100.0, -100.0); val moved = t.layout(400, 200, 1000, 1000)
        assertEquals(1000.0, moved.x, 0.0); assertEquals(-1000.0, moved.y, 0.0)
        t.scaleBy(0.001); val small = t.layout(400, 200, 1000, 1000)
        assertEquals(500.0, small.x, 0.0); assertEquals(500.0, small.y, 0.0)
        t.rotate(-2); assertEquals(3, t.quarterTurns); t.reset(); assertEquals(0, t.quarterTurns)
    }
    @Test fun fixedVideoRatiosFitInsideEitherOrientation() {
        assertEquals(1920 to 1080, VideoFraming.WIDE.viewport(1920,1080))
        assertEquals(1440 to 1080, VideoFraming.CLASSIC.viewport(1920,1080))
        assertEquals(1080 to 607, VideoFraming.WIDE.viewport(1080,1920))
        assertEquals(1080 to 810, VideoFraming.CLASSIC.viewport(1080,1920))
        assertEquals(1080 to 1920, VideoFraming.FIT.viewport(1080,1920))
    }
}

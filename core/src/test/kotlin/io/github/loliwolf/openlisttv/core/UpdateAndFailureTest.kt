package io.github.loliwolf.openlisttv.core

import org.junit.Assert.*
import org.junit.Test

class UpdateAndFailureTest {
    @Test fun stableAndPreviewVersionsCompareCorrectly() {
        fun v(s: String) = checkNotNull(AppVersion.parse(s))
        assertTrue(v("v1.10.0") > v("1.9.9"))
        assertTrue(v("1.0.1") > v("1.0.1-preview.1"))
        assertTrue(v("1.0.1-preview.10") > v("1.0.1-preview.2"))
        assertTrue(v("1.0.1-preview.1") > v("1.0.0"))
        assertEquals(v("1.0.1+build.5"), v("1.0.1"))
        for (bad in listOf("latest", "1.0", "1.01.0", "1.0.1-preview.01", "-1.0.0", "1.0.0/evil")) assertNull(AppVersion.parse(bad))
    }
    @Test fun oversizedFileIsNotAGlobalCapacityFailure() {
        assertFalse(PlaybackFailures.requiresPause(FailureKind.RESOURCE_LIMIT))
        assertFalse(PlaybackFailures.requiresPause(FailureKind.UNSUPPORTED))
        for (kind in listOf(FailureKind.AUTH, FailureKind.RATE_LIMIT, FailureKind.NETWORK, FailureKind.CAPACITY)) assertTrue(PlaybackFailures.requiresPause(kind))
        assertEquals(128L * 1024 * 1024, Budgets.IMAGE_DISK_BYTES)
        assertEquals(32L * 1024 * 1024, Budgets.THUMBNAIL_SOURCE_BYTES)
    }
}

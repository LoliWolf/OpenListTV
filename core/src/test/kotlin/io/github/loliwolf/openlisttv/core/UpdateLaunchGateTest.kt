package io.github.loliwolf.openlisttv.core

import org.junit.Assert.*
import org.junit.Test

class UpdateLaunchGateTest {
    @Test fun checksOncePerEntryNotEachFocusAndNeverOverridesDisabledSwitch() {
        val gate = UpdateLaunchGate(); gate.enter()
        assertFalse(gate.consume(false, true)); assertFalse(gate.consume(true, false)); assertTrue(gate.consume(true, true))
        repeat(10) { assertFalse(gate.consume(true, true)) }
        gate.enter(); assertTrue(gate.consume(true, true))
    }
    @Test fun installerReturnsDoNotPromptAgainButNextEntryDoes() {
        val gate = UpdateLaunchGate(); gate.enter(true); assertFalse(gate.consume(true, true))
        gate.enter(); assertTrue(gate.consume(true, true))
        gate.request(); assertTrue(gate.consume(true, true))
    }
}

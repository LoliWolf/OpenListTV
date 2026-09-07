package io.github.loliwolf.openlisttv.core

/** One check per app entry, not per directory/window-focus/rotation event. */
class UpdateLaunchGate {
    private var pending = false
    fun enter(returningFromExternalFlow: Boolean = false) { pending = !returningFromExternalFlow }
    fun request() { pending = true }
    fun consume(automatic: Boolean, canPrompt: Boolean): Boolean {
        if (!pending || !automatic || !canPrompt) return false
        pending = false; return true
    }
}

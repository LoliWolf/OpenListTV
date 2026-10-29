package io.github.loliwolf.openlisttv

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** Exercise the same disclosure path as a remote user, not hidden legacy buttons. */
@androidx.media3.common.util.UnstableApi
internal object PlayerUiActions {
    private fun find(view: View, label: String): View? {
        if (view.isClickable && (view.contentDescription == label || view is TextView && view.text == label)) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), label)?.let { return it }
        return null
    }
    fun click(panel: PlaybackPanel, label: String) {
        find(panel, label)?.let { it.performClick(); return }
        checkNotNull(find(panel, "更多")).performClick()
        val dialog = PlaybackPanel::class.java.getDeclaredField("activeDialog").apply { isAccessible = true }.get(panel) as AlertDialog
        checkNotNull(find(checkNotNull(dialog.window).decorView, label)) { "Missing playback action: $label" }.performClick()
    }
}

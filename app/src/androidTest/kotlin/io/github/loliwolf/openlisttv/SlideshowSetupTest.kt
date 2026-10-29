package io.github.loliwolf.openlisttv

import android.app.AlertDialog
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.loliwolf.openlisttv.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class SlideshowSetupTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun dialogs(): List<AlertDialog> {
        val map = UiPanels::class.java.getDeclaredField("openDialogs").apply { isAccessible = true }.get(UiPanels) as Map<*, *>
        return map.keys.filterIsInstance<AlertDialog>().filter { it.isShowing }
    }
    private fun top(excluding: AlertDialog? = null): AlertDialog {
        var result: AlertDialog? = null
        val end = SystemClock.elapsedRealtime() + 5000
        while (result == null && SystemClock.elapsedRealtime() < end) {
            main { result = dialogs().singleOrNull { it !== excluding && it.window?.decorView?.hasWindowFocus() == true } }
            if (result == null) SystemClock.sleep(20)
        }
        return checkNotNull(result) { "No focused dialog" }
    }
    private fun click(dialog: AlertDialog, label: String) {
        main { views(dialog.window!!.decorView).first { it.isClickable && (it.contentDescription == label || it is TextView && it.text == label) }.performClick() }
        // AlertDialog dispatches native button callbacks through its Handler.
        instrumentation.waitForIdleSync()
    }
    private fun fixture(initial: SlideshowOptions = SlideshowOptions(), block: (SlideshowSetup, () -> SlideshowOptions?) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        var setup: SlideshowSetup? = null
        var committed: SlideshowOptions? = null
        try {
            main { setup = SlideshowSetup(activity, "/", initial) { committed = it }; UiPanels.show(setup!!.dialog) }
            top(); block(checkNotNull(setup)) { committed }
        } finally { main { dialogs().forEach { it.dismiss() }; activity.finish() } }
    }
    @Test fun overviewHasNoNumericWallAndCancelledEditorDoesNotChangeDraft() = fixture { setup, result ->
        main { assertTrue(views(setup.dialog.window!!.decorView).filterIsInstance<EditText>().isEmpty()) }
        click(setup.dialog, "图片停留时间")
        val editor = top(setup.dialog)
        main { views(editor.window!!.decorView).filterIsInstance<EditText>().single().setText("11") }
        click(editor, "取消")
        click(setup.dialog, "开始")
        assertEquals(SlideshowOptions(), result())
    }
    @Test fun numericValidationKeepsEditorOpenAndCommitOnlyHappensAtStart() = fixture { setup, result ->
        click(setup.dialog, "图片停留时间")
        val editor = top(setup.dialog)
        main { views(editor.window!!.decorView).filterIsInstance<EditText>().single().setText("0") }
        click(editor, "应用")
        main {
            assertTrue(editor.isShowing)
            assertNotNull(views(editor.window!!.decorView).filterIsInstance<EditText>().single().error)
            views(editor.window!!.decorView).filterIsInstance<EditText>().single().setText("12")
        }
        click(editor, "应用"); assertNull(result())
        click(setup.dialog, "开始"); assertEquals(12, result()!!.imageSeconds)
    }
    @Test fun cancellingAdvancedEditorKeepsAllOriginalOptions() {
        val initial = SlideshowOptions(mode = TraversalMode.RESERVOIR, recursive = false, imageOnly = true, videoSeconds = 15, audioSeconds = 30, prefetch = false, stopAfterMinutes = 40, reuseIndex = false, autoRefreshMinutes = 30)
        fixture(initial) { setup, result ->
            click(setup.dialog, "索引与高级选项")
            val editor = top(setup.dialog)
            main {
                views(editor.window!!.decorView).filterIsInstance<Switch>().forEach { it.performClick() }
                views(editor.window!!.decorView).filterIsInstance<EditText>().single().setText("5")
            }
            click(editor, "取消"); click(setup.dialog, "开始"); assertEquals(initial, result())
        }
    }
    @Test fun allModesAreReachableWithoutLosingOtherOptions() {
        for (mode in TraversalMode.entries) fixture(SlideshowOptions(imageSeconds = 17, stopAfterMinutes = 20)) { setup, result ->
            click(setup.dialog, "播放方式"); click(top(setup.dialog), mode.label); click(setup.dialog, "开始")
            assertEquals(mode, result()!!.mode); assertEquals(17, result()!!.imageSeconds); assertEquals(20, result()!!.stopAfterMinutes)
        }
    }
    @Test fun activityFinishClosesOwnedParentAndChildPanels() {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var setup: SlideshowSetup
        try {
            main { setup = SlideshowSetup(activity, "/", SlideshowOptions()) {}; UiPanels.show(setup.dialog) }
            top(); click(setup.dialog, "图片停留时间")
            val child = top(setup.dialog)
            main { activity.finish() }
            val end = SystemClock.elapsedRealtime() + 5000
            var showing = true
            while (showing && SystemClock.elapsedRealtime() < end) {
                main { showing = setup.dialog.isShowing || child.isShowing }
                if (showing) SystemClock.sleep(20)
            }
            assertFalse("Activity retained its configuration panels", showing)
        } finally { main { dialogs().forEach { it.dismiss() }; activity.finish() } }
    }
}

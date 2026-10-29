package io.github.loliwolf.openlisttv

import android.app.AlertDialog
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*
import io.github.loliwolf.openlisttv.TvUi.dp

/** Code-native surfaces: no web content, artwork decoding or extra UI runtime. */
object UiPanels {
    private val openDialogs = java.util.WeakHashMap<AlertDialog, Unit>()
    class Preference(c: Context, label: String, description: String, value: String, action: () -> Unit) : LinearLayout(c) {
        private val detail = TvUi.text(c, description, 12f, TvUi.muted)
        private val valueText = TvUi.text(c, value, 13f, TvUi.accent)
        init {
            id = View.generateViewId(); orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            isFocusable = true; isClickable = true; contentDescription = label
            background = TvUi.focusBackground(radius = c.dp(12).toFloat())
            minimumHeight = c.dp(58); setPadding(c.dp(16), c.dp(12), c.dp(14), c.dp(12))
            val copy = TvUi.column(c)
            copy.addView(TvUi.text(c, label, 16f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
            detail.maxLines = 2
            copy.addView(detail.apply { setPadding(0, c.dp(5), 0, 0); visibility = if (description.isEmpty()) GONE else VISIBLE })
            addView(copy, LayoutParams(0, -2, 1f))
            valueText.maxLines = 2; valueText.gravity = Gravity.END
            addView(valueText, LayoutParams(-2, -2).apply { marginStart = c.dp(12) })
            addView(TvUi.text(c, "›", 22f, TvUi.muted), LayoutParams(c.dp(20), -2).apply { marginStart = c.dp(12) })
            setOnClickListener { action() }
            layoutParams = LayoutParams(-1, -2).apply { bottomMargin = c.dp(3) }
        }
        fun value(text: String) { valueText.text = text }
        fun description(text: String) { detail.text = text; detail.visibility = if (text.isEmpty()) GONE else VISIBLE }
        fun compact(): Preference = apply { minimumHeight = context.dp(48); setPadding(context.dp(16), context.dp(8), context.dp(14), context.dp(8)) }
    }
    data class Action(val title: String, val detail: String = "", val run: () -> Unit)

    fun section(c: Context, title: String) = TvUi.text(c, title, 12f, TvUi.muted).apply {
        letterSpacing = .04f; setPadding(c.dp(16), c.dp(18), c.dp(12), c.dp(8))
    }
    fun form(c: Context) = TvUi.column(c).apply { setPadding(c.dp(12), c.dp(4), c.dp(12), c.dp(8)) }
    fun scroll(c: Context, body: View) = ScrollView(c).apply {
        isFillViewport = false; isFocusable = false; clipToPadding = false
        addView(body)
    }
    fun fit(dialog: AlertDialog) {
        openDialogs[dialog] = Unit
        val c = dialog.context
        var owner = c
        while (owner is ContextWrapper && owner !is Activity && owner.baseContext !== owner) owner = owner.baseContext
        if (owner is Activity) dialog.setOwnerActivity(owner)
        dialog.window?.setLayout(minOf(c.dp(540), c.resources.displayMetrics.widthPixels - c.dp(32)), -2)
    }
    fun refitOpenDialogs() { openDialogs.keys.toList().filter { it.isShowing }.forEach(::fit) }
    fun dismissFor(activity: Activity) {
        openDialogs.keys.toList().filter { it.ownerActivity === activity && it.isShowing }.forEach { it.dismiss() }
    }
    fun show(dialog: AlertDialog, first: View? = null): AlertDialog {
        dialog.show(); fit(dialog); first?.requestFocus()
        return dialog
    }
    fun menu(c: Context, title: String, actions: List<Action>, subtitle: String = ""): AlertDialog {
        val body = form(c)
        if (subtitle.isNotEmpty()) body.addView(TvUi.text(c, subtitle, 13f, TvUi.muted).apply { setPadding(c.dp(16), c.dp(4), c.dp(16), c.dp(12)); maxLines = 2 })
        val dialog = AlertDialog.Builder(c).setTitle(title).setView(scroll(c, body)).setNegativeButton("关闭", null).create()
        actions.forEach { action -> body.addView(Preference(c, action.title, action.detail, "") { dialog.dismiss(); action.run() }) }
        dialog.setOnShowListener {
            val rows = (0 until body.childCount).map(body::getChildAt).filter { it.isFocusable }
            TvUi.verticalFocus(*(rows + dialog.getButton(AlertDialog.BUTTON_NEGATIVE)).toTypedArray())
            rows.firstOrNull()?.requestFocus(); fit(dialog)
        }
        return dialog
    }
    fun toggle(c: Context, label: String, checked: Boolean, changed: (Boolean) -> Unit) = Switch(c).apply {
        id = View.generateViewId(); text = label; contentDescription = label
        textSize = 15f; isChecked = checked; isFocusable = true; minHeight = c.dp(52)
        setTextColor(TvUi.foreground); setPadding(c.dp(16), c.dp(8), c.dp(16), c.dp(8))
        background = TvUi.focusBackground(radius = c.dp(12).toFloat())
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = c.dp(3) }
        setOnCheckedChangeListener { _, value -> changed(value) }
    }
    /** A bounded form stays centered on TV and uses the full available phone width. */
    class ContentFrame(c: Context, private val maximumDp: Int = 600) : FrameLayout(c) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            for (i in 0 until childCount) {
                val params = getChildAt(i).layoutParams as LayoutParams
                params.width = minOf(available, context.dp(maximumDp)); params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
    class Header(c: Context, private val copy: View, private val actions: View) : LinearLayout(c) {
        init { gravity = Gravity.CENTER_VERTICAL; addView(copy); addView(actions) }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val wide = MeasureSpec.getSize(widthMeasureSpec) >= context.dp(760)
            orientation = if (wide) HORIZONTAL else VERTICAL
            (copy.layoutParams as LayoutParams).apply { width = if (wide) 0 else -1; height = -2; weight = if (wide) 1f else 0f }
            (actions.layoutParams as LayoutParams).apply { width = if (wide) -2 else -1; height = -2; marginStart = if (wide) context.dp(20) else 0; topMargin = if (wide) 0 else context.dp(12) }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}

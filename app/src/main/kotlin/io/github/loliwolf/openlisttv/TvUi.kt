package io.github.loliwolf.openlisttv

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.KeyEvent
import android.widget.*

object TvUi {
    var night = false; private set
    var background = Color.rgb(253, 252, 255); private set
    var card = Color.WHITE; private set
    var accent = Color.rgb(0, 96, 169); private set
    var muted = Color.rgb(85, 98, 116); private set
    var foreground = Color.rgb(26, 28, 30); private set
    var tint = Color.rgb(211, 228, 255); private set
    fun configure(c: Context) {
        night = c.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        background = Color.parseColor(if (night) "#111111" else "#FDFCFF")
        card = Color.parseColor(if (night) "#1D2026" else "#FFFFFF")
        accent = Color.parseColor(if (night) "#A2C9FF" else "#0060A9")
        muted = Color.parseColor(if (night) "#C3C6CF" else "#596879")
        foreground = Color.parseColor(if (night) "#E3E2E6" else "#1A1C1E")
        tint = Color.parseColor(if (night) "#23384D" else "#D3E4FF")
    }
    fun screenBackground() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(if (night) background else tint, background, background))
    fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    fun shape(color: Int, radius: Float = 12f) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
    fun focusBackground(dark: Boolean = false, radius: Float = 18f) = StateListDrawable().apply {
        val focus = if (dark) Color.rgb(43, 67, 97) else tint
        val stroke = if (dark) Color.rgb(162, 201, 255) else accent
        addState(intArrayOf(android.R.attr.state_focused), shape(focus, radius).apply { setStroke(3, stroke) })
        addState(intArrayOf(android.R.attr.state_pressed), shape(focus, radius))
        addState(intArrayOf(-android.R.attr.state_enabled), shape(if (dark) Color.rgb(35, 42, 52) else Color.TRANSPARENT, radius))
        addState(intArrayOf(), shape(if (dark) Color.rgb(30, 43, 61) else card, radius))
    }
    fun text(c: Context, value: String, size: Float = 18f, color: Int = foreground) = TextView(c).apply { text = value; textSize = size; setTextColor(color) }
    fun title(c: Context, value: String) = text(c, value, 26f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    fun column(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    fun row(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun button(c: Context, label: String, dark: Boolean = false, primary: Boolean = false, action: () -> Unit) = Button(c).apply {
        id = View.generateViewId()
        text = label; textSize = 15f; isAllCaps = false; minHeight = c.dp(44); minimumWidth = c.dp(68)
        setTextColor(ColorStateList.valueOf(if (dark) Color.WHITE else TvUi.foreground)); background = focusBackground(dark, c.dp(22).toFloat())
        if (primary) {
            setTextColor(if (night) Color.rgb(0, 49, 91) else Color.WHITE)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), shape(accent, c.dp(22).toFloat()).apply { setStroke(c.dp(3), TvUi.foreground) })
                addState(intArrayOf(), shape(accent, c.dp(22).toFloat()))
            }
        }
        setPadding(c.dp(14), c.dp(6), c.dp(14), c.dp(6)); setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-2, c.dp(44)).apply { marginEnd = c.dp(8) }
    }
    fun input(c: Context, label: String, value: String = "", unit: String = "") = LabeledInput(c, label, unit).apply {
        id = View.generateViewId()
        hint = if (unit.isEmpty()) "按确认键输入" else "输入数值"; contentDescription = label; setText(value); textSize = 20f
        // D-pad focus is navigation. Open the TV keyboard only after explicit activation.
        showSoftInputOnFocus = false
        setOnClickListener { view ->
            (c.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        setTextColor(TvUi.foreground); setHintTextColor(muted); isSingleLine = true; minHeight = c.dp(68)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(c.dp(16), c.dp(25), c.dp(if (unit.isEmpty()) 16 else 52), c.dp(7))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(tint, c.dp(12).toFloat()).apply { setStroke(c.dp(2), accent) })
            addState(intArrayOf(), shape(card, c.dp(12).toFloat()).apply { setStroke(c.dp(1), Color.parseColor(if (night) "#465565" else "#CAD5E2")) })
        }
        layoutParams = LinearLayout.LayoutParams(-1, c.dp(68)).apply { bottomMargin = c.dp(12) }
        setOnKeyListener { view, keyCode, event ->
            if (keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) view.performClick()
                true
            } else if (keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener true
                val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) View.FOCUS_DOWN else View.FOCUS_UP
                val explicitId = if (direction == View.FOCUS_DOWN) view.nextFocusDownId else view.nextFocusUpId
                val target = if (explicitId != View.NO_ID) view.rootView.findViewById<View>(explicitId) else view.focusSearch(direction)
                if (target != null && target !== view) {
                    (c.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
                    target.requestFocus(); true
                } else false
            } else false
        }
    }
    fun check(c: Context, label: String, checked: Boolean) = CheckBox(c).apply { id = View.generateViewId(); isFocusable = true; text = label; textSize = 16f; setTextColor(TvUi.foreground); isChecked = checked; minHeight = c.dp(44) }
    fun verticalFocus(vararg views: View) {
        views.forEachIndexed { index, view ->
            if (view.id == View.NO_ID) view.id = View.generateViewId()
            if (index > 0) view.nextFocusUpId = views[index - 1].id
            if (index < views.lastIndex) view.nextFocusDownId = views[index + 1].id
        }
    }
    fun spacer(c: Context, height: Int) = View(c).apply { layoutParams = LinearLayout.LayoutParams(1, c.dp(height)) }
    fun ellipsis(v: TextView) { v.maxLines = 1; v.ellipsize = TextUtils.TruncateAt.END }

    class LabeledInput(c: Context, private val caption: String, private val unit: String) : EditText(c) {
        private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12.5f * resources.displayMetrics.scaledDensity; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            captionPaint.color = if (hasFocus()) accent else muted
            canvas.save(); canvas.translate(scrollX.toFloat(), scrollY.toFloat())
            canvas.save(); canvas.clipRect(0, 0, width, context.dp(24))
            canvas.drawText(caption, context.dp(16).toFloat(), context.dp(19).toFloat(), captionPaint); canvas.restore()
            if (unit.isNotEmpty()) canvas.drawText(unit, (width - context.dp(36)).toFloat(), (height - context.dp(19)).toFloat(), captionPaint)
            canvas.restore()
        }
    }
}

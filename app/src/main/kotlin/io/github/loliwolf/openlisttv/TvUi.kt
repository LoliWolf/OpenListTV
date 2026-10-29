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
    var background = 0xFFF3F5F0.toInt(); private set
    var card = 0xFFFCFDF9.toInt(); private set
    var accent = 0xFF246653.toInt(); private set
    var muted = 0xFF5D7064.toInt(); private set
    var foreground = 0xFF1E3027.toInt(); private set
    var tint = 0xFFDFEDE1.toInt(); private set
    fun configure(c: Context) {
        night = c.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        background = Color.parseColor(if (night) "#111C19" else "#F3F5F0")
        card = Color.parseColor(if (night) "#1A2923" else "#FCFDF9")
        accent = Color.parseColor(if (night) "#A6DCC6" else "#246653")
        muted = Color.parseColor(if (night) "#A8BBB1" else "#5D7064")
        foreground = Color.parseColor(if (night) "#ECF3EA" else "#1E3027")
        tint = Color.parseColor(if (night) "#294638" else "#DFEDE1")
    }
    fun screenBackground() = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(if (night) background else 0xFFE8EFE5.toInt(), background, background))
    fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    fun shape(color: Int, radius: Float = 12f) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
    fun focusBackground(dark: Boolean = false, radius: Float = 18f) = StateListDrawable().apply {
        val focus = if (dark) 0xFF314C3F.toInt() else tint
        val stroke = if (dark) 0xFFB6E5CC.toInt() else accent
        addState(intArrayOf(android.R.attr.state_focused), shape(focus, radius).apply { setStroke(3, stroke) })
        addState(intArrayOf(android.R.attr.state_pressed), shape(focus, radius))
        addState(intArrayOf(android.R.attr.state_selected), shape(focus, radius))
        addState(intArrayOf(-android.R.attr.state_enabled), shape(if (dark) Color.rgb(35, 42, 52) else Color.TRANSPARENT, radius))
        addState(intArrayOf(), shape(if (dark) 0x522B4035 else card, radius))
    }
    fun text(c: Context, value: String, size: Float = 18f, color: Int = foreground) = TextView(c).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false
    }
    fun title(c: Context, value: String) = text(c, value, 24f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    fun column(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    fun row(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun button(c: Context, label: String, dark: Boolean = false, primary: Boolean = false, action: () -> Unit) = Button(c).apply {
        id = View.generateViewId()
        text = label; textSize = 14f; isAllCaps = false; minHeight = c.dp(44); minWidth = c.dp(60); minimumWidth = c.dp(60)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); stateListAnimator = null
        val disabled = if (dark) 0xFF81978A.toInt() else 0xFF8C9A90.toInt()
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(disabled, if (dark) Color.WHITE else TvUi.foreground)))
        background = focusBackground(dark, c.dp(10).toFloat())
        if (primary) {
            setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(disabled, if (night || dark) 0xFF10291D.toInt() else Color.WHITE)))
            val fill = if (dark) 0xFFB6E5CC.toInt() else accent
            background = StateListDrawable().apply {
                addState(intArrayOf(-android.R.attr.state_enabled), shape(if (dark) 0xFF2A3B30.toInt() else tint, c.dp(10).toFloat()))
                addState(intArrayOf(android.R.attr.state_focused), shape(fill, c.dp(10).toFloat()).apply { setStroke(c.dp(3), if (dark) Color.WHITE else TvUi.foreground) })
                addState(intArrayOf(), shape(fill, c.dp(10).toFloat()))
            }
        }
        setPadding(c.dp(16), c.dp(6), c.dp(16), c.dp(6)); setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-2, c.dp(44)).apply { marginEnd = c.dp(8) }
    }
    fun input(c: Context, label: String, value: String = "", unit: String = "") = LabeledInput(c, label, unit).apply {
        id = View.generateViewId()
        hint = if (unit.isEmpty()) "按确认键输入" else "输入数值"; contentDescription = label; setText(value); textSize = 18f
        // D-pad focus is navigation. Open the TV keyboard only after explicit activation.
        showSoftInputOnFocus = false
        setOnClickListener { view ->
            (c.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        setTextColor(TvUi.foreground); setHintTextColor(muted); isSingleLine = true; minHeight = c.dp(62)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(c.dp(16), c.dp(25), c.dp(if (unit.isEmpty()) 16 else 52), c.dp(7))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(tint, c.dp(12).toFloat()).apply { setStroke(c.dp(2), accent) })
            addState(intArrayOf(), shape(card, c.dp(12).toFloat()).apply { setStroke(c.dp(1), Color.parseColor(if (night) "#3C5045" else "#D5DED3")) })
        }
        layoutParams = LinearLayout.LayoutParams(-1, c.dp(62)).apply { bottomMargin = c.dp(10) }
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
    fun check(c: Context, label: String, checked: Boolean) = CheckBox(c).apply {
        id = View.generateViewId(); isFocusable = true; text = label; contentDescription = label
        textSize = 15f; setTextColor(TvUi.foreground); isChecked = checked; minHeight = c.dp(48)
        background = focusBackground(radius = c.dp(10).toFloat())
        buttonTintList = ColorStateList.valueOf(accent)
    }
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

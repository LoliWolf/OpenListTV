package io.github.loliwolf.openlisttv

import android.content.Context
import android.view.View
import android.view.ViewGroup
import io.github.loliwolf.openlisttv.TvUi.dp

/** Small native wrapping action bar: one TV row, multiple rows on a narrow display. */
class ActionFlow(context: Context) : ViewGroup(context) {
    private val gap = context.dp(8)
    override fun generateDefaultLayoutParams() = MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(source: LayoutParams) = MarginLayoutParams(source)
    override fun checkLayoutParams(params: LayoutParams) = params is MarginLayoutParams
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        var used = 0; var rowHeight = 0; var height = 0; var widest = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i); if (child.visibility == View.GONE) continue
            val params = child.layoutParams as MarginLayoutParams
            params.resolveLayoutDirection(layoutDirection)
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val w = child.measuredWidth + params.leftMargin + params.rightMargin
            val h = child.measuredHeight + params.topMargin + params.bottomMargin
            if (used > 0 && used + w > available) { widest = maxOf(widest, used); height += rowHeight + gap; used = 0; rowHeight = 0 }
            used += w; rowHeight = maxOf(rowHeight, h)
        }
        setMeasuredDimension(resolveSize(maxOf(widest, used) + paddingLeft + paddingRight, widthMeasureSpec), resolveSize(height + rowHeight + paddingTop + paddingBottom, heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val available = width - paddingLeft - paddingRight
        var used = 0; var y = paddingTop; var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i); if (child.visibility == View.GONE) continue
            val params = child.layoutParams as MarginLayoutParams
            val w = child.measuredWidth + params.leftMargin + params.rightMargin
            val h = child.measuredHeight + params.topMargin + params.bottomMargin
            if (used > 0 && used + w > available) { y += rowHeight + gap; used = 0; rowHeight = 0 }
            val x = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) width - paddingRight - used - params.rightMargin - child.measuredWidth else paddingLeft + used + params.leftMargin
            child.layout(x, y + params.topMargin, x + child.measuredWidth, y + params.topMargin + child.measuredHeight)
            used += w; rowHeight = maxOf(rowHeight, h)
        }
    }
}

package io.github.loliwolf.openlisttv

import android.graphics.*
import android.graphics.drawable.Drawable
import io.github.loliwolf.openlisttv.core.*

/** Small code-native icons, not copied assets or extra image libraries. */
class FileGlyph(private val entry: Entry?) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(bounds.width() / 48f, bounds.height() / 48f)
        val color = if (entry?.directory == true) Color.rgb(65, 137, 207) else when (entry?.kind) {
            MediaKind.IMAGE -> Color.rgb(70, 151, 190); MediaKind.VIDEO -> Color.rgb(129, 103, 171); MediaKind.AUDIO -> Color.rgb(46, 150, 137); else -> Color.rgb(141, 151, 167)
        }
        paint.style = Paint.Style.FILL; paint.color = color
        if (entry?.directory == true) {
            canvas.drawRoundRect(5f, 12f, 43f, 38f, 5f, 5f, paint); canvas.drawRoundRect(5f, 8f, 24f, 22f, 4f, 4f, paint)
            paint.color = Color.argb(75, 255, 255, 255); canvas.drawRoundRect(7f, 17f, 41f, 36f, 3f, 3f, paint)
        } else {
            canvas.drawRoundRect(8f, 5f, 40f, 43f, 6f, 6f, paint)
            paint.color = Color.WHITE
            when (entry?.kind) {
                MediaKind.VIDEO -> canvas.drawPath(Path().apply { moveTo(20f, 15f); lineTo(33f, 24f); lineTo(20f, 33f); close() }, paint)
                MediaKind.IMAGE -> { canvas.drawCircle(18f, 17f, 3f, paint); canvas.drawPath(Path().apply { moveTo(12f, 35f); lineTo(23f, 22f); lineTo(28f, 28f); lineTo(33f, 22f); lineTo(37f, 35f); close() }, paint) }
                MediaKind.AUDIO -> { paint.strokeWidth = 3f; canvas.drawLine(28f, 13f, 28f, 32f, paint); canvas.drawLine(28f, 13f, 35f, 17f, paint); canvas.drawOval(16f, 28f, 28f, 36f, paint) }
                else -> { paint.strokeWidth = 2f; for (y in listOf(18f, 24f, 30f)) canvas.drawLine(16f, y, 32f, y, paint) }
            }
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Drawable opacity") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

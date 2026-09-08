package io.github.loliwolf.openlisttv.core

import kotlin.math.max
import kotlin.math.min

enum class VideoFraming(val label: String, val ratio: Double? = null) {
    FIT("原始比例 · 完整显示"), ZOOM("原始比例 · 铺满裁切"), STRETCH("拉伸全屏"),
    WIDE("16:9", 16.0 / 9), CLASSIC("4:3", 4.0 / 3);
    fun viewport(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || ratio == null) return width to height
        return if (width.toDouble() / height > ratio) max(1, (height * ratio).toInt()) to height
        else width to max(1, (width / ratio).toInt())
    }
}

/** Geometry only: no bitmap allocation, decoding or image copies. */
class PictureTransform {
    var zoom = 1.0; private set
    var quarterTurns = 0; private set
    private var panX = 0.0
    private var panY = 0.0
    data class Layout(val scale: Double, val rotation: Int, val x: Double, val y: Double)
    fun reset() { zoom = 1.0; quarterTurns = 0; panX = 0.0; panY = 0.0 }
    fun scaleBy(factor: Double) { require(factor.isFinite() && factor > 0); zoom = (zoom * factor).coerceIn(1.0, 4.0) }
    fun rotate(delta: Int) { quarterTurns = ((quarterTurns + delta) % 4 + 4) % 4; panX = 0.0; panY = 0.0 }
    fun pan(dx: Double, dy: Double) { require(dx.isFinite() && dy.isFinite()); panX = (panX + dx).coerceIn(-4.0, 4.0); panY = (panY + dy).coerceIn(-4.0, 4.0) }
    fun layout(imageWidth: Int, imageHeight: Int, viewWidth: Int, viewHeight: Int): Layout {
        require(imageWidth > 0 && imageHeight > 0 && viewWidth > 0 && viewHeight > 0)
        val w = if (quarterTurns % 2 == 0) imageWidth else imageHeight
        val h = if (quarterTurns % 2 == 0) imageHeight else imageWidth
        val scale = min(viewWidth.toDouble() / w, viewHeight.toDouble() / h) * zoom
        val maxX = max(0.0, (w * scale - viewWidth) / 2)
        val maxY = max(0.0, (h * scale - viewHeight) / 2)
        val x = (panX * viewWidth).coerceIn(-maxX, maxX)
        val y = (panY * viewHeight).coerceIn(-maxY, maxY)
        panX = x / viewWidth; panY = y / viewHeight
        return Layout(scale, quarterTurns * 90, viewWidth / 2.0 + x, viewHeight / 2.0 + y)
    }
}

/** Time-based TV scrubbing, independent of the remote's key-repeat frequency. */
class AcceleratingSeek(position: Long, val duration: Long, direction: Int, now: Long) {
    val origin = position.coerceIn(0, duration)
    var target = origin; private set
    var direction = direction; private set
    private var started = now
    private var last = now
    init { require(duration > 0 && direction in listOf(-1, 1)); target = clamp(origin.toDouble() + direction * 10_000.0) }
    fun rate(now: Long): Int = when ((now - started).coerceAtLeast(0)) { in 0..349 -> 0; in 350..1999 -> 15; in 2000..4999 -> 45; in 5000..9999 -> 120; else -> 300 }
    fun advance(now: Long): Long {
        val end = max(last, now)
        val delta = distance(end - started) - distance(last - started)
        target = clamp(target.toDouble() + direction * delta); last = end
        return target
    }
    fun reverse(next: Int, now: Long) {
        require(next in listOf(-1, 1)); advance(now)
        if (next == direction) return
        direction = next; started = now; last = now
        target = clamp(target.toDouble() + direction * 10_000.0)
    }
    private fun clamp(value: Double) = value.coerceIn(0.0, duration.toDouble()).toLong()
    private fun distance(ms: Long): Double {
        val t = ms.coerceAtLeast(0)
        return (t.coerceIn(350, 2000) - 350) * 15.0 + (t.coerceIn(2000, 5000) - 2000) * 45.0 + (t.coerceIn(5000, 10000) - 5000) * 120.0 + (t - 10000).coerceAtLeast(0) * 300.0
    }
}

package io.github.loliwolf.openlisttv.core

enum class BrowserType(val label: String) {
    ALL("全部类型"), IMAGE("图片"), VIDEO("视频"), AUDIO("音频"), FOLDER("文件夹"), OTHER("其他文件")
}

/** A current-directory view only; traversal never inherits these UI constraints. */
data class BrowserQuery(val text: String = "", val type: BrowserType = BrowserType.ALL) {
    init { require(text.length <= 128) }
    val active get() = text.isNotEmpty() || type != BrowserType.ALL
    val description get() = listOfNotNull(text.takeIf { it.isNotEmpty() }?.let { "搜索：$it" }, type.takeIf { it != BrowserType.ALL }?.label).joinToString(" · ")
}

/** Monotonic session deadline. Pausing or moving to the background doesn't extend it. */
class SlideshowDeadline {
    private var deadline: Long? = null
    fun set(durationMillis: Long, now: Long) {
        require(durationMillis in 0..86_400_000L)
        deadline = if (durationMillis == 0L) null else Math.addExact(now, durationMillis)
    }
    fun remaining(now: Long): Long? = deadline?.let { (it - now).coerceAtLeast(0) }
    fun expired(now: Long) = remaining(now) == 0L
}

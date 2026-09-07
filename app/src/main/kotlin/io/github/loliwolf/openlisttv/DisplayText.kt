package io.github.loliwolf.openlisttv

import java.util.Locale

object DisplayText {
    fun bytes(value: Long): String = when {
        value < 0 -> "大小未知"
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KiB", value / 1024.0)
        value < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MiB", value / (1024.0 * 1024))
        else -> String.format(Locale.ROOT, "%.2f GiB", value / (1024.0 * 1024 * 1024))
    }
}

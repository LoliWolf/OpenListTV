package io.github.loliwolf.openlisttv.core

/** SemVer comparison: a stable version outranks its preview, never compare tags lexically. */
data class AppVersion(val major: Int, val minor: Int, val patch: Int, val prerelease: List<String> = emptyList()) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }).takeIf { it != 0 }?.let { return it }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) return when { prerelease.isEmpty() && other.prerelease.isEmpty() -> 0; prerelease.isEmpty() -> 1; else -> -1 }
        for (i in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val a = prerelease[i]; val b = other.prerelease[i]
            val an = a.all(Char::isDigit); val bn = b.all(Char::isDigit)
            val cmp = when { an && bn -> a.length.compareTo(b.length).takeIf { it != 0 } ?: a.compareTo(b); an -> -1; bn -> 1; else -> a.compareTo(b) }
            if (cmp != 0) return cmp
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }
    companion object {
        private val pattern = Regex("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")
        fun parse(raw: String): AppVersion? {
            val m = pattern.matchEntire(raw.trim().takeIf { it.length <= 128 } ?: return null) ?: return null
            val pre = m.groupValues[4].takeIf(String::isNotEmpty)?.split('.').orEmpty()
            if (pre.any { it.length > 1 && it.all(Char::isDigit) && it.startsWith('0') }) return null
            return AppVersion(m.groupValues[1].toIntOrNull() ?: return null, m.groupValues[2].toIntOrNull() ?: return null, m.groupValues[3].toIntOrNull() ?: return null, pre)
        }
    }
}

package io.github.loliwolf.openlisttv.core

import java.security.MessageDigest
import java.util.Locale

data class Site(val id: String, val label: String, val url: String, val username: String, val allowHttp: Boolean = false)
data class Session(val site: Site, val token: String, val basePath: String = "/") {
    override fun toString() = "Session(siteId=${site.id}, token=[redacted], basePath=[redacted])"
}
enum class MediaKind { IMAGE, VIDEO, AUDIO, OTHER }
enum class SortField(val label: String) { NAME("名称"), MODIFIED("修改时间"), SIZE("文件大小"), SERVER("服务器顺序") }
data class SortOrder(val field: SortField = SortField.NAME, val descending: Boolean = false, val foldersFirst: Boolean = true)
data class Entry(
    val path: String, val name: String, val directory: Boolean,
    val size: Long = -1, val modified: String = "", val sign: String = "", val ordinal: Int = 0,
    val thumbnail: String = "",
) {
    val kind: MediaKind get() = if (directory) MediaKind.OTHER else MediaTypes.of(name)
}
data class Listing(val entries: List<Entry>, val total: Int)
data class MediaSource(val entry: Entry, val url: String, val subtitles: List<Subtitle> = emptyList())
data class Subtitle(val name: String, val url: String, val mime: String)

object MediaTypes {
    private val images = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "avif")
    private val videos = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "m2ts", "m3u8", "mpg", "mpeg")
    private val audio = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "oga")
    fun of(name: String): MediaKind = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        in images -> MediaKind.IMAGE
        in videos -> MediaKind.VIDEO
        in audio -> MediaKind.AUDIO
        else -> MediaKind.OTHER
    }
}

object Paths {
    fun child(parent: String, name: String): String {
        require(name.isNotEmpty() && name !in setOf(".", "..") && '/' !in name && '\u0000' !in name) { "服务器返回了无效文件名" }
        return if (parent == "/") "/$name" else "${parent.trimEnd('/')}/$name"
    }
    fun parent(path: String): String = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
    fun inside(root: String, path: String) = path == root || path.startsWith(if (root == "/") "/" else "$root/")
    fun depth(root: String, path: String) = path.removePrefix(root).count { it == '/' }
}

object NaturalOrder {
    private val tokens = Regex("[0-9]+|[^0-9]+")
    /** A disk-sortable key; numbers are ordered by magnitude without Long overflow. */
    fun key(name: String): String = tokens.findAll(name.lowercase(Locale.ROOT)).joinToString("") {
        val part = it.value
        if (part[0] in '0'..'9') {
            val number = part.trimStart('0').ifEmpty { "0" }
            "\u0001${number.length.toString().padStart(6, '0')}$number\u0002"
        } else part
    }
    val comparator: Comparator<String> = compareBy<String> { key(it) }.thenBy { it }
}

object OpenListPassword {
    fun hash(password: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$password-https://github.com/alist-org/alist".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

enum class FailureKind { AUTH, ACCESS, CHALLENGE, RATE_LIMIT, NETWORK, SERVER, INVALID, CAPACITY, RESOURCE_LIMIT, UNSUPPORTED, STALE_LINK }
class ClientFailure(val kind: FailureKind, message: String, cause: Throwable? = null) : Exception(message, cause)

object PlaybackFailures {
    fun requiresPause(kind: FailureKind?) = kind in setOf(FailureKind.AUTH, FailureKind.CHALLENGE, FailureKind.RATE_LIMIT, FailureKind.NETWORK, FailureKind.CAPACITY)
}

interface AuthProvider {
    suspend fun login(site: Site, password: String): Session
    suspend fun validate(session: Session): Session
    suspend fun logout(session: Session)
}
interface FileGateway {
    suspend fun list(session: Session, path: String, password: String, page: Int, perPage: Int): Listing
    suspend fun resolve(session: Session, entry: Entry, password: String): MediaSource
}

object Budgets {
    const val PAGE = 100
    // Metadata indexing only; visible rows/thumbnails still use PAGE and a two-page window.
    const val INDEX_PAGE = 500
    const val MAX_ENTRIES = 100_000
    const val MAX_DEPTH = 128
    const val INDEX_BYTES = 64L * 1024 * 1024
    const val IMAGE_DISK_BYTES = 128L * 1024 * 1024
    const val THUMBNAIL_SOURCE_BYTES = 32L * 1024 * 1024
    const val DISK_RESERVE_BYTES = 8L * 1024 * 1024
    const val METADATA_RESPONSE_BYTES = 4L * 1024 * 1024
    const val PLAYER_BUFFER_BYTES = 24 * 1024 * 1024
    const val HISTORY = 50
    const val CONSECUTIVE_FAILURES = 10
    const val THUMBNAIL_MEMORY_BYTES = 8 * 1024 * 1024
    const val THUMBNAIL_DISK_BYTES = 16L * 1024 * 1024
    const val SLIDESHOW_IMAGE_BYTES = 64L * 1024 * 1024
}

data class PageWindow(val current: Int, val next: Int?) {
    val pages: Set<Int> get() = setOfNotNull(current, next)
    fun contains(position: Int) = position >= 0 && position / Budgets.PAGE in pages
    companion object {
        fun at(firstVisible: Int, total: Int): PageWindow {
            val last = ((total - 1).coerceAtLeast(0)) / Budgets.PAGE
            val current = (firstVisible.coerceAtLeast(0) / Budgets.PAGE).coerceAtMost(last)
            return PageWindow(current, (current + 1).takeIf { it <= last })
        }
    }
}

object ImageBudget {
    /** Two owned slide bitmaps share at most 1/4 of the heap, capped at 64 MiB.
     * Decoder/rotation scratch still uses the existing available-heap reserve. */
    fun slideshowMaxPixels(screenWidth: Int, screenHeight: Int, availableHeap: Long, heapLimit: Long, lowMemory: Boolean): Long {
        val pairBytes = minOf(Budgets.SLIDESHOW_IMAGE_BYTES, heapLimit.coerceAtLeast(1) / 4)
        return minOf(maxPixels(screenWidth, screenHeight, availableHeap, lowMemory), (pairBytes / 2 / 4).coerceAtLeast(1))
    }
    fun maxPixels(screenWidth: Int, screenHeight: Int, availableHeap: Long, lowMemory: Boolean): Long {
        val display = screenWidth.toLong().coerceAtLeast(1) * screenHeight.coerceAtLeast(1)
        // Reserve room for a rotation copy and decoder scratch, not just the displayed bitmap.
        val memoryPixels = (availableHeap / 4 / 4).coerceAtLeast(1)
        val ceiling = if (lowMemory) 1280L * 720 else 3840L * 2160
        return minOf(display, ceiling, memoryPixels)
    }
    fun sampleSize(width: Int, height: Int, maxPixels: Long): Int {
        require(width > 0 && height > 0 && maxPixels > 0)
        var sample = 1
        while ((width.toLong() / sample).coerceAtLeast(1) * (height.toLong() / sample).coerceAtLeast(1) > maxPixels && sample < 32768) sample *= 2
        return sample
    }
}

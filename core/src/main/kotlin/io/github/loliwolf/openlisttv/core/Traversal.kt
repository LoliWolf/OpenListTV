package io.github.loliwolf.openlisttv.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.random.Random

enum class TraversalMode(val label: String) { ORDERED("顺序深度遍历"), WALK("随机游走"), SHUFFLE("全目录树随机"), RESERVOIR("超大库公平抽样") }
data class SlideshowOptions(
    val mode: TraversalMode = TraversalMode.ORDERED,
    val recursive: Boolean = true,
    val imageOnly: Boolean = false,
    val imageSeconds: Int = 8,
    val videoSeconds: Int = 0,
    val audioSeconds: Int = 0,
    val prefetch: Boolean = true,
    val stopAfterMinutes: Int = 0,
    val reuseIndex: Boolean = true,
    val forceRescan: Boolean = false,
    val autoRefreshMinutes: Int = 0,
) {
    init {
        require(imageSeconds in 1..3600)
        require(videoSeconds in 0..3600 && audioSeconds in 0..3600)
        require(stopAfterMinutes in 0..1440)
        require(autoRefreshMinutes == 0 || autoRefreshMinutes in 5..1440)
    }
    fun accepts(e: Entry) = !e.directory && e.kind != MediaKind.OTHER && (!imageOnly || e.kind == MediaKind.IMAGE) && !e.name.startsWith("._")
}
enum class EntryFilter { MEDIA, FOLDERS, IMAGES }

/** Implementations page from bounded storage; a strategy must never own a whole tree. */
interface DirectoryReader {
    suspend fun page(path: String, offset: Int, filter: EntryFilter): Listing
}
interface TraversalSource {
    val description: String
    val warning: String? get() = null
    suspend fun next(): Entry?
    fun close() {}
}

enum class RefreshRequest { STARTED, BUSY, UNAVAILABLE }
data class IndexStatus(
    val count: Int, val seen: Long, val scanning: Boolean, val refreshing: Boolean,
    val scannedCount: Int, val scannedSeen: Long, val directories: Int,
    val updatedAt: Long?, val reused: Boolean, val limited: String?, val message: String?,
    val automaticPaused: Boolean = false,
)
interface RefreshableTraversal : TraversalSource {
    val indexStatus: IndexStatus
    /** Non-blocking, single-flight; never changes the currently displayed/prepared media. */
    fun requestRefresh(): RefreshRequest
}

class OrderedTraversal(private val root: String, private val reader: DirectoryReader, private val options: SlideshowOptions) : TraversalSource {
    private data class Frame(val path: String, var offset: Int = 0, var folders: Boolean = false)
    private val stack = ArrayDeque<Frame>()
    private var yielded = false
    override val description = "顺序深度遍历"
    override suspend fun next(): Entry? {
        if (stack.isEmpty()) stack.addLast(Frame(root))
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val frame = stack.last()
            val filter = if (frame.folders) EntryFilter.FOLDERS else if (options.imageOnly) EntryFilter.IMAGES else EntryFilter.MEDIA
            val page = try { reader.page(frame.path, frame.offset, filter) } catch (e: ClientFailure) {
                if (e.kind != FailureKind.ACCESS) throw e
                stack.removeLast(); continue
            }
            if (page.entries.isNotEmpty()) {
                val entry = page.entries.first()
                frame.offset++
                if (frame.folders) {
                    if (stack.size >= Budgets.MAX_DEPTH) throw ClientFailure(FailureKind.CAPACITY, "目录超过 128 层，请缩小轮播范围")
                    stack.addLast(Frame(entry.path))
                } else if (options.accepts(entry)) { yielded = true; return entry }
            } else if (!frame.folders && options.recursive) { frame.folders = true; frame.offset = 0 }
            else stack.removeLast()
            if (stack.isEmpty() && yielded) { yielded = false; stack.addLast(Frame(root)) }
        }
        return null
    }
}

class RandomWalkTraversal(private val root: String, private val reader: DirectoryReader, private val options: SlideshowOptions, private val random: Random = Random.Default) : TraversalSource {
    private var path = root
    override val description = "随机游走（非全库均匀随机）"
    override suspend fun next(): Entry? {
        repeat(512) {
            currentCoroutineContext().ensureActive()
            val filter = if (options.imageOnly) EntryFilter.IMAGES else EntryFilter.MEDIA
            val media = try { reader.page(path, 0, filter) } catch (e: ClientFailure) {
                if (e.kind != FailureKind.ACCESS) throw e
                if (path == root) return null
                path = Paths.parent(path); return@repeat
            }
            val folders = if (options.recursive) reader.page(path, 0, EntryFilter.FOLDERS) else Listing(emptyList(), 0)
            if (media.total > 0 && (folders.total == 0 || random.nextBoolean())) {
                val e = reader.page(path, random.nextInt(media.total), filter).entries.firstOrNull()
                if (e != null && options.accepts(e)) return e
            }
            if (path != root && (folders.total == 0 || random.nextInt(4) == 0)) path = Paths.parent(path)
            else if (folders.total > 0 && Paths.depth(root, path) < Budgets.MAX_DEPTH - 1) {
                path = reader.page(path, random.nextInt(folders.total), EntryFilter.FOLDERS).entries.firstOrNull()?.path ?: root
            } else if (media.total == 0) { if (path == root) return null else path = root }
        }
        throw ClientFailure(FailureKind.CAPACITY, "本次随机探索未找到可播放资源，请换目录或使用全目录树扫描")
    }
}

/** Bounded back/forward history is independent of all traversal algorithms. */
class PlaybackHistory(private val limit: Int = Budgets.HISTORY) {
    private val items = ArrayList<Entry>()
    private var cursor = -1
    fun add(entry: Entry) {
        while (items.size > cursor + 1) items.removeAt(items.lastIndex)
        items.add(entry)
        if (items.size > limit) items.removeAt(0)
        cursor = items.lastIndex
    }
    fun previous(): Entry? = if (cursor > 0) items[--cursor] else null
    fun forward(): Entry? = if (cursor + 1 < items.size) items[++cursor] else null
    fun peekForward(): Entry? = items.getOrNull(cursor + 1)
    val size get() = items.size
}

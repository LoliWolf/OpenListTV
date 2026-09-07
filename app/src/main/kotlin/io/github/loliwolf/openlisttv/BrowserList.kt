package io.github.loliwolf.openlisttv

import android.graphics.Canvas
import android.graphics.Paint
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.loliwolf.openlisttv.TvUi.dp
import io.github.loliwolf.openlisttv.core.*
import io.github.loliwolf.openlisttv.data.*
import io.github.loliwolf.openlisttv.media.ThumbnailLoader
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Virtual list: the database owns the directory; the UI owns only a two-page window. */
class BrowserList(
    private val activity: android.app.Activity,
    private val repository: FileRepository,
    private val path: String,
    private val order: SortOrder,
    initial: Listing,
    initialOffset: Int,
    parent: CoroutineScope,
    private val opened: (Entry, Int) -> Unit,
    private val focused: (Entry) -> Unit,
    private val pageChanged: (Int) -> Unit,
    private val problem: (Exception) -> Unit,
    private val query: BrowserQuery = BrowserQuery(),
    private val actions: (Entry) -> Unit = {},
) : LinearLayout(activity) {
    private val total = initial.total
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val pages = linkedMapOf(initialOffset / Budgets.PAGE to initial.entries)
    private val jobs = mutableMapOf<Int, Job>()
    private val failedPages = mutableSetOf<Int>()
    private var window = PageWindow.at(initialOffset, total)
    private var closed = false
    private var paused = false
    private var lastVisible = -1
    private var pendingFocus: Int? = null
    private var compact = resources.configuration.screenWidthDp < 600
    private val columns = TvUi.row(activity)
    private val nameColumn = TvUi.text(activity, "", 12f, TvUi.muted)
    private val sizeColumn = TvUi.text(activity, "大小", 12f, TvUi.muted).apply { gravity = Gravity.END }
    private val manager = LinearLayoutManager(activity).apply { isItemPrefetchEnabled = false }
    private val adapter = FilesAdapter()
    private val list = RecyclerView(activity).apply {
        layoutManager = manager; itemAnimator = null; setHasFixedSize(true); isFocusable = false
        background = TvUi.shape(TvUi.card, activity.dp(18).toFloat()); clipToOutline = true
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }
    private val summary = TvUi.text(activity, "", 13f, TvUi.muted).also(TvUi::ellipsis)
    private val previous = TvUi.button(activity, "上一页") { jumpPage(-1) }
    private val next = TvUi.button(activity, "下一页") { jumpPage(1) }
    private val thumbnails = ThumbnailLoader(activity.applicationContext, HttpClients.media(repository.session.site.allowHttp), scope,
        resolve = { entry, preferThumb ->
            if (preferThumb && entry.thumbnail.isNotBlank()) {
                val base = repository.session.site.url.toHttpUrlOrNull()!!
                val url = base.resolve(entry.thumbnail) ?: throw ClientFailure(FailureKind.INVALID, "无效缩略图地址")
                ServerAddress.requireSafe(url, repository.session.site.allowHttp); url.toString()
            } else repository.resolve(entry).url
        }, onReady = { loaded ->
            // A bitmap does not change row data/geometry. Avoid RecyclerView re-layouts
            // for every preview completion, which can interrupt D-pad focus recovery.
            for (i in 0 until list.childCount) {
                val holder = list.getChildViewHolder(list.getChildAt(i)) as? FileHolder ?: continue
                if (entry(holder.bindingAdapterPosition)?.path == loaded.path) adapter.bindPreview(holder)
            }
        }, onProblem = { summary.text = it })

    init {
        orientation = VERTICAL
        columns.addView(nameColumn, LayoutParams(0, -2, 1f))
        columns.addView(sizeColumn, LayoutParams(activity.dp(90), -2))
        addView(columns)
        adapter.setHasStableIds(true); list.adapter = adapter
        list.addItemDecoration(object : RecyclerView.ItemDecoration() {
            private val line = Paint().apply { color = if (TvUi.night) 0xFF31363D.toInt() else 0xFFEAF0F6.toInt(); strokeWidth = 1f }
            override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
                for (i in 0 until parent.childCount) { val child = parent.getChildAt(i); c.drawLine(activity.dp(if (compact) 80 else 100).toFloat(), child.bottom.toFloat(), (parent.width - activity.dp(20)).toFloat(), child.bottom.toFloat(), line) }
            }
        })
        addView(list, LayoutParams(-1, 0, 1f))
        val footer = TvUi.row(activity).apply { setPadding(0, activity.dp(10), 0, 0) }
        footer.addView(summary, LayoutParams(0, -2, 1f)); footer.addView(previous); footer.addView(next); addView(footer)
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val first = manager.findFirstVisibleItemPosition().coerceAtLeast(0)
                if (first != lastVisible) { lastVisible = first; setWindow(first); updateThumbnails() }
                list.post { fulfillFocus() }
            }
        })
        list.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) { list.post { fulfillFocus() } }
            override fun onChildViewDetachedFromWindow(view: View) = Unit
        })
        updateCompactLayout(); list.scrollToPosition(initialOffset)
        list.post { ensurePages(); updateThumbnails() }
    }
    private fun entry(position: Int): Entry? = if (position < 0) null else pages[position / Budgets.PAGE]?.getOrNull(position % Budgets.PAGE)
    private fun setWindow(position: Int) {
        val selected = PageWindow.at(position, total)
        if (selected != window) {
            window = selected
            jobs.filterKeys { it !in window.pages }.values.toList().forEach { it.cancel() }
            pages.keys.retainAll(window.pages); failedPages.retainAll(window.pages)
            pageChanged(window.current * Budgets.PAGE); updateFooter()
        }
        ensurePages()
    }
    private fun ensurePages() {
        if (closed || paused) return
        for (page in window.pages) {
            if (page in pages || page in jobs || page in failedPages || jobs.size >= 2) continue
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result = if (!query.active && order.field == SortField.SERVER && !order.foldersFirst) repository.remotePage(path, page * Budgets.PAGE) else repository.page(path, page * Budgets.PAGE, order, query = query)
                    if (result.total != total) throw ClientFailure(FailureKind.SERVER, "目录内容已变化，请重新加载")
                    if (!closed && page in window.pages) {
                        pages[page] = result.entries
                        adapter.notifyItemRangeChanged(page * Budgets.PAGE, result.entries.size)
                        updateThumbnails()
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failedPages += page; problem(e) }
                finally { jobs.remove(page); ensurePages() }
            }
            jobs[page] = job; job.start()
        }
    }
    private fun updateThumbnails() {
        if (closed || paused) return
        val first = manager.findFirstVisibleItemPosition().coerceAtLeast(window.current * Budgets.PAGE)
        val last = manager.findLastVisibleItemPosition().coerceAtLeast(first)
        val visible = (first..last).mapNotNull(::entry)
        thumbnails.window(pages.filterKeys { it in window.pages }.toSortedMap().values.flatten(), visible)
    }
    private fun updateFooter() {
        previous.isEnabled = window.current > 0; next.isEnabled = window.next != null
        val pageCount = ((total + Budgets.PAGE - 1) / Budgets.PAGE).coerceAtLeast(1)
        summary.text = if (compact) "第 ${window.current + 1} 页 / 共 $pageCount 页" else "第 ${window.current + 1} 页 / 共 $pageCount 页 · 上下浏览，左右翻页 · 当前＋下一页预览"
    }
    fun updateCompactLayout() {
        compact = resources.configuration.screenWidthDp < 600
        columns.setPadding(activity.dp(if (compact) 80 else 100), activity.dp(6), activity.dp(32), activity.dp(8))
        nameColumn.text = if (compact) "名称 / 信息" else "名称 / 修改时间"
        sizeColumn.visibility = if (compact) View.GONE else View.VISIBLE
        updateFooter()
        for (i in 0 until list.childCount) (list.getChildViewHolder(list.getChildAt(i)) as? FileHolder)?.let { adapter.bindDetails(it, it.bindingAdapterPosition) }
        list.invalidateItemDecorations()
    }
    fun jumpPage(delta: Int) {
        val target = (window.current + delta) * Budgets.PAGE
        if (target < 0 || target >= total) return
        setWindow(target); requestPositionFocus(target, alignStart = true)
    }
    fun restoreFocus(selectedPath: String?) {
        val position = pages.entries.firstNotNullOfOrNull { (page, entries) -> entries.indexOfFirst { it.path == selectedPath }.takeIf { it >= 0 }?.let { page * Budgets.PAGE + it } } ?: window.current * Budgets.PAGE
        requestPositionFocus(position, alignStart = true)
    }
    private fun requestPositionFocus(position: Int, alignStart: Boolean = false) {
        if (closed || position !in 0 until total) return
        pendingFocus = position
        if (alignStart) manager.scrollToPositionWithOffset(position, 0)
        else if (list.findViewHolderForAdapterPosition(position) == null) list.scrollToPosition(position)
        else fulfillFocus()
        list.post { fulfillFocus(); updateThumbnails() }
    }
    private fun fulfillFocus() {
        if (closed || paused) return
        val position = pendingFocus ?: return
        val view = list.findViewHolderForAdapterPosition(position)?.itemView ?: return
        if (view.requestFocus()) pendingFocus = null
    }
    fun pauseLoading() {
        paused = true; jobs.values.toList().forEach { it.cancel() }; thumbnails.pause()
        for (i in 0 until list.childCount) (list.getChildViewHolder(list.getChildAt(i)) as? FileHolder)?.image?.setImageDrawable(null)
    }
    fun resumeLoading() {
        if (closed) return
        paused = false; failedPages.clear(); thumbnails.resume(); ensurePages()
        for (i in 0 until list.childCount) (list.getChildViewHolder(list.getChildAt(i)) as? FileHolder)?.let { adapter.bindPreview(it) }
        list.post { updateThumbnails() }
    }
    fun dispose() { if (!closed) { closed = true; thumbnails.close(); scope.cancel(); list.adapter = null; pages.clear() } }

    private inner class FilesAdapter : RecyclerView.Adapter<FileHolder>() {
        override fun getItemCount() = total
        override fun getItemId(position: Int) = position.toLong()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileHolder {
            val row = TvUi.row(activity).apply {
                isFocusable = true; isClickable = true; background = TvUi.focusBackground(false, activity.dp(12).toFloat())
                setPadding(activity.dp(14), activity.dp(8), activity.dp(18), activity.dp(8))
                layoutParams = RecyclerView.LayoutParams(-1, activity.dp(64))
            }
            val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = TvUi.shape(TvUi.tint, activity.dp(8).toFloat()); clipToOutline = true }
            row.addView(image, LayoutParams(activity.dp(64), activity.dp(44)).apply { marginEnd = activity.dp(18) })
            val column = TvUi.column(activity)
            val name = TvUi.text(activity, "", 18f).also(TvUi::ellipsis)
            val time = TvUi.text(activity, "", 12f, TvUi.muted).also(TvUi::ellipsis)
            column.addView(name); column.addView(time); row.addView(column, LayoutParams(0, -2, 1f))
            val size = TvUi.text(activity, "", 13f, TvUi.muted).apply { gravity = Gravity.END }
            row.addView(size, LayoutParams(activity.dp(90), -2))
            row.addView(TvUi.text(activity, "›", 24f, TvUi.muted).apply { gravity = Gravity.END }, LayoutParams(activity.dp(24), -2))
            return FileHolder(row, image, name, time, size)
        }
        override fun onBindViewHolder(holder: FileHolder, position: Int) {
            val e = entry(position)
            bindDetails(holder, position)
            holder.itemView.contentDescription = e?.let { "打开 ${it.name}" } ?: "加载第 ${position + 1} 项"
            bindPreview(holder, position)
            holder.itemView.setOnClickListener { val index = holder.bindingAdapterPosition; entry(index)?.let { opened(it, index) } ?: ensurePages() }
            holder.itemView.setOnLongClickListener { entry(holder.bindingAdapterPosition)?.let(actions); true }
            holder.itemView.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus -> if (hasFocus) entry(holder.bindingAdapterPosition)?.let(focused) }
            holder.itemView.setOnKeyListener { _, code, event ->
                if (code == KeyEvent.KEYCODE_MENU) { if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) entry(holder.bindingAdapterPosition)?.let(actions); true }
                else if (code != KeyEvent.KEYCODE_DPAD_DOWN && code != KeyEvent.KEYCODE_DPAD_UP) false
                else {
                    val origin = pendingFocus ?: holder.bindingAdapterPosition
                    val target = origin + if (code == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                    if (origin < 0 || target !in 0 until total) false
                    else { if (event.action == KeyEvent.ACTION_DOWN) requestPositionFocus(target); true }
                }
            }
        }
        fun bindDetails(holder: FileHolder, position: Int) {
            val e = entry(position)
            holder.name.text = e?.name ?: "正在加载…"
            val date = e?.modified?.replace('T', ' ')?.take(if (compact) 10 else 19).orEmpty()
            val size = e?.let { if (it.directory) "文件夹" else formatSize(it.size) }.orEmpty()
            holder.time.text = if (compact) listOf(size, date).filter { it.isNotEmpty() }.joinToString(" · ") else date
            holder.size.text = size; holder.size.visibility = if (compact) View.GONE else View.VISIBLE
            val width = activity.dp(if (compact) 48 else 64)
            if (holder.image.layoutParams.width != width) holder.image.layoutParams = holder.image.layoutParams.apply { this.width = width }
        }
        override fun onBindViewHolder(holder: FileHolder, position: Int, payloads: MutableList<Any>) { if (payloads.isNotEmpty()) bindPreview(holder, position) else onBindViewHolder(holder, position) }
        fun bindPreview(holder: FileHolder, position: Int = holder.bindingAdapterPosition) {
            val e = entry(position)
            val bitmap = e?.takeIf { it.kind == MediaKind.IMAGE }?.let(thumbnails::bitmap)
            holder.image.scaleType = if (bitmap != null) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.CENTER_INSIDE
            holder.image.setPadding(if (bitmap == null) activity.dp(8) else 0, 0, if (bitmap == null) activity.dp(8) else 0, 0)
            if (bitmap != null) holder.image.setImageBitmap(bitmap) else holder.image.setImageDrawable(FileGlyph(e))
            holder.image.contentDescription = if (bitmap != null) "缩略图 ${e?.name}" else null
        }
        override fun onViewAttachedToWindow(holder: FileHolder) { bindDetails(holder, holder.bindingAdapterPosition); bindPreview(holder) }
        override fun onViewDetachedFromWindow(holder: FileHolder) { holder.image.setImageDrawable(null) }
        override fun onViewRecycled(holder: FileHolder) { holder.image.setImageDrawable(null) }
    }
    private class FileHolder(view: View, val image: ImageView, val name: TextView, val time: TextView, val size: TextView) : RecyclerView.ViewHolder(view)
    private fun formatSize(bytes: Long) = when { bytes < 0 -> "—"; bytes >= 1073741824 -> "%.2f GiB".format(bytes / 1073741824.0); bytes >= 1048576 -> "%.1f MiB".format(bytes / 1048576.0); bytes >= 1024 -> "%.1f KiB".format(bytes / 1024.0); else -> "$bytes B" }
}

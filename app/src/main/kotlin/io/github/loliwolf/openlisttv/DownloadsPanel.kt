package io.github.loliwolf.openlisttv

import android.app.Activity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.loliwolf.openlisttv.TvUi.dp
import io.github.loliwolf.openlisttv.data.SavedDownload
import kotlinx.coroutines.*

/** Text-only, bounded download records; no thumbnail extraction or periodic polling. */
class DownloadsPanel(activity: Activity, controller: DownloadController, parent: CoroutineScope, choose: (SavedDownload) -> Unit) : LinearLayout(activity) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    init {
        orientation = VERTICAL
        val status = TvUi.text(activity, "", 14f, TvUi.muted).apply { maxLines = 3 }
        val pause = TvUi.button(activity, "暂停当前传输") { controller.pause() }
        addView(status); addView(pause); addView(TvUi.spacer(activity, 8))
        var records = emptyList<SavedDownload>()
        val adapter = object : RecyclerView.Adapter<Holder>() {
            override fun getItemCount() = records.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(TvUi.text(activity, "", 16f).apply {
                isFocusable = true; isClickable = true; maxLines = 3
                setPadding(activity.dp(16), activity.dp(8), activity.dp(16), activity.dp(8))
                background = TvUi.focusBackground(false, activity.dp(12).toFloat())
                layoutParams = RecyclerView.LayoutParams(-1, activity.dp(80))
            })
            override fun onBindViewHolder(holder: Holder, position: Int) {
                val item = records[position]
                holder.text.text = "${item.entry.name}\n${item.status.label} · ${DisplayText.bytes(item.bytes)} · ${item.siteLabel}"
                holder.text.contentDescription = "下载文件 ${item.entry.name}"
                holder.text.setOnClickListener { choose(item) }
            }
        }
        val list = RecyclerView(activity).apply { layoutManager = LinearLayoutManager(activity); itemAnimator = null; setHasFixedSize(true); this.adapter = adapter }
        addView(list, LayoutParams(-1, 0, 1f))
        addView(TvUi.text(activity, "已完成文件可离线打开或导出；清缓存不删除下载，卸载会删除。最多 100 条记录。", 13f, TvUi.muted))
        scope.launch {
            var lastKey = ""
            controller.state.collect { state ->
                status.text = if (state.id == null) "${state.message.ifEmpty { "选择文件查看操作；下载只在前台进行，播放优先。" }}" else "${state.phase.label} · ${state.name.take(100)}\n${DisplayText.bytes(state.bytes)} / ${DisplayText.bytes(state.total)}  ${state.message}"
                pause.isEnabled = controller.busy
                val key = "${state.id}:${state.phase}:${state.revision}"
                if (key != lastKey) {
                    lastKey = key
                    try { records = withContext(Dispatchers.IO) { controller.store.list() }; adapter.notifyDataSetChanged() }
                    catch (_: Exception) { status.text = "无法读取本机下载，请检查本机存储" }
                }
            }
        }
    }
    fun dispose() { scope.cancel() }
    private class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
}

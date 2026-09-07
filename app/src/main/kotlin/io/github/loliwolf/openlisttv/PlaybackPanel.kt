package io.github.loliwolf.openlisttv

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.*
import android.widget.*
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.lifecycle.lifecycleScope
import io.github.loliwolf.openlisttv.TvUi.dp
import io.github.loliwolf.openlisttv.core.*
import io.github.loliwolf.openlisttv.data.*
import io.github.loliwolf.openlisttv.media.ImagePipeline
import io.github.loliwolf.openlisttv.media.MediaEngine
import io.github.loliwolf.openlisttv.media.HdrSupport
import io.github.loliwolf.openlisttv.media.HdrImageWindow
import kotlinx.coroutines.*

@UnstableApi
class PlaybackPanel(
    private val activity: MainActivity,
    private val repository: FileRepository,
    private val catalog: Catalog,
    private val rootPath: String,
    private val sort: SortOrder,
    private val options: SlideshowOptions?,
    parentScope: CoroutineScope,
    private val onExit: () -> Unit,
    private val onManualNext: suspend (Int) -> Entry?,
) : FrameLayout(activity), MediaEngine.Events {
    private fun dp(value: Int) = activity.dp(value)
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val http = HttpClients.media(repository.session.site.allowHttp)
    private val images = ImagePipeline(activity, http).apply { hdrEnabled = HdrSupport.canRenderHdrImages(activity) }
    private val hdrWindow = HdrImageWindow(activity)
    private val imageView = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val playerView = PlayerView(activity).apply { useController = false; setKeepContentOnPlayerReset(false) }
    private val center = TvUi.text(activity, "正在加载…", 22f, Color.WHITE).apply { gravity = Gravity.CENTER; setPadding(dp(40), dp(20), dp(40), dp(20)) }
    private val controls = TvUi.column(activity).apply { setBackgroundColor(Color.argb(235, 14, 26, 41)); setPadding(dp(28), dp(16), dp(28), dp(16)) }
    private val title = TvUi.text(activity, "", 19f, Color.WHITE).also(TvUi::ellipsis)
    private val status = TvUi.text(activity, "", 14f, 0xFFC3C6CF.toInt())
    private val progress = TvUi.text(activity, "", 14f, 0xFFA2C9FF.toInt())
    private val pause = TvUi.button(activity, "暂停", dark = true) { togglePause() }
    private var engine: MediaEngine? = null
    private var bitmap: Bitmap? = null
    private var source: TraversalSource? = null
    private val history = PlaybackHistory()
    private var loadJob: Job? = null
    private var ticker: Job? = null
    private var hideJob: Job? = null
    private class PreparedSlide(val entry: Entry, val media: MediaSource?, var image: Bitmap? = null) {
        fun takeImage(): Bitmap? = image.also { image = null }
        fun close() { image?.recycle(); image = null }
    }
    private val nextPrepared = OneAhead<PreparedSlide>(scope) { it.close() }
    private var reservedNext: Entry? = null
    private var autoSkip: Job? = null
    private var current: Entry? = null
    private var pendingEntry: Entry? = null
    private var currentSource: MediaSource? = null
    private var paused = false
    private var suspended = false
    private var ready = false
    private var disposed = false
    private var expiredRetries = 0
    private var failures = 0
    private var skipped = 0
    private var imageElapsed = 0L
    private var resumePosition = 0L
    private val stopDeadline = SlideshowDeadline()
    private var stopJob: Job? = null
    private var activeDialog: AlertDialog? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = "播放画面：确认暂停，方向键控制，返回退出"
        setBackgroundColor(Color.BLACK)
        addView(playerView, LayoutParams(-1, -1)); addView(imageView, LayoutParams(-1, -1)); addView(center, LayoutParams(-1, -1))
        controls.addView(title); controls.addView(status); controls.addView(progress)
        val row = ActionFlow(activity)
        row.addView(TvUi.button(activity, "上一项", dark = true) { advance(-1) }); row.addView(pause)
        row.addView(TvUi.button(activity, "下一项", dark = true) { advance(1) })
        row.addView(TvUi.button(activity, "音轨 / 字幕", dark = true) { tracks() })
        row.addView(TvUi.button(activity, "播放信息", dark = true) { information() })
        if (options != null) row.addView(TvUi.button(activity, "定时停止", dark = true) { stopDialog() })
        row.addView(TvUi.button(activity, "重试", dark = true) {
            if (stopIfExpired()) return@button
            failures = 0; expiredRetries = 0; suspended = false
            if (options != null && source == null) source = createSource()
            (pendingEntry ?: current)?.let { launchPlay(it) } ?: advance(1)
        })
        row.addView(TvUi.button(activity, "退出", dark = true) { onExit() }); controls.addView(row)
        addView(controls, LayoutParams(-1, -2, Gravity.BOTTOM))
        showControls()
    }
    private fun createSource(): TraversalSource? {
        val o = options ?: return null
        val factories: Map<TraversalMode, () -> TraversalSource> = mapOf(
            TraversalMode.ORDERED to { OrderedTraversal(rootPath, repository.reader(sort), o) },
            TraversalMode.WALK to { RandomWalkTraversal(rootPath, repository.reader(sort), o) },
            TraversalMode.SHUFFLE to { ShuffleTraversal(rootPath, repository, o, scope) },
        )
        return factories.getValue(o.mode).invoke()
    }
    fun start(entry: Entry?) {
        stopDeadline.set((options?.stopAfterMinutes ?: 0) * 60_000L, SystemClock.elapsedRealtime()); armStopTimer()
        showControls(); source = createSource(); if (entry == null) advance(1) else launchPlay(entry)
    }
    private fun armStopTimer() {
        stopJob?.cancel()
        val remaining = stopDeadline.remaining(SystemClock.elapsedRealtime()) ?: return
        stopJob = scope.launch { delay(remaining); stopIfExpired() }
    }
    private fun stopIfExpired(): Boolean {
        if (disposed) return true
        if (!stopDeadline.expired(SystemClock.elapsedRealtime())) return false
        // Close the complete session, including traversal, lookahead and the player.
        onExit()
        Toast.makeText(activity, "定时时间已到，轮播已停止", Toast.LENGTH_LONG).show()
        return true
    }
    fun foreground() { if (!stopIfExpired()) armStopTimer() }
    private fun stopDialog() {
        val remaining = stopDeadline.remaining(SystemClock.elapsedRealtime())
        val input = TvUi.input(activity, "从现在起停止轮播（0–1440 分钟）", if (remaining == null) "0" else ((remaining + 59_999) / 60_000).toString(), "分钟").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val dialog = AlertDialog.Builder(activity).setTitle("定时停止轮播")
            .setMessage("0 为关闭。暂停和加载也计时；后台不唤醒，返回时检查是否到时。不关闭设备。")
            .setView(input).setPositiveButton("应用", null).setNegativeButton("取消", null).create()
        presentDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val minutes = input.text.toString().toIntOrNull()
            if (minutes == null || minutes !in 0..1440) { input.error = "请输入 0–1440 分钟"; return@setOnClickListener }
            stopDeadline.set(minutes * 60_000L, SystemClock.elapsedRealtime()); armStopTimer(); dialog.dismiss()
        }
    }
    private fun information() {
        val entry = current ?: pendingEntry
        val snapshot = buildList {
            add("文件：${entry?.name ?: "尚未打开资源"}")
            entry?.size?.takeIf { it >= 0 }?.let { add("文件大小：${DisplayText.bytes(it)}") }
            bitmap?.let { b ->
                add("显示位图：${b.width} × ${b.height}（按预算采样）")
                add("位图内存：${DisplayText.bytes(HdrSupport.bitmapBytes(b))}")
                add("当前位图：${if (android.os.Build.VERSION.SDK_INT >= 34 && b.hasGainmap()) "Ultra HDR / gainmap" else "SDR"}")
            }
            if (entry?.kind in listOf(MediaKind.VIDEO, MediaKind.AUDIO)) engine?.information()?.takeIf { it.isNotEmpty() }?.let(::add)
            val types = HdrSupport.displayTypes(activity)
            add("系统报告 HDR 显示能力：${HdrSupport.Range.entries.filter { it != HdrSupport.Range.SDR && HdrSupport.supports(it, types) }.joinToString { it.label }.ifEmpty { "未报告" }}")
            stopDeadline.remaining(SystemClock.elapsedRealtime())?.let { add("轮播将在约 ${(it + 59_999) / 60_000} 分钟后停止") }
            add("\n打开时快照，不后台采集；轨道信息不等于面板实际输出效果。")
        }.joinToString("\n")
        val text = TvUi.text(activity, snapshot, 16f).apply { setPadding(dp(20), dp(12), dp(20), dp(12)) }
        presentDialog(AlertDialog.Builder(activity).setTitle("播放信息").setView(ScrollView(activity).apply { addView(text) }).setPositiveButton("关闭", null).create())
    }
    private fun presentDialog(dialog: AlertDialog) {
        activeDialog?.dismiss(); activeDialog = dialog; hideJob?.cancel()
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null; if (!disposed && !suspended) showControls() }
        dialog.show()
    }
    private fun launchPlay(entry: Entry, resolved: MediaSource? = null) {
        autoSkip?.cancel()
        nextPrepared.cancel()
        loadJob?.cancel()
        loadJob = scope.launch { play(entry, resolved) }
    }
    private suspend fun play(entry: Entry, resolved: MediaSource? = null, prepared: PreparedSlide? = null) {
        val holdImage = options != null && bitmap != null
        stopCurrent(holdImage)
        if ((pendingEntry ?: current)?.path != entry.path) expiredRetries = 0
        pendingEntry = entry; ready = false; paused = false; suspended = false
        if (!holdImage) {
            title.text = entry.name; center.text = "正在读取资源…"; center.visibility = VISIBLE
            status.text = source?.description ?: "单项点播"; progress.text = ""
        } else center.visibility = GONE
        pause.text = "暂停"
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        var unshown: Bitmap? = null
        try {
            val media = prepared?.media ?: resolved ?: repository.resolve(entry)
            when (entry.kind) {
                MediaKind.IMAGE -> {
                    playerView.visibility = GONE; imageView.visibility = VISIBLE
                    val display = resources.displayMetrics
                    unshown = prepared?.takeImage() ?: images.load(media, display.widthPixels, display.heightPixels, slideshow = options != null)
                    currentCoroutineContext().ensureActive()
                    if (disposed || suspended) return
                    // One UI-thread assignment: never clear the old drawable before the new
                    // image is decoded. Displayed bitmaps are GC-managed on our API 23+ baseline;
                    // HWUI may still reference the old frame, so do not recycle it underneath it.
                    hdrWindow.display(checkNotNull(unshown))
                    imageView.setImageBitmap(unshown); bitmap = unshown; unshown = null
                    imageView.contentDescription = "图片 ${entry.name}"
                    current = entry; pendingEntry = null; currentSource = media; imageElapsed = 0
                    title.text = entry.name; progress.text = ""; center.visibility = GONE; ready()
                }
                MediaKind.VIDEO, MediaKind.AUDIO -> {
                    clearImage()
                    current = entry; pendingEntry = null; currentSource = media; imageElapsed = 0
                    title.text = entry.name; progress.text = ""; center.text = "正在读取资源…"; center.visibility = VISIBLE
                    imageView.visibility = GONE; playerView.visibility = if (entry.kind == MediaKind.VIDEO) VISIBLE else GONE
                    if (engine == null) engine = MediaEngine(activity, http, this).also { playerView.player = it.player }
                    val stored = if (options == null) withContext(Dispatchers.IO) { catalog.position(repository.session.site.id, entry) } else 0L
                    val position = if (resumePosition > 0) resumePosition else stored
                    resumePosition = 0
                    engine!!.play(media, position)
                }
                else -> throw ClientFailure(FailureKind.UNSUPPORTED, "不支持此文件类型")
            }
            startTicker()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if ((e as? ClientFailure)?.kind == FailureKind.STALE_LINK && expiredRetries++ == 0) play(entry)
            else handleFailure(MainActivity.message(e), (e as? ClientFailure)?.kind)
        } finally { unshown?.recycle(); prepared?.close() }
    }
    override fun ready() {
        if (disposed || suspended) return
        val first = !ready
        ready = true; failures = 0
        center.visibility = if (current?.kind == MediaKind.AUDIO) VISIBLE else GONE
        if (current?.kind == MediaKind.AUDIO) center.text = "♪\n${current?.name}"
        if (first) {
            status.text = (source?.description ?: "单项点播") + if (skipped > 0) " · 已跳过 $skipped 项" else ""
            prepareNext()
            // Automatic media changes must not steal focus or keep reopening the overlay.
            if (controls.visibility == VISIBLE && hideJob?.isActive != true) hideJob = scope.launch { delay(5000); if (!paused && ready) hideControls() }
        }
    }
    override fun ended() {
        if (options != null) advance(1)
        else { ready = false; current?.let { activity.lifecycleScope.launch(Dispatchers.IO) { catalog.savePosition(repository.session.site.id, it, 0) } }; status.text = "播放结束"; showControls() }
    }
    override fun qualityChanged(message: String) { status.text = message; showControls() }
    override fun failed(message: String, sourceExpired: Boolean, kind: FailureKind) {
        if (disposed || suspended) return
        if (sourceExpired && expiredRetries++ == 0) {
            resumePosition = engine?.player?.currentPosition ?: 0
            current?.let { launchPlay(it) }; return
        }
        handleFailure(message, kind)
    }
    private fun prepareNext() {
        val settings = options ?: return
        if (nextPrepared.occupied || paused || disposed || suspended) return
        val imageSlideshow = current?.kind == MediaKind.IMAGE
        if (!imageSlideshow && !settings.prefetch) return
        nextPrepared.start {
            // Peeking history must not advance its cursor or throw away a reserved random pick.
            val entry = history.peekForward() ?: reservedNext ?: source?.next()?.also { reservedNext = it } ?: return@start null
            // The legacy preference remains an AV-address switch. Image-to-image
            // lookahead is automatic, including sites upgraded with that switch off.
            if (entry.kind != MediaKind.IMAGE && !settings.prefetch) return@start PreparedSlide(entry, null)
            var prepared = PreparedSlide(entry, repository.resolve(entry))
            try {
                if (entry.kind == MediaKind.IMAGE && current?.kind == MediaKind.IMAGE && !images.lowMemory) {
                    val display = resources.displayMetrics
                    try { prepared.image = images.load(checkNotNull(prepared.media), display.widthPixels, display.heightPixels, slideshow = true) }
                    catch (e: ClientFailure) {
                        if (e.kind != FailureKind.STALE_LINK) throw e
                        prepared.close(); prepared = PreparedSlide(entry, repository.resolve(entry))
                        prepared.image = images.load(checkNotNull(prepared.media), display.widthPixels, display.heightPixels, slideshow = true)
                    }
                }
                currentCoroutineContext().ensureActive()
                prepared
            } catch (e: Throwable) { prepared.close(); throw e }
        }
    }
    private fun advance(direction: Int) {
        if (disposed || suspended) return
        autoSkip?.cancel()
        loadJob?.cancel()
        loadJob = scope.launch {
            savePosition()
            val holdImage = options != null && bitmap != null
            stopCurrent(holdImage)
            if (holdImage) center.visibility = GONE else { center.visibility = VISIBLE; center.text = "准备下一项…" }
            var prepared: PreparedSlide? = null
            try {
                var preparationFailure: Exception? = null
                val entry = if (options == null) onManualNext(direction) else if (direction < 0) {
                    nextPrepared.cancel(); history.previous()
                } else {
                    nextPrepared.await()
                    try { prepared = nextPrepared.take() } catch (e: Exception) { preparationFailure = e }
                    history.forward() ?: run {
                        val next = reservedNext ?: if (preparationFailure == null) source?.next() else null
                        reservedNext = null; next?.also(history::add)
                    }
                }
                pendingEntry = entry
                preparationFailure?.let { throw it }
                if (entry == null) {
                    paused = true; ready = bitmap != null; pause.text = "继续"
                    center.text = if (current == null) "没有找到可播放资源" else "没有更多资源"
                    status.text = "没有更多资源，可返回文件浏览选择其他目录"; showControls()
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); return@launch
                }
                if (prepared?.entry != entry) { prepared?.close(); prepared = null }
                play(entry, prepared = prepared)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { handleFailure(MainActivity.message(e), (e as? ClientFailure)?.kind) }
            finally { prepared?.close() }
        }
    }
    private fun handleFailure(message: String, kind: FailureKind?) {
        if (disposed) return
        val holdImage = options != null && bitmap != null
        stopCurrent(holdImage); ready = false; failures++; skipped++
        val global = PlaybackFailures.requiresPause(kind)
        status.text = message
        if (options != null && !global && failures < Budgets.CONSECUTIVE_FAILURES) {
            if (holdImage) center.visibility = GONE
            else { center.visibility = VISIBLE; center.text = message; showControls() }
            autoSkip = scope.launch { delay(800); if (!disposed && !suspended && !paused) advance(1) }
        } else {
            center.visibility = VISIBLE; center.text = message; showControls()
            paused = true; pause.text = "继续"; nextPrepared.cancel(); reservedNext = null; source?.close(); source = null
            if (failures >= Budgets.CONSECUTIVE_FAILURES) status.text = "连续失败已达 10 项，自动暂停。请检查资源或换目录。"
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            var previous = SystemClock.elapsedRealtime(); var lastSaved = previous
            while (isActive) {
                delay(250)
                val now = SystemClock.elapsedRealtime()
                if (ready && !paused) {
                    val entry = current ?: break
                    if (entry.kind == MediaKind.IMAGE) {
                        imageElapsed += now - previous
                        val label = if (options != null) "${imageElapsed / 1000} / ${options.imageSeconds} 秒" else "图片 · 左右切换，返回退出"
                        if (progress.text.toString() != label) progress.text = label
                        if (options != null && imageElapsed >= options.imageSeconds * 1000L) { advance(1); break }
                    } else {
                        val p = engine?.player
                        val position = p?.currentPosition ?: 0
                        val duration = (p?.duration ?: 0).coerceAtLeast(0)
                        val label = "${time(position)} / ${if (duration > 0) time(duration) else "--:--"}"
                        if (progress.text.toString() != label) progress.text = label
                        val limit = if (entry.kind == MediaKind.VIDEO) options?.videoSeconds else options?.audioSeconds
                        if (p?.isPlaying == true && limit != null && limit > 0 && position >= limit * 1000L) { advance(1); break }
                        if (options == null && now - lastSaved >= 5000) { savePosition(); lastSaved = now }
                    }
                }
                previous = now
            }
        }
    }
    private fun togglePause() {
        if (stopIfExpired()) return
        if (!suspended && !ready && loadJob?.isActive == true) {
            if (options != null && bitmap != null) {
                loadJob?.cancel(); ticker?.cancel(); nextPrepared.cancel(); paused = true; ready = true
                pause.text = "继续"; activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); showControls()
            } else {
                // A user pause during initial loading releases IO but remains a
                // foreground session: its stop deadline must still be armed.
                background(); armStopTimer()
            }
            return
        }
        if (paused && pendingEntry != null && source != null) { launchPlay(pendingEntry!!); return }
        if (suspended || source == null && options != null) {
            suspended = false; source = createSource()
            (pendingEntry ?: current)?.let { launchPlay(it) } ?: advance(1); return
        }
        paused = !paused; pause.text = if (paused) "继续" else "暂停"
        engine?.player?.playWhenReady = !paused
        if (paused) { autoSkip?.cancel(); activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); nextPrepared.cancel() }
        else { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); prepareNext(); if (ticker?.isActive != true) startTicker() }
        showControls()
    }
    private fun tracks() {
        val player = engine?.player ?: return
        data class Choice(val label: String, val apply: () -> Unit)
        val choices = mutableListOf(Choice("关闭字幕") { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() })
        choices += Choice("选择同目录外挂字幕（SRT / VTT）") { externalSubtitle() }
        player.currentTracks.groups.filter { it.type in listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT) }.forEach { group ->
            repeat(group.length) { i ->
                if (group.isTrackSupported(i)) {
                    val format = group.getTrackFormat(i)
                    choices += Choice("${if (group.type == C.TRACK_TYPE_AUDIO) "音轨" else "字幕"} ${i + 1} · ${format.label ?: format.language ?: format.sampleMimeType.orEmpty()}") {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(group.type, false).clearOverridesOfType(group.type).addOverride(TrackSelectionOverride(group.mediaTrackGroup, listOf(i))).build()
                    }
                }
            }
        }
        hideJob?.cancel()
        presentDialog(AlertDialog.Builder(activity).setTitle("音轨 / 内嵌字幕").setItems(choices.map { it.label }.toTypedArray()) { _, i -> choices[i].apply() }.setNegativeButton("取消", null).create())
    }
    private fun externalSubtitle() {
        val entry = current ?: return
        scope.launch {
            try {
                val candidates = repository.subtitles(entry)
                if (candidates.isEmpty()) { Toast.makeText(activity, "同目录没有 SRT / VTT 字幕", Toast.LENGTH_LONG).show(); return@launch }
                presentDialog(AlertDialog.Builder(activity).setTitle("选择外挂字幕").setItems(candidates.map { it.name }.toTypedArray()) { _, index ->
                    scope.launch {
                        try {
                            val subtitle = repository.resolve(candidates[index])
                            if (current?.path != entry.path) return@launch
                            val media = currentSource ?: return@launch
                            val updated = media.copy(subtitles = listOf(Subtitle(candidates[index].name, subtitle.url, if (candidates[index].name.endsWith(".vtt", true)) "text/vtt" else "application/x-subrip")))
                            currentSource = updated
                            engine?.let { it.play(updated, it.player.currentPosition); it.player.playWhenReady = !paused }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { status.text = MainActivity.message(e); showControls() }
                    }
                }.setNegativeButton("取消", null).create())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.text = MainActivity.message(e); showControls() }
        }
    }
    private suspend fun savePosition() {
        val e = current ?: return
        if (options != null || e.kind == MediaKind.IMAGE || !ready) return
        val p = engine?.player ?: return
        val position = if (p.playbackState == androidx.media3.common.Player.STATE_ENDED) 0 else p.currentPosition
        withContext(Dispatchers.IO) { catalog.savePosition(repository.session.site.id, e, position) }
    }
    private fun showControls() {
        if (disposed) return
        controls.visibility = VISIBLE; pause.requestFocus(); hideJob?.cancel()
        hideJob = scope.launch { delay(5000); if (!paused && ready) hideControls() }
    }
    private fun hideControls() { if (activeDialog == null) { controls.visibility = GONE; requestFocus() } }
    fun back(): Boolean { if (controls.visibility == VISIBLE) { hideControls(); return true }; return false }
    fun handleKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePause(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (paused || suspended) togglePause(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (!paused && !suspended) togglePause(); return true }
        }
        if (controls.visibility != VISIBLE) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { togglePause(); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { showControls(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val direction = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                    if (options != null || current?.kind == MediaKind.IMAGE) { if (event.repeatCount == 0) advance(direction) }
                    else engine?.player?.let { if (it.isCurrentMediaItemSeekable) it.seekTo((it.currentPosition + direction * 10_000L).coerceAtLeast(0)) }
                    return true
                }
            }
        }
        if (event.keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER) { hideJob?.cancel(); hideJob = scope.launch { delay(5000); if (!paused && ready) hideControls() } }
        return false
    }
    private fun clearImage() { imageView.setImageDrawable(null); imageView.contentDescription = null; bitmap = null }
    private fun stopCurrent(keepImage: Boolean = false) { ticker?.cancel(); engine?.stop(); if (!keepImage) clearImage(); ready = false }
    fun background() {
        if (disposed) return
        persistPosition()
        resumePosition = engine?.player?.currentPosition ?: 0
        suspended = true; paused = true
        activeDialog?.dismiss(); activeDialog = null
        nextPrepared.cancel(); scope.coroutineContext.cancelChildren(); source?.close(); source = null; reservedNext = null
        stopCurrent(); playerView.player = null; engine?.release(); engine = null
        hdrWindow.restore()
        center.visibility = VISIBLE; center.text = "已暂停 · 按确认继续当前资源"; pause.text = "继续"; controls.visibility = VISIBLE
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    fun trimMemory() { images.lowMemory = true; nextPrepared.cancel(); hdrWindow.restore(); engine?.trimMemory() }
    fun dispose() {
        if (disposed) return
        persistPosition()
        disposed = true; activeDialog?.dismiss(); activeDialog = null; nextPrepared.cancel(); source?.close(); scope.cancel(); stopCurrent(); playerView.player = null; engine?.release(); engine = null; hdrWindow.restore()
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun persistPosition() {
        val entry = current ?: return
        val player = engine?.player ?: return
        if (options != null || !ready) return
        val position = if (player.playbackState == androidx.media3.common.Player.STATE_ENDED) 0 else player.currentPosition
        activity.lifecycleScope.launch(Dispatchers.IO) { catalog.savePosition(repository.session.site.id, entry, position) }
    }
    private fun time(ms: Long): String { val seconds = ms / 1000; return "%d:%02d".format(seconds / 60, seconds % 60) }
}

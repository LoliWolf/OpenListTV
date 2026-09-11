package io.github.loliwolf.openlisttv

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.text.InputFilter
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.loliwolf.openlisttv.core.*
import io.github.loliwolf.openlisttv.data.*
import io.github.loliwolf.openlisttv.TvUi.dp
import kotlinx.coroutines.*
import java.util.UUID

@UnstableApi
class MainActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var sites: SiteStore
    private lateinit var catalog: Catalog
    private val api = OpenListApi()
    private val updates by lazy { UpdateController(this,
        canPromptAutomatically = { panel == null && slideshowSettings == null && root.hasWindowFocus() && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) },
        beforeDownload = { downloads.pauseAndJoin("正在下载应用更新，资源传输已暂停"); browserList?.pauseLoading() },
        afterDownload = { if (browserVisible && panel == null && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) browserList?.resumeLoading() }) }
    private var repository: FileRepository? = null
    private var currentPath = "/"
    private var offset = 0
    private var sort = SortOrder()
    private var work: Job? = null
    private var generation = 0
    private var panel: PlaybackPanel? = null
    private var browserVisible = false
    private var browserTotal = 0
    private var browserList: BrowserList? = null
    private var browserContainer: LinearLayout? = null
    private var slideshowSettings: AlertDialog? = null
    private val downloads get() = (application as TvApplication).transfers
    private var downloadsVisible = false
    private var downloadList: DownloadsPanel? = null
    private var selectedEntry: Entry? = null
    private var exportId: String? = null
    private val exportDestination = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = exportId; exportId = null
        if (uri == null && id != null) toast("未选择保存位置；部分电视系统未提供文件选择器，仍可在本机离线打开")
        if (uri != null && id != null) lifecycleScope.launch {
            val record = withContext(Dispatchers.IO) { downloads.store.get(id) }
            if (record != null && panel == null && !downloads.export(record, uri)) toast("已有传输正在进行，请先暂停")
        }
    }
    private val queries = object : LinkedHashMap<String, BrowserQuery>(16, .75f, true) { override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BrowserQuery>?) = size > 100 }
    private val browserQuery get() = queries[currentPath] ?: BrowserQuery()
    private val focusMemory = object : LinkedHashMap<String, String>(16, .75f, true) { override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 100 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvUi.configure(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        root = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean = handleRemoteKey(event) || super.dispatchKeyEvent(event)
        }.apply { background = TvUi.screenBackground(); isFocusable = true; isFocusableInTouchMode = true }
        setContentView(root)
        sites = SiteStore(this); catalog = (application as TvApplication).catalog
        exportId = savedInstanceState?.getString("download.exportId")
        updates.restore(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                panel?.let { if (!it.back()) closePlayer(); return }
                if (downloadsVisible) { if (repository != null) showBrowser() else showLogin(); return }
                if (browserVisible && currentPath != "/") { currentPath = Paths.parent(currentPath); offset = 0; showBrowser() }
                else if (!browserVisible && repository != null) showBrowser()
                else finish()
            }
        })
        val site = sites.active()
        if (site == null) showLogin() else restore(site)
    }
    private fun page(title: String, subtitle: String): LinearLayout {
        generation++; work?.cancel(); browserVisible = false
        downloadsVisible = false; downloadList?.dispose(); downloadList = null
        browserList?.dispose(); browserList = null; browserContainer = null
        root.removeAllViews()
        return TvUi.column(this).apply {
            setPadding(dp(32), dp(20), dp(32), dp(16))
            addView(TvUi.text(this@MainActivity, "OPENLIST TV  /  资源库", 11f, TvUi.accent).apply { letterSpacing = .12f })
            addView(TvUi.spacer(this@MainActivity, 5))
            addView(TvUi.title(this@MainActivity, title).also(TvUi::ellipsis))
            addView(TvUi.text(this@MainActivity, subtitle, 14f, TvUi.muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            addView(TvUi.spacer(this@MainActivity, 16))
            root.addView(this, FrameLayout.LayoutParams(-1, -1))
        }
    }
    private fun restore(site: Site) {
        val content = page("OpenList TV", "正在验证保存的会话…")
        content.addView(TvUi.button(this, "重新登录") { showLogin(site) })
        content.addView(TvUi.button(this, "本机下载") { showDownloads() })
        work = lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { sites.session(site) }
            if (saved == null) { showLogin(site); return@launch }
            try { activate(api.validate(saved)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if ((e as? ClientFailure)?.kind in listOf(FailureKind.AUTH, FailureKind.CHALLENGE)) showLogin(site)
                else {
                    val error = page("连接暂不可用", "${site.label} · 站点配置和登录信息仍然保留")
                    error.addView(TvUi.text(this@MainActivity, message(e), 18f, TvUi.muted))
                    error.addView(TvUi.spacer(this@MainActivity, 18))
                    error.addView(TvUi.button(this@MainActivity, "重试连接", primary = true) { restore(site) }.apply { requestFocus() })
                    error.addView(TvUi.spacer(this@MainActivity, 10))
                    error.addView(TvUi.button(this@MainActivity, "编辑站点") { showLogin(site) })
                    error.addView(TvUi.spacer(this@MainActivity, 10))
                    error.addView(TvUi.button(this@MainActivity, "已保存站点") { showSites() })
                    error.addView(TvUi.spacer(this@MainActivity, 10))
                    error.addView(updateButton())
                    error.addView(TvUi.button(this@MainActivity, "本机下载") { showDownloads() })
                }
            }
        }
    }
    private suspend fun activate(session: Session) {
        downloads.pauseAndJoin("切换站点，下载已暂停")
        val oldSite = repository?.session?.site?.id
        withContext(Dispatchers.IO) {
            if (oldSite != null && oldSite != session.site.id) catalog.clear(oldSite)
            val previous = sites.sites().firstOrNull { it.id == session.site.id }
            val previousSession = sites.session(session.site)
            val changedIdentity = previous != null && (previous.username != session.site.username || previous.url != session.site.url ||
                previousSession != null && previousSession.basePath != session.basePath)
            sites.save(session)
            // Preserve progress on upgrades/reconnects, but never carry it to another
            // account or server when an existing site's login fields are replaced.
            // Refresh browser metadata after validation, but keep a same-identity slideshow
            // snapshot. Its own root/filter/TTL checks still run before any cache reuse.
            catalog.clear(session.site.id, clearHistory = changedIdentity, preserveScanSnapshot = !changedIdentity)
        }
        repository = FileRepository(session, api, catalog)
        sort = sites.sort(session.site); currentPath = "/"; offset = 0; focusMemory.clear(); queries.clear()
        showBrowser()
    }
    private fun showLogin(existing: Site? = null) {
        downloads.pause("登录页面，下载已暂停")
        val content = page("OpenList TV", "轻量资源浏览 · 为电视与投影遥控而设计")
        val scroll = ScrollView(this)
        val form = TvUi.column(this)
        scroll.addView(form); content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val label = TvUi.input(this, "站点名称", existing?.label.orEmpty())
        val address = TvUi.input(this, "服务器地址（含 https://）", existing?.url.orEmpty()).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        val username = TvUi.input(this, "账号", existing?.username.orEmpty())
        val password = TvUi.input(this, "密码").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; isSaveEnabled = false }
        val http = TvUi.check(this, "允许此站点使用 HTTP（局域网明文传输）", existing?.allowHttp ?: false)
        val status = TvUi.text(this, "一期使用账号密码登录；不会保存原始密码。", 14f, TvUi.muted)
        listOf(label, address, username, password, http, status, TvUi.spacer(this, 10)).forEach(form::addView)
        val row = ActionFlow(this)
        lateinit var connect: Button
        connect = TvUi.button(this, "连接站点", primary = true) {
            if (work?.isActive == true) return@button
            val secret = password.text.toString()
            val account = username.text.toString().trim()
            if (account.isEmpty() || secret.isEmpty()) { status.text = "请填写账号和密码"; return@button }
            val url = try { ServerAddress.normalize(address.text.toString(), http.isChecked) } catch (e: ClientFailure) { status.text = e.message; return@button }
            val site = Site(existing?.id ?: UUID.randomUUID().toString(), label.text.toString().trim().ifEmpty { "我的 OpenList" }, url, account, http.isChecked)
            connect.isEnabled = false; status.text = "正在登录…"
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(password.windowToken, 0)
            work = lifecycleScope.launch {
                try { val session = api.login(site, secret); password.text.clear(); activate(session) }
                catch (e: CancellationException) { status.text = "登录已取消"; throw e }
                catch (e: Exception) { status.text = message(e) }
                finally { connect.isEnabled = true }
            }
        }
        row.addView(connect)
        TvUi.verticalFocus(label, address, username, password, http, connect)
        if (sites.sites().isNotEmpty()) row.addView(TvUi.button(this, "已保存站点") { showSites() })
        row.addView(updateButton())
        row.addView(TvUi.button(this, "本机下载") { showDownloads() })
        form.addView(row)
        address.requestFocus()
    }
    private fun showBrowser(force: Boolean = false) {
        val repo = repository ?: return showLogin()
        val content = page(repo.session.site.label, "${repo.session.site.username} · ${currentPath}")
        browserVisible = true
        selectedEntry = null
        val viewQuery = browserQuery
        val toolbar = ActionFlow(this)
        toolbar.addView(TvUi.button(this, "上一级") { if (currentPath != "/") { currentPath = Paths.parent(currentPath); offset = 0 }; showBrowser() })
        toolbar.addView(TvUi.button(this, "根目录") { currentPath = "/"; offset = 0; showBrowser() })
        toolbar.addView(TvUi.button(this, "排序") { sortingDialog() })
        toolbar.addView(TvUi.button(this, "搜索 / 筛选") { searchDialog() })
        toolbar.addView(TvUi.button(this, "开始轮播", primary = true) { slideshowDialog() })
        toolbar.addView(TvUi.button(this, "重新加载") { showBrowser(true) })
        toolbar.addView(TvUi.button(this, "站点") { showSites() })
        toolbar.addView(TvUi.button(this, "设置") { showSettings() })
        toolbar.addView(TvUi.button(this, "下载") { downloadMenu() })
        content.addView(toolbar); content.addView(TvUi.spacer(this, 10))
        val status = TvUi.text(this, "准备目录…", 14f, TvUi.muted).apply { maxLines = 2 }
        content.addView(status)
        val listHost = FrameLayout(this)
        content.addView(listHost, LinearLayout.LayoutParams(-1, 0, 1f))
        val currentGeneration = generation
        var loading = true
        val path = currentPath
        val pageOffset = offset
        work = lifecycleScope.launch {
            try {
                val result = if (!viewQuery.active && sort.field == SortField.SERVER && !sort.foldersFirst) repo.remotePage(path, pageOffset) else {
                    repo.ensure(path, force) { read, total -> status.post { if (generation == currentGeneration && loading) status.text = "整理完整目录 $read / $total · ${sort.field.label}" } }
                    repo.page(path, pageOffset, sort, query = viewQuery)
                }
                loading = false
                if (generation != currentGeneration) return@launch
                if (result.total > 0 && pageOffset >= result.total) { offset = 0; showBrowser(); return@launch }
                browserTotal = result.total
                status.text = (if (result.total == 0) if (viewQuery.active) "没有匹配项" else "目录为空" else "${result.total} 项 · ${sort.field.label}${if (sort.descending) "降序" else "升序"} · 图片自动预览") + if (viewQuery.active) "\n当前目录 · ${viewQuery.description}" else ""
                if (result.total > 0) {
                    val browser = BrowserList(this@MainActivity, repo, path, sort, result, pageOffset, lifecycleScope,
                        opened = { e, index ->
                            focusMemory[path] = e.path; selectedEntry = e
                            if (e.directory) { currentPath = e.path; offset = 0; showBrowser() }
                            else if (e.kind != MediaKind.OTHER) openPlayer(e, index, null)
                            else fileActions(e)
                        }, focused = { focusMemory[path] = it.path; selectedEntry = it }, pageChanged = { offset = it }, problem = { status.text = message(it) }, query = viewQuery, actions = { fileActions(it) })
                    browserList = browser; browserContainer = content
                    listHost.addView(browser, FrameLayout.LayoutParams(-1, -1)); browser.restoreFocus(focusMemory[path])
                } else {
                    val empty = TvUi.column(this@MainActivity).apply { gravity = Gravity.CENTER }
                    empty.addView(TvUi.title(this@MainActivity, if (viewQuery.active) "没有匹配的文件" else "这里还没有文件"))
                    empty.addView(TvUi.text(this@MainActivity, if (viewQuery.active) "只搜索当前目录，不包含子目录。" else "可以返回上一级，或重新加载目录。", 16f, TvUi.muted))
                    if (viewQuery.active) empty.addView(TvUi.button(this@MainActivity, "清除搜索与筛选") { queries.remove(currentPath); offset = 0; showBrowser() })
                    listHost.addView(empty, FrameLayout.LayoutParams(-1, -1)); toolbar.getChildAt(0).requestFocus()
                }
                updates.foreground()
            } catch (e: CancellationException) { loading = false; if (generation == currentGeneration) status.text = "已暂停加载，按重新加载继续"; throw e }
            catch (e: Exception) {
                loading = false
                if (generation != currentGeneration) return@launch
                status.text = message(e)
                val actions = TvUi.column(this@MainActivity).apply { gravity = Gravity.CENTER }
                actions.addView(TvUi.text(this@MainActivity, message(e), 18f, TvUi.muted)); actions.addView(TvUi.spacer(this@MainActivity, 18))
                when ((e as? ClientFailure)?.kind) {
                    FailureKind.ACCESS -> actions.addView(TvUi.button(this@MainActivity, "输入目录密码", primary = true) { directoryPassword(path) })
                    FailureKind.AUTH -> actions.addView(TvUi.button(this@MainActivity, "重新登录", primary = true) { showLogin(repo.session.site) })
                    else -> actions.addView(TvUi.button(this@MainActivity, "重试", primary = true) { showBrowser(true) })
                }
                listHost.removeAllViews(); listHost.addView(actions, FrameLayout.LayoutParams(-1, -1)); actions.getChildAt(actions.childCount - 1)?.requestFocus()
            }
        }
    }
    private fun searchDialog() {
        val current = browserQuery
        val form = TvUi.column(this).apply { setPadding(dp(20), 0, dp(20), 0) }
        val input = TvUi.input(this, "当前目录文件名搜索", current.text).apply { filters = arrayOf(InputFilter.LengthFilter(128)); isSaveEnabled = false }
        val types = RadioGroup(this)
        val ids = BrowserType.entries.map { View.generateViewId() }
        BrowserType.entries.forEachIndexed { i, type -> types.addView(RadioButton(this).apply { id = ids[i]; text = type.label; textSize = 17f; isChecked = current.type == type }) }
        form.addView(input); form.addView(types)
        form.addView(TvUi.text(this, "搜索和类型可组合；仅当前目录，不扫描子目录。筛选不改变轮播范围。", 13f, TvUi.muted))
        AlertDialog.Builder(this).setTitle("当前目录搜索 / 类型筛选").setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("应用") { _, _ ->
                queries[currentPath] = BrowserQuery(input.text.toString().trim(), BrowserType.entries[ids.indexOf(types.checkedRadioButtonId).coerceAtLeast(0)])
                offset = 0; focusMemory.remove(currentPath); showBrowser()
            }.setNeutralButton("清除") { _, _ -> queries.remove(currentPath); offset = 0; focusMemory.remove(currentPath); showBrowser() }
            .setNegativeButton("取消", null).show()
    }
    private fun directoryPassword(path: String) {
        val input = TvUi.input(this, "目录密码").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; isSaveEnabled = false }
        AlertDialog.Builder(this).setTitle("目录密码").setMessage("密码仅在当前会话内记忆。").setView(input)
            .setPositiveButton("确定") { _, _ -> repository?.remember(path, input.text.toString()); input.text.clear(); showBrowser(true) }.setNegativeButton("取消", null).show()
    }
    private fun sortingDialog() {
        val fields = SortField.entries
        val form = TvUi.column(this).apply { setPadding(dp(20), 0, dp(20), 0) }
        val group = RadioGroup(this)
        val fieldIds = fields.map { View.generateViewId() }
        fields.forEachIndexed { i, field -> group.addView(RadioButton(this).apply { id = fieldIds[i]; text = field.label; textSize = 18f; isChecked = field == sort.field }) }
        val descending = TvUi.check(this, "降序", sort.descending)
        val folders = TvUi.check(this, "文件夹置顶", sort.foldersFirst)
        form.addView(group); form.addView(descending); form.addView(folders)
        AlertDialog.Builder(this).setTitle("完整目录排序").setView(form).setPositiveButton("应用") { _, _ ->
            sort = SortOrder(fields[fieldIds.indexOf(group.checkedRadioButtonId).coerceAtLeast(0)], descending.isChecked, folders.isChecked)
            repository?.let { sites.setSort(it.session.site, sort) }; offset = 0; showBrowser()
        }.setNegativeButton("取消", null).show()
    }
    private fun slideshowDialog() {
        val options = sites.slideshow()
        val form = TvUi.column(this).apply { setPadding(dp(20), 0, dp(20), 0) }
        val modes = RadioGroup(this)
        val modeIds = TraversalMode.entries.map { View.generateViewId() }
        TraversalMode.entries.forEachIndexed { i, mode -> modes.addView(RadioButton(this).apply { id = modeIds[i]; text = mode.label; textSize = 17f; isChecked = mode == options.mode }) }
        val recursive = TvUi.check(this, "包含子目录", options.recursive)
        val imageOnly = TvUi.check(this, "仅图片", options.imageOnly)
        val reuseIndex = TvUi.check(this, "复用轮播索引（6 小时）", options.reuseIndex)
        val forceRescan = TvUi.check(this, "本次重新扫描（忽略旧快照）", false)
        val imageSeconds = TvUi.input(this, "图片停留秒数（1–3600）", options.imageSeconds.toString(), "秒").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val videoSeconds = TvUi.input(this, "视频秒数（0 表示播放到结束）", options.videoSeconds.toString(), "秒").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val audioSeconds = TvUi.input(this, "音频秒数（0 表示播放到结束）", options.audioSeconds.toString(), "秒").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val stopMinutes = TvUi.input(this, "定时停止轮播（0 表示关闭）", options.stopAfterMinutes.toString(), "分钟").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val prefetch = TvUi.check(this, "预取音视频地址（图片始终预加载一张）", options.prefetch)
        form.addView(TvUi.text(this, "播放方式", 13f, TvUi.accent)); form.addView(modes)
        form.addView(recursive); form.addView(imageOnly); form.addView(TvUi.spacer(this, 12))
        form.addView(TvUi.text(this, "公平抽样：最多 1 万候选，继续扫描整个可访问范围；可能重复，不保证每张都播到。扫描完成前仅针对已读部分抽样。", 13f, TvUi.muted))
        form.addView(reuseIndex); form.addView(forceRescan)
        form.addView(TvUi.text(this, "索引复用适用于全树随机和公平抽样。6 小时内免重扫；6–24 小时先播旧快照并在前台重扫，超过 24 小时重建。仅保留最近一个范围；有目录密码时不跨会话复用。", 13f, TvUi.muted))
        form.addView(TvUi.text(this, "播放时长", 13f, TvUi.accent)); form.addView(TvUi.spacer(this, 8))
        listOf(imageSeconds, videoSeconds, audioSeconds).forEach(form::addView)
        form.addView(TvUi.text(this, "图片：到时切换。视频 / 音频：填 0 播放到结束，填其他数字则最多播放该时长。", 14f, TvUi.muted))
        form.addView(stopMinutes)
        form.addView(TvUi.text(this, "定时范围 0–1440 分钟，暂停也计时；到时退出轮播，不关机。后台不唤醒，返回时检查。", 13f, TvUi.muted))
        form.addView(prefetch)
        form.addView(TvUi.text(this, "只提前准备一张图片；慢网时保留当前画面，准备好再切换。", 13f, TvUi.muted))
        val scroll = ScrollView(this).apply { addView(form) }
        val dialog = AlertDialog.Builder(this).setTitle("从 $currentPath 开始轮播").setView(scroll).setPositiveButton("开始", null).setNegativeButton("取消", null).create()
        slideshowSettings = dialog
        dialog.setOnDismissListener { if (slideshowSettings === dialog) slideshowSettings = null }
        dialog.setOnShowListener {
            TvUi.verticalFocus(*(modeIds.map { modes.findViewById<View>(it) } + listOf(recursive, imageOnly, reuseIndex, forceRescan, imageSeconds, videoSeconds, audioSeconds, stopMinutes, prefetch, dialog.getButton(AlertDialog.BUTTON_POSITIVE))).toTypedArray())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val o = runCatching { SlideshowOptions(TraversalMode.entries[modeIds.indexOf(modes.checkedRadioButtonId).coerceAtLeast(0)], recursive.isChecked, imageOnly.isChecked, imageSeconds.text.toString().toInt(), videoSeconds.text.toString().toInt(), audioSeconds.text.toString().toInt(), prefetch.isChecked, stopMinutes.text.toString().toInt(), reuseIndex.isChecked, forceRescan.isChecked) }.getOrNull()
                if (o == null) { Toast.makeText(this, "图片 1–3600 秒，音视频 0–3600 秒，定时停止 0–1440 分钟", Toast.LENGTH_LONG).show(); return@setOnClickListener }
                sites.setSlideshow(o); dialog.dismiss(); openPlayer(null, 0, o)
            }
        }
        dialog.show()
        fitSlideshowDialog()
    }
    private fun fitSlideshowDialog() {
        val inset = if (resources.configuration.screenWidthDp < 600) 24 else 80
        slideshowSettings?.window?.setLayout(minOf(dp(620), resources.displayMetrics.widthPixels - dp(inset)), -2)
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Keep directory, current media and timers alive while native views reflow.
        browserList?.updateCompactLayout(); fitSlideshowDialog()
    }
    private fun openPlayer(entry: Entry?, origin: Int, options: SlideshowOptions?, promptResume: Boolean = true,
        playerRepository: FileRepository? = repository, playerPath: String = currentPath, playerQuery: BrowserQuery = browserQuery) {
        val repo = playerRepository ?: return
        if (updates.downloading) {
            work?.cancel(); work = lifecycleScope.launch { updates.pauseDownload(); openPlayer(entry, origin, options, promptResume, repo, playerPath, playerQuery) }; return
        }
        if (downloads.busy) {
            work?.cancel()
            work = lifecycleScope.launch { downloads.pauseAndJoin("播放优先，下载已暂停；返回本机下载后可手动继续"); openPlayer(entry, origin, options, promptResume, repo, playerPath, playerQuery) }
            return
        }
        if (promptResume && options == null && entry != null && entry.kind in listOf(MediaKind.VIDEO, MediaKind.AUDIO)) {
            work = lifecycleScope.launch {
                val position = withContext(Dispatchers.IO) { catalog.position(repo.session.site.id, entry) }
                if (position < 3000) openPlayer(entry, origin, null, false, repo, playerPath, playerQuery)
                else AlertDialog.Builder(this@MainActivity).setTitle(entry.name).setMessage("上次播放到 ${position / 60000}:${((position / 1000) % 60).toString().padStart(2, '0')}")
                    .setPositiveButton("继续播放") { _, _ -> openPlayer(entry, origin, null, false, repo, playerPath, playerQuery) }
                    .setNeutralButton("从头播放") { _, _ -> work = lifecycleScope.launch { withContext(Dispatchers.IO) { catalog.savePosition(repo.session.site.id, entry, 0) }; openPlayer(entry, origin, null, false, repo, playerPath, playerQuery) } }
                    .setNegativeButton("取消", null).show()
            }
            return
        }
        work?.cancel(); generation++; browserVisible = false; browserList?.pauseLoading(); root.removeAllViews()
        var manualIndex = origin
        val newPanel = PlaybackPanel(this, repo, catalog, playerPath, sort, options, lifecycleScope,
            onExit = { closePlayer() }, onManualNext = { delta ->
                repo.ensure(playerPath)
                var result: Entry? = null
                while (true) {
                    manualIndex += delta
                    if (manualIndex < 0) break
                    val page = repo.page(playerPath, manualIndex, sort, 1, query = playerQuery)
                    val candidate = page.entries.firstOrNull() ?: break
                    if (!candidate.directory && candidate.kind != MediaKind.OTHER) { result = candidate; break }
                }
                result
            })
        panel = newPanel; root.addView(newPanel, FrameLayout.LayoutParams(-1, -1)); newPanel.start(entry)
    }
    private fun closePlayer() {
        panel?.dispose(); panel = null
        if (downloadsVisible) { showDownloads(); return }
        val saved = browserContainer
        if (saved != null && browserList != null) {
            root.removeAllViews(); root.addView(saved, FrameLayout.LayoutParams(-1, -1)); browserVisible = true
            browserList?.resumeLoading(); browserList?.restoreFocus(focusMemory[currentPath])
            updates.foreground()
        } else showBrowser()
    }
    private fun showSites() {
        val all = sites.sites()
        val labels = all.map { "${it.label} · ${it.username}" } + "添加站点"
        AlertDialog.Builder(this).setTitle("切换站点（同一时刻仅一个活动站点）").setItems(labels.toTypedArray()) { _, which ->
            if (which == all.size) showLogin() else { focusMemory.clear(); restore(all[which]) }
        }.setNegativeButton("取消", null).show()
    }
    private fun showSettings() {
        val screen = page("设置", "OpenList TV ${BuildConfig.VERSION_NAME} · 原生 Android · 无后台常驻")
        val content = TvUi.column(this)
        screen.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(TvUi.text(this, "画质：最高 4K，HDR 按设备能力输出\n图片输入上限：128 MiB，显示仍采样\n单文件超限或无法解码：轮播跳过\n索引上限：10 万条 / 64 MiB\n视频缓冲目标上限：24 MiB", 18f))
        content.addView(TvUi.spacer(this, 18))
        lateinit var checkUpdate: Button
        checkUpdate = TvUi.button(this, "检查 GitHub 更新", primary = true) { updates.manual(checkUpdate) }
        content.addView(checkUpdate)
        updates.addSettings(content)
        content.addView(TvUi.spacer(this, 10))
        content.addView(TvUi.button(this, "本机下载") { showDownloads() })
        content.addView(TvUi.spacer(this, 10))
        content.addView(TvUi.button(this, "第三方许可") {
            val text = TvUi.text(this, assets.open("third-party-notices.txt").bufferedReader().use { it.readText() }, 12f).apply { setPadding(dp(16), dp(12), dp(16), dp(12)) }
            AlertDialog.Builder(this).setTitle("第三方许可").setView(ScrollView(this).apply { addView(text) }).setPositiveButton("关闭", null).show()
        })
        content.addView(TvUi.spacer(this, 10))
        content.addView(TvUi.button(this, "清理资源索引与图片缓存") {
            work = lifecycleScope.launch { withContext(Dispatchers.IO) { catalog.clear(); cacheDir.resolve("images").listFiles()?.forEach { it.delete() } }; Toast.makeText(this@MainActivity, "缓存已清理，服务器文件未改变", Toast.LENGTH_SHORT).show() }
        })
        content.addView(TvUi.spacer(this, 10))
        content.addView(TvUi.button(this, "退出当前账号") {
            val session = repository?.session ?: return@button
            AlertDialog.Builder(this).setTitle("退出账号？").setMessage("清除本机会话和该账号的缓存，不删除服务器资源。已下载的本机副本保留，可在本机下载中单独删除。").setNegativeButton("取消", null).setPositiveButton("退出") { _, _ ->
                work = lifecycleScope.launch {
                    downloads.pauseAndJoin("账号已退出，下载已暂停")
                    withContext(Dispatchers.IO) { sites.signOut(session.site); catalog.clear(session.site.id, clearHistory = true) }
                    repository = null
                    val signedOut = try { api.logout(session); true } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                    Toast.makeText(this@MainActivity, if (signedOut) "已退出" else "本机已退出，服务端注销未确认", Toast.LENGTH_LONG).show()
                    showLogin(session.site)
                }
            }.show()
        })
        content.addView(TvUi.spacer(this, 10))
        content.addView(TvUi.button(this, "返回文件浏览") { showBrowser() })
        // Switch's thumb-focused rectangle can make Android skip a full-width switch
        // between left-aligned buttons. Define a predictable TV up/down chain.
        TvUi.verticalFocus(*(0 until content.childCount).map(content::getChildAt).filter { it.isFocusable }.toTypedArray())
        checkUpdate.requestFocus()
    }
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    private fun downloadMenu() {
        val entry = selectedEntry?.takeUnless { it.directory }
        if (entry == null) { showDownloads(); return }
        AlertDialog.Builder(this).setTitle("下载")
            .setItems(arrayOf("下载选中项：${entry.name}", "本机下载 / 传输进度")) { _, index -> if (index == 0) offerDownload(entry) else showDownloads() }
            .setNegativeButton("取消", null).show()
    }
    private fun fileActions(entry: Entry) {
        selectedEntry = entry
        if (entry.directory) { toast("下载支持单个文件，请进入目录选择文件"); return }
        AlertDialog.Builder(this).setTitle(entry.name).setMessage("${DisplayText.bytes(entry.size)}\n可下载原文件到本机；不修改服务器资源。")
            .setPositiveButton("下载文件") { _, _ -> offerDownload(entry) }.setNeutralButton("本机下载") { _, _ -> showDownloads() }.setNegativeButton("取消", null).show()
    }
    private fun offerDownload(entry: Entry) {
        val repo = repository ?: return
        if (updates.downloading) { toast("正在下载应用更新，请稍后再下载资源"); return }
        if (downloads.busy) { toast("已有传输正在进行，请在本机下载中暂停后再开始"); return }
        AlertDialog.Builder(this).setTitle("下载 ${entry.name}？")
            .setMessage("${DisplayText.bytes(entry.size)}\n\n保存到应用的本机下载，可离线打开或导出到系统允许的位置。单任务前台下载；播放、退出或休眠时暂停。无可靠续传标识时从头重试。\n\n清缓存不删除下载；卸载应用会删除本机副本。")
            .setPositiveButton("开始下载") { _, _ -> if (downloads.start(repo, entry)) showDownloads() else toast("已有传输正在进行") }
            .setNegativeButton("取消", null).show()
    }
    private fun showDownloads() {
        val content = page("本机下载", "前台单任务 · 可离线打开 · 不修改服务器")
        downloadsVisible = true
        val back = TvUi.button(this, if (repository == null) "返回登录" else "返回文件浏览") { if (repository == null) showLogin() else showBrowser() }
        content.addView(back); back.requestFocus()
        content.addView(TvUi.spacer(this, 8))
        downloadList = DownloadsPanel(this, downloads, lifecycleScope) { downloadActions(it) }.also { content.addView(it, LinearLayout.LayoutParams(-1, 0, 1f)) }
    }
    private fun downloadActions(selected: SavedDownload) {
        lifecycleScope.launch {
            val record = withContext(Dispatchers.IO) { downloads.store.get(selected.id) } ?: return@launch
            if (!downloadsVisible || panel != null) return@launch
            val actions = mutableListOf<Pair<String, () -> Unit>>()
            if (record.status == DownloadStatus.COMPLETE) {
                if (record.entry.kind != MediaKind.OTHER) actions += "离线打开" to { openDownloaded(record) }
                actions += "导出到 U 盘 / 其他位置" to { exportDownloaded(record) }
            } else actions += "继续 / 重试下载" to {
                val repo = repository
                if (updates.downloading) toast("正在下载应用更新，请稍后再传输资源")
                else if (repo == null || DownloadStore.identity(repo.session.site) != record.identity) toast("请先登录原站点账号，再继续此下载")
                else if (!downloads.start(repo, record.entry, record)) toast("已有传输正在进行，请先暂停")
            }
            actions += "查看下载详情" to {
                AlertDialog.Builder(this@MainActivity).setTitle(record.entry.name)
                    .setMessage("${record.status.label} · ${record.siteLabel}\n已保存 ${DisplayText.bytes(record.bytes)} / ${DisplayText.bytes(record.total)}\n\n${record.message}\n\n清缓存和退出账号不删除本机副本；卸载应用会删除。")
                    .setPositiveButton("关闭", null).show()
            }
            actions += "删除本机文件与记录" to {
                AlertDialog.Builder(this@MainActivity).setTitle("删除本机副本？").setMessage("仅删除 ${record.entry.name} 的本机下载和记录，不影响服务器原文件。本机副本删除后需重新下载。")
                    .setPositiveButton("删除") { _, _ -> lifecycleScope.launch {
                        try {
                            if (downloads.state.value.id == record.id) downloads.pauseAndJoin()
                            withContext(Dispatchers.IO) { downloads.store.remove(record.id) }
                            downloads.changed(); toast("本机副本与记录已删除，服务器原文件保留")
                        } catch (e: Exception) { if (e is CancellationException) throw e; toast(message(e)) }
                    } }.setNegativeButton("取消", null).show()
            }
            AlertDialog.Builder(this@MainActivity).setTitle(record.entry.name)
                .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }.setNegativeButton("关闭", null).show()
        }
    }
    private fun openDownloaded(record: SavedDownload) {
        val stamp = generation
        work?.cancel()
        work = lifecycleScope.launch {
            try {
                downloads.pauseAndJoin("离线播放优先，传输已暂停")
                val session = Session(Site("local-downloads", "本机下载", "", "本机"), "")
                val local = FileRepository(session, LocalDownloadGateway(downloads.store), catalog)
                withContext(Dispatchers.IO) { downloads.store.completedFile(record); catalog.clear(session.site.id) }
                local.ensure("/")
                val page = local.page("/", 0, sort)
                val index = page.entries.indexOfFirst { it.path == "/${record.id}" }
                if (index < 0) throw ClientFailure(FailureKind.ACCESS, "下载文件不可用")
                if (stamp == generation && downloadsVisible) openPlayer(page.entries[index], index, null, playerRepository = local, playerPath = "/", playerQuery = BrowserQuery())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast(message(e)) }
        }
    }
    private fun exportDownloaded(record: SavedDownload) {
        lifecycleScope.launch {
            downloads.pauseAndJoin("选择导出位置，原传输已暂停")
            exportId = record.id
            try { exportDestination.launch(record.entry.name.take(240)) }
            catch (_: android.content.ActivityNotFoundException) { exportId = null; toast("此系统没有文件选择器，无法选择 U 盘位置；仍可在本机下载中离线打开") }
        }
    }
    private fun updateButton(): Button {
        lateinit var button: Button
        button = TvUi.button(this, "检查 GitHub 更新") { updates.manual(button) }
        return button
    }
    private fun handleRemoteKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) return true
        if (panel?.handleKey(event) == true) return true
        if (browserVisible && event.action == KeyEvent.ACTION_DOWN && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT) && root.findFocus()?.contentDescription?.startsWith("打开 ") == true) {
            if (event.repeatCount == 0 && work?.isActive != true) browserList?.jumpPage(if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
            return true
        }
        return false
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = handleRemoteKey(event) || super.onKeyDown(keyCode, event)
    override fun onStart() { super.onStart(); updates.onStart(); panel?.foreground(); if (browserVisible && panel == null) browserList?.resumeLoading() }
    override fun onResume() { super.onResume(); updates.onResume() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); panel?.windowFocusChanged(hasFocus); if (hasFocus && ::root.isInitialized) updates.foreground() }
    override fun onStop() { work?.cancel(); updates.background(); downloads.pause("离开前台，传输已暂停；可手动继续"); browserList?.pauseLoading(); panel?.background(); super.onStop() }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); if (level != TRIM_MEMORY_UI_HIDDEN) panel?.trimMemory() }
    override fun onDestroy() { work?.cancel(); downloadList?.dispose(); browserList?.dispose(); panel?.dispose(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("download.exportId", exportId); updates.save(outState); super.onSaveInstanceState(outState) }
    companion object {
        fun message(e: Exception): String {
            if (BuildConfig.DEBUG) android.util.Log.d("OpenListTV", generateSequence<Throwable>(e) { it.cause }.joinToString(" <- ") { it.javaClass.simpleName + ": " + (it.message.orEmpty().replace(Regex("https?://\\S+"), "[address omitted]").take(160)) })
            return if (e is ClientFailure) e.message ?: "请求失败" else "操作失败，请检查网络或重试"
        }
    }

}

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
            val inset = if (resources.configuration.screenWidthDp < 600) 16 else 32
            setPadding(dp(inset), dp(18), dp(inset), dp(12))
            val heading = TvUi.column(this@MainActivity)
            heading.addView(TvUi.text(this@MainActivity, "OPENLIST  /  TV", 10f, TvUi.accent).apply { letterSpacing = .16f; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) })
            heading.addView(TvUi.spacer(this@MainActivity, 8))
            heading.addView(TvUi.title(this@MainActivity, title).also(TvUi::ellipsis))
            heading.addView(TvUi.spacer(this@MainActivity, 6))
            heading.addView(TvUi.text(this@MainActivity, subtitle, 12f, TvUi.muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            addView(heading)
            addView(TvUi.spacer(this@MainActivity, 14))
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
            // Stored sessions contain only a token, so their default basePath is not an
            // earlier validated root. The snapshot identity checks the current validated
            // basePath separately; never clear valid caches by comparing it with that default.
            val changedIdentity = previous != null && (previous.username != session.site.username || previous.url != session.site.url)
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
        val content = page("连接你的资源", "照片、音乐与影片，在大屏上轻松打开")
        val scroll = ScrollView(this)
        val form = TvUi.column(this).apply { background = TvUi.shape(TvUi.card, dp(20).toFloat()); setPadding(dp(20), dp(20), dp(20), dp(16)) }
        scroll.addView(form)
        val centered = UiPanels.ContentFrame(this, 540).apply { addView(scroll, FrameLayout.LayoutParams(-1, -1)) }
        content.addView(centered, LinearLayout.LayoutParams(-1, 0, 1f))
        val label = TvUi.input(this, "站点名称", existing?.label.orEmpty())
        val address = TvUi.input(this, "服务器地址（含 https://）", existing?.url.orEmpty()).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        val username = TvUi.input(this, "账号", existing?.username.orEmpty())
        val password = TvUi.input(this, "密码").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; isSaveEnabled = false }
        val http = TvUi.check(this, "允许此站点使用 HTTP（局域网明文传输）", existing?.allowHttp ?: false)
        val status = TvUi.text(this, "会话加密保存，不保存原始密码。", 12f, TvUi.muted)
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
        row.addView(TvUi.button(this, "本机下载") { showDownloads() })
        row.addView(TvUi.button(this, "更多") {
            updateSettings()
        })
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
        toolbar.addView(TvUi.button(this, "搜索") { searchDialog() }.apply { contentDescription = "搜索 / 筛选" })
        toolbar.addView(TvUi.button(this, "轮播", primary = true) { slideshowDialog() }.apply { contentDescription = "开始轮播" })
        toolbar.addView(TvUi.button(this, "更多") { browserMenu() })
        val heading = content.getChildAt(0)
        content.removeView(heading)
        content.addView(UiPanels.Header(this, heading, toolbar), 0, LinearLayout.LayoutParams(-1, -2))
        val status = TvUi.text(this, "准备目录…", 12f, TvUi.muted).apply { maxLines = 2; setPadding(dp(2), dp(2), 0, dp(6)) }
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
                status.text = (if (result.total == 0) if (viewQuery.active) "没有匹配项" else "目录为空" else "${result.total} 项  ·  ${sort.field.label}${if (sort.descending) "降序" else "升序"}") + if (viewQuery.active) "\n${viewQuery.description}" else ""
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
    private fun browserMenu() {
        UiPanels.show(UiPanels.menu(this, "资源库操作", listOf(
            UiPanels.Action("排序", "${sort.field.label} · ${if (sort.descending) "降序" else "升序"}") { sortingDialog() },
            UiPanels.Action("重新加载", "重新读取当前目录") { showBrowser(true) },
            UiPanels.Action("根目录", "返回站点根目录") { currentPath = "/"; offset = 0; showBrowser() },
            UiPanels.Action("站点", "切换或添加 OpenList") { showSites() },
            UiPanels.Action("下载", "下载选中项，或查看本机文件") { downloadMenu() },
            UiPanels.Action("设置", "更新、账号与存储") { showSettings() },
        )))
    }
    private fun searchDialog() {
        val current = browserQuery
        var type = current.type
        val form = UiPanels.form(this)
        val input = TvUi.input(this, "当前目录文件名搜索", current.text).apply { filters = arrayOf(InputFilter.LengthFilter(128)); isSaveEnabled = false }
        lateinit var choice: UiPanels.Preference
        choice = UiPanels.Preference(this, "文件类型", "与文件名组合筛选", type.label) {
            UiPanels.show(UiPanels.menu(this, "文件类型", BrowserType.entries.map { candidate ->
                UiPanels.Action(candidate.label) { type = candidate; choice.value(type.label) }
            }))
        }
        form.addView(input); form.addView(choice)
        form.addView(TvUi.text(this, "只搜索当前目录，不扫描子目录，也不改变轮播范围。", 12f, TvUi.muted).apply { setPadding(dp(16), dp(12), dp(16), dp(4)) })
        val dialog = AlertDialog.Builder(this).setTitle("搜索与筛选").setView(form)
            .setPositiveButton("应用") { _, _ ->
                queries[currentPath] = BrowserQuery(input.text.toString().trim(), type)
                offset = 0; focusMemory.remove(currentPath); showBrowser()
            }.setNeutralButton("清除") { _, _ -> queries.remove(currentPath); offset = 0; focusMemory.remove(currentPath); showBrowser() }
            .setNegativeButton("取消", null).create()
        UiPanels.show(dialog, input); TvUi.verticalFocus(input, choice, dialog.getButton(AlertDialog.BUTTON_POSITIVE))
    }
    private fun directoryPassword(path: String) {
        val input = TvUi.input(this, "目录密码").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; isSaveEnabled = false }
        AlertDialog.Builder(this).setTitle("目录密码").setMessage("密码仅在当前会话内记忆。").setView(input)
            .setPositiveButton("确定") { _, _ -> repository?.remember(path, input.text.toString()); input.text.clear(); showBrowser(true) }.setNegativeButton("取消", null).show()
    }
    private fun sortingDialog() {
        var field = sort.field
        val form = UiPanels.form(this)
        lateinit var choice: UiPanels.Preference
        choice = UiPanels.Preference(this, "排序依据", "应用于当前目录的全部文件", field.label) {
            UiPanels.show(UiPanels.menu(this, "排序依据", SortField.entries.map { candidate ->
                UiPanels.Action(candidate.label) { field = candidate; choice.value(field.label) }
            }))
        }
        val descending = UiPanels.toggle(this, "降序", sort.descending) {}
        val folders = UiPanels.toggle(this, "文件夹置顶", sort.foldersFirst) {}
        form.addView(choice); form.addView(descending); form.addView(folders)
        val dialog = AlertDialog.Builder(this).setTitle("排序").setView(form).setPositiveButton("应用") { _, _ ->
            sort = SortOrder(field, descending.isChecked, folders.isChecked)
            repository?.let { sites.setSort(it.session.site, sort) }; offset = 0; showBrowser()
        }.setNegativeButton("取消", null).create()
        UiPanels.show(dialog, choice); TvUi.verticalFocus(choice, descending, folders, dialog.getButton(AlertDialog.BUTTON_POSITIVE))
    }
    private fun slideshowDialog() {
        val dialog = SlideshowSetup(this, currentPath, sites.slideshow()) { options ->
            sites.setSlideshow(options); openPlayer(null, 0, options)
        }.dialog
        slideshowSettings = dialog
        dialog.setOnDismissListener { if (slideshowSettings === dialog) slideshowSettings = null }
        dialog.show()
    }
    private fun fitSlideshowDialog() { slideshowSettings?.let(UiPanels::fit) }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Keep directory, current media and timers alive while native views reflow.
        browserList?.updateCompactLayout(); fitSlideshowDialog(); UiPanels.refitOpenDialogs()
        (root.getChildAt(0) as? LinearLayout)?.let {
            val inset = dp(if (newConfig.screenWidthDp < 600) 16 else 32)
            it.setPadding(inset, it.paddingTop, inset, it.paddingBottom)
        }
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
        val actions = all.map { site -> UiPanels.Action(site.label, site.username + if (site.id == repository?.session?.site?.id) " · 当前站点" else "") {
            focusMemory.clear(); restore(site)
        } } + UiPanels.Action("添加站点", "连接另一台 OpenList") { showLogin() }
        UiPanels.show(UiPanels.menu(this, "我的站点", actions, "同一时刻只连接一个站点"))
    }
    private fun showSettings() {
        val screen = page("设置", "OpenList TV ${BuildConfig.VERSION_NAME}")
        val content = UiPanels.form(this)
        val rows = listOf(
            UiPanels.Preference(this, "应用更新", "更新策略、代理和安装方式", BuildConfig.VERSION_NAME) { updateSettings() },
            UiPanels.Preference(this, "本机下载", "离线打开、导出与传输进度", "") { showDownloads() },
            UiPanels.Preference(this, "站点与账号", repository?.session?.site?.label ?: "管理已保存站点", "") { accountSettings() },
            UiPanels.Preference(this, "存储与缓存", "资源预算与本机缓存管理", "") { storageSettings() },
            UiPanels.Preference(this, "关于 OpenList TV", "轻量原生播放器与第三方许可", "") { about() },
        )
        rows.forEach(content::addView)
        val back = TvUi.button(this, "返回文件浏览") { showBrowser() }
        content.addView(TvUi.spacer(this, 14)); content.addView(back)
        val centered = UiPanels.ContentFrame(this, 720).apply { addView(UiPanels.scroll(this@MainActivity, content), FrameLayout.LayoutParams(-1, -1)) }
        screen.addView(centered, LinearLayout.LayoutParams(-1, 0, 1f))
        TvUi.verticalFocus(*(rows + back).toTypedArray()); rows.first().requestFocus()
    }
    private fun updateSettings() {
        val content = UiPanels.form(this)
        lateinit var check: Button
        check = TvUi.button(this, "检查 GitHub 更新", primary = true) { updates.manual(check) }
        content.addView(check); content.addView(UiPanels.section(this, "更新偏好"))
        updates.addSettings(content)
        val dialog = AlertDialog.Builder(this).setTitle("应用更新").setView(UiPanels.scroll(this, content)).setNegativeButton("完成", null).create()
        UiPanels.show(dialog, check)
        val focus = (0 until content.childCount).map(content::getChildAt).filter { it.isFocusable }
        TvUi.verticalFocus(*(focus + dialog.getButton(AlertDialog.BUTTON_NEGATIVE)).toTypedArray())
    }
    private fun accountSettings() {
        val site = repository?.session?.site
        val actions = mutableListOf(UiPanels.Action("切换或添加站点") { showSites() })
        if (site != null) {
            actions += UiPanels.Action("编辑当前站点", site.label) { showLogin(site) }
            actions += UiPanels.Action("退出当前账号", "本机下载保留，服务器文件不变") { signOut() }
        }
        UiPanels.show(UiPanels.menu(this, "站点与账号", actions))
    }
    private fun storageSettings() {
        UiPanels.show(UiPanels.menu(this, "存储与缓存", listOf(
            UiPanels.Action("资源预算", "查看图片、索引与播放缓冲限制") {
                UiPanels.show(AlertDialog.Builder(this).setTitle("资源预算")
                    .setMessage("图片输入最多 128 MiB，显示按内存预算采样。\n索引最多 10 万候选，共享 64 MiB 磁盘预算。\n音视频缓冲目标 24 MiB。\n\n最高 4K、HDR 取决于设备能力；无法解码且无低清源时轮播跳过。")
                    .setPositiveButton("知道了", null).create())
            },
            UiPanels.Action("清理资源索引与图片缓存", "不删除账号、播放进度或本机下载") {
                UiPanels.show(AlertDialog.Builder(this).setTitle("清理缓存？").setMessage("仅清理可重新生成的资源索引和图片缓存，不影响服务器文件或本机下载。")
                    .setPositiveButton("清理") { _, _ -> work = lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) { catalog.clear(); cacheDir.resolve("images").listFiles()?.forEach { it.delete() } }
                            toast("缓存已清理，服务器文件未改变")
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { toast(message(e)) }
                    } }.setNegativeButton("取消", null).create())
            },
        )))
    }
    private fun about() {
        UiPanels.show(UiPanels.menu(this, "OpenList TV", listOf(UiPanels.Action("第三方许可", "查看开源组件与授权") {
            val text = TvUi.text(this, assets.open("third-party-notices.txt").bufferedReader().use { it.readText() }, 12f).apply { setPadding(dp(20), dp(12), dp(20), dp(12)) }
            UiPanels.show(AlertDialog.Builder(this).setTitle("第三方许可").setView(UiPanels.scroll(this, text)).setPositiveButton("关闭", null).create())
        }), "${BuildConfig.VERSION_NAME} · 原生 Android\n为电视与投影设计，无后台常驻"))
    }
    private fun signOut() {
        val session = repository?.session ?: return
        UiPanels.show(AlertDialog.Builder(this).setTitle("退出账号？").setMessage("清除本机会话和该账号的索引及历史，不删除服务器资源。本机下载保留。")
            .setNegativeButton("取消", null).setPositiveButton("退出") { _, _ ->
                work = lifecycleScope.launch {
                    downloads.pauseAndJoin("账号已退出，下载已暂停")
                    withContext(Dispatchers.IO) { sites.signOut(session.site); catalog.clear(session.site.id, clearHistory = true) }
                    repository = null
                    val signedOut = try { api.logout(session); true } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                    toast(if (signedOut) "已退出" else "本机已退出，服务端注销未确认")
                    showLogin(session.site)
                }
            }.create())
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
    override fun onDestroy() { work?.cancel(); downloadList?.dispose(); browserList?.dispose(); panel?.dispose(); UiPanels.dismissFor(this); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("download.exportId", exportId); updates.save(outState); super.onSaveInstanceState(outState) }
    companion object {
        fun message(e: Exception): String {
            if (BuildConfig.DEBUG) android.util.Log.d("OpenListTV", generateSequence<Throwable>(e) { it.cause }.joinToString(" <- ") { it.javaClass.simpleName + ": " + (it.message.orEmpty().replace(Regex("https?://\\S+"), "[address omitted]").take(160)) })
            return if (e is ClientFailure) e.message ?: "请求失败" else "操作失败，请检查网络或重试"
        }
    }

}

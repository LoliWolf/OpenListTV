package io.github.loliwolf.openlisttv

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
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
    private val updates by lazy { UpdateController(this) { browserVisible && panel == null && slideshowSettings == null && root.hasWindowFocus() && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) } }
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
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                panel?.let { if (!it.back()) closePlayer(); return }
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
                }
            }
        }
    }
    private suspend fun activate(session: Session) {
        val oldSite = repository?.session?.site?.id
        withContext(Dispatchers.IO) {
            if (oldSite != null && oldSite != session.site.id) catalog.clear(oldSite)
            val previous = sites.sites().firstOrNull { it.id == session.site.id }
            val changedIdentity = previous != null && (previous.username != session.site.username || previous.url != session.site.url)
            sites.save(session)
            // Preserve progress on upgrades/reconnects, but never carry it to another
            // account or server when an existing site's login fields are replaced.
            catalog.clear(session.site.id, clearHistory = changedIdentity)
        }
        repository = FileRepository(session, api, catalog)
        sort = sites.sort(session.site); currentPath = "/"; offset = 0; focusMemory.clear()
        showBrowser()
    }
    private fun showLogin(existing: Site? = null) {
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
        form.addView(row)
        address.requestFocus()
    }
    private fun showBrowser(force: Boolean = false) {
        val repo = repository ?: return showLogin()
        val content = page(repo.session.site.label, "${repo.session.site.username} · ${currentPath}")
        browserVisible = true
        val toolbar = ActionFlow(this)
        toolbar.addView(TvUi.button(this, "上一级") { if (currentPath != "/") { currentPath = Paths.parent(currentPath); offset = 0 }; showBrowser() })
        toolbar.addView(TvUi.button(this, "根目录") { currentPath = "/"; offset = 0; showBrowser() })
        toolbar.addView(TvUi.button(this, "排序") { sortingDialog() })
        toolbar.addView(TvUi.button(this, "开始轮播", primary = true) { slideshowDialog() })
        toolbar.addView(TvUi.button(this, "重新加载") { showBrowser(true) })
        toolbar.addView(TvUi.button(this, "站点") { showSites() })
        toolbar.addView(TvUi.button(this, "设置") { showSettings() })
        content.addView(toolbar); content.addView(TvUi.spacer(this, 10))
        val status = TvUi.text(this, "准备目录…", 14f, TvUi.muted)
        content.addView(status)
        val listHost = FrameLayout(this)
        content.addView(listHost, LinearLayout.LayoutParams(-1, 0, 1f))
        val currentGeneration = generation
        var loading = true
        val path = currentPath
        val pageOffset = offset
        work = lifecycleScope.launch {
            try {
                val result = if (sort.field == SortField.SERVER && !sort.foldersFirst) repo.remotePage(path, pageOffset) else {
                    repo.ensure(path, force) { read, total -> status.post { if (generation == currentGeneration && loading) status.text = "整理完整目录 $read / $total · ${sort.field.label}" } }
                    repo.page(path, pageOffset, sort)
                }
                loading = false
                if (generation != currentGeneration) return@launch
                browserTotal = result.total
                status.text = if (result.total == 0) "目录为空" else "${result.total} 项 · ${sort.field.label}${if (sort.descending) "降序" else "升序"} · 图片自动预览"
                if (result.total > 0) {
                    val browser = BrowserList(this@MainActivity, repo, path, sort, result, pageOffset, lifecycleScope,
                        opened = { e, index ->
                            focusMemory[path] = e.path
                            if (e.directory) { currentPath = e.path; offset = 0; showBrowser() }
                            else if (e.kind != MediaKind.OTHER) openPlayer(e, index, null)
                            else AlertDialog.Builder(this@MainActivity).setTitle(e.name).setMessage("当前只支持图片、音乐和视频查看。此文件不会被下载或修改。").setPositiveButton("知道了", null).show()
                        }, focused = { focusMemory[path] = it.path }, pageChanged = { offset = it }, problem = { status.text = message(it) })
                    browserList = browser; browserContainer = content
                    listHost.addView(browser, FrameLayout.LayoutParams(-1, -1)); browser.restoreFocus(focusMemory[path])
                } else {
                    val empty = TvUi.column(this@MainActivity).apply { gravity = Gravity.CENTER }
                    empty.addView(TvUi.title(this@MainActivity, "这里还没有文件"))
                    empty.addView(TvUi.text(this@MainActivity, "可以返回上一级，或重新加载目录。", 16f, TvUi.muted))
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
        val imageSeconds = TvUi.input(this, "图片停留秒数（1–3600）", options.imageSeconds.toString(), "秒").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val videoSeconds = TvUi.input(this, "视频秒数（0 表示播放到结束）", options.videoSeconds.toString(), "秒").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val audioSeconds = TvUi.input(this, "音频秒数（0 表示播放到结束）", options.audioSeconds.toString(), "秒").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val prefetch = TvUi.check(this, "预取音视频地址（图片始终预加载一张）", options.prefetch)
        form.addView(TvUi.text(this, "播放方式", 13f, TvUi.accent)); form.addView(modes)
        form.addView(recursive); form.addView(imageOnly); form.addView(TvUi.spacer(this, 12))
        form.addView(TvUi.text(this, "播放时长", 13f, TvUi.accent)); form.addView(TvUi.spacer(this, 8))
        listOf(imageSeconds, videoSeconds, audioSeconds).forEach(form::addView)
        form.addView(TvUi.text(this, "图片：到时切换。视频 / 音频：填 0 播放到结束，填其他数字则最多播放该时长。", 14f, TvUi.muted))
        form.addView(prefetch)
        form.addView(TvUi.text(this, "只提前准备一张图片；慢网时保留当前画面，准备好再切换。", 13f, TvUi.muted))
        val scroll = ScrollView(this).apply { addView(form) }
        val dialog = AlertDialog.Builder(this).setTitle("从 $currentPath 开始轮播").setView(scroll).setPositiveButton("开始", null).setNegativeButton("取消", null).create()
        slideshowSettings = dialog
        dialog.setOnDismissListener { if (slideshowSettings === dialog) slideshowSettings = null }
        dialog.setOnShowListener {
            TvUi.verticalFocus(imageSeconds, videoSeconds, audioSeconds, prefetch, dialog.getButton(AlertDialog.BUTTON_POSITIVE))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val o = runCatching { SlideshowOptions(TraversalMode.entries[modeIds.indexOf(modes.checkedRadioButtonId).coerceAtLeast(0)], recursive.isChecked, imageOnly.isChecked, imageSeconds.text.toString().toInt(), videoSeconds.text.toString().toInt(), audioSeconds.text.toString().toInt(), prefetch.isChecked) }.getOrNull()
                if (o == null) { Toast.makeText(this, "图片须为 1–3600 秒，音视频须为 0–3600 秒", Toast.LENGTH_LONG).show(); return@setOnClickListener }
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
    private fun openPlayer(entry: Entry?, origin: Int, options: SlideshowOptions?, promptResume: Boolean = true) {
        val repo = repository ?: return
        if (promptResume && options == null && entry != null && entry.kind in listOf(MediaKind.VIDEO, MediaKind.AUDIO)) {
            work = lifecycleScope.launch {
                val position = withContext(Dispatchers.IO) { catalog.position(repo.session.site.id, entry) }
                if (position < 3000) openPlayer(entry, origin, null, false)
                else AlertDialog.Builder(this@MainActivity).setTitle(entry.name).setMessage("上次播放到 ${position / 60000}:${((position / 1000) % 60).toString().padStart(2, '0')}")
                    .setPositiveButton("继续播放") { _, _ -> openPlayer(entry, origin, null, false) }
                    .setNeutralButton("从头播放") { _, _ -> work = lifecycleScope.launch { withContext(Dispatchers.IO) { catalog.savePosition(repo.session.site.id, entry, 0) }; openPlayer(entry, origin, null, false) } }
                    .setNegativeButton("取消", null).show()
            }
            return
        }
        work?.cancel(); generation++; browserVisible = false; browserList?.pauseLoading(); root.removeAllViews()
        var manualIndex = origin
        val newPanel = PlaybackPanel(this, repo, catalog, currentPath, sort, options, lifecycleScope,
            onExit = { closePlayer() }, onManualNext = { delta ->
                repo.ensure(currentPath)
                var result: Entry? = null
                while (true) {
                    manualIndex += delta
                    if (manualIndex < 0) break
                    val page = repo.page(currentPath, manualIndex, sort, 1)
                    val candidate = page.entries.firstOrNull() ?: break
                    if (!candidate.directory && candidate.kind != MediaKind.OTHER) { result = candidate; break }
                }
                result
            })
        panel = newPanel; root.addView(newPanel, FrameLayout.LayoutParams(-1, -1)); newPanel.start(entry)
    }
    private fun closePlayer() {
        panel?.dispose(); panel = null
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
        content.addView(TvUi.check(this, "自动检查 GitHub 更新（每天一次）", updates.automatic).apply { setOnCheckedChangeListener { _, checked -> updates.automatic = checked } })
        content.addView(TvUi.text(this, "仅检查正式 Release，不发送 OpenList 会话信息，不在轮播中弹窗。", 13f, TvUi.muted))
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
            AlertDialog.Builder(this).setTitle("退出账号？").setMessage("清除本机会话和该账号的缓存，不删除服务器资源。").setNegativeButton("取消", null).setPositiveButton("退出") { _, _ ->
                work = lifecycleScope.launch {
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
        checkUpdate.requestFocus()
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
    override fun onStart() { super.onStart(); if (browserVisible && panel == null) browserList?.resumeLoading() }
    override fun onResume() { super.onResume(); updates.foreground() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus && ::root.isInitialized) updates.foreground() }
    override fun onStop() { work?.cancel(); updates.cancel(); browserList?.pauseLoading(); panel?.background(); super.onStop() }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); if (level != TRIM_MEMORY_UI_HIDDEN) panel?.trimMemory() }
    override fun onDestroy() { work?.cancel(); browserList?.dispose(); panel?.dispose(); super.onDestroy() }
    companion object {
        fun message(e: Exception): String {
            if (BuildConfig.DEBUG) android.util.Log.d("OpenListTV", generateSequence<Throwable>(e) { it.cause }.joinToString(" <- ") { it.javaClass.simpleName + ": " + (it.message.orEmpty().replace(Regex("https?://\\S+"), "[address omitted]").take(160)) })
            return if (e is ClientFailure) e.message ?: "请求失败" else "操作失败，请检查网络或重试"
        }
    }

}

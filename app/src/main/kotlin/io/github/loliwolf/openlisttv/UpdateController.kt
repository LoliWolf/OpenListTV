package io.github.loliwolf.openlisttv

import android.app.AlertDialog
import android.content.*
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import io.github.loliwolf.openlisttv.TvUi.dp
import io.github.loliwolf.openlisttv.core.UpdateLaunchGate
import io.github.loliwolf.openlisttv.data.*
import kotlinx.coroutines.*
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrl

/** User-initiated installation only; no background service or silent installation. */
class UpdateController(
    private val activity: ComponentActivity,
    private val canPromptAutomatically: () -> Boolean,
    private val beforeDownload: suspend () -> Unit = {},
    private val afterDownload: () -> Unit = {},
    private val networkFactory: (UpdateProxy) -> UpdateNetwork = { UpdateNetwork(it) },
    private val intentLauncher: (Intent) -> Unit = { activity.startActivity(it) },
    private val packageStore: UpdatePackages = (activity.application as TvApplication).updatePackages,
    private val prefs: android.content.SharedPreferences = activity.getSharedPreferences("github-updates", Context.MODE_PRIVATE),
) {
    private val packages get() = packageStore
    private val gate = UpdateLaunchGate()
    private var checkJob: Job? = null
    private var actionJob: Job? = null
    private var checkingManually = false
    private var pending: GithubRelease? = null
    private var pendingMessage: String? = null
    private var external = false
    private var dialog: AlertDialog? = null
    val downloading get() = actionJob != null || packages.busy
    var automatic: Boolean
        get() = prefs.getBoolean("automatic", true)
        set(value) { prefs.edit().putBoolean("automatic", value).apply(); pending = null; pendingMessage = null; if (!value && !checkingManually) checkJob?.cancel(); if (value) { gate.request(); foreground() } }
    var testing: Boolean
        get() = prefs.getBoolean("testing", false)
        set(value) { prefs.edit().putBoolean("testing", value).apply(); configurationChanged() }
    var browserDownload: Boolean
        get() = prefs.getBoolean("browser.download", false)
        set(value) {
            // Do not resume a previous native installer handoff after switching modes.
            prefs.edit().putBoolean("browser.download", value).apply {
                if (value) remove("permission.pending")
            }.apply()
        }
    private fun savedProxy(): UpdateProxy = UpdateProxy(runCatching { UpdateProxyMode.valueOf(prefs.getString("proxy.mode", "NETWORK")!!) }.getOrDefault(UpdateProxyMode.NETWORK), prefs.getString("proxy.address", "").orEmpty())
    private fun proxy() = savedProxy().normalized()
    private fun configurationChanged() { checkJob?.cancel(); pending = null; pendingMessage = null; gate.request(); activity.window.decorView.post { foreground() } }

    fun onStart() { gate.enter(external || prefs.getBoolean("permission.pending", false) && System.currentTimeMillis() - prefs.getLong("permission.time", 0) in 0..15 * 60_000L); external = false }
    fun restore(state: Bundle?) { external = state?.getBoolean("update.external", false) ?: false }
    fun save(state: Bundle) { state.putBoolean("update.external", external) }
    fun onResume() {
        if (prefs.getBoolean("permission.pending", false)) {
            val recent = System.currentTimeMillis() - prefs.getLong("permission.time", 0) in 0..15 * 60_000L
            prefs.edit().remove("permission.pending").apply()
            if (recent && allowedToInstall() && !browserDownload) activity.lifecycleScope.launch {
                try {
                    val ready = packages.cached()
                    if (ready != null && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) launchInstaller(ready.second)
                    else notice("更新包已失效，请重新检查更新")
                } catch (e: CancellationException) { throw e } catch (_: Exception) { notice("无法读取已下载更新，请重新检查更新") }
            } else if (!browserDownload) notice("安装授权未开启或已过期；更新包保留，再点更新即可继续安装")
        }
        foreground()
    }
    fun foreground() {
        if (!automatic || !canPromptAutomatically() || downloading || dialog?.isShowing == true) return
        pending?.let { pending = null; offer(it); return }
        pendingMessage?.let { pendingMessage = null; notice(it); return }
        if (checkJob?.isActive != true && gate.consume(automatic, true)) check(false)
    }
    fun manual(button: Button) = check(true, button)
    private fun check(manual: Boolean, button: Button? = null) {
        if (downloading) { notice("更新包正在准备，请稍候"); return }
        checkJob?.cancel()
        gate.consume(true, true)
        pending = null; pendingMessage = null; checkingManually = manual
        val channel = testing
        button?.apply { isEnabled = false; text = "正在检查…" }
        checkJob = activity.lifecycleScope.launch {
            try {
                val release = GithubUpdates(networkFactory(proxy())).check(channel)
                ensureActive()
                if (!manual && !automatic) return@launch
                if (release?.newerThan(BuildConfig.VERSION_NAME) == true) {
                    if (canPromptAutomatically() || manual && button?.isAttachedToWindow == true) offer(release) else pending = release
                } else {
                    val text = "当前版本：${BuildConfig.VERSION_NAME}\n${if (channel) "可用正式 / 测试版本" else "最新正式版本"}：${release?.version ?: "暂无可用安装包"}"
                    if (manual && button?.isAttachedToWindow == true) show(AlertDialog.Builder(activity).setTitle("没有可用更新").setMessage(text).setPositiveButton("确定", null).create())
                    else autoNotice("没有可用更新 · 当前 ${BuildConfig.VERSION_NAME}")
                }
            } catch (_: TimeoutCancellationException) {
                if (manual && button?.isAttachedToWindow == true) show(AlertDialog.Builder(activity).setTitle("检查更新超时").setMessage("请检查网络及更新代理后重试").setPositiveButton("确定", null).create())
                else if (automatic) autoNotice("更新检查超时，请检查网络及更新代理")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val text = (e as? UpdateFailure)?.message ?: "无法连接更新服务，请检查网络和更新代理"
                if (manual && button?.isAttachedToWindow == true) show(AlertDialog.Builder(activity).setTitle("检查更新失败").setMessage(text).setPositiveButton("确定", null).create()) else if (automatic) autoNotice(text)
            } finally { button?.apply { isEnabled = true; text = "检查 GitHub 更新" } }
        }
    }
    private fun autoNotice(text: String) { if (canPromptAutomatically()) notice(text) else pendingMessage = text }
    private fun offer(release: GithubRelease) {
        val route = if (browserDownload) "当前使用浏览器下载：点击更新会打开 APK 下载链接，保存位置和后续安装由浏览器及系统处理。" else "点击更新后下载安装包并打开系统安装界面；安装仍需你确认。"
        show(AlertDialog.Builder(activity).setTitle("发现${if (release.prerelease) "测试" else "正式"}版本 ${release.version}")
            .setMessage("当前版本：${BuildConfig.VERSION_NAME}\n\n${release.notes.take(1200)}\n\n$route\n覆盖安装保留配置，不要卸载。")
            .setPositiveButton("更新") { _, _ -> beginUpdate(release) }.setNeutralButton("版本说明") { _, _ -> openLink(release.pageUrl) }.setNegativeButton("稍后", null).create())
    }
    internal fun beginUpdate(release: GithubRelease) {
        if (downloading || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (browserDownload) {
            try {
                if (GithubUpdates.decodeCache(GithubUpdates.encode(release), true) != release || release.apkUrl == null || !release.newerThan(BuildConfig.VERSION_NAME)) throw UpdateFailure("没有有效的新版本 APK 下载链接")
                val config = proxy()
                val url = config.route(checkNotNull(release.apkUrl).toHttpUrl()).toString()
                if (config.mode == UpdateProxyMode.NETWORK && config.address.isNotEmpty()) notice("浏览器使用自身网络设置，不会继承应用内 HTTP / SOCKS5 代理")
                prefs.edit().remove("permission.pending").apply()
                openLink(url, apk = true)
            } catch (e: UpdateFailure) { notice(e.message ?: "无法打开更新链接") }
            return
        }
        val body = TvUi.column(activity).apply { setPadding(activity.dp(20), activity.dp(12), activity.dp(20), activity.dp(12)) }
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; isIndeterminate = true }
        val label = TvUi.text(activity, "准备更新包…", 15f)
        body.addView(label); body.addView(progress)
        val progressDialog = AlertDialog.Builder(activity).setTitle("更新 ${release.version}").setView(body).setNegativeButton("取消") { _, _ -> actionJob?.cancel() }.create()
        progressDialog.setCanceledOnTouchOutside(false); progressDialog.setOnCancelListener { actionJob?.cancel() }
        show(progressDialog)
        actionJob = activity.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                beforeDownload()
                val file = packages.obtain(release, networkFactory(proxy())) { bytes, total -> label.post {
                    if (!progressDialog.isShowing) return@post
                    label.text = "${DisplayText.bytes(bytes)} / ${DisplayText.bytes(total)}"; progress.isIndeterminate = total <= 0
                    if (total > 0) progress.progress = (bytes * 1000 / total).toInt()
                } }
                ensureActive(); progressDialog.dismiss()
                if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) requestInstall(file)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                progressDialog.dismiss()
                if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) show(AlertDialog.Builder(activity).setTitle("无法更新")
                    .setMessage((e as? UpdateFailure)?.message ?: "更新下载或校验失败，请检查网络、代理及存储空间后重试")
                    .setPositiveButton("确定", null).setNeutralButton("查看发布页") { _, _ -> openLink(release.pageUrl) }.create())
            } finally { actionJob = null; progressDialog.dismiss(); afterDownload() }
        }.also { it.start() }
    }
    private fun allowedToInstall() = Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()
    private fun requestInstall(file: File) {
        if (allowedToInstall()) { launchInstaller(file); return }
        show(AlertDialog.Builder(activity).setTitle("允许安装应用更新")
            .setMessage("安装包已下载并核对。请在系统页面允许 OpenList TV 安装应用；返回后自动打开安装界面。你仍需确认最终安装。")
            .setPositiveButton("去授权并安装") { _, _ ->
                prefs.edit().putBoolean("permission.pending", true).putLong("permission.time", System.currentTimeMillis()).apply()
                launchExternal(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            }.setNegativeButton("稍后", null).create())
    }
    @Suppress("DEPRECATION")
    internal fun installerIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.update-files", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("OpenList TV 更新包", uri) }
        val handlers = activity.packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        val system = handlers.firstOrNull { it.activityInfo.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 }
            ?: throw UpdateFailure("此设备未提供系统 APK 安装入口，请从发布页下载后使用设备支持的方式覆盖安装")
        return intent.setComponent(ComponentName(system.activityInfo.packageName, system.activityInfo.name))
    }
    private fun launchInstaller(file: File) {
        try { launchExternal(installerIntent(file)) }
        catch (e: Exception) { notice((e as? UpdateFailure)?.message ?: "无法打开系统安装界面，更新包仍保留") }
    }
    private fun launchExternal(intent: Intent) {
        external = true
        try { intentLauncher(intent) }
        catch (_: Exception) { external = false; prefs.edit().remove("permission.pending").apply(); notice("系统未提供此操作入口；请到系统设置检查安装权限") }
    }
    private fun openLink(url: String, apk: Boolean = false) {
        try {
            external = true
            intentLauncher(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { if (apk) addCategory(Intent.CATEGORY_BROWSABLE) })
        }
        catch (_: Exception) {
            external = false
            val message = if (apk) "未能打开可用浏览器。可复制 APK 下载链接，在浏览器或其他设备下载后覆盖安装。\n\n$url" else url
            show(AlertDialog.Builder(activity).setTitle(if (apk) "APK 下载链接" else "发布页链接").setMessage(message).setPositiveButton("复制链接") { _, _ ->
                runCatching { (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("OpenList TV 更新", url)) }
            }.setNegativeButton("关闭", null).create())
        }
    }
    private fun show(value: AlertDialog) {
        if (activity.isFinishing || activity.isDestroyed) return
        dialog?.dismiss(); dialog = value
        value.setOnDismissListener { if (dialog === value) dialog = null }
        value.show()
    }
    private fun notice(text: String) { if (!activity.isFinishing && !activity.isDestroyed) Toast.makeText(activity, text, Toast.LENGTH_LONG).show() }
    fun addSettings(content: LinearLayout) {
        fun toggle(label: String, checked: Boolean, change: (Boolean) -> Unit) = Switch(activity).apply {
            text = label; textSize = 16f; isChecked = checked; isFocusable = true; minHeight = activity.dp(48); setTextColor(TvUi.foreground)
            id = View.generateViewId(); contentDescription = label
            background = TvUi.focusBackground(false, activity.dp(12).toFloat()); setPadding(activity.dp(12), 0, activity.dp(12), 0)
            setOnCheckedChangeListener { _, value -> change(value) }
        }
        content.addView(toggle("自动检测更新（每次启动）", automatic) { automatic = it })
        content.addView(toggle("参与测试版本", testing) { testing = it })
        content.addView(toggle("使用浏览器下载", browserDownload) { browserDownload = it })
        content.addView(TvUi.text(activity, "开启后点更新只打开 APK 下载链接，不在应用内下载或唤起安装。保存位置由浏览器决定；HTTP/SOCKS5 代理不会带入浏览器，HTTPS 加速前缀会保留。", 13f, TvUi.muted))
        val proxyStatus = TvUi.text(activity, "", 13f, TvUi.muted)
        fun refresh() { proxyStatus.text = runCatching { proxy().let { if (it.address.isEmpty()) "更新代理：未配置（直连）" else "更新代理：${it.mode.label} · ${it.address}" } }.getOrDefault("更新代理配置无效，请修改或清除") }
        refresh()
        content.addView(TvUi.button(activity, "配置更新代理") { proxyDialog { refresh() } }); content.addView(proxyStatus)
        content.addView(TvUi.text(activity, "仅更新检查和安装包下载使用代理，不影响 OpenList。测试版本包含已发布的预发布包，不含草稿。新版本由系统确认安装。", 13f, TvUi.muted))
    }
    private fun proxyDialog(saved: () -> Unit) {
        checkJob?.cancel(); pending = null; pendingMessage = null
        val old = savedProxy()
        val form = TvUi.column(activity).apply { setPadding(activity.dp(20), 0, activity.dp(20), 0) }
        val group = RadioGroup(activity); val ids = UpdateProxyMode.entries.map { View.generateViewId() }
        UpdateProxyMode.entries.forEachIndexed { i, mode -> group.addView(RadioButton(activity).apply { id = ids[i]; text = mode.label; isChecked = mode == old.mode; textSize = 16f }) }
        val input = TvUi.input(activity, "更新代理地址（留空为直连）", old.address).apply { filters = arrayOf(InputFilter.LengthFilter(1024)); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI; isSaveEnabled = false }
        form.addView(group); form.addView(input)
        form.addView(TvUi.text(activity, "网络代理：http://主机:端口 或 socks5://主机:端口，不含账号密码。\n加速前缀：https://加速站/，须同时支持 api.github.com 和 GitHub 附件转发。不关闭 HTTPS 校验，不失败后偷偷直连。", 13f, TvUi.muted))
        val prompt = AlertDialog.Builder(activity).setTitle("仅更新使用的代理").setView(ScrollView(activity).apply { addView(form) }).setPositiveButton("保存", null).setNeutralButton("清除代理") { _, _ -> prefs.edit().remove("proxy.address").apply(); saved(); configurationChanged() }.setNegativeButton("取消", null).create()
        show(prompt)
        prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val value = UpdateProxy(UpdateProxyMode.entries[ids.indexOf(group.checkedRadioButtonId).coerceAtLeast(0)], input.text.toString()).normalized()
                prefs.edit().putString("proxy.mode", value.mode.name).putString("proxy.address", value.address).apply()
                prompt.dismiss(); saved(); configurationChanged()
            } catch (e: UpdateFailure) { input.error = e.message }
        }
    }
    fun background() { checkJob?.cancel(); actionJob?.cancel(); packages.cancel(); pending = null; pendingMessage = null; dialog?.dismiss(); dialog = null }
    suspend fun pauseDownload() { val old = actionJob; old?.cancelAndJoin(); packages.cancelAndJoin() }
}

package io.github.loliwolf.openlisttv

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.loliwolf.openlisttv.data.GithubRelease
import io.github.loliwolf.openlisttv.data.GithubUpdates
import io.github.loliwolf.openlisttv.data.UpdateFailure
import kotlinx.coroutines.*

/** Manual check plus at most one foreground automatic attempt per day; never a service. */
class UpdateController(private val activity: ComponentActivity, private val canPromptAutomatically: () -> Boolean) {
    private val prefs = activity.getSharedPreferences("github-updates", Context.MODE_PRIVATE)
    private val client = GithubUpdates()
    private var job: Job? = null
    private var manualRequest = false
    private var pending: GithubRelease? = null
    private var lastOffered: String? = null
    var automatic: Boolean
        get() = prefs.getBoolean("automatic", true)
        set(value) { prefs.edit().putBoolean("automatic", value).apply(); if (!value) { pending = null; if (!manualRequest) cancel() } }

    fun foreground() {
        if (!automatic || !canPromptAutomatically()) return
        pending?.let { offer(it); return }
        val elapsed = System.currentTimeMillis() - prefs.getLong("lastAttempt", 0)
        if (elapsed < 0 || elapsed >= 24L * 60 * 60 * 1000) check(manual = false)
    }
    fun manual(button: Button) = check(manual = true, button = button)
    private fun check(manual: Boolean, button: Button? = null) {
        if (job?.isActive == true) {
            if (manual && !manualRequest) { job?.cancel(); job = null }
            else { if (manual) Toast.makeText(activity, "正在检查更新", Toast.LENGTH_SHORT).show(); return }
        }
        button?.apply { isEnabled = false; text = "正在检查…" }
        manualRequest = manual
        prefs.edit().putLong("lastAttempt", System.currentTimeMillis()).apply()
        job = activity.lifecycleScope.launch {
            try {
                var result = client.check(prefs.getString("etag", null))
                val cached = prefs.getString("release", null)?.let(GithubUpdates::decodeCache)
                if (result.notModified && cached == null) result = client.check()
                val release = if (result.notModified) cached else result.release
                if (!result.notModified) prefs.edit().apply {
                    if (release == null) remove("release") else putString("release", GithubUpdates.encode(release))
                    if (result.etag == null) remove("etag") else putString("etag", result.etag)
                }.apply()
                if (activity.isFinishing || activity.isDestroyed) return@launch
                if (!manual && !automatic) return@launch
                if (release != null && release.newerThan(BuildConfig.VERSION_NAME)) {
                    if (manual && button?.isAttachedToWindow == true) offer(release, manual = true)
                    else if (canPromptAutomatically()) offer(release)
                    else pending = release
                } else if (manual && button?.isAttachedToWindow == true) {
                    AlertDialog.Builder(activity).setTitle("没有可用更新")
                        .setMessage("当前版本：${BuildConfig.VERSION_NAME}\nGitHub 最新稳定版：${release?.version ?: "暂无发布"}")
                        .setPositiveButton("确定", null).show()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (manual && button?.isAttachedToWindow == true && !activity.isFinishing) AlertDialog.Builder(activity).setTitle("检查更新失败")
                    .setMessage((e as? UpdateFailure)?.message ?: "无法连接 GitHub，请稍后重试")
                    .setPositiveButton("确定", null).show()
            } finally { button?.apply { isEnabled = true; text = "检查 GitHub 更新" } }
        }
    }
    private fun offer(release: GithubRelease, manual: Boolean = false) {
        pending = null
        if (activity.isFinishing || activity.isDestroyed) return
        if (!manual && lastOffered == release.version) return
        lastOffered = release.version
        AlertDialog.Builder(activity).setTitle("发现新版本 ${release.version}")
            .setMessage("当前版本：${BuildConfig.VERSION_NAME}\n\n${release.notes.take(1200)}\n\n下载后直接覆盖安装，不要先卸载。")
            .setPositiveButton(if (release.apkUrl != null) "下载 APK" else "查看发布页") { _, _ -> open(release.apkUrl ?: release.pageUrl) }
            .setNeutralButton("版本说明") { _, _ -> open(release.pageUrl) }
            .setNegativeButton("稍后", null).show()
    }
    private fun open(url: String) {
        try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: ActivityNotFoundException) { copyLink(url) }
        catch (_: SecurityException) { copyLink(url) }
    }
    private fun copyLink(url: String) {
            AlertDialog.Builder(activity).setTitle("请在手机或电脑下载")
                .setMessage("此设备没有可打开链接的浏览器。下载 APK 后传到设备覆盖安装。\n\n$url")
                .setPositiveButton("复制链接") { _, _ -> runCatching { (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("OpenList TV 更新", url)) }.onFailure { Toast.makeText(activity, "设备不允许复制链接", Toast.LENGTH_SHORT).show() } }
                .setNegativeButton("关闭", null).show()
    }
    fun cancel() { job?.cancel(); job = null }
}

package io.github.loliwolf.openlisttv

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import io.github.loliwolf.openlisttv.core.AppVersion
import io.github.loliwolf.openlisttv.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/** Private staging/ready files only. This directory is separate from explicit user downloads. */
class UpdatePackages(private val context: Context, private val directory: File = File(context.filesDir, "updates")) {
    private val prefs = context.getSharedPreferences("github-updates", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    @Volatile private var owner: Job? = null
    val busy get() = owner != null
    fun cancel() { owner?.cancel() }
    suspend fun cancelAndJoin() { val old = owner; old?.cancelAndJoin() }
    private val readyDirectory get() = File(directory, "ready")

    suspend fun cached(): Pair<GithubRelease, File>? = withContext(Dispatchers.IO) {
        try {
        val release = prefs.getString("ready.release", null)?.let { GithubUpdates.decodeCache(it, true) } ?: return@withContext null
        val name = prefs.getString("ready.file", "").orEmpty()
        if (!name.matches(Regex("[0-9a-f-]{36}\\.apk"))) return@withContext null
        val file = File(readyDirectory, name)
        if (!file.isFile || file.length() !in 1..GithubUpdates.MAX_APK) return@withContext null
        if (digest(file) != prefs.getString("ready.sha256", null)) return@withContext null
        verify(file, release); release to file
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    suspend fun obtain(release: GithubRelease, network: UpdateNetwork, progress: (Long, Long) -> Unit = { _, _ -> }): File = mutex.withLock {
        owner = currentCoroutineContext()[Job]
        var partial: File? = null
        try {
            if (GithubUpdates.decodeCache(GithubUpdates.encode(release), true) != release || release.apkUrl == null) throw UpdateFailure("更新包地址无效")
            cached()?.takeIf { it.first == release }?.let { return@withLock it.second }
            if (release.size > GithubUpdates.MAX_APK || release.size == 0L) throw UpdateFailure("更新包超过 64 MiB 安全预算或为空")
            withContext(Dispatchers.IO) {
                val staging = File(directory, "staging")
                if ((!staging.isDirectory && !staging.mkdirs()) || (!readyDirectory.isDirectory && !readyDirectory.mkdirs())) throw UpdateFailure("无法创建更新缓存，请检查存储空间")
                staging.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
                partial = File.createTempFile("update-", ".part", staging)
            }
            val target = checkNotNull(partial)
            val hash = network.use(checkNotNull(release.apkUrl).toHttpUrl()) { response ->
                if (response.code != 200) throw UpdateFailure("更新包下载失败（${response.code}）")
                if (response.header("Content-Encoding").orEmpty().let { it.isNotEmpty() && !it.equals("identity", true) }) throw UpdateFailure("更新代理返回了不支持的压缩响应")
                val length = response.body.contentLength()
                if (length > GithubUpdates.MAX_APK || length == 0L || release.size > 0 && length >= 0 && length != release.size) throw UpdateFailure("更新包长度与发布信息不一致或超过预算")
                val expected = if (release.size > 0) release.size else length
                requireSpace(target, expected.coerceAtLeast(0))
                val hash = MessageDigest.getInstance("SHA-256")
                val job = currentCoroutineContext()
                var total = 0L; var last = 0L; var checkAt = 0L
                response.body.byteStream().use { input -> target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        job.ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        if (n.toLong() > GithubUpdates.MAX_APK - total || expected >= 0 && n.toLong() > expected - total) throw UpdateFailure("更新包长度超出发布信息或 64 MiB 预算")
                        if (total >= checkAt) { requireSpace(target, 2L * 1024 * 1024); checkAt = total + 1024 * 1024 }
                        output.write(buffer, 0, n); hash.update(buffer, 0, n); total += n
                        val now = System.nanoTime()
                        if (now - last > 500_000_000L) { progress(total, expected); last = now }
                    }
                    output.fd.sync()
                } }
                job.ensureActive()
                if (total == 0L || expected >= 0 && total != expected) throw UpdateFailure("更新包未下载完整")
                progress(total, total)
                hex(hash.digest())
            }
            if (release.sha256 != null && release.sha256 != hash) throw UpdateFailure("更新包 SHA-256 与发布信息不一致，已拒绝安装")
            withContext(Dispatchers.IO) {
                ensureActive(); verify(target, release)
                val ready = File(readyDirectory, "${UUID.randomUUID()}.apk")
                if (!target.renameTo(ready)) throw UpdateFailure("无法保存已下载更新包")
                if (!prefs.edit().putString("ready.release", GithubUpdates.encode(release)).putString("ready.file", ready.name).putString("ready.sha256", hash).commit()) throw UpdateFailure("无法保存更新状态")
                readyDirectory.listFiles()?.filter { it != ready && it.name.endsWith(".apk") }?.forEach { it.delete() }
                ready
            }
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) { partial?.delete() }
                owner = null
            }
        }
    }
    @Suppress("DEPRECATION")
    fun verify(file: File, release: GithubRelease) {
        // Bound archive metadata before handing untrusted bytes to the platform parser.
        ZipFile(file).use { zip ->
            var count = 0; var total = 0L; val names = HashSet<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement(); count++
                if (count > 4096 || e.size < 0 || e.size > 256L * 1024 * 1024 - total || !names.add(e.name)) throw UpdateFailure("更新包归档结构超出安全预算")
                total += e.size
            }
            if ((zip.getEntry("AndroidManifest.xml")?.size ?: 0) !in 1..1024 * 1024L || (zip.getEntry("resources.arsc")?.size ?: 0) > 32L * 1024 * 1024) throw UpdateFailure("更新包清单或资源表异常")
        }
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: throw UpdateFailure("下载文件不是可识别的 APK")
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (archive.packageName != context.packageName || archive.versionName != release.version) throw UpdateFailure("更新包包名或版本与发布信息不一致")
        if (code(archive) <= code(installed) || AppVersion.parse(archive.versionName.orEmpty())?.let { it > (AppVersion.parse(installed.versionName.orEmpty()) ?: throw UpdateFailure("当前版本无效")) } != true) throw UpdateFailure("更新包不是更高版本，不会降级或覆盖同版本")
        if (archive.applicationInfo?.flags?.and(ApplicationInfo.FLAG_DEBUGGABLE) != 0) throw UpdateFailure("拒绝调试版安装包")
        if (certificates(archive) != setOf(CERTIFICATE) || certificates(installed) != setOf(CERTIFICATE)) throw UpdateFailure("更新包签名信息不匹配，不能覆盖当前应用；请勿卸载或清数据")
        // PackageManager exposes archive signing identity. The system installer performs
        // the final cryptographic APK verification and asks the user to approve installation.
    }
    private fun requireSpace(file: File, needed: Long) { if (file.parentFile!!.usableSpace - 64L * 1024 * 1024 < needed) throw UpdateFailure("更新缓存空间不足，请至少保留 64 MiB 空闲空间") }
    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) } }
        return hex(hash.digest())
    }
    @Suppress("DEPRECATION") private fun certificates(info: PackageInfo): Set<String> = (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures).orEmpty().map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
    @Suppress("DEPRECATION") private fun code(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    companion object {
        const val CERTIFICATE = "57107a591edf888d84666852fd87ce5a27156949d90b6725bddbec33ef5c3079"
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

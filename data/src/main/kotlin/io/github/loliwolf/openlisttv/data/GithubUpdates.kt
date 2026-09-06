package io.github.loliwolf.openlisttv.data

import io.github.loliwolf.openlisttv.core.AppVersion
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class GithubRelease(val version: String, val pageUrl: String, val apkUrl: String?, val notes: String) {
    fun newerThan(installed: String): Boolean = AppVersion.parse(installed)?.let { current -> AppVersion.parse(version)?.let { it > current } } ?: false
}
data class GithubCheck(val release: GithubRelease?, val etag: String?, val notModified: Boolean = false)
class UpdateFailure(message: String) : IOException(message)

class GithubUpdates(
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).followRedirects(false).retryOnConnectionFailure(false).build(),
    private val endpoint: HttpUrl = "https://api.github.com/repos/LoliWolf/OpenListTV/releases/latest".toHttpUrl(),
) {
    suspend fun check(etag: String? = null): GithubCheck = suspendCancellableCoroutine { continuation ->
        val builder = Request.Builder().url(endpoint).header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "OpenListTV-Android")
        etag?.takeIf { it.length <= 256 && '\n' !in it && '\r' !in it }?.let { builder.header("If-None-Match", it) }
        val call = client.newCall(builder.build()); continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(UpdateFailure("无法连接 GitHub，请检查网络后重试")) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        when (it.code) {
                            304 -> GithubCheck(null, etag, true)
                            404 -> GithubCheck(null, null)
                            403, 429 -> throw UpdateFailure("GitHub 暂时限制请求，请稍后再试")
                            200 -> {
                                if (it.body.contentLength() > MAX_RESPONSE) throw UpdateFailure("GitHub 响应超过安全大小限制")
                                val source = it.body.source(); source.request(MAX_RESPONSE + 1)
                                if (source.buffer.size > MAX_RESPONSE) throw UpdateFailure("GitHub 响应超过安全大小限制")
                                GithubCheck(parse(source.readUtf8()), it.header("ETag")?.take(256))
                            }
                            else -> throw UpdateFailure("GitHub 检查失败（${it.code}），请稍后重试")
                        }
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(if (e is UpdateFailure) e else UpdateFailure("无法读取 GitHub 版本信息")) }
            }
        })
    }
    companion object {
        private const val MAX_RESPONSE = 1024L * 1024
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        @Serializable private data class Asset(val name: String = "", val browser_download_url: String = "", val state: String = "uploaded")
        @Serializable private data class ReleaseDto(val tag_name: String, val draft: Boolean = false, val prerelease: Boolean = false, val html_url: String, val body: String? = null, val assets: List<Asset> = emptyList())
        fun parse(raw: String): GithubRelease? {
            val dto = try { json.decodeFromString<ReleaseDto>(raw) } catch (_: Exception) { throw UpdateFailure("GitHub 版本信息格式无效") }
            val version = AppVersion.parse(dto.tag_name) ?: throw UpdateFailure("GitHub 版本号格式无效")
            if (dto.draft || dto.prerelease || version.prerelease.isNotEmpty()) return null
            val tag = dto.tag_name
            val page = trustedUrl(dto.html_url, listOf("LoliWolf", "OpenListTV", "releases", "tag", tag)) ?: throw UpdateFailure("GitHub 返回了非官方发布地址")
            val expected = "OpenListTV-${tag.removePrefix("v")}.apk"
            val apk = dto.assets.firstOrNull { it.name == expected && it.state == "uploaded" }?.let { trustedUrl(it.browser_download_url, listOf("LoliWolf", "OpenListTV", "releases", "download", tag, expected)) }
            return GithubRelease(tag.removePrefix("v"), page, apk, dto.body.orEmpty().take(4000))
        }
        fun encode(release: GithubRelease) = json.encodeToString(release)
        fun decodeCache(raw: String): GithubRelease? = runCatching {
            val value = json.decodeFromString<GithubRelease>(raw)
            val version = AppVersion.parse(value.version) ?: return null
            if (version.prerelease.isNotEmpty()) return null
            val page = value.pageUrl.toHttpUrlOrNull() ?: return null
            val tag = page.pathSegments.lastOrNull() ?: return null
            if (AppVersion.parse(tag) != version || trustedUrl(value.pageUrl, listOf("LoliWolf", "OpenListTV", "releases", "tag", tag)) == null) return null
            if (value.apkUrl != null && trustedUrl(value.apkUrl, listOf("LoliWolf", "OpenListTV", "releases", "download", tag, "OpenListTV-${tag.removePrefix("v")}.apk")) == null) return null
            value.copy(notes = value.notes.take(4000))
        }.getOrNull()
        private fun trustedUrl(raw: String, path: List<String>): String? {
            val url = raw.toHttpUrlOrNull() ?: return null
            if (!url.isHttps || url.host != "github.com" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
            if (url.pathSegments.size != path.size || url.pathSegments.indices.any { i -> !url.pathSegments[i].equals(path[i], ignoreCase = i < 2) }) return null
            return url.toString()
        }
    }
}

package io.github.loliwolf.openlisttv.data

import io.github.loliwolf.openlisttv.core.AppVersion
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

@Serializable
data class GithubRelease(val version: String, val pageUrl: String, val apkUrl: String?, val notes: String,
    val prerelease: Boolean = false, val size: Long = -1, val sha256: String? = null) {
    fun newerThan(installed: String): Boolean = AppVersion.parse(installed)?.let { current -> AppVersion.parse(version)?.let { it > current } } ?: false
}
class UpdateFailure(message: String) : IOException(message)

class GithubUpdates(private val network: UpdateNetwork = UpdateNetwork()) {
    /** Unlike /latest, the list endpoint includes published previews. Keep one winner. */
    suspend fun check(includePrereleases: Boolean = false): GithubRelease? = withTimeout(60_000) {
        var newest: GithubRelease? = null
        for (page in 1..50) {
            val url = "https://api.github.com/repos/LoliWolf/OpenListTV/releases".toHttpUrl().newBuilder().addQueryParameter("per_page", "30").addQueryParameter("page", page.toString()).build()
            val (releases, hasNext) = network.use(url) { response ->
                when (response.code) {
                    404 -> throw UpdateFailure("更新仓库不可访问，或加速地址不支持 GitHub API")
                    403, 429 -> throw UpdateFailure("GitHub 暂时限制请求，请稍后再试")
                    200 -> {
                        if (response.body.contentLength() > MAX_RESPONSE) throw UpdateFailure("GitHub 响应超过安全大小限制")
                        val source = response.body.source(); source.request(MAX_RESPONSE + 1)
                        if (source.buffer.size > MAX_RESPONSE) throw UpdateFailure("GitHub 响应超过安全大小限制")
                        val list = try { json.decodeFromString<List<ReleaseDto>>(source.readUtf8()) } catch (_: Exception) { throw UpdateFailure("更新响应不是 GitHub 版本列表；请检查代理是否支持 API") }
                        if (list.size > 30) throw UpdateFailure("更新服务未遵守分页限制")
                        list to (list.size == 30 || response.header("Link").orEmpty().contains("rel=\"next\""))
                    }
                    else -> throw UpdateFailure("GitHub 检查失败（${response.code}）")
                }
            }
            for (dto in releases) {
                val release = candidate(dto, includePrereleases) ?: continue
                if (release.apkUrl != null && (newest == null || release.newerThan(newest.version))) newest = release
            }
            if (!hasNext) return@withTimeout newest
        }
        throw UpdateFailure("发布记录超出检查上限，请到 GitHub 查看；未判定为最新版本")
    }
    companion object {
        const val MAX_APK = 64L * 1024 * 1024
        private const val MAX_RESPONSE = 2L * 1024 * 1024
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        @Serializable private data class Asset(val name: String = "", val browser_download_url: String = "", val state: String = "uploaded", val size: Long = -1, val digest: String? = null)
        @Serializable private data class ReleaseDto(val tag_name: String = "", val draft: Boolean = false, val prerelease: Boolean = false, val html_url: String = "", val body: String? = null, val assets: List<Asset> = emptyList())
        fun parse(raw: String, includePrereleases: Boolean = false): GithubRelease? = candidate(try { json.decodeFromString<ReleaseDto>(raw) } catch (_: Exception) { throw UpdateFailure("GitHub 版本信息格式无效") }, includePrereleases)
        private fun candidate(dto: ReleaseDto, include: Boolean): GithubRelease? {
            val version = AppVersion.parse(dto.tag_name) ?: return null
            val preview = dto.prerelease || version.prerelease.isNotEmpty()
            if (dto.draft || preview && !include) return null
            val tag = dto.tag_name
            val page = trustedUrl(dto.html_url, listOf("LoliWolf", "OpenListTV", "releases", "tag", tag)) ?: throw UpdateFailure("GitHub 返回了非官方发布地址")
            val expected = "OpenListTV-${tag.removePrefix("v")}.apk"
            val asset = dto.assets.firstOrNull { it.name == expected && it.state == "uploaded" }
            val apk = asset?.let { trustedUrl(it.browser_download_url, listOf("LoliWolf", "OpenListTV", "releases", "download", tag, expected)) }
            val digest = asset?.digest?.let { if (!it.matches(Regex("sha256:[0-9a-fA-F]{64}"))) throw UpdateFailure("更新包摘要格式不支持"); it.removePrefix("sha256:").lowercase() }
            return GithubRelease(tag.removePrefix("v"), page, apk, dto.body.orEmpty().take(4000), preview, asset?.size ?: -1, digest)
        }
        fun encode(release: GithubRelease) = json.encodeToString(release)
        fun decodeCache(raw: String, includePrereleases: Boolean = false): GithubRelease? = runCatching {
            val value = json.decodeFromString<GithubRelease>(raw)
            val version = AppVersion.parse(value.version) ?: return null
            if (!includePrereleases && (value.prerelease || version.prerelease.isNotEmpty())) return null
            val tag = value.pageUrl.toHttpUrlOrNull()?.pathSegments?.lastOrNull() ?: return null
            if (AppVersion.parse(tag) != version || trustedUrl(value.pageUrl, listOf("LoliWolf", "OpenListTV", "releases", "tag", tag)) == null) return null
            if (value.apkUrl != null && trustedUrl(value.apkUrl, listOf("LoliWolf", "OpenListTV", "releases", "download", tag, "OpenListTV-${tag.removePrefix("v")}.apk")) == null) return null
            if (value.sha256 != null && !value.sha256.matches(Regex("[0-9a-f]{64}"))) return null
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

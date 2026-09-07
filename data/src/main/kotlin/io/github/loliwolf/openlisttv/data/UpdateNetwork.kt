package io.github.loliwolf.openlisttv.data

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

enum class UpdateProxyMode(val label: String) { NETWORK("HTTP / SOCKS5 网络代理"), PREFIX("GitHub 加速前缀（HTTPS）") }
data class UpdateProxy(val mode: UpdateProxyMode = UpdateProxyMode.NETWORK, val address: String = "") {
    fun normalized(): UpdateProxy {
        val raw = address.trim()
        if (raw.isEmpty()) return copy(address = "")
        if (raw.length > 1024) throw UpdateFailure("代理地址过长")
        if (mode == UpdateProxyMode.PREFIX) {
            val url = raw.toHttpUrlOrNull() ?: throw UpdateFailure("请输入完整 HTTPS 加速前缀")
            if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) throw UpdateFailure("加速前缀须为 HTTPS，不能包含账号、密码、查询参数或锚点")
            return copy(address = url.toString().trimEnd('/') + "/")
        }
        val uri = runCatching { URI(raw) }.getOrNull() ?: throw UpdateFailure("代理格式应为 http://主机:端口 或 socks5://主机:端口")
        if (uri.scheme?.lowercase() !in listOf("http", "socks5") || uri.host.isNullOrEmpty() || uri.port !in 1..65535 || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || uri.rawPath.orEmpty() !in listOf("", "/")) throw UpdateFailure("网络代理须为 http://主机:端口 或 socks5://主机:端口；不支持账号密码或路径")
        return copy(address = raw.trimEnd('/'))
    }
    fun javaProxy(): Proxy {
        if (address.isEmpty() || mode == UpdateProxyMode.PREFIX) return Proxy.NO_PROXY
        val uri = URI(address)
        return Proxy(if (uri.scheme.equals("socks5", true)) Proxy.Type.SOCKS else Proxy.Type.HTTP, InetSocketAddress(uri.host, uri.port))
    }
    fun route(url: HttpUrl): HttpUrl = if (mode == UpdateProxyMode.PREFIX && address.isNotEmpty()) (address + url).toHttpUrl() else url
}

/** Isolated from OpenList/media clients. Never changes ProxySelector or system properties. */
class UpdateNetwork(proxy: UpdateProxy = UpdateProxy(), private val mapForTest: ((HttpUrl) -> HttpUrl)? = null) {
    private val proxy = proxy.normalized()
    private val client by lazy {
        OkHttpClient.Builder().proxy(this.proxy.javaProxy()).cookieJar(CookieJar.NO_COOKIES)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectionPool(ConnectionPool(2, 30, TimeUnit.SECONDS)).build()
    }
    private sealed interface Step<out T> { data class Next(val url: HttpUrl) : Step<Nothing>; data class Done<T>(val value: T) : Step<T> }
    suspend fun <T> use(url: HttpUrl, consume: suspend (Response) -> T): T {
        var logical = url
        repeat(6) {
            requireDestination(logical)
            val routed = proxy.route(logical)
            val physical = mapForTest?.invoke(routed) ?: routed
            val step = exchange(Request.Builder().url(physical).header("User-Agent", "OpenListTV-Android")
                .header("Accept-Encoding", "identity").header("Accept", if (logical.host == "api.github.com") "application/vnd.github+json" else "application/octet-stream")
                .header("X-GitHub-Api-Version", "2022-11-28").build()) { response ->
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    val location = response.header("Location") ?: throw UpdateFailure("更新服务重定向地址缺失")
                    val next = if (proxy.mode == UpdateProxyMode.PREFIX && proxy.address.isNotEmpty() && location.startsWith(proxy.address)) location.removePrefix(proxy.address).toHttpUrlOrNull() else logical.resolve(location)
                    Step.Next(next ?: throw UpdateFailure("更新服务返回无效重定向"))
                } else Step.Done(consume(response))
            }
            when (step) { is Step.Done -> return step.value; is Step.Next -> logical = step.url }
        }
        throw UpdateFailure("更新服务重定向过多")
    }
    private suspend fun <T> exchange(request: Request, consume: suspend (Response) -> T): T = withContext(Dispatchers.IO) { coroutineScope {
        val call = client.newCall(request)
        val worker = async(Dispatchers.IO) {
            try { call.execute().use { consume(it) } }
            catch (e: Exception) { currentCoroutineContext().ensureActive(); throw e }
        }
        try { worker.await() }
        finally { call.cancel(); withContext(NonCancellable) { worker.join() } }
    } }
    companion object {
        fun requireDestination(url: HttpUrl) {
            if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) throw UpdateFailure("拒绝非 HTTPS 官方更新地址")
            val p = url.pathSegments
            val allowed = when (url.host) {
                "api.github.com" -> p == listOf("repos", "LoliWolf", "OpenListTV", "releases")
                "github.com" -> p.size == 6 && p[0].equals("LoliWolf", true) && p[1].equals("OpenListTV", true) && p[2] == "releases" && p[3] == "download"
                "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com" -> true
                else -> false
            }
            if (!allowed) throw UpdateFailure("更新服务返回非官方地址，已停止")
        }
    }
}

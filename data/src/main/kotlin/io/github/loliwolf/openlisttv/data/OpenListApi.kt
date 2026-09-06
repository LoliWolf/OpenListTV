package io.github.loliwolf.openlisttv.data

import io.github.loliwolf.openlisttv.core.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ServerAddress {
    fun normalize(input: String, allowHttp: Boolean): String {
        val url = input.trim().toHttpUrlOrNull() ?: throw ClientFailure(FailureKind.INVALID, "请输入完整的 HTTP(S) 服务地址")
        requireSafe(url, allowHttp)
        if (url.query != null || url.fragment != null) throw ClientFailure(FailureKind.INVALID, "服务地址不能包含查询参数或锚点")
        return url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/").build().toString()
    }
    fun requireSafe(url: HttpUrl, allowHttp: Boolean) {
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) throw ClientFailure(FailureKind.INVALID, "地址中不能包含用户名或密码")
        if (!url.isHttps && !allowHttp) throw ClientFailure(FailureKind.INVALID, "此站点未允许 HTTP 明文传输，请使用 HTTPS 或明确允许 HTTP")
    }
}

object HttpClients {
    private val pool = ConnectionPool(3, 2, TimeUnit.MINUTES)
    fun api(): OkHttpClient = OkHttpClient.Builder().connectionPool(pool)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).followRedirects(false).retryOnConnectionFailure(true).build()
    fun media(allowHttp: Boolean): OkHttpClient = OkHttpClient.Builder().connectionPool(pool)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            try { ServerAddress.requireSafe(request.url, allowHttp) } catch (e: ClientFailure) { throw IOException(e.message) }
            // This client never receives a main-site token. Strip secrets on every redirect hop too.
            chain.proceed(request.newBuilder().removeHeader("Authorization").removeHeader("Cookie").removeHeader("Proxy-Authorization").build())
        }.build()
}

class OpenListApi(private val client: OkHttpClient = HttpClients.api()) : AuthProvider, FileGateway {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true; encodeDefaults = true }
    @Serializable private data class Envelope(val code: Int, val message: String = "", val data: JsonElement? = null)
    @Serializable private data class LoginBody(val username: String, val password: String)
    @Serializable private data class Token(val token: String)
    @Serializable private data class Me(val username: String = "", val base_path: String = "/", val disabled: Boolean = false)
    @Serializable private data class ListBody(val path: String, val password: String, val page: Int, val per_page: Int, val refresh: Boolean = false)
    @Serializable private data class GetBody(val path: String, val password: String)
    @Serializable private data class FileDto(val name: String, val size: Long = -1, val is_dir: Boolean = false, val modified: String = "", val sign: String = "", val thumb: String = "")
    @Serializable private data class ListDto(val content: List<FileDto>? = null, val total: Int = 0)
    @Serializable private data class GetDto(val raw_url: String = "", val related: List<FileDto>? = null)

    override suspend fun login(site: Site, password: String): Session {
        val body = json.encodeToString(LoginBody(site.username, OpenListPassword.hash(password)))
        val token = json.decodeFromJsonElement<Token>(request(site, "auth/login/hash", body, null)).token
        if (token.isBlank()) throw ClientFailure(FailureKind.SERVER, "服务端没有返回有效会话")
        return validate(Session(site, token))
    }
    override suspend fun validate(session: Session): Session {
        val me = json.decodeFromJsonElement<Me>(request(session.site, "me", null, session.token))
        if (me.disabled || me.username != session.site.username) throw ClientFailure(FailureKind.AUTH, "会话已失效，请重新登录")
        return session.copy(basePath = me.base_path)
    }
    override suspend fun logout(session: Session) { request(session.site, "auth/logout", null, session.token) }
    override suspend fun list(session: Session, path: String, password: String, page: Int, perPage: Int): Listing {
        require(page > 0 && perPage in 1..Budgets.PAGE)
        val dto = json.decodeFromJsonElement<ListDto>(request(session.site, "fs/list", json.encodeToString(ListBody(path, password, page, perPage)), session.token))
        if (dto.total < 0 || dto.total > Budgets.MAX_ENTRIES || (dto.content?.size ?: 0) > perPage) throw ClientFailure(FailureKind.CAPACITY, "目录超出 10 万条索引限制或服务端未遵守分页")
        val entries = dto.content.orEmpty().mapIndexed { index, f ->
            if (f.name.length > 4096) throw ClientFailure(FailureKind.INVALID, "服务器返回的文件名过长")
            try { Entry(Paths.child(path, f.name), f.name, f.is_dir, f.size, f.modified, f.sign, (page - 1) * perPage + index, f.thumb) }
            catch (_: IllegalArgumentException) { throw ClientFailure(FailureKind.INVALID, "服务器返回了无效路径") }
        }
        return Listing(entries, dto.total)
    }
    override suspend fun resolve(session: Session, entry: Entry, password: String): MediaSource {
        val dto = json.decodeFromJsonElement<GetDto>(request(session.site, "fs/get", json.encodeToString(GetBody(entry.path, password)), session.token))
        val url = dto.raw_url.toHttpUrlOrNull() ?: throw ClientFailure(FailureKind.UNSUPPORTED, "服务端未返回可播放的 HTTP(S) 地址")
        ServerAddress.requireSafe(url, session.site.allowHttp)
        return MediaSource(entry, url.toString())
    }
    private suspend fun request(site: Site, endpoint: String, body: String?, token: String?): JsonElement {
        val base = ServerAddress.normalize(site.url, site.allowHttp).toHttpUrlOrNull()!!
        val builder = Request.Builder().url(base.newBuilder().addPathSegments("api/$endpoint").build())
            .header("Accept", "application/json")
        if (token != null) builder.header("Authorization", token)
        if (body != null) builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        val text = client.newCall(builder.build()).readText(Budgets.METADATA_RESPONSE_BYTES)
        val envelope = try { json.decodeFromString<Envelope>(text) } catch (_: Exception) { throw ClientFailure(FailureKind.SERVER, "响应不是兼容的 OpenList JSON，请检查地址与反向代理") }
        if (envelope.code != 200) throw failure(envelope.code)
        return envelope.data ?: kotlinx.serialization.json.JsonNull
    }
    companion object {
        fun failure(code: Int): ClientFailure = when (code) {
            401 -> ClientFailure(FailureKind.AUTH, "账号或密码不正确，或登录已失效")
            402 -> ClientFailure(FailureKind.CHALLENGE, "账号要求双因素验证码；当前版本只支持账号密码登录")
            403 -> ClientFailure(FailureKind.ACCESS, "目录密码不正确或没有访问权限")
            429 -> ClientFailure(FailureKind.RATE_LIMIT, "请求被限流，请稍后再试")
            in 300..399 -> ClientFailure(FailureKind.SERVER, "服务地址发生重定向，请填写最终的服务地址")
            404 -> ClientFailure(FailureKind.SERVER, "资源或接口不存在，请刷新目录")
            else -> ClientFailure(FailureKind.SERVER, "服务器请求失败（$code）")
        }
    }
}

suspend fun Call.readText(limit: Long): String = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(ClientFailure(FailureKind.NETWORK, "连接失败，请检查网络、服务地址与证书", e))
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                response.use {
                    if (!it.isSuccessful) throw OpenListApi.failure(it.code)
                    if (it.body.contentLength() > limit) throw ClientFailure(FailureKind.CAPACITY, "服务器响应超过安全大小限制")
                    val source = it.body.source()
                    source.request(limit + 1)
                    if (source.buffer.size > limit) throw ClientFailure(FailureKind.CAPACITY, "服务器响应超过安全大小限制")
                    val value = source.readUtf8()
                    if (continuation.isActive) continuation.resume(value)
                }
            } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(if (e is ClientFailure) e else ClientFailure(FailureKind.NETWORK, "读取响应失败")) }
        }
    })
}

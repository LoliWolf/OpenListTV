package io.github.loliwolf.openlisttv.data

import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ProxySelector
import java.util.concurrent.TimeUnit

class GithubUpdatesTest {
    private fun release(version: String = "1.0.1", extra: String = "") = """{"tag_name":"v$version","html_url":"https://github.com/LoliWolf/OpenListTV/releases/tag/v$version","assets":[{"name":"OpenListTV-$version.apk","browser_download_url":"https://github.com/LoliWolf/OpenListTV/releases/download/v$version/OpenListTV-$version.apk"}]$extra}"""
    private fun checker(server: MockWebServer) = GithubUpdates(UpdateNetwork(mapForTest = { server.url(it.encodedPath + "?" + it.encodedQuery.orEmpty()) }))
    @Test fun compareSelectOfficialApkAndRoundTripSafeCache() {
        val next = GithubUpdates.parse(release())!!
        assertTrue(next.newerThan("1.0.0")); assertTrue(next.newerThan("1.0.1-preview.1")); assertFalse(next.newerThan("1.0.2"))
        assertEquals(next, GithubUpdates.decodeCache(GithubUpdates.encode(next)))
        assertNull(GithubUpdates.parse(release(extra = ",\"draft\":true"), true))
        assertNull(GithubUpdates.parse(release(extra = ",\"prerelease\":true")))
        val preview = GithubUpdates.parse(release("1.0.2-preview.1"), true)!!
        assertTrue(preview.prerelease); assertNull(GithubUpdates.decodeCache(GithubUpdates.encode(preview)))
        assertEquals(preview, GithubUpdates.decodeCache(GithubUpdates.encode(preview), true))
    }
    @Test fun paginatesAndChoosesSemverRatherThanListOrder() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[${release("1.0.9")},${release("2.0.0-preview.1")},${release("9.9.9", ",\"draft\":true")}]").setHeader("Link", "<https://example.invalid/never-follow-this>; rel=\"next\""))
            server.enqueue(MockResponse().setBody("[${release("1.0.10")} ]"))
            assertEquals("1.0.10", checker(server).check()!!.version)
            assertTrue(server.takeRequest().path!!.contains("page=1")); assertTrue(server.takeRequest().path!!.contains("page=2"))
            server.enqueue(MockResponse().setBody("[${release("1.0.10")},${release("2.0.0-preview.1")},${release("9.9.9", ",\"draft\":true")} ]"))
            assertEquals("2.0.0-preview.1", checker(server).check(true)!!.version)
        }
    }
    @Test fun rejectForeignLinksAndSkipMissingApks() = runBlocking<Unit> {
        assertNull(GithubUpdates.parse(release().replace("https://github.com/LoliWolf/OpenListTV/releases/download", "https://evil.example/releases/download"))!!.apkUrl)
        assertThrows(UpdateFailure::class.java) { GithubUpdates.parse(release().replace("https://github.com", "http://github.com")) }
        assertNull(GithubUpdates.decodeCache("""{"version":"1.0.1","pageUrl":"https://evil.example/","apkUrl":null,"notes":""}"""))
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[${release("9.0.0").replace("OpenListTV-9.0.0.apk", "sources.zip")},${release()}]"))
            assertEquals("1.0.1", checker(server).check()!!.version)
        }
    }
    @Test fun eachCheckActuallyFetchesAndSendsNoCredentials() = runBlocking<Unit> {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("[${release()}]")) }
            val checker = checker(server)
            repeat(2) {
                assertNotNull(checker.check())
                val request = server.takeRequest()
                for (header in listOf("Authorization", "Cookie", "Proxy-Authorization", "If-None-Match")) assertNull(request.getHeader(header))
            }
            server.enqueue(MockResponse().setResponseCode(403))
            try { checker.check(); fail() } catch (e: UpdateFailure) { assertTrue(e.message!!.contains("限制请求")) }
        }
    }
    @Test fun emptyAndProxyErrorsAreDistinct() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[]")); assertNull(checker(server).check())
            for (response in listOf(MockResponse().setResponseCode(404), MockResponse().setBody("<html>proxy unsupported</html>"))) {
                server.enqueue(response); try { checker(server).check(); fail() } catch (_: UpdateFailure) { }
            }
        }
    }
    @Test fun proxySettingsAreExplicitAndDoNotChangeGlobalProxySelector() {
        val global = ProxySelector.getDefault()
        assertEquals("http://127.0.0.1:7890", UpdateProxy(address = " http://127.0.0.1:7890/ ").normalized().address)
        assertEquals(java.net.Proxy.Type.SOCKS, UpdateProxy(address = "socks5://127.0.0.1:1080").normalized().javaProxy().type())
        for (address in listOf("http://u:p@localhost:8080", "https://localhost:443", "http://localhost:7890/path", "socks5://localhost", "http://localhost:0")) assertThrows(UpdateFailure::class.java) { UpdateProxy(address = address).normalized() }
        val prefix = UpdateProxy(UpdateProxyMode.PREFIX, "https://proxy.example/relay").normalized()
        assertEquals("https://proxy.example/relay/https://api.github.com/repos/LoliWolf/OpenListTV/releases", prefix.route("https://api.github.com/repos/LoliWolf/OpenListTV/releases".toHttpUrl()).toString())
        assertThrows(UpdateFailure::class.java) { UpdateProxy(UpdateProxyMode.PREFIX, "http://proxy.example").normalized() }
        assertSame(global, ProxySelector.getDefault())
    }
    @Test fun redirectDestinationsStayOfficialAndCancellationJoinsNetwork() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val network = UpdateNetwork(mapForTest = { server.url("/") })
            val asset = "https://github.com/LoliWolf/OpenListTV/releases/download/v1.0.1/OpenListTV-1.0.1.apk".toHttpUrl()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://release-assets.githubusercontent.com/example?signature=fixture"))
            server.enqueue(MockResponse().setBody("data")); assertEquals("data", network.use(asset) { it.body.string() })
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1/private"))
            try { network.use(asset) { it.body.string() }; fail() } catch (_: UpdateFailure) { }
            server.enqueue(MockResponse().setBody("delayed").setBodyDelay(10, TimeUnit.SECONDS))
            val job = launch { network.use(asset) { it.body.string() } }; delay(100)
            withTimeout(2000) { job.cancelAndJoin() }
        }
    }
    @Test fun prefixRoutesApiAndEachCdnHopWithoutDirectFallback() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val proxy = UpdateProxy(UpdateProxyMode.PREFIX, "https://proxy.example/")
            val network = UpdateNetwork(proxy, mapForTest = { server.url(it.encodedPath + "?" + it.encodedQuery.orEmpty()) })
            server.enqueue(MockResponse().setBody("[]")); assertNull(GithubUpdates(network).check())
            assertTrue(server.takeRequest().path!!.startsWith("/https://api.github.com/repos/LoliWolf/OpenListTV/releases"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://release-assets.githubusercontent.com/test?sig=fixture"))
            server.enqueue(MockResponse().setBody("apk"))
            network.use("https://github.com/LoliWolf/OpenListTV/releases/download/v1.0.1/OpenListTV-1.0.1.apk".toHttpUrl()) { assertEquals("apk", it.body.string()) }
            assertTrue(server.takeRequest().path!!.startsWith("/https://github.com/")); assertTrue(server.takeRequest().path!!.startsWith("/https://release-assets.githubusercontent.com/"))
        }
    }
    @Test fun networkProxyUsesConnectAndDoesNotFallBackWhenItRefuses() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(407).setHeader("Proxy-Authenticate", "Basic realm=fixture"))
            val proxy = UpdateProxy(address = "http://127.0.0.1:${server.port}")
            try { GithubUpdates(UpdateNetwork(proxy)).check(); fail() } catch (_: java.io.IOException) { }
            val request = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("CONNECT", request.method); assertEquals("api.github.com:443", request.path)
            assertNull(request.getHeader("Authorization")); assertEquals(1, server.requestCount)
        }
    }
}

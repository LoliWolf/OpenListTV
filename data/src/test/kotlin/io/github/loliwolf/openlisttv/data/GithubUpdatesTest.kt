package io.github.loliwolf.openlisttv.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class GithubUpdatesTest {
    private fun release(version: String = "1.0.1", extra: String = "") = """{"tag_name":"v$version","html_url":"https://github.com/LoliWolf/OpenListTV/releases/tag/v$version","assets":[{"name":"OpenListTV-$version.apk","browser_download_url":"https://github.com/LoliWolf/OpenListTV/releases/download/v$version/OpenListTV-$version.apk"}]$extra}"""
    @Test fun compareSelectOfficialApkAndRoundTripSafeCache() {
        val next = GithubUpdates.parse(release())!!
        assertTrue(next.newerThan("1.0.0")); assertTrue(next.newerThan("1.0.1-preview.1")); assertFalse(next.newerThan("1.0.2"))
        assertTrue(next.apkUrl!!.endsWith("OpenListTV-1.0.1.apk")); assertEquals(next, GithubUpdates.decodeCache(GithubUpdates.encode(next)))
        assertNull(GithubUpdates.parse(release(extra = ",\"draft\":true")))
        assertNull(GithubUpdates.parse(release(extra = ",\"prerelease\":true")))
        assertNull(GithubUpdates.parse(release("1.0.2-preview.1")))
    }
    @Test fun rejectForeignOrInsecureDownloadLinks() {
        assertNull(GithubUpdates.parse(release().replace("https://github.com/LoliWolf/OpenListTV/releases/download", "https://evil.example/releases/download"))!!.apkUrl)
        try { GithubUpdates.parse(release().replace("https://github.com", "http://github.com")); fail() } catch (_: UpdateFailure) { }
        assertNull(GithubUpdates.decodeCache("""{"version":"1.0.1","pageUrl":"https://evil.example/","apkUrl":null,"notes":""}"""))
    }
    @Test fun etagAndRateLimitUseIndependentUnauthenticatedRequests() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(release()).addHeader("ETag", "\"release-v1\""))
            server.enqueue(MockResponse().setResponseCode(304))
            server.enqueue(MockResponse().setResponseCode(403))
            val checker = GithubUpdates(endpoint = server.url("/latest"))
            val first = checker.check(); assertEquals("\"release-v1\"", first.etag)
            val request = server.takeRequest(); assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
            assertTrue(checker.check(first.etag).notModified); assertEquals(first.etag, server.takeRequest().getHeader("If-None-Match"))
            try { checker.check(); fail() } catch (e: UpdateFailure) { assertTrue(e.message!!.contains("限制请求")) }
        }
    }
    @Test fun missingReleaseAndMissingApkAreNotUnsafeDownloads() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404)); assertNull(GithubUpdates(endpoint = server.url("/latest")).check().release)
        }
        val noApk = GithubUpdates.parse(release().replace("OpenListTV-1.0.1.apk", "sources.zip"))!!
        assertNull(noApk.apkUrl)
    }
}

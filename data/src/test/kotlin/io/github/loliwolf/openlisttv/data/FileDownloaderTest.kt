package io.github.loliwolf.openlisttv.data

import io.github.loliwolf.openlisttv.core.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class FileDownloaderTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: MockWebServer
    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }
    private fun downloader() = FileDownloader(HttpClients.media(true)) { Long.MAX_VALUE }
    private fun resume(tag: String = "\"one\"", total: Long = 6) = FileDownloader.Resume(tag, total, FileDownloader.sourceKey(server.url("/file").toString()))
    private suspend fun failure(block: suspend () -> Unit): ClientFailure = try { block(); throw AssertionError("Expected ClientFailure") } catch (e: ClientFailure) { e }

    @Test fun savesChunkedInputWithoutNeedingWholeFileLength() = runBlocking<Unit> {
        val file = temp.newFile(); val payload = "abcdef".repeat(12000)
        server.enqueue(MockResponse().setChunkedBody(payload, 1007).setHeader("ETag", "\"one\""))
        var meta = ""; var finished = -1L
        assertEquals(payload.length.toLong(), downloader().download(server.url("/file").toString(), file, metadata = { total, etag, _ -> assertEquals(-1L, total); meta = etag }, progress = { n, _ -> finished = n }))
        assertEquals(payload, file.readText()); assertEquals("\"one\"", meta); assertEquals(file.length(), finished)
    }
    @Test fun resumesOnlyWithMatchingStrongEtagAndRange() = runBlocking<Unit> {
        val file = temp.newFile().apply { writeText("abc") }
        server.enqueue(MockResponse().setResponseCode(206).setBody("def").setHeader("Content-Range", "bytes 3-5/6").setHeader("ETag", "\"one\""))
        downloader().download(server.url("/file").toString(), file, resume())
        assertEquals("abcdef", file.readText())
        val request = server.takeRequest(); assertEquals("bytes=3-", request.getHeader("Range")); assertEquals("\"one\"", request.getHeader("If-Range")); assertEquals("identity", request.getHeader("Accept-Encoding"))
    }
    @Test fun ignoredRangeRestartsRatherThanAppending() = runBlocking<Unit> {
        val file = temp.newFile().apply { writeText("old") }
        server.enqueue(MockResponse().setBody("new content").setHeader("ETag", "\"two\""))
        downloader().download(server.url("/file").toString(), file, resume(total = 8))
        assertEquals("new content", file.readText())
    }
    @Test fun restartingReclaimsItsOwnPartialBeforeCheckingReplacementSpace() = runBlocking<Unit> {
        val file = temp.newFile().apply { writeText("old") }
        server.enqueue(MockResponse().setBody("fresh"))
        FileDownloader(HttpClients.media(true)) { target -> FileDownloader.RESERVE_BYTES + 5 - target.length() }
            .download(server.url("/file").toString(), file)
        assertEquals("fresh", file.readText())
    }
    @Test fun changedValidatorWrongRangeAnd416AllRestartSafely() = runBlocking<Unit> {
        for (response in listOf(
            MockResponse().setResponseCode(206).setBody("def").setHeader("Content-Range", "bytes 3-5/6").setHeader("ETag", "\"two\""),
            MockResponse().setResponseCode(206).setBody("def").setHeader("Content-Range", "bytes 2-4/6").setHeader("ETag", "\"one\""),
            MockResponse().setResponseCode(416))) {
            val file = temp.newFile().apply { writeText("abc") }
            server.enqueue(response); server.enqueue(MockResponse().setBody("replacement"))
            downloader().download(server.url("/file").toString(), file, resume())
            assertEquals("replacement", file.readText())
            assertNotNull(server.takeRequest().getHeader("Range")); assertNull(server.takeRequest().getHeader("Range"))
        }
    }
    @Test fun weakOrMissingValidatorNeverRequestsAppend() = runBlocking<Unit> {
        for (tag in listOf("", "W/\"one\"")) {
            val file = temp.newFile().apply { writeText("old") }
            server.enqueue(MockResponse().setBody("new"))
            downloader().download(server.url("/file").toString(), file, resume(tag))
            assertEquals("new", file.readText()); assertNull(server.takeRequest().getHeader("Range"))
        }
    }
    @Test fun sameEtagFromDifferentResourceCannotMixPartialBytes() = runBlocking<Unit> {
        val file = temp.newFile().apply { writeText("old") }
        server.enqueue(MockResponse().setResponseCode(206).setBody("new").setHeader("Content-Range", "bytes 3-5/6").setHeader("ETag", "\"one\""))
        server.enqueue(MockResponse().setBody("newnew").setHeader("ETag", "\"one\""))
        downloader().download(server.url("/file").toString(), file, resume().copy(sourceKey = FileDownloader.sourceKey(server.url("/old-resource").toString())))
        assertEquals("newnew", file.readText()); assertNotNull(server.takeRequest().getHeader("Range")); assertNull(server.takeRequest().getHeader("Range"))
    }
    @Test fun capacityAndRateLimitAreReportedWithoutCallingThemComplete() = runBlocking<Unit> {
        val file = temp.newFile()
        server.enqueue(MockResponse().setBody("a"))
        assertEquals(FailureKind.CAPACITY, failure { FileDownloader(HttpClients.media(true)) { FileDownloader.RESERVE_BYTES }.download(server.url("/file").toString(), file) }.kind)
        assertEquals(0, file.length())
        server.enqueue(MockResponse().setResponseCode(429))
        assertEquals(FailureKind.RATE_LIMIT, failure { downloader().download(server.url("/file").toString(), file) }.kind)
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(FailureKind.STALE_LINK, failure { downloader().download(server.url("/file").toString(), file) }.kind)
    }
    @Test fun interruptionClosesAndJoinsWriterBeforeReturning() = runBlocking<Unit> {
        val file = temp.newFile(); val received = CompletableDeferred<Unit>()
        server.enqueue(MockResponse().setBody("x".repeat(256 * 1024)).throttleBody(4096, 50, TimeUnit.MILLISECONDS))
        val job = launch { downloader().download(server.url("/file").toString(), file, progress = { _, _ -> received.complete(Unit) }) }
        withTimeout(5000) { received.await() }
        withTimeout(3000) { job.cancelAndJoin() }
        val stopped = file.length(); assertTrue(stopped in 1 until 256 * 1024)
        delay(120); assertEquals(stopped, file.length())
        server.enqueue(MockResponse().setBody("next"))
        downloader().download(server.url("/file").toString(), file)
        assertEquals("next", file.readText())
    }
    @Test fun fiveGigabyteLengthStaysLongAndTruncatedBodyIsNotComplete() = runBlocking<Unit> {
        val file = temp.newFile(); var length = 0L
        server.enqueue(MockResponse().setBody("partial").setHeader("Content-Length", 5_000_000_000L).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
        val error = withTimeout(3000) { failure { downloader().download(server.url("/file").toString(), file, metadata = { total, _, _ -> length = total }) } }
        assertEquals(5_000_000_000L, length); assertEquals(FailureKind.NETWORK, error.kind); assertTrue(file.length() < length)
    }
    @Test fun redirectHopsDoNotReceiveAccountCredentials() = runBlocking<Unit> {
        MockWebServer().use { other ->
            other.start(); other.enqueue(MockResponse().setBody("safe"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/file")))
            val http = HttpClients.media(true).newBuilder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", "test-secret").header("Cookie", "test-cookie").header("Proxy-Authorization", "test-proxy").build()) }.build()
            FileDownloader(http) { Long.MAX_VALUE }.download(server.url("/file").toString(), temp.newFile())
            for (request in listOf(server.takeRequest(), other.takeRequest())) for (header in listOf("Authorization", "Cookie", "Proxy-Authorization")) assertNull(request.getHeader(header))
        }
    }
    @Test fun invalidRangePlaylistAndUnexpectedPartialAreRejected() = runBlocking<Unit> {
        assertNull(FileDownloader.contentRange("bytes 3-2/6")); assertNull(FileDownloader.contentRange("bytes 0-6/6")); assertNull(FileDownloader.contentRange("bytes 0-3/*"))
        server.enqueue(MockResponse().setResponseCode(206).setBody("abc").setHeader("Content-Range", "bytes 0-2/6"))
        assertEquals(FailureKind.SERVER, failure { downloader().download(server.url("/file").toString(), temp.newFile()) }.kind)
        server.enqueue(MockResponse().setBody("#EXTM3U").setHeader("Content-Type", "application/vnd.apple.mpegurl"))
        assertEquals(FailureKind.UNSUPPORTED, failure { downloader().download(server.url("/file").toString(), temp.newFile()) }.kind)
    }
}

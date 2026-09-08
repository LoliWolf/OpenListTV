package io.github.loliwolf.openlisttv.data

import io.github.loliwolf.openlisttv.core.*
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class OpenListApiTest {
    @Test fun indexingUsesLargerMetadataPagesWithoutLoadingWholeDirectory() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":200,"data":{"content":[{"name":"1.jpg"}],"total":150000}}"""))
            val session = Session(Site("one", "test", server.url("/").toString(), "u", true), "t")
            val page = OpenListApi().list(session, "/", "", 1, Budgets.INDEX_PAGE)
            assertEquals(150_000, page.total); assertEquals(1, page.entries.size)
            assertTrue(server.takeRequest().body.readUtf8().contains("\"per_page\":500"))
        }
    }
    @Test fun oversizedMetadataIsAResponseBudgetNotDiskCapacity() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}").setHeader("Content-Length", Budgets.METADATA_RESPONSE_BYTES + 1))
            val session = Session(Site("one", "test", server.url("/").toString(), "u", true), "t")
            try { OpenListApi().list(session, "/", "", 1, 500); fail("Oversize response accepted") }
            catch (e: ClientFailure) { assertEquals(FailureKind.RESOURCE_LIMIT, e.kind) }
        }
    }
    @Test fun loginUsesHashAndRawTokenWithDeploymentPrefix() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":200,"data":{"token":"test-token"}}"""))
            server.enqueue(MockResponse().setBody("""{"code":200,"data":{"username":"tester","base_path":"/private"}}"""))
            val site = Site("one", "test", server.url("/prefix/").toString(), "tester", true)
            val session = OpenListApi().login(site, "test-password")
            val login = server.takeRequest()
            assertEquals("/prefix/api/auth/login/hash", login.path)
            val body = login.body.readUtf8()
            assertTrue(body.contains("11feb86d591edc1bd608e1fdbba2588ec21f9418cc114b43e7ab838f72b81238"))
            assertFalse(body.contains("test-password"))
            val me = server.takeRequest()
            assertEquals("test-token", me.getHeader("Authorization"))
            assertEquals("/private", session.basePath)
        }
    }
    @Test fun http200BusinessFailureIsNotSuccess() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":403,"message":"private-server-text","data":null}"""))
            val session = Session(Site("one", "test", server.url("/").toString(), "u", true), "t")
            try { OpenListApi().list(session, "/", "", 1, 100); fail() }
            catch (e: ClientFailure) { assertEquals(FailureKind.ACCESS, e.kind); assertFalse(e.message!!.contains("private-server-text")) }
        }
    }
    @Test fun directoriesRemainUserRelativeAndSpecialNamesArePreserved() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":200,"data":{"content":[{"name":"a #+%😀.jpg","is_dir":false,"size":5000000000,"extra":"ignored"}],"total":1}}"""))
            val session = Session(Site("one", "test", server.url("/").toString(), "u", true), "t", "/private")
            val list = OpenListApi().list(session, "/", "", 1, 100)
            assertEquals("/a #+%😀.jpg", list.entries.single().path)
            assertEquals(5_000_000_000, list.entries.single().size)
            val request = server.takeRequest().body.readUtf8()
            assertTrue(request.contains("\"path\":\"/\"")); assertFalse(request.contains("/private"))
        }
    }
    @Test fun mediaRedirectsNeverForwardApiCredentials() {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.enqueue(MockResponse().setResponseCode(302).addHeader("Location", second.url("/video")))
            second.enqueue(MockResponse().setBody("media"))
            HttpClients.media(true).newCall(Request.Builder().url(first.url("/start")).header("Authorization", "api-secret").header("Cookie", "secret-cookie").build()).execute().use { assertEquals("media", it.body.string()) }
            assertNull(first.takeRequest().getHeader("Authorization"))
            val destination = second.takeRequest()
            assertNull(destination.getHeader("Authorization")); assertNull(destination.getHeader("Cookie"))
        } }
    }
    @Test fun thumbnailFieldSupportsUrlMissingAndNullWithoutBreakingTheDirectory() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":200,"data":{"content":[{"name":"1.jpg","thumb":"/preview/1.jpg?sign=sample"},{"name":"2.jpg"},{"name":"3.jpg","thumb":null}],"total":3}}"""))
            val session = Session(Site("one", "test", server.url("/prefix/").toString(), "u", true), "t")
            val entries = OpenListApi().list(session, "/", "", 1, 100).entries
            assertEquals(listOf("/preview/1.jpg?sign=sample", "", ""), entries.map { it.thumbnail })
        }
    }
    @Test fun addressRequiresExplicitHttpAndRejectsEmbeddedCredentials() {
        assertEquals("https://example.com/prefix/", ServerAddress.normalize(" https://example.com/prefix/// ", false))
        for (bad in listOf("http://example.com", "https://user:secret@example.com", "https://example.com/?token=s")) {
            try { ServerAddress.normalize(bad, false); fail() } catch (_: ClientFailure) { }
        }
    }
}

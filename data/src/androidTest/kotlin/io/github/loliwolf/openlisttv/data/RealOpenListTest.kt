package io.github.loliwolf.openlisttv.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.loliwolf.openlisttv.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealOpenListTest {
    @Test fun loginRestrictedRootPasswordAndSignedProxy() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("realOpenListUrl")
        assumeTrue("Optional isolated integration fixture", url == "http://10.0.2.2:18766/")
        val api = OpenListApi()
        val session = api.login(Site("real-fixture", "local fixture", url!!, "tv-fixture", true), "demo-fixture-only")
        assertEquals("/Test", session.basePath)
        val files = api.list(session, "/", "", 1, 100)
        assertTrue(files.entries.any { it.name == "2.png" })
        assertFalse(files.entries.any { it.path.startsWith("/Test/") })
        try { api.list(session, "/Locked", "", 1, 100); fail("Password directory must be protected") }
        catch (e: ClientFailure) { assertEquals(FailureKind.ACCESS, e.kind) }
        val secret = api.list(session, "/Locked", "1234", 1, 100).entries.single()
        val media = api.resolve(session, secret, "1234")
        assertTrue(media.url.contains("sign="))
        HttpClients.media(true).newCall(okhttp3.Request.Builder().url(media.url).build()).execute().use {
            assertTrue(it.isSuccessful)
            assertTrue(it.body.bytes().isNotEmpty())
        }
        api.logout(session)
        try { api.validate(session); fail("Revoked token must not be accepted") } catch (e: ClientFailure) { assertEquals(FailureKind.AUTH, e.kind) }
    }
}

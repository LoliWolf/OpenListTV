package io.github.loliwolf.openlisttv

import android.app.AlertDialog
import android.content.*
import android.os.SystemClock
import android.widget.Button
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.util.UnstableApi
import io.github.loliwolf.openlisttv.data.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@UnstableApi
@androidx.test.filters.SdkSuppress(minSdkVersion = 26)
@RunWith(AndroidJUnit4::class)
class UpdateFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun eventually(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 12000
        while (!condition()) { if (SystemClock.elapsedRealtime() >= end) fail("Timed out"); SystemClock.sleep(30) }
    }
    private fun fixtureBytes(): ByteArray = instrumentation.context.assets.open("update-validation.apk").use { it.readBytes() }
    private fun release(bytes: ByteArray) = GithubRelease("1.0.1-preview.6", "https://github.com/LoliWolf/OpenListTV/releases/tag/v1.0.1-preview.6", "https://github.com/LoliWolf/OpenListTV/releases/download/v1.0.1-preview.6/OpenListTV-1.0.1-preview.6.apk", "Private fixture; never install", true, bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
    private inner class Fixture : AutoCloseable {
        val id = UUID.randomUUID().toString()
        val root = File(context.filesDir, "updates/ready/test-$id")
        val isolated = object : ContextWrapper(context) { override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("update-test-$id-$name", mode) }
        val packages = UpdatePackages(isolated, root)
        val server = MockWebServer().apply { start() }
        val network = UpdateNetwork(mapForTest = { server.url(it.encodedPath) })
        override fun close() {
            runBlocking { packages.cancelAndJoin() }; server.shutdown()
            root.deleteRecursively(); isolated.getSharedPreferences("github-updates", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
    @Test fun verifiesSignedNewerPackageCachesItAndExposesOnlyReadyFile() = runBlocking<Unit> {
        Fixture().use { f ->
            val bytes = fixtureBytes(); val release = release(bytes)
            f.server.enqueue(MockResponse().setChunkedBody(Buffer().write(bytes), 1031))
            val ready = f.packages.obtain(release, f.network)
            assertArrayEquals(bytes, ready.readBytes()); assertEquals(ready, f.packages.cached()!!.second)
            assertEquals(ready, f.packages.obtain(release, f.network)); assertEquals(1, f.server.requestCount)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.update-files", ready)
            assertEquals("content", uri.scheme)
            assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(context, "${context.packageName}.update-files", File(context.filesDir, "sites.xml")) }
        }
    }
    @Test fun rejectsDigestVersionAndNonApkPayloadsWithoutReplacingReadyCache() = runBlocking<Unit> {
        Fixture().use { f ->
            val bytes = fixtureBytes(); val valid = release(bytes)
            f.server.enqueue(MockResponse().setBody(Buffer().write(bytes))); val previous = f.packages.obtain(valid, f.network)
            for ((offer, body) in listOf(valid.copy(sha256 = "0".repeat(64)) to bytes, valid.copy(version = "1.0.1-preview.7", pageUrl = valid.pageUrl.replace("preview.6", "preview.7"), apkUrl = valid.apkUrl!!.replace("preview.6", "preview.7")) to bytes, valid.copy(size = 3, sha256 = null) to byteArrayOf(1,2,3))) {
                f.server.enqueue(MockResponse().setBody(Buffer().write(body)))
                try { f.packages.obtain(offer, f.network); fail("Bad package accepted") } catch (_: Exception) { }
                assertTrue(previous.isFile); assertEquals(previous, f.packages.cached()!!.second)
            }
        }
    }
    @Test fun corruptCachedFileIsNotReusedAndCanBeRedownloaded() = runBlocking<Unit> {
        Fixture().use { f ->
            val bytes = fixtureBytes(); val release = release(bytes)
            repeat(2) { f.server.enqueue(MockResponse().setBody(Buffer().write(bytes))) }
            val first = f.packages.obtain(release, f.network); first.writeBytes(byteArrayOf(1,2,3))
            assertNull(f.packages.cached()); val second = f.packages.obtain(release, f.network)
            assertArrayEquals(bytes, second.readBytes()); assertEquals(2, f.server.requestCount)
        }
    }
    @Test fun unsignedPackageWithCorrectNameVersionAndDigestIsStillRejected() = runBlocking<Unit> {
        Fixture().use { f ->
            val signed = File(context.cacheDir, "signed-test-${f.id}.apk").apply { writeBytes(fixtureBytes()) }
            try {
                val output = ByteArrayOutputStream()
                ZipFile(signed).use { zip -> ZipOutputStream(output).use { rebuilt ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement(); if (e.name.startsWith("META-INF/")) continue
                        rebuilt.putNextEntry(ZipEntry(e.name)); zip.getInputStream(e).use { it.copyTo(rebuilt) }; rebuilt.closeEntry()
                    }
                } }
                val unsigned = output.toByteArray()
                f.server.enqueue(MockResponse().setBody(Buffer().write(unsigned)))
                try { f.packages.obtain(release(unsigned), f.network); fail("Unsigned APK accepted") } catch (_: UpdateFailure) { }
                assertNull(f.packages.cached())
            } finally { signed.delete() }
        }
    }
    @Test fun cancelledDownloadJoinsAndDoesNotBecomeInstallable() = runBlocking<Unit> {
        Fixture().use { f ->
            val bytes = fixtureBytes(); val began = CompletableDeferred<Unit>()
            f.server.enqueue(MockResponse().setBody(Buffer().write(bytes)).throttleBody(256, 100, TimeUnit.MILLISECONDS))
            val job = launch { f.packages.obtain(release(bytes), f.network) { _, _ -> began.complete(Unit) } }
            withTimeout(6000) { began.await() }; job.cancelAndJoin()
            assertFalse(f.packages.busy); assertNull(f.packages.cached())
            assertTrue(File(f.root,"staging").listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun downloadAutomaticallyRequestsSystemInstallerButNeverConfirmsInstallation() {
        Fixture().use { f ->
            val prefs = context.getSharedPreferences("github-updates", Context.MODE_PRIVATE)
            val auto = prefs.getBoolean("automatic", true); prefs.edit().putBoolean("automatic", false).commit()
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val launched = CopyOnWriteArrayList<Intent>(); var controller: UpdateController? = null
            try {
                val bytes = fixtureBytes(); f.server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
                main {
                    controller = UpdateController(activity, { true }, networkFactory = { f.network }, intentLauncher = { launched += it }, packageStore = f.packages, prefs = f.isolated.getSharedPreferences("github-updates", Context.MODE_PRIVATE))
                    controller!!.beginUpdate(release(bytes))
                }
                eventually { runBlocking { f.packages.cached() != null } }
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    eventually {
                        var ready = false
                        main {
                            val dialog = UpdateController::class.java.getDeclaredField("dialog").apply { isAccessible = true }.get(controller) as? AlertDialog
                            val button = dialog?.getButton(AlertDialog.BUTTON_POSITIVE)
                            if (button?.text == "去授权并安装") { ready = true; button.performClick() }
                        }; ready
                    }
                    eventually { launched.isNotEmpty() }
                    assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, launched.single().action)
                } else {
                    eventually { launched.isNotEmpty() }
                    assertEquals(Intent.ACTION_VIEW, launched.single().action)
                }
                val readyFile = runBlocking { f.packages.cached()!!.second }
                main {
                    val intent = controller!!.installerIntent(readyFile)
                    assertEquals("application/vnd.android.package-archive", intent.type)
                    assertEquals("content", intent.data!!.scheme); assertNotNull(intent.component)
                    assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                }
            } finally {
                main { controller?.background(); activity.finish() }
                prefs.edit().putBoolean("automatic", auto).remove("permission.pending").remove("permission.time").commit()
            }
        }
    }
    @Test fun automaticEntryRechecksSameVersionAndDisabledSwitchStopsIt() {
        Fixture().use { f ->
            val appPrefs = context.getSharedPreferences("github-updates", Context.MODE_PRIVATE)
            val wasAuto = appPrefs.getBoolean("automatic", true); appPrefs.edit().putBoolean("automatic", false).commit()
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val settings = f.isolated.getSharedPreferences("github-updates", Context.MODE_PRIVATE)
            var controller: UpdateController? = null
            val response = """[{"tag_name":"v2.0.0","html_url":"https://github.com/LoliWolf/OpenListTV/releases/tag/v2.0.0","assets":[{"name":"OpenListTV-2.0.0.apk","browser_download_url":"https://github.com/LoliWolf/OpenListTV/releases/download/v2.0.0/OpenListTV-2.0.0.apk"}]}]"""
            repeat(2) { f.server.enqueue(MockResponse().setBody(response)) }
            fun dialog(): AlertDialog? = UpdateController::class.java.getDeclaredField("dialog").apply { isAccessible = true }.get(controller) as? AlertDialog
            try {
                main { controller = UpdateController(activity, { true }, networkFactory = { f.network }, packageStore = f.packages, prefs = settings); controller!!.onStart(); controller!!.foreground() }
                eventually { var found = false; main { found = dialog()?.getButton(AlertDialog.BUTTON_POSITIVE)?.text == "更新" }; found }
                main { dialog()!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick() }
                eventually { var closed = false; main { closed = dialog() == null }; closed }
                main { repeat(5) { controller!!.foreground() } }; SystemClock.sleep(100); assertEquals(1, f.server.requestCount)
                main { controller!!.onStart(); controller!!.foreground() }
                eventually { var found = false; main { found = dialog()?.getButton(AlertDialog.BUTTON_POSITIVE)?.text == "更新" }; found }; assertEquals(2, f.server.requestCount)
                main { dialog()!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); controller!!.automatic = false; controller!!.onStart(); controller!!.foreground() }
                SystemClock.sleep(100); assertEquals(2, f.server.requestCount)
            } finally { main { controller?.background(); activity.finish() }; appPrefs.edit().putBoolean("automatic", wasAuto).commit() }
        }
    }
    @Test fun returningFromGrantedPermissionRequestsInstallerOnlyOnce() {
        // Permission revocation kills the app process on Android. The external dedicated-
        // emulator runner changes/restores AppOps outside instrumentation, not from here.
        org.junit.Assume.assumeTrue("Use tools/update-flow-device-tests.mjs to grant before this run", context.packageManager.canRequestPackageInstalls())
        val appPrefs = context.getSharedPreferences("github-updates", Context.MODE_PRIVATE)
        val oldAuto = appPrefs.getBoolean("automatic", true); appPrefs.edit().putBoolean("automatic", false).commit()
        Fixture().use { f ->
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            var controller: UpdateController? = null
            try {
                val bytes = fixtureBytes(); f.server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
                runBlocking { f.packages.obtain(release(bytes), f.network) }
                val settings = f.isolated.getSharedPreferences("github-updates", Context.MODE_PRIVATE)
                settings.edit().putBoolean("permission.pending", true).putLong("permission.time", System.currentTimeMillis()).commit()
                val intents = CopyOnWriteArrayList<Intent>()
                main {
                    controller = UpdateController(activity, { true }, networkFactory = { f.network }, intentLauncher = { intents += it }, packageStore = f.packages, prefs = settings)
                    controller!!.onStart(); controller!!.onResume()
                }
                eventually { intents.isNotEmpty() }; assertEquals(Intent.ACTION_VIEW, intents.single().action)
                main { controller!!.onResume() }; SystemClock.sleep(100)
                assertEquals(1, intents.size); assertEquals(1, f.server.requestCount)
            } finally {
                main { controller?.background(); activity.finish() }
                appPrefs.edit().putBoolean("automatic", oldAuto).commit()
            }
        }
    }
}

package io.github.loliwolf.openlisttv

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.loliwolf.openlisttv.core.*
import io.github.loliwolf.openlisttv.data.*
import io.github.loliwolf.openlisttv.media.ImagePipeline
import io.github.loliwolf.openlisttv.media.MediaEngine
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@UnstableApi
@RunWith(AndroidJUnit4::class)
class LightweightFeaturesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun eventually(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 12000
        while (!condition()) { if (SystemClock.elapsedRealtime() >= end) fail("Timed out"); SystemClock.sleep(30) }
    }
    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        return ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.toByteArray() }
    }
    private inner class DownloadsFixture : AutoCloseable {
        val name = "downloads-feature-${UUID.randomUUID()}.db"
        val store = DownloadStore(context, name)
        val catalogName = "catalog-feature-${UUID.randomUUID()}.db"
        val catalog = Catalog(context, catalogName)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = DownloadController(context, scope, store)
        val server = MockWebServer().apply { start() }
        val site = Site("test-site", "fixture", server.url("/").toString(), "fixture", true)
        val resolves = AtomicInteger()
        fun repo(selectedSite: Site = site): FileRepository = FileRepository(Session(selectedSite, "never-send-this-token"), object : FileGateway {
            override suspend fun list(session: Session, path: String, password: String, page: Int, perPage: Int) = Listing(emptyList(), 0)
            override suspend fun resolve(session: Session, entry: Entry, password: String): MediaSource { resolves.incrementAndGet(); return MediaSource(entry, server.url("/file?private-signature=fixture").toString()) }
        }, catalog)
        fun complete(bytes: ByteArray, name: String = "fixture.png"): SavedDownload {
            val record = store.create(site, Entry("/$name", name, false, bytes.size.toLong(), sign = "must-not-persist", thumbnail = "must-not-persist"))
            store.file(record.id, false).writeBytes(bytes); store.settle(record.id, DownloadStatus.COMPLETE)
            return store.get(record.id)!!
        }
        override fun close() {
            runBlocking { withContext(Dispatchers.Main) { controller.pauseAndJoin() }; scope.coroutineContext[Job]!!.cancelAndJoin() }
            store.list().forEach { store.remove(it.id) }; store.close(); catalog.close()
            context.deleteDatabase(name); context.deleteDatabase(catalogName); server.shutdown()
        }
    }
    @Test fun singleWriterPauseSafeResumeAndPersistentLocalRecord() {
        DownloadsFixture().use { f ->
            val bytes = png() + ByteArray(256 * 1024) { (it % 251).toByte() }
            f.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val offset = request.getHeader("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
                    return MockResponse().setBody(Buffer().write(bytes, offset, bytes.size - offset)).setHeader("ETag", "\"stable\"")
                        .apply { if (offset > 0) { setResponseCode(206); setHeader("Content-Range", "bytes $offset-${bytes.lastIndex}/${bytes.size}") } else throttleBody(4096, 50, TimeUnit.MILLISECONDS) }
                }
            }
            val entry = Entry("/image.png", "image.png", false, bytes.size.toLong())
            main { assertTrue(f.controller.start(f.repo(), entry)); assertFalse(f.controller.start(f.repo(), entry)) }
            eventually { f.controller.state.value.bytes > 0 }
            runBlocking { withContext(Dispatchers.Main) { f.controller.pauseAndJoin("test pause") } }
            val paused = f.store.list().single()
            assertEquals(DownloadStatus.PAUSED, paused.status); assertTrue(paused.bytes > 0)
            val length = f.store.file(paused.id, true).length(); SystemClock.sleep(150); assertEquals(length, f.store.file(paused.id, true).length())
            main { assertTrue(f.controller.start(f.repo(), paused.entry, paused)) }
            eventually { !f.controller.busy }
            val done = f.store.get(paused.id)!!
            assertEquals(DownloadStatus.COMPLETE, done.status); assertArrayEquals(bytes, f.store.completedFile(done).readBytes())
            assertEquals(2, f.resolves.get()); assertFalse(f.store.file(done.id, true).exists())
            assertNull(f.server.takeRequest().getHeader("Authorization"))
            assertEquals("bytes=$length-", f.server.takeRequest().getHeader("Range"))
            DownloadStore(context, f.name).use { reopened -> assertEquals(done, reopened.get(done.id)); assertTrue(reopened.completedFile(done).isFile) }
        }
    }
    @Test fun accountMismatchCannotMutateOrResumeAnotherDownload() {
        DownloadsFixture().use { f ->
            val record = f.complete(png())
            assertEquals("", record.entry.sign); assertEquals("", record.entry.thumbnail)
            main { f.controller.start(f.repo(f.site.copy(username = "other")), record.entry, record) }
            eventually { !f.controller.busy }
            assertEquals(record, f.store.get(record.id)); assertEquals(0, f.resolves.get()); assertTrue(f.store.completedFile(record).isFile)
        }
    }
    @Test fun committedRenameIsRecoveredButPartialFileIsNotMarkedComplete() {
        DownloadsFixture().use { f ->
            val entry = Entry("/crash.png", "crash.png", false)
            val committed = f.store.create(f.site, entry)
            f.store.metadata(committed.id, 3, "\"one\"", "opaque-url-hash")
            f.store.file(committed.id, false).writeBytes(byteArrayOf(1, 2, 3))
            val partial = f.store.create(f.site, entry.copy(path = "/partial.png"))
            f.store.metadata(partial.id, 3, "\"one\"", "opaque-url-hash")
            f.store.file(partial.id, true).writeBytes(byteArrayOf(1))
            DownloadStore(context, f.name).use { reopened ->
                assertEquals(DownloadStatus.COMPLETE, reopened.get(committed.id)!!.status)
                assertEquals(3L, reopened.get(committed.id)!!.bytes)
                assertEquals(DownloadStatus.PAUSED, reopened.get(partial.id)!!.status)
                assertFalse(reopened.file(partial.id, false).exists())
            }
        }
    }
    @Test fun activityHomeStopsGlobalTransferAndReturnDoesNotAutoResume() {
        DownloadsFixture().use { f ->
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val global = (context.applicationContext as TvApplication).transfers
            f.server.enqueue(MockResponse().setBody("x".repeat(1024 * 1024)).throttleBody(4096, 50, TimeUnit.MILLISECONDS))
            var id: String? = null
            try {
                eventually {
                    var visible = false
                    main { visible = MainActivity::class.java.getDeclaredField("browserVisible").apply { isAccessible = true }.getBoolean(activity) }
                    visible
                }
                main { assertTrue(global.start(f.repo(), Entry("/lifecycle.bin", "lifecycle.bin", false))) }
                eventually { global.state.value.id != null }
                id = global.state.value.id
                eventually { global.state.value.bytes > 0 }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_HOME)
                eventually { !global.busy }
                assertEquals(DownloadController.Phase.PAUSED, global.state.value.phase)
                val size = global.store.file(id!!, true).length()
                main { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
                SystemClock.sleep(300)
                assertFalse(global.busy); assertEquals(size, global.store.file(id!!, true).length()); assertEquals(1, f.server.requestCount)
            } finally {
                runBlocking { withContext(Dispatchers.Main) { global.pauseAndJoin() } }
                id?.let { global.store.remove(it) }; global.changed(); main { activity.finish() }
            }
        }
    }
    @Test fun downloadDatabaseMigrationKeepsPreviouslySavedFiles() {
        DownloadsFixture().use { f ->
            val id = UUID.randomUUID().toString()
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(f.name), null).use { db ->
                db.execSQL("CREATE TABLE downloads(id TEXT PRIMARY KEY,identity TEXT,site_label TEXT,path TEXT,name TEXT,size INTEGER,modified TEXT,status TEXT,bytes INTEGER,total INTEGER,etag TEXT,message TEXT,created INTEGER)")
                db.execSQL("INSERT INTO downloads VALUES(?,?,'fixture','/old.png','old.png',3,'','COMPLETE',3,3,'','','1')", arrayOf(id, DownloadStore.identity(f.site)))
                db.version = 1
            }
            f.store.directory.mkdirs(); f.store.file(id, false).writeBytes(byteArrayOf(1, 2, 3))
            val restored = f.store.get(id)!!
            assertEquals("", restored.sourceKey); assertEquals(DownloadStatus.COMPLETE, restored.status)
            assertArrayEquals(byteArrayOf(1, 2, 3), f.store.completedFile(restored).readBytes())
            assertEquals(2, f.store.readableDatabase.version)
        }
    }
    @Test fun localImageLoadsWithoutNetworkOrAnotherDiskCopy() = runBlocking<Unit> {
        DownloadsFixture().use { f ->
            val record = f.complete(png())
            val session = Session(Site("local", "local", "", ""), "")
            val gateway = LocalDownloadGateway(f.store)
            val entry = gateway.list(session, "/", "", 1, 100).entries.single()
            val source = gateway.resolve(session, entry, "")
            val bitmap = ImagePipeline(context, HttpClients.media(false)).load(source, 1920, 1080)
            assertEquals(48, bitmap.width); assertEquals(32, bitmap.height); assertEquals(Color.GREEN, bitmap.getPixel(5, 5)); bitmap.recycle()
            assertTrue(f.store.completedFile(record).isFile); assertEquals(0, f.server.requestCount)
            assertTrue(context.cacheDir.resolve("images").listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun localAudioUsesSamePlayerAndOnDemandInformation() {
        DownloadsFixture().use { f ->
            val rate = 16000; val dataSize = rate * 2
            val bytes = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVEfmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(rate); putInt(rate * 2); putShort(2); putShort(16); put("data".toByteArray()); putInt(dataSize)
            }.array()
            val record = f.complete(bytes, "fixture.wav")
            val ready = AtomicInteger(); var engine: MediaEngine? = null
            try {
                main {
                    engine = MediaEngine(context, HttpClients.media(false), object : MediaEngine.Events {
                        override fun ready() { ready.incrementAndGet() }
                        override fun ended() = Unit
                        override fun qualityChanged(message: String) = Unit
                        override fun failed(message: String, sourceExpired: Boolean, kind: FailureKind) { fail(message) }
                    })
                    engine!!.play(MediaSource(record.entry, f.store.completedFile(record).toURI().toString()))
                }
                eventually { ready.get() > 0 }
                main { val info = engine!!.information(); assertTrue(info.contains("采样率：16000")); assertFalse(info.contains("file:")); assertFalse(info.contains("http")) }
                assertEquals(0, f.server.requestCount)
            } finally { main { engine?.release() } }
        }
    }
    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    fun exportUsesUserGrantedUriAndPreservesOriginalFile() {
        DownloadsFixture().use { f ->
            val bytes = png(); val record = f.complete(bytes)
            // A synthetic, app-owned scoped-storage fixture; no broad storage permissions.
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "openlisttv-test-${UUID.randomUUID()}.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png"); put(MediaStore.MediaColumns.IS_PENDING, 1)
            })!!
            try {
                main { assertTrue(f.controller.export(record, uri)) }
                eventually { !f.controller.busy }
                assertEquals(DownloadController.Phase.COMPLETE, f.controller.state.value.phase)
                assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
                assertArrayEquals(bytes, f.store.completedFile(record).readBytes())
            } finally { context.contentResolver.delete(uri, null, null) }
        }
    }

    private inner class PlayerFixture(slow: Boolean = false) : AutoCloseable {
        val files = DownloadsFixture()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val exits = AtomicInteger()
        lateinit var panel: PlaybackPanel
        init {
            val image = png()
            files.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(Buffer().write(image)).setBodyDelay(if (slow) 3000 else 0, TimeUnit.MILLISECONDS)
            }
            val entry = Entry("/a.png", "a.png", false)
            val repository = FileRepository(Session(files.site, "secret"), object : FileGateway {
                override suspend fun list(session: Session, path: String, password: String, page: Int, perPage: Int) = Listing(listOf(entry), 1)
                override suspend fun resolve(session: Session, entry: Entry, password: String) = MediaSource(entry, files.server.url("/a.png?private-signature=fixture").toString())
            }, files.catalog)
            main {
                panel = PlaybackPanel(activity, repository, files.catalog, "/", SortOrder(), SlideshowOptions(recursive = false, imageOnly = true, imageSeconds = 30), files.scope, { exits.incrementAndGet(); panel.dispose() }, { null })
                activity.setContentView(panel); panel.start(null)
            }
        }
        fun deadline(ms: Long) = main {
            val field = PlaybackPanel::class.java.getDeclaredField("stopDeadline").apply { isAccessible = true }
            (field.get(panel) as SlideshowDeadline).set(ms, SystemClock.elapsedRealtime()); panel.foreground()
        }
        override fun close() { main { panel.dispose(); activity.finish() }; files.close() }
    }
    @Test fun stopDeadlineExpiresEvenDuringLoadingAndUserPause() {
        PlayerFixture(slow = true).use { f -> f.deadline(200); eventually { f.exits.get() == 1 }; SystemClock.sleep(250); assertEquals(1, f.exits.get()) }
        PlayerFixture(slow = true).use { f ->
            eventually { f.files.server.requestCount > 0 }
            f.deadline(300)
            main { f.panel.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)) }
            eventually { f.exits.get() == 1 }
        }
    }
    @Test fun backgroundDoesNotWakeAndExpiredDeadlineStopsOnReturn() {
        PlayerFixture().use { f ->
            f.deadline(200); main { f.panel.background() }; SystemClock.sleep(400)
            assertEquals(0, f.exits.get())
            main { f.panel.foreground() }; assertEquals(1, f.exits.get())
        }
    }
    @Test fun informationIsASanitizedSnapshotWithNoExtraRequests() {
        PlayerFixture().use { f ->
            eventually { f.files.server.requestCount >= 2 }
            SystemClock.sleep(150)
            fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
            var text = ""
            main {
                PlayerUiActions.click(f.panel, "播放信息")
                val dialog = PlaybackPanel::class.java.getDeclaredField("activeDialog").apply { isAccessible = true }.get(f.panel) as AlertDialog
                text = views(dialog.window!!.decorView).filterIsInstance<TextView>().joinToString("\n") { it.text }
            }
            assertTrue(text.contains("显示位图：48 × 32")); assertTrue(text.contains("位图内存")); assertFalse(text.contains("private-signature")); assertFalse(text.contains("http"))
            val count = f.files.server.requestCount; SystemClock.sleep(300); assertEquals(count, f.files.server.requestCount)
        }
    }
}

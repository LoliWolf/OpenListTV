package io.github.loliwolf.openlisttv

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.loliwolf.openlisttv.core.*
import io.github.loliwolf.openlisttv.data.*
import io.github.loliwolf.openlisttv.media.ImagePipeline
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

@UnstableApi
@RunWith(AndroidJUnit4::class)
class SeamlessSlideshowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val colors = mapOf("1.png" to Color.rgb(204, 40, 40), "2.png" to Color.rgb(34, 136, 51), "3.png" to Color.rgb(51, 102, 221))
    private fun png(color: Int, w: Int = 320, h: Int = 180): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); bitmap.recycle(); stream.toByteArray() }
    }
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun eventually(timeout: Long = 10000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition()) { if (SystemClock.elapsedRealtime() > end) fail("Timed out waiting for condition"); SystemClock.sleep(30) }
    }
    private inner class Fixture(val delaySecond: AtomicLong = AtomicLong(0), val badSecond: Boolean = false, val rateLimited: Boolean = false, val prefetch: Boolean = true, val oversizedSecond: Boolean = false) : AutoCloseable {
        val requests = CopyOnWriteArrayList<Pair<String, Long>>()
        val frames = CopyOnWriteArrayList<Pair<String, Long>>()
        val violations = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val name = "slideshow-test-${UUID.randomUUID()}.db"
        val catalog = Catalog(context, name)
        val activity: MainActivity
        val panel: PlaybackPanel
        val photo: ImageView
        val center: TextView
        private var firstSeen = false
        private var drawListener: ViewTreeObserver.OnDrawListener? = null
        init {
            val bytes = colors.mapValues { png(it.value) }
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val file = request.path!!.removePrefix("/")
                    requests += file to SystemClock.elapsedRealtime()
                    if (request.getHeader("Authorization") != null || request.getHeader("Cookie") != null) violations += "media credentials"
                    if (file == "2.png" && rateLimited) return MockResponse().setResponseCode(429)
                    if (file == "2.png" && oversizedSecond) return MockResponse().setBody(Buffer().write(bytes[file]!!)).setHeader("Content-Length", Budgets.IMAGE_DISK_BYTES + 1)
                    val body = if (file == "2.png" && badSecond) byteArrayOf(1, 2, 3) else bytes[file]!!
                    return MockResponse().setBody(Buffer().write(body)).setBodyDelay(if (file == "2.png") delaySecond.get() else 0, TimeUnit.MILLISECONDS)
                }
            }
            server.start()
            val entries = colors.keys.map { Entry("/$it", it, false, modified = "fixture") }
            val gateway = object : FileGateway {
                override suspend fun list(session: Session, path: String, password: String, page: Int, perPage: Int) = Listing(entries, entries.size)
                override suspend fun resolve(session: Session, entry: Entry, password: String) = MediaSource(entry, server.url(entry.path).toString())
            }
            val repository = FileRepository(Session(Site("fixture", "fixture", server.url("/").toString(), "fixture", true), "unused"), gateway, catalog)
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            var created: PlaybackPanel? = null
            onMain {
                created = PlaybackPanel(activity, repository, catalog, "/", SortOrder(), SlideshowOptions(recursive = false, imageOnly = true, imageSeconds = 1, prefetch = prefetch), scope, {}, { null })
                activity.setContentView(created!!)
            }
            panel = created!!; photo = panel.getChildAt(1) as ImageView; center = panel.getChildAt(2) as TextView
            onMain {
                drawListener = object : ViewTreeObserver.OnDrawListener {
                    override fun onDraw() {
                        val displayed = (photo.drawable as? BitmapDrawable)?.bitmap
                        if (displayed != null) {
                            if (displayed.isRecycled) violations += "recycled displayed bitmap"
                            firstSeen = true
                            val file = photo.contentDescription?.toString().orEmpty()
                            if (frames.lastOrNull()?.first != file) frames += file to SystemClock.elapsedRealtime()
                        }
                        if (firstSeen && !rateLimited) {
                            if (photo.visibility != View.VISIBLE || displayed == null) violations += "blank image frame"
                            if (center.visibility == View.VISIBLE && listOf("加载", "读取", "准备下一").any { center.text.contains(it) }) violations += "loading text"
                        }
                    }
                }.also { panel.viewTreeObserver.addOnDrawListener(it) }
                panel.start(null)
            }
            waitImage("1.png")
        }
        fun displayed(): String { var result = ""; onMain { result = photo.contentDescription?.toString().orEmpty() }; return result }
        fun waitImage(name: String) = eventually { displayed() == "图片 $name" }
        fun press(code: Int) = onMain { panel.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, code)) }
        fun click(label: String) = onMain {
            fun find(view: View): Button? {
                if (view is Button && view.text.toString() == label) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            checkNotNull(find(panel)).performClick()
        }
        fun checkScreenPixels() {
            val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
            try {
                // Central image area: excludes controls/letterboxing and includes the old loading text.
                for (y in screenshot.height / 2 - 25..screenshot.height / 2 + 25 step 5) {
                    for (x in screenshot.width / 2 - 100..screenshot.width / 2 + 100 step 5) {
                        val pixel = screenshot.getPixel(x, y)
                        assertTrue("Unexpected blank/text pixel in rendered image", colors.values.any { color ->
                            kotlin.math.abs(Color.red(pixel) - Color.red(color)) < 10 && kotlin.math.abs(Color.green(pixel) - Color.green(color)) < 10 && kotlin.math.abs(Color.blue(pixel) - Color.blue(color)) < 10
                        })
                    }
                }
            } finally { screenshot.recycle() }
        }
        override fun close() {
            onMain { drawListener?.let { panel.viewTreeObserver.removeOnDrawListener(it) }; panel.dispose(); activity.finish() }
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
            server.shutdown(); catalog.close(); context.deleteDatabase(name)
        }
    }
    @Test fun slowPreloadStartsEarlyAndKeepsRenderedImageUntilAtomicSwap() {
        Fixture(AtomicLong(2800)).use { f ->
            eventually { f.requests.any { it.first == "2.png" } }
            assertEquals(0, f.requests.count { it.first == "3.png" })
            SystemClock.sleep(1300)
            assertEquals("图片 1.png", f.displayed()); f.checkScreenPixels()
            assertEquals(0, f.requests.count { it.first == "3.png" })
            f.waitImage("2.png"); f.checkScreenPixels(); f.waitImage("3.png"); f.checkScreenPixels()
            val first = f.frames.first { it.first == "图片 1.png" }.second
            val nextRequested = f.requests.first { it.first == "2.png" }.second
            assertTrue("Next image was not requested during current dwell", nextRequested < first + 1000)
            val second = f.frames.first { it.first == "图片 2.png" }.second
            val third = f.frames.first { it.first == "图片 3.png" }.second
            assertTrue("Dwell must start after actual display", third - second >= 900)
            assertTrue(f.violations.toString(), f.violations.isEmpty())
        }
    }
    @Test fun legacyAddressPrefetchOffDoesNotDisableImagePredecode() {
        Fixture(AtomicLong(2800), prefetch = false).use { f ->
            eventually(timeout = 700) { f.requests.any { it.first == "2.png" } }
            SystemClock.sleep(1500); assertEquals("图片 1.png", f.displayed()); f.checkScreenPixels()
            f.waitImage("2.png"); assertTrue(f.violations.toString(), f.violations.isEmpty())
        }
    }
    @Test fun brokenNextImageIsSkippedWithoutClearingTheLastGoodImage() {
        Fixture(badSecond = true).use { f ->
            SystemClock.sleep(1250); assertEquals("图片 1.png", f.displayed()); f.checkScreenPixels()
            f.waitImage("3.png"); assertFalse(f.frames.any { it.first == "图片 2.png" })
            assertTrue(f.violations.toString(), f.violations.isEmpty())
        }
    }
    @Test fun oversizedImageIsSkippedAndSlideshowAdvancesPastIt() {
        Fixture(oversizedSecond = true).use { f ->
            f.waitImage("3.png")
            assertFalse(f.frames.any { it.first == "图片 2.png" })
            assertTrue(f.violations.toString(), f.violations.isEmpty())
        }
    }
    @Test fun rateLimitPausesWithoutScanningPastTheFailedItem() {
        Fixture(rateLimited = true).use { f ->
            eventually { var paused = false; onMain { paused = f.center.visibility == View.VISIBLE && f.center.text.isNotEmpty() }; paused }
            SystemClock.sleep(1200); assertEquals("图片 1.png", f.displayed())
            assertEquals(0, f.requests.count { it.first == "3.png" })
        }
    }
    @Test fun pauseHistoryTrimAndExitDoNotLoseTheReservedNextImage() {
        Fixture(AtomicLong(2600)).use { f ->
            eventually { f.requests.any { it.first == "2.png" } }; f.press(KeyEvent.KEYCODE_MEDIA_PAUSE)
            SystemClock.sleep(1200); assertEquals("图片 1.png", f.displayed()); assertEquals(0, f.requests.count { it.first == "3.png" })
            f.delaySecond.set(0); f.press(KeyEvent.KEYCODE_MEDIA_PLAY); f.waitImage("2.png")
            f.press(KeyEvent.KEYCODE_MEDIA_PAUSE); f.click("上一项"); f.waitImage("1.png")
            f.press(KeyEvent.KEYCODE_MEDIA_PAUSE); f.click("下一项"); f.waitImage("2.png")
            f.press(KeyEvent.KEYCODE_MEDIA_PAUSE); onMain { f.panel.trimMemory() }; f.click("下一项"); f.waitImage("3.png")
            f.press(KeyEvent.KEYCODE_MEDIA_PAUSE); assertTrue(f.violations.toString(), f.violations.isEmpty())
            onMain { f.panel.background(); assertNull(f.photo.drawable) }
            val count = f.requests.size; SystemClock.sleep(500); assertEquals(count, f.requests.size)
        }
    }
    @Test fun decoderRespectsTwoImageBudgetAndCancellationRemovesTemporaryInput() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(png(Color.RED, 3840, 2160))))
            server.enqueue(MockResponse().setBody(Buffer().write(png(Color.BLUE))).setBodyDelay(3, TimeUnit.SECONDS))
            server.start()
            val pipeline = ImagePipeline(context, HttpClients.media(true))
            val image = pipeline.load(MediaSource(Entry("/a.png", "a.png", false), server.url("/a.png").toString()), 3840, 2160, slideshow = true)
            assertTrue(image.allocationByteCount <= Budgets.SLIDESHOW_IMAGE_BYTES / 2); image.recycle()
            val job = launch { pipeline.load(MediaSource(Entry("/b.png", "b.png", false), server.url("/b.png").toString()), 3840, 2160, slideshow = true).recycle() }
            delay(150); job.cancelAndJoin()
            eventually { context.cacheDir.resolve("images").listFiles().orEmpty().isEmpty() }
        }
    }
}

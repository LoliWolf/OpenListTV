package io.github.loliwolf.openlisttv

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.loliwolf.openlisttv.core.*
import io.github.loliwolf.openlisttv.data.*
import io.github.loliwolf.openlisttv.media.MediaEngine
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlaybackControlsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun eventually(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime()+12000
        while(!condition()) { if(SystemClock.elapsedRealtime()>deadline) fail("Condition timed out"); SystemClock.sleep(30) }
    }
    private fun all(v: View): List<View> = listOf(v) + if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun field(panel: PlaybackPanel, name: String): Any? = PlaybackPanel::class.java.getDeclaredField(name).apply { isAccessible=true }.get(panel)
    private fun png(): ByteArray {
        val b=Bitmap.createBitmap(400,200,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        return ByteArrayOutputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it);b.recycle();it.toByteArray() }
    }
    private fun wav(): ByteArray {
        val rate=8000;val data=rate*2*300
        return ByteBuffer.allocate(44+data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray());putInt(36+data);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1)
            putInt(rate);putInt(rate*2);putShort(2);putShort(16);put("data".toByteArray());putInt(data)
        }.array()
    }
    private inner class Fixture(audio: Boolean=false, slideshow: Boolean=false): AutoCloseable {
        val server=MockWebServer()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val name="playback-controls-${UUID.randomUUID()}.db"
        val catalog=Catalog(context,name)
        val activity: MainActivity
        val panel: PlaybackPanel
        val entries=if(audio)listOf(Entry("/long.wav","long.wav",false)) else listOf(Entry("/1.png","1.png",false),Entry("/2.png","2.png",false))
        init {
            val bytes=if(audio)wav() else png()
            server.dispatcher=object: Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{
                val start=request.getHeader("Range")?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull()?:0
                if(start>=bytes.size)return MockResponse().setResponseCode(416)
                return MockResponse().setBody(Buffer().write(bytes,start,bytes.size-start)).setHeader("Accept-Ranges","bytes")
                    .setHeader("Content-Type",if(audio)"audio/wav" else "image/png").apply{if(start>0){setResponseCode(206);setHeader("Content-Range","bytes $start-${bytes.lastIndex}/${bytes.size}")}}
            }};server.start()
            val gateway=object:FileGateway{
                override suspend fun list(session:Session,path:String,password:String,page:Int,perPage:Int)=Listing(entries,entries.size)
                override suspend fun resolve(session:Session,entry:Entry,password:String)=MediaSource(entry,server.url(entry.path).toString())
            }
            val repo=FileRepository(Session(Site("fixture","fixture",server.url("/").toString(),"fixture",true),"unused"),gateway,catalog)
            activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            var created:PlaybackPanel?=null;var index=0
            main{
                created=PlaybackPanel(activity,repo,catalog,"/",SortOrder(),if(slideshow)SlideshowOptions(recursive=false,imageOnly=true,imageSeconds=1) else null,scope,{}, {delta->index+=delta;entries.getOrNull(index)})
                activity.setContentView(created!!);created!!.start(if(slideshow)null else entries.first())
            };panel=created!!
            eventually{var ready=false;main{ready=field(panel,"ready")==true};ready}
        }
        fun click(label:String)=main{PlayerUiActions.click(panel,label)}
        fun dialogClick(label:String)=main{
            val dialog=field(panel,"activeDialog") as AlertDialog
            all(dialog.window!!.decorView).filterIsInstance<Button>().single{it.text==label}.performClick()
        }
        fun photo()=panel.getChildAt(1) as ImageView
        fun port()=(field(panel,"engine") as MediaEngine).player
        override fun close(){main{panel.dispose();activity.finish()};runBlocking{scope.coroutineContext[Job]!!.cancelAndJoin()};server.shutdown();catalog.close();context.deleteDatabase(name)}
    }
    @Test fun imageToolsTransformTheSameBitmapAndPanDoesNotAdvanceFiles() {
        Fixture().use { f ->
            var original:Bitmap?=null
            main { original=(f.photo().drawable as BitmapDrawable).bitmap }
            val requests=f.server.requestCount
            f.click("画面设置");repeat(3){f.dialogClick("放大 ＋")};f.dialogClick("向右旋转")
            main {
                assertSame(original,(f.photo().drawable as BitmapDrawable).bitmap)
                val t=field(f.panel,"picture") as PictureTransform;assertTrue(t.zoom>1.9);assertEquals(1,t.quarterTurns)
                val points=floatArrayOf(f.photo().drawable.intrinsicWidth/2f,f.photo().drawable.intrinsicHeight/2f)
                f.photo().imageMatrix.mapPoints(points);assertEquals(f.photo().width/2f,points[0],1f);assertEquals(f.photo().height/2f,points[1],1f)
            }
            f.dialogClick("移动画面")
            eventually{var yes=false;main{yes=field(f.panel,"imagePan")==true};yes}
            main {
                val before=FloatArray(9).also{f.photo().imageMatrix.getValues(it)}
                f.panel.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN))
                val after=FloatArray(9).also{f.photo().imageMatrix.getValues(it)}
                assertTrue(after[Matrix.MTRANS_Y]>before[Matrix.MTRANS_Y]);assertEquals("图片 1.png",f.photo().contentDescription)
                assertTrue(f.panel.back())
            }
            assertEquals(requests,f.server.requestCount)
            f.click("下一项")
            eventually{var yes=false;main{yes=f.photo().contentDescription=="图片 2.png"};yes}
            main { val t=field(f.panel,"picture") as PictureTransform;assertEquals(1.0,t.zoom,0.0);assertEquals(0,t.quarterTurns) }
        }
    }
    @Test fun imageInspectionPausesSlideshowWithoutReplacingItsBitmap() {
        Fixture(slideshow=true).use { f ->
            f.click("画面设置");f.dialogClick("放大 ＋");f.dialogClick("完成")
            var b:Bitmap?=null;main{b=(f.photo().drawable as BitmapDrawable).bitmap}
            SystemClock.sleep(1400)
            main{assertSame(b,(f.photo().drawable as BitmapDrawable).bitmap);assertEquals(true,field(f.panel,"paused"))}
        }
    }
    @Test fun videoRatiosResizeTheExistingSurfaceWithoutCreatingAPlayer() {
        Fixture().use { f ->
            val prefs=context.getSharedPreferences("playback.display",0);val old=prefs.getString("video.framing",null)
            try {
                main { PlaybackPanel::class.java.getDeclaredField("current").apply{isAccessible=true}.set(f.panel,Entry("/video.mp4","video.mp4",false)) }
                f.click("画面设置")
                main { val d=field(f.panel,"activeDialog") as AlertDialog;d.listView.performItemClick(null,VideoFraming.CLASSIC.ordinal,VideoFraming.CLASSIC.ordinal.toLong()) }
                eventually{var okay=false;main{val view=f.panel.getChildAt(0) as PlayerView;val size=VideoFraming.CLASSIC.viewport(f.panel.width,f.panel.height);val p=view.layoutParams as FrameLayout.LayoutParams;okay=p.width==size.first&&p.height==size.second&&view.resizeMode==AspectRatioFrameLayout.RESIZE_MODE_FILL};okay}
                main { assertNull(field(f.panel,"engine"));assertEquals("CLASSIC",prefs.getString("video.framing",null)) }
            } finally {prefs.edit().apply {if(old==null)remove("video.framing") else putString("video.framing",old)}.commit()}
        }
    }
    @Test fun heldRemoteSeekCommitsOnlyOnReleaseAndPreservesPauseState() {
        Fixture(audio=true).use { f ->
            val seeks=AtomicInteger();var origin=0L
            main {
                f.port().addListener(object:Player.Listener{override fun onPositionDiscontinuity(old:Player.PositionInfo,new:Player.PositionInfo,reason:Int){if(reason==Player.DISCONTINUITY_REASON_SEEK)seeks.incrementAndGet()}})
                f.panel.back();origin=f.port().currentPosition
                assertTrue(f.panel.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT)))
                assertFalse(f.port().playWhenReady)
            }
            SystemClock.sleep(1300)
            main { assertEquals(0,seeks.get());assertTrue(kotlin.math.abs(f.port().currentPosition-origin)<1000);assertTrue((field(f.panel,"seek") as RemoteSeekController).active)
                f.panel.handleKey(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_RIGHT));assertTrue(f.port().currentPosition>origin+18000);assertTrue(f.port().playWhenReady) }
            eventually{seeks.get()==1}
            main {
                f.panel.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MEDIA_PAUSE));f.panel.back()
                f.panel.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_LEFT));f.panel.handleKey(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_LEFT));assertFalse(f.port().playWhenReady)
            }
        }
    }
    @Test fun cancelAndLostFocusDoNotSeekOrLeaveARepeatJob() {
        Fixture(audio=true).use { f ->
            main {
                f.panel.back();val origin=f.port().currentPosition
                f.panel.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));assertTrue(f.panel.back())
                assertTrue(kotlin.math.abs(f.port().currentPosition-origin)<1000);assertTrue(f.port().playWhenReady)
                f.panel.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));f.panel.windowFocusChanged(false)
                assertFalse((field(f.panel,"seek") as RemoteSeekController).active);assertFalse(f.port().playWhenReady);assertEquals(true,field(f.panel,"paused"))
                f.panel.handleKey(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_RIGHT))
                assertTrue(kotlin.math.abs(f.port().currentPosition-origin)<1000)
            }
        }
    }
}

package io.github.loliwolf.openlisttv.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import android.database.sqlite.SQLiteDatabase
import io.github.loliwolf.openlisttv.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AdvancedIndexTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val session = Session(Site("s", "fixture", "https://example.invalid/", "reader", false), "not-persisted")
    private val shuffle = SlideshowOptions(TraversalMode.SHUFFLE)
    private fun image(i: Int) = Entry("/folder/$i.jpg", "$i.jpg", false, i.toLong(), "2026-09-12T00:00:00Z", "do-not-cache", i, "do-not-cache")
    private fun fixture(block: (Catalog, String) -> Unit) {
        val name="advanced-index-${UUID.randomUUID()}.db"
        try { Catalog(context,name).use { block(it,name) } } finally { context.deleteDatabase(name) }
    }
    private fun ready(c: Catalog, key: String = "key", now: Long = 1_000_000L, options: SlideshowOptions = shuffle, from: Int = 0, total: Int = 200): Long {
        val lease=c.reserveScan(); c.openScan(lease,"/folder","s",key,options,now)
        assertTrue(c.scanView(lease).scanning)
        (from until from+total).chunked(500).forEach { c.appendScan(lease,"s","/folder",it.map(::image),options) }
        c.finishScan(lease,null,now); return lease
    }
    @Test fun allSortsNullsAndNaturalTiesMatchLegacyOrderAcrossCursorPages() = fixture { c,_ ->
        val entries=(0..260).map { i ->
            val name=when(i%7) {0->"Photo${i/7}.JPG";1->"photo0${i/7}.jpg";else->"图${i}100%_'.jpg"}
            Entry("/folder/$name",name,i%11==0,if(i%9==0) -1 else (i%13).toLong(),if(i%5==0) "" else "2026-09-${(i%12+1).toString().padStart(2,'0')}T00:00:00Z",ordinal=i)
        }
        c.begin("s","/folder"); c.insert("s","/folder",entries); c.finish("s","/folder",entries.size)
        for(field in SortField.entries) for(desc in listOf(false,true)) for(folders in listOf(false,true)) {
            val sort=SortOrder(field,desc,folders)
            val direction=if(desc) "DESC" else "ASC"
            val primary=when(field){ SortField.NAME->"sort_name $direction";SortField.SIZE->"size IS NULL,size $direction";SortField.MODIFIED->"modified_at IS NULL,modified_at $direction";SortField.SERVER->"ordinal ASC" }
            val order=(if(folders) "directory DESC," else "")+"$primary,sort_name ASC,path ASC"
            val expected=c.readableDatabase.rawQuery("SELECT path FROM entries WHERE site='s' AND parent='/folder' ORDER BY $order",null).use { cursor->buildList {while(cursor.moveToNext()) add(cursor.getString(0))} }
            val actual=buildList { for(offset in 0..260 step 17) { addAll(c.page("s","/folder",offset,17,sort).entries.map {it.path}); if(offset>0) assertTrue("$sort offset=$offset",c.lastPageUsedCursor) } }
            assertEquals(sort.toString(),expected,actual)
        }
        val query=BrowserQuery("%_'",BrowserType.IMAGE)
        val expected=c.page("s","/folder",0,100,SortOrder(),query=query)
        val rest=c.page("s","/folder",100,100,SortOrder(),query=query)
        assertTrue(c.lastPageUsedCursor); assertEquals(expected.total,(expected.entries+rest.entries).size)
    }
    @Test fun anchorsInvalidateAfterRefreshAndUnseenJumpsFallBackSafely() = fixture { c,_ ->
        c.begin("s","/folder"); c.insert("s","/folder",(0..399).map(::image)); c.finish("s","/folder",400)
        c.page("s","/folder",300,100,SortOrder()); assertFalse(c.lastPageUsedCursor)
        c.page("s","/folder",0,100,SortOrder()); c.page("s","/folder",100,100,SortOrder()); assertTrue(c.lastPageUsedCursor)
        c.begin("s","/folder"); c.insert("s","/folder",(500..899).map(::image)); c.finish("s","/folder",400)
        val page=c.page("s","/folder",100,100,SortOrder())
        assertFalse(c.lastPageUsedCursor); assertEquals("600.jpg",page.entries.first().name)
    }
    @Test fun deepPageUsesIndexRangesWithoutTempSort() = fixture { c,_ ->
        c.begin("s","/folder"); (0 until 30_000).chunked(500).forEach {c.insert("s","/folder",it.map(::image))}; c.finish("s","/folder",30_000)
        val sort=SortOrder()
        c.page("s","/folder",29_800,100,sort)
        val start=SystemClock.elapsedRealtime()
        val result=c.page("s","/folder",29_900,100,sort)
        val elapsed=SystemClock.elapsedRealtime()-start
        assertTrue(c.lastPageUsedCursor); assertEquals("29900.jpg",result.entries.first().name)
        val plan=c.readableDatabase.rawQuery("EXPLAIN QUERY PLAN SELECT * FROM entries WHERE site='s' AND parent='/folder' AND directory=0 AND sort_name>? ORDER BY directory DESC,sort_name,path LIMIT 100",arrayOf(NaturalOrder.key("29900.jpg"))).use { cursor->buildList {while(cursor.moveToNext())add(cursor.getString(3))}.joinToString() }
        assertTrue(plan,plan.contains("browse_seek")); assertFalse(plan,plan.contains("TEMP B-TREE"))
        println("ADVANCED_INDEX deepCursorMs=$elapsed plan=$plan")
    }
    @Test fun freshCachePersistsAndResumesRoundsWithoutScanning() = fixture { c,name ->
        val first=ready(c)
        val consumed=(1..50).map {c.pickScanned(first)!!.path}.toSet()
        c.cancelScan(first)
        Catalog(context,name).use { reopened ->
            val lease=reopened.reserveScan()
            val view=reopened.openScan(lease,"/folder","s","key",shuffle,1_000_100)
            assertTrue(view.reused); assertFalse(view.scanning); assertEquals(200,view.count)
            val next=(1..150).map {reopened.pickScanned(lease)!!.path}.toSet()
            assertEquals(150,next.size); assertTrue(consumed.intersect(next).isEmpty())
        }
    }
    @Test fun staleRefreshKeepsOldBankUntilAtomicCommitAndCancellationCannotPublish() = fixture { c,_ ->
        ready(c)
        val stale=1_000_000L+ScanIndex.FRESH_MS+1
        var lease=c.reserveScan(); c.openScan(lease,"/folder","s","key",shuffle,stale)
        assertTrue(c.scanView(lease).refreshing)
        c.appendScan(lease,"s","/folder",(500..599).map(::image),shuffle)
        assertTrue(c.pickScanned(lease)!!.name.substringBefore('.').toInt()<200)
        c.cancelScan(lease)
        lease=c.reserveScan(); c.openScan(lease,"/folder","s","key",shuffle,stale+1)
        assertEquals(200,c.scanView(lease).count)
        c.appendScan(lease,"s","/folder",(1000..1099).map(::image),shuffle); c.finishScan(lease,null,stale+1)
        assertEquals(100,c.scanView(lease).count)
        repeat(100) {assertTrue(c.pickScanned(lease)!!.name.substringBefore('.').toInt()>=1000)}
    }
    @Test fun failedAndBudgetLimitedRefreshDoNotReplaceOrRenewSnapshot() = fixture { c,_ ->
        ready(c)
        val stale=1_000_000L+ScanIndex.FRESH_MS+1
        var lease=c.reserveScan(); c.openScan(lease,"/folder","s","key",shuffle,stale)
        c.appendScan(lease,"s","/folder",listOf(image(999)),shuffle); c.finishScan(lease,"budget fixture",stale)
        assertEquals(200,c.scanView(lease).count); assertNotNull(c.scanView(lease).limited)
        lease=c.reserveScan(); c.openScan(lease,"/folder","s","key",shuffle,stale+1)
        assertTrue(c.scanView(lease).scanning); c.failedScan(lease)
        lease=c.reserveScan(); c.openScan(lease,"/folder","s","key",shuffle,stale+2)
        assertEquals(200,c.scanView(lease).count); assertTrue(c.scanView(lease).refreshing)
    }
    @Test fun ttlForceSettingsAndIdentityPreventWrongCacheReuse() = fixture { c,_ ->
        for ((options,key,time) in listOf(Triple(shuffle.copy(forceRescan=true),"key",1_000_100L), Triple(shuffle.copy(reuseIndex=false),"key",1_000_100L),
            Triple(shuffle,"different",1_000_100L), Triple(shuffle,"key",1_000_000L+ScanIndex.STALE_MS+1), Triple(shuffle,"key",999_999L))) {
            ready(c,options=shuffle.copy(forceRescan=true))
            val lease=c.reserveScan(); val view=c.openScan(lease,"/folder","s",key,options,time)
            assertFalse(view.reused); assertTrue(view.scanning); assertEquals(0,view.count)
        }
        val gateway=object:FileGateway {
            override suspend fun list(session:Session,path:String,password:String,page:Int,perPage:Int):Listing=error("Not needed")
            override suspend fun resolve(session:Session,entry:Entry,password:String):MediaSource=error("Not needed")
        }
        val repo=FileRepository(session,gateway,c); val key=repo.scanIdentity("/folder",shuffle)
        assertNotEquals(key,FileRepository(session.copy(site=session.site.copy(username="another")),gateway,c).scanIdentity("/folder",shuffle))
        assertNotEquals(key,FileRepository(session.copy(basePath="/other"),gateway,c).scanIdentity("/folder",shuffle))
        assertNotEquals(key,repo.scanIdentity("/folder",shuffle.copy(imageOnly=true)))
        repo.remember("/folder","private-password"); assertNull(repo.scanIdentity("/folder",shuffle))
    }
    @Test fun millionItemReservoirStaysAtTenThousandAndIncludesLateDirectories() = fixture { c,_ ->
        val options=SlideshowOptions(TraversalMode.RESERVOIR)
        val lease=c.reserveScan(); c.openScan(lease,"/folder","s","sample",options,1_000_000)
        val start=SystemClock.elapsedRealtime()
        for (startAt in 0 until 1_000_000 step 500) {
            assertFalse(c.appendScan(lease,"s","/folder",(startAt until startAt+500).map(::image),options))
            assertTrue(c.scannedCount(lease)<=10_000)
        }
        c.finishScan(lease,null,1_000_001)
        assertEquals(10_000,c.scannedCount(lease)); assertEquals(1_000_000L,c.scanView(lease).seen)
        val ranges=c.readableDatabase.rawQuery("SELECT MIN(CAST(substr(path,9) AS INTEGER)),MAX(CAST(substr(path,9) AS INTEGER)),COUNT(DISTINCT path) FROM candidate_0",null).use {it.moveToFirst();Triple(it.getInt(0),it.getInt(1),it.getInt(2))}
        assertTrue(ranges.toString(),ranges.first<10000 && ranges.second>990000); assertEquals(10000,ranges.third)
        repeat(100) {assertNotNull(c.pickScanned(lease))}
        println("ADVANCED_INDEX reservoirMillionMs=${SystemClock.elapsedRealtime()-start} candidates=10000 ranges=$ranges")
    }
    @Test fun completedTraversalReusesSnapshotWithoutGatewayCalls() = fixture { c,_ -> runBlocking {
        var requests=0
        val gateway=object:FileGateway {
            override suspend fun list(session:Session,path:String,password:String,page:Int,perPage:Int):Listing {requests++;return Listing((0..49).map(::image),50)}
            override suspend fun resolve(session:Session,entry:Entry,password:String):MediaSource=error("Not needed")
        }
        val repo=FileRepository(session,gateway,c)
        var source=ShuffleTraversal("/folder",repo,shuffle,this)
        try {withTimeout(5000) {source.next();while(!source.description.contains("扫描完成")){delay(25);source.next()}}} finally{source.close()}
        val before=requests
        source=ShuffleTraversal("/folder",repo,shuffle,this)
        try {assertNotNull(withTimeout(2000){source.next()});assertTrue(source.description.contains("复用"));assertEquals(before,requests)} finally{source.close()}
    } }
    @Test fun v3UpgradeKeepsConfigTablesAndAddsEmptySnapshotBanks() {
        val name="v3-upgrade-${UUID.randomUUID()}.db"
        try {
            // A genuine v3 schema: remove only newly-created v4 empty tables from this
            // isolated test DB, then exercise the normal SQLiteOpenHelper upgrade.
            Catalog(context,name).use { c ->
                c.begin("s","/folder");c.insert("s","/folder",listOf(image(1)));c.finish("s","/folder",1);c.savePosition("s",image(1),1234)
                c.writableDatabase.apply {execSQL("DROP TABLE scan_snapshot");execSQL("DROP TABLE candidate_0");execSQL("DROP TABLE candidate_1");version=3}
            }
            Catalog(context,name).use { c ->assertEquals(4,c.readableDatabase.version);assertEquals(1234L,c.position("s",image(1)));assertEquals(1,c.page("s","/folder",0,100,SortOrder()).total)}
        } finally{context.deleteDatabase(name)}
    }
    @Test fun optionalPagingIndexQuotaFailureKeepsRowsAndCorrectFallback() {
        val name="paging-quota-${UUID.randomUUID()}.db"
        try { Catalog(context,name,byteLimit=1024L*1024).use { c ->
            c.begin("s","/folder"); c.insert("s","/folder",(0..499).map(::image)); c.finish("s","/folder",500)
            val db=c.writableDatabase
            db.execSQL("CREATE TABLE fixture_padding(bytes BLOB)")
            while(true) { try {db.execSQL("INSERT INTO fixture_padding VALUES(zeroblob(8192))")} catch(_:android.database.sqlite.SQLiteFullException) {break} }
            val result=c.page("s","/folder",0,100,SortOrder(SortField.SIZE,true))
            assertEquals(500,result.total);assertEquals("499.jpg",result.entries.first().name)
            val next=c.page("s","/folder",100,100,SortOrder(SortField.SIZE,true))
            assertEquals("399.jpg",next.entries.first().name)
        } } finally{context.deleteDatabase(name)}
    }
}

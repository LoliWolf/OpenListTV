package io.github.loliwolf.openlisttv.data

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.loliwolf.openlisttv.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpgradeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun v1DatabaseAddsThumbnailWithoutDroppingRecordsOrPositions() = databaseUpgrade(1)
    @Test fun v2DatabaseAddsCompactScanMetadataWithoutDroppingRecordsOrPositions() = databaseUpgrade(2)
    private fun databaseUpgrade(version: Int) {
        val name = "upgrade-fixture-$version.db"; context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            val thumbnail = if (version >= 2) ",thumbnail TEXT NOT NULL DEFAULT ''" else ""
            db.execSQL("CREATE TABLE entries(site TEXT,path TEXT,parent TEXT,name TEXT,directory INTEGER,kind TEXT,size INTEGER,modified TEXT,modified_at INTEGER,sign TEXT,ordinal INTEGER,sort_name TEXT$thumbnail,PRIMARY KEY(site,path))")
            db.execSQL("CREATE TABLE directories(site TEXT,path TEXT,complete INTEGER,total INTEGER,updated INTEGER,PRIMARY KEY(site,path))")
            db.execSQL("CREATE TABLE progress(site TEXT,path TEXT,modified TEXT,position INTEGER,updated INTEGER,PRIMARY KEY(site,path))")
            db.execSQL("CREATE TABLE scan_queue(path TEXT PRIMARY KEY,done INTEGER)")
            db.execSQL("CREATE TABLE scan_items(path TEXT PRIMARY KEY,shuffle_key INTEGER,round INTEGER)")
            val oldThumbnail = if (version >= 2) ",'old-thumb'" else ""
            db.execSQL("INSERT INTO entries VALUES('old','/movie.mp4','/','movie.mp4',0,'VIDEO',5000000000,'2026-09-06',0,'',0,'movie.mp4'$oldThumbnail)")
            db.execSQL("INSERT INTO scan_items VALUES('/movie.mp4',1,0)")
            db.execSQL("INSERT INTO progress VALUES('old','/movie.mp4','2026-09-06',123000,1)")
            db.version = version
        }
        Catalog(context, name).use { catalog ->
            val e = catalog.page("old", "/", 0, 100, SortOrder()).entries.single()
            assertEquals(if (version >= 2) "old-thumb" else "", e.thumbnail); assertEquals(5_000_000_000, e.size)
            assertEquals(3, catalog.readableDatabase.version)
            catalog.readableDatabase.rawQuery("SELECT size,modified FROM scan_items", null).use {
                assertTrue(it.moveToFirst()); assertEquals(-1L, it.getLong(0)); assertEquals("", it.getString(1))
            }
            assertEquals(123000L, catalog.position("old", e))
            catalog.clear("old")
            assertEquals(123000L, catalog.position("old", e))
            catalog.clear("old", clearHistory = true)
            assertEquals(0L, catalog.position("old", e))
        }
        context.deleteDatabase(name)
    }
    @Test fun preferencesAndEncryptedSessionSurviveStoreRecreation() {
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("upgrade-test-$name", mode)
        }
        val site = Site("upgrade", "Saved server", "https://example.com/", "reader", false)
        val first = SiteStore(isolated)
        first.save(Session(site, "fixture-session"))
        first.setSort(site, SortOrder(SortField.SIZE, true, false))
        val options = SlideshowOptions(TraversalMode.WALK, false, true, 17, 23, 0, false)
        first.setSlideshow(options)
        val restored = SiteStore(isolated)
        assertEquals(site, restored.active()); assertEquals("fixture-session", restored.session(site)?.token)
        assertEquals(SortOrder(SortField.SIZE, true, false), restored.sort(site)); assertEquals(options, restored.slideshow())
        restored.remove(site)
    }
}

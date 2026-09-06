package io.github.loliwolf.openlisttv

import android.app.Application
import io.github.loliwolf.openlisttv.data.Catalog
import kotlin.concurrent.thread

class TvApplication : Application() {
    // One process-lifetime database, never an Activity-owned connection closed under an IO job.
    val catalog by lazy { Catalog(this) }
    override fun onCreate() {
        super.onCreate()
        // Snapshot before any browser is created: never delete a live browser's cache.
        // A killed process cannot run loader.close(), so sweep only its disposable previews.
        val abandoned = cacheDir.resolve("thumbnails").listFiles().orEmpty()
        if (abandoned.isNotEmpty()) thread(name = "thumbnail-cache-cleanup", isDaemon = true) {
            abandoned.forEach { it.deleteRecursively() }
        }
    }
}

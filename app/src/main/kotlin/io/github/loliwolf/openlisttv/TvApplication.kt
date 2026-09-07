package io.github.loliwolf.openlisttv

import android.app.Application
import io.github.loliwolf.openlisttv.data.Catalog
import io.github.loliwolf.openlisttv.data.DownloadStore
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

class TvApplication : Application() {
    // One process-lifetime database, never an Activity-owned connection closed under an IO job.
    val catalog by lazy { Catalog(this) }
    val downloads by lazy { DownloadStore(this) }
    // Keep the single writer slot across Activity recreation. No jobs exist when idle;
    // MainActivity.onStop cancels the current task, including its socket/file writer.
    val transfers by lazy { DownloadController(this, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), downloads) }
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

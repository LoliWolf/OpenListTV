package io.github.loliwolf.openlisttv

import android.os.SystemClock
import io.github.loliwolf.openlisttv.core.AcceleratingSeek
import kotlinx.coroutines.*

/** Update only the timeline while held; perform a single decoder/network seek on release. */
class RemoteSeekController(private val scope: CoroutineScope, private val port: () -> Port?, private val preview: (AcceleratingSeek?) -> Unit) {
    interface Port {
        val position: Long
        val duration: Long
        val seekable: Boolean
        var playWhenReady: Boolean
        fun seekTo(position: Long)
    }
    private var model: AcceleratingSeek? = null
    private var currentPort: Port? = null
    private var key: Int? = null
    private var wasPlaying = false
    private var ticker: Job? = null
    val active get() = model != null
    fun press(keyCode: Int, direction: Int): Boolean {
        val now = SystemClock.elapsedRealtime()
        model?.let { key = keyCode; it.reverse(direction, now); preview(it); return true }
        val p = port() ?: return false
        if (!p.seekable || p.duration <= 0) return false
        currentPort = p; key = keyCode; wasPlaying = p.playWhenReady
        p.playWhenReady = false
        model = AcceleratingSeek(p.position, p.duration, direction, now)
        preview(model)
        ticker = scope.launch {
            while (isActive) {
                delay(100)
                val current = model ?: break
                if (!p.seekable || p.duration <= 0) { this@RemoteSeekController.cancel(); break }
                current.advance(SystemClock.elapsedRealtime()); preview(current)
            }
        }
        return true
    }
    fun release(keyCode: Int, canceled: Boolean = false): Boolean {
        if (key != keyCode || model == null) return false
        finish(commit = !canceled, restorePlayback = true); return true
    }
    fun cancel(restorePlayback: Boolean = true) { finish(false, restorePlayback) }
    private fun finish(commit: Boolean, restorePlayback: Boolean) {
        val current = model ?: return
        val p = currentPort
        current.advance(SystemClock.elapsedRealtime())
        ticker?.cancel(); ticker = null; model = null; currentPort = null; key = null
        if (commit && p?.seekable == true && p.duration > 0) p.seekTo(current.target.coerceIn(0, p.duration))
        if (restorePlayback && p != null) p.playWhenReady = wasPlaying
        preview(null)
    }
}

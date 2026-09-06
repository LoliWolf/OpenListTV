package io.github.loliwolf.openlisttv.media

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import io.github.loliwolf.openlisttv.core.*
import okhttp3.OkHttpClient

@UnstableApi
class MediaEngine(context: Context, http: OkHttpClient, private val events: Events) {
    interface Events {
        fun ready()
        fun ended()
        fun failed(message: String, sourceExpired: Boolean = false, kind: FailureKind = FailureKind.UNSUPPORTED)
        fun qualityChanged(message: String)
    }
    private val selector = DefaultTrackSelector(context)
    private var currentKind = MediaKind.OTHER
    private var failed = false
    private var maxHeight = 2160
    private val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true).setMediaCodecSelector { mime, secure, tunneling ->
        MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).filter { !mime.startsWith("video/") || it.hardwareAccelerated }
    }
    val player: ExoPlayer = ExoPlayer.Builder(context, renderers)
        .setTrackSelector(selector)
        .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(http)))
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(2000, 10000, 500, 1000)
            .setTargetBufferBytes(Budgets.PLAYER_BUFFER_BYTES).setPrioritizeTimeOverSizeThresholds(false).setBackBuffer(0, false).build())
        .build().apply {
            setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (failed) return
                    when (state) { Player.STATE_READY -> events.ready(); Player.STATE_ENDED -> events.ended() }
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (failed) return
                    if (error.errorCode in 4000..4999 && lowerTrack()) { events.qualityChanged("正在切换低清播放源…"); return }
                    val httpCode = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
                    val kind = when {
                        httpCode == 429 -> FailureKind.RATE_LIMIT
                        httpCode == 404 -> FailureKind.SERVER
                        httpCode != null && httpCode >= 500 -> FailureKind.NETWORK
                        error.errorCode in 2001..2002 -> FailureKind.NETWORK
                        else -> FailureKind.UNSUPPORTED
                    }
                    fail(if (error.errorCode in 4000..4999) "设备无法硬件解码，且没有可用低清源；已停止此资源" else "媒体读取失败，请检查网络或刷新资源", httpCode in listOf(401, 403), kind)
                }
                override fun onTracksChanged(tracks: Tracks) {
                    if (failed) return
                    val selectedVideo = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { g -> (0 until g.length).filter { g.isTrackSelected(it) }.map { g.getTrackFormat(it) } }
                    if (selectedVideo.any { it.colorInfo?.colorTransfer in listOf(C.COLOR_TRANSFER_ST2084, C.COLOR_TRANSFER_HLG) }) { fail("一期不支持 HDR，请使用 SDR 版本"); return }
                    if (currentKind == MediaKind.VIDEO && tracks.groups.isNotEmpty() && selectedVideo.isEmpty()) fail("没有设备可解码的视频轨道")
                }
            })
        }
    init { androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF); setVideoLimit(2160) }
    private fun setVideoLimit(height: Int) {
        maxHeight = height
        selector.parameters = selector.buildUponParameters().setMaxVideoSize(if (height == 2160) 3840 else if (height == 1080) 1920 else 1280, height)
            .setExceedVideoConstraintsIfNecessary(false).setExceedRendererCapabilitiesIfNecessary(false).build()
    }
    fun play(source: MediaSource, position: Long = 0) {
        failed = false; currentKind = source.entry.kind; setVideoLimit(2160)
        val item = MediaItem.Builder().setUri(source.url).setMediaId(source.entry.path)
        if (source.entry.name.endsWith(".m3u8", true)) item.setMimeType("application/x-mpegURL")
        if (source.subtitles.isNotEmpty()) item.setSubtitleConfigurations(source.subtitles.map { MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(it.url)).setMimeType(it.mime).setLabel(it.name).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build() })
        player.setMediaItem(item.build(), position)
        player.prepare(); player.playWhenReady = true
    }
    private fun lowerTrack(): Boolean {
        val target = if (maxHeight > 1080) 1080 else if (maxHeight > 720) 720 else return false
        val available = player.currentTracks.groups.any { g -> g.type == C.TRACK_TYPE_VIDEO && (0 until g.length).any { i -> val f = g.getTrackFormat(i); f.height in 1..target && g.isTrackSupported(i) } }
        if (!available) return false
        setVideoLimit(target); player.prepare(); player.playWhenReady = true
        return true
    }
    fun trimMemory() { if (currentKind == MediaKind.VIDEO && !lowerTrack()) fail("设备可用资源不足，且没有低清播放源") }
    private fun fail(message: String, expired: Boolean = false, kind: FailureKind = FailureKind.UNSUPPORTED) { if (!failed) { failed = true; player.stop(); events.failed(message, expired, kind) } }
    fun stop() { player.stop(); player.clearMediaItems() }
    fun release() { player.release() }
}

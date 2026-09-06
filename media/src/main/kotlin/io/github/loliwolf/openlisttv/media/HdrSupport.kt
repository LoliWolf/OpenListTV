package io.github.loliwolf.openlisttv.media

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Build
import android.view.WindowManager
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi

/** Informational output capability. MediaCodec/Media3 still decides actual decode support. */
@UnstableApi
object HdrSupport {
    enum class Range(val label: String, val displayType: Int) { SDR("SDR", 0), HDR10("HDR10/PQ", 2), HLG("HLG", 3), DOLBY_VISION("Dolby Vision", 1) }
    fun range(format: Format) = when {
        format.sampleMimeType == "video/dolby-vision" || format.codecs?.split(',')?.any { it.trim().startsWith("dvhe") || it.trim().startsWith("dvh1") || it.trim().startsWith("dva1") || it.trim().startsWith("dvav") } == true -> Range.DOLBY_VISION
        format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> Range.HDR10
        format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> Range.HLG
        else -> Range.SDR
    }
    @Suppress("DEPRECATION")
    fun displayTypes(context: Context): Set<Int> {
        if (Build.VERSION.SDK_INT < 24) return emptySet()
        val display = (context as? Activity)?.window?.decorView?.display
            ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
        return runCatching { display?.hdrCapabilities?.supportedHdrTypes?.toSet().orEmpty() }.getOrDefault(emptySet())
    }
    fun supports(range: Range, types: Set<Int>) = range == Range.SDR || range.displayType in types || range == Range.HDR10 && 4 in types
    fun canRenderHdrImages(context: Context) = Build.VERSION.SDK_INT >= 34 && displayTypes(context).isNotEmpty()
    fun bitmapBytes(bitmap: Bitmap): Long = bitmap.allocationByteCount.toLong() + if (Build.VERSION.SDK_INT >= 34 && bitmap.hasGainmap()) bitmap.gainmap?.gainmapContents?.allocationByteCount?.toLong() ?: 0L else 0L
}

/** Opt into HDR bitmap rendering once per player session, not at every slide boundary. */
@UnstableApi
class HdrImageWindow(private val activity: Activity) {
    private val original = if (Build.VERSION.SDK_INT >= 26) activity.window.colorMode else 0
    private var enabled = false
    fun display(bitmap: Bitmap) {
        if (Build.VERSION.SDK_INT >= 34 && !enabled && bitmap.hasGainmap() && HdrSupport.canRenderHdrImages(activity)) {
            enabled = runCatching { activity.window.colorMode = ActivityInfo.COLOR_MODE_HDR; true }.getOrDefault(false)
        }
    }
    fun restore() { if (Build.VERSION.SDK_INT >= 26 && enabled) { activity.window.colorMode = original; enabled = false } }
}

package io.github.loliwolf.openlisttv

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import io.github.loliwolf.openlisttv.TvUi.dp
import io.github.loliwolf.openlisttv.core.SlideshowOptions
import io.github.loliwolf.openlisttv.core.TraversalMode

/** Edit a private draft; only Start commits it. Opening or cancelling a subpanel does no IO. */
class SlideshowSetup(private val context: Context, path: String, initial: SlideshowOptions, begin: (SlideshowOptions) -> Unit) {
    private var draft = initial.copy(forceRescan = false)
    private val body = UiPanels.form(context)
    private val mode = UiPanels.Preference(context, "播放方式", "", "") { chooseMode() }
    private val image = UiPanels.Preference(context, "图片停留时间", "", "") {
        number("图片停留秒数（1–3600）", draft.imageSeconds, 1..3600, "秒", "慢网时保留当前图，下一张准备好再切换。") { draft = draft.copy(imageSeconds = it); update() }
    }.compact()
    private val media = UiPanels.Preference(context, "视频与音频时长", "", "") { mediaDuration() }.compact()
    private val stop = UiPanels.Preference(context, "定时停止", "", "") {
        number("定时停止轮播（0 表示关闭）", draft.stopAfterMinutes, 0..1440, "分钟", "暂停和加载也计时；后台不唤醒，返回时检查。") { draft = draft.copy(stopAfterMinutes = it); update() }
    }.compact()
    private val advanced = UiPanels.Preference(context, "索引与高级选项", "", "") { advanced() }.compact()
    val dialog: AlertDialog
    init {
        body.addView(TvUi.text(context, path, 13f, TvUi.muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setPadding(context.dp(16), 0, context.dp(16), context.dp(8)) })
        body.addView(mode)
        val recursive = UiPanels.toggle(context, "包含子目录", draft.recursive) { draft = draft.copy(recursive = it) }.apply { minimumHeight = context.dp(48) }
        val images = UiPanels.toggle(context, "仅图片", draft.imageOnly) { draft = draft.copy(imageOnly = it) }.apply { minimumHeight = context.dp(48) }
        body.addView(recursive); body.addView(images)
        body.addView(UiPanels.section(context, "播放节奏").apply { setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(6)) })
        body.addView(image); body.addView(media); body.addView(stop)
        body.addView(advanced)
        dialog = AlertDialog.Builder(context).setTitle("开始轮播").setView(UiPanels.scroll(context, body))
            .setPositiveButton("开始") { _, _ -> begin(draft) }.setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            TvUi.verticalFocus(mode, recursive, images, image, media, stop, advanced, dialog.getButton(AlertDialog.BUTTON_POSITIVE))
            mode.requestFocus(); UiPanels.fit(dialog)
        }
        update()
    }
    private fun update() {
        mode.value(draft.mode.label)
        mode.description(when (draft.mode) {
            TraversalMode.ORDERED -> "按目录顺序播放"
            TraversalMode.WALK -> "在目录间随机探索，非全库均匀"
            TraversalMode.SHUFFLE -> "完成扫描后，每轮内不重复"
            TraversalMode.RESERVOIR -> "最多一万候选，可能重复，不保证全部播到"
        })
        image.value("${draft.imageSeconds} 秒")
        media.value(if (draft.videoSeconds == 0 && draft.audioSeconds == 0) "播完" else "已限制")
        stop.value(if (draft.stopAfterMinutes == 0) "关闭" else "${draft.stopAfterMinutes} 分钟")
        advanced.value(if (draft.autoRefreshMinutes == 0) "自动刷新关闭" else "${draft.autoRefreshMinutes} 分钟刷新")
    }
    private fun chooseMode() {
        val descriptions = listOf("按目录依次播放，可包含子目录", "快速开始；不等同全库均匀随机", "边扫描边播，完成后逐轮抽取", "持续扫描、均匀入选一万候选；可能重复")
        UiPanels.show(UiPanels.menu(context, "播放方式", TraversalMode.entries.mapIndexed { i, mode ->
            UiPanels.Action(mode.label, (if (mode == draft.mode) "当前选择 · " else "") + descriptions[i]) { draft = draft.copy(mode = mode); update() }
        }))
    }
    private fun number(label: String, current: Int, range: IntRange, unit: String, note: String, changed: (Int) -> Unit) {
        val form = UiPanels.form(context)
        val input = TvUi.input(context, label, current.toString(), unit).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        form.addView(input); form.addView(TvUi.text(context, note, 13f, TvUi.muted))
        val prompt = AlertDialog.Builder(context).setTitle(label.substringBefore('（')).setView(form).setPositiveButton("应用", null).setNegativeButton("取消", null).create()
        UiPanels.show(prompt, input)
        TvUi.verticalFocus(input, prompt.getButton(AlertDialog.BUTTON_POSITIVE))
        prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString().toIntOrNull()
            if (value == null || value !in range) input.error = "请输入 ${range.first}–${range.last}"
            else { changed(value); prompt.dismiss() }
        }
    }
    private fun mediaDuration() {
        val form = UiPanels.form(context)
        val video = TvUi.input(context, "视频秒数（0 表示播放到结束）", draft.videoSeconds.toString(), "秒").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val audio = TvUi.input(context, "音频秒数（0 表示播放到结束）", draft.audioSeconds.toString(), "秒").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        form.addView(video); form.addView(audio); form.addView(TvUi.text(context, "0 为播完，1–3600 为单项最多播放秒数。", 13f, TvUi.muted))
        val prompt = AlertDialog.Builder(context).setTitle("视频与音频时长").setView(form).setPositiveButton("应用", null).setNegativeButton("取消", null).create()
        UiPanels.show(prompt, video); TvUi.verticalFocus(video, audio, prompt.getButton(AlertDialog.BUTTON_POSITIVE))
        prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val v = video.text.toString().toIntOrNull(); val a = audio.text.toString().toIntOrNull()
            when {
                v == null || v !in 0..3600 -> video.error = "请输入 0–3600 秒"
                a == null || a !in 0..3600 -> audio.error = "请输入 0–3600 秒"
                else -> { draft = draft.copy(videoSeconds = v, audioSeconds = a); update(); prompt.dismiss() }
            }
        }
    }
    private fun advanced() {
        val form = UiPanels.form(context)
        val reuse = UiPanels.toggle(context, "复用轮播索引（6 小时）", draft.reuseIndex) {}
        val force = UiPanels.toggle(context, "本次重新扫描（忽略旧快照）", draft.forceRescan) {}
        val interval = TvUi.input(context, "自动刷新索引（0 关闭，5–1440 分钟）", draft.autoRefreshMinutes.toString(), "分钟").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val prefetch = UiPanels.toggle(context, "预取音视频地址", draft.prefetch) {}
        form.addView(UiPanels.section(context, "全树随机 / 公平抽样"))
        form.addView(reuse); form.addView(force); form.addView(interval)
        form.addView(TvUi.text(context, "先播放旧索引，再在前台刷新；成功后切换，不打断当前画面。新增文件仍受服务端缓存影响。", 13f, TvUi.muted).apply { setPadding(context.dp(16), 0, context.dp(16), context.dp(12)) })
        form.addView(prefetch)
        form.addView(TvUi.text(context, "图片始终自动预加载一张。关闭此项只停止预取音视频地址。", 13f, TvUi.muted).apply { setPadding(context.dp(16), 0, context.dp(16), context.dp(8)) })
        val prompt = AlertDialog.Builder(context).setTitle("索引与高级选项").setView(UiPanels.scroll(context, form)).setPositiveButton("应用", null).setNegativeButton("取消", null).create()
        UiPanels.show(prompt, reuse); TvUi.verticalFocus(reuse, force, interval, prefetch, prompt.getButton(AlertDialog.BUTTON_POSITIVE))
        prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val minutes = interval.text.toString().toIntOrNull()
            if (minutes == null || minutes != 0 && minutes !in 5..1440) interval.error = "请输入 0 或 5–1440 分钟"
            else {
                draft = draft.copy(reuseIndex = reuse.isChecked, forceRescan = force.isChecked, autoRefreshMinutes = minutes, prefetch = prefetch.isChecked)
                update(); prompt.dismiss()
            }
        }
    }
}

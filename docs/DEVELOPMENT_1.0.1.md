# 1.0.1 本地迭代上下文

分支：`dev/1.0.1`。测试包版本：`1.0.1-preview.2`，versionCode 6。原 preview.1 / code 5 APK 保留，不覆盖。

**用户要求：等负责人实测确认后再发布。该分支、APK、标签和 Release 均不得推送或上传 GitHub。** 目前公开稳定版仍是 1.0.0；仓库改名及 1.0.0 文案清理已单独完成，不属于新版本发布。

## 本次改动

- GitHub 更新检测：登录、连接失败和设置页有手动入口；每天最多一次前台自动检测，可关闭。仅访问公开 latest Release，不带 OpenList 凭证，不接受草稿/预发布或非官方下载链接，不自动安装。
- HDR 视频：删除按 HDR 标记一律拒绝的逻辑，保留色彩元数据，使用 Media3/MediaCodec/SurfaceView 原生链路。显示设备无相应 HDR 能力且有可用 SDR 轨道时优先 SDR；实际解码失败仍按原策略处理。
- Ultra HDR 图片：Android 14+ 和 HDR 显示条件下保留 gainmap，包含方向变换和内存计数；低内存时回到 SDR 窗口并降采样。
- 图片输入上限提高到 128 MiB；下载只用小块流式写临时文件，解码位图预算不随文件上限增加。预留 8 MiB 磁盘余量。
- 单文件超限使用 RESOURCE_LIMIT，跳过该项，不再被误当成全局 CAPACITY 使轮播停住。磁盘/索引容量故障仍暂停；连续 10 个失败的保护规则保留。
- 列表缩略图的原图回退仍限 32 MiB；已有服务端 thumb 不受原图大小影响，避免为了小预览下载 128 MiB 原图。

## 继续工作时

preview.2 在上述功能上追加当前目录搜索/类型筛选、会话定时停止、播放信息快照、单任务前台下载及本机离线点播/导出。操作说明见 [预览版说明](PREVIEW_1.0.1.md)。Catalog 仍为 v2；新增独立 downloads.db v2，增量增加 source_key，下载副本不是缓存。

传输任务由 Application 持有唯一 slot，Activity.onStop 取消；播放前等待旧写入者完全退出。强 ETag、最终 URL 哈希和范围响应一致才续传；其他情况完整重下。完成必须流结束/长度校验及原子 rename，进程中断后不能把 .part 当成完成。取消后的 NonCancellable 范围必须覆盖 IO 返回和释放 slot。

先遵循 AGENTS.md 的签名、数据库和资源预算约束。新增代码入口：core/AppVersion、data/GithubUpdates、app/UpdateController、media/HdrSupport。测试入口：GithubUpdatesTest、UpdateAndFailureTest、HdrAndImageBudgetTest、HdrVideoTest、SeamlessSlideshowTest 和 tools/github-update-ui-smoke.mjs。

真实 HDR 样本仅放在忽略的 artifacts/hdr-fixtures，来自 AndroidX Media 固定提交 43e3af79dabb43a69badffbbdfa6d421a1cdb36c；不打包进 APK。HdrVideoTest 会检查硬解前置条件，缺硬解时属于跳过，不能计入播放通过。

发布前需用户确认，再递增正式版 versionCode、去掉预览后缀并同步公开指南。GitHub 正式资产命名为 `OpenListTV-<version>.apk`、标签为 `v<version>`，更新检测依此选择官方 APK；已发布 1.0.0 不替换或重打包。

# OpenList TV — 项目接续上下文

先读本文件即可开始工作；按任务需要再读下述源码或文档，不需要寻找已退役 PRD。文件采用标准大写 `AGENTS.md`，避免在大小写不敏感文件系统创建两份相互冲突的 agents.md。

## 当前产品与发布

- 名称 OpenList TV，原生 Android OpenList 客户端，优先电视、投影、遥控器，也支持真实横竖屏与触控。
- 当前版本 `1.0.0`，versionCode `4`，发布仓库 `https://github.com/LoliWolf/OpenListTV`，稳定 Release 标签 `v1.0.0`。
- 当前开发分支 `dev/1.0.1`，已公开预发布 `1.0.1-preview.7` / versionCode `11`；本地迭代 `1.0.1-preview.9` / versionCode `13`，加入索引快照复用、游标分页和可选公平抽样。用户的 preview.7 发布授权不延伸到之后版本：本轮只本地交付，不推送/建标签/发布。公开 preview.7 为 prerelease、非 Latest，稳定版仍为 v1.0.0。旧 APK/标签不覆盖，后续 versionCode 继续递增。
- 仓库已公开，v1.0.0 已发布为非 draft / 非 prerelease / Latest；发布源提交 ed452bffd4052d34948673e26d3e2c4e0c1e7d91 的 GitHub 构建检查通过。后续只改文档不重发 APK，不移动已发布标签或覆盖旧 Release 附件。
- 包名固定 `io.github.loliwolf.openlisttv`。正式 APK 由 `release` 构建，非 debuggable、开启 R8 与资源裁剪；不能把 debug APK 当正式包。
- 负责人于 2026-09-07 确认 1.0.0 已实测验收。公开文档保持产品功能与必要限制，不列待验证清单；历史工程记录保存在本机忽略的 artifacts/engineering-history 中，不编造额外测试结论。
- README 保持简洁：下载、主要功能、真实横版/竖版截图、飞书入口。用户指南在 `docs/USAGE.md`，开发签名在 `docs/BUILDING.md`。

## 用户明确约束

- 性能与占用优先，不引入 Flutter、WebView 壳、服务端、全量 FFmpeg/VLC 或后台常驻。
- Android 14 / 2 GB RAM 为主要目标，尽量兼容 API 23；最高 4K 按资源与硬件能力运行。1.0.1 新需求扩展 HDR：视频走 Media3/MediaCodec/SurfaceView，Ultra HDR 图片走系统 gainmap；不加入软件转码器。
- 账号密码、多站点保存/单活动站点、只读文件浏览、播放、排序及原三种轮播均保留；preview.9另增可选公平抽样模式。TOTP/SSO/访客等不是当前已实现能力。
- 无低清源且无法解码时轮播跳过，不做客户端实时转码；5 GB 视频可以流式播放，文件大小不等于运存需求。
- 方向与已有实现冲突或不符合最佳实践时，实施前向用户指出事实与取舍。
- 本项目没有 Nacos/MySQL 依赖。若后续任务确实涉及：Nacos 使用 API，配置来自 env；MySQL 使用 `dbx`，不要自行另建访问路径或暴露 env。
- 不擅自发布新版本、推送外部状态、修改公开权限或删除云端内容。只有用户明确授权的范围才执行；保留用户工作区改动。

## 架构与关键文件

| 模块 | 职责与主要入口 |
| --- | --- |
| core | 纯 Kotlin：Models、自然排序/路径、资源预算、AuthProvider/FileGateway、TraversalSource、PlaybackHistory、OneAhead、BrowserQuery/SlideshowDeadline |
| data | OpenListApi、SiteStore/Android Keystore、Catalog/SQLite、FileRepository、全树随机扫描、DownloadStore/FileDownloader/LocalDownloadGateway |
| media | 单 MediaEngine/ExoPlayer、ImagePipeline 串行采样、ThumbnailLoader 有界列表预览 |
| app | MainActivity 组合，BrowserList 虚拟列表，PlaybackPanel 生命周期，DownloadController/DownloadsPanel，TvUi/ActionFlow 原生响应式布局 |

app 依赖 core/data/media；data 与 media 只依赖 core，不互相依赖。Catalog 是 Application 生命周期的连接管理器，不随 Activity 关闭而中断 IO。

### 网络与身份

- OpenList 优先，协议联调基线是 v4.2.6 Local 驱动。其他驱动依靠服务端返回的媒体地址或代理能力接入。
- 账号密码走 `/api/auth/login/hash`。兼容哈希盐仍含上游 AList URL，不能因为项目改名擅自替换。
- API Authorization 是服务端原始 token，不凭习惯加 Bearer；每次同时检查 HTTP 状态与业务 code。
- 文件路径按用户相对根处理，不重复拼 base_path；媒体使用 fs/get 返回的 raw_url，不靠猜测 /d/ 或管理接口。
- API 与媒体客户端隔离；媒体每次重定向也移除 Authorization/Cookie/Proxy-Authorization。HTTPS 验证不关闭，HTTP 必须逐站点明确允许。
- 只保存加密会话，密码输入禁用保存；目录密码仅会话内、有界。日志、截图和 Issue 不出现密码、token、真实私密资源或完整签名 URL。

### 资源预算与播放

- 目录每页 100 条，UI/缩略图只保留当前页＋下一页，滚动后淘汰旧页。完整排序的元数据可索引全目录，不等于下载全目录图片。
- 索引数据库总计仍最多64 MiB；浏览完整排序最多10万条，普通全树随机每个候选区最多10万，公平抽样最多1万。刷新至多两个磁盘候选区共享同一64MiB上限，不增加位图预算。深度128、扫描目录累计10万上限仍提示范围受限，不声称无限库或全覆盖。
- 缩略图最多 2 并发，内存 LRU 8 MiB，PNG 磁盘缓存 16 MiB；无 thumb 时只为可见项串行生成原图预览。
- 1.0.1 图片临时输入最多 128 MiB，保留至少 8 MiB 磁盘余量；列表原图回退仍限 32 MiB，避免为小缩略图下载巨型原图。音视频缓冲目标仍为 24 MiB，只有一个播放器。
- 单文件超限属于 RESOURCE_LIMIT，轮播应跳过；真实磁盘/索引容量故障属于 CAPACITY，暂停。不能再次把图片超限归入全局容量故障。
- HDR 视频保留色彩元数据，由平台硬解输出。无匹配 HDR 显示能力时优先可用 SDR 轨道，否则使用系统显示链路；实际解码错误按原跳过策略处理。Ultra HDR gainmap 的内存也必须计入位图预算。
- 图片轮播自动下载并解码下一张，直接替换 ImageView，不先清空/显示加载文字。慢网保留当前图，下一张显示后重新计时。
- 双图目标预算 `min(64 MiB, JVM maxHeap / 4)`，还要预留解码/旋转空间；内存不足时降采样或暂停预解码。预算不是总 PSS 的替代。
- 已显示 Bitmap 交给 GC/HWUI 管理，不能在仍可能被绘制时 recycle。未显示的预加载由 OneAhead 明确拥有并在取消/替换时回收。
- OneAhead 只容纳一个任务/值；异常到实际消费时才处理，防止预加载失败取消整个播放会话。ImagePipeline 跨 IO dispatcher 返回时保留所有权，避免取消丢失位图。
- PlaybackHistory 的 peek 不移动游标；取消预加载不丢失随机模式已经预约的 Entry。旧 slide.prefetch 仅控制音视频地址，关闭它不影响图片自动预加载。
- 坏资源可跳过，连续 10 项失败或鉴权/限流/持续网络错误暂停。Home/休眠/退出取消任务、释放播放资源，回前台需要用户继续。
- 旋转由原生 View 重排，保留目录、媒体和计时状态；窄屏按钮换行、文件信息合并。不要再锁横屏，也不要裁横图冒充竖屏截图。

### preview.2 轻量功能

- 用户明确选定：当前目录搜索、类型筛选、定时停止轮播、按需播放信息、下载；没有授权上传、整库同步、后台下载或新发布。
- 搜索为文件名包含匹配（英文大小写不敏感，中文直接匹配），最多 128 字符；类型为全部/图片/视频/音频/文件夹/其他文件。SQL 参数化、`instr` 按字面处理 `%`/`_`/引号。不调用递归搜索 API，不逐键查询。只在按“应用”后使用当前目录完整索引分页；服务器顺序快路径也必须在筛选时走本地索引。
- 查询条件是有界、按目录保存的会话视图状态。切换站点/重启不保留搜索；排序、账号等持久配置保持兼容。点播前后项沿用同一查询条件，轮播遍历不继承浏览筛选。
- `slide.stopMinutes` 增量保存 0–1440 分钟，0 默认关闭；从本次轮播开始按 elapsedRealtime 计时，暂停/加载也计时。一个延迟任务；后台取消、不唤醒，返回时检查到期，到期结束完整会话。加载中主动暂停若复用 background() 释放 IO，必须重新挂起前台截止任务，不能漏掉到期。播放中可重新设定本次截止时间，不覆盖下次轮播的默认参数。
- 播放信息是打开时快照：图片显示位图尺寸/内存/gainmap，音视频轨道编码/分辨率/标称码率/帧率/声道/缓冲与系统 HDR 能力。不得把元数据表述成实测 HDR 输出，不显示签名 URL/凭证，不启动采样监控。
- 下载使用 Application 生命周期的单任务协调器，防止 Activity 重建创建第二个写入者；MainActivity.onStop 必须取消任务。没有 Service/WorkManager/后台常驻/自动续传，播放前必须 cancel + join 写入者。取消 IO 后的状态落盘和释放 slot 必须整体处于 NonCancellable 中，避免从 IO 返回时的 prompt cancellation 卡住 busy。
- `FileDownloader` 64 KiB 流式缓冲、进度最多每 500 ms 一次、保留至少 64 MiB 磁盘余量。仅单文件；不打包 HLS/DASH 分片，不支持整目录下载。图片显示仍遵守 128 MiB 输入及双图位图预算，下载本身不使用该图片大小上限。
- 每次继续通过原站点/账号重新 fs/get；下载客户端及每次重定向不携带 API 凭证。续传必须核对强 ETag、最终 URL 的 SHA-256（不保存原 URL）、Content-Range 起止/总长；无法核对就完整重下，不能盲目追加。替换流须先截断旧文件再记录新 validator。
- 下载位于应用私有 `files/downloads`；UUID 文件名、`.part` 未完成、原子 rename 为 `.media` 才算完成。独立 `downloads.db` v2（v1→v2 只加 source_key），最多 100 条记录，不保存 token/sign/thumb/raw_url。启动修复已 rename 但 DB 未提交的窄窗口，不能把 `.part` 判为完成。Catalog 后于 preview.8 升级为 v3，不影响下载库。
- 本机下载可离线点播/前后项及按需导出，不需要 OpenList 在线鉴权；不自动组成离线轮播。`LocalDownloadGateway` 不访问网络，MediaEngine 用 DefaultDataSource 支持私有文件，ImagePipeline 只接收下载目录内的本地文件、直接采样不重复复制。
- 下载操作可由文件行长按确认键、菜单键或触控长按打开，标准遥控不必有专用菜单键；已用实际 long-press D-pad OK 验证非首项菜单。不要破坏 View 原生长按事件或让键松开后误点播。
- 用户明确下载的本机副本在清缓存、退出账号和覆盖安装时保留；卸载会删除。只在用户确认后删除精确 UUID 副本/记录；服务器不变。继续未完成下载必须匹配原站点 URL、账号和站点 ID。
- 导出走系统 CreateDocument 授权（可选 U 盘/其他位置，取决于设备文件提供方），无广泛存储权限。不提供文件选择器的电视仍能本机离线打开。导出中断提醒目标可能有未完成文件，不擅自删除用户选中的外部文档。

### preview.5 画面与遥控进度

- 用户本轮选择视频画面比例、图片手动缩放/旋转、左右长按逐渐加速的快进退。不增加手动选择跳几秒的设置，不改变轮播左右切文件规则。
- `core/PlaybackControls.kt`：VideoFraming、PictureTransform、AcceleratingSeek 为纯模型。视频有原始完整/铺满裁切/拉伸全屏/16:9/4:3；用现有 PlayerView 和 SurfaceView 调整尺寸，不更换解码器。模式保存在新 SharedPreferences `playback.display` 的 `video.framing`，旧配置不变。
- 图片 1–4×、90° 步进旋转、复位；ImageView MATRIX 操作当前解码位图，无再下载/再解码/位图复制。用 drawable 的 intrinsic 尺寸计算矩阵并约束平移边界；换新图片复位，同图旋转屏幕/后台恢复保留调整。放大不提升原始解码清晰度。
- 画面设置内“移动画面”进入独立方向键平移模式；确认重新调整，返回退出移动，不切文件。调整图片暂停轮播并取消预解码，定时停止依然有效，用户手动继续。
- `RemoteSeekController` 仅在点播音视频、控制栏收起或专用快进退键下工作；控制栏左右仍导航，图片/轮播左右仍切换。短按 10 秒；长按按单调时钟而非 repeatCount 加速，350ms 后 15/45/120/300 倍进度速率（2/5/10 秒分档），没有步长选项。
- 长按暂停播放器，仅更新目标进度 HUD（100ms），松开一次 seekTo 并恢复原播放/暂停状态，不生成视频预览图或不断触发网络跳转。返回取消；弹窗、失焦、切文件、错误、Home/退出取消，失焦/后台不自动恢复播放。不可定位/未知时长资源提示不支持。
- 单元测试 PlaybackControlsTest；设备 app/PlaybackControlsTest 验证矩阵、同一 Bitmap、平移/换图、比例、真实 WAV 单次定位、暂停/取消/失焦；tools/playback-controls-ui-smoke.mjs 验证遥控操作与横竖屏。改 KeyEvent 路由时必须保留 ACTION_UP，不能把松键事件提前过滤掉。

## 数据与升级：不可破坏

- versionCode 必须单调增加；包名、签名、SharedPreferences 名称 `sites` / `sessions.encrypted`、Keystore alias `openlisttv.sessions.v1` 保持兼容。
- Catalog SQLite 当前版本4；v1→v2增加thumbnail，v2→v3增加scan_items的size/modified，v3→v4仅新增candidate_0/candidate_1及scan_snapshot，不删除旧表或账号/进度。downloads.db仍v2。排序加速索引按需创建，不在升级时全量重建。
- 普通重连/清缓存保留配置与播放进度；退出账号或明确替换账号/服务器身份才清原身份历史。
- 不通过卸载、pm clear、重置密钥来解决用户升级问题。清数据开关只允许专用模拟器的合成 fixture 测试。

### preview.8 大目录索引与轮播

- 旧“扫描无法继续，请清理索引或缩小范围”是通用兜底，不能据此断言用户磁盘满；旧全树扫描先 ensure 整目录，再逐条 autocommit 插入 scan_items，选图 ORDER BY random() 并联结 entries，多个大目录共用条目限额导致中断。
- FileRepository.scanPages 串行请求每页最多 500 条元数据，维持 4 MiB 响应预算；服务端缩小分页或单次元数据超限时，消费前以正确 offset 回退每页 100 条。总数变化、重复项、短页/空页不完整仍报错，不静默漏项。UI/缩略图仍每页100、当前加下一页，没有按500加载图片。
- 浏览 entries 按页复用 prepared INSERT / 单事务；条目数量缓存避免每页全表 COUNT。超出全局浏览缓存时淘汰非当前目录的旧目录，只删索引，不删 progress/账号/下载。过滤 COUNT 为最多128项有界缓存，写入/淘汰后失效；遍历 DirectoryReader 仅缓存2个100条窗口，不再每一张都排序/统计整目录。
- 全树扫描不调用 ensure，也不重复保存缩略图、sign；直接将每页可播文件的 path/size/modified 写入 scan_items，首批提交即可播。只保留一页网络元数据；每页让出 IO 机会，继续图片自动 OneAhead。候选与浏览索引独立，淘汰浏览目录不丢候选。
- 扫描中按稠密 ROWID 均匀抽取，完成后使用 (round,shuffle_key) 索引选择未播放项；每轮不重复。禁止往 scan_items 加 AUTOINCREMENT、逐条 DELETE 或 INSERT OR IGNORE，否则稠密 ROWID 假设失效。队列处理完删除该节点，不累积已完成节点；路径严格下行且有深度/数量上限。
- reserveScan 在异步初始化前取得代次，所有异步队列/候选操作核对代次；旧任务取消后不能清空或污染新会话。扫描状态采样与轮次切换必须使用同一快照，避免空表转完成时误跳轮次。
- IndexBudgetReached 仅表示软件索引限额；为后续轮次更新留512KiB以上磁盘预算。达到条目/深度/数据库预算后停止新增，但继续已发现资源，并在控制栏及一次提示中明确未覆盖全部范围。不自动遍历超限后剩余目录；不是无限库保证。实际磁盘空间不足/IO故障、鉴权、限流、网络错误仍暂停，不伪装成预算可继续。
- data/LargeLibraryTest 覆盖4×3万文件、容量边界后继续、首批先播、精简索引/浏览淘汰/进度保留、三轮不重复、代次取消、分页回退与失败分类；SeamlessSlideshowTest 增加3万文件边扫描边播放的实际帧/像素回归。测试均使用专用模拟器与合成资源，不表述成极米实测速度。

### preview.9 快照、游标与公平抽样

- 用户本轮明确要求三项全部实现。RESERVOIR作为第四模式追加，原SHUFFLE规则不替换。core/ReservoirSampler是Algorithm R，稳定无重复的输入流上均匀入选；仅返回槽号，常数状态，不引入算法库。候选在SQLite中占最多1万槽，继续读取后续分页，首次完整扫描网络量不减少。抽样可能重复，不保证每张都播到；扫描中只代表已读部分。队列/深度/磁盘限额触发时明确部分范围。
- data/ScanIndex统一拥有两个磁盘区和一条最近范围快照；API读取仍串行500条，图像OneAhead不变。抽样替换用原ROWID槽位UPDATE，不逐项DELETE/AUTOINCREMENT/REPLACE，不破坏稠密槽位。前台播放器已预约的Entry是不可变副本，不随池替换变化。
- 最近快照按站点ID、服务器URL、用户名、basePath、根路径、模式、递归、仅图片隔离，持久化身份摘要不含token或密码。记忆了任何目录密码时不保存/复用快照。每次媒体仍重新fs/get，不提供绕过鉴权的离线服务器访问。
- 进入轮播时：缓存0–6小时直接复用；6–24小时先播旧快照并在前台扫描另一区；超过24小时、时钟回退或身份/范围变化重新构建。仅保存最近一个范围。旧快照有效期不因失败/预算截断的刷新而延长；这类刷新不替换旧池。普通扫描完成或明确限额停止才发布快照，取消/网络等错误不提交半成品。无服务端快照/变更日志，不仅凭条目数判断内容没变。
- 设置新增“复用轮播索引（6小时）”，偏好sites/slide.reuseIndex，默认true；“本次重新扫描”仅本次启动，不持久化，Home返回不重复强制。同身份activate验证后只刷新浏览索引，preserveScanSnapshot=true保留快照，不能恢复旧的无条件清快照逻辑；服务器/账号变化清除，basePath由验证后的快照键隔离。SiteStore只保存token，session()默认basePath不能拿来与验证结果比较身份，否则非根账号每次启动都误清。用户强制重新加载或清缓存使快照失效；登出清理历史。没有Service/WorkManager/定时后台扫描。浏览页启动仍有正常列表请求，免掉的是轮播重扫请求。
- data/CatalogPaging提供256个有界排序键书签，顺序前翻使用索引区间游标；未访问的任意跳页或已淘汰书签可回退OFFSET。支持全部排序/升降序/文件夹置顶/NULL末置/自然序同键及字面搜索筛选；目录写入/淘汰清空书签和计数缓存。游标分解为互斥词典序区间，兼容API23无row-value语法；表达式NULL键绑定显式CAST整数，不能按TEXT比较。
- 全库只保留一个按当前排序匹配的browse_seek自适应索引，不为所有组合各建索引。索引创建遇软件磁盘限额保留旧索引、继续正确查询；API23旧SQLite不支持NULL表达式索引时回退正确但可能较慢的查询。新索引占用仍计入64MiB预算，不能把SQL微基准倍数当成整页或整机加速。
- 测试：core/ReservoirSamplerTest固定种子均匀性/百万项槽位边界；data/AdvancedIndexTest覆盖全部排序、游标跳转/失效、v3迁移、6/24小时/强制/身份隔离、重开后续轮、缓存前台刷新原子切换/失败不续期、百万候选输入及源级免API重扫。SeamlessSlideshowTest增加抽样池替换期间实际像素/帧连续回归。tools/advanced-index-ui-smoke.mjs验证遥控选项、覆盖升级保留与二次启动免列表请求。

## 构建与本机工具

- JDK 17，Android SDK 36 / Build Tools 36.0.0，Gradle Wrapper 9.6.0，AGP 9.4.0，Kotlin 插件 2.2.10。实际 Kotlin stdlib 由依赖解析到 2.2.21；不要混淆插件与运行库版本。
- 本机可用 `~/sdk/android-env.zsh` 补 PATH/JAVA_HOME/ANDROID_HOME；其他机器按标准环境变量配置，不硬编码某台机器的用户名路径。
- 快速检查：`./gradlew :core:test :data:testDebugUnitTest :app:lintDebug :app:assembleDebug`。
- 实际图片设备测试：构建并在专用设备安装 debug + AndroidTest APK，运行 `io.github.loliwolf.openlisttv.SeamlessSlideshowTest`。覆盖慢网画面、像素、预取深度、坏图、限流、暂停/历史、预算与取消清理。
- `tools/mock-openlist.mjs` 仅监听本机。默认 18765，demo/demo 为纯测试凭证；`OPENLIST_FIXTURE_PORT=18768 OPENLIST_FIXTURE_SHOWCASE=1` 提供原创演示素材。不得用于生产。
- `tools/ui-smoke.mjs`、`thumbnail-ui-smoke.mjs`、`slideshow-smoke.mjs`、`rotation-smoke.mjs` 为遥控回归；`update-smoke.mjs <apk>` 验证覆盖升级；`capture-showcase.mjs` 输出真实截图。工程记录写清实际执行的环境和时长。
- preview.2 增加 core/BrowserAndTimerTest、data/FileDownloaderTest、CatalogTest 的筛选分页、app/LightweightFeaturesTest 与 tools/lightweight-{ui,rotation,upgrade}-smoke.mjs。轮播 UI 自动化先按暂停，再做 hierarchy 轮询，避免进度刷新使 UIAutomator 等待超过控制栏显示时间。预览包操作说明见 `docs/PREVIEW_1.0.1.md`，详细检查仅写本机忽略的 artifacts/engineering-history。

## 发布密钥与 GitHub

- 固定证书 SHA-256：`57107a591edf888d84666852fd87ce5a27156949d90b6725bddbec33ef5c3079`，门禁文件 `gradle/alpha-certificate.sha256`。
- 保留早期证书 Subject 是为了兼容已有安装；APK 本身非调试。应用商店渠道另行评估，不能偷偷换签名。
- `tools/setup-release-signing.mjs` 只迁移原密钥；私有 PKCS12 在项目外的 macOS Application Support/OpenListTV/signing，密码在 Keychain。不要重新生成身份、打印密码或向 GitHub 上传私钥。
- 正式构建：`node tools/build-release.mjs`，或按 BUILDING.md 提供 OPENLIST_RELEASE_* 环境变量；不用长期 daemon/configuration cache 存签名状态。
- GitHub CLI 已安装；`node tools/gh.mjs ...` 通过现有 Git 凭证调用，不输出或保存 token。发布账号 LoliWolf；公共提交使用 GitHub noreply 邮箱，避免暴露私人邮箱。
- CI 只有读取权限，执行测试/lint/debug 构建，不持有生产签名。
- preview.3 按用户明确要求替换旧“每天一次、仅稳定版、浏览器下载”更新行为：设置有“自动检测更新（每次启动）”“参与测试版本”开关，以及仅更新使用的代理。原 automatic 偏好沿用，testing 默认 false；不再用 lastAttempt/ETag 缓存跳过启动检查。
- preview.6 新增“使用浏览器下载”开关，默认 false；沿用 `github-updates` 偏好新增 `browser.download`，重启/覆盖升级保留。开启后点击更新仅用 ACTION_VIEW + CATEGORY_BROWSABLE 打开已验证的本仓库新版 APK HTTPS 链接，不启动 UpdatePackages/资源暂停/安装授权；清除旧 permission.pending，返回应用不续装。关闭后沿用原应用内下载校验安装流程。
- 浏览器使用自身网络，无法继承应用 HTTP/SOCKS5 代理；HTTPS 加速前缀可保留在 URL 中。保存位置、下载和安装由浏览器及系统处理，无浏览器则展示/复制链接，不偷偷回退应用内下载。不新增依赖、权限或后台任务。UpdateFlowTest 覆盖链接/代理/缺浏览器/无效版本及偏好；更新设置 UI/覆盖脚本包含新开关。
- UpdateLaunchGate 每次进入应用仅检查一次，翻目录/窗口焦点/旋转不重复；系统安装/授权流程返回不重复。新版本弹窗，无新版或失败轻提示；播放或其他模态界面内延后提示。草稿和 Actions 构建产物不纳入“测试版本”。
- GithubUpdates 分页读取官方 `/repos/LoliWolf/OpenListTV/releases`，按 SemVer 选最高的可用 APK，不以列表首项/上传时间作为最高版本；可选正式＋预发布。每页最多 30 条 / 2 MiB，最多 50 页 / 60 秒，触及上限报检查失败而非声称最新。只保留当前页和最佳候选。
- UpdateNetwork 与 OpenList/媒体客户端完全隔离，不修改系统 ProxySelector、Authenticator 或全局代理。支持不含账号密码的 http://host:port、socks5://host:port；另有 HTTPS GitHub 加速前缀模式，前缀须同时支持 API 与 Release 附件。清空为直连；不关闭 TLS、不在代理失败时偷切直连。每次 CDN 跳转仍按配置代理转发，逻辑目的地只允许本仓库官方 API/附件及 GitHub 附件 CDN，无账号 token/cookie。
- 点击“更新”后 UpdatePackages 前台串行下载，暂停资源传输/列表缩略图，不与播放并行；离开前台取消并等待 IO 退出。64 KiB 缓冲、APK 上限 64 MiB、磁盘余量 64 MiB，目录私有 `files/updates/staging` / `ready`，不混入用户下载。完整长度、可用的 GitHub SHA-256、ZIP 元数据预算、包名/版本/更高 versionCode、非 debuggable 及固定签名信息均须检查；系统安装器再做实际 APK 签名验证和用户确认。损坏缓存不复用，取消不成为 ready。
- 新增 REQUEST_INSTALL_PACKAGES 是用户本轮指定更新流程的必要权限，不是静默安装授权。Android 8+ 未授权时引导“允许此来源”，返回后继续安装；已授权则下载完成直接请求系统安装器。使用不可导出的 FileProvider，只共享 updates/ready 子目录，content:// + 临时只读授权，显式选系统安装处理程序；不暴露站点/会话/其他私有文件。保留包名和证书，不建议卸载/清数据。
- 更新测试：先 `node tools/prepare-update-fixture.mjs`，再构建/安装 AndroidTest，运行 `node tools/update-flow-device-tests.mjs`。工具只使用原有兼容签名生成 androidTest 私有校验 APK；**fixture 没有应用代码/启动器，不得安装、发布或当成用户测试包**。设备脚本只允许专用模拟器，在 instrumentation 之外切换/恢复安装 AppOp；Android 撤销此授权会杀进程，不能在测试体 finally 中切权限，否则会把系统行为误当崩溃。Gate/代理/分页的 JVM 测试和 tools/update-settings-{ui,upgrade}-smoke.mjs 覆盖本轮行为。宽 Switch 的 TV 焦点需要显式 verticalFocus 顺序。
- 依赖更新须 `:app:exportNoticeInputs` + `node tools/generate-notices.mjs`，审阅新增许可证。完整许可在 APK 设置中；desugar_jdk_libs 2.1.5 的上游源码归档独立随 Release 提供。
- 自有代码许可证未指定，不擅自标 MIT/Apache/GPL；第三方授权见 NOTICE.md，不受此状态影响。

## 知识库维护

- 入口固定为 `https://my.feishu.cn/wiki/LojHwxGlOiAofDk2JLocRIYinnb`，space ID `7682261063663897529`。首页底层 docx 为 `J5Nid5ocdoVqjmxf56lcK4jHnpb`。
- 用户要求知识库面向使用者，只保留首页与使用文档，原 PRD 退役。使用文档通过首页子节点列表按标题唯一定位，不猜 token、不重复新建。
- 2026-09-06 已完成替换及删除并回读确认。使用文档：`https://my.feishu.cn/docx/VXlLdkASMoCiGdxdhItcr9kYnfh`；wiki node 为 `ERHmwJswui0Im6kV4nlcO3v7n0e`。修改前仍重新确认实际节点与最新版本。
- 用户的重要决策已浓缩在本文件。原 PRD 的本地备份在忽略的 artifacts/knowledge-backup 中，不作为对外产品入口。
- 操作时按可用 lark-doc/lark-wiki/lark-drive 技能，显式 user 身份，先读现状、局部修改、回读验证。创建/删除/改权限分别按授权范围，不扩大到其他文档。
- README、USAGE、测试报告和知识库随批准的版本更新；历史报告保留。飞书链接不等于匿名公开，不擅自改共享权限。
- 只有收到明确新任务才继续外部发布/删除；本文件不授权后台自动运维或反复发布。

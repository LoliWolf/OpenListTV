# OpenList TV — 项目接续上下文

先读本文件即可开始工作；按任务需要再读下述源码或文档，不需要寻找已退役 PRD。文件采用标准大写 `AGENTS.md`，避免在大小写不敏感文件系统创建两份相互冲突的 agents.md。

## 当前产品与发布

- 名称 OpenList TV，原生 Android OpenList 客户端，优先电视、投影、遥控器，也支持真实横竖屏与触控。
- 当前版本 `1.0.0`，versionCode `4`，发布仓库 `https://github.com/LoliWolf/OpenListTV`，稳定 Release 标签 `v1.0.0`。
- 仓库已公开，v1.0.0 已发布为非 draft / 非 prerelease / Latest；发布源提交 ed452bffd4052d34948673e26d3e2c4e0c1e7d91 的 GitHub 构建检查通过。后续只改文档不重发 APK，不移动已发布标签或覆盖旧 Release 附件。
- 包名固定 `io.github.loliwolf.openlisttv`。正式 APK 由 `release` 构建，非 debuggable、开启 R8 与资源裁剪；不能把 debug APK 当正式包。
- 负责人于 2026-09-07 确认 1.0.0 已实测验收。公开文档保持产品功能与必要限制，不列待验证清单；历史工程记录保存在本机忽略的 artifacts/engineering-history 中，不编造额外测试结论。
- README 保持简洁：下载、主要功能、真实横版/竖版截图、飞书入口。用户指南在 `docs/USAGE.md`，开发签名在 `docs/BUILDING.md`。

## 用户明确约束

- 性能与占用优先，不引入 Flutter、WebView 壳、服务端、全量 FFmpeg/VLC 或后台常驻。
- Android 14 / 2 GB RAM 为主要目标，尽量兼容 API 23；最高 4K SDR 按资源与硬件能力降级，HDR 不在当前范围内。
- 账号密码、多站点保存/单活动站点、只读文件浏览、播放、排序及三种可扩展轮播均保留。TOTP/SSO/访客等不是当前已实现能力。
- 无低清源且无法解码时轮播跳过，不做客户端实时转码；5 GB 视频可以流式播放，文件大小不等于运存需求。
- 方向与已有实现冲突或不符合最佳实践时，实施前向用户指出事实与取舍。
- 本项目没有 Nacos/MySQL 依赖。若后续任务确实涉及：Nacos 使用 API，配置来自 env；MySQL 使用 `dbx`，不要自行另建访问路径或暴露 env。
- 不擅自发布新版本、推送外部状态、修改公开权限或删除云端内容。只有用户明确授权的范围才执行；保留用户工作区改动。

## 架构与关键文件

| 模块 | 职责与主要入口 |
| --- | --- |
| core | 纯 Kotlin：Models、自然排序/路径、资源预算、AuthProvider/FileGateway、TraversalSource、PlaybackHistory、OneAhead |
| data | OpenListApi、SiteStore/Android Keystore、Catalog/SQLite、FileRepository、全树随机扫描 |
| media | 单 MediaEngine/ExoPlayer、ImagePipeline 串行采样、ThumbnailLoader 有界列表预览 |
| app | MainActivity 组合，BrowserList 虚拟列表，PlaybackPanel 生命周期，TvUi/ActionFlow 原生响应式布局 |

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
- 索引最多 100,000 条 / 64 MiB，深度最多 128；目录变化、截断和达到上限必须提示。
- 缩略图最多 2 并发，内存 LRU 8 MiB，PNG 磁盘缓存 16 MiB；无 thumb 时只为可见项串行生成原图预览。
- 图片临时输入最多 32 MiB；音视频缓冲目标 24 MiB，只有一个播放器，不预缓冲下一段视频。
- 图片轮播自动下载并解码下一张，直接替换 ImageView，不先清空/显示加载文字。慢网保留当前图，下一张显示后重新计时。
- 双图目标预算 `min(64 MiB, JVM maxHeap / 4)`，还要预留解码/旋转空间；内存不足时降采样或暂停预解码。预算不是总 PSS 的替代。
- 已显示 Bitmap 交给 GC/HWUI 管理，不能在仍可能被绘制时 recycle。未显示的预加载由 OneAhead 明确拥有并在取消/替换时回收。
- OneAhead 只容纳一个任务/值；异常到实际消费时才处理，防止预加载失败取消整个播放会话。ImagePipeline 跨 IO dispatcher 返回时保留所有权，避免取消丢失位图。
- PlaybackHistory 的 peek 不移动游标；取消预加载不丢失随机模式已经预约的 Entry。旧 slide.prefetch 仅控制音视频地址，关闭它不影响图片自动预加载。
- 坏资源可跳过，连续 10 项失败或鉴权/限流/持续网络错误暂停。Home/休眠/退出取消任务、释放播放资源，回前台需要用户继续。
- 旋转由原生 View 重排，保留目录、媒体和计时状态；窄屏按钮换行、文件信息合并。不要再锁横屏，也不要裁横图冒充竖屏截图。

## 数据与升级：不可破坏

- versionCode 必须单调增加；包名、签名、SharedPreferences 名称 `sites` / `sessions.encrypted`、Keystore alias `openlisttv.sessions.v1` 保持兼容。
- SQLite 当前版本 2；v1→v2 只增加 thumbnail 列。以后必须增量迁移，不能删表重建。
- 普通重连/清缓存保留配置与播放进度；退出账号或明确替换账号/服务器身份才清原身份历史。
- 不通过卸载、pm clear、重置密钥来解决用户升级问题。清数据开关只允许专用模拟器的合成 fixture 测试。

## 构建与本机工具

- JDK 17，Android SDK 36 / Build Tools 36.0.0，Gradle Wrapper 9.6.0，AGP 9.4.0，Kotlin 插件 2.2.10。实际 Kotlin stdlib 由依赖解析到 2.2.21；不要混淆插件与运行库版本。
- 本机可用 `~/sdk/android-env.zsh` 补 PATH/JAVA_HOME/ANDROID_HOME；其他机器按标准环境变量配置，不硬编码某台机器的用户名路径。
- 快速检查：`./gradlew :core:test :data:testDebugUnitTest :app:lintDebug :app:assembleDebug`。
- 实际图片设备测试：构建并在专用设备安装 debug + AndroidTest APK，运行 `io.github.loliwolf.openlisttv.SeamlessSlideshowTest`。覆盖慢网画面、像素、预取深度、坏图、限流、暂停/历史、预算与取消清理。
- `tools/mock-openlist.mjs` 仅监听本机。默认 18765，demo/demo 为纯测试凭证；`OPENLIST_FIXTURE_PORT=18768 OPENLIST_FIXTURE_SHOWCASE=1` 提供原创演示素材。不得用于生产。
- `tools/ui-smoke.mjs`、`thumbnail-ui-smoke.mjs`、`slideshow-smoke.mjs`、`rotation-smoke.mjs` 为遥控回归；`update-smoke.mjs <apk>` 验证覆盖升级；`capture-showcase.mjs` 输出真实截图。工程记录写清实际执行的环境和时长。

## 发布密钥与 GitHub

- 固定证书 SHA-256：`57107a591edf888d84666852fd87ce5a27156949d90b6725bddbec33ef5c3079`，门禁文件 `gradle/alpha-certificate.sha256`。
- 保留早期证书 Subject 是为了兼容已有安装；APK 本身非调试。应用商店渠道另行评估，不能偷偷换签名。
- `tools/setup-release-signing.mjs` 只迁移原密钥；私有 PKCS12 在项目外的 macOS Application Support/OpenListTV/signing，密码在 Keychain。不要重新生成身份、打印密码或向 GitHub 上传私钥。
- 正式构建：`node tools/build-release.mjs`，或按 BUILDING.md 提供 OPENLIST_RELEASE_* 环境变量；不用长期 daemon/configuration cache 存签名状态。
- GitHub CLI 已安装；`node tools/gh.mjs ...` 通过现有 Git 凭证调用，不输出或保存 token。发布账号 LoliWolf；公共提交使用 GitHub noreply 邮箱，避免暴露私人邮箱。
- CI 只有读取权限，执行测试/lint/debug 构建，不持有生产签名。
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

# 0.1.0-alpha02 验证记录

日期：2026-09-06。此为历史版本记录。范围：界面与遥控操作、列表缩略图、覆盖升级及功能回归，不是全品牌、全格式或长稳验收完成声明。上版结果留档到 [alpha01 测试记录](TEST_REPORT-alpha01.md)，当前记录见 `../TEST_REPORT.md`。

## 产物

| 项目 | 结果 |
| --- | --- |
| 应用版本 | `0.1.0-alpha02`，versionCode 2 |
| 包名 | `io.github.loliwolf.openlisttv`，未改变 |
| 构建 | benchmark，R8 / 资源裁剪，延续 alpha01 调试签名 |
| APK | `artifacts/OpenListTV-0.1.0-alpha02.apk` |
| 体积 | 1,662,265 字节，约 1.59 MiB；比 alpha01 增加 18,012 字节 |
| SHA-256 | `4395a90d51b729f6858f4b3475661fdc6577938594926743c4db7a8b44785273` |
| 签名证书 SHA-256 | `57107a591edf888d84666852fd87ce5a27156949d90b6725bddbec33ef5c3079`，与 alpha01 一致 |
| 平台声明 | minSdk 23 / targetSdk 36；无 ABI 专属本地库，不等于 ARM32 已实测 |
| 构建 / Lint | 通过；0 errors / 38 warnings，未加全局错误忽略基线 |

签名验证通过；与 alpha01 相同的 v1 META-INF 元数据提示仍保留，没有删除第三方许可文件来隐藏提示。签名身份受构建门禁保护，私钥未入库。正式分发签名及渠道迁移仍需单独确定，不能以卸载应用解决更新不兼容。

## 验证范围与结果

开发机 Apple M2 Pro / 32 GB；专用 Android 14 ARM64 TV 模拟器，2 GB RAM、1920×1080。仅使用本机生成的 PNG/WAV、模拟 OpenList 合同接口；没有访问用户线上账号或资源。

| 层次 | 实际结果 |
| --- | --- |
| JVM | 16 项通过：core 10、data 6，包含分页窗口边界、thumb URL / 缺失 / null、自然排序、协议和凭证隔离 |
| Android 数据层 | 4 项通过：跨页排序/站点隔离、随机轮次、v1→v2 增量迁移/进度保留、设置/加密会话恢复 |
| 实际覆盖升级 | alpha01 中保存文件大小降序、随机游走、仅图片、17/23/29 秒和非零播放进度；仅用 adb install -r 更新，站点及上述配置保留；loginRequests 不增加，点播出现继续播放提示；再次冷启动仍保留 |
| 基础遥控回归 | 原 8 组 D-pad 检查通过：登录/输入法、空目录/返回焦点、1,001 条排序/左右跳页、图片、退出播放器、WAV、Home 暂停及媒体凭证隔离 |
| 两页预览窗口 | 初始仅请求 1–200.png；连续 Down 跨页后才请求到 300.png，无第 4 页提前请求、无原图下载；回翻、行复用、图片退出和焦点恢复通过，后台请求停止 |
| 缺失预览 | 4K.png 的 thumb 返回 404、Large6000.png 和 Nested/3.png 没有 thumb，均在可见列表内自动生成预览；未反复下载或泄漏账号凭证 |
| 轮播回归 | 顺序深度遍历、随机游走、全树随机均连续切换；每个短窗口观察到 5 次媒体请求，暂停和退出正常 |
| 字段可读性 | 浅色/深色下三个已填值字段都能由 D-pad 到达。截图 OCR 验证图片/视频/音频说明、17/23/29 及秒单位同时可见；不是仅添加无障碍标签或空值 hint |

覆盖升级测试没有卸载、清数据或要求重新登录。之后基础登录回归的 `--reset-fixture-app` 只清理专用模拟器的合成测试数据；真实投影请直接覆盖安装。

最终产物另做一次覆盖复验：先确认已有非零续播提示，再覆盖安装导出的 APK，逐字比对续播位置不变，loginRequests 不增加。见 `tools/final-upgrade-smoke.mjs`。

数据层独立 instrumentation 调用确认 `OK (4 tests)`；Gradle 部分过滤运行的报告只含 UpgradeTest 的 2 项，不把过滤掉的用例当作通过。真实 OpenList v4.2.6 Local 驱动、签名 MP4/SRT 与坏视频跳过已在 alpha01 验证，本轮未重建真实服务或冒充全量真实驱动回归。

## 短时内存观察

| 场景 | 应用 TOTAL PSS |
| --- | --- |
| 连续图片列表，进入第 2 页 | 两次短时点：53,890 / 53,111 KiB，约 51.9–52.6 MiB |
| 顺序图片轮播 | 44,886 KiB，约 43.8 MiB |
| 随机游走图片轮播 | 45,470 KiB，约 44.4 MiB |
| 全树随机图片轮播 | 51,313 KiB，约 50.1 MiB |

这些是指定模拟器/样本的短时单点，不是峰值、P95、4K 输出或 24 小时成绩。轮播样本含 4K 和 6000×4000 源图，但按 1080p 预算采样；PSS 不包含所有系统媒体/GPU 服务开销。缩略图内存 LRU 限 8 MiB、磁盘 PNG 限 16 MiB，不把子预算当作进程总占用。

## 复现与限制

```sh
./gradlew :core:test :data:testDebugUnitTest :app:lintDebug :app:assembleBenchmark
node tools/mock-openlist.mjs
# 以下只在专用模拟器运行，文本注入代替系统 IME 打字。
node tools/ui-smoke.mjs --reset-fixture-app
node tools/thumbnail-ui-smoke.mjs
node tools/thumbnail-fallback-smoke.mjs
node tools/slideshow-smoke.mjs
node tools/form-ui-smoke.mjs
node tools/final-upgrade-smoke.mjs
```

升级复现：先安装原 alpha01 并准备 fixture 登录，运行 `node tools/upgrade-ui-smoke.mjs --seed-alpha01`，只执行 `adb install -r artifacts/OpenListTV-0.1.0-alpha02.apk`，再运行 `--verify-alpha02`。不要把 alpha02 降级后的数据当作初始 v1 数据，不在真实用户设备使用清测试数据选项。

`artifacts/screenshots/` 保留列表和已填值轮播字段截图。使用 UI 层级与 macOS Vision OCR 核对字段/布局文字，未冒充人工逐像素视觉验收。仍需在实际投影确认观感、不同字体缩放、输入法、长遥控连按、各品牌解码与长稳表现。应用崩溃日志本轮未见 AndroidRuntime 崩溃，不是永不崩溃保证。

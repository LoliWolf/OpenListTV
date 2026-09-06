# 0.1.0-alpha03 验证记录

日期：2026-09-06。此为 alpha03 历史记录，不是全品牌、4K 真机或 24 小时认证。旧结果见 [alpha02](TEST_REPORT-alpha02.md)、[alpha01](TEST_REPORT-alpha01.md)，当前发布结果见 `../TEST_REPORT.md`。

## 安装包

| 项目 | 实测 |
| --- | --- |
| 版本 / 包名 | `0.1.0-alpha03` / versionCode 3 / `io.github.loliwolf.openlisttv` |
| 构建 | benchmark，R8 / 资源裁剪，延续原 alpha 签名 |
| APK | `artifacts/OpenListTV-0.1.0-alpha03.apk` |
| 大小 | 1,664,717 字节，约 1.59 MiB；比 alpha02 增加 2,452 字节 |
| SHA-256 | `5056e9ccb0c719c61c219b5535a9a025ceba881002418cbb920a35eaec898fac` |
| 签名证书 SHA-256 | `57107a591edf888d84666852fd87ce5a27156949d90b6725bddbec33ef5c3079`，未更换 |
| 平台 / 检查 | minSdk 23 / targetSdk 36；构建通过，Lint 0 errors / 38 warnings |

原 alpha01/alpha02 APK 未改动。仅执行 `adb install -r` 从 alpha02 更新：保存的会话、全目录树随机模式和 2/0/0 秒参数均保留，服务端 loginRequests 未增加。此后登录回归只清理专用模拟器的合成测试数据，不是覆盖升级流程。

## 功能验证

环境：Android 14 ARM64 TV 专用模拟器，2 GB RAM、1920×1080。只用本机 PNG/WAV fixture 与隔离测试数据库；没有修改用户线上 OpenList。

- 22 项 JVM 通过：core 16、data 6。新增的 6 项覆盖单槽并发、所有权转移、取消已完成预加载、迟到结果释放、预加载异常隔离、历史 peek 与双图预算等组合断言。
- 6 项 Android 图片测试通过，直接运行实际 PlaybackPanel/ImagePipeline：下一张人为延迟 2.8 秒时，1 秒到期仍保持原图；预请求发生在当前图停留期间；不越过未显示图片继续预取第三张；新图的计时从显示后开始。
- 同组设备测试覆盖旧音视频预取开关关闭仍自动预加载图片、坏图静默跳过但保留上一张、HTTP 429 暂停且不扫过后续文件、暂停/恢复/历史前后项/内存压力/后台清理，以及 4K 源图采样预算和取消后的临时文件清理。
- 上述图片测试监听实际 View 绘制，检查已显示图不被置空、不引用 recycled Bitmap、不插入加载文字，并通过系统截图检查中央图像区域像素。不是仅验证状态变量，也不把有限截图当作全设备逐帧认证。
- 8 组原有遥控回归通过：登录/输入法、空目录/焦点恢复、1,001 项排序与翻页、点播图片、退出播放器、WAV、Home 暂停和媒体凭证隔离。
- 顺序深度遍历、随机游走、全目录树随机三种轮播都通过遥控回归；各短观察窗口有 6 次媒体请求，含下一张预加载，不能直接当作显示张数。暂停后退出正常。

## 内存与边界

三个短时单点 TOTAL PSS：顺序 51,211 KiB（约 50.0 MiB）、随机游走 57,443 KiB（约 56.1 MiB）、全树随机 71,275 KiB（约 69.6 MiB）。样本含 4K 与 6000×4000 源图，但当前显示按 1080p 采样；这些不是峰值、P95 或真机 4K 数据。

另做全树随机 60 秒观察，每 5 秒采样一次：57,300–66,696 KiB（约 56.0–65.1 MiB），最后一个点 60,776 KiB，期间有 31 次媒体请求（包含下一张预加载），未见该窗口内单调持续增长。原始点为 66564, 57300, 60996, 60924, 60780, 58428, 64368, 58440, 60555, 62996, 66696, 60776 KiB；也保存在 `artifacts/alpha03-memory.json`。一分钟不能证明不存在慢性泄漏或已通过长稳验收。

同时只持有当前图片和一个预加载槽位；双图目标预算为 `min(64 MiB, JVM maxHeap / 4)`，还按可用内存预留解码和旋转副本。已显示图片由 GC/HWUI 管理生命周期，未显示图片在取消时显式回收。预算不包含所有图形缓存、系统服务和瞬时解码开销，不等于 PSS 上限。测试不主动调用 GC 美化数据。

慢网下允许延长当前图片停留，不假装网络不足也能精确按固定秒数切换。首次启动、音视频加载和全局认证/网络错误仍可给出必要提示；本次移除的是已显示图片之间的加载文字/空白。Android 6、32 位、真实 4K、不同投影品牌及 24 小时运行尚未验证。本轮未重建真实 OpenList 协议实例，旧版协议联调结果只作为历史基线。

## 复现

```sh
./gradlew :core:test :data:testDebugUnitTest :app:lintDebug :app:assembleBenchmark
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
# 安装 app-debug.apk 及 app-debug-androidTest.apk 到专用模拟器，再运行：
adb -s emulator-5554 shell am instrument -w -e class io.github.loliwolf.openlisttv.SeamlessSlideshowTest io.github.loliwolf.openlisttv.test/androidx.test.runner.AndroidJUnitRunner
# 使用优化包做遥控及短时内存验证，fixture 仅监听本机：
node tools/mock-openlist.mjs
node tools/ui-smoke.mjs --reset-fixture-app
node tools/slideshow-smoke.mjs
node tools/slideshow-memory-smoke.mjs
# 仅做覆盖，不先清数据：
node tools/update-smoke.mjs artifacts/OpenListTV-0.1.0-alpha03.apk
```

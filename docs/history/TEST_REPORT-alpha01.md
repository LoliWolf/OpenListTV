# 0.1.0-alpha01 验证记录

历史版本记录。当前版本的结果见 `../TEST_REPORT.md`，不要将这些旧版数据当作新版实测。

日期：2026-09-06。结论：已得到可安装的优化内测包；本记录不是稳定版、全品牌或 24 小时验收证书。

## 产物

| 项目 | 实际结果 |
| --- | --- |
| 应用 | OpenList TV `0.1.0-alpha01`，versionCode 1 |
| 包名 | `io.github.loliwolf.openlisttv` |
| 构建 | `benchmark`，启用 R8 与资源裁剪，调试签名 |
| APK | `artifacts/OpenListTV-0.1.0-alpha01.apk` |
| 体积 | **1,644,253 字节，约 1.57 MiB** |
| 最低 / target SDK | 23 / 36 |
| 本地 ABI 库 | APK 内无 `lib/` 目录；不等于已完成 ARM32 真机测试 |
| 签名检查 | v1/v2 验证通过；不是正式发行密钥 |
| SHA-256 | `c6031d89feb9aa33a40ca5fcd1e2f0ff2f72c5ae36d94275b28fa8946a52610c` |

签名工具对 v1 中部分 `META-INF` 元数据给出不受保护提示；未通过删除第三方许可文件来掩盖提示。正式发行需确定发布签名并复验，不把调试签名包作为长期正式分发物。

## 环境和覆盖

开发机为 Apple M2 Pro / 32 GB，Android 14 ARM64 TV 模拟器配置 2 GB RAM、1920×1080。使用本机合成媒体接口，以及独立临时目录中、仅绑定 loopback 的 **OpenList v4.2.6 Local 驱动**实例。未连接或修改用户线上服务。

最终相关构建、Lint 与自动检查通过。共记录 **29 项功能检查**，分组如下：

| 层次 | 数量 | 内容 |
| --- | --- | --- |
| core JVM 测试 | 7 | 自然排序、路径安全、图片预算、有限历史、顺序遍历/空目录、会话字符串脱敏 |
| data JVM 测试 | 5 | 登录哈希和原始 Authorization、部署前缀、HTTP 200 业务错误、用户相对路径/5 GB 大小字段、媒体重定向凭证隔离、地址校验等组合场景 |
| Android 仪器测试 | 3 | 1,001 条跨页排序与站点隔离；随机轮次不重复；真实 OpenList 登录、受限根、目录密码、签名代理与注销 |
| 遥控端到端检查 | 8 | 登录及关闭输入法、空目录、返回焦点、1,001 条目录/左右翻页、图片、播放器退出、WAV、Home 后暂停及媒体请求无账号凭证等组合断言 |
| 轮播模式检查 | 3 | 顺序、随机游走、全树随机均连续换图，暂停后可退出 |
| 真实服务媒体界面 | 2 | 签名 MP4 播放与时长；同目录 SRT 挂载到字幕轨道 |
| 自动跳过检查 | 1 | 真实服务中的无效 MP4 无低清源时自动跳过，继续播放图片 |

Lint：**0 errors / 27 warnings**。警告主要涉及固定但非最新的依赖/SDK、中文界面字符串、传统全屏 API、固定横屏和布局工具提示；没有设置全局基线来忽略错误。

字幕检查验证了获取与轨道注册，不冒充所有字幕视觉效果、编码或语言的认证。遥控脚本的导航使用 D-pad/OK/Back，文字注入模拟系统输入法，不替代各品牌真实输入法测试。

## 短时性能观察

这些数字是指定模拟器和样本下的观察值，不可外推为真机或所有资源的保证。

| 项目 | 结果 | 口径 |
| --- | --- | --- |
| 冷启动首帧 | 30 次，P50 403 ms，P95 445 ms，最大 473 ms | 强停应用进程后 `adb am start -W` 的 TotalTime；不是目录加载完成时间 |
| 顺序图片轮播 | PSS 42,320 KiB，约 41.33 MiB | 短时单点采样 |
| 随机游走图片轮播 | PSS 43,844 KiB，约 42.82 MiB | 短时单点采样 |
| 全树随机图片轮播 | PSS 43,436 KiB，约 42.42 MiB | 短时单点采样 |

轮播包含普通 PNG、4K 源图和 6000×4000 源图，按 **1080p 显示预算采样**；每种模式采样窗口内观察到 5/8/5 次媒体请求。不是原图像素常驻，不是 4K 显示模式、峰值、P95、30 分钟或 24 小时内存成绩。应用 PSS 不包含开发机工具和所有系统媒体/GPU 的额外开销。

冷启动原始毫秒样本：445, 473, 375, 329, 403, 360, 318, 282, 402, 426, 415, 417, 423, 392, 417, 424, 232, 438, 353, 345, 296, 412, 367, 417, 432, 419, 362, 438, 406, 303。

开发验证中未观察到 AndroidRuntime 崩溃；这不是无崩溃保证。尚未实施 24 小时持续测试、100 次播放器进出压力测试、系统回收矩阵或真实断网恢复全矩阵。

## 复现入口

```sh
./gradlew :core:test :data:testDebugUnitTest :app:lintDebug :app:assembleBenchmark
node tools/mock-openlist.mjs
# 专用模拟器上执行；--reset-fixture-app 会清除该模拟器中本应用的测试数据。
node tools/ui-smoke.mjs --reset-fixture-app
node tools/slideshow-smoke.mjs
node tools/measure-startup.mjs
```

真实服务测试使用 `tools/real-openlist-fixture.mjs` 创建的新实例，并传入 `-Pandroid.testInstrumentationRunnerArguments.realOpenListUrl=http://10.0.2.2:18766/`。无此参数时真实服务仪器测试显式跳过，不能把“跳过”算通过。媒体界面脚本需要对应样本预置，详见脚本注释。

有效 MP4 使用 AndroidX Media 固定提交的 [silent-black-25s.mp4](https://github.com/androidx/media/blob/43e3af79dabb43a69badffbbdfa6d421a1cdb36c/libraries/test_data/src/test/assets/media/mp4/silent-black-25s.mp4)，Git blob SHA 为 `2e649792a23b1bca656af01639ed4967b8af7e06`。仅下载到临时联调目录，未打包进 APK；图片、WAV、SRT 与坏文件由测试程序生成。

## 待完成的验收

真实投影/电视的 Android 6、32 位、4K SDR、不同硬件解码器、网络/云盘驱动、各类常见编码样本及长稳测试尚未完成。HLS/低清轨道选择等能力虽已接入，但没有在本轮对所有流型与降级组合逐一认证。AList 未做独立版本回归。

下一步应使用受限测试账号在实际投影上验证，收集型号、系统、ABI、内存、OpenList 版本、驱动和脱敏失败信息。不得提交密码、令牌或完整签名 URL 到 GitHub Issue 或公开知识库。

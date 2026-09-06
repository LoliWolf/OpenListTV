# 1.0.0 发布验证记录

日期：2026-09-06。负责人指定 1.0.0 为 GitHub 稳定渠道版本；稳定渠道标识不等于所有设备已通过测试。历史 alpha 数据保存在 `docs/history/`，不作为本版新增能力的替代证据。

## 发布产物

- 版本 `1.0.0`，versionCode 4，包名 `io.github.loliwolf.openlisttv`。
- 正式 `release` 构建，R8 / 资源裁剪开启，apkanalyzer 返回 debuggable=false。
- apksigner 验证 v1/v2 签名通过；证书 SHA-256 为 `57107a591edf888d84666852fd87ce5a27156949d90b6725bddbec33ef5c3079`，与已有 alpha 一致。
- 发布私钥副本位于项目外，密码在 macOS Keychain；原始密钥未改动，私钥和密码不上传 GitHub。保留旧证书 Subject 是为了覆盖安装兼容，不代表调试构建；未评估应用商店签名迁移。
- 文件名 `OpenListTV-1.0.0.apk`。准确大小与 SHA-256 以 Release 的 `SHA256SUMS` 为准，避免把生成前的候选校验和写入自身源提交。
- APK 包含完整第三方许可和原始 NOTICE，在“设置 → 第三方许可”查看；desugar_jdk_libs 2.1.5 对应的上游源码与构建脚本另附归档。

## 已通过

主环境为专用 Android 14 ARM64 TV 模拟器，2 GB RAM；横屏 1920×1080、竖屏 1080×1920。使用本机合成资源、独立测试数据库，不访问用户线上 OpenList。

| 检查 | 结果 |
| --- | --- |
| JVM | 22 项通过，core 16 / data 6 |
| 图片组件设备测试 | 6 项通过：慢网保留画面、预取深度/计时、旧预取开关关闭、坏图、限流、暂停/前后历史、内存压力与取消清理等组合断言 |
| 基础遥控回归 | 8 组通过：登录/输入法、空目录/焦点、1,001 项排序/翻页、图片、退出播放器、WAV、Home 暂停、媒体凭证隔离 |
| 覆盖安装 | 从 alpha03 直接 install -r，登录会话、轮播模式与 2/0/0 秒参数保留，loginRequests 未增加 |
| 横竖屏 | 目录与进程保留、当前图片保留、暂停轮播不自动恢复，窄屏工具栏/播放按钮均处于屏幕内 |
| README 截图 | 4 张原始 Release 截图，2 横 / 2 竖；无裁切、旋转伪装或合成界面。素材为本地生成的演示场景 |
| 字段可读性 | UI 层级与截图 OCR 确认三种时长说明、数值和秒单位，非仅设置无障碍文字 |
| 构建与静态检查 | 通过，Lint 0 errors / 38 warnings；未新增忽略错误的全局基线 |

原有三种轮播及一分钟内存观察详见 [alpha03 记录](history/TEST_REPORT-alpha03.md)。当前版本没有修改图片预加载核心预算，但不把旧版的 PSS 数字标作本版新实测。新的横竖屏功能采用真实视图重排，未改变媒体排序、账号协议或数据库版本。

## 未完成的矩阵

Android 6、32 位、真实投影 / TV 品牌、真实 4K SDR、所有云盘驱动/编码与 24 小时长稳均未全面验证。OpenList v4.2.6 Local 驱动的历史协议联调通过，本轮没有重建全部真实服务矩阵。截图 / 有限像素检查不等于对所有 GPU 的逐帧认证。

## 复现入口

见 `docs/BUILDING.md`。主要工具：`ui-smoke.mjs`、`rotation-smoke.mjs`、`update-smoke.mjs`、`capture-showcase.mjs`，以及 `SeamlessSlideshowTest`。仅专用模拟器可使用 `--reset-fixture-app`，覆盖升级验证本身不卸载或清数据。

# 1.0.0 验收记录

2026-09-07：项目负责人确认 1.0.0 实测功能正常，完成验收。

## 发布产物

- 稳定版本 v1.0.0，versionCode 4，包名 io.github.loliwolf.openlisttv。
- Release 构建，R8 / 资源裁剪开启，debuggable=false。
- v1 / v2 签名验证通过；证书 SHA-256：57107a591edf888d84666852fd87ce5a27156949d90b6725bddbec33ef5c3079。
- [发布页](https://github.com/LoliWolf/OpenListTV/releases/tag/v1.0.0)提供 APK、SHA256SUMS 和第三方库源码归档。
- 私钥及其密码不进入源码仓库；第三方许可随 APK 提供。

## 工程检查

2026-09-06 的构建及自动检查记录：

| 检查 | 结果 |
| --- | --- |
| JVM | 22 项通过 |
| 图片组件设备测试 | 6 项通过 |
| 基础遥控回归 | 8 组通过 |
| 横竖屏 | 目录、当前图片和暂停状态保持，工具栏及播放器按钮完整显示 |
| 覆盖安装 | 会话、轮播模式和时长保持 |
| 截图 | 两张横屏、两张竖屏，来自实际 Release 应用 |
| 构建及 Lint | 构建通过，0 errors / 38 warnings |

自动设备检查使用 Android 14 ARM64 TV、2 GB RAM 的专用模拟器。复现入口见 docs/BUILDING.md；清测试数据仅用于专用 fixture，不作为用户升级步骤。

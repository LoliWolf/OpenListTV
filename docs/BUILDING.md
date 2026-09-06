# 构建、测试与发布

原生 Kotlin / Android View，无 NDK、自建服务端或跨平台引擎。

## 环境

JDK 17、Android SDK Platform 36、Build Tools 36.0.0。设置 `ANDROID_HOME` 和 `JAVA_HOME`。Gradle Wrapper 固定 9.6.0 并校验分发 SHA-256；AGP 9.4.0、Kotlin 插件 2.2.10。完整依赖见版本目录与 Gradle 解析结果。

```sh
./gradlew :core:test :data:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:assembleDebugAndroidTest
```

debug 只用于专用测试设备。benchmark 保留早期签名门禁供性能复现；正式对外文件来自 release。

## 发布签名

Release 非 debuggable，开启 R8 和资源裁剪。为保证已有安装覆盖升级，沿用最初 alpha 的证书身份；Subject 保留早期 Android Debug 名称，不等于 APK 开启调试。当前发布渠道是 GitHub；变更渠道时须保持升级签名连续性。

macOS 首次迁移（必须持有原始 alpha 私钥）：

```sh
node tools/setup-release-signing.mjs
node tools/build-release.mjs
```

迁移不修改原密钥：新 PKCS12 位于项目外的 `~/Library/Application Support/OpenListTV/signing/release.p12`，目录 / 文件权限 0700 / 0600；随机密码位于 macOS Keychain 项 `io.github.loliwolf.openlisttv.release`。不要把私钥、密码、完整环境变量或钥匙串导出上传到 GitHub。

其他主机从安全备份恢复同一密钥，提供 `OPENLIST_RELEASE_STORE_FILE`、`OPENLIST_RELEASE_STORE_PASSWORD`、`OPENLIST_RELEASE_KEY_ALIAS`、`OPENLIST_RELEASE_KEY_PASSWORD`（可与 store 密码相同），格式 PKCS12。发布脚本不用长期 Gradle daemon 或 configuration cache，不开启 debug 日志。

门禁比较 `gradle/alpha-certificate.sha256` 的固定证书指纹。签名不匹配必须停止，不得生成新密钥、修改指纹或要求卸载来绕过。私人备份需要 keystore 和密码恢复方式，但不进入源码仓库或普通构建产物。

## 许可与发布

```sh
./gradlew :app:exportNoticeInputs
node tools/generate-notices.mjs
node tools/build-release.mjs
```

依赖变更后重新生成并审阅许可。desugar_jdk_libs 的原始源码归档独立随 Release 提供，见[第三方组件](THIRD_PARTY_NOTICES.md)。自有代码许可证尚未指定，不擅自补写授权。

发布前递增版本、检查 APK 签名与 debuggable、覆盖安装、横竖屏、遥控和预加载取消，并更新测试记录。只有负责人明确要求时才推送 / 发布。可用 `node tools/gh.mjs ...` 复用 Git 凭证而不输出令牌。

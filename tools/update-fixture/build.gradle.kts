plugins { id("com.android.application") version "9.4.0" }
android {
    namespace = "io.github.loliwolf.openlisttv"
    compileSdk = 36
    defaultConfig { applicationId = "io.github.loliwolf.openlisttv"; minSdk = 23; targetSdk = 36; versionCode = 12; versionName = "1.0.1-preview.8" }
    buildTypes { getByName("release") { isDebuggable = false; signingConfig = signingConfigs.getByName("debug") } }
}
// Test-only archive, no launcher/code. NEVER install or publish it.
// Use the existing compatible signing identity; never generate a replacement.

plugins { alias(libs.plugins.android.library) }
android {
    namespace = "io.github.loliwolf.openlisttv.media"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
}
dependencies {
    api(project(":core"))
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.exif)
    api(libs.media3.player)
    api(libs.media3.ui)
    implementation(libs.media3.hls)
    implementation(libs.media3.okhttp)
    coreLibraryDesugaring(libs.desugar)
}

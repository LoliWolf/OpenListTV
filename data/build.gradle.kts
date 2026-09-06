plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.serialization) }
android {
    namespace = "io.github.loliwolf.openlisttv.data"
    compileSdk = 36
    defaultConfig { minSdk = 23; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
}
dependencies {
    api(project(":core"))
    api(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    coreLibraryDesugaring(libs.desugar)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.test)
    androidTestImplementation(libs.androidx.test.junit)
}

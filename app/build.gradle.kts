import java.security.KeyStore
import java.security.MessageDigest
import groovy.json.JsonOutput

plugins { alias(libs.plugins.android.application) }
android {
    namespace = "io.github.loliwolf.openlisttv"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.loliwolf.openlisttv"
        minSdk = 23
        targetSdk = 36
        versionCode = 13
        versionName = "1.0.1-preview.9"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("release") {
            storeFile = providers.environmentVariable("OPENLIST_RELEASE_STORE_FILE").orNull?.let { file(it) }
            storePassword = providers.environmentVariable("OPENLIST_RELEASE_STORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("OPENLIST_RELEASE_KEY_ALIAS").orElse("openlisttv").get()
            keyPassword = providers.environmentVariable("OPENLIST_RELEASE_KEY_PASSWORD").orNull ?: storePassword
            storeType = "PKCS12"
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
    buildFeatures { buildConfig = true }
    // Locally generated signed fixture, never included in a production APK.
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("artifacts/update-fixture-assets"))
}

// Keep alpha updates compatible with the already distributed alpha01 certificate.
// Never silently ship a different newly generated debug key on another machine.
val verifyAlphaSigningIdentity = tasks.register("verifyAlphaSigningIdentity") {
    doLast {
        val config = android.signingConfigs.getByName("debug")
        val keyFile = config.storeFile ?: error("Alpha signing keystore is not configured")
        check(keyFile.isFile) { "Restore the original alpha signing keystore before building an update" }
        val store = KeyStore.getInstance("JKS")
        keyFile.inputStream().use { store.load(it, config.storePassword!!.toCharArray()) }
        val certificate = store.getCertificate(config.keyAlias) ?: error("Missing alpha signing certificate")
        val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
        val expected = rootProject.file("gradle/alpha-certificate.sha256").readText().trim()
        check(actual == expected) { "Signing certificate differs from alpha01. Do not uninstall users' apps; restore the original keystore." }
    }
}
tasks.matching { it.name == "validateSigningBenchmark" }.configureEach { dependsOn(verifyAlphaSigningIdentity) }
val verifyReleaseSigningIdentity = tasks.register("verifyReleaseSigningIdentity") {
    doLast {
        val config = android.signingConfigs.getByName("release")
        val keyFile = config.storeFile ?: error("Release signing is required; use tools/build-release.mjs or supply OPENLIST_RELEASE_* variables")
        check(keyFile.isFile && !config.storePassword.isNullOrEmpty()) { "Restore the release keystore and its password; never generate a replacement identity" }
        val store = KeyStore.getInstance("PKCS12")
        keyFile.inputStream().use { store.load(it, config.storePassword!!.toCharArray()) }
        val certificate = store.getCertificate(config.keyAlias) ?: error("Missing release signing certificate")
        val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
        check(actual == rootProject.file("gradle/alpha-certificate.sha256").readText().trim()) { "Release signature differs from installed versions; stop instead of asking users to uninstall" }
    }
}
tasks.matching { it.name == "validateSigningRelease" || it.name == "packageRelease" }.configureEach { dependsOn(verifyReleaseSigningIdentity) }
tasks.register("exportNoticeInputs") {
    doLast {
        val entries = listOf("releaseRuntimeClasspath", "coreLibraryDesugaring").flatMap { name ->
            configurations.getByName(name).incoming.resolutionResult.allComponents.mapNotNull { component ->
                (component.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.let { id ->
                    mapOf("coordinate" to "${id.group}:${id.module}:${id.version}", "cacheDir" to gradle.gradleUserHomeDir.resolve("caches/modules-2/files-2.1/${id.group}/${id.module}/${id.version}").absolutePath)
                }
            }
        }.distinctBy { it["coordinate"] }.sortedBy { it["coordinate"] }
        val output = layout.buildDirectory.file("notices-input.json").get().asFile
        output.parentFile.mkdirs(); output.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("dependencies" to entries, "gradleLicense" to gradle.gradleHomeDir?.resolve("LICENSE")?.absolutePath))))
    }
}
dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":media"))
    implementation(libs.activity)
    implementation(libs.lifecycle)
    implementation(libs.recyclerview)
    implementation(libs.coroutines.android)
    coreLibraryDesugaring(libs.desugar)
    androidTestImplementation(libs.androidx.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.mockwebserver)
    androidTestImplementation(libs.exif)
}

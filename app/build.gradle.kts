import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val releaseVersion = Properties().apply { rootProject.file("release.properties").inputStream().use { load(it) } }
val signingValues = listOf("MADMAIL_KEYSTORE", "MADMAIL_STORE_PASSWORD", "MADMAIL_KEY_ALIAS", "MADMAIL_KEY_PASSWORD")
    .associateWith { providers.environmentVariable(it).orNull }
val hasSigning = signingValues.values.all { !it.isNullOrBlank() }
check(signingValues.values.all { it.isNullOrBlank() } || hasSigning) { "Supply all four MADMAIL signing environment variables, or none." }
android {
    namespace = "org.madmail.mobile"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.madmail.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = (providers.gradleProperty("appVersionCode").orNull ?: releaseVersion.getProperty("versionCode")).toInt().also {
            require(it in 1..2100000000) { "Invalid Android version code" }
        }
        versionName = providers.gradleProperty("appVersionName").orNull ?: releaseVersion.getProperty("versionName")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    signingConfigs {
        if (hasSigning) create("distribution") {
            storeFile = file(signingValues.getValue("MADMAIL_KEYSTORE")!!)
            storePassword = signingValues.getValue("MADMAIL_STORE_PASSWORD")
            keyAlias = signingValues.getValue("MADMAIL_KEY_ALIAS")
            keyPassword = signingValues.getValue("MADMAIL_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("debug") { versionNameSuffix = "-dev" }
        getByName("release") {
            isDebuggable = false
            // Keep reflective SSH/crypto dependencies intact until R8 has device coverage.
            isMinifyEnabled = false
            if (hasSigning) signingConfig = signingConfigs.getByName("distribution")
        }
    }
    buildFeatures { compose = true }
    packaging {
        jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/libmadmail*.so" }
        resources.excludes += "META-INF/versions/**/OSGI-INF/MANIFEST.MF"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
tasks.register("verifyPackagedEngine") {
    doLast {
        listOf("libmadmail_v2_30_5.so", "libengine_launcher.so").forEach {
            check(file("src/main/jniLibs/arm64-v8a/$it").isFile) {
                "Missing packaged engine. Run scripts/prepare-engine.sh with ANDROID_NDK_HOME set."
            }
        }
        val releases = groovy.json.JsonSlurper().parse(file("src/main/assets/engine/releases.json")) as List<*>
        releases.forEach { entry ->
            val release = entry as Map<*, *>
            val binary = file("src/main/jniLibs/arm64-v8a/${release["file"]}")
            val digest = MessageDigest.getInstance("SHA-256").digest(binary.readBytes())
                .joinToString("") { "%02x".format(it) }
            check(digest == release["sha256"]) { "Packaged engine checksum mismatch. Run scripts/prepare-engine.sh." }
        }
    }
}
tasks.named("preBuild").configure { dependsOn("verifyPackagedEngine") }
tasks.register("verifyReleaseSigning") {
    doLast {
        if (providers.gradleProperty("requireReleaseSigning").orNull == "true") {
            check(hasSigning) { "Release signing is required. Configure MADMAIL signing environment variables." }
            check(file(signingValues.getValue("MADMAIL_KEYSTORE")!!).isFile) { "Release keystore is missing." }
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn("verifyReleaseSigning") }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("dev.chrisbanes.haze:haze:1.5.4")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.github.mwiede:jsch:2.28.8")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

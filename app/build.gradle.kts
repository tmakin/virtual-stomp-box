import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "uk.neatlines.stompbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "uk.neatlines.stompbox"
        // Android 9, so an old phone can read the sensor over USB. Bluetooth needs Android 13 (the GATT
        // write APIs WitBleSource uses) and says so on older phones. 28 is also AAudio's setUsage.
        minSdk = 28
        targetSdk = 35
        // CI run number, so every downloaded build installs over the previous one.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.$versionCode"
        // Shown in the app's status line, so a test result can be matched to its build.
        val sha = (System.getenv("GITHUB_SHA") ?: "local").take(7)
        val built = ZonedDateTime.now(ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'"))
        buildConfigField("String", "BUILD_LABEL", "\"#$versionCode $sha $built\"")
        // Native AAudio engine (src/main/cpp): 64-bit ARM, plus 32-bit ARM for older phones.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }

    // A fixed debug key checked into the repo, so APKs built on different CI runners can be
    // installed over each other. It signs test builds only; never use it for a store release.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")   // FileProvider for sharing recordings
}

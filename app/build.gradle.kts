import java.util.Properties

plugins {
    // AGP 9 has built-in Kotlin support, enabled by default. Applying
    // org.jetbrains.kotlin.android on top of it is a hard conflict.
    id("com.android.application")
}

// Signing material is never committed. When keystore.properties is absent — for anyone who
// has just cloned the repository, and in CI — the release build is simply unsigned, so the
// project still compiles for everyone.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasSigningConfig = keystorePropertiesFile.exists()

android {
    namespace = "com.seamless.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.seamless.player"
        minSdk = 29          // Android 10
        targetSdk = 36
        // 0.2.0 adds subtitles, and with them the INTERNET permission — a change to what this
        // app can do that PRIVACY.md and the manifest both name by version, so the number has to
        // be real rather than pending. The tag is still the maintainer's to push.
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    // F-Droid rejects the signed dependency-metadata blob AGP embeds by default: it is
    // opaque and defeats reproducible builds. Nothing depends on it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        // Off by default since AGP 8; Log.kt uses BuildConfig.DEBUG to silence debug logging
        // in release builds.
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        // jvmTarget is inherited from android.compileOptions.targetCompatibility above.
        // The preload manager is still marked unstable in Media3 1.11.
        freeCompilerArgs.add("-opt-in=androidx.media3.common.util.UnstableApi")
    }
}

dependencies {
    // Pinned deliberately: core-ktx 1.19.0 declares minCompileSdk 37, which would force
    // compileSdk up to 37. 1.18.0 is the newest release that builds against 36.
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.fragment:fragment-ktx:1.9.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Needed for the Storage Access Framework fallback when moving files. MediaStore only
    // permits videos inside its own recognised directories; SAF has no such restriction.
    implementation("androidx.documentfile:documentfile:1.1.0")

    // 1.1.0 is the last stable release; everything newer is still alpha. Its AAR declares
    // no minCompileSdk, so it cannot trip the checkDebugAarMetadata trap.
    implementation("androidx.biometric:biometric:1.1.0")

    // The playback engine. media3-exoplayer brings in the preload manager.
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-ui:1.11.0")
}

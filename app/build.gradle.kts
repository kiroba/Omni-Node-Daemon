plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

// The single Omni Node version (repo VERSION): the APK, the desktop zips and the core's version line
// all read it, so every platform shows the same number.
val nodeVersion = rootProject.file("VERSION").readText().trim()
val nodeVersionParts = nodeVersion.substringBefore('-').split('.').map { it.toInt() }

// One stable signing key, so a new Omni Node installs over the old one (the in-app update needs that).
// CI writes the keystore from repo secrets and sets these; without them (local builds, or before the
// secrets exist) the APK falls back to Gradle's per-machine debug key, which Android treats as another app.
val omniKeystore = System.getenv("OMNI_SIGNING_KEYSTORE")?.takeIf { it.isNotBlank() }?.let { file(it) }?.takeIf { it.isFile }

android {
    namespace = "com.omniverse.node"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.omniverse.node"
        minSdk = 26
        targetSdk = 35
        // 0.5.0 -> 500 (0.4.0 was versionCode 6, so this keeps increasing)
        versionCode = nodeVersionParts[0] * 10000 + nodeVersionParts[1] * 100 + nodeVersionParts[2]
        versionName = nodeVersion
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildFeatures { buildConfig = true }   // BuildConfig.VERSION_NAME: the installed version the updater compares

    signingConfigs {
        if (omniKeystore != null) {
            create("omni") {
                storeFile = omniKeystore
                storePassword = System.getenv("OMNI_SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("OMNI_SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("OMNI_SIGNING_KEY_PASSWORD")?.takeIf { it.isNotEmpty() }
                    ?: System.getenv("OMNI_SIGNING_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        // The released APK is still the debug build type (app-debug.apk), now signed with the stable key.
        debug {
            if (omniKeystore != null) signingConfig = signingConfigs.getByName("omni")
        }
        release {
            isMinifyEnabled = false
            if (omniKeystore != null) signingConfig = signingConfigs.getByName("omni")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // FileProvider only: hands the downloaded update APK to Android's installer.
    implementation("androidx.core:core:1.13.1")
}

chaquopy {
    defaultConfig {
        version = "3.11"
        pip {
            // The daemon's only third-party dependency (Ed25519 via omni_signing).
            install("cryptography")
        }
        // Keep the APK and cold start small: compile to .pyc, no source.
        pyc { src = true }
    }
}

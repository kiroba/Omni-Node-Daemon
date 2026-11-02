plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

// The single Omni Node version (repo VERSION): the APK, the desktop zips and the core's version line
// all read it, so every platform shows the same number.
val nodeVersion = rootProject.file("VERSION").readText().trim()
val nodeVersionParts = nodeVersion.substringBefore('-').split('.').map { it.toInt() }

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

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
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

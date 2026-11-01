plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.omniverse.node"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.omniverse.node"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.3.0"
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

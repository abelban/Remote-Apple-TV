plugins {
    id("com.android.application")
    id("com.chaquo.python")
}

android {
    namespace = "dev.abelman.remote"
    testBuildType = "qa"
    compileSdk {
        version = release(36) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "dev.abelman.remote"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0"
        testInstrumentationRunner = "dev.abelman.remote.RemoteSmokeInstrumentation"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        create("qa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
        pip {
            options("--find-links", project.file("wheels").absolutePath)
            install("-r", "requirements.txt")
        }
    }
}

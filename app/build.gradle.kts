plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ahad.lyricsoverlay"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ahad.lyricsoverlay"
        minSdk = 24
        targetSdk = 34
        versionCode = 7
        versionName = "2.5.0"

        // The current native whisper.cpp runtime is built for modern 64-bit Android phones.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media:media:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // whisper.cpp runs fully on the phone. Only a multilingual model file is downloaded once.
    implementation("dev.ffmpegkit-maintained:whisper-android:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json classes are stubs in local JVM tests; use the real implementation there.
    testImplementation("org.json:json:20240303")
}

// The user-facing GitHub build command also executes JVM regression tests.
tasks.matching { it.name == "assembleDebug" }.configureEach {
    dependsOn("testDebugUnitTest")
}

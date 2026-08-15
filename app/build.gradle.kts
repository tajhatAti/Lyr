plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val aiSyncBaseUrl = providers.gradleProperty("LYR_AI_BASE_URL")
    .orElse(providers.environmentVariable("LYR_AI_BASE_URL"))
    .getOrElse("")
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "com.ahad.lyricsoverlay"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ahad.lyricsoverlay"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "2.2.0"
        buildConfigField("String", "AI_SYNC_BASE_URL", "\"$aiSyncBaseUrl\"")
    }

    buildFeatures {
        buildConfig = true
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

    testImplementation("junit:junit:4.13.2")
}

// The user-facing GitHub build command also executes JVM regression tests.
tasks.matching { it.name == "assembleDebug" }.configureEach {
    dependsOn("testDebugUnitTest")
}

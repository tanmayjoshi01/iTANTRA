plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    // Kept separate from speech-engine's library namespace
    // (com.itantra.speechengine), following the same com.itantra.* convention.
    namespace = "com.itantra.app"
    compileSdk = 36 // Matches speech-engine.

    defaultConfig {
        applicationId = "com.itantra.app"
        minSdk = 24 // Matches speech-engine; an app cannot go lower than its libraries.
        // The application module is the one that controls runtime
        // platform-behavior compatibility (see speech-engine/build.gradle.kts).
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // The app consumes speech processing through speech-engine's public API
    // only; no speech/audio code lives in this module.
    implementation(project(":speech-engine"))

    // Jetpack Compose (project UI framework, see README). The BOM pins
    // mutually compatible Compose library versions. All Apache 2.0.
    // 2026.06.01 (Compose 1.11.4) is the newest BOM that fits this project's
    // compileSdk 36 / AGP 8.13.2: BOMs from 2026.08.00 on bring Compose 1.12,
    // whose AAR metadata requires compileSdk 37 and AGP 9.1+.
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Used directly by the speech controller (StateFlow, recognition worker)
    // and SpeechViewModel. Both already resolved transitively at exactly these
    // versions (coroutines matches speech-engine); declared because app code
    // now uses them directly.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.9.4")

    testImplementation("junit:junit:4.13.2")
    // Deterministic virtual-time dispatcher for SpeechController tests.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // GrantPermissionRule, for exercising Start/Stop without the system dialog.
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

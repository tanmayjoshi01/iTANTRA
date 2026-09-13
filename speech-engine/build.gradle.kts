plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.itantra.speechengine"
    compileSdk = 36 // Latest available stable Android platform at time of setup (verified against Google's SDK repository metadata).

    defaultConfig {
        minSdk = 24 // Engineering choice for low/mid-range device coverage; see project README and Phase 1 report.
        // targetSdk is intentionally not set here: for an Android *library*
        // module it only affects this module's own test APK, and is
        // superseded by whatever the consuming application module (Paras's
        // app) declares. Setting it here would be misleading about which
        // module actually controls runtime platform-behavior compatibility.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

dependencies {
    // Standard, lightweight Android/Kotlin concurrency primitive used only
    // to run microphone capture off the calling thread (AudioRecorder).
    // Apache 2.0 license; small footprint; avoids hand-rolled Thread/Handler
    // management for cancellation and lifecycle safety. See Phase 1 report,
    // Dependencies section, for full justification.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")

    // Stage 3B: on-device Hindi STT inference runtime. MIT licensed.
    // Promoted from androidTestImplementation (Stage 3 Phase A) to a real
    // production dependency now that speech-engine's own STT recognizer
    // (IndicConformerRecognizer) calls it directly. sherpa-onnx was
    // evaluated (Stage 3B) and not adopted: this direct ONNX Runtime path,
    // combined with the hand-verified MelSpectrogramFeatureExtractor and
    // CtcGreedyDecoder in this module, was already demonstrated end-to-end
    // and required no additional native dependency.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
}

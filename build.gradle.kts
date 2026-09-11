// Top-level build file: declares plugin versions once (with apply false) so
// submodules — speech-engine now, and Paras's future application module —
// can apply them without re-specifying a version, keeping AGP/Kotlin
// versions consistent across the whole project.
//
// Version selection, actually verified in this environment rather than
// assumed:
//   - Android Gradle Plugin 9.4.0 (the genuinely latest stable AGP release,
//     confirmed via Google's Maven repository metadata) was tried first,
//     but AGP 9.0+ ships a new build-in-Kotlin DSL that is incompatible
//     with the separate 'org.jetbrains.kotlin.android' plugin used below;
//     applying it fails with an explicit Gradle error naming this
//     incompatibility. Adopting AGP 9's new DSL would require a broader
//     rewrite of this build (and is a larger, less-tested surface than
//     Phase 1 warrants), so this project uses the latest stable AGP 8.x
//     release instead, which is fully compatible with the traditional
//     Kotlin Android plugin DSL used here.
//   - Android Gradle Plugin 8.13.2 (latest stable 8.x release, confirmed
//     via Google's Maven repository metadata).
//   - Kotlin 2.4.20 (latest stable release, confirmed via Maven Central
//     metadata).
// Gradle itself is pinned via gradle/wrapper/gradle-wrapper.properties to
// 9.5.1. The genuinely latest stable Gradle (9.7.1) was tried first, but
// AGP 8.x depends on a Gradle-internal API ('InternalProblems') that
// Gradle removed in 9.6.0 — Gradle's own error message names 9.5 as the
// last compatible line for AGP 8.x, which is confirmed by Gradle's public
// upgrade guide (docs.gradle.org, "Upgrading your build from Gradle 9.x to
// the latest", AGP 8.x incompatibility note). 9.5.1 is the latest stable
// patch release in that line, confirmed via services.gradle.org.
plugins {
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
}

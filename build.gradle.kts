// Top-level build file: declares plugin versions once (with apply false) so
// submodules — speech-engine now, and Paras's future application module —
// can apply them without re-specifying a version, keeping AGP/Kotlin
// versions consistent across the whole project.
//
// Versions were confirmed as the latest published stable releases (not
// alpha/beta/RC) via Google's Maven repository and Maven Central metadata
// at the time this project was set up:
//   - Android Gradle Plugin 9.4.0
//   - Kotlin 2.4.20
// Gradle itself is pinned via gradle/wrapper/gradle-wrapper.properties to
// 9.7.1, the latest stable Gradle release at the same time. See the Phase 1
// implementation report for the verification method and the residual
// compatibility caveat (AGP 9.4.0 x Gradle 9.7.1 has not been exercised by
// an actual build in this environment, since the required Android SDK
// platform is not installed here).
plugins {
    id("com.android.library") version "9.4.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
}

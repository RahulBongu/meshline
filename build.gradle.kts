// Top-level build file. Versions are pinned here so the app module stays lean.
// Chosen for JDK 17 + Gradle 8.14 + AGP 8.12 (compileSdk 36 / targetSdk 36).
plugins {
    id("com.android.application") version "8.12.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    // KSP is intentionally NOT added yet; it lands with Room entities in Phase D.
}

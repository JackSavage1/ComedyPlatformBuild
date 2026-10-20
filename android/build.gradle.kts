// =============================================================================
// Root build file. It only *declares* the plugins (apply false) so every
// module uses the same versions from gradle/libs.versions.toml. Each module
// then applies the ones it needs in its own build.gradle.kts.
// =============================================================================

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

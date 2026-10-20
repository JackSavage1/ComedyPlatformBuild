// =============================================================================
// settings.gradle.kts — tells Gradle which modules make up the Android build
// and where to download plugins and libraries from.
//
//   :app   the Android app (Jetpack Compose UI, networking, Android services)
//   :core  pure Kotlin/JVM: data models + every business rule, unit-tested
//          without an emulator (spec section 4)
// =============================================================================

pluginManagement {
    repositories {
        // Google's Maven repo hosts the Android Gradle plugin and AndroidX.
        // The content filter keeps Gradle from asking Google for everything else.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Modules may not declare their own repositories; everything comes from here.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MicTracker"

include(":app")
include(":core")

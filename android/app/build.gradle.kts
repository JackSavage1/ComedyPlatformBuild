// =============================================================================
// :app — the Android app (Jetpack Compose + Material 3, single Activity).
//
// Supabase settings come from android/secrets.properties (gitignored; copy
// android/secrets.example.properties). Missing values do NOT break the build —
// they become empty strings and the app shows a "Not configured" screen.
// =============================================================================

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------------------
// Secrets: android/secrets.properties → BuildConfig.SUPABASE_URL / _ANON_KEY.
// An environment variable with the same name is used as a fallback (handy for
// CI); when neither exists the value is "".
// ---------------------------------------------------------------------------
val secrets = Properties().apply {
    val file = rootProject.file("secrets.properties")
    if (file.isFile) {
        file.inputStream().use { load(it) }
    }
}

fun secret(name: String): String =
    (secrets.getProperty(name) ?: System.getenv(name) ?: "").trim()

/** Quotes a value for a Java string literal inside BuildConfig. */
fun javaString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.comedyplatform.mictracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.comedyplatform.mictracker"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "SUPABASE_URL", javaString(secret("SUPABASE_URL")))
        buildConfigField("String", "SUPABASE_ANON_KEY", javaString(secret("SUPABASE_ANON_KEY")))
    }

    buildTypes {
        release {
            // Keep release builds simple for now: no code shrinking.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        // Android framework calls in plain JVM unit tests return defaults
        // instead of throwing (our tests only touch pure-Kotlin classes).
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    // Compose (versions from the BOM)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // Kotlin
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Networking + maps
    implementation(libs.okhttp)
    implementation(libs.maplibre.android)

    // JVM unit tests (src/test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

// =============================================================================
// :core — pure Kotlin/JVM module (no Android dependencies).
//
// Holds the data models and every business rule from docs/MOBILE_SPEC.md
// section 4. Because it's plain Kotlin, its unit tests run on any JVM in a
// second or two:   ./gradlew :core:test
// =============================================================================

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Compile for Java 17 bytecode. We set the *target* rather than a toolchain so
// the build works with whatever JDK (17 or newer) runs Gradle — CI uses 17,
// Android Studio ships 21.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // `api` (not `implementation`): the app module sees JsonObject/JsonElement
    // in :core's public functions (payload builders, lenient decoding).
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        showExceptions = true
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

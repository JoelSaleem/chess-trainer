import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    // Compose MP *plugin* is skipped here: its AGP integration (onVariant) is incompatible with AGP 9.5.1's
    // KMP-library plugin. We use the Kotlin compose compiler plugin + the Compose MP artifacts directly.
    alias(libs.plugins.kotlin.compose)
}

val composeMp = "1.9.0"

kotlin {
    jvmToolchain(17)

    android {
        namespace = "com.joelsaleem.chesstrainer.ui"
        compileSdk = 36
        minSdk = 26
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            // api so consumers (:app, :web) get chess types + one Compose version transitively.
            api(project(":chess-core"))
            api(project(":chess-training"))
            api("org.jetbrains.compose.runtime:runtime:$composeMp")
            api("org.jetbrains.compose.foundation:foundation:$composeMp")
            api("org.jetbrains.compose.material3:material3:$composeMp")
            api("org.jetbrains.compose.ui:ui:$composeMp")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
        }
    }
}

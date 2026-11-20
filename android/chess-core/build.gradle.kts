import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvmToolchain(17)

    // Android consumers (:app, :chess-engine, :chess-training) resolve this variant.
    // withHostTest {} runs the commonTest suite on the JVM host too (keeps Android-side coverage).
    android {
        namespace = "com.joelsaleem.chesstrainer.core"
        compileSdk = 36
        minSdk = 26
        withHostTest {}
    }
    // Plain Kotlin/JS target (emulated Long); fast headless tests + a TS-interop option.
    js {
        nodejs {
            testTask { useMocha { timeout = "300s" } }
        }
    }
    // Wasm target (native i64 Long) — what Compose Multiplatform for web depends on.
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        nodejs {
            testTask { useMocha { timeout = "300s" } }
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

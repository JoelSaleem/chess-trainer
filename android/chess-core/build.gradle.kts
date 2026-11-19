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
    // Web target. Pure-stdlib core compiles to JS unchanged; nodejs() runs the tests headless.
    // browser() is added in the web-UI phase; the library itself is browser-ready either way.
    js {
        nodejs {
            testTask {
                // perft walks millions of nodes; JS (emulated Long) needs far more than Mocha's 2s default.
                useMocha { timeout = "300s" }
            }
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

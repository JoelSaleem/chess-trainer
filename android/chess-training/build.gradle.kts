plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)

    // Android consumers (:app) resolve this variant. withHostTest {} runs commonTest on the JVM host.
    android {
        namespace = "com.joelsaleem.chesstrainer.training"
        compileSdk = 36
        minSdk = 26
        withHostTest {}
    }
    // Web target; commonMain is pure once java.time → kotlinx-datetime. Platform I/O lives in androidMain.
    js {
        nodejs {
            testTask {
                useMocha { timeout = "60s" }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":chess-core"))
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

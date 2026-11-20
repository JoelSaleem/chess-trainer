plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.joelsaleem.chesstrainer.engine"
    compileSdk = 36
    ndkVersion = "30.0.16248370"

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    // The networks are copied to filesDir and loaded by path; don't re-compress them.
    androidResources {
        noCompress += "nnue"
    }
}

// No dependencies: this module is now only the native Stockfish build + the JNI
// StockfishNative surface. The orchestration + tests moved to :engine.

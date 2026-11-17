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

dependencies {
    implementation(project(":chess-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

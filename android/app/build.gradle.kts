plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.joelsaleem.chesstrainer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.joelsaleem.chesstrainer"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures { compose = true }
}

dependencies {
    // Compose + chess types come transitively from :ui (its api(...) deps), one version for both platforms.
    implementation(project(":ui"))
    implementation(libs.activity.compose)
    implementation(project(":chess-core"))
    implementation(project(":chess-training"))
    implementation(project(":chess-engine"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
}

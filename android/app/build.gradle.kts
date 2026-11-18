plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.joelsaleem.chesstrainer"
    // Compose BOM 2026.09.00 pulls compose 1.12.1, which requires compileSdk >= 37.
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
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(project(":chess-core"))
    implementation(project(":chess-training"))
    implementation(project(":chess-engine"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
}

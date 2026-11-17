plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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
    implementation(project(":chess-engine"))
}

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.joelsaleem.chesstrainer.training"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }
}

dependencies {
    implementation(project(":chess-core"))

    testImplementation("junit:junit:4.13.2")
}

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.kooo.evcam.core.model"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

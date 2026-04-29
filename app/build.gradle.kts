plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.memoos"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.memoos"
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "0.2.0-ebpf"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

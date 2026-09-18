plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.digitech.algorithm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.digitech.algorithm"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String",
            "TWELVE_DATA_API_KEY",
            "\"${project.findProperty("TWELVE_DATA_API_KEY") ?: ""}\""
        )
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    kotlin {
        jvmToolchain(17)
    }

    dependencies {
        implementation("androidx.core:core-ktx:1.15.0")
        implementation("androidx.appcompat:appcompat:1.7.0")
    }
}

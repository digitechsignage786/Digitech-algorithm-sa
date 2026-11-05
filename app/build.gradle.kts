plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.digitech.algorithm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.digitech.algorithm.test2"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Public gateway URL only; the provider API key stays in the Cloudflare Worker secret.
        buildConfigField(
            "String",
            "MARKET_DATA_WORKER_URL",
            "\"${project.findProperty("MARKET_DATA_WORKER_URL") ?: ""}\""
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
        testImplementation("junit:junit:4.13.2")
        implementation("androidx.core:core-ktx:1.15.0")
        implementation("androidx.appcompat:appcompat:1.7.0")
        implementation("com.squareup.okhttp3:okhttp:4.12.0")
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.foodielover.printagent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.foodielover.printagent"
        // minSdk 26 (Android 8 Oreo) -- required for notification channels used by the
        // foreground-service notification; also comfortably covers the Bluetooth Classic
        // (RFCOMM) APIs used throughout, which have been stable since API 5.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Lifecycle-aware coroutine scopes for the Activity and the Service.
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // WorkManager -- used ONLY as a watchdog that restarts the foreground service if it is
    // not running. It must never itself poll /api/print-jobs (see WatchdogWorker.kt).
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Keystore-backed encrypted storage for PRINT_AGENT_KEY and the rest of the one-time
    // configuration (see config/SecureConfig.kt). No server/API changes needed for this --
    // it is purely how the existing shared secret is stored on-device.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

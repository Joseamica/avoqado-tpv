plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")  // KSP instead of kapt (2x faster) - version managed in root
    id("com.google.dagger.hilt.android")
    id("com.google.gms.google-services")  // Firebase
    id("com.google.firebase.crashlytics")  // Firebase Crashlytics
}

android {
    namespace = "com.jaac.avoqado_tpv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jaac.avoqado_tpv"
        minSdk = 27  // Android 8.1 (required by Blumon PAX SDK EMV module)
        targetSdk = 34
        versionCode = 4
        versionName = "1.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ⚠️ CRITICAL: Blumon PAX SDK requires armeabi ONLY
        ndk {
            abiFilters.clear()
            abiFilters.add("armeabi")
//            abiFilters.add("arm64-v8a")
        }

        // Environment variables (NEVER hardcode secrets in code)
        buildConfigField("String", "API_BASE_URL", "\"https://api.avoqado.io/api/v1/\"")
        buildConfigField("String", "API_BASE_URL_DEV", "\"https://patchiest-noncommemorational-willia.ngrok-free.dev/api/v1/\"")
        buildConfigField("String", "SOCKET_URL", "\"https://api.avoqado.io\"")
        buildConfigField("String", "SOCKET_URL_DEV", "\"https://patchiest-noncommemorational-willia.ngrok-free.dev\"")

        // ⚠️ REMOVED: Hardcoded terminal configuration (2025-11-05)
        // Serial numbers and merchant accounts now fetched dynamically from backend
        // See: TerminalConfigRepository, GET /api/v1/tpv/terminals/:serial/config
        //
        // buildConfigField("String", "TERMINAL_SERIAL", "\"2841548417\"")  // ← REMOVED
        // buildConfigField("String", "TERMINAL_BRAND", "\"PAX\"")         // ← REMOVED
        // buildConfigField("String", "TERMINAL_MODEL", "\"A910S\"")       // ← REMOVED
        // buildConfigField("String", "BLUMON_ENV", "\"SAND\"")            // ← REMOVED
    }


    // ⭐ PRODUCTION MIGRATION (2025-11-19): Build Variants for Sandbox + Production
    flavorDimensions += "environment"

    productFlavors {
        create("sandbox") {
            dimension = "environment"
            applicationIdSuffix = ".sandbox"
            versionNameSuffix = "-sandbox"
            isDefault = true  // Make sandboxDebug the default variant in IDE

            // Blumon Sandbox Environment
            buildConfigField("String", "BLUMON_ENV", "\"SAND\"")
            buildConfigField("String", "TOKEN_SERVER_URL", "\"https://sandbox-tokener.blumonpay.net\"")
            buildConfigField("String", "CORE_SERVER_URL", "\"https://sandbox-core.blumonpay.net\"")

            // Default terminal config (for fallback - will be fetched from backend)
            buildConfigField("String", "DEFAULT_TERMINAL_SERIAL", "\"2841548417\"")
            buildConfigField("String", "DEFAULT_TERMINAL_BRAND", "\"PAX\"")
            buildConfigField("String", "DEFAULT_TERMINAL_MODEL", "\"A910S\"")
        }

        create("production") {
            dimension = "environment"

            // Blumon Production Environment
            buildConfigField("String", "BLUMON_ENV", "\"PROD\"")
            buildConfigField("String", "TOKEN_SERVER_URL", "\"https://tokener.blumonpay.net\"")
            buildConfigField("String", "CORE_SERVER_URL", "\"https://core.blumonpay.net\"")

            // Default terminal config (for fallback - will be fetched from backend)
            buildConfigField("String", "DEFAULT_TERMINAL_SERIAL", "\"2841548417\"")
            buildConfigField("String", "DEFAULT_TERMINAL_BRAND", "\"PAX\"")
            buildConfigField("String", "DEFAULT_TERMINAL_MODEL", "\"A910S\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Production: Use 24-hour cache for SDK initialization
            buildConfigField("boolean", "FORCE_BLUMON_REINIT", "false")
        }
        debug {
            isMinifyEnabled = false

            // Development: Force re-init on every app launch to prevent DUKPT key corruption
            // This fixes NA_002 errors when doing rebuild without uninstalling
            buildConfigField("boolean", "FORCE_BLUMON_REINIT", "true")
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
        compose = true
        buildConfig = true  // Enable BuildConfig for environment variables
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // ⚠️ LINT: Fail build on orphaned files and code quality issues
    lint {
        abortOnError = false  // Don't fail build on warnings (for now)
        warningsAsErrors = false
        checkReleaseBuilds = true
        checkDependencies = false  // Skip checking library dependencies

        // Orphaned files detection
        checkGeneratedSources = false
        disable += setOf(
            "ObsoleteLintCustomCheck",
            "GradleDependency",
            "OldTargetApi",  // targetSdk 34 is fine
            "Aligned16KB"    // Blumon SDK issue, not our code
        )

        // Treat UnusedResources as warning (will catch orphaned drawables/layouts/strings)
        warning += setOf(
            "UnusedResources"
        )

        // HTML report for detailed analysis
        htmlReport = true
        htmlOutput = layout.buildDirectory.file("reports/lint-results-debug.html").get().asFile
    }
}

dependencies {
    // BlumonPay SDK Modules (project dependencies - common to all flavors)
    implementation(project(":sdk"))  // PAX SDK (PosApi) + Neptune API
    implementation(project(":commonlib"))  // Common utilities
    implementation(project(":emv"))  // EMV kernel logic

    // ⭐ SANDBOX FLAVOR: Blumon SDK AAR files (Sandbox environment)
    "sandboxImplementation"(files("libs/blumon_sdk-debug.aar"))
    "sandboxImplementation"(files("libs/lib-services-BP-SAND_1601.aar"))

    // ⭐ PRODUCTION FLAVOR: Blumon SDK AAR files (Production environment)
    "productionImplementation"(files("libs/blumon_sdk-prod.aar"))
    "productionImplementation"(files("libs/lib_services-1.2.0.0-PROD.aar"))

    // Common AAR (both flavors)
    implementation(files("libs/nativetouchevent-release.aar"))  // Native touch event library (moved from sdk module)

    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation(libs.androidx.appcompat)
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")

    // Material Design
    implementation(libs.material)
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Jetpack Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Timber for logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Hilt Dependency Injection
    implementation("com.google.dagger:hilt-android:2.57")
    ksp("com.google.dagger:hilt-compiler:2.57")

    // Networking (Retrofit + OkHttp)
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Socket.IO (Real-time communication)
    // Version 2.1.1 is latest stable compatible with Socket.IO Server 4.x
    implementation("io.socket:socket.io-client:2.1.1")

    // Security (EncryptedSharedPreferences)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // WorkManager (Offline sync + Heartbeat)
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    // JSON
    implementation("com.google.code.gson:gson:2.13.1")

    // QR Code
    implementation("com.google.zxing:core:3.5.3")

    // Firebase (App Distribution for OTA updates + Crashlytics for error tracking)
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-appdistribution-api:16.0.0-beta14")
    implementation("com.google.firebase:firebase-appdistribution:16.0.0-beta14")
    implementation("com.google.firebase:firebase-storage-ktx")  // Step 4: Verification photos
    implementation("com.google.firebase:firebase-crashlytics-ktx")  // Crash reporting for production
    implementation("com.google.firebase:firebase-analytics-ktx")  // Analytics (required by Crashlytics)

    // CameraX (Step 4: Verification photo capture)
    val cameraxVersion = "1.4.0"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // ZXing Barcode Scanner (Step 4: Product verification)
    // NOTE: Using ZXing instead of ML Kit because ML Kit requires armeabi-v7a
    // but PAX devices only support armeabi (required by Blumon SDK)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")

    // Room (para gestión de transacciones local)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Testing
    testImplementation(libs.junit)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("io.mockk:mockk:1.13.14")
    testImplementation("app.cash.turbine:turbine:1.0.0")  // Flow testing
    testImplementation("com.google.truth:truth:1.1.5")  // Assertions
    testImplementation("org.robolectric:robolectric:4.11.1")  // Android testing in JVM
    testImplementation("androidx.test:core:1.5.0")  // ApplicationProvider for testing
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.57")
    androidTestImplementation("app.cash.turbine:turbine:1.0.0")  // Flow testing
    androidTestImplementation("com.google.truth:truth:1.1.5")  // Assertions
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")  // Coroutine testing
    kspAndroidTest("com.google.dagger:hilt-compiler:2.57")
}
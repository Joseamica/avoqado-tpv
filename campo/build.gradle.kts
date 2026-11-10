plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.jaac.avoqado_campo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jaac.avoqado_tpv.campo"
        minSdk = 27
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Celulares modernos. :app conserva "armeabi" porque el SDK de Blumon lo exige.
        ndk {
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
            abiFilters.add("armeabi-v7a")
        }
        buildConfigField("String", "API_BASE_URL", "\"https://api.avoqado.io/api/v1/\"")
        buildConfigField("String", "STORAGE_ENV", "\"prod\"")
    }

    buildTypes {
        debug {
            // QA: `-Pavoqado.devBaseUrl=http://<ip-de-la-mac>:3000/api/v1/` (igual que :app).
            val devBaseUrl = (project.findProperty("avoqado.devBaseUrl") as String?)
                ?: "http://10.0.2.2:3000/api/v1/"
            buildConfigField("String", "API_BASE_URL", "\"$devBaseUrl\"")
            buildConfigField("String", "STORAGE_ENV", "\"dev\"")
        }
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // MigracionTest (Robolectric) lee los esquemas exportados de los assets; las pruebas unitarias sólo ven los de la
    // variante probada (los de `test` no se mezclan), así que van en `debug` (unos KB en el APK de QA, nunca en release).
    sourceSets { getByName("debug").assets.srcDir("$projectDir/schemas") }
    testOptions { unitTests.isIncludeAndroidResources = true }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Room exporta el esquema de cada versión (se commitea): la v2 de la Tarea 12 escribe su migración contra éste.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)                       // trae los temas Theme.Material3.*
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")  // la que ya usa :app
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("com.google.dagger:hilt-android:2.57")
    ksp("com.google.dagger:hilt-compiler:2.57")

    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.mockk:mockk:1.13.12")
    // Reproduce de verdad lo que Retrofit+Gson lanzan ante un cuerpo que no es el JSON
    // esperado (portal cautivo de WiFi) — un fake no puede ejercitar el parseo real.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.room:room-testing:2.7.0")
    testImplementation("androidx.work:work-testing:2.9.0")
    testImplementation("androidx.test:core:1.6.1")
}

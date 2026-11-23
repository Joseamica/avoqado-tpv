// Motor de cobro · laboratorio (SDK propio, fase 3, bloque 4-L · L-a). App de pruebas APARTE para la PAX del laboratorio,
// contra el sandbox de Blumon. No tiene sabores: los builds de los negocios (`production` y `nexgoProd` de :app) no la arman.
// No depende de :app. Patrón: el laboratorio viejo `lectorlab` (46a3ba37, rama spike/lector-pax).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.avoqado.motorlab"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jaac.avoqado_tpv.sandbox.motorlab" // la puerta de la PAX sólo instala com.jaac.avoqado_tpv.sandbox(.…)*
        minSdk = 27
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-sandbox" // la puerta exige «-sandbox» a toda TPV de pruebas
        // Las librerías nativas de PAX (:emv y :sdk) sólo vienen para armeabi, igual que en :app.
        ndk {
            abiFilters.clear()
            abiFilters.add("armeabi")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation(project(":cobropropio"))
    // El motor trae OkHttp como `compileOnly` (cobropropio/build.gradle.kts): quien lo usa pone la de verdad, la misma versión.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // El lector es `suspend` y se cancela con la espera del motor (bloque 4-L · L-c): la misma versión que :app y :cobropropio.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation(project(":emv"))
    implementation(project(":sdk"))
    implementation(project(":commonlib"))
    // ponytail: del SDK de Blumon de sandbox sólo se usarán (L-c, L-d) los puentes al aparato y el par Basic de `ApiConstants`;
    // el cobro es del motor.
    implementation(files("../app/libs/blumon_sdk-1.6.1.2-sandbox.aar"))
    implementation(files("../app/libs/lib_services-1.6.1.2-SANDBOX.aar"))
    // :emv trae annotation-jvm 1.7.1 y otra dependencia la 1.3.0: subir a 1.7.1 las une (como lectorlab).
    implementation("androidx.annotation:annotation:1.7.1")
    implementation(libs.material) // los recursos del AAR de Blumon usan Theme.Material3.*

    testImplementation(libs.junit)
}

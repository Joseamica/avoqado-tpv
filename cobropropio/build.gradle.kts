// SDK propio de cobro (fase 2): la PAX habla directo con Blumon. Módulo INERTE: :app no depende de él.
plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.avoqado.cobropropio"
    compileSdk = 36
    defaultConfig {
        minSdk = 27
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// Fase 3, bloque 1: la superficie pública es una lista cerrada (GuardasTest); cada declaración pública lleva `public` escrito.
kotlin { explicitApi() }

// La libreta (Room) exporta su esquema: es el DDL canónico para cualquier migración futura.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

// El recorrido real del sandbox (SesionSandboxTest) lee un archivo fuera de git: con él, las pruebas nunca salen de caché.
tasks.withType<Test>().configureEach {
    if (System.getenv("COBRO_PROPIO_SANDBOX_FILE") != null) {
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
    // Ola final, 6ª parte: si una prueba truena, el log del CI dice por qué (el mensaje del assert y su pila completa).
    testLogging {
        events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    ksp("androidx.room:room-compiler:2.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("app.cash.turbine:turbine:1.0.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core) // sólo trae el AndroidJUnitRunner, con la versión de :app
    androidTestImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}

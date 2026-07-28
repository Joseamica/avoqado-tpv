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
        versionCode = 93
        versionName = "2.7.1"

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
        buildConfigField("boolean", "ENABLE_PAX_SDK", "true")
        buildConfigField("boolean", "ENABLE_BLUMON_INIT", "true")
        buildConfigField("boolean", "ANGELPAY_SDK_ENABLED", "false")
        buildConfigField("boolean", "ANGELPAY_SDK_FALLBACK_ENABLED", "true")

        // Demo-only serial override. "" on every normal flavor → inert (DeviceInfoManager
        // ignores it). ONLY the gymDemo ("Avoqado Demo") flavor sets a value, and the
        // override is additionally gated behind BuildConfig.DEBUG. See gymDemo flavor below.
        buildConfigField("String", "OVERRIDE_TERMINAL_SERIAL", "\"\"")

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

            // Firebase Storage environment prefix (dev/prod)
            buildConfigField("String", "STORAGE_ENV_PREFIX", "\"dev\"")

            // Blumon Sandbox Environment
            buildConfigField("String", "BLUMON_ENV", "\"SAND\"")
            buildConfigField("String", "TOKEN_SERVER_URL", "\"https://sandbox-tokener.blumonpay.net\"")
            buildConfigField("String", "CORE_SERVER_URL", "\"https://sandbox-core.blumonpay.net\"")

            // Default terminal config (for fallback - will be fetched from backend)
            buildConfigField("String", "DEFAULT_TERMINAL_SERIAL", "\"2841548417\"")
            buildConfigField("String", "DEFAULT_TERMINAL_BRAND", "\"PAX\"")
            buildConfigField("String", "DEFAULT_TERMINAL_MODEL", "\"A910S\"")

            // Processor selector (Task 21 — spec §6.1). Resolver in Task 25 reads this.
            buildConfigField("String", "SUPPORTED_PROCESSOR", "\"BLUMON\"")

            // AngelPay not used on PAX sandbox — empty placeholders
            buildConfigField("String", "ANGELPAY_QA_EMAIL", "\"\"")
            buildConfigField("String", "ANGELPAY_QA_PASSWORD", "\"\"")
            buildConfigField("String", "ANGELPAY_QA_AFFILIATION", "\"\"")
            buildConfigField("String", "ANGELPAY_QA_COMMERCE_TOKEN", "\"\"")
            // ANGELPAY_ENV — empty placeholder so shared `AngelPayCredentialResolver`
            // in main/ compiles on PAX flavors. PAX flavors never read this value
            // (resolver short-circuits when SUPPORTED_PROCESSOR != "ANGELPAY").
            buildConfigField("String", "ANGELPAY_ENV", "\"\"")
        }

        create("production") {
            dimension = "environment"

            // Firebase Storage environment prefix (dev/prod)
            buildConfigField("String", "STORAGE_ENV_PREFIX", "\"prod\"")

            // Blumon Production Environment
            buildConfigField("String", "BLUMON_ENV", "\"PROD\"")
            buildConfigField("String", "TOKEN_SERVER_URL", "\"https://tokener.blumonpay.net\"")
            buildConfigField("String", "CORE_SERVER_URL", "\"https://core.blumonpay.net\"")

            // Default terminal config (for fallback - will be fetched from backend)
            buildConfigField("String", "DEFAULT_TERMINAL_SERIAL", "\"2841548417\"")
            buildConfigField("String", "DEFAULT_TERMINAL_BRAND", "\"PAX\"")
            buildConfigField("String", "DEFAULT_TERMINAL_MODEL", "\"A910S\"")

            // Processor selector (Task 21 — spec §6.1). Resolver in Task 25 reads this.
            buildConfigField("String", "SUPPORTED_PROCESSOR", "\"BLUMON\"")

            // AngelPay not used on PAX production — empty placeholders
            buildConfigField("String", "ANGELPAY_QA_EMAIL", "\"\"")
            buildConfigField("String", "ANGELPAY_QA_PASSWORD", "\"\"")
            buildConfigField("String", "ANGELPAY_QA_AFFILIATION", "\"\"")
            buildConfigField("String", "ANGELPAY_QA_COMMERCE_TOKEN", "\"\"")
            // ANGELPAY_ENV — empty placeholder so shared `AngelPayCredentialResolver`
            // in main/ compiles on PAX flavors. PAX flavors never read this value
            // (resolver short-circuits when SUPPORTED_PROCESSOR != "ANGELPAY").
            buildConfigField("String", "ANGELPAY_ENV", "\"\"")
        }

        create("tutorialEmu") {
            dimension = "environment"
            // Reuse sandbox flavor source/dependency graph where possible.
            matchingFallbacks += listOf("sandbox")
            versionNameSuffix = "-tutorial-emu"

            // Firebase Storage environment prefix
            buildConfigField("String", "STORAGE_ENV_PREFIX", "\"dev\"")

            // Keep sandbox endpoints for safer local screenshots/tutorial runs.
            buildConfigField("String", "BLUMON_ENV", "\"SAND\"")
            buildConfigField("String", "TOKEN_SERVER_URL", "\"https://sandbox-tokener.blumonpay.net\"")
            buildConfigField("String", "CORE_SERVER_URL", "\"https://sandbox-core.blumonpay.net\"")

            // Default terminal config (for fallback - will be fetched from backend)
            buildConfigField("String", "DEFAULT_TERMINAL_SERIAL", "\"2841548417\"")
            buildConfigField("String", "DEFAULT_TERMINAL_BRAND", "\"PAX\"")
            buildConfigField("String", "DEFAULT_TERMINAL_MODEL", "\"A910S\"")

            // Emulator-only mode: disable native PAX/Blumon initialization.
            buildConfigField("boolean", "ENABLE_PAX_SDK", "false")
            buildConfigField("boolean", "ENABLE_BLUMON_INIT", "false")
            buildConfigField("boolean", "ANGELPAY_SDK_ENABLED", "false")
            buildConfigField("boolean", "ANGELPAY_SDK_FALLBACK_ENABLED", "true")

            // Processor selector (Task 21 — spec §6.1). Emulator/tutorial → BLUMON path
            // (no real processor invoked, but resolver in Task 25 needs a value).
            buildConfigField("String", "SUPPORTED_PROCESSOR", "\"BLUMON\"")

            // AngelPay QA credentials — emptied in Task 21. Kept as empty placeholders
            // because shared code in main/ references them at compile time. tutorialEmu
            // is emulator/screenshots only — no real AngelPay traffic.
            buildConfigField("String", "ANGELPAY_QA_EMAIL", "\"\"")
            buildConfigField("String", "ANGELPAY_QA_PASSWORD", "\"\"")
            buildConfigField("String", "ANGELPAY_QA_AFFILIATION", "\"\"")
            buildConfigField("String", "ANGELPAY_QA_COMMERCE_TOKEN", "\"\"")
            // ANGELPAY_ENV — empty placeholder so shared `AngelPayCredentialResolver`
            // in main/ compiles on emulator/tutorial flavor (SUPPORTED_PROCESSOR=BLUMON).
            buildConfigField("String", "ANGELPAY_ENV", "\"\"")

            // Allow installation on modern emulator ABIs.
            ndk {
                abiFilters.clear()
                abiFilters.add("arm64-v8a")
                abiFilters.add("x86_64")
            }
        }

        create("nexgo") {
            dimension = "environment"
            // Reuse sandbox-specific PaymentViewModel/BlumonInitializer (not used, but required for compilation)
            matchingFallbacks += listOf("sandbox")
            // Uses sandbox package ID (.sandbox) — registered in Firebase.
            // Backend Avoqado + AngelPay QA. Visualmente lleva el badge "a"
            // de sandbox (sourceSet incluye src/sandbox/res) para diferenciar
            // de la build productiva real (`nexgoProd`).
            applicationIdSuffix = ".sandbox"
            versionNameSuffix = "-nexgo"

            // Firebase Storage environment prefix
            buildConfigField("String", "STORAGE_ENV_PREFIX", "\"dev\"")

            // AngelPay QA environment — uses sandbox backend + AngelPay QA app
            buildConfigField("String", "BLUMON_ENV", "\"SAND\"")
            buildConfigField("String", "TOKEN_SERVER_URL", "\"https://sandbox-tokener.blumonpay.net\"")
            buildConfigField("String", "CORE_SERVER_URL", "\"https://sandbox-core.blumonpay.net\"")

            // Nexgo N86 terminal defaults
            buildConfigField("String", "DEFAULT_TERMINAL_SERIAL", "\"NEXGO-N86\"")
            buildConfigField("String", "DEFAULT_TERMINAL_BRAND", "\"NEXGO\"")
            buildConfigField("String", "DEFAULT_TERMINAL_MODEL", "\"N86\"")

            // No Blumon SDK on Nexgo — payments via AngelPay app-to-app Intent
            buildConfigField("boolean", "ENABLE_PAX_SDK", "false")
            buildConfigField("boolean", "ENABLE_BLUMON_INIT", "false")
            buildConfigField("boolean", "ANGELPAY_SDK_ENABLED", "true")
            buildConfigField("boolean", "ANGELPAY_SDK_FALLBACK_ENABLED", "false")

            // Processor selector (Task 21 — spec §6.1). Resolver in Task 25 reads this.
            buildConfigField("String", "SUPPORTED_PROCESSOR", "\"ANGELPAY\"")
            // AngelPay environment selector (Task 21). Resolver uses QA endpoints + creds.
            buildConfigField("String", "ANGELPAY_ENV", "\"QA\"")

            // Transition fallback (D4 — spec §6.1) — empty defaults during the 30-day
            // grace period to support dual-source resolver fallback in Task 25.
            // Real creds now flow exclusively via backend `/tpv/terminals/:serial/config`
            // → AngelPayAuthPayload (Task 13). Remove these field declarations entirely
            // in v3 after the grace window closes.
            buildConfigField("String", "ANGELPAY_QA_EMAIL", "\"\"")
            buildConfigField("String", "ANGELPAY_QA_PASSWORD", "\"\"")
            buildConfigField("String", "ANGELPAY_QA_AFFILIATION", "\"\"")
            buildConfigField("String", "ANGELPAY_QA_COMMERCE_TOKEN", "\"\"")

            // Nexgo N86 is arm64-v8a (Android 9, API 28)
            ndk {
                abiFilters.clear()
                abiFilters.add("armeabi-v7a")
                abiFilters.add("arm64-v8a")
            }
        }

        create("nexgoProd") {
            dimension = "environment"
            // Productive Nexgo build (Avoqado backend prod + AngelPay prod).
            // Reuses the production package id `com.jaac.avoqado_tpv` (no
            // suffix) because `.nexgo` is not yet registered in Firebase
            // (see google-services.json). Coexists with `nexgo` (.sandbox)
            // on the same Nexgo terminal — different package ids. Will not
            // coexist with `productionRelease` PAX builds (same package),
            // but PAX and Nexgo are different hardware so that's fine.
            // ⚠️ TODO: register `com.jaac.avoqado_tpv.nexgo` in Firebase
            // and switch `applicationIdSuffix = ".nexgo"`.
            versionNameSuffix = "-nexgo-prod"

            // Firebase Storage environment prefix (prod)
            buildConfigField("String", "STORAGE_ENV_PREFIX", "\"prod\"")

            // Avoqado backend + Blumon endpoints (prod) — Blumon URLs are
            // unused on Nexgo (no PAX SDK init) but kept consistent with
            // the production flavor for clarity.
            buildConfigField("String", "BLUMON_ENV", "\"PROD\"")
            buildConfigField("String", "TOKEN_SERVER_URL", "\"https://tokener.blumonpay.net\"")
            buildConfigField("String", "CORE_SERVER_URL", "\"https://core.blumonpay.net\"")

            // Nexgo N86 terminal defaults
            buildConfigField("String", "DEFAULT_TERMINAL_SERIAL", "\"NEXGO-N86\"")
            buildConfigField("String", "DEFAULT_TERMINAL_BRAND", "\"NEXGO\"")
            buildConfigField("String", "DEFAULT_TERMINAL_MODEL", "\"N86\"")

            // No Blumon SDK on Nexgo — payments via AngelPay SDK
            buildConfigField("boolean", "ENABLE_PAX_SDK", "false")
            buildConfigField("boolean", "ENABLE_BLUMON_INIT", "false")
            buildConfigField("boolean", "ANGELPAY_SDK_ENABLED", "true")
            buildConfigField("boolean", "ANGELPAY_SDK_FALLBACK_ENABLED", "false")

            // Processor selector (Task 21 — spec §6.1). Resolver in Task 25 reads this.
            buildConfigField("String", "SUPPORTED_PROCESSOR", "\"ANGELPAY\"")
            // AngelPay environment selector (Task 21). Resolver uses PROD endpoints + creds.
            buildConfigField("String", "ANGELPAY_ENV", "\"PROD\"")

            // Transition fallback (D4 — spec §6.1) — empty defaults during the 30-day
            // grace period. Real prod credentials now flow exclusively via backend
            // `/tpv/terminals/:serial/config` → AngelPayAuthPayload (Task 13). The
            // previously-hardcoded QA creds were removed in Task 21 to eliminate the
            // "QA creds in PROD APK" footgun documented in spec §17 (R3). Remove these
            // field declarations entirely in v3 after the grace window closes.
            buildConfigField("String", "ANGELPAY_QA_EMAIL", "\"\"")
            buildConfigField("String", "ANGELPAY_QA_PASSWORD", "\"\"")
            buildConfigField("String", "ANGELPAY_QA_AFFILIATION", "\"\"")
            buildConfigField("String", "ANGELPAY_QA_COMMERCE_TOKEN", "\"\"")

            // Nexgo N86 is arm32 / arm64 (Android 9, API 28)
            ndk {
                abiFilters.clear()
                abiFilters.add("armeabi-v7a")
                abiFilters.add("arm64-v8a")
            }
        }

        // ⭐ DEMO (2026-07-07): "Avoqado Demo" — money-safe TPV demo build.
        // SANDBOX Blumon processor (test cards, NO real money) BUT pointed at the
        // PRODUCTION backend, so the sale reflects in the prod demo venue
        // (avoqado-fitness). Coexists with production/sandbox (own `.demo` id → 2 icons).
        // Backed by a SANDBOX MerchantAccount copied into prod + Terminal AVQD-2841548418
        // (see avoqado-server docs/guides/VENUE_CREATION_GUIDE.md §7b, memory note
        // `avoqado-fitness-prod-sandbox-tpv`). Reuses src/sandbox/java+res via sourceSets +
        // gymDemoImplementation deps. ⚠️ NEVER sign/ship. Normal variants are UNTOUCHED.
        create("gymDemo") {
            dimension = "environment"
            matchingFallbacks += listOf("sandbox")
            // Own package id `.demo` → coexists with BOTH the production build (base id) AND
            // the normal dev `sandbox` build (`.sandbox`) → 3 distinct icons on one device.
            // Requires a `com.jaac.avoqado_tpv.demo` client in google-services.json (added by
            // cloning the sandbox client — Firebase metrics for this demo build report under the
            // sandbox Firebase app, which is fine for a demo). To give it a real Firebase app,
            // register `com.jaac.avoqado_tpv.demo` in the Firebase console and re-download the json.
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            resValue("string", "app_name", "Demo Prod")

            buildConfigField("String", "STORAGE_ENV_PREFIX", "\"prod\"")

            // SANDBOX Blumon processor → test cards, no real money
            buildConfigField("String", "BLUMON_ENV", "\"SAND\"")
            buildConfigField("String", "TOKEN_SERVER_URL", "\"https://sandbox-tokener.blumonpay.net\"")
            buildConfigField("String", "CORE_SERVER_URL", "\"https://sandbox-core.blumonpay.net\"")

            // ⭐ DECOUPLE: point the "_DEV" URLs (used by provideBaseUrl/socket when
            // BLUMON_ENV != "PROD") at PRODUCTION, so this build hits the prod backend
            // WITHOUT touching NetworkModule or any other flavor.
            buildConfigField("String", "API_BASE_URL_DEV", "\"https://api.avoqado.io/api/v1/\"")
            buildConfigField("String", "SOCKET_URL_DEV", "\"https://api.avoqado.io\"")

            // ⭐ Serial override → resolves to the prod gym Terminal. DEBUG-gated in
            // DeviceInfoManager; "" on every other flavor.
            buildConfigField("String", "OVERRIDE_TERMINAL_SERIAL", "\"AVQD-2841548418\"")

            buildConfigField("String", "DEFAULT_TERMINAL_SERIAL", "\"2841548418\"")
            buildConfigField("String", "DEFAULT_TERMINAL_BRAND", "\"PAX\"")
            buildConfigField("String", "DEFAULT_TERMINAL_MODEL", "\"A910S\"")

            buildConfigField("String", "SUPPORTED_PROCESSOR", "\"BLUMON\"")

            // AngelPay unused on the PAX/Blumon path — empty placeholders so shared
            // main/ code compiles.
            buildConfigField("String", "ANGELPAY_QA_EMAIL", "\"\"")
            buildConfigField("String", "ANGELPAY_QA_PASSWORD", "\"\"")
            buildConfigField("String", "ANGELPAY_QA_AFFILIATION", "\"\"")
            buildConfigField("String", "ANGELPAY_QA_COMMERCE_TOKEN", "\"\"")
            buildConfigField("String", "ANGELPAY_ENV", "\"\"")
        }
    }

    sourceSets {
        // Expose Room's exported schema JSONs (app/schemas/) to instrumented tests so
        // MigrationTestHelper can create a DB at an old version and validate migrations
        // against the canonical schema. Required by AvoqadoDatabaseMigrationTest.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")

        getByName("tutorialEmu") {
            // Reuse sandbox-specific implementation files for tutorial emulator builds.
            java.srcDirs("src/sandbox/java")
            res.srcDirs("src/sandbox/res")
        }
        getByName("nexgo") {
            // Reuse sandbox-specific implementation files (PaymentViewModel, BlumonInitializer).
            // These compile but are not invoked on Nexgo — AngelPay flow is in main/.
            // Reusing res too so the launcher icon shows the sandbox "a" badge —
            // visually distinguishes this from `nexgoProd`.
            java.srcDirs("src/sandbox/java")
            res.srcDirs("src/sandbox/res")
        }
        getByName("nexgoProd") {
            // Reuse sandbox-specific implementation files (PaymentViewModel,
            // BlumonInitializer) — compile only, never invoked on Nexgo.
            // Picking sandbox/java (not production/java) avoids pulling
            // lib_services-PROD.aar into the Nexgo classpath since it lives
            // under productionImplementation only.
            // Does NOT include sandbox/res, so the launcher icon comes from
            // src/main/res (clean, no badge) — that's how you tell `nexgoProd`
            // apart from `nexgo` on the device.
            java.srcDirs("src/sandbox/java")
        }
        getByName("gymDemo") {
            // "Avoqado Demo" reuses the sandbox Blumon implementation (BlumonInitializer,
            // InitializationManager, sandbox PaymentViewModel) + res (sandbox "a" badge icon
            // → visually distinct from the prod app). app_name overridden via resValue above.
            java.srcDirs("src/sandbox/java")
            res.srcDirs("src/sandbox/res")
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
        resources {
            excludes += setOf(
                "META-INF/versions/**",
                "META-INF/NOTICE.md",
                "META-INF/LICENSE.md",
                "META-INF/*.kotlin_module"
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.jvmArgs("-Xmx2g")
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

// Room schema export (app/schemas/, committed to git). Required so migrations can be
// written against the exact DDL Room validates, and enables MigrationTestHelper later.
// Zero runtime cost — JSON is generated at compile time only.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// AngelPay ships a fat AAR with okhttp/okio classes included.
// For nexgo variants, exclude Maven okhttp/okio artifacts to avoid duplicate classes.
configurations.configureEach {
    if (name.startsWith("nexgo")) {
        exclude(group = "com.squareup.okhttp3", module = "okhttp")
        exclude(group = "com.squareup.okio", module = "okio")
        exclude(group = "com.squareup.okio", module = "okio-jvm")
    }
}

// Defense-in-depth (Task 21 — spec §17.5): variant-scoped jniLibs excludes for
// Nexgo APKs. PAX SDK natives live under sdk/src/main/jniLibs/armeabi/, which
// Nexgo's abiFilters (armeabi-v7a + arm64-v8a) already exclude by ABI mismatch;
// these patterns are a belt-and-suspenders guard for any future PAX SDK updates
// that might ship arm64-v8a variants of PAX-only libs.
//
// IMPORTANT: this MUST NOT apply to PAX flavors (sandbox/production) — those
// builds NEED these natives at runtime for Blumon EMV. Variant-scoped via
// `androidComponents.onVariants` so PAX builds are untouched.
//
// NOTE: as of Task 21, no `libcyber*.so` files exist in the project (plan's
// placeholder pattern kept for safety). Actual PAX-only natives observed:
// `libJNI_*`, `libF_*_PayDroid`, `libHDSD`, `libDeviceConfig`, `libDCL`.
// Nexgo natives (`libnexgo_*`) are explicitly preserved (not in this list).
// Task 38 will verify the final list against an actual Nexgo APK build.
androidComponents {
    onVariants(selector().withFlavor("environment" to "nexgo")) { variant ->
        variant.packaging.jniLibs.excludes.addAll(
            // Wildcard-strip armeabi tree — AAR-shipped natives (libIGLBarDecoder 3.9MB,
            // libmetaprotect_sdk 195KB, libdecoder_jni 102KB, libimgprocessing 146KB,
            // libserialport 17KB) bypass abiFilters because they come from transitive
            // dependencies. Nexgo runs armeabi-v7a + arm64-v8a only, so armeabi is dead
            // weight. Saves ~5.7MB on the APK (verified: 12 entries → 0 entries).
            "lib/armeabi/**",
            "**/libcyber*.so",
            "**/libF_*_PayDroid.so",
            "**/libJNI_*.so",
            "**/libHDSD.so",
            "**/libDeviceConfig.so",
            "**/libDCL.so",
        )
    }
    onVariants(selector().withFlavor("environment" to "nexgoProd")) { variant ->
        variant.packaging.jniLibs.excludes.addAll(
            // Wildcard-strip armeabi tree — AAR-shipped natives (libIGLBarDecoder 3.9MB,
            // libmetaprotect_sdk 195KB, libdecoder_jni 102KB, libimgprocessing 146KB,
            // libserialport 17KB) bypass abiFilters because they come from transitive
            // dependencies. Nexgo runs armeabi-v7a + arm64-v8a only, so armeabi is dead
            // weight. Saves ~5.7MB on the APK (verified: 12 entries → 0 entries).
            "lib/armeabi/**",
            "**/libcyber*.so",
            "**/libF_*_PayDroid.so",
            "**/libJNI_*.so",
            "**/libHDSD.so",
            "**/libDeviceConfig.so",
            "**/libDCL.so",
        )
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

    // gymDemo ("Avoqado Demo") reuses the sandbox Blumon SDK + lib-services (sandbox aar)
    "gymDemoImplementation"(files("libs/blumon_sdk-debug.aar"))
    "gymDemoImplementation"(files("libs/lib-services-BP-SAND_1601.aar"))

    // ⭐ TUTORIAL EMULATOR FLAVOR: reuse sandbox SDK API surface for compilation.
    "tutorialEmuImplementation"(files("libs/blumon_sdk-debug.aar"))
    "tutorialEmuImplementation"(files("libs/lib-services-BP-SAND_1601.aar"))

    // ⭐ NEXGO FLAVOR: reuse sandbox SDK API surface for Hilt compilation.
    // These AARs provide Java class stubs — native .so libs are NOT invoked on Nexgo.
    //
    // STUB ONLY (spec §17.5): Blumon AAR is packaged for Nexgo flavors to satisfy
    // compile-time symbol resolution in shared code that references Blumon types.
    // No Blumon entry-point may be invoked at runtime in Nexgo builds — enforce via
    // BuildConfig.SUPPORTED_PROCESSOR == "BLUMON" guards. Same applies to nexgoProd
    // below. PAX native .so live under sdk/src/main/jniLibs/armeabi/ which Nexgo
    // ABIs (armeabi-v7a + arm64-v8a) already exclude by abiFilters mismatch; the
    // packagingOptions block above adds defense-in-depth.
    "nexgoImplementation"(files("libs/blumon_sdk-debug.aar"))
    "nexgoImplementation"(files("libs/lib-services-BP-SAND_1601.aar"))

    // ⭐ NEXGO PROD FLAVOR: same SDK API surface as nexgo (so Hilt can resolve
    // TokenServer / *UseCase types). Native libs never run on Nexgo. See
    // STUB ONLY note above (spec §17.5).
    "nexgoProdImplementation"(files("libs/blumon_sdk-debug.aar"))
    "nexgoProdImplementation"(files("libs/lib-services-BP-SAND_1601.aar"))

    // ⭐ PRODUCTION FLAVOR: Blumon SDK AAR files (Production environment)
    "productionImplementation"(files("libs/blumon_sdk-prod.aar"))
    "productionImplementation"(files("libs/lib_services-1.2.0.0-PROD.aar"))

    // Common AAR (both flavors)
    implementation(files("libs/nativetouchevent-release.aar"))  // Native touch event library (moved from sdk module)
    // AngelPay fat AAR:
    // - compileOnly for all flavors (types available at compile time in main/)
    // - packaged only in nexgo / nexgoProd to avoid duplicate transitive classes
    compileOnly(files("libs/angelpaySDK-v1.0.17-fat-release.aar"))
    "nexgoImplementation"(files("libs/angelpaySDK-v1.0.17-fat-release.aar"))
    "nexgoProdImplementation"(files("libs/angelpaySDK-v1.0.17-fat-release.aar"))
    // AngelPay SDK types are referenced in unit tests (e.g., AngelPaySdkGatewayTest) for
    // mocking — Result<List<MerchantSummary>> return types and `AngelPaySDK` object need
    // the AAR on the test classpath at both compile time and runtime so MockK can mock
    // the singleton via `mockkObject(AngelPaySDK)`. Production code still uses compileOnly
    // for non-Nexgo flavors, so this affects unit tests only.
    testImplementation(files("libs/angelpaySDK-v1.0.17-fat-release.aar"))

    // AngelPay SDK 1.0.7 fat-release.aar ships the full io.github.binaryfoo:emv-bertlv
    // bundle internally (AmexTags, DecodedData, CryptogramInformationDecoder,
    // CryptogramType, RootDecoder, etc.). NO external dep needed. The R8 keep
    // rules in proguard-rules.pro (`-keep class io.github.binaryfoo.**`) are
    // what's actually required — without them the SDK's reflection-based
    // `Class.forName("io.github.binaryfoo.decoders.CryptogramInformationDecoder")`
    // throws ClassNotFoundException at chip-payment time on release builds.

    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
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
    // OkHttp is flavor-scoped to avoid duplicate classes with AngelPay fat AAR on nexgo.
    "sandboxImplementation"("com.squareup.okhttp3:okhttp:4.12.0")
    "sandboxImplementation"("com.squareup.okhttp3:logging-interceptor:4.12.0")
    "gymDemoImplementation"("com.squareup.okhttp3:okhttp:4.12.0")
    "gymDemoImplementation"("com.squareup.okhttp3:logging-interceptor:4.12.0")
    "productionImplementation"("com.squareup.okhttp3:okhttp:4.12.0")
    "productionImplementation"("com.squareup.okhttp3:logging-interceptor:4.12.0")
    "tutorialEmuImplementation"("com.squareup.okhttp3:okhttp:4.12.0")
    "tutorialEmuImplementation"("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Nexgo gets OkHttp from AngelPay fat AAR. Keep logging-interceptor only, excluding bundled okhttp/okio.
    "nexgoImplementation"("com.squareup.okhttp3:logging-interceptor:4.12.0") {
        exclude(group = "com.squareup.okhttp3", module = "okhttp")
        exclude(group = "com.squareup.okio", module = "okio-jvm")
    }
    "nexgoProdImplementation"("com.squareup.okhttp3:logging-interceptor:4.12.0") {
        exclude(group = "com.squareup.okhttp3", module = "okhttp")
        exclude(group = "com.squareup.okio", module = "okio-jvm")
    }
    // AngelPay SDK serializers are generated against kotlinx.serialization 1.8+ APIs.
    // Keep Nexgo aligned to avoid AbstractMethodError in GeneratedSerializer.
    "nexgoImplementation"("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    "nexgoProdImplementation"("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

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

    // Google Play Services Location (GPS for clock-in/out)
    implementation("com.google.android.gms:play-services-location:21.3.0")

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

    // ExoPlayer (Media3) - Video playback for training videos
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")

    // Room (align with AngelPay SDK generated database API)
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")

    // Testing
    testImplementation(libs.junit)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("io.mockk:mockk:1.13.14")
    testImplementation("app.cash.turbine:turbine:1.0.0")  // Flow testing
    testImplementation("com.google.truth:truth:1.1.5")  // Assertions
    testImplementation("org.robolectric:robolectric:4.11.1")  // Android testing in JVM
    testImplementation("androidx.test:core:1.5.0")  // ApplicationProvider for testing
    testImplementation("androidx.work:work-testing:2.9.0")  // TestListenableWorkerBuilder (F-9 — PaymentSyncWorkerTest)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.57")
    androidTestImplementation("app.cash.turbine:turbine:1.0.0")  // Flow testing
    androidTestImplementation("com.google.truth:truth:1.1.5")  // Assertions
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")  // Coroutine testing
    androidTestImplementation("androidx.room:room-testing:2.7.0")  // MigrationTestHelper (DB upgrade validation)
    kspAndroidTest("com.google.dagger:hilt-compiler:2.57")
}

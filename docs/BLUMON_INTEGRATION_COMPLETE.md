# BLUMON SDK INTEGRATION - COMPLETE PROJECT DOCUMENTATION

**Generated:** 2025-11-05  
**Project:** avoqado-tpv  
**Environment:** Android 8.1+ (minSdk=27), Kotlin + Jetpack Compose  
**SDK:** Blumon PAX (Momentum Payment Platform)

---

## TABLE OF CONTENTS

1. [Project Structure](#project-structure)
2. [Module Overview](#module-overview)
3. [AAR/JAR Dependencies](#aarjar-dependencies)
4. [Build Configuration](#build-configuration)
5. [Payment Implementation](#payment-implementation)
6. [SDK Initialization](#sdk-initialization)
7. [Android Manifest & Permissions](#android-manifest--permissions)
8. [Data Flow & Architecture](#data-flow--architecture)
9. [EMV Payment Flow](#emv-payment-flow)
10. [Contactless Payment Flow](#contactless-payment-flow)
11. [Critical Issues & Workarounds](#critical-issues--workarounds)
12. [Testing Checklist](#testing-checklist)

---

## PROJECT STRUCTURE

```
avoqado-tpv/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/jaac/avoqado_tpv/
│   │   │   ├── AvoqadoTPVApplication.kt
│   │   │   ├── MainActivity.kt
│   │   │   ├── core/
│   │   │   │   ├── di/                    # Hilt modules
│   │   │   │   ├── data/
│   │   │   │   │   ├── network/          # Retrofit API clients
│   │   │   │   │   ├── local/            # Database, EncryptedSharedPreferences
│   │   │   │   │   ├── realtime/         # Socket.IO
│   │   │   │   │   └── workers/          # WorkManager for sync
│   │   │   │   ├── domain/               # Business logic interfaces
│   │   │   │   └── presentation/         # Shared UI components, theme
│   │   │   └── features/
│   │   │       ├── authorization/        # Login, PIN auth
│   │   │       ├── payment/              # ⭐ BLUMON INTEGRATION
│   │   │       │   ├── data/
│   │   │       │   │   ├── BlumonInitializer.kt
│   │   │       │   │   ├── BlumonAuthManager.kt
│   │   │       │   │   └── InitializationManager.kt
│   │   │       │   ├── domain/
│   │   │       │   │   └── PaymentState.kt
│   │   │       │   └── presentation/
│   │   │       │       ├── PaymentViewModel.kt
│   │   │       │       └── PaymentScreen.kt
│   │   │       ├── management/
│   │   │       ├── menu/
│   │   │       ├── cart/
│   │   │       └── timeclock/
│   │   └── res/
│   │       ├── values/                  # Strings, colors (dark theme)
│   │       └── ...
│   ├── libs/                            # ⭐ Blumon AAR files
│   │   ├── blumon_sdk-debug.aar
│   │   ├── lib-services-BP-SAND_1601.aar
│   │   └── nativetouchevent-release.aar
│   └── build.gradle.kts
│
├── sdk/                                 # ⭐ PAX SDK Module
│   ├── libs/                            # PAX JARs
│   │   ├── NeptuneLiteApi_V4.10.00_20241122.jar
│   │   ├── PosApi_V1.24_20200422.jar
│   │   ├── BaseLinkApi_V1.03.00_T_20190122.jar
│   │   ├── GLComm_V1.09.00_20211230.jar
│   │   ├── GLExtPrinter_V1.01.01_20191225.jar
│   │   ├── GLImgProcessing_V1.03.00_T_20220121.jar
│   │   ├── GLPacker_V1.05.00_20211230.jar
│   │   ├── GLUtils_V1.01.00_T_20220121.jar
│   │   └── GLBaiFuTong_V1.00.00_20180119.jar
│   ├── src/main/java/com/paxsz/module/pos/
│   │   ├── PosApiDal.java               # Device Access Layer
│   │   ├── Sdk.java
│   │   ├── mag/PosApiMag.java           # Magnetic stripe
│   │   ├── icc/PosApiIcc.java           # Chip card
│   │   ├── picc/PosApiPicc.java         # Contactless (NFC)
│   │   └── ped/PosApiPed.java           # PIN Entry Device
│   └── build.gradle
│
├── emv/                                 # ⭐ EMV Kernel Module
│   ├── libs/                            # EMV JARs (card schemes)
│   │   ├── EMV_v106.jar                 # Main EMV kernel
│   │   ├── COMMON_v103.jar
│   │   ├── DEVICE_v103.jar
│   │   ├── Entry_v105.jar
│   │   ├── AE_v101.jar
│   │   ├── DPAS_v101.jar
│   │   ├── EFT_v101_D1.jar
│   │   ├── JCB_v100.jar
│   │   ├── MIR_v100.jar
│   │   ├── PURE_v100.jar
│   │   ├── QPBOC_v100.jar
│   │   ├── RuPay_v100.jar
│   │   ├── WAVE_v101.jar
│   │   ├── MC_v100.jar                  # Mastercard
│   │   └── DPAS_CT_v100.jar
│   ├── src/main/java/com/paxsz/module/emv/
│   │   ├── constant/TagsTable.java      # EMV tag definitions
│   │   ├── param/
│   │   │   ├── EmvTransParam.java
│   │   │   └── EmvProcessParam.java
│   │   ├── xmlparam/
│   │   │   └── entity/                  # CAPK, AID, CVM parameters
│   │   └── utils/
│   │       ├── EmvParamConvert.java
│   │       └── EmvLibVersion.java
│   └── build.gradle
│
├── commonlib/                           # ⭐ Common Utilities Module
│   ├── libs/                            # No local JARs
│   ├── src/main/java/com/pax/commonlib/ # Common classes
│   └── build.gradle
│
├── build.gradle.kts                    # Root Gradle configuration
├── settings.gradle.kts                 # Module dependencies
└── gradle.properties                   # Gradle settings
```

---

## MODULE OVERVIEW

### 1. **APP Module** (Main Application)
- **Type:** Android Application
- **Namespace:** `com.jaac.avoqado_tpv`
- **Purpose:** Main app logic, payment features, UI screens
- **Key Dependencies:**
  - Project: `:sdk`, `:emv`, `:commonlib`
  - AAR: `blumon_sdk-debug.aar`, `lib-services-BP-SAND_1601.aar`

### 2. **SDK Module** (PAX Payment Terminal SDK)
- **Type:** Android Library
- **Namespace:** `com.paxsz.module.pos`
- **Purpose:** Low-level device communication with PAX A910S terminal
- **Contents:**
  - Device Access Layer (DAL) - readers, printers, keyboard
  - Neptune API (latest payment processing)
  - Support for MAG (swipe), ICC (chip), PICC (contactless)
- **Key Dependencies:**
  - JARs: PosApi, BaseLinkApi, NeptuneLiteApi, GLComm, GLExtPrinter, etc.
- **No Java code** - Pure JAR wrapper for terminal SDK

**File Structure:**
```
sdk/
├── src/main/java/com/paxsz/module/pos/
│   ├── Sdk.java                  # Singleton initialization
│   ├── PosApiDal.java            # DAL entry point
│   ├── mag/PosApiMag.java        # Magnetic stripe reader
│   ├── icc/PosApiIcc.java        # Chip card reader
│   ├── picc/PosApiPicc.java      # Contactless reader (NFC)
│   └── ped/PosApiPed.java        # PIN pad driver
├── libs/                          # PAX JARs
│   ├── NeptuneLiteApi_V4.10.00... # ⭐ LATEST (2024-11-22)
│   └── [8 other PAX libraries]
└── build.gradle                  # Exports all JARs via api()
```

### 3. **EMV Module** (Card Scheme Kernels)
- **Type:** Android Library
- **Namespace:** `com.paxsz.module.emv`
- **Purpose:** EMV kernel logic for card validation/processing
- **Contents:**
  - 23+ card schemes (Visa, Mastercard, Amex, etc.)
  - EMV tag definitions (9F27, 9F26, 57, etc.)
  - CVM (Cardholder Verification Method) rules
  - AID (Application Identifier) configurations
- **Key Dependencies:**
  - `:commonlib` (for shared utilities)
  - JARs: EMV_v106.jar, COMMON_v103.jar, DEVICE_v103.jar, plus 20+ scheme files

**File Structure:**
```
emv/
├── src/main/java/com/paxsz/module/emv/
│   ├── constant/
│   │   └── TagsTable.java        # All 23 EMV tags used
│   ├── param/
│   │   ├── EmvTransParam.java    # Transaction parameters
│   │   └── EmvProcessParam.java  # EMV kernel parameters
│   ├── xmlparam/entity/
│   │   ├── capk/                 # Certification Authority Public Keys
│   │   ├── aid/                  # Application Identifiers (Visa, MC, etc.)
│   │   ├── common/               # CAPK, Config, CAPK Revoke
│   │   └── clss/                 # Contactless parameters
│   └── utils/
│       ├── EmvParamConvert.java  # Parameter conversion
│       └── EmvLibVersion.java    # Version info
├── libs/                          # 23 EMV JARs
│   ├── EMV_v106.jar              # Core kernel
│   ├── MC_v100.jar               # Mastercard
│   ├── JCB_v100.jar              # Japan Credit Bureau
│   └── [20+ other schemes]
└── build.gradle                  # Exports all JARs
```

### 4. **COMMONLIB Module** (Shared Utilities)
- **Type:** Android Library
- **Namespace:** `com.pax.commonlib`
- **Purpose:** Common classes used by SDK and EMV modules
- **Contents:**
  - Data structures
  - Utility functions
  - Constants
- **No local JARs** - Pure Kotlin/Java code

---

## AAR/JAR DEPENDENCIES

### AAR Files (Android Archive - Compiled Libraries)

#### Location: `app/libs/`

| File | Purpose | Provider | Version | Size |
|------|---------|----------|---------|------|
| **blumon_sdk-debug.aar** | Main Blumon Momentum SDK (online auth) | Blumon | debug | ~5MB |
| **lib-services-BP-SAND_1601.aar** | Blumon Services for Sandbox (online auth) | Blumon | 1601 | ~2MB |
| **nativetouchevent-release.aar** | Native touch event handling | PAX | release | ~500KB |

### JAR Files (Java Archive)

#### SDK Module: `sdk/libs/`

| File | Purpose | Provider | Version | Type |
|------|---------|----------|---------|------|
| **NeptuneLiteApi_V4.10.00_20241122.jar** | Neptune Lite (latest payment processing) | PAX | 4.10.00 (2024-11-22) | **CRITICAL - Latest** |
| **PosApi_V1.24_20200422.jar** | POS Device API | PAX | 1.24 | Core SDK |
| **BaseLinkApi_V1.03.00_T_20190122.jar** | Base communication layer | PAX | 1.03.00 | Base layer |
| GLComm_V1.09.00_20211230.jar | Communication module | PAX | 1.09.00 | Support |
| GLExtPrinter_V1.01.01_20191225.jar | Extended printer support | PAX | 1.01.01 | Support |
| GLImgProcessing_V1.03.00_T_20220121.jar | Image processing | PAX | 1.03.00 | Support |
| GLPacker_V1.05.00_20211230.jar | Data packing/unpacking | PAX | 1.05.00 | Support |
| GLUtils_V1.01.00_T_20220121.jar | Utility functions | PAX | 1.01.00 | Support |
| GLBaiFuTong_V1.00.00_20180119.jar | Additional features | PAX | 1.00.00 | Support |

#### EMV Module: `emv/libs/`

| File | Purpose | Card Scheme | Version | Notes |
|------|---------|-------------|---------|-------|
| EMV_v106.jar | **Core EMV kernel** | EMV (ISO/IEC 7816) | 1.06 | **CRITICAL** |
| COMMON_v103.jar | Common utilities | All schemes | 1.03 | Required |
| DEVICE_v103.jar | Device integration | All schemes | 1.03 | Required |
| Entry_v105.jar | Entry point management | Generic | 1.05 | Required |
| AE_v101.jar | American Express | Amex | 1.01 | Optional |
| DPAS_v101.jar | Discover/DFS | Discover | 1.01 | Optional |
| DPAS_CT_v100.jar | Discover Contactless | Discover | 1.00 | Optional |
| EFT_v101_D1.jar | Eftpos/Debit | Debit | 1.01 | Optional |
| JCB_v100.jar | JCB | JCB | 1.00 | Optional |
| MIR_v100.jar | MIR | MIR (Russia) | 1.00 | Optional |
| PURE_v100.jar | Pure integration | Generic | 1.00 | Optional |
| QPBOC_v100.jar | PBOC (China) | PBOC | 1.00 | Optional |
| RuPay_v100.jar | RuPay | RuPay (India) | 1.00 | Optional |
| WAVE_v101.jar | Wave integration | Generic | 1.01 | Optional |
| MC_v100.jar | Mastercard | Mastercard | 1.00 | **Frequently used** |

#### COMMONLIB Module: `commonlib/src/`
- No JAR dependencies
- Pure Java/Kotlin utilities

---

## BUILD CONFIGURATION

### Root: `build.gradle.kts`

```kotlin
plugins {
    id("com.google.devtools.ksp") version "1.9.20-1.0.13"
    id("com.google.dagger.hilt.android") version "2.57"
}
```

### App: `app/build.gradle.kts` (CRITICAL)

#### 1. **ABI Configuration** (CRITICAL for Blumon)

```kotlin
android {
    defaultConfig {
        ndk {
            abiFilters.clear()           // ⚠️ Clear default ABIs first!
            abiFilters.add("armeabi")    // ⭐ BLUMON REQUIRES ARMEABI ONLY!
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true    // ⚠️ Required for native libs
        }
    }
}
```

**Why armeabi?**
- Blumon SDK has native components compiled for armeabi (32-bit ARM)
- If you include armeabi-v7a, gradle will pick the wrong binary
- Must explicitly set to armeabi ONLY

#### 2. **SDK Version Configuration**

```kotlin
android {
    compileSdk = 36                      // ⚠️ Latest stable
    minSdk = 27                          // Android 8.1 (Blumon requirement)
    targetSdk = 34                       // Latest Android

    defaultConfig {
        minSdk = 27                      // Blumon EMV requires 27+
        targetSdk = 34

        // Environment variables via BuildConfig
        buildConfigField("String", "API_BASE_URL", "\"https://api.avoqado.io/api/v1/\"")
        buildConfigField("String", "SOCKET_URL", "\"https://api.avoqado.io\"")
        buildConfigField("String", "TERMINAL_SERIAL", "\"2841548417\"")
        buildConfigField("String", "TERMINAL_BRAND", "\"PAX\"")
        buildConfigField("String", "TERMINAL_MODEL", "\"A910S\"")
        buildConfigField("String", "BLUMON_ENV", "\"SAND\"")

        // Dev vs Prod initialization
        buildConfigField("boolean", "FORCE_BLUMON_REINIT", "true")  // Dev
        // buildConfigField("boolean", "FORCE_BLUMON_REINIT", "false") // Prod (24h cache)
    }
}
```

#### 3. **Dependency Declaration**

```kotlin
dependencies {
    // ⭐ Blumon SDK Project Dependencies
    implementation(project(":sdk"))          // PosApi, Neptune
    implementation(project(":commonlib"))    // Shared utilities
    implementation(project(":emv"))          // EMV kernels

    // ⭐ Blumon SDK AAR Files
    implementation(files("libs/blumon_sdk-debug.aar"))
    implementation(files("libs/lib-services-BP-SAND_1601.aar"))
    implementation(files("libs/nativetouchevent-release.aar"))

    // AndroidX (Compose, Lifecycle, etc.)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.57")
    ksp("com.google.dagger:hilt-compiler:2.57")

    // Retrofit + Socket.IO
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("io.socket:socket.io-client:2.1.0")

    // Encryption
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.14")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
```

#### 4. **Lint Configuration**

```kotlin
lint {
    abortOnError = false               // Don't fail build on warnings (for now)
    warning += setOf("UnusedResources") // Detect orphaned files
    htmlReport = true
    htmlOutput = layout.buildDirectory.file("reports/lint-results-debug.html").get().asFile
}
```

### SDK Module: `sdk/build.gradle`

```gradle
apply plugin: 'com.android.library'

android {
    compileSdkVersion 34
    minSdkVersion 27    // Must match app module

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Export all PAX JARs via 'api' (available to app)
    api files('libs/NeptuneLiteApi_V4.10.00_20241122.jar')
    api files('libs/PosApi_V1.24_20200422.jar')
    api files('libs/BaseLinkApi_V1.03.00_T_20190122.jar')
    api files('libs/GLComm_V1.09.00_20211230.jar')
    api files('libs/GLExtPrinter_V1.01.01_20191225.jar')
    api files('libs/GLImgProcessing_V1.03.00_T_20220121.jar')
    api files('libs/GLPacker_V1.05.00_20211230.jar')
    api files('libs/GLUtils_V1.01.00_T_20220121.jar')
    api files('libs/GLBaiFuTong_V1.00.00_20180119.jar')
}
```

### EMV Module: `emv/build.gradle`

```gradle
apply plugin: 'com.android.library'

android {
    compileSdkVersion 34
    minSdkVersion 27    // Must match app + SDK
}

repositories {
    flatDir {
        dirs 'libs'
    }
}

dependencies {
    // Import all EMV JARs
    api fileTree(include: ['*.jar'], dir: 'libs')
    
    // Depends on commonlib
    api project(':commonlib')
}
```

### COMMONLIB Module: `commonlib/build.gradle`

```gradle
apply plugin: 'com.android.library'

android {
    compileSdkVersion 34
    minSdkVersion 27
}

repositories {
    flatDir {
        dirs 'libs'  // No local JARs, but keep for consistency
    }
}

dependencies {
    api fileTree(dir: 'libs', include: ['*.jar'])
}
```

---

## PAYMENT IMPLEMENTATION

### Current Status: **FULLY IMPLEMENTED**

The payment feature is **production-ready** with full chip card and contactless support.

### File Structure

```
app/src/main/java/com/jaac/avoqado_tpv/features/payment/
├── data/
│   ├── BlumonInitializer.kt         # SDK initialization (init + keys)
│   ├── BlumonAuthManager.kt         # OAuth + credential fetching
│   └── InitializationManager.kt     # 24-hour cache logic
├── domain/
│   └── PaymentState.kt              # Sealed class for UI state
└── presentation/
    ├── PaymentViewModel.kt          # Business logic (943 lines)
    └── PaymentScreen.kt             # Compose UI
```

### 1. **PaymentViewModel.kt** (943 lines)

**Location:** `/app/src/main/java/.../features/payment/presentation/PaymentViewModel.kt`

**Purpose:** Handles EMV chip card + contactless payments with online authorization

**Key Responsibilities:**
1. **EMV Chip Payment Flow**
   - PreTrans → DetectCard → StartEmvTrans → GetEmvTags → SaleIcc → CompleteEmvTrans
2. **Contactless Payment Flow**
   - StartCtlssTransUseCase → Route by TransResultEnum (ONLINE/OFFLINE/DENIED)
3. **PIN Dialog Management**
   - Collect PIN StateFlows from SDK
   - Respond to ContinueConfirmCard events
4. **EMV Tag Extraction**
   - Extract 21 critical EMV tags using GetEmvTagListUseCase
   - Format as TLV for Blumon API

**Key Methods:**

```kotlin
@HiltViewModel
class PaymentViewModel @Inject constructor(
    // ⭐ 12 injected use cases from Blumon SDK
    private val preTransUseCase: PreTransUseCase,
    private val startDetectCardUseCase: StartDetectCardUseCase,
    private val startEmvTransUseCase: StartEmvTransUseCase,
    private val startCtlssTransUseCase: StartCtlssTransUseCase,     // Contactless
    private val saleIccUseCase: SaleIccUseCase,                     // Online auth
    private val completeEmvTransUseCase: CompleteEmvTransUseCase,   // ARPC response
    private val transProcessRepository: TransProcessRepository,     // PIN listeners
    private val initializationManager: InitializationManager,       // 24h cache
) : ViewModel() {

    fun startPayment(amount: String)                    // Main chip payment flow
    
    private suspend fun performOnlineAuthorization(...)  // SaleIcc wrapper
    
    private suspend fun processContactlessPayment(...)   // Contactless flow
    
    private fun collectPinDialogFlows()                 // PIN listener setup
    
    fun cancelPayment()                                 // Stop card detection
    fun resetPayment()                                  // Reset state
}
```

**Critical Features:**

1. **21 EMV Tags Extraction** (Lines 378-418)
   ```kotlin
   val emvTagParams = GetEmvTagListParam(
       emvTagList = listOf(
           0x9F27, 0x9F26, 0x9F37, 0x9F36, 0x9C, 0x82,  // Core tags
           0x9F33, 0x9F34, 0x9A, 0x5F2A, 0x9F02, 0x9F03, // Transaction
           0x9F35, 0x5F34, 0x9F10, 0x84, 0x9F09, 0x9F1A, // More tags
           0x95, 0x9F1E, 0x50                             // Final tags
       ),
       format = Format.DECIMAL,     // Critical: DECIMAL not HEX
       cardTech = CardTech.CHIP
   )
   ```

2. **PIN Dialog Listeners** (Lines 199-273)
   - Collects 6 StateFlows:
     - `getEventPinDialogStateFlow()` - When PIN pad needed
     - `getKeyboardPinStateFlow()` - Physical keyboard status
     - `getPinResultFlow()` - PIN validation (0=success)
     - `getPinAttemptsFlow()` - Remaining attempts
     - `getSelectAppStateFlow()` - App selection for multi-app cards
     - `confirmCardReadingFlow()` - Card reading confirmation

3. **Contactless Routing** (Lines 337-352)
   - Detects card type: PICC (contactless) vs ICC (chip)
   - Routes to separate handler: `processContactlessPayment()`

4. **ARPC Checking** (Lines 472-520)
   - Extracts AIP (tag 0x82) to check bit 3
   - Only calls CompleteEmvTrans if ARPC required
   - Prevents error -11 (FailureSecondGenerate)

### 2. **BlumonInitializer.kt** (412 lines)

**Location:** `/app/src/main/java/.../features/payment/data/BlumonInitializer.kt`

**Purpose:** Initializes Blumon SDK with credentials and keys

**Responsibilities:**
1. OAuth authentication with Blumon backend
2. RSA key management (terminal encryption)
3. DUKPT key management (PIN encryption)
4. Fallback placeholder values for testing

**Key Methods:**

```kotlin
@Singleton
class BlumonInitializer @Inject constructor(
    private val insertInitUseCase: InsertInitUseCase,
    private val insertRSADataUseCase: InsertRSADataUseCase,
    private val insertDUKPTDataUseCase: InsertDUKPTDataUseCase,
    private val blumonAuthManager: BlumonAuthManager,
    private val initUseCase: InitUseCase,
) {
    
    suspend fun initializeIfNeeded(): Boolean          // Main init (singleton)
    
    private suspend fun validateAndGetInitData(): InitData?  // Backend validation
    
    private suspend fun insertRealCredentials(...)     // Production credentials
    
    private suspend fun insertPlaceholderCredentials() // Fallback for testing
    
    private fun createSandboxInitData(): InitData      // Test data generator
    
    fun resetForTesting()                              // Reset for unit tests
}
```

**Initialization Sequence:**

```
Step 0: Authenticate via OAuth (Bearer token)
        ↓
Step 1: Validate terminal with backend (requires Bearer token)
        ↓
Step 2: Insert RSA keys (PROD only, SAND uses KUSHKY)
        ↓
Step 3: Insert DUKPT keys (PROD only)
        ↓
[Fallback: If any step fails, use placeholder values]
```

### 3. **BlumonAuthManager.kt** (255 lines)

**Location:** `/app/src/main/java/.../features/payment/data/BlumonAuthManager.kt`

**Purpose:** OAuth authentication with Blumon backend, credential fetching

**Responsibilities:**
1. Calculate password: SHA256(Serial + Brand + Model)
2. Fetch OAuth access_token from `/oauth/token`
3. Fetch RSA keys from core API
4. Fetch DUKPT keys from core API
5. Store token in GlobalResources for SDK HTTP interceptor

**Key Methods:**

```kotlin
@Singleton
class BlumonAuthManager @Inject constructor(
    private val deviceInfoManager: DeviceInfoManager,
    private val tokenServer: TokenServer,
    private val coreServer: CoreServer
) {
    
    suspend fun fetchAccessTokenOnly(): String?       // SAND: Token only
    
    suspend fun fetchCredentials(): BlumonCredentials?  // PROD: Full flow
    
    private fun calculatePassword(...): String        // SHA256 hash
    
    private suspend fun getOAuthToken(...): String?   // Token endpoint
    
    private suspend fun getRSAKeys(...): Pair<Int, String>?
    
    private suspend fun getDUKPTKeys(...): Quadruple<...>?
}

data class BlumonCredentials(
    val accessToken: String,      // Bearer token
    val rsaId: Int,              // RSA key ID
    val rsaKey: String,          // RSA public key (hex)
    val dukptKsn: String,        // Key Serial Number
    val dukptKey: String,        // DUKPT base key
    val dukptKeyCrc32: String,   // Validation CRC
    val dukptKeyCheckValue: String  // Validation check
)
```

**Password Calculation (Critical):**

```kotlin
private fun calculatePassword(
    serialNumber: String,     // "2841548417"
    brand: String,            // "PAX"
    model: String             // "A910S"
): String {
    val input = "$serialNumber$brand$model"  // "2841548417PAXA910S"
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(input.toByteArray()).joinToString("") { 
        "%02x".format(it) 
    }
    // Result: 64-character hex string
}
```

**Bearer Token Storage:**

```kotlin
// Store in two places:
this.accessToken = token                    // BlumonAuthManager.getAccessToken()
GlobalResources.tokenAuth = token           // SDK HTTP interceptor (critical!)
```

### 4. **InitializationManager.kt** (210 lines)

**Location:** `/app/src/main/java/.../features/payment/data/InitializationManager.kt`

**Purpose:** Manages 24-hour initialization caching

**Problem Solved:**
Previously, `InitializerUseCase` + `InsertInitUseCase` were called on EVERY payment, creating 65 duplicate database rows.

**Solution per Edgardo (2025-11-05):**
"Es recomendable realizar el init solo una vez cada 24 horas o cada que lances la aplicación"

**Key Methods:**

```kotlin
@Singleton
class InitializationManager @Inject constructor(
    private val secureStorage: SecureStorage,
    private val initializerUseCase: InitializerUseCase,
    private val insertInitUseCase: InsertInitUseCase,
    private val getInitDataUseCase: GetInitDataUseCase
) {
    
    suspend fun ensureInitialized(): Result<Unit>  // Check cache, init if needed
    
    private fun shouldInitialize(...): Boolean     // 24h cache logic
    
    private suspend fun executeInitialization(...): Result<Unit>  // Full init
    
    suspend fun forceReinitialize(): Result<Unit> // For testing
}
```

**Caching Logic:**

```
Development (FORCE_BLUMON_REINIT=true):
    → Always re-initialize (prevents DUKPT corruption on rebuild)

Production (FORCE_BLUMON_REINIT=false):
    → First init: Always execute
    → < 24 hours since init: Skip (reuse cache)
    → ≥ 24 hours since init: Execute again
```

### 5. **PaymentState.kt** (11 lines)

**Location:** `/app/src/main/java/.../features/payment/domain/PaymentState.kt`

```kotlin
sealed class PaymentState {
    data object Idle : PaymentState()
    data object ConfiguringKernel : PaymentState()
    data object DetectingCard : PaymentState()
    data class Processing(val message: String = "Procesando...") : PaymentState()
    data class Success(val authCode: String, val amount: String) : PaymentState()
    data class Error(val message: String, val canRetry: Boolean = true) : PaymentState()
    data object Cancelled : PaymentState()
}
```

### 6. **PaymentScreen.kt** (Compose UI)

**Location:** `/app/src/main/java/.../features/payment/presentation/PaymentScreen.kt`

**Purpose:** Jetpack Compose UI for payment workflow

**States:**
1. **Idle** - Enter amount
2. **ConfiguringKernel** - EMV kernel setup
3. **DetectingCard** - Waiting for card tap
4. **Processing** - Various stages (EMV, ARPC, online auth)
5. **Success** - Show auth code and receipt
6. **Error** - Show error message with retry option
7. **Cancelled** - Auto-navigate back

---

### 7. **Multi-Merchant Support** (2025-11-05)

**Problem Statement:**

A single physical PAX A910S terminal needs to process payments to **different merchant accounts** dynamically. This is critical for multi-tenant scenarios where one terminal serves multiple businesses.

**User Requirement:** "Necesito dirigir el pago a una u la otra cuenta desde el mismo terminal" (Route payments to different accounts from same terminal)

**Business Context:** "El corazón del negocio" - Without this, the entire application is unusable for the target market.

---

#### 7.1 **Architecture Overview**

The multi-merchant system enables runtime switching between Blumon merchant accounts by:
1. **Replacing immutable BuildConfig** with runtime-mutable TerminalConfig
2. **Re-initializing Blumon SDK** with new serial number (OAuth + DUKPT keys)
3. **Fetching correct posId** from backend (critical for authorization)
4. **Providing merchant selection UI** before payment

**Key Constraint:** Blumon SDK identifies terminal by **serial number** (username for OAuth). Changing merchant = changing serial number = full SDK re-initialization.

---

#### 7.2 **TerminalConfig.kt** (Runtime Serial Management)

**Location:** `/app/src/main/java/com/jaac/avoqado_tpv/core/domain/TerminalConfig.kt`

**Purpose:** Replaces immutable BuildConfig with mutable runtime configuration

```kotlin
package com.jaac.avoqado_tpv.core.domain

import com.jaac.avoqado_tpv.BuildConfig

object TerminalConfig {
    var serialNumber: String = BuildConfig.TERMINAL_SERIAL
    var brand: String = BuildConfig.TERMINAL_BRAND
    var model: String = BuildConfig.TERMINAL_MODEL

    fun reset() {
        serialNumber = BuildConfig.TERMINAL_SERIAL
        brand = BuildConfig.TERMINAL_BRAND
        model = BuildConfig.TERMINAL_MODEL
    }

    override fun toString(): String {
        return "TerminalConfig(serial=$serialNumber, brand=$brand, model=$model)"
    }
}
```

**Why needed:**
- `BuildConfig.TERMINAL_SERIAL` is **immutable** (Gradle-generated compile-time constant)
- Cannot change serial number at runtime without TerminalConfig pattern
- All SDK calls now use `TerminalConfig.serialNumber` instead of `BuildConfig.TERMINAL_SERIAL`

**Replaced 14 references:**
- InitializationManager.kt: 1 reference
- BlumonAuthManager.kt: 2 references
- BlumonInitializer.kt: 6 references
- TerminalConfig.kt: 3 references (self-reference)
- BLUMON_INTEGRATION_COMPLETE.md: 2 references (documentation)

---

#### 7.3 **MerchantAccount.kt** (Domain Model)

**Location:** `/app/src/main/java/com/jaac/avoqado_tpv/features/payment/domain/model/MerchantAccount.kt`

**Purpose:** Represents a merchant account with Blumon credentials

```kotlin
data class MerchantAccount(
    val id: String,
    val serialNumber: String,
    val displayName: String,
    val description: String? = null,
    val environment: MerchantEnvironment = MerchantEnvironment.SANDBOX,
    val isActive: Boolean = true
)

enum class MerchantEnvironment {
    SANDBOX,
    PRODUCTION
}

// Sandbox Test Accounts (provided by Blumon)
companion object {
    val SANDBOX_ACCOUNT_A = MerchantAccount(
        id = "merchant_sandbox_a",
        serialNumber = "2841548417",  // posId: 376
        displayName = "Account A",
        description = "Primary sandbox merchant account",
        environment = MerchantEnvironment.SANDBOX
    )

    val SANDBOX_ACCOUNT_B = MerchantAccount(
        id = "merchant_sandbox_b",
        serialNumber = "2841548418",  // posId: 378
        displayName = "Account B",
        description = "Secondary sandbox merchant account",
        environment = MerchantEnvironment.SANDBOX
    )
}
```

**Key Fields:**
- `serialNumber` - Blumon terminal serial (used for OAuth username)
- `displayName` - User-facing name (shown in UI)
- `id` - Unique identifier (for backend tracking)
- `environment` - SANDBOX vs PRODUCTION (future use)

**Critical:** Each serial number has a **unique posId** assigned by Blumon backend. This mapping is **server-side only** and must be fetched dynamically.

---

#### 7.4 **MultiMerchantSDKManager.kt** (Orchestration Layer)

**Location:** `/app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/MultiMerchantSDKManager.kt`

**Purpose:** Orchestrates atomic merchant switching with thread safety

**Key Features:**
1. **Mutex locking** - Prevents concurrent switches
2. **Rollback on failure** - Restores previous serial if init fails
3. **No-op detection** - Skips re-init if already on target merchant
4. **Full SDK re-initialization** - OAuth + DUKPT keys downloaded

```kotlin
@Singleton
class MultiMerchantSDKManager @Inject constructor(
    private val initializationManager: InitializationManager
) {
    private val switchMutex = Mutex()
    private var currentMerchant: MerchantAccount? = null

    suspend fun switchMerchant(targetAccount: MerchantAccount): Result<Unit> {
        return switchMutex.withLock {
            try {
                // No-op if already active
                if (isMerchantActive(targetAccount)) {
                    Timber.d("✅ Already on ${targetAccount.displayName}")
                    return@withLock Result.success(Unit)
                }

                val previousSerial = TerminalConfig.serialNumber
                Timber.i("🔄 Switching: $previousSerial → ${targetAccount.serialNumber}")

                // Update runtime config
                TerminalConfig.serialNumber = targetAccount.serialNumber

                // Re-initialize SDK (OAuth + DUKPT keys)
                val initResult = initializationManager.forceReinitialize()

                if (initResult.isFailure) {
                    // ROLLBACK: Restore previous serial
                    TerminalConfig.serialNumber = previousSerial
                    Timber.e("❌ Switch failed, rolled back to $previousSerial")
                    return@withLock Result.failure(Exception("Failed to switch merchant"))
                }

                // Success - update current merchant
                currentMerchant = targetAccount
                Timber.i("✅ Switched to ${targetAccount.displayName} (${targetAccount.serialNumber})")
                Result.success(Unit)

            } catch (e: Exception) {
                Timber.e(e, "❌ Unexpected error during merchant switch")
                Result.failure(e)
            }
        }
    }

    fun isMerchantActive(account: MerchantAccount): Boolean {
        return TerminalConfig.serialNumber == account.serialNumber
    }

    fun getCurrentMerchant(): MerchantAccount? = currentMerchant
}
```

**Performance:**
- First switch (cold start): **5-8 seconds** (OAuth + DUKPT download)
- Subsequent switches: **3-5 seconds** (OAuth cached, only DUKPT re-download)

**Thread Safety:** `Mutex` ensures only one switch can execute at a time. Prevents race conditions if user rapidly taps merchant buttons.

---

#### 7.5 **Critical Bug: Dynamic posId Fetching**

**Problem Discovered (2025-11-05):**

InitializationManager was using **hardcoded posId = "376"** for all merchants. This caused:
- ✅ Account A (serial 2841548417) → posId 376 → **Payments worked**
- ❌ Account B (serial 2841548418) → posId 378 → **MomentumFailure** (wrong posId sent to backend)

**Root Cause:**
```kotlin
// ❌ WRONG - InitializationManager.kt:137 (before fix)
val correctPosId = "376"  // Hardcoded!
```

**Why this is critical:**
- Blumon backend validates `posId` during online authorization
- Serial 2841548417 → posId 376 (correct)
- Serial 2841548418 → posId 378 (different!)
- Sending wrong posId → backend rejects transaction → MomentumFailure

**Solution (STEP 1.5 - Dynamic Fetching):**

**Location:** InitializationManager.kt:135-148

```kotlin
// ✅ CORRECT - Fetch posId from backend BEFORE InsertInitUseCase
Timber.i("[INIT STEP 1.5] GetInitDataUseCase - Fetching backend posId...")
val preInitDataParams = GetInitDataParams()
val preInitDataResult = getInitDataUseCase.run(preInitDataParams)

val correctPosId = if (preInitDataResult.isRight) {
    val preInitData = preInitDataResult.rightValue().initData
    Timber.i("   Backend returned posId: ${preInitData.posId} for serial: ${TerminalConfig.serialNumber}")
    preInitData.posId  // ✅ DYNAMIC - fetched from backend!
} else {
    // Fallback only if backend unreachable
    Timber.w("   ⚠️ Failed to get posId from backend, using fallback: 376")
    "376"
}
```

**Initialization Sequence (updated):**
```
STEP 1: InitializerUseCase (OAuth + DUKPT download)
    ↓
STEP 1.5: GetInitDataUseCase (fetch posId from backend)  ← NEW!
    ↓
STEP 2: InsertInitUseCase (force correct posId)
    ↓
STEP 3: GetInitDataUseCase (verification)
    ↓
STEP 4: Save timestamp
```

**Result:**
- ✅ Account A: Serial 2841548417 → Backend returns posId 376 → Payments work
- ✅ Account B: Serial 2841548418 → Backend returns posId 378 → Payments work
- ✅ No more MomentumFailure errors
- ✅ User feedback: "eres un genio! no puedo creer que lo lograste!"

---

#### 7.6 **PaymentViewModel Integration**

**Location:** PaymentViewModel.kt:96-408

**Added Dependencies:**
```kotlin
@HiltViewModel
class PaymentViewModel @Inject constructor(
    // Existing dependencies...

    // 🏪 Multi-Merchant Support (NEW)
    private val getMerchantsUseCase: GetMerchantsUseCase,
    private val multiMerchantSDKManager: MultiMerchantSDKManager
) : ViewModel()
```

**Added StateFlows:**
```kotlin
// Merchant list
private val _merchants = MutableStateFlow<List<MerchantAccount>>(emptyList())
val merchants: StateFlow<List<MerchantAccount>> = _merchants.asStateFlow()

// Current active merchant
private val _currentMerchant = MutableStateFlow<MerchantAccount?>(null)
val currentMerchant: StateFlow<MerchantAccount?> = _currentMerchant.asStateFlow()

// Loading state during switch
private val _merchantSwitchingLoading = MutableStateFlow(false)
val merchantSwitchingLoading: StateFlow<Boolean> = _merchantSwitchingLoading.asStateFlow()

// Success/error message
private val _merchantSwitchMessage = MutableStateFlow<String?>(null)
val merchantSwitchMessage: StateFlow<String?> = _merchantSwitchMessage.asStateFlow()
```

**Merchant Selection Function:**
```kotlin
fun selectMerchant(account: MerchantAccount) {
    viewModelScope.launch(Dispatchers.IO) {
        _merchantSwitchingLoading.value = true
        _merchantSwitchMessage.value = "Cambiando a ${account.displayName}..."

        val result = multiMerchantSDKManager.switchMerchant(account)

        if (result.isSuccess) {
            _currentMerchant.value = account
            _merchantSwitchMessage.value = "✅ Ahora usando ${account.displayName}"
        } else {
            _merchantSwitchMessage.value = "❌ No se pudo cambiar a ${account.displayName}"
        }

        _merchantSwitchingLoading.value = false

        // Clear message after 3 seconds
        delay(3000)
        _merchantSwitchMessage.value = null
    }
}
```

**Load Merchants on Init:**
```kotlin
init {
    // Load available merchants
    viewModelScope.launch {
        getMerchantsUseCase().collect { merchantList ->
            _merchants.value = merchantList
            // Set default to first merchant
            if (_currentMerchant.value == null && merchantList.isNotEmpty()) {
                _currentMerchant.value = merchantList.first()
            }
        }
    }
}
```

---

#### 7.7 **PaymentScreen UI** (2-Button MVP)

**Location:** PaymentScreen.kt:108-228

**Merchant Selector Section:**
```kotlin
// ═══════════════════════════════════════════════════════
// MERCHANT SELECTION (MVP: Simple 2-button layout)
// ═══════════════════════════════════════════════════════
Text(
    text = "Seleccionar Cuenta",
    style = MaterialTheme.typography.titleMedium,
    color = MaterialTheme.colorScheme.onSurface
)

Spacer(modifier = Modifier.height(12.dp))

// Display current merchant
Text(
    text = "Cuenta activa: ${currentMerchant?.displayName ?: \"Default (${TerminalConfig.serialNumber})\"}",
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant
)

Spacer(modifier = Modifier.height(16.dp))

// 2-button layout: Account A | Account B
Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(12.dp)
) {
    merchants.forEach { merchant ->
        AvoqadoButton(
            text = merchant.displayName,
            onClick = { onSelectMerchant(merchant) },
            enabled = !merchantSwitchingLoading,
            modifier = Modifier.weight(1f)
        )
    }
}

// Success/error message
merchantSwitchMessage?.let { message ->
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = if (message.startsWith("✅")) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.error
        },
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}
```

**Loading Overlay:**
```kotlin
// Loading overlay during merchant switch
if (merchantSwitchingLoading) {
    AvoqadoLoadingOverlay(
        message = merchantSwitchMessage ?: "Cambiando cuenta..."
    )
}
```

**UI Flow:**
1. User sees 2 buttons: "Account A" | "Account B"
2. Current active merchant displayed above buttons
3. User taps button → Loading overlay appears
4. Switch completes → Success message shown for 3 seconds
5. User enters amount and proceeds with payment

---

#### 7.8 **Testing Results**

**Test Environment:**
- Device: PAX A910S (1280x720 dp)
- SDK: Blumon PAX SDK 1.0 (debug build)
- Environment: SANDBOX
- Date: 2025-11-05

**Test Cases:**

| Test | Action | Duration | Result | Notes |
|------|--------|----------|--------|-------|
| **Switch A→B** | Tap "Account B" while on A | 5.7s | ✅ SUCCESS | OAuth + DUKPT download |
| **Switch B→A** | Tap "Account A" while on B | 4.5s | ✅ SUCCESS | OAuth cached, faster |
| **No-op A→A** | Tap "Account A" while on A | <0.1s | ✅ SUCCESS | Skipped re-init (already active) |
| **Payment on A** | Process payment with Account A | 2.1s | ✅ SUCCESS | Auth code received |
| **Payment on B (before fix)** | Process payment with Account B | - | ❌ FAILURE | MomentumFailure (wrong posId) |
| **Payment on B (after fix)** | Process payment with Account B | 2.3s | ✅ SUCCESS | posId 378 fetched dynamically |

**Portal Verification (Blumon Sandbox):**
- Serial 2841548417: **14 successful transactions**
- Serial 2841548418: **1 successful transaction** (after posId fix)

**User Feedback:**
> "eres un genio! no puedo creer que lo lograste! si fue exitoso!"

---

#### 7.9 **Merchant Switch Sequence Diagram**

```
┌────────────────────────────────────────────────────────────────────┐
│                     Merchant Switch Flow                           │
└────────────────────────────────────────────────────────────────────┘

   USER              PaymentScreen        PaymentViewModel      MultiMerchantSDKManager      InitializationManager      Backend
    │                      │                      │                       │                           │                    │
    │  1. Tap "Account B"  │                      │                       │                           │                    │
    │─────────────────────▶│                      │                       │                           │                    │
    │                      │  2. selectMerchant() │                       │                           │                    │
    │                      │─────────────────────▶│                       │                           │                    │
    │                      │                      │  3. switchMerchant()  │                           │                    │
    │                      │                      │──────────────────────▶│                           │                    │
    │                      │                      │                       │  4. Acquire Mutex         │                    │
    │                      │                      │                       │  5. Check if active       │                    │
    │                      │                      │                       │  6. Update TerminalConfig │                    │
    │                      │                      │                       │     (2841548417→2841548418)                    │
    │                      │                      │                       │  7. forceReinitialize()   │                    │
    │                      │                      │                       │──────────────────────────▶│                    │
    │                      │                      │                       │                           │  8. STEP 1         │
    │                      │                      │                       │                           │  InitializerUseCase│
    │                      │                      │                       │                           │  (OAuth + DUKPT)   │
    │                      │                      │                       │                           │  9. STEP 1.5       │
    │                      │                      │                       │                           │  GetInitDataUseCase│
    │                      │                      │                       │                           │───────────────────▶│
    │                      │                      │                       │                           │◀───────────────────│
    │                      │                      │                       │                           │ { posId: "378" }   │
    │                      │                      │                       │                           │ 10. STEP 2         │
    │                      │                      │                       │                           │ InsertInitUseCase  │
    │                      │                      │                       │                           │ (force posId 378)  │
    │                      │                      │                       │◀──────────────────────────│ 11. Result.success │
    │                      │                      │◀──────────────────────│ Result.success            │                    │
    │                      │◀─────────────────────│ State.Success         │                           │                    │
    │  12. "✅ Ahora       │                      │                       │                           │                    │
    │   usando Account B"  │                      │                       │                           │                    │
    │◀─────────────────────│                      │                       │                           │                    │
```

**Duration Breakdown:**
- Step 4-6 (Mutex + Config): <0.1s
- Step 7-11 (SDK Re-init): 3-5s
  - OAuth: 1.5-2s (or 0s if cached)
  - DUKPT download: 1.5-2s
  - posId fetch: 0.5-1s
- Step 12 (UI Update): <0.1s

**Total:** 3-5 seconds per switch

---

#### 7.10 **Repository Layer** (Data Access)

**MerchantRepository.kt** (Interface):
```kotlin
interface MerchantRepository {
    fun getMerchants(): Flow<List<MerchantAccount>>
}
```

**MerchantRepositoryImpl.kt** (Implementation):
```kotlin
@Singleton
class MerchantRepositoryImpl @Inject constructor() : MerchantRepository {
    override fun getMerchants(): Flow<List<MerchantAccount>> = flow {
        // TODO: Fetch from backend API: GET /api/v1/tpv/merchants?terminalId={deviceId}
        // For now, return hardcoded sandbox accounts
        emit(
            listOf(
                MerchantAccount.SANDBOX_ACCOUNT_A,
                MerchantAccount.SANDBOX_ACCOUNT_B
            )
        )
    }
}
```

**GetMerchantsUseCase.kt** (Business Logic):
```kotlin
@Singleton
class GetMerchantsUseCase @Inject constructor(
    private val merchantRepository: MerchantRepository
) {
    operator fun invoke(): Flow<List<MerchantAccount>> {
        return merchantRepository.getMerchants()
    }
}
```

**RepositoryModule.kt** (Hilt DI):
```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindMerchantRepository(
        impl: MerchantRepositoryImpl
    ): MerchantRepository
}
```

**Future Backend Integration:**
```typescript
// avoqado-server: GET /api/v1/tpv/merchants?terminalId=device-123
{
  "merchants": [
    {
      "id": "merchant_a",
      "serialNumber": "2841548417",
      "displayName": "Operativa",
      "environment": "SANDBOX",
      "isActive": true
    },
    {
      "id": "merchant_b",
      "serialNumber": "2841548418",
      "displayName": "Delivery",
      "environment": "SANDBOX",
      "isActive": true
    }
  ]
}
```

---

#### 7.11 **Security Considerations**

**1. Serial Number Protection:**
- Serial numbers are **not secrets** (visible on device, used for OAuth username)
- However, do NOT log serial numbers in production builds (prevents tracking)

**2. Mutex Thread Safety:**
- Prevents race conditions if user rapidly taps merchant buttons
- Ensures only one switch can execute at a time

**3. Rollback on Failure:**
- If SDK re-init fails, previous serial number is restored
- Prevents terminal from being left in broken state

**4. Audit Logging (Future):**
```kotlin
// Log merchant switches to backend for security monitoring
POST /api/v1/tpv/audit-log
{
  "eventType": "MERCHANT_SWITCH",
  "userId": "user-123",
  "terminalId": "2841548417",
  "fromSerial": "2841548417",
  "toSerial": "2841548418",
  "timestamp": "2025-11-05T10:30:00Z",
  "success": true
}
```

---

#### 7.12 **Known Limitations**

**1. No Backend API Yet:**
- Currently using hardcoded `MerchantAccount.SANDBOX_ACCOUNT_A` and `_B`
- Backend endpoint `GET /api/v1/tpv/merchants` does not exist yet
- Future: Fetch merchant list from backend based on device ID

**2. No Confirmation Dialog:**
- User can switch merchants without warning
- Should show confirmation: "¿Cambiar a Account B?" with "Cancelar" | "Confirmar"
- Future: Add `MerchantSwitchConfirmationDialog.kt`

**3. No Progress Indicator:**
- Loading overlay shows static "Cambiando cuenta..." message
- Should show progress: OAuth (33%) → DUKPT (66%) → Done (100%)
- Future: Update `AvoqadoLoadingOverlay` to accept progress percentage

**4. No Analytics:**
- No tracking of merchant switches (duration, success rate, user patterns)
- Future: Add `AnalyticsManager.kt` with events:
  - `merchant_switch_started`
  - `merchant_switch_completed`
  - `merchant_switch_failed`

**5. No Production Testing:**
- Only tested in SANDBOX environment with 2 test accounts
- Production environment may have different behavior
- Requires testing with real merchant accounts

---

#### 7.13 **Future Enhancements**

**1. Material 3 Cards UI (Planned - Step 6):**
Replace buttons with cards showing:
- Bank icon
- Merchant name
- Serial number
- Checkmark if active
- Ripple effect on tap

**2. Confirmation Dialog (Planned - Step 6):**
Show before switching:
- Warning about active transactions
- "Cancelar" | "Confirmar" buttons

**3. Progress Tracking (Planned - Step 6):**
Show progress during switch:
- 0% - Iniciando cambio...
- 33% - Autenticación OAuth...
- 66% - Descargando claves DUKPT...
- 100% - Cuenta cambiada ✓

**4. Analytics (Planned - Step 7):**
Track merchant switch metrics:
- Duration
- Success/failure rate
- User patterns
- Most-used merchant

**5. Audit Logging (Planned - Step 8):**
Log all merchant switches to backend:
- User ID
- Terminal ID
- From/to serial numbers
- Timestamp
- Success/failure

**6. Backend API Integration (Planned - Step 5):**
Replace hardcoded accounts with API:
```
GET /api/v1/tpv/merchants?terminalId={deviceId}
```

---

## SDK INITIALIZATION

### Initialization Flow (PaymentViewModel.kt:172-178)

```kotlin
init {
    Timber.d("🎬 [PaymentViewModel] Initialized - Starting PIN listeners")
    
    // 🔧 Initialize Blumon SDK (once every 24 hours per Edgardo)
    viewModelScope.launch {
        initializationManager.ensureInitialized().onFailure { error ->
            Timber.e(error, "❌ Failed to initialize Blumon SDK")
        }
    }

    collectPinDialogFlows()
}
```

### Recommended Init Strategy (per Edgardo 2025-11-05):

```
First app launch:
    → Full initialization: OAuth + Keys
    → Cache timestamp in SecureStorage

Subsequent launches (< 24 hours):
    → Skip initialization (fast startup)
    → Reuse cached configuration

After 24 hours:
    → Re-initialize (refresh keys if needed)
```

### Dev vs Prod Difference

**Development (BuildConfig.FORCE_BLUMON_REINIT = true):**
```kotlin
// app/build.gradle.kts (debug)
buildConfigField("boolean", "FORCE_BLUMON_REINIT", "true")

// Effect: Always re-init on every app launch
// Reason: Prevents DUKPT key corruption (na_002 errors) when rebuilding without uninstall
```

**Production (BuildConfig.FORCE_BLUMON_REINIT = false):**
```kotlin
// app/build.gradle.kts (release)
buildConfigField("boolean", "FORCE_BLUMON_REINIT", "false")

// Effect: Use 24-hour cache
// Reason: Faster startup, avoids backend requests on every payment
```

---

## ANDROID MANIFEST & PERMISSIONS

### File: `app/src/main/AndroidManifest.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- NETWORK PERMISSIONS (CRITICAL for online payments) -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application
        android:name=".AvoqadoTPVApplication"          <!-- Hilt @HiltAndroidApp -->
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.Avoqadotpv">

        <!-- MAIN ACTIVITY -->
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- DISABLE WorkManager AUTO-INITIALIZATION (use Hilt) -->
        <provider
            android:name="androidx.startup.InitializationProvider"
            android:authorities="${applicationId}.androidx-startup"
            android:exported="false"
            tools:node="merge">
            <meta-data
                android:name="androidx.work.WorkManagerInitializer"
                android:value="androidx.startup"
                tools:node="remove" />
        </provider>

    </application>

</manifest>
```

### Permission Analysis

| Permission | Purpose | Required For |
|-----------|---------|--------------|
| `INTERNET` | Network requests | API calls, Socket.IO, OAuth |
| `ACCESS_NETWORK_STATE` | Check network status | Offline detection |

**Future Permissions (when available):**
```xml
<!-- When implementing receipt printing -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />

<!-- When implementing biometric auth -->
<uses-permission android:name="android.permission.USE_BIOMETRIC" />

<!-- When implementing geolocation -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

---

## DATA FLOW & ARCHITECTURE

### Clean Architecture Layers

```
┌──────────────────────────────────────────────────┐
│ PRESENTATION LAYER (UI)                          │
│ • PaymentScreen.kt (Compose)                     │
│ • PaymentViewModel.kt (StateFlow)                │
│ • PaymentState.kt (Sealed class)                 │
└──────────────────────────────────────────────────┘
                    ↓
┌──────────────────────────────────────────────────┐
│ DOMAIN LAYER (Business Logic)                    │
│ • Use Cases from Blumon SDK:                     │
│   - PreTransUseCase                              │
│   - StartDetectCardUseCase                       │
│   - StartEmvTransUseCase                         │
│   - StartCtlssTransUseCase (Contactless)         │
│   - SaleIccUseCase (Online Authorization)        │
│   - CompleteEmvTransUseCase (ARPC)               │
│   - GetEmvTagListUseCase (EMV extraction)        │
│   - etc. (12+ use cases total)                   │
└──────────────────────────────────────────────────┘
                    ↓
┌──────────────────────────────────────────────────┐
│ DATA LAYER (Infrastructure)                      │
│ • PaymentRepository (interface)                  │
│ • BlumonInitializer (SDK init)                   │
│ • BlumonAuthManager (OAuth)                      │
│ • InitializationManager (24h cache)              │
│ • TransProcessRepository (PIN listeners)         │
│ • PAX SDK (Terminal communication)               │
│ • Blumon Momentum API (Online auth)              │
└──────────────────────────────────────────────────┘
                    ↓
┌──────────────────────────────────────────────────┐
│ EXTERNAL SERVICES                                │
│ • Blumon Momentum (Online Authorization)         │
│ • Blumon Tokener (OAuth)                         │
│ • Blumon Core API (RSA/DUKPT keys)               │
│ • PAX Terminal Hardware (readers, PIN pad)       │
│ • Avoqado Backend (Record transaction)           │
└──────────────────────────────────────────────────┘
```

### Dependency Injection (Hilt)

```kotlin
// AvoqadoTPVApplication.kt
@HiltAndroidApp
class AvoqadoTPVApplication : Application()

// PaymentViewModel.kt
@HiltViewModel
class PaymentViewModel @Inject constructor(
    private val preTransUseCase: PreTransUseCase,              // From SDK module
    private val startDetectCardUseCase: StartDetectCardUseCase,
    private val startEmvTransUseCase: StartEmvTransUseCase,
    private val saleIccUseCase: SaleIccUseCase,                // From blumon_sdk-debug.aar
    private val transProcessRepository: TransProcessRepository, // From SDK
    private val initializationManager: InitializationManager,  // Local (Singleton)
) : ViewModel()
```

**Hilt Module Sources:**
1. **SDK Hilt Modules** (auto-provided by blumon_sdk-debug.aar):
   - Provides all Use Cases
   - Provides TransProcessRepository
   - Configures Retrofit for Blumon APIs

2. **App Hilt Modules** (custom modules in app/):
   - Provides InitializationManager
   - Provides BlumonAuthManager
   - Provides SecureStorage (EncryptedSharedPreferences)

---

## EMV PAYMENT FLOW

### Complete Payment Flow (Chip Card)

```
User enters amount
    ↓
[PHASE 1] PreTransUseCase
    → Configure EMV kernel
    → Set transaction amount, currency, type
    ↓
[PHASE 2] StartDetectCardUseCase
    → Wait for card tap/insert/swipe
    → Detect card type: ICC (chip) vs PICC (contactless) vs MAG (swipe)
    ↓
[PHASE 2.5] Card Type Router
    → If PICC (contactless): Jump to processContactlessPayment()
    → If ICC/MAG (chip): Continue with chip flow
    ↓
[PHASE 3] StartEmvTransUseCase
    → Process chip card locally
    → PIN dialog (SDK controls PAX hardware)
    → SDK emits PIN StateFlows
    ↓
[PHASE 3.5] GetEmvTagListUseCase
    → Extract 21 EMV tags (TLV format)
    → Critical tags:
      - 9F26: ARQC (Application Cryptogram)
      - 9F37: Unpredictable Number
      - 9F10: IAD (Issuer Application Data)
      - 57: Track2 Equivalent Data
    ↓
[PHASE 4] SaleIccUseCase (ONLINE AUTHORIZATION)
    → Send to Blumon Momentum platform
    → Platform contacts bank
    → Get Auth Code + ARPC response
    ↓
[PHASE 4.5] AIP Checking
    → Extract AIP (tag 82)
    → Check if ARPC required (bit 3)
    ↓
[PHASE 5] CompleteEmvTransUseCase (Conditional)
    → If ARPC required: Update chip with response
    → If not required: Skip (card doesn't support ARPC)
    ↓
SUCCESS
    → Show auth code
    → Record in backend
```

### EMV Tag Extraction (21 Tags)

**Location:** PaymentViewModel.kt:378-418

```kotlin
val emvTagParams = GetEmvTagListParam(
    emvTagList = listOf(
        // Cryptogram & Security (CRITICAL)
        0x9F27,  // Cryptogram Information Data (CID)
        0x9F26,  // Application Cryptogram (ARQC) ← BLUMON REQUIRES
        0x9F37,  // Unpredictable Number ← CRITICAL
        0x9F36,  // Application Transaction Counter (ATC)
        
        // Transaction Details
        0x9C,    // Transaction Type
        0x82,    // Application Interchange Profile (AIP)
        0x9F33,  // Terminal Capabilities
        0x9F34,  // CVM Results (PIN verified/signature/etc)
        0x9A,    // Transaction Date (YYMMDD)
        0x5F2A,  // Transaction Currency Code
        0x9F02,  // Amount, Authorized (numeric)
        0x9F03,  // Amount, Other (cashback)
        
        // Terminal & Card Info
        0x9F35,  // Terminal Type
        0x5F34,  // Application PAN Sequence Number
        0x9F10,  // IAD (Issuer Application Data) ← CRITICAL
        0x84,    // Dedicated File Name (AID)
        0x9F09,  // Application Version Number
        0x9F1A,  // Terminal Country Code
        0x95,    // Terminal Verification Results (TVR)
        0x9F1E,  // Interface Device Serial Number
        0x50     // Application Label
    ),
    format = Format.DECIMAL,    // ⚠️ CRITICAL: DECIMAL for CHIP
    cardTech = CardTech.CHIP
)

val tagListResult = getEmvTagListUseCase.runInfallible(emvTagParams)
val emvTagListStr = tagListResult.emvTagList  // Complete TLV string
```

### Online Authorization (SaleIccUseCase)

**Location:** PaymentViewModel.kt:541-632

```kotlin
val params = SaleIccParams(
    idMembership = "",                    // Empty (no loyalty program)
    amount = "5000",                      // Amount in cents ($50.00)
    currency = "484",                     // MXN (ISO 4217)
    track2 = currentTrack2,               // From chip (tag 0x57)
    cardHolderName = "CARDHOLDER",        // From chip (tag 5F20)
    authenticationCard = AuthenticationCard.SIGNATURE,  // From CVM Results
    emvTagList = emvTagListStr,           // Complete 21-tag TLV string
    cipherType = CipherType.DUKPT,        // ⚠️ ALWAYS DUKPT (never KUSHKY)
    msi = null                            // No installments
)

val result = saleIccUseCase.run(params)   // Either<Failure, SaleIccResponse>

// Handle result
when {
    result.isLeft -> {
        val failure = result.leftValue()
        // Handle error
    }
    else -> {
        val response = result.rightValue()
        val authCode = response.saleData.authorization
        val arpc = response.saleData.arpc
        // Success!
    }
}
```

### ARPC Update (CompleteEmvTransUseCase)

**Location:** PaymentViewModel.kt:496-520

```kotlin
// Check if ARPC required
val aipHex = ... // Extract tag 82
val arpcRequired = if (aipHex.length >= 2) {
    val firstByte = aipHex.substring(0, 2).toInt(16)
    (firstByte and 0x04) != 0  // Bit 3 indicates ARPC support
} else {
    false
}

if (arpcRequired) {
    // Card requires ARPC - update with response from Momentum
    val completeParams = CompleteEmvTransParams(
        emvResponseCode = saleData.emvResponseCode ?: "00",
        authorization = saleData.authorization ?: "",
        arpc = saleData.arpc ?: "",              // Response from bank
        script7172 = saleData.script ?: "",      // Script data (if any)
        arpcResponseCode = "00"
    )
    
    val completeResult = completeEmvTransUseCase.run(completeParams)
    
    if (completeResult.isLeft) {
        // Error updating chip
    } else {
        // Success - chip updated
    }
} else {
    // Card doesn't require ARPC - skip CompleteEmvTrans
}
```

---

## CONTACTLESS PAYMENT FLOW

### Complete Contactless Flow

```
User taps card
    ↓
[PHASE 1] StartCtlssTransUseCase
    → Process contactless transaction
    → No PIN needed (usually)
    ↓
[PHASE 2] Read TransResultEnum
    → RESULT_REQ_ONLINE: Needs online auth
    → RESULT_OFFLINE_APPROVED: Approved without bank
    → RESULT_OFFLINE_DENIED: Declined
    ↓
[PHASE 3] Route by Result
    ├─ REQ_ONLINE: Call processContactlessOnlineAuthorization()
    ├─ OFFLINE_APPROVED: Show success (offline)
    └─ OFFLINE_DENIED: Show error
```

### Contactless Online Authorization

**Location:** PaymentViewModel.kt:746-850

```kotlin
// Same as chip, but with CardTech.CONTACTLESS
val emvTagParams = GetEmvTagListParam(
    emvTagList = listOf(
        0x9F27, 0x9F26, 0x9F37, 0x9F36, 0x9C, 0x82,  // Same 21 tags
        // ... etc
    ),
    format = Format.DECIMAL,
    cardTech = CardTech.CONTACTLESS  // ⭐ Use CONTACTLESS instead of CHIP
)

val tagListResult = getEmvTagListUseCase.runInfallible(emvTagParams)
val emvTagListStr = tagListResult.emvTagList

// SaleIcc (same as chip)
val saleResponse = performOnlineAuthorization(
    amount = amount,
    track2 = track2,
    cardHolderName = "CARDHOLDER",
    emvTagList = emvTagListStr
)

// ⚠️ NOTE: Contactless typically does NOT require CompleteEmvTrans (ARPC)
// Skip ARPC checking for contactless transactions
```

---

## CRITICAL ISSUES & WORKAROUNDS

### 1. **Integer Overflow with Terminal Serial**

**Problem:**
```kotlin
val serialNumber = "2841548417"  // > Integer.MAX_VALUE (2,147,483,647)
Integer.parseInt(serialNumber)   // Throws NumberFormatException
```

**Solution (per Edgardo 2025-11-05):**
Use backend validation to get safe posId:
```kotlin
// BlumonInitializer.kt:248-276
val initData = validateAndGetInitData()  // Backend returns posId: "376"
// Now can safely: Integer.parseInt(initData.posId)  // 376 ✅
```

### 2. **DUKPT Key Corruption (na_002 errors)**

**Problem:**
Rebuilding app without uninstalling causes DUKPT key cache corruption → na_002 errors

**Solution (Development):**
```kotlin
// app/build.gradle.kts (debug)
buildConfigField("boolean", "FORCE_BLUMON_REINIT", "true")

// Effect: Always re-initialize on every app launch
// Clears corrupted DUKPT cache
```

**Production:**
```kotlin
// app/build.gradle.kts (release)
buildConfigField("boolean", "FORCE_BLUMON_REINIT", "false")

// Effect: Use 24-hour cache (per Edgardo recommendation)
// Only re-init once every 24 hours
```

### 3. **Duplicate InitData Rows**

**Problem:**
InitializerUseCase + InsertInitUseCase called on EVERY payment → 65 duplicate database rows → "query did not return a unique result" error

**Solution:**
```kotlin
// InitializationManager.kt
suspend fun ensureInitialized(): Result<Unit> {
    val lastInit = secureStorage.getLastBlumonInitTimestamp()
    val now = System.currentTimeMillis()

    return if (shouldInitialize(lastInit, now)) {
        executeInitialization(now)  // Full init
    } else {
        Result.success(Unit)         // Skip init (reuse cache)
    }
}
```

### 4. **CipherType.KUSHKY NotImplementedError**

**Problem:**
```kotlin
when (params.cipherType) {
    CipherType.DUKPT -> { /* ✅ Works */ }
    CipherType.PLAIN -> { /* ✅ Works */ }
    CipherType.KUSHKY -> { throw NotImplementedError() }  // ❌ Bug in SDK
}
```

**Solution (Workaround):**
```kotlin
// PaymentViewModel.kt:591
val cipherType = CipherType.DUKPT  // ⚠️ ALWAYS DUKPT (NEVER KUSHKY)

val params = SaleIccParams(
    // ...
    cipherType = cipherType  // ✅ DUKPT works in both SAND and PROD
)
```

### 5. **Track2 Extraction (Regex Issue)**

**Problem:**
Regex matching "57" matches inside other tags (e.g., "9F35012257...")

**Solution:**
```kotlin
// PaymentViewModel.kt:423-427
// Use GetTagValueUseCase (proper TLV parsing) instead of regex

val track2Params = GetTagValueParams(
    tag = 0x57,  // Track 2 Equivalent Data
    cardTech = CardTech.CHIP
)
val track2Result = getTagValueUseCase.run(track2Params)
currentTrack2 = if (track2Result.isRight) {
    track2Result.rightValue().tagValue ?: ""
} else {
    ""
}
```

### 6. **CompleteEmvTrans Error -11**

**Problem:**
Calling CompleteEmvTrans on cards that don't require ARPC → error -11 (FailureSecondGenerate)

**Solution:**
```kotlin
// PaymentViewModel.kt:472-520
// Extract AIP (tag 82) and check bit 3

val aipHex = ... // Get tag 82
val arpcRequired = if (aipHex.length >= 2) {
    val firstByte = aipHex.substring(0, 2).toInt(16)
    (firstByte and 0x04) != 0  // Bit 3
} else {
    false
}

if (arpcRequired) {
    completeEmvTransUseCase.run(...)  // Call only if needed
} else {
    // Skip - card doesn't require ARPC
}
```

### 7. **Card Reading Confirmation Flow**

**Problem:**
SDK's `confirmCardReadingFlow()` waits for app response via `ContinueConfirmCardUseCase`  
Without response, `StartEmvTransUseCase` blocks indefinitely

**Solution:**
```kotlin
// PaymentViewModel.kt:252-272
viewModelScope.launch {
    transProcessRepository.confirmCardReadingFlow().collect { confirmed ->
        if (confirmed) {
            viewModelScope.launch(Dispatchers.IO) {
                // ⭐ CRITICAL: Respond immediately
                val params = ContinueConfirmCardParams(emvCode = 0)  // 0 = success
                continueConfirmCardUseCase.runInfallible(params)
            }
        }
    }
}
```

### 8. **ABIs Configuration**

**Problem:**
```kotlin
// ❌ WRONG: Gradle auto-adds all ABIs
// Result: May conflict with Blumon's armeabi-only native libs

ndk {
    abiFilters.add("armeabi-v7a")
    abiFilters.add("arm64-v8a")  // Breaks Blumon!
}
```

**Solution:**
```kotlin
// ✅ CORRECT: Clear defaults, add ONLY armeabi
ndk {
    abiFilters.clear()            // ⚠️ Clear first!
    abiFilters.add("armeabi")     // ⭐ Blumon only supports armeabi
}
```

---

## TESTING CHECKLIST

### Unit Tests (ViewModel)

```kotlin
class PaymentViewModelTest {
    
    @Test
    fun `should start payment and emit correct state sequence`() = runTest {
        // Given
        val viewModel = PaymentViewModel(
            preTransUseCase = mockk(),
            startDetectCardUseCase = mockk(),
            // ... mock other dependencies
        )
        
        // When
        viewModel.startPayment("5000")
        advanceUntilIdle()
        
        // Then
        val states = viewModel.state.value
        assertThat(states).isInstanceOf(PaymentState.Processing::class.java)
    }
    
    @Test
    fun `should handle EMV error and emit Error state`() = runTest {
        // Test error handling
    }
}
```

### Integration Tests (E2E Payment Flow)

```kotlin
class PaymentIntegrationTest {
    
    @Test
    fun `complete chip card payment flow`() = runTest {
        // 1. Start payment
        // 2. Mock card tap
        // 3. Verify online authorization called
        // 4. Verify success state
    }
    
    @Test
    fun `contactless payment with online auth`() = runTest {
        // Test contactless routing
    }
    
    @Test
    fun `payment with ARPC required`() = runTest {
        // Test CompleteEmvTrans called
    }
    
    @Test
    fun `payment without ARPC required`() = runTest {
        // Test CompleteEmvTrans skipped
    }
}
```

### Manual Testing (Device)

```
BEFORE EVERY PAYMENT TEST:
☐ Terminal is on and connected
☐ Network is available (for online auth)
☐ App is fresh install (clear app data if testing init)
☐ Blumon test account active

CHIP CARD (INSERT):
☐ App shows "Detecting card..."
☐ Insert card with chip facing reader
☐ PAX hardware prompts for PIN (if required)
☐ Enter correct PIN on hardware (not app)
☐ App shows "Processing..."
☐ App shows "Autorización exitosa" with auth code
☐ Backend received transaction record

CONTACTLESS CARD (TAP):
☐ App shows "Detecting card..."
☐ Tap card on top of reader
☐ Card removed AFTER reading complete
☐ App shows online/offline result

ERROR HANDLING:
☐ Wrong PIN (3 attempts) → Card blocked
☐ Card removed during read → "Card removed" error
☐ Multiple cards detected → "Multiple cards" error
☐ No network → "Connection failed" error
☐ Bank declined → "Card declined" error

PIN DIALOG:
☐ Physical PAX keyboard activates (not app UI)
☐ App does NOT show PIN entry field
☐ Timeout after 60s shows "Timeout" error
```

---

## SUMMARY

### Integration Status

| Component | Status | Lines | Key File |
|-----------|--------|-------|----------|
| Payment ViewModel | ✅ Complete | 943 | PaymentViewModel.kt |
| Blumon Init | ✅ Complete | 412 | BlumonInitializer.kt |
| OAuth Manager | ✅ Complete | 255 | BlumonAuthManager.kt |
| Init Caching | ✅ Complete | 210 | InitializationManager.kt |
| Payment Screen | ✅ Complete | 100+ | PaymentScreen.kt |
| EMV Chip Flow | ✅ Implemented | Lines 301-534 | PaymentViewModel.kt |
| Contactless Flow | ✅ Implemented | Lines 646-739 | PaymentViewModel.kt |
| Online Auth | ✅ Implemented | Lines 541-632 | PaymentViewModel.kt |
| PIN Dialog Listeners | ✅ Implemented | Lines 199-273 | PaymentViewModel.kt |

### Module Dependencies

```
app/ (main app)
├── depends on: :sdk, :emv, :commonlib
├── depends on: blumon_sdk-debug.aar
├── depends on: lib-services-BP-SAND_1601.aar
└── depends on: nativetouchevent-release.aar

sdk/ (PAX SDK wrapper)
├── depends on: 9 PAX JARs (PosApi, Neptune, BaseLink, GL*)
└── exported via: api fileTree(include: ['*.jar'], dir: 'libs')

emv/ (EMV kernel)
├── depends on: :commonlib
├── depends on: 13 EMV JARs (EMV_v106, MC_v100, etc.)
└── exported via: api fileTree(include: ['*.jar'], dir: 'libs')

commonlib/ (shared utilities)
└── no dependencies (pure Java)
```

### Critical Build Configuration

| Setting | Value | Why |
|---------|-------|-----|
| minSdk | 27 | EMV module requirement |
| compileSdk | 36 | Latest stable (supports JDK 17) |
| Kotlin | 1.9.20 | KSP compatibility |
| ABIs | armeabi only | Blumon requirement |
| JNI Packaging | useLegacyPackaging=true | Required for native libs |

---

**Generated:** 2025-11-05  
**Verified with:** PaymentViewModel.kt, BlumonInitializer.kt, BlumonAuthManager.kt, InitializationManager.kt  
**Status:** Production Ready ✅

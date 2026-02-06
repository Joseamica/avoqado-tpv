# 🚀 Avoqado TPV - Greenfield Blueprint

> **Arquitectura de clase mundial para Android POS**
> Clean Architecture + MVVM + Jetpack Compose + Offline-First + Security-First

**Fecha:** 2025-11-03
**Objetivo:** Crear desde CERO una aplicación TPV de clase mundial, aprendiendo QUÉ hace AvoqadoPOS pero implementando CÓMO con las mejores prácticas modernas.

---

## 📋 Tabla de Contenidos

1. [Principios Fundamentales](#-principios-fundamentales)
2. [Stack Tecnológico](#-stack-tecnológico)
3. [Arquitectura Clean + MVVM](#-arquitectura-clean--mvvm)
4. [Estructura de Directorios](#-estructura-de-directorios)
5. [Sistema de Diseño Consistente](#-sistema-de-diseño-consistente)
6. [Módulos Core](#-módulos-core)
7. [Módulos Features](#-módulos-features)
8. [Real-Time con Socket.IO](#-real-time-con-socketio)
9. [Offline-First Strategy](#-offline-first-strategy)
10. [Security-First Patterns](#-security-first-patterns)
11. [Testing desde Día 1](#-testing-desde-día-1)
12. [Performance Optimization](#-performance-optimization)
13. [Accessibility](#-accessibility)
14. [Checklist Pre-Código](#-checklist-pre-código)

---

## 🎯 Principios Fundamentales

### 1. **Greenfield Total**
- ✅ Aprender funcionalidad de AvoqadoPOS (tablas, órdenes, pagos, turnos)
- ❌ NO copiar código de AvoqadoPOS (God objects, manual DI, SharedPreferences sin encriptar)
- ✅ Implementar con arquitectura moderna desde día 1

### 2. **World-Class Quality**
- 🏗️ **Arquitectura:** Clean Architecture + MVVM + UDF
- 🎨 **UI:** 100% Jetpack Compose (Material 3, NO XML)
- 💉 **DI:** Hilt (NO manual dependency injection)
- 🔐 **Security:** EncryptedSharedPreferences, Certificate Pinning, ProGuard
- 📴 **Offline:** Offline-first con Room + WorkManager
- 🧪 **Testing:** Unit + Integration + UI tests desde día 1
- ⚡ **Performance:** < 2s startup, 60fps constante, Baseline Profiles
- ♿ **Accessibility:** TalkBack, contrast AA/AAA, dynamic fonts

### 3. **Consistencia de Diseño**
- 🔘 **Componentes:** Librería centralizada (AvoqadoButton, AvoqadoCard, AvoqadoHeader)
- 📏 **Spacing:** Sistema basado en 4dp/8dp (nunca hardcoded)
- 🎨 **Colors:** SOLO MaterialTheme.colorScheme (nunca Color(0xFF...))
- 🔤 **Typography:** SOLO MaterialTheme.typography
- 📱 **Icons:** Posición y tamaño estandarizado
- 👁️ **Preview:** TODAS las composables deben tener @Preview

### 4. **Backend Integration**
- 🔌 **API:** REST + Socket.IO con avoqado-server
- 🗄️ **Schema:** Basado en schema.prisma (Terminal → Venue → Organization)
- 🏢 **Multi-tenant:** Aislamiento por venueId en TODAS las queries
- 💳 **Payments:** Integración con Blumon PAX SDK

---

## 🛠️ Stack Tecnológico

### Lenguaje & Build
```kotlin
// build.gradle.kts (Project)
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.google.dagger.hilt.android") version "2.50" apply false
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" apply false
}
```

### Core Dependencies
```kotlin
// build.gradle.kts (app)
dependencies {
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Jetpack Compose (Material 3)
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Hilt (Dependency Injection)
    implementation("com.google.dagger:hilt-android:2.50")
    ksp("com.google.dagger:hilt-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Socket.IO
    implementation("io.socket:socket.io-client:2.1.0")

    // Room (Offline Queue)
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Security
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // WorkManager (Offline Sync)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Blumon PAX SDK
    implementation(files("libs/blumon-pax-sdk.aar"))

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("app.cash.turbine:turbine:1.0.0")
    testImplementation("io.mockk:mockk:1.13.9")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.50")
}
```

### Critical Configuration
```kotlin
android {
    namespace = "com.jaac.avoqado_tpv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jaac.avoqado_tpv"
        minSdk = 24  // Android 7.0 (PAX terminals)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // ⚠️ CRITICAL: Blumon PAX SDK requires armeabi ONLY
        ndk {
            abiFilters.clear()
            abiFilters.add("armeabi")
        }

        // Environment variables (NEVER hardcode secrets)
        buildConfigField("String", "API_BASE_URL", "\"https://api.avoqado.io/api/v1/\"")
        buildConfigField("String", "SOCKET_URL", "\"https://api.avoqado.io\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true  // ⚠️ REQUIRED for Blumon native libs
        }
    }

    // ⚠️ LINT: Fail build on orphaned files
    lint {
        abortOnError = true
        warningsAsErrors = false

        // Treat UnusedResources as ERROR (prevents orphaned files)
        error += setOf(
            "UnusedResources",  // Unused drawables, layouts, strings
            "UnusedIds"         // Unused view IDs
        )

        htmlReport = true
        htmlOutput = layout.buildDirectory.file("reports/lint-results-debug.html").get().asFile
    }
}
```

---

## 🏗️ Arquitectura Clean + MVVM

### Capas & Responsabilidades

```
┌─────────────────────────────────────────────────────────┐
│  PRESENTATION LAYER (UI + ViewModel)                    │
│  - Jetpack Compose screens                              │
│  - ViewModels with StateFlow                            │
│  - UI State sealed classes                              │
│  - Navigation                                            │
└────────────────────┬────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│  DOMAIN LAYER (Business Logic)                          │
│  - Use Cases (single responsibility)                    │
│  - Domain Models (business entities)                    │
│  - Repository Interfaces                                │
│  - Business Rules                                        │
└────────────────────┬────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│  DATA LAYER (Data Sources)                              │
│  - Repository Implementations                           │
│  - Remote Data Source (Retrofit + Socket.IO)            │
│  - Local Data Source (Room)                             │
│  - DTOs (API response models)                           │
│  - Mappers (DTO → Domain)                               │
└─────────────────────────────────────────────────────────┘
```

### Flujo de Datos (UDF - Unidirectional Data Flow)

```
User Action → ViewModel → UseCase → Repository → API/DB
                 ↓                                    ↓
              UI State ←─────────────────────────────┘
```

### Ejemplo Completo: Feature Payment

```kotlin
// ========================================
// PRESENTATION LAYER
// ========================================

// UI State (sealed class)
sealed interface PaymentUiState {
    data object Idle : PaymentUiState
    data object Processing : PaymentUiState
    data class Success(val receipt: Receipt) : PaymentUiState
    data class Error(val message: String) : PaymentUiState
}

// ViewModel
@HiltViewModel
class PaymentViewModel @Inject constructor(
    private val processPaymentUseCase: ProcessPaymentUseCase,
    private val recordPaymentUseCase: RecordPaymentUseCase
) : ViewModel() {

    private val _state = MutableStateFlow<PaymentUiState>(PaymentUiState.Idle)
    val state: StateFlow<PaymentUiState> = _state.asStateFlow()

    fun processPayment(orderId: String, amount: BigDecimal, merchantAccountId: String) {
        viewModelScope.launch {
            _state.value = PaymentUiState.Processing

            processPaymentUseCase(
                orderId = orderId,
                amount = amount,
                merchantAccountId = merchantAccountId
            ).onSuccess { receipt ->
                _state.value = PaymentUiState.Success(receipt)
            }.onFailure { error ->
                _state.value = PaymentUiState.Error(error.message ?: "Unknown error")
            }
        }
    }
}

// Screen (Composable)
@Composable
fun PaymentScreen(
    viewModel: PaymentViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    AvoqadoScaffold(
        topBar = { AvoqadoTopBar(title = "Procesar Pago") }
    ) { padding ->
        when (val currentState = state) {
            is PaymentUiState.Idle -> PaymentIdleContent()
            is PaymentUiState.Processing -> AvoqadoLoadingIndicator()
            is PaymentUiState.Success -> SuccessContent(currentState.receipt)
            is PaymentUiState.Error -> AvoqadoErrorMessage(currentState.message)
        }
    }
}

// ========================================
// DOMAIN LAYER
// ========================================

// Domain Model
data class Payment(
    val id: String,
    val orderId: String,
    val amount: BigDecimal,
    val method: PaymentMethod,
    val status: PaymentStatus,
    val merchantAccountId: String,
    val createdAt: Instant
)

enum class PaymentMethod {
    CARD, CASH, TRANSFER
}

// Use Case
class ProcessPaymentUseCase @Inject constructor(
    private val paymentRepository: PaymentRepository,
    private val blumonSdk: BlumonSdkWrapper
) {
    suspend operator fun invoke(
        orderId: String,
        amount: BigDecimal,
        merchantAccountId: String
    ): Result<Receipt> = withContext(Dispatchers.IO) {
        try {
            // 1. Process with Blumon PAX
            val blumonResult = blumonSdk.processPayment(
                amount = amount.toPlainString(),
                merchantAccountId = merchantAccountId
            )

            // 2. Record in backend
            val payment = paymentRepository.recordPayment(
                orderId = orderId,
                amount = amount,
                method = PaymentMethod.CARD,
                merchantAccountId = merchantAccountId,
                blumonReference = blumonResult.reference
            )

            Result.success(payment.receipt)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

// Repository Interface (in domain)
interface PaymentRepository {
    suspend fun recordPayment(
        orderId: String,
        amount: BigDecimal,
        method: PaymentMethod,
        merchantAccountId: String,
        blumonReference: String
    ): Payment

    suspend fun getPaymentsByOrder(orderId: String): List<Payment>
}

// ========================================
// DATA LAYER
// ========================================

// DTO (API Response)
@Serializable
data class PaymentDto(
    val id: String,
    val orderId: String,
    val amount: String,  // Decimal as string from API
    val method: String,
    val status: String,
    val merchantAccountId: String,
    val createdAt: String
)

// Mapper
fun PaymentDto.toDomain(): Payment {
    return Payment(
        id = id,
        orderId = orderId,
        amount = amount.toBigDecimal(),
        method = PaymentMethod.valueOf(method),
        status = PaymentStatus.valueOf(status),
        merchantAccountId = merchantAccountId,
        createdAt = Instant.parse(createdAt)
    )
}

// Repository Implementation
class PaymentRepositoryImpl @Inject constructor(
    private val api: AvoqadoApiService,
    private val authContext: AuthContext
) : PaymentRepository {

    override suspend fun recordPayment(
        orderId: String,
        amount: BigDecimal,
        method: PaymentMethod,
        merchantAccountId: String,
        blumonReference: String
    ): Payment = withContext(Dispatchers.IO) {

        val request = RecordPaymentRequest(
            orderId = orderId,
            amount = amount.toPlainString(),
            method = method.name,
            merchantAccountId = merchantAccountId,
            blumonReference = blumonReference
        )

        val response = api.recordPayment(
            venueId = authContext.venueId,  // ⚠️ ALWAYS include tenant ID
            orderId = orderId,
            request = request
        )

        if (response.isSuccessful) {
            response.body()!!.toDomain()
        } else {
            throw ApiException(response.code(), response.message())
        }
    }
}

// API Service
interface AvoqadoApiService {
    @POST("tpv/venues/{venueId}/orders/{orderId}/payments")
    suspend fun recordPayment(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body request: RecordPaymentRequest
    ): Response<PaymentDto>
}
```

---

## 📁 Estructura de Directorios

```
avoqado-tpv/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── kotlin/com/jaac/avoqado_tpv/
│   │   │   │   ├── AvoqadoApp.kt                    # @HiltAndroidApp
│   │   │   │   │
│   │   │   │   ├── core/                            # ⚙️ Infraestructura compartida
│   │   │   │   │   ├── data/
│   │   │   │   │   │   ├── network/
│   │   │   │   │   │   │   ├── interceptors/
│   │   │   │   │   │   │   │   ├── AuthInterceptor.kt
│   │   │   │   │   │   │   │   ├── TenantInterceptor.kt
│   │   │   │   │   │   │   │   └── LoggingInterceptor.kt
│   │   │   │   │   │   │   ├── ssl/
│   │   │   │   │   │   │   │   └── CertificatePinner.kt
│   │   │   │   │   │   │   └── ApiService.kt
│   │   │   │   │   │   ├── local/
│   │   │   │   │   │   │   ├── SecureStorage.kt           # EncryptedSharedPreferences
│   │   │   │   │   │   │   ├── database/
│   │   │   │   │   │   │   │   ├── AvoqadoDatabase.kt    # Room (offline queue)
│   │   │   │   │   │   │   │   └── dao/
│   │   │   │   │   │   │   │       └── OfflineQueueDao.kt
│   │   │   │   │   │   │   └── preferences/
│   │   │   │   │   │   │       └── AppPreferences.kt
│   │   │   │   │   │   └── realtime/
│   │   │   │   │   │       ├── SocketManager.kt           # Socket.IO wrapper
│   │   │   │   │   │       └── events/
│   │   │   │   │   │           └── SocketEvent.kt
│   │   │   │   │   │
│   │   │   │   │   ├── domain/
│   │   │   │   │   │   ├── models/
│   │   │   │   │   │   │   ├── Result.kt                  # Sealed class for API results
│   │   │   │   │   │   │   ├── ApiException.kt
│   │   │   │   │   │   │   └── AuthContext.kt             # venueId, staffId, permissions
│   │   │   │   │   │   └── repository/
│   │   │   │   │   │       └── BaseRepository.kt
│   │   │   │   │   │
│   │   │   │   │   ├── di/                             # 💉 Hilt modules
│   │   │   │   │   │   ├── NetworkModule.kt
│   │   │   │   │   │   ├── DatabaseModule.kt
│   │   │   │   │   │   ├── RepositoryModule.kt
│   │   │   │   │   │   └── BlumonModule.kt
│   │   │   │   │   │
│   │   │   │   │   ├── presentation/                   # 🎨 Shared UI
│   │   │   │   │   │   ├── theme/
│   │   │   │   │   │   │   ├── AvoqadoTheme.kt
│   │   │   │   │   │   │   ├── Color.kt
│   │   │   │   │   │   │   ├── Typography.kt
│   │   │   │   │   │   │   └── Dimensions.kt
│   │   │   │   │   │   ├── components/                 # ⭐ COMPONENTES ESTANDARIZADOS
│   │   │   │   │   │   │   ├── AvoqadoButton.kt
│   │   │   │   │   │   │   ├── AvoqadoCard.kt
│   │   │   │   │   │   │   ├── AvoqadoTopBar.kt
│   │   │   │   │   │   │   ├── AvoqadoTextField.kt
│   │   │   │   │   │   │   ├── AvoqadoScaffold.kt
│   │   │   │   │   │   │   ├── AvoqadoLoadingIndicator.kt
│   │   │   │   │   │   │   ├── AvoqadoErrorMessage.kt
│   │   │   │   │   │   │   └── AvoqadoDialog.kt
│   │   │   │   │   │   └── navigation/
│   │   │   │   │   │       ├── Navigator.kt
│   │   │   │   │   │       └── Routes.kt
│   │   │   │   │   │
│   │   │   │   │   └── util/
│   │   │   │   │       ├── Extensions.kt
│   │   │   │   │       ├── Constants.kt
│   │   │   │   │       └── Formatters.kt
│   │   │   │   │
│   │   │   │   ├── features/                           # 🎯 Módulos de features
│   │   │   │   │   │
│   │   │   │   │   ├── auth/                           # Feature: Authentication
│   │   │   │   │   │   ├── data/
│   │   │   │   │   │   │   ├── remote/
│   │   │   │   │   │   │   │   ├── AuthApiService.kt
│   │   │   │   │   │   │   │   └── dto/
│   │   │   │   │   │   │   │       ├── LoginRequest.kt
│   │   │   │   │   │   │   │       └── AuthResponse.kt
│   │   │   │   │   │   │   └── repository/
│   │   │   │   │   │   │       └── AuthRepositoryImpl.kt
│   │   │   │   │   │   ├── domain/
│   │   │   │   │   │   │   ├── model/
│   │   │   │   │   │   │   │   ├── User.kt
│   │   │   │   │   │   │   │   └── Session.kt
│   │   │   │   │   │   │   ├── repository/
│   │   │   │   │   │   │   │   └── AuthRepository.kt
│   │   │   │   │   │   │   └── usecase/
│   │   │   │   │   │   │       ├── LoginWithPinUseCase.kt
│   │   │   │   │   │   │       └── LogoutUseCase.kt
│   │   │   │   │   │   └── presentation/
│   │   │   │   │   │       ├── login/
│   │   │   │   │   │       │   ├── LoginScreen.kt
│   │   │   │   │   │       │   ├── LoginViewModel.kt
│   │   │   │   │   │       │   └── LoginUiState.kt
│   │   │   │   │   │       └── pin/
│   │   │   │   │   │           ├── PinEntryScreen.kt
│   │   │   │   │   │           └── PinEntryViewModel.kt
│   │   │   │   │   │
│   │   │   │   │   ├── tables/                         # Feature: Table Management
│   │   │   │   │   │   ├── data/
│   │   │   │   │   │   │   └── repository/
│   │   │   │   │   │   │       └── TableRepositoryImpl.kt
│   │   │   │   │   │   ├── domain/
│   │   │   │   │   │   │   ├── model/
│   │   │   │   │   │   │   │   └── Table.kt
│   │   │   │   │   │   │   ├── repository/
│   │   │   │   │   │   │   │   └── TableRepository.kt
│   │   │   │   │   │   │   └── usecase/
│   │   │   │   │   │   │       ├── GetTablesUseCase.kt
│   │   │   │   │   │   │       └── UpdateTableStatusUseCase.kt
│   │   │   │   │   │   └── presentation/
│   │   │   │   │   │       ├── list/
│   │   │   │   │   │       │   ├── TablesScreen.kt
│   │   │   │   │   │       │   ├── TablesViewModel.kt
│   │   │   │   │   │       │   └── components/
│   │   │   │   │   │       │       └── TableCard.kt
│   │   │   │   │   │       └── detail/
│   │   │   │   │   │           └── TableDetailScreen.kt
│   │   │   │   │   │
│   │   │   │   │   ├── orders/                         # Feature: Order Management
│   │   │   │   │   │   ├── data/
│   │   │   │   │   │   ├── domain/
│   │   │   │   │   │   │   ├── model/
│   │   │   │   │   │   │   │   ├── Order.kt
│   │   │   │   │   │   │   │   ├── OrderItem.kt
│   │   │   │   │   │   │   │   └── OrderStatus.kt
│   │   │   │   │   │   │   └── usecase/
│   │   │   │   │   │   │       ├── CreateOrderUseCase.kt
│   │   │   │   │   │   │       ├── GetActiveOrdersUseCase.kt
│   │   │   │   │   │   │       └── UpdateOrderUseCase.kt
│   │   │   │   │   │   └── presentation/
│   │   │   │   │   │       ├── create/
│   │   │   │   │   │       │   └── CreateOrderScreen.kt
│   │   │   │   │   │       └── list/
│   │   │   │   │   │           └── OrdersScreen.kt
│   │   │   │   │   │
│   │   │   │   │   ├── payment/                        # Feature: Payment Processing
│   │   │   │   │   │   ├── data/
│   │   │   │   │   │   │   ├── blumon/
│   │   │   │   │   │   │   │   ├── BlumonSdkWrapper.kt
│   │   │   │   │   │   │   │   └── CredentialCache.kt
│   │   │   │   │   │   │   └── repository/
│   │   │   │   │   │   │       └── PaymentRepositoryImpl.kt
│   │   │   │   │   │   ├── domain/
│   │   │   │   │   │   │   ├── model/
│   │   │   │   │   │   │   │   ├── Payment.kt
│   │   │   │   │   │   │   │   └── Receipt.kt
│   │   │   │   │   │   │   └── usecase/
│   │   │   │   │   │   │       ├── ProcessPaymentUseCase.kt
│   │   │   │   │   │   │       └── RecordPaymentUseCase.kt
│   │   │   │   │   │   └── presentation/
│   │   │   │   │   │       ├── process/
│   │   │   │   │   │       │   ├── PaymentScreen.kt
│   │   │   │   │   │       │   └── PaymentViewModel.kt
│   │   │   │   │   │       └── receipt/
│   │   │   │   │   │           └── ReceiptScreen.kt
│   │   │   │   │   │
│   │   │   │   │   ├── menu/                           # Feature: Product Catalog
│   │   │   │   │   │   ├── data/
│   │   │   │   │   │   ├── domain/
│   │   │   │   │   │   │   ├── model/
│   │   │   │   │   │   │   │   ├── Product.kt
│   │   │   │   │   │   │   │   └── Category.kt
│   │   │   │   │   │   │   └── usecase/
│   │   │   │   │   │   │       └── GetMenuUseCase.kt
│   │   │   │   │   │   └── presentation/
│   │   │   │   │   │       └── MenuScreen.kt
│   │   │   │   │   │
│   │   │   │   │   └── timeclock/                      # Feature: Shift Management
│   │   │   │   │       ├── data/
│   │   │   │   │       ├── domain/
│   │   │   │   │       │   ├── model/
│   │   │   │   │       │   │   └── Shift.kt
│   │   │   │   │       │   └── usecase/
│   │   │   │   │       │       ├── ClockInUseCase.kt
│   │   │   │   │       │       └── ClockOutUseCase.kt
│   │   │   │   │       └── presentation/
│   │   │   │   │           └── TimeclockScreen.kt
│   │   │   │   │
│   │   │   │   └── MainActivity.kt
│   │   │   │
│   │   │   ├── res/
│   │   │   │   ├── values/
│   │   │   │   │   ├── strings.xml                     # TODAS las strings aquí
│   │   │   │   │   └── themes.xml
│   │   │   │   └── drawable/                           # SOLO iconos/assets
│   │   │   │
│   │   │   └── AndroidManifest.xml
│   │   │
│   │   ├── test/                                       # Unit tests
│   │   │   └── kotlin/com/jaac/avoqado_tpv/
│   │   │       ├── features/
│   │   │       │   ├── auth/
│   │   │       │   │   └── LoginViewModelTest.kt
│   │   │       │   └── payment/
│   │   │       │       └── PaymentViewModelTest.kt
│   │   │       └── core/
│   │   │
│   │   └── androidTest/                                # Instrumented tests
│   │       └── kotlin/com/jaac/avoqado_tpv/
│   │           ├── flows/
│   │           │   └── PaymentFlowTest.kt
│   │           └── ui/
│   │               └── LoginScreenTest.kt
│   │
│   ├── build.gradle.kts
│   └── proguard-rules.pro
│
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── CLAUDE.md                                           # Project context
└── GREENFIELD_BLUEPRINT.md                             # This file
```

---

## 🎨 Sistema de Diseño Consistente

### Regla de Oro
> **NUNCA crear variaciones de componentes. Usar SIEMPRE los componentes estandarizados de `core/presentation/components/`**

### Componentes Estandarizados

#### 1. AvoqadoButton
```kotlin
// core/presentation/components/AvoqadoButton.kt
@Composable
fun AvoqadoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: ButtonVariant = ButtonVariant.Primary,
    icon: ImageVector? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),  // ⚠️ SIEMPRE 56dp altura
        colors = ButtonDefaults.buttonColors(
            containerColor = when (variant) {
                ButtonVariant.Primary -> MaterialTheme.colorScheme.primary
                ButtonVariant.Secondary -> MaterialTheme.colorScheme.secondary
                ButtonVariant.Danger -> MaterialTheme.colorScheme.error
            }
        ),
        shape = RoundedCornerShape(12.dp)  // ⚠️ SIEMPRE 12dp radio
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier
                        .size(20.dp)  // ⚠️ SIEMPRE 20dp iconos en botones
                        .padding(end = 8.dp)
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge  // ⚠️ SIEMPRE labelLarge
            )
        }
    }
}

enum class ButtonVariant { Primary, Secondary, Danger }

@Preview(showBackground = true)
@Composable
private fun AvoqadoButtonPreview() {
    AvoqadoTheme {
        Column(Modifier.padding(16.dp)) {
            AvoqadoButton(text = "Primary", onClick = {})
            Spacer(Modifier.height(8.dp))
            AvoqadoButton(
                text = "With Icon",
                onClick = {},
                icon = Icons.Default.Check
            )
        }
    }
}
```

#### 2. AvoqadoTopBar
```kotlin
// core/presentation/components/AvoqadoTopBar.kt
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvoqadoTopBar(
    title: String,
    onBackClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,  // ⚠️ SIEMPRE titleLarge
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            onBackClick?.let {
                IconButton(onClick = it) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Volver",
                        modifier = Modifier.size(24.dp)  // ⚠️ SIEMPRE 24dp para iconos de navegación
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

@Preview
@Composable
private fun AvoqadoTopBarPreview() {
    AvoqadoTheme {
        AvoqadoTopBar(
            title = "Procesar Pago",
            onBackClick = {}
        )
    }
}
```

#### 3. AvoqadoCard
```kotlin
// core/presentation/components/AvoqadoCard.kt
@Composable
fun AvoqadoCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.then(
            onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier
        ),
        shape = RoundedCornerShape(16.dp),  // ⚠️ SIEMPRE 16dp para cards
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp  // ⚠️ SIEMPRE 2dp elevation
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),  // ⚠️ SIEMPRE 16dp padding interno
            content = content
        )
    }
}

@Preview
@Composable
private fun AvoqadoCardPreview() {
    AvoqadoTheme {
        AvoqadoCard {
            Text("Card Content")
        }
    }
}
```

#### 4. AvoqadoTextField
```kotlin
// core/presentation/components/AvoqadoTextField.kt
@Composable
fun AvoqadoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    onTrailingIconClick: (() -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    isError: Boolean = false,
    errorMessage: String? = null,
    enabled: Boolean = true
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            leadingIcon = leadingIcon?.let {
                {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)  // ⚠️ SIEMPRE 20dp
                    )
                }
            },
            trailingIcon = trailingIcon?.let {
                {
                    IconButton(onClick = { onTrailingIconClick?.invoke() }) {
                        Icon(
                            imageVector = it,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            isError = isError,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = RoundedCornerShape(12.dp),  // ⚠️ SIEMPRE 12dp
            modifier = Modifier.fillMaxWidth()
        )

        if (isError && errorMessage != null) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        }
    }
}
```

### Sistema de Spacing

```kotlin
// core/presentation/theme/Dimensions.kt
object AvoqadoDimensions {
    // Spacing (basado en 4dp/8dp grid)
    val space4 = 4.dp
    val space8 = 8.dp
    val space12 = 12.dp
    val space16 = 16.dp
    val space24 = 24.dp
    val space32 = 32.dp
    val space48 = 48.dp

    // Component sizes
    val buttonHeight = 56.dp
    val iconSmall = 16.dp
    val iconMedium = 20.dp
    val iconLarge = 24.dp
    val iconXLarge = 32.dp

    // Border radius
    val radiusSmall = 8.dp
    val radiusMedium = 12.dp
    val radiusLarge = 16.dp

    // Elevation
    val elevationNone = 0.dp
    val elevationSmall = 2.dp
    val elevationMedium = 4.dp
    val elevationLarge = 8.dp
}
```

### Tema Material 3

```kotlin
// core/presentation/theme/Color.kt
val Primary = Color(0xFF2563EB)      // Blue 600
val OnPrimary = Color(0xFFFFFFFF)
val Secondary = Color(0xFF10B981)    // Green 500
val OnSecondary = Color(0xFFFFFFFF)
val Error = Color(0xFFDC2626)        // Red 600
val OnError = Color(0xFFFFFFFF)
val Background = Color(0xFFF9FAFB)   // Gray 50
val Surface = Color(0xFFFFFFFF)
val OnSurface = Color(0xFF111827)    // Gray 900

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    secondary = Secondary,
    onSecondary = OnSecondary,
    error = Error,
    onError = OnError,
    background = Background,
    surface = Surface,
    onSurface = OnSurface
)

// core/presentation/theme/Typography.kt
val AvoqadoTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    )
)

// core/presentation/theme/AvoqadoTheme.kt
@Composable
fun AvoqadoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,  // TODO: Add dark theme
        typography = AvoqadoTypography,
        content = content
    )
}
```

---

## ⚙️ Módulos Core

### 1. Networking (Retrofit + Certificate Pinning)

```kotlin
// core/data/network/ApiService.kt
interface AvoqadoApiService {
    // Auth
    @POST("tpv/venues/{venueId}/auth/login-pin")
    suspend fun loginWithPin(
        @Path("venueId") venueId: String,
        @Body request: PinLoginRequest
    ): Response<AuthResponse>

    // Tables
    @GET("tpv/venues/{venueId}/tables")
    suspend fun getTables(@Path("venueId") venueId: String): Response<List<TableDto>>

    // Orders
    @GET("tpv/venues/{venueId}/orders")
    suspend fun getOrders(@Path("venueId") venueId: String): Response<List<OrderDto>>

    @POST("tpv/venues/{venueId}/orders")
    suspend fun createOrder(
        @Path("venueId") venueId: String,
        @Body order: CreateOrderRequest
    ): Response<OrderDto>

    // Payments
    @POST("tpv/venues/{venueId}/orders/{orderId}/payments")
    suspend fun recordPayment(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body payment: RecordPaymentRequest
    ): Response<PaymentDto>
}

// core/data/network/interceptors/AuthInterceptor.kt
class AuthInterceptor @Inject constructor(
    private val secureStorage: SecureStorage
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val token = secureStorage.getToken()

        val request = chain.request().newBuilder()
            .apply {
                if (token != null) {
                    addHeader("Authorization", "Bearer $token")
                }
            }
            .build()

        return chain.proceed(request)
    }
}

// core/data/network/ssl/CertificatePinner.kt
object CertificatePinning {
    val certificatePinner = CertificatePinner.Builder()
        .add("api.avoqado.io", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        .build()
}

// core/di/NetworkModule.kt
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BODY
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
            })
            .certificatePinner(CertificatePinning.certificatePinner)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): AvoqadoApiService {
        return retrofit.create(AvoqadoApiService::class.java)
    }
}
```

### 2. Secure Storage (EncryptedSharedPreferences)

```kotlin
// core/data/local/SecureStorage.kt
class SecureStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "avoqado_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // ✅ ALWAYS encrypt sensitive data
    fun saveToken(token: String) {
        encryptedPrefs.edit().putString(KEY_TOKEN, token).apply()
    }

    fun getToken(): String? {
        return encryptedPrefs.getString(KEY_TOKEN, null)
    }

    fun saveVenueId(venueId: String) {
        encryptedPrefs.edit().putString(KEY_VENUE_ID, venueId).apply()
    }

    fun getVenueId(): String? {
        return encryptedPrefs.getString(KEY_VENUE_ID, null)
    }

    fun saveStaffId(staffId: String) {
        encryptedPrefs.edit().putString(KEY_STAFF_ID, staffId).apply()
    }

    fun getStaffId(): String? {
        return encryptedPrefs.getString(KEY_STAFF_ID, null)
    }

    fun clear() {
        encryptedPrefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_VENUE_ID = "venue_id"
        private const val KEY_STAFF_ID = "staff_id"
    }
}
```

### 3. Socket.IO Manager

```kotlin
// core/data/realtime/SocketManager.kt
@Singleton
class SocketManager @Inject constructor() {

    private var socket: Socket? = null
    private val _events = MutableSharedFlow<SocketEvent>(
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<SocketEvent> = _events.asSharedFlow()

    fun connect(url: String, token: String) {
        if (socket?.connected() == true) return

        val options = IO.Options().apply {
            auth = mapOf("token" to token)
            reconnection = true
            reconnectionDelay = 1000
            reconnectionDelayMax = 5000
        }

        socket = IO.socket(url, options).apply {
            on(Socket.EVENT_CONNECT) {
                Timber.d("✅ Socket connected")
                _events.tryEmit(SocketEvent.Connected)
            }

            on(Socket.EVENT_DISCONNECT) {
                Timber.w("⚠️ Socket disconnected")
                _events.tryEmit(SocketEvent.Disconnected)
            }

            on(Socket.EVENT_ERROR) { args ->
                Timber.e("❌ Socket error: ${args[0]}")
                _events.tryEmit(SocketEvent.Error(args[0].toString()))
            }

            // Business events
            on("order_updated") { args ->
                val data = args[0] as JSONObject
                _events.tryEmit(SocketEvent.OrderUpdated(data.toString()))
            }

            on("payment_completed") { args ->
                val data = args[0] as JSONObject
                _events.tryEmit(SocketEvent.PaymentCompleted(data.toString()))
            }

            connect()
        }
    }

    fun joinVenueRoom(venueId: String) {
        socket?.emit("join_room", JSONObject().apply {
            put("roomType", "venue")
            put("venueId", venueId)
        })
    }

    fun disconnect() {
        socket?.disconnect()
        socket = null
    }
}

// core/data/realtime/events/SocketEvent.kt
sealed interface SocketEvent {
    data object Connected : SocketEvent
    data object Disconnected : SocketEvent
    data class Error(val message: String) : SocketEvent
    data class OrderUpdated(val data: String) : SocketEvent
    data class PaymentCompleted(val data: String) : SocketEvent
}
```

### 4. Offline Queue (Room + WorkManager)

```kotlin
// core/data/local/database/AvoqadoDatabase.kt
@Database(
    entities = [OfflinePaymentEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AvoqadoDatabase : RoomDatabase() {
    abstract fun offlineQueueDao(): OfflineQueueDao
}

@Entity(tableName = "offline_payments")
data class OfflinePaymentEntity(
    @PrimaryKey val id: String,
    val orderId: String,
    val amount: String,
    val method: String,
    val merchantAccountId: String,
    val blumonReference: String,
    val createdAt: Long,
    val syncStatus: String  // PENDING, SYNCING, SYNCED, FAILED
)

@Dao
interface OfflineQueueDao {
    @Query("SELECT * FROM offline_payments WHERE syncStatus = 'PENDING'")
    suspend fun getPendingPayments(): List<OfflinePaymentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(payment: OfflinePaymentEntity)

    @Query("UPDATE offline_payments SET syncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: String)

    @Delete
    suspend fun delete(payment: OfflinePaymentEntity)
}

// core/data/sync/PaymentSyncWorker.kt
@HiltWorker
class PaymentSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val offlineQueueDao: OfflineQueueDao,
    private val paymentRepository: PaymentRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val pending = offlineQueueDao.getPendingPayments()

            pending.forEach { payment ->
                offlineQueueDao.updateSyncStatus(payment.id, "SYNCING")

                try {
                    paymentRepository.recordPayment(
                        orderId = payment.orderId,
                        amount = payment.amount.toBigDecimal(),
                        method = PaymentMethod.valueOf(payment.method),
                        merchantAccountId = payment.merchantAccountId,
                        blumonReference = payment.blumonReference
                    )

                    offlineQueueDao.delete(payment)
                    Timber.d("✅ Synced payment ${payment.id}")
                } catch (e: Exception) {
                    offlineQueueDao.updateSyncStatus(payment.id, "FAILED")
                    Timber.e(e, "❌ Failed to sync payment ${payment.id}")
                }
            }

            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "❌ Sync worker failed")
            Result.retry()
        }
    }
}
```

---

## 🎯 Módulos Features

### Feature: Auth (PIN Login)

```kotlin
// features/auth/domain/usecase/LoginWithPinUseCase.kt
class LoginWithPinUseCase @Inject constructor(
    private val authRepository: AuthRepository,
    private val secureStorage: SecureStorage,
    private val socketManager: SocketManager
) {
    suspend operator fun invoke(venueId: String, pin: String): Result<Session> {
        return try {
            val session = authRepository.loginWithPin(venueId, pin)

            // Save session securely
            secureStorage.saveToken(session.token)
            secureStorage.saveVenueId(session.venueId)
            secureStorage.saveStaffId(session.staffId)

            // Connect to real-time
            socketManager.connect(BuildConfig.SOCKET_URL, session.token)
            socketManager.joinVenueRoom(session.venueId)

            Result.success(session)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

// features/auth/presentation/login/LoginViewModel.kt
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val loginWithPinUseCase: LoginWithPinUseCase
) : ViewModel() {

    private val _state = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun login(venueId: String, pin: String) {
        if (pin.length != 4) {
            _state.value = LoginUiState.Error("PIN debe tener 4 dígitos")
            return
        }

        viewModelScope.launch {
            _state.value = LoginUiState.Loading

            loginWithPinUseCase(venueId, pin)
                .onSuccess { session ->
                    _state.value = LoginUiState.Success(session)
                }
                .onFailure { error ->
                    _state.value = LoginUiState.Error(
                        error.message ?: "Error desconocido"
                    )
                }
        }
    }
}

sealed interface LoginUiState {
    data object Idle : LoginUiState
    data object Loading : LoginUiState
    data class Success(val session: Session) : LoginUiState
    data class Error(val message: String) : LoginUiState
}
```

### Feature: Payment (Blumon Integration)

```kotlin
// features/payment/data/blumon/BlumonSdkWrapper.kt
@Singleton
class BlumonSdkWrapper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentialCache: CredentialCache
) {

    init {
        AppManager.init(context)
    }

    suspend fun processPayment(
        amount: String,
        merchantAccountId: String
    ): BlumonResult = withContext(Dispatchers.IO) {

        // Use cached credentials (reduces 6s → <1s)
        val credentials = credentialCache.get(merchantAccountId)

        val request = PaymentRequest().apply {
            this.amount = amount
            this.merchantAccountId = merchantAccountId
            this.credentials = credentials
        }

        // Blumon SDK call (blocking)
        val result = AppManager.processPayment(request)

        // Cache credentials for next payment
        credentialCache.put(merchantAccountId, result.credentials)

        BlumonResult(
            success = result.isSuccess,
            reference = result.reference,
            errorMessage = result.errorMessage
        )
    }
}

data class BlumonResult(
    val success: Boolean,
    val reference: String,
    val errorMessage: String?
)

// features/payment/data/blumon/CredentialCache.kt
@Singleton
class CredentialCache @Inject constructor() {
    private val cache = mutableMapOf<String, Credentials>()

    fun get(merchantAccountId: String): Credentials? {
        return cache[merchantAccountId]
    }

    fun put(merchantAccountId: String, credentials: Credentials) {
        cache[merchantAccountId] = credentials
    }

    fun clear() {
        cache.clear()
    }
}
```

---

## 🔌 Real-Time con Socket.IO

### Casos de Uso

1. **Órdenes Actualizadas**: Otro terminal modifica una orden
2. **Pagos Completados**: Confirmar pago procesado
3. **Mesas Actualizadas**: Estado de mesa cambiado
4. **Heartbeat**: Terminal activo

### Patrón de Implementación

```kotlin
// En cualquier ViewModel
@HiltViewModel
class OrdersViewModel @Inject constructor(
    private val getOrdersUseCase: GetOrdersUseCase,
    private val socketManager: SocketManager
) : ViewModel() {

    init {
        // Subscribe to real-time events
        viewModelScope.launch {
            socketManager.events.collect { event ->
                when (event) {
                    is SocketEvent.OrderUpdated -> {
                        // Refresh orders list
                        refreshOrders()
                    }
                    else -> {}
                }
            }
        }
    }
}
```

### Rate Limiting (evitar sobrecarga)

```kotlin
// Debounce de 500ms para evitar múltiples actualizaciones
socketManager.events
    .debounce(500)
    .collect { event ->
        // Handle event
    }
```

---

## 📴 Offline-First Strategy

### ¿Qué se guarda offline?

#### ✅ SOLO en memoria/cache (NO Room)
- Lista de mesas
- Lista de productos (menú)
- Órdenes activas

#### ✅ EN Room (offline queue)
- Pagos pendientes de sincronizar

#### ❌ NUNCA offline
- Login/autenticación (requiere validación en tiempo real)
- Inventario (debe ser en tiempo real para evitar inconsistencias)

### Flujo de Pago Offline

```kotlin
// 1. Procesar con Blumon (offline-capable)
val blumonResult = blumonSdk.processPayment(amount, merchantAccountId)

// 2. Si hay internet, sincronizar inmediatamente
if (networkManager.isOnline()) {
    paymentRepository.recordPayment(...)
} else {
    // 3. Si no hay internet, guardar en cola
    offlineQueueDao.insert(OfflinePaymentEntity(...))

    // 4. WorkManager intentará sincronizar automáticamente
    enqueuePaymentSync()
}
```

---

## 🔐 Security-First Patterns

### 1. NUNCA hardcodear secretos

```kotlin
// ❌ MAL
const val API_KEY = "sk_live_abc123"

// ✅ BIEN
// En build.gradle.kts
buildConfigField("String", "API_KEY", "\"${System.getenv("AVOQADO_API_KEY")}\"")

// En código
val apiKey = BuildConfig.API_KEY
```

### 2. SIEMPRE filtrar por venueId (tenant isolation)

```kotlin
// ❌ MAL (cross-tenant data leak!)
suspend fun getOrders(): List<Order> {
    return api.getAllOrders()  // ⚠️ SECURITY RISK!
}

// ✅ BIEN
suspend fun getOrders(venueId: String): List<Order> {
    return api.getOrders(venueId)
}
```

### 3. SIEMPRE usar EncryptedSharedPreferences

```kotlin
// ❌ MAL
val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
prefs.edit().putString("token", token).apply()  // ⚠️ Plain text!

// ✅ BIEN
secureStorage.saveToken(token)  // Encrypted
```

### 4. Certificate Pinning en producción

```kotlin
val certificatePinner = CertificatePinner.Builder()
    .add("api.avoqado.io", "sha256/YOUR_CERTIFICATE_HASH_HERE")
    .build()
```

### 5. ProGuard Rules

```proguard
# proguard-rules.pro

# Keep Retrofit models
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.jaac.avoqado_tpv.**.dto.** { *; }

# Keep Hilt
-dontwarn com.google.dagger.**
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper

# Blumon SDK
-keep class com.blumon.** { *; }
-keepclassmembers class com.blumon.** { *; }
```

---

## 🧪 Testing desde Día 1

### Unit Tests (ViewModels)

```kotlin
// features/payment/PaymentViewModelTest.kt
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var viewModel: PaymentViewModel
    private val processPaymentUseCase: ProcessPaymentUseCase = mockk()

    @Before
    fun setup() {
        viewModel = PaymentViewModel(processPaymentUseCase)
    }

    @Test
    fun `processPayment should emit success state when payment succeeds`() = runTest {
        // Given
        val receipt = Receipt(id = "123", amount = 500.toBigDecimal())
        coEvery { processPaymentUseCase(any(), any(), any()) } returns Result.success(receipt)

        // When
        viewModel.processPayment("order123", 500.toBigDecimal(), "ma_operativa")

        // Then
        val state = viewModel.state.value
        assertThat(state).isInstanceOf(PaymentUiState.Success::class.java)
        assertThat((state as PaymentUiState.Success).receipt.id).isEqualTo("123")
    }

    @Test
    fun `processPayment should emit error state when payment fails`() = runTest {
        // Given
        coEvery { processPaymentUseCase(any(), any(), any()) } returns
            Result.failure(Exception("Network error"))

        // When
        viewModel.processPayment("order123", 500.toBigDecimal(), "ma_operativa")

        // Then
        val state = viewModel.state.value
        assertThat(state).isInstanceOf(PaymentUiState.Error::class.java)
    }
}
```

### Integration Tests

```kotlin
// flows/PaymentFlowTest.kt
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class PaymentFlowTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun completePaymentFlow() {
        // 1. Login
        composeTestRule.onNodeWithText("Iniciar Sesión").performClick()
        composeTestRule.onNodeWithTag("pin_input").performTextInput("1234")
        composeTestRule.onNodeWithText("Ingresar").performClick()

        // 2. Select table
        composeTestRule.onNodeWithText("Mesa 1").performClick()

        // 3. Create order
        composeTestRule.onNodeWithText("Hamburguesa").performClick()
        composeTestRule.onNodeWithText("Crear Orden").performClick()

        // 4. Process payment
        composeTestRule.onNodeWithText("Pagar").performClick()
        composeTestRule.onNodeWithText("Procesar Pago").performClick()

        // 5. Verify success
        composeTestRule.onNodeWithText("Pago Exitoso").assertIsDisplayed()
    }
}
```

---

## ⚡ Performance Optimization

### 1. Startup < 2 segundos

```kotlin
// AvoqadoApp.kt
class AvoqadoApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // ✅ Initialize critical components only
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // ⚠️ Defer non-critical initialization
        lifecycleScope.launch {
            initializeNonCritical()
        }
    }

    private suspend fun initializeNonCritical() = withContext(Dispatchers.IO) {
        // Analytics, crash reporting, etc.
    }
}
```

### 2. Baseline Profiles (Android 13+)

```kotlin
// build.gradle.kts
plugins {
    id("androidx.baselineprofile")
}

dependencies {
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
}
```

### 3. Lazy Initialization

```kotlin
// ✅ BIEN: Lazy initialization
val storage: Storage by lazy { Storage(context) }

// ❌ MAL: Eager initialization
val storage = Storage(context)  // Created immediately
```

### 4. Composable Performance

```kotlin
// ✅ Use derivedStateOf for expensive calculations
val filteredOrders by remember {
    derivedStateOf {
        orders.filter { it.status == OrderStatus.ACTIVE }
    }
}

// ✅ Use key() for list performance
LazyColumn {
    items(items = orders, key = { it.id }) { order ->
        OrderCard(order)
    }
}
```

---

## ♿ Accessibility

### 1. Content Descriptions

```kotlin
Icon(
    imageVector = Icons.Default.ShoppingCart,
    contentDescription = "Carrito de compras"  // ✅ ALWAYS provide
)
```

### 2. Semantic Properties

```kotlin
Button(
    onClick = { },
    modifier = Modifier.semantics {
        contentDescription = "Agregar producto al carrito"
        role = Role.Button
    }
) {
    Text("Agregar")
}
```

### 3. Contrast Ratio (AA/AAA)

```kotlin
// Ensure 4.5:1 contrast for normal text
// Ensure 3:1 contrast for large text (18pt+)
val Primary = Color(0xFF2563EB)  // Blue 600 (AA/AAA compliant)
```

### 4. Dynamic Font Sizes

```kotlin
// ✅ ALWAYS use MaterialTheme.typography (respects user font size)
Text(
    text = "Título",
    style = MaterialTheme.typography.titleLarge  // ✅
)

// ❌ NEVER hardcode fontSize
Text(
    text = "Título",
    fontSize = 22.sp  // ❌ Ignores user preference
)
```

---

## ✅ Checklist Pre-Código

### Fase 1: Configuración (Día 1)

- [ ] Crear proyecto Android con Android Studio
- [ ] Configurar Hilt (build.gradle.kts + @HiltAndroidApp)
- [ ] Configurar Jetpack Compose (BOM + dependencies)
- [ ] Configurar Blumon PAX SDK (armeabi + useLegacyPackaging)
- [ ] Crear estructura de carpetas (core/ + features/)
- [ ] Configurar ProGuard rules
- [ ] **Configurar Lint** (fail on UnusedResources to prevent orphaned files)
- [ ] Configurar Git (.gitignore con local.properties)

### Fase 2: Core Infrastructure (Días 2-3)

- [ ] Implementar SecureStorage (EncryptedSharedPreferences)
- [ ] Implementar Networking (Retrofit + OkHttp + Certificate Pinning)
- [ ] Implementar SocketManager (Socket.IO)
- [ ] Implementar Room Database (offline queue)
- [ ] Crear Hilt modules (NetworkModule, DatabaseModule)
- [ ] Crear AuthContext (venueId, staffId, permissions)

### Fase 3: Design System (Día 4)

- [ ] Crear AvoqadoTheme (Material 3)
- [ ] Definir Color.kt (semantic colors)
- [ ] Definir Typography.kt (text styles)
- [ ] Definir Dimensions.kt (spacing, sizes)
- [ ] Crear AvoqadoButton (estandarizado)
- [ ] Crear AvoqadoTopBar (estandarizado)
- [ ] Crear AvoqadoCard (estandarizado)
- [ ] Crear AvoqadoTextField (estandarizado)
- [ ] Crear AvoqadoScaffold (estandarizado)
- [ ] Crear AvoqadoLoadingIndicator
- [ ] Crear AvoqadoErrorMessage
- [ ] Añadir @Preview a TODOS los componentes

### Fase 4: Feature Auth (Días 5-6)

- [ ] Crear AuthRepository interface (domain)
- [ ] Crear AuthRepositoryImpl (data)
- [ ] Crear LoginWithPinUseCase
- [ ] Crear LoginViewModel + LoginUiState
- [ ] Crear LoginScreen (Composable)
- [ ] Crear PinEntryScreen (Composable)
- [ ] Implementar navegación Auth → Home
- [ ] Escribir LoginViewModelTest (unit test)

### Fase 5: Feature Tables (Días 7-8)

- [ ] Crear Table model (domain)
- [ ] Crear TableRepository interface
- [ ] Crear TableRepositoryImpl
- [ ] Crear GetTablesUseCase
- [ ] Crear TablesViewModel + TablesUiState
- [ ] Crear TablesScreen (Composable)
- [ ] Crear TableCard component
- [ ] Integrar Socket.IO para actualizaciones en tiempo real
- [ ] Escribir TablesViewModelTest

### Fase 6: Feature Orders (Días 9-11)

- [ ] Crear Order, OrderItem models
- [ ] Crear OrderRepository interface
- [ ] Crear OrderRepositoryImpl
- [ ] Crear CreateOrderUseCase
- [ ] Crear GetActiveOrdersUseCase
- [ ] Crear OrdersViewModel + OrdersUiState
- [ ] Crear OrdersScreen (Composable)
- [ ] Crear CreateOrderScreen (Composable)
- [ ] Integrar Socket.IO para "order_updated"
- [ ] Escribir OrdersViewModelTest

### Fase 7: Feature Payment (Días 12-15)

- [ ] Integrar Blumon PAX SDK (BlumonSdkWrapper)
- [ ] Crear CredentialCache (reducir latencia)
- [ ] Crear Payment, Receipt models
- [ ] Crear PaymentRepository interface
- [ ] Crear PaymentRepositoryImpl
- [ ] Crear ProcessPaymentUseCase
- [ ] Crear RecordPaymentUseCase
- [ ] Crear PaymentViewModel + PaymentUiState
- [ ] Crear PaymentScreen (Composable)
- [ ] Crear ReceiptScreen (Composable)
- [ ] Implementar offline queue (Room)
- [ ] Crear PaymentSyncWorker (WorkManager)
- [ ] Escribir PaymentViewModelTest
- [ ] Escribir PaymentFlowTest (integration)

### Fase 8: Feature Menu (Días 16-17)

- [ ] Crear Product, Category models
- [ ] Crear MenuRepository interface
- [ ] Crear MenuRepositoryImpl
- [ ] Crear GetMenuUseCase
- [ ] Crear MenuViewModel + MenuUiState
- [ ] Crear MenuScreen (Composable)
- [ ] Crear ProductCard component
- [ ] Escribir MenuViewModelTest

### Fase 9: Feature Timeclock (Días 18-19)

- [ ] Crear Shift model
- [ ] Crear TimeclockRepository interface
- [ ] Crear TimeclockRepositoryImpl
- [ ] Crear ClockInUseCase, ClockOutUseCase
- [ ] Crear TimeclockViewModel + TimeclockUiState
- [ ] Crear TimeclockScreen (Composable)
- [ ] Escribir TimeclockViewModelTest

### Fase 10: Integration & Testing (Días 20-22)

- [ ] Integración completa de todos los features
- [ ] Escribir integration tests (flows completos)
- [ ] Escribir UI tests (Compose UI Testing)
- [ ] Testing en dispositivo PAX real
- [ ] Testing de offline-first (airplane mode)
- [ ] Testing de Socket.IO (reconexión automática)

### Fase 11: Performance & Accessibility (Días 23-24)

- [ ] Generar Baseline Profile
- [ ] Optimizar startup time (< 2s)
- [ ] Verificar 60fps en todas las pantallas
- [ ] Añadir content descriptions a TODOS los iconos
- [ ] Testing con TalkBack
- [ ] Verificar contrast ratio (AA/AAA)
- [ ] Testing con fuentes dinámicas (150%, 200%)

### Fase 12: Security & Production (Días 25-27)

- [ ] Configurar certificate pinning
- [ ] Configurar ProGuard (minify + shrink)
- [ ] Auditoría de seguridad (no hardcoded secrets)
- [ ] Auditoría de tenant isolation (venueId en TODAS las queries)
- [ ] Testing en producción (staging environment)
- [ ] Generar APK de release
- [ ] Testing final en PAX

### Fase 13: Documentation (Día 28)

- [ ] Actualizar CLAUDE.md
- [ ] Actualizar este GREENFIELD_BLUEPRINT.md
- [ ] Documentar API Contract
- [ ] Documentar Socket.IO events
- [ ] Crear README.md del proyecto

---

## 🎯 Métricas de Éxito

### Performance
- [ ] Startup time < 2 segundos
- [ ] 60fps constante en todas las pantallas
- [ ] Primer pago < 1 segundo (con credential cache)
- [ ] APK release < 20MB

### Quality
- [ ] 100% Jetpack Compose (NO XML)
- [ ] 100% Hilt (NO manual DI)
- [ ] 100% EncryptedSharedPreferences (NO plain SharedPreferences)
- [ ] 100% de componentes con @Preview
- [ ] 100% de strings en strings.xml
- [ ] 100% de colors en MaterialTheme.colorScheme

### Testing
- [ ] Code coverage > 80%
- [ ] Unit tests para TODOS los ViewModels
- [ ] Integration tests para flows críticos
- [ ] UI tests para pantallas principales

### Security
- [ ] Certificate pinning activo
- [ ] ProGuard activo en release
- [ ] 0 secrets hardcoded
- [ ] 100% tenant isolation (venueId en todas las queries)

### Accessibility
- [ ] TalkBack funcional en todas las pantallas
- [ ] Contrast ratio AA/AAA compliant
- [ ] Fuentes dinámicas soportadas

---

## 📚 Referencias

### Documentación Oficial
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Hilt](https://dagger.dev/hilt/)
- [Material Design 3](https://m3.material.io/)
- [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-guide.html)
- [Room](https://developer.android.com/training/data-storage/room)
- [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)

### Avoqado Ecosystem
- [Backend API](https://api.avoqado.io/api-docs) - Swagger docs
- [KOTLIN_AGENT_PROMPT.md](../KOTLIN_AGENT_PROMPT.md) - Ecosystem guide
- [avoqado-server/prisma/schema.prisma](../avoqado-server/prisma/schema.prisma) - Data models

### Security
- [OWASP Mobile Top 10](https://owasp.org/www-project-mobile-top-10/)
- [Android Security Best Practices](https://developer.android.com/topic/security/best-practices)

---

**Última Actualización:** 2025-11-03
**Autor:** Development Team
**Próximo Paso:** Ejecutar Fase 1 del checklist


# Avoqado TPV - Development Context

> **Sistema de punto de venta Android para restaurantes, hoteles, gimnasios y retail**
> **Stack:** Kotlin + Jetpack Compose + Hilt + Blumon PAX SDK

---

## 📚 Quick Reference

- 🚀 **[GREENFIELD BLUEPRINT](./GREENFIELD_BLUEPRINT.md)** - Complete architecture & implementation plan for new TPV
- 🏗️ **Architecture:** Clean Architecture (Presentation → Domain → Data)
- 🎨 **UI:** 100% Jetpack Compose (NO XML)
- 💉 **DI:** Hilt 2.57
- 🔐 **Security:** EncryptedSharedPreferences, Certificate Pinning
- 🌐 **Backend:** Production: `https://api.avoqado.io/api/v1/` Dev: `https://humane-immortal-pika.ngrok-free.app`
- 🔌 **Real-time:** Socket.IO (room-based events)

---

## 🎯 CRITICAL: Development Standards & Anti-Hallucination Protocol

> **MANDATORY**: All code MUST follow world-class Kotlin/Android best practices. NO exceptions unless explicitly documented.

### 1. ZERO TOLERANCE for Bad Practices

#### ❌ NEVER Do This (Immediate Red Flags)
```kotlin
// ❌ Magic numbers
val timeout = 5000

// ❌ God classes / Massive functions
class Manager { /* 2000+ lines */ }

// ❌ Mutable state without StateFlow/MutableState
var isLoading = false

// ❌ Blocking calls on Main thread
suspend fun loadData() {
    val data = database.query() // WRONG if on Main
}

// ❌ Suppress warnings without explanation
@Suppress("UNCHECKED_CAST")

// ❌ Raw types / Platform types
fun process(data: List<Any>)

// ❌ Nullability hacks
val value = data!!.field!!.value!!

// ❌ Exception swallowing
try { riskyOperation() } catch (e: Exception) { }

// ❌ String concatenation for paths
val path = "https://api.com" + "/endpoint"

// ❌ Manual threading
Thread { doWork() }.start()
```

#### ✅ ALWAYS Do This (Required Standards)

```kotlin
// ✅ Named constants
private const val NETWORK_TIMEOUT_MS = 5000L
private const val MAX_RETRY_ATTEMPTS = 3

// ✅ Single Responsibility Principle
class PaymentProcessor(private val repository: PaymentRepository) {
    suspend fun processPayment(request: PaymentRequest): Result<Payment>
}

// ✅ Immutable state with Kotlin Flows
private val _state = MutableStateFlow<UiState>(UiState.Idle)
val state: StateFlow<UiState> = _state.asStateFlow()

// ✅ Proper coroutine dispatchers
suspend fun loadData() = withContext(Dispatchers.IO) {
    database.query()
}

// ✅ Explicit nullability
fun process(data: List<String>?)

// ✅ Safe null handling
val value = data?.field?.value ?: defaultValue

// ✅ Proper error handling
try {
    riskyOperation()
} catch (e: NetworkException) {
    Timber.e(e, "Network error during operation")
    emit(Result.Error(e.toUserFriendlyMessage()))
}

// ✅ Type-safe builders
val url = buildString {
    append(BASE_URL)
    append("/api/v1")
    append("/endpoint")
}

// ✅ Structured concurrency
viewModelScope.launch {
    doWork()
}
```

---

### 2. Kotlin Best Practices (World-Class Standards)

#### Naming Conventions
```kotlin
// ✅ Classes: PascalCase
class PaymentViewModel

// ✅ Functions: camelCase (verb-based)
fun processPayment()
fun calculateTotal()
fun isValid()

// ✅ Properties: camelCase
val totalAmount: BigDecimal
val isProcessing: Boolean

// ✅ Constants: SCREAMING_SNAKE_CASE
const val MAX_RETRY_ATTEMPTS = 3
const val DEFAULT_TIMEOUT_MS = 30_000L

// ✅ Private members: prefix with underscore for backing properties
private val _state = MutableStateFlow<UiState>(UiState.Idle)
val state: StateFlow<UiState> = _state.asStateFlow()

// ✅ Sealed classes for state
sealed class PaymentState {
    data object Idle : PaymentState()
    data class Loading(val progress: Float) : PaymentState()
    data class Success(val result: TransactionResult) : PaymentState()
    data class Error(val error: PaymentError) : PaymentState()
}
```

#### Coroutines & Flow (MANDATORY)
```kotlin
// ✅ ViewModel scope for UI operations
viewModelScope.launch {
    processPayment()
}

// ✅ IO dispatcher for network/database
suspend fun fetchData() = withContext(Dispatchers.IO) {
    api.getData()
}

// ✅ StateFlow for UI state (not LiveData)
private val _uiState = MutableStateFlow(UiState.Idle)
val uiState: StateFlow<UiState> = _uiState.asStateFlow()

// ✅ SharedFlow for one-time events
private val _events = Channel<PaymentEvent>(Channel.BUFFERED)
val events = _events.receiveAsFlow()

// ✅ Proper error handling in flows
flow {
    emit(Resource.Loading)
    try {
        val data = api.getData()
        emit(Resource.Success(data))
    } catch (e: Exception) {
        Timber.e(e, "Failed to fetch data")
        emit(Resource.Error(e.message))
    }
}
```

#### Dependency Injection (Hilt)
```kotlin
// ✅ Constructor injection (preferred)
@HiltViewModel
class PaymentViewModel @Inject constructor(
    private val processPaymentUseCase: ProcessPaymentUseCase,
    private val repository: PaymentRepository
) : ViewModel()

// ✅ Module provides dependencies
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()
}

// ❌ NEVER use manual singletons (anti-pattern)
object PaymentManager { /* BAD */ }
```

#### Jetpack Compose Best Practices
```kotlin
// ✅ Stateless composables
@Composable
fun PaymentButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text("Pay")
    }
}

// ✅ Hoist state to caller
@Composable
fun PaymentScreen(
    viewModel: PaymentViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    PaymentContent(
        state = state,
        onPayClick = viewModel::processPayment
    )
}

// ✅ ALWAYS add @Preview
@Preview(showBackground = true)
@Composable
private fun PaymentButtonPreview() {
    MaterialTheme {
        PaymentButton(onClick = {}, enabled = true)
    }
}

// ✅ Use semantic colors (not hardcoded)
Text(color = MaterialTheme.colorScheme.primary)

// ❌ NEVER hardcode colors
Text(color = Color(0xFF2563EB)) // WRONG
```

#### Error Handling (CRITICAL - MANDATORY FOR ALL IMPLEMENTATIONS)

> **NON-NEGOTIABLE**: Every feature MUST have perfect, user-friendly error handling. Technical errors are for logs, user messages are for humans.

**Philosophy**: Errors are part of the user experience. A well-handled error builds trust; a cryptic error loses customers.

##### ❌ NEVER Show Technical Errors to Users

```kotlin
// ❌ WRONG: Exposing internal SDK errors
catch (e: Exception) {
    _state.value = State.Error("Error: $e")
    // User sees: "StartCtlssTransFailure$ReadingContactlessFailure@efcd17c"
}

// ❌ WRONG: Generic unhelpful messages
catch (e: Exception) {
    _state.value = State.Error("Something went wrong")
    // User doesn't know WHAT or HOW to fix
}

// ❌ WRONG: Swallowing errors silently
catch (e: Exception) {
    // Nothing - user has no idea payment failed!
}
```

##### ✅ ALWAYS Translate Errors to User-Friendly Messages

```kotlin
// ✅ CORRECT: Translate SDK/API errors to actionable user messages
if (result.isLeft) {
    val error = result.leftValue()
    Timber.e("❌ [TECHNICAL] Contactless failed: $error")  // Log technical details

    // Translate to user-friendly message
    val userMessage = when {
        error.toString().contains("ReadingContactlessFailure", ignoreCase = true) -> {
            "La tarjeta se retiró demasiado rápido.\n\n" +
            "Por favor, mantenga la tarjeta sobre el lector hasta que " +
            "aparezca el mensaje de confirmación."
        }
        error.toString().contains("Timeout", ignoreCase = true) -> {
            "Tiempo de espera agotado.\n\n" +
            "Por favor, mantenga la tarjeta cerca del lector durante toda la transacción."
        }
        error.toString().contains("Collision", ignoreCase = true) -> {
            "Se detectaron múltiples tarjetas.\n\n" +
            "Por favor, presente solo una tarjeta a la vez."
        }
        error.toString().contains("NetworkException", ignoreCase = true) -> {
            "No se pudo conectar al servidor.\n\n" +
            "Verifique su conexión a internet e intente nuevamente."
        }
        else -> {
            "Error leyendo tarjeta contactless.\n\n" +
            "Intente nuevamente o inserte la tarjeta en el chip."
        }
    }

    _state.value = State.Error(userMessage)  // Show friendly message
}
```

##### Error Message Checklist (Every Error MUST Have)

1. **✅ What happened** (in simple terms)
   - ✅ "La tarjeta se retiró demasiado rápido"
   - ❌ "ReadingContactlessFailure"

2. **✅ Why it happened** (if helpful)
   - ✅ "El servidor está en mantenimiento"
   - ❌ "HTTP 503 Service Unavailable"

3. **✅ How to fix it** (actionable steps)
   - ✅ "Por favor, mantenga la tarjeta sobre el lector hasta que aparezca la confirmación"
   - ❌ "Error code: 0x8001"

4. **✅ Alternative action** (if available)
   - ✅ "Intente nuevamente o inserte la tarjeta en el chip"
   - ❌ "Payment failed"

##### Real-World Examples from Avoqado TPV

**Example 1: Contactless Card Removed Too Quickly**
```kotlin
// SDK Error: com.pax.api.PiccException: No response timeout
// User Message: "La tarjeta se retiró demasiado rápido.\n\n
//                Por favor, mantenga la tarjeta sobre el lector hasta
//                que aparezca el mensaje de confirmación."
```

**Example 2: Network Timeout**
```kotlin
// SDK Error: java.net.SocketTimeoutException: timeout
// User Message: "No se pudo conectar al servidor.\n\n
//                Verifique su conexión a internet e intente nuevamente."
```

**Example 3: Payment Declined by Bank**
```kotlin
// Momentum Error: {"code":"NA_002","description":"NO AUTORIZADO"}
// User Message: "Pago rechazado por el banco.\n\n
//                Por favor, verifique con su banco o use otro método de pago."
```

##### Logging Strategy (Dual-Layer)

```kotlin
// ✅ ALWAYS log technical details for debugging
Timber.e(e, "❌ [TECHNICAL] Payment failed with SDK error: ${error.javaClass.simpleName}")

// ✅ THEN show user-friendly message
_state.value = State.Error(userFriendlyMessage)
```

**Benefits:**
- Developers get full technical context in logs (Logcat, Crashlytics)
- Users get clear, actionable messages in UI
- Support team can help users without confusing them with tech jargon

##### Testing Error Messages

Before merging ANY code with error handling:

1. **✅ Trigger the error manually** (remove card, disable network, etc.)
2. **✅ Read the message as a non-technical user** - Is it clear?
3. **✅ Follow the instructions** - Can you actually fix it?
4. **✅ Check logs** - Is technical info preserved for debugging?

If ANY of these fail → FIX the error message.

##### Common Error Patterns

| Error Type | Bad Message | Good Message |
|-----------|-------------|--------------|
| **Network** | "IOException" | "No se pudo conectar. Verifique su internet." |
| **Timeout** | "SocketTimeoutException" | "La operación tardó demasiado. Intente nuevamente." |
| **SDK Error** | "ReadingContactlessFailure@efcd17c" | "La tarjeta se retiró muy rápido. Manténgala hasta la confirmación." |
| **Validation** | "Invalid input" | "El monto debe ser mayor a $0.01" |
| **Permission** | "SecurityException" | "La app necesita permiso de ubicación para procesar pagos." |
| **Database** | "SQLiteException" | "Error guardando datos. Reinicie la app." |

---

#### Responsive UI Design for TPV Devices (MANDATORY)

> **CRITICAL**: All UI screens MUST be responsive and work on small POS devices (480x800 to 1280x800) WITHOUT scrolling.

**Philosophy**: Professional POS systems (Square Terminal, Toast POS, Clover) NEVER require scrolling on core workflows (login, payment, checkout). Everything must be visible at once.

**Common TPV Device Resolutions**:
| Device | Resolution | Density | Use Case |
|--------|------------|---------|----------|
| **PAX A920** | 1280x720 dp | 320 dpi | Most common |
| **PAX A80** | 1024x600 dp | 240 dpi | Budget option |
| **Sunmi T2s** | 1280x800 dp | 213 dpi | Alternative |
| **Generic 10"** | 1280x800 dp | 160 dpi | Testing baseline |

##### ❌ NEVER Use Fixed Sizes for Vertical Layouts

```kotlin
// ❌ WRONG: Hardcoded sizes will overflow on small screens
Column {
    Image(modifier = Modifier.size(120.dp))
    Spacer(modifier = Modifier.height(48.dp))
    Text("Title")
    Spacer(modifier = Modifier.height(48.dp))
    PinPad() // This will be cut off on PAX A80!
}
```

##### ✅ ALWAYS Use ResponsiveScaffold (Reusable Component)

**Component**: `core/presentation/components/ResponsiveScaffold.kt`

**Philosophy**: Don't repeat BoxWithConstraints logic in every screen. Use a centralized, tested component.

```kotlin
// ✅ CORRECT: Use ResponsiveScaffold for all screens
@Composable
fun LoginScreen() {
    ResponsiveScaffold(
        scrollable = false,  // Workflow screen - everything must fit
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val sizes = LocalResponsiveSizes.current

        // All sizes automatically adjust based on screen
        Image(modifier = Modifier.size(sizes.logoSize))
        Spacer(modifier = Modifier.height(sizes.spacingMedium))
        Text("Ingresa tu PIN")
        Spacer(modifier = Modifier.height(sizes.spacingMedium))
        PinPad() // Guaranteed to fit!
    }
}
```

**Available size tokens from `LocalResponsiveSizes.current`:**

| Token | Small (<600dp) | Medium (600-700dp) | Large (>700dp) | Usage |
|-------|----------------|---------------------|----------------|-------|
| `logoSize` | 60.dp | 80.dp | 100.dp | Venue logos, app icons |
| `iconSizeSmall` | 16.dp | 20.dp | 24.dp | Small UI icons |
| `iconSizeMedium` | 24.dp | 32.dp | 40.dp | Action buttons |
| `iconSizeLarge` | 48.dp | 56.dp | 64.dp | Hero icons |
| `spacingSmall` | 8.dp | 12.dp | 16.dp | Tight spacing |
| `spacingMedium` | 16.dp | 24.dp | 32.dp | Standard spacing |
| `spacingLarge` | 24.dp | 32.dp | 48.dp | Section dividers |
| `paddingScreen` | 16.dp | 20.dp | 24.dp | Screen edges |

**When to set `scrollable = true`:**
- ✅ Long lists (products, orders, history)
- ✅ Settings screens with many options
- ✅ Forms longer than screen height
- ❌ Login, PIN, payment screens (must fit without scroll)

**Example (scrollable screen):**
```kotlin
ResponsiveScaffold(scrollable = true) {
    val sizes = LocalResponsiveSizes.current

    repeat(20) {
        ProductCard()
        Spacer(modifier = Modifier.height(sizes.spacingSmall))
    }
}
```

**Real implementation**: LoginScreen.kt:98-158 uses ResponsiveScaffold.

##### Testing Responsive Layouts

**MANDATORY checklist before committing any screen:**

1. ✅ **Wrap screen with ResponsiveScaffold** (not raw BoxWithConstraints)
2. ✅ **Use `LocalResponsiveSizes.current`** for all dynamic sizing
3. ✅ **Test in Android Studio Preview** with multiple device configs:
   ```kotlin
   @Preview(name = "Small - PAX A80", device = "spec:width=1024dp,height=600dp")
   @Preview(name = "Medium - PAX A920", device = "spec:width=1280dp,height=720dp")
   @Preview(name = "Large - 10 inch", device = "spec:width=1280dp,height=800dp")
   @Composable
   fun MyScreenPreview() { MyScreen() }
   ```
4. ✅ **Verify no scroll on workflow screens** (login, payment, checkout)
5. ✅ **Check spacing ratios** - Elements should look balanced across all sizes

**Rule of thumb**: If you need to scroll on a **workflow screen** (login, payment, checkout), the layout is broken.

**Acceptable scroll areas**:
- ✅ Product lists
- ✅ Order history
- ✅ Settings pages
- ❌ Login screen
- ❌ PIN pad screen
- ❌ Payment confirmation

---

#### When to Create Reusable Components (Square/Toast Pattern)

> **CRITICAL**: Always think "Will I need this UI pattern in more than one place?" If yes, create a component in the Design System.

**✅ ALWAYS Create Reusable Component When:**
- Pattern used in 2+ screens (Login + Activation = component)
- Critical UI pattern (loading, errors, dialogs, empty states)
- Consistent branding needed (buttons, cards, chips)
- Complex UI with multiple states

**❌ NEVER Duplicate UI Code:**
```kotlin
// ❌ WRONG: Inline overlay in LoginScreen
if (state is Loading) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(0.6f))) {
        Card { CircularProgressIndicator() }
    }
}

// ❌ WRONG: Same code duplicated in PaymentScreen
if (state is Processing) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(0.6f))) {
        Card { CircularProgressIndicator() }
    }
}
```

**✅ CORRECT: Reusable Component in Design System:**
```kotlin
// ✅ core/presentation/components/AvoqadoLoadingIndicator.kt
@Composable
fun AvoqadoLoadingOverlay(
    message: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Card { /* ... loading UI ... */ }
    }
}

// ✅ LoginScreen.kt (1 line instead of 30+)
if (state is Loading) {
    AvoqadoLoadingOverlay(message = "Autenticando...")
}

// ✅ PaymentScreen.kt (reuses same component)
if (state is Processing) {
    AvoqadoLoadingOverlay(message = "Procesando pago...")
}

// ✅ ActivationScreen.kt (reuses same component)
if (state is Activating) {
    AvoqadoLoadingOverlay(message = "Activando terminal...")
}
```

**Decision Tree:**
```
┌─────────────────────────────────────────┐
│ Do I need this UI in 2+ places?        │
└─────────────────────────────────────────┘
                │
        ┌───────┴───────┐
        │               │
       YES             NO
        │               │
        v               v
┌───────────────┐   ┌──────────────┐
│ Create in     │   │ Is it a      │
│ Design System │   │ common       │
│ (core/        │   │ pattern?     │
│ components/)  │   │ (loading,    │
└───────────────┘   │ error, etc.) │
                    └──────────────┘
                            │
                    ┌───────┴───────┐
                    │               │
                   YES             NO
                    │               │
                    v               v
            ┌───────────────┐   ┌──────────────┐
            │ Create in     │   │ Keep inline  │
            │ Design System │   │ (1-time use) │
            │ anyway        │   └──────────────┘
            │ (future-proof)│
            └───────────────┘
```

**Examples from Avoqado TPV:**
| UI Pattern | Component | Used In |
|-----------|-----------|---------|
| Loading overlay | `AvoqadoLoadingOverlay` | Login, Payment, Activation |
| Text input | `AvoqadoTextField` | Forms across app |
| Confirmation dialog | `AvoqadoDialog` | Delete, Logout, Confirm actions |
| Empty state | `AvoqadoEmptyState` | Empty lists, no data screens |
| Error message | `AvoqadoError` (TBD) | All screens with errors |

---

### 3. Architecture Patterns (Clean Architecture)

```
┌─────────────────────────────────────────────────┐
│ PRESENTATION LAYER (UI)                         │
│ • Composables (stateless)                       │
│ • ViewModels (state management)                 │
│ • StateFlow/SharedFlow                          │
└─────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────┐
│ DOMAIN LAYER (Business Logic)                   │
│ • UseCases (single responsibility)              │
│ • Repository Interfaces                         │
│ • Domain Models (data classes)                  │
└─────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────┐
│ DATA LAYER (Infrastructure)                     │
│ • Repository Implementations                    │
│ • Data Sources (API, Database, SDK)             │
│ • DTOs → Domain Mappers                         │
└─────────────────────────────────────────────────┘
```

#### Example: Clean Architecture in Practice
```kotlin
// ✅ PRESENTATION: ViewModel
@HiltViewModel
class PaymentViewModel @Inject constructor(
    private val processPaymentUseCase: ProcessPaymentUseCase
) : ViewModel() {
    private val _state = MutableStateFlow<PaymentState>(PaymentState.Idle)
    val state: StateFlow<PaymentState> = _state.asStateFlow()

    fun processPayment(request: PaymentRequest) {
        viewModelScope.launch {
            _state.value = PaymentState.Loading
            processPaymentUseCase(request)
                .onSuccess { result -> _state.value = PaymentState.Success(result) }
                .onFailure { error -> _state.value = PaymentState.Error(error) }
        }
    }
}

// ✅ DOMAIN: UseCase
class ProcessPaymentUseCase @Inject constructor(
    private val paymentRepository: PaymentRepository
) {
    suspend operator fun invoke(request: PaymentRequest): Result<TransactionResult> {
        return try {
            val result = paymentRepository.processPayment(request)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

// ✅ DATA: Repository Implementation
class PaymentRepositoryImpl @Inject constructor(
    private val blumonSdk: BlumonPaySDK,
    private val api: PaymentApi
) : PaymentRepository {
    override suspend fun processPayment(request: PaymentRequest): TransactionResult {
        return withContext(Dispatchers.IO) {
            // SDK integration
            val sdkResult = blumonSdk.processPayment(request.toSdkRequest())

            // Backend sync
            api.recordPayment(sdkResult.toDto())

            // Map to domain
            sdkResult.toDomain()
        }
    }
}
```

---

### 4. When No Ideal Solution Exists: Workaround Protocol

**IF** you cannot implement with best practices (e.g., SDK limitations, API constraints):

#### ✅ REQUIRED Documentation Format
```kotlin
/**
 * ⚠️ WORKAROUND: Using manual listener initialization
 *
 * **Problem:**
 * Blumon SDK listeners don't follow standard Kotlin Flow patterns.
 * They expose `getEventPinDialogStateFlow` instead of being callable.
 *
 * **Ideal Solution:**
 * SDK should provide: `suspend operator fun invoke(): Flow<EventPinDialogState>`
 *
 * **Current Implementation:**
 * Direct property access: `listenForEventPinDialogState.getEventPinDialogStateFlow`
 *
 * **Trade-offs:**
 * - ❌ Less idiomatic Kotlin
 * - ✅ Works reliably with current SDK version
 *
 * **Future Action:**
 * Contact Blumon to update SDK API in next release.
 *
 * @see [SDK Issue #123](https://github.com/blumon/sdk/issues/123)
 */
private fun observeSDKListeners() {
    viewModelScope.launch {
        listenForEventPinDialogState.getEventPinDialogStateFlow.collect { state ->
            handlePinStateChange(state)
        }
    }
}
```

#### Decision Tree
```
┌─────────────────────────────────────────┐
│ Can I implement with best practices?   │
└─────────────────────────────────────────┘
                │
        ┌───────┴───────┐
        │               │
       YES             NO
        │               │
        v               v
┌───────────┐   ┌──────────────┐
│ Implement │   │ Document WHY │
│ cleanly   │   │ + Trade-offs │
└───────────┘   └──────────────┘
                        │
                        v
        ┌───────────────────────────────┐
        │ ASK USER for approval:        │
        │                               │
        │ "⚠️ Best practice solution    │
        │ not possible due to [reason]. │
        │                               │
        │ Workaround: [description]     │
        │                               │
        │ Trade-offs:                   │
        │ - Pro: [benefits]             │
        │ - Con: [drawbacks]            │
        │                               │
        │ Proceed or keep trying?"      │
        └───────────────────────────────┘
```

---

### 5. Code Review Checklist (Self-Check Before Committing)

```markdown
## Before Every Commit:

### Architecture
- [ ] Follows Clean Architecture (Presentation → Domain → Data)
- [ ] Single Responsibility Principle applied
- [ ] Dependency injection via Hilt (no manual singletons)
- [ ] No business logic in UI layer

### Kotlin Style
- [ ] No `!!` (null assertion) without try-catch
- [ ] Proper coroutine scopes (viewModelScope, lifecycleScope)
- [ ] StateFlow for state, Channel for events
- [ ] Named constants for magic numbers
- [ ] Sealed classes for exhaustive states

### Compose
- [ ] Stateless composables with hoisted state
- [ ] @Preview annotations present
- [ ] MaterialTheme colors (no hardcoded)
- [ ] Proper modifiers with `= Modifier` default

### Error Handling
- [ ] Try-catch with specific exceptions
- [ ] Timber logging with context
- [ ] User-friendly error messages
- [ ] No swallowed exceptions

### Testing
- [ ] Unit tests for ViewModels
- [ ] Integration tests for critical paths
- [ ] Edge cases covered

### Documentation
- [ ] KDoc for public APIs
- [ ] Inline comments for complex logic
- [ ] Workarounds clearly marked with ⚠️
- [ ] TODO items tracked

### Performance
- [ ] No blocking calls on Main thread
- [ ] Proper Dispatchers.IO for I/O
- [ ] Flow cancellation handled
- [ ] Memory leaks checked
```

---

### 6. Common Anti-Patterns to AVOID

| ❌ Anti-Pattern | ✅ Best Practice |
|----------------|------------------|
| `object PaymentManager` | `@Inject constructor(private val repository: PaymentRepository)` |
| `var isLoading = false` | `private val _state = MutableStateFlow(UiState.Idle)` |
| `Thread { }.start()` | `viewModelScope.launch` |
| `GlobalScope.launch` | `viewModelScope.launch` or `lifecycleScope.launch` |
| `LiveData` in new code | `StateFlow` |
| `data?.field!!.value!!` | `data?.field?.value ?: default` |
| Hardcoded strings | `stringResource(R.string.key)` |
| `Color(0xFF...)` | `MaterialTheme.colorScheme.primary` |
| God classes (1000+ lines) | Split into focused classes |
| Manual DI | Hilt `@Inject` |

---

### 7. Performance Guidelines

```kotlin
// ✅ Lazy initialization
val expensiveObject by lazy { HeavyCalculation() }

// ✅ Remember in Compose
@Composable
fun Screen() {
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
}

// ✅ Derived state (not manual updates)
val isEnabled by remember {
    derivedStateOf { name.isNotBlank() && amount > 0 }
}

// ✅ List keys for performance
LazyColumn {
    items(items, key = { it.id }) { item ->
        ItemRow(item)
    }
}

// ✅ Background processing
suspend fun processLargeData() = withContext(Dispatchers.Default) {
    heavyComputation()
}
```

---

### 8. Security Checklist

```kotlin
// ✅ Input validation
fun validateAmount(input: String): BigDecimal? {
    return input.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
}

// ✅ Encrypted storage
val encryptedPrefs = EncryptedSharedPreferences.create(...)

// ✅ Certificate pinning
val certificatePinner = CertificatePinner.Builder()
    .add("api.avoqado.io", "sha256/...")
    .build()

// ✅ No secrets in code
buildConfigField("String", "API_KEY", "\"${System.getenv("AVOQADO_API_KEY")}\"")

// ✅ Tenant isolation
fun getOrders(venueId: String): Flow<List<Order>>

// ❌ NEVER log sensitive data
Timber.d("Card number: ${card.number}") // WRONG!
```

---

## 🚀 Quick Start

### 1. Clone & Setup
```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-tpv
./gradlew build
```

### 2. Environment Variables
Create `local.properties`:
```properties
AVOQADO_API_KEY=your_api_key_here
BLUMON_MERCHANT_ID=your_merchant_id
```

### 3. Run
```bash
./gradlew installDebug
adb shell am start -n com.jaac.avoqado_tpv/.MainActivity
```

---

## 🏗️ Architecture Overview

### Module Structure
```
app/
├── core/                          # Shared infrastructure
│   ├── data/
│   │   ├── network/              # Retrofit, Socket.IO
│   │   └── local/                # EncryptedSharedPreferences
│   ├── domain/                   # Core models & interfaces
│   ├── di/                       # Hilt modules
│   └── presentation/             # Shared Composables
├── features/                     # Feature modules
│   ├── authorization/            # Login, PIN authentication
│   ├── payment/                  # Blumon PAX integration
│   ├── management/               # Tables, orders
│   ├── menu/                     # Product catalog
│   ├── cart/                     # Shopping cart
│   └── timeclock/                # Shift management
└── AvoqadoApp.kt                 # @HiltAndroidApp
```

### Data Flow
```
UI (Compose) → ViewModel (StateFlow) → UseCase → Repository → API/Database
```

---

## 🔐 Security Rules (NON-NEGOTIABLE)

### 1. Encrypted Storage
```kotlin
// ✅ ALWAYS use EncryptedSharedPreferences
val masterKey = MasterKey.Builder(context)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .build()

val encryptedPrefs = EncryptedSharedPreferences.create(
    context, "secure_session", masterKey,
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)
```

### 2. Tenant Isolation
```kotlin
// ✅ ALWAYS filter by venueId
val orders = orderRepository.getOrders(venueId = authContext.venueId)

// ❌ NEVER fetch without tenant filter (security risk!)
val orders = orderRepository.getAllOrders() // WRONG!
```

### 3. No Hardcoded Secrets
```kotlin
// ❌ WRONG
const val API_KEY = "sk_live_abc123"

// ✅ CORRECT
buildConfigField("String", "API_KEY", "\"${System.getenv("AVOQADO_API_KEY")}\"")
```

---

## 🎨 UI/UX Patterns

### Composable Structure
```kotlin
@Composable
fun FeatureScreen(
    viewModel: FeatureViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { FeatureTopBar() },
        content = { padding ->
            when (val currentState = state) {
                is State.Loading -> LoadingIndicator()
                is State.Success -> SuccessContent(currentState.data)
                is State.Error -> ErrorMessage(currentState.message)
            }
        }
    )
}

// ✅ MUST HAVE Preview
@Preview
@Composable
private fun FeatureScreenPreview() {
    AvoqadoTheme {
        FeatureScreen()
    }
}
```

### Theme Usage
```kotlin
// ✅ ALWAYS use semantic colors
Text(color = MaterialTheme.colorScheme.primary)
Box(modifier = Modifier.background(MaterialTheme.colorScheme.surface))

// ❌ NEVER hardcode colors
Text(color = Color(0xFF2563EB)) // WRONG!
```

### Dark Theme (Avoqado Dashboard Web Design)

**⚠️ IMPORTANT: The app ALWAYS uses Dark Mode by default** (no system theme detection)

Professional POS systems like Square Terminal and Toast POS maintain consistent dark UI for restaurant environments. The app uses the EXACT same dark theme as the Avoqado Web Dashboard.

Source: `avoqado-web-dashboard/src/index.css` (.dark theme)

#### Color Palette

| Token | OKLCH | HEX | Usage |
|-------|-------|-----|-------|
| **background** | oklch(0.145 0 0) | `#1C1C1C` | Main background (deep charcoal) |
| **foreground** | oklch(0.985 0 0) | `#FAFAFA` | Primary text (soft white) |
| **card** | oklch(0.205 0 0) | `#2A2A2A` | Cards & elevated surfaces |
| **primary** | oklch(0.922 0 0) | `#E8E8E8` | Primary buttons & accents |
| **secondary** | oklch(0.269 0 0) | `#383838` | Secondary elements (muted gray) |
| **muted** | oklch(0.269 0 0) | `#383838` | Muted text & disabled states |
| **accent** | oklch(0.371 0 0) | `#505050` | Accents & hover states |
| **destructive** | oklch(0.704 0.191 22.216) | `#EB5757` | Errors & destructive actions |
| **surface** | oklch(0.2 0 0) | `#282828` | Surface layers |
| **border** | oklch(1 0 0 / 10%) | `#1AFFFFFF` | Borders (10% white) |

#### Visual Comparison

```
┌─────────────────────────────────────────┐
│  Avoqado Web Dashboard (Dark)           │
│  ┌───────────────────────────────────┐  │
│  │ #1C1C1C  Deep charcoal background │  │
│  │ #2A2A2A  Card surface             │  │
│  │ #FAFAFA  Soft white text          │  │
│  │ #E8E8E8  Primary (light gray)     │  │
│  │ #EB5757  Soft red (destructive)   │  │
│  └───────────────────────────────────┘  │
│                                          │
│  Avoqado TPV Android (Dark) ← MATCHES!  │
│  ┌───────────────────────────────────┐  │
│  │ DarkBackground = #1C1C1C          │  │
│  │ DarkSurface = #2A2A2A             │  │
│  │ DarkOnSurface = #FAFAFA           │  │
│  │ DarkPrimary = #E8E8E8             │  │
│  │ DarkError = #EB5757               │  │
│  └───────────────────────────────────┘  │
└─────────────────────────────────────────┘
```

#### Implementation Details

**File:** `app/src/main/java/com/jaac/avoqado_tpv/core/presentation/theme/Color.kt:135-188`

```kotlin
// Background & Surface (deep charcoal matching dashboard)
val DarkBackground = Color(0xFF1C1C1C) // oklch(0.145 0 0)
val DarkOnBackground = Color(0xFFFAFAFA) // oklch(0.985 0 0)
val DarkSurface = Color(0xFF2A2A2A) // oklch(0.205 0 0)
val DarkOnSurface = Color(0xFFFAFAFA) // oklch(0.985 0 0)

// Primary (light gray for text/buttons in dark mode)
val DarkPrimary = Color(0xFFE8E8E8) // oklch(0.922 0 0)
val DarkOnPrimary = Color(0xFF2A2A2A) // oklch(0.205 0 0)

// Error/Destructive (soft red from dashboard)
val DarkError = Color(0xFFEB5757) // oklch(0.704 0.191 22.216)
val DarkOnError = Color(0xFFFAFAFA) // oklch(0.985 0 0)
```

#### Why This Matters

1. **Brand Consistency**: Users experience the same visual design across web and mobile
2. **OKLCH Color Space**: Modern color system with better perceptual uniformity than RGB/HSL
3. **Accessibility**: All color combinations meet WCAG AA contrast requirements
4. **Professional Look**: Matches Square Terminal, Toast POS, Stripe Terminal aesthetics

#### Usage in Components

```kotlin
// ✅ Backgrounds
Scaffold(
    containerColor = MaterialTheme.colorScheme.background // #1C1C1C
)

// ✅ Cards
Card(
    colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface // #2A2A2A
    )
)

// ✅ Text
Text(
    text = "Welcome",
    color = MaterialTheme.colorScheme.onSurface // #FAFAFA
)

// ✅ Primary buttons
Button(
    colors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary // #E8E8E8
    )
)

// ✅ Error states
Text(
    text = "Error message",
    color = MaterialTheme.colorScheme.error // #EB5757
)
```

---

## 🔌 Backend Integration

### REST API Endpoints
```kotlin
interface AvoqadoService {
    // Authentication
    @POST("tpv/venues/{venueId}/auth/login-pin")
    suspend fun loginWithPin(
        @Path("venueId") venueId: String,
        @Body request: PinLoginRequest
    ): Response<AuthResponse>

    // Orders
    @GET("tpv/venues/{venueId}/orders")
    suspend fun getOrders(@Path("venueId") venueId: String): Response<List<Order>>

    @POST("tpv/venues/{venueId}/orders")
    suspend fun createOrder(
        @Path("venueId") venueId: String,
        @Body order: CreateOrderRequest
    ): Response<Order>

    // Payments
    @POST("tpv/venues/{venueId}/orders/{orderId}")
    suspend fun recordPayment(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body payment: PaymentRequest
    ): Response<Payment>
}
```

### Socket.IO (Real-time)
```kotlin
// Join venue room
socket.emit("join_room", JSONObject().apply {
    put("roomType", "venue")
    put("venueId", venueId)
})

// Listen to events
socket.on("order_updated") { args ->
    val data = args[0] as JSONObject
    viewModelScope.launch {
        _events.emit(OrderEvent.Updated(data))
    }
}
```

### Rate Limiting

> **CRITICAL**: Backend must configure environment-specific rate limits. Development environments need higher limits to prevent blocking during testing.

#### Production vs Development Limits

| Endpoint | Production | Development | Reason |
|----------|-----------|-------------|---------|
| **PIN Login** | 10 attempts / 15 min | 100 attempts / 1 min | DEV needs rapid testing without lockouts |
| **Activation** | 5 attempts / 15 min | 50 attempts / 1 min | Multiple device testing in DEV |
| **API Calls** | 1000 req / hour | 10,000 req / hour | Load testing and development |
| **Refresh Token** | 20 attempts / hour | 200 attempts / hour | Session testing |

#### Backend Configuration (Recommended)

```typescript
// Backend: rate-limiter.config.ts
const isProd = process.env.NODE_ENV === 'production';

export const rateLimits = {
  pinLogin: {
    windowMs: isProd ? 15 * 60 * 1000 : 1 * 60 * 1000, // 15 min : 1 min
    max: isProd ? 10 : 100, // 10 : 100 attempts
    message: 'Too many login attempts. Please try again later.'
  },
  activation: {
    windowMs: isProd ? 15 * 60 * 1000 : 1 * 60 * 1000,
    max: isProd ? 5 : 50,
    message: 'Too many activation attempts.'
  },
  api: {
    windowMs: 60 * 60 * 1000, // 1 hour
    max: isProd ? 1000 : 10000
  }
};
```

#### Android Error Handling

When 429 (Rate Limit Exceeded) is received, the app shows helpful developer-friendly messages:

```kotlin
// AuthRepository.kt:110-115
429 -> {
    Timber.w("⚠️ Rate limit exceeded (429) - Backend should have higher limits in DEV")
    "Demasiados intentos. Por favor espera un momento e intenta nuevamente.\n\n" +
    "ℹ️ Si estás en desarrollo, el backend debe configurar rate limits más altos para DEV."
}
```

**User Experience:**
- **Production**: Protects against brute force attacks (10 attempts = ~40 PIN guesses max)
- **Development**: Allows rapid testing without frustration (100 attempts = unlimited realistic testing)

#### Testing Rate Limits

```bash
# Test rate limit in development (should NOT trigger at normal testing pace)
for i in {1..20}; do
  curl -X POST "http://localhost:3000/api/v1/tpv/venues/venue-123/auth/login-pin" \
    -H "Content-Type: application/json" \
    -d '{"pin": "1234", "serialNumber": "test-serial"}'
  sleep 1
done

# Production: Should trigger 429 after 10 attempts
# Development: Should NOT trigger 429 even after 20 attempts (limit is 100)
```

#### Action Items for Backend Team

1. **Implement environment-based rate limiting** (see config above)
2. **Add rate limit headers** to responses:
   ```
   X-RateLimit-Limit: 100
   X-RateLimit-Remaining: 95
   X-RateLimit-Reset: 1640000000
   ```
3. **Log rate limit hits** for monitoring:
   ```typescript
   logger.warn('Rate limit exceeded', {
     ip: req.ip,
     endpoint: req.path,
     environment: process.env.NODE_ENV
   });
   ```

---

## 💳 Payment Integration (Blumon PAX SDK)

### Critical Configuration
```kotlin
android {
    defaultConfig {
        ndk {
            abiFilters.clear()
            abiFilters.add("armeabi")  // ⚠️ CRITICAL: Blumon requires armeabi ONLY
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true  // ⚠️ REQUIRED for native libraries
        }
    }
}
```

### Payment Flow
```kotlin
// 1. Initialize SDK
AppManager.init(context)

// 2. Process payment
val payment = PaymentRequest(
    amount = 50000,  // cents ($500.00)
    tip = 5000,      // cents ($50.00)
    merchantAccountId = "ma_operativa"
)

// 3. Record in backend (triggers automatic inventory deduction)
val result = paymentRepository.recordPayment(orderId, payment)
```

---

## 📝 Development Workflow

### 1. Before Starting a Feature
- [ ] Read feature requirements
- [ ] Check existing similar features
- [ ] Plan architecture (ViewModel → UseCase → Repository)
- [ ] Create feature module structure

### 2. During Development
- [ ] Write ViewModel with StateFlow
- [ ] Create Repository interface (domain layer)
- [ ] Implement Repository (data layer)
- [ ] Build Composable UI
- [ ] Add @Preview annotations
- [ ] Use stringResource for all text
- [ ] Use MaterialTheme for all colors

### 3. Before Committing

#### Code Quality
- [ ] Run `./gradlew lint --continue` (fails build on warnings)
- [ ] Run `./gradlew ktlintFormat` (if using ktlint)
- [ ] Add/update unit tests
- [ ] Update this CLAUDE.md if needed

#### CHANGELOG.md (MANDATORY)
- [ ] **ALWAYS** document changes in `CHANGELOG.md` (see [Changelog Guidelines](#-changelog-guidelines))
- [ ] Use proper category: Added, Changed, Fixed, Removed, Security
- [ ] Include file references and line numbers when relevant
- [ ] Check if CHANGELOG.md size exceeds 2000 lines → suggest rotation

#### Prevent Orphaned Files (CRITICAL)
- [ ] **Find unused files**: Check if deleted/refactored code left orphaned files
  ```bash
  # Option 1: IDE "Find Usages" on suspicious files
  # Option 2: Search with ripgrep
  rg "SuspiciousClassName" --type kotlin

  # Option 3: Check Android unused resources
  ./gradlew lint
  # Look for: UnusedResources warnings (drawables, layouts, strings)
  ```

- [ ] **Delete orphaned files**: Remove files with zero references
  - ViewModels without screens
  - Repositories without use cases
  - Composables not used anywhere
  - Resources (drawable, layout, strings) marked as unused by lint

- [ ] **Verify lint passes**: Ensure no UnusedResources warnings
  ```bash
  ./gradlew lint --continue
  # Check: build/reports/lint-results-debug.html
  ```

#### Git
- [ ] Write descriptive commit message (Conventional Commits)
- [ ] Verify no debug code (println, hardcoded values)

### 4. Git Commit Format
```
feat(payment): add credential caching for instant payments

- Implement singleton credential manager
- Reduce payment time from 6s to <1s
- Add fallback to Constants.kt

Resolves #234
```

---

## 📋 Changelog Guidelines

> **MANDATORY**: Every change must be documented in `CHANGELOG.md`. This creates a clear audit trail for AI and developers.

### Format: Keep a Changelog

Follow [Keep a Changelog](https://keepachangelog.com/) standard:

```markdown
# Changelog

All notable changes to Avoqado TPV will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangeable.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- PaymentViewModel: Add credential caching for instant payments (PaymentViewModel.kt:45)
  - Reduces payment processing time from 6s to <1s
  - Implements singleton CredentialManager
  - Fallback to Constants.kt for missing credentials
  - Related issue: #234

### Changed
- MainActivity: Migrate from XML to Jetpack Compose (MainActivity.kt:28)
  - Remove fragment_payment.xml
  - Add PaymentScreen composable
  - Update navigation to use Compose Navigation

### Fixed
- PaymentViewModel: Fix credential leakage on device rotation (PaymentViewModel.kt:67)
  - Clear sensitive data in onCleared()
  - Add EncryptedSharedPreferences for persistence
  - Security issue: #456

### Removed
- Delete orphaned PaymentFragment.kt (no usages found)
- Remove unused drawable: ic_old_logo.xml

### Security
- Add certificate pinning for api.avoqado.io (NetworkModule.kt:89)
  - SHA256: abc123...
  - Prevents MITM attacks

## [1.0.0] - 2025-01-30

### Added
- Initial release with Blumon PAX SDK integration
- Clean Architecture structure (Presentation/Domain/Data)
- Hilt dependency injection
- Feature modules: authorization, payment, management, menu, cart, timeclock

[Unreleased]: https://github.com/yourusername/avoqado-tpv/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/yourusername/avoqado-tpv/releases/tag/v1.0.0
```

---

### Categories (Use These ONLY)

| Category | When to Use | Examples |
|----------|-------------|----------|
| **Added** | New features, files, functionality | New ViewModel, API endpoint, Composable |
| **Changed** | Modifications to existing features | Refactor logic, update UI, change dependency |
| **Deprecated** | Features marked for removal | Old API methods, legacy code |
| **Removed** | Deleted features, files | Orphaned files, unused resources |
| **Fixed** | Bug fixes | Crash fixes, logic errors, UI glitches |
| **Security** | Vulnerability fixes, security improvements | Encryption, auth fixes, dependency updates |

---

### Entry Format (STRICT)

```markdown
### [Category]
- [ClassName/FileName]: [Action verb] [description] ([file:line])
  - [Optional: Additional detail 1]
  - [Optional: Additional detail 2]
  - [Optional: Related issue: #123]
```

#### Examples:

**Good Entries:**
```markdown
### Added
- PaymentViewModel: Add credential caching mechanism (PaymentViewModel.kt:45)
  - Reduces payment time from 6s to <1s
  - Uses singleton pattern with fallback to Constants.kt
  - Issue: #234

### Fixed
- CartViewModel: Fix null pointer exception on empty cart (CartViewModel.kt:89)
  - Add null check before calculating total
  - Display "Empty cart" message instead of crashing
  - Bug: #456

### Removed
- Delete PaymentFragment.kt (orphaned after Compose migration)
- Remove unused drawable resources: ic_old_logo.xml, ic_deprecated_icon.xml

### Changed
- PaymentScreen: Refactor UI to use Material3 components (PaymentScreen.kt:120)
  - Replace deprecated Button with new FilledButton
  - Update color scheme to MaterialTheme.colorScheme
  - Improves consistency across app
```

**Bad Entries (DO NOT DO THIS):**
```markdown
### Added
- Added some payment stuff  ❌ (too vague)
- New feature  ❌ (no file reference, unclear)
- PaymentViewModel.kt  ❌ (no action verb, no line number)
```

---

### When to Update CHANGELOG.md

#### ✅ ALWAYS Document These:
- New files created (ViewModels, Repositories, Composables)
- Modified existing logic (bug fixes, refactors)
- Deleted files (especially orphaned files)
- Dependency updates
- Security fixes
- Breaking changes
- Performance improvements

#### ❌ DON'T Document These:
- Typo fixes in comments
- Whitespace changes
- Code formatting (unless project-wide)
- Trivial variable renames (unless part of larger refactor)

---

### Rotation Strategy (File Size Management)

#### When CHANGELOG.md Exceeds 2000 Lines:

1. **Check line count:**
   ```bash
   wc -l CHANGELOG.md
   ```

2. **If > 2000 lines, Claude MUST suggest rotation:**
   ```
   ⚠️ CHANGELOG.md has exceeded 2000 lines (currently: 2345 lines).

   Suggested action:
   1. Create changelog/ directory if not exists
   2. Move old entries to changelog/2024.md (keep last 6 months in CHANGELOG.md)
   3. Update CHANGELOG.md header to reference archive

   Proceed with rotation? [Y/n]
   ```

3. **Rotation process:**
   ```bash
   # Create directory
   mkdir -p changelog

   # Move old content (manual: keep last 6 months in CHANGELOG.md)
   # Archive older entries to changelog/YYYY.md

   # Update CHANGELOG.md header
   cat > CHANGELOG.md <<'EOF'
   # Changelog

   For older changes, see:
   - [2024 Changelog](./changelog/2024.md)
   - [2023 Changelog](./changelog/2023.md)

   ## [Unreleased]
   ...
   EOF
   ```

4. **Git commit:**
   ```bash
   git add changelog/2024.md CHANGELOG.md
   git commit -m "docs: rotate CHANGELOG.md (archive 2024 entries)"
   ```

---

### AI Usage: How Claude Uses CHANGELOG.md

**Before making changes:**
1. Read CHANGELOG.md to understand recent work
2. Avoid duplicating recently added features
3. Follow existing patterns from recent entries

**During development:**
1. Keep track of changes in memory
2. Prepare CHANGELOG entry alongside code changes

**Before committing:**
1. Add entry to CHANGELOG.md under `[Unreleased]` section
2. Use proper category (Added/Changed/Fixed/Removed/Security)
3. Include file path and line number
4. Check if file size exceeds 2000 lines → suggest rotation

**Example workflow:**
```
User: "Add caching to PaymentViewModel"

Claude:
1. ✅ Read CHANGELOG.md first (check for existing caching work)
2. ✅ Implement feature
3. ✅ Add entry:
   ### Added
   - PaymentViewModel: Add credential caching for instant payments (PaymentViewModel.kt:45)
     - Reduces payment time from 6s to <1s
     - Issue: #234
4. ✅ Check file size: 1834 lines (OK, no rotation needed)
5. ✅ Commit with both code + CHANGELOG changes
```

---

### Integration with Git Workflow

#### Single commit includes BOTH code + CHANGELOG:

```bash
# ✅ CORRECT: Both in same commit
git add app/src/.../PaymentViewModel.kt CHANGELOG.md
git commit -m "feat(payment): add credential caching

- Implement singleton CredentialManager
- Reduce payment time from 6s to <1s
- Update CHANGELOG.md

Resolves #234"

# ❌ WRONG: Separate commits
git commit -m "feat: add caching"  # Missing CHANGELOG
git commit -m "docs: update changelog"  # Should be together
```

---

### Template for Claude to Use

```markdown
### [Category]
- [ClassName]: [Action] [description] ([file]:[line])
  - [Detail 1]
  - [Detail 2]
  - [Optional: Issue #]
```

**Pre-filled example:**
```markdown
### Added
- PaymentViewModel: Add credential caching mechanism (PaymentViewModel.kt:45)
  - Reduces payment processing time from 6s to <1s
  - Implements singleton CredentialManager with fallback
  - Related issue: #234
```

---

## 🧹 Orphaned Files Prevention

> **CRITICAL**: Prevent orphaned files from accumulating in the codebase. They cause confusion, slow down development, and bloat the APK.

### What are Orphaned Files?
- **Kotlin files** (ViewModels, Repositories, UseCases) with zero references
- **Composables** never imported or used
- **Resources** (drawables, layouts, strings) never referenced in code
- **Old implementations** replaced but not deleted

### Automated Detection (Build-time)

#### 1. Lint Configuration (Already Configured in build.gradle.kts)
```kotlin
// app/build.gradle.kts
lint {
    abortOnError = true  // Fail build on lint errors

    // Treat UnusedResources as ERROR
    error += setOf(
        "UnusedResources",  // Unused drawables, layouts, strings
        "UnusedIds"         // Unused view IDs
    )

    htmlReport = true
    htmlOutput = layout.buildDirectory.file("reports/lint-results-debug.html").get().asFile
}
```

#### 2. Run Lint Before Every Commit
```bash
# Option 1: Check for issues (doesn't fail)
./gradlew lint

# Option 2: Continuous (shows all issues)
./gradlew lint --continue

# View detailed report
open app/build/reports/lint-results-debug.html
```

#### 3. CI/CD Integration
```yaml
# .github/workflows/ci.yml (example)
- name: Run lint
  run: ./gradlew lint --continue

- name: Upload lint report
  uses: actions/upload-artifact@v3
  with:
    name: lint-report
    path: app/build/reports/lint-results-debug.html
```

### Manual Detection (Development-time)

#### 1. IDE "Find Usages" (Fastest)
```
1. Right-click on suspicious file/class
2. Select "Find Usages" (Cmd+B on Mac, Alt+F7 on Windows)
3. If zero usages → DELETE the file
```

#### 2. Ripgrep Search (Codebase-wide)
```bash
# Search for class usage
rg "PaymentViewModel" --type kotlin

# Search for Composable usage
rg "PaymentScreen" --type kotlin

# Search for resource usage (in XML or code)
rg "ic_payment" --type xml --type kotlin
```

#### 3. Grep for Imports (Alternative)
```bash
# Find files importing a specific class
grep -r "import.*PaymentViewModel" app/src/
```

### Common Orphaned File Patterns

#### Pattern 1: Refactored ViewModels
```kotlin
// ❌ WRONG: Old ViewModel still exists
// app/.../OldPaymentViewModel.kt  ← ORPHANED
// app/.../PaymentViewModel.kt     ← New one

// ✅ CORRECT: Delete old file after refactor
```

#### Pattern 2: Unused Composables
```kotlin
// ❌ WRONG: Created but never used
@Composable
fun UnusedDialog() { ... }  // ← Nobody calls this

// ✅ CORRECT: Delete if not referenced anywhere
```

#### Pattern 3: Orphaned Resources
```xml
<!-- res/drawable/old_icon.xml → ORPHANED -->
<!-- Nobody references @drawable/old_icon -->

<!-- ✅ CORRECT: Lint will catch this if configured -->
```

### Deletion Checklist

Before deleting a file, verify:
1. ✅ Zero imports in codebase (`rg "import.*ClassName"`)
2. ✅ Zero direct references (`rg "ClassName"`)
3. ✅ No dynamic references (reflection, string-based)
4. ✅ Not used in tests
5. ✅ Lint doesn't report it as used
6. ✅ Git commit separately: `git rm file.kt`

### Emergency: Clean All Orphaned Files

```bash
# 1. Run lint to generate report
./gradlew lint --continue

# 2. Open HTML report and check UnusedResources
open app/build/reports/lint-results-debug.html

# 3. Delete files listed as unused
# IMPORTANT: Review carefully before deleting!

# 4. Re-run lint to verify
./gradlew lint

# 5. Build to ensure nothing broke
./gradlew assembleDebug
```

---

## ⚠️ Common Pitfalls & Solutions

### Problem: First Payment Takes 30 Seconds
**Cause:** SQLite connection leak in Storage singleton
**Solution:** Use single Storage instance in AvoqadoApp
```kotlin
// AvoqadoApp.kt
companion object {
    val storage: Storage by lazy { Storage(context) }
}
```

### Problem: Cross-Tenant Data Leak
**Cause:** Missing venueId filter in query
**Solution:** ALWAYS filter by venueId
```kotlin
// ❌ WRONG
database.orderDao().getAll()

// ✅ CORRECT
database.orderDao().getOrdersByVenue(authContext.venueId)
```

### Problem: UI Freezes During Payment
**Cause:** Blocking operation on main thread
**Solution:** Use coroutines + Dispatchers.IO
```kotlin
viewModelScope.launch {
    withContext(Dispatchers.IO) {
        processPayment(payment)
    }
}
```

### Problem: Socket Events Not Received
**Cause:** Not joined to correct room
**Solution:** Join room before listening
```kotlin
// First join room
SocketIOFacade.joinVenueRoom(venueId)

// Then collect events
SocketIOFacade.messageFlow.collect { message ->
    // Handle event
}
```

---

## 🧪 Testing Strategy

### Unit Tests (ViewModels)
```kotlin
class PaymentViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `should process payment successfully`() = runTest {
        // Given
        val payment = Payment(amount = 500, tip = 50)
        coEvery { repository.processPayment(any()) } returns Result.success(payment)

        // When
        viewModel.processPayment(payment)

        // Then
        val state = viewModel.state.value
        assertThat(state).isInstanceOf(PaymentState.Success::class.java)
    }
}
```

### Integration Tests (Critical Flows)
```kotlin
@Test
fun `complete payment flow from cart to receipt`() = runTest {
    // 1. Create order
    val order = createOrder(items = listOf(burger, fries))

    // 2. Process payment
    val payment = processPayment(orderId = order.id, amount = 500)

    // 3. Verify backend sync
    val updatedOrder = getOrder(order.id)
    assertThat(updatedOrder.status).isEqualTo(OrderStatus.PAID)
}
```

---

## 🔍 Debugging Tips

### ADB Log Monitoring
```bash
# Clear logs and monitor with filters
adb logcat -c && adb logcat | grep -E "AvoqadoTPV|Payment|Socket" --line-buffered

# Monitor specific component
adb logcat | grep -E "PaymentViewModel|SocketIO"

# Save logs to file
adb logcat > logs.txt
```

### Socket.IO Debugging
```kotlin
// Enable debug logs
socket.on(Socket.EVENT_CONNECT) {
    Timber.d("✅ Socket connected")
}

socket.on(Socket.EVENT_DISCONNECT) {
    Timber.w("⚠️ Socket disconnected")
}

socket.on(Socket.EVENT_ERROR) { args ->
    Timber.e("❌ Socket error: ${args[0]}")
}
```

---

## 📚 Additional Resources

### Project Documentation
- [GREENFIELD BLUEPRINT](./GREENFIELD_BLUEPRINT.md) - Complete architecture & 28-day implementation plan
- [Backend API Docs](https://humane-immortal-pika.ngrok-free.app/api-docs) - Swagger documentation <- might be incomplete, double check on avoqado-server directory>

### External References
- [Jetpack Compose Docs](https://developer.android.com/jetpack/compose)
- [Hilt Documentation](https://dagger.dev/hilt/)
- [Kotlin Coroutines Guide](https://kotlinlang.org/docs/coroutines-guide.html)
- [Material Design 3](https://m3.material.io/)

### Team Contacts
- Backend API: Check `avoqado-server/` Claude.md
- Payment Issues: Blumon PAX SDK documentation

## Before ending
- Try to compile to see if there is some issues
- Always check if your changes would impact on the blumonpay implementation, it should work always
- If some files are deprecated or unused please delete them, we want to prevent the orphaned files

## 🔄 Recent Changes

### [2025-01-30] - Project Setup
- Initial project structure with Clean Architecture
- Configured Hilt dependency injection
- Set up Blumon PAX SDK integration
- Created feature modules (authorization, payment, management, menu, cart, timeclock)

---

## 🎯 Next Steps / TODO

- [ ] Implement offline payment queue
- [ ] Add receipt printing (PAX printer SDK)
- [ ] Implement biometric authentication
- [ ] Add unit tests for ViewModels
- [ ] Add integration tests for payment flow
- [ ] Implement certificate pinning
- [ ] Add performance monitoring (Firebase Performance)

---

**Last Updated:** 2025-01-30
**Maintainer:** Development Team

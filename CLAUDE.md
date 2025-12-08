# Avoqado TPV - Development Context

> **Sistema de punto de venta Android para restaurantes, hoteles, gimnasios y retail**
> **Stack:** Kotlin + Jetpack Compose + Hilt + Blumon PAX SDK

---

## 📚 Quick Reference

### Core Stack
- 🏗️ **Architecture:** Clean Architecture (Presentation → Domain → Data)
- 🎨 **UI:** 100% Jetpack Compose (NO XML)
- 💉 **DI:** Hilt 2.57
- 🔐 **Security:** EncryptedSharedPreferences, Certificate Pinning
- 🌐 **Backend:** Production: `https://api.avoqado.io/api/v1/` | Dev: `https://unmistrustful-marla-unvermiculated.ngrok-free.dev`
- 🔌 **Real-time:** Socket.IO (room-based events)
- 💳 **Payments:** Blumon PAX SDK (multi-merchant support)
- ⚡ **Performance:** 1GB RAM target (PAX A80) - ALWAYS paginate, cleanup cache, avoid heavy animations

### Specialized Guides (Deep Dives)
- 🚀 **[GREENFIELD_BLUEPRINT.md](./GREENFIELD_BLUEPRINT.md)** - Complete architecture & 28-day implementation plan
- 💰 **[PAYMENT_RECONCILIATION.md](./PAYMENT_RECONCILIATION.md)** - Payment logic + Blumon multi-merchant system
- ⚡ **[PERFORMANCE_GUIDE.md](./PERFORMANCE_GUIDE.md)** - 1GB RAM optimization, pagination, caching
- 📱 **[UI_RESPONSIVE_GUIDE.md](./UI_RESPONSIVE_GUIDE.md)** - Responsive patterns for TPV devices (PAX A80, A920)
- 🔌 **[SOCKET_IO_IMPLEMENTATION.md](./SOCKET_IO_IMPLEMENTATION.md)** - Real-time events architecture & integration
- 🔄 **[LOCAL_FIRST_SYNC_PATTERNS.md](./LOCAL_FIRST_SYNC_PATTERNS.md)** - **CRITICAL: Avoid losing local-only fields when syncing with backend**
- 📡 **[docs/TPV_COMMAND_FLOW.md](./docs/TPV_COMMAND_FLOW.md)** - Remote command system (lock, maintenance, heartbeat ACK flow)
- 🧪 **[TESTING_GUIDE.md](./TESTING_GUIDE.md)** - Unit tests, integration tests, debugging tools
- 🧪 **[SOCKET_IO_TESTING.md](./SOCKET_IO_TESTING.md)** - Socket.IO testing strategies & examples
- 🔐 **[SECURITY_CHECKLIST.md](./SECURITY_CHECKLIST.md)** - Encryption, tenant isolation, certificate pinning
- 🏗️ **[PRODUCTION_BUILD_GUIDE.md](./PRODUCTION_BUILD_GUIDE.md)** - Build variants, deployment, troubleshooting

---

## 🚨 CRITICAL: Blumon SDK & Multi-Merchant - HANDLE WITH EXTREME CARE

> **⚠️ WARNING**: The Blumon PAX SDK and multi-merchant functionality are the MOST CRITICAL parts of this application. ANY changes to payment-related code can result in **failed transactions, lost revenue, or corrupted payment data**.

### Before Modifying Payment Code:

1. **UNDERSTAND THE FULL FLOW** - Read [PAYMENT_RECONCILIATION.md](./PAYMENT_RECONCILIATION.md) completely
2. **NEVER modify without testing** - Test BOTH sandbox AND production variants
3. **Multi-merchant switching takes ~8 seconds** - This is EXPECTED behavior (OAuth + DUKPT keys download)
4. **SDK can only have ONE merchant active** - Switching requires full re-initialization

### Files Requiring Extra Caution:

| File | Risk Level | Why |
|------|-----------|-----|
| `PaymentViewModel.kt` (sandbox/production) | 🔴 CRITICAL | Core payment flow, SDK integration |
| `InitializationManager.kt` (sandbox/production) | 🔴 CRITICAL | Blumon SDK initialization (OAuth, DUKPT) |
| `MultiMerchantSDKManager.kt` | 🔴 CRITICAL | Merchant switching logic |
| `BlumonInitializer.kt` (sandbox/production) | 🔴 CRITICAL | SDK credential setup |
| `RecordPaymentUseCase.kt` | 🟠 HIGH | Backend payment recording |

### SDK Initialization Flow (DO NOT BREAK):

```
Login Success
    │
    └─► InitializationManager.ensureInitialized() [BACKGROUND]
            │
            ├─► Step 1: OAuth authentication (sandbox-tokener.blumonpay.net)
            ├─► Step 2: DUKPT key download
            ├─► Step 3: posId verification via backend
            └─► Step 4: Store last init timestamp (24h cache)
```

### Multi-Merchant Switch Flow (DO NOT BREAK):

```
User selects different merchant
    │
    └─► MultiMerchantSDKManager.switchMerchant()
            │
            ├─► Update TerminalConfig.serialNumber
            ├─► Force SDK re-initialization (~8 seconds)
            └─► Update _currentMerchant StateFlow
```

### Race Condition Prevention:

- **ALWAYS await merchants** before reading `_merchants.value`
- **ALWAYS check SDK ready** before starting payment
- **NEVER assume merchants are loaded** - they load asynchronously

### Testing Checklist for Payment Changes:

- [ ] Test with sandbox variant (`./gradlew installSandboxDebug`)
- [ ] Test with production variant (if you have production terminal)
- [ ] Test merchant switching (A → B → A)
- [ ] Test fast user flow (rating → tip → pay in <5 seconds)
- [ ] Test payment after app restart
- [ ] Verify backend receives correct `merchantAccountId`
- [ ] Verify cash payments have `merchantAccountId: null`

---

## 🚨 CRITICAL: Local-First Sync - PRESERVE LOCAL-ONLY FIELDS

> **⚠️ WARNING**: When loading data from backend, LOCAL-ONLY fields like `sentToKitchenAt` get LOST if not handled correctly. This causes bugs where items lose their "printed" status.

### The Problem

Some fields exist ONLY in Room DB, NOT in backend:
- `sentToKitchenAt` - Kitchen print timestamp
- `syncStatus` - Local sync state
- `isServerCreated` - Whether item has server CUID

**When backend returns data, these fields are `null`!**

### Before Modifying Sync/Cache/Load Code:

1. **READ** [LOCAL_FIRST_SYNC_PATTERNS.md](./LOCAL_FIRST_SYNC_PATTERNS.md) completely
2. **CHECK** if the code handles local-only fields
3. **NEVER** use backend data directly for state - always load from local DB after caching

### Quick Pattern

```kotlin
// ❌ WRONG: Backend data loses local fields
val backendOrder = orderRepository.getOrder(orderId)
_state.value = MenuState.Success(backendOrder)  // sentToKitchenAt = null!

// ✅ CORRECT: Cache first (preserves local), then load from DB
orderSyncCoordinator.cacheBackendOrder(backendOrder)
val mergedOrder = orderSyncCoordinator.getLocalOrder(orderId) ?: backendOrder
_state.value = MenuState.Success(mergedOrder)  // sentToKitchenAt preserved!
```

### Files Requiring This Check

| File | Risk | What to check |
|------|------|---------------|
| `MenuViewModel.kt` | 🔴 HIGH | `loadOrder()`, sync event handlers |
| `OrderSyncCoordinator.kt` | 🔴 HIGH | `cacheBackendOrder()`, any cache updates |
| `OrderListViewModel.kt` | 🟠 MEDIUM | Order list loading |

---

## 🎯 Core Principles: Anti-Hallucination Protocol

> **MANDATORY**: All code MUST follow world-class Kotlin/Android best practices. NO exceptions unless explicitly documented.

### Zero Tolerance for Bad Practices

#### ❌ NEVER Do This

```kotlin
// ❌ Magic numbers
val timeout = 5000

// ❌ Mutable state without StateFlow
var isLoading = false

// ❌ Blocking calls on Main thread
suspend fun loadData() { database.query() }

// ❌ Nullability hacks
val value = data!!.field!!.value!!

// ❌ Exception swallowing
try { riskyOperation() } catch (e: Exception) { }

// ❌ Manual threading
Thread { doWork() }.start()
```

#### ✅ ALWAYS Do This

```kotlin
// ✅ Named constants
private const val NETWORK_TIMEOUT_MS = 5000L

// ✅ Immutable state with StateFlow
private val _state = MutableStateFlow<UiState>(UiState.Idle)
val state: StateFlow<UiState> = _state.asStateFlow()

// ✅ Proper coroutine dispatchers
suspend fun loadData() = withContext(Dispatchers.IO) { database.query() }

// ✅ Safe null handling
val value = data?.field?.value ?: defaultValue

// ✅ Proper error handling
try {
    riskyOperation()
} catch (e: NetworkException) {
    Timber.e(e, "Network error")
    emit(Result.Error(e.toUserFriendlyMessage()))
}

// ✅ Structured concurrency
viewModelScope.launch { doWork() }
```

### ⚠️ Anti-Overengineering Protocol (MANDATORY)

> **CRITICAL**: Before implementing any solution, ALWAYS verify it's the MINIMUM necessary code. Review TWICE to prevent overengineering.

#### The 3-Question Test (Ask Before Every Implementation)

1. **"Does this already work?"** → Check existing code first. 99% of cases may already be handled.
2. **"Is this the simplest solution?"** → Can I achieve the same with fewer files/lines?
3. **"Am I solving a real problem?"** → Is this fixing an actual bug or a hypothetical one?

#### Red Flags 🚩 (STOP and Reconsider)

```kotlin
// 🚩 RED FLAG: Modifying 7+ files for a "simple" fix
// Ask: Can I do this with 3 files or less?

// 🚩 RED FLAG: Adding JWT decoding, proactive token refresh, new managers
// Ask: Does TokenAuthenticator already handle 401s automatically?

// 🚩 RED FLAG: Creating new abstractions "for future flexibility"
// Ask: Do I need this NOW or am I guessing future requirements?

// 🚩 RED FLAG: Adding validation that duplicates existing validation
// Ask: Is this already validated elsewhere in the flow?
```

#### Minimal Solution Pattern

```kotlin
// ❌ OVERENGINEERED: 7 files, JWT decoding, proactive refresh, new managers
// "What if the token expires mid-payment? Let me decode JWT, track expiry,
//  add proactive refresh, create a SessionHealthChecker..."

// ✅ MINIMAL: 3 files, simple null check
// "If venueId/staffId are null, show error + login button. Done."
fun startPayment(amount: String) {
    val venueId = authRepository.getVenueId()
    val staffId = authRepository.getStaffId()

    if (venueId.isNullOrBlank() || staffId.isNullOrBlank()) {
        _state.value = PaymentState.Error(
            message = "Tu sesión expiró.\n\nPor favor inicia sesión de nuevo.",
            showLoginButton = true
        )
        return
    }
    // Continue with existing flow...
}
```

#### Before Implementing, Check:

- [ ] **Existing handlers**: Does TokenAuthenticator/SessionManager already cover this?
- [ ] **Scope creep**: Am I adding features nobody asked for?
- [ ] **Hypothetical bugs**: Am I fixing a bug that hasn't happened?
- [ ] **File count**: Can I reduce the number of modified files?
- [ ] **Line count**: Can I achieve the same with less code?

**Rule of Thumb**: If your "fix" touches more than 3-4 files, STOP and ask yourself if you're overengineering.

### Naming Conventions

```kotlin
// Classes: PascalCase
class PaymentViewModel

// Functions: camelCase (verb-based)
fun processPayment()
fun calculateTotal()

// Properties: camelCase
val totalAmount: BigDecimal

// Constants: SCREAMING_SNAKE_CASE
const val MAX_RETRY_ATTEMPTS = 3

// Backing properties: underscore prefix
private val _state = MutableStateFlow(State.Idle)
val state: StateFlow<State> = _state.asStateFlow()

// Sealed classes for state
sealed class PaymentState {
    data object Idle : PaymentState()
    data class Loading(val progress: Float) : PaymentState()
    data class Success(val result: TransactionResult) : PaymentState()
    data class Error(val error: PaymentError) : PaymentState()
}
```

### Architecture Patterns (Clean Architecture)

```
┌─────────────────────────────────────────┐
│ PRESENTATION (UI)                       │
│ • Composables (stateless)               │
│ • ViewModels (StateFlow)                │
└─────────────────────────────────────────┘
                  ↓
┌─────────────────────────────────────────┐
│ DOMAIN (Business Logic)                 │
│ • UseCases (single responsibility)      │
│ • Repository Interfaces                 │
│ • Domain Models                         │
└─────────────────────────────────────────┘
                  ↓
┌─────────────────────────────────────────┐
│ DATA (Infrastructure)                   │
│ • Repository Implementations            │
│ • Data Sources (API, DB, SDK)           │
│ • DTOs → Domain Mappers                 │
└─────────────────────────────────────────┘
```

**Example:**
```kotlin
// PRESENTATION: ViewModel
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

// DOMAIN: UseCase
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

// DATA: Repository Implementation
class PaymentRepositoryImpl @Inject constructor(
    private val blumonSdk: BlumonPaySDK,
    private val api: PaymentApi
) : PaymentRepository {
    override suspend fun processPayment(request: PaymentRequest): TransactionResult {
        return withContext(Dispatchers.IO) {
            val sdkResult = blumonSdk.processPayment(request.toSdkRequest())
            api.recordPayment(sdkResult.toDto())
            sdkResult.toDomain()
        }
    }
}
```

---

## ⚡ Performance Guidelines (1GB RAM Devices)

> **Complete guide:** [PERFORMANCE_GUIDE.md](./PERFORMANCE_GUIDE.md)

**Critical Rules (memorize these):**

| Rule | Limit | Why |
|------|-------|-----|
| Pagination | 20 items/page (max 50) | Prevents OOM on large datasets |
| Cache TTL | 24h, cleanup on logout | Prevents memory leaks |
| Images | Always request thumbnails | Full-size images cause OOM |
| Animations | Prefer instant, max fadeIn/fadeOut | Heavy animations lag on 1GB RAM |
| Database | Index ALL queried columns | Unindexed queries are slow |
| Memory | Total app RAM < 200MB | PAX A80 has only 1GB total |
| Background | Always use `Dispatchers.IO` | Main thread blocks = UI freeze |

**Quick Examples:**
```kotlin
// ❌ val orders = orderRepository.getAllOrders()  // OOM risk!
// ✅ val orders = orderRepository.getOrders(limit = 20, cursor = cursor)

// ❌ Timber.d("Order: $order")  // Creates huge string!
// ✅ Timber.d("Order: id=${order.id}, status=${order.status}")
```

---

## 🧠 Quick Decision Matrix

### 1. Error Handling: Technical vs User-Friendly

> **CRITICAL**: NEVER show technical errors to users. Always translate to actionable messages.

#### Decision Tree

```
┌─────────────────────────────────┐
│ Error occurred                  │
└─────────────────────────────────┘
          │
          v
┌─────────────────────────────────┐
│ Log technical details           │
│ Timber.e(e, "Technical context")│
└─────────────────────────────────┘
          │
          v
┌─────────────────────────────────┐
│ Translate to user message       │
│ when (error) {                  │
│   SDK → "Card removed too fast" │
│   Network → "Check connection"  │
│   Timeout → "Try again"         │
│ }                               │
└─────────────────────────────────┘
```

#### Examples

```kotlin
// ❌ WRONG: Technical error exposed
catch (e: Exception) {
    _state.value = State.Error("Error: $e")
    // User sees: "ReadingContactlessFailure@efcd17c"
}

// ✅ CORRECT: User-friendly translation
if (result.isLeft) {
    val error = result.leftValue()
    Timber.e("❌ [TECHNICAL] Contactless failed: $error")

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
        error.toString().contains("NetworkException", ignoreCase = true) -> {
            "No se pudo conectar al servidor.\n\n" +
            "Verifique su conexión a internet e intente nuevamente."
        }
        else -> {
            "Error leyendo tarjeta contactless.\n\n" +
            "Intente nuevamente o inserte la tarjeta en el chip."
        }
    }

    _state.value = State.Error(userMessage)
}
```

**Every error message MUST have:**
1. ✅ **What happened** (in simple terms) - "La tarjeta se retiró demasiado rápido"
2. ✅ **How to fix it** (actionable steps) - "Mantenga la tarjeta sobre el lector"
3. ✅ **Alternative action** (if available) - "Intente nuevamente o inserte en el chip"

### 2. When to Create Reusable Components

#### Decision Tree

```
┌─────────────────────────────────┐
│ Do I need this UI in 2+ places? │
└─────────────────────────────────┘
          │
    ┌─────┴─────┐
   YES          NO
    │            │
    v            v
┌────────┐  ┌────────────┐
│ Create │  │ Is it a    │
│ in     │  │ common     │
│ Design │  │ pattern?   │
│ System │  │ (loading,  │
└────────┘  │ error)     │
            └────────────┘
                 │
            ┌────┴────┐
           YES       NO
            │         │
            v         v
      ┌────────┐  ┌────────┐
      │ Create │  │ Keep   │
      │ anyway │  │ inline │
      └────────┘  └────────┘
```

#### Example: AvoqadoLoadingOverlay

```kotlin
// ❌ WRONG: Inline overlay duplicated across screens
if (state is Loading) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(0.6f))) {
        Card { CircularProgressIndicator() }
    }
}

// ✅ CORRECT: Reusable component
// core/presentation/components/AvoqadoLoadingIndicator.kt
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

// Usage in LoginScreen
if (state is Loading) {
    AvoqadoLoadingOverlay(message = "Autenticando...")
}

// Usage in PaymentScreen
if (state is Processing) {
    AvoqadoLoadingOverlay(message = "Procesando pago...")
}
```

### 3. Responsive UI for TPV Devices

> **See [UI_RESPONSIVE_GUIDE.md](./UI_RESPONSIVE_GUIDE.md) for complete patterns**

#### MANDATORY: Use ResponsiveScaffold

```kotlin
// ❌ WRONG: Hardcoded sizes (won't fit on PAX A80)
Column {
    Image(modifier = Modifier.size(120.dp))
    Spacer(modifier = Modifier.height(48.dp))
    PinPad()  // Cut off on small screens!
}

// ✅ CORRECT: ResponsiveScaffold with dynamic sizing
ResponsiveScaffold(
    scrollable = false,  // Workflow screen - no scroll allowed
    horizontalAlignment = Alignment.CenterHorizontally
) {
    val sizes = LocalResponsiveSizes.current
    Image(modifier = Modifier.size(sizes.logoSize))  // Auto-scales
    Spacer(modifier = Modifier.height(sizes.spacingMedium))
    PinPad()  // Guaranteed to fit!
}
```

**Available size tokens:**
- `logoSize`: 60dp / 80dp / 100dp (small / medium / large)
- `spacingSmall`: 8dp / 12dp / 16dp
- `spacingMedium`: 16dp / 24dp / 32dp
- `spacingLarge`: 24dp / 32dp / 48dp
- `paddingScreen`: 16dp / 20dp / 24dp

**Device matrix:**
- PAX A80: 1024x600 dp (small)
- PAX A920: 1280x720 dp (medium)
- Sunmi T2s: 1280x800 dp (large)

### 4. Loading States: Prevent Flash Screens

> **CRITICAL**: ALWAYS use `AvoqadoLoadingOverlay` to prevent jarring UI transitions

#### What are Flash Screens?
- Brief flicker of previous screen during navigation
- User sees: Modal closes → **Flash of WelcomeScreen** ⚠️ → PaymentScreen appears

#### Solution

```kotlin
// ✅ CORRECT: Loading overlay prevents flash
is PaymentState.Idle -> {
    // Show loading overlay immediately
    if (initialAmount != null) {
        AvoqadoLoadingOverlay(message = "Preparando pago...")
    }

    LaunchedEffect(initialAmount) {
        if (initialAmount != null) {
            viewModel.submitAmount(initialAmount)
        }
    }
}
```

**Result**: User sees: Modal closes → **Smooth loading overlay** ✅ → PaymentScreen

**MANDATORY Rules:**
1. ✅ ALWAYS use same component: `AvoqadoLoadingOverlay`
2. ✅ ALWAYS show loading during async state transitions
3. ✅ Loading messages MUST be contextual ("Preparando pago...", not "Cargando...")
4. ❌ NEVER navigate without loading if data processing is involved

---

## 💰 Avoqado-Specific Domain Rules

### Payment Integration: Blumon PAX SDK

> **Complete guide:** [PAYMENT_RECONCILIATION.md](./PAYMENT_RECONCILIATION.md)

#### Critical Configuration

```kotlin
// build.gradle.kts
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

#### Multi-Merchant System (1 Device → N Merchants)

**Concept**: 1 physical PAX terminal can process payments for MULTIPLE merchants using virtual serial numbers.

```
Physical Terminal: AVQD-2841548417
├── Virtual Serial A: 2841548417 → Merchant A (BBVA, 1.5% rate)
└── Virtual Serial B: 2841548418 → Merchant B (Santander, 1.8% rate)
```

**Payment Flow**:
1. User selects merchant (UI button)
2. SDK reinitializes with new credentials (3-5 seconds)
3. Payment routes to correct posId/merchant
4. Backend records payment with `merchantAccountId`

**Critical Rule: Payment Source Separation**

```typescript
// ✅ CORRECT: Cash with no merchant
{
  method: "CASH",
  merchantAccountId: null,  // ← Cash has no merchant
  amount: 5000
}

// ✅ CORRECT: Card with merchant
{
  method: "CARD",
  merchantAccountId: "merchant_002",  // ← Required for cards
  amount: 5000
}
```

**Why?** End-of-day reconciliation separates cash (0% commission) from card payments (2.5% commission).

### Backend Integration

#### REST API

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

    // Payments
    @POST("tpv/venues/{venueId}/orders/{orderId}")
    suspend fun recordPayment(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body payment: PaymentRequest
    ): Response<Payment>
}
```

#### Socket.IO (Real-time Events)

> **Complete guides:**
> - **[SOCKET_IO_IMPLEMENTATION.md](./SOCKET_IO_IMPLEMENTATION.md)** - Architecture & integration patterns
> - **[SOCKET_IO_TESTING.md](./SOCKET_IO_TESTING.md)** - Testing strategies & examples

**Core Concept**: Socket.IO provides real-time bidirectional communication for instant updates across multiple terminals/devices.

**Architecture**:
- Singleton `SocketManager` (Hilt injected)
- Auto-connects on login with JWT authentication
- Room-based event isolation (venue, table, order)
- SharedFlow for reactive event streaming

**Quick Example**:
```kotlin
@HiltViewModel
class PaymentViewModel @Inject constructor(
    private val socketManager: SocketManager
) : ViewModel() {

    init {
        collectSocketEvents()
    }

    private fun collectSocketEvents() {
        viewModelScope.launch {
            socketManager.events.collect { event ->
                when (event) {
                    is SocketEvent.PaymentCompleted -> {
                        Timber.i("✅ Payment completed: ${event.paymentId}")
                        refreshOrder(event.orderId)
                    }
                    else -> {}
                }
            }
        }
    }
}
```

##### 🎯 Decision Tree: When to Add Socket.IO Events for New Features

> **MANDATORY**: Before implementing any new feature, evaluate if it needs real-time updates.

```
┌─────────────────────────────────────────────────────┐
│ Is data shown to multiple users/terminals?         │
└─────────────────────────────────────────────────────┘
                    │
            ┌───────┴───────┐
           YES             NO
            │               │
            v               v
┌──────────────────┐  ┌──────────────────┐
│ Can data change  │  │ Does data change │
│ while user is    │  │ while user is    │
│ viewing?         │  │ viewing?         │
└──────────────────┘  └──────────────────┘
         │                     │
    ┌────┴────┐           ┌────┴────┐
   YES       NO          YES       NO
    │         │           │         │
    v         v           v         v
┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐
│ ✅ YES │ │ ❌ NO  │ │ ⚠️ ASK │ │ ❌ NO  │
│ Socket │ │ Polling│ │  USER  │ │ Static │
│ needed │ │ or     │ │        │ │ fetch  │
│        │ │ manual │ │        │ │        │
│        │ │ refresh│ │        │ │        │
└────────┘ └────────┘ └────────┘ └────────┘
```

**Examples**:

| Feature | Multi-User? | Changes? | Socket? | Reason |
|---------|-------------|----------|---------|--------|
| **Payment on Order** | ✅ YES | ✅ YES | ✅ YES | Multiple terminals view same order → instant sync |
| **Order Status Update** | ✅ YES | ✅ YES | ✅ YES | Kitchen updates status → waiters see instantly |
| **Inventory Low Stock Alert** | ✅ YES | ✅ YES | ✅ YES | System detects shortage → all terminals notified |
| **Admin Remote Command** | ✅ YES | ⚠️ RARE | ✅ YES | Dashboard can disable terminal remotely |
| **Staff Login** | ❌ NO | ❌ NO | ❌ NO | Single-user, one-time action |
| **Menu Browsing** | ❌ NO | ⚠️ RARELY | ❌ NO | Use polling or manual refresh |
| **Historical Reports** | ❌ NO | ❌ NO | ❌ NO | Static data, fetch on demand |
| **Table Reservation** | ✅ YES | ✅ YES | ⚠️ ASK | Depends on use case (ask user) |

**Quick Reference:**
| Use Socket? | Scenario | Events |
|-------------|----------|--------|
| ✅ YES | Multi-terminal payments | `payment_completed`, `payment_failed` |
| ✅ YES | Order status changes | `order_updated`, `order_status_changed` |
| ✅ YES | System alerts | `system_alert` |
| ✅ YES | Admin commands | `tpv_command` |
| ❌ NO | Historical data, auth, static menus | Use REST API |
| ⚠️ ASK | Table reservations, shift changes | Depends on use case |

> **How to add new events:** See [SOCKET_IO_IMPLEMENTATION.md](./SOCKET_IO_IMPLEMENTATION.md) → "How to Add a New Socket.IO Event"

#### Rate Limiting

> **Production vs Development Limits**

| Endpoint | Production | Development |
|----------|-----------|-------------|
| PIN Login | 10 attempts / 15 min | 100 attempts / 1 min |
| Activation | 5 attempts / 15 min | 50 attempts / 1 min |
| API Calls | 1000 req / hour | 10,000 req / hour |

**Android Error Handling (429)**:
```kotlin
429 -> {
    Timber.w("⚠️ Rate limit exceeded - Backend should have higher limits in DEV")
    "Demasiados intentos. Por favor espera un momento.\n\n" +
    "ℹ️ Si estás en desarrollo, el backend debe configurar rate limits más altos."
}
```

### Security

> **Complete guide:** [SECURITY_CHECKLIST.md](./SECURITY_CHECKLIST.md)

#### Critical Rules (NON-NEGOTIABLE)

```kotlin
// ✅ Encrypted storage (ALWAYS)
val masterKey = MasterKey.Builder(context)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .build()

val encryptedPrefs = EncryptedSharedPreferences.create(
    context, "secure_session", masterKey,
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)

// ✅ Tenant isolation (ALWAYS filter by venueId)
val orders = orderRepository.getOrders(venueId = authContext.venueId)

// ❌ NEVER fetch without tenant filter (security risk!)
val orders = orderRepository.getAllOrders()  // WRONG!

// ✅ Certificate pinning
val certificatePinner = CertificatePinner.Builder()
    .add("api.avoqado.io", "sha256/...")
    .build()

// ✅ No secrets in code (use environment variables)
buildConfigField("String", "API_KEY", "\"${System.getenv("AVOQADO_API_KEY")}\"")

// ❌ NEVER log sensitive data
Timber.d("Card number: ${card.number}")  // WRONG!
```

### UI/UX Patterns

> **Complete guide:** [UI_RESPONSIVE_GUIDE.md](./UI_RESPONSIVE_GUIDE.md)

#### Composable Structure

```kotlin
@Composable
fun FeatureScreen(
    viewModel: FeatureViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ResponsiveScaffold(scrollable = false) {
        val sizes = LocalResponsiveSizes.current

        when (val currentState = state) {
            is State.Loading -> AvoqadoLoadingOverlay(message = "Cargando...")
            is State.Success -> SuccessContent(currentState.data)
            is State.Error -> ErrorMessage(currentState.message)
        }
    }
}

// ✅ MUST HAVE Preview
@Preview(showBackground = true)
@Composable
private fun FeatureScreenPreview() {
    AvoqadoTheme {
        FeatureScreen()
    }
}
```

#### Spacing & Layout Consistency

**⚠️ MANDATORY**: All screens MUST use consistent spacing between header and content sections.

**Standard Spacing Rules**:

```kotlin
Scaffold(
    topBar = { AvoqadoTopBar(...) }
) { paddingValues ->
    ResponsiveScaffold(
        scrollable = true,
        modifier = Modifier.padding(paddingValues)  // ← CRITICAL: Apply paddingValues from Scaffold
    ) {
        val sizes = LocalResponsiveSizes.current

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(sizes.spacingLarge)  // ← Use spacingLarge (24dp-48dp)
        ) {
            // Section 1
            MainContentSection()

            // Section 2 (with section header)
            SectionWithHeader()
        }
    }
}
```

**Spacing Tokens** (from ResponsiveSizes):
- `spacingSmall`: 8dp / 12dp / 16dp - Between small elements (icon + text, form fields)
- `spacingMedium`: 16dp / 24dp / 32dp - Between cards, list items
- `spacingLarge`: 24dp / 32dp / 48dp - Between major sections, header to content
- `paddingScreen`: 16dp / 20dp / 24dp - Screen edge padding (handled by ResponsiveScaffold)

**Common Patterns**:

```kotlin
// ✅ CORRECT: Major sections with spacingLarge
Column(
    verticalArrangement = Arrangement.spacedBy(sizes.spacingLarge)
) {
    ActiveShiftContent()
    ShiftHistoryList()
}

// ✅ CORRECT: Cards within section with spacingMedium
Column(
    verticalArrangement = Arrangement.spacedBy(sizes.spacingMedium)
) {
    shifts.forEach { shift ->
        ShiftCard(shift)
    }
}

// ❌ WRONG: Hardcoded spacing
Column(
    verticalArrangement = Arrangement.spacedBy(16.dp)  // Don't hardcode!
)

// ❌ WRONG: No spacing between major sections
Column {
    ActiveShiftContent()
    ShiftHistoryList()  // Too cramped!
}
```

**Section Headers** (like "HISTORIAL DE TURNOS"):
- Should be inside the section's Column/LazyColumn
- Add `Spacer(modifier = Modifier.height(sizes.spacingMedium))` after header
- Or use `verticalArrangement = Arrangement.spacedBy(sizes.spacingMedium)`

#### Dark Theme (Avoqado Dashboard Web Design)

**⚠️ IMPORTANT:** App ALWAYS uses Dark Mode by default (matches Avoqado Web Dashboard).

**Color Palette (OKLCH)**:

| Token | HEX | Usage |
|-------|-----|-------|
| **background** | `#1C1C1C` | Main background (deep charcoal) |
| **foreground** | `#FAFAFA` | Primary text (soft white) |
| **card** | `#2A2A2A` | Cards & elevated surfaces |
| **primary** | `#E8E8E8` | Primary buttons & accents |
| **error** | `#EB5757` | Errors & destructive actions |

**Usage**:
```kotlin
// ✅ ALWAYS use semantic colors
Text(color = MaterialTheme.colorScheme.primary)
Box(modifier = Modifier.background(MaterialTheme.colorScheme.surface))

// ❌ NEVER hardcode colors
Text(color = Color(0xFF2563EB))  // WRONG!
```

#### Auto-Retry on Reconnection

**⚠️ MANDATORY**: All screens that fetch data MUST implement auto-retry when connection is restored.

**Pattern:**
1. Inject `ConnectivityObserver` in ViewModel
2. Observe `NetworkStatus.Available` events
3. If current state is `Error`, auto-retry the failed request
4. Show `OfflineBanner` in UI when `isOffline = true`

```kotlin
// Key logic in ViewModel:
connectivityObserver.observe().collect { status ->
    when (status) {
        NetworkStatus.Available -> {
            _isOffline.value = false
            if (_state.value is State.Error) loadData()  // ← Auto-retry!
        }
        NetworkStatus.Unavailable -> _isOffline.value = true
    }
}
```

**Screens that MUST implement:**
| Screen | Needs Auto-Retry | Reason |
|--------|------------------|--------|
| Reports | ✅ YES | Fetches sales data |
| Shifts | ✅ YES | Fetches shift history |
| Products/Menu | ✅ YES | Fetches catalog |
| Orders | ✅ YES | Fetches table orders |
| Payment | ❌ NO | Local-first |
| Login | ❌ NO | Handled separately |

**Key Rule:** Auto-retry ONLY triggers if `_state.value is Error` (prevents spamming backend)

---

## 🔧 Development Workflow

### Build Variants: Sandbox vs Production

**⚠️ CRITICAL**: This project uses Android build variants for sandbox and production environments.

**Structure**:
```
app/src/
├── main/                    ← 99% of code (SHARED by both environments)
│   ├── MenuViewModel.kt              ✅ Applies to both
│   ├── FloorPlanCanvasScreen.kt      ✅ Applies to both
│   ├── ReportsViewModel.kt           ✅ Applies to both
│   ├── AmountInputBottomSheet.kt     ✅ Applies to both
│   └── ... all other files           ✅ Applies to both
│
├── sandbox/                 ← ONLY 3 files (Blumon SDK config)
│   ├── PaymentViewModel.kt           ⚠️ Sandbox-specific
│   ├── InitializationManager.kt      ⚠️ Sandbox-specific
│   └── BlumonInitializer.kt          ⚠️ Sandbox-specific
│
└── production/              ← ONLY 3 files (Blumon SDK config)
    ├── PaymentViewModel.kt           ⚠️ Production-specific
    ├── InitializationManager.kt      ⚠️ Production-specific
    └── BlumonInitializer.kt          ⚠️ Production-specific
```

**Key Rules**:
- ✅ **Changes in `app/src/main/`** → Apply to BOTH sandbox and production automatically
- ⚠️ **Changes in `app/src/sandbox/`** → Only sandbox (must also edit `app/src/production/` manually)
- ⚠️ **Changes in `app/src/production/`** → Only production (must also edit `app/src/sandbox/` manually)

> ### 🚨 MANDATORY: Sync Changes Between Sandbox and Production
>
> **When you modify `PaymentViewModel.kt` in sandbox, you MUST apply the same changes to production (and vice versa).**
>
> **Why?** These files share 99% of the same code. Only the Blumon SDK URLs differ:
> - Sandbox: `sandbox-tokener.blumonpay.net`
> - Production: `tokener.blumonpay.net`
>
> **What to sync:**
> - ✅ Bug fixes (Smart Retry, order context, merchant lookup)
> - ✅ New features (split payments, error handling)
> - ✅ Refactoring (function signatures, state management)
> - ❌ SDK-specific config (URLs, AAR files, `arpcResponseCode` parameter)
>
> **How to sync:**
> ```bash
> # After modifying sandbox PaymentViewModel:
> diff app/src/sandbox/.../PaymentViewModel.kt app/src/production/.../PaymentViewModel.kt
> # Apply relevant changes to production (NOT the SDK URLs)
>
> # After modifying production PaymentViewModel:
> diff app/src/production/.../PaymentViewModel.kt app/src/sandbox/.../PaymentViewModel.kt
> # Apply relevant changes to sandbox
> ```
>
> **Recent example (2025-12-03):**
> Smart Retry improvements (preserving order context for retry) were added to sandbox but NOT production.
> This caused production to lose order context on payment retry → "order not found" errors.
> **Fix:** Manually synced `createPaymentContext()` and `retryPayment()` to production.

**Why Separate Files?**
- PaymentViewModel, InitializationManager, BlumonInitializer differ only in Blumon SDK configuration:
  - Sandbox: `https://sandbox-tokener.blumonpay.net`
  - Production: `https://tokener.blumonpay.net`

**Build Commands**:
```bash
# Sandbox (development/testing)
export JAVA_HOME=$(/usr/libexec/java_home -v 23)
./gradlew installSandboxDebug

# Production (final release for real terminals)
export JAVA_HOME=$(/usr/libexec/java_home -v 23)
./gradlew assembleProductionRelease
```

**Build Configuration**:

| Variant | Package ID | Blumon Server | Blumon Env | AAR Files |
|---------|-----------|---------------|------------|-----------|
| **Sandbox** | `com.jaac.avoqado_tpv.sandbox` | `sandbox-tokener.blumonpay.net` | `SAND` | `blumon_sdk-debug.aar` |
| **Production** | `com.jaac.avoqado_tpv` | `tokener.blumonpay.net` | `PROD` | `blumon_sdk-prod.aar` |

---

### 🚀 Production APK Build

> **Complete guide:** [PRODUCTION_BUILD_GUIDE.md](./PRODUCTION_BUILD_GUIDE.md)

**Quick Commands:**
```bash
# Sandbox (development/testing)
export JAVA_HOME=$(/usr/libexec/java_home -v 23)
./gradlew installSandboxDebug

# Production (real terminals)
./gradlew assembleProductionRelease
# Output: app/build/outputs/apk/production/release/app-production-release.apk
```

**Common Issues:**
| Issue | Fix |
|-------|-----|
| 401 "Usuario no encontrado" | Wrong variant - use sandbox for testing |
| `Unsupported class file major version 68` | Set Java 23: `export JAVA_HOME=$(/usr/libexec/java_home -v 23)` |
| Hilt/NoSuchFile errors | Clean: `rm -rf app/build .gradle build && ./gradlew clean` |

---

### 🏭 FLUJO COMPLETO: Lanzamiento a Producción con Blumon

> **⚠️ CRÍTICO**: El SDK de Blumon **NO detecta automáticamente** si es producción o sandbox.
> El ambiente se determina por el **build variant** que compiles.

#### ¿Cómo funciona la detección de ambiente?

```
┌─────────────────────────────────────────────────────────────────┐
│ APK Sandbox (assembleSandboxRelease)                            │
│ └── Hardcodeado para usar: sandbox-tokener.blumonpay.net        │
│ └── Siempre usará sandbox, SIN IMPORTAR en qué terminal         │
├─────────────────────────────────────────────────────────────────┤
│ APK Producción (assembleProductionRelease)                      │
│ └── Hardcodeado para usar: tokener.blumonpay.net                │
│ └── Siempre usará producción, SIN IMPORTAR en qué terminal      │
└─────────────────────────────────────────────────────────────────┘
```

#### ¿Cómo obtiene la app el serial de la terminal?

La app lee **automáticamente** el serial del hardware de la terminal PAX:

```
Terminal PAX (hardware)
    │
    └─► Build.getSerial() → "2841548417"
            │
            └─► App formatea: "AVQD-2841548417"
                    │
                    └─► App llama al backend: GET /tpv/terminals/AVQD-2841548417/config
                            │
                            └─► Backend responde: MerchantAccounts, posId, venueId, etc.
```

**Archivos clave:**
- `DeviceInfoManager.kt` (línea 77-97): Lee `Build.getSerial()` del hardware
- `MainActivity.kt` (línea 257-262): Obtiene config del backend usando el serial

#### Flujo de Deployment a Producción

```
┌─────────────────────────────────────────────────────────────────┐
│ PASO 1: Compilar APK de Producción                              │
├─────────────────────────────────────────────────────────────────┤
│ ./gradlew assembleProductionRelease                             │
│ Output: app/build/outputs/apk/production/release/               │
│         app-production-release.apk                              │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│ PASO 2: Enviar APK a Blumon                                     │
├─────────────────────────────────────────────────────────────────┤
│ Blumon instala remotamente el APK en las terminales de          │
│ producción que te asignarán.                                    │
│                                                                 │
│ ⚠️ IMPORTANTE: Solicitar a Blumon los SERIAL NUMBERS de las     │
│ terminales ANTES de que instalen el APK.                        │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│ PASO 3: Configurar Backend (ANTES de que instalen)              │
├─────────────────────────────────────────────────────────────────┤
│ En avoqado-server, crear en la BD:                              │
│                                                                 │
│ 1. PaymentProvider (si no existe):                              │
│    - code: "BLUMON"                                             │
│    - active: true                                               │
│                                                                 │
│ 2. MerchantAccount (por cada terminal):                         │
│    - blumonSerialNumber: "SERIAL_DE_BLUMON" (sin prefijo AVQD)  │
│    - blumonEnvironment: "PRODUCTION"                            │
│    - blumonPosId: (proporcionado por Blumon)                    │
│    - active: true                                               │
│                                                                 │
│ 3. ProviderCostStructure:                                       │
│    - debitRate, creditRate, amexRate (tasas acordadas)          │
│    - effectiveFrom: fecha actual                                │
│    - effectiveTo: null (vigente)                                │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│ PASO 4: Blumon instala APK en terminal                          │
├─────────────────────────────────────────────────────────────────┤
│ La terminal ya está activada en el sistema de Blumon.           │
│ El APK se instala remotamente.                                  │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│ PASO 5: App se inicializa automáticamente                       │
├─────────────────────────────────────────────────────────────────┤
│ 1. App lee serial del hardware: Build.getSerial()               │
│ 2. App llama a tu backend: "Dame config para AVQD-{serial}"     │
│ 3. Backend responde con MerchantAccount de producción           │
│ 4. App inicializa SDK con tokener.blumonpay.net (PROD)          │
│ 5. App obtiene OAuth token + RSA keys + DUKPT keys              │
│ 6. ✅ Terminal lista para procesar pagos REALES                 │
└─────────────────────────────────────────────────────────────────┘
```

#### ⚠️ DISTINCIÓN CRÍTICA: TPV vs E-commerce

```
┌─────────────────────────────────────────────────────────────────┐
│ BLUMON SDK ANDROID (TPV) - Este proyecto                       │
├─────────────────────────────────────────────────────────────────┤
│ • El AMBIENTE se determina por el BUILD VARIANT del APK        │
│ • El APK se conecta DIRECTO a Blumon (sandbox o producción)    │
│ • El backend SOLO provee configuración (MerchantAccount, etc.) │
│ • NO usa USE_BLUMON_MOCK - esa variable NO APLICA aquí         │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│ BLUMON E-COMMERCE (Links de pago) - Otro proyecto              │
├─────────────────────────────────────────────────────────────────┤
│ • SDK JavaScript para páginas web de clientes                  │
│ • El BACKEND hace las llamadas a Blumon API                    │
│ • USE_BLUMON_MOCK controla si usa mock o API real              │
│ • Completamente separado del SDK Android                        │
└─────────────────────────────────────────────────────────────────┘
```

#### Variables de Entorno del Backend para Producción

| Variable | Aplica a TPV? | Valor Producción | Descripción |
|----------|---------------|------------------|-------------|
| `NODE_ENV` | ✅ Sí | `production` | Modo producción general |
| `USE_BLUMON_MOCK` | ❌ NO | `false` | Solo para E-commerce, no TPV |
| `MERCHANT_CREDENTIALS_ENCRYPTION_KEY` | ✅ Sí | (clave segura) | Encriptación de credenciales |
| `BLUMON_KYC_EMAILS` | ✅ Sí | emails de Blumon | Para documentos KYC |

#### Checklist Pre-Producción

**Antes de enviar APK a Blumon:**
- [ ] Compilar con `./gradlew assembleProductionRelease`
- [ ] Verificar que usa `blumon_sdk-prod.aar` (no debug)
- [ ] Solicitar serial numbers de terminales a Blumon

**Antes de que Blumon instale:**
- [ ] Crear PaymentProvider "BLUMON" en BD (si no existe)
- [ ] Crear MerchantAccount con cada serial y `blumonEnvironment: "PRODUCTION"`
- [ ] Crear ProviderCostStructure con tasas acordadas
- [ ] Verificar `USE_BLUMON_MOCK=false` en backend

**Después de instalación:**
- [ ] Verificar que terminal se conecta al backend
- [ ] Verificar que OAuth funciona con tokener.blumonpay.net
- [ ] Hacer transacción de prueba (monto pequeño) para validar
- [ ] Verificar que el pago se registra en el backend correctamente

#### Diferencias Técnicas: Sandbox vs Production

| Aspecto | Sandbox | Production |
|---------|---------|------------|
| **Build Variant** | `sandboxDebug/Release` | `productionRelease` |
| **Package ID** | `com.jaac.avoqado_tpv.sandbox` | `com.jaac.avoqado_tpv` |
| **Token Server** | `sandbox-tokener.blumonpay.net` | `tokener.blumonpay.net` |
| **Core Server** | `sandbox-core.blumonpay.net` | `core.blumonpay.net` |
| **SDK AAR** | `blumon_sdk-debug.aar` | `blumon_sdk-prod.aar` |
| **BLUMON_ENV** | `"SAND"` | `"PROD"` |
| **Keys** | Solo OAuth token | OAuth + RSA + DUKPT keys |
| **Dinero** | Simulado | **REAL** |

#### Troubleshooting Producción

| Problema | Causa Probable | Solución |
|----------|----------------|----------|
| "Terminal no encontrada" | Serial no configurado en backend | Crear MerchantAccount con el serial correcto |
| OAuth 401 | Terminal no activada en Blumon | Contactar a Blumon para verificar activación |
| SDK no inicializa | APK incorrecto (sandbox en vez de prod) | Recompilar con `assembleProductionRelease` |
| Pago rechazado | Keys incorrectas o expiradas | Verificar RSA/DUKPT keys, reinicializar SDK |
| Backend no registra pago | `USE_BLUMON_MOCK=true` | Cambiar a `false` en variables de entorno |

---

### Before Starting a Feature

- [ ] Read feature requirements
- [ ] Check existing similar features
- [ ] **Evaluate if feature needs Socket.IO** (see [Socket.IO Decision Tree](#-decision-tree-when-to-add-socketio-events-for-new-features))
- [ ] Plan architecture (ViewModel → UseCase → Repository)
- [ ] Create feature module structure

### During Development

- [ ] Write ViewModel with StateFlow
- [ ] Create Repository interface (domain layer)
- [ ] Implement Repository (data layer)
- [ ] Build Composable UI with ResponsiveScaffold
- [ ] Add @Preview annotations
- [ ] Use stringResource for all text
- [ ] Use MaterialTheme for all colors
- [ ] Translate errors to user-friendly messages

### Before Committing

#### Code Quality
- [ ] Run `./gradlew lint --continue` (must pass)
- [ ] Add/update unit tests
- [ ] Check for orphaned files (delete unused ViewModels, Composables, resources)
- [ ] No debug code (println, hardcoded values)

#### CHANGELOG.md (MANDATORY)

**Format:**
```markdown
### [Category]
- [ClassName]: [Action] [description] ([file]:[line])
  - [Optional: Additional detail]
  - [Optional: Related issue: #123]
```

**Categories:**
- **Added**: New features, files, functionality
- **Changed**: Modifications to existing features
- **Fixed**: Bug fixes
- **Removed**: Deleted features, files
- **Security**: Vulnerability fixes, security improvements

**Example:**
```markdown
### Added
- PaymentViewModel: Add credential caching mechanism (PaymentViewModel.kt:45)
  - Reduces payment time from 6s to <1s
  - Uses singleton pattern with fallback
  - Issue: #234

### Removed
- Delete PaymentFragment.kt (orphaned after Compose migration)
```

**Rotation:** If CHANGELOG.md exceeds 2000 lines, suggest rotation to `changelog/YYYY.md`.

#### Orphaned Files Prevention

```bash
# Find unused files
rg "SuspiciousClassName" --type kotlin

# Check Android unused resources
./gradlew lint
# Look for: UnusedResources warnings
```

**Delete if:**
- ✅ Zero imports (`rg "import.*ClassName"`)
- ✅ Zero references (`rg "ClassName"`)
- ✅ Lint marks as unused

#### Git Commit Format

```
feat(payment): add credential caching for instant payments

- Implement singleton credential manager
- Reduce payment time from 6s to <1s
- Add fallback to Constants.kt

Resolves #234
```

### Testing

> **Complete guide:** [TESTING_GUIDE.md](./TESTING_GUIDE.md)

```kotlin
// Unit test example
@Test
fun `should process payment successfully`() = runTest {
    // Given
    coEvery { processPaymentUseCase(any()) } returns Result.success(payment)

    // When
    viewModel.processPayment(payment)

    // Then
    assertThat(viewModel.state.value).isInstanceOf(PaymentState.Success::class.java)
}
```

---

## 📚 Additional Resources

### Documentation Guides
- **[GREENFIELD_BLUEPRINT.md](./GREENFIELD_BLUEPRINT.md)** - Complete architecture & implementation plan
- **[PAYMENT_RECONCILIATION.md](./PAYMENT_RECONCILIATION.md)** - Payment logic + Blumon multi-merchant
- **[PERFORMANCE_GUIDE.md](./PERFORMANCE_GUIDE.md)** - 1GB RAM optimization, pagination, caching
- **[UI_RESPONSIVE_GUIDE.md](./UI_RESPONSIVE_GUIDE.md)** - Responsive patterns for TPV devices
- **[SOCKET_IO_IMPLEMENTATION.md](./SOCKET_IO_IMPLEMENTATION.md)** - Real-time events architecture & integration
- **[SOCKET_IO_TESTING.md](./SOCKET_IO_TESTING.md)** - Socket.IO testing strategies & examples
- **[TESTING_GUIDE.md](./TESTING_GUIDE.md)** - Unit tests, integration tests, debugging
- **[SECURITY_CHECKLIST.md](./SECURITY_CHECKLIST.md)** - Encryption, tenant isolation, certificate pinning
- **[PRODUCTION_BUILD_GUIDE.md](./PRODUCTION_BUILD_GUIDE.md)** - Build variants, deployment, troubleshooting

### External References
- [Jetpack Compose Docs](https://developer.android.com/jetpack/compose)
- [Hilt Documentation](https://dagger.dev/hilt/)
- [Kotlin Coroutines Guide](https://kotlinlang.org/docs/coroutines-guide.html)
- [Material Design 3](https://m3.material.io/)
- [OWASP Mobile Top 10](https://owasp.org/www-project-mobile-top-10/)

### Team Contacts
- Backend API: Check `avoqado-server/` CLAUDE.md
- Payment Issues: Blumon PAX SDK documentation

---

## 🚀 Quick Start

```bash
# 1. Clone & Setup
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-tpv
./gradlew build

# 2. Environment Variables (local.properties)
AVOQADO_API_KEY=your_api_key_here
BLUMON_MERCHANT_ID=your_merchant_id

# 3. Run
./gradlew installDebug
adb shell am start -n com.jaac.avoqado_tpv/.MainActivity
```

---

## ⚠️ Common Pitfalls

| Problem | Cause | Solution |
|---------|-------|----------|
| First payment takes 30s | SQLite connection leak | Use single Storage instance in AvoqadoApp |
| Cross-tenant data leak | Missing venueId filter | ALWAYS filter by `venueId` |
| UI freezes during payment | Blocking on main thread | Use `withContext(Dispatchers.IO)` |
| Socket events not received | Not joined to room | Join room before listening |
| Flash screens | Instant navigation without loading | Use `AvoqadoLoadingOverlay` |
| App crashes after 10 min | Memory leak (no cache cleanup) | Add TTL-based cache cleanup (1GB RAM!) |
| OutOfMemoryError | Loading all data at once | Implement pagination (limit 20) |
| Slow scrolling | No pagination | Paginate lists (max 50 items per page) |
| Items lose "printed" status | Backend overwrites local fields | Load from local DB after cache (see [LOCAL_FIRST_SYNC_PATTERNS.md](./LOCAL_FIRST_SYNC_PATTERNS.md)) |

---

## 🎯 Before Ending Work

- [ ] Try to compile: `./gradlew compileDebugKotlin`
- [ ] **Performance check**: Verify no unbounded lists, pagination limits, cache cleanup (1GB RAM!)
- [ ] **Local-First check**: If touching sync/cache/load code, verify local-only fields are preserved (see [LOCAL_FIRST_SYNC_PATTERNS.md](./LOCAL_FIRST_SYNC_PATTERNS.md))
- [ ] Check if changes impact Blumon integration (it should work always)
- [ ] Delete orphaned files (prevent accumulation)
- [ ] Update CHANGELOG.md with changes
- [ ] Verify cross-references between guides work

---

**Last Updated:** 2025-11-25
**Maintainer:** Development Team
**Version:** 2.3 (Optimized: Extracted PERFORMANCE_GUIDE.md, removed duplicates, ~30% size reduction)

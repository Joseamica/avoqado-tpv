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
- 🌐 **Backend:** Production: `https://api.avoqado.io/api/v1/` | Dev: `https://humane-immortal-pika.ngrok-free.app`
- 🔌 **Real-time:** Socket.IO (room-based events)
- 💳 **Payments:** Blumon PAX SDK (multi-merchant support)

### Specialized Guides (Deep Dives)
- 🚀 **[GREENFIELD_BLUEPRINT.md](./GREENFIELD_BLUEPRINT.md)** - Complete architecture & 28-day implementation plan
- 💰 **[PAYMENT_RECONCILIATION.md](./PAYMENT_RECONCILIATION.md)** - Payment logic + Blumon multi-merchant system
- 📱 **[UI_RESPONSIVE_GUIDE.md](./UI_RESPONSIVE_GUIDE.md)** - Responsive patterns for TPV devices (PAX A80, A920)
- 🔌 **[SOCKET_IO_IMPLEMENTATION.md](./SOCKET_IO_IMPLEMENTATION.md)** - Real-time events architecture & integration
- 🧪 **[TESTING_GUIDE.md](./TESTING_GUIDE.md)** - Unit tests, integration tests, debugging tools
- 🧪 **[SOCKET_IO_TESTING.md](./SOCKET_IO_TESTING.md)** - Socket.IO testing strategies & examples
- 🔐 **[SECURITY_CHECKLIST.md](./SECURITY_CHECKLIST.md)** - Encryption, tenant isolation, certificate pinning

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

##### ✅ ALWAYS Use Socket.IO For:

1. **Multi-terminal payment coordination** (CRITICAL)
   - Scenario: Terminal A processes payment → Terminal B shows order as paid
   - Event: `payment_completed`, `payment_failed`

2. **Order status changes** (HIGH PRIORITY)
   - Scenario: Kitchen marks order ready → Waiter notified
   - Event: `order_updated`, `order_status_changed`

3. **System alerts** (CRITICAL)
   - Scenario: Backend detects issue → All terminals show warning
   - Event: `system_alert` (levels: info, warning, error, critical)

4. **Admin remote control** (SECURITY)
   - Scenario: Admin puts terminal in maintenance mode from dashboard
   - Event: `tpv_command` (MAINTENANCE_MODE, RELOAD, DISABLE, SHUTDOWN)

5. **Inventory stock alerts** (BUSINESS LOGIC)
   - Scenario: Ingredient runs low → Kitchen and cashiers notified
   - Event: `inventory_low_stock`, `inventory_out_of_stock`

6. **Hardware status updates** (OPERATIONAL)
   - Scenario: Printer runs out of paper → Terminal shows alert
   - Event: `printer_status`, `card_reader_status`, `peripheral_error`

##### ❌ DON'T Use Socket.IO For:

1. **Historical data fetching** → Use REST API
2. **One-time authentication** → Use REST API
3. **Static menu/product lists** → Use REST API with cache
4. **Report generation** → Use REST API polling
5. **File uploads/downloads** → Use REST API with progress tracking
6. **Search queries** → Use REST API with debouncing

##### ⚠️ ASK USER Before Adding Socket.IO For:

1. **Table reservations** (depends on restaurant workflow)
2. **Customer notifications** (might use push notifications instead)
3. **Employee shift changes** (might not need instant updates)
4. **Menu item price changes** (depends on how often prices change)

##### How to Add a New Socket.IO Event

**Step 1: Define Event in Server** (`avoqado-server/src/communication/sockets/types/index.ts`)
```typescript
export enum SocketEventType {
  // ... existing events
  NEW_FEATURE_EVENT = 'new_feature_event',
}

export interface NewFeatureEventPayload extends BaseEventPayload {
  featureId: string
  data: any
  venueId: string
}
```

**Step 2: Add Broadcasting Method** (`avoqado-server/src/communication/sockets/services/broadcasting.service.ts`)
```typescript
public broadcastNewFeatureEvent(
  venueId: string,
  data: Omit<NewFeatureEventPayload, 'correlationId' | 'timestamp' | 'venueId'>,
  options?: BroadcastOptions,
): void {
  this.broadcastToVenue(venueId, SocketEventType.NEW_FEATURE_EVENT, {
    ...data,
    venueId,
    correlationId: randomUUID(),
    timestamp: new Date().toISOString(),
  }, options)
}
```

**Step 3: Add Event to Android** (`avoqado-tpv/app/src/main/java/com/jaac/avoqado_tpv/core/data/realtime/events/SocketEvent.kt`)
```kotlin
sealed interface SocketEvent {
    // ... existing events

    data class NewFeature(
        val featureId: String,
        val data: Map<String, Any>,
        val venueId: String,
        val timestamp: String,
        val metadata: Map<String, Any>? = null
    ) : SocketEvent
}
```

**Step 4: Add Event Listener** (`SocketManager.kt`)
```kotlin
socket.on("new_feature_event") { args ->
    try {
        val json = args[0] as JSONObject
        val event = SocketEvent.NewFeature(
            featureId = json.getString("featureId"),
            data = json.getJSONObject("data").toMap(),
            venueId = json.getString("venueId"),
            timestamp = json.getString("timestamp"),
            metadata = json.optJSONObject("metadata")?.toMap()
        )
        emitEvent(event)
    } catch (e: Exception) {
        Timber.e(e, "Error parsing new_feature_event")
    }
}
```

**Step 5: Handle in ViewModel**
```kotlin
private fun collectSocketEvents() {
    viewModelScope.launch {
        socketManager.events.collect { event ->
            when (event) {
                is SocketEvent.NewFeature -> {
                    Timber.i("✅ New feature event: ${event.featureId}")
                    handleNewFeature(event)
                }
                else -> {}
            }
        }
    }
}
```

**Step 6: Add Unit Tests** (see [SOCKET_IO_TESTING.md](./SOCKET_IO_TESTING.md))
```kotlin
@Test
fun `should parse new_feature_event correctly`() = runTest(testDispatcher) {
    val json = JSONObject().apply {
        put("featureId", "feature_123")
        put("data", JSONObject().apply { put("key", "value") })
        put("venueId", "venue_789")
        put("timestamp", "2025-01-15T10:30:00Z")
    }

    capturedListeners["new_feature_event"]?.call(json)

    socketManager.events.test {
        val event = awaitItem()
        assertThat(event).isInstanceOf(SocketEvent.NewFeature::class.java)
        cancelAndIgnoreRemainingEvents()
    }
}
```

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

---

## 🔧 Development Workflow

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
- **[UI_RESPONSIVE_GUIDE.md](./UI_RESPONSIVE_GUIDE.md)** - Responsive patterns for TPV devices
- **[SOCKET_IO_IMPLEMENTATION.md](./SOCKET_IO_IMPLEMENTATION.md)** - Real-time events architecture & integration
- **[SOCKET_IO_TESTING.md](./SOCKET_IO_TESTING.md)** - Socket.IO testing strategies & examples
- **[TESTING_GUIDE.md](./TESTING_GUIDE.md)** - Unit tests, integration tests, debugging
- **[SECURITY_CHECKLIST.md](./SECURITY_CHECKLIST.md)** - Encryption, tenant isolation, certificate pinning

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

---

## 🎯 Before Ending Work

- [ ] Try to compile: `./gradlew compileDebugKotlin`
- [ ] Check if changes impact Blumon integration (it should work always)
- [ ] Delete orphaned files (prevent accumulation)
- [ ] Update CHANGELOG.md with changes
- [ ] Verify cross-references between guides work

---

**Last Updated:** 2025-01-15
**Maintainer:** Development Team
**Version:** 2.1 (Added: Socket.IO integration guidelines & decision tree)

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
- ⚡ **Performance:** 1GB RAM target (PAX A80) - ALWAYS paginate, cleanup cache, avoid heavy animations

### Specialized Guides (Deep Dives)
- 🚀 **[GREENFIELD_BLUEPRINT.md](./GREENFIELD_BLUEPRINT.md)** - Complete architecture & 28-day implementation plan
- 💰 **[PAYMENT_RECONCILIATION.md](./PAYMENT_RECONCILIATION.md)** - Payment logic + Blumon multi-merchant system
- 📱 **[UI_RESPONSIVE_GUIDE.md](./UI_RESPONSIVE_GUIDE.md)** - Responsive patterns for TPV devices (PAX A80, A920)
- 🔌 **[SOCKET_IO_IMPLEMENTATION.md](./SOCKET_IO_IMPLEMENTATION.md)** - Real-time events architecture & integration
- 🧪 **[TESTING_GUIDE.md](./TESTING_GUIDE.md)** - Unit tests, integration tests, debugging tools
- 🧪 **[SOCKET_IO_TESTING.md](./SOCKET_IO_TESTING.md)** - Socket.IO testing strategies & examples
- 🔐 **[SECURITY_CHECKLIST.md](./SECURITY_CHECKLIST.md)** - Encryption, tenant isolation, certificate pinning

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

## ⚡ Performance Guidelines (1GB RAM Devices)

> **CRITICAL**: Target devices (PAX A80, A920, Sunmi T2s) have **1GB RAM**. ALWAYS optimize for low memory footprint.

### 🎯 Mandatory Performance Rules

#### 1. **Pagination ALWAYS (Never load all data at once)**

```kotlin
// ❌ WRONG: Loads all orders in memory (OOM risk on 1GB RAM)
val orders = orderRepository.getAllOrders(venueId)  // Could be 10,000+ orders!

// ✅ CORRECT: Pagination with reasonable limits
val orders = orderRepository.getOrders(
    venueId = venueId,
    limit = 20,  // ← Limit per page
    cursor = cursor  // ← Cursor-based pagination
)
```

**Pagination Limits**:
- **Lists**: 20 items per page (max 50)
- **Historical data**: 20 periods per page
- **Images**: 10 items per page (heavy memory)
- **Search results**: 15 items per page

#### 2. **Cache Cleanup (Prevent memory leaks)**

```kotlin
// ✅ CORRECT: Auto-cleanup old cache
suspend fun cleanupOldCache() {
    val cutoffTime = System.currentTimeMillis() - CACHE_TTL_MILLIS
    historicalPeriodDao.deleteOldPeriods(cutoffTime)  // Delete stale data

    Timber.d("🧹 [Cache Cleanup] Freed memory from old cache")
}
```

**Cleanup Rules**:
- **TTL-based**: Delete data older than 24h (historical cache)
- **Size-based**: Limit cache to max 500 entries
- **On logout**: Clear all venue-specific cache
- **On low memory**: Android system triggers `onTrimMemory()`

#### 3. **Lazy Loading (Load data only when needed)**

```kotlin
// ❌ WRONG: Loads all data upfront
LaunchedEffect(Unit) {
    val allProducts = productRepository.getAllProducts()  // 1000+ products!
    val allOrders = orderRepository.getAllOrders()        // 5000+ orders!
}

// ✅ CORRECT: Lazy load on demand
LaunchedEffect(selectedCategory) {
    val products = productRepository.getProductsByCategory(
        categoryId = selectedCategory,
        limit = 20
    )
}
```

#### 4. **StateFlow Instead of State (Memory efficient)**

```kotlin
// ❌ WRONG: State creates recomposition for every field change
data class UiState(
    val orders: List<Order> = emptyList(),  // Entire list recomposed on change
    val isLoading: Boolean = false,
    val error: String? = null
)

// ✅ CORRECT: StateFlow with immutable data
private val _orders = MutableStateFlow<List<Order>>(emptyList())
val orders: StateFlow<List<Order>> = _orders.asStateFlow()

private val _isLoading = MutableStateFlow(false)
val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
```

#### 5. **Avoid Heavy Composables (No complex animations)**

```kotlin
// ❌ WRONG: Heavy animation on 1GB RAM device
AnimatedVisibility(
    visible = isVisible,
    enter = slideInVertically() + fadeIn() + scaleIn(),  // ← Too heavy!
    exit = slideOutVertically() + fadeOut() + scaleOut()
) {
    ComplexContent()
}

// ✅ CORRECT: Simple fade (lightweight)
AnimatedVisibility(
    visible = isVisible,
    enter = fadeIn(),  // ← Simple, performant
    exit = fadeOut()
) {
    Content()
}

// ✅ BETTER: No animation (instant)
if (isVisible) {
    Content()
}
```

**Animation Rules**:
- **Avoid**: slideIn, scaleIn, expandVertically (allocate memory for transitions)
- **Use sparingly**: fadeIn, fadeOut (lightweight)
- **Prefer**: Instant show/hide (no memory overhead)

#### 6. **Efficient Data Structures**

```kotlin
// ❌ WRONG: Stores entire order object in map (high memory)
val orderMap = mutableMapOf<String, Order>()  // Order has 20+ fields!

// ✅ CORRECT: Store only IDs, fetch on demand
val selectedOrderIds = mutableSetOf<String>()  // Just strings

// When needed:
val order = orderRepository.getOrder(selectedOrderIds.first())
```

#### 7. **Image Loading (CRITICAL for 1GB RAM)**

```kotlin
// ❌ WRONG: Load full-size images (OOM risk)
Image(
    painter = rememberImagePainter(imageUrl),  // ← Loads full resolution!
    modifier = Modifier.size(100.dp)
)

// ✅ CORRECT: Request thumbnail/scaled version from backend
Image(
    painter = rememberImagePainter("$imageUrl?size=thumbnail"),  // ← Scaled
    modifier = Modifier.size(100.dp)
)
```

**Image Rules**:
- **Never** load images larger than display size
- **Always** request thumbnails from backend (query param: `?size=thumbnail`)
- **Limit** concurrent image loads to 3-5 at once
- **Use** Coil's built-in memory cache (max 50 images)

#### 8. **Room Database Queries (Index everything)**

```kotlin
// ❌ WRONG: No index (slow query on large tables)
@Entity(tableName = "orders")
data class OrderEntity(
    @PrimaryKey val id: String,
    val venueId: String,  // ← Queried often, but NO INDEX!
    val status: String
)

// ✅ CORRECT: Indexed columns for fast queries
@Entity(
    tableName = "orders",
    indices = [
        Index(value = ["venue_id"]),      // ← Fast venue filtering
        Index(value = ["status"]),        // ← Fast status filtering
        Index(value = ["created_at"])     // ← Fast time-based queries
    ]
)
data class OrderEntity(...)
```

**Indexing Rules**:
- **Index ALL** columns used in WHERE clauses
- **Composite index** for multi-column queries (venue_id, status)
- **Unique index** to prevent duplicates

#### 9. **Background Work (Dispatcher.IO)**

```kotlin
// ❌ WRONG: Blocking main thread (UI freeze)
fun loadData() {
    val data = database.query()  // ← Blocks UI thread!
    _state.value = State.Success(data)
}

// ✅ CORRECT: Background thread
suspend fun loadData() = withContext(Dispatchers.IO) {
    val data = database.query()  // ← Background thread
    withContext(Dispatchers.Main) {
        _state.value = State.Success(data)
    }
}
```

#### 10. **Avoid toString() on Large Objects**

```kotlin
// ❌ WRONG: Logs entire order object (memory + performance hit)
Timber.d("Order: $order")  // ← Creates string representation of entire object!

// ✅ CORRECT: Log only relevant fields
Timber.d("Order: id=${order.id}, total=${order.total}, status=${order.status}")
```

### 📊 Memory Budget Guidelines

| Feature | Max Memory | Notes |
|---------|-----------|-------|
| **Cached Orders** | 100 entries | ~2MB (20KB per order) |
| **Cached Products** | 500 entries | ~5MB (10KB per product) |
| **Cached Images** | 50 images | ~20MB (400KB per image) |
| **Historical Cache** | 200 periods | ~200KB (1KB per period) |
| **ViewModel State** | <5MB | Entire app state |
| **Total App RAM** | <200MB | Peak memory usage |

### 🧪 Performance Testing Checklist

Before committing, verify:

- [ ] **No unbounded lists** (all lists paginated with limit)
- [ ] **No memory leaks** (ViewModels cleared on destroy)
- [ ] **No blocking calls** on main thread (use `withContext(Dispatchers.IO)`)
- [ ] **Cache cleanup** implemented (TTL or size-based)
- [ ] **Indexes** on all queried columns
- [ ] **Images** scaled to display size
- [ ] **No heavy animations** (prefer instant or fade)
- [ ] **StateFlow** instead of mutable State
- [ ] **toString()** only on small objects

### 🐛 Common Performance Issues

| Symptom | Cause | Fix |
|---------|-------|-----|
| **App crashes after 10 min** | Memory leak (cache not cleaned) | Add TTL-based cache cleanup |
| **Slow scrolling** | Loading all data at once | Implement pagination (limit 20) |
| **UI freezes on tap** | Blocking main thread | Move to `Dispatchers.IO` |
| **OutOfMemoryError** | Large images or unbounded lists | Scale images, paginate lists |
| **Slow database queries** | Missing indexes | Add `@Index` to queried columns |

### 📱 Target Device Specs

| Device | RAM | Storage | CPU | Screen |
|--------|-----|---------|-----|--------|
| **PAX A80** | 1GB | 8GB | Quad-core 1.4GHz | 1024x600 (small) |
| **PAX A920** | 1GB | 16GB | Quad-core 1.5GHz | 1280x720 (medium) |
| **Sunmi T2s** | 2GB | 16GB | Octa-core 2.0GHz | 1280x800 (large) |

**Optimization Priority**: PAX A80 (smallest RAM/CPU) → If it works on A80, it works on all devices.

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

#### Auto-Retry on Reconnection

**⚠️ MANDATORY**: All screens that fetch data MUST implement auto-retry when connection is restored.

**Problem:**
User loses connection → Screen shows error → Connection restored → Screen STILL shows error (user must manually retry)

**Solution:**
Use `ConnectivityObserver` to detect reconnection and auto-retry.

**Implementation:**

```kotlin
// STEP 1: Inject ConnectivityObserver
@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val reportsRepository: ReportsRepository,
    private val connectivityObserver: ConnectivityObserver
) : ViewModel() {

    private val _state = MutableStateFlow<ReportsState>(ReportsState.Loading)
    val state: StateFlow<ReportsState> = _state.asStateFlow()

    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    init {
        loadReports()
        observeConnectivity()  // ← CRITICAL: Monitor network changes
    }

    // STEP 2: Observe connectivity and auto-retry
    private fun observeConnectivity() {
        viewModelScope.launch {
            connectivityObserver.observe().collect { status ->
                when (status) {
                    NetworkStatus.Available -> {
                        Timber.i("✅ [Connectivity] Connection restored")
                        _isOffline.value = false

                        // ⭐ AUTO-RETRY: If screen showed error, retry automatically
                        if (_state.value is ReportsState.Error) {
                            Timber.d("🔄 [Auto-Retry] Retrying failed request...")
                            loadReports()
                        }
                    }
                    NetworkStatus.Unavailable -> {
                        Timber.w("⚠️ [Connectivity] Connection lost")
                        _isOffline.value = true
                    }
                }
            }
        }
    }

    // STEP 3: Load data (will be auto-retried on reconnection)
    fun loadReports() {
        viewModelScope.launch {
            _state.value = ReportsState.Loading

            reportsRepository.getReports()
                .onSuccess { reports ->
                    _state.value = ReportsState.Success(reports)
                }
                .onFailure { error ->
                    _state.value = ReportsState.Error(error.message ?: "Error cargando reportes")
                }
        }
    }
}
```

**UI Pattern:**

```kotlin
@Composable
fun ReportsScreen(
    viewModel: ReportsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isOffline by viewModel.isOffline.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            Column {
                AvoqadoTopBar(title = "Reportes")

                // Show offline banner when connection lost
                if (isOffline) {
                    OfflineBanner(
                        message = "Trabajando sin conexión - Las ventas se guardarán localmente"
                    )
                }
            }
        }
    ) { paddingValues ->
        when (val currentState = state) {
            is ReportsState.Loading -> LoadingScreen()
            is ReportsState.Success -> ReportsContent(currentState.reports)
            is ReportsState.Error -> {
                ErrorScreen(
                    message = currentState.message,
                    onRetry = { viewModel.loadReports() }  // Manual retry button
                )
                // Auto-retry happens automatically when connection restored!
            }
        }
    }
}

@Composable
fun OfflineBanner(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                tint = Color.White
            )
            Text(
                text = message,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}
```

**Screens that MUST implement this pattern:**
- ✅ Reports (fetches sales data from backend)
- ✅ Shifts (fetches shift history)
- ✅ Products/Menu (fetches catalog)
- ✅ Orders (fetches table orders)
- ❌ Payment (local-first, doesn't need auto-retry)
- ❌ Login (handled separately)

**Benefits:**
- ✅ User doesn't need to manually retry after reconnection
- ✅ Seamless UX when connection flickers
- ✅ Offline banner clearly communicates network status
- ✅ Auto-retry only happens if screen is in error state (doesn't spam backend)

**IMPORTANT:**
- Auto-retry ONLY triggers if `_state.value is Error` (prevents spamming backend when data already loaded)
- Offline banner disappears when connection is restored
- Manual retry button still available (if user wants to retry before reconnection)

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

### 🚀 Production APK Build Guide

**When to build production APK:**
- Deploying to real PAX terminals (not test devices)
- Terminal serial numbers are registered in Blumon PRODUCTION server
- Ready for real customer payments

**Step-by-Step Process:**

#### 1. Pre-Build Verification
```bash
# Check current git status
git status

# Ensure all changes are committed
git add .
git commit -m "feat: prepare production build"

# Verify production BuildConfig
cat app/build.gradle.kts | grep -A 10 'create("production")'
```

**Expected output:**
```kotlin
create("production") {
    buildConfigField("String", "BLUMON_ENV", "\"PROD\"")
    buildConfigField("String", "TOKEN_SERVER_URL", "\"https://tokener.blumonpay.net\"")
    buildConfigField("String", "CORE_SERVER_URL", "\"https://core.blumonpay.net\"")
}
```

#### 2. Clean Build Environment
```bash
# Uninstall any test variants from device
adb uninstall com.jaac.avoqado_tpv.sandbox
adb uninstall com.jaac.avoqado_tpv

# Clean all build artifacts
./gradlew clean
rm -rf app/build app/.cxx .gradle build
```

#### 3. Build Production APK
```bash
# Set Java 23 (required for build compatibility)
export JAVA_HOME=$(/usr/libexec/java_home -v 23)

# Build RELEASE variant (signed, optimized)
./gradlew assembleProductionRelease

# OR for testing: Build DEBUG variant
./gradlew assembleProductionDebug
```

**Build output location:**
- **Release**: `app/build/outputs/apk/production/release/app-production-release.apk`
- **Debug**: `app/build/outputs/apk/production/debug/app-production-debug.apk`

#### 4. Verify Production APK
```bash
# Check APK exists
ls -lh app/build/outputs/apk/production/release/

# Verify package name and version
aapt dump badging app/build/outputs/apk/production/release/app-production-release.apk | grep -E "package:|versionName"
```

**Expected output:**
```
package: name='com.jaac.avoqado_tpv' versionCode='1' versionName='1.0.0'
```

**⚠️ CRITICAL CHECKS:**
- ✅ Package name is `com.jaac.avoqado_tpv` (NO `.sandbox` suffix)
- ✅ Version name is correct (e.g., `1.0.0`)
- ✅ APK size is ~25-30 MB

#### 5. Test Production APK (Optional - if you have a production-registered terminal)
```bash
# Install on device
adb install app/build/outputs/apk/production/release/app-production-release.apk

# Verify correct variant installed
adb shell pm list packages | grep avoqado
# Should show: package:com.jaac.avoqado_tpv

# Check app version
adb shell dumpsys package com.jaac.avoqado_tpv | grep versionName
# Should show: versionName=1.0.0
```

#### 6. Deploy to Production Terminals

**Option A: Manual Installation (USB)**
```bash
# Connect PAX terminal via USB
adb devices

# Install APK
adb install -r app/build/outputs/apk/production/release/app-production-release.apk
```

**Option B: Remote Distribution**
```bash
# Upload to server/CDN
scp app/build/outputs/apk/production/release/app-production-release.apk user@server:/path/

# Or use MDM (Mobile Device Management) system
# Follow your company's MDM deployment process
```

---

### ⚠️ Common Production Build Issues

#### Issue 1: Wrong Variant Installed During Testing
**Symptom:** 401 "Usuario no encontrado" when testing with sandbox serial numbers

**Cause:** Production variant installed, but using sandbox serial (2841548417) which only exists in sandbox Blumon server

**Solution:**
```bash
# Uninstall production variant
adb uninstall com.jaac.avoqado_tpv

# Install sandbox variant for testing
export JAVA_HOME=$(/usr/libexec/java_home -v 23)
./gradlew installSandboxDebug

# Verify sandbox installed
adb shell pm list packages | grep avoqado
# Should show: package:com.jaac.avoqado_tpv.sandbox
```

#### Issue 2: Java Version Mismatch
**Symptom:** `Unsupported class file major version 68`

**Solution:**
```bash
# Check current Java version
java -version

# List available Java versions
/usr/libexec/java_home -V

# Set Java 23 (recommended)
export JAVA_HOME=$(/usr/libexec/java_home -v 23)

# Verify
echo $JAVA_HOME
java -version
```

#### Issue 3: Build Cache Corruption
**Symptom:** Build fails with `NoSuchFileException` or Hilt errors

**Solution:**
```bash
# Stop Gradle daemon
./gradlew --stop

# Clean everything
rm -rf app/build app/.cxx .gradle build

# Rebuild
export JAVA_HOME=$(/usr/libexec/java_home -v 23)
./gradlew assembleProductionRelease
```

---

### 📋 Production Deployment Checklist

Before deploying production APK to real terminals:

**Pre-Deployment:**
- [ ] All code changes committed to git
- [ ] CHANGELOG.md updated with version changes
- [ ] Terminal serial numbers provisioned in Blumon PRODUCTION server
- [ ] Backend API endpoints configured for production (`https://api.avoqado.io`)
- [ ] Production merchant accounts configured in backend

**Build Verification:**
- [ ] Clean build environment (no cache corruption)
- [ ] Java 23 set: `export JAVA_HOME=$(/usr/libexec/java_home -v 23)`
- [ ] Build successful: `./gradlew assembleProductionRelease`
- [ ] APK package name verified: `com.jaac.avoqado_tpv` (no `.sandbox`)
- [ ] APK version correct

**Post-Deployment:**
- [ ] Test payment with real production terminal
- [ ] Verify Blumon authentication succeeds (no 401 errors)
- [ ] Test multi-merchant switching
- [ ] Verify payments sync to backend
- [ ] Test offline mode and sync recovery

---

**Before Committing**:
- [ ] If you modified PaymentViewModel/InitializationManager/BlumonInitializer:
  - [ ] Update BOTH `sandbox/` and `production/` versions
  - [ ] Verify changes are environment-appropriate (URLs, keys, configs)
- [ ] All other files: No special action needed (automatically shared)

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
| App crashes after 10 min | Memory leak (no cache cleanup) | Add TTL-based cache cleanup (1GB RAM!) |
| OutOfMemoryError | Loading all data at once | Implement pagination (limit 20) |
| Slow scrolling | No pagination | Paginate lists (max 50 items per page) |

---

## 🎯 Before Ending Work

- [ ] Try to compile: `./gradlew compileDebugKotlin`
- [ ] **Performance check**: Verify no unbounded lists, pagination limits, cache cleanup (1GB RAM!)
- [ ] Check if changes impact Blumon integration (it should work always)
- [ ] Delete orphaned files (prevent accumulation)
- [ ] Update CHANGELOG.md with changes
- [ ] Verify cross-references between guides work

---

**Last Updated:** 2025-01-19
**Maintainer:** Development Team
**Version:** 2.2 (Added: Performance guidelines for 1GB RAM devices + Historical reports offline cache)

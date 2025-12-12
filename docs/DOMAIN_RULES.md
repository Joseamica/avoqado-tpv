# Avoqado-Specific Domain Rules

**Purpose**: Domain-specific rules, patterns, and configurations for Avoqado TPV.

---

## 1. Payment Integration: Blumon PAX SDK

> **Complete guide:** `PAYMENT_RECONCILIATION.md`

### Critical Configuration

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

---

## 2. Multi-Merchant System (1 Device → N Merchants)

**Concept**: 1 physical PAX terminal can process payments for MULTIPLE merchants using virtual serial numbers.

```
Physical Terminal: AVQD-2841548417
├── Virtual Serial A: 2841548417 → Merchant A (BBVA, 1.5% rate)
└── Virtual Serial B: 2841548418 → Merchant B (Santander, 1.8% rate)
```

### Payment Flow

1. User selects merchant (UI button)
2. SDK reinitializes with new credentials (3-5 seconds)
3. Payment routes to correct posId/merchant
4. Backend records payment with `merchantAccountId`

### Critical Rule: Payment Source Separation

```kotlin
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

---

## 3. Backend Integration: REST API

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

---

## 4. Socket.IO: Real-time Events

> **Complete guides:**
> - **`SOCKET_IO_IMPLEMENTATION.md`** - Architecture & integration patterns
> - **`SOCKET_IO_TESTING.md`** - Testing strategies & examples

### Core Concept

Socket.IO provides real-time bidirectional communication for instant updates across multiple terminals/devices.

### Architecture

- Singleton `SocketManager` (Hilt injected)
- Auto-connects on login with JWT authentication
- Room-based event isolation (venue, table, order)
- SharedFlow for reactive event streaming

### Quick Example

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

---

## 5. Rate Limiting

> **Production vs Development Limits**

| Endpoint | Production | Development |
|----------|-----------|-------------|
| PIN Login | 10 attempts / 15 min | 100 attempts / 1 min |
| Activation | 5 attempts / 15 min | 50 attempts / 1 min |
| API Calls | 1000 req / hour | 10,000 req / hour |

### Android Error Handling (429)

```kotlin
429 -> {
    Timber.w("⚠️ Rate limit exceeded - Backend should have higher limits in DEV")
    "Demasiados intentos. Por favor espera un momento.\n\n" +
    "ℹ️ Si estás en desarrollo, el backend debe configurar rate limits más altos."
}
```

---

## 6. Security Patterns

> **Complete guide:** `SECURITY_CHECKLIST.md`

### Critical Rules (NON-NEGOTIABLE)

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

---

## 7. Dark Theme (Avoqado Dashboard Web Design)

**⚠️ IMPORTANT:** App ALWAYS uses Dark Mode by default (matches Avoqado Web Dashboard).

### Color Palette (OKLCH)

| Token | HEX | Usage |
|-------|-----|-------|
| **background** | `#1C1C1C` | Main background (deep charcoal) |
| **foreground** | `#FAFAFA` | Primary text (soft white) |
| **card** | `#2A2A2A` | Cards & elevated surfaces |
| **primary** | `#E8E8E8` | Primary buttons & accents |
| **error** | `#EB5757` | Errors & destructive actions |

### Usage

```kotlin
// ✅ ALWAYS use semantic colors
Text(color = MaterialTheme.colorScheme.primary)
Box(modifier = Modifier.background(MaterialTheme.colorScheme.surface))

// ❌ NEVER hardcode colors
Text(color = Color(0xFF2563EB))  // WRONG!
```

---

**Last Updated:** 2025-12-12

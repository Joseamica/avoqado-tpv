# Deep Analysis: Payment Processing Flow in Avoqado TPV

## Executive Summary

This document provides a **complete analysis of the payment processing flow** in avoqado-tpv (Android Kotlin app) and identifies the exact locations where `PAYMENT_INITIATED`, `PAYMENT_PROCESSING`, and `PAYMENT_FAILED` Socket.IO events should be emitted.

**Current State**: Only `PAYMENT_COMPLETED` is handled (received from server) at **PaymentViewModel.kt:372**. No events are currently being emitted FROM the app TO the server.

**Key Finding**: The app receives payment events from the server but doesn't emit them. The server needs to emit these events based on payments recorded in the backend database.

---

## Part 1: Payment State Machine

### File: `/app/src/main/java/com/jaac/avoqado_tpv/features/payment/domain/PaymentState.kt`

**Lines 72-142**: Sealed class defining 8 payment states

```kotlin
sealed class PaymentState {
    // Pre-payment states (USER INPUT)
    data class EnteringAmount(val amount: String = "") : PaymentState()
    data class CollectingRating(val amount: String, val rating: Int = 0) : PaymentState()
    data class CollectingTip(val amount: String, val rating: Int?, val tipAmount: String = "0") : PaymentState()
    data class SelectingMerchant(val subtotal: String, val tipAmount: String, val totalAmount: String, val rating: Int?) : PaymentState()

    // Idle state (legacy, redirects to EnteringAmount)
    data object Idle : PaymentState()

    // Payment processing states (BLUMON SDK)
    data object ConfiguringKernel : PaymentState()           // PASO 1: PreTrans
    data class DetectingCard(val amount: String) : PaymentState()  // PASO 2: StartDetectCard
    data class Processing(val message: String = "Procesando...") : PaymentState()  // PASO 3-4
    
    // Final states
    data class Success(val authCode: String, val amount: String, ...) : PaymentState()
    data class Error(val message: String, val context: RetryContext? = null, val canRetry: Boolean = true) : PaymentState()
    data object Cancelled : PaymentState()
    
    // Printing states (NEW)
    data object Printing : PaymentState()
    data class PrintError(val message: String, val previousState: Success) : PaymentState()
}
```

### State Transition Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    PAYMENT FLOW STATE MACHINE                            │
└─────────────────────────────────────────────────────────────────────────┘

    ┌─ IDLE
    │
    └─→ EnteringAmount (user types amount)
        │
        └─→ CollectingRating (optional, user skips or rates)
            │
            └─→ CollectingTip (optional, user skips or enters tip)
                │
                └─→ SelectingMerchant (choose merchant account)
                    │
                    └─→ ConfiguringKernel
                        │   (PASO 1: PreTrans - Initialize Blumon SDK kernel)
                        │
                        ├─→ ERROR → Error (preserve context for retry)
                        │
                        └─→ DetectingCard
                            │   (PASO 2: StartDetectCard - Wait for card tap)
                            │
                            ├─→ ERROR → Error (preserve context for retry)
                            │
                            └─→ Processing (message: "Procesando chip..." or "Procesando contactless...")
                                │
                                ├─→ PAYMENT FLOW (Chip vs Contactless)
                                │   ├─ Chip: PASO 3 (StartEmvTrans) → PASO 4 (SaleIcc online auth) → PASO 5 (CompleteEmvTrans)
                                │   └─ Contactless: PASO 3 (StartCtlssTransUseCase) → PASO 4 (SaleIcc online auth)
                                │
                                ├─→ ERROR → Error (preserve context for retry)
                                │
                                └─→ Success (payment approved by Blumon, backend recording in progress)
                                    │
                                    ├─→ [Background] recordPaymentUseCase (FastPaymentRecorder)
                                    │   ├─ Success → Update Success state with receipt
                                    │   └─ Failure → Queue for offline sync (PaymentSyncWorker)
                                    │
                                    └─→ [User sees Success] Can print receipt or return home
                                        │
                                        └─→ Printing → PrintError/Success
                                            │
                                            └─→ Return to home/Idle

    Error State (with retry context):
        └─→ User taps "Reintentar" → Back to DetectingCard (NOT EnteringAmount)
```

---

## Part 2: Payment Processing Flow - EXACT LOCATIONS

### OVERVIEW: Where Payments Are Created/Initiated/Failed

```
Phase              Location (File:Line)                    Function                State Set
────────────────────────────────────────────────────────────────────────────────────────────
1. User enters amount     PaymentViewModel.kt:517           submitAmount()           EnteringAmount
2. Kernel config          PaymentViewModel.kt:741           startPayment()           ConfiguringKernel
3. Card detection         PaymentViewModel.kt:802           startPayment()           DetectingCard
4. Chip/Contactless       PaymentViewModel.kt:847/1246      startPayment()           Processing
5. Online authorization   PaymentViewModel.kt:946           performOnlineAuthorization()  Processing
6. Success                PaymentViewModel.kt:1023          startPayment()           Success
7. Backend recording      PaymentViewModel.kt:1857          handlePaymentSuccess()   [Async]
8. Error/Failure          PaymentViewModel.kt:810+ (multiple)                       Error
```

### Detailed Breakdown by Phase

---

## Phase 1: Payment Initiation - PAYMENT_INITIATED Event

### When: User enters amount and taps "Continuar" 

**Exact Location**: `PaymentViewModel.kt:517-525`

```kotlin
fun submitAmount(amount: String) {
    Timber.d("📝 [Payment Flow] Amount submitted: $amount")
    _state.value = PaymentState.CollectingRating(amount = amount)
}
```

**Triggers**: User in `EnteringAmount` state → taps amount input field → enters amount → taps "Continuar"

**Event Should Emit**: `PAYMENT_INITIATED`
- **When**: IMMEDIATELY after user submits amount (before configuring kernel)
- **Where**: Right after line 519 in `submitAmount()` or in `startPayment()` at line 741
- **Data Available**:
  - `paymentId`: NOT YET - will be generated by backend on recording
  - `amount`: `currentAmount` variable (e.g., "30.00")
  - `currency`: "MXN" (hardcoded)
  - `venueId`: `currentVenueId` (from auth context)
  - `staffId`: `currentStaffId` (from auth context)
  - `merchantAccountId`: `_currentMerchant.value?.id`
  - `timestamp`: `System.currentTimeMillis()`

**Socket Event Definition** (Already exists):
```kotlin
data class PaymentInitiated(
    val paymentId: String,        // Will be backend-generated
    val amount: Int,              // in cents
    val currency: String,
    val tableId: String?,
    val orderId: String?,
    val venueId: String,
    val timestamp: String
) : SocketEvent
```

**⚠️ PROBLEM**: `paymentId` doesn't exist yet at this point. It's only generated when payment is recorded to backend.

**SOLUTION**: 
1. Generate temporary `paymentId` (UUID) in app
2. Pass it through the entire payment flow
3. Match it when recording to backend
4. Emit events with this ID so server can track single payment across multiple states

---

## Phase 2: Payment Processing - PAYMENT_PROCESSING Event

### When: Blumon SDK starts actual payment processing

**Exact Locations** (Multiple):
1. **ConfiguringKernel** - `PaymentViewModel.kt:741`
2. **StartDetectCard** - `PaymentViewModel.kt:802`
3. **StartEmvTrans** - `PaymentViewModel.kt:847` (Chip) or `PaymentViewModel.kt:1246` (Contactless)
4. **Online Authorization** - `PaymentViewModel.kt:946`

### Location Details

```kotlin
// LOCATION 1: ConfiguringKernel (PASO 1)
fun startPayment() {
    viewModelScope.launch {
        try {
            _state.value = PaymentState.ConfiguringKernel  // Line 741
            val preTransResult = preTransUseCase.run(params)
            
            if (preTransResult.isLeft) {
                _state.value = PaymentState.Error(message = "Error configurando kernel...", context = ...)  // Line 754-765
                return@launch
            }
```

**Line 741**: `_state.value = PaymentState.ConfiguringKernel`
- **Emit Point**: After this line
- **Event**: `PAYMENT_PROCESSING` (message: "Configurando terminal...")

```kotlin
// LOCATION 2: StartDetectCard (PASO 2)
_state.value = PaymentState.DetectingCard(currentAmount)  // Line 802
Timber.i("[PHASE 2] StartDetectCard - Waiting for card tap...")

// LOCATION 3: StartEmvTrans (PASO 3 - Chip)
_state.value = PaymentState.Processing("Procesando chip...")  // Line 848
Timber.i("[PHASE 3] StartEmvTrans - Processing EMV chip...")

// LOCATION 4: SaleIcc Online Auth (PASO 4)
_state.value = PaymentState.Processing("Autorizando con banco...")  // Line 946
Timber.i("[PHASE 4] SaleIcc - Sending to Momentum for ONLINE authorization...")
```

### Payment Processing Event Timeline

```
Timeline                    State                       Should Emit Event?
─────────────────────────────────────────────────────────────────────────
User submits amount    →    EnteringAmount             PAYMENT_INITIATED ✅
                      ↓
Configuring kernel     →    ConfiguringKernel         PAYMENT_PROCESSING ✅
                      ↓
Waiting for card       →    DetectingCard             (Still Processing)
                      ↓
Card detected          →    Processing("chip...")     (Still Processing)
                      ↓
Online auth            →    Processing("banco...")    (Still Processing)
                      ↓
Authorization success  →    Success                   PAYMENT_COMPLETED ✅
                      ↓
Backend recording      →    [Async handlePaymentSuccess]
                      ↓
Backend response ok    →    Update Success state
```

**Event to Emit**: `PAYMENT_PROCESSING`
- **When**: Just before `ConfiguringKernel` state
- **Where**: `PaymentViewModel.kt:741` or right after PreTrans succeeds at `PaymentViewModel.kt:795`
- **Data Available**:
  - `paymentId`: Generated temporary UUID
  - `amount`: `currentAmountInCents` (in cents, e.g., 3000 for $30)
  - `currency`: "MXN"
  - `venueId`: `currentVenueId`
  - `timestamp`: Current time

---

## Phase 3: Payment Failed - PAYMENT_FAILED Event

### Error Points (CRITICAL - Multiple Locations)

Payment can fail at any of these points:

| Phase | Location | Error Message | Emit PAYMENT_FAILED? |
|-------|----------|---------------|-------------------|
| **Kernel Config** | Line 754-765 | `_state.value = PaymentState.Error("Error configurando...")` | ✅ YES |
| **Card Detection** | Line 810-814 | `_state.value = PaymentState.Error("Error detectando tarjeta...")` | ✅ YES |
| **EMV Processing** | Line 856-860 | `_state.value = PaymentState.Error("Error procesando EMV...")` | ✅ YES |
| **Online Auth** | Line 957-961 | `_state.value = PaymentState.Error(authResult.userFriendlyError)` | ✅ YES |
| **Complete EMV Trans** | Line 1011 | `_state.value = PaymentState.Error("Error finalizando...")` | ✅ YES |
| **Contactless** | Line 1272, 1286 | `_state.value = PaymentState.Error(...)` | ✅ YES |
| **Backend Recording** | Line 1957 | `result.onFailure { error ->` | ⚠️ PARTIAL* |

**⚠️ Note**: Backend recording failures (line 1957) are handled by offline queue, NOT by marking payment as failed.

### Error Handling Function

**Function**: `PaymentViewModel.kt:1857-2001` → `handlePaymentSuccess()`

```kotlin
private fun handlePaymentSuccess(saleData: Any, entryMode: CardEntryMode) {
    viewModelScope.launch {
        // ... backend recording ...
        val result = recordPaymentUseCase(...)
        
        result.onSuccess { receipt ->
            Timber.i("✅ [Backend Recording] Payment recorded successfully")
            _state.value = currentState.copy(receipt = receipt, ...)
        }.onFailure { error ->
            Timber.e("❌ [Backend Recording] Failed to record payment: ${error.message}")
            // Line 1959-1994: Queue for offline sync
            paymentQueueRepository.enqueue(queuedPayment)  // ← Offline queue, not failure
        }
    }
}
```

### Emit PAYMENT_FAILED When

1. **Blumon SDK Error** - Emit immediately when error state set
   - `_state.value = PaymentState.Error(..., context = ...)`
   
2. **Backend Recording Error** - Queue offline sync BUT also emit error
   - Current: Only queues for retry (no event)
   - Should: Emit `PAYMENT_FAILED` with `canRetry=true` flag

---

## Part 3: Socket.IO Event Implementation

### Current Socket Event Listeners

**File**: `PaymentViewModel.kt:358-391`

```kotlin
private fun collectSocketEvents() {
    viewModelScope.launch {
        socketManager.events.collect { event ->
            when (event) {
                is SocketEvent.PaymentInitiated -> {
                    Timber.d("💳 [Socket] Payment initiated: ${event.paymentId}")
                    // Optional: Show notification that another terminal started payment
                }
                is SocketEvent.PaymentProcessing -> {
                    Timber.d("⏳ [Socket] Payment processing: ${event.paymentId}")
                    // Optional: Update UI to show payment in progress
                }
                is SocketEvent.PaymentCompleted -> {
                    Timber.i("✅ [Socket] Payment completed: ${event.paymentId}")
                    // Currently just logs - doesn't refresh order
                }
                is SocketEvent.PaymentFailed -> {
                    Timber.w("❌ [Socket] Payment failed: ${event.paymentId}")
                    // Optional: Show error notification
                }
                else -> {}  // Other events handled elsewhere
            }
        }
    }
}
```

**Location**: `PaymentViewModel.kt:358-391`

**Current Behavior**:
- ✅ Listens to `PAYMENT_INITIATED`, `PAYMENT_PROCESSING`, `PAYMENT_COMPLETED`, `PAYMENT_FAILED`
- ❌ Does NOT emit these events
- ❌ Does NOT refresh order when payment completes on other terminal

### Event Definition File

**File**: `core/data/realtime/events/SocketEvent.kt:57-97`

```kotlin
sealed interface SocketEvent {
    data class PaymentInitiated(
        val paymentId: String,
        val amount: Int,        // in cents
        val currency: String,
        val tableId: String?,
        val orderId: String?,
        val venueId: String,
        val timestamp: String
    ) : SocketEvent

    data class PaymentProcessing(
        val paymentId: String,
        val amount: Int,
        val currency: String,
        val tableId: String?,
        val orderId: String?,
        val venueId: String,
        val timestamp: String
    ) : SocketEvent

    data class PaymentCompleted(
        val paymentId: String,
        val amount: Int,
        val currency: String,
        val tableId: String?,
        val orderId: String?,
        val venueId: String,
        val timestamp: String,
        val metadata: Map<String, Any>? = null
    ) : SocketEvent

    data class PaymentFailed(
        val paymentId: String,
        val amount: Int,
        val currency: String,
        val tableId: String?,
        val orderId: String?,
        val venueId: String,
        val timestamp: String,
        val metadata: Map<String, Any>? = null
    ) : SocketEvent
}
```

### Socket.IO Event Parsing

**File**: `core/data/realtime/SocketManager.kt:442-495`

```kotlin
private val onPaymentInitiated = Emitter.Listener { args ->
    try {
        val data = args.getOrNull(0) as? JSONObject ?: return@Listener
        _events.tryEmit(parsePaymentEvent(data, "initiated"))  // Line 445
    } catch (e: Exception) {
        Timber.e(e, "❌ Error parsing payment_initiated")
    }
}

private fun parsePaymentEvent(data: JSONObject, status: String): SocketEvent {
    return when (status) {
        "initiated" -> SocketEvent.PaymentInitiated(paymentId, amount, currency, ...)
        "processing" -> SocketEvent.PaymentProcessing(...)
        "completed" -> SocketEvent.PaymentCompleted(...)
        "failed" -> SocketEvent.PaymentFailed(...)
    }
}
```

**Line 278-281**: Socket event listener setup
```kotlin
on("payment_initiated", onPaymentInitiated)      // Line 278
on("payment_processing", onPaymentProcessing)    // Line 279
on("payment_completed", onPaymentCompleted)      // Line 280
on("payment_failed", onPaymentFailed)            // Line 281
```

---

## Part 4: Payment Recording Flow

### Backend Payment Recording

**Function**: `handlePaymentSuccess()`
**Location**: `PaymentViewModel.kt:1857-2001`

```
Timeline:
─────────────────────────────────────────────────────────────────
1. Blumon approves payment       (Line 1021)
2. Show Success state to user    (Line 1023)
3. handlePaymentSuccess() called  (Line 1029) [BACKGROUND]
4. Extract card details          (Line 1902-1906)
5. Build payment context         (Line 1914-1922)
6. Call recordPaymentUseCase()   (Line 1927)      ← BACKEND CALL
7. Success: Update Success state (Line 1935-1955)
8. Failure: Queue for offline    (Line 1956-1994)
```

### recordPaymentUseCase Location

**File**: `features/payment/domain/usecase/RecordPaymentUseCase.kt:1-147`

```kotlin
class RecordPaymentUseCase @Inject constructor(
    private val fastPaymentRecorder: FastPaymentRecorder,
    private val orderPaymentRecorder: OrderPaymentRecorder,
) {
    suspend operator fun invoke(
        context: PaymentContext,
        cardDetails: CardDetails,
        authorizationNumber: String,
        referenceNumber: String,
    ): Result<PaymentReceipt> {
        val recorder = when (context) {
            is PaymentContext.FastPayment -> fastPaymentRecorder  // Line 128-130
            is PaymentContext.OrderPayment -> orderPaymentRecorder  // Line 133-135
        }
        return recorder.recordPayment(...)  // Line 140-145
    }
}
```

### FastPaymentRecorder Implementation

**File**: `features/payment/data/repository/FastPaymentRecorder.kt:54-232`

```kotlin
class FastPaymentRecorder @Inject constructor(
    private val apiService: PaymentApiService,
) : PaymentRecorder {
    override suspend fun recordPayment(...): Result<PaymentReceipt> = withContext(Dispatchers.IO) {
        try {
            val request = buildFastPaymentRequest(context, cardDetails, ...)  // Line 76
            val response = apiService.recordFastPayment(
                venueId = context.venueId,
                request = request
            )  // Line 79-82: CALLS BACKEND

            when {
                response.isSuccessful && response.body() != null -> {
                    val body = response.body()!!
                    val receipt = PaymentReceipt(
                        paymentId = body.data.id,          // ← Backend generates this!
                        receiptUrl = body.data.digitalReceipt.receiptUrl,
                        ...
                    )
                    Result.success(receipt)  // Line 101
                }
                response.code() in 500..599 -> {
                    Result.failure(Exception("Error del servidor..."))
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("Error registrando el pago..."))
        }
    }
}
```

**API Endpoint Called**: 
```
POST /tpv/venues/{venueId}/fast
Body: FastPaymentRequest (contains amount, tip, cardBrand, merchantAccountId, etc.)
```

---

## Part 5: Complete Implementation Plan

### Step 1: Generate Payment ID Early

**Location**: `PaymentViewModel.kt:517`

```kotlin
fun submitAmount(amount: String) {
    // Generate temporary payment ID for tracking through entire flow
    val temporaryPaymentId = UUID.randomUUID().toString()
    
    // Store it in ViewModel for entire payment lifetime
    _currentPaymentId = temporaryPaymentId
    
    // Emit PAYMENT_INITIATED event
    socketManager.emitPaymentInitiated(
        paymentId = temporaryPaymentId,
        amount = (amount.toBigDecimal() * 100.toBigDecimal()).toInt(),  // cents
        currency = "MXN",
        venueId = currentVenueId,
        staffId = currentStaffId
    )
    
    _state.value = PaymentState.CollectingRating(amount = amount)
}
```

### Step 2: Emit PAYMENT_PROCESSING

**Location**: `PaymentViewModel.kt:741` (After PreTrans succeeds)

```kotlin
// After PreTrans succeeds
_state.value = PaymentState.ConfiguringKernel

// Emit PAYMENT_PROCESSING event
socketManager.emitPaymentProcessing(
    paymentId = _currentPaymentId,
    amount = currentAmountInCents.toInt(),
    currency = "MXN",
    venueId = currentVenueId,
    timestamp = System.currentTimeMillis()
)
```

### Step 3: Emit PAYMENT_FAILED on Error

**Location**: `PaymentViewModel.kt` (Multiple error points)

```kotlin
_state.value = PaymentState.Error(
    message = "Error detectando tarjeta: $error",
    context = createPaymentContext()
)

// Emit PAYMENT_FAILED event
socketManager.emitPaymentFailed(
    paymentId = _currentPaymentId,
    amount = currentAmountInCents.toInt(),
    currency = "MXN",
    venueId = currentVenueId,
    reason = error.toString(),
    canRetry = true
)
```

### Step 4: Match Backend Payment ID

**Location**: `PaymentViewModel.kt:1935-1955` (handlePaymentSuccess success block)

```kotlin
result.onSuccess { receipt ->
    // Backend returns paymentId generated by server
    val serverPaymentId = receipt.paymentId
    
    // Update our tracking (for future reference)
    _currentPaymentId = serverPaymentId
    
    // Emit PAYMENT_COMPLETED with backend ID
    socketManager.emitPaymentCompleted(
        paymentId = serverPaymentId,
        amount = currentAmountInCents.toInt(),
        currency = "MXN",
        venueId = currentVenueId,
        timestamp = System.currentTimeMillis()
    )
}
```

### Step 5: Add socketManager Methods

**Location**: Create new file or add to `core/data/realtime/SocketManager.kt`

```kotlin
fun emitPaymentInitiated(paymentId: String, amount: Int, currency: String, venueId: String, staffId: String) {
    socket?.emit("payment_initiated", JSONObject().apply {
        put("paymentId", paymentId)
        put("amount", amount)
        put("currency", currency)
        put("venueId", venueId)
        put("staffId", staffId)
        put("timestamp", System.currentTimeMillis())
    })
}

fun emitPaymentProcessing(paymentId: String, amount: Int, currency: String, venueId: String, timestamp: Long) {
    socket?.emit("payment_processing", JSONObject().apply {
        put("paymentId", paymentId)
        put("amount", amount)
        put("currency", currency)
        put("venueId", venueId)
        put("timestamp", timestamp)
    })
}

fun emitPaymentFailed(paymentId: String, amount: Int, currency: String, venueId: String, reason: String, canRetry: Boolean) {
    socket?.emit("payment_failed", JSONObject().apply {
        put("paymentId", paymentId)
        put("amount", amount)
        put("currency", currency)
        put("venueId", venueId)
        put("reason", reason)
        put("canRetry", canRetry)
        put("timestamp", System.currentTimeMillis())
    })
}
```

---

## Summary Table: Event Emission Points

| Event | When | Where (File:Line) | Condition | Data Available |
|-------|------|-------------------|-----------|-----------------|
| **PAYMENT_INITIATED** | User enters amount & taps continue | PaymentViewModel.kt:517 | Always | amount, venueId, staffId, merchantId |
| **PAYMENT_PROCESSING** | Blumon kernel configured | PaymentViewModel.kt:741-795 | After PreTrans succeeds | paymentId (temp), amount, venueId |
| **PAYMENT_FAILED** | Any error during payment | PaymentViewModel.kt:810/856/957/1011 | `_state.value = Error(...)` set | paymentId, amount, error message |
| **PAYMENT_COMPLETED** | Backend records payment | PaymentViewModel.kt:1935 | `result.onSuccess` | paymentId (from backend), amount, receipt URL |

---

## Critical Implementation Notes

### ⚠️ Issue 1: Payment ID Generation

**Problem**: Backend generates `paymentId` when recording to database. At app startup, we don't have this ID yet.

**Solution**: 
- Generate temporary UUID in app (e.g., "temp_uuid_12345")
- Use it for all events until backend returns real ID
- When backend responds, update ID for any future reference
- Backend can match via `referenceNumber` (from Blumon SDK) which is unique per transaction

### ⚠️ Issue 2: Socket.IO Timing

**Problem**: Socket events might be emitted before socket is connected.

**Solution**:
- Check `socketManager.isConnected` before emitting
- Or use `_events.tryEmit()` which handles disconnection gracefully
- Add error logging if emit fails

### ⚠️ Issue 3: Backend Event Broadcasting

**Problem**: App emits events, but **server needs to listen and broadcast** to other terminals.

**Solution** (Server-Side, not in scope):
- Add listeners in `SocketManager` for `payment_initiated`, `payment_processing`, `payment_failed`
- When recording payment in database, emit corresponding event to venue room
- Match app's paymentId with backend's stored paymentId

---

## Testing Points

1. **Payment Initiated**: User submits amount → Check Socket listener receives event
2. **Payment Processing**: Card detected → Check Socket listener receives event
3. **Payment Failed**: Pull card out → Check Socket listener receives PaymentFailed event
4. **Payment Completed**: Complete payment → Check Socket listener receives event with receipt URL
5. **Multi-Terminal**: Payment on Terminal A → Terminal B should see all events

---

## Appendix: File Reference Map

```
Core Payment Files:
├── PaymentViewModel.kt              (Main payment orchestration)
├── PaymentState.kt                  (State definitions)
├── RecordPaymentUseCase.kt          (Backend recording orchestration)
├── FastPaymentRecorder.kt           (Backend API calls)
├── PaymentApiService.kt             (Retrofit interface)
├── CardDetails.kt                   (Card data model)
├── PaymentContext.kt                (Payment context model)

Socket.IO Files:
├── SocketManager.kt                 (Socket client, event parsing)
├── SocketEvent.kt                   (Event type definitions)

Error Handling:
├── PaymentSyncWorker.kt             (Offline queue retry)
├── PaymentQueueRepository.kt         (Offline queue storage)
├── PendingPaymentEntity.kt          (Room database entity)
```

---

**Document Generated**: 2025-01-15
**Status**: COMPLETE - Ready for Implementation

# Payment Events - Quick Reference Guide

## Three Documents to Review

1. **PAYMENT_EVENTS_ANALYSIS.md** ← START HERE
   - Complete deep analysis of payment flow
   - Exact file locations and line numbers
   - Current state of event handling
   - Issues and solutions

2. **PAYMENT_FLOW_DIAGRAM.txt**
   - ASCII flow diagram of entire payment process
   - Phase-by-phase breakdown
   - State transitions
   - Error handling flow

3. **PAYMENT_EVENTS_QUICK_REFERENCE.md** ← YOU ARE HERE
   - Quick lookup table for developers
   - Where to add code
   - What data is available at each point

---

## Event Emission Checklist

### PAYMENT_INITIATED
- **When**: User submits amount and taps "Continuar"
- **Where**: `PaymentViewModel.kt:517` in `submitAmount()`
- **Status**: NOT YET IMPLEMENTED
- **Add Before**: `_state.value = PaymentState.CollectingRating(amount = amount)`
- **Data Available**:
  - `amount`: from parameter (decimal, e.g., "30.00")
  - `currentAmountInCents`: calculated, NOT available yet (calculate: amount * 100)
  - `currentVenueId`: available
  - `currentStaffId`: available
  - `_currentMerchant.value?.id`: merchant account ID
  - Need to generate: `paymentId` (UUID temporary)

**Code to Add**:
```kotlin
fun submitAmount(amount: String) {
    // Generate payment ID for tracking
    val paymentId = UUID.randomUUID().toString()
    _currentPaymentId = paymentId
    
    // Emit event
    socketManager.emitPaymentInitiated(
        paymentId = paymentId,
        amount = (amount.toBigDecimal() * 100.toBigDecimal()).toInt(),
        currency = "MXN",
        venueId = currentVenueId,
        staffId = currentStaffId
    )
    
    Timber.d("💳 [Socket] PAYMENT_INITIATED: $paymentId")
    _state.value = PaymentState.CollectingRating(amount = amount)
}
```

---

### PAYMENT_PROCESSING
- **When**: Blumon SDK begins configuration (after PreTrans succeeds)
- **Where**: `PaymentViewModel.kt:795` (after `preTransResult.rightValue()`)
- **Status**: NOT YET IMPLEMENTED
- **Add After**: PreTrans succeeds (line 795)
- **Add Before**: `_state.value = PaymentState.DetectingCard(currentAmount)` (line 802)
- **Data Available**:
  - `_currentPaymentId`: from Phase 1
  - `currentAmountInCents`: available
  - `currentVenueId`: available
  - `timestamp`: System.currentTimeMillis()

**Code to Add**:
```kotlin
// After preTransResult.rightValue() succeeds (line 795)
socketManager.emitPaymentProcessing(
    paymentId = _currentPaymentId,
    amount = currentAmountInCents.toInt(),
    currency = "MXN",
    venueId = currentVenueId,
    timestamp = System.currentTimeMillis()
)
Timber.d("⏳ [Socket] PAYMENT_PROCESSING: $_currentPaymentId")
```

---

### PAYMENT_FAILED
- **When**: Any error occurs during payment
- **Where**: Multiple locations (810, 856, 957, 1011, 1272, 1286)
- **Status**: NOT YET IMPLEMENTED
- **Add When**: Setting `_state.value = PaymentState.Error(...)`
- **Data Available**:
  - `_currentPaymentId`: from Phase 1
  - `currentAmountInCents`: available
  - `currentVenueId`: available
  - `error`: the error object/message

**Code Template**:
```kotlin
// Whenever error occurs:
_state.value = PaymentState.Error(
    message = userFriendlyMessage,
    context = createPaymentContext()
)

// Add this after setting error state:
socketManager.emitPaymentFailed(
    paymentId = _currentPaymentId,
    amount = currentAmountInCents.toInt(),
    currency = "MXN",
    venueId = currentVenueId,
    reason = error.toString(),
    canRetry = true  // Always true - users can retry
)
Timber.w("❌ [Socket] PAYMENT_FAILED: $_currentPaymentId - $error")
```

**Error Points to Update**:
1. Line 810-814: Card detection error
2. Line 856-860: EMV processing error
3. Line 957-961: Online authorization error
4. Line 1011: Complete EMV trans error
5. Line 1272: Contactless error
6. Line 1286: Contactless error
7. Line 1343: Contactless final error

---

### PAYMENT_COMPLETED
- **When**: Backend records payment successfully
- **Where**: `PaymentViewModel.kt:1935` in `handlePaymentSuccess()`
- **Status**: PARTIALLY IMPLEMENTED (events received, NOT emitted)
- **Current Code**: Lines 1935-1955 (success handling)
- **Add After**: Success state updated with receipt
- **Data Available**:
  - `receipt.paymentId`: Backend-generated (THIS REPLACES temp ID!)
  - `currentAmountInCents`: available
  - `currentVenueId`: available
  - `receipt.receiptUrl`: for reference

**Code to Add**:
```kotlin
result.onSuccess { receipt ->
    Timber.i("✅ [Backend Recording] Payment recorded successfully")
    
    // Update our ID tracking
    _currentPaymentId = receipt.paymentId
    
    // Emit completion event to other terminals
    socketManager.emitPaymentCompleted(
        paymentId = receipt.paymentId,
        amount = currentAmountInCents.toInt(),
        currency = "MXN",
        venueId = currentVenueId,
        timestamp = System.currentTimeMillis()
    )
    Timber.i("✅ [Socket] PAYMENT_COMPLETED: ${receipt.paymentId}")
    
    // Update UI with receipt (existing code)
    val currentState = _state.value
    if (currentState is PaymentState.Success) {
        _state.value = currentState.copy(
            receipt = receipt,
            cardDetails = cardDetails,
            referenceNumber = referenceNumber
        )
    }
}
```

---

## SocketManager Methods to Add

**File**: `core/data/realtime/SocketManager.kt`

**Add These Methods**:

```kotlin
fun emitPaymentInitiated(
    paymentId: String,
    amount: Int,
    currency: String,
    venueId: String,
    staffId: String
) {
    socket?.emit("payment_initiated", JSONObject().apply {
        put("paymentId", paymentId)
        put("amount", amount)
        put("currency", currency)
        put("venueId", venueId)
        put("staffId", staffId)
        put("timestamp", System.currentTimeMillis())
    })
    Timber.d("🔌 [Socket Emit] payment_initiated: $paymentId")
}

fun emitPaymentProcessing(
    paymentId: String,
    amount: Int,
    currency: String,
    venueId: String,
    timestamp: Long
) {
    socket?.emit("payment_processing", JSONObject().apply {
        put("paymentId", paymentId)
        put("amount", amount)
        put("currency", currency)
        put("venueId", venueId)
        put("timestamp", timestamp)
    })
    Timber.d("🔌 [Socket Emit] payment_processing: $paymentId")
}

fun emitPaymentFailed(
    paymentId: String,
    amount: Int,
    currency: String,
    venueId: String,
    reason: String,
    canRetry: Boolean
) {
    socket?.emit("payment_failed", JSONObject().apply {
        put("paymentId", paymentId)
        put("amount", amount)
        put("currency", currency)
        put("venueId", venueId)
        put("reason", reason)
        put("canRetry", canRetry)
        put("timestamp", System.currentTimeMillis())
    })
    Timber.w("🔌 [Socket Emit] payment_failed: $paymentId - $reason")
}

fun emitPaymentCompleted(
    paymentId: String,
    amount: Int,
    currency: String,
    venueId: String,
    timestamp: Long
) {
    socket?.emit("payment_completed", JSONObject().apply {
        put("paymentId", paymentId)
        put("amount", amount)
        put("currency", currency)
        put("venueId", venueId)
        put("timestamp", timestamp)
    })
    Timber.i("🔌 [Socket Emit] payment_completed: $paymentId")
}
```

---

## Variable Initialization

**In PaymentViewModel class** (around line 145-150):

```kotlin
// Add this with other payment state variables
private var _currentPaymentId: String = ""  // Tracks payment across entire flow

// Or better, store in a property:
private var currentPaymentId: String = ""
```

---

## Critical Code Locations - All Files

| File | Location | What | Action |
|------|----------|------|--------|
| PaymentViewModel.kt | 517 | submitAmount() | Add PAYMENT_INITIATED emit |
| PaymentViewModel.kt | 795 | After PreTrans success | Add PAYMENT_PROCESSING emit |
| PaymentViewModel.kt | 810/856/957/1011/1272 | Error states | Add PAYMENT_FAILED emit to each |
| PaymentViewModel.kt | 1935 | handlePaymentSuccess() | Add PAYMENT_COMPLETED emit |
| SocketManager.kt | [end of file] | Add methods | Add 4 emit methods |

---

## Testing Checklist

- [ ] **Test 1**: Submit amount → logs show PAYMENT_INITIATED event
- [ ] **Test 2**: Card detected → logs show PAYMENT_PROCESSING event
- [ ] **Test 3**: Remove card during processing → logs show PAYMENT_FAILED event
- [ ] **Test 4**: Complete payment → logs show PAYMENT_COMPLETED event
- [ ] **Test 5**: Multi-terminal: Payment on Terminal A → Terminal B receives event
- [ ] **Test 6**: Check Socket connection before emitting (optional but recommended)
- [ ] **Test 7**: Verify paymentId matches throughout flow (temp → backend ID)
- [ ] **Test 8**: Offline scenario - events should still queue/emit when reconnected

---

## Implementation Priority

1. **High Priority** (Blocking multi-terminal):
   - PAYMENT_INITIATED (tells other terminals a payment started)
   - PAYMENT_COMPLETED (critical - tells other terminals payment succeeded)

2. **Medium Priority** (Nice to have):
   - PAYMENT_PROCESSING (informational)
   - PAYMENT_FAILED (allows error handling on other terminals)

3. **Optional Enhancements**:
   - Add Socket connection check before emitting
   - Add error logging if emit fails
   - Add metrics/analytics to track event emission

---

## Common Pitfalls to Avoid

1. **Forgetting to generate paymentId early**
   - If you wait until backend response, temp events have no ID
   - Solution: Generate UUID in Phase 1, replace with backend ID in Phase 6

2. **Emitting to disconnected socket**
   - Use `socket?.emit()` (safe null check) not `socket.emit()`
   - Better: Check `isConnected` before emitting

3. **Not preserving context on error**
   - Already implemented with `RetryContext`
   - Don't break this when adding events

4. **Backend doesn't listen to events**
   - App emits correctly, but server might not have listeners
   - Coordinate with backend team: Server MUST emit to other terminals

5. **Hardcoding values**
   - Always use `currentVenueId`, `currentAmountInCents`, etc.
   - Never hardcode "MXN" without checking payment context

---

## Server-Side Integration

**For Backend Team**:

The app will emit these Socket.IO events:

```typescript
// Client → Server
socket.emit("payment_initiated", {
  paymentId: "temp-uuid-123",
  amount: 3000,  // cents
  currency: "MXN",
  venueId: "venue_456",
  staffId: "staff_789",
  timestamp: 1705315200000
})

socket.emit("payment_processing", {
  paymentId: "temp-uuid-123",
  amount: 3000,
  currency: "MXN",
  venueId: "venue_456",
  timestamp: 1705315200000
})

socket.emit("payment_failed", {
  paymentId: "temp-uuid-123",
  amount: 3000,
  currency: "MXN",
  venueId: "venue_456",
  reason: "Card detection timeout",
  canRetry: true,
  timestamp: 1705315200000
})

socket.emit("payment_completed", {
  paymentId: "server-generated-id-789",  // NOW with real backend ID
  amount: 3000,
  currency: "MXN",
  venueId: "venue_456",
  timestamp: 1705315200000
})
```

**Server should**:
- Listen to these events
- Broadcast to venue room: `io.to(`venue_${venueId}`).emit()`
- Match temp paymentId with backend ID via referenceNumber (Blumon reference)
- Update database payment status
- Broadcast PAYMENT_COMPLETED to other terminals

---

**Document Version**: 1.0
**Last Updated**: 2025-01-15
**Status**: Ready for Implementation

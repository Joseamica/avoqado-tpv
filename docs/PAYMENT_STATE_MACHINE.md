# Payment State Machine

**Location**: `/app/src/main/java/com/jaac/avoqado_tpv/features/payment/domain/PaymentState.kt`

Shared by 8 features: Fast Payment, Quick Order, Table Service, Pay Later, Serialized Inventory, Split Payments, Refunds, Kiosk Mode.

## 21 Payment States

| State | Purpose | Next States |
|-------|---------|-------------|
| **Pre-Payment (Steps 1-4)** | | |
| `Idle` | Legacy start state (redirects to EnteringAmount) | EnteringAmount |
| `EnteringAmount` | User enters amount (`amount: String`) | CollectingRating |
| `CollectingRating` | 0-5 stars (0=unrated) | CollectingTip |
| `CollectingTip` | Optional tip (10%, 15%, 20%, custom) | VerifyingPrePayment/SelectingMerchant |
| `VerifyingPrePayment` | Pre-payment photo/barcode capture (retail/telecomunicaciones only) | SelectingMerchant |
| `SelectingMerchant` | Choose merchant account (multi-merchant) | ConfiguringKernel/GeneratingCryptoQR/AwaitingCashConfirmation |
| **Payment Processing** | | |
| `ConfiguringKernel` | Blumon SDK PreTrans (3-5s) | DetectingCard/Error |
| `DetectingCard` | Waiting for card insertion/tap (`amount: String`) | Processing/Cancelled/Error |
| `Processing` | EMV transaction in progress (`message: String`) | Success/Error/Cancelled |
| **Alternative Flows** | | |
| `GeneratingCryptoQR` | B4Bit API call for crypto QR | AwaitingCryptoPayment/Error |
| `AwaitingCryptoPayment` | Display QR, wait for Socket.IO `crypto:payment_confirmed` | Success/Error |
| `AwaitingCashConfirmation` | Kiosk cash: wait for staff PIN (prevents walk-away fraud) | Success/Error |
| **Terminal States** | | |
| `Success` | Payment recorded (`authCode`, `receipt`, `cardDetails`, `remainingBalance`, `isRefund`) | Verifying/Printing/Idle |
| `Error` | Retriable/non-retriable error with `RetryContext` | DetectingCard (retry)/Idle |
| `Cancelled` | User/timeout cancelled | Idle |
| **Post-Payment** | | |
| `Verifying` | Post-payment photo/barcode capture (retail/telecomunicaciones) | Idle |
| `Printing` | Receipt printing in progress | Success/PrintError |
| `PrintError` | Printer failed, show retry option | Success |

## State Transition Flow

```
EnteringAmount → CollectingRating → CollectingTip → VerifyingPrePayment* → SelectingMerchant
                                                                                   ↓
                                    ┌──────────────────────────────────────────────┴───────────────┐
                                    ↓                                ↓                             ↓
                         ConfiguringKernel → DetectingCard   GeneratingCryptoQR          AwaitingCashConfirmation
                                    ↓                                ↓                             ↓
                              Processing → Success       AwaitingCryptoPayment → Success        Success
                                    ↓           ↓                                   ↓               ↓
                                  Error    Verifying*                           Verifying*      Verifying*
                                    ↓           ↓                                   ↓               ↓
                              DetectingCard   Idle                                Idle            Idle
```

*VerifyingPrePayment and Verifying only active if TpvSettings requires photo/barcode.

## RetryContext Pattern (Toast/Square/Stripe)

**Philosophy**: When payment fails (card timeout, declined), user NEVER re-enters amount/tip/rating.

```kotlin
data class RetryContext(
    val amount: String,                    // Preserve entered amount
    val tipAmount: String,                 // Preserve calculated tip
    val rating: Int?,                      // Preserve 1-5 stars (null=skipped)
    val merchantAccountId: String?,        // Preserve selected merchant (null for cash)
    val merchantLocalId: String?,          // Fallback merchant ID
    val flowOrigin: PaymentFlowOrigin,     // FAST, ORDER, TABLE, etc.
    val orderId: String?,                  // Order payment context
    val orderNumber: String?,              // Display reference
    val splitType: String?,                // EQUALPARTS, PERPRODUCT, CUSTOMAMOUNT, FULLPAYMENT
    val equalPartsPartySize: Int?,         // Total people (EQUALPARTS)
    val equalPartsPayedFor: Int?,          // Already paid (EQUALPARTS)
    val paidProductIds: List<String>?      // Already paid (PERPRODUCT)
)
```

### Smart Retry Flow

1. User enters $50 + 10% tip ($5) + 5-star rating → Total $55
2. Card times out → `Error(context = RetryContext($50, $5, 5★, merchant_id))`
3. User taps "Reintentar" → **Goes to `DetectingCard($55)`**, NOT `EnteringAmount`
4. User inserts card → Payment succeeds with preserved data

## 8 Features Sharing State Machine

| Feature | Key Conditional | States Used | Notes |
|---------|----------------|-------------|-------|
| **Fast Payment** | `orderId == null` | All card/crypto/cash states | Quick checkout, no order |
| **Quick Order** | `orderId != null && tableId == null` | All card/crypto/cash states | Order payment |
| **Table Service** | `currentTableId != null` | All card/crypto/cash states | Clears table on Success |
| **Pay Later** | `wasPayLaterOrder == true` | All card/crypto/cash states | Returns to PayLaterListScreen |
| **Serialized Inventory** | `isSerializedInventoryMode == true` | EnteringAmount → VerifyingPrePayment → Card/Crypto | Photo/barcode mandatory |
| **Split Payments** | `remainingBalance > 0` | All card states | Success shows "Continuar pagando" |
| **Refunds** | `isRefund == true` | DetectingCard → Processing → Success | Uses `TransType.REFUND` |
| **Kiosk Mode** | `isKioskPayment == true` | Cash: AwaitingCashConfirmation | Auto-dismiss on Success |

## Error Handling & Retry Strategy

### RecordPaymentUseCase Retry Logic

**Max retries**: 5 attempts
**Backoff**: Exponential (500ms → 1s → 2s → 4s)
**Total time**: ~7.5s before offline queue

```kotlin
// Attempt 1: Immediate
// Attempt 2: +500ms delay
// Attempt 3: +1s delay
// Attempt 4: +2s delay
// Attempt 5: +4s delay
```

### Retriable Errors (Transient)

- 500-599 server errors
- Network/timeout/connection errors
- `message.contains("Error del servidor")`
- `message.contains("timeout")`
- `message.contains("Verifica tu conexión")`

### Non-Retriable Errors (Permanent)

| Error | Code | Reason | Action |
|-------|------|--------|--------|
| Unauthorized | 401 | Token expired | Re-login required |
| Forbidden | 403 | No permissions | Role change required |
| Not Found | 404 | Resource missing | Fix data |
| Rate Limit | 429 | Too many requests | Wait longer |

**Error State Behavior**:
```kotlin
Error(
    message = "Tarjeta rechazada",      // User-friendly message
    context = RetryContext(...),        // Preserved user data
    canRetry = true,                    // Show "Reintentar" button
    showOpenShiftButton = false         // Show "Abrir Turno" for shift errors
)
```

## Refund Flow

### operationNumber vs referenceNumber

**CRITICAL DISTINCTION**:

| Field | Source | Example | Usage |
|-------|--------|---------|-------|
| `operationNumber` | **Blumon webhook** | 123 (small int) | CancelIcc parameter (REQUIRED for refunds) |
| `referenceNumber` | **Blumon SDK** | "000000188231" (large string) | Idempotency key (payment recording) |

### Why operationNumber is Critical

Blumon's `CancelIcc` API requires `operationNumber` (from webhook) to identify which transaction to refund. SDK's `referenceNumber` is NOT accepted.

**Refund Flow**:
```
1. Original payment → SDK returns referenceNumber "000000188231"
2. Backend records payment → waits for Blumon webhook
3. Webhook arrives → backend saves operationNumber 123
4. User selects "Refund" → PaymentViewModel checks operationNumber present
5. SDK.CancelIcc(operationNumber = 123) → processes refund
6. RecordRefundUseCase → records to backend
```

**Validation in RecordRefundUseCase**:
```kotlin
if (context.originalOperationNumber <= 0) {
    return Result.failure(
        "No se puede procesar el reembolso: número de operación Blumon no disponible. " +
        "El webhook de Blumon aún no se ha recibido para este pago."
    )
}
```

### RefundPayment Context

```kotlin
PaymentContext.RefundPayment(
    venueId,
    staffId,
    shiftId,                         // Current shift
    amount,                          // Refund amount (partial or full)
    tip = BigDecimal.ZERO,           // Separate tip refund if needed
    merchantAccountId,               // MUST match original payment
    blumonSerialNumber,              // Original terminal (for SDK switch)
    originalPaymentId,               // Payment to refund
    originalOrderId,                 // Order (nullable for fast payments)
    originalTotalAmount,             // Original payment total
    refundReason,                    // CUSTOMER_REQUEST, WRONG_AMOUNT, etc.
    isPartialRefund,                 // true if amount < originalTotalAmount
    originalOperationNumber          // 🎫 CRITICAL: From webhook for CancelIcc
)
```

## Cash vs Card Flow Differences

| Aspect | Card | Cash | Crypto |
|--------|------|------|--------|
| **Merchant selection** | Required | Skipped (merchantAccountId = null) | Required |
| **SDK interaction** | ConfiguringKernel → DetectingCard → Processing | None | None |
| **Recording** | After SDK Success | Immediate | After Socket.IO event |
| **Kiosk handling** | Normal flow | AwaitingCashConfirmation → Staff PIN | Normal flow |
| **Receipt** | Card details included | No card details | Crypto details (address, symbol) |
| **Refund** | TransType.REFUND via SDK | Backend deducts from shift | Not supported |

### Kiosk Cash Confirmation

**Problem**: Walk-away fraud (customer selects "Cash", order marked paid, customer leaves).

**Solution** (McDonald's/Cinépolis pattern):
```
1. Customer selects "Efectivo" in kiosk
2. State → AwaitingCashConfirmation (shows "Espera al empleado")
3. Receipt auto-prints with amount owed
4. Staff collects cash → enters PIN to confirm
5. ONLY THEN backend records payment
6. Success screen shown
7. Timeout (3 min) → order can be cancelled
```

## Crypto Payment Flow (B4Bit Integration)

**States**: `GeneratingCryptoQR` → `AwaitingCryptoPayment` → `Success`

### Flow Details

```
1. User selects "Cripto" in SelectingMerchant
2. GeneratingCryptoQR → calls backend `/crypto/initiate`
3. Backend → B4Bit API creates payment order
4. Backend returns { requestId, paymentUrl, expiresAt }
5. AwaitingCryptoPayment → displays QR code (from paymentUrl)
6. User scans QR → pays in crypto wallet
7. B4Bit webhook → backend → Socket.IO `crypto:payment_confirmed`
8. TPV receives event → Success state
9. Or timeout (expiresInSeconds) → Error state
```

### AwaitingCryptoPayment State

```kotlin
data class AwaitingCryptoPayment(
    val requestId: String,           // B4Bit tracking ID
    val paymentId: String,           // Backend payment ID
    val paymentUrl: String,          // QR code content
    val subtotal: String,
    val tipAmount: String,
    val totalAmount: String,
    val rating: Int?,
    val expiresAt: String,           // ISO timestamp
    val expiresInSeconds: Int,       // Countdown timer
    val cryptoAddress: String?,      // Optional: wallet for manual transfer
    val cryptoSymbol: String?        // BTC, ETH, etc.
)
```

**UI Elements**:
- Large QR code (from `paymentUrl`)
- Total amount in MXN
- Countdown timer ("Expira en 4:32")
- "Cancelar" button
- Optional: crypto address for manual copy

## Verification (Photo/Barcode Capture)

**Two verification points**:

1. **VerifyingPrePayment** (BEFORE payment) — Retail/telecomunicaciones capture evidence first
2. **Verifying** (AFTER payment) — Legacy flow for post-payment capture

### VerificationPhoto Upload Tracking

```kotlin
data class VerificationPhoto(
    val localPath: String,                  // For preview display
    val status: PhotoUploadStatus,          // PENDING → UPLOADING → UPLOADED/ERROR
    val firebaseUrl: String?,               // Download URL after upload
    val uploadProgress: Float,              // 0.0 to 1.0
    val error: String?                      // Error message if failed
)
```

**Upload Flow**:
```
1. Photo captured → status = PENDING, localPath set
2. Upload starts → status = UPLOADING, uploadProgress updating (0.0 → 1.0)
3. Success → status = UPLOADED, firebaseUrl set
4. Failure → status = ERROR, error message set
```

### Skip Logic

```kotlin
fun canSkip(): Boolean = !requirePhoto && !requireBarcode
fun canProceed(): Boolean {
    val photoMet = !requirePhoto || photos.isNotEmpty()
    val barcodeMet = !requireBarcode || scannedBarcodes.isNotEmpty()
    val uploadsComplete = photos.isEmpty() || allPhotosUploaded
    val noErrors = !hasUploadError
    return photoMet && barcodeMet && uploadsComplete && noErrors
}
```

**UI**:
- "Saltar" button only visible if `canSkip() == true`
- "Continuar" enabled only if `canProceed() == true`

### ScannedProduct (Barcode Data)

```kotlin
data class ScannedProduct(
    val barcode: String,                // EAN-13, UPC-A, QR, etc.
    val format: String,                 // "EAN_13", "UPC_A", "QR_CODE"
    val productName: String?,           // From local cache (null if unknown)
    val productId: String?,             // Backend ID (null if unknown)
    val hasInventory: Boolean,          // true if trackInventory enabled
    val quantity: Int                   // Default 1, increment for duplicates
)
```

**ML Kit scan** → lookup in local cache → populate name/ID/inventory flag → backend resolves unknown barcodes.

## Success State (Most Complex)

```kotlin
data class Success(
    val authCode: String,                       // Authorization code (Blumon/B4Bit)
    val amount: String,                         // Payment amount
    val tipAmount: String?,                     // Tip amount
    val rating: Int?,                           // User rating
    val receipt: PaymentReceipt?,               // Digital receipt with QR URL
    val cardDetails: CardDetails?,              // Card info (for receipt printing)
    val referenceNumber: String?,               // SDK reference number
    val orderId: String?,                       // Order ID (for loading items)
    val orderNumber: String?,                   // Display reference
    val orderItems: List<OrderItem>?,           // For itemized receipt
    val remainingBalance: BigDecimal?,          // ⭐ Split payment: amount left to pay
    val discountAmount: String?,                // Discount applied
    val verificationCompleted: Boolean,         // Prevents verification loop
    val isRefund: Boolean                       // Refund transaction flag
)
```

**Split Payment Logic**:
- If `remainingBalance > 0` → show "Continuar pagando" button
- Returns to payment flow with updated order context
- Success screen shows partial payment confirmation

**Refund Display**:
- If `isRefund == true` → show "Reembolso procesado" instead of "Pago exitoso"
- Different receipt format for refunds

## PaymentViewModel Safety Rules

**BEFORE ANY CHANGE**:

1. Identify all affected features (8 conditionals in Success navigation)
2. Test ALL 6+ payment flows (fast, order, table, pay-later, split, refund)
3. Add new state variables → immediately clear in `resetPayment()`
4. Verify Success includes ALL required fields
5. Sync changes between `sandbox/` and `production/` variants

**State Contamination Risk**:
```kotlin
// BAD: Cached data leaks to next payment
var lastMerchantId: String? = null

// GOOD: Clear in resetPayment()
fun resetPayment() {
    _state.value = Idle
    lastMerchantId = null        // Explicit clear
    isKioskPayment = false
    wasPayLaterOrder = false
    // ... clear ALL state variables
}
```

## Production Checklist

- [ ] Merchant switch tested (3-5s ConfiguringKernel)
- [ ] Retry preserves amount/tip/rating
- [ ] Refund requires `originalOperationNumber > 0`
- [ ] Kiosk cash waits for staff PIN
- [ ] Split payment shows remainingBalance correctly
- [ ] Verification skipped if no mandatory requirements
- [ ] All 8 feature conditionals tested
- [ ] Variants synced (sandbox + production)

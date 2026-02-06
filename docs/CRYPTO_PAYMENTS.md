# Crypto Payments (B4Bit Integration)

## Overview

B4Bit crypto payment gateway integration. Customer scans QR, pays with crypto wallet (BTC/ETH/USDT/USDC/SOL), backend receives webhook, Socket.IO confirms payment.

## Flow

| Step | Component | Action |
|------|-----------|--------|
| 1 | User | Taps "Cripto" on merchant selection screen |
| 2 | PaymentViewModel | Calls `POST /tpv/venues/{venueId}/crypto/initiate` |
| 3 | Backend | Calls B4Bit API, creates order, returns payment URL |
| 4 | TPV | Displays QR code (from paymentUrl) + countdown timer |
| 5 | Customer | Scans QR with crypto wallet app |
| 6 | B4Bit | Customer selects crypto, confirms payment |
| 7 | B4Bit | Webhook → Backend |
| 8 | Backend | Socket.IO emits `crypto:payment_confirmed` |
| 9 | PaymentViewModel | Receives Socket event → Success state |

## Payment States

```kotlin
// GeneratingCryptoQR (loading)
SelectingMerchant → GeneratingCryptoQR → AwaitingCryptoPayment

// AwaitingCryptoPayment (showing QR)
data class AwaitingCryptoPayment(
    val requestId: String,        // B4Bit request ID
    val paymentId: String,         // Avoqado payment ID
    val paymentUrl: String,        // URL encoded in QR
    val expiresInSeconds: Int,     // Countdown timer (e.g., 300s)
    val cryptoAddress: String?,    // Optional wallet address
    val cryptoSymbol: String?      // Optional selected crypto (BTC, ETH, etc.)
)
```

## QR Code Generation

**File:** `CryptoPaymentQrScreen.kt`

```kotlin
// QR generated from paymentUrl returned by backend
CryptoPaymentQrScreen(
    paymentUrl = "https://pay.b4bit.com/order/abc123",  // From backend
    totalAmount = "55.00",
    expiresInSeconds = 300,
    onCancel = { /* Cancel crypto payment */ },
    onTimeout = { /* Payment expired */ }
)
```

**UI Features:**
- 240dp QR code (white background, primary border)
- Total amount (MXN)
- Countdown timer (MM:SS format)
- Color changes: >60s (normal), >30s (tertiary), ≤30s (error)
- Progress bar
- Accepted cryptos footer: "BTC • ETH • USDT • USDC • SOL"

## Socket.IO Events

### `crypto:payment_confirmed`

```kotlin
data class CryptoPaymentConfirmed(
    val requestId: String,
    val paymentId: String,
    val amount: Int,               // Amount in cents
    val currency: String,          // "MXN"
    val txHash: String,            // Blockchain transaction hash
    val cryptoAmount: String,      // Amount in crypto (e.g., "0.00123")
    val cryptoCurrency: String,    // "BTC", "ETH", "USDT", etc.
    val confirmations: Int?,       // Blockchain confirmations
    val orderId: String?,
    val orderNumber: String?,
    val receiptUrl: String?,       // Digital receipt URL
    val receiptAccessKey: String?,
    val venueId: String,
    val timestamp: String
)
```

**Handler (SocketManager.kt:558):**
```kotlin
private val onCryptoPaymentConfirmed = Emitter.Listener { args ->
    val data = args.getOrNull(0) as? JSONObject
    _events.tryEmit(SocketEvent.CryptoPaymentConfirmed(...))
}
```

### `crypto:payment_failed`

```kotlin
data class CryptoPaymentFailed(
    val requestId: String,
    val paymentId: String?,
    val reason: String,           // "Payment failed", "Timeout", etc.
    val status: String,           // B4Bit status code
    val venueId: String,
    val timestamp: String
)
```

## API Endpoints

### Initiate Crypto Payment

**Endpoint:** `POST /tpv/venues/{venueId}/crypto/initiate`

**Request:**
```kotlin
data class CryptoPaymentRequest(
    val amount: Int,              // Amount in cents
    val tip: Int?,                // Tip in cents (optional)
    val staffId: String,
    val orderId: String?,         // null = fast payment
    val orderNumber: String?,
    val deviceSerialNumber: String
)
```

**Response:**
```kotlin
data class CryptoPaymentResponse(
    val success: Boolean,
    val data: CryptoPaymentData?,
    val message: String?
)

data class CryptoPaymentData(
    val requestId: String,        // B4Bit request ID (for tracking)
    val paymentId: String,         // Avoqado payment ID
    val paymentUrl: String,        // URL to encode in QR
    val expiresAt: String,         // ISO timestamp
    val expiresInSeconds: Int,     // Seconds until expiration (e.g., 300)
    val cryptoAddress: String?,    // Optional wallet address
    val cryptoSymbol: String?      // Optional selected crypto
)
```

### Cancel Crypto Payment

**Endpoint:** `POST /tpv/venues/{venueId}/crypto/cancel`

**Request:**
```kotlin
data class CancelCryptoPaymentRequest(
    val requestId: String,
    val reason: String?  // "Cancelled by user", "Timeout", etc.
)
```

### Check Payment Status (Fallback)

**Endpoint:** `GET /tpv/venues/{venueId}/crypto/status/{requestId}`

**Response:**
```kotlin
data class CryptoPaymentStatusResponse(
    val success: Boolean,
    val requestId: String,
    val status: String,           // "PENDING", "PROCESSING", "COMPLETED", "FAILED"
    val paymentId: String?,
    val txHash: String?,
    val cryptoAmount: String?,
    val cryptoCurrency: String?,
    val confirmedAt: String?
)
```

## When This Is Used

| Scenario | Trigger |
|----------|---------|
| **Fast Payment** | User selects "Cripto" on merchant selection → pays without creating order first |
| **Order Payment** | User has existing order (table/quick) → selects "Cripto" instead of card |
| **Alternative to Card** | Venue doesn't have Blumon TPV merchant account, or customer prefers crypto |
| **International Payments** | Crypto bypasses currency conversion fees |

**Venue Requirement:** Backend must have B4Bit API credentials configured for the venue.

## Error Handling

| Error | Cause | Recovery |
|-------|-------|----------|
| QR generation fails | Backend → B4Bit API error | Show error state, allow retry |
| Payment timeout | Customer didn't scan/pay within 300s | Auto-cancel, return to merchant selection |
| Socket.IO disconnect | Network issue during payment | Fallback to polling `/crypto/status/{requestId}` |
| Payment failed | Customer cancelled, insufficient crypto | Emit `crypto:payment_failed` → show error |

## File Locations

| File | Lines | Purpose |
|------|-------|---------|
| `PaymentState.kt:252-310` | ~60 | `GeneratingCryptoQR`, `AwaitingCryptoPayment` states |
| `CryptoPaymentQrScreen.kt` | 323 | QR code UI, countdown timer |
| `SocketManager.kt:313-317` | 5 | Socket event listeners setup |
| `SocketManager.kt:558-602` | 45 | `crypto:payment_confirmed`, `crypto:payment_failed` handlers |
| `ApiService.kt:579-643` | 65 | `/crypto/initiate`, `/crypto/cancel`, `/crypto/status` endpoints |
| `ApiService.kt:1503-1577` | 75 | Request/response DTOs |

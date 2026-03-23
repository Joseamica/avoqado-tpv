# AngelPay Integration Plan

## Overview
AngelPay payment processor integration into Avoqado TPV. Based on ActivityResult API pattern in MainActivity, parameter passing via AppNavigation's savedStateHandle, and Socket.IO result emission via SocketManager.

## Architecture Decision
- **Payment Processor Interface**: Abstract `PaymentProcessor` interface (parallels Blumon SDK approach)
- **AngelPay Implementation**: `AngelPayProcessor : PaymentProcessor`
- **Credential Storage**: Extend `SecureStorage` with AngelPay-specific keys
- **Result Handling**: BroadcastReceiver pattern for async callbacks from external AngelPay app
- **State Bridge**: CompletableDeferred to convert callback-based API to coroutine-based code
- **Result Emission**: Use existing `SocketManager.emitTerminalPaymentResult()` for Socket.IO callback

## Integration Points (Mapped)

### 1. MainActivity.kt
- Add `ActivityResultContract` for AngelPay callback
- Register via `registerForActivityResult(ActivityResultContracts.StartActivityForResult())`
- Launch AngelPay app in PaymentViewModel

### 2. SecureStorage.kt
- Add keys: `KEY_ANGELPY_MERCHANT_ID`, `KEY_ANGELPY_TERMINAL_ID`, `KEY_ANGELPY_API_KEY`
- Pattern: Save/getAngelPayMerchantId(), save/getAngelPayTerminalId(), save/getAngelPayApiKey()
- Use EncryptedSharedPreferences (existing AES256-GCM encryption)

### 3. PaymentViewModel.kt
- Inject AngelPayProcessor
- Add payment flow state for AngelPay
- Call `angelPayProcessor.processPayment(amount, merchantId, ...)`
- Handle result via callback/Flow

### 4. PaymentScreen.kt
- Detect payment processor selection (dropdown or from config)
- Trigger AngelPay flow when user selects AngelPay
- Display processing state UI

### 5. AppNavigation.kt
- If AngelPay result comes from Socket.IO, update savedStateHandle with payment result
- If ActivityResult callback, pass result to PaymentViewModel

### 6. SocketManager.kt
- Already has `emitTerminalPaymentResult()` method
- Use for sending payment results back to backend

## Implementation Phases

### Phase 1: Credential Storage
- Add AngelPay keys to SecureStorage.kt
- Add save/get methods for AngelPay config
- Test encryption/decryption

### Phase 2: Payment Processor Interface
- Create `PaymentProcessor` sealed interface
- Create `AngelPayProcessor` implementation
- Add Hilt module for AngelPayProcessor injection

### Phase 3: ActivityResult Wiring
- Add ActivityResultContract in MainActivity
- Create BroadcastReceiver for AngelPay callbacks (pattern from InstallResultReceiver)
- Wire callback to PaymentViewModel via Hilt EntryPoint

### Phase 4: PaymentViewModel Integration
- Add AngelPay payment flow state machine
- Integrate with existing payment state (PaymentState.kt)
- Add AngelPay-specific error handling

### Phase 5: UI & Navigation
- Update PaymentScreen to support AngelPay processor selection
- Update AppNavigation for AngelPay-specific parameters
- Test parameter passing via savedStateHandle

### Phase 6: Socket.IO Integration
- Emit payment result via `socketManager.emitTerminalPaymentResult()`
- Handle async callback from AngelPay app
- Test end-to-end flow

### Phase 7: Testing & Release
- Unit tests for AngelPayProcessor
- Integration tests with mock AngelPay app
- Pre-commit: Run all payment flow tests
- Version bump (MINOR — new capability)
- Update CHANGELOG.md

## Socket.IO Result Structure
```kotlin
socketManager.emitTerminalPaymentResult(
    requestId = socketRequestId,  // from PaymentScreen param
    status = "SUCCESS" | "FAILED" | "CANCELLED",
    transactionId = "angelPay_txn_id",
    cardDetails = mapOf(
        "last4" to "1234",
        "brand" to "VISA",
        "processor" to "ANGELPY"
    ),
    errorMessage = "User cancelled" or null,
    receiptUrl = "https://..." or null
)
```

## Key Files to Create/Modify
- `payment/data/AngelPayProcessor.kt` (NEW)
- `core/data/local/SecureStorage.kt` (MODIFY)
- `payment/presentation/PaymentViewModel.kt` (MODIFY)
- `features/payment/presentation/PaymentScreen.kt` (MODIFY)
- `core/presentation/navigation/AppNavigation.kt` (MODIFY)
- `features/payment/domain/PaymentProcessor.kt` (NEW - interface)
- `payment/data/AngelPayBroadcastReceiver.kt` (NEW)
- `di/AngelPayModule.kt` (NEW - Hilt module)

## Status
- Research: COMPLETE
- Plan: READY FOR USER CONFIRMATION

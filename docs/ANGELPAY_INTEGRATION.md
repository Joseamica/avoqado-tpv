# AngelPay App-to-App Integration

AngelPay payment processing via Android Intents on Nexgo N86 terminals. Completely isolated from Blumon SDK — parallel payment path with its own ViewModel, Screen, and state machine.

## Architecture: Parallel Paths

```
PAX terminals (UNTOUCHED):
  AppNavigation → PaymentScreen → PaymentViewModel → Blumon SDK
  (zero changes, zero risk)

Nexgo terminals (NEW, isolated):
  AppNavigation → AngelPayPaymentScreen → AngelPayPaymentViewModel → Intent → AngelPay app
  (new code only, shares stateless use cases via DI)
```

Routing decision: `BuildConfig.ENABLE_PAX_SDK` (compile-time per Gradle flavor)
- `true` (sandbox/production) → Blumon PaymentScreen
- `false` (nexgo/tutorialEmu) → AngelPayPaymentScreen

## Build Variants

| Flavor | Command | Device | Processor |
|--------|---------|--------|-----------|
| sandbox | `./gradlew installSandboxDebug` | PAX A910S | Blumon SDK (QA) |
| production | `./gradlew assembleProductionRelease` | PAX A910S | Blumon SDK (PROD) |
| tutorialEmu | `./gradlew installTutorialEmuDebug` | Emulator | None |
| **nexgo** | **`./gradlew installNexgoDebug`** | **Nexgo N86/N5** | **AngelPay (QA)** |

## Payment Flow

```
1. User taps "Cobrar" on WelcomeScreen
2. AppNavigation checks BuildConfig.ENABLE_PAX_SDK
3. nexgo → navigates to NavRoute.AngelPayPayment
4. AngelPayPaymentScreen renders
5. ViewModel validates shift (same as Blumon flow)
6. ViewModel builds DO_SALE Intent via AngelPayIntentBuilder
7. Screen launches Intent via ActivityResultLauncher
8. AngelPay app opens, processes card payment
9. AngelPay returns result via onActivityResult
10. ViewModel parses TransactionResult + CallResult
11. If approved → records payment to backend via RecordPaymentUseCase
12. Shows Success screen
```

## AngelPay API Reference (Manual v1.2, 17/03/2026)

### Request Objects

**TransactionRequest** (JSON in Intent extra):
```kotlin
{
  "operationType": "SALE",
  "subtotal": 150000,        // centavos ($1,500.00)
  "tip": 20000,              // centavos ($200.00), optional
  "waiter": "Carlos",        // optional
  "installments": 0,         // MSI months, optional
  "integratorReference": "REF-123",  // our reference, optional
  "timeOutApproved": 0,      // 0 = return immediately (skip AngelPay print)
  "timeOutDeclined": 3000    // 3s for declined
}
```

**AuthExternal** (JSON in Intent extra):
```kotlin
{
  "email": "contacto@avoqado.io",
  "password": "123456",
  "affiliation": "9814275 ultrathink",
  "commerceToken": "1773083056540lIE"
}
```

### Intent Actions

| Action | Purpose |
|--------|---------|
| `mx.angel_pay_prod.app.intent.action.DO_SALE` | Process card payment |
| `mx.angel_pay_prod.app.intent.action.SEE_HISTORY` | View transaction history |
| `mx.angel_pay_prod.app.intent.action.REPORTS` | View reports |

Package: `mx.angel_pay_prod.app` (prod) / `mx.angel_pay_prod.app.qa` (QA)

### Response Format

AngelPay returns **2 JSON String extras** via `onActivityResult`:

| Extra Key | Object |
|-----------|--------|
| `mx.angel_pay_prod.app.extra.RESULT_TRANSACTION` | TransactionResult |
| `mx.angel_pay_prod.app.extra.RESULT_CALL` | CallResult |

**TransactionResult**:
```kotlin
data class TransactionResult(
    val approved: Boolean,    // true = approved
    val authCode: String?,    // authorization code (e.g., "502511")
    val reference: String?,   // reference number
    val amount: Long,         // amount in centavos
    val message: String?,     // descriptive message
    val code: String?,        // response code (e.g., "S000")
)
```

**CallResult**:
```kotlin
data class CallResult(
    val code: String?,        // e.g., "S000", "U100", "E600"
    val status: String?,      // "OK", "ERROR", "CANCELLED"
    val category: String?,    // "SUCCESS", "AUTH", "USER", "CLIENT", "DEVICE", "EMV"
    val message: String?,     // human-readable message
)
```

### Result Logic

| Condition | Meaning |
|-----------|---------|
| `RESULT_OK` + `tx.approved == true` | Payment approved |
| `tx.approved == false` | Payment declined |
| `tx == null && call == null` | User cancelled |
| `call != null` (no approved tx) | Error with details |

### Error Catalog (key codes)

| Code | Status | Category | Description |
|------|--------|----------|-------------|
| S000 | OK | SUCCESS | Aprobada |
| U100 | CANCELLED | USER | Cancelada por usuario |
| U101 | CANCELLED | USER | Timeout |
| E600 | ERROR | EMV | Error lectura tarjeta |
| E606 | ERROR | EMV | Rechazo online |
| D308 | ERROR | DEVICE | Sesion expirada |
| C202 | ERROR | CLIENT | Monto invalido |

Full catalog in AngelPay Manual v1.2 pages 25-28.

## File Structure

```
features/payment/
├── domain/
│   └── processor/
│       └── ProcessorType.kt              # BLUMON, ANGELPAY enum
├── data/
│   └── processor/
│       └── angelpay/
│           ├── AngelPayCredentials.kt     # Data class for auth
│           ├── AngelPayIntentBuilder.kt   # Builds DO_SALE/HISTORY/REPORTS intents
│           └── AngelPayResultParser.kt    # Parses onActivityResult response
└── presentation/
    └── angelpay/
        ├── AngelPayPaymentState.kt        # ~10 state sealed class
        ├── AngelPayPaymentViewModel.kt    # Shift validation → intent → parse → record
        └── AngelPayPaymentScreen.kt       # Compose UI with ActivityResultLauncher
```

## QA Credentials

Hardcoded in `AvoqadoTPVApplication.onCreate()` when `ENABLE_PAX_SDK == false`:

| Field | Value |
|-------|-------|
| email | contacto@avoqado.io |
| password | 123456 |
| affiliation | 9814275 ultrathink |
| commerceToken | 1773083056540lIE |
| Portal URL | https://portal.angelpay-qa.com.mx/ |
| Portal user | ConatctoAvoq |
| Portal pass | Avoqado2026@ |

## Vendor Documentation

```
~/Library/Mobile Documents/com~apple~CloudDocs/Avoqado/AngelPay/
└── Manual de Integración App to App.pdf  # v1.0

~/Downloads/
├── Manual de Integración App Angel Pay-v1-2.pdf  # v1.2 (latest, 17/03/2026)
└── angel-pay-consumer/                           # Example integration app
```

## Limitations & Future Work

1. **No refunds** — AngelPay doesn't expose refund intent to merchants. Cancellations (same-day before 23:00) coming in 1-2 weeks
2. **No card details in response** — AngelPay doesn't return maskedPan, cardBrand, or entryMode. Backend records with UNKNOWN
3. **No ticket printing from TPV** — AngelPay auto-prints its own ticket. We set `timeOutApproved=0` to skip it. Future: use their BroadcastReceiver print API for our own receipts
4. **QA creds hardcoded** — Need backend terminal config or SuperAdmin UI for production credential management
5. **Firebase package** — nexgo flavor uses `.sandbox` suffix temporarily. Register `.nexgo` in Firebase Console for production

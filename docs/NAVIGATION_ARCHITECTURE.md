# Navigation Architecture

Avoqado TPV navigation system. 1,990 lines of conditional routing logic, dual NavHost pattern for staff/kiosk modes, external payment triggers, and session lifecycle management.

**Key Files:**
- `/app/src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt` (1,990 lines)
- `/app/src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/NavRoute.kt` (251 lines)
- `/app/src/main/java/com/jaac/avoqado_tpv/features/kiosk/presentation/KioskNavigation.kt` (~400 lines)
- `/app/src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/SafeNavigationHelper.kt` (169 lines)

---

## All NavRoutes

### Staff Routes (Main NavHost)

| Route | Path | Arguments | Purpose |
|-------|------|-----------|---------|
| `Splash` | `splash` | - | Initial loading, determines activation/auth flow |
| `Activation` | `activation` | - | Terminal activation with 6-char code or QR |
| `Login` | `login` | - | PIN authentication for staff |
| `Timeclock` | `timeclock/{venueId}/{pin}` | venueId, pin | Clock in/out without full session |
| `Home` | `home` | - | Main dashboard after login |
| `FastPaymentEntry` | `fast_payment_entry` | - | Dedicated screen for entering payment amount |
| `Shifts` | `shifts` | - | Shift management (open/close shifts) |
| `Settings` | `settings` | - | App settings and configuration |
| `Payment` | `payment` | - | EMV chip card payment (args via savedStateHandle) |
| `SuperAdmin` | `superadmin` | - | Testing and debugging tools |
| `OrderingWelcome` | `ordering_welcome` | - | Entry point: Quick Order vs Table Service |
| `FloorPlan` | `floor_plan` | - | Visual floor plan canvas (zoom/pan gestures) |
| `Menu` | `menu/{orderId}` | orderId | Product selection + Order check (hybrid UI) |
| `OrderList` | `order_list?filter={filter}` | filter (optional) | All orders with filter chips |
| `Reports` | `reports` | - | Sales analytics dashboard |
| `HistoricalPeriodDetail` | `historical_period_detail` | - | Detailed period view (data via ViewModel) |
| `Payments` | `payments` | - | Payment history with pagination/filters |
| `Support` | `support` | - | Help, FAQs, contact info |
| `SelfUpdate` | `self_update` | - | Check and install app updates |
| `SplitByProduct` | `split_by_product/{orderId}` | orderId | Select specific items to pay |
| `SplitByPerson` | `split_by_person/{orderId}` | orderId | Split order equally among N people |
| `RefundConfirmation` | `refund_confirmation/{paymentId}` | paymentId | Review refund before processing |
| `SerializedSale` | `serialized_sale` | - | Quick sell for serialized items (barcode → price → payment) |
| `SerializedInventoryRegister` | `serialized_inventory_register` | - | Batch registration of serialized inventory |

### Kiosk Routes (Separate NavHost)

| Route | Path | Arguments | Purpose |
|-------|------|-----------|---------|
| `KioskWelcome` | `kiosk/welcome` | - | "Touch to Start" entry (5 taps on logo = exit) |
| `KioskMenu` | `kiosk/menu` | - | Self-service product selection (McDonald's style) |
| `KioskCart` | `kiosk/cart` | - | Order summary before payment |
| `KioskSuccess` | `kiosk/success/{orderNumber}` | orderNumber | Thank you + auto-reset countdown |

**Note:** Kiosk mode uses separate NavHost. Payment/Success screens rendered directly in AppNavigation (bypass NavHost) to avoid race conditions.

---

## Conditional Routing Flow

### Splash → Activation → Login → Home

`SplashScreen` determines initial route based on device/auth state:

```kotlin
// Splash Decision Tree (lines 562-583)
when {
    !terminal.isActivated() -> navigate(Activation)
    !user.isAuthenticated() -> navigate(Login)
    else -> navigate(Home)
}
```

**Checks:**
1. **Activation status** — `DeviceInfoManager.getVenueId()` present?
2. **Authentication status** — `SecureStorage.getUserId()` + valid JWT token?
3. **Modules fetch** — Load venue modules at startup for feature availability

**Auto-retry:** Activation screen checks backend every 10s to handle temporary server downtime (lines 609-618).

**Security monitoring:** Login/Home screens check activation status every 2s. If `venueId` cleared by HeartbeatWorker (terminal RETIRED), force navigate to Activation (lines 657-668, 737-748).

### Session Expiry Overlay

**Flow:**
1. **401 detected** — `TokenAuthenticator` intercepts 401 → calls `SessionManager.notifySessionVerifying()` → sets `isSessionExpiring = true`
2. **Loading overlay shown** — `AvoqadoLoadingOverlay(message = "Verificando sesión...")` (line 139)
3. **Token refresh attempt** — TokenAuthenticator tries refresh token
4. **On failure** — `SessionManager.notifySessionExpired()` emits `SessionEvent.Expired`
5. **Navigation** — AppNavigation observes `sessionEvents` → navigate to Login with `popUpTo(0)` (lines 254-311)
6. **Reset overlay** — `SessionManager.resetSessionExpiringState()` clears loading overlay

**Kiosk mode handling:** If session expires in kiosk mode, exit kiosk first (KioskNavigation doesn't have Login route), then navigate (lines 264-282).

---

## External Payment Handling

### BLE/Socket.IO Payment Triggers

Payments can be triggered from **any screen** via Bluetooth or Socket.IO (iOS app integration).

**LaunchedEffect (lines 179-236):**
```kotlin
bluetoothPaymentService.paymentRequests.collect { request ->
    // 🚫 Validation
    if (request.amountCents <= 0) { /* ignore */ }
    if (currentRoute == Payment.route) { /* reject duplicate */ }

    // 🧹 Clear stale args (avoid contamination from previous payments)
    clearPaymentArgs(handle)

    // 📦 Set payment args
    handle.set("initialAmount", formatAmountFromCents(request.amountCents))
    handle.set("skipReview", request.skipReview)
    handle.set("externalTipCents", request.tipCents)
    handle.set("externalRating", request.rating)

    // 🔀 Dual-mode: Set orderId if present (ORDER PAYMENT vs FAST PAYMENT)
    if (request.orderId != null) {
        handle.set("orderId", request.orderId)
        handle.set("skipLocalOrderValidation", true) // iOS validated
    }

    // 📡 Store source for result callback
    handle.set("paymentSource", request.source.name)
    handle.set("socketRequestId", request.socketRequestId)

    // ✅ Navigate to payment
    navController.navigate(Payment.route) { launchSingleTop = true }
}
```

**Payment cancellation (lines 239-252):**
```kotlin
bluetoothPaymentService.paymentCancelRequests.collect { requestId ->
    if (currentRoute == Payment.route) {
        navController.navigate(Home.route) {
            popUpTo(Home.route) { inclusive = false }
            launchSingleTop = true
        }
    }
}
```

**Critical function: clearPaymentArgs() (lines 1952-1980)** — Removes all stale payment arguments to prevent contamination:
- Order args: `orderId`, `orderNumber`, `tableId`, `skipLocalOrderValidation`
- Split args: `splitType`, `equalPartsPartySize`, `equalPartsPayedFor`, `paidProductIds`
- Refund args: `isRefundMode`, `refundAmount`, `refundReason`, `originalPaymentId`, etc.
- Pay-later: `wasPayLaterOrder`, `payLaterOrdersCount`

**Why:** 8 features share PaymentScreen (Fast Payment, Quick Order, Table Service, Pay Later, Split, Refund, Serialized Sale, Kiosk). Stale args from one payment leak to next if not cleared.

---

## Kiosk Mode NavHost Switching

### Dual NavHost Pattern

AppNavigation renders **one of two** NavHost composables:

```kotlin
// AppNavigation.kt (lines 507-531)
if (isKioskMode) {
    KioskNavigation(
        navController = navController,
        onExitKiosk = { kioskModeManager.exitKioskMode() },
        onNavigateToPayment = { orderId, amount, orderNumber, staffId ->
            // Set kiosk payment state (bypasses NavHost)
            kioskPaymentOrderId = orderId
            kioskPaymentAmount = amount
            kioskPaymentOrderNumber = orderNumber
            kioskPaymentStaffId = staffId
        },
        onClearCartRequest = { clearCartFn -> kioskClearCart = clearCartFn }
    )
    return // Don't render staff NavHost
}

// Staff NavHost (lines 556-1790)
NavHost(navController, startDestination = Splash.route) {
    composable(Splash.route) { /* ... */ }
    composable(Activation.route) { /* ... */ }
    // ... 25 staff routes
}
```

### Kiosk Payment/Success Bypass

**Problem:** KioskNavigation NavHost doesn't have Payment/Success routes. If we navigate normally, crash.

**Solution:** Render PaymentScreen/KioskSuccessScreen **directly in AppNavigation** when kiosk payment in progress (lines 334-498):

```kotlin
// 🥝 KIOSK SUCCESS - Render outside NavHost
if (isKioskMode && isKioskSuccessInProgress) {
    KioskSuccessScreen(
        orderNumber = kioskSuccessOrderNumber!!,
        receipt = kioskSuccessReceipt,
        orderItems = kioskSuccessOrderItems,
        onTimeout = { /* clear state, navigate to KioskMenu */ },
        onPrintReceipt = { /* printer logic */ },
        onSendReceipt = { email -> /* API call */ }
    )
    return // Don't show NavHost
}

// 🥝 KIOSK PAYMENT - Render outside NavHost
if (isKioskMode && isKioskPaymentInProgress) {
    PaymentScreen(
        initialAmount = amountPesos.toString(),
        orderId = kioskPaymentOrderId,
        isKioskPayment = true,
        kioskStaffId = kioskPaymentStaffId,
        onKioskPaymentSuccess = { orderNumber, receipt, items ->
            // Clear payment state, show success screen
            kioskPaymentOrderId = null
            kioskSuccessOrderNumber = orderNumber
            kioskSuccessReceipt = receipt
            kioskSuccessOrderItems = items
        },
        onNavigateBack = { /* cancel payment */ },
        onNavigateToShifts = { /* exit kiosk mode */ }
    )
    return // Don't show NavHost
}
```

**State variables:**
- `kioskPaymentOrderId`, `kioskPaymentAmount`, `kioskPaymentOrderNumber`, `kioskPaymentStaffId` — Payment in progress
- `kioskSuccessOrderNumber`, `kioskSuccessReceipt`, `kioskSuccessOrderItems` — Success screen state
- `kioskClearCart` — Function reference from KioskViewModel to clear cart after payment

---

## SafeNavigationHelper

**Problem:** Rapid back button clicks can empty the navigation stack → black screen.

**Example:**
```
Stack: [Welcome → FastPaymentEntry → Payment]
User clicks back 3 times rapidly (within 300ms):
  Click 1: [Welcome → FastPaymentEntry]  ✅
  Click 2: [Welcome]                      ✅
  Click 3: []                             ❌ EMPTY STACK → BLACK SCREEN
```

**Solution:** Debounce + validate backstack before popping.

### Implementation

**Debouncing (300ms window):**
```kotlin
// SafeNavigationHelper.kt (lines 60-67)
val currentTime = System.currentTimeMillis()
val lastTime = lastNavigationTime.get()

if (currentTime - lastTime < DEBOUNCE_DELAY_MS) {
    Timber.w("Debounced popBackStack")
    return false // Ignore rapid clicks
}
```

**Empty stack protection:**
```kotlin
// SafeNavigationHelper.kt (lines 70-89)
val popped = navController.popBackStack()
lastNavigationTime.set(currentTime)

if (popped) {
    Timber.d("Successfully popped backstack")
} else {
    // Can't pop (already at root) - navigate to fallback
    Timber.w("Can't pop - navigating to fallback: $fallbackRoute")
    navController.navigate(fallbackRoute) {
        popUpTo(0) { inclusive = true }
    }
}
```

### Usage

```kotlin
// ❌ BEFORE (unsafe)
onNavigateBack = { navController.popBackStack() }

// ✅ AFTER (safe)
onNavigateBack = { navController.safePopBackStack() }

// With custom fallback
navController.safePopBackStack(fallbackRoute = NavRoute.OrderingWelcome.route)

// Pop to specific route
navController.safePopBackStack(NavRoute.Home.route, inclusive = false)
```

**Extension functions:**
```kotlin
fun NavController.safePopBackStack(fallbackRoute: String = NavRoute.Home.route): Boolean
fun NavController.safePopBackStack(route: String, inclusive: Boolean): Boolean
```

---

## Force Update Blocking Dialog

### Version Gate System

**Flow:**
1. **Heartbeat response** — Backend returns `updateRequired` (soft) or `forceUpdateRequired` (hard) in `/device/heartbeat`
2. **DeviceHealthViewModel** — Creates `DeviceAlert.UpdateAvailable(isForced = true/false)`
3. **AppNavigation** — Filters alerts for forced updates (line 154):
   ```kotlin
   val forceUpdateAlert = deviceAlerts.filterIsInstance<DeviceAlert.UpdateAvailable>()
       .firstOrNull { it.isForced }
   ```
4. **Render blocking dialog** — If `forceUpdateAlert != null`, show modal overlay (line 551):
   ```kotlin
   onUpdate = { navController.navigate(NavRoute.SelfUpdate.route) }
   ```

**Dialog behavior:**
- **Soft update** — Dismissable banner, navigate to SelfUpdate screen
- **Force update** — Non-dismissable modal, blocks all app interaction
- **Rendered globally** — Shows in staff AND kiosk modes

**VersionGateInterceptor:** If backend sends `X-Update-Required: FORCE` header in any API response, immediately show force update dialog.

---

## Deep Link Patterns

**Current status:** No deep links implemented.

**AndroidManifest.xml** — No `<intent-filter>` with `<data>` scheme/host defined (lines 1-50 checked).

**Potential future patterns:**
- `avoqado://payment/{amount}` — Launch payment with pre-filled amount
- `avoqado://order/{orderId}` — Open existing order
- `avoqado://activation/{code}` — Pre-fill activation code
- `avoqado://kiosk` — Launch directly into kiosk mode

**Implementation guide:** Use `navDeepLink {}` in Compose Navigation + add `<intent-filter>` to MainActivity in AndroidManifest.xml.

---

## Common Navigation Pitfalls

### 1. Stale Payment Arguments

**Problem:** PaymentScreen receives stale args from previous payment (wrong orderId, refund mode, split type).

**Cause:** 8 features share PaymentScreen. Arguments persist in savedStateHandle between navigations.

**Solution:** Always call `clearPaymentArgs(handle)` before setting new payment args (line 208):
```kotlin
clearPaymentArgs(handle)
handle.set("initialAmount", formattedAmount)
handle.set("orderId", orderId) // Only if needed
```

### 2. Kiosk Mode Route Crashes

**Problem:** Navigate to Login/Activation while in kiosk mode → crash ("Route not found").

**Cause:** KioskNavigation uses separate NavHost without staff routes.

**Solution:** Check `isKioskMode` before navigation. If true, exit kiosk first (lines 264-282, 287-300):
```kotlin
if (kioskModeManager.isKioskMode.value) {
    Timber.w("Exiting kiosk mode before navigating to Login")
    kioskModeManager.exitKioskMode()
    // After exiting, staff NavHost will render at Splash
}
```

### 3. Empty Navigation Stack (Black Screen)

**Problem:** Rapid back button clicks empty stack → black screen.

**Solution:** Use `safePopBackStack()` extension function (all instances already migrated).

### 4. Duplicate Login Navigation

**Problem:** Session expiry triggers navigation to Login, but LoginViewModel recreates and loses error state (e.g., VenueNotOperational).

**Cause:** Race condition in SessionEvent.Expired handler.

**Solution:** Guard navigation if already on Login screen (lines 273-281):
```kotlin
val currentRoute = navController.currentBackStackEntry?.destination?.route
if (currentRoute != NavRoute.Login.route) {
    navController.navigate(NavRoute.Login.route) {
        popUpTo(0) { inclusive = true }
    }
} else {
    Timber.d("Already on Login - ignoring SessionEvent.Expired")
}
```

### 5. Payment Already in Progress

**Problem:** BLE/Socket payment request received while PaymentScreen already visible → duplicate payment attempt.

**Cause:** Multiple devices sending payment commands simultaneously.

**Solution:** Check current route before navigating (lines 186-199):
```kotlin
val currentRoute = navController.currentBackStackEntry?.destination?.route
if (currentRoute == NavRoute.Payment.route) {
    Timber.w("Payment already in progress - ignoring")
    if (request.source == PaymentSource.SOCKET) {
        socketManager.emitTerminalPaymentResult(
            requestId = request.socketRequestId!!,
            status = "failed",
            errorMessage = "Ya hay un pago en proceso"
        )
    }
    return@collect
}
```

### 6. savedStateHandle Access Pattern

**Problem:** Accessing `navController.currentBackStackEntry?.savedStateHandle` for writing, but `previousBackStackEntry` for reading.

**Correct pattern (lines 755, 849, 921-930, 1019-1029):**
```kotlin
// WRITE (before navigating to destination)
navController.currentBackStackEntry?.savedStateHandle?.set("initialAmount", amount)
navController.navigate(NavRoute.Payment.route)

// READ (from destination screen)
val initialAmount = navController.previousBackStackEntry?.savedStateHandle?.get<String>("initialAmount")
```

**Why:** Write to current entry (the screen navigating FROM), read from previous entry (the screen navigating TO).

### 7. Logout Without Clearing Backstack

**Problem:** User logs out → navigate to Login, but back button returns to Home (security issue).

**Solution:** Always use `popUpTo(0) { inclusive = true }` for logout (line 827):
```kotlin
navController.navigate(NavRoute.Login.route) {
    popUpTo(0) { inclusive = true } // Clear entire backstack
}
```

### 8. Payment Args via URL vs savedStateHandle

**Problem:** Trying to pass complex objects (BigDecimal, Payment, OrderItem list) via navigation args → serialization failure.

**Solution:** Use savedStateHandle for all payment args (lines 921-950). Only use URL args for simple strings/IDs:
```kotlin
// ✅ CORRECT — Simple orderId in URL
NavRoute.Menu.createRoute(orderId)

// ✅ CORRECT — Complex payment data via savedStateHandle
navController.currentBackStackEntry?.savedStateHandle?.apply {
    set("initialAmount", order.remainingBalance.toString())
    set("orderId", order.id)
    set("splitType", splitType.value)
    set("wasPayLaterOrder", isPayLaterOrder)
}
navController.navigate(NavRoute.Payment.route)
```

---

## Decision Matrix

| Scenario | Navigation Pattern | Example |
|----------|-------------------|---------|
| Simple screen transition | `navigate(route)` | Home → Reports |
| Payment flow (8 variants) | `savedStateHandle` + `navigate(Payment)` | Menu → Payment with orderId |
| Back navigation | `safePopBackStack()` | Menu → Ordering Welcome |
| Logout/Deactivation | `popUpTo(0) { inclusive = true }` | Home → Login (clear stack) |
| External payment trigger | `clearPaymentArgs()` + `savedStateHandle` + `navigate(Payment)` | BLE request → Payment |
| Kiosk payment/success | Direct render (bypass NavHost) | KioskCart → PaymentScreen → KioskSuccessScreen |
| Session expiry | Check `isKioskMode` → exit if needed → `navigate(Login)` | Any screen → Login |
| After successful login | Fetch modules + `navigate(Home)` | Login → Home |
| Post-payment navigation | Conditional: table? order? serialized? | Payment → Table clearing / Order list / New sale |

---

**Pattern:** Square POS / Toast POS navigation structure. Inspired by Jetpack Compose Navigation best practices.

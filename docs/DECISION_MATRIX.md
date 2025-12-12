# Decision Matrix for Common Development Tasks

**Purpose**: Quick decision trees and guidelines for recurring development patterns in Avoqado TPV.

---

## 1. Error Handling: Technical vs User-Friendly

> **CRITICAL**: NEVER show technical errors to users. Always translate to actionable messages.

### Decision Tree

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

### Examples

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

---

## 2. When to Create Reusable Components

### Decision Tree

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

### Example: AvoqadoLoadingOverlay

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

---

## 3. Responsive UI for TPV Devices

> **See `UI_RESPONSIVE_GUIDE.md` for complete patterns**

### MANDATORY: Use ResponsiveScaffold

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

---

## 4. Loading States: Prevent Flash Screens

> **CRITICAL**: ALWAYS use `AvoqadoLoadingOverlay` to prevent jarring UI transitions

### What are Flash Screens?
- Brief flicker of previous screen during navigation
- User sees: Modal closes → **Flash of WelcomeScreen** ⚠️ → PaymentScreen appears

### Solution

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

## 5. When to Add Socket.IO Events for New Features

> **MANDATORY**: Before implementing any new feature, evaluate if it needs real-time updates.

### Decision Tree

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

### Examples

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

### Quick Reference

| Use Socket? | Scenario | Events |
|-------------|----------|--------|
| ✅ YES | Multi-terminal payments | `payment_completed`, `payment_failed` |
| ✅ YES | Order status changes | `order_updated`, `order_status_changed` |
| ✅ YES | System alerts | `system_alert` |
| ✅ YES | Admin commands | `tpv_command` |
| ❌ NO | Historical data, auth, static menus | Use REST API |
| ⚠️ ASK | Table reservations, shift changes | Depends on use case |

> **How to add new events:** See `SOCKET_IO_IMPLEMENTATION.md` → "How to Add a New Socket.IO Event"

---

## 6. Spacing & Layout Consistency

**⚠️ MANDATORY**: All screens MUST use consistent spacing between header and content sections.

### Standard Spacing Rules

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

### Spacing Tokens (from ResponsiveSizes)

- `spacingSmall`: 8dp / 12dp / 16dp - Between small elements (icon + text, form fields)
- `spacingMedium`: 16dp / 24dp / 32dp - Between cards, list items
- `spacingLarge`: 24dp / 32dp / 48dp - Between major sections, header to content
- `paddingScreen`: 16dp / 20dp / 24dp - Screen edge padding (handled by ResponsiveScaffold)

### Common Patterns

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

---

## 7. Auto-Retry on Reconnection

**⚠️ MANDATORY**: All screens that fetch data MUST implement auto-retry when connection is restored.

### Pattern

1. Inject `ConnectivityObserver` in ViewModel
2. Observe `NetworkStatus.Available` events
3. If current state is `Error`, auto-retry the failed request
4. Show `OfflineBanner` in UI when `isOffline = true`

```kotlin
// Key logic in ViewModel:
connectivityObserver.observe().collect { status ->
    when (status) {
        NetworkStatus.Available -> {
            _isOffline.value = false
            if (_state.value is State.Error) loadData()  // ← Auto-retry!
        }
        NetworkStatus.Unavailable -> _isOffline.value = true
    }
}
```

### Screens that MUST implement

| Screen | Needs Auto-Retry | Reason |
|--------|------------------|--------|
| Reports | ✅ YES | Fetches sales data |
| Shifts | ✅ YES | Fetches shift history |
| Products/Menu | ✅ YES | Fetches catalog |
| Orders | ✅ YES | Fetches table orders |
| Payment | ❌ NO | Local-first |
| Login | ❌ NO | Handled separately |

**Key Rule:** Auto-retry ONLY triggers if `_state.value is Error` (prevents spamming backend)

---

**Last Updated:** 2025-12-12

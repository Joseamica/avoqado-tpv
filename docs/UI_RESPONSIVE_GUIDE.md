# Responsive UI Design for TPV Devices

> **Main Context:** See [CLAUDE.md](./CLAUDE.md) for core principles and quick reference

---

## 📋 Table of Contents

1. [Philosophy](#philosophy)
2. [Device Matrix](#device-matrix)
3. [ResponsiveScaffold Component](#responsivescaffold-component)
4. [Loading States & Flash Screens](#loading-states--flash-screens)
5. [Testing Checklist](#testing-checklist)
6. [Common Patterns](#common-patterns)
7. [Component Styling (Design System)](#component-styling-design-system)

---

## Philosophy

> **CRITICAL**: All UI screens MUST be responsive and work on small POS devices (480x800 to 1280x800) WITHOUT scrolling on workflow screens.

**Philosophy**: Professional POS systems (Square Terminal, Toast POS, Clover) NEVER require scrolling on core workflows (login, payment, checkout). Everything must be visible at once.

---

## Device Matrix

### Common TPV Device Resolutions

| Device | Resolution | Density | Use Case | Priority |
|--------|------------|---------|----------|----------|
| **PAX A920** | 1280x720 dp | 320 dpi | Most common | ✅ Target |
| **PAX A80** | 1024x600 dp | 240 dpi | Budget option | ⚠️ Minimum |
| **Sunmi T2s** | 1280x800 dp | 213 dpi | Alternative | ✅ Support |
| **Generic 10"** | 1280x800 dp | 160 dpi | Testing baseline | ✅ Support |

### Testing Device Configs

Use these Preview configurations in Android Studio:

```kotlin
@Preview(name = "Small - PAX A80", device = "spec:width=1024dp,height=600dp")
@Preview(name = "Medium - PAX A920", device = "spec:width=1280dp,height=720dp")
@Preview(name = "Large - 10 inch", device = "spec:width=1280dp,height=800dp")
@Composable
fun MyScreenPreview() {
    MyScreen()
}
```

---

## ResponsiveScaffold Component

### Purpose

**Don't repeat BoxWithConstraints logic in every screen.** Use a centralized, tested component.

**File**: `app/src/main/java/com/jaac/avoqado_tpv/core/presentation/components/ResponsiveScaffold.kt`

### Usage

#### ❌ NEVER Use Fixed Sizes

```kotlin
// ❌ WRONG: Hardcoded sizes will overflow on small screens
Column {
    Image(modifier = Modifier.size(120.dp))
    Spacer(modifier = Modifier.height(48.dp))
    Text("Title")
    Spacer(modifier = Modifier.height(48.dp))
    PinPad() // This will be cut off on PAX A80!
}
```

#### ✅ ALWAYS Use ResponsiveScaffold

```kotlin
// ✅ CORRECT: Use ResponsiveScaffold for all screens
@Composable
fun LoginScreen() {
    ResponsiveScaffold(
        scrollable = false,  // Workflow screen - everything must fit
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val sizes = LocalResponsiveSizes.current

        // All sizes automatically adjust based on screen
        Image(modifier = Modifier.size(sizes.logoSize))
        Spacer(modifier = Modifier.height(sizes.spacingMedium))
        Text("Ingresa tu PIN")
        Spacer(modifier = Modifier.height(sizes.spacingMedium))
        PinPad() // Guaranteed to fit!
    }
}
```

### LocalResponsiveSizes Tokens

**Available size tokens from `LocalResponsiveSizes.current`:**

| Token | Small (<600dp) | Medium (600-700dp) | Large (>700dp) | Usage |
|-------|----------------|---------------------|----------------|-------|
| `logoSize` | 60.dp | 80.dp | 100.dp | Venue logos, app icons |
| `iconSizeSmall` | 16.dp | 20.dp | 24.dp | Small UI icons |
| `iconSizeMedium` | 24.dp | 32.dp | 40.dp | Action buttons |
| `iconSizeLarge` | 48.dp | 56.dp | 64.dp | Hero icons |
| `spacingSmall` | 8.dp | 12.dp | 16.dp | Tight spacing |
| `spacingMedium` | 16.dp | 24.dp | 32.dp | Standard spacing |
| `spacingLarge` | 24.dp | 32.dp | 48.dp | Section dividers |
| `paddingScreen` | 16.dp | 20.dp | 24.dp | Screen edges |

### When to Set scrollable = true

**Scrollable = false (Workflow screens):**
- ❌ Login screen
- ❌ PIN pad screen
- ❌ Payment confirmation
- ❌ Amount input

**Scrollable = true (List screens):**
- ✅ Long lists (products, orders, history)
- ✅ Settings screens with many options
- ✅ Forms longer than screen height

**Example (scrollable screen):**
```kotlin
ResponsiveScaffold(scrollable = true) {
    val sizes = LocalResponsiveSizes.current

    repeat(20) {
        ProductCard()
        Spacer(modifier = Modifier.height(sizes.spacingSmall))
    }
}
```

**Real implementation**: `LoginScreen.kt:98-158` uses ResponsiveScaffold.

---

## Loading States & Flash Screens

### What are Flash Screens?

**Flash screens** are jarring visual glitches that feel unprofessional:
- Brief flicker of previous screen during navigation
- Momentary display of wrong UI state
- Common in apps with poor state management

### MANDATORY: Use AvoqadoLoadingOverlay

**Component**: `app/src/main/java/com/jaac/avoqado_tpv/core/presentation/components/ShimmerEffect.kt:12`

#### ❌ BAD: Flash Screen Example

```kotlin
// User flow: WelcomeScreen → Enter amount → Navigate to PaymentScreen
// Problem: Brief flash of WelcomeScreen before PaymentScreen appears

// WelcomeScreen.kt
onStartPaymentWithAmount = { amount ->
    showAmountBottomSheet = false
    navController.navigate(NavRoute.Payment.route)  // ❌ Instant navigation
}

// PaymentScreen.kt
is PaymentState.Idle -> {
    LaunchedEffect(initialAmount) {
        if (initialAmount != null) {
            viewModel.submitAmount(initialAmount)  // ❌ Async - causes flash
        }
    }
}
```

**Result**: User sees:
1. Amount modal closes
2. Brief flash of WelcomeScreen ⚠️ (BAD UX)
3. PaymentScreen appears

#### ✅ GOOD: Loading Overlay Solution

```kotlin
// PaymentScreen.kt
is PaymentState.Idle -> {
    // ✅ Show loading overlay immediately to prevent flash
    if (initialAmount != null) {
        AvoqadoLoadingOverlay(
            message = "Preparando pago..."
        )
    }

    LaunchedEffect(initialAmount) {
        if (initialAmount != null) {
            viewModel.submitAmount(initialAmount)
        }
    }
}
```

**Result**: User sees:
1. Amount modal closes
2. **Smooth loading overlay** ✅ (Professional)
3. PaymentScreen appears

### MANDATORY Rules

#### 1. ALWAYS use same loading component

```kotlin
// ✅ Use AvoqadoLoadingOverlay everywhere
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoLoadingOverlay

// Benefits:
// - ✅ Consistent UX across entire app
// - ✅ Maintains brand identity
// - ✅ Easy to update globally
```

#### 2. ALWAYS show loading during state transitions

```kotlin
// ✅ Navigation with data
if (initialAmount != null) {
    AvoqadoLoadingOverlay(message = "Preparando...")
}

// ✅ Async operations
if (state is Processing) {
    AvoqadoLoadingOverlay(message = "Procesando...")
}

// ✅ SDK initialization
if (state is ConfiguringKernel) {
    AvoqadoLoadingOverlay(message = "Configurando terminal...")
}
```

#### 3. NEVER navigate without loading if data processing is involved

```kotlin
// ❌ WRONG: Instant navigation with async work
navController.navigate(Route.Payment)
viewModel.loadPaymentData()  // Flash screen!

// ✅ CORRECT: Show loading first
if (state is LoadingPaymentData) {
    AvoqadoLoadingOverlay(message = "Cargando...")
}
```

#### 4. Loading message should be contextual

```kotlin
// ✅ GOOD: Specific messages
"Preparando pago..."
"Procesando transacción..."
"Configurando terminal..."
"Activando dispositivo..."

// ❌ BAD: Generic messages
"Cargando..."
"Espere..."
"Procesando..."
```

### Common Flash Screen Scenarios & Fixes

| Scenario | Problem | Solution |
|----------|---------|----------|
| **Navigation with data** | Modal closes → flash → new screen | Add `AvoqadoLoadingOverlay` in destination screen's idle/loading state |
| **ViewModel init** | Empty state → flash → loaded state | Show loading overlay during init, hide when data ready |
| **API calls** | Old data → flash → new data | Use loading state between requests |
| **SDK operations** | Previous screen → flash → SDK screen | Add ConfiguringKernel state with loading overlay |

### Real Example: Payment Flow

```kotlin
// ✅ CORRECT: No flash screens
fun PaymentScreen(initialAmount: String?) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (state) {
        is PaymentState.Idle -> {
            // ✅ Prevent flash when coming from Home
            if (initialAmount != null) {
                AvoqadoLoadingOverlay(message = "Preparando pago...")
            }

            LaunchedEffect(initialAmount) {
                if (initialAmount != null) {
                    viewModel.submitAmount(initialAmount)
                }
            }
        }

        is PaymentState.CollectingRating -> {
            ReviewScreen(...)  // ✅ Smooth transition
        }

        is PaymentState.Processing -> {
            AvoqadoLoadingOverlay(message = "Procesando transacción...")
        }
    }
}
```

**User Experience**:
- ✅ No flash screens
- ✅ Always clear what's happening
- ✅ Professional, polished feel
- ✅ Matches Square Terminal / Toast POS quality

---

## Testing Checklist

### MANDATORY checklist before committing any screen:

#### 1. Wrap screen with ResponsiveScaffold
- [ ] Not using raw BoxWithConstraints
- [ ] ResponsiveScaffold configured correctly
- [ ] scrollable = false for workflow screens

#### 2. Use LocalResponsiveSizes.current
- [ ] All dynamic sizing uses tokens
- [ ] No hardcoded dp values in vertical layouts
- [ ] Spacing scales across devices

#### 3. Test in Android Studio Preview
- [ ] Preview with PAX A80 (1024x600) - minimum
- [ ] Preview with PAX A920 (1280x720) - target
- [ ] Preview with 10" (1280x800) - large
- [ ] All elements visible without scrolling

#### 4. Verify no scroll on workflow screens
- [ ] Login screen: No scroll
- [ ] PIN pad screen: No scroll
- [ ] Payment screen: No scroll
- [ ] Amount input: No scroll

#### 5. Check spacing ratios
- [ ] Elements look balanced on all sizes
- [ ] Not too cramped on small screens
- [ ] Not too spaced on large screens

#### 6. Test flash screens
- [ ] Navigate from different sources (Home, deep link, back button)
- [ ] Check for flash screens during navigation
- [ ] Verify loading overlay appears during async operations
- [ ] Test on slow network (throttle in DevTools)
- [ ] Test on slow device (PAX A80)
- [ ] Ensure loading messages are contextual

**Rule of thumb**: If you need to scroll on a **workflow screen** (login, payment, checkout), the layout is broken.

---

## Common Patterns

### Pattern 1: Login Screen

```kotlin
@Composable
fun LoginScreen() {
    ResponsiveScaffold(
        scrollable = false,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val sizes = LocalResponsiveSizes.current

        // Logo
        Image(
            painter = painterResource(R.drawable.logo),
            contentDescription = "Logo",
            modifier = Modifier.size(sizes.logoSize)
        )

        Spacer(modifier = Modifier.height(sizes.spacingLarge))

        // Title
        Text(
            text = "Ingresa tu PIN",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(sizes.spacingMedium))

        // PIN Pad
        PinPad(
            onPinEntered = { pin -> viewModel.login(pin) },
            modifier = Modifier.padding(horizontal = sizes.paddingScreen)
        )
    }
}

@Preview(name = "Small - PAX A80", device = "spec:width=1024dp,height=600dp")
@Preview(name = "Medium - PAX A920", device = "spec:width=1280dp,height=720dp")
@Preview(name = "Large - 10 inch", device = "spec:width=1280dp,height=800dp")
@Composable
private fun LoginScreenPreview() {
    AvoqadoTheme {
        LoginScreen()
    }
}
```

### Pattern 2: List Screen (Scrollable)

```kotlin
@Composable
fun ProductListScreen() {
    ResponsiveScaffold(
        scrollable = true  // ✅ Lists can scroll
    ) {
        val sizes = LocalResponsiveSizes.current

        LazyColumn {
            items(products, key = { it.id }) { product ->
                ProductCard(
                    product = product,
                    onClick = { viewModel.selectProduct(it) }
                )
                Spacer(modifier = Modifier.height(sizes.spacingSmall))
            }
        }
    }
}
```

### Pattern 3: Form Screen (Conditional Scroll)

```kotlin
@Composable
fun SettingsScreen() {
    ResponsiveScaffold(
        scrollable = true  // ✅ Many options, needs scroll
    ) {
        val sizes = LocalResponsiveSizes.current

        // Header (always visible)
        Text(
            text = "Configuración",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(sizes.paddingScreen)
        )

        Spacer(modifier = Modifier.height(sizes.spacingMedium))

        // Settings list (scrollable)
        SettingsList(
            settings = settings,
            onSettingClick = { /* ... */ }
        )
    }
}
```

### Pattern 4: Payment Confirmation (No Scroll)

```kotlin
@Composable
fun PaymentConfirmationScreen() {
    ResponsiveScaffold(
        scrollable = false,  // ✅ Critical workflow, no scroll
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        val sizes = LocalResponsiveSizes.current

        // Top: Transaction details
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(sizes.paddingScreen)
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Color.Green,
                modifier = Modifier.size(sizes.iconSizeLarge)
            )

            Spacer(modifier = Modifier.height(sizes.spacingMedium))

            Text(
                text = "Pago exitoso",
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(sizes.spacingSmall))

            Text(
                text = "$${amount}",
                style = MaterialTheme.typography.displayMedium
            )
        }

        // Bottom: Actions
        Row(
            horizontalArrangement = Arrangement.spacedBy(sizes.spacingMedium),
            modifier = Modifier.padding(sizes.paddingScreen)
        ) {
            Button(
                onClick = { /* Print receipt */ },
                modifier = Modifier.weight(1f)
            ) {
                Text("Imprimir")
            }

            Button(
                onClick = { /* New payment */ },
                modifier = Modifier.weight(1f)
            ) {
                Text("Nuevo Pago")
            }
        }
    }
}
```

---

## Performance Tips

### 1. Use remember for calculations

```kotlin
@Composable
fun MyScreen() {
    val sizes = LocalResponsiveSizes.current

    // ✅ Calculate once, remember result
    val iconSize = remember(sizes.iconSizeMedium) {
        sizes.iconSizeMedium * 1.5f
    }

    Icon(modifier = Modifier.size(iconSize))
}
```

### 2. Avoid recomposition on size changes

```kotlin
// ✅ Use derivedStateOf for expensive calculations
val isSmallScreen by remember {
    derivedStateOf { sizes.logoSize < 80.dp }
}

if (isSmallScreen) {
    // Show compact layout
} else {
    // Show expanded layout
}
```

### 3. LazyColumn keys for performance

```kotlin
LazyColumn {
    items(
        items = products,
        key = { it.id }  // ✅ Stable key for better performance
    ) { product ->
        ProductCard(product)
    }
}
```

---

## Debugging Responsive Issues

### Issue: Elements cut off on PAX A80

**Cause**: Hardcoded dp values or fixed sizes
**Solution**: Use ResponsiveScaffold + LocalResponsiveSizes tokens

### Issue: Too much empty space on large screens

**Cause**: Spacing not scaling properly
**Solution**: Use relative spacing tokens (`spacingSmall`, `spacingMedium`, `spacingLarge`)

### Issue: Flash screen during navigation

**Cause**: Missing loading state
**Solution**: Add `AvoqadoLoadingOverlay` during state transitions

### Issue: Content scrolling on login screen

**Cause**: Too many elements or fixed sizes
**Solution**: Reduce elements, use responsive sizing, test on PAX A80

---

## Component Styling (Design System)

### Philosophy

> **CRITICAL**: All inputs, buttons, and interactive cards should use **pill shape** (`RoundedCornerShape(50)`) for a modern, consistent appearance across the app.

This design language matches modern POS systems and provides:
- ✅ Friendly, approachable feel
- ✅ Clear interactive affordance
- ✅ Consistent brand identity
- ✅ Better touch targets on POS devices

---

### Pill Shape Constant

**File**: `core/presentation/components/AvoqadoTextField.kt`

```kotlin
/**
 * Default pill shape for all Avoqado components
 * Provides consistent, modern appearance across the app
 */
val AvoqadoPillShape: Shape = RoundedCornerShape(50)
```

Use this constant for consistency:

```kotlin
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoPillShape

// In any composable
OutlinedTextField(
    shape = AvoqadoPillShape,
    // ...
)

Button(
    shape = AvoqadoPillShape,
    // ...
)

Card(
    shape = AvoqadoPillShape,
    // ...
)
```

---

### Text Inputs

#### ✅ ALWAYS Use AvoqadoTextField

**File**: `core/presentation/components/AvoqadoTextField.kt`

```kotlin
// ✅ CORRECT: Use AvoqadoTextField (pill shape by default)
AvoqadoTextField(
    value = text,
    onValueChange = { text = it },
    label = "Email",
    placeholder = "user@example.com",
    leadingIcon = Icons.Default.Email,
    showClearButton = true
)
```

#### If Using OutlinedTextField Directly

```kotlin
// ✅ CORRECT: Add pill shape
OutlinedTextField(
    value = text,
    onValueChange = { text = it },
    label = { Text("Código de Activación") },
    shape = RoundedCornerShape(50),  // Pill shape
    // ...
)

// ❌ WRONG: Default rectangular shape
OutlinedTextField(
    value = text,
    onValueChange = { text = it },
    label = { Text("Código de Activación") },
    // Missing shape = pill shape!
)
```

---

### Buttons

```kotlin
// ✅ CORRECT: Pill-shaped button
Button(
    onClick = { /* ... */ },
    shape = RoundedCornerShape(50),  // Pill shape
    modifier = Modifier
        .fillMaxWidth()
        .height(56.dp)
) {
    Text("Activar Terminal")
}

// ❌ WRONG: Default rectangular button
Button(
    onClick = { /* ... */ },
    modifier = Modifier.fillMaxWidth()
) {
    Text("Activar Terminal")
}
```

---

### Cards (Info Display)

```kotlin
// ✅ CORRECT: Pill-shaped card for info display
Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(50),  // Pill shape
    colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    )
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Número de Serie",
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center
        )
        Text(
            text = "AVQD-2841548417",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}
```

---

### Keyboard Dismissal Pattern

> **MANDATORY**: All screens with text inputs MUST allow dismissing the keyboard by tapping outside.

```kotlin
@Composable
fun ScreenWithInput() {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Helper to dismiss keyboard
    val dismissKeyboard: () -> Unit = {
        keyboardController?.hide()
        focusManager.clearFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()  // Adjust for keyboard
            .verticalScroll(rememberScrollState())  // Allow scroll when keyboard open
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { dismissKeyboard() }  // Tap outside to dismiss
            .padding(24.dp)
    ) {
        // Your content with text fields...
    }
}
```

**Required imports:**
```kotlin
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
```

---

### Complete Example: Activation Screen Pattern

```kotlin
@Composable
fun ActivationScreen() {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    val dismissKeyboard: () -> Unit = {
        keyboardController?.hide()
        focusManager.clearFocus()
    }

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
                .verticalScroll(scrollState)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { dismissKeyboard() }
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Title
            Text(
                text = "Activar Terminal",
                style = MaterialTheme.typography.headlineLarge
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Info Card - Pill shaped
            Card(
                shape = RoundedCornerShape(50),
                // ...
            ) {
                // Serial number display
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Input - Pill shaped
            OutlinedTextField(
                shape = RoundedCornerShape(50),
                // ...
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Button - Pill shaped
            Button(
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("Activar Terminal")
            }
        }
    }
}
```

---

### Visual Reference

```
┌────────────────────────────────────────────┐
│                                            │
│         Activar Terminal                   │  ← Title
│                                            │
│  ╭────────────────────────────────────╮    │
│  │     Número de Serie                │    │  ← Pill Card
│  │     AVQD-2841548417                │    │
│  ╰────────────────────────────────────╯    │
│                                            │
│  ╭────────────────────────────────────╮    │
│  │ Código de Activación               │    │  ← Pill Input
│  │ A3F9K2                             │    │
│  ╰────────────────────────────────────╯    │
│                                            │
│  ╭────────────────────────────────────╮    │
│  │        Activar Terminal            │    │  ← Pill Button
│  ╰────────────────────────────────────╯    │
│                                            │
└────────────────────────────────────────────┘
```

---

### Component Styling Checklist

Before committing any screen with inputs:

- [ ] All `OutlinedTextField` use `shape = RoundedCornerShape(50)` or `AvoqadoTextField`
- [ ] All `Button` use `shape = RoundedCornerShape(50)`
- [ ] Info display `Card` use `shape = RoundedCornerShape(50)`
- [ ] Screen uses `imePadding()` for keyboard handling
- [ ] Screen uses `verticalScroll()` if content may be hidden by keyboard
- [ ] Tapping outside input dismisses keyboard (clickable + clearFocus)
- [ ] Text in cards/inputs is centered where appropriate

---

## Key Takeaways

1. **ALWAYS** use `ResponsiveScaffold` for all screens
2. **ALWAYS** use `LocalResponsiveSizes.current` for dynamic sizing
3. **NO SCROLLING** on workflow screens (login, PIN, payment)
4. **ALWAYS** use `AvoqadoLoadingOverlay` for loading states
5. **NEVER** hardcode dp values in vertical layouts
6. **TEST** on multiple device configs (PAX A80, A920, 10")
7. **PREVENT** flash screens with proper loading states

---

## Additional Resources

### Component Files
- ResponsiveScaffold: `core/presentation/components/ResponsiveScaffold.kt`
- AvoqadoLoadingOverlay: `core/presentation/components/ShimmerEffect.kt:12`
- LocalResponsiveSizes: `core/presentation/theme/ResponsiveSizes.kt`

### Example Implementations
- Login screen: `LoginScreen.kt:98-158`
- Payment screen: `PaymentScreen.kt:120`
- Merchant selection: `MerchantSelectionContent.kt:32`

### External References
- [Compose Layout Basics](https://developer.android.com/jetpack/compose/layouts/basics)
- [BoxWithConstraints](https://developer.android.com/reference/kotlin/androidx/compose/foundation/layout/package-summary#BoxWithConstraints(androidx.compose.ui.Modifier,androidx.compose.ui.Alignment,kotlin.Boolean,kotlin.Function1))

---

**Last Updated:** 2025-11-26
**Maintainer:** Development Team

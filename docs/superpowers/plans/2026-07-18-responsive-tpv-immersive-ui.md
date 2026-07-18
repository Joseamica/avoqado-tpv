# Responsive TPV Keypad and Immersive UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Increase the Nexgo N62 fast-payment keypad touch targets without changing the effective N86/PAX A910S keypad, and keep Android system bars hidden in the TPV activity and Checkout-owned modal windows.

**Architecture:** `ResponsiveSizes` will classify dimension-derived screen profiles and own all keyboard tokens. `CustomKeyboard` and the amount overlay will consume those tokens through one configuration helper. A reusable window controller will apply Android immersive mode to `MainActivity`; a Compose effect will apply the same policy to app-owned dialog windows used by Checkout.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, AndroidX `WindowInsetsControllerCompat`, JUnit 4, Truth, Gradle Android variants, ADB.

**Approved design:** `docs/superpowers/specs/2026-07-18-responsive-tpv-immersive-ui-design.md`

## Scope and file map

### Create

- `app/src/main/java/com/jaac/avoqado_tpv/core/presentation/systemui/ImmersiveSystemUi.kt`
  - Shared, idempotent window controller.
  - Compose bridge for `DialogWindowProvider` windows.
- `app/src/test/java/com/jaac/avoqado_tpv/core/presentation/components/ResponsiveSizesTest.kt`
  - Regression tests for N62, compact portrait, N86/A910S-sized portrait, and legacy categories.

### Modify

- `app/src/main/java/com/jaac/avoqado_tpv/core/presentation/components/ResponsiveScaffold.kt`
  - Add `ScreenProfile`.
  - Centralize physical-screen calculation.
  - Set N62 keyboard tokens to `60.dp / 72.dp / 4.dp / 22.sp`.
  - Preserve existing `sizeCategory` behavior and effective portrait keyboard dimensions.
- `app/src/main/java/com/jaac/avoqado_tpv/core/presentation/components/CustomKeyboard.kt`
  - Remove duplicated `LocalConfiguration` calculations.
  - Consume `ResponsiveSizes` keyboard tokens.
- `app/src/main/java/com/jaac/avoqado_tpv/core/presentation/components/AmountInputBottomSheet.kt`
  - Compact non-interactive spacing on `CompactSquare`.
  - Add an N62 preview.
- `app/src/main/java/com/jaac/avoqado_tpv/core/presentation/screens/FastPaymentEntryScreen.kt`
  - Use the typed profile.
  - Add an N86 portrait regression preview while retaining N62 and A910S previews.
- `app/src/main/java/com/jaac/avoqado_tpv/MainActivity.kt`
  - Replace tutorial/model-specific system-bar logic with the shared TPV controller.
- `app/src/main/java/com/jaac/avoqado_tpv/core/presentation/components/SettingsBottomSheet.kt`
  - Replace the private tutorial-only effect with the shared effect.
- `app/src/main/java/com/jaac/avoqado_tpv/features/checkout/presentation/CheckoutScreen.kt`
  - Apply immersive mode to cart, customer, modifier, and unavailable-product modal windows.
- `app/src/main/java/com/jaac/avoqado_tpv/features/checkout/presentation/components/NoteDialog.kt`
  - Apply immersive mode inside the dialog-owned window.
- `app/src/main/java/com/jaac/avoqado_tpv/features/checkout/presentation/components/TaxPercentDialog.kt`
  - Apply immersive mode inside the dialog-owned window.
- `app/src/main/java/com/jaac/avoqado_tpv/features/checkout/presentation/components/cart/CartDetailsSheet.kt`
  - Keep the overflow popup from taking system-window focus while preserving
    Back, outside-dismiss, and menu-item behavior.

## Task 1: Lock the responsive contract with unit tests

**Files:**

- Create: `app/src/test/java/com/jaac/avoqado_tpv/core/presentation/components/ResponsiveSizesTest.kt`
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/core/presentation/components/ResponsiveScaffold.kt`

- [ ] **Step 1: Add the failing responsive-size tests**

Create the test file with representative Android dp viewports:

```kotlin
package com.jaac.avoqado_tpv.core.presentation.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import androidx.compose.ui.unit.dp

class ResponsiveSizesTest {

    @Test
    fun `N62 square viewport uses larger accessible keypad tokens`() {
        val sizes = ResponsiveSizes.calculate(height = 480.dp, width = 480.dp)

        assertThat(sizes.screenProfile).isEqualTo(ScreenProfile.CompactSquare)
        assertThat(sizes.isSquareScreen).isTrue()
        assertThat(sizes.keyboardButtonSize).isEqualTo(60.dp)
        assertThat(sizes.keyboardActionWidth).isEqualTo(72.dp)
        assertThat(sizes.keyboardSpacing).isEqualTo(4.dp)
        assertThat(sizes.keyboardFontSize).isEqualTo(22)
    }

    @Test
    fun `compact portrait viewport keeps existing portrait keypad tokens`() {
        val sizes = ResponsiveSizes.calculate(height = 568.dp, width = 360.dp)

        assertThat(sizes.screenProfile).isEqualTo(ScreenProfile.CompactPortrait)
        assertThat(sizes.isSquareScreen).isFalse()
        assertThat(sizes.keyboardButtonSize).isEqualTo(80.dp)
        assertThat(sizes.keyboardActionWidth).isEqualTo(100.dp)
        assertThat(sizes.keyboardSpacing).isEqualTo(8.dp)
        assertThat(sizes.keyboardFontSize).isEqualTo(24)
    }

    @Test
    fun `N86 and A910S sized portrait viewport keeps existing keypad tokens`() {
        val sizes = ResponsiveSizes.calculate(height = 640.dp, width = 360.dp)

        assertThat(sizes.screenProfile).isEqualTo(ScreenProfile.RegularPortrait)
        assertThat(sizes.isSquareScreen).isFalse()
        assertThat(sizes.keyboardButtonSize).isEqualTo(80.dp)
        assertThat(sizes.keyboardActionWidth).isEqualTo(100.dp)
        assertThat(sizes.keyboardSpacing).isEqualTo(8.dp)
        assertThat(sizes.keyboardFontSize).isEqualTo(24)
    }

    @Test
    fun `legacy size categories remain stable`() {
        assertThat(ResponsiveSizes.calculate(480.dp, 480.dp).sizeCategory).isEqualTo("small")
        assertThat(ResponsiveSizes.calculate(568.dp, 360.dp).sizeCategory).isEqualTo("small")
        assertThat(ResponsiveSizes.calculate(640.dp, 360.dp).sizeCategory).isEqualTo("medium")
        assertThat(ResponsiveSizes.calculate(800.dp, 400.dp).sizeCategory).isEqualTo("large")
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails for the missing profile/new N62 values**

Run:

```bash
./gradlew :app:testSandboxDebugUnitTest \
  --tests "com.jaac.avoqado_tpv.core.presentation.components.ResponsiveSizesTest"
```

Expected: compilation/test failure because `ScreenProfile` does not exist and the square tokens still resolve to `52.dp / 64.dp / 18`.

- [ ] **Step 3: Add the typed profile and central configuration helper**

In `ResponsiveScaffold.kt`, add:

```kotlin
enum class ScreenProfile {
    CompactSquare,
    CompactPortrait,
    RegularPortrait,
}
```

Add `screenProfile: ScreenProfile` immediately after `screenWidth` in `ResponsiveSizes`.

At the start of `calculate`, keep the current category thresholds and add:

```kotlin
val isSquare = kotlin.math.abs((height - width).value) < 80f
val screenProfile = when {
    isSquare -> ScreenProfile.CompactSquare
    height < 600.dp -> ScreenProfile.CompactPortrait
    else -> ScreenProfile.RegularPortrait
}
val category = when {
    isSquare -> "small"
    height < 600.dp -> "small"
    height < 700.dp -> "medium"
    else -> "large"
}
```

Pass `screenProfile = screenProfile` into the constructor and replace only the keyboard token block with:

```kotlin
keyboardButtonSize = when (screenProfile) {
    ScreenProfile.CompactSquare -> 60.dp
    ScreenProfile.CompactPortrait,
    ScreenProfile.RegularPortrait -> 80.dp
},
keyboardActionWidth = when (screenProfile) {
    ScreenProfile.CompactSquare -> 72.dp
    ScreenProfile.CompactPortrait,
    ScreenProfile.RegularPortrait -> 100.dp
},
keyboardSpacing = if (screenProfile == ScreenProfile.CompactSquare) 4.dp else 8.dp,
keyboardFontSize = if (screenProfile == ScreenProfile.CompactSquare) 22 else 24,
```

Add a reusable calculation helper:

```kotlin
@Composable
fun rememberResponsiveSizes(): ResponsiveSizes {
    val configuration = LocalConfiguration.current
    return remember(configuration.screenHeightDp, configuration.screenWidthDp) {
        ResponsiveSizes.calculate(
            height = configuration.screenHeightDp.dp,
            width = configuration.screenWidthDp.dp,
        )
    }
}
```

Import `androidx.compose.runtime.remember`, and replace the calculation inside `ResponsiveScaffold` with:

```kotlin
val sizes = rememberResponsiveSizes()
```

- [ ] **Step 4: Rerun the focused tests**

Run:

```bash
./gradlew :app:testSandboxDebugUnitTest \
  --tests "com.jaac.avoqado_tpv.core.presentation.components.ResponsiveSizesTest"
```

Expected: `BUILD SUCCESSFUL`; all four responsive tests pass.

- [ ] **Step 5: Commit the responsive contract**

```bash
git add \
  app/src/main/java/com/jaac/avoqado_tpv/core/presentation/components/ResponsiveScaffold.kt \
  app/src/test/java/com/jaac/avoqado_tpv/core/presentation/components/ResponsiveSizesTest.kt
git commit -m "test(ui): define responsive terminal profiles"
```

If this workspace still prevents writes to `.git/index`, do not change Git permissions; record the limitation and continue with the working-tree changes.

## Task 2: Move the keypad and amount layout onto shared tokens

**Files:**

- Modify: `app/src/main/java/com/jaac/avoqado_tpv/core/presentation/components/CustomKeyboard.kt`
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/core/presentation/components/AmountInputBottomSheet.kt`
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/core/presentation/screens/FastPaymentEntryScreen.kt`

- [ ] **Step 1: Remove duplicated screen detection from `CustomKeyboard`**

Replace the `LocalConfiguration`/`abs` block with:

```kotlin
val sizes = rememberResponsiveSizes()
val btnSize = sizes.keyboardButtonSize
val actionWidth = sizes.keyboardActionWidth
val gap = sizes.keyboardSpacing
val fontSize = sizes.keyboardFontSize
```

Remove now-unused imports for `remember`, `LocalConfiguration`, `Dp`, and `kotlin.math.abs`. Do not change input rules, callbacks, key ordering, colors, or the tall confirmation-button calculation.

- [ ] **Step 2: Compact only the non-interactive N62 overlay spacing**

Near the top of `AmountInputBottomSheet`, resolve:

```kotlin
val sizes = rememberResponsiveSizes()
val isCompactSquare = sizes.screenProfile == ScreenProfile.CompactSquare
```

Use these exact surrounding-layout values:

```kotlin
Column(
    modifier = Modifier
        .fillMaxWidth()
        .padding(
            horizontal = if (isCompactSquare) 16.dp else 24.dp,
            vertical = if (isCompactSquare) 12.dp else 24.dp,
        ),
    horizontalAlignment = Alignment.CenterHorizontally,
) {
```

Change the header bottom padding to `8.dp` only for `CompactSquare`, the amount text to `40.sp` with `8.dp` vertical padding on `CompactSquare`, and the error spacer to `8.dp` on `CompactSquare`. Keep the portrait values at `16.dp`, `48.sp`, `16.dp`, and `12.dp`.

This frees at least the `32.dp` of extra vertical space consumed by four `60.dp` key rows compared with the former four `52.dp` rows.

- [ ] **Step 3: Use the typed profile in the full-screen fast-payment layout**

In `FastPaymentEntryScreen`, replace:

```kotlin
val isCompact = sizes.isSquareScreen
```

with:

```kotlin
val isCompact = sizes.screenProfile == ScreenProfile.CompactSquare
```

Import `ScreenProfile`. Preserve the existing square-only instruction, amount, and spacer compaction.

- [ ] **Step 4: Add three terminal regression previews**

Keep the existing N62 and A910S previews. Add:

```kotlin
private const val NEXGO_N86 = "spec:width=720px,height=1280px,dpi=320"

@Preview(name = "NEXGO N86", device = NEXGO_N86, showSystemUi = true)
@Composable
private fun FastPaymentEntryScreenN86Preview() {
    AvoqadoTheme {
        FastPaymentEntryScreen()
    }
}
```

Name the existing previews `"PAX A910S"` and `"NEXGO N62"` so the Preview panel is unambiguous.

Add a second preview to `AmountInputBottomSheet.kt`:

```kotlin
private const val NEXGO_N62 = "spec:width=480px,height=480px,dpi=160"

@Preview(name = "Amount overlay - NEXGO N62", device = NEXGO_N62, showSystemUi = true)
@Composable
private fun AmountInputBottomSheetN62Preview() {
    AvoqadoTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            AmountInputBottomSheet(
                visible = true,
                onDismiss = {},
                onConfirm = {},
            )
        }
    }
}
```

- [ ] **Step 5: Verify responsive tests and compile shared Compose code**

Run:

```bash
./gradlew \
  :app:testSandboxDebugUnitTest \
  :app:compileSandboxDebugKotlin \
  --tests "com.jaac.avoqado_tpv.core.presentation.components.ResponsiveSizesTest"
```

Expected: `BUILD SUCCESSFUL`.

Open the three `FastPaymentEntryScreen` previews and the N62 amount-overlay preview. Verify:

- N62 numeric keys measure `60.dp`, use `22.sp` labels, and remain fully visible.
- The title, amount, close affordance, error area, and confirm column do not overlap or clip.
- N86 and A910S retain `80.dp` numeric keys, `100.dp` action width, `8.dp` gaps, and `24.sp` labels.

- [ ] **Step 6: Commit the keypad adaptation**

```bash
git add \
  app/src/main/java/com/jaac/avoqado_tpv/core/presentation/components/CustomKeyboard.kt \
  app/src/main/java/com/jaac/avoqado_tpv/core/presentation/components/AmountInputBottomSheet.kt \
  app/src/main/java/com/jaac/avoqado_tpv/core/presentation/screens/FastPaymentEntryScreen.kt
git commit -m "feat(ui): enlarge keypad on compact square terminals"
```

Apply the same read-only Git-index fallback described in Task 1.

## Task 3: Introduce one safe immersive-system-UI controller

**Files:**

- Create: `app/src/main/java/com/jaac/avoqado_tpv/core/presentation/systemui/ImmersiveSystemUi.kt`
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/MainActivity.kt`
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/core/presentation/components/SettingsBottomSheet.kt`

- [ ] **Step 1: Create the reusable window controller and dialog effect**

Create `ImmersiveSystemUi.kt` with:

```kotlin
package com.jaac.avoqado_tpv.core.presentation.systemui

import android.view.ViewTreeObserver
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import timber.log.Timber

fun applyTpvImmersiveMode(window: Window) {
    runCatching {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        window.decorView.post {
            runCatching {
                WindowInsetsControllerCompat(window, window.decorView)
                    .hide(WindowInsetsCompat.Type.systemBars())
            }.onFailure { error ->
                Timber.w(error, "Could not reapply TPV immersive mode")
            }
        }
    }.onFailure { error ->
        Timber.w(error, "Could not apply TPV immersive mode")
    }
}

@Composable
fun ImmersiveSystemUiEffect() {
    val view = LocalView.current

    DisposableEffect(view) {
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window

        if (dialogWindow == null) {
            onDispose {}
        } else {
            val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                if (hasFocus) {
                    applyTpvImmersiveMode(dialogWindow)
                }
            }

            applyTpvImmersiveMode(dialogWindow)
            dialogWindow.decorView.viewTreeObserver
                .addOnWindowFocusChangeListener(focusListener)

            onDispose {
                val observer = dialogWindow.decorView.viewTreeObserver
                if (observer.isAlive) {
                    observer.removeOnWindowFocusChangeListener(focusListener)
                }
            }
        }
    }
}
```

The controller intentionally catches vendor-specific failures so system UI cannot crash a sale. The Compose effect intentionally no-ops outside a dialog-owned window.

- [ ] **Step 2: Make `MainActivity` use the controller for every TPV flavor**

Import `applyTpvImmersiveMode`. Replace all three calls to `applyTutorialImmersiveNavigation()` in `onCreate`, `onWindowFocusChanged`, and `onResume` with:

```kotlin
applyTpvImmersiveMode(window)
```

Delete the private `applyTutorialImmersiveNavigation()` function and its model/flavor exclusions. Remove the now-unused insets imports from `MainActivity`. Do not alter permission launchers, external payment intents, lifecycle recovery, or edge-to-edge initialization.

- [ ] **Step 3: Replace the duplicated Settings-only effect**

In `SettingsBottomSheet`, replace:

```kotlin
HideSystemBarsInTutorialDialog()
```

with:

```kotlin
ImmersiveSystemUiEffect()
```

Import the shared effect, delete the private `HideSystemBarsInTutorialDialog` function, and remove only imports that become unused. Keep `BuildConfig` because the sheet still displays `VERSION_NAME`.

- [ ] **Step 4: Compile both processor families**

Run:

```bash
./gradlew \
  :app:compileSandboxDebugKotlin \
  :app:compileNexgoDebugKotlin
```

Expected: `BUILD SUCCESSFUL`; no unresolved Android window or Compose effect symbols.

- [ ] **Step 5: Commit the immersive foundation**

```bash
git add \
  app/src/main/java/com/jaac/avoqado_tpv/core/presentation/systemui/ImmersiveSystemUi.kt \
  app/src/main/java/com/jaac/avoqado_tpv/MainActivity.kt \
  app/src/main/java/com/jaac/avoqado_tpv/core/presentation/components/SettingsBottomSheet.kt
git commit -m "feat(ui): apply immersive mode across TPV windows"
```

Apply the same read-only Git-index fallback described in Task 1.

## Task 4: Apply immersive behavior to Checkout-owned modal windows

**Files:**

- Modify: `app/src/main/java/com/jaac/avoqado_tpv/features/checkout/presentation/CheckoutScreen.kt`
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/features/checkout/presentation/components/NoteDialog.kt`
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/features/checkout/presentation/components/TaxPercentDialog.kt`
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/features/checkout/presentation/components/cart/CartDetailsSheet.kt`

- [ ] **Step 1: Apply the effect inside each Checkout sheet window**

Import:

```kotlin
import com.jaac.avoqado_tpv.core.presentation.systemui.ImmersiveSystemUiEffect
```

As the first statement in the content lambda of each `ModalBottomSheet`, add:

```kotlin
ImmersiveSystemUiEffect()
```

Apply it to exactly these three Checkout sheets:

1. `CartDetailsSheet`
2. `CustomerSelectorSheet`
3. `ProductDetailSheet`

Do not add it to the full-screen search or barcode-scanner views because they share the activity window.

- [ ] **Step 2: Apply the effect inside all three Checkout alert-dialog windows**

For `UnavailableProductDialog`, insert this exact statement immediately after
the existing `Column {` in the `text` lambda:

```kotlin
ImmersiveSystemUiEffect()
```

For `NoteDialog`, insert the same exact statement immediately before its
existing `OutlinedTextField` inside the `text` column.

For `TaxPercentDialog`, insert the same exact statement immediately before its
existing preset `Row` inside the `text` column.

The effect emits no visual node and therefore does not change dialog spacing.

- [ ] **Step 3: Prevent the cart overflow popup from taking system-window focus**

In `CartDetailsSheet`, add a `BackHandler` that closes the menu while it is
expanded, and configure the popup as non-focusable:

```kotlin
BackHandler(enabled = showMenu) {
    showMenu = false
}

DropdownMenu(
    expanded = showMenu,
    onDismissRequest = { showMenu = false },
    properties = PopupProperties(focusable = false),
) {
```

This keeps immersive mode active while retaining menu-item taps, outside
dismissal, and Back dismissal.

- [ ] **Step 4: Run Checkout regressions and variant compilation**

Run:

```bash
./gradlew :app:testSandboxDebugUnitTest \
  --tests "com.jaac.avoqado_tpv.core.presentation.components.ResponsiveSizesTest" \
  --tests "com.jaac.avoqado_tpv.features.checkout.presentation.CheckoutViewModelTest" \
  --tests "com.jaac.avoqado_tpv.features.checkout.presentation.CheckoutViewModelReferralTest"

./gradlew \
  :app:compileNexgoDebugKotlin \
  :app:compileNexgoProdDebugKotlin \
  :app:compileProductionDebugKotlin
```

Expected: `BUILD SUCCESSFUL`; responsive and Checkout business-logic tests pass, and shared UI compiles for sandbox PAX, sandbox Nexgo, production Nexgo, and production PAX.

- [ ] **Step 5: Review the diff for scope containment**

Run:

```bash
git diff --check
git diff -- \
  app/src/main/java/com/jaac/avoqado_tpv/MainActivity.kt \
  app/src/main/java/com/jaac/avoqado_tpv/core/presentation/components/ResponsiveScaffold.kt \
  app/src/main/java/com/jaac/avoqado_tpv/core/presentation/components/CustomKeyboard.kt \
  app/src/main/java/com/jaac/avoqado_tpv/core/presentation/components/AmountInputBottomSheet.kt \
  app/src/main/java/com/jaac/avoqado_tpv/core/presentation/screens/FastPaymentEntryScreen.kt \
  app/src/main/java/com/jaac/avoqado_tpv/core/presentation/systemui/ImmersiveSystemUi.kt \
  app/src/main/java/com/jaac/avoqado_tpv/core/presentation/components/SettingsBottomSheet.kt \
  app/src/main/java/com/jaac/avoqado_tpv/features/checkout/presentation/CheckoutScreen.kt \
  app/src/main/java/com/jaac/avoqado_tpv/features/checkout/presentation/components/NoteDialog.kt \
  app/src/main/java/com/jaac/avoqado_tpv/features/checkout/presentation/components/TaxPercentDialog.kt \
  app/src/main/java/com/jaac/avoqado_tpv/features/checkout/presentation/components/cart/CartDetailsSheet.kt \
  app/src/test/java/com/jaac/avoqado_tpv/core/presentation/components/ResponsiveSizesTest.kt
```

Expected:

- No whitespace errors.
- No `Build.MODEL`, manufacturer, processor, or plan-tier branching in responsive code.
- No changes to payment state, payment repositories, processor integrations, cart arithmetic, or Room.
- No unrelated dirty-worktree files included in this feature.

- [ ] **Step 6: Commit Checkout modal adoption**

```bash
git add \
  app/src/main/java/com/jaac/avoqado_tpv/features/checkout/presentation/CheckoutScreen.kt \
  app/src/main/java/com/jaac/avoqado_tpv/features/checkout/presentation/components/NoteDialog.kt \
  app/src/main/java/com/jaac/avoqado_tpv/features/checkout/presentation/components/TaxPercentDialog.kt \
  app/src/main/java/com/jaac/avoqado_tpv/features/checkout/presentation/components/cart/CartDetailsSheet.kt
git commit -m "fix(checkout): keep app modals in immersive mode"
```

Apply the same read-only Git-index fallback described in Task 1.

## Task 5: Mandatory physical-device and ADB validation

**Devices:**

- Nexgo N62
- Nexgo N86
- PAX A910S

- [ ] **Step 1: Confirm a single intended device is connected**

Run:

```bash
adb devices -l
adb shell wm size
adb shell wm density
```

Expected: one device in `device` state. Record physical and override size/density so preview assumptions can be compared with hardware.

- [ ] **Step 2: Build and install the correct FREE debug flavor**

For N62 or N86:

```bash
./gradlew :app:installNexgoDebug
```

For PAX A910S:

```bash
./gradlew :app:installSandboxDebug
```

Expected: `BUILD SUCCESSFUL` and installation succeeds. Do not install a production/release APK during this UI verification.

- [ ] **Step 3: Start mandatory log capture**

Run:

```bash
./scripts/capture-logs.sh all start
```

Expected: Android capture starts and prints its capture destination under
`/tmp/avoqado-logs`.

- [ ] **Step 4: Validate fast payment on each terminal**

On N62:

- Open `Pago Rápido`.
- Confirm the 12 numeric/clear/decimal keys are visibly larger than the previous `52.dp` layout.
- Confirm the complete keypad, amount, title, backspace, and confirm action remain on-screen.
- Tap every digit, decimal, clear, backspace, and confirm.
- Confirm invalid zero still shows `Ingresa un monto mayor a $0.00`.

On N86 and A910S:

- Repeat the input sequence.
- Confirm keyboard size and surrounding portrait layout match the prior effective UI.
- Confirm there is no clipping in portrait orientation.

- [ ] **Step 5: Validate Checkout modal system bars on each terminal**

With at least one Checkout item:

1. Tap `Cobrar` to open cart details.
2. Open customer selection.
3. Open a product with modifiers.
4. Open note and unavailable-product dialogs.
5. If tax is re-enabled in the current checkout configuration, open its dialog.

At every app-owned modal:

- Android square/circle/back controls remain hidden.
- Content extends to the intended edge without a black navigation strip.
- Swipe from the system edge reveals transient bars.
- After interaction/focus returns, the bars hide again.
- Dismissal does not leave persistent bars on the underlying activity or sheet.

Do not attempt to hide permission prompts, keyboard/IME UI, or an external AngelPay/Blumon application.

- [ ] **Step 6: Stop and inspect logs**

Run:

```bash
./scripts/capture-logs.sh all stop
./scripts/capture-logs.sh all read
adb logcat -d | rg -i "AndroidRuntime|FATAL EXCEPTION|ANR|Could not apply TPV immersive mode|Could not reapply TPV immersive mode"
```

Expected:

- No `FATAL EXCEPTION`, ANR, or new system-UI-related crash.
- No repeated immersive-mode warnings.
- Normal payment and Checkout navigation continue to function.

- [ ] **Step 7: Final verification**

Run:

```bash
git diff --check
git status --short
```

Expected: only the intended feature files plus the approved spec/plan are part of this work; all pre-existing unrelated user changes remain untouched.

## Acceptance checklist

- [ ] N62 keypad buttons are `60.dp`, labels are `22.sp`, and action width is `72.dp`.
- [ ] N62 touch targets never fall below `56.dp` in the implemented layout.
- [ ] N86 and A910S keep the former effective `80.dp / 100.dp / 8.dp / 24.sp` keypad.
- [ ] Responsive decisions use dimensions/aspect only, never model, manufacturer, flavor, plan, or processor.
- [ ] Activity status/navigation bars are hidden on Nexgo and PAX.
- [ ] Checkout cart, customer, modifier, note, tax, and unavailable-product modal windows apply immersive mode.
- [ ] The cart overflow menu stays immersive and still dismisses by Back or outside tap.
- [ ] Transient swipe access to system bars still works.
- [ ] Permission prompts and external payment apps are unaffected.
- [ ] Focused unit tests and all four relevant compile variants pass.
- [ ] Mandatory ADB/log validation passes on N62, N86, and A910S.

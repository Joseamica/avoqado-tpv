# Responsive TPV Keypad and Immersive UI Design

**Date:** 2026-07-18  
**Status:** Approved for implementation planning  
**Tier:** FREE  
**Scope:** Avoqado TPV Android UI

## 1. Problem

Avoqado TPV runs on terminals with materially different screen shapes and
available space:

- Nexgo N62: small square display.
- Nexgo N86: portrait display.
- PAX A910S: portrait display with more vertical space.

The shared `CustomKeyboard` currently identifies square screens and hardcodes a
smaller profile (`52.dp` buttons and `18.sp` labels). On the N62 this makes the
fast-payment keypad harder to read and operate with fingers.

Separately, Android's native navigation bar can reappear when app-owned modal
windows such as `ModalBottomSheet` gain focus. The existing immersive behavior
is restricted to the tutorial emulator and explicitly excludes Nexgo models,
so it does not provide consistent dedicated-terminal behavior.

## 2. Goals

1. Make the N62 numeric keypad easier to read and tap.
2. Preserve the existing N86 and A910S layouts unless their available space
   requires adaptation.
3. Establish reusable, dimension-based responsive profiles without checking
   hardware model names.
4. Keep Android system bars hidden throughout normal TPV operation, including
   app-owned checkout sheets and dialogs.
5. Preserve transient system-bar access through a swipe gesture.
6. Keep the change isolated from payment, order, and navigation business logic.

## 3. Non-goals

- Redesigning every TPV screen in this change.
- Changing payment state, `PaymentViewModel`, processor integrations, or money
  calculations.
- Hiding Android permission dialogs, the IME, or other system-owned surfaces.
- Introducing backend configuration or paid-tier gating.
- Scaling the entire application or changing Android display density.

## 4. Responsive Strategy

### 4.1 Profiles

Responsive behavior will be derived from screen dimensions and aspect ratio:

- `CompactSquare`: constrained square screens such as the N62.
- `CompactPortrait`: portrait windows whose usable height is constrained,
  including N86 configurations when Android reports a compact viewport.
- `RegularPortrait`: portrait terminals with more available height such as the
  A910S.

No profile may depend on `Build.MODEL`, manufacturer, flavor, or processor.

### 4.2 Token ownership

`ResponsiveSizes` remains the source of reusable size tokens. The current
keyboard-size duplication inside `CustomKeyboard` will be removed or reduced
to a safe fallback so consumers cannot silently disagree.

Add a typed `ScreenProfile` value to `ResponsiveSizes`. Keep the existing
`sizeCategory` calculation temporarily and unchanged for current consumers;
newly adapted components use the typed profile so this change does not require
a repository-wide migration.

### 4.3 Compact-square keypad

The N62 profile will prioritize the keypad over decorative vertical spacing:

- Numeric targets: target approximately `60.dp`, never below `56.dp`.
- Numeric labels: target approximately `22.sp`.
- Action column: target approximately `72.dp`.
- Inter-key spacing: approximately `4.dp`.
- Confirmation action retains the current tall, prominent shape.

Exact values may be clamped downward only when the measured container cannot
fit them. Header, amount, and section spacing may compact on square screens so
the larger keypad remains fully visible without scrolling or clipping.

Portrait profiles retain their current effective sizes unless preview or
physical-device verification reveals overflow.

## 5. Immersive System UI Strategy

### 5.1 Activity window

Replace the tutorial-only policy with a reusable immersive controller for
normal TPV operation:

- Draw edge-to-edge.
- Hide status and navigation bars using `WindowInsetsControllerCompat`.
- Use `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`.
- Reapply on activity creation, resume, and regained window focus.
- Do not exclude Nexgo by model name.

### 5.2 App-owned modal windows

Compose modal sheets and dialogs can own a separate Android window. A reusable
Compose effect will:

1. Resolve the app-owned dialog window through `DialogWindowProvider`.
2. Apply edge-to-edge and the same transient immersive behavior.
3. Reapply after the window is attached or regains control.
4. Perform no action when no dialog window is present.

The first rollout applies this effect to Checkout's:

- Cart details sheet.
- Cart details overflow menu.
- Customer selector sheet.
- Product modifier sheet.
- Note, tax-percent, and unavailable-product dialogs.

Existing reusable app dialogs may adopt the same effect when they are already
in the touched call path. A repository-wide modal migration is a separate,
follow-up task.

### 5.3 Safety

System permission prompts and external payment applications are not modified.
Transient swipe access prevents staff from being permanently locked out of
system navigation. Dismissing a modal must restore focus without flashing a
persistent navigation bar.

## 6. Component Boundaries

### Responsive layout

- `ResponsiveSizes`: calculates dimension-derived profiles and tokens.
- `CustomKeyboard`: renders keys using supplied/resolved tokens.
- `AmountInputBottomSheet`: compacts surrounding content on square screens.
- `FastPaymentEntryScreen`: continues using shared responsive tokens.

### Immersive UI

- A small window-level controller owns the Android insets calls.
- `MainActivity` invokes the controller at lifecycle boundaries.
- A Compose effect bridges app-owned modal windows to the controller.
- Checkout uses the reusable effect; it does not duplicate window APIs.

## 7. Error Handling

- Missing dialog-window access is a no-op, not a crash.
- Insets calls remain idempotent and safe to repeat.
- Unsupported or vendor-specific behavior must degrade to normal Android
  navigation rather than block the payment UI.
- No exception from immersive reapplication may terminate the activity.

## 8. Accessibility

- N62 keypad targets meet or exceed `56.dp` wherever the container permits.
- Labels remain high contrast through `MaterialTheme`.
- Confirmation and destructive actions keep distinct shapes and hierarchy.
- The layout must not rely on color alone.
- Transient bars remain available by swipe for recovery and accessibility.

## 9. Verification

### Automated

- Unit tests for responsive-profile calculation using representative square,
  compact portrait, and regular portrait dimensions.
- Compose previews for N62, N86, and A910S.
- Existing checkout and payment unit tests remain green.
- Compilation for the relevant Nexgo and PAX variants.

### Physical devices

For N62, N86, and A910S:

1. Launch fast payment and verify keypad readability and tap comfort.
2. Enter, clear, backspace, use decimal, and confirm an amount.
3. Open Checkout cart details, customer selector, and modifier sheet.
4. Confirm native square/circle/back controls remain hidden.
5. Swipe to reveal transient bars and confirm they hide again.
6. Navigate back and repeat after app resume.
7. Capture logs using `./scripts/capture-logs.sh`.

ADB monitoring is mandatory during physical verification.

## 10. Acceptance Criteria

- The N62 fast-payment keypad is visibly larger and fully operable without
  clipping.
- N86 and A910S retain their current visual proportions.
- The implementation contains no hardware-model checks for responsive layout.
- Checkout sheets do not leave the Android navigation bar visible.
- System bars can still be revealed transiently by swipe.
- Payment behavior and amounts are unchanged.
- Sandbox and production behavior stay synchronized through shared `main`
  source code.

## 11. Rollout

Ship as a FREE UI improvement in the shared Android source set. Validate first
on sandbox builds and all three physical terminal types, then include it in the
normal production APK release process.

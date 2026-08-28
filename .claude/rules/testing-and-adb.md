# Testing & ADB Policy

## 🔴 Emoji en nombres de test: NO (rompen la caché de build de Gradle)

En logs, KDoc y comentarios el emoji está bien. Pero un `fun \`🔴 no se confunde con un pago\`()`
hace que Kotlin bautice las clases anónimas de ese test con ese nombre — genera el ARCHIVO
`…Test$🔴 no se confunde con un pago$1.class`, que el packer de la caché no puede leer. La tarea de
transform revienta con «Could not get file mode for …», un fallo que NO es de tu código y que no
menciona la causa. Acentos y em-dash (—) sí funcionan; sólo los emoji rompen.

Para marcar criticidad en el nombre, la convención es **`P1` / `P2` / `P3`** (antes `🔴` / `🟠` /
`🟡`). Lo vigila `:app:checkNoEmojiInTestNames`, que corre solo antes de cualquier tarea de test —
y nombra archivo y línea del culpable. Mismo guardia en `avoqado-android`. Caso que lo originó:
2026-08-20, dos tests de `DeclineTicketTest` aquí y 35 en Android.

## The Golden Rule: No Regressions

When you fix or implement something, you MUST NOT break something else. Before committing: (1) new feature works, (2) existing features still work, (3) related features unaffected.

## Pre-Commit Checklist

- [ ] `./gradlew compileDebugKotlin` passes
- [ ] `./gradlew lint --continue` passes
- [ ] Room @Entity changes have corresponding migrations
- [ ] Variant-specific files synced (sandbox/ and production/)
- [ ] Tested with multiple roles (WAITER, CASHIER, MANAGER, ADMIN)
- [ ] No crashes in ADB logcat

## ADB Monitoring (MANDATORY After Every Change)

```bash
# Clear logs and monitor
adb logcat -c && adb logcat -s PaymentViewModel,MenuViewModel | grep -iE "keyword"
```

Full guide: `docs/ADB_MONITORING_GUIDE.md`

## Log Capture Workflow

After implementing code, start log capture before user tests:

```bash
./scripts/capture-logs.sh <feature> start   # Start capturing
./scripts/capture-logs.sh <feature> read    # Read logs (after user tests)
./scripts/capture-logs.sh <feature> stop    # Stop + cleanup
```

Available features: `payment`, `order`, `menu`, `bluetooth`, `socket`, `auth`, `printer`, `sync`, `table`, `kiosk`, `inventory`, `all`

When user says "ya termine de testear" or similar: read logs, analyze errors, stop capture.

## Unit Test Trigger Map

When modifying any of these files, run the corresponding tests:

| Modified file | Tests to run |
|---------------|-------------|
| `payment/data/InitializationManager.kt` (sandbox or production) | `*InitializationManagerTest*` |
| `payment/data/MultiMerchantSDKManager.kt` | `*MultiMerchantSDKManagerTest*` |
| `payment/presentation/PaymentViewModel.kt` (sandbox or production) | `*PaymentViewModelTest*` |
| `payment/domain/model/TpvSettings.kt` | `*AttendanceVerificationTest*`, `*PaymentViewModelTest*` |
| `core/data/network/dto/TpvSettingsDto.kt` | `*AttendanceVerificationTest*` |
| `payment/domain/model/MerchantAccount.kt` | `*MultiMerchantSDKManagerTest*`, `*PaymentViewModelTest*` |
| `payment/domain/PaymentState.kt` | `*PaymentViewModelTest*` |
| `payment/domain/model/PaymentContext.kt` | `*PaymentViewModelTest*` |
| `payment/domain/model/PaymentFlowOrigin.kt` | `*PaymentViewModelTest*` |
| `shift/domain/Shift.kt` | `*PaymentViewModelTest*`, `*ShiftViewModelTest*` |
| `core/domain/TerminalConfig.kt` | `*MultiMerchantSDKManagerTest*`, `*InitializationManagerTest*` |

Quick command for all payment tests:
```bash
./gradlew testSandboxDebugUnitTest --tests "*InitializationManagerTest*" \
  --tests "*MultiMerchantSDKManagerTest*" --tests "*PaymentViewModelTest*" \
  --tests "*AttendanceVerificationTest*"
```

Or run the full suite (~70s): `./gradlew testSandboxDebugUnitTest --rerun-tasks`

## Payment Flow Regression Testing

When touching PaymentViewModel/PaymentScreen, test ALL flows:

1. Fast payment (no order)
2. Quick order payment
3. Table order payment
4. Pay-later order payment
5. Split payment (partial amount)
6. Refund flow

## Room Migration Testing

Before every production release:

```bash
# Install OLD version -> generate data -> install NEW version -> verify no crash
./gradlew installSandboxDebug  # Old version first
# Use app, create orders, process payments
./gradlew installSandboxDebug  # New version (should migrate cleanly)
adb logcat -s "RoomDatabase:*" | grep -i "migration"
```

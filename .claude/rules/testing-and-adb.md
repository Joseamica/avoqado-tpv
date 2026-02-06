# Testing & ADB Policy

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

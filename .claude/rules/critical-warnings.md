# Critical Warnings (NO EXCEPTIONS)

These rules apply to ALL files in the TPV app. Violations cause production bugs.

## PaymentViewModel & PaymentScreen Safety

**EXTREME CAUTION** when modifying `PaymentViewModel.kt` or `PaymentScreen.kt` (sandbox AND production variants).

8 features share the same payment state machine:

| Feature | Key Conditional | Risk if broken |
|---------|----------------|----------------|
| Fast Payment | `orderId == null` | Quick checkout flow |
| Quick Order | `orderId != null && tableId == null` | Order payment flow |
| Table Service | `currentTableId != null` | Table clearing, navigation |
| Pay Later | `wasPayLaterOrder == true` | Pay-later navigation |
| SERIALIZED_INVENTORY | `isSerializedInventoryMode` | Proof-of-sale flow |
| Split Payments | `remainingBalance > 0` | Partial payment flow |
| Refunds | `isRefund == true` | Refund UI/flow |
| Kiosk Mode | `isKioskPayment == true` | Auto-dismiss |

**Before ANY change to PaymentViewModel/PaymentScreen:**

- [ ] Identify all affected features (which conditionals does your change touch?)
- [ ] Test ALL 6+ payment flows, not just the one you're fixing
- [ ] If adding state variables, clear them in `resetPayment()`
- [ ] Verify Success state includes ALL required fields (receipt, cardDetails, etc.)
- [ ] Sync changes between `sandbox/` and `production/` variants

**Golden Rules:**
1. Never assume only one feature uses a code path
2. Clear ALL state in `resetPayment()` — add new fields immediately
3. Watch for state contamination — cached data from one payment can leak to next
4. Payment bugs = lost revenue + merchant distrust

## Blumon: TWO Separate Integrations

Always say "Blumon TPV" or "Blumon E-commerce". Never just "Blumon".

| | **TPV (Android SDK)** | **E-commerce (Web)** |
|---|---|---|
| Runs on | APK on PAX terminals | Backend API calls |
| Environment | APK build variant (sandbox/prod) | `USE_BLUMON_MOCK` env var |
| DB model | `MerchantAccount` + `Terminal` | `EcommerceMerchant` + `CheckoutSession` |

Before working on Blumon payments, read in order:
1. `docs/BLUMON_INTEGRATION_COMPLETE.md` — Full SDK integration, multi-merchant switching (Section 7), EMV flow, PIN dialog
2. `docs/PAYMENT_RECONCILIATION.md` — Multi-merchant architecture, virtual serial numbers, cost structures
3. `avoqado-server/docs/blumon-tpv/BLUMON_MULTI_MERCHANT_ANALYSIS.md` — Backend multi-merchant analysis

Official Blumon SDK docs: `~/Library/Mobile Documents/com~apple~CloudDocs/Avoqado/Blumon/` (production/ and dev/ folders)

## Build Variants: Sandbox vs Production

| Variant | Package ID | Blumon Server | AAR |
|---------|-----------|---------------|-----|
| Sandbox | `com.jaac.avoqado_tpv.sandbox` | `sandbox-tokener.blumonpay.net` | `blumon_sdk-debug.aar` |
| Production | `com.jaac.avoqado_tpv` | `tokener.blumonpay.net` | `blumon_sdk-prod.aar` |

Variant-specific files (`sandbox/` and `production/`): `PaymentViewModel.kt`, `InitializationManager.kt`, `BlumonInitializer.kt`

**Rule**: Changes MUST be synced between sandbox and production (except SDK URLs). `productionDebug` and `productionRelease` use REAL money.

## Room Migration Checklist

**Production crash risk.** Users cannot uninstall — all updates preserve existing DB. Missing migration = 100% crash rate.

When modifying any `@Entity`:
1. Add field to `@Entity` with default value
2. Create `MIGRATION_X_Y` in `AvoqadoDatabase.kt`
3. Add migration to `DatabaseModule.addMigrations()`
4. Increment `@Database(version = Y)`
5. Test by installing old version, generating data, then installing new version

## Authentication

```kotlin
// CORRECT
val authContext = authRepository.getAuthContext()
val venueId = authContext.venueId

// WRONG — req.user is a backend pattern, doesn't exist in Android
val user = request.user
```

## Tenant Isolation

EVERY database query MUST filter by `venueId`. No exceptions.

## Money = BigDecimal, Never Float

```kotlin
val amount = BigDecimal("100.50")  // CORRECT
val amount = 100.5                  // WRONG — precision loss
```

## Performance (1GB RAM Devices)

Always paginate. Never load all records at once — PAX A80 has only 1GB RAM.

```kotlin
val orders = orderRepository.getOrders(limit = 20, cursor = cursor)  // CORRECT
val orders = orderRepository.getAllOrders()  // WRONG — OOM risk
```

## API Endpoint Paths

Base URL already includes `/api/v1/`. Don't add `/v1/` again:

```kotlin
@GET("tpv/modules")       // CORRECT — becomes /api/v1/tpv/modules
@GET("tpv/v1/modules")    // WRONG — becomes /api/v1/tpv/v1/modules
```

## Common Pitfalls

| Problem | Cause | Solution |
|---------|-------|---------|
| App crashes on update | Room @Entity field without migration | ALWAYS create migration |
| First payment takes 30s | SQLite connection leak | Single Storage instance in AvoqadoApp |
| UI freezes during payment | Blocking main thread | `withContext(Dispatchers.IO)` |
| Socket events not received | Not joined to room | Join room before listening |
| 401 "Usuario no encontrado" | Wrong build variant | Use `sandboxDebug` for testing |
| 404 on new endpoint | Double `/v1/` in path | Use `tpv/endpoint` not `tpv/v1/endpoint` |
| Module config stale after logout | `remember {}` not Flow | `collectAsStateWithLifecycle()` on StateFlow |
| Feature button not appearing | Permission not in DEFAULT_PERMISSIONS | Add to `permissions.ts` DEFAULT_PERMISSIONS + INDIVIDUAL_PERMISSIONS_BY_RESOURCE |
| Permission check fails silently | Name mismatch TPV vs Backend | Verify EXACT name in both `checkPermission()` and `hasPermission()` |

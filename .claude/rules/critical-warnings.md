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

## AngelPay: Isolated Payment Path (Nexgo Terminals)

AngelPay is a **completely separate** payment flow from Blumon. Never mix them.

| | **Blumon (PAX)** | **AngelPay (Nexgo)** |
|---|---|---|
| Integration | Embedded SDK (native .so) | App-to-app Intent |
| ViewModel | `PaymentViewModel` (sandbox/production) | `AngelPayPaymentViewModel` (main/) |
| Screen | `PaymentScreen` | `AngelPayPaymentScreen` |
| Route | `NavRoute.Payment` | `NavRoute.AngelPayPayment` |
| Routing | `ENABLE_PAX_SDK=true` | `ENABLE_PAX_SDK=false` |
| Refunds | CancelIcc via SDK | Not available (admin only) |

**Rules:**
- NEVER modify `PaymentViewModel` for AngelPay changes — use `AngelPayPaymentViewModel`
- Payment routing uses `BuildConfig.ENABLE_PAX_SDK`, NOT `Build.MODEL` (hardware detection failed on Nexgo)
- AngelPay doesn't return card details (maskedPan, brand, entryMode) — recorded as UNKNOWN
- QA creds auto-provisioned in `AvoqadoTPVApplication.onCreate()` when `ENABLE_PAX_SDK=false`

Before working on AngelPay, read: `docs/ANGELPAY_INTEGRATION.md`

## Build Variants: Sandbox vs Production vs Nexgo

| Variant | Package ID | Processor | ABI |
|---------|-----------|-----------|-----|
| Sandbox | `com.jaac.avoqado_tpv.sandbox` | Blumon sandbox | armeabi |
| Production | `com.jaac.avoqado_tpv` | Blumon production | armeabi |
| Nexgo | `com.jaac.avoqado_tpv.sandbox` | AngelPay QA | arm64-v8a |
| TutorialEmu | `com.jaac.avoqado_tpv.sandbox` | None | arm64-v8a + x86_64 |

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

## 🔴 El cobro al cliente NUNCA puede ser menor al total registrado

Lo que se le manda al procesador es **SIEMPRE el TOTAL (venta + propina)**. Los dos
procesadores funcionan igual y Avoqado guarda el desglose de su lado:

| Procesador | Qué se manda | Dónde |
|---|---|---|
| **Blumon TPV** | `calculateTotal(amount, tip)` → `SaleIcc` | `PaymentViewModel` (ambas variantes) |
| **AngelPay** | `amountCents = subtotal + propina`; `tipCents` = **desglose** | `AngelPaySdkGateway.buildPaymentRequest` |

⚠️ **`tipCents` de AngelPay NO se suma: se RESTA.** Es cuánto del total es propina, y
AngelPay lo descuenta de `amountCents` para mostrar el importe de la venta. Su recibo lo
dice explícito: `Pago con tarjeta $330.00 = Importe $280.50 + Propina $49.50`.

Mandar el subtotal ahí costó **11 ventas cobradas de menos por $1,225.65** en un
restaurante (Rest MX, 2026-08-09/10): el cliente pagó menos de lo que aceptó y el local
nunca recibió las propinas. **Sólo se ve en comercios tipo restaurante** — los retail
rechazan la propina con `C208` y caen al fallback, que ya mandaba el total, así que el
bug puede vivir meses sin manifestarse.

Al tocar cualquier ruta de cobro, el invariante es uno: **monto enviado al procesador ==
monto registrado en Avoqado**. Tests guardianes: `AngelPaySdkGatewayTest` → "el cobro
NUNCA es menor al total registrado".

## 🔴 Sin red es el estado NORMAL — nada se entrega sin responder qué pasa offline

La PAX cobra en tiendas con WiFi malo: la red se cae a media venta. Antes de dar por terminado
cualquier cambio de esta app, responde las cuatro preguntas de
`../.claude/rules/todo-funciona-sin-red.md` (regla del workspace, carga en toda sesión):

1. **Qué VE el cajero sin red** — offline se dice con todas sus letras, nunca es un error genérico.
2. **Qué se pierde si el proceso muere entre el toque y el POST** — lo que el usuario ya hizo se
   persiste ANTES de tocar la red (`PaymentSyncWorker`, Room), no en el `catch`.
3. **En qué ORDEN se reproduce lo encolado** — lo que CIERRA una etapa (cierre de turno, corte) es
   barrera: no se manda hasta que lo anterior esté confirmado. Al revés se firman números que mienten.
4. **Qué pasa cuando vuelve la red y el servidor ya cambió** — lo tardío no se descarta en silencio ni
   se aplica a lo que esté abierto ahora; viaja con su id.

Casos medidos en Android que aplican igual aquí: un cierre offline que se perdía y un retiro que
inventaba un faltante de $50 por reproducirse después del cierre. **Ninguno lo vieron los tests: sólo
salieron apagando el WiFi de un aparato real** (recetas de adb en la regla del workspace).
Detalle del mecanismo de esta app: `docs/OFFLINE_SYNC_ARCHITECTURE.md` y `docs/ORDERING_OFFLINE.md`.

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

## Timezone: Venue Timezone, Never Device Timezone

**Never use `ZoneId.systemDefault()`**, `LocalDate.now()` (no-arg), `LocalDateTime.now()` (no-arg), or `SimpleDateFormat` without setting timezone. The device timezone may not match the venue's timezone.

Backend stores all timestamps in **UTC**. TPV converts to **venue timezone** for display.

**Where the timezone comes from:**
1. Backend sends `venue.timezone` (IANA string, e.g. `"America/Mexico_City"`) in terminal config response
2. TPV saves it via `SecureStorage.saveVenueTimezone()`
3. `VenueTimeZone.get(secureStorage)` reads it with in-memory cache (fallback: `"America/Mexico_City"`)

**Usage patterns:**

```kotlin
// In ViewModels / Repositories — inject SecureStorage
private val venueZoneId get() = VenueTimeZone.get(secureStorage)
val today = LocalDate.now(venueZoneId)

// In @Composable functions — pass ZoneId as parameter or use fallback
val venueZone = remember { ZoneId.of("America/Mexico_City") }
val currentTime = LocalTime.now(venueZone)

// SimpleDateFormat — ALWAYS set timezone
val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "MX"))
    .apply { timeZone = java.util.TimeZone.getTimeZone(venueZoneId) }
```

**Cache invalidation:** Call `VenueTimeZone.invalidateCache()` when venue changes (REMOTE_ACTIVATE, terminal config fetch, logout).

**Exceptions** (OK to use device/fixed timezone):
- Internal log file timestamps (`FileLogger`)
- Firebase Storage paths (file naming, not user-facing)
- Camera photo file names

## PAX A910S Target Device (UI Priority)

**Only device in production.** All UI must be tested against PAX A910S dimensions.

| Spec | Value |
|------|-------|
| Resolution | 720x1280px |
| Density | 320dpi (xhdpi) |
| DP size | 360x640dp |
| ResponsiveSizes | "medium" (600-700dp height) |

**Rules:**
1. Every screen MUST have `@Preview(widthDp = 360, heightDp = 640)` — include venue status banner
2. No fixed button sizes >80dp — use adaptive sizing via `LocalResponsiveSizes.current`
3. No scroll for primary input UI (PIN pads, keypads) — adapt sizes instead
4. Minimum touch target: 44dp (prefer 48dp+)
5. Test with banner visible — it steals ~56dp from usable height

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
| Wrong time on receipts/reports | `ZoneId.systemDefault()` or no-arg `LocalDate.now()` | Use `VenueTimeZone.get(secureStorage)` or `ZoneId.of("America/Mexico_City")` |

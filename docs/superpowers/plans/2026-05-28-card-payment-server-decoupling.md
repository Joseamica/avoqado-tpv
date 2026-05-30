# Card Payment Server-Decoupling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let card payments (cobro / kiosko-directo) proceed when our own backend `api.avoqado.io` is temporarily unreachable, gated behind a remote, default-OFF kill-switch — without changing authorization (always online to Momentum), without breaking init/heartbeat/sockets, and without losing payment records.

**Architecture:** The card charge is ALWAYS online to Momentum (unchanged). Today a preflight (`PaymentViewModel:2252`) blocks the charge unless our backend heartbeat (`hasServer`) is up — the wrong dependency. We add a backend-controlled flag `requireAvoqadoServerForCardPayment` (default `true` = today's behavior). When set `false` for a venue, the TPV relaxes the preflight to only require device internet (`hasInternet`) **and only for `orderId == null`** (cobro / kiosko-directo). Order/table payments keep their separate order-sync gate (`PaymentViewModel:2431`). Recording falls to the existing offline queue, hardened by enqueue-on-approval + a real idempotency key. A backend alert fires if a charge is never recorded within 24h (orphan). Contactless "INSERTE TARJETA" (code 1A) declines prompt chip insertion instead of dead-ending.

**Tech Stack:** Kotlin / Jetpack Compose / Hilt / Room (TPV) · Express + TypeScript + Prisma (backend) · React + Vite (dashboard) · Blumon PAX SDK (Momentum) · Firebase Crashlytics + BetterStack (observability).

**Deploy order (cross-repo, mandatory):** Backend (Phase A) → Dashboard (Phase C) → wait stable → TPV APK (Phase B, ships dormant) → flip flag for Dona Simona only (Phase D). Backend default-OFF means nothing changes until the flag is flipped.

---

## File Structure

**Backend (`avoqado-server`):**
- Modify: `src/controllers/tpv/terminal.tpv.controller.ts` — add `requireAvoqadoServerForCardPayment` to `TpvSettings` interface + `DEFAULT_TPV_SETTINGS` + response assembly.
- Modify: `src/schemas/dashboard/venueSettings.schema.ts` — accept the flag from the dashboard PATCH.
- Modify: `src/jobs/blumon-webhook-reconciliation.job.ts` — ORPHANED per-event alert (**already done this session** — verify only).
- Verify: fast-payment recording dedups by `referenceNumber` (queue-retry safety).

**TPV (`avoqado-tpv`):**
- Modify: `app/src/main/.../payment/domain/model/TpvSettings.kt` — add flag (default `true`).
- Modify: `app/src/main/.../core/data/network/dto/TpvSettingsDto.kt` — deserialize flag + map to domain (2 mappers).
- Modify: `app/src/main/.../core/data/local/SecureStorage.kt` — persist + read flag (survives backend outage).
- Modify: `app/src/sandbox/.../payment/presentation/PaymentViewModel.kt` + `app/src/production/.../payment/presentation/PaymentViewModel.kt` — relax preflight (flag-gated, `orderId == null`); enqueue-on-approval; contactless→chip fallback.
- Modify: `app/src/main/.../payment/domain/model/QueuedPayment.kt` — add `idempotencyKey`.
- Modify: `app/src/main/.../core/data/local/entity/PendingPaymentEntity.kt` + `AvoqadoDatabase.kt` + `DatabaseModule.kt` — Room column + migration.
- Modify: `app/src/main/.../payment/data/repository/PaymentQueueRepository*.kt` — persist/read idempotencyKey.

**Dashboard (`avoqado-web-dashboard`):**
- Modify: venue TPV settings page — toggle for the flag.

---

## PHASE A — Backend flag (deploys in minutes; default-OFF = zero behavior change)

### Task A1: Add `requireAvoqadoServerForCardPayment` to TpvSettings (backend)

**Files:**
- Modify: `avoqado-server/src/controllers/tpv/terminal.tpv.controller.ts:24-60` (interface), `:65-100` (defaults), `:379-389` (assembly)
- Modify: `avoqado-server/src/schemas/dashboard/venueSettings.schema.ts`

- [ ] **Step 1: Add the field to the `TpvSettings` interface**

In `terminal.tpv.controller.ts`, inside `interface TpvSettings` (after `enableShifts: boolean` at line 36):

```typescript
  // Card payment server-decoupling kill-switch.
  // true (default) = require api.avoqado.io reachable before allowing a card charge (legacy behavior).
  // false = allow the (always-online-to-Momentum) charge even when our backend heartbeat is down;
  //         recording falls to the TPV offline queue. Scoped on-device to orderId == null.
  requireAvoqadoServerForCardPayment: boolean
```

- [ ] **Step 2: Add the safe default**

In `DEFAULT_TPV_SETTINGS` (after `enableShifts: true,` at line 77):

```typescript
  // Card auth requires our backend by default (legacy behavior — no change until flipped per-venue).
  requireAvoqadoServerForCardPayment: true,
```

- [ ] **Step 3: Wire it into the response assembly**

In the `tpvSettings` object built around line 379 (where `enableShifts` is resolved from `venueSettings`), add the resolution from venue settings with the safe fallback:

```typescript
  requireAvoqadoServerForCardPayment:
    venueSettings?.requireAvoqadoServerForCardPayment ??
    DEFAULT_TPV_SETTINGS.requireAvoqadoServerForCardPayment,
```

- [ ] **Step 4: Accept the flag in the dashboard settings schema**

In `venueSettings.schema.ts`, add to the venue-settings update body schema (Zod), alongside the other optional booleans:

```typescript
  requireAvoqadoServerForCardPayment: z.boolean().optional(),
```

- [ ] **Step 5: Typecheck**

Run: `cd avoqado-server && npx tsc --noEmit -p tsconfig.json`
Expected: no errors.

- [ ] **Step 6: Commit**

```bash
git add src/controllers/tpv/terminal.tpv.controller.ts src/schemas/dashboard/venueSettings.schema.ts
git commit -m "feat(tpv): add requireAvoqadoServerForCardPayment flag (default true, no behavior change)"
```

### Task A2: ORPHANED webhook alert (ALREADY IMPLEMENTED this session — verify only)

**Files:**
- Modify: `avoqado-server/src/jobs/blumon-webhook-reconciliation.job.ts:136` (`markOrphaned`)

- [ ] **Step 1: Confirm the enriched per-event alert is present**

`markOrphaned()` must `findMany` the to-be-orphaned rows and `logger.error('🚨 [Blumon recon] ORPHANED webhook ...', {venueId, amount, authorizationCode, lastFour, reference, operationNumber, serialNumber, ageHours})` before the `updateMany`. (Done 2026-05-28.)

- [ ] **Step 2: Wire BetterStack alert (ops task, no code)**

Create a BetterStack alert on source `1720702` (render log stream) matching `raw LIKE '%[Blumon recon] ORPHANED webhook%'` → notify the payments channel. Verify with: query the last 7 days for that message to confirm field shape.

- [ ] **Step 3: Typecheck + commit (if not already committed)**

```bash
cd avoqado-server && npx tsc --noEmit -p tsconfig.json
git add src/jobs/blumon-webhook-reconciliation.job.ts
git commit -m "feat(blumon): per-event ORPHANED webhook alert for manual reconciliation"
```

### Task A3: Verify fast-payment recording dedups by `referenceNumber`

**Files:**
- Read: `avoqado-server/src/services/tpv/payment.tpv.service.ts` (fast payment create path)
- Read: `avoqado-server/prisma/schema.prisma` (Payment model uniques)

- [ ] **Step 1: Confirm dedup**

Verify the fast-payment recording path dedups on BOTH `(venueId, idempotencyKey)` AND a legacy `referenceNumber` check (per `tpv.schema.ts:222-231`). Queue retries from old records carry `referenceNumber` (and, after Task B3, `idempotencyKey`). Confirm a repeat POST with the same `referenceNumber` returns the existing payment (HTTP 200/409), not a duplicate.

- [ ] **Step 2: If the `referenceNumber` legacy check is missing, add it**

Before creating a Payment, query `prisma.payment.findFirst({ where: { venueId, referenceNumber } })` and return it if found. (Only if not already present.)

- [ ] **Step 3: Commit (only if code changed)**

```bash
git commit -am "fix(tpv): ensure fast-payment recording dedups by referenceNumber for queue retries"
```

### Task A4: Mark late/orphan-reconciled payments as "recovered via Blumon" (reporting clarity)

**Files:**
- Read: `avoqado-server/src/services/tpv/blumon-webhook.service.ts` (`reconcileWebhooksForPayment`, `attemptPaymentMatch`)
- Read: `avoqado-server/prisma/schema.prisma` (Payment.processorData / a nullable flag)

- [ ] **Step 1: Tag payments recorded AFTER their webhook arrived**

When `reconcileWebhooksForPayment` (or the cron `reconcileBlumonEvent`) matches a Payment that was recorded late (i.e. a webhook had been PENDING and a queued/offline payment then synced), write a marker into the Payment's `processorData` JSON, e.g. `{ reconciledVia: 'BLUMON_WEBHOOK', reconciledLate: true, reconciledAt: <ISO> }`. This requires no schema change if `processorData` is a JSON column; confirm and use it. Reports/dashboards can then distinguish "recovered" payments.

- [ ] **Step 2: Provide an ops action for ORPHANED → manual reconcile**

Document (or add an endpoint) so that when ops manually reconciles an ORPHANED event against Blumon's portal, the created/linked Payment carries `{ reconciledVia: 'MANUAL_BLUMON' }`. (May reuse the existing settlement-incident flow.)

- [ ] **Step 3: Typecheck + commit**

```bash
cd avoqado-server && npx tsc --noEmit -p tsconfig.json
git commit -am "feat(blumon): tag late/orphan-reconciled payments as recovered-via-Blumon for reporting"
```

---

## PHASE B — TPV (ships DORMANT; flag default `true` = no change until flipped)

### Task B1: Add the flag to TPV settings (DTO → domain → SecureStorage)

**Files:**
- Modify: `app/src/main/.../payment/domain/model/TpvSettings.kt:68`
- Modify: `app/src/main/.../core/data/network/dto/TpvSettingsDto.kt:58-59`, `:157`, `:202`
- Modify: `app/src/main/.../core/data/local/SecureStorage.kt:1169`, `:1237`

- [ ] **Step 1: Add field to the domain model**

In `TpvSettings.kt`, after `val enableShifts: Boolean = true,` (line 68):

```kotlin
    // Card payment server-decoupling kill-switch (default true = legacy: require backend before charge).
    // When false, the (always-online-to-Momentum) charge is allowed even if our backend heartbeat is down.
    // Cached in SecureStorage so it survives the very backend outage it guards against.
    val requireAvoqadoServerForCardPayment: Boolean = true,
```

- [ ] **Step 2: Deserialize in the DTO**

In `TpvSettingsDto.kt`, near `enableShifts` (line 58-59), add:

```kotlin
    @SerializedName("requireAvoqadoServerForCardPayment")
    val requireAvoqadoServerForCardPayment: Boolean?,
```

- [ ] **Step 3: Map DTO → domain in BOTH mappers**

In `TpvSettingsDto.kt` at the two `toDomain`/mapping sites (lines ~157 and ~202 where `enableShifts` is mapped), add — using the safe default where the network mapper coalesces nulls:

```kotlin
    requireAvoqadoServerForCardPayment = requireAvoqadoServerForCardPayment ?: true,   // line ~157 (null-coalescing mapper)
```
```kotlin
    requireAvoqadoServerForCardPayment = requireAvoqadoServerForCardPayment,            // line ~202 (domain-to-domain mapper)
```

- [ ] **Step 4: Persist + read in SecureStorage**

In `SecureStorage.kt`: add a key constant `private const val KEY_REQUIRE_SERVER_FOR_CARD = "require_server_for_card_payment"`. In the save block (near line 1169) add:

```kotlin
            putBoolean(KEY_REQUIRE_SERVER_FOR_CARD, settings.requireAvoqadoServerForCardPayment)
```
In the read block (near line 1237) add:

```kotlin
            requireAvoqadoServerForCardPayment = encryptedPrefs.getBoolean(KEY_REQUIRE_SERVER_FOR_CARD, true),
```

- [ ] **Step 5: Compile**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 23) && ./gradlew compileSandboxDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/jaac/avoqado_tpv/features/payment/domain/model/TpvSettings.kt app/src/main/java/com/jaac/avoqado_tpv/core/data/network/dto/TpvSettingsDto.kt app/src/main/java/com/jaac/avoqado_tpv/core/data/local/SecureStorage.kt
git commit -m "feat(payment): plumb requireAvoqadoServerForCardPayment flag (DTO→domain→storage)"
```

### Task B2: Relax the preflight (flag-gated, orderId == null) — sandbox AND production

**Files:**
- Modify: `app/src/sandbox/.../payment/presentation/PaymentViewModel.kt:~2259`
- Modify: `app/src/production/.../payment/presentation/PaymentViewModel.kt:2252`

- [ ] **Step 1: Replace the preflight condition (production)**

At `PaymentViewModel.kt:2252` (production), the current gate is:

```kotlin
        if (!connectionStateManager.isFullyConnected()) {
```

Replace with a helper that honors the flag and scope. Add this private helper near the other connectivity helpers:

```kotlin
    /**
     * Card preflight: when the venue has requireAvoqadoServerForCardPayment=false AND this is a
     * cobro/kiosko-directo (orderId == null), only require device internet — the charge is always
     * online to Momentum and recording falls to the offline queue. Otherwise keep legacy behavior
     * (require our backend reachable). Order/table payments are unaffected (separate gate at PASO -1).
     */
    private fun cardPreflightBlocked(): Boolean {
        val settings = tpvSettingsRepository.getCurrentSettings()
        val isCobro = getOrderIdForFlow() == null && !shouldSkipLocalValidation()
        return if (!settings.requireAvoqadoServerForCardPayment && isCobro) {
            !connectionStateManager.hasInternet()   // relaxed: only need device internet
        } else {
            !connectionStateManager.isFullyConnected()  // legacy: need internet + our backend
        }
    }
```

Then change the gate line to:

```kotlin
        if (cardPreflightBlocked()) {
```

- [ ] **Step 2: Add `hasInternet()` to ConnectionStateManager if absent**

In `core/util/ConnectionStateManager.kt`, if no public `hasInternet()` exists, add:

```kotlin
    fun hasInternet(): Boolean = _connectionState.value.hasInternet
```

- [ ] **Step 3: Mirror the exact change in the sandbox variant**

Apply Steps 1 identical at `app/src/sandbox/.../PaymentViewModel.kt:~2259`. Verify the helper + gate match byte-for-byte (except any sandbox-only SDK URLs — none here).

- [ ] **Step 4: Compile both variants**

Run: `./gradlew compileSandboxDebugKotlin compileProductionDebugKotlin`
Expected: BUILD SUCCESSFUL for both.

- [ ] **Step 5: Commit**

```bash
git add app/src/sandbox/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentViewModel.kt app/src/production/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentViewModel.kt app/src/main/java/com/jaac/avoqado_tpv/core/util/ConnectionStateManager.kt
git commit -m "feat(payment): flag-gated preflight — allow cobro when backend down (orderId==null only)"
```

### Task B2b: Shift-check offline fallback (shifts-enabled venues)

**Why:** The card preflight isn't the only backend gate before Blumon. For venues with shifts ENABLED, `PaymentViewModel` (~production:2298, sandbox equivalent) calls `shiftRepository.getCurrentShift(venueId)`, which hits the backend (`ApiService.getCurrentShift`) and returns `Result.Error(NetworkError)` on failure → `.getOrNull()` → `null` → the flow BLOCKS ("No hay turno activo"). So relaxing the preflight alone does NOT make shifts-enabled venues work offline. A local shift cache already exists (`CachedShiftDao.getCachedShift(venueId)`, used by WelcomeScreen for offline display) but is NOT wired into the payment shift check.

**Files:**
- Modify: `app/src/main/.../features/shift/data/repository/ShiftRepository.kt` (`getCurrentShift`)
- Modify: `app/src/sandbox` + `app/src/production` `PaymentViewModel.kt` (shift validation block)
- Use: `app/src/main/.../core/data/local/dao/CachedShiftDao.kt`

- [x] **Step 1: Cache the open shift on every successful fetch**

In `ShiftRepository.getCurrentShift`, on the `Result.Success(shift)` branch, write the shift to `CachedShiftDao.cacheShift(CachedShiftEntity.fromDomain(shift, venueId))` (per the documented pattern in `CachedShiftDao.kt`). Confirm this isn't already happening elsewhere to avoid double-writes.

- [x] **Step 2: Offline fallback in the payment shift check (flag-gated)**

In the payment shift-validation block (both variants), when `isShiftSystemEnabled()` is true AND the live `getCurrentShift` returned null/Error AND `!settings.requireAvoqadoServerForCardPayment` (relaxed mode) AND `getOrderIdForFlow() == null` (cobro), read `cachedShiftDao.getCachedShift(venueId)`. If a cached shift with status OPEN exists, use it (`currentShiftId = cached.id`) and proceed; otherwise block as today (show "Abrir Turno"). Do NOT change behavior when the flag is on its default (`true`).

- [x] **Step 3: Test**

Unit test: shifts enabled + `getCurrentShift` returns NetworkError + flag false + cached OPEN shift present → payment proceeds with `currentShiftId` = cached id. With flag true (default) → blocks as today. Run `./gradlew testSandboxDebugUnitTest --tests "*PaymentViewModelTest*" --tests "*ShiftViewModelTest*"`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/jaac/avoqado_tpv/features/shift/data/repository/ShiftRepository.kt app/src/sandbox app/src/production
git commit -m "feat(payment): shift-check offline fallback to cached open shift (flag-gated, cobro only)"
```

### Task B3: Option A — enqueue-on-approval + real idempotencyKey (Room migration)

**Files:**
- Modify: `app/src/main/.../payment/domain/model/QueuedPayment.kt:28-92`
- Modify: `app/src/main/.../core/data/local/entity/PendingPaymentEntity.kt`
- Modify: `app/src/main/.../core/data/local/AvoqadoDatabase.kt` (version + migration)
- Modify: `app/src/main/.../core/di/DatabaseModule.kt` (register migration)
- Modify: `app/src/main/.../payment/data/repository/PaymentQueueRepositoryImpl.kt`
- Modify: `app/src/production` + `app/src/sandbox` `PaymentViewModel.kt` (`handlePaymentSuccess` enqueue timing)

- [ ] **Step 1: Add `idempotencyKey` to QueuedPayment + carry it in `toPaymentContext()`**

In `QueuedPayment.kt`, add to the data class (after `referenceNumber`, line 33):

```kotlin
    // Idempotency key (device paymentAttemptId UUID). Distinct from referenceNumber.
    // Restores PRIMARY backend dedup (venueId, idempotencyKey) for queue retries.
    val idempotencyKey: String? = null,
```
In `toPaymentContext()` (line 82), pass it through to `PaymentContext.FastPayment(... idempotencyKey = idempotencyKey)`. (Confirm `PaymentContext.FastPayment` has an `idempotencyKey` param; it is set on the online path via `buildFastPaymentContext`. If the param name differs, match it.)

- [ ] **Step 2: Add the column to PendingPaymentEntity**

In `PendingPaymentEntity.kt`, add a nullable column with a default (so existing rows migrate cleanly):

```kotlin
    @ColumnInfo(name = "idempotency_key", defaultValue = "NULL")
    val idempotencyKey: String? = null,
```

- [ ] **Step 3: Write the Room migration (MANDATORY — missing = 100% crash)**

In `AvoqadoDatabase.kt`: bump `@Database(version = N+1)`, and add:

```kotlin
        val MIGRATION_<N>_<N+1> = object : Migration(<N>, <N+1>) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_payments ADD COLUMN idempotency_key TEXT DEFAULT NULL")
            }
        }
```
(Use the actual current version for `<N>`; confirm the table name matches `PendingPaymentEntity`'s `@Entity(tableName = ...)`.)

- [ ] **Step 4: Register the migration**

In `DatabaseModule.kt`, add `MIGRATION_<N>_<N+1>` to the `.addMigrations(...)` list.

- [ ] **Step 5: Map idempotencyKey in PaymentQueueRepositoryImpl**

In `enqueue(...)` set `idempotencyKey = queuedPayment.idempotencyKey` on the entity; in the entity→domain mapper, read it back.

- [ ] **Step 6: Enqueue at approval time in handlePaymentSuccess**

In `handlePaymentSuccess(...)` (production + sandbox), BEFORE calling `recordPaymentUseCase`, persist the payment to the queue immediately (status PENDING) so an app-kill mid-record cannot lose it; on `recordPaymentUseCase` success, mark/delete the queued row (so the sync worker won't re-send). Concretely: build the `QueuedPayment` (with `idempotencyKey = sessionSnapshot.paymentAttemptId` and `referenceNumber` from `saleData`), `paymentQueueRepository.enqueue(it)` first, then attempt the online record, then on success call `paymentQueueRepository.markSynced(referenceNumber)` (add this method if absent — it sets SyncStatus.SUCCESS / deletes). The existing `onFailure` enqueue becomes a no-op duplicate (the `OnConflictStrategy.IGNORE` on `reference_number` handles it).

- [ ] **Step 7: Room migration test (old → new, no crash)**

Run: `./gradlew installSandboxDebug` on the PREVIOUS version, create a queued payment, then `./gradlew installSandboxDebug` with this version.
Run: `adb logcat -s RoomDatabase:* | grep -i migration`
Expected: clean migration, no `IllegalStateException`.

- [ ] **Step 8: Compile both variants + commit**

```bash
./gradlew compileSandboxDebugKotlin compileProductionDebugKotlin
git add app/src/main/java/com/jaac/avoqado_tpv/features/payment/domain/model/QueuedPayment.kt app/src/main/java/com/jaac/avoqado_tpv/core/data/local/entity/PendingPaymentEntity.kt app/src/main/java/com/jaac/avoqado_tpv/core/data/local/AvoqadoDatabase.kt app/src/main/java/com/jaac/avoqado_tpv/core/di/DatabaseModule.kt app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/repository/PaymentQueueRepositoryImpl.kt app/src/sandbox app/src/production
git commit -m "feat(payment): enqueue-on-approval + idempotencyKey in offline queue (Room migration)"
```

### Task B4: Rule #3 — contactless "INSERTE TARJETA" (code 1A) → prompt chip insertion

**Files:**
- Modify: `app/src/production` + `app/src/sandbox` `PaymentViewModel.kt` (SaleCtls MomentumFailure handler — the contactless online-auth failure branch)
- Modify: `PaymentScreen.kt` if a new "insert card" prompt state is needed

- [x] **Step 1: Read the current contactless decline branch**

Read the `performOnlineAuthorization(... isContactless = true)` failure handling and the `[CONTACTLESS ONLINE PHASE 2] Online authorization FAILED` path. Identify where `specificErrorDescription` / `codeResponse` is available.

- [x] **Step 2: Detect the switch-interface decline and branch to chip**

In the contactless failure branch, before showing the generic hard decline, detect code `1A` / description containing `INSERTE TARJETA` (case-insensitive). When matched, instead of `PaymentState.Error(hard decline)`, set a recoverable state that prompts the cashier/customer to **insert the chip** and re-enters the chip flow (reuse the existing chip entry, e.g. `startPaymentWithResolvedInputs(... entryMode = CHIP)`), preserving amount/tip/context. Do NOT auto-retry contactless.

```kotlin
val switchToChip = (specificErrorDescription?.contains("INSERTE TARJETA", ignoreCase = true) == true) ||
    failure.toString().contains("\"codeResponse\":\"1A\"", ignoreCase = true)
if (switchToChip) {
    _state.value = PaymentState.Error(
        message = "Inserta la tarjeta (chip) para continuar el cobro.",
        context = createPaymentContext(),
        canRetry = true        // re-run as CHIP, same amount/context
    )
    return@... // do not fall through to hard-decline
}
```

- [x] **Step 3: Mirror in sandbox variant + compile**

Apply identically to the sandbox `PaymentViewModel.kt`. Run `./gradlew compileSandboxDebugKotlin compileProductionDebugKotlin`.

- [ ] **Step 4: Commit**

```bash
git add app/src/sandbox app/src/production
git commit -m "feat(payment): contactless 1A 'INSERTE TARJETA' -> prompt chip insertion (no dead-end)"
```

### Task B5: Tests & device verification

- [ ] **Step 1: Unit tests (trigger map)**

Run: `./gradlew testSandboxDebugUnitTest --tests "*PaymentViewModelTest*" --tests "*InitializationManagerTest*" --tests "*MultiMerchantSDKManagerTest*"`
Then full suite: `./gradlew testSandboxDebugUnitTest`
Expected: 0 failures. Add a `PaymentViewModelTest` case: with `requireAvoqadoServerForCardPayment=false` + `hasServer=false` + `hasInternet=true` + `orderId==null` → preflight does NOT block; with `orderId!=null` → behavior unchanged; with flag `true` → legacy block.

- [ ] **Step 2: Lint**

Run: `./gradlew lint --continue`
Expected: pass.

- [ ] **Step 3: Six-flow regression on device (PAX A910S)**

Test ALL: (1) Fast payment, (2) Quick order, (3) Table order, (4) Pay-later, (5) Split, (6) Refund — with flag default (true) → all behave exactly as before.

- [ ] **Step 4: The decisive device test (backend down, internet up)**

On PAX A910S with the flag flipped to `false` for the test venue: block `api.avoqado.io` (e.g. DNS blackhole / firewall) while leaving Momentum reachable. Run a Fast Payment.
Expected: init runs, SaleIcc approves at Momentum, success screen shows, payment is enqueued; on backend restore, `PaymentSyncWorker` syncs and the Blumon webhook matches (Crashlytics + BetterStack confirm). Verify NO duplicate payment in the dashboard.

---

## PHASE C — Dashboard toggle (`avoqado-web-dashboard`)

### Task C1: Per-venue toggle for `requireAvoqadoServerForCardPayment`

**Files:**
- Modify: venue TPV/payment settings page (the component that already renders `enableShifts` and other TpvSettings toggles).

- [ ] **Step 1: Add the toggle**

Add a switch labeled "Permitir cobrar aunque el servidor Avoqado no responda" (help text: "El cobro siempre se autoriza en línea con el procesador; solo difiere el registro en Avoqado. Solo aplica a cobro/kiosko, no a órdenes."). Bind to `requireAvoqadoServerForCardPayment` (inverted in the UI: switch ON = allow offline = flag `false`). Persist via the existing venue-settings PATCH (now accepts the field per Task A4).

- [ ] **Step 2: Build + commit**

```bash
cd avoqado-web-dashboard && npm run build
git commit -am "feat(settings): toggle for card payment server-decoupling (per venue)"
```

---

## PHASE D — Rollout (controlled, reversible)

- [ ] Deploy backend (Phase A) + dashboard (Phase C). Default-OFF → zero change fleet-wide. Confirm `GET tpv/terminal/config` returns `requireAvoqadoServerForCardPayment: true` for all.
- [ ] Build + sign production APK (Phase B). Ships DORMANT (flag true everywhere → identical behavior). Follow `release-and-git.md` (apksigner v2, iCloud, version bump = MINOR — new capability behind flag).
- [ ] After APK reaches Dona Simona's terminal: in dashboard, set the toggle ON for **Dona Simona only**.
- [ ] Watch 24–48h: Crashlytics (`PaymentViewModel`, `InitializationManager`), BetterStack (`Recording fast payment` resumes for serial 2840744168; zero `ORPHANED`), and the terminal's success rate.
- [ ] If healthy → enable per additional venue gradually. If ANY anomaly → flip the toggle OFF (instant, no APK) — reverts to legacy behavior.

---

## Success Criteria

1. With the flag default (`true`), every payment flow behaves byte-identically to today (verified by the 6-flow regression + unit tests).
2. With the flag `false` for a venue + `api.avoqado.io` unreachable + internet up: a Fast Payment authorizes online at Momentum, shows success, and records via the offline queue with no duplicate.
3. Init, heartbeat, sockets, merchant-switch, and the order-sync gate are unchanged (no new failures in Crashlytics).
4. A charge that never records within 24h emits a `🚨 ORPHANED` alert with venue/amount/auth/lastFour for manual reconciliation.
5. Contactless code `1A` prompts chip insertion instead of a hard decline.
6. The toggle flips behavior remotely in seconds with no APK.

## Rollback

- **Instant (no deploy):** flip the dashboard toggle OFF for the venue → legacy behavior immediately on next settings fetch.
- **Backend:** revert Phase A commits (default was already safe).
- **TPV:** the APK is safe to leave installed (dormant when flag true); no APK rollback needed unless the dormant code itself regressed (caught by the 6-flow regression before release).

## Out of Scope (separate future work)

- Offline order/table payments (the `PaymentViewModel:2431` order-sync gate stays — orders must reach the backend for inventory/linkage).
- Webhook-creates-payment backstop (Option B) — rejected as HIGH risk (incomplete data, venue ambiguity, double-record on missing reference). Manual reconciliation via the ORPHANED alert is the chosen backstop.
- ISO 8583 time-out reversal after a timed-out SaleIcc (separate hardening, requires Blumon/Edgardo alignment on CancelIcc).

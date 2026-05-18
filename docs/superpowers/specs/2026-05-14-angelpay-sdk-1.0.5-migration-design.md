# AngelPay SDK 1.0.5 Upgrade + Multi-Merchant Runtime Switching — Design Spec

**Date:** 2026-05-14
**Owner:** Jose Antonio Amieva
**Status:** Draft v2.5 — post `/plan-eng-review`, 6 decisions integrated (D2–D7), ready for writing-plans
**Scope:** Cross-repo (avoqado-server, avoqado-web-dashboard, avoqado-tpv)
**Estimated effort (MVP scope):** 12–16 dev-days (3 repos, single dev)

## ⚠️ Revision note (v2 — 2026-05-14, post-audit)

Earlier draft framed this as a "migration from Intent app-to-app to embedded SDK". **That was wrong.** Verified current state:

- AngelPay SDK 1.0.4 AAR is already in `app/libs/`
- `AngelPaySDK.initialize()`, `authenticateSimple()`, `selectMerchant()`, `createPaymentIntent()`, `getTransactionHistory()`, `cancelTransaction()`, `refundTransaction()`, `sendTicketEmail()`, `getTicketUrl()`, `printTicket()` are already wired in `AngelPaySdkGateway.kt` and `AngelPaySdkPostOperationsAdapter.kt`
- `nexgo` and `nexgoProd` flavors exist
- `AuthenticateSimpleResult.MerchantSelectionRequired` is already handled
- **What's actually missing**: runtime `getUserMerchants()` + `switchMerchant()` wiring, backend-sourced credentials (today they're `BuildConfig.ANGELPAY_QA_*` hardcoded), and the cross-repo plumbing for managing AngelPay merchants in the Avoqado data model

This is reframed as **"upgrade 1.0.4 → 1.0.5 + finish runtime multimerchant"**, not migration from scratch.

v2 audit findings addressed:
- Reuse `MerchantAccount.externalMerchantId` (already required + unique per provider) instead of adding a new `angelpayMerchantId` field
- `credentialsEncrypted` stays required on `MerchantAccount` at schema level; for AngelPay rows the service produces an `encryptCredentials({})` blob that has the standard `{ encrypted, iv }` shape (auth lives in `AngelPayUserAccount`, not here). On `AngelPayUserAccount.pinEncrypted` itself, the field is **nullable** — `null` is the canonical "no PIN provisioned yet" signal (PENDING_PIN status), not a placeholder JSON
- Device-provider validation added in 4 spots, not just `createMerchantAccount`: assign/unassign, terminal update, config endpoint, dashboard
- TPV `MerchantAccountDto` + domain model need extending for AngelPay mapping (don't exist yet)
- Real credentials moved out of spec to vault references
- Offline queue corrected: only enqueue **backend recording** when `PaymentResult.approved == true`. Never enqueue an un-approved card transaction
- Scope tightened to MVP: refund/cancel kept only if existing `AngelPaySdkPostOperationsAdapter` passes real QA; MSI advanced, digital tickets, card reader, hotel, print fallback, sophisticated dashboard status all deferred

v2.4 post-eng-review integration (this revision, v2.5):
- **D2 race condition** — added `AngelPayPaymentState.Switching(targetMerchantId)` and mutual-exclusion `Mutex` between switch and charge inside `AngelPayMerchantRepository`. `switchActiveMerchant()` rejects if a charge is in progress; `startPayment()` waits up to 8s for an in-flight switch, then errors out if it doesn't complete. Multi-tap on the merchant selector cancels the previous in-flight switch. See §18.1.
- **D3 lifecycle states** — added `status` enum to `AngelPayUserAccount`: `PENDING_PIN | ACTIVE | PIN_ROTATION_REQUIRED | SUSPENDED | DELETED`. Dashboard surfaces it; backend rejects `createMerchantAccount(provider=ANGELPAY)` if account is not `ACTIVE`; TPV only operates while `ACTIVE`. See §18.2.
- **D4 dual-source transition** — TPV v2 reads `angelpayAuth` from config response first; if null/absent, falls back to `BuildConfig.ANGELPAY_QA_*` with Crashlytics warning "using deprecated hardcoded creds". Backend keeps `angelpayAuth` populated for terminals on legacy config for 30 days post-deploy. v3 deletes the BuildConfig fields entirely. See §18.3.
- **D5 mismatch policy** — `validateAgainstConfig()` now uses **intersection logic**: merchants in BOTH SDK and Avoqado config remain operable; mismatches generate a yellow warning banner + Crashlytics + `/tpv/angelpay/report-validation` event but do NOT block payments to valid merchants. Only an **empty intersection** blocks payments. See §18.4.
- **D6 cache freshness** — `AngelPayMerchantRepository.fetchAndCacheMerchants()` refreshes every 15 min while app is foreground AND immediately whenever the operator opens the merchant selector / switcher sheet. See §18.5.
- **D7 explicit test inventory** — §10.1 expanded with 46+ enumerated unit tests (was "~30+"). See §10.1 rewrite and §18.6 traceability table.

v2.3 post-approval polish (revision v2.4):
- §6.4 Hilt safety: replaced direct `@Inject lateinit` with **Pattern A (`Provider<AngelPaySdkGateway>` + `.get()` inside the SUPPORTED_PROCESSOR guard)** or escalation to **Pattern B (flavor source-set binding with no-op stub)**. PAX startup verification added to §17.6 regression gate (mandatory `adb logcat` capture on PAX A910S)
- §6.9 added **post-auth configuration consistency check** — `AngelPayMerchantRepository.validateAgainstConfig(config)` runs immediately after auth, on cold start, on socket reconnect, on manual refresh. If SDK merchant IDs ≠ Avoqado config merchant IDs (either direction), enters `CONFIG_MISMATCH` state, **blocks payments**, surfaces operator banner with the diff, reports to backend. No silent fallback to "first available merchant"
- §7.3 wording softened: "AngelPay backend down" mitigation now says "cash, or another device/processor in the same venue if available"; explicitly clarifies that per single-processor-per-device rule the same Nexgo terminal cannot fall back to Blumon — operator must walk to a PAX terminal if one exists

v2.2 audit findings addressed (revision v2.3):
- §6.4 typo fixed — uses `angelPaySdkGateway.ensureInitialized(applicationContext, env)` (existing method on `AngelPaySdkGateway.kt:20`), not the removed `AngelPaySdkClient.initialize(...)`
- §17.5 packaging guardrail rewritten to **asymmetric rule**: PAX builds remain strict (must not package AngelPay AAR); Nexgo builds tolerate documented stub Blumon AAR for compile compatibility (verified `app/build.gradle.kts:379`) but MUST NOT execute PAX code at runtime and MUST exclude PAX native `.so` / Neptune runtime via `packagingOptions`
- §6.13 aligned with §2.2 non-goals — print fallback + email-ticket adapter remain compile/smoke-tested only; **no operator UX ships** in MVP (existing `_isSendingReceipt` / `_sendReceiptMessage` flows stay internal and unsurfaced)
- §6.7 reframed as **incremental change to existing `AngelPayPaymentViewModel`** (which already has `AngelPayPaymentState`, `RecordPaymentUseCase`, `_currentMerchant`, `_merchants`, `_state`). No `PaymentUiState`, `OrderPaymentInput`, or `AngelPayPaymentRepository` new types — code block marked as pseudocode illustrating intent
- §6.6 active merchant snippet uses real `MutableStateFlow<Int?>` for `_activeAngelPayMerchantId`, updated on every successful `completeInitialSelection`/`switchActiveMerchant`, reverted on failure, cleared on logout. Removed unused `sdkGateway.getSessionInfo()?.merchantToken` (vendor unconfirmed semantics)

v2.1 audit findings addressed (revision v2.2):
- New §17 **Blumon/PAX Untouchable Guardrails** — release-blocking rules covering frozen files, frozen Blumon semantics, AngelPay code gating, additive-only schema/DTO, APK packaging CI gate, mandatory regression suite + PAX A910S smoke before merge, code review checklist
- §6.8 **Merchant selector hook map** clarified: `MerchantSelectionContent` untouched (shared composable); Blumon path (`updateSelectedMerchant` + later `multiMerchantSDKManager.switchMerchant` inside `startPayment`) frozen; **only `AngelPayPaymentViewModel.selectMerchant(...)` changes** to call `AngelPayMerchantRepository.completeInitialSelection` or `switchActiveMerchant`. Card payment now blocks if SDK active merchant disagrees with Avoqado-selected merchant
- §6.9 **active merchant mapping** corrected: `AngelPayMerchantRepository` owns `activeAngelPayMerchantId: Int`, updated after every successful select/switch; **do NOT infer from `SDK.getSessionInfo()?.affiliation`** (vendor has not confirmed equality)
- §10.3 **QA scenarios** split into two groups: **Shipped UX (10 scenarios — must pass)** and **Adapter smoke-only (5 scenarios — not shipped UX)**. MSI advanced and digital ticket email are now adapter-only and explicitly NOT shipped in MVP
- Wrapper naming standardized to **`AngelPaySdkGateway`** (extending the existing one); the placeholder `AngelPaySdkClient` removed throughout

---

## 1. Context

### 1.1. Current state (verified 2026-05-14)

Avoqado supports **two payment processors** today:

- **Blumon TPV (PAX terminals)**: production, fully integrated. Multi-merchant works via `MultiMerchantSDKManager.switchMerchant()` which re-initializes the Blumon SDK against a new `posId`. Each `MerchantAccount` holds its own `blumonSerialNumber` + `blumonPosId`.
- **AngelPay (Nexgo terminals)**: embedded SDK 1.0.4 already integrated. Lives in `nexgo` and `nexgoProd` Gradle flavors. The following primitives are **already wired** in production code:
  - `AngelPaySDK.initialize(context, env)` in `AvoqadoTPVApplication.kt:194`
  - `AngelPaySdkGateway.kt`: `isInitialized()`, `isAuthenticated()`, `ensureInitialized()`, `authenticateSimple()`, `selectMerchant()` (with `MerchantSelectionRequired` branch handled at line 39), `createPaymentIntent()`
  - `AngelPaySdkPostOperationsAdapter.kt`: `getTransactionHistory()`, `cancelTransaction()`, `refundTransaction()`, `sendTicketEmail()`, `getTicketUrl()`, `printTicket()`
- Credentials are currently sourced from BuildConfig fields `ANGELPAY_QA_EMAIL`, `ANGELPAY_QA_PASSWORD`, `ANGELPAY_QA_AFFILIATION`, `ANGELPAY_QA_COMMERCE_TOKEN` (`AvoqadoTPVApplication.kt:153-160`) — hardcoded at compile time. **This must be removed.**

### 1.1b. What's actually missing (the real scope of this work)

- ❌ Runtime merchant switching (`AngelPaySDK.getUserMerchants()` + `AngelPaySDK.switchMerchant()`) — no production code references these methods
- ❌ Backend-sourced credentials (`AngelPayUserAccount` model does not exist)
- ❌ Per-merchant mapping in `MerchantAccount` (TPV `MerchantAccountDto` + domain model lack AngelPay-specific fields)
- ❌ Device-processor coupling enforcement (admin can assign ANGELPAY merchant to PAX-only venue)
- ❌ SDK upgrade 1.0.4 → 1.0.5 (new AAR + verify breaking changes; release notes claim backwards-compatible per vendor)
- ❌ Dashboard UI for managing `AngelPayUserAccount` + AngelPay-specific merchant fields
- ❌ Test on real Nexgo with 2-merchant test account (`(QA user — vault)`) — currently only single-merchant tested
- ⚠️ Refund/cancel — adapter exists but real QA round needed; if it doesn't pass, keep refund admin-only for MVP

### 1.2. The trigger

AngelPay released **SDK 1.0.5** (May 2026) — a brand new embedded AAR SDK that replaces the Intent app-to-app model. The new SDK exposes native multi-merchant primitives:

- `authenticateSimple(email, pin)` returns either `Success` (single merchant) or `MerchantSelectionRequired(merchants, temporaryToken)` (multi)
- `selectMerchant(merchantId, temporaryToken)` finalizes initial selection
- `switchMerchant(merchantId)` swaps active merchant **without re-login** (internally fetches new JWT, updates session)
- `getUserMerchants()` returns `List<MerchantSummary>` with `isActive` flag
- `getMerchantInfo()` exposes capabilities (`type: Venta | Venta con propina | Check In`, `hasRefund`, `hasCancel`, `hasReauthorization`, `msiPlans`)
- `createPaymentIntent(context, request)` → `Result<Intent>` for charging with full EMV result data
- `cancelTransaction(...)`, `refundTransaction(...)`, `getTransactionHistory(...)`, `printTicket(...)`, `sendTicketEmail(...)`, `getTicketUrl(...)`, `getCardReader(...)` (NFC/Magnetic standalone)

### 1.3. AngelPay business model (from May 13, 2026 call w/ Diego)

- **Onboarding is manual** by AngelPay: Avoqado provides client info → AngelPay creates user (email+PIN) + merchants
- **One AngelPay user → N merchants**: the user is the auth principal; merchants are operating affiliations attached to it
- **Distributor portal** (in 2 weeks) will give Avoqado visibility into client revenue/share
- **No auto-registration API** short term (regulatory: blacklists, SAT, FINCEN, PEP checks)
- **N62 terminal** is available (contrary to earlier info); André handles commercial
- Test creds provided for QA: 1 user with 2 demo merchants — stored locally (see References §16); not committed to repo

### 1.4. Why this matters

Three drivers:

1. **Multi-merchant parity with Blumon**. Clients like Madre Café (3 venues), stadium operators, and multi-affiliation businesses need to route per-transaction. Blumon supports this today; AngelPay didn't.
2. **Replace Intent-based AngelPay before production deploy**. The Intent integration was a placeholder. Going to production with the new SDK avoids needing two rounds of Nexgo signing/PAXSTORE-style distribution.
3. **Feature parity**. New SDK enables refunds/cancel/MSI/digital tickets — features Blumon already has via its own SDK.

---

## 2. Goals & Non-Goals

### 2.1. MVP Goals (in-scope for this delivery)

| # | Goal |
|---|------|
| G1 | Upgrade AngelPay SDK AAR from 1.0.4 → 1.0.5 (verify no breaking changes, swap AAR file) |
| G2 | Wire runtime multi-merchant switch: `getUserMerchants()` + `switchMerchant()` exposed to operator UI |
| G3 | Add device-processor coupling enforcement at **4 enforcement points**: createMerchantAccount, assignMerchantToTerminal, terminal update (brand change), and `/tpv/terminals/:serial/config` response filtering. Dashboard surfaces the rule visually |
| G4 | Add `AngelPayUserAccount` model at venue level with encrypted PIN; replace BuildConfig hardcoded credentials with backend-sourced via `/tpv/terminals/:serial/config` |
| G5 | Extend TPV `MerchantAccountDto` and domain `MerchantAccount` with AngelPay fields (display affiliation, display name); reuse existing `externalMerchantId` for the AngelPay merchant ID (no new field) |
| G6 | Network-resilient auth/switch flow: retry policy, state machine, persistent UI banner |
| G7 | Dashboard CRUD: `AngelPayUserAccount` (create/rotate/delete) + AngelPay-specific MerchantAccount fields |
| G8 | Backend recording: when SDK approves payment, record to Avoqado with `merchantAccountId` resolved from `externalMerchantId` |
| G9 | Test suite: happy path, multi-merchant selection, runtime switch, offline retry, token expiry — on Nexgo N86 with provided 2-merchant test account |
| G10 | Verify existing refund/cancel adapter works end-to-end with QA real data; if yes, ship it. If no, keep admin-only and defer |

### 2.2. Non-goals (deferred to Phase 2 or later)

- ❌ MSI advanced flow (selector UI for installment plans) — SDK exposes capability via `getMerchantInfo().msiPlans`; we read it but don't expose plan picker in MVP
- ❌ Digital ticket UX (email + URL post-payment screen) — adapter exists but no operator UX yet
- ❌ Print receipt via SDK fallback — Avoqado's existing printer system stays primary; SDK printing untouched
- ❌ Unified Card Reader (NFC/Magnetic standalone) — no loyalty/membership flow defined
- ❌ Hotel check-in — no hotel venues in Avoqado today
- ❌ Sophisticated dashboard status panel (per-terminal AngelPay state realtime view) — minimum observability only
- ❌ Migrating Blumon flow — stays as-is
- ❌ Auto-onboarding AngelPay (no public API available)
- ❌ Multi-user AngelPay per venue — one user per venue is sufficient
- ❌ Cross-processor reconciliation reporting (already covered by existing reconciliation system)

---

## 3. Architecture — 3-layer Model

### 3.1. Layer 1: Device-Processor Coupling (Lightweight)

**Problem**: Today `MerchantAccount` has no constraint preventing an admin from assigning an `ANGELPAY` merchant to a venue with only PAX terminals. Runtime crash risk on TPV.

**Solution** (no new enums, **4 enforcement points** for defense in depth):

1. **Normalize `Terminal.brand`** (existing nullable String field):
   - Accept values: `"PAX" | "NEXGO" | "INGENICO" | "VERIFONE"`
   - Validate at terminal create/update via zod schema
   - One-time migration that uppercases existing values (`pax` → `PAX`, `nexgo` → `NEXGO`); log warnings for unknown values so we can clean manually
   - Frontend Terminal form: dropdown with these 4 values

2. **Backend validation — 4 enforcement points** (this was the gap the audit caught):

   2a. **`createMerchantAccount`**: when provider is ANGELPAY/BLUMON, query count of terminals in venue with compatible brand. Reject if zero.

   2b. **`assignMerchantToTerminal` / `unassignMerchantFromTerminal`** (existing endpoints — exact path verify against `terminal.assignedMerchantIds` write path): when adding a `merchantAccountId` to a terminal's `assignedMerchantIds`, verify `terminal.brand` is in `PROVIDER_DEVICE_COMPATIBILITY[merchant.provider.code]`. Reject incompatible. Same for org-level fallback merchants.

   2c. **Terminal update (brand change)**: when a terminal's `brand` is changed via PATCH `/api/v1/superadmin/terminals/:id`, query all `assignedMerchantIds` plus any inherited (venue-level + org-level) ANGELPAY/BLUMON merchants. If new brand is incompatible with any assigned/inherited merchant, return a warning (NOT a hard reject — admin may be replacing the device) listing affected merchants. UI then asks "Unassign incompatible merchants?" before committing.

   2d. **`/tpv/terminals/:serialNumber/config`**: server-side filter the merchant list before responding. Only return merchants where `merchant.provider.code` is compatible with `terminal.brand`. This is the **runtime gate** even if validation at 2a-c was skipped or data is legacy.

3. **Dashboard UX** (`Superadmin/MerchantAccounts.tsx` and `Superadmin/Terminals/[id]/Assignments.tsx`):
   - When admin selects provider = ANGELPAY: show info banner "AngelPay solo opera en terminales Nexgo. Este venue tiene **N terminales Nexgo activas**" (blue if N≥1, red+disable submit if N=0)
   - In Terminal assignment view: filter merchant dropdown by terminal brand; show only compatible providers
   - When admin tries to change a terminal's brand: show diff "Estos merchants quedarán sin asignar: [list]" with confirm

4. **TPV runtime auto-filter** (defense in depth — last line):
   - Constants: `BuildConfig.SUPPORTED_PROCESSOR` = `"BLUMON"` (sandbox/production flavors) or `"ANGELPAY"` (nexgo/nexgoProd flavors)
   - In `MerchantRepository.fetchMerchantsForTerminal()`: filter result by `merchant.providerCode == BuildConfig.SUPPORTED_PROCESSOR`
   - If backend mis-returns an incompatible merchant, TPV silently ignores + logs warning to Crashlytics

**Trade-offs**:
- ✅ No new schema enums, no large migration
- ✅ Easy to extend if a future processor supports multiple devices (just update constant)
- ⚠️ Rule lives in 3 places (backend, dashboard, TPV) — acceptable since it changes rarely

### 3.2. Layer 2: AngelPay User Account Model

**Problem**: AngelPay auth model differs from Blumon. AngelPay has **one user per venue, with N merchants attached**. Need a place to store user creds separate from per-merchant config.

**Solution**: New Prisma model `AngelPayUserAccount`.

```prisma
model AngelPayUserAccount {
  id                String   @id @default(cuid())
  venueId           String   @unique
  venue             Venue    @relation(fields: [venueId], references: [id], onDelete: Restrict)
  //                                                              ^^^^^^^^^^^^^^^^^^^^^^^
  // Restrict (not Cascade) — accidentally deleting a venue must NOT silently
  // drop the AngelPay credential trail. Operator must explicitly transition the
  // account to DELETED status (§18.2) before deleting the venue.

  email             String
  pinEncrypted      Json?    // nullable — present only when status is past PENDING_PIN. Shape: { encrypted: hex, iv: hex } produced by encryptCredentials() in src/services/superadmin/merchantAccount.service.ts
  environment       String   @default("QA")  // "QA" | "PROD"

  externalUserId    Int?     // AngelPay-side user ID (from getSessionInfo().userId)
  lastValidatedAt   DateTime?
  lastValidationErr String?

  createdAt         DateTime @default(now())
  updatedAt         DateTime @updatedAt
  createdBy         String?  // staff user CUID (denormalized — no FK to preserve audit if user deleted)

  // No @@index([venueId]) — Prisma auto-creates a unique btree on @unique columns; an explicit
  // @@index on the same column doubles write amplification with zero query benefit.
  @@index([status])
}
```

**MerchantAccount changes — reuse existing fields, no new columns required for the ID:**

The schema already has `externalMerchantId: String` (required, `@@unique([providerId, externalMerchantId])`) and `credentialsEncrypted: Json` (required). We reuse these:

- **`externalMerchantId`** = `String(angelpayMerchantId)` (from AngelPay's `MerchantOption.id` cast to string). TPV parses it back to `Int` before calling `SDK.switchMerchant(int)`. The schema-level unique constraint guarantees no two AngelPay merchants under the same provider share the same external ID.
- **`credentialsEncrypted`** = `{}` (empty JSON placeholder) for AngelPay rows. Auth lives at `AngelPayUserAccount` level; per-merchant rows don't carry credentials. Existing service supports this path — `createMerchantAccount` already has a "Blumon pending" branch that doesn't require credentials; we replicate that branch for AngelPay.

**Display fields for UX** — small additive change, no migration risk:

```prisma
model MerchantAccount {
  // ...existing fields stay unchanged...

  // Optional display cache (filled at creation, refreshed if AngelPay portal exposes a lookup API)
  angelpayAffiliation  String?  // mirrors MerchantOption.afiliationNumber (helps admin disambiguate in dashboard)
  angelpayMerchantName String?  // mirrors MerchantOption.name (display in TPV merchant selector)
}
```

**Why not a typed `angelpayMerchantId: Int?` column** (revised from v1):
- `externalMerchantId` already exists, is required, indexed, and unique per provider
- Adding a typed column duplicates state and forces sync logic
- TPV does `externalMerchantId.toIntOrNull() ?: error("AngelPay merchant ID malformed")` at boundary — typed-safe at use point
- Backend service validates `externalMerchantId` is a numeric string when `provider.code == 'ANGELPAY'`

**Service-level changes** (`merchantAccount.service.ts`):
- Provider-aware `externalMerchantId` validation: ANGELPAY → numeric string regex; BLUMON → existing rules
- AngelPay branch in `createMerchantAccount`: stores `credentialsEncrypted = encryptCredentials({})` on the `MerchantAccount` row (auth lives on `AngelPayUserAccount`, not the merchant). For `AngelPayUserAccount.pinEncrypted` itself: write `null` when no PIN yet (status=PENDING_PIN); write `encryptCredentials(pin)` once PIN arrives. **Never write `{}` to `pinEncrypted`** — that would trip `decryptCredentials()` which throws on missing `.encrypted` key and surface a misleading "Invalid encrypted data format" error

**Encryption**: reuses existing `encryptString()` from `src/services/superadmin/merchantAccount.service.ts` (AES-256-CBC with random IV, key derived from `MERCHANT_ACCOUNT_ENCRYPTION_KEY` env var).

**Backend service**:
- `AngelPayUserAccountService.create(venueId, email, pin, environment)` — encrypts PIN, persists
- `AngelPayUserAccountService.getForTerminal(serialNumber)` — joins via terminal → venue → angelpayUserAccount
- `AngelPayUserAccountService.markValidated(id)` — updates `lastValidatedAt`
- `AngelPayUserAccountService.recordError(id, message)` — updates `lastValidationErr`
- `AngelPayUserAccountService.rotate(id, newPin)` — encrypts new PIN, clears `lastValidationErr`

**Why not just `Venue.angelpayEmail` flat fields**: rotation requires separate audit trail (when PIN changed, by whom, validation status). Account model gets us this cleanly.

### 3.3. Layer 3: Network Resilience

**Problem**: TPV runs in restaurants with intermittent WiFi. SDK auth/switch calls hit AngelPay's backend. We must never freeze the operator at a network call.

**Mapping of 5 critical moments**:

| Moment | SDK call | Network? | Risk | Mitigation |
|---|---|---|---|---|
| Cold start | `initialize(context, env)` | No | None | Run in `Application.onCreate` |
| First auth | `authenticateSimple(email, pin)` | Yes | Can't start cobrar | **Lazy**: trigger on first payment, not on splash. Banner "AngelPay: ⚠️ requiere autenticación, conecta WiFi" if fails. |
| Initial merchant pick | `selectMerchant(id, tempToken)` | Yes | Same | Retry 3x with exponential backoff (0.5s, 1s, 2s). If still fails: surface to operator with manual retry button. |
| Merchant switch (mid-shift) | `switchMerchant(id)` | Yes | Operator stuck on switch | Cached merchant list from `getUserMerchants()` post-auth → switch is optimistic in UI; actual SDK call has 5s timeout + retry; if fails, revert active merchant to previous. |
| Payment EMV submit | `createPaymentIntent` + EMV | Yes (during submit) | Payment fails | SDK returns `PaymentResult.approved = false`. **Do NOT enqueue the card transaction** — the gateway never approved it. Surface error to operator; suggest retry on better network or alternative payment method. Only the **post-approval backend recording** uses the existing offline queue (see 7.5 below). |
| JWT expires (auto) | SDK internal refresh | Yes | Next payment auth-fails | Detect auth-category errors (`AppErrorCatalog` code starts with `C2`-ish for auth — TBD with vendor); auto trigger `authenticateSimple` once + retry payment. |
| Socket reconnect | N/A (Avoqado socket, not AngelPay) | Yes (Avoqado) | Stale terminal config | On socket reconnect: refetch `/tpv/terminals/:serial/config` → if active merchant was removed, fallback to first compatible merchant. |

**State machine for AngelPay auth state (in `AngelPayAuthState` flow)**:

```
UNAUTHENTICATED ──authenticateSimple()──→ SELECTING_MERCHANT (if multi)
                                       └→ AUTHENTICATED (if single)

SELECTING_MERCHANT ──selectMerchant()──→ AUTHENTICATED
                  └─(network fail)────→ AUTH_ERROR

AUTHENTICATED ──switchMerchant()──→ SWITCHING ─(ok)→ AUTHENTICATED (new merchant)
                                  └─(fail)→ AUTHENTICATED (previous merchant) + toast

AUTHENTICATED ──token expires────→ REAUTH_NEEDED ──(auto)→ authenticateSimple()
                                                         └→ AUTHENTICATED

ANY ──logout()──→ UNAUTHENTICATED (manual only, never auto)
```

**UI surface**: persistent compact indicator at top of TPV (next to existing venue status banner) showing:
- ✅ "AngelPay: Madre Café Rooftop" (green chip)
- ⏳ "Cambiando a Madre Café..." (yellow chip)
- ⚠️ "AngelPay: reconectando..." (yellow chip with spin)
- ❌ "AngelPay: requiere autenticación" (red chip + tap to manual retry)

**Caching strategy**:
- `MerchantSummary[]` cached in Room (table `angelpay_user_merchants_cache`) after `getUserMerchants()` succeeds
- Active merchant ID cached in `SecureStorage.getActiveAngelpayMerchantId()`
- Cache invalidated on logout, manual refresh, or socket-driven config update

**Heartbeat integration**: existing heartbeat to backend reports `angelpayAuthState: "AUTHENTICATED" | "AUTH_ERROR" | ...` so dashboard can show per-terminal auth status (useful for support).

---

## 4. Backend Changes (avoqado-server)

### 4.1. Schema changes (`prisma/schema.prisma`)

```prisma
model AngelPayUserAccount {
  id                String                 @id @default(cuid())
  venueId           String                 @unique
  venue             Venue                  @relation(fields: [venueId], references: [id], onDelete: Restrict)
  //                                                                              ^^^^^^^^^^^^^^^^^^^^^^^
  // Restrict (not Cascade) — accidentally deleting a venue must NOT silently drop the
  // AngelPay credential trail or break the externalUserId mapping. Operator must explicitly
  // transition the account to DELETED status (§18.2) first.

  email             String
  pinEncrypted      Json?                  // nullable. null = no PIN provisioned yet (status=PENDING_PIN). When present, shape: { encrypted: hex, iv: hex } produced by encryptCredentials() in src/services/superadmin/merchantAccount.service.ts
  environment       String                 @default("QA")  // "QA" | "PROD"

  status            AngelPayAccountStatus  @default(PENDING_PIN)
  statusChangedAt   DateTime?
  statusChangedBy   String?                // staff user CUID (denormalized — no FK to preserve audit if user is deleted)
  statusReason      String?

  externalUserId    Int?                   // AngelPay-side user ID (from getSessionInfo().userId after first auth)
  lastValidatedAt   DateTime?
  lastValidationErr String?

  createdAt         DateTime               @default(now())
  updatedAt         DateTime               @updatedAt
  createdBy         String?                // staff user CUID (denormalized)

  // No @@index([venueId]) — redundant with the @unique constraint above (Postgres auto-creates a unique btree)
  @@index([status])
}

model Venue {
  // ...existing fields...
  angelpayUserAccount AngelPayUserAccount?
}

model MerchantAccount {
  // ...existing fields unchanged — externalMerchantId + credentialsEncrypted reused...

  // Additive only — optional display cache for AngelPay merchants
  angelpayAffiliation  String?
  angelpayMerchantName String?
}
```

**`Terminal.brand` validation:** no schema-level enum (keep as `String?`), but enforce via zod validator and a one-time normalization migration that uppercases existing values.

### 4.2. New seed entry (`prisma/seed.ts`)

```ts
await prisma.paymentProvider.upsert({
  where: { code: 'ANGELPAY' },
  create: {
    code: 'ANGELPAY',
    name: 'Angel Pay',
    type: 'PAYMENT_PROCESSOR',
    countryCode: ['MX'],
    configSchema: {
      type: 'object',
      // externalMerchantId carries the AngelPay merchant ID (numeric string from MerchantOption.id) — already required on MerchantAccount
      required: [],
      properties: {
        angelpayAffiliation: { type: 'string', description: 'Display affiliation number (from MerchantOption.afiliationNumber)' },
        angelpayMerchantName: { type: 'string', description: 'Display merchant name (from MerchantOption.name)' },
      },
    },
  },
  update: {
    name: 'Angel Pay',
    countryCode: ['MX'],
  },
})
```

### 4.3. New service: `AngelPayUserAccountService`

`src/services/superadmin/angelpayUserAccount.service.ts`:

```ts
import { encryptString, decryptString } from './merchantAccount.service'

export class AngelPayUserAccountService {
  async create(input: { venueId: string; email: string; pin: string; environment: 'QA' | 'PROD'; createdBy?: string }) {
    if (!/^\d{6}$/.test(input.pin)) throw new ValidationError('PIN must be 6 numeric digits')
    if (!/^[^@]+@[^@]+\.[^@]+$/.test(input.email)) throw new ValidationError('Invalid email format')

    const existing = await prisma.angelPayUserAccount.findUnique({ where: { venueId: input.venueId } })
    if (existing) throw new ConflictError('Venue already has an AngelPay user account')

    const pinEncrypted = encryptString(input.pin)
    const { pin: _pin, ...rest } = input
    return prisma.angelPayUserAccount.create({
      data: { ...rest, pinEncrypted },
    })
  }

  async getForTerminal(serialNumber: string) {
    const terminal = await prisma.terminal.findUnique({ where: { serialNumber }, include: { venue: { include: { angelpayUserAccount: true } } } })
    return terminal?.venue.angelpayUserAccount ?? null
  }

  async rotate(id: string, newPin: string, rotatedBy: string) {
    if (!/^\d{6}$/.test(newPin)) throw new ValidationError('PIN must be 6 numeric digits')
    return prisma.angelPayUserAccount.update({
      where: { id },
      data: { pinEncrypted: encryptString(newPin), lastValidationErr: null, updatedAt: new Date() },
    })
  }

  async markValidated(id: string, externalUserId: number) {
    return prisma.angelPayUserAccount.update({
      where: { id },
      data: { lastValidatedAt: new Date(), externalUserId, lastValidationErr: null },
    })
  }

  async recordError(id: string, message: string) {
    return prisma.angelPayUserAccount.update({
      where: { id },
      data: { lastValidationErr: message },
    })
  }
}
```

### 4.4. Device-compatibility validation in 4 places

Centralize the rule in one helper, call it from 4 enforcement points.

`src/lib/providerDeviceCompatibility.ts`:

```ts
export const PROVIDER_DEVICE_COMPATIBILITY: Record<string, string[]> = {
  BLUMON: ['PAX'],
  ANGELPAY: ['NEXGO'],
  // future: MENTA: ['PAX', 'NEXGO'],
}

export async function assertVenueHasCompatibleTerminal(
  venueId: string,
  providerCode: string,
  tx: PrismaTx = prisma,
) {
  const compatible = PROVIDER_DEVICE_COMPATIBILITY[providerCode]
  if (!compatible?.length) return  // unknown provider — permissive
  const count = await tx.terminal.count({
    where: { venueId, brand: { in: compatible }, status: 'ACTIVE' },
  })
  if (count === 0) {
    throw new IncompatibleDeviceError(
      `Provider ${providerCode} requires at least one ${compatible.join(' or ')} terminal in this venue`,
    )
  }
}

export function isProviderCompatibleWithBrand(providerCode: string, brand: string | null): boolean {
  const compatible = PROVIDER_DEVICE_COMPATIBILITY[providerCode]
  if (!compatible?.length || !brand) return true
  return compatible.includes(brand)
}
```

**Point 1 — `merchantAccount.service.ts → createMerchantAccount`**: call `assertVenueHasCompatibleTerminal(input.venueId, provider.code)`.

**Point 2 — terminal merchant assignment** (existing endpoint, find exact path via `assignedMerchantIds` write):
```ts
// In assignMerchantToTerminal:
const terminal = await prisma.terminal.findUnique({ where: { id: terminalId } })
const merchant = await prisma.merchantAccount.findUnique({ where: { id: merchantId }, include: { provider: true } })
if (!isProviderCompatibleWithBrand(merchant.provider.code, terminal.brand)) {
  throw new IncompatibleDeviceError(`Cannot assign ${merchant.provider.code} merchant to ${terminal.brand} terminal`)
}
```
Apply the same check on any path that mutates `terminal.assignedMerchantIds` (including bulk updates and Venue-level inheritance setup).

**Point 3 — terminal update (brand change)**: on `PATCH /api/v1/superadmin/terminals/:id` when `brand` changes:
```ts
const oldTerminal = await prisma.terminal.findUnique({ where: { id }, include: { venue: true } })
const assignedMerchants = await prisma.merchantAccount.findMany({
  where: { id: { in: oldTerminal.assignedMerchantIds } },
  include: { provider: true },
})
const incompatible = assignedMerchants.filter(m => !isProviderCompatibleWithBrand(m.provider.code, newBrand))
if (incompatible.length) {
  // Return a soft-warning response — frontend prompts admin to confirm
  return { warning: true, incompatibleMerchants: incompatible.map(m => ({ id: m.id, name: m.name, code: m.provider.code })) }
}
```
If admin confirms, unassign incompatible merchants in a transaction with the brand change.

**Point 4 — `/tpv/terminals/:serialNumber/config` filtering**: runtime gate.
```ts
// In terminal.tpv.controller.ts:
const merchants = await resolveMerchantsForTerminal(terminal)  // existing function
const filtered = merchants.filter(m => isProviderCompatibleWithBrand(m.provider.code, terminal.brand))
return { ..., merchants: filtered.map(toDto) }
```

### 4.5. Modified endpoint: `/tpv/terminals/:serialNumber/config`

`src/controllers/tpv/terminal.tpv.controller.ts`:

- Filter `merchantAccounts` returned to only those compatible with `terminal.brand` (defense in depth)
- If `terminal.brand === 'NEXGO'` AND any merchant is `ANGELPAY`: include `angelpayUserAccount` (with decrypted PIN passed over TLS to TPV) in the response payload
- New response shape (addition):

```ts
{
  terminal: { ... },
  venue: { ... },
  merchants: MerchantAccountDto[],  // filtered by compatible brand
  angelpayAuth: {                    // only when terminal is NEXGO and venue has ANGELPAY merchants
    email: string,
    pin: string,                     // decrypted server-side, sent over TLS
    environment: 'QA' | 'PROD',
    accountId: string,               // for reporting validation status back
  } | null,
  // ...
}
```

**Security note**: PIN travels over TLS in the config response. Acceptable because (a) endpoint requires `X-Terminal-Token` auth, (b) PIN is low-value (AngelPay user auth, not card data), (c) same pattern Blumon uses today for `credentialsEncrypted` blob. The PIN is never logged on TPV (Crashlytics breadcrumbs filter it explicitly).

**Alternative (more secure)**: TPV decrypts client-side using a key derived from BuildConfig + terminal serial. Adds complexity; revisit if security review demands.

**Compromised credentials cleanup**: any AngelPay QA credentials previously embedded in `BuildConfig.ANGELPAY_QA_*` are considered exposed (committed to repo history). As part of Phase 0:
1. Request AngelPay to rotate the QA user PIN
2. Delete the BuildConfig fields from `app/build.gradle.kts:221` (or equivalent line numbers)
3. Confirm no test data inside spec or commit messages contains real PINs / tokens

### 4.5b. AngelPay PIN handling rules (TPV side — strict)

Once the PIN arrives in the TPV via `/tpv/terminals/:serial/config`, these rules are MANDATORY:

| Rule | Where enforced |
|---|---|
| PIN is held **only in memory** inside `AngelPayAuthRepository` until consumed by `AngelPaySDK.authenticateSimple(...)` | `AngelPayAuthRepository.kt` — keep PIN in a `private val pin: AtomicReference<String?>` cleared after auth succeeds |
| PIN MUST NOT be written to Room, plain SharedPreferences, EncryptedSharedPreferences cache, file logs, or analytics events | Code review + grep CI rule `git grep -nE "pin\s*=\s*"` excludes `pin` from any persistence helper |
| `TerminalConfigRepository` cache **must NOT persist `angelpayAuth` to disk** — only the rest of the config object is cached to Room/SecureStorage. On app restart, TPV refetches `/tpv/terminals/:serial/config` to obtain a fresh PIN | Implementation note in `TerminalConfigRepository.cache()` that strips `angelpayAuth` before persisting |
| OkHttp request/response logging interceptor MUST redact the `angelpayAuth.pin` field and any `password`/`pin` field name globally | Extend existing `RedactingLoggingInterceptor` (or create one) with regex `("pin"\s*:\s*)"[^"]*"` → `$1"***"` |
| Crashlytics: no breadcrumb, custom key, or log message may contain the PIN | Add `Crashlytics` setter wrapper that asserts on debug builds if a key/value contains digit-only 6-char string |
| `WorkManager` jobs that retry network calls MUST NOT capture the PIN in serialized work data | Pass `accountId` instead and re-resolve PIN at runtime |
| If the app is forced offline and PIN is needed (cold start, no cached SDK session), TPV shows the auth-error banner — it does NOT prompt the operator to type the PIN, ever (operator never sees or knows it) | `AngelPayAuthBanner.kt` states: `❌ "AngelPay no autenticado. Restablecer conexión."` |
| When `AngelPayUserAccount` is deleted server-side, TPV clears any in-memory PIN copy on next config refresh | `AngelPayAuthRepository.onConfigUpdated()` |

These rules are repeated in the `.claude/rules/critical-warnings.md` once implementation lands.

### 4.6. New endpoints

| Method | Path | Purpose | Auth |
|---|---|---|---|
| POST | `/api/v1/superadmin/venues/:venueId/angelpay-account` | Create AngelPayUserAccount | Superadmin |
| PATCH | `/api/v1/superadmin/angelpay-accounts/:id` | Rotate PIN, change env | Superadmin |
| GET | `/api/v1/superadmin/venues/:venueId/angelpay-account` | Read (PIN masked) | Superadmin |
| DELETE | `/api/v1/superadmin/angelpay-accounts/:id` | Delete — does NOT cascade-delete MerchantAccount rows. Sets `isActive=false` on any ANGELPAY MerchantAccount belonging to this venue so they don't appear in TPV until reconfigured. `externalMerchantId` is preserved for audit trail. | Superadmin |
| POST | `/api/v1/tpv/angelpay/report-validation` | TPV reports auth success/failure back to backend (updates `lastValidatedAt` / `lastValidationErr`) | Terminal token |
| POST | `/api/v1/tpv/angelpay/report-merchant-switch` | TPV reports merchant switch event (audit trail) | Terminal token |

### 4.7. Migrations

- `2026XXXXX_add_angelpay_user_account.sql` — adds `AngelPayUserAccount` table + `Venue.angelpayUserAccount` FK
- `2026XXXXX_add_angelpay_display_fields.sql` — adds optional `MerchantAccount.angelpayAffiliation` + `angelpayMerchantName` (display cache only; the AngelPay merchant ID itself is stored in the existing `externalMerchantId` column)
- `2026XXXXX_normalize_terminal_brand.sql` — `UPDATE Terminal SET brand = UPPER(brand) WHERE brand IS NOT NULL`; followed by app-level validation
- Seed update for `PaymentProvider.ANGELPAY`

---

## 5. Dashboard Changes (avoqado-web-dashboard)

### 5.1. New page section: AngelPay User Account

`src/pages/Superadmin/Venues/[venueId]/AngelPayAccount.tsx`:

- If no account exists: empty state with "Create AngelPay Account" CTA
- If exists: card showing `email`, masked PIN (`••••••`), `environment`, `lastValidatedAt`, `lastValidationErr`
- Actions: "Rotate PIN", "Switch environment", "Delete account" (with confirmation that this affects all AngelPay merchants in the venue)

### 5.2. Modified page: `Superadmin/MerchantAccounts.tsx`

Changes to MerchantAccount creation/edit form:

1. **Provider dropdown** — when `ANGELPAY` selected:
   - Info banner: "AngelPay solo opera en terminales Nexgo. Este venue tiene **N terminales Nexgo activas**" (blue if N≥1, red if N=0)
   - If N=0: disable submit + CTA "Register a Nexgo terminal first"
   - Show prerequisite: "This venue must have an AngelPay user account configured. [Configure now →]" if missing

2. **AngelPay-specific fields** (shown only when provider = ANGELPAY):
   - `externalMerchantId` (text input, required, **numeric-only validator**) — repurposed existing field. Help text: "AngelPay merchant ID (integer, e.g. `42`). Look up in AngelPay portal or via technical contact."
   - `angelpayAffiliation` (text input, required) — for operator display
   - `angelpayMerchantName` (text input, optional) — defaults to MerchantAccount.displayName

3. **Blumon-specific fields** stay as-is (no breaking changes)

### 5.3. Modified page: Terminal create/edit

`src/pages/Superadmin/Terminals.tsx`:
- Change `brand` from free-text input to dropdown: `PAX | NEXGO | INGENICO | VERIFONE`
- Backfill warning if existing terminals have non-normalized brand (informational only)

### 5.4. New page: Terminal AngelPay Status (read-only)

`src/pages/Superadmin/Terminals/[id]/Status.tsx`:
- Shows live `angelpayAuthState` from heartbeat: `AUTHENTICATED | SELECTING_MERCHANT | AUTH_ERROR | UNAUTHENTICATED`
- Shows current active AngelPay merchant
- Action: "Force re-authentication" (sends remote command to TPV)

### 5.5. Reused infrastructure

- Existing `useSuperadminApi` hooks
- Existing form validation patterns
- Existing toast notifications
- Existing permission gating (`PERMISSION_SUPERADMIN_FULL`)

---

## 6. TPV Changes (avoqado-tpv)

### 6.1. Gradle / SDK upgrade

**Swap AAR** — current `app/libs/angelpaySDK-v1.0.4-fat-release.aar` → `angelpaySDK-v1.0.5-fat-release.aar`. Update references in `app/build.gradle.kts:396-398`. Per vendor release notes, 1.0.5 is backwards-compatible with 1.0.4 wiring — verify by recompiling and running existing tests.

**Remove hardcoded QA creds** (`app/build.gradle.kts:221`):
- Delete `buildConfigField("String", "ANGELPAY_QA_EMAIL", ...)`, `ANGELPAY_QA_PASSWORD`, `ANGELPAY_QA_AFFILIATION`, `ANGELPAY_QA_COMMERCE_TOKEN` from `nexgo` and `nexgoProd` flavors
- Add per-flavor only the env switch: `buildConfigField("String", "ANGELPAY_ENV", "\"QA\"")` (nexgo) / `"\"PROD\""` (nexgoProd)
- Add `buildConfigField("String", "SUPPORTED_PROCESSOR", "\"ANGELPAY\"")` to nexgo flavors and `"\"BLUMON\""` to sandbox/production flavors

**Sensitive value handling** — any real credentials previously embedded in BuildConfig must be rotated by AngelPay (consider previous values compromised). The new flow sources all credentials from the backend config response.

### 6.2. Files to refactor (NOT delete — the SDK wiring exists)

Keep and extend:
- `AngelPaySdkGateway.kt` — already wraps `AngelPaySDK`. Refactor to read credentials from `TerminalConfigRepository.getCachedConfig().angelpayAuth` instead of `BuildConfig.ANGELPAY_QA_*`. Add `getUserMerchants()` and `switchMerchant()` methods (currently absent).
- `AngelPaySdkPostOperationsAdapter.kt` — already wraps history/cancel/refund/print/ticket. Audit code paths and add Crashlytics breadcrumbs.
- Existing `AngelPayPaymentViewModel` — incremental refactor: replace static creds path, add merchant switch handling.
- `AvoqadoTPVApplication.kt:153-160` — delete the `secureStorage.saveAngelPayCredentials(...)` auto-provisioning block; SDK initialization stays (`AngelPaySDK.initialize`), authentication is lazy on first payment via `AngelPaySdkGateway`.

Delete only when fully replaced:
- `AngelPayCredentials.kt` (the BuildConfig-derived holder) — delete after refactor lands
- `SecureStorage.getAngelPayCredentials()` / `.saveAngelPayCredentials()` — delete after refactor lands

### 6.3. New files

```
app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/processor/angelpay/
├── AngelPayAuthRepository.kt         # Wraps SdkGateway + state machine + retry policy
├── AngelPayMerchantRepository.kt     # getUserMerchants + switchMerchant + Room cache
├── AngelPayAuthState.kt              # Sealed class (UNAUTHENTICATED, AUTHENTICATING, ...)
└── (extend existing AngelPaySdkGateway.kt with getUserMerchants/switchMerchant)

app/src/main/java/com/jaac/avoqado_tpv/core/data/network/dto/
└── (additive edits to TerminalConfigDto.kt — extend the existing MerchantAccountDto + TerminalConfigDto inside this file; add AngelPayAuthDto. See 6.4)

app/src/main/java/com/jaac/avoqado_tpv/features/payment/domain/model/
└── (additive edits to MerchantAccount.kt — add externalMerchantId, isActive, angelpayAffiliation, angelpayMerchantName + requireAngelpayMerchantId() helper. See 6.4)

app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/
├── AngelPayMerchantSelectorViewModel.kt   # Initial multi-selection after MerchantSelectionRequired
├── AngelPayMerchantSelectorScreen.kt
├── AngelPayMerchantSwitcherSheet.kt       # Runtime switcher (bottom sheet from top bar)
└── AngelPayAuthBanner.kt                  # Persistent UI chip showing auth state
```

### 6.4. TPV DTO + domain extension (the missing mapping)

`MerchantAccountDto` already exists inside `core/data/network/dto/TerminalConfigDto.kt` (it shares the file with `TerminalConfigDto`). The current shape uses `displayName: String` + `providerCode: String? = "BLUMON"` + does NOT include `externalMerchantId` or `isActive`. Domain `MerchantAccount` (`features/payment/domain/model/MerchantAccount.kt`) has `merchantAccountId` + `processorType` but **no `externalMerchantId`**.

The work is purely **additive on the existing files** (do not create new DTO/domain files):

```kotlin
// core/data/network/dto/TerminalConfigDto.kt — additive changes inside the existing file

data class MerchantAccountDto(
    // ...existing fields kept (displayName, providerCode?: String? = "BLUMON", blumon* fields, etc.)...

    // NEW — additive
    @SerializedName("externalMerchantId")
    val externalMerchantId: String? = null,           // numeric string for AngelPay; Blumon serial for Blumon
    @SerializedName("isActive")
    val isActive: Boolean = true,                     // used by TPV to filter inactive merchants
    @SerializedName("angelpayAffiliation")
    val angelpayAffiliation: String? = null,
    @SerializedName("angelpayMerchantName")
    val angelpayMerchantName: String? = null,
)

data class TerminalConfigDto(
    // ...existing fields kept...
    @SerializedName("angelpayAuth")
    val angelpayAuth: AngelPayAuthDto? = null,        // present only when terminal.brand == "NEXGO" AND venue has ANGELPAY merchants
)

data class AngelPayAuthDto(
    @SerializedName("accountId")    val accountId: String,    // AngelPayUserAccount.id
    @SerializedName("email")        val email: String,
    @SerializedName("pin")          val pin: String,          // decrypted server-side, TLS in transit, never persisted on TPV (see §6.5)
    @SerializedName("environment")  val environment: String,  // "QA" | "PROD"
)
```

```kotlin
// features/payment/domain/model/MerchantAccount.kt — additive changes inside the existing file

data class MerchantAccount(
    val merchantAccountId: String? = null,            // existing — Avoqado CUID
    val processorType: ProcessorType = ProcessorType.BLUMON,  // existing
    // ...other existing fields kept...

    // NEW — additive
    val externalMerchantId: String? = null,           // for AngelPay: numeric string parseable to Int; for Blumon: serial/posId equivalent
    val isActive: Boolean = true,
    val angelpayAffiliation: String? = null,
    val angelpayMerchantName: String? = null,
) {
    /** AngelPay merchant ID as Int — only call when processorType == ANGELPAY. */
    fun requireAngelpayMerchantId(): Int =
        externalMerchantId?.toIntOrNull()
            ?: error("Malformed externalMerchantId for ANGELPAY merchant $merchantAccountId: '$externalMerchantId'")
}
```

**The mapper** (existing `MerchantAccountDto.toDomain()` extension function in the same file) gets these new fields wired through. No new mapper file. No new DTO module.

This mapping lets TPV call `SDK.switchMerchant(merchant.requireAngelpayMerchantId())` for the AngelPay-side switch while still recording payments to backend with `merchantAccountId = merchant.merchantAccountId` (Avoqado CUID).

### 6.4. Application initialization

`AvoqadoTPVApplication.kt` — incremental change to the existing init block (today at lines 153-194). Replace the `secureStorage.saveAngelPayCredentials(...)` block (lines 153-160) with nothing (credentials now come from backend). Keep the existing `AngelPaySDK.initialize(...)` call but route it through the gateway helper.

**Hilt injection safety (PAX startup guard):** a direct `@Inject lateinit var angelPaySdkGateway: AngelPaySdkGateway` would force Hilt to construct `AngelPaySdkGateway` (and transitively pull AngelPay SDK classes) on every flavor's `Application.onCreate`. In PAX flavors (`sandbox`, `production`, `productionRelease`) the AngelPay AAR is NOT packaged, so eager construction would crash startup with `ClassNotFoundException` even though the `if (SUPPORTED_PROCESSOR == "ANGELPAY")` guard is in place. Two acceptable patterns — pick one:

**Pattern A (preferred): Hilt `Provider<T>` + lazy resolution**

```kotlin
@HiltAndroidApp
class AvoqadoTPVApplication : Application() {
    // Provider is constructed without resolving the underlying graph
    @Inject lateinit var angelPaySdkGatewayProvider: javax.inject.Provider<AngelPaySdkGateway>

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.SUPPORTED_PROCESSOR == "ANGELPAY") {
            // .get() resolves only inside the guarded branch — never executes on PAX flavors
            angelPaySdkGatewayProvider.get()
                .ensureInitialized(applicationContext, BuildConfig.ANGELPAY_ENV)
        }
        // existing Blumon init for sandbox/production flavors — untouched
    }
}
```

**Pattern B: flavor-source-set binding** — `AngelPaySdkGateway` lives in `app/src/nexgo/.../AngelPaySdkGateway.kt` AND a no-op stub or interface lives in `app/src/sandbox`/`app/src/production`/`app/src/productionRelease`. Hilt module binds the appropriate implementation per flavor. PAX builds compile against the stub; AngelPay code never enters PAX classpath. This is heavier (more source-set ceremony) but most defensible.

**Verification gate** (mandatory part of §17.6 regression suite):
- Run `./gradlew assembleSandboxDebug` and `assembleProductionRelease` end-to-end
- Manually launch the PAX APK on a real PAX A910S (sandbox flavor) and confirm `Application.onCreate` completes without exceptions. Capture `adb logcat` to PR.
- If Pattern A produces any startup classloader warning related to AngelPay symbols in PAX builds, escalate to Pattern B.

### 6.5. Auth flow (lazy + resilient)

`AngelPayAuthRepository.kt`:

```kotlin
@Singleton
class AngelPayAuthRepository @Inject constructor(
    private val sdkGateway: AngelPaySdkGateway,
    private val terminalConfigRepo: TerminalConfigRepository,
    private val secureStorage: SecureStorage,
    private val crashlytics: FirebaseCrashlytics,
) {
    private val _state = MutableStateFlow(AngelPayAuthState.UNAUTHENTICATED)
    val state: StateFlow<AngelPayAuthState> = _state.asStateFlow()

    suspend fun ensureAuthenticated(): Result<Unit> = withContext(Dispatchers.IO) {
        if (sdkGateway.isAuthenticated()) {
            _state.value = AngelPayAuthState.AUTHENTICATED
            return@withContext Result.success(Unit)
        }

        val angelpayAuth = terminalConfigRepo.getCachedConfig()?.angelpayAuth
            ?: return@withContext Result.failure(MissingAngelPayCredsError)

        _state.value = AngelPayAuthState.AUTHENTICATING
        val result = sdkGateway.authenticateSimple(angelpayAuth.email, angelpayAuth.pin)

        result.fold(
            onSuccess = { authResult ->
                when (authResult) {
                    is AuthenticateSimpleResult.Success -> {
                        _state.value = AngelPayAuthState.AUTHENTICATED
                        reportValidation(angelpayAuth.accountId, success = true)
                        Result.success(Unit)
                    }
                    is AuthenticateSimpleResult.MerchantSelectionRequired -> {
                        _state.value = AngelPayAuthState.SELECTING_MERCHANT(authResult.merchants, authResult.temporaryToken)
                        // operator picks via AngelPayMerchantSelectorScreen
                        Result.success(Unit)
                    }
                }
            },
            onFailure = { error ->
                _state.value = AngelPayAuthState.AUTH_ERROR(error.message ?: "Unknown auth error")
                reportValidation(angelpayAuth.accountId, success = false, error = error.message)
                crashlytics.recordException(error)
                Result.failure(error)
            }
        )
    }

    suspend fun completeMerchantSelection(merchantId: Int, tempToken: String): Result<Unit> { /* ... */ }
    suspend fun handleAuthExpiry(): Result<Unit> { /* ensure + retry once */ }
    fun logout() { sdkGateway.logout(); _state.value = AngelPayAuthState.UNAUTHENTICATED }

    private suspend fun reportValidation(accountId: String, success: Boolean, error: String? = null) {
        runCatching { /* POST /tpv/angelpay/report-validation */ }  // fire-and-forget, don't fail auth on this
    }
}
```

### 6.6. Multi-merchant switching

`AngelPayMerchantRepository.kt` — **single source of truth for the active AngelPay merchant ID**:

```kotlin
@Singleton
class AngelPayMerchantRepository @Inject constructor(
    private val sdkGateway: AngelPaySdkGateway,
    private val merchantCacheDao: AngelPayMerchantCacheDao,
) {
    private val _activeAngelPayMerchantId = MutableStateFlow<Int?>(null)
    /** Authoritative active merchant ID — updated on every successful select/switch, cleared on logout/auth failure */
    val activeAngelPayMerchantId: StateFlow<Int?> = _activeAngelPayMerchantId.asStateFlow()

    suspend fun fetchAndCacheMerchants(): Result<List<MerchantSummary>> = withContext(Dispatchers.IO) {
        sdkGateway.getUserMerchants().onSuccess { merchants ->
            merchantCacheDao.replaceAll(merchants.map { it.toEntity() })
            // Sync active flag from SDK's truth (the `isActive` field on MerchantSummary)
            merchants.firstOrNull { it.isActive }?.id?.let { _activeAngelPayMerchantId.value = it }
        }
    }

    fun observeCachedMerchants(): Flow<List<MerchantSummary>> =
        merchantCacheDao.observeAll().map { it.map { e -> e.toDomain() } }

    /** Called after `AuthenticateSimpleResult.MerchantSelectionRequired` — finalizes initial pick. */
    suspend fun completeInitialSelection(merchantId: Int, temporaryToken: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            sdkGateway.selectMerchant(merchantId, temporaryToken).onSuccess {
                _activeAngelPayMerchantId.value = merchantId
                merchantCacheDao.markActive(merchantId)
            }
        }

    /** Called when operator switches mid-shift via UI. */
    suspend fun switchActiveMerchant(merchantId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        val previousActiveId = _activeAngelPayMerchantId.value
        sdkGateway.switchMerchant(merchantId).onSuccess {
            _activeAngelPayMerchantId.value = merchantId
            merchantCacheDao.markActive(merchantId)
        }.onFailure {
            // SDK call failed — keep previous active; do NOT mutate _activeAngelPayMerchantId
            // Caller (ViewModel) reverts _currentMerchant and surfaces toast
            // (no use of getSessionInfo()?.merchantToken — vendor has not confirmed semantics)
            if (previousActiveId != null) merchantCacheDao.markActive(previousActiveId)
        }
    }

    /** Called from AngelPayAuthRepository on logout or unrecoverable auth failure. */
    fun clearActive() {
        _activeAngelPayMerchantId.value = null
    }
}
```

**Consumers**:
- `AngelPayPaymentViewModel.startPayment(...)` reads `activeAngelPayMerchantId.value` and asserts equality with `_currentMerchant.value?.requireAngelpayMerchantId()` before calling `createPaymentIntent` (see §6.7 guard)
- `AngelPayAuthBanner.kt` may observe the flow to show "Activo: <merchantName>" on the chip

### 6.7. Payment flow — incremental change to existing `AngelPayPaymentViewModel`

The repo already has `AngelPayPaymentViewModel` with `AngelPayPaymentState` (sealed class, starts at `Idle`), `RecordPaymentUseCase`, `_currentMerchant`, `_merchants`, `_state` flows. The migration does NOT introduce a new ViewModel, a new `PaymentUiState`, an `OrderPaymentInput` DTO, or an `AngelPayPaymentRepository`. Work is purely incremental on existing types.

**Incremental changes (pseudocode, illustrating intent only):**

```kotlin
// existing AngelPayPaymentViewModel.kt — add these collaborators by injection
@HiltViewModel
class AngelPayPaymentViewModel @Inject constructor(
    // ...existing deps...
    private val recordPaymentUseCase: RecordPaymentUseCase,                  // existing
    private val angelPaySdkGateway: AngelPaySdkGateway,                       // existing or newly injected
    // NEW additive deps
    private val angelPayAuthRepository: AngelPayAuthRepository,
    private val angelPayMerchantRepository: AngelPayMerchantRepository,
) : ViewModel() {

    // existing fields:
    //   private val _state = MutableStateFlow<AngelPayPaymentState>(AngelPayPaymentState.Idle)
    //   private val _merchants = MutableStateFlow<List<MerchantAccount>>(emptyList())
    //   private val _currentMerchant = MutableStateFlow<MerchantAccount?>(null)
    // (no new state types — extend AngelPayPaymentState if needed for switch-in-flight)

    // existing fun selectMerchant(merchant: MerchantAccount) (line 357) — MODIFIED behavior only:
    //   1. set _currentMerchant = merchant
    //   2. invoke angelPayMerchantRepository.completeInitialSelection(...) OR switchActiveMerchant(...) as appropriate
    //   3. on failure: revert _currentMerchant, emit error via _state
    //   (no new method name — keep the existing entry point used by AngelPayPaymentScreen)

    // existing fun startPayment(...) — additive guard at top:
    //   guard: assert _currentMerchant.value?.requireAngelpayMerchantId() ==
    //          angelPayMerchantRepository.activeAngelPayMerchantId.value
    //   if mismatch: trigger switch first; otherwise proceed to createPaymentIntent path (already in place)

    // existing handlePaymentResult (or equivalent) — when result.approved:
    //   recordPaymentUseCase(...) // existing call, just pass merchantAccountId resolved via the Map<Int,String>
}
```

The **decision logic** described earlier (auth lazy, MSI rules client-side, etc.) is implementation guidance — the actual edits live inside existing methods, not in a new ViewModel. When implementation lands, the plan in `writing-plans` will spell out the exact diffs.

`AngelPayPaymentScreen.kt` — additive change to existing screen:

The existing screen already collects `viewModel.state` (`AngelPayPaymentState`) and is wired to the SDK launcher. Incremental work:

- Observe the new `AngelPayAuthBanner` composable at the top, fed by `angelPayAuthRepository.state` exposed via the ViewModel
- When `AngelPayPaymentState` transitions to a "ready to launch" sub-state (extending the existing sealed class; no new `PaymentUiState` type), `LaunchedEffect` calls `paymentLauncher.launch(request)` — the existing contract and `handlePaymentResult` callback paths are reused
- The merchant selection branch already uses `MerchantSelectionContent` (line 363 in current code); no new screen — only the ViewModel-side hook (§6.8) changes

### 6.8. Merchant selector UX (hook map — Blumon untouched)

**Shared composable (no changes):** `features/payment/presentation/MerchantSelectionContent.kt` is reused by both Blumon `PaymentScreen.kt:473` and `AngelPayPaymentScreen.kt:363`. We do **not** modify this composable.

**Blumon path (untouched, no changes):**
- `PaymentScreen.kt:485` → `viewModel.updateSelectedMerchant(merchant)` (visual-only state set)
- Actual PAX SDK swap happens later inside `PaymentViewModel.startPayment()` via `multiMerchantSDKManager.switchMerchant(...)`
- This semantics, ordering, and split between visual and SDK switch must remain unchanged

**AngelPay path (this is what we change):**

1. **Initial merchant selection** (after `authenticateSimple` returns `MerchantSelectionRequired`):
   - Full-screen flow uses the same `MerchantSelectionContent` composable populated from `MerchantSelectionRequired.merchants`
   - Operator taps one → `AngelPayPaymentViewModel.selectMerchant(merchant)` (existing method, line 357) — **modified** behavior:
     - Today: only assigns `_currentMerchant = merchant`
     - New: assigns `_currentMerchant`, then calls `AngelPayMerchantRepository.completeInitialSelection(merchant.requireAngelpayMerchantId(), tempToken)` which wraps `AngelPaySDK.selectMerchant(id, tempToken)`. On success, repository sets `activeAngelPayMerchantId`. On failure, repository clears `_currentMerchant` and emits an error event for the screen.

2. **Runtime switch** (operator changes merchant mid-shift):
   - Same UI entry-point `MerchantSelectionContent` (or the top-bar chip-based bottom sheet for inline switching)
   - Operator taps one → `AngelPayPaymentViewModel.selectMerchant(merchant)` (same method, different SDK call):
     - Calls `AngelPayMerchantRepository.switchActiveMerchant(merchant.requireAngelpayMerchantId())` which wraps `AngelPaySDK.switchMerchant(id)`
     - On success: repository updates `activeAngelPayMerchantId`
     - On failure: repository reverts `_currentMerchant` to the previous merchant and emits toast "No se pudo cambiar de merchant; sigues en X"

3. **Payment-time guard (critical correctness rule):**
   - `AngelPayPaymentViewModel.startPayment(...)` first asserts `_currentMerchant.value?.requireAngelpayMerchantId() == AngelPayMerchantRepository.activeAngelPayMerchantId.value`
   - If they diverge (e.g., operator picked a merchant but SDK switch hasn't completed yet), the ViewModel either waits for in-flight switch, or triggers a fresh switch before calling `createPaymentIntent`
   - **A card transaction must never start while the SDK active merchant disagrees with the Avoqado-selected merchant**

4. **Audit trail:** every successful switch reported via `POST /tpv/angelpay/report-merchant-switch` (fire-and-forget; never blocks UI)

### 6.9. Avoqado MerchantAccount mapping

When TPV records a payment to Avoqado backend, it must reference the **Avoqado MerchantAccount.id** (CUID), not the AngelPay merchantId (Int). Mapping:

- TPV builds `Map<Int, String>` (AngelPay merchant ID parsed from `externalMerchantId` → Avoqado `merchantAccountId` CUID) from the filtered merchants in `TerminalConfigDto`
- **`AngelPayMerchantRepository` is the single source of truth** for the currently-active AngelPay merchant. After every successful `selectMerchant(id, tempToken)` or `switchMerchant(id)`, it stores `activeAngelPayMerchantId: Int` (e.g., in a `StateFlow<Int?>`). Cleared on logout or auth failure.
- At payment time: read `activeAngelPayMerchantId` from the repository, look up the Avoqado CUID through the map, send as `merchantAccountId` in payment record (existing flow).
- **Do NOT infer the active merchant from `SDK.getSessionInfo()?.affiliation`** — affiliation is a separate value from `MerchantOption.id` and the vendor has not confirmed they are stable/equal. Using affiliation would silently misroute payments if AngelPay ever changes the relationship.

**Post-auth configuration consistency check (mandatory, no silent fallback):**

Immediately after `authenticateSimple()` succeeds (or after every subsequent successful `getUserMerchants()` refresh), `AngelPayMerchantRepository.validateAgainstConfig(terminalConfig)` runs:

```kotlin
fun validateAgainstConfig(config: TerminalConfigDto) {
    val sdkMerchantIds: Set<Int> = sdkGateway.getUserMerchants().getOrNull()?.map { it.id }.orEmpty().toSet()
    val avoqadoMerchantIds: Set<Int> = config.merchants
        .filter { it.providerCode == "ANGELPAY" && it.isActive }
        .mapNotNull { it.externalMerchantId?.toIntOrNull() }
        .toSet()

    val missingInAvoqado = sdkMerchantIds - avoqadoMerchantIds
    val missingInSdk = avoqadoMerchantIds - sdkMerchantIds

    if (missingInAvoqado.isNotEmpty() || missingInSdk.isNotEmpty()) {
        // CONFIG ERROR — surface to operator + report to backend; do NOT silently fall back
        crashlytics.recordException(
            AngelPayConfigMismatchError(
                missingInAvoqado = missingInAvoqado,
                missingInSdk = missingInSdk,
            )
        )
        _state.value = AngelPayAuthState.CONFIG_MISMATCH(
            message = "AngelPay y Avoqado tienen merchants desincronizados. " +
                "Faltan en Avoqado: $missingInAvoqado. Faltan en AngelPay: $missingInSdk. " +
                "Contacta soporte para revisar la configuración."
        )
        reportToBackend("config-mismatch", missingInAvoqado, missingInSdk)  // fire-and-forget POST
        // Payment flow is BLOCKED until config is corrected
    }
}
```

Rules:
- If a merchant exists in SDK but not in Avoqado config → **block payments** (we don't know how to record them) and surface the operator-facing banner above
- If a merchant exists in Avoqado config but not in SDK → **block payments** (operator could pick it from selector and we'd fail at `switchMerchant`)
- Either condition is a hard configuration error reported to the backend via the same `/tpv/angelpay/report-validation` endpoint (with a new `state: "CONFIG_MISMATCH"` field)
- Re-run validation on every: app cold start, socket reconnect, manual config refresh, and immediately post-auth
- **No silent fallback to "first available merchant"** — the operator must know something is misconfigured

### 6.10. Error mapping

`AngelPayErrorMapper.kt`:

```kotlin
object AngelPayErrorMapper {
    fun toUserMessage(status: String?, code: String?, callResult: CallResult?): String = when {
        status == "DECLINED" -> "Tarjeta declinada (${code ?: "—"})"
        status == "CANCELLED" -> "Pago cancelado"
        status == "TIMEOUT" -> "Tiempo agotado, intenta de nuevo"
        callResult?.category == "GATEWAY" -> "Error del banco: ${callResult.message}"
        callResult?.category == "USER" -> callResult.message
        callResult?.category == "EMV" -> "Error de chip, intenta nuevamente"
        callResult?.category == "NETWORK" -> "Sin red, reintentando..."
        else -> callResult?.message ?: "Error desconocido"
    }

    fun isAuthError(code: String?): Boolean = code?.startsWith("C2") == true  // TBD with vendor
}
```

### 6.11. Reliability hooks

- **On socket reconnect** (existing `SocketManager.onConnect`): refetch terminal config → if the active AngelPay merchant (matched via `externalMerchantId`) is no longer in the compatible merchant list, switch to first available + toast
- **On Avoqado backend reconnect**: re-validate `angelpayUserAccount` is still configured; if account was deleted server-side, force logout
- **On detected auth error during payment**: call `angelPayAuthRepo.handleAuthExpiry()` (re-authenticate once + retry)
- **On airplane mode toggle** (broadcast receiver): show offline indicator on auth banner

### 6.12. Observability

- Crashlytics: every SDK call wrapped, errors recorded with breadcrumbs
- Log capture script: add `angelpay` keyword to `scripts/capture-logs.sh` features list
- ADB monitoring: `AngelPayAuthRepository`, `AngelPayMerchantRepository`, `AngelPayPaymentViewModel` log tags
- Heartbeat: `angelpayAuthState`, `angelpayActiveMerchantId`, `angelpaySdkVersion` reported every 30s

### 6.13. Receipt printing strategy (MVP: adapter only, no new UX)

Aligned with §2.2 non-goals — no receipt fallback or email-ticket UX ships in MVP:

- Avoqado's existing printing system (Bluetooth printer, PAX/Nexgo native printer) remains the sole owner of receipts. No code change.
- SDK `printTicket()` is **NOT** invoked from any operator-facing path. The existing `AngelPaySdkPostOperationsAdapter.printTicket(...)` stays compile-tested + smoke-tested only (see §10.3 adapter scenarios).
- SDK `sendTicketEmail()` / `getTicketUrl()` are **NOT** exposed to operators in MVP. The existing adapter methods remain on `AngelPaySdkPostOperationsAdapter` for Phase 2 promotion. Any pre-existing `_isSendingReceipt` / `_sendReceiptMessage` state already in `AngelPayPaymentViewModel` stays internal/unused at the UI level for MVP — no Compose UI surfaces them.
- Phase 2 may promote any of these to shipped UX after a dedicated design + QA round.

---

## 7. Reliability & Network Resilience (Cross-cutting)

### 7.1. Retry policies

| Operation | Strategy | Max attempts | Timeout per attempt |
|---|---|---|---|
| `initialize` | None (synchronous) | 1 | — |
| `authenticateSimple` | Exponential backoff | 3 | 10s |
| `selectMerchant` | Exponential backoff | 3 | 5s |
| `switchMerchant` | Exponential backoff | 2 | 5s |
| `createPaymentIntent` (validation) | None (fast) | 1 | 2s |
| Payment submit (EMV) | SDK internal | — | SDK default |
| `getUserMerchants` | Exponential backoff | 2 | 5s |
| Cancel / Refund | None (operator can retry) | 1 | 15s |
| History fetch | Exponential backoff | 2 | 10s |

### 7.2. Caching policy

| Data | TTL | Refresh trigger |
|---|---|---|
| Terminal config (`/tpv/terminals/:serial/config`) | 5 min | App resume, socket reconnect, remote command |
| AngelPay user merchants (`MerchantSummary[]`) | Session lifetime | Login, manual refresh, switch failure |
| Current `MerchantInfo` (capabilities) | Until merchant switch | After successful `switchMerchant` |
| SDK auth state | SDK internal | — |

### 7.3. Failure modes catalog

| Failure | Symptom | Recovery |
|---|---|---|
| No backend creds available | Banner red "Configura AngelPay en dashboard" | Operator contacts admin; admin creates `AngelPayUserAccount` in dashboard |
| Auth network failure (transient) | Banner yellow "Reconectando..." | Auto-retry 3x; if persists, manual retry button |
| Auth failure (wrong PIN) | Banner red "Credenciales inválidas" | Admin rotates PIN in dashboard |
| Merchant switch fail | Toast "No se pudo cambiar de merchant", revert | Operator retries when network recovers |
| Payment auth expiry | Auto re-auth + retry payment once | If still fails, surface error |
| AngelPay backend down | Banner red "Servicio AngelPay no disponible" | Operator falls back to cash, or to another device/processor in the same venue if available. (Per single-processor-per-device rule, the affected Nexgo terminal cannot route to Blumon; operator may walk to a PAX terminal in the same venue if one exists.) |
| Unknown SDK error | Crashlytics report + generic toast | Engineering investigates |

### 7.4. Offline policy

- TPV must NEVER block on AngelPay auth at boot. If creds unavailable or network down: enter "offline auth" mode with red banner; all non-AngelPay features (orders, menu, kitchen) work normally
- Cash payments and Blumon payments (in mixed venues — though current rule is single-processor-per-device, mixed setups don't apply) continue working
- AngelPay card transactions **cannot be approved offline** — the SDK refuses. UX: "AngelPay requiere conexión a internet. Intenta con efectivo o espera reconexión." No queueing of un-approved card transactions.

### 7.5. Offline queue (corrected semantics)

The existing offline queue (used today for Blumon) covers **post-approval backend recording**, not card transactions themselves. Same rule applies to AngelPay:

```
Card flow (online required):
  SDK.createPaymentIntent → SDK launches payment Activity → operator inserts/taps card →
  SDK contacts gateway → PaymentResult { approved: true, authCode, reference, ... }
                              │
                              ▼
  Avoqado-side recording (queueable):
  OrderRepository.recordPayment(...)
    → POST /api/v1/tpv/orders/:id/payments  (this is the call that may be queued offline)
```

If `PaymentResult.approved == false` (decline, cancel, timeout, error): no backend record, no queue entry, just surface error to operator. The transaction never happened on the gateway side.

If `PaymentResult.approved == true` but backend recording fails (network blip between gateway and Avoqado server): enqueue the recording. The gateway already approved; we just need to sync to Avoqado at some later point. This is identical to existing Blumon behavior.

---

## 8. Observability

### 8.1. Backend (avoqado-server)

- New Pino logger context `angelpay.user-account` for auth ops
- Metrics (StatsD/Datadog):
  - `avoqado.angelpay.user_account.created`
  - `avoqado.angelpay.user_account.rotated`
  - `avoqado.angelpay.validation.success` (from TPV report endpoint)
  - `avoqado.angelpay.validation.failure`
  - `avoqado.angelpay.merchant_switch.success`
  - `avoqado.angelpay.merchant_switch.failure`

### 8.2. TPV (avoqado-tpv)

- Crashlytics: all SDK exceptions
- Custom breadcrumbs on auth/switch events
- Heartbeat fields:
  - `angelpayAuthState`
  - `angelpayActiveMerchantId`
  - `angelpaySdkVersion` (from `AngelPaySDK.version()`)
  - `angelpayLastAuthError` (max 200 chars)

### 8.3. Dashboard

- New page: Per-venue AngelPay status panel (consume backend health metrics)
- Alerts: PIN validation failed > 3 times in 1 hour → email superadmin

---

## 9. Permissions

Use existing TPV permission system. New additions:

| Permission | Description | Default roles |
|---|---|---|
| `tpv-payments:angelpay-switch-merchant` | Can switch active AngelPay merchant mid-shift | CASHIER, MANAGER, ADMIN |
| `tpv-payments:angelpay-refund` | Can issue AngelPay refund via SDK | MANAGER, ADMIN |
| `tpv-payments:angelpay-cancel` | Can cancel same-day AngelPay transaction | CASHIER, MANAGER, ADMIN |

Existing permissions reused:
- `tpv-payments:create` — required to charge (any processor)
- `tpv-payments:refund` — currently admin-only; expanded to MANAGER for AngelPay
- `superadmin:full` — required for dashboard AngelPay account management

---

## 10. Testing Strategy

### 10.1. Unit tests (TPV) — explicit inventory

All new tests use `MockK + UnconfinedTestDispatcher` and call `viewModel.viewModelScope.cancel()` at end per project convention. Existing `PaymentViewModelTest` (Blumon) untouched.

**`AngelPayAuthRepositoryTest.kt`** (~10 tests):
- `ensureAuthenticated() with backend creds → success path emits Authenticated`
- `ensureAuthenticated() with backend creds → MerchantSelectionRequired emits SelectingMerchant`
- `ensureAuthenticated() with invalid PIN → AuthError + reportValidation(success=false)`
- `ensureAuthenticated() with network timeout → 3 retries with exponential backoff (0.5s, 1s, 2s)`
- `ensureAuthenticated() with all retries exhausted → AuthError surfaced`
- `ensureAuthenticated() refuses when account status != ACTIVE (D3) → AccountSuspended state`
- `handleAuthExpiry() triggers re-auth + retries payment once`
- `handleAuthExpiry() second failure surfaces error`
- `state transitions cover all enum values`
- `logout() clears in-memory PIN and resets state`

**`AngelPayMerchantRepositoryTest.kt`** (~8 tests including D2 race + D6 periodic):
- `completeInitialSelection() on success updates _activeAngelPayMerchantId + cache`
- `switchActiveMerchant() rejected when payment is charging (D2)`
- `switchActiveMerchant() times out at 8s (D2)`
- `switchActiveMerchant() failure keeps previous activeId (D2)`
- `multi-tap cancels in-flight switch and starts fresh (D2)`
- `fetchAndCacheMerchants() periodic refresh fires at 15min (D6)`
- `fetchAndCacheMerchants() paused while app background (D6)`
- `refreshBeforeSelector() invoked when switcher opens (D6)`

**`AngelPaySdkGatewayTest.kt`** (~6 tests — extensions to existing wiring):
- `ensureInitialized() succeeds on first call, no-op on subsequent`
- `getUserMerchants() returns SDK list and caches`
- `getUserMerchants() offline → returns Room cache`
- `switchMerchant() SDK success → returns Result.success`
- `switchMerchant() SDK auth error → returns categorized failure`
- `switchMerchant() SDK network error → returns categorized failure`

**`AngelPayConfigValidationTest.kt`** (~5 tests for D5):
- `validateAgainstConfig() — all merchants match → AllClear`
- `validateAgainstConfig() — SDK has extra → PartialOperable + reportToBackend`
- `validateAgainstConfig() — Avoqado has extra → PartialOperable + warning`
- `validateAgainstConfig() — empty intersection → HardBlock`
- `validateAgainstConfig() — SDK list empty → HardBlock`

**`AngelPayCredentialResolverTest.kt`** (~3 tests for D4):
- `resolveCredentials() prefers backend angelpayAuth`
- `resolveCredentials() falls back to BuildConfig + records deprecation warning`
- `resolveCredentials() both null → MissingAngelPayCredsError`

**`AngelPayPinHandlingTest.kt`** (~3 tests for §4.5b):
- `Room cache write of TerminalConfigDto strips angelpayAuth.pin`
- `RedactingLoggingInterceptor redacts "pin" field in OkHttp logs`
- `Crashlytics asserter throws on 6-digit numeric string in debug builds`

**`NexgoFlavorHiltGraphTest.kt`** (~2 tests for §17.5):
- `Hilt graph in nexgo flavor does not eagerly instantiate BlumonInitializer`
- `Hilt graph in nexgo flavor does not eagerly instantiate MultiMerchantSDKManager`

**`PaxStartupSmokeTest.kt`** (~2 tests for §6.4 Pattern A safety):
- `sandboxDebug Application.onCreate completes without ClassNotFoundException`
- `productionRelease Application.onCreate does not load AngelPay symbols`

**`AngelPayPaymentViewModelTest.kt`** (~5 tests — additive to existing file):
- `startPayment() waits for in-flight switch up to 8s (D2)`
- `startPayment() errors after 8s if switch never completes (D2)`
- `startPayment() rejects when intersection empty (D5 hard-block)`
- `startPayment() proceeds when current merchant in intersection (D5 partial-operable)`
- `handlePaymentResult(approved=true) calls recordPaymentUseCase with resolved merchantAccountId`

**`AngelPayErrorMapperTest.kt`** (~2 tests):
- `toUserMessage() maps known SDK codes (S000, G500, C2xx, U100/U101, E699) to user-friendly text`
- `isAuthError() returns true only for C2xx prefix`

**TPV inventory subtotal: 46 unit tests** across 10 files.

Backend tests (§10.2) add ~10 more. Manual QA (§10.3) shipped scenarios add ~10 instrumented flows.

**Target: 220 existing + 46 new TPV unit tests + 10 backend = ~276 tests, 0 failures.**

### 10.2. Integration tests (backend)

- `AngelPayUserAccountService.test.ts` — create, rotate, validate, error recording
- `MerchantAccountService.test.ts` — device compatibility validation
- `terminal.tpv.controller.test.ts` — terminal config response includes `angelpayAuth` only when relevant

### 10.3. Manual QA on real Nexgo N86

Using provided QA test user (2 demo commerces — see References §16 for vault path):

**Shipped UX scenarios (must pass for MVP release):**

1. ✅ Cold start → SDK initializes
2. ✅ First payment trigger → auth flow → multi-merchant selection screen → pick first → payment OK
3. ✅ Mid-shift switch via top-bar chip → second merchant active
4. ✅ Process payment with tip on restaurant-type merchant
5. ✅ Reject tip on retail-type merchant (validation error)
6. ✅ Auth banner shows correct state during network loss + recovery
7. ✅ Merchant switch fails offline → reverts to previous merchant
8. ✅ Token expiry → auto re-auth + retry
9. ✅ Logout → re-login flow
10. ✅ Blumon `sandbox` and `production` flavors compile and pass their existing test suite (regression gate — see §17 Guardrails)

**Adapter smoke-only (not shipped UX in MVP — verifies adapter still works for Phase 2):**

11. 🔬 Same-day cancellation via `AngelPaySdkPostOperationsAdapter.cancelTransaction` — invoked via dev menu, not exposed in operator UI. PASS criterion: SDK returns expected result; no UI shipped.
12. 🔬 Next-day refund via `AngelPaySdkPostOperationsAdapter.refundTransaction` — same as above. If this fails QA, defer refund/cancel entirely (per Goal G10).
13. 🔬 Transaction history sync via `AngelPaySdkPostOperationsAdapter.getTransactionHistory` — dev-menu only.
14. 🔬 MSI 3-month plan capability read from `getMerchantInfo().msiPlans` — adapter must surface plan list; **no plan-picker UI ships in MVP**.
15. 🔬 Digital ticket email via `sendTicketEmail` — adapter invoked, but **no post-payment "Email receipt" UX ships in MVP**.

### 10.4. Cross-flavor regression

Sandbox/production flavors (Blumon) must:
- Not link AngelPay AAR (verified by APK inspection)
- Continue Blumon payments without regression
- Respect `BuildConfig.SUPPORTED_PROCESSOR == "BLUMON"` filter

---

## 11. Rollout Plan

### Phase 0 — Prep (days 1-2)

- [ ] Swap AAR 1.0.4 → 1.0.5, recompile, run existing test suite (220 tests, expect 0 failures)
- [ ] Rotate AngelPay QA credentials with vendor (previous BuildConfig values considered compromised)
- [ ] Spec audit by another LLM + engineering review (this doc — v2 in progress)

### Phase 1 — Backend (days 3-5)

- [ ] Schema: `AngelPayUserAccount` model + additive fields on `MerchantAccount`
- [ ] Seed: `PaymentProvider.ANGELPAY` row
- [ ] `lib/providerDeviceCompatibility.ts` helper
- [ ] `AngelPayUserAccountService` (create/rotate/get/markValidated/recordError) + unit tests
- [ ] `merchantAccount.service.ts`: AngelPay-aware createMerchantAccount with empty `credentialsEncrypted = encryptCredentials({})` path
- [ ] **4 validation points** wired: createMerchantAccount, assignMerchantToTerminal, terminal update, `/tpv/terminals/:serial/config`
- [ ] Modified terminal config endpoint returns `angelpayAuth` (decrypted PIN) when applicable
- [ ] New `POST /tpv/angelpay/report-validation` endpoint
- [ ] Deploy to staging, smoke test

### Phase 2 — Dashboard (days 6-7)

- [ ] AngelPay account management page (create/rotate/delete + status)
- [ ] MerchantAccount form: AngelPay-specific fields (affiliation, display name) + compatibility banner
- [ ] Terminal form: brand dropdown (PAX/NEXGO/INGENICO/VERIFONE)
- [ ] Terminal assignment view: filter merchants by brand compatibility
- [ ] Brand-change warning UI (incompatible merchant list + confirm dialog)
- [ ] Deploy to staging

### Phase 3 — TPV (days 8-12)

- [ ] AAR swap to 1.0.5 + remove `ANGELPAY_QA_*` BuildConfig fields
- [ ] Extend `MerchantAccountDto` + `TerminalConfigDto` + domain `MerchantAccount` (3 additive fields)
- [ ] Delete `AvoqadoTPVApplication.kt:153-160` auto-provisioning block + `AngelPayCredentials` once refactor lands
- [ ] Refactor `AngelPaySdkGateway` to source creds from `TerminalConfigRepository`
- [ ] Add `getUserMerchants()` + `switchMerchant()` to `AngelPaySdkGateway`
- [ ] Implement `AngelPayAuthRepository` + state machine
- [ ] Implement `AngelPayMerchantRepository` + Room cache for merchant list
- [ ] UI: `AngelPayMerchantSelectorScreen` (initial selection after `MerchantSelectionRequired`)
- [ ] UI: `AngelPayMerchantSwitcherSheet` (runtime switcher in top bar)
- [ ] UI: `AngelPayAuthBanner` (persistent state chip)
- [ ] Wire payment flow to source creds from backend (replace BuildConfig path)
- [ ] Unit tests: `AngelPayAuthRepositoryTest`, `AngelPayMerchantRepositoryTest`, runtime switch in `PaymentViewModelTest` (~20 new tests)
- [ ] Verify existing `AngelPaySdkPostOperationsAdapter` (history/cancel/refund/print/ticket) still compiles + passes — if not, defer those features

### Phase 4 — QA on real Nexgo (days 13-15)

- [ ] Install nexgoDebug APK on Nexgo N86
- [ ] Execute manual QA checklist (focused on multi-merchant + auth resilience)
- [ ] Capture logs + Crashlytics review
- [ ] Fix issues, iterate
- [ ] If refund/cancel adapter passes QA: include in MVP; otherwise defer

### Phase 5 — Production rollout (days 16+, gated)

- [ ] Configure first production venue (manual: AngelPay creates user, Avoqado admin enters in dashboard)
- [ ] Build `nexgoProd` flavor (env=PROD)
- [ ] Sign with Nexgo signing (process TBD — coordinate with AngelPay)
- [ ] Deploy via remote install command (existing `INSTALL_VERSION` system)
- [ ] Monitor heartbeats + Crashlytics for 48h
- [ ] Gradual rollout to additional venues

### Version bump

- TPV: **MINOR** (new user-facing capability: multi-merchant AngelPay) — e.g., 2.0.0 → 2.1.0
- Backend + Dashboard: standard deploy (no version pinning concerns)

### Cross-repo order (per CLAUDE.md)

1. Backend deployed to production (minutes)
2. Wait stable (~24h)
3. Dashboard deployed to production (minutes)
4. Wait stable (~24h)
5. TPV APK signed + deployed (3-5 days via AngelPay/Nexgo signing)

---

## 12. Phase 2 (Deferred)

Features available in SDK 1.0.5 but not in initial release:

- **Hotel check-in** — no hotel venues in Avoqado today; defer until first hotel client
- **Unified Card Reader (NFC/Magnetic)** — for loyalty cards, member cards, gift cards; defer until product spec exists
- **Reauthorization** — only relevant for hotel/car rental holds; tied to hotel
- **Print via SDK fallback** — implement when Avoqado's primary printer system shows >1% failure rate

---

## 13. Risks & Mitigations

| Risk | Probability | Impact | Mitigation |
|---|---|---|---|
| AngelPay SDK token expiry behavior undocumented | High | Medium | Test exhaustively; coordinate with AngelPay engineering; error codes catalog from page 14 maps `Cxxx` to category USER/EMV — we'll map `C2xx` heuristically |
| Nexgo signing process unclear | Medium | High | Coordinate with AngelPay (André + technical contact); same process as their `angel-pay-consumer` example |
| Device-processor enforcement vs custom (multi-device) providers | Low | Low | `PROVIDER_DEVICE_COMPATIBILITY` extensible; can add `MENTA: ['PAX', 'NEXGO']` if needed |
| Operator overwhelmed by merchant switcher UX | Medium | Medium | Pre-select first merchant; runtime switcher hidden until tapped; default to single-merchant UX if only 1 exists |
| Auth state desync between SDK and Avoqado UI | Medium | Medium | Always trust `SDK.isAuthenticated()` as source of truth; observe every 10s |
| Crash if AAR missing from build | Low | Critical | Gradle constraint: `nexgo` flavor REQUIRES the AAR; CI build fails if missing |
| PIN leakage in logs | Low | High | Never log PIN; only log validation result (success/error message); audit Crashlytics breadcrumbs |
| AngelPay env=QA leaks into production by mis-flavor | Low | High | `nexgo` (debug) → QA; `nexgoProd` → PROD; verified by BuildConfig + Sentry tag |
| Concurrent merchant switch + payment-in-progress | Low | Medium | Block `switchMerchant` while `paymentInProgress`; queue if needed |

---

## 14. Open Questions (to resolve before implementation)

1. **AngelPay error code catalog**: vendor docs mention `~95 codes in AppErrorCatalog`. Do we have a copy of the full catalog? If not, request from technical contact.
2. **Auth code regex**: which code prefixes indicate auth-category errors? (Spec assumes `C2xx`; needs confirmation.)
3. **Nexgo signing**: what's the exact process? PAXSTORE-equivalent or direct sideload?
4. **Production AngelPay env switch**: is there a separate AAR for production, or same AAR with `env="PROD"` param?
5. **Webhook integration**: does AngelPay push transaction events to a webhook (for backend reconciliation), or do we poll via `getTransactionHistory`?
6. **Refund SLA**: can refunds be issued any time, or only within 6 months / certain window?
7. **MSI flow UX**: does operator pick MSI plan from a list (TPV UI), or does customer choose at terminal? (Affects PaymentRequest building.)
8. **Logout policy**: if backend deletes `AngelPayUserAccount`, should TPV force logout immediately or at next config refresh?
9. **Multiple venues per Nexgo terminal**: today `Terminal.venueId` is 1:1. Does Madre Café use one terminal across 3 venues, or one terminal per venue? (Affects whether `AngelPayUserAccount` is venue-scoped or org-scoped.)

---

## 15. Glossary

- **MerchantAccount** (Avoqado): A row in our DB representing one operating affiliation (Blumon or AngelPay) for a venue
- **AngelPayUserAccount** (new): A row representing the AngelPay user (email + PIN) that has access to N merchants
- **MerchantOption / MerchantSummary** (AngelPay SDK): SDK-side representation of one merchant assigned to the user
- **AngelPay merchant ID** (Int): AngelPay's internal merchant ID (from `MerchantOption.id`). Persisted as a **numeric string** in `MerchantAccount.externalMerchantId`; no dedicated typed column exists in Avoqado's schema.
- **TKR**: Terminal Key Registration token (legacy AngelPay auth method, replaced by `authenticateSimple`)
- **IPEK**: Initial PIN Encryption Key (injected into terminal hardware by SDK during merchant setup)
- **MSI**: Meses Sin Intereses (interest-free installments)
- **EMV**: Europay-Mastercard-Visa chip card standard

---

## 16. References

- AngelPay SDK 1.0.5 Manual: `~/Library/Mobile Documents/com~apple~CloudDocs/Avoqado/AngelPay/dev/sdk_propio/1.0.5/Angel_Pay_Sdk_Manual_EN_v1.0.5.pdf`
- AngelPay v1.2 (legacy app-to-app): `~/Downloads/Manual de Integración App Angel Pay-v1-2.pdf`
- Avoqado Blumon SDK integration: `avoqado-tpv/docs/BLUMON_INTEGRATION_COMPLETE.md`
- Avoqado multi-merchant analysis: `avoqado-server/docs/blumon-tpv/BLUMON_MULTI_MERCHANT_ANALYSIS.md`
- **Test credentials (QA AngelPay user, portal user, terminal user, affiliation, token)** — moved out of this spec for security. Stored at `~/Library/Mobile Documents/com~apple~CloudDocs/Avoqado/AngelPay/dev/credentials.md` (iCloud, local only, never committed). Any credentials previously visible in this spec or in `BuildConfig.ANGELPAY_QA_*` must be considered compromised and rotated by AngelPay.
- Portal QA URL: `https://portal.angelpay-qa.com.mx/` (creds in iCloud credentials file)
- May 13 2026 meeting transcript w/ Diego (AngelPay) — notes link stored alongside iCloud credentials file
- Distributor portal (coming in ~2 weeks per Diego) — URL TBD

---

---

## 17. Blumon / PAX Untouchable Guardrails (release-blocking)

The largest practical risk in this work is **regressing the existing Blumon/PAX flow**, which is in production today and handles real-money transactions. The following rules are **release-blocking**. Any PR that violates one of them must be rejected, regardless of how clean the AngelPay-side work is.

### 17.1. Files that must not be modified beyond compile-safe additive field propagation

- `app/src/sandbox/.../PaymentViewModel.kt`
- `app/src/production/.../PaymentViewModel.kt`
- `app/src/main/.../features/payment/data/MultiMerchantSDKManager.kt`
- `app/src/main/.../features/payment/data/InitializationManager.kt` (sandbox + production variants)
- `app/src/main/.../features/payment/data/BlumonInitializer.kt` (sandbox + production variants)
- `app/src/main/.../features/payment/presentation/MerchantSelectionContent.kt` — shared composable, no behavior change
- `app/src/main/.../features/payment/presentation/PaymentScreen.kt` — Blumon entry-point; only allowed change is to display the new optional `angelpayAffiliation`/`angelpayMerchantName` fields when present, **never** alter `updateSelectedMerchant(...)` or the `startPayment()` ordering
- Blumon auth / token refresh / PAX SDK initialization / EMV / contactless / refund flows — fully off-limits
- PAX native dependencies in `app/libs/` (e.g., Blumon AAR, PAX `NeptuneLiteApi`) — no changes, no upgrade in this work

"Compile-safe additive field propagation" means: when an existing data class (`MerchantAccount`, `MerchantAccountDto`) gains a new nullable field with a default, the Blumon code paths that destructure or `copy()` from these classes must still compile and behave identically. No new requireXxx calls, no new error paths in Blumon code.

### 17.2. Blumon merchant switching semantics — frozen

- `PaymentViewModel.updateSelectedMerchant(merchant)` stays **visual-only** (sets selected merchant in UI state; no SDK call)
- Actual PAX SDK switch stays inside `PaymentViewModel.startPayment()` via `multiMerchantSDKManager.switchMerchant(merchant)` — same call site, same ordering
- We do not introduce an eager-switch behavior for Blumon to mirror AngelPay. The two processors have distinct semantics by design

### 17.3. AngelPay code must be gated behind compile-time flags

- All AngelPay-specific behavior gated by `BuildConfig.SUPPORTED_PROCESSOR == "ANGELPAY"` (or the legacy `BuildConfig.ANGELPAY_SDK_ENABLED` flag where it already exists)
- No AngelPay code may execute inside `sandbox` or `production` flavors
- `nexgo` / `nexgoProd` flavors are the only ones permitted to package the AngelPay AAR

### 17.4. Schema/API additive only

- TPV `MerchantAccountDto` + domain `MerchantAccount`: new fields are nullable with defaults that preserve BLUMON behavior
- Backend `MerchantAccount` schema: only `angelpayAffiliation`, `angelpayMerchantName` added (both optional). No required-field changes that would affect Blumon creation
- `PROVIDER_DEVICE_COMPATIBILITY['BLUMON'] = ['PAX']` — existing flow continues to permit only PAX

### 17.5. APK packaging verification (CI gate)

**Asymmetric rule** — PAX builds are strict; Nexgo builds tolerate compile-time stubs but must not execute any PAX code:

**PAX APKs (`sandbox`, `production`, `productionRelease`)**: MUST NOT package the AngelPay AAR.

```bash
unzip -l app/build/outputs/apk/production/release/app-production-release-unsigned.apk \
  | grep -i angelpay
# Expected: no output
```

**Nexgo APKs (`nexgo`, `nexgoProd`)** — current Gradle (verified `app/build.gradle.kts:379`) includes Blumon stub AARs via `nexgoImplementation(files("libs/blumon_sdk-debug.aar"))` for compile compatibility, so a strict "must not package" rule would break today's build. Instead enforce:

1. **Nexgo MUST NOT execute** PAX/Blumon native initialization, payment, EMV, refund, contactless, or token-refresh code at runtime. Every Blumon entry-point invocation from shared code is guarded by `if (BuildConfig.SUPPORTED_PROCESSOR == "BLUMON") { ... }` or equivalent flavor check.
2. **Nexgo MUST NOT package PAX native runtime artifacts** (PAX `.so`, `NeptuneLiteApi` runtime jars) if they can be excluded without breaking compilation. Add `packagingOptions { exclude "lib/armeabi-v7a/libcyber-*.so", "lib/armeabi/libcyber-*.so", "**/NeptuneLiteApi*" }` to the `nexgo` and `nexgoProd` flavors. Verify ABI in the output APK is `arm64-v8a` only.
3. **If stub Blumon AARs remain packaged** (because they expose interfaces the shared code references at compile time), document this explicitly in `app/build.gradle.kts` with a comment:

   ```kotlin
   // STUB ONLY: Blumon AAR is packaged for Nexgo flavors to satisfy compile-time symbol resolution
   // in shared code. No Blumon entry-point may be invoked at runtime in Nexgo builds —
   // enforce via BuildConfig.SUPPORTED_PROCESSOR == "BLUMON" guards. See spec §17.5.
   "nexgoImplementation"(files("libs/blumon_sdk-debug.aar"))
   ```

4. **Runtime guard test** — a unit test that scans `BlumonInitializer`, `MultiMerchantSDKManager`, and `PaymentViewModel` Hilt graph in `nexgo` flavor at init time and verifies none are eagerly instantiated. (If they are scoped to sandbox/production-only modules, this is automatic.)

5. **Inspection commands** (run on every PR build):

   ```bash
   # Nexgo APK should be arm64-v8a only (PAX is armeabi)
   unzip -l app/build/outputs/apk/nexgo/debug/app-nexgo-debug.apk \
     | grep -E "lib/(armeabi|arm64-v8a)/" | head
   # Expected: only arm64-v8a entries (or empty)

   # Nexgo APK should not contain PAX-specific .so
   unzip -l app/build/outputs/apk/nexgo/debug/app-nexgo-debug.apk \
     | grep -iE "libcyber|libpax|neptune"
   # Expected: no output (or only zero-byte stub if compile-required)
   ```

### 17.6. Mandatory regression tests before merge

Run on the dev machine and attach output to the PR:

```bash
./gradlew testSandboxDebugUnitTest --rerun-tasks
./gradlew testProductionDebugUnitTest --rerun-tasks  # if exists; otherwise testSandboxReleaseUnitTest
./gradlew compileSandboxDebugKotlin
./gradlew compileProductionReleaseKotlin
./gradlew assembleSandboxDebug
./gradlew assembleProductionRelease
./gradlew lint --continue
```

Requirements:
- All existing unit tests pass (currently 220 tests, 0 failures — this number must not regress)
- Both `sandboxDebug` and `productionRelease` APKs build successfully
- Lint passes with no new errors

### 17.7. Manual smoke (PAX A910S) before merge

Even after the regression suite is green, perform a manual smoke on a real PAX A910S in sandbox:

- ✅ Login + venue load
- ✅ Order creation
- ✅ Blumon merchant selection (multi-merchant venue)
- ✅ Blumon card payment with tip (chip or contactless)
- ✅ Blumon split payment
- ✅ Blumon refund flow (admin role)
- ✅ Receipt printing

If any of these regress, the AngelPay change ships only after the regression is rooted out.

### 17.8. Code review checklist (mandatory questions)

Reviewer must answer each in writing on the PR:

1. Does this PR modify any file under §17.1?
2. Does this PR change Blumon merchant switch semantics (§17.2)?
3. Is every new AngelPay code path gated behind `BuildConfig.SUPPORTED_PROCESSOR == "ANGELPAY"` (§17.3)?
4. Are all new DTO/domain fields nullable with safe defaults (§17.4)?
5. Did §17.5 (APK packaging inspection) pass on this PR's build outputs?
6. Did §17.6 (regression test suite) pass with 220 tests, 0 failures?
7. Was §17.7 (PAX A910S smoke) executed? Link to evidence (screenshot/video).

Any "no" / "not done" answer blocks merge.

---

---

## 18. Eng-Review Integration (v2.5 — D2 to D7)

This section documents the concrete code/schema shape for the 6 decisions made during `/plan-eng-review`. Treat as binding alongside §1–§17.

### 18.1. D2 — switchMerchant ↔ payment race protection

Three coordinated changes:

**Sealed state extension** (`AngelPayPaymentState`):

```kotlin
sealed class AngelPayPaymentState {
    object Idle : AngelPayPaymentState()
    // ...existing states...
    data class Switching(val targetMerchantId: Int, val previousMerchantId: Int?) : AngelPayPaymentState()
    data class Charging(val merchantId: Int, val startedAt: Long) : AngelPayPaymentState()
}
```

**Mutex inside `AngelPayMerchantRepository`** (single source of truth for concurrency):

```kotlin
private val operationMutex = Mutex()  // serializes switch + charge intent
private val _inFlightSwitch = MutableStateFlow<Int?>(null)  // observable for UI

suspend fun switchActiveMerchant(merchantId: Int): Result<Unit> = operationMutex.withLock {
    if (paymentStateProvider.isCharging()) {
        return@withLock Result.failure(SwitchBlockedDuringChargeError)
    }
    _inFlightSwitch.value = merchantId
    val result = withTimeoutOrNull(8_000) {
        sdkGateway.switchMerchant(merchantId)
    } ?: Result.failure(SwitchTimeoutError(merchantId))

    result.onSuccess {
        _activeAngelPayMerchantId.value = merchantId
        merchantCacheDao.markActive(merchantId)
    }
    _inFlightSwitch.value = null
    result
}
```

**Payment-time guard** (`AngelPayPaymentViewModel.startPayment`):

```kotlin
suspend fun startPayment(...) {
    val targetMerchantId = _currentMerchant.value?.requireAngelpayMerchantId() ?: return
    val activeId = angelPayMerchantRepository.activeAngelPayMerchantId.value
    if (activeId != targetMerchantId) {
        // Wait for in-flight switch up to 8s, then re-check
        withTimeoutOrNull(8_000) {
            angelPayMerchantRepository.activeAngelPayMerchantId
                .first { it == targetMerchantId }
        } ?: run {
            _state.value = AngelPayPaymentState.Error("Cambio de merchant no se completó. Reintenta.")
            return
        }
    }
    _state.value = AngelPayPaymentState.Charging(targetMerchantId, System.currentTimeMillis())
    // ...proceed to createPaymentIntent...
}
```

**Multi-tap behavior:** if operator taps merchant C while switch to B is in-flight, the ViewModel cancels the pending B switch (via `coroutineScope.cancel()`) and starts a fresh C switch. `_currentMerchant` updates immediately for visual feedback; `_activeAngelPayMerchantId` only updates on success.

### 18.2. D3 — AngelPayUserAccount lifecycle states

**Schema addition** (`AngelPayUserAccount`):

```prisma
enum AngelPayAccountStatus {
  PENDING_PIN              // Avoqado admin created account but PIN not yet entered (waiting on AngelPay)
  ACTIVE                   // Fully operational
  PIN_ROTATION_REQUIRED    // AngelPay flagged: rotate PIN within X days
  SUSPENDED                // AngelPay or Avoqado disabled account; TPV refuses to operate
  DELETED                  // Soft-deleted; retained for audit, TPV ignores
}

model AngelPayUserAccount {
  // ...existing fields...
  status  AngelPayAccountStatus  @default(PENDING_PIN)
  statusChangedAt  DateTime?
  statusChangedBy  String?  // staff CUID
  statusReason     String?
}
```

**Service rules** (`AngelPayUserAccountService`):
- `create()` → status `PENDING_PIN` (PIN field still nullable until set)
- `setPin()` → transitions to `ACTIVE` (validates PIN is 6 numeric digits, encrypts)
- `markRotationRequired(reason)` → `PIN_ROTATION_REQUIRED` (TPV shows yellow banner, payments still work)
- `suspend(reason)` → `SUSPENDED` (TPV stops operating, refunds queue cleared)
- `softDelete()` → `DELETED` (TPV clears local PIN cache)

**Backend gating** (`createMerchantAccount` for AngelPay providers):
- Reject if `angelpayUserAccount.status != ACTIVE` with error `"AngelPay account is in state X; cannot create merchant accounts until ACTIVE"`

**TPV gating** (`AngelPayAuthRepository`):
- If `terminalConfig.angelpayAuth.status != ACTIVE`, emit `AngelPayAuthState.AccountSuspended(status, reason)` banner and refuse `authenticateSimple` call

**Dashboard surfaces** (`Superadmin/AngelPayAccount.tsx`):
- Status chip on detail page
- Transition controls: "Mark PIN Rotation Required", "Suspend", "Re-activate"
- Audit log of status transitions

### 18.3. D4 — Dual-source credential transition

**TPV resolution order** (inside `AngelPayAuthRepository.resolveCredentials()`):

```kotlin
private fun resolveCredentials(): Result<AngelPayCreds> {
    // 1. Prefer backend-sourced
    val backendCreds = terminalConfigRepo.getCachedConfig()?.angelpayAuth?.let {
        AngelPayCreds(it.email, it.pin, it.environment, source = "backend")
    }
    if (backendCreds != null) return Result.success(backendCreds)

    // 2. Fallback to BuildConfig (transition only, 30-day grace window)
    val legacyEmail = BuildConfig.ANGELPAY_QA_EMAIL.takeIf { it.isNotBlank() }
    val legacyPassword = BuildConfig.ANGELPAY_QA_PASSWORD.takeIf { it.isNotBlank() }
    if (legacyEmail != null && legacyPassword != null) {
        crashlytics.log("AngelPay: using deprecated BuildConfig creds (delete in v3)")
        crashlytics.recordException(DeprecatedBuildConfigCredsWarning)
        return Result.success(AngelPayCreds(legacyEmail, legacyPassword, BuildConfig.ANGELPAY_ENV, source = "buildconfig-fallback"))
    }

    // 3. Both missing — hard error
    return Result.failure(MissingAngelPayCredsError)
}
```

**Backend behavior** (`terminal.tpv.controller.ts`):
- During the 30-day grace period (config flag `angelpayDualSourceEnabled = true`), `angelpayAuth` is included in response if `AngelPayUserAccount` exists for the venue
- After grace period (config flag flipped to `false`), the dual-source path is removed and TPV v3 ships without the BuildConfig fallback

**Test coverage** (3 unit tests, see §10.1 expanded):
- Backend cred present → uses backend, no warning
- Backend cred null + BuildConfig present → fallback + warning recorded
- Both null → `MissingAngelPayCredsError` surfaced to UI

### 18.4. D5 — Intersection-based mismatch handling

`validateAgainstConfig()` rewritten:

```kotlin
suspend fun validateAgainstConfig(config: TerminalConfigDto): ValidationResult {
    val sdkMerchantIds = sdkGateway.getUserMerchants().getOrNull()?.map { it.id }?.toSet().orEmpty()
    val avoqadoMerchantIds = config.merchants
        .filter { it.providerCode == "ANGELPAY" && it.isActive }
        .mapNotNull { it.externalMerchantId?.toIntOrNull() }
        .toSet()

    val intersection = sdkMerchantIds intersect avoqadoMerchantIds
    val onlyInSdk = sdkMerchantIds - avoqadoMerchantIds
    val onlyInAvoqado = avoqadoMerchantIds - sdkMerchantIds

    return when {
        intersection.isEmpty() -> ValidationResult.HardBlock(
            message = "Sin merchants válidos compartidos entre AngelPay y Avoqado. Contacta soporte."
        )
        onlyInSdk.isNotEmpty() || onlyInAvoqado.isNotEmpty() -> ValidationResult.PartialOperable(
            operableIds = intersection,
            warning = AngelPayMismatchWarning(onlyInSdk, onlyInAvoqado),
        )
        else -> ValidationResult.AllClear
    }.also { result ->
        if (result is ValidationResult.PartialOperable) {
            crashlytics.recordException(AngelPayConfigMismatchInfo(onlyInSdk, onlyInAvoqado))
            reportToBackend("config-mismatch", onlyInSdk, onlyInAvoqado)
        }
    }
}
```

**Merchant selector behavior:**
- `AngelPayMerchantSwitcherSheet` and `MerchantSelectionContent` (AngelPay path) only show merchants in the intersection
- Yellow banner above the selector lists the mismatch counts: "1 merchant solo en AngelPay (no facturable). 0 merchants solo en Avoqado."

**Hard-block path** triggers only when intersection is empty — operator can't proceed with AngelPay at all and is told to use cash or contact support.

### 18.5. D6 — Cache refresh schedule

`AngelPayMerchantRepository` adds two refresh triggers:

```kotlin
@Singleton
class AngelPayMerchantRepository @Inject constructor(...) {
    private var periodicRefreshJob: Job? = null

    fun startPeriodicRefresh(scope: CoroutineScope) {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = scope.launch {
            while (isActive) {
                delay(15.minutes)
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(STARTED)) {
                    fetchAndCacheMerchants()
                }
            }
        }
    }

    fun stopPeriodicRefresh() { periodicRefreshJob?.cancel() }

    /** Called by ViewModel right before opening the selector UI. */
    suspend fun refreshBeforeSelector(): Result<List<MerchantSummary>> =
        fetchAndCacheMerchants()
}
```

**Wiring:**
- `startPeriodicRefresh` invoked from `AngelPayAuthRepository.ensureAuthenticated()` on first success
- `stopPeriodicRefresh` invoked on logout / `AccountSuspended` transition
- `AngelPayMerchantSwitcherSheet` calls `refreshBeforeSelector()` in `LaunchedEffect(Unit)` when opened
- Process lifecycle observer pauses periodic refresh while app is background (battery)

### 18.6. D7 — Traceability table (test inventory ↔ requirements)

Every D2–D6 decision is covered by enumerated tests in §10.1. The traceability:

| Decision | Test file | Test count |
|---|---|---|
| D2 race | `AngelPayMerchantRepositoryTest.kt` (switch reject during charge, switch timeout, multi-tap cancel) | 5 |
| D2 race | `AngelPayPaymentViewModelTest.kt` (payment waits for in-flight switch, gives up after 8s) | 2 |
| D3 lifecycle | `AngelPayUserAccountServiceTest.ts` (status transitions + invalid state rejections) | 6 |
| D3 lifecycle | `MerchantAccountServiceTest.ts` (createMerchantAccount rejected when account not ACTIVE) | 2 |
| D3 lifecycle | `AngelPayAuthRepositoryTest.kt` (refuses auth when account status != ACTIVE) | 1 |
| D4 dual-source | `AngelPayCredentialResolverTest.kt` (backend wins, BuildConfig fallback + warning, both null = error) | 3 |
| D5 intersection | `AngelPayConfigValidationTest.kt` (all-clear, partial-operable, hard-block, empty SDK list, empty Avoqado list) | 5 |
| D6 cache refresh | `AngelPayMerchantRepositoryTest.kt` (periodic timer fires, paused background, refreshBeforeSelector) | 3 |
| §4.5b PIN handling | `AngelPayPinHandlingTest.kt` (no Room write, OkHttp redaction, Crashlytics asserter) | 3 |
| §17.5 nexgo packaging | `NexgoFlavorHiltGraphTest.kt` (Blumon classes not eagerly instantiated) | 2 |
| §6.4 PAX Hilt safety | `PaxStartupSmokeTest.kt` (sandboxDebug Application.onCreate does not load AngelPay symbols) | 2 |

Subtotal **34 new unit tests** + ~12 existing infrastructure tests (auth/state/error mapping) = **46 total**. Matches §10.1 promise.

---

**End of spec.** Awaiting `writing-plans` skill to translate into implementation plan.

---

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 0 | — | not run; scope locked via brainstorming + 4 audit rounds |
| Codex Review | `/codex review` | Independent 2nd opinion | 4 | clean (4th pass) | external LLM gave directional approval; remaining tweaks (D2–D7) integrated this pass |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 1 | clean (issues_open → integrated) | 6 decisions made (D2 race, D3 lifecycle, D4 dual-source, D5 mismatch, D6 cache, D7 test expansion) |
| Design Review | `/plan-design-review` | UI/UX gaps | 0 | — | minor dashboard UX changes; defer to implementation |
| DX Review | `/plan-devex-review` | Developer experience gaps | 0 | — | internal feature, not developer-facing |

- **CROSS-MODEL:** external auditor (4 rounds) and eng-review agree on §17 guardrails, AngelPay-only merchant switch hook, externalMerchantId mapping. Eng-review added correctness gates (D2/D5) that auditor didn't surface.
- **UNRESOLVED:** 0 unresolved decisions
- **VERDICT:** ENG REVIEW CLEARED — ready to invoke `writing-plans` skill

**Completion summary (eng review pass):**
- Step 0 — Scope Challenge: scope accepted as-is (Opción A, 12-16 days MVP)
- Architecture Review: 3 issues found (D2, D3, D4), all integrated
- Code Quality Review: 2 issues found (D5, D6), all integrated
- Test Review: coverage diagram produced, 46-test inventory written into §10.1 with traceability table in §18.6
- Performance Review: 0 issues found
- NOT in scope: written in §2.2
- What already exists: written in §1.1 + §1.1b
- TODOS.md updates: 0 (all findings integrated into spec itself)
- Failure modes: 0 critical gaps remaining (D5 covers config mismatch, §7.3 catalog covers SDK/network failures)
- Outside voice: 4 prior rounds with external LLM already executed; not re-run for this eng-review pass
- Lake Score: 6/6 recommendations chose complete option

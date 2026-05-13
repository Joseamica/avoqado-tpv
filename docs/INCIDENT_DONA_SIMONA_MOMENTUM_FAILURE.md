# Incident review — Doña Simona payment failure (2026-05-12)

> **Audience**: external LLM reviewing this investigation
> **Status**: hypothesis confirmed by SDK bytecode; waiting on Blumon (Edgardo) to confirm token TTL; telemetry deployed in working tree
> **Date**: 2026-05-12

## TL;DR

Customer reported that a payment failed at Doña Simona (terminal `AVQD-2840744151`, serial `2841548417`) approximately 5 days after the v1.14.0 release. Rebooting the TPV fixed it. We need to decide whether to ship a preventive fix in v1.14.1 and how aggressive the re-init threshold should be.

After investigation (Crashlytics + Render logs + decompiling the Blumon SDK AAR), we have **strong evidence** that the SDK keeps the OAuth token in a static singleton with **no TTL tracking and no refresh logic**, and our `InitializationManager._isInitialized` flag is in-memory only — so a long-lived process never re-fetches the token. We have deployed read-only telemetry (no behavior change) to capture proof in the next incident. We are also waiting on Edgardo (Blumon contact) to confirm the server-side token TTL before choosing the fix threshold.

We need an external review of the diagnosis, the telemetry design, and the proposed preventive fix.

## Repo / environment context

- **App**: avoqado-tpv — Android POS for PAX A910S payment terminals
- **Stack**: Kotlin + Jetpack Compose, Hilt DI, Room, Blumon PAX SDK (AAR)
- **Build variant under investigation**: `productionRelease` v1.14.0 (versionCode 63)
- **Architecture rule (CLAUDE.md)**: 8 features share the same payment state machine, so we MUST sync changes between `sandbox/` and `production/` source sets and avoid touching the payment flow unless we have high confidence.

## Customer report (verbatim summary)

- Terminal: Doña Simona, serial `2841548417`, terminal ID `AVQD-2840744151`
- Operator tried a $0.10 test payment that did not go through ("lo dejó colgando")
- After rebooting the TPV, payments started working again
- The customer's words: "el time span fue de muchos días después" — the TPV had been running ~5 days since v1.14.0 install

## Evidence collected (hard data)

### 1. Crashlytics — the issue is new in v1.14.0

**Issue ID**: `12d6e3c4484dc3223c3a30722980cc16`
- Title: `com.jaac.avoqado_tpv.features.payment.presentation.PaymentViewModel$collectPinDialogFlows$3$1.emit`
- Subtitle (one of 3 variants): `[null] ❌ [PIN Result] PIN incorrect or error: null`
- `firstSeenVersion: 1.14.0`, `lastSeenVersion: 1.14.0`
- 305 events / 14 impacted users / 46 sessions in 7 days

**Same payment attempt produced 3 cascading NON_FATAL logs** (same `payment_attempt_id: ac7782e0-fa82-4115-b3a4-06751f75a2ae`):
1. `processContactlessPayment` → `StartCtlssTransFailure$CtlssUseContactFailure` (contactless said "use chip" — normal)
2. `performOnlineAuthorization` → `SaleIccFailure$MomentumFailure@a0af4d1` (chip transaction failed at SDK layer)
3. `continuePaymentFlow` → `[PHASE 4] Online authorization FAILED`

**Crashlytics customKeys for the sample event**:
- `app_environment: PROD`, `app_version: 1.14.0`, `app_build_variant: release/production`
- `blumon_sdk_status: READY` ← the SDK thought it was ready
- `payment_emv_message: EMV processed successfully` ← EMV layer worked fine
- `payment_emv_stage: START_EMV_TRANS_OK`
- `payment_processor: BLUMON`, `payment_method: CARD`
- `payment_amount: 0.10`
- `network_internet: true`, `network_server: true`, `network_slow: false`, `network_latency_ms: 901`
- `venue_id: cmn3acoxt000mn227k1vdgj95`, `payment_merchant_id: cmn3a6xr8000kn227eg8zeh41`
- `terminal_id: AVQD-2840744151`, `app_terminal_serial: 2841548417`

**Reference issue NOT in earlier versions**: `topIssues` query for v1.13.5 (60) and v1.13.7 (62) shows network/socket errors but **no PaymentViewModel issues**. Could be ProGuard fingerprint changes between releases, but `PaymentViewModel.kt` was **not modified** in v1.14.0 (verified via git log). So this is either (a) a new bug introduced by `InitializationManager` changes in v1.14.0, or (b) a pre-existing bug that Crashlytics started grouping under a new issue ID after the v1.14.0 release. We have NOT ruled out either yet.

### 2. Render backend logs — the payment never reached the backend

Queried `avoqado-server` Render logs filtered by `app_terminal_serial: 2841548417` between `2026-05-12T19:30:00Z` and `2026-05-12T20:30:00Z`. Full endpoint timeline:

```
19:35:25–19:36:49  Normal navigation GETs (shifts, sales-goals, products, verifications)
19:36:41           Crashlytics event time of the $0.10 failed payment
                   ⛔ ZERO POST to /payments/record
                   ⛔ ZERO POST to /orders/.../payment
                   ⛔ ZERO POST to any payment endpoint
19:36:41–19:48:22  11 minutes of silence (no requests from this terminal)
19:48:22           GET /terminals/AVQD-2840744151/config (no statusCode = mid-flight)
                   GET /activation-status?environment=PROD
                   GET /auth/permissions × 3
                   = TPV REBOOT confirmed by request pattern
19:48:35+          Terminal operational again, normal GET pattern resumes
```

**Conclusion**: the payment **never reached the Avoqado backend**. The SDK Blumon failure happened locally, before any HTTPS call to `api.avoqado.io` was even attempted for payment recording. This rules out:
- Network issues between TPV and Avoqado backend
- Avoqado backend rejecting the payment
- Race conditions in `RecordPaymentUseCase`

The failure is **between TPV's `SaleIccUseCase.run()` call and the Blumon server**, contained inside the SDK.

### 3. Customer not in Blumon portal

Customer confirmed the $0.10 payment does **not** appear in the Blumon TPV portal (`element.blumonpay.net/transacciones`).

Per the rule in `CLAUDE.md`:
> "If the SDK reports a decline but no matching transaction appears in the Blumon TPV portal, treat it as high probability TPV/app integration bug and inspect SDK initialization, merchant posId, serial, entry mode, EMV tags, idempotency, and backend recording."

So the failure happened **before** the SDK was able to send the authorization request to Momentum/Blumon's backend, or Blumon's backend rejected at the gateway level (auth failure) before logging the transaction.

### 4. SDK AAR bytecode (this is the load-bearing evidence)

**AAR file**: `app/libs/lib_services-1.2.0.0-PROD.aar`
- SHA-256: `756f7dabeff8dbb16f12cc277e4e213d8ccf02543bfc662ee34e62392210c594`
- Size: 4,777,667 bytes (4.6 MB), dated 2025-03-18
- Confirmed byte-identical to what Blumon distributes via Google Drive (same SHA-256)

#### Finding A: `GlobalResources.tokenAuth` is a static `String` with no TTL

Decompiled `com.example.clean_lib_services.shared_tools.api.GlobalResources.class`:

```java
public final class GlobalResources {
  public static final GlobalResources INSTANCE;
  private static String tokenAuth;  // ← no timestamp, no expiry tracking
  public final String getTokenAuth();
  public final void setTokenAuth(String);
  static {}
}
```

The token is held in a singleton, set once by `InitializerUseCase` (via `GetTokenUseCase`), never invalidated.

#### Finding B: HTTP interceptor adds `Bearer <token>` without authenticator

Decompiled `com.example.clean_lib_services.shared_tools.api.utils.HttpClientKt.class`:

- Builds an `OkHttpClient` with `addInterceptor` (NOT `authenticator`)
- The interceptor reads `GlobalResources.INSTANCE.getTokenAuth()` and adds `Authorization: Bearer <token>` to every outbound request
- No retry on 401, no refresh-on-expire logic, no `Authenticator` to handle stale tokens

Verified strings in bytecode: `"Authorization"`, `"Bearer"`, `"GlobalResources"`, `"getTokenAuth"`. No `"refresh"`, `"expires"`, `"expiry"`, `"ttl"`, `"authenticator"`.

#### Finding C: `GetTokenApiResponse` discards `expires_in`

Decompiled `com.example.clean_lib_services.shared.tokener.data.remote.model.GetTokenApiResponse.class`:

```java
public final class GetTokenApiResponse {
  // only one field — accessToken
  private final String accessToken;
  public final String getAccessToken();
}
```

The `oauth/token` endpoint (verified at `TokenerApiService` interface) is standard OAuth, which normally returns `{access_token, expires_in, token_type, ...}` — but the SDK's Gson model **only deserializes `accessToken`**. The `expires_in` field, if returned by the server, is silently dropped.

#### What this means

The SDK has **no concept of token expiration**. Once `InitializerUseCase` runs successfully:
1. `GlobalResources.tokenAuth = "<accessToken>"` is set
2. All subsequent `SaleIccUseCase` / `SaleCtlsUseCase` / `CancelIccUseCase` calls reuse the same token via the interceptor
3. If the token expires server-side (which is normal for OAuth), the next `SaleIcc` request gets rejected with what the SDK wraps as `SaleIccFailure$MomentumFailure`
4. The SDK does NOT re-fetch the token automatically — the only way to refresh is for the **integrating app (us)** to call `InitializerUseCase` again

This matches the symptom exactly: SDK shows `READY`, payments work for some time, then start failing with generic `MomentumFailure` until the process restarts (which triggers a fresh init).

### 5. Our app's `InitializationManager` keeps the SDK init flag forever

**File**: `app/src/production/.../features/payment/data/InitializationManager.kt`

```kotlin
suspend fun ensureInitialized(defaultMerchantPosId: String? = null): Result<Unit> {
    // ...
    // Fast path: already initialized inside this app process.
    if (_isInitialized.value) {
        Timber.d("✅ [InitializationManager] Already initialized (fast path)")
        return Result.success(Unit)
    }
    // ...
}
```

`_isInitialized` is a `MutableStateFlow<Boolean>` that gets set to `true` after the first successful init and **never gets invalidated while the process is alive**. The class also persists a `lastBlumonInitTimestamp` to `SecureStorage`, but that timestamp is **only consulted when `_isInitialized.value == false`** — i.e., it never actually triggers a re-init while the process lives.

The comment on lines 35-37 says:
> "Per Edgardo (2025-11-05): 'Es recomendable realizar el init solo una vez cada 24 horas o cada que lances la aplicación'"

So Edgardo (Blumon engineer) recommended 24h cycling, but our implementation only enforces "once per app process". On a long-lived PAX terminal that never reboots, this means the SDK init can be days old.

### 6. What v1.14.0 changed (and what it didn't)

Reviewed git log + diff of commit `4c294417` (release v1.14.0):

**Changed in v1.14.0**:
- `InitializationManager.kt` (both sandbox and production): added `defaultMerchantPosId` parameter path that forces full init on app launch with merchant context (to fix the NA_002 "stale disk cache after restart" bug)
- `AppNavigation.kt` + `WelcomeScreen.kt` + `KioskCartScreen.kt`: added `awaitPaxPaymentReady(...)` UI guards that block PaymentScreen entry until SDK is `_isInitialized.value == true`
- `SecureStorage.kt`: added 4 keys for cache fingerprint diagnostics

**NOT changed in v1.14.0**:
- `PaymentViewModel.kt` (both variants) — `processContactlessPayment`, `performOnlineAuthorization`, `continuePaymentFlow` are untouched since previous releases
- The Blumon AAR files (same 2025-03-18 SHA-256)
- The token refresh logic (still none)

So v1.14.0's NA_002 fix addressed "stale disk cache" but did NOT address "long-lived process + token expiration". The two bugs are independent — one happens at app launch with bad cache, the other happens hours/days into a session.

## Hypotheses

### H1 (favored) — Token TTL exceeded, in-memory flag prevents re-init

1. App starts, `InitializerUseCase` runs, token T0 obtained, stored in `GlobalResources.tokenAuth`, `_isInitialized = true`
2. T0 has some server-side TTL (1h? 24h? 7d? — unknown)
3. Operator does cobros for N hours, all successful
4. T0 expires server-side
5. Next `SaleIcc` sends `Bearer T0` → server rejects → SDK wraps as `MomentumFailure`
6. `_isInitialized.value` is still `true` → `ensureInitialized()` short-circuits → no new token fetched
7. Symptom persists until process is killed (TPV reboot) → flag resets → next launch fetches fresh token

**Strongly supported by**:
- SDK bytecode (no refresh logic at all)
- Customer behavior (reboot fixes it)
- Edgardo's recommendation of "init every 24h" (implies it matters)
- Timeline (~5 days post-v1.14.0, plenty of time to exceed any reasonable TTL)

**Open question**: what is the actual TTL on the server side? Need Edgardo confirmation.

### H2 (less likely) — Fallback merchant with wrong credentials

CHANGELOG mentions a separate bug where the TPV could end up using hardcoded fallback merchant accounts when terminal config fetch fails on boot (e.g., SIM→WiFi switch during startup). If that happened on Simona, all subsequent SaleIccs would use the wrong posId/credentials and produce `MomentumFailure`.

**Less likely because**:
- Reboot also fixes this, but the customer would notice if it consistently happened on every fresh boot
- Crashlytics customKey shows `payment_merchant_id: cmn3a6xr8000kn227eg8zeh41` which appears to be a real merchant ID, not a fallback "default"

We have telemetry to confirm or rule this out (see "Telemetry deployed" below).

### H3 (low probability) — Server-side / processor / card-specific failure

The transaction NOT appearing in Blumon's portal weakens this, but it's possible the SDK's auth-layer rejection at Blumon's gateway prevents the transaction from being logged. Less likely given:
- $0.10 is a test amount (unlikely to trip processor risk rules)
- Reboot fixes it (which wouldn't change card/processor state)

## Telemetry deployed (working tree, not committed)

To distinguish H1 vs H2 vs H3 without guessing, we added **read-only** instrumentation that captures state at the moment of a `MomentumFailure`. NO behavior changes.

### Files modified

1. **`app/src/main/java/.../core/observability/CrashlyticsContext.kt`** — added new function `recordSdkStalenessSnapshot(...)` that sets Crashlytics custom keys (prefix `sdk_state_*` to avoid colliding with existing keys).

2. **`app/src/production/.../PaymentViewModel.kt`** — added 2 invocations of `recordSdkStalenessSnapshot(...)`:
   - Inside `when { saleFailure != null -> { ... } }` block of `performOnlineAuthorization` (line ~2987, right after `Timber.e("❌ [$saleType] Failed: $failure")`)
   - Inside the outer `catch (e: Exception)` block of `performOnlineAuthorization` (line ~3135)

3. **`app/src/sandbox/.../PaymentViewModel.kt`** — identical changes to keep sandbox/production in sync (project rule).

### Custom keys captured

- `sdk_state_init_flag_in_memory` (Boolean) — `_isInitialized.value` at failure time
- `sdk_state_init_age_hours` (Long) — hours since `getLastBlumonInitTimestamp()`, `-1` if never initialized
- `sdk_state_init_pos_id` (String) — `getLastBlumonInitPosId()` from disk
- `sdk_state_merchant_is_fallback` (Boolean) — `merchantRepository.isUsingFallback()`
- `sdk_state_current_merchant_pos_id` (String) — `_currentMerchant.value?.posId`
- `sdk_state_process_uptime_minutes` (Long) — `SystemClock.elapsedRealtime() / 60_000`
- `sdk_state_blumon_env` (String) — `BuildConfig.BLUMON_ENV` (`PROD` or `SAND`)
- `sdk_state_failure_class` (String) — `failure.javaClass.simpleName` or `EXCEPTION:<className>` for the catch path

### Safety properties

- The snapshot call is inside `runCatching { }` so a Crashlytics SDK failure cannot affect the payment flow
- The snapshot runs **after** `Timber.e(...)`, before any `return` or `_state` write — does not change control flow
- Does NOT call `forceReinitialize`, does NOT retry, does NOT change `userFriendlyError`, does NOT touch UI
- The data is queryable in the Firebase Console "Keys" tab on every issue

### Validation

- `./gradlew compileSandboxDebugKotlin compileProductionDebugKotlin` → BUILD SUCCESSFUL
- `./gradlew testSandboxDebugUnitTest --tests "*PaymentViewModelTest*" --tests "*InitializationManagerTest*"` → BUILD SUCCESSFUL (no test regression)

## Decision tree once telemetry data arrives

| Telemetry signature | Diagnosis | Recommended fix |
|---|---|---|
| `init_age_hours > 12` AND `init_flag_in_memory = true` AND `merchant_is_fallback = false` | **H1 confirmed** — long-running process, token expired, flag never invalidated | Add timestamp check inside `ensureInitialized()` fast path; force re-init when `now - lastInit > N hours` |
| `merchant_is_fallback = true` (regardless of age) | **H2 confirmed** — wrong merchant credentials | Fix the merchant resolution path; not a timestamp issue |
| `init_age_hours < 1` AND `process_uptime_minutes < 60` AND fresh state | **H1/H2 both ruled out** — likely H3 (processor/network/card-specific) | Open ticket with Blumon directly; no client-side fix |

## Proposed fix (waiting on Edgardo's TTL answer)

If H1 is confirmed, the minimal fix is inside `InitializationManager.ensureInitialized()`:

```kotlin
// Fast path: already initialized inside this app process — BUT also check timestamp age.
if (_isInitialized.value) {
    val lastInit = secureStorage.getLastBlumonInitTimestamp() ?: 0L
    val ageMs = System.currentTimeMillis() - lastInit
    if (ageMs < TOKEN_REFRESH_THRESHOLD_MS) {
        Timber.d("✅ [InitializationManager] Already initialized, age within threshold (fast path)")
        return Result.success(Unit)
    }
    Timber.w("⚠️ [InitializationManager] In-memory flag stale (${ageMs}ms > threshold) — forcing re-init")
    _isInitialized.value = false
    // fall through to the normal re-init flow below
}
```

**Threshold selection** based on Edgardo's answer:
- Server TTL ≤ 1h → threshold = 30 min
- Server TTL 24h → threshold = 12-18h (current best guess)
- Server TTL ≥ 7d → fix unnecessary, look elsewhere

### Risk assessment for the proposed fix

- **Scope**: 6 added lines inside `InitializationManager.ensureInitialized()`. Zero changes to `PaymentViewModel`, zero changes to any `SaleIcc` / `SaleCtls` / `processContactlessPayment` / `performOnlineAuthorization` code paths. Zero new dependencies, zero schema changes.
- **Worst case if threshold is too aggressive** (e.g., 12h when server is 24h): every `12h` of process uptime, the first cobro takes 2-3s extra to re-init. Cobros still work. No data loss. No double-charge possible (re-init is synchronous and the cobro waits for it).
- **Worst case if threshold is too lax** (e.g., 12h when server is 6h): the fix does not fully prevent the bug — but it does not make the bug worse either, and we still have the manual reboot workaround.
- **The fix cannot cause** a stuck payment, a double payment, an orphan order, or an unauthorized charge — the re-init path is the exact same code that runs on every app launch, which is known to work.

## Pending Edgardo questions (final version, kept minimal)

The contact `ebarajas@blumon...` distributed the AAR (verified by SHA-256 match with iCloud-synced files). He's the same Blumon engineer who advised the "init every 24h" rule in 2025-11-05.

3 binary questions:

1. ¿Cuántas horas dura el token de `oauth/token` antes de expirar en el servidor? (1h / 24h / 7d / 30d / nunca)
2. Cuando el token expira y llamo `SaleIccUseCase`, ¿qué falla recibo? ¿`MomentumFailure` genérico o hay un tipo específico de "token expirado"?
3. ¿La app debe re-correr `InitializerUseCase` cada X horas para refrescar el token, o el SDK lo hace solo?

## What we want the external LLM to review

1. **Is the bytecode evidence solid?** We claim `GlobalResources.tokenAuth` is a static `String` with no refresh logic and no authenticator. Verify the inference is correct: does the absence of `Authenticator` + presence of plain `addInterceptor` actually prove there's no refresh? Or are there other code paths we missed (e.g., `Tokener.refresh()` called from somewhere we haven't decompiled)?
2. **Is the timeline coherent?** We claim the $0.10 failure at 19:36 UTC followed by 11 min of silence followed by a reboot pattern at 19:48 UTC is conclusive that the SDK failed locally. Anything weaker about that conclusion?
3. **Is the proposed fix safe?** Adding a timestamp check inside `ensureInitialized()` fast path. Is there any scenario where forcing a re-init mid-session (when `_isInitialized` was `true` before the timestamp check) could leave the SDK or our app in a worse state than before?
4. **Is the telemetry sufficient?** The 8 custom keys we capture — are we missing any signal that would distinguish H1 from H2 from H3? Anything we should add before shipping v1.14.1?
5. **Are there alternative hypotheses we haven't considered?** Particularly: anything about EMV state machine, kernel state, ARPC handling, or merchant switching that could explain the symptom without invoking token expiration?

## File pointers for the reviewer

- `app/src/production/java/com/jaac/avoqado_tpv/features/payment/data/InitializationManager.kt` (whole file, focus on `ensureInitialized` line 111-186)
- `app/src/production/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentViewModel.kt` lines 2745-3142 (`performOnlineAuthorization` + handling of `SaleIccFailure`)
- `app/src/main/java/com/jaac/avoqado_tpv/core/observability/CrashlyticsContext.kt` — new `recordSdkStalenessSnapshot(...)` function (telemetry)
- AAR for decompilation: `app/libs/lib_services-1.2.0.0-PROD.aar` (SHA-256 `756f7dabeff8dbb16f12cc277e4e213d8ccf02543bfc662ee34e62392210c594`)
- CHANGELOG entry for the telemetry: `CHANGELOG.md` under `[Unreleased]` → `[Observability][Blumon SDK]`

## Acknowledgment of uncertainty

We are NOT 100% certain that token TTL is the root cause. We are ~80-90% confident based on:
- Bytecode evidence (strong, deterministic)
- Reboot fix correlation (strong, multiple customer reports historically)
- Timeline (medium — 5 days is enough for any common OAuth TTL)
- Absence of payment record in backend (strong — rules out backend-side issues)

The remaining 10-20% uncertainty is why we shipped telemetry before any behavior change. The fix should only land after we have one concrete Crashlytics event confirming the predicted `sdk_state_*` signature.

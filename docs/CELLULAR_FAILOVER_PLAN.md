# Payment Resilience Plan (v1.11.0)

**Created**: 2026-04-09
**Status**: AUDITED — Joint position agreed between Claude Code + Codex
**Target release**: v1.11.0 (Phase 2 + Phase 3), spike separate (Phase 1)
**Estimated effort**: 2 days (Phase 2 + 3), spike TBD
**Risk level**: Low (Phase 2 + 3), Unknown (Phase 1 spike)

---

## Joint Technical Position (Claude Code + Codex)

After technical debate grounded in the avoqado-tpv codebase:

| Phase | Status | Rationale |
|---|---|---|
| **Phase 2: Pre-flight check** | **APPROVED for implementation** | Low risk. Signal already exists in codebase. Guards card payments before entering Blumon flow. |
| **Phase 3: Short payment timeout** | **APPROVED for implementation** | Low risk. Only affects our backend client, not Blumon SDK. Reduces post-authorization dead time. |
| **Phase 1: Cellular failover** | **SPIKE ONLY — not implementation** | Viable only as OS-level WiFi toggle (`WifiManager.setWifiEnabled`), not `bindProcessToNetwork`. Must prove on real PAX hardware first. |

### Why Phase 1 is a spike, not a feature

The Blumon SDK (`SaleCtlsUseCase`, `SaleIccUseCase`, `InitializerUseCase`) uses its own internal OkHttp/Retrofit clients (`tokenServer.retrofit`, `coreServer.retrofit` in `BlumonAuthManager.kt:334`). The app's `NetworkModule.kt:102` OkHttpClient does NOT control SDK network calls.

The only approach that would affect Blumon is disabling WiFi at the OS level, forcing Android to route all traffic through cellular. This is architecturally different from `bindProcessToNetwork()` (which hacks process-level routing and would break Socket.IO, heartbeat, token refresh, merchant switching).

**But it requires proof on real PAX hardware because:**
- `WifiManager.setWifiEnabled(false)` is deprecated on Android 10+
- PAX runs custom Android — may or may not honor the API
- Handoff timing must be fast enough to improve UX, not trade one timeout for another

---

## Phase 2: Pre-flight Connectivity Check (APPROVED)

### Priority: #1 — Implement first

### Problem

Today `MerchantSelectionContent.kt:245` enables the "Tarjeta" button without checking network state. The merchant taps "Tarjeta", waits up to 60 seconds (PreTrans + DetectCard + SaleCtls timeout), then sees "Error de conexión". Predictable failure, terrible UX.

### Solution

Check `ConnectionStateManager.isFullyConnected()` before entering the Blumon payment flow. If no internet, show dialog offering cash as alternative.

### Existing signal in codebase (no new infrastructure needed)

```
ConnectionViewModel.kt:311    → measures latency every 30s
ConnectionStateManager.kt     → exposes isFullyConnected(), isSlowConnection
MerchantSelectionContent.kt:245 → enables "Tarjeta" without checking network
PaymentViewModel.kt:1998      → startPayment() has no connectivity guard
```

### Proposed Flow

```
User taps "Tarjeta"
  → PaymentViewModel.startPayment()
    → Check: connectionStateManager.isFullyConnected()?
      YES → proceed normally (existing flow unchanged)
      NO  → _state.value = PaymentState.Error(
              message = "Sin conexión a internet.\n\n¿Cobrar en efectivo?",
              canRetry = true,
              showCashFallback = true  // NEW flag
            )
            → UI shows dialog with two buttons:
              [Cobrar en Efectivo] → processCashPayment()
              [Reintentar]        → heartbeat check → if OK, startPayment() again
              (optional [Cancelar] based on existing error UX)
```

### Files to Modify

| File | Change | Risk |
|---|---|---|
| `PaymentState.kt` | Add `showCashFallback: Boolean = false` to `Error` state | **None** — additive field with default |
| `sandbox/.../PaymentViewModel.kt` | Add connectivity check at top of `startPayment()`, after guard but before SDK | **Low** — early return before any Blumon interaction |
| `production/.../PaymentViewModel.kt` | Same | **Low** |
| `PaymentScreen.kt` → `PaymentErrorContent` | Extend error UI to show cash fallback button when `showCashFallback = true`. Current error UI (`PaymentScreen.kt:2230`) only supports Reintentar / Abrir Turno / Cancelar — a new "Cobrar en Efectivo" action must be added to `PaymentErrorContent`. | **Low** — UI only, no payment logic |

### What this does NOT touch

- Blumon SDK (no init, no SaleCtls, no SaleIcc)
- Payment recording (no recordFastPayment, no recordOrderPayment)
- Merchant switching (no MultiMerchantSDKManager)
- Offline queue (no PaymentSyncWorker)
- Room database

### UX Details

- "Tarjeta" button stays visible (disabled buttons confuse users)
- Dialog appears ON TAP, not preemptively
- "Cobrar en Efectivo" directly calls `processCashPayment()` with current amount
- "Reintentar" triggers immediate heartbeat, proceeds if connected
- If connection restores while showing error, user taps "Reintentar" and it works

### Tests

| Test | Method | Expected |
|---|---|---|
| No internet + tap "Tarjeta" | Airplane mode | Error with cash fallback option |
| "Cobrar en Efectivo" from dialog | Tap button | Cash payment flow starts |
| "Reintentar" with internet back | Toggle WiFi on, tap Reintentar | Card payment proceeds |
| Good internet + tap "Tarjeta" | Normal | No change in behavior |

---

## Phase 3: Shorter Payment Timeout (APPROVED)

### Priority: #2 — Implement after Phase 2

### Problem

After Blumon successfully authorizes a card payment (~3s), `recordFastPayment` calls our backend to record it. If our backend is slow or unreachable, OkHttp waits up to 30s (the global timeout). The merchant sees 33s total wait even though the charge already went through.

### What this fixes and what it doesn't

| Call | Current timeout | Proposed | Controlled by us? |
|---|---|---|---|
| `recordFastPayment` (our backend) | 30s | **5-10s** | ✅ Yes |
| `recordOrderPayment` (our backend) | 30s | **5-10s** | ✅ Yes |
| `SaleCtls` / `SaleIcc` (Blumon SDK) | Unknown (SDK internal) | Unchanged | ❌ No |
| `InitializerUseCase` (Blumon SDK) | Unknown (SDK internal) | Unchanged | ❌ No |
| Heartbeat (our backend) | 30s | Unchanged | Not payment-critical |

### Solution

Create a qualified `@PaymentClient` OkHttpClient with shorter timeouts, used only by `PaymentApiService`.

### Files to Create

| File | Purpose |
|---|---|
| `core/di/PaymentClient.kt` | Qualifier annotation `@PaymentClient` |

### Files to Modify

| File | Change | Risk |
|---|---|---|
| `core/di/NetworkModule.kt` | Add `@PaymentClient` OkHttpClient provider (5s connect, 10s read) + `@PaymentClient` Retrofit provider | **None** — new providers, doesn't change existing |
| `core/di/PaymentModule.kt` | Change `providePaymentApiService()` to use `@PaymentClient` Retrofit instead of the default | **Low** — only changes which Retrofit instance the service gets |

### What this does NOT touch

- Blumon SDK timeouts (we cannot control them)
- Heartbeat client
- Socket.IO
- Any non-payment API call

### Impact

- Post-authorization failure: merchant waits 8s instead of 33s
- Offline queue activates 25s sooner
- Payment is not lost — already approved by Blumon, queued for sync

---

## Phase 1: Cellular Failover (SPIKE ONLY)

### Status: NOT approved for implementation. Requires PAX hardware proof.

### Approach

Disable WiFi at the OS level via `WifiManager.setWifiEnabled(false)` when WiFi is degraded beyond usability. Android naturally falls back to cellular for ALL traffic including Blumon SDK.

**This is NOT `bindProcessToNetwork()`.** It is the normal OS behavior when WiFi is off — identical to the user manually toggling WiFi in settings.

### Spike Acceptance Criteria (ALL must pass before Phase 1 becomes implementable)

1. [ ] `WifiManager.setWifiEnabled(false)` works on PAX A910S Android 10 custom build
2. [ ] After WiFi disable, cellular connectivity is established within 5 seconds
3. [ ] `initializeBlumonSDK()` succeeds on cellular after WiFi toggle
4. [ ] `switchMerchant()` succeeds on cellular
5. [ ] Chip payment (`SaleIcc`) completes successfully on cellular
6. [ ] Contactless payment (`SaleCtls`) completes successfully on cellular
7. [ ] Refund (`CancelIcc`) completes successfully on cellular
8. [ ] Heartbeat continues on cellular
9. [ ] Socket.IO reconnects on cellular
10. [ ] `recordFastPayment` to backend succeeds on cellular
11. [ ] Re-enabling WiFi restores WiFi as primary network
12. [ ] No payment, refund, init, or merchant switch is disrupted during toggle
13. [ ] Edgardo (Blumon) confirms no SDK issues with WiFi→cellular transition

### Safety Constraints (non-negotiable for eventual implementation)

- **Never toggle WiFi during in-flight payment, refund, SDK init, or merchant switch**
- **Never auto-toggle based on heartbeat latency alone** in first version — require manual confirmation or configurable threshold from dashboard
- **Strong hysteresis**: minimum 60s cooldown between toggles, 3+ consecutive bad readings before trigger
- **Recovery verification**: after re-enabling WiFi, verify connectivity before switching back

### Phase 0 (required): Rollout Controls + Flags

Before writing failover logic, add explicit controls in terminal settings so rollout can be staged safely and remotely disabled.

| Flag | Type | Default | Purpose |
|---|---|---|---|
| `cellularFailoverMode` | enum/string (`OFF`, `MANUAL_TOGGLE`, `AUTO_SHADOW`, `AUTO_ENFORCED`) | `OFF` | Controls rollout stage per terminal |
| `cellularFailoverBadReadingsThreshold` | int | `3` | Consecutive bad readings required before considering failover |
| `cellularFailoverCooldownSeconds` | int | `60` | Minimum time between WiFi toggles |
| `cellularFailoverMinCellHoldSeconds` | int | `120` | Minimum time to stay on cellular before attempting WiFi restore |

### Wiring points for Phase 0

| Layer | File(s) |
|---|---|
| Domain model | `features/payment/domain/model/TpvSettings.kt` |
| Network DTO mapping | `core/data/network/dto/TpvSettingsDto.kt` |
| Local cache | `core/data/local/SecureStorage.kt` |
| Repository sync | `features/payment/data/repository/TpvSettingsRepository.kt` |
| Terminal settings UI (optional for manual mode testing) | `features/settings/presentation/SettingsViewModel.kt`, `features/settings/presentation/SettingsScreen.kt` |

**Important:** `cellularFailoverMode=OFF` must be the hard default in all layers (DTO null fallback, local cache fallback, and UI initial state). This guarantees existing production behavior until explicitly enabled.

### Development Validation Strategy (No Field Dependency)

Phase 1 should be validated in a controlled lab on real PAX hardware before any production rollout.

#### Environment

1. PAX A910S with active SIM (same model used in production).
2. `sandbox` build variant.
3. SuperAdmin slow-network controls enabled for backend latency simulation (`SlowNetworkInterceptor`).
4. ADB logging session focused on payment/network components.

#### Recommended lab controls

1. WiFi toggling (OS-level behavior under test):
   - `adb shell svc wifi disable`
   - `adb shell svc wifi enable`
2. Optional stress on app/backend client:
   - SuperAdmin → Slow Network = 3000ms, 5000ms, 8000ms presets.
3. Observability:
   - Track logs for `ConnectionState`, `PaymentViewModel`, merchant switch, heartbeat, queue activation, and Socket reconnect.
   - Capture Crashlytics custom keys already wired in `ConnectionStateManager` (`network_internet`, `network_server`, `network_slow`, `network_latency_ms`).

#### Lab test matrix (must pass before AUTO modes)

| Scenario | Steps | Pass condition |
|---|---|---|
| Manual WiFi OFF while idle | Disable WiFi, wait for connectivity settle | Heartbeat recovers on cellular, no app crash |
| Manual WiFi OFF before card payment | Disable WiFi, start card payment | `InitializerUseCase`, merchant switch, and sale succeed on cellular |
| Manual WiFi OFF before refund | Disable WiFi, execute refund | `CancelIcc` flow succeeds on cellular |
| WiFi restore after cellular run | Re-enable WiFi | Heartbeat + Socket reconnect on WiFi, no duplicated payment events |
| Backend slowdown after successful authorization | Enable slow network, complete payment | `recordFastPayment` fails fast (Phase 3 timeout), queue activates quickly |
| Repeated toggles (stability) | OFF/ON cycles with cooldown respected | No deadlocks, no stuck payment state, no duplicate recording |

#### Go/No-Go for enabling Phase 1 beyond spike

1. 20 consecutive lab runs with zero crashes in payment/refund/init/switch flows.
2. No duplicated backend recordings (idempotency key still single-write).
3. No stuck `_isPaymentInProgress` lock after network transitions.
4. Edgardo confirmation on Blumon SDK behavior across WiFi→cellular and cellular→WiFi transitions.
5. Stage rollout by mode:
   - `OFF` (default) → `MANUAL_TOGGLE` (internal terminals only) → `AUTO_SHADOW` (observe-only, no toggle) → `AUTO_ENFORCED` (small canary set first).

### Questions for Edgardo (Blumon)

1. Does the SDK work correctly on cellular-only (no WiFi)?
2. Does `AppManager.init()` complete on cellular?
3. Is there store-and-forward / offline authorization in the SDK?
4. What are the SDK's internal HTTP timeouts for SaleCtls/SaleIcc?

---

## Implementation Timeline

| Day | Task |
|---|---|
| 0 | **Phase 0** — Add rollout flags in `TpvSettings` + DTO/cache/repository wiring (default `OFF`) |
| 1 | **Phase 2** — Pre-flight check in `startPayment()` + cash fallback dialog |
| 2 | **Phase 3** — `@PaymentClient` OkHttpClient + wire to `PaymentApiService` |
| 3 | Testing: smoke test all 8 payment flows on PAX device |
| TBD | **Phase 1 spike** — requires PAX with SIM + Edgardo confirmation |

---

## Acceptance Criteria (v1.11.0 release)

- [ ] Card payment blocked with clear dialog when no internet (Phase 2)
- [ ] Cash fallback offered from the dialog (Phase 2)
- [ ] Post-authorization timeout reduced to 5-10s for our backend (Phase 3)
- [ ] Offline queue activates faster on backend timeout (Phase 3)
- [ ] All 8 payment flows pass smoke test on PAX device
- [ ] Full unit test suite passing (327 tests as of v1.10.10; CLAUDE.md references 220 from an older baseline — actual count is authoritative)
- [ ] No changes to Blumon SDK interaction, init, or merchant switching
- [ ] Crashlytics keys from v1.10.10 provide diagnostic data for Phase 1 spike
- [ ] Phase 0 flags exist with `OFF` as default in model/DTO/cache layers
- [ ] Phase 1 remains disabled in production until lab Go/No-Go criteria are met

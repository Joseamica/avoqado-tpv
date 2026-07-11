package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.angelpay.angelpaysdk.models.AuthenticateSimpleResult
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.jaac.avoqado_tpv.core.domain.repository.TerminalConfigRepository
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private const val LOG_TAG = "AngelPayAuth"

/**
 * Orchestrates the full AngelPay auth lifecycle (Task 30 — spec §6.5, §18.4, §18.6).
 *
 * Sits between [AngelPaySdkGateway] (raw SDK primitives), [AngelPayCredentialResolver]
 * (D4 dual-source — backend or BuildConfig fallback), [AngelPayConfigValidator] (D5
 * intersection check), and [AngelPayMerchantRepository] (active merchant state).
 *
 * **Responsibilities:**
 *   - Drive the [AngelPayAuthState] state machine (§18.6)
 *   - Idempotent re-entry — if the SDK is already authenticated, transition to
 *     [AngelPayAuthState.Authenticated] without re-credentialing
 *   - 3x exponential backoff (500 / 1000 / 2000 ms) around `authenticateSimple`
 *   - Surface [AuthenticateSimpleResult.MerchantSelectionRequired] as
 *     [AngelPayAuthState.SelectingMerchant] for the ViewModel to render
 *   - Run [runConfigValidation] post-auth (and post-selection) — emit
 *     [AngelPayAuthState.ConfigMismatchBanner] on PartialOperable,
 *     [AngelPayAuthState.AuthError] on HardBlock
 *   - Recover from mid-payment [AngelPayAuthExpiredError] via [handleAuthExpiry]
 *   - Fire-and-forget POST to backend `/tpv/angelpay/report-validation`
 *     (Task 14) for observability — wrapped in `runCatching` so transport
 *     failure can't crash the auth flow
 *
 * **Threading:** all suspending entry points may be called from any dispatcher;
 * the underlying SDK calls + repository writes are short-lived. The state flow
 * is updated synchronously — collectors see consistent transitions.
 *
 * **Guardrails:** only constructed on AngelPay flavors. PAX flavors don't touch
 * any of these classes (see [AngelPayCredentialResolver] guard note).
 */
@Singleton
class AngelPayAuthRepository @Inject constructor(
    private val sdkGateway: AngelPaySdkGateway,
    private val credentialResolver: AngelPayCredentialResolver,
    private val configValidator: AngelPayConfigValidator,
    private val terminalConfigRepository: TerminalConfigRepository,
    private val deviceInfoManager: DeviceInfoManager,
    private val merchantRepository: AngelPayMerchantRepository,
    // 🛡️ D2 charging gate (P1 fix 2026-07-09): account switches log the SDK session
    // OUT — doing that mid-charge kills the in-flight payment with an ambiguous
    // outcome. Merchant switches already consult this same lock (AngelPayMerchantRepository).
    private val paymentStateProvider: PaymentStateProvider,
    private val crashlytics: FirebaseCrashlytics,
    /**
     * Optional backend reporter for the `/tpv/angelpay/report-validation` endpoint
     * (Task 14). Defaults to `null` so Hilt can construct the repository without a
     * real Retrofit binding — Task 38 wires the concrete implementation. Nullable
     * keeps unit tests trivial and avoids forcing a no-op subclass into the graph.
     *
     * Every invocation is wrapped in `runCatching` so any backend hiccup is opaque
     * to the cashier-facing auth flow.
     */
    private val reportApi: AngelPayReportApi? = null,
) {
    private val _state = MutableStateFlow<AngelPayAuthState>(AngelPayAuthState.Unauthenticated)
    val state: StateFlow<AngelPayAuthState> = _state.asStateFlow()

    /**
     * Tracks which AngelPay user account (Avoqado `AngelPayUserAccount.id`) the
     * SDK is currently authenticated as. Updated on every successful auth and
     * after every `switchAccount`. `null` when the SDK has no live session.
     *
     * Multi-AngelPay accounts per venue (2026-05-18). The ViewModel reads this
     * via [getCurrentAngelPayAccountId] to decide whether picking a merchant
     * requires swapping AngelPay logins via [switchAccount].
     */
    private var currentAngelPayAccountId: String? = null

    /** Snapshot of the AngelPay account ID currently authenticated, or null. */
    fun getCurrentAngelPayAccountId(): String? = currentAngelPayAccountId

    /**
     * Lazy + idempotent. Resolves creds, drives `authenticateSimple` with retries,
     * and either transitions to [AngelPayAuthState.Authenticated] (running config
     * validation as a side-effect) or to [AngelPayAuthState.SelectingMerchant]
     * (waiting for the cashier to finish the initial pick via
     * [completeMerchantSelection]).
     */
    /**
     * @param reportMerchants When `true`, fires
     *   `POST /tpv/angelpay/report-discovered-merchants` to the backend
     *   after a successful auth. Default `false` keeps the TPV silent on
     *   spontaneous auth flows (reboot, screen entry, payment-start
     *   pre-flight) so the backend's `MerchantAccount` table is NEVER
     *   mutated as a side-effect of normal TPV operation.
     *
     *   The only path that should pass `true` is the explicit
     *   `FETCH_ANGELPAY_MERCHANTS` command dispatched from the dashboard
     *   ([com.jaac.avoqado_tpv.features.remote_command.domain.CommandExecutor.executeFetchAngelPayMerchants]).
     *   That keeps merchant discovery a deliberate, audit-able action
     *   instead of an opaque side-effect — solves the "reboot recreates
     *   merchants we just cleaned up" loop reported 2026-05-28.
     *
     *   [reportValidation] (auth ok/fail telemetry) is NOT gated by this
     *   flag — it doesn't create merchant rows, just records observability.
     */
    suspend fun ensureAuthenticated(reportMerchants: Boolean = false): Result<Unit> {
        Timber.tag(LOG_TAG).i("ensureAuthenticated() called")
        if (sdkGateway.isAuthenticated()) {
            // Probe SDK session health BEFORE trusting the cached auth flag.
            // An "authenticated" session with 0 merchants is a stale/broken
            // session — observed 2026-05-20 after app reinstall + venue
            // recreation, where the SDK kept its token but lost its merchant
            // list. Short-circuiting on a stale session would report 0
            // merchants to the backend, leaving FETCH_ANGELPAY_MERCHANTS
            // "succeed-but-empty" and the dashboard spinner stuck at 90s.
            val cachedMerchants = runCatching { sdkGateway.getUserMerchants().getOrNull() }.getOrNull()
            if (cachedMerchants.isNullOrEmpty()) {
                Timber.tag(LOG_TAG).w("SDK reports authenticated but getUserMerchants() returned ${cachedMerchants?.size ?: "null"} merchants — session is stale, forcing logout + full re-auth")
                runCatching { sdkGateway.logout() }
                merchantRepository.clearActive()
                // Fall through to the credential-resolve + authenticate path.
            } else {
                Timber.tag(LOG_TAG).i("SDK already authenticated with ${cachedMerchants.size} merchants — short-circuit to Authenticated state")
                _state.value = AngelPayAuthState.Authenticated

                // Multi-AngelPay accounts per venue — only populate if NOT already
                // set. Trust the in-memory value, because after a `switchAccount`
                // call the SDK is authed as a NON-primary account but the resolver
                // still returns the venue's primary account creds (the cached
                // `terminalConfig.angelpayAuth` field is "the primary account",
                // not "which account is currently in the SDK session").
                //
                // Overwriting here on every ensureAuthenticated() call would
                // corrupt `currentAngelPayAccountId` back to the primary right
                // after a switchAccount — making `reportDiscoveredMerchantsIfPossible`
                // attribute the switched-account's merchants to the WRONG accountId
                // server-side, so placeholders for non-primary accounts never
                // upgrade. (Reproduced 2026-05-19 with contacto@avoqado.io.)
                if (currentAngelPayAccountId == null) {
                    // The SDK can hold a cached session for a NON-primary account
                    // (e.g. after a prior switchAccount to "ventas"). The config's
                    // primary (`angelpayAuth`) might be a DIFFERENT account
                    // ("contacto"), so blindly resolving to the primary scopes the
                    // validator to the WRONG account's merchants → empty intersection
                    // → false "Sin merchants válidos" HardBlock until the cashier
                    // switches manually. (Repro: venue with 2 AngelPay accounts where
                    // primary != SDK-session account. Amaena prod is unaffected because
                    // there primary == the SDK-session account.)
                    //
                    // Derive the active account from the SDK session's actual merchants
                    // first; fall back to the resolver's primary only when no config
                    // merchant matches the session (e.g. cold cache).
                    currentAngelPayAccountId =
                        resolveAccountIdFromSdkMerchantIds(cachedMerchants.map { it.id }.toSet())
                            ?: credentialResolver.resolve().getOrNull()?.accountId
                }

                Timber.tag(LOG_TAG).i("SDK session merchants (cached auth): ${cachedMerchants.size} total — " +
                    cachedMerchants.joinToString { "${it.id}=${it.name}(afil=${it.affiliationNumber}, active=${it.isActive})" })
                runCatching {
                    val session = sdkGateway.getSessionInfo()
                    if (session != null) {
                        Timber.tag(LOG_TAG).i("SDK session info: userId=${session.userId}, merchantName=${session.merchantName}, affiliation=${session.affiliation}")
                    }
                }

                // Seed the active merchant so the payment screen can pre-select
                // it without waiting for the optional "report merchants" feature.
                // Must come BEFORE reportDiscoveredMerchantsIfPossible so the
                // StateFlow is populated even when reportApi is null (the common
                // case on terminals that skipped Task 38 wiring).
                merchantRepository.seedActiveMerchantFromSession(cachedMerchants)

                // ⚠️ ORDER MATTERS: report FIRST so backend creates merchants +
                // auto-assigns to slots, THEN refresh terminal config so the new
                // merchants are in the cache, THEN validate against the fresh
                // intersection. Doing validation first always fails on first auth
                // because backend hasn't seen the SDK's merchant list yet.
                if (reportMerchants) {
                    reportDiscoveredMerchantsIfPossible()
                }
                refreshTerminalConfigQuietly()
                runConfigValidation()
                return Result.success(Unit)
            }
        }

        Timber.tag(LOG_TAG).i("SDK not authenticated yet → resolving credentials")
        var credsResult = credentialResolver.resolve()

        // Self-heal: if the cache doesn't have backend creds, force a fresh fetch
        // and retry once. The cache may be empty for legitimate reasons:
        //   - The first config fetch (MainActivity startup) ran without a user session
        //     and the backend gated angelpayAuth on auth, so the response was empty.
        //   - AppNavigation post-login fetch was skipped because the user's session
        //     was restored from SecureStorage (no fresh login event).
        //   - The cache was invalidated and not refreshed yet.
        // This makes auth resilient to fetch timing instead of relying on every caller
        // to remember to refresh the config first.
        if (credsResult.isFailure) {
            Timber.tag(LOG_TAG).w("Resolver returned failure on first try — forcing terminal config refresh and retrying")
            val serial = deviceInfoManager.getSerialNumber()
            terminalConfigRepository.fetchConfig(serial)
                .onSuccess { Timber.tag(LOG_TAG).i("Self-heal fetch succeeded — retrying resolver") }
                .onFailure { Timber.tag(LOG_TAG).e(it, "Self-heal fetch FAILED — resolver will fail again") }
            credsResult = credentialResolver.resolve()
        }

        if (credsResult.isFailure) {
            val err = credsResult.exceptionOrNull()
                ?: IllegalStateException("Missing AngelPay credentials")
            Timber.tag(LOG_TAG).e(err, "Credential resolver FAILED after self-heal retry → AuthError state. " +
                "Backend still didn't return angelpayAuth. Verify: " +
                "(a) dashboard shows AngelPay account ACTIVE for this venue, " +
                "(b) the venue this terminal belongs to matches the venue with the AngelPay account, " +
                "(c) BuildConfig.ANGELPAY_QA_* are empty (expected post-Task-21).")
            _state.value = AngelPayAuthState.AuthError(err.message ?: "Missing AngelPay credentials")
            return Result.failure(err)
        }
        val creds = credsResult.getOrThrow()
        return authenticateWithCreds(creds, reportMerchants = reportMerchants)
    }

    /**
     * Core authenticate body extracted so both [ensureAuthenticated] and
     * [switchAccount] can share the exact same `authenticateSimple` + 3-retry
     * backoff + state-machine + post-auth report/refresh/validate sequence.
     *
     * Transitions [_state] and updates [currentAngelPayAccountId] on success.
     * Surfaces [AngelPayAuthState.AuthError] + records to Crashlytics on
     * failure — never throws.
     */
    private suspend fun authenticateWithCreds(
        creds: AngelPayCreds,
        reportMerchants: Boolean = false,
    ): Result<Unit> {
        Timber.tag(LOG_TAG).i("authenticateWithCreds(source=${creds.source}, accountId=${creds.accountId}, email=${creds.email}, env=${creds.environment}) → calling authenticateSimple")

        _state.value = AngelPayAuthState.Authenticating

        // 5 attempts with longer backoff (1s → 3s → 5s → 10s → 20s, ~39s total).
        // AngelPay QA `initKeys` endpoint occasionally takes 15+ sec under load;
        // the previous 3-attempt schedule (500ms+1s+2s = 3.5s) bailed before the
        // server recovered. Reported: 2026-05-19 timeout on contacto@avoqado.io.
        val authResult = retryWithBackoff(maxAttempts = 5) {
            sdkGateway.authenticateSimple(creds.email, creds.pin)
        }

        return authResult.fold(
            onSuccess = { sdkResult ->
                when (sdkResult) {
                    is AuthenticateSimpleResult.Success -> {
                        Timber.tag(LOG_TAG).i("authenticateSimple → Success (single-merchant flow). Transitioning to Authenticated.")
                        _state.value = AngelPayAuthState.Authenticated
                        currentAngelPayAccountId = creds.accountId
                        reportValidation(creds.accountId, success = true)
                        // Seed the active merchant from the live SDK session so the
                        // payment screen can pre-select it. getUserMerchants() is a
                        // cheap in-memory read here (the SDK just authed); wrapped in
                        // runCatching so a transient SDK hiccup can't block auth.
                        runCatching {
                            sdkGateway.getUserMerchants().getOrNull()?.let {
                                merchantRepository.seedActiveMerchantFromSession(it)
                            }
                        }
                        // ⚠️ ORDER MATTERS — see comment in `isAuthenticated`
                        // short-circuit branch. Report first → refresh config →
                        // then validate against fresh intersection.
                        if (reportMerchants) {
                            reportDiscoveredMerchantsIfPossible()
                        }
                        refreshTerminalConfigQuietly()
                        runConfigValidation()
                        Result.success(Unit)
                    }
                    is AuthenticateSimpleResult.MerchantSelectionRequired -> {
                        Timber.tag(LOG_TAG).i("authenticateSimple → MerchantSelectionRequired with ${sdkResult.merchants.size} merchants: " +
                            sdkResult.merchants.joinToString { "${it.id}=${it.name}(${it.afiliationNumber})" })
                        currentAngelPayAccountId = creds.accountId
                        _state.value = AngelPayAuthState.SelectingMerchant(
                            merchants = sdkResult.merchants,
                            temporaryToken = sdkResult.temporaryToken,
                        )
                        // NOTE: `reportValidation(AUTHENTICATED)` deliberately NOT
                        // called here. The SDK has a session but no merchant is
                        // selected yet → `getSessionInfo()?.userId` returns null →
                        // backend 400s with "externalUserId (number) required for
                        // AUTHENTICATED state". The discovered-merchants report
                        // below is the meaningful signal for the dashboard
                        // pre-selection; the AUTHENTICATED state report fires in
                        // `completeMerchantSelection` once a merchant is actually
                        // selected and the SDK populates `getSessionInfo()`.
                        // Reproduced 2026-05-20 — ventas@avoqado.io returns
                        // MerchantSelectionRequired(2 merchants), backend 400 on
                        // every FETCH_ANGELPAY_MERCHANTS.
                        // Multi-AngelPay accounts per venue (2026-05-19): report
                        // the merchants AngelPay returned even though the SDK is
                        // still in "selecting merchant" state. The cashier-side
                        // selection completes the SDK session, but the dashboard
                        // operator needs visibility on what merchants this
                        // account has access to RIGHT NOW (so they can approve /
                        // assign to slots before the TPV cashier ever picks).
                        // Previously the report only fired in the Success branch
                        // → multi-merchant accounts left the backend blind. The
                        // discovered list is in `sdkResult.merchants` so we
                        // don't need to call `getUserMerchants()` separately.
                        if (reportMerchants) {
                            reportDiscoveredMerchantsFromList(creds.accountId, sdkResult.merchants)
                        }
                        refreshTerminalConfigQuietly()
                        Result.success(Unit)
                    }
                }
            },
            onFailure = { err ->
                Timber.tag(LOG_TAG).e(err, "authenticateSimple FAILED after 3 retries → AuthError state")
                _state.value = AngelPayAuthState.AuthError(err.message ?: "Unknown auth error")
                reportValidation(creds.accountId, success = false, error = err.message)
                runCatching { crashlytics.recordException(err) }
                Result.failure(err)
            },
        )
    }

    /**
     * Switch the SDK to a DIFFERENT AngelPay account (different email/PIN
     * login). Multi-AngelPay accounts per venue (2026-05-18).
     *
     *  - Idempotent: no-op + Result.success when [accountId] equals the
     *    [currentAngelPayAccountId].
     *  - Resolves creds for the target [accountId] via
     *    [AngelPayCredentialResolver.resolveByAccountId].
     *  - Logs out the current SDK session, then re-authenticates via the
     *    shared [authenticateWithCreds] helper (same 3-retry backoff +
     *    state-machine + report/refresh/validate as [ensureAuthenticated]).
     *  - Updates [_state] and [currentAngelPayAccountId] on success.
     *  - Surfaces failure via [AngelPayAuthState.AuthError] AND returns
     *    `Result.failure(err)` for the caller to render. Never crashes.
     */
    /**
     * @param reportMerchants See [ensureAuthenticated] — same semantics.
     *   Default `false` so cashier-driven account switches in the merchant
     *   selector (`AngelPayPaymentViewModel.selectMerchant`) stay silent
     *   on the backend. Only the dashboard's `FETCH_ANGELPAY_MERCHANTS`
     *   command should pass `true`.
     */
    suspend fun switchAccount(
        accountId: String,
        reportMerchants: Boolean = false,
    ): Result<Unit> {
        // 🛡️ P1 fix (2026-07-09): a dashboard FETCH_ANGELPAY_MERCHANTS command can
        // land mid-charge (commands arrive via heartbeat/socket regardless of what
        // the cashier is doing). switchAccount logs the SDK session out, which would
        // kill the in-flight payment with an ambiguous post-auth outcome. Reject and
        // let the dashboard retry once the charge settles.
        if (paymentStateProvider.isCharging()) {
            val err = IllegalStateException(
                "Cobro en curso — no se puede cambiar la cuenta AngelPay durante un pago",
            )
            Timber.tag(LOG_TAG).w("switchAccount($accountId) rejected: ${err.message}")
            return Result.failure(err)
        }
        if (currentAngelPayAccountId == accountId) {
            // Probe SDK session health before trusting the no-op. Same reasoning
            // as `ensureAuthenticated`'s short-circuit: an "authenticated" session
            // with 0 merchants is stale (observed 2026-05-20 after reinstall +
            // venue recreation). A no-op here means FETCH_ANGELPAY_MERCHANTS
            // reports 0 merchants and the dashboard spinner sits at 90s.
            val cachedMerchants = runCatching { sdkGateway.getUserMerchants().getOrNull() }.getOrNull()
            if (!cachedMerchants.isNullOrEmpty()) {
                Timber.tag(LOG_TAG).i("switchAccount($accountId) — already on this account, SDK session has ${cachedMerchants.size} merchants, no-op")
                return Result.success(Unit)
            }
            Timber.tag(LOG_TAG).w("switchAccount($accountId) — already on this account but SDK session has 0 merchants — forcing full re-auth")
            currentAngelPayAccountId = null
            runCatching { sdkGateway.logout() }
            merchantRepository.clearActive()
            // Fall through to the credential-resolve + authenticate path below.
        }
        Timber.tag(LOG_TAG).i("switchAccount: $currentAngelPayAccountId → $accountId")

        // Self-heal: if the requested accountId isn't in the cached config's
        // angelpayAccounts list, force a terminal-config refetch and retry
        // ONCE. Symmetric to the self-heal in `ensureAuthenticated` (line 148+).
        // Triggers in two real scenarios:
        //   1. Operator adds an AngelPay account in dashboard AFTER the TPV's
        //      last config fetch — cache is stale until next heartbeat refresh.
        //      Without this, the FETCH_ANGELPAY_MERCHANTS command for the new
        //      account fails with MissingAngelPayCredsError, the dashboard
        //      spinner times out at 90s, and the operator thinks the system
        //      is broken when really the cache just needs a poke.
        //   2. Venue was re-created/migrated — accountIds in the new venue
        //      don't match the TPV's pre-migration cache. Same self-heal path.
        // Reproduced 2026-05-20 on venue `cmpe64yq2001f9k92m0lbhmf4` (post-
        // venue-recreation) with accountId `cmpe6s63400119k3y9euvmf70`.
        var credsResult = credentialResolver.resolveByAccountId(accountId)
        if (credsResult.isFailure) {
            Timber.tag(LOG_TAG).w("switchAccount: resolveByAccountId($accountId) miss on first try — refreshing terminal config and retrying")
            runCatching {
                val serial = deviceInfoManager.getSerialNumber()
                terminalConfigRepository.fetchConfig(serial)
                    .onSuccess { Timber.tag(LOG_TAG).i("Self-heal config refresh succeeded — retrying resolver") }
                    .onFailure { Timber.tag(LOG_TAG).e(it, "Self-heal config refresh FAILED — resolver will fail again") }
            }
            credsResult = credentialResolver.resolveByAccountId(accountId)
        }
        if (credsResult.isFailure) {
            val err = credsResult.exceptionOrNull()
                ?: IllegalStateException("AngelPay account $accountId not found in cached config")
            Timber.tag(LOG_TAG).e(err, "switchAccount: resolveByAccountId failed AFTER self-heal retry → AuthError. " +
                "Backend did not return this accountId in the angelpayAccounts list. Verify: " +
                "(a) the AngelPayUserAccount row exists and is ACTIVE, " +
                "(b) it belongs to the SAME venue as this terminal, " +
                "(c) the terminal config response includes the angelpayAccounts array.")
            _state.value = AngelPayAuthState.AuthError(err.message ?: "Cuenta AngelPay no encontrada")
            return Result.failure(err)
        }
        val creds = credsResult.getOrThrow()

        // Logout BEFORE re-authenticating so the SDK doesn't reject the call as
        // already-authenticated. `logout()` is idempotent + clears the in-memory
        // JWT; the next `authenticateSimple` establishes a fresh session.
        runCatching { sdkGateway.logout() }
        merchantRepository.clearActive()

        return authenticateWithCreds(creds, reportMerchants = reportMerchants)
    }

    /**
     * Finalize the initial merchant pick after [AngelPayAuthState.SelectingMerchant].
     * Delegates the SDK call + cache write to [AngelPayMerchantRepository], then runs
     * the post-auth config validation just like [ensureAuthenticated]'s success path.
     */
    /**
     * @param reportMerchants See [ensureAuthenticated] — same semantics.
     *   Default `false`: cashier-driven merchant picks never mutate the
     *   backend's `MerchantAccount` table. Today this is *only* invoked
     *   from the cashier UI ([com.jaac.avoqado_tpv.features.payment.presentation.angelpay.AngelPayPaymentViewModel.selectMerchant]),
     *   so default-false is correct. Param exists for future symmetry if
     *   the dashboard ever dispatches a "complete merchant selection"
     *   command.
     */
    suspend fun completeMerchantSelection(
        merchantId: Int,
        temporaryToken: String,
        reportMerchants: Boolean = false,
    ): Result<Unit> {
        val result = merchantRepository.completeInitialSelection(merchantId, temporaryToken)
        if (result.isSuccess) {
            _state.value = AngelPayAuthState.Authenticated
            // Now the SDK has a fully-authenticated session with externalUserId
            // populated — fire the deferred validation report that we skipped
            // in the MerchantSelectionRequired branch of authenticateWithCreds.
            reportValidation(currentAngelPayAccountId, success = true)
            // ⚠️ ORDER MATTERS — same reasoning as `ensureAuthenticated`'s
            // Success branch: report first so backend creates+assigns merchants,
            // refresh config so cache has them, then validate.
            if (reportMerchants) {
                reportDiscoveredMerchantsIfPossible()
            }
            refreshTerminalConfigQuietly()
            runConfigValidation()
        } else {
            val err = result.exceptionOrNull()
            _state.value = AngelPayAuthState.AuthError(err?.message ?: "Merchant selection failed")
        }
        return result
    }

    /**
     * Mid-payment recovery hook — called by the payment flow when it catches
     * [AngelPayAuthExpiredError]. Forces a clean logout + one re-auth attempt;
     * the caller (PaymentViewModel) decides whether to retry the charge.
     */
    suspend fun handleAuthExpiry(): Result<Unit> {
        sdkGateway.logout()
        merchantRepository.clearActive()
        currentAngelPayAccountId = null
        _state.value = AngelPayAuthState.Unauthenticated
        return ensureAuthenticated()
    }

    /** Manual logout — clears SDK + active merchant + state flow. Idempotent. */
    fun logout() {
        sdkGateway.logout()
        merchantRepository.clearActive()
        currentAngelPayAccountId = null
        _state.value = AngelPayAuthState.Unauthenticated
    }

    /**
     * Post-auth D5 intersection validation (spec §6.9, §18.4).
     *
     *   - `AllClear`  → keep [AngelPayAuthState.Authenticated], no banner
     *   - `PartialOperable` → emit [AngelPayAuthState.ConfigMismatchBanner]
     *     (payments still allowed against the operable subset)
     *   - `HardBlock` → transition to [AngelPayAuthState.AuthError]
     *     (payments blocked, operator banner)
     *
     * If no config is cached yet (cold start race), this is a no-op — the next
     * `fetchConfig()` heartbeat will surface drift later.
     */
    /**
     * Map the SDK session's merchant ids back to the owning AngelPayUserAccount
     * id via the cached terminal config. The SDK reports merchant ids (e.g. 63);
     * the config maps each `externalMerchantId` → `angelpayUserAccountId`. Returns
     * the first ANGELPAY merchant whose external id is in the SDK session, or null
     * when the cache is cold / no match (caller falls back to the config primary).
     *
     * This keeps `currentAngelPayAccountId` aligned with the account the SDK is
     * ACTUALLY authenticated as, so the D5 intersection validator scopes to the
     * right merchants on first (cached) auth instead of false-blocking.
     */
    private fun resolveAccountIdFromSdkMerchantIds(sdkMerchantIds: Set<Int>): String? {
        if (sdkMerchantIds.isEmpty()) return null
        val config = terminalConfigRepository.getCachedConfig() ?: return null
        return config.merchantAccounts
            .firstOrNull { ma ->
                ma.providerCode == "ANGELPAY" &&
                    ma.angelpayUserAccountId != null &&
                    ma.externalMerchantId?.toIntOrNull() in sdkMerchantIds
            }
            ?.angelpayUserAccountId
    }

    private suspend fun runConfigValidation() {
        val config = terminalConfigRepository.getCachedConfig() ?: return
        // Multi-AngelPay accounts per venue (2026-05-19): pass the current
        // SDK-session account ID so the validator scopes the intersection
        // check to JUST this account's merchants (and not merchants from
        // OTHER accounts in the same venue which would always look as
        // "drift" because the current SDK session can't see them).
        when (val result = configValidator.validate(config, currentAngelPayAccountId)) {
            is ValidationResult.AllClear -> { /* keep Authenticated */ }
            is ValidationResult.PartialOperable -> {
                _state.value = AngelPayAuthState.ConfigMismatchBanner(
                    onlyInSdk = result.onlyInSdk,
                    onlyInAvoqado = result.onlyInAvoqado,
                )
                runCatching {
                    reportApi?.reportValidation(
                        accountId = config.angelpayAuth?.accountId ?: return@runCatching,
                        state = "CONFIG_MISMATCH",
                        externalUserId = sdkGateway.getSessionInfo()?.userId?.toIntOrNull(),
                        missingInAvoqado = result.onlyInSdk.toList(),
                        missingInSdk = result.onlyInAvoqado.toList(),
                    )
                }
            }
            is ValidationResult.HardBlock -> {
                _state.value = AngelPayAuthState.AuthError(result.message)
            }
            is ValidationResult.Unavailable -> {
                // Transport failure querying the SDK — drift UNKNOWN. Keep the
                // current auth state; a transient network error must never
                // masquerade as a "config mismatch" hard block (P1 fix 2026-07-09).
                Timber.tag(LOG_TAG).w(
                    "Config validation unavailable (%s) — keeping current auth state",
                    result.reason,
                )
            }
        }
    }

    /**
     * Option B workaround: after successful auth (or merchant switch), fetch the
     * authoritative merchant list via `AngelPaySDK.getUserMerchants()` and POST
     * it to backend `/tpv/angelpay/report-discovered-merchants` so an admin can
     * approve any newly-visible merchants in the dashboard.
     *
     * Runs AFTER `runConfigValidation()` (config drift is the more critical signal
     * to surface first) and is fully fire-and-forget: any failure is swallowed
     * inside `runCatching` so it cannot disturb the cashier-facing auth state.
     */
    /**
     * Multi-AngelPay accounts per venue (2026-05-19) — report merchants
     * coming directly from `AuthenticateSimpleResult.MerchantSelectionRequired`.
     * Distinct from [reportDiscoveredMerchantsIfPossible] which calls
     * `getUserMerchants()` post-auth (only works AFTER a merchant is selected
     * — chicken-and-egg in the multi-merchant case). This helper accepts the
     * pre-selection merchant list AngelPay already sent us in the auth
     * response, so the backend learns about them BEFORE the cashier picks.
     */
    private suspend fun reportDiscoveredMerchantsFromList(
        accountId: String?,
        merchants: List<com.angelpay.angelpaysdk.models.MerchantOption>,
    ) {
        if (accountId == null) return
        runCatching {
            val api = reportApi ?: return@runCatching
            if (merchants.isEmpty()) return@runCatching
            api.reportDiscoveredMerchants(
                accountId = accountId,
                // MerchantOption (pre-selection list) has no isActive field —
                // pass `true` because AngelPay only returns merchants the user
                // can currently transact through. The richer MerchantSummary
                // (post-selection from getUserMerchants) DOES have isActive,
                // and the post-selection report path uses it. Note the typo
                // in `afiliationNumber` (single `f`) — that's AngelPay's
                // field name, not ours.
                merchants = merchants.map { m ->
                    DiscoveredMerchantDto(
                        angelpayId = m.id,
                        name = m.name,
                        affiliationNumber = m.afiliationNumber,
                        isActive = true,
                    )
                },
            )
        }
    }

    private suspend fun reportDiscoveredMerchantsIfPossible() {
        runCatching {
            // Use the in-memory SDK-session accountId FIRST. After a
            // `switchAccount(contacto@)` call, the SDK is authed as contacto@
            // but `terminalConfig.angelpayAuth.accountId` is still the venue's
            // PRIMARY (e.g. ventas@) — pairing primary's accountId with
            // contacto@'s merchants tells the backend to upgrade ventas@'s
            // placeholders instead of contacto@'s, so contacto@'s placeholder
            // never gets upgraded. Fall back to the cached primary only on
            // cold start (before any auth has happened). Reproduced on
            // contacto@avoqado.io 2026-05-19.
            val accountId = currentAngelPayAccountId
                ?: terminalConfigRepository.getCachedConfig()?.angelpayAuth?.accountId
                ?: return@runCatching
            val api = reportApi ?: return@runCatching
            val merchantsResult = merchantRepository.fetchAndCacheMerchants()
            val merchants = merchantsResult.getOrNull() ?: return@runCatching
            if (merchants.isEmpty()) return@runCatching
            api.reportDiscoveredMerchants(
                accountId = accountId,
                merchants = merchants.map { m ->
                    DiscoveredMerchantDto(
                        angelpayId = m.id,
                        name = m.name,
                        affiliationNumber = m.affiliationNumber,
                        isActive = m.isActive,
                    )
                },
            )
        }
    }

    private suspend fun reportValidation(
        accountId: String?,
        success: Boolean,
        error: String? = null,
    ) {
        if (accountId == null) return
        runCatching {
            reportApi?.reportValidation(
                accountId = accountId,
                state = if (success) "AUTHENTICATED" else "AUTH_ERROR",
                externalUserId = sdkGateway.getSessionInfo()?.userId?.toIntOrNull(),
                error = error,
            )
        }
    }

    /**
     * Retry [block] up to [maxAttempts] times with custom backoff schedule
     * (1s, 3s, 5s, 10s, 20s). Tuned for AngelPay QA `initKeys` endpoint which
     * can timeout >15s under load. Returns the last result on completion;
     * success short-circuits early.
     *
     * Logs each retry with attempt number + delay so admin can see the
     * progression in adb logcat without grep gymnastics.
     */
    private suspend fun <T> retryWithBackoff(
        maxAttempts: Int,
        block: suspend () -> Result<T>,
    ): Result<T> {
        val backoffMs = longArrayOf(1_000L, 3_000L, 5_000L, 10_000L, 20_000L)
        var lastResult: Result<T> = Result.failure(IllegalStateException("retry never invoked"))
        repeat(maxAttempts) { attempt ->
            if (attempt > 0) {
                Timber.tag(LOG_TAG).i("Retry attempt ${attempt + 1}/$maxAttempts (after ${backoffMs[(attempt - 1).coerceAtMost(backoffMs.size - 1)]}ms)")
            }
            lastResult = block()
            if (lastResult.isSuccess) {
                if (attempt > 0) Timber.tag(LOG_TAG).i("Retry succeeded on attempt ${attempt + 1}")
                return lastResult
            }
            if (attempt < maxAttempts - 1) {
                val delayMs = backoffMs[attempt.coerceAtMost(backoffMs.size - 1)]
                Timber.tag(LOG_TAG).w(lastResult.exceptionOrNull(), "Attempt ${attempt + 1} failed — sleeping ${delayMs}ms before retry")
                delay(delayMs)
            }
        }
        return lastResult
    }

    /**
     * Refresh the cached terminal config after we've reported discovered
     * merchants to the backend so `runConfigValidation` sees the fresh
     * intersection (otherwise the validator compares SDK merchants vs the
     * stale cache and emits "no merchants compartidos" HardBlock the very
     * first time auth runs — chicken-and-egg).
     *
     * Fire-and-forget semantics: if the refresh fails (network blip), the
     * validator falls back to its cached config and may emit a transient
     * mismatch banner. The next heartbeat refresh corrects it.
     */
    private suspend fun refreshTerminalConfigQuietly() {
        runCatching {
            val serial = deviceInfoManager.getSerialNumber()
            Timber.tag(LOG_TAG).i("Refreshing terminal config post-report so validator sees fresh merchant intersection")
            terminalConfigRepository.fetchConfig(serial)
                .onSuccess { Timber.tag(LOG_TAG).i("Post-report config refresh OK") }
                .onFailure { Timber.tag(LOG_TAG).w(it, "Post-report config refresh failed — validator may emit transient mismatch") }
        }
    }
}

/**
 * Stub interface for the backend `/tpv/angelpay/report-validation` endpoint
 * (Task 14). The concrete Retrofit binding is wired in Task 38 (Phase 4 closeout)
 * — until then [AngelPayAuthRepository] receives `null` and skips the network
 * call entirely. All call sites are wrapped in `runCatching` so a future
 * malfunctioning implementation can't crash the cashier-facing auth flow.
 *
 * @property state One of `"AUTHENTICATED"`, `"AUTH_ERROR"`, `"CONFIG_MISMATCH"`.
 *                 Defined by spec §6.5 observability.
 */
interface AngelPayReportApi {
    suspend fun reportValidation(
        accountId: String,
        state: String,
        externalUserId: Int? = null,
        error: String? = null,
        missingInAvoqado: List<Int>? = null,
        missingInSdk: List<Int>? = null,
    ): Result<Unit>

    /**
     * Option B workaround: report merchants returned by `AngelPaySDK.getUserMerchants()`
     * after successful auth (and after every successful merchant switch). Backend
     * upserts `MerchantAccount` rows with `active=false` so an admin can approve them
     * in the dashboard. Fire-and-forget — all call sites wrap in `runCatching`.
     */
    suspend fun reportDiscoveredMerchants(
        accountId: String,
        merchants: List<DiscoveredMerchantDto>,
    ): Result<Unit>
}

/**
 * Wire-format merchant payload sent to
 * `POST /tpv/angelpay/report-discovered-merchants`. Matches the backend's
 * `DiscoveredAngelPayMerchant` interface exactly.
 */
data class DiscoveredMerchantDto(
    val angelpayId: Int,
    val name: String,
    val affiliationNumber: String,
    val isActive: Boolean,
)

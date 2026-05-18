package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.angelpay.angelpaysdk.models.AuthenticateSimpleResult
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.jaac.avoqado_tpv.core.domain.repository.TerminalConfigRepository
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
    private val merchantRepository: AngelPayMerchantRepository,
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
     * Lazy + idempotent. Resolves creds, drives `authenticateSimple` with retries,
     * and either transitions to [AngelPayAuthState.Authenticated] (running config
     * validation as a side-effect) or to [AngelPayAuthState.SelectingMerchant]
     * (waiting for the cashier to finish the initial pick via
     * [completeMerchantSelection]).
     */
    suspend fun ensureAuthenticated(): Result<Unit> {
        Timber.tag(LOG_TAG).i("ensureAuthenticated() called")
        if (sdkGateway.isAuthenticated()) {
            Timber.tag(LOG_TAG).i("SDK already authenticated — short-circuit to Authenticated state")
            _state.value = AngelPayAuthState.Authenticated
            runConfigValidation()
            reportDiscoveredMerchantsIfPossible()
            return Result.success(Unit)
        }

        Timber.tag(LOG_TAG).i("SDK not authenticated yet → resolving credentials")
        val credsResult = credentialResolver.resolve()
        if (credsResult.isFailure) {
            val err = credsResult.exceptionOrNull()
                ?: IllegalStateException("Missing AngelPay credentials")
            Timber.tag(LOG_TAG).e(err, "Credential resolver FAILED → AuthError state. " +
                "Most common cause: terminal config response did NOT include `angelpayAuth` " +
                "(meaning backend thinks venue lacks ACTIVE AngelPayUserAccount) AND BuildConfig.ANGELPAY_QA_* are empty.")
            _state.value = AngelPayAuthState.AuthError(err.message ?: "Missing AngelPay credentials")
            return Result.failure(err)
        }
        val creds = credsResult.getOrThrow()
        Timber.tag(LOG_TAG).i("Credentials resolved (source=${creds.source}, accountId=${creds.accountId}, email=${creds.email}, env=${creds.environment}) → calling authenticateSimple")

        _state.value = AngelPayAuthState.Authenticating

        val authResult = retryWithBackoff(maxAttempts = 3) {
            sdkGateway.authenticateSimple(creds.email, creds.pin)
        }

        return authResult.fold(
            onSuccess = { sdkResult ->
                when (sdkResult) {
                    is AuthenticateSimpleResult.Success -> {
                        Timber.tag(LOG_TAG).i("authenticateSimple → Success (single-merchant flow). Transitioning to Authenticated.")
                        _state.value = AngelPayAuthState.Authenticated
                        reportValidation(creds.accountId, success = true)
                        runConfigValidation()
                        reportDiscoveredMerchantsIfPossible()
                        Result.success(Unit)
                    }
                    is AuthenticateSimpleResult.MerchantSelectionRequired -> {
                        Timber.tag(LOG_TAG).i("authenticateSimple → MerchantSelectionRequired with ${sdkResult.merchants.size} merchants: " +
                            sdkResult.merchants.joinToString { "${it.id}=${it.name}(${it.afiliationNumber})" })
                        _state.value = AngelPayAuthState.SelectingMerchant(
                            merchants = sdkResult.merchants,
                            temporaryToken = sdkResult.temporaryToken,
                        )
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
     * Finalize the initial merchant pick after [AngelPayAuthState.SelectingMerchant].
     * Delegates the SDK call + cache write to [AngelPayMerchantRepository], then runs
     * the post-auth config validation just like [ensureAuthenticated]'s success path.
     */
    suspend fun completeMerchantSelection(
        merchantId: Int,
        temporaryToken: String,
    ): Result<Unit> {
        val result = merchantRepository.completeInitialSelection(merchantId, temporaryToken)
        if (result.isSuccess) {
            _state.value = AngelPayAuthState.Authenticated
            runConfigValidation()
            reportDiscoveredMerchantsIfPossible()
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
        _state.value = AngelPayAuthState.Unauthenticated
        return ensureAuthenticated()
    }

    /** Manual logout — clears SDK + active merchant + state flow. Idempotent. */
    fun logout() {
        sdkGateway.logout()
        merchantRepository.clearActive()
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
    private suspend fun runConfigValidation() {
        val config = terminalConfigRepository.getCachedConfig() ?: return
        when (val result = configValidator.validate(config)) {
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
    private suspend fun reportDiscoveredMerchantsIfPossible() {
        runCatching {
            val accountId = terminalConfigRepository.getCachedConfig()?.angelpayAuth?.accountId
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
     * Retry [block] up to [maxAttempts] times with exponential backoff
     * (500ms, 1s, 2s gaps). Returns the last result — success short-circuits.
     */
    private suspend fun <T> retryWithBackoff(
        maxAttempts: Int,
        block: suspend () -> Result<T>,
    ): Result<T> {
        var lastResult: Result<T> = Result.failure(IllegalStateException("retry never invoked"))
        repeat(maxAttempts) { attempt ->
            lastResult = block()
            if (lastResult.isSuccess) return lastResult
            if (attempt < maxAttempts - 1) {
                delay(500L * (1L shl attempt))  // 500, 1000, 2000
            }
        }
        return lastResult
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

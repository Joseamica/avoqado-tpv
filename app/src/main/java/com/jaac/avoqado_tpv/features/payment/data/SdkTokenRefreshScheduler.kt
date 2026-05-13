package com.jaac.avoqado_tpv.features.payment.data

import com.blumonpay.pax.shared.neptune_polling.domain.use_case.stop_detect_card.StopDetectCardParams
import com.blumonpay.pax.shared.neptune_polling.domain.use_case.stop_detect_card.StopDetectCardUseCase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.util.CriticalNetworkOperationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Refreshes the Blumon SDK's OAuth token before its 24h server-side TTL expires.
 *
 * ## Why it exists
 *
 * Blumon's `oauth/token` endpoint issues an access token with a **24-hour server-side TTL**
 * (confirmed by Edgardo / Blumon on 2026-05-12). When that token expires, `SaleIccUseCase`
 * starts returning `SaleIccFailure$MomentumFailure` with a body containing `invalid_token`,
 * because the SDK has **no automatic refresh logic**:
 *
 * - `com.example.clean_lib_services.shared_tools.api.GlobalResources.tokenAuth` is a static
 *   `String` with no expiry timestamp.
 * - The OkHttp client in the SDK uses `addInterceptor` (NOT `Authenticator`) so it never
 *   retries on auth failure.
 * - `GetTokenApiResponse` only deserializes `accessToken`, discarding `expires_in`.
 *
 * Without this scheduler, a long-lived TPV process eventually uses an expired token until
 * the operator reboots the device. The Doña Simona incident on 2026-05-12 was the first
 * confirmed production occurrence after v1.14.0 shipped.
 *
 * ## Strategy
 *
 * Edgardo's literal recommendation: **"recorrer el init y realizar el stop card"**. This
 * scheduler runs that recipe **only when idle**:
 *
 * 1. Every hour, check if the last successful init is older than [STALE_THRESHOLD_HOURS] (23h)
 *    and the in-memory flag is still `true`.
 * 2. If stale AND no critical operation in progress (no payment, no merchant switch, no init),
 *    run `StopDetectCardUseCase` to clear the EMV kernel state.
 * 3. Call [InitializationManager.invalidateForRefresh] which flips `_isInitialized.value` to
 *    `false`. The next payment then hits the normal init path used at app launch — the same
 *    code that already runs in production.
 *
 * ## Why this is safe (vs. retry-on-MomentumFailure)
 *
 * Edgardo also said: "si usas el retry [on MomentumFailure] lo que va a suceder es que si no
 * limpias memoria sí puedes tener algún doble cargo". That ruled out reactive auto-retry of
 * `SaleIccUseCase` because, even with stop card, there is risk of double charge if the prior
 * attempt landed on Momentum. This scheduler **never retries a payment**: it only refreshes
 * the token while no payment is happening. There is no path here that can produce a duplicate
 * charge.
 *
 * Historical lesson (see CHANGELOG entry from 2026-04-08): previous fixes that modified the
 * global SDK init order or moved `AppManager.init()` to a different thread broke payments
 * fleet-wide. This scheduler **does not modify the init flow** — it only calls
 * [InitializationManager.invalidateForRefresh] (a new method that just flips a boolean) and
 * the existing [StopDetectCardUseCase] preflight that PaymentViewModel already calls before
 * every cobro.
 *
 * ## Lifecycle
 *
 * - [start] launches a coroutine inside the caller-provided `applicationScope`. Idempotent —
 *   safe to call multiple times (a second call no-ops).
 * - The coroutine terminates when the scope is cancelled (Application.onTerminate).
 */
@Singleton
class SdkTokenRefreshScheduler @Inject constructor(
    private val initializationManager: InitializationManager,
    private val stopDetectCardUseCase: StopDetectCardUseCase,
    private val secureStorage: SecureStorage,
    private val criticalNetworkOperationManager: CriticalNetworkOperationManager,
) {

    companion object {
        /** Hours after the last successful init at which we refresh. Stays below the 24h server TTL. */
        const val STALE_THRESHOLD_HOURS = 23L
        /** Interval at which the scheduler wakes up and evaluates the threshold. */
        const val CHECK_INTERVAL_MS = 60L * 60L * 1000L // 1 hour
        const val STALE_THRESHOLD_MS = STALE_THRESHOLD_HOURS * 60L * 60L * 1000L
    }

    private val _lastRefreshOutcome = MutableStateFlow<RefreshOutcome?>(null)
    val lastRefreshOutcome: StateFlow<RefreshOutcome?> = _lastRefreshOutcome.asStateFlow()

    private var schedulerJob: Job? = null

    /**
     * Start the periodic check. Idempotent: a second call returns the existing job.
     *
     * @param scope Application-scoped coroutine context that survives configuration changes
     *              and outlives any individual screen.
     */
    fun start(scope: CoroutineScope) {
        if (schedulerJob?.isActive == true) {
            Timber.d("⏰ [SdkTokenRefreshScheduler] Already running")
            return
        }
        Timber.i("⏰ [SdkTokenRefreshScheduler] Starting periodic check (every ${CHECK_INTERVAL_MS / 60_000L} min, threshold ${STALE_THRESHOLD_HOURS}h)")
        schedulerJob = scope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                runCheck()
            }
        }
    }

    /**
     * One iteration of the check. Suspends only on [StopDetectCardUseCase]. Catches all
     * exceptions so a single bad iteration cannot kill the scheduler loop.
     *
     * Visibility: package-private for unit tests.
     */
    internal suspend fun runCheck() {
        val outcome = try {
            evaluate()
        } catch (e: Throwable) {
            Timber.w(e, "⏰ [SdkTokenRefreshScheduler] Iteration failed — will retry next cycle")
            RefreshOutcome.Error(e.javaClass.simpleName)
        }
        _lastRefreshOutcome.value = outcome
    }

    private suspend fun evaluate(): RefreshOutcome {
        if (!initializationManager.isInitialized.value) {
            return RefreshOutcome.SkippedFlagAlreadyFalse
        }
        if (criticalNetworkOperationManager.isAnyCriticalOperationInProgress()) {
            return RefreshOutcome.SkippedCriticalInProgress
        }
        val lastInit = secureStorage.getLastBlumonInitTimestamp()
        if (lastInit == null || lastInit <= 0) {
            // The flag is true but we have no recorded timestamp; nothing safe to do here.
            return RefreshOutcome.SkippedNoTimestamp
        }
        val ageMs = System.currentTimeMillis() - lastInit
        if (ageMs < STALE_THRESHOLD_MS) {
            return RefreshOutcome.SkippedFresh(ageHours = ageMs / 3_600_000L)
        }

        Timber.w("⏰ [SdkTokenRefreshScheduler] Token age ${ageMs / 3_600_000L}h ≥ ${STALE_THRESHOLD_HOURS}h — applying Edgardo's recipe (stop card + invalidate)")

        // Re-check the guard one more time right before mutating state. Closes the
        // race where a payment starts between the first guard and this point. The
        // worst case if a payment starts AFTER this check is still benign: the
        // payment proceeds with the still-valid flag and we skip the invalidation
        // (we record the outcome and try again next iteration).
        if (criticalNetworkOperationManager.isAnyCriticalOperationInProgress()) {
            return RefreshOutcome.SkippedRaceLost
        }

        // Step 1: stop card per Edgardo's instructions — clears the EMV kernel state.
        // Wrapped so a SDK error here does not abort the refresh.
        try {
            stopDetectCardUseCase.runInfallible(StopDetectCardParams())
        } catch (e: Exception) {
            Timber.w(e, "⏰ [SdkTokenRefreshScheduler] StopDetectCard threw — continuing with invalidation")
        }

        // Step 2: invalidate the in-memory flag. Next call to awaitInitialization() /
        // ensureInitialized() will run the full init sequence (the same path used at
        // every app launch). No payment code is touched.
        initializationManager.invalidateForRefresh()

        return RefreshOutcome.Invalidated(ageHours = ageMs / 3_600_000L)
    }

    /**
     * Result of one evaluation. Surfaced via [lastRefreshOutcome] for tests and diagnostics.
     */
    sealed class RefreshOutcome {
        object SkippedFlagAlreadyFalse : RefreshOutcome()
        object SkippedCriticalInProgress : RefreshOutcome()
        object SkippedNoTimestamp : RefreshOutcome()
        data class SkippedFresh(val ageHours: Long) : RefreshOutcome()
        object SkippedRaceLost : RefreshOutcome()
        data class Invalidated(val ageHours: Long) : RefreshOutcome()
        data class Error(val exceptionClass: String) : RefreshOutcome()
    }
}

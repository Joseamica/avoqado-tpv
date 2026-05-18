package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.angelpay.angelpaysdk.models.MerchantSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the active AngelPay merchant.
 *
 * Synchronizes:
 *   - SDK session state (via [AngelPaySdkGateway.switchMerchant] / [AngelPaySdkGateway.selectMerchant])
 *   - Room cache ([AngelPayMerchantCacheDao]) for cold-start UI
 *   - In-memory `StateFlow<Int?>` for ViewModel observation
 *
 * **D2 race protection (spec §18.1):**
 *   - [operationMutex] serializes select + switch operations
 *   - [switchActiveMerchant] rejects with [SwitchBlockedDuringChargeError]
 *     if a payment is currently charging (read via [PaymentStateProvider])
 *   - 8s watchdog ([withTimeoutOrNull]) wraps SDK calls — fails with
 *     [SwitchTimeoutError] without updating active id
 *   - Multi-tap: a new switch cancels the previous in-flight switch coroutine
 *
 * The inverse direction (payment must wait for in-flight switch) is handled
 * by the ViewModel in Task 32, NOT here.
 *
 * **D6 periodic refresh (spec §18.5):**
 *   - [startPeriodicRefresh] schedules [fetchAndCacheMerchants] every 15 min
 *     while app is foreground
 *   - [stopPeriodicRefresh] cancels — called on logout/auth failure
 *   - [refreshBeforeSelector] is an on-demand alias for the switcher sheet
 */
@Singleton
class AngelPayMerchantRepository @Inject constructor(
    private val sdkGateway: AngelPaySdkGateway,
    private val cacheDao: AngelPayMerchantCacheDao,
    private val paymentStateProvider: PaymentStateProvider,
) {
    private val operationMutex = Mutex()

    private val _activeAngelPayMerchantId = MutableStateFlow<Int?>(null)
    /** Authoritative active merchant ID — updated on every successful select/switch, cleared on logout/auth failure. */
    val activeAngelPayMerchantId: StateFlow<Int?> = _activeAngelPayMerchantId.asStateFlow()

    private val _inFlightSwitch = MutableStateFlow<Int?>(null)
    /** Target merchant ID for a switch currently in flight, or null when idle. */
    val inFlightSwitch: StateFlow<Int?> = _inFlightSwitch.asStateFlow()

    private var currentSwitchJob: Job? = null
    private var periodicRefreshJob: Job? = null

    /** Observe the cached merchant list reactively — backs the merchant switcher UI. */
    fun observeCachedMerchants(): Flow<List<MerchantSummary>> =
        cacheDao.observeAll().map { it.map(::toSdkSummary) }

    /**
     * Fetch fresh merchant list from the SDK, replace the Room cache, sync active marker.
     * Called by:
     *   - Initial auth flow (after authenticateSimple succeeds)
     *   - Periodic refresh (D6)
     *   - On-demand from [refreshBeforeSelector]
     */
    suspend fun fetchAndCacheMerchants(): Result<List<MerchantSummary>> =
        sdkGateway.getUserMerchants().onSuccess { merchants ->
            cacheDao.replaceAll(merchants.map(::toEntity))
            merchants.firstOrNull { it.isActive }?.id?.let {
                _activeAngelPayMerchantId.value = it
            }
        }

    /** Convenience wrapper used by the switcher sheet right before showing the list (D6). */
    suspend fun refreshBeforeSelector(): Result<List<MerchantSummary>> = fetchAndCacheMerchants()

    /**
     * Finalize the initial merchant selection after `AuthenticateSimpleResult.MerchantSelectionRequired`.
     * Different from [switchActiveMerchant]: this is the FIRST selection ever (no previous active merchant).
     */
    suspend fun completeInitialSelection(merchantId: Int, temporaryToken: String): Result<Unit> =
        operationMutex.withLock {
            sdkGateway.selectMerchant(merchantId, temporaryToken).onSuccess {
                _activeAngelPayMerchantId.value = merchantId
                cacheDao.markActive(merchantId)
            }
        }

    /**
     * Switch the active merchant mid-shift.
     *
     * Rejection cases (do NOT call SDK):
     *   - [SwitchBlockedDuringChargeError] if a payment is in flight (D2)
     *
     * Failure cases (call SDK, fail):
     *   - [SwitchTimeoutError] if SDK call > 8s
     *   - Pass-through of SDK errors (categorized by [AngelPaySdkGateway])
     *
     * Success: update [_activeAngelPayMerchantId] + [cacheDao.markActive].
     *
     * Multi-tap: if called while a previous switch is in flight, the previous
     * coroutine is cancelled and a fresh switch starts.
     */
    suspend fun switchActiveMerchant(merchantId: Int): Result<Unit> = coroutineScope {
        // Multi-tap: cancel the previous in-flight switch if any
        currentSwitchJob?.cancel()

        operationMutex.withLock {
            if (paymentStateProvider.isCharging()) {
                return@withLock Result.failure(SwitchBlockedDuringChargeError)
            }

            val previousActive = _activeAngelPayMerchantId.value
            _inFlightSwitch.value = merchantId

            val deferred: Deferred<Result<Unit>> = async {
                withTimeoutOrNull(8_000L) {
                    sdkGateway.switchMerchant(merchantId)
                } ?: Result.failure(SwitchTimeoutError(merchantId))
            }
            currentSwitchJob = deferred

            try {
                val result = deferred.await()
                result
                    .onSuccess {
                        _activeAngelPayMerchantId.value = merchantId
                        cacheDao.markActive(merchantId)
                    }
                    .onFailure {
                        // Keep previous active in cache + state. Don't surface false confidence.
                        if (previousActive != null) cacheDao.markActive(previousActive)
                    }
                result
            } finally {
                _inFlightSwitch.value = null
                currentSwitchJob = null
            }
        }
    }

    /** Start the periodic 15-min refresh job (D6). Idempotent — restarting cancels the previous job. */
    fun startPeriodicRefresh(scope: CoroutineScope, lifecycleOwner: LifecycleOwner) {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = scope.launch {
            while (isActive) {
                delay(15 * 60 * 1000L)
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    fetchAndCacheMerchants()
                }
            }
        }
    }

    fun stopPeriodicRefresh() {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = null
    }

    /** Called from AngelPayAuthRepository on logout / unrecoverable auth failure. */
    fun clearActive() {
        _activeAngelPayMerchantId.value = null
    }

    private fun toEntity(m: MerchantSummary) =
        AngelPayMerchantCacheEntity(
            merchantId = m.id,
            name = m.name,
            affiliationNumber = m.affiliationNumber,
            isActive = m.isActive,
        )

    private fun toSdkSummary(e: AngelPayMerchantCacheEntity) =
        MerchantSummary(
            id = e.merchantId,
            name = e.name,
            affiliationNumber = e.affiliationNumber,
            isActive = e.isActive,
        )
}

/** Returned when [AngelPayMerchantRepository.switchActiveMerchant] is called while a payment is in flight. The ViewModel's payment-time guard ensures the inverse (payment waits for switch). */
object SwitchBlockedDuringChargeError : RuntimeException(
    "Cannot switch merchant while a payment is in progress"
)

/** Returned when the SDK switch call doesn't complete within 8 seconds. */
data class SwitchTimeoutError(val targetMerchantId: Int) : RuntimeException(
    "Merchant switch to $targetMerchantId timed out after 8s"
)

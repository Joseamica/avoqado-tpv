package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.angelpay.angelpaysdk.models.MerchantSummary
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import timber.log.Timber
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
     *   - [SwitchTimeoutError] if SDK call > [ANGELPAY_SWITCH_TIMEOUT_MS]
     *   - Pass-through of SDK errors (categorized by [AngelPaySdkGateway])
     *   Either way the live session is re-read first ([conciliarTrasCambioFallido]): if AngelPay is
     *   already on the target, it's a success.
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
            // Multi-AngelPay accounts per venue (2026-05-19): idempotent
            // short-circuit when the cashier picks the merchant that's already
            // active in the SDK session. Without this, `sdkGateway.switchMerchant`
            // throws `AuthenticationError("Error al cambiar de comercio")`
            // because AngelPay 1.0.5 rejects a switch to the same merchant.
            // Most common path: single-merchant account (the SDK auto-selects
            // its only merchant on auth, then the cashier confirms the same one
            // in MerchantSelectionContent and we'd otherwise re-fire the switch).
            if (previousActive == merchantId) {
                // Idempotent: SDK already on this merchant, no need to call it.
                cacheDao.markActive(merchantId)
                return@withLock Result.success(Unit)
            }

            // Single-merchant auth gap (2026-05-28): when the SDK auto-selects its
            // ONLY merchant during `authenticateSimple` Success (single-merchant
            // account, e.g. contacto@avoqado.io with merchant 61), it does so
            // WITHOUT routing through this repo, so `_activeAngelPayMerchantId`
            // stays null even though the SDK is already on that merchant. Picking
            // that same merchant in the UI then fires `switchMerchant(id)` →
            // AngelPay throws AuthenticationError("Error al cambiar de comercio")
            // because you cannot switch to the merchant you're already on. Detect
            // this by asking the SDK which merchant is currently active BEFORE
            // blindly switching. Only runs when the repo has no tracked active
            // merchant, so it never adds latency to a normal switch.
            if (previousActive == null) {
                if (comercioActivoEnSdk() == merchantId) {
                    _activeAngelPayMerchantId.value = merchantId
                    cacheDao.markActive(merchantId)
                    return@withLock Result.success(Unit)
                }
            }
            _inFlightSwitch.value = merchantId

            val deferred: Deferred<Result<Unit>> = async {
                withTimeoutOrNull(ANGELPAY_SWITCH_TIMEOUT_MS) {
                    sdkGateway.switchMerchant(merchantId)
                } ?: Result.failure(SwitchTimeoutError(merchantId))
            }
            currentSwitchJob = deferred

            try {
                val result = deferred.await()
                if (result.isSuccess) {
                    _activeAngelPayMerchantId.value = merchantId
                    cacheDao.markActive(merchantId)
                    result
                } else {
                    conciliarTrasCambioFallido(merchantId, previousActive, result)
                }
            } catch (cancelled: CancellationException) {
                // La pantalla se cerró (Atrás) o llegó otro toque a mitad del cambio: la petición pudo haber llegado
                // ya a AngelPay. Se anota dónde quedó la sesión antes de soltar, o la siguiente pantalla arranca en el
                // comercio viejo y el candado bloquea el cobro (N86, 7-oct-2026).
                withContext(NonCancellable) {
                    conciliarTrasCambioFallido(merchantId, previousActive, Result.failure(cancelled))
                }
                throw cancelled
            } finally {
                _inFlightSwitch.value = null
                currentSwitchJob = null
            }
        }
    }

    /** El comercio activo según la sesión viva del SDK; null si no se puede saber. */
    private suspend fun comercioActivoEnSdk(): Int? {
        val merchants = try {
            sdkGateway.getUserMerchants().getOrNull()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            null
        } ?: return null
        return merchants.firstOrNull { it.isActive }?.id ?: merchants.singleOrNull()?.id
    }

    /**
     * Un cambio fallido NO prueba que AngelPay siga en el comercio anterior (N86, 7-oct-2026): el
     * servidor cambia en ~1 s y lo lento es la preparación posterior del SDK, así que un corte por
     * tiempo o un error tardío pueden dejar la sesión YA en el destino — y entonces cada reintento
     * recibe 409 «Ya está operando con este comercio». Se le pregunta a la sesión viva y se anota
     * la verdad:
     *  - en el destino ⇒ el cambio está hecho;
     *  - en otro comercio ⇒ se anota ése (no el anterior) y se devuelve el error;
     *  - sin poder saberlo ⇒ se conserva el anterior.
     * Antes de cobrar, `sessionAlignedWithSelectedMerchant` vuelve a preguntar de todos modos.
     */
    private suspend fun conciliarTrasCambioFallido(
        targetId: Int,
        previousActive: Int?,
        failed: Result<Unit>,
    ): Result<Unit> {
        val enSdk = comercioActivoEnSdk()
        Timber.w(
            failed.exceptionOrNull(),
            "🔶 [AngelPay] switchMerchant(%d) falló; la sesión viva está en %s (anterior=%s)",
            targetId, enSdk ?: "desconocido", previousActive ?: "ninguno",
        )
        val verdad = enSdk ?: previousActive
        if (verdad != null) {
            _activeAngelPayMerchantId.value = verdad
            cacheDao.markActive(verdad)
        }
        return if (enSdk == targetId) Result.success(Unit) else failed
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

    /**
     * Seed [activeAngelPayMerchantId] from an already-fetched SDK merchant list
     * (e.g. the `getUserMerchants()` result the auth flow holds in hand). This
     * decouples active-merchant seeding from `fetchAndCacheMerchants()`, which only
     * ran as a side-effect of the optional "report discovered merchants" feature
     * (and silently skipped seeding when `reportApi` was null). Without a reliable
     * seed, the payment screen can't pre-select the merchant and the cashier must
     * manually pick even for a single-merchant account.
     *
     * Idempotent + safe: only sets when there's an active merchant in the list and
     * we don't already track it. Never clears.
     */
    suspend fun seedActiveMerchantFromSession(merchants: List<MerchantSummary>) {
        val activeId = merchants.firstOrNull { it.isActive }?.id
            ?: merchants.singleOrNull()?.id
            ?: return
        if (_activeAngelPayMerchantId.value == activeId) return
        _activeAngelPayMerchantId.value = activeId
        cacheDao.markActive(activeId)
    }

    /**
     * T26: el último comercio activo que se conoce — el de la sesión en memoria o, si ya se
     * limpió (logout, auth caída, proceso nuevo), el marcado en el caché de Room, que
     * sobrevive a reinicios. Sirve para que la recuperación de fondo vuelva a la cuenta de
     * ESE comercio en vez de a la primaria del venue.
     */
    suspend fun ultimoComercioActivoConocido(): Int? {
        _activeAngelPayMerchantId.value?.let { return it }
        return try {
            cacheDao.getAll().firstOrNull { it.isActive }?.merchantId
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            null // sin caché legible no se inventa un comercio
        }
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

/**
 * Tope de `switchMerchant`. Era 8 s; desde el SDK 1.0.20 el cambio incluye una preparación posterior
 * (GetOperations + runPostAuthSetup) que en la N86 con red lenta pasó de 7 s (7-oct-2026). Cortarla
 * a la mitad deja la sesión del SDK en el comercio nuevo con la preparación incompleta, así que el
 * tope sólo protege de un SDK colgado: la pantalla muestra «Cambiando cuenta…» mientras tanto.
 */
internal const val ANGELPAY_SWITCH_TIMEOUT_MS = 45_000L

/** Returned when the SDK switch call doesn't complete within [ANGELPAY_SWITCH_TIMEOUT_MS]. */
data class SwitchTimeoutError(val targetMerchantId: Int) : RuntimeException(
    "AngelPay no terminó de cambiar al comercio $targetMerchantId en ${ANGELPAY_SWITCH_TIMEOUT_MS / 1000} s"
)

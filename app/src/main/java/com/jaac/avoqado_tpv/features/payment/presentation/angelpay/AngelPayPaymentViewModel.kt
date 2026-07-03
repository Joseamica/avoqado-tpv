package com.jaac.avoqado_tpv.features.payment.presentation.angelpay

import android.content.Intent
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.annotation.VisibleForTesting
import com.angelpay.angelpaysdk.models.PaymentRequest
import com.angelpay.angelpaysdk.models.PaymentResult
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.CancelCryptoPaymentRequest
import com.jaac.avoqado_tpv.core.data.network.CryptoPaymentRequest
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.core.domain.TerminalConfig
import com.jaac.avoqado_tpv.core.printer.PrinterManager
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.payment.data.api.PaymentApiService
import com.jaac.avoqado_tpv.features.payment.data.dto.SendReceiptRequest
import com.jaac.avoqado_tpv.features.payment.data.dto.SendWhatsAppReceiptRequest
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayAuthRepository
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayAuthState
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayCredentials
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayErrorMapper
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayIntentBuilder
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayMerchantRepository
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayResult
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayResultParser
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPaySdkGateway
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.payment.domain.PaymentFlowGate
import com.jaac.avoqado_tpv.features.payment.domain.PrePaymentNextStep
import com.jaac.avoqado_tpv.features.payment.domain.model.CardBrand
import com.jaac.avoqado_tpv.features.payment.domain.model.CardDetails
import com.jaac.avoqado_tpv.features.payment.domain.model.CardEntryMode
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import com.jaac.avoqado_tpv.features.payment.domain.repository.MerchantRepository
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordPaymentUseCase
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.angelpay.angelpaysdk.models.MerchantSummary
import timber.log.Timber
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

/**
 * ViewModel for AngelPay payment flow on Nexgo N86 terminals.
 *
 * **COMPLETELY ISOLATED from Blumon PaymentViewModel.**
 * Shares only stateless domain use cases (RecordPaymentUseCase) via DI.
 *
 * **Pre-payment flow (mirrors Blumon UX):**
 * initPayment → [Rating] → [Tip] → Merchant Selection → Card/Cash → Success
 *
 * Uses PaymentFlowGate for screen gating (same logic as Blumon).
 * Reuses ReviewScreen, TipScreen, MerchantSelectionContent composables.
 */
@HiltViewModel
class AngelPayPaymentViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val recordPaymentUseCase: RecordPaymentUseCase,
    private val shiftRepository: ShiftRepository,
    private val authRepository: AuthRepository,
    private val merchantRepository: MerchantRepository,
    private val secureStorage: SecureStorage,
    private val terminalConfigRepository: com.jaac.avoqado_tpv.core.domain.repository.TerminalConfigRepository,
    private val intentBuilder: AngelPayIntentBuilder,
    private val sdkGateway: AngelPaySdkGateway,
    private val angelPayAuthRepository: AngelPayAuthRepository,
    private val angelPayMerchantRepository: AngelPayMerchantRepository,
    private val paymentStateHolder: PaymentStateHolder,
    private val tpvSettingsRepository: TpvSettingsRepository,
    private val printerManager: PrinterManager,
    private val angelPayTicketBuilder: com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayTicketBuilder,
    private val paymentApiService: PaymentApiService,
    private val apiService: ApiService,
    private val socketManager: SocketManager,
) : ViewModel() {

    private val _state = MutableStateFlow<AngelPayPaymentState>(AngelPayPaymentState.Idle)
    val state: StateFlow<AngelPayPaymentState> = _state.asStateFlow()

    // ── Task 32 — exposed flows for Task 33 banner + switcher UI ─────
    /** Live auth state machine (Authenticated / SelectingMerchant / ConfigMismatchBanner / AuthError / …). */
    val authState: StateFlow<AngelPayAuthState> = angelPayAuthRepository.state

    /** Active merchant ID per AngelPay SDK session — null when no merchant selected yet. */
    val activeAngelPayMerchantId: StateFlow<Int?> = angelPayMerchantRepository.activeAngelPayMerchantId

    /** Target ID of a merchant switch currently in flight, or null when idle. */
    val inFlightSwitch: StateFlow<Int?> = angelPayMerchantRepository.inFlightSwitch

    /** Reactive list of cached AngelPay merchants for the switcher sheet. */
    val cachedMerchants: Flow<List<MerchantSummary>> = angelPayMerchantRepository.observeCachedMerchants()

    private val resultParser = AngelPayResultParser()

    // Merchant list for MerchantSelectionContent
    private val _merchants = MutableStateFlow<List<MerchantAccount>>(emptyList())
    val merchants: StateFlow<List<MerchantAccount>> = _merchants.asStateFlow()

    private val _currentMerchant = MutableStateFlow<MerchantAccount?>(null)
    val currentMerchant: StateFlow<MerchantAccount?> = _currentMerchant.asStateFlow()

    /**
     * Multi-AngelPay accounts per venue (2026-05-19) — synchronous flag flipped
     * to `true` at the VERY TOP of [selectMerchant] and reset in its `finally`.
     * The screen `OR`s this with `inFlightSwitch` and `authState.Authenticating`
     * to disable Tarjeta/Efectivo/Cripto immediately on the merchant tap.
     *
     * Why a third flag: `inFlightSwitch` only goes non-null INSIDE
     * `AngelPayMerchantRepository.switchActiveMerchant`'s `withLock` body,
     * which happens AFTER any `switchAccount` chain (which can itself take
     * ~256ms even on the no-op path). During that gap the operator can tap
     * Tarjeta → `startCardPayment` calls `setCharging(true)` → `selectMerchant`
     * finally gets to `switchActiveMerchant` → trips `SwitchBlockedDuringCharge`
     * → `_currentMerchant` reverted to null → `recordPayment` POST goes out
     * without `merchantAccountId` → backend 400. Reproduced 2026-05-19 with
     * a 256ms window between `Charging gate set` and `switchActiveMerchant`.
     */
    private val _selectionInProgress = MutableStateFlow(false)
    val selectionInProgress: StateFlow<Boolean> = _selectionInProgress.asStateFlow()

    // TpvSettings for tip suggestions / default tip percentage
    val tipSuggestions: List<Int> get() = tpvSettingsRepository.getCurrentSettings().tipSuggestions
    val defaultTipPercentage: Int? get() = tpvSettingsRepository.getCurrentSettings().defaultTipPercentage
    /**
     * Whether the print button should be visible on the AngelPay success
     * screen. Bifurcates by build flavor because PAX and Nexgo terminals
     * use completely different printer APIs.
     *
     * **PAX (Blumon) flavor** — defer to [PrinterManager.isPrinterAvailable]
     * which checks whether the PAX SDK's IPrinter handle initialized
     * successfully.
     *
     * **Nexgo (AngelPay) flavor** — detect by hardware model returned by
     * the AngelPay SDK device info. Nexgo's lineup has mixed printer
     * support and there is no `hasPrinter()` API in SDK 1.0.8 (manual §2):
     * - N86 → built-in thermal printer ✅
     * - N62 → no printer ❌
     *
     * **Previous bug (fixed 2026-05-27)**: the gate read
     * `BuildConfig.ENABLE_PAX_SDK && printerManager.isPrinterAvailable()`.
     * Because every Nexgo build sets `ENABLE_PAX_SDK = false`, the short-
     * circuit hid the print button on every Nexgo terminal — including
     * N86 which physically has a thermal printer. The new check uses
     * hardware capability instead of build flavor.
     *
     * When you add new Nexgo models with printers, append them to
     * [NEXGO_MODELS_WITH_PRINTER] below.
     */
    val canPrintReceipt: Boolean
        get() = runCatching {
            if (BuildConfig.ENABLE_PAX_SDK) {
                printerManager.isPrinterAvailable()
            } else {
                val model = com.angelpay.angelpaysdk.AngelPaySDK.getDeviceInfo()
                    ?.model
                    ?.uppercase()
                    .orEmpty()
                NEXGO_MODELS_WITH_PRINTER.any { it in model }
            }
        }.getOrElse { error ->
            Timber.w(error, "🔶 [AngelPay] Printer capability check failed; disabling print action")
            false
        }

    // Cached payment context
    private var pendingAmount: BigDecimal = BigDecimal.ZERO
    private var pendingTip: BigDecimal = BigDecimal.ZERO
    private var pendingRating: Int? = null
    private var pendingOrderId: String? = null
    private var pendingOrderNumber: String? = null
    private var cachedShiftId: String? = null
    private var cachedVenueId: String? = null
    private var cachedStaffId: String? = null

    // 🛡️ IDEMPOTENCY KEY (2026-04-08) — Stripe/Square/Toast pattern
    // UUID v4 generated ONCE per logical payment attempt and reused on every retry.
    // Generated in initPayment() and cleared in resetPayment(). Cleared explicitly so
    // each new attempt gets a fresh key.
    private var currentPaymentAttemptId: String? = null
    // Consumes external payment callback only once per launched attempt.
    private var consumedResultAttemptId: String? = null

    // 🔐 D308 mid-payment session recovery (2026-07-03). The SDK can report
    // isAuthenticated()=true locally while the server-side session is dead, so the
    // expiry only surfaces as AppErrorCatalog D308 in the PaymentResult. We keep the
    // last launched request to re-launch it once after handleAuthExpiry(); the
    // attempt id guard caps recovery at ONE re-auth per payment attempt.
    private var lastSdkLaunchRequest: PaymentRequest? = null
    private var lastSdkLaunchUsedTipFallback: Boolean = false
    private var authExpiryRetriedAttemptId: String? = null

    // 🪙 B4Bit request ID currently in-flight. Used to filter Socket.IO events
    // so we only react to OUR pending crypto payment.
    private var currentCryptoRequestId: String? = null

    private fun backendRecordFailureState(paymentLabel: String, error: Throwable): AngelPayPaymentState.Error {
        val detail = error.message ?: "error desconocido"
        return AngelPayPaymentState.Error(
            message = "$paymentLabel fue procesado, pero no se pudo registrar en Avoqado. No vuelvas a cobrar; revisa transacciones o avisa al supervisor. Detalle: $detail",
            canRetry = false,
        )
    }

    /**
     * Get the current idempotency key, generating one if there isn't one yet.
     * Idempotent — multiple calls during the same attempt return the SAME UUID.
     * Cleared by resetPayment().
     */
    private fun ensurePaymentAttemptId(): String {
        val existing = currentPaymentAttemptId
        if (existing != null) {
            Timber.d("🛡️ [AngelPay Idempotency] Reusing existing paymentAttemptId=$existing")
            return existing
        }
        val generated = java.util.UUID.randomUUID().toString()
        currentPaymentAttemptId = generated
        Timber.i("🛡️ [AngelPay Idempotency] Generated new paymentAttemptId=$generated")
        return generated
    }

    private fun isSdkFlowEnabled(): Boolean {
        val settings = tpvSettingsRepository.getCurrentSettings()
        return BuildConfig.ANGELPAY_SDK_ENABLED && settings.angelPaySdkEnabled
    }

    private fun isAppToAppFallbackEnabled(): Boolean {
        val settings = tpvSettingsRepository.getCurrentSettings()
        return BuildConfig.ANGELPAY_SDK_FALLBACK_ENABLED && settings.angelPaySdkFallbackEnabled
    }

    init {
        // Load AngelPay merchants
        viewModelScope.launch {
            merchantRepository.getActiveMerchants().collect { allMerchants ->
                val angelPayMerchants = allMerchants.filter { it.processorType == ProcessorType.ANGELPAY }
                _merchants.value = angelPayMerchants
                // Auto-select if single merchant
                if (angelPayMerchants.size == 1 && _currentMerchant.value == null) {
                    _currentMerchant.value = angelPayMerchants.first()
                }
                // Hydrate _currentMerchant from the persisted active merchant
                // when there's more than one option. Without this hydration:
                //   - The green banner ("AngelPay: <X>") reads from
                //     `AngelPayMerchantRepository.activeAngelPayMerchantId`
                //     which IS persisted across screen entries.
                //   - But `_currentMerchant` lives only in this VM and stays
                //     null on first composition with multi-merchant accounts
                //     → the Tarjeta button reads `currentMerchant != null` →
                //     stays disabled even though the SDK is already
                //     authenticated to the merchant the banner shows.
                //   - The operator's only workaround was tapping "Cambiar
                //     cuenta" and re-selecting the SAME merchant just to
                //     trigger `selectMerchant()` which populates
                //     `_currentMerchant`.
                if (_currentMerchant.value == null && angelPayMerchants.size > 1) {
                    val activeId = angelPayMerchantRepository.activeAngelPayMerchantId.value
                    if (activeId != null) {
                        val matched = angelPayMerchants.firstOrNull { merchant ->
                            merchant.externalMerchantId?.toIntOrNull() == activeId
                        }
                        if (matched != null) {
                            _currentMerchant.value = matched
                            Timber.d(
                                "🔶 [AngelPay] Hydrated _currentMerchant from cached activeId=%s → %s",
                                activeId,
                                matched.displayName,
                            )
                        }
                    }
                }
                Timber.d("🔶 [AngelPay] Merchants loaded: ${angelPayMerchants.size} AngelPay")
            }
        }

        // Mirror late changes in `activeAngelPayMerchantId` into
        // `_currentMerchant` so the Tarjeta button enables even when the
        // SDK finishes its auth/cache refresh AFTER the merchants list
        // already loaded (race with the collector above).
        //
        // Guard: only hydrate when `_currentMerchant` is null so an
        // in-flight `selectMerchant()` flow (which sets `_currentMerchant`
        // optimistically before the repo commits) is never clobbered.
        viewModelScope.launch {
            angelPayMerchantRepository.activeAngelPayMerchantId.collect { activeId ->
                if (activeId == null) return@collect
                if (_currentMerchant.value != null) return@collect
                val matched = _merchants.value.firstOrNull { merchant ->
                    merchant.externalMerchantId?.toIntOrNull() == activeId
                } ?: return@collect
                _currentMerchant.value = matched
                Timber.d(
                    "🔶 [AngelPay] Late-hydrated _currentMerchant from activeId=%s → %s",
                    activeId,
                    matched.displayName,
                )
            }
        }

        // 🪙 Subscribe to Socket.IO crypto events for B4Bit webhook callbacks.
        // Same logic as PaymentViewModel — only handle events whose requestId
        // matches our `currentCryptoRequestId`.
        viewModelScope.launch {
            socketManager.events.collect { event ->
                when (event) {
                    is SocketEvent.CryptoPaymentConfirmed -> {
                        Timber.i("🪙 [AngelPay Socket] Crypto confirmed: ${event.requestId}")
                        handleCryptoPaymentConfirmed(event)
                    }
                    is SocketEvent.CryptoPaymentFailed -> {
                        Timber.w("🪙 [AngelPay Socket] Crypto failed: ${event.requestId} - ${event.reason}")
                        handleCryptoPaymentFailed(event)
                    }
                    else -> Unit // Other events handled elsewhere
                }
            }
        }
    }

    // ── Initialization ────────────────────────────────────────────────

    /**
     * Start payment flow: validate shift → determine first pre-payment step.
     *
     * @param amount Payment amount as string (e.g., "150.00")
     * @param orderId Optional order ID (null = fast payment)
     * @param orderNumber Optional order number for display
     */
    fun initPayment(
        amount: String,
        orderId: String? = null,
        orderNumber: String? = null,
    ) {
        viewModelScope.launch {
            Timber.i("🔶 [AngelPay] initPayment | amount=$amount, orderId=$orderId")

            val amountDecimal = amount.toBigDecimalOrNull()
            if (amountDecimal == null || amountDecimal <= BigDecimal.ZERO) {
                _state.value = AngelPayPaymentState.Error("Monto invalido")
                return@launch
            }

            // 1. Validate shift
            val venueId = authRepository.getVenueId()
            if (venueId == null) {
                _state.value = AngelPayPaymentState.Error("Error: No hay venue activo")
                return@launch
            }

            val staffId = authRepository.getStaffId()
            if (staffId == null) {
                _state.value = AngelPayPaymentState.Error("Error: No hay staff activo")
                return@launch
            }

            // Respect the venue's `enableShifts` setting — when shifts are
            // disabled in dashboard, skip the open-shift requirement entirely.
            // Without this guard the operator hit "Debes abrir un turno" even
            // though turnos was OFF, and the only fix was opening a shift just
            // to bypass the gate. Reproduced 2026-05-20 on `Avoqado Full`
            // venue with enableShifts=false (visible in TerminalConfig logs).
            val shiftsEnabled = tpvSettingsRepository.getCurrentSettings().enableShifts
            val shift = if (shiftsEnabled) {
                val shiftResult = withContext(Dispatchers.IO) {
                    shiftRepository.getCurrentShift(venueId)
                }
                val resolved = shiftResult.getOrNull()
                if (resolved == null) {
                    _state.value = AngelPayPaymentState.Error(
                        message = "Debes abrir un turno antes de cobrar",
                        canRetry = false,
                        showOpenShiftButton = true,
                    )
                    return@launch
                }
                resolved
            } else {
                null
            }

            // 2. Validate AngelPay credentials.
            //
            // Per spec §4.5b, the AngelPay PIN MUST NOT be persisted to SecureStorage —
            // it lives only in `TerminalConfigRepository.cachedAngelPayAuth` (in-memory,
            // populated from the backend's `/tpv/terminals/:serial/config` response). The
            // previous check against `secureStorage.getAngelPayCredentials()` always
            // returned null and dead-ended the flow with "No hay credenciales de
            // AngelPay configuradas" before `ensureAuthenticated()` ever ran.
            //
            // We do a soft check here for UX (instant error feedback) but the real
            // self-heal happens in `AngelPayAuthRepository.ensureAuthenticated()` — if
            // the cache is empty, it forces a fresh config fetch and retries the resolver.
            // So this guard only fires when EVEN the self-heal can't get creds (no
            // ACTIVE AngelPayUserAccount for the venue, or terminal not NEXGO).
            val backendAuth = terminalConfigRepository.getCachedAngelPayAuth()
            if (backendAuth == null) {
                Timber.w("🔶 [AngelPay] cachedAngelPayAuth is null — ensureAuthenticated will attempt a self-heal refresh")
                // Do NOT early-return — let ensureAuthenticated handle the missing-cache
                // path. It logs the resolver path clearly and surfaces a proper AuthError
                // state that the banner/screen renders.
            }

            // 3. Cache context
            pendingAmount = amountDecimal
            pendingTip = BigDecimal.ZERO
            pendingRating = null
            pendingOrderId = orderId
            pendingOrderNumber = orderNumber
            cachedShiftId = shift?.id
            cachedVenueId = venueId
            cachedStaffId = staffId

            // 🛡️ IDEMPOTENCY KEY (2026-04-08): Generate the UUID NOW for this payment attempt.
            // Reused by both cash and card flows below, and persisted across all retries.
            ensurePaymentAttemptId()

            // 4. Use PaymentFlowGate to determine first screen
            val settings = tpvSettingsRepository.getCurrentSettings()
            val nextStep = PaymentFlowGate.nextAfterAmount(settings)
            Timber.d("🔶 [AngelPay] FlowGate.nextAfterAmount → $nextStep")

            navigateToStep(nextStep, amount)
        }
    }

    // ── Rating ────────────────────────────────────────────────────────

    fun selectRatingAndProceed(amount: String, rating: Int) {
        pendingRating = rating
        val settings = tpvSettingsRepository.getCurrentSettings()
        val nextStep = PaymentFlowGate.nextAfterRating(settings)
        Timber.d("🔶 [AngelPay] Rating=$rating → FlowGate.nextAfterRating → $nextStep")
        navigateToStep(nextStep, amount)
    }

    fun skipRating(amount: String) {
        pendingRating = null
        val settings = tpvSettingsRepository.getCurrentSettings()
        val nextStep = PaymentFlowGate.nextAfterRating(settings)
        Timber.d("🔶 [AngelPay] Rating skipped → FlowGate.nextAfterRating → $nextStep")
        navigateToStep(nextStep, amount)
    }

    // ── Tip ──────────────────────────────────────────────────────────

    fun updateTipSelection(percentage: Int) {
        val currentState = _state.value
        if (currentState is AngelPayPaymentState.CollectingTip) {
            val subtotal = currentState.amount.toBigDecimalOrNull() ?: return
            val tipAmount = subtotal.multiply(BigDecimal(percentage))
                .divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
            _state.value = currentState.copy(
                selectedTipPercentage = percentage,
                customTipAmount = null,
                tipAmount = tipAmount.toPlainString(),
            )
        }
    }

    fun updateCustomTip(customTip: String) {
        val currentState = _state.value
        if (currentState is AngelPayPaymentState.CollectingTip) {
            val tipDecimal = customTip.toBigDecimalOrNull() ?: BigDecimal.ZERO
            _state.value = currentState.copy(
                selectedTipPercentage = null,
                customTipAmount = customTip,
                tipAmount = tipDecimal.toPlainString(),
            )
        }
    }

    fun selectTipAndProceed(amount: String, rating: Int?, tipAmount: String) {
        val tipDecimal = tipAmount.toBigDecimalOrNull() ?: BigDecimal.ZERO
        pendingTip = tipDecimal
        pendingRating = rating

        val subtotal = amount.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val total = subtotal.add(tipDecimal)

        _state.value = AngelPayPaymentState.SelectingMerchant(
            subtotal = amount,
            tipAmount = tipDecimal.toPlainString(),
            totalAmount = total.toPlainString(),
            rating = rating,
        )
        Timber.d("🔶 [AngelPay] Tip=$tipDecimal → SelectingMerchant (total=$total)")
    }

    fun skipTip(amount: String, rating: Int?) {
        pendingTip = BigDecimal.ZERO
        pendingRating = rating

        _state.value = AngelPayPaymentState.SelectingMerchant(
            subtotal = amount,
            tipAmount = "0",
            totalAmount = amount,
            rating = rating,
        )
        Timber.d("🔶 [AngelPay] Tip skipped → SelectingMerchant")
    }

    // ── Merchant Selection ───────────────────────────────────────────

    /**
     * Task 32 (spec §6.7, §18.1): when the cashier picks a merchant from the
     * shared `MerchantSelectionContent` composable we must:
     *
     *   1. Update [_currentMerchant] for instant visual feedback in the picker.
     *   2. Branch on [AngelPayAuthRepository.state]:
     *      - `SelectingMerchant` → first ever pick after auth → call
     *        `completeMerchantSelection(merchantId, temporaryToken)` (finalizes
     *        the SDK-level `MerchantSelectionRequired` flow + runs D5 config
     *        validation).
     *      - `Authenticated` or `ConfigMismatchBanner` (still cashier-operable)
     *        → mid-shift switch → call
     *        `AngelPayMerchantRepository.switchActiveMerchant(merchantId)`
     *        (D2 mutex-guarded + rejects during charge + 8s watchdog).
     *      - Any other state → cannot switch → emit [Error].
     *   3. On failure: revert [_currentMerchant] to the previous selection so
     *      the picker doesn't show stale confidence, and emit [Error].
     *
     * Non-AngelPay merchants (e.g., misconfigured `externalMerchantId`) trip
     * [MerchantAccount.requireAngelpayMerchantId]; we surface that as a typed
     * error rather than crashing the picker.
     */
    /**
     * Task 34 — bridge from [AngelPayMerchantSwitcherSheet] (which yields a raw
     * AngelPay merchant `Int` id) to the existing [selectMerchant] flow (which
     * takes the Avoqado [MerchantAccount] domain). Resolves the corresponding
     * AngelPay merchant from [_merchants]; no-op if none matches.
     */
    fun selectMerchantByAngelPayId(angelpayMerchantId: Int) {
        val merchantAccount = _merchants.value.firstOrNull { account ->
            account.processorType == ProcessorType.ANGELPAY &&
                account.externalMerchantId?.toIntOrNull() == angelpayMerchantId
        }
        if (merchantAccount == null) {
            Timber.w(
                "🔶 [AngelPay] selectMerchantByAngelPayId($angelpayMerchantId): no matching MerchantAccount in _merchants (size=${_merchants.value.size})"
            )
            return
        }
        selectMerchant(merchantAccount)
    }

    /**
     * Task 34 — manual D6 refresh trigger surfaced from the switcher sheet's
     * empty-state retry button. Delegates to
     * [AngelPayMerchantRepository.refreshBeforeSelector] inside a viewModelScope
     * coroutine so the Compose layer never has to manage scopes for this.
     */
    fun refreshMerchants() {
        viewModelScope.launch {
            angelPayMerchantRepository.refreshBeforeSelector()
        }
    }

    fun selectMerchant(merchant: MerchantAccount) {
        // Flip selectionInProgress SYNCHRONOUSLY (before the coroutine launches)
        // so the screen disables Tarjeta/Efectivo/Cripto on the same frame as
        // the merchant tap. Must be set OUTSIDE viewModelScope.launch — once
        // the coroutine starts, we're already on a different scheduling tick
        // and the operator can race a Tarjeta tap.
        _selectionInProgress.value = true
        viewModelScope.launch {
            val targetId = runCatching { merchant.requireAngelpayMerchantId() }.getOrElse { err ->
                Timber.e(err, "🔶 [AngelPay] Merchant ${merchant.displayName} has invalid AngelPay id")
                _state.value = AngelPayPaymentState.Error(
                    message = "Merchant inválido para AngelPay: ${err.message}",
                    canRetry = false,
                )
                _selectionInProgress.value = false
                return@launch
            }

            val previousMerchant = _currentMerchant.value
            _currentMerchant.value = merchant
            Timber.d("🔶 [AngelPay] Merchant selected: ${merchant.displayName} (angelpayId=$targetId)")

            try {

            // Multi-AngelPay accounts per venue (2026-05-18): if this merchant is
            // owned by a DIFFERENT AngelPayUserAccount than the SDK is currently
            // authenticated as, swap SDK sessions FIRST so the SDK-level
            // merchant selection / payment routes to the right account. No-op
            // when the merchant's account matches the current one OR when the
            // merchant has no `angelpayUserAccountId` (legacy / un-backfilled
            // rows preserve the original single-account behavior).
            val targetAccountId = merchant.angelpayUserAccountId
            val currentAccountId = angelPayAuthRepository.getCurrentAngelPayAccountId()
            if (targetAccountId != null && currentAccountId != null && targetAccountId != currentAccountId) {
                Timber.tag("AngelPayAuth").i(
                    "selectMerchant: switching AngelPay session $currentAccountId → $targetAccountId for merchant ${merchant.displayName}",
                )
                val switchResult = angelPayAuthRepository.switchAccount(targetAccountId)
                if (switchResult.isFailure) {
                    Timber.e(
                        switchResult.exceptionOrNull(),
                        "🔶 [AngelPay] switchAccount FAILED; reverting merchant pick",
                    )
                    _currentMerchant.value = previousMerchant
                    _state.value = AngelPayPaymentState.Error(
                        message = "No se pudo cambiar a la cuenta AngelPay del merchant: ${switchResult.exceptionOrNull()?.message ?: "error desconocido"}",
                        canRetry = true,
                    )
                    return@launch
                }
            }

            val authState = angelPayAuthRepository.state.value
            val result: Result<Unit> = when (authState) {
                is AngelPayAuthState.SelectingMerchant -> {
                    Timber.i("🔶 [AngelPay] completeMerchantSelection(id=$targetId)")
                    angelPayAuthRepository.completeMerchantSelection(targetId, authState.temporaryToken)
                }
                is AngelPayAuthState.Authenticated,
                is AngelPayAuthState.ConfigMismatchBanner -> {
                    Timber.i("🔶 [AngelPay] switchActiveMerchant(id=$targetId)")
                    angelPayMerchantRepository.switchActiveMerchant(targetId)
                }
                else -> {
                    Timber.w("🔶 [AngelPay] Cannot switch in auth state $authState")
                    Result.failure(
                        IllegalStateException("No se puede cambiar de merchant en estado $authState"),
                    )
                }
            }

            result.onFailure { err ->
                Timber.e(err, "🔶 [AngelPay] Merchant selection failed; reverting to $previousMerchant")
                _currentMerchant.value = previousMerchant
                _state.value = AngelPayPaymentState.Error(
                    message = "No se pudo cambiar de merchant: ${err.message ?: "error desconocido"}",
                    canRetry = true,
                )
            }
            } finally {
                // Clear the synchronous gate on EVERY exit path (success,
                // failure, account-switch-failure early return). Keeping it
                // stuck `true` would permanently disable the payment buttons.
                _selectionInProgress.value = false
            }
        }
    }

    // ── Card Payment (AngelPay SDK + App-to-App Fallback) ───────────

    fun startCardPayment() {
        viewModelScope.launch {
            // Per spec §4.5b, AngelPay PIN MUST NOT be persisted to SecureStorage —
            // it lives only in `TerminalConfigRepository.cachedAngelPayAuth` (in-memory,
            // populated from backend's `/tpv/terminals/:serial/config`). The previous
            // check against `secureStorage.getAngelPayCredentials()` always returned
            // null and dead-ended the flow with "No hay credenciales de AngelPay"
            // AFTER the user had already passed rating/verification — the most
            // frustrating possible failure point. By the time startCardPayment runs:
            //   - HomeViewModel startup auth has already triggered ensureAuthenticated()
            //   - SDK is authenticated (verified by the green "AngelPay: Avoqado" banner)
            //   - SDK has session, knows the active merchant, ready to charge.
            // We do a soft check against the correct in-memory source; if even THAT
            // is null, the auth banner above has already surfaced AuthError state and
            // the cashier knows what's up — but we don't block the charge attempt
            // because the SDK itself manages auth.
            val backendAuth = terminalConfigRepository.getCachedAngelPayAuth()
            if (backendAuth == null) {
                Timber.w("🔶 [AngelPay] startCardPayment — cachedAngelPayAuth null, but SDK manages session independently; proceeding")
            }

            // 🛡️ Tag in-flight payment so any error during the AngelPay flow carries
            // processor / amount / merchant / order / attempt context in Crashlytics.
            com.jaac.avoqado_tpv.core.observability.CrashlyticsContext.setPaymentContext(
                processor = "ANGELPAY",
                method = "CARD",
                merchantId = _currentMerchant.value?.merchantAccountId,
                amount = pendingAmount.add(pendingTip).toPlainString(),
                orderId = pendingOrderId,
                attemptId = currentPaymentAttemptId,
            )

            // ── Task 32 — D2 payment-time guard (spec §18.1) ─────────────
            // If the Avoqado-selected merchant differs from the SDK-side
            // active merchant (e.g. switch still in flight), wait up to 8s
            // for the merchant repository to settle on the target. Without
            // this, an SDK call could route a charge to the previous merchant.
            if (!waitForMerchantToSettle()) {
                paymentStateHolder.setCharging(false)
                return@launch
            }

            // ── Task 32 — mark charging BEFORE SDK launch so any concurrent
            // `switchActiveMerchant` call rejects with SwitchBlockedDuringChargeError.
            val activeId = angelPayMerchantRepository.activeAngelPayMerchantId.value
                ?: _currentMerchant.value?.let {
                    runCatching { it.requireAngelpayMerchantId() }.getOrNull()
                } ?: -1
            paymentStateHolder.setCharging(true)
            _state.value = AngelPayPaymentState.Charging(
                merchantId = activeId,
                startedAt = System.currentTimeMillis(),
            )
            Timber.d("🔶 [AngelPay] Charging gate set | activeMerchant=$activeId")

            try {
                // The `credentials` parameter on the start*Payment functions is
                // vestigial — neither function actually reads from it (the SDK
                // manages session internally + the app-to-app intent uses different
                // wire fields). Synthesize a stub from the in-memory backend auth
                // so the signature is satisfied without leaking real PIN to the
                // signature contract. If/when those functions stop taking the param,
                // delete this stub.
                val stubCredentials = AngelPayCredentials(
                    email = backendAuth?.email.orEmpty(),
                    password = "", // never read — SDK has session, app-to-app uses commerceToken
                    affiliation = backendAuth?.accountId.orEmpty(),
                    commerceToken = "",
                )
                if (isSdkFlowEnabled()) {
                    startSdkCardPayment(stubCredentials)
                } else {
                    startAppToAppCardPayment(stubCredentials)
                }
            } catch (t: Throwable) {
                paymentStateHolder.setCharging(false)
                throw t
            }
            // NOTE: paymentStateHolder.setCharging(false) is cleared in the
            // result handlers (onAngelPayResult / onAngelPaySdkResult / error
            // emission inside startSdkCardPayment) via clearChargingOnTerminal().
        }
    }

    /**
     * Task 32 (spec §18.1) — wait until `AngelPayMerchantRepository.activeAngelPayMerchantId`
     * equals the cashier-selected merchant's AngelPay id. Returns false (and
     * emits an [AngelPayPaymentState.Error]) if the switch never settles in 8s.
     *
     * Short-circuits to `true` when:
     *   - There is no current AngelPay-targeted merchant (defensive — happens
     *     in tests / odd routes where SDK isn't expected to run).
     *   - The active merchant already matches the selected one.
     */
    private suspend fun waitForMerchantToSettle(): Boolean {
        val merchant = _currentMerchant.value ?: return true
        val targetId = runCatching { merchant.requireAngelpayMerchantId() }.getOrNull()
            ?: return true
        val currentActive = angelPayMerchantRepository.activeAngelPayMerchantId.value
        if (currentActive == targetId) return true

        Timber.i(
            "🔶 [AngelPay] Payment-time guard: waiting for merchant switch | target=$targetId, active=$currentActive"
        )
        _state.value = AngelPayPaymentState.Switching(
            targetMerchantId = targetId,
            previousMerchantId = currentActive,
        )

        val settled = withTimeoutOrNull(8_000L) {
            angelPayMerchantRepository.activeAngelPayMerchantId.first { it == targetId }
            true
        }

        return if (settled == true) {
            Timber.i("🔶 [AngelPay] Merchant switch settled (target=$targetId)")
            true
        } else {
            Timber.e("❌ [AngelPay] Merchant switch did not settle in 8s | target=$targetId")
            _state.value = AngelPayPaymentState.Error(
                message = "Cambio de merchant no se completó. Reintenta.",
                canRetry = true,
            )
            false
        }
    }

    /** Task 32 — single source of truth for clearing the charging flag on any terminal state. */
    private fun clearChargingOnTerminal() {
        paymentStateHolder.setCharging(false)
    }

    private suspend fun startSdkCardPayment(credentials: AngelPayCredentials) {
        val sdkEnv = if (BuildConfig.BLUMON_ENV == "PROD") "PROD" else "QA"
        val initResult = sdkGateway.ensureInitialized(appContext, sdkEnv)
        if (initResult.isFailure || !sdkGateway.isInitialized()) {
            val initError = initResult.exceptionOrNull()
            Timber.e(initError, "❌ [AngelPay SDK] Not initialized (env=$sdkEnv)")
            if (isAppToAppFallbackEnabled()) {
                Timber.w("↩️ [AngelPay] Falling back to app-to-app because SDK is not initialized")
                startAppToAppCardPayment(credentials)
                return
            }
            _state.value = AngelPayPaymentState.Error(
                message = initError?.message ?: "AngelPay SDK no está inicializado",
                canRetry = true,
            )
            clearChargingOnTerminal()
            return
        }

        val staffName = secureStorage.getStaffName()
        val paymentAttemptId = ensurePaymentAttemptId()

        // Task 31 — delegate auth orchestration to AngelPayAuthRepository so the
        // D4 resolver (backend-preferred + BuildConfig fallback), retry/backoff,
        // state machine, and post-auth config validation all live in one place.
        val authResult = angelPayAuthRepository.ensureAuthenticated()
        if (authResult.isFailure) {
            val error = authResult.exceptionOrNull()
            Timber.e(error, "❌ [AngelPay SDK] Authentication failed")
            if (isAppToAppFallbackEnabled()) {
                Timber.w("↩️ [AngelPay] Falling back to app-to-app after SDK auth failure")
                startAppToAppCardPayment(credentials)
                return
            }
            _state.value = AngelPayPaymentState.Error(
                message = error?.message ?: "No se pudo autenticar AngelPay SDK",
                canRetry = true,
            )
            clearChargingOnTerminal()
            return
        }

        val primaryRequest = sdkGateway.buildPaymentRequest(
            subtotal = pendingAmount,
            tip = pendingTip,
            waiter = staffName,
            reference = paymentAttemptId,
        )

        val validation = sdkGateway.validatePaymentIntent(
            context = appContext,
            request = primaryRequest,
        )

        if (validation.isSuccess) {
            launchSdkRequest(primaryRequest, usedQaTipFallback = false)
            return
        }

        val validationError = validation.exceptionOrNull()
        // Tip-unsupported fallback applies in ALL environments (incl. PROD): some
        // AngelPay merchants (e.g. SALON AMAENA) reject tips with C208 "Propina no
        // soportada". When that happens we re-issue the sale with the tip merged into
        // the subtotal (tip=0 to the processor). Our backend still records the real
        // subtotal/tip split. Self-gated: only fires when there's a tip AND the merchant
        // rejected it, so merchants that DO support tips are unaffected.
        val canUseTipFallback = pendingTip > BigDecimal.ZERO &&
                validationError != null &&
                sdkGateway.isTipUnsupportedError(validationError)

        if (canUseTipFallback) {
            Timber.w(
                "⚠️ [AngelPay SDK] Tip not supported by merchant, applying fallback (subtotal=total, tip=0) | error=${validationError?.message}"
            )
            val fallbackRequest = sdkGateway.buildQaTipFallbackRequest(
                subtotal = pendingAmount,
                tip = pendingTip,
                waiter = staffName,
                reference = paymentAttemptId,
            )

            val fallbackValidation = sdkGateway.validatePaymentIntent(
                context = appContext,
                request = fallbackRequest,
            )

            if (fallbackValidation.isSuccess) {
                launchSdkRequest(fallbackRequest, usedQaTipFallback = true)
                return
            }
        }

        Timber.e(validationError, "❌ [AngelPay SDK] createPaymentIntent validation failed")
        if (isAppToAppFallbackEnabled()) {
            Timber.w("↩️ [AngelPay] Falling back to app-to-app after SDK validation failure")
            startAppToAppCardPayment(credentials)
            return
        }

        _state.value = AngelPayPaymentState.Error(
            message = validationError?.message ?: "No se pudo iniciar el cobro con AngelPay SDK",
            canRetry = true,
        )
        clearChargingOnTerminal()
    }

    // Internal for tests: the sandbox variant compiles with ANGELPAY_SDK_ENABLED=false,
    // so startCardPayment can never reach the SDK path in testSandboxDebugUnitTest —
    // D308-recovery tests prime the launched-request state through this seam instead.
    @VisibleForTesting
    internal fun launchSdkRequest(
        request: PaymentRequest,
        usedQaTipFallback: Boolean,
    ) {
        lastSdkLaunchRequest = request
        lastSdkLaunchUsedTipFallback = usedQaTipFallback
        prepareExternalLaunch(ensurePaymentAttemptId())
        _state.value = AngelPayPaymentState.LaunchingAngelPaySdk(
            request = request,
            amount = pendingAmount.toPlainString(),
            tip = pendingTip.toPlainString(),
            orderId = pendingOrderId,
            orderNumber = pendingOrderNumber,
            usedQaTipFallback = usedQaTipFallback,
        )
        val totalAmount = pendingAmount.add(pendingTip)
        Timber.i(
            "🔶 [AngelPay SDK] Payment request ready | subtotal=$pendingAmount, tip=$pendingTip, total=$totalAmount, qaTipFallback=$usedQaTipFallback"
        )
    }

    private fun startAppToAppCardPayment(credentials: AngelPayCredentials) {
        val staffName = secureStorage.getStaffName()
        val paymentAttemptId = ensurePaymentAttemptId()
        val intent = intentBuilder.buildSaleIntent(
            amount = pendingAmount,
            tip = pendingTip,
            credentials = credentials,
            waiter = staffName,
            integratorReference = paymentAttemptId,
        )
        prepareExternalLaunch(paymentAttemptId)

        _state.value = AngelPayPaymentState.LaunchingAngelPay(
            intent = intent,
            amount = pendingAmount.toPlainString(),
            tip = pendingTip.toPlainString(),
            orderId = pendingOrderId,
            orderNumber = pendingOrderNumber,
        )
        val totalAmount = pendingAmount.add(pendingTip)
        Timber.i("🔶 [AngelPay] App-to-app intent built | subtotal=$pendingAmount, tip=$pendingTip, total=$totalAmount")
    }

    // ── Cash Payment ─────────────────────────────────────────────────

    fun startCashPayment() {
        viewModelScope.launch {
            _state.value = AngelPayPaymentState.ProcessingCash()

            com.jaac.avoqado_tpv.core.observability.CrashlyticsContext.setPaymentContext(
                processor = "ANGELPAY",
                method = "CASH",
                merchantId = _currentMerchant.value?.merchantAccountId,
                amount = pendingAmount.add(pendingTip).toPlainString(),
                orderId = pendingOrderId,
                attemptId = currentPaymentAttemptId,
            )

            val venueId = cachedVenueId ?: run {
                _state.value = AngelPayPaymentState.Error("Error: No hay venue activo")
                return@launch
            }
            val staffId = cachedStaffId ?: run {
                _state.value = AngelPayPaymentState.Error("Error: No hay staff activo")
                return@launch
            }

            val timestamp = System.currentTimeMillis()
            val paymentContext = PaymentContext.AngelPayPayment(
                venueId = venueId,
                staffId = staffId,
                shiftId = cachedShiftId,
                amount = pendingAmount,
                tip = pendingTip,
                rating = pendingRating,
                merchantAccountId = null, // Cash = no processor
                deviceSerialNumber = TerminalConfig.serialNumber,
                idempotencyKey = ensurePaymentAttemptId(), // 🛡️ Idempotency key (2026-04-08)
                cardDetails = CardDetails.CASH,
                authorizationCode = "EFECTIVO",
                referenceNumber = "CASH-$timestamp",
                orderId = pendingOrderId,
                orderNumber = pendingOrderNumber,
            )

            Timber.d("🔶 [AngelPay] Recording cash payment | amount=$pendingAmount, tip=$pendingTip")

            val recordResult = withContext(Dispatchers.IO) {
                recordPaymentUseCase(
                    context = paymentContext,
                    cardDetails = CardDetails.CASH,
                    authorizationNumber = "EFECTIVO",
                    referenceNumber = "CASH-$timestamp",
                )
            }

            val successState = AngelPayPaymentState.Success(
                authCode = "EFECTIVO",
                amount = pendingAmount.toPlainString(),
                tipAmount = if (pendingTip > BigDecimal.ZERO) pendingTip.toPlainString() else null,
                referenceNumber = "CASH-$timestamp",
                orderId = pendingOrderId,
                orderNumber = pendingOrderNumber,
                isCash = true,
            )

            recordResult.fold(
                onSuccess = { receipt ->
                    Timber.i("🔶 [AngelPay] Cash payment recorded | receipt=${receipt.receiptUrl}")
                    _state.value = successState.copy(receipt = receipt)
                },
                onFailure = { error ->
                    Timber.e(error, "🔶 [AngelPay] Cash payment failed to record to backend")
                    _state.value = backendRecordFailureState("El pago en efectivo", error)
                    Timber.w("🔶 [AngelPay] Cash payment NOT recorded to backend")
                },
            )
        }
    }

    // ── AngelPay Launchers Result ────────────────────────────────────

    /**
     * Called by Screen after launching the AngelPay intent.
     */
    fun onIntentLaunched() {
        _state.value = AngelPayPaymentState.WaitingForResult()
        Timber.d("🔶 [AngelPay] Intent launched, waiting for result...")
    }

    /**
     * Called by Screen when AngelPay returns via onActivityResult.
     */
    fun onAngelPayResult(resultCode: Int, data: Intent?) {
        viewModelScope.launch {
            if (!consumeResultForCurrentAttempt(source = "app_to_app")) return@launch
            Timber.i("🔶 [AngelPay] onAngelPayResult | resultCode=$resultCode")
            _state.value = AngelPayPaymentState.WaitingForResult(message = "Validando resultado del pago...")

            val result = resultParser.parse(resultCode, data)

            when (result) {
                is AngelPayResult.Success -> recordCardPayment(result)
                is AngelPayResult.Failure -> {
                    _state.value = AngelPayPaymentState.Error(
                        message = result.message,
                        canRetry = true,
                    )
                }
                is AngelPayResult.Cancelled -> {
                    _state.value = AngelPayPaymentState.Cancelled
                }
            }
            // Task 32 — clear D2 charging gate on any terminal outcome.
            clearChargingOnTerminal()
        }
    }

    fun onAngelPaySdkResult(result: PaymentResult) {
        viewModelScope.launch {
            if (!consumeResultForCurrentAttempt(source = "sdk_contract")) return@launch
            Timber.i("🔶 [AngelPay SDK] Result received | approved=${result.approved}, status=${result.status}")
            _state.value = AngelPayPaymentState.WaitingForResult(message = "Validando resultado del pago...")
            if (result.approved) {
                recordCardPayment(result)
            } else {
                val sessionExpiryShape = when {
                    AngelPayErrorMapper.isAuthError(result.callResult?.code) -> "D308"
                    // Pre-charge terminal-registration failure: same dead session, but the
                    // SDK hardcodes N400 there, so only the message identifies it. The SDK
                    // aborts before the gateway call → relaunching cannot double-charge.
                    AngelPayErrorMapper.isPreChargeRegisterFailure(result.message) -> "register"
                    else -> null
                }
                if (sessionExpiryShape != null &&
                    tryRecoverFromSessionExpiry(reason = sessionExpiryShape)
                ) {
                    // Re-authenticated and relaunched — NOT a terminal outcome,
                    // so the D2 charging gate stays set for the retried attempt.
                    return@launch
                }
                _state.value = AngelPayPaymentState.Error(
                    message = buildString {
                        append(result.message ?: "Pago rechazado")
                        result.callResult?.let { call ->
                            if (!call.code.isNullOrBlank()) {
                                append("\n\nSDK ${call.code}: ${call.message ?: "Sin detalle"}")
                            }
                        }
                    },
                    canRetry = true,
                )
            }
            // Task 32 — clear D2 charging gate on any terminal outcome.
            clearChargingOnTerminal()
        }
    }

    /**
     * Expired-session mid-payment recovery — AppErrorCatalog SDK 1.0.10/1.0.13.
     *
     * `ensureAuthenticated()` at [startSdkCardPayment] can't prevent this case: the SDK
     * still reports `isAuthenticated() = true` locally while the server-side session is
     * dead (typical after the terminal sits idle for hours between promoter sales), so
     * the expiry only surfaces in the PaymentResult. It takes TWO shapes ([reason]):
     * `"D308"` (explicit "Sesión Expirada" during the charge) and `"register"` (the
     * pre-charge terminal-registration failure, hardcoded N400 — see
     * [AngelPayErrorMapper.isPreChargeRegisterFailure]). Recovery = logout + full re-auth
     * ([AngelPayAuthRepository.handleAuthExpiry], which re-runs merchant selection and
     * key injection) and ONE relaunch of the exact same request — same
     * `paymentAttemptId`/reference by design, so backend idempotency is preserved.
     *
     * Returns true only when the payment was relaunched; on any other path (no cached
     * request, already retried this attempt, re-auth failed) the caller falls through
     * to the normal error state.
     */
    private suspend fun tryRecoverFromSessionExpiry(reason: String): Boolean {
        val attemptId = currentPaymentAttemptId ?: return false
        if (authExpiryRetriedAttemptId == attemptId) {
            Timber.w("🔐 [AngelPay SDK] Session-expiry shape ($reason) again after re-auth — surfacing error | attemptId=$attemptId")
            return false
        }
        val request = lastSdkLaunchRequest ?: return false
        authExpiryRetriedAttemptId = attemptId
        Timber.w("🔐 [AngelPay SDK] Session expired mid-payment (shape=$reason) — re-authenticating and relaunching once | attemptId=$attemptId")
        _state.value = AngelPayPaymentState.WaitingForResult(message = "Sesión expirada, renovando…")
        val reauth = angelPayAuthRepository.handleAuthExpiry()
        if (reauth.isFailure) {
            Timber.e(reauth.exceptionOrNull(), "❌ [AngelPay SDK] Re-auth after $reason failed")
            return false
        }
        launchSdkRequest(request = request, usedQaTipFallback = lastSdkLaunchUsedTipFallback)
        return true
    }

    private suspend fun recordCardPayment(result: AngelPayResult.Success) {
        _state.value = AngelPayPaymentState.RecordingPayment()

        val venueId = cachedVenueId ?: run {
            _state.value = AngelPayPaymentState.Error("Error: No hay venue activo")
            return
        }
        val staffId = cachedStaffId ?: run {
            _state.value = AngelPayPaymentState.Error("Error: No hay staff activo")
            return
        }

        // AngelPay doesn't return card details — use OTHER entryMode
        val cardDetails = CardDetails(
            maskedPan = "",
            cardBrand = CardBrand.UNKNOWN,
            entryMode = CardEntryMode.OTHER,
        )

        val merchantAccountId = _currentMerchant.value?.merchantAccountId
        Timber.d("🔶 [AngelPay] Recording card payment | merchantAccountId=$merchantAccountId")

        val paymentContext = PaymentContext.AngelPayPayment(
            venueId = venueId,
            staffId = staffId,
            shiftId = cachedShiftId,
            amount = pendingAmount,
            tip = pendingTip,
            rating = pendingRating,
            merchantAccountId = merchantAccountId,
            deviceSerialNumber = TerminalConfig.serialNumber,
            idempotencyKey = ensurePaymentAttemptId(), // 🛡️ Idempotency key (2026-04-08)
            cardDetails = cardDetails,
            authorizationCode = result.authorizationCode,
            referenceNumber = result.referenceNumber,
            orderId = pendingOrderId,
            orderNumber = pendingOrderNumber,
        )

        val recordResult = withContext(Dispatchers.IO) {
            recordPaymentUseCase(
                context = paymentContext,
                cardDetails = cardDetails,
                authorizationNumber = result.authorizationCode,
                referenceNumber = result.referenceNumber,
            )
        }

        val successState = AngelPayPaymentState.Success(
            authCode = result.authorizationCode,
            amount = pendingAmount.toPlainString(),
            tipAmount = if (pendingTip > BigDecimal.ZERO) pendingTip.toPlainString() else null,
            referenceNumber = result.referenceNumber,
            orderId = pendingOrderId,
            orderNumber = pendingOrderNumber,
            isCash = false,
        )

        recordResult.fold(
            onSuccess = { receipt ->
                Timber.i("🔶 [AngelPay] Card payment recorded | receipt=${receipt.receiptUrl}")
                _state.value = successState.copy(receipt = receipt)
            },
            onFailure = { error ->
                Timber.e(error, "🔶 [AngelPay] Card payment failed to record to backend")
                _state.value = backendRecordFailureState("El pago con tarjeta", error)
                Timber.w("🔶 [AngelPay] Card payment NOT recorded to backend")
            },
        )
    }

    private suspend fun recordCardPayment(result: PaymentResult) {
        _state.value = AngelPayPaymentState.RecordingPayment()

        val venueId = cachedVenueId ?: run {
            _state.value = AngelPayPaymentState.Error("Error: No hay venue activo")
            return
        }
        val staffId = cachedStaffId ?: run {
            _state.value = AngelPayPaymentState.Error("Error: No hay staff activo")
            return
        }

        val detectedBrand = result.cardBin?.let { CardBrand.fromBin(it) } ?: CardBrand.UNKNOWN
        val cardDetails = CardDetails(
            maskedPan = "",
            cardBrand = detectedBrand,
            entryMode = CardEntryMode.OTHER,
        )

        val merchantAccountId = _currentMerchant.value?.merchantAccountId
        Timber.d("🔶 [AngelPay SDK] Recording card payment | merchantAccountId=$merchantAccountId")

        val paymentContext = PaymentContext.AngelPayPayment(
            venueId = venueId,
            staffId = staffId,
            shiftId = cachedShiftId,
            amount = pendingAmount,
            tip = pendingTip,
            rating = pendingRating,
            merchantAccountId = merchantAccountId,
            deviceSerialNumber = TerminalConfig.serialNumber,
            idempotencyKey = ensurePaymentAttemptId(),
            cardDetails = cardDetails,
            authorizationCode = result.authCode ?: "",
            referenceNumber = result.reference ?: "",
            orderId = pendingOrderId,
            orderNumber = pendingOrderNumber,
        )

        val recordResult = withContext(Dispatchers.IO) {
            recordPaymentUseCase(
                context = paymentContext,
                cardDetails = cardDetails,
                authorizationNumber = result.authCode ?: "",
                referenceNumber = result.reference ?: "",
            )
        }

        val successState = AngelPayPaymentState.Success(
            authCode = result.authCode ?: "",
            amount = pendingAmount.toPlainString(),
            tipAmount = if (pendingTip > BigDecimal.ZERO) pendingTip.toPlainString() else null,
            referenceNumber = result.reference,
            orderId = pendingOrderId,
            orderNumber = pendingOrderNumber,
            isCash = false,
        )

        recordResult.fold(
            onSuccess = { receipt ->
                Timber.i("🔶 [AngelPay SDK] Card payment recorded | receipt=${receipt.receiptUrl}")
                _state.value = successState.copy(receipt = receipt)
            },
            onFailure = { error ->
                Timber.e(error, "🔶 [AngelPay SDK] Card payment failed to record to backend")
                _state.value = backendRecordFailureState("El pago con tarjeta", error)
                Timber.w("🔶 [AngelPay SDK] Card payment NOT recorded to backend")
            },
        )
    }

    // ── Navigation ───────────────────────────────────────────────────

    /**
     * Navigate backwards through pre-payment states.
     * @return true if navigated back one step, false if at first step (caller should navigate away)
     */
    fun goBackOneStep(): Boolean {
        val settings = tpvSettingsRepository.getCurrentSettings()
        val amount = pendingAmount.toPlainString()

        when (_state.value) {
            is AngelPayPaymentState.CollectingRating -> {
                // At first step — caller should navigate back
                resetPayment()
                return false
            }
            is AngelPayPaymentState.CollectingTip -> {
                if (settings.showReviewScreen) {
                    _state.value = AngelPayPaymentState.CollectingRating(
                        amount = amount,
                        rating = pendingRating ?: 0,
                    )
                    return true
                } else {
                    resetPayment()
                    return false
                }
            }
            is AngelPayPaymentState.SelectingMerchant -> {
                if (settings.showTipScreen) {
                    _state.value = AngelPayPaymentState.CollectingTip(
                        amount = amount,
                        rating = pendingRating,
                    )
                    return true
                } else if (settings.showReviewScreen) {
                    _state.value = AngelPayPaymentState.CollectingRating(
                        amount = amount,
                        rating = pendingRating ?: 0,
                    )
                    return true
                } else {
                    resetPayment()
                    return false
                }
            }
            else -> return false
        }
    }

    // ── Receipt Sending ─────────────────────────────────────────────

    private val _isSendingReceipt = MutableStateFlow(false)
    val isSendingReceipt: StateFlow<Boolean> = _isSendingReceipt.asStateFlow()

    private val _sendReceiptMessage = MutableStateFlow<String?>(null)
    val sendReceiptMessage: StateFlow<String?> = _sendReceiptMessage.asStateFlow()

    fun sendReceiptByEmail(email: String) {
        val currentState = _state.value
        if (currentState !is AngelPayPaymentState.Success) {
            _sendReceiptMessage.value = "Error: No hay pago completado"
            return
        }
        val receipt = currentState.receipt ?: run {
            _sendReceiptMessage.value = "Error: No hay recibo disponible"
            return
        }
        val venueId = cachedVenueId ?: return

        viewModelScope.launch {
            try {
                _isSendingReceipt.value = true
                val request = SendReceiptRequest(recipientEmail = email)
                val response = paymentApiService.sendReceipt(
                    venueId = venueId,
                    paymentId = receipt.paymentId,
                    request = request,
                )
                if (response.isSuccessful && response.body()?.success == true) {
                    _sendReceiptMessage.value = "✓ Recibo enviado a $email"
                } else {
                    _sendReceiptMessage.value = "Error: ${response.body()?.message ?: "Error al enviar recibo"}"
                }
            } catch (e: Exception) {
                _sendReceiptMessage.value = "Error de conexión: ${e.message}"
            } finally {
                _isSendingReceipt.value = false
            }
        }
    }

    fun sendReceiptByWhatsApp(phone: String) {
        val currentState = _state.value
        if (currentState !is AngelPayPaymentState.Success) {
            _sendReceiptMessage.value = "Error: No hay pago completado"
            return
        }
        val receipt = currentState.receipt ?: run {
            _sendReceiptMessage.value = "Error: No hay recibo disponible"
            return
        }
        val venueId = cachedVenueId ?: return

        viewModelScope.launch {
            try {
                _isSendingReceipt.value = true
                val request = SendWhatsAppReceiptRequest(recipientPhone = phone)
                val response = paymentApiService.sendReceiptWhatsApp(
                    venueId = venueId,
                    paymentId = receipt.paymentId,
                    request = request,
                )
                if (response.isSuccessful && response.body()?.success == true) {
                    _sendReceiptMessage.value = "✓ Recibo enviado por WhatsApp"
                } else {
                    _sendReceiptMessage.value = "Error: ${response.body()?.message ?: "Error al enviar por WhatsApp"}"
                }
            } catch (e: Exception) {
                _sendReceiptMessage.value = "Error de conexión: ${e.message}"
            } finally {
                _isSendingReceipt.value = false
            }
        }
    }

    fun clearSendReceiptMessage() {
        _sendReceiptMessage.value = null
    }

    // ── Printing ─────────────────────────────────────────────────────

    fun printReceipt(receipt: PaymentReceipt?) {
        viewModelScope.launch {
            if (receipt == null) {
                Timber.w("🔶 [AngelPay] No receipt to print")
                return@launch
            }
            if (!canPrintReceipt) {
                Timber.i("🔶 [AngelPay] Print skipped: printer not available on this terminal")
                _sendReceiptMessage.value = "Esta terminal no tiene impresora"
                return@launch
            }
            val state = _state.value as? AngelPayPaymentState.Success
            val authCode = state?.authCode.orEmpty()
            val referenceNumber = state?.referenceNumber
            val venueName = secureStorage.getVenueName()
            val staffName = secureStorage.getStaffName()
            withContext(Dispatchers.IO) {
                try {
                    if (BuildConfig.ENABLE_PAX_SDK) {
                        // PAX/Blumon path — keeps existing PrinterManager
                        // behavior so PAX flavors are byte-for-byte unchanged.
                        printerManager.printReceipt(
                            receiptUrl = receipt.receiptUrl,
                            amount = receipt.baseAmount.toPlainString(),
                            authCode = authCode,
                            tipAmount = receipt.tipAmount.toPlainString(),
                            referenceNumber = referenceNumber,
                            venueName = venueName,
                            staffName = staffName,
                            orderNumber = pendingOrderNumber,
                        )
                    } else {
                        // Nexgo path — print through the AngelPay SDK
                        // (manual §12). Same ticket builder used elsewhere
                        // (e.g. PaymentTransactionsScreen) so the receipt
                        // layout stays consistent across entry points.
                        //
                        // Pulls the same fiscal + venue metadata that the
                        // PAX printer uses (venue legal name, RFC,
                        // address, timezone for date/time stamp, app
                        // version), matching the look of the PAX receipt
                        // within the constraints of the AngelPay SDK 1.0.8
                        // print primitives (no QR/bitmap support yet —
                        // receipt URL surfaces as text instead).
                        val venueZone = com.jaac.avoqado_tpv.core.util.VenueTimeZone.get(secureStorage)
                        val ticket = angelPayTicketBuilder.buildPaymentTicket(
                            amount = receipt.baseAmount,
                            tipAmount = receipt.tipAmount,
                            authCode = authCode,
                            referenceNumber = referenceNumber,
                            venueName = venueName,
                            venueLegalName = secureStorage.getVenueLegalName(),
                            venueRfc = secureStorage.getVenueRfc(),
                            venueAddress = secureStorage.getVenueAddress(),
                            venueCity = secureStorage.getVenueCity(),
                            venueState = secureStorage.getVenueState(),
                            venueZipCode = secureStorage.getVenueZipCode(),
                            venueTimeZone = java.util.TimeZone.getTimeZone(venueZone),
                            staffName = staffName,
                            orderNumber = pendingOrderNumber,
                            receiptUrl = receipt.receiptUrl,
                            appVersionName = BuildConfig.VERSION_NAME,
                        )
                        com.angelpay.angelpaysdk.AngelPaySDK.printTicket(ticket)
                    }
                    Timber.i("🔶 [AngelPay] Receipt printed successfully")
                } catch (e: Exception) {
                    Timber.e(e, "🔶 [AngelPay] Failed to print receipt")
                }
            }
        }
    }

    // ── Crypto (B4Bit) ────────────────────────────────────────────────
    //
    // Hardware-agnostic flow — same backend as PAX/Blumon. The customer scans a
    // QR generated by B4Bit and pays with their crypto wallet. The TPV waits for
    // a Socket.IO event from the backend (which receives a webhook from B4Bit).
    //
    // Flow:
    //   SelectingMerchant → [tap "Cripto"] → processCryptoPayment()
    //     → GeneratingCryptoQR (loading) → backend returns paymentUrl
    //     → AwaitingCryptoPayment (showing QR) → Socket.IO confirms
    //     → Success
    //
    // Server already validates the $20 MXN minimum; the TPV also blocks tap below
    // that in MerchantSelectionContent for instant feedback.

    /**
     * Initiate a B4Bit crypto payment. Caller must be in SelectingMerchant state.
     */
    fun processCryptoPayment(totalAmount: String) {
        Timber.d("🪙 [AngelPay Crypto] Processing crypto payment: \$$totalAmount")

        // 🛡️ Reuse the existing idempotency key if one was generated earlier in
        // this attempt (e.g. user picked card, errored, then switched to crypto).
        ensurePaymentAttemptId()

        com.jaac.avoqado_tpv.core.observability.CrashlyticsContext.setPaymentContext(
            processor = "B4BIT",
            method = "CRYPTO",
            merchantId = _currentMerchant.value?.merchantAccountId,
            amount = totalAmount,
            orderId = pendingOrderId,
            attemptId = currentPaymentAttemptId,
        )

        viewModelScope.launch {
            try {
                val currentState = _state.value as? AngelPayPaymentState.SelectingMerchant
                    ?: throw IllegalStateException("Invalid state for crypto payment: ${_state.value}")

                val venueId = authRepository.getVenueId()
                val staffId = authRepository.getStaffId()
                if (venueId.isNullOrBlank() || staffId.isNullOrBlank()) {
                    _state.value = AngelPayPaymentState.Error(
                        message = "Tu sesión expiró.\n\nPor favor inicia sesión de nuevo.",
                        canRetry = false,
                    )
                    return@launch
                }

                // Validate shift is open (mirrors initPayment validation) —
                // skipped when venue has shifts disabled.
                val shiftsEnabled = tpvSettingsRepository.getCurrentSettings().enableShifts
                if (shiftsEnabled) {
                    val shift = withContext(Dispatchers.IO) { shiftRepository.getCurrentShift(venueId) }.getOrNull()
                    if (shift == null) {
                        _state.value = AngelPayPaymentState.Error(
                            message = "Debes abrir un turno antes de cobrar",
                            canRetry = false,
                            showOpenShiftButton = true,
                        )
                        return@launch
                    }
                }

                // Transition to GeneratingCryptoQR (loading)
                _state.value = AngelPayPaymentState.GeneratingCryptoQR(
                    subtotal = currentState.subtotal,
                    tipAmount = currentState.tipAmount,
                    totalAmount = currentState.totalAmount,
                    rating = currentState.rating,
                )

                // Convert MXN strings to centavos (server expects cents as Int)
                val amountCentavos = (BigDecimal(currentState.subtotal.replace(",", "")) * BigDecimal(100))
                    .toInt()
                val tipCentavos = currentState.tipAmount.replace(",", "").let { tip ->
                    if (tip.isBlank() || tip == "0") 0
                    else (BigDecimal(tip) * BigDecimal(100)).toInt()
                }

                val deviceSerial = secureStorage.getSerialNumber() ?: "UNKNOWN"

                val request = CryptoPaymentRequest(
                    amount = amountCentavos,
                    tip = if (tipCentavos > 0) tipCentavos else null,
                    staffId = staffId,
                    orderId = pendingOrderId,
                    orderNumber = pendingOrderNumber,
                    deviceSerialNumber = deviceSerial,
                )

                Timber.d("🪙 [AngelPay Crypto] Calling backend: amount=$amountCentavos centavos, tip=$tipCentavos")
                val response = apiService.initiateCryptoPayment(venueId, request)

                if (!response.isSuccessful || response.body()?.success != true) {
                    val errorMessage = response.errorBody()?.string() ?: "Error iniciando pago crypto"
                    throw Exception(errorMessage)
                }

                val cryptoData = response.body()?.data
                    ?: throw Exception("Server returned empty crypto payment data")

                Timber.i("✅ [AngelPay Crypto] B4Bit order created: requestId=${cryptoData.requestId}")

                currentCryptoRequestId = cryptoData.requestId

                _state.value = AngelPayPaymentState.AwaitingCryptoPayment(
                    requestId = cryptoData.requestId,
                    paymentId = cryptoData.paymentId,
                    paymentUrl = cryptoData.paymentUrl,
                    subtotal = currentState.subtotal,
                    tipAmount = currentState.tipAmount,
                    totalAmount = currentState.totalAmount,
                    rating = currentState.rating,
                    expiresAt = cryptoData.expiresAt,
                    expiresInSeconds = cryptoData.expiresInSeconds,
                    cryptoAddress = cryptoData.cryptoAddress,
                    cryptoSymbol = cryptoData.cryptoSymbol,
                )
            } catch (e: Exception) {
                Timber.e(e, "❌ [AngelPay Crypto] Failed to start crypto payment")
                _state.value = AngelPayPaymentState.Error(
                    message = "Error procesando pago crypto:\n\n${e.message ?: "Error desconocido"}",
                    canRetry = true,
                )
            }
        }
    }

    /**
     * Cancel a pending crypto payment (user-initiated or timeout).
     * Best-effort backend notification; clears state and returns to SelectingMerchant.
     */
    fun cancelCryptoPayment(reason: String? = null) {
        Timber.i("🪙 [AngelPay Crypto] Cancelling: ${currentCryptoRequestId ?: "no request ID"}")

        val currentState = _state.value
        if (currentState !is AngelPayPaymentState.AwaitingCryptoPayment &&
            currentState !is AngelPayPaymentState.GeneratingCryptoQR
        ) {
            Timber.w("🪙 [AngelPay Crypto] Cannot cancel — not in crypto state: $currentState")
            return
        }

        viewModelScope.launch {
            try {
                currentCryptoRequestId?.let { requestId ->
                    val venueId = authRepository.getVenueId() ?: return@let
                    val cancelRequest = CancelCryptoPaymentRequest(
                        requestId = requestId,
                        reason = reason ?: "User cancelled",
                    )
                    apiService.cancelCryptoPayment(venueId, cancelRequest)
                }
            } catch (e: Exception) {
                Timber.w(e, "⚠️ [AngelPay Crypto] Cancel notification failed (proceeding anyway)")
            }

            currentCryptoRequestId = null

            // Restore SelectingMerchant with previous values
            val (subtotal, tipAmount, totalAmount, rating) = when (currentState) {
                is AngelPayPaymentState.AwaitingCryptoPayment -> Quad(
                    currentState.subtotal, currentState.tipAmount, currentState.totalAmount, currentState.rating,
                )
                is AngelPayPaymentState.GeneratingCryptoQR -> Quad(
                    currentState.subtotal, currentState.tipAmount, currentState.totalAmount, currentState.rating,
                )
                else -> Quad("0", "0", "0", null) // unreachable
            }

            _state.value = AngelPayPaymentState.SelectingMerchant(
                subtotal = subtotal,
                tipAmount = tipAmount,
                totalAmount = totalAmount,
                rating = rating,
            )
        }
    }

    fun handleCryptoTimeout() {
        Timber.w("🪙 [AngelPay Crypto] Payment timed out")
        cancelCryptoPayment(reason = "Timeout - customer did not pay")
        _state.value = AngelPayPaymentState.Error(
            message = "El tiempo de pago expiró.\n\nEl cliente no completó el pago crypto a tiempo.",
            canRetry = true,
        )
    }

    private fun handleCryptoPaymentConfirmed(event: SocketEvent.CryptoPaymentConfirmed) {
        if (currentCryptoRequestId != event.requestId) {
            Timber.d("🪙 [AngelPay Crypto] Ignoring confirmation for different request: ${event.requestId}")
            return
        }
        val currentState = _state.value
        if (currentState !is AngelPayPaymentState.AwaitingCryptoPayment) {
            Timber.w("🪙 [AngelPay Crypto] Confirmation arrived but state=$currentState")
            return
        }

        Timber.i("🪙 [AngelPay Crypto] Confirmed: txHash=${event.txHash}")

        // Build receipt from currentState (source of truth for user intent — see PaymentViewModel
        // for the rationale on the subtotal/tip split).
        val subtotalBd = currentState.subtotal.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val tipBd = currentState.tipAmount.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val receipt = PaymentReceipt(
            paymentId = event.paymentId,
            receiptUrl = event.receiptUrl ?: "",
            accessKey = event.receiptAccessKey ?: "",
            amount = subtotalBd,
            tipAmount = tipBd,
        )

        _state.value = AngelPayPaymentState.Success(
            authCode = event.txHash ?: "CRYPTO-CONFIRMED",
            amount = currentState.totalAmount,
            tipAmount = currentState.tipAmount,
            receipt = receipt,
            referenceNumber = event.requestId,
            orderId = event.orderId,
            orderNumber = event.orderNumber,
            isCash = false,
        )

        currentCryptoRequestId = null
    }

    private fun handleCryptoPaymentFailed(event: SocketEvent.CryptoPaymentFailed) {
        if (currentCryptoRequestId != event.requestId) {
            Timber.d("🪙 [AngelPay Crypto] Ignoring failure for different request: ${event.requestId}")
            return
        }
        if (_state.value !is AngelPayPaymentState.AwaitingCryptoPayment) {
            Timber.w("🪙 [AngelPay Crypto] Failure arrived but state=${_state.value}")
            return
        }

        val message = when (event.status) {
            "EX" -> "El tiempo de pago expiró.\n\nEl cliente no completó el pago a tiempo."
            "OC" -> "Monto insuficiente.\n\nEl cliente envió menos de lo requerido."
            else -> "Pago crypto no completado.\n\n${event.reason}"
        }
        _state.value = AngelPayPaymentState.Error(message = message, canRetry = true)
        currentCryptoRequestId = null
    }

    // Tiny inline tuple to keep cancelCryptoPayment readable.
    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    // ── Retry ────────────────────────────────────────────────────────

    /**
     * "Reintentar" after an [AngelPayPaymentState.Error]. Returns to the
     * **payment-method step** ("Método de Pago") — NEVER back to rating/tip —
     * keeping the amount/tip/rating already captured AND the merchant already
     * selected. The cashier re-taps Tarjeta/Efectivo/Cripto (1 tap), or
     * switches merchant first if THEY choose to. We never auto-switch the
     * merchant on retry (user decision 2026-05-30): in multi-merchant a decline
     * on one comercio is a common reason to retry on another, but that's the
     * cashier's call, not ours.
     *
     * Idempotency: `currentPaymentAttemptId` is intentionally NOT cleared here,
     * so re-tapping Tarjeta reuses the same key (`ensurePaymentAttemptId()`) —
     * a missed processor approval de-dupes and the webhook's integratorReference
     * still matches the eventual Payment.
     *
     * The "charge approved but backend record failed" case is `canRetry = false`
     * (see [backendRecordFailureState]) → the button is hidden, so this is never
     * reached for it.
     *
     * (User feedback 2026-05-30: AngelPay retry bounced the cashier all the way
     * back to rating/tip. Blumon retry was already correct.)
     */
    fun retryAfterError() {
        if (pendingAmount > BigDecimal.ZERO) {
            Timber.i("🔁 [AngelPay] Retry → payment-method step (merchant unchanged)")
            navigateToStep(PrePaymentNextStep.SELECT_MERCHANT, pendingAmount.toPlainString())
        } else {
            Timber.w("🔁 [AngelPay] Retry with no cached context → full reset")
            resetPayment()
        }
    }

    // ── Reset ────────────────────────────────────────────────────────

    fun resetPayment() {
        pendingAmount = BigDecimal.ZERO
        pendingTip = BigDecimal.ZERO
        pendingRating = null
        pendingOrderId = null
        pendingOrderNumber = null
        cachedShiftId = null
        cachedVenueId = null
        cachedStaffId = null
        currentPaymentAttemptId = null // 🛡️ Clear idempotency key so the next attempt generates a fresh one
        consumedResultAttemptId = null
        currentCryptoRequestId = null  // 🪙 Drop in-flight crypto request so old socket events are ignored
        lastSdkLaunchRequest = null    // 🔐 D308 recovery state — never reuse a request across attempts
        lastSdkLaunchUsedTipFallback = false
        authExpiryRetriedAttemptId = null
        // Do NOT clear _currentMerchant: the merchant account selection is independent of
        // the payment attempt. Clearing it here breaks retry — init{} auto-selects only when
        // the merchants Flow emits a NEW value, which doesn't happen on retry, leaving the
        // card button permanently disabled when there's a single merchant (selector hidden).
        _isSendingReceipt.value = false
        _sendReceiptMessage.value = null
        _state.value = AngelPayPaymentState.Idle
    }

    private fun prepareExternalLaunch(attemptId: String) {
        consumedResultAttemptId = null
        Timber.d("🛡️ [AngelPay] Prepared external launch | paymentAttemptId=$attemptId")
    }

    private fun consumeResultForCurrentAttempt(source: String): Boolean {
        val attemptId = currentPaymentAttemptId
        if (attemptId == null) {
            val terminalState = _state.value is AngelPayPaymentState.RecordingPayment ||
                    _state.value is AngelPayPaymentState.Success
            if (terminalState) {
                Timber.w("🛡️ [AngelPay] Ignoring duplicate $source result without attemptId (terminal state)")
                return false
            }
            Timber.w("⚠️ [AngelPay] Missing paymentAttemptId when consuming $source result; processing anyway")
            return true
        }
        if (consumedResultAttemptId == attemptId) {
            Timber.w("🛡️ [AngelPay] Duplicate $source result ignored | paymentAttemptId=$attemptId")
            return false
        }
        consumedResultAttemptId = attemptId
        return true
    }

    // ── Internal helpers ─────────────────────────────────────────────

    private fun navigateToStep(step: PrePaymentNextStep, amount: String) {
        when (step) {
            PrePaymentNextStep.COLLECT_RATING -> {
                _state.value = AngelPayPaymentState.CollectingRating(amount = amount)
            }
            PrePaymentNextStep.COLLECT_TIP -> {
                _state.value = AngelPayPaymentState.CollectingTip(
                    amount = amount,
                    rating = pendingRating,
                )
            }
            PrePaymentNextStep.VERIFY_PRE_PAYMENT -> {
                // AngelPay doesn't support pre-payment verification — skip to merchant
                _state.value = AngelPayPaymentState.SelectingMerchant(
                    subtotal = amount,
                    tipAmount = pendingTip.toPlainString(),
                    totalAmount = pendingAmount.add(pendingTip).toPlainString(),
                    rating = pendingRating,
                )
            }
            PrePaymentNextStep.SELECT_MERCHANT -> {
                _state.value = AngelPayPaymentState.SelectingMerchant(
                    subtotal = amount,
                    tipAmount = pendingTip.toPlainString(),
                    totalAmount = pendingAmount.add(pendingTip).toPlainString(),
                    rating = pendingRating,
                )
            }
        }
    }

    private companion object {
        /**
         * Nexgo terminal models that ship with a built-in thermal
         * printer. Matched against the uppercased `model` returned by
         * `AngelPaySDK.getDeviceInfo()` using substring containment so
         * variant suffixes (e.g. "N86-XYZ") still match.
         *
         * Add new entries here when deploying additional Nexgo SKUs.
         * Leave OUT models that ship without a printer (e.g. N62).
         */
        val NEXGO_MODELS_WITH_PRINTER = listOf("N86")
    }
}

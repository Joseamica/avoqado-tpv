package com.jaac.avoqado_tpv.features.payment.presentation.angelpay

import android.content.Intent
import android.content.Context
import androidx.lifecycle.SavedStateHandle
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
import com.jaac.avoqado_tpv.core.observability.ObservabilityManager
import com.jaac.avoqado_tpv.core.printer.PrinterManager
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.payment.data.api.PaymentApiService
import com.jaac.avoqado_tpv.features.payment.data.dto.SendReceiptRequest
import com.jaac.avoqado_tpv.features.payment.data.dto.SendWhatsAppReceiptRequest
import com.jaac.avoqado_tpv.features.payment.data.ledger.CercaDeSolicitud
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger
import com.jaac.avoqado_tpv.features.payment.presentation.CobroRemotoDelPos
// 📊 Task 6 — local telemetry of authorization attempts (fire-and-forget, rides the heartbeat)
import com.jaac.avoqado_tpv.features.payment.data.local.AuthAttemptTelemetryStore
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.serialParaAngelPay
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayAuthRepository
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayAuthState
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AuthErrorKind
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayCredentials
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayErrorMapper
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayOutcomeClassifier
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.DesenlaceDelCobro
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.VerificacionDelCobro
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayIntentBuilder
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayMerchantRepository
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayResult
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayResultParser
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPaySdkGateway
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.payment.domain.AuthWatchdogLevel
import com.jaac.avoqado_tpv.features.payment.domain.authWatchdogLevel
import com.jaac.avoqado_tpv.features.payment.domain.PaymentFlowGate
import com.jaac.avoqado_tpv.features.payment.domain.PrePaymentNextStep
import com.jaac.avoqado_tpv.features.payment.domain.model.CardBrand
import com.jaac.avoqado_tpv.features.payment.domain.model.CardDetails
import com.jaac.avoqado_tpv.features.payment.domain.model.CardEntryMode
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount
import com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt
import com.jaac.avoqado_tpv.features.payment.domain.model.QueuedPayment
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import com.jaac.avoqado_tpv.features.payment.domain.repository.MerchantRepository
import com.jaac.avoqado_tpv.features.payment.domain.repository.PaymentQueueRepository
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordPaymentUseCase
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
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
// Tick of the authorization watchdog (Task 3). Also the unit the elapsed time is
// accumulated in — see startAuthorizationWatchdog().
private const val WATCHDOG_TICK_MS = 1_000L

@HiltViewModel
class AngelPayPaymentViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val recordPaymentUseCase: RecordPaymentUseCase,
    private val shiftRepository: ShiftRepository,
    private val authRepository: AuthRepository,
    private val merchantRepository: MerchantRepository,
    // 🧭 MERCHANT_ROUTING_RULES conditional visibility (PREMIUM) — same repo as the Blumon flow
    private val merchantEligibilityRepository: com.jaac.avoqado_tpv.features.payment.domain.repository.MerchantEligibilityRepository,
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
    private val verificationUploadManager: com.jaac.avoqado_tpv.core.data.firebase.VerificationUploadManager,
    private val observability: ObservabilityManager,
    private val paymentQueueRepository: PaymentQueueRepository,
    // 📒 PaymentAttemptLedger — La Libreta write-ahead: observational marks only, never
    // blocks a charge (every ledger entry point is runCatching + venue-flag gated).
    private val paymentAttemptLedger: PaymentAttemptLedger,
    // 🔍 Resuelve un desenlace INCIERTO preguntándole al historial de AngelPay si el cobro
    // llegó a moverse — ver [AngelPayChargeVerifier]. Sin esto, "no sé" sería un callejón.
    private val chargeVerifier: com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayChargeVerifier,
    // 📊 AuthAttemptTelemetryStore — Task 6: local (Room-backed) batch of authorization-attempt
    // telemetry (result code + duration + rail), fire-and-forget, rides the next heartbeat.
    // NEVER a network call of its own, NEVER card data/amounts — see the store's own KDoc.
    private val authAttemptTelemetryStore: AuthAttemptTelemetryStore,
    // 🔁 T26: botón «Reintentar» del banner de AngelPay → recuperación MANUAL de la auth.
    private val angelPayAuthRecovery: com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayAuthRecovery,
    // 📡 Survives process/Activity death while the AngelPay SDK Activity holds the foreground —
    // see [_paymentSource] / [_socketRequestId]. Hilt provides this automatically to @HiltViewModel.
    private val savedStateHandle: SavedStateHandle,
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

    // 🧭 MERCHANT_ROUTING_RULES: eligibility for the charge in progress (drives the selector filter +
    // "showing all accounts" banner). AngelPay only FILTERS (no auto SDK-session switch on auto-select).
    private val _merchantRouting = MutableStateFlow<com.jaac.avoqado_tpv.features.payment.domain.model.MerchantEligibility?>(null)
    val merchantRouting: StateFlow<com.jaac.avoqado_tpv.features.payment.domain.model.MerchantEligibility?> = _merchantRouting.asStateFlow()

    /** Evaluate routing rules for a charge of [totalAmount] pesos and publish to [merchantRouting]. */
    private fun evaluateRoutingForCharge(totalAmount: String) {
        viewModelScope.launch {
            _merchantRouting.value = runCatching {
                merchantEligibilityRepository.evaluate(
                    totalAmount = totalAmount.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                    staffId = authRepository.getStaffId(),
                )
            }.getOrElse {
                Timber.w(it, "🧭 [AngelPay Eligibility] evaluate() threw — fail-open (show all)")
                com.jaac.avoqado_tpv.features.payment.domain.model.MerchantEligibility.disabled()
            }
        }
    }

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
    val showCryptoOption: Boolean get() = tpvSettingsRepository.getCurrentSettings().showCryptoOption
    val showReceiptScreen: Boolean get() = tpvSettingsRepository.getCurrentSettings().showReceiptScreen

    /**
     * 📸 Serialized inventory (SIM) sale: attach metadata to the payment before
     * charging. Called from the navigation layer for a SIM sale only; a normal
     * AngelPay payment never calls this, so the recorded context stays byte-identical.
     */
    fun setSerializedSaleInfo(
        serialNumber: String?,
        isPortabilidad: Boolean,
    ) {
        pendingSerialNumbers = listOfNotNull(serialNumber)
        pendingIsPortabilidad = isPortabilidad
    }

    /**
     * 📡 Tag this charge as POS-initiated over Socket.IO so its terminal outcome is reported
     * back to the caller (POS long-poll) via [emitSocketResultIfSocketSourced]. Called from the
     * navigation layer for a socket-sourced charge; a device-initiated charge never calls this,
     * so [_paymentSource] stays null and nothing is ever emitted. Mirrors the Blumon flow.
     */
    fun setSocketPaymentSource(source: String?, requestId: String?) {
        // Called from a LaunchedEffect keyed on (source, requestId), so it re-runs on every
        // recomposition AND after an Activity/VM recreation. Ignore a null/repeat tag for the
        // request already in flight: after a VM death the savedStateHandle may already hold the
        // right values, and re-tagging with the SAME id must not reset the emitted-flag (that
        // would allow a second emit for one request).
        //
        // 🔴 UN TAG NULO NUNCA BORRA UNO EXISTENTE. La pantalla lee paymentSource/socketRequestId
        // del `previousBackStackEntry`, que CAMBIA durante el pop: al desmontarse, el
        // LaunchedEffect vuelve a correr con (null, null) y este método borraba el enlace justo
        // antes de que [onCleared] lo necesitara → la red de seguridad no avisaba, la fila del
        // server quedaba UNKNOWN y la terminal empezaba a rechazar cobros con "ocupada" hasta que
        // alguien la reconciliara a mano. Verificado en hardware el 2026-08-10 (N86, a9e7503e).
        // Sólo [resetPayment] limpia el enlace, y lo hace DESPUÉS de avisar.
        // Esto no afecta un cobro iniciado en la terminal: ese llega con (null, null) sobre un VM
        // nuevo cuyos campos ya son null, así que salir temprano deja el mismo estado.
        //
        // 🔴 UN VM = UNA SOLICITUD (D.7, 2026-09-11). El VM vive atado a SU entrada de navegación, y
        // un cobro remoto nuevo siempre llega en una entrada NUEVA (el colector saca la pantalla
        // vieja y empuja otra). Pero al salir, la pantalla vieja se recomponía y releía el
        // `previousBackStackEntry`, que ya traía los argumentos del cobro SIGUIENTE: adoptarlos
        // dejaba este VM con importe, propina, orden e intento de A y el id de B, y al morir
        // (onCleared) —o al vencer el reloj de abandono— mandaba un «cancelled» de B con
        // PRE_AUTHORIZATION prestada de A, que el servidor acepta para liberar la terminal mientras
        // la pantalla de B sigue cobrando. Por eso la PRIMERA llamada fija el vínculo (la de un
        // cobro local también: llega con null, null) y desde ahí ningún otro id se adopta — ni
        // siquiera tras [resetPayment]: un VM limpio que sale es justo el caso del cancel falso.
        // Vive en el SavedStateHandle para que un VM restaurado tras la muerte del proceso lo
        // respete igual. La navegación ya lee los argumentos una sola vez; esto es la segunda
        // puerta, y si alguna vez se toca se registra (Crashlytics, sin datos del cobro).
        if (vinculoFijado) {
            if (source != null && requestId != null && requestId != solicitudVinculada) {
                Timber.w(
                    "📡 [AngelPay Socket] Solicitud ajena ignorada: este cobro es de %s y llegó %s",
                    solicitudVinculada ?: "un cobro local", requestId,
                )
                com.jaac.avoqado_tpv.core.observability.CrashlyticsContext.recordSolicitudRemotaAjena(
                    riel = "angelpay",
                    solicitudDelCobro = solicitudVinculada,
                    solicitudRecibida = requestId,
                )
            }
            return
        }
        vinculoFijado = true
        if (source == null || requestId == null) return
        solicitudVinculada = requestId
        if (requestId == _socketRequestId && source == _paymentSource) return
        // Un reloj de abandono armado emitiría con el id que tenga el VM al vencer.
        cancelarCierrePorAbandono()
        _paymentSource = source
        _socketRequestId = requestId
        _socketResultEmitted = false
        solicitudCerradaAntesDeAutorizar = false
        finalPreAutorizacionRetenido = null
        declinacionRetenida = null
        if (source == "SOCKET") {
            Timber.i("📡 [AngelPay Socket] Source set | requestId=$requestId")
        }
    }

    /** Whether the in-flight/just-completed payment is a serialized (SIM) sale. */
    val isSerializedSale: Boolean get() = pendingSerialNumbers.isNotEmpty()

    // ── Post-payment proof-of-sale (serialized SIM sale) — mirrors Blumon exactly ──
    // Photos are captured AFTER the charge, on the success screen (replacing the QR),
    // non-blocking. Gated purely by isSerializedSale — a normal AngelPay payment never
    // reaches this code.
    private val _isUploadingProofOfSale = MutableStateFlow(false)
    val isUploadingProofOfSale: StateFlow<Boolean> = _isUploadingProofOfSale.asStateFlow()

    private val _proofOfSaleComplete = MutableStateFlow(false)
    val proofOfSaleComplete: StateFlow<Boolean> = _proofOfSaleComplete.asStateFlow()

    private val _pendingProofOfSaleUrls = mutableMapOf<String, String>() // label -> Firebase URL
    private val _sentToBackendUrls = mutableSetOf<String>()

    /**
     * 📸 Upload a proof-of-sale photo to Firebase, then attach it to the just-recorded
     * payment's PENDING SaleVerification via a dedicated endpoint keyed by paymentId.
     * Mirrors Blumon's `PaymentViewModel.uploadProofOfSale` byte-for-byte.
     */
    fun uploadProofOfSale(
        photoPath: String,
        paymentId: String,
        orderNumber: String,
        amount: String,
        photoLabel: String = "linea",
    ) {
        viewModelScope.launch {
            try {
                _isUploadingProofOfSale.value = true
                val requiredCount = if (pendingIsPortabilidad) 2 else 1
                Timber.i("📸 [AngelPay PROOF-OF-SALE] Starting upload | label=$photoLabel | paymentId=$paymentId | order=$orderNumber | required=$requiredCount")

                val venueSlug = authRepository.getVenueSlug() ?: run {
                    Timber.e("📸 [AngelPay PROOF-OF-SALE] No venueSlug available")
                    return@launch
                }

                val uploadResult = verificationUploadManager.uploadProofOfSale(
                    localPath = photoPath,
                    venueSlug = venueSlug,
                    orderNumber = orderNumber,
                    amount = amount,
                    photoLabel = photoLabel,
                )

                uploadResult.onSuccess { photoUrl ->
                    Timber.i("📸 [AngelPay PROOF-OF-SALE] Firebase upload success: $photoUrl")
                    _pendingProofOfSaleUrls[photoLabel] = photoUrl
                    sendSinglePhotoToBackend(paymentId, photoUrl, photoLabel)
                    if (_pendingProofOfSaleUrls.size >= requiredCount) {
                        _proofOfSaleComplete.value = true
                    }
                }.onFailure { error ->
                    Timber.e(error, "📸 [AngelPay PROOF-OF-SALE] Firebase upload failed")
                }
            } catch (e: Exception) {
                Timber.e(e, "📸 [AngelPay PROOF-OF-SALE] Unexpected error")
            } finally {
                _isUploadingProofOfSale.value = false
            }
        }
    }

    private suspend fun sendSinglePhotoToBackend(paymentId: String, photoUrl: String, photoLabel: String?) {
        val backendLabel = when (photoLabel) {
            "linea" -> "Vinculacion"
            "portabilidad" -> "Portabilidad"
            else -> photoLabel
        }
        try {
            val response = paymentApiService.uploadProofOfSale(
                com.jaac.avoqado_tpv.core.data.network.ProofOfSaleRequest(
                    paymentId = paymentId,
                    photoUrls = listOf(photoUrl),
                    photoLabel = backendLabel,
                )
            )
            if (response.isSuccessful && response.body()?.success == true) {
                Timber.i("✅ [AngelPay PROOF-OF-SALE] Backend recorded photo for payment $paymentId")
                _sentToBackendUrls.add(photoUrl)
            } else {
                Timber.e("❌ [AngelPay PROOF-OF-SALE] Backend failed: ${response.body()?.message}")
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ [AngelPay PROOF-OF-SALE] Backend call error")
        }
    }

    /** 📸 Retake a proof-of-sale photo — deletes the existing Firebase upload for the slot. */
    fun retakeProofOfSalePhoto(photoLabel: String) {
        val existingUrl = _pendingProofOfSaleUrls.remove(photoLabel)
        _proofOfSaleComplete.value = false
        if (existingUrl != null) {
            viewModelScope.launch {
                verificationUploadManager.deletePhoto(existingUrl)
                    .onSuccess { Timber.d("📸 [AngelPay PROOF-OF-SALE] Deleted old $photoLabel photo") }
                    .onFailure { Timber.w(it, "📸 [AngelPay PROOF-OF-SALE] Failed to delete old $photoLabel photo") }
            }
        }
    }

    /**
     * 📸 Cleanup photos uploaded to Firebase but never sent to the backend (e.g. app
     * closed mid-flow before completing all required photos). Mirrors Blumon.
     */
    private fun cleanupOrphanedProofOfSalePhotos() {
        if (_pendingProofOfSaleUrls.isEmpty()) return
        val urlsToDelete = _pendingProofOfSaleUrls.values.filter { it !in _sentToBackendUrls }
        _pendingProofOfSaleUrls.clear()
        _sentToBackendUrls.clear()
        if (urlsToDelete.isEmpty()) return
        viewModelScope.launch {
            urlsToDelete.forEach { url ->
                verificationUploadManager.deletePhoto(url)
                    .onSuccess { Timber.d("📸 [AngelPay PROOF-OF-SALE] Deleted orphan: $url") }
                    .onFailure { Timber.w(it, "📸 [AngelPay PROOF-OF-SALE] Failed to delete orphan: $url") }
            }
        }
    }
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
    // 📸 Serialized inventory (SIM) proof-of-sale — set via [setSerializedSaleInfo] before
    // charging. Empty/false for a normal AngelPay payment so the recorded context is
    // byte-identical to before. Cleared in resetPayment().
    private var pendingSerialNumbers: List<String> = emptyList()
    private var pendingIsPortabilidad: Boolean = false
    // ⬅️ ¿Este cobro se saltó calificación/propina en la IDA? Lo pide quien lo lanza:
    // el POS por socket (`skipReview` del terminal:payment_request) o una venta serializada.
    // [goBackOneStep] lo consulta para NO retroceder a pantallas que nunca se mostraron —
    // sin esto lee la config CRUDA del venue y mete al operador a Propina y Calificación,
    // dejando al POS colgado mientras sale de un wizard que no existió (caso real N86
    // 2026-08-10: 34 s hasta que llegó la cancelación). Espejo de `isSkipReviewFlow` del
    // riel Blumon. Campo simple a propósito: si el proceso muere, la pantalla vuelve a
    // llamar initPayment con el mismo skipReview y el flag se recompone solo.
    private var isSkipReviewFlow: Boolean = false
    private var cachedShiftId: String? = null
    private var cachedVenueId: String? = null
    private var cachedStaffId: String? = null

    /**
     * 🔴 Candado simétrico al del riel Blumon ([com.jaac.avoqado_tpv.features.payment.presentation.PaymentViewModel],
     * 2026-09-07). Este riel HOY no tiene el defecto —fija `ProcessingCash` en la primera línea
     * del `launch` y no espera red antes del POST—, pero eso lo garantiza el ORDEN de dos líneas:
     * basta meter un `await` (leer el turno, refrescar credenciales) antes de esa primera línea
     * para heredar la ráfaga de N toques = N cobros. Cuesta una línea; en Blumon el mismo hueco
     * costó 5 cobros COMPLETED sobre la misma orden en 1.7 s (SN00396, 2026-09-04).
     */
    private val cobroEnEfectivoEnVuelo = java.util.concurrent.atomic.AtomicBoolean(false)

    // 📡 POS→TPV terminal arbitration: a charge initiated by the POS over Socket.IO carries a
    // request id the caller long-polls on. Set from the navigation layer via [setSocketPaymentSource];
    // stays null for device-initiated charges (which therefore never emit).
    //
    // ⚠️ BACKED BY SavedStateHandle ON PURPOSE — do NOT "simplify" these back to plain fields.
    // The AngelPay SDK runs in its OWN Activity (com.angelpay.angelpaysdk.ui.PaymentActivity), so
    // MainActivity is stopped and Android may destroy it mid-charge. On recreation the NavController
    // restores the backstack but a PLAIN field would come back null, and the VM that RECORDS the
    // payment would not be the VM that was configured → the recorded payment carries
    // terminalPaymentRequestId = null and no terminal:payment_result is emitted → the server's
    // arbitration row stays CANCELLED/FAILED for a charge whose money actually MOVED, and the 🚨
    // reconcile alert never fires. (That the app loses foreground here is already codified by
    // ForegroundRecoveryGate.arm(reason = "angelpay_sdk_approved") in AngelPayPaymentScreen.)
    // Cleared in [resetPayment]; the emit clears only the emitted-flag, never the request id.
    private var _paymentSource: String?              // "SOCKET" | null
        get() = savedStateHandle[KEY_PAYMENT_SOURCE]
        set(value) { savedStateHandle[KEY_PAYMENT_SOURCE] = value }
    private var _socketRequestId: String?
        get() = savedStateHandle[KEY_SOCKET_REQUEST_ID]
        set(value) { savedStateHandle[KEY_SOCKET_REQUEST_ID] = value }

    // One emit per request — tracked separately so the REQUEST ID SURVIVES the emit. Clearing the
    // id on emit (the old behavior) silently broke retry-after-decline: the decline emits "failed"
    // and nulls the id, the cashier retries ON THE TERMINAL, the card is APPROVED, and the recorded
    // payment carries terminalPaymentRequestId = null → the server can never reconcile the FAILED
    // row to COMPLETED and the 🚨 "money moved despite close" alert never fires.
    private var _socketResultEmitted: Boolean
        get() = savedStateHandle[KEY_SOCKET_EMITTED] ?: false
        set(value) { savedStateHandle[KEY_SOCKET_EMITTED] = value }

    // 🔒 Un VM = una solicitud: se fija en la PRIMERA llamada a [setSocketPaymentSource] (con id o
    // con null, null para un cobro local) y NUNCA se suelta — ni en [resetPayment]. Ver ahí el porqué.
    // [solicitudVinculada] es el id que fijó esa llamada (null = cobro local); a diferencia de
    // [_socketRequestId], sobrevive a [resetPayment] para reconocer el MISMO id sin alarma.
    private var vinculoFijado: Boolean
        get() = savedStateHandle[KEY_VINCULO_FIJADO] ?: false
        set(value) { savedStateHandle[KEY_VINCULO_FIJADO] = value }
    private var solicitudVinculada: String?
        get() = savedStateHandle[KEY_SOLICITUD_VINCULADA]
        set(value) { savedStateHandle[KEY_SOLICITUD_VINCULADA] = value }

    // 🛡️ IDEMPOTENCY KEY (2026-04-08) — Stripe/Square/Toast pattern
    // UUID v4 generated ONCE per logical payment attempt and reused on every retry.
    // Generated in initPayment() and cleared in resetPayment(). Cleared explicitly so
    // each new attempt gets a fresh key.
    private var currentPaymentAttemptId: String? = null
    // Consumes external payment callback only once per launched attempt.
    private var consumedResultAttemptId: String? = null
    // 📒 [Libreta] Fingerprint of the last attempt THIS ViewModel instance successfully opened
    // a ledger row for: attemptId + the EXACT cents it was opened with. AngelPay deliberately
    // REUSES the attemptId on retry-after-decline (see [retryAfterError]) and on the SDK→
    // app-to-app in-session fallback — both with IDENTICAL amounts — and without this guard
    // every one of those legit reuses would trip the ledger's PK-collision "REUSE" alarm.
    // The guard is AMOUNT-AWARE on purpose: a stale attemptId reused for DIFFERENT money
    // (state contamination — initPayment overwrites pendingAmount/pendingTip while
    // ensurePaymentAttemptId only generates when null) is exactly the dedup-swallowed
    // double-charge signal spec §6 reserves the alarm for, so ANY cent mismatch falls through
    // to openAttempt and lets the collision alarm fire. Armed ONLY when openAttempt reported
    // success — a collided/failed open must never arm the suppression. Purely ledger
    // bookkeeping; NOT cleared in resetPayment() on purpose — a new attempt generates a fresh
    // UUID, so the comparison self-corrects, and a post-process-death VM starts at null and
    // opens normally.
    private var ledgerOpenedAttemptId: String? = null
    private var authorizationWasLaunched = false
    // 🔴 T26 / H.3: desenlace final RETENIDO de un cobro remoto que falló ANTES de autorizar
    // (mensaje para el POS). Sale como `failed + PRE_AUTHORIZATION` sólo al cancelar/salir o al
    // vencer el reloj de abandono — nunca mientras la pantalla ofrezca reintentar.
    private var finalPreAutorizacionRetenido: String? = null
    // 🔴 T26 / H.3: ya salió el final de esta solicitud por el reloj de abandono; la terminal ya
    // no la ejecuta. Se limpia con una solicitud nueva o con resetPayment.
    private var solicitudCerradaAntesDeAutorizar = false
    private var confirmedNegativeOutcome = false
    private var confirmedNegativeEvidence: String? = null

    /**
     * 🛑 H.3 — desenlace NEGATIVO de un cobro remoto que ya llegó al SDK (rechazo del banco o aviso EMV)
     * y que se RETIENE mientras la pantalla ofrezca reintentar ESA MISMA solicitud.
     *
     * Antes salía en el instante del rechazo: el servidor lo daba por «no se cobró», la tablet soltaba su
     * llave y el cajero podía cobrar de otra forma… mientras la terminal seguía ofreciendo «Reintentar»
     * sobre la MISMA solicitud. Si ese reintento aprobaba, el cliente pagaba dos veces (P1-2 de la
     * auditoría del 11-sep; en producción hay 6 filas COMPLETED con `failureCode = TPV_ERROR` que son
     * exactamente ese patrón). Sale al salir/cancelar o cuando vence el reloj de abandono, y desde ese
     * momento la solicitud queda CERCADA en la bandeja.
     */
    private var declinacionRetenida: DesenlaceRetenido? = null

    /** El POS canceló esta solicitud y la terminal lo aceptó: este cobro ya no continúa por ningún camino. */
    @Volatile private var cobroCanceladoPorElPos = false

    private data class DesenlaceRetenido(val evidencia: String?, val mensaje: String?)

    /** Lo que la pantalla muestra cuando el POS canceló (o llegó tarde). Null = no hay nada que decir. */
    private val _mensajeDelPos = MutableStateFlow<String?>(null)
    val mensajeDelPos: StateFlow<String?> = _mensajeDelPos.asStateFlow()
    private var pendingProcessorAffiliation: String? = null
    private var ledgerOpenedAmountCents: Long? = null
    private var ledgerOpenedTipCents: Long? = null

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
     * 💥 Last-resort money-safety guard: a charge ALREADY moved money but we cannot reconstruct
     * venue/staff (session fully gone) so we can neither record NOR enqueue it — both require
     * venue+staff. We MUST NOT silently drop the sale: alert loudly for manual reconciliation and
     * report the caller a single "success" (money moved). Extremely rare — venue/staff are cached at
     * initPayment AND recoverable from authRepository/secureStorage before we ever reach here.
     */
    private fun reportChargedButUnrecordable(
        paymentLabel: String,
        error: Throwable,
    ): AngelPayPaymentState.Error {
        Timber.e(error, "💥 [AngelPay] $paymentLabel: money moved but venue/staff unrecoverable — cannot record OR enqueue")
        observability.logWarning(
            tag = "AngelPayChargeOrphaned",
            message = "$paymentLabel cobrado pero imposible registrar/encolar (sesión perdida)",
            metadata = mapOf(
                "amount" to pendingAmount.add(pendingTip).toPlainString(),
                "attemptId" to (currentPaymentAttemptId ?: "none"),
                "socketRequestId" to (_socketRequestId ?: "none"),
                "error" to (error.message ?: "unknown"),
            ),
        )
        // 📡 money moved ⇒ tell the caller success ONCE (guard de-dupes via _socketResultEmitted).
        emitSocketResultIfSocketSourced(status = "success")
        return backendRecordFailureState(paymentLabel, error)
    }

    /**
     * AngelPay already moved the money — a backend recording failure must NEVER
     * strand the sale (P1 fix 2026-07-09). Persist it to the offline queue so
     * [com.jaac.avoqado_tpv.core.data.workers.PaymentSyncWorker] replays it: the
     * same idempotencyKey + referenceNumber travel with the row, so backend dedup
     * makes the replay safe even if the original request actually landed. Falls
     * back to the manual-review error state only if the LOCAL enqueue also fails.
     */
    @VisibleForTesting
    internal suspend fun handleRecordFailure(
        paymentLabel: String,
        context: PaymentContext.AngelPayPayment,
        error: Throwable,
    ): AngelPayPaymentState {
        // 📡 POS→TPV: the card WAS charged (money moved) — the backend record merely failed and is
        // being enqueued for offline sync. Report SUCCESS to the POS now (founder rule 2026-07:
        // money moved ⇒ success). No paymentId yet; the synced REST record reconciles the row later.
        // No-op unless socket-sourced.
        emitSocketResultIfSocketSourced(status = "success")

        val queued = QueuedPayment(
            // UNIQUE column in Room — AngelPay can return a blank reference on edge
            // paths, so fall back to the attempt UUID to keep the row insertable.
            referenceNumber = context.referenceNumber.ifBlank {
                context.idempotencyKey ?: "ANGELPAY-${System.currentTimeMillis()}"
            },
            venueId = context.venueId,
            staffId = context.staffId,
            amount = context.amount,
            tip = context.tip,
            rating = context.rating,
            merchantAccountId = context.merchantAccountId.orEmpty(),
            blumonSerialNumber = "", // N/A — AngelPay row
            deviceSerialNumber = context.deviceSerialNumber,
            maskedPan = context.cardDetails?.maskedPan,
            cardBrand = context.cardDetails?.cardBrand?.name,
            entryMode = context.cardDetails?.entryMode?.name ?: CardEntryMode.OTHER.name,
            isInternational = context.cardDetails?.isInternational ?: false,
            authorizationNumber = context.authorizationCode,
            idempotencyKey = context.idempotencyKey,
            processor = ProcessorType.ANGELPAY,
            // 📡 Carry the arbitration link through the queue — the replayed record is what
            // closes the terminal's TerminalPaymentRequest row (the watchdog can't reconcile
            // a fast payment: its row has no orderId).
            terminalPaymentRequestId = context.terminalPaymentRequestId,
            orderId = context.orderId,
            orderNumber = context.orderNumber,
            shiftId = context.shiftId,
            isPortabilidad = context.isPortabilidad,
            serialNumbers = context.serialNumbers,
            createdAt = System.currentTimeMillis(),
        )

        val enqueueResult = paymentQueueRepository.enqueue(queued)
        return if (enqueueResult.isSuccess) {
            // 📒 [Libreta] ENTREGADA_A_COLA — pending_payments owns the money now (its own
            // idempotency + retry); the ledger row rests. For the cash path (no ledger row
            // is opened for cash) this is a CAS no-match no-op inside the ledger.
            context.idempotencyKey?.let { paymentAttemptLedger.markDeliveredToQueue(it) }
            Timber.i(
                "💾 [AngelPay] Payment queued for offline sync | ref=%s order=%s",
                queued.referenceNumber,
                queued.orderId ?: "-",
            )
            observability.logWarning(
                tag = "AngelPayRecordQueued",
                message = "Registro de pago falló — encolado para sync automático",
                metadata = mapOf(
                    "reference" to queued.referenceNumber,
                    "orderId" to (queued.orderId ?: "none"),
                    "amount" to queued.amount.toPlainString(),
                    "error" to (error.message ?: "unknown"),
                ),
            )
            // Best-effort immediate kick — the 15-min periodic worker is the guarantee.
            runCatching { PaymentSyncScheduler.runNow(appContext) }
            // 🟢 F-1 fix (spec §4.2): money moved AND it's safely queued — this is a
            // SUCCESS with a caveat, not an Error. Was AngelPayPaymentState.Error before;
            // the cashier saw a red screen on a charge that had already succeeded and
            // recharged the customer out of fear. See AngelPayPaymentState.Queued kdoc.
            AngelPayPaymentState.Queued(
                message = "$paymentLabel fue procesado, pero Avoqado no respondió. El registro quedó " +
                    "EN COLA y se completará automáticamente al recuperar conexión. NO vuelvas a cobrar.",
                authCode = context.authorizationCode.orEmpty(),
                amount = queued.amount.toPlainString(),
                tipAmount = queued.tip.toPlainString(),
                referenceNumber = queued.referenceNumber,
                orderId = queued.orderId,
                orderNumber = queued.orderNumber,
            )
        } else {
            val enqueueError = enqueueResult.exceptionOrNull()
            Timber.e(enqueueError, "❌ [AngelPay] Offline enqueue ALSO failed")
            // Fix round 1, finding 2: thread the enqueue failure reason into the detail the
            // supervisor sees, so an index-blocked enqueue (F-7) is distinguishable from a
            // plain backend outage. Additive — backendRecordFailureState's signature is
            // untouched; the original backend error is preserved as `cause` for Crashlytics.
            val combinedError = enqueueError?.message?.let { enqueueDetail ->
                Exception(
                    "${error.message ?: "error desconocido"} | Encolado también falló: $enqueueDetail",
                    error,
                )
            } ?: error
            backendRecordFailureState(paymentLabel, combinedError)
        }
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

    /**
     * 📒 [Libreta] Write-ahead before launching the AngelPay SDK/intent. The SDK returns
     * exactly once — if the process dies while it runs, this row + integratorReference
     * (== attemptId) are the only correlation evidence that a charge was in flight.
     *
     * Suspend on purpose: both launch sites await it, so the row is COMMITTED before the
     * launch trigger (the `Launching*` state emission the Screen reacts to) can fire —
     * the pre-SDK barrier (spec §4.4). A ledger failure never blocks the charge: the
     * ledger's entry points are runCatching inside, and this outer runCatching also
     * covers argument building (cents conversion, venue lookup).
     *
     * Single helper for both launch paths instead of the brief's duplicated block —
     * identical-by-construction beats identical-by-copy in a single-variant main/ file.
     *
     * Re-open suppression is AMOUNT-AWARE: only an attempt already opened with the SAME
     * attemptId AND the SAME amount/tip cents skips [PaymentAttemptLedger.openAttempt]
     * (the legit reuse shapes — retry-after-decline, SDK→app-to-app fallback — never change
     * the money). A cent mismatch on a reused id falls through so the ledger's PK-collision
     * "REUSE" alarm fires — see [ledgerOpenedAttemptId] for the full rationale.
     */
    @VisibleForTesting
    internal suspend fun openLedgerAttemptAndMarkAuthorizing(paymentAttemptId: String): Boolean {
        return try {
            val venueId = cachedVenueId ?: authRepository.getVenueId() ?: secureStorage.getVenueId() ?: return false
            val staffId = cachedStaffId ?: authRepository.getStaffId() ?: secureStorage.getStaffId() ?: return false
            val amountCents = pendingAmount.movePointRight(2).longValueExact()
            val tipCents = pendingTip.movePointRight(2).longValueExact()
            val alreadyOpened = ledgerOpenedAttemptId == paymentAttemptId &&
                ledgerOpenedAmountCents == amountCents && ledgerOpenedTipCents == tipCents
            if (!alreadyOpened) {
                val context = PaymentContext.AngelPayPayment(
                    venueId = venueId, staffId = staffId, shiftId = cachedShiftId,
                    amount = pendingAmount, tip = pendingTip, rating = pendingRating,
                    merchantAccountId = _currentMerchant.value?.merchantAccountId,
                    // 🔴 El serial REAL del aparato, sin el prefijo `AVQD-`. Este valor es el que
                    // `LedgerUnknownRecovery` lee del `payment_context_json` para preguntarle a
                    // AngelPay «¿este cobro pasó?» tras un reinicio. Con `TerminalConfig.serialNumber`
                    // —que NO es de este aparato sino de un COMERCIO Blumon, por defecto "2841548417",
                    // una PAX— el historial contesta vacío SIEMPRE y la recuperación automática queda
                    // MUERTA: ningún cobro incierto se acredita nunca. Medido el 2026-09-12.
                    deviceSerialNumber = serialParaAngelPay(secureStorage.getSerialNumber()),
                    idempotencyKey = paymentAttemptId,
                    terminalPaymentRequestId = _socketRequestId,
                    orderId = pendingOrderId, orderNumber = pendingOrderNumber,
                    isPortabilidad = pendingIsPortabilidad, serialNumbers = pendingSerialNumbers,
                )
                pendingProcessorAffiliation = runCatching { sdkGateway.getSessionInfo()?.affiliation }.getOrNull()
                val json = com.google.gson.Gson().toJsonTree(context).asJsonObject.apply {
                    addProperty("processorAffiliation", pendingProcessorAffiliation)
                }
                if (!paymentAttemptLedger.openAttempt(
                        paymentAttemptId, venueId, PaymentAttemptEntity.PROCESSOR_ANGELPAY,
                        amountCents, tipCents,
                        if (pendingOrderId != null) PaymentAttemptEntity.ROUTE_ORDER else PaymentAttemptEntity.ROUTE_FAST,
                        json.toString())) return false
                ledgerOpenedAttemptId = paymentAttemptId
                ledgerOpenedAmountCents = amountCents
                ledgerOpenedTipCents = tipCents
            }
            // A second launch is only the known pre-authorization D308/validation fallback.
            val marked = paymentAttemptLedger.markAuthorizing(paymentAttemptId)
            marked.also { if (it) { authorizationWasLaunched = true; confirmedNegativeOutcome = false; confirmedNegativeEvidence = null } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.e(error, "Cannot persist payment attempt; processor entry refused")
            false
        }
    }

    /**
     * Atajo de pruebas (T26): `testSandboxDebugUnitTest` compila con
     * `ANGELPAY_SDK_ENABLED=false`, así que [startCardPayment] nunca llegaba a la ruta del
     * SDK en pruebas. Extiende el mismo atajo que [launchSdkRequest]. En producción vale
     * `false` y no cambia nada.
     */
    @VisibleForTesting
    internal var flujoSdkForzadoParaPruebas: Boolean = false

    private fun isSdkFlowEnabled(): Boolean {
        val settings = tpvSettingsRepository.getCurrentSettings()
        return (BuildConfig.ANGELPAY_SDK_ENABLED || flujoSdkForzadoParaPruebas) && settings.angelPaySdkEnabled
    }

    private fun isAppToAppFallbackEnabled(): Boolean {
        val settings = tpvSettingsRepository.getCurrentSettings()
        return BuildConfig.ANGELPAY_SDK_FALLBACK_ENABLED && settings.angelPaySdkFallbackEnabled
    }

    init {
        // 📡 Publish "is a charge genuinely active?" to the shared PaymentStateHolder so the
        // socket/BLE charge dispatcher (AppNavigation) can tell a live charge from a stale
        // RESOLVED result screen. Single funnel over our own state stream — a decline that
        // leaves the Error screen up is NOT active, so it no longer blocks the next POS charge.
        // (Idle/Success/Queued/Error/Cancelled = resolved → not active; every other state =
        // working.) F-1 fix round 1: Queued is a RESOLVED outcome (money moved, safely queued)
        // exactly like Success/Error/Cancelled — omitting it here reopened the exact "screen
        // stays up, terminal rejects every subsequent POS charge" bug from 2026-07-14, because
        // this path used to publish Error (which WAS in the list) before the F-1 fix.
        viewModelScope.launch {
            state.collect { s ->
                paymentStateHolder.setChargeAttemptActive(
                    s !is AngelPayPaymentState.Idle &&
                        s !is AngelPayPaymentState.Success &&
                        s !is AngelPayPaymentState.Queued &&
                        s !is AngelPayPaymentState.Error &&
                        s !is AngelPayPaymentState.Cancelled,
                )
            }
        }

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
                    // 🛑 C.5: el POS canceló. La decisión ya la tomó la bandeja (durable); esto es sólo el
                    // aviso en pantalla, filtrado por la solicitud de ESTE cobro — `events` tiene replay = 1
                    // y un suscriptor nuevo recibe el último evento, que puede ser de otra solicitud.
                    is SocketEvent.TerminalPaymentCancel -> manejarCancelacionRemota(event)
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
     * @param skipReview When true, bypass the rating/tip screens (serialized SIM sales or
     *   POS-initiated payments). Any valid external tip/rating is still retained; this flag only
     *   controls which screens are shown. Pre-payment verification is still honored via
     *   `showVerificationScreen`.
     * @param externalTipCents Tip already chosen by the POS, in cents. Null for terminal-led flows.
     * @param externalRating Rating already chosen by the POS (1-5). Null when it was not provided.
     */
    fun initPayment(
        amount: String,
        orderId: String? = null,
        orderNumber: String? = null,
        skipReview: Boolean = false,
        externalTipCents: Long? = null,
        externalRating: Int? = null,
    ) {
        viewModelScope.launch {
            Timber.i("🔶 [AngelPay] initPayment | amount=$amount, orderId=$orderId")

            val amountDecimal = amount.toBigDecimalOrNull()
            if (amountDecimal == null || amountDecimal <= BigDecimal.ZERO) {
                _state.value = AngelPayPaymentState.Error("Monto invalido")
                // 📡 POS→TPV: pre-charge validation error — no money moved (no-op unless socket-sourced).
                emitSocketResultIfSocketSourced(status = "failed", errorMessage = "Monto invalido", outcomeEvidence = "PRE_AUTHORIZATION".takeUnless { authorizationWasLaunched })
                return@launch
            }
            if (externalTipCents != null && externalTipCents < 0L) {
                _state.value = AngelPayPaymentState.Error("Propina invalida")
                emitSocketResultIfSocketSourced(status = "failed", errorMessage = "Propina invalida", outcomeEvidence = "PRE_AUTHORIZATION".takeUnless { authorizationWasLaunched })
                return@launch
            }

            // 1. Validate shift
            val venueId = authRepository.getVenueId()
            if (venueId == null) {
                _state.value = AngelPayPaymentState.Error("Error: No hay venue activo")
                // 📡 POS→TPV: gate pre-cobro — no se movió dinero (no-op salvo socket-sourced).
                emitSocketResultIfSocketSourced(status = "failed", errorMessage = "No hay venue activo", outcomeEvidence = "PRE_AUTHORIZATION".takeUnless { authorizationWasLaunched })
                return@launch
            }

            val staffId = authRepository.getStaffId()
            if (staffId == null) {
                _state.value = AngelPayPaymentState.Error("Error: No hay staff activo")
                // 📡 POS→TPV: gate pre-cobro — no se movió dinero (no-op salvo socket-sourced).
                emitSocketResultIfSocketSourced(status = "failed", errorMessage = "No hay staff activo", outcomeEvidence = "PRE_AUTHORIZATION".takeUnless { authorizationWasLaunched })
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
                    // 📡 POS→TPV: pre-charge gate (no open shift) — no money moved (no-op unless socket-sourced).
                    emitSocketResultIfSocketSourced(status = "failed", errorMessage = "Debes abrir un turno antes de cobrar", outcomeEvidence = "PRE_AUTHORIZATION".takeUnless { authorizationWasLaunched })
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
            pendingTip = externalTipCents
                ?.let { BigDecimal.valueOf(it, 2) }
                ?: BigDecimal.ZERO
            pendingRating = externalRating?.takeIf { it in 1..5 }
            if (externalRating != null && pendingRating == null) {
                Timber.w("🔶 [AngelPay] Ignoring invalid external rating=$externalRating")
            }
            pendingOrderId = orderId
            pendingOrderNumber = orderNumber
            cachedShiftId = shift?.id
            cachedVenueId = venueId
            cachedStaffId = staffId

            // 🛡️ IDEMPOTENCY KEY (2026-04-08): Generate the UUID NOW for this payment attempt.
            // Reused by both cash and card flows below, and persisted across all retries.
            ensurePaymentAttemptId()

            // 4. Use PaymentFlowGate to determine first screen.
            // skipReview (serialized SIM sales) suppresses rating/tip but KEEPS
            // pre-payment verification — mirror of Blumon's skipReview semantics.
            val settings = tpvSettingsRepository.getCurrentSettings()
            // ⬅️ Recordarlo para el back: sin esto, [goBackOneStep] usaría la config cruda y
            // retrocedería a las pantallas que esta misma línea acaba de suprimir.
            isSkipReviewFlow = skipReview
            val effectiveSettings = if (skipReview) {
                settings.copy(showReviewScreen = false, showTipScreen = false)
            } else {
                settings
            }
            val nextStep = PaymentFlowGate.nextAfterAmount(effectiveSettings)
            Timber.d("🔶 [AngelPay] FlowGate.nextAfterAmount → $nextStep (skipReview=$skipReview)")

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
        evaluateRoutingForCharge(total.toPlainString()) // 🧭 MERCHANT_ROUTING_RULES
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
        evaluateRoutingForCharge(amount) // 🧭 MERCHANT_ROUTING_RULES
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

    /**
     * T26: «Reintentar» del banner de AngelPay. Recuperación MANUAL: no espera el enfriamiento
     * de los reintentos de fondo, pero conserva sus candados (sin cobro en curso, sin tocar
     * una sesión viva, una corrida a la vez).
     */
    fun retryAngelPayAuth() {
        // «Pantalla quieta»: método de pago (o un error/idle) sin selección de comercio en vuelo.
        // El banner con «Reintentar» sólo se muestra en «Método de pago», y ese estado NO es un
        // cobro; el candado de dueño y `isCharging` se siguen exigiendo en la recuperación.
        val estado = _state.value
        val pantallaQuieta = !_selectionInProgress.value && (
            estado is AngelPayPaymentState.SelectingMerchant ||
                estado is AngelPayPaymentState.Idle ||
                estado is AngelPayPaymentState.Error ||
                estado is AngelPayPaymentState.Cancelled
            )
        viewModelScope.launch {
            val resultado = angelPayAuthRecovery.recoverIfStuck(
                com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.DisparadorRecuperacion.MANUAL,
                pantallaDeCobroQuieta = pantallaQuieta,
                // En multicuenta, la cuenta del comercio elegido en pantalla — nunca la primaria.
                cuentaDelComercioElegido = _currentMerchant.value?.angelpayUserAccountId,
            )
            Timber.i("🔁 [AngelPay] Reintento manual de la auth → $resultado")
        }
    }

    /**
     * Núcleo compartido por [selectMerchant] y la auth previa del cobro (T26): deja la sesión
     * del SDK en el comercio [targetId] según el estado de la auth.
     */
    private suspend fun fijarComercioEnSesion(targetId: Int): Result<Unit> =
        when (val authState = angelPayAuthRepository.state.value) {
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
                // 📡 POS→TPV: pre-charge merchant error — no money moved (no-op unless socket-sourced).
                emitSocketResultIfSocketSourced(status = "failed", errorMessage = "Merchant inválido para AngelPay: ${err.message}", outcomeEvidence = "PRE_AUTHORIZATION".takeUnless { authorizationWasLaunched })
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
            // Incidente Amaena (2026-07-29): un accountId no trackeado (null) NO es prueba
            // de estar en el login correcto — tras un restart del proceso o una re-auth no
            // trackeada la sesión del SDK puede pertenecer a la PRIMARIA del venue mientras
            // la UI muestra este merchant. Deriva la cuenta real de la sesión viva; si el
            // target sigue difiriendo (o no hay nada derivable), switchAccount establece el
            // login del target directamente en vez de saltarse el switch en silencio.
            val currentAccountId = angelPayAuthRepository.getCurrentAngelPayAccountId()
                ?: angelPayAuthRepository.deriveSessionAccountId()
            if (targetAccountId != null && targetAccountId != currentAccountId) {
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
                    // 📡 POS→TPV: TRANSIENT/retryable pre-money error (money did NOT move). Do NOT emit and
                    // do NOT clear _socketRequestId — a terminal-side retry can still succeed on THIS request;
                    // leaving the id set lets that success re-emit "success" to the still-open POS long-poll
                    // (~315s), avoiding a stale "failed" → human double-charge. Trade-off: on ABANDONMENT the
                    // long-poll times out → server watchdog marks the row UNKNOWN (false-busy) — accepted.
                    // 🔌 Circuit breaker: a technical account-switch failure counts against this merchant.
                    merchantEligibilityRepository.recordChargeFailure(merchant.merchantAccountId ?: merchant.id)
                    return@launch
                }
            }

            val result: Result<Unit> = fijarComercioEnSesion(targetId)

            result.onFailure { err ->
                Timber.e(err, "🔶 [AngelPay] Merchant selection failed; reverting to $previousMerchant")
                _currentMerchant.value = previousMerchant
                _state.value = AngelPayPaymentState.Error(
                    message = "No se pudo cambiar de merchant: ${err.message ?: "error desconocido"}",
                    canRetry = true,
                )
                // 📡 POS→TPV: TRANSIENT/retryable pre-money error (money did NOT move). Do NOT emit and do
                // NOT clear _socketRequestId — a terminal-side retry can still succeed on THIS request;
                // leaving the id set lets that success re-emit "success" to the still-open POS long-poll
                // (~315s), avoiding a stale "failed" → human double-charge. Trade-off: on ABANDONMENT the
                // long-poll times out → server watchdog marks the row UNKNOWN (false-busy) — accepted.
                // 🔌 Circuit breaker: technical merchant-selection failure.
                merchantEligibilityRepository.recordChargeFailure(merchant.merchantAccountId ?: merchant.id)
            }
            result.onSuccess {
                // 🔌 Circuit breaker: a healthy selection resets this merchant's failure counter.
                merchantEligibilityRepository.recordChargeSuccess(merchant.merchantAccountId ?: merchant.id)
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

            // ── T26 — el cobro es el DUEÑO ÚNICO de la sesión del SDK desde aquí hasta lanzar
            // el SDK o fallar (incidente de Amaena, $4,344.50). Entre la auth de abajo (que puede
            // hacer logout) y `setCharging(true)` hay una ventana en la que `isCharging` es false:
            // si la recuperación de fondo, el panel o el arranque tienen la sesión, se ESPERA
            // —mostrando «Conectando con AngelPay…»— en vez de encimarse.
            val candadoDeSesion = angelPayAuthRepository.candadoDeSesion
            if (!candadoDeSesion.tryLock()) {
                _state.value = AngelPayPaymentState.ConectandoAngelPay
                candadoDeSesion.lock()
            }
            try {
                // 🛑 C.5: el POS canceló este cobro mientras se tomaba el candado. Nada que lanzar.
                if (cobroCanceladoPorElPos) {
                    Timber.w("🛑 [AngelPay] El POS canceló este cobro: no se lanza el SDK")
                    paymentStateHolder.setCharging(false)
                    return@launch
                }
                // ── T26 — auth ANTES de la espera del comercio (sólo en la ruta del SDK) ──
                // Tras arrancar sin red la sesión no existe y nada movía el comercio activo: la
                // espera de abajo vencía a los 8 s ANTES de llegar a la única línea que
                // autenticaba. Ahora se autentica primero con la cuenta del comercio ELEGIDO.
                if (isSdkFlowEnabled() && !asegurarSesionAntesDeEsperar()) {
                    paymentStateHolder.setCharging(false)
                    return@launch
                }

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
            } finally {
                // Lanzado o fallido: desde aquí la sesión la protegen `isCharging` y el estado de
                // la pantalla (la recuperación y el panel los revisan dentro del mismo candado).
                candadoDeSesion.unlock()
            }
            // NOTE: paymentStateHolder.setCharging(false) is cleared in the
            // result handlers (onAngelPayResult / onAngelPaySdkResult / error
            // emission inside startSdkCardPayment) via clearChargingOnTerminal().
        }
    }

    /**
     * T26 (Testarudo, 2026-09-11) — deja la sesión del SDK lista ANTES de esperar al comercio.
     *
     *  - Sin sesión, con la auth en error/sin autenticar, o con la sesión en OTRA cuenta:
     *    autentica con la cuenta del comercio elegido ([AngelPayAuthRepository.ensureAuthenticatedAs];
     *    el genérico sólo para comercios legacy sin cuenta — regla de Amaena).
     *  - Si la sesión quedó eligiendo comercio, completa la selección con su token; si el
     *    comercio activo no es el elegido y no hay un cambio en vuelo, lo cambia.
     *
     * Devuelve false (y deja la pantalla en Error) si algo falla. Todo esto ocurre ANTES de
     * la libreta y del SDK: nada capaz de autorizar empezó. La auth de `startSdkCardPayment`
     * y los candados de alineación siguen igual después.
     */
    private suspend fun asegurarSesionAntesDeEsperar(): Boolean {
        val merchant = _currentMerchant.value
        val targetId = merchant?.let { runCatching { it.requireAngelpayMerchantId() }.getOrNull() }
        val cuentaObjetivo = merchant?.angelpayUserAccountId
        val estado = angelPayAuthRepository.state.value
        val sesionViva = runCatching { sdkGateway.isAuthenticated() }.getOrDefault(false)
        val cuentaDeSesion = angelPayAuthRepository.getCurrentAngelPayAccountId()
        val sesionLista = sesionViva &&
            (estado is AngelPayAuthState.Authenticated || estado is AngelPayAuthState.ConfigMismatchBanner) &&
            (cuentaObjetivo == null || cuentaDeSesion == null || cuentaDeSesion == cuentaObjetivo)

        if (!sesionLista) {
            Timber.i(
                "🔶 [AngelPay] Auth previa al cobro | estado=%s sesion=%s cuenta=%s→%s",
                estado::class.simpleName, sesionViva, cuentaDeSesion, cuentaObjetivo,
            )
            _state.value = AngelPayPaymentState.ConectandoAngelPay
            val auth = if (cuentaObjetivo != null) {
                angelPayAuthRepository.ensureAuthenticatedAs(cuentaObjetivo)
            } else {
                angelPayAuthRepository.ensureAuthenticated()
            }
            if (auth.isFailure) {
                Timber.e(auth.exceptionOrNull(), "❌ [AngelPay] La auth previa al cobro falló")
                val base = when ((angelPayAuthRepository.state.value as? AngelPayAuthState.AuthError)?.kind) {
                    AuthErrorKind.SIN_RED -> "Sin conexión con AngelPay."
                    AuthErrorKind.SIN_CREDENCIALES -> "Faltan credenciales de AngelPay en el panel."
                    AuthErrorKind.CUENTA_NO_EN_CONFIG -> "La cuenta AngelPay del comercio no está en el panel."
                    else -> "No se pudo conectar con AngelPay."
                }
                fallaAntesDeAutorizar(base, mensajeParaPos = "La terminal no pudo conectar con AngelPay")
                return false
            }
        }

        if (targetId == null) return true
        val activo = angelPayMerchantRepository.activeAngelPayMerchantId.value
        if (activo == targetId) return true
        // Un cambio ya en vuelo lo resuelve la espera de siempre; lanzar otro lo cancelaría.
        if (angelPayMerchantRepository.inFlightSwitch.value != null) return true

        _state.value = AngelPayPaymentState.Switching(targetMerchantId = targetId, previousMerchantId = activo)
        val fijado = fijarComercioEnSesion(targetId)
        if (fijado.isFailure) {
            Timber.e(fijado.exceptionOrNull(), "❌ [AngelPay] No se pudo fijar el comercio $targetId antes de cobrar")
            fallaAntesDeAutorizar("No se pudo preparar el comercio en AngelPay.", mensajeParaPos = "La terminal no pudo preparar el comercio")
            return false
        }
        return true
    }

    /**
     * T26: un fallo ANTES de abrir la libreta y el SDK (auth previa o espera del comercio).
     * La pantalla queda en Error con «Reintentar» sobre la MISMA solicitud.
     *
     * 🔴 Regla H.3 (diseño del 11-sep, auditoría de Codex): una solicitud remota tiene a lo más
     * UN desenlace final emitido, y desde que se emite la terminal ya no la ejecuta. Emitir aquí
     * `failed + PRE_AUTHORIZATION` con «Reintentar» todavía en pantalla haría que el servidor la
     * diera por NOT_CHARGED, la tablet soltara su llave… y un reintento en la N86 podría cobrar
     * ESA misma solicitud. Por eso el final se RETIENE, igual que el aviso EMV recuperable:
     *  - sale cuando el cajero cancela o sale del cobro ([emitCancelledIfAbandoned]) o cuando
     *    vence el reloj de abandono ([programarCierreDeCobroAbandonado]);
     *  - si el reintento autentica y cobra, sale `success` y no hubo final negativo.
     *
     * Sólo se retiene (y por tanto sólo se afirmará «no se cobró») cuando consta: esta
     * solicitud no lanzó nada ([authorizationWasLaunched]) y la libreta no tiene ningún cobro
     * sin resolver (un ViewModel recreado no recuerda; la libreta sí). Antes no se emitía
     * NUNCA nada aquí y la tablet terminaba en UNKNOWN con la terminal reservada.
     */
    private suspend fun fallaAntesDeAutorizar(base: String, mensajeParaPos: String) {
        val esRemoto = _paymentSource == "SOCKET" && _socketRequestId != null && !_socketResultEmitted
        if (esRemoto) {
            val constaQueNoSeCobro = !authorizationWasLaunched && paymentAttemptLedger.cobroSinResolver() == null
            if (constaQueNoSeCobro) {
                finalPreAutorizacionRetenido = mensajeParaPos
                Timber.i("📡 [AngelPay Socket] Falla antes de autorizar — final RETENIDO mientras se pueda reintentar")
            } else {
                finalPreAutorizacionRetenido = null
                Timber.w("📡 [AngelPay Socket] Falla antes de autorizar SIN final retenido: no consta que no hubo cobro")
            }
        }
        _state.value = AngelPayPaymentState.Error(message = "$base Reintenta.", canRetry = true)
        if (esRemoto && finalPreAutorizacionRetenido != null) programarCierreDeCobroAbandonado()
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
            // 📡 T26 / H.3: se sigue ofreciendo «Reintentar» sobre ESTA solicitud, así que el final
            // NO sale aquí: se retiene y sale (failed + PRE_AUTHORIZATION) sólo al cancelar/salir o
            // al vencer el reloj de abandono — antes no salía nunca y la tablet terminaba en UNKNOWN
            // con la terminal reservada, el caso de Testarudo. Ver fallaAntesDeAutorizar.
            fallaAntesDeAutorizar("Cambio de merchant no se completó.", mensajeParaPos = "La terminal no pudo preparar el comercio")
            false
        }
    }

    /** Task 32 — single source of truth for clearing the charging flag on any terminal state. */
    private fun clearChargingOnTerminal() {
        paymentStateHolder.setCharging(false)
    }

    // ── Task 3 (cobro resiliente en red lenta) — vigilante de autorización, riel AngelPay ──

    // 🔴 OBSERVADOR, NO TIMEOUT. En AngelPay la autorización no es una única llamada
    // suspend como en Blumon: el SDK/app AngelPay corre fuera de nuestro proceso y el
    // resultado vuelve async vía onAngelPayResult/onAngelPaySdkResult. Este job corre en
    // paralelo desde que se lanza el intent (onIntentLaunched) hasta que el resultado
    // llega, y SOLO publica avisos de UI sobre WaitingForResult. Jamás cancela la
    // autorización: si el procesador aprobó y "canceláramos" aquí, sólo estaríamos
    // mintiendo en pantalla sobre un cobro que sí se hizo — el dinero ya se movió fuera
    // de nuestro control.
    private var authWatchdogJob: Job? = null

    // 📊 Task 6 — real wall-clock start of the CURRENT authorization attempt, captured by
    // startAuthorizationWatchdog(). Read back in onAngelPayResult/onAngelPaySdkResult's
    // `finally` to compute durationMs for the local telemetry batch — deliberately NOT a
    // second System.currentTimeMillis() call there, and NOT the watchdog's own `elapsed`
    // accumulator (that's a virtual/test-driven clock via delay(), not real elapsed time).
    private var authWatchdogStartedAtMs: Long = 0L

    @VisibleForTesting
    internal fun startAuthorizationWatchdog(startedAt: Long = System.currentTimeMillis()) {
        authWatchdogStartedAtMs = startedAt
        authWatchdogJob?.cancel()
        authWatchdogJob = viewModelScope.launch {
            // 🔴 El tiempo transcurrido se acumula sumando los delays, NO leyendo
            // System.currentTimeMillis(). En un test, advanceTimeBy() mueve el reloj
            // VIRTUAL de las corrutinas pero no el del sistema: leer el reloj real
            // aqui hacia que el nivel nunca subiera de NONE, el bucle nunca saliera
            // y runTest esperara para siempre — el test no fallaba, se colgaba.
            var elapsed = 0L
            while (isActive) {
                delay(WATCHDOG_TICK_MS)
                elapsed += WATCHDOG_TICK_MS
                val level = authWatchdogLevel(elapsed)
                if (level != AuthWatchdogLevel.NONE) publishAuthWatchdogLevel(level)
            }
        }
    }

    private fun stopAuthorizationWatchdog() {
        authWatchdogJob?.cancel()
        authWatchdogJob = null
        publishAuthWatchdogLevel(AuthWatchdogLevel.NONE)
    }

    /**
     * Aplica el aviso del vigilante al estado `WaitingForResult` actual, si sigue vigente.
     * No-op sobre cualquier otro estado — en particular `RecordingPayment`, que es la
     * espera del REGISTRO (no de la autorización) y tiene su propio diseño pendiente; el
     * vigilante nunca debe escribir sobre ella.
     */
    private fun publishAuthWatchdogLevel(level: AuthWatchdogLevel) {
        val current = _state.value
        if (current is AngelPayPaymentState.WaitingForResult) {
            _state.value = current.copy(watchdogLevel = level)
        }
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
            // 📡 POS→TPV: TRANSIENT/retryable pre-money error (money did NOT move). Do NOT emit and do NOT
            // clear _socketRequestId — a terminal-side retry can still succeed on THIS request; leaving the
            // id set lets that success re-emit "success" to the still-open POS long-poll (~315s), avoiding a
            // stale "failed" → human double-charge. Trade-off: on ABANDONMENT the long-poll times out →
            // server watchdog marks the row UNKNOWN (false-busy) — accepted over the double-charge risk.
            clearChargingOnTerminal()
            return
        }

        val staffName = secureStorage.getStaffName()
        val paymentAttemptId = ensurePaymentAttemptId()

        // Task 31 — delegate auth orchestration to AngelPayAuthRepository so the
        // D4 resolver (backend-preferred + BuildConfig fallback), retry/backoff,
        // state machine, and post-auth config validation all live in one place.
        //
        // Incidente Amaena (2026-07-29): con la sesión SDK muerta en este punto, el
        // `ensureAuthenticated()` genérico autenticaba la cuenta PRIMARIA del venue
        // DESPUÉS de que waitForMerchantToSettle ya había pasado — cobrando una
        // afiliación y registrando otra. Autentica explícitamente COMO la cuenta del
        // merchant seleccionado; el genérico queda solo para merchants legacy sin
        // angelpayUserAccountId.
        val selectedAccountId = _currentMerchant.value?.angelpayUserAccountId
        val authResult = if (selectedAccountId != null) {
            angelPayAuthRepository.ensureAuthenticatedAs(selectedAccountId)
        } else {
            angelPayAuthRepository.ensureAuthenticated()
        }
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
            // 📡 POS→TPV: TRANSIENT/retryable pre-money error (money did NOT move). Do NOT emit and do NOT
            // clear _socketRequestId — a terminal-side retry can still succeed on THIS request; leaving the
            // id set lets that success re-emit "success" to the still-open POS long-poll (~315s), avoiding a
            // stale "failed" → human double-charge. Trade-off: on ABANDONMENT the long-poll times out →
            // server watchdog marks the row UNKNOWN (false-busy) — accepted over the double-charge risk.
            clearChargingOnTerminal()
            return
        }

        // Gate de alineación post-auth (incidente Amaena 2026-07-29): la auth de arriba
        // pudo re-establecer la sesión — NUNCA entregarle un cobro al SDK con su sesión
        // en un merchant distinto al seleccionado. Fail-closed: una sesión no verificable
        // no mueve dinero.
        if (!sessionAlignedWithSelectedMerchant()) {
            _state.value = AngelPayPaymentState.Error(
                message = "La sesión de AngelPay quedó en otra cuenta. Vuelve a seleccionar el comercio e intenta de nuevo.",
                canRetry = true,
            )
            // 📡 POS→TPV: TRANSIENT/retryable pre-money error (money did NOT move). Do NOT emit and do NOT
            // clear _socketRequestId — a terminal-side retry can still succeed on THIS request (same
            // rationale as the other pre-money sites above).
            clearChargingOnTerminal()
            return
        }

        // 📒 [Libreta] Pre-SDK barrier — committed after session alignment, before validation/launch.
        if (!openLedgerAttemptAndMarkAuthorizing(paymentAttemptId)) {
            // 🛑 La cerca de la solicitud remota se traduce a lo que pasó, nunca a «no se pudo guardar».
            traducirBarreraRechazada()
            clearChargingOnTerminal()
            return
        }

        // Deja en el log CONTRA QUÉ TIPO de comercio se va a cobrar. Desde que mandamos
        // `tipCents = 0` ningún comercio rechaza la propina, así que ésta es la única forma
        // de saberlo — y era el dato que faltó para diagnosticar el incidente de Rest MX.
        sdkGateway.logTipoDeComercio(contexto = "antes de cobrar")

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
        // 📡 POS→TPV: TRANSIENT/retryable pre-money error (money did NOT move). Do NOT emit and do NOT
        // clear _socketRequestId — a terminal-side retry can still succeed on THIS request; leaving the id
        // set lets that success re-emit "success" to the still-open POS long-poll (~315s), avoiding a stale
        // "failed" → human double-charge. Trade-off: on ABANDONMENT the long-poll times out → server
        // watchdog marks the row UNKNOWN (false-busy) — accepted over the double-charge risk.
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
        // 💰 Se loguea el monto REALMENTE enviado al procesador, no sólo lo que creemos cobrar.
        // El bug de propinas (2026-08-09/10, $1,225.65 cobrados de menos en Rest MX) vivió meses
        // invisible porque este log decía "total=379.50" mientras el request llevaba 33000 centavos.
        // Con `enviadoAngelPay` cualquier divergencia se ve en logcat/Better Stack sin abrir el portal.
        val enviadoAngelPay = BigDecimal.valueOf(request.amountCents).movePointLeft(2)
        Timber.i(
            "🔶 [AngelPay SDK] Payment request ready | subtotal=$pendingAmount, tip=$pendingTip, " +
                "total=$totalAmount, enviadoAngelPay=$enviadoAngelPay, qaTipFallback=$usedQaTipFallback"
        )
        // 🔴 Invariante de dinero: el cobro NUNCA puede ser menor al total registrado en Avoqado.
        // Alarma ruidosa y grepeable — si esto aparece, el cliente está pagando de menos.
        if (enviadoAngelPay.compareTo(totalAmount) != 0) {
            Timber.e(
                "🚨 [AngelPay SDK] MONTO DIVERGENTE — se cobra $enviadoAngelPay pero el total registrado " +
                    "es $totalAmount (subtotal=$pendingAmount + propina=$pendingTip)"
            )
        }
    }

    // 📒 [Libreta] `suspend` since 2026-07-18: the write-ahead row must be COMMITTED before
    // the launch trigger (`_state.value = LaunchingAngelPay`, which the Screen reacts to by
    // firing the intent) — same write→launch relocation Task 4 applied to Blumon. Every call
    // site already runs inside a coroutine (startCardPayment's viewModelScope.launch, or the
    // suspend SDK path's fallbacks), so no call-site restructuring was needed.
    private suspend fun startAppToAppCardPayment(credentials: AngelPayCredentials) {
        val staffName = secureStorage.getStaffName()
        val paymentAttemptId = ensurePaymentAttemptId()

        // 📒 [Libreta] Pre-intent barrier. On the SDK→app-to-app in-session fallback the row
        // is already open+AUTORIZANDO — the helper's attemptId guard + the CAS make this a no-op.
        if (!openLedgerAttemptAndMarkAuthorizing(paymentAttemptId)) {
            // 🛑 La cerca de la solicitud remota se traduce a lo que pasó, nunca a «no se pudo guardar».
            traducirBarreraRechazada()
            clearChargingOnTerminal()
            return
        }

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
        authorizationWasLaunched = true // Prevent stale card proof from crossing payment methods.
        confirmedNegativeOutcome = false
        confirmedNegativeEvidence = null
        // 🔴 Se toma SÍNCRONO, antes de lanzar: un candado DENTRO del `launch` no protegería de
        // un segundo toque que entre mientras el primero está suspendido. Ver [cobroEnEfectivoEnVuelo].
        if (!cobroEnEfectivoEnVuelo.compareAndSet(false, true)) {
            Timber.w("🔶 [AngelPay] Toque ignorado: ya hay un cobro en efectivo en vuelo")
            return
        }
        viewModelScope.launch {
            try {
                // 🛑 C.5: el efectivo de un cobro del POS se ancla ANTES de registrar. Si el cancel ya ganó,
                // aquí se para: registrar sería cobrar algo que el servidor dio por cancelado.
                if (!puedeArrancarEfectivoOCripto()) return@launch
                _state.value = AngelPayPaymentState.ProcessingCash()

                com.jaac.avoqado_tpv.core.observability.CrashlyticsContext.setPaymentContext(
                    processor = "ANGELPAY",
                    method = "CASH",
                    merchantId = _currentMerchant.value?.merchantAccountId,
                    amount = pendingAmount.add(pendingTip).toPlainString(),
                    orderId = pendingOrderId,
                    attemptId = currentPaymentAttemptId,
                )

                // 💰 Money moved (cash collected). Recover venue/staff from the most reliable source so
                // this sale is ALWAYS recorded or enqueued — never dropped (cached → auth → secureStorage).
                val venueId = cachedVenueId ?: authRepository.getVenueId() ?: secureStorage.getVenueId()
                val staffId = cachedStaffId ?: authRepository.getStaffId() ?: secureStorage.getStaffId()
                if (venueId == null || staffId == null) {
                    _state.value = reportChargedButUnrecordable(
                        paymentLabel = "El pago en efectivo",
                        error = IllegalStateException("venue/staff no recuperable tras cobro en efectivo"),
                    )
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
                    terminalPaymentRequestId = _socketRequestId, // 📡 POS→TPV arbitration link (null unless socket-sourced)
                    cardDetails = CardDetails.CASH,
                    authorizationCode = "EFECTIVO",
                    referenceNumber = "CASH-$timestamp",
                    orderId = pendingOrderId,
                    orderNumber = pendingOrderNumber,
                    // 📸 Serialized inventory (SIM) proof-of-sale — empty for a normal payment
                    isPortabilidad = pendingIsPortabilidad,
                    serialNumbers = pendingSerialNumbers,
                )

                Timber.d("🔶 [AngelPay] Recording cash payment | amount=$pendingAmount, tip=$pendingTip")

                // 🔴 NonCancellable: si el proceso muere/la pantalla se abandona a media
                // espera (recordPaymentUseCase reintenta hasta 5 veces con backoff, ~67.5s
                // peor caso), la llamada NO debe abortarse a medio camino — sin esto el
                // cobro puede saltarse la cola offline por completo.
                val recordResult = withContext(NonCancellable + Dispatchers.IO) {
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
                        // 📡 POS→TPV: report success to the caller (no-op unless socket-sourced).
                        emitSocketResultIfSocketSourced(
                            status = "success",
                            paymentId = receipt.paymentId,
                            transactionId = receipt.paymentId,
                            receiptUrl = receipt.receiptUrl,
                            receiptAccessKey = receipt.accessKey,
                        )
                    },
                    onFailure = { error ->
                        Timber.e(error, "🔶 [AngelPay] Cash payment failed to record to backend — enqueueing for sync")
                        // Cash rows are first-class queue citizens: QueuedPayment detects the
                        // CASH-*/EFECTIVO markers and replays with merchant=null + CardDetails.CASH.
                        _state.value = handleRecordFailure("El pago en efectivo", paymentContext, error)
                    },
                )
            } finally {
                // Un solo punto de salida: cubre también el `return@launch` de
                // «venue/staff no recuperable».
                cobroEnEfectivoEnVuelo.set(false)
            }
        }
    }

    // ── AngelPay Launchers Result ────────────────────────────────────

    /**
     * Called by Screen after launching the AngelPay intent.
     */
    fun onIntentLaunched() {
        authorizationWasLaunched = true
        confirmedNegativeOutcome = false
        confirmedNegativeEvidence = null
        _state.value = AngelPayPaymentState.WaitingForResult()
        startAuthorizationWatchdog()
        Timber.d("🔶 [AngelPay] Intent launched, waiting for result...")
    }

    /**
     * Called by Screen when AngelPay returns via onActivityResult.
     */
    fun onAngelPayResult(resultCode: Int, data: Intent?) {
        viewModelScope.launch {
          // 🔴 El vigilante que arrancó en onIntentLaunched observa hasta que este
          // resultado (cualquiera que sea) queda resuelto. Cubre éxito, declinación,
          // cancelación y el registro subsecuente — NUNCA cancela la autorización, solo
          // deja de publicar avisos cuando ya no hay nada que observar.
          // 📊 Task 6 — set by whichever branch below resolves; null stays null on a
          // duplicate/stale result (consumeResultForCurrentAttempt returns false), which
          // deliberately records nothing for an attempt already accounted for.
          var authAttemptOutcomeCode: String? = null
          try {
            // 🔴 RECUPERACIÓN antes de consumir nada. Un ViewModel recreado (el sistema mató la
            // Activity, el cajero volvió atrás) nace SIN memoria del cobro en vuelo: sus campos
            // viven en RAM y no viajan en el SavedStateHandle. Sin esto, el resultado se
            // procesaba como si no hubiera intento — y la libreta se quedaba con la fila
            // AUTORIZANDO para siempre mientras la pantalla volvía a estar lista para cobrar.
            // La libreta SÍ sobrevive: se adopta de ahí el intento que este objeto no recuerda.
            if (currentPaymentAttemptId == null && _socketRequestId != null) {
                paymentAttemptLedger.cobroSinResolver()?.attemptId?.let { pendiente ->
                    Timber.w("📒 [AngelPay] ViewModel recreado: se adopta el intento pendiente %s", pendiente)
                    currentPaymentAttemptId = pendiente
                    authorizationWasLaunched = true
                    ledgerOpenedAttemptId = pendiente
                }
            }
            if (!consumeResultForCurrentAttempt(source = "app_to_app")) return@launch
            Timber.i("🔶 [AngelPay] onAngelPayResult | resultCode=$resultCode")
            _state.value = AngelPayPaymentState.WaitingForResult(message = "Validando resultado del pago...")

            val parsedResult = resultParser.parse(resultCode, data)
            // A cancellation before the durable pre-launch barrier has no SDK authorization.
            // Once a ledger attempt exists, an empty activity callback stays unknown.
            // 🔴 «No me acuerdo» NO es prueba de que no se cobró. Las dos banderas de arriba
            // viven en RAM y NO van al SavedStateHandle: un ViewModel recreado (el sistema
            // mató la Activity, el cajero volvió atrás) nace con authorizationWasLaunched=false
            // y ledgerOpenedAttemptId=null aunque en la libreta haya una fila AUTORIZANDO.
            // Con eso, un callback vacío se degradaba a Cancelled y se publicaba
            // «PRE_AUTHORIZATION» — afirmando que no hubo cobro sobre uno que pudo ocurrir.
            // Sólo se consulta cuando la memoria dice que no hay nada: el camino normal no
            // paga el viaje a Room ni cambia de comportamiento.
            val cobroPendiente = if (!authorizationWasLaunched && ledgerOpenedAttemptId == null)
                paymentAttemptLedger.cobroSinResolver() else null
            val result = if (!authorizationWasLaunched && ledgerOpenedAttemptId == null && cobroPendiente == null &&
                data == null && resultCode != android.app.Activity.RESULT_OK &&
                parsedResult is AngelPayResult.Failure && parsedResult.code == "UNKNOWN") AngelPayResult.Cancelled else parsedResult

            if (result is AngelPayResult.Failure && AngelPayOutcomeClassifier.clasificar(
                    aprobado = false, status = null,
                    codigoSdk = result.code.takeUnless { it.length == 2 },
                    codigoGateway = result.code.takeIf { it.length == 2 },
                ) == DesenlaceDelCobro.INCIERTO) {
                authAttemptOutcomeCode = result.code
                // El id puede venir de la LIBRETA cuando este ViewModel es uno recreado: la
                // fila existe aunque el objeto no la recuerde. Sin esto, el cobro quedaba
                // AUTORIZANDO para siempre y nadie lo marcaba como «no sé qué pasó».
                (currentPaymentAttemptId ?: cobroPendiente?.attemptId)?.let {
                    paymentAttemptLedger.markIndeterminate(it, "AngelPay ${result.code}")
                }
                verificarCobroIncierto(result.message, result.code, null, null, null)
                return@launch
            }

            confirmedNegativeOutcome = result !is AngelPayResult.Success
            // 🛑 P2-13: UNA sola tabla de rechazos confirmados ([AngelPayOutcomeClassifier]). La lista
            // paralela que vivía aquí ({G500,G504,E605,E606}) mandaba como PRE_AUTHORIZATION —«nada capaz
            // de autorizar empezó»— rechazos que el SDK ya había lanzado (D308, C208, E608…).
            confirmedNegativeEvidence = when (result) {
                is AngelPayResult.Cancelled -> "PRE_AUTHORIZATION"
                // Llegar aquí ya significa RECHAZADO_CONFIRMADO (lo incierto salió arriba) y que la app de
                // AngelPay corrió: la evidencia es la de la tabla única, no una lista de códigos local.
                is AngelPayResult.Failure -> AngelPayOutcomeClassifier.EVIDENCIA_RECHAZO_CONFIRMADO
                is AngelPayResult.Success -> null
            }
            // 📒 [Libreta] AngelPay's single return IS the host verdict (no separate
            // host-response moment exists for this processor). Marked here, before any
            // record/publish step — everything since the result arrived (consume guard,
            // state set, parse) is non-suspending, so an approval can never be lost to a
            // cancellation before this write. approved=false (decline AND user cancel:
            // money did not move) lands the row in DESCARTADA inside the ledger.
            // currentPaymentAttemptId is read directly (never ensurePaymentAttemptId():
            // minting an id here would be a state side effect for a stray null-attempt
            // result, and a minted id matches no row anyway).
            currentPaymentAttemptId?.let { attemptIdForLedger ->
                paymentAttemptLedger.markHostResponded(
                    attemptId = attemptIdForLedger,
                    approved = result is AngelPayResult.Success,
                    operationId = null,
                    referenceNumber = (result as? AngelPayResult.Success)?.referenceNumber,
                    authCode = (result as? AngelPayResult.Success)?.authorizationCode,
                )
            }

            when (result) {
                is AngelPayResult.Success -> {
                    // 📊 Task 6 — AngelPay's own catalog code when present (e.g. "S000"), never
                    // result.message (human-readable, can carry amount/reference).
                    authAttemptOutcomeCode = result.transactionCode ?: "SUCCESS"
                    recordCardPayment(result)
                }
                is AngelPayResult.Failure -> {
                    authAttemptOutcomeCode = result.code
                    logPaymentDecline(
                        source = "app_to_app",
                        sdkCode = result.code,
                        category = result.category,
                        sdkMessage = result.message,
                        displayMessage = result.message,
                    )
                    _state.value = AngelPayPaymentState.Error(
                        message = result.message,
                        canRetry = true,
                    )
                    // 🛑 H.3: aviso EMV recuperable o rechazo del banco, da igual — mientras la pantalla
                    // ofrezca «Reintentar» esta misma solicitud, su desenlace NO sale.
                    retenerDesenlaceDeRechazo(result.message)
                }
                is AngelPayResult.Cancelled -> {
                    authAttemptOutcomeCode = "CANCELLED"
                    _state.value = AngelPayPaymentState.Cancelled
                    // 📡 POS→TPV: user cancelled at the terminal (no-op unless socket-sourced).
                    emitSocketResultIfSocketSourced(
                        status = "cancelled",
                        outcomeEvidence = confirmedNegativeEvidence,
                        errorMessage = "Pago cancelado en la terminal",
                    )
                }
            }
            // Task 32 — clear D2 charging gate on any terminal outcome.
            clearChargingOnTerminal()
          } finally {
            // Cubre éxito, declinación, cancelación y el registro subsecuente. Un
            // observador que no se apaga es una fuga que sigue escalando avisos sobre
            // una pantalla que ya cambió.
            stopAuthorizationWatchdog()
            // 📊 Task 6 — fire-and-forget local telemetry; null (duplicate/stale result,
            // or an early return before any branch set it) records nothing. Never blocks
            // or affects the charge — record() itself never throws.
            authAttemptOutcomeCode?.let { code ->
                runCatching {
                    authAttemptTelemetryStore.record(
                        code = code,
                        durationMs = System.currentTimeMillis() - authWatchdogStartedAtMs,
                        rail = "ANGELPAY"
                    )
                }
            }
          }
        }
    }

    /**
     * 🔴 Verifica lo que AngelPay REALMENTE cobró contra lo que Avoqado registró.
     *
     * Todo lo demás en esta app verifica lo que *mandamos*. Esto verifica lo que *pasó*, que
     * es lo único que no depende de suposiciones sobre el procesador: da igual el tipo de
     * comercio, cómo interprete `tipCents`, o si mañana cambian el SDK.
     *
     * Existe por el incidente de Rest MX (2026-08-09/10): 11 ventas cobradas de menos por
     * $1,225.65 que **nadie detectó durante días** porque el cobro se aprobaba y la app
     * mostraba éxito. El daño no fue el bug: fue el silencio. Esta comprobación convierte
     * ese silencio en una alarma inmediata en Crashlytics.
     *
     * `Timber.e` a propósito: ProGuard borra `d/v/i` en release (`-assumenosideeffects`),
     * así que un log informativo NO existiría en las terminales. El nivel error sobrevive
     * y llega a Crashlytics.
     *
     * No bloquea ni revierte nada: cuando esto corre el dinero YA se movió y mentir en
     * pantalla sobre un cobro hecho sería peor. Su trabajo es que te enteres el mismo día.
     */
    internal fun verificarMontoCobrado(result: PaymentResult) {
        if (!result.approved) return

        val alarma = describirDivergenciaDeCobro(
            cobradoCents = result.amount,
            venta = pendingAmount,
            propina = pendingTip,
        ) ?: return

        Timber.e(
            "$alarma | auth=${result.authCode}, ref=${result.reference}, " +
                "merchant=${_currentMerchant.value?.displayName}"
        )
    }


    fun onAngelPaySdkResult(result: PaymentResult) {
        viewModelScope.launch {
          // 🔴 Mismo vigilante, mismo invariante que onAngelPayResult: observa desde
          // onIntentLaunched hasta que este resultado (incluida la relanzada por
          // expiración de sesión) queda resuelto. Nunca cancela la autorización.
          // 📊 Task 6 — set ONLY on a terminal outcome (never on the session-expiry
          // relaunch below, which is deliberately not a resolved attempt).
          var authAttemptOutcomeCode: String? = null
          try {
            if (!consumeResultForCurrentAttempt(source = "sdk_contract")) return@launch
            Timber.i("🔶 [AngelPay SDK] Result received | approved=${result.approved}, status=${result.status}, montoCobrado=${result.amount}")
            verificarMontoCobrado(result)
            _state.value = AngelPayPaymentState.WaitingForResult(message = "Validando resultado del pago...")

            confirmedNegativeOutcome = false
            confirmedNegativeEvidence = null
            // 📒 [Libreta] AngelPay's single return IS the host verdict (no separate
            // host-response moment exists for this processor). One deliberate exception:
            // a session-expiry-shaped DECLINE (D308 / pre-charge register failure — money
            // did NOT move, per AngelPayErrorMapper) may be RELAUNCHED with the SAME
            // attemptId by tryRecoverFromSessionExpiry below; marking DESCARTADA now would
            // blind the row to that retry's outcome, so its mark is deferred until the
            // recovery decision. The shape checks are pure (no suspension), and an APPROVED
            // result is always marked HERE — before any cancellable work — so an approval
            // can never be lost. (Mirrors the `sessionExpiryShape` when-block below.)
            val ledgerExpiryShapedDecline = !result.approved && (
                AngelPayErrorMapper.isAuthError(result.callResult?.code) ||
                    AngelPayErrorMapper.isPreChargeRegisterFailure(result.message)
                )

            // 🔍 Los TRES desenlaces (2026-09-08). Antes esto era un `if (approved)` con un
            // `else` que metía en el mismo saco un rechazo del emisor y un "no sé qué pasó".
            // Ver [AngelPayOutcomeClassifier]: la pregunta no es "¿falló?" sino "¿contestó
            // el procesador?".
            val desenlace = when {
                // 🔴 La forma de sesión expirada GANA sobre "no se sabe". No es una excepción
                // cómoda: en esas dos formas está DEMOSTRADO que el SDK abortó antes de llamar
                // al gateway (D308 y el fallo de registro previo al cobro, ambos verificados
                // contra el AAR en AngelPayErrorMapper), así que el dinero no se movió y
                // relanzar no puede duplicar nada. Sin esta precedencia, un fallo de registro
                // que además llegara con `status=TIMEOUT` bloquearía una venta buena y le
                // quitaría al cajero la re-autenticación automática que ya funciona.
                ledgerExpiryShapedDecline -> DesenlaceDelCobro.RECHAZADO_CONFIRMADO
                else -> AngelPayOutcomeClassifier.clasificar(
                    aprobado = result.approved,
                    status = nombreDeStatus(result),
                    codigoSdk = result.callResult?.code,
                    codigoGateway = result.code,
                )
            }

            // 📒 Un desenlace INCIERTO no puede marcar DESCARTADA: esa fila afirma "no se
            // cobró" y además se poda a los 7 días, así que la evidencia del único caso en que
            // hace falta desaparecería. Va a INDETERMINADO, que el barrido NUNCA borra y que
            // los CAS de abajo ya aceptan como origen — así, si el veredicto llega tarde
            // (por la verificación o por un resultado rezagado), la fila se resuelve sola.
            if (desenlace == DesenlaceDelCobro.INCIERTO) {
                currentPaymentAttemptId?.let { attemptIdForLedger ->
                    paymentAttemptLedger.markIndeterminate(
                        attemptId = attemptIdForLedger,
                        reason = "AngelPay sin veredicto: status=${nombreDeStatus(result)} " +
                            "code=${result.callResult?.code ?: "sin-codigo"}",
                    )
                }
            } else if (!ledgerExpiryShapedDecline) {
                currentPaymentAttemptId?.let { attemptIdForLedger ->
                    paymentAttemptLedger.markHostResponded(
                        attemptId = attemptIdForLedger,
                        approved = result.approved,
                        operationId = null,
                        referenceNumber = result.reference,
                        authCode = result.authCode,
                    )
                }
            }

            if (desenlace == DesenlaceDelCobro.INCIERTO) {
                // 📊 Task 6 — el intento SÍ quedó resuelto para la telemetría (el cobro no
                // sigue en vuelo), aunque su desenlace sea justamente "no se sabe".
                authAttemptOutcomeCode = result.callResult?.code ?: "INDETERMINADO"
                manejarDesenlaceIncierto(result)
                clearChargingOnTerminal()
                return@launch
            }

            if (result.approved) {
                // 📊 Task 6 — AngelPay's own catalog code when present (e.g. "S000"), never
                // result.message (human-readable, can carry amount/reference).
                authAttemptOutcomeCode = result.callResult?.code ?: "SUCCESS"
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
                    // (📒 the ledger row stays AUTORIZANDO for the relaunched attempt.
                    // 📊 authAttemptOutcomeCode deliberately stays null here too — Task 6
                    // never records a relaunch as a resolved attempt.)
                    return@launch
                }
                // 📊 Task 6 — reached only on a TERMINAL decline (no relaunch above).
                authAttemptOutcomeCode = result.callResult?.code ?: "DECLINED"
                // 📒 [Libreta] Expiry-shaped decline that did NOT relaunch — now it IS the
                // terminal host verdict for this attempt (deferred from the mark above).
                if (ledgerExpiryShapedDecline) {
                    currentPaymentAttemptId?.let { attemptIdForLedger ->
                        paymentAttemptLedger.markHostResponded(
                            attemptId = attemptIdForLedger,
                            approved = false,
                            operationId = null,
                            referenceNumber = result.reference,
                            authCode = result.authCode,
                        )
                    }
                }
                confirmedNegativeOutcome = true
                // 🛑 P2-13: la evidencia sale de la ÚNICA tabla de rechazos confirmados; lo que la separa
                // de PRE_AUTHORIZATION es si el SDK llegó a lanzarse, no una segunda lista de códigos.
                confirmedNegativeEvidence = AngelPayOutcomeClassifier.EVIDENCIA_RECHAZO_CONFIRMADO
                val displayMessage = buildString {
                    append(result.message ?: "Pago rechazado")
                    // El emisor que exige autenticación adicional (1A) NO rechazó la venta:
                    // pide step-up, y con AngelPay el step-up es el chip. Su texto ya va
                    // arriba; esto añade la ACCIÓN, que es lo que le faltaba al cajero
                    // (incidente Amaena 2026-08-31: reintentó a ciegas y aprobó al segundo).
                    // Aditivo: para cualquier otro rechazo devuelve null y el mensaje queda
                    // exactamente igual que antes.
                    AngelPayErrorMapper.accionSugerida(result.message, result.code)?.let { accion ->
                        append("\n\n")
                        append(accion)
                    }
                    result.callResult?.let { call ->
                        if (!call.code.isNullOrBlank()) {
                            append("\n\nSDK ${call.code}: ${call.message ?: "Sin detalle"}")
                        }
                    }
                }
                logPaymentDecline(
                    source = "sdk_contract",
                    sdkCode = result.callResult?.code,
                    category = result.callResult?.category,
                    sdkMessage = result.callResult?.message ?: result.message,
                    displayMessage = displayMessage,
                )

                // 🖨️ Ticket de RECHAZO — el papel que Blumon sí entrega y nosotros no.
                // Sin esto el rechazo sólo vive en pantalla y se va al cerrar el diálogo: el
                // cliente se queda sin nada y el comerciante sin evidencia de por qué no pasó.
                //
                // 🔴 NO puede tumbar el flujo. Esta rama YA va a terminar en Error; si la
                // impresora falla, el cajero debe ver igual el motivo en pantalla. Por eso
                // va con su propio try/catch y NUNCA propaga: un fallo de papel no puede
                // convertirse en una pantalla distinta de la que el cajero espera.
                imprimirTicketDeRechazo(displayMessage, result.callResult?.code)
                _state.value = AngelPayPaymentState.Error(
                    message = displayMessage,
                    canRetry = true,
                )
                // 🛑 H.3 (11-sep): ni el aviso EMV recuperable (E608: el cajero completa ESTA venta por
                // chip) ni el rechazo del banco emiten su desenlace mientras la pantalla siga ofreciendo
                // «Reintentar» esta MISMA solicitud. El caso del aviso ya era así desde el QA del
                // 2026-07-14 (emitir «failed» ahí produjo un cobro doble mediado por una persona); lo que
                // cambia es que el rechazo «de verdad» se trata igual, porque el botón sigue ahí.
                // Sale al salir/cancelar o al vencer el reloj de abandono, y entonces la solicitud queda
                // cercada: `reserveTerminal` ya no admite otro intento suyo.
                retenerDesenlaceDeRechazo(displayMessage)
            }
            // Task 32 — clear D2 charging gate on any terminal outcome.
            clearChargingOnTerminal()
          } finally {
            // Cubre éxito, declinación, la relanzada por expiración de sesión y el
            // registro subsecuente. Un observador que no se apaga es una fuga.
            stopAuthorizationWatchdog()
            // 📊 Task 6 — fire-and-forget local telemetry; null (relaunch, duplicate/stale
            // result, or an early return before any branch set it) records nothing. Never
            // blocks or affects the charge — record() itself never throws.
            authAttemptOutcomeCode?.let { code ->
                runCatching {
                    authAttemptTelemetryStore.record(
                        code = code,
                        durationMs = System.currentTimeMillis() - authWatchdogStartedAtMs,
                        rail = "ANGELPAY"
                    )
                }
            }
          }
        }
    }

    /**
     * Nombre del `PaymentResult.Status`, a prueba de resultados mal formados.
     *
     * Se lee dentro de un `runCatching` a propósito: leer un campo de un resultado del SDK
     * jamás puede tumbar la clasificación de un cobro. Sin status, [AngelPayOutcomeClassifier]
     * decide por los códigos, que es el camino de siempre.
     */
    private fun nombreDeStatus(result: PaymentResult): String? =
        runCatching { result.status.name }.getOrNull()

    /**
     * 🔍 El SDK volvió SIN veredicto del procesador: hay que averiguar si el dinero se movió
     * ANTES de dejar que nadie vuelva a cobrar.
     *
     * La secuencia es la que pide el propio catálogo de AngelPay para `G505` («Resultado no
     * concluyente, **verifique el historial de transacciones**»):
     *
     *  1. La pantalla lo DICE, sin adornos y sin botón de reintentar.
     *  2. Se le pregunta al historial por nuestra `integratorReference`.
     *  3. Si el cobro existía → se registra como cualquier venta aprobada (el dinero se movió,
     *     y perderlo aquí sería el peor final: cliente cobrado, venta inexistente).
     *  4. Un historial vacío/parcial no confirma ausencia: se conserva el pendiente.
     *  5. Si no se pudo preguntar → se queda dicho que no se sabe, y el cobro sigue bloqueado.
     *
     * 📡 En ningún punto se le reporta `failed` al POS: ese status es lo que le pone enfrente
     * al cajero el botón de Reintentar del otro lado del mostrador.
     */
    private suspend fun manejarDesenlaceIncierto(result: PaymentResult) = verificarCobroIncierto(
        result.message?.takeIf { it.isNotBlank() } ?: "La terminal no confirmó el resultado",
        result.callResult?.code, nombreDeStatus(result), result.reference, result.authCode,
    )

    private suspend fun verificarCobroIncierto(
        motivo: String, sdkCode: String?, status: String?, reference: String?, authCode: String?,
    ) {
        Timber.e(
            "🔍 [AngelPay] DESENLACE INCIERTO — status=%s code=%s | attemptId=%s",
            status, sdkCode, currentPaymentAttemptId,
        )
        observability.logWarning(
            tag = "AngelPayResultadoIncierto",
            message = "Cobro sin veredicto del procesador: ${sdkCode ?: "sin-codigo"}",
            metadata = mapOf(
                "sdkCode" to (sdkCode ?: "none"),
                "status" to (status ?: "none"),
                "attemptId" to (currentPaymentAttemptId ?: "none"),
                "amount" to pendingAmount.add(pendingTip).toPlainString(),
            ),
        )

        _state.value = AngelPayPaymentState.ResultadoIncierto(
            message = "$motivo\n\nCobro sin confirmar: NO vuelvas a cobrar hasta verificar. " +
                "Estamos consultando con AngelPay si el pago pasó.",
            verificando = true,
        )

        val attemptId = currentPaymentAttemptId
        if (attemptId == null) {
            // Sin la referencia que viajó con el cobro no hay forma de reconocerlo en el
            // historial, y emparejar por monto/hora sería adivinar. Se queda dicho.
            quedarSinVerificar("no hay referencia del intento")
            return
        }

        // 🔴 `runCatching` aunque el verificador ya capture por dentro: si algo sube desde
        // aquí (construir el adaptador, el SDK ausente en una variante que no toca AngelPay),
        // la excepción reventaría la corrutina y la pantalla se quedaría clavada en
        // "Consultando…" para siempre — un cobro sin confirmar Y sin salida. Ante cualquier
        // fallo el desenlace es el mismo que no poder preguntar: sigue sin saberse.
        val verificacion = runCatching {
            chargeVerifier.verificar(
                attemptId = attemptId,
                // 🔴 El serial REAL del aparato, sin el prefijo `AVQD-`. Antes iba
                // `TerminalConfig.serialNumber`, que NO es de este aparato sino de un COMERCIO
                // Blumon (su default es "2841548417", una PAX): la consulta al historial volvía
                // vacía SIEMPRE y ningún cobro incierto podía acreditarse. Medido en la N86 el
                // 2026-09-12. Mismo desliz que T26 ya corrigió para Crashlytics y no aquí.
                terminalSerial = serialParaAngelPay(secureStorage.getSerialNumber()),
                affiliation = pendingProcessorAffiliation,
            )
        }.getOrElse { error ->
            Timber.e(error, "🔍 [AngelPay] La verificación del cobro falló de forma inesperada")
            VerificacionDelCobro.NoSePudoVerificar(error.message ?: "error al verificar")
        }

        when (verificacion) {
            is VerificacionDelCobro.Cobrado -> {
                Timber.i("🔍 [AngelPay] El historial CONFIRMA el cobro — se registra la venta")
                // 📒 Ahora sí hay veredicto: la fila sale de INDETERMINADO por su propio CAS.
                paymentAttemptLedger.markHostResponded(
                    attemptId = attemptId,
                    approved = true,
                    operationId = null,
                    referenceNumber = verificacion.referencia,
                    authCode = verificacion.authCode,
                )
                recordCardPayment(
                    authCode = verificacion.authCode,
                    reference = verificacion.referencia,
                    cardBin = verificacion.cardBin,
                )
            }

            VerificacionDelCobro.NoCobrado -> quedarSinVerificar("El historial no ofrece prueba final de ausencia de cobro")

            is VerificacionDelCobro.NoSePudoVerificar -> quedarSinVerificar(verificacion.motivo)
        }
    }

    /**
     * No se pudo preguntar: el desenlace SIGUE sin conocerse. La fila de la libreta se queda
     * en INDETERMINADO (nunca se poda) y la pantalla lo dice con todas sus letras.
     *
     * 📡 Al POS se le manda **`timeout`**, no `failed`. Es lo más cerca de "no sé" que admite
     * el contrato del servidor (`success|failed|cancelled|timeout`): cierra la fila sin
     * afirmar que el cobro falló ni dejar el slot de la terminal retenido, y si el pago
     * aparece después, el servidor reconcilia esa fila a COMPLETED desde el `Payment`
     * registrado (su `lateResult`). Un status inventado sería peor que no mandar nada: el
     * servidor descarta lo que no reconoce, en silencio.
     */
    private fun quedarSinVerificar(motivo: String) {
        Timber.w("🔍 [AngelPay] No se pudo verificar el cobro (%s) — queda sin confirmar", motivo)
        _state.value = AngelPayPaymentState.ResultadoIncierto(
            message = "No se pudo confirmar si el cobro pasó ($motivo).\n\n" +
                "NO vuelvas a cobrar: revisa Transacciones o pregúntale al supervisor antes de intentarlo otra vez.",
            verificando = false,
        )
        emitSocketResultIfSocketSourced(
            status = "timeout",
            errorMessage = "La terminal no pudo confirmar el resultado del cobro. Verifica antes de reintentar.",
        )
    }

    // ── Aviso EMV recuperable abandonado ─────────────────────────────

    private var cierrePorAbandonoJob: Job? = null

    /**
     * Retraso real del reloj de abandono. Existe como propiedad y no como constante para que
     * las pruebas puedan bajarlo a milisegundos: avanzar DOS MINUTOS de reloj virtual dentro
     * de este ViewModel despierta todos sus colectores periódicos, y basta con que uno tropiece
     * para que la excepción contamine la prueba SIGUIENTE (que es justo lo que pasó). En
     * producción vale siempre [MS_ABANDONO_AVISO_EMV].
     */
    @VisibleForTesting
    internal var msAbandonoAvisoEmv: Long = MS_ABANDONO_AVISO_EMV

    /**
     * Arranca el reloj que le cierra la fila al POS si nadie retoma un aviso EMV recuperable.
     *
     * 🔴 Sólo se llama desde estados en los que ya está establecido que **el dinero no se
     * movió** (los avisos EMV: el kernel rechaza la tarjeta antes de cualquier llamada al
     * gateway). Por eso `cancelled` es seguro aquí y no lo sería en un desenlace incierto.
     * Y si aun así apareciera un cobro, el servidor reconcilia la fila a COMPLETED desde el
     * `Payment` registrado: un pago real le gana a cualquier cierre previo.
     *
     * Se apaga solo en cuanto alguien retoma ([retryAfterError], [resetPayment]) o cuando el
     * resultado ya se reportó por otra vía.
     */
    private fun programarCierreDeCobroAbandonado() {
        cancelarCierrePorAbandono()
        if (_paymentSource != "SOCKET" || _socketRequestId == null || _socketResultEmitted) return
        cierrePorAbandonoJob = viewModelScope.launch {
            delay(msAbandonoAvisoEmv)
            val estado = _state.value
            if (estado !is AngelPayPaymentState.Error) {
                // Alguien avanzó: el cobro se retomó o terminó por otro camino.
                return@launch
            }
            // 🛑 H.3: un rechazo retenido (el SDK ya se lanzó) sale AQUÍ si nadie retomó, como final ÚNICO.
            if (declinacionRetenida != null) {
                Timber.w(
                    "📡 [AngelPay Socket] Rechazo sin retomar en %d s — sale su final y la solicitud queda cerrada",
                    msAbandonoAvisoEmv / 1000,
                )
                emitirDeclinacionRetenida()
                solicitudCerradaAntesDeAutorizar = true
                _state.value = AngelPayPaymentState.Error(
                    message = CobroRemotoDelPos.CERRADO_POR_ABANDONO,
                    canRetry = false,
                )
                return@launch
            }
            val retenido = finalPreAutorizacionRetenido
            if (retenido != null && !authorizationWasLaunched) {
                // T26 / H.3: falla antes de autorizar sin retomar — sale su final y la solicitud
                // queda cerrada para esta terminal (sin «Reintentar»).
                Timber.w(
                    "📡 [AngelPay Socket] Falla antes de autorizar sin retomar en %d s — final failed + PRE_AUTHORIZATION",
                    msAbandonoAvisoEmv / 1000,
                )
                emitSocketResultIfSocketSourced(
                    status = "failed",
                    errorMessage = retenido,
                    outcomeEvidence = "PRE_AUTHORIZATION",
                )
                finalPreAutorizacionRetenido = null
                solicitudCerradaAntesDeAutorizar = true
                _state.value = AngelPayPaymentState.Error(
                    message = "El cobro se cerró sin cobrar. Vuelve a cobrar desde el punto de venta.",
                    canRetry = false,
                )
                return@launch
            }
            Timber.w(
                "📡 [AngelPay Socket] Aviso EMV sin retomar en %d s — se cierra la fila del POS como cancelada",
                msAbandonoAvisoEmv / 1000,
            )
            emitSocketResultIfSocketSourced(
                status = "cancelled",
                outcomeEvidence = confirmedNegativeEvidence,
                errorMessage = "El cobro se dejó sin terminar en la terminal",
            )
        }
    }

    private fun cancelarCierrePorAbandono() {
        cierrePorAbandonoJob?.cancel()
        cierrePorAbandonoJob = null
    }

    /**
     * 🛑 H.3 — retiene el desenlace de un rechazo (del banco o del kernel) de un cobro remoto. NO se emite
     * nada: la pantalla sigue ofreciendo «Reintentar» sobre la MISMA solicitud, y emitir aquí haría que el
     * servidor la diera por «no se cobró» mientras esa terminal todavía puede cobrarla.
     */
    private fun retenerDesenlaceDeRechazo(mensaje: String?) {
        if (_paymentSource != CobroRemotoDelPos.FUENTE_SOCKET || _socketRequestId == null || _socketResultEmitted) return
        declinacionRetenida = DesenlaceRetenido(evidencia = confirmedNegativeEvidence, mensaje = mensaje)
        Timber.i("📡 [AngelPay Socket] Rechazo RETENIDO mientras se pueda reintentar | evidencia=%s", confirmedNegativeEvidence)
        programarCierreDeCobroAbandonado()
    }

    /**
     * Emite el rechazo retenido como `failed` — la ÚNICA combinación con la que el servidor acepta
     * `PROCESSOR_DECLINED` como «no se cobró» (con `cancelled` la degrada a timeout y deja la terminal y la
     * tablet bloqueadas). Devuelve true si había uno retenido.
     */
    private fun emitirDeclinacionRetenida(): Boolean {
        val retenida = declinacionRetenida ?: return false
        declinacionRetenida = null
        emitSocketResultIfSocketSourced(
            status = "failed",
            errorMessage = retenida.mensaje,
            outcomeEvidence = retenida.evidencia,
        )
        return true
    }

    /**
     * 🛑 C.5 — el POS canceló y la bandeja lo ACEPTÓ (ya escribió el desenlace durable). Aquí sólo se
     * alinea la pantalla: nada que emitir, nada que reintentar, y el cobro en curso no puede continuar.
     */
    private fun aplicarCancelacionDelPos() {
        cancelarCierrePorAbandono()
        _socketResultEmitted = true // el desenlace durable lo escribió la bandeja: este VM no emite otro
        declinacionRetenida = null
        finalPreAutorizacionRetenido = null
        solicitudCerradaAntesDeAutorizar = true
        cobroCanceladoPorElPos = true
        confirmedNegativeOutcome = true
        confirmedNegativeEvidence = "PRE_AUTHORIZATION"
        clearChargingOnTerminal()
        _mensajeDelPos.value = CobroRemotoDelPos.CANCELADO_POR_EL_POS
        _state.value = AngelPayPaymentState.Cancelled
    }

    /** Aviso visible aunque la app/SDK de AngelPay ocupe la pantalla. Nunca puede tumbar el cobro. */
    private fun avisarEnPantalla(texto: String) {
        runCatching {
            android.widget.Toast.makeText(appContext, texto, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Aviso en pantalla del cancel del POS, filtrado por la solicitud de ESTE cobro. */
    @VisibleForTesting
    internal fun manejarCancelacionRemota(event: SocketEvent.TerminalPaymentCancel) {
        val requestId = event.requestId ?: return
        if (_paymentSource != CobroRemotoDelPos.FUENTE_SOCKET || requestId != _socketRequestId) return
        when (event.disposition) {
            "ACCEPTED" -> {
                val sdkEnPrimerPlano = _state.value.let {
                    it is AngelPayPaymentState.LaunchingAngelPay ||
                        it is AngelPayPaymentState.LaunchingAngelPaySdk ||
                        it is AngelPayPaymentState.WaitingForResult
                }
                Timber.w("🛑 [AngelPay Socket] El POS canceló %s y la terminal lo aceptó", requestId)
                aplicarCancelacionDelPos()
                if (sdkEnPrimerPlano) avisarEnPantalla(CobroRemotoDelPos.CANCELADO_POR_EL_POS)
            }
            // La terminal NO lo aceptó: el cobro ya empezó. Se dice, y no se toca el estado.
            "ACTIVE" -> {
                Timber.w("🛑 [AngelPay Socket] El POS pidió cancelar %s, pero el cobro ya empezó", requestId)
                _mensajeDelPos.value = CobroRemotoDelPos.CANCEL_TARDE
                avisarEnPantalla(CobroRemotoDelPos.CANCEL_TARDE)
            }
            else -> Unit // ALREADY_RESOLVED: el desenlace ya se reprodujo; no hay nada que avisar
        }
    }

    /**
     * Traduce el fallo de la barrera de la libreta a lo que DE VERDAD pasó. «No se pudo guardar el intento»
     * sobre una solicitud cancelada por el POS manda a llamar a soporte por algo que ya está resuelto.
     */
    private suspend fun traducirBarreraRechazada() {
        when (paymentAttemptLedger.cercaDeSolicitud(_socketRequestId)) {
            CercaDeSolicitud.CANCELADA_POR_EL_POS -> aplicarCancelacionDelPos()
            CercaDeSolicitud.CERRADA -> {
                cancelarCierrePorAbandono()
                _socketResultEmitted = true
                solicitudCerradaAntesDeAutorizar = true
                declinacionRetenida = null
                finalPreAutorizacionRetenido = null
                _mensajeDelPos.value = CobroRemotoDelPos.SOLICITUD_CERRADA
                _state.value = AngelPayPaymentState.Error(CobroRemotoDelPos.SOLICITUD_CERRADA, canRetry = false)
            }
            CercaDeSolicitud.LIBRE -> _state.value = AngelPayPaymentState.Error(
                "No se pudo guardar el intento o esta venta tiene un cobro pendiente. No se inició otro cobro.",
                canRetry = false,
            )
        }
    }

    /**
     * 🛑 C.5 — ancla el EFECTIVO o el CRIPTO de un cobro remoto ANTES de registrar nada. Si el cancel del
     * POS ya ganó, no se registra: la terminal no puede cobrar algo que el servidor ya dio por cancelado.
     * Para un cobro iniciado en la terminal no hay nada que anclar y devuelve true.
     */
    private suspend fun puedeArrancarEfectivoOCripto(): Boolean {
        val requestId = _socketRequestId?.takeIf { _paymentSource == CobroRemotoDelPos.FUENTE_SOCKET } ?: return true
        return when (paymentAttemptLedger.iniciarEjecucionNoTarjeta(requestId)) {
            CercaDeSolicitud.LIBRE -> true
            CercaDeSolicitud.CANCELADA_POR_EL_POS -> { aplicarCancelacionDelPos(); false }
            CercaDeSolicitud.CERRADA -> {
                _socketResultEmitted = true
                _mensajeDelPos.value = CobroRemotoDelPos.SOLICITUD_CERRADA
                _state.value = AngelPayPaymentState.Error(CobroRemotoDelPos.SOLICITUD_CERRADA, canRetry = false)
                false
            }
        }
    }

    /**
     * EMV advisories the AngelPay vendor catalog marks `Retry.IMMEDIATE_AFTER_FIX` —
     * the payment SESSION is still alive at the terminal: the cashier fixes the condition
     * (E608 tap over the contactless limit → insert chip; E615 chip required; E603 card
     * removed; E607 CVM failed; …) and retries THIS SAME charge. They are NOT terminal
     * outcomes, so a socket-sourced charge must NOT report "failed" to the POS on them.
     *
     * Source of truth: `AppErrorCatalog$Code` inside the vendored AAR (v1.0.15), extracted
     * via javap 2026-07-14 — every EMV (E6xx) code whose retry policy is IMMEDIATE_AFTER_FIX.
     * The runtime CallResult does not carry the retry policy, hence this mirrored set.
     * NEVER-retry EMV codes (E601 not supported, E604 PIN attempts exceeded, E605/E606
     * offline/online rejection, E610 expired, E619, E621, E622 AMEX) stay hard declines.
     */
    private val recoverableEmvAdvisoryCodes = setOf(
        "E600", "E603", "E607", "E608", "E609", "E611", "E612", "E613", "E614",
        "E615", "E616", "E617", "E618", "E620", "E623", "E624", "E625", "E699",
    )

    private fun isRecoverableEmvAdvisory(sdkCode: String?): Boolean =
        sdkCode != null && sdkCode.uppercase() in recoverableEmvAdvisoryCodes

    /**
     * Reports every terminal (non-recovered) AngelPay decline to the backend/Crashlytics.
     *
     * These declines never reach our server on their own — the SDK's EMV kernel rejects
     * the card (e.g. E608 "Limite contactless excedido") before any gateway call is made,
     * so without this, the only trace of the incident is what the cashier can screenshot.
     */
    private fun logPaymentDecline(
        source: String,
        sdkCode: String?,
        category: String?,
        sdkMessage: String?,
        displayMessage: String,
    ) {
        observability.logWarning(
            tag = "AngelPayDecline",
            message = "Pago rechazado ($source): ${sdkCode ?: "sin-código"} ${sdkMessage ?: ""}".trim(),
            metadata = mapOf(
                "source" to source,
                "sdkCode" to (sdkCode ?: "unknown"),
                "category" to (category ?: "unknown"),
                "sdkMessage" to (sdkMessage ?: "unknown"),
                "displayMessage" to displayMessage,
                "amount" to pendingAmount.toPlainString(),
            ),
        )
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
        // Incidente Amaena (2026-07-29): handleAuthExpiry ahora vuelve a la cuenta que
        // estaba activa, pero si aun así la sesión quedó en OTRO merchant (fallback a
        // primaria, cuenta multi-merchant esperando pick manual), relanzar cobraría esa
        // otra afiliación mientras el registro llevaría la seleccionada. No relanzar:
        // el caller cae al estado de error normal y el cajero re-selecciona.
        if (!sessionAlignedWithSelectedMerchant()) {
            Timber.e(
                "🔐 [AngelPay SDK] Post-expiry re-auth landed on a different merchant than selected — NOT relaunching | attemptId=%s",
                attemptId,
            )
            return false
        }
        launchSdkRequest(request = request, usedQaTipFallback = lastSdkLaunchUsedTipFallback)
        return true
    }

    /**
     * Incidente Amaena (2026-07-29): el SDK cobra el merchant que SU SESIÓN tenga
     * activo, mientras que el registro lleva [_currentMerchant] — si divergen, el
     * dinero y los libros quedan en afiliaciones distintas (7 pagos, $4,344.50).
     *
     * True cuando no hay nada seleccionado con qué alinear (flujos single-merchant
     * pre-selección). Fail-closed: si el merchant de la sesión es desconocido o
     * difiere del seleccionado, NO se cobra.
     */
    private suspend fun sessionAlignedWithSelectedMerchant(): Boolean {
        val merchant = _currentMerchant.value ?: return true
        if (merchant.processorType != ProcessorType.ANGELPAY) return true
        val targetId = runCatching { merchant.requireAngelpayMerchantId() }.getOrNull() ?: return true
        // 🔴 T26: se revalida contra la sesión VIVA del SDK, no sólo contra la marca en memoria:
        // una auth ajena que corrió después (recuperación, panel) pudo dejar el SDK en otra
        // cuenta sin tocar `activeAngelPayMerchantId`. Si la marca existe, también debe coincidir.
        val enMemoria = angelPayMerchantRepository.activeAngelPayMerchantId.value
        val enSesionViva = comercioActivoEnSesionViva()
        if (enSesionViva == targetId && (enMemoria == null || enMemoria == targetId)) return true
        Timber.e(
            "❌ [AngelPay] Session merchant (vivo=%s, memoria=%s) != selected merchant (%s) — blocking charge",
            enSesionViva ?: "unknown",
            enMemoria ?: "null",
            targetId,
        )
        observability.logWarning(
            tag = "AngelPaySessionMerchantMismatch",
            message = "Sesión SDK en merchant ${enSesionViva ?: "desconocido"} pero el cajero seleccionó $targetId — cobro bloqueado",
            metadata = mapOf(
                "selectedMerchantAccountId" to (merchant.merchantAccountId ?: "unknown"),
                "selectedAngelpayMerchantId" to targetId.toString(),
                "sessionAngelpayMerchantId" to (enSesionViva?.toString() ?: "unknown"),
                "memoryAngelpayMerchantId" to (enMemoria?.toString() ?: "null"),
                "attemptId" to (currentPaymentAttemptId ?: "none"),
            ),
        )
        return false
    }

    /** T26: el comercio activo según la sesión VIVA del SDK; null si no se puede saber (fail-closed). */
    private suspend fun comercioActivoEnSesionViva(): Int? = try {
        sdkGateway.getUserMerchants().getOrNull()
            ?.let { list -> list.firstOrNull { it.isActive }?.id ?: list.singleOrNull()?.id }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Timber.w(error, "🔶 [AngelPay] No se pudo leer el comercio de la sesión viva")
        null
    }

    private suspend fun recordCardPayment(result: AngelPayResult.Success) {
        _state.value = AngelPayPaymentState.RecordingPayment()

        // 💰 Money moved (card charged). Recover venue/staff from the most reliable source so this
        // charge is ALWAYS recorded or enqueued — never dropped (cached → auth → secureStorage).
        val venueId = cachedVenueId ?: authRepository.getVenueId() ?: secureStorage.getVenueId()
        val staffId = cachedStaffId ?: authRepository.getStaffId() ?: secureStorage.getStaffId()
        if (venueId == null || staffId == null) {
            _state.value = reportChargedButUnrecordable(
                paymentLabel = "El pago con tarjeta",
                error = IllegalStateException("venue/staff no recuperable tras cobro con tarjeta"),
            )
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
            terminalPaymentRequestId = _socketRequestId, // 📡 POS→TPV arbitration link (null unless socket-sourced)
            cardDetails = cardDetails,
            authorizationCode = result.authorizationCode,
            referenceNumber = result.referenceNumber,
            orderId = pendingOrderId,
            orderNumber = pendingOrderNumber,
            // 📸 Serialized inventory (SIM) proof-of-sale — empty for a normal payment
            isPortabilidad = pendingIsPortabilidad,
            serialNumbers = pendingSerialNumbers,
        )

        // 🔴 NonCancellable: si el proceso muere/la pantalla se abandona a media espera
        // (recordPaymentUseCase reintenta hasta 5 veces con backoff, ~67.5s peor caso),
        // la llamada NO debe abortarse a medio camino — sin esto el cobro puede
        // saltarse la cola offline por completo.
        val recordResult = withContext(NonCancellable + Dispatchers.IO) {
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
                // 📒 [Libreta] Money moved and the backend owns the record from here on:
                // HOST_RESPONDIO → AUTORIZADO (card facts) → REGISTRADO.
                paymentContext.idempotencyKey?.let { attemptIdForLedger ->
                    paymentAttemptLedger.markAuthorized(attemptIdForLedger, null, cardDetails.cardBrand.name, cardDetails.entryMode.name)
                    paymentAttemptLedger.markRecorded(attemptIdForLedger)
                }
                _state.value = successState.copy(receipt = receipt)
                // 📡 POS→TPV: report success to the caller (no-op unless socket-sourced).
                emitSocketResultIfSocketSourced(
                    status = "success",
                    paymentId = receipt.paymentId,
                    transactionId = receipt.paymentId,
                    receiptUrl = receipt.receiptUrl,
                    receiptAccessKey = receipt.accessKey,
                )
            },
            onFailure = { error ->
                Timber.e(error, "🔶 [AngelPay] Card payment failed to record to backend — enqueueing for sync")
                // 📒 [Libreta] REGISTRO_FALLIDO — the row is the evidence until the queue
                // takes over (handleRecordFailure marks ENTREGADA_A_COLA on enqueue success).
                paymentContext.idempotencyKey?.let { paymentAttemptLedger.markRecordFailed(it, error.message) }
                _state.value = handleRecordFailure("El pago con tarjeta", paymentContext, error)
            },
        )
    }

    private suspend fun recordCardPayment(result: PaymentResult) = recordCardPayment(
        authCode = result.authCode ?: "",
        reference = result.reference ?: "",
        cardBin = result.cardBin,
    )

    /**
     * Registra un cobro con tarjeta ya autorizado.
     *
     * Recibe los tres datos que de verdad usa (autorización, referencia y BIN) en vez del
     * `PaymentResult` completo, porque hay DOS orígenes legítimos: el resultado del SDK y
     * —cuando ese resultado nunca llegó— el historial de AngelPay consultado por
     * [AngelPayChargeVerifier]. El camino de registro tiene que ser el MISMO en los dos: un
     * cobro confirmado por verificación se registra exactamente igual que uno normal.
     */
    private suspend fun recordCardPayment(authCode: String, reference: String, cardBin: String?) {
        _state.value = AngelPayPaymentState.RecordingPayment()

        // 💰 Money moved (card charged). Recover venue/staff from the most reliable source so this
        // charge is ALWAYS recorded or enqueued — never dropped (cached → auth → secureStorage).
        val venueId = cachedVenueId ?: authRepository.getVenueId() ?: secureStorage.getVenueId()
        val staffId = cachedStaffId ?: authRepository.getStaffId() ?: secureStorage.getStaffId()
        if (venueId == null || staffId == null) {
            _state.value = reportChargedButUnrecordable(
                paymentLabel = "El pago con tarjeta",
                error = IllegalStateException("venue/staff no recuperable tras cobro con tarjeta"),
            )
            return
        }

        val detectedBrand = cardBin?.let { CardBrand.fromBin(it) } ?: CardBrand.UNKNOWN
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
            terminalPaymentRequestId = _socketRequestId, // 📡 POS→TPV arbitration link (null unless socket-sourced)
            cardDetails = cardDetails,
            authorizationCode = authCode,
            referenceNumber = reference,
            orderId = pendingOrderId,
            orderNumber = pendingOrderNumber,
            // 📸 Serialized inventory (SIM) proof-of-sale — empty for a normal payment
            isPortabilidad = pendingIsPortabilidad,
            serialNumbers = pendingSerialNumbers,
        )

        // 🔴 NonCancellable: si el proceso muere/la pantalla se abandona a media espera
        // (recordPaymentUseCase reintenta hasta 5 veces con backoff, ~67.5s peor caso),
        // la llamada NO debe abortarse a medio camino — sin esto el cobro puede
        // saltarse la cola offline por completo.
        val recordResult = withContext(NonCancellable + Dispatchers.IO) {
            recordPaymentUseCase(
                context = paymentContext,
                cardDetails = cardDetails,
                authorizationNumber = authCode,
                referenceNumber = reference,
            )
        }

        val successState = AngelPayPaymentState.Success(
            authCode = authCode,
            amount = pendingAmount.toPlainString(),
            tipAmount = if (pendingTip > BigDecimal.ZERO) pendingTip.toPlainString() else null,
            referenceNumber = reference,
            orderId = pendingOrderId,
            orderNumber = pendingOrderNumber,
            isCash = false,
        )

        recordResult.fold(
            onSuccess = { receipt ->
                Timber.i("🔶 [AngelPay SDK] Card payment recorded | receipt=${receipt.receiptUrl}")
                // 📒 [Libreta] Money moved and the backend owns the record from here on:
                // HOST_RESPONDIO → AUTORIZADO (card facts) → REGISTRADO.
                paymentContext.idempotencyKey?.let { attemptIdForLedger ->
                    paymentAttemptLedger.markAuthorized(attemptIdForLedger, null, cardDetails.cardBrand.name, cardDetails.entryMode.name)
                    paymentAttemptLedger.markRecorded(attemptIdForLedger)
                }
                _state.value = successState.copy(receipt = receipt)
                // 📡 POS→TPV: report success to the caller (no-op unless socket-sourced).
                emitSocketResultIfSocketSourced(
                    status = "success",
                    paymentId = receipt.paymentId,
                    transactionId = receipt.paymentId,
                    receiptUrl = receipt.receiptUrl,
                    receiptAccessKey = receipt.accessKey,
                )
            },
            onFailure = { error ->
                Timber.e(error, "🔶 [AngelPay SDK] Card payment failed to record to backend — enqueueing for sync")
                // 📒 [Libreta] REGISTRO_FALLIDO — the row is the evidence until the queue
                // takes over (handleRecordFailure marks ENTREGADA_A_COLA on enqueue success).
                paymentContext.idempotencyKey?.let { paymentAttemptLedger.markRecordFailed(it, error.message) }
                _state.value = handleRecordFailure("El pago con tarjeta", paymentContext, error)
            },
        )
    }

    // ── Navigation ───────────────────────────────────────────────────

    /**
     * Navigate backwards through pre-payment states.
     * @return true if navigated back one step, false if at first step (caller should navigate away)
     */
    fun goBackOneStep(): Boolean {
        // ⬅️ El cobro se saltó calificación/propina en la ida (POS con skipReview=true, o venta
        // serializada): no hay wizard al cual retroceder, así que atrás = salir, y
        // `resetPayment()` es lo que le avisa al POS. Acotado a los pasos del wizard: en
        // cualquier otro estado se cae al `when` de abajo, cuyo `else` devuelve false SIN
        // resetear — nunca hay que tirar el estado con un cobro en vuelo.
        // Espejo de Blumon (`if (isSkipReviewFlow) return false` en su back handler).
        val enPasoDelWizard = _state.value is AngelPayPaymentState.CollectingRating ||
            _state.value is AngelPayPaymentState.CollectingTip ||
            _state.value is AngelPayPaymentState.SelectingMerchant
        if (isSkipReviewFlow && enPasoDelWizard) {
            Timber.d("⬅️ [AngelPay] Back con skipReview → salir directo (no se mostró wizard)")
            resetPayment()
            return false
        }

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

    /**
     * Imprime el ticket de PAGO RECHAZADO. Nunca lanza.
     *
     * 🔴 El invariante: esta función se llama desde la rama que YA va a terminar en Error, y
     * NO puede cambiar ese desenlace. Si la impresora está ausente, sin papel o falla, el
     * cajero tiene que seguir viendo el motivo del rechazo en pantalla — que es lo que de
     * verdad necesita para pedir otra forma de pago. Un fallo de papel no puede disfrazarse
     * de otra cosa ni tumbar el flujo. Por eso: `runCatching`, log, y seguir.
     *
     * Sólo aplica al riel Nexgo/AngelPay. En PAX el rechazo vive dentro de la máquina de
     * estados EMV y se atenderá aparte — ahí el archivo tiene la advertencia de que 8
     * funcionalidades comparten el mismo flujo.
     */
    private fun imprimirTicketDeRechazo(motivo: String?, codigoSdk: String?) {
        if (BuildConfig.ENABLE_PAX_SDK) return
        if (!canPrintReceipt) {
            Timber.i("🔶 [AngelPay] Ticket de rechazo omitido: la terminal no tiene impresora")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val venueZone = com.jaac.avoqado_tpv.core.util.VenueTimeZone.get(secureStorage)
                val ticket = angelPayTicketBuilder.buildDeclineTicket(
                    amount = pendingAmount ?: java.math.BigDecimal.ZERO,
                    reason = motivo,
                    sdkCode = codigoSdk,
                    venueName = secureStorage.getVenueName(),
                    staffName = secureStorage.getStaffName(),
                    venueTimeZone = java.util.TimeZone.getTimeZone(venueZone),
                )
                // Igual que la impresión de recibo: el SDK 1.0.16 DEVUELVE `Result` en vez
                // de lanzar, así que hay que abrirlo o un fallo pasa por éxito.
                com.angelpay.angelpaysdk.AngelPaySDK.printTicket(ticket).getOrThrow()
            }.onFailure {
                // Se registra y se sigue. El cajero ya está viendo el motivo en pantalla.
                Timber.w(it, "🔶 [AngelPay] No se pudo imprimir el ticket de rechazo")
            }.onSuccess {
                Timber.i("🖨️ [AngelPay] Ticket de rechazo impreso")
            }
        }
    }

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
                            autofacturaAvailable = receipt.autofacturaAvailable,
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
                            autofacturaAvailable = receipt.autofacturaAvailable,
                        )
                        // 🖨️ A6 (SDK 1.0.16): `printTicket` RETURNS `kotlin.Result`
                        // instead of throwing (javap: `printTicket-IoAF18A`).
                        // Discarding it meant a FAILED print fell straight through
                        // to the "printed successfully" log below. Rethrow the SDK's
                        // real error so the catch — and the log — tell the truth.
                        com.angelpay.angelpaysdk.AngelPaySDK.printTicket(ticket)
                            .getOrElse { err ->
                                // `getOrThrow()` would rethrow a raw Throwable, which
                                // the `catch (e: Exception)` below would NOT catch.
                                throw (err as? Exception)
                                    ?: IllegalStateException(
                                        "Fallo de impresión AngelPay: ${err.message}",
                                        err,
                                    )
                            }
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
        authorizationWasLaunched = true // Prevent stale card proof from crossing payment methods.
        confirmedNegativeOutcome = false
        confirmedNegativeEvidence = null
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
                // 🛑 C.5: mismo ancla que el efectivo — el QR del cliente es dinero en camino.
                if (!puedeArrancarEfectivoOCripto()) return@launch
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
        // 📡 POS→TPV: report crypto success to the caller (no-op unless socket-sourced).
        emitSocketResultIfSocketSourced(
            status = "success",
            paymentId = receipt.paymentId,
            transactionId = receipt.paymentId,
            receiptUrl = receipt.receiptUrl.ifBlank { null },
            receiptAccessKey = receipt.accessKey.ifBlank { null },
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
        // 🛑 C.5: el POS canceló este cobro y la terminal lo aceptó. Reintentar aquí cobraría algo que el
        // servidor ya dio por cancelado (la cerca de la libreta lo impediría igual; esto lo DICE).
        if (cobroCanceladoPorElPos) {
            Timber.w("🔁 [AngelPay] Reintento BLOQUEADO: el POS canceló este cobro")
            _state.value = AngelPayPaymentState.Error(CobroRemotoDelPos.CANCELADO_POR_EL_POS, canRetry = false)
            return
        }
        // 🛑 H.3: si el desenlace final de esta solicitud ya salió, la terminal no la vuelve a ejecutar.
        if (_paymentSource == CobroRemotoDelPos.FUENTE_SOCKET && _socketRequestId != null && _socketResultEmitted) {
            Timber.w("🔁 [AngelPay] Reintento BLOQUEADO: el desenlace de esta solicitud ya salió")
            _state.value = AngelPayPaymentState.Error(CobroRemotoDelPos.SOLICITUD_CERRADA, canRetry = false)
            return
        }
        // 🔴 T26 / H.3: el reloj de abandono ya le mandó al POS «failed + PRE_AUTHORIZATION» de esta
        // solicitud; cobrarla aquí movería dinero sobre una fila que el servidor cerró como «no se cobró».
        if (solicitudCerradaAntesDeAutorizar && _paymentSource == "SOCKET") {
            Timber.w("🔁 [AngelPay] Reintento BLOQUEADO: el POS ya fue avisado de que esta solicitud no se cobró")
            return
        }
        // 🔴 Mientras no se sepa si el primer cobro pasó, volver a cobrar es exactamente el
        // daño que [AngelPayPaymentState.ResultadoIncierto] existe para evitar. La pantalla
        // ya no ofrece este botón ahí; esta guarda cubre cualquier otro camino que lo llame.
        if (_state.value is AngelPayPaymentState.ResultadoIncierto ||
            (authorizationWasLaunched && !confirmedNegativeOutcome && _state.value is AngelPayPaymentState.Error)) {
            Timber.w("🔁 [AngelPay] Reintento BLOQUEADO: el desenlace del cobro anterior sigue sin verificarse")
            return
        }
        // A deliberate retry after a definitive refusal is a NEW processor attempt.
        currentPaymentAttemptId = null
        ledgerOpenedAttemptId = null
        consumedResultAttemptId = null
        // El cajero retomó: el reloj de abandono ya no debe cerrarle la fila al POS.
        cancelarCierrePorAbandono()
        // T26 / H.3: un intento nuevo — si vuelve a fallar antes de autorizar, retendrá su propio final.
        finalPreAutorizacionRetenido = null
        // …y el rechazo del intento anterior deja de estar retenido: este intento traerá su propio desenlace.
        declinacionRetenida = null
        _mensajeDelPos.value = null
        if (pendingAmount > BigDecimal.ZERO) {
            Timber.i("🔁 [AngelPay] Retry → payment-method step (merchant unchanged)")
            navigateToStep(PrePaymentNextStep.SELECT_MERCHANT, pendingAmount.toPlainString())
        } else {
            Timber.w("🔁 [AngelPay] Retry with no cached context → full reset")
            resetPayment()
        }
    }

    // ── POS→TPV socket result ─────────────────────────────────────────

    /**
     * 📡 Report the terminal outcome of a SOCKET-initiated charge to the caller (POS long-poll)
     * via [SocketManager.emitTerminalPaymentResult]. No-op for device-initiated charges (guard:
     * [_paymentSource] == "SOCKET"). cardDetails is always null: AngelPay does not return a
     * reliable maskedPan/brand. Mirrors the Blumon PaymentViewModel.
     *
     * ⚠️ De-duped via [_socketResultEmitted] — NOT by nulling [_socketRequestId]. The request id
     * must SURVIVE the emit because it is also the arbitration link threaded into the recorded
     * payment (terminalPaymentRequestId). Nulling it here silently broke retry-after-decline:
     * decline emits "failed" → cashier retries ON THE TERMINAL → card APPROVED → the recorded
     * payment carried a null link → the server could never reconcile the FAILED row to COMPLETED
     * and the 🚨 "money moved despite close" alert never fired. One emit per request, but the link
     * lives until [resetPayment].
     */
    private fun emitSocketResultIfSocketSourced(
        status: String,
        paymentId: String? = null,
        transactionId: String? = null,
        errorMessage: String? = null,
        receiptUrl: String? = null,
        receiptAccessKey: String? = null,
        outcomeEvidence: String? = null,
    ) {
        if (_paymentSource != "SOCKET") return
        val requestId = _socketRequestId ?: return
        if (_socketResultEmitted) {
            Timber.d("📡 [AngelPay Socket] Result already emitted for $requestId — skipping (status=$status)")
            return
        }
        // 🛑 El servidor sólo acredita `PROCESSOR_DECLINED` junto a `failed`: con `cancelled` lo degrada a
        // timeout, la fila queda UNKNOWN y la terminal y la tablet se quedan bloqueadas (P1, 11-sep).
        //
        // ⚠️ REDUNDANTE a propósito, y conviene saberlo antes de "limpiarla": los emisores reales de un
        // desenlace retenido ([emitirDeclinacionRetenida], el final pre-autorización) ya mandan `failed`,
        // así que hoy NINGÚN camino llega aquí con `cancelled` + `PROCESSOR_DECLINED`. Es la segunda capa
        // para el emisor que se escriba mañana. Medido el 11-sep rompiendo cada capa por separado: con una
        // sola rota las 98 pruebas siguen en VERDE (la otra tapa el hueco); rotas LAS DOS caen 6, entre
        // ellas «P1 el reloj de abandono de A nunca emite con el id de B». O sea: el invariante está
        // protegido, pero ninguna prueba distingue las capas — no la borres creyendo que alguna la cubre.
        val statusFinal = if (outcomeEvidence == "PROCESSOR_DECLINED" && status == "cancelled") "failed" else status
        socketManager.emitTerminalPaymentResult(
            requestId = requestId,
            status = statusFinal,
            paymentId = paymentId,
            transactionId = transactionId,
            cardDetails = null,
            errorMessage = errorMessage,
            receiptUrl = receiptUrl,
            receiptAccessKey = receiptAccessKey,
            outcomeEvidence = outcomeEvidence,
        )
        _socketResultEmitted = true
        Timber.i("📡 [AngelPay Socket] Emitted terminal:payment_result | status=$statusFinal | requestId=$requestId")
    }

    /** Test seam: drive the emit directly (the real call sites need a full SDK round-trip). */
    @androidx.annotation.VisibleForTesting
    internal fun emitSocketResultForTest(
        status: String,
        paymentId: String? = null,
        errorMessage: String? = null,
    ) = emitSocketResultIfSocketSourced(status = status, paymentId = paymentId, errorMessage = errorMessage)

    /** Test seam: the arbitration link must survive an emit — see the regression tests. */
    @androidx.annotation.VisibleForTesting
    internal fun socketRequestIdForTest(): String? = _socketRequestId

    /**
     * 📡 Red de seguridad: la pantalla murió sin que nadie reportara el desenlace al POS.
     *
     * La llaman [resetPayment] (flecha atrás, `goBackOneStep` en el primer paso) y [onCleared]
     * (botón atrás del sistema, o cualquier navegación que haga pop del destino — ninguno de esos
     * dos pasa por código de la pantalla). Sin esto, el POS que pidió el cobro se queda en
     * "Esperando respuesta de la terminal" hasta agotar su propio timeout: es el caso reportado en
     * hardware el 2026-08-10 (Sunmi D3 → Nexgo N86).
     *
     * Gateada por [sinDineroEnVuelo]: si hay una autorización en curso NO se avisa nada y se deja
     * que el watchdog del server resuelva la fila. Mentirle al POS ahí provoca doble cobro.
     *
     * 🔴 Sólo habla por la solicitud que ESTE VM ejecutó: `_socketRequestId` lo fija una única vez
     * [setSocketPaymentSource] y ningún id posterior lo sustituye (D.7: la pantalla que sale ya no
     * puede prestarle a este cierre el id del cobro siguiente).
     */
    @androidx.annotation.VisibleForTesting
    internal fun emitCancelledIfAbandoned() {
        // 🔍 Cada salida se LOGUEA. Tres `return` mudos dejaron un incidente sin diagnosticar
        // (N86 2026-08-10: el pop ocurrió, la red no avisó, y no había forma de saber cuál guard
        // la cortó). Un guard que decide si el POS se entera o se cuelga no puede ser invisible.
        val source = _paymentSource
        val requestId = _socketRequestId
        if (source != "SOCKET") {
            Timber.d("📡 [AngelPay Socket] Abandono sin avisar: cobro no viene del POS (source=$source)")
            return
        }
        if (requestId == null) {
            Timber.w("📡 [AngelPay Socket] Abandono sin avisar: source=SOCKET pero SIN requestId — el POS se va a colgar")
            return
        }
        if (_socketResultEmitted) {
            Timber.d("📡 [AngelPay Socket] Abandono sin avisar: ya se reportó el desenlace de $requestId")
            return
        }

        val state = _state.value
        if ((authorizationWasLaunched && !confirmedNegativeOutcome) || !sinDineroEnVuelo(state)) {
            Timber.w(
                "📡 [AngelPay Socket] Pantalla abandonada en %s — NO se avisa cancelación " +
                    "(puede haber dinero en vuelo); la fila la resuelve el watchdog del server",
                state::class.simpleName,
            )
            return
        }

        // 🛑 H.3: un rechazo RETENIDO (el SDK ya se lanzó y la pantalla ofrecía reintentar) sale aquí, al
        // salir, con la evidencia de SU intento — y es el único desenlace de esta solicitud.
        if (emitirDeclinacionRetenida()) return

        // T26 / H.3: una falla antes de autorizar retuvo su final mientras se podía reintentar;
        // al salir, ése es el ÚNICO desenlace que sale (failed + PRE_AUTHORIZATION).
        val retenido = finalPreAutorizacionRetenido
        if (retenido != null && !authorizationWasLaunched) {
            emitSocketResultIfSocketSourced(
                status = "failed",
                errorMessage = retenido,
                outcomeEvidence = "PRE_AUTHORIZATION",
            )
            finalPreAutorizacionRetenido = null
            return
        }

        emitSocketResultIfSocketSourced(
            status = "cancelled",
            outcomeEvidence = confirmedNegativeEvidence ?: "PRE_AUTHORIZATION".takeUnless { authorizationWasLaunched },
            errorMessage = "Pago cancelado en la terminal",
        )
    }

    override fun onCleared() {
        // Antes de super: SocketManager persiste y emite en su scope de aplicación, no en
        // viewModelScope; por eso sobrevive a la cancelación de este ViewModel.
        Timber.d("♻️ [AngelPay] onCleared — la pantalla murió, evaluando si hay que avisarle al POS")
        cancelarCierrePorAbandono()
        emitCancelledIfAbandoned()
        super.onCleared()
    }

    // ── Reset ────────────────────────────────────────────────────────

    fun resetPayment() {
        if (_state.value is AngelPayPaymentState.ResultadoIncierto ||
            (authorizationWasLaunched && !confirmedNegativeOutcome && _state.value is AngelPayPaymentState.Error)) return
        pendingProcessorAffiliation = null
        // El reloj de abandono muere con el cobro: `emitCancelledIfAbandoned` de abajo ya
        // resuelve la fila, y dejarlo vivo emitiría un segundo desenlace sobre otro cobro.
        cancelarCierrePorAbandono()
        // 📡 POS→TPV: si salimos sin haber reportado desenlace, el POS se queda colgado esperando.
        // Espejo del riel Blumon (PaymentViewModel.resetPayment). Va ANTES de la limpieza de abajo
        // a propósito: al dejar _paymentSource en null, esa limpieza es lo que deduplica contra la
        // red de onCleared() — sin bandera extra.
        emitCancelledIfAbandoned()
        authorizationWasLaunched = false
        confirmedNegativeOutcome = false
        confirmedNegativeEvidence = null
        pendingAmount = BigDecimal.ZERO
        pendingTip = BigDecimal.ZERO
        pendingRating = null
        pendingOrderId = null
        pendingOrderNumber = null
        pendingSerialNumbers = emptyList()
        pendingIsPortabilidad = false
        // ⬅️ Sin esto, un cobro empujado por el POS dejaría el flag encendido y el SIGUIENTE
        // cobro iniciado en la terminal saldría al primer back sin mostrar su wizard.
        isSkipReviewFlow = false
        cleanupOrphanedProofOfSalePhotos()
        _isUploadingProofOfSale.value = false
        _proofOfSaleComplete.value = false
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
        // 🧭 Clear routing eligibility so the next charge re-evaluates (no stale filter/banner)
        _merchantRouting.value = null
        // 📡 Clear socket arbitration source so a stale request id never leaks into the next charge.
        _paymentSource = null
        _socketRequestId = null
        _socketResultEmitted = false
        solicitudCerradaAntesDeAutorizar = false
        finalPreAutorizacionRetenido = null
        declinacionRetenida = null
        _mensajeDelPos.value = null
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

    companion object {
        /**
         * Cuánto se espera a que el cajero retome un aviso EMV recuperable antes de cerrarle
         * la fila al POS.
         *
         * 🔴 Existe porque "no emitir nada" sólo era correcto mientras el cajero SIGUIERA ahí.
         * Un `E618` («Retire la tarjeta») no reporta desenlace a propósito, para que el
         * reintento en la misma sesión resuelva el long-poll abierto. Pero si nadie vuelve y
         * nadie navega —la terminal se queda con la pantalla puesta— no se emite jamás: la
         * fila vence sola y el vigilante del servidor la parquea en `UNKNOWN`, que **retiene
         * el slot de la terminal** hasta que un humano lo desatasque (incidente Testarudo:
         * una PAX bloqueada 3 horas).
         *
         * 2 min: mucho más que un reintento real (segundos) y menos que los 5 min que tarda
         * en vencer la fila del servidor, para llegar antes que el vigilante.
         */
        const val MS_ABANDONO_AVISO_EMV = 120_000L

        // 📡 SavedStateHandle keys for the POS→TPV arbitration link. These MUST survive
        // MainActivity death while the AngelPay SDK Activity is in front — see [_paymentSource].
        private const val KEY_PAYMENT_SOURCE = "angelpay_socket_payment_source"
        private const val KEY_SOCKET_REQUEST_ID = "angelpay_socket_request_id"
        private const val KEY_SOCKET_EMITTED = "angelpay_socket_result_emitted"
        private const val KEY_VINCULO_FIJADO = "angelpay_socket_binding_fixed"
        private const val KEY_SOLICITUD_VINCULADA = "angelpay_socket_bound_request_id"

        /**
         * Nexgo terminal models that ship with a built-in thermal
         * printer. Matched against the uppercased `model` returned by
         * `AngelPaySDK.getDeviceInfo()` using substring containment so
         * variant suffixes (e.g. "N86-XYZ") still match.
         *
         * Add new entries here when deploying additional Nexgo SKUs.
         * Leave OUT models that ship without a printer (e.g. N62).
         */
        private val NEXGO_MODELS_WITH_PRINTER = listOf("N86")
    }
}

/**
 * Compara lo que AngelPay REALMENTE cobró contra lo que Avoqado registró, y devuelve el
 * mensaje de alarma — o `null` si cuadran.
 *
 * Puro y de nivel superior a propósito: la comparación es lógica de dinero y tiene que poder
 * probarse sin levantar el ViewModel entero. Ver `AngelPayPaymentViewModel.verificarMontoCobrado`.
 *
 * `cobradoCents` viene de `PaymentResult.amount`, que el manual del SDK de AngelPay (v1.13,
 * sección PaymentResult) define como **"Monto en centavos"** — la misma unidad que
 * `PaymentRequest.amountCents`. Si algún día cambiaran esa unidad, la alarma se dispararía en
 * TODOS los cobros, que es exactamente la señal que querríamos recibir.
 */
@VisibleForTesting
internal fun describirDivergenciaDeCobro(
    cobradoCents: Long,
    venta: BigDecimal,
    propina: BigDecimal,
): String? {
    val totalRegistrado = venta.add(propina)
    val esperadoCents = totalRegistrado
        .setScale(2, RoundingMode.HALF_UP)
        .movePointRight(2)
        .toLong()

    // `<= 0` = el SDK no reportó monto en esta respuesta; no hay nada que comparar.
    if (cobradoCents <= 0L || cobradoCents == esperadoCents) return null

    val cobrado = BigDecimal.valueOf(cobradoCents).movePointLeft(2)
    val diferencia = cobrado.subtract(totalRegistrado)
    val sentido = if (diferencia.signum() < 0) "DE MENOS" else "DE MÁS"

    return "🚨 [AngelPay] COBRO NO COINCIDE — el cliente pagó $sentido | " +
        "cobrado=$cobrado, registrado=$totalRegistrado " +
        "(venta=$venta + propina=$propina), diferencia=$diferencia"
}

/**
 * ¿Este estado garantiza que NO hay una autorización en vuelo ni dinero ya movido?
 *
 * Sólo desde estos estados es seguro avisarle al POS "cancelado" cuando el operador abandona la
 * pantalla. Si hay un cobro en curso —o ya terminó y capturó— mentirle al POS hace que el operador
 * recobre: doble cobro. Es el mismo incidente de device-QA 2026-07-14 que ya obligó a dejar de
 * emitir "failed" en los avisos EMV recuperables.
 *
 * 🔴 El `when` es EXHAUSTIVO A PROPÓSITO: **no tiene `else`, y no debe tenerlo**. Un estado nuevo
 * rompe la compilación y obliga a clasificarlo aquí, que es justo lo que queremos. Con `else` el
 * default sería "avisar", y un estado nuevo con dinero en vuelo se colaría en silencio hasta que
 * alguien cobrara dos veces. La dirección de esta lista es la decisión de diseño: un estado sin
 * clasificar debe caer en silencio (el POS agota su timeout — molesto y barato), nunca en mentira.
 *
 * Ver `docs/superpowers/specs/2026-08-10-angelpay-socket-cancel-result-design.md` §4.3.
 */
@VisibleForTesting
internal fun sinDineroEnVuelo(state: AngelPayPaymentState): Boolean = when (state) {
    // Pre-dinero: nada se ha lanzado al procesador todavía.
    // `Switching` espera a que asiente el cambio de merchant (con su propio timeout de 8s a Error);
    // `GeneratingCryptoQR` ni siquiera ha mostrado el QR al cliente.
    is AngelPayPaymentState.Idle,
    is AngelPayPaymentState.Cancelled,
    is AngelPayPaymentState.CollectingRating,
    is AngelPayPaymentState.CollectingTip,
    is AngelPayPaymentState.SelectingMerchant,
    is AngelPayPaymentState.Switching,
    // T26: autenticando ANTES de la libreta y del SDK — nada capaz de autorizar empezó.
    is AngelPayPaymentState.ConectandoAngelPay,
    is AngelPayPaymentState.GeneratingCryptoQR,
    is AngelPayPaymentState.Error -> true

    // Dinero en vuelo, o ya movido: JAMÁS avisar cancelación.
    // `AwaitingCryptoPayment` está aquí porque el cliente puede estar transfiriendo en este
    // instante; esa ruta ya tiene su propio `cancelCryptoPayment()` que notifica al backend.
    is AngelPayPaymentState.LaunchingAngelPaySdk,
    is AngelPayPaymentState.LaunchingAngelPay,
    is AngelPayPaymentState.WaitingForResult,
    is AngelPayPaymentState.Charging,
    is AngelPayPaymentState.RecordingPayment,
    is AngelPayPaymentState.ProcessingCash,
    is AngelPayPaymentState.AwaitingCryptoPayment,
    is AngelPayPaymentState.Success,
    is AngelPayPaymentState.Queued,
    // 🔴 `ResultadoIncierto` es literalmente "no se sabe si el dinero se movió": avisar
    // "cancelado" desde aquí sería afirmar que no se cobró. Se deja que lo resuelva la
    // verificación (que emite su propio desenlace) o, en último término, el vigilante del
    // servidor — que para eso tiene el estado UNKNOWN, y que RETIENE el slot a propósito.
    is AngelPayPaymentState.ResultadoIncierto -> false
}

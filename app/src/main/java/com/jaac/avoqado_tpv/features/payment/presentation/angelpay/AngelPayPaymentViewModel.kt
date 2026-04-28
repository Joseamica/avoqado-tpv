package com.jaac.avoqado_tpv.features.payment.presentation.angelpay

import android.content.Intent
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayCredentials
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayIntentBuilder
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayResult
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayResultParser
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPaySdkGateway
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val intentBuilder: AngelPayIntentBuilder,
    private val sdkGateway: AngelPaySdkGateway,
    private val tpvSettingsRepository: TpvSettingsRepository,
    private val printerManager: PrinterManager,
    private val paymentApiService: PaymentApiService,
    private val apiService: ApiService,
    private val socketManager: SocketManager,
) : ViewModel() {

    private val _state = MutableStateFlow<AngelPayPaymentState>(AngelPayPaymentState.Idle)
    val state: StateFlow<AngelPayPaymentState> = _state.asStateFlow()

    private val resultParser = AngelPayResultParser()

    // Merchant list for MerchantSelectionContent
    private val _merchants = MutableStateFlow<List<MerchantAccount>>(emptyList())
    val merchants: StateFlow<List<MerchantAccount>> = _merchants.asStateFlow()

    private val _currentMerchant = MutableStateFlow<MerchantAccount?>(null)
    val currentMerchant: StateFlow<MerchantAccount?> = _currentMerchant.asStateFlow()

    // TpvSettings for tip suggestions / default tip percentage
    val tipSuggestions: List<Int> get() = tpvSettingsRepository.getCurrentSettings().tipSuggestions
    val defaultTipPercentage: Int? get() = tpvSettingsRepository.getCurrentSettings().defaultTipPercentage
    val canPrintReceipt: Boolean
        get() = runCatching {
            BuildConfig.ENABLE_PAX_SDK && printerManager.isPrinterAvailable()
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
                Timber.d("🔶 [AngelPay] Merchants loaded: ${angelPayMerchants.size} AngelPay")
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

            val shiftResult = withContext(Dispatchers.IO) {
                shiftRepository.getCurrentShift(venueId)
            }
            val shift = shiftResult.getOrNull()

            if (shift == null) {
                _state.value = AngelPayPaymentState.Error(
                    message = "Debes abrir un turno antes de cobrar",
                    canRetry = false,
                    showOpenShiftButton = true,
                )
                return@launch
            }

            // 2. Validate AngelPay credentials
            val credentials = secureStorage.getAngelPayCredentials()
            if (credentials == null) {
                _state.value = AngelPayPaymentState.Error(
                    message = "No hay credenciales de AngelPay configuradas",
                    canRetry = false,
                )
                return@launch
            }

            // 3. Cache context
            pendingAmount = amountDecimal
            pendingTip = BigDecimal.ZERO
            pendingRating = null
            pendingOrderId = orderId
            pendingOrderNumber = orderNumber
            cachedShiftId = shift.id
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

    fun selectMerchant(merchant: MerchantAccount) {
        _currentMerchant.value = merchant
        Timber.d("🔶 [AngelPay] Merchant selected: ${merchant.displayName}")
    }

    // ── Card Payment (AngelPay SDK + App-to-App Fallback) ───────────

    fun startCardPayment() {
        viewModelScope.launch {
            val credentials = secureStorage.getAngelPayCredentials()
            if (credentials == null) {
                _state.value = AngelPayPaymentState.Error("No hay credenciales de AngelPay")
                return@launch
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

            if (isSdkFlowEnabled()) {
                startSdkCardPayment(credentials)
            } else {
                startAppToAppCardPayment(credentials)
            }
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
            return
        }

        val staffName = secureStorage.getStaffName()
        val paymentAttemptId = ensurePaymentAttemptId()

        val authResult = sdkGateway.ensureAuthenticated(credentials)
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
        val canUseQaTipFallback = BuildConfig.BLUMON_ENV != "PROD" &&
                pendingTip > BigDecimal.ZERO &&
                validationError != null &&
                sdkGateway.isTipUnsupportedError(validationError)

        if (canUseQaTipFallback) {
            Timber.w(
                "⚠️ [AngelPay SDK] Tip not supported in QA, applying fallback (subtotal=total, tip=0) | error=${validationError?.message}"
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
    }

    private fun launchSdkRequest(
        request: PaymentRequest,
        usedQaTipFallback: Boolean,
    ) {
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
        }
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
            withContext(Dispatchers.IO) {
                try {
                    val venueName = secureStorage.getVenueName()
                    val staffName = secureStorage.getStaffName()
                    printerManager.printReceipt(
                        receiptUrl = receipt.receiptUrl,
                        amount = receipt.baseAmount.toPlainString(),
                        authCode = (_state.value as? AngelPayPaymentState.Success)?.authCode ?: "",
                        tipAmount = receipt.tipAmount.toPlainString(),
                        referenceNumber = (_state.value as? AngelPayPaymentState.Success)?.referenceNumber,
                        venueName = venueName,
                        staffName = staffName,
                        orderNumber = pendingOrderNumber,
                    )
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

                // Validate shift is open (mirrors initPayment validation)
                val shift = withContext(Dispatchers.IO) { shiftRepository.getCurrentShift(venueId) }.getOrNull()
                if (shift == null) {
                    _state.value = AngelPayPaymentState.Error(
                        message = "Debes abrir un turno antes de cobrar",
                        canRetry = false,
                        showOpenShiftButton = true,
                    )
                    return@launch
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
}

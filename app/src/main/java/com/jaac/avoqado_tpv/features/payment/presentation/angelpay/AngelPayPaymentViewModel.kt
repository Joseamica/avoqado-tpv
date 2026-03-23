package com.jaac.avoqado_tpv.features.payment.presentation.angelpay

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.domain.TerminalConfig
import com.jaac.avoqado_tpv.core.printer.PrinterManager
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.payment.data.api.PaymentApiService
import com.jaac.avoqado_tpv.features.payment.data.dto.SendReceiptRequest
import com.jaac.avoqado_tpv.features.payment.data.dto.SendWhatsAppReceiptRequest
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayIntentBuilder
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayResult
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayResultParser
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    private val recordPaymentUseCase: RecordPaymentUseCase,
    private val shiftRepository: ShiftRepository,
    private val authRepository: AuthRepository,
    private val merchantRepository: MerchantRepository,
    private val secureStorage: SecureStorage,
    private val intentBuilder: AngelPayIntentBuilder,
    private val tpvSettingsRepository: TpvSettingsRepository,
    private val printerManager: PrinterManager,
    private val paymentApiService: PaymentApiService,
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

    // Cached payment context
    private var pendingAmount: BigDecimal = BigDecimal.ZERO
    private var pendingTip: BigDecimal = BigDecimal.ZERO
    private var pendingRating: Int? = null
    private var pendingOrderId: String? = null
    private var pendingOrderNumber: String? = null
    private var cachedShiftId: String? = null
    private var cachedVenueId: String? = null
    private var cachedStaffId: String? = null

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

    // ── Card Payment (AngelPay Intent) ───────────────────────────────

    fun startCardPayment() {
        viewModelScope.launch {
            val credentials = secureStorage.getAngelPayCredentials()
            if (credentials == null) {
                _state.value = AngelPayPaymentState.Error("No hay credenciales de AngelPay")
                return@launch
            }

            val staffName = secureStorage.getStaffName()
            val intent = intentBuilder.buildSaleIntent(
                amount = pendingAmount,
                tip = pendingTip,
                credentials = credentials,
                waiter = staffName,
            )

            _state.value = AngelPayPaymentState.LaunchingAngelPay(
                intent = intent,
                amount = pendingAmount.toPlainString(),
                tip = pendingTip.toPlainString(),
                orderId = pendingOrderId,
                orderNumber = pendingOrderNumber,
            )
            val totalAmount = pendingAmount.add(pendingTip)
            Timber.i("🔶 [AngelPay] Intent built for card payment | subtotal=$pendingAmount, tip=$pendingTip, total=$totalAmount")
        }
    }

    // ── Cash Payment ─────────────────────────────────────────────────

    fun startCashPayment() {
        viewModelScope.launch {
            _state.value = AngelPayPaymentState.ProcessingCash()

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
                    // Cash was collected — show success but note backend failure
                    _state.value = successState
                    Timber.w("🔶 [AngelPay] Cash payment NOT recorded to backend")
                },
            )
        }
    }

    // ── AngelPay Intent Result ───────────────────────────────────────

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
            Timber.i("🔶 [AngelPay] onAngelPayResult | resultCode=$resultCode")

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
                // AngelPay already processed — show success
                _state.value = successState
                Timber.w("🔶 [AngelPay] Card payment NOT recorded to backend")
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
        _currentMerchant.value = null
        _isSendingReceipt.value = false
        _sendReceiptMessage.value = null
        _state.value = AngelPayPaymentState.Idle
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

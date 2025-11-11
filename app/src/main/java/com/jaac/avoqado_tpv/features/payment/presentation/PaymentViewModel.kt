package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blumonpay.pax.shared.neptune_polling.domain.use_case.start_detect_card.StartDetectCardParams
import com.blumonpay.pax.shared.neptune_polling.domain.use_case.start_detect_card.StartDetectCardUseCase
import com.blumonpay.pax.shared.neptune_polling.domain.use_case.stop_detect_card.StopDetectCardParams
import com.blumonpay.pax.shared.neptune_polling.domain.use_case.stop_detect_card.StopDetectCardUseCase
import com.blumonpay.pax.shared.trans_process.domain.TransProcessRepository
import com.blumonpay.pax.shared.trans_process.domain.entity.TransType
import com.blumonpay.pax.shared.trans_process.domain.use_case.complete_emv_trans.CompleteEmvTransParams
import com.blumonpay.pax.shared.trans_process.domain.use_case.complete_emv_trans.CompleteEmvTransUseCase
import com.blumonpay.pax.shared.trans_process.domain.use_case.continue_confirm_card.ContinueConfirmCardParams
import com.blumonpay.pax.shared.trans_process.domain.use_case.continue_confirm_card.ContinueConfirmCardUseCase
import com.blumonpay.pax.shared.trans_process.domain.use_case.get_emv_tag_list.GetEmvTagListParam
import com.blumonpay.pax.shared.trans_process.domain.use_case.get_emv_tag_list.GetEmvTagListUseCase
import com.blumonpay.pax.shared.trans_process.domain.use_case.get_emv_tag_tlv.Format
import com.blumonpay.pax.shared.trans_process.domain.use_case.get_emv_tag_tlv.GetEmvTagTlvUseCase
import com.blumonpay.pax.shared.trans_process.domain.use_case.get_emv_tags.GetEmvTagsParams
import com.blumonpay.pax.shared.trans_process.domain.use_case.get_emv_tags.GetEmvTagUseCase
import com.blumonpay.pax.shared.trans_process.domain.use_case.get_tag_value.CardTech
import com.blumonpay.pax.shared.trans_process.domain.use_case.get_tag_value.GetTagValueParams
import com.blumonpay.pax.shared.trans_process.domain.use_case.get_tag_value.GetTagValueUseCase
import com.blumonpay.pax.shared.trans_process.domain.use_case.pre_trans.PreTransParams
import com.blumonpay.pax.shared.trans_process.domain.use_case.pre_trans.PreTransUseCase
import com.blumonpay.pax.shared.trans_process.domain.use_case.strat_emv_trans.StartEmvTransParams
import com.blumonpay.pax.shared.trans_process.domain.use_case.strat_emv_trans.StartEmvTransUseCase
// Contactless (NFC) payment processing
import com.blumonpay.pax.shared.trans_process.domain.use_case.start_ctlss_trans.StartCtlssTransUseCase
import com.blumonpay.pax.shared.trans_process.domain.use_case.start_ctlss_trans.StartCtlssTransParams
import com.blumonpay.pax.shared_tools.manager.CountryConstants
import com.example.clean_lib_services.shared.core.domain.entity.sale_data.AuthenticationCard
import com.example.clean_lib_services.shared.core.domain.entity.sale_data.CipherType
import com.example.clean_lib_services.shared.core.domain.use_case.sale_package.sale_icc.SaleIccParams
import com.example.clean_lib_services.shared.core.domain.use_case.sale_package.sale_icc.SaleIccResponse
import com.example.clean_lib_services.shared.core.domain.use_case.sale_package.sale_icc.SaleIccUseCase
import com.example.clean_lib_services.shared.initializer.domain.use_case.initializer.InitializerParams
import com.example.clean_lib_services.shared.initializer.domain.use_case.initializer.InitializerUseCase
import com.example.clean_lib_services.shared.initializer.domain.use_case.get_init_data.GetInitDataParams
import com.example.clean_lib_services.shared.initializer.domain.use_case.get_init_data.GetInitDataUseCase
// ⭐ InsertInitUseCase - Manual initialization to fix SDK posId bug (stores serial instead of server posId)
import com.example.clean_lib_services.shared.initializer.domain.use_case.insert_init.InsertInitUseCase
import com.example.clean_lib_services.shared.initializer.domain.use_case.insert_init.InsertInitParams
import com.example.clean_lib_services.shared.core.domain.entity.init.InitData
import com.example.clean_lib_services.shared.core.domain.entity.init.Contact
import com.example.clean_lib_services.shared.core.domain.entity.init.KushkiData
import com.jaac.avoqado_tpv.features.payment.domain.PaymentState
import com.jaac.avoqado_tpv.features.payment.domain.RetryContext
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount
// ⭐ NEW: Backend payment recording
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordPaymentUseCase
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.CardDetails
import com.jaac.avoqado_tpv.features.payment.domain.model.CardBrand
import com.jaac.avoqado_tpv.features.payment.domain.model.CardEntryMode
import com.pax.dal.entity.EReaderType
import com.paxsz.module.emv.process.enums.TransResultEnum
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * PaymentViewModel - Handles EMV chip card payments with ONLINE authorization via Blumon Momentum
 *
 * Flow: PreTrans → DetectCard → StartEmvTrans → ⭐ SaleIcc (ONLINE) ⭐ → CompleteEmvTrans
 *
 * Based on successful implementation from BLUMON_INTEGRATION_COMPLETE_SUMMARY.md
 *
 * ⚠️ CRITICAL: PIN Dialog Listeners
 * The Blumon SDK controls the PAX A910S physical keyboard automatically.
 * We MUST collect the repository's StateFlows to receive PIN events.
 * The PAX hardware handles all PIN UI - we just observe the flows.
 */
@HiltViewModel
class PaymentViewModel @Inject constructor(
    private val preTransUseCase: PreTransUseCase,
    private val startDetectCardUseCase: StartDetectCardUseCase,
    private val stopDetectCardUseCase: StopDetectCardUseCase,
    private val startEmvTransUseCase: StartEmvTransUseCase,
    // ⭐ StartCtlssTransUseCase - For contactless (NFC tap-and-go) payments
    private val startCtlssTransUseCase: StartCtlssTransUseCase,
    private val getEmvTagUseCase: GetEmvTagUseCase,
    private val completeEmvTransUseCase: CompleteEmvTransUseCase,
    // ⭐ ContinueConfirmCardUseCase - CRITICAL: Required to respond to SDK card reading confirmation
    private val continueConfirmCardUseCase: ContinueConfirmCardUseCase,
    // ⭐ SaleIccUseCase provided automatically by lib-services Hilt modules
    private val saleIccUseCase: SaleIccUseCase,
    // 🔐 TransProcessRepository for PIN StateFlows (auto-injected by SDK's Hilt module)
    private val transProcessRepository: TransProcessRepository,
    // 🔧 InitializerUseCase from SDK - Complete initialization with DUKPT download
    private val initializerUseCase: InitializerUseCase,
    // 📊 GetInitDataUseCase - Retrieves validated posId from SDK database (prevents NumberFormatException)
    private val getInitDataUseCase: GetInitDataUseCase,
    // 🛠️ InsertInitUseCase - Manual fix for SDK bug where InitializerUseCase stores serial as posId
    private val insertInitUseCase: InsertInitUseCase,
    // 🔧 InitializationManager - Ensures Blumon SDK init runs once every 24h (per Edgardo 2025-11-05)
    private val initializationManager: com.jaac.avoqado_tpv.features.payment.data.InitializationManager,
    // 🏪 Multi-Merchant Support - Allow single terminal to process payments for multiple merchant accounts
    private val getMerchantsUseCase: com.jaac.avoqado_tpv.features.payment.domain.use_case.GetMerchantsUseCase,
    private val multiMerchantSDKManager: com.jaac.avoqado_tpv.features.payment.data.MultiMerchantSDKManager,
    // 💾 Backend Payment Recording - Record payments to avoqado-server database
    private val recordPaymentUseCase: RecordPaymentUseCase,
    // 🔐 Auth Repository - Get current venue and staff context
    private val authRepository: com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository,
    // 💾 Payment Queue Repository - Offline payment queue for failed backend recordings
    private val paymentQueueRepository: com.jaac.avoqado_tpv.features.payment.domain.repository.PaymentQueueRepository,
    // 🖨️ Printer Manager - PAX thermal printer for receipt printing
    private val printerManager: com.jaac.avoqado_tpv.core.printer.PrinterManager
) : ViewModel() {

    private val _state = MutableStateFlow<PaymentState>(PaymentState.Idle)
    val state: StateFlow<PaymentState> = _state.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════════════
    // MULTI-MERCHANT STATE
    // ═══════════════════════════════════════════════════════════════════════════

    // Available merchant accounts (reactive - updates from repository)
    private val _merchants = MutableStateFlow<List<com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount>>(emptyList())
    val merchants: StateFlow<List<com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount>> = _merchants.asStateFlow()

    // Currently active merchant account
    private val _currentMerchant = MutableStateFlow<com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount?>(null)
    val currentMerchant: StateFlow<com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount?> = _currentMerchant.asStateFlow()

    // Loading state during merchant switch (3-5 seconds)
    private val _merchantSwitchingLoading = MutableStateFlow(false)
    val merchantSwitchingLoading: StateFlow<Boolean> = _merchantSwitchingLoading.asStateFlow()

    // Success/error message after merchant switch
    private val _merchantSwitchMessage = MutableStateFlow<String?>(null)
    val merchantSwitchMessage: StateFlow<String?> = _merchantSwitchMessage.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════════════

    private var currentAmount: String = ""  // Amount in decimal format (e.g., "30.00") for UI display
    private var currentAmountInCents: String = ""  // Amount in cents (e.g., "3000") for SDK calls
    private var currentTip: String = "0.00"  // Tip amount in decimal format (e.g., "5.00")
    private var currentTrack2: String = ""  // Track2 data extracted from chip/contactless
    private var currentRating: Int? = null  // Optional rating from user (1-5 stars)

    // ⭐ NEW: Payment context data for backend recording
    private var currentVenueId: String = ""  // Venue ID from auth context
    private var currentStaffId: String = ""  // Staff ID from auth context

    // ═══════════════════════════════════════════════════════════════════════════
    // EMV TAG EXTRACTION USE CASES
    // ═══════════════════════════════════════════════════════════════════════════
    // These use cases are required to extract ALL 23 EMV tags that Blumon requires
    // according to pure.json: addTagObjList specification
    //
    // Hierarchy: GetTagValueUseCase → GetEmvTagTlvUseCase → GetEmvTagListUseCase

    private val getTagValueUseCase: GetTagValueUseCase by lazy {
        GetTagValueUseCase(transProcessRepository)
    }

    private val getEmvTagTlvUseCase: GetEmvTagTlvUseCase by lazy {
        GetEmvTagTlvUseCase(getTagValueUseCase)
    }

    private val getEmvTagListUseCase: GetEmvTagListUseCase by lazy {
        GetEmvTagListUseCase(getEmvTagTlvUseCase)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CARD TYPE DETECTION (CHIP vs CONTACTLESS)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Card type detected by StartDetectCardUseCase
     */
    private enum class CardType {
        MAG,        // Magnetic stripe card (swipe)
        ICC,        // Chip card (insert)
        PICC,       // Contactless card (tap) - RFID/NFC
        UNKNOWN     // Detection failed or unsupported type
    }

    /**
     * Map EReaderType (from SDK) to CardType
     *
     * EReaderType values from PAX SDK:
     * - EReaderType.MAG = Magnetic stripe
     * - EReaderType.ICC = Chip card
     * - EReaderType.PICC = Contactless (NFC)
     * - EReaderType.MAG_ICC_PICC = Auto-detect all types
     */
    private fun mapReaderTypeToCardType(readerType: EReaderType?): CardType {
        return when (readerType) {
            EReaderType.MAG -> CardType.MAG
            EReaderType.ICC -> CardType.ICC
            EReaderType.PICC -> CardType.PICC
            else -> CardType.UNKNOWN
        }
    }

    init {
        Timber.d("🎬 [PaymentViewModel] Initialized - Starting PIN listeners")
        Timber.d("🔍 [DIAGNOSTIC] TransProcessRepository instance: ${System.identityHashCode(transProcessRepository)}")
        Timber.d("🔍 [DIAGNOSTIC] StartEmvTransUseCase instance: ${System.identityHashCode(startEmvTransUseCase)}")
        Timber.d("🔍 [DIAGNOSTIC] PreTransUseCase instance: ${System.identityHashCode(preTransUseCase)}")

        // Try to get repository from UseCase using reflection to verify instance
        try {
            val field = startEmvTransUseCase.javaClass.getDeclaredField("repository")
            field.isAccessible = true
            val useCaseRepo = field.get(startEmvTransUseCase)
            Timber.d("🔍 [DIAGNOSTIC] Repository inside StartEmvTransUseCase: ${System.identityHashCode(useCaseRepo)}")
            Timber.d("🔍 [DIAGNOSTIC] Same instance? ${useCaseRepo === transProcessRepository}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to access UseCase repository via reflection")
        }

        // 🔧 Initialize Blumon SDK (once every 24 hours per Edgardo's recommendation)
        // This replaces the duplicate init logic that was being called on EVERY payment
        viewModelScope.launch {
            initializationManager.ensureInitialized().onFailure { error ->
                Timber.e(error, "❌ Failed to initialize Blumon SDK")
            }
        }

        // 🏪 Load available merchant accounts
        viewModelScope.launch {
            getMerchantsUseCase().collect { merchantList ->
                _merchants.value = merchantList
                Timber.d("🏪 [Merchants] Loaded ${merchantList.size} accounts: ${merchantList.map { it.displayName }}")
            }
        }

        // 🏪 Track current merchant from SDK manager
        viewModelScope.launch {
            _currentMerchant.value = multiMerchantSDKManager.getCurrentMerchant()
            Timber.d("🏪 [Merchants] Current account: ${_currentMerchant.value?.displayName ?: "Default (${com.jaac.avoqado_tpv.core.domain.TerminalConfig.serialNumber})"}")
        }

        collectPinDialogFlows()
    }

    /**
     * ⚠️ CRITICAL: Collect PIN Dialog StateFlows
     *
     * The Blumon SDK exposes reactive StateFlows that control the PAX A910S physical keyboard.
     * We MUST collect these flows for the SDK to activate the hardware PIN pad.
     *
     * How it works:
     * 1. StartEmvTransUseCase detects card requires PIN
     * 2. SDK updates getEventPinDialogStateFlow()
     * 3. PAX A910S physical keyboard activates AUTOMATICALLY
     * 4. User enters PIN on PAX hardware (not app UI)
     * 5. SDK validates PIN with chip
     * 6. getPinResultFlow() emits result (0 = success)
     *
     * Pattern: Square Terminal, Toast POS - Hardware manages PIN, app observes events
     */
    private fun collectPinDialogFlows() {
        // 1️⃣ PIN Dialog State - When SDK needs to show PIN pad
        viewModelScope.launch {
            val flow = transProcessRepository.getEventPinDialogStateFlow()
            Timber.d("🔍 [DIAGNOSTIC] EventPinDialogStateFlow instance: ${System.identityHashCode(flow)}")
            flow.collect { state ->
                Timber.d("📟 [PIN Dialog] State changed: $state")
                // PAX A910S physical keyboard activates automatically
                // No UI action needed - hardware handles it
            }
        }

        // 2️⃣ Keyboard PIN State - Physical keyboard status
        viewModelScope.launch {
            transProcessRepository.getKeyboardPinStateFlow().collect { pinState ->
                Timber.d("⌨️  [PIN Keyboard] State: $pinState")
                // Tracks when user is typing on PAX physical keyboard
            }
        }

        // 3️⃣ PIN Result - Final validation result
        viewModelScope.launch {
            transProcessRepository.getPinResultFlow().collect { result ->
                when (result) {
                    0 -> {
                        Timber.i("✅ [PIN Result] PIN correct - Continuing transaction")
                        // PIN validated successfully by chip
                    }
                    else -> {
                        Timber.e("❌ [PIN Result] PIN incorrect or error: $result")
                        // PIN failed - SDK will retry or fail transaction
                    }
                }
            }
        }

        // 4️⃣ PIN Attempts - Remaining attempts
        viewModelScope.launch {
            transProcessRepository.getPinAttemptsFlow().collect { attempts ->
                Timber.d("🔢 [PIN Attempts] Remaining: $attempts")
                // Usually 3 attempts before card is blocked
            }
        }

        // 5️⃣ App Selection - When card has multiple applications
        viewModelScope.launch {
            transProcessRepository.getSelectAppStateFlow().collect { candidateList ->
                Timber.d("📱 [App Selection] Available apps: ${candidateList?.size ?: 0}")
                // SDK automatically selects the best matching app
                // We just need to collect this flow for the SDK to proceed
            }
        }

        // 6️⃣ ⚠️ CRITICAL: Card Reading Confirmation
        viewModelScope.launch {
            transProcessRepository.confirmCardReadingFlow().collect { confirmed ->
                Timber.d("✅ [Card Reading] Confirmed: $confirmed")

                // ⭐ CRITICAL: SDK waits for our response via ContinueConfirmCardUseCase
                // Without this response, StartEmvTransUseCase blocks indefinitely
                if (confirmed) {
                    Timber.i("🔄 [Card Reading] Responding with ContinueConfirmCard...")
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val params = ContinueConfirmCardParams(emvCode = 0) // 0 = success
                            continueConfirmCardUseCase.runInfallible(params)
                            Timber.i("✅ [Card Reading] Response sent to SDK - transaction can proceed")
                        } catch (e: Exception) {
                            Timber.e(e, "❌ Failed to send ContinueConfirmCard response")
                        }
                    }
                }
            }
        }
    }

    /**
     * Start OFFLINE chip card payment (NO online authorization)
     *
     * Flow: PreTrans → DetectCard → StartEmvTrans → CompleteEmvTrans (OFFLINE)
     *
     * Advantages:
     * - Does NOT require internet connection
     * - Easier debugging (no network errors)
     * - Validates PAX hardware works correctly
     * - PIN pad functionality validates
     *
     * Limitations:
     * - NO real bank authorization
     * - For testing/development only
     * - Transaction marked as "OFFLINE" in success screen
     *
     * Recommended by Blumon consultant: Implement OFFLINE first, then migrate to ONLINE
     */
    // ═══════════════════════════════════════════════════════════════════════════
    // MULTI-MERCHANT SWITCHING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Select a merchant account for payment processing
     *
     * **Flow:**
     * 1. Show loading overlay (3-5 seconds)
     * 2. Call MultiMerchantSDKManager.switchMerchant()
     *    - Updates TerminalConfig.serialNumber
     *    - Triggers SDK re-initialization (OAuth + DUKPT keys)
     * 3. Show success/error message
     * 4. Update current merchant state
     *
     * **Usage in UI:**
     * ```kotlin
     * Button(onClick = { viewModel.selectMerchant(accountA) }) {
     *     Text("Account A")
     * }
     * ```
     *
     * **User Experience:**
     * - Button click → "Cambiando a Account A..." (3-5s loading)
     * - Success → "✅ Ahora usando Account A. Puede procesar pago."
     * - Error → "❌ No se pudo cambiar. Intente nuevamente."
     *
     * @param account Target merchant account to switch to
     */
    fun selectMerchant(account: com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount) {
        Timber.i("🏪 [Merchants] User selected: ${account.displayName} (${account.serialNumber})")

        // Allow switching in Idle or SelectingMerchant states
        val currentState = _state.value
        if (currentState !is PaymentState.Idle && currentState !is PaymentState.SelectingMerchant) {
            Timber.w("⚠️ [Merchants] Cannot switch during active payment")
            _merchantSwitchMessage.value = "No puede cambiar de cuenta durante un pago activo"
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Step 1: Show loading
                _merchantSwitchingLoading.value = true
                _merchantSwitchMessage.value = "Iniciando cambio a ${account.displayName}..."
                Timber.d("🏪 [Merchants] Starting switch to: ${account.displayName}")

                // TODO: Future enhancement - Add progress callback from MultiMerchantSDKManager
                // Could show: OAuth (33%) → DUKPT (66%) → Done (100%)
                // For now, showing informative message

                // Step 2: Show intermediate progress message
                kotlinx.coroutines.delay(500)  // Brief delay for UI update
                _merchantSwitchMessage.value = "Autenticando con Blumon (3-5 segundos)..."

                // Step 3: Switch merchant (OAuth + DUKPT download + re-init)
                val result = multiMerchantSDKManager.switchMerchant(account)

                // Step 4: Handle result
                if (result.isSuccess) {
                    _currentMerchant.value = account
                    _merchantSwitchMessage.value = "✅ Ahora usando ${account.displayName}. Puede procesar pago."
                    Timber.i("✅ [Merchants] Successfully switched to: ${account.displayName}")
                    Timber.i("   Serial: ${account.serialNumber}")
                    Timber.i("   TerminalConfig.serialNumber: ${com.jaac.avoqado_tpv.core.domain.TerminalConfig.serialNumber}")
                } else {
                    val error = result.exceptionOrNull()
                    // Error message is already user-friendly from MultiMerchantSDKManager
                    _merchantSwitchMessage.value = "❌ ${error?.message ?: "Error desconocido al cambiar cuenta"}"
                    Timber.e(error, "❌ [Merchants] Failed to switch to: ${account.displayName}")
                }

            } catch (e: Exception) {
                Timber.e(e, "❌ [TECHNICAL] Unexpected error during merchant switch")
                _merchantSwitchMessage.value = "❌ Error inesperado al cambiar de cuenta.\n\nCierre y vuelva a abrir la app."
            } finally {
                _merchantSwitchingLoading.value = false
            }
        }
    }

    /**
     * Clear merchant switch message (dismiss success/error notification)
     */
    fun clearMerchantSwitchMessage() {
        _merchantSwitchMessage.value = null
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NEW PAYMENT FLOW: Rating → Tip → Merchant → Payment
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Initiate payment flow - Start with amount input
     *
     * **New Flow (Phase 4):**
     * EnteringAmount → CollectingRating → CollectingTip → SelectingMerchant → Payment
     */
    fun initiatePaymentFlow() {
        Timber.d("🎯 [Payment Flow] Initiating new payment flow")
        _state.value = PaymentState.EnteringAmount()
    }

    /**
     * Submit amount and proceed to rating screen
     */
    fun submitAmount(amount: String) {
        Timber.d("💰 [Payment Flow] Amount entered: $$amount")
        _state.value = PaymentState.CollectingRating(amount = amount)
    }

    /**
     * Update selected merchant in SelectingMerchant state (visual only, no SDK switch yet)
     */
    fun updateSelectedMerchant(merchant: MerchantAccount) {
        if (_state.value is PaymentState.SelectingMerchant) {
            Timber.d("🏪 [Payment Flow] Merchant selected (visual): ${merchant.displayName}")
            _currentMerchant.value = merchant
        } else {
            Timber.w("⚠️ [Payment Flow] updateSelectedMerchant called outside SelectingMerchant state")
        }
    }

    /**
     * Update rating when user taps a star
     */
    fun updateRating(amount: String, rating: Int) {
        Timber.d("⭐ [Payment Flow] Rating updated: $rating stars")
        _state.value = PaymentState.CollectingRating(amount = amount, rating = rating)
    }

    /**
     * Submit rating and proceed to tip screen
     */
    fun submitRating(amount: String, rating: Int) {
        Timber.d("⭐ [Payment Flow] Rating submitted: $rating stars")
        _state.value = PaymentState.CollectingTip(amount = amount, rating = rating)
    }

    /**
     * Skip rating and proceed to tip screen
     */
    fun skipRating(amount: String) {
        Timber.d("⏭️  [Payment Flow] Rating skipped")
        _state.value = PaymentState.CollectingTip(amount = amount, rating = null)
    }

    /**
     * Submit tip and proceed to merchant selection
     */
    fun submitTip(subtotal: String, tipAmount: String, rating: Int?) {
        Timber.d("💵 [Payment Flow] submitTip called with: subtotal='$subtotal', tipAmount='$tipAmount', rating=$rating")

        val totalAmount = calculateTotal(subtotal, tipAmount)

        Timber.d("💵 [Payment Flow] Calculated total: '$totalAmount' (subtotal='$subtotal' + tip='$tipAmount')")

        // ⭐ NEW: Save tip and rating for backend recording
        currentTip = tipAmount
        currentRating = rating

        _state.value = PaymentState.SelectingMerchant(
            subtotal = subtotal,
            tipAmount = tipAmount,
            totalAmount = totalAmount,
            rating = rating
        )

        Timber.d("💵 [Payment Flow] SelectingMerchant state created: subtotal='$subtotal', tipAmount='$tipAmount', totalAmount='$totalAmount'")
    }

    /**
     * Skip tip (no tip) and proceed to merchant selection
     */
    fun skipTip(subtotal: String, rating: Int?) {
        Timber.d("⏭️  [Payment Flow] Tip skipped")

        // ⭐ NEW: Save zero tip and rating for backend recording
        currentTip = "0.00"
        currentRating = rating

        _state.value = PaymentState.SelectingMerchant(
            subtotal = subtotal,
            tipAmount = "0",
            totalAmount = subtotal,
            rating = rating
        )
    }

    /**
     * Update tip percentage selection
     */
    fun updateTipPercentage(amount: String, rating: Int?, percentage: Int) {
        val tipAmount = calculateTipAmount(amount, percentage)
        Timber.d("💵 [Payment Flow] Tip percentage selected: $percentage% = $$tipAmount")
        _state.value = PaymentState.CollectingTip(
            amount = amount,
            rating = rating,
            selectedTipPercentage = percentage,
            tipAmount = tipAmount
        )
    }

    /**
     * Update custom tip amount
     */
    fun updateCustomTip(amount: String, rating: Int?, customTip: String) {
        Timber.d("💵 [Payment Flow] Custom tip entered: $$customTip")
        _state.value = PaymentState.CollectingTip(
            amount = amount,
            rating = rating,
            selectedTipPercentage = null,
            tipAmount = customTip
        )
    }

    /**
     * Calculate tip amount based on percentage
     */
    private fun calculateTipAmount(subtotal: String, percentage: Int): String {
        val subtotalDecimal = subtotal.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
        val tip = subtotalDecimal.multiply(java.math.BigDecimal(percentage))
            .divide(java.math.BigDecimal(100), 2, java.math.RoundingMode.HALF_UP)
        return tip.toString()
    }

    /**
     * Calculate total amount (subtotal + tip)
     */
    private fun calculateTotal(subtotal: String, tipAmount: String): String {
        Timber.d("🧮 [calculateTotal] Input: subtotal='$subtotal', tipAmount='$tipAmount'")

        val subtotalDecimal = subtotal.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
        val tipDecimal = tipAmount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO

        Timber.d("🧮 [calculateTotal] Parsed: subtotalDecimal=$subtotalDecimal, tipDecimal=$tipDecimal")

        val total = subtotalDecimal.add(tipDecimal)

        Timber.d("🧮 [calculateTotal] Result: $total")

        return total.toString()
    }

    /**
     * Convert decimal amount to cents
     * Example: "30.00" → "3000"
     */
    private fun convertToCents(amount: String): String {
        val amountDecimal = amount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
        val cents = amountDecimal.multiply(java.math.BigDecimal(100))
            .setScale(0, java.math.RoundingMode.HALF_UP)
        return cents.toLong().toString()
    }

    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Start chip card payment with ONLINE bank authorization via Momentum platform
     *
     * Flow: PreTrans → DetectCard → StartEmvTrans → GetEmvTags → SaleIcc (ONLINE) → CompleteEmvTrans (conditional)
     *
     * ⚠️ CRITICAL: CompleteEmvTrans is ONLY called if the card requires ARPC (AIP bit 3 = 1)
     * This prevents error -11 (FailureSecondGenerate) for cards that don't need ARPC.
     */
    fun startPayment(amount: String) {
        currentAmount = amount  // Save for UI display
        currentAmountInCents = convertToCents(amount)  // Save for SDK calls

        // ⭐ NEW: Get venue and staff context for backend recording
        currentVenueId = authRepository.getVenueId() ?: ""
        currentStaffId = authRepository.getStaffId() ?: ""

        Timber.d("🎯 [BlumonPayment] Starting ONLINE chip payment flow: $$amount")
        Timber.d("   💰 Amount: $$amount → $currentAmountInCents centavos")
        Timber.d("   🏪 Venue: $currentVenueId | Staff: $currentStaffId | Tip: $$currentTip")
        _state.value = PaymentState.ConfiguringKernel

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // ═══════════════════════════════════════════════════════════════════════════
                // PASO 0: Ensure correct merchant SDK is active (multi-merchant support)
                // ═══════════════════════════════════════════════════════════════════════════

                // 🛡️ CRITICAL: Prevent race condition if user rapidly clicks back/forward
                // during merchant switch. Without this check, user can trigger multiple
                // concurrent switches (queued by Mutex) leading to confusing UI states.
                if (_merchantSwitchingLoading.value) {
                    Timber.w("⚠️ [Merchant Switch] Switch already in progress, blocking duplicate request")
                    _state.value = PaymentState.Error(
                        message = "Ya hay un cambio de cuenta en progreso.\n\n" +
                                  "Por favor espere a que termine la operación actual.",
                        context = createPaymentContext()
                    )
                    return@launch
                }

                val selectedMerchant = _currentMerchant.value
                if (selectedMerchant == null) {
                    Timber.e("❌ [Merchant Switch] No merchant selected before payment")
                    _state.value = PaymentState.Error(
                        message = "Debe seleccionar una cuenta de pago antes de continuar",
                        context = createPaymentContext()
                    )
                    return@launch
                }

                // Check if we need to switch merchants (3-5 seconds if switching, 0ms if already active)
                if (!multiMerchantSDKManager.isMerchantActive(selectedMerchant)) {
                    Timber.i("🔄 [Merchant Switch] Switching SDK to: ${selectedMerchant.displayName} (${selectedMerchant.serialNumber})")
                    _state.value = PaymentState.Processing("Configurando cuenta ${selectedMerchant.displayName}...")

                    val switchResult = multiMerchantSDKManager.switchMerchant(selectedMerchant)
                    if (switchResult.isFailure) {
                        val error = switchResult.exceptionOrNull()
                        Timber.e(error, "❌ [Merchant Switch] Failed to switch to: ${selectedMerchant.displayName}")
                        _state.value = PaymentState.Error(
                            message = "Error configurando cuenta:\n${error?.message ?: "Error desconocido"}",
                            context = createPaymentContext()
                        )
                        return@launch
                    }
                    Timber.i("✅ [Merchant Switch] Successfully switched to: ${selectedMerchant.displayName}")
                } else {
                    Timber.d("✅ [Merchant Switch] Already on correct merchant: ${selectedMerchant.displayName} (no switch needed)")
                }

                // ═══════════════════════════════════════════════════════════════════════════
                // PASO 1: PreTrans (configure EMV kernel)
                // ═══════════════════════════════════════════════════════════════════════════
                _state.value = PaymentState.ConfiguringKernel
                Timber.i("[PHASE 1] PreTrans - Configuring EMV kernel...")
                val preParams = PreTransParams(currentAmountInCents, "0", TransType.SALE, CountryConstants.MEX)
                preTransUseCase.runInfallible(preParams)
                Timber.d("✅ [PHASE 1] PreTrans completed")

                // PASO 2: StartDetectCard (wait for card tap)
                _state.value = PaymentState.DetectingCard(currentAmount)
                Timber.i("[PHASE 2] StartDetectCard - Waiting for card tap...")
                val detectParams = StartDetectCardParams(EReaderType.MAG_ICC_PICC)
                val detectResult = startDetectCardUseCase.run(detectParams)

                if (detectResult.isLeft) {
                    val error = detectResult.leftValue()
                    Timber.e("❌ [PHASE 2] Detect card failed: $error")
                    _state.value = PaymentState.Error(
                        message = "Error detectando tarjeta: $error",
                        context = createPaymentContext()  // 🔄 Preserve context for smart retry
                    )
                    return@launch
                }

                val detectResponse = detectResult.rightValue()
                val pollingResult = detectResponse.pollingResult
                val detectedReaderType = pollingResult.readerType
                val cardType = mapReaderTypeToCardType(detectedReaderType)

                Timber.i("✅ [PHASE 2] Card detected - Type: $cardType (ReaderType: $detectedReaderType)")

                // ═══════════════════════════════════════════════════════════════════════════
                // PASO 2.5: Route to correct payment processor based on card type
                // ═══════════════════════════════════════════════════════════════════════════
                when (cardType) {
                    CardType.PICC -> {
                        Timber.i("🔄 [ROUTING] Contactless card detected → Calling processContactlessPayment()")
                        processContactlessPayment(currentAmountInCents)  // ✅ Pass cents format
                        return@launch  // Exit startPayment() - contactless flow handles everything
                    }
                    CardType.ICC, CardType.MAG -> {
                        Timber.i("🔄 [ROUTING] Chip/Mag card detected → Continuing with chip payment flow")
                        // Continue with chip payment below (PASO 3)
                    }
                    CardType.UNKNOWN -> {
                        Timber.e("❌ [ROUTING] Unknown card type detected: $detectedReaderType")
                        _state.value = PaymentState.Error(
                            message = "Tipo de tarjeta no soportado",
                            context = createPaymentContext()  // 🔄 Preserve context for smart retry
                        )
                        return@launch
                    }
                }

                // PASO 3: StartEmvTrans (process chip locally)
                _state.value = PaymentState.Processing("Procesando chip...")
                Timber.i("[PHASE 3] StartEmvTrans - Processing EMV chip...")
                val emvParams = StartEmvTransParams()
                val emvResult = startEmvTransUseCase.run(emvParams)

                if (emvResult.isLeft) {
                    val error = emvResult.leftValue()
                    Timber.e("❌ [PHASE 3] EMV failed: $error")
                    _state.value = PaymentState.Error(
                        message = "Error procesando EMV: $error",
                        context = createPaymentContext()  // 🔄 Preserve context for smart retry
                    )
                    return@launch
                }

                val emvResponse = emvResult.rightValue()
                Timber.d("✅ [PHASE 3] EMV processed successfully")

                // ═══════════════════════════════════════════════════════════════════════════
                // PASO 3.5: Extract COMPLETE EMV Tag List (23 tags required by Blumon)
                // ═══════════════════════════════════════════════════════════════════════════
                _state.value = PaymentState.Processing("Leyendo datos de la tarjeta...")
                Timber.i("═══════════════════════════════════════════════════════════")
                Timber.i("[PHASE 3.5] GetEmvTagList - Extracting COMPLETE EMV data from chip...")
                Timber.i("═══════════════════════════════════════════════════════════")

                // Extract 21 EMV tags specified by Edgardo (2025-11-05)
                // CRITICAL: Order and list per Edgardo's specification from WhatsApp conversation
                // "Lista de tags para enviar en chip: CHIP(listOf(0x9F27, 0x9F26, ...))"
                val emvTagParams = GetEmvTagListParam(
                    emvTagList = listOf(
                        0x9F27,  // Cryptogram Information Data (CID)
                        0x9F26,  // Application Cryptogram (ARQC) ← CRÍTICO
                        0x9F37,  // Unpredictable Number ← CRÍTICO
                        0x9F36,  // Application Transaction Counter (ATC)
                        0x9C,    // Transaction Type
                        0x82,    // Application Interchange Profile (AIP)
                        0x9F33,  // Terminal Capabilities
                        0x9F34,  // Cardholder Verification Method (CVM) Results
                        0x9A,    // Transaction Date (YYMMDD)
                        0x5F2A,  // Transaction Currency Code
                        0x9F02,  // Amount, Authorized (Numeric)
                        0x9F03,  // Amount, Other (Numeric) - Cashback
                        0x9F35,  // Terminal Type
                        0x5F34,  // Application PAN Sequence Number
                        0x9F10,  // Issuer Application Data (IAD) ← CRÍTICO
                        0x84,    // Dedicated File (DF) Name / AID
                        0x9F09,  // Application Version Number ← ADDED per Edgardo
                        0x9F1A,  // Terminal Country Code
                        0x95,    // Terminal Verification Results (TVR)
                        0x9F1E,  // Interface Device (IFD) Serial Number ← ADDED per Edgardo
                        0x50     // Application Label ← ADDED per Edgardo
                    ),
                    format = Format.DECIMAL,  // ⭐ CRITICAL: DECIMAL for CHIP (per Edgardo)
                    cardTech = CardTech.CHIP  // ICC transaction (chip card)
                )

                Timber.d("   Requesting ${emvTagParams.emvTagList.size} EMV tags from kernel...")

                val tagListResult = getEmvTagListUseCase.runInfallible(emvTagParams)
                val emvTagListStr = tagListResult.emvTagList  // Complete TLV string

                Timber.i("✅ [PHASE 3.5] Complete EMV tag extraction SUCCESS!")
                Timber.i("   Total TLV length: ${emvTagListStr.length} characters")
                Timber.i("   First 150 chars: ${emvTagListStr.take(150)}...")
                Timber.i("   Last 100 chars: ...${emvTagListStr.takeLast(100)}")
                Timber.i("═══════════════════════════════════════════════════════════")

                // ⚠️ CRITICAL: Extract Track2 using GetTagValueUseCase (not regex)
                // Regex was matching "57" inside other tags (e.g., 9F35012257...)
                // GetTagValueUseCase properly parses TLV structure
                Timber.d("   Extracting Track2 (tag 0x57) using GetTagValueUseCase...")
                val track2Params = GetTagValueParams(
                    tag = 0x57,  // Track 2 Equivalent Data
                    cardTech = CardTech.CHIP
                )
                val track2Result = getTagValueUseCase.run(track2Params)
                currentTrack2 = if (track2Result.isRight) {
                    track2Result.rightValue().tagValue ?: ""
                } else {
                    ""
                }

                if (currentTrack2.isNotEmpty()) {
                    Timber.i("   ✅ Track2 extracted: ${currentTrack2.take(16)}... (length: ${currentTrack2.length})")
                } else {
                    Timber.w("   ⚠️ Track2 not found in EMV tags - this may cause issues")
                }

                // ⚠️ NOTE: emvTagListStr already contains ALL 21 tags in correct TLV format
                // No need to manually construct - GetEmvTagListUseCase returned complete TLV string

                // ✅ REMOVED: Init logic (PASO 3.9 + 3.9.5) - Now handled by InitializationManager in init{}
                // This was causing 65 duplicate database rows because init was called on EVERY payment.
                // Per Edgardo (2025-11-05): "Es recomendable realizar el init solo una vez cada 24 horas"
                // InitializationManager now ensures init runs once every 24 hours via timestamp caching.

                // PASO 4: ⭐ SaleIcc - ONLINE AUTHORIZATION ⭐
                _state.value = PaymentState.Processing("Autorizando con banco...")
                Timber.i("[PHASE 4] SaleIcc - Sending to Momentum for ONLINE authorization...")
                val authResult = performOnlineAuthorization(
                    amount = currentAmountInCents,  // ✅ Pass cents format to SDK
                    track2 = currentTrack2,  // Extracted from emvTagListStr above
                    cardHolderName = "CARDHOLDER",  // TODO: Extract from tag 5F20 if available
                    emvTagList = emvTagListStr
                )

                if (authResult.response == null) {
                    Timber.e("❌ [PHASE 4] Online authorization FAILED")
                    _state.value = PaymentState.Error(
                        message = authResult.userFriendlyError ?: "Error en autorización con banco",
                        context = createPaymentContext()  // 🔄 Preserve context for smart retry
                    )
                    return@launch
                }

                val saleData = authResult.response.saleData
                Timber.i("✅ [PHASE 4] Online authorization SUCCESS!")
                Timber.i("   Auth Code: ${saleData.authorization}")
                Timber.i("   Reference: ${saleData.reference}")
                Timber.i("   EMV Code: ${saleData.emvResponseCode}")
                Timber.i("   ARPC: ${saleData.arpc?.take(16)}...")

                // ⭐ PASO 4.5: AIP Checking - Determine if CompleteEmvTrans is required
                // Check AIP (Application Interchange Profile) bit 3 to know if card needs ARPC
                // Extract AIP (tag 82) using GetTagValueUseCase
                Timber.d("[PHASE 4.5] Extracting AIP (tag 0x82) to check ARPC requirement...")
                val aipParams = GetTagValueParams(
                    tag = 0x82,  // Application Interchange Profile
                    cardTech = CardTech.CHIP
                )
                val aipResult = getTagValueUseCase.run(aipParams)
                val aipHex = if (aipResult.isRight) {
                    aipResult.rightValue().tagValue ?: ""
                } else {
                    ""
                }

                val arpcRequired = if (aipHex.length >= 2) {
                    val firstByte = aipHex.substring(0, 2).toInt(16)
                    (firstByte and 0x04) != 0  // Bit 3 (0x04) indicates ARPC support
                } else {
                    false
                }

                Timber.i("[PHASE 4.5] AIP Checking - ARPC required: $arpcRequired (AIP: $aipHex)")

                if (arpcRequired) {
                    // PASO 5: CompleteEmvTrans with REAL ARPC from Momentum
                    Timber.i("[PHASE 5] CompleteEmvTrans - Card requires ARPC, updating chip...")
                    val completeParams = CompleteEmvTransParams(
                        emvResponseCode = saleData.emvResponseCode ?: "00",
                        authorization = saleData.authorization ?: "",
                        arpc = saleData.arpc ?: "",
                        script7172 = saleData.script ?: "",
                        arpcResponseCode = "00"
                    )

                    val completeResult = completeEmvTransUseCase.run(completeParams)

                    if (completeResult.isLeft) {
                        val error = completeResult.leftValue()
                        Timber.e("❌ [PHASE 5] Complete failed: $error")
                        _state.value = PaymentState.Error("Error finalizando transacción: $error")
                        return@launch
                    }

                    Timber.i("✅ [PHASE 5] EMV completion SUCCESS!")
                } else {
                    // Card does NOT require ARPC - Skip CompleteEmvTrans
                    Timber.i("✅ [PHASE 5] Skipping CompleteEmvTrans (card does not require ARPC)")
                }

                Timber.i("🎉 PAYMENT APPROVED WITH ONLINE AUTHORIZATION!")

                _state.value = PaymentState.Success(
                    authCode = saleData.authorization ?: "",
                    amount = currentAmount
                )

                // ⭐ NEW: Record payment to backend (in background)
                handlePaymentSuccess(
                    saleData = saleData,
                    entryMode = CardEntryMode.CHIP
                )

            } catch (e: Exception) {
                Timber.e(e, "❌ [BlumonPayment] Unexpected error in payment flow")
                _state.value = PaymentState.Error("Error inesperado: ${e.message}")
            }
        }
    }

    /**
     * ⭐ Perform online authorization with Momentum platform using SaleIccUseCase
     *
     * Based on successful implementation from BLUMON_INTEGRATION_GUIDE.md
     */
    /**
     * Result class for online authorization
     * @param response The successful response from Blumon, or null if failed
     * @param userFriendlyError User-friendly error message (Spanish), or null if success
     */
    private data class AuthorizationResult(
        val response: SaleIccResponse?,
        val userFriendlyError: String?
    )

    private suspend fun performOnlineAuthorization(
        amount: String,
        track2: String,
        cardHolderName: String,
        emvTagList: String
    ): AuthorizationResult {
        return try {
            // ⚠️ CRITICAL: Retrieve validated posId from SDK database BEFORE calling SaleIccUseCase
            // This must be done in the same suspend context to prevent NumberFormatException
            Timber.i("🔐 [GetInitData] Retrieving validated posId from SDK database...")
            val initDataParams = GetInitDataParams()
            val initDataResult = getInitDataUseCase.run(initDataParams)

            if (initDataResult.isLeft) {
                val failure = initDataResult.leftValue()
                Timber.e("❌ [GetInitData] Failed: $failure")
                return AuthorizationResult(
                    response = null,
                    userFriendlyError = "Error obteniendo configuración del terminal.\n\nPor favor, reinicie la aplicación."
                )
            }

            val initDataResponse = initDataResult.rightValue()
            val initData = initDataResponse.initData
            Timber.i("✅ [GetInitData] Success!")
            Timber.i("   posId: ${initData.posId} (safe to parse as Int - prevents NumberFormatException)")
            Timber.i("   Commerce Name: ${initData.commerceName}")
            Timber.i("   Commerce Address: ${initData.commerceAddress}")

            Timber.i("🌐 [SaleIcc] Sending online authorization to Momentum...")
            Timber.d("   Amount: $amount")
            Timber.d("   Track2: ${track2.take(16)}...")
            Timber.d("   Cardholder: $cardHolderName")
            Timber.d("   EMV Tags: ${emvTagList.take(50)}...")

            // Build SaleIccParams with REAL EMV data from chip
            // ⚠️ CRITICAL FIX: ALWAYS use CipherType.DUKPT (even in SANDBOX)
            //
            // REASON: SaleIccUseCase has a bug where CipherType.KUSHKY throws NotImplementedError
            // Source: lib-services-BP-SAND_1601.aar -> SaleIccUseCase.kt:99
            //
            // when (params.cipherType) {
            //     CipherType.DUKPT -> { /* ✅ Works - Generates DUKPTDataForSale */ }
            //     CipherType.PLAIN -> { /* ✅ Works - Uses fixed counter */ }
            //     CipherType.KUSHKY -> { /* ❌ throw NotImplementedError() */ }
            // }
            //
            // SOLUTION: Force DUKPT for both SANDBOX and PROD
            // - InitializerUseCase already downloaded DUKPT keys (ksn + ipek) from Blumon server
            // - InitData.kushki.isKsk defaults to false (from server response)
            // - DUKPT encryption works correctly in both environments
            //
            // Reference: AvoqadoPOS/BlumonPaymentViewModel.kt:1445-1473
            val cipherType = CipherType.DUKPT  // ✅ ALWAYS DUKPT (NEVER KUSHKY due to SDK bug)

            val params = SaleIccParams(
                idMembership = "",  // Empty = no loyalty program (optional field)
                amount = amount,
                currency = "484",  // MXN (ISO 4217)
                track2 = track2,  // ✅ Real Track2 from chip
                cardHolderName = cardHolderName,  // ✅ Real cardholder name from chip
                authenticationCard = AuthenticationCard.SIGNATURE,  // TODO: Detect from CVM Results (9F34)
                emvTagList = emvTagList,  // ✅ Real EMV tags from chip (TLV format)
                cipherType = cipherType,  // ✅ DUKPT for both SANDBOX and PROD
                msi = null  // No installments for MVP
            )

            // Call SaleIccUseCase (returns Either<Failure, Success>)
            val result = saleIccUseCase.run(params)

            // Handle Either<Failure, Success> result
            when {
                result.isLeft -> {
                    // Handle failure - Translate SDK error to user-friendly message
                    val failure = result.leftValue()
                    Timber.e("❌ [SaleIcc] Failed: $failure")

                    // Extract error message from SDK failure object
                    val errorString = failure.toString()

                    // Translate SDK errors to user-friendly Spanish messages
                    val userMessage = when {
                        errorString.contains("AMOUNT' must be greater than", ignoreCase = true) -> {
                            "El monto del pago debe ser mayor a $0.00\n\n" +
                            "Por favor, verifica el monto e intenta nuevamente."
                        }
                        errorString.contains("RQ_002", ignoreCase = true) -> {
                            "Error en validación de datos.\n\n" +
                            "Verifica el monto del pago e intenta nuevamente."
                        }
                        errorString.contains("401", ignoreCase = true) ||
                        errorString.contains("Unauthorized", ignoreCase = true) -> {
                            "Error de autenticación con el banco.\n\n" +
                            "Verifica la configuración del terminal."
                        }
                        errorString.contains("timeout", ignoreCase = true) -> {
                            "Tiempo de espera agotado.\n\n" +
                            "Verifica tu conexión a internet e intenta nuevamente."
                        }
                        errorString.contains("network", ignoreCase = true) -> {
                            "Error de conexión.\n\n" +
                            "Verifica tu conexión a internet e intenta nuevamente."
                        }
                        errorString.contains("declined", ignoreCase = true) ||
                        errorString.contains("rechazado", ignoreCase = true) -> {
                            "Pago rechazado por el banco.\n\n" +
                            "Solicita otra forma de pago."
                        }
                        else -> {
                            "Error en autorización con banco.\n\n" +
                            "Por favor, intenta nuevamente o contacta soporte."
                        }
                    }

                    AuthorizationResult(response = null, userFriendlyError = userMessage)
                }
                else -> {
                    // Handle success
                    val response = result.rightValue()
                    Timber.i("✅ [SaleIcc] Success!")
                    Timber.i("   Operation: ${response.operation}")
                    Timber.i("   Auth: ${response.saleData.authorization}")
                    Timber.i("   Reference: ${response.saleData.reference}")

                    // ⭐ DETAILED LOGGING: Full Blumon response structure
                    Timber.d("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    Timber.d("📋 [BLUMON RESPONSE] Full SaleIccResponse structure:")
                    Timber.d("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    try {
                        Timber.d("🔹 operation: ${response.operation}")
                        Timber.d("🔹 saleData.authorization: ${response.saleData.authorization}")
                        Timber.d("🔹 saleData.reference: ${response.saleData.reference}")
                        Timber.d("🔹 saleData.emvResponseCode: ${response.saleData.emvResponseCode}")
                        Timber.d("🔹 saleData.arpc: ${response.saleData.arpc}")
                        Timber.d("🔹 saleData.script: ${response.saleData.script}")

                        // Try to access fields that might exist via reflection
                        val saleDataClass = response.saleData::class.java
                        Timber.d("🔍 SaleData class: ${saleDataClass.simpleName}")
                        Timber.d("🔍 SaleData fields:")
                        saleDataClass.declaredFields.forEach { field ->
                            try {
                                field.isAccessible = true
                                val value = field.get(response.saleData)
                                if (value != null) {
                                    Timber.d("   • ${field.name}: $value (${value.javaClass.simpleName})")
                                }
                            } catch (e: Exception) {
                                // Ignore fields we can't access
                            }
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Could not extract full response structure")
                    }
                    Timber.d("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                    AuthorizationResult(response = response, userFriendlyError = null)
                }
            }

        } catch (e: Exception) {
            Timber.e(e, "❌ [SaleIcc] Exception in online authorization")
            AuthorizationResult(
                response = null,
                userFriendlyError = "Error inesperado procesando el pago.\n\nPor favor, intenta nuevamente."
            )
        }
    }

    /**
     * Process contactless (NFC tap-and-go) payment
     *
     * Flow: PreTrans → StartCtlssTransUseCase → Extract TransResultEnum → Route by result
     *
     * Possible results:
     * - RESULT_REQ_ONLINE: Card requires online authorization (call SaleIcc)
     * - RESULT_OFFLINE_APPROVED: Card approved offline (no bank authorization needed)
     * - RESULT_OFFLINE_DENIED: Card declined offline
     *
     * Based on AvoqadoPOS/BlumonPaymentViewModel.kt:1105-1271
     */
    private suspend fun processContactlessPayment(amount: String) {
        try {
            Timber.i("═══════════════════════════════════════════════════════════")
            Timber.i("🌊 [CONTACTLESS] Starting contactless (NFC) payment flow")
            Timber.i("═══════════════════════════════════════════════════════════")

            // PreTrans already called in startPayment() - we're ready to process

            // PASO 1: StartCtlssTransUseCase (process contactless transaction)
            _state.value = PaymentState.Processing("Procesando pago contactless...")
            Timber.i("[CONTACTLESS PHASE 1] StartCtlssTransUseCase - Processing NFC transaction...")

            val ctlssParams = StartCtlssTransParams()
            val ctlssResult = startCtlssTransUseCase.run(ctlssParams)

            if (ctlssResult.isLeft) {
                val error = ctlssResult.leftValue()
                Timber.e("❌ [CONTACTLESS PHASE 1] Contactless transaction failed: $error")

                // Translate SDK error to user-friendly message
                val userMessage = when {
                    error.toString().contains("ReadingContactlessFailure", ignoreCase = true) -> {
                        "La tarjeta se retiró demasiado rápido.\n\nPor favor, mantenga la tarjeta sobre el lector hasta que aparezca el mensaje de confirmación."
                    }
                    error.toString().contains("Timeout", ignoreCase = true) -> {
                        "Tiempo de espera agotado.\n\nPor favor, mantenga la tarjeta cerca del lector durante toda la transacción."
                    }
                    error.toString().contains("Collision", ignoreCase = true) -> {
                        "Se detectaron múltiples tarjetas.\n\nPor favor, presente solo una tarjeta a la vez."
                    }
                    else -> {
                        "Error leyendo tarjeta contactless.\n\nIntente nuevamente o inserte la tarjeta en el chip."
                    }
                }

                _state.value = PaymentState.Error(
                    message = userMessage,
                    context = createPaymentContext()  // 🔄 Preserve context for smart retry
                )
                return
            }

            val ctlssResponse = ctlssResult.rightValue()
            Timber.i("✅ [CONTACTLESS PHASE 1] Contactless transaction processed")

            // PASO 2: Read TransResult directly from the SDK response (no reflection)
            Timber.i("[CONTACTLESS PHASE 2] Extracting transaction result...")
            val transResult = ctlssResponse.transResult ?: run {
                Timber.e("❌ [CONTACTLESS PHASE 2] transResult is null in SDK response")
                _state.value = PaymentState.Error(
                    message = "Error procesando resultado contactless",
                    context = createPaymentContext()  // 🔄 Preserve context for smart retry
                )
                return
            }
            val transResultEnum = transResult.transResult
            if (transResultEnum == null) {
                Timber.e("❌ [CONTACTLESS PHASE 2] transResultEnum is null (resultCode=${transResult.resultCode})")
                _state.value = PaymentState.Error(
                    message = "Error procesando resultado contactless",
                    context = createPaymentContext()  // 🔄 Preserve context for smart retry
                )
                return
            }
            Timber.i(
                "✅ [CONTACTLESS PHASE 2] Transaction result: $transResultEnum (resultCode=${transResult.resultCode})"
            )

            // PASO 3: Route based on transaction result
            when (transResultEnum) {
                TransResultEnum.RESULT_REQ_ONLINE -> {
                    // Card requires online authorization with bank
                    Timber.i("[CONTACTLESS PHASE 3] RESULT_REQ_ONLINE → Extracting EMV tags and calling SaleIcc...")
                    processContactlessOnlineAuthorization(currentAmountInCents)  // ✅ Pass cents format
                }

                TransResultEnum.RESULT_OFFLINE_APPROVED -> {
                    // Card approved offline (no online authorization needed)
                    Timber.i("🎉 [CONTACTLESS PHASE 3] RESULT_OFFLINE_APPROVED → Payment approved offline!")
                    _state.value = PaymentState.Success(
                        authCode = "OFFLINE_APPROVED",
                        amount = currentAmount  // ✅ Use decimal format for display
                    )
                }

                TransResultEnum.RESULT_OFFLINE_DENIED -> {
                    // Card declined offline
                    Timber.e("❌ [CONTACTLESS PHASE 3] RESULT_OFFLINE_DENIED → Card declined")
                    _state.value = PaymentState.Error(
                        message = "Tarjeta declinada",
                        context = createPaymentContext()  // 🔄 Preserve context for smart retry
                    )
                }

                else -> {
                    // Unknown result
                    Timber.e("❌ [CONTACTLESS PHASE 3] Unknown transaction result: $transResultEnum")
                    _state.value = PaymentState.Error(
                        message = "Resultado desconocido: $transResultEnum",
                        context = createPaymentContext()  // 🔄 Preserve context for smart retry
                    )
                }
            }

        } catch (e: Exception) {
            Timber.e(e, "❌ [CONTACTLESS] Unexpected error in contactless payment flow")
            _state.value = PaymentState.Error(
                message = "Error inesperado en pago contactless: ${e.message}",
                context = createPaymentContext()  // 🔄 Preserve context for smart retry
            )
        }
    }

    /**
     * Process contactless payment with online authorization
     *
     * Called when StartCtlssTransUseCase returns RESULT_REQ_ONLINE
     */
    private suspend fun processContactlessOnlineAuthorization(amount: String) {
        try {
            Timber.i("[CONTACTLESS ONLINE] Starting online authorization flow...")

            // PASO 1: Extract EMV tags with CardTech.CONTACTLESS
            _state.value = PaymentState.Processing("Leyendo datos de la tarjeta contactless...")
            Timber.i("[CONTACTLESS ONLINE PHASE 1] Extracting EMV tags with CardTech.CONTACTLESS...")

            // ⭐ CRITICAL: Use SAME 21 tags as chip (per Edgardo's specification)
            // Same tag list + Format.DECIMAL makes contactless work like chip
            val emvTagParams = GetEmvTagListParam(
                emvTagList = listOf(
                    0x9F27,  // Cryptogram Information Data (CID)
                    0x9F26,  // Application Cryptogram (ARQC)
                    0x9F37,  // Unpredictable Number
                    0x9F36,  // Application Transaction Counter (ATC)
                    0x9C,    // Transaction Type
                    0x82,    // Application Interchange Profile (AIP)
                    0x9F33,  // Terminal Capabilities
                    0x9F34,  // Cardholder Verification Method (CVM) Results
                    0x9A,    // Transaction Date (YYMMDD)
                    0x5F2A,  // Transaction Currency Code
                    0x9F02,  // Amount, Authorized (Numeric)
                    0x9F03,  // Amount, Other (Numeric) - Cashback
                    0x9F35,  // Terminal Type
                    0x5F34,  // Application PAN Sequence Number
                    0x9F10,  // Issuer Application Data (IAD)
                    0x84,    // Dedicated File (DF) Name / AID
                    0x9F09,  // Application Version Number
                    0x9F1A,  // Terminal Country Code
                    0x95,    // Terminal Verification Results (TVR)
                    0x9F1E,  // Interface Device (IFD) Serial Number
                    0x50     // Application Label
                ),
                format = Format.DECIMAL,  // ⭐ CRITICAL: DECIMAL (same as chip)
                cardTech = CardTech.CONTACTLESS  // ⭐ CRITICAL: Use CONTACTLESS instead of CHIP
            )

            val tagListResult = getEmvTagListUseCase.runInfallible(emvTagParams)
            val emvTagListStr = tagListResult.emvTagList

            Timber.i("✅ [CONTACTLESS ONLINE PHASE 1] EMV tags extracted (${emvTagListStr.length} chars)")
            Timber.i("   First 100 chars: ${emvTagListStr.take(100)}...")

            // PASO 2: Extract Track2 using CardTech.CONTACTLESS
            Timber.d("   Extracting Track2 (tag 0x57) for contactless...")
            val track2Params = GetTagValueParams(
                tag = 0x57,
                cardTech = CardTech.CONTACTLESS  // ⭐ CRITICAL: Use CONTACTLESS
            )
            val track2Result = getTagValueUseCase.run(track2Params)
            val track2 = if (track2Result.isRight) {
                track2Result.rightValue().tagValue ?: ""
            } else {
                ""
            }

            if (track2.isNotEmpty()) {
                Timber.i("   ✅ Track2 extracted: ${track2.take(16)}... (length: ${track2.length})")
            } else {
                Timber.w("   ⚠️ Track2 not found - may cause issues")
            }

            // ✅ REMOVED: Init logic (PASO 2 + 2.5) - Now handled by InitializationManager in init{}
            // This was causing duplicate database rows on every contactless payment.
            // InitializationManager ensures init runs once every 24 hours per Edgardo's recommendation.

            // PASO 2: Call SaleIcc for online authorization (same as chip)
            _state.value = PaymentState.Processing("Autorizando con banco...")
            Timber.i("[CONTACTLESS ONLINE PHASE 2] Calling SaleIcc for online authorization...")

            val authResult = performOnlineAuthorization(
                amount = amount,
                track2 = track2,
                cardHolderName = "CARDHOLDER",
                emvTagList = emvTagListStr
            )

            if (authResult.response == null || authResult.userFriendlyError != null) {
                Timber.e("❌ [CONTACTLESS ONLINE PHASE 2] Online authorization FAILED: ${authResult.userFriendlyError}")
                _state.value = PaymentState.Error(
                    message = authResult.userFriendlyError ?: "Error en autorización con banco",
                    context = createPaymentContext()  // 🔄 Preserve context for smart retry
                )
                return
            }

            val saleResponse = authResult.response!!
            val saleData = saleResponse.saleData
            Timber.i("✅ [CONTACTLESS ONLINE PHASE 2] Online authorization SUCCESS!")
            Timber.i("   Auth Code: ${saleData.authorization}")
            Timber.i("   Reference: ${saleData.reference}")

            // ⚠️ NOTE: Contactless typically does NOT require CompleteEmvTrans (ARPC)
            // Skip ARPC checking for contactless transactions
            Timber.i("ℹ️  [CONTACTLESS ONLINE] Skipping CompleteEmvTrans (not required for contactless)")

            Timber.i("🎉 CONTACTLESS PAYMENT APPROVED WITH ONLINE AUTHORIZATION!")

            _state.value = PaymentState.Success(
                authCode = saleData.authorization ?: "",
                amount = currentAmount  // ✅ Use decimal format for display
            )

            // ⭐ NEW: Record payment to backend (in background)
            handlePaymentSuccess(
                saleData = saleData,
                entryMode = CardEntryMode.CONTACTLESS
            )

        } catch (e: Exception) {
            Timber.e(e, "❌ [CONTACTLESS ONLINE] Unexpected error")
            _state.value = PaymentState.Error(
                message = "Error inesperado: ${e.message}",
                context = createPaymentContext()  // 🔄 Preserve context for smart retry
            )
        }
    }

    /**
     * Cancel payment (remove card)
     */
    fun cancelPayment() {
        viewModelScope.launch {
            stopDetectCardUseCase.runInfallible(StopDetectCardParams())
            _state.value = PaymentState.Cancelled
            Timber.d("🚫 Payment cancelled by user")
        }
    }

    /**
     * Reset payment state to idle (ONLY for cancel/success).
     *
     * ⚠️ WARNING: Do NOT use this for error retry!
     * Use retryPayment() instead to preserve user's entered data.
     */
    fun resetPayment() {
        _state.value = PaymentState.Idle
        currentAmount = ""
        currentTip = "0"
        currentRating = null
        currentTrack2 = ""
        Timber.d("🔄 Payment state reset")
    }

    /**
     * Create immutable RetryContext from current ViewModel state.
     *
     * **Philosophy (Toast/Square pattern):**
     * Snapshot the current transaction data to enable smart retry.
     * If payment fails, context can be restored without losing user input.
     *
     * @return RetryContext with amount, tip, rating, merchant
     */
    private fun createPaymentContext(): RetryContext {
        val context = RetryContext(
            amount = currentAmount,
            tipAmount = currentTip,
            rating = currentRating,
            merchantAccountId = _currentMerchant.value?.id ?: ""
        )
        Timber.d("📸 [Context Snapshot] amount=$currentAmount | tip=$currentTip | rating=$currentRating | merchant=${_currentMerchant.value?.id ?: "NULL"}")
        Timber.d("📸 [Context Snapshot] isValid=${context.isValid()}")
        return context
    }

    /**
     * Smart retry with preserved context (Toast/Square/Stripe pattern).
     *
     * **Philosophy:**
     * When payment fails (card timeout, declined, SDK error), user should
     * NEVER have to re-enter amount, tip, rating, or merchant selection.
     *
     * **Flow:**
     * 1. User enters $50 + 10% tip + 5★ rating
     * 2. Card times out during payment
     * 3. Error state preserves context
     * 4. User taps "Reintentar"
     * 5. → Restore context (amount/tip/rating/merchant)
     * 6. → Go directly to ConfiguringKernel → DetectingCard
     * 7. User presents card again (NO re-entering data!)
     *
     * **Called from:** PaymentErrorContent when user taps "Reintentar"
     *
     * @param context Preserved payment data from Error state
     */
    fun retryPayment(context: RetryContext?) {
        Timber.i("🔄 [Smart Retry] Called with context: $context")

        if (context == null) {
            Timber.w("⚠️ [Retry] Context is NULL, resetting to idle")
            resetPayment()
            return
        }

        if (!context.isValid()) {
            Timber.w("⚠️ [Retry] Context is INVALID")
            Timber.w("   - amount: ${context.amount} (valid: ${context.amount.toBigDecimalOrNull()?.let { it > java.math.BigDecimal.ZERO }})")
            Timber.w("   - tipAmount: ${context.tipAmount}")
            Timber.w("   - rating: ${context.rating}")
            Timber.w("   - merchantAccountId: '${context.merchantAccountId}' (blank: ${context.merchantAccountId.isBlank()})")
            resetPayment()
            return
        }

        // Restore context to ViewModel state
        currentAmount = context.amount
        currentTip = context.tipAmount
        currentRating = context.rating

        // Restore merchant selection
        val merchant = _merchants.value.firstOrNull { it.id == context.merchantAccountId }
        if (merchant != null) {
            _currentMerchant.value = merchant
        }

        Timber.i("🔄 [Smart Retry] Restored context | amount=${context.amount} | tip=${context.tipAmount} | rating=${context.rating} | merchant=${context.merchantAccountId}")

        // ✅ Go directly to payment processing (skip amount/tip/rating steps)
        startPayment(context.amount)
    }

    /**
     * Navigate back one step in the payment flow.
     *
     * **Flow:**
     * - EnteringAmount → NO back (return false, caller should navigate to home)
     * - CollectingRating → EnteringAmount(preserve amount)
     * - CollectingTip → CollectingRating(preserve amount + rating)
     * - SelectingMerchant → CollectingTip(preserve amount + rating + tip)
     * - Processing states → NO back (return false, transaction in progress)
     *
     * **Returns:** true if handled, false if caller should handle navigation (e.g., go to home)
     */
    fun goBackOneStep(): Boolean {
        val currentState = _state.value

        return when (currentState) {
            is PaymentState.EnteringAmount -> {
                // First step - caller should navigate to home
                Timber.d("⬅️  [Payment Flow] Back from EnteringAmount → Return to home")
                false
            }

            is PaymentState.CollectingRating -> {
                // Go back to amount input
                Timber.d("⬅️  [Payment Flow] Back from CollectingRating → EnteringAmount")
                _state.value = PaymentState.EnteringAmount(amount = currentState.amount)
                true
            }

            is PaymentState.CollectingTip -> {
                // Go back to rating
                Timber.d("⬅️  [Payment Flow] Back from CollectingTip → CollectingRating")
                _state.value = PaymentState.CollectingRating(
                    amount = currentState.amount,
                    rating = currentState.rating ?: 0
                )
                true
            }

            is PaymentState.SelectingMerchant -> {
                // Go back to tip
                Timber.d("⬅️  [Payment Flow] Back from SelectingMerchant → CollectingTip")
                _state.value = PaymentState.CollectingTip(
                    amount = currentState.subtotal,
                    rating = currentState.rating,
                    tipAmount = currentState.tipAmount
                )
                true
            }

            // During payment processing - no back allowed
            is PaymentState.ConfiguringKernel,
            is PaymentState.DetectingCard,
            is PaymentState.Processing -> {
                Timber.w("⚠️  [Payment Flow] Back not allowed during payment processing")
                false
            }

            // Final states - no back
            is PaymentState.Success,
            is PaymentState.Error,
            is PaymentState.Cancelled,
            is PaymentState.Idle,
            // 🆕 NEW: Printing states - no back
            is PaymentState.Printing,
            is PaymentState.PrintError -> {
                Timber.d("⬅️  [Payment Flow] Back from final state → Return to home")
                false
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EMV TLV HELPER FUNCTIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Helper function to validate and format EMV tag in TLV format
     * TLV = Tag + Length + Value
     *
     * ⚠️ NOTE: Blumon SDK's GetEmvTagUseCase response values likely ALREADY include
     * the LENGTH field. This function just prepends the TAG.
     *
     * @param tag Tag identifier (e.g., "9F26", "57", "5A")
     * @param value Hex string value (should already include length prefix from SDK)
     * @return Properly formatted TLV string, or null if value is invalid
     */
    private fun formatTLV(tag: String, value: String?): String? {
        if (value.isNullOrEmpty()) return null

        return try {
            // Blumon SDK responses typically return value WITH length already included
            // So we just prepend the tag
            val tlv = "$tag$value"
            Timber.v("   📦 TLV formatted: Tag=$tag, Value=${value.take(20)}... → $tlv")
            tlv
        } catch (e: Exception) {
            Timber.e(e, "Failed to format TLV for tag $tag")
            null
        }
    }

    /**
     * Helper function to calculate hex length for TLV format
     * @param hexValue Hex string (without length prefix)
     * @return 2-character hex length string (e.g., "08" for 8 bytes)
     */
    private fun calculateHexLength(hexValue: String): String {
        val byteLength = hexValue.length / 2  // Each byte is 2 hex chars
        return byteLength.toString(16).padStart(2, '0').uppercase()
    }

    /**
     * Validate if a hex string already has TLV format (Tag-Length-Value)
     * This checks if the first 2 chars represent a valid length
     * @return true if appears to have valid TLV structure
     */
    private fun valueHasLengthPrefix(hexValue: String): Boolean {
        if (hexValue.length < 4) return false  // Minimum: 2 char length + 2 char value

        return try {
            // Extract potential length byte (first 2 characters)
            val lengthHex = hexValue.substring(0, 2)
            val declaredLength = lengthHex.toInt(16) * 2  // Convert to hex char count

            // Check if declared length matches actual remaining string length
            val actualValueLength = hexValue.length - 2
            actualValueLength >= declaredLength

        } catch (e: Exception) {
            false
        }
    }

    /**
     * Add tag to EMV tag list if value exists
     * This is a convenience function that handles null checking
     */
    private fun StringBuilder.appendTag(tag: String, value: String?) {
        formatTLV(tag, value)?.let { append(it) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ⭐ NEW: BACKEND PAYMENT RECORDING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Handle successful payment and record to backend.
     *
     * This function is called after Blumon SDK approves the payment.
     * It runs in background to avoid blocking the UI.
     *
     * **Flow:**
     * 1. Show success to user immediately (already done by caller)
     * 2. Extract card details from Blumon SDK response (binInformation)
     * 3. Build PaymentContext (FastPayment for now)
     * 4. Call RecordPaymentUseCase
     * 5. Handle response (success → receipt ready, error → retry/queue)
     *
     * **Important:** This function does NOT block the success state.
     * Even if backend call fails, the payment was already approved by Blumon.
     *
     * @param saleData Complete sale data from Blumon SDK (includes binInformation)
     * @param entryMode How the card was read (CHIP, CONTACTLESS, SWIPE)
     */
    private fun handlePaymentSuccess(
        saleData: Any, // Blumon SDK SaleData object (type from com.example.clean_lib_services)
        entryMode: CardEntryMode,
    ) {
        viewModelScope.launch {
            try {
                // Extract authorization and reference using reflection (SDK type not directly accessible)
                val authorizationNumber = try {
                    val authField = saleData::class.java.getDeclaredField("authorization")
                    authField.isAccessible = true
                    authField.get(saleData)?.toString() ?: ""
                } catch (e: Exception) {
                    Timber.w("Could not extract authorization from saleData: ${e.message}")
                    ""
                }

                val referenceNumber = try {
                    val refField = saleData::class.java.getDeclaredField("reference")
                    refField.isAccessible = true
                    refField.get(saleData)?.toString() ?: ""
                } catch (e: Exception) {
                    Timber.w("Could not extract reference from saleData: ${e.message}")
                    ""
                }

                Timber.d("💾 [Backend Recording] Starting payment record | auth=$authorizationNumber | ref=$referenceNumber")

                // ⚠️ CRITICAL: Validate authentication before backend recording
                // Payment already succeeded with Blumon SDK, so we don't block the user
                // But backend recording requires auth token + staffId
                val hasAuth = authRepository.isAuthenticated()
                val hasStaffId = currentStaffId.isNotBlank()
                val hasVenueId = currentVenueId.isNotBlank()

                if (!hasAuth || !hasStaffId || !hasVenueId) {
                    Timber.w("⚠️ [Backend Recording] SKIPPED - Missing authentication context")
                    Timber.w("   → hasAuth: $hasAuth | staffId: ${if (hasStaffId) "✓" else "✗"} | venueId: ${if (hasVenueId) "✓" else "✗"}")
                    Timber.w("   → Payment succeeded with Blumon, but backend sync requires login")
                    Timber.w("   → SOLUTION: User must log in with PIN before processing payments")
                    Timber.w("   → TODO: Queue payment for offline sync when user logs in")
                    Timber.w("   → Payment details: auth=$authorizationNumber | ref=$referenceNumber | amount=$currentAmount")
                    // Payment still shows success to user (Blumon approved it)
                    return@launch
                }

                // 1. Extract card details from Blumon SDK response (includes real card brand from binInformation)
                val cardDetails = extractCardDetailsFromBlumonResponse(
                    saleData = saleData,
                    entryMode = entryMode
                )

                Timber.d("💾 [Backend Recording] Card details: brand=${cardDetails.cardBrand} | masked=${cardDetails.maskedPan} | entry=${cardDetails.entryMode}")

                // 2. Build payment context (FastPayment for now)
                // ⭐ PROVIDER-AGNOSTIC MERCHANT TRACKING: Use merchant account ID (primary)
                val merchantAccountId = _currentMerchant.value?.id ?: ""
                val blumonSerial = _currentMerchant.value?.serialNumber ?: "" // ✅ CRITICAL FIX: Use VIRTUAL serial (e.g., "2841548417"), not physical terminal serial
                val context = PaymentContext.FastPayment(
                    venueId = currentVenueId,
                    staffId = currentStaffId,
                    amount = currentAmount.toBigDecimal(),
                    tip = currentTip.toBigDecimal(),
                    rating = currentRating, // 🆕 NEW: Include user rating (1-5 stars or null)
                    merchantAccountId = merchantAccountId, // 🆕 PRIMARY: Merchant account ID
                    blumonSerialNumber = blumonSerial, // ⚠️ LEGACY: Fallback (FIXED: virtual serial)
                )

                Timber.d("💾 [Backend Recording] Context: venue=$currentVenueId, staff=$currentStaffId, amount=$currentAmount, tip=$currentTip, rating=$currentRating, merchantId=${context.merchantAccountId}, blumonSerial=${context.blumonSerialNumber}")

                // 3. Call use case to record payment
                val result = recordPaymentUseCase(
                    context = context,
                    cardDetails = cardDetails,
                    authorizationNumber = authorizationNumber,
                    referenceNumber = referenceNumber,
                )

                // 4. Handle result
                result.onSuccess { receipt ->
                    Timber.i("✅ [Backend Recording] Payment recorded successfully | paymentId=${receipt.paymentId}")
                    Timber.i("📄 [Backend Recording] Receipt URL: ${receipt.receiptUrl}")

                    // 🆕 NEW: Update Success state with receipt + card details for QR code display and printing
                    val currentState = _state.value
                    if (currentState is PaymentState.Success) {
                        _state.value = currentState.copy(
                            receipt = receipt,
                            cardDetails = cardDetails,  // 🎫 Include card info for professional receipts
                            referenceNumber = referenceNumber  // 🎫 Include reference for receipts
                        )
                        Timber.d("🎫 [Receipt] Updated Success state with receipt | URL=${receipt.receiptUrl}")
                        Timber.d("🎫 [Receipt] Card: ${cardDetails.cardBrand} ${cardDetails.maskedPan} | Entry: ${cardDetails.entryMode}")

                        // 🐛 DEBUG: Verify the state was actually updated
                        val updatedState = _state.value
                        if (updatedState is PaymentState.Success) {
                            Timber.d("🐛 [DEBUG] Confirmed state update | receipt is ${if (updatedState.receipt != null) "NOT NULL" else "NULL"}")
                        }
                    }
                }.onFailure { error ->
                    Timber.e("❌ [Backend Recording] Failed to record payment: ${error.message}")

                    // ⭐ Queue payment for offline sync
                    Timber.w("💾 [Offline Queue] Queueing payment for retry | ref=$referenceNumber")

                    val queuedPayment = com.jaac.avoqado_tpv.features.payment.domain.model.QueuedPayment(
                        queueId = 0, // Auto-generate
                        referenceNumber = referenceNumber,
                        venueId = currentVenueId,
                        staffId = currentStaffId,
                        amount = currentAmount.toBigDecimal(),
                        tip = currentTip.toBigDecimal(),
                        rating = currentRating, // 🆕 NEW: Preserve user rating for offline queue retry
                        merchantAccountId = _currentMerchant.value?.id ?: "", // 🆕 PRIMARY: Merchant account ID
                        blumonSerialNumber = _currentMerchant.value?.serialNumber ?: "", // ✅ CRITICAL FIX: Use VIRTUAL serial (e.g., "2841548417"), not physical terminal serial
                        maskedPan = cardDetails.maskedPan,
                        cardBrand = cardDetails.cardBrand.name,
                        entryMode = cardDetails.entryMode.name,
                        isInternational = cardDetails.isInternational,
                        authorizationNumber = authorizationNumber,
                        createdAt = System.currentTimeMillis(),
                        retryCount = 0,
                        lastError = error.message,
                        syncStatus = com.jaac.avoqado_tpv.features.payment.domain.model.SyncStatus.PENDING
                    )

                    viewModelScope.launch {
                        val queueResult = paymentQueueRepository.enqueue(queuedPayment)
                        queueResult.onSuccess {
                            Timber.i("✅ [Offline Queue] Payment queued successfully | ref=$referenceNumber")
                            Timber.i("   → PaymentSyncWorker will retry every 15 minutes")
                            Timber.i("   → Payment will sync automatically when network is available")
                        }.onFailure { queueError ->
                            Timber.e(queueError, "❌ [Offline Queue] Failed to queue payment - CRITICAL")
                            Timber.e("   → Payment succeeded with Blumon but NOT recorded to backend")
                            Timber.e("   → Manual intervention may be required")
                        }
                    }
                }

            } catch (e: Exception) {
                Timber.e(e, "❌ [Backend Recording] Unexpected error")
            }
        }
    }

    /**
     * Extract CardDetails from Blumon SDK response.
     *
     * ⭐ NEW: Extracts card brand from binInformation.brand instead of Track2 BIN detection.
     * This provides accurate card brand (MASTERCARD, VISA, etc.) from the issuer.
     *
     * @param saleData Complete sale data from Blumon SDK
     * @param entryMode How the card was read (CHIP, CONTACTLESS, SWIPE)
     * @return CardDetails with real card brand from binInformation
     */
    private fun extractCardDetailsFromBlumonResponse(
        saleData: Any, // Blumon SDK SaleData object
        entryMode: CardEntryMode,
    ): CardDetails {
        return try {
            // Extract binInformation from Blumon response (available via reflection)
            val saleDataClass = saleData.javaClass
            val binInfoField = saleDataClass.getDeclaredField("binInformation")
            binInfoField.isAccessible = true
            val binInfo = binInfoField.get(saleData)

            var cardBrand = CardBrand.UNKNOWN
            var bin = ""
            var bank = ""

            if (binInfo != null) {
                val binInfoClass = binInfo::class.java

                // Extract brand from binInformation
                try {
                    val brandField = binInfoClass.getDeclaredField("brand")
                    brandField.isAccessible = true
                    val brandStr = brandField.get(binInfo)?.toString() ?: ""

                    // Map Blumon brand to our CardBrand enum
                    cardBrand = when (brandStr.uppercase()) {
                        "VISA" -> CardBrand.VISA
                        "MASTERCARD" -> CardBrand.MASTERCARD
                        "AMERICAN EXPRESS", "AMEX" -> CardBrand.AMEX
                        "DISCOVER" -> CardBrand.DISCOVER
                        "DINERS CLUB", "DINERS" -> CardBrand.DINERS
                        "JCB" -> CardBrand.JCB
                        else -> CardBrand.UNKNOWN
                    }

                    Timber.d("🎯 [BinInformation] Extracted real card brand: $brandStr → $cardBrand")
                } catch (e: Exception) {
                    Timber.w("Could not extract brand from binInformation: ${e.message}")
                }

                // Extract BIN
                try {
                    val binField = binInfoClass.getDeclaredField("bin")
                    binField.isAccessible = true
                    bin = binField.get(binInfo)?.toString() ?: ""
                    Timber.d("🎯 [BinInformation] Extracted BIN: $bin")
                } catch (e: Exception) {
                    Timber.w("Could not extract bin from binInformation: ${e.message}")
                }

                // Extract bank
                try {
                    val bankField = binInfoClass.getDeclaredField("bank")
                    bankField.isAccessible = true
                    bank = bankField.get(binInfo)?.toString() ?: ""
                    Timber.d("🎯 [BinInformation] Extracted bank: $bank")
                } catch (e: Exception) {
                    Timber.w("Could not extract bank from binInformation: ${e.message}")
                }
            }

            // Mask PAN from Track2 or BIN
            val maskedPan = if (currentTrack2.isNotEmpty()) {
                val panEndIndex = currentTrack2.indexOf('=')
                val pan = if (panEndIndex > 0) currentTrack2.substring(0, panEndIndex) else currentTrack2
                maskPan(pan)
            } else if (bin.isNotEmpty()) {
                "$bin******XXXX" // Use BIN with masked suffix
            } else {
                "************"
            }

            CardDetails(
                maskedPan = maskedPan,
                cardBrand = cardBrand,
                entryMode = entryMode,
                isInternational = false, // TODO: Determine from binInformation if available
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to extract card details from Blumon response - falling back to Track2 detection")
            // Fallback to old Track2 extraction method
            extractCardDetailsFromTrack2(
                track2 = currentTrack2,
                entryMode = entryMode
            )
        }
    }

    /**
     * Extract CardDetails from Track2 data.
     *
     * Track2 format: PAN=Separator=Expiry=ServiceCode=Discretionary
     * Example: 4111111111111111=2512101...
     *
     * @param track2 Track2 data from chip/contactless (EMV tag 0x57)
     * @param entryMode How the card was read (CHIP, CONTACTLESS, SWIPE)
     * @return CardDetails with masked PAN, brand, entry mode
     */
    private fun extractCardDetailsFromTrack2(
        track2: String,
        entryMode: CardEntryMode,
    ): CardDetails {
        return try {
            // Extract PAN (before '=' separator)
            val panEndIndex = track2.indexOf('=')
            val pan = if (panEndIndex > 0) track2.substring(0, panEndIndex) else track2

            // Mask PAN (show first 6 and last 4 digits)
            val maskedPan = maskPan(pan)

            // Detect card brand from BIN (first 6 digits)
            val cardBrand = detectCardBrand(pan)

            // Check if international (TODO: Implement BIN database lookup)
            val isInternational = false  // For now, assume domestic

            CardDetails(
                maskedPan = maskedPan,
                cardBrand = cardBrand,
                entryMode = entryMode,
                isInternational = isInternational,
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to extract card details from Track2")
            // Return safe default
            CardDetails(
                maskedPan = "************",
                cardBrand = CardBrand.UNKNOWN,
                entryMode = entryMode,
                isInternational = false,
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RECEIPT PRINTING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 🖨️ Print physical receipt using PAX thermal printer.
     *
     * **Flow:**
     * 1. Get current Success state with receipt URL
     * 2. Call PrinterManager to print
     * 3. Update state to Printing (loading indicator)
     * 4. On success: Return to Success state
     * 5. On failure: Show PrintError state with retry option
     *
     * **Called from:** PaymentSuccessContent when user taps "Imprimir Recibo" button
     *
     * **Requirements:**
     * - Current state must be PaymentState.Success
     * - Receipt must be available (receipt URL not null)
     * - PAX printer must be available
     */
    fun printReceipt() {
        val currentState = _state.value
        if (currentState !is PaymentState.Success) {
            Timber.w("⚠️ [Print] Cannot print: Not in Success state")
            return
        }

        if (currentState.receipt == null) {
            Timber.w("⚠️ [Print] Cannot print: No receipt available")
            return
        }

        viewModelScope.launch {
            try {
                Timber.i("🖨️ [Print] Starting receipt print")
                _state.value = PaymentState.Printing

                val result = printerManager.printReceipt(
                    receiptUrl = currentState.receipt.receiptUrl,
                    amount = currentState.amount,
                    authCode = currentState.authCode,
                    tipAmount = currentState.tipAmount,
                    cardDetails = currentState.cardDetails,  // 🎫 Pass card info for professional receipt
                    referenceNumber = currentState.referenceNumber  // 🎫 Pass reference for receipt
                )

                result.onSuccess {
                    Timber.i("✅ [Print] Receipt printed successfully")
                    // Return to Success state
                    _state.value = currentState
                }.onFailure { error ->
                    Timber.e(error, "❌ [Print] Failed to print receipt")
                    _state.value = PaymentState.PrintError(
                        message = error.message ?: "Error al imprimir recibo",
                        previousState = currentState
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ [Print] Unexpected error during print")
                _state.value = PaymentState.PrintError(
                    message = "Error inesperado al imprimir: ${e.message}",
                    previousState = currentState
                )
            }
        }
    }

    /**
     * Dismiss print error and return to success screen.
     *
     * **Called from:** Error dialog in PaymentScreen when user taps "Cerrar"
     */
    fun dismissPrintError() {
        val currentState = _state.value
        if (currentState is PaymentState.PrintError) {
            _state.value = currentState.previousState
            Timber.d("🔙 [Print] Dismissed print error, returned to Success state")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CARD DETAIL HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Mask PAN (Primary Account Number) for security.
     *
     * Format: First 6 digits + ******* + Last 4 digits
     * Example: 4111111111111111 → 411111******1111
     *
     * @param pan Full PAN (16 digits typically)
     * @return Masked PAN safe to log/store
     */
    private fun maskPan(pan: String): String {
        return when {
            pan.length < 10 -> "************"  // Too short, fully mask
            pan.length <= 16 -> {
                val first6 = pan.substring(0, 6)
                val last4 = pan.substring(pan.length - 4)
                val maskedCount = pan.length - 10
                "$first6${"*".repeat(maskedCount)}$last4"
            }
            else -> {
                // Very long PAN (Amex can be 15 digits)
                val first6 = pan.substring(0, 6)
                val last4 = pan.substring(pan.length - 4)
                "$first6******$last4"
            }
        }
    }

    /**
     * Detect card brand from BIN (Bank Identification Number).
     *
     * BIN = First 6 digits of PAN
     *
     * **Ranges:**
     * - VISA: 4xxxxx
     * - MASTERCARD: 51-55xxxx, 222100-272099xxxx
     * - AMEX: 34xxxx, 37xxxx
     * - DISCOVER: 6011xx, 622126-622925xxxx, 644-649xxx, 65xxxx
     *
     * @param pan Full PAN
     * @return CardBrand enum
     */
    private fun detectCardBrand(pan: String): CardBrand {
        if (pan.length < 4) return CardBrand.UNKNOWN

        return try {
            when {
                pan.startsWith("4") -> CardBrand.VISA
                pan.substring(0, 2).toInt() in 51..55 -> CardBrand.MASTERCARD
                pan.length >= 6 && pan.substring(0, 6).toInt() in 222100..272099 -> CardBrand.MASTERCARD
                pan.startsWith("34") || pan.startsWith("37") -> CardBrand.AMEX
                pan.startsWith("6011") -> CardBrand.DISCOVER
                pan.length >= 6 && pan.substring(0, 6).toInt() in 622126..622925 -> CardBrand.DISCOVER
                pan.length >= 3 && pan.substring(0, 3).toInt() in 644..649 -> CardBrand.DISCOVER
                pan.startsWith("65") -> CardBrand.DISCOVER
                else -> CardBrand.UNKNOWN
            }
        } catch (e: Exception) {
            Timber.w("Failed to detect card brand from PAN: ${e.message}")
            CardBrand.UNKNOWN
        }
    }
}

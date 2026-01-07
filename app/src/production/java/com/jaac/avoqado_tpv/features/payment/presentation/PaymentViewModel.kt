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
import com.blumonpay.pax.shared.trans_process.domain.use_case.strat_emv_trans.StartEmvTransFailure
// Contactless (NFC) payment processing
import com.blumonpay.pax.shared.trans_process.domain.use_case.start_ctlss_trans.StartCtlssTransUseCase
import com.blumonpay.pax.shared.trans_process.domain.use_case.start_ctlss_trans.StartCtlssTransParams
import com.blumonpay.pax.shared_tools.manager.CountryConstants
import com.example.clean_lib_services.shared.core.domain.entity.sale_data.AuthenticationCard
import com.example.clean_lib_services.shared.core.domain.entity.sale_data.CipherType
import com.example.clean_lib_services.shared.core.domain.use_case.sale_package.sale_icc.SaleIccParams
import com.example.clean_lib_services.shared.core.domain.use_case.sale_package.sale_icc.SaleIccResponse
import com.example.clean_lib_services.shared.core.domain.use_case.sale_package.sale_icc.SaleIccUseCase
// 💸 CancelIccUseCase - For REFUND transactions (returns money to customer)
// NOTE: We use CancelIcc (NOT ReverseIcc) because:
// - ReverseIcc reads operationID from pending transactions table (not useful for completed payments)
// - CancelIcc accepts operationID parameter for canceling COMPLETED transactions
// - CancelIcc is what we need for refunding payments that have already been settled
import com.example.clean_lib_services.shared.core.domain.use_case.cancel_package.cancel_icc.CancelIccParams
import com.example.clean_lib_services.shared.core.domain.use_case.cancel_package.cancel_icc.CancelIccResponse
import com.example.clean_lib_services.shared.core.domain.use_case.cancel_package.cancel_icc.CancelIccUseCase
import com.example.clean_lib_services.shared.core.domain.entity.cancel_data.AuthenticationCardCancel
import com.example.clean_lib_services.shared.core.domain.entity.cancel_data.CipherTypeCancel
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
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.features.payment.domain.PaymentState
import com.jaac.avoqado_tpv.features.payment.domain.ScannedProduct
import com.jaac.avoqado_tpv.features.payment.domain.RetryContext
import com.jaac.avoqado_tpv.features.payment.domain.VerificationPhoto
import com.jaac.avoqado_tpv.features.payment.domain.PhotoUploadStatus
import com.jaac.avoqado_tpv.core.data.firebase.VerificationUploadManager
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
// 🔌 Socket.IO Events
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
// 👤 Customer Search (for email receipt dialog)
import com.jaac.avoqado_tpv.features.ordering.domain.Customer
import com.jaac.avoqado_tpv.features.ordering.domain.CustomerRepository
import com.jaac.avoqado_tpv.features.ordering.domain.CustomerSearchState
// ⭐ NEW: Backend payment recording
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordPaymentUseCase
// 💸 Backend Refund Recording - Record refunds to avoqado-server database
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordRefundUseCase
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt
import com.jaac.avoqado_tpv.features.payment.domain.model.CardDetails
import com.jaac.avoqado_tpv.features.payment.domain.model.CardBrand
import com.jaac.avoqado_tpv.features.payment.domain.model.CardEntryMode
import com.jaac.avoqado_tpv.features.payment.domain.model.SplitType
import com.pax.dal.entity.EReaderType
import com.paxsz.module.emv.process.enums.TransResultEnum
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    // 💸 CancelIccUseCase for REFUND transactions - provided by lib-services Hilt modules
    // NOTE: We use CancelIcc (NOT ReverseIcc) because:
    // - ReverseIcc reads operationID from pending transactions table (not useful for completed payments)
    // - CancelIcc accepts operationID parameter for canceling COMPLETED transactions
    // - CancelIcc is what we need for refunding payments that have already been settled
    private val cancelIccUseCase: CancelIccUseCase,
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
    // 💸 Backend Refund Recording - Record refunds to avoqado-server database
    private val recordRefundUseCase: RecordRefundUseCase,
    // 🔐 Auth Repository - Get current venue and staff context
    private val authRepository: com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository,
    // 💾 Payment Queue Repository - Offline payment queue for failed backend recordings
    private val paymentQueueRepository: com.jaac.avoqado_tpv.features.payment.domain.repository.PaymentQueueRepository,
    // 🖨️ Printer Manager - PAX thermal printer for receipt printing
    private val printerManager: com.jaac.avoqado_tpv.core.printer.PrinterManager,
    // 🔌 Socket Manager - Real-time Socket.IO events (payment updates, system alerts)
    private val socketManager: com.jaac.avoqado_tpv.core.data.realtime.SocketManager,
    // 🕐 Shift Repository - Validate shift is open before processing payments (Square/Toast pattern)
    private val shiftRepository: com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository,
    // 📦 Order Repository - Load order items for displaying in success screen receipt
    private val orderRepository: com.jaac.avoqado_tpv.features.ordering.domain.OrderRepository,
    // ⭐ OrderSyncCoordinator - Local-first order sync (ensures order synced before payment)
    private val orderSyncCoordinator: com.jaac.avoqado_tpv.features.ordering.domain.OrderSyncCoordinator,
    // ⚙️ TPV Settings - Configurable payment flow screens (tip, review, receipt)
    private val tpvSettingsRepository: TpvSettingsRepository,
    // 📸 Firebase Storage - Upload verification photos before payment
    private val verificationUploadManager: VerificationUploadManager,
    // 📧 PaymentApiService - For sending receipt by email
    private val paymentApiService: com.jaac.avoqado_tpv.features.payment.data.api.PaymentApiService,
    // 👤 CustomerRepository - For searching customers in email receipt dialog
    private val customerRepository: CustomerRepository
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

    // 📸 Step 4: TPV Settings for verification screen
    private val _tpvSettings = MutableStateFlow<com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings?>(null)
    val tpvSettings: StateFlow<com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings?> = _tpvSettings.asStateFlow()

    // 🔢 PIN Entry feedback - asterisks from SDK ("", "*", "**", "***", "****")
    // READ-ONLY: Only observes what SDK emits, does NOT affect payment flow
    private val _pinEntryState = MutableStateFlow("")
    val pinEntryState: StateFlow<String> = _pinEntryState.asStateFlow()

    // 🔢 PIN Dialog visibility - true when SDK needs PIN input
    // Used to keep "Ingrese su PIN" visible even when asterisks are cleared
    private val _isPinDialogVisible = MutableStateFlow(false)
    val isPinDialogVisible: StateFlow<Boolean> = _isPinDialogVisible.asStateFlow()

    // 📧 Send receipt by email loading state
    private val _isSendingReceipt = MutableStateFlow(false)
    val isSendingReceipt: StateFlow<Boolean> = _isSendingReceipt.asStateFlow()

    // 📧 Send receipt result message (for toast/snackbar display)
    private val _sendReceiptMessage = MutableStateFlow<String?>(null)
    val sendReceiptMessage: StateFlow<String?> = _sendReceiptMessage.asStateFlow()

    // 👤 Customer search for email receipt dialog
    private val _customerSearchState = MutableStateFlow<CustomerSearchState>(CustomerSearchState.Idle)
    val customerSearchState: StateFlow<CustomerSearchState> = _customerSearchState.asStateFlow()

    private val _recentCustomers = MutableStateFlow<List<Customer>>(emptyList())
    val recentCustomers: StateFlow<List<Customer>> = _recentCustomers.asStateFlow()

    private val _isLoadingRecentCustomers = MutableStateFlow(false)
    val isLoadingRecentCustomers: StateFlow<Boolean> = _isLoadingRecentCustomers.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════════════

    private var currentAmount: String = ""  // Amount in decimal format (e.g., "30.00") for UI display
    private var currentAmountInCents: String = ""  // Amount in cents (e.g., "3000") for SDK calls
    private var currentTip: String = "0.00"  // Tip amount in decimal format (e.g., "5.00")
    private var currentTrack2: String = ""  // Track2 data extracted from chip/contactless
    private var lastEmvIssuerCountry: String = ""  // 🌍 EMV tag 5F28 (Issuer Country Code) for international detection
    private var currentRating: Int? = null  // Optional rating from user (1-5 stars)

    // ⭐ NEW: Payment context data for backend recording
    private var currentVenueId: String = ""  // Venue ID from auth context
    private var currentStaffId: String = ""  // Staff ID from auth context
    private var currentShiftId: String? = null  // Shift ID from current open shift (null if no shift)

    // 🆕 Order context (for order payment with inventory deduction)
    private var currentOrderId: String? = null  // Order ID (null = fast payment, non-null = order payment)
    private var currentOrderNumber: String? = null  // Order number (for display in receipt)

    // ⭐ Split payment params (from SplitByPersonScreen or SplitByProductScreen)
    private var currentSplitType: String? = null  // EQUALPARTS, PERPRODUCT, CUSTOMAMOUNT, FULLPAYMENT
    private var currentEqualPartsPartySize: Int? = null  // Total people for EQUALPARTS mode
    private var currentEqualPartsPayedFor: Int? = null  // How many parts being paid now
    private var currentPaidProductIds: List<String> = emptyList()  // Product IDs for PERPRODUCT mode

    // ⚡ Performance optimization flags (1GB RAM devices)
    private var pinDialogFlowsStarted = false  // Track if PIN dialog collectors are running
    private var merchantsLoaded = false  // Track if merchants have been loaded

    // 📸 PRE-payment verification data (stored until payment is recorded)
    private var prePaymentVerificationPhotos: List<String> = emptyList()
    private var prePaymentVerificationBarcodes: List<ScannedProduct> = emptyList()

    // 🔧 FIX: Store the order reference generated for verification (ensures consistent naming)
    // For fast payments: "FAST-{timestamp}" generated ONCE when entering VerifyingPrePayment
    // For order payments: Uses existing order number (e.g., "ORD-12345")
    // This ensures photos uploaded to Firebase match the orderNumber created in backend
    private var prePaymentOrderReference: String? = null

    // 💸 REFUND SUPPORT: Track transaction type (SALE vs REFUND)
    // Default to SALE for normal payments, set to REFUND in startRefund()
    private var currentTransactionType: TransType = TransType.SALE
    // Refund context (null for sales, set for refunds)
    private var currentRefundContext: PaymentContext.RefundPayment? = null

    // 🔒 CRITICAL: Signal when merchants are fully loaded (prevents race condition)
    // Used by skipTip()/submitTip() to await merchants before reading _merchants.value
    private var merchantsLoadingDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()

    // ⚙️ TPV Settings: Expose showReceiptScreen for PaymentSuccessContent
    val showReceiptScreen: Boolean
        get() = tpvSettingsRepository.getCurrentSettings().showReceiptScreen

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
        Timber.d("🎬 [PaymentViewModel] Initialized")

        // 🔧 Blumon SDK init is now triggered after login (see LoginViewModel.initializeBlumonSDK)
        // This gives ~10-15 seconds head start before user opens payment screen
        // If SDK is not ready when user starts payment, we await it in startPayment()

        collectSocketEvents()  // 🔌 Listen to real-time Socket.IO events

        // 📸 Step 4: Load TPV settings for verification screen
        loadTpvSettings()
    }

    /**
     * 📸 Load TPV settings (used for Step 4 verification screen configuration).
     */
    private fun loadTpvSettings() {
        viewModelScope.launch {
            try {
                val settings = tpvSettingsRepository.getCurrentSettings()
                _tpvSettings.value = settings
                Timber.d("📸 [TPV Settings] Loaded | showVerificationScreen=${settings.showVerificationScreen} | requirePhoto=${settings.requireVerificationPhoto} | requireBarcode=${settings.requireVerificationBarcode}")
            } catch (e: Exception) {
                Timber.e(e, "📸 [TPV Settings] Failed to load settings")
            }
        }
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
     *
     * ⚡ Performance: Only started when payment begins (not in init block)
     */
    private fun collectPinDialogFlows() {
        // Prevent multiple executions (performance optimization for 1GB RAM)
        if (pinDialogFlowsStarted) {
            Timber.d("📟 [PIN Dialog] Collectors already running, skipping...")
            return
        }
        pinDialogFlowsStarted = true
        Timber.d("📟 [PIN Dialog] Starting collectors...")
        // 1️⃣ PIN Dialog State - When SDK needs to show PIN pad
        viewModelScope.launch {
            transProcessRepository.getEventPinDialogStateFlow().collect { state ->
                Timber.d("📟 [PIN Dialog] State changed: $state")
                // Update visibility for UI (keeps "Ingrese su PIN" visible even when cleared)
                _isPinDialogVisible.value = state.show && !state.dismiss
            }
        }

        // 2️⃣ Keyboard PIN State - Physical keyboard status (asterisks feedback)
        viewModelScope.launch {
            transProcessRepository.getKeyboardPinStateFlow().collect { pinState ->
                Timber.d("⌨️  [PIN Keyboard] State: $pinState")
                // Expose asterisks to UI for visual feedback (READ-ONLY, does not affect payment)
                _pinEntryState.value = pinState
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
     * 🔌 Collect Socket.IO Real-time Events
     *
     * Listen to payment-related events from other terminals/systems:
     * - payment_initiated: Another terminal started processing payment
     * - payment_processing: Payment is being authorized
     * - payment_completed: Payment succeeded (update UI, show notification)
     * - payment_failed: Payment failed (show error notification)
     *
     * Use case: Multi-terminal coordination
     * Example: Terminal 1 processes payment for Table 5
     *          Terminal 2 (managing Table 5) receives payment_completed event
     *          Terminal 2 updates UI to show order is paid
     *
     * Pattern: Similar to Square Terminal multi-device synchronization
     */
    private fun collectSocketEvents() {
        viewModelScope.launch {
            socketManager.events.collect { event ->
                when (event) {
                    is SocketEvent.PaymentInitiated -> {
                        Timber.d("💳 [Socket] Payment initiated: ${event.paymentId} - Amount: ${event.amount / 100.0} ${event.currency}")
                        // Optional: Show notification that another terminal started payment
                    }

                    is SocketEvent.PaymentProcessing -> {
                        Timber.d("⏳ [Socket] Payment processing: ${event.paymentId}")
                        // Optional: Update UI to show payment in progress
                    }

                    is SocketEvent.PaymentCompleted -> {
                        Timber.i("✅ [Socket] Payment completed: ${event.paymentId} - Amount: ${event.amount / 100.0} ${event.currency}")
                        // Optional: Show success notification
                        // Could trigger order refresh if this payment is for current order
                    }

                    is SocketEvent.PaymentFailed -> {
                        Timber.w("❌ [Socket] Payment failed: ${event.paymentId}")
                        // Optional: Show error notification
                    }

                    // Ignore other events (handled by other ViewModels)
                    else -> {
                        // Other events like OrderCreated, SystemAlert, etc.
                        // will be handled by MainViewModel or OrderViewModel
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

    /**
     * ⚡ Performance: Lazy-load merchants only when needed
     * Called before showing merchant selection or auto-selecting merchant
     *
     * 🔒 THREAD-SAFE: Uses CompletableDeferred to signal completion.
     * Callers can await merchantsLoadingDeferred to ensure merchants are loaded.
     */
    private fun ensureMerchantsLoaded() {
        if (merchantsLoaded) {
            Timber.d("🏪 [Merchants] Already observing, skipping...")
            return
        }
        merchantsLoaded = true
        Timber.d("🏪 [Merchants] Starting merchant flow observation...")

        // 🏪 OBSERVE merchants continuously (NOT .first() snapshot!)
        // This fixes race condition where backend fetch completes AFTER ViewModel loads
        // When MerchantRepository.updateMerchants() is called, this collector receives update
        viewModelScope.launch {
            try {
                getMerchantsUseCase().collect { merchantList ->
                    val previousCount = _merchants.value.size
                    _merchants.value = merchantList

                    // Log changes for debugging
                    if (previousCount != merchantList.size || previousCount == 0) {
                        Timber.i("🏪 [Merchants] Updated: ${merchantList.size} accounts")
                        merchantList.forEach { merchant ->
                            Timber.d("   📦 ${merchant.displayName} (${merchant.serialNumber}, posId: ${merchant.posId}, env: ${merchant.environment})")
                        }
                    }

                    // Track current merchant from SDK manager (only on first emission)
                    if (_currentMerchant.value == null) {
                        _currentMerchant.value = multiMerchantSDKManager.getCurrentMerchant()
                        Timber.d("🏪 [Merchants] Current account: ${_currentMerchant.value?.displayName ?: "Default"}")
                    }

                    // 🔒 Signal that merchants are loaded (first emission)
                    if (!merchantsLoadingDeferred.isCompleted) {
                        merchantsLoadingDeferred.complete(Unit)
                        Timber.d("✅ [Merchants] Initial load complete - deferred signaled")
                    }
                }
            } catch (e: CancellationException) {
                // Expected when ViewModel is destroyed during navigation - rethrow per coroutines best practice
                Timber.d("🏪 [Merchants] Observation cancelled (navigation)")
                throw e
            } catch (e: Exception) {
                Timber.e(e, "❌ [Merchants] Failed to observe merchants")
                // Complete anyway to avoid blocking forever
                if (!merchantsLoadingDeferred.isCompleted) {
                    merchantsLoadingDeferred.complete(Unit)
                }
            }
        }
    }

    /**
     * Await merchants to be fully loaded (suspend function)
     */
    private suspend fun awaitMerchantsLoaded() {
        ensureMerchantsLoaded()
        merchantsLoadingDeferred.await()
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
     *
     * @param amount Payment amount (e.g., "10.00")
     * @param orderId Order ID (null = fast payment, non-null = order payment with inventory deduction)
     * @param orderNumber Order number (for display in receipt)
     * @param splitType Split type (EQUALPARTS, PERPRODUCT, CUSTOMAMOUNT, FULLPAYMENT)
     * @param equalPartsPartySize Total people for EQUALPARTS mode
     * @param equalPartsPayedFor How many parts being paid now
     * @param paidProductIds Product IDs for PERPRODUCT mode
     */
    fun submitAmount(
        amount: String,
        orderId: String? = null,
        orderNumber: String? = null,
        splitType: String? = null,
        equalPartsPartySize: Int? = null,
        equalPartsPayedFor: Int? = null,
        paidProductIds: List<String> = emptyList()
    ) {
        Timber.d("💰 [Payment Flow] Amount entered: $$amount")
        // Save order context
        currentOrderId = orderId
        currentOrderNumber = orderNumber

        // ⭐ Save split payment params
        currentSplitType = splitType
        currentEqualPartsPartySize = equalPartsPartySize
        currentEqualPartsPayedFor = equalPartsPayedFor
        currentPaidProductIds = paidProductIds

        if (orderId != null) {
            Timber.d("📦 [Payment Flow] Order payment mode: orderId=$orderId, orderNumber=$orderNumber, splitType=$splitType")
        } else {
            Timber.d("⚡ [Payment Flow] Fast payment mode (no order)")
        }

        // ⚡ Performance: Pre-load merchants early so they're ready when needed
        // This runs async but gives time for merchants to load before submitTip() needs them
        ensureMerchantsLoaded()

        // ⚙️ TPV Settings: Check which screens to show in payment flow
        val tpvSettings = tpvSettingsRepository.getCurrentSettings()
        Timber.d("⚙️ [TPV Settings] showReviewScreen=${tpvSettings.showReviewScreen}, showTipScreen=${tpvSettings.showTipScreen}")

        when {
            tpvSettings.showReviewScreen -> {
                // Show rating screen first
                _state.value = PaymentState.CollectingRating(amount = amount)
            }
            tpvSettings.showTipScreen -> {
                // Skip rating, go directly to tip screen
                Timber.d("⏭️ [Payment Flow] Skipping review screen (disabled in TPV settings)")
                val defaultTipPercentage = tpvSettings.defaultTipPercentage ?: 15
                val defaultTipAmount = calculateTipAmount(amount, defaultTipPercentage)
                _state.value = PaymentState.CollectingTip(
                    amount = amount,
                    rating = null,
                    selectedTipPercentage = defaultTipPercentage,
                    tipAmount = defaultTipAmount
                )
            }
            else -> {
                // Skip both rating and tip
                Timber.d("⏭️ [Payment Flow] Skipping review and tip screens (disabled in TPV settings)")
                currentTip = "0.00"
                currentRating = null

                // 📸 PRE-PAYMENT VERIFICATION: Check if verification is enabled BEFORE merchant selection
                if (tpvSettings.showVerificationScreen) {
                    // 🔧 FIX: Generate orderReference ONCE for consistent naming
                    val orderRef = currentOrderNumber ?: "FAST-${System.currentTimeMillis()}"
                    Timber.d("📸 [Payment Flow] PRE-payment verification enabled → Going to VerifyingPrePayment (no rating/tip)")
                    Timber.d("📸 [Payment Flow] Requirements: requirePhoto=${tpvSettings.requireVerificationPhoto}, requireBarcode=${tpvSettings.requireVerificationBarcode}, orderRef=$orderRef")
                    _state.value = PaymentState.VerifyingPrePayment(
                        amount = amount,
                        rating = null,
                        tipAmount = "0.00",
                        requirePhoto = tpvSettings.requireVerificationPhoto,
                        requireBarcode = tpvSettings.requireVerificationBarcode,
                        orderReference = orderRef
                    )
                    return
                }

                proceedToMerchantSelection(amount, "0", amount, null)
            }
        }
    }

    /**
     * Helper function to proceed to merchant selection (handles auto-skip for single merchant)
     */
    private fun proceedToMerchantSelection(subtotal: String, tipAmount: String, totalAmount: String, rating: Int?) {
        viewModelScope.launch {
            awaitMerchantsLoaded()

            val merchants = _merchants.value

            // ❌ NO MERCHANTS: Show error and block payment
            if (merchants.isEmpty()) {
                Timber.e("❌ [Payment Flow] No merchants available - cannot proceed with payment")
                _state.value = PaymentState.Error(
                    message = "No hay cuentas de pago configuradas.\n\n" +
                            "Contacta al administrador para agregar una cuenta de procesador de pagos.",
                    context = createPaymentContext()
                )
                return@launch
            }

            // Pre-select if only 1 merchant (but still show Step 3 for user confirmation)
            if (merchants.size == 1) {
                val onlyMerchant = merchants.first()
                Timber.d("🏪 [Payment Flow] Only 1 merchant (${onlyMerchant.displayName}) → Pre-selecting")
                _currentMerchant.value = onlyMerchant
            } else if (_currentMerchant.value == null && merchants.isNotEmpty()) {
                // Multiple merchants: auto-select first as default
                val defaultMerchant = merchants.first()
                Timber.d("🏪 [Payment Flow] Auto-selecting default merchant: ${defaultMerchant.displayName}")
                _currentMerchant.value = defaultMerchant
            }

            // ✅ ALWAYS show Step 3 (SelectingMerchant) - user must see summary & confirm payment method
            _state.value = PaymentState.SelectingMerchant(
                subtotal = subtotal,
                tipAmount = tipAmount,
                totalAmount = totalAmount,
                rating = rating
            )
        }
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
     * Submit rating and proceed to tip screen (or skip if disabled)
     */
    fun submitRating(amount: String, rating: Int) {
        Timber.d("⭐ [Payment Flow] Rating submitted: $rating stars")

        // ⚙️ TPV Settings: Check if tip screen should be shown
        val tpvSettings = tpvSettingsRepository.getCurrentSettings()

        if (tpvSettings.showTipScreen) {
            val defaultTipPercentage = tpvSettings.defaultTipPercentage ?: 15
            val defaultTipAmount = calculateTipAmount(amount, defaultTipPercentage)
            _state.value = PaymentState.CollectingTip(
                amount = amount,
                rating = rating,
                selectedTipPercentage = defaultTipPercentage,
                tipAmount = defaultTipAmount
            )
        } else {
            // Skip tip screen
            Timber.d("⏭️ [Payment Flow] Skipping tip screen (disabled in TPV settings)")
            currentTip = "0.00"
            currentRating = rating

            // 📸 PRE-PAYMENT VERIFICATION: Check if verification is enabled BEFORE merchant selection
            if (tpvSettings.showVerificationScreen) {
                // 🔧 FIX: Generate orderReference ONCE for consistent naming
                val orderRef = currentOrderNumber ?: "FAST-${System.currentTimeMillis()}"
                Timber.d("📸 [Payment Flow] PRE-payment verification enabled → Going to VerifyingPrePayment (from submitRating)")
                Timber.d("📸 [Payment Flow] Requirements: requirePhoto=${tpvSettings.requireVerificationPhoto}, requireBarcode=${tpvSettings.requireVerificationBarcode}, orderRef=$orderRef")
                _state.value = PaymentState.VerifyingPrePayment(
                    amount = amount,
                    rating = rating,
                    tipAmount = "0.00",
                    requirePhoto = tpvSettings.requireVerificationPhoto,
                    requireBarcode = tpvSettings.requireVerificationBarcode,
                    orderReference = orderRef
                )
                return
            }

            proceedToMerchantSelection(amount, "0", amount, rating)
        }
    }

    /**
     * Skip rating and proceed to tip screen (or skip if disabled)
     */
    fun skipRating(amount: String) {
        Timber.d("⏭️  [Payment Flow] Rating skipped")

        // ⚙️ TPV Settings: Check if tip screen should be shown
        val tpvSettings = tpvSettingsRepository.getCurrentSettings()

        if (tpvSettings.showTipScreen) {
            val defaultTipPercentage = tpvSettings.defaultTipPercentage ?: 15
            val defaultTipAmount = calculateTipAmount(amount, defaultTipPercentage)
            _state.value = PaymentState.CollectingTip(
                amount = amount,
                rating = null,
                selectedTipPercentage = defaultTipPercentage,
                tipAmount = defaultTipAmount
            )
        } else {
            // Skip tip screen
            Timber.d("⏭️ [Payment Flow] Skipping tip screen (disabled in TPV settings)")
            currentTip = "0.00"
            currentRating = null

            // 📸 PRE-PAYMENT VERIFICATION: Check if verification is enabled BEFORE merchant selection
            if (tpvSettings.showVerificationScreen) {
                // 🔧 FIX: Generate orderReference ONCE for consistent naming
                val orderRef = currentOrderNumber ?: "FAST-${System.currentTimeMillis()}"
                Timber.d("📸 [Payment Flow] PRE-payment verification enabled → Going to VerifyingPrePayment (from skipRating)")
                Timber.d("📸 [Payment Flow] Requirements: requirePhoto=${tpvSettings.requireVerificationPhoto}, requireBarcode=${tpvSettings.requireVerificationBarcode}, orderRef=$orderRef")
                _state.value = PaymentState.VerifyingPrePayment(
                    amount = amount,
                    rating = null,
                    tipAmount = "0.00",
                    requirePhoto = tpvSettings.requireVerificationPhoto,
                    requireBarcode = tpvSettings.requireVerificationBarcode,
                    orderReference = orderRef
                )
                return
            }

            proceedToMerchantSelection(amount, "0", amount, null)
        }
    }

    /**
     * ⭐ NEW: Select rating and proceed automatically to tip screen
     *
     * This function combines updateRating + submitRating to avoid state race condition
     * when auto-advancing from ReviewScreen after user selects stars.
     *
     * **Why needed:**
     * When ReviewScreen calls updateRating() + onContinue() sequentially,
     * onContinue() uses the OLD rating value before recomposition happens.
     * This function ensures the correct rating value is used when advancing.
     */
    fun selectRatingAndProceed(amount: String, rating: Int) {
        Timber.d("⭐ [Payment Flow] Rating selected and proceeding: $rating stars")
        // Call submitRating directly with the rating value (skip intermediate state update)
        submitRating(amount, rating)
    }

    /**
     * ⭐ TEST PAYMENT: Submit amount and skip directly to merchant selection
     *
     * Used for test payments from SuperAdmin screen.
     * Skips rating and tip collection, goes directly to SelectingMerchant state.
     *
     * @param amount Payment amount (e.g., "10.00")
     * @param orderId Order ID (null = fast payment, non-null = order payment with inventory deduction)
     * @param orderNumber Order number (for display in receipt)
     * @param splitType Split type (EQUALPARTS, PERPRODUCT, CUSTOMAMOUNT, FULLPAYMENT)
     * @param equalPartsPartySize Total people for EQUALPARTS mode
     * @param equalPartsPayedFor How many parts being paid now
     * @param paidProductIds Product IDs for PERPRODUCT mode
     */
    fun submitAmountDirectToMerchant(
        amount: String,
        orderId: String? = null,
        orderNumber: String? = null,
        splitType: String? = null,
        equalPartsPartySize: Int? = null,
        equalPartsPayedFor: Int? = null,
        paidProductIds: List<String> = emptyList()
    ) {
        Timber.d("🧪 [Test Payment] Skipping rating/tip, going directly to merchant selection: $$amount")

        // Save order context
        currentOrderId = orderId
        currentOrderNumber = orderNumber

        // ⭐ Save split payment params
        currentSplitType = splitType
        currentEqualPartsPartySize = equalPartsPartySize
        currentEqualPartsPayedFor = equalPartsPayedFor
        currentPaidProductIds = paidProductIds

        if (orderId != null) {
            Timber.d("📦 [Test Payment] Order payment mode: orderId=$orderId, orderNumber=$orderNumber, splitType=$splitType")
        } else {
            Timber.d("⚡ [Test Payment] Fast payment mode (no order)")
        }

        // Save zero tip and no rating for backend recording
        currentTip = "0.00"
        currentRating = null

        // 🔒 CRITICAL: Await merchants to be fully loaded before using them
        viewModelScope.launch {
            awaitMerchantsLoaded()

            // ⭐ AUTO-SKIP: If only 1 merchant, select it and skip merchant selection screen
            val merchants = _merchants.value
            // Pre-select if only 1 merchant (but still show Step 3 for user confirmation)
            if (merchants.size == 1) {
                val onlyMerchant = merchants.first()
                Timber.d("🏪 [Test Payment] Only 1 merchant (${onlyMerchant.displayName}) → Pre-selecting")
                _currentMerchant.value = onlyMerchant
            } else if (_currentMerchant.value == null && merchants.isNotEmpty()) {
                // Multiple merchants: auto-select first as default
                val defaultMerchant = merchants.first()
                Timber.d("🏪 [Test Payment] Auto-selecting default merchant: ${defaultMerchant.displayName}")
                _currentMerchant.value = defaultMerchant
            }

            // ✅ ALWAYS show Step 3 (SelectingMerchant) - user must see summary & confirm payment method
            _state.value = PaymentState.SelectingMerchant(
                subtotal = amount,
                tipAmount = "0",
                totalAmount = amount,
                rating = null
            )
        }
    }

    /**
     * Submit tip and proceed to verification (if enabled) or merchant selection
     *
     * 📸 PRE-PAYMENT VERIFICATION: If showVerificationScreen is enabled in TpvSettings,
     * route to VerifyingPrePayment state BEFORE selecting merchant/processing payment.
     */
    fun submitTip(subtotal: String, tipAmount: String, rating: Int?) {
        Timber.d("💵 [Payment Flow] submitTip called with: subtotal='$subtotal', tipAmount='$tipAmount', rating=$rating")

        val totalAmount = calculateTotal(subtotal, tipAmount)

        Timber.d("💵 [Payment Flow] Calculated total: '$totalAmount' (subtotal='$subtotal' + tip='$tipAmount')")

        // ⭐ Save tip and rating for backend recording
        currentTip = tipAmount
        currentRating = rating

        // 📸 PRE-PAYMENT VERIFICATION: Check if verification is enabled
        // ⚠️ CRITICAL: Use synchronous getCurrentSettings(), NOT async _tpvSettings.value
        // (same pattern as submitRating - _tpvSettings.value might be null due to async load)
        val settings = tpvSettingsRepository.getCurrentSettings()
        if (settings.showVerificationScreen) {
            // 🔧 FIX: Generate orderReference ONCE for consistent naming
            val orderRef = currentOrderNumber ?: "FAST-${System.currentTimeMillis()}"
            Timber.d("📸 [Payment Flow] PRE-payment verification enabled → Going to VerifyingPrePayment")
            Timber.d("📸 [Payment Flow] Requirements: requirePhoto=${settings.requireVerificationPhoto}, requireBarcode=${settings.requireVerificationBarcode}, orderRef=$orderRef")
            _state.value = PaymentState.VerifyingPrePayment(
                amount = subtotal,
                rating = rating,
                tipAmount = tipAmount,
                requirePhoto = settings.requireVerificationPhoto,
                requireBarcode = settings.requireVerificationBarcode,
                orderReference = orderRef
            )
            return
        }

        // 🔒 CRITICAL: Await merchants to be fully loaded before using them
        // This prevents race condition where _merchants.value is empty
        proceedToMerchantSelection(subtotal, tipAmount, totalAmount, rating)
    }

    /**
     * Skip tip (no tip) and proceed to verification (if enabled) or merchant selection
     *
     * 🔒 RACE CONDITION FIX: Now awaits merchants to be fully loaded before reading them.
     * Previously, ensureMerchantsLoaded() was async and _merchants.value could be empty.
     *
     * 📸 PRE-PAYMENT VERIFICATION: If showVerificationScreen is enabled, route to
     * VerifyingPrePayment state BEFORE selecting merchant/processing payment.
     */
    fun skipTip(subtotal: String, rating: Int?) {
        Timber.d("⏭️  [Payment Flow] Tip skipped")

        // ⭐ Save zero tip and rating for backend recording
        currentTip = "0.00"
        currentRating = rating

        // 📸 PRE-PAYMENT VERIFICATION: Check if verification is enabled
        // ⚠️ CRITICAL: Use synchronous getCurrentSettings(), NOT async _tpvSettings.value
        // (same pattern as submitRating - _tpvSettings.value might be null due to async load)
        val settings = tpvSettingsRepository.getCurrentSettings()
        if (settings.showVerificationScreen) {
            // 🔧 FIX: Generate orderReference ONCE for consistent naming
            val orderRef = currentOrderNumber ?: "FAST-${System.currentTimeMillis()}"
            Timber.d("📸 [Payment Flow] PRE-payment verification enabled → Going to VerifyingPrePayment (no tip)")
            Timber.d("📸 [Payment Flow] Requirements: requirePhoto=${settings.requireVerificationPhoto}, requireBarcode=${settings.requireVerificationBarcode}, orderRef=$orderRef")
            _state.value = PaymentState.VerifyingPrePayment(
                amount = subtotal,
                rating = rating,
                tipAmount = "0.00",
                requirePhoto = settings.requireVerificationPhoto,
                requireBarcode = settings.requireVerificationBarcode,
                orderReference = orderRef
            )
            return
        }

        // 🔒 CRITICAL: Await merchants to be fully loaded before using them
        // This prevents race condition where _merchants.value is empty
        proceedToMerchantSelection(subtotal, "0.00", subtotal, rating)
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
     * ⭐ NEW: Select tip percentage and proceed automatically to merchant selection
     *
     * This function combines updateTipPercentage + submitTip to avoid state race condition
     * when auto-advancing from TipScreen after user selects a percentage.
     *
     * **Why needed:**
     * When TipScreen calls updateTipPercentage() + onContinue() sequentially,
     * onContinue() uses the OLD state value before recomposition happens.
     * This function ensures the correct tip value is used when advancing.
     */
    fun selectTipPercentageAndProceed(amount: String, rating: Int?, percentage: Int) {
        val tipAmount = calculateTipAmount(amount, percentage)
        Timber.d("💵 [Payment Flow] Tip percentage selected and proceeding: $percentage% = $$tipAmount")

        // Call submitTip directly with calculated values (skip intermediate state update)
        submitTip(amount, tipAmount, rating)
    }

    /**
     * ⭐ NEW: Select custom tip and proceed automatically to merchant selection
     *
     * This function combines updateCustomTip + submitTip to avoid state race condition
     * when auto-advancing from TipScreen after user confirms custom amount.
     */
    fun selectCustomTipAndProceed(amount: String, rating: Int?, customTip: String) {
        Timber.d("💵 [Payment Flow] Custom tip selected and proceeding: $$customTip")

        // Call submitTip directly with custom value (skip intermediate state update)
        submitTip(amount, customTip, rating)
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
     * Convert decimal amount to cents for PreTrans (EMV kernel)
     *
     * PreTrans/EMV kernel expects INTEGER cents (Long.parseLong compatible).
     * - Input: "10.00" → Output: "1000" (cents)
     * - Input: "10.50" → Output: "1050" (cents)
     *
     * ⚠️ IMPORTANT: This is ONLY for PreTrans. SaleIcc uses formatAmountDecimal() instead.
     */
    private fun convertToCents(amount: String): String {
        val amountDecimal = amount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
        val cents = amountDecimal.multiply(java.math.BigDecimal(100))
            .setScale(0, java.math.RoundingMode.HALF_UP)
        return cents.toLong().toString()
    }

    /**
     * Format amount as decimal string for SaleIcc (Blumon API)
     *
     * SaleIcc/Blumon API expects DECIMAL format with 2 decimal places.
     * - Input: "10" → Output: "10.00"
     * - Input: "10.5" → Output: "10.50"
     *
     * Per Edgardo Olvera (2025-01-21): "Es float... Si tienes que poner un decimal"
     */
    private fun formatAmountDecimal(amount: String): String {
        val amountDecimal = amount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
        return String.format(java.util.Locale.US, "%.2f", amountDecimal)
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
        currentAmountInCents = convertToCents(amount)  // Save for SDK calls (cents as integer string)

        // Reset international detection state for new payment
        lastEmvIssuerCountry = ""

        // ⭐ Get venue and staff context for backend recording
        val venueId = authRepository.getVenueId()
        val staffId = authRepository.getStaffId()

        // 🔐 SESSION VALIDATION: Prevent payment if session expired
        // Bug fix: User was kicked to login MID-PAYMENT because session expired
        // Now we catch this BEFORE starting, showing a clear error
        if (venueId.isNullOrBlank() || staffId.isNullOrBlank()) {
            Timber.w("⚠️ [Payment] No session - venueId=$venueId, staffId=$staffId")
            _state.value = PaymentState.Error(
                message = "Tu sesión expiró.\n\nPor favor inicia sesión de nuevo.",
                canRetry = false
            )
            return
        }

        currentVenueId = venueId
        currentStaffId = staffId

        // ⚡ Performance: Start PIN dialog collectors only when payment begins
        collectPinDialogFlows()

        Timber.d("🎯 [BlumonPayment] Starting ONLINE chip payment flow: $$amount")
        Timber.d("   💰 Amount: $$amount → $currentAmountInCents centavos")
        Timber.d("   🏪 Venue: $currentVenueId | Staff: $currentStaffId | Tip: $$currentTip")

        // ⭐ CRITICAL: Validate shift is open before processing payment (Square/Toast pattern)
        // Without an open shift, cash reconciliation is impossible and payments can't be properly tracked
        viewModelScope.launch {
            // 🔧 CRITICAL: Await SDK initialization before proceeding with payment
            // SDK init is triggered after login (LoginViewModel.initializeBlumonSDK)
            // This ensures SDK is ready even if user navigates quickly to payment screen

            // 📺 Show loading if SDK needs initialization (prevents 7s of no feedback)
            if (!initializationManager.isInitialized.value) {
                _state.value = PaymentState.Processing("Configurando sistema de pago...")
                Timber.d("⏳ [Payment] SDK not ready - showing loading indicator")
            }

            initializationManager.awaitInitialization().onFailure { error ->
                Timber.e(error, "❌ [Payment] SDK initialization failed")
                _state.value = PaymentState.Error(
                    message = "Error inicializando sistema de pago.\n\n" +
                             "Por favor, cierra sesión e intenta nuevamente.",
                    context = null
                )
                return@launch
            }

            val currentShift = shiftRepository.getCurrentShift(currentVenueId).getOrNull()

            // ⭐ CRITICAL: Shift System Validation
            // - If enabled (default): Block payment if no shift is open
            // - If disabled (Settings): Bypass check
            if (shiftRepository.isShiftSystemEnabled()) {
                if (currentShift == null || currentShift.status != com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus.OPEN) {
                    Timber.w("⚠️ [Payment] No active shift - cannot process payment")
                    Timber.w("   → Shift validation required for cash reconciliation and audit trail")
                    Timber.w("   → Pattern: Square POS / Toast POS - always require open shift for payments")
                    _state.value = PaymentState.Error(
                        message = "No hay turno abierto.\n\n" +
                                 "Abre un turno para procesar pagos.",
                        context = null,
                        showOpenShiftButton = true  // ⭐ Show "Abrir Turno" button in dialog
                    )
                    return@launch
                }
                // Save shiftId for backend recording
                currentShiftId = currentShift.id
                Timber.d("✅ [Payment] Shift validation passed: ${currentShift.id} (${currentShift.staffName})")
            } else {
                currentShiftId = null
                Timber.i("ℹ️ [Payment] Shift system disabled - bypassing shift check")
            }

            // ⭐ P0 FIX: Merchant Account Validation (Split Payment Protection)
            // Validates that split payments use the same merchant account to avoid reconciliation issues
            if (currentOrderId != null) {
                try {
                    val order = orderSyncCoordinator.getLocalOrder(currentOrderId!!)

                    if (order == null) {
                        Timber.e("❌ [Merchant Validation] Order not found: $currentOrderId")
                        _state.value = PaymentState.Error(
                            message = "Orden no encontrada.\n\nVerifica que la orden existe e intenta nuevamente.",
                            context = null
                        )
                        return@launch
                    }

                    // Check if order has partial payment with different merchant
                    if (order.merchantAccountId != null && order.paymentStatus == com.jaac.avoqado_tpv.features.ordering.domain.PaymentStatus.PARTIAL) {
                        val currentMerchantId = _currentMerchant.value?.merchantAccountId
                        val orderMerchantId = order.merchantAccountId

                        if (currentMerchantId != orderMerchantId) {
                            Timber.w("⚠️ [Merchant Validation] Split payment merchant mismatch")
                            Timber.w("   Order merchant: $orderMerchantId (${order.merchantAccountName})")
                            Timber.w("   Current merchant: $currentMerchantId (${_currentMerchant.value?.displayName})")

                            _state.value = PaymentState.Error(
                                message = "Esta orden tiene un pago parcial con otra cuenta (${order.merchantAccountName}).\n\n" +
                                         "Debes continuar pagando con la misma cuenta para evitar problemas de reconciliación.\n\n" +
                                         "Cuenta actual: ${_currentMerchant.value?.displayName ?: "Ninguna"}\n" +
                                         "Cuenta requerida: ${order.merchantAccountName ?: "Desconocida"}",
                                context = null
                            )
                            return@launch
                        }

                        Timber.d("✅ [Merchant Validation] Split payment merchant matches: $currentMerchantId")
                    } else {
                        Timber.d("✅ [Merchant Validation] No merchant lock (first payment or full payment)")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "❌ [Merchant Validation] Error validating merchant")
                    _state.value = PaymentState.Error(
                        message = "Error validando cuenta de pago.\n\nIntenta nuevamente.",
                        context = null
                    )
                    return@launch
                }
            }

            // Continue with payment flow
            _state.value = PaymentState.ConfiguringKernel
            continuePaymentFlow()
        }
    }

    /**
     * Continue payment flow after shift validation
     *
     * Separated from startPayment() to allow coroutine launch for shift check
     */
    private fun continuePaymentFlow() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // ═══════════════════════════════════════════════════════════════════════════
                // PASO -1: Sync order to backend (LOCAL-FIRST CRITICAL STEP)
                // ═══════════════════════════════════════════════════════════════════════════

                // ⭐ CRITICAL: If this is an order payment (not fast payment), we MUST sync
                // the order to the backend BEFORE processing payment. This ensures:
                // 1. Backend has the complete order with all items
                // 2. Inventory deduction happens correctly
                // 3. Payment can be properly linked to the order
                // 4. Multi-terminal consistency (other terminals see synced order)

                if (currentOrderId != null) {
                    Timber.d("💾 [Local-First] Order payment detected - syncing to backend before payment")
                    Timber.d("   📦 Order ID: $currentOrderId")

                    _state.value = PaymentState.Processing("Sincronizando orden...")

                    try {
                        // Force immediate sync (bypass 5s debounce - payment can't wait)
                        orderSyncCoordinator.syncOrderImmediately(currentOrderId!!)

                        Timber.i("✅ [Local-First] Order synced successfully before payment")
                    } catch (e: Exception) {
                        Timber.e(e, "❌ [Local-First] Failed to sync order before payment")
                        _state.value = PaymentState.Error(
                            message = "Error sincronizando orden antes del pago.\n\n" +
                                     "La orden se guardó localmente pero no se pudo enviar al servidor.\n" +
                                     "Verifique su conexión e intente nuevamente.",
                            context = createPaymentContext()
                        )
                        return@launch
                    }
                } else {
                    Timber.d("⚡ [Local-First] Fast payment mode - no order sync needed")
                }

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
                // 💸 REFUND SUPPORT: Use currentTransactionType (SALE or REFUND) instead of hardcoded SALE
                // 💰 TIP FIX: Pass tip in cents to Blumon SDK (was hardcoded to "0")
                val tipInCents = convertToCents(currentTip)
                Timber.d("💰 [PreTrans] Amount: $currentAmountInCents cents | Tip: $tipInCents cents (from currentTip=$currentTip)")
                val preParams = PreTransParams(currentAmountInCents, tipInCents, currentTransactionType, CountryConstants.MEX)
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
                    // Convert SDK error class to user-friendly message
                    val friendlyMessage = when (error) {
                        is StartEmvTransFailure.WithdrawnCardFailure -> "Tarjeta retirada antes de tiempo. Por favor, mantenga la tarjeta insertada hasta que se complete la operación."
                        is StartEmvTransFailure.TimeoutFailure -> "Tiempo de espera agotado. Por favor, intente de nuevo."
                        is StartEmvTransFailure.CancelOperationFailure -> "Operación cancelada."
                        is StartEmvTransFailure.DetectChipFailure -> "Error al leer el chip. Por favor, limpie el chip e intente de nuevo."
                        is StartEmvTransFailure.CardDeclineByEmvFailure -> "Tarjeta rechazada por el chip. Por favor, use otra tarjeta."
                        is StartEmvTransFailure.EmvIncompleteFailure -> "Lectura de tarjeta incompleta. Por favor, intente de nuevo."
                        is StartEmvTransFailure.EMVFailure -> "Error al procesar la tarjeta. Por favor, intente de nuevo."
                        else -> "Error procesando tarjeta. Por favor, intente de nuevo."
                    }
                    _state.value = PaymentState.Error(
                        message = friendlyMessage,
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
                        0x50,    // Application Label ← ADDED per Edgardo
                        0x5F28   // 🌍 Issuer Country Code (ISO 3166 numeric, e.g., "484" = Mexico)
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

                // 🌍 Extract Issuer Country Code (EMV tag 0x5F28) for international card detection
                try {
                    val countryParams = GetTagValueParams(
                        tag = 0x5F28,  // Issuer Country Code (ISO 3166 numeric)
                        cardTech = CardTech.CHIP
                    )
                    val countryResult = getTagValueUseCase.run(countryParams)
                    if (countryResult.isRight) {
                        lastEmvIssuerCountry = countryResult.rightValue().tagValue ?: ""
                        if (lastEmvIssuerCountry.isNotEmpty()) {
                            Timber.i("🌍 [EMV 5F28] Issuer Country Code (CHIP): $lastEmvIssuerCountry")
                        }
                    } else {
                        lastEmvIssuerCountry = ""
                        Timber.d("🌍 [EMV 5F28] Not available for this card (CHIP)")
                    }
                } catch (e: Exception) {
                    lastEmvIssuerCountry = ""
                    Timber.w("🌍 [EMV 5F28] Could not extract (CHIP): ${e.message}")
                }

                // ⚠️ NOTE: emvTagListStr already contains ALL 22 tags in correct TLV format
                // No need to manually construct - GetEmvTagListUseCase returned complete TLV string

                // ✅ REMOVED: Init logic (PASO 3.9 + 3.9.5) - Now handled by InitializationManager in init{}
                // This was causing 65 duplicate database rows because init was called on EVERY payment.
                // Per Edgardo (2025-11-05): "Es recomendable realizar el init solo una vez cada 24 horas"
                // InitializationManager now ensures init runs once every 24 hours via timestamp caching.

                // PASO 4: ⭐ SaleIcc - ONLINE AUTHORIZATION ⭐
                _state.value = PaymentState.Processing("Autorizando con banco...")
                Timber.i("[PHASE 4] SaleIcc - Sending to Momentum for ONLINE authorization...")
                val amountForSaleIcc = formatAmountDecimal(currentAmount)
                val authResult = performOnlineAuthorization(
                    amount = amountForSaleIcc,  // ✅ Decimal format for Blumon API (e.g., "10.00")
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
                val operationNumber = authResult.response.operation?.toIntOrNull() // 💸 Extract for refunds (String → Int)
                Timber.i("✅ [PHASE 4] Online authorization SUCCESS!")
                Timber.i("   Auth Code: ${saleData.authorization}")
                Timber.i("   Reference: ${saleData.reference}")
                Timber.i("   Operation: $operationNumber") // 💸 Log for debugging
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
                        script7172 = saleData.script ?: ""
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

                // ✅ FIX: Display total (subtotal + tip) in Success screen
                // currentAmount = subtotal, currentTip = tip
                _state.value = PaymentState.Success(
                    authCode = saleData.authorization ?: "",
                    amount = calculateTotal(currentAmount, currentTip),
                    tipAmount = currentTip
                )

                // ⭐ NEW: Record payment to backend (in background)
                handlePaymentSuccess(
                    saleData = saleData,
                    entryMode = CardEntryMode.CHIP,
                    blumonOperationNumber = operationNumber, // 💸 Pass for refunds without webhook
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
            // ✅ CRITICAL FIX: Use posId from current merchant instead of SDK database
            // Reason: SDK database may have stale posId after merchant switching
            // The SDK stores posId internally and doesn't update immediately after InsertInitUseCase
            val currentMerchantAccount = _currentMerchant.value
            val posIdToUse = currentMerchantAccount?.posId

            if (posIdToUse == null) {
                Timber.e("❌ [Payment] No posId available - current merchant: ${currentMerchantAccount?.displayName ?: "null"}")
                return AuthorizationResult(
                    response = null,
                    userFriendlyError = "Error de configuración del merchant.\n\n" +
                        "Por favor, seleccione un merchant antes de procesar el pago."
                )
            }

            Timber.i("✅ [Payment] Using posId from current merchant: $posIdToUse")
            Timber.i("   Merchant: ${currentMerchantAccount.displayName}")
            Timber.i("   Serial: ${currentMerchantAccount.serialNumber}")

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

                    // ⭐ NEW: Try to extract error description from Blumon response
                    var specificErrorDescription: String? = null
                    try {
                        // Use reflection to access failure object's fields
                        val failureClass = failure.javaClass
                        Timber.d("🔍 [Error Parsing] Failure class: ${failureClass.name}")

                        // Try to find error-related fields
                        failureClass.declaredFields.forEach { field ->
                            try {
                                field.isAccessible = true
                                val value = field.get(failure)
                                if (value != null) {
                                    Timber.d("   • ${field.name}: $value")

                                    // Check if this field contains MomentumDataFailure object
                                    val valueStr = value.toString()

                                    // Strategy 1: Extract from Kotlin object toString() format
                                    // Pattern: "description=TARJETA INVALIDA"
                                    if (valueStr.contains("description=", ignoreCase = true)) {
                                        val kotlinPattern = """description=([^,)]+)""".toRegex()
                                        val match = kotlinPattern.find(valueStr)
                                        if (match != null) {
                                            specificErrorDescription = match.groupValues[1].trim()
                                            Timber.i("✅ [Error Parsing] Extracted description from Kotlin object: $specificErrorDescription")
                                        }
                                    }

                                    // Strategy 2: Extract from JSON format (fallback)
                                    // Pattern: "description":"TARJETA INVALIDA"
                                    if (specificErrorDescription == null &&
                                        (valueStr.contains("\"description\":", ignoreCase = true) ||
                                         valueStr.contains("\"error\":", ignoreCase = true))) {

                                        val jsonPattern = """"description"\s*:\s*"([^"]+)"""".toRegex()
                                        val match = jsonPattern.find(valueStr)
                                        if (match != null) {
                                            specificErrorDescription = match.groupValues[1]
                                            Timber.i("✅ [Error Parsing] Extracted description from JSON: $specificErrorDescription")
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // Ignore fields we can't access
                            }
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "⚠️ [Error Parsing] Failed to parse error details from failure object")
                    }

                    // Translate SDK errors to user-friendly Spanish messages
                    // 💸 Use "Reembolso" for refund mode, "Pago" for sales
                    val isRefund = currentTransactionType == TransType.REFUND
                    val rejectedPrefix = if (isRefund) "Reembolso rechazado" else "Pago rechazado"
                    val userMessage = when {
                        // ⭐ NEW: Use specific error description if available
                        specificErrorDescription != null -> {
                            "$rejectedPrefix:\n\n$specificErrorDescription\n\n" +
                            "Por favor, solicita otra forma de pago."
                        }
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
                            "$rejectedPrefix por el banco.\n\n" +
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

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // 💸 REFUND AUTHORIZATION - Uses CancelIccUseCase (NOT SaleIccUseCase!)
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Result class for refund authorization (CancelIcc)
     * Note: CancelData has 'reference' field, not 'authorization'
     */
    private data class RefundAuthorizationResult(
        val response: CancelIccResponse?,
        val userFriendlyError: String?
    )

    /**
     * 💸 Perform REFUND authorization with Momentum platform using CancelIccUseCase
     *
     * CRITICAL: This uses CancelIccUseCase (not SaleIccUseCase) to ensure:
     * 1. Blumon registers the transaction as a REFUND (not a sale)
     * 2. Money is returned TO the customer (not charged)
     * 3. Correct ticket type ("Ticket de Devolución" vs "Ticket de Venta")
     * 4. The operationID links to the original transaction for proper refund
     *
     * @param amount Amount to refund (positive value)
     * @param track2 Track2 data from the card
     * @param cardHolderName Cardholder name from chip
     * @param emvTagList EMV tags in TLV format
     * @param originalOperationNumber Blumon operation number from original payment (REQUIRED)
     * @return RefundAuthorizationResult with response or error message
     */
    private suspend fun performRefundAuthorization(
        amount: String,
        track2: String,
        cardHolderName: String,
        emvTagList: String,
        originalOperationNumber: Int  // 💸 CRITICAL: Blumon operation number from original payment
    ): RefundAuthorizationResult {
        return try {
            // ✅ Use posId from current merchant (same as payment authorization)
            val currentMerchantAccount = _currentMerchant.value
            val posIdToUse = currentMerchantAccount?.posId

            if (posIdToUse == null) {
                Timber.e("❌ [Refund] No posId available - current merchant: ${currentMerchantAccount?.displayName ?: "null"}")
                return RefundAuthorizationResult(
                    response = null,
                    userFriendlyError = "Error de configuración del merchant.\n\n" +
                        "Por favor, seleccione un merchant antes de procesar el reembolso."
                )
            }

            Timber.i("✅ [Refund] Using posId from current merchant: $posIdToUse")
            Timber.i("   Merchant: ${currentMerchantAccount.displayName}")
            Timber.i("   Serial: ${currentMerchantAccount.serialNumber}")

            Timber.i("🌐 [CancelIcc] Sending REFUND authorization to Momentum...")
            Timber.d("   Amount: $amount")
            Timber.d("   Track2: ${track2.take(16)}...")
            Timber.d("   Cardholder: $cardHolderName")
            Timber.d("   EMV Tags: ${emvTagList.take(50)}...")
            Timber.d("   OperationID (blumon operation number): $originalOperationNumber")

            // Build CancelIccParams with operationID for the original transaction
            // NOTE: operationID expects String, but we pass the Int as String
            // CRITICAL: This must be the small int from webhook (e.g., 75656), NOT referenceNumber!
            val cipherType = CipherTypeCancel.DUKPT  // ✅ ALWAYS DUKPT (same as sales)

            val params = CancelIccParams(
                operationID = originalOperationNumber.toString(),  // 🎫 CRITICAL: Blumon operation number (fits in Integer)
                idMembership = "",  // Empty = no loyalty program
                amount = amount,
                currency = "484",  // MXN (ISO 4217)
                track2 = track2,
                cardHolderName = cardHolderName,
                authenticationCard = AuthenticationCardCancel.SIGNATURE,
                emvTagList = emvTagList,
                cipherType = cipherType
            )

            // Call CancelIccUseCase (returns Either<Failure, Success>)
            val result = cancelIccUseCase.run(params)

            // Handle Either<Failure, Success> result
            when {
                result.isLeft -> {
                    // Handle failure
                    val failure = result.leftValue()
                    Timber.e("❌ [CancelIcc] Refund failed: $failure")

                    // Extract error description from failure
                    var specificErrorDescription: String? = null
                    try {
                        val failureClass = failure.javaClass
                        failureClass.declaredFields.forEach { field ->
                            try {
                                field.isAccessible = true
                                val value = field.get(failure)
                                if (value != null) {
                                    val valueStr = value.toString()
                                    if (valueStr.contains("description=", ignoreCase = true)) {
                                        val pattern = """description=([^,)]+)""".toRegex()
                                        val match = pattern.find(valueStr)
                                        if (match != null) {
                                            specificErrorDescription = match.groupValues[1].trim()
                                        }
                                    }
                                }
                            } catch (e: Exception) { }
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "⚠️ [Error Parsing] Failed to parse refund error details")
                    }

                    val userMessage = when {
                        specificErrorDescription != null -> {
                            "Reembolso rechazado:\n\n$specificErrorDescription"
                        }
                        failure.toString().contains("NetworkConnection", ignoreCase = true) -> {
                            "Sin conexión a internet.\n\nVerifique su conexión e intente nuevamente."
                        }
                        failure.toString().contains("timeout", ignoreCase = true) -> {
                            "El banco no respondió a tiempo.\n\nPor favor, intente nuevamente."
                        }
                        else -> {
                            "Error procesando el reembolso.\n\nPor favor, intente nuevamente."
                        }
                    }

                    RefundAuthorizationResult(response = null, userFriendlyError = userMessage)
                }
                else -> {
                    // Success - extract response
                    val response = result.rightValue()
                    Timber.i("✅ [CancelIcc] REFUND Success!")
                    Timber.i("   Reference: ${response.cancelData.reference}")
                    Timber.i("   Description: ${response.cancelData.description}")

                    // Log full response structure using reflection (SDK fields vary between versions)
                    Timber.d("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    Timber.d("📋 [BLUMON RESPONSE] CancelIccResponse structure:")
                    Timber.d("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    try {
                        val cancelData = response.cancelData
                        Timber.d("🔹 reference: ${cancelData.reference}")
                        Timber.d("🔹 description: ${cancelData.description}")
                        // Use reflection for optional fields that may vary between SDK versions
                        cancelData::class.java.declaredFields.forEach { field ->
                            try {
                                field.isAccessible = true
                                val value = field.get(cancelData)
                                if (value != null && field.name !in listOf("reference", "description")) {
                                    Timber.d("🔹 ${field.name}: $value")
                                }
                            } catch (e: Exception) { /* Skip inaccessible fields */ }
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Could not extract full refund response structure")
                    }
                    Timber.d("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                    RefundAuthorizationResult(response = response, userFriendlyError = null)
                }
            }

        } catch (e: Exception) {
            Timber.e(e, "❌ [CancelIcc] Exception in refund authorization")
            RefundAuthorizationResult(
                response = null,
                userFriendlyError = "Error inesperado procesando el reembolso.\n\nPor favor, intenta nuevamente."
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
                    // 💰 TIP FIX: Pass total (subtotal + tip) to SaleIcc
                    val totalAmount = calculateTotal(currentAmount, currentTip)
                    Timber.d("💰 [Contactless Online] Passing total to SaleIcc: $totalAmount (subtotal=$currentAmount + tip=$currentTip)")
                    processContactlessOnlineAuthorization(formatAmountDecimal(totalAmount))  // ✅ Decimal format for SaleIcc
                }

                TransResultEnum.RESULT_OFFLINE_APPROVED -> {
                    // Card approved offline (no online authorization needed)
                    Timber.i("🎉 [CONTACTLESS PHASE 3] RESULT_OFFLINE_APPROVED → Payment approved offline!")
                    // ✅ FIX: Display total (subtotal + tip)
                    _state.value = PaymentState.Success(
                        authCode = "OFFLINE_APPROVED",
                        amount = calculateTotal(currentAmount, currentTip),
                        tipAmount = currentTip
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
                    0x50,    // Application Label
                    0x5F28   // 🌍 Issuer Country Code (ISO 3166 numeric, e.g., "484" = Mexico)
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

            // 🌍 Extract Issuer Country Code (EMV tag 0x5F28) for international card detection
            try {
                val countryParams = GetTagValueParams(
                    tag = 0x5F28,  // Issuer Country Code (ISO 3166 numeric)
                    cardTech = CardTech.CONTACTLESS
                )
                val countryResult = getTagValueUseCase.run(countryParams)
                if (countryResult.isRight) {
                    lastEmvIssuerCountry = countryResult.rightValue().tagValue ?: ""
                    if (lastEmvIssuerCountry.isNotEmpty()) {
                        Timber.i("🌍 [EMV 5F28] Issuer Country Code (CONTACTLESS): $lastEmvIssuerCountry")
                    }
                } else {
                    lastEmvIssuerCountry = ""
                    Timber.d("🌍 [EMV 5F28] Not available for this card (CONTACTLESS)")
                }
            } catch (e: Exception) {
                lastEmvIssuerCountry = ""
                Timber.w("🌍 [EMV 5F28] Could not extract (CONTACTLESS): ${e.message}")
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
            val operationNumber = saleResponse.operation?.toIntOrNull() // 💸 Extract for refunds (String → Int)
            Timber.i("✅ [CONTACTLESS ONLINE PHASE 2] Online authorization SUCCESS!")
            Timber.i("   Auth Code: ${saleData.authorization}")
            Timber.i("   Reference: ${saleData.reference}")
            Timber.i("   Operation: $operationNumber") // 💸 Log for debugging

            // ⚠️ NOTE: Contactless typically does NOT require CompleteEmvTrans (ARPC)
            // Skip ARPC checking for contactless transactions
            Timber.i("ℹ️  [CONTACTLESS ONLINE] Skipping CompleteEmvTrans (not required for contactless)")

            Timber.i("🎉 CONTACTLESS PAYMENT APPROVED WITH ONLINE AUTHORIZATION!")

            // ✅ FIX: Display total (subtotal + tip)
            _state.value = PaymentState.Success(
                authCode = saleData.authorization ?: "",
                amount = calculateTotal(currentAmount, currentTip),
                tipAmount = currentTip
            )

            // ⭐ NEW: Record payment to backend (in background)
            handlePaymentSuccess(
                saleData = saleData,
                entryMode = CardEntryMode.CONTACTLESS,
                blumonOperationNumber = operationNumber, // 💸 Pass for refunds without webhook
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
     * Process cash payment (skip card reading, direct backend recording)
     *
     * Flow:
     * 1. Extract payment context from SelectingMerchant state
     * 2. Set state to Processing
     * 3. Call recordPaymentUseCase with CardDetails.CASH
     * 4. Set state to Success or Error
     *
     * ⚠️ CRITICAL: This skips ALL Blumon SDK operations (no PreTrans, no card reading, no EMV)
     */
    fun processCashPayment(totalAmount: String) {
        Timber.d("💵 [Cash Payment] Processing cash payment: \$$totalAmount")

        viewModelScope.launch {
            try {
                // Get current payment context from SelectingMerchant state BEFORE changing state
                val currentState = _state.value as? PaymentState.SelectingMerchant
                    ?: throw IllegalStateException("Invalid state for cash payment. Expected SelectingMerchant, got: ${_state.value}")

                // 🔐 SESSION VALIDATION: Prevent payment if session expired
                val venueId = authRepository.getVenueId()
                val staffId = authRepository.getStaffId()

                if (venueId.isNullOrBlank() || staffId.isNullOrBlank()) {
                    Timber.w("⚠️ [Cash Payment] No session - venueId=$venueId, staffId=$staffId")
                    _state.value = PaymentState.Error(
                        message = "Tu sesión expiró.\n\nPor favor inicia sesión de nuevo.",
                        canRetry = false
                    )
                    return@launch
                }

                currentVenueId = venueId
                currentStaffId = staffId

                // ⭐ CRITICAL: Validate shift is open before processing payment (Square/Toast pattern)
                // Without an open shift, cash reconciliation is impossible
                val currentShift = shiftRepository.getCurrentShift(currentVenueId!!).getOrNull()

                if (shiftRepository.isShiftSystemEnabled()) {
                    if (currentShift == null || currentShift.status != com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus.OPEN) {
                        Timber.w("⚠️ [Cash Payment] No active shift - cannot process payment")
                        Timber.w("   → Cash payments REQUIRE shift for reconciliation (starting cash + payments = expected cash)")
                        _state.value = PaymentState.Error(
                            message = "No hay turno abierto.\n\n" +
                                     "Abre un turno para procesar pagos.",
                            context = null,
                            showOpenShiftButton = true  // ⭐ Show "Abrir Turno" button in dialog
                        )
                        return@launch
                    }
                    // Save shiftId for backend recording
                    currentShiftId = currentShift.id
                    Timber.d("✅ [Cash Payment] Shift validation passed: ${currentShift.id}")
                } else {
                    currentShiftId = null
                    Timber.i("ℹ️ [Cash Payment] Shift system disabled - bypassing shift check")
                }

                // Now change state to Processing
                _state.value = PaymentState.Processing("Registrando pago en efectivo...")

                Timber.d("💵 [Cash Payment] Context: Venue=$currentVenueId, Staff=$currentStaffId, Shift=$currentShiftId")
                Timber.d("💵 [Cash Payment] Details: Subtotal=${currentState.subtotal}, Tip=${currentState.tipAmount}, Total=${currentState.totalAmount}, Rating=${currentState.rating}")

                // Create payment context for cash
                // ✅ RECONCILIATION: null merchantAccountId = proper separation (cash has no processor cost)
                // 🆕 Order vs Fast: If orderId present, create OrderPayment (triggers inventory deduction)
                // ⭐ Split payment params: Convert string to SplitType enum
                val splitTypeEnum = currentSplitType?.let {
                    try { SplitType.valueOf(it.uppercase()) } catch (e: Exception) { SplitType.FULLPAYMENT }
                } ?: SplitType.FULLPAYMENT

                val context = if (currentOrderId != null) {
                    PaymentContext.OrderPayment(
                        venueId = currentVenueId!!,
                        staffId = currentStaffId!!,
                        shiftId = currentShiftId,
                        orderId = currentOrderId!!,  // 🆕 Order ID for backend
                        amount = currentState.subtotal.toBigDecimal(),
                        tip = currentState.tipAmount.toBigDecimal(),
                        rating = currentState.rating,
                        merchantAccountId = null,  // ✅ null = cash (no processor, no commission)
                        blumonSerialNumber = "",   // No Blumon SDK for cash payments
                        // ⭐ Split payment params - CRITICAL for restricting split options after partial payment
                        splitType = splitTypeEnum,
                        paidProductIds = currentPaidProductIds,
                        equalPartsPartySize = currentEqualPartsPartySize,
                        equalPartsPayedFor = currentEqualPartsPayedFor
                    )
                } else {
                    PaymentContext.FastPayment(
                        venueId = currentVenueId!!,
                        staffId = currentStaffId!!,
                        shiftId = currentShiftId,
                        amount = currentState.subtotal.toBigDecimal(),
                        tip = currentState.tipAmount.toBigDecimal(),
                        rating = currentState.rating,
                        merchantAccountId = null,  // ✅ null = cash (no processor, no commission)
                        blumonSerialNumber = "",  // No Blumon SDK for cash payments
                        // 📸 PRE-PAYMENT VERIFICATION (2025-01-14)
                        orderReference = prePaymentOrderReference,
                        verificationPhotos = prePaymentVerificationPhotos,
                        verificationBarcodes = prePaymentVerificationBarcodes.map { it.barcode },
                    )
                }

                // Generate cash reference (unique ID for cash payments)
                val cashReference = "CASH-${System.currentTimeMillis()}"

                Timber.d("💵 [Cash Payment] Recording payment to backend...")
                Timber.d("   💰 Amount: ${context.amount} + Tip: ${context.tip} = Total: ${currentState.totalAmount}")
                Timber.d("   📝 Reference: $cashReference")

                // Record payment directly (skip Blumon SDK)
                val result = recordPaymentUseCase(
                    context = context,
                    cardDetails = CardDetails.CASH,
                    authorizationNumber = "EFECTIVO",
                    referenceNumber = cashReference
                )

                result.onSuccess { receipt ->
                    Timber.d("✅ [Cash Payment] Successfully recorded to backend")
                    Timber.d("   🧾 Payment ID: ${receipt.paymentId} | Receipt URL: ${receipt.receiptUrl}")

                    // 📦 Load order data if this is an order payment (Pedido Rápido or Servicio de Mesa)
                    // ⭐ NEW: Also loads remainingBalance for split payment UI
                    val orderData = loadOrderData(currentOrderId)

                    _state.value = PaymentState.Success(
                        authCode = "EFECTIVO",
                        amount = currentState.totalAmount,  // ✅ FIX: Use totalAmount (subtotal + tip) for display
                        tipAmount = currentState.tipAmount,
                        rating = currentState.rating,
                        receipt = receipt,
                        cardDetails = CardDetails.CASH,
                        referenceNumber = cashReference,
                        orderId = currentOrderId,  // 🆕 Order ID (for loading order items in success screen)
                        orderNumber = currentOrderNumber,  // 🆕 Order number (for display)
                        orderItems = orderData?.items,  // 🆕 Order items (for displaying itemized receipt)
                        remainingBalance = orderData?.remainingBalance,  // ⭐ NEW: For split payment "Continuar pagando" button
                        discountAmount = orderData?.discountAmount?.toPlainString()  // 🆕 Discount for receipt printing
                    )

                    Timber.d("💚 [Cash Payment] Payment completed successfully")
                }.onFailure { error ->
                    Timber.e(error, "❌ [Cash Payment] Failed to record payment to backend")

                    _state.value = PaymentState.Error(
                        message = "Error registrando pago en efectivo:\n\n${error.message ?: "Error desconocido"}",
                        context = RetryContext(
                            amount = currentState.totalAmount,  // ✅ FIX: Use totalAmount for consistency
                            tipAmount = currentState.tipAmount,
                            rating = currentState.rating,
                            merchantAccountId = ""  // No merchant for cash
                        ),
                        canRetry = true
                    )
                }

            } catch (e: IllegalStateException) {
                Timber.e(e, "❌ [Cash Payment] Invalid state or missing context")
                _state.value = PaymentState.Error(
                    message = "Error procesando pago en efectivo:\n\n${e.message}",
                    context = null,
                    canRetry = false
                )
            } catch (e: Exception) {
                Timber.e(e, "❌ [Cash Payment] Unexpected error")
                _state.value = PaymentState.Error(
                    message = "Error inesperado procesando pago en efectivo:\n\n${e.message}",
                    context = null,
                    canRetry = false
                )
            }
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
     * @return RetryContext with amount, tip, rating, merchant, and order context
     */
    private fun createPaymentContext(): RetryContext {
        val context = RetryContext(
            amount = currentAmount,
            tipAmount = currentTip,
            rating = currentRating,
            merchantAccountId = _currentMerchant.value?.merchantAccountId,  // Backend CUID (may be null for fallback)
            merchantLocalId = _currentMerchant.value?.id,  // 🆕 Local ID fallback for merchants without backend CUID
            // 🆕 Order context (FIX: preserve order data for retry)
            orderId = currentOrderId,
            orderNumber = currentOrderNumber,
            splitType = currentSplitType,
            equalPartsPartySize = currentEqualPartsPartySize,
            equalPartsPayedFor = currentEqualPartsPayedFor,
            paidProductIds = currentPaidProductIds
        )
        Timber.d("📸 [Context Snapshot] amount=$currentAmount | tip=$currentTip | rating=$currentRating | merchant_cuid=${_currentMerchant.value?.merchantAccountId ?: "NULL"} | local_id=${_currentMerchant.value?.id}")
        Timber.d("📸 [Context Snapshot] orderId=$currentOrderId | orderNumber=$currentOrderNumber | splitType=$currentSplitType")
        Timber.d("📸 [Context Snapshot] isValid=${context.isValid()} | isOrderPayment=${context.isOrderPayment()}")
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
            Timber.w("   - merchantAccountId: '${context.merchantAccountId ?: "null"}' (is null/blank: ${context.merchantAccountId?.isBlank() ?: true})")
            resetPayment()
            return
        }

        // Restore context to ViewModel state
        currentAmount = context.amount
        currentTip = context.tipAmount
        currentRating = context.rating

        // 🆕 FIX: Restore order context from RetryContext (prevents "order not found" error)
        currentOrderId = context.orderId
        currentOrderNumber = context.orderNumber
        currentSplitType = context.splitType
        currentEqualPartsPartySize = context.equalPartsPartySize
        currentEqualPartsPayedFor = context.equalPartsPayedFor
        currentPaidProductIds = context.paidProductIds ?: emptyList()

        Timber.d("🔄 [Smart Retry] Order context restored: orderId=${context.orderId} | orderNumber=${context.orderNumber} | splitType=${context.splitType}")

        // Restore merchant selection (if not cash payment)
        // ✅ CASH HANDLING: null merchantAccountId = cash payment (no merchant to restore)
        // 🆕 FIX: Try merchantAccountId first (backend CUID), fallback to merchantLocalId
        val merchant = context.merchantAccountId?.let { merchantCuid ->
            // Try to find by backend CUID
            _merchants.value.firstOrNull { it.merchantAccountId == merchantCuid }
        } ?: context.merchantLocalId?.let { localId ->
            // Fallback: Try to find by local ID (for fallback merchants without backend CUID)
            _merchants.value.firstOrNull { it.id == localId }
        }

        if (merchant != null) {
            _currentMerchant.value = merchant
            Timber.d("🔄 [Smart Retry] Merchant restored: ${merchant.displayName} (id=${merchant.id}, cuid=${merchant.merchantAccountId})")
        } else if (context.merchantAccountId == null && context.merchantLocalId == null) {
            Timber.d("🔄 [Smart Retry] Cash payment - no merchant to restore")
        } else {
            Timber.w("🔄 [Smart Retry] Merchant not found | cuid=${context.merchantAccountId} | localId=${context.merchantLocalId}")
            // 🆕 FIX: If merchant not found but we have merchants loaded, use first one as fallback
            if (_merchants.value.isNotEmpty()) {
                val fallbackMerchant = _merchants.value.first()
                _currentMerchant.value = fallbackMerchant
                Timber.w("🔄 [Smart Retry] Using fallback merchant: ${fallbackMerchant.displayName}")
            }
        }

        Timber.i("🔄 [Smart Retry] Restored context | amount=${context.amount} | tip=${context.tipAmount} | rating=${context.rating} | merchant=${_currentMerchant.value?.displayName ?: "NONE"} | orderId=${context.orderId ?: "null"}")

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
                // ✅ NEW FLOW: Amount comes from WelcomeScreen modal, not EnteringAmount screen
                // Return false to let PaymentScreen navigate back to WelcomeScreen
                Timber.d("⬅️  [Payment Flow] Back from CollectingRating → Return to home (WelcomeScreen)")
                false
            }

            is PaymentState.CollectingTip -> {
                // ⚙️ TPV Settings: Check if review screen is enabled
                val tpvSettings = tpvSettingsRepository.getCurrentSettings()

                if (tpvSettings.showReviewScreen) {
                    // Go back to rating
                    Timber.d("⬅️  [Payment Flow] Back from CollectingTip → CollectingRating")
                    _state.value = PaymentState.CollectingRating(
                        amount = currentState.amount,
                        rating = currentState.rating ?: 0
                    )
                    true
                } else {
                    // Review screen disabled - go back to home
                    Timber.d("⬅️  [Payment Flow] Back from CollectingTip → Home (review disabled)")
                    false
                }
            }

            // 📸 PRE-payment verification: Go back to tip (or rating if tip disabled)
            is PaymentState.VerifyingPrePayment -> {
                val tpvSettings = tpvSettingsRepository.getCurrentSettings()

                when {
                    tpvSettings.showTipScreen -> {
                        // Go back to tip
                        Timber.d("⬅️  [Payment Flow] Back from VerifyingPrePayment → CollectingTip")
                        _state.value = PaymentState.CollectingTip(
                            amount = currentState.amount,
                            rating = currentState.rating,
                            tipAmount = currentState.tipAmount
                        )
                        true
                    }
                    tpvSettings.showReviewScreen -> {
                        // Tip disabled, go back to rating
                        Timber.d("⬅️  [Payment Flow] Back from VerifyingPrePayment → CollectingRating (tip disabled)")
                        _state.value = PaymentState.CollectingRating(
                            amount = currentState.amount,
                            rating = currentState.rating ?: 0
                        )
                        true
                    }
                    else -> {
                        // Both disabled - go back to home
                        Timber.d("⬅️  [Payment Flow] Back from VerifyingPrePayment → Home (tip & review disabled)")
                        false
                    }
                }
            }

            is PaymentState.SelectingMerchant -> {
                // ⚙️ TPV Settings: Check which screen to go back to
                val tpvSettings = tpvSettingsRepository.getCurrentSettings()

                when {
                    // 📸 PRE-payment verification: Go back to verification if enabled
                    tpvSettings.showVerificationScreen -> {
                        Timber.d("⬅️  [Payment Flow] Back from SelectingMerchant → VerifyingPrePayment")
                        // Reconstruct VerificationPhoto objects from saved Firebase URLs
                        val restoredPhotos = prePaymentVerificationPhotos.map { url ->
                            VerificationPhoto(
                                localPath = url, // Use URL as path (already uploaded)
                                status = PhotoUploadStatus.UPLOADED,
                                firebaseUrl = url
                            )
                        }
                        _state.value = PaymentState.VerifyingPrePayment(
                            amount = currentState.subtotal,
                            rating = currentState.rating,
                            tipAmount = currentState.tipAmount,
                            photos = restoredPhotos,
                            scannedBarcodes = prePaymentVerificationBarcodes,
                            requirePhoto = tpvSettings.requireVerificationPhoto,
                            requireBarcode = tpvSettings.requireVerificationBarcode,
                            // 🔧 FIX: Restore saved order reference (ensures consistency on go-back)
                            orderReference = prePaymentOrderReference
                        )
                        true
                    }
                    tpvSettings.showTipScreen -> {
                        // Go back to tip
                        Timber.d("⬅️  [Payment Flow] Back from SelectingMerchant → CollectingTip")
                        _state.value = PaymentState.CollectingTip(
                            amount = currentState.subtotal,
                            rating = currentState.rating,
                            tipAmount = currentState.tipAmount
                        )
                        true
                    }
                    tpvSettings.showReviewScreen -> {
                        // Tip disabled, go back to rating
                        Timber.d("⬅️  [Payment Flow] Back from SelectingMerchant → CollectingRating (tip disabled)")
                        _state.value = PaymentState.CollectingRating(
                            amount = currentState.subtotal,
                            rating = currentState.rating ?: 0
                        )
                        true
                    }
                    else -> {
                        // Both disabled - go back to home
                        Timber.d("⬅️  [Payment Flow] Back from SelectingMerchant → Home (tip & review disabled)")
                        false
                    }
                }
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
            is PaymentState.PrintError,
            // 📸 Step 4: Verification state - no back
            is PaymentState.Verifying -> {
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
        blumonOperationNumber: Int? = null, // 💸 Operation number from SDK for refunds
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

                // 2. Build payment context
                // ⭐ PROVIDER-AGNOSTIC MERCHANT TRACKING: Use merchant account ID (primary)
                // 🆕 Order vs Fast: If orderId present, create OrderPayment (triggers inventory deduction)
                val merchantAccountId = _currentMerchant.value?.merchantAccountId  // ✅ FIX: Use backend CUID, not local ID

                // ⚠️ CRITICAL: Log warning if merchantAccountId is missing (backend will reject with 400)
                if (merchantAccountId == null && _currentMerchant.value != null) {
                    Timber.w("⚠️ [Payment] Merchant account missing backend CUID | local_id=${_currentMerchant.value?.id} | serial=${_currentMerchant.value?.serialNumber}")
                    Timber.w("   → Backend will reject this payment with 400 validation error")
                    Timber.w("   → Fetch merchant accounts from backend or add CUIDs to hardcoded accounts")
                }

                val blumonSerial = _currentMerchant.value?.serialNumber ?: "" // ✅ CRITICAL FIX: Use VIRTUAL serial (e.g., "2841548417"), not physical terminal serial

                // ⭐ Split payment params: Convert string to SplitType enum
                val splitTypeEnum = currentSplitType?.let {
                    try { SplitType.valueOf(it.uppercase()) } catch (e: Exception) { SplitType.FULLPAYMENT }
                } ?: SplitType.FULLPAYMENT

                val context = if (currentOrderId != null) {
                    PaymentContext.OrderPayment(
                        venueId = currentVenueId,
                        staffId = currentStaffId,
                        shiftId = currentShiftId, // 🆕 CRITICAL: Shift ID for cash reconciliation (Square/Toast pattern)
                        orderId = currentOrderId!!, // 🆕 Order ID for backend
                        amount = currentAmount.toBigDecimal(),
                        tip = currentTip.toBigDecimal(),
                        rating = currentRating, // 🆕 NEW: Include user rating (1-5 stars or null)
                        merchantAccountId = merchantAccountId, // 🆕 PRIMARY: Merchant account ID
                        blumonSerialNumber = blumonSerial, // ⚠️ LEGACY: Fallback (FIXED: virtual serial)
                        // ⭐ Split payment params - CRITICAL for restricting split options after partial payment
                        splitType = splitTypeEnum,
                        paidProductIds = currentPaidProductIds,
                        equalPartsPartySize = currentEqualPartsPartySize,
                        equalPartsPayedFor = currentEqualPartsPayedFor,
                        // 💸 Blumon Operation Number - For refunds without webhook
                        blumonOperationNumber = blumonOperationNumber,
                    )
                } else {
                    PaymentContext.FastPayment(
                        venueId = currentVenueId,
                        staffId = currentStaffId,
                        shiftId = currentShiftId, // 🆕 CRITICAL: Shift ID for cash reconciliation (Square/Toast pattern)
                        amount = currentAmount.toBigDecimal(),
                        tip = currentTip.toBigDecimal(),
                        rating = currentRating, // 🆕 NEW: Include user rating (1-5 stars or null)
                        merchantAccountId = merchantAccountId, // 🆕 PRIMARY: Merchant account ID
                        blumonSerialNumber = blumonSerial, // ⚠️ LEGACY: Fallback (FIXED: virtual serial)
                        // 📸 PRE-PAYMENT VERIFICATION (2025-01-14)
                        orderReference = prePaymentOrderReference,
                        verificationPhotos = prePaymentVerificationPhotos,
                        verificationBarcodes = prePaymentVerificationBarcodes.map { it.barcode },
                        // 💸 Blumon Operation Number - For refunds without webhook
                        blumonOperationNumber = blumonOperationNumber,
                    )
                }

                Timber.d("💾 [Backend Recording] Context: venue=$currentVenueId, staff=$currentStaffId, shift=$currentShiftId, amount=$currentAmount, tip=$currentTip, rating=$currentRating, merchantId=${context.merchantAccountId}, blumonSerial=${context.blumonSerialNumber}")

                // 🔍 DEBUG: Trace blumonOperationNumber being passed to recorder
                val contextOpNumber = when (context) {
                    is PaymentContext.FastPayment -> context.blumonOperationNumber
                    is PaymentContext.OrderPayment -> context.blumonOperationNumber
                    is PaymentContext.RefundPayment -> context.originalOperationNumber
                }
                Timber.i("═══════════════════════════════════════════════════════════")
                Timber.i("🔍 DEBUG TRACE - ViewModel → Recorder handoff")
                Timber.i("   blumonOperationNumber param received: $blumonOperationNumber")
                Timber.i("   context type: ${context::class.simpleName}")
                Timber.i("   context.blumonOperationNumber: $contextOpNumber")
                Timber.i("═══════════════════════════════════════════════════════════")

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

                    // ⭐ P0 FIX: Update order's merchant account after successful payment
                    // This locks the order to the merchant used for the first payment, preventing split payment mismatches
                    val orderId = currentOrderId  // Local immutable copy to avoid smart cast issues
                    if (orderId != null && _currentMerchant.value != null) {
                        Timber.d("🔒 [Merchant Lock] Updating order merchant | orderId=$orderId | merchant=${_currentMerchant.value?.displayName}")
                        orderSyncCoordinator.updateOrderMerchant(
                            orderId = orderId,
                            merchantAccountId = _currentMerchant.value?.merchantAccountId,
                            merchantAccountName = _currentMerchant.value?.displayName
                        )
                    }

                    // 📦 Load order data if this is an order payment (Pedido Rápido or Servicio de Mesa)
                    // ⭐ NEW: Also loads remainingBalance for split payment UI
                    val orderData = loadOrderData(currentOrderId)

                    // 🆕 NEW: Update Success state with receipt + card details for QR code display and printing
                    val currentState = _state.value
                    if (currentState is PaymentState.Success) {
                        _state.value = currentState.copy(
                            receipt = receipt,
                            cardDetails = cardDetails,  // 🎫 Include card info for professional receipts
                            referenceNumber = referenceNumber,  // 🎫 Include reference for receipts
                            orderId = currentOrderId,  // 🆕 Order ID (for loading order items in success screen)
                            orderNumber = currentOrderNumber,  // 🆕 Order number (for display)
                            orderItems = orderData?.items,  // 🆕 Order items (for displaying itemized receipt)
                            remainingBalance = orderData?.remainingBalance,  // ⭐ NEW: For split payment "Continuar pagando" button
                            discountAmount = orderData?.discountAmount?.toPlainString()  // 🆕 Discount for receipt printing
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
                        merchantAccountId = _currentMerchant.value?.merchantAccountId ?: "", // ✅ FIX: Use backend CUID, not local ID
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
            var issuerCountryFromBlumon = ""

            if (binInfo != null) {
                val binInfoClass = binInfo::class.java

                // 🔍 DISCOVERY: List available fields in binInformation (DEBUG only)
                // ⚠️ SECURITY: Only log in debug builds - may contain sensitive card data
                if (BuildConfig.DEBUG) {
                    try {
                        val allFields = binInfoClass.declaredFields
                        Timber.d("🔍 [BinInformation] Available fields: ${allFields.map { it.name }}")

                        // Log field values for discovery (debug only)
                        allFields.forEach { field ->
                            try {
                                field.isAccessible = true
                                val value = field.get(binInfo)
                                Timber.d("🔍 [BinInformation] Field '${field.name}' = $value (type: ${field.type.simpleName})")
                            } catch (e: Exception) {
                                Timber.w("🔍 [BinInformation] Could not read field '${field.name}': ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        Timber.w("🔍 [BinInformation] Could not list fields: ${e.message}")
                    }
                }

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

                // 🌍 Extract country/issuerCountry for international card detection
                val countryFieldNames = listOf(
                    "country", "countryCode", "issuerCountry", "issuerCountryCode",
                    "cardOrigin", "cardCountry", "origin", "issuingCountry", "emisor"
                )
                for (fieldName in countryFieldNames) {
                    try {
                        val countryField = binInfoClass.getDeclaredField(fieldName)
                        countryField.isAccessible = true
                        val countryValue = countryField.get(binInfo)?.toString() ?: ""
                        if (countryValue.isNotEmpty()) {
                            issuerCountryFromBlumon = countryValue
                            Timber.i("🌍 [BinInformation] Found country field '$fieldName' = $countryValue")
                            break
                        }
                    } catch (e: NoSuchFieldException) {
                        // Field doesn't exist, try next
                    } catch (e: Exception) {
                        Timber.w("🌍 [BinInformation] Error reading '$fieldName': ${e.message}")
                    }
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

            // 🌍 Determine if international card (3-level strategy)
            val isInternational = determineIsInternational(
                binInfoCountry = issuerCountryFromBlumon,
                emvIssuerCountry = lastEmvIssuerCountry,
                bin = bin
            )

            CardDetails(
                maskedPan = maskedPan,
                cardBrand = cardBrand,
                entryMode = entryMode,
                isInternational = isInternational,
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

            // Extract BIN for international detection
            val bin = pan.take(6)

            // 🌍 Determine if international (using EMV + BIN lookup since no Blumon binInfo here)
            val isInternational = determineIsInternational(
                binInfoCountry = null,
                emvIssuerCountry = lastEmvIssuerCountry,
                bin = bin
            )

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
    // INTERNATIONAL CARD DETECTION (3-LEVEL STRATEGY)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 🌍 Determine if a card is international using a 3-level strategy.
     *
     * **Level 1 (Most Reliable):** binInformation.country from Blumon
     * - Blumon has BIN database and returns issuer country
     * - Works for ALL transaction types
     *
     * **Level 2 (Chip/Contactless):** EMV Tag 0x5F28 (Issuer Country Code)
     * - ISO 3166 numeric code (e.g., "484" = Mexico)
     * - May not be available for swipe/fallback
     *
     * **Level 3 (Fallback):** BIN lookup against known Mexican BINs
     * - Less reliable, only used when Level 1 and 2 unavailable
     * - Has false positives/negatives
     *
     * @param binInfoCountry Country from Blumon's binInformation (Level 1)
     * @param emvIssuerCountry EMV tag 0x5F28 value (Level 2)
     * @param bin First 6-8 digits of card number (Level 3 fallback)
     * @return true if card is international, false if domestic (Mexico)
     */
    private fun determineIsInternational(
        binInfoCountry: String?,
        emvIssuerCountry: String? = null,
        bin: String?
    ): Boolean {
        // Level 1: binInformation.country from Blumon (most reliable)
        if (!binInfoCountry.isNullOrEmpty()) {
            val isMexico = binInfoCountry.equals("MX", ignoreCase = true) ||
                    binInfoCountry.equals("MEX", ignoreCase = true) ||
                    binInfoCountry.equals("Mexico", ignoreCase = true) ||
                    binInfoCountry.equals("México", ignoreCase = true) ||
                    binInfoCountry.contains("484") ||  // ISO 3166 numeric
                    binInfoCountry.contains("Mexico", ignoreCase = true)

            val result = !isMexico
            Timber.i("🌍 [International] Level 1 (Blumon): country='$binInfoCountry' → ${if (result) "INTERNATIONAL" else "DOMESTIC"}")
            return result
        }

        // Level 2: EMV Tag 0x5F28 (Issuer Country Code from chip/contactless)
        if (!emvIssuerCountry.isNullOrEmpty()) {
            // ISO 3166 numeric code: "484" = Mexico
            val normalizedCode = emvIssuerCountry.trim().padStart(3, '0')
            val isMexico = normalizedCode == "484" ||
                    emvIssuerCountry == "484" ||
                    emvIssuerCountry == "0484"

            val result = !isMexico
            Timber.i("🌍 [International] Level 2 (EMV 5F28): code='$emvIssuerCountry' (normalized: $normalizedCode) → ${if (result) "INTERNATIONAL" else "DOMESTIC"}")
            return result
        }

        // Level 3: BIN lookup against known Mexican bank BINs (fallback)
        if (!bin.isNullOrEmpty() && bin.length >= 6) {
            val isLikelyMexican = isLikelyMexicanBin(bin)
            val result = !isLikelyMexican
            Timber.i("🌍 [International] Level 3 (BIN lookup): bin='$bin' → ${if (result) "LIKELY INTERNATIONAL" else "LIKELY DOMESTIC"}")
            return result
        }

        // Default: Assume domestic if no data available
        Timber.w("🌍 [International] No country data available, assuming DOMESTIC")
        return false
    }

    /**
     * Check if a BIN is likely from a Mexican bank.
     *
     * ⚠️ WARNING: This is a FALLBACK method with known limitations:
     * - BIN ranges change frequently
     * - Some Mexican banks use international BINs
     * - Some international banks have Mexican BINs
     *
     * Only use when Blumon's binInformation.country is unavailable.
     *
     * Source: Common Mexican bank BIN prefixes
     */
    private fun isLikelyMexicanBin(bin: String): Boolean {
        // Known Mexican bank BIN prefixes (6 digits)
        // This list should be expanded based on actual transaction data
        val mexicanBinPrefixes = setOf(
            // BBVA Bancomer
            "400000", "400001", "400895", "403684", "406334", "410180",
            "411111", "415231", "417100", "417101", "422222", "428331",
            // Banamex / Citibanamex
            "432420", "451510", "454671", "455000", "489363", "493193",
            // Banorte
            "500000", "504363", "511785", "512912", "516758", "520000",
            // Santander
            "521000", "523456", "530000", "542418", "546554",
            // HSBC México
            "400360", "402191", "404091", "407440", "411250",
            // Scotiabank México
            "400217", "400835", "402658", "417432",
            // Inbursa
            "400470", "431510", "432920",
            // Banregio
            "410160", "410161", "423514",
            // Banco Azteca
            "451100", "464401", "464402",
        )

        val binPrefix = bin.take(6)

        // Check if BIN starts with any known Mexican prefix
        val isKnownMexican = mexicanBinPrefixes.any { prefix ->
            binPrefix.startsWith(prefix.take(binPrefix.length.coerceAtMost(prefix.length)))
        }

        // Additional heuristics for common Mexican patterns
        // Most Mexican cards use certain BIN ranges
        val isLikelyMexicanRange = when {
            binPrefix.startsWith("4") -> {
                // VISA: Many Mexican banks use 4xxxxx
                // But this is too broad - only trust explicit prefixes
                isKnownMexican
            }
            binPrefix.startsWith("5") -> {
                // Mastercard: 51-55 range
                // Mexican banks commonly use 51xxxx, 52xxxx
                isKnownMexican
            }
            binPrefix.startsWith("3") -> {
                // AMEX: 34, 37 - Usually international
                false
            }
            binPrefix.startsWith("6") -> {
                // Discover: Usually international
                false
            }
            else -> false
        }

        return isKnownMexican || isLikelyMexicanRange
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ORDER ITEMS LOADING (for displaying in success screen receipt)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 📦 Load order items for displaying in payment success screen.
     *
     * **Purpose:**
     * When payment is for an order (Pedido Rápido or Servicio de Mesa), load the order items
     * to display itemized receipt in the success screen.
     *
     * **Flow:**
     * 1. Check if orderId is present (null = fast payment, skip loading)
     * 2. Call orderRepository.getOrder() to fetch full order details
     * 3. Extract items list from order
     * 4. Return items or null on error
     *
     * **Called from:**
     * - processCashPayment (after cash payment recorded successfully)
     * - handlePaymentSuccess (after card payment recorded successfully)
     *
     * @param orderId Order ID to load items for (null = fast payment, skip loading)
     * @return OrderData with items and remainingBalance, or null if error/not applicable
     */
    private data class OrderData(
        val items: List<com.jaac.avoqado_tpv.features.ordering.domain.OrderItem>,
        val remainingBalance: java.math.BigDecimal,
        val discountAmount: java.math.BigDecimal = java.math.BigDecimal.ZERO  // 🆕 Discount for receipt printing
    )

    private suspend fun loadOrderData(orderId: String?): OrderData? {
        if (orderId == null) {
            Timber.d("📦 [Order Data] Skipping load - Fast payment (no order)")
            return null
        }

        Timber.d("📦 [Order Data] Loading data for order: $orderId")
        return try {
            val result = orderRepository.getOrder(
                venueId = currentVenueId,
                orderId = orderId
            )

            result.onSuccess { order ->
                Timber.d("✅ [Order Data] Loaded ${order.items.size} items | remainingBalance=${order.remainingBalance} | paidAmount=${order.paidAmount}")
                order.items.forEach { item ->
                    Timber.d("   - ${item.quantity}x ${item.productName} @ ${item.formattedTotalPrice}")

                    // ✅ FIX: Log modifiers to debug payment success modal display
                    if (item.modifiers.isNotEmpty()) {
                        Timber.d("      Modifiers (${item.modifiers.size}):")
                        item.modifiers.forEach { modifier ->
                            Timber.d("         • ${modifier.name} (${modifier.formattedPrice})")
                        }
                    }
                }

                // ⭐ FIX: Update Room with fresh order data (paidAmount, remainingBalance)
                // This ensures MenuScreen shows correct remaining balance after split payment
                try {
                    orderSyncCoordinator.cacheBackendOrder(order)
                    Timber.d("💾 [Order Data] Updated Room with fresh payment data | paidAmount=${order.paidAmount} | remainingBalance=${order.remainingBalance}")
                } catch (e: Exception) {
                    Timber.w(e, "⚠️ [Order Data] Failed to cache order to Room (non-blocking)")
                }
            }.onFailure { error ->
                Timber.e(error, "❌ [Order Data] Failed to load data for order $orderId")
            }

            result.getOrNull()?.let { order ->
                OrderData(
                    items = order.items,
                    remainingBalance = order.remainingBalance,
                    discountAmount = order.discountAmount  // 🆕 Include discount for receipt printing
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ [Order Data] Exception loading data for order $orderId")
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RECEIPT PRINTING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 🖨️ Print physical receipt using PAX thermal printer.
     *
     * **Flow:**
     * 1. Get current Success state with payment data
     * 2. Call PrinterManager to print (generic receipt if backend registration failed)
     * 3. Update state to Printing (loading indicator)
     * 4. On success: Return to Success state
     * 5. On failure: Show PrintError state with retry option
     *
     * **Called from:** PaymentSuccessContent when user taps "Imprimir Recibo" button
     *
     * **Requirements:**
     * - Current state must be PaymentState.Success
     * - PAX printer must be available
     *
     * **Behavior:**
     * - If receipt exists (backend registration succeeded): Print full receipt with URL
     * - If receipt is null (backend failed/offline): Print generic receipt with basic payment data
     */
    fun printReceipt() {
        val currentState = _state.value
        if (currentState !is PaymentState.Success) {
            Timber.w("⚠️ [Print] Cannot print: Not in Success state")
            return
        }

        // ✅ REMOVED: No longer require receipt to exist - can print generic receipt
        if (currentState.receipt == null) {
            Timber.i("📄 [Print] No receipt from backend - will print generic receipt with payment data")
        }

        viewModelScope.launch {
            try {
                Timber.i("🖨️ [Print] Starting receipt print")
                _state.value = PaymentState.Printing

                val result = printerManager.printReceipt(
                    receiptUrl = currentState.receipt?.receiptUrl,  // ✅ FIX: Can be null for generic receipt
                    amount = currentState.amount,
                    authCode = currentState.authCode,
                    tipAmount = currentState.tipAmount,
                    cardDetails = currentState.cardDetails,  // 🎫 Pass card info for professional receipt
                    referenceNumber = currentState.referenceNumber,  // 🎫 Pass reference for receipt
                    orderNumber = currentState.orderNumber,  // 🆕 Order number (for display in receipt)
                    orderItems = currentState.orderItems,  // 🆕 Order items (for itemized receipt - only for orders, not fast payments)
                    discountAmount = currentState.discountAmount,  // 🆕 Discount for receipt printing
                    isRefund = currentState.isRefund  // 💸 Pass refund flag for receipt header
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

    /**
     * Send receipt to customer via email.
     *
     * **Called from:** PaymentSuccessContent "Enviar recibo" button dialog
     *
     * **Backend Endpoint:** POST /tpv/venues/{venueId}/payments/{paymentId}/send-receipt
     *
     * @param email Customer email address (validated in UI before calling)
     */
    fun sendReceiptByEmail(email: String) {
        val currentState = _state.value
        if (currentState !is PaymentState.Success) {
            Timber.w("⚠️ [SendReceipt] Cannot send: Not in Success state")
            _sendReceiptMessage.value = "Error: No hay pago completado"
            return
        }

        val receipt = currentState.receipt
        if (receipt == null) {
            Timber.w("⚠️ [SendReceipt] Cannot send: No receipt available (backend recording may have failed)")
            _sendReceiptMessage.value = "Error: No hay recibo disponible"
            return
        }

        viewModelScope.launch {
            try {
                Timber.i("📧 [SendReceipt] Sending receipt to: $email")
                _isSendingReceipt.value = true

                val request = com.jaac.avoqado_tpv.features.payment.data.dto.SendReceiptRequest(
                    recipientEmail = email
                )

                val response = paymentApiService.sendReceipt(
                    venueId = currentVenueId,
                    paymentId = receipt.paymentId,
                    request = request
                )

                if (response.isSuccessful && response.body()?.success == true) {
                    Timber.i("✅ [SendReceipt] Receipt sent successfully to $email")
                    _sendReceiptMessage.value = "✓ Recibo enviado a $email"
                } else {
                    val errorMessage = response.body()?.message ?: "Error al enviar recibo"
                    Timber.e("❌ [SendReceipt] Failed: $errorMessage")
                    _sendReceiptMessage.value = "Error: $errorMessage"
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ [SendReceipt] Unexpected error sending receipt")
                _sendReceiptMessage.value = "Error de conexión: ${e.message}"
            } finally {
                _isSendingReceipt.value = false
            }
        }
    }

    /**
     * Clear send receipt message after displaying to user.
     *
     * **Called from:** PaymentScreen after showing toast/snackbar
     */
    fun clearSendReceiptMessage() {
        _sendReceiptMessage.value = null
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 👤 CUSTOMER SEARCH (for email receipt dialog)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Search customers by name, email, or phone for the email receipt dialog.
     *
     * **Debounce:** Should be called from UI with 300ms debounce (via LaunchedEffect).
     *
     * @param query Search query (min 2 characters)
     */
    fun searchCustomersForReceipt(query: String) {
        if (query.length < 2) {
            _customerSearchState.value = CustomerSearchState.Idle
            return
        }

        viewModelScope.launch {
            _customerSearchState.value = CustomerSearchState.Loading
            Timber.d("🔍 [CustomerSearch] Searching for: $query")

            customerRepository.searchCustomers(
                venueId = currentVenueId,
                query = query,
                limit = 10
            ).onSuccess { customers ->
                _customerSearchState.value = CustomerSearchState.Success(customers)
                Timber.d("✅ [CustomerSearch] Found ${customers.size} customers")
            }.onFailure { error ->
                _customerSearchState.value = CustomerSearchState.Error(
                    error.message ?: "Error buscando clientes"
                )
                Timber.e(error, "❌ [CustomerSearch] Failed to search")
            }
        }
    }

    /**
     * Load recent customers for quick selection in email receipt dialog.
     *
     * **Called from:** Dialog's LaunchedEffect(Unit) on open.
     */
    fun loadRecentCustomersForReceipt() {
        viewModelScope.launch {
            _isLoadingRecentCustomers.value = true
            Timber.d("🕐 [CustomerSearch] Loading recent customers")

            customerRepository.getRecentCustomers(
                venueId = currentVenueId,
                limit = 10
            ).onSuccess { customers ->
                _recentCustomers.value = customers
                Timber.d("✅ [CustomerSearch] Loaded ${customers.size} recent customers")
            }.onFailure { error ->
                _recentCustomers.value = emptyList()
                Timber.e(error, "❌ [CustomerSearch] Failed to load recent customers")
            }

            _isLoadingRecentCustomers.value = false
        }
    }

    /**
     * Reset customer search state when dialog closes or user clears search.
     */
    fun resetCustomerSearch() {
        _customerSearchState.value = CustomerSearchState.Idle
    }

    /**
     * Print kitchen ticket (comanda) for food preparation.
     *
     * **Purpose:** Informative ticket for kitchen staff - NO prices, NO payment info.
     * Focus on products, modifiers, and special instructions.
     *
     * **Called from:** Order details modal in PaymentSuccessContent
     *
     * @param orderNumber Order number for identification
     * @param tableName Optional table name
     * @param orderItems List of order items with modifiers and notes
     */
    fun printKitchenTicket(
        orderNumber: String?,
        tableName: String? = null,
        orderItems: List<com.jaac.avoqado_tpv.features.ordering.domain.OrderItem>
    ) {
        val currentState = _state.value
        if (currentState !is PaymentState.Success) {
            Timber.w("⚠️ [Print] Cannot print kitchen ticket: Not in Success state")
            return
        }

        if (orderItems.isEmpty()) {
            Timber.w("⚠️ [Print] Cannot print kitchen ticket: No order items")
            return
        }

        viewModelScope.launch {
            try {
                Timber.i("🖨️ [Print] Starting kitchen ticket print")
                _state.value = PaymentState.Printing

                val result = printerManager.printKitchenTicket(
                    orderNumber = orderNumber,
                    tableName = tableName,
                    orderItems = orderItems,
                    staffName = null  // TODO: Get from auth context if needed
                )

                result.onSuccess {
                    Timber.i("✅ [Print] Kitchen ticket printed successfully")
                    _state.value = currentState
                }.onFailure { error ->
                    Timber.e(error, "❌ [Print] Failed to print kitchen ticket")
                    _state.value = PaymentState.PrintError(
                        message = error.message ?: "Error al imprimir comanda",
                        previousState = currentState
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ [Print] Unexpected error during kitchen ticket print")
                _state.value = PaymentState.PrintError(
                    message = "Error inesperado al imprimir: ${e.message}",
                    previousState = currentState
                )
            }
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

    // ═══════════════════════════════════════════════════════════════════════════
    // 📸 STEP 4: SALE VERIFICATION (Photo + Barcode)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 📸 Add captured photo to verification state.
     *
     * Called from VerificationScreen when user captures a photo with CameraPreviewScreen.
     *
     * @param photoPath Local file path of captured photo
     */
    fun addVerificationPhoto(photoPath: String) {
        val currentState = _state.value
        if (currentState is PaymentState.Verifying) {
            val updatedPhotos = currentState.capturedPhotos + photoPath
            _state.value = currentState.copy(capturedPhotos = updatedPhotos)
            Timber.d("📸 [Verification] Added photo: $photoPath | total=${updatedPhotos.size}")
        } else {
            Timber.w("📸 [Verification] Cannot add photo - state is not Verifying: $currentState")
        }
    }

    /**
     * 📸 Remove photo from verification state.
     *
     * @param index Index of photo to remove
     */
    fun removeVerificationPhoto(index: Int) {
        val currentState = _state.value
        if (currentState is PaymentState.Verifying) {
            val updatedPhotos = currentState.capturedPhotos.toMutableList().apply {
                if (index in indices) removeAt(index)
            }
            _state.value = currentState.copy(capturedPhotos = updatedPhotos)
            Timber.d("📸 [Verification] Removed photo at index $index | remaining=${updatedPhotos.size}")
        }
    }

    /**
     * 📸 Add scanned barcode to verification state.
     *
     * Called from VerificationScreen when user scans a barcode with BarcodeScannerScreen.
     *
     * @param barcode The scanned barcode value
     * @param format The barcode format (EAN_13, UPC_A, QR_CODE, etc.)
     */
    fun addScannedBarcode(barcode: String, format: String) {
        val currentState = _state.value
        if (currentState is PaymentState.Verifying) {
            // Check if already scanned (prevent duplicates)
            if (currentState.scannedBarcodes.any { it.barcode == barcode }) {
                Timber.w("📸 [Verification] Barcode already scanned: $barcode")
                return
            }

            val scannedProduct = ScannedProduct(
                barcode = barcode,
                format = format,
                productName = null, // TODO: Lookup product by barcode from inventory
                productId = null,
                hasInventory = false
            )
            val updatedBarcodes = currentState.scannedBarcodes + scannedProduct
            _state.value = currentState.copy(scannedBarcodes = updatedBarcodes)
            Timber.d("📸 [Verification] Added barcode: $barcode ($format) | total=${updatedBarcodes.size.toString()}")
        } else {
            Timber.w("📸 [Verification] Cannot add barcode - state is not Verifying: $currentState")
        }
    }

    /**
     * 📸 Remove scanned barcode from verification state.
     *
     * @param barcode Barcode value to remove
     */
    fun removeScannedBarcode(barcode: String) {
        val currentState = _state.value
        if (currentState is PaymentState.Verifying) {
            val updatedBarcodes = currentState.scannedBarcodes.filter { it.barcode != barcode }
            _state.value = currentState.copy(scannedBarcodes = updatedBarcodes)
            Timber.d("📸 [Verification] Removed barcode: $barcode | remaining=${updatedBarcodes.size}")
        }
    }

    /**
     * 📸 Confirm verification and upload data.
     *
     * **Flow:**
     * 1. Validate requirements (photo/barcode if required)
     * 2. Upload photos to Firebase Storage
     * 3. Send verification data to backend API
     * 4. Transition to Success state
     *
     * Called when user taps "Confirmar" in VerificationScreen.
     */
    fun confirmVerification() {
        val currentState = _state.value
        if (currentState !is PaymentState.Verifying) {
            Timber.w("📸 [Verification] Cannot confirm - state is not Verifying")
            return
        }

        viewModelScope.launch {
            try {
                // Mark as uploading
                _state.value = currentState.copy(isUploading = true, error = null)
                Timber.d("📸 [Verification] Starting upload | photos=${currentState.capturedPhotos.size} | barcodes=${currentState.scannedBarcodes.size}")

                // TODO: Phase 6 - Upload photos to Firebase Storage
                // TODO: Phase 6 - Send verification data to backend API

                // For now, just log and transition to success
                Timber.i("✅ [Verification] Verification confirmed | paymentId=${currentState.paymentId}")

                // Transition back to Success state with verificationCompleted=true to prevent loop
                _state.value = PaymentState.Success(
                    authCode = currentState.paymentId, // Use paymentId as authCode
                    amount = currentState.amount,
                    orderId = currentState.orderId,
                    orderNumber = currentState.orderNumber,
                    receipt = currentState.receipt, // 🆕 Preserve receipt for QR code display
                    cardDetails = null,
                    referenceNumber = null,
                    orderItems = null,
                    remainingBalance = null,
                    discountAmount = null,
                    verificationCompleted = true  // ⚠️ CRITICAL: Prevents infinite verification loop
                )

            } catch (e: Exception) {
                Timber.e(e, "📸 [Verification] Upload failed")
                _state.value = currentState.copy(
                    isUploading = false,
                    error = "Error al subir verificación: ${e.message}"
                )
            }
        }
    }

    /**
     * 📸 Skip verification step.
     *
     * Transitions directly to Success state without uploading verification data.
     * Used when verification is optional or user chooses to skip.
     */
    fun skipVerification() {
        val currentState = _state.value
        if (currentState !is PaymentState.Verifying) {
            Timber.w("📸 [Verification] Cannot skip - state is not Verifying")
            return
        }

        Timber.d("📸 [Verification] Skipped by user | paymentId=${currentState.paymentId}")

        // Transition to Success state
        _state.value = PaymentState.Success(
            authCode = currentState.paymentId, // Use paymentId as authCode
            amount = currentState.amount,
            orderId = currentState.orderId,
            orderNumber = currentState.orderNumber,
            receipt = currentState.receipt, // 🆕 Preserve receipt for QR code display
            cardDetails = null,
            referenceNumber = null,
            orderItems = null,
            remainingBalance = null,
            discountAmount = null,
            verificationCompleted = true  // 🆕 Prevent re-triggering verification
        )
    }

    /**
     * 📸 Check if verification step should be shown for this venue.
     *
     * Conditions:
     * 1. showVerificationScreen is enabled in TpvSettings
     * 2. Venue type is in list of applicable types (retail, telecomunicaciones, etc.)
     *
     * @return true if verification should be shown
     */
    fun shouldShowVerificationStep(): Boolean {
        val settings = _tpvSettings.value ?: return false

        if (!settings.showVerificationScreen) {
            Timber.d("📸 [Verification] Not showing - disabled in settings")
            return false
        }

        // TODO: Check venue type against applicable types
        // For now, just check if enabled in settings
        Timber.d("📸 [Verification] Should show verification screen")
        return true
    }

    /**
     * 📸 Start verification step after successful payment.
     *
     * Called from PaymentScreen after PaymentState.Success to transition to Verifying.
     *
     * @param paymentId The payment ID from success state
     * @param amount The payment amount
     * @param orderId Optional order ID
     * @param orderNumber Optional order number for display
     */
    fun startVerificationStep(
        paymentId: String,
        amount: String,
        orderId: String? = null,
        orderNumber: String? = null,
        receipt: com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt? = null  // 🆕 Preserve receipt for QR code
    ) {
        // Removed: Internal shouldShowVerificationStep() check
        // Caller (PaymentScreen) already validated using collected tpvSettings StateFlow
        // This fixes the race condition where _tpvSettings.value could be null

        Timber.i("📸 [Verification] Starting verification step | paymentId=$paymentId | amount=$amount | hasReceipt=${receipt != null}")
        _state.value = PaymentState.Verifying(
            paymentId = paymentId,
            amount = amount,
            orderId = orderId,
            orderNumber = orderNumber,
            capturedPhotos = emptyList(),
            scannedBarcodes = emptyList(),
            isUploading = false,
            uploadProgress = 0f,
            error = null,
            receipt = receipt  // 🆕 Preserve receipt for QR code after verification
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 📸 PRE-PAYMENT VERIFICATION FUNCTIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 📸 Add photo to PRE-payment verification state with immediate Firebase upload.
     *
     * **Flow:**
     * 1. Create VerificationPhoto with PENDING status
     * 2. Add to state immediately (shows local preview)
     * 3. Launch background coroutine to upload to Firebase
     * 4. Update state with UPLOADING → UPLOADED/ERROR
     *
     * @param photoPath Local file path of captured photo
     */
    fun addPrePaymentPhoto(photoPath: String) {
        val currentState = _state.value
        if (currentState is PaymentState.VerifyingPrePayment) {
            // Step 1: Create photo with PENDING status
            val newPhoto = VerificationPhoto(
                localPath = photoPath,
                status = PhotoUploadStatus.PENDING
            )

            // Step 2: Add to state immediately (shows local preview)
            val updatedPhotos = currentState.photos + newPhoto
            val photoIndex = updatedPhotos.size - 1
            _state.value = currentState.copy(photos = updatedPhotos)
            Timber.d("📸 [PRE-Verification] Added photo: $photoPath | index=$photoIndex | status=PENDING")

            // Step 3: Launch background upload
            viewModelScope.launch {
                uploadPhotoInBackground(photoPath, photoIndex)
            }
        } else {
            Timber.w("📸 [PRE-Verification] Cannot add photo - state is not VerifyingPrePayment: $currentState")
        }
    }

    /**
     * 📸 Upload photo to Firebase Storage in background.
     *
     * **Path Structure:**
     * venues/{venueSlug}/verifications/{YYYY-MM-DD}/{orderRef}_{index}.jpg
     *
     * Updates state with progress and final status (UPLOADED/ERROR).
     *
     * @param localPath Local file path of photo
     * @param photoIndex Index of photo in photos list (0-based)
     */
    private suspend fun uploadPhotoInBackground(localPath: String, photoIndex: Int) {
        val venueSlug = authRepository.getVenueSlug()
        if (venueSlug.isNullOrBlank()) {
            Timber.e("📸 [PRE-Verification] Cannot upload - no venueSlug")
            updatePhotoStatus(photoIndex, PhotoUploadStatus.ERROR, error = "No venueSlug disponible")
            return
        }

        // 🔧 FIX: Use orderReference from state (generated ONCE when entering VerifyingPrePayment)
        // This ensures photos match the orderNumber that will be sent to backend
        // Previously: Generated new timestamp here → mismatch with backend order number
        val currentState = _state.value
        val orderReference = if (currentState is PaymentState.VerifyingPrePayment) {
            currentState.orderReference ?: currentOrderNumber ?: "FAST-${System.currentTimeMillis()}"
        } else {
            currentOrderNumber ?: "FAST-${System.currentTimeMillis()}"
        }

        Timber.d("📸 [PRE-Verification] Uploading with orderRef=$orderReference | venue=$venueSlug")

        // Update to UPLOADING status
        updatePhotoStatus(photoIndex, PhotoUploadStatus.UPLOADING)

        // Upload with progress tracking
        verificationUploadManager.uploadPhoto(
            localPath = localPath,
            venueSlug = venueSlug,
            orderReference = orderReference,
            photoIndex = photoIndex + 1,  // Convert to 1-based index for filename
            onProgress = { progress ->
                updatePhotoProgress(photoIndex, progress)
            }
        ).fold(
            onSuccess = { downloadUrl ->
                Timber.i("📸 [PRE-Verification] Upload complete | index=$photoIndex | url=$downloadUrl")
                updatePhotoStatus(photoIndex, PhotoUploadStatus.UPLOADED, firebaseUrl = downloadUrl)
            },
            onFailure = { error ->
                Timber.e(error, "📸 [PRE-Verification] Upload failed | index=$photoIndex")
                updatePhotoStatus(photoIndex, PhotoUploadStatus.ERROR, error = error.message ?: "Error desconocido")
            }
        )
    }

    /**
     * 📸 Update photo upload status in state.
     */
    private fun updatePhotoStatus(
        photoIndex: Int,
        status: PhotoUploadStatus,
        firebaseUrl: String? = null,
        error: String? = null
    ) {
        val currentState = _state.value
        if (currentState is PaymentState.VerifyingPrePayment && photoIndex in currentState.photos.indices) {
            val updatedPhoto = currentState.photos[photoIndex].copy(
                status = status,
                firebaseUrl = firebaseUrl,
                error = error
            )
            val updatedPhotos = currentState.photos.toMutableList().apply {
                set(photoIndex, updatedPhoto)
            }
            _state.value = currentState.copy(photos = updatedPhotos)
            Timber.d("📸 [PRE-Verification] Photo $photoIndex status → $status")
        }
    }

    /**
     * 📸 Update photo upload progress in state.
     */
    private fun updatePhotoProgress(photoIndex: Int, progress: Float) {
        val currentState = _state.value
        if (currentState is PaymentState.VerifyingPrePayment && photoIndex in currentState.photos.indices) {
            val updatedPhoto = currentState.photos[photoIndex].copy(
                uploadProgress = progress
            )
            val updatedPhotos = currentState.photos.toMutableList().apply {
                set(photoIndex, updatedPhoto)
            }
            _state.value = currentState.copy(photos = updatedPhotos)
            // Don't log every progress update to avoid log spam
        }
    }

    /**
     * 📸 Remove photo from PRE-payment verification state.
     *
     * Also deletes from Firebase Storage if already uploaded.
     *
     * @param index Index of photo to remove
     */
    fun removePrePaymentPhoto(index: Int) {
        val currentState = _state.value
        if (currentState is PaymentState.VerifyingPrePayment && index in currentState.photos.indices) {
            val photoToRemove = currentState.photos[index]

            // Delete from Firebase if already uploaded
            if (photoToRemove.isUploaded() && photoToRemove.firebaseUrl != null) {
                viewModelScope.launch {
                    verificationUploadManager.deletePhoto(photoToRemove.firebaseUrl)
                        .onFailure { Timber.w(it, "📸 [PRE-Verification] Failed to delete from Firebase") }
                }
            }

            val updatedPhotos = currentState.photos.toMutableList().apply {
                removeAt(index)
            }
            _state.value = currentState.copy(photos = updatedPhotos)
            Timber.d("📸 [PRE-Verification] Removed photo at index $index | remaining=${updatedPhotos.size}")
        }
    }

    /**
     * 📸 Add scanned barcode to PRE-payment verification state.
     *
     * @param barcode The scanned barcode value
     * @param format The barcode format (EAN_13, UPC_A, QR_CODE, etc.)
     */
    fun addPrePaymentBarcode(barcode: String, format: String) {
        val currentState = _state.value
        if (currentState is PaymentState.VerifyingPrePayment) {
            // Check if already scanned (prevent duplicates)
            if (currentState.scannedBarcodes.any { it.barcode == barcode }) {
                Timber.w("📸 [PRE-Verification] Barcode already scanned: $barcode")
                return
            }

            val scannedProduct = ScannedProduct(
                barcode = barcode,
                format = format,
                productName = null, // TODO: Lookup product by barcode from inventory
                productId = null,
                hasInventory = false
            )
            val updatedBarcodes = currentState.scannedBarcodes + scannedProduct
            _state.value = currentState.copy(scannedBarcodes = updatedBarcodes)
            Timber.d("📸 [PRE-Verification] Added barcode: $barcode ($format) | total=${updatedBarcodes.size}")
        } else {
            Timber.w("📸 [PRE-Verification] Cannot add barcode - state is not VerifyingPrePayment: $currentState")
        }
    }

    /**
     * 📸 Remove scanned barcode from PRE-payment verification state.
     *
     * @param barcode Barcode value to remove
     */
    fun removePrePaymentBarcode(barcode: String) {
        val currentState = _state.value
        if (currentState is PaymentState.VerifyingPrePayment) {
            val updatedBarcodes = currentState.scannedBarcodes.filter { it.barcode != barcode }
            _state.value = currentState.copy(scannedBarcodes = updatedBarcodes)
            Timber.d("📸 [PRE-Verification] Removed barcode: $barcode | remaining=${updatedBarcodes.size}")
        }
    }

    /**
     * 📸 Complete PRE-payment verification and proceed to merchant selection.
     *
     * Called when user taps "Continuar" in VerifyingPrePayment screen.
     * Stores verification data (Firebase URLs) and proceeds to SelectingMerchant state.
     *
     * **Validation:**
     * - If requirePhoto=true, at least one photo must be captured AND uploaded
     * - If requireBarcode=true, at least one barcode must be scanned
     * - All photos must be uploaded (canProceed() checks this)
     * - Only proceeds if canProceed() returns true
     */
    fun completePrePaymentVerification() {
        val currentState = _state.value
        if (currentState !is PaymentState.VerifyingPrePayment) {
            Timber.w("📸 [PRE-Verification] Cannot complete - state is not VerifyingPrePayment")
            return
        }

        if (!currentState.canProceed()) {
            Timber.w("📸 [PRE-Verification] Cannot proceed - requirements not met")
            Timber.w("📸 [PRE-Verification] requirePhoto=${currentState.requirePhoto} | photos=${currentState.photos.size}")
            Timber.w("📸 [PRE-Verification] allUploaded=${currentState.allPhotosUploaded} | hasError=${currentState.hasUploadError}")
            Timber.w("📸 [PRE-Verification] requireBarcode=${currentState.requireBarcode} | barcodes=${currentState.scannedBarcodes.size}")
            return
        }

        // 💾 Store verification data for later (when recording payment)
        // ⭐ Use Firebase URLs (not local paths) for backend storage
        prePaymentVerificationPhotos = currentState.uploadedPhotoUrls
        prePaymentVerificationBarcodes = currentState.scannedBarcodes
        // 🔧 FIX: Store order reference for backend (ensures photos match order)
        prePaymentOrderReference = currentState.orderReference
        Timber.d("📸 [PRE-Verification] Stored verification data: photos=${prePaymentVerificationPhotos.size} (Firebase URLs) | barcodes=${prePaymentVerificationBarcodes.size} | orderRef=$prePaymentOrderReference")

        // Calculate total and proceed to merchant selection
        val totalAmount = calculateTotal(currentState.amount, currentState.tipAmount)
        Timber.d("📸 [PRE-Verification] Proceeding to merchant selection: amount=${currentState.amount} | tip=${currentState.tipAmount} | total=$totalAmount")
        proceedToMerchantSelection(currentState.amount, currentState.tipAmount, totalAmount, currentState.rating)
    }

    /**
     * 📸 Skip PRE-payment verification and proceed to merchant selection.
     *
     * Called when user taps "Saltar" in VerifyingPrePayment screen.
     * Only available when canSkip() returns true (no mandatory requirements).
     *
     * **Logic:**
     * - If requirePhoto=false AND requireBarcode=false → Skip allowed
     * - If ANY requirement is mandatory → Skip NOT allowed (button hidden in UI)
     */
    fun skipPrePaymentVerification() {
        val currentState = _state.value
        if (currentState !is PaymentState.VerifyingPrePayment) {
            Timber.w("📸 [PRE-Verification] Cannot skip - state is not VerifyingPrePayment")
            return
        }

        if (!currentState.canSkip()) {
            Timber.w("📸 [PRE-Verification] Cannot skip - verification is required")
            Timber.w("📸 [PRE-Verification] requirePhoto=${currentState.requirePhoto} | requireBarcode=${currentState.requireBarcode}")
            return
        }

        // Clear any partial verification data
        prePaymentVerificationPhotos = emptyList()
        prePaymentVerificationBarcodes = emptyList()
        prePaymentOrderReference = null
        Timber.d("📸 [PRE-Verification] Skipped - no verification data stored")

        // Calculate total and proceed to merchant selection
        val totalAmount = calculateTotal(currentState.amount, currentState.tipAmount)
        Timber.d("📸 [PRE-Verification] Skipping → merchant selection: amount=${currentState.amount} | tip=${currentState.tipAmount} | total=$totalAmount")
        proceedToMerchantSelection(currentState.amount, currentState.tipAmount, totalAmount, currentState.rating)
    }

    /**
     * 📸 Go back from PRE-payment verification to previous step.
     *
     * Called when user taps back button in VerifyingPrePayment screen.
     * Returns to CollectingTip (or CollectingRating if tip is disabled).
     */
    fun goBackFromPrePaymentVerification() {
        val currentState = _state.value
        if (currentState !is PaymentState.VerifyingPrePayment) {
            Timber.w("📸 [PRE-Verification] Cannot go back - state is not VerifyingPrePayment")
            return
        }

        val tpvSettings = tpvSettingsRepository.getCurrentSettings()

        when {
            tpvSettings.showTipScreen -> {
                // Go back to tip
                Timber.d("📸 [PRE-Verification] Back → CollectingTip")
                _state.value = PaymentState.CollectingTip(
                    amount = currentState.amount,
                    rating = currentState.rating,
                    tipAmount = currentState.tipAmount
                )
            }
            tpvSettings.showReviewScreen -> {
                // Tip disabled, go back to rating
                Timber.d("📸 [PRE-Verification] Back → CollectingRating (tip disabled)")
                _state.value = PaymentState.CollectingRating(
                    amount = currentState.amount,
                    rating = currentState.rating ?: 0
                )
            }
            else -> {
                // Both disabled - go back to home (return to EnteringAmount)
                Timber.d("📸 [PRE-Verification] Back → EnteringAmount (tip & review disabled)")
                _state.value = PaymentState.EnteringAmount(amount = currentState.amount)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // 💸 REFUND FUNCTIONS - Process card refunds with TransType.REFUND
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * Start a refund transaction.
     *
     * **Multi-Merchant CRITICAL:**
     * Refunds MUST be processed on the SAME merchant account as the original payment.
     * This ensures proper settlement reconciliation with Blumon.
     *
     * **Flow:**
     * 1. Switch to original payment's merchant (using blumonSerialNumber)
     * 2. PreTrans with TransType.REFUND (0x20)
     * 3. Detect card
     * 4. Route to processChipRefund() or processContactlessRefund()
     * 5. Record refund to backend via RecordRefundUseCase
     *
     * @param context RefundPayment context with original payment info
     */
    fun startRefund(context: PaymentContext.RefundPayment) {
        currentTransactionType = TransType.REFUND

        // Set amount from context
        currentAmount = context.amount.toPlainString()
        currentAmountInCents = convertToCents(currentAmount)
        currentTip = context.tip.toPlainString()

        // 💸 CRITICAL FIX: Get auth context from repository (not from RefundPayment which has empty fields)
        // The RefundPayment context is created in PaymentScreen without access to auth info
        currentVenueId = authRepository.getVenueId() ?: ""
        currentStaffId = authRepository.getStaffId() ?: ""
        currentShiftId = context.shiftId  // ShiftId from context (may be null, will be resolved later if needed)

        // 💸 CRITICAL FIX: Update context with auth info from repository
        // The original context has empty venueId/staffId - we MUST update them here
        // Otherwise RecordRefundUseCase will call API with /venues//refunds (404)
        currentRefundContext = context.copy(
            venueId = currentVenueId,
            staffId = currentStaffId
        )

        Timber.i("═══════════════════════════════════════════════════════════")
        Timber.i("💸 [REFUND] Starting refund transaction")
        Timber.i("═══════════════════════════════════════════════════════════")
        Timber.i("   💰 Refund amount: $$currentAmount")
        Timber.i("   📄 Original payment: ${context.originalPaymentId}")
        Timber.i("   🏪 Merchant: ${context.merchantAccountId}")
        Timber.i("   📟 Serial: ${context.blumonSerialNumber}")
        Timber.i("   📝 Reason: ${context.refundReason.displayName}")
        Timber.i("   👤 Staff: $currentStaffId")
        Timber.i("   🏢 Venue: $currentVenueId")

        // ⚡ Performance: Start PIN dialog collectors only when transaction begins
        collectPinDialogFlows()

        viewModelScope.launch {
            try {
                // ═══════════════════════════════════════════════════════════════════════════
                // STEP 0: Ensure merchants are loaded (CRITICAL for multi-merchant lookup)
                // ═══════════════════════════════════════════════════════════════════════════
                _state.value = PaymentState.Processing("Cargando configuración...")
                awaitMerchantsLoaded()
                Timber.i("✅ [REFUND] Merchants loaded: ${_merchants.value.size} accounts")

                // ═══════════════════════════════════════════════════════════════════════════
                // STEP 1: Switch to original payment's merchant (CRITICAL for multi-merchant)
                // ═══════════════════════════════════════════════════════════════════════════
                _state.value = PaymentState.Processing("Configurando cuenta de comerciante...")

                // Find merchant account by serial number OR merchantAccountId (from original payment)
                val targetMerchant = _merchants.value.find {
                    it.serialNumber == context.blumonSerialNumber ||
                    it.merchantAccountId == context.merchantAccountId
                }

                if (targetMerchant == null) {
                    Timber.e("❌ [REFUND] Merchant not found!")
                    Timber.e("   🔍 Looking for serial: '${context.blumonSerialNumber}' or merchantId: '${context.merchantAccountId}'")
                    Timber.e("   📋 Available merchants (${_merchants.value.size}):")
                    _merchants.value.forEach { m ->
                        Timber.e("      - ${m.displayName}: serial='${m.serialNumber}', merchantId='${m.merchantAccountId}'")
                    }
                    _state.value = PaymentState.Error(
                        message = "No se encontró la cuenta del comerciante original.\n\n" +
                                "El reembolso debe procesarse con la misma cuenta del pago original.",
                        canRetry = false
                    )
                    return@launch
                }

                // Switch SDK to target merchant
                if (!multiMerchantSDKManager.isMerchantActive(targetMerchant)) {
                    Timber.i("🔄 [REFUND] Switching to merchant: ${targetMerchant.displayName}")
                    val switchResult = multiMerchantSDKManager.switchMerchant(targetMerchant)

                    if (switchResult.isFailure) {
                        val error = switchResult.exceptionOrNull()
                        Timber.e(error, "❌ [REFUND] Failed to switch merchant")
                        _state.value = PaymentState.Error(
                            message = "Error configurando cuenta:\n${error?.message ?: "Error desconocido"}",
                            canRetry = true
                        )
                        return@launch
                    }
                    Timber.i("✅ [REFUND] Switched to merchant: ${targetMerchant.displayName}")
                }

                // Update current merchant state
                _currentMerchant.value = targetMerchant

                // 💸 CRITICAL FIX: Update refund context with merchant's serial number if it was missing
                // This fixes the "blumonSerialNumber is required" error when original payment
                // doesn't have blumonSerialNumber stored (older payments before this field was added)
                // NOTE: Use currentRefundContext (not context) to preserve the venueId/staffId we already fixed
                if (currentRefundContext?.blumonSerialNumber.isNullOrBlank() && targetMerchant.serialNumber.isNotBlank()) {
                    Timber.i("🔧 [REFUND] Fixing missing blumonSerialNumber from target merchant")
                    currentRefundContext = currentRefundContext?.copy(blumonSerialNumber = targetMerchant.serialNumber)
                }

                // ═══════════════════════════════════════════════════════════════════════════
                // STEP 2: Await SDK initialization (same as regular payment)
                // ═══════════════════════════════════════════════════════════════════════════
                if (!initializationManager.isInitialized.value) {
                    _state.value = PaymentState.Processing("Configurando sistema de pago...")
                }

                initializationManager.awaitInitialization().onFailure { error ->
                    Timber.e(error, "❌ [REFUND] SDK initialization failed")
                    _state.value = PaymentState.Error(
                        message = "Error inicializando sistema de pago.\n\nPor favor, cierre sesión e intente nuevamente.",
                        canRetry = false
                    )
                    return@launch
                }

                // ═══════════════════════════════════════════════════════════════════════════
                // STEP 3: PreTrans with TransType.REFUND (already set in currentTransactionType)
                // ═══════════════════════════════════════════════════════════════════════════
                _state.value = PaymentState.ConfiguringKernel
                Timber.i("[REFUND PHASE 1] PreTrans - Configuring EMV kernel for REFUND...")
                // 💰 TIP FIX: Pass tip in cents to Blumon SDK (was hardcoded to "0")
                val tipInCents = convertToCents(currentTip)
                Timber.d("💰 [PreTrans REFUND] Amount: $currentAmountInCents cents | Tip: $tipInCents cents")
                val preParams = PreTransParams(currentAmountInCents, tipInCents, currentTransactionType, CountryConstants.MEX)
                preTransUseCase.runInfallible(preParams)
                Timber.d("✅ [REFUND PHASE 1] PreTrans completed with TransType.REFUND")

                // ═══════════════════════════════════════════════════════════════════════════
                // STEP 4: Detect card (same as sale)
                // ═══════════════════════════════════════════════════════════════════════════
                _state.value = PaymentState.DetectingCard(currentAmount)
                Timber.i("[REFUND PHASE 2] Detecting card for refund...")

                val detectParams = StartDetectCardParams(EReaderType.MAG_ICC_PICC)
                val detectResult = startDetectCardUseCase.run(detectParams)

                if (detectResult.isLeft) {
                    val error = detectResult.leftValue()
                    Timber.e("❌ [REFUND PHASE 2] Card detection failed: $error")
                    _state.value = PaymentState.Error(
                        message = "Error detectando tarjeta: $error",
                        canRetry = true
                    )
                    return@launch
                }

                val detectResponse = detectResult.rightValue()
                val cardType = mapReaderTypeToCardType(detectResponse.pollingResult.readerType)
                Timber.i("✅ [REFUND PHASE 2] Card detected - Type: $cardType")

                // Route based on card type (same as sale)
                when (cardType) {
                    CardType.PICC -> {
                        Timber.i("🔄 [REFUND ROUTING] Contactless card → processContactlessRefund()")
                        processContactlessRefund(currentAmountInCents)
                        return@launch
                    }
                    CardType.ICC, CardType.MAG -> {
                        Timber.i("🔄 [REFUND ROUTING] Chip/Mag card → processChipRefund()")
                        processChipRefund()
                    }
                    CardType.UNKNOWN -> {
                        _state.value = PaymentState.Error(
                            message = "Tipo de tarjeta no soportado para reembolso",
                            canRetry = true
                        )
                        return@launch
                    }
                }

            } catch (e: CancellationException) {
                throw e // Don't catch coroutine cancellation
            } catch (e: Exception) {
                Timber.e(e, "❌ [REFUND] Unexpected error")
                _state.value = PaymentState.Error(
                    message = "Error inesperado en reembolso: ${e.message}",
                    canRetry = true
                )
            }
        }
    }

    /**
     * Process chip card refund (EMV flow with TransType.REFUND)
     */
    private suspend fun processChipRefund() {
        try {
            // STEP 1: Start EMV transaction
            _state.value = PaymentState.Processing("Procesando reembolso con chip...")
            Timber.i("[REFUND PHASE 3] StartEmvTrans...")

            val emvParams = StartEmvTransParams()
            val emvResult = startEmvTransUseCase.run(emvParams)

            if (emvResult.isLeft) {
                val error = emvResult.leftValue()
                Timber.e("❌ [REFUND PHASE 3] EMV failed: $error")
                // Convert SDK error class to user-friendly message
                val friendlyMessage = when (error) {
                    is StartEmvTransFailure.WithdrawnCardFailure -> "Tarjeta retirada antes de tiempo. Por favor, mantenga la tarjeta insertada."
                    is StartEmvTransFailure.TimeoutFailure -> "Tiempo de espera agotado. Por favor, intente de nuevo."
                    is StartEmvTransFailure.CancelOperationFailure -> "Operación cancelada."
                    is StartEmvTransFailure.DetectChipFailure -> "Error al leer el chip. Por favor, limpie el chip e intente de nuevo."
                    is StartEmvTransFailure.CardDeclineByEmvFailure -> "Tarjeta rechazada por el chip. Por favor, use otra tarjeta."
                    is StartEmvTransFailure.EmvIncompleteFailure -> "Lectura incompleta. Por favor, intente de nuevo."
                    is StartEmvTransFailure.EMVFailure -> "Error al procesar la tarjeta. Por favor, intente de nuevo."
                    else -> "Error procesando tarjeta. Por favor, intente de nuevo."
                }
                _state.value = PaymentState.Error(message = friendlyMessage, canRetry = true)
                return
            }

            Timber.d("✅ [REFUND PHASE 3] EMV processed")

            // STEP 2: Extract EMV tags (same as sale)
            _state.value = PaymentState.Processing("Leyendo datos de la tarjeta...")
            Timber.i("[REFUND PHASE 4] Extracting EMV tags...")

            val emvTagParams = GetEmvTagListParam(
                emvTagList = listOf(
                    0x9F27, 0x9F26, 0x9F37, 0x9F36, 0x9C, 0x82, 0x9F33, 0x9F34,
                    0x9A, 0x5F2A, 0x9F02, 0x9F03, 0x9F35, 0x9F10, 0x9F1A, 0x9F09,
                    0x9F6C, 0x9F6E, 0x5F34, 0x84, 0x5F28
                ),
                format = Format.DECIMAL,
                cardTech = CardTech.CHIP
            )

            val tagListResult = getEmvTagListUseCase.runInfallible(emvTagParams)
            val emvTagList = tagListResult.emvTagList
            Timber.d("✅ [REFUND PHASE 4] EMV tags extracted")

            // STEP 3: Extract Track2 for SaleIcc
            val track2TagParams = GetTagValueParams(0x57, CardTech.CHIP)
            val track2Result = getTagValueUseCase.run(track2TagParams)
            val track2 = if (track2Result.isRight) track2Result.rightValue().tagValue else ""
            currentTrack2 = track2

            // STEP 4: Online authorization using CancelIccUseCase
            // 💸 CancelIcc is the correct SDK method for refunding COMPLETED transactions
            // - It accepts operationID (original Blumon operation number)
            // - This tells Blumon which transaction to cancel/refund
            _state.value = PaymentState.Processing("Autorizando reembolso...")

            // 💸 Get original operation number from refund context
            val originalOpNum = currentRefundContext?.originalOperationNumber
            if (originalOpNum == null || originalOpNum == 0) {
                Timber.e("❌ [REFUND PHASE 5] Missing originalOperationNumber in refund context!")
                _state.value = PaymentState.Error(
                    message = "Error: No se encontró el número de operación original.\n\n" +
                        "El reembolso requiere el número de operación del pago original.",
                    canRetry = false
                )
                return
            }

            Timber.i("[REFUND PHASE 5] Using CancelIccUseCase with operationID: $originalOpNum")

            val authResult = performRefundAuthorization(
                amount = formatAmountDecimal(currentAmount),
                track2 = track2,
                cardHolderName = "",
                emvTagList = emvTagList,
                originalOperationNumber = originalOpNum
            )

            if (authResult.userFriendlyError != null) {
                Timber.e("❌ [REFUND PHASE 5] Authorization failed: ${authResult.userFriendlyError}")
                _state.value = PaymentState.Error(message = authResult.userFriendlyError, canRetry = true)
                return
            }

            val cancelResponse = authResult.response!!
            val cancelData = cancelResponse.cancelData

            // STEP 5: Complete EMV if required (same as sale)
            val continueParams = ContinueConfirmCardParams(emvCode = 0)
            continueConfirmCardUseCase.runInfallible(continueParams)

            Timber.i("🎉 REFUND APPROVED!")
            Timber.i("   Reference: ${cancelData.reference}")
            Timber.i("   Description: ${cancelData.description}")

            _state.value = PaymentState.Success(
                authCode = cancelData.reference ?: "", // CancelData uses reference, not authorization
                amount = currentAmount,
                isRefund = true // 💸 Flag this as refund for UI
            )

            // Record refund to backend (using cancelData - handleRefundSuccess uses reflection)
            handleRefundSuccess(
                saleData = cancelData, // CancelData object - reflection extracts reference field
                entryMode = CardEntryMode.CHIP
            )

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "❌ [REFUND] Unexpected error in chip refund flow")
            _state.value = PaymentState.Error(message = "Error inesperado: ${e.message}", canRetry = true)
        }
    }

    /**
     * Process contactless (NFC) refund
     */
    private suspend fun processContactlessRefund(amount: String) {
        try {
            Timber.i("🌊 [CONTACTLESS REFUND] Starting contactless refund flow")

            _state.value = PaymentState.Processing("Procesando reembolso contactless...")
            val ctlssParams = StartCtlssTransParams()
            val ctlssResult = startCtlssTransUseCase.run(ctlssParams)

            if (ctlssResult.isLeft) {
                val error = ctlssResult.leftValue()
                Timber.e("❌ [CONTACTLESS REFUND] Failed: $error")
                _state.value = PaymentState.Error(
                    message = "Error en pago contactless para reembolso",
                    canRetry = true
                )
                return
            }

            val ctlssResponse = ctlssResult.rightValue()
            val transResultEnum = ctlssResponse.transResult?.transResult

            when (transResultEnum) {
                TransResultEnum.RESULT_REQ_ONLINE -> {
                    Timber.i("[CONTACTLESS REFUND] RESULT_REQ_ONLINE → Online authorization")
                    // 💰 TIP FIX: Pass total (subtotal + tip) for refund
                    val totalAmount = calculateTotal(currentAmount, currentTip)
                    Timber.d("💰 [Contactless Refund Online] Passing total: $totalAmount (subtotal=$currentAmount + tip=$currentTip)")
                    processContactlessRefundOnlineAuth(formatAmountDecimal(totalAmount))
                }
                TransResultEnum.RESULT_OFFLINE_APPROVED -> {
                    Timber.i("🎉 [CONTACTLESS REFUND] Offline approved!")
                    _state.value = PaymentState.Success(
                        authCode = "OFFLINE_APPROVED",
                        amount = currentAmount,
                        isRefund = true
                    )
                    // Note: Offline refunds should still be recorded to backend
                    handleRefundSuccess(saleData = null, entryMode = CardEntryMode.CONTACTLESS)
                }
                TransResultEnum.RESULT_OFFLINE_DENIED -> {
                    // 💸 EMV SPEC: Para REEMBOLSOS, OFFLINE_DENIED significa "requiero autorización online"
                    // Los bancos NO permiten reembolsos offline por seguridad anti-fraude
                    // Esto es diferente a pagos donde OFFLINE_DENIED es rechazo final
                    // Square/Toast behavior: Intentar online auth cuando el kernel dice "no puedo offline"
                    Timber.i("[CONTACTLESS REFUND] RESULT_OFFLINE_DENIED → Requiere autorización online (comportamiento EMV normal para reembolsos)")
                    processContactlessRefundOnlineAuth(formatAmountDecimal(currentAmount))
                }
                else -> {
                    _state.value = PaymentState.Error(
                        message = "Resultado desconocido: $transResultEnum",
                        canRetry = true
                    )
                }
            }

        } catch (e: Exception) {
            Timber.e(e, "❌ [CONTACTLESS REFUND] Unexpected error")
            _state.value = PaymentState.Error(message = "Error inesperado: ${e.message}", canRetry = true)
        }
    }

    /**
     * Process contactless refund with online authorization
     */
    private suspend fun processContactlessRefundOnlineAuth(amount: String) {
        try {
            _state.value = PaymentState.Processing("Leyendo datos de tarjeta contactless...")

            // Extract EMV tags with CONTACTLESS card tech
            val emvTagParams = GetEmvTagListParam(
                emvTagList = listOf(
                    0x9F27, 0x9F26, 0x9F37, 0x9F36, 0x9C, 0x82, 0x9F33, 0x9F34,
                    0x9A, 0x5F2A, 0x9F02, 0x9F03, 0x9F35, 0x9F10, 0x9F1A, 0x9F09,
                    0x9F6C, 0x9F6E, 0x5F34, 0x84, 0x5F28
                ),
                format = Format.DECIMAL,
                cardTech = CardTech.CONTACTLESS
            )

            val tagListResult = getEmvTagListUseCase.runInfallible(emvTagParams)
            val emvTagList = tagListResult.emvTagList

            // Get Track2
            val track2Params = GetTagValueParams(0x57, CardTech.CONTACTLESS)
            val track2Result = getTagValueUseCase.run(track2Params)
            val track2 = if (track2Result.isRight) track2Result.rightValue().tagValue else ""
            currentTrack2 = track2

            // Online authorization using CancelIccUseCase
            // 💸 CancelIcc is the correct SDK method for refunding COMPLETED transactions
            _state.value = PaymentState.Processing("Autorizando reembolso...")

            // 💸 Get original operation number from refund context
            val originalOpNum = currentRefundContext?.originalOperationNumber
            if (originalOpNum == null || originalOpNum == 0) {
                Timber.e("❌ [CONTACTLESS REFUND] Missing originalOperationNumber in refund context!")
                _state.value = PaymentState.Error(
                    message = "Error: No se encontró el número de operación original.\n\n" +
                        "El reembolso requiere el número de operación del pago original.",
                    canRetry = false
                )
                return
            }

            Timber.i("[CONTACTLESS REFUND] Using CancelIccUseCase with operationID: $originalOpNum")

            val authResult = performRefundAuthorization(
                amount = amount,
                track2 = track2,
                cardHolderName = "",
                emvTagList = emvTagList,
                originalOperationNumber = originalOpNum
            )

            if (authResult.userFriendlyError != null) {
                _state.value = PaymentState.Error(message = authResult.userFriendlyError, canRetry = true)
                return
            }

            val cancelResponse = authResult.response!!
            val cancelData = cancelResponse.cancelData

            Timber.i("🎉 CONTACTLESS REFUND APPROVED!")
            Timber.i("   Reference: ${cancelData.reference}")
            Timber.i("   Description: ${cancelData.description}")

            _state.value = PaymentState.Success(
                authCode = cancelData.reference ?: "", // CancelData uses reference, not authorization
                amount = currentAmount,
                isRefund = true
            )

            handleRefundSuccess(saleData = cancelData, entryMode = CardEntryMode.CONTACTLESS)

        } catch (e: Exception) {
            Timber.e(e, "❌ [CONTACTLESS REFUND ONLINE] Unexpected error")
            _state.value = PaymentState.Error(message = "Error inesperado: ${e.message}", canRetry = true)
        }
    }

    /**
     * 💸 Handle successful refund and record to backend.
     *
     * This function is called after Blumon SDK approves the refund.
     * It runs in background to avoid blocking the UI.
     *
     * **Flow:**
     * 1. Show success to user immediately (already done by caller)
     * 2. Extract card details from Blumon SDK response (binInformation)
     * 3. Call RecordRefundUseCase with RefundPayment context
     * 4. Handle response (success → receipt ready, error → log warning)
     *
     * **Important:** This function does NOT block the success state.
     * Even if backend call fails, the refund was already approved by Blumon.
     *
     * @param saleData Complete sale data from Blumon SDK (includes binInformation), null for offline refunds
     * @param entryMode How the card was read (CHIP, CONTACTLESS, SWIPE)
     */
    private fun handleRefundSuccess(
        saleData: Any?, // Blumon SDK SaleData object (null for offline refunds)
        entryMode: CardEntryMode,
    ) {
        viewModelScope.launch {
            try {
                val refundContext = currentRefundContext
                if (refundContext == null) {
                    Timber.e("❌ [Refund Recording] currentRefundContext is NULL - cannot record refund")
                    return@launch
                }

                // Extract authorization and reference from SDK response
                val authorizationNumber = if (saleData != null) {
                    try {
                        val authField = saleData::class.java.getDeclaredField("authorization")
                        authField.isAccessible = true
                        authField.get(saleData)?.toString() ?: ""
                    } catch (e: Exception) {
                        Timber.w("Could not extract authorization from saleData: ${e.message}")
                        ""
                    }
                } else {
                    "OFFLINE"
                }

                val referenceNumber = if (saleData != null) {
                    try {
                        val refField = saleData::class.java.getDeclaredField("reference")
                        refField.isAccessible = true
                        refField.get(saleData)?.toString() ?: ""
                    } catch (e: Exception) {
                        Timber.w("Could not extract reference from saleData: ${e.message}")
                        ""
                    }
                } else {
                    "OFFLINE-${System.currentTimeMillis()}"
                }

                Timber.d("💸 [Refund Recording] Starting refund record | auth=$authorizationNumber | ref=$referenceNumber")
                Timber.d("   Original payment: ${refundContext.originalPaymentId}")
                Timber.d("   Refund amount: ${refundContext.amount}")
                Timber.d("   Reason: ${refundContext.refundReason.displayName}")

                // Validate authentication before backend recording
                val hasAuth = authRepository.isAuthenticated()
                if (!hasAuth || currentStaffId.isBlank() || currentVenueId.isBlank()) {
                    Timber.w("⚠️ [Refund Recording] SKIPPED - Missing authentication context")
                    Timber.w("   → Refund succeeded with Blumon, but backend sync requires login")
                    return@launch
                }

                // Extract card details (if saleData available)
                val cardDetails = if (saleData != null) {
                    extractCardDetailsFromBlumonResponse(saleData, entryMode)
                } else {
                    // Offline refund - minimal card details
                    CardDetails(
                        maskedPan = "****",
                        cardBrand = CardBrand.UNKNOWN,
                        entryMode = entryMode,
                        isInternational = false
                    )
                }

                Timber.d("💸 [Refund Recording] Card: ${cardDetails.cardBrand} ${cardDetails.maskedPan} | Entry: ${entryMode}")

                // Call RecordRefundUseCase
                val result = recordRefundUseCase(
                    context = refundContext,
                    cardDetails = cardDetails,
                    authorizationNumber = authorizationNumber,
                    referenceNumber = referenceNumber
                )

                result.onSuccess { receipt ->
                    Timber.i("✅ [Refund Recording] Refund recorded successfully | refundId=${receipt.refundId}")
                    Timber.i("📄 [Refund Recording] Receipt URL: ${receipt.receiptUrl}")

                    // Update Success state with refund receipt
                    val currentState = _state.value
                    if (currentState is PaymentState.Success) {
                        // Convert RefundReceipt to PaymentReceipt for display
                        val paymentReceipt = PaymentReceipt(
                            paymentId = receipt.refundId,
                            receiptUrl = receipt.receiptUrl ?: "", // Fallback to empty if no receipt URL
                            accessKey = "", // Refunds may not have separate access key
                            amount = refundContext.amount.add(refundContext.tip),
                            tipAmount = refundContext.tip
                        )

                        _state.value = currentState.copy(
                            receipt = paymentReceipt,
                            cardDetails = cardDetails,
                            referenceNumber = referenceNumber,
                            isRefund = true
                        )
                        Timber.d("🎫 [Refund Receipt] Updated Success state with refund receipt")
                    }
                }.onFailure { error ->
                    Timber.e(error, "❌ [Refund Recording] Failed to record refund: ${error.message}")
                    Timber.w("   → Refund succeeded with Blumon but NOT recorded to backend")
                    Timber.w("   → This requires manual reconciliation")
                    // Note: Unlike payments, we don't queue refunds for offline retry
                    // Refunds require immediate attention for proper reconciliation
                }

                // Reset transaction type after refund completes
                currentTransactionType = TransType.SALE
                currentRefundContext = null

            } catch (e: Exception) {
                Timber.e(e, "❌ [Refund Recording] Unexpected error")
                // Reset state even on error
                currentTransactionType = TransType.SALE
                currentRefundContext = null
            }
        }
    }
}

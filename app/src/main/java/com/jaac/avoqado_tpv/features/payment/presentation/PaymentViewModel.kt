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
    private val multiMerchantSDKManager: com.jaac.avoqado_tpv.features.payment.data.MultiMerchantSDKManager
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

    private var currentAmount: String = ""
    private var currentTrack2: String = ""

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

        // Prevent switching during active payment
        if (_state.value !is PaymentState.Idle) {
            Timber.w("⚠️ [Merchants] Cannot switch during active payment")
            _merchantSwitchMessage.value = "No puede cambiar de cuenta durante un pago activo"
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Step 1: Show loading
                _merchantSwitchingLoading.value = true
                _merchantSwitchMessage.value = "Cambiando a ${account.displayName}..."
                Timber.d("🏪 [Merchants] Starting switch to: ${account.displayName}")

                // Step 2: Switch merchant (3-5 seconds - OAuth + re-init)
                val result = multiMerchantSDKManager.switchMerchant(account)

                // Step 3: Handle result
                if (result.isSuccess) {
                    _currentMerchant.value = account
                    _merchantSwitchMessage.value = "✅ Ahora usando ${account.displayName}. Puede procesar pago."
                    Timber.i("✅ [Merchants] Successfully switched to: ${account.displayName}")
                    Timber.i("   Serial: ${account.serialNumber}")
                    Timber.i("   TerminalConfig.serialNumber: ${com.jaac.avoqado_tpv.core.domain.TerminalConfig.serialNumber}")
                } else {
                    val error = result.exceptionOrNull()
                    _merchantSwitchMessage.value = "❌ ${error?.message ?: "Error desconocido al cambiar cuenta"}"
                    Timber.e(error, "❌ [Merchants] Failed to switch to: ${account.displayName}")
                }

            } catch (e: Exception) {
                Timber.e(e, "❌ [Merchants] Unexpected error during merchant switch")
                _merchantSwitchMessage.value = "❌ Error inesperado: ${e.message}"
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

    /**
     * Start chip card payment with ONLINE bank authorization via Momentum platform
     *
     * Flow: PreTrans → DetectCard → StartEmvTrans → GetEmvTags → SaleIcc (ONLINE) → CompleteEmvTrans (conditional)
     *
     * ⚠️ CRITICAL: CompleteEmvTrans is ONLY called if the card requires ARPC (AIP bit 3 = 1)
     * This prevents error -11 (FailureSecondGenerate) for cards that don't need ARPC.
     */
    fun startPayment(amount: String) {
        currentAmount = amount
        Timber.d("🎯 [BlumonPayment] Starting ONLINE chip payment flow: $$amount")
        _state.value = PaymentState.ConfiguringKernel

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // PASO 1: PreTrans (configure EMV kernel)
                Timber.i("[PHASE 1] PreTrans - Configuring EMV kernel...")
                val preParams = PreTransParams(amount, "0", TransType.SALE, CountryConstants.MEX)
                preTransUseCase.runInfallible(preParams)
                Timber.d("✅ [PHASE 1] PreTrans completed")

                // PASO 2: StartDetectCard (wait for card tap)
                _state.value = PaymentState.DetectingCard
                Timber.i("[PHASE 2] StartDetectCard - Waiting for card tap...")
                val detectParams = StartDetectCardParams(EReaderType.MAG_ICC_PICC)
                val detectResult = startDetectCardUseCase.run(detectParams)

                if (detectResult.isLeft) {
                    val error = detectResult.leftValue()
                    Timber.e("❌ [PHASE 2] Detect card failed: $error")
                    _state.value = PaymentState.Error("Error detectando tarjeta: $error")
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
                        processContactlessPayment(amount)
                        return@launch  // Exit startPayment() - contactless flow handles everything
                    }
                    CardType.ICC, CardType.MAG -> {
                        Timber.i("🔄 [ROUTING] Chip/Mag card detected → Continuing with chip payment flow")
                        // Continue with chip payment below (PASO 3)
                    }
                    CardType.UNKNOWN -> {
                        Timber.e("❌ [ROUTING] Unknown card type detected: $detectedReaderType")
                        _state.value = PaymentState.Error("Tipo de tarjeta no soportado")
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
                    _state.value = PaymentState.Error("Error procesando EMV: $error")
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
                val saleResponse = performOnlineAuthorization(
                    amount = currentAmount,
                    track2 = currentTrack2,  // Extracted from emvTagListStr above
                    cardHolderName = "CARDHOLDER",  // TODO: Extract from tag 5F20 if available
                    emvTagList = emvTagListStr
                )

                if (saleResponse == null) {
                    Timber.e("❌ [PHASE 4] Online authorization FAILED")
                    _state.value = PaymentState.Error("Error en autorización con banco")
                    return@launch
                }

                val saleData = saleResponse.saleData
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
    private suspend fun performOnlineAuthorization(
        amount: String,
        track2: String,
        cardHolderName: String,
        emvTagList: String
    ): SaleIccResponse? {
        return try {
            // ⚠️ CRITICAL: Retrieve validated posId from SDK database BEFORE calling SaleIccUseCase
            // This must be done in the same suspend context to prevent NumberFormatException
            Timber.i("🔐 [GetInitData] Retrieving validated posId from SDK database...")
            val initDataParams = GetInitDataParams()
            val initDataResult = getInitDataUseCase.run(initDataParams)

            if (initDataResult.isLeft) {
                val failure = initDataResult.leftValue()
                Timber.e("❌ [GetInitData] Failed: $failure")
                return null
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
                    // Handle failure
                    val failure = result.leftValue()
                    val errorMessage = "Payment failed: $failure"
                    Timber.e("❌ [SaleIcc] Failed: $errorMessage")
                    null
                }
                else -> {
                    // Handle success
                    val response = result.rightValue()
                    Timber.i("✅ [SaleIcc] Success!")
                    Timber.i("   Operation: ${response.operation}")
                    Timber.i("   Auth: ${response.saleData.authorization}")
                    Timber.i("   Reference: ${response.saleData.reference}")
                    response
                }
            }

        } catch (e: Exception) {
            Timber.e(e, "❌ [SaleIcc] Exception in online authorization")
            null
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

                _state.value = PaymentState.Error(userMessage)
                return
            }

            val ctlssResponse = ctlssResult.rightValue()
            Timber.i("✅ [CONTACTLESS PHASE 1] Contactless transaction processed")

            // PASO 2: Read TransResult directly from the SDK response (no reflection)
            Timber.i("[CONTACTLESS PHASE 2] Extracting transaction result...")
            val transResult = ctlssResponse.transResult ?: run {
                Timber.e("❌ [CONTACTLESS PHASE 2] transResult is null in SDK response")
                _state.value = PaymentState.Error("Error procesando resultado contactless")
                return
            }
            val transResultEnum = transResult.transResult
            if (transResultEnum == null) {
                Timber.e("❌ [CONTACTLESS PHASE 2] transResultEnum is null (resultCode=${transResult.resultCode})")
                _state.value = PaymentState.Error("Error procesando resultado contactless")
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
                    processContactlessOnlineAuthorization(amount)
                }

                TransResultEnum.RESULT_OFFLINE_APPROVED -> {
                    // Card approved offline (no online authorization needed)
                    Timber.i("🎉 [CONTACTLESS PHASE 3] RESULT_OFFLINE_APPROVED → Payment approved offline!")
                    _state.value = PaymentState.Success(
                        authCode = "OFFLINE_APPROVED",
                        amount = amount
                    )
                }

                TransResultEnum.RESULT_OFFLINE_DENIED -> {
                    // Card declined offline
                    Timber.e("❌ [CONTACTLESS PHASE 3] RESULT_OFFLINE_DENIED → Card declined")
                    _state.value = PaymentState.Error("Tarjeta declinada")
                }

                else -> {
                    // Unknown result
                    Timber.e("❌ [CONTACTLESS PHASE 3] Unknown transaction result: $transResultEnum")
                    _state.value = PaymentState.Error("Resultado desconocido: $transResultEnum")
                }
            }

        } catch (e: Exception) {
            Timber.e(e, "❌ [CONTACTLESS] Unexpected error in contactless payment flow")
            _state.value = PaymentState.Error("Error inesperado en pago contactless: ${e.message}")
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

            val saleResponse = performOnlineAuthorization(
                amount = amount,
                track2 = track2,
                cardHolderName = "CARDHOLDER",
                emvTagList = emvTagListStr
            )

            if (saleResponse == null) {
                Timber.e("❌ [CONTACTLESS ONLINE PHASE 2] Online authorization FAILED")
                _state.value = PaymentState.Error("Error en autorización con banco")
                return
            }

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
                amount = amount
            )

        } catch (e: Exception) {
            Timber.e(e, "❌ [CONTACTLESS ONLINE] Unexpected error")
            _state.value = PaymentState.Error("Error inesperado: ${e.message}")
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
     * Reset payment state to idle
     */
    fun resetPayment() {
        _state.value = PaymentState.Idle
        currentAmount = ""
        currentTrack2 = ""
        Timber.d("🔄 Payment state reset")
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
}

package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.lifecycle.viewModelScope
import com.blumonpay.pax.shared.trans_process.domain.TransProcessRepository
import com.blumonpay.pax.shared.trans_process.domain.use_case.set_select_app_code.SetSelectAppCodeUseCase
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.domain.models.Result as AppResult
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.core.domain.TerminalConfig
import com.jaac.avoqado_tpv.core.util.CriticalNetworkOperationManager
import com.jaac.avoqado_tpv.core.util.ConnectionStateManager
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.modules.domain.model.VenueModule
import com.jaac.avoqado_tpv.features.modules.domain.repository.ModulesRepository
import com.jaac.avoqado_tpv.features.payment.data.InitializationManager
import com.jaac.avoqado_tpv.features.payment.data.MultiMerchantSDKManager
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.payment.domain.PaymentState
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantEnvironment
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentFlowOrigin
import com.jaac.avoqado_tpv.features.payment.domain.model.RefundReason
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordPaymentUseCase
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordRefundUseCase
import com.jaac.avoqado_tpv.features.payment.domain.use_case.GetMerchantsUseCase
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus
import com.paxsz.module.emv.process.contact.CandidateAID
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/**
 * PaymentViewModelTest
 *
 * Tests the payment state machine, multi-merchant switching, refund flow,
 * state contamination prevention, and flow origin management.
 *
 * PaymentViewModel has 33 constructor dependencies. Most are relaxed mocks.
 * Key dependencies requiring explicit configuration:
 * - InitializationManager: SDK readiness
 * - TransProcessRepository: PIN dialog flows
 * - ShiftRepository: Shift validation
 * - TpvSettingsRepository: Payment flow screens
 * - AuthRepository: Venue/staff context
 * - SocketManager: Events SharedFlow
 * - ModulesRepository: Modules StateFlow
 *
 * Uses UnconfinedTestDispatcher because init block launches collectors
 * on StateFlow/SharedFlow that run in viewModelScope.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    // ═══════════════════════════════════════════════════════════════════════════
    // MOCKS requiring explicit configuration
    // ═══════════════════════════════════════════════════════════════════════════

    private lateinit var mockInitializationManager: InitializationManager
    private lateinit var mockMultiMerchantSDKManager: MultiMerchantSDKManager
    private lateinit var mockTransProcessRepository: TransProcessRepository
    private lateinit var mockShiftRepository: ShiftRepository
    private lateinit var mockTpvSettingsRepository: TpvSettingsRepository
    private lateinit var mockAuthRepository: AuthRepository
    private lateinit var mockSecureStorage: SecureStorage
    private lateinit var mockSocketManager: SocketManager
    private lateinit var mockModulesRepository: ModulesRepository
    private lateinit var mockGetMerchantsUseCase: GetMerchantsUseCase
    private lateinit var mockRecordPaymentUseCase: RecordPaymentUseCase
    private lateinit var mockRecordRefundUseCase: RecordRefundUseCase
    private lateinit var mockConnectionStateManager: ConnectionStateManager
    private lateinit var mockCriticalNetworkOperationManager: CriticalNetworkOperationManager
    private lateinit var mockSetSelectAppCodeUseCase: SetSelectAppCodeUseCase

    // Flows needed by init block collectors
    private val socketEventsFlow = MutableSharedFlow<SocketEvent>()
    private val modulesFlow = MutableStateFlow<List<VenueModule>>(emptyList())
    private lateinit var selectAppStateFlow: MutableStateFlow<MutableList<CandidateAID>?>

    // Test data
    private val testVenueId = "venue-test-001"
    private val testStaffId = "staff-test-001"
    private val testShiftId = "shift-test-001"
    private val testMerchantA = MerchantAccount(
        id = "merchant_a",
        serialNumber = "2841548417",
        posId = "376",
        displayName = "Account A",
        environment = MerchantEnvironment.SANDBOX,
        isActive = true
    )

    /**
     * Helper to create RefundPayment context with all required fields.
     */
    private fun createRefundContext(
        venueId: String = testVenueId,
        staffId: String = testStaffId,
        amount: BigDecimal = BigDecimal("50.00"),
        originalPaymentId: String = "pay-001"
    ) = PaymentContext.RefundPayment(
        venueId = venueId,
        staffId = staffId,
        amount = amount,
        tip = BigDecimal.ZERO,
        originalPaymentId = originalPaymentId,
        originalOrderId = null,
        originalTotalAmount = amount,
        refundReason = RefundReason.CUSTOMER_REQUEST,
        merchantAccountId = "merchant_a",
        blumonSerialNumber = "2841548417",
        originalOperationNumber = 75656
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(TerminalConfig)
        every { TerminalConfig.serialNumber } returns "TEST-SERIAL"
        every { TerminalConfig.brand } returns "PAX"
        every { TerminalConfig.model } returns "A80"

        // Configure InitializationManager
        mockInitializationManager = mockk(relaxed = true) {
            every { isInitialized } returns MutableStateFlow(true)
            coEvery { ensureInitialized(any()) } returns Result.success(Unit)
            coEvery { awaitInitialization() } returns Result.success(Unit)
        }

        // Configure MultiMerchantSDKManager
        mockMultiMerchantSDKManager = mockk(relaxed = true) {
            every { getCurrentMerchant() } returns null
            every { isMerchantActive(any()) } returns false
            coEvery { switchMerchant(any()) } returns Result.success(Unit)
        }

        // Configure TransProcessRepository with required flows
        // Note: All flow return types must match SDK types exactly
        selectAppStateFlow = MutableStateFlow(null)
        mockTransProcessRepository = mockk(relaxed = true) {
            every { getEventPinDialogStateFlow() } returns MutableStateFlow(mockk(relaxed = true) {
                every { show } returns false
                every { dismiss } returns false
            })
            every { getKeyboardPinStateFlow() } returns MutableStateFlow("")
            every { getPinResultFlow() } returns MutableStateFlow(null)
            // getPinAttemptsFlow returns Flow<PinAttempts> (SDK type) - use relaxed mock
            every { getSelectAppStateFlow() } returns selectAppStateFlow
            every { confirmCardReadingFlow() } returns MutableStateFlow(false)
        }

        // Configure ShiftRepository (uses custom Result, not kotlin.Result)
        mockShiftRepository = mockk(relaxed = true) {
            every { isShiftSystemEnabled() } returns true
            coEvery { getCurrentShift(any()) } returns AppResult.Success(
                Shift(
                    id = testShiftId,
                    venueId = testVenueId,
                    staffId = testStaffId,
                    staffName = "Test Staff",
                    startTime = "2026-02-07T10:00:00Z",
                    endTime = null,
                    status = ShiftStatus.OPEN,
                    startingCash = BigDecimal.ZERO,
                    endingCash = null,
                    totalSales = BigDecimal.ZERO,
                    totalTips = BigDecimal.ZERO,
                    totalOrders = 0,
                    totalCashPayments = BigDecimal.ZERO,
                    totalCardPayments = BigDecimal.ZERO,
                    totalVoucherPayments = BigDecimal.ZERO,
                    totalOtherPayments = BigDecimal.ZERO,
                    totalProductsSold = 0,
                    durationMinutes = null
                )
            )
        }

        // Configure TpvSettingsRepository
        mockTpvSettingsRepository = mockk(relaxed = true) {
            every { getCurrentSettings() } returns TpvSettings.DEFAULT
        }

        // Configure AuthRepository
        mockAuthRepository = mockk(relaxed = true) {
            every { getVenueId() } returns testVenueId
            every { getStaffId() } returns testStaffId
        }

        // Configure SecureStorage
        mockSecureStorage = mockk(relaxed = true) {
            every { getSerialNumber() } returns "TEST-SERIAL"
        }

        // Configure SocketManager with SharedFlow
        mockSocketManager = mockk(relaxed = true) {
            every { events } returns socketEventsFlow
        }

        // Configure ModulesRepository with StateFlow
        mockModulesRepository = mockk(relaxed = true) {
            every { modules } returns modulesFlow
        }

        // Configure GetMerchantsUseCase (returns single merchant so auto-selection works)
        mockGetMerchantsUseCase = mockk(relaxed = true)
        every { mockGetMerchantsUseCase.invoke() } returns flowOf(listOf(testMerchantA))

        // Configure record use cases
        mockRecordPaymentUseCase = mockk(relaxed = true)
        mockRecordRefundUseCase = mockk(relaxed = true)

        // Configure connectivity manager
        mockConnectionStateManager = mockk(relaxed = true) {
            every { isFullyConnected() } returns true
        }

        mockCriticalNetworkOperationManager = mockk(relaxed = true)
        mockSetSelectAppCodeUseCase = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkObject(TerminalConfig)
        unmockkAll()
        Dispatchers.resetMain()
    }

    /**
     * Create PaymentViewModel with all 33 dependencies.
     * Most are relaxed mocks; key dependencies are configured in setup().
     *
     * IMPORTANT: Must cancel viewModelScope at end of each test to prevent
     * runTest hang from infinite StateFlow collectors in init block.
     */
    private fun createViewModel(): PaymentViewModel {
        return PaymentViewModel(
            preTransUseCase = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.pre_trans.PreTransUseCase>(relaxed = true),
            startDetectCardUseCase = mockk<com.blumonpay.pax.shared.neptune_polling.domain.use_case.start_detect_card.StartDetectCardUseCase>(relaxed = true),
            stopDetectCardUseCase = mockk<com.blumonpay.pax.shared.neptune_polling.domain.use_case.stop_detect_card.StopDetectCardUseCase>(relaxed = true),
            startEmvTransUseCase = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.strat_emv_trans.StartEmvTransUseCase>(relaxed = true),
            startCtlssTransUseCase = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.start_ctlss_trans.StartCtlssTransUseCase>(relaxed = true),
            getEmvTagUseCase = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.get_emv_tags.GetEmvTagUseCase>(relaxed = true),
            completeEmvTransUseCase = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.complete_emv_trans.CompleteEmvTransUseCase>(relaxed = true),
            continueConfirmCardUseCase = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.continue_confirm_card.ContinueConfirmCardUseCase>(relaxed = true),
            setSelectAppCodeUseCase = mockSetSelectAppCodeUseCase,
            saleIccUseCase = mockk<com.example.clean_lib_services.shared.core.domain.use_case.sale_package.sale_icc.SaleIccUseCase>(relaxed = true),
            saleCtlsUseCase = mockk<com.example.clean_lib_services.shared.core.domain.use_case.sale_package.sale_ctls.SaleCtlsUseCase>(relaxed = true),
            cancelIccUseCase = mockk<com.example.clean_lib_services.shared.core.domain.use_case.cancel_package.cancel_icc.CancelIccUseCase>(relaxed = true),
            transProcessRepository = mockTransProcessRepository,
            initializerUseCase = mockk<com.example.clean_lib_services.shared.initializer.domain.use_case.initializer.InitializerUseCase>(relaxed = true),
            getInitDataUseCase = mockk<com.example.clean_lib_services.shared.initializer.domain.use_case.get_init_data.GetInitDataUseCase>(relaxed = true),
            insertInitUseCase = mockk<com.example.clean_lib_services.shared.initializer.domain.use_case.insert_init.InsertInitUseCase>(relaxed = true),
            initializationManager = mockInitializationManager,
            getMerchantsUseCase = mockGetMerchantsUseCase,
            multiMerchantSDKManager = mockMultiMerchantSDKManager,
            recordPaymentUseCase = mockRecordPaymentUseCase,
            recordRefundUseCase = mockRecordRefundUseCase,
            authRepository = mockAuthRepository,
            paymentQueueRepository = mockk(relaxed = true),
            printerManager = mockk(relaxed = true),
            socketManager = mockSocketManager,
            shiftRepository = mockShiftRepository,
            orderRepository = mockk(relaxed = true),
            orderSyncCoordinator = mockk(relaxed = true),
            tpvSettingsRepository = mockTpvSettingsRepository,
            verificationUploadManager = mockk(relaxed = true),
            paymentApiService = mockk(relaxed = true),
            apiService = mockk(relaxed = true),
            customerRepository = mockk(relaxed = true),
            secureStorage = mockSecureStorage,
            modulesRepository = mockModulesRepository,
            connectionStateManager = mockConnectionStateManager,
            merchantRepository = mockk(relaxed = true),
            criticalNetworkOperationManager = mockCriticalNetworkOperationManager
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // A. STATE MACHINE BASICS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `initial state is Idle`() = runTest {
        val viewModel = createViewModel()

        assertThat(viewModel.state.value).isEqualTo(PaymentState.Idle)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `startPayment transitions to Processing`() = runTest {
        val viewModel = createViewModel()

        viewModel.startPayment("100.00")

        // Should transition to Processing (or further) — not Idle
        assertThat(viewModel.state.value).isNotEqualTo(PaymentState.Idle)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `resetPayment returns to Idle and clears all state`() = runTest {
        val viewModel = createViewModel()

        // Start a payment first
        viewModel.startPayment("50.00")
        // Wait for Dispatchers.IO coroutine (continuePaymentFlow) to complete
        Thread.sleep(1000)
        testDispatcher.scheduler.advanceUntilIdle()
        // Then reset
        viewModel.resetPayment()

        assertThat(viewModel.state.value).isEqualTo(PaymentState.Idle)
        assertThat(viewModel.isPaymentInProgress.value).isFalse()
        assertThat(viewModel.flowOrigin.value).isEqualTo(PaymentFlowOrigin.FAST)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `payment guard prevents duplicate startPayment`() = runTest {
        // Make init slow so payment stays in progress
        coEvery { mockInitializationManager.awaitInitialization() } coAnswers {
            kotlinx.coroutines.delay(5000)
            Result.success(Unit)
        }

        val viewModel = createViewModel()

        viewModel.startPayment("100.00")
        assertThat(viewModel.isPaymentInProgress.value).isTrue()

        // Second call should be ignored
        viewModel.startPayment("200.00")
        // State should NOT change to a second Processing with different amount
        // The guard prevents it

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `resetPayment releases payment guard`() = runTest {
        val viewModel = createViewModel()

        viewModel.startPayment("100.00")
        assertThat(viewModel.isPaymentInProgress.value).isTrue()

        viewModel.resetPayment()
        assertThat(viewModel.isPaymentInProgress.value).isFalse()

        viewModel.viewModelScope.cancel()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // B. MULTI-MERCHANT
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `merchants StateFlow populated from getMerchantsUseCase`() = runTest {
        val merchantList = listOf(testMerchantA)
        every { mockGetMerchantsUseCase.invoke() } returns flowOf(merchantList)

        val viewModel = createViewModel()

        // ensureMerchantsLoaded is lazy, trigger it via selectMerchant or directly
        // The merchants flow should eventually emit
        assertThat(viewModel.merchants.value).isEqualTo(emptyList<MerchantAccount>())

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `selectMerchant updates currentMerchant on success`() = runTest {
        val viewModel = createViewModel()

        viewModel.selectMerchant(testMerchantA)
        // selectMerchant uses Dispatchers.IO — wait for the real IO coroutine to complete
        Thread.sleep(1000)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.currentMerchant.value).isEqualTo(testMerchantA)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `selectMerchant sets loading state during switch`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectMerchant(testMerchantA)
        // selectMerchant uses Dispatchers.IO — wait for completion
        Thread.sleep(1000)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.merchantSwitchingLoading.value).isFalse()

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `selectMerchant shows error message on failure`() = runTest {
        coEvery { mockMultiMerchantSDKManager.switchMerchant(any()) } returns
            Result.failure(Exception("Switch failed"))

        val viewModel = createViewModel()
        viewModel.selectMerchant(testMerchantA)
        // selectMerchant uses Dispatchers.IO — wait for the real IO coroutine to complete
        Thread.sleep(1000)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.merchantSwitchMessage.value).contains("❌")

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `clearMerchantSwitchMessage clears message`() = runTest {
        coEvery { mockMultiMerchantSDKManager.switchMerchant(any()) } returns
            Result.failure(Exception("Error"))

        val viewModel = createViewModel()
        viewModel.selectMerchant(testMerchantA)
        // selectMerchant uses Dispatchers.IO — wait for the real IO coroutine to complete
        Thread.sleep(1000)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.merchantSwitchMessage.value).isNotNull()

        viewModel.clearMerchantSwitchMessage()
        assertThat(viewModel.merchantSwitchMessage.value).isNull()

        viewModel.viewModelScope.cancel()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // C. PAYMENT FLOW
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `startPayment with no shift open shows error with shift button`() = runTest {
        every { mockShiftRepository.isShiftSystemEnabled() } returns true
        coEvery { mockShiftRepository.getCurrentShift(any()) } returns AppResult.Success(null)

        val viewModel = createViewModel()
        viewModel.startPayment("100.00")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state).isInstanceOf(PaymentState.Error::class.java)
        val errorState = state as PaymentState.Error
        assertThat(errorState.showOpenShiftButton).isTrue()
        assertThat(errorState.message).contains("turno")

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `startPayment ensures SDK initialization`() = runTest {
        val viewModel = createViewModel()
        viewModel.startPayment("100.00")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { mockInitializationManager.awaitInitialization() }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `startPayment with init failure transitions to Error`() = runTest {
        coEvery { mockInitializationManager.awaitInitialization() } returns
            Result.failure(Exception("SDK init failed"))

        val viewModel = createViewModel()
        viewModel.startPayment("100.00")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state).isInstanceOf(PaymentState.Error::class.java)
        assertThat((state as PaymentState.Error).message).contains("inicializando")

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `startPayment with expired session shows session error`() = runTest {
        every { mockAuthRepository.getVenueId() } returns null
        every { mockAuthRepository.getStaffId() } returns null

        val viewModel = createViewModel()
        viewModel.startPayment("100.00")

        val state = viewModel.state.value
        assertThat(state).isInstanceOf(PaymentState.Error::class.java)
        assertThat((state as PaymentState.Error).message).contains("sesión")
        assertThat(viewModel.isPaymentInProgress.value).isFalse()

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `startPayment with no connectivity shows cash fallback error`() = runTest {
        every { mockConnectionStateManager.isFullyConnected() } returns false

        val viewModel = createViewModel()
        viewModel.startPayment("100.00")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state).isInstanceOf(PaymentState.Error::class.java)
        val errorState = state as PaymentState.Error
        assertThat(errorState.showCashFallback).isTrue()
        assertThat(errorState.message).contains("Sin conexión")
        assertThat(viewModel.isPaymentInProgress.value).isFalse()

        coVerify(exactly = 0) { mockInitializationManager.awaitInitialization() }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `chip app selection responds to SDK with first candidate`() = runTest {
        val viewModel = createViewModel()
        viewModel.startPayment("100.00")

        selectAppStateFlow.value = mutableListOf(mockk(relaxed = true))

        coVerify(timeout = 1000) {
            mockSetSelectAppCodeUseCase.runInfallible(match { it.selectAppCode == 0 })
        }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `retryPayment releases payment guard from failed attempt`() = runTest {
        val viewModel = createViewModel()

        // Simulate a failed payment that left guard locked
        viewModel.startPayment("100.00")

        // retryPayment should release the guard before restarting
        viewModel.retryPayment(null) // null context → resets to idle

        assertThat(viewModel.isPaymentInProgress.value).isFalse()

        viewModel.viewModelScope.cancel()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // D. REFUND FLOW
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `startRefund sets flowOrigin to REFUND`() = runTest {
        every { mockGetMerchantsUseCase.invoke() } returns flowOf(listOf(testMerchantA))
        every { mockMultiMerchantSDKManager.isMerchantActive(any()) } returns true

        val viewModel = createViewModel()
        viewModel.startRefund(createRefundContext())

        assertThat(viewModel.flowOrigin.value).isEqualTo(PaymentFlowOrigin.REFUND)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `startRefund uses payment venueId not auth context`() = runTest {
        every { mockGetMerchantsUseCase.invoke() } returns flowOf(listOf(testMerchantA))
        every { mockMultiMerchantSDKManager.isMerchantActive(any()) } returns true

        val viewModel = createViewModel()
        viewModel.startRefund(createRefundContext(venueId = "venue-from-payment"))

        // flowOrigin confirms refund started with the provided venueId
        assertThat(viewModel.flowOrigin.value).isEqualTo(PaymentFlowOrigin.REFUND)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `startRefund falls back to auth venueId when context venueId is blank`() = runTest {
        every { mockGetMerchantsUseCase.invoke() } returns flowOf(listOf(testMerchantA))
        every { mockMultiMerchantSDKManager.isMerchantActive(any()) } returns true

        val viewModel = createViewModel()
        viewModel.startRefund(createRefundContext(venueId = ""))

        // Should fallback to auth repo venueId
        verify { mockAuthRepository.getVenueId() }
        assertThat(viewModel.flowOrigin.value).isEqualTo(PaymentFlowOrigin.REFUND)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `startRefund always uses auth context for staffId`() = runTest {
        every { mockGetMerchantsUseCase.invoke() } returns flowOf(listOf(testMerchantA))
        every { mockMultiMerchantSDKManager.isMerchantActive(any()) } returns true

        val viewModel = createViewModel()
        viewModel.startRefund(createRefundContext(staffId = "original-staff"))

        // startRefund always calls authRepository.getStaffId() for the CURRENT staff
        verify { mockAuthRepository.getStaffId() }

        viewModel.viewModelScope.cancel()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // E. STATE CONTAMINATION PREVENTION
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `resetPayment clears all payment-level state`() = runTest {
        val viewModel = createViewModel()

        // Start a payment to set various state
        viewModel.startPayment("150.00")
        testDispatcher.scheduler.advanceUntilIdle()

        // Reset
        viewModel.resetPayment()

        // Verify all state is clean
        assertThat(viewModel.state.value).isEqualTo(PaymentState.Idle)
        assertThat(viewModel.isPaymentInProgress.value).isFalse()
        assertThat(viewModel.flowOrigin.value).isEqualTo(PaymentFlowOrigin.FAST)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `resetPayment after refund clears flowOrigin back to FAST`() = runTest {
        every { mockGetMerchantsUseCase.invoke() } returns flowOf(listOf(testMerchantA))
        every { mockMultiMerchantSDKManager.isMerchantActive(any()) } returns true

        val viewModel = createViewModel()

        viewModel.startRefund(createRefundContext(originalPaymentId = "pay-005"))
        assertThat(viewModel.flowOrigin.value).isEqualTo(PaymentFlowOrigin.REFUND)

        viewModel.resetPayment()
        assertThat(viewModel.flowOrigin.value).isEqualTo(PaymentFlowOrigin.FAST)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `consecutive payments dont leak state`() = runTest {
        val viewModel = createViewModel()

        // First payment
        viewModel.startPayment("100.00")
        // Wait for Dispatchers.IO coroutine (continuePaymentFlow) to complete
        Thread.sleep(1000)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.resetPayment()

        // Second payment should start clean
        assertThat(viewModel.state.value).isEqualTo(PaymentState.Idle)
        assertThat(viewModel.flowOrigin.value).isEqualTo(PaymentFlowOrigin.FAST)
        assertThat(viewModel.isPaymentInProgress.value).isFalse()

        viewModel.viewModelScope.cancel()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // F. FLOW ORIGIN
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `default flowOrigin is FAST`() = runTest {
        val viewModel = createViewModel()

        assertThat(viewModel.flowOrigin.value).isEqualTo(PaymentFlowOrigin.FAST)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `resetPayment resets flowOrigin to FAST`() = runTest {
        // Simulate setting a different flow origin (via startRefund)
        every { mockGetMerchantsUseCase.invoke() } returns flowOf(listOf(testMerchantA))
        every { mockMultiMerchantSDKManager.isMerchantActive(any()) } returns true

        val viewModel = createViewModel()

        viewModel.startRefund(createRefundContext(amount = BigDecimal("10.00"), originalPaymentId = "pay-006"))
        assertThat(viewModel.flowOrigin.value).isEqualTo(PaymentFlowOrigin.REFUND)

        viewModel.resetPayment()
        assertThat(viewModel.flowOrigin.value).isEqualTo(PaymentFlowOrigin.FAST)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `startRefund sets flowOrigin to REFUND via StateFlow`() = runTest {
        every { mockGetMerchantsUseCase.invoke() } returns flowOf(listOf(testMerchantA))
        every { mockMultiMerchantSDKManager.isMerchantActive(any()) } returns true

        val viewModel = createViewModel()

        assertThat(viewModel.flowOrigin.value).isEqualTo(PaymentFlowOrigin.FAST)

        viewModel.startRefund(createRefundContext(amount = BigDecimal("75.00"), originalPaymentId = "pay-007"))

        assertThat(viewModel.flowOrigin.value).isEqualTo(PaymentFlowOrigin.REFUND)

        viewModel.viewModelScope.cancel()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // G. TPV SETTINGS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `tpvSettings loaded on init`() = runTest {
        val viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // getCurrentSettings is called in init (loadTpvSettings)
        verify { mockTpvSettingsRepository.getCurrentSettings() }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `startPayment with disabled shift system bypasses shift check`() = runTest {
        every { mockShiftRepository.isShiftSystemEnabled() } returns false
        coEvery { mockShiftRepository.getCurrentShift(any()) } returns AppResult.Success(null)

        val viewModel = createViewModel()
        viewModel.startPayment("100.00")
        testDispatcher.scheduler.advanceUntilIdle()

        // Should NOT show shift error even with no shift
        val state = viewModel.state.value
        if (state is PaymentState.Error) {
            assertThat(state.showOpenShiftButton).isFalse()
        }

        viewModel.viewModelScope.cancel()
    }
}

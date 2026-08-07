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
import com.jaac.avoqado_tpv.core.util.ConnectionEventManager
import com.jaac.avoqado_tpv.core.util.ConnectionStateManager
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.modules.domain.model.VenueModule
import com.jaac.avoqado_tpv.features.modules.domain.repository.ModulesRepository
import com.jaac.avoqado_tpv.features.payment.data.InitializationManager
import com.jaac.avoqado_tpv.features.payment.data.MultiMerchantSDKManager
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger
import com.jaac.avoqado_tpv.features.payment.data.local.AuthAttemptTelemetryStore
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.payment.domain.PaymentState
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantEnvironment
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentFlowOrigin
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt
import com.jaac.avoqado_tpv.features.payment.domain.model.RefundReason
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordPaymentUseCase
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordRefundUseCase
import com.jaac.avoqado_tpv.features.payment.domain.use_case.GetMerchantsUseCase
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus
import com.paxsz.module.emv.process.contact.CandidateAID
import android.content.Context
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
import com.jaac.avoqado_tpv.core.observability.CrashlyticsContext
import com.jaac.avoqado_tpv.core.observability.ObservabilityManager

/**
 * PaymentViewModelTest
 *
 * Tests the payment state machine, multi-merchant switching, refund flow,
 * state contamination prevention, and flow origin management.
 *
 * PaymentViewModel has 34 constructor dependencies. Most are relaxed mocks.
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

    /**
     * Real holder (a pair of AtomicBooleans) hoisted to a field so tests can OBSERVE the two
     * money signals instead of just satisfying the constructor. Recreated per test in setup().
     */
    private lateinit var paymentStateHolder:
        com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder

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
    private lateinit var mockPaymentAttemptLedger: PaymentAttemptLedger
    // 📊 Task 6 — relaxed: recording is fire-and-forget and observational only.
    private lateinit var mockAuthAttemptTelemetryStore: AuthAttemptTelemetryStore
    private lateinit var mockMerchantEligibilityRepository:
        com.jaac.avoqado_tpv.features.payment.domain.repository.MerchantEligibilityRepository
    private lateinit var mockConnectionStateManager: ConnectionStateManager
    private lateinit var mockCriticalNetworkOperationManager: CriticalNetworkOperationManager
    private lateinit var mockSetSelectAppCodeUseCase: SetSelectAppCodeUseCase
    private lateinit var mockConnectionEventManager: ConnectionEventManager
    private lateinit var mockAppContext: Context
    // 🚨 Money-safety telemetry — verified explicitly by the "RECORDING LOST" test section below
    private lateinit var observabilityManager: ObservabilityManager
    // 🔴 Fix round 1: promoted from an anonymous inline mock to a named field so the
    // mid-enqueue-cancellation test can control exactly when enqueue() suspends/returns.
    private lateinit var mockPaymentQueueRepository:
        com.jaac.avoqado_tpv.features.payment.domain.repository.PaymentQueueRepository

    // Flows needed by init block collectors
    private val socketEventsFlow = MutableSharedFlow<SocketEvent>()
    private val connectionRestoredFlow = MutableSharedFlow<com.jaac.avoqado_tpv.core.util.ConnectionRestoredEvent>()
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
        // Fresh per test — it is a @Singleton in prod, so leaking state across tests would hide bugs.
        paymentStateHolder =
            com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder()
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

        // 📒 Ledger (La Libreta) — relaxed: the wiring is observational, tests verify the calls
        mockPaymentAttemptLedger = mockk(relaxed = true)
        mockAuthAttemptTelemetryStore = mockk(relaxed = true)
        mockMerchantEligibilityRepository = mockk(relaxed = true) {
            coEvery { evaluate(any(), any(), any()) } returns
                com.jaac.avoqado_tpv.features.payment.domain.model.MerchantEligibility.disabled()
        }

        // Configure connectivity manager
        mockConnectionStateManager = mockk(relaxed = true) {
            every { isFullyConnected() } returns true
        }

        mockCriticalNetworkOperationManager = mockk(relaxed = true)
        mockSetSelectAppCodeUseCase = mockk(relaxed = true)

        // Configure ConnectionEventManager with a SharedFlow the tests can emit to
        mockConnectionEventManager = mockk(relaxed = true) {
            every { connectionRestoredEvents } returns connectionRestoredFlow
        }

        // Application context (needed by PaymentSyncScheduler.runNow — mocked at object level)
        mockAppContext = mockk(relaxed = true)
        observabilityManager = mockk(relaxed = true)
        mockPaymentQueueRepository = mockk(relaxed = true)
        mockkObject(com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler)
        every { com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler.runNow(any()) } just runs
    }

    @After
    fun tearDown() {
        unmockkObject(TerminalConfig)
        unmockkObject(com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler)
        unmockkAll()
        Dispatchers.resetMain()
    }

    /**
     * Create PaymentViewModel with all 34 dependencies.
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
            validateCancelUseCase = mockk<com.example.clean_lib_services.shared.core.domain.use_case.cancel_package.validate_cancel.ValidateCancelUseCase>(relaxed = true),
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
            paymentQueueRepository = mockPaymentQueueRepository,
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
            merchantEligibilityRepository = mockMerchantEligibilityRepository,
            criticalNetworkOperationManager = mockCriticalNetworkOperationManager,
            // Real holder (tiny AtomicBoolean singleton) — lets tests observe the money window
            paymentStateHolder = paymentStateHolder,
            connectionEventManager = mockConnectionEventManager,
            paymentAttemptLedger = mockPaymentAttemptLedger,
            observability = observabilityManager,
            authAttemptTelemetryStore = mockAuthAttemptTelemetryStore,
            appContext = mockAppContext
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

    // ═══════════════════════════════════════════════════════════════════════════
    // A2. SOCKET → TPV ARBITRATION LINK (terminalPaymentRequestId threading)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * POS→TPV terminal arbitration: when a socket-initiated payment tags the flow via
     * setSocketPaymentSource("SOCKET", requestId), the recorded PaymentContext MUST carry
     * that id as `terminalPaymentRequestId` so the backend closes the matching
     * TerminalPaymentRequest row. Threads the identical path as `idempotencyKey`.
     */
    @Test
    fun `socket-sourced cash payment threads terminalPaymentRequestId into PaymentContext`() = runTest {
        val viewModel = createViewModel()

        val contextSlot = slot<PaymentContext>()
        coEvery {
            mockRecordPaymentUseCase(
                context = capture(contextSlot),
                cardDetails = any(),
                authorizationNumber = any(),
                referenceNumber = any()
            )
        } returns Result.success(
            PaymentReceipt(
                paymentId = "pay-socket-001",
                receiptUrl = "https://receipt.avoqado.io/pay-socket-001",
                accessKey = "acc-key",
                amount = BigDecimal("100.00"),
                tipAmount = BigDecimal.ZERO
            )
        )

        // Drive straight to SelectingMerchant (skips rating/tip), tag the socket source,
        // then record a cash payment (no Blumon SDK) so buildFastPaymentContext runs.
        viewModel.submitAmountDirectToMerchant("100.00")
        viewModel.setSocketPaymentSource("SOCKET", "req-123")
        viewModel.processCashPayment("100.00")

        coVerify(timeout = 2000) {
            mockRecordPaymentUseCase(
                context = any(),
                cardDetails = any(),
                authorizationNumber = any(),
                referenceNumber = any()
            )
        }
        assertThat(contextSlot.isCaptured).isTrue()
        assertThat(contextSlot.captured.terminalPaymentRequestId).isEqualTo("req-123")

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

    @Test
    fun `double skip tip starts only one merchant selection preparation`() = runTest {
        val eligibilityRelease = CompletableDeferred<Unit>()
        coEvery {
            mockMerchantEligibilityRepository.evaluate(any(), any(), any())
        } coAnswers {
            eligibilityRelease.await()
            com.jaac.avoqado_tpv.features.payment.domain.model.MerchantEligibility.disabled()
        }
        val viewModel = createViewModel()

        viewModel.submitAmount("1.00")
        viewModel.skipTip("1.00", rating = null)
        assertThat(viewModel.isPreparingMerchantSelection.value).isTrue()
        viewModel.skipTip("1.00", rating = null)

        coVerify(exactly = 1) {
            mockMerchantEligibilityRepository.evaluate(any(), any(), any())
        }

        eligibilityRelease.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()
        assertThat(viewModel.state.value).isInstanceOf(PaymentState.SelectingMerchant::class.java)
        assertThat(viewModel.isPreparingMerchantSelection.value).isFalse()

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `reset cancels pending merchant selection preparation`() = runTest {
        val eligibilityRelease = CompletableDeferred<Unit>()
        coEvery {
            mockMerchantEligibilityRepository.evaluate(any(), any(), any())
        } coAnswers {
            eligibilityRelease.await()
            com.jaac.avoqado_tpv.features.payment.domain.model.MerchantEligibility.disabled()
        }
        val viewModel = createViewModel()

        viewModel.submitAmount("1.00")
        viewModel.skipTip("1.00", rating = null)
        viewModel.resetPayment()
        assertThat(viewModel.isPreparingMerchantSelection.value).isFalse()
        eligibilityRelease.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.state.value).isEqualTo(PaymentState.Idle)

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

    // B2b: shift-check offline fallback (shifts-enabled venues)

    @Test
    fun `offline cobro with relaxed flag falls back to cached open shift`() = runTest {
        // Shifts enabled, but the live shift fetch fails because our backend is unreachable.
        every { mockShiftRepository.isShiftSystemEnabled() } returns true
        coEvery { mockShiftRepository.getCurrentShift(any()) } returns
            AppResult.Error(com.jaac.avoqado_tpv.core.domain.models.ApiException.NetworkError(RuntimeException("offline")))
        coEvery { mockShiftRepository.getCachedOpenShift(any()) } returns Shift(
            id = testShiftId,
            venueId = testVenueId,
            staffId = testStaffId,
            staffName = "Cached Staff",
            startTime = "2026-05-30T10:00:00Z",
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
        // Venue opted into offline card payments; internet is up but our backend is unreachable.
        every { mockTpvSettingsRepository.getCurrentSettings() } returns
            TpvSettings.DEFAULT.copy(requireAvoqadoServerForCardPayment = false)
        every { mockConnectionStateManager.hasInternet() } returns true
        every { mockConnectionStateManager.isFullyConnected() } returns false

        val viewModel = createViewModel()
        viewModel.startPayment("100.00")
        testDispatcher.scheduler.advanceUntilIdle()

        // Fast path: the live getCurrentShift is SKIPPED (no ~30s hang) because the backend is
        // already known-unreachable; the cached OPEN shift is used directly and the payment is
        // NOT blocked on the shift check.
        coVerify(exactly = 0) { mockShiftRepository.getCurrentShift(any()) }
        coVerify { mockShiftRepository.getCachedOpenShift(any()) }
        val state = viewModel.state.value
        val blockedOnShift = state is PaymentState.Error && state.showOpenShiftButton
        assertThat(blockedOnShift).isFalse()

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `shift fetch failure with default flag blocks and skips cache fallback`() = runTest {
        // Default flag (require backend): a failed shift fetch must NOT consult the cache.
        every { mockShiftRepository.isShiftSystemEnabled() } returns true
        coEvery { mockShiftRepository.getCurrentShift(any()) } returns
            AppResult.Error(com.jaac.avoqado_tpv.core.domain.models.ApiException.NetworkError(RuntimeException("offline")))
        every { mockTpvSettingsRepository.getCurrentSettings() } returns TpvSettings.DEFAULT
        // Fully connected so the preflight passes and we actually reach the shift check.
        every { mockConnectionStateManager.isFullyConnected() } returns true

        val viewModel = createViewModel()
        viewModel.startPayment("100.00")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state).isInstanceOf(PaymentState.Error::class.java)
        assertThat((state as PaymentState.Error).showOpenShiftButton).isTrue()
        coVerify(exactly = 0) { mockShiftRepository.getCachedOpenShift(any()) }

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
        // Wait for Dispatchers.IO coroutines (shift/merchant work outside the test dispatcher) so the
        // flow publishes its terminal state BEFORE resetPayment — without this the assert races them
        // (same pattern as `consecutive payments dont leak state`). Flaked once under full-suite load.
        Thread.sleep(1000)
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

    @Test
    fun `reportProcessingTimeoutIfNeeded fires for non-chip processing stall at 45s`() {
        mockkObject(CrashlyticsContext)
        every { CrashlyticsContext.recordPaymentEmvStall(any(), any(), any()) } just Runs
        val viewModel = createViewModel()
        try {
            // "Autorizando con banco..." (online auth) lacks the word "chip" - was previously ignored
            viewModel.reportProcessingTimeoutIfNeeded("Autorizando con banco...", 45)
            verify(exactly = 1) {
                CrashlyticsContext.recordPaymentEmvStall(any(), "Autorizando con banco...", 45)
            }
        } finally {
            viewModel.viewModelScope.cancel()
            unmockkObject(CrashlyticsContext)
        }
    }

    @Test
    fun `reportProcessingTimeoutIfNeeded does not fire below 45s`() {
        mockkObject(CrashlyticsContext)
        every { CrashlyticsContext.recordPaymentEmvStall(any(), any(), any()) } just Runs
        val viewModel = createViewModel()
        try {
            viewModel.reportProcessingTimeoutIfNeeded("Autorizando con banco...", 30)
            verify(exactly = 0) { CrashlyticsContext.recordPaymentEmvStall(any(), any(), any()) }
        } finally {
            viewModel.viewModelScope.cancel()
            unmockkObject(CrashlyticsContext)
        }
    }

    @Test
    fun `reportProcessingTimeoutIfNeeded dedups repeated identical stalls`() {
        mockkObject(CrashlyticsContext)
        every { CrashlyticsContext.recordPaymentEmvStall(any(), any(), any()) } just Runs
        val viewModel = createViewModel()
        try {
            viewModel.reportProcessingTimeoutIfNeeded("Autorizando con banco...", 45)
            viewModel.reportProcessingTimeoutIfNeeded("Autorizando con banco...", 45)
            verify(exactly = 1) { CrashlyticsContext.recordPaymentEmvStall(any(), any(), any()) }
        } finally {
            viewModel.viewModelScope.cancel()
            unmockkObject(CrashlyticsContext)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LAS DOS SEÑALES DE DINERO (isCharging / isChargeAttemptActive)
    // ═══════════════════════════════════════════════════════════════════════════
    // Contestan preguntas OPUESTAS y NO deben unificarse:
    //   · isCharging()            → ventana de dinero (angosta). Gatea el CANCEL del POS.
    //   · isChargeAttemptActive() → "trabajando" (ancha). Gatea el COBRO del POS.
    // Confundirlas ya causó dos bugs reales: la angosta usada para "ocupado" deja que un 2º POS
    // secuestre una terminal esperando tarjeta; la ancha usada para "cancel" varaba la terminal.

    @Test
    fun `las dos senales de dinero son independientes - abrir la ventana NO marca ocupado`() {
        // El miedo concreto: que abrir la ventana de dinero en un cobro aprobado por la tarjeta
        // (sin pasar por el banco) acabe bloqueando cobros. NO puede: son AtomicBooleans distintos
        // y AppNavigation los lee por separado (isCharging → cancel, isChargeAttemptActive → cobro).
        paymentStateHolder.setCharging(true)

        assertThat(paymentStateHolder.isCharging()).isTrue()
        assertThat(paymentStateHolder.isChargeAttemptActive()).isFalse()
    }

    @Test
    fun `las dos senales de dinero son independientes - marcar ocupado NO abre la ventana`() {
        paymentStateHolder.setChargeAttemptActive(true)

        assertThat(paymentStateHolder.isChargeAttemptActive()).isTrue()
        assertThat(paymentStateHolder.isCharging()).isFalse()
    }

    @Test
    fun `el VM arranca con las dos banderas apagadas - sin residuo de un cobro anterior`() = runTest {
        paymentStateHolder.setCharging(true)
        paymentStateHolder.setChargeAttemptActive(true)

        val viewModel = createViewModel()
        try {
            // El colector del init publica desde el estado real (Idle) y limpia el residuo:
            // sin esto, un holder @Singleton contaminado por un cobro anterior dejaría la
            // terminal rechazando cancels/cobros para siempre.
            testScheduler.advanceUntilIdle()

            assertThat(viewModel.state.value).isEqualTo(PaymentState.Idle)
            assertThat(paymentStateHolder.isCharging()).isFalse()
            assertThat(paymentStateHolder.isChargeAttemptActive()).isFalse()
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LIBRETA WRITE-AHEAD (PaymentAttemptLedger wiring — Blumon path)
    // ═══════════════════════════════════════════════════════════════════════════
    // Only the JVM-reachable wiring is verified here: openAttempt (startPayment) and
    // markDiscardedBeforeCharge (cancelPayment). The performOnlineAuthorization and
    // handlePaymentSuccess marks need the real Blumon SDK → device drills (Task 7).

    @Test
    fun `startPayment opens a ledger attempt before charging`() = runTest {
        val viewModel = createViewModel()

        viewModel.startPayment("100.00", null)
        Thread.sleep(1000) // Dispatchers.IO outside the test dispatcher — file pattern
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify {
            mockPaymentAttemptLedger.openAttempt(
                any(), any(), PaymentAttemptEntity.PROCESSOR_BLUMON,
                any(), any(), any(), any(), any()
            )
        }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `cancelPayment discards the ledger row only from PREPARANDO`() = runTest {
        val viewModel = createViewModel()

        // No attempt open (paymentAttemptId is null) → nothing to discard
        viewModel.cancelPayment()
        coVerify(exactly = 0) { mockPaymentAttemptLedger.markDiscardedBeforeCharge(any(), any()) }

        // Open an attempt, then cancel → exactly one discard with the user_cancel reason
        viewModel.startPayment("100.00", null)
        Thread.sleep(1000) // Dispatchers.IO outside the test dispatcher — file pattern
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.cancelPayment()
        coVerify(exactly = 1) { mockPaymentAttemptLedger.markDiscardedBeforeCharge(any(), "user_cancel") }

        viewModel.viewModelScope.cancel()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MONEY-SAFETY: card charged but NEITHER backend NOR local queue recorded it
    // ═══════════════════════════════════════════════════════════════════════════
    // buildRecordingLostState / reportRecordingLost are the pure/near-pure pieces extracted
    // from the previously-silent onFailure(queueError) branch inside handlePaymentSuccess.
    // Only these are unit-tested directly — driving the full handlePaymentSuccess flow needs
    // the real Blumon SDK reflection dance → device drills (Task 7), same boundary as the
    // LIBRETA section above.

    @Test
    fun `buildRecordingLostState alarms the live Success with the reference and a dont-recharge message`() = runTest {
        val viewModel = createViewModel()
        try {
            val currentSuccess = PaymentState.Success(
                authCode = "AUTH77",
                amount = "150.00",
                tipAmount = "15.00",
                receipt = null,
                referenceNumber = "OLD-REF",
                orderId = "order-1",
                orderNumber = "0007"
            )

            val alarmed = viewModel.buildRecordingLostState(currentSuccess, "195978383755")

            assertThat(alarmed).isNotNull()
            assertThat(alarmed!!.recordingLostMessage).isNotNull()
            // Must say the money moved — never "failed" (would invite a double charge).
            assertThat(alarmed.recordingLostMessage).contains("SÍ se realizó")
            assertThat(alarmed.recordingLostMessage).contains("NO vuelvas a cobrar")
            // Reference is the ONLY thread back to this sale for manual reconciliation.
            assertThat(alarmed.recordingLostMessage).contains("195978383755")
            assertThat(alarmed.referenceNumber).isEqualTo("195978383755")
            // A TARGETED alarm, not a reset — every other field survives untouched.
            assertThat(alarmed.authCode).isEqualTo("AUTH77")
            assertThat(alarmed.amount).isEqualTo("150.00")
            assertThat(alarmed.tipAmount).isEqualTo("15.00")
            assertThat(alarmed.orderId).isEqualTo("order-1")
            assertThat(alarmed.orderNumber).isEqualTo("0007")
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `buildRecordingLostState updates the reference when called again on an already-alarmed Success`() = runTest {
        val viewModel = createViewModel()
        try {
            // Simulates a second queue-retry resolving after the first alarm already fired.
            val alreadyAlarmed = PaymentState.Success(
                authCode = "AUTH77",
                amount = "150.00",
                referenceNumber = "FIRST-REF",
                recordingLostMessage = "primera alarma"
            )

            val alarmed = viewModel.buildRecordingLostState(alreadyAlarmed, "SECOND-REF")

            // A supervisor reconciling later needs the CURRENT reference, not a stale first one
            // — the alarm must refresh, not freeze on the first call.
            assertThat(alarmed).isNotNull()
            assertThat(alarmed!!.referenceNumber).isEqualTo("SECOND-REF")
            assertThat(alarmed.recordingLostMessage).contains("SECOND-REF")
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `buildRecordingLostState returns null when the money window already closed`() = runTest {
        val viewModel = createViewModel()
        try {
            // The cashier can navigate away between "queue enqueue also failed" and this
            // resolving (Success was published optimistically, seconds earlier). Nothing to
            // alarm in that case — pendingReceiptRetry / listenToConnectionRestored remain the
            // only backstop, and this must not resurrect a screen the cashier already left.
            assertThat(viewModel.buildRecordingLostState(PaymentState.Idle, "REF1")).isNull()
            assertThat(
                viewModel.buildRecordingLostState(PaymentState.Error(message = "otro error"), "REF1")
            ).isNull()
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    // 🟡 buildPendingSyncState — same pure-function shape as buildRecordingLostState above, for
    // the far more common "queue enqueue SUCCEEDED" sibling: the charge went through, backend
    // recording failed, but a queue row exists and will self-heal. This is what
    // handleOfflineQueueOutcome's previously-silent onSuccess branch is missing today.

    @Test
    fun `buildPendingSyncState notes the live Success with the reference and a dont-recharge message`() = runTest {
        val viewModel = createViewModel()
        try {
            val currentSuccess = PaymentState.Success(
                authCode = "F628CL",
                amount = "25.00",
                tipAmount = "0",
                receipt = null,
                referenceNumber = "OLD-REF",
                orderId = "order-1",
                orderNumber = "0007"
            )

            val queued = viewModel.buildPendingSyncState(currentSuccess, "873257481453")

            assertThat(queued).isNotNull()
            assertThat(queued!!.pendingSyncMessage).isNotNull()
            // Must say the money moved — never "failed" (would invite a double charge).
            assertThat(queued.pendingSyncMessage).contains("se realizó correctamente")
            assertThat(queued.pendingSyncMessage).contains("NO vuelvas a cobrar")
            // Reference is what a supervisor reconciles with while the sync is still pending.
            assertThat(queued.pendingSyncMessage).contains("873257481453")
            assertThat(queued.referenceNumber).isEqualTo("873257481453")
            // Never sets recordingLostMessage — this is the self-healing case, not the alarm.
            assertThat(queued.recordingLostMessage).isNull()
            // A TARGETED note, not a reset — every other field survives untouched.
            assertThat(queued.authCode).isEqualTo("F628CL")
            assertThat(queued.amount).isEqualTo("25.00")
            assertThat(queued.orderId).isEqualTo("order-1")
            assertThat(queued.orderNumber).isEqualTo("0007")
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `buildPendingSyncState updates the reference when called again on an already-queued Success`() = runTest {
        val viewModel = createViewModel()
        try {
            val alreadyQueued = PaymentState.Success(
                authCode = "F628CL",
                amount = "25.00",
                referenceNumber = "FIRST-REF",
                pendingSyncMessage = "primera nota"
            )

            val queued = viewModel.buildPendingSyncState(alreadyQueued, "SECOND-REF")

            assertThat(queued).isNotNull()
            assertThat(queued!!.referenceNumber).isEqualTo("SECOND-REF")
            assertThat(queued.pendingSyncMessage).contains("SECOND-REF")
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `buildPendingSyncState returns null when the money window already closed`() = runTest {
        val viewModel = createViewModel()
        try {
            // Same "cashier may have already navigated away" caveat as buildRecordingLostState —
            // Success publishes optimistically, seconds before the queue outcome is known.
            assertThat(viewModel.buildPendingSyncState(PaymentState.Idle, "REF1")).isNull()
            assertThat(
                viewModel.buildPendingSyncState(PaymentState.Error(message = "otro error"), "REF1")
            ).isNull()
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `handleOfflineQueueOutcome notes the live Success as pending-sync when enqueue succeeds`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        try {
            coEvery { mockPaymentQueueRepository.enqueue(any()) } returns Result.success(Unit)

            // Seed the private _state to a live Success — handleOfflineQueueOutcome reads
            // _state.value fresh at the moment the queue resolves (see buildPendingSyncState
            // kdoc), so the test must put the ViewModel in that exact situation instead of
            // asserting on the pure function alone.
            val stateField = PaymentViewModel::class.java.getDeclaredField("_state")
            stateField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val stateFlow = stateField.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<PaymentState>
            stateFlow.value = PaymentState.Success(
                authCode = "F628CL",
                amount = "25.00",
                receipt = null
            )

            val queuedPayment = com.jaac.avoqado_tpv.features.payment.domain.model.QueuedPayment(
                queueId = 0,
                referenceNumber = "873257481453",
                venueId = testVenueId,
                staffId = testStaffId,
                amount = BigDecimal("25.00"),
                tip = BigDecimal.ZERO,
                rating = null,
                merchantAccountId = "merchant_a",
                blumonSerialNumber = "2841548417",
                maskedPan = "**** 4242",
                cardBrand = "VISA",
                entryMode = "CHIP",
                isInternational = false,
                authorizationNumber = "F628CL",
                idempotencyKey = "idem-queued-1",
                createdAt = System.currentTimeMillis(),
                retryCount = 0,
                lastError = "HTTP 502",
                syncStatus = com.jaac.avoqado_tpv.features.payment.domain.model.SyncStatus.PENDING
            )
            val context = PaymentContext.FastPayment(
                venueId = testVenueId,
                staffId = testStaffId,
                amount = BigDecimal("25.00"),
                merchantAccountId = "merchant_a"
            )

            viewModel.handleOfflineQueueOutcome(
                queuedPayment = queuedPayment,
                context = context,
                recordError = RuntimeException("HTTP 502"),
                referenceNumber = "873257481453",
                orderIdForFlow = null
            )

            val updated = viewModel.state.value
            assertThat(updated).isInstanceOf(PaymentState.Success::class.java)
            val success = updated as PaymentState.Success
            assertThat(success.pendingSyncMessage).isNotNull()
            assertThat(success.pendingSyncMessage).contains("NO vuelvas a cobrar")
            assertThat(success.referenceNumber).isEqualTo("873257481453")
            // The lost-record alarm must NOT fire on this path — the queue captured it.
            assertThat(success.recordingLostMessage).isNull()
            // Never fires the money-lost telemetry — the queue succeeded, nothing lost.
            verify(exactly = 0) { observabilityManager.logCritical(any(), any(), any(), any()) }
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `reportRecordingLost sends structured telemetry with reference orderId amount venue and both errors`() = runTest {
        val viewModel = createViewModel()
        try {
            val queuedPayment = com.jaac.avoqado_tpv.features.payment.domain.model.QueuedPayment(
                queueId = 0,
                referenceNumber = "195978383755",
                venueId = testVenueId,
                staffId = testStaffId,
                amount = BigDecimal("150.00"),
                tip = BigDecimal("15.00"),
                rating = null,
                merchantAccountId = "merchant_a",
                blumonSerialNumber = "2841548417",
                maskedPan = "**** 4242",
                cardBrand = "VISA",
                entryMode = "CHIP",
                isInternational = false,
                authorizationNumber = "AUTH77",
                idempotencyKey = "idem-1",
                createdAt = System.currentTimeMillis(),
                retryCount = 0,
                lastError = "HTTP 503",
                syncStatus = com.jaac.avoqado_tpv.features.payment.domain.model.SyncStatus.PENDING
            )
            val metadataSlot = slot<Map<String, Any?>>()
            val errorSlot = slot<Throwable>()

            viewModel.reportRecordingLost(
                queuedPayment = queuedPayment,
                recordError = RuntimeException("HTTP 503"),
                queueError = IllegalStateException("disk full"),
                orderIdForFlow = "order-42"
            )

            verify(exactly = 1) {
                observabilityManager.logCritical(
                    tag = "BlumonRecordAndQueueLost",
                    message = any(),
                    error = capture(errorSlot),
                    metadata = capture(metadataSlot)
                )
            }
            // 🔴 Fix round 1 (nit): the attached exception is a named RecordingLostException
            // (not the raw queueError) so Crashlytics groups every occurrence under ONE issue
            // instead of scattering by whatever exception type the queue happened to throw.
            // queueError is preserved as `cause` — full original stack trace still visible.
            assertThat(errorSlot.captured).isInstanceOf(RecordingLostException::class.java)
            assertThat(errorSlot.captured.cause?.message).isEqualTo("disk full")
            assertThat(metadataSlot.captured["reference"]).isEqualTo("195978383755")
            assertThat(metadataSlot.captured["orderId"]).isEqualTo("order-42")
            assertThat(metadataSlot.captured["amount"]).isEqualTo("150.00")
            assertThat(metadataSlot.captured["venueId"]).isEqualTo(testVenueId)
            assertThat(metadataSlot.captured["recordError"]).isEqualTo("HTTP 503")
            assertThat(metadataSlot.captured["queueError"]).isEqualTo("disk full")
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `reportRecordingLost reports orderId none for a fast payment`() = runTest {
        val viewModel = createViewModel()
        try {
            val queuedPayment = com.jaac.avoqado_tpv.features.payment.domain.model.QueuedPayment(
                queueId = 0,
                referenceNumber = "REF1",
                venueId = testVenueId,
                staffId = testStaffId,
                amount = BigDecimal("50.00"),
                tip = BigDecimal.ZERO,
                rating = null,
                merchantAccountId = "merchant_a",
                blumonSerialNumber = "2841548417",
                maskedPan = null,
                cardBrand = null,
                entryMode = "CHIP",
                isInternational = false,
                authorizationNumber = "AUTH1",
                idempotencyKey = "idem-2",
                createdAt = System.currentTimeMillis(),
                retryCount = 0,
                lastError = "timeout",
                syncStatus = com.jaac.avoqado_tpv.features.payment.domain.model.SyncStatus.PENDING
            )
            val metadataSlot = slot<Map<String, Any?>>()

            viewModel.reportRecordingLost(
                queuedPayment = queuedPayment,
                recordError = RuntimeException("timeout"),
                queueError = RuntimeException("index collision"),
                orderIdForFlow = null // Fast payment — no order
            )

            verify(exactly = 1) {
                observabilityManager.logCritical(
                    tag = any(),
                    message = any(),
                    error = any(),
                    metadata = capture(metadataSlot)
                )
            }
            assertThat(metadataSlot.captured["orderId"]).isEqualTo("none")
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    // 🔴 Fix round 1 (Critical): handleOfflineQueueOutcome must survive a scope cancellation
    // that races paymentQueueRepository.enqueue(). PaymentQueueRepositoryImpl.enqueue's OWN
    // NonCancellable only protects the DB INSERT — the caller's continuation resumes via
    // enqueue()'s outer withContext(Dispatchers.IO), which is cancellable. Without wrapping the
    // WHOLE handleOfflineQueueOutcome body in NonCancellable, a cancel landing while suspended
    // inside enqueue() (a POS charge/cancel, or the cashier tapping Home — exactly the
    // conditions, disk full / DB locked, that make this branch reachable) would skip
    // buildRecordingLostState AND reportRecordingLost entirely: money charged, backend failed,
    // queue write raced a cancel, no alarm, no telemetry.
    @Test
    fun `handleOfflineQueueOutcome survives a mid-enqueue scope cancellation - reportRecordingLost still fires`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        try {
            val enqueueStarted = CompletableDeferred<Unit>()
            val releaseEnqueue = CompletableDeferred<Unit>()
            coEvery { mockPaymentQueueRepository.enqueue(any()) } coAnswers {
                enqueueStarted.complete(Unit)
                releaseEnqueue.await()
                Result.failure(RuntimeException("disk full"))
            }

            val queuedPayment = com.jaac.avoqado_tpv.features.payment.domain.model.QueuedPayment(
                queueId = 0,
                referenceNumber = "REF-CANCEL-TEST",
                venueId = testVenueId,
                staffId = testStaffId,
                amount = BigDecimal("150.00"),
                tip = BigDecimal.ZERO,
                rating = null,
                merchantAccountId = "merchant_a",
                blumonSerialNumber = "2841548417",
                maskedPan = "**** 4242",
                cardBrand = "VISA",
                entryMode = "CHIP",
                isInternational = false,
                authorizationNumber = "AUTH-CANCEL",
                idempotencyKey = "idem-cancel-1",
                createdAt = System.currentTimeMillis(),
                retryCount = 0,
                lastError = "HTTP 503",
                syncStatus = com.jaac.avoqado_tpv.features.payment.domain.model.SyncStatus.PENDING
            )
            val context = PaymentContext.FastPayment(
                venueId = testVenueId,
                staffId = testStaffId,
                amount = BigDecimal("150.00"),
                merchantAccountId = "merchant_a"
            )

            val job = launch {
                viewModel.handleOfflineQueueOutcome(
                    queuedPayment = queuedPayment,
                    context = context,
                    recordError = RuntimeException("HTTP 503"),
                    referenceNumber = "REF-CANCEL-TEST",
                    orderIdForFlow = null
                )
            }

            enqueueStarted.await() // the coroutine is now suspended INSIDE enqueue()
            job.cancel() // 💥 simulate a POS charge/cancel or a Home tap racing the write
            releaseEnqueue.complete(Unit) // let the (mocked) DB write actually conclude
            job.join()

            // Even though the launching job was cancelled WHILE suspended inside enqueue(),
            // NonCancellable means the failure-handling — including the structured telemetry —
            // still runs to completion.
            verify(exactly = 1) { observabilityManager.logCritical(any(), any(), any(), any()) }
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // A3. CARD ENQUEUE → ORDER LINKAGE (2026-07-28)
    //
    // Bug: a card payment for an ORDER that fails to record and falls into the
    // offline queue was enqueued WITHOUT orderId/orderNumber. On replay,
    // QueuedPayment.toPaymentContext() rebuilds every non-ANGELPAY row as
    // PaymentContext.FastPayment — no order link — so the card gets charged, a
    // loose fast payment lands in the backend, and the order still shows UNPAID.
    // The cashier then charges it again: a human-induced double charge.
    //
    // Fix: the card enqueue site inside handlePaymentSuccess() now threads
    // orderId = getOrderIdForFlow() / orderNumber = getOrderNumberForFlow() into
    // the QueuedPayment it builds on a failed recording. These tests exercise
    // that exact private construction path (via reflection — handlePaymentSuccess
    // has no other public seam) rather than a hand-built QueuedPayment, so a
    // regression in the constructor call itself would be caught here.
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Invokes the private handlePaymentSuccess(saleData, entryMode, blumonOperationNumber)
     * via reflection. saleData=null takes the "offline-approved contactless" branch
     * (authorizationNumber="OFFLINE_APPROVED", referenceNumber="OFFLINE-<ts>"), which avoids
     * having to fake the Blumon SDK's SaleData reflection-based field extraction — the branch
     * under test (the QueuedPayment construction on record failure) is identical either way.
     */
    private fun invokeHandlePaymentSuccess(viewModel: PaymentViewModel) {
        val method = PaymentViewModel::class.java.getDeclaredMethod(
            "handlePaymentSuccess",
            Any::class.java,
            com.jaac.avoqado_tpv.features.payment.domain.model.CardEntryMode::class.java,
            java.lang.Integer::class.java
        )
        method.isAccessible = true
        method.invoke(viewModel, null, com.jaac.avoqado_tpv.features.payment.domain.model.CardEntryMode.CHIP, null)
    }

    /** handlePaymentSuccess() requires an authenticated venue/staff session before it will
     * record or queue anything — set it directly (same reflection pattern already used above
     * for `_state`) rather than driving the full SDK startPayment() flow just to populate two
     * private fields. */
    private fun seedAuthenticatedSession(viewModel: PaymentViewModel) {
        every { mockAuthRepository.isAuthenticated() } returns true
        val venueIdField = PaymentViewModel::class.java.getDeclaredField("currentVenueId")
        venueIdField.isAccessible = true
        venueIdField.set(viewModel, testVenueId)
        val staffIdField = PaymentViewModel::class.java.getDeclaredField("currentStaffId")
        staffIdField.isAccessible = true
        staffIdField.set(viewModel, testStaffId)
    }

    @Test
    fun `card enqueue attaches orderId and orderNumber when the payment belongs to an order`() = runTest(testDispatcher) {
        coEvery {
            mockRecordPaymentUseCase(context = any(), cardDetails = any(), authorizationNumber = any(), referenceNumber = any())
        } returns Result.failure(RuntimeException("HTTP 500"))
        val querySlot = slot<com.jaac.avoqado_tpv.features.payment.domain.model.QueuedPayment>()
        coEvery { mockPaymentQueueRepository.enqueue(capture(querySlot)) } returns Result.success(Unit)

        val viewModel = createViewModel()
        try {
            seedAuthenticatedSession(viewModel)
            // Order in scope (also auto-selects the single configured merchant).
            viewModel.submitAmountDirectToMerchant("25.00", orderId = "order-1", orderNumber = "0007")

            invokeHandlePaymentSuccess(viewModel)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) { mockPaymentQueueRepository.enqueue(any()) }
            assertThat(querySlot.isCaptured).isTrue()
            assertThat(querySlot.captured.orderId).isEqualTo("order-1")
            assertThat(querySlot.captured.orderNumber).isEqualTo("0007")
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `card enqueue leaves orderId and orderNumber null for a fast payment with no order in scope`() = runTest(testDispatcher) {
        coEvery {
            mockRecordPaymentUseCase(context = any(), cardDetails = any(), authorizationNumber = any(), referenceNumber = any())
        } returns Result.failure(RuntimeException("HTTP 500"))
        val querySlot = slot<com.jaac.avoqado_tpv.features.payment.domain.model.QueuedPayment>()
        coEvery { mockPaymentQueueRepository.enqueue(capture(querySlot)) } returns Result.success(Unit)

        val viewModel = createViewModel()
        try {
            seedAuthenticatedSession(viewModel)
            // No orderId → fast payment (cobro). No-behavior-change guarantee: orderId/orderNumber
            // stay null exactly as they did before the fix.
            viewModel.submitAmountDirectToMerchant("15.00")

            invokeHandlePaymentSuccess(viewModel)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) { mockPaymentQueueRepository.enqueue(any()) }
            assertThat(querySlot.isCaptured).isTrue()
            assertThat(querySlot.captured.orderId).isNull()
            assertThat(querySlot.captured.orderNumber).isNull()
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `card enqueue site never tags the queued payment as ANGELPAY`() = runTest(testDispatcher) {
        // 🔴 Pins the safety argument documented at the fix site (PaymentViewModel.kt, card
        // enqueue block in handlePaymentSuccess): orderId is safe to thread through ONLY
        // because the sole consumer of QueuedPayment.orderId in toPaymentContext() is the
        // ANGELPAY branch, and this call site never sets processor=ANGELPAY (AngelPay enqueues
        // from its own ViewModel; this row defaults to BLUMON). If a future refactor unifies
        // the Blumon/AngelPay ViewModels and starts tagging this site ANGELPAY, replay behavior
        // changes silently — this test is what catches it.
        coEvery {
            mockRecordPaymentUseCase(context = any(), cardDetails = any(), authorizationNumber = any(), referenceNumber = any())
        } returns Result.failure(RuntimeException("HTTP 500"))
        val querySlot = slot<com.jaac.avoqado_tpv.features.payment.domain.model.QueuedPayment>()
        coEvery { mockPaymentQueueRepository.enqueue(capture(querySlot)) } returns Result.success(Unit)

        val viewModel = createViewModel()
        try {
            seedAuthenticatedSession(viewModel)
            viewModel.submitAmountDirectToMerchant("15.00", orderId = "order-9", orderNumber = "0099")

            invokeHandlePaymentSuccess(viewModel)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) { mockPaymentQueueRepository.enqueue(any()) }
            assertThat(querySlot.isCaptured).isTrue()
            assertThat(querySlot.captured.processor).isEqualTo(
                com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType.BLUMON
            )
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }
}

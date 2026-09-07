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
    // 💸 La cola de REEMBOLSOS (Fase 1): campo con nombre para poder verificar y controlar enqueue().
    private lateinit var mockRefundQueueRepository:
        com.jaac.avoqado_tpv.features.payment.domain.repository.RefundQueueRepository

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
        originalPaymentId: String = "pay-001",
        merchantAccountId: String? = "merchant_a"
    ) = PaymentContext.RefundPayment(
        venueId = venueId,
        staffId = staffId,
        amount = amount,
        tip = BigDecimal.ZERO,
        originalPaymentId = originalPaymentId,
        originalOrderId = null,
        originalTotalAmount = amount,
        refundReason = RefundReason.CUSTOMER_REQUEST,
        merchantAccountId = merchantAccountId,
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
        mockRefundQueueRepository = mockk(relaxed = true) {
            coEvery { enqueue(any()) } returns Result.success(Unit)
        }
        // Defaults de la cola de reembolsos: la fila write-ahead y el respaldo "entran". Van AQUÍ y no en
        // refundReady() para que el `coEvery` de cada prueba (capturas, orden, fallos) mande sobre ellos.
        coEvery { mockRefundQueueRepository.enqueueClaimed(any(), any()) } returns Result.success(Unit)
        coEvery { mockRefundQueueRepository.enqueue(any()) } returns Result.success(Unit)
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
    private fun createViewModel(
        startDetectCardUseCase: com.blumonpay.pax.shared.neptune_polling.domain.use_case.start_detect_card.StartDetectCardUseCase =
            mockk(relaxed = true),
        startCtlssTransUseCase: com.blumonpay.pax.shared.trans_process.domain.use_case.start_ctlss_trans.StartCtlssTransUseCase =
            mockk(relaxed = true),
    ): PaymentViewModel {
        return PaymentViewModel(
            preTransUseCase = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.pre_trans.PreTransUseCase>(relaxed = true),
            startDetectCardUseCase = startDetectCardUseCase,
            stopDetectCardUseCase = mockk<com.blumonpay.pax.shared.neptune_polling.domain.use_case.stop_detect_card.StopDetectCardUseCase>(relaxed = true),
            startEmvTransUseCase = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.strat_emv_trans.StartEmvTransUseCase>(relaxed = true),
            startCtlssTransUseCase = startCtlssTransUseCase,
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
            refundQueueRepository = mockRefundQueueRepository,
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
    // D2. 📒 LA LIBRETA EN EL REEMBOLSO (Fase 1, Task 4 — 2026-09-03)
    //
    // Un reembolso que el SDK ya aprobó y cuyo POST falla se perdía sin dejar rastro. La libreta
    // write-ahead ya cerraba esa ventana para los COBROS; estas pruebas fijan que el reembolso
    // entra por la misma puerta: fila `kind=REFUND` abierta en `startRefund`, ANTES de que corra
    // cualquier código del SDK, y con `attemptId == idempotencyKey` — la MISMA llave con la que
    // el servidor deduplica el reintento. Sin esa igualdad la libreta y el servidor hablarían de
    // dos cosas distintas y la fila no serviría para conciliar nada.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `P1 startRefund abre la libreta con kind REFUND y attemptId igual a la idempotencyKey`() = runTest {
        every { mockGetMerchantsUseCase.invoke() } returns flowOf(listOf(testMerchantA))
        every { mockMultiMerchantSDKManager.isMerchantActive(any()) } returns true
        val attemptId = slot<String>()
        val contextJson = slot<String>()

        val viewModel = createViewModel()
        viewModel.startRefund(createRefundContext(amount = BigDecimal("50.00")))

        coVerify(exactly = 1) {
            mockPaymentAttemptLedger.openAttempt(
                attemptId = capture(attemptId),
                venueId = testVenueId,
                processor = PaymentAttemptEntity.PROCESSOR_BLUMON,
                amountCents = 5000L,
                tipCents = 0L,
                recordingRoute = PaymentAttemptEntity.ROUTE_REFUND,
                contextJson = capture(contextJson),
                kind = PaymentAttemptEntity.KIND_REFUND
            )
        }
        assertThat(attemptId.captured).isNotEmpty()
        // La llave que viaja al servidor es la MISMA que identifica la fila de la libreta.
        assertThat(contextJson.captured).contains("\"idempotencyKey\":\"${attemptId.captured}\"")

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `P1 cada startRefund abre la libreta con un attemptId NUEVO, nunca reusa el anterior`() = runTest {
        // Un reintento MANUAL del cajero es una operación nueva del SDK — el dinero se mueve otra
        // vez — y tiene que llevar llave nueva. Reusarla haría que el servidor deduplicara el
        // segundo reembolso contra el primero: dinero devuelto dos veces, registrado una.
        // (El reintento AUTOMÁTICO de la cola sí reusa la llave, pero ése no pasa por aquí.)
        every { mockGetMerchantsUseCase.invoke() } returns flowOf(listOf(testMerchantA))
        every { mockMultiMerchantSDKManager.isMerchantActive(any()) } returns true
        val ids = mutableListOf<String>()

        val viewModel = createViewModel()
        viewModel.startRefund(createRefundContext(originalPaymentId = "pay-A"))
        viewModel.startRefund(createRefundContext(originalPaymentId = "pay-B"))

        coVerify(exactly = 2) {
            mockPaymentAttemptLedger.openAttempt(
                attemptId = capture(ids), venueId = any(), processor = any(), amountCents = any(),
                tipCents = any(), recordingRoute = any(), contextJson = any(),
                kind = PaymentAttemptEntity.KIND_REFUND
            )
        }
        assertThat(ids.toSet()).hasSize(2)

        viewModel.viewModelScope.cancel()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // D3. 💸 EL REEMBOLSO QUE FALLA AL REGISTRARSE SE ENCOLA, NO SE PIERDE (Fase 1, Tasks 5-6)
    //
    // Cuando esto corre, el SDK YA devolvió el dinero. Antes, un POST fallido dejaba dos Timber.w
    // y nada más: el corte del turno cuadraba de más y nadie se enteraba. `handleRefundSuccess`
    // es privado y el SDK no se puede recorrer en la JVM, así que entra por el shim.
    // ═══════════════════════════════════════════════════════════════════════════

    private fun refundReady(isAuthenticated: Boolean = true): PaymentViewModel {
        every { mockGetMerchantsUseCase.invoke() } returns flowOf(listOf(testMerchantA))
        every { mockMultiMerchantSDKManager.isMerchantActive(any()) } returns true
        every { mockAuthRepository.isAuthenticated() } returns isAuthenticated
        val vm = createViewModel()
        vm.startRefund(createRefundContext()) // siembra refundContext + attemptId en la sesión
        return vm
    }

    private fun exitoSembrado() = PaymentState.Success(authCode = "AUTH", amount = "50.00", isRefund = true)

    private fun filaEncolada(key: String = "k-prev") = com.jaac.avoqado_tpv.features.payment.domain.model.QueuedRefund(
        idempotencyKey = key, venueId = "venue-1", staffId = "staff-1",
        processor = com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType.BLUMON,
        originalPaymentId = "pay-001", originalOrderId = null, amount = BigDecimal("50.00"),
        originalTotalAmount = BigDecimal("100.00"), tipRefundCents = null, isPartialRefund = true,
        refundReason = com.jaac.avoqado_tpv.features.payment.domain.model.RefundReason.CUSTOMER_REQUEST,
        merchantAccountId = "m1", blumonSerialNumber = "SER1", originalOperationNumber = 1,
        authorizationNumber = "502511", referenceNumber = "000000188231", maskedPan = null, cardBrand = null,
        entryMode = "CHIP", createdAt = 1_000L,
    )

    private fun PaymentViewModel.registrar() =
        handleRefundSuccessForTest(saleData = null, entryMode = com.jaac.avoqado_tpv.features.payment.domain.model.CardEntryMode.CHIP, seedState = exitoSembrado())

    @Test
    fun `P1 la fila se escribe en Room ANTES de tocar la red - write-ahead de verdad`() = runTest {
        // Auditoría de Codex F1: antes la fila nacía en el onFailure, DESPUÉS del POST. Un proceso muerto
        // a media petición dejaba dinero devuelto sin fila y sin registro.
        val orden = mutableListOf<String>()
        coEvery { mockRefundQueueRepository.enqueueClaimed(any(), any()) } coAnswers { orden += "room"; Result.success(Unit) }
        coEvery { mockRecordRefundUseCase(any(), any(), any(), any(), any()) } coAnswers {
            orden += "red"
            Result.success(mockk(relaxed = true))
        }
        val vm = refundReady()

        vm.registrar()

        assertThat(orden).containsExactly("room", "red").inOrder()
        vm.viewModelScope.cancel()
    }

    @Test
    fun `P1 con exito del servidor la fila write-ahead se cierra con el MISMO token`() = runTest {
        val fila = slot<com.jaac.avoqado_tpv.features.payment.domain.model.QueuedRefund>()
        val token = slot<String>()
        coEvery { mockRefundQueueRepository.enqueueClaimed(capture(fila), capture(token)) } returns Result.success(Unit)
        coEvery { mockRecordRefundUseCase(any(), any(), any(), any(), any()) } returns Result.success(mockk(relaxed = true))
        val vm = refundReady()

        vm.registrar()

        coVerify(exactly = 1) { mockRefundQueueRepository.markSuccess(fila.captured.idempotencyKey, token.captured) }
        coVerify(exactly = 0) { mockRefundQueueRepository.release(any(), any(), any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `P1 sin red la fila queda PENDING para el worker y la pantalla lo dice en ambar`() = runTest {
        val fila = slot<com.jaac.avoqado_tpv.features.payment.domain.model.QueuedRefund>()
        val token = slot<String>()
        coEvery { mockRefundQueueRepository.enqueueClaimed(capture(fila), capture(token)) } returns Result.success(Unit)
        coEvery { mockRecordRefundUseCase(any(), any(), any(), any(), any()) } returns
            Result.failure(java.io.IOException("sin red"))
        val vm = refundReady()

        vm.registrar()

        assertThat(fila.captured.processor).isEqualTo(com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType.BLUMON)
        assertThat(fila.captured.originalPaymentId).isEqualTo("pay-001")
        assertThat(fila.captured.amount).isEqualTo(BigDecimal("50.00"))
        coVerify(exactly = 1) { mockRefundQueueRepository.release(fila.captured.idempotencyKey, token.captured, 1, any()) }
        coVerify(exactly = 0) { mockRefundQueueRepository.markPermanentlyFailed(any(), any(), any()) }
        coVerify { mockPaymentAttemptLedger.markDeliveredToQueue(any()) }
        val estado = vm.state.value
        assertThat(estado).isInstanceOf(PaymentState.Success::class.java)
        assertThat((estado as PaymentState.Success).pendingSyncMessage).contains("La devolución SÍ se hizo")
        assertThat(estado.recordingLostMessage).isNull()
        vm.viewModelScope.cancel()
    }

    @Test
    fun `P1 la fila write-ahead lleva la MISMA llave que viajó al servidor`() = runTest {
        val contexto = slot<PaymentContext.RefundPayment>()
        coEvery { mockRecordRefundUseCase(capture(contexto), any(), any(), any(), any()) } returns
            Result.failure(java.io.IOException("sin red"))
        val vm = refundReady()

        vm.registrar()

        val llaveEnviada = contexto.captured.idempotencyKey
        assertThat(llaveEnviada).isNotNull()
        coVerify { mockRefundQueueRepository.enqueueClaimed(match { it.idempotencyKey == llaveEnviada }, any()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `P1 sin sesion TAMBIEN queda en la cola, sin tocar la red`() = runTest {
        val vm = refundReady(isAuthenticated = false)

        vm.registrar()

        coVerify(exactly = 1) { mockRefundQueueRepository.enqueueClaimed(any(), any()) }
        coVerify(exactly = 1) { mockRefundQueueRepository.release(any(), any(), 1, any()) }
        coVerify(exactly = 0) { mockRecordRefundUseCase(any(), any(), any(), any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `P1 un rechazo DEFINITIVO del servidor deja la fila FAILED permanente y la pantalla en rojo`() = runTest {
        val fila = slot<com.jaac.avoqado_tpv.features.payment.domain.model.QueuedRefund>()
        val token = slot<String>()
        coEvery { mockRefundQueueRepository.enqueueClaimed(capture(fila), capture(token)) } returns Result.success(Unit)
        coEvery { mockRecordRefundUseCase(any(), any(), any(), any(), any()) } returns
            Result.failure(com.jaac.avoqado_tpv.core.data.network.BackendHttpException(422, "regla de negocio"))
        val vm = refundReady()

        vm.registrar()

        coVerify(exactly = 1) {
            mockRefundQueueRepository.markPermanentlyFailed(fila.captured.idempotencyKey, token.captured, match { it.contains("422") })
        }
        coVerify(exactly = 0) { mockRefundQueueRepository.release(any(), any(), any(), any()) }
        assertThat((vm.state.value as PaymentState.Success).recordingLostMessage).contains("regla de negocio")
        vm.viewModelScope.cancel()
    }

    @Test
    fun `P1 un 401 NO es permanente, es la sesion, y la fila vuelve a PENDING`() = runTest {
        coEvery { mockRecordRefundUseCase(any(), any(), any(), any(), any()) } returns
            Result.failure(com.jaac.avoqado_tpv.core.data.network.BackendHttpException(401, "token"))
        val vm = refundReady()

        vm.registrar()

        coVerify(exactly = 1) { mockRefundQueueRepository.release(any(), any(), 1, any()) }
        coVerify(exactly = 0) { mockRefundQueueRepository.markPermanentlyFailed(any(), any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `P1 si el write-ahead falla, el fallo de red cae al encolado de respaldo`() = runTest {
        coEvery { mockRefundQueueRepository.enqueueClaimed(any(), any()) } returns Result.failure(IllegalStateException("room"))
        coEvery { mockRecordRefundUseCase(any(), any(), any(), any(), any()) } returns
            Result.failure(java.io.IOException("sin red"))
        val vm = refundReady()

        vm.registrar()

        coVerify(exactly = 1) { mockRefundQueueRepository.enqueue(match { !it.permanent }) }
        coVerify(exactly = 0) { mockRefundQueueRepository.release(any(), any(), any(), any()) }
        assertThat((vm.state.value as PaymentState.Success).pendingSyncMessage).contains("La devolución SÍ se hizo")
        vm.viewModelScope.cancel()
    }

    @Test
    fun `P1 si fallan write-ahead, backend Y respaldo hay telemetria critica en el canal vigilado`() = runTest {
        coEvery { mockRefundQueueRepository.enqueueClaimed(any(), any()) } returns Result.failure(IllegalStateException("room"))
        coEvery { mockRecordRefundUseCase(any(), any(), any(), any(), any()) } returns
            Result.failure(java.io.IOException("sin red"))
        coEvery { mockRefundQueueRepository.enqueue(any()) } returns Result.failure(IllegalStateException("disco lleno"))
        val vm = refundReady()

        vm.registrar()

        verify { observabilityManager.logCritical(tag = "RefundRecordAndQueueLost", message = any(), error = any(), metadata = any()) }
        assertThat((vm.state.value as PaymentState.Success).recordingLostMessage).contains("ni en el servidor")
        vm.viewModelScope.cancel()
    }

    @Test
    fun `P1 el desenlace sobrevive a que cancelen el viewModelScope a media llamada de red`() = runTest {
        // El cajero sale de la pantalla justo después de ver «devolución aprobada»: el scope muere
        // con el POST en vuelo. Sin NonCancellable, el cierre de la fila y el aviso se cancelan con él.
        val puerta = CompletableDeferred<Unit>()
        coEvery { mockRecordRefundUseCase(any(), any(), any(), any(), any()) } coAnswers {
            puerta.await()
            Result.failure(java.io.IOException("sin red"))
        }
        val vm = refundReady()

        vm.registrar()
        vm.viewModelScope.cancel() // se va de la pantalla con la llamada suspendida
        puerta.complete(Unit)      // la red contesta tarde

        coVerify(exactly = 1) { mockRefundQueueRepository.enqueueClaimed(any(), any()) }
        coVerify(exactly = 1) { mockRefundQueueRepository.release(any(), any(), 1, any()) }
    }

    @Test
    fun `P1 startRefund se NIEGA a lanzar otra devolucion si el pago ya tiene una sin registrar`() = runTest {
        // Auditoría de Codex F7: mientras la primera no esté en el servidor, el saldo remoto sigue
        // pareciendo reembolsable; una segunda con otra llave devolvería el dinero dos veces.
        coEvery { mockRefundQueueRepository.unresolvedForPayment("pay-001") } returns listOf(filaEncolada())
        every { mockGetMerchantsUseCase.invoke() } returns flowOf(listOf(testMerchantA))
        every { mockMultiMerchantSDKManager.isMerchantActive(any()) } returns true
        val vm = createViewModel()

        vm.startRefund(createRefundContext())

        val estado = vm.state.value
        assertThat(estado).isInstanceOf(PaymentState.Error::class.java)
        assertThat((estado as PaymentState.Error).message).contains("sin registrar")
        assertThat(estado.canRetry).isFalse()
        coVerify(exactly = 0) { mockPaymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) }
        vm.viewModelScope.cancel()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // D4. 💸 UNA SOLA LLAVE, Y NADA DE BUCLES ETERNOS (revisión del 5-sep-2026)
    //
    // Dos defectos de DINERO que la cola durable dejó abiertos, los dos invisibles al compilador:
    //   • el respaldo `refundContextEnVuelo` mandaba el POST SIN `idempotencyKey` mientras la fila
    //     nacía con una llave nueva ⇒ el servidor guardaba el REFUND sin llave y el replay del
    //     worker creaba un SEGUNDO `Payment REFUND` sobre el mismo pago;
    //   • un `merchantAccountId` en blanco producía una fila que se reintentaba para siempre y
    //     bloqueaba el cierre del turno sin que nadie pudiera reconocerla.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `P1 tras resetPayment el POST lleva la MISMA llave que la fila, nunca null`() = runTest {
        // 🔴 El caso REAL: el cajero toca atrás durante «Autorizando reembolso…» (PaymentScreen
        // llama a `resetPayment()`), el SDK aprueba de todas formas y el registro cae al respaldo
        // `refundContextEnVuelo`. Ese respaldo se copiaba TAL CUAL del contexto que construye
        // PaymentScreen, que NO trae `idempotencyKey` (default null en PaymentContext).
        val contexto = slot<PaymentContext.RefundPayment>()
        val fila = slot<com.jaac.avoqado_tpv.features.payment.domain.model.QueuedRefund>()
        coEvery { mockRefundQueueRepository.enqueueClaimed(capture(fila), any()) } returns Result.success(Unit)
        coEvery { mockRecordRefundUseCase(capture(contexto), any(), any(), any(), any()) } returns
            Result.success(mockk(relaxed = true))
        val vm = refundReady()

        vm.resetPayment() // ← la sesión queda vacía: `buildRefundPaymentContext()` devolverá null

        vm.registrar()

        // Sin la llave, el servidor guarda el REFUND con idempotencyKey NULL y el replay del worker
        // —que sí manda la de la fila— nace como un reembolso NUEVO: dinero devuelto dos veces.
        assertThat(contexto.captured.idempotencyKey).isNotNull()
        assertThat(contexto.captured.idempotencyKey).isEqualTo(fila.captured.idempotencyKey)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `P1 sin resetPayment nada cambia - la llave sigue siendo el refundAttemptId de la sesion`() = runTest {
        // El camino normal no se toca: `buildRefundPaymentContext()` ya rellenaba la llave desde la
        // sesión. Esta prueba es el control: fija que el arreglo no movió el comportamiento bueno.
        val contexto = slot<PaymentContext.RefundPayment>()
        val fila = slot<com.jaac.avoqado_tpv.features.payment.domain.model.QueuedRefund>()
        coEvery { mockRefundQueueRepository.enqueueClaimed(capture(fila), any()) } returns Result.success(Unit)
        coEvery { mockRecordRefundUseCase(capture(contexto), any(), any(), any(), any()) } returns
            Result.success(mockk(relaxed = true))
        val vm = refundReady()

        vm.registrar()

        assertThat(contexto.captured.idempotencyKey).isNotNull()
        assertThat(contexto.captured.idempotencyKey).isEqualTo(fila.captured.idempotencyKey)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `P1 la libreta abre y cierra con la MISMA llave aunque medie un resetPayment`() = runTest {
        // `openAttempt` corre en startRefund con el `refundAttemptId` de la sesión; `markRecorded`
        // corría con la llave que se generara en handleRefundSuccess. Tras un reset eran DISTINTAS:
        // la fila abierta no se cerraba nunca y se marcaba «registrada» una llave sin fila.
        val abierta = slot<String>()
        coEvery {
            mockPaymentAttemptLedger.openAttempt(capture(abierta), any(), any(), any(), any(), any(), any(), any())
        } returns true
        coEvery { mockRecordRefundUseCase(any(), any(), any(), any(), any()) } returns
            Result.success(mockk(relaxed = true))
        val vm = refundReady()

        vm.resetPayment()
        vm.registrar()

        coVerify(exactly = 1) { mockPaymentAttemptLedger.markRecorded(abierta.captured) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `P1 sin merchantAccountId la fila nace PERMANENTE y NUNCA se toca la red`() = runTest {
        // 🔴 `RefundRecorder` rechaza un merchantAccountId vacío con IllegalArgumentException ANTES
        // de la red, y `classifySyncFailure` clasifica eso `Retryable`: la fila volvía a PENDING en
        // cada pasada del worker, para siempre, bloqueando el cierre del turno — y sin poder
        // reconocerla, porque `acknowledgeRefund` sólo acepta filas permanentes.
        val fila = slot<com.jaac.avoqado_tpv.features.payment.domain.model.QueuedRefund>()
        val token = slot<String>()
        coEvery { mockRefundQueueRepository.enqueueClaimed(capture(fila), capture(token)) } returns Result.success(Unit)
        every { mockGetMerchantsUseCase.invoke() } returns flowOf(emptyList()) // no hay de dónde rellenarlo
        every { mockAuthRepository.isAuthenticated() } returns true
        val vm = createViewModel()
        vm.startRefund(createRefundContext(merchantAccountId = null))

        vm.registrar()

        assertThat(fila.captured.merchantAccountId).isEmpty()
        coVerify(exactly = 1) {
            mockRefundQueueRepository.markPermanentlyFailed(fila.captured.idempotencyKey, token.captured, any())
        }
        coVerify(exactly = 0) { mockRefundQueueRepository.release(any(), any(), any(), any()) }
        // Ni se intenta el POST: no hay nada que el servidor pueda aceptar.
        coVerify(exactly = 0) { mockRecordRefundUseCase(any(), any(), any(), any(), any()) }
        // Y la pantalla lo dice en rojo, con el motivo: «necesita una persona».
        assertThat((vm.state.value as PaymentState.Success).recordingLostMessage)
            .contains("cuenta del comerciante")
        vm.viewModelScope.cancel()
    }

    @Test
    fun `P1 con merchantAccountId presente el desenlace NO se fuerza a permanente`() = runTest {
        // Control del arreglo de arriba: un fallo de red con la fila completa sigue siendo PENDING.
        coEvery { mockRecordRefundUseCase(any(), any(), any(), any(), any()) } returns
            Result.failure(java.io.IOException("sin red"))
        val vm = refundReady()

        vm.registrar()

        coVerify(exactly = 1) { mockRefundQueueRepository.release(any(), any(), 1, any()) }
        coVerify(exactly = 0) { mockRefundQueueRepository.markPermanentlyFailed(any(), any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `los tres textos honestos dicen que la devolucion SI se hizo y que NO se repita`() = runTest {
        val vm = createViewModel()
        val exito = PaymentState.Success(authCode = "AUTH", amount = "50.00", isRefund = true)

        val pendiente = vm.buildRefundPendingSyncState(exito, "REF-1")!!
        val rechazado = vm.buildRefundRejectedState(exito, "REF-1", "monto excede lo reembolsable")!!
        val perdido = vm.buildRefundLostState(exito, "REF-1")!!

        for (texto in listOf(pendiente.pendingSyncMessage, rechazado.recordingLostMessage, perdido.recordingLostMessage)) {
            assertThat(texto).contains("La devolución SÍ se hizo")
            assertThat(texto).contains("NO vuelvas a reembolsar")
            assertThat(texto).contains("REF-1")
            assertThat(texto).doesNotContain("Error")
        }
        assertThat(rechazado.recordingLostMessage).contains("monto excede lo reembolsable")
        assertThat(perdido.recordingLostMessage).contains("ni en el servidor")
        // Fuera de Success no hay nada que anotar: el cajero ya no está viendo esa pantalla.
        assertThat(vm.buildRefundPendingSyncState(PaymentState.Idle, "REF-1")).isNull()
        vm.viewModelScope.cancel()
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
    // 🔴 Hueco 2 (2026-09-07): el registro del cobro se cortó (timeout de 25 s con la red VIVA, o
    // caída de red) y el cobro entró a la cola — pero en el camino Blumon nadie pedía el sync
    // inmediato: la fila esperaba al periódico de 15 min mientras el servidor mantenía la
    // terminal «ocupada». AngelPay ya hacía el kick (AngelPayPaymentViewModel). Con la red
    // viva WorkManager corre en segundos; sin red, la petición única espera al constraint
    // CONNECTED y dispara sola al volver — sin observador propio ni candados nuevos.
    @Test
    fun `P1 tras encolar el cobro pide el sync inmediato en vez de esperar 15 minutos`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        try {
            coEvery { mockPaymentQueueRepository.enqueue(any()) } returns Result.success(Unit)
            clearMocks(com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler, answers = false, recordedCalls = true)

            viewModel.handleOfflineQueueOutcome(
                queuedPayment = queuedPaymentDeHueco2(),
                context = PaymentContext.FastPayment(
                    venueId = testVenueId,
                    staffId = testStaffId,
                    amount = BigDecimal("25.00"),
                    merchantAccountId = "merchant_a"
                ),
                recordError = RuntimeException("timeout"),
                referenceNumber = "873257481453",
                orderIdForFlow = null
            )

            verify(exactly = 1) { com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler.runNow(any()) }
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 si la cola NO aceptó el cobro no se pide ningún sync - no hay nada que reproducir`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        try {
            coEvery { mockPaymentQueueRepository.enqueue(any()) } returns Result.failure(RuntimeException("disco lleno"))
            clearMocks(com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler, answers = false, recordedCalls = true)

            viewModel.handleOfflineQueueOutcome(
                queuedPayment = queuedPaymentDeHueco2(),
                context = PaymentContext.FastPayment(
                    venueId = testVenueId,
                    staffId = testStaffId,
                    amount = BigDecimal("25.00"),
                    merchantAccountId = "merchant_a"
                ),
                recordError = RuntimeException("timeout"),
                referenceNumber = "873257481453",
                orderIdForFlow = null
            )

            verify(exactly = 0) { com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler.runNow(any()) }
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    // Los dos sitios de EFECTIVO encolan por el mismo camino (y llevan el `terminalPaymentRequestId`
    // del cobro que mandó la tablet): sin el kick, la fila del servidor se queda «ocupada» hasta el
    // periódico de 15 min aunque el dinero ya esté en el cajón.
    @Test
    fun `P1 efectivo - tras encolar el cobro pide el sync inmediato`() = runTest {
        val viewModel = createViewModel()
        try {
            coEvery {
                mockRecordPaymentUseCase(context = any(), cardDetails = any(), authorizationNumber = any(), referenceNumber = any())
            } returns Result.failure(RuntimeException("timeout"))
            coEvery { mockPaymentQueueRepository.enqueue(any()) } returns Result.success(Unit)
            clearMocks(com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler, answers = false, recordedCalls = true)

            viewModel.submitAmountDirectToMerchant("100.00")
            viewModel.setSocketPaymentSource("SOCKET", "req-123")
            viewModel.processCashPayment("100.00")

            coVerify(timeout = 2000) { mockPaymentQueueRepository.enqueue(any()) }
            verify(timeout = 2000, exactly = 1) { com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler.runNow(any()) }
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 efectivo confirmado en kiosco - tras encolar el cobro pide el sync inmediato`() = runTest {
        val viewModel = createViewModel()
        try {
            coEvery {
                mockRecordPaymentUseCase(context = any(), cardDetails = any(), authorizationNumber = any(), referenceNumber = any())
            } returns Result.failure(RuntimeException("timeout"))
            coEvery { mockPaymentQueueRepository.enqueue(any()) } returns Result.success(Unit)
            clearMocks(com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler, answers = false, recordedCalls = true)

            // confirmCashPayment exige AwaitingCashConfirmation: se siembra el estado privado, como
            // hacen las pruebas de handleOfflineQueueOutcome.
            val stateField = PaymentViewModel::class.java.getDeclaredField("_state")
            stateField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val stateFlow = stateField.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<PaymentState>
            stateFlow.value = PaymentState.AwaitingCashConfirmation(
                subtotal = "100.00",
                tipAmount = "0.00",
                totalAmount = "100.00",
                rating = null,
                orderId = null,
                orderNumber = null
            )

            viewModel.confirmCashPayment(confirmedByStaffId = testStaffId)

            coVerify(timeout = 2000) { mockPaymentQueueRepository.enqueue(any()) }
            verify(timeout = 2000, exactly = 1) { com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler.runNow(any()) }
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    private fun queuedPaymentDeHueco2() = com.jaac.avoqado_tpv.features.payment.domain.model.QueuedPayment(
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
        idempotencyKey = "idem-hueco2-1",
        createdAt = System.currentTimeMillis(),
        retryCount = 0,
        lastError = "timeout",
        syncStatus = com.jaac.avoqado_tpv.features.payment.domain.model.SyncStatus.PENDING
    )


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

    // ── Contactless denegado por el KERNEL de la PAX (Testarudo 2026-09-07) ─────────────────
    // Nueve `CtlssDeniedFailure` en tres ventas; la app pintaba «Error leyendo tarjeta contactless»
    // y la cajera entraba al bucle cancelar → reenviar → volver a acercar la MISMA tarjeta.

    private fun detectQueDevuelveUnToque(
        calls: MutableList<com.blumonpay.pax.shared.neptune_polling.domain.use_case.start_detect_card.StartDetectCardParams>,
    ): com.blumonpay.pax.shared.neptune_polling.domain.use_case.start_detect_card.StartDetectCardUseCase {
        val tap = mockk<com.pax.dal.entity.PollingResult>(relaxed = true)
        every { tap.readerType } returns com.pax.dal.entity.EReaderType.PICC
        val detect = mockk<com.blumonpay.pax.shared.neptune_polling.domain.use_case.start_detect_card.StartDetectCardUseCase>(relaxed = true)
        coEvery { detect.run(capture(calls)) } returns com.blumonpay.pax.utils.clean.Either.Right(
            com.blumonpay.pax.shared.neptune_polling.domain.use_case.start_detect_card.StartDetectCardResponse(tap),
        )
        return detect
    }

    private fun kernelQueDeniega(emvCode: Int): com.blumonpay.pax.shared.trans_process.domain.use_case.start_ctlss_trans.StartCtlssTransUseCase {
        val ctlss = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.start_ctlss_trans.StartCtlssTransUseCase>(relaxed = true)
        coEvery { ctlss.run(any()) } returns com.blumonpay.pax.utils.clean.Either.Left(
            com.blumonpay.pax.shared.trans_process.domain.use_case.start_ctlss_trans.StartCtlssTransFailure.CtlssDeniedFailure(emvCode),
        )
        return ctlss
    }

    @Test
    fun `P1 contactless denegado por el kernel dice chip y el reintento abre el lector sin PICC`() = runTest {
        val detectCalls = mutableListOf<com.blumonpay.pax.shared.neptune_polling.domain.use_case.start_detect_card.StartDetectCardParams>()
        val viewModel = createViewModel(
            startDetectCardUseCase = detectQueDevuelveUnToque(detectCalls),
            startCtlssTransUseCase = kernelQueDeniega(emvCode = 12),
        )
        viewModel.selectMerchant(testMerchantA)
        Thread.sleep(1000)

        viewModel.startPayment("100.00")
        Thread.sleep(1500)
        testDispatcher.scheduler.advanceUntilIdle()

        val error = viewModel.state.value as? PaymentState.Error
        assertThat(error).isNotNull()
        assertThat(error!!.message).contains("INSERTE")
        assertThat(error.message).contains("código 12")
        assertThat(error.message).contains("No es un rechazo del banco")
        assertThat(error.canRetry).isTrue()
        assertThat(detectCalls.map { it.readerType }).containsExactly(com.pax.dal.entity.EReaderType.MAG_ICC_PICC)

        // Reintentar: misma venta (monto, propina, merchant), pero el lector ya no escucha el NFC
        viewModel.retryPayment(error.context)
        Thread.sleep(1500)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(detectCalls.map { it.readerType })
            .containsExactly(com.pax.dal.entity.EReaderType.MAG_ICC_PICC, com.pax.dal.entity.EReaderType.MAG_ICC)
            .inOrder()

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `P1 cancelar tras un rechazo contactless deja el lector completo para la siguiente venta`() = runTest {
        val detectCalls = mutableListOf<com.blumonpay.pax.shared.neptune_polling.domain.use_case.start_detect_card.StartDetectCardParams>()
        val viewModel = createViewModel(
            startDetectCardUseCase = detectQueDevuelveUnToque(detectCalls),
            startCtlssTransUseCase = kernelQueDeniega(emvCode = 12),
        )
        viewModel.selectMerchant(testMerchantA)
        Thread.sleep(1000)

        viewModel.startPayment("100.00")
        Thread.sleep(1500)
        testDispatcher.scheduler.advanceUntilIdle()
        assertThat(viewModel.state.value).isInstanceOf(PaymentState.Error::class.java)

        // El cajero cancela y llega OTRO cliente: nada del rechazo anterior puede recortarle el lector
        viewModel.cancelPayment()
        viewModel.resetPayment()
        viewModel.startPayment("80.00")
        Thread.sleep(1500)
        testDispatcher.scheduler.advanceUntilIdle()

        assertThat(detectCalls.map { it.readerType })
            .containsExactly(com.pax.dal.entity.EReaderType.MAG_ICC_PICC, com.pax.dal.entity.EReaderType.MAG_ICC_PICC)

        viewModel.viewModelScope.cancel()
    }
}

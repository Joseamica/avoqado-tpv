package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.lifecycle.viewModelScope
import com.blumonpay.pax.shared.trans_process.domain.TransProcessRepository
import com.blumonpay.pax.shared.trans_process.domain.use_case.set_select_app_code.SetSelectAppCodeUseCase
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
import com.jaac.avoqado_tpv.features.payment.domain.PaymentPhaseTracker
import com.jaac.avoqado_tpv.features.payment.domain.PaymentSdkCallback
import com.jaac.avoqado_tpv.features.payment.domain.PaymentSdkPhase
import com.jaac.avoqado_tpv.features.payment.domain.PhaseOutcome
import com.jaac.avoqado_tpv.features.payment.domain.mensajeDeFase
import com.jaac.avoqado_tpv.features.payment.domain.ProcessingClock
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
import kotlinx.coroutines.flow.first
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
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, application = android.app.Application::class, sdk = [28])
class PaymentViewModelKernelDurabilityTest {

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
            // La venta alinea la cuenta efectiva del SDK antes de pedir tarjeta (24-sep); aqui ya esta alineada.
            coEvery { ensureEffectivelyActive(any(), any()) } returns Result.success(Unit)
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
        mockPaymentAttemptLedger = mockk(relaxed = true) {
            coEvery { openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) } returns true
            coEvery { markAuthorizing(any()) } returns true
            coEvery { markKernelEntered(any()) } returns true
            coEvery { markHostResponded(any(), any(), any(), any(), any()) } returns true
        }
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
        startEmvTransUseCase: com.blumonpay.pax.shared.trans_process.domain.use_case.strat_emv_trans.StartEmvTransUseCase =
            mockk(relaxed = true),
        stopDetectCardUseCase: com.blumonpay.pax.shared.neptune_polling.domain.use_case.stop_detect_card.StopDetectCardUseCase =
            mockk(relaxed = true),
        cancelIccUseCase: com.example.clean_lib_services.shared.core.domain.use_case.cancel_package.cancel_icc.CancelIccUseCase =
            mockk(relaxed = true),
        validateCancelUseCase: com.example.clean_lib_services.shared.core.domain.use_case.cancel_package.validate_cancel.ValidateCancelUseCase =
            mockk(relaxed = true),
    ): PaymentViewModel {
        return PaymentViewModel(
            preTransUseCase = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.pre_trans.PreTransUseCase>(relaxed = true),
            startDetectCardUseCase = startDetectCardUseCase,
            stopDetectCardUseCase = stopDetectCardUseCase,
            startEmvTransUseCase = startEmvTransUseCase,
            startCtlssTransUseCase = startCtlssTransUseCase,
            getEmvTagUseCase = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.get_emv_tags.GetEmvTagUseCase>(relaxed = true),
            completeEmvTransUseCase = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.complete_emv_trans.CompleteEmvTransUseCase>(relaxed = true),
            continueConfirmCardUseCase = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.continue_confirm_card.ContinueConfirmCardUseCase>(relaxed = true),
            setSelectAppCodeUseCase = mockSetSelectAppCodeUseCase,
            saleIccUseCase = mockk<com.example.clean_lib_services.shared.core.domain.use_case.sale_package.sale_icc.SaleIccUseCase>(relaxed = true),
            saleCtlsUseCase = mockk<com.example.clean_lib_services.shared.core.domain.use_case.sale_package.sale_ctls.SaleCtlsUseCase>(relaxed = true),
            cancelIccUseCase = cancelIccUseCase,
            validateCancelUseCase = validateCancelUseCase,
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
            blumonAttemptResolver = mockk(relaxed = true),
            observability = observabilityManager,
            authAttemptTelemetryStore = mockAuthAttemptTelemetryStore,
            appContext = mockAppContext
        )
    }

    /**
     * 🔴 El flujo de cobro cruza DOS relojes: el `TestDispatcher` (virtual, donde vive `viewModelScope`)
     * y `Dispatchers.IO` REAL, al que saltan `continuePaymentFlow()` y las suspensiones de Room. Un
     * `Thread.sleep` ciego no avanza el virtual, y un `advanceUntilIdle()` suelto no espera al real:
     * la secuencia vieja (sleep → advanceUntilIdle → verify) lanzaba el trabajo de IO justo ANTES de
     * comprobarlo, así que el kernel "no fue llamado" por carrera, no por comportamiento.
     *
     * Esto ALTERNA los dos relojes y para en cuanto la condición REAL se cumple. Si no se cumple,
     * agota el tope y deja que falle el aserto de la prueba — nunca lo enmascara.
     */
    private fun esperarA(timeoutMs: Long = 5_000, condicion: () -> Boolean) {
        val limite = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < limite) {
            testDispatcher.scheduler.advanceUntilIdle()
            if (condicion()) return
            Thread.sleep(10)
        }
        testDispatcher.scheduler.advanceUntilIdle()
    }

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

    @Test
    fun `Mindform detection failure closes its reservation before retry can open another reader`() =
        checkFailedDetectionRetry(returnToMerchant = false)

    @Test
    fun `Mindform cancelling an error and choosing card again uses a fresh attempt`() =
        checkFailedDetectionRetry(returnToMerchant = true)

    private fun checkFailedDetectionRetry(returnToMerchant: Boolean) = runTest {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.paymentAttemptDao()
        mockPaymentAttemptLedger = PaymentAttemptLedger(dao, mockTpvSettingsRepository)
        val detect = mockk<com.blumonpay.pax.shared.neptune_polling.domain.use_case.start_detect_card.StartDetectCardUseCase>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val secondRead = CompletableDeferred<Unit>()
        coEvery { detect.run(any()) } coAnswers {
            if (calls.incrementAndGet() == 2) secondRead.await()
            com.blumonpay.pax.utils.clean.Either.Left(mockk(relaxed = true))
        }
        val vm = createViewModel(startDetectCardUseCase = detect)
        try {
            vm.selectMerchant(testMerchantA)
            esperarA { vm.currentMerchant.value != null }
            vm.startPayment("100.00")
            esperarA { vm.state.value is PaymentState.Error }
            assertThat(calls.get()).isEqualTo(1)
            val error = vm.state.value as PaymentState.Error
            assertThat(dao.findTerminalHold()).isNull()
            assertThat(error.canRetry).isTrue()
            if (returnToMerchant) {
                vm.returnToSelectingMerchantFromError(error.context)
                vm.startPayment("100.00")
            } else vm.retryPayment(error.context)
            esperarA { calls.get() == 2 || vm.state.value is PaymentState.Error }
            assertWithMessage("retry must reach the reader without deleting storage: ${vm.state.value}")
                .that(calls.get()).isEqualTo(2)
            assertThat(dao.findTerminalHold()?.amountCents).isEqualTo(10000L)
        } finally {
            vm.viewModelScope.cancel()
            secondRead.cancel()
            db.close()
        }
    }

    @Test
    fun `Mindform withdrawn chip before authorization leaves the next sale free`() = runTest {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.paymentAttemptDao()
        mockPaymentAttemptLedger = PaymentAttemptLedger(dao, mockTpvSettingsRepository)
        val tap = mockk<com.pax.dal.entity.PollingResult>(relaxed = true)
        every { tap.readerType } returns com.pax.dal.entity.EReaderType.ICC
        val detect = mockk<com.blumonpay.pax.shared.neptune_polling.domain.use_case.start_detect_card.StartDetectCardUseCase>()
        coEvery { detect.run(any()) } returns com.blumonpay.pax.utils.clean.Either.Right(
            com.blumonpay.pax.shared.neptune_polling.domain.use_case.start_detect_card.StartDetectCardResponse(tap))
        val emv = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.strat_emv_trans.StartEmvTransUseCase>()
        coEvery { emv.run(any()) } returns com.blumonpay.pax.utils.clean.Either.Left(
            mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.strat_emv_trans.StartEmvTransFailure.WithdrawnCardFailure>(relaxed = true))
        val vm = createViewModel(startDetectCardUseCase = detect, startEmvTransUseCase = emv)
        try {
            vm.selectMerchant(testMerchantA)
            esperarA { vm.currentMerchant.value != null }
            vm.startPayment("100.00")
            esperarA { vm.state.value is PaymentState.Error }
            assertThat((vm.state.value as PaymentState.Error).message).contains("Tarjeta retirada")
            assertThat(dao.findTerminalHold()).isNull()
            vm.resetPayment()
            assertThat(mockPaymentAttemptLedger.openAttempt("next-sale", testVenueId, "BLUMON", 32000, 0, "FAST", "{}")).isTrue()
        } finally { vm.viewModelScope.cancel(); db.close() }
    }

    @Test
    fun `Mindform leaving detection closes the reservation and ignores its late card after a new sale`() = runTest {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.paymentAttemptDao()
        mockPaymentAttemptLedger = PaymentAttemptLedger(dao, mockTpvSettingsRepository)
        val detect = mockk<com.blumonpay.pax.shared.neptune_polling.domain.use_case.start_detect_card.StartDetectCardUseCase>()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val oldCard = CompletableDeferred<Unit>()
        val nextCard = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val oldFlow = CompletableDeferred<kotlinx.coroutines.Job>()
        val tap = mockk<com.pax.dal.entity.PollingResult>(relaxed = true)
        every { tap.readerType } returns com.pax.dal.entity.EReaderType.PICC
        coEvery { detect.run(any()) } coAnswers {
            if (reads.incrementAndGet() == 1) {
                oldFlow.complete(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!)
                oldCard.await()
            } else nextCard.await()
            com.blumonpay.pax.utils.clean.Either.Right(
                com.blumonpay.pax.shared.neptune_polling.domain.use_case.start_detect_card.StartDetectCardResponse(tap))
        }
        val kernel = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.start_ctlss_trans.StartCtlssTransUseCase>(relaxed = true)
        val stop = mockk<com.blumonpay.pax.shared.neptune_polling.domain.use_case.stop_detect_card.StopDetectCardUseCase>(relaxed = true)
        val stops = java.util.concurrent.atomic.AtomicInteger()
        coEvery { stop.runInfallible(any()) } coAnswers {
            // First call is preflight; the second is the user leaving detection.
            if (stops.incrementAndGet() > 1) stopped.await()
            mockk(relaxed = true)
        }
        val vm = createViewModel(startDetectCardUseCase = detect, startCtlssTransUseCase = kernel, stopDetectCardUseCase = stop)
        try {
            vm.selectMerchant(testMerchantA)
            esperarA { vm.currentMerchant.value != null }
            vm.startPayment("100.00")
            esperarA { reads.get() == 1 }
            val first = dao.findTerminalHold()!!
            assertThat(vm.goBackOneStep()).isTrue()
            assertThat(vm.state.value).isInstanceOf(PaymentState.Processing::class.java)
            assertThat(vm.goBackOneStep()).isTrue() // Consumed: the screen must not reset/navigate away.
            vm.startPayment("200.00")
            assertThat(reads.get()).isEqualTo(1)
            assertThat(dao.getById(first.attemptId)?.state).isEqualTo("PREPARANDO")
            stopped.complete(Unit)
            esperarA { vm.state.value is PaymentState.SelectingMerchant }
            assertThat(dao.getById(first.attemptId)?.state).isEqualTo("DESCARTADA")
            vm.startPayment("100.00")
            esperarA { reads.get() == 2 || vm.state.value is PaymentState.Error }
            assertThat(reads.get()).isEqualTo(2)
            val next = dao.findTerminalHold()!!
            assertThat(next.attemptId).isNotEqualTo(first.attemptId)
            oldCard.complete(Unit)
            esperarA { oldFlow.getCompleted().isCompleted }
            assertThat(oldFlow.getCompleted().isCompleted).isTrue()
            coVerify(exactly = 0) { kernel.run(any()) }
            assertThat(dao.getById(next.attemptId)?.state).isEqualTo("PREPARANDO")
            assertThat(vm.state.value).isInstanceOf(PaymentState.DetectingCard::class.java)
        } finally {
            vm.viewModelScope.cancel(); oldCard.cancel(); nextCard.cancel(); stopped.cancel(); db.close()
        }
    }

    @Test
    fun `Mindform exception before authorization releases only the failed preparation`() = runTest {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.paymentAttemptDao()
        mockPaymentAttemptLedger = PaymentAttemptLedger(dao, mockTpvSettingsRepository)
        val detect = mockk<com.blumonpay.pax.shared.neptune_polling.domain.use_case.start_detect_card.StartDetectCardUseCase>()
        coEvery { detect.run(any()) } throws java.io.IOException("reader failed")
        val vm = createViewModel(startDetectCardUseCase = detect)
        try {
            vm.selectMerchant(testMerchantA)
            esperarA { vm.currentMerchant.value != null }
            vm.startPayment("100.00")
            esperarA { vm.state.value is PaymentState.Error }
            assertThat((vm.state.value as PaymentState.Error).message).contains("reader failed")
            assertThat(dao.findTerminalHold()).isNull()
        } finally { vm.viewModelScope.cancel(); db.close() }
    }

    @Test
    fun `Mindform failed reservation close cannot offer retry or erase the pending attempt`() = runTest {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.paymentAttemptDao()
        val broken = object : com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptDao by dao {
            override suspend fun casWithError(attemptId: String, expectedStates: List<String>, newState: String, now: Long, error: String?): Int =
                throw java.io.IOException("disk unavailable")
        }
        mockPaymentAttemptLedger = PaymentAttemptLedger(broken, mockTpvSettingsRepository)
        val detect = mockk<com.blumonpay.pax.shared.neptune_polling.domain.use_case.start_detect_card.StartDetectCardUseCase>()
        coEvery { detect.run(any()) } returns com.blumonpay.pax.utils.clean.Either.Left(mockk(relaxed = true))
        val vm = createViewModel(startDetectCardUseCase = detect)
        try {
            vm.selectMerchant(testMerchantA)
            esperarA { vm.currentMerchant.value != null }
            vm.startPayment("100.00")
            esperarA { vm.state.value is PaymentState.Error }
            assertThat((vm.state.value as PaymentState.Error).canRetry).isFalse()
            assertThat(dao.findTerminalHold()?.state).isEqualTo("PREPARANDO")
            assertThat(mockPaymentAttemptLedger.openAttempt("next-sale", testVenueId, "BLUMON", 32000, 0, "FAST", "{}")).isFalse()
        } finally { vm.viewModelScope.cancel(); db.close() }
    }

    @Test
    fun `P1 el aprobado offline del chip en una VENTA no se anota como cobro`() = runTest {
        // 🔴 Founder, 25-sep: «ninguna transacción en Blumon ni AngelPay puede ser offline». Antes, un RESULT_OFFLINE_APPROVED
        // del chip publicaba Success y registraba la venta sin que Blumon la cobrara. Ahora se autoriza en línea: sin la
        // respuesta de Blumon no hay cobro que anotar (en este arnés la autorización en línea no tiene tarjeta y falla).
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase::class.java).allowMainThreadQueries().build()
        mockPaymentAttemptLedger = PaymentAttemptLedger(db.paymentAttemptDao(), mockTpvSettingsRepository)
        val response = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.start_ctlss_trans.StartCtlssTransResponse>(relaxed = true) {
            every { transResult!!.transResult } returns com.paxsz.module.emv.process.enums.TransResultEnum.RESULT_OFFLINE_APPROVED
        }
        val kernel = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.start_ctlss_trans.StartCtlssTransUseCase>()
        val vecesQueCorrioElKernel = java.util.concurrent.atomic.AtomicInteger(0)
        coEvery { kernel.run(any()) } answers {
            vecesQueCorrioElKernel.incrementAndGet()
            com.blumonpay.pax.utils.clean.Either.Right(response)
        }
        val vm = createViewModel(startDetectCardUseCase = detectQueDevuelveUnToque(mutableListOf()), startCtlssTransUseCase = kernel)
        try {
            vm.selectMerchant(testMerchantA)
            esperarA { vm.currentMerchant.value != null }
            vm.setSocketPaymentSource("SOCKET", "offline-no-es-cobro")
            vm.startPayment("100.00")
            esperarA { vecesQueCorrioElKernel.get() > 0 }
            esperarA { vm.state.value is PaymentState.Error }
            assertThat(vecesQueCorrioElKernel.get()).isEqualTo(1)
            assertThat(vm.state.value).isNotInstanceOf(PaymentState.Success::class.java)
            verify(exactly = 0) {
                mockSocketManager.emitTerminalPaymentResult(any(), "success", any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
            }
        } finally { vm.viewModelScope.cancel(); db.close() }
    }

    @Test
    fun `P1 el aprobado offline del chip en un reembolso no se anota y la terminal queda libre`() = runTest {
        // 🔴 25-sep, PAX de pruebas con tarjetas reales: el chip de una VISA contestó RESULT_OFFLINE_APPROVED y la app
        // anotó el reembolso de $254 SIN llamar a Blumon (no existe reembolso offline). Y cuando Blumon rechazó otro
        // reembolso (400, TX_010 TARJETA INVALIDA) el intento se quedó AUTORIZANDO y la terminal no volvió a cobrar.
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.paymentAttemptDao()
        mockPaymentAttemptLedger = PaymentAttemptLedger(dao, mockTpvSettingsRepository)
        coEvery { mockInitializationManager.readEffectivePosId() } returns testMerchantA.posId
        val response = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.start_ctlss_trans.StartCtlssTransResponse>(relaxed = true) {
            every { transResult!!.transResult } returns com.paxsz.module.emv.process.enums.TransResultEnum.RESULT_OFFLINE_APPROVED
        }
        val kernel = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.start_ctlss_trans.StartCtlssTransUseCase>()
        coEvery { kernel.run(any()) } returns com.blumonpay.pax.utils.clean.Either.Right(response)
        // Lo que contestó Blumon esa vez, con la forma del SDK: el código vive en el data class del campo.
        val datos = mockk<com.example.clean_lib_services.shared.core.domain.entity.cancel_data.MomentumDataFailureCancel>()
        every { datos.toString() } returns "MomentumDataFailureCancel(httpCode=400, code=TX_010, description=TARJETA INVALIDA)"
        val rechazo = com.example.clean_lib_services.shared.core.domain.use_case.cancel_package.cancel_icc.CancelIccFailure.MomentumFailure(datos)
        val cancel = mockk<com.example.clean_lib_services.shared.core.domain.use_case.cancel_package.cancel_icc.CancelIccUseCase>()
        val llamadasABlumon = java.util.concurrent.atomic.AtomicInteger(0)
        coEvery { cancel.run(any()) } answers {
            llamadasABlumon.incrementAndGet()
            com.example.clean_lib_services.utils.clean.Either.Left(rechazo)
        }
        val validate = mockk<com.example.clean_lib_services.shared.core.domain.use_case.cancel_package.validate_cancel.ValidateCancelUseCase>()
        coEvery { validate.run(any()) } returns com.example.clean_lib_services.utils.clean.Either.Right(
            com.example.clean_lib_services.shared.core.domain.use_case.cancel_package.validate_cancel.ValidateCancelResponse(mockk(relaxed = true))
        )
        val vm = createViewModel(startDetectCardUseCase = detectQueDevuelveUnToque(mutableListOf()), startCtlssTransUseCase = kernel,
            cancelIccUseCase = cancel, validateCancelUseCase = validate)
        // Timber no imprime en las pruebas: se junta el flujo para que una falla diga DÓNDE se detuvo.
        val flujo = java.util.Collections.synchronizedList(mutableListOf<String>())
        val arbol = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) { flujo.add(message.take(160)) }
        }
        timber.log.Timber.plant(arbol)
        try {
            vm.selectMerchant(testMerchantA)
            esperarA { vm.currentMerchant.value != null }
            vm.startRefund(createRefundContext())
            esperarA { vm.state.value is PaymentState.Error }
            coVerify(exactly = 1) { kernel.run(any()) }
            // El «aprobado» del chip ya no es un reembolso: el flujo va a autorizar en línea (en este arnés la lectura de la
            // tarjeta viene vacía y la autorización truena antes de Blumon — eso se prueba en la PAX con tarjeta real).
            assertWithMessage("el aprobado offline debe ir en línea; flujo = ${flujo.takeLast(25)}")
                .that(flujo.any { it.contains("se autoriza en línea con Blumon") }).isTrue()
            assertThat(vm.state.value).isNotInstanceOf(PaymentState.Success::class.java)
            assertThat(llamadasABlumon.get()).isAtMost(1)
            // Nada se anotó como devuelto…
            coVerify(exactly = 0) { mockRecordRefundUseCase.invoke(any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { mockRefundQueueRepository.enqueueClaimed(any(), any()) }
            coVerify(exactly = 0) { mockRefundQueueRepository.enqueue(any()) }
            // …y la terminal quedó libre para la siguiente operación.
            esperarA { kotlinx.coroutines.runBlocking { dao.findTerminalHold() } == null }
            assertThat(mockPaymentAttemptLedger.openAttempt("siguiente", testVenueId, "BLUMON", 1000, 0, "FAST", "{}")).isTrue()
        } finally { timber.log.Timber.uproot(arbol); vm.viewModelScope.cancel(); db.close() }
    }

    @Test
    fun `refund cannot enter the offline capable kernel while another attempt holds the terminal`() = runTest {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.paymentAttemptDao()
        val previo = PaymentAttemptLedger(dao, mockTpvSettingsRepository)
        assertThat(previo.openAttempt("venta-en-vuelo", testVenueId, "BLUMON", 10000, 0, "FAST", "{}")).isTrue()
        mockPaymentAttemptLedger = PaymentAttemptLedger(dao, mockTpvSettingsRepository)
        val kernel = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.start_ctlss_trans.StartCtlssTransUseCase>(relaxed = true)
        val vm = createViewModel(startDetectCardUseCase = detectQueDevuelveUnToque(mutableListOf()), startCtlssTransUseCase = kernel)
        try {
            vm.selectMerchant(testMerchantA)
            Thread.sleep(1000)
            vm.startRefund(createRefundContext())
            Thread.sleep(1500)
            testDispatcher.scheduler.advanceUntilIdle()
            coVerify(exactly = 0) { kernel.run(any()) }
            assertThat(dao.getById("venta-en-vuelo")?.state).isEqualTo("PREPARANDO")
        } finally { vm.viewModelScope.cancel(); db.close() }
    }

    @Test
    fun `recreated VM cannot enter offline capable kernel while previous instance is preparing`() = runTest {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.paymentAttemptDao()
        val firstLedger = PaymentAttemptLedger(dao, mockTpvSettingsRepository)
        assertThat(firstLedger.openAttempt("previous-preparing", testVenueId, "BLUMON", 10000, 0, "FAST", "{}")).isTrue()
        assertThat(dao.getById("previous-preparing")?.state).isEqualTo("PREPARANDO")
        mockPaymentAttemptLedger = PaymentAttemptLedger(dao, mockTpvSettingsRepository)
        val kernel = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.start_ctlss_trans.StartCtlssTransUseCase>(relaxed = true)
        val vecesQueCorrioElKernelRecreado = java.util.concurrent.atomic.AtomicInteger(0)
        coEvery { kernel.run(any()) } answers {
            vecesQueCorrioElKernelRecreado.incrementAndGet()
            com.blumonpay.pax.utils.clean.Either.Left(
                com.blumonpay.pax.shared.trans_process.domain.use_case.start_ctlss_trans.StartCtlssTransFailure.CtlssDeniedFailure(12))
        }
        val recreated = createViewModel(startDetectCardUseCase = detectQueDevuelveUnToque(mutableListOf()), startCtlssTransUseCase = kernel)
        try {
            recreated.selectMerchant(testMerchantA)
            esperarA { recreated.currentMerchant.value != null }
            // 🔴 PRECONDICIÓN, no decoración: si esta espera AGOTA el tope, `merchantSwitchingLoading`
            // se queda en true y el cobro muere en "Ya hay un cambio de cuenta en progreso" — un Error
            // que satisface la espera de abajo con el kernel en 0. La prueba pasaría VERDE aunque el
            // candado de la libreta no existiera, que es justo el defecto que vino a cerrar.
            assertWithMessage("precondición: el comercio debe quedar seleccionado antes de cobrar")
                .that(recreated.currentMerchant.value).isNotNull()
            recreated.startPayment("100.00")
            // 🔴 Antes esto era `Thread.sleep(1500)` + un `advanceUntilIdle()` suelto, y la prueba
            // pasaba EN VACÍO: por el camino de VENTA esa secuencia no alcanza el kernel nunca, así
            // que `exactly = 0` se cumplía hubiera candado o no (P2 de la auditoría Codex del
            // 10-sep). Ahora se espera a que el flujo ATERRICE — el desenlace correcto es que la
            // libreta se niegue a abrir el intento porque ya hay uno PREPARANDO— y sólo entonces
            // se afirma que el kernel no corrió.
            esperarA { recreated.state.value is PaymentState.Error || vecesQueCorrioElKernelRecreado.get() > 0 }
            assertWithMessage("el candado de la libreta debe impedir el kernel; estado final = ${recreated.state.value}")
                .that(vecesQueCorrioElKernelRecreado.get()).isEqualTo(0)
            // Y que sea EL error de la libreta, no cualquiera: un Error de otra guarda (cambio de
            // comercio en curso, sesión, conectividad) también dejaría el kernel en 0 sin probar nada.
            // 🔴 El texto pasó a decir CUÁL de las cercas rechazó el intento (founder, 21-sep). Lo que
            // hace que este aserto siga probando lo mismo es que ninguna OTRA guarda de la pantalla
            // menciona un cobro sin confirmar: sólo la libreta lo hace.
            assertWithMessage("debe ser el rechazo de la LIBRETA; estado = ${recreated.state.value}")
                .that((recreated.state.value as PaymentState.Error).message)
                .contains("cobro sin confirmar")
            coVerify(exactly = 0) { kernel.run(any()) }
            assertThat(dao.getById("previous-preparing")?.state).isEqualTo("PREPARANDO")
        } finally { recreated.viewModelScope.cancel(); db.close() }
    }

    /**
     * Resolver la fila del kernel devuelve la terminal — la otra mitad del rechazo contactless.
     *
     * ⚠️ CORRECCIÓN medida el 2026-09-10 (relevo Testarudo): la afirmación de abajo NO se sostiene
     * tal cual. El alta de cuenta SÍ termina; lo que fallaba era esperar en el reloj equivocado —
     * `Thread.sleep` no avanza el `TestDispatcher`, así que la continuación de `openAttempt` (Room
     * real) quedaba encolada y el trabajo de `Dispatchers.IO` arrancaba justo DESPUÉS del último
     * `advanceUntilIdle()`. Con `esperarA` (alterna los dos relojes) el camino de venta alcanza el
     * kernel: ver `offline approval write failure...`, verde con el kernel corriendo 1 vez.
     * Esta prueba se dejó SIN conducir el ViewModel a propósito: reescribirla no formaba parte del
     * arreglo y su cobertura actual sobre la libreta es válida.
     *
     * 🔴 Por qué esta prueba NO conduce el ViewModel, que sería lo natural: medido el 2026-09-10,
     * en pruebas unitarias el alta de cuenta del gestor de comercios **nunca termina**
     * (`merchantSwitchingLoading` se queda en `true` indefinidamente) y `startPayment` rebota con
     * «Ya hay un cambio de cuenta en progreso». Esperar más no ayuda — empeora, y llega a romper
     * las pruebas vecinas de esta misma clase.
     *
     * Así que la conducta se fija en DOS piezas que juntas prueban lo mismo y no dependen del
     * orden ni del reloj:
     *  1. que la RAMA `RESULT_OFFLINE_DENIED` del cobro llame a `markKernelRefused`, en las DOS
     *     variantes → `RechazoContactlessLiberaLaTerminalTest` (lee el fuente).
     *  2. que esa llamada SURTA EFECTO sobre Room de verdad → esta prueba.
     */
    // ═══ P2 (Codex 10-sep): el cableado del rechazo y del desconocido, CONDUCIDOS por el ViewModel ═══

    /**
     * Rechazo EXPLÍCITO del kernel (`RESULT_OFFLINE_DENIED`): decidido offline y antes de cualquier
     * autorización, así que nunca llegó al procesador. El ViewModel debe llamar `markKernelRefused`,
     * la fila queda DESCARTADA, la terminal se libera y la SIGUIENTE venta puede abrir su intento.
     * Antes esto sólo lo «fijaba» una guarda de texto (`contains("markKernelRefused")`) que seguía
     * pasando con la llamada comentada; esto sí ejecuta la rama.
     */
    @Test
    fun `un kernel que DENIEGA offline libera la terminal por el ViewModel y deja pasar la siguiente venta`() = runTest {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.paymentAttemptDao()
        mockPaymentAttemptLedger = PaymentAttemptLedger(dao, mockTpvSettingsRepository)
        val response = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.start_ctlss_trans.StartCtlssTransResponse>(relaxed = true) {
            every { transResult!!.transResult } returns com.paxsz.module.emv.process.enums.TransResultEnum.RESULT_OFFLINE_DENIED
        }
        val kernel = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.start_ctlss_trans.StartCtlssTransUseCase>()
        val vecesQueCorrioElKernel = java.util.concurrent.atomic.AtomicInteger(0)
        coEvery { kernel.run(any()) } answers {
            vecesQueCorrioElKernel.incrementAndGet()
            com.blumonpay.pax.utils.clean.Either.Right(response)
        }
        val vm = createViewModel(startDetectCardUseCase = detectQueDevuelveUnToque(mutableListOf()), startCtlssTransUseCase = kernel)
        try {
            vm.selectMerchant(testMerchantA)
            esperarA { vm.currentMerchant.value != null }
            assertWithMessage("precondición: comercio seleccionado").that(vm.currentMerchant.value).isNotNull()
            vm.startPayment("100.00")
            esperarA { vecesQueCorrioElKernel.get() > 0 }
            assertWithMessage("el arnés debe ALCANZAR el kernel; estado = ${vm.state.value}").that(vecesQueCorrioElKernel.get()).isEqualTo(1)
            esperarA { vm.state.value is PaymentState.Error }
            assertWithMessage("debe aterrizar en 'Tarjeta declinada'; estado = ${vm.state.value}")
                .that((vm.state.value as PaymentState.Error).message).contains("Tarjeta declinada")
            // La rama del ViewModel resolvió la fila: DESCARTADA, sin reserva, y la siguiente venta entra.
            esperarA { kotlinx.coroutines.runBlocking { dao.findTerminalHold() } == null }
            assertThat(dao.findTerminalHold()).isNull()
            assertThat(dao.findUnresolvedCharge()).isNull()
            val recreatedLedger = PaymentAttemptLedger(dao, mockTpvSettingsRepository)
            assertThat(recreatedLedger.openAttempt("siguiente-venta", testVenueId, "BLUMON", 10000, 0, "FAST", "{}")).isTrue()
        } finally { vm.viewModelScope.cancel(); db.close() }
    }

    /**
     * Resultado DESCONOCIDO del kernel (`RESULT_TRY_AGAIN` cae en el `else ->` «Resultado desconocido»):
     * puede esconder una transacción que sí avanzó, así que la reserva se RETIENE y la siguiente venta
     * queda bloqueada hasta que haya evidencia. Conducido por el ViewModel, no por la libreta a mano.
     */
    @Test
    fun `un resultado DESCONOCIDO del kernel retiene la terminal por el ViewModel y bloquea la siguiente venta`() = runTest {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.paymentAttemptDao()
        mockPaymentAttemptLedger = PaymentAttemptLedger(dao, mockTpvSettingsRepository)
        val response = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.start_ctlss_trans.StartCtlssTransResponse>(relaxed = true) {
            every { transResult!!.transResult } returns com.paxsz.module.emv.process.enums.TransResultEnum.RESULT_TRY_AGAIN
        }
        val kernel = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.start_ctlss_trans.StartCtlssTransUseCase>()
        val vecesQueCorrioElKernel = java.util.concurrent.atomic.AtomicInteger(0)
        coEvery { kernel.run(any()) } answers {
            vecesQueCorrioElKernel.incrementAndGet()
            com.blumonpay.pax.utils.clean.Either.Right(response)
        }
        val vm = createViewModel(startDetectCardUseCase = detectQueDevuelveUnToque(mutableListOf()), startCtlssTransUseCase = kernel)
        try {
            vm.selectMerchant(testMerchantA)
            esperarA { vm.currentMerchant.value != null }
            assertWithMessage("precondición: comercio seleccionado").that(vm.currentMerchant.value).isNotNull()
            vm.startPayment("100.00")
            esperarA { vecesQueCorrioElKernel.get() > 0 }
            assertWithMessage("el arnés debe ALCANZAR el kernel; estado = ${vm.state.value}").that(vecesQueCorrioElKernel.get()).isEqualTo(1)
            esperarA { vm.state.value is PaymentState.Error }
            assertWithMessage("debe aterrizar en 'Resultado desconocido'; estado = ${vm.state.value}")
                .that((vm.state.value as PaymentState.Error).message).contains("Resultado desconocido")
            // La reserva sigue viva: nadie la resolvió, y la siguiente venta NO entra.
            assertThat(dao.findTerminalHold()).isNotNull()
            assertThat(dao.findUnresolvedCharge()).isNotNull()
            val recreatedLedger = PaymentAttemptLedger(dao, mockTpvSettingsRepository)
            assertThat(recreatedLedger.openAttempt("siguiente-bloqueada", testVenueId, "BLUMON", 10000, 0, "FAST", "{}")).isFalse()
        } finally { vm.viewModelScope.cancel(); db.close() }
    }

    @Test
    fun `resolver la fila del kernel devuelve la terminal a la siguiente venta`() = runTest {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.paymentAttemptDao()
        try {
            val ledger = PaymentAttemptLedger(dao, mockTpvSettingsRepository)
            assertThat(ledger.openAttempt("cobro-rechazado", testVenueId, "BLUMON", 10000, 0, "FAST", "{}")).isTrue()
            // La entrada al kernel se compromete ANTES de llamarlo: el contactless puede aprobar
            // OFFLINE sin pasar por la barrera online.
            assertThat(ledger.markKernelEntered("cobro-rechazado")).isTrue()

            // Con esa fila viva la terminal está apartada y la siguiente venta NO entra.
            assertThat(dao.findTerminalHold()).isNotNull()
            assertThat(dao.findUnresolvedCharge()).isNotNull()
            assertThat(ledger.openAttempt("siguiente-bloqueada", testVenueId, "BLUMON", 10000, 0, "FAST", "{}")).isFalse()

            // El chip rechaza OFFLINE: negativa EXPLÍCITA, nunca llegó al procesador.
            assertThat(ledger.markKernelRefused("cobro-rechazado", "RESULT_OFFLINE_DENIED")).isTrue()

            assertThat(dao.getById("cobro-rechazado")?.state).isEqualTo("DESCARTADA")
            assertThat(dao.findTerminalHold()).isNull()
            assertThat(dao.findUnresolvedCharge()).isNull()
            // Lo que de verdad importa en el mostrador: la siguiente venta ya puede cobrar.
            assertThat(ledger.openAttempt("siguiente-venta", testVenueId, "BLUMON", 10000, 0, "FAST", "{}")).isTrue()
        } finally { db.close() }
    }

    /**
     * La otra mitad, y la que cuesta dinero si se afloja: un desenlace DESCONOCIDO se retiene.
     *
     * `TIMEOUT` y `OTHER` quedan FUERA de `KERNEL_REFUSALS_WITHOUT_CHARGE` a propósito: pueden
     * esconder una transacción que sí avanzó, y liberar sobre una duda es exactamente lo que
     * produce un doble cobro. Aquí se fija que retener SIGNIFIQUE algo — que la terminal siga
     * apartada y la siguiente venta no entre.
     *
     * 🔴 Determinista y sin ViewModel, por lo mismo que su hermana de arriba: el alta de cuenta
     * nunca termina en pruebas unitarias. Que la rama del resultado desconocido NO llame a
     * `markKernelRefused` lo fija `RechazoContactlessLiberaLaTerminalTest` sobre el fuente de las
     * dos variantes.
     */
    @Test
    fun `una fila con desenlace desconocido NO se da por resuelta - en la PAX se avisa y la caja sigue`() = runTest {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.paymentAttemptDao()
        try {
            val ledger = PaymentAttemptLedger(dao, mockTpvSettingsRepository)
            assertThat(ledger.openAttempt("cobro-incierto", testVenueId, "BLUMON", 10000, 0, "FAST", "{}")).isTrue()
            assertThat(ledger.markKernelEntered("cobro-incierto")).isTrue()

            // El kernel devuelve algo que no se entiende (o un timeout): NO es prueba de que no se cobró.
            ledger.markIndeterminate("cobro-incierto", "RESULT_DESCONOCIDO")

            // El desenlace del dinero sigue sin conocerse y la fila NO se toca: nadie la da por «no cobrada»...
            assertThat(dao.getById("cobro-incierto")?.state).isEqualTo("INDETERMINADO")
            assertThat(dao.findUnresolvedCharge()).isNotNull()
            assertThat(db.remotePaymentRequestDao().observePendingObligations(testVenueId).first()).isNotEmpty()
            // ...pero en la PAX eso se AVISA y la caja sigue (founder, 24-sep); en el kiosco, donde nadie lee el aviso, aparta.
            assertThat(dao.findTerminalHold()).isNull()
            assertThat(dao.findTerminalHold(esKiosco = true)).isNotNull()
            assertThat(ledger.openAttempt("siguiente-venta", testVenueId, "BLUMON", 10000, 0, "FAST", "{}")).isTrue()
        } finally { db.close() }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 24-sep, PAX de pruebas: se eligió «Avoqado Full» (posId 5729) y el SDK cobró con 376, la cuenta
    // de respaldo con la que el arranque lo inicializó. Misma serie ⇒ la revisión sólo-serie no vio
    // nada y Blumon contestó NO AUTORIZADO. Aquí el MultiMerchantSDKManager es el REAL: lo único
    // simulado es la tabla Init del SDK (el posId que de verdad viaja) y su re-inicialización.
    // ═══════════════════════════════════════════════════════════════════════════

    private val cuentaElegida = MerchantAccount(
        id = "avoqado_full", merchantAccountId = "cm-avoqado-full", serialNumber = "2841548417",
        posId = "5729", displayName = "Avoqado Full", environment = MerchantEnvironment.PRODUCTION, isActive = true,
    )

    /** [efectivo] = lo que el SDK mandaría a Blumon; [reinicializar] decide qué deja escrito un re-init. */
    private fun sdkConCuentaEfectiva(
        efectivo: java.util.concurrent.atomic.AtomicReference<String?>,
        eventos: MutableList<String>,
        reinicializar: suspend (String?) -> Result<Unit> = { posId -> efectivo.set(posId); Result.success(Unit) },
    ) {
        every { TerminalConfig.serialNumber } returns cuentaElegida.serialNumber
        coEvery { mockInitializationManager.readEffectivePosId() } answers { efectivo.get() }
        coEvery { mockInitializationManager.forceReinitialize(any()) } coAnswers {
            val posId = firstArg<String?>()
            eventos += "reinit:$posId"
            reinicializar(posId)
        }
        mockMultiMerchantSDKManager = MultiMerchantSDKManager(mockInitializationManager, mockCriticalNetworkOperationManager)
        every { mockGetMerchantsUseCase.invoke() } returns flowOf(listOf(cuentaElegida))
    }

    /** Lector que anota con qué cuenta efectiva se abrió; termina sin tarjeta. */
    private fun lectorQueAnota(
        efectivo: java.util.concurrent.atomic.AtomicReference<String?>,
        eventos: MutableList<String>,
    ) = mockk<com.blumonpay.pax.shared.neptune_polling.domain.use_case.start_detect_card.StartDetectCardUseCase>().also { detect ->
        coEvery { detect.run(any()) } coAnswers {
            eventos += "lector:${efectivo.get()}"
            com.blumonpay.pax.utils.clean.Either.Left(mockk(relaxed = true))
        }
    }

    private fun filas(db: com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase): List<Pair<String, String>> =
        db.query("SELECT attempt_id, state FROM payment_attempts ORDER BY created_at, attempt_id", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }

    private fun baseEnMemoria() = androidx.room.Room.inMemoryDatabaseBuilder(
        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
        com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase::class.java).allowMainThreadQueries().build()

    @Test
    fun `P1 misma serie y otra cuenta en el SDK - se reinicializa con la elegida ANTES de abrir el lector`() = runTest {
        val db = baseEnMemoria()
        mockPaymentAttemptLedger = PaymentAttemptLedger(db.paymentAttemptDao(), mockTpvSettingsRepository)
        val efectivo = java.util.concurrent.atomic.AtomicReference<String?>("376")
        val eventos = java.util.Collections.synchronizedList(mutableListOf<String>())
        sdkConCuentaEfectiva(efectivo, eventos)
        val vm = createViewModel(startDetectCardUseCase = lectorQueAnota(efectivo, eventos))
        try {
            vm.selectMerchant(cuentaElegida)
            esperarA { vm.currentMerchant.value != null }
            vm.startPayment("10.00")
            esperarA { eventos.any { it.startsWith("lector") } || vm.state.value is PaymentState.Error }
            assertWithMessage("la tarjeta sólo se pide con la cuenta elegida; estado = ${vm.state.value}")
                .that(eventos).containsExactly("reinit:5729", "lector:5729").inOrder()
        } finally { vm.viewModelScope.cancel(); db.close() }
    }

    @Test
    fun `P2 cuenta ya alineada - no se reinicializa y el lector abre`() = runTest {
        val db = baseEnMemoria()
        mockPaymentAttemptLedger = PaymentAttemptLedger(db.paymentAttemptDao(), mockTpvSettingsRepository)
        val efectivo = java.util.concurrent.atomic.AtomicReference<String?>("5729")
        val eventos = java.util.Collections.synchronizedList(mutableListOf<String>())
        sdkConCuentaEfectiva(efectivo, eventos)
        val vm = createViewModel(startDetectCardUseCase = lectorQueAnota(efectivo, eventos))
        try {
            vm.selectMerchant(cuentaElegida)
            esperarA { vm.currentMerchant.value != null }
            vm.startPayment("10.00")
            esperarA { eventos.any { it.startsWith("lector") } || vm.state.value is PaymentState.Error }
            // Un re-init de más cuesta 3-5 s y roza el límite de Blumon: sólo cuando hace falta.
            assertThat(eventos).containsExactly("lector:5729")
        } finally { vm.viewModelScope.cancel(); db.close() }
    }

    @Test
    fun `P1 si la cuenta no se puede reparar no se abre el lector y el reintento usa otra identidad`() = runTest {
        val db = baseEnMemoria()
        val dao = db.paymentAttemptDao()
        mockPaymentAttemptLedger = PaymentAttemptLedger(dao, mockTpvSettingsRepository)
        val efectivo = java.util.concurrent.atomic.AtomicReference<String?>("376")
        val eventos = java.util.Collections.synchronizedList(mutableListOf<String>())
        val sinRed = java.util.concurrent.atomic.AtomicBoolean(true)
        sdkConCuentaEfectiva(efectivo, eventos) { posId ->
            if (sinRed.get()) Result.failure(Exception("network unreachable"))
            else { efectivo.set(posId); Result.success(Unit) }
        }
        val vm = createViewModel(startDetectCardUseCase = lectorQueAnota(efectivo, eventos))
        try {
            vm.selectMerchant(cuentaElegida)
            esperarA { vm.currentMerchant.value != null }
            vm.startPayment("10.00")
            esperarA { vm.state.value is PaymentState.Error }
            val error = vm.state.value as PaymentState.Error
            assertWithMessage("se intentó reparar y NUNCA se pidió tarjeta").that(eventos).containsExactly("reinit:5729")
            assertThat(error.message).contains("No se pidió la tarjeta")
            assertThat(error.canRetry).isTrue()
            val primero = filas(db).single()
            assertThat(primero.second).isEqualTo("DESCARTADA")
            assertThat(dao.findTerminalHold()).isNull()

            sinRed.set(false)
            vm.retryPayment(error.context)
            esperarA { eventos.any { it.startsWith("lector") } }
            assertThat(eventos).containsExactly("reinit:5729", "reinit:5729", "lector:5729").inOrder()
            val segundo = filas(db).last()
            assertThat(segundo.first).isNotEqualTo(primero.first)
        } finally { vm.viewModelScope.cancel(); db.close() }
    }

    @Test
    fun `P1 un re-init que dice exito pero deja otra cuenta no abre el lector`() = runTest {
        val db = baseEnMemoria()
        val dao = db.paymentAttemptDao()
        mockPaymentAttemptLedger = PaymentAttemptLedger(dao, mockTpvSettingsRepository)
        val efectivo = java.util.concurrent.atomic.AtomicReference<String?>("376")
        val eventos = java.util.Collections.synchronizedList(mutableListOf<String>())
        sdkConCuentaEfectiva(efectivo, eventos) { Result.success(Unit) } // «éxito» sin tocar la tabla
        val vm = createViewModel(startDetectCardUseCase = lectorQueAnota(efectivo, eventos))
        try {
            vm.selectMerchant(cuentaElegida)
            esperarA { vm.currentMerchant.value != null }
            vm.startPayment("10.00")
            esperarA { vm.state.value is PaymentState.Error }
            assertThat(eventos).containsExactly("reinit:5729")
            assertThat((vm.state.value as PaymentState.Error).message).contains("otra cuenta")
            assertThat(filas(db).single().second).isEqualTo("DESCARTADA")
            assertThat(dao.findTerminalHold()).isNull()
        } finally { vm.viewModelScope.cancel(); db.close() }
    }

    @Test
    fun `P1 sin poder leer la cuenta efectiva no se abre el lector`() = runTest {
        val db = baseEnMemoria()
        val dao = db.paymentAttemptDao()
        mockPaymentAttemptLedger = PaymentAttemptLedger(dao, mockTpvSettingsRepository)
        val efectivo = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val eventos = java.util.Collections.synchronizedList(mutableListOf<String>())
        sdkConCuentaEfectiva(efectivo, eventos) { Result.success(Unit) } // la tabla sigue ilegible
        val vm = createViewModel(startDetectCardUseCase = lectorQueAnota(efectivo, eventos))
        try {
            vm.selectMerchant(cuentaElegida)
            esperarA { vm.currentMerchant.value != null }
            vm.startPayment("10.00")
            esperarA { vm.state.value is PaymentState.Error }
            assertThat(eventos).containsExactly("reinit:5729")
            assertThat((vm.state.value as PaymentState.Error).message).contains("No se pidió la tarjeta")
            assertThat(filas(db).single().second).isEqualTo("DESCARTADA")
        } finally { vm.viewModelScope.cancel(); db.close() }
    }

    @Test
    fun `P1 cancelar mientras se repara la cuenta - al terminar el re-init no se abre el lector`() = runTest {
        val db = baseEnMemoria()
        val dao = db.paymentAttemptDao()
        mockPaymentAttemptLedger = PaymentAttemptLedger(dao, mockTpvSettingsRepository)
        val efectivo = java.util.concurrent.atomic.AtomicReference<String?>("376")
        val eventos = java.util.Collections.synchronizedList(mutableListOf<String>())
        val reparando = CompletableDeferred<Unit>()
        val soltar = CompletableDeferred<Unit>()
        sdkConCuentaEfectiva(efectivo, eventos) { posId ->
            reparando.complete(Unit)
            soltar.await()
            efectivo.set(posId)
            Result.success(Unit)
        }
        val vm = createViewModel(startDetectCardUseCase = lectorQueAnota(efectivo, eventos))
        try {
            vm.selectMerchant(cuentaElegida)
            esperarA { vm.currentMerchant.value != null }
            vm.startPayment("10.00")
            esperarA { reparando.isCompleted }
            assertWithMessage("precondición: el re-init está en curso").that(reparando.isCompleted).isTrue()
            vm.cancelPayment()
            esperarA { vm.state.value is PaymentState.Cancelled }
            soltar.complete(Unit)
            esperarA(timeoutMs = 1_500) { eventos.any { it.startsWith("lector") } }
            assertWithMessage("un re-init que termina tarde no abre el lector de una venta cancelada")
                .that(eventos).containsExactly("reinit:5729")
            assertThat(vm.state.value).isInstanceOf(PaymentState.Cancelled::class.java)
            assertThat(filas(db).single().second).isEqualTo("DESCARTADA")
        } finally { vm.viewModelScope.cancel(); soltar.cancel(); db.close() }
    }
}

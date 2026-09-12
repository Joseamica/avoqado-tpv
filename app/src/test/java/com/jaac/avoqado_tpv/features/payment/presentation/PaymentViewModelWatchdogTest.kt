package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.lifecycle.viewModelScope
import com.blumonpay.pax.shared.trans_process.domain.TransProcessRepository
import com.blumonpay.pax.shared.trans_process.domain.use_case.set_select_app_code.SetSelectAppCodeUseCase
import com.example.clean_lib_services.shared.core.domain.use_case.sale_package.sale_icc.SaleIccUseCase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
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
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger
import com.jaac.avoqado_tpv.features.payment.data.local.AuthAttemptTelemetryStore
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.payment.domain.AuthWatchdogLevel
import com.jaac.avoqado_tpv.features.payment.domain.PaymentState
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantEnvironment
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordPaymentUseCase
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordRefundUseCase
import com.jaac.avoqado_tpv.features.payment.domain.use_case.GetMerchantsUseCase
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import com.paxsz.module.emv.process.contact.CandidateAID
import android.content.Context
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Task 2 (cobro resiliente en red lenta): verifica que el vigilante de autorización
 * (`AuthorizationWatchdog`, Task 1) quede correctamente cableado alrededor de
 * `performOnlineAuthorization` en el riel Blumon (PAX).
 *
 * ⚠️ StandardTestDispatcher, NO UnconfinedTestDispatcher: con Unconfined el trabajo del
 * init corre antes del verify y el test pasa en falso. Ya nos mordió una vez en este repo
 * (ver PaymentViewModelTest.kt).
 *
 * Reusa el patrón de construcción de `PaymentViewModelTest.kt` (34 dependencias, mocks
 * relajados salvo los que este archivo necesita controlar explícitamente) en vez de
 * inventar uno nuevo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentViewModelWatchdogTest {

    private val scheduler = TestCoroutineScheduler()
    private val testDispatcher = StandardTestDispatcher(scheduler)

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
    private lateinit var observabilityManager: com.jaac.avoqado_tpv.core.observability.ObservabilityManager
    private lateinit var mockPaymentQueueRepository:
        com.jaac.avoqado_tpv.features.payment.domain.repository.PaymentQueueRepository
    // ⭐ El único mock que este archivo necesita controlar activamente: la llamada SDK que
    // el vigilante observa (nunca cancela) durante la autorización online.
    private lateinit var mockSaleIccUseCase: SaleIccUseCase
    private lateinit var mockSaleCtlsUseCase: com.example.clean_lib_services.shared.core.domain.use_case.sale_package.sale_ctls.SaleCtlsUseCase

    private val socketEventsFlow = MutableSharedFlow<SocketEvent>()
    private val connectionRestoredFlow = MutableSharedFlow<com.jaac.avoqado_tpv.core.util.ConnectionRestoredEvent>()
    private val modulesFlow = MutableStateFlow<List<VenueModule>>(emptyList())
    private lateinit var selectAppStateFlow: MutableStateFlow<MutableList<CandidateAID>?>

    private val testMerchantA = MerchantAccount(
        id = "merchant_a",
        serialNumber = "2841548417",
        posId = "376",
        displayName = "Account A",
        environment = MerchantEnvironment.SANDBOX,
        isActive = true
    )

    // Estado de la autorización "pendiente" que cada test controla vía completeAuthorization().
    private lateinit var pendingAuthDeferred: CompletableDeferred<Unit>
    private lateinit var pendingAuthJob: Job

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        paymentStateHolder =
            com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder()
        mockkObject(TerminalConfig)
        every { TerminalConfig.serialNumber } returns "TEST-SERIAL"
        every { TerminalConfig.brand } returns "PAX"
        every { TerminalConfig.model } returns "A80"

        mockInitializationManager = mockk(relaxed = true) {
            every { isInitialized } returns MutableStateFlow(true)
            coEvery { ensureInitialized(any()) } returns Result.success(Unit)
            coEvery { awaitInitialization() } returns Result.success(Unit)
        }

        mockMultiMerchantSDKManager = mockk(relaxed = true) {
            every { getCurrentMerchant() } returns null
            every { isMerchantActive(any()) } returns false
            coEvery { switchMerchant(any()) } returns Result.success(Unit)
        }

        selectAppStateFlow = MutableStateFlow(null)
        mockTransProcessRepository = mockk(relaxed = true) {
            every { getEventPinDialogStateFlow() } returns MutableStateFlow(mockk(relaxed = true) {
                every { show } returns false
                every { dismiss } returns false
            })
            every { getKeyboardPinStateFlow() } returns MutableStateFlow("")
            every { getPinResultFlow() } returns MutableStateFlow(null)
            every { getSelectAppStateFlow() } returns selectAppStateFlow
            every { confirmCardReadingFlow() } returns MutableStateFlow(false)
        }

        mockShiftRepository = mockk(relaxed = true) {
            every { isShiftSystemEnabled() } returns true
        }

        mockTpvSettingsRepository = mockk(relaxed = true) {
            every { getCurrentSettings() } returns com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings.DEFAULT
        }

        mockAuthRepository = mockk(relaxed = true) {
            every { getVenueId() } returns "venue-test-001"
            every { getStaffId() } returns "staff-test-001"
        }

        mockSecureStorage = mockk(relaxed = true) {
            every { getSerialNumber() } returns "TEST-SERIAL"
        }

        mockSocketManager = mockk(relaxed = true) {
            every { events } returns socketEventsFlow
        }

        mockModulesRepository = mockk(relaxed = true) {
            every { modules } returns modulesFlow
        }

        mockGetMerchantsUseCase = mockk(relaxed = true)
        every { mockGetMerchantsUseCase.invoke() } returns flowOf(listOf(testMerchantA))

        mockRecordPaymentUseCase = mockk(relaxed = true)
        mockRecordRefundUseCase = mockk(relaxed = true)
        mockPaymentAttemptLedger = mockk(relaxed = true) {
            coEvery { markAuthorizing(any()) } returns true
            coEvery { markKernelEntered(any()) } returns true
            coEvery { markHostResponded(any(), any(), any(), any(), any()) } returns true
            // C.5: por defecto la solicitud no está cercada y el efectivo/cripto puede arrancar.
            coEvery { cercaDeSolicitud(any()) } returns com.jaac.avoqado_tpv.features.payment.data.ledger.CercaDeSolicitud.LIBRE
            coEvery { iniciarEjecucionNoTarjeta(any()) } returns com.jaac.avoqado_tpv.features.payment.data.ledger.CercaDeSolicitud.LIBRE
        }
        mockAuthAttemptTelemetryStore = mockk(relaxed = true)
        mockMerchantEligibilityRepository = mockk(relaxed = true) {
            coEvery { evaluate(any(), any(), any()) } returns
                com.jaac.avoqado_tpv.features.payment.domain.model.MerchantEligibility.disabled()
        }

        mockConnectionStateManager = mockk(relaxed = true) {
            every { isFullyConnected() } returns true
        }

        mockCriticalNetworkOperationManager = mockk(relaxed = true)
        mockSetSelectAppCodeUseCase = mockk(relaxed = true)

        mockConnectionEventManager = mockk(relaxed = true) {
            every { connectionRestoredEvents } returns connectionRestoredFlow
        }

        mockAppContext = mockk(relaxed = true)
        observabilityManager = mockk(relaxed = true)
        mockPaymentQueueRepository = mockk(relaxed = true)
        mockkObject(com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler)
        every { com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler.runNow(any()) } just Runs

        mockSaleIccUseCase = mockk(relaxed = true)
        mockSaleCtlsUseCase = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkObject(TerminalConfig)
        unmockkObject(com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler)
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        stopDetectCardUseCase: com.blumonpay.pax.shared.neptune_polling.domain.use_case.stop_detect_card.StopDetectCardUseCase =
            mockk(relaxed = true),
    ): PaymentViewModel {
        return PaymentViewModel(
            preTransUseCase = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.pre_trans.PreTransUseCase>(relaxed = true),
            startDetectCardUseCase = mockk<com.blumonpay.pax.shared.neptune_polling.domain.use_case.start_detect_card.StartDetectCardUseCase>(relaxed = true),
            stopDetectCardUseCase = stopDetectCardUseCase,
            startEmvTransUseCase = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.strat_emv_trans.StartEmvTransUseCase>(relaxed = true),
            startCtlssTransUseCase = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.start_ctlss_trans.StartCtlssTransUseCase>(relaxed = true),
            getEmvTagUseCase = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.get_emv_tags.GetEmvTagUseCase>(relaxed = true),
            completeEmvTransUseCase = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.complete_emv_trans.CompleteEmvTransUseCase>(relaxed = true),
            continueConfirmCardUseCase = mockk<com.blumonpay.pax.shared.trans_process.domain.use_case.continue_confirm_card.ContinueConfirmCardUseCase>(relaxed = true),
            setSelectAppCodeUseCase = mockSetSelectAppCodeUseCase,
            saleIccUseCase = mockSaleIccUseCase,
            saleCtlsUseCase = mockSaleCtlsUseCase,
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
            refundQueueRepository = mockk(relaxed = true),
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
            paymentStateHolder = paymentStateHolder,
            connectionEventManager = mockConnectionEventManager,
            paymentAttemptLedger = mockPaymentAttemptLedger,
            observability = observabilityManager,
            authAttemptTelemetryStore = mockAuthAttemptTelemetryStore,
            appContext = mockAppContext
        )
    }

    /**
     * Deja al ViewModel en la ventana de espera de autorización online (equivalente a lo
     * que `startPayment()` produce justo antes de llamar a `performOnlineAuthorization`):
     * `_state` = `Processing("Autorizando con banco...")`, `_currentMerchant` configurado
     * con posId (si no, la función corta antes de llegar al SDK) y la llamada al SDK
     * (`saleIccUseCase.run`) suspendida indefinidamente hasta `completeAuthorization()`.
     *
     * Invoca `performOnlineAuthorization` DIRECTAMENTE (es `@VisibleForTesting internal`,
     * mismo patrón que `handleOfflineQueueOutcome` en `PaymentViewModelTest.kt`) en vez de
     * recorrer todo `startPayment()` — evitar esto ahorra tener que simular PreTrans/
     * StartEmvTrans/GetEmvTags, que no son parte de lo que este task cablea.
     */
    private fun buildViewModelWithPendingAuthorization(isContactless: Boolean = false): PaymentViewModel {
        val vm = createViewModel()
        PaymentViewModel::class.java.getDeclaredField("sessionSnapshot").apply { isAccessible = true }.set(
            vm, com.jaac.avoqado_tpv.features.payment.domain.model.PaymentSession.empty().copy(paymentAttemptId = "test-attempt")
        )

        val merchantField = PaymentViewModel::class.java.getDeclaredField("_currentMerchant")
        merchantField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val merchantFlow = merchantField.get(vm) as MutableStateFlow<MerchantAccount?>
        merchantFlow.value = testMerchantA

        val stateField = PaymentViewModel::class.java.getDeclaredField("_state")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(vm) as MutableStateFlow<PaymentState>
        stateFlow.value = PaymentState.Processing("Autorizando con banco...")

        pendingAuthDeferred = CompletableDeferred()
        coEvery { mockSaleIccUseCase.run(any()) } coAnswers {
            pendingAuthDeferred.await()
            // Cualquier desenlace sirve para este test: lo único que importa es CUÁNDO
            // termina la llamada, no si aprueba o rechaza. Lanzar cae en el catch(Exception)
            // ya existente de performOnlineAuthorization, que sigue devolviendo un resultado.
            throw RuntimeException("PaymentViewModelWatchdogTest: autorización resuelta")
        }

        pendingAuthJob = vm.viewModelScope.launch {
            vm.performOnlineAuthorization(
                amount = "100.00",
                track2 = "",
                cardHolderName = "CARDHOLDER",
                emvTagList = "",
                isContactless = isContactless,
            )
        }
        return vm
    }

    @Test
    fun `exception after authorization entry blocks legacy retry`() {
        val vm = buildViewModelWithPendingAuthorization()
        scheduler.runCurrent()
        completeAuthorization(vm)
        vm.retryPayment(null)
        val state = vm.state.value
        org.junit.Assert.assertTrue("Unknown authorization must remain visible", state is PaymentState.Error)
        org.junit.Assert.assertFalse((state as PaymentState.Error).canRetry)
        org.junit.Assert.assertTrue(state.message.contains("No vuelvas a pasar la tarjeta"))
    }

    @Test
    fun `GenericFailure after authorization preserves unknown and never authorizes on retry`() {
        val vm = buildViewModelWithPendingAuthorization()
        coEvery { mockSaleIccUseCase.run(any()) } returns
            com.example.clean_lib_services.utils.clean.Either.Left(
                com.example.clean_lib_services.shared.core.domain.use_case.sale_package.sale_icc.SaleIccFailure.GenericFailure("response lost")
            )
        try {
            scheduler.runCurrent()
            vm.retryPayment(null)
            scheduler.runCurrent()
            val state = vm.state.value as PaymentState.Error
            org.junit.Assert.assertFalse(state.canRetry)
            org.junit.Assert.assertTrue(state.message.contains("No vuelvas a pasar la tarjeta"))
            coVerify(exactly = 1) { mockSaleIccUseCase.run(any()) }
            coVerify(exactly = 1) { mockPaymentAttemptLedger.markIndeterminate("test-attempt", "GenericFailure") }
            coVerify(exactly = 0) { mockPaymentAttemptLedger.markHostResponded(any(), false, any(), any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `partial SDK response without authorization or issuer verdict remains unknown`() {
        val vm = buildViewModelWithPendingAuthorization()
        val response = mockk<com.example.clean_lib_services.shared.core.domain.use_case.sale_package.sale_icc.SaleIccResponse>(relaxed = true) {
            every { saleData.authorization } returns ""
            every { saleData.emvResponseCode } returns ""
            every { saleData.description } returns ""
        }
        coEvery { mockSaleIccUseCase.run(any()) } returns
            com.example.clean_lib_services.utils.clean.Either.Right(response)
        try {
            scheduler.runCurrent()
            vm.retryPayment(null)
            scheduler.runCurrent()
            org.junit.Assert.assertTrue("Partial response must preserve unknown Error, got ${vm.state.value}", vm.state.value is PaymentState.Error)
            val state = vm.state.value as PaymentState.Error
            org.junit.Assert.assertFalse(state.canRetry)
            org.junit.Assert.assertTrue(state.message.contains("No vuelvas a pasar la tarjeta"))
            coVerify(exactly = 1) { mockSaleIccUseCase.run(any()) }
            coVerify(exactly = 0) { mockPaymentAttemptLedger.markHostResponded(any(), false, any(), any(), any()) }
            coVerify(exactly = 1) { mockPaymentAttemptLedger.markIndeterminate("test-attempt", any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `cancel queued before authorization cannot publish cancellation after SDK entry`() {
        val vm = buildViewModelWithPendingAuthorization()
        try {
            // cancel's synchronous guard runs before the already-queued SDK coroutine.
            vm.cancelPayment()
            scheduler.runCurrent()
            org.junit.Assert.assertFalse(vm.state.value is PaymentState.Cancelled)
            coVerify(exactly = 1) { mockSaleIccUseCase.run(any()) }
            coVerify(exactly = 0) { mockPaymentAttemptLedger.markDiscardedBeforeCharge(any(), any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun `reset after approved durable queue handoff never reports cancelled`() {
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "approved-queued")
            PaymentViewModel::class.java.getDeclaredField("authorizationApproved").apply { isAccessible = true }.set(vm, true)
            // Approval remains true after the queue takes durable ownership; unknown is false.
            vm.resetPayment()
            io.mockk.verify(exactly = 0) {
                mockSocketManager.emitTerminalPaymentResult(any(), "cancelled", any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
            }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun `review missing posId cannot publish PRE when durable discard did not commit`() {
        val vm = buildViewModelWithPendingAuthorization()
        vm.setSocketPaymentSource("SOCKET", "missing-pos-id")
        val merchantField = PaymentViewModel::class.java.getDeclaredField("_currentMerchant").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val merchant = merchantField.get(vm) as MutableStateFlow<MerchantAccount?>
        merchant.value = testMerchantA.copy(posId = null)
        coEvery { mockPaymentAttemptLedger.markDiscardedBeforeCharge(any(), any()) } returns false
        try {
            scheduler.runCurrent()
            val stateField = PaymentViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val state = stateField.get(vm) as MutableStateFlow<PaymentState>
            state.value = PaymentState.Error("Missing posId", canRetry = false)
            scheduler.runCurrent()
            io.mockk.verify(exactly = 0) {
                mockSocketManager.emitTerminalPaymentResult(any(), "failed", any(), any(), any(), any(), any(), any(), outcomeEvidence = "PRE_AUTHORIZATION")
            }
            coVerify(exactly = 0) { mockSaleIccUseCase.run(any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun `explicit issuer decline carries processor evidence to socket result`() {
        val vm = buildViewModelWithPendingAuthorization()
        vm.setSocketPaymentSource("SOCKET", "issuer-decline")
        val response = mockk<com.example.clean_lib_services.shared.core.domain.use_case.sale_package.sale_icc.SaleIccResponse>(relaxed = true) {
            every { saleData.authorization } returns ""
            every { saleData.emvResponseCode } returns "3035"
            every { saleData.description } returns "Do not honor"
        }
        coEvery { mockSaleIccUseCase.run(any()) } returns com.example.clean_lib_services.utils.clean.Either.Right(response)
        try {
            scheduler.runCurrent()
            val field = PaymentViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val state = field.get(vm) as MutableStateFlow<PaymentState>
            state.value = PaymentState.Error("Issuer declined", canRetry = false)
            scheduler.runCurrent()
            io.mockk.verify(exactly = 1) {
                mockSocketManager.emitTerminalPaymentResult(
                    any(), "failed", any(), any(), any(), any(), any(), any(),
                    outcomeEvidence = "PROCESSOR_DECLINED",
                )
            }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun `cancel before any financial operation carries preauthorization proof`() {
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "pre-sdk-cancel")
            vm.cancelPayment()
            scheduler.runCurrent()
            io.mockk.verify(exactly = 1) {
                mockSocketManager.emitTerminalPaymentResult(any(), "cancelled", any(), any(), any(), any(), any(), any(), outcomeEvidence = "PRE_AUTHORIZATION")
            }
            coVerify(exactly = 0) { mockSaleIccUseCase.run(any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun `cancel with preparing attempt carries proof only after committed discard`() {
        val vm = createViewModel()
        PaymentViewModel::class.java.getDeclaredField("sessionSnapshot").apply { isAccessible = true }.set(
            vm, com.jaac.avoqado_tpv.features.payment.domain.model.PaymentSession.empty().copy(paymentAttemptId = "preparing")
        )
        coEvery { mockPaymentAttemptLedger.markDiscardedBeforeCharge("preparing", any()) } returns true
        try {
            vm.setSocketPaymentSource("SOCKET", "preparing-cancel")
            vm.cancelPayment()
            scheduler.runCurrent()
            io.mockk.verify(exactly = 1) {
                mockSocketManager.emitTerminalPaymentResult(any(), "cancelled", any(), any(), any(), any(), any(), any(), outcomeEvidence = "PRE_AUTHORIZATION")
            }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun `uncommitted discard cannot carry preauthorization proof`() {
        val vm = createViewModel()
        PaymentViewModel::class.java.getDeclaredField("sessionSnapshot").apply { isAccessible = true }.set(
            vm, com.jaac.avoqado_tpv.features.payment.domain.model.PaymentSession.empty().copy(paymentAttemptId = "preparing")
        )
        coEvery { mockPaymentAttemptLedger.markDiscardedBeforeCharge("preparing", any()) } returns false
        try {
            vm.setSocketPaymentSource("SOCKET", "failed-discard")
            vm.cancelPayment()
            scheduler.runCurrent()
            io.mockk.verify(exactly = 0) {
                mockSocketManager.emitTerminalPaymentResult(any(), "cancelled", any(), any(), any(), any(), any(), any(), outcomeEvidence = "PRE_AUTHORIZATION")
            }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun `contactless generic chip insertion text cannot prove issuer refusal`() {
        val vm = buildViewModelWithPendingAuthorization(isContactless = true)
        coEvery { mockSaleCtlsUseCase.run(any()) } returns com.example.clean_lib_services.utils.clean.Either.Left(
            com.example.clean_lib_services.shared.core.domain.use_case.sale_package.sale_ctls.SaleCtlsFailure.GenericFailure("description=INSERTE TARJETA")
        )
        try {
            scheduler.runCurrent()
            coVerify(exactly = 1) { mockSaleCtlsUseCase.run(any()) }
            coVerify(exactly = 0) { mockPaymentAttemptLedger.markHostResponded(any(), false, any(), any(), any()) }
            coVerify(exactly = 1) { mockPaymentAttemptLedger.markIndeterminate("test-attempt", any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    private fun completeAuthorization(vm: PaymentViewModel) {
        pendingAuthDeferred.complete(Unit)
        scheduler.advanceUntilIdle()
    }

    private fun authorizationCallStillActive(vm: PaymentViewModel): Boolean = pendingAuthJob.isActive

    private fun PaymentViewModel.currentWatchdogLevel(): AuthWatchdogLevel =
        (state.value as? PaymentState.Processing)?.watchdogLevel ?: AuthWatchdogLevel.NONE

    // ═══════════════════════════════════════════════════════════════════════════
    // Task 2 — vigilante cableado en el riel Blumon (PAX)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `una autorizacion rapida nunca muestra aviso`() = runTest(testDispatcher) {
        val vm = buildViewModelWithPendingAuthorization()
        advanceTimeBy(3_000L)
        assertEquals(AuthWatchdogLevel.NONE, vm.currentWatchdogLevel())
        completeAuthorization(vm)
        assertEquals(AuthWatchdogLevel.NONE, vm.currentWatchdogLevel())

        vm.viewModelScope.cancel()
    }

    @Test
    fun `a los 8 segundos sin respuesta aparece el aviso`() = runTest(testDispatcher) {
        val vm = buildViewModelWithPendingAuthorization()
        advanceTimeBy(8_100L)
        assertEquals(AuthWatchdogLevel.SLOW, vm.currentWatchdogLevel())

        vm.viewModelScope.cancel()
    }

    @Test
    fun `a los 25 segundos escala`() = runTest(testDispatcher) {
        val vm = buildViewModelWithPendingAuthorization()
        advanceTimeBy(25_100L)
        assertEquals(AuthWatchdogLevel.VERY_SLOW, vm.currentWatchdogLevel())

        vm.viewModelScope.cancel()
    }

    @Test
    fun `el vigilante NUNCA cancela la autorizacion`() = runTest(testDispatcher) {
        // El invariante de dinero: pase lo que pase con el reloj, la llamada
        // del SDK sigue viva. Si el procesador aprobo y abandonamos, hay dinero
        // movido que la app no conoce.
        val vm = buildViewModelWithPendingAuthorization()
        advanceTimeBy(120_000L)
        assertEquals(true, authorizationCallStillActive(vm))

        pendingAuthDeferred.complete(Unit)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `al terminar la autorizacion el observador se apaga`() = runTest(testDispatcher) {
        val vm = buildViewModelWithPendingAuthorization()
        advanceTimeBy(9_000L)
        completeAuthorization(vm)
        advanceTimeBy(60_000L)
        // Sin fuga: ya no debe seguir escalando despues de terminar.
        assertEquals(AuthWatchdogLevel.NONE, vm.currentWatchdogLevel())

        vm.viewModelScope.cancel()
    }

    // ------------------------------------------------------------------
    // Turnos desactivados NO deben costar una vuelta de red (bug real 2026-08-08:
    // el fetch del turno corria ANTES de preguntar isShiftSystemEnabled() y un
    // socket medio muerto congelo "iniciar pago" 22.4s EN SILENCIO — la fase no
    // tiene vigilante. AngelPay ya lo hacia bien desde 2026-05-20; esto es la
    // paridad que nunca se porto al riel Blumon.)
    // ------------------------------------------------------------------

    @Test
    fun `turnos desactivados - iniciar pago no consulta el turno por red`() = runTest(scheduler) {
        every { mockShiftRepository.isShiftSystemEnabled() } returns false

        val vm = createViewModel()
        PaymentViewModel::class.java.getDeclaredField("sessionSnapshot").apply { isAccessible = true }.set(
            vm, com.jaac.avoqado_tpv.features.payment.domain.model.PaymentSession.empty().copy(paymentAttemptId = "test-attempt")
        )
        vm.startPayment("100.00")
        scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { mockShiftRepository.getCurrentShift(any()) }

        vm.viewModelScope.cancel()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 🛑 H.3 + C.5 (11-sep) — el desenlace de un cobro remoto sale UNA vez, y el POS puede cancelarlo
    // ═══════════════════════════════════════════════════════════════════════════

    private fun ponerEstado(vm: PaymentViewModel, estado: PaymentState) {
        val campo = PaymentViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (campo.get(vm) as MutableStateFlow<PaymentState>).value = estado
    }

    /** Deja el VM como tras un RECHAZO del emisor (autorización en blanco + código 05), como el flujo real. */
    private fun vmConRechazoDelBanco(requestId: String): PaymentViewModel {
        val vm = createViewModel()
        PaymentViewModel::class.java.getDeclaredField("sessionSnapshot").apply { isAccessible = true }.set(
            vm, com.jaac.avoqado_tpv.features.payment.domain.model.PaymentSession.empty().copy(paymentAttemptId = "attempt-rechazado"),
        )
        val merchantField = PaymentViewModel::class.java.getDeclaredField("_currentMerchant").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (merchantField.get(vm) as MutableStateFlow<MerchantAccount?>).value = testMerchantA
        vm.setSocketPaymentSource("SOCKET", requestId)

        val respuesta = mockk<com.example.clean_lib_services.shared.core.domain.use_case.sale_package.sale_icc.SaleIccResponse>(relaxed = true)
        every { respuesta.saleData.authorization } returns "" // rechazo del emisor: autorización en blanco
        every { respuesta.saleData.emvResponseCode } returns "05"
        every { respuesta.saleData.description } returns "PAGO NO PERMITIDO EMISOR"
        coEvery { mockSaleIccUseCase.run(any()) } returns
            com.example.clean_lib_services.utils.clean.Either.Right(respuesta)

        vm.viewModelScope.launch {
            vm.performOnlineAuthorization(amount = "100.00", track2 = "", cardHolderName = "CARDHOLDER", emvTagList = "")
        }
        scheduler.advanceUntilIdle()
        return vm
    }

    @Test
    fun `un rechazo del banco no emite nada mientras se pueda reintentar, y al salir sale failed con PROCESSOR_DECLINED`() =
        runTest(scheduler) {
            val vm = vmConRechazoDelBanco("req-rechazo")
            try {
                // El flujo publica el Error REINTENTABLE que ve el cajero.
                ponerEstado(vm, PaymentState.Error(message = "Pago rechazado por el banco", context = null, canRetry = true))
                scheduler.runCurrent()

                verify(exactly = 0) {
                    mockSocketManager.emitTerminalPaymentResult(any(), any(), any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
                }

                vm.resetPayment()
                scheduler.advanceUntilIdle()

                // 🛑 `failed`, no `cancelled`: el servidor sólo acredita PROCESSOR_DECLINED con `failed`.
                verify(exactly = 1) {
                    mockSocketManager.emitTerminalPaymentResult(
                        "req-rechazo", "failed", any(), any(), any(), any(), any(), any(),
                        outcomeEvidence = "PROCESSOR_DECLINED",
                    )
                }
                verify(exactly = 0) {
                    mockSocketManager.emitTerminalPaymentResult(any(), "cancelled", any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
                }
            } finally { vm.viewModelScope.cancel() }
        }

    @Test
    fun `un cobro remoto que nadie retoma se cierra solo con UN desenlace`() = runTest(scheduler) {
        val vm = vmConRechazoDelBanco("req-abandonado")
        vm.msAbandonoDelCobroRemoto = 100L
        try {
            ponerEstado(vm, PaymentState.Error(message = "Pago rechazado por el banco", context = null, canRetry = true))
            scheduler.runCurrent()

            scheduler.advanceTimeBy(300)
            scheduler.runCurrent()

            verify(exactly = 1) {
                mockSocketManager.emitTerminalPaymentResult(
                    "req-abandonado", "failed", any(), any(), any(), any(), any(), any(),
                    outcomeEvidence = "PROCESSOR_DECLINED",
                )
            }
            val estado = vm.state.value as PaymentState.Error
            assertFalse(estado.canRetry)
            assertEquals(CobroRemotoDelPos.CERRADO_POR_ABANDONO, estado.message)

            // Y al salir NO sale un segundo desenlace: una solicitud tiene a lo más UNO.
            vm.resetPayment()
            scheduler.advanceUntilIdle()
            verify(exactly = 1) {
                mockSocketManager.emitTerminalPaymentResult(
                    "req-abandonado", any(), any(), any(), any(), any(), any(), any(), outcomeEvidence = any(),
                )
            }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun `tras un rechazo, un reintento aprobado emite success y ningun desenlace negativo`() = runTest(scheduler) {
        val vm = vmConRechazoDelBanco("req-rechazo-y-exito")
        try {
            ponerEstado(vm, PaymentState.Error(message = "Pago rechazado por el banco", context = null, canRetry = true))
            scheduler.runCurrent()
            // El cajero reintenta con otra tarjeta y esta vez aprueba: el cobro se registra.
            ponerEstado(
                vm,
                PaymentState.Success(
                    authCode = "A1", amount = "100.00", tipAmount = "0.00",
                    receipt = com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt(
                        paymentId = "pay-ok", receiptUrl = "https://receipt/pay-ok", accessKey = "k",
                        amount = java.math.BigDecimal("100.00"), tipAmount = java.math.BigDecimal.ZERO,
                    ),
                ),
            )
            scheduler.advanceUntilIdle()

            verify(exactly = 1) {
                mockSocketManager.emitTerminalPaymentResult(
                    "req-rechazo-y-exito", "success", any(), any(), any(), any(), any(), any(), outcomeEvidence = any(),
                )
            }
            verify(exactly = 0) {
                mockSocketManager.emitTerminalPaymentResult(any(), "failed", any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
            }
            verify(exactly = 0) {
                mockSocketManager.emitTerminalPaymentResult(any(), "cancelled", any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
            }
        } finally { vm.viewModelScope.cancel() }
    }

    /**
     * 🛑 Evidencia POR INTENTO: el rechazo del intento anterior no puede viajar como prueba de un intento
     * NUEVO. Sin esto, un `PROCESSOR_DECLINED` del primero certificaba «no se cobró» sobre un segundo
     * intento que quedó incierto (contactless con TIMEOUT, resultado desconocido).
     */
    @Test
    fun `un intento nuevo no hereda la evidencia del rechazo anterior`() = runTest(scheduler) {
        every { mockShiftRepository.isShiftSystemEnabled() } returns false
        val vm = vmConRechazoDelBanco("req-evidencia")
        try {
            vm.startPayment("100.00") // intento NUEVO: la evidencia del anterior se limpia
            scheduler.advanceUntilIdle()

            vm.resetPayment()
            scheduler.advanceUntilIdle()

            // El intento nuevo quedó INCIERTO: sale un desenlace, pero SIN evidencia negativa.
            verify(exactly = 1) {
                mockSocketManager.emitTerminalPaymentResult(
                    "req-evidencia", any(), any(), any(), any(), any(), any(), any(), outcomeEvidence = null,
                )
            }
            verify(exactly = 0) {
                mockSocketManager.emitTerminalPaymentResult(
                    any(), any(), any(), any(), any(), any(), any(), any(), outcomeEvidence = "PROCESSOR_DECLINED",
                )
            }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun `un cancel ACEPTADO del POS detiene la lectura, lo dice en pantalla y no emite otro desenlace`() = runTest(scheduler) {
        val stopDetect = mockk<com.blumonpay.pax.shared.neptune_polling.domain.use_case.stop_detect_card.StopDetectCardUseCase>(relaxed = true)
        val vm = createViewModel(stopDetectCardUseCase = stopDetect)
        try {
            vm.setSocketPaymentSource("SOCKET", "req-cancelado")
            ponerEstado(vm, PaymentState.DetectingCard("100.00"))
            scheduler.runCurrent()

            vm.manejarCancelacionRemota(
                SocketEvent.TerminalPaymentCancel("req-cancelado", "cancelado desde el POS", "2026-09-11T12:00:00Z", "ACCEPTED"),
            )
            scheduler.advanceUntilIdle()

            coVerify(atLeast = 1) { stopDetect.runInfallible(any()) }
            val estado = vm.state.value as PaymentState.Error
            assertFalse(estado.canRetry)
            assertEquals(CobroRemotoDelPos.CANCELADO_POR_EL_POS, estado.message)
            assertEquals(CobroRemotoDelPos.CANCELADO_POR_EL_POS, vm.mensajeDelPos.value)
            // La bandeja ya escribió el desenlace durable: este VM no emite otro, ni al salir.
            vm.resetPayment()
            scheduler.advanceUntilIdle()
            verify(exactly = 0) {
                mockSocketManager.emitTerminalPaymentResult(any(), any(), any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
            }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun `el cancel de OTRA solicitud no toca este cobro`() = runTest(scheduler) {
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-mio")
            ponerEstado(vm, PaymentState.DetectingCard("100.00"))
            scheduler.runCurrent()

            vm.manejarCancelacionRemota(
                SocketEvent.TerminalPaymentCancel("req-de-otro", "cancelado desde el POS", "2026-09-11T12:00:00Z", "ACCEPTED"),
            )
            scheduler.advanceUntilIdle()

            assertTrue(vm.state.value is PaymentState.DetectingCard)
            assertEquals(null, vm.mensajeDelPos.value)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun `la cerca de la libreta se traduce a - el POS cancelo este cobro -`() = runTest(scheduler) {
        every { mockShiftRepository.isShiftSystemEnabled() } returns false
        coEvery { mockPaymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) } returns false
        coEvery { mockPaymentAttemptLedger.cercaDeSolicitud(any()) } returns
            com.jaac.avoqado_tpv.features.payment.data.ledger.CercaDeSolicitud.CANCELADA_POR_EL_POS
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-cercado")
            vm.startPayment("100.00")
            scheduler.advanceUntilIdle()

            val estado = vm.state.value as PaymentState.Error
            assertEquals(CobroRemotoDelPos.CANCELADO_POR_EL_POS, estado.message)
            assertFalse(estado.canRetry)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun `el efectivo de un cobro remoto no se registra si el POS ya cancelo`() = runTest(scheduler) {
        coEvery { mockPaymentAttemptLedger.iniciarEjecucionNoTarjeta(any()) } returns
            com.jaac.avoqado_tpv.features.payment.data.ledger.CercaDeSolicitud.CANCELADA_POR_EL_POS
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-efectivo")
            vm.processCashPayment("100.00")
            scheduler.advanceUntilIdle()

            coVerify(exactly = 0) { mockRecordPaymentUseCase(any(), any(), any(), any()) }
            assertEquals(CobroRemotoDelPos.CANCELADO_POR_EL_POS, (vm.state.value as PaymentState.Error).message)
        } finally { vm.viewModelScope.cancel() }
    }

    /**
     * REGRESION y anti-vacuidad: con turnos ACTIVADOS el fetch SI debe ocurrir.
     * Si este test falla, el arnes no esta llegando al codigo del turno y el
     * test de arriba pasaria en vacio — los dos se leen JUNTOS.
     */
    @Test
    fun `turnos activados - el turno se consulta como siempre`() = runTest(scheduler) {
        every { mockShiftRepository.isShiftSystemEnabled() } returns true
        coEvery { mockShiftRepository.getCurrentShift(any()) } returns
            com.jaac.avoqado_tpv.core.domain.models.Result.Success(null)

        val vm = createViewModel()
        PaymentViewModel::class.java.getDeclaredField("sessionSnapshot").apply { isAccessible = true }.set(
            vm, com.jaac.avoqado_tpv.features.payment.domain.model.PaymentSession.empty().copy(paymentAttemptId = "test-attempt")
        )
        vm.startPayment("100.00")
        scheduler.advanceUntilIdle()

        coVerify(atLeast = 1) { mockShiftRepository.getCurrentShift(any()) }

        vm.viewModelScope.cancel()
    }
}

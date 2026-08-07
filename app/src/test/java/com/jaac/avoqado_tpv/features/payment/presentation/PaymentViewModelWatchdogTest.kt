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
import org.junit.After
import org.junit.Assert.assertEquals
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
        mockPaymentAttemptLedger = mockk(relaxed = true)
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
    }

    @After
    fun tearDown() {
        unmockkObject(TerminalConfig)
        unmockkObject(com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler)
        unmockkAll()
        Dispatchers.resetMain()
    }

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
            saleIccUseCase = mockSaleIccUseCase,
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
    private fun buildViewModelWithPendingAuthorization(): PaymentViewModel {
        val vm = createViewModel()

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
                emvTagList = ""
            )
        }
        return vm
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
}

package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.lifecycle.viewModelScope
import com.blumonpay.pax.shared.trans_process.domain.TransProcessRepository
import com.blumonpay.pax.shared.trans_process.domain.use_case.set_select_app_code.SetSelectAppCodeUseCase
import com.example.clean_lib_services.shared.core.domain.entity.sale_data.EntryMode
import com.example.clean_lib_services.shared.core.domain.use_case.sale_package.sale_ctls.SaleCtlsParams
import com.example.clean_lib_services.shared.core.domain.use_case.sale_package.sale_ctls.SaleCtlsUseCase
import com.example.clean_lib_services.shared.core.domain.use_case.sale_package.sale_icc.SaleIccParams
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
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Guarda el contrato del campo `reference` de lib_services 1.6.1.2 (y del `entryMode`
 * que la misma version pide en la ruta contactless).
 *
 * POR QUE IMPORTA — medido en el bytecode de los dos .aar, no en la documentacion:
 *
 *   - Hasta `lib-services-BP-SAND_1601`, `SaleIccUseCase`/`SaleCtlsUseCase` generaban el
 *     `reference` ELLOS MISMOS con
 *     `SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(Calendar.getInstance().time)`.
 *   - En 1.6.1.2 el campo se movio al parametro, PERO conservaron el respaldo:
 *     `params.reference.ifEmpty { ...la misma fecha... }`. Verificado en las 6 rutas de
 *     venta (icc, ctls, mag y sus tres variantes restaurant).
 *
 * Por eso mandamos CADENA VACIA: es lo unico que reproduce, byte por byte, lo que la
 * terminal viene haciendo desde siempre. Inventar un valor aqui cambiaria lo que Blumon
 * registra como referencia de la transaccion, en el camino del dinero, sin que nadie lo
 * haya decidido.
 *
 * `entryMode` NO tiene respaldo: la version vieja lo fijaba en `EntryMode.CONTACTLESS`
 * dentro de `SaleCtlsUseCase`. Mandar otra cosa haria que Blumon clasifique mal el modo
 * de entrada de la operacion.
 *
 * Si algun dia se decide mandar una referencia propia (p.ej. el id del pago de Avoqado,
 * que si serviria para conciliar: Blumon la devuelve en `SaleDataDTO.reference`), este
 * test debe fallar primero y actualizarse a proposito.
 *
 * StandardTestDispatcher, NO UnconfinedTestDispatcher (misma razon que en
 * PaymentViewModelWatchdogTest.kt).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlumonSaleReferenceTest {

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

    // Los dos mocks que este archivo controla: son las llamadas al SDK cuyos parametros capturamos.
    private lateinit var mockSaleIccUseCase: SaleIccUseCase
    private lateinit var mockSaleCtlsUseCase: SaleCtlsUseCase

    private val socketEventsFlow = MutableSharedFlow<SocketEvent>()
    private val connectionRestoredFlow = MutableSharedFlow<com.jaac.avoqado_tpv.core.util.ConnectionRestoredEvent>()
    private val modulesFlow = MutableStateFlow<List<VenueModule>>(emptyList())
    private lateinit var selectAppStateFlow: MutableStateFlow<MutableList<CandidateAID>?>

    private val testMerchant = MerchantAccount(
        id = "merchant_a",
        serialNumber = "2841548417",
        posId = "376",
        displayName = "Account A",
        environment = MerchantEnvironment.SANDBOX,
        isActive = true
    )

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
        every { mockGetMerchantsUseCase.invoke() } returns flowOf(listOf(testMerchant))

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
        mockSaleCtlsUseCase = mockk(relaxed = true)
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
     * Deja el ViewModel justo en la ventana de autorizacion online, igual que hace
     * PaymentViewModelWatchdogTest: sin `_currentMerchant` la funcion corta antes de
     * llegar al SDK y no se capturaria nada.
     */
    private fun viewModelReadyToAuthorize(): PaymentViewModel {
        val vm = createViewModel()

        val merchantField = PaymentViewModel::class.java.getDeclaredField("_currentMerchant")
        merchantField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (merchantField.get(vm) as MutableStateFlow<MerchantAccount?>).value = testMerchant

        val stateField = PaymentViewModel::class.java.getDeclaredField("_state")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (stateField.get(vm) as MutableStateFlow<PaymentState>).value =
            PaymentState.Processing("Autorizando con banco...")

        return vm
    }

    @Test
    fun `P1 el cobro por chip manda reference vacio para que la libreria genere la fecha`() =
        runTest(testDispatcher) {
            val vm = viewModelReadyToAuthorize()
            val captured = slot<SaleIccParams>()
            // Lanzar corta el flujo justo despues de capturar: cae en el catch(Exception)
            // que performOnlineAuthorization ya tiene.
            coEvery { mockSaleIccUseCase.run(capture(captured)) } throws
                RuntimeException("BlumonSaleReferenceTest: corte tras capturar")

            vm.performOnlineAuthorization(
                amount = "100.00",
                track2 = "",
                cardHolderName = "CARDHOLDER",
                emvTagList = "",
                isContactless = false
            )

            assertEquals(
                "reference debe ir VACIO: es lo unico que hace que 1.6.1.2 genere el mismo " +
                    "yyyyMMddHHmmss que generaba la libreria vieja",
                "",
                captured.captured.reference
            )
            vm.viewModelScope.cancel()
        }

    @Test
    fun `P1 el cobro contactless manda reference vacio para que la libreria genere la fecha`() =
        runTest(testDispatcher) {
            val vm = viewModelReadyToAuthorize()
            val captured = slot<SaleCtlsParams>()
            coEvery { mockSaleCtlsUseCase.run(capture(captured)) } throws
                RuntimeException("BlumonSaleReferenceTest: corte tras capturar")

            vm.performOnlineAuthorization(
                amount = "100.00",
                track2 = "",
                cardHolderName = "CARDHOLDER",
                emvTagList = "",
                isContactless = true
            )

            assertEquals(
                "reference debe ir VACIO tambien en contactless",
                "",
                captured.captured.reference
            )
            vm.viewModelScope.cancel()
        }

    @Test
    fun `P1 el cobro contactless declara entryMode CONTACTLESS`() =
        runTest(testDispatcher) {
            val vm = viewModelReadyToAuthorize()
            val captured = slot<SaleCtlsParams>()
            coEvery { mockSaleCtlsUseCase.run(capture(captured)) } throws
                RuntimeException("BlumonSaleReferenceTest: corte tras capturar")

            vm.performOnlineAuthorization(
                amount = "100.00",
                track2 = "",
                cardHolderName = "CARDHOLDER",
                emvTagList = "",
                isContactless = true
            )

            assertEquals(
                "entryMode NO tiene respaldo en 1.6.1.2: la libreria vieja lo fijaba en " +
                    "CONTACTLESS dentro de SaleCtlsUseCase. Mandar otra cosa hace que Blumon " +
                    "clasifique mal el modo de entrada de la operacion.",
                EntryMode.CONTACTLESS,
                captured.captured.entryMode
            )
            vm.viewModelScope.cancel()
        }
}

package com.jaac.avoqado_tpv.core.presentation.viewmodels

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentCoordinator
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.dao.ProductCategoryDao
import com.jaac.avoqado_tpv.core.data.local.CatalogoGuardado
import com.jaac.avoqado_tpv.core.data.manager.LockScreenManager
import com.jaac.avoqado_tpv.core.data.manager.MaintenanceManager
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.repository.HeartbeatRepository
import com.jaac.avoqado_tpv.core.domain.events.VenueStatusEvent
import com.jaac.avoqado_tpv.core.domain.repository.TerminalConfigRepository
import com.jaac.avoqado_tpv.core.session.SessionManager
import com.jaac.avoqado_tpv.core.util.ConnectionEventManager
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.core.util.UpdateCheckManager
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.authentication.domain.models.VenueStatus
import com.jaac.avoqado_tpv.features.ordering.domain.OrderSyncCoordinator
import com.jaac.avoqado_tpv.features.ordering.domain.ProductRepository
import com.jaac.avoqado_tpv.features.payment.data.InitializationManager
import com.jaac.avoqado_tpv.features.payment.domain.repository.MerchantRepository
import com.jaac.avoqado_tpv.features.payment.domain.use_case.GetMerchantsUseCase
import com.jaac.avoqado_tpv.features.remote_command.domain.CommandExecutor
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Uses UnconfinedTestDispatcher so init coroutines run eagerly through
 * their first iteration, then suspend at delay(). Each test cancels
 * viewModelScope at the end to prevent runTest from hanging.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val createdViewModels = mutableListOf<HomeViewModel>()

    private lateinit var authRepository: AuthRepository
    private lateinit var socketManager: SocketManager
    private lateinit var secureStorage: SecureStorage
    private lateinit var initializationManager: InitializationManager
    private lateinit var getMerchantsUseCase: GetMerchantsUseCase
    private lateinit var commandExecutor: CommandExecutor
    private lateinit var heartbeatRepository: HeartbeatRepository
    private lateinit var lockScreenManager: LockScreenManager
    private lateinit var maintenanceManager: MaintenanceManager
    private lateinit var productRepository: ProductRepository
    private lateinit var catalogoGuardado: CatalogoGuardado
    private lateinit var productCategoryDao: ProductCategoryDao
    private lateinit var apiService: ApiService
    private lateinit var connectionEventManager: ConnectionEventManager
    private lateinit var terminalConfigRepository: TerminalConfigRepository
    private lateinit var merchantRepository: MerchantRepository
    private lateinit var deviceInfoManager: DeviceInfoManager
    private lateinit var angelPaySdkGatewayProvider: javax.inject.Provider<com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPaySdkGateway>
    private lateinit var angelPayAuthRepositoryProvider: javax.inject.Provider<com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayAuthRepository>
    private lateinit var angelPayAuthRecoveryProvider: javax.inject.Provider<com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayAuthRecovery>
    private lateinit var connectionStateManager: com.jaac.avoqado_tpv.core.util.ConnectionStateManager
    private val fakeConnectionState = MutableStateFlow(com.jaac.avoqado_tpv.core.util.ConnectionState())
    private lateinit var remotePaymentCoordinator: RemotePaymentCoordinator
    private lateinit var updateCheckManager: UpdateCheckManager
    private lateinit var sessionManager: SessionManager
    private lateinit var paymentQueueRepository: com.jaac.avoqado_tpv.features.payment.domain.repository.PaymentQueueRepository
    private lateinit var paymentQueueStateManager: com.jaac.avoqado_tpv.core.util.PaymentQueueStateManager
    private lateinit var observabilityManager: com.jaac.avoqado_tpv.core.observability.ObservabilityManager
    private lateinit var orderSyncCoordinator: OrderSyncCoordinator
    private lateinit var timeEntryRepository: com.jaac.avoqado_tpv.features.timeclock.domain.repository.TimeEntryRepository
    private lateinit var tpvSettingsRepository: com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
    private lateinit var saleVerificationRepository: com.jaac.avoqado_tpv.features.payment.data.repository.SaleVerificationRepository
    private lateinit var printerManager: com.jaac.avoqado_tpv.core.printer.PrinterManager

    private val fakeSessionEvents = MutableSharedFlow<com.jaac.avoqado_tpv.core.session.SessionEvent>()
    private val fakeSocketEvents = MutableSharedFlow<com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent>()
    private val fakeConnectionRestoredEvents = MutableSharedFlow<com.jaac.avoqado_tpv.core.util.ConnectionRestoredEvent>()
    private val fakePendingUpdate = MutableStateFlow<com.jaac.avoqado_tpv.core.data.network.AvoqadoUpdateInfo?>(null)
    private val fakeIsConnected = MutableSharedFlow<Boolean>()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        authRepository = mockk(relaxed = true)
        socketManager = mockk(relaxed = true)
        secureStorage = mockk(relaxed = true)
        initializationManager = mockk(relaxed = true)
        getMerchantsUseCase = mockk(relaxed = true)
        commandExecutor = mockk(relaxed = true)
        heartbeatRepository = mockk(relaxed = true)
        lockScreenManager = mockk(relaxed = true)
        maintenanceManager = mockk(relaxed = true)
        productRepository = mockk(relaxed = true)
        catalogoGuardado = mockk(relaxed = true)
        productCategoryDao = mockk(relaxed = true)
        apiService = mockk(relaxed = true)
        connectionEventManager = mockk(relaxed = true)
        terminalConfigRepository = mockk(relaxed = true)
        merchantRepository = mockk(relaxed = true)
        deviceInfoManager = mockk(relaxed = true)
        angelPaySdkGatewayProvider = mockk(relaxed = true)
        angelPayAuthRepositoryProvider = mockk(relaxed = true)
        angelPayAuthRecoveryProvider = mockk(relaxed = true)
        connectionStateManager = mockk(relaxed = true)
        every { connectionStateManager.connectionState } returns fakeConnectionState
        remotePaymentCoordinator = mockk(relaxed = true)
        updateCheckManager = mockk(relaxed = true)
        sessionManager = mockk(relaxed = true)
        paymentQueueRepository = mockk(relaxed = true)
        paymentQueueStateManager = com.jaac.avoqado_tpv.core.util.PaymentQueueStateManager()
        observabilityManager = mockk(relaxed = true)
        orderSyncCoordinator = mockk(relaxed = true)
        timeEntryRepository = mockk(relaxed = true)
        tpvSettingsRepository = mockk(relaxed = true)
        saleVerificationRepository = mockk(relaxed = true)
        coEvery { saleVerificationRepository.getPendingVerifications() } returns Result.success(emptyList())
        printerManager = mockk(relaxed = true)

        every { sessionManager.sessionEvents } returns fakeSessionEvents
        every { socketManager.events } returns fakeSocketEvents
        every { socketManager.isConnected } returns fakeIsConnected
        every { connectionEventManager.connectionRestoredEvents } returns fakeConnectionRestoredEvents
        every { updateCheckManager.pendingUpdate } returns fakePendingUpdate

        every { secureStorage.getVenueStatus() } returns VenueStatus.ACTIVE
        every { secureStorage.getVenueId() } returns "venue-123"
        every { secureStorage.getToken() } returns "jwt-token"
        // Note: socketManager.isCurrentlyConnected() returns false by default (relaxed mock)
        // No need to mock explicitly — the SharedFlow property and the function used to
        // share the name `isConnected` and confused MockK; the function was renamed to
        // `isCurrentlyConnected()` to disambiguate.
    }

    @After
    fun tearDown() {
        try {
            runBlocking {
                createdViewModels.forEach { it.viewModelScope.cancel() }
                createdViewModels.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            }
        } finally {
            createdViewModels.clear()
            Dispatchers.resetMain()
            unmockkAll()
        }
    }

    private fun createViewModel(): HomeViewModel {
        return HomeViewModel(
            authRepository = authRepository,
            socketManager = socketManager,
            secureStorage = secureStorage,
            initializationManager = initializationManager,
            getMerchantsUseCase = getMerchantsUseCase,
            commandExecutor = commandExecutor,
            heartbeatRepository = heartbeatRepository,
            lockScreenManager = lockScreenManager,
            maintenanceManager = maintenanceManager,
            productRepository = productRepository,
            catalogoGuardado = catalogoGuardado,
            productCategoryDao = productCategoryDao,
            apiService = apiService,
            connectionEventManager = connectionEventManager,
            terminalConfigRepository = terminalConfigRepository,
            merchantRepository = merchantRepository,
            deviceInfoManager = deviceInfoManager,
            angelPaySdkGatewayProvider = angelPaySdkGatewayProvider,
            angelPayAuthRepositoryProvider = angelPayAuthRepositoryProvider,
            angelPayAuthRecoveryProvider = angelPayAuthRecoveryProvider,
            connectionStateManager = connectionStateManager,
            remotePaymentCoordinator = remotePaymentCoordinator,
            printerManager = printerManager,
            updateCheckManager = updateCheckManager,
            sessionManager = sessionManager,
            paymentQueueRepository = paymentQueueRepository,
            paymentQueueStateManager = paymentQueueStateManager,
            observabilityManager = observabilityManager,
            orderSyncCoordinator = orderSyncCoordinator,
            timeEntryRepository = timeEntryRepository,
            tpvSettingsRepository = tpvSettingsRepository,
            saleVerificationRepository = saleVerificationRepository
        ).also { createdViewModels.add(it) }
    }

    // ========================================
    // BLUMON SDK INITIALIZATION TESTS
    // ========================================
    // NOTE: Blumon SDK init tests removed — the init coroutine uses Dispatchers.IO
    // with delay(2000), which can't be controlled via test dispatcher. These would
    // require injecting a dispatcher parameter to test properly.

    // ========================================
    // STAFF NAME LOADING TESTS
    // ========================================

    @Test
    fun `staffName gets loaded from authRepository`() = runTest(testDispatcher) {
        coEvery { authRepository.getStaffName() } returns "Maria Garcia"

        val viewModel = createViewModel()

        assertThat(viewModel.staffName.value).isEqualTo("Maria Garcia")
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `staffName defaults to Usuario when null`() = runTest(testDispatcher) {
        coEvery { authRepository.getStaffName() } returns null

        val viewModel = createViewModel()

        assertThat(viewModel.staffName.value).isEqualTo("Usuario")
        viewModel.viewModelScope.cancel()
    }

    // ========================================
    // LOGOUT TESTS
    // ========================================

    @Test
    fun `logout disconnects socket and calls authRepository`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        viewModel.logout()

        verify { socketManager.disconnect() }
        verify { authRepository.logout() }
        viewModel.viewModelScope.cancel()
    }

    // ========================================
    // VENUE STATUS TESTS
    // ========================================

    @Test
    fun `venueStatus is loaded from secureStorage`() = runTest(testDispatcher) {
        every { secureStorage.getVenueStatus() } returns VenueStatus.TRIAL

        val viewModel = createViewModel()

        assertThat(viewModel.venueStatus.value).isEqualTo(VenueStatus.TRIAL)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `checkVenueStatusChange emits event when status changes`() = runTest(testDispatcher) {
        every { secureStorage.getVenueStatus() } returns VenueStatus.ACTIVE
        val viewModel = createViewModel()

        viewModel.venueStatusEvents.test {
            viewModel.checkVenueStatusChange(VenueStatus.SUSPENDED)

            val event = awaitItem()
            assertThat(event).isInstanceOf(VenueStatusEvent.VenueSuspended::class.java)
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `checkVenueStatusChange does not emit when unchanged`() = runTest(testDispatcher) {
        every { secureStorage.getVenueStatus() } returns VenueStatus.ACTIVE
        val viewModel = createViewModel()

        viewModel.venueStatusEvents.test {
            viewModel.checkVenueStatusChange(VenueStatus.ACTIVE)

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.viewModelScope.cancel()
    }

    // ========================================
    // MAINTENANCE MODE TESTS
    // ========================================

    @Test
    fun `exitMaintenance calls maintenanceManager`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        viewModel.exitMaintenance()

        verify { maintenanceManager.exitMaintenance() }
        viewModel.viewModelScope.cancel()
    }

    // ========================================
    // CANCEL BLUMON INIT TESTS
    // ========================================
    // NOTE: initializeBlumonSDK() uses Dispatchers.IO which can't be controlled
    // by test dispatcher. But cancelBlumonInit() is synchronous — we can test
    // it sets the correct state even if the init hasn't actually started.

    @Test
    fun `cancelBlumonInit sets initializing to false`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        viewModel.cancelBlumonInit()

        assertThat(viewModel.isBlumonInitializing.value).isFalse()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `cancelBlumonInit sets ready to false`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        viewModel.cancelBlumonInit()

        assertThat(viewModel.isBlumonReady.value).isFalse()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `cancelBlumonInit sets error message for retry`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        viewModel.cancelBlumonInit()

        assertThat(viewModel.blumonInitError.value).isNotNull()
        assertThat(viewModel.blumonInitError.value).contains("cancelada")
        viewModel.viewModelScope.cancel()
    }

    // ========================================
    // BLUMON INIT ELAPSED SECONDS TESTS
    // ========================================

    @Test
    fun `blumonInitElapsedSeconds starts at zero`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        assertThat(viewModel.blumonInitElapsedSeconds.value).isEqualTo(0)
        viewModel.viewModelScope.cancel()
    }

    // ========================================
    // NETWORK ERROR CLASSIFICATION TESTS (Task 8 / F-5)
    // ========================================
    // isNetworkRelatedInitError is a pure Throwable -> Boolean function (no coroutines
    // involved), so it can be called directly on the ViewModel instance without hitting
    // the Dispatchers.IO limitation noted above for the full init flow.

    @Test
    fun `isNetworkRelatedInitError detecta UnknownHostException directo`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        assertThat(viewModel.isNetworkRelatedInitError(java.net.UnknownHostException("api.avoqado.io"))).isTrue()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `isNetworkRelatedInitError detecta UnknownHostException envuelto 2 niveles de profundidad`() = runTest(testDispatcher) {
        // El check viejo (1 nivel: error.cause is UnknownHostException) no alcanzaba esto.
        val viewModel = createViewModel()
        val wrapped = Exception("outer", Exception("middle", java.net.UnknownHostException("api.avoqado.io")))

        assertThat(viewModel.isNetworkRelatedInitError(wrapped)).isTrue()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `isNetworkRelatedInitError detecta ConnectException y SocketTimeoutException`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        assertThat(viewModel.isNetworkRelatedInitError(java.net.ConnectException("Connection refused"))).isTrue()
        assertThat(viewModel.isNetworkRelatedInitError(java.net.SocketTimeoutException("timeout"))).isTrue()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `isNetworkRelatedInitError NO clasifica un error de negocio generico como red`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        assertThat(viewModel.isNetworkRelatedInitError(IllegalStateException("posId invalido"))).isFalse()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `isNetworkRelatedInitError sigue reconociendo el fallback de texto del SDK Blumon`() = runTest(testDispatcher) {
        // InitializerFailure.NetworkConnectionFailure (SDK Blumon, com.example.clean_lib_services)
        // no es Throwable - InitializationManager lo aplana a texto antes de que llegue
        // aqui. Ver KDoc de isNetworkRelatedInitError para el detalle verificado
        // (decompilado del .aar) y por que este fallback de texto se queda.
        val viewModel = createViewModel()
        val flattened = Exception("InitializerUseCase failed: NetworkConnectionFailure")

        assertThat(viewModel.isNetworkRelatedInitError(flattened)).isTrue()
        viewModel.viewModelScope.cancel()
    }

    // ========================================
    // T26 (Testarudo, 2026-09-11)
    // ========================================

    @Test
    fun `app_terminal_serial lleva el serial del aparato, no el serial por defecto`() = runTest(testDispatcher) {
        // Antes la escribía Application.onCreate con TerminalConfig.serialNumber, que ahí
        // todavía vale DEFAULT_SERIAL "2841548417" (y es el serial del comercio Blumon):
        // los reportes de la N86 de Testarudo decían venir de una PAX.
        val crashlytics = mockk<com.google.firebase.crashlytics.FirebaseCrashlytics>(relaxed = true)
        mockkStatic(com.google.firebase.crashlytics.FirebaseCrashlytics::class)
        every { com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance() } returns crashlytics
        every { deviceInfoManager.getSerialNumber() } returns "N860W173397"

        val viewModel = createViewModel()

        verify(atLeast = 1) { crashlytics.setCustomKey("app_terminal_serial", "N860W173397") }
        verify(exactly = 0) { crashlytics.setCustomKey("app_terminal_serial", "2841548417") }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `P1 en PAX volver la red o el servidor jamas resuelve la recuperacion de AngelPay`() = runTest(testDispatcher) {
        org.junit.Assume.assumeTrue("Caso PAX; Nexgo prueba su recuperación AngelPay", com.jaac.avoqado_tpv.BuildConfig.FLAVOR in setOf("sandbox", "production", "gymDemo"))
        // El sabor de pruebas es BLUMON (sandbox). Resolver el Provider cargaría clases de
        // AngelPay en una PAX: el candado de sabor va ANTES del get(), igual que el arranque.
        val viewModel = createViewModel()
        advanceTimeBy(2_000)
        runCurrent()

        fakeConnectionRestoredEvents.emit(
            com.jaac.avoqado_tpv.core.util.ConnectionRestoredEvent(timestamp = "t", attemptsBeforeReconnection = 3),
        )
        fakeConnectionState.value = fakeConnectionState.value.copy(hasServer = false)
        fakeConnectionState.value = fakeConnectionState.value.copy(hasServer = true)
        runCurrent()

        verify(exactly = 0) { angelPayAuthRecoveryProvider.get() }
        verify(exactly = 0) { angelPayAuthRepositoryProvider.get() }
        viewModel.viewModelScope.cancel()
    }

    private suspend fun remoteReceiptT11(
        receipt: Map<String, Any>, expectedStatus: String,
        expectedCargo: String? = null, printerFails: Boolean = false,
    ) {
        val ack = kotlinx.coroutines.CompletableDeferred<String>()
        val printed = java.util.concurrent.CopyOnWriteArrayList<List<Any?>>()
        every {
            printerManager.printReceipt(
                receiptUrl = any(),
                amount = any(),
                authCode = any(),
                tipAmount = any(),
                cardDetails = any(),
                referenceNumber = any(),
                venueName = any(),
                venueLogoUrl = any(),
                venueLegalName = any(),
                venueRfc = any(),
                venueAddress = any(),
                venueCity = any(),
                venueState = any(),
                venueZipCode = any(),
                staffName = any(),
                orderNumber = any(),
                orderItems = any(),
                discountAmount = any(),
                isRefund = any(),
                isPortabilidad = any(),
                serialNumber = any(),
                categoryName = any(),
                autofacturaAvailable = any(),
                serviceChargeAmount = any()
            )
        } answers {
            printed.add(args.toList())
            if (printerFails) kotlin.Result.failure(IllegalStateException("papel agotado")) else kotlin.Result.success(Unit)
        }
        every { socketManager.emitTerminalReceiptPrintResult("receipt-T11", any(), any()) } answers {
            ack.complete(secondArg())
            Unit
        }
        val vm = createViewModel()
        vm.usaPaxParaReciboRemoto = true
        try {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                kotlinx.coroutines.withTimeout(5_000) { fakeSocketEvents.subscriptionCount.first { it > 0 } }
            }
            fakeSocketEvents.emit(com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent.TerminalReceiptPrintRequest(
                requestId = "receipt-T11", receipt = receipt, venueId = "venue-123", timestamp = "2026-10-10T12:00:00Z",
            ))
            // El renderer corre en IO real. Esperar su ACK, no asumir que runCurrent terminó IO.
            val status = kotlinx.coroutines.withContext(Dispatchers.IO) {
                kotlinx.coroutines.withTimeout(5_000) { ack.await() }
            }
            assertThat(status).isEqualTo(expectedStatus)
            if (expectedStatus == "success" || printerFails) {
                assertThat(printed).hasSize(1)
                assertThat(printed.single()[1]).isEqualTo("120.00")
                assertThat(printed.single()[3]).isEqualTo("5.00")
                assertThat(printed.single()[17]).isEqualTo("20.00")
                assertThat(printed.single()[23]).isEqualTo(expectedCargo)
                val items = printed.single()[16] as List<*>
                assertThat(items).hasSize(1)
                assertThat((items.single() as com.jaac.avoqado_tpv.features.ordering.domain.OrderItem).totalPrice.toPlainString()).isEqualTo("120.00")
            } else {
                assertThat(printed).isEmpty()
            }
            verify(exactly = 1) { socketManager.emitTerminalReceiptPrintResult("receipt-T11", expectedStatus, any()) }
            if (expectedStatus == "failed") verify(exactly = 0) { socketManager.emitTerminalReceiptPrintResult("receipt-T11", "success", any()) }
        } finally {
            vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
            clearMocks(socketManager, answers = false, recordedCalls = true)
        }
    }

    private fun receiptT11(): Map<String, Any> = mapOf(
        "total" to 12000, "tipAmount" to 500, "discountAmount" to 2000,
        "venueName" to "Negocio A", "paymentMethod" to "Efectivo",
        "items" to listOf(mapOf("name" to "Producto", "quantity" to 1, "unitPrice" to 12000, "totalPrice" to 12000)),
    )

    @Test
    fun `T11 evento remoto pasa cargo original y demas cifras a impresora`() = runTest(testDispatcher) {
        remoteReceiptT11(receiptT11() + ("serviceChargeAmount" to 1500), "success", "15.00")
    }

    @Test
    fun `T11 evento remoto conserva cargo ausente y cero`() = runTest(testDispatcher) {
        remoteReceiptT11(receiptT11(), "success")
        remoteReceiptT11(receiptT11() + ("serviceChargeAmount" to 0), "success", "0.00")
    }

    @Test
    fun `T11 evento remoto rechaza fraccion overflow negativo y cargo mayor a base`() = runTest(testDispatcher) {
        for (value in listOf<Any>(1500.5, "1500.5", 2147483648L, "2147483648", -1, 11501, "NaN", true)) {
            remoteReceiptT11(receiptT11() + ("serviceChargeAmount" to value), "failed")
        }
    }

    @Test
    fun `T11 evento remoto propaga fallo real de impresora sin success`() = runTest(testDispatcher) {
        remoteReceiptT11(receiptT11() + ("serviceChargeAmount" to 1500), "failed", "15.00", printerFails = true)
    }
    private suspend fun remotePublicNexgoT11(
        receipt: Map<String, Any>, expectedStatus: String,
        callbackCode: Int = 0, statusCode: Int = 0, expectedStarts: Int = 1,
    ): List<String> {
        every {
            printerManager.printReceipt(
                receiptUrl = any(),
                amount = any(),
                authCode = any(),
                tipAmount = any(),
                cardDetails = any(),
                referenceNumber = any(),
                venueName = any(),
                venueLogoUrl = any(),
                venueLegalName = any(),
                venueRfc = any(),
                venueAddress = any(),
                venueCity = any(),
                venueState = any(),
                venueZipCode = any(),
                staffName = any(),
                orderNumber = any(),
                orderItems = any(),
                discountAmount = any(),
                isRefund = any(),
                isPortabilidad = any(),
                serialNumber = any(),
                categoryName = any(),
                autofacturaAvailable = any(),
                serviceChargeAmount = any()
            )
        } returns kotlin.Result.success(Unit)
        coEvery { saleVerificationRepository.getPendingVerifications() } returns kotlin.Result.success(emptyList())
        val printer = mockk<com.nexgo.oaf.apiv3.device.printer.Printer>(relaxed = true)
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        every { printer.status } returns statusCode
        every { printer.initPrinter() } returns 0
        every { printer.appendPrnStr(any<String>(), any<Int>(), any<com.nexgo.oaf.apiv3.device.printer.AlignEnum>(), any<Boolean>()) } answers {
            lines.add(firstArg()); 0
        }
        every { printer.appendPrnStr(any<String>(), any<String>(), any<Int>(), any<Boolean>()) } answers {
            lines.add(firstArg<String>() + "|" + secondArg<String>()); 0
        }
        val started = kotlinx.coroutines.CompletableDeferred<com.nexgo.oaf.apiv3.device.printer.OnPrintListener>()
        every { printer.startPrint(any(), any()) } answers { started.complete(secondArg()); 0 }
        val gateway = com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPaySdkGateway()
        gateway.receiptPrinterFactory = { printer }
        every { angelPaySdkGatewayProvider.get() } returns gateway
        val ack = kotlinx.coroutines.CompletableDeferred<Pair<String, String?>>()
        every { socketManager.emitTerminalReceiptPrintResult("public-Nexgo", any(), any()) } answers {
            ack.complete(secondArg<String>() to thirdArg<String?>()); Unit
        }
        val vm = createViewModel()
        vm.usaPaxParaReciboRemoto = false
        try {
            fakeSocketEvents.subscriptionCount.first { it > 0 }
            fakeSocketEvents.emit(com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent.TerminalReceiptPrintRequest(
                requestId = "public-Nexgo", receipt = receipt, venueId = "venue-123", timestamp = "2026-10-10T12:00:00Z"))
            if (expectedStarts > 0) {
                val observed = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    kotlinx.coroutines.withTimeout(5_000) {
                        kotlinx.coroutines.selects.select<String> {
                            started.onAwait { "start" }
                            ack.onAwait { "premature ack" }
                        }
                    }
                }
                assertThat(observed).isEqualTo("start")
                verify(exactly = 0) { socketManager.emitTerminalReceiptPrintResult("public-Nexgo", any(), any()) }
                started.await().onPrintResult(callbackCode)
            }
            val response = kotlinx.coroutines.withContext(Dispatchers.IO) {
                kotlinx.coroutines.withTimeout(5_000) { ack.await() }
            }
            assertThat(response.first).isEqualTo(expectedStatus)
            verify(exactly = expectedStarts) { printer.startPrint(any(), any()) }
            verify { printerManager wasNot Called }
            verify(exactly = 0) { remotePaymentCoordinator.submitSocketPaymentRequest(any()) }
            verify(exactly = 1) { socketManager.emitTerminalReceiptPrintResult("public-Nexgo", expectedStatus, any()) }
            if (expectedStatus == "failed") {
                assertThat(response.second).isNotEmpty()
                verify(exactly = 0) { socketManager.emitTerminalReceiptPrintResult("public-Nexgo", "success", any()) }
            }
            return lines.toList()
        } finally {
            // Own fixture completion only: no live printer. Cancellation never manufactures success.
            if (started.isCompleted) started.await().onPrintResult(callbackCode)
            vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
            clearMocks(socketManager, answers = false, recordedCalls = true)
            clearMocks(printerManager, answers = false, recordedCalls = true)
        }
    }

    @Test fun `T11 public Nexgo Home waits callback and renders discount charge tip cash`() = runTest(testDispatcher) {
        val lines = remotePublicNexgoT11(receiptT11() + mapOf(
            "serviceChargeAmount" to 1500, "paymentMethod" to "CASH", "cashierName" to "Ana",
            "receiptUrl" to "", "items" to listOf(mapOf("name" to "Producto A", "quantity" to 2,
                "unitPrice" to 6000, "totalPrice" to 12000, "modifiers" to listOf("Azul"), "note" to "Entregar junto"))), "success")
        assertThat(lines).containsAtLeast("Subtotal|$120.00 MXN", "Descuento|-$20.00 MXN", "Cargo|$15.00 MXN", "Propina|$5.00 MXN", "TOTAL|$120.00 MXN")
        assertThat(lines.joinToString("\n")).contains("Forma de pago: EFECTIVO")
        assertThat(lines.joinToString("\n")).doesNotContain("Autorizacion|")
        assertThat(lines.joinToString("\n")).doesNotContain("Referencia|")
        assertThat(lines.joinToString("\n")).contains("2 x Producto A")
        assertThat(lines.joinToString("\n")).contains("Azul")
        assertThat(lines.joinToString("\n")).contains("Ana")
        assertThat(lines).contains(com.jaac.avoqado_tpv.core.printer.LeyendaIva.TEXTO)
    }

    @Test fun `T11 public Nexgo partial never subtracts whole order discount`() = runTest(testDispatcher) {
        val lines = remotePublicNexgoT11(receiptT11() + mapOf("total" to 5500, "serviceChargeAmount" to 1500, "receiptUrl" to ""), "success")
        assertThat(lines).containsAtLeast("Subtotal|$35.00 MXN", "Cargo|$15.00 MXN", "Propina|$5.00 MXN", "TOTAL|$55.00 MXN")
        assertThat(lines.joinToString("\n")).doesNotContain("Descuento")
    }

    @Test fun `T11 public Nexgo charge equal total has zero base`() = runTest(testDispatcher) {
        val lines = remotePublicNexgoT11(receiptT11() + mapOf("total" to 1500, "tipAmount" to 0, "serviceChargeAmount" to 1500, "items" to emptyList<Any>(), "receiptUrl" to ""), "success")
        assertThat(lines).containsAtLeast("Subtotal|$0.00 MXN", "Cargo|$15.00 MXN", "TOTAL|$15.00 MXN")
        assertThat(lines.joinToString("\n")).doesNotContain("Propina")
    }

    @Test fun `T11 public Nexgo absent and zero charge preserve legacy totals`() = runTest(testDispatcher) {
        for (cargo in listOf(emptyMap<String, Any>(), mapOf("serviceChargeAmount" to 0))) {
            val lines = remotePublicNexgoT11(receiptT11() + cargo + ("receiptUrl" to ""), "success")
            assertThat(lines).containsAtLeast("Subtotal|$115.00 MXN", "Propina|$5.00 MXN", "TOTAL|$120.00 MXN")
            assertThat(lines.joinToString("\n")).doesNotContain("Cargo|")
        }
    }

    @Test fun `T11 public Nexgo callback paper error is failed and never another payment`() = runTest(testDispatcher) {
        for (code in listOf(-1005, -1001, -1008, -1009)) {
            remotePublicNexgoT11(receiptT11() + mapOf("serviceChargeAmount" to 1500, "receiptUrl" to ""), "failed", callbackCode = code)
        }
    }

    @Test fun `T11 public Nexgo invalid charge and busy never start printing`() = runTest(testDispatcher) {
        for (cargo in listOf<Any>(1500.5, "1500.5", 2147483648L, -1, 11501)) {
            remotePublicNexgoT11(receiptT11() + ("serviceChargeAmount" to cargo), "failed", expectedStarts = 0)
        }
        remotePublicNexgoT11(receiptT11() + mapOf("serviceChargeAmount" to 1500, "receiptUrl" to ""), "failed", statusCode = -1004, expectedStarts = 0)
    }

    @Test
    fun `cancelBlumonInit remains cancelled after already entered IO initialization completes`() = runTest(testDispatcher) {
        org.junit.Assume.assumeTrue("Caso PAX: inicialización Blumon", com.jaac.avoqado_tpv.BuildConfig.FLAVOR in setOf("sandbox", "production", "gymDemo"))
        assertThat(com.jaac.avoqado_tpv.BuildConfig.ENABLE_PAX_SDK).isTrue()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val releasedNormally = AtomicBoolean(false)
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (message == "[PERF] HomeVM.initBlumonSDK START") {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Initialization latch was not released" }
                    releasedNormally.set(true)
                }
            }
        }
        Timber.plant(tree)
        var viewModel: HomeViewModel? = null
        try {
            val vm = createViewModel()
            viewModel = vm
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue()
            vm.cancelBlumonInit()
            assertThat(vm.isBlumonInitializing.value).isFalse()
            release.countDown()
            // Join the actual scope: the blocked init must run through cancellation/finally.
            vm.viewModelScope.coroutineContext[Job]!!.cancelAndJoin()
            assertThat(releasedNormally.get()).isTrue()
            assertThat(vm.isBlumonInitializing.value).isFalse()
            assertThat(vm.isBlumonReady.value).isFalse()
            assertThat(vm.blumonInitError.value)
                .isEqualTo("Inicialización cancelada. Puedes reintentar cuando la conexión mejore.")
            coVerify(exactly = 0) { initializationManager.ensureInitialized(defaultMerchantPosId = any()) }
        } finally {
            release.countDown()
            try {
                withContext(NonCancellable) {
                    viewModel?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
                }
            } finally {
                Timber.uproot(tree)
            }
        }
    }
}

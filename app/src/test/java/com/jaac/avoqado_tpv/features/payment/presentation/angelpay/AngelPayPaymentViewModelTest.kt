package com.jaac.avoqado_tpv.features.payment.presentation.angelpay

import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import java.math.BigDecimal
import android.content.Context
import android.content.Intent
import androidx.lifecycle.viewModelScope
import com.angelpay.angelpaysdk.models.CallResult
import com.angelpay.angelpaysdk.models.MerchantOption
import com.angelpay.angelpaysdk.models.MerchantSummary
import com.angelpay.angelpaysdk.models.PaymentRequest
import com.angelpay.angelpaysdk.models.PaymentResult
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.core.observability.ObservabilityManager
import com.jaac.avoqado_tpv.core.printer.PrinterManager
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.payment.data.api.PaymentApiService
import com.jaac.avoqado_tpv.features.payment.data.ledger.CercaDeSolicitud
import com.jaac.avoqado_tpv.features.payment.data.local.AuthAttemptTelemetryStore
import com.jaac.avoqado_tpv.features.payment.presentation.CobroRemotoDelPos
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayAuthRepository
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayAuthState
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayIntentBuilder
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayMerchantRepository
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayChargeVerifier
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPaySdkGateway
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.VerificacionDelCobro
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantEnvironment
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import com.jaac.avoqado_tpv.features.payment.domain.repository.MerchantRepository
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordPaymentUseCase
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import com.jaac.avoqado_tpv.core.domain.TerminalConfig
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AngelPayPaymentViewModel] — Task 32 of the AngelPay SDK 1.0.5
 * multi-merchant migration. Covers the §6.7 / §18.1 D2 race protection,
 * the new `Switching` + `Charging` sub-states, and the `selectMerchant`
 * branching on the [AngelPayAuthState] state machine.
 *
 * Uses [UnconfinedTestDispatcher] following the repo's established pattern for
 * ViewModel + StateFlow-heavy tests (memory note 2026-02-06). Each test cancels
 * `viewModelScope` in a `finally` so `runTest` doesn't hang on the init-block
 * collectors.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AngelPayPaymentViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    // ── Mocks ────────────────────────────────────────────────────────
    private lateinit var appContext: Context
    private lateinit var recordPaymentUseCase: RecordPaymentUseCase
    private lateinit var shiftRepository: ShiftRepository
    private lateinit var authRepository: AuthRepository
    private lateinit var merchantRepository: MerchantRepository
    private lateinit var secureStorage: SecureStorage
    private lateinit var terminalConfigRepository: com.jaac.avoqado_tpv.core.domain.repository.TerminalConfigRepository
    private lateinit var intentBuilder: AngelPayIntentBuilder
    private lateinit var sdkGateway: AngelPaySdkGateway
    private lateinit var angelPayAuthRepository: AngelPayAuthRepository
    private lateinit var angelPayMerchantRepository: AngelPayMerchantRepository
    private lateinit var paymentStateHolder: PaymentStateHolder
    private lateinit var tpvSettingsRepository: TpvSettingsRepository
    private lateinit var printerManager: PrinterManager
    private lateinit var angelPayTicketBuilder: com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayTicketBuilder
    private lateinit var paymentApiService: PaymentApiService
    private lateinit var apiService: ApiService
    private lateinit var socketManager: SocketManager
    private lateinit var verificationUploadManager: com.jaac.avoqado_tpv.core.data.firebase.VerificationUploadManager
    private lateinit var observabilityManager: ObservabilityManager
    private lateinit var paymentQueueRepository: com.jaac.avoqado_tpv.features.payment.domain.repository.PaymentQueueRepository
    // 📒 La Libreta (Task 5) — relaxed: every mark is observational and must never affect the flow.
    private lateinit var paymentAttemptLedger: com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger
    // 📊 Task 6 — relaxed: recording is fire-and-forget and observational only.
    private lateinit var authAttemptTelemetryStore: AuthAttemptTelemetryStore
    // 🔍 Verificador del desenlace INCIERTO: consulta el historial de AngelPay para saber
    // si un cobro sin veredicto llego a moverse. Mockeado aqui; su logica vive en
    // AngelPayChargeVerifierTest.
    private lateinit var chargeVerifier: AngelPayChargeVerifier

    // Backing state for repositories whose flows the VM observes
    private val authStateFlow = MutableStateFlow<AngelPayAuthState>(AngelPayAuthState.Authenticated)
    private val activeMerchantIdFlow = MutableStateFlow<Int?>(null)
    private val inFlightSwitchFlow = MutableStateFlow<Int?>(null)
    private val cachedMerchantsFlow = MutableStateFlow<List<MerchantSummary>>(emptyList())
    private val merchantsFlow = MutableStateFlow<List<MerchantAccount>>(emptyList())
    private val socketEventsFlow = MutableSharedFlow<SocketEvent>()

    private val angelPayMerchantA = MerchantAccount(
        id = "merchant_a",
        merchantAccountId = "cma-001",
        serialNumber = "N86-001",
        displayName = "Bar",
        environment = MerchantEnvironment.SANDBOX,
        processorType = ProcessorType.ANGELPAY,
        externalMerchantId = "11",
    )

    private val angelPayMerchantB = MerchantAccount(
        id = "merchant_b",
        merchantAccountId = "cma-002",
        serialNumber = "N86-002",
        displayName = "Restaurant",
        environment = MerchantEnvironment.SANDBOX,
        processorType = ProcessorType.ANGELPAY,
        externalMerchantId = "22",
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        appContext = mockk(relaxed = true)
        recordPaymentUseCase = mockk(relaxed = true)
        shiftRepository = mockk(relaxed = true)
        authRepository = mockk(relaxed = true)
        merchantRepository = mockk(relaxed = true)
        secureStorage = mockk(relaxed = true)
        terminalConfigRepository = mockk(relaxed = true)
        intentBuilder = mockk(relaxed = true)
        sdkGateway = mockk(relaxed = true)
        angelPayAuthRepository = mockk(relaxed = true)
        angelPayMerchantRepository = mockk(relaxed = true)
        paymentStateHolder = mockk(relaxed = true)
        tpvSettingsRepository = mockk(relaxed = true)
        printerManager = mockk(relaxed = true)
        angelPayTicketBuilder = mockk(relaxed = true)
        paymentApiService = mockk(relaxed = true)
        apiService = mockk(relaxed = true)
        socketManager = mockk(relaxed = true)
        verificationUploadManager = mockk(relaxed = true)
        observabilityManager = mockk(relaxed = true)
        paymentQueueRepository = mockk(relaxed = true)
        coEvery { paymentQueueRepository.enqueue(any()) } returns Result.success(Unit)
        paymentAttemptLedger = mockk(relaxed = true) {
            coEvery { cobroSinResolver() } returns null
            coEvery { openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) } returns true
            coEvery { markAuthorizing(any()) } returns true
            // C.5: por defecto la solicitud no está cercada y el efectivo/cripto puede arrancar.
            coEvery { cercaDeSolicitud(any()) } returns CercaDeSolicitud.LIBRE
            coEvery { iniciarEjecucionNoTarjeta(any()) } returns CercaDeSolicitud.LIBRE
        }
        authAttemptTelemetryStore = mockk(relaxed = true)
        chargeVerifier = mockk(relaxed = true)

        // Reactive flows the VM observes
        every { angelPayAuthRepository.state } returns authStateFlow
        every { angelPayMerchantRepository.activeAngelPayMerchantId } returns activeMerchantIdFlow
        every { angelPayMerchantRepository.inFlightSwitch } returns inFlightSwitchFlow
        every { angelPayMerchantRepository.observeCachedMerchants() } returns cachedMerchantsFlow
        every { merchantRepository.getActiveMerchants() } returns merchantsFlow
        every { socketManager.events } returns socketEventsFlow
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings()
        // T26: la alineación se revalida contra la sesión VIVA del SDK. Por default la sesión
        // viva coincide con la marca en memoria; una prueba que quiera divergencia lo dice.
        coEvery { sdkGateway.getUserMerchants() } answers {
            Result.success(
                activeMerchantIdFlow.value
                    ?.let { listOf(MerchantSummary(id = it, name = "x", affiliationNumber = "1", isActive = true)) }
                    ?: emptyList(),
            )
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(
        savedStateHandle: androidx.lifecycle.SavedStateHandle = androidx.lifecycle.SavedStateHandle(),
    ): AngelPayPaymentViewModel = AngelPayPaymentViewModel(
        appContext = appContext,
        recordPaymentUseCase = recordPaymentUseCase,
        shiftRepository = shiftRepository,
        authRepository = authRepository,
        merchantRepository = merchantRepository,
        merchantEligibilityRepository = mockk(relaxed = true),
        secureStorage = secureStorage,
        terminalConfigRepository = terminalConfigRepository,
        intentBuilder = intentBuilder,
        sdkGateway = sdkGateway,
        angelPayAuthRepository = angelPayAuthRepository,
        angelPayMerchantRepository = angelPayMerchantRepository,
        paymentStateHolder = paymentStateHolder,
        tpvSettingsRepository = tpvSettingsRepository,
        printerManager = printerManager,
        angelPayTicketBuilder = angelPayTicketBuilder,
        paymentApiService = paymentApiService,
        apiService = apiService,
        socketManager = socketManager,
        verificationUploadManager = verificationUploadManager,
        observability = observabilityManager,
        paymentQueueRepository = paymentQueueRepository,
        paymentAttemptLedger = paymentAttemptLedger,
        authAttemptTelemetryStore = authAttemptTelemetryStore,
        // T26: botón «Reintentar» del banner (recuperación MANUAL de la auth de AngelPay).
        angelPayAuthRecovery = mockk(relaxed = true),
        chargeVerifier = chargeVerifier,
        // Real handle (a plain in-memory map here) — the socket arbitration fields are backed by
        // it so they survive Activity/VM death while the AngelPay SDK Activity is in front.
        savedStateHandle = savedStateHandle,
    )

    // ----------------------------------------------------------------------
    // 1. selectMerchant — SelectingMerchant branch
    // ----------------------------------------------------------------------
    @Test
    fun `selectMerchant in SelectingMerchant state calls completeMerchantSelection with temporaryToken`() = runTest(testDispatcher) {
        authStateFlow.value = AngelPayAuthState.SelectingMerchant(
            merchants = listOf(MerchantOption(id = 11, name = "Bar", afiliationNumber = "1001")),
            temporaryToken = "TEMP-TOKEN-XYZ",
        )
        coEvery {
            angelPayAuthRepository.completeMerchantSelection(11, "TEMP-TOKEN-XYZ")
        } returns Result.success(Unit)

        val vm = createViewModel()
        try {
            vm.selectMerchant(angelPayMerchantA)
            runCurrent()

            coVerify(exactly = 1) {
                angelPayAuthRepository.completeMerchantSelection(11, "TEMP-TOKEN-XYZ")
            }
            coVerify(exactly = 0) { angelPayMerchantRepository.switchActiveMerchant(any()) }
            assertThat(vm.currentMerchant.value).isEqualTo(angelPayMerchantA)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // 2. selectMerchant — Authenticated branch
    // ----------------------------------------------------------------------
    @Test
    fun `selectMerchant in Authenticated state calls switchActiveMerchant`() = runTest(testDispatcher) {
        authStateFlow.value = AngelPayAuthState.Authenticated
        coEvery { angelPayMerchantRepository.switchActiveMerchant(22) } returns Result.success(Unit)

        val vm = createViewModel()
        try {
            vm.selectMerchant(angelPayMerchantB)
            runCurrent()

            coVerify(exactly = 1) { angelPayMerchantRepository.switchActiveMerchant(22) }
            coVerify(exactly = 0) { angelPayAuthRepository.completeMerchantSelection(any(), any()) }
            assertThat(vm.currentMerchant.value).isEqualTo(angelPayMerchantB)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // 3. selectMerchant — failure reverts _currentMerchant
    // ----------------------------------------------------------------------
    @Test
    fun `selectMerchant in failure case reverts _currentMerchant`() = runTest(testDispatcher) {
        authStateFlow.value = AngelPayAuthState.Authenticated
        coEvery { angelPayMerchantRepository.switchActiveMerchant(22) } returns
            Result.failure(RuntimeException("SDK switch failed"))

        // Seed previous merchant via a successful first call (still Authenticated branch).
        coEvery { angelPayMerchantRepository.switchActiveMerchant(11) } returns Result.success(Unit)

        val vm = createViewModel()
        try {
            vm.selectMerchant(angelPayMerchantA)
            runCurrent()
            assertThat(vm.currentMerchant.value).isEqualTo(angelPayMerchantA)

            vm.selectMerchant(angelPayMerchantB)
            runCurrent()

            // Reverts back to A on failure
            assertThat(vm.currentMerchant.value).isEqualTo(angelPayMerchantA)
            val state = vm.state.value
            assertThat(state).isInstanceOf(AngelPayPaymentState.Error::class.java)
            assertThat((state as AngelPayPaymentState.Error).message)
                .contains("No se pudo cambiar de merchant")
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // 4. startPayment waits for in-flight switch (success path)
    // ----------------------------------------------------------------------
    @Test
    fun `startCardPayment waits for in-flight switch up to 8s when active merchant differs from selected`() = runTest(testDispatcher) {
        // Cashier has selected merchant 11, but SDK is still on a different one.
        activeMerchantIdFlow.value = 99
        authStateFlow.value = AngelPayAuthState.Authenticated
        every { secureStorage.getAngelPayCredentials() } returns mockk(relaxed = true)
        // Ensure we skip the SDK path entirely (force app-to-app) so the test
        // doesn't blow up on SDK init mocks.
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(
            angelPaySdkEnabled = false,
            angelPaySdkFallbackEnabled = true,
        )

        val vm = createViewModel()
        try {
            // Seed selected merchant by routing through the Authenticated branch.
            coEvery { angelPayMerchantRepository.switchActiveMerchant(11) } returns Result.success(Unit)
            vm.selectMerchant(angelPayMerchantA)
            runCurrent()

            // Kick off card payment — should enter Switching while waiting for active id.
            vm.startCardPayment()
            // Let the launch run until it suspends on `first { it == 11 }`.
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Switching::class.java)
            val switching = vm.state.value as AngelPayPaymentState.Switching
            assertThat(switching.targetMerchantId).isEqualTo(11)
            assertThat(switching.previousMerchantId).isEqualTo(99)

            // Repository finishes the switch → activeMerchantIdFlow flips to 11.
            activeMerchantIdFlow.value = 11
            runCurrent()

            // The guard advances; we should have transitioned past Switching.
            assertThat(vm.state.value).isNotInstanceOf(AngelPayPaymentState.Switching::class.java)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // 5. startPayment errors after 8s if switch never completes
    // ----------------------------------------------------------------------
    @Test
    fun `startCardPayment errors after 8s if switch never completes`() = runTest(testDispatcher) {
        activeMerchantIdFlow.value = 99
        authStateFlow.value = AngelPayAuthState.Authenticated
        every { secureStorage.getAngelPayCredentials() } returns mockk(relaxed = true)
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(
            angelPaySdkEnabled = false,
            angelPaySdkFallbackEnabled = true,
        )

        val vm = createViewModel()
        try {
            coEvery { angelPayMerchantRepository.switchActiveMerchant(11) } returns Result.success(Unit)
            vm.selectMerchant(angelPayMerchantA)
            runCurrent()

            vm.startCardPayment()
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Switching::class.java)

            // Advance past the 8s watchdog without the active id ever flipping.
            advanceTimeBy(8_500L)
            runCurrent()

            val state = vm.state.value
            assertThat(state).isInstanceOf(AngelPayPaymentState.Error::class.java)
            assertThat((state as AngelPayPaymentState.Error).message).contains("Cambio de merchant")
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // 6. paymentStateHolder.setCharging set and cleared around payment
    // ----------------------------------------------------------------------
    @Test
    fun `empty callback after SDK launch preserves the charging gate`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("sin respuesta")
        // No mismatch — guard short-circuits to true, VM enters Charging.
        activeMerchantIdFlow.value = 11
        authStateFlow.value = AngelPayAuthState.Authenticated
        every { secureStorage.getAngelPayCredentials() } returns mockk(relaxed = true)
        // Force app-to-app path with a no-op intent builder (relaxed mock returns mockk Intent).
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(
            angelPaySdkEnabled = false,
            angelPaySdkFallbackEnabled = true,
        )

        val vm = createViewModel()
        try {
            coEvery { angelPayMerchantRepository.switchActiveMerchant(11) } returns Result.success(Unit)
            vm.selectMerchant(angelPayMerchantA)
            runCurrent()

            vm.startCardPayment()
            runCurrent()

            // Charging gate engaged before SDK launch.
            coVerify(atLeast = 1) { paymentStateHolder.setCharging(true) }

            // Empty callback cannot prove cancellation after launch.
            vm.onAngelPayResult(resultCode = 0, data = null)
            runCurrent()

            coVerify(exactly = 0) { paymentStateHolder.setCharging(false) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // 6b. POS→TPV arbitration link survival (regression, 2026-07-14)
    //
    // The link (_socketRequestId) is BOTH the emit target AND the
    // terminalPaymentRequestId threaded into the recorded payment. It used to be
    // nulled on emit, which silently broke retry-after-decline: the decline emits
    // "failed" and nulls the id → cashier retries ON THE TERMINAL → card APPROVED
    // → the recorded payment carries a null link → the server can never reconcile
    // the FAILED row to COMPLETED and the 🚨 "money moved despite close" alert
    // never fires. Now de-duped by a separate flag; the id lives until reset.
    // ----------------------------------------------------------------------

    @Test
    fun `socket request id SURVIVES the emit so a retry-after-decline still threads the arbitration link`() =
        runTest(testDispatcher) {
            val vm = createViewModel()
            try {
                vm.setSocketPaymentSource("SOCKET", "REQ-RETRY")
                runCurrent()

                // First terminal outcome (a decline) emits exactly once.
                vm.emitSocketResultForTest(status = "failed", errorMessage = "declinada")
                runCurrent()
                verify(exactly = 1) {
                    socketManager.emitTerminalPaymentResult(
                        requestId = "REQ-RETRY", status = "failed", any(), any(), any(), any(), any(), any(),
                     outcomeEvidence = any())
                }

                // 🔑 The id must STILL be there — it is the link the recorded payment carries.
                assertThat(vm.socketRequestIdForTest()).isEqualTo("REQ-RETRY")

                // A second emit for the SAME request is suppressed (one result per request)…
                vm.emitSocketResultForTest(status = "success", paymentId = "pay-1")
                runCurrent()
                verify(exactly = 0) {
                    socketManager.emitTerminalPaymentResult(
                        requestId = "REQ-RETRY", status = "success", any(), any(), any(), any(), any(), any(),
                     outcomeEvidence = any())
                }
                // …but the link is STILL intact for the retry's payment record.
                assertThat(vm.socketRequestIdForTest()).isEqualTo("REQ-RETRY")
            } finally {
                vm.viewModelScope.cancel()
            }
        }

    @Test
    fun `re-tagging the SAME request id is a no-op and cannot re-open the emit gate`() =
        runTest(testDispatcher) {
            val vm = createViewModel()
            try {
                vm.setSocketPaymentSource("SOCKET", "REQ-SAME")
                vm.emitSocketResultForTest(status = "success", paymentId = "pay-9")
                runCurrent()

                // The screen re-tags on every recomposition / after an Activity+VM recreation.
                // Re-tagging the same request must NOT reset the emitted flag (double emit).
                vm.setSocketPaymentSource("SOCKET", "REQ-SAME")
                vm.emitSocketResultForTest(status = "success", paymentId = "pay-9")
                runCurrent()

                verify(exactly = 1) {
                    socketManager.emitTerminalPaymentResult(
                        requestId = "REQ-SAME", status = "success", any(), any(), any(), any(), any(), any(),
                     outcomeEvidence = any())
                }
            } finally {
                vm.viewModelScope.cancel()
            }
        }

    // ----------------------------------------------------------------------
    // P1 · Un ViewModel = UNA solicitud (2026-09-11)
    //
    // El VM está atado a la entrada de navegación (hiltViewModel): un cobro remoto NUEVO siempre
    // llega en una entrada NUEVA con un VM NUEVO. Pero la pantalla lee paymentSource/
    // socketRequestId del `previousBackStackEntry`, que se calcula contra el TOPE actual: cuando
    // B tapa la pantalla ya resuelta de A, la pantalla de A que va saliendo se recompone, lee los
    // argumentos de B y se los pasaba a SU VM. El VM de A quedaba con importe, propina, orden e
    // intento de A y el id de B; al morir (onCleared) le mandaba al servidor un «cancelled» de B
    // con evidencia PRE_AUTHORIZATION prestada de A — que el servidor acepta para liberar la
    // terminal mientras la pantalla de B sigue pidiendo tarjeta. Si luego se cobra B, llega tarde
    // con 🚨; si el cajero ya cobró en otra terminal, es un cobro doble.
    // ----------------------------------------------------------------------

    private fun verificarNingunDesenlacePara(requestId: String) {
        verify(exactly = 0) {
            socketManager.emitTerminalPaymentResult(
                requestId, any(), any(), any(), any(), any(), any(), any(), outcomeEvidence = any(),
            )
        }
    }

    @Test
    fun `P1 un VM ya atado a la solicitud A no adopta el id de B que le llega al salir`() =
        runTest(testDispatcher) {
            val vm = createViewModel()
            try {
                vm.setSocketPaymentSource("SOCKET", "REQ-A")
                runCurrent()
                // A se resolvió (rechazo): su pantalla ya no trabaja y el colector la saca.
                vm.emitSocketResultForTest(status = "failed", errorMessage = "declinada")
                runCurrent()

                // La pantalla de A, al salir, relee el handle de Home — que ya trae los de B.
                vm.setSocketPaymentSource("SOCKET", "REQ-B")
                runCurrent()

                assertThat(vm.socketRequestIdForTest()).isEqualTo("REQ-A")

                // Y al morir ese VM NO puede hablar en nombre de B.
                vm.emitCancelledIfAbandoned()
                runCurrent()
                verificarNingunDesenlacePara("REQ-B")
            } finally {
                vm.viewModelScope.cancel()
            }
        }

    @Test
    fun `P1 un VM de un cobro LOCAL no se vuelve remoto por los argumentos de B`() =
        runTest(testDispatcher) {
            val vm = createViewModel()
            try {
                // Primer compose de un cobro iniciado en la terminal: la pantalla pasa (null, null).
                vm.setSocketPaymentSource(null, null)
                runCurrent()

                // B tapa esa pantalla y, al salir, le llegan sus argumentos.
                vm.setSocketPaymentSource("SOCKET", "REQ-B")
                runCurrent()

                assertThat(vm.socketRequestIdForTest()).isNull()
                vm.emitCancelledIfAbandoned()
                runCurrent()
                verificarNingunDesenlacePara("REQ-B")
            } finally {
                vm.viewModelScope.cancel()
            }
        }

    @Test
    fun `P1 tras resetPayment el VM no adopta ninguna solicitud nueva`() =
        runTest(testDispatcher) {
            val vm = createViewModel()
            try {
                vm.setSocketPaymentSource("SOCKET", "REQ-A")
                runCurrent()
                vm.resetPayment() // avisa cancelled de A y limpia el enlace
                runCurrent()
                clearMocks(socketManager, answers = false)

                vm.setSocketPaymentSource("SOCKET", "REQ-B")
                runCurrent()

                assertThat(vm.socketRequestIdForTest()).isNull()
                vm.emitCancelledIfAbandoned()
                runCurrent()
                verificarNingunDesenlacePara("REQ-B")
            } finally {
                vm.viewModelScope.cancel()
            }
        }

    @Test
    fun `P1 el candado sobrevive a la muerte del proceso - el VM restaurado no adopta otro id`() =
        runTest(testDispatcher) {
            val handle = androidx.lifecycle.SavedStateHandle()
            val original = createViewModel(handle)
            original.setSocketPaymentSource("SOCKET", "REQ-A")
            runCurrent()
            original.viewModelScope.cancel()

            val restaurado = createViewModel(
                androidx.lifecycle.SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }),
            )
            try {
                // El mismo id (la recomposición tras recrear) sigue siendo un no-op…
                restaurado.setSocketPaymentSource("SOCKET", "REQ-A")
                // …pero otro id no se adopta.
                restaurado.setSocketPaymentSource("SOCKET", "REQ-B")
                runCurrent()

                assertThat(restaurado.socketRequestIdForTest()).isEqualTo("REQ-A")
            } finally {
                restaurado.viewModelScope.cancel()
            }
        }

    @Test
    fun `P1 una solicitud B no re-etiqueta al VM de A con un intento sin cerrar`() =
        runTest(testDispatcher) {
            // A ya tiene su contexto de pago (importe, intento) y el SDK lanzado: un intento
            // SIN cerrar. Si B se le pegara aquí, un registro de este VM llevaría el importe de A
            // con el id de B.
            val vm = vmConCobroDelPos("REQ-A")
            try {
                vm.setSocketPaymentSource("SOCKET", "REQ-B")
                runCurrent()

                assertThat(vm.socketRequestIdForTest()).isEqualTo("REQ-A")
                vm.emitCancelledIfAbandoned()
                runCurrent()
                verificarNingunDesenlacePara("REQ-B")
            } finally {
                vm.viewModelScope.cancel()
            }
        }

    @Test
    fun `P1 el reloj de abandono de A nunca emite con el id de B`() = runTest(testDispatcher) {
        val vm = vmConCobroDelPos("REQ-A")
        vm.msAbandonoAvisoEmv = 100L
        try {
            // Aviso EMV recuperable: se arma el reloj que cierra la fila de A si nadie retoma.
            vm.onAngelPaySdkResult(
                sdkFailureResult(sdkCode = "E608", message = "Limite contactless excedido", category = "EMV"),
            )
            runCurrent()
            // Mientras el reloj corre, llega B (la pantalla de A sale y relee el handle de Home).
            vm.setSocketPaymentSource("SOCKET", "REQ-B")
            runCurrent()

            advanceTimeBy(300)
            runCurrent()

            verificarNingunDesenlacePara("REQ-B")
            // 🔴 El desenlace del abandono sale con el id de A — que es lo que este P1 guarda — y
            // con `failed`, NO con `cancelled` (H.3, 11-sep): el rechazo del procesador (E608) ya
            // dejo evidencia `PROCESSOR_DECLINED`, y el servidor solo la acredita junto a `failed`.
            // Con `cancelled` la degrada a timeout, la fila queda UNKNOWN y la terminal y la tablet
            // se quedan bloqueadas. Se fija la evidencia explicita, no `any()`: es lo que convierte
            // este desenlace en «no se cobro» acreditado.
            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    "REQ-A", "failed", any(), any(), any(), any(), any(), any(), outcomeEvidence = "PROCESSOR_DECLINED",
                )
            }
            // Y nunca con `cancelled`: si alguien revierte H.3, esta linea cae.
            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(
                    "REQ-A", "cancelled", any(), any(), any(), any(), any(), any(), outcomeEvidence = any(),
                )
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `un VM nuevo si adopta su primera solicitud remota`() =
        runTest(testDispatcher) {
            val vm = createViewModel()
            try {
                vm.setSocketPaymentSource("SOCKET", "REQ-1")
                vm.emitSocketResultForTest(status = "success", paymentId = "pay-1")
                runCurrent()

                assertThat(vm.socketRequestIdForTest()).isEqualTo("REQ-1")
                verify(exactly = 1) {
                    socketManager.emitTerminalPaymentResult(
                        requestId = "REQ-1", status = "success", any(), any(), any(), any(), any(), any(),
                     outcomeEvidence = any())
                }
            } finally {
                vm.viewModelScope.cancel()
            }
        }

    // ----------------------------------------------------------------------
    // 7. D308 mid-payment session recovery (AppErrorCatalog, byte-identical in
    //    SDK 1.0.10/1.0.13)
    //
    // The sandbox variant compiles with ANGELPAY_SDK_ENABLED=false, so
    // startCardPayment can never reach the SDK launch path in this suite. The
    // tests prime the launched-request state through the @VisibleForTesting
    // launchSdkRequest seam and then drive onAngelPaySdkResult directly.
    // ----------------------------------------------------------------------

    /** Primes the VM as if an SDK payment had just been launched. */
    private fun AngelPayPaymentViewModel.primeSdkLaunch() {
        launchSdkRequest(mockk<PaymentRequest>(relaxed = true), usedQaTipFallback = false)
    }

    /** Builds a declined SDK PaymentResult carrying the given AppErrorCatalog code. */
    private fun sdkFailureResult(
        sdkCode: String,
        message: String = "Pago rechazado",
        category: String = "UNKNOWN",
    ): PaymentResult {
        val call = mockk<CallResult>(relaxed = true)
        every { call.code } returns sdkCode
        every { call.message } returns "msg-$sdkCode"
        every { call.category } returns category
        val result = mockk<PaymentResult>(relaxed = true)
        every { result.approved } returns false
        every { result.callResult } returns call
        every { result.message } returns message
        return result
    }

    @Test
    fun `D308 result triggers handleAuthExpiry and relaunches the same payment once`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            coEvery { angelPayAuthRepository.handleAuthExpiry() } answers { Result.success(Unit) }
            vm.primeSdkLaunch()
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.LaunchingAngelPaySdk::class.java)

            vm.onAngelPaySdkResult(sdkFailureResult("D308"))
            runCurrent()

            coVerify(exactly = 1) { angelPayAuthRepository.handleAuthExpiry() }
            // Relaunched — back in LaunchingAngelPaySdk, not Error.
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.LaunchingAngelPaySdk::class.java)
            // Recovery is NOT a terminal outcome: the D2 charging gate must stay set.
            coVerify(exactly = 0) { paymentStateHolder.setCharging(false) }
            // Recovered silently — nothing terminal happened, so nothing to report.
            verify(exactly = 0) { observabilityManager.logWarning(any(), any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `second D308 on the same attempt surfaces Error instead of looping re-auth`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            coEvery { angelPayAuthRepository.handleAuthExpiry() } answers { Result.success(Unit) }
            vm.primeSdkLaunch()
            runCurrent()

            vm.onAngelPaySdkResult(sdkFailureResult("D308"))
            runCurrent()
            vm.onAngelPaySdkResult(sdkFailureResult("D308"))
            runCurrent()

            // Only ONE re-auth per payment attempt — the second D308 falls through to Error.
            coVerify(exactly = 1) { angelPayAuthRepository.handleAuthExpiry() }
            val state = vm.state.value
            assertThat(state).isInstanceOf(AngelPayPaymentState.Error::class.java)
            assertThat((state as AngelPayPaymentState.Error).message).contains("D308")
            coVerify(atLeast = 1) { paymentStateHolder.setCharging(false) }
            // Re-auth failed — this IS a terminal decline shown to the cashier, so report it.
            verify(exactly = 1) { observabilityManager.logWarning(any(), any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `non-session error codes do not trigger re-auth`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            vm.primeSdkLaunch()
            runCurrent()

            vm.onAngelPaySdkResult(sdkFailureResult("G500"))
            runCurrent()

            coVerify(exactly = 0) { angelPayAuthRepository.handleAuthExpiry() }
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
            coVerify(atLeast = 1) { paymentStateHolder.setCharging(false) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `E608 contactless-limit decline is reported to observability with the SDK code and category`() = runTest(testDispatcher) {
        // Regression coverage for the Amaena incident (2026-07-06): AngelPay's EMV kernel
        // rejects a plastic contactless tap over the regulatory limit (Visa/Mastercard
        // $1,000 MXN, Amex $1,500 MXN) BEFORE any gateway call, so this decline is invisible
        // to both AngelPay's and our own backend logs unless the TPV reports it itself.
        val vm = createViewModel()
        try {
            vm.primeSdkLaunch()
            runCurrent()

            vm.onAngelPaySdkResult(
                sdkFailureResult(sdkCode = "E608", message = "Limite contactless excedido", category = "EMV"),
            )
            runCurrent()

            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
            verify(exactly = 1) {
                observabilityManager.logWarning(
                    tag = "AngelPayDecline",
                    message = any(),
                    metadata = match { meta ->
                        meta["source"] == "sdk_contract" &&
                            meta["sdkCode"] == "E608" &&
                            meta["category"] == "EMV"
                    },
                )
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `pre-charge register failure (N400) triggers re-auth and relaunches once`() = runTest(testDispatcher) {
        // SDK 1.0.10+/1.0.13: PaymentActivity registers the terminal BEFORE charging and,
        // on failure (expired session being the common cause), aborts with a HARDCODED
        // N400 + this exact message. Message-based detection — see
        // AngelPayErrorMapper.isPreChargeRegisterFailure. Safe to relaunch: the SDK
        // failed before the gateway call, no money moved.
        val vm = createViewModel()
        try {
            coEvery { angelPayAuthRepository.handleAuthExpiry() } answers { Result.success(Unit) }
            vm.primeSdkLaunch()
            runCurrent()

            vm.onAngelPaySdkResult(
                sdkFailureResult(
                    sdkCode = "N400",
                    message = "No fue posible registrar la terminal antes del cobro",
                ),
            )
            runCurrent()

            coVerify(exactly = 1) { angelPayAuthRepository.handleAuthExpiry() }
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.LaunchingAngelPaySdk::class.java)
            coVerify(exactly = 0) { paymentStateHolder.setCharging(false) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `mid-charge N400 network error does NOT trigger re-auth`() = runTest(testDispatcher) {
        // Same N400 code but a normal network message: the charge may have reached the
        // gateway, so re-auth + relaunch is forbidden (double-charge risk).
        val vm = createViewModel()
        try {
            vm.primeSdkLaunch()
            runCurrent()

            vm.onAngelPaySdkResult(sdkFailureResult(sdkCode = "N400", message = "Sin conexión a internet"))
            runCurrent()

            coVerify(exactly = 0) { angelPayAuthRepository.handleAuthExpiry() }
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
            coVerify(atLeast = 1) { paymentStateHolder.setCharging(false) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `D308 with failing re-auth surfaces Error without relaunching`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            coEvery { angelPayAuthRepository.handleAuthExpiry() } answers {
                Result.failure(Exception("re-auth failed"))
            }
            vm.primeSdkLaunch()
            runCurrent()

            vm.onAngelPaySdkResult(sdkFailureResult("D308"))
            runCurrent()

            coVerify(exactly = 1) { angelPayAuthRepository.handleAuthExpiry() }
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
            coVerify(atLeast = 1) { paymentStateHolder.setCharging(false) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // Incidente Amaena (2026-07-29): alineación sesión↔merchant seleccionado.
    // La re-auth post-D308 aterrizaba en la cuenta PRIMARIA del venue y el
    // relanzamiento cobraba ESA afiliación mientras el registro llevaba la
    // seleccionada — dinero en A, libros en B.
    // ----------------------------------------------------------------------

    @Test
    fun `D308 recovery does NOT relaunch when the re-authenticated session lands on a different merchant`() = runTest(testDispatcher) {
        coEvery { angelPayMerchantRepository.switchActiveMerchant(22) } returns Result.success(Unit)
        val vm = createViewModel()
        try {
            vm.selectMerchant(angelPayMerchantB) // externalMerchantId 22
            runCurrent()
            activeMerchantIdFlow.value = 22 // sesión alineada al momento de lanzar

            coEvery { angelPayAuthRepository.handleAuthExpiry() } answers {
                // La re-auth quedó en la sesión de OTRA cuenta: su merchant auto-
                // seleccionado es el 11, no el 22 que el cajero eligió (la firma
                // exacta del incidente Amaena).
                activeMerchantIdFlow.value = 11
                Result.success(Unit)
            }
            vm.primeSdkLaunch()
            runCurrent()

            vm.onAngelPaySdkResult(sdkFailureResult("D308"))
            runCurrent()

            coVerify(exactly = 1) { angelPayAuthRepository.handleAuthExpiry() }
            // NUNCA se relanza el cobro sobre una sesión en el merchant equivocado.
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
            coVerify(atLeast = 1) { paymentStateHolder.setCharging(false) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `D308 recovery relaunches when the re-authenticated session matches the selected merchant`() = runTest(testDispatcher) {
        coEvery { angelPayMerchantRepository.switchActiveMerchant(22) } returns Result.success(Unit)
        val vm = createViewModel()
        try {
            vm.selectMerchant(angelPayMerchantB)
            runCurrent()
            coEvery { angelPayAuthRepository.handleAuthExpiry() } answers {
                activeMerchantIdFlow.value = 22 // la re-auth volvió al merchant seleccionado
                Result.success(Unit)
            }
            vm.primeSdkLaunch()
            runCurrent()

            vm.onAngelPaySdkResult(sdkFailureResult("D308"))
            runCurrent()

            coVerify(exactly = 1) { angelPayAuthRepository.handleAuthExpiry() }
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.LaunchingAngelPaySdk::class.java)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `selectMerchant with no tracked session account switches to the target account instead of skipping`() = runTest(testDispatcher) {
        val merchantOwnedByB = angelPayMerchantB.copy(angelpayUserAccountId = "acc-B")
        every { angelPayAuthRepository.getCurrentAngelPayAccountId() } returns null
        coEvery { angelPayAuthRepository.deriveSessionAccountId() } returns null
        coEvery { angelPayAuthRepository.switchAccount("acc-B") } returns Result.success(Unit)
        coEvery { angelPayMerchantRepository.switchActiveMerchant(22) } returns Result.success(Unit)

        val vm = createViewModel()
        try {
            vm.selectMerchant(merchantOwnedByB)
            runCurrent()

            // Antes del fix este switch se SALTABA en silencio (currentAccountId == null):
            // la sesión podía pertenecer a la primaria mientras la UI mostraba este merchant.
            coVerify(exactly = 1) { angelPayAuthRepository.switchAccount("acc-B") }
            assertThat(vm.currentMerchant.value).isEqualTo(merchantOwnedByB)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // skipReview (serialized SIM sales) — bypasses rating/tip, keeps verification
    // ----------------------------------------------------------------------
    @Test
    fun `initPayment with skipReview true bypasses rating and tip`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(
            enableShifts = false,
            showReviewScreen = true,
            showTipScreen = true,
            showVerificationScreen = false,
        )

        val vm = createViewModel()
        try {
            vm.initPayment(amount = "100.00", orderId = "order_1", skipReview = true)
            runCurrent()

            // showReviewScreen/showTipScreen are both ON, yet skipReview must jump
            // straight past them to merchant selection.
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.SelectingMerchant::class.java)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `initPayment with skipReview false keeps rating when enabled`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(
            enableShifts = false,
            showReviewScreen = true,
            showTipScreen = true,
            showVerificationScreen = false,
        )

        val vm = createViewModel()
        try {
            vm.initPayment(amount = "100.00", orderId = "order_1", skipReview = false)
            runCurrent()

            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.CollectingRating::class.java)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `remote payment keeps external tip and rating while bypassing their screens`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(
            enableShifts = false,
            showReviewScreen = true,
            showTipScreen = true,
            showVerificationScreen = false,
        )
        coEvery { recordPaymentUseCase(any(), any(), any(), any()) } returns Result.success(
            PaymentReceipt(
                paymentId = "pay-remote",
                receiptUrl = "https://receipt/pay-remote",
                accessKey = "key-remote",
                amount = java.math.BigDecimal("475.00"),
                tipAmount = java.math.BigDecimal("47.50"),
            ),
        )

        val vm = createViewModel()
        try {
            vm.initPayment(
                amount = "475.00",
                skipReview = true,
                externalTipCents = 4_750L,
                externalRating = 5,
            )
            runCurrent()

            val selectingMerchant = vm.state.value as AngelPayPaymentState.SelectingMerchant
            assertThat(selectingMerchant.subtotal).isEqualTo("475.00")
            assertThat(selectingMerchant.tipAmount).isEqualTo("47.50")
            assertThat(selectingMerchant.totalAmount).isEqualTo("522.50")
            assertThat(selectingMerchant.rating).isEqualTo(5)

            vm.onAngelPaySdkResult(approvedSdkResult())
            runCurrent()

            coVerify(timeout = 2_000, exactly = 1) {
                recordPaymentUseCase(
                    match { context ->
                        context.amount.compareTo(java.math.BigDecimal("475.00")) == 0 &&
                            context.tip.compareTo(java.math.BigDecimal("47.50")) == 0 &&
                            context.rating == 5
                    },
                    any(),
                    any(),
                    any(),
                )
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `remote payment rejects a negative external tip before charging`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-negative-tip")

            vm.initPayment(
                amount = "475.00",
                skipReview = true,
                externalTipCents = -1L,
                externalRating = 5,
            )
            runCurrent()

            val error = vm.state.value as AngelPayPaymentState.Error
            assertThat(error.message).isEqualTo("Propina invalida")
            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-negative-tip",
                    status = "failed",
                    paymentId = any(),
                    transactionId = any(),
                    cardDetails = any(),
                    errorMessage = "Propina invalida",
                    receiptUrl = any(),
                    receiptAccessKey = any(),
                 outcomeEvidence = any())
            }
            coVerify(exactly = 0) { recordPaymentUseCase(any(), any(), any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // Offline queue on backend-record failure (P1 fix 2026-07-09)
    // ----------------------------------------------------------------------

    private fun angelPayContext() = com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext.AngelPayPayment(
        venueId = "v1",
        staffId = "s1",
        shiftId = "shift-1",
        amount = java.math.BigDecimal("150.00"),
        tip = java.math.BigDecimal("15.00"),
        idempotencyKey = "idem-uuid-1",
        authorizationCode = "AUTH77",
        referenceNumber = "195978383755",
        orderId = "order-9",
        orderNumber = "SN00042",
        serialNumbers = listOf("8952000000000000001"),
    )

    @Test
    fun `handleRecordFailure enqueues the payment and reports queued state`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            val state = vm.handleRecordFailure(
                paymentLabel = "El pago con tarjeta",
                context = angelPayContext(),
                error = RuntimeException("HTTP 503"),
            )

            // F-1: money moved AND it's safely queued — a SUCCESS with a caveat, never Error.
            assertThat(state).isInstanceOf(AngelPayPaymentState.Queued::class.java)
            val queued = state as AngelPayPaymentState.Queued
            // Operator sees the self-healing message, still forbidding a re-charge.
            assertThat(queued.message).contains("EN COLA")
            assertThat(queued.message).contains("NO vuelvas a cobrar")

            coVerify(exactly = 1) {
                paymentQueueRepository.enqueue(
                    match {
                        it.processor == ProcessorType.ANGELPAY &&
                            it.referenceNumber == "195978383755" &&
                            it.idempotencyKey == "idem-uuid-1" &&
                            it.orderId == "order-9" &&
                            it.shiftId == "shift-1" &&
                            it.serialNumbers == listOf("8952000000000000001")
                    },
                )
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `un cobro exitoso con registro encolado NO es un estado de Error`() = runTest(testDispatcher) {
        coEvery { paymentQueueRepository.enqueue(any()) } returns Result.success(Unit)

        val vm = createViewModel()
        try {
            val state = vm.handleRecordFailure(
                paymentLabel = "El pago",
                context = angelPayContext(),
                error = java.io.IOException("backend no respondio"),
            )

            // 🔴 El bug: era Error. El cajero veia rojo y volvia a cobrar.
            assertThat(state).isInstanceOf(AngelPayPaymentState.Queued::class.java)
            val queued = state as AngelPayPaymentState.Queued
            assertThat(queued.message).contains("EN COLA")
            assertThat(queued.message).contains("NO vuelvas a cobrar")
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `handleRecordFailure falls back to manual-review state when enqueue also fails`() = runTest(testDispatcher) {
        coEvery { paymentQueueRepository.enqueue(any()) } returns Result.failure(RuntimeException("disk full"))

        val vm = createViewModel()
        try {
            val state = vm.handleRecordFailure(
                paymentLabel = "El pago con tarjeta",
                context = angelPayContext(),
                error = RuntimeException("HTTP 503"),
            )

            // Legacy manual-review message — nothing got queued, supervisor must act.
            assertThat(state).isInstanceOf(AngelPayPaymentState.Error::class.java)
            val errorState = state as AngelPayPaymentState.Error
            assertThat(errorState.message).contains("avisa al supervisor")
            assertThat(errorState.canRetry).isFalse()
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `si el encolado TAMBIEN falla si es un Error real`() = runTest(testDispatcher) {
        coEvery { paymentQueueRepository.enqueue(any()) } returns
            Result.failure(IllegalStateException("no entro a la cola"))

        val vm = createViewModel()
        try {
            val state = vm.handleRecordFailure(
                paymentLabel = "El pago",
                context = angelPayContext(),
                error = java.io.IOException("backend no respondio"),
            )

            assertThat(state).isInstanceOf(AngelPayPaymentState.Error::class.java)
            assertThat((state as AngelPayPaymentState.Error).message).contains("avisa al supervisor")
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `handleRecordFailure uses idempotencyKey as reference fallback when SDK reference is blank`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            vm.handleRecordFailure(
                paymentLabel = "El pago con tarjeta",
                context = angelPayContext().copy(referenceNumber = ""),
                error = RuntimeException("HTTP 503"),
            )

            // reference_number is UNIQUE in Room — a blank reference must not collide.
            coVerify(exactly = 1) {
                paymentQueueRepository.enqueue(match { it.referenceNumber == "idem-uuid-1" })
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // 📒 La Libreta write-ahead (Task 5) — observational marks on the AngelPay
    // path. AngelPay has NO separate host-response moment: the SDK's single
    // return IS the verdict, so markHostResponded fires at result arrival
    // (approved=false → DESCARTADA inside the ledger). All marks are relaxed
    // mocks: a ledger failure must never alter the payment flow.
    // ----------------------------------------------------------------------

    @Test
    fun `handleRecordFailure with enqueue OK marks the ledger row delivered to queue`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            vm.handleRecordFailure(
                paymentLabel = "El pago con tarjeta",
                context = angelPayContext(), // idempotencyKey == attemptId == "idem-uuid-1"
                error = RuntimeException("HTTP 503"),
            )

            coVerify(exactly = 1) { paymentAttemptLedger.markDeliveredToQueue("idem-uuid-1") }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `handleRecordFailure does NOT mark delivered to queue when the enqueue also fails`() = runTest(testDispatcher) {
        coEvery { paymentQueueRepository.enqueue(any()) } returns Result.failure(RuntimeException("disk full"))

        val vm = createViewModel()
        try {
            vm.handleRecordFailure(
                paymentLabel = "El pago con tarjeta",
                context = angelPayContext(),
                error = RuntimeException("HTTP 503"),
            )

            // Nothing got queued — the row must stay in REGISTRO_FALLIDO (sweep evidence).
            coVerify(exactly = 0) { paymentAttemptLedger.markDeliveredToQueue(any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `declined SDK result marks the host verdict approved=false at result arrival`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            vm.primeSdkLaunch() // generates + registers the attemptId pre-launch
            runCurrent()

            val declined = mockk<PaymentResult>(relaxed = true)
            every { declined.approved } returns false
            // 🔴 `status` DECLINED es lo que hace de esto un rechazo y no un "no se sabe".
            // Se declara desde que existen los tres desenlaces (2026-09-08): un
            // `PaymentResult` sin código de catálogo, sin código del emisor Y sin status
            // no describe ningún rechazo real — `status` no es nullable en el SDK, así que
            // ese resultado sólo puede salir de un mock a medio armar. Un resultado así,
            // en la vida real, es una salida SIN veredicto y se clasifica como INCIERTO.
            every { declined.status } returns PaymentResult.Status.DECLINED
            every { declined.code } returns "05"
            every { declined.message } returns "Transaccion declinada"
            every { declined.callResult } returns null
            every { declined.authCode } returns null
            every { declined.reference } returns null
            every { declined.cardBin } returns null

            vm.onAngelPaySdkResult(declined)
            runCurrent()

            // The ledger converts approved=false into DESCARTADA — one mark, no record marks.
            coVerify(exactly = 1) {
                paymentAttemptLedger.markHostResponded(any(), false, null, null, null)
            }
            coVerify(exactly = 0) { paymentAttemptLedger.markAuthorized(any(), any(), any(), any()) }
            coVerify(exactly = 0) { paymentAttemptLedger.markRecorded(any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `empty app-to-app callback after authorization remains unknown`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("sin respuesta")
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)

        val vm = createViewModel()
        try {
            vm.initPayment(amount = "100.00") // caches venue/staff + attemptId
            runCurrent()

            vm.openLedgerAttemptAndMarkAuthorizing("attempt-empty")
            // Empty callback cannot prove financial cancellation.
            vm.onAngelPayResult(resultCode = 0, data = null)
            runCurrent()

            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.ResultadoIncierto::class.java)
            coVerify(exactly = 0) {
                paymentAttemptLedger.markHostResponded(any(), false, null, null, null)
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `approved SDK result marks host verdict then AUTORIZADO and REGISTRADO on record success`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        coEvery { recordPaymentUseCase(any(), any(), any(), any()) } returns Result.success(
            PaymentReceipt(
                paymentId = "pay-led-1",
                receiptUrl = "https://receipt/pay-led-1",
                accessKey = "key-led-1",
                amount = java.math.BigDecimal("100.00"),
                tipAmount = java.math.BigDecimal.ZERO,
            ),
        )

        val vm = createViewModel()
        try {
            vm.initPayment(amount = "100.00")
            runCurrent()

            vm.onAngelPaySdkResult(approvedSdkResult(authCode = "A1", reference = "R1"))
            runCurrent()

            // recordCardPayment hops through withContext(Dispatchers.IO) — wait for the tail mark.
            coVerify(timeout = 2000, exactly = 1) { paymentAttemptLedger.markRecorded(any()) }
            coVerify(exactly = 1) {
                paymentAttemptLedger.markHostResponded(any(), true, null, "R1", "A1")
            }
            // AngelPay returns no maskedPan; brand/entryMode captured from the built CardDetails.
            coVerify(exactly = 1) { paymentAttemptLedger.markAuthorized(any(), null, "UNKNOWN", "OTHER") }
            coVerify(exactly = 0) { paymentAttemptLedger.markRecordFailed(any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `approved SDK result with record failure marks REGISTRO_FALLIDO then delivered to queue`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        coEvery { recordPaymentUseCase(any(), any(), any(), any()) } returns Result.failure(RuntimeException("HTTP 503"))

        val vm = createViewModel()
        try {
            vm.initPayment(amount = "100.00")
            runCurrent()

            vm.onAngelPaySdkResult(approvedSdkResult())
            runCurrent()

            // markRecordFailed runs BEFORE handleRecordFailure so the queue mark's CAS
            // (REGISTRO_FALLIDO → ENTREGADA_A_COLA) can match.
            coVerify(timeout = 2000, exactly = 1) { paymentAttemptLedger.markDeliveredToQueue(any()) }
            coVerify(exactly = 1) { paymentAttemptLedger.markRecordFailed(any(), "HTTP 503") }
            coVerify(exactly = 0) { paymentAttemptLedger.markRecorded(any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `D308 recovery relaunch defers the ledger verdict - no DESCARTADA for a live retry`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            coEvery { angelPayAuthRepository.handleAuthExpiry() } answers { Result.success(Unit) }
            vm.primeSdkLaunch()
            runCurrent()

            // First D308: recovery relaunches the SAME attemptId — an early DESCARTADA would
            // blind the ledger to the retried charge's outcome, so NO verdict is written.
            vm.onAngelPaySdkResult(sdkFailureResult("D308"))
            runCurrent()
            coVerify(exactly = 0) { paymentAttemptLedger.markHostResponded(any(), any(), any(), any(), any()) }

            // Second D308 on the same attempt: recovery is spent → terminal decline → the
            // DEFERRED mark now fires exactly once with approved=false.
            vm.onAngelPaySdkResult(sdkFailureResult("D308"))
            runCurrent()
            coVerify(exactly = 1) { paymentAttemptLedger.markHostResponded(any(), false, null, any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `app to app G505 preserves unknown instead of recording a decline`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("sin respuesta")
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        io.mockk.mockkConstructor(com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayResultParser::class)
        every { anyConstructed<com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayResultParser>().parse(any(), any()) } returns
            com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayResult.Failure("Resultado no concluyente", "G505", "GATEWAY")
        val vm = createViewModel()
        try {
            vm.initPayment("100.00")
            vm.onAngelPayResult(0, null)
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.ResultadoIncierto::class.java)
            coVerify(exactly = 0) { paymentAttemptLedger.markHostResponded(any(), false, any(), any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
            io.mockk.unmockkConstructor(com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayResultParser::class)
        }
    }

    @Test
    fun `review duplicate barrier cannot authorize second SDK launch when durable CAS loses`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        coEvery { paymentAttemptLedger.markAuthorizing("attempt-X") } returnsMany listOf(true, false)
        val vm = createViewModel()
        try {
            vm.initPayment("100.00")
            runCurrent()
            assertThat(vm.openLedgerAttemptAndMarkAuthorizing("attempt-X")).isTrue()
            assertThat(vm.openLedgerAttemptAndMarkAuthorizing("attempt-X")).isFalse()
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun `pre-launch persists complete replayable identity before SDK`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        val json = slot<String>()
        coEvery { paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), capture(json), any()) } returns true
        val vm = createViewModel()
        try {
            vm.initPayment(amount = "100.00")
            runCurrent()
            vm.openLedgerAttemptAndMarkAuthorizing("attempt-X")
            val saved = com.google.gson.Gson().fromJson(json.captured, PaymentContext.AngelPayPayment::class.java)
            assertThat(saved.venueId).isEqualTo("v1")
            assertThat(saved.staffId).isEqualTo("s1")
            assertThat(saved.idempotencyKey).isEqualTo("attempt-X")
            assertThat(saved.amount).isEqualTo(BigDecimal("100.00"))
        } finally { vm.viewModelScope.cancel() }
    }

    /**
     * 🔴 El serial que se GUARDA en la libreta es el que usará `LedgerUnknownRecovery` para
     * preguntarle a AngelPay «¿este cobro pasó?» tras un reinicio — lo lee del propio
     * `payment_context_json` (`LedgerUnknownRecovery:34`). Si ahí queda
     * `TerminalConfig.serialNumber`, que NO es de este aparato sino de un COMERCIO Blumon
     * (su defecto es "2841548417", una PAX), la consulta vuelve vacía SIEMPRE y ningún cobro
     * incierto se puede acreditar nunca.
     *
     * Es la otra mitad del arreglo del 11-sep: ahí se corrigió la consulta EN VIVO
     * (`chargeVerifier.verificar`) y se dejó intacto lo que la libreta persiste, así que la
     * recuperación automática seguía muerta. Lo destapó la auditoría de Codex del 12-sep.
     */
    @Test
    fun `la libreta guarda el serial del APARATO, no el del comercio Blumon — sin eso la recuperacion tras reinicio nunca acredita`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        every { secureStorage.getSerialNumber() } returns "AVQD-N860W173397"
        val json = slot<String>()
        coEvery { paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), capture(json), any()) } returns true
        val vm = createViewModel()
        try {
            vm.initPayment(amount = "100.00")
            runCurrent()
            vm.openLedgerAttemptAndMarkAuthorizing("attempt-S")
            val saved = com.google.gson.Gson().fromJson(json.captured, PaymentContext.AngelPayPayment::class.java)
            // Sin el prefijo `AVQD-`: es el ÚNICO valor con el que AngelPay contesta (lo fija
            // `serialParaAngelPay`, y su condición de aceptación exige serial no vacío).
            assertThat(saved.deviceSerialNumber).isEqualTo("N860W173397")
            assertThat(saved.deviceSerialNumber).isNotEqualTo(TerminalConfig.serialNumber)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun `pre-launch guard suppresses re-open only for the same attempt id AND same amounts`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        coEvery {
            paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any())
        } returns true

        val vm = createViewModel()
        try {
            vm.initPayment(amount = "100.00")
            runCurrent()

            // Legit reuse shape (retry-after-decline / SDK→app-to-app fallback): SAME id,
            // SAME amounts → the second call must NOT re-open (no false REUSE alarm)…
            vm.openLedgerAttemptAndMarkAuthorizing("attempt-X")
            vm.openLedgerAttemptAndMarkAuthorizing("attempt-X")

            coVerify(exactly = 1) {
                paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any())
            }
            // …but AUTORIZANDO is (re)marked on every launch — the CAS resolves duplicates.
            coVerify(exactly = 2) { paymentAttemptLedger.markAuthorizing("attempt-X") }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `pre-launch guard does NOT suppress re-open when the amount changed - collision alarm stays reachable`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        coEvery {
            paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any())
        } returns true

        val vm = createViewModel()
        try {
            vm.initPayment(amount = "100.00")
            runCurrent()
            vm.openLedgerAttemptAndMarkAuthorizing("attempt-X")

            // State-contamination shape (spec §6): a surviving VM without resetPayment —
            // initPayment overwrites pendingAmount while ensurePaymentAttemptId keeps the
            // stale id. DIFFERENT money on the SAME id must fall through to openAttempt so
            // the ledger's PK-collision "REUSE" alarm fires.
            vm.initPayment(amount = "250.00")
            runCurrent()
            vm.openLedgerAttemptAndMarkAuthorizing("attempt-X")

            coVerify(exactly = 2) {
                paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `a collided or failed open does not arm the re-open suppression`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        // openAttempt = false ⇒ PK collision (the REUSE alarm path) — suppression must NOT arm.
        coEvery {
            paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any())
        } returns false

        val vm = createViewModel()
        try {
            vm.initPayment(amount = "100.00")
            runCurrent()

            vm.openLedgerAttemptAndMarkAuthorizing("attempt-X")
            vm.openLedgerAttemptAndMarkAuthorizing("attempt-X")

            // Same id + same amounts, but the first open never succeeded → both calls open.
            coVerify(exactly = 2) {
                paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // POS→TPV terminal arbitration (Slice 4 Part B): terminal:payment_result
    // emission for SOCKET-initiated charges. Money rule (founder 2026-07):
    // money moved ⇒ "success"; money did NOT move ⇒ "failed". Guarded by
    // _paymentSource == "SOCKET" so device-initiated charges emit nothing.
    // ----------------------------------------------------------------------

    private fun approvedSdkResult(authCode: String = "A1", reference: String = "R1"): PaymentResult {
        val result = mockk<PaymentResult>(relaxed = true)
        every { result.approved } returns true
        every { result.authCode } returns authCode
        every { result.reference } returns reference
        every { result.cardBin } returns null
        every { result.callResult } returns null
        return result
    }

    @Test
    fun `socket-sourced recorded card payment emits success with paymentId`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        coEvery { recordPaymentUseCase(any(), any(), any(), any()) } returns Result.success(
            PaymentReceipt(
                paymentId = "pay-1",
                receiptUrl = "https://receipt/pay-1",
                accessKey = "key-1",
                amount = java.math.BigDecimal("100.00"),
                tipAmount = java.math.BigDecimal.ZERO,
            ),
        )

        val vm = createViewModel()
        try {
            vm.initPayment(amount = "100.00") // caches venue/staff + attemptId
            runCurrent()
            vm.setSocketPaymentSource("SOCKET", "req-success")

            vm.onAngelPaySdkResult(approvedSdkResult())
            runCurrent()

            // recordCardPayment records via withContext(Dispatchers.IO); verify(timeout) waits for it.
            verify(timeout = 2000) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-success",
                    status = "success",
                    paymentId = "pay-1",
                    transactionId = any(),
                    cardDetails = any(),
                    errorMessage = any(),
                    receiptUrl = any(),
                    receiptAccessKey = any(),
                 outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `socket-sourced enqueued (money-moved) payment emits success WITHOUT paymentId`() = runTest(testDispatcher) {
        // The gotcha: card WAS charged but backend record failed → enqueued offline. Money moved ⇒
        // report success now; paymentId arrives later when the queue syncs.
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-enqueue")

            vm.handleRecordFailure(
                paymentLabel = "El pago con tarjeta",
                context = angelPayContext(),
                error = RuntimeException("HTTP 503"),
            )

            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-enqueue",
                    status = "success",
                    paymentId = null,
                    transactionId = any(),
                    cardDetails = any(),
                    errorMessage = any(),
                    receiptUrl = any(),
                    receiptAccessKey = any(),
                 outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    /**
     * 🛑 H.3 (11-sep): un rechazo del banco NO es un desenlace final mientras la terminal ofrezca
     * «Reintentar» sobre la MISMA solicitud. Emitirlo ahí hacía que el servidor la diera por NOT_CHARGED
     * y la tablet soltara su llave… con el botón de reintentar todavía en pantalla: si ese reintento
     * aprobaba, el cliente pagaba dos veces (P1-2 de la auditoría; 6 filas COMPLETED con failureCode
     * TPV_ERROR en producción son exactamente ese patrón). Sale al salir, y sale UNO.
     */
    @Test
    fun `socket-sourced SDK decline no emite nada mientras se pueda reintentar y al salir emite UN failed`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-decline")
            vm.primeSdkLaunch()
            runCurrent()

            vm.onAngelPaySdkResult(sdkFailureResult("G500")) // non-session decline → terminal
            runCurrent()

            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(any(), any(), any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
            }

            vm.resetPayment() // el cajero sale del cobro
            runCurrent()

            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-decline",
                    status = "failed",
                    paymentId = any(),
                    transactionId = any(),
                    cardDetails = any(),
                    errorMessage = any(),
                    receiptUrl = any(),
                    receiptAccessKey = any(),
                    outcomeEvidence = "PROCESSOR_DECLINED",
                )
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    /** El reloj de abandono cierra la fila del POS con UN solo desenlace, y quita el «Reintentar». */
    @Test
    fun `un rechazo sin retomar se cierra solo con UN failed mas PROCESSOR_DECLINED`() = runTest(testDispatcher) {
        val vm = vmConCobroDelPos("req-decline-abandonado")
        vm.msAbandonoAvisoEmv = 100L
        try {
            vm.onAngelPaySdkResult(sdkFailureResult("G500"))
            runCurrent()
            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(any(), any(), any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
            }

            advanceTimeBy(300)
            runCurrent()

            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-decline-abandonado", status = "failed", paymentId = any(),
                    transactionId = any(), cardDetails = any(), errorMessage = any(), receiptUrl = any(),
                    receiptAccessKey = any(), outcomeEvidence = "PROCESSOR_DECLINED",
                )
            }
            val estado = vm.state.value as AngelPayPaymentState.Error
            assertThat(estado.canRetry).isFalse()
            assertThat(estado.message).isEqualTo(CobroRemotoDelPos.CERRADO_POR_ABANDONO)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    /** Y si el cajero SÍ retoma y el chip aprueba, el único desenlace es `success`. */
    @Test
    fun `tras un rechazo, un reintento aprobado emite success y ningun desenlace negativo`() = runTest(testDispatcher) {
        coEvery { recordPaymentUseCase(any(), any(), any(), any()) } returns Result.success(
            PaymentReceipt(
                paymentId = "pay-retry", receiptUrl = "https://receipt/pay-retry", accessKey = "key-retry",
                amount = java.math.BigDecimal("100.00"), tipAmount = java.math.BigDecimal.ZERO,
            ),
        )
        val vm = vmConCobroDelPos("req-decline-retry")
        try {
            vm.onAngelPaySdkResult(sdkFailureResult("G500"))
            runCurrent()
            vm.retryAfterError()
            runCurrent()
            vm.primeSdkLaunch()
            runCurrent()
            vm.onAngelPaySdkResult(approvedSdkResult())
            runCurrent()

            verify(timeout = 2000, exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-decline-retry", status = "success", paymentId = "pay-retry",
                    transactionId = any(), cardDetails = any(), errorMessage = any(), receiptUrl = any(),
                    receiptAccessKey = any(), outcomeEvidence = any(),
                )
            }
            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(any(), "failed", any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `charge-active flag flips false on a resolved decline screen (terminal not stuck-busy)`() = runTest(testDispatcher) {
        // Device-QA regression: after a decline the AngelPay screen stays on the payment route,
        // but the terminal is FREE. AppNavigation reads paymentStateHolder.isChargeAttemptActive()
        // to tell a live charge from a stale result screen — a decline must publish `false` so the
        // next POS-initiated charge isn't rejected with "Ya hay un pago en proceso" until an app
        // restart. (The bug: the guard checked only the route, conflating "on-screen" with "busy".)
        val vm = createViewModel()
        try {
            runCurrent()
            // A working state → active.
            vm.primeSdkLaunch() // LaunchingAngelPaySdk
            runCurrent()
            verify { paymentStateHolder.setChargeAttemptActive(true) }

            // Fix round 2: `paymentStateHolder` is relaxed with no call-clearing, and
            // `init{}`'s `state.collect{}` guard fires synchronously on construction
            // (UnconfinedTestDispatcher) observing the still-Idle state — that ALONE already
            // calls setChargeAttemptActive(false) before this test drives anything. A bare
            // `verify { setChargeAttemptActive(false) }` below would match THAT stale call and
            // pass regardless of what the decline actually does — proven by deliberately
            // removing `Error` from the ViewModel's guard and re-running: see "Fix round 2" in
            // task-6-report.md. Clearing history right after the `true` checkpoint forces the
            // next verify to be about THIS transition's call, not any earlier one.
            clearMocks(paymentStateHolder, answers = false)

            // Decline → Error is a RESOLVED state → NOT active (terminal free again). Declines
            // resolve synchronously (no backend record/enqueue attempt — money never moved), so
            // `runCurrent()` alone is enough; `exactly = 1` is what makes the assertion
            // discriminate (not the `timeout`, which is defensive/harmless here).
            vm.onAngelPaySdkResult(sdkFailureResult("G500"))
            runCurrent()
            verify(timeout = 2000, exactly = 1) { paymentStateHolder.setChargeAttemptActive(false) }
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `charge-active flag flips false on a resolved Queued screen (terminal not stuck-busy)`() = runTest(testDispatcher) {
        // F-1 fix round 1 (sibling of the Error case above): Queued is a RESOLVED outcome
        // (money moved, safely queued) exactly like Success/Error/Cancelled. Before the F-1
        // fix this path published Error, which WAS excluded from "active" — so the flag
        // already flipped false correctly. Adding Queued as a NEW resolved state reopened
        // the "screen stays up, terminal rejects every subsequent POS charge" bug from
        // 2026-07-14 unless Queued is excluded here too.
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        coEvery { recordPaymentUseCase(any(), any(), any(), any()) } returns Result.failure(RuntimeException("HTTP 503"))

        val vm = createViewModel()
        try {
            vm.initPayment(amount = "100.00")
            runCurrent()
            // A working state → active.
            vm.primeSdkLaunch() // LaunchingAngelPaySdk
            runCurrent()
            verify { paymentStateHolder.setChargeAttemptActive(true) }

            // Fix round 2: same stale-match hole as the decline sibling above — construction
            // already logged a `false` call from the initial Idle state, so a bare
            // `verify(timeout = 2000) { setChargeAttemptActive(false) }` matched THAT call and
            // returned instantly (0ms), never actually waiting on the Dispatchers.IO hop below.
            // Confirmed two ways: (1) run this test in isolation with the old code — it failed
            // on the NEXT line (state was still RecordingPayment); (2) remove `Queued` from the
            // guard and re-run — the class still went green. Clearing history right after the
            // `true` checkpoint fixes the discrimination.
            clearMocks(paymentStateHolder, answers = false)

            // Approved + record failure → Queued is a RESOLVED state → NOT active. The
            // record→enqueue attempt genuinely hops through Dispatchers.IO (same as the sibling
            // "approved SDK result with record failure..." test above, which waits on
            // paymentAttemptLedger the same way) — `exactly = 1` is what makes `timeout` do real
            // polling work now: right after the clear the count is 0 (only a same-transaction
            // `true` from the intermediate RecordingPayment state is recorded), and MockK keeps
            // retrying until the async Queued transition lands the `false` call or 2s elapse.
            vm.onAngelPaySdkResult(approvedSdkResult())
            runCurrent()
            verify(timeout = 2000, exactly = 1) { paymentStateHolder.setChargeAttemptActive(false) }
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Queued::class.java)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `socket-sourced E608 advisory does NOT emit failed and the in-session chip retry emits success`() = runTest(testDispatcher) {
        // Device-QA 2026-07-14 double-charge regression: a $1,501 tap hit the contactless
        // limit (E608). Emitting "failed" showed the POS "Reintentar" and dropped
        // _socketRequestId — while the cashier completed THE SAME charge by chip+PIN
        // (APPROVED, money moved) → orphaned Payment + human-mediated double charge.
        // Vendor catalog (AppErrorCatalog, AAR v1.0.15) marks E608 Retry.IMMEDIATE_AFTER_FIX:
        // hold the socket result so the in-session retry resolves the still-open long-poll.
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        coEvery { recordPaymentUseCase(any(), any(), any(), any()) } returns Result.success(
            PaymentReceipt(
                paymentId = "pay-e608",
                receiptUrl = "https://receipt/pay-e608",
                accessKey = "key-e608",
                amount = java.math.BigDecimal("1501.00"),
                tipAmount = java.math.BigDecimal.ZERO,
            ),
        )

        val vm = createViewModel()
        try {
            vm.initPayment(amount = "1501.00")
            runCurrent()
            vm.setSocketPaymentSource("SOCKET", "req-e608")
            vm.primeSdkLaunch()
            runCurrent()

            // 1) Tap over the contactless limit → E608 advisory. Cashier sees the error
            //    (canRetry), but the POS long-poll must stay OPEN: no emit at all.
            vm.onAngelPaySdkResult(
                sdkFailureResult(sdkCode = "E608", message = "Limite contactless excedido", category = "EMV"),
            )
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(any(), any(), any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
            }

            // 2) In-session retry: relaunch (resets the consume guard, same as the real
            //    Reintentar → Tarjeta path) + chip APPROVED → the ORIGINAL request id
            //    resolves as success (proves _socketRequestId survived the advisory).
            vm.primeSdkLaunch()
            runCurrent()
            vm.onAngelPaySdkResult(approvedSdkResult())
            runCurrent()

            verify(timeout = 2000) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-e608",
                    status = "success",
                    paymentId = "pay-e608",
                    transactionId = any(),
                    cardDetails = any(),
                    errorMessage = any(),
                    receiptUrl = any(),
                    receiptAccessKey = any(),
                 outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    /**
     * H.3: un rechazo EMV que el catálogo marca «nunca reintentar» (E606) tampoco emite en el acto — la
     * pantalla sigue ofreciendo «Reintentar» con otra tarjeta, que es el mismo hueco. Al salir sale UNO.
     */
    @Test
    fun `socket-sourced NEVER-retry EMV decline (E606 online rejection) sale al salir, no al rechazar`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-e606")
            vm.primeSdkLaunch()
            runCurrent()

            vm.onAngelPaySdkResult(sdkFailureResult(sdkCode = "E606", message = "Rechazo online", category = "EMV"))
            runCurrent()
            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(any(), any(), any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
            }

            vm.resetPayment()
            runCurrent()

            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-e606",
                    status = "failed",
                    paymentId = any(),
                    transactionId = any(),
                    cardDetails = any(),
                    errorMessage = any(),
                    receiptUrl = any(),
                    receiptAccessKey = any(),
                 outcomeEvidence = "PROCESSOR_DECLINED")
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `socket-sourced terminal cancellation emits cancelled`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-cancel")

            vm.onAngelPayResult(resultCode = 0, data = null) // RESULT_CANCELED
            runCurrent()

            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Cancelled::class.java)
            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-cancel",
                    status = "cancelled",
                    paymentId = any(),
                    transactionId = any(),
                    cardDetails = any(),
                    errorMessage = any(),
                    receiptUrl = any(),
                    receiptAccessKey = any(),
                 outcomeEvidence = "PRE_AUTHORIZATION")
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `socket-sourced pre-charge error (invalid amount, no money moved) emits failed`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-premoney")

            vm.initPayment(amount = "0.00") // invalid amount → pre-charge failure
            runCurrent()

            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-premoney",
                    status = "failed",
                    paymentId = any(),
                    transactionId = any(),
                    cardDetails = any(),
                    errorMessage = any(),
                    receiptUrl = any(),
                    receiptAccessKey = any(),
                    outcomeEvidence = "PRE_AUTHORIZATION",
                )
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `device-initiated charge (no socket source) emits nothing`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            // No setSocketPaymentSource → _paymentSource stays null → guard suppresses all emits.
            vm.handleRecordFailure(
                paymentLabel = "El pago con tarjeta",
                context = angelPayContext(),
                error = RuntimeException("HTTP 503"),
            )
            vm.onAngelPayResult(resultCode = 0, data = null)
            runCurrent()

            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(any(), any(), any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // Money-safety: a charge whose money moved must ALWAYS be recorded or
    // enqueued — never dropped. venue/staff null AFTER a successful charge is
    // recovered (cached → auth → secureStorage); only a fully-lost session
    // falls back to the loud manual-review state (still a single success emit).
    // ----------------------------------------------------------------------

    @Test
    fun `cash charge with null cached venue recovers from auth, enqueues on record failure, emits success once`() = runTest(testDispatcher) {
        // No initPayment → cachedVenueId/staffId stay null (the post-charge null scenario).
        every { authRepository.getVenueId() } returns "v-recovered"
        every { authRepository.getStaffId() } returns "s-recovered"
        val ctxSlot = slot<com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext>()
        coEvery { recordPaymentUseCase(capture(ctxSlot), any(), any(), any()) } returns
            Result.failure(RuntimeException("HTTP 503"))

        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-cash")
            vm.startCashPayment()
            runCurrent()

            // Charge NOT dropped: recovered venue/staff → context built → record fails → enqueued.
            coVerify(timeout = 2000) {
                paymentQueueRepository.enqueue(
                    match { it.referenceNumber.startsWith("CASH-") && it.idempotencyKey != null },
                )
            }
            val ctx = ctxSlot.captured as com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext.AngelPayPayment
            assertThat(ctx.venueId).isEqualTo("v-recovered")   // recovered from authRepository
            assertThat(ctx.staffId).isEqualTo("s-recovered")
            assertThat(ctx.terminalPaymentRequestId).isEqualTo("req-cash") // threaded from _socketRequestId
            // Exactly ONE socket emit (money moved ⇒ success), from handleRecordFailure.
            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-cash", status = "success", paymentId = any(),
                    transactionId = any(), cardDetails = any(), errorMessage = any(),
                    receiptUrl = any(), receiptAccessKey = any(),
                 outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `card SDK charge with null cached venue recovers from secureStorage, enqueues on record failure, emits success once`() = runTest(testDispatcher) {
        // cachedVenueId/staffId null; authRepository returns null → fall through to secureStorage.
        every { authRepository.getVenueId() } returns null
        every { authRepository.getStaffId() } returns null
        every { secureStorage.getVenueId() } returns "v-secure"
        every { secureStorage.getStaffId() } returns "s-secure"
        val ctxSlot = slot<com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext>()
        coEvery { recordPaymentUseCase(capture(ctxSlot), any(), any(), any()) } returns
            Result.failure(RuntimeException("HTTP 503"))

        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-card")
            vm.onAngelPaySdkResult(approvedSdkResult(authCode = "A9", reference = "REF9"))

            coVerify(timeout = 2000) {
                paymentQueueRepository.enqueue(
                    match { it.referenceNumber == "REF9" && it.idempotencyKey != null && it.processor == ProcessorType.ANGELPAY },
                )
            }
            val ctx = ctxSlot.captured as com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext.AngelPayPayment
            assertThat(ctx.venueId).isEqualTo("v-secure")  // recovered from secureStorage (auth was null)
            assertThat(ctx.terminalPaymentRequestId).isEqualTo("req-card")
            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-card", status = "success", paymentId = any(),
                    transactionId = any(), cardDetails = any(), errorMessage = any(),
                    receiptUrl = any(), receiptAccessKey = any(),
                 outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `charge with unrecoverable venue does NOT drop money - loud manual-review, no enqueue, one success emit`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns null
        every { authRepository.getStaffId() } returns null
        every { secureStorage.getVenueId() } returns null
        every { secureStorage.getStaffId() } returns null

        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-orphan")
            vm.startCashPayment()
            runCurrent()

            // No venue/staff to build a context → never reaches recordPaymentUseCase or the queue.
            coVerify(exactly = 0) { recordPaymentUseCase(any(), any(), any(), any()) }
            coVerify(exactly = 0) { paymentQueueRepository.enqueue(any()) }
            // Loud manual-review state so a human reconciles the money that moved.
            val state = vm.state.value
            assertThat(state).isInstanceOf(AngelPayPaymentState.Error::class.java)
            assertThat((state as AngelPayPaymentState.Error).message).contains("avisa al supervisor")
            // Money moved ⇒ exactly one "success" to the POS.
            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-orphan", status = "success", paymentId = any(),
                    transactionId = any(), cardDetails = any(), errorMessage = any(),
                    receiptUrl = any(), receiptAccessKey = any(),
                 outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // Double-charge mitigation: TRANSIENT/retryable pre-money errors do NOT
    // emit and do NOT clear _socketRequestId, so a terminal-retry-into-success
    // still reports "success" on the original request (no stale "failed").
    // ----------------------------------------------------------------------

    @Test
    fun `transient merchant-select failure on a socket charge does NOT emit`() = runTest(testDispatcher) {
        authStateFlow.value = AngelPayAuthState.Authenticated
        coEvery { angelPayMerchantRepository.switchActiveMerchant(22) } returns
            Result.failure(RuntimeException("SDK switch failed"))

        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-transient")
            vm.selectMerchant(angelPayMerchantB) // transient pre-money error (canRetry=true)
            runCurrent()

            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
            // No socket result: the request stays open so a terminal retry can still report success.
            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(any(), any(), any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `transient error then a successful charge emits exactly one success for the original request`() = runTest(testDispatcher) {
        authStateFlow.value = AngelPayAuthState.Authenticated
        coEvery { angelPayMerchantRepository.switchActiveMerchant(22) } returns
            Result.failure(RuntimeException("SDK switch failed"))

        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-retry")

            // 1) Transient failure → no emit, _socketRequestId left intact.
            vm.selectMerchant(angelPayMerchantB)
            runCurrent()
            verify(exactly = 0) { socketManager.emitTerminalPaymentResult(any(), any(), any(), any(), any(), any(), any(), any(), outcomeEvidence = any()) }

            // 2) Cashier retries → charge succeeds (money-moved/enqueued path emits success).
            vm.handleRecordFailure(
                paymentLabel = "El pago con tarjeta",
                context = angelPayContext(),
                error = RuntimeException("HTTP 503"),
            )

            // Exactly ONE "success" for the ORIGINAL requestId → proves _socketRequestId survived.
            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-retry", status = "success", paymentId = any(),
                    transactionId = any(), cardDetails = any(), errorMessage = any(),
                    receiptUrl = any(), receiptAccessKey = any(),
                 outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `real SDK decline emits failed al salir (regression)`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-decline-reg")
            vm.primeSdkLaunch()
            runCurrent()

            vm.onAngelPaySdkResult(sdkFailureResult("G500")) // real decline — money did NOT move
            runCurrent()
            vm.resetPayment()
            runCurrent()

            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-decline-reg", status = "failed", paymentId = any(),
                    transactionId = any(), cardDetails = any(), errorMessage = any(),
                    receiptUrl = any(), receiptAccessKey = any(),
                 outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // Predicado de dinero-en-vuelo — la lista blanca que impide el doble cobro
    // ----------------------------------------------------------------------

    @Test
    fun `estados pre-dinero permiten avisar cancelacion`() {
        val preDinero = listOf(
            AngelPayPaymentState.Idle,
            AngelPayPaymentState.Cancelled,
            AngelPayPaymentState.CollectingRating(amount = "100.00"),
            AngelPayPaymentState.CollectingTip(amount = "100.00", rating = 5),
            AngelPayPaymentState.SelectingMerchant(
                subtotal = "100.00", tipAmount = "0", totalAmount = "100.00", rating = null,
            ),
            AngelPayPaymentState.Switching(targetMerchantId = 22, previousMerchantId = 11),
            AngelPayPaymentState.GeneratingCryptoQR(
                subtotal = "100.00", tipAmount = "0", totalAmount = "100.00", rating = null,
            ),
            AngelPayPaymentState.Error(message = "Pago rechazado"),
        )

        preDinero.forEach { state ->
            assertThat(sinDineroEnVuelo(state)).isTrue()
        }
    }

    @Test
    fun `estados con dinero en vuelo o ya movido JAMAS permiten avisar cancelacion`() {
        // 🔴 Este es el test que impide el doble cobro. Si alguno de estos pasa a `true`, el POS
        // recibe "cancelado" sobre un cobro que puede haber capturado dinero, el operador recobra,
        // y el cliente paga dos veces.
        val dineroEnJuego = listOf(
            AngelPayPaymentState.LaunchingAngelPaySdk(
                request = mockk<PaymentRequest>(), amount = "100.00", tip = "0",
            ),
            AngelPayPaymentState.LaunchingAngelPay(
                intent = mockk<Intent>(), amount = "100.00", tip = "0",
            ),
            AngelPayPaymentState.WaitingForResult(),
            AngelPayPaymentState.Charging(merchantId = 11, startedAt = 0L),
            AngelPayPaymentState.RecordingPayment(),
            AngelPayPaymentState.ProcessingCash(),
            AngelPayPaymentState.AwaitingCryptoPayment(
                requestId = "req", paymentId = "pay", paymentUrl = "https://x",
                subtotal = "100.00", tipAmount = "0", totalAmount = "100.00", rating = null,
                expiresAt = "2026-08-10T18:00:00Z", expiresInSeconds = 600,
            ),
            AngelPayPaymentState.Success(authCode = "123456", amount = "100.00"),
            AngelPayPaymentState.Queued(
                message = "En cola", authCode = "123456", amount = "100.00",
            ),
        )

        dineroEnJuego.forEach { state ->
            assertThat(sinDineroEnVuelo(state)).isFalse()
        }
    }

    // ----------------------------------------------------------------------
    // Disparadores de cancelación — resetPayment() y la red de onCleared()
    // ----------------------------------------------------------------------

    @Test
    fun `resetPayment con fuente SOCKET en estado pre-dinero emite cancelled`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-reset")

            vm.resetPayment()

            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-reset",
                    status = "cancelled",
                    paymentId = any(),
                    transactionId = any(),
                    cardDetails = any(),
                    errorMessage = any(),
                    receiptUrl = any(),
                    receiptAccessKey = any(),
                 outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `resetPayment tras un desenlace ya emitido no emite un segundo resultado`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-ya-emitido")
            vm.emitSocketResultForTest(status = "failed", errorMessage = "Pago rechazado")
            clearMocks(socketManager, answers = false)

            vm.resetPayment()

            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(
                    requestId = any(), status = any(), paymentId = any(), transactionId = any(),
                    cardDetails = any(), errorMessage = any(), receiptUrl = any(), receiptAccessKey = any(),
                 outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `resetPayment sin fuente socket (cobro iniciado en la terminal) no emite nada`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            // Sin setSocketPaymentSource → _paymentSource queda null.
            vm.resetPayment()

            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(
                    requestId = any(), status = any(), paymentId = any(), transactionId = any(),
                    cardDetails = any(), errorMessage = any(), receiptUrl = any(), receiptAccessKey = any(),
                 outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `la red de onCleared avisa cancelacion cuando la pantalla muere en estado pre-dinero`() = runTest(testDispatcher) {
        // El caso reportado 2026-08-10: el operador da atrás con el botón del sistema, el
        // NavController hace pop y la pantalla muere sin pasar por resetPayment().
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-abandonado")

            vm.emitCancelledIfAbandoned()

            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-abandonado",
                    status = "cancelled",
                    paymentId = any(),
                    transactionId = any(),
                    cardDetails = any(),
                    errorMessage = any(),
                    receiptUrl = any(),
                    receiptAccessKey = any(),
                 outcomeEvidence = "PRE_AUTHORIZATION")
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `la red de onCleared NUNCA avisa cancelacion con una autorizacion en vuelo`() = runTest(testDispatcher) {
        // 🔴 El test que impide el doble cobro. Android puede destruir MainActivity mientras la
        // Activity del SDK de AngelPay tiene el foreground; si emitiéramos aquí, el POS vería
        // "cancelado" sobre un cobro que puede haber capturado dinero y el operador recobraría.
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-en-vuelo")
            vm.onIntentLaunched() // → AngelPayPaymentState.WaitingForResult
            runCurrent()

            vm.emitCancelledIfAbandoned()

            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(
                    requestId = any(), status = any(), paymentId = any(), transactionId = any(),
                    cardDetails = any(), errorMessage = any(), receiptUrl = any(), receiptAccessKey = any(),
                 outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `la red de onCleared no avisa cancelacion si ya se reporto el desenlace`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-exitoso")
            vm.emitSocketResultForTest(status = "success", paymentId = "pay-1")
            clearMocks(socketManager, answers = false)

            vm.emitCancelledIfAbandoned()

            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(
                    requestId = any(), status = any(), paymentId = any(), transactionId = any(),
                    cardDetails = any(), errorMessage = any(), receiptUrl = any(), receiptAccessKey = any(),
                 outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `el barrido de nulls al desmontar la pantalla no borra el enlace de arbitracion`() = runTest(testDispatcher) {
        // 🔴 Verificado en hardware 2026-08-10 (N86, requestId a9e7503e): al hacer pop, el
        // `previousBackStackEntry` del que la pantalla lee paymentSource/socketRequestId cambia,
        // el LaunchedEffect vuelve a correr con (null, null) y ESTO borraba el enlace justo antes
        // de que onCleared lo necesitara → la red no avisaba y la fila del server quedaba UNKNOWN,
        // dejando la terminal rechazando cobros con "ocupada".
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-teardown")

            vm.setSocketPaymentSource(null, null) // el barrido del desmontaje

            assertThat(vm.socketRequestIdForTest()).isEqualTo("req-teardown")
            vm.emitCancelledIfAbandoned()
            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-teardown", status = "cancelled", paymentId = any(),
                    transactionId = any(), cardDetails = any(), errorMessage = any(),
                    receiptUrl = any(), receiptAccessKey = any(),
                 outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // Gates pre-cobro que eran mudos
    // ----------------------------------------------------------------------

    @Test
    fun `el gate de venue nulo avisa failed al POS con el motivo`() = runTest(testDispatcher) {
        // Los vecinos del mismo bloque (monto inválido, sin turno, merchant inválido) ya emitían;
        // estos dos eran los únicos mudos del pre-cobro.
        every { authRepository.getVenueId() } returns null
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-sin-venue")

            vm.initPayment(amount = "100.00")
            runCurrent()

            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-sin-venue",
                    status = "failed",
                    paymentId = any(),
                    transactionId = any(),
                    cardDetails = any(),
                    errorMessage = "No hay venue activo",
                    receiptUrl = any(),
                    receiptAccessKey = any(),
                 outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // Back con skipReview — no retroceder a pantallas que la ida se saltó
    // ----------------------------------------------------------------------

    @Test
    fun `con skipReview el back sale de una vez en lugar de entrar a propina y calificacion`() = runTest(testDispatcher) {
        // Caso real 2026-08-10 (N86, requestId 9c76fa0a): el POS mandó skipReview=true, la ida
        // aterrizó DIRECTO en "Método de Pago"... y el back metía al operador a Propina y luego a
        // Calificación, dos pantallas que nunca se le mostraron. 34 s con el POS colgado.
        // El venue SÍ tiene propina y calificación encendidas: skipReview debe ganarles.
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(
            enableShifts = false, showTipScreen = true, showReviewScreen = true,
        )
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-skipreview")
            vm.initPayment(amount = "100.00", skipReview = true)
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.SelectingMerchant::class.java)

            val consumed = vm.goBackOneStep()
            runCurrent()

            // false = "no me quedé en el wizard, sácame de aquí" → la pantalla navega a Home.
            assertThat(consumed).isFalse()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Idle::class.java)
            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-skipreview", status = "cancelled", paymentId = any(),
                    transactionId = any(), cardDetails = any(), errorMessage = any(),
                    receiptUrl = any(), receiptAccessKey = any(),
                 outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `sin skipReview el back sigue recorriendo el wizard paso a paso`() = runTest(testDispatcher) {
        // Guardia de regresión: el cobro normal iniciado en la terminal NO cambia — el cajero
        // sigue pudiendo retroceder a corregir la propina.
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(
            enableShifts = false, showTipScreen = true, showReviewScreen = true,
        )
        val vm = createViewModel()
        try {
            // Sin skipReview el wizard ARRANCA en Calificación; avanzamos un paso para tener
            // de dónde retroceder.
            vm.initPayment(amount = "100.00", skipReview = false)
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.CollectingRating::class.java)

            vm.selectRatingAndProceed(amount = "100.00", rating = 5)
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.CollectingTip::class.java)

            val consumed = vm.goBackOneStep()
            runCurrent()

            assertThat(consumed).isTrue()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.CollectingRating::class.java)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `el gate de staff nulo avisa failed al POS con el motivo`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "venue-1"
        every { authRepository.getStaffId() } returns null
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-sin-staff")

            vm.initPayment(amount = "100.00")
            runCurrent()

            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-sin-staff",
                    status = "failed",
                    paymentId = any(),
                    transactionId = any(),
                    cardDetails = any(),
                    errorMessage = "No hay staff activo",
                    receiptUrl = any(),
                    receiptAccessKey = any(),
                 outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ── Candado de «cobro en efectivo en vuelo» (2026-09-07) ───────────────────────────────
    // Simétrico al del riel Blumon. Aquí el registro es lo primero que espera; sin candado, un
    // segundo toque mientras el POST viaja registra su propio cobro.

    @Test
    fun `dos toques en Efectivo con el registro en vuelo registran UN solo cobro`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v-1"
        every { authRepository.getStaffId() } returns "s-1"
        val compuerta = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { recordPaymentUseCase(any(), any(), any(), any()) } coAnswers {
            compuerta.await()
            Result.success(
                PaymentReceipt(
                    paymentId = "pay-1",
                    receiptUrl = "https://r/pay-1",
                    accessKey = "k",
                    amount = java.math.BigDecimal.ZERO,
                    tipAmount = java.math.BigDecimal.ZERO,
                )
            )
        }
        val vm = createViewModel()
        try {
            vm.startCashPayment()
            vm.startCashPayment()
            runCurrent()
            compuerta.complete(Unit)
            runCurrent()

            coVerify(exactly = 1) { recordPaymentUseCase(any(), any(), any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // Desenlace INCIERTO (2026-09-08) — el SDK vuelve SIN veredicto del procesador
    // ----------------------------------------------------------------------
    //
    // 🔴 El defecto: `U101` («Tiempo de espera agotado») y `PaymentResult.Status.TIMEOUT`
    // caian en la MISMA rama que un rechazo del emisor. Consecuencia triple, y las tres
    // le dicen al cajero "no se cobro" sobre un cobro que quiza si ocurrio: pantalla de
    // error con Reintentar, fila DESCARTADA en la libreta, y `failed` al POS (que ahi
    // muestra su propio Reintentar). Con la tarjeta ya en la mano del cliente, la accion
    // que las tres invitan es volver a cobrar.

    /** Resultado del SDK sin veredicto del procesador (por defecto: U101 / tiempo agotado). */
    private fun sdkInciertoResult(
        sdkCode: String? = "U101",
        status: PaymentResult.Status = PaymentResult.Status.CANCELLED,
        message: String = "Tiempo de espera agotado",
    ): PaymentResult {
        val call = sdkCode?.let {
            mockk<CallResult>(relaxed = true).also { c ->
                every { c.code } returns it
                every { c.message } returns "msg-$it"
                every { c.category } returns "USER"
            }
        }
        val result = mockk<PaymentResult>(relaxed = true)
        every { result.approved } returns false
        every { result.status } returns status
        every { result.callResult } returns call
        every { result.code } returns null
        every { result.message } returns message
        return result
    }

    private fun kotlinx.coroutines.test.TestScope.vmConCobroDelPos(requestId: String = "req-incierto"): AngelPayPaymentViewModel {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        val vm = createViewModel()
        vm.initPayment(amount = "100.00")
        runCurrent()
        vm.setSocketPaymentSource("SOCKET", requestId)
        vm.primeSdkLaunch()
        runCurrent()
        return vm
    }

    @Test
    fun `U101 deja el cobro en ResultadoIncierto y NUNCA reporta failed al POS`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns
            VerificacionDelCobro.NoSePudoVerificar("sin red")
        val vm = vmConCobroDelPos()
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult())
            runCurrent()

            // La pantalla NO puede decir "rechazado": no se sabe.
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.ResultadoIncierto::class.java)
            // 🔴 Lo que de verdad guarda esta prueba: `failed` es lo que hace que el POS
            // ofrezca Reintentar sobre un cobro que quiza ya paso.
            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(any(), "failed", any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `un desenlace incierto marca INDETERMINADO en la libreta, jamas DESCARTADA`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns
            VerificacionDelCobro.NoSePudoVerificar("sin red")
        val vm = vmConCobroDelPos()
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult())
            runCurrent()

            coVerify(atLeast = 1) { paymentAttemptLedger.markIndeterminate(any(), any()) }
            // DESCARTADA afirma "no se cobro" y ademas se poda a los 7 dias: la evidencia
            // del unico caso en que hace falta desapareceria.
            coVerify(exactly = 0) {
                paymentAttemptLedger.markHostResponded(any(), false, any(), any(), any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `PaymentResult con status TIMEOUT tambien es incierto`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns
            VerificacionDelCobro.NoSePudoVerificar("sin red")
        val vm = vmConCobroDelPos("req-timeout")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult(sdkCode = null, status = PaymentResult.Status.TIMEOUT))
            runCurrent()

            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.ResultadoIncierto::class.java)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `si el historial confirma el cobro se registra y se reporta success`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns
            VerificacionDelCobro.Cobrado(authCode = "251259", referencia = "260908155812", cardBin = "411111")
        coEvery { recordPaymentUseCase(any(), any(), any(), any()) } returns Result.success(
            PaymentReceipt(
                paymentId = "pay-incierto",
                receiptUrl = "https://receipt/pay-incierto",
                accessKey = "key-incierto",
                amount = java.math.BigDecimal("100.00"),
                tipAmount = java.math.BigDecimal.ZERO,
            ),
        )
        val vm = vmConCobroDelPos("req-cobrado")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult())
            runCurrent()

            // El dinero SI se movio: la venta se registra con la autorizacion que devolvio
            // el historial, no se pierde por haber vuelto sin resultado.
            coVerify(timeout = 2000) {
                recordPaymentUseCase(any(), any(), "251259", "260908155812")
            }
            verify(timeout = 2000) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-cobrado",
                    status = "success",
                    paymentId = "pay-incierto",
                    transactionId = any(),
                    cardDetails = any(),
                    errorMessage = any(),
                    receiptUrl = any(),
                    receiptAccessKey = any(),
                 outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `legacy NoCobrado without processor proof stays unknown`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoCobrado
        val vm = vmConCobroDelPos("req-nocobrado")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult())
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.ResultadoIncierto::class.java)
            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(any(), "failed", any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
            }
            coVerify(exactly = 0) { paymentAttemptLedger.markHostResponded(any(), false, any(), any(), any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun `abandoning generic Error after SDK entry cannot cancel the request`() = runTest(testDispatcher) {
        val vm = vmConCobroDelPos("req-error-after-launch")
        try {
            vm.onIntentLaunched()
            val field = AngelPayPaymentViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val state = field.get(vm) as MutableStateFlow<AngelPayPaymentState>
            state.value = AngelPayPaymentState.Error("Unexpected callback error", canRetry = false)
            vm.emitCancelledIfAbandoned()
            vm.retryAfterError()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
            vm.resetPayment()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(any(), "cancelled", any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
            }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun `sin poder verificar se le dice timeout al POS, nunca failed`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns
            VerificacionDelCobro.NoSePudoVerificar("sin red")
        val vm = vmConCobroDelPos("req-sinverificar")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult())
            runCurrent()

            // `timeout` es lo mas cerca de "no se" que admite el contrato del servidor
            // (success|failed|cancelled|timeout): cierra la fila sin decirle al POS que
            // fallo, y un cobro que aparezca despues la reconcilia a COMPLETED.
            verify(timeout = 2000) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-sinverificar",
                    status = "timeout",
                    paymentId = any(),
                    transactionId = any(),
                    cardDetails = any(),
                    errorMessage = any(),
                    receiptUrl = any(),
                    receiptAccessKey = any(),
                 outcomeEvidence = any())
            }
            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(any(), "failed", any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `no se puede volver a cobrar mientras el desenlace sigue sin verificar`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns
            VerificacionDelCobro.NoSePudoVerificar("sin red")
        val vm = vmConCobroDelPos("req-bloqueo")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult())
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.ResultadoIncierto::class.java)

            vm.retryAfterError()
            runCurrent()

            // Sigue bloqueado: el boton no puede llevar al cajero de vuelta al cobro
            // mientras nadie sepa si el primero paso.
            //
            // 🔴 Desde la cerca durable de C.5 (11-sep) el bloqueo se DICE, en vez de dejar la
            // pantalla muda en `ResultadoIncierto`: la solicitud ya emitio su desenlace al POS
            // (`timeout`, lo fija la prueba de arriba) y esta terminal no la vuelve a ejecutar.
            // Lo que la prueba guarda es lo mismo de siempre —no se puede volver a cobrar— y se
            // aprieta con las dos cosas que lo garantizan: `canRetry = false` (el boton no
            // reaparece) y el texto de la tabla unica, que ademas le dice al cajero que hacer.
            val estado = vm.state.value
            assertThat(estado).isInstanceOf(AngelPayPaymentState.Error::class.java)
            assertThat((estado as AngelPayPaymentState.Error).message).isEqualTo(CobroRemotoDelPos.SOLICITUD_CERRADA)
            assertThat(estado.canRetry).isFalse()
            // Y sobre todo: nunca vuelve a un estado desde el que se pueda autorizar otra vez.
            assertThat(estado).isNotInstanceOf(AngelPayPaymentState.LaunchingAngelPaySdk::class.java)
            assertThat(estado).isNotInstanceOf(AngelPayPaymentState.LaunchingAngelPay::class.java)
            assertThat(estado).isNotInstanceOf(AngelPayPaymentState.WaitingForResult::class.java)
            assertThat(estado).isNotInstanceOf(AngelPayPaymentState.Charging::class.java)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `G500 sigue siendo un rechazo confirmado y no se vuelve incierto`() = runTest(testDispatcher) {
        // Regresion: la clasificacion nueva no puede convertir rechazos reales del emisor
        // en "por verificar" — eso bloquearia ventas buenas en cada tarjeta sin fondos.
        val vm = vmConCobroDelPos("req-g500")
        try {
            vm.onAngelPaySdkResult(sdkFailureResult("G500"))
            runCurrent()

            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
            coVerify(exactly = 0) { chargeVerifier.verificar(any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { paymentAttemptLedger.markIndeterminate(any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `un fallo de registro previo al cobro se recupera aunque llegue con status TIMEOUT`() = runTest(testDispatcher) {
        // 🔴 La forma de sesion expirada gana sobre "no se sabe": ahi esta demostrado que el
        // SDK aborto ANTES de llamar al gateway, asi que relanzar no puede duplicar nada.
        // Sin esta precedencia el cajero perderia la re-autenticacion automatica y se
        // quedaria con una venta bloqueada por un cobro que nunca salio.
        coEvery { angelPayAuthRepository.handleAuthExpiry() } answers { Result.success(Unit) }
        val vm = vmConCobroDelPos("req-registro")
        try {
            val fallo = sdkInciertoResult(
                sdkCode = "N400",
                status = PaymentResult.Status.TIMEOUT,
                message = "No se pudo registrar la terminal antes del cobro",
            )
            vm.onAngelPaySdkResult(fallo)
            runCurrent()

            coVerify(exactly = 1) { angelPayAuthRepository.handleAuthExpiry() }
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.LaunchingAngelPaySdk::class.java)
            coVerify(exactly = 0) { paymentAttemptLedger.markIndeterminate(any(), any()) }
            coVerify(exactly = 0) { chargeVerifier.verificar(any(), any(), any(), any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // Aviso EMV recuperable abandonado — cerrar la fila del POS, no dejarla colgando
    // ----------------------------------------------------------------------

    @Test
    fun `un aviso EMV abandonado cierra la fila del POS con failed y PROCESSOR_DECLINED`() = runTest(testDispatcher) {
        // E608 («Limite contactless excedido») no emite nada a proposito: el cajero puede resolver
        // y reintentar EN la misma sesion. Pero si no vuelve, hoy no se emite nunca: la
        // fila del POS vence sola y el vigilante del servidor la parquea en UNKNOWN, que
        // RETIENE el slot de la terminal (incidente Testarudo, PAX bloqueada 3 h).
        val vm = vmConCobroDelPos("req-e608")
        // 🔴 100 ms en vez de 2 min: avanzar el reloj virtual dos minutos dentro de este
        // ViewModel despierta todos sus colectores periódicos y una excepción de cualquiera
        // de ellos contamina la prueba siguiente. Lo que se prueba es el reloj, no su duración.
        vm.msAbandonoAvisoEmv = 100L
        try {
            vm.onAngelPaySdkResult(
                sdkFailureResult(sdkCode = "E608", message = "Limite contactless excedido", category = "EMV"),
            )
            runCurrent()
            // De inmediato no se avisa nada: el reintento en sesion sigue vivo.
            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(any(), any(), any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
            }

            advanceTimeBy(300)
            runCurrent()

            // Nadie volvio: se cierra de forma inequivoca. Y sale como `failed` + PROCESSOR_DECLINED,
            // que es la ÚNICA combinación que el servidor acredita como «no se cobró» con esa evidencia:
            // con `cancelled` la degrada a timeout, la fila queda UNKNOWN y la terminal sigue reservada.
            // La evidencia es PROCESSOR_DECLINED porque el SDK ya se había lanzado (P2-13: la tabla de
            // rechazos confirmados es una sola y E608 está en ella).
            verify {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-e608",
                    status = "failed",
                    paymentId = any(),
                    transactionId = any(),
                    cardDetails = any(),
                    errorMessage = any(),
                    receiptUrl = any(),
                    receiptAccessKey = any(),
                 outcomeEvidence = "PROCESSOR_DECLINED")
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // 🛑 C.5 — el POS cancela un cobro que esta terminal YA reclamó
    // ----------------------------------------------------------------------

    private fun cancelDelPos(requestId: String, disposition: String) = SocketEvent.TerminalPaymentCancel(
        requestId = requestId, reason = "cancelado desde el POS", timestamp = "2026-09-11T12:00:00Z",
        disposition = disposition,
    )

    @Test
    fun `un cancel ACEPTADO deja la pantalla en Cancelado, sin reintentar y sin emitir otro desenlace`() = runTest(testDispatcher) {
        val vm = vmConCobroDelPos("req-cancelado")
        try {
            vm.manejarCancelacionRemota(cancelDelPos("req-cancelado", "ACCEPTED"))
            runCurrent()

            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Cancelled::class.java)
            assertThat(vm.mensajeDelPos.value).isEqualTo(CobroRemotoDelPos.CANCELADO_POR_EL_POS)
            // El desenlace durable lo escribió la bandeja: este VM no emite otro, ni al salir.
            vm.resetPayment()
            runCurrent()
            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(any(), any(), any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `tras un cancel ACEPTADO, Reintentar no vuelve a cobrar`() = runTest(testDispatcher) {
        val vm = vmConCobroDelPos("req-cancelado-retry")
        try {
            vm.manejarCancelacionRemota(cancelDelPos("req-cancelado-retry", "ACCEPTED"))
            runCurrent()

            vm.retryAfterError()
            runCurrent()

            val estado = vm.state.value as AngelPayPaymentState.Error
            assertThat(estado.canRetry).isFalse()
            assertThat(estado.message).isEqualTo(CobroRemotoDelPos.CANCELADO_POR_EL_POS)
            coVerify(exactly = 0) { paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `un cancel ACTIVO no toca el estado - el cobro ya empezo`() = runTest(testDispatcher) {
        val vm = vmConCobroDelPos("req-activo")
        try {
            val antes = vm.state.value
            vm.manejarCancelacionRemota(cancelDelPos("req-activo", "ACTIVE"))
            runCurrent()

            assertThat(vm.state.value).isEqualTo(antes)
            assertThat(vm.mensajeDelPos.value).isEqualTo(CobroRemotoDelPos.CANCEL_TARDE)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `el cancel de OTRA solicitud no cancela este cobro`() = runTest(testDispatcher) {
        val vm = vmConCobroDelPos("req-mio")
        try {
            val antes = vm.state.value
            vm.manejarCancelacionRemota(cancelDelPos("req-de-otro", "ACCEPTED"))
            runCurrent()

            assertThat(vm.state.value).isEqualTo(antes)
            assertThat(vm.mensajeDelPos.value).isNull()
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `la cerca de la libreta se traduce a - el POS cancelo este cobro - y no a un error de disco`() = runTest(testDispatcher) {
        coEvery { paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) } returns false
        coEvery { paymentAttemptLedger.cercaDeSolicitud(any()) } returns CercaDeSolicitud.CANCELADA_POR_EL_POS
        val vm = vmConCobroDelPos("req-cercado")
        try {
            vm.flujoSdkForzadoParaPruebas = true
            vm.startCardPayment()
            runCurrent()
            advanceUntilIdle()

            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Cancelled::class.java)
            assertThat(vm.mensajeDelPos.value).isEqualTo(CobroRemotoDelPos.CANCELADO_POR_EL_POS)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `el efectivo de un cobro remoto no se registra si el POS ya cancelo`() = runTest(testDispatcher) {
        coEvery { paymentAttemptLedger.iniciarEjecucionNoTarjeta(any()) } returns CercaDeSolicitud.CANCELADA_POR_EL_POS
        val vm = vmConCobroDelPos("req-efectivo-cancelado")
        try {
            vm.startCashPayment()
            runCurrent()
            advanceUntilIdle()

            coVerify(exactly = 0) { recordPaymentUseCase(any(), any(), any(), any()) }
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Cancelled::class.java)
            assertThat(vm.mensajeDelPos.value).isEqualTo(CobroRemotoDelPos.CANCELADO_POR_EL_POS)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `el efectivo de un cobro iniciado en la terminal no consulta la cerca`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        coEvery { recordPaymentUseCase(any(), any(), any(), any()) } returns Result.success(
            PaymentReceipt(
                paymentId = "pay-local", receiptUrl = "https://receipt/pay-local", accessKey = "k",
                amount = java.math.BigDecimal("100.00"), tipAmount = java.math.BigDecimal.ZERO,
            ),
        )
        val vm = createViewModel()
        try {
            vm.initPayment(amount = "100.00")
            runCurrent()
            vm.startCashPayment()
            runCurrent()
            advanceUntilIdle()

            coVerify(exactly = 0) { paymentAttemptLedger.iniciarEjecucionNoTarjeta(any()) }
            coVerify(timeout = 2000, exactly = 1) { recordPaymentUseCase(any(), any(), any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `si el cajero reintenta tras el aviso EMV, nadie cancela la fila`() = runTest(testDispatcher) {
        val vm = vmConCobroDelPos("req-e608-retry")
        vm.msAbandonoAvisoEmv = 100L
        try {
            vm.onAngelPaySdkResult(
                sdkFailureResult(sdkCode = "E608", message = "Limite contactless excedido", category = "EMV"),
            )
            runCurrent()
            vm.retryAfterError()
            runCurrent()

            advanceTimeBy(300)
            runCurrent()

            // El reloj de abandono se apaga al retomar: cancelar aqui cerraria la fila
            // justo cuando el cajero esta por cobrar de verdad.
            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(any(), any(), any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }
}

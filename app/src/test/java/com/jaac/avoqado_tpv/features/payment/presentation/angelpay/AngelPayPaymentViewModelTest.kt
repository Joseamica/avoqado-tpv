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
import com.google.common.truth.Truth.assertWithMessage
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.core.observability.ObservabilityManager
import com.jaac.avoqado_tpv.core.printer.PrinterManager
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.payment.data.api.PaymentApiService
import com.jaac.avoqado_tpv.features.payment.data.ledger.CercaDeSolicitud
import com.jaac.avoqado_tpv.features.payment.data.ledger.LedgerServerRecovery
import com.jaac.avoqado_tpv.features.payment.data.ledger.LedgerServerRecovery.LecturaDelIntento
import com.jaac.avoqado_tpv.features.payment.data.ledger.NoInstrumentResolutionRequest
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity
import com.jaac.avoqado_tpv.features.payment.data.ledger.TerminalAttemptApiService
import com.jaac.avoqado_tpv.features.payment.data.ledger.TerminalAttemptResultDto
import com.jaac.avoqado_tpv.features.payment.data.ledger.TerminalAttemptStatusResponse
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
import io.mockk.coVerifyOrder
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Response

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
    // 🕰️ Task 7 (ventana de confirmación): la recuperación por servidor (S6) que la pantalla consulta cada 5 s mientras
    // espera el veredicto, y la API con la que el cajero DECLARA «no se presentó tarjeta». La primera relajada (por defecto
    // no hay nada que aplicar: `recoverOne` devuelve ""/null); la segunda estricta, para que un POST no previsto reviente.
    private val ledgerServerRecovery: LedgerServerRecovery = mockk(relaxed = true)
    private val attemptApi: TerminalAttemptApiService = mockk()

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
            // 🔴 Explícito, y no por relajado: un mock relajado devuelve un CobroSinResolver FALSO,
            // y entonces el ViewModel adopta un intento inventado y sigue registrando un pago que
            // nunca ocurrió. Por defecto NO hay nada que adoptar, que es el caso de estas pruebas.
            coEvery { adoptarCobroDeLaSolicitud(any()) } returns null
            // Fix 4: la restauración por solicitud lee la fila propia; por defecto no hay ninguna (un relajado devolvería una fila FALSA).
            coEvery { intentoDeLaSolicitud(any()) } returns null
            coEvery { openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) } returns true
            coEvery { markAuthorizing(any()) } returns true
            // C.5: por defecto la solicitud no está cercada y el efectivo/cripto puede arrancar.
            coEvery { cercaDeSolicitud(any()) } returns CercaDeSolicitud.LIBRE
            coEvery { iniciarEjecucionNoTarjeta(any()) } returns CercaDeSolicitud.LIBRE
            // Checkpoint 2 (E1, Codex P1-1): la pantalla consume la DECISIÓN de la libreta. Por defecto el 2xx del REST queda
            // APLICADO (un relajado devuelve un `Result` con un Object adentro y revienta el `getOrNull()` con ClassCastException).
            coEvery { aplicarVeredictoDelServidor(any()) } returns Result.success(
                com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto(
                    com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto.Decision.APLICADO, true, null, false,
                ),
            )
            // Task 7: por defecto la libreta no tiene fila que leer (un relajado devolvería una fila FALSA con `state = ""`).
            coEvery { leerIntento(any()) } returns null
            // Task 7 · fix 4: la evidencia positiva del servidor se hace DURABLE (un relajado devolvería un `Result` roto).
            coEvery { marcarEvidenciaPositivaDelServidor(any(), any(), any()) } returns Result.success(true)
            // Codex r7 (P1-1): el veto durable también se escribe desde la declaración (un relajado devolvería un `Result` roto).
            coEvery { marcarVetoDelServidor(any(), any(), any(), any()) } returns Result.success(true)
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
        // Task 7 (ventana de confirmación): S6 mientras se espera al servidor + la declaración del cajero.
        ledgerServerRecovery = ledgerServerRecovery,
        attemptApi = attemptApi,
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
    fun `approved SDK result marks host verdict then AUTORIZADO, and the REST verdict (E1) decides REGISTRADO`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        // Checkpoint 2 · E1: el 2xx del REST ya no libera con `markRecorded` a ciegas — se aplica como VEREDICTO durable.
        val veredictos = mutableListOf<com.jaac.avoqado_tpv.features.payment.data.ledger.VeredictoDeIntento>()
        coEvery { paymentAttemptLedger.aplicarVeredictoDelServidor(capture(veredictos)) } returns Result.success(
            com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto(
                com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto.Decision.APLICADO, true, null, false,
            ),
        )
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
            coVerify(timeout = 2000, exactly = 1) { paymentAttemptLedger.aplicarVeredictoDelServidor(any()) }
            val veredicto = veredictos.single()
            assertThat(veredicto.outcome).isEqualTo(com.jaac.avoqado_tpv.features.payment.domain.model.VeredictoDelServidor.RECORDED)
            assertThat(veredicto.fuente).isEqualTo(com.jaac.avoqado_tpv.features.payment.data.ledger.VeredictoDeIntento.Fuente.REST)
            assertThat(veredicto.paymentId).isEqualTo("pay-led-1")
            assertThat(veredicto.amountCents).isEqualTo(10000L)
            assertThat(veredicto.requestId).isNull() // cobro LOCAL: sin solicitud del POS
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Success::class.java)
            // El paso a REGISTRADO lo decide el veredicto (E1): nadie llama `markRecorded` a ciegas.
            coVerify(exactly = 0) { paymentAttemptLedger.markRecorded(any()) }
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

    private fun resultado(decision: com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto.Decision, transiciono: Boolean, contradiccion: Boolean) =
        Result.success(com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto(decision, transiciono, null, contradiccion))

    /** Cobro remoto aprobado por el SDK y registrado por REST con el recibo dado; el DAO contesta [decision]. */
    private fun kotlinx.coroutines.test.TestScope.vmRemotoRegistradoCon(
        decision: com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto.Decision, transiciono: Boolean, contradiccion: Boolean,
        receipt: PaymentReceipt = PaymentReceipt(paymentId = "pay-1", receiptUrl = "https://receipt/pay-1", accessKey = "key-1",
            amount = java.math.BigDecimal("100.00"), tipAmount = java.math.BigDecimal.ZERO),
    ): AngelPayPaymentViewModel {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        coEvery { recordPaymentUseCase(any(), any(), any(), any()) } returns Result.success(receipt)
        coEvery { paymentAttemptLedger.aplicarVeredictoDelServidor(any()) } returns resultado(decision, transiciono, contradiccion)
        val vm = createViewModel()
        vm.initPayment(amount = "100.00")
        runCurrent()
        vm.setSocketPaymentSource("SOCKET", "req-veredicto")
        vm.onAngelPaySdkResult(approvedSdkResult())
        runCurrent()
        return vm
    }

    @Test
    fun `P1-1 el REST NO pinta Success ni reporta success al POS cuando el DAO RECHAZA el veredicto o guarda una contradiccion`() = runTest(testDispatcher) {
        // Codex (código, P1-1): `aplicarVeredictoDelRest` decidía sólo con el recibo. Libreta $100, REST RECORDED $90 ⇒ el DAO
        // conserva la discrepancia sin liberar y la pantalla decía «cobrado» con el importe pendiente, reportando `success`.
        val casos = listOf(
            Triple(com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto.Decision.RECHAZADO_DATOS_DISTINTOS, false, true),
            Triple(com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto.Decision.RECHAZADO_OTRO_PAYMENT, false, true),
            Triple(com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto.Decision.RECHAZADO_PERTENENCIA, false, false),
            Triple(com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto.Decision.GUARDADO_SIN_LIBERAR, false, true), // RECORDED con montos distintos
        )
        for ((decision, transiciono, contradiccion) in casos) {
            io.mockk.clearMocks(socketManager, paymentAttemptLedger, answers = false, recordedCalls = true, verificationMarks = true)
            val vm = vmRemotoRegistradoCon(decision, transiciono, contradiccion)
            try {
                // Se espera al ESTADO en tiempo real, no a la llamada al mock: la continuación tras `withContext(IO)` corre en el
                // hilo de IO (dispatcher de prueba unconfined) y `coVerify(timeout)` volvía en cuanto el mock era LLAMADO, unas
                // instrucciones ANTES de `_state.value = Error` — leía `RecordingPayment` 1 de cada 3 corridas (Task 7, fix 1).
                val estado = withContext(Dispatchers.Default) {
                    kotlinx.coroutines.withTimeout(2_000) { vm.state.first { it is AngelPayPaymentState.Error || it is AngelPayPaymentState.Success } }
                }
                coVerify(exactly = 1) { paymentAttemptLedger.aplicarVeredictoDelServidor(any()) }
                assertThat(estado).isNotInstanceOf(AngelPayPaymentState.Success::class.java)
                assertThat(estado).isInstanceOf(AngelPayPaymentState.Error::class.java)
                assertThat((estado as AngelPayPaymentState.Error).canRetry).isFalse()
                assertThat(estado.message).isEqualTo(CobroRemotoDelPos.REGISTRADO_CON_DISCREPANCIA)
                verify(exactly = 0) {
                    socketManager.emitTerminalPaymentResult(any(), "success", any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
                }
            } finally { vm.viewModelScope.cancel() }
        }
    }

    @Test
    fun `P1-1 un veredicto ya APLICADO antes (S5 llego primero, sin transicion nueva) sigue siendo Success y reporta success`() = runTest(testDispatcher) {
        val vm = vmRemotoRegistradoCon(com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto.Decision.APLICADO, transiciono = false, contradiccion = false)
        try {
            verify(timeout = 2000) {
                socketManager.emitTerminalPaymentResult(requestId = "req-veredicto", status = "success", paymentId = "pay-1",
                    transactionId = any(), cardDetails = any(), errorMessage = any(), receiptUrl = any(), receiptAccessKey = any(), outcomeEvidence = any())
            }
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Success::class.java)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun `P1-1 una segunda captura APLICADA con ganador conserva su aviso y reporta al GANADOR`() = runTest(testDispatcher) {
        val vm = vmRemotoRegistradoCon(
            com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto.Decision.APLICADO, transiciono = true, contradiccion = true,
            receipt = PaymentReceipt(paymentId = "pay-mio", receiptUrl = "", accessKey = "", amount = java.math.BigDecimal("100.00"),
                tipAmount = java.math.BigDecimal.ZERO, serverStatus = "PENDING", reconciliationKind = "POSSIBLE_SECOND_CAPTURE", winnerPaymentId = "pay-ganador"),
        )
        try {
            verify(timeout = 2000) {
                socketManager.emitTerminalPaymentResult(requestId = "req-veredicto", status = "success", paymentId = "pay-ganador",
                    transactionId = any(), cardDetails = any(), errorMessage = any(), receiptUrl = any(), receiptAccessKey = any(), outcomeEvidence = any())
            }
            runCurrent()
            val estado = vm.state.value
            assertThat(estado).isInstanceOf(AngelPayPaymentState.Success::class.java)
            assertThat((estado as AngelPayPaymentState.Success).aviso).isEqualTo(CobroRemotoDelPos.SEGUNDA_CAPTURA)
        } finally { vm.viewModelScope.cancel() }
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

    // ----------------------------------------------------------------------
    // Ventana de confirmación (Task 7, 2026-09-16) — la pantalla ESPERA al servidor, ofrece
    // «El cliente no presentó tarjeta» y NUNCA ofrece Reintentar.
    // ----------------------------------------------------------------------
    //
    // Sin veredicto del historial, la duda se le pasa al SERVIDOR (`timeout` al POS, como
    // siempre) y esta pantalla consulta S6 cada 5 s hasta 45 s. La fila se lee con EL DINERO
    // PRIMERO: REGISTRADO ⇒ Success; RECORDED sin promover ⇒ contradicción (nunca «se puede
    // volver a cobrar»); liberación del servidor ⇒ «se puede volver a cobrar» y a los 4 s Idle.

    private fun filaLiberada(attemptId: String, evidencia: String) = mockk<PaymentAttemptEntity>(relaxed = true).also {
        every { it.attemptId } returns attemptId
        every { it.state } returns PaymentAttemptEntity.STATE_DESCARTADA
        every { it.lastError } returns PaymentAttemptEntity.LAST_ERROR_LIBERADA_PREFIX + evidencia
        every { it.serverOutcome } returns if (evidencia == "OPERATOR_RECONCILED") PaymentAttemptEntity.SERVER_OPERATOR_NO_INSTRUMENT else PaymentAttemptEntity.SERVER_RELEASED_NO_EVIDENCE
        every { it.serverPaymentId } returns null
    }
    private fun filaRegistrada(attemptId: String) = mockk<PaymentAttemptEntity>(relaxed = true).also {
        every { it.attemptId } returns attemptId
        every { it.state } returns PaymentAttemptEntity.STATE_REGISTRADO
        every { it.serverOutcome } returns PaymentAttemptEntity.SERVER_RECORDED
        every { it.serverPaymentId } returns "pay-s6"
    }
    /** Contradicción: el servidor registró dinero, pero la libreta ya estaba DESCARTADA (liberada) y no pudo promover. */
    private fun filaContradictoria(attemptId: String) = mockk<PaymentAttemptEntity>(relaxed = true).also {
        every { it.attemptId } returns attemptId
        every { it.state } returns PaymentAttemptEntity.STATE_DESCARTADA
        every { it.lastError } returns PaymentAttemptEntity.LAST_ERROR_LIBERADA_PREFIX + "NO_EVIDENCE_AFTER_WINDOW"
        every { it.serverOutcome } returns PaymentAttemptEntity.SERVER_RECORDED
        every { it.serverPaymentId } returns "pay-tardio"
    }
    private fun respuestaDeclaracionOk() = Response.success(TerminalAttemptStatusResponse(success = true))
    private fun respuesta403() = Response.error<TerminalAttemptStatusResponse>(403, """{"success":false,"code":"SUPERVISOR_AUTHORIZATION_REQUIRED"}""".toResponseBody("application/json".toMediaType()))
    // Ronda 23: un 409 GENÉRICO. `POSITIVE_EVIDENCE_EXISTS` ya no es genérico — veta desde que llega (ver «R23 la declaracion…»).
    private fun respuesta409() = Response.error<TerminalAttemptStatusResponse>(409, """{"success":false,"code":"RESOLUTION_CONFLICT"}""".toResponseBody("application/json".toMediaType()))

    @Test fun `INCIERTO sin hallazgo en historial emite timeout al POS y se queda esperando al servidor con el boton de declarar`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("historial vacío")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null
        // El holder es un mock relajado (su `isChargeAttemptActive()` siempre da false): se lee lo ÚLTIMO que el VM publicó.
        val publicado = mutableListOf<Boolean>()
        every { paymentStateHolder.setChargeAttemptActive(capture(publicado)) } returns Unit
        val vm = vmConCobroDelPos("REQ-W1")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            verify(exactly = 1) { socketManager.emitTerminalPaymentResult("REQ-W1", "timeout", any(), any(), any(), any(), any(), any(), outcomeEvidence = any()) }
            coVerify(atLeast = 1) { ledgerServerRecovery.recoverOne("v1", any(), any(), false) }   // el sondeo NO gasta el turno de E3 (Task 8 · S37b)
            val s = vm.state.value as AngelPayPaymentState.ResultadoIncierto
            assertThat(s.esperandoAlServidor).isTrue(); assertThat(s.puedeDeclarar).isTrue()
            assertThat(publicado.last()).isTrue()   // ESTA pantalla sigue ocupada mientras espera (rechaza un segundo cobro remoto)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `si S6 trae la liberacion por ventana, la pantalla dice que se puede volver a cobrar, sin Reintentar, y vuelve a Idle`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null andThen filaLiberada("att", "NO_EVIDENCE_AFTER_WINDOW")   // null en la consulta inmediata; liberada en el sondeo
        val vm = vmConCobroDelPos("REQ-W2")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            advanceTimeBy(vm.msEntreConsultasS6 + 100); runCurrent()
            val s = vm.state.value as AngelPayPaymentState.Error
            assertThat(s.canRetry).isFalse(); assertThat(s.message).contains("volver a cobrar")
            advanceTimeBy(vm.msMostrarLiberada + 100); runCurrent()
            assertThat(vm.state.value).isEqualTo(AngelPayPaymentState.Idle)
            verify(exactly = 0) { socketManager.emitTerminalPaymentResult(any(), "failed", any(), any(), any(), any(), any(), any(), outcomeEvidence = any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `si S6 trae RECORDED durante la espera, gana el dinero — Success con el paymentId de la libreta`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        // La consulta inmediata no resuelve nada; el sondeo de los 5 s SÍ resuelve la bandeja y devuelve su JSON durable.
        val bandeja = """{"requestId":"REQ-W3","status":"success","paymentId":"pay-s6"}"""
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null) andThen LecturaDelIntento(bandeja)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null andThen filaRegistrada("att")   // null en la consulta inmediata; REGISTRADO en el sondeo
        val vm = vmConCobroDelPos("REQ-W3")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            advanceTimeBy(vm.msEntreConsultasS6 + 100); runCurrent()
            val s = vm.state.value as AngelPayPaymentState.Success
            assertThat(s.receipt?.paymentId).isEqualTo("pay-s6"); assertThat(s.receipt?.serverRecordedVia).isEqualTo("s6")
            // Fix 1 · Minor #5: el JSON durable que resolvió `recoverOne` se EMITE al servidor, como hacen el trigger y el worker.
            verify(exactly = 1) { socketManager.emitDurableTerminalPaymentResult(bandeja) }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `una fila DESCARTADA con RECORDED encima es contradiccion — la pantalla dice que Avoqado registro dinero y NUNCA que se puede volver a cobrar`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null andThen filaContradictoria("att")   // null en la consulta inmediata; contradicción en el sondeo
        val vm = vmConCobroDelPos("REQ-W8")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            advanceTimeBy(vm.msEntreConsultasS6 + 100); runCurrent()
            val s = vm.state.value as AngelPayPaymentState.Error
            assertThat(s.canRetry).isFalse(); assertThat(s.message).contains("evidencia de cobro"); assertThat(s.message).doesNotContain("volver a cobrar")
            advanceTimeBy(vm.msMostrarLiberada + 100); runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)   // sin reset automático: el cajero sale con Salir
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `r8 P1-2 - si guardar el veto FALLA, el veto de la respuesta manda igual - la liberacion VIEJA de la fila no se anuncia`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        // Codex r8: una respuesta limpia anterior dejó la fila LIBERADA; la consulta nueva trae la contradicción, pero guardarla
        // en la libreta falló (la fila sigue sin `server_veto`). Leer la fila sí funciona. Sin reiniciar nada.
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns
            LecturaDelIntento(null, servidorContesto = true, vetoDelServidor = PaymentAttemptEntity.VETO_PAYMENT_CONTRADICTION)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns filaLiberada("att", "NO_EVIDENCE_AFTER_WINDOW")
        val vm = vmConCobroDelPos("REQ-R8-P12")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            val s = vm.state.value as AngelPayPaymentState.Error
            assertThat(s.canRetry).isFalse()
            assertThat(s.message).contains("evidencia de cobro")
            assertThat(s.message).doesNotContain("volver a cobrar")
            advanceTimeBy(vm.msMostrarLiberada + 100); runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)   // sin reset automático
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `un 2xx cuyo cuerpo trae RECORDED aplica el veredicto del CUERPO (sin otro viaje de red) y termina en Success aunque S6 falle`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)   // S6 «sin red»
        val cuerpo = TerminalAttemptStatusResponse(success = true, attemptId = "att", requestId = "REQ-W10",
            attempt = TerminalAttemptResultDto(attemptId = "att", outcome = "RECORDED", paymentId = "pay-body", paymentStatus = "COMPLETED", recordedVia = "webhook", amountCents = 10000, tipCents = 0, isWinner = true, winnerPaymentId = "pay-body"))
        coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } returns Response.success(cuerpo)
        coEvery { paymentAttemptLedger.aplicarVeredictoDelServidor(any()) } returns Result.success(com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto(com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto.Decision.APLICADO, true, null, false))   // real: «quedó en la fila» se lee de la decisión
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null andThen filaRegistrada("att")   // null durante la espera; REGISTRADO tras guardar el cuerpo
        val vm = vmConCobroDelPos("REQ-W10")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            vm.declararSinTarjeta(); runCurrent()
            // El veredicto se guarda con el intento de ESTE cobro (el id que generó la terminal, no el del cuerpo) y con lo que dijo el CUERPO.
            coVerify(exactly = 1) { paymentAttemptLedger.aplicarVeredictoDelServidor(match { it.outcome.name == "RECORDED" && it.paymentId == "pay-body" && it.requestId == "REQ-W10" }) }
            coVerify(exactly = 0) { paymentAttemptLedger.aplicarLiberacionDelServidor(any(), any()) }   // con dinero en el cuerpo la liberación NI SE INTENTA
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Success::class.java)
            verify(exactly = 0) { socketManager.emitTerminalPaymentResult(any(), "failed", any(), any(), any(), any(), any(), any(), outcomeEvidence = any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `un 2xx con RECORDED cuyo guardado FALLA prohibe recobrar aunque la fila conserve una liberacion vieja`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        val cuerpo = TerminalAttemptStatusResponse(success = true, attemptId = "att", requestId = "REQ-W12",
            attempt = TerminalAttemptResultDto(attemptId = "att", outcome = "RECORDED", paymentId = "pay-body", paymentStatus = "COMPLETED", recordedVia = "webhook", amountCents = 10000, tipCents = 0, isWinner = true, winnerPaymentId = "pay-body"))
        coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } returns Response.success(cuerpo)
        coEvery { paymentAttemptLedger.aplicarVeredictoDelServidor(any()) } returns Result.failure(IllegalStateException("room caída"))
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null andThen filaLiberada("att", "NO_EVIDENCE_AFTER_WINDOW") // liberación VIEJA en la fila
        val vm = vmConCobroDelPos("REQ-W12")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            vm.declararSinTarjeta(); runCurrent()
            coVerify(exactly = 0) { paymentAttemptLedger.aplicarLiberacionDelServidor(any(), any()) }   // con dinero en el cuerpo la liberación NI SE INTENTA
            val s = vm.state.value as AngelPayPaymentState.Error
            assertThat(s.canRetry).isFalse(); assertThat(s.message).contains("evidencia de cobro"); assertThat(s.message).doesNotContain("volver a cobrar")
            advanceTimeBy(vm.msMostrarLiberada + 100); runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)   // sin reset automático
        } finally { vm.viewModelScope.cancel() }
    }

    // ── Codex r11 (P2-4): la respuesta de la DECLARACIÓN con dinero que no quedó en la fila deja la marca durable ──

    @Test fun `r11 P2-4 - un 2xx de la declaracion con RECORDED que NO quedo en la fila (fallo o pertenencia) deja la evidencia durable`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        val casos = listOf(
            "falla la transacción" to Result.failure<com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto>(IllegalStateException("room caída")),
            "otra solicitud" to Result.success(com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto(
                com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto.Decision.RECHAZADO_PERTENENCIA, false, null, false)),
        )
        for ((caso, resultado) in casos) {
            val requestId = "REQ-P24-${caso.length}"
            val cuerpo = TerminalAttemptStatusResponse(success = true, attemptId = "att", requestId = requestId,
                attempt = TerminalAttemptResultDto(attemptId = "att", outcome = "RECORDED", paymentId = "pay-body", paymentStatus = "COMPLETED", recordedVia = "webhook", amountCents = 10000, tipCents = 0, isWinner = true, winnerPaymentId = "pay-body"))
            coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } returns Response.success(cuerpo)
            coEvery { paymentAttemptLedger.aplicarVeredictoDelServidor(any()) } returns resultado
            // La fila sólo trae una liberación VIEJA: si la pantalla se recrea, eso es lo único que quedaría sin la marca.
            coEvery { paymentAttemptLedger.leerIntento(any()) } returns null andThen filaLiberada("att", "NO_EVIDENCE_AFTER_WINDOW")
            io.mockk.clearMocks(paymentAttemptLedger, answers = false, recordedCalls = true, childMocks = false, verificationMarks = true, exclusionRules = false)
            val vm = vmConCobroDelPos(requestId)
            try {
                vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
                val intento = vm.attemptIdForTest()!!
                vm.declararSinTarjeta(); runCurrent()
                coVerify(exactly = 1) { paymentAttemptLedger.marcarEvidenciaPositivaDelServidor("v1", intento, any()) }
                val s = vm.state.value as AngelPayPaymentState.Error
                assertWithMessage(caso).that(s.canRetry).isFalse()
                assertWithMessage(caso).that(s.message).contains("evidencia de cobro")
            } finally { vm.viewModelScope.cancel() }
        }
    }

    @Test fun `r12 P2-1 - cerrar la pantalla mientras se guarda el veredicto de la declaracion no se lleva la marca de dinero`() = runTest(testDispatcher) {
        // Codex r12: «aplicar» y «marcar» iban separados. Cancelada la pantalla mientras Room trabajaba, la cancelación saltaba
        // en el regreso de «aplicar» y la marca nunca se escribía: la fila conservaba sólo su liberación vieja.
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        val requestId = "REQ-P21"
        val cuerpo = TerminalAttemptStatusResponse(success = true, attemptId = "att", requestId = requestId,
            attempt = TerminalAttemptResultDto(attemptId = "att", outcome = "RECORDED", paymentId = "pay-body", paymentStatus = "COMPLETED", recordedVia = "webhook", amountCents = 10000, tipCents = 0, isWinner = true, winnerPaymentId = "pay-body"))
        coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } returns Response.success(cuerpo)
        val guardando = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { paymentAttemptLedger.aplicarVeredictoDelServidor(any()) } coAnswers {
            guardando.await()
            Result.success(com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto(
                com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto.Decision.RECHAZADO_PERTENENCIA, false, null, false))
        }
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null
        io.mockk.clearMocks(paymentAttemptLedger, answers = false, recordedCalls = true, childMocks = false, verificationMarks = true, exclusionRules = false)
        val vm = vmConCobroDelPos(requestId)
        vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
        val intento = vm.attemptIdForTest()!!
        vm.declararSinTarjeta(); runCurrent()          // detenida DENTRO de «aplicar el veredicto»
        // Codex r14: la marca que aparta el aparato va ANTES del veredicto — ya está escrita cuando la pantalla se va.
        coVerify(exactly = 1) { paymentAttemptLedger.marcarEvidenciaPositivaDelServidor("v1", intento, any()) }
        io.mockk.clearMocks(paymentAttemptLedger, answers = false, recordedCalls = true, childMocks = false, verificationMarks = true, exclusionRules = false)

        vm.viewModelScope.cancel(); runCurrent()       // la pantalla se va
        guardando.complete(Unit); runCurrent()

        // Codex r13 (P2-2): y la cancelación se PROPAGA al salir del tramo — la pantalla muerta no sigue leyendo ni pintando.
        coVerify(exactly = 0) { paymentAttemptLedger.leerIntento(any()) }
    }

    @Test fun `r13 P1 - cerrar la pantalla entre el veto y la aprobacion de la MISMA respuesta no pierde la aprobacion`() = runTest(testDispatcher) {
        // Codex r13: el veto solo NO aparta la terminal para un Pago rápido sin orden; la aprobación sí. Escritos por separado,
        // cancelar entre los dos dejaba sólo el veto y la terminal podía apartarse para otro cobro.
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        val requestId = "REQ-R13P1"
        val cuerpo = TerminalAttemptStatusResponse(success = true, attemptId = "att", requestId = requestId,
            attempt = TerminalAttemptResultDto(attemptId = "att", outcome = "NOT_RECORDED", processorEvidence = "APPROVED", evidenceContradiction = true))
        coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } returns Response.success(cuerpo)
        val guardandoVeto = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { paymentAttemptLedger.marcarVetoDelServidor(any(), any(), any(), any()) } coAnswers { guardandoVeto.await(); Result.success(true) }
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null
        io.mockk.clearMocks(paymentAttemptLedger, answers = false, recordedCalls = true, childMocks = false, verificationMarks = true, exclusionRules = false)
        val vm = vmConCobroDelPos(requestId)
        vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
        val intento = vm.attemptIdForTest()!!
        vm.declararSinTarjeta(); runCurrent()          // detenida DENTRO de la escritura del veto
        // Codex r14: la aprobación va ANTES que el veto — ya está escrita cuando la pantalla se va.
        coVerify(exactly = 1) { paymentAttemptLedger.marcarEvidenciaPositivaDelServidor("v1", intento, any()) }
        io.mockk.clearMocks(paymentAttemptLedger, answers = false, recordedCalls = true, childMocks = false, verificationMarks = true, exclusionRules = false)

        vm.viewModelScope.cancel(); runCurrent()
        guardandoVeto.complete(Unit); runCurrent()

        coVerify(exactly = 0) { paymentAttemptLedger.leerIntento(any()) }
    }

    @Test fun `r14 P1-1 - veto y aprobacion de la MISMA respuesta - la aprobacion (lo que aparta el aparato) se escribe PRIMERO`() = runTest(testDispatcher) {
        // Codex r14: «no cancelable» no es «atómico». Entre los dos commits otra pantalla podía reservar y autorizar; con la
        // aprobación primero no existe el instante «veto sin aprobación».
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        val requestId = "REQ-R14P11"
        val cuerpo = TerminalAttemptStatusResponse(success = true, attemptId = "att", requestId = requestId,
            attempt = TerminalAttemptResultDto(attemptId = "att", outcome = "NOT_RECORDED", processorEvidence = "APPROVED", evidenceContradiction = true))
        coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } returns Response.success(cuerpo)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null
        val vm = vmConCobroDelPos(requestId)
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            val intento = vm.attemptIdForTest()!!
            io.mockk.clearMocks(paymentAttemptLedger, answers = false, recordedCalls = true, childMocks = false, verificationMarks = true, exclusionRules = false)
            vm.declararSinTarjeta(); runCurrent()
            io.mockk.coVerifyOrder {
                paymentAttemptLedger.marcarEvidenciaPositivaDelServidor("v1", intento, any())
                paymentAttemptLedger.marcarVetoDelServidor("v1", intento, any(), any())
            }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `r15 P1 - si la marca que aparta el aparato FALLA, no se aplica el veredicto y la pantalla no invita a cobrar`() = runTest(testDispatcher) {
        // Codex r15: guardar el veredicto sin la marca dejaba la fila fuera de toda recuperación (RECORDED sale de las candidatas y una
        // DESCARTADA no se reaplica) y la terminal aceptaba otro cobro. Sin marca, el veredicto queda para la consulta siguiente.
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        val requestId = "REQ-R15P1"
        val cuerpo = TerminalAttemptStatusResponse(success = true, attemptId = "att", requestId = requestId,
            attempt = TerminalAttemptResultDto(attemptId = "att", outcome = "RECORDED", paymentId = "pay-r", paymentStatus = "COMPLETED", recordedVia = "webhook", amountCents = 10000, tipCents = 0, isWinner = true, winnerPaymentId = "pay-r"))
        coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } returns Response.success(cuerpo)
        coEvery { paymentAttemptLedger.marcarEvidenciaPositivaDelServidor(any(), any(), any()) } returns Result.failure(java.io.IOException("disco lleno"))
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null
        val vm = vmConCobroDelPos(requestId)
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            vm.declararSinTarjeta(); runCurrent()
            coVerify(exactly = 0) { paymentAttemptLedger.aplicarVeredictoDelServidor(any()) }
            val s = vm.state.value as AngelPayPaymentState.Error
            assertThat(s.canRetry).isFalse()
            assertThat(s.message).contains("evidencia de cobro")
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `r14 P1-2 - un veredicto GUARDADO_SIN_LIBERAR tambien deja la aprobacion, y ANTES de aplicarlo`() = runTest(testDispatcher) {
        // Codex r14: «quedó en la fila» no es «aparta el aparato». Un veredicto guardado que no promueve la fila (colisión de
        // referencia, RECORDED sobre una DESCARTADA) no escribe la aprobación, y la reserva de otro cobro no mira el Payment.
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        val requestId = "REQ-R14P12"
        val cuerpo = TerminalAttemptStatusResponse(success = true, attemptId = "att", requestId = requestId,
            attempt = TerminalAttemptResultDto(attemptId = "att", outcome = "REFERENCE_COLLISION_EVIDENCE", paymentId = "pay-col", paymentStatus = "COMPLETED", recordedVia = "webhook", amountCents = 10000, tipCents = 0, isWinner = false, processorEvidence = "APPROVED"))
        coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } returns Response.success(cuerpo)
        coEvery { paymentAttemptLedger.aplicarVeredictoDelServidor(any()) } returns Result.success(com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto(
            com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto.Decision.GUARDADO_SIN_LIBERAR, false, null, true))
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null
        val vm = vmConCobroDelPos(requestId)
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            val intento = vm.attemptIdForTest()!!
            io.mockk.clearMocks(paymentAttemptLedger, answers = false, recordedCalls = true, childMocks = false, verificationMarks = true, exclusionRules = false)
            vm.declararSinTarjeta(); runCurrent()
            coVerify(exactly = 1) { paymentAttemptLedger.marcarEvidenciaPositivaDelServidor("v1", intento, any()) }
            io.mockk.coVerifyOrder {
                paymentAttemptLedger.marcarEvidenciaPositivaDelServidor("v1", intento, any())
                paymentAttemptLedger.aplicarVeredictoDelServidor(any())
            }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `un 2xx con RECORDED guardado pero con la fila sin promover (GUARDADO_SIN_LIBERAR) prohibe recobrar`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        val cuerpo = TerminalAttemptStatusResponse(success = true, attemptId = "att", requestId = "REQ-W13",
            attempt = TerminalAttemptResultDto(attemptId = "att", outcome = "SECOND_CAPTURE_EVIDENCE", paymentId = "pay-2", paymentStatus = "COMPLETED", recordedVia = "webhook", amountCents = 10000, tipCents = 0, isWinner = false, winnerPaymentId = "pay-1"))
        coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } returns Response.success(cuerpo)
        coEvery { paymentAttemptLedger.aplicarVeredictoDelServidor(any()) } returns Result.success(com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto(com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto.Decision.GUARDADO_SIN_LIBERAR, false, null, true))
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null andThen filaContradictoria("att")
        val vm = vmConCobroDelPos("REQ-W13")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            vm.declararSinTarjeta(); runCurrent()
            coVerify(exactly = 0) { paymentAttemptLedger.aplicarLiberacionDelServidor(any(), any()) }
            assertThat((vm.state.value as AngelPayPaymentState.Error).message).contains("evidencia de cobro")
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `dos toques abren UN solo POST, con el guardado del dinero SUSPENDIDO el sondeo lee una liberacion vieja y NO la anuncia (el veto se enciende antes de suspender)`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        val puertaPost = kotlinx.coroutines.CompletableDeferred<Unit>()
        val puertaGuardado = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cuerpoConDinero = TerminalAttemptStatusResponse(success = true, attemptId = "att", requestId = "REQ-W14",
            attempt = TerminalAttemptResultDto(attemptId = "att", outcome = "RECORDED", paymentId = "pay-body", paymentStatus = "COMPLETED", recordedVia = "webhook", amountCents = 10000, tipCents = 0, isWinner = true, winnerPaymentId = "pay-body"))
        coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } coAnswers { puertaPost.await(); Response.success(cuerpoConDinero) }
        // El guardado del dinero se queda SUSPENDIDO hasta que la prueba lo suelta, y al final FALLA.
        coEvery { paymentAttemptLedger.aplicarVeredictoDelServidor(any()) } coAnswers { puertaGuardado.await(); Result.failure(IllegalStateException("room caída")) }
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null andThen filaLiberada("att", "NO_EVIDENCE_AFTER_WINDOW")   // null en la consulta inmediata; liberación VIEJA en la fila, que lee el sondeo
        val vm = vmConCobroDelPos("REQ-W14")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            vm.declararSinTarjeta(); runCurrent()
            vm.declararSinTarjeta(); runCurrent()   // doble toque
            coVerify(exactly = 1) { attemptApi.resolveNoInstrument(any(), any(), any()) }
            puertaPost.complete(Unit); runCurrent()   // el POST contestó CON dinero: el veto ya está encendido, el guardado sigue suspendido
            advanceTimeBy(vm.msEntreConsultasS6 + 100); runCurrent()   // el sondeo lee la liberación vieja mientras el guardado está suspendido
            assertThat(vm.state.value).isNotInstanceOf(AngelPayPaymentState.Idle::class.java)
            assertThat((vm.state.value as? AngelPayPaymentState.Error)?.message ?: "").doesNotContain("volver a cobrar")
            puertaGuardado.complete(Unit); runCurrent()   // el guardado FALLA
            val s = vm.state.value as AngelPayPaymentState.Error
            assertThat(s.message).contains("evidencia de cobro")
            vm.declararSinTarjeta(); runCurrent()   // con el veto encendido no se declara nada
            coVerify(exactly = 1) { attemptApi.resolveNoInstrument(any(), any(), any()) }
            coVerify(exactly = 0) { paymentAttemptLedger.aplicarLiberacionDelServidor(any(), any()) }
            advanceTimeBy(vm.msMostrarLiberada + 100); runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)   // sin reset automático
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `un POST sin dinero pendiente, llega S5 (registrado=false) y despues la respuesta — contradiccion, sin liberar`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null
        val ids = mutableListOf<String>()
        coEvery { paymentAttemptLedger.markIndeterminate(capture(ids), any()) } returns Unit
        val puertaPost = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } coAnswers { puertaPost.await(); respuestaDeclaracionOk() }   // sin dinero
        val vm = vmConCobroDelPos("REQ-W16")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            val mio = ids.last()
            vm.declararSinTarjeta(); runCurrent()   // POST en vuelo
            vm.manejarConfirmacionDelServidor(SocketEvent.TerminalPaymentConfirmed("REQ-W16", mio, "pay-s5", 10000, 0, registrado = false)); runCurrent()
            assertThat((vm.state.value as AngelPayPaymentState.Error).message).contains("evidencia de cobro")
            puertaPost.complete(Unit); runCurrent()   // la respuesta SIN dinero llega con el veto encendido
            coVerify(exactly = 0) { paymentAttemptLedger.aplicarLiberacionDelServidor(any(), any()) }
            assertThat((vm.state.value as AngelPayPaymentState.Error).message).doesNotContain("volver a cobrar")
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `S5 (registrado=false) sobre una liberacion YA mostrada la desmiente y apaga el reset automatico`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null andThen filaLiberada("att", "NO_EVIDENCE_AFTER_WINDOW")   // null en la consulta inmediata; liberada en el sondeo
        val ids = mutableListOf<String>()
        coEvery { paymentAttemptLedger.markIndeterminate(capture(ids), any()) } returns Unit
        val vm = vmConCobroDelPos("REQ-W17")
        try {
            // `primeSdkLaunch()` NO enciende `authorizationWasLaunched`; el SDK real sí (`onIntentLaunched`). Sin esa precondición
            // la guarda de `resetPayment()` no aplica y la prueba no demostraría la retención (Codex, Task 0 R7).
            vm.onIntentLaunched(); runCurrent()
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            val mio = ids.last()
            advanceTimeBy(vm.msEntreConsultasS6 + 100); runCurrent()
            assertThat((vm.state.value as AngelPayPaymentState.Error).message).contains("volver a cobrar")   // liberación mostrada
            vm.manejarConfirmacionDelServidor(SocketEvent.TerminalPaymentConfirmed("REQ-W17", mio, "pay-s5", 10000, 0, registrado = false)); runCurrent()
            val contradiccion = vm.state.value as AngelPayPaymentState.Error
            assertThat(contradiccion.message).contains("evidencia de cobro")
            advanceTimeBy(vm.msMostrarLiberada + 100); runCurrent()
            assertThat(vm.state.value).isSameInstanceAs(contradiccion)   // el reset de la liberación NO dispara
            // Un reset MANUAL (Salir / flecha) tampoco la borra: la contradicción revocó el «negativo confirmado» y la guarda retiene.
            vm.resetPayment(); runCurrent()
            assertThat(vm.state.value).isSameInstanceAs(contradiccion)
            vm.declararSinTarjeta(); runCurrent()
            coVerify(exactly = 0) { attemptApi.resolveNoInstrument(any(), any(), any()) }   // el veto sobrevivió al reset rechazado
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `S5 (registrado=true) sobre una liberacion YA mostrada la desmiente con Success — gana el dinero`() = runTest(testDispatcher) {
        // Sin esto, la pantalla se quedaría diciendo «se puede volver a cobrar» con el veto encendido (el reset automático
        // se apaga por el veto, pero nadie retiraba el texto): Success con el Payment del servidor, como cualquier S5.
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null andThen filaLiberada("att", "NO_EVIDENCE_AFTER_WINDOW")   // null en la consulta inmediata; liberada en el sondeo
        val ids = mutableListOf<String>()
        coEvery { paymentAttemptLedger.markIndeterminate(capture(ids), any()) } returns Unit
        val vm = vmConCobroDelPos("REQ-W19")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            val mio = ids.last()
            advanceTimeBy(vm.msEntreConsultasS6 + 100); runCurrent()
            assertThat((vm.state.value as AngelPayPaymentState.Error).message).contains("volver a cobrar")   // liberación mostrada
            vm.manejarConfirmacionDelServidor(SocketEvent.TerminalPaymentConfirmed("REQ-W19", mio, "pay-s5", 10000, 0, registrado = true)); runCurrent()
            val exito = vm.state.value as AngelPayPaymentState.Success
            assertThat(exito.receipt?.paymentId).isEqualTo("pay-s5")
            advanceTimeBy(vm.msMostrarLiberada + 100); runCurrent()
            assertThat(vm.state.value).isSameInstanceAs(exito)   // el reset de la liberación NO dispara sobre el Success
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `S5 con liga del recibo pinta el Success CON la liga — el ticket de la terminal sale con QR sin esperar al REST`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null
        val ids = mutableListOf<String>()
        coEvery { paymentAttemptLedger.markIndeterminate(capture(ids), any()) } returns Unit
        val vm = vmConCobroDelPos("REQ-W20")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            val mio = ids.last()
            vm.manejarConfirmacionDelServidor(SocketEvent.TerminalPaymentConfirmed("REQ-W20", mio, "pay-s5", 10000, 0, registrado = true,
                receiptUrl = "https://dashboard.avoqado.io/receipts/public/key-w20", receiptAccessKey = "key-w20")); runCurrent()
            val exito = vm.state.value as AngelPayPaymentState.Success
            assertThat(exito.receipt?.receiptUrl).isEqualTo("https://dashboard.avoqado.io/receipts/public/key-w20")
            assertThat(exito.receipt?.accessKey).isEqualTo("key-w20")
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `liberacion YA mostrada y luego el POST responde CON dinero — la liberacion se retira al instante, antes de guardar`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null andThen filaLiberada("att", "NO_EVIDENCE_AFTER_WINDOW")   // null en la consulta inmediata; liberada en el sondeo
        val puertaPost = kotlinx.coroutines.CompletableDeferred<Unit>()
        val puertaGuardado = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cuerpoConDinero = TerminalAttemptStatusResponse(success = true, attemptId = "att", requestId = "REQ-W18",
            attempt = TerminalAttemptResultDto(attemptId = "att", outcome = "RECORDED", paymentId = "pay-body", paymentStatus = "COMPLETED", recordedVia = "webhook", amountCents = 10000, tipCents = 0, isWinner = true, winnerPaymentId = "pay-body"))
        coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } coAnswers { puertaPost.await(); Response.success(cuerpoConDinero) }
        coEvery { paymentAttemptLedger.aplicarVeredictoDelServidor(any()) } coAnswers { puertaGuardado.await(); Result.failure(IllegalStateException("room caída")) }
        val vm = vmConCobroDelPos("REQ-W18")
        try {
            vm.onIntentLaunched(); runCurrent()
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            vm.declararSinTarjeta(); runCurrent()   // POST en vuelo
            advanceTimeBy(vm.msEntreConsultasS6 + 100); runCurrent()   // el sondeo lee la fila liberada y la anuncia
            assertThat((vm.state.value as AngelPayPaymentState.Error).message).contains("volver a cobrar")
            puertaPost.complete(Unit); runCurrent()   // el POST responde CON dinero; el guardado queda suspendido
            assertThat((vm.state.value as AngelPayPaymentState.Error).message).contains("evidencia de cobro")   // retirada AL INSTANTE
            advanceTimeBy(vm.msMostrarLiberada + 100); runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
            assertThat((vm.state.value as AngelPayPaymentState.Error).message).doesNotContain("volver a cobrar")
            puertaGuardado.complete(Unit); runCurrent()
            assertThat((vm.state.value as AngelPayPaymentState.Error).message).contains("evidencia de cobro")
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `un 2xx sin poder leer la fila despues NO muestra liberada — avisa y vuelve a consultar`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } returns respuestaDeclaracionOk()
        coEvery { paymentAttemptLedger.aplicarLiberacionDelServidor(any(), any()) } returns Result.failure(IllegalStateException("room caída"))
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null
        val vm = vmConCobroDelPos("REQ-W11")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            vm.declararSinTarjeta(); runCurrent()
            val s = vm.state.value as AngelPayPaymentState.ResultadoIncierto
            assertThat(s.error).contains("No se pudo confirmar en este aparato")
            assertThat(s.esperandoAlServidor).isTrue()
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `tras un 2xx de la declaracion, si la libreta ya tenia RECORDED gana el dinero y no se muestra liberada`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null andThen filaRegistrada("att")
        coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } returns respuestaDeclaracionOk()
        coEvery { paymentAttemptLedger.aplicarLiberacionDelServidor(any(), any()) } returns Result.success(false)  // el CAS local no transicionó
        val vm = vmConCobroDelPos("REQ-W9")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            vm.declararSinTarjeta(); runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Success::class.java)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `declarar sin tarjeta con sesion con permiso libera sin PIN`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null andThen filaLiberada("att", "OPERATOR_RECONCILED")   // null al entrar en espera; tras el CAS de la declaración la fila YA es la liberada
        coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } returns respuestaDeclaracionOk()
        coEvery { paymentAttemptLedger.aplicarLiberacionDelServidor(any(), any()) } returns Result.success(true)
        val vm = vmConCobroDelPos("REQ-W4")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            vm.declararSinTarjeta(); runCurrent()
            coVerify { attemptApi.resolveNoInstrument("v1", any(), match { it.supervisorPin == null && it.statement == "NO_INSTRUMENT_PRESENTED" && it.requestId == "REQ-W4" }) }
            coVerify { paymentAttemptLedger.aplicarLiberacionDelServidor(match { it.evidencia == "OPERATOR_RECONCILED" && it.requestId == "REQ-W4" }, any()) }
            assertThat((vm.state.value as AngelPayPaymentState.Error).canRetry).isFalse()
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `declarar sin permiso pide PIN y con el PIN vuelve a intentar con el MISMO resolutionId`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null andThen filaLiberada("att", "OPERATOR_RECONCILED")   // null al entrar en espera; tras el CAS de la declaración la fila YA es la liberada
        coEvery { attemptApi.resolveNoInstrument(any(), any(), match { it.supervisorPin == null }) } returns respuesta403()
        coEvery { attemptApi.resolveNoInstrument(any(), any(), match { it.supervisorPin == "1234" }) } returns respuestaDeclaracionOk()
        coEvery { paymentAttemptLedger.aplicarLiberacionDelServidor(any(), any()) } returns Result.success(true)
        val vm = vmConCobroDelPos("REQ-W5")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            vm.declararSinTarjeta(); runCurrent()
            assertThat((vm.state.value as AngelPayPaymentState.ResultadoIncierto).pidePin).isTrue()
            vm.declararSinTarjeta(pin = "1234"); runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
            val ids = mutableListOf<NoInstrumentResolutionRequest>()
            coVerify(exactly = 2) { attemptApi.resolveNoInstrument(any(), any(), capture(ids)) }
            assertThat(ids.map { it.resolutionId }.toSet()).hasSize(1)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `una declaracion rechazada con 409 no libera nada, muestra el codigo y vuelve a consultar`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null
        coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } returns respuesta409()
        val vm = vmConCobroDelPos("REQ-W6")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            vm.declararSinTarjeta(); runCurrent()
            val s = vm.state.value as AngelPayPaymentState.ResultadoIncierto
            assertThat(s.error).contains("RESOLUTION_CONFLICT")
            coVerify(exactly = 0) { paymentAttemptLedger.aplicarLiberacionDelServidor(any(), any()) }
            advanceTimeBy(vm.msEntreConsultasS6 + 100); runCurrent()
            coVerify(atLeast = 1) { ledgerServerRecovery.recoverOne("v1", any(), any(), any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `a los 45 s sin veredicto la pantalla deja de esperar pero conserva el boton de declarar y Consultar de nuevo`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null
        val vm = vmConCobroDelPos("REQ-W7")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            advanceTimeBy(vm.msEsperaAlServidor + vm.msEntreConsultasS6); runCurrent()
            val s = vm.state.value as AngelPayPaymentState.ResultadoIncierto
            assertThat(s.esperandoAlServidor).isFalse(); assertThat(s.puedeDeclarar).isTrue()
            vm.consultarDeNuevo(); runCurrent()
            assertThat((vm.state.value as AngelPayPaymentState.ResultadoIncierto).esperandoAlServidor).isTrue()
            // Consultar de nuevo NO vuelve a emitir `timeout` al POS: ese aviso salió UNA vez, al entrar en incierto.
            verify(exactly = 1) { socketManager.emitTerminalPaymentResult("REQ-W7", "timeout", any(), any(), any(), any(), any(), any(), outcomeEvidence = any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `P1 un cobro INICIADO EN LA TERMINAL (sin solicitud del POS) SI entra a la ventana y SI puede declarar`() = runTest(testDispatcher) {
        // 🔴 «Ninguna terminal muerta» (founder, 22-sep). Antes esta misma prueba fijaba lo contrario: un cobro local caía
        // en «NO vuelvas a cobrar, pregúntale al supervisor», sin reloj, sin botón y sin reintento, con la fila apartando
        // EL APARATO ENTERO — y la única salida medida fue escribir SQL en la base del aparato, tres veces. El servidor ya
        // contesta por INTENTO aunque no haya vínculo (pieza A) y acepta la declaración sin `requestId` (pieza B).
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        val vm = createViewModel()
        try {
            vm.initPayment(amount = "100.00"); runCurrent()
            vm.primeSdkLaunch(); runCurrent()
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            val s = vm.state.value as AngelPayPaymentState.ResultadoIncierto
            assertThat(s.esperandoAlServidor).isTrue(); assertThat(s.puedeDeclarar).isTrue()
            // Le PREGUNTA al servidor: es lo que convierte el callejón en una espera con desenlace.
            coVerify(atLeast = 1) { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) }

            vm.declararSinTarjeta(); runCurrent()
            // 🔴 Y el POST viaja SIN solicitud: `requestId` nulo, que Gson omite del cuerpo. Mandar uno inventado sería
            // declarar sobre una solicitud ajena.
            coVerify(exactly = 1) { attemptApi.resolveNoInstrument(eq("v1"), any(), match { it.requestId == null }) }
        } finally { vm.viewModelScope.cancel() }
    }

    // ----------------------------------------------------------------------
    // El respaldo SIN RED (founder, 22-sep: «los dos — con internet decide el servidor; si no contesta, el aparato»).
    // Se ofrece SÓLO para un cobro LOCAL, sólo si el servidor NO contestó en toda la ventana, sólo si la libreta dice
    // que la fila es declarable (sus cinco candados) y sólo a quien tiene el MISMO permiso que el camino por servidor
    // (`payments:resolve-no-instrument`, decisión de gerencia): sin eso, apagar el WiFi sería la forma de saltarse el PIN.
    // ----------------------------------------------------------------------

    private val permisoDeclarar = "payments:resolve-no-instrument"

    /**
     * Codex r7 (P2-1): el permiso sale de la última lista EFECTIVA guardada (la de `tpv/auth/permissions`), no de los permisos
     * CRUDOS del login. Para que ninguna prueba vuelva a pasar por el motivo equivocado, los crudos dicen SIEMPRE lo contrario.
     */
    private fun permisoEfectivo(conPermiso: Boolean, duenoDeLaLista: String = "v1|s1") {
        // Codex r8 (P1-3) / r9 (P2-8): la lista lleva su DUEÑO en el mismo valor (`venueId|staffId`, salto de línea, lista).
        // La sesión es v1|s1: una lista de otra persona no vale.
        every { secureStorage.getString("permissions_cache", null) } returns
            "$duenoDeLaLista\n" + (if (conPermiso) "payments:read,$permisoDeclarar" else "payments:read")
        every { secureStorage.getVenueId() } returns "v1"
        every { secureStorage.getStaffId() } returns "s1"
        every { authRepository.hasPermission(permisoDeclarar) } returns !conPermiso
    }

    /** Lo único que el ViewModel lee de la fila declarable es su id. */
    private fun filaLocalDeclarable(attemptId: String) = mockk<PaymentAttemptEntity>(relaxed = true).also {
        every { it.attemptId } returns attemptId
        every { it.terminalPaymentRequestId } returns null
        every { it.state } returns PaymentAttemptEntity.STATE_INDETERMINADO
    }

    /**
     * Un Pago rápido que quedó incierto y una ventana en la que el servidor contesta (o no) según [servidorContesto]. El id
     * del intento se captura de la consulta S6 —es el mismo que la libreta devolverá como declarable—.
     */
    private fun kotlinx.coroutines.test.TestScope.pagoRapidoInciertoTrasLaVentana(
        servidorContesto: Boolean,
        declarable: Boolean = true,
        conPermiso: Boolean = true,
        vm: AngelPayPaymentViewModel? = null,
        /** De quién es la lista de permisos guardada (`venueId|staffId`). La sesión es siempre v1|s1. */
        duenoDeLaLista: String = "v1|s1",
    ): Pair<AngelPayPaymentViewModel, io.mockk.CapturingSlot<String>> {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("sin red")
        val intento = slot<String>()
        coEvery { ledgerServerRecovery.recoverOne(any(), capture(intento), any(), any()) } returns LecturaDelIntento(null, servidorContesto = servidorContesto)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null
        coEvery { paymentAttemptLedger.retencionLocalDeclarable() } answers { if (declarable) filaLocalDeclarable(intento.captured) else null }
        permisoEfectivo(conPermiso, duenoDeLaLista)
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        val elVm = vm ?: createViewModel().also {
            it.initPayment(amount = "100.00"); runCurrent()
            it.primeSdkLaunch(); runCurrent()
        }
        elVm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
        // Mientras espera al servidor, el respaldo NUNCA se ofrece: con internet manda el servidor.
        assertThat((elVm.state.value as AngelPayPaymentState.ResultadoIncierto).puedeDeclararSinRed).isFalse()
        advanceTimeBy(elVm.msEsperaAlServidor + elVm.msEntreConsultasS6); runCurrent()
        return elVm to intento
    }

    @Test fun `P1 respaldo sin red - un Pago rapido al que el servidor NO contesto en toda la ventana ofrece cerrarlo en el aparato`() = runTest(testDispatcher) {
        val (vm, _) = pagoRapidoInciertoTrasLaVentana(servidorContesto = false)
        try {
            val s = vm.state.value as AngelPayPaymentState.ResultadoIncierto
            assertThat(s.esperandoAlServidor).isFalse()
            assertThat(s.puedeDeclararSinRed).isTrue()
            assertThat(s.message).contains("El servidor no contesta")
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `P1 respaldo sin red - si el servidor SI contesto (sin veredicto) NO se ofrece, y el texto no dice que no contesta`() = runTest(testDispatcher) {
        // Con el servidor vivo, la declaración va por él (bitácora del servidor, PIN de supervisor si hace falta). Ofrecer el
        // atajo del aparato ahí sería ofrecer una forma de declarar SIN la regla de gerencia con internet funcionando.
        val (vm, _) = pagoRapidoInciertoTrasLaVentana(servidorContesto = true)
        try {
            val s = vm.state.value as AngelPayPaymentState.ResultadoIncierto
            assertThat(s.puedeDeclararSinRed).isFalse()
            assertThat(s.puedeDeclarar).isTrue()
            assertThat(s.message).doesNotContain("no contesta")
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `P1 respaldo sin red - sin el permiso de gerencia NO se ofrece, y la pantalla dice a quien pedirselo`() = runTest(testDispatcher) {
        val (vm, _) = pagoRapidoInciertoTrasLaVentana(servidorContesto = false, conPermiso = false)
        try {
            val s = vm.state.value as AngelPayPaymentState.ResultadoIncierto
            assertThat(s.puedeDeclararSinRed).isFalse()
            assertThat(s.message).contains("gerente")
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `r7 P2-1 - un MANAGER con el permiso por su ROL y sin permisos crudos en el login SI puede cerrarlo sin red`() = runTest(testDispatcher) {
        // El caso de Codex: `StaffVenue.permissions = null` (lo normal) ⇒ los crudos del login no traen el permiso, pero la lista
        // efectiva que resolvió el servidor (rol + agregados − negados) sí. Antes el botón miraba los crudos y lo rechazaba.
        val (vm, _) = pagoRapidoInciertoTrasLaVentana(servidorContesto = false, conPermiso = true)
        try {
            verify(exactly = 0) { authRepository.hasPermission(permisoDeclarar) }
            val s = vm.state.value as AngelPayPaymentState.ResultadoIncierto
            assertThat(s.puedeDeclararSinRed).isTrue()
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `r8 P1-3 - con la lista del GERENTE A guardada y la sesion del cajero B, el respaldo sin red NO se ofrece ni se deja tocar`() = runTest(testDispatcher) {
        // Codex r8: A deja su lista, su sesión caduca, entra B y falla la descarga de SUS permisos. La lista de A seguía ahí
        // sin dueño y B cerraba el pendiente sin red con el permiso del gerente.
        val (vm, _) = pagoRapidoInciertoTrasLaVentana(servidorContesto = false, conPermiso = true, duenoDeLaLista = "v1|s-gerente-A")
        try {
            val s = vm.state.value as AngelPayPaymentState.ResultadoIncierto
            assertThat(s.puedeDeclararSinRed).isFalse()
            assertThat(s.message).contains("gerente")
            vm.declararSinRed(); runCurrent()
            coVerify(exactly = 0) { paymentAttemptLedger.declararSinCobroLocal(any(), any(), any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `r7 P2-1 - sin lista efectiva guardada NO se ofrece aunque los permisos crudos del login lo traigan`() = runTest(testDispatcher) {
        val (vm, _) = pagoRapidoInciertoTrasLaVentana(servidorContesto = false, conPermiso = true)
        try {
            every { secureStorage.getString("permissions_cache", null) } returns null
            every { authRepository.hasPermission(permisoDeclarar) } returns true
            vm.declararSinRed(); runCurrent()
            coVerify(exactly = 0) { paymentAttemptLedger.declararSinCobroLocal(any(), any(), any()) }
            assertThat((vm.state.value as AngelPayPaymentState.ResultadoIncierto).puedeDeclararSinRed).isFalse()
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `r7 P2-8 - con el servidor agotando cada consulta, la ventana dura lo que dice y no 5 minutos`() = runTest(testDispatcher) {
        // Codex: `transcurrido` sumaba sólo las pausas. Con cada consulta agotando su tope de 30 s, diez consultas + nueve pausas
        // eran 5 min 45 s mientras la pantalla contaba 45 — justo el caso del respaldo sin red, que hereda esa espera.
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("sin red")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } coAnswers {
            delay(30_000); LecturaDelIntento(null, servidorContesto = false)
        }
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null
        coEvery { paymentAttemptLedger.retencionLocalDeclarable() } returns null
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        val vm = createViewModel().also { it.initPayment(amount = "100.00"); runCurrent(); it.primeSdkLaunch(); runCurrent() }
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            assertThat((vm.state.value as AngelPayPaymentState.ResultadoIncierto).esperandoAlServidor).isTrue()
            advanceTimeBy(vm.msEsperaAlServidor + vm.msEntreConsultasS6 + 100); runCurrent()
            assertThat((vm.state.value as AngelPayPaymentState.ResultadoIncierto).esperandoAlServidor).isFalse()
        } finally { vm.viewModelScope.cancel() }
    }

    // ----------------------------------------------------------------------
    // 🔴 Ronda 20 (founder, 23-sep: «no debería trabarse nunca y todo es por webhook»). Un Pago rápido que quedó en duda
    // ya no espera 45 s y un botón: espera el aviso del banco 10 s (medido: p99 3.4 s, máximo 4.5 s) y le pide al servidor
    // liberarlo SOLO. El servidor decide si ese silencio vale (aviso comprobado del comercio) y sostiene el veto de dinero.
    // Un cobro del POS NO: ése lo libera la ventana de 30 s del propio servidor.
    // ----------------------------------------------------------------------

    private fun kotlinx.coroutines.test.TestScope.pagoRapidoEnDuda(): Pair<AngelPayPaymentViewModel, io.mockk.CapturingSlot<String>> {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("sin veredicto")
        val intento = slot<String>()
        coEvery { ledgerServerRecovery.recoverOne(any(), capture(intento), any(), any()) } returns LecturaDelIntento(null, servidorContesto = true)
        coEvery { paymentAttemptLedger.retencionLocalDeclarable() } returns null
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        val vm = createViewModel()
        vm.initPayment(amount = "100.00"); runCurrent()
        vm.primeSdkLaunch(); runCurrent()
        vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
        return vm to intento
    }

    @Test fun `R20 un Pago rapido en duda espera el aviso del banco 10 s y despues pide liberarlo SOLO, una vez`() = runTest(testDispatcher) {
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null
        coEvery { ledgerServerRecovery.liberarSinRastroDelBanco(any(), any(), any()) } returns LedgerServerRecovery.SinRastro.SIN_AVISO_COMPROBADO
        val (vm, intento) = pagoRapidoEnDuda()
        try {
            assertThat(vm.msEsperaLocal).isEqualTo(10_000L)
            advanceTimeBy(vm.msEsperaLocal - 1_000); runCurrent()
            coVerify(exactly = 0) { ledgerServerRecovery.liberarSinRastroDelBanco(any(), any(), any()) }   // antes de los 10 s, no
            advanceTimeBy(vm.msEntreConsultasS6 + 2_000); runCurrent()
            coVerify(exactly = 1) { ledgerServerRecovery.liberarSinRastroDelBanco("v1", intento.captured, any()) }
            // Ronda 21 (Codex r19, P2-1): la espera se cuenta desde que la pantalla la pone en duda, en el reloj monotónico.
            coVerifyOrder {
                paymentAttemptLedger.anotarEnDuda(intento.captured)
                ledgerServerRecovery.liberarSinRastroDelBanco("v1", intento.captured, any())
            }
            // Sin aviso comprobado: el cajero decide, y la pantalla dice POR QUÉ no se liberó sola.
            val s = vm.state.value as AngelPayPaymentState.ResultadoIncierto
            assertThat(s.esperandoAlServidor).isFalse()
            assertThat(s.puedeDeclarar).isTrue()
            assertThat(s.message).contains("aviso del banco")
            // «Consultar de nuevo» no vuelve a pedirla: el servidor ya contestó que ese silencio no prueba nada.
            vm.consultarDeNuevo(); runCurrent(); advanceTimeBy(vm.msEsperaLocal + vm.msEntreConsultasS6 + 100); runCurrent()
            coVerify(exactly = 1) { ledgerServerRecovery.liberarSinRastroDelBanco(any(), any(), any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `R20 si el servidor la libera sola, la pantalla dice que se puede volver a cobrar`() = runTest(testDispatcher) {
        var liberada = false
        coEvery { ledgerServerRecovery.liberarSinRastroDelBanco(any(), any(), any()) } coAnswers { liberada = true; LedgerServerRecovery.SinRastro.LIBERADA }
        coEvery { paymentAttemptLedger.leerIntento(any()) } answers { if (liberada) filaLiberada(firstArg(), "NO_EVIDENCE_AFTER_WINDOW") else null }
        val (vm, _) = pagoRapidoEnDuda()
        try {
            advanceTimeBy(vm.msEsperaLocal + 100); runCurrent()   // la liberación se pinta al vencer los 10 s…
            val s = vm.state.value as AngelPayPaymentState.Error
            assertThat(s.canRetry).isFalse()
            assertThat(s.message).contains("volver a cobrar")
            advanceTimeBy(vm.msMostrarLiberada + 100); runCurrent()   // …y a los 4 s la pantalla vuelve a cobrar
            assertThat(vm.state.value).isEqualTo(AngelPayPaymentState.Idle)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `R20 si al pedirla aparece dinero, gana el dinero — nunca se puede volver a cobrar`() = runTest(testDispatcher) {
        coEvery { ledgerServerRecovery.liberarSinRastroDelBanco(any(), any(), any()) } returns LedgerServerRecovery.SinRastro.CON_EVIDENCIA
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null
        val (vm, _) = pagoRapidoEnDuda()
        try {
            advanceTimeBy(vm.msEsperaLocal + vm.msEntreConsultasS6 + 100); runCurrent()
            val estado = vm.state.value
            assertWithMessage("estado=%s", estado).that(estado is AngelPayPaymentState.Error && (estado as AngelPayPaymentState.Error).message.contains("NO lo vuelvas a cobrar")).isTrue()
        } finally { vm.viewModelScope.cancel() }
    }

    // ── Ronda 23 · Codex r21 ──

    @Test fun `R23 una fila REGISTRADO con veto del servidor ensena la contradiccion, no cobrado`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null, servidorContesto = true)
        val registradaConVeto = filaRegistrada("att").also { every { it.serverVeto } returns PaymentAttemptEntity.VETO_EVIDENCE_CONTRADICTION }
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns registradaConVeto
        val vm = vmConCobroDelPos("REQ-R23")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            val s = vm.state.value
            assertWithMessage("estado=%s", s).that(s is AngelPayPaymentState.Error && s.message.contains("evidencia de cobro")).isTrue()
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `R23 la declaracion del cajero rechazada con POSITIVE_EVIDENCE_EXISTS veta desde ahi y deja la marca durable`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)   // S6 sin red
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null
        coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } returns Response.error(
            409, """{"success":false,"code":"POSITIVE_EVIDENCE_EXISTS"}""".toResponseBody("application/json".toMediaType()),
        )
        val vm = vmConCobroDelPos("REQ-R23b")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            vm.declararSinTarjeta(); runCurrent()
            val s = vm.state.value
            assertWithMessage("estado=%s", s).that(s is AngelPayPaymentState.Error && s.message.contains("evidencia de cobro")).isTrue()
            coVerify(exactly = 1) { paymentAttemptLedger.marcarEvidenciaPositivaDelServidor(any(), any(), any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `R20 un cobro del POS NO se libera solo — sigue esperando la ventana del servidor`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null, servidorContesto = true)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null
        val vm = vmConCobroDelPos("REQ-R20")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            advanceTimeBy(vm.msEsperaLocal + vm.msEntreConsultasS6 + 100); runCurrent()
            assertThat((vm.state.value as AngelPayPaymentState.ResultadoIncierto).esperandoAlServidor).isTrue()   // 10 s no bastan: son 45
            advanceTimeBy(vm.msEsperaAlServidor + vm.msEntreConsultasS6); runCurrent()
            coVerify(exactly = 0) { ledgerServerRecovery.liberarSinRastroDelBanco(any(), any(), any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    // ── Codex r7 · P2-7: tras reiniciar, el Pago rápido pendiente que aparta el aparato se ADOPTA, no se deja sin salida ──

    private fun pendienteLocal(state: String = PaymentAttemptEntity.STATE_INDETERMINADO, requestId: String? = null) = PaymentAttemptEntity(
        attemptId = "a-pendiente", venueId = "v1", processor = "ANGELPAY", state = state,
        amountCents = 5_000, tipCents = 0, recordingRoute = "FAST", paymentContextJson = "{}",
        lastError = "AngelPay sin veredicto", terminalPaymentRequestId = requestId,
        createdAt = System.currentTimeMillis() - 12 * 60_000L, updatedAt = System.currentTimeMillis() - 12 * 60_000L,
    )

    /** Un Pago rápido NUEVO de $80 cuya reserva choca con la barrera de la libreta (otro cobro aparta el aparato). */
    private fun kotlinx.coroutines.test.TestScope.pagoRapidoQueChocaConLaBarrera(pendiente: PaymentAttemptEntity?): AngelPayPaymentViewModel {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        coEvery { paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) } returns false
        coEvery { paymentAttemptLedger.retencionDelAparato() } returns pendiente
        coEvery { paymentAttemptLedger.motivoDeLaBarrera(any(), any(), any()) } returns "LO QUE DICE LA LIBRETA"
        val vm = createViewModel()
        vm.initPayment(amount = "80.00"); runCurrent()
        vm.flujoSdkForzadoParaPruebas = true
        vm.startCardPayment(); runCurrent()
        return vm
    }

    @Test fun `r7 P2-7 - un Pago rapido nuevo que choca con un cobro LOCAL pendiente adopta ESE cobro y entra a su ventana`() = runTest(testDispatcher) {
        // Codex: A queda retenido y el proceso muere. Al volver, las restauraciones adoptan por solicitud del POS y A no tiene;
        // un cobro nuevo B choca con la barrera y la pantalla decía el importe de A… sin ninguna salida. Terminal muerta.
        val consultados = mutableListOf<String>()
        coEvery { ledgerServerRecovery.recoverOne(any(), capture(consultados), any(), any()) } returns LecturaDelIntento(null, servidorContesto = true)
        val vm = pagoRapidoQueChocaConLaBarrera(pendienteLocal())
        try {
            val s = vm.state.value as AngelPayPaymentState.ResultadoIncierto
            assertThat(s.message).contains("$50.00")          // nombra el cobro ANTERIOR, no el nuevo de $80
            assertThat(s.puedeDeclarar).isTrue()
            assertThat(vm.attemptIdForTest()).isEqualTo("a-pendiente")
            assertThat(consultados).contains("a-pendiente")
            coVerify(exactly = 0) { paymentAttemptLedger.markAuthorizing(any()) }   // el cobro nuevo nunca llegó al SDK

            coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } returns Response.success(
                TerminalAttemptStatusResponse(success = true, attemptId = "a-pendiente",
                    attempt = TerminalAttemptResultDto(attemptId = "a-pendiente", outcome = "NOT_RECORDED")),
            )
            vm.declararSinTarjeta(); runCurrent()
            coVerify(exactly = 1) { attemptApi.resolveNoInstrument("v1", "a-pendiente", match { it.requestId == null }) }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `r7 P2-7 control - un pendiente en AUTORIZANDO (el SDK puede seguir dentro) NO se adopta, y la barrera se dice como siempre`() = runTest(testDispatcher) {
        val vm = pagoRapidoQueChocaConLaBarrera(pendienteLocal(state = PaymentAttemptEntity.STATE_AUTORIZANDO))
        try {
            assertThat((vm.state.value as AngelPayPaymentState.Error).message).isEqualTo("LO QUE DICE LA LIBRETA")
            assertThat(vm.attemptIdForTest()).isNotEqualTo("a-pendiente")
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `r7 P2-7 control - un pendiente DEL POS (con solicitud) no se adopta por aqui`() = runTest(testDispatcher) {
        val vm = pagoRapidoQueChocaConLaBarrera(pendienteLocal(requestId = "req-del-pos"))
        try {
            assertThat((vm.state.value as AngelPayPaymentState.Error).message).isEqualTo("LO QUE DICE LA LIBRETA")
            assertThat(vm.attemptIdForTest()).isNotEqualTo("a-pendiente")
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `P1 respaldo sin red - un cobro del POS NUNCA lo ofrece aunque la libreta lo permita`() = runTest(testDispatcher) {
        // La solicitud del POS tiene su camino, que además libera la ranura del servidor y avisa al POS.
        val (vm, _) = pagoRapidoInciertoTrasLaVentana(servidorContesto = false, vm = vmConCobroDelPos("REQ-SINRED"))
        try {
            assertThat((vm.state.value as AngelPayPaymentState.ResultadoIncierto).puedeDeclararSinRed).isFalse()
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `P1 respaldo sin red - declarar cierra la fila en el aparato SIN tocar la red y lo dice con honestidad`() = runTest(testDispatcher) {
        val (vm, intento) = pagoRapidoInciertoTrasLaVentana(servidorContesto = false)
        coEvery { paymentAttemptLedger.declararSinCobroLocal(any(), any(), any()) } returns true
        try {
            vm.declararSinRed(); runCurrent()
            coVerify(exactly = 1) { paymentAttemptLedger.declararSinCobroLocal(intento.captured, "v1", any()) }
            coVerify(exactly = 0) { attemptApi.resolveNoInstrument(any(), any(), any()) }   // el respaldo no depende de la red
            val s = vm.state.value as AngelPayPaymentState.Error
            assertThat(s.canRetry).isFalse()
            assertThat(s.message).contains("volver a cobrar")
            assertThat(s.message).contains("este aparato")
            assertThat(s.message).doesNotContain("30 s")   // no fue la ventana del servidor: fue el cajero
            advanceTimeBy(vm.msMostrarLiberada + 100); runCurrent()
            assertThat(vm.state.value).isEqualTo(AngelPayPaymentState.Idle)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `P1 respaldo sin red - si el CAS de la libreta lo rechaza NUNCA se dice liberado`() = runTest(testDispatcher) {
        val (vm, _) = pagoRapidoInciertoTrasLaVentana(servidorContesto = false)
        coEvery { paymentAttemptLedger.declararSinCobroLocal(any(), any(), any()) } returns false
        try {
            vm.declararSinRed(); runCurrent()
            val s = vm.state.value as AngelPayPaymentState.ResultadoIncierto
            assertThat(s.puedeDeclararSinRed).isFalse()
            assertThat(s.error).contains("No se pudo cerrar en el aparato")
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `P1 respaldo sin red - el permiso se revisa OTRA vez al tocar - sin el, la libreta ni se toca`() = runTest(testDispatcher) {
        // La oferta no es la garantía: entre pintar el botón y tocarlo puede cambiar la sesión.
        val (vm, _) = pagoRapidoInciertoTrasLaVentana(servidorContesto = false)
        permisoEfectivo(false)
        try {
            vm.declararSinRed(); runCurrent()
            coVerify(exactly = 0) { paymentAttemptLedger.declararSinCobroLocal(any(), any(), any()) }
            val s = vm.state.value as AngelPayPaymentState.ResultadoIncierto
            assertThat(s.puedeDeclararSinRed).isFalse()
            assertThat(s.error).contains("gerente")
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `S5 (registrado=false) sobre un ResultadoIncierto es contradiccion — prohibe recobrar y un declarar posterior no abre POST`() = runTest(testDispatcher) {
        // Antes de la Task 7 la pantalla «no afirmaba nada» y seguía en ResultadoIncierto; S5 es evidencia de DINERO del servidor,
        // la libreta lo haya promovido o no, así que ahora se dice y se veta la liberación de ESTE intento.
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("sin red")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null
        val ids = mutableListOf<String>()
        coEvery { paymentAttemptLedger.markIndeterminate(capture(ids), any()) } returns Unit
        val vm = vmConCobroDelPos("REQ-S5B")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            val mio = ids.last()
            vm.manejarConfirmacionDelServidor(SocketEvent.TerminalPaymentConfirmed("REQ-S5B", mio, "pay-s5", 10000, 0, registrado = false)); runCurrent()
            val s = vm.state.value as AngelPayPaymentState.Error
            assertThat(s.canRetry).isFalse(); assertThat(s.message).contains("evidencia de cobro"); assertThat(s.message).doesNotContain("volver a cobrar")
            vm.declararSinTarjeta(); runCurrent()
            coVerify(exactly = 0) { attemptApi.resolveNoInstrument(any(), any(), any()) }
            verify(exactly = 0) { socketManager.emitTerminalPaymentResult(any(), "failed", any(), any(), any(), any(), any(), any(), outcomeEvidence = any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    // ── Task 8 (certificación por sabotajes, 17-sep): cierran los NO CAE de S30 y S34, y el camino de S44 sin onIntentLaunched ──

    @Test fun `un 2xx con RECORDED cuyo guardado FALLA no afirma Success aunque la fila lea REGISTRADO — sin guardado, contradiccion`() = runTest(testDispatcher) {
        // Sabotaje S30 (ignorar `isSuccess` del guardado) NO caía: la prueba de la liberación vieja lee una fila DESCARTADA y el
        // `guardado &&` sólo decide cuando la fila lee REGISTRADO. Ésta lo fija: sin el veredicto guardado en ESTE aparato la
        // pantalla prohíbe recobrar (la libreta lo reaplicará por E2/N3) y nunca afirma el cobro.
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        val cuerpo = TerminalAttemptStatusResponse(success = true, attemptId = "att", requestId = "REQ-W27",
            attempt = TerminalAttemptResultDto(attemptId = "att", outcome = "RECORDED", paymentId = "pay-body", paymentStatus = "COMPLETED", recordedVia = "webhook", amountCents = 10000, tipCents = 0, isWinner = true, winnerPaymentId = "pay-body"))
        coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } returns Response.success(cuerpo)
        coEvery { paymentAttemptLedger.aplicarVeredictoDelServidor(any()) } returns Result.failure(IllegalStateException("room caída"))
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null andThen filaRegistrada("att")   // null durante la espera; REGISTRADO al releer tras el guardado fallido
        val vm = vmConCobroDelPos("REQ-W27")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            vm.declararSinTarjeta(); runCurrent()
            val s = vm.state.value as AngelPayPaymentState.Error
            assertThat(s.canRetry).isFalse(); assertThat(s.message).contains("evidencia de cobro")
            coVerify(exactly = 0) { paymentAttemptLedger.aplicarLiberacionDelServidor(any(), any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `el reset automatico de una liberacion es de ESA pantalla — tras Salir y un cobro NUEVO rechazado dentro de los 4 s, el Error del cobro nuevo se queda`() = runTest(testDispatcher) {
        // Sabotaje S34 (`is Error` en vez de identidad) NO caía: en la prueba de S5 el veto ya apaga el reset. Lo que sólo la
        // identidad guarda es esto: el cajero sale antes de los 4 s y arranca otro cobro que el banco rechaza; el reset diferido
        // de la liberación anterior no puede borrar ese rechazo en silencio.
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null andThen filaLiberada("att", "NO_EVIDENCE_AFTER_WINDOW")
        val vm = vmConCobroDelPos("REQ-W28")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            advanceTimeBy(vm.msEntreConsultasS6 + 100); runCurrent()
            assertThat((vm.state.value as AngelPayPaymentState.Error).message).contains("volver a cobrar")   // liberación mostrada: reset programado a 4 s
            vm.resetPayment(); runCurrent()   // Salir antes de los 4 s
            assertThat(vm.state.value).isEqualTo(AngelPayPaymentState.Idle)
            vm.initPayment(amount = "50.00"); runCurrent()
            vm.primeSdkLaunch(); runCurrent()
            vm.onAngelPaySdkResult(sdkFailureResult("G500")); runCurrent()   // el cobro NUEVO, rechazado por el banco
            val rechazo = vm.state.value as AngelPayPaymentState.Error
            advanceTimeBy(vm.msMostrarLiberada + 100); runCurrent()          // vence el reset de la liberación anterior
            assertThat(vm.state.value).isSameInstanceAs(rechazo)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `contradiccion por APPROVED sin Payment en un intento que NO paso por onIntentLaunched — Salir tampoco la borra (la guarda del veto)`() = runTest(testDispatcher) {
        // El sabotaje S44 (quitar la cláusula del veto de `resetPayment`) no caía en 4c86747: «fix3 P1» pasa por `onIntentLaunched`
        // y la guarda vieja (`authorizationWasLaunched && !confirmedNegativeOutcome`) ya retiene; desde el fix 4 lo cazan R8/R9 (veto
        // desde la fila / desde S6). Ésta recorre el camino que ninguna otra: el 2xx APPROVED sin Payment y «Salir» sin `onIntentLaunched`.
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null
        val cuerpoAprobado = TerminalAttemptStatusResponse(success = true, attemptId = "att", requestId = "REQ-W30",
            attempt = TerminalAttemptResultDto(attemptId = "att", outcome = "NOT_RECORDED", paymentId = null, processorEvidence = "APPROVED"))
        coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } returns Response.success(cuerpoAprobado)
        val vm = vmConCobroDelPos("REQ-W30")   // sin onIntentLaunched: `authorizationWasLaunched` queda en false
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            vm.declararSinTarjeta(); runCurrent()
            val contradiccion = vm.state.value as AngelPayPaymentState.Error
            assertThat(contradiccion.message).contains("evidencia de cobro")
            vm.resetPayment(); runCurrent()   // «Salir»
            assertThat(vm.state.value).isSameInstanceAs(contradiccion)   // sólo la cláusula del veto lo retiene
            vm.declararSinTarjeta(); runCurrent()
            coVerify(exactly = 1) { attemptApi.resolveNoInstrument(any(), any(), any()) }   // el veto sobrevivió al reset rechazado
        } finally { vm.viewModelScope.cancel() }
    }

    // ── Task 10 (QA en la Nexgo N86, 17-sep): tras liberar el cobro, la terminal SALE sola de la pantalla ─────────────
    //
    // Medido dos veces en hardware: la liberación se pintaba ~4 s y el reset automático dejaba el ViewModel en `Idle`, que para
    // `AngelPayPaymentScreen` con monto es «preparando el cobro» (el cargando) y cuyo auto-arranque no se vuelve a disparar
    // (sus llaves no cambian): la terminal se quedaba en el cargando para siempre. El ViewModel ahora pide UNA salida —evento
    // de una vez— sólo cuando ESE reset ocurrió; la pantalla la convierte en su «Regresar».

    /** Una pantalla que escucha la salida automática del ViewModel; `job.cancel()` la «desmonta». */
    private class PantallaQueEscucha(val salidas: MutableList<Unit>, val job: kotlinx.coroutines.Job)

    private fun kotlinx.coroutines.test.TestScope.pantallaQueEscucha(vm: AngelPayPaymentViewModel): PantallaQueEscucha {
        val salidas = mutableListOf<Unit>()
        val job = backgroundScope.launch { vm.salidaAutomatica.collect { salidas += it } }
        return PantallaQueEscucha(salidas, job)
    }

    /** Un cobro del POS cuya liberación por VENTANA ya está en pantalla («se puede volver a cobrar»); el reset vence en 4 s. */
    private fun kotlinx.coroutines.test.TestScope.vmConLiberacionPorVentana(requestId: String): AngelPayPaymentViewModel {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null andThen filaLiberada("att", "NO_EVIDENCE_AFTER_WINDOW")   // null en la consulta inmediata; liberada en el sondeo
        val vm = vmConCobroDelPos(requestId)
        vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
        advanceTimeBy(vm.msEntreConsultasS6 + 100); runCurrent()
        assertThat((vm.state.value as AngelPayPaymentState.Error).message).contains("volver a cobrar")
        return vm
    }

    private fun fijarCampo(vm: AngelPayPaymentViewModel, nombre: String, valor: Any?) {
        AngelPayPaymentViewModel::class.java.getDeclaredField(nombre).apply { isAccessible = true }.set(vm, valor)
    }

    @Test fun `Task 10 - la liberacion por ventana pide UNA salida de la pantalla, y sólo al vencer los 4 s`() = runTest(testDispatcher) {
        val vm = vmConLiberacionPorVentana("REQ-T10A")
        val pantalla = pantallaQueEscucha(vm)
        try {
            assertThat(pantalla.salidas).isEmpty()                          // la liberación se LEE primero: nada de salir al instante
            advanceTimeBy(vm.msMostrarLiberada - 200); runCurrent()
            assertThat(pantalla.salidas).isEmpty()                          // todavía no vencen los 4 s
            advanceTimeBy(300); runCurrent()
            assertThat(vm.state.value).isEqualTo(AngelPayPaymentState.Idle)
            assertThat(pantalla.salidas).hasSize(1)                         // …y al vencer, UNA salida
            advanceTimeBy(vm.msMostrarLiberada * 10); runCurrent()
            assertThat(pantalla.salidas).hasSize(1)                         // nunca otra
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `Task 10 - la declaracion del cajero (OPERATOR_RECONCILED) tambien pide UNA salida al vencer los 4 s`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null andThen filaLiberada("att", "OPERATOR_RECONCILED")   // null al entrar en espera; tras el CAS de la declaración, liberada
        coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } returns respuestaDeclaracionOk()
        coEvery { paymentAttemptLedger.aplicarLiberacionDelServidor(any(), any()) } returns Result.success(true)
        val vm = vmConCobroDelPos("REQ-T10B")
        val pantalla = pantallaQueEscucha(vm)
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            vm.declararSinTarjeta(); runCurrent()
            assertThat((vm.state.value as AngelPayPaymentState.Error).message).contains("no se presentó tarjeta")
            assertThat(pantalla.salidas).isEmpty()
            advanceTimeBy(vm.msMostrarLiberada + 100); runCurrent()
            assertThat(vm.state.value).isEqualTo(AngelPayPaymentState.Idle)
            assertThat(pantalla.salidas).hasSize(1)
            advanceTimeBy(vm.msEsperaAlServidor * 2); runCurrent()          // ni el sondeo (cancelado) ni nada más pide otra
            assertThat(pantalla.salidas).hasSize(1)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `Task 10 - si dentro de los 4 s llega S5 sin registro (contradiccion), no se pide salida y la contradiccion se queda`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null andThen filaLiberada("att", "NO_EVIDENCE_AFTER_WINDOW")
        val ids = mutableListOf<String>()
        coEvery { paymentAttemptLedger.markIndeterminate(capture(ids), any()) } returns Unit
        val vm = vmConCobroDelPos("REQ-T10C")
        val pantalla = pantallaQueEscucha(vm)
        try {
            vm.onIntentLaunched(); runCurrent()
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            val mio = ids.last()
            advanceTimeBy(vm.msEntreConsultasS6 + 100); runCurrent()
            assertThat((vm.state.value as AngelPayPaymentState.Error).message).contains("volver a cobrar")
            vm.manejarConfirmacionDelServidor(SocketEvent.TerminalPaymentConfirmed("REQ-T10C", mio, "pay-s5", 10000, 0, registrado = false)); runCurrent()
            val contradiccion = vm.state.value as AngelPayPaymentState.Error
            assertThat(contradiccion.message).contains("evidencia de cobro")
            advanceTimeBy(vm.msMostrarLiberada * 3); runCurrent()
            assertThat(vm.state.value).isSameInstanceAs(contradiccion)
            assertThat(pantalla.salidas).isEmpty()
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `Task 10 - si dentro de los 4 s llega S5 registrado (dinero), no se pide salida y el Success se queda`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null andThen filaLiberada("att", "NO_EVIDENCE_AFTER_WINDOW")
        val ids = mutableListOf<String>()
        coEvery { paymentAttemptLedger.markIndeterminate(capture(ids), any()) } returns Unit
        val vm = vmConCobroDelPos("REQ-T10D")
        val pantalla = pantallaQueEscucha(vm)
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            val mio = ids.last()
            advanceTimeBy(vm.msEntreConsultasS6 + 100); runCurrent()
            assertThat((vm.state.value as AngelPayPaymentState.Error).message).contains("volver a cobrar")
            vm.manejarConfirmacionDelServidor(SocketEvent.TerminalPaymentConfirmed("REQ-T10D", mio, "pay-s5", 10000, 0, registrado = true)); runCurrent()
            val exito = vm.state.value as AngelPayPaymentState.Success
            advanceTimeBy(vm.msMostrarLiberada * 3); runCurrent()
            assertThat(vm.state.value).isSameInstanceAs(exito)
            assertThat(pantalla.salidas).isEmpty()
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `Task 10 - si el reset automatico se niega, no se pide salida y la liberacion se queda con su Regresar`() = runTest(testDispatcher) {
        val vm = vmConLiberacionPorVentana("REQ-T10E")
        val pantalla = pantallaQueEscucha(vm)
        try {
            val liberacion = vm.state.value as AngelPayPaymentState.Error
            // Defensa en profundidad (hoy ningún camino de producción lo hace sin repintar): el «negativo confirmado» se revoca
            // con la pantalla intacta y sin veto. Identidad y veto dejan pasar al reset automático; la guarda de `resetPayment()`
            // («Error tras autorizar sin desenlace negativo») se niega.
            fijarCampo(vm, "authorizationWasLaunched", true)
            fijarCampo(vm, "confirmedNegativeOutcome", false)
            advanceTimeBy(vm.msMostrarLiberada + 100); runCurrent()
            assertThat(vm.state.value).isSameInstanceAs(liberacion)        // el reset se negó…
            assertThat(pantalla.salidas).isEmpty()                         // …y la pantalla NO sale
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `Task 10 - Salir a mano antes de los 4 s no deja pedida una segunda salida`() = runTest(testDispatcher) {
        val vm = vmConLiberacionPorVentana("REQ-T10F")
        val pantalla = pantallaQueEscucha(vm)
        try {
            vm.resetPayment(); runCurrent()                                // «Regresar»: la pantalla navega por su cuenta
            assertThat(vm.state.value).isEqualTo(AngelPayPaymentState.Idle)
            advanceTimeBy(vm.msMostrarLiberada + 100); runCurrent()
            assertThat(pantalla.salidas).isEmpty()                         // el reset diferido no reinició nada: no pide otra salida
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `Task 10 - la salida es de UNA vez - una pantalla recreada no la vuelve a recibir`() = runTest(testDispatcher) {
        val vm = vmConLiberacionPorVentana("REQ-T10G")
        val primera = pantallaQueEscucha(vm)
        try {
            advanceTimeBy(vm.msMostrarLiberada + 100); runCurrent()
            assertThat(primera.salidas).hasSize(1)
            primera.job.cancel()                                           // la pantalla se desmonta (rotación, recreación)
            val recreada = pantallaQueEscucha(vm)
            advanceTimeBy(vm.msMostrarLiberada * 10); runCurrent()
            assertThat(recreada.salidas).isEmpty()
            assertThat(primera.salidas).hasSize(1)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `Task 10 - si al pedir la salida nadie escucha, se entrega UNA vez a la pantalla que vuelve`() = runTest(testDispatcher) {
        val vm = vmConLiberacionPorVentana("REQ-T10H")                 // sin pantalla escuchando
        try {
            advanceTimeBy(vm.msMostrarLiberada + 100); runCurrent()
            assertThat(vm.state.value).isEqualTo(AngelPayPaymentState.Idle)
            val vuelve = pantallaQueEscucha(vm); runCurrent()
            assertThat(vuelve.salidas).hasSize(1)                          // no se perdió…
            vuelve.job.cancel()
            val otraVez = pantallaQueEscucha(vm); runCurrent()
            assertThat(otraVez.salidas).isEmpty()                          // …ni se repite
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `Task 10 - el colector de la pantalla convierte la salida en UNA llamada a su Regresar`() = runTest(testDispatcher) {
        val vm = vmConLiberacionPorVentana("REQ-T10I")
        var regresos = 0
        // La MISMA función que usa `AngelPayPaymentScreen` (su cableado lo fija `AngelPayPaymentScreenSalidaTest`).
        val colector = backgroundScope.launch { recogerSalidaAutomatica(vm.salidaAutomatica) { regresos++ } }
        try {
            advanceTimeBy(vm.msMostrarLiberada - 200); runCurrent()
            assertThat(regresos).isEqualTo(0)
            advanceTimeBy(300); runCurrent()
            assertThat(regresos).isEqualTo(1)
            advanceTimeBy(vm.msMostrarLiberada * 10); runCurrent()
            assertThat(regresos).isEqualTo(1)
        } finally { colector.cancel(); vm.viewModelScope.cancel() }
    }

    @Test fun `Task 10 hermano - Reintentar sin contexto (error antes de fijar el monto) sale de la pantalla en vez de quedarse cargando, y un doble toque no pide dos salidas`() = runTest(testDispatcher) {
        // `retryAfterError()` sin monto cacheado hace un reset COMPLETO → `Idle` → el mismo cargando eterno. Se alcanza en un cobro
        // LOCAL con un error de validación previo a fijar el monto (`Error.canRetry` es true por defecto); en un cobro del POS ese
        // error ya emitió `failed` y la guarda H.3 corta antes.
        every { authRepository.getVenueId() } returns null
        val vm = createViewModel()
        val pantalla = pantallaQueEscucha(vm)
        try {
            vm.setSocketPaymentSource(null, null)                          // lo que hace la pantalla en un cobro de la terminal
            vm.initPayment(amount = "100.00"); runCurrent()
            val error = vm.state.value as AngelPayPaymentState.Error
            assertThat(error.canRetry).isTrue()
            assertThat(pantalla.salidas).isEmpty()
            vm.retryAfterError(); runCurrent()
            assertThat(vm.state.value).isEqualTo(AngelPayPaymentState.Idle)
            assertThat(pantalla.salidas).hasSize(1)
            vm.retryAfterError(); runCurrent()                             // doble toque: ya estaba en Idle
            assertThat(pantalla.salidas).hasSize(1)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `Task 10 regresion - Idle sigue siendo el arranque legitimo - initPayment desde Idle avanza y no pide salida`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        val vm = createViewModel()
        val pantalla = pantallaQueEscucha(vm)
        try {
            assertThat(vm.state.value).isEqualTo(AngelPayPaymentState.Idle)   // el estado inicial ANTES de initPayment
            vm.setSocketPaymentSource("SOCKET", "REQ-T10J")
            vm.initPayment(amount = "100.00"); runCurrent()
            assertThat(vm.state.value).isNotEqualTo(AngelPayPaymentState.Idle)
            advanceTimeBy(vm.msMostrarLiberada * 10); runCurrent()
            assertThat(pantalla.salidas).isEmpty()
            verify(exactly = 0) { socketManager.emitTerminalPaymentResult(any(), any(), any(), any(), any(), any(), any(), any(), outcomeEvidence = any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    // ── Fix round 1 (revisión independiente de la Task 7) ─────────────────────────────────────────

    @Test fun `Minor 2 — una fila con PENDING_EVIDENCE (cualquier evidencia de dinero, no solo RECORDED) es contradiccion, prohibe recobrar y nunca ofrece declarar`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        val filaPendiente = mockk<PaymentAttemptEntity>(relaxed = true).also {
            every { it.attemptId } returns "att"
            every { it.state } returns PaymentAttemptEntity.STATE_INDETERMINADO
            every { it.serverOutcome } returns PaymentAttemptEntity.SERVER_PENDING_EVIDENCE   // SQL_CONTRADICCION lo cuenta como contradicción
            every { it.serverPaymentId } returns "pay-pendiente"
        }
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null andThen filaPendiente
        val vm = vmConCobroDelPos("REQ-W20")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            advanceTimeBy(vm.msEntreConsultasS6 + 100); runCurrent()
            val s = vm.state.value as AngelPayPaymentState.Error
            assertThat(s.canRetry).isFalse(); assertThat(s.message).contains("NO lo vuelvas a cobrar"); assertThat(s.message).doesNotContain("volver a cobrar")
            advanceTimeBy(vm.msEsperaAlServidor + vm.msEntreConsultasS6); runCurrent()
            assertThat(vm.state.value).isSameInstanceAs(s)   // ni vuelve a esperar ni ofrece la declaración a los 45 s
            vm.declararSinTarjeta(); runCurrent()
            coVerify(exactly = 0) { attemptApi.resolveNoInstrument(any(), any(), any()) }   // con evidencia de dinero no se declara nada
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `Minor 3 — un 403 que no es SUPERVISOR_AUTHORIZATION_REQUIRED no pide PIN, avisa con el codigo`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null
        coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } returns Response.error(403, """{"success":false,"code":"TERMINAL_IDENTITY_REQUIRED"}""".toResponseBody("application/json".toMediaType()))
        val vm = vmConCobroDelPos("REQ-W22")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            vm.declararSinTarjeta(); runCurrent()
            val s = vm.state.value as AngelPayPaymentState.ResultadoIncierto
            assertThat(s.pidePin).isFalse(); assertThat(s.error).contains("TERMINAL_IDENTITY_REQUIRED")
            // Un 403 sin cuerpo legible tampoco pide PIN: el aviso lleva el código HTTP.
            coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } returns Response.error(403, "".toResponseBody("application/json".toMediaType()))
            vm.declararSinTarjeta(); runCurrent()
            val s2 = vm.state.value as AngelPayPaymentState.ResultadoIncierto
            assertThat(s2.pidePin).isFalse(); assertThat(s2.error).contains("403")
            coVerify(exactly = 0) { paymentAttemptLedger.aplicarLiberacionDelServidor(any(), any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `Minor 4a — S5 (registrado=true) que llega mientras se consulta el historial deja Success, la espera al servidor no lo pisa`() = runTest(testDispatcher) {
        val puertaHistorial = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } coAnswers { puertaHistorial.await(); VerificacionDelCobro.NoSePudoVerificar("x") }
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null
        val ids = mutableListOf<String>()
        coEvery { paymentAttemptLedger.markIndeterminate(capture(ids), any()) } returns Unit
        val vm = vmConCobroDelPos("REQ-W21")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            assertThat((vm.state.value as AngelPayPaymentState.ResultadoIncierto).verificando).isTrue()
            val mio = ids.last()
            vm.manejarConfirmacionDelServidor(SocketEvent.TerminalPaymentConfirmed("REQ-W21", mio, "pay-s5", 10000, 0, registrado = true)); runCurrent()
            val exito = vm.state.value as AngelPayPaymentState.Success
            puertaHistorial.complete(Unit); runCurrent()   // el historial contesta «no se pudo verificar» DESPUÉS del S5
            assertThat(vm.state.value).isSameInstanceAs(exito)
            verify(exactly = 0) { socketManager.emitTerminalPaymentResult(any(), "timeout", any(), any(), any(), any(), any(), any(), outcomeEvidence = any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `Minor 4a — S5 (registrado=false) que llega mientras se consulta el historial deja la contradiccion, la espera al servidor no la pisa`() = runTest(testDispatcher) {
        val puertaHistorial = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } coAnswers { puertaHistorial.await(); VerificacionDelCobro.NoSePudoVerificar("x") }
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null
        val ids = mutableListOf<String>()
        coEvery { paymentAttemptLedger.markIndeterminate(capture(ids), any()) } returns Unit
        val vm = vmConCobroDelPos("REQ-W23")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            val mio = ids.last()
            vm.manejarConfirmacionDelServidor(SocketEvent.TerminalPaymentConfirmed("REQ-W23", mio, "pay-s5", 10000, 0, registrado = false)); runCurrent()
            val contradiccion = vm.state.value as AngelPayPaymentState.Error
            assertThat(contradiccion.message).contains("evidencia de cobro")
            puertaHistorial.complete(Unit); runCurrent()
            assertThat(vm.state.value).isSameInstanceAs(contradiccion)
            vm.declararSinTarjeta(); runCurrent()
            coVerify(exactly = 0) { attemptApi.resolveNoInstrument(any(), any(), any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `Minor 4b — Success por S5 tras una liberacion mostrada y luego S5 (registrado=false) tardio — el Success se queda`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null andThen filaLiberada("att", "NO_EVIDENCE_AFTER_WINDOW")
        val ids = mutableListOf<String>()
        coEvery { paymentAttemptLedger.markIndeterminate(capture(ids), any()) } returns Unit
        val vm = vmConCobroDelPos("REQ-W24")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            val mio = ids.last()
            advanceTimeBy(vm.msEntreConsultasS6 + 100); runCurrent()
            assertThat((vm.state.value as AngelPayPaymentState.Error).message).contains("volver a cobrar")   // liberación mostrada
            vm.manejarConfirmacionDelServidor(SocketEvent.TerminalPaymentConfirmed("REQ-W24", mio, "pay-s5", 10000, 0, registrado = true)); runCurrent()
            val exito = vm.state.value as AngelPayPaymentState.Success
            // Un S5 repetido/atrasado sin transición en la libreta NO puede convertir un Success en contradicción.
            vm.manejarConfirmacionDelServidor(SocketEvent.TerminalPaymentConfirmed("REQ-W24", mio, "pay-s5", 10000, 0, registrado = false)); runCurrent()
            assertThat(vm.state.value).isSameInstanceAs(exito)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `Minor 7a — mientras el POST de la declaracion vuela la pantalla lo dice (declarando) y al volver se apaga`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null
        val puertaPost = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } coAnswers { puertaPost.await(); respuesta403() }
        val vm = vmConCobroDelPos("REQ-W25")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            assertThat((vm.state.value as AngelPayPaymentState.ResultadoIncierto).declarando).isFalse()
            vm.declararSinTarjeta(); runCurrent()
            val enVuelo = vm.state.value as AngelPayPaymentState.ResultadoIncierto
            assertThat(enVuelo.declarando).isTrue(); assertThat(enVuelo.puedeDeclarar).isTrue()
            puertaPost.complete(Unit); runCurrent()
            val tras = vm.state.value as AngelPayPaymentState.ResultadoIncierto
            assertThat(tras.declarando).isFalse(); assertThat(tras.pidePin).isTrue()
        } finally { vm.viewModelScope.cancel() }
    }

    // ── Fix round 2 (Codex acotado sobre el diff de la TPV) ───────────────────────────────────────

    @Test fun `P1-2 — el sondeo trae evidencia positiva SIN registro (banco APPROVED, sin Payment) — contradiccion, veto, sin liberacion y sin declarar`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        // Consulta inmediata sin nada; el sondeo de los 5 s trae la evidencia positiva sin registro (S6 NOT_RECORDED + APPROVED).
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null) andThen LecturaDelIntento(null, evidenciaPositivaSinRegistro = true)
        val filaIndeterminada = mockk<PaymentAttemptEntity>(relaxed = true).also {
            every { it.attemptId } returns "att"
            every { it.state } returns PaymentAttemptEntity.STATE_INDETERMINADO
            every { it.serverOutcome } returns null   // NUNCA se marca RECORDED en local: no hay Payment
            every { it.serverPaymentId } returns null
        }
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns filaIndeterminada
        val vm = vmConCobroDelPos("REQ-W26")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            assertThat((vm.state.value as AngelPayPaymentState.ResultadoIncierto).puedeDeclarar).isTrue()
            advanceTimeBy(vm.msEntreConsultasS6 + 100); runCurrent()
            val s = vm.state.value as AngelPayPaymentState.Error
            assertThat(s.canRetry).isFalse(); assertThat(s.message).contains("NO lo vuelvas a cobrar"); assertThat(s.message).doesNotContain("volver a cobrar")
            coVerify(exactly = 0) { paymentAttemptLedger.aplicarLiberacionDelServidor(any(), any()) }
            coVerify(exactly = 0) { paymentAttemptLedger.aplicarVeredictoDelServidor(any()) }   // sin Payment no hay veredicto que guardar
            advanceTimeBy(vm.msEsperaAlServidor + vm.msEntreConsultasS6); runCurrent()
            assertThat(vm.state.value).isSameInstanceAs(s)   // no vuelve a esperar ni ofrece la declaración
            vm.declararSinTarjeta(); runCurrent()
            coVerify(exactly = 0) { attemptApi.resolveNoInstrument(any(), any(), any()) }   // veto encendido
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `P1-2 — un 2xx de la declaracion cuyo cuerpo trae processorEvidence APPROVED sin Payment es contradiccion, no liberacion`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null
        val cuerpo = TerminalAttemptStatusResponse(success = true, attemptId = "att", requestId = "REQ-W27",
            attempt = TerminalAttemptResultDto(attemptId = "att", outcome = "NOT_RECORDED", paymentId = null, processorEvidence = "APPROVED"),
            request = com.google.gson.JsonParser.parseString("""{"status":"FAILED","outcome":"NOT_CHARGED","outcomeEvidence":"OPERATOR_RECONCILED"}""").asJsonObject)
        coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } returns Response.success(cuerpo)
        val vm = vmConCobroDelPos("REQ-W27")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            vm.declararSinTarjeta(); runCurrent()
            val s = vm.state.value as AngelPayPaymentState.Error
            assertThat(s.canRetry).isFalse(); assertThat(s.message).contains("NO lo vuelvas a cobrar")
            coVerify(exactly = 0) { paymentAttemptLedger.aplicarLiberacionDelServidor(any(), any()) }
            vm.declararSinTarjeta(); runCurrent()
            coVerify(exactly = 1) { attemptApi.resolveNoInstrument(any(), any(), any()) }   // veto: no se vuelve a declarar
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `P2 — un 2xx atrasado de la declaracion (sin dinero) tras un S5 registrado=true conserva la MISMA instancia de Success`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null andThen filaRegistrada("att")   // tras el S5 la fila YA es REGISTRADO
        val ids = mutableListOf<String>()
        coEvery { paymentAttemptLedger.markIndeterminate(capture(ids), any()) } returns Unit
        val puertaPost = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } coAnswers { puertaPost.await(); respuestaDeclaracionOk() }   // sin dinero en el cuerpo
        val vm = vmConCobroDelPos("REQ-W28")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            val mio = ids.last()
            vm.declararSinTarjeta(); runCurrent()   // POST en vuelo
            vm.manejarConfirmacionDelServidor(SocketEvent.TerminalPaymentConfirmed("REQ-W28", mio, "pay-s5", 10000, 0, registrado = true)); runCurrent()
            val exito = vm.state.value as AngelPayPaymentState.Success
            puertaPost.complete(Unit); runCurrent()   // el 2xx atrasado, sin dinero, con el veto ya encendido
            assertThat(vm.state.value).isSameInstanceAs(exito)
            coVerify(exactly = 0) { paymentAttemptLedger.aplicarLiberacionDelServidor(any(), any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    // ── Fix round 4 (Codex, P1: la evidencia positiva del servidor vivía sólo en RAM) ───────────

    private fun filaConMarcaDurable(attemptId: String, state: String = PaymentAttemptEntity.STATE_INDETERMINADO) = mockk<PaymentAttemptEntity>(relaxed = true).also {
        every { it.attemptId } returns attemptId
        every { it.state } returns state
        every { it.serverOutcome } returns null
        every { it.serverPaymentId } returns null
        every { it.serverProcessorEvidence } returns PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED
    }
    private fun filaIndeterminadaSinMarca(attemptId: String) = mockk<PaymentAttemptEntity>(relaxed = true).also {
        every { it.attemptId } returns attemptId
        every { it.state } returns PaymentAttemptEntity.STATE_INDETERMINADO
        every { it.serverOutcome } returns null
        every { it.serverPaymentId } returns null
        every { it.serverProcessorEvidence } returns null
    }
    private fun cuerpoAprobadoSinPayment(requestId: String) = TerminalAttemptStatusResponse(success = true, attemptId = "att", requestId = requestId,
        attempt = TerminalAttemptResultDto(attemptId = "att", outcome = "NOT_RECORDED", paymentId = null, processorEvidence = "APPROVED"),
        request = com.google.gson.JsonParser.parseString("""{"status":"FAILED","outcome":"NOT_CHARGED","outcomeEvidence":"OPERATOR_RECONCILED"}""").asJsonObject)

    @Test fun `fix4 W3 - el 2xx de la declaracion con APPROVED sin Payment deja la evidencia DURABLE en la libreta`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null
        val ids = mutableListOf<String>()
        coEvery { paymentAttemptLedger.markIndeterminate(capture(ids), any()) } returns Unit
        coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } returns Response.success(cuerpoAprobadoSinPayment("REQ-W40"))
        val vm = vmConCobroDelPos("REQ-W40")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            val mio = ids.last()
            vm.declararSinTarjeta(); runCurrent()
            assertThat((vm.state.value as AngelPayPaymentState.Error).message).contains("evidencia de cobro")
            coVerify(exactly = 1) { paymentAttemptLedger.marcarEvidenciaPositivaDelServidor("v1", mio, any()) }
            coVerify(exactly = 0) { paymentAttemptLedger.aplicarLiberacionDelServidor(any(), any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `fix4 W3b - la escritura durable del 2xx ocurre aunque el veto RAM ya estuviera encendido por el sondeo`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        // Consulta inmediata sin nada; el sondeo de los 5 s trae la evidencia positiva sin registro (S6) y enciende el veto RAM.
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null) andThen LecturaDelIntento(null, evidenciaPositivaSinRegistro = true)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns filaIndeterminadaSinMarca("att")
        val puertaPost = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } coAnswers { puertaPost.await(); Response.success(cuerpoAprobadoSinPayment("REQ-W41")) }
        val vm = vmConCobroDelPos("REQ-W41")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            vm.declararSinTarjeta(); runCurrent()                       // POST en vuelo
            advanceTimeBy(vm.msEntreConsultasS6 + 100); runCurrent()    // el sondeo enciende el veto (evidencia sin registro)
            assertThat((vm.state.value as AngelPayPaymentState.Error).message).contains("evidencia de cobro")
            puertaPost.complete(Unit); runCurrent()                     // el 2xx APPROVED llega con el veto YA encendido
            coVerify(exactly = 1) { paymentAttemptLedger.marcarEvidenciaPositivaDelServidor("v1", any(), any()) }
            assertThat((vm.state.value as AngelPayPaymentState.Error).message).contains("evidencia de cobro")
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `fix4 R8 - la fila trae la marca durable sin evidencia nueva de S6 - contradiccion con veto, sin liberar y sin declarar, y el reset se rechaza`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)   // S6 sin red o sin nada nuevo
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns filaConMarcaDurable("att")
        val vm = vmConCobroDelPos("REQ-W42")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
            val s = vm.state.value as AngelPayPaymentState.Error
            assertThat(s.canRetry).isFalse(); assertThat(s.message).contains("NO lo vuelvas a cobrar")
            vm.declararSinTarjeta(); runCurrent()
            coVerify(exactly = 0) { attemptApi.resolveNoInstrument(any(), any(), any()) }
            coVerify(exactly = 0) { paymentAttemptLedger.aplicarLiberacionDelServidor(any(), any()) }
            advanceTimeBy(vm.msEsperaAlServidor + vm.msEntreConsultasS6); runCurrent()
            assertThat(vm.state.value).isSameInstanceAs(s)
            vm.resetPayment(); runCurrent()
            assertThat(vm.state.value).isSameInstanceAs(s)   // el veto restaurado desde la FILA también retiene el reset
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `fix4 R9 - un cancel ACCEPTED atrasado no pisa el veto - la pantalla sigue en contradiccion, no en Cancelado`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null, evidenciaPositivaSinRegistro = true)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns filaIndeterminadaSinMarca("att")
        val vm = vmConCobroDelPos("REQ-W43")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            val contradiccion = vm.state.value as AngelPayPaymentState.Error
            assertThat(contradiccion.message).contains("evidencia de cobro")
            vm.manejarCancelacionRemota(cancelDelPos("REQ-W43", "ACCEPTED")); runCurrent()
            assertThat(vm.state.value).isNotInstanceOf(AngelPayPaymentState.Cancelled::class.java)
            assertThat((vm.state.value as AngelPayPaymentState.Error).message).contains("evidencia de cobro")
            assertThat(vm.mensajeDelPos.value).isNotEqualTo(CobroRemotoDelPos.CANCELADO_POR_EL_POS)
            vm.resetPayment(); runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)   // veto vivo: el reset sigue rechazado
            vm.declararSinTarjeta(); runCurrent()
            coVerify(exactly = 0) { attemptApi.resolveNoInstrument(any(), any(), any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    // ── Fix round 5 (Codex r5 sobre el fix 4: P1-B contexto monetario · D5 adopción y sumideros) ──

    /** La fila REAL de la libreta que dejó el cobro remoto antes de morir el proceso: importe $120.50 + $10.00, venta o-77. */
    private fun filaDeLaSolicitudConMarca(requestId: String, attemptId: String = "att-rc1") = PaymentAttemptEntity(
        attemptId = attemptId, venueId = "v1", processor = "ANGELPAY", state = PaymentAttemptEntity.STATE_INDETERMINADO,
        amountCents = 12050, tipCents = 1000, recordingRoute = "ORDER",
        paymentContextJson = """{"venueId":"v1","staffId":"s1","shiftId":"sh-9","amount":120.50,"tip":10.00,"rating":5,"blumonSerialNumber":"","idempotencyKey":"$attemptId","terminalPaymentRequestId":"$requestId","authorizationCode":"","referenceNumber":"","orderId":"o-77","orderNumber":"77","isPortabilidad":false,"serialNumbers":[],"processorAffiliation":"AF-1"}""",
        lastError = "AngelPay U101", createdAt = 1L, updatedAt = 2L, terminalPaymentRequestId = requestId,
        serverProcessorEvidence = PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED, serverProcessorEvidenceAt = 3L,
    )
    /** El SavedStateHandle de un ViewModel que MURIÓ con un cobro del POS en vuelo (sólo estas llaves sobreviven). */
    private fun handleRecreado(requestId: String) = androidx.lifecycle.SavedStateHandle(mapOf(
        "angelpay_socket_payment_source" to "SOCKET", "angelpay_socket_request_id" to requestId,
        "angelpay_socket_binding_fixed" to true, "angelpay_socket_bound_request_id" to requestId,
    ))
    private fun kotlinx.coroutines.test.TestScope.vmRecreadoConEvidencia(requestId: String): AngelPayPaymentViewModel {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        coEvery { paymentAttemptLedger.intentoDeLaSolicitud(requestId) } returns filaDeLaSolicitudConMarca(requestId)
        coEvery { paymentAttemptLedger.leerIntento("att-rc1") } returns filaDeLaSolicitudConMarca(requestId)
        val vm = createViewModel(handleRecreado(requestId))
        vm.setSocketPaymentSource("SOCKET", requestId)   // la pantalla vuelve a etiquetar: mismo id ⇒ retorno temprano
        vm.initPayment("100.00"); runCurrent()            // …y arranca el cobro: la fila con evidencia restaura la contradicción
        assertThat((vm.state.value as AngelPayPaymentState.Error).message).contains("evidencia de cobro")
        return vm
    }

    @Test fun `fix5 P1-B - recreacion con evidencia y S5 registrado=true - Success con el importe, la propina y la venta de la FILA, no con cero`() = runTest(testDispatcher) {
        val vm = vmRecreadoConEvidencia("REQ-RC1")
        try {
            vm.manejarConfirmacionDelServidor(SocketEvent.TerminalPaymentConfirmed("REQ-RC1", "att-rc1", "pay-s5", 12050, 1000, registrado = true)); runCurrent()
            val exito = vm.state.value as AngelPayPaymentState.Success
            assertThat(exito.amount).isEqualTo("120.50")
            assertThat(exito.tipAmount).isEqualTo("10.00")
            assertThat(exito.orderId).isEqualTo("o-77")
            assertThat(exito.orderNumber).isEqualTo("77")
            assertThat(exito.receipt!!.amount).isEqualTo(java.math.BigDecimal("120.50"))
            assertThat(exito.receipt!!.tipAmount).isEqualTo(java.math.BigDecimal("10.00"))
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `fix5 P1-B - recreacion con evidencia y callback aprobado tardio - el registro lleva el importe y la venta originales (recorder de orden, no de cobro rapido)`() = runTest(testDispatcher) {
        val ctxSlot = slot<com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext>()
        coEvery { recordPaymentUseCase(capture(ctxSlot), any(), any(), any()) } returns Result.success(
            PaymentReceipt(paymentId = "pay-tardio", receiptUrl = "https://r/1", accessKey = "k", amount = java.math.BigDecimal("120.50"), tipAmount = java.math.BigDecimal("10.00")),
        )
        val vm = vmRecreadoConEvidencia("REQ-RC2")
        try {
            vm.onAngelPaySdkResult(approvedSdkResult("A9", "R9")); runCurrent()
            // El registro corre en `withContext(NonCancellable + IO)` (hilo real): se espera al ESTADO, como en P1-1.
            withContext(Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) { vm.state.first { it is AngelPayPaymentState.Success || it is AngelPayPaymentState.Error } }
            }
            val ctx = ctxSlot.captured as PaymentContext.AngelPayPayment
            assertThat(ctx.amount).isEqualTo(java.math.BigDecimal("120.50"))
            assertThat(ctx.tip).isEqualTo(java.math.BigDecimal("10.00"))
            assertThat(ctx.orderId).isEqualTo("o-77")          // ⇒ RecordPaymentUseCase elige el recorder de ORDEN
            assertThat(ctx.orderNumber).isEqualTo("77")
            assertThat(ctx.idempotencyKey).isEqualTo("att-rc1")
            assertThat(ctx.terminalPaymentRequestId).isEqualTo("REQ-RC2")
            assertThat(ctx.shiftId).isEqualTo("sh-9")
            assertThat(ctx.rating).isEqualTo(5)
            val exito = vm.state.value as AngelPayPaymentState.Success
            assertThat(exito.amount).isEqualTo("120.50"); assertThat(exito.orderId).isEqualTo("o-77")
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `r8 P2-6 - el cobro ADOPTADO se registra con SU cuenta de cobro, no con la que el cajero eligio para el cobro nuevo`() = runTest(testDispatcher) {
        // Codex r8: A quedó pendiente con la cuenta M1 (cma-001); el cajero preparó B con M2 (cma-002) y la pantalla adoptó A.
        // Si llega el aprobado rezagado de A, el registro llevaba la referencia y el importe de A… con la cuenta de B.
        merchantsFlow.value = listOf(angelPayMerchantB)   // la cuenta elegida AHORA es M2
        val requestId = "REQ-R8-M"
        val base = filaDeLaSolicitudConMarca(requestId)
        val filaConM1 = base.copy(paymentContextJson = base.paymentContextJson.replace("\"venueId\":\"v1\",", "\"venueId\":\"v1\",\"merchantAccountId\":\"cma-001\","))
        assertThat(filaConM1.paymentContextJson).contains("\"merchantAccountId\":\"cma-001\"")
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        coEvery { paymentAttemptLedger.intentoDeLaSolicitud(requestId) } returns filaConM1
        coEvery { paymentAttemptLedger.leerIntento("att-rc1") } returns filaConM1
        val ctxSlot = slot<com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext>()
        coEvery { recordPaymentUseCase(capture(ctxSlot), any(), any(), any()) } returns Result.success(
            PaymentReceipt(paymentId = "pay-tardio", receiptUrl = "https://r/1", accessKey = "k", amount = java.math.BigDecimal("120.50"), tipAmount = java.math.BigDecimal("10.00")),
        )
        val vm = createViewModel(handleRecreado(requestId))
        try {
            runCurrent()
            assertThat(vm.currentMerchant.value?.merchantAccountId).isEqualTo("cma-002")   // precondición: la cuenta elegida es M2
            vm.setSocketPaymentSource("SOCKET", requestId)
            vm.initPayment("100.00"); runCurrent()
            assertThat((vm.state.value as AngelPayPaymentState.Error).message).contains("evidencia de cobro")

            vm.onAngelPaySdkResult(approvedSdkResult("A9", "R9")); runCurrent()
            withContext(Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) { vm.state.first { it is AngelPayPaymentState.Success || it is AngelPayPaymentState.Error } }
            }
            val ctx = ctxSlot.captured as PaymentContext.AngelPayPayment
            assertThat(ctx.idempotencyKey).isEqualTo("att-rc1")
            assertThat(ctx.merchantAccountId).isEqualTo("cma-001")   // la cuenta con la que A viajó al SDK
        } finally { vm.viewModelScope.cancel() }
    }

    // ── Codex r9 ─────────────────────────────────────────────────────────────────────────────────

    private val parserAngelPay = com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayResultParser::class

    @Test fun `r9 P1-1 - app to app - un rechazo NORMAL del banco sobre un intento con el veto del worker es contradiccion, sin Reintentar`() = runTest(testDispatcher) {
        // El worker guardó `server_veto` mientras AngelPay tenía la pantalla; la libreta ya no deja cerrar ESE intento y la fila
        // lo delata. Sin mirarla, la pantalla ofrecía «Reintentar» (el veto en RAM estaba apagado).
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        io.mockk.mockkConstructor(parserAngelPay)
        every { anyConstructed<com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayResultParser>().parse(any(), any()) } returns
            com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayResult.Failure("Transacción rechazada", "G500", "GATEWAY")
        val vm = createViewModel()
        try {
            vm.initPayment(amount = "100.00"); runCurrent()
            // El intento de ESTA pantalla (initPayment ya lo acuñó): es el que el callback resuelve.
            val intento = vm.attemptIdForTest()!!
            coEvery { paymentAttemptLedger.markHostResponded(intento, false, any(), any(), any()) } returns false
            coEvery { paymentAttemptLedger.leerIntento(intento) } returns PaymentAttemptEntity(
                attemptId = intento, venueId = "v1", processor = "ANGELPAY", state = PaymentAttemptEntity.STATE_AUTORIZANDO,
                amountCents = 10_000, tipCents = 0, recordingRoute = "FAST", paymentContextJson = "{}", createdAt = 1L, updatedAt = 2L,
                serverVeto = PaymentAttemptEntity.VETO_PAYMENT_CONTRADICTION,
            )
            assertThat(vm.openLedgerAttemptAndMarkAuthorizing(intento)).isTrue()
            vm.onAngelPayResult(android.app.Activity.RESULT_OK, null); runCurrent()

            val s = vm.state.value as AngelPayPaymentState.Error
            assertThat(s.canRetry).isFalse()
            assertThat(s.message).contains("evidencia de cobro")
        } finally {
            vm.viewModelScope.cancel()
            io.mockk.unmockkConstructor(parserAngelPay)
        }
    }

    @Test fun `r9 P2-5 - app to app - el aprobado de un cobro ADOPTADO sin evidencia se registra con SU cuenta, no con la elegida ahora`() = runTest(testDispatcher) {
        // Codex r9: A quedó AUTORIZANDO con M1 (cma-001), sin evidencia del servidor; la pantalla murió y la nueva eligió M2.
        // Llega el aprobado app a app de A: la adopción tomaba sólo el número de intento, y el registro llevaba la cuenta de M2.
        merchantsFlow.value = listOf(angelPayMerchantB)
        val requestId = "REQ-R9-A2A"
        val base = filaDeLaSolicitudConMarca(requestId)
        val filaA = base.copy(
            state = PaymentAttemptEntity.STATE_AUTORIZANDO, serverProcessorEvidence = null, serverProcessorEvidenceAt = null,
            paymentContextJson = base.paymentContextJson.replace("\"venueId\":\"v1\",", "\"venueId\":\"v1\",\"merchantAccountId\":\"cma-001\","),
        )
        assertThat(filaA.paymentContextJson).contains("\"merchantAccountId\":\"cma-001\"")
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        coEvery { paymentAttemptLedger.adoptarCobroDeLaSolicitud(requestId) } returns com.jaac.avoqado_tpv.features.payment.data.ledger.CobroSinResolver("att-rc1")
        coEvery { paymentAttemptLedger.leerIntento("att-rc1") } returns filaA
        io.mockk.mockkConstructor(parserAngelPay)
        every { anyConstructed<com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayResultParser>().parse(any(), any()) } returns
            com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayResult.Success("A9", "R9", 13_050, "APROBADA", "S000", null)
        val ctxSlot = slot<com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext>()
        coEvery { recordPaymentUseCase(capture(ctxSlot), any(), any(), any()) } returns Result.success(
            PaymentReceipt(paymentId = "pay-a2a", receiptUrl = "https://r/1", accessKey = "k", amount = java.math.BigDecimal("120.50"), tipAmount = java.math.BigDecimal("10.00")),
        )
        val vm = createViewModel(handleRecreado(requestId))
        try {
            runCurrent()
            assertThat(vm.currentMerchant.value?.merchantAccountId).isEqualTo("cma-002")   // precondición: la cuenta elegida es M2

            vm.onAngelPayResult(android.app.Activity.RESULT_OK, null); runCurrent()
            withContext(Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) { vm.state.first { it is AngelPayPaymentState.Success || it is AngelPayPaymentState.Error } }
            }
            val ctx = ctxSlot.captured as PaymentContext.AngelPayPayment
            assertThat(ctx.idempotencyKey).isEqualTo("att-rc1")
            assertThat(ctx.merchantAccountId).isEqualTo("cma-001")   // la cuenta con la que A salió al banco
            assertThat(ctx.amount).isEqualTo(java.math.BigDecimal("120.50"))
        } finally {
            vm.viewModelScope.cancel()
            io.mockk.unmockkConstructor(parserAngelPay)
        }
    }

    @Test fun `r9 - adoptar A local, rechazo confirmado y Reintentar - el cobro NUEVO se registra con la cuenta elegida, no con la de A`() = runTest(testDispatcher) {
        // La secuencia que encontró Codex: `retryAfterError()` borra el número de intento y CONSERVA el contexto restaurado de A.
        // Sin comparar el número de intento, el cobro B —que salió con M2— se registraría con la cuenta de A (M1).
        merchantsFlow.value = listOf(angelPayMerchantB)
        activeMerchantIdFlow.value = 22   // la sesión del SDK está en M2: sin esto la alineación bloquea el cobro antes de la barrera
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null, servidorContesto = true)
        val ctxSlot = slot<com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext>()
        coEvery { recordPaymentUseCase(capture(ctxSlot), any(), any(), any()) } returns Result.success(
            PaymentReceipt(paymentId = "pay-b", receiptUrl = "https://r/1", accessKey = "k", amount = java.math.BigDecimal("50.00"), tipAmount = java.math.BigDecimal.ZERO),
        )
        val vm = pagoRapidoQueChocaConLaBarrera(pendienteLocal().copy(paymentContextJson = """{"venueId":"v1","merchantAccountId":"cma-001"}"""))
        try {
            assertThat(vm.attemptIdForTest()).isEqualTo("a-pendiente")
            vm.onAngelPaySdkResult(sdkFailureResult("G500", message = "Transacción rechazada por el gateway")); runCurrent()
            assertThat((vm.state.value as AngelPayPaymentState.Error).canRetry).isTrue()

            coEvery { paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) } returns true
            vm.retryAfterError(); runCurrent()
            vm.primeSdkLaunch(); runCurrent()
            assertThat(vm.attemptIdForTest()).isNotEqualTo("a-pendiente")   // B es un intento nuevo
            vm.onAngelPaySdkResult(approvedSdkResult("B9", "RB")); runCurrent()
            withContext(Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) { vm.state.first { it is AngelPayPaymentState.Success || it is AngelPayPaymentState.Error } }
            }
            val ctx = ctxSlot.captured as PaymentContext.AngelPayPayment
            assertThat(ctx.idempotencyKey).isNotEqualTo("a-pendiente")
            assertThat(ctx.merchantAccountId).isEqualTo("cma-002")
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `fix5 D5 - un ViewModel recreado adopta el intento con evidencia al recibir un callback VACIO - contradiccion con veto, nunca ResultadoIncierto ni declaracion`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        // Los SEIS parámetros: con cinco, la afiliación queda fija en null y el verificador sólo contesta a un intento sin afiliación.
        // Desde Codex r9 (P2-5) la adopción restaura la afiliación REAL de la fila (AF-1) y el mock relajado contestaba otra cosa.
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("offline")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.adoptarCobroDeLaSolicitud("REQ-AD1") } returns com.jaac.avoqado_tpv.features.payment.data.ledger.CobroSinResolver("att-rc1")
        coEvery { paymentAttemptLedger.leerIntento("att-rc1") } returns filaDeLaSolicitudConMarca("REQ-AD1")
        val vm = createViewModel(handleRecreado("REQ-AD1"))
        try {
            vm.onAngelPayResult(android.app.Activity.RESULT_CANCELED, null); runCurrent()   // «no me acuerdo»: adopta att-rc1 por identidad
            val s = vm.state.value as AngelPayPaymentState.Error
            assertThat(s.canRetry).isFalse(); assertThat(s.message).contains("evidencia de cobro")
            coVerify(exactly = 1) { paymentAttemptLedger.markIndeterminate("att-rc1", any()) }
            vm.declararSinTarjeta(); runCurrent()
            coVerify(exactly = 0) { attemptApi.resolveNoInstrument(any(), any(), any()) }
            verify(exactly = 0) { socketManager.emitTerminalPaymentResult(any(), "cancelled", any(), any(), any(), any(), any(), any(), outcomeEvidence = any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `fix5 D5 - sumidero del SDK embebido - un rechazo confirmado sobre un intento restaurado con evidencia pinta contradiccion, no Error con Reintentar`() = runTest(testDispatcher) {
        val vm = vmRecreadoConEvidencia("REQ-RC3")
        try {
            vm.onAngelPaySdkResult(sdkFailureResult("G500", message = "Transacción rechazada por el gateway")); runCurrent()
            val s = vm.state.value as AngelPayPaymentState.Error
            assertThat(s.canRetry).isFalse()
            assertThat(s.message).contains("evidencia de cobro")
            assertThat(s.message).doesNotContain("Reintenta")
            vm.resetPayment(); runCurrent()
            assertThat(vm.state.value).isSameInstanceAs(s)   // el veto sigue: ni «Salir» lo limpia
            verify(exactly = 0) { socketManager.emitTerminalPaymentResult(any(), "failed", any(), any(), any(), any(), any(), any(), outcomeEvidence = any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `S35 - contradiccion restaurada y rechazo del SDK embebido - al morir la pantalla no sale ningun negativo`() = runTest(testDispatcher) {
        // Sonda de S35 (Task 8, validada en la copia aislada; aplicada en la Task 10): el rechazo confirmado del SDK fija `confirmedNegativeOutcome = true` ANTES de que el
        // veto lo mande a `mostrarContradiccion()`, que lo REVOCA. La red de `onCleared` (`emitCancelledIfAbandoned`) no consulta el
        // veto: sin la revocación (S35) le pediría al socket un negativo sobre un intento con evidencia de dinero del servidor.
        val vm = vmRecreadoConEvidencia("REQ-RC9")
        try {
            vm.onAngelPaySdkResult(sdkFailureResult("G500", message = "Transacción rechazada por el gateway")); runCurrent()
            assertThat((vm.state.value as AngelPayPaymentState.Error).message).contains("evidencia de cobro")
            vm.emitCancelledIfAbandoned()   // lo que corre onCleared() cuando la pantalla muere
            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(
                    requestId = any(), status = any(), paymentId = any(), transactionId = any(),
                    cardDetails = any(), errorMessage = any(), receiptUrl = any(), receiptAccessKey = any(),
                    outcomeEvidence = any())
            }
        } finally { vm.viewModelScope.cancel() }
    }

    // ── Fix round 3 (Codex r2 sobre el fix 2: 1 P1 + 1 P2 en la pantalla) ───────────────────────

    @Test fun `fix3 P1 — liberacion visible y luego el 2xx trae APPROVED sin Payment — la liberacion se retira ANTES de leer Room, y Salir no borra el veto ni cancela la declaracion`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        val puertaPost = kotlinx.coroutines.CompletableDeferred<Unit>()
        val puertaLectura = kotlinx.coroutines.CompletableDeferred<Unit>()
        var lecturas = 0
        var leidaTrasLaPuerta = false
        // 1ª lectura (consulta inmediata): nada · 2ª (sondeo a los 5 s): la fila YA liberada por ventana · 3ª (dentro de la rama del
        // 2xx): SUSPENDIDA hasta que la prueba la suelta — es el intervalo en el que el cajero toca «Salir».
        coEvery { paymentAttemptLedger.leerIntento(any()) } coAnswers {
            lecturas++
            when {
                lecturas == 1 -> null
                lecturas == 2 -> filaLiberada("att", "NO_EVIDENCE_AFTER_WINDOW")
                else -> { puertaLectura.await(); leidaTrasLaPuerta = true; filaLiberada("att", "NO_EVIDENCE_AFTER_WINDOW") }
            }
        }
        val cuerpoAprobado = TerminalAttemptStatusResponse(success = true, attemptId = "att", requestId = "REQ-W29",
            attempt = TerminalAttemptResultDto(attemptId = "att", outcome = "NOT_RECORDED", paymentId = null, processorEvidence = "APPROVED"),
            request = com.google.gson.JsonParser.parseString("""{"status":"FAILED","outcome":"NOT_CHARGED","outcomeEvidence":"NO_EVIDENCE_AFTER_WINDOW"}""").asJsonObject)
        coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } coAnswers { puertaPost.await(); Response.success(cuerpoAprobado) }
        val vm = vmConCobroDelPos("REQ-W29")
        try {
            vm.onIntentLaunched(); runCurrent()   // el SDK real enciende `authorizationWasLaunched`; sin eso la guarda del reset no aplica
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            vm.declararSinTarjeta(); runCurrent()   // POST en vuelo
            advanceTimeBy(vm.msEntreConsultasS6 + 100); runCurrent()   // el sondeo lee la fila liberada y la anuncia
            assertThat((vm.state.value as AngelPayPaymentState.Error).message).contains("volver a cobrar")
            puertaPost.complete(Unit); runCurrent()   // el 2xx: banco APPROVED sin Payment; la lectura de Room queda suspendida
            val contradiccion = vm.state.value as AngelPayPaymentState.Error
            assertThat(contradiccion.message).contains("evidencia de cobro")   // retirada ANTES de suspender
            vm.resetPayment(); runCurrent()   // «Salir» en ese intervalo
            assertThat(vm.state.value).isSameInstanceAs(contradiccion)   // el reset se rechaza: el veto y la contradicción se quedan
            puertaLectura.complete(Unit); runCurrent()   // la declaración NO quedó cancelada: la lectura termina
            assertThat(leidaTrasLaPuerta).isTrue()
            val final = vm.state.value as AngelPayPaymentState.Error
            assertThat(final.message).contains("evidencia de cobro"); assertThat(final.canRetry).isFalse()
            coVerify(exactly = 0) { paymentAttemptLedger.aplicarLiberacionDelServidor(any(), any()) }
            vm.declararSinTarjeta(); runCurrent()
            coVerify(exactly = 1) { attemptApi.resolveNoInstrument(any(), any(), any()) }   // el veto sigue encendido para el intento
            advanceTimeBy(vm.msMostrarLiberada + 100); runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)   // el reset automático de la liberación tampoco dispara
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `fix3 P2a — con Success por S5, declararSinTarjeta conserva la MISMA instancia de Success y no abre POST`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        coEvery { paymentAttemptLedger.leerIntento(any()) } returns null
        val ids = mutableListOf<String>()
        coEvery { paymentAttemptLedger.markIndeterminate(capture(ids), any()) } returns Unit
        val vm = vmConCobroDelPos("REQ-W30")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            val mio = ids.last()
            vm.manejarConfirmacionDelServidor(SocketEvent.TerminalPaymentConfirmed("REQ-W30", mio, "pay-s5", 10000, 0, registrado = true)); runCurrent()
            val exito = vm.state.value as AngelPayPaymentState.Success
            vm.declararSinTarjeta(); runCurrent()
            assertThat(vm.state.value).isSameInstanceAs(exito)
            coVerify(exactly = 0) { attemptApi.resolveNoInstrument(any(), any(), any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `fix3 P2b — S5 registrado=true DURANTE la lectura suspendida de la declaracion — la lectura vuelve con una fila vieja y el Success se conserva`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        val puertaLectura = kotlinx.coroutines.CompletableDeferred<Unit>()
        var lecturas = 0
        // 1ª lectura (consulta inmediata): nada · 2ª (tras aplicar la liberación del 2xx): SUSPENDIDA; cuando vuelve trae la fila
        // VIEJA (liberada), anterior al S5 que llegó mientras tanto.
        coEvery { paymentAttemptLedger.leerIntento(any()) } coAnswers {
            lecturas++
            if (lecturas == 1) null else { puertaLectura.await(); filaLiberada("att", "OPERATOR_RECONCILED") }
        }
        coEvery { paymentAttemptLedger.aplicarLiberacionDelServidor(any(), any()) } returns Result.success(true)
        val ids = mutableListOf<String>()
        coEvery { paymentAttemptLedger.markIndeterminate(capture(ids), any()) } returns Unit
        coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } returns respuestaDeclaracionOk()   // 2xx sin dinero, veto apagado
        val vm = vmConCobroDelPos("REQ-W31")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            val mio = ids.last()
            vm.declararSinTarjeta(); runCurrent()   // el 2xx ya volvió; la lectura de Room está suspendida
            vm.manejarConfirmacionDelServidor(SocketEvent.TerminalPaymentConfirmed("REQ-W31", mio, "pay-s5", 10000, 0, registrado = true)); runCurrent()
            val exito = vm.state.value as AngelPayPaymentState.Success
            puertaLectura.complete(Unit); runCurrent()   // la fila vieja (liberada) NO puede desmentir el dinero ya pintado
            assertThat(vm.state.value).isSameInstanceAs(exito)
        } finally { vm.viewModelScope.cancel() }
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

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // Checkpoint 2 · N1 (diseño v3, D4): la decisión del vínculo intento→solicitud ANTES del SDK
    // ══════════════════════════════════════════════════════════════════════════════════════════

    private fun vmRemotoConVinculo(decision: com.jaac.avoqado_tpv.core.remotepayment.DecisionDelVinculo, capacidad: Int = 1): AngelPayPaymentViewModel {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        coEvery { paymentAttemptLedger.capacidadDeVinculo("REQ-N1") } returns capacidad
        coEvery { socketManager.emitAttemptOpened("REQ-N1", any()) } returns decision
        val vm = createViewModel()
        vm.initPayment("100.00")
        vm.setSocketPaymentSource("SOCKET", "REQ-N1")
        return vm
    }

    @Test
    fun `N1 con ACK autorizador se anuncia el intento DESPUES de la fila durable y ANTES de AUTORIZANDO, y se cobra`() = runTest(testDispatcher) {
        val orden = mutableListOf<String>()
        coEvery { paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) } answers { orden += "openAttempt"; true }
        coEvery { paymentAttemptLedger.markAuthorizing("attempt-N1") } answers { orden += "markAuthorizing"; true }
        val vm = vmRemotoConVinculo(com.jaac.avoqado_tpv.core.remotepayment.DecisionDelVinculo.Cobrar)
        // DESPUÉS del helper: su stub genérico de `emitAttemptOpened("REQ-N1", any())` taparía a éste, que es el que anota el orden.
        coEvery { socketManager.emitAttemptOpened("REQ-N1", "attempt-N1") } answers { orden += "emit"; com.jaac.avoqado_tpv.core.remotepayment.DecisionDelVinculo.Cobrar }
        try {
            runCurrent()
            assertThat(vm.openLedgerAttemptAndMarkAuthorizing("attempt-N1")).isTrue()
            assertThat(orden).containsExactly("openAttempt", "emit", "markAuthorizing").inOrder()
            // El fallback con la MISMA llave ya autorizada no vuelve a esperar.
            assertThat(vm.openLedgerAttemptAndMarkAuthorizing("attempt-N1")).isTrue()
            coVerify(exactly = 1) { socketManager.emitAttemptOpened("REQ-N1", "attempt-N1") }
            coVerify(exactly = 0) { paymentAttemptLedger.markDiscardedBeforeCharge(any(), any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun `N1 un ACK autorizador que llega DESPUES de que la pantalla dejo de esperar (reset) no cobra`() = runTest(testDispatcher) {
        // Codex (código, precisión de N1): la espera vive en la corrutina del cobro, no en un Job que `resetPayment()` cancele.
        val ack = kotlinx.coroutines.CompletableDeferred<com.jaac.avoqado_tpv.core.remotepayment.DecisionDelVinculo>()
        coEvery { paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) } returns true
        coEvery { paymentAttemptLedger.markAuthorizing(any()) } returns true
        val vm = vmRemotoConVinculo(com.jaac.avoqado_tpv.core.remotepayment.DecisionDelVinculo.Cobrar)
        coEvery { socketManager.emitAttemptOpened("REQ-N1", "attempt-tardio") } coAnswers { ack.await() }
        try {
            runCurrent()
            var resultado: Boolean? = null
            val espera = launch { resultado = vm.openLedgerAttemptAndMarkAuthorizing("attempt-tardio") }
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.LinkingAttempt::class.java)
            vm.resetPayment() // el cajero empezó otra cosa mientras el servidor no contestaba
            runCurrent()
            ack.complete(com.jaac.avoqado_tpv.core.remotepayment.DecisionDelVinculo.Cobrar)
            runCurrent()
            espera.join()
            assertThat(resultado).isFalse()
            coVerify(exactly = 0) { paymentAttemptLedger.markAuthorizing(any()) }
            assertThat(vm.state.value).isNotInstanceOf(AngelPayPaymentState.LinkingAttempt::class.java)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun `N1 sin capacidad del servidor no se anuncia nada y se cobra como hoy`() = runTest(testDispatcher) {
        val vm = vmRemotoConVinculo(com.jaac.avoqado_tpv.core.remotepayment.DecisionDelVinculo.NoEsDuena, capacidad = 0)
        try {
            runCurrent()
            assertThat(vm.openLedgerAttemptAndMarkAuthorizing("attempt-N1")).isTrue()
            coVerify(exactly = 0) { socketManager.emitAttemptOpened(any(), any()) }
            coVerify(exactly = 1) { paymentAttemptLedger.markAuthorizing("attempt-N1") }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun `N1 un cobro LOCAL nunca anuncia el intento aunque el servidor tenga capacidad`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        coEvery { paymentAttemptLedger.capacidadDeVinculo(any()) } returns 1
        val vm = createViewModel()
        try {
            vm.initPayment("100.00")
            runCurrent()
            assertThat(vm.openLedgerAttemptAndMarkAuthorizing("attempt-local")).isTrue()
            coVerify(exactly = 0) { socketManager.emitAttemptOpened(any(), any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun `N1 el camino legacy (ERROR, malformado o sin ACK) cobra sin vinculo y NO descarta`() = runTest(testDispatcher) {
        val vm = vmRemotoConVinculo(com.jaac.avoqado_tpv.core.remotepayment.DecisionDelVinculo.Legacy("sin ACK"))
        try {
            runCurrent()
            assertThat(vm.openLedgerAttemptAndMarkAuthorizing("attempt-N1")).isTrue()
            coVerify(exactly = 1) { paymentAttemptLedger.markAuthorizing("attempt-N1") }
            coVerify(exactly = 0) { paymentAttemptLedger.markDiscardedBeforeCharge(any(), any()) }
            coVerify(exactly = 0) { socketManager.emitTerminalPaymentResult(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            // Tras un timeout el mismo intento vuelve a anunciarse en un relanzamiento (segunda oportunidad de vincular).
            assertThat(vm.openLedgerAttemptAndMarkAuthorizing("attempt-N1")).isTrue()
            coVerify(exactly = 2) { socketManager.emitAttemptOpened("REQ-N1", "attempt-N1") }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun `N1 NOT_OWNER nunca toca el SDK descarta la PREPARANDO emite failed PRE_AUTHORIZATION y lo dice`() = runTest(testDispatcher) {
        val vm = vmRemotoConVinculo(com.jaac.avoqado_tpv.core.remotepayment.DecisionDelVinculo.NoEsDuena)
        try {
            runCurrent()
            assertThat(vm.openLedgerAttemptAndMarkAuthorizing("attempt-N1")).isFalse()
            coVerify(exactly = 0) { paymentAttemptLedger.markAuthorizing(any()) }
            coVerify(exactly = 1) { paymentAttemptLedger.markDiscardedBeforeCharge("attempt-N1", "NOT_OWNER") }
            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "REQ-N1", status = "failed", paymentId = null, transactionId = null, cardDetails = null,
                    errorMessage = any(), receiptUrl = null, receiptAccessKey = null, outcomeEvidence = "PRE_AUTHORIZATION",
                )
            }
            val estado = vm.state.value
            assertThat(estado).isInstanceOf(AngelPayPaymentState.Error::class.java)
            assertThat((estado as AngelPayPaymentState.Error).message).isEqualTo(CobroRemotoDelPos.NO_ES_LA_DUENA)
            assertThat(estado.canRetry).isFalse()
            // El desenlace ya salió: un reintento en esta pantalla queda bloqueado (H.3).
            vm.retryAfterError()
            assertThat((vm.state.value as AngelPayPaymentState.Error).message).isEqualTo(CobroRemotoDelPos.SOLICITUD_CERRADA)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun `N1 executionAuthorized false no cobra y el negativo es cancelled si el POS pidio cancelar y failed en los demas`() = runTest(testDispatcher) {
        val cancelRequested = com.jaac.avoqado_tpv.core.remotepayment.DecisionDelVinculo.NoEjecutable(requestStatus = "CANCEL_REQUESTED", outcome = "LINKED")
        val vm = vmRemotoConVinculo(cancelRequested)
        try {
            runCurrent()
            assertThat(vm.openLedgerAttemptAndMarkAuthorizing("attempt-N1")).isFalse()
            coVerify(exactly = 0) { paymentAttemptLedger.markAuthorizing(any()) }
            coVerify(exactly = 1) { paymentAttemptLedger.markDiscardedBeforeCharge("attempt-N1", "REQUEST_NOT_EXECUTABLE:CANCEL_REQUESTED") }
            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "REQ-N1", status = "cancelled", paymentId = null, transactionId = null, cardDetails = null,
                    errorMessage = any(), receiptUrl = null, receiptAccessKey = null, outcomeEvidence = "PRE_AUTHORIZATION",
                )
            }
            assertThat((vm.state.value as AngelPayPaymentState.Error).message).contains("No se inició otro cobro")
        } finally { vm.viewModelScope.cancel() }

        val completed = com.jaac.avoqado_tpv.core.remotepayment.DecisionDelVinculo.NoEjecutable(requestStatus = "COMPLETED", outcome = "LATE_EVIDENCE")
        val vm2 = vmRemotoConVinculo(completed)
        try {
            runCurrent()
            assertThat(vm2.openLedgerAttemptAndMarkAuthorizing("attempt-N1b")).isFalse()
            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "REQ-N1", status = "failed", paymentId = null, transactionId = null, cardDetails = null,
                    errorMessage = any(), receiptUrl = null, receiptAccessKey = null, outcomeEvidence = "PRE_AUTHORIZATION",
                )
            }
            val mensaje = (vm2.state.value as AngelPayPaymentState.Error).message
            assertThat(mensaje).contains("No se inició otro cobro")
            assertThat(mensaje).doesNotContain("No se cobró")
        } finally { vm2.viewModelScope.cancel() }
    }

    @Test
    fun `N1 llave ajena o invalida no cobra no emite negativo y el reintento abre una llave NUEVA`() = runTest(testDispatcher) {
        val vm = vmRemotoConVinculo(com.jaac.avoqado_tpv.core.remotepayment.DecisionDelVinculo.LlaveAjena)
        try {
            runCurrent()
            assertThat(vm.openLedgerAttemptAndMarkAuthorizing("attempt-N1")).isFalse()
            coVerify(exactly = 0) { paymentAttemptLedger.markAuthorizing(any()) }
            coVerify(exactly = 1) { paymentAttemptLedger.markDiscardedBeforeCharge("attempt-N1", "ATTEMPT_REUSE") }
            verify(exactly = 0) { socketManager.emitTerminalPaymentResult(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            val estado = vm.state.value as AngelPayPaymentState.Error
            assertThat(estado.canRetry).isTrue()
            // Reintentar NO está bloqueado (no salió ningún desenlace) y el siguiente intento es una llave nueva.
            vm.retryAfterError()
            assertThat(vm.state.value).isNotInstanceOf(AngelPayPaymentState.Error::class.java)
        } finally { vm.viewModelScope.cancel() }

        val vm2 = vmRemotoConVinculo(com.jaac.avoqado_tpv.core.remotepayment.DecisionDelVinculo.Invalida)
        try {
            runCurrent()
            assertThat(vm2.openLedgerAttemptAndMarkAuthorizing("attempt-N1c")).isFalse()
            coVerify(exactly = 1) { paymentAttemptLedger.markDiscardedBeforeCharge("attempt-N1c", "INVALID_LINK") }
            verify(exactly = 0) { socketManager.emitTerminalPaymentResult(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        } finally { vm2.viewModelScope.cancel() }
    }

    @Test
    fun `N1 durante la espera la pantalla es pre-dinero y un abandono avisa cancelled sin haber tocado el SDK`() = runTest(testDispatcher) {
        // La espera queda ANCLADA (el ACK nunca llega): el estado observable es LinkingAttempt.
        val nuncaContesta = kotlinx.coroutines.CompletableDeferred<com.jaac.avoqado_tpv.core.remotepayment.DecisionDelVinculo>()
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(enableShifts = false)
        coEvery { paymentAttemptLedger.capacidadDeVinculo("REQ-N1") } returns 1
        coEvery { socketManager.emitAttemptOpened("REQ-N1", any()) } coAnswers { nuncaContesta.await() }
        val vm = createViewModel()
        try {
            vm.initPayment("100.00")
            vm.setSocketPaymentSource("SOCKET", "REQ-N1")
            runCurrent()
            val espera = launch { vm.openLedgerAttemptAndMarkAuthorizing("attempt-N1") }
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.LinkingAttempt::class.java)
            assertThat(sinDineroEnVuelo(vm.state.value)).isTrue()
            coVerify(exactly = 0) { paymentAttemptLedger.markAuthorizing(any()) }
            // El cajero abandona la pantalla: el POS recibe cancelled + PRE_AUTHORIZATION (nada capaz de autorizar empezó).
            vm.emitCancelledIfAbandoned()
            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "REQ-N1", status = "cancelled", paymentId = null, transactionId = null, cardDetails = null,
                    errorMessage = any(), receiptUrl = null, receiptAccessKey = null, outcomeEvidence = "PRE_AUTHORIZATION",
                )
            }
            espera.cancel()
            runCurrent()
            coVerify(exactly = 0) { paymentAttemptLedger.markAuthorizing(any()) }
        } finally { vm.viewModelScope.cancel() }
    }


    // ══════════════════════════════════════════════════════════════════════════════════════════
    // Checkpoint 2 · N2 (S5): la confirmación del servidor cierra la espera de la pantalla — sólo para ESTE intento
    // ══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `N2 payment_confirmed registrado cierra un ResultadoIncierto de ESTE intento con Success y no toca otro intento ni uno sin registrar`() = runTest(testDispatcher) {
        // Mismo camino que U101: un intento ABIERTO (primeSdkLaunch) cuyo SDK contesta sin veredicto y AngelPay no responde.
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("sin red")
        val ids = mutableListOf<String>()
        coEvery { paymentAttemptLedger.markIndeterminate(capture(ids), any()) } returns Unit
        val vm = vmConCobroDelPos("REQ-S5")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult())
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.ResultadoIncierto::class.java)
            val mio = ids.last()
            // El «timeout» al POS ya salió (ResultadoIncierto sin verificar): lo que se vigila abajo es que S5 no emita OTRO.
            io.mockk.clearMocks(socketManager, answers = false, recordedCalls = true, verificationMarks = true)

            // Otro intento: se ignora.
            vm.manejarConfirmacionDelServidor(SocketEvent.TerminalPaymentConfirmed("REQ-S5", "otro-intento", "pay-x", 10000, 0, registrado = true))
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.ResultadoIncierto::class.java)
            // (El caso «mío, registrado = false» vive en su propia prueba, abajo: desde la Task 7 es una CONTRADICCIÓN, no un no-op.)
            // El mío, registrado: Success con el Payment del servidor y sin emitir otro desenlace (ya está en la bandeja).
            vm.manejarConfirmacionDelServidor(SocketEvent.TerminalPaymentConfirmed("REQ-S5", mio, "pay-s5", 10000, 0, registrado = true))
            val exito = vm.state.value
            assertThat(exito).isInstanceOf(AngelPayPaymentState.Success::class.java)
            assertThat((exito as AngelPayPaymentState.Success).receipt?.paymentId).isEqualTo("pay-s5")
            assertThat(exito.receipt?.serverRecordedVia).isEqualTo("webhook")
            verify(exactly = 0) { socketManager.emitTerminalPaymentResult(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            // Y un abandono posterior tampoco emite: el desenlace ya es durable.
            vm.emitCancelledIfAbandoned()
            verify(exactly = 0) { socketManager.emitTerminalPaymentResult(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // Codex r7 · P1-1: la RESPUESTA de la declaración también trae los tres avisos — y se hacen DURABLES
    // ══════════════════════════════════════════════════════════════════════════════════════════

    @Test fun `r7 P1-1 - el 2xx de la declaracion trae un aviso sin dinero propio tras una liberacion ya mostrada - veto durable y contradiccion`() = runTest(testDispatcher) {
        // Escenario de Codex: mientras vuela el POST, un S6 LIMPIO libera la fila; el POST llega después con una contradicción
        // (sin dinero propio). El parser de la declaración devuelve null, pero sin el veto la lectura de la fila volvía a anunciar
        // la liberación limpia — y ningún otro consumidor se enteraba del aviso. Los tres avisos, uno por vuelta.
        val avisos = listOf(
            PaymentAttemptEntity.VETO_PAYMENT_CONTRADICTION to TerminalAttemptResultDto(attemptId = "att", outcome = "NOT_RECORDED", paymentContradiction = true),
            PaymentAttemptEntity.VETO_EVIDENCE_CONTRADICTION to TerminalAttemptResultDto(attemptId = "att", outcome = "NOT_RECORDED", evidenceContradiction = true),
            PaymentAttemptEntity.VETO_UNATTRIBUTED_EVIDENCE to TerminalAttemptResultDto(attemptId = "att", outcome = "NOT_RECORDED", unattributedEvidence = true),
        )
        for ((motivo, intento) in avisos) {
            io.mockk.clearMocks(paymentAttemptLedger, answers = false, recordedCalls = true, verificationMarks = true)
            coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
            coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
            val puertaPost = kotlinx.coroutines.CompletableDeferred<Unit>()
            var lecturas = 0
            // 1ª lectura (consulta inmediata): nada · desde la 2ª: la fila YA liberada por la ventana (el S6 limpio ganó).
            coEvery { paymentAttemptLedger.leerIntento(any()) } answers { lecturas++; if (lecturas == 1) null else filaLiberada("att", "NO_EVIDENCE_AFTER_WINDOW") }
            val cuerpo = TerminalAttemptStatusResponse(success = true, attemptId = "att", requestId = "REQ-R7", attempt = intento,
                request = com.google.gson.JsonParser.parseString("""{"status":"FAILED","outcome":"NOT_CHARGED","outcomeEvidence":"OPERATOR_RECONCILED"}""").asJsonObject)
            coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } coAnswers { puertaPost.await(); Response.success(cuerpo) }
            val vm = vmConCobroDelPos("REQ-R7")
            try {
                vm.onIntentLaunched(); runCurrent()
                vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
                vm.declararSinTarjeta(); runCurrent()   // POST en vuelo
                advanceTimeBy(vm.msEntreConsultasS6 + 100); runCurrent()   // el sondeo lee la fila liberada y la anuncia
                assertThat((vm.state.value as AngelPayPaymentState.Error).message).contains("volver a cobrar")
                puertaPost.complete(Unit); runCurrent()   // el 2xx con el aviso
                val final = vm.state.value as AngelPayPaymentState.Error
                assertThat(final.message).contains("evidencia de cobro")
                assertThat(final.canRetry).isFalse()
                coVerify(exactly = 1) { paymentAttemptLedger.marcarVetoDelServidor("v1", any(), motivo, any()) }
                coVerify(exactly = 0) { paymentAttemptLedger.aplicarLiberacionDelServidor(any(), any()) }
                advanceTimeBy(vm.msMostrarLiberada + 100); runCurrent()
                assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)   // ningún reset automático de la liberación
            } finally { vm.viewModelScope.cancel() }
        }
    }

    @Test fun `r7 P1-1 control - un 2xx limpio de la declaracion no escribe ningun veto`() = runTest(testDispatcher) {
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("x")
        coEvery { ledgerServerRecovery.recoverOne(any(), any(), any(), any()) } returns LecturaDelIntento(null)
        val cuerpo = TerminalAttemptStatusResponse(success = true, attemptId = "att", requestId = "REQ-R7C",
            attempt = TerminalAttemptResultDto(attemptId = "att", outcome = "NOT_RECORDED"),
            request = com.google.gson.JsonParser.parseString("""{"status":"FAILED","outcome":"NOT_CHARGED","outcomeEvidence":"OPERATOR_RECONCILED"}""").asJsonObject)
        coEvery { attemptApi.resolveNoInstrument(any(), any(), any()) } returns Response.success(cuerpo)
        val vm = vmConCobroDelPos("REQ-R7C")
        try {
            vm.onAngelPaySdkResult(sdkInciertoResult()); runCurrent()
            vm.declararSinTarjeta(); runCurrent()
            coVerify(exactly = 0) { paymentAttemptLedger.marcarVetoDelServidor(any(), any(), any(), any()) }
            coVerify(exactly = 1) { paymentAttemptLedger.aplicarLiberacionDelServidor(any(), any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    // ── Arranque SIN servidor (Nexgo N86, 23-sep-2026) ─────────────────────────────────────
    // `getCurrentShift` falla por red y `initPayment` lo leía como «no hay turno»: «Debes abrir
    // un turno antes de cobrar», también en efectivo, con la caja ABIERTA en el servidor y
    // guardada en el aparato. El efectivo no necesita al servidor: se encola con el último turno
    // abierto conocido y el servidor atribuye el turno al llegar.

    // `initPayment` consulta el turno en `Dispatchers.IO` REAL: `advanceUntilIdle` no lo espera y se
    // leía el estado antes de tiempo. Se espera al ESTADO en tiempo real, como las pruebas vecinas.
    private suspend fun esperarQueSalgaDeIdle(vm: AngelPayPaymentViewModel): AngelPayPaymentState =
        withContext(Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(2_000) { vm.state.first { it !is AngelPayPaymentState.Idle } }
        }

    private fun sinServidor() = com.jaac.avoqado_tpv.core.domain.models.Result.Error(
        com.jaac.avoqado_tpv.core.domain.models.ApiException.NetworkError(java.net.ConnectException("ECONNREFUSED")),
    )

    private fun turnoGuardado(id: String) = com.jaac.avoqado_tpv.features.shift.domain.Shift(
        id = id, venueId = "v1", staffId = "s1", staffName = "Cajera", startTime = "2026-09-11T05:12:11Z",
        endTime = null, status = com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus.OPEN,
        startingCash = java.math.BigDecimal.ZERO, endingCash = null, totalSales = java.math.BigDecimal.ZERO,
        totalTips = java.math.BigDecimal.ZERO, totalOrders = 0, totalCashPayments = java.math.BigDecimal.ZERO,
        totalCardPayments = java.math.BigDecimal.ZERO, totalVoucherPayments = java.math.BigDecimal.ZERO,
        totalOtherPayments = java.math.BigDecimal.ZERO, totalProductsSold = 0, durationMinutes = null,
    )

    @Test
    fun `P1 sin servidor, el efectivo se cobra con el ultimo turno abierto guardado`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        coEvery { shiftRepository.getCurrentShift("v1") } returns sinServidor()
        coEvery { shiftRepository.getCachedOpenShift("v1") } returns turnoGuardado("shift-guardado")
        val ctxSlot = slot<com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext>()
        coEvery { recordPaymentUseCase(capture(ctxSlot), any(), any(), any()) } returns
            Result.failure(java.net.ConnectException("ECONNREFUSED"))
        val vm = createViewModel()
        try {
            vm.initPayment(amount = "100.00")
            esperarQueSalgaDeIdle(vm)
            assertThat(vm.state.value).isNotInstanceOf(AngelPayPaymentState.Error::class.java)

            vm.startCashPayment()
            runCurrent()
            advanceUntilIdle()

            coVerify(timeout = 2000, exactly = 1) { recordPaymentUseCase(any(), any(), any(), any()) }
            assertThat(ctxSlot.captured.shiftId).isEqualTo("shift-guardado")
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 con el turno SIN confirmar, la tarjeta no se lanza y se dice por que`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        coEvery { shiftRepository.getCurrentShift("v1") } returns sinServidor()
        coEvery { shiftRepository.getCachedOpenShift("v1") } returns turnoGuardado("shift-guardado")
        val vm = createViewModel()
        try {
            vm.flujoSdkForzadoParaPruebas = true
            vm.initPayment(amount = "100.00")
            esperarQueSalgaDeIdle(vm)

            vm.startCardPayment()
            runCurrent()
            advanceUntilIdle()

            val estado = vm.state.value
            assertThat(estado).isInstanceOf(AngelPayPaymentState.Error::class.java)
            assertThat((estado as AngelPayPaymentState.Error).message).contains("Sin conexión")
            assertThat(estado.message).contains("efectivo")
            coVerify(exactly = 0) { paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 sin servidor y SIN turno guardado no se inventa un turno y se dice que es la conexion`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        coEvery { shiftRepository.getCurrentShift("v1") } returns sinServidor()
        coEvery { shiftRepository.getCachedOpenShift("v1") } returns null
        val vm = createViewModel()
        try {
            vm.initPayment(amount = "100.00")
            esperarQueSalgaDeIdle(vm)

            val estado = vm.state.value
            assertThat(estado).isInstanceOf(AngelPayPaymentState.Error::class.java)
            assertThat((estado as AngelPayPaymentState.Error).message).contains("Sin conexión")
            // Abrir la caja sin servidor no es posible: ofrecer el botón sería mandarlo a otro error.
            assertThat(estado.showOpenShiftButton).isFalse()
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 un cobro REMOTO con el REST caido no usa el turno guardado - no abre efectivo sobre un remoto`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        coEvery { shiftRepository.getCurrentShift("v1") } returns sinServidor()
        coEvery { shiftRepository.getCachedOpenShift("v1") } returns turnoGuardado("shift-guardado")
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-remoto-sin-rest")
            vm.initPayment(amount = "100.00")
            val estado = esperarQueSalgaDeIdle(vm)

            assertThat(estado).isInstanceOf(AngelPayPaymentState.Error::class.java)
            assertThat((estado as AngelPayPaymentState.Error).message).contains("Sin conexión")
            coVerify(exactly = 0) { shiftRepository.getCachedOpenShift(any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `con servidor que dice sin turno se sigue pidiendo abrir la caja y no se mira la cache`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        coEvery { shiftRepository.getCurrentShift("v1") } returns com.jaac.avoqado_tpv.core.domain.models.Result.Success(null)
        coEvery { shiftRepository.getCachedOpenShift("v1") } returns turnoGuardado("shift-guardado")
        val vm = createViewModel()
        try {
            vm.initPayment(amount = "100.00")
            esperarQueSalgaDeIdle(vm)

            val estado = vm.state.value
            assertThat(estado).isInstanceOf(AngelPayPaymentState.Error::class.java)
            assertThat((estado as AngelPayPaymentState.Error).showOpenShiftButton).isTrue()
            coVerify(exactly = 0) { shiftRepository.getCachedOpenShift(any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

}

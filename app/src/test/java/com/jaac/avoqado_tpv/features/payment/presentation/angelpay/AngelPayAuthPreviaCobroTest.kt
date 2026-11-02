package com.jaac.avoqado_tpv.features.payment.presentation.angelpay

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.angelpay.angelpaysdk.models.MerchantOption
import com.angelpay.angelpaysdk.models.MerchantSummary
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.features.payment.data.ledger.CobroSinResolver
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayAuthRecovery
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayAuthRepository
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayAuthState
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayMerchantRepository
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.ANGELPAY_SWITCH_TIMEOUT_MS
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPaySdkGateway
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AuthErrorKind
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.DisparadorRecuperacion
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantEnvironment
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import com.jaac.avoqado_tpv.features.payment.domain.repository.MerchantRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * T26 (Testarudo, 2026-09-11) — el cobro con tarjeta autentica AngelPay ANTES de esperar
 * al comercio.
 *
 * Antes, `startCardPayment` esperaba 8 s a que el comercio activo cambiara ANTES de la única
 * línea que autentica; tras arrancar sin red nada lo cambiaba, así que el cajero veía
 * «Cambio de merchant no se completó. Reintenta.» y «Reintentar» repetía el ciclo. Y en un
 * cobro remoto no se le avisaba nada al POS: la tablet terminaba en UNKNOWN.
 *
 * El sabor sandbox compila con `ANGELPAY_SDK_ENABLED=false`; se usa el atajo
 * [AngelPayPaymentViewModel.flujoSdkForzadoParaPruebas] para llegar a la ruta del SDK.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AngelPayAuthPreviaCobroTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var intentBuilder: com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayIntentBuilder
    private lateinit var sdkGateway: AngelPaySdkGateway
    private lateinit var angelPayAuthRepository: AngelPayAuthRepository
    private lateinit var angelPayMerchantRepository: AngelPayMerchantRepository
    private lateinit var merchantRepository: MerchantRepository
    private lateinit var socketManager: SocketManager
    private lateinit var paymentAttemptLedger: PaymentAttemptLedger
    private lateinit var tpvSettingsRepository: TpvSettingsRepository
    private lateinit var angelPayAuthRecovery: AngelPayAuthRecovery
    private lateinit var paymentStateHolder: PaymentStateHolder

    private val authStateFlow = MutableStateFlow<AngelPayAuthState>(AngelPayAuthState.Authenticated)
    private val activeMerchantIdFlow = MutableStateFlow<Int?>(null)
    private val inFlightSwitchFlow = MutableStateFlow<Int?>(null)
    private val merchantsFlow = MutableStateFlow<List<MerchantAccount>>(emptyList())
    private var sesionViva = true
    /** El candado REAL de dueño de la sesión (el repositorio es un mock). */
    private val candado = Mutex()

    /** Un solo comercio (se autoselecciona), de la cuenta acc-A. */
    private val comercio = MerchantAccount(
        id = "merchant_a",
        merchantAccountId = "cma-001",
        serialNumber = "N86-001",
        displayName = "Testarudo",
        environment = MerchantEnvironment.PRODUCTION,
        processorType = ProcessorType.ANGELPAY,
        externalMerchantId = "11",
        angelpayUserAccountId = "acc-A",
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        intentBuilder = mockk(relaxed = true)
        sdkGateway = mockk(relaxed = true)
        angelPayAuthRepository = mockk(relaxed = true)
        angelPayMerchantRepository = mockk(relaxed = true)
        merchantRepository = mockk(relaxed = true)
        socketManager = mockk(relaxed = true)
        tpvSettingsRepository = mockk(relaxed = true)
        angelPayAuthRecovery = mockk(relaxed = true)
        paymentStateHolder = mockk(relaxed = true)
        paymentAttemptLedger = mockk(relaxed = true) {
            coEvery { cobroSinResolver() } returns null
            coEvery { openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) } returns true
            coEvery { markAuthorizing(any()) } returns true
            // C.5: por defecto la solicitud no está cercada y el efectivo/cripto puede arrancar.
            coEvery { cercaDeSolicitud(any()) } returns com.jaac.avoqado_tpv.features.payment.data.ledger.CercaDeSolicitud.LIBRE
            coEvery { iniciarEjecucionNoTarjeta(any()) } returns com.jaac.avoqado_tpv.features.payment.data.ledger.CercaDeSolicitud.LIBRE
        }

        every { angelPayAuthRepository.state } returns authStateFlow
        every { angelPayAuthRepository.candadoDeSesion } returns candado
        // Por default la sesión VIVA del SDK coincide con la marca en memoria.
        coEvery { sdkGateway.getUserMerchants() } answers {
            Result.success(
                activeMerchantIdFlow.value
                    ?.let { listOf(MerchantSummary(id = it, name = "Testarudo", affiliationNumber = "1001", isActive = true)) }
                    ?: emptyList(),
            )
        }
        every { angelPayMerchantRepository.activeAngelPayMerchantId } returns activeMerchantIdFlow
        every { angelPayMerchantRepository.inFlightSwitch } returns inFlightSwitchFlow
        every { angelPayMerchantRepository.observeCachedMerchants() } returns MutableStateFlow<List<MerchantSummary>>(emptyList())
        every { merchantRepository.getActiveMerchants() } returns merchantsFlow
        every { socketManager.events } returns MutableSharedFlow<SocketEvent>()
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(
            angelPaySdkEnabled = true,
            angelPaySdkFallbackEnabled = false,
            enableShifts = false,
            showReviewScreen = false,
            showTipScreen = false,
        )
        every { sdkGateway.isAuthenticated() } answers { sesionViva }
        every { sdkGateway.ensureInitialized(any(), any()) } returns Result.success(Unit)
        every { sdkGateway.isInitialized() } returns true
        every { sdkGateway.validatePaymentIntent(any(), any()) } returns Result.success(Unit)
        every { angelPayAuthRepository.getCurrentAngelPayAccountId() } returns "acc-A"
        coEvery { angelPayAuthRepository.ensureAuthenticatedAs(any()) } returns Result.success(Unit)
        coEvery { angelPayAuthRepository.ensureAuthenticated(any()) } returns Result.success(Unit)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(merchant: MerchantAccount? = comercio): AngelPayPaymentViewModel = AngelPayPaymentViewModel(
        appContext = mockk<Context>(relaxed = true),
        recordPaymentUseCase = mockk(relaxed = true),
        shiftRepository = mockk(relaxed = true),
        authRepository = mockk(relaxed = true),
        merchantRepository = merchantRepository,
        merchantEligibilityRepository = mockk(relaxed = true),
        secureStorage = mockk(relaxed = true),
        terminalConfigRepository = mockk(relaxed = true),
        intentBuilder = intentBuilder,
        sdkGateway = sdkGateway,
        angelPayAuthRepository = angelPayAuthRepository,
        angelPayMerchantRepository = angelPayMerchantRepository,
        paymentStateHolder = paymentStateHolder,
        tpvSettingsRepository = tpvSettingsRepository,
        printerManager = mockk(relaxed = true),
        angelPayTicketBuilder = mockk(relaxed = true),
        paymentApiService = mockk(relaxed = true),
        apiService = mockk(relaxed = true),
        socketManager = socketManager,
        verificationUploadManager = mockk(relaxed = true),
        observability = mockk(relaxed = true),
        paymentQueueRepository = mockk(relaxed = true),
        paymentAttemptLedger = paymentAttemptLedger,
        chargeVerifier = mockk(relaxed = true),
        authAttemptTelemetryStore = mockk(relaxed = true),
        angelPayAuthRecovery = angelPayAuthRecovery,
        // Task 7 (ventana de confirmación): S6 mientras se espera al servidor + la declaración del cajero. Fuera de estas pruebas.
        ledgerServerRecovery = mockk(relaxed = true),
        attemptApi = mockk(relaxed = true),
        savedStateHandle = androidx.lifecycle.SavedStateHandle(),
    ).also {
        it.flujoSdkForzadoParaPruebas = true
        merchantsFlow.value = listOfNotNull(merchant) // un solo comercio → autoselección
    }

    /** El arranque sin red: sin sesión del SDK y la auth en error. */
    private fun terminalAtoradaSinRed() {
        sesionViva = false
        activeMerchantIdFlow.value = null
        authStateFlow.value = AngelPayAuthState.AuthError("Sin conexión", AuthErrorKind.SIN_RED)
    }

    /** La auth con la cuenta del comercio levanta la sesión y el SDK autoselecciona el 11. */
    private fun authConRedDeVuelta() {
        coEvery { angelPayAuthRepository.ensureAuthenticatedAs("acc-A") } answers {
            // Con la sesión ya viva, la llamada de siempre en startSdkCardPayment es un no-op.
            if (!sesionViva) {
                sesionViva = true
                authStateFlow.value = AngelPayAuthState.Authenticated
                activeMerchantIdFlow.value = 11
            }
            Result.success(Unit)
        }
    }

    private fun verificarSinEmision() = verify(exactly = 0) {
        socketManager.emitTerminalPaymentResult(any(), any(), any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
    }

    private fun verificarUnSoloFailedPreAutorizacion(requestId: String) {
        verify(exactly = 1) {
            socketManager.emitTerminalPaymentResult(
                requestId = requestId, status = "failed", paymentId = any(), transactionId = any(),
                cardDetails = any(), errorMessage = any(), receiptUrl = any(), receiptAccessKey = any(),
                outcomeEvidence = "PRE_AUTHORIZATION",
            )
        }
        // Y NADA más para esa solicitud: a lo más UN desenlace final (regla H.3).
        verify(exactly = 1) {
            socketManager.emitTerminalPaymentResult(requestId, any(), any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
        }
    }

    /** Un cobro que mandó el POS, ya en «Método de pago» con su monto. */
    private fun AngelPayPaymentViewModel.cobroRemoto(requestId: String) {
        setSocketPaymentSource("SOCKET", requestId)
        initPayment("286.00")
    }

    @Test
    fun `P1 el cobro autentica ANTES de esperar al comercio y cobra sin la espera de 8 s`() = runTest(testDispatcher) {
        terminalAtoradaSinRed()
        authConRedDeVuelta()
        val vm = createViewModel()
        try {
            vm.startCardPayment()
            runCurrent() // sin avanzar el reloj: la espera de 8 s NO debe ser lo que resuelva

            coVerify(atLeast = 1) { angelPayAuthRepository.ensureAuthenticatedAs("acc-A") }
            // Llegó hasta lanzar el SDK — nunca pasó por «Cambio de merchant no se completó».
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.LaunchingAngelPaySdk::class.java)
            // Regla de Amaena: el genérico (que cae en la primaria) nunca en el camino de cobro.
            coVerify(exactly = 0) { angelPayAuthRepository.ensureAuthenticated(any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `muestra Conectando con AngelPay mientras autentica`() = runTest(testDispatcher) {
        terminalAtoradaSinRed()
        val puerta = CompletableDeferred<Result<Unit>>()
        coEvery { angelPayAuthRepository.ensureAuthenticatedAs("acc-A") } coAnswers { puerta.await() }
        val vm = createViewModel()
        try {
            vm.startCardPayment()
            runCurrent()

            assertThat(vm.state.value).isEqualTo(AngelPayPaymentState.ConectandoAngelPay)
            puerta.complete(Result.failure(IllegalStateException("x")))
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `si la sesion queda eligiendo comercio completa la seleccion con su token`() = runTest(testDispatcher) {
        terminalAtoradaSinRed()
        coEvery { angelPayAuthRepository.ensureAuthenticatedAs("acc-A") } answers {
            if (sesionViva) return@answers Result.success(Unit)
            sesionViva = true
            authStateFlow.value = AngelPayAuthState.SelectingMerchant(
                merchants = listOf(MerchantOption(id = 11, name = "Testarudo", afiliationNumber = "1001")),
                temporaryToken = "TOKEN-T26",
            )
            Result.success(Unit)
        }
        coEvery { angelPayAuthRepository.completeMerchantSelection(11, "TOKEN-T26") } answers {
            authStateFlow.value = AngelPayAuthState.Authenticated
            activeMerchantIdFlow.value = 11
            Result.success(Unit)
        }
        val vm = createViewModel()
        try {
            vm.startCardPayment()
            runCurrent()

            coVerify(exactly = 1) { angelPayAuthRepository.completeMerchantSelection(11, "TOKEN-T26") }
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.LaunchingAngelPaySdk::class.java)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `si el comercio activo no es el elegido lo cambia antes de esperar`() = runTest(testDispatcher) {
        terminalAtoradaSinRed()
        coEvery { angelPayAuthRepository.ensureAuthenticatedAs("acc-A") } answers {
            if (!sesionViva) {
                sesionViva = true
                authStateFlow.value = AngelPayAuthState.Authenticated
                activeMerchantIdFlow.value = 99 // la sesión quedó en otro comercio de la misma cuenta
            }
            Result.success(Unit)
        }
        coEvery { angelPayMerchantRepository.switchActiveMerchant(11) } answers {
            activeMerchantIdFlow.value = 11
            Result.success(Unit)
        }
        val vm = createViewModel()
        try {
            vm.startCardPayment()
            runCurrent()

            coVerify(exactly = 1) { angelPayMerchantRepository.switchActiveMerchant(11) }
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.LaunchingAngelPaySdk::class.java)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 cobro remoto con la auth previa fallida NO avisa nada mientras se puede reintentar`() = runTest(testDispatcher) {
        terminalAtoradaSinRed()
        coEvery { angelPayAuthRepository.ensureAuthenticatedAs("acc-A") } returns Result.failure(IllegalStateException("sin red"))
        val vm = createViewModel()
        try {
            vm.cobroRemoto("req-t26")
            runCurrent()
            vm.startCardPayment()
            runCurrent()

            // Regla H.3: con «Reintentar» en pantalla sobre ESTA solicitud, emitir un final haría
            // que el servidor la diera por NOT_CHARGED y un reintento aquí la cobrara igual.
            verificarSinEmision()
            assertThat((vm.state.value as AngelPayPaymentState.Error).canRetry).isTrue()
            // Nada capaz de autorizar empezó: ni libreta ni SDK.
            coVerify(exactly = 0) { paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { sdkGateway.buildPaymentRequest(any(), any(), any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 al cancelar tras la auth previa fallida sale exactamente un failed PRE_AUTHORIZATION`() = runTest(testDispatcher) {
        terminalAtoradaSinRed()
        coEvery { angelPayAuthRepository.ensureAuthenticatedAs("acc-A") } returns Result.failure(IllegalStateException("sin red"))
        val vm = createViewModel()
        try {
            vm.cobroRemoto("req-cancelada")
            runCurrent()
            vm.startCardPayment()
            runCurrent()
            verificarSinEmision()

            vm.resetPayment() // «Volver» o la flecha del top bar
            runCurrent()

            verificarUnSoloFailedPreAutorizacion("req-cancelada")
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `si el reintento autentica y cobra no sale ningun final negativo`() = runTest(testDispatcher) {
        terminalAtoradaSinRed()
        var intentos = 0
        coEvery { angelPayAuthRepository.ensureAuthenticatedAs("acc-A") } answers {
            intentos++
            if (intentos == 1) return@answers Result.failure(IllegalStateException("sin red"))
            if (!sesionViva) {
                sesionViva = true
                authStateFlow.value = AngelPayAuthState.Authenticated
                activeMerchantIdFlow.value = 11
            }
            Result.success(Unit)
        }
        val vm = createViewModel()
        try {
            vm.cobroRemoto("req-reintento")
            runCurrent()
            vm.startCardPayment()
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)

            vm.retryAfterError()
            runCurrent()
            vm.startCardPayment()
            runCurrent()

            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.LaunchingAngelPaySdk::class.java)
            verificarSinEmision()
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `si nadie retoma al vencer el reloj sale un solo failed PRE_AUTHORIZATION y ya no se reintenta`() = runTest(testDispatcher) {
        terminalAtoradaSinRed()
        coEvery { angelPayAuthRepository.ensureAuthenticatedAs("acc-A") } returns Result.failure(IllegalStateException("sin red"))
        val vm = createViewModel()
        try {
            vm.msAbandonoAvisoEmv = 50L
            vm.cobroRemoto("req-abandonada")
            runCurrent()
            vm.startCardPayment()
            runCurrent()
            verificarSinEmision()

            advanceTimeBy(100L)
            runCurrent()

            verificarUnSoloFailedPreAutorizacion("req-abandonada")
            // Desde que salió el final, la terminal ya no ejecuta esa solicitud.
            assertThat((vm.state.value as AngelPayPaymentState.Error).canRetry).isFalse()
            vm.retryAfterError()
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
            vm.resetPayment()
            runCurrent()
            verificarUnSoloFailedPreAutorizacion("req-abandonada")
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 cobro remoto con la espera del comercio vencida tampoco avisa hasta que se cancela`() = runTest(testDispatcher) {
        // La sesión está viva, pero hay un cambio de comercio en vuelo que nunca asienta.
        sesionViva = true
        authStateFlow.value = AngelPayAuthState.Authenticated
        activeMerchantIdFlow.value = 99
        inFlightSwitchFlow.value = 11
        val vm = createViewModel()
        try {
            vm.cobroRemoto("req-espera")
            runCurrent()
            vm.startCardPayment()
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Switching::class.java)

            advanceTimeBy(ANGELPAY_SWITCH_TIMEOUT_MS + 2_500L)
            runCurrent()

            verificarSinEmision()
            assertThat((vm.state.value as AngelPayPaymentState.Error).canRetry).isTrue()
            coVerify(exactly = 0) { paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) }

            vm.resetPayment()
            runCurrent()
            verificarUnSoloFailedPreAutorizacion("req-espera")
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 un cambio de comercio lento que termina bien deja cobrar aunque pase de 8 s`() = runTest(testDispatcher) {
        // N86, 7-oct-2026 (SDK 1.0.21, red lenta): la preparación posterior del cambio pasó de 8 s y
        // la espera fija de 8 s cortaba un cambio sano con «Cambio de merchant no se completó».
        sesionViva = true
        authStateFlow.value = AngelPayAuthState.Authenticated
        activeMerchantIdFlow.value = 99
        inFlightSwitchFlow.value = 11
        val vm = createViewModel()
        try {
            vm.startCardPayment()
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Switching::class.java)

            advanceTimeBy(20_000L)
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Switching::class.java)

            activeMerchantIdFlow.value = 11
            inFlightSwitchFlow.value = null
            runCurrent()

            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.LaunchingAngelPaySdk::class.java)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 no retiene ni avisa PRE_AUTHORIZATION si la libreta tiene un cobro sin resolver`() = runTest(testDispatcher) {
        terminalAtoradaSinRed()
        coEvery { angelPayAuthRepository.ensureAuthenticatedAs("acc-A") } returns Result.failure(IllegalStateException("sin red"))
        coEvery { paymentAttemptLedger.cobroSinResolver() } returns CobroSinResolver("intento-previo")
        val vm = createViewModel()
        try {
            vm.cobroRemoto("req-dudoso")
            runCurrent()
            vm.startCardPayment()
            runCurrent()
            verificarSinEmision()

            vm.resetPayment()
            runCurrent()

            // «No me acuerdo» no es prueba de que no se cobró: el final «failed» nunca sale.
            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(any(), "failed", any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `cobro local con la auth previa fallida conserva Reintentar y no avisa a nadie`() = runTest(testDispatcher) {
        terminalAtoradaSinRed()
        coEvery { angelPayAuthRepository.ensureAuthenticatedAs("acc-A") } returns Result.failure(IllegalStateException("sin red"))
        val vm = createViewModel()
        try {
            vm.startCardPayment()
            runCurrent()

            val state = vm.state.value as AngelPayPaymentState.Error
            assertThat(state.canRetry).isTrue()
            verificarSinEmision()
            verify(atLeast = 1) { paymentStateHolder.setCharging(false) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `con la sesion lista no re-autentica antes de esperar`() = runTest(testDispatcher) {
        sesionViva = true
        authStateFlow.value = AngelPayAuthState.Authenticated
        activeMerchantIdFlow.value = 11
        val vm = createViewModel()
        try {
            vm.startCardPayment()
            runCurrent()

            // Sólo la llamada de siempre dentro de startSdkCardPayment — la auth previa no se disparó.
            coVerify(exactly = 1) { angelPayAuthRepository.ensureAuthenticatedAs("acc-A") }
            coVerify(exactly = 0) { angelPayMerchantRepository.switchActiveMerchant(any()) }
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.LaunchingAngelPaySdk::class.java)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `el boton Reintentar del banner pide la recuperacion MANUAL`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            vm.retryAngelPayAuth()
            runCurrent()

            coVerify(exactly = 1) { angelPayAuthRecovery.recoverIfStuck(DisparadorRecuperacion.MANUAL, any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // Segunda indicación (incidente de Amaena): el cobro es el DUEÑO ÚNICO de la sesión
    // del SDK desde que empieza hasta lanzar el SDK o fallar; y la alineación se
    // revalida contra la sesión VIVA justo antes de lanzar.
    // ----------------------------------------------------------------------

    @Test
    fun `P1 el cobro es duenio de la sesion desde la auth hasta lanzar el SDK`() = runTest(testDispatcher) {
        terminalAtoradaSinRed()
        val puerta = CompletableDeferred<Unit>()
        coEvery { angelPayAuthRepository.ensureAuthenticatedAs("acc-A") } coAnswers {
            if (!sesionViva) {
                puerta.await()
                sesionViva = true
                authStateFlow.value = AngelPayAuthState.Authenticated
                activeMerchantIdFlow.value = 11
            }
            Result.success(Unit)
        }
        val vm = createViewModel()
        try {
            vm.startCardPayment()
            runCurrent()
            assertThat(candado.isLocked).isTrue() // en plena auth: nadie más puede tocar la sesión

            puerta.complete(Unit)
            runCurrent()

            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.LaunchingAngelPaySdk::class.java)
            assertThat(candado.isLocked).isFalse() // lanzado: desde aquí protege isCharging
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 si la recuperacion tiene la sesion el cobro espera y no autentica encima`() = runTest(testDispatcher) {
        sesionViva = true
        authStateFlow.value = AngelPayAuthState.Authenticated
        activeMerchantIdFlow.value = 11
        candado.lock() // la recuperación de fondo (o el panel) es dueña ahora
        val vm = createViewModel()
        try {
            vm.startCardPayment()
            runCurrent()

            assertThat(vm.state.value).isEqualTo(AngelPayPaymentState.ConectandoAngelPay)
            coVerify(exactly = 0) { angelPayAuthRepository.ensureAuthenticatedAs(any()) }

            candado.unlock()
            runCurrent()

            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.LaunchingAngelPaySdk::class.java)
            coVerify(exactly = 1) { angelPayAuthRepository.ensureAuthenticatedAs("acc-A") }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `al fallar la auth previa el cobro suelta la sesion`() = runTest(testDispatcher) {
        terminalAtoradaSinRed()
        coEvery { angelPayAuthRepository.ensureAuthenticatedAs("acc-A") } returns Result.failure(IllegalStateException("sin red"))
        val vm = createViewModel()
        try {
            vm.startCardPayment()
            runCurrent()

            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
            assertThat(candado.isLocked).isFalse()
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 la alineacion se revalida contra la sesion VIVA y no solo contra la marca en memoria`() = runTest(testDispatcher) {
        sesionViva = true
        authStateFlow.value = AngelPayAuthState.Authenticated
        activeMerchantIdFlow.value = 11 // la marca en memoria dice «el elegido»…
        // …pero la sesión viva quedó en otro comercio (otra auth corrió después).
        coEvery { sdkGateway.getUserMerchants() } returns Result.success(
            listOf(MerchantSummary(id = 99, name = "Primaria", affiliationNumber = "9999", isActive = true)),
        )
        val vm = createViewModel()
        try {
            vm.startCardPayment()
            runCurrent()

            assertThat((vm.state.value as AngelPayPaymentState.Error).message).contains("quedó en otro comercio")
            coVerify(exactly = 0) { paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { sdkGateway.buildPaymentRequest(any(), any(), any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `sin poder leer la sesion viva no se cobra`() = runTest(testDispatcher) {
        sesionViva = true
        authStateFlow.value = AngelPayAuthState.Authenticated
        activeMerchantIdFlow.value = 11
        coEvery { sdkGateway.getUserMerchants() } returns Result.failure(IllegalStateException("sin respuesta"))
        val vm = createViewModel()
        try {
            vm.startCardPayment()
            runCurrent()

            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
            coVerify(exactly = 0) { paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `el Reintentar desde metodo de pago quieto va como pantalla quieta y en pleno cobro no`() = runTest(testDispatcher) {
        terminalAtoradaSinRed()
        val puerta = CompletableDeferred<Result<Unit>>()
        coEvery { angelPayAuthRepository.ensureAuthenticatedAs("acc-A") } coAnswers { puerta.await() }
        val vm = createViewModel()
        try {
            vm.initPayment("286.00")
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.SelectingMerchant::class.java)
            vm.retryAngelPayAuth()
            runCurrent()
            // Pantalla quieta, y con la cuenta del comercio elegido (multicuenta: nunca la primaria).
            coVerify(exactly = 1) { angelPayAuthRecovery.recoverIfStuck(DisparadorRecuperacion.MANUAL, true, "acc-A") }

            vm.startCardPayment() // queda en «Conectando con AngelPay…»
            runCurrent()
            vm.retryAngelPayAuth()
            runCurrent()
            coVerify(exactly = 1) { angelPayAuthRecovery.recoverIfStuck(DisparadorRecuperacion.MANUAL, false, "acc-A") }
            puerta.complete(Result.failure(IllegalStateException("x")))
        } finally {
            vm.viewModelScope.cancel()
        }
    }
    @Test
    fun `D4 cuenta de sesion desconocida no equivale al login elegido aunque coincida el comercio`() = runTest(testDispatcher) {
        activeMerchantIdFlow.value = 11
        every { angelPayAuthRepository.getCurrentAngelPayAccountId() } returns null
        val vm = createViewModel()
        try {
            vm.startCardPayment()
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
            coVerify(exactly = 0) { paymentAttemptLedger.markAuthorizing(any()) }
            coVerify(atLeast = 1) { angelPayAuthRepository.ensureAuthenticatedAs("acc-A") }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun `D4 configuracion invalida no lanza tarjeta ni abre libreta aun llamando directo`() = runTest(testDispatcher) {
        activeMerchantIdFlow.value = 11
        val invalidas = listOf(null, comercio.copy(processorType = ProcessorType.BLUMON),
            comercio.copy(externalMerchantId = "0"), comercio.copy(externalMerchantId = "+11"),
            comercio.copy(externalMerchantId = "AWAITING_1"), comercio.copy(externalMerchantId = "2147483648"))
        for (merchant in invalidas) {
            val vm = createViewModel(merchant)
            try {
                vm.startCardPayment()
                runCurrent()
                assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
            } finally { vm.viewModelScope.cancel() }
        }
        coVerify(exactly = 0) { paymentAttemptLedger.markAuthorizing(any()) }
        verify(exactly = 0) { sdkGateway.buildPaymentRequest(any(), any(), any(), any()) }
    }

    private suspend fun sesionCacheadaBAntesDeCobrarA(incluirB: Boolean) {
        val configRepo = mockk<com.jaac.avoqado_tpv.core.domain.repository.TerminalConfigRepository>(relaxed = true)
        val authA = com.jaac.avoqado_tpv.core.data.network.dto.AngelPayAuthDto("acc-A", "a@tienda.test", "123456", "QA")
        val authB = authA.copy(accountId = "acc-B", email = "b@tienda.test")
        val config = mockk<com.jaac.avoqado_tpv.core.data.network.dto.TerminalConfigData>(relaxed = true) {
            every { merchantAccounts } returns (if (incluirB) listOf("acc-A", "acc-B") else listOf("acc-A")).map { cuenta ->
                mockk<com.jaac.avoqado_tpv.core.data.network.dto.MerchantAccountDto>(relaxed = true) {
                    every { providerCode } returns "ANGELPAY"
                    every { externalMerchantId } returns "11"
                    every { angelpayUserAccountId } returns cuenta
                }
            }
        }
        every { configRepo.getCachedConfig() } returns config
        every { configRepo.getCachedAngelPayAuth() } returns authA
        every { configRepo.getCachedAngelPayAccounts() } returns if (incluirB) listOf(authA, authB) else listOf(authA)
        coEvery { configRepo.fetchConfig(any()) } returns Result.success(mockk<com.jaac.avoqado_tpv.core.domain.repository.TerminalInfo>() to listOf(comercio))
        val validator = mockk<com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayConfigValidator> {
            coEvery { validate(any(), any()) } returns com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.ValidationResult.AllClear
        }
        val crash = mockk<com.google.firebase.crashlytics.FirebaseCrashlytics>(relaxed = true)
        val resolver = com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayCredentialResolver(configRepo, crash) { null }
        val authReal = AngelPayAuthRepository(sdkGateway, resolver, validator, configRepo, mockk(relaxed = true),
            angelPayMerchantRepository, paymentStateHolder, crash)
        angelPayAuthRepository = authReal
        activeMerchantIdFlow.value = 11 // B/X sigue viva; ambas cuentas pueden ver X.
        val puerta = CompletableDeferred<Unit>()
        coEvery { sdkGateway.authenticateSimple("a@tienda.test", "123456") } coAnswers {
            puerta.await()
            com.angelpay.angelpaysdk.models.AuthenticateSimpleResult.Success.let { Result.success(it) }
        }
        val vm = createViewModel()
        try {
            vm.startCardPayment()
            coVerify(exactly = 1) { sdkGateway.authenticateSimple("a@tienda.test", "123456") }
            assertThat(vm.state.value).isNotInstanceOf(AngelPayPaymentState.LaunchingAngelPaySdk::class.java)
            assertThat(authReal.getCurrentAngelPayAccountId()).isNull()
            coVerify(exactly = 0) { paymentAttemptLedger.markAuthorizing(any()) }
            puerta.complete(Unit)
            assertThat(authReal.getCurrentAngelPayAccountId()).isEqualTo("acc-A")
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.LaunchingAngelPaySdk::class.java)
        } finally { puerta.complete(Unit); vm.viewModelScope.cancel() }
    }

    @Test
    fun `D4 repo REAL autentica A antes de cobrar con B cacheada y A primero en config`() = runTest(testDispatcher) {
        sesionCacheadaBAntesDeCobrarA(incluirB = true)
    }

    @Test
    fun `D4 repo REAL autentica A antes de cobrar aun cuando B ya no aparece en config`() = runTest(testDispatcher) {
        sesionCacheadaBAntesDeCobrarA(incluirB = false)
    }

    @Test
    fun `D4 AuthError no puede cobrar aunque la funcion se llame sin pasar por boton`() = runTest(testDispatcher) {
        activeMerchantIdFlow.value = 11
        authStateFlow.value = AngelPayAuthState.AuthError("Comercio no autorizado")
        val vm = createViewModel()
        try {
            vm.startCardPayment()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
            coVerify(exactly = 0) { paymentAttemptLedger.markAuthorizing(any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun `D4 el canal legacy sin login conocido conserva app to app con comercio valido`() = runTest(testDispatcher) {
        activeMerchantIdFlow.value = 11
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(
            enableShifts = false, angelPaySdkEnabled = false, angelPaySdkFallbackEnabled = true,
            showReviewScreen = false, showTipScreen = false,
        )
        val vm = createViewModel(comercio.copy(angelpayUserAccountId = null))
        vm.flujoSdkForzadoParaPruebas = false
        try {
            vm.startCardPayment()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.LaunchingAngelPay::class.java)
        } finally { vm.viewModelScope.cancel() }
    }

    private fun legacyConFallback(sdk: Boolean) {
        activeMerchantIdFlow.value = 11
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(
            enableShifts = false, angelPaySdkEnabled = sdk, angelPaySdkFallbackEnabled = true,
            showReviewScreen = false, showTipScreen = false,
        )
    }

    private fun verificarSinIntentExterno() = verify(exactly = 0) {
        intentBuilder.buildSaleIntent(any(), any(), any(), any(), any())
    }

    @Test
    fun `R1 legacy con AuthError no abre app externa al llamar directo sin SDK`() = runTest(testDispatcher) {
        legacyConFallback(sdk = false)
        authStateFlow.value = AngelPayAuthState.AuthError("No se pudo autenticar")
        val vm = createViewModel(comercio.copy(angelpayUserAccountId = null))
        vm.flujoSdkForzadoParaPruebas = false
        try {
            vm.startCardPayment()
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
            verificarSinIntentExterno()
            coVerify(exactly = 0) { paymentAttemptLedger.markAuthorizing(any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    private suspend fun segundaAuthFallidaNoAbreExterna(publicaAuthError: Boolean) {
        legacyConFallback(sdk = true)
        sesionViva = false
        authStateFlow.value = AngelPayAuthState.Unauthenticated
        var llamadas = 0
        coEvery { angelPayAuthRepository.ensureAuthenticated(any()) } answers {
            llamadas++
            if (llamadas == 1) {
                sesionViva = true
                authStateFlow.value = AngelPayAuthState.Authenticated
                Result.success(Unit)
            } else {
                if (publicaAuthError) authStateFlow.value = AngelPayAuthState.AuthError("Falló la segunda auth")
                Result.failure(IllegalStateException("Falló la segunda auth"))
            }
        }
        val vm = createViewModel(comercio.copy(angelpayUserAccountId = null))
        try {
            vm.startCardPayment()
            assertThat(llamadas).isEqualTo(2)
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
            verificarSinIntentExterno()
            coVerify(exactly = 0) { paymentAttemptLedger.markAuthorizing(any()) }
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun `R1 auth previa valida y segunda auth fallida con AuthError no deriva a app externa`() = runTest(testDispatcher) {
        segundaAuthFallidaNoAbreExterna(publicaAuthError = true)
    }

    @Test
    fun `R1 un resultado AUTH fallido tampoco deriva a externa aunque su estado anterior siga Authenticated`() = runTest(testDispatcher) {
        segundaAuthFallidaNoAbreExterna(publicaAuthError = false)
    }

    private suspend fun errorDuranteLibretaNoLanza(cierrePermitido: Boolean) {
        legacyConFallback(sdk = false)
        val puerta = CompletableDeferred<Unit>()
        coEvery { paymentAttemptLedger.markAuthorizing(any()) } coAnswers {
            puerta.await()
            authStateFlow.value = AngelPayAuthState.AuthError("Sesión perdió autorización durante la espera")
            true
        }
        coEvery { paymentAttemptLedger.markSinAutorizacion(any(), any(), any()) } returns cierrePermitido
        val vm = createViewModel(comercio.copy(angelpayUserAccountId = null))
        vm.flujoSdkForzadoParaPruebas = false
        vm.cobroRemoto("req-r1-await")
        try {
            vm.startCardPayment()
            assertThat(vm.state.value).isNotInstanceOf(AngelPayPaymentState.LaunchingAngelPay::class.java)
            puerta.complete(Unit)
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
            val estado = vm.state.value as AngelPayPaymentState.Error
            assertThat(estado.canRetry).isEqualTo(cierrePermitido)
            verificarSinIntentExterno()
            coVerify(exactly = 1) { paymentAttemptLedger.markSinAutorizacion(any(), any(), "no_lanzado:identidad") }
            verificarSinEmision()
        } finally { vm.viewModelScope.cancel() }
    }

    @Test
    fun `R1 AuthError durante await legacy cierra solo el intento propio no lanzado`() = runTest(testDispatcher) {
        errorDuranteLibretaNoLanza(cierrePermitido = true)
    }

    @Test
    fun `R1 AuthError durante await legacy con veto conserva obligacion sin negativo falso`() = runTest(testDispatcher) {
        errorDuranteLibretaNoLanza(cierrePermitido = false)
    }

}

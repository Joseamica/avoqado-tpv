package com.jaac.avoqado_tpv.features.payment.presentation.angelpay

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.angelpay.angelpaysdk.models.AppErrorCatalog
import com.angelpay.angelpaysdk.models.MerchantSummary
import com.angelpay.angelpaysdk.models.PaymentResult
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.features.payment.data.ledger.CercaDeSolicitud
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger
import com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto
import com.jaac.avoqado_tpv.features.payment.data.local.AuthAttemptTelemetryStore
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayAuthRepository
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayAuthState
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayChargeVerifier
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayMerchantRepository
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPaySdkGateway
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.VerificacionDelCobro
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantEnvironment
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import com.jaac.avoqado_tpv.features.payment.domain.repository.MerchantRepository
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordPaymentUseCase
import com.jaac.avoqado_tpv.features.payment.presentation.CobroRemotoDelPos
import com.jaac.avoqado_tpv.core.observability.ObservabilityManager
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
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
 * El ViewModel de Nexgo con el SDK de AngelPay 1.0.19 (18-sep): lo que hace la pantalla y lo que sale al POS cuando el
 * SDK acredita que el cobro NO salió al banco, el rechazo MUDO de la barrera (§3.8) y sus hermanos previos a la libreta.
 *
 * Los resultados del SDK son `PaymentResult` REALES del AAR 1.0.19 (mismos argumentos que su orquestador), no mocks
 * relajados: un relajado devuelve `authorizationAttempted = false` e `integratorReference = ""` y escondería la regla.
 * Diseño: `diseno-nexgo-sdk-1.0.19.md` §2.3-§2.4 y §6.3 (T22-T30, T38-T39) y `diseno-nexgo-sin-tarjeta-leida.md` §3.8 (T31-T36).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AngelPaySdk119ViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var authRepository: AuthRepository
    private lateinit var sdkGateway: AngelPaySdkGateway
    private lateinit var angelPayAuthRepository: AngelPayAuthRepository
    private lateinit var angelPayMerchantRepository: AngelPayMerchantRepository
    private lateinit var merchantRepository: MerchantRepository
    private lateinit var paymentStateHolder: PaymentStateHolder
    private lateinit var tpvSettingsRepository: TpvSettingsRepository
    private lateinit var socketManager: SocketManager
    private lateinit var observabilityManager: ObservabilityManager
    private lateinit var paymentAttemptLedger: PaymentAttemptLedger
    private lateinit var authAttemptTelemetryStore: AuthAttemptTelemetryStore
    private lateinit var chargeVerifier: AngelPayChargeVerifier
    private lateinit var recordPaymentUseCase: RecordPaymentUseCase
    private lateinit var angelPayTicketBuilder: com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayTicketBuilder

    private val authStateFlow = MutableStateFlow<AngelPayAuthState>(AngelPayAuthState.Authenticated)
    private val activeMerchantIdFlow = MutableStateFlow<Int?>(11)
    private val merchantsFlow = MutableStateFlow<List<MerchantAccount>>(emptyList())
    private val socketEventsFlow = MutableSharedFlow<SocketEvent>()

    private val comercio = MerchantAccount(
        id = "merchant_a", merchantAccountId = "cma-001", serialNumber = "N86-001", displayName = "Testarudo",
        environment = MerchantEnvironment.SANDBOX, processorType = ProcessorType.ANGELPAY, externalMerchantId = "11",
        angelpayUserAccountId = "acc-A",
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        authRepository = mockk(relaxed = true)
        sdkGateway = mockk(relaxed = true)
        angelPayAuthRepository = mockk(relaxed = true)
        angelPayMerchantRepository = mockk(relaxed = true)
        merchantRepository = mockk(relaxed = true)
        paymentStateHolder = mockk(relaxed = true)
        tpvSettingsRepository = mockk(relaxed = true)
        socketManager = mockk(relaxed = true)
        observabilityManager = mockk(relaxed = true)
        authAttemptTelemetryStore = mockk(relaxed = true)
        chargeVerifier = mockk(relaxed = true)
        recordPaymentUseCase = mockk(relaxed = true)
        angelPayTicketBuilder = mockk(relaxed = true)
        paymentAttemptLedger = mockk(relaxed = true) {
            coEvery { cobroSinResolver() } returns null
            coEvery { adoptarCobroDeLaSolicitud(any()) } returns null
            coEvery { intentoDeLaSolicitud(any()) } returns null
            coEvery { openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) } returns true
            coEvery { markAuthorizing(any()) } returns true
            coEvery { cercaDeSolicitud(any()) } returns CercaDeSolicitud.LIBRE
            coEvery { iniciarEjecucionNoTarjeta(any()) } returns CercaDeSolicitud.LIBRE
            coEvery { aplicarVeredictoDelServidor(any()) } returns Result.success(
                ResultadoDelVeredicto(ResultadoDelVeredicto.Decision.APLICADO, true, null, false),
            )
            // Tras el CAS de «no se cobró» la relectura devuelve la fila tal como la dejó: DESCARTADA, sin evidencia.
            coEvery { leerIntento(any()) } answers { filaDelIntento(firstArg(), PaymentAttemptEntity.STATE_DESCARTADA) }
            coEvery { marcarEvidenciaPositivaDelServidor(any(), any(), any()) } returns Result.success(true)
            coEvery { reabrirSinAutorizacion(any(), any(), any(), any()) } returns true
            // El CAS nuevo gana por defecto; la prueba que quiere que pierda lo dice.
            coEvery { markSinAutorizacion(any(), any(), any()) } returns true
            coEvery { retencionDelAparato() } returns null
            coEvery { capacidadDeVinculo(any()) } returns 0
        }
        coEvery { chargeVerifier.verificar(any(), any(), any(), any(), any(), any()) } returns
            VerificacionDelCobro.NoSePudoVerificar("sin red")

        every { authRepository.getVenueId() } returns "v1"
        every { authRepository.getStaffId() } returns "s1"
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(
            enableShifts = false, angelPaySdkEnabled = true, angelPaySdkFallbackEnabled = false,
            showReviewScreen = false, showTipScreen = false,
        )
        every { angelPayAuthRepository.state } returns authStateFlow
        every { angelPayAuthRepository.candadoDeSesion } returns Mutex()
        every { angelPayAuthRepository.getCurrentAngelPayAccountId() } returns "acc-A"
        coEvery { angelPayAuthRepository.ensureAuthenticatedAs(any()) } returns Result.success(Unit)
        coEvery { angelPayAuthRepository.ensureAuthenticated(any()) } returns Result.success(Unit)
        every { angelPayMerchantRepository.activeAngelPayMerchantId } returns activeMerchantIdFlow
        every { angelPayMerchantRepository.inFlightSwitch } returns MutableStateFlow<Int?>(null)
        every { angelPayMerchantRepository.observeCachedMerchants() } returns MutableStateFlow<List<MerchantSummary>>(emptyList())
        every { merchantRepository.getActiveMerchants() } returns merchantsFlow
        every { socketManager.events } returns socketEventsFlow
        // 🔑 El candado de versión: el AAR que corre es el auditado.
        every { sdkGateway.sdkVersion() } returns "1.0.19"
        every { sdkGateway.isAuthenticated() } returns true
        every { sdkGateway.isInitialized() } returns true
        every { sdkGateway.ensureInitialized(any(), any()) } returns Result.success(Unit)
        every { sdkGateway.validatePaymentIntent(any(), any()) } returns Result.success(Unit)
        coEvery { sdkGateway.getUserMerchants() } answers {
            Result.success(listOf(MerchantSummary(id = activeMerchantIdFlow.value ?: 11, name = "Testarudo", affiliationNumber = "1001", isActive = true)))
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(handle: SavedStateHandle = SavedStateHandle()) = AngelPayPaymentViewModel(
        appContext = mockk<Context>(relaxed = true),
        recordPaymentUseCase = recordPaymentUseCase,
        shiftRepository = mockk(relaxed = true),
        authRepository = authRepository,
        merchantRepository = merchantRepository,
        merchantEligibilityRepository = mockk(relaxed = true),
        secureStorage = mockk(relaxed = true),
        terminalConfigRepository = mockk(relaxed = true),
        intentBuilder = mockk(relaxed = true),
        sdkGateway = sdkGateway,
        angelPayAuthRepository = angelPayAuthRepository,
        angelPayMerchantRepository = angelPayMerchantRepository,
        paymentStateHolder = paymentStateHolder,
        tpvSettingsRepository = tpvSettingsRepository,
        printerManager = mockk(relaxed = true),
        angelPayTicketBuilder = angelPayTicketBuilder,
        paymentApiService = mockk(relaxed = true),
        apiService = mockk(relaxed = true),
        socketManager = socketManager,
        verificationUploadManager = mockk(relaxed = true),
        observability = observabilityManager,
        paymentQueueRepository = mockk(relaxed = true),
        paymentAttemptLedger = paymentAttemptLedger,
        chargeVerifier = chargeVerifier,
        authAttemptTelemetryStore = authAttemptTelemetryStore,
        angelPayAuthRecovery = mockk(relaxed = true),
        ledgerServerRecovery = mockk(relaxed = true),
        attemptApi = mockk(relaxed = true),
        savedStateHandle = handle,
    )

    // ───────────────────── Resultados REALES del AAR 1.0.19 (mismos argumentos que su orquestador) ─────────────────────

    private fun delOrquestador(
        referencia: String?, status: PaymentResult.Status, mensaje: String, codigo: AppErrorCatalog.Code,
    ) = PaymentResult(
        approved = false, status = status, message = mensaje, amount = 10_000L, integratorReference = referencia,
        operationType = "VENTA", requireSignature = false, authorizationAttempted = false,
        callResult = callResultDe(codigo),
    )

    private fun u101(ref: String?) = delOrquestador(ref, PaymentResult.Status.TIMEOUT, "Tiempo de espera agotado", AppErrorCatalog.Code.U101)
    private fun e618(ref: String?) = delOrquestador(ref, PaymentResult.Status.ERROR, "Retire la tarjeta del lector e intente de nuevo", AppErrorCatalog.Code.E618)
    private fun e622(ref: String?) = delOrquestador(ref, PaymentResult.Status.DECLINED, "Tarjeta AMEX no soportada", AppErrorCatalog.Code.E622)

    /** El botón Cancelar de la pantalla del SDK: `false` escrito a mano, `operationType` null (medido en la N86). */
    private fun botonCancelar(ref: String?) = PaymentResult(
        approved = false, status = PaymentResult.Status.CANCELLED, message = "User cancelled", amount = 10_000L,
        integratorReference = ref, requireSignature = false, authorizationAttempted = false,
        callResult = callResultDe(AppErrorCatalog.Code.U100),
    )

    /** E608 real: el orquestador ya escribió los campos de tarjeta leída (`f0.java:682`). */
    private fun e608(ref: String?) = PaymentResult(
        approved = false, status = PaymentResult.Status.DECLINED, message = "Límite contactless excedido", amount = 10_000L,
        cardEntryMode = "contactless", aid = "A0000000031010", arqc = "9F2601AB", applicationLabel = "VISA",
        tvr = "0000008000", tsi = "E800", authentication = "Sin firma", integratorReference = ref,
        operationType = "VENTA", requireSignature = false, authorizationAttempted = false,
        callResult = callResultDe(AppErrorCatalog.Code.E608),
    )

    /** Respuesta del HOST (`attempted = true`). */
    private fun delHost(ref: String?, aprobado: Boolean, codigo: AppErrorCatalog.Code, codigoEmisor: String?, attempted: Boolean = true) = PaymentResult(
        approved = aprobado, status = if (aprobado) PaymentResult.Status.APPROVED else PaymentResult.Status.DECLINED,
        message = if (aprobado) "APROBADA" else "Declinada", authCode = if (aprobado) "600287" else null, amount = 10_000L,
        code = codigoEmisor, reference = "260917235506", integratorReference = ref, operationType = "VENTA",
        requireSignature = false, authorizationAttempted = attempted, callResult = callResultDe(codigo),
    )

    /** Un cobro con el SDK YA lanzado; devuelve el ViewModel y el intento (la referencia que el SDK hace eco). */
    private fun TestScope.cobroLanzado(requestId: String? = null): Pair<AngelPayPaymentViewModel, String> {
        val vm = createViewModel()
        vm.initPayment("100.00")
        runCurrent()
        if (requestId != null) vm.setSocketPaymentSource("SOCKET", requestId)
        vm.launchSdkRequest(mockk(relaxed = true), usedQaTipFallback = false)
        vm.onIntentLaunched()
        runCurrent()
        return vm to vm.attemptIdForTest()!!
    }

    private fun verificarSinEmision() = verify(exactly = 0) {
        socketManager.emitTerminalPaymentResult(any(), any(), any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
    }

    private fun verificarUnFailedPreAutorizacion(requestId: String, mensaje: String) {
        verify(exactly = 1) {
            socketManager.emitTerminalPaymentResult(
                requestId = requestId, status = "failed", paymentId = any(), transactionId = any(), cardDetails = any(),
                errorMessage = mensaje, receiptUrl = any(), receiptAccessKey = any(), outcomeEvidence = "PRE_AUTHORIZATION",
            )
        }
        // A lo más UN desenlace final por solicitud (H.3), y nunca un `timeout`.
        verify(exactly = 1) {
            socketManager.emitTerminalPaymentResult(requestId, any(), any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
        }
    }

    // ═════════════════════════════════ Pago rápido local (T22, T23, T26, T38) ═════════════════════════════════

    @Test
    fun `P1 T22 Pago rapido U101 sin autorizacion - la libreta primero, No se cobro con Intentar de nuevo, sin verificador ni ticket`() = runTest(testDispatcher) {
        val (vm, intento) = cobroLanzado()
        try {
            vm.onAngelPaySdkResult(u101(intento))
            runCurrent()

            coVerify(exactly = 1) {
                paymentAttemptLedger.markSinAutorizacion(intento, "v1", match { it.startsWith("sin_autorizacion:sdk=1.0.19;code=U101") })
            }
            val estado = vm.state.value as AngelPayPaymentState.Error
            assertThat(estado.noSeCobro).isTrue()
            assertThat(estado.canRetry).isTrue()
            assertThat(estado.message).isEqualTo("Tiempo agotado: nadie acercó una tarjeta. No se cobró nada.")
            // Sin verificador, sin incertidumbre, sin «rechazo del banco» ni su ticket, y un cobro LOCAL no emite nada.
            coVerify(exactly = 0) { chargeVerifier.verificar(any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { paymentAttemptLedger.markIndeterminate(any(), any()) }
            coVerify(exactly = 0) { paymentAttemptLedger.markHostResponded(any(), any(), any(), any(), any()) }
            verify(exactly = 0) { observabilityManager.logWarning("AngelPayDecline", any(), any()) }
            verify(exactly = 0) { angelPayTicketBuilder.buildDeclineTicket(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            verificarSinEmision()
            verify(exactly = 1) { authAttemptTelemetryStore.record("U101_SIN_AUTORIZACION", any(), "ANGELPAY") }
            verify(atLeast = 1) { paymentStateHolder.setCharging(false) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 T23 Intentar de nuevo abre un intento y una referencia NUEVOS`() = runTest(testDispatcher) {
        val (vm, intento) = cobroLanzado()
        try {
            vm.onAngelPaySdkResult(e618(intento))
            runCurrent()
            assertThat((vm.state.value as AngelPayPaymentState.Error).message)
                .isEqualTo("Retira la tarjeta del lector: no se cobró nada.")

            vm.retryAfterError()
            runCurrent()

            // De vuelta en «Método de pago», sin la llave vieja: el siguiente cobro nace con una referencia nueva.
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.SelectingMerchant::class.java)
            assertThat(vm.attemptIdForTest()).isNull()
            vm.launchSdkRequest(mockk(relaxed = true), usedQaTipFallback = false)
            assertThat(vm.attemptIdForTest()).isNotEqualTo(intento)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 T26 si la libreta no escribe, NO se declara no se cobro - queda incierto como hoy`() = runTest(testDispatcher) {
        coEvery { paymentAttemptLedger.markSinAutorizacion(any(), any(), any()) } returns false
        val (vm, intento) = cobroLanzado()
        try {
            vm.onAngelPaySdkResult(u101(intento))
            runCurrent()

            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.ResultadoIncierto::class.java)
            coVerify(exactly = 1) { paymentAttemptLedger.markIndeterminate(intento, any()) }
            assertThat((vm.state.value as? AngelPayPaymentState.Error)?.noSeCobro ?: false).isFalse()
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 decision U - el U100 del boton Cancelar con nuestra referencia es no se cobro`() = runTest(testDispatcher) {
        val (vm, intento) = cobroLanzado()
        try {
            vm.onAngelPaySdkResult(botonCancelar(intento))
            runCurrent()

            coVerify(exactly = 1) { paymentAttemptLedger.markSinAutorizacion(intento, "v1", any()) }
            val estado = vm.state.value as AngelPayPaymentState.Error
            assertThat(estado.noSeCobro).isTrue()
            assertThat(estado.message).isEqualTo("Cobro cancelado en la terminal: no se cobró nada.")
            coVerify(exactly = 0) { chargeVerifier.verificar(any(), any(), any(), any(), any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ═════════════════════════════════ Cobro del POS (T24, T39): se cierra AL INSTANTE ═════════════════════════════════

    @Test
    fun `P1 T24 cobro del POS U101 sin autorizacion emite failed mas PRE_AUTHORIZATION al instante y jamas timeout`() = runTest(testDispatcher) {
        val (vm, intento) = cobroLanzado(requestId = "req-u101")
        try {
            vm.onAngelPaySdkResult(u101(intento))
            runCurrent()

            coVerify(exactly = 1) { paymentAttemptLedger.markSinAutorizacion(intento, "v1", any()) }
            verificarUnFailedPreAutorizacion("req-u101", "Nadie acercó una tarjeta: no se cobró.")
            val estado = vm.state.value as AngelPayPaymentState.Error
            assertThat(estado.noSeCobro).isTrue()
            assertThat(estado.canRetry).isFalse() // la solicitud ya se cerró: el POS la reenvía
            assertThat(estado.message).isEqualTo(
                "Tiempo agotado: nadie acercó una tarjeta. No se cobró nada. Vuelve a enviar el cobro desde el punto de venta.",
            )
            assertThat(vm.mensajeDelPos.value).isEqualTo("Nadie acercó una tarjeta: no se cobró.")
            // Decisión del founder (18-sep): nada de retención H.3 — salir de la pantalla no emite un segundo desenlace.
            vm.resetPayment()
            runCurrent()
            verificarUnFailedPreAutorizacion("req-u101", "Nadie acercó una tarjeta: no se cobró.")
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 T39 cobro del POS con la tarjeta olvidada (E618) emite failed mas PRE_AUTHORIZATION con su texto`() = runTest(testDispatcher) {
        val (vm, intento) = cobroLanzado(requestId = "req-e618")
        try {
            vm.onAngelPaySdkResult(e618(intento))
            runCurrent()

            verificarUnFailedPreAutorizacion(
                "req-e618", "La terminal tenía una tarjeta puesta: no se cobró. Pide que la retiren y vuelve a cobrar.",
            )
            coVerify(exactly = 0) { paymentAttemptLedger.markIndeterminate(any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 cobro del POS E622 - la tablet recibe lo mismo que la terminal`() = runTest(testDispatcher) {
        val (vm, intento) = cobroLanzado(requestId = "req-e622")
        try {
            vm.onAngelPaySdkResult(e622(intento))
            runCurrent()
            verificarUnFailedPreAutorizacion("req-e622", "No se cobró nada: esta terminal no acepta tarjetas AMEX (E622).")
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 cobro del POS - tras mostrar el No se cobro la terminal sale sola, UNA vez`() = runTest(testDispatcher) {
        val (vm, intento) = cobroLanzado(requestId = "req-sale-sola")
        vm.msMostrarLiberada = 50L
        val salidas = mutableListOf<Unit>()
        val colector = launch { vm.salidaAutomatica.collect { salidas += it } }
        try {
            vm.onAngelPaySdkResult(botonCancelar(intento))
            runCurrent()
            assertThat(salidas).isEmpty()

            advanceTimeBy(60)
            runCurrent()

            assertThat(vm.state.value).isEqualTo(AngelPayPaymentState.Idle)
            assertThat(salidas).hasSize(1)
            verificarUnFailedPreAutorizacion("req-sale-sola", "Se canceló en la terminal: no se cobró.")
        } finally {
            colector.cancel()
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 un webhook con dinero que llega mientras se muestra el No se cobro del POS lo desmiente`() = runTest(testDispatcher) {
        // El SDK dijo «la petición no salió» y el servidor acredita dinero de ESE intento (S5 sin registro): el binario se
        // contradijo. La pantalla no puede seguir diciendo «no se cobró» ni salir sola como si nada.
        val (vm, intento) = cobroLanzado(requestId = "req-s5-tardio")
        vm.msMostrarLiberada = 50L
        try {
            vm.onAngelPaySdkResult(u101(intento))
            runCurrent()
            assertThat((vm.state.value as AngelPayPaymentState.Error).noSeCobro).isTrue()

            vm.manejarConfirmacionDelServidor(
                SocketEvent.TerminalPaymentConfirmed("req-s5-tardio", intento, "pay-1", 10_000, 0, registrado = false),
            )
            advanceTimeBy(60)
            runCurrent()

            val estado = vm.state.value as AngelPayPaymentState.Error
            assertThat(estado.noSeCobro).isFalse()
            assertThat(estado.canRetry).isFalse()
            assertThat(estado.message).contains("NO lo vuelvas a cobrar")
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 un webhook que REGISTRO el cobro mientras se muestra el No se cobro del POS pinta el cobro`() = runTest(testDispatcher) {
        val (vm, intento) = cobroLanzado(requestId = "req-s5-registrado")
        vm.msMostrarLiberada = 50L
        try {
            vm.onAngelPaySdkResult(u101(intento))
            runCurrent()

            vm.manejarConfirmacionDelServidor(
                SocketEvent.TerminalPaymentConfirmed("req-s5-registrado", intento, "pay-9", 10_000, 0, registrado = true),
            )
            advanceTimeBy(60)
            runCurrent()

            val estado = vm.state.value as AngelPayPaymentState.Success
            assertThat(estado.receipt?.paymentId).isEqualTo("pay-9")
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ═════════ Codex r1 (P1-2): S5 llega MIENTRAS el CAS de la libreta está suspendido (ni antes ni después) ═════════

    /** La fila tal como la deja S5 sobre el intento: lo DURABLE que la libreta escribe ANTES de avisarle a la pantalla. */
    private fun filaDelIntento(
        intento: String, estado: String, paymentId: String? = null, outcome: String? = null, evidencia: String? = null,
    ) = PaymentAttemptEntity(
        attemptId = intento, venueId = "v1", processor = "ANGELPAY", state = estado,
        amountCents = 10_000, tipCents = 0, recordingRoute = "FAST", paymentContextJson = "{}",
        lastError = "sin_autorizacion:sdk=1.0.19;code=U101;status=TIMEOUT", createdAt = 1, updatedAt = 2,
        serverPaymentId = paymentId, serverOutcome = outcome, serverProcessorEvidence = evidencia,
    )

    /**
     * El CAS GANA, pero dentro de su suspensión llega S5: primero la fila (lo durable) y después el aviso a la pantalla.
     * Así se reproduce la carrera de Codex: el ViewModel retoma DESPUÉS de S5 con el CAS ya escrito.
     */
    private fun s5DuranteElCas(
        vm: AngelPayPaymentViewModel, intento: String, requestId: String, filaTrasS5: PaymentAttemptEntity, registrado: Boolean,
    ) {
        var fila: PaymentAttemptEntity? = null
        coEvery { paymentAttemptLedger.leerIntento(intento) } answers { fila }
        coEvery { paymentAttemptLedger.markSinAutorizacion(intento, "v1", any()) } coAnswers {
            fila = filaTrasS5
            vm.manejarConfirmacionDelServidor(
                SocketEvent.TerminalPaymentConfirmed(requestId, intento, filaTrasS5.serverPaymentId ?: "pay-1", 10_000, 0, registrado),
            )
            true
        }
    }

    private fun verificarContradiccionSinNoSeCobro(vm: AngelPayPaymentViewModel) {
        val estado = vm.state.value as AngelPayPaymentState.Error
        assertWithMessage("con dinero conocido la pantalla nunca dice «no se cobró»").that(estado.noSeCobro).isFalse()
        assertThat(estado.canRetry).isFalse()
        assertThat(estado.message).contains("NO lo vuelvas a cobrar")
        verify(exactly = 0) {
            socketManager.emitTerminalPaymentResult(any(), "failed", any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
        }
        verify(atLeast = 1) { observabilityManager.logError("AngelPaySdkContradiccion", any(), any(), any()) }
    }

    @Test
    fun `P1 cobro del POS - S5 con dinero DURANTE el CAS termina en contradiccion, nunca en No se cobro`() = runTest(testDispatcher) {
        val (vm, intento) = cobroLanzado(requestId = "req-s5-cas")
        // Lo que deja S5 sobre la fila que el CAS acaba de cerrar: la contradicción con el Payment del servidor.
        s5DuranteElCas(
            vm, intento, "req-s5-cas",
            filaDelIntento(intento, PaymentAttemptEntity.STATE_DESCARTADA, paymentId = "pay-1", outcome = PaymentAttemptEntity.SERVER_RECORDED),
            registrado = false,
        )
        try {
            vm.onAngelPaySdkResult(u101(intento))
            runCurrent()
            verificarContradiccionSinNoSeCobro(vm)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 cobro del POS - el veto de S5 DURANTE el CAS basta aunque la fila aun no lo refleje`() = runTest(testDispatcher) {
        val (vm, intento) = cobroLanzado(requestId = "req-s5-veto")
        // La escritura durable de S5 no quedó (o no se ve todavía): la fila sigue siendo la DESCARTADA limpia del CAS. El aviso sí llegó.
        s5DuranteElCas(vm, intento, "req-s5-veto", filaDelIntento(intento, PaymentAttemptEntity.STATE_DESCARTADA), registrado = false)
        try {
            vm.onAngelPaySdkResult(u101(intento))
            runCurrent()
            verificarContradiccionSinNoSeCobro(vm)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 Pago rapido - cualquier evidencia de dinero en la libreta DURANTE el CAS termina en contradiccion, sin Intentar de nuevo`() = runTest(testDispatcher) {
        // Un cobro local no escucha el aviso de S5 (va por solicitud del POS): sólo la FILA puede delatar el dinero. Cada caso
        // aísla UNA forma de evidencia durable, para que ninguna guarda de la revalidación quede cubierta sólo por otra.
        val casos: List<Pair<String, (String) -> PaymentAttemptEntity>> = listOf(
            "aprobación bancaria sin Payment (marca APPROVED)" to { i ->
                filaDelIntento(i, PaymentAttemptEntity.STATE_DESCARTADA, evidencia = PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED)
            },
            "Payment del servidor" to { i -> filaDelIntento(i, PaymentAttemptEntity.STATE_DESCARTADA, paymentId = "pay-2") },
            "veredicto con dinero sin Payment" to { i ->
                filaDelIntento(i, PaymentAttemptEntity.STATE_DESCARTADA, outcome = PaymentAttemptEntity.SERVER_PENDING_EVIDENCE)
            },
            "aprobación del host" to { i -> filaDelIntento(i, PaymentAttemptEntity.STATE_DESCARTADA).copy(hostApproved = true) },
            "estado con dinero (HOST_RESPONDIO)" to { i -> filaDelIntento(i, PaymentAttemptEntity.STATE_HOST_RESPONDIO) },
        )
        casos.forEach { (caso, fila) ->
            val (vm, intento) = cobroLanzado()
            s5DuranteElCas(vm, intento, "sin-solicitud", fila(intento), registrado = false)
            try {
                vm.onAngelPaySdkResult(u101(intento))
                runCurrent()
                val estado = vm.state.value as AngelPayPaymentState.Error
                assertWithMessage("$caso: nunca «no se cobró»").that(estado.noSeCobro).isFalse()
                assertWithMessage("$caso: sin Intentar de nuevo").that(estado.canRetry).isFalse()
                assertWithMessage(caso).that(estado.message).contains("NO lo vuelvas a cobrar")
                // Y nada abre un intento nuevo encima del dinero conocido.
                vm.retryAfterError()
                runCurrent()
                assertWithMessage(caso).that(vm.attemptIdForTest()).isEqualTo(intento)
            } finally {
                vm.viewModelScope.cancel()
            }
        }
        verificarSinEmision()
        verify(exactly = casos.size) { observabilityManager.logError("AngelPaySdkContradiccion", any(), any(), any()) }
    }

    @Test
    fun `P1 cobro del POS - si S5 REGISTRO el cobro DURANTE el CAS termina en exito`() = runTest(testDispatcher) {
        val (vm, intento) = cobroLanzado(requestId = "req-s5-registrado-cas")
        s5DuranteElCas(
            vm, intento, "req-s5-registrado-cas",
            filaDelIntento(intento, PaymentAttemptEntity.STATE_REGISTRADO, paymentId = "pay-9", outcome = PaymentAttemptEntity.SERVER_RECORDED),
            registrado = true,
        )
        try {
            vm.onAngelPaySdkResult(u101(intento))
            runCurrent()
            val estado = vm.state.value as AngelPayPaymentState.Success
            assertThat(estado.receipt?.paymentId).isEqualTo("pay-9")
            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(any(), "failed", any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ═════ Codex r2 (P1-2 residual): la relectura que FALLA y el dinero que sólo consta en el VETO de S5 ═════

    /**
     * La relectura posterior al CAS LANZA (la pantalla no debe fiarse de que la libreta la traduzca a null); las lecturas
     * siguientes se comportan como la libreta real, que convierte un error en null (`PaymentAttemptLedger.leerIntento`).
     */
    private fun relecturaQueFallaUnaVez(intento: String) {
        var lecturas = 0
        coEvery { paymentAttemptLedger.leerIntento(intento) } answers {
            if (lecturas++ == 0) throw IllegalStateException("disco") else null
        }
    }

    @Test
    fun `P1 Pago rapido - si la relectura tras el CAS FALLA se falla cerrado - incierto y la fila se reabre`() = runTest(testDispatcher) {
        val (vm, intento) = cobroLanzado()
        // El CAS gana; después NO se puede releer la fila: nada garantiza que S6/S5 no dejaron dinero en ese hueco.
        relecturaQueFallaUnaVez(intento)
        try {
            vm.onAngelPaySdkResult(u101(intento))
            runCurrent()

            coVerify(exactly = 1) { paymentAttemptLedger.markSinAutorizacion(intento, "v1", any()) }
            coVerify(exactly = 1) {
                paymentAttemptLedger.reabrirSinAutorizacion(intento, "v1", match { it.startsWith("sin_autorizacion:") }, any())
            }
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.ResultadoIncierto::class.java)
            verificarSinEmision()
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 cobro del POS - si la relectura tras el CAS FALLA se falla cerrado - incierto, nunca failed`() = runTest(testDispatcher) {
        val (vm, intento) = cobroLanzado(requestId = "req-relectura-falla")
        relecturaQueFallaUnaVez(intento)
        try {
            vm.onAngelPaySdkResult(u101(intento))
            runCurrent()

            coVerify(exactly = 1) { paymentAttemptLedger.reabrirSinAutorizacion(intento, "v1", any(), any()) }
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.ResultadoIncierto::class.java)
            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(any(), "failed", any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 cobro del POS - si la relectura no devuelve la fila, o devuelve otra que no es la del CAS, se falla cerrado`() = runTest(testDispatcher) {
        listOf<Pair<String, (String) -> PaymentAttemptEntity?>>(
            "sin fila" to { _ -> null },
            "fila en otro estado sin dinero" to { i -> filaDelIntento(i, PaymentAttemptEntity.STATE_INDETERMINADO) },
        ).forEach { (caso, fila) ->
            val (vm, intento) = cobroLanzado(requestId = "req-relectura-$caso")
            coEvery { paymentAttemptLedger.leerIntento(intento) } returns fila(intento)
            try {
                vm.onAngelPaySdkResult(u101(intento))
                runCurrent()
                assertWithMessage(caso).that(vm.state.value).isInstanceOf(AngelPayPaymentState.ResultadoIncierto::class.java)
                coVerify(exactly = 1) { paymentAttemptLedger.reabrirSinAutorizacion(intento, "v1", any(), any()) }
            } finally {
                vm.viewModelScope.cancel()
            }
        }
        verify(exactly = 0) {
            socketManager.emitTerminalPaymentResult(any(), "failed", any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
        }
    }

    /** El veredicto EXACTO que S5 habría escrito para el aviso de [s5DuranteElCas] (servidor: `via: 'webhook'` fijo). */
    private fun esElVeredictoDeS5(v: com.jaac.avoqado_tpv.features.payment.data.ledger.VeredictoDeIntento, intento: String, requestId: String) =
        v == com.jaac.avoqado_tpv.features.payment.data.ledger.VeredictoDeIntento.desdeAvisoS5(
            venueId = "v1", requestId = requestId, attemptId = intento, paymentId = "pay-1", via = "webhook",
            amountCents = 10_000L, tipCents = 0L,
        )

    @Test
    fun `P1 cobro del POS - el dinero que solo consta en el veto de S5 se deja DURABLE en la fila con el veredicto de S5`() = runTest(testDispatcher) {
        val (vm, intento) = cobroLanzado(requestId = "req-veto-durable")
        // S5 avisó, pero su propia escritura FALLÓ: la fila sigue siendo la DESCARTADA limpia del CAS. La libreta aplica ahora el
        // veredicto (DESCARTADA + RECORDED = contradicción) y la bandeja queda resuelta con el ganador, como S5.
        val bandeja = """{"requestId":"req-veto-durable","status":"success","paymentId":"pay-1"}"""
        coEvery { paymentAttemptLedger.aplicarVeredictoDelServidor(any()) } returns Result.success(
            ResultadoDelVeredicto(ResultadoDelVeredicto.Decision.GUARDADO_SIN_LIBERAR, false, bandeja, true),
        )
        s5DuranteElCas(vm, intento, "req-veto-durable", filaDelIntento(intento, PaymentAttemptEntity.STATE_DESCARTADA), registrado = false)
        try {
            vm.onAngelPaySdkResult(u101(intento))
            runCurrent()

            coVerify(exactly = 1) { paymentAttemptLedger.aplicarVeredictoDelServidor(match { esElVeredictoDeS5(it, intento, "req-veto-durable") }) }
            // La bandeja resuelta se EMITE, como la emitiría S5 tras su commit.
            verify(exactly = 1) { socketManager.emitDurableTerminalPaymentResult(bandeja) }
            coVerify(exactly = 0) { paymentAttemptLedger.reabrirSinAutorizacion(any(), any(), any(), any()) }
            verificarContradiccionSinNoSeCobro(vm)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 cobro del POS - si ni el veredicto de S5 queda durable, la fila se reabre y se reporta`() = runTest(testDispatcher) {
        listOf(
            "la escritura falla" to Result.failure<ResultadoDelVeredicto>(IllegalStateException("disco")),
            "la libreta no lo acepta" to Result.success(
                ResultadoDelVeredicto(ResultadoDelVeredicto.Decision.RECHAZADO_PERTENENCIA, false, null, false),
            ),
        ).forEachIndexed { i, (caso, respuesta) ->
            coEvery { paymentAttemptLedger.aplicarVeredictoDelServidor(any()) } returns respuesta
            val requestId = "req-veto-sin-evidencia-$i"
            val (vm, intento) = cobroLanzado(requestId = requestId)
            s5DuranteElCas(vm, intento, requestId, filaDelIntento(intento, PaymentAttemptEntity.STATE_DESCARTADA), registrado = false)
            try {
                vm.onAngelPaySdkResult(u101(intento))
                runCurrent()

                coVerify(exactly = 1) {
                    paymentAttemptLedger.reabrirSinAutorizacion(intento, "v1", match { it.startsWith("sin_autorizacion:") }, any())
                }
                val estado = vm.state.value as AngelPayPaymentState.Error
                assertWithMessage(caso).that(estado.noSeCobro).isFalse()
                assertWithMessage(caso).that(estado.message).contains("NO lo vuelvas a cobrar")
            } finally {
                vm.viewModelScope.cancel()
            }
        }
        verify(exactly = 0) {
            socketManager.emitTerminalPaymentResult(any(), "failed", any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
        }
        // Nunca en silencio: además de la contradicción del SDK (una por caso), se reporta la evidencia que no quedó durable.
        verify(exactly = 4) { observabilityManager.logError("AngelPaySdkContradiccion", any(), any(), any()) }
    }

    @Test
    fun `P1 cobro del POS - un S5 SIN registrar sobre el No se cobro ya en pantalla deja durable su veredicto`() = runTest(testDispatcher) {
        val (vm, intento) = cobroLanzado(requestId = "req-s5-tras-pintar")
        vm.msMostrarLiberada = 50L
        try {
            vm.onAngelPaySdkResult(u101(intento))
            runCurrent()
            assertThat((vm.state.value as AngelPayPaymentState.Error).noSeCobro).isTrue()
            coVerify(exactly = 0) { paymentAttemptLedger.aplicarVeredictoDelServidor(any()) }

            vm.manejarConfirmacionDelServidor(
                SocketEvent.TerminalPaymentConfirmed("req-s5-tras-pintar", intento, "pay-1", 10_000, 0, registrado = false),
            )
            advanceTimeBy(60)
            runCurrent()

            coVerify(exactly = 1) { paymentAttemptLedger.aplicarVeredictoDelServidor(match { esElVeredictoDeS5(it, intento, "req-s5-tras-pintar") }) }
            val estado = vm.state.value as AngelPayPaymentState.Error
            assertThat(estado.noSeCobro).isFalse()
            assertThat(estado.message).contains("NO lo vuelvas a cobrar")
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 cobro del POS - tras salir, un S5 sin registrar de un cobro NUEVO no toca el cierre anterior`() = runTest(testDispatcher) {
        val (vm, intento) = cobroLanzado(requestId = "req-cierre-viejo")
        vm.msMostrarLiberada = 50L
        try {
            vm.onAngelPaySdkResult(u101(intento))
            runCurrent()
            advanceTimeBy(60)   // la terminal sale sola: resetPayment()
            runCurrent()
            // Un cobro NUEVO en el MISMO ViewModel; su S5 sin registrar llega con el SDK dentro.
            vm.initPayment("100.00")
            runCurrent()
            vm.setSocketPaymentSource("SOCKET", "req-nuevo")
            vm.launchSdkRequest(mockk(relaxed = true), usedQaTipFallback = false)
            vm.onIntentLaunched()
            runCurrent()
            val nuevo = vm.attemptIdForTest()!!
            assertThat(nuevo).isNotEqualTo(intento)
            vm.manejarConfirmacionDelServidor(SocketEvent.TerminalPaymentConfirmed("req-nuevo", nuevo, "pay-2", 10_000, 0, registrado = false))
            runCurrent()

            coVerify(exactly = 0) { paymentAttemptLedger.reabrirSinAutorizacion(any(), any(), any(), any()) }
            coVerify(exactly = 0) { paymentAttemptLedger.aplicarVeredictoDelServidor(any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ═══════════════════════════════ Lo que NO cambia (T25, T27, T29, T30 + decisiones) ═══════════════════════════════

    @Test
    fun `P1 T12 con otra version del SDK el MISMO U101 sigue incierto como hoy`() = runTest(testDispatcher) {
        every { sdkGateway.sdkVersion() } returns "1.0.18"
        val (vm, intento) = cobroLanzado(requestId = "req-v18")
        try {
            vm.onAngelPaySdkResult(u101(intento))
            runCurrent()

            coVerify(exactly = 0) { paymentAttemptLedger.markSinAutorizacion(any(), any(), any()) }
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.ResultadoIncierto::class.java)
            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(any(), "failed", any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 T3 un resultado con la referencia de OTRO intento queda incierto`() = runTest(testDispatcher) {
        val (vm, _) = cobroLanzado(requestId = "req-otro")
        try {
            vm.onAngelPaySdkResult(e622("otro-intento"))
            runCurrent()

            coVerify(exactly = 0) { paymentAttemptLedger.markSinAutorizacion(any(), any(), any()) }
            // E622 HOY sería un rechazo con Reintentar: con la referencia de otro cobro no puede cerrar ESTE intento.
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.ResultadoIncierto::class.java)
            coVerify(exactly = 0) { paymentAttemptLedger.markHostResponded(any(), false, any(), any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 T5 attempted=false con datos del host es contradiccion - incierto y se reporta`() = runTest(testDispatcher) {
        val (vm, intento) = cobroLanzado(requestId = "req-contradiccion")
        try {
            vm.onAngelPaySdkResult(u101(intento).copy(authCode = "600287", reference = "260917235506"))
            runCurrent()

            coVerify(exactly = 0) { paymentAttemptLedger.markSinAutorizacion(any(), any(), any()) }
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.ResultadoIncierto::class.java)
            verify(atLeast = 1) { observabilityManager.logError("AngelPaySdkContradiccion", any(), any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 T27 con evidencia de dinero del servidor para ESTE intento, un sin autorizacion es contradiccion`() = runTest(testDispatcher) {
        val (vm, intento) = cobroLanzado(requestId = "req-veto")
        try {
            // S5 sin registro (webhook) enciende el veto de dinero de ESTE intento mientras el SDK estaba dentro.
            vm.manejarConfirmacionDelServidor(
                SocketEvent.TerminalPaymentConfirmed("req-veto", intento, "pay-1", 10_000, 0, registrado = false),
            )
            vm.onAngelPaySdkResult(u101(intento))
            runCurrent()

            coVerify(exactly = 0) { paymentAttemptLedger.markSinAutorizacion(any(), any(), any()) }
            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(any(), "failed", any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
            }
            val estado = vm.state.value
            assertWithMessage("la pantalla nunca dice «no se cobró» con evidencia de dinero").that(
                estado is AngelPayPaymentState.Error && estado.noSeCobro,
            ).isFalse()
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 decision 2 - attempted=true sigue como hoy - G505 incierto y G500 rechazo con Reintentar`() = runTest(testDispatcher) {
        val (vm, intento) = cobroLanzado()
        try {
            vm.onAngelPaySdkResult(delHost(intento, aprobado = false, codigo = AppErrorCatalog.Code.G505, codigoEmisor = null))
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.ResultadoIncierto::class.java)
        } finally {
            vm.viewModelScope.cancel()
        }
        val (vm2, intento2) = cobroLanzado()
        try {
            vm2.onAngelPaySdkResult(delHost(intento2, aprobado = false, codigo = AppErrorCatalog.Code.G500, codigoEmisor = "05"))
            runCurrent()
            val estado = vm2.state.value as AngelPayPaymentState.Error
            assertThat(estado.noSeCobro).isFalse()
            assertThat(estado.canRetry).isTrue()
            coVerify(exactly = 1) { paymentAttemptLedger.markHostResponded(intento2, false, null, any(), any()) }
            coVerify(exactly = 0) { paymentAttemptLedger.markSinAutorizacion(any(), any(), any()) }
        } finally {
            vm2.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 E608 sigue como hoy - rechazo con Reintentar en la misma venta`() = runTest(testDispatcher) {
        val (vm, intento) = cobroLanzado()
        try {
            vm.onAngelPaySdkResult(e608(intento))
            runCurrent()

            val estado = vm.state.value as AngelPayPaymentState.Error
            assertThat(estado.noSeCobro).isFalse()
            assertThat(estado.canRetry).isTrue()
            coVerify(exactly = 1) { paymentAttemptLedger.markHostResponded(intento, false, null, any(), any()) }
            coVerify(exactly = 0) { paymentAttemptLedger.markSinAutorizacion(any(), any(), any()) }
            verify(exactly = 1) { observabilityManager.logWarning("AngelPayDecline", any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 T10 aprobado con attempted=false gana el dinero y se reporta la contradiccion`() = runTest(testDispatcher) {
        coEvery { recordPaymentUseCase(any(), any(), any(), any()) } returns Result.success(
            PaymentReceipt(paymentId = "pay-1", receiptUrl = "", accessKey = "", amount = java.math.BigDecimal("100.00"), tipAmount = java.math.BigDecimal.ZERO),
        )
        val (vm, intento) = cobroLanzado()
        try {
            vm.onAngelPaySdkResult(delHost(intento, aprobado = true, codigo = AppErrorCatalog.Code.S000, codigoEmisor = "00", attempted = false))
            runCurrent()

            coVerify(timeout = 2_000, exactly = 1) { recordPaymentUseCase(any(), any(), any(), any()) }
            coVerify(exactly = 0) { paymentAttemptLedger.markSinAutorizacion(any(), any(), any()) }
            verify(atLeast = 1) { observabilityManager.logError("AngelPaySdkContradiccion", any(), any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 T2 un respaldo del contrato sin referencia sigue incierto`() = runTest(testDispatcher) {
        val (vm, _) = cobroLanzado(requestId = "req-respaldo")
        try {
            vm.onAngelPaySdkResult(PaymentResult(approved = false, status = PaymentResult.Status.CANCELLED, message = "Cancelled"))
            runCurrent()
            coVerify(exactly = 0) { paymentAttemptLedger.markSinAutorizacion(any(), any(), any()) }
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.ResultadoIncierto::class.java)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 T2 un C200 sin referencia al abrir la pantalla del SDK queda incierto, nunca un rechazo con Reintentar`() = runTest(testDispatcher) {
        // Codex r1 (P1-1): la tabla de hoy lo daba por rechazo CIERTO —`host_approved = 0`, «Reintentar» y un final retenido
        // con `PROCESSOR_DECLINED`— sobre un resultado que nada correlaciona con este intento.
        val (vm, _) = cobroLanzado(requestId = "req-c200")
        try {
            vm.onAngelPaySdkResult(
                PaymentResult(
                    approved = false, status = PaymentResult.Status.ERROR, message = "Missing request",
                    callResult = callResultDe(AppErrorCatalog.Code.C200),
                ),
            )
            runCurrent()

            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.ResultadoIncierto::class.java)
            coVerify(exactly = 0) { paymentAttemptLedger.markHostResponded(any(), false, any(), any(), any()) }
            coVerify(exactly = 0) { paymentAttemptLedger.markSinAutorizacion(any(), any(), any()) }
            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(any(), "failed", any(), any(), any(), any(), any(), any(), outcomeEvidence = any())
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ═══════════════════════════ §3.8 — el rechazo MUDO de la barrera (T31, T33, T34, T35) ═══════════════════════════

    private fun TestScope.cobroDelPosEnMetodoDePago(requestId: String?): AngelPayPaymentViewModel {
        val vm = createViewModel()
        vm.flujoSdkForzadoParaPruebas = true
        merchantsFlow.value = listOf(comercio)
        if (requestId != null) vm.setSocketPaymentSource("SOCKET", requestId)
        vm.initPayment("100.00")
        runCurrent()
        return vm
    }

    private fun retencion(centavos: Long, haceMs: Long) = PaymentAttemptEntity(
        attemptId = "incierta", venueId = "v1", processor = "ANGELPAY", state = "INDETERMINADO",
        amountCents = centavos, tipCents = 0, recordingRoute = "FAST", paymentContextJson = "{}",
        lastError = "AngelPay sin veredicto", createdAt = System.currentTimeMillis() - haceMs,
        updatedAt = System.currentTimeMillis() - haceMs,
    )

    @Test
    fun `P1 T31 un cobro del POS rechazado por la cerca del aparato emite failed mas PRE_AUTHORIZATION al instante y NOMBRA lo que la aparta`() = runTest(testDispatcher) {
        coEvery { paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) } returns false
        coEvery { paymentAttemptLedger.retencionDelAparato() } returns retencion(centavos = 500, haceMs = 2 * 60_000L)
        val vm = cobroDelPosEnMetodoDePago("req-barrera")
        try {
            vm.startCardPayment()
            runCurrent()

            verificarUnFailedPreAutorizacion("req-barrera", CobroRemotoDelPos.NO_INICIADO_POR_COBRO_PENDIENTE)
            val estado = vm.state.value as AngelPayPaymentState.Error
            assertThat(estado.canRetry).isFalse()
            assertThat(estado.message).contains("NO se inició")
            assertThat(estado.message).contains("$5.00")
            assertThat(estado.message).contains("hace 2 min")
            assertThat(vm.mensajeDelPos.value).isEqualTo(CobroRemotoDelPos.NO_INICIADO_POR_COBRO_PENDIENTE)
            verify(exactly = 0) { sdkGateway.validatePaymentIntent(any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 T31 sin retencion nombrable la tablet recibe el texto sin detalle`() = runTest(testDispatcher) {
        coEvery { paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) } returns false
        coEvery { paymentAttemptLedger.retencionDelAparato() } returns null
        val vm = cobroDelPosEnMetodoDePago("req-barrera-sin-detalle")
        try {
            vm.startCardPayment()
            runCurrent()
            verificarUnFailedPreAutorizacion("req-barrera-sin-detalle", CobroRemotoDelPos.NO_INICIADO_SIN_DETALLE)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 T31 si la barrera que pierde es la de AUTORIZANDO tambien se emite`() = runTest(testDispatcher) {
        coEvery { paymentAttemptLedger.markAuthorizing(any()) } returns false
        val vm = cobroDelPosEnMetodoDePago("req-barrera-autorizando")
        try {
            vm.startCardPayment()
            runCurrent()
            verificarUnFailedPreAutorizacion("req-barrera-autorizando", CobroRemotoDelPos.NO_INICIADO_SIN_DETALLE)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 T31 la fila PROPIA en PREPARANDO no se nombra como el cobro que aparta la terminal`() = runTest(testDispatcher) {
        // La reserva entró (PREPARANDO propia) y perdió la barrera de AUTORIZANDO: `findTerminalHold` puede devolver ESA
        // misma fila, y nombrarla («$100.00, hace unos segundos») le diría al cajero que hay OTRO cobro pendiente.
        coEvery { paymentAttemptLedger.markAuthorizing(any()) } returns false
        val vm = cobroDelPosEnMetodoDePago("req-barrera-propia")
        coEvery { paymentAttemptLedger.retencionDelAparato() } answers {
            retencion(centavos = 10_000, haceMs = 1_000L).copy(attemptId = vm.attemptIdForTest()!!, state = "PREPARANDO")
        }
        try {
            vm.startCardPayment()
            runCurrent()
            verificarUnFailedPreAutorizacion("req-barrera-propia", CobroRemotoDelPos.NO_INICIADO_SIN_DETALLE)
            assertThat((vm.state.value as AngelPayPaymentState.Error).message)
                .isEqualTo(CobroRemotoDelPos.noIniciadoEnLaTerminal(null))
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 T33 el rechazo de la barrera en un cobro LOCAL no emite nada y dice lo de siempre`() = runTest(testDispatcher) {
        coEvery { paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) } returns false
        val vm = cobroDelPosEnMetodoDePago(requestId = null)
        try {
            vm.startCardPayment()
            runCurrent()
            verificarSinEmision()
            assertThat((vm.state.value as AngelPayPaymentState.Error).message)
                .isEqualTo("No se pudo guardar el intento o esta venta tiene un cobro pendiente. No se inició otro cobro.")
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 T34 tras el rechazo de la barrera, salir no emite un segundo desenlace`() = runTest(testDispatcher) {
        coEvery { paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) } returns false
        val vm = cobroDelPosEnMetodoDePago("req-barrera-salir")
        try {
            vm.startCardPayment()
            runCurrent()
            vm.retryAfterError()
            vm.resetPayment()
            vm.emitCancelledIfAbandoned()
            runCurrent()
            verificarUnFailedPreAutorizacion("req-barrera-salir", CobroRemotoDelPos.NO_INICIADO_SIN_DETALLE)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 T35 la app-a-app rechazada por la barrera tambien emite`() = runTest(testDispatcher) {
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(
            enableShifts = false, angelPaySdkEnabled = false, angelPaySdkFallbackEnabled = true,
            showReviewScreen = false, showTipScreen = false,
        )
        coEvery { paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) } returns false
        val vm = cobroDelPosEnMetodoDePago("req-barrera-app")
        vm.flujoSdkForzadoParaPruebas = false
        try {
            vm.startCardPayment()
            runCurrent()
            verificarUnFailedPreAutorizacion("req-barrera-app", CobroRemotoDelPos.NO_INICIADO_SIN_DETALLE)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ══════════ Codex r1 (P2 previo): la validación fallida no deja la fila en AUTORIZANDO ni aparta la terminal ══════════

    @Test
    fun `P1 Pago rapido - validacion fallida cierra la fila acreditada y Reintentar abre un intento NUEVO`() = runTest(testDispatcher) {
        every { sdkGateway.validatePaymentIntent(any(), any()) } returns Result.failure(IllegalStateException("Monto inválido para el comercio"))
        val vm = cobroDelPosEnMetodoDePago(requestId = null)
        try {
            vm.startCardPayment()
            runCurrent()
            val intento = vm.attemptIdForTest()!!

            // El SDK nunca se lanzó con esta llave: la fila AUTORIZANDO se cierra desde AQUÍ, con el CAS con guardas de evidencia.
            coVerify(exactly = 1) {
                paymentAttemptLedger.markSinAutorizacion(intento, "v1", match { it.startsWith("no_lanzado:validacion") })
            }
            coVerify(exactly = 0) { paymentAttemptLedger.markIndeterminate(any(), any()) }
            val estado = vm.state.value as AngelPayPaymentState.Error
            assertThat(estado.canRetry).isTrue()
            assertThat(estado.noSeCobro).isFalse()
            assertThat(estado.message).contains("Monto inválido para el comercio")
            verificarSinEmision()

            // «Reintentar» FUNCIONA: vuelve al método de pago con la llave limpia (antes las guardas lo bloqueaban).
            vm.retryAfterError()
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.SelectingMerchant::class.java)
            assertThat(vm.attemptIdForTest()).isNull()
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 cobro del POS - validacion fallida retiene su final y el reloj de abandono lo cierra, UNA vez`() = runTest(testDispatcher) {
        every { sdkGateway.validatePaymentIntent(any(), any()) } returns Result.failure(IllegalStateException("Monto inválido para el comercio"))
        val vm = cobroDelPosEnMetodoDePago("req-validacion")
        vm.msAbandonoAvisoEmv = 100L
        try {
            vm.startCardPayment()
            runCurrent()
            // Mientras la pantalla ofrezca «Reintentar» esta solicitud, su final NO sale…
            assertThat((vm.state.value as AngelPayPaymentState.Error).canRetry).isTrue()
            verificarSinEmision()
            // …y si nadie retoma, sale `failed + PRE_AUTHORIZATION`, una vez.
            advanceTimeBy(vm.msAbandonoAvisoEmv + 10)
            runCurrent()
            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    "req-validacion", "failed", any(), any(), any(), any(), any(), any(), outcomeEvidence = "PRE_AUTHORIZATION",
                )
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 validacion fallida - si la libreta no cierra la fila, no se ofrece un Reintentar que las guardas bloquearian`() = runTest(testDispatcher) {
        every { sdkGateway.validatePaymentIntent(any(), any()) } returns Result.failure(IllegalStateException("Monto inválido para el comercio"))
        coEvery { paymentAttemptLedger.markSinAutorizacion(any(), any(), any()) } returns false
        val vm = cobroDelPosEnMetodoDePago(requestId = null)
        try {
            vm.startCardPayment()
            runCurrent()
            // La fila sigue viva (obligación): no se afirma nada ni se ofrece un botón que no puede funcionar.
            val estado = vm.state.value as AngelPayPaymentState.Error
            assertThat(estado.canRetry).isFalse()
            assertThat(estado.noSeCobro).isFalse()
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 si el fallback de propina valida, NO se cierra la fila y se lanza el cobro`() = runTest(testDispatcher) {
        // «Descartando primero cualquier fallback que vaya a continuar» (Codex r1): el cierre sólo va cuando NADIE va a lanzar.
        every { sdkGateway.validatePaymentIntent(any(), any()) } returnsMany listOf(
            Result.failure(IllegalStateException("Propina no soportada")), Result.success(Unit),
        )
        every { sdkGateway.isTipUnsupportedError(any()) } returns true
        val vm = createViewModel()
        vm.flujoSdkForzadoParaPruebas = true
        merchantsFlow.value = listOf(comercio)
        vm.initPayment("100.00", externalTipCents = 1_000)
        runCurrent()
        try {
            vm.startCardPayment()
            runCurrent()
            coVerify(exactly = 0) { paymentAttemptLedger.markSinAutorizacion(any(), any(), any()) }
            assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.LaunchingAngelPaySdk::class.java)
            assertThat((vm.state.value as AngelPayPaymentState.LaunchingAngelPaySdk).usedQaTipFallback).isTrue()
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ══════════ T36 · los hermanos PREVIOS a la libreta retienen su final y arman el reloj de abandono (H.3) ══════════

    private fun TestScope.verificarRetenidoYCerradoAlAbandonar(vm: AngelPayPaymentViewModel, requestId: String) {
        // Mientras la pantalla ofrezca «Reintentar» ESTA solicitud, su final NO sale…
        assertThat((vm.state.value as AngelPayPaymentState.Error).canRetry).isTrue()
        verificarSinEmision()
        coVerify(exactly = 0) { paymentAttemptLedger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) }
        // …y si nadie retoma, el reloj de abandono lo cierra como `failed + PRE_AUTHORIZATION`, UNA vez.
        advanceTimeBy(vm.msAbandonoAvisoEmv + 10)
        runCurrent()
        verify(exactly = 1) {
            socketManager.emitTerminalPaymentResult(requestId, "failed", any(), any(), any(), any(), any(), any(), outcomeEvidence = "PRE_AUTHORIZATION")
        }
    }

    @Test
    fun `P1 T36 el SDK sin inicializar en un cobro del POS retiene su final y lo cierra al abandonar`() = runTest(testDispatcher) {
        every { sdkGateway.ensureInitialized(any(), any()) } returns Result.failure(IllegalStateException("sin SDK"))
        every { sdkGateway.isInitialized() } returns false
        val vm = cobroDelPosEnMetodoDePago("req-init")
        vm.msAbandonoAvisoEmv = 100L
        try {
            vm.startCardPayment()
            runCurrent()
            verificarRetenidoYCerradoAlAbandonar(vm, "req-init")
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 T36 la auth fallida dentro del cobro del POS retiene su final y lo cierra al abandonar`() = runTest(testDispatcher) {
        // La sesión estaba lista (la auth previa no corre); la de `startSdkCardPayment` falla.
        coEvery { angelPayAuthRepository.ensureAuthenticatedAs(any()) } returns Result.failure(IllegalStateException("401"))
        val vm = cobroDelPosEnMetodoDePago("req-auth")
        vm.msAbandonoAvisoEmv = 100L
        try {
            vm.startCardPayment()
            runCurrent()
            verificarRetenidoYCerradoAlAbandonar(vm, "req-auth")
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 T36 la sesion en otra cuenta en un cobro del POS retiene su final y lo cierra al abandonar`() = runTest(testDispatcher) {
        // La sesión VIVA del SDK quedó en el comercio 22 mientras el cajero eligió el 11.
        coEvery { sdkGateway.getUserMerchants() } returns Result.success(
            listOf(MerchantSummary(id = 22, name = "Otro", affiliationNumber = "2", isActive = true)),
        )
        val vm = cobroDelPosEnMetodoDePago("req-alineacion")
        vm.msAbandonoAvisoEmv = 100L
        try {
            vm.startCardPayment()
            runCurrent()
            verificarRetenidoYCerradoAlAbandonar(vm, "req-alineacion")
        } finally {
            vm.viewModelScope.cancel()
        }
    }
}

/**
 * `AppErrorCatalog.toCallResult` es una extensión MIEMBRO del objeto (`fun Code.toCallResult()` dentro de
 * `object AppErrorCatalog`): desde Kotlin se llama con el objeto como receptor de despacho. Es la MISMA función con
 * la que el AAR 1.0.19 arma el `callResult` de cada resultado (`AppErrorCatalog.INSTANCE.toCallResult(...)`).
 */
private fun callResultDe(codigo: AppErrorCatalog.Code) = with(AppErrorCatalog) { codigo.toCallResult() }

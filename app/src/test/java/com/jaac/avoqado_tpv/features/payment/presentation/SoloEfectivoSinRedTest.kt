package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.domain.TerminalConfig
import com.jaac.avoqado_tpv.core.util.ConnectionStateManager
import com.jaac.avoqado_tpv.features.payment.data.InitializationManager
import com.jaac.avoqado_tpv.features.payment.data.ledger.CercaDeSolicitud
import com.jaac.avoqado_tpv.features.payment.domain.MOTIVO_SIN_TARJETA
import com.jaac.avoqado_tpv.features.payment.domain.PaymentState
import com.jaac.avoqado_tpv.features.payment.domain.cobrarSaltaElLector
import com.jaac.avoqado_tpv.features.payment.domain.decidirMotivoSinTarjeta
import com.jaac.avoqado_tpv.features.payment.domain.rutaDePagoEsperaAlLector
import com.jaac.avoqado_tpv.features.payment.domain.tarjetaSinRed
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantEligibility
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantEnvironment
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings
import com.jaac.avoqado_tpv.features.payment.domain.repository.MerchantRepository
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordPaymentUseCase
import com.jaac.avoqado_tpv.features.payment.presentation.angelpay.AngelPayPaymentState
import com.jaac.avoqado_tpv.features.payment.presentation.angelpay.AngelPayPaymentViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
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

/**
 * 💵 D2 «Cobrar sin red» (21-oct-2026): el efectivo no espera al lector ni a las cuentas de pago.
 *
 * Hoy, en una PAX que arrancó sin red, ni el efectivo se cobra: verificar las cuentas de pago por red
 * termina en «Sin conexión…» y el lector de Blumon nunca termina de prepararse. Sin red (o con
 * `soloEfectivo`, o con cuentas de respaldo que no se pueden refrescar) la pantalla «Método de pago»
 * sale en sólo efectivo: tarjeta, MSI y cripto en gris con [MOTIVO_SIN_TARJETA]. Con red y cuentas
 * reales, todo como hoy. Blumon corre contra la copia de SANDBOX (la de producción no tiene pruebas
 * unitarias que corran, ver memoria `testproduction-unit-roto-desde-2-8-2`); Nexgo, contra la única.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SoloEfectivoSinRedTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val cuentaReal = MerchantAccount(
        id = "merchant_a",
        serialNumber = "2841548417",
        posId = "376",
        displayName = "Account A",
        environment = MerchantEnvironment.SANDBOX,
        isActive = true,
    )

    private lateinit var conexion: ConnectionStateManager
    private lateinit var cuentas: MerchantRepository
    private lateinit var lector: InitializationManager
    private lateinit var registrarPago: RecordPaymentUseCase
    private val obtenerCuentas: com.jaac.avoqado_tpv.features.payment.domain.use_case.GetMerchantsUseCase = mockk(relaxed = true)

    private fun recibo() = PaymentReceipt(
        paymentId = "pay-1",
        receiptUrl = "https://receipt.avoqado.io/pay-1",
        accessKey = "k",
        amount = BigDecimal("100.00"),
        tipAmount = BigDecimal.ZERO,
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(TerminalConfig)
        every { TerminalConfig.serialNumber } returns "TEST-SERIAL"
        every { TerminalConfig.brand } returns "PAX"
        every { TerminalConfig.model } returns "A80"
        mockkObject(com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler)
        every { com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler.runNow(any()) } just runs

        // La de verdad (arranca conectada): las pruebas la apagan y la prenden como pasa en el aparato.
        conexion = ConnectionStateManager()
        cuentas = mockk(relaxed = true) {
            every { isUsingFallback() } returns false
            coEvery { refreshMerchants() } returns Result.success(Unit)
        }
        // El lector de Blumon que nunca termina de prepararse: lo que pasa en una PAX que arrancó sin red.
        lector = mockk(relaxed = true) {
            every { isInitialized } returns MutableStateFlow(false)
            coEvery { awaitInitialization() } coAnswers { awaitCancellation() }
            coEvery { ensureInitialized(any()) } coAnswers { awaitCancellation() }
        }
        registrarPago = mockk(relaxed = true)
        coEvery { registrarPago(context = any(), cardDetails = any(), authorizationNumber = any(), referenceNumber = any()) } returns
            Result.success(recibo())
        every { obtenerCuentas.invoke() } returns flowOf(listOf(cuentaReal))
    }

    private fun sinRed() {
        conexion.setInternetConnected(false)
        conexion.setServerConnected(false)
    }

    private fun conRed() {
        conexion.setInternetConnected(true)
        conexion.setServerConnected(true)
    }

    /** Espera activa corta a que se cumpla [condicion] (los colectores corren fuera del cuerpo de la prueba). */
    private fun esperarQue(descripcion: String, timeoutMs: Long = 5000, condicion: () -> Boolean) {
        val limite = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < limite) {
            if (condicion()) return
            Thread.sleep(20)
        }
        throw AssertionError("No se cumplió: $descripcion")
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Blumon (PAX)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun blumon(): PaymentViewModel = PaymentViewModel(
        preTransUseCase = mockk(relaxed = true),
        startDetectCardUseCase = mockk(relaxed = true),
        stopDetectCardUseCase = mockk(relaxed = true),
        startEmvTransUseCase = mockk(relaxed = true),
        startCtlssTransUseCase = mockk(relaxed = true),
        getEmvTagUseCase = mockk(relaxed = true),
        completeEmvTransUseCase = mockk(relaxed = true),
        continueConfirmCardUseCase = mockk(relaxed = true),
        setSelectAppCodeUseCase = mockk(relaxed = true),
        saleIccUseCase = mockk(relaxed = true),
        saleCtlsUseCase = mockk(relaxed = true),
        cancelIccUseCase = mockk(relaxed = true),
        validateCancelUseCase = mockk(relaxed = true),
        transProcessRepository = mockk(relaxed = true) {
            every { getEventPinDialogStateFlow() } returns MutableStateFlow(mockk(relaxed = true) {
                every { show } returns false
                every { dismiss } returns false
            })
            every { getKeyboardPinStateFlow() } returns MutableStateFlow("")
            every { getPinResultFlow() } returns MutableStateFlow(null)
            every { getSelectAppStateFlow() } returns MutableStateFlow(null)
            every { confirmCardReadingFlow() } returns MutableStateFlow(false)
        },
        initializerUseCase = mockk(relaxed = true),
        getInitDataUseCase = mockk(relaxed = true),
        insertInitUseCase = mockk(relaxed = true),
        initializationManager = lector,
        getMerchantsUseCase = obtenerCuentas,
        multiMerchantSDKManager = mockk(relaxed = true) { every { getCurrentMerchant() } returns null },
        recordPaymentUseCase = registrarPago,
        recordRefundUseCase = mockk(relaxed = true),
        authRepository = mockk(relaxed = true) {
            every { getVenueId() } returns "venue-test-001"
            every { getStaffId() } returns "staff-test-001"
        },
        paymentQueueRepository = mockk(relaxed = true),
        refundQueueRepository = mockk(relaxed = true),
        printerManager = mockk(relaxed = true),
        socketManager = mockk(relaxed = true) { every { events } returns MutableSharedFlow() },
        shiftRepository = mockk(relaxed = true) { every { isShiftSystemEnabled() } returns false },
        orderRepository = mockk(relaxed = true),
        orderSyncCoordinator = mockk(relaxed = true),
        tpvSettingsRepository = mockk(relaxed = true) { every { getCurrentSettings() } returns TpvSettings.DEFAULT },
        verificationUploadManager = mockk(relaxed = true),
        paymentApiService = mockk(relaxed = true),
        apiService = mockk(relaxed = true),
        customerRepository = mockk(relaxed = true),
        secureStorage = mockk(relaxed = true) { every { getSerialNumber() } returns "TEST-SERIAL" },
        modulesRepository = mockk(relaxed = true) { every { modules } returns MutableStateFlow(emptyList()) },
        connectionStateManager = conexion,
        merchantRepository = cuentas,
        merchantEligibilityRepository = mockk(relaxed = true) {
            coEvery { evaluate(any(), any(), any()) } returns MerchantEligibility.disabled()
        },
        criticalNetworkOperationManager = mockk(relaxed = true),
        paymentStateHolder = com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder(),
        connectionEventManager = mockk(relaxed = true) { every { connectionRestoredEvents } returns MutableSharedFlow() },
        paymentAttemptLedger = mockk(relaxed = true) {
            coEvery { cercaDeSolicitud(any()) } returns CercaDeSolicitud.LIBRE
            coEvery { iniciarEjecucionNoTarjeta(any()) } returns CercaDeSolicitud.LIBRE
        },
        blumonAttemptResolver = mockk(relaxed = true),
        observability = mockk(relaxed = true),
        authAttemptTelemetryStore = mockk(relaxed = true),
        appContext = mockk(relaxed = true),
        ventaLocalRepository = mockk(relaxed = true),
        pendingPaymentDao = mockk(relaxed = true),
        subirVentaGuardadaUseCase = mockk(relaxed = true),
    )

    /**
     * Espera a que «Método de pago» quede LISTO (la preparación terminó) o a un error. Espera activa corta, como
     * `esperarSelectingMerchant` de PaymentViewModelTest: el refresco de cuentas corre en Dispatchers.IO.
     */
    private fun esperarMetodoDePago(viewModel: PaymentViewModel, timeoutMs: Long = 5000): PaymentState {
        val limite = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < limite) {
            val estado = viewModel.state.value
            if (estado is PaymentState.Error) return estado
            if (estado is PaymentState.SelectingMerchant && !viewModel.isPreparingMerchantSelection.value) return estado
            Thread.sleep(20)
        }
        throw AssertionError(
            "«Método de pago» no quedó listo. Estado=${viewModel.state.value}, " +
                "preparando=${viewModel.isPreparingMerchantSelection.value}"
        )
    }

    @Test
    fun `sin red y cuentas de respaldo ⇒ SelectingMerchant con motivoSinTarjeta (no Error)`() = runTest {
        sinRed()
        every { cuentas.isUsingFallback() } returns true
        val viewModel = blumon()
        try {
            viewModel.submitAmountDirectToMerchant("100.00")

            val estado = esperarMetodoDePago(viewModel)
            assertThat(estado).isInstanceOf(PaymentState.SelectingMerchant::class.java)
            assertThat((estado as PaymentState.SelectingMerchant).motivoSinTarjeta).isEqualTo(MOTIVO_SIN_TARJETA)
            // Sin red no se intenta verificar las cuentas: el efectivo no las necesita.
            coVerify(exactly = 0) { cuentas.refreshMerchants() }
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `con red pero cuentas de respaldo que no se pueden refrescar ⇒ sólo efectivo (no Error)`() = runTest {
        every { cuentas.isUsingFallback() } returns true
        coEvery { cuentas.refreshMerchants() } returns Result.failure(RuntimeException("HTTP 503"))
        val viewModel = blumon()
        try {
            viewModel.submitAmountDirectToMerchant("100.00")

            val estado = esperarMetodoDePago(viewModel)
            assertThat(estado).isInstanceOf(PaymentState.SelectingMerchant::class.java)
            assertThat((estado as PaymentState.SelectingMerchant).motivoSinTarjeta).isEqualTo(MOTIVO_SIN_TARJETA)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `con red y cuentas reales ⇒ motivoSinTarjeta null`() = runTest {
        val viewModel = blumon()
        try {
            viewModel.submitAmountDirectToMerchant("100.00")

            val estado = esperarMetodoDePago(viewModel)
            assertThat(estado).isInstanceOf(PaymentState.SelectingMerchant::class.java)
            assertThat((estado as PaymentState.SelectingMerchant).motivoSinTarjeta).isNull()
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `con soloEfectivo y red ⇒ sólo efectivo y el efectivo se registra sin cuenta ni refresco de cuentas`() = runTest {
        // Con red: lo único que pide «sólo efectivo» es el argumento del cobro. (Que la NAVEGACIÓN no espere al lector
        // se prueba en las reglas puras de abajo: cobrarSaltaElLector / rutaDePagoEsperaAlLector.)
        val viewModel = blumon()
        try {
            viewModel.setSoloEfectivo(true)
            viewModel.submitAmountDirectToMerchant("100.00")

            val estado = esperarMetodoDePago(viewModel)
            assertThat((estado as PaymentState.SelectingMerchant).motivoSinTarjeta).isEqualTo(MOTIVO_SIN_TARJETA)

            val contexto = slot<PaymentContext>()
            coEvery {
                registrarPago(context = capture(contexto), cardDetails = any(), authorizationNumber = any(), referenceNumber = any())
            } returns Result.success(recibo())
            viewModel.processCashPayment("100.00")

            coVerify(timeout = 5000) {
                registrarPago(context = any(), cardDetails = any(), authorizationNumber = any(), referenceNumber = any())
            }
            assertThat(contexto.captured.merchantAccountId).isNull()
            coVerify(exactly = 0) { cuentas.refreshMerchants() }
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `entra sin red con sólo efectivo y al volver la red la tarjeta se vuelve a encender`() = runTest {
        sinRed()
        val viewModel = blumon()
        try {
            viewModel.submitAmountDirectToMerchant("100.00")
            val estado = esperarMetodoDePago(viewModel)
            assertThat((estado as PaymentState.SelectingMerchant).motivoSinTarjeta).isEqualTo(MOTIVO_SIN_TARJETA)

            conRed()
            esperarQue("la tarjeta se vuelve a encender al volver la red") {
                (viewModel.state.value as? PaymentState.SelectingMerchant)?.motivoSinTarjeta == null
            }
            assertThat(viewModel.state.value).isInstanceOf(PaymentState.SelectingMerchant::class.java)

            sinRed()
            esperarQue("se vuelve a apagar si la red se cae otra vez") {
                (viewModel.state.value as? PaymentState.SelectingMerchant)?.motivoSinTarjeta == MOTIVO_SIN_TARJETA
            }
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `con soloEfectivo el motivo es fijo aunque vuelva la red`() = runTest {
        sinRed()
        val viewModel = blumon()
        try {
            viewModel.setSoloEfectivo(true)
            viewModel.submitAmountDirectToMerchant("100.00")
            esperarMetodoDePago(viewModel)

            conRed()
            Thread.sleep(200)
            assertThat((viewModel.state.value as PaymentState.SelectingMerchant).motivoSinTarjeta).isEqualTo(MOTIVO_SIN_TARJETA)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    // ── Cobro que mandó el POS (SOCKET): no ofrece efectivo ⇒ se conservan los caminos Error de antes (reloj H.3) ──

    @Test
    fun `cobro remoto con cuentas de respaldo que no se refrescan ⇒ Error con Reintentar como hoy`() = runTest {
        every { cuentas.isUsingFallback() } returns true
        coEvery { cuentas.refreshMerchants() } returns Result.failure(RuntimeException("HTTP 503"))
        val viewModel = blumon()
        try {
            viewModel.setSocketPaymentSource("SOCKET", "req-remoto-1")
            viewModel.submitAmountDirectToMerchant("100.00")

            val estado = esperarMetodoDePago(viewModel)
            assertThat(estado).isInstanceOf(PaymentState.Error::class.java)
            estado as PaymentState.Error
            assertThat(estado.message).startsWith("No se pudieron cargar las cuentas de pago.")
            assertThat(estado.canRetry).isTrue()
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `cobro remoto sin red ⇒ sin motivo y el toque de Tarjeta da Error con Reintentar como hoy`() = runTest {
        sinRed()
        val viewModel = blumon()
        try {
            viewModel.setSocketPaymentSource("SOCKET", "req-remoto-2")
            viewModel.submitAmountDirectToMerchant("100.00")

            val estado = esperarMetodoDePago(viewModel)
            assertThat((estado as PaymentState.SelectingMerchant).motivoSinTarjeta).isNull()

            viewModel.startPayment("100.00")
            esperarQue("el toque de Tarjeta sin red termina en Error") { viewModel.state.value is PaymentState.Error }
            val error = viewModel.state.value as PaymentState.Error
            assertThat(error.canRetry).isTrue()
            assertThat(error.showCashFallback).isTrue()
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Nexgo (AngelPay)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun nexgo(): AngelPayPaymentViewModel = AngelPayPaymentViewModel(
        appContext = mockk(relaxed = true),
        recordPaymentUseCase = mockk(relaxed = true),
        shiftRepository = mockk(relaxed = true),
        authRepository = mockk(relaxed = true) {
            every { getVenueId() } returns "venue-test-001"
            every { getStaffId() } returns "staff-test-001"
        },
        merchantRepository = mockk(relaxed = true) {
            every { getActiveMerchants() } returns MutableStateFlow<List<MerchantAccount>>(emptyList())
        },
        merchantEligibilityRepository = mockk(relaxed = true),
        secureStorage = mockk(relaxed = true),
        terminalConfigRepository = mockk(relaxed = true),
        intentBuilder = mockk(relaxed = true),
        sdkGateway = mockk(relaxed = true),
        angelPayAuthRepository = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(
                com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayAuthState.Authenticated
            )
        },
        angelPayMerchantRepository = mockk(relaxed = true) {
            every { activeAngelPayMerchantId } returns MutableStateFlow(null)
            every { inFlightSwitch } returns MutableStateFlow(null)
            every { observeCachedMerchants() } returns MutableStateFlow(emptyList())
        },
        paymentStateHolder = mockk(relaxed = true),
        // Sin turnos y sin pantallas de calificación/propina: initPayment va directo a «Método de pago».
        tpvSettingsRepository = mockk(relaxed = true) {
            every { getCurrentSettings() } returns TpvSettings(enableShifts = false, showReviewScreen = false, showTipScreen = false)
        },
        printerManager = mockk(relaxed = true),
        angelPayTicketBuilder = mockk(relaxed = true),
        paymentApiService = mockk(relaxed = true),
        apiService = mockk(relaxed = true),
        socketManager = mockk(relaxed = true) { every { events } returns MutableSharedFlow() },
        verificationUploadManager = mockk(relaxed = true),
        observability = mockk(relaxed = true),
        paymentQueueRepository = mockk(relaxed = true),
        paymentAttemptLedger = mockk(relaxed = true) {
            coEvery { cobroSinResolver() } returns null
            coEvery { intentoDeLaSolicitud(any()) } returns null
            coEvery { cercaDeSolicitud(any()) } returns CercaDeSolicitud.LIBRE
            coEvery { iniciarEjecucionNoTarjeta(any()) } returns CercaDeSolicitud.LIBRE
        },
        authAttemptTelemetryStore = mockk(relaxed = true),
        angelPayAuthRecovery = mockk(relaxed = true),
        chargeVerifier = mockk(relaxed = true),
        ledgerServerRecovery = mockk(relaxed = true),
        attemptApi = mockk(relaxed = true),
        savedStateHandle = SavedStateHandle(),
        connectionStateManager = conexion,
        ventaLocalRepository = mockk(relaxed = true),
        pendingPaymentDao = mockk(relaxed = true),
        subirVentaGuardadaUseCase = mockk(relaxed = true),
        impresoraDeVentaGuardada = mockk(relaxed = true),
    )

    private fun esperarMetodoDePago(viewModel: AngelPayPaymentViewModel, timeoutMs: Long = 5000): AngelPayPaymentState {
        val limite = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < limite) {
            val estado = viewModel.state.value
            if (estado is AngelPayPaymentState.SelectingMerchant || estado is AngelPayPaymentState.Error) return estado
            Thread.sleep(20)
        }
        throw AssertionError("«Método de pago» no quedó listo. Estado=${viewModel.state.value}")
    }

    @Test
    fun `Nexgo sin red ⇒ bloqueo de tarjeta y cripto con el texto`() = runTest {
        sinRed()
        val viewModel = nexgo()
        try {
            viewModel.initPayment(amount = "100.00", skipReview = true)

            val estado = esperarMetodoDePago(viewModel)
            assertThat(estado).isInstanceOf(AngelPayPaymentState.SelectingMerchant::class.java)
            assertThat((estado as AngelPayPaymentState.SelectingMerchant).motivoSinTarjeta).isEqualTo(MOTIVO_SIN_TARJETA)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `Nexgo con soloEfectivo ⇒ sólo efectivo aunque haya red`() = runTest {
        val viewModel = nexgo()
        try {
            viewModel.initPayment(amount = "100.00", skipReview = true, soloEfectivo = true)

            val estado = esperarMetodoDePago(viewModel)
            assertThat((estado as AngelPayPaymentState.SelectingMerchant).motivoSinTarjeta).isEqualTo(MOTIVO_SIN_TARJETA)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `Nexgo con red ⇒ motivoSinTarjeta null`() = runTest {
        val viewModel = nexgo()
        try {
            viewModel.initPayment(amount = "100.00", skipReview = true)

            val estado = esperarMetodoDePago(viewModel)
            assertThat((estado as AngelPayPaymentState.SelectingMerchant).motivoSinTarjeta).isNull()
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `Nexgo con internet pero sin servidor de Avoqado ⇒ tarjeta habilitada (paridad con su toque)`() = runTest {
        // El toque de Tarjeta de Nexgo no revisa la conexión con el servidor: AngelPay sólo necesita internet.
        conexion.setServerConnected(false)
        val viewModel = nexgo()
        try {
            viewModel.initPayment(amount = "100.00", skipReview = true)

            val estado = esperarMetodoDePago(viewModel)
            assertThat((estado as AngelPayPaymentState.SelectingMerchant).motivoSinTarjeta).isNull()
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `Nexgo entra sin red y al volver la red la tarjeta se vuelve a encender`() = runTest {
        sinRed()
        val viewModel = nexgo()
        try {
            viewModel.initPayment(amount = "100.00", skipReview = true)
            val estado = esperarMetodoDePago(viewModel)
            assertThat((estado as AngelPayPaymentState.SelectingMerchant).motivoSinTarjeta).isEqualTo(MOTIVO_SIN_TARJETA)

            conRed()
            esperarQue("la tarjeta de Nexgo se vuelve a encender al volver la red") {
                (viewModel.state.value as? AngelPayPaymentState.SelectingMerchant)?.motivoSinTarjeta == null
            }
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `Nexgo cobro remoto sin red ⇒ sin motivo, como hoy`() = runTest {
        sinRed()
        val viewModel = nexgo()
        try {
            viewModel.setSocketPaymentSource("SOCKET", "req-remoto-3")
            viewModel.initPayment(amount = "100.00", skipReview = true)

            val estado = esperarMetodoDePago(viewModel)
            assertThat((estado as AngelPayPaymentState.SelectingMerchant).motivoSinTarjeta).isNull()
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Reglas puras (CobroSinRed.kt): las comparten la navegación y los dos rieles
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `tarjetaSinRed es la regla del toque de Tarjeta en Blumon`() {
        // Por defecto (exige el servidor de Avoqado): hace falta la conexión completa.
        assertThat(tarjetaSinRed(requiereServidorDeAvoqado = true, esCobro = true, hayInternet = true, conexionCompleta = false)).isTrue()
        assertThat(tarjetaSinRed(requiereServidorDeAvoqado = true, esCobro = true, hayInternet = true, conexionCompleta = true)).isFalse()
        // Negocio que no exige el servidor: en un cobro basta internet…
        assertThat(tarjetaSinRed(requiereServidorDeAvoqado = false, esCobro = true, hayInternet = true, conexionCompleta = false)).isFalse()
        assertThat(tarjetaSinRed(requiereServidorDeAvoqado = false, esCobro = true, hayInternet = false, conexionCompleta = false)).isTrue()
        // …pero no en una orden.
        assertThat(tarjetaSinRed(requiereServidorDeAvoqado = false, esCobro = false, hayInternet = true, conexionCompleta = false)).isTrue()
    }

    @Test
    fun `decidirMotivoSinTarjeta - soloEfectivo manda, el remoto conserva lo de antes`() {
        assertThat(decidirMotivoSinTarjeta(soloEfectivo = true, ofreceEfectivo = true, tarjetaSinRed = false, cuentasDeRespaldo = false))
            .isEqualTo(MOTIVO_SIN_TARJETA)
        assertThat(decidirMotivoSinTarjeta(soloEfectivo = false, ofreceEfectivo = true, tarjetaSinRed = true, cuentasDeRespaldo = false))
            .isEqualTo(MOTIVO_SIN_TARJETA)
        assertThat(decidirMotivoSinTarjeta(soloEfectivo = false, ofreceEfectivo = true, tarjetaSinRed = false, cuentasDeRespaldo = true))
            .isEqualTo(MOTIVO_SIN_TARJETA)
        assertThat(decidirMotivoSinTarjeta(soloEfectivo = false, ofreceEfectivo = true, tarjetaSinRed = false, cuentasDeRespaldo = false))
            .isNull()
        // Cobro del POS (no ofrece efectivo): ni la red ni las cuentas de respaldo lo pasan a «sólo efectivo».
        assertThat(decidirMotivoSinTarjeta(soloEfectivo = false, ofreceEfectivo = false, tarjetaSinRed = true, cuentasDeRespaldo = true))
            .isNull()
    }

    @Test
    fun `cobrarSaltaElLector sólo cuando esperar no serviría`() {
        // PAX, lector sin preparar y la tarjeta sin red ⇒ sólo efectivo, sin esperar.
        assertThat(cobrarSaltaElLector(hayLectorBlumon = true, lectorListo = false, tarjetaSinRed = true)).isTrue()
        // Con red la tarjeta espera al lector como siempre.
        assertThat(cobrarSaltaElLector(hayLectorBlumon = true, lectorListo = false, tarjetaSinRed = false)).isFalse()
        // Lector listo: no hay espera que saltar; la pantalla decide sola.
        assertThat(cobrarSaltaElLector(hayLectorBlumon = true, lectorListo = true, tarjetaSinRed = true)).isFalse()
        // Nexgo no tiene lector Blumon.
        assertThat(cobrarSaltaElLector(hayLectorBlumon = false, lectorListo = false, tarjetaSinRed = true)).isFalse()
    }

    @Test
    fun `rutaDePagoEsperaAlLector - no con un cobro en sólo efectivo`() {
        assertThat(rutaDePagoEsperaAlLector(hayLectorBlumon = true, lectorListo = false, soloEfectivo = true)).isFalse()
        assertThat(rutaDePagoEsperaAlLector(hayLectorBlumon = true, lectorListo = false, soloEfectivo = false)).isTrue()
        assertThat(rutaDePagoEsperaAlLector(hayLectorBlumon = true, lectorListo = true, soloEfectivo = false)).isFalse()
        assertThat(rutaDePagoEsperaAlLector(hayLectorBlumon = false, lectorListo = false, soloEfectivo = false)).isFalse()
    }
}

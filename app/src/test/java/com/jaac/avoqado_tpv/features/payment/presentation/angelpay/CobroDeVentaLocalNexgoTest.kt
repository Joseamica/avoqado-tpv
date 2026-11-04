package com.jaac.avoqado_tpv.features.payment.presentation.angelpay

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.angelpay.angelpaysdk.AngelPaySDK
import com.angelpay.angelpaysdk.models.PrintTicketRequest
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity
import com.jaac.avoqado_tpv.core.domain.TerminalConfig
import com.jaac.avoqado_tpv.core.util.ConnectionStateManager
import com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.checkout.data.ImpresoraDeVentaGuardada
import com.jaac.avoqado_tpv.features.checkout.data.VentaLocalRepository
import com.jaac.avoqado_tpv.features.checkout.domain.model.ExtraDeTicket
import com.jaac.avoqado_tpv.features.checkout.domain.model.RenglonDeTicket
import com.jaac.avoqado_tpv.features.checkout.domain.model.VentaLocal
import com.jaac.avoqado_tpv.features.payment.data.api.VentaGuardadaApi
import com.jaac.avoqado_tpv.features.payment.data.ledger.CercaDeSolicitud
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayAuthState
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPaySdkGateway
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayTicketBuilder
import com.jaac.avoqado_tpv.features.payment.data.repository.PaymentQueueRepositoryImpl
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings
import com.jaac.avoqado_tpv.features.payment.domain.MOTIVO_MONTO_CERO
import com.jaac.avoqado_tpv.features.payment.domain.referenciaDeEfectivo
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordPaymentUseCase
import com.jaac.avoqado_tpv.features.payment.domain.usecase.SubirVentaGuardadaUseCase
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.IOException
import java.math.BigDecimal
import javax.inject.Provider

/** VM real -> escritor Room único -> subidor real; ticket final entregado al SDK, sin banco ni merchants. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class CobroDeVentaLocalNexgoTest {
    private lateinit var context: Context
    private lateinit var db: AvoqadoDatabase
    private lateinit var dao: PendingPaymentDao
    private lateinit var escritor: VentaLocalRepository
    private lateinit var api: VentaGuardadaApi
    private lateinit var registrar: RecordPaymentUseCase
    private lateinit var auth: AuthRepository
    private lateinit var sdk: AngelPaySdkGateway
    private lateinit var storage: SecureStorage
    private lateinit var impresora: ImpresoraDeVentaGuardada
    private lateinit var shiftRepo: com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
    private var settings = TpvSettings(enableShifts = false, showReviewScreen = false, showTipScreen = false)
    private lateinit var vm: AngelPayPaymentViewModel
    private val tickets = mutableListOf<PrintTicketRequest>()
    private val vms = mutableListOf<AngelPayPaymentViewModel>()
    private val conexion = ConnectionStateManager()
    private val llave = "c000000000000000000000001"
    private val venta = VentaLocal(
        llave, "venue-1", "staff-1", "turno-original", 1_760_000_000_000L, 10000,
        """{ "externalId":"$llave", "total":10000, "items":[] }""",
        listOf(RenglonDeTicket("$llave:0", "Blusa", 1, 12000, 12000,
            listOf(ExtraDeTicket("extra-1", "Bolsa", 500, "MULTIPLE_CHOICE")), "Para regalo")),
    )

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AvoqadoDatabase::class.java).allowMainThreadQueries().build()
        dao = spyk(db.pendingPaymentDao())
        escritor = VentaLocalRepository(dao, shiftRepository = com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture())
        api = mockk()
        coEvery { api.crearOrden(any(), any()) } throws IOException("sin conexión")
        registrar = mockk()
        coEvery { registrar(context = any(), cardDetails = any(), authorizationNumber = any(), referenceNumber = any()) } returns
            Result.success(PaymentReceipt("payment-1", "https://example.invalid/receipt", "access", BigDecimal("100.00"), BigDecimal.ZERO))
        auth = mockk(relaxed = true) {
            every { getVenueId() } returns "venue-1"
            every { getStaffId() } returns "staff-1"
        }
        storage = mockk(relaxed = true) {
            every { getVenueName() } returns "Tienda"
            every { getVenueTimezone() } returns "America/Mexico_City"
        }
        mockkObject(TerminalConfig)
        every { TerminalConfig.serialNumber } returns "N86-QA"
        mockkObject(PaymentSyncScheduler)
        every { PaymentSyncScheduler.runNow(any()) } just runs
        mockkObject(AngelPaySDK)
        every { AngelPaySDK.getDeviceInfo() } returns mockk(relaxed = true) { every { model } returns "N86" }
        sdk = mockk()
        coEvery { sdk.printTicket(any()) } answers { tickets += firstArg<PrintTicketRequest>(); Result.success(Unit) }
        impresora = ImpresoraDeVentaGuardada(mockk(relaxed = true), AngelPayTicketBuilder(), Provider { sdk }, storage)
        conexion.setInternetConnected(false)
        conexion.setServerConnected(false)
        shiftRepo = mockk(relaxed = true)
        vm = crearVM()
    }

    @After fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        db.close()
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun crearVM() = AngelPayPaymentViewModel(
        appContext = context, recordPaymentUseCase = registrar, shiftRepository = shiftRepo, authRepository = auth,
        merchantRepository = mockk(relaxed = true) { every { getActiveMerchants() } returns MutableStateFlow(emptyList()) },
        merchantEligibilityRepository = mockk(relaxed = true), secureStorage = storage,
        terminalConfigRepository = mockk(relaxed = true) { every { getCachedAngelPayAuth() } returns null },
        intentBuilder = mockk(relaxed = true), sdkGateway = sdk,
        angelPayAuthRepository = mockk(relaxed = true) { every { state } returns MutableStateFlow<AngelPayAuthState>(AngelPayAuthState.Unauthenticated) },
        angelPayMerchantRepository = mockk(relaxed = true) {
            every { activeAngelPayMerchantId } returns MutableStateFlow(null)
            every { inFlightSwitch } returns MutableStateFlow(null)
            every { observeCachedMerchants() } returns MutableStateFlow(emptyList())
        },
        paymentStateHolder = mockk(relaxed = true),
        tpvSettingsRepository = mockk(relaxed = true) {
            every { getCurrentSettings() } answers { this@CobroDeVentaLocalNexgoTest.settings }
        },
        printerManager = mockk(relaxed = true), angelPayTicketBuilder = AngelPayTicketBuilder(),
        paymentApiService = mockk(relaxed = true), apiService = mockk(relaxed = true),
        socketManager = mockk(relaxed = true) { every { events } returns MutableSharedFlow() },
        observability = mockk(relaxed = true),
        paymentQueueRepository = PaymentQueueRepositoryImpl(dao),
        paymentAttemptLedger = mockk(relaxed = true) {
            coEvery { cobroSinResolver() } returns null
            coEvery { intentoDeLaSolicitud(any()) } returns null
            coEvery { cercaDeSolicitud(any()) } returns CercaDeSolicitud.LIBRE
            coEvery { iniciarEjecucionNoTarjeta(any()) } returns CercaDeSolicitud.LIBRE
        },
        chargeVerifier = mockk(relaxed = true), authAttemptTelemetryStore = mockk(relaxed = true), angelPayAuthRecovery = mockk(relaxed = true),
        ledgerServerRecovery = mockk(relaxed = true), attemptApi = mockk(), savedStateHandle = SavedStateHandle(), connectionStateManager = conexion,
        ventaLocalRepository = escritor, pendingPaymentDao = dao, subirVentaGuardadaUseCase = SubirVentaGuardadaUseCase(api, dao, context),
        impresoraDeVentaGuardada = impresora,
    ).also { vms += it }

    private fun iniciar(v: VentaLocal = venta, tip: Long = 0L) {
        vm.initPayment(BigDecimal.valueOf(v.totalCents.toLong(), 2).toPlainString(), skipReview = true,
            externalTipCents = tip, soloEfectivo = true, ventaLocalJson = Gson().toJson(v))
        esperar("No terminó initPayment") { vm.state.value !is AngelPayPaymentState.Idle }
    }
    private fun esperar(mensaje: String, condicion: () -> Boolean) {
        val limite = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < limite) { if (condicion()) return; Thread.sleep(10) }
        throw AssertionError("$mensaje; estado=${vm.state.value}")
    }
    private fun fin() = esperar("Sin desenlace") { vm.state.value is AngelPayPaymentState.Success || vm.state.value is AngelPayPaymentState.Error }
    private suspend fun fila() = dao.findByReference(referenciaDeEfectivo(llave), "venue-1")
    private fun cobrar(v: VentaLocal = venta, tip: Long = 0L) { iniciar(v, tip); vm.startCashPayment(); fin() }
    private fun json(s: String) = JsonParser.parseString(s).asJsonObject

    @Test fun `Room ya contiene venta dinero turno y ticket antes de HTTP`() = runTest {
        coEvery { api.crearOrden("venue-1", any()) } coAnswers {
            val f = fila()
            assertThat(f).isNotNull()
            assertThat(f!!.orderRequestJson).isEqualTo(venta.pedidoJson)
            assertThat(f.shiftId).isEqualTo("turno-original")
            assertThat(f.tip).isEqualTo("5.00")
            assertThat(f.paymentProcessor).isEqualTo("ANGELPAY")
            assertThat(f.claimToken).isNotEmpty()
            assertThat(f.exitoMostrado).isFalse()
            throw IOException("sin conexión")
        }
        cobrar(tip = 500)
        assertThat(fila()).isNotNull()
        coVerifyOrder { dao.insert(any()); api.crearOrden(any(), any()) }
        coVerify(exactly = 0) { registrar(context = any(), cardDetails = any(), authorizationNumber = any(), referenceNumber = any()) }
    }

    @Test fun `sin red devuelve exito de efectivo con renglones extras y aviso`() = runTest {
        cobrar(tip = 500)
        val s = vm.state.value as AngelPayPaymentState.Success
        assertThat(s.isCash).isTrue()
        assertThat(s.receipt).isNull()
        assertThat(s.amount).isEqualTo("100.00")
        assertThat(s.tipAmount).isEqualTo("5.00")
        assertThat(s.pendingSyncMessage).isEqualTo("Venta guardada sin conexión: se manda sola al volver el internet")
        assertThat(s.referenceNumber).isEqualTo("CASH-0000000000000001")
        assertThat(s.orderItems).isNotNull()
        assertThat(s.orderItems!!.single().productName).isEqualTo("Blusa")
        assertThat(s.orderItems!!.single().modifiers.single().name).isEqualTo("Bolsa")
        assertThat(fila()!!.syncStatus).isEqualTo("PENDING")
        assertThat(fila()!!.exitoMostrado).isFalse()
    }

    @Test fun `dos toques guardan y suben una sola fila`() = runTest {
        val espera = CompletableDeferred<Response<com.google.gson.JsonObject>>()
        coEvery { api.crearOrden(any(), any()) } coAnswers { espera.await() }
        iniciar(); vm.startCashPayment(); vm.startCashPayment()
        esperar("No llegó a mobile") { kotlinx.coroutines.runBlocking { fila() != null } }
        espera.completeExceptionally(IOException("sin red")); fin()
        coVerify(exactly = 1) { dao.insert(any()) }
        coVerify(exactly = 1) { api.crearOrden(any(), any()) }
    }

    @Test fun `reentrada conserva JSON turno importe ticket y claim originales sin otro POST`() = runTest {
        escritor.guardar(venta, "ANGELPAY", "", "N86-QA", reclamada = true, propinaCents = 500).getOrThrow()
        val antes = fila()!!
        cobrar(venta.copy(shiftId = "turno-nuevo", renglones = venta.renglones.map { it.copy(nombre = "CATALOGO NUEVO") }), 500)
        assertThat(fila()).isEqualTo(antes)
        val s = vm.state.value as AngelPayPaymentState.Success
        assertThat(s.orderItems).isNotNull()
        assertThat(s.orderItems!!.single().productName).isEqualTo("Blusa")
        coVerify(exactly = 0) { api.crearOrden(any(), any()) }
    }

    @Test fun `misma llave con cuerpo distinto falla visible sin rescate fast`() = runTest {
        escritor.guardar(venta, "ANGELPAY", "", "N86-QA").getOrThrow()
        cobrar(venta.copy(pedidoJson = venta.pedidoJson + " "))
        assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
        assertThat(fila()!!.orderRequestJson).isEqualTo(venta.pedidoJson)
        coVerify(exactly = 0) { registrar(context = any(), cardDetails = any(), authorizationNumber = any(), referenceNumber = any()) }
    }

    @Test fun `falla de persistencia no llama red ni pinta cobrado`() = runTest {
        coEvery { dao.insert(any()) } throws IOException("disco lleno")
        cobrar()
        assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
        coVerify(exactly = 0) { api.crearOrden(any(), any()) }
        coVerify(exactly = 0) { registrar(context = any(), cardDetails = any(), authorizationNumber = any(), referenceNumber = any()) }
    }

    @Test fun `rechazo permanente deja dinero FAILED y mensaje de revision`() = runTest {
        coEvery { api.crearOrden(any(), any()) } returns Response.error(422, "datos inválidos".toResponseBody("text/plain".toMediaType()))
        cobrar()
        assertThat(fila()!!.syncStatus).isEqualTo("FAILED")
        assertThat((vm.state.value as AngelPayPaymentState.Success).pendingSyncMessage).contains("necesita revisión")
    }

    @Test fun `morir despues del insert conserva venta recuperable sin tomar efectivo otra vez`() = runTest {
        val llego = CompletableDeferred<Unit>()
        coEvery { api.crearOrden(any(), any()) } coAnswers { llego.complete(Unit); throw CancellationException("muere") }
        iniciar(); vm.startCashPayment()
        esperar("No llegó a red") { llego.isCompleted }
        vm.viewModelScope.cancel()
        val antes = fila()!!
        assertThat(dao.ultimaVentaSinMostrar("venue-1")!!.id).isEqualTo(antes.id)
        vm = crearVM(); cobrar()
        assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Success::class.java)
        assertThat(fila()).isEqualTo(antes)
        coVerify(exactly = 1) { api.crearOrden(any(), any()) }
    }

    @Test fun `respuesta tardia tras reset no pinta otra venta`() = runTest {
        val espera = CompletableDeferred<Response<com.google.gson.JsonObject>>()
        val llego = CompletableDeferred<Unit>()
        coEvery { api.crearOrden(any(), any()) } coAnswers { llego.complete(Unit); withContext(NonCancellable) { espera.await() } }
        iniciar(); vm.startCashPayment()
        esperar("No llegó a red") { llego.isCompleted }
        vm.resetPayment(); iniciar(otraVenta())
        val nueva = vm.state.value
        espera.completeExceptionally(IOException("tarde"))
        esperar("No soltó la fila") { kotlinx.coroutines.runBlocking { fila()?.syncStatus == "PENDING" } }
        assertThat(vm.state.value).isEqualTo(nueva)
    }

    @Test fun `cero PAID recuperado omite pago y queda SUCCESS`() = runTest {
        coEvery { api.crearOrden(any(), any()) } returns Response.success(json("""{"success":true,"order":{"id":"o1","total":0,"paymentStatus":"PAID","items":[{"externalId":"$llave:0","total":120.00}]}}"""))
        cobrar(venta.copy(totalCents = 0))
        assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Success::class.java)
        assertThat(fila()!!.syncStatus).isEqualTo("SUCCESS")
        coVerify(exactly = 0) { api.pagar(any(), any(), any()) }
    }

    @Test fun `cero PENDING se paga con la llave turno e importe guardados`() = runTest {
        coEvery { api.crearOrden(any(), any()) } returns Response.success(json("""{"success":true,"order":{"id":"o1","total":0,"paymentStatus":"PENDING"}}"""))
        coEvery { api.pagar(any(), any(), any()) } returns Response.success(json("""{"success":true,"payment":{"paymentId":"p1","orderId":"o1","status":"COMPLETED","orderTotalCents":0}}"""))
        cobrar(venta.copy(totalCents = 0))
        coVerify(exactly = 1) { api.pagar("venue-1", "o1", match { it.amount == 0 && it.tip == 0 && it.idempotencyKey == llave && it.shiftIdDeLaVenta == "turno-original" }) }
        assertThat(fila()!!.syncStatus).isEqualTo("SUCCESS")
    }

    @Test fun `referencia de efectivo legacy sale de la misma llave que registra`() = runTest {
        vm.initPayment("100.00", skipReview = true); vm.startCashPayment(); fin()
        coVerify(exactly = 1) { registrar(context = match { it is PaymentContext.AngelPayPayment && it.referenceNumber == referenciaDeEfectivo(it.idempotencyKey!!) },
            cardDetails = any(), authorizationNumber = "EFECTIVO", referenceNumber = match { it.startsWith("CASH-") && it.length == 21 }) }
    }

    @Test fun `boton Imprimir con receipt null entrega payload final con propina descuento notas y leyenda`() = runTest {
        Assume.assumeFalse(BuildConfig.ENABLE_PAX_SDK)
        cobrar(tip = 500)
        assertThat((vm.state.value as AngelPayPaymentState.Success).receipt).isNull()
        vm.printReceipt(null)
        esperar("No entregó ticket al SDK") { tickets.isNotEmpty() }
        comprobarTicket(tickets.single(), "\$105.00 MXN", "\$5.00 MXN")
    }

    @Test fun `impresion de cero usa mismo payload con leyenda y sin folio QR ni red`() = runTest {
        Assume.assumeFalse(BuildConfig.ENABLE_PAX_SDK)
        impresora.imprimir(venta.copy(totalCents = 0)).getOrThrow()
        comprobarTicket(tickets.single(), "\$0.00 MXN", null)
        coVerify(exactly = 0) { api.crearOrden(any(), any()) }
    }

    @Test fun `misma llave con otra propina nunca vuelve a tomar dinero`() = runTest {
        escritor.guardar(venta, "ANGELPAY", "", "N86-QA").getOrThrow()
        cobrar(tip = 500)
        assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
        assertThat(fila()!!.tip).isEqualTo("0.00")
        coVerify(exactly = 0) { api.crearOrden(any(), any()) }
        coVerify(exactly = 0) { registrar(context = any(), cardDetails = any(), authorizationNumber = any(), referenceNumber = any()) }
    }

    @Test fun `importe del argumento distinto al pedido bloquea antes del efectivo`() = runTest {
        vm.initPayment("1.00", skipReview = true, ventaLocalJson = Gson().toJson(venta))
        vm.startCashPayment(); fin()
        assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
        assertThat(fila()).isNull()
        coVerify(exactly = 0) { registrar(context = any(), cardDetails = any(), authorizationNumber = any(), referenceNumber = any()) }
    }

    @Test fun `JSON ilegible no se reemplaza por venta rapida`() = runTest {
        vm.initPayment("100.00", skipReview = true, ventaLocalJson = "{")
        vm.startCashPayment(); fin()
        assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
        coVerify(exactly = 0) { registrar(context = any(), cardDetails = any(), authorizationNumber = any(), referenceNumber = any()) }
    }

    @Test fun `venta de otra sesion se bloquea antes de persistir`() = runTest {
        cobrar(venta.copy(staffId = "otro-staff"))
        assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
        assertThat(fila()).isNull()
        coVerify(exactly = 0) { api.crearOrden(any(), any()) }
    }

    @Test fun `cableado local llega de navegacion al efecto con identidad`() {
        val nav = java.io.File("src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt").readText()
            .substringAfter("composable(NavRoute.AngelPayPayment.route)")
        assertThat(nav.substringBefore("onNavigateBack =")).contains("ventaLocalJson =")
        val screen = java.io.File("src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentScreen.kt").readText()
        val effect = screen.substringBefore("viewModel.initPayment(").substringAfterLast("LaunchedEffect(")
        assertThat(effect.substringBefore(") {")).contains("ventaLocalJson")
        assertThat(screen.substringAfter("viewModel.initPayment(").substringBefore("\n            )")).contains("ventaLocalJson = ventaLocalJson")
    }

    @Test fun `sin cuentas ni SDK listo init cobra con la caja guardada y conserva el turno de venta`() = runTest {
        settings = settings.copy(enableShifts = true)
        coEvery { shiftRepo.getCurrentShift("venue-1") } returns com.jaac.avoqado_tpv.core.domain.models.Result.Error(
            com.jaac.avoqado_tpv.core.domain.models.ApiException.NetworkError(java.net.ConnectException("sin red")),
        )
        coEvery { shiftRepo.getCachedOpenShift("venue-1") } returns mockk(relaxed = true) {
            every { id } returns "otro-turno-en-cache"
        }
        cobrar()
        assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Success::class.java)
        assertThat(fila()!!.shiftId).isEqualTo("turno-original")
        assertThat(vm.merchants.value).isEmpty()
        assertThat(vm.currentMerchant.value).isNull()
        coVerify(exactly = 1) { shiftRepo.getCachedOpenShift("venue-1") }
    }

    @Test fun `reset antes del insert conserva la seleccion del toque y no pinta la siguiente venta`() = runTest {
        iniciar(tip = 500)
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        vm.startCashPayment()
        vm.resetPayment()
        vm.initPayment("200.00", skipReview = true, ventaLocalJson = Gson().toJson(otraVenta()))
        runCurrent()
        esperar("Se perdió la venta aceptada antes del insert") { kotlinx.coroutines.runBlocking { fila()?.syncStatus == "PENDING" } }
        runCurrent()
        assertThat(fila()!!.amount).isEqualTo("100.00")
        assertThat(fila()!!.tip).isEqualTo("5.00")
        assertThat((vm.state.value as AngelPayPaymentState.SelectingMerchant).subtotal).isEqualTo("200.00")
        coVerify(exactly = 1) { dao.insert(any()) }
    }

    @Test fun `reset mientras Room guarda no pierde la fila ni toma datos de otra venta`() = runTest {
        val entro = CompletableDeferred<Unit>()
        val seguir = CompletableDeferred<Unit>()
        coEvery { dao.insert(any()) } coAnswers { entro.complete(Unit); seguir.await(); callOriginal() }
        iniciar(tip = 500); vm.startCashPayment()
        esperar("No llegó a guardar") { entro.isCompleted }
        vm.resetPayment(); iniciar(otraVenta())
        val nueva = vm.state.value
        seguir.complete(Unit)
        esperar("No conservó la fila") { kotlinx.coroutines.runBlocking { fila()?.syncStatus == "PENDING" } }
        assertThat(fila()!!.tip).isEqualTo("5.00")
        assertThat(fila()!!.orderRequestJson).isEqualTo(venta.pedidoJson)
        assertThat(vm.state.value).isEqualTo(nueva)
    }

    @Test fun `respuesta tardia exitosa no pinta el cobro siguiente`() = runTest {
        coEvery { api.pagar(any(), any(), any()) } returns Response.success(json("""{"success":true,"payment":{"paymentId":"p1","orderId":"o1","status":"COMPLETED","orderTotalCents":10000}}"""))
        comprobarRespuestaTardia(Response.success(json("""{"success":true,"order":{"id":"o1","orderNumber":"FOLIO","total":100,"items":[{"externalId":"$llave:0","total":120}]}}""")), "SUCCESS")
    }

    @Test fun `rechazo permanente tardio conserva FAILED sin pintar error en otra venta`() = runTest {
        comprobarRespuestaTardia(Response.error(422, "datos inválidos".toResponseBody("text/plain".toMediaType())), "FAILED")
    }

    private suspend fun comprobarRespuestaTardia(resultado: Response<com.google.gson.JsonObject>, status: String) {
        val entro = CompletableDeferred<Unit>()
        val seguir = CompletableDeferred<Response<com.google.gson.JsonObject>>()
        coEvery { api.crearOrden(any(), any()) } coAnswers { entro.complete(Unit); seguir.await() }
        iniciar(); vm.startCashPayment()
        esperar("No llegó a subir") { entro.isCompleted }
        vm.resetPayment(); iniciar(otraVenta())
        val nueva = vm.state.value
        seguir.complete(resultado)
        esperar("No cerró la fila anterior") { kotlinx.coroutines.runBlocking { fila()?.syncStatus == status } }
        assertThat(vm.state.value).isEqualTo(nueva)
    }

    @Test fun `impresion tardia conserva ticket original y no contamina otra venta`() = runTest {
        Assume.assumeFalse(BuildConfig.ENABLE_PAX_SDK)
        comprobarImpresionTardia(Result.success(Unit))
    }

    @Test fun `error tardio de impresora no aparece en otra venta`() = runTest {
        Assume.assumeFalse(BuildConfig.ENABLE_PAX_SDK)
        comprobarImpresionTardia(Result.failure(IOException("sin papel")))
    }

    private suspend fun comprobarImpresionTardia(resultado: Result<Unit>) {
        val entro = CompletableDeferred<Unit>()
        val seguir = CompletableDeferred<Result<Unit>>()
        coEvery { sdk.printTicket(any()) } coAnswers {
            tickets += firstArg<PrintTicketRequest>(); entro.complete(Unit); seguir.await()
        }
        cobrar(tip = 500)
        val raiz = checkNotNull(vm.viewModelScope.coroutineContext[Job])
        val previos = raiz.children.toSet()
        vm.printReceipt(null)
        esperar("No llegó a imprimir") { entro.isCompleted }
        val impresion = raiz.children.single { it !in previos }
        vm.resetPayment(); iniciar(otraVenta())
        val nueva = vm.state.value
        seguir.complete(resultado)
        esperar("La impresión no terminó") { impresion.isCompleted }
        comprobarTicket(tickets.single(), "\$105.00 MXN", "\$5.00 MXN")
        assertThat(vm.state.value).isEqualTo(nueva)
        assertThat(vm.sendReceiptMessage.value).isNull()
    }

    private fun otraVenta(): VentaLocal {
        val key = "c000000000000000000000002"
        return venta.copy(llave = key, totalCents = 20000,
            pedidoJson = venta.pedidoJson.replace(llave, key).replace("10000", "20000"),
            renglones = venta.renglones.map { it.copy(externalId = "$key:0", totalCents = 20000, brutoCents = 20000) })
    }

    @Test fun `ticket normal de tarjeta y efectivo legacy conservan su contenido sin leyenda local`() {
        for (cash in listOf(false, true)) {
            val ticket = AngelPayTicketBuilder().buildPaymentTicket(
                amount = BigDecimal("100.00"), tipAmount = BigDecimal("5.00"), authCode = "AUTORIZADA",
                referenceNumber = "REFERENCIA", venueName = "Tienda", staffName = "Cajera", orderNumber = "F-1", cardBrand = "VISA", isCash = cash,
            )
            val text = Gson().toJson(ticket)
            assertThat(ticket.body.single { it.left == "TOTAL" }.right).isEqualTo("\$105.00 MXN")
            assertThat(text).contains("F-1")
            assertThat(text).doesNotContain("Venta guardada sin conexión")
            if (cash) assertThat(text).doesNotContain("AUTORIZADA") else assertThat(text).contains("AUTORIZADA")
        }
    }

    @Test fun `misma llave con importe guardado distinto se bloquea sin otro pago`() = runTest {
        escritor.guardar(venta, "ANGELPAY", "", "N86-QA").getOrThrow()
        cobrar(venta.copy(totalCents = 9900))
        assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Error::class.java)
        assertThat(fila()!!.amount).isEqualTo("100.00")
        coVerify(exactly = 0) { api.crearOrden(any(), any()) }
        coVerify(exactly = 0) { registrar(context = any(), cardDetails = any(), authorizationNumber = any(), referenceNumber = any()) }
    }

    @Test fun `marcar el exito mostrado y otro toque no vuelven a guardar la venta`() = runTest {
        cobrar()
        assertThat(fila()!!.exitoMostrado).isFalse()
        vm.marcarExitoDeVentaLocalMostrado()
        esperar("No marcó la venta vista") { kotlinx.coroutines.runBlocking { fila()!!.exitoMostrado } }
        vm.startCashPayment()
        coVerify(exactly = 1) { dao.insert(any()) }
        coVerify(exactly = 1) { api.crearOrden(any(), any()) }
    }

    @Test fun `ticket local ya subido conserva renglones pero no hereda folio ni QR del servidor`() = runTest {
        Assume.assumeFalse(BuildConfig.ENABLE_PAX_SDK)
        coEvery { api.crearOrden(any(), any()) } returns Response.success(json("""{"success":true,"order":{"id":"o1","orderNumber":"NO-IMPRIMIR","total":100,"items":[{"externalId":"$llave:0","total":120}]}}"""))
        coEvery { api.pagar(any(), any(), any()) } returns Response.success(json("""{"success":true,"payment":{"paymentId":"p1","orderId":"o1","status":"COMPLETED","orderTotalCents":10500}}"""))
        cobrar(tip = 500)
        assertThat(fila()!!.syncStatus).isEqualTo("SUCCESS")
        assertThat((vm.state.value as AngelPayPaymentState.Success).orderNumber).isEqualTo("NO-IMPRIMIR")
        vm.printReceipt(null)
        esperar("No imprimió la venta subida") { tickets.isNotEmpty() }
        comprobarTicket(tickets.single(), "\$105.00 MXN", "\$5.00 MXN")
        assertThat(Gson().toJson(tickets.single())).doesNotContain("NO-IMPRIMIR")
    }

    @Test fun `turno ausente de init anterior no publica error sobre el cobro siguiente`() = runTest {
        settings = settings.copy(enableShifts = true)
        val entro = CompletableDeferred<Unit>()
        val respuesta = CompletableDeferred<com.jaac.avoqado_tpv.core.domain.models.Result<com.jaac.avoqado_tpv.features.shift.domain.Shift?>>()
        coEvery { shiftRepo.getCurrentShift("venue-1") } coAnswers { entro.complete(Unit); respuesta.await() }
        val raiz = checkNotNull(vm.viewModelScope.coroutineContext[Job])
        val previos = raiz.children.toSet()
        vm.initPayment("100.00", skipReview = true, ventaLocalJson = Gson().toJson(venta))
        esperar("No consultó el turno") { entro.isCompleted }
        val inicializacion = raiz.children.single { it !in previos }
        iniciarOtroCobroConRed()
        val nueva = vm.state.value
        respuesta.complete(com.jaac.avoqado_tpv.core.domain.models.Result.Success(null))
        esperar("No terminó la inicialización vieja") { inicializacion.isCompleted }
        assertThat(vm.state.value).isEqualTo(nueva)
    }

    @Test fun `cache de turno de init anterior no cambia la disponibilidad del cobro siguiente`() = runTest {
        settings = settings.copy(enableShifts = true)
        val entro = CompletableDeferred<Unit>()
        val respuesta = CompletableDeferred<com.jaac.avoqado_tpv.features.shift.domain.Shift?>()
        coEvery { shiftRepo.getCurrentShift("venue-1") } returns com.jaac.avoqado_tpv.core.domain.models.Result.Error(
            com.jaac.avoqado_tpv.core.domain.models.ApiException.NetworkError(java.net.ConnectException("sin red")),
        )
        coEvery { shiftRepo.getCachedOpenShift("venue-1") } coAnswers { entro.complete(Unit); respuesta.await() }
        val raiz = checkNotNull(vm.viewModelScope.coroutineContext[Job])
        val previos = raiz.children.toSet()
        vm.initPayment("100.00", skipReview = true, ventaLocalJson = Gson().toJson(venta))
        esperar("No consultó la caja guardada") { entro.isCompleted }
        val inicializacion = raiz.children.single { it !in previos }
        iniciarOtroCobroConRed()
        respuesta.complete(mockk(relaxed = true) { every { id } returns "turno-tardio" })
        esperar("No terminó la lectura vieja") { inicializacion.isCompleted }
        conexion.setInternetConnected(false); conexion.setInternetConnected(true)
        assertThat((vm.state.value as AngelPayPaymentState.SelectingMerchant).motivoSinTarjeta).isNull()
    }

    // D6 (4-nov): el regalo de una SIM ($0) desde «Vender SIM» en la Nexgo daba «Monto invalido»; en la PAX se cierra en
    // efectivo. Un $0 hecho en la terminal va a «sólo efectivo» con su propio motivo; uno que manda el POS sigue rechazado.
    @Test fun `regalo de SIM en 0 pesos elige metodo en solo efectivo con su motivo`() = runTest {
        conexion.setInternetConnected(true); conexion.setServerConnected(true)
        vm.initPayment("0.00", orderId = "orden-regalo", orderNumber = "SN00099", skipReview = true)
        esperar("No terminó initPayment") { vm.state.value !is AngelPayPaymentState.Idle }
        val estado = vm.state.value
        assertThat(estado).isInstanceOf(AngelPayPaymentState.SelectingMerchant::class.java)
        assertThat((estado as AngelPayPaymentState.SelectingMerchant).motivoSinTarjeta).isEqualTo(MOTIVO_MONTO_CERO)
    }

    @Test fun `regalo de SIM en 0 pesos se registra en efectivo`() = runTest {
        conexion.setInternetConnected(true); conexion.setServerConnected(true)
        vm.initPayment("0.00", orderId = "orden-regalo", orderNumber = "SN00099", skipReview = true)
        esperar("No llegó a elegir método") { vm.state.value is AngelPayPaymentState.SelectingMerchant }
        vm.startCashPayment()
        fin()
        assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.Success::class.java)
        coVerify(exactly = 1) { registrar(context = any(), cardDetails = any(), authorizationNumber = any(), referenceNumber = any()) }
    }

    private fun iniciarOtroCobroConRed() {
        vm.resetPayment()
        settings = settings.copy(enableShifts = false)
        conexion.setInternetConnected(true); conexion.setServerConnected(true)
        vm.initPayment("200.00", skipReview = true)
        assertThat(vm.state.value).isInstanceOf(AngelPayPaymentState.SelectingMerchant::class.java)
    }

    @Test fun `el boton de impresion local no exige recibo remoto y los envios si`() {
        val screen = java.io.File("src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPaySuccessContent.kt").readText()
        val acciones = screen.substringAfter("// ── Receipt actions").substringBefore("// ── Email dialog")
        assertThat(acciones.substringBefore("val buttonShape")).contains("state.orderItems")
        assertThat(acciones.substringBefore("// 📧 Email button").substringAfterLast("if (")).contains("state.receipt != null")
    }

    private fun comprobarTicket(ticket: PrintTicketRequest, total: String, tip: String?) {
        val text = Gson().toJson(ticket)
        assertThat(text).contains("Blusa")
        assertThat(text).contains("Bolsa")
        assertThat(text).contains("Para regalo")
        assertThat(text).contains("Descuento")
        assertThat(ticket.body.single { it.left == "Subtotal" }.right).isEqualTo("\$120.00 MXN")
        assertThat(ticket.body.single { it.left == "Descuento" }.right).isEqualTo(if (total == "\$0.00 MXN") "-\$120.00 MXN" else "-\$20.00 MXN")
        assertThat(text).contains(total)
        if (tip != null) assertThat(text).contains(tip)
        assertThat(text).contains("Venta guardada sin conexión")
        assertThat(text).doesNotContain("FOLIO")
        assertThat(text).doesNotContain("IMAGE_BASE64")
        assertThat(text).doesNotContain("https://")
        assertThat(ticket.body.single { it.left == "TOTAL" }.right).isEqualTo(total)
    }
}

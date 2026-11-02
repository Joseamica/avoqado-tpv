package com.jaac.avoqado_tpv.features.payment.presentation

import android.app.Application
import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity
import com.jaac.avoqado_tpv.core.domain.TerminalConfig
import com.jaac.avoqado_tpv.core.util.ConnectionStateManager
import com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler
import com.jaac.avoqado_tpv.features.checkout.data.VentaLocalRepository
import com.jaac.avoqado_tpv.features.checkout.domain.model.ExtraDeTicket
import com.jaac.avoqado_tpv.features.checkout.domain.model.RenglonDeTicket
import com.jaac.avoqado_tpv.features.checkout.domain.model.VentaLocal
import com.jaac.avoqado_tpv.features.ordering.domain.OrderRepository
import com.jaac.avoqado_tpv.features.payment.data.api.VentaGuardadaApi
import com.jaac.avoqado_tpv.features.payment.data.ledger.CercaDeSolicitud
import com.jaac.avoqado_tpv.features.payment.data.repository.PaymentQueueRepositoryImpl
import com.jaac.avoqado_tpv.features.payment.domain.PaymentState
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings
import com.jaac.avoqado_tpv.features.payment.domain.referenciaDeEfectivo
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordPaymentUseCase
import com.jaac.avoqado_tpv.features.payment.domain.usecase.SubirVentaGuardadaUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.IOException
import java.math.BigDecimal

/** La frontera que puede perder dinero: UI real -> único escritor Room -> mobile (nunca /fast). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class CobroDeVentaLocalTest {
    private lateinit var context: Context
    private lateinit var db: AvoqadoDatabase
    private lateinit var dao: PendingPaymentDao
    private lateinit var escritor: VentaLocalRepository
    private lateinit var api: VentaGuardadaApi
    private lateinit var registrar: RecordPaymentUseCase
    private lateinit var ordenes: OrderRepository
    private lateinit var obtenerCuentas: com.jaac.avoqado_tpv.features.payment.domain.use_case.GetMerchantsUseCase
    private lateinit var vm: PaymentViewModel
    private lateinit var impresor: com.jaac.avoqado_tpv.core.printer.PrinterManager
    private val impresiones = mutableListOf<List<Any?>>()
    private val llave = "c000000000000000000000001"
    private val venta = VentaLocal(
        llave, "venue-1", "staff-1", "shift-1", 1_760_000_000_000L, 10000,
        """{ "externalId":"$llave", "total":10000, "items":[] }""",
        listOf(RenglonDeTicket("$llave:0", "Blusa", 1, 10000, 10000,
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
        ordenes = mockk(relaxed = true)
        obtenerCuentas = mockk(relaxed = true)
        every { obtenerCuentas.invoke() } returns MutableStateFlow(emptyList())
        mockkObject(TerminalConfig)
        every { TerminalConfig.serialNumber } returns "AVQD-1"
        every { TerminalConfig.brand } returns "PAX"
        every { TerminalConfig.model } returns "A80"
        mockkObject(PaymentSyncScheduler)
        every { PaymentSyncScheduler.runNow(any()) } just runs
        impresor = mockk(relaxed = true)
        impresiones.clear()
        every { impresor.checkPrinterStatus() } returns com.jaac.avoqado_tpv.core.printer.PrinterStatusResult(0, true, true, message = "Lista")
        every { impresor.printReceipt(
            receiptUrl = any(), amount = any(), authCode = any(), tipAmount = any(), cardDetails = any(), referenceNumber = any(),
            venueName = any(), venueLogoUrl = any(), venueLegalName = any(), venueRfc = any(), venueAddress = any(), venueCity = any(),
            venueState = any(), venueZipCode = any(), staffName = any(), orderNumber = any(), orderItems = any(), discountAmount = any(),
            isRefund = any(), isPortabilidad = any(), serialNumber = any(), categoryName = any(), autofacturaAvailable = any(),
            serviceChargeAmount = any(), ventaGuardadaSinConexion = any(),
        ) } answers { impresiones += args.toList(); Result.success(Unit) }
        vm = crearViewModel()
        seleccionar(venta)
    }

    @After fun tearDown() {
        vm.viewModelScope.cancel()
        db.close()
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun seleccionar(v: VentaLocal, propina: String = "0.00") {
        assertThat(vm.setVentaLocalJson(Gson().toJson(v))).isTrue()
        val stateField = PaymentViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(vm) as MutableStateFlow<PaymentState>
        state.value = PaymentState.SelectingMerchant(
            subtotal = "100.00", tipAmount = propina,
            totalAmount = BigDecimal("100.00").add(propina.toBigDecimal()).toPlainString(), rating = null,
        )
    }

    private fun esperar(descripcion: String, condicion: () -> Boolean) {
        val limite = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < limite) {
            if (condicion()) return
            Thread.sleep(10)
        }
        throw AssertionError("$descripcion; estado=${vm.state.value}")
    }

    private suspend fun fila(): PendingPaymentEntity? = dao.findByReference(referenciaDeEfectivo(llave), "venue-1")
    private fun esperarFinal() = esperar("Faltó el desenlace local") { vm.state.value is PaymentState.Success || vm.state.value is PaymentState.Error }

    @Test fun `sin red la fila ya existe antes de la primera llamada a mobile`() = runTest {
        coEvery { api.crearOrden("venue-1", any()) } coAnswers {
            val persistida = fila()!!
            assertThat(persistida.amount).isEqualTo("100.00")
            assertThat(persistida.orderRequestJson).isEqualTo(venta.pedidoJson)
            assertThat(persistida.syncStatus).isEqualTo("SYNCING")
            assertThat(persistida.claimToken).isNotEmpty()
            assertThat(persistida.exitoMostrado).isFalse()
            throw IOException("sin conexión")
        }

        vm.processCashPayment("100.00")
        esperarFinal()

        assertThat(vm.state.value).isInstanceOf(PaymentState.Success::class.java)
        assertThat(fila()).isNotNull()
        assertThat(fila()!!.syncStatus).isEqualTo("PENDING")
        assertThat(fila()!!.claimToken).isNull()
        coVerifyOrder { dao.insert(any()); api.crearOrden("venue-1", any()) }
        coVerify(exactly = 0) { registrar(context = any(), cardDetails = any(), authorizationNumber = any(), referenceNumber = any()) }
        verify(exactly = 1) { PaymentSyncScheduler.runNow(context) }
    }

    @Test fun `morir despues del insert no pierde la venta aunque no se llegue a pintar el exito`() = runTest {
        val llegoARed = CompletableDeferred<Unit>()
        coEvery { api.crearOrden(any(), any()) } coAnswers {
            llegoARed.complete(Unit)
            throw CancellationException("muerte tras guardar")
        }
        vm.processCashPayment("100.00")
        esperar("No llegó al punto de interrupción") { llegoARed.isCompleted }
        vm.viewModelScope.cancel()

        val persistida = fila()!!
        assertThat(persistida.idempotencyKey).isEqualTo(llave)
        assertThat(persistida.orderRequestJson).isEqualTo(venta.pedidoJson)
        assertThat(persistida.exitoMostrado).isFalse()
        assertThat(dao.ultimaVentaSinMostrar("venue-1")!!.id).isEqualTo(persistida.id)
        assertThat(dao.ultimaVentaSinMostrar("otro-venue")).isNull()
    }

    @Test fun `exito local muestra la nota y los renglones del ticket sin consultar la orden`() = runTest {
        vm.processCashPayment("100.00")
        esperarFinal()

        val exito = vm.state.value as PaymentState.Success
        assertThat(exito.pendingSyncMessage).isEqualTo("Venta guardada sin conexión: se manda sola al volver el internet")
        assertThat(exito.amount).isEqualTo("100.00")
        assertThat(exito.referenceNumber).isEqualTo("CASH-0000000000000001")
        assertThat(exito.orderItems!!.single().productName).isEqualTo("Blusa")
        assertThat(exito.orderItems!!.single().modifiers.single().name).isEqualTo("Bolsa")
        assertThat(exito.orderItems!!.single().totalPrice).isEqualTo(BigDecimal("100.00"))
        assertThat(exito.orderId).isNull()
        coVerify(exactly = 0) { ordenes.getOrder(any(), any()) }
        assertThat(fila()!!.exitoMostrado).isFalse()

        vm.marcarExitoDeVentaLocalMostrado()
        esperar("La pantalla de éxito no marcó su venta") { kotlinx.coroutines.runBlocking { fila()!!.exitoMostrado } }
        assertThat(fila()!!.exitoMostrado).isTrue()
    }

    @Test fun `fallar al agendar no borra la fila ni cambia su exito local por error`() = runTest {
        every { PaymentSyncScheduler.runNow(any()) } throws IllegalStateException("WorkManager no disponible")
        vm.processCashPayment("100.00")
        esperarFinal()
        assertThat(vm.state.value).isInstanceOf(PaymentState.Success::class.java)
        assertThat(fila()).isNotNull()
        assertThat(fila()!!.syncStatus).isEqualTo("PENDING")
    }

    @Test fun `rechazo permanente conserva el dinero como FAILED y pide revision sin prometer envio automatico`() = runTest {
        coEvery { api.crearOrden(any(), any()) } returns Response.error(
            422, """{"message":"Producto archivado"}""".toResponseBody("application/json".toMediaType()),
        )
        vm.processCashPayment("100.00")
        esperarFinal()

        val exito = vm.state.value as PaymentState.Success
        assertThat(fila()).isNotNull()
        assertThat(fila()!!.syncStatus).isEqualTo("FAILED")
        assertThat(fila()!!.permanent).isTrue()
        assertThat(fila()!!.amount).isEqualTo("100.00")
        assertThat(exito.pendingSyncMessage).contains("revisión")
        assertThat(exito.pendingSyncMessage).doesNotContain("se manda sola")
        coVerify(exactly = 0) { registrar(context = any(), cardDetails = any(), authorizationNumber = any(), referenceNumber = any()) }
        verify(exactly = 0) { PaymentSyncScheduler.runNow(any()) }
    }

    @Test fun `si Room no pudo guardar no se toca la red ni se afirma exito`() = runTest {
        coEvery { dao.insert(any()) } throws IllegalStateException("disco lleno")
        vm.processCashPayment("100.00")
        esperarFinal()
        assertThat(vm.state.value).isInstanceOf(PaymentState.Error::class.java)
        assertThat(fila()).isNull()
        coVerify(exactly = 0) { api.crearOrden(any(), any()) }
        coVerify(exactly = 0) { api.pagar(any(), any(), any()) }
    }

    @Test fun `la misma venta ya reclamada conserva identidad y no arranca otra subida`() = runTest {
        escritor.guardar(venta, PendingPaymentEntity.PROCESSOR_BLUMON, "", "AVQD-1", reclamada = true).getOrThrow()
        val anterior = fila()!!

        vm.processCashPayment("100.00")
        esperarFinal()

        assertThat(vm.state.value).isInstanceOf(PaymentState.Success::class.java)
        assertThat(fila()!!.id).isEqualTo(anterior.id)
        assertThat(fila()!!.claimToken).isEqualTo(anterior.claimToken)
        assertThat(dao.getPendingCount()).isEqualTo(1)
        coVerify(exactly = 0) { api.crearOrden(any(), any()) }
        coVerify(exactly = 0) { api.pagar(any(), any(), any()) }
        coVerify(exactly = 0) { registrar(context = any(), cardDetails = any(), authorizationNumber = any(), referenceNumber = any()) }
    }

    @Test fun `misma llave con cuerpo distinto es error visible y no se pisa lo guardado`() = runTest {
        escritor.guardar(venta, PendingPaymentEntity.PROCESSOR_BLUMON, "", "AVQD-1").getOrThrow()
        seleccionar(venta.copy(pedidoJson = venta.pedidoJson + " "))
        vm.processCashPayment("100.00")
        esperarFinal()
        assertThat(vm.state.value).isInstanceOf(PaymentState.Error::class.java)
        assertThat(fila()!!.orderRequestJson).isEqualTo(venta.pedidoJson)
        coVerify(exactly = 0) { api.crearOrden(any(), any()) }
        coVerify(exactly = 0) { registrar(context = any(), cardDetails = any(), authorizationNumber = any(), referenceNumber = any()) }
    }

    @Test fun `reabrir la misma venta usa el ticket durable y conserva su identidad`() = runTest {
        escritor.guardar(venta, PendingPaymentEntity.PROCESSOR_BLUMON, "", "AVQD-1", reclamada = true).getOrThrow()
        seleccionar(venta.copy(renglones = listOf(venta.renglones.single().copy(nombre = "Otra prenda"))))
        vm.processCashPayment("100.00")
        esperarFinal()
        val exito = vm.state.value as PaymentState.Success
        assertThat(exito.orderItems).isNotNull()
        assertThat(exito.orderItems!!.single().productName).isEqualTo("Blusa")
        assertThat(fila()!!.staffId).isEqualTo("staff-1")
        coVerify(exactly = 0) { api.crearOrden(any(), any()) }
    }

    @Test fun `misma llave y pedido pero otra propina no toma el exito de la primera venta`() = runTest {
        escritor.guardar(venta, PendingPaymentEntity.PROCESSOR_BLUMON, "", "AVQD-1", propinaCents = 575).getOrThrow()
        vm.processCashPayment("100.00")
        esperarFinal()
        assertThat(vm.state.value).isInstanceOf(PaymentState.Error::class.java)
        assertThat(fila()!!.tip).isEqualTo("5.75")
        coVerify(exactly = 0) { api.crearOrden(any(), any()) }
    }

    @Test fun `misma llave y pedido pero otro importe no toma el exito de la primera venta`() = runTest {
        escritor.guardar(venta.copy(totalCents = 9000), PendingPaymentEntity.PROCESSOR_BLUMON, "", "AVQD-1").getOrThrow()
        vm.processCashPayment("100.00")
        esperarFinal()
        assertThat(vm.state.value).isInstanceOf(PaymentState.Error::class.java)
        assertThat(fila()!!.amount).isEqualTo("90.00")
    }

    @Test fun `la llave de otro venue no acredita una venta para la sesion actual`() = runTest {
        escritor.guardar(venta.copy(venueId = "otro-venue"), PendingPaymentEntity.PROCESSOR_BLUMON, "", "AVQD-1").getOrThrow()
        vm.processCashPayment("100.00")
        esperarFinal()
        assertThat(vm.state.value).isInstanceOf(PaymentState.Error::class.java)
        assertThat(dao.findByReference(referenciaDeEfectivo(llave), "otro-venue")!!.venueId).isEqualTo("otro-venue")
        coVerify(exactly = 0) { api.crearOrden(any(), any()) }
    }

    @Test fun `venta local malformada bloquea el cobro y no cae a venta rapida`() = runTest {
        assertThat(vm.setVentaLocalJson("{no es una venta}")).isFalse()
        assertThat(vm.state.value).isInstanceOf(PaymentState.Error::class.java)
        coVerify(exactly = 0) { registrar(context = any(), cardDetails = any(), authorizationNumber = any(), referenceNumber = any()) }
    }

    @Test fun `reset limpia el argumento local para el siguiente pago rapido`() = runTest {
        vm.resetPayment()
        val stateField = PaymentViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(vm) as MutableStateFlow<PaymentState>
        state.value = PaymentState.SelectingMerchant("100.00", "0.00", "100.00", null)

        vm.processCashPayment("100.00")
        esperarFinal()

        assertThat(vm.state.value).isInstanceOf(PaymentState.Success::class.java)
        assertThat(fila()).isNull()
        coVerify(exactly = 1) { registrar(context = any(), cardDetails = any(), authorizationNumber = any(), referenceNumber = any()) }
        coVerify(exactly = 0) { api.crearOrden(any(), any()) }
    }

    @Test fun `reset durante la subida conserva la fila sin publicar exito sobre el siguiente cobro`() = runTest {
        val respuesta = CompletableDeferred<Response<JsonObject>>()
        val llegoARed = CompletableDeferred<Unit>()
        coEvery { api.crearOrden(any(), any()) } coAnswers { llegoARed.complete(Unit); respuesta.await() }
        vm.processCashPayment("100.00")
        esperar("No llegó a la red") { llegoARed.isCompleted }

        vm.resetPayment()
        respuesta.completeExceptionally(IOException("se cayó la red"))
        esperar("La venta no regresó a pendientes") { kotlinx.coroutines.runBlocking { fila()?.syncStatus == "PENDING" } }

        assertThat(vm.state.value).isEqualTo(PaymentState.Idle)
        assertThat(fila()!!.exitoMostrado).isFalse()
        assertThat(fila()!!.amount).isEqualTo("100.00")
    }

    @Test fun `cancelar antes de cobrar limpia la venta local para otro flujo`() = runTest {
        vm.cancelPayment()
        esperar("No terminó la cancelación") { vm.state.value is PaymentState.Cancelled }
        val stateField = PaymentViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(vm) as MutableStateFlow<PaymentState>
        state.value = PaymentState.SelectingMerchant("100.00", "0.00", "100.00", null)
        vm.processCashPayment("100.00")
        esperarFinal()
        assertThat(fila()).isNull()
        coVerify(exactly = 1) { registrar(context = any(), cardDetails = any(), authorizationNumber = any(), referenceNumber = any()) }
        coVerify(exactly = 0) { api.crearOrden(any(), any()) }
    }

    private fun crearViewModel() = PaymentViewModel(
        preTransUseCase = mockk(relaxed = true), startDetectCardUseCase = mockk(relaxed = true), stopDetectCardUseCase = mockk(relaxed = true),
        startEmvTransUseCase = mockk(relaxed = true), startCtlssTransUseCase = mockk(relaxed = true), getEmvTagUseCase = mockk(relaxed = true),
        completeEmvTransUseCase = mockk(relaxed = true), continueConfirmCardUseCase = mockk(relaxed = true), setSelectAppCodeUseCase = mockk(relaxed = true),
        saleIccUseCase = mockk(relaxed = true), saleCtlsUseCase = mockk(relaxed = true), cancelIccUseCase = mockk(relaxed = true), validateCancelUseCase = mockk(relaxed = true),
        transProcessRepository = mockk(relaxed = true) {
            every { getEventPinDialogStateFlow() } returns MutableStateFlow(mockk(relaxed = true) { every { show } returns false; every { dismiss } returns false })
            every { getKeyboardPinStateFlow() } returns MutableStateFlow("")
            every { getPinResultFlow() } returns MutableStateFlow(null)
            every { getSelectAppStateFlow() } returns MutableStateFlow(null)
            every { confirmCardReadingFlow() } returns MutableStateFlow(false)
        },
        initializerUseCase = mockk(relaxed = true), getInitDataUseCase = mockk(relaxed = true), insertInitUseCase = mockk(relaxed = true),
        initializationManager = mockk(relaxed = true) { every { isInitialized } returns MutableStateFlow(false) },
        getMerchantsUseCase = obtenerCuentas,
        multiMerchantSDKManager = mockk(relaxed = true), recordPaymentUseCase = registrar, recordRefundUseCase = mockk(relaxed = true),
        authRepository = mockk(relaxed = true) { every { getVenueId() } returns "venue-1"; every { getStaffId() } returns "staff-1" },
        paymentQueueRepository = PaymentQueueRepositoryImpl(dao), refundQueueRepository = mockk(relaxed = true), printerManager = impresor,
        socketManager = mockk(relaxed = true) { every { events } returns MutableSharedFlow() },
        shiftRepository = mockk(relaxed = true) { every { isShiftSystemEnabled() } returns false },
        orderRepository = ordenes, orderSyncCoordinator = mockk(relaxed = true),
        tpvSettingsRepository = mockk(relaxed = true) { every { getCurrentSettings() } returns TpvSettings.DEFAULT },
        verificationUploadManager = mockk(relaxed = true), paymentApiService = mockk(relaxed = true), apiService = mockk(relaxed = true),
        customerRepository = mockk(relaxed = true), secureStorage = mockk(relaxed = true) { every { getSerialNumber() } returns "AVQD-1" },
        modulesRepository = mockk(relaxed = true) { every { modules } returns MutableStateFlow(emptyList()) },
        connectionStateManager = ConnectionStateManager().apply { setInternetConnected(false); setServerConnected(false) },
        merchantRepository = mockk(relaxed = true), merchantEligibilityRepository = mockk(relaxed = true),
        criticalNetworkOperationManager = mockk(relaxed = true),
        paymentStateHolder = com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder(),
        connectionEventManager = mockk(relaxed = true) { every { connectionRestoredEvents } returns MutableSharedFlow() },
        paymentAttemptLedger = mockk(relaxed = true) {
            coEvery { cercaDeSolicitud(any()) } returns CercaDeSolicitud.LIBRE
            coEvery { iniciarEjecucionNoTarjeta(any()) } returns CercaDeSolicitud.LIBRE
        },
        blumonAttemptResolver = mockk(relaxed = true), observability = mockk(relaxed = true), authAttemptTelemetryStore = mockk(relaxed = true),
        appContext = context, ventaLocalRepository = escritor, pendingPaymentDao = dao,
        subirVentaGuardadaUseCase = SubirVentaGuardadaUseCase(api, dao, context),
    )
    @Test fun `PAX imprime venta local con receipt nulo usando el ticket guardado sin cargar orden`() = runTest {
        seleccionar(venta, propina = "5.00")
        vm.processCashPayment("105.00")
        esperarFinal()
        assertThat((vm.state.value as PaymentState.Success).receipt).isNull()
        vm.printReceipt()
        esperar("No imprimió el ticket") { impresiones.isNotEmpty() }
        val args = impresiones.single()
        assertThat(args[0]).isNull() // QR
        assertThat(args[1]).isEqualTo("105.00")
        assertThat(args[3]).isEqualTo("5.00")
        assertThat(args[15]).isNull() // Folio
        @Suppress("UNCHECKED_CAST")
        val items = args[16] as List<com.jaac.avoqado_tpv.features.ordering.domain.OrderItem>
        assertThat(items.single().productName).isEqualTo("Blusa")
        assertThat(items.single().modifiers.single().name).isEqualTo("Bolsa")
        assertThat(items.single().modifiers.single().type).isEqualTo(com.jaac.avoqado_tpv.features.ordering.domain.ModifierType.MULTIPLE_CHOICE)
        assertThat(args.last()).isEqualTo(true) // Leyenda
        coVerify(exactly = 0) { ordenes.getOrder(any(), any()) }
    }

    @Test fun `el ticket local sigue sin folio ni QR aunque el servidor ya haya dado numero a la orden`() = runTest {
        coEvery { api.crearOrden(any(), any()) } returns Response.success(JsonParser.parseString(
            """{"success":true,"order":{"id":"orden-1","orderNumber":"T-123","total":100.0}}""").asJsonObject)
        vm.processCashPayment("100.00")
        esperarFinal()
        val exito = vm.state.value as PaymentState.Success
        assertThat(exito.orderId).isEqualTo("orden-1")
        vm.printReceipt()
        esperar("No imprimió el ticket subido") { impresiones.isNotEmpty() }
        assertThat(impresiones.single()[0]).isNull()
        assertThat(impresiones.single()[15]).isNull()
        assertThat(impresiones.single().last()).isEqualTo(true)
        coVerify(exactly = 0) { ordenes.getOrder(any(), any()) }
    }

}

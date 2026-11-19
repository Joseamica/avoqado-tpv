package com.jaac.avoqado_tpv.features.ordering.presentation.menu

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.CatalogoGuardado
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.dao.ProductCategoryDao
import com.jaac.avoqado_tpv.core.data.local.dao.ProductDao
import com.jaac.avoqado_tpv.core.data.network.PermisoRequeridoException
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.core.util.ConnectionEventManager
import com.jaac.avoqado_tpv.core.util.ConnectionRestoredEvent
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.features.ordering.data.repository.DescuentoSinConfirmarException
import com.jaac.avoqado_tpv.features.ordering.data.repository.ID_DE_FILA_SIN_CONFIRMAR
import com.jaac.avoqado_tpv.features.ordering.data.repository.TEXTO_APLICAR_SIN_CONFIRMAR
import com.jaac.avoqado_tpv.features.ordering.data.repository.TEXTO_CUENTA_SIN_SUBIR
import com.jaac.avoqado_tpv.features.ordering.data.repository.TEXTO_DESCUENTO_YA_APLICADO
import com.jaac.avoqado_tpv.features.ordering.data.repository.TEXTO_ESPERA_EL_DESCUENTO
import com.jaac.avoqado_tpv.features.ordering.data.repository.TEXTO_NO_SE_PUDO_APLICAR
import com.jaac.avoqado_tpv.features.ordering.data.repository.TEXTO_QUITAR_SIN_CONFIRMAR
import com.jaac.avoqado_tpv.features.ordering.data.repository.TEXTO_REBAJA_FIJA_DE_VALES
import com.jaac.avoqado_tpv.features.ordering.domain.AcuseDeDescuento
import com.jaac.avoqado_tpv.features.ordering.domain.AssignTableResult
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountRepository
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountType
import com.jaac.avoqado_tpv.features.ordering.domain.KitchenStatus
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.OrderDiscount
import com.jaac.avoqado_tpv.features.ordering.domain.OrderItem
import com.jaac.avoqado_tpv.features.ordering.domain.OrderRepository
import com.jaac.avoqado_tpv.features.ordering.domain.OrderStatus
import com.jaac.avoqado_tpv.features.ordering.domain.OrderSyncCoordinator
import com.jaac.avoqado_tpv.features.ordering.domain.OrderType
import com.jaac.avoqado_tpv.features.ordering.domain.PaymentStatus
import com.jaac.avoqado_tpv.features.ordering.domain.ProductRepository
import com.jaac.avoqado_tpv.features.ordering.domain.TableRepository
import com.jaac.avoqado_tpv.features.permissions.presentation.override.Autorizacion
import com.jaac.avoqado_tpv.features.permissions.presentation.override.AutorizacionDeGerente
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant

/**
 * D16 v4 (hz:e82938e1:D16) · **TPV SOLA → ÓRDENES**: los descuentos de una cuenta se atienden UNO POR UNO (una fila por cuenta).
 * Aplicar (catálogo, manual, cortesía, cupón), quitar y releer entran a la misma fila; cada cambio termina releyendo la cuenta en el
 * SERVIDOR y SÓLO esa lectura escribe la lista («Descuentos Aplicados»). Room, las copias locales y los acuses nunca la escriben.
 * D21: el mismo descuento reenviado tras «sin confirmar» lleva la MISMA llave. Promesa y mapa:
 * `docs/audit/2026-11-18-d16-tpv-ordenes-descuentos/promesa.md` (workspace).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MenuViewModelDescuentosDelServidorTest {

    private val venueId = "venue-1"
    private val testDispatcher = UnconfinedTestDispatcher()
    private val creados = mutableListOf<MenuViewModel>()
    private val avisos = mutableListOf<String>()
    private val escuchas = mutableListOf<Job>()

    private lateinit var orderRepository: OrderRepository
    private lateinit var discountRepository: DiscountRepository
    private lateinit var tableRepository: TableRepository
    private lateinit var orderSyncCoordinator: OrderSyncCoordinator
    private lateinit var autorizacion: AutorizacionDeGerente
    private lateinit var syncEvents: MutableSharedFlow<OrderSyncCoordinator.SyncEvent>
    private lateinit var socketEvents: MutableSharedFlow<SocketEvent>
    private lateinit var reconexiones: MutableSharedFlow<ConnectionRestoredEvent>
    private lateinit var room: MutableSharedFlow<Order>

    @Before fun preparar() {
        Dispatchers.setMain(testDispatcher)
        orderRepository = mockk(relaxed = true)
        discountRepository = mockk(relaxed = true)
        tableRepository = mockk(relaxed = true)
        orderSyncCoordinator = mockk(relaxed = true)
        autorizacion = mockk(relaxed = true)
        syncEvents = MutableSharedFlow()
        socketEvents = MutableSharedFlow()
        reconexiones = MutableSharedFlow()
        room = MutableSharedFlow()
        avisos.clear()
        every { orderSyncCoordinator.syncEvents } returns syncEvents
        every { orderSyncCoordinator.observeOrder(any()) } returns room
        every { orderSyncCoordinator.resolveOrderId(any()) } answers { firstArg() }
        coEvery { orderSyncCoordinator.syncOrderImmediately(any()) } answers { firstArg() }
        coEvery { orderSyncCoordinator.getLocalOrder(any()) } returns null
        coEvery { autorizacion.paraCortesia(any(), any(), any()) } returns Autorizacion.NoHaceFalta
        // El `Result` (clase en línea) de un mock relajado truena dentro de la app; la excepción, que nadie atrapa, la reportaría el
        // `runTest` de OTRA clase de pruebas (visto: UncaughtExceptionsBeforeTest en `TableMenuFamiliasTest`).
        coEvery { orderRepository.getOrderCustomers(any(), any()) } returns Result.success(emptyList())
        coEvery { orderRepository.getOrder(any(), any()) } returns Result.failure(IOException("sin stub"))
    }

    @After fun limpiar() {
        try {
            runBlocking {
                creados.forEach { it.viewModelScope.cancel() }
                creados.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            }
        } finally {
            creados.clear()
            escuchas.forEach { it.cancel() }
            escuchas.clear()
            Dispatchers.resetMain()
            unmockkAll()
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Abrir la venta
    // ------------------------------------------------------------------------------------------------

    @Test fun `abrir en frio pinta las filas de su lectura y no las de la copia de Room`() {
        coEvery { orderSyncCoordinator.getLocalOrder("o-1") } returnsMany listOf(null, orden())
        coEvery { orderRepository.getOrder(venueId, "o-1") } returns Result.success(orden(filas = listOf(fila("od-vale"), fila("od-suelto"))))
        val vm = crear(estado = null)
        vm.loadOrder("o-1")
        assertThat(ids(vm)).containsExactly("od-vale", "od-suelto").inOrder()
        coVerify(exactly = 1) { orderRepository.getOrder(venueId, "o-1") }
    }

    @Test fun `abrir una mesa ya abierta pinta sus filas del SERVIDOR`() {
        coEvery { tableRepository.assignTable(venueId, "t-1", any(), any()) } returns
            com.jaac.avoqado_tpv.core.domain.models.Result.Success(AssignTableResult("o-1", "1", isNewOrder = false))
        coEvery { orderSyncCoordinator.getLocalOrder("o-1") } returns orden()
        coEvery { orderRepository.getOrder(venueId, "o-1") } returns Result.success(orden(filas = listOf(fila("od-mesa"))))
        val vm = crear(estado = null)
        vm.loadOrder("CREATE_TABLE_ORDER:t-1")
        assertThat(ids(vm)).containsExactly("od-mesa")
    }

    @Test fun `abrir en caliente relee las filas del SERVIDOR`() {
        coEvery { orderSyncCoordinator.getLocalOrder("o-1") } returns orden()
        coEvery { orderRepository.getOrder(venueId, "o-1") } returns Result.success(orden(filas = listOf(fila("od-1"))))
        val vm = crear(estado = null)
        vm.loadOrder("o-1")
        assertThat(ids(vm)).containsExactly("od-1")
    }

    @Test fun `abrir en caliente sin red queda como hoy y no avisa nada`() {
        coEvery { orderSyncCoordinator.getLocalOrder("o-1") } returns orden()
        val vm = crear(estado = null)
        escucha(vm)
        vm.loadOrder("o-1")
        assertThat(vm.state.value).isInstanceOf(MenuState.Success::class.java)
        assertThat(ids(vm)).isEmpty()
        assertThat(avisos).isEmpty()
        assertThat(vm.descuentoEnCurso.value).isFalse()
    }

    @Test fun `al volver la red con Ordenes abierta llegan las filas solas`() {
        coEvery { orderSyncCoordinator.getLocalOrder("o-1") } returns orden()
        coEvery { orderRepository.getOrder(venueId, "o-1") } returnsMany listOf(
            Result.failure(IOException("sin red")),
            Result.success(orden(filas = listOf(fila("od-1")))),
        )
        val vm = crear(estado = null)
        vm.setOrderingActive(true)
        vm.loadOrder("o-1")
        assertThat(ids(vm)).isEmpty()
        runBlocking { reconexiones.emit(ConnectionRestoredEvent("ahora", 1)) }
        assertThat(ids(vm)).containsExactly("od-1")
    }

    @Test fun `si la primera lectura falla se reintenta sola aunque no llegue el aviso de reconexion`() {
        coEvery { orderSyncCoordinator.getLocalOrder("o-1") } returns orden()
        coEvery { orderRepository.getOrder(venueId, "o-1") } returnsMany listOf(
            Result.failure(IOException("sin red")),
            Result.success(orden(filas = listOf(fila("od-1")))),
        )
        val vm = crear(estado = null)
        vm.loadOrder("o-1")
        assertThat(ids(vm)).isEmpty()
        testDispatcher.scheduler.advanceTimeBy(6_000)
        testDispatcher.scheduler.runCurrent()
        assertThat(ids(vm)).containsExactly("od-1")
    }

    @Test fun `abrir en caliente no escribe la cuenta en Room ni cambia su total`() {
        val copia = orden(descuentos = "10.00")
        coEvery { orderSyncCoordinator.getLocalOrder("o-1") } returns copia
        coEvery { orderRepository.getOrder(venueId, "o-1") } returns Result.success(orden(descuentos = "30.00", filas = listOf(fila("od-1"))))
        val vm = crear(estado = null)
        vm.loadOrder("o-1")
        assertThat(ids(vm)).containsExactly("od-1")
        assertThat((vm.state.value as MenuState.Success).order.total).isEqualTo(copia.total)
        coVerify(exactly = 0) { orderSyncCoordinator.cacheBackendOrder(any()) }
    }

    // ------------------------------------------------------------------------------------------------
    // Relecturas que piden otros caminos
    // ------------------------------------------------------------------------------------------------

    @Test fun `tras el abono la relectura de la fila toma la lista aunque Room no tenga filas`() {
        coEvery { orderSyncCoordinator.getLocalOrder("o-1") } returns orden()
        coEvery { orderRepository.getOrder(venueId, "o-1") } returns Result.success(orden(filas = listOf(fila("od-a"), fila("od-b"))))
        val vm = crear()
        ponerLista(vm, listOf(fila("od-a")))
        runBlocking { socketEvents.emit(pagoCompletado("o-1")) }
        assertThat(ids(vm)).containsExactly("od-a", "od-b").inOrder()
    }

    @Test fun `una lista vacia del SERVIDOR es la verdad y vacia la pantalla`() {
        coEvery { orderRepository.getOrder(venueId, "o-1") } returns Result.success(orden(filas = emptyList()))
        val vm = crear()
        ponerLista(vm, listOf(fila("od-quitada-en-otra-caja")))
        runBlocking { socketEvents.emit(pagoCompletado("o-1")) }
        assertThat(ids(vm)).isEmpty()
    }

    @Test fun `tras sincronizar esta cuenta se relee la lista aunque este vacia y sin descuento`() {
        coEvery { orderRepository.getOrder(venueId, "o-1") } returns Result.success(orden(filas = listOf(fila("od-de-otra-caja"))))
        val vm = crear()
        ponerCuentaAbierta(vm, "o-1")
        runBlocking { syncEvents.emit(OrderSyncCoordinator.SyncEvent.Synced("o-1", 2)) }
        assertThat(ids(vm)).containsExactly("od-de-otra-caja")
    }

    @Test fun `un Synced de OTRA cuenta no cambia la cuenta abierta ni lee sus filas`() {
        val vm = crear(descuentos = "10.00")
        ponerLista(vm, listOf(fila("od-1")))
        ponerCuentaAbierta(vm, "o-1")
        runBlocking { syncEvents.emit(OrderSyncCoordinator.SyncEvent.Synced("o-2", 1)) }
        assertThat(cuentaAbierta(vm)).isEqualTo("o-1")
        coVerify(exactly = 0) { orderRepository.getOrder(any(), "o-2") }
        assertThat(ids(vm)).containsExactly("od-1")
    }

    @Test fun `el Synced del id nuevo de la cuenta local si la cambia`() {
        every { orderSyncCoordinator.resolveOrderId("local_1") } returns "o-1"
        val vm = crear(id = "local_1")
        ponerCuentaAbierta(vm, "local_1")
        runBlocking { syncEvents.emit(OrderSyncCoordinator.SyncEvent.Synced("o-1", 1)) }
        assertThat(cuentaAbierta(vm)).isEqualTo("o-1")
    }

    @Test fun `un error de sincronizar de esta cuenta relee sus filas`() {
        coEvery { orderRepository.getOrder(venueId, "o-1") } returns Result.success(orden(filas = listOf(fila("od-1"))))
        val vm = crear()
        ponerCuentaAbierta(vm, "o-1")
        runBlocking { syncEvents.emit(OrderSyncCoordinator.SyncEvent.Error("o-1", "rechazo definitivo")) }
        assertThat(ids(vm)).containsExactly("od-1")
    }

    @Test fun `tras intentar anular se relee la lista aunque anular no siga`() {
        coEvery { orderRepository.getOrder(venueId, "o-1") } returns
            Result.success(orden(renglones = listOf(renglon().copy(id = "i-otro", productId = "p-otro")), filas = listOf(fila("od-otra-caja"))))
        val vm = crear(renglones = listOf(renglon()))
        vm.voidItems(listOf("i-1"), "x")
        assertThat(ids(vm)).containsExactly("od-otra-caja")
    }

    // ------------------------------------------------------------------------------------------------
    // La fila: uno por uno, botones en gris, sólo la lectura final escribe
    // ------------------------------------------------------------------------------------------------

    @Test fun `mientras un cambio sigue en curso los botones van en gris y otro toque no se manda`() {
        val respuesta = CompletableDeferred<Result<AcuseDeDescuento>>()
        coEvery { discountRepository.applyManualDiscount(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers { respuesta.await() }
        coEvery { orderRepository.getOrder(venueId, "o-1") } returns Result.success(orden(filas = listOf(fila("od-x"))))
        val vm = crear()
        ponerLista(vm, listOf(fila("od-a")))
        vm.applyManualDiscount(DiscountType.FIXED, 10.0, "x")
        assertThat(vm.descuentoEnCurso.value).isTrue()
        vm.removeDiscount("od-a")
        vm.applyManualDiscount(DiscountType.FIXED, 10.0, "x")
        vm.applyPredefinedDiscount("d-1")
        coVerify(exactly = 0) { discountRepository.removeDiscount(any(), any(), any(), any()) }
        coVerify(exactly = 0) { discountRepository.applyPredefinedDiscount(any(), any(), any(), any(), any()) }
        respuesta.complete(Result.success(acuse(fila("od-x"))))
        coVerify(exactly = 1) { discountRepository.applyManualDiscount(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertThat(ids(vm)).containsExactly("od-x")
        assertThat(vm.descuentoEnCurso.value).isFalse()
    }

    @Test fun `una relectura en la fila antes de aplicar termina primero y la lectura final del cambio manda`() {
        val primera = CompletableDeferred<Result<Order>>()
        var llamadas = 0
        coEvery { orderRepository.getOrder(venueId, "o-1") } coAnswers {
            if (llamadas++ == 0) primera.await() else Result.success(orden(filas = listOf(fila("od-a"), fila("od-x"))))
        }
        coEvery { discountRepository.applyManualDiscount(any(), any(), any(), any(), any(), any(), any(), any()) } returns Result.success(acuse(fila("od-x")))
        val vm = crear()
        ponerCuentaAbierta(vm, "o-1")
        runBlocking { syncEvents.emit(OrderSyncCoordinator.SyncEvent.Synced("o-1", 2)) }   // relectura en la fila, en vuelo
        vm.applyManualDiscount(DiscountType.FIXED, 10.0, "x")                               // espera su turno
        coVerify(exactly = 0) { discountRepository.applyManualDiscount(any(), any(), any(), any(), any(), any(), any(), any()) }
        primera.complete(Result.success(orden(filas = listOf(fila("od-a")))))
        assertThat(ids(vm)).containsExactly("od-a", "od-x").inOrder()
    }

    @Test fun `el acuse no escribe la lista - si la lectura final ya no trae la fila, no aparece`() {
        coEvery { discountRepository.applyManualDiscount(any(), any(), any(), any(), any(), any(), any(), any()) } returns Result.success(acuse(fila("od-x")))
        coEvery { orderRepository.getOrder(venueId, "o-1") } returns Result.success(orden(filas = emptyList()))   // otra caja ya la quitó
        val vm = crear()
        vm.applyManualDiscount(DiscountType.FIXED, 10.0, "x")
        assertThat(ids(vm)).isEmpty()
    }

    @Test fun `la fila aplicada llega con la lectura final y se quita con su id real`() {
        coEvery { discountRepository.applyManualDiscount(any(), any(), any(), any(), any(), any(), any(), any()) } returns Result.success(acuse(fila("od-real")))
        coEvery { discountRepository.removeDiscount(any(), any(), any(), any()) } returns Result.success(null)
        coEvery { orderRepository.getOrder(venueId, "o-1") } returnsMany listOf(
            Result.success(orden(filas = listOf(fila("od-real")))),
            Result.success(orden(filas = emptyList())),
        )
        val vm = crear()
        vm.applyManualDiscount(DiscountType.FIXED, 10.0, "x")
        assertThat(ids(vm)).containsExactly("od-real")
        vm.removeDiscount("od-real")
        coVerify(exactly = 1) { discountRepository.removeDiscount(venueId, "o-1", "od-real", any()) }
        assertThat(ids(vm)).isEmpty()
    }

    @Test fun `si la lectura final falla la fila llega con la relectura de los 5 segundos`() {
        coEvery { discountRepository.applyManualDiscount(any(), any(), any(), any(), any(), any(), any(), any()) } returns Result.success(acuse(fila("od-x")))
        coEvery { orderRepository.getOrder(venueId, "o-1") } returnsMany listOf(
            Result.failure(IOException("sin red")),
            Result.success(orden(filas = listOf(fila("od-x")))),
        )
        val vm = crear()
        vm.applyManualDiscount(DiscountType.FIXED, 10.0, "x")
        assertThat(ids(vm)).isEmpty()
        assertThat(vm.descuentoEnCurso.value).isFalse()
        testDispatcher.scheduler.advanceTimeBy(6_000)
        testDispatcher.scheduler.runCurrent()
        assertThat(ids(vm)).containsExactly("od-x")
    }

    @Test fun `SERVIDOR viejo sin id y lecturas caidas - el total baja por los dos descuentos y no sale ninguna fila inventada`() {
        coEvery { discountRepository.applyManualDiscount(any(), any(), any(), any(), any(), any(), any(), any()) } returnsMany listOf(
            Result.success(acuse(fila(ID_DE_FILA_SIN_CONFIRMAR, "10.00"))),
            Result.success(acuse(fila(ID_DE_FILA_SIN_CONFIRMAR, "5.00"))),
        )
        val vm = crear()
        vm.applyManualDiscount(DiscountType.FIXED, 10.0, "x")
        vm.applyManualDiscount(DiscountType.FIXED, 5.0, "y")
        assertThat(ids(vm)).isEmpty()
        assertThat((vm.state.value as MenuState.Success).order.total).isEqualTo(BigDecimal("85.00"))
    }

    @Test fun `con la lectura final el total es el del SERVIDOR si los renglones no cambiaron`() {
        coEvery { discountRepository.applyManualDiscount(any(), any(), any(), any(), any(), any(), any(), any()) } returns Result.success(acuse(fila("od-x")))
        coEvery { orderRepository.getOrder(venueId, "o-1") } returns Result.success(orden(descuentos = "10.00", total = "88.00", filas = listOf(fila("od-x"))))
        val vm = crear()
        vm.applyManualDiscount(DiscountType.FIXED, 10.0, "x")
        val cuenta = (vm.state.value as MenuState.Success).order
        assertThat(cuenta.total).isEqualTo(BigDecimal("88.00"))
        assertThat(cuenta.discountAmount).isEqualTo(BigDecimal("10.00"))
    }

    // ------------------------------------------------------------------------------------------------
    // Sin confirmar y la llave del envío (D21)
    // ------------------------------------------------------------------------------------------------

    @Test fun `aplicar sin respuesta y sin PIN avisa, relee sin tocar Room y no se reenvia`() {
        coEvery { discountRepository.applyManualDiscount(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.failure(DescuentoSinConfirmarException(IOException("timeout")))
        coEvery { orderRepository.getOrder(venueId, "o-1") } returns Result.success(orden(filas = listOf(fila("od-llego"))))
        val vm = crear()
        escucha(vm)
        vm.applyManualDiscount(DiscountType.FIXED, 10.0, "x")
        assertThat(avisos).contains(TEXTO_APLICAR_SIN_CONFIRMAR)
        assertThat(ids(vm)).containsExactly("od-llego")
        coVerify(exactly = 1) { discountRepository.applyManualDiscount(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { orderSyncCoordinator.cacheBackendOrder(any()) }
    }

    @Test fun `cortesia con PIN sin confirmar toma las filas de la lectura y no reescribe Room`() {
        coEvery { autorizacion.paraCortesia(venueId, any(), false) } returns Autorizacion.ConPin("tok-1")
        coEvery { discountRepository.applyManualDiscount(any(), any(), any(), any(), any(), any(), "tok-1", any()) } returns
            Result.failure(IOException("timeout"))
        coEvery { orderSyncCoordinator.getLocalOrder("o-1") } returns orden()
        coEvery { orderRepository.getOrder(venueId, "o-1") } returns Result.success(orden(filas = listOf(fila("od-cortesia"))))
        val vm = crear()
        escucha(vm)
        vm.applyManualDiscount(DiscountType.PERCENTAGE, 100.0, "Cortesía")
        assertThat(avisos).contains("No pudimos confirmar la cortesía. Revisa la cuenta")
        assertThat(ids(vm)).containsExactly("od-cortesia")
        coVerify(exactly = 0) { orderSyncCoordinator.cacheBackendOrder(any()) }
    }

    @Test fun `volver a dar el mismo descuento tras sin confirmar manda la misma llave, otro descuento una nueva`() {
        val llaves = mutableListOf<String?>()
        coEvery { discountRepository.applyManualDiscount(any(), any(), any(), any(), any(), any(), any(), captureNullable(llaves)) } returnsMany listOf(
            Result.failure(DescuentoSinConfirmarException(IOException("timeout"))),
            Result.success(acuse(fila("od-x"))),
            Result.success(acuse(fila("od-y"))),
            Result.success(acuse(fila("od-z"))),
        )
        val vm = crear()
        vm.applyManualDiscount(DiscountType.FIXED, 10.0, "x")   // sin confirmar
        vm.applyManualDiscount(DiscountType.FIXED, 10.0, "x")   // el mismo: misma llave
        vm.applyManualDiscount(DiscountType.FIXED, 10.0, "x")   // ya tuvo respuesta: llave nueva
        vm.applyManualDiscount(DiscountType.FIXED, 7.0, "x")    // otro: llave nueva
        assertThat(llaves).hasSize(4)
        assertThat(llaves.all { !it.isNullOrBlank() }).isTrue()
        assertThat(llaves[1]).isEqualTo(llaves[0])
        assertThat(llaves[2]).isNotEqualTo(llaves[1])
        assertThat(llaves[3]).isNotEqualTo(llaves[2])
    }

    @Test fun `el reenvio con el PIN forzado lleva la misma llave`() {
        val llaves = mutableListOf<String?>()
        coEvery { autorizacion.paraCortesia(venueId, any(), true) } returns Autorizacion.ConPin("tok-f")
        coEvery { discountRepository.applyManualDiscount(any(), any(), any(), any(), any(), any(), null, captureNullable(llaves)) } returns
            Result.failure(PermisoRequeridoException("orders:comp", true, "x"))
        coEvery { discountRepository.applyManualDiscount(any(), any(), any(), any(), any(), any(), "tok-f", captureNullable(llaves)) } returns
            Result.success(acuse(fila("od-x")))
        val vm = crear(subtotal = "100.00", total = "100.00")
        vm.applyManualDiscount(DiscountType.PERCENTAGE, 99.999, "x")
        assertThat(llaves).hasSize(2)
        assertThat(llaves[1]).isEqualTo(llaves[0])
    }

    @Test fun `el catalogo repetido tras sin confirmar manda la misma llave`() {
        val llaves = mutableListOf<String?>()
        coEvery { discountRepository.applyPredefinedDiscount(any(), any(), any(), any(), captureNullable(llaves)) } returnsMany listOf(
            Result.failure(DescuentoSinConfirmarException(IOException("timeout"))),
            Result.success(acuse(fila("od-c"))),
        )
        val vm = crear()
        vm.applyPredefinedDiscount("d-1")
        vm.applyPredefinedDiscount("d-1")
        assertThat(llaves).hasSize(2)
        assertThat(llaves[1]).isEqualTo(llaves[0])
    }

    // ------------------------------------------------------------------------------------------------
    // Textos
    // ------------------------------------------------------------------------------------------------

    @Test fun `aplicar del catalogo rechazado dice la razon`() {
        coEvery { discountRepository.applyPredefinedDiscount(any(), any(), any(), any(), any()) } returns
            Result.failure(Exception("Este descuento no aplica a esta cuenta."))
        val vm = crear()
        escucha(vm)
        vm.applyPredefinedDiscount("d-1")
        assertThat(avisos).contains("Este descuento no aplica a esta cuenta.")
    }

    @Test fun `un rechazo en ingles sale en espanol`() {
        coEvery { discountRepository.applyPredefinedDiscount(any(), any(), "d-1", any(), any()) } returns
            Result.failure(Exception("Discount already applied to this order"))
        coEvery { discountRepository.applyPredefinedDiscount(any(), any(), "d-2", any(), any()) } returns
            Result.failure(Exception("This discount cannot be applied to this order"))
        val vm = crear()
        escucha(vm)
        vm.applyPredefinedDiscount("d-1")
        vm.applyPredefinedDiscount("d-2")
        assertThat(avisos).doesNotContain(TEXTO_ESPERA_EL_DESCUENTO)
        coVerify(exactly = 1) { discountRepository.applyPredefinedDiscount(any(), any(), "d-2", any(), any()) }
        assertThat(avisos).containsAtLeast(TEXTO_DESCUENTO_YA_APLICADO, TEXTO_NO_SE_PUDO_APLICAR)
        assertThat(avisos.none { it.contains("Discount") || it.contains("This discount") }).isTrue()
    }

    @Test fun `cupon sin respuesta dice sin confirmar y relee`() {
        coEvery { discountRepository.applyCoupon(any(), any(), any()) } returns Result.failure(DescuentoSinConfirmarException(IOException("timeout")))
        coEvery { orderRepository.getOrder(venueId, "o-1") } returns Result.success(orden(filas = listOf(fila("od-cupon"))))
        val vm = crear()
        vm.applyCoupon("PROMO")
        assertThat(vm.couponValidationState.value).isEqualTo(CouponValidationState.Invalid(TEXTO_APLICAR_SIN_CONFIRMAR))
        assertThat(ids(vm)).containsExactly("od-cupon")
    }

    @Test fun `el cupon sincroniza la cuenta antes de mandarse`() {
        coEvery { orderSyncCoordinator.syncOrderImmediately("local_1") } returns "o-1"
        every { orderSyncCoordinator.resolveOrderId("local_1") } returns "o-1"
        coEvery { discountRepository.applyCoupon(any(), any(), any()) } returns Result.success(acuse(fila("od-cupon")))
        val vm = crear(id = "local_1")
        vm.applyCoupon("PROMO")
        coVerify(exactly = 1) { discountRepository.applyCoupon(venueId, "o-1", "PROMO") }
    }

    @Test fun `sin el id del SERVIDOR para la cuenta no se manda el descuento`() {
        coEvery { orderSyncCoordinator.syncOrderImmediately("local_1") } returns "local_1"
        val vm = crear(id = "local_1")
        escucha(vm)
        vm.applyCoupon("PROMO")
        vm.applyManualDiscount(DiscountType.FIXED, 10.0, "x")
        vm.applyPredefinedDiscount("d-1")
        coVerify(exactly = 0) { discountRepository.applyCoupon(any(), any(), any()) }
        coVerify(exactly = 0) { discountRepository.applyManualDiscount(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { discountRepository.applyPredefinedDiscount(any(), any(), any(), any(), any()) }
        assertThat(avisos).contains(TEXTO_CUENTA_SIN_SUBIR)
        assertThat(vm.descuentoEnCurso.value).isFalse()
    }

    @Test fun `quitar con exito toma la lista de la lectura final`() {
        coEvery { discountRepository.removeDiscount(any(), any(), any(), any()) } returns Result.success(null)
        coEvery { orderRepository.getOrder(venueId, "o-1") } returns Result.success(orden(filas = listOf(fila("od-2"))))
        val vm = crear()
        ponerLista(vm, listOf(fila("od-1"), fila("od-2")))
        vm.removeDiscount("od-1")
        assertThat(ids(vm)).containsExactly("od-2")
        coVerify(exactly = 1) { orderRepository.getOrder(venueId, "o-1") }
    }

    @Test fun `quitar una rebaja fija de vales dice la razon de D15 y relee`() {
        coEvery { discountRepository.removeDiscount(any(), any(), any(), any()) } returns Result.failure(Exception(TEXTO_REBAJA_FIJA_DE_VALES))
        coEvery { orderRepository.getOrder(venueId, "o-1") } returns Result.success(orden(filas = listOf(fila("od-vale"))))
        val vm = crear()
        ponerLista(vm, listOf(fila("od-vale")))
        escucha(vm)
        vm.removeDiscount("od-vale")
        assertThat(avisos).contains(TEXTO_REBAJA_FIJA_DE_VALES)
        assertThat(ids(vm)).containsExactly("od-vale")
    }

    @Test fun `quitar sin respuesta lo dice y la fila sigue`() {
        coEvery { discountRepository.removeDiscount(any(), any(), any(), any()) } returns
            Result.failure(DescuentoSinConfirmarException(IOException("sin red")))
        val vm = crear()
        ponerLista(vm, listOf(fila("od-1")))
        escucha(vm)
        vm.removeDiscount("od-1")
        assertThat(avisos).contains(TEXTO_QUITAR_SIN_CONFIRMAR)
        assertThat(ids(vm)).containsExactly("od-1")
    }

    // ------------------------------------------------------------------------------------------------
    // Nadie más escribe la lista · «Pagar»
    // ------------------------------------------------------------------------------------------------

    @Test fun `Room y las copias locales de la cuenta no tocan la lista`() {
        val renglon = renglon()
        val vm = crear(renglones = listOf(renglon), filasDeLaCopia = listOf(fila("od-vieja")))
        ponerLista(vm, listOf(fila("od-nueva")))
        vm.printSingleItem(renglon)
        coVerify(timeout = 3_000) { orderSyncCoordinator.markItemAsSentToKitchen(renglon.id, any()) }
        Thread.sleep(200)
        assertThat(ids(vm)).containsExactly("od-nueva")
        ponerCuentaAbierta(vm, "o-1")
        runBlocking { room.emit(orden(renglones = listOf(renglon))) }
        assertThat(ids(vm)).containsExactly("od-nueva")
    }

    @Test fun `Pagar con un descuento en curso avisa y no prepara el cobro`() {
        val respuesta = CompletableDeferred<Result<AcuseDeDescuento>>()
        coEvery { discountRepository.applyManualDiscount(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers { respuesta.await() }
        val vm = crear()
        escucha(vm)
        vm.applyManualDiscount(DiscountType.FIXED, 10.0, "x")
        var listo = false
        vm.onPaymentRequested(onReady = { listo = true }, onError = {})
        assertThat(avisos).contains(TEXTO_ESPERA_EL_DESCUENTO)
        assertThat(listo).isFalse()
        assertThat(vm.isPreparingPayment.value).isFalse()
    }

    // ------------------------------------------------------------------------------------------------
    // Ayudantes
    // ------------------------------------------------------------------------------------------------

    private fun ids(vm: MenuViewModel) = vm.appliedDiscounts.value.map { it.id }

    private fun escucha(vm: MenuViewModel) {
        escuchas += CoroutineScope(Dispatchers.Unconfined).launch {
            vm.uiEvents.collect { if (it is MenuViewModel.MenuUiEvent.ShowSnackbar) avisos.add(it.message) }
        }
    }

    private fun pagoCompletado(orderId: String) = SocketEvent.PaymentCompleted(
        paymentId = "p-1", amount = 5000, currency = "MXN", tableId = null, orderId = orderId, venueId = venueId, timestamp = "ahora",
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> campo(vm: MenuViewModel, nombre: String): MutableStateFlow<T> =
        MenuViewModel::class.java.getDeclaredField(nombre).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>

    private fun ponerLista(vm: MenuViewModel, filas: List<OrderDiscount>) { campo<List<OrderDiscount>>(vm, "_appliedDiscounts").value = filas }
    private fun ponerCuentaAbierta(vm: MenuViewModel, id: String) { campo<String?>(vm, "_currentOrderId").value = id }
    private fun cuentaAbierta(vm: MenuViewModel) = campo<String?>(vm, "_currentOrderId").value

    private fun crear(
        estado: Unit? = Unit,
        id: String = "o-1",
        subtotal: String = "100.00",
        descuentos: String = "0.00",
        total: String? = null,
        renglones: List<OrderItem> = emptyList(),
        filasDeLaCopia: List<OrderDiscount> = emptyList(),
    ): MenuViewModel {
        val secureStorage = mockk<SecureStorage>(relaxed = true)
        val deviceInfoManager = mockk<DeviceInfoManager>(relaxed = true)
        val socketManager = mockk<SocketManager>(relaxed = true)
        val connectionEventManager = mockk<ConnectionEventManager>(relaxed = true)
        val productRepository = mockk<ProductRepository>(relaxed = true)
        val productDao = mockk<ProductDao>(relaxed = true)
        val productCategoryDao = mockk<ProductCategoryDao>(relaxed = true)
        every { secureStorage.getOrderingProductDisplayMode() } returns com.jaac.avoqado_tpv.features.ordering.domain.ProductDisplayMode.GRID_3
        every { secureStorage.getStaffId() } returns "staff-1"
        every { deviceInfoManager.getVenueId() } returns venueId
        every { socketManager.events } returns socketEvents
        every { connectionEventManager.connectionRestoredEvents } returns reconexiones
        coEvery { productCategoryDao.getAllCategories(any()) } returns emptyList()
        coEvery { productRepository.getCategories(venueId) } returns Result.success(emptyList())
        val vm = MenuViewModel(
            context = mockk(relaxed = true),
            secureStorage = secureStorage,
            deviceInfoManager = deviceInfoManager,
            productRepository = productRepository,
            orderRepository = orderRepository,
            tableRepository = tableRepository,
            discountRepository = discountRepository,
            customerRepository = mockk(relaxed = true),
            socketManager = socketManager,
            orderSyncCoordinator = orderSyncCoordinator,
            connectionEventManager = connectionEventManager,
            productDao = productDao,
            catalogoGuardado = CatalogoGuardado(productRepository, productDao),
            productCategoryDao = productCategoryDao,
            printerManager = mockk(relaxed = true),
            paymentApiService = mockk(relaxed = true),
            permissionsRepository = mockk(relaxed = true),
            autorizacionDeGerente = autorizacion,
        ).also { creados.add(it) }
        if (estado != null) {
            campo<MenuState>(vm, "_state").value = MenuState.Success(
                orden(id = id, subtotal = subtotal, descuentos = descuentos, total = total, renglones = renglones, filas = filasDeLaCopia),
            )
        }
        return vm
    }

    private fun orden(
        id: String = "o-1",
        subtotal: String = "100.00",
        descuentos: String = "0.00",
        total: String? = null,
        renglones: List<OrderItem> = emptyList(),
        filas: List<OrderDiscount> = emptyList(),
    ): Order {
        val elTotal = total?.let { BigDecimal(it) } ?: BigDecimal(subtotal).subtract(BigDecimal(descuentos))
        return Order(
            id = id, orderNumber = "1", venueId = venueId, tableId = null, tableName = null, covers = 1,
            waiterId = null, waiterName = null, status = OrderStatus.OPEN, kitchenStatus = KitchenStatus.PENDING,
            paymentStatus = PaymentStatus.PENDING, orderType = OrderType.DINE_IN, items = renglones,
            subtotal = BigDecimal(subtotal), discountAmount = BigDecimal(descuentos), tax = BigDecimal.ZERO,
            total = elTotal, remainingBalance = elTotal, notes = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
            version = 1, discounts = filas,
        )
    }

    private fun fila(id: String, monto: String = "10.00") = OrderDiscount(
        id = id, orderId = "o-1", discountId = null, couponCodeId = null, name = "Descuento $id", type = DiscountType.FIXED,
        value = BigDecimal(monto), amount = BigDecimal(monto), reason = null, appliedBy = "", appliedAt = Instant.EPOCH,
    )

    private fun acuse(fila: OrderDiscount, total: String? = null) = AcuseDeDescuento(fila, total?.let { BigDecimal(it) })

    private fun renglon() = OrderItem(
        id = "i-1", orderId = "o-1", productId = "p-1", productName = "Café", productSku = null, quantity = 1,
        unitPrice = BigDecimal("100.00"), totalPrice = BigDecimal("100.00"), notes = null, kitchenStatus = KitchenStatus.PENDING,
        createdAt = Instant.EPOCH, sentToKitchenAt = null,
    )
}

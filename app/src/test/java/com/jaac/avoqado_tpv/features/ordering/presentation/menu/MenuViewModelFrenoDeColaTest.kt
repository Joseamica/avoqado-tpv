package com.jaac.avoqado_tpv.features.ordering.presentation.menu

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.CatalogoGuardado
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.dao.ProductCategoryDao
import com.jaac.avoqado_tpv.core.data.local.dao.ProductDao
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.core.util.ConnectionEventManager
import com.jaac.avoqado_tpv.core.util.ConnectionRestoredEvent
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.features.ordering.domain.ConflictException
import com.jaac.avoqado_tpv.features.ordering.domain.KitchenStatus
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.OrderItem
import com.jaac.avoqado_tpv.features.ordering.domain.OrderRepository
import com.jaac.avoqado_tpv.features.ordering.domain.OrderStatus
import com.jaac.avoqado_tpv.features.ordering.domain.OrderSyncCoordinator
import com.jaac.avoqado_tpv.features.ordering.domain.OrderType
import com.jaac.avoqado_tpv.features.ordering.domain.PaymentStatus
import com.jaac.avoqado_tpv.features.ordering.domain.ProductRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant
import java.util.Collections

/**
 * Libreta de Órdenes · fase 1 MÍNIMA (encargo 3a34fd): «Pagar» y «Anular» no abren mientras haya productos sin subir; lo dicen
 * y la terminal los vuelve a subir. Room real y el coordinador real; sólo el SERVIDOR es de mentiras. El caso: la cajera agrega un
 * café, otra caja cambió la cuenta (409) y el café sigue sin subir cuando toca «Pagar» o «Anular».
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class MenuViewModelFrenoDeColaTest {

    private val venue = "venue-1"
    private val cuenta = "cuenta-srv-1"
    private val testDispatcher = UnconfinedTestDispatcher()
    private val creados = mutableListOf<MenuViewModel>()
    private val avisos: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val tacos = renglon("renglon-srv-tacos", "prod-tacos", "Tacos", "100.00")

    private lateinit var db: AvoqadoDatabase
    private lateinit var repo: OrderRepository
    private lateinit var coordinator: OrderSyncCoordinator

    @Before fun preparar() {
        Dispatchers.setMain(testDispatcher)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = mockk(relaxed = true)
        coordinator = OrderSyncCoordinator(db.draftOrderDao(), db.draftOrderItemDao(), repo)
    }

    @After fun limpiar() {
        try {
            runBlocking {
                creados.forEach { it.viewModelScope.cancel() }
                creados.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            }
        } finally {
            creados.clear()
            coordinator.cleanup()
            db.close()
            Dispatchers.resetMain()
            unmockkAll()
        }
    }

    /** La cuenta con tacos abierta en la caja, un café agregado sin subir, y otra caja que ya cambió la cuenta (409). */
    private fun cuentaConCafeSinSubir(): Order = runBlocking {
        coordinator.cacheBackendOrder(delServidor(2, tacos))
        coordinator.addItemToLocalOrder(cuenta, "prod-cafe", "Café", 1, "50.00", emptyList(), null)
        coEvery { repo.addItemsToOrder(venue, cuenta, any(), any(), any()) } returns Result.failure(ConflictException("3"))
        coEvery { repo.getOrder(venue, cuenta) } returns Result.success(delServidor(3, tacos))
        coordinator.getLocalOrder(cuenta)!!
    }

    @Test fun `Pagar con un producto sin subir no abre el cobro y lo dice`() {
        val vm = crear(cuentaConCafeSinSubir())
        var listo: Order? = null
        var error: String? = null

        vm.onPaymentRequested(onReady = { listo = it }, onError = { error = it })
        esperar { listo != null || error != null }

        assertThat(listo).isNull() // antes: cobraba $100, sin el café
        assertThat(error).contains("todavía no se suben")
        esperar { avisos.isNotEmpty() }
        assertThat(avisos.joinToString(" | ")).contains("todavía no se suben") // se ve en pantalla (antes sólo se registraba)
        val cafe = runBlocking { db.draftOrderItemDao().getAllItemsByOrderId(cuenta) }.single { it.productId == "prod-cafe" }
        assertThat(cafe.syncStatus).isEqualTo("PENDING")
    }

    @Test fun `Anular con un producto sin subir no anula y lo dice`() {
        val vm = crear(cuentaConCafeSinSubir())

        vm.voidItems(listOf("renglon-srv-tacos"), "se equivocó")
        esperar { avisos.isNotEmpty() }

        coVerify(exactly = 0) { repo.voidItems(any(), any(), any(), any(), any(), any()) }
        assertThat(avisos.joinToString(" | ")).contains("todavía no se suben")
    }

    @Test fun `Pagar sin nada en cola abre el cobro con la cuenta del SERVIDOR (regresion)`() {
        runBlocking { coordinator.cacheBackendOrder(delServidor(2, tacos)) }
        coEvery { repo.getOrder(venue, cuenta) } returns Result.success(delServidor(2, tacos))
        val vm = crear(runBlocking { coordinator.getLocalOrder(cuenta)!! })
        var listo: Order? = null
        var error: String? = null

        vm.onPaymentRequested(onReady = { listo = it }, onError = { error = it })
        esperar { listo != null || error != null }

        assertThat(error).isNull()
        assertThat(listo?.total).isEqualToIgnoringScale("100.00")
    }

    private fun esperar(condicion: () -> Boolean) = runBlocking {
        withTimeout(20_000) { while (!condicion()) delay(20) }
    }

    private fun crear(order: Order): MenuViewModel {
        val secureStorage = mockk<SecureStorage>(relaxed = true)
        val deviceInfoManager = mockk<DeviceInfoManager>(relaxed = true)
        val socketManager = mockk<SocketManager>(relaxed = true)
        val connectionEventManager = mockk<ConnectionEventManager>(relaxed = true)
        val productRepository = mockk<ProductRepository>(relaxed = true)
        val productDao = mockk<ProductDao>(relaxed = true)
        val productCategoryDao = mockk<ProductCategoryDao>(relaxed = true)
        every { secureStorage.getStaffId() } returns "staff-1"
        every { secureStorage.getOrderingProductDisplayMode() } returns com.jaac.avoqado_tpv.features.ordering.domain.ProductDisplayMode.GRID_3
        every { deviceInfoManager.getVenueId() } returns venue
        every { socketManager.events } returns MutableSharedFlow<SocketEvent>()
        every { connectionEventManager.connectionRestoredEvents } returns MutableSharedFlow<ConnectionRestoredEvent>()
        coEvery { productCategoryDao.getAllCategories(any()) } returns emptyList()
        coEvery { productRepository.getCategories(venue) } returns Result.success(emptyList())
        val vm = MenuViewModel(
            context = mockk(relaxed = true),
            secureStorage = secureStorage,
            deviceInfoManager = deviceInfoManager,
            productRepository = productRepository,
            orderRepository = repo,
            tableRepository = mockk(relaxed = true),
            discountRepository = mockk(relaxed = true),
            customerRepository = mockk(relaxed = true),
            socketManager = socketManager,
            orderSyncCoordinator = coordinator,
            connectionEventManager = connectionEventManager,
            productDao = productDao,
            catalogoGuardado = CatalogoGuardado(productRepository, productDao),
            productCategoryDao = productCategoryDao,
            printerManager = mockk(relaxed = true),
            paymentApiService = mockk(relaxed = true),
            permissionsRepository = mockk(relaxed = true),
            autorizacionDeGerente = mockk(relaxed = true),
        ).also { creados.add(it) }
        vm.viewModelScope.launch {
            vm.uiEvents.collect { if (it is MenuViewModel.MenuUiEvent.ShowSnackbar) avisos.add(it.message) }
        }
        val campo = MenuViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (campo.get(vm) as MutableStateFlow<MenuState>).value = MenuState.Success(order)
        return vm
    }

    private fun renglon(id: String, productId: String, nombre: String, precio: String) = OrderItem(
        id = id, orderId = cuenta, productId = productId, productName = nombre, productSku = null, quantity = 1,
        unitPrice = BigDecimal(precio), totalPrice = BigDecimal(precio), notes = null, kitchenStatus = KitchenStatus.PENDING,
        createdAt = Instant.parse("2026-11-20T12:00:00Z"), sentToKitchenAt = null,
    )

    private fun delServidor(version: Int, vararg renglones: OrderItem): Order {
        val total = renglones.fold(BigDecimal.ZERO) { s, r -> s + r.totalPrice }
        return Order(
            id = cuenta, orderNumber = "ORD-1", venueId = venue, tableId = null, tableName = null, covers = 1, waiterId = null,
            waiterName = null, status = OrderStatus.OPEN, kitchenStatus = KitchenStatus.PENDING, paymentStatus = PaymentStatus.PENDING,
            orderType = OrderType.TAKEOUT, items = renglones.toList(), subtotal = total, tax = BigDecimal.ZERO, total = total,
            remainingBalance = total, notes = null, createdAt = Instant.parse("2026-11-20T12:00:00Z"),
            updatedAt = Instant.parse("2026-11-20T12:00:00Z"), version = version,
        )
    }
}

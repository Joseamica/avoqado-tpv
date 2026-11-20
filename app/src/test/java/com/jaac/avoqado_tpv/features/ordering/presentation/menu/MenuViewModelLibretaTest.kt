package com.jaac.avoqado_tpv.features.ordering.presentation.menu

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.CatalogoGuardado
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.dao.ProductCategoryDao
import com.jaac.avoqado_tpv.core.data.local.dao.ProductDao
import com.jaac.avoqado_tpv.core.data.local.dao.ResultadoDeLectura
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.core.util.ConnectionEventManager
import com.jaac.avoqado_tpv.core.util.ConnectionRestoredEvent
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant

/**
 * Libreta de Órdenes · fase 1 (encargo 95c69a): «Pagar» y «Anular» no salen con productos sin subir ni con una cuenta que otro
 * aparato cambió sin que la caja la viera, y «Anular» manda el renglón que la caja eligió (Codex D16 r6 #3 y #6, fase 1 r1 #11 y #12).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class MenuViewModelLibretaTest {

    private val venueId = "venue-1"
    private val testDispatcher = UnconfinedTestDispatcher()
    private val creados = mutableListOf<MenuViewModel>()
    private val avisos = mutableListOf<String>()

    private lateinit var orderRepository: OrderRepository
    private lateinit var coordinator: OrderSyncCoordinator
    private lateinit var secureStorage: SecureStorage

    @Before fun preparar() {
        Dispatchers.setMain(testDispatcher)
        orderRepository = mockk(relaxed = true)
        coordinator = mockk(relaxed = true)
        secureStorage = mockk(relaxed = true)
        every { coordinator.syncEvents } returns MutableSharedFlow<OrderSyncCoordinator.SyncEvent>()
        coEvery { coordinator.syncOrderImmediately(any()) } answers { firstArg() }
        coEvery { coordinator.getLocalOrder(any()) } returns null
        coEvery { coordinator.hayRenglonesSinSubir(any()) } returns false
        coEvery { coordinator.cacheBackendOrder(any()) } returns ResultadoDeLectura.REEMPLAZADA
        every { secureStorage.getStaffId() } returns "staff-1"
    }

    @After fun limpiar() {
        try {
            runBlocking {
                creados.forEach { it.viewModelScope.cancel() }
                creados.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            }
        } finally {
            creados.clear()
            Dispatchers.resetMain()
            unmockkAll()
        }
    }

    @Test fun `Pagar con productos sin subir no abre el cobro y lo dice`() {
        coEvery { coordinator.hayRenglonesSinSubir("o-1") } returns true
        val vm = crear(orden("100.00"))
        var listo: Order? = null
        var error: String? = null

        vm.onPaymentRequested(onReady = { listo = it }, onError = { error = it })

        assertThat(listo).isNull()
        assertThat(error).isEqualTo(TEXTO_COLA_ANTES_DE_COBRAR)
        assertThat(avisos).contains(TEXTO_COLA_ANTES_DE_COBRAR)
        coVerify(exactly = 0) { orderRepository.getOrder(any(), any()) }
    }

    @Test fun `Pagar con una cuenta que cambio en otro aparato la muestra y no abre el cobro`() {
        coEvery { orderRepository.getOrder(venueId, "o-1") } returns Result.success(orden("150.00"))
        val vm = crear(orden("100.00"))
        var listo: Order? = null
        var error: String? = null

        vm.onPaymentRequested(onReady = { listo = it }, onError = { error = it })

        assertThat(listo).isNull()
        assertThat(error).isEqualTo(textoTotalCambio(BigDecimal("150.00")))
    }

    @Test fun `Pagar con un total menor del SERVIDOR (un porcentaje recalculado) abre el cobro con el del SERVIDOR`() {
        coEvery { orderRepository.getOrder(venueId, "o-1") } returns Result.success(orden("90.00"))
        val vm = crear(orden("100.00"))
        var listo: Order? = null

        vm.onPaymentRequested(onReady = { listo = it }, onError = {})

        assertThat(listo?.total).isEqualToIgnoringScale("90.00")
    }

    @Test fun `Pagar con una lectura que dejo cola no abre el cobro`() {
        coEvery { orderRepository.getOrder(venueId, "o-1") } returns Result.success(orden("100.00"))
        coEvery { coordinator.cacheBackendOrder(any()) } returns ResultadoDeLectura.CON_COLA
        val vm = crear(orden("100.00"))
        var listo: Order? = null
        var error: String? = null

        vm.onPaymentRequested(onReady = { listo = it }, onError = { error = it })

        assertThat(listo).isNull()
        assertThat(error).isEqualTo(TEXTO_CUENTA_CAMBIO)
    }

    @Test fun `Pagar con la cuenta igual abre el cobro (regresion)`() {
        coEvery { orderRepository.getOrder(venueId, "o-1") } returns Result.success(orden("100.00"))
        val vm = crear(orden("100.00"))
        var listo: Order? = null

        vm.onPaymentRequested(onReady = { listo = it }, onError = {})

        assertThat(listo?.total).isEqualToIgnoringScale("100.00")
    }

    @Test fun `Anular con productos sin subir no lee ni anula y lo dice`() {
        coEvery { coordinator.hayRenglonesSinSubir("o-1") } returns true
        val vm = crear(orden("100.00", cafe("L1")))

        vm.voidItems(listOf("L1"), "se equivocó")

        assertThat(avisos).contains(TEXTO_COLA_ANTES_DE_ANULAR)
        coVerify(exactly = 0) { orderRepository.getOrder(any(), any()) }
        coVerify(exactly = 0) { orderRepository.voidItems(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `Anular manda el renglon que la caja eligio aunque haya otro igual`() {
        // Dos cafés de $50 con distinta nota: la caja anula el SEGUNDO.
        val enPantalla = orden("100.00", cafe("L1", notas = "sin azúcar"), cafe("L2", notas = "con leche"))
        val delServidor = orden("100.00", cafe("S1", externalId = "L1", notas = "sin azúcar"), cafe("S2", externalId = "L2", notas = "con leche"))
        coEvery { orderRepository.getOrder(venueId, "o-1") } returns Result.success(delServidor)
        val vm = crear(enPantalla)

        vm.voidItems(listOf("L2"), "se equivocó")

        coVerify(exactly = 1) { orderRepository.voidItems(venueId, "o-1", listOf("S2"), any(), any(), any()) }
    }

    private fun crear(order: Order): MenuViewModel {
        val deviceInfoManager = mockk<DeviceInfoManager>(relaxed = true)
        val socketManager = mockk<SocketManager>(relaxed = true)
        val connectionEventManager = mockk<ConnectionEventManager>(relaxed = true)
        val productRepository = mockk<ProductRepository>(relaxed = true)
        val productDao = mockk<ProductDao>(relaxed = true)
        val productCategoryDao = mockk<ProductCategoryDao>(relaxed = true)
        every { secureStorage.getOrderingProductDisplayMode() } returns com.jaac.avoqado_tpv.features.ordering.domain.ProductDisplayMode.GRID_3
        every { deviceInfoManager.getVenueId() } returns venueId
        every { socketManager.events } returns MutableSharedFlow<SocketEvent>()
        every { connectionEventManager.connectionRestoredEvents } returns MutableSharedFlow<ConnectionRestoredEvent>()
        coEvery { productCategoryDao.getAllCategories(any()) } returns emptyList()
        coEvery { productRepository.getCategories(venueId) } returns Result.success(emptyList())
        val vm = MenuViewModel(
            context = mockk(relaxed = true),
            secureStorage = secureStorage,
            deviceInfoManager = deviceInfoManager,
            productRepository = productRepository,
            orderRepository = orderRepository,
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

    private fun cafe(id: String, externalId: String? = null, notas: String? = null) = OrderItem(
        id = id, orderId = "o-1", productId = "prod-cafe", productName = "Café", productSku = null, quantity = 1,
        unitPrice = BigDecimal("50.00"), totalPrice = BigDecimal("50.00"), notes = notas, kitchenStatus = KitchenStatus.PENDING,
        createdAt = Instant.EPOCH, sentToKitchenAt = null, externalId = externalId,
    )

    private fun orden(total: String, vararg renglones: OrderItem) = Order(
        id = "o-1", orderNumber = "1", venueId = venueId, tableId = null, tableName = null, covers = 1,
        waiterId = null, waiterName = null, status = OrderStatus.OPEN, kitchenStatus = KitchenStatus.PENDING,
        paymentStatus = PaymentStatus.PENDING, orderType = OrderType.DINE_IN, items = renglones.toList(),
        subtotal = BigDecimal(total), tax = BigDecimal.ZERO, total = BigDecimal(total), remainingBalance = BigDecimal(total),
        notes = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, version = 1,
    )
}

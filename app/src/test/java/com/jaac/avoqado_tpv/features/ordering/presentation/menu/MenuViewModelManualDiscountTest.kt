package com.jaac.avoqado_tpv.features.ordering.presentation.menu

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
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountRepository
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountType
import com.jaac.avoqado_tpv.features.ordering.domain.KitchenStatus
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.OrderDiscount
import com.jaac.avoqado_tpv.features.ordering.domain.OrderStatus
import com.jaac.avoqado_tpv.features.ordering.domain.OrderSyncCoordinator
import com.jaac.avoqado_tpv.features.ordering.domain.OrderType
import com.jaac.avoqado_tpv.features.ordering.domain.PaymentStatus
import com.jaac.avoqado_tpv.features.ordering.domain.ProductRepository
import com.jaac.avoqado_tpv.features.permissions.presentation.override.Autorizacion
import com.jaac.avoqado_tpv.features.permissions.presentation.override.AutorizacionDeGerente
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import androidx.lifecycle.viewModelScope
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

/** PIN de gerente en la cortesía y en el descuento manual que deja la cuenta en $0 (Menú). */
@OptIn(ExperimentalCoroutinesApi::class)
class MenuViewModelManualDiscountTest {

    private val venueId = "venue-1"
    private val motivo = "Para dar esta cortesía"
    private val testDispatcher = UnconfinedTestDispatcher()
    private val creados = mutableListOf<MenuViewModel>()

    private lateinit var discountRepository: DiscountRepository
    private lateinit var autorizacion: AutorizacionDeGerente
    private lateinit var orderSyncCoordinator: OrderSyncCoordinator

    @Before fun preparar() {
        Dispatchers.setMain(testDispatcher)
        discountRepository = mockk(relaxed = true)
        autorizacion = mockk(relaxed = true)
        orderSyncCoordinator = mockk(relaxed = true)
        every { orderSyncCoordinator.syncEvents } returns MutableSharedFlow<OrderSyncCoordinator.SyncEvent>()
        coEvery { orderSyncCoordinator.syncOrderImmediately(any()) } answers { firstArg() }
        coEvery { discountRepository.applyManualDiscount(any(), any(), any(), any(), any(), any(), any()) } returns Result.success(descuento())
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

    @Test fun `cien_por_ciento_sin_permiso_pide_pin_y_manda_token`() {
        coEvery { autorizacion.paraCortesia(venueId, motivo, false) } returns Autorizacion.ConPin("tok-1")
        crear().applyManualDiscount(DiscountType.PERCENTAGE, 100.0, "Cortesia")
        coVerify(exactly = 1) { autorizacion.paraCortesia(venueId, motivo, false) }
        coVerify(exactly = 1) { discountRepository.applyManualDiscount(venueId, "o-1", DiscountType.PERCENTAGE, 100.0, "Cortesia", null, "tok-1") }
    }

    @Test fun `cien_por_ciento_con_permiso_no_pide_pin`() {
        coEvery { autorizacion.paraCortesia(any(), any(), any()) } returns Autorizacion.NoHaceFalta
        crear().applyManualDiscount(DiscountType.PERCENTAGE, 100.0, "Cortesia")
        coVerify(exactly = 1) { discountRepository.applyManualDiscount(venueId, "o-1", DiscountType.PERCENTAGE, 100.0, "Cortesia", null, null) }
    }

    @Test fun `noventa_y_nueve_sin_permiso_no_pide_pin`() {
        crear().applyManualDiscount(DiscountType.PERCENTAGE, 99.0, "x")
        coVerify(exactly = 0) { autorizacion.paraCortesia(any(), any(), any()) }
        coVerify(exactly = 1) { discountRepository.applyManualDiscount(venueId, "o-1", DiscountType.PERCENTAGE, 99.0, "x", null, null) }
    }

    @Test fun `fijo_igual_a_lo_que_queda_pide_pin`() {
        // subtotal 200 − descuentos 50 = 150 (el total lleva propina e impuestos y no cuenta)
        coEvery { autorizacion.paraCortesia(venueId, motivo, false) } returns Autorizacion.ConPin("tok-2")
        crear(subtotal = "200.00", descuentos = "50.00", total = "190.00").applyManualDiscount(DiscountType.FIXED, 150.0, "x")
        coVerify(exactly = 1) { autorizacion.paraCortesia(venueId, motivo, false) }
        coVerify(exactly = 1) { discountRepository.applyManualDiscount(venueId, "o-1", DiscountType.FIXED, 150.0, "x", null, "tok-2") }
    }

    @Test fun `fijo_un_centavo_menos_no_pide_pin`() {
        crear(subtotal = "200.00", descuentos = "50.00", total = "190.00").applyManualDiscount(DiscountType.FIXED, 149.99, "x")
        coVerify(exactly = 0) { autorizacion.paraCortesia(any(), any(), any()) }
        coVerify(exactly = 1) { discountRepository.applyManualDiscount(venueId, "o-1", DiscountType.FIXED, 149.99, "x", null, null) }
    }

    @Test fun `cancelado_no_llama_al_repositorio`() {
        coEvery { autorizacion.paraCortesia(any(), any(), any()) } returns Autorizacion.Cancelada
        crear().applyManualDiscount(DiscountType.PERCENTAGE, 100.0, "x")
        coVerify(exactly = 0) { discountRepository.applyManualDiscount(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `403_orders_comp_reintenta_una_vez_con_pin_forzado`() {
        coEvery { autorizacion.paraCortesia(venueId, motivo, false) } returns Autorizacion.NoHaceFalta
        coEvery { autorizacion.paraCortesia(venueId, motivo, true) } returns Autorizacion.ConPin("tok-f")
        coEvery { discountRepository.applyManualDiscount(any(), any(), any(), any(), any(), any(), null) } returns
            Result.failure(PermisoRequeridoException("orders:comp", true, "x"))
        crear().applyManualDiscount(DiscountType.PERCENTAGE, 100.0, "x")
        coVerify(exactly = 1) { autorizacion.paraCortesia(venueId, motivo, true) }
        coVerify(exactly = 1) { discountRepository.applyManualDiscount(venueId, "o-1", DiscountType.PERCENTAGE, 100.0, "x", null, "tok-f") }
    }

    @Test fun `porcentaje_menor_a_100_que_el_servidor_rechaza_por_redondeo_pide_pin_y_reintenta`() {
        coEvery { autorizacion.paraCortesia(venueId, motivo, true) } returns Autorizacion.ConPin("tok-r")
        coEvery { discountRepository.applyManualDiscount(any(), any(), any(), any(), any(), any(), null) } returns
            Result.failure(PermisoRequeridoException("orders:comp", true, "x"))
        crear(subtotal = "0.02", total = "0.02").applyManualDiscount(DiscountType.PERCENTAGE, 99.9, "x")
        coVerify(exactly = 0) { autorizacion.paraCortesia(venueId, motivo, false) }
        coVerify(exactly = 1) { autorizacion.paraCortesia(venueId, motivo, true) }
        coVerify(exactly = 1) { discountRepository.applyManualDiscount(venueId, "o-1", DiscountType.PERCENTAGE, 99.9, "x", null, "tok-r") }
    }

    @Test fun `403_con_token_no_pide_otro_pin_y_revisa_la_cuenta`() {
        coEvery { autorizacion.paraCortesia(venueId, motivo, false) } returns Autorizacion.ConPin("tok-1")
        coEvery { discountRepository.applyManualDiscount(any(), any(), any(), any(), any(), any(), "tok-1") } returns
            Result.failure(PermisoRequeridoException("orders:comp", true, "x"))
        crear().applyManualDiscount(DiscountType.PERCENTAGE, 100.0, "x")
        coVerify(exactly = 0) { autorizacion.paraCortesia(venueId, motivo, true) }
        coVerify(exactly = 1) { discountRepository.applyManualDiscount(any(), any(), any(), any(), any(), any(), any()) }
    }

    private fun crear(subtotal: String = "100.00", descuentos: String = "0.00", total: String = "100.00"): MenuViewModel {
        val secureStorage = mockk<SecureStorage>(relaxed = true)
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
            orderRepository = mockk(relaxed = true),
            tableRepository = mockk(relaxed = true),
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
        val campo = MenuViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (campo.get(vm) as MutableStateFlow<MenuState>).value = MenuState.Success(orden(subtotal, descuentos, total))
        return vm
    }

    private fun orden(subtotal: String, descuentos: String, total: String) = Order(
        id = "o-1", orderNumber = "1", venueId = venueId, tableId = null, tableName = null, covers = 1,
        waiterId = null, waiterName = null, status = OrderStatus.OPEN, kitchenStatus = KitchenStatus.PENDING,
        paymentStatus = PaymentStatus.PENDING, orderType = OrderType.DINE_IN, items = emptyList(),
        subtotal = BigDecimal(subtotal), discountAmount = BigDecimal(descuentos), tax = BigDecimal.ZERO,
        total = BigDecimal(total), notes = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, version = 1,
    )

    private fun descuento() = OrderDiscount(
        id = "d-1", orderId = "o-1", discountId = null, couponCodeId = null, name = "Descuento", type = DiscountType.PERCENTAGE,
        value = BigDecimal.ZERO, amount = BigDecimal.ZERO, reason = null, appliedBy = "", appliedAt = Instant.EPOCH,
    )
}

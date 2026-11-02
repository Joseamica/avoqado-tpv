package com.jaac.avoqado_tpv.features.kiosk.presentation

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.gson.Gson
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.features.kiosk.domain.model.KioskProduct
import com.jaac.avoqado_tpv.features.ordering.data.dto.OrderDto
import com.jaac.avoqado_tpv.features.ordering.data.mappers.toOrder
import com.jaac.avoqado_tpv.features.ordering.domain.AddOrderItemRequest
import com.jaac.avoqado_tpv.features.ordering.domain.ConflictException
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierType
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.OrderRepository
import com.jaac.avoqado_tpv.features.ordering.domain.ProductModifier
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal

/**
 * hz:d4d6c503:D1 — kiosco de la terminal: un corte de red al agregar los productos ya no deja una venta huérfana ni crea otra.
 *
 * El SERVIDOR falso de abajo se porta como el real (`order.tpv.service.ts`, develop y main) y contesta en JSON, que pasa por la
 * misma traducción que la terminal (`OrderDto.toOrder()`): crear con la misma `externalId` devuelve la MISMA venta con su versión,
 * sus productos y su estado de ahora; agregar exige la versión actual (409 si no), rechaza una venta cobrada y junta los renglones
 * iguales del envío — reemplaza la cantidad del que ya existía, salvo que el envío traiga varios iguales (`_count > 1`), que SUMA.
 * Promesa y rondas de Codex: `Avoqado/docs/audit/2026-11-01-kiosco-orden-huerfana/`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KioskOrdenHuerfanaTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var servidor: ServidorFalso

    @Before fun preparar() {
        Dispatchers.setMain(testDispatcher)
        servidor = ServidorFalso()
    }

    @After fun limpiar() {
        Dispatchers.resetMain()
    }

    @Test fun `respuesta perdida al agregar - el reintento recupera la misma venta y no crea otra`() = runTest {
        val vm = kiosco()
        vm.addToCart(producto("p-cafe"))
        servidor.alAgregar = listOf(Agregar.LLEGA_PERO_SE_PIERDE_LA_RESPUESTA, Agregar.NORMAL)

        assertThat(vm.getOrCreateOrder()).isNull() // el cliente ve el aviso de siempre
        val segunda = vm.getOrCreateOrder()

        assertThat(servidor.ventas).hasSize(1)
        assertThat(segunda!!.first).isEqualTo(servidor.ventas.values.single().id)
        assertThat(servidor.ventas.values.single().cantidades).containsExactly("p-cafe|", 1) // no se sumó al reenviar
        assertThat(servidor.llavesRecibidas.distinct()).hasSize(1)
        assertThat(servidor.llavesRecibidas.first()).startsWith("kiosk:")
        vm.viewModelScope.cancel()
    }

    @Test fun `el agregar no llego - el reintento carga los productos en la misma venta`() = runTest {
        val vm = kiosco()
        vm.addToCart(producto("p-cafe"))
        servidor.alAgregar = listOf(Agregar.NO_LLEGA, Agregar.NORMAL)

        assertThat(vm.getOrCreateOrder()).isNull()
        val segunda = vm.getOrCreateOrder()

        assertThat(servidor.ventas).hasSize(1)
        assertThat(segunda!!.first).isEqualTo(servidor.ventas.values.single().id)
        assertThat(servidor.ventas.values.single().cantidades).containsExactly("p-cafe|", 1)
        vm.viewModelScope.cancel()
    }

    @Test fun `si el cliente cambia el carrito tras el fallo se empieza con otra llave`() = runTest {
        val vm = kiosco()
        vm.addToCart(producto("p-cafe"))
        servidor.alAgregar = listOf(Agregar.NO_LLEGA, Agregar.NORMAL)

        assertThat(vm.getOrCreateOrder()).isNull()
        vm.addToCart(producto("p-pan"))
        assertThat(vm.getOrCreateOrder()).isNotNull()

        assertThat(servidor.llavesRecibidas.distinct()).hasSize(2)
        vm.viewModelScope.cancel()
    }

    @Test fun `vaciar el carrito olvida la venta y su llave`() = runTest {
        val vm = kiosco()
        vm.addToCart(producto("p-cafe"))
        val primera = vm.getOrCreateOrder()!!

        vm.clearCart()
        vm.addToCart(producto("p-cafe"))
        val segunda = vm.getOrCreateOrder()!!

        assertThat(segunda.first).isNotEqualTo(primera.first)
        assertThat(servidor.llavesRecibidas.distinct()).hasSize(2)
        vm.viewModelScope.cancel()
    }

    @Test fun `dos renglones con los mismos extras - el reintento no los vuelve a sumar (Codex r1 #1)`() = runTest {
        val vm = kiosco()
        // El cliente elige los extras en distinto orden: el carrito tiene 2 renglones, el SERVIDOR los junta en 1 de cantidad 2
        // y, si se le vuelven a mandar juntos, SUMA (order.tpv.service.ts `_count > 1`): 4 en vez de 2.
        vm.addToCartWithModifiers(producto("p-cafe"), listOf(extra("leche"), extra("canela")), BigDecimal("40.00"))
        vm.addToCartWithModifiers(producto("p-cafe"), listOf(extra("canela"), extra("leche")), BigDecimal("40.00"))
        servidor.alAgregar = listOf(Agregar.LLEGA_PERO_SE_PIERDE_LA_RESPUESTA)

        assertThat(vm.getOrCreateOrder()).isNull()
        val segunda = vm.getOrCreateOrder()

        assertThat(segunda).isNotNull()
        assertThat(servidor.ventas).hasSize(1)
        assertThat(servidor.ventas.values.single().cantidades).containsExactly("p-cafe|canela,leche", 2)
        vm.viewModelScope.cancel()
    }

    @Test fun `cambiar 2 de A y 1 de B por 1 de A y 2 de B es otro carrito (Codex r1 #2)`() = runTest {
        val vm = kiosco()
        vm.addToCart(producto("p-a")); vm.addToCart(producto("p-a")); vm.addToCart(producto("p-b"))
        servidor.alAgregar = listOf(Agregar.LLEGA_PERO_SE_PIERDE_LA_RESPUESTA)

        assertThat(vm.getOrCreateOrder()).isNull()
        vm.decreaseQuantity("p-a"); vm.increaseQuantity("p-b")
        val segunda = vm.getOrCreateOrder()!!

        assertThat(servidor.llavesRecibidas.distinct()).hasSize(2)
        assertThat(servidor.venta(segunda.first).cantidades).containsExactly("p-a|", 1, "p-b|", 2)
        vm.viewModelScope.cancel()
    }

    @Test fun `si alguien mas toco la venta recuperada ni se cobra ni se abre otra (Codex r1 #3, r2 #1, r3 #1-#3)`() = runTest {
        // Cada caso: el envío llegó, se perdió la respuesta y, antes del reintento, alguien cambió esa venta desde otro lado.
        val casos = mapOf<String, (Venta) -> Unit>(
            "cancelada" to { it.estado = "CANCELLED" },
            "borrada (la terminal lee DELETED como OPEN)" to { it.estado = "DELETED" },
            "unida a otra cuenta (el origen queda CANCELLED sin dinero)" to { it.estado = "CANCELLED"; it.cantidades.clear() },
            "dividida (se llevaron un producto a otra cuenta)" to { it.cantidades["p-cafe|"] = 1 },
            "con descuento (mismos productos, otro total)" to { it.descuento = 10.0 },
        )
        for ((caso, tocar) in casos) {
            servidor = ServidorFalso()
            val vm = kiosco()
            vm.addToCart(producto("p-cafe")); vm.addToCart(producto("p-cafe"))
            servidor.alAgregar = listOf(Agregar.LLEGA_PERO_SE_PIERDE_LA_RESPUESTA)

            assertThat(vm.getOrCreateOrder()).isNull()
            tocar(servidor.ventas.values.single())

            assertWithMessage(caso).that(vm.getOrCreateOrder()).isNull()
            assertWithMessage(caso).that(vm.getOrCreateOrder()).isNull() // por más que reintente
            assertWithMessage(caso).that(servidor.ventas).hasSize(1)

            // La salida: vaciar el carrito empieza de cero.
            vm.clearCart()
            vm.addToCart(producto("p-cafe"))
            assertWithMessage(caso).that(vm.getOrCreateOrder()).isNotNull()
            assertWithMessage(caso).that(servidor.ventas).hasSize(2)
            vm.viewModelScope.cancel()
        }
    }

    @Test fun `con un cobro o un abono - ni se cobra otra vez ni se abre otra (Codex r2 #2)`() = runTest {
        for ((pago, pagado) in listOf("PAID" to 30.0, "PARTIAL" to 10.0)) {
            servidor = ServidorFalso()
            val vm = kiosco()
            vm.addToCart(producto("p-cafe"))
            servidor.alAgregar = listOf(Agregar.LLEGA_PERO_SE_PIERDE_LA_RESPUESTA)

            assertThat(vm.getOrCreateOrder()).isNull()
            servidor.ventas.values.single().apply { this.pago = pago; this.pagado = pagado }

            assertThat(vm.getOrCreateOrder()).isNull()
            assertThat(vm.getOrCreateOrder()).isNull() // por más que reintente
            assertThat(servidor.ventas).hasSize(1)

            // La salida: vaciar el carrito empieza de cero.
            vm.clearCart()
            vm.addToCart(producto("p-cafe"))
            assertThat(vm.getOrCreateOrder()).isNotNull()
            assertThat(servidor.ventas).hasSize(2)
            vm.viewModelScope.cancel()
        }
    }

    @Test fun `un cargo puesto a la venta vacia que solo se ve al cargar los productos no se cobra de menos (Codex r4 #1)`() = runTest {
        val vm = kiosco()
        vm.addToCart(producto("p-cafe"))
        servidor.alAgregar = listOf(Agregar.NO_LLEGA)

        assertThat(vm.getOrCreateOrder()).isNull()
        servidor.ventas.values.single().cargo = 10.0 // vacía: todavía no cambia nada a la vista
        assertThat(vm.getOrCreateOrder()).isNull() // al cargarle los productos el total es $40 y el carrito $30

        assertThat(servidor.ventas).hasSize(1)
        assertThat(vm.getOrCreateOrder()).isNull() // tampoco al reintentar
        assertThat(servidor.ventas).hasSize(1)
        vm.viewModelScope.cancel()
    }

    // ---- Regresiones: lo de hoy sigue igual ----

    @Test fun `primer intento con un precio distinto en el servidor sigue como hoy`() = runTest {
        // El candado del total es sólo para ventas recuperadas: una venta nueva no la tocó nadie (el precio distinto es otro tema).
        val vm = kiosco()
        vm.addToCart(producto("p-cafe"))
        servidor.ajusteDePrecio = -5.0

        assertThat(vm.getOrCreateOrder()).isNotNull()
        assertThat(servidor.ventas).hasSize(1)
        vm.viewModelScope.cancel()
    }

    @Test fun `camino feliz - una venta con sus productos y volver a pagar la reutiliza sin crear otra`() = runTest {
        val vm = kiosco()
        vm.addToCart(producto("p-cafe"))
        vm.addToCart(producto("p-cafe"))

        val primera = vm.getOrCreateOrder()!!
        val otraVez = vm.getOrCreateOrder()!!

        assertThat(otraVez).isEqualTo(primera)
        assertThat(servidor.ventas).hasSize(1)
        assertThat(servidor.crearLlamadas).isEqualTo(1)
        assertThat(servidor.ventas.values.single().cantidades).containsExactly("p-cafe|", 2)
        vm.viewModelScope.cancel()
    }

    @Test fun `un rechazo del servidor al agregar no se disfraza de exito`() = runTest {
        val vm = kiosco()
        vm.addToCart(producto("p-cafe"))
        servidor.alAgregar = listOf(Agregar.RECHAZO, Agregar.RECHAZO)

        assertThat(vm.getOrCreateOrder()).isNull()
        assertThat(vm.getOrCreateOrder()).isNull()
        assertThat(servidor.ventas).hasSize(1) // ni siquiera el reintento crea otra
        vm.viewModelScope.cancel()
    }

    @Test fun `la traduccion de la terminal conserva el estado DELETED del servidor aunque lo lea como abierta`() {
        val orden = servidor.orden(Venta(id = "orden-1", numero = "K-1", version = 2, estado = "DELETED"))

        assertThat(orden.serverStatus).isEqualTo("DELETED")
        assertThat(orden.status.name).isEqualTo("OPEN") // lo que ve el resto de la terminal no cambia
    }

    // ---- Piezas ----

    private enum class Agregar { NORMAL, NO_LLEGA, LLEGA_PERO_SE_PIERDE_LA_RESPUESTA, RECHAZO }

    private class Venta(
        val id: String,
        val numero: String,
        var version: Int,
        var estado: String = "PENDING", // como lo guarda el SERVIDOR
        var pago: String = "PENDING",
        var pagado: Double = 0.0,
        var descuento: Double = 0.0,
        var cargo: Double = 0.0,
        val cantidades: MutableMap<String, Int> = linkedMapOf(), // «producto|extras ordenados» → cantidad
    )

    private inner class ServidorFalso {
        val ventas = linkedMapOf<String, Venta>() // por llave (o por id si no hay llave)
        val llavesRecibidas = mutableListOf<String>()
        var crearLlamadas = 0
        var alAgregar: List<Agregar> = emptyList()
        var ajusteDePrecio = 0.0
        private var agregarLlamadas = 0

        fun venta(id: String) = ventas.values.first { it.id == id }

        fun crear(llave: String?): Result<Order> {
            crearLlamadas += 1
            llave?.let { llavesRecibidas += it }
            val existente = llave?.let { ventas[it] }
            if (existente != null) return Result.success(orden(existente))
            val n = ventas.size + 1
            val venta = Venta(id = "orden-$n", numero = "K-$n", version = 1)
            ventas[llave ?: venta.id] = venta
            return Result.success(orden(venta))
        }

        fun agregar(ordenId: String, items: List<AddOrderItemRequest>, version: Int): Result<Order> {
            val modo = alAgregar.getOrElse(agregarLlamadas) { Agregar.NORMAL }
            agregarLlamadas += 1
            val venta = venta(ordenId)
            if (modo == Agregar.NO_LLEGA) return Result.failure(IOException("sin red"))
            if (modo == Agregar.RECHAZO || venta.pago == "PAID") return Result.failure(IllegalStateException("400"))
            if (venta.version != version) return Result.failure(ConflictException(serverVersion = "${venta.version}", message = "409"))
            items.groupBy { "${it.productId}|${it.modifierIds.orEmpty().sorted().joinToString(",")}" }.forEach { (llave, renglones) ->
                val cantidad = renglones.sumOf { it.quantity }
                val antes = venta.cantidades[llave]
                venta.cantidades[llave] = if (antes != null && renglones.size > 1) antes + cantidad else cantidad
            }
            venta.version += 1
            if (modo == Agregar.LLEGA_PERO_SE_PIERDE_LA_RESPUESTA) return Result.failure(IOException("respuesta perdida"))
            return Result.success(orden(venta))
        }

        private fun precio(extras: String) = 30.0 + ajusteDePrecio + 5.0 * extras.split(",").count { it.isNotBlank() }
        private fun subtotal(v: Venta) = v.cantidades.entries.sumOf { (llave, cantidad) -> precio(llave.substringAfter("|")) * cantidad }

        /** La venta como JSON del SERVIDOR, traducida igual que en la terminal. */
        fun orden(v: Venta): Order {
            val ahora = "2026-11-01T12:00:00.000Z"
            val json = Gson().toJson(
                mapOf(
                    "id" to v.id, "venueId" to "venue-1", "tableId" to null, "orderNumber" to v.numero, "covers" to 1,
                    "status" to v.estado, "paymentStatus" to v.pago, "type" to "TAKEOUT",
                    "items" to v.cantidades.entries.mapIndexed { i, (llave, cantidad) ->
                        val (producto, extras) = llave.split("|")
                        mapOf(
                            "id" to "${v.id}-r$i", "orderId" to v.id, "productId" to producto, "productName" to producto,
                            "quantity" to cantidad, "unitPrice" to precio(extras), "total" to precio(extras) * cantidad, "notes" to null,
                            "modifiers" to extras.split(",").filter { it.isNotBlank() }.map { mapOf("id" to it, "name" to it, "price" to 5.0) },
                            "createdAt" to ahora, "sentToKitchenAt" to null,
                        )
                    },
                    "subtotal" to subtotal(v), "discountAmount" to v.descuento, "taxAmount" to 0.0,
                    "total" to subtotal(v) - v.descuento + (if (v.cantidades.isEmpty()) 0.0 else v.cargo),
                    "paidAmount" to v.pagado, "servedById" to null,
                    "createdAt" to ahora, "updatedAt" to ahora, "version" to v.version,
                ),
            )
            return Gson().fromJson(json, OrderDto::class.java).toOrder()
        }
    }

    private fun kiosco(): KioskViewModel {
        val secureStorage = mockk<SecureStorage>(relaxed = true)
        every { secureStorage.getKioskVenueId() } returns "venue-1"
        val orderRepository = mockk<OrderRepository>(relaxed = true)
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            servidor.crear(arg<String?>(7))
        }
        coEvery { orderRepository.addItemsToOrder(any(), any(), any(), any(), any()) } answers {
            servidor.agregar(arg(1), arg(2), arg(3))
        }
        val getMerchants = mockk<com.jaac.avoqado_tpv.features.payment.domain.use_case.GetMerchantsUseCase>()
        every { getMerchants() } throws IllegalStateException("sin SDK en la prueba")
        return KioskViewModel(
            productRepository = mockk(relaxed = true),
            orderRepository = orderRepository,
            customerRepository = mockk(relaxed = true),
            secureStorage = secureStorage,
            initializationManager = mockk(relaxed = true),
            getMerchantsUseCase = getMerchants,
            multiMerchantSDKManager = mockk(relaxed = true),
            tpvSettingsRepository = mockk(relaxed = true),
        )
    }

    private fun producto(id: String) = KioskProduct(id = id, name = id, price = BigDecimal("30.00"), categoryId = "c-1")

    private fun extra(id: String) = ProductModifier(id = id, name = id, priceAdjustment = BigDecimal("5.00"), type = ModifierType.MULTIPLE_CHOICE)
}

package com.jaac.avoqado_tpv.features.checkout.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartItem
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartItemType
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartState
import com.jaac.avoqado_tpv.features.checkout.domain.model.SelectedModifier
import com.jaac.avoqado_tpv.features.ordering.domain.DescuentoDelCliente
import com.jaac.avoqado_tpv.features.ordering.domain.Discount
import com.jaac.avoqado_tpv.features.ordering.domain.comoDescuento
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountConditions
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountScope
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

/**
 * «Cobrar» sin red, C2a: el carrito y SU llave sobreviven a que el sistema mate el proceso. El almacenamiento es SharedPreferences
 * con un DTO propio (CartItemType es sellado: nada de Gson directo sobre él).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class CarritoEnCursoTest {

    private val contexto get() = ApplicationProvider.getApplicationContext<Application>()
    private val llave = "ckx0000000000000000000001"

    @Before @After fun limpiar() {
        CarritoEnCurso(contexto).let { kotlinx.coroutines.runBlocking { it.borrar("venue-1"); it.borrar("venue-2") } }
    }

    private fun carritoCompleto() = CartState(
        items = listOf(
            CartItem(
                id = "i-0", type = CartItemType.ProductItem("p-1"), name = "Café", subtitle = "Bebidas", unitPriceCents = 5000, quantity = 2,
                imageUrl = "https://x/y.png", colorHex = "#112233", categoryId = "c-1",
                selectedModifiers = listOf(SelectedModifier("g-1", "Extras", "m-1", "Leche", 1000)),
                itemNote = "sin hielo", itemDiscountId = "d-linea", catalogAt = "2026-10-20T10:00:00.000Z",
            ),
            CartItem(
                id = "i-1", type = CartItemType.ProductItem("p-2"), name = "Galleta", unitPriceCents = 3000,
                isCortesia = true, cortesiaReason = "Cliente VIP", catalogAt = null,
            ),
            CartItem(id = "i-2", type = CartItemType.CustomAmount, name = "Otro importe", unitPriceCents = 12000, priceAdjustmentCents = 11000),
        ),
        orderDiscount = Discount(
            id = "d-cuenta", name = "Cupón X", type = DiscountType.PERCENTAGE, value = BigDecimal("10"),
            scope = DiscountScope.ORDER, conditions = DiscountConditions(BigDecimal("50"), null, null, "14:00", "18:00"),
            active = true, requiresAuthorization = false,
        ),
        manualDiscountCents = 0, orderNote = "para llevar", selectedStaffId = "staff-1", selectedStaffName = "María",
    )

    @Test fun `guardar y leer devuelve la misma llave y el mismo carrito`() = runTest {
        val carrito = carritoCompleto()
        assertThat(CarritoEnCurso(contexto).guardar("venue-1", llave, carrito)).isTrue()

        val (llaveLeida, leido) = CarritoEnCurso(contexto).leer("venue-1")!!

        assertThat(llaveLeida).isEqualTo(llave)
        assertThat(leido).isEqualTo(carrito)
        assertThat(leido.items[0].catalogAt).isEqualTo("2026-10-20T10:00:00.000Z")
        assertThat(leido.totalCents).isEqualTo(carrito.totalCents)
    }

    @Test fun `tras guardar, otra instancia lee la MISMA llave (la durabilidad la da commit)`() = runTest {
        CarritoEnCurso(contexto).guardar("venue-1", llave, carritoCompleto())
        // Otra instancia lee lo que quedó en SharedPreferences. Lo que garantiza que sobreviva a la muerte real del proceso es
        // `commit()` (síncrono); esta prueba sólo verifica que la llave se lee de lo escrito, no que simule matar el proceso.
        val trasLaMuerte = CarritoEnCurso(contexto)

        assertThat(trasLaMuerte.leer("venue-1")!!.first).isEqualTo(llave)
    }

    @Test fun `guardar otra vez reemplaza el carrito y conserva la llave que se le pasa`() = runTest {
        val carritos = CarritoEnCurso(contexto)
        carritos.guardar("venue-1", llave, carritoCompleto())
        carritos.guardar("venue-1", llave, CartState(items = listOf(carritoCompleto().items[2])))

        val (l, c) = carritos.leer("venue-1")!!
        assertThat(l).isEqualTo(llave)
        assertThat(c.items).hasSize(1)
    }

    @Test fun `cada negocio tiene su carrito`() = runTest {
        val carritos = CarritoEnCurso(contexto)
        carritos.guardar("venue-1", llave, carritoCompleto())

        assertThat(carritos.leer("venue-2")).isNull()
    }

    @Test fun `borrar deja sin carrito`() = runTest {
        val carritos = CarritoEnCurso(contexto)
        carritos.guardar("venue-1", llave, carritoCompleto())
        carritos.borrar("venue-1")

        assertThat(carritos.leer("venue-1")).isNull()
        assertThat(CarritoEnCurso(contexto).leer("venue-1")).isNull()
    }

    @Test fun `guarda cuándo se guardó y lo mínimo del cliente para volver a mostrarlo`() = runTest {
        val cliente = com.jaac.avoqado_tpv.features.ordering.domain.Customer(
            id = "c-1", firstName = "Ana", lastName = "López", email = "ana@x.mx", phone = "5551234567", loyaltyPoints = 120,
            totalVisits = 4, totalSpent = BigDecimal("900"), customerGroup = null,
        )
        CarritoEnCurso(contexto).guardar("venue-1", llave, carritoCompleto(), cliente = cliente, guardadoEn = 1_760_000_000_000L)

        val guardado = CarritoEnCurso(contexto).leerGuardado("venue-1")!!

        assertThat(guardado.llave).isEqualTo(llave)
        assertThat(guardado.cart).isEqualTo(carritoCompleto())
        assertThat(guardado.guardadoEn).isEqualTo(1_760_000_000_000L)
        assertThat(guardado.cliente?.id).isEqualTo("c-1")
        assertThat(guardado.cliente?.displayName).isEqualTo("Ana López")
        assertThat(guardado.cliente?.email).isEqualTo("ana@x.mx")
    }

    @Test fun `un carrito guardado sin hora se lee con la hora vacía`() = runTest {
        contexto.getSharedPreferences("avoqado_carrito_en_curso", android.content.Context.MODE_PRIVATE).edit()
            .putString(
                "carrito_venue-1",
                """{"version":1,"llave":"$llave","items":[],"manualDiscountCents":0,"selectedStaffId":"s","selectedStaffName":"S"}""",
            ).commit()

        val guardado = CarritoEnCurso(contexto).leerGuardado("venue-1")!!

        assertThat(guardado.guardadoEn).isNull()
        assertThat(guardado.cliente).isNull()
    }

    @Test fun `un contenido ilegible se conserva y avisa en lugar de darlo por ausente`() = runTest {
        contexto.getSharedPreferences("avoqado_carrito_en_curso", android.content.Context.MODE_PRIVATE)
            .edit().putString("carrito_venue-1", "{no es json").commit()

        var fallo: Throwable? = null
        try { CarritoEnCurso(contexto).leer("venue-1") } catch (e: Exception) { fallo = e }
        assertThat(fallo).isNotNull()
        assertThat(contexto.getSharedPreferences("avoqado_carrito_en_curso", android.content.Context.MODE_PRIVATE)
            .getString("carrito_venue-1", null)).isEqualTo("{no es json")
    }

    @Test fun `el descuento del cliente y las tres marcas del carrito vuelven iguales`() = runTest {
        val descuento = DescuentoDelCliente("d-1", "Socio", DiscountType.FIXED, 50.0, 30.0)
        val cliente = com.jaac.avoqado_tpv.features.ordering.domain.Customer(
            id = "c-1", firstName = "Ana", lastName = null, email = null, phone = null,
            loyaltyPoints = 0, totalVisits = 0, totalSpent = BigDecimal.ZERO, customerGroup = null, descuentoDelCliente = descuento,
        )
        val carrito = carritoCompleto().copy(
            orderDiscount = descuento.comoDescuento(),
            descuentoEsDelCliente = true, descuentoDelClienteQuitadoDe = "c-1", autorizacionDelDescuento = "tok-123",
        )
        CarritoEnCurso(contexto).guardar("venue-1", llave, carrito, cliente = cliente)

        val guardado = CarritoEnCurso(contexto).leerGuardado("venue-1")!!

        assertThat(guardado.cart).isEqualTo(carrito)
        assertThat(guardado.cliente?.descuentoDelCliente).isEqualTo(descuento)
    }

    @Test fun `un carrito guardado por la version de hoy se lee con los valores por omisión`() = runTest {
        val viejo = """{"version":1,"llave":"$llave","items":[],"manualDiscountCents":0,"selectedStaffId":"s","selectedStaffName":"S",""" +
            """"guardadoEn":1,"cliente":{"id":"c-1","firstName":"Ana"}}"""
        contexto.getSharedPreferences("avoqado_carrito_en_curso", android.content.Context.MODE_PRIVATE)
            .edit().putString("carrito_venue-1", viejo).commit()

        val guardado = CarritoEnCurso(contexto).leerGuardado("venue-1")!!

        assertThat(guardado.cart.descuentoEsDelCliente).isFalse()
        assertThat(guardado.cart.descuentoDelClienteQuitadoDe).isNull()
        assertThat(guardado.cart.autorizacionDelDescuento).isNull()
        assertThat(guardado.cliente?.descuentoDelCliente).isNull()
    }

    @Test fun `la marca booleana de quitar de una version de prueba se lee como null y el carrito vuelve`() = runTest {
        val viejo = """{"version":1,"llave":"$llave","items":[],"manualDiscountCents":0,"selectedStaffId":"s","selectedStaffName":"S",""" +
            """"guardadoEn":1,"descuentoEsDelCliente":false,"descuentoDelClienteQuitado":true}"""
        contexto.getSharedPreferences("avoqado_carrito_en_curso", android.content.Context.MODE_PRIVATE)
            .edit().putString("carrito_venue-1", viejo).commit()

        val guardado = CarritoEnCurso(contexto).leerGuardado("venue-1")!!

        assertThat(guardado.cart.descuentoDelClienteQuitadoDe).isNull()
    }
}

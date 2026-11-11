package com.jaac.avoqado_tpv.features.ordering.data.dto

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartItem
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartItemType
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartState
import com.jaac.avoqado_tpv.features.ordering.data.mappers.toCustomer
import com.jaac.avoqado_tpv.features.ordering.domain.comoDescuento
import com.jaac.avoqado_tpv.features.ordering.domain.llaveDelDescuentoDelCliente
import org.junit.Test

class CustomerDtoDescuentoTest {
    private fun json(descuento: String?) = """
        {"id":"c-1","firstName":"Ana","lastName":null,"email":null,"phone":null,"loyaltyPoints":0,"totalVisits":0,"totalSpent":0,
         "customerGroup":null${descuento?.let { ""","descuentoDelCliente":$it""" } ?: ""}}
    """.trimIndent()

    private fun cliente(descuento: String?) = Gson().fromJson(json(descuento), CustomerDto::class.java).toCustomer()

    @Test fun `un servidor viejo sin el campo deja el descuento en null`() {
        assertThat(cliente(null).descuentoDelCliente).isNull()
        assertThat(cliente("null").descuentoDelCliente).isNull()
    }

    @Test fun `10 por ciento rebaja 10 pesos de 100 en el carrito`() {
        val d = cliente("""{"discountId":"d-1","name":"Socio","type":"PERCENTAGE","value":10,"maxDiscountAmount":null}""").descuentoDelCliente!!
        assertThat(d.discountId).isEqualTo("d-1")
        assertThat(d.nombre).isEqualTo("Socio")

        val carrito = CartState(
            items = listOf(CartItem(id = "i", type = CartItemType.ProductItem("p"), name = "X", unitPriceCents = 10000)),
            orderDiscount = d.comoDescuento(),
        )
        assertThat(carrito.discountCents).isEqualTo(1000)
        assertThat(d.comoDescuento().couponCode).isNull()
    }

    @Test fun `monto fijo con tope`() {
        val d = cliente("""{"discountId":"d-2","name":"Fijo","type":"FIXED_AMOUNT","value":50,"maxDiscountAmount":30}""").descuentoDelCliente!!
        val carrito = CartState(
            items = listOf(CartItem(id = "i", type = CartItemType.ProductItem("p"), name = "X", unitPriceCents = 10000)),
            orderDiscount = d.comoDescuento(),
        )
        assertThat(carrito.discountCents).isEqualTo(3000)
    }

    @Test fun `un tipo desconocido da null`() {
        assertThat(cliente("""{"discountId":"d-3","name":"Raro","type":"COMP","value":100,"maxDiscountAmount":null}""").descuentoDelCliente).isNull()
    }

    @Test fun `la llave espeja la del servidor`() {
        assertThat(llaveDelDescuentoDelCliente("c-1", "d-1")).isEqualTo("descuento-cliente:c-1:d-1")
    }
}

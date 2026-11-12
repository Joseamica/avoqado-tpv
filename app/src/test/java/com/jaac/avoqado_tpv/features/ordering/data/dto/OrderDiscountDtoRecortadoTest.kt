package com.jaac.avoqado_tpv.features.ordering.data.dto

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.jaac.avoqado_tpv.features.ordering.data.mappers.toOrderDiscount
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountType
import org.junit.Test
import java.math.BigDecimal

/**
 * El SERVIDOR (desde el 29-jul hasta la versión con el descuento del cliente) manda cada descuento de la cuenta recortado a
 * { id, name, amount }. Sin `type`, el traductor tronaba, la relectura de la venta tras cobrar fallaba y la pantalla final
 * decía «Queda por pagar» con lo ya pagado (QA en la PAX, 11-nov). Una línea recortada se lee sin tronar.
 */
class OrderDiscountDtoRecortadoTest {

    private val gson = Gson()

    @Test
    fun `un descuento sin tipo ni valor se lee con su monto, como monto fijo`() {
        val dto = gson.fromJson("""{"id":"od-1","name":"Cliente 10 %","amount":"10"}""", OrderDiscountDto::class.java)

        val descuento = dto.toOrderDiscount()

        assertThat(descuento.name).isEqualTo("Cliente 10 %")
        assertThat(descuento.amount.compareTo(BigDecimal("10"))).isEqualTo(0)
        assertThat(descuento.type).isEqualTo(DiscountType.FIXED)
    }

    @Test
    fun `un descuento completo conserva su tipo`() {
        val dto = gson.fromJson(
            """{"id":"od-1","orderId":"o-1","name":"Cliente 10 %","type":"PERCENTAGE","value":"10","amount":"10"}""",
            OrderDiscountDto::class.java,
        )

        assertThat(dto.toOrderDiscount().type).isEqualTo(DiscountType.PERCENTAGE)
    }
}

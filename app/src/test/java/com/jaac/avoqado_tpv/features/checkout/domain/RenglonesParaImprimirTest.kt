package com.jaac.avoqado_tpv.features.checkout.domain

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.checkout.domain.model.ExtraDeTicket
import com.jaac.avoqado_tpv.features.checkout.domain.model.RenglonDeTicket
import com.jaac.avoqado_tpv.features.checkout.domain.model.VentaLocal
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierType
import org.junit.Test
import java.math.BigDecimal

/**
 * «Cobrar» sin red, C2b: el ticket de una venta guardada sale de SUS renglones (los de `ticket_json`), nunca del catálogo ni de la red.
 */
class RenglonesParaImprimirTest {

    private val llave = "ckx0000000000000000000001"

    private fun venta(totalCents: Int, vararg renglones: RenglonDeTicket) = VentaLocal(
        llave = llave, venueId = "venue-1", staffId = "staff-1", shiftId = null, createdAtLocal = 0L,
        totalCents = totalCents, pedidoJson = "{}", renglones = renglones.toList(),
    )

    @Test fun `cada renglón con su cantidad, su importe, sus extras y su nota`() {
        val v = venta(
            11000,
            RenglonDeTicket("$llave:0", "Café", 2, 11000, 11000, listOf(ExtraDeTicket("m-1", "Leche de avena", 500, "MULTIPLE_CHOICE")), "sin azúcar"),
        )

        val item = renglonesParaImprimir(v).single()

        assertThat(item.id).isEqualTo("$llave:0")
        assertThat(item.orderId).isEqualTo(llave)
        assertThat(item.productName).isEqualTo("Café")
        assertThat(item.quantity).isEqualTo(2)
        assertThat(item.totalPrice).isEqualTo(BigDecimal("110.00"))
        assertThat(item.unitPrice).isEqualTo(BigDecimal("55.00"))
        assertThat(item.notes).isEqualTo("sin azúcar")
        val extra = item.modifiers.single()
        assertThat(extra.id).isEqualTo("m-1")
        assertThat(extra.name).isEqualTo("Leche de avena")
        assertThat(extra.priceAdjustment).isEqualTo(BigDecimal("5.00"))
        assertThat(extra.type).isEqualTo(ModifierType.MULTIPLE_CHOICE)
    }

    @Test fun `una cortesía sale en $0 y un tipo de extra desconocido no truena`() {
        val v = venta(0, RenglonDeTicket("$llave:0", "Galleta", 1, 0, 3000, listOf(ExtraDeTicket("m-9", "Chispas", 0, "NO_EXISTE")), null))

        val item = renglonesParaImprimir(v).single()

        assertThat(item.totalPrice).isEqualTo(BigDecimal("0.00"))
        assertThat(item.modifiers.single().type).isEqualTo(ModifierType.MULTIPLE_CHOICE)
        assertThat(descuentoParaImprimir(v)).isEqualTo(BigDecimal("0.00"))
    }

    @Test fun `el descuento de la cuenta es lo que los renglones suman de más sobre el total`() {
        // 100 % de descuento manual: los renglones valen $50 y se cobra $0.
        val v = venta(0, RenglonDeTicket("$llave:0", "Café", 1, 5000, 5000, emptyList(), null))

        assertThat(descuentoParaImprimir(v)).isEqualTo(BigDecimal("50.00"))
    }
}

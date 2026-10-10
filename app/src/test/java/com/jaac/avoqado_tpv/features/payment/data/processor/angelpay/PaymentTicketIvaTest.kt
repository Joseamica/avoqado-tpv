package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.angelpay.angelpaysdk.models.PrintTicketRequest
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.printer.LeyendaIva
import java.math.BigDecimal
import org.junit.Test

/**
 * IVA por producto, plan 7 (6.2): el ticket de la Nexgo ya no calcula «IVA 16 %» sobre el total CON propina (falso para el
 * 0 %, el exento y cualquier propina). Dice la leyenda; la cantidad exacta por tasa vive en la factura.
 */
class PaymentTicketIvaTest {

    private val builder = AngelPayTicketBuilder()

    private fun textoDe(t: PrintTicketRequest): String =
        (t.header + t.body + t.footer).flatMap { listOfNotNull(it.text, it.left, it.right) }.joinToString("\n")

    private fun ticket(propina: String = "0.00") = builder.buildPaymentTicket(
        amount = BigDecimal("100.00"),
        tipAmount = BigDecimal(propina),
        authCode = "A1B2C3",
        referenceNumber = "000123",
        venueName = "Testarudo Cafe",
        staffName = "Ana",
        orderNumber = "42",
    )

    @Test
    fun `P1 el ticket dice IVA incluido cuando aplica y no inventa un desglose`() {
        val t = textoDe(ticket())
        assertThat(t).contains(LeyendaIva.TEXTO)
        assertThat(t).doesNotContain("Desglose IVA")
        assertThat(t).doesNotContain("IVA 16%")
        assertThat(t).doesNotContain("  Base")
    }

    @Test
    fun `P1 con propina tampoco hay base inventada`() {
        val t = textoDe(ticket(propina = "15.00"))
        assertThat(t).contains(LeyendaIva.TEXTO)
        assertThat(t).doesNotContain("$86.21") // 100 / 1.16
        assertThat(t).doesNotContain("$99.14") // 115 / 1.16: el 16 % sobre venta + propina
    }

    @Test
    fun `la leyenda cabe en las 32 columnas del papel`() {
        assertThat(LeyendaIva.TEXTO.length).isAtMost(32)
    }

    @Test
    fun `P1 el orden de los renglones es el de las apps - Subtotal, leyenda, Propina, TOTAL`() {
        val renglones = ticket(propina = "15.00").body.flatMap { listOfNotNull(it.text, it.left) }
        val orden = listOf("Subtotal", LeyendaIva.TEXTO, "Propina", "TOTAL").map { renglones.indexOf(it) }
        assertThat(orden).doesNotContain(-1)
        assertThat(orden).isInStrictOrder()
    }

    @Test
    fun `P1 sin propina tambien sale el Subtotal y nunca un renglon de descuento`() {
        val t = textoDe(ticket())
        assertThat(t).contains("Subtotal")
        assertThat(t).doesNotContain("Descuento")
        assertThat(t).doesNotContain("DESC")
    }

    @Test
    fun `control - subtotal, propina y total no cambian`() {
        val t = textoDe(ticket(propina = "15.00"))
        assertThat(t).contains("$100.00 MXN")
        assertThat(t).contains("$15.00 MXN")
        assertThat(t).contains("$115.00 MXN")
    }
}

package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.angelpay.angelpaysdk.models.PrintTicketRequest
import com.google.common.truth.Truth.assertThat
import java.math.BigDecimal
import org.junit.Test

/**
 * El ticket de PAGO RECHAZADO — el papel que se entrega cuando el banco NO autoriza.
 *
 * Los dos primeros tests son los que de verdad importan y no deben relajarse nunca:
 * que este papel no se pueda confundir con un comprobante de pago, y que jamás lleve el
 * número de tarjeta completo. Los demás son contenido.
 */
class DeclineTicketTest {

    private val builder = AngelPayTicketBuilder()

    /** Aplana el ticket a texto para poder afirmar sobre lo que el cliente va a leer. */
    private fun textoDe(t: PrintTicketRequest): String =
        (t.header + t.body + t.footer)
            .flatMap { listOfNotNull(it.text, it.left, it.right) }
            .joinToString("\n")

    private fun ticket(
        reason: String? = "FONDOS INSUFICIENTES",
        sdkCode: String? = "51",
        last4: String? = "4242",
        cardBrand: String? = "VISA",
    ) = builder.buildDeclineTicket(
        amount = BigDecimal("150.00"),
        reason = reason,
        sdkCode = sdkCode,
        last4 = last4,
        cardBrand = cardBrand,
        venueName = "Restaurante El Atole",
        staffName = "Ana",
        terminalSerial = "N86-001",
    )

    @Test
    fun `P1 no se puede confundir con un comprobante de pago`() {
        val t = textoDe(ticket())
        // Lo dice arriba…
        assertThat(t).contains("PAGO RECHAZADO")
        // …y lo repite abajo, que es donde la gente mira al arrancar el papel.
        assertThat(t).contains("NO SE REALIZO EL CARGO")
        // Y jamás las palabras que harían creer que sí pasó. En un rechazo la
        // autorización viene vacía — es justo así como se detecta el rechazo.
        assertThat(t.uppercase()).doesNotContain("APROBAD")
        assertThat(t.uppercase()).doesNotContain("AUTORIZACIÓN")
        assertThat(t.uppercase()).doesNotContain("AUTORIZACION")
    }

    @Test
    fun `P1 nunca imprime la tarjeta completa, sólo los últimos 4`() {
        val t = textoDe(ticket())
        assertThat(t).contains("****4242")
        // Ni un PAN de 13-19 dígitos seguidos en ninguna parte del papel.
        assertThat(t).doesNotContainMatch("\\d{13,19}")
    }

    @Test
    fun `lleva el motivo del banco, que es para lo que sirve el ticket`() {
        assertThat(textoDe(ticket(reason = "FONDOS INSUFICIENTES"))).contains("FONDOS INSUFICIENTES")
    }

    @Test
    fun `sin motivo se imprime igual — deja constancia del intento`() {
        val t = textoDe(ticket(reason = null))
        assertThat(t).contains("El banco no autorizó la transacción")
        assertThat(t).contains("PAGO RECHAZADO")
    }

    @Test
    fun `sin datos de tarjeta NO imprime la línea`() {
        val t = textoDe(ticket(last4 = null, cardBrand = null))
        assertThat(t).doesNotContain("Tarjeta")
        assertThat(t).doesNotContain("null")
    }

    @Test
    fun `el código del SDK aparece — es lo que soporte necesita para rastrearlo`() {
        assertThat(textoDe(ticket(sdkCode = "D308"))).contains("D308")
    }

    @Test
    fun `imprime el monto que se intentó cobrar`() {
        assertThat(textoDe(ticket())).contains("150")
    }
}

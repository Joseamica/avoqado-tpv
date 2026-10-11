package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.angelpay.angelpaysdk.models.PrintTicketRequest
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.math.BigDecimal
import org.junit.Test

/**
 * Visto en papel en la N86 (11-oct): un cobro en EFECTIVO salía «Forma de pago: TARJETA» con «Autorizacion EFECTIVO».
 * La Nexgo debe decir lo mismo que la PAX (`PrinterManager.printReceipt`): «EFECTIVO» y sin autorización ni referencia.
 */
class PaymentTicketEfectivoTest {

    private val builder = AngelPayTicketBuilder()

    private fun textoDe(t: PrintTicketRequest): String =
        (t.header + t.body + t.footer).flatMap { listOfNotNull(it.text, it.left, it.right) }.joinToString("\n")

    private fun ticket(isCash: Boolean, authCode: String, referencia: String) = textoDe(
        builder.buildPaymentTicket(
            amount = BigDecimal("100.00"),
            tipAmount = BigDecimal("15.00"),
            authCode = authCode,
            referenceNumber = referencia,
            venueName = "Estética Luna",
            staffName = "Ana",
            orderNumber = "42",
            isCash = isCash,
        )
    )

    @Test
    fun `P1 un cobro en efectivo dice EFECTIVO y no TARJETA`() {
        val t = ticket(isCash = true, authCode = "EFECTIVO", referencia = "CASH-1791753676475")
        assertThat(t).contains("Forma de pago: EFECTIVO")
        assertThat(t).doesNotContain("TARJETA")
    }

    @Test
    fun `P1 en efectivo no hay autorizacion ni referencia, como en la PAX`() {
        val t = ticket(isCash = true, authCode = "EFECTIVO", referencia = "CASH-1791753676475")
        assertThat(t).doesNotContain("Autorizacion")
        assertThat(t).doesNotContain("Referencia")
        assertThat(t).doesNotContain("CASH-1791753676475")
    }

    @Test
    fun `control - un cobro con tarjeta sigue igual`() {
        val t = ticket(isCash = false, authCode = "A1B2C3", referencia = "000123")
        assertThat(t).contains("Forma de pago: TARJETA")
        assertThat(t).contains("Autorizacion")
        assertThat(t).contains("A1B2C3")
        assertThat(t).contains("000123")
    }

    @Test
    fun `control - los totales del efectivo no cambian`() {
        val t = ticket(isCash = true, authCode = "EFECTIVO", referencia = "CASH-1")
        assertThat(t).contains("$100.00 MXN")
        assertThat(t).contains("$15.00 MXN")
        assertThat(t).contains("$115.00 MXN")
    }

    /** El builder sólo sabe que fue efectivo si `printReceipt` se lo dice: el estado de éxito ya trae `isCash`. */
    @Test
    fun `P1 printReceipt le pasa a la Nexgo si el cobro fue en efectivo`() {
        val codigo = File("src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModel.kt")
            .readLines().map { it.substringBefore("//") }.joinToString("\n").filterNot { it.isWhitespace() }
        val llamada = codigo.substringAfter("angelPayTicketBuilder.buildPaymentTicket(", missingDelimiterValue = "")
            .substringBefore("AngelPaySDK.printTicket(")
        assertThat(llamada).isNotEmpty()
        assertThat(llamada).contains("isCash=state?.isCash==true,")
    }
}

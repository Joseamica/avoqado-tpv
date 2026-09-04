package com.jaac.avoqado_tpv.features.reports

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.reports.data.dto.PaymentMethodDto
import com.jaac.avoqado_tpv.features.reports.data.dto.ShiftsSummaryData
import com.jaac.avoqado_tpv.features.reports.data.dto.SummaryDto
import com.jaac.avoqado_tpv.features.reports.data.dto.toPaymentBreakdown
import org.junit.Test
import java.math.BigDecimal

/**
 * `toPaymentBreakdown` — que CREDIT_CARD y DEBIT_CARD no se sumen.
 *
 * Queja real de Testarudo Café (3-sep-2026): *"homologar efectivo, tarjeta de crédito
 * y tarjeta de débito…. No poner solo 'tarjeta'"*. El servidor ya los mandaba
 * separados (son dos valores distintos de su enum `PaymentMethod`) y era ESTA app la
 * que los colapsaba en `cardAmount`.
 *
 * Es lógica pura, así que se prueba sin red ni impresora.
 */
class PaymentMethodSplitTest {

    private fun summary(vararg methods: Pair<String, Double>) = ShiftsSummaryData(
        summary = SummaryDto(
            totalSales = methods.sumOf { it.second },
            totalTips = 0.0,
            ordersCount = 1,
            averageTipPercentage = 0.0
        ),
        paymentMethods = methods.map { PaymentMethodDto(method = it.first, total = it.second, percentage = 0.0) }
    )

    // ── P1: el defecto que reporto el cliente ───────────────────────────────

    @Test
    fun `P1 credito y debito NO se suman entre si`() {
        val b = summary("CREDIT_CARD" to 6605.00, "DEBIT_CARD" to 8966.50).toPaymentBreakdown()

        assertThat(b.creditCardAmount).isEqualTo(BigDecimal.valueOf(6605.00))
        assertThat(b.debitCardAmount).isEqualTo(BigDecimal.valueOf(8966.50))
    }

    @Test
    fun `P1 cada tarjeta conserva SU propio porcentaje`() {
        // 6605 + 8966.50 + 4601.25 = 20172.75 (cifras reales de Testarudo)
        val b = summary(
            "CREDIT_CARD" to 6605.00,
            "DEBIT_CARD" to 8966.50,
            "CASH" to 4601.25
        ).toPaymentBreakdown()

        // Ni un porcentaje compartido ni uno igual al del otro: cada uno es el suyo.
        assertThat(b.creditCardPercentage.setScale(0, java.math.RoundingMode.HALF_UP).toInt()).isEqualTo(33)
        assertThat(b.debitCardPercentage.setScale(0, java.math.RoundingMode.HALF_UP).toInt()).isEqualTo(44)
        assertThat(b.cashPercentage.setScale(0, java.math.RoundingMode.HALF_UP).toInt()).isEqualTo(23)
    }

    @Test
    fun `P1 el total NO cambia por haberlos separado`() {
        val b = summary(
            "CREDIT_CARD" to 6605.00,
            "DEBIT_CARD" to 8966.50,
            "CASH" to 4601.25
        ).toPaymentBreakdown()

        assertThat(b.totalAmount).isEqualTo(BigDecimal.valueOf(20172.75))
    }

    @Test
    fun `P1 cardAmount agregado sigue siendo la suma de las tres tarjetas`() {
        val b = summary(
            "CREDIT_CARD" to 100.00,
            "DEBIT_CARD" to 200.00,
            "CARD" to 50.00
        ).toPaymentBreakdown()

        // Quien lea el agregado (la grafica de la pantalla) sigue viendo lo mismo que antes.
        assertThat(b.cardAmount).isEqualTo(BigDecimal.valueOf(350.00))
    }

    // ── donde cae lo que no es ni credito ni debito ─────────────────────────

    @Test
    fun `CARD a secas cae en tarjeta sin especificar, no en credito ni en debito`() {
        val b = summary("CARD" to 500.00).toPaymentBreakdown()

        assertThat(b.unspecifiedCardAmount).isEqualTo(BigDecimal.valueOf(500.00))
        assertThat(b.creditCardAmount).isEqualTo(BigDecimal.ZERO)
        assertThat(b.debitCardAmount).isEqualTo(BigDecimal.ZERO)
    }

    @Test
    fun `P1 billetera, transferencia y cripto entran en Otros y NO se pierden del total`() {
        // Antes caian en `otherAmount`, que el ticket JAMAS imprimia pero SI sumaba:
        // el desglose no cuadraba con su propia suma.
        val b = summary(
            "DIGITAL_WALLET" to 100.00,
            "BANK_TRANSFER" to 200.00,
            "CRYPTOCURRENCY" to 300.00
        ).toPaymentBreakdown()

        assertThat(b.otherAmount).isEqualTo(BigDecimal.valueOf(600.00))
        assertThat(b.totalAmount).isEqualTo(BigDecimal.valueOf(600.00))
    }

    @Test
    fun `un metodo desconocido no revienta ni se descarta`() {
        val b = summary("ALGO_QUE_NO_EXISTE" to 42.00).toPaymentBreakdown()

        assertThat(b.otherAmount).isEqualTo(BigDecimal.valueOf(42.00))
    }

    @Test
    fun `minusculas del servidor tambien se clasifican`() {
        val b = summary("credit_card" to 10.00, "debit_card" to 20.00).toPaymentBreakdown()

        assertThat(b.creditCardAmount).isEqualTo(BigDecimal.valueOf(10.00))
        assertThat(b.debitCardAmount).isEqualTo(BigDecimal.valueOf(20.00))
    }

    @Test
    fun `sin cobros todo queda en cero y no divide entre cero`() {
        val b = summary().toPaymentBreakdown()

        assertThat(b.totalAmount).isEqualTo(BigDecimal.ZERO)
        assertThat(b.cashPercentage).isEqualTo(BigDecimal.ZERO)
    }
}

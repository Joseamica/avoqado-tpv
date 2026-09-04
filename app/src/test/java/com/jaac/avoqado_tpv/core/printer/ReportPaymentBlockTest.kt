package com.jaac.avoqado_tpv.core.printer

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.reports.domain.models.PaymentMethodBreakdown
import com.jaac.avoqado_tpv.features.reports.domain.models.TenderBreakdownResult
import com.jaac.avoqado_tpv.features.reports.domain.models.TenderRow
import org.junit.Test
import java.math.BigDecimal

/**
 * Lo que de verdad sale en el papel del bloque "DESGLOSE POR METODO DE PAGO".
 *
 * `PrinterManager` necesita el SDK de PAX y no se puede instanciar aquí; por eso el
 * bloque se extrajo a un objeto puro. Estas pruebas cubren las dos cosas que el
 * cliente pidió (los tres números por método, y débito separado de crédito) y la que
 * el papel no perdona: **32 columnas**.
 */
class ReportPaymentBlockTest {

    private fun bd(v: String) = BigDecimal(v)

    /** Cifras reales de Testarudo Café el 3-sep-2026. */
    private val testarudo = TenderBreakdownResult.Available(
        listOf(
            TenderRow("DEBIT_CARD", total = bd("9771.15"), tips = bd("804.65")),
            TenderRow("CREDIT_CARD", total = bd("7107.00"), tips = bd("502.00")),
            TenderRow("CASH", total = bd("4728.25"), tips = bd("127.00")),
        )
    )

    private val vacio = PaymentMethodBreakdown.empty()

    private fun render(
        tenders: TenderBreakdownResult = testarudo,
        fallback: PaymentMethodBreakdown = vacio
    ) = ReportPaymentBlock.lines(tenders, fallback)

    // ── P1: los tres numeros por metodo ─────────────────────────────────────

    @Test
    fun `P1 cada metodo imprime venta, propina y total`() {
        val t = render().joinToString("\n")

        assertThat(t).contains("Tarjeta de debito")
        assertThat(t).contains("  Venta                $8,966.50")
        assertThat(t).contains("  Propina                $804.65")
        assertThat(t).contains("  Total                $9,771.15")
    }

    @Test
    fun `P1 la venta se DERIVA del total menos la propina, y los tres suman`() {
        // El servidor manda total (con propina) y propina; la venta no se pide aparte
        // para que no pueda haber un renglon que no cuadre con los otros dos.
        val row = TenderRow("CASH", total = bd("4728.25"), tips = bd("127.00"))

        assertThat(row.sale).isEqualTo(bd("4601.25"))
        assertThat(row.sale + row.tips).isEqualTo(row.total)
    }

    @Test
    fun `P1 debito y credito salen en bloques SEPARADOS, nunca como un solo Tarjeta`() {
        val t = render().joinToString("\n")

        assertThat(t).contains("Tarjeta de debito")
        assertThat(t).contains("Tarjeta de credito")
        // Un renglon "Tarjeta" a secas seria justo lo que el cliente reclamo.
        assertThat(render()).doesNotContain("Tarjeta")
    }

    @Test
    fun `P1 los tres totales del pie cuadran con los metodos`() {
        val t = render().joinToString("\n")

        assertThat(t).contains("TOTAL VENTA           $20,172.75")
        assertThat(t).contains("TOTAL PROPINA          $1,433.65")
        assertThat(t).contains("TOTAL COBRADO         $21,606.40")
    }

    @Test
    fun `los metodos salen de mayor a menor, igual que el corte de la tablet`() {
        val etiquetas = render().filter { it.startsWith("Tarjeta") || it == "Efectivo" }

        assertThat(etiquetas).containsExactly(
            "Tarjeta de debito", "Tarjeta de credito", "Efectivo"
        ).inOrder()
    }

    @Test
    fun `las etiquetas son las MISMAS que usa el corte de la tablet`() {
        // Espejo de `tenderLabel()` en avoqado-android. Si allá cambian, aquí cambian.
        assertThat(ReportPaymentBlock.tenderLabel("CASH")).isEqualTo("Efectivo")
        assertThat(ReportPaymentBlock.tenderLabel("CREDIT_CARD")).isEqualTo("Tarjeta de credito")
        assertThat(ReportPaymentBlock.tenderLabel("DEBIT_CARD")).isEqualTo("Tarjeta de debito")
        assertThat(ReportPaymentBlock.tenderLabel("DIGITAL_WALLET")).isEqualTo("Billetera digital")
        assertThat(ReportPaymentBlock.tenderLabel("BANK_TRANSFER")).isEqualTo("Transferencia")
        assertThat(ReportPaymentBlock.tenderLabel("LO_QUE_SEA")).isEqualTo("Otros")
    }

    // ── P1: 32 columnas. Una linea de mas se corta SIN AVISAR ───────────────

    @Test
    fun `P1 ninguna linea pasa de 32 columnas`() {
        val largas = render().filter { it.length > ReportPaymentBlock.WIDTH }

        assertThat(largas).isEmpty()
    }

    @Test
    fun `P1 tampoco con un dia de mas de un millon de pesos`() {
        val millonario = TenderBreakdownResult.Available(
            listOf(TenderRow("DEBIT_CARD", total = bd("1234567.89"), tips = bd("98765.43")))
        )

        val largas = render(tenders = millonario).filter { it.length > ReportPaymentBlock.WIDTH }
        assertThat(largas).isEmpty()
    }

    @Test
    fun `P1 un importe NUNCA se recorta - si no cabe, la etiqueta se va sola`() {
        val linea = ReportPaymentBlock.dosColumnas("UNA ETIQUETA ABSURDAMENTE LARGA", "$1,234,567.89")

        assertThat(linea).contains("$1,234,567.89")
        // Se parte en dos renglones en vez de comerse digitos.
        assertThat(linea).contains("\n")
        linea.split("\n").forEach { assertThat(it.length).isAtMost(ReportPaymentBlock.WIDTH) }
    }

    @Test
    fun `las dos columnas ocupan exactamente el ancho del papel`() {
        assertThat(ReportPaymentBlock.dosColumnas("  Venta", "$8,966.50").length)
            .isEqualTo(ReportPaymentBlock.WIDTH)
    }

    // ── tres estados: hubo cobros / no hubo / no se pudo preguntar ──────────

    @Test
    fun `P1 sin cobros lo DICE, y no finge un fallo de red`() {
        val t = render(tenders = TenderBreakdownResult.Available(emptyList())).joinToString("\n")

        assertThat(t).contains("No hubo cobros en este periodo.")
        assertThat(t).doesNotContain("No se pudo consultar")
    }

    @Test
    fun `P1 si no se pudo consultar la propina, el ticket lo DICE en vez de callarlo`() {
        val fallback = PaymentMethodBreakdown(
            cashAmount = bd("4601.25"),
            creditCardAmount = bd("6605.00"),
            debitCardAmount = bd("8966.50"),
            unspecifiedCardAmount = BigDecimal.ZERO,
            voucherAmount = BigDecimal.ZERO,
            otherAmount = BigDecimal.ZERO,
            totalAmount = bd("20172.75"),
            cashPercentage = BigDecimal.ZERO,
            creditCardPercentage = BigDecimal.ZERO,
            debitCardPercentage = BigDecimal.ZERO,
            unspecifiedCardPercentage = BigDecimal.ZERO,
            voucherPercentage = BigDecimal.ZERO,
            otherPercentage = BigDecimal.ZERO
        )

        val lines = render(tenders = TenderBreakdownResult.Unavailable, fallback = fallback)
        val t = lines.joinToString("\n")

        // Se imprime lo que SI se sabe...
        assertThat(t).contains("Tarjeta de debito")
        assertThat(t).contains("Tarjeta de credito")
        assertThat(t).contains("TOTAL VENTA           $20,172.75")
        // ...y se declara lo que falta. Un ticket que se ve completo sin estarlo es peor.
        assertThat(t).contains("No se pudo consultar la")
        assertThat(t).contains("importes son SIN propina")
        assertThat(lines.filter { it.length > ReportPaymentBlock.WIDTH }).isEmpty()
    }

    @Test
    fun `en el respaldo credito y debito TAMPOCO se suman`() {
        val fallback = PaymentMethodBreakdown.empty().copy(
            creditCardAmount = bd("100.00"),
            debitCardAmount = bd("200.00")
        )

        val filas = ReportPaymentBlock.fallbackRows(fallback)

        assertThat(filas).containsExactly(
            "Tarjeta de credito" to bd("100.00"),
            "Tarjeta de debito" to bd("200.00")
        ).inOrder()
    }

    @Test
    fun `un metodo en cero no ensucia el ticket, pero uno NEGATIVO si se ve`() {
        val conReembolso = PaymentMethodBreakdown.empty().copy(
            cashAmount = BigDecimal.ZERO,
            creditCardAmount = bd("-350.00")
        )

        val filas = ReportPaymentBlock.fallbackRows(conReembolso)

        // Mas reembolsos que cobros es justo cuando alguien quiere ver el renglon.
        assertThat(filas).containsExactly("Tarjeta de credito" to bd("-350.00"))
    }
}

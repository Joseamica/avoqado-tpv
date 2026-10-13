package com.jaac.avoqado_tpv.core.printer

import com.angelpay.angelpaysdk.models.PrintAlignmentRequest
import com.angelpay.angelpaysdk.models.PrintTicketItemType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * El traductor del dictado de la PAX (32 letras de ancho fijo, acomodado con espacios) al ticket
 * de AngelPay, que imprime con letra de ancho variable.
 *
 * 🔴 Origen (founder, 12-oct-2026, con una N86 en la mano): el corte no salía en la Nexgo —
 * «Esta terminal no imprime desde Avoqado. Pídele el corte a una terminal PAX» — y nada más de
 * [PrinterManager] salía tampoco. Si el traductor copiara los espacios tal cual, el corte saldría,
 * pero con las columnas de dinero chuecas: eso es lo que vigilan estas pruebas.
 */
class TicketDictadoTest {

    @Test
    fun `P1 un renglon de dinero del corte sale en dos columnas, no con espacios`() {
        val pieza = TicketDictado.traducir(ReportPaymentBlock.dosColumnas("TOTAL COBRADO", "$4,619.00") + "\n").single()

        assertThat(pieza.type).isEqualTo(PrintTicketItemType.TWO_COLUMNS)
        assertThat(pieza.left).isEqualTo("TOTAL COBRADO")
        assertThat(pieza.right).isEqualTo("$4,619.00")
    }

    @Test
    fun `P1 la columna derecha es el ULTIMO hueco, aunque la izquierda traiga espacios dobles`() {
        // Renglón de comanda: CANT  DESCRIPCION  IMPORTE.
        val pieza = TicketDictado.traducir("2     Pizza Margherit  $360.00\n").single()

        assertThat(pieza.type).isEqualTo(PrintTicketItemType.TWO_COLUMNS)
        assertThat(pieza.left).isEqualTo("2     Pizza Margherit")
        assertThat(pieza.right).isEqualTo("$360.00")
    }

    @Test
    fun `la sangria corta del desglose se conserva`() {
        val pieza = TicketDictado.traducir(ReportPaymentBlock.dosColumnas("  Venta", "$100.00")).single()

        assertThat(pieza.left).isEqualTo("  Venta")
    }

    @Test
    fun `el derecho con espacios sencillos se queda junto`() {
        val pieza = TicketDictado.traducir("Subtotal:              $665.00 MXN").single()

        assertThat(pieza.left).isEqualTo("Subtotal:")
        assertThat(pieza.right).isEqualTo("$665.00 MXN")
    }

    @Test
    fun `P1 un titulo centrado con espacios sale centrado de verdad`() {
        val pieza = TicketDictado.traducir("    REPORTE DE VENTAS\n").single()

        assertThat(pieza.type).isEqualTo(PrintTicketItemType.TEXT)
        assertThat(pieza.text).isEqualTo("REPORTE DE VENTAS")
        assertThat(pieza.style?.alignment).isEqualTo(PrintAlignmentRequest.CENTER)
    }

    @Test
    fun `un importe empujado a la derecha sale a la derecha`() {
        // Lo que deja dosColumnas cuando la etiqueta no cabe: el importe solo, pegado a la derecha.
        val piezas = TicketDictado.traducir(ReportPaymentBlock.dosColumnas("Tarjeta de credito con propina larga", "$1,234.00"))

        assertThat(piezas.last().text).isEqualTo("$1,234.00")
        assertThat(piezas.last().style?.alignment).isEqualTo(PrintAlignmentRequest.RIGHT)
    }

    @Test
    fun `texto normal va a la izquierda y tal cual`() {
        val pieza = TicketDictado.traducir("Impreso: 12 oct 2026, 10:30\n").single()

        assertThat(pieza.type).isEqualTo(PrintTicketItemType.TEXT)
        assertThat(pieza.text).isEqualTo("Impreso: 12 oct 2026, 10:30")
        assertThat(pieza.style?.alignment).isEqualTo(PrintAlignmentRequest.LEFT)
    }

    @Test
    fun `P1 las lineas de iguales y de guiones son divisores, nunca 32 letras`() {
        val piezas = TicketDictado.traducir("================================\n--------------------------------\n")

        assertThat(piezas.map { it.type }).containsExactly(PrintTicketItemType.DIVIDER, PrintTicketItemType.DIVIDER)
    }

    @Test
    fun `los renglones vacios son espacio en blanco, uno por renglon`() {
        val piezas = TicketDictado.traducir("A\n\n\n")

        assertThat(piezas.map { it.type })
            .containsExactly(PrintTicketItemType.TEXT, PrintTicketItemType.SPACER, PrintTicketItemType.SPACER)
            .inOrder()
    }

    @Test
    fun `varios printStr que arman un solo renglon se leen juntos`() {
        // Así llega del dictado: el texto se junta antes de partirlo, no por cada printStr.
        val piezas = TicketDictado.traducir("Total Ordenes:" + "               8\n")

        assertThat(piezas.single().type).isEqualTo(PrintTicketItemType.TWO_COLUMNS)
    }

    @Test
    fun `sin dictado no hay piezas`() {
        assertThat(TicketDictado.traducir("")).isEmpty()
    }

    // ── La impresora Nexgo completa, con el SDK de mentira ──────────────────

    @Test
    fun `P1 el dictado entero viaja en UN solo ticket al SDK`() {
        val enviados = mutableListOf<com.angelpay.angelpaysdk.models.PrintTicketRequest>()
        val impresora = ImpresoraAngelPay(enviar = { enviados += it; Result.success(Unit) })

        impresora.init()
        impresora.printStr("    REPORTE DE VENTAS\n", null)
        impresora.printStr(ReportPaymentBlock.dosColumnas("TOTAL COBRADO", "$4,619.00") + "\n", null)

        assertThat(impresora.start()).isEqualTo(0)
        assertThat(enviados).hasSize(1)
        assertThat(enviados.single().body.map { it.type })
            .containsExactly(PrintTicketItemType.TEXT, PrintTicketItemType.TWO_COLUMNS)
            .inOrder()
    }

    @Test
    fun `P1 si el SDK falla, start lo dice (no pasa por exito)`() {
        val impresora = ImpresoraAngelPay(enviar = { Result.failure<Unit>(IllegalStateException("C220")) })

        impresora.printStr("hola\n", null)

        assertThat(impresora.start()).isNotEqualTo(0)
    }

    @Test
    fun `init borra un dictado anterior`() {
        val enviados = mutableListOf<com.angelpay.angelpaysdk.models.PrintTicketRequest>()
        val impresora = ImpresoraAngelPay(enviar = { enviados += it; Result.success(Unit) })

        impresora.printStr("basura\n", null)
        impresora.init()
        impresora.printStr("bueno\n", null)
        impresora.start()

        assertThat(enviados.single().body.single().text).isEqualTo("bueno")
    }
}

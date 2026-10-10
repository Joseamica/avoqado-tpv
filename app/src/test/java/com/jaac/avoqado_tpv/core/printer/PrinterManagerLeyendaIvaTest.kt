package com.jaac.avoqado_tpv.core.printer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** IVA por producto, plan 7 (6.2): la PAX imprime la MISMA leyenda que la Nexgo y ya no calcula un 16 %. */
class PrinterManagerLeyendaIvaTest {

    /** El código de PrinterManager SIN comentarios: un comentario que cuente la historia no es lo que se imprime. */
    private val codigo = File("src/main/java/com/jaac/avoqado_tpv/core/printer/PrinterManager.kt").readLines()
        .map { it.substringBefore("//") }
        .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
        .joinToString("\n")

    @Test
    fun `P1 lo que imprime la PAX en el lugar del IVA es la leyenda, y nada en una devolucion`() {
        assertEquals(listOf(LeyendaIva.TEXTO), LeyendaIva.renglonesDelTicket(esDevolucion = false))
        assertEquals(emptyList<String>(), LeyendaIva.renglonesDelTicket(esDevolucion = true))
    }

    @Test
    fun `P1 la PAX imprime esos renglones y ya no calcula un IVA del 16 por ciento`() {
        assertTrue("PrinterManager no imprime LeyendaIva.renglonesDelTicket", codigo.contains("LeyendaIva.renglonesDelTicket("))
        assertFalse("PrinterManager volvió a calcular el IVA al 16 %", codigo.contains("1.16"))
        assertFalse("PrinterManager volvió a imprimir «IVA 16%»", codigo.contains("IVA 16%"))
        assertFalse("PrinterManager volvió a imprimir el desglose", codigo.contains("Desglose IVA"))
    }

    /**
     * Plan 7a, ola (T6 m1): las reglas de [TotalesDelCobro] sólo sirven si `printReceipt` les pasa lo que recibió. La llamada, sin
     * espacios, de `TotalesDelCobro.desdeLoCobrado(` hasta los renglones que imprime.
     */
    private val llamada = codigo.filterNot { it.isWhitespace() }
        .substringAfter("TotalesDelCobro.desdeLoCobrado(", missingDelimiterValue = "")
        .substringBefore("TotalesDelCobro.renglonesPax(")

    @Test
    fun `P1 la PAX le pasa a TotalesDelCobro el descuento que recibio`() {
        assertTrue("PrinterManager ya no llama a TotalesDelCobro.desdeLoCobrado", llamada.isNotEmpty())
        assertTrue("PrinterManager no pasa discountValue como descuento: «$llamada»", llamada.contains("descuento=discountValue,"))
        assertTrue(
            "discountValue ya no sale del discountAmount que recibe printReceipt",
            codigo.filterNot { it.isWhitespace() }.contains("valdiscountValue=discountAmount?.toBigDecimalOrNull()"),
        )
    }

    @Test
    fun `P1 la PAX le pasa a TotalesDelCobro los renglones de la orden`() {
        assertTrue(
            "PrinterManager no pasa los renglones de la orden: «$llamada»",
            llamada.contains("renglones=orderItems.orEmpty().map{it.totalPrice}"),
        )
    }
}

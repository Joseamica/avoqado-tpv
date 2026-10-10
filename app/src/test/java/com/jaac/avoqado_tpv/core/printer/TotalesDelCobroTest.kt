package com.jaac.avoqado_tpv.core.printer

import java.io.File
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IVA por producto, plan 7a, Tarea 6 ronda 1 (A7-R6): el papel del cobro de la PAX cuadra como el de las apps y el del servidor.
 * Antes: «Subtotal» = total − propina (ya neto), «DESC. C/IMP.» siempre (también $0.00) y TOTAL ⇒ con descuento no sumaba.
 */
class TotalesDelCobroTest {

    private fun d(v: String) = BigDecimal(v)

    private fun papel(total: String, propina: String = "0", descuento: String = "0", renglones: List<String> = listOf("90.00", "30.00")) =
        TotalesDelCobro.desdeLoCobrado(d(total), d(propina), d(descuento), renglones.map(::d))

    private fun cuadra(p: TotalesDelCobro.Papel) = (p.subtotal - p.descuento + p.propina).compareTo(p.total) == 0

    private fun texto(p: TotalesDelCobro.Papel) = TotalesDelCobro.renglonesPax(p, LeyendaIva.renglonesDelTicket(esDevolucion = false))

    @Test
    fun `P1 venta con descuento - subtotal bruto, descuento y total suman`() {
        val p = papel(total = "100.00", descuento = "20.00")
        assertEquals(0, p.subtotal.compareTo(d("120.00")))
        assertEquals(0, p.descuento.compareTo(d("20.00")))
        assertTrue("no cuadra: $p", cuadra(p))
        assertEquals(
            listOf(
                "Subtotal:".padEnd(22) + "$120.00 MXN",
                "Descuento:".padEnd(22) + "-$20.00 MXN",
                LeyendaIva.TEXTO,
            ),
            texto(p),
        )
    }

    @Test
    fun `P1 venta con descuento y propina - el orden de las apps y cuadra`() {
        val p = papel(total = "115.00", propina = "15.00", descuento = "20.00")
        assertTrue("no cuadra: $p", cuadra(p))
        assertEquals(
            listOf(
                "Subtotal:".padEnd(22) + "$120.00 MXN",
                "Descuento:".padEnd(22) + "-$20.00 MXN",
                LeyendaIva.TEXTO,
                "Propina:".padEnd(22) + "$15.00 MXN",
            ),
            texto(p),
        )
    }

    @Test
    fun `P1 venta sin descuento - no hay renglon de 0 pesos`() {
        val p = papel(total = "120.00")
        assertTrue(cuadra(p))
        val t = texto(p)
        assertFalse("salió un renglón de descuento en $0.00: $t", t.any { it.startsWith("Descuento") })
        assertEquals(listOf("Subtotal:".padEnd(22) + "$120.00 MXN", LeyendaIva.TEXTO), t)
    }

    @Test
    fun `P1 pago parcial - no inventa un descuento ni un subtotal de la cuenta entera`() {
        // Cuenta de $120 con $20 de descuento; este cobro es de $50 (dividido). La terminal no sabe qué parte del descuento le toca.
        val p = papel(total = "50.00", descuento = "20.00")
        assertEquals(0, p.subtotal.compareTo(d("50.00")))
        assertEquals(0, p.descuento.signum())
        assertTrue(cuadra(p))
    }

    @Test
    fun `P1 descuento sin renglones - subtotal es lo cobrado, sin renglon de descuento`() {
        val p = papel(total = "100.00", descuento = "20.00", renglones = emptyList())
        assertEquals(0, p.subtotal.compareTo(d("100.00")))
        assertEquals(0, p.descuento.signum())
        assertTrue(cuadra(p))
    }

    @Test
    fun `P1 cuenta con cargo o IVA aparte que la terminal no ve - renglon unico, no un descuento falso`() {
        // Renglones $100, descuento $10, pero se cobró $105 (la cuenta sumaba $15 de cargo): 100 − 10 ≠ 105 ⇒ sin desglose.
        val p = papel(total = "105.00", descuento = "10.00", renglones = listOf("100.00"))
        assertEquals(0, p.subtotal.compareTo(d("105.00")))
        assertEquals(0, p.descuento.signum())
        assertTrue(cuadra(p))
    }

    /** El código de PrinterManager SIN comentarios. */
    private val codigo = File("src/main/java/com/jaac/avoqado_tpv/core/printer/PrinterManager.kt").readLines()
        .map { it.substringBefore("//") }
        .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
        .joinToString("\n")

    @Test
    fun `P1 la PAX imprime estos totales y ya no el renglon DESC C-IMP`() {
        assertTrue("PrinterManager no arma los totales con TotalesDelCobro", codigo.contains("TotalesDelCobro.desdeLoCobrado("))
        assertTrue("PrinterManager no imprime TotalesDelCobro.renglonesPax", codigo.contains("TotalesDelCobro.renglonesPax("))
        assertFalse("PrinterManager volvió a imprimir «DESC. C/IMP.»", codigo.contains("DESC. C/IMP."))
    }
}

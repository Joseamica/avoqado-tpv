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

    /** Renderer real. Sólo se sustituye el dispositivo IPrinter: no invoca bibliotecas nativas. */
    private fun renderT11(
        total: String, tip: String? = null, discount: String? = null,
        charge: String? = null, itemTotal: String? = null, refund: Boolean = false, printerCode: Int = 0,
    ): Pair<Result<Unit>, String> {
        val device = io.mockk.mockk<com.pax.dal.IPrinter>(relaxed = true)
        val lines = mutableListOf<String>()
        io.mockk.every { device.printStr(capture(lines), any()) } answers { Unit }
        io.mockk.every { device.start() } returns printerCode
        val manager = PrinterManager(io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true))
        // Se evita cargar Neptune. Se ejecuta íntegro printReceipt, incluidos sus totales y printStr.
        // Desde la 2.12.5 la PAX imprime por `ImpresoraPax` y el camino se elige con `esHardwarePax`;
        // se fija a PAX para que el renglón sea el mismo también en la variante Nexgo.
        val field = PrinterManager::class.java.getDeclaredField("impresoraPax\$delegate")
        field.isAccessible = true
        field.set(manager, lazyOf(ImpresoraPax(device)))
        PrinterManager::class.java.getDeclaredField("esHardwarePax").apply { isAccessible = true }.setBoolean(manager, true)
        val items = itemTotal?.let {
            listOf(com.jaac.avoqado_tpv.features.ordering.domain.OrderItem(
                id = "item", orderId = "order", productId = "product", productName = "Producto", productSku = null,
                quantity = 1, unitPrice = java.math.BigDecimal(it), totalPrice = java.math.BigDecimal(it),
                notes = null, kitchenStatus = com.jaac.avoqado_tpv.features.ordering.domain.KitchenStatus.PENDING,
                createdAt = java.time.Instant.EPOCH, sentToKitchenAt = null,
            ))
        }
        return try {
            manager.printReceipt(receiptUrl = null, amount = total, authCode = "auth", tipAmount = tip,
                venueName = "Negocio A", orderItems = items, discountAmount = discount, isRefund = refund,
                serviceChargeAmount = charge) to lines.joinToString("")
        } finally {
            io.mockk.clearMocks(device)
        }
    }

    @Test
    fun `T11 renderer PAX conserva cargo descuento propina y total`() {
        val (result, text) = renderT11("120.00", tip = "5.00", discount = "20.00", charge = "15.00", itemTotal = "120.00")
        assertTrue(result.toString(), result.isSuccess)
        assertTrue(text, text.contains("Subtotal:".padEnd(22) + "$120.00 MXN"))
        assertTrue(text, text.contains("Descuento:".padEnd(22) + "-$20.00 MXN"))
        assertTrue(text, text.contains("Cargo:".padEnd(22) + "$15.00 MXN"))
        assertTrue(text, text.contains("Propina:".padEnd(22) + "$5.00 MXN"))
        assertTrue(text, text.contains("TOTAL: $120.00"))
        assertTrue(text, text.indexOf("Cargo:") < text.indexOf(LeyendaIva.TEXTO))
    }

    @Test
    fun `T11 renderer PAX cargo sin articulos no inventa descuento incluso base cero`() {
        for ((total, base) in listOf("115.00" to "100.00", "15.00" to "0.00")) {
            val (result, text) = renderT11(total, discount = "20.00", charge = "15.00")
            assertTrue(result.toString(), result.isSuccess)
            assertTrue(text, text.contains("Subtotal:".padEnd(22) + "$$base MXN"))
            assertTrue(text, text.contains("Cargo:".padEnd(22) + "$15.00 MXN"))
            assertFalse(text, text.contains("Descuento:"))
            assertTrue(text, text.contains("TOTAL: $$total"))
        }
    }

    @Test
    fun `T11 renderer PAX parcial ausente cero y refund no agregan cargo`() {
        for (charge in listOf(null, "0.00")) {
            val (result, text) = renderT11("50.00", discount = "20.00", charge = charge, itemTotal = "120.00")
            assertTrue(result.toString(), result.isSuccess)
            assertTrue(text, text.contains("Subtotal:".padEnd(22) + "$50.00 MXN"))
            assertFalse(text, text.contains("Cargo:"))
            assertFalse(text, text.contains("Descuento:"))
        }
        val (refund, text) = renderT11("50.00", tip = "5.00", charge = "999.00", refund = true)
        assertTrue(refund.toString(), refund.isSuccess)
        assertTrue(text, text.contains("TOTAL REEMBOLSO:"))
        assertFalse(text, text.contains("Cargo:"))
        assertFalse(text, text.contains("Propina:"))
        assertFalse(text, text.contains(LeyendaIva.TEXTO))
    }

    @Test
    fun `T11 renderer PAX invalido no imprime y error dispositivo sigue failure`() {
        for (charge in listOf("-1", "115.01", "0.001", "NaN")) {
            val (result, text) = renderT11("120.00", tip = "5.00", charge = charge)
            assertTrue("cargo invalido: $charge", result.isFailure)
            assertEquals("", text)
        }
        val (result, _) = renderT11("115.00", charge = "15.00", printerCode = 3)
        assertTrue(result.isFailure)
    }
}

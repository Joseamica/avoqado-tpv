package com.jaac.avoqado_tpv.core.printer

import com.jaac.avoqado_tpv.features.payments.domain.models.Payment
import com.jaac.avoqado_tpv.features.payments.domain.models.PaymentMethod
import com.jaac.avoqado_tpv.features.payments.domain.models.PaymentStatus
import com.pax.dal.IPrinter
import io.mockk.*
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class PrinterManagerPaymentHistoryTest {
    private fun render(amount: String, tip: String, total: String, printerCode: Int = 0): Pair<Result<Unit>, String> {
        val device = mockk<IPrinter>(relaxed = true)
        val lines = mutableListOf<String>()
        every { device.printStr(capture(lines), any()) } answers { Unit }
        every { device.start() } returns printerCode
        val manager = PrinterManager(mockk(relaxed = true), mockk(relaxed = true))
        // El renderer real dicta a la impresora; sólo se sustituye el hardware nativo.
        PrinterManager::class.java.getDeclaredField("impresoraPax\$delegate").apply {
            isAccessible = true
        }.set(manager, lazyOf(ImpresoraPax(device)))
        PrinterManager::class.java.getDeclaredField("esHardwarePax").apply {
            isAccessible = true
        }.setBoolean(manager, true)
        val payment = Payment(
            id = "cmvmqk85c003owatlt6wu9pge", orderId = null, orderNumber = null,
            venueId = "cmvjzw7r70002wamrlf6igos2", amount = BigDecimal(amount),
            tipAmount = BigDecimal(tip), totalAmount = BigDecimal(total), method = PaymentMethod.CASH,
            processedBy = null, createdAt = Instant.parse("2026-10-24T18:32:12Z"),
            status = PaymentStatus.COMPLETED, tableName = null,
        )
        return try {
            manager.printPaymentHistoryReceipt(payment, venueName = "Negocio de prueba") to lines.joinToString("")
        } finally {
            clearMocks(device)
        }
    }

    @Test
    fun `historial sin propina imprime un solo signo de pesos`() {
        val (result, text) = render("90.00", "0.00", "90.00")
        assertTrue(result.toString(), result.isSuccess)
        val money = text.lineSequence().filter { it.startsWith("Monto:") || it.startsWith("TOTAL:") }
            .map { it.replace(Regex(" +"), " ").trim() }.toList()
        assertEquals(listOf("Monto: $90.00 MXN", "TOTAL: $90.00 MXN"), money)
        assertFalse(text, text.contains("Propina:"))
    }

    @Test
    fun `historial con propina conserva redondeo y un solo signo en los tres importes`() {
        val (result, text) = render("90.125", "9.875", "100.00")
        assertTrue(result.toString(), result.isSuccess)
        val money = text.lineSequence().filter {
            it.startsWith("Monto:") || it.startsWith("Propina:") || it.startsWith("TOTAL:")
        }.map { it.replace(Regex(" +"), " ").trim() }.toList()
        assertEquals(listOf("Monto: $90.13 MXN", "Propina: $9.88 MXN", "TOTAL: $100.00 MXN"), money)
    }

    @Test
    fun `historial conserva fallo cuando la impresora no acepta el ticket`() {
        assertTrue(render("90.00", "0.00", "90.00", printerCode = 3).first.isFailure)
    }
}

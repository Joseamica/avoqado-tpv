package com.jaac.avoqado_tpv.features.payment.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.DatosFiscalesDelTicket
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.printer.ImpresoraPax
import com.jaac.avoqado_tpv.core.printer.PrinterManager
import com.jaac.avoqado_tpv.features.checkout.data.ImpresoraDeVentaGuardada
import com.jaac.avoqado_tpv.features.checkout.domain.model.ExtraDeTicket
import com.jaac.avoqado_tpv.features.checkout.domain.model.RenglonDeTicket
import com.jaac.avoqado_tpv.features.checkout.domain.model.VentaLocal
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Renderer real hasta IPrinter, sin red ni SDK nativo. No acredita papel físico. */
class TicketDeVentaGuardadaTest {
    private val venta = VentaLocal("c000000000000000000000001", "venue", "staff", null, 1760000000000L, 9000, "{}", listOf(
        RenglonDeTicket("linea-1", "Blusa guardada", 2, 10000, 12000, listOf(
            ExtraDeTicket("extra-1", "Bolsa", 500, "MULTIPLE_CHOICE")), "Para regalo"),
        RenglonDeTicket("linea-2", "Cortesia", 1, 0, 2000, emptyList(), null)))

    @Test fun `ticket local imprime renglones extras descuento total y leyenda sin folio ni QR`() = runTest {
        val device = mockk<com.pax.dal.IPrinter>(relaxed = true)
        val texto = mutableListOf<String>()
        every { device.printStr(capture(texto), any()) } answers { Unit }
        every { device.start() } returns 0
        val storage = mockk<SecureStorage>(relaxed = true)
        every { storage.getVenueName() } returns "Boutique"
        every { storage.getVenueLogo() } returns null
        val printer = PrinterManager(mockk(relaxed = true), storage, io.mockk.mockk(relaxed = true))
        PrinterManager::class.java.getDeclaredField("impresoraPax\$delegate").apply { isAccessible = true }.set(printer, lazyOf(ImpresoraPax(device)))
        PrinterManager::class.java.getDeclaredField("esHardwarePax").apply { isAccessible = true }.setBoolean(printer, true)
        val result = ImpresoraDeVentaGuardada.imprimirEnPax(venta, 500, printer, storage)
        assertThat(result.isSuccess).isTrue()
        val papel = texto.joinToString("")
        assertThat(papel).contains("Blusa guardada")
        assertThat(papel).contains("Bolsa")
        assertThat(papel).contains("Para regalo")
        assertThat(papel).contains("Cortesia")
        assertThat(papel).contains("TOTAL: $95.00")
        assertThat(papel).contains("-$10.00")
        assertThat(papel).contains("Venta guardada sin conexión")
        assertThat(papel).doesNotContain("FOLIO")
        assertThat(papel).doesNotContain("Escanea")
    }

    private suspend fun papelDe(venta: VentaLocal, razonSocial: String = "Boutique SA de CV"): String {
        val device = mockk<com.pax.dal.IPrinter>(relaxed = true)
        val texto = mutableListOf<String>()
        every { device.printStr(capture(texto), any()) } answers { Unit }
        every { device.start() } returns 0
        val storage = mockk<SecureStorage>(relaxed = true)
        every { storage.getVenueName() } returns "Boutique"
        every { storage.getVenueLogo() } returns null
        // D50: la terminal está en "venue" y guarda el emisor de "venue" (regla real: sólo se da al ticket de ese negocio).
        every { storage.getVenueId() } returns "venue"
        every { storage.datosFiscalesDelTicket(any()) } answers {
            if (firstArg<String?>() == "venue") DatosFiscalesDelTicket(razonSocial, "BOU010101AA1", delEmisor = true) else DatosFiscalesDelTicket.NINGUNO
        }
        val printer = PrinterManager(mockk(relaxed = true), storage, io.mockk.mockk(relaxed = true))
        PrinterManager::class.java.getDeclaredField("impresoraPax\$delegate").apply { isAccessible = true }.set(printer, lazyOf(ImpresoraPax(device)))
        PrinterManager::class.java.getDeclaredField("esHardwarePax").apply { isAccessible = true }.setBoolean(printer, true)
        assertThat(ImpresoraDeVentaGuardada.imprimirEnPax(venta, 0, printer, storage).isSuccess).isTrue()
        return texto.joinToString("")
    }

    @Test fun `control D50 el ticket de la venta guardada lleva el emisor de su negocio`() = runTest {
        val papel = papelDe(venta)
        assertThat(papel).contains("Boutique SA de CV")
        assertThat(papel).contains("RFC: BOU010101AA1")
    }

    @Test fun `D50 Codex r1 2 la razon social larga del emisor sale completa en varios renglones`() = runTest {
        val papel = papelDe(venta, razonSocial = "OPERADORA GASTRONOMICA DEL VALLE DE MEXICO SA DE CV")
        assertThat(papel).contains("OPERADORA GASTRONOMICA DEL VALLE")
        assertThat(papel).contains("DE MEXICO SA DE CV")
        assertThat(papel).contains("RFC: BOU010101AA1")
    }

    @Test fun `P1 D50 la venta guardada de otro negocio sale sin la razon social ni el RFC del negocio de la terminal`() = runTest {
        // Se guardó sin red en "venue-B"; la terminal se reactivó en "venue" antes de imprimirla.
        val papel = papelDe(venta.copy(venueId = "venue-B"))
        assertThat(papel).doesNotContain("BOU010101AA1")
        assertThat(papel).doesNotContain("Boutique SA de CV")
    }
}

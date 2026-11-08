package com.jaac.avoqado_tpv.core.printer

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.entity.LocalShiftSessionEntity
import com.jaac.avoqado_tpv.features.shift.domain.LocalShiftSummary
import com.jaac.avoqado_tpv.features.shift.presentation.CajaLocalPresentation
import io.mockk.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Task7CajaPrinterTest {
    @Test fun `provisional summary uses a readable bitmap and separates cash blocks`() {
        val device = mockk<com.pax.dal.IPrinter>(relaxed = true)
        val images = mutableListOf<android.graphics.Bitmap>()
        every { device.printBitmap(capture(images)) } answers { Unit }
        every { device.start() } returns 0
        val manager = PrinterManager(mockk(relaxed = true), mockk(relaxed = true), io.mockk.mockk(relaxed = true))
        PrinterManager::class.java.getDeclaredField("impresoraPax\$delegate").apply { isAccessible = true }
            .set(manager, lazyOf(ImpresoraPax(device)))
        PrinterManager::class.java.getDeclaredField("esHardwarePax").apply { isAccessible = true }.setBoolean(manager, true)
        val row = LocalShiftSessionEntity("c000000000000000000000001", "venue", "LOCAL", openingStatus = "CONFIRMED",
            originalOpenedAt = "2026-11-01T10:00:00Z", startingCash = "400.00", openedByStaffId = "authorA", localStatus = "CLOSED",
            closeStatus = "CONFIRMED", countedCash = "425.00", cashReconciliationAction = "COUNTED", closeOperationId = "closeA",
            closedAtLocal = "2026-11-01T11:00:00Z", createdAt = 1, updatedAt = 1)
        val summary = CajaLocalPresentation(row, LocalShiftSummary(BigDecimal("400.00"), BigDecimal("425.00"), "CLOSED", "CONFIRMED", "CONFIRMED", null))

        assertThat(manager.printLocalShiftSummary(summary).isSuccess).isTrue()

        assertThat(images).hasSize(1)
        assertThat(images.single().width).isEqualTo(384)
        assertThat(images.single().height).isGreaterThan(600)
        assertThat(images.single().getPixel(0, 0)).isEqualTo(android.graphics.Color.WHITE)
        assertThat(summary.paper()).contains("Fondo capturado: $400.00\n\nConteo capturado: $425.00")
        assertThat(summary.paper()).contains("NO ES UN CORTE DEFINITIVO")
        assertThat(summary.paper()).contains(row.localSessionId)
        assertThat(summary.paper()).contains(row.originalOpenedAt)
        assertThat(summary.paper()).contains(row.closedAtLocal!!)
    }
    private fun unknownFundSummary(closeStatus: String = "REVIEW"): CajaLocalPresentation {
        val row = LocalShiftSessionEntity("clocal00000000000000000001", "venue", "REMOTE", openingStatus = "CONFIRMED",
            originalOpenedAt = "2026-10-28T10:00:00Z", startingCash = null, openedByStaffId = "authorA", localStatus = "CLOSED",
            closeStatus = closeStatus, countedCash = "72.00", cashReconciliationAction = "COUNTED", closeOperationId = "closeA",
            createdAt = 1, updatedAt = 1)
        return CajaLocalPresentation(row, LocalShiftSummary(null, BigDecimal("72.00"), "CLOSED", "CONFIRMED", closeStatus, null))
    }
    private fun render(code: Int): Pair<Result<Unit>, android.graphics.Bitmap> {
        val device = mockk<com.pax.dal.IPrinter>(relaxed = true)
        val images = mutableListOf<android.graphics.Bitmap>()
        every { device.printBitmap(capture(images)) } answers { Unit }
        every { device.start() } returns code
        val manager = PrinterManager(mockk(relaxed = true), mockk(relaxed = true), io.mockk.mockk(relaxed = true))
        PrinterManager::class.java.getDeclaredField("impresoraPax\$delegate").apply { isAccessible = true }
            .set(manager, lazyOf(ImpresoraPax(device)))
        PrinterManager::class.java.getDeclaredField("esHardwarePax").apply { isAccessible = true }.setBoolean(manager, true)
        return try { manager.printLocalShiftSummary(unknownFundSummary()) to images.single() } finally { clearMocks(device) }
    }
    @Test fun `real printer bridge prints original count without a false zero corte`() {
        val (result, image) = render(0)
        val text = unknownFundSummary().paper()
        assertThat(result.isSuccess).isTrue()
        assertThat(image.sameAs(renderLocalShiftSummary(text))).isTrue()
        assertThat(text).contains("PROVISIONAL")
        assertThat(text).contains("Conteo capturado: $72.00")
        assertThat(text).contains("Fondo no disponible")
        assertThat(text).doesNotContain("$0.00")
        assertThat(text).doesNotContain("Corte definitivo")
    }
    @Test fun `printing failure remains recoverable and cannot confirm a close`() {
        assertThat(render(3).first.isFailure).isTrue()
        assertThat(render(0).first.isSuccess).isTrue()
    }
    @Test fun `Nexgo existing adapter receives the same provisional content without native PAX`() {
        val requests = mutableListOf<com.angelpay.angelpaysdk.models.PrintTicketRequest>()
        val adapter = ImpresoraAngelPay { request -> requests += request; Result.success(Unit) }
        mockkObject(ImpresoraAngelPay.Companion)
        try {
            every { ImpresoraAngelPay.paraEstaTerminal() } returns adapter
            val manager = PrinterManager(mockk(relaxed = true), mockk(relaxed = true), io.mockk.mockk(relaxed = true))
            PrinterManager::class.java.getDeclaredField("esHardwarePax").apply { isAccessible = true }.setBoolean(manager, false)
            val summary = unknownFundSummary("PENDING")
            assertThat(manager.printLocalShiftSummary(summary).isSuccess).isTrue()
            assertThat(requests).hasSize(1)
            val image = requests.single().body.single { it.type == com.angelpay.angelpaysdk.models.PrintTicketItemType.IMAGE_BASE64 }
            assertThat(image.targetWidthPx).isEqualTo(384)
            val png = android.util.Base64.decode(image.imageBase64, android.util.Base64.NO_WRAP)
            val decoded = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
            assertThat(decoded).isNotNull()
            assertThat(decoded.sameAs(renderLocalShiftSummary(summary.paper()))).isTrue()
            val text = summary.paper()
            assertThat(text).contains("PROVISIONAL")
            assertThat(text).contains("Conteo capturado: $72.00")
            assertThat(text).contains("Fondo no disponible")
            assertThat(text).doesNotContain("$0.00")
        } finally { unmockkObject(ImpresoraAngelPay.Companion) }
    }
}

package com.jaac.avoqado_tpv.features.shift.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.google.gson.Gson
import com.jaac.avoqado_tpv.core.data.local.entity.LocalShiftSessionEntity
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.shift.domain.LocalShiftSummary
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Real Compose controls; callbacks are recorded without writing money or starting a card reader. */
class LocalCajaContentTest {
    @get:Rule val compose = createComposeRule()
    private val row = LocalShiftSessionEntity("c000000000000000000000001", "venue", "LOCAL",
        openingStatus = "CONFIRMED", originalOpenedAt = "2026-11-01T10:00:00Z", startingCash = "50.00",
        openedByStaffId = "staff", localStatus = "OPEN", createdAt = 1, updatedAt = 1)
    private fun ui(r: LocalShiftSessionEntity = row) = CajaLocalPresentation(r,
        LocalShiftSummary(r.startingCash?.toBigDecimal(), r.countedCash?.toBigDecimal(), r.localStatus,
            r.openingStatus, r.closeStatus, r.adoptionNotice))
    private val financialShift = Gson().fromJson("""{"id":"remoteA","venueId":"venue","staffId":"staff","staffName":"Ana","startTime":"2026-11-01T10:00:00Z","status":"OPEN","startingCash":50,"totalSales":27.50,"totalTips":0,"totalOrders":1,"totalCashPayments":27.50,"totalCardPayments":0,"totalVoucherPayments":0,"totalOtherPayments":0,"totalProductsSold":1,"financialTotalsKnown":true}""", Shift::class.java)

    @Test fun paperExportsTheActualAndroidRasterForVisualReview() {
        val closed = row.copy(startingCash = "400.00", localStatus = "CLOSED", closeOperationId = "closeA",
            closeStatus = "CONFIRMED", cashReconciliationAction = "COUNTED", countedCash = "425.00",
            closedAtLocal = "2026-11-01T11:00:00Z")
        val cases = mapOf(
            "counted" to closed,
            "pending" to closed.copy(closeStatus = "PENDING"),
            "adopted" to row.copy(startingCash = "300.00", openingDisposition = "ADOPTED", remoteStartingCash = "400.00"),
            "ignored" to closed.copy(closeEvidenceJson = """{"operation":{"countApplied":false}}"""),
            "remote-closed" to row.copy(localStatus = "CLOSED", closeStatus = "REVIEW",
                lastError = "Cierre observado en el servidor (CLOSED). Revisa la caja original; no se aplicó un conteo de este aparato."),
        )
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val folder = java.io.File(context.filesDir, "caja-paper-preview").apply { mkdirs() }
        cases.forEach { (name, source) ->
            val bitmap = com.jaac.avoqado_tpv.core.printer.renderLocalShiftSummary(ui(source).paper())
            assertEquals(384, bitmap.width)
            org.junit.Assert.assertTrue(bitmap.height > 600)
            java.io.File(folder, "$name.png").outputStream().use {
                org.junit.Assert.assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        }
    }

    @Test fun normalCajaShowsItsActionAndRevealsExistingToolsOnlyOnDemand() {
        val actions = mutableListOf<String>()
        compose.setContent { AvoqadoTheme {
            LocalCajaContent(ui(), false, true, true, false,
                { actions += "close" }, { actions += "open" }, { actions += "done" }, { actions += "adopt" },
                { id, offset -> actions += "review:$id:$offset" }, { actions += "history:$it" }, null,
                financialShift = financialShift)
        } }
        compose.onNodeWithText("Caja abierta").assertIsDisplayed()
        compose.onNodeWithText("Fondo capturado: $50.00").assertIsDisplayed()
        compose.onNodeWithText("Cerrar caja").assertIsDisplayed().performClick()
        compose.onNodeWithText(ui().description).assertDoesNotExist()
        compose.onNodeWithText("Ventas: $27.50").assertDoesNotExist()
        compose.onNodeWithText("Imprimir resumen provisional").assertDoesNotExist()
        compose.onNodeWithText("Ver detalles").performClick()
        compose.onNodeWithText("Ventas: $27.50").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Imprimir resumen provisional").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Revisar registros pendientes").performScrollTo().performClick()
        compose.onNodeWithText("Historial de este aparato").performScrollTo().performClick()
        compose.onNodeWithText("Ocultar detalles").performScrollTo().performClick()
        compose.onNodeWithText("Ventas: $27.50").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("close", "review:${row.localSessionId}:0", "history:0"), actions) }
    }

    @Test fun offlineCloseKeepsTheCountAndExplicitAcknowledgmentOutsideDetails() {
        var done = 0
        val closed = row.copy(localStatus = "CLOSED", closeOperationId = "closeA", closeStatus = "PENDING",
            cashReconciliationAction = "COUNTED", countedCash = "72.00")
        compose.setContent { AvoqadoTheme {
            LocalCajaContent(ui(closed), true, false, false, true, {}, {}, { done++ }, {}, { _, _ -> }, {}, null)
        } }
        compose.onNodeWithText("Sin conexión · guardado en este aparato").assertIsDisplayed()
        compose.onNodeWithText("Cierre pendiente").assertIsDisplayed()
        compose.onNodeWithText("Conteo capturado: $72.00").assertIsDisplayed()
        compose.onNodeWithText(ui(closed).notice!!).assertIsDisplayed()
        compose.onNodeWithText("Listo").assertIsEnabled().performClick()
        compose.onNodeWithText("Abrir nueva caja").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, done) }
    }

    @Test fun conflictAndBothFundsStayVisibleWithLargeTextAndDarkTheme() {
        var reviews = 0
        var adopts = 0
        val conflict = row.copy(localStatus = "CLOSED", closeStatus = "REVIEW", closeOperationId = "closeA",
            openingDisposition = "ADOPTED", remoteStartingCash = "100.00", cashReconciliationAction = "COUNTED",
            countedCash = "72.00", lastError = "Otra operación cerró esta caja; tu conteo se conserva sin aplicar.")
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                AvoqadoTheme(darkTheme = true) {
                    LocalCajaContent(ui(conflict), false, true, true, true, {}, {}, {}, { adopts++ },
                        { _, _ -> reviews++ }, {}, null)
                }
            }
        }
        compose.onNodeWithText(conflict.lastError!!).assertIsDisplayed()
        compose.onNodeWithText("Fondo capturado: $50.00").assertIsDisplayed()
        compose.onNodeWithText("Fondo de la caja vinculada: $100.00").assertIsDisplayed()
        compose.onNodeWithText("Conteo capturado: $72.00").assertIsDisplayed()
        compose.onNodeWithText("Revisar registros pendientes").performScrollTo().performClick()
        compose.onNodeWithText("Usar la caja vinculada para nuevas ventas").performScrollTo().performClick()
        compose.onNodeWithText("Imprimir resumen provisional").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, reviews); assertEquals(1, adopts) }
    }

    @Test fun confirmedIgnoredCountRemainsAnExplicitWarningWithoutOpeningDetails() {
        val ignored = row.copy(localStatus = "CLOSED", closeOperationId = "closeA", closeStatus = "CONFIRMED",
            countedCash = "72.00", cashReconciliationAction = "COUNTED",
            closeEvidenceJson = "{\"operation\":{\"countApplied\":false}}")
        compose.setContent { AvoqadoTheme {
            LocalCajaContent(ui(ignored), false, true, true, true, {}, {}, {}, {}, { _, _ -> }, {}, null)
        } }
        compose.onNodeWithText("Caja cerrada").assertIsDisplayed()
        compose.onNodeWithText("El conteo no se aplicó al cierre.").assertIsDisplayed()
        compose.onNodeWithText("Conteo capturado: $72.00").assertIsDisplayed()
        compose.onNodeWithText("Ver detalles").assertIsDisplayed()
    }

    @Test fun unknownTotalsNeverBecomeZerosAndPermissionsStillDisableClosing() {
        compose.setContent { AvoqadoTheme {
            LocalCajaContent(ui(row.copy(startingCash = null)), true, false, true, false, {}, {}, {}, {},
                { _, _ -> }, {}, null, financialShift = financialShift.copy(financialTotalsKnown = false))
        } }
        compose.onNodeWithText("Fondo no disponible").assertIsDisplayed()
        compose.onNodeWithText("Cerrar caja").assertIsNotEnabled()
        compose.onNodeWithText("Ver detalles").performClick()
        compose.onNodeWithText("Ventas, efectivo esperado y diferencia: no disponibles aquí").assertIsDisplayed()
        compose.onNodeWithText("Ventas: $0.00").assertDoesNotExist()
        compose.onNodeWithText("Ventas: $27.50").assertDoesNotExist()
    }

    @Test fun changingCajaResetsDetailsWithoutOpeningOrClosingAnything() {
        val current = mutableStateOf(row)
        var actions = 0
        compose.setContent { AvoqadoTheme {
            LocalCajaContent(ui(current.value), false, true, true, false, { actions++ }, { actions++ },
                { actions++ }, { actions++ }, { _, _ -> actions++ }, { actions++ }, null)
        } }
        compose.onNodeWithText("Ver detalles").performClick()
        compose.onNodeWithText("Ocultar detalles").assertIsDisplayed()
        compose.runOnIdle { current.value = row.copy(localSessionId = "c000000000000000000000002", startingCash = "90.00") }
        compose.onNodeWithText("Ver detalles").assertIsDisplayed()
        compose.onNodeWithText("Imprimir resumen provisional").assertDoesNotExist()
        compose.onNodeWithText("Fondo capturado: $90.00").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, actions) }
    }
}

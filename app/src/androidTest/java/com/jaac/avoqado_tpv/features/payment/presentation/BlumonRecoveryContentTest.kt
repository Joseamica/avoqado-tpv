package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.jaac.avoqado_tpv.features.payment.data.ledger.BlumonAttemptResolver
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Runs on the PAX display with a fixture; never starts the card SDK or writes to its financial database.
 * F1 mínimo, tarea 5 (17-nov): el cajero sólo ve el total del intento y [Seguir]; ninguna decisión.
 */
class BlumonRecoveryContentTest {
    @get:Rule val compose = createComposeRule()
    private val row = PaymentAttemptEntity(
        attemptId = "ui-fixture", venueId = "ui-fixture", processor = "BLUMON", state = "INDETERMINADO",
        amountCents = 10000, tipCents = 500, recordingRoute = "FAST", paymentContextJson = "{}", createdAt = 1, updatedAt = 2,
    )

    @Test fun theDoubtShowsTheOriginalTotalWithTipAndOnlySeguir() {
        compose.setContent {
            MaterialTheme {
                BlumonRecoveryContent(BlumonAttemptResolver.Result(BlumonAttemptResolver.State.PENDING, row.copy(serverAnsweredAt = 3), true),
                    busy = false, secondsRemaining = 0, onSeguir = {})
            }
        }
        compose.onNodeWithText("Tarjeta $105.00 por confirmar. No la repitas. Avisa al gerente.").assertIsDisplayed()
        compose.onNodeWithText("Seguir").performScrollTo().assertIsDisplayed()
        for (fuera in listOf("Ya revisé la terminal: no se cobró", "El cliente no presentó tarjeta", "Consultar de nuevo", "Regresar"))
            compose.onNodeWithText(fuera).assertDoesNotExist()
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "pax-recovery-f1.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun seguirWorksDuringASlowConsultationAndLeavesOnlyOnce() {
        var salidas = 0
        compose.setContent {
            MaterialTheme {
                BlumonRecoveryContent(BlumonAttemptResolver.Result(BlumonAttemptResolver.State.PENDING, row, true),
                    busy = true, secondsRemaining = 5, onSeguir = { salidas++ })
            }
        }
        compose.onNodeWithText("Confirmando con el banco… No pases la tarjeta otra vez.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Seguir").performScrollTo().performClick()
        compose.onNodeWithText("Seguir").performClick()
        assertEquals(1, salidas)
    }

    @Test fun aLateApprovalChangesTheTextButKeepsTheSameExit() {
        val result = mutableStateOf(BlumonAttemptResolver.Result(BlumonAttemptResolver.State.PENDING, row, true))
        compose.setContent {
            MaterialTheme { BlumonRecoveryContent(result.value, busy = false, secondsRemaining = 0, onSeguir = {}) }
        }
        compose.runOnIdle { result.value = result.value.copy(state = BlumonAttemptResolver.State.MONEY_EVIDENCE, row = row.copy(serverProcessorEvidence = "APPROVED")) }
        compose.onNodeWithText("Hay evidencia de cobro").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Seguir").performScrollTo().assertIsDisplayed()
    }
}

package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.jaac.avoqado_tpv.features.payment.data.ledger.BlumonAttemptResolver
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Runs on the PAX display with a fixture; never starts the card SDK or writes to its financial database. */
class BlumonRecoveryContentTest {
    @get:Rule val compose = createComposeRule()
    private val row = PaymentAttemptEntity(
        attemptId = "ui-fixture", venueId = "ui-fixture", processor = "BLUMON", state = "INDETERMINADO",
        amountCents = 10000, tipCents = 500, recordingRoute = "FAST", paymentContextJson = "{}", createdAt = 1, updatedAt = 2,
    )

    @Test fun noDeclarationDuringTheConfirmationWindow() {
        compose.setContent {
            MaterialTheme {
                BlumonRecoveryContent(BlumonAttemptResolver.Result(BlumonAttemptResolver.State.PENDING, row, true),
                    busy = false, secondsRemaining = 5, hasPermission = true, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Esperando confirmación: 5 s").assertIsDisplayed()
        compose.onNodeWithText("El cliente no presentó tarjeta").assertDoesNotExist()
        compose.onNodeWithText("Ya revisé la terminal: no se cobró").assertDoesNotExist()
    }

    @Test fun cashierChecksTheTerminalWithOrWithoutNetworkAndItsButtonWorks() {
        var declarations = 0
        compose.setContent {
            MaterialTheme {
                BlumonRecoveryContent(BlumonAttemptResolver.Result(BlumonAttemptResolver.State.PENDING, row.copy(serverAnsweredAt = 3), true),
                    busy = false, secondsRemaining = 0, hasPermission = true, {}, {}, { declarations++ }, {}, hasCheckPermission = true)
            }
        }
        compose.onNodeWithText("El cliente no presentó tarjeta").assertDoesNotExist()
        compose.onNodeWithText("Ya revisé la terminal: no se cobró").performScrollTo().performClick()
        assertEquals(1, declarations)
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "pax-recovery-offline.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun missingPermissionExplainsWhoCanResolveTheAttempt() {
        compose.setContent {
            MaterialTheme {
                BlumonRecoveryContent(BlumonAttemptResolver.Result(BlumonAttemptResolver.State.PENDING, row, true),
                    busy = false, secondsRemaining = 0, hasPermission = false, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("El cliente no presentó tarjeta").assertDoesNotExist()
        compose.onNodeWithText("Ya revisé la terminal: no se cobró").assertDoesNotExist()
        compose.onNodeWithText("Para dejar constancia de que no se cobró se necesita el permiso de conciliar cobros; pídeselo a tu gerente.")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun aPosRequestKeepsItsOwnManagerDeclaration() {
        compose.setContent {
            MaterialTheme {
                BlumonRecoveryContent(BlumonAttemptResolver.Result(BlumonAttemptResolver.State.PENDING, row.copy(terminalPaymentRequestId = "req"), true),
                    busy = false, secondsRemaining = 0, hasPermission = true, {}, {}, {}, {}, hasCheckPermission = true)
            }
        }
        compose.onNodeWithText("El cliente no presentó tarjeta").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ya revisé la terminal: no se cobró").assertDoesNotExist()
    }

    @Test fun blumonsAnswerIsShownWithoutClaimingTheOutcome() {
        val conRespuesta = row.copy(lastError = "Blumon sin veredicto: MomentumFailure · NO AUTORIZADO")
        compose.setContent {
            MaterialTheme {
                BlumonRecoveryContent(BlumonAttemptResolver.Result(BlumonAttemptResolver.State.PENDING, conRespuesta, true),
                    busy = false, secondsRemaining = 5, hasPermission = true, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Blumon contestó «NO AUTORIZADO», pero sin un código del banco no se puede confirmar si hubo cargo.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Estamos confirmando el cobro").assertIsDisplayed()
    }

    @Test fun aLateApprovalRemovesTheDeclarationWithoutReopeningTheScreen() {
        val result = mutableStateOf(BlumonAttemptResolver.Result(BlumonAttemptResolver.State.PENDING, row, true))
        compose.setContent {
            MaterialTheme { BlumonRecoveryContent(result.value, false, 0, true, {}, {}, {}, {}, hasCheckPermission = true) }
        }
        compose.onNodeWithText("Ya revisé la terminal: no se cobró").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { result.value = result.value.copy(state = BlumonAttemptResolver.State.MONEY_EVIDENCE, row = row.copy(serverProcessorEvidence = "APPROVED")) }
        compose.onNodeWithText("Ya revisé la terminal: no se cobró").assertDoesNotExist()
        compose.onNodeWithText("Hay evidencia de cobro").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Regresar").performScrollTo().assertIsDisplayed()
    }
}

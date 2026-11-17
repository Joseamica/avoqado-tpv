package com.jaac.avoqado_tpv.features.payment.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 🔴 D13 (encargo ff3278): el banco aprobó y la libreta no pudo escribirlo. La pantalla de la PAX ya no dice «Aprobado» a secas.
 *
 * Es de FUENTE, como [CobroSinSesionNoSeDescartaTest]: las pruebas de comportamiento sólo ejercitan la variante SANDBOX y la de
 * PRODUCCIÓN es la que mueve dinero real. Esto lee las dos.
 */
class AprobacionSinGuardarFuenteTest {

    private val variantes = listOf(
        "sandbox" to File("src/sandbox/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentViewModel.kt"),
        "production" to File("src/production/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentViewModel.kt"),
    )

    private fun fuente(archivo: File): String = archivo.readText()

    private fun entre(fuente: String, desde: String, hasta: String, nombre: String): String {
        val inicio = fuente.indexOf(desde)
        assertTrue("[$nombre] no se encontró «$desde»", inicio >= 0)
        val fin = fuente.indexOf(hasta, inicio + desde.length)
        assertTrue("[$nombre] no se encontró «$hasta» después de «$desde»", fin > inicio)
        return fuente.substring(inicio, fin)
    }

    @Test
    fun `P1 tras AUTORIZADO la pantalla pregunta si la aprobacion quedo guardada y si no, avisa en ambar`() {
        for ((nombre, archivo) in variantes) {
            val funcion = entre(archivo.readText(), "private fun handlePaymentSuccess(", "private fun extractCardDetailsFromBlumonResponse(", nombre)
            val tramo = entre(funcion, "// 📒 [Libreta] Money moved", "recordPaymentUseCase(", nombre)
            val autorizado = tramo.indexOf("paymentAttemptLedger.markAuthorized(")
            val pregunta = tramo.indexOf("paymentAttemptLedger.aprobacionPendienteDeGuardar(")
            assertTrue("[$nombre] no pregunta a la libreta si la aprobación quedó guardada", pregunta >= 0)
            assertTrue("[$nombre] pregunta ANTES de AUTORIZADO (que también la sella)", autorizado in 0 until pregunta)
            assertTrue("[$nombre] no avisa en ámbar", tramo.contains("buildApprovalNotSavedState("))
        }
    }

    @Test
    fun `P1 sin sesion y sin la aprobacion guardada no dice que quedo guardada en el equipo`() {
        for ((nombre, archivo) in variantes) {
            val funcion = entre(archivo.readText(), "private fun handlePaymentSuccess(", "private fun extractCardDetailsFromBlumonResponse(", nombre)
            val rama = entre(funcion, "if (!hasAuth || !hasStaffId || !hasVenueId) {", "return@launch", nombre)
            assertTrue("[$nombre] la rama sin sesión no mira si la aprobación quedó guardada",
                rama.contains("paymentAttemptLedger::aprobacionPendienteDeGuardar") && rama.contains("buildRecordingLostState("))
        }
    }

    @Test
    fun `P2 el aviso es el mismo en las dos variantes`() {
        val textos = variantes.map { (nombre, archivo) ->
            entre(archivo.readText(), "internal fun buildApprovalNotSavedState(", "    }\n", nombre)
        }
        assertEquals("production y sandbox divergen en el aviso de D13", textos[0], textos[1])
    }

    @Test
    fun `P1 Codex r2-3 el primer Aprobado ya sale con el aviso - chip y contactless`() {
        for ((nombre, archivo) in variantes) {
            val veces = archivo.readText().split("_state.value = exitoConAvisoDeLaLibreta(PaymentState.Success(").size - 1
            assertEquals("[$nombre] el éxito del chip y el del contactless no pasan por la libreta", 2, veces)
            // Codex r3 #4: que la función de verdad pregunte a la libreta y ponga el aviso (no sólo que se llame).
            val cuerpo = entre(fuente(archivo), "private fun exitoConAvisoDeLaLibreta(", "\n\n", nombre)
            assertTrue("[$nombre] exitoConAvisoDeLaLibreta no pregunta a la libreta",
                cuerpo.contains("paymentAttemptLedger::aprobacionPendienteDeGuardar"))
            assertTrue("[$nombre] exitoConAvisoDeLaLibreta no pone el aviso", cuerpo.contains("buildApprovalNotSavedState(exito"))
            // Y el vigilante quita el aviso si la aprobación se guarda mientras se registra (Codex r3 #2).
            val tramo = entre(fuente(archivo), "// 📒 [Libreta] Money moved", "recordPaymentUseCase(", nombre)
            assertTrue("[$nombre] sin vigilante del aviso", tramo.contains("vigilarAprobacionSinGuardar(attemptIdForLedger)"))
            val vigilante = entre(fuente(archivo), "private fun vigilarAprobacionSinGuardar(", "\n    }\n", nombre)
            assertTrue("[$nombre] el vigilante no lee la fila (un veredicto del servidor no la soltaría)",
                vigilante.contains("paymentAttemptLedger.revisarAprobacionPendiente(attemptId)"))
            assertTrue("[$nombre] AUTORIZADO no limpia el aviso", tramo.contains("clearApprovalNotSavedState(_state.value)"))
        }
    }

    @Test
    fun `P2 Codex r2-4 la prueba de venta no confunde el aviso de D13 con una venta en cola`() {
        val pantalla = File("src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentScreen.kt").readText()
        assertTrue("la pantalla manda a «Pendientes» con el aviso de D13",
            pantalla.contains("currentState.pendingSyncMessage != null && !currentState.aprobacionSinGuardar -> PROOF_SALE_QUEUED_MESSAGE"))
    }

    @Test
    fun `P2 al cerrar No se pudo imprimir no revive el aviso si la aprobacion ya quedo a salvo`() {
        for ((nombre, archivo) in variantes) {
            val cuerpo = entre(fuente(archivo), "fun dismissPrintError() {", "\n    }\n", nombre)
            assertTrue("[$nombre] «Cerrar» restaura la copia con el aviso sin preguntar a la libreta",
                cuerpo.contains("paymentAttemptLedger::aprobacionPendienteDeGuardar") && cuerpo.contains("clearApprovalNotSavedState(restaurado)"))
        }
    }
}

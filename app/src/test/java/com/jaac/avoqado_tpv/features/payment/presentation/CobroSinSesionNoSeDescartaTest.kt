package com.jaac.avoqado_tpv.features.payment.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 🔴 D3 (encargo 9308a7, auditoría sin red del 15-nov, fila 21): un cobro que Blumon TPV aprobó y cuya sesión se cerró
 * antes de registrarlo se descartaba con un `return` mudo (`TODO: Queue payment for offline sync`). La pantalla seguía en
 * éxito, la libreta se quedaba en HOST_RESPONDIO sin aviso a la recuperación y la venta aparecía horas después (o nunca,
 * tras gastar sus 5 intentos con 401).
 *
 * Es de FUENTE, como [NadaEsOfflineEnBlumonTest]: las pruebas de comportamiento sólo ejercitan la variante SANDBOX y la de
 * PRODUCCIÓN es la que mueve dinero real. Esto lee las dos.
 */
class CobroSinSesionNoSeDescartaTest {

    private val variantes = listOf(
        "sandbox" to File("src/sandbox/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentViewModel.kt"),
        "production" to File("src/production/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentViewModel.kt"),
    )

    private fun entre(fuente: String, desde: String, hasta: String, nombre: String): String {
        val inicio = fuente.indexOf(desde)
        assertTrue("[$nombre] no se encontró «$desde»", inicio >= 0)
        val fin = fuente.indexOf(hasta, inicio + desde.length)
        assertTrue("[$nombre] no se encontró «$hasta» después de «$desde»", fin > inicio)
        return fuente.substring(inicio, fin)
    }

    @Test
    fun `sin sesion el cobro aprobado queda como registro pendiente en la libreta en las dos variantes`() {
        for ((nombre, archivo) in variantes) {
            val funcion = entre(archivo.readText(), "private fun handlePaymentSuccess(", "private fun extractCardDetailsFromBlumonResponse(", nombre)
            // La llave se toma ANTES de lanzar la corrutina: un cierre de sesión a media espera vacía la sesión.
            val antesDeLanzar = entre(funcion, "recordingInFlight = true", "viewModelScope.launch(", nombre)
            assertTrue("[$nombre] la llave del intento no se toma antes de lanzar", antesDeLanzar.contains("sessionSnapshot.paymentAttemptId"))

            val rama = entre(funcion, "if (!hasAuth || !hasStaffId || !hasVenueId) {", "return@launch", nombre)
            assertFalse("[$nombre] la rama sin sesión sigue siendo un TODO mudo", rama.contains("TODO"))
            assertTrue("[$nombre] la rama sin sesión no deja los datos de la tarjeta en la libreta", rama.contains("paymentAttemptLedger.markAuthorized("))
            // Codex r2: «el banco aprobó» se vuelve a escribir ANTES de AUTORIZADO, por si su primera escritura falló.
            val marca = rama.indexOf("paymentAttemptLedger.markHostResponded(")
            assertTrue("[$nombre] la rama sin sesión no asegura host_approved antes de AUTORIZADO",
                marca >= 0 && marca < rama.indexOf("paymentAttemptLedger.markAuthorized(") && rama.contains("approved = true"))
            // AUTORIZADO, no REGISTRO_FALLIDO: éste exige `host_approved = 1` para ser candidato, y esa escritura pudo fallar
            // (Codex r1 #3). AUTORIZADO lo toma la recuperación siempre.
            assertFalse("[$nombre] la rama sin sesión pasa a REGISTRO_FALLIDO (deja de ser recuperable sin host_approved)", rama.contains("markRecordFailed("))
            assertTrue("[$nombre] la rama sin sesión no le dice al cajero que NO vuelva a cobrar", rama.contains("buildPendingLoginState("))
            assertTrue("[$nombre] la rama sin sesión no pide la pasada de la recuperación por el carril que no se absorbe (Codex r3–r5)", rama.contains("LedgerSweepScheduler.runApprovalFollowUp(") && !rama.contains("runOnceNow("))
            // Nunca se registra con la sesión de quien entre DESPUÉS: el registro lo hace la recuperación con el contexto congelado.
            assertFalse("[$nombre] la rama sin sesión arma un contexto con la sesión viva", rama.contains("buildFastPaymentContext(") || rama.contains("buildOrderPaymentContext("))
            assertFalse("[$nombre] la rama sin sesión llama al registro sin token", rama.contains("recordPaymentUseCase("))

            // Los datos de la tarjeta se calculan antes de la guarda, para que esta rama los tenga.
            val tarjeta = funcion.indexOf("val cardDetails = ")
            val guarda = funcion.indexOf("if (!hasAuth || !hasStaffId || !hasVenueId) {")
            assertTrue("[$nombre] los datos de la tarjeta se calculan después de la guarda de sesión", tarjeta in 0 until guarda)
        }
    }
}

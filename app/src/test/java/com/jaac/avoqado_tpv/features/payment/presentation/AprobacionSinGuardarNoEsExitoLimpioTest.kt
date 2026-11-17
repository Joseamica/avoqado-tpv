package com.jaac.avoqado_tpv.features.payment.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 🔴 D13 (encargo 5ffa66): el «Aprobado» de la PAX se publicaba limpio aunque la libreta no hubiera podido escribir la
 * aprobación del banco (`markHostResponded` daba false por un fallo de disco y nadie lo miraba). Ahora los DOS éxitos que siguen
 * a la autorización en línea (chip y contactless) salen por [PaymentViewModel.exitoTrasAprobacion], que pregunta a la libreta.
 *
 * Es de FUENTE, como [CobroSinSesionNoSeDescartaTest]: las pruebas de comportamiento sólo ejercitan la variante SANDBOX y la de
 * PRODUCCIÓN es la que mueve dinero real. Esto lee las dos.
 */
class AprobacionSinGuardarNoEsExitoLimpioTest {

    private val variantes = listOf(
        "sandbox" to File("src/sandbox/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentViewModel.kt"),
        "production" to File("src/production/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentViewModel.kt"),
    )

    @Test
    fun `los dos exitos de la autorizacion en linea preguntan a la libreta antes de pintar Aprobado`() {
        for ((nombre, archivo) in variantes) {
            val fuente = archivo.readText()
            assertEquals("[$nombre] chip y contactless deben salir por exitoTrasAprobacion",
                2, Regex("""_state\.value = exitoTrasAprobacion\(saleData\.authorization, saleData\.reference\)""").findAll(fuente).count())
            assertFalse("[$nombre] queda un éxito de venta con tarjeta publicado sin preguntar a la libreta",
                Regex("""_state\.value = PaymentState\.Success\(\s*authCode = saleData\.authorization""").containsMatchIn(fuente))
            val inicio = fuente.indexOf("internal suspend fun exitoTrasAprobacion(")
            assertTrue("[$nombre] no existe exitoTrasAprobacion", inicio >= 0)
            val cuerpo = fuente.substring(inicio, fuente.indexOf("\n    }\n", inicio))
            assertTrue("[$nombre] exitoTrasAprobacion no pregunta a la libreta", cuerpo.contains("paymentAttemptLedger.aprobacionSigueSinGuardar("))
            assertTrue("[$nombre] exitoTrasAprobacion no pone el aviso", cuerpo.contains("pendingSyncMessage = mensajeAprobacionSinGuardar("))
        }
    }

    @Test
    fun `sin sesion y sin nada guardado no dice quedo guardado en este equipo`() {
        for ((nombre, archivo) in variantes) {
            val fuente = archivo.readText()
            val inicio = fuente.indexOf("if (!hasAuth || !hasStaffId || !hasVenueId) {")
            assertTrue("[$nombre] no se encontró la rama sin sesión", inicio >= 0)
            val rama = fuente.substring(inicio, fuente.indexOf("return@launch", inicio))
            val pregunta = rama.indexOf("paymentAttemptLedger.aprobacionSigueSinGuardar(")
            assertTrue("[$nombre] la rama sin sesión no pregunta si la aprobación quedó", pregunta >= 0)
            assertTrue("[$nombre] la rama sin sesión no da la alarma cuando nada quedó guardado",
                rama.indexOf("buildRecordingLostState(", pregunta) > pregunta)
        }
    }

    @Test
    fun `el registro que llega durante No se pudo imprimir quita el aviso del exito guardado`() {
        // Codex r1 #2: «Cerrar» restauraba el éxito viejo con su aviso aunque el servidor ya lo tenía.
        for ((nombre, archivo) in variantes) {
            val fuente = archivo.readText()
            val inicio = fuente.indexOf("private suspend fun applyRecordedReceiptToSuccessState(")
            assertTrue("[$nombre] no existe applyRecordedReceiptToSuccessState", inicio >= 0)
            val cuerpo = fuente.substring(inicio, fuente.indexOf("\n    }\n", inicio))
            val rama = cuerpo.indexOf("currentState is PaymentState.PrintError")
            assertTrue("[$nombre] el registro no pone al día el éxito guardado en «No se pudo imprimir»", rama >= 0)
            val resto = cuerpo.substring(rama)
            assertTrue("[$nombre] el éxito guardado conserva el aviso", resto.contains("previousState = currentState.previousState.copy(") &&
                resto.contains("pendingSyncMessage = null") && resto.contains("recordingLostMessage = null"))
        }
    }

    @Test
    fun `No se pudo imprimir guarda el exito vigente y no la copia de antes de esperar a la impresora`() {
        // Codex r2 #2: el registro podía llegar mientras se revisaba la impresora y la copia vieja volvía a pintar el aviso.
        for ((nombre, archivo) in variantes) {
            val fuente = archivo.readText()
            assertFalse("[$nombre] queda un PrintError con la copia capturada antes de esperar",
                Regex("""previousState = currentState\n""").containsMatchIn(fuente))
            assertEquals("[$nombre] los 7 PrintError usan el éxito vigente", 7,
                Regex("""previousState = exitoVigente\(currentState\)""").findAll(fuente).count())
        }
    }
}

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
}

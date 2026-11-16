package com.jaac.avoqado_tpv.features.payment.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 🔴 D5 (encargo 533d77, opción C): en la PAX el banco APROBÓ y la tarjeta rechazó el cierre (`CompleteEmvTrans`). Antes se
 * publicaba «Error finalizando transacción» con Reintentar (el colector lo cambiaba por «Cobrado. Sincronización pendiente») y
 * la recuperación lo registraba sola como cobrado. Ahora va a la duda que ya existe: la libreta en duda ANTES del fin del SDK,
 * sin Reintentar y con la pantalla de duda de la PAX.
 *
 * Es de FUENTE, como [CobroSinSesionNoSeDescartaTest]: las pruebas de comportamiento sólo ejercitan la variante SANDBOX y la
 * de PRODUCCIÓN es la que mueve dinero real. El flujo del chip llama al SDK nativo y no se puede manejar en una prueba.
 */
class ChipRechazaTrasAprobacionFuenteTest {

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
    fun `el chip que rechaza tras aprobar va a la duda en las dos variantes, sin Reintentar`() {
        for ((nombre, archivo) in variantes) {
            val fuente = archivo.readText()
            val tramo = entre(fuente, "val completeResult = completeEmvTransUseCase.run(completeParams)", "EMV completion SUCCESS!", nombre)
            val rama = entre(tramo, "if (completeResult.isLeft) {", "REGRESÓ con éxito", nombre)

            assertFalse("[$nombre] la rama sigue publicando un Error con Reintentar", rama.contains("PaymentState.Error("))
            val libreta = rama.indexOf("paymentAttemptLedger.markChipRechazoTrasAprobacion(")
            val finDelSdk = rama.indexOf("marcarFinDelSdk(preparationAttemptId)")
            assertTrue("[$nombre] la libreta no pasa el intento a la duda", libreta >= 0)
            assertTrue("[$nombre] el fin del SDK se marca ANTES de dejar la fila en duda", finDelSdk > libreta)
            // Codex r3: sin la duda escrita, el fin del SDK no se marca (la recuperación no puede reclamar la fila).
            assertTrue("[$nombre] el fin del SDK se marca aunque la duda no se haya escrito",
                rama.contains("if (desenlace != com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger.ChipRechazo.SIN_ESCRIBIR) {\n                            marcarFinDelSdk(preparationAttemptId)"))
            assertTrue("[$nombre] la pantalla no queda en la duda que ya existe", rama.contains("showUnresolvedAuthorization()"))
            // Codex r1 #2: también si la duda no se pudo escribir; sólo la evidencia del servidor conserva «Cobrado».
            val baja = rama.indexOf("authorizationApproved = false")
            assertTrue("[$nombre] la aprobación en memoria no se baja", baja >= 0)
            assertTrue("[$nombre] la aprobación sólo se conserva con evidencia del servidor",
                rama.contains("if (esVenta && desenlace != com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger.ChipRechazo.HAY_EVIDENCIA)"))
            assertTrue("[$nombre] la aprobación se baja ANTES de pintar la duda", baja < rama.indexOf("showUnresolvedAuthorization()"))
            assertTrue("[$nombre] una pantalla vieja puede pisar la de un cobro nuevo",
                rama.contains("if (sessionSnapshot.paymentAttemptId != preparationAttemptId) return@launch"))

            // El camino con éxito sigue marcando el fin del SDK (una sola vez por intento, en cada rama).
            val despues = tramo.substring(tramo.indexOf("REGRESÓ con éxito"))
            assertTrue("[$nombre] el cierre EMV con éxito ya no marca el fin del SDK", despues.contains("marcarFinDelSdk(preparationAttemptId)"))
        }
    }
}

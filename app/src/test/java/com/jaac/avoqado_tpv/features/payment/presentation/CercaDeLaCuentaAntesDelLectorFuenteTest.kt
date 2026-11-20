package com.jaac.avoqado_tpv.features.payment.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F1 · Codex código r1 #5 y #6, en las DOS variantes de la PAX. Es de FUENTE, como [ChipRechazaTrasAprobacionFuenteTest]: las pruebas
 * de comportamiento (`PaymentViewModelKernelDurabilityTest`) sólo ejercitan SANDBOX, y las de la variante de PRODUCCIÓN —la que mueve
 * dinero real— no compilan hoy (`BlumonSaleReferenceTest`).
 *
 * #5: la cerca de la cuenta se mira ANTES de encender el lector, y si una barrera rechaza después de leer, la reserva se suelta.
 * #6: un lector sin contacto que regresa SIN veredicto (duda guardada) lleva el ID del intento a la pantalla: nunca «Reintentar».
 */
class CercaDeLaCuentaAntesDelLectorFuenteTest {

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

    @Test fun `Codex r1 5 - la cerca de la cuenta se mira antes de encender el lector y suelta la reserva`() {
        for ((nombre, archivo) in variantes) {
            val fuente = archivo.readText()
            val tramo = entre(fuente, "La venta cambió mientras se preparaba", "stopCardPollingBeforeEmv(\"sale\")", nombre)
            val cerca = tramo.indexOf("paymentAttemptLedger.otraCercaDeLaTarjeta(it, preparationAttemptId)")
            assertTrue("[$nombre] la cerca de la cuenta no se mira antes del lector", cerca >= 0)
            // showPreparationError descarta la reserva (PREPARANDO → DESCARTADA) antes de pintar el motivo.
            assertTrue("[$nombre] la reserva no se suelta", tramo.indexOf("showPreparationError(preparationAttemptId", cerca) > cerca)
            assertTrue("[$nombre] no dice qué cuenta espera", tramo.contains("paymentAttemptLedger.motivoDeLaBarrera(getOrderIdForFlow(), preparationAttemptId)"))
        }
    }

    @Test fun `Codex r1 5 - si la barrera rechaza despues de leer, la reserva se suelta - lector y autorizacion`() {
        for ((nombre, archivo) in variantes) {
            val fuente = archivo.readText()
            val lector = entre(fuente, "no se pudo comprometer la entrada al kernel — no se inicia el cobro", "val ctlssParams", nombre)
            assertTrue("[$nombre] la barrera del lector no suelta la reserva", lector.contains("soltarLaReservaRechazada(intentoDelKernel)"))
            val autorizacion = entre(fuente, "!paymentAttemptLedger.markAuthorizing(durableAttemptId)", "authorizationUnresolved = true\n", nombre)
            assertTrue("[$nombre] la barrera de la autorización no suelta la reserva", autorizacion.contains("soltarLaReservaRechazada(durableAttemptId)"))
            val ayuda = entre(fuente, "private suspend fun soltarLaReservaRechazada(", "private suspend fun showPreparationError(", nombre)
            // Sólo PREPARANDO/KERNEL_ACTIVO → DESCARTADA: nunca un intento que ya pidió autorización.
            assertTrue("[$nombre] no suelta con el CAS del rechazo antes del banco", ayuda.contains("paymentAttemptLedger.markKernelRefused(attemptId, \"barrera_antes_del_banco\")"))
            assertFalse("[$nombre] no debe descartar desde AUTORIZANDO", ayuda.contains("STATE_AUTORIZANDO"))
        }
    }

    @Test fun `Codex r1 6 - las salidas sin veredicto del lector sin contacto llevan el intento a la pantalla de duda`() {
        for ((nombre, archivo) in variantes) {
            val fuente = archivo.readText()
            val contactless = entre(fuente, "private suspend fun processContactlessPayment(", "private suspend fun processContactlessOnlineAuthorization(", nombre)
            // El fallo del lector que no es rechazo, transResult nulo, su enum nulo y el resultado desconocido.
            assertEquals("[$nombre] cada salida sin veredicto pinta la duda", 4, Regex("""mostrarDudaDelLector\(intentoDelKernel""").findAll(contactless).count())
            assertFalse("[$nombre] queda una salida sin veredicto con «Reintentar»", contactless.contains("message = \"Resultado desconocido: \$transResultEnum\""))
            val ayuda = entre(fuente, "private fun mostrarDudaDelLector(", "private fun showUnresolvedAuthorization()", nombre)
            assertTrue("[$nombre] la duda no lleva el ID", ayuda.contains("unresolvedAttemptId = attemptId"))
            assertTrue("[$nombre] la duda ofrece Reintentar", ayuda.contains("canRetry = false"))
        }
    }
}

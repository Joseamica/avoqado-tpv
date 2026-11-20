package com.jaac.avoqado_tpv.features.payment.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F1 bloque 6 (I6, Codex código r3 #7), en las DOS variantes de la PAX. De FUENTE, como [CercaDeLaCuentaAntesDelLectorFuenteTest]: las
 * pruebas de la variante de PRODUCCIÓN no compilan hoy. La salida del `catch` del sin contacto se resuelve con el dueño fijado al
 * entrar (nunca la sesión mutable), ofrece «Reintentar» SÓLO si el lector de verdad se soltó, y si no, lleva el ID del intento a la
 * pantalla de la duda. Nunca acredita el regreso del SDK (la firma del bloque 2).
 */
class ReservaDelLectorFuenteTest {

    private val variantes = listOf(
        "sandbox" to File("src/sandbox/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentViewModel.kt"),
        "production" to File("src/production/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentViewModel.kt"),
    )

    @Test fun `callback_viejo_no_suelta_el_lector_de_B - el catch usa el duenio fijado al entrar`() {
        for ((nombre, archivo) in variantes) {
            val fuente = archivo.readText()
            val entrada = fuente.indexOf("val intentoDelLector = sessionSnapshot.paymentAttemptId")
            val inicio = fuente.indexOf("🌊 [CONTACTLESS] Starting contactless (NFC) payment flow")
            assertTrue("[$nombre] el dueño no se fija antes de tocar el SDK", entrada in 0 until inicio)
            assertTrue("[$nombre] el kernel no entra con el dueño", fuente.contains("val intentoDelKernel = intentoDelLector"))
            assertTrue("[$nombre] la autorización en línea no recibe el dueño",
                fuente.contains("private suspend fun processContactlessOnlineAuthorization(amount: String, intentoDelLector: String?)"))
            assertEquals("[$nombre] los dos llamados llevan el dueño", 2,
                Regex("""processContactlessOnlineAuthorization\(formatAmountDecimal\(totalAmount\), intentoDelKernel\)""").findAll(fuente).count())
            assertFalse("[$nombre] un catch todavía suelta con la sesión mutable",
                Regex("""sessionSnapshot\.paymentAttemptId\?\.let \{ id ->\s*withContext\(NonCancellable\) \{ paymentAttemptLedger\.markKernelRefused\(id, "excepcion_antes_del_banco"\)""")
                    .containsMatchIn(fuente))
        }
    }

    @Test fun `CTLS - Reintentar solo si el lector se solto, y si no la duda con el intento`() {
        for ((nombre, archivo) in variantes) {
            val fuente = archivo.readText()
            val salidas = Regex("""val soltado = e !is CancellationException && \(intentoDelLector == null \|\|""").findAll(fuente).toList()
            assertEquals("[$nombre] las dos salidas del sin contacto", 2, salidas.size)
            for (salida in salidas) {
                val tramo = fuente.substring(salida.range.first, fuente.indexOf("\n        }\n", salida.range.first))
                assertTrue("[$nombre] no suelta con el CAS antes del banco", tramo.contains("paymentAttemptLedger.markKernelRefused(intentoDelLector, \"excepcion_antes_del_banco\")"))
                val reintentar = tramo.indexOf("canRetry = true")
                assertTrue("[$nombre] «Reintentar» no depende de que se soltara", reintentar > tramo.indexOf("if (soltado)"))
                assertTrue("[$nombre] sin soltar no lleva el intento a la duda", tramo.contains("mostrarDudaDelLector(intentoDelLector"))
                assertFalse("[$nombre] el catch no puede acreditar el regreso del SDK", tramo.contains("marcarFinDelSdk") || tramo.contains("marcarSdkRegreso"))
            }
        }
    }
}

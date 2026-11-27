package com.jaac.avoqado_tpv.features.payment.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cobro simple · paso 2 (R5; rescate de F1 `bb527bfa`, sólo la salida del `catch`), en las DOS variantes de la PAX. De FUENTE: las pruebas
 * de la variante de PRODUCCIÓN no compilan hoy. Una excepción del sin contacto ANTES de ir al banco dejaba `KERNEL_ACTIVO` y el lector
 * contaba 150 s: la terminal rechazaba el siguiente cobro. La salida se resuelve con el dueño fijado al entrar (nunca la sesión mutable),
 * ofrece «Reintentar» SÓLO si el lector de verdad se soltó, y si no, lleva el ID del intento a la pantalla de la duda. Nunca acredita el
 * regreso del SDK.
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

    // D26 (Codex r1 #2) sigue: si lanzó la llamada MISMA al lector, nada prueba que terminó; el lector se suelta a su tope, como hoy.
    @Test fun `paso 2 - en el cobro sin contacto solo se suelta en el acto con el lector ya de regreso`() {
        for ((nombre, archivo) in variantes) {
            val fuente = archivo.readText()
            val cobro = fuente.substring(
                fuente.indexOf("    private suspend fun processContactlessPayment(amount: String) {"),
                fuente.indexOf("    private suspend fun processContactlessOnlineAuthorization("),
            )
            val llamada = cobro.indexOf("val ctlssResult = startCtlssTransUseCase.run(ctlssParams)")
            assertTrue("[$nombre] el regreso se marca justo después de la llamada",
                cobro.indexOf("elLectorRegreso = true", llamada) in llamada until cobro.indexOf("\n", llamada) + 60)
            assertTrue("[$nombre] la salida pide el regreso", cobro.contains("val puedeSoltar = e !is CancellationException && elLectorRegreso && lectorCerrado &&"))
        }
    }

    @Test fun `CTLS - se suelta solo con el lector cerrado y sin cancelar, Reintentar si se solto o como hoy, y la duda si ya paso al banco`() {
        for ((nombre, archivo) in variantes) {
            val fuente = archivo.readText()
            val salidas = Regex("""val lectorCerrado = runCatching \{ stopDetectCardUseCase\.runInfallible\(StopDetectCardParams\(\)\) \}\.isSuccess""")
                .findAll(fuente).toList()
            assertEquals("[$nombre] las dos salidas del sin contacto", 2, salidas.size)
            for (salida in salidas) {
                val tramo = fuente.substring(salida.range.first, fuente.indexOf("\n        }\n", salida.range.first))
                assertTrue("[$nombre] sin cancelación ni lector abierto",
                    tramo.contains("e !is CancellationException") && tramo.contains("lectorCerrado") &&
                        tramo.contains("currentCoroutineContext().isActive"))
                assertTrue("[$nombre] no suelta con el CAS antes del banco",
                    tramo.contains("paymentAttemptLedger.markKernelRefused(intentoDelLector, \"excepcion_antes_del_banco\")"))
                val condicion = tramo.indexOf("if (soltado || !puedeSoltar) {")
                assertTrue("[$nombre] «Reintentar» depende de soltar (o de no poder, como hoy)", condicion >= 0 && tramo.indexOf("canRetry = true") > condicion)
                assertTrue("[$nombre] lo que pasó al banco lleva el intento a la duda", tramo.contains("mostrarDudaDelLector(intentoDelLector"))
                assertFalse("[$nombre] el catch no puede acreditar el regreso del SDK", tramo.contains("marcarFinDelSdk") || tramo.contains("marcarSdkRegreso"))
                assertFalse("[$nombre] el cierre del lector no se traga su error", tramo.contains("catch (_: Exception) {}"))
            }
        }
    }
}

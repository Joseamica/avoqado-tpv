package com.jaac.avoqado_tpv.features.payment.presentation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * El rechazo contactless libera la terminal — en las DOS variantes.
 *
 * ## Por qué existe, y por qué es de FUENTE y no de comportamiento
 *
 * Testarudo, 2026-09-07: nueve toques denegados en tres ventas. La entrada al kernel se
 * compromete como `KERNEL_ACTIVO` ANTES de llamarlo, porque el contactless puede aprobar
 * OFFLINE sin pasar por la barrera online. Si la rama que recibe el rechazo no resuelve esa
 * fila, se queda en `KERNEL_ACTIVO` — estado que aparece en las DOS consultas que bloquean:
 * `findTerminalHold` («¿está apartada la terminal?») y `findUnresolvedCharge` («¿pudo haberse
 * movido dinero?»). El siguiente `openAttempt` devuelve -1 y **la caja no puede cobrar**.
 *
 * 🔴 La prueba de comportamiento (`PaymentViewModelKernelDurabilityTest`) sólo puede ejercitar
 * la variante SANDBOX: los tests unitarios corren contra ese flavor. **Producción es la que
 * mueve dinero real** y ninguna prueba la toca. Este guardián lee el fuente de las dos, que es
 * lo único que las cubre a ambas — el mismo patrón que `NexgoFlavorHiltGraphTest`.
 */
class RechazoContactlessLiberaLaTerminalTest {

    private val variantes = listOf(
        "sandbox" to File("src/sandbox/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentViewModel.kt"),
        "production" to File("src/production/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentViewModel.kt"),
    )

    /**
     * 🔴 Hay DOS ramas `RESULT_OFFLINE_DENIED` por variante y significan cosas OPUESTAS:
     *
     *  · **Cobro** (`[CONTACTLESS PHASE 3]`): el chip rechazó la tarjeta. El flujo TERMINA, así
     *    que la fila tiene que soltarse o la caja queda trabada.
     *  · **Reembolso** (`[CONTACTLESS REFUND]`): para un reembolso, «denegado offline» significa
     *    «requiero autorización ONLINE» — los bancos no permiten reembolsos offline. El flujo
     *    SIGUE (`processContactlessRefundOnlineAuth`) y la fila DEBE seguir retenida. Soltarla
     *    ahí sería el defecto CONTRARIO: afirmar que no se movió dinero mientras la operación
     *    sigue viva.
     *
     * Por eso se ancla en la marca del log, no en el primer `RESULT_OFFLINE_DENIED` que aparezca.
     */
    private fun bloqueTrasLaMarca(fuente: String, marca: String, nombre: String): String {
        val marcado = fuente.indexOf(marca)
        assertTrue("[$nombre] no se encontró la marca «$marca»", marcado >= 0)
        val inicio = fuente.lastIndexOf("RESULT_OFFLINE_DENIED ->", marcado)
        assertTrue("[$nombre] no se encontró el inicio de la rama", inicio >= 0)
        val fin = fuente.indexOf("else -> {", marcado)
        assertTrue("[$nombre] no se encontró el final de la rama", fin > inicio)
        return fuente.substring(inicio, fin)
    }

    /** El texto de la rama `else ->` que sigue al COBRO: el resultado DESCONOCIDO. */
    private fun ramaDesconocida(fuente: String): String {
        val marcado = fuente.indexOf("[CONTACTLESS PHASE 3] RESULT_OFFLINE_DENIED")
        val inicio = fuente.indexOf("else -> {", marcado)
        return fuente.substring(inicio, minOf(inicio + 900, fuente.length))
    }

    @Test
    fun `un rechazo offline explicito resuelve la fila en las dos variantes`() {
        for ((nombre, archivo) in variantes) {
            assertTrue("no existe $archivo", archivo.exists())
            val rama = bloqueTrasLaMarca(archivo.readText(), "[CONTACTLESS PHASE 3] RESULT_OFFLINE_DENIED", nombre)
            assertTrue(
                "[$nombre] la rama RESULT_OFFLINE_DENIED no resuelve la fila de la libreta. " +
                    "Sin `markKernelRefused` se queda en KERNEL_ACTIVO y la terminal no vuelve a " +
                    "cobrar hasta el barrido — Testarudo 2026-09-07, nueve rechazos en tres ventas.",
                rama.contains("markKernelRefused"),
            )
        }
    }

    @Test
    fun `un resultado desconocido NO libera la terminal en ninguna variante`() {
        for ((nombre, archivo) in variantes) {
            val rama = ramaDesconocida(archivo.readText())
            assertTrue(
                "[$nombre] no se reconoció la rama del resultado desconocido",
                rama.contains("Resultado desconocido"),
            )
            assertTrue(
                "[$nombre] la rama del resultado DESCONOCIDO libera la terminal. Un timeout o un " +
                    "resultado que no se entiende pueden esconder una transacción que sí avanzó: " +
                    "liberar sobre una duda es exactamente lo que produce un doble cobro.",
                !rama.contains("markKernelRefused"),
            )
        }
    }

    /**
     * El defecto CONTRARIO: soltar la fila en el reembolso, donde «denegado offline» sólo
     * significa «hay que ir online» y la operación sigue viva.
     */
    @Test
    fun `el reembolso NO suelta la fila cuando el kernel pide autorizacion online`() {
        for ((nombre, archivo) in variantes) {
            val rama = bloqueTrasLaMarca(archivo.readText(), "[CONTACTLESS REFUND] RESULT_OFFLINE_DENIED", nombre)
            assertTrue(
                "[$nombre] el reembolso suelta la fila en OFFLINE_DENIED. Ahí el flujo NO termina: " +
                    "sigue por `processContactlessRefundOnlineAuth`, así que soltar la fila afirmaría " +
                    "que no se movió dinero mientras la operación está viva.",
                !rama.contains("markKernelRefused"),
            )
            assertTrue(
                "[$nombre] el reembolso ya no continúa online tras OFFLINE_DENIED",
                rama.contains("processContactlessRefundOnlineAuth"),
            )
        }
    }
}

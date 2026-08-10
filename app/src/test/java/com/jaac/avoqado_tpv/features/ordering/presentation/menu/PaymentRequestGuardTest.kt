package com.jaac.avoqado_tpv.features.ordering.presentation.menu

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Candado de reentrada del botón `Pagar` de la pantalla de Órdenes / Pedido rápido.
 *
 * ORIGEN — bug real (Mindform, ORD-1784305133392, 17 jul 2026):
 * el cajero picó `Pagar` varias veces porque el cobro se sentía congelado (12 s sin
 * respuesta visible). Medido en hardware el 2026-07-29 sobre una PAX A910S, ANTES del
 * arreglo: 5 toques produjeron 5 invocaciones completas de `prepareForPayment` en 1.3 s,
 * todas sobre la misma orden. Cada invocación lanza su propia pasada de descuento de
 * inventario; las pasadas se pisaron y Postgres las abortó => venta cancelada.
 *
 * De contraste, el botón "Cobrar" del carrito (que SÍ tiene candado) medía 10 toques = 1
 * acción en la misma terminal. Este test cierra esa brecha.
 *
 * 🔴 LA INVARIANTE QUE PROTEGE, y que NO se debe relajar: el candado tiene VENTANA. Un
 * candado permanente puede dejar un local sin poder cobrar si la bandera se queda pegada,
 * y eso es peor que el bug que arregla. Regla del proyecto: degradar, nunca bloquear.
 */
class PaymentRequestGuardTest {

    private val VENTANA = 15_000L

    // ─── Lo que debe IGNORAR ────────────────────────────────────────────────

    @Test
    fun `ignora un segundo toque inmediato mientras hay preparacion en vuelo`() {
        val ignora = shouldIgnoreDuplicatePaymentRequest(
            isPreparingPayment = true,
            nowMs = 1_000_000L,
            startedAtMs = 1_000_000L,
        )
        assertThat(ignora).isTrue()
    }

    @Test
    fun `ignora los 5 toques del caso Mindform (1punto3 segundos)`() {
        // Los toques reales medidos: +0ms, +123ms, +207ms, +397ms, +1276ms.
        val arranque = 5_000_000L
        val toques = listOf(123L, 207L, 397L, 1_276L)

        for (delta in toques) {
            val ignora = shouldIgnoreDuplicatePaymentRequest(
                isPreparingPayment = true,
                nowMs = arranque + delta,
                startedAtMs = arranque,
            )
            assertThat(ignora).isTrue()
        }
    }

    @Test
    fun `ignora incluso una preparacion lenta, como la de 12 segundos de Mindform`() {
        val ignora = shouldIgnoreDuplicatePaymentRequest(
            isPreparingPayment = true,
            nowMs = 12_000L,
            startedAtMs = 0L,
        )
        assertThat(ignora).isTrue()
    }

    // ─── 🔴 Lo que debe PERMITIR (que no se quede sin cobrar) ───────────────

    @Test
    fun `permite cobrar cuando no hay nada en vuelo`() {
        val ignora = shouldIgnoreDuplicatePaymentRequest(
            isPreparingPayment = false,
            nowMs = 9_999_999L,
            startedAtMs = 9_999_998L, // recién, pero ya liberado
        )
        assertThat(ignora).isFalse()
    }

    @Test
    fun `permite reintentar despues de un error, porque la bandera se libera`() {
        // Las tres salidas de onPaymentRequested (exito, fallo, excepcion) ponen la
        // bandera en false, así que un reintento legítimo del cajero SIEMPRE pasa.
        val ignora = shouldIgnoreDuplicatePaymentRequest(
            isPreparingPayment = false,
            nowMs = 1_500L,
            startedAtMs = 1_000L,
        )
        assertThat(ignora).isFalse()
    }

    @Test
    fun `se auto-cura si la bandera se queda pegada mas alla de la ventana`() {
        // Escenario catastrófico: la bandera quedó en true por un camino no previsto.
        // El cobro NO puede quedar bloqueado para siempre.
        val ignora = shouldIgnoreDuplicatePaymentRequest(
            isPreparingPayment = true,
            nowMs = VENTANA + 1,
            startedAtMs = 0L,
        )
        assertThat(ignora).isFalse()
    }

    @Test
    fun `en el borde exacto de la ventana ya permite cobrar`() {
        val ignora = shouldIgnoreDuplicatePaymentRequest(
            isPreparingPayment = true,
            nowMs = VENTANA,
            startedAtMs = 0L,
        )
        assertThat(ignora).isFalse()
    }

    @Test
    fun `un delta negativo NUNCA bloquea el cobro`() {
        // El ViewModel usa SystemClock.elapsedRealtime() (monotónico), así que esto no
        // debería ocurrir. Pero si por cualquier razón el arranque quedara "en el futuro",
        // el candado tiene que abrirse, no cerrarse: jamás dejar un local sin cobrar.
        val ignora = shouldIgnoreDuplicatePaymentRequest(
            isPreparingPayment = true,
            nowMs = 1_000L,
            startedAtMs = 50_000L,
        )
        assertThat(ignora).isFalse()
    }
}

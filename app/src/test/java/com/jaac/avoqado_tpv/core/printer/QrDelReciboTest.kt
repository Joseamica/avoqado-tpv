package com.jaac.avoqado_tpv.core.printer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Las dos reglas del QR del recibo.
 *
 * 🔴 Origen (Asana «POS - Reimpresion de Ticket sin QR de facturacion», 11-sep-2026): al cerrar el
 * hueco de la reimpresión salieron dos defectos hermanos que ya estaban en el ticket del COBRO:
 *
 *  1. **La URL vacía.** El cliente guarda `""` cuando el servidor responde sin recibo. Con la
 *     condición vieja (`!= null`) se entraba al bloque, ZXing fallaba con contenido vacío y el
 *     ticket salía con «Escanea para recibo y factura» y NINGÚN código debajo — peor que no decir
 *     nada, porque el cliente se queda buscando un QR que nunca se imprimió.
 *  2. **La promesa de factura.** La leyenda decía «y factura» siempre, también en negocios sin
 *     autofacturación: eso manda al cliente a un botón que no existe.
 */
class QrDelReciboTest {

    // ── P1: cuándo se intenta el QR ─────────────────────────────────────────

    @Test
    fun `con una liga de verdad se intenta`() {
        assertThat(QrDelRecibo.debeIntentarse("https://dashboardv2.avoqado.io/receipts/public/k")).isTrue()
    }

    @Test
    fun `P1 sin liga no se intenta`() {
        assertThat(QrDelRecibo.debeIntentarse(null)).isFalse()
    }

    @Test
    fun `P1 una liga VACIA no se intenta`() {
        // El caso que dejaba la leyenda huérfana en producción.
        assertThat(QrDelRecibo.debeIntentarse("")).isFalse()
    }

    @Test
    fun `P1 una liga de puros espacios tampoco`() {
        assertThat(QrDelRecibo.debeIntentarse("   ")).isFalse()
    }

    // ── P1: qué dice la leyenda ─────────────────────────────────────────────

    @Test
    fun `P1 con autofactura la leyenda la menciona`() {
        assertThat(QrDelRecibo.leyenda(true)).contains("y factura")
    }

    @Test
    fun `P1 sin autofactura la leyenda NO promete factura`() {
        assertThat(QrDelRecibo.leyenda(false)).doesNotContain("factura")
        assertThat(QrDelRecibo.leyenda(false)).contains("recibo digital")
    }

    @Test
    fun `la leyenda cabe en las 32 columnas del papel`() {
        // El papel de la PAX no perdona: una línea más larga se corta sola.
        listOf(QrDelRecibo.leyenda(true), QrDelRecibo.leyenda(false)).forEach { leyenda ->
            leyenda.trimEnd('\n').split("\n").forEach { linea ->
                assertThat(linea.length).isAtMost(32)
            }
        }
    }
}

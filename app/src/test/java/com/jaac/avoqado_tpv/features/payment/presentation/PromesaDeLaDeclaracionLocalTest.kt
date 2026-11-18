package com.jaac.avoqado_tpv.features.payment.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.payment.data.ledger.CobroDeclarable
import org.junit.Test

/**
 * D12 (16-nov, acta de #55 parte 2, P2-c1 y P2-c2): «Ya revisé la terminal: no se cobró» en un cobro LOCAL (Pago rápido, sin
 * solicitud del POS) sólo cambia la libreta de ESTA terminal (`declarado_sin_cobro:<staffId>`). Medido en la PAX: el servidor
 * no recibe nada (0 filas en `TerminalAttemptResolution`). La pantalla prometía «queda tu nombre y la hora» y «Quedó
 * registrado»: el texto ya no promete lo que no hace. Un cobro del POS sí llega al servidor y conserva su texto.
 *
 * Codex r1: los diagnósticos (Crashlytics) sí llevan la línea de la libreta, así que lo verdadero no es «no sale del aparato»
 * sino «no aparece en Avoqado» (ni el dueño ni el panel lo ven). Codex r1 y r2: el aviso de un cobro que aparezca después
 * caduca con la poda (`pruneTerminalOlderThan`) y se apaga si la terminal cambia de negocio o pierde la activación, y la
 * libreta puede guardar `declarado_sin_cobro:sin_identificar` si la sesión se cierra en medio: el texto local ya no promete
 * ni el aviso ni «quién», sólo lo que siempre es verdad.
 */
class PromesaDeLaDeclaracionLocalTest {

    private fun promesaHonesta(texto: String) {
        assertThat(texto).contains("en esta terminal")
        assertThat(texto).contains("no aparece")
        assertThat(texto).doesNotContain("queda tu nombre")
        assertThat(texto).doesNotContain("registrado")
        assertThat(texto).doesNotContain("no se envía")
        assertThat(texto).doesNotContain("avis")
        assertThat(texto).doesNotContain("quién")
    }

    // F1 (tarea 5): la PAX ya no invita al cajero a declarar; la firma es del gerente en «Por revisar».

    @Test fun `P1 tras declarar un cobro local que no llegó a Avoqado lo dice y no promete un aviso`() {
        promesaHonesta(textoDelIntentoCerrado("declarado_sin_cobro:staff-1"))
        promesaHonesta(textoDelIntentoCerrado("declarado_sin_cobro:sin_identificar"))
    }

    @Test fun `P1 tras declarar un cobro local que Avoqado guardó dice que quedó en Avoqado (D22)`() {
        val texto = textoDelIntentoCerrado("declarado_sin_cobro:staff-1:avoqado")
        assertThat(texto).contains("Quedó anotado en Avoqado")
        assertThat(texto).doesNotContain("no aparece")
        assertThat(texto).doesNotContain("sólo en esta terminal")
        assertThat(texto).doesNotContain("avis")
    }

    @Test fun `P2 una liberacion que dio el servidor conserva su texto`() {
        assertThat(textoDelIntentoCerrado(null)).isEqualTo("Quedó guardada la declaración de que no se cobró. Puedes volver a cobrar.")
    }

    // F1 (tarea 4 y Codex código r1 #10): lo que firma el gerente es SU marca, en el mismo paso del PIN; nunca «el banco dijo no».
    @Test fun `F1 el paso del gerente dice que el marca el cobro, por cuanto y a que hora - en los dos caminos`() {
        val zona = java.time.ZoneOffset.ofHours(-6)
        for (cobro in listOf(
            CobroDeclarable("a1", requestId = null, totalCentavos = 1000, desdeMillis = 0, serverAnsweredAt = 1),
            CobroDeclarable("a1", requestId = "req-1", totalCentavos = 1000, desdeMillis = 0, serverAnsweredAt = 1),
        )) {
            val texto = textoDeLaFirma(cobro, zona)
            assertThat(texto).contains("$10.00")
            assertThat(texto).contains("18:00")
            assertThat(texto).contains("Gerente: escribe tu PIN para marcar")
            assertThat(texto).contains("como no cobrado")
            assertThat(texto).doesNotContain("sólo en esta terminal")
            assertThat(texto).doesNotContain("no se cobró")
        }
    }

    /**
     * Codex r3: «Si el banco avisa que sí se cobró, se registra como cobrado» no se cumple. El aviso del banco no crea el pago:
     * el receptor de Blumon TPV sólo busca un `Payment` que ya exista y, si no, lo deja pendiente hasta marcarlo huérfano
     * (`blumon-webhook.service.ts`, `blumon-webhook-reconciliation.job.ts`); el de AngelPay tampoco lo crea.
     */
    @Test fun `P1 el aviso del Inicio en un cobro del POS ya no promete que el aviso del banco lo registra`() {
        val remoto = CobroDeclarable("a1", requestId = "req-1", totalCentavos = 1000, desdeMillis = 0, serverAnsweredAt = 1)
        assertThat(textoDeLaFirma(remoto, java.time.ZoneOffset.ofHours(-6))).doesNotContain("se registra como cobrado")
    }
}

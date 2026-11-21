package com.jaac.avoqado_tpv.features.payment.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.payment.data.ledger.FrenoDelResuelto
import com.jaac.avoqado_tpv.features.payment.data.ledger.LoQueFrenaElResuelto
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity
import org.junit.Test

/**
 * F1-textos (catálogo D5, filas 4 y 5 ⚙). Cuando la libreta frena la tarjeta, la frase depende de QUIÉN la frena: si la fila trae
 * `terminal_payment_request_id` es un cobro del POS (se suelta sola al techo, «5 min», o con el gerente); si no, es la terminal sola
 * (sólo el gerente). Se escoge leyendo esa fila, que ya está a la mano; sin fila va la frase que vale para los dos.
 */
class FrenoSegunElFlujoTest {

    private fun fila(requestId: String?) = PaymentAttemptEntity(
        attemptId = "a", venueId = "venue", processor = "ANGELPAY", state = "INDETERMINADO",
        amountCents = 12_000, tipCents = 0, recordingRoute = "ORDER", paymentContextJson = "{}",
        createdAt = 0L, updatedAt = 0L, terminalPaymentRequestId = requestId,
    )

    @Test fun `fila 4 historial pendiente - POS, terminal sola y sin fila`() {
        val freno = FrenoDelResuelto.HISTORIAL_PENDIENTE
        assertThat(freno.textoSegun(fila("req-1"))).isEqualTo("Confirmando con el banco… No pases la tarjeta otra vez. Se libera sola a los 5 min.")
        assertThat(freno.textoSegun(fila(null))).isEqualTo("Confirmando con el banco… No pases la tarjeta otra vez. Avísale al gerente.")
        assertThat(freno.textoSegun(fila(""))).isEqualTo("Confirmando con el banco… No pases la tarjeta otra vez. Avísale al gerente.")
        assertThat(freno.textoSegun(null)).isEqualTo(CONFIRMANDO_CON_EL_BANCO)
        assertThat(freno.texto).doesNotContain("AngelPay")
        assertThat(freno.texto).doesNotContain("soporte")
    }

    @Test fun `fila 5 otra tarjeta de la cuenta - POS, terminal sola y sin fila`() {
        val freno = FrenoDelResuelto.TARJETA_FRENADA
        assertThat(freno.textoSegun(fila("req-1")))
            .isEqualTo("Otra tarjeta de esta cuenta sigue por confirmar. Se libera sola a los 5 min o con el gerente.")
        assertThat(freno.textoSegun(fila(null))).isEqualTo("Esta cuenta tiene un cobro por confirmar. Avísale al gerente.")
        assertThat(freno.textoSegun(null)).isEqualTo("Esta cuenta tiene un cobro por confirmar. Avísale al gerente.")
        assertThat(freno.texto).doesNotContain("Vuelve a consultar")
    }

    @Test fun `los 5 min salen del techo de la libreta`() {
        val minutos = PaymentAttemptEntity.TECHO_DE_LA_DUDA_DEL_POS_MS / 60_000
        assertThat(FrenoDelResuelto.HISTORIAL_PENDIENTE.textoSegun(fila("req-1"))).contains("a los $minutos min")
        assertThat(FrenoDelResuelto.TARJETA_FRENADA.textoSegun(fila("req-1"))).contains("a los $minutos min")
    }

    @Test fun `el dinero no cambia por flujo`() {
        val dinero = FrenoDelResuelto.DINERO
        assertThat(dinero.textoSegun(fila("req-1"))).isEqualTo(dinero.texto)
        assertThat(dinero.textoSegun(fila(null))).isEqualTo(dinero.texto)
    }

    @Test fun `PAX - el intento cerrado con algo que frena dice la frase de su flujo`() {
        assertThat(textoDelIntentoCerrado(null, LoQueFrenaElResuelto(FrenoDelResuelto.TARJETA_FRENADA, fila("req-1"))))
            .isEqualTo("Otra tarjeta de esta cuenta sigue por confirmar. Se libera sola a los 5 min o con el gerente.")
        assertThat(textoDelIntentoCerrado(null, LoQueFrenaElResuelto(FrenoDelResuelto.TARJETA_FRENADA, fila(null))))
            .isEqualTo("Esta cuenta tiene un cobro por confirmar. Avísale al gerente.")
        // La libreta ilegible (sin fila) falla cerrado con la frase que vale para los dos.
        assertThat(textoDelIntentoCerrado(null, LoQueFrenaElResuelto(FrenoDelResuelto.TARJETA_FRENADA, null)))
            .isEqualTo("Esta cuenta tiene un cobro por confirmar. Avísale al gerente.")
    }
}

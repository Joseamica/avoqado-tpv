package com.jaac.avoqado_tpv.features.payment.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.payment.data.ledger.BlumonAttemptResolver
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity
import org.junit.Test

/**
 * Qué salida ofrece la pantalla de recuperación de la PAX, ya pasada la ventana (founder, 25-sep): en un cobro local
 * el cajero deja constancia de «revisé la terminal y no se cobró», con o sin red. La frase «el cliente no presentó
 * tarjeta» era falsa cuando sí la pasó; queda sólo para el cobro que mandó el POS, que tiene su propio camino.
 */
class SalidaDelCobroTest {
    private val fila = PaymentAttemptEntity(
        attemptId = "a", venueId = "v", processor = "BLUMON", state = "INDETERMINADO",
        amountCents = 1000, tipCents = 0, recordingRoute = "ORDER", paymentContextJson = "{}", createdAt = 1, updatedAt = 2,
    )

    private fun pendiente(f: PaymentAttemptEntity = fila, contesto: Boolean = false) =
        BlumonAttemptResolver.Result(BlumonAttemptResolver.State.PENDING, f, contesto)

    @Test fun `P1 un cobro local ofrece al cajero la salida honesta, con red`() {
        assertThat(salidaDelCobro(pendiente(contesto = true), puedeRevisar = true, puedeDeclararSinTarjeta = false))
            .isEqualTo(SalidaDelCobro.REVISE_LA_TERMINAL)
    }

    @Test fun `P1 y sin red, si Avoqado ya contesto alguna vez por ese cobro`() {
        assertThat(salidaDelCobro(pendiente(fila.copy(serverAnsweredAt = 3)), puedeRevisar = true, puedeDeclararSinTarjeta = false))
            .isEqualTo(SalidaDelCobro.REVISE_LA_TERMINAL)
    }

    @Test fun `P1 sin ninguna respuesta de Avoqado pide consultar primero`() {
        assertThat(salidaDelCobro(pendiente(), puedeRevisar = true, puedeDeclararSinTarjeta = true))
            .isEqualTo(SalidaDelCobro.FALTA_CONSULTAR)
    }

    @Test fun `P1 un cobro local ya no ofrece decir que no se presento tarjeta`() {
        assertThat(salidaDelCobro(pendiente(contesto = true), puedeRevisar = false, puedeDeclararSinTarjeta = true))
            .isEqualTo(SalidaDelCobro.FALTA_PERMISO_CAJERO)
    }

    @Test fun `P1 un cobro del POS conserva su propia declaracion de gerencia`() {
        val remoto = fila.copy(terminalPaymentRequestId = "req")
        assertThat(salidaDelCobro(pendiente(remoto, contesto = true), puedeRevisar = true, puedeDeclararSinTarjeta = true))
            .isEqualTo(SalidaDelCobro.NO_PRESENTO_TARJETA)
        assertThat(salidaDelCobro(pendiente(remoto, contesto = true), puedeRevisar = true, puedeDeclararSinTarjeta = false))
            .isEqualTo(SalidaDelCobro.FALTA_PERMISO_GERENCIA)
    }
}

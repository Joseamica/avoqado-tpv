package com.jaac.avoqado_tpv.features.payment.presentation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Hardware-free unit tests for the Blumon decline discriminator used by the DECLINE GUARD in
 * `performOnlineAuthorization`. An issuer decline arrives as a non-null SaleIcc response with a
 * BLANK authorization; a genuine approval always carries an authorization code (verified in prod:
 * 2850/2850 real Blumon approvals). These pin that behavior without needing a terminal.
 */
class BlumonDeclineDetectionTest {

    @Test
    fun `blank authorization yields decline message including the issuer reason`() {
        val msg = blumonDeclineMessage(
            authorization = "",
            description = "PAGO NO PERMITIDO EMISOR",
            isRefund = false,
        )
        assertThat(msg).isNotNull()
        assertThat(msg).contains("Pago rechazado")
        assertThat(msg).contains("PAGO NO PERMITIDO EMISOR")
    }

    @Test
    fun `null authorization is treated as a decline`() {
        assertThat(blumonDeclineMessage(null, "FONDOS INSUFICIENTES", isRefund = false))
            .contains("FONDOS INSUFICIENTES")
    }

    @Test
    fun `whitespace-only authorization is treated as a decline`() {
        assertThat(blumonDeclineMessage("   ", "TARJETA INVALIDA", isRefund = false))
            .contains("TARJETA INVALIDA")
    }

    @Test
    fun `non-blank authorization returns null so an approval proceeds untouched`() {
        assertThat(blumonDeclineMessage("766817", "APROBADA", isRefund = false)).isNull()
    }

    @Test
    fun `blank authorization with no description falls back to a generic reason`() {
        assertThat(blumonDeclineMessage("", null, isRefund = false))
            .contains("El banco no autorizó la transacción")
    }

    @Test
    fun `refund mode uses the refund prefix`() {
        assertThat(blumonDeclineMessage("", "X", isRefund = true)).contains("Reembolso rechazado")
    }
}

package com.jaac.avoqado_tpv.features.payment.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthorizationWatchdogTest {

    @Test
    fun `antes del umbral no hay aviso`() {
        assertEquals(AuthWatchdogLevel.NONE, authWatchdogLevel(0L))
        assertEquals(AuthWatchdogLevel.NONE, authWatchdogLevel(7_999L))
    }

    @Test
    fun `a los 8 segundos avisa que sigue procesando`() {
        assertEquals(AuthWatchdogLevel.SLOW, authWatchdogLevel(8_000L))
        assertEquals(AuthWatchdogLevel.SLOW, authWatchdogLevel(24_999L))
    }

    @Test
    fun `a los 25 segundos escala a aviso fuerte`() {
        assertEquals(AuthWatchdogLevel.VERY_SLOW, authWatchdogLevel(25_000L))
        assertEquals(AuthWatchdogLevel.VERY_SLOW, authWatchdogLevel(600_000L))
    }

    @Test
    fun `un elapsed negativo no rompe y no avisa`() {
        // Un reloj que retrocede (cambio de hora, NTP) no debe disparar avisos.
        assertEquals(AuthWatchdogLevel.NONE, authWatchdogLevel(-1L))
    }
}

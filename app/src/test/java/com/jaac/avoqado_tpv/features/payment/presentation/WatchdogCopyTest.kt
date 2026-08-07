package com.jaac.avoqado_tpv.features.payment.presentation

import com.jaac.avoqado_tpv.features.payment.domain.AuthWatchdogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchdogCopyTest {

    @Test
    fun `sin aviso no hay mensaje`() {
        assertNull(watchdogMessage(AuthWatchdogLevel.NONE))
    }

    @Test
    fun `el aviso lento pide no retirar la tarjeta`() {
        val msg = watchdogMessage(AuthWatchdogLevel.SLOW)!!
        assertTrue(msg.contains("no retires", ignoreCase = true))
    }

    @Test
    fun `el aviso fuerte instruye NO volver a cobrar`() {
        // Es la instruccion que evita el doble cobro: un cajero que ve un
        // spinner mudo vuelve a cobrar; uno que lee esto, espera.
        val msg = watchdogMessage(AuthWatchdogLevel.VERY_SLOW)!!
        assertTrue(msg.contains("no cobres de nuevo", ignoreCase = true))
    }

    @Test
    fun `ningun mensaje sugiere que el cobro fallo`() {
        // El cobro puede estar aprobandose en este momento. Decir "error"
        // aqui seria mentir sobre dinero en vuelo.
        listOf(AuthWatchdogLevel.SLOW, AuthWatchdogLevel.VERY_SLOW).forEach { level ->
            val msg = watchdogMessage(level)!!.lowercase()
            assertTrue(!msg.contains("error"))
            assertTrue(!msg.contains("fall"))
            assertTrue(!msg.contains("rechaz"))
        }
    }

    @Test
    fun `los niveles SLOW y VERY_SLOW producen mensajes distintos`() {
        // Regresion: si algun dia colapsan al mismo texto, el cajero pierde
        // la escalada que le dice "ya no reintentes" a los 25s.
        assertTrue(
            watchdogMessage(AuthWatchdogLevel.SLOW) != watchdogMessage(AuthWatchdogLevel.VERY_SLOW)
        )
    }
}

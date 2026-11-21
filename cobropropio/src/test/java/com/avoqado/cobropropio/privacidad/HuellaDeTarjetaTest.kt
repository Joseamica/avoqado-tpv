package com.avoqado.cobropropio.privacidad

import com.avoqado.cobropropio.fixtures.LecturasDePrueba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class HuellaDeTarjetaTest {

    private fun huella(llave: Byte) = HuellaHmac(SecretKeySpec(ByteArray(32) { llave }, "HmacSHA256"))

    @Test fun `huella estable y no es el pan`() {
        val pan = LecturasDePrueba.PAN_VISA
        val h = huella(7)
        val a = h.de(pan)
        assertEquals(a, h.de(pan))
        assertEquals(a, huella(7).de(pan))
        assertTrue(Regex("^[0-9A-F]{64}$").matches(a))
        assertNotEquals(a, h.de(LecturasDePrueba.PAN_MASTERCARD))
        assertNotEquals(a, huella(8).de(pan))
        assertFalse(Enmascarar.contieneDatoDeLaTarjeta(a, pan))
        assertEquals("HuellaHmac(oculta)", h.toString())
    }
}

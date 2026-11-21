package com.avoqado.cobropropio.privacidad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnmascararTest {

    @Test fun `pan 4111111111111111 da 411111****1111`() {
        assertEquals("411111****1111", Enmascarar.pan("4111111111111111"))
        assertEquals("506199****0016", Enmascarar.pan("5061990000000000016"))
        assertEquals("****", Enmascarar.pan("123456789"))
    }

    @Test fun `texto tapa rachas de 12 digitos`() {
        assertEquals("pan=**** ok", Enmascarar.texto("pan=555555555555 ok"))
        assertEquals("op 12345678901 ok", Enmascarar.texto("op 12345678901 ok")) // 11 dígitos: un número de operación
    }

    @Test fun `texto tapa una banda con D`() {
        val s = Enmascarar.texto("t2 5413330000000011D2512201123456789 fin")
        assertEquals("t2 **** fin", s)
        assertEquals("t2 **** fin", Enmascarar.texto("t2 5413330000000011=2512201123456789 fin"))
    }

    @Test fun `texto tapa hex de 16`() {
        assertEquals("llave ****", Enmascarar.texto("llave 042666B49184CFA368DE9628D0397BC9"))
        assertEquals("kcv AF8C07", Enmascarar.texto("kcv AF8C07"))
    }

    @Test fun `contiene dato de la tarjeta detecta el pan y una racha de 12`() {
        val pan = "4761739001010010"
        assertTrue(Enmascarar.contieneDatoDeLaTarjeta(pan, pan))
        assertTrue(Enmascarar.contieneDatoDeLaTarjeta("op739001010010", pan))     // los últimos 12
        assertTrue(Enmascarar.contieneDatoDeLaTarjeta("x476173900101y", pan))     // los primeros 12
        assertFalse(Enmascarar.contieneDatoDeLaTarjeta("39001010010", pan))       // 11 no basta
        assertFalse(Enmascarar.contieneDatoDeLaTarjeta("476173", pan))            // el BIN sí puede salir
        assertFalse(Enmascarar.contieneDatoDeLaTarjeta("0010", pan))              // los últimos 4 también
        assertFalse(Enmascarar.contieneDatoDeLaTarjeta("476173900101", null))
    }
}

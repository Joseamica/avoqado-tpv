package com.avoqado.cobropropio.dominio

import com.avoqado.cobropropio.fixtures.LecturasDePrueba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TiposTest {

    /** Tabla B, `SolicitudDeVenta` (D11): fuera de 1..999,999,999,999 centavos no se construye, ni emite `Iniciado` ni anota. */
    @Test fun `una venta fuera de rango no se construye`() {
        for (malo in listOf(0L, -1L, 1_000_000_000_000L)) {
            assertThrows(IllegalArgumentException::class.java) { SolicitudDeVenta(AttemptId.nuevo(), "venue-1", malo, Origen.VINCULADO_AL_POS) }
        }
        SolicitudDeVenta(AttemptId.nuevo(), "venue-1", 1, Origen.VINCULADO_AL_POS)
        SolicitudDeVenta(AttemptId.nuevo(), "venue-1", 999_999_999_999L, Origen.VINCULADO_AL_POS)
    }

    private fun rechaza(valor: String) {
        val error = runCatching { AttemptId(valor) }.exceptionOrNull()
        assertTrue("debió rechazar un id de largo ${valor.length}", error is IllegalArgumentException)
        if (valor.isNotEmpty()) assertFalse(error!!.message.orEmpty().contains(valor))
    }

    /** Ruling T8 (arreglos 1): el `AttemptId` ES la `reference` de Blumon, que acepta hasta 32 (36 da `E_000`). */
    @Test fun `attemptId acepta 32 hexadecimales y AttemptId nuevo genera uno valido`() {
        assertEquals("3f2a9c1e8b7d4e6fa1b2c3d4e5f60718", AttemptId("3f2a9c1e8b7d4e6fa1b2c3d4e5f60718").valor)
        val ids = (1..50).map { AttemptId.nuevo().valor }
        for (id in ids) {
            assertTrue(id, AttemptId.REGEX.matches(id))
            assertEquals('4', id[12]) // UUID v4
        }
        assertEquals(ids.size, ids.toSet().size)
        // 11 dígitos seguidos valen; 12 ya no (arreglos 2: la misma racha que rechaza el lector y que tapa Enmascarar).
        assertEquals("12345678901abcdef0123456789abcde", AttemptId("12345678901abcdef0123456789abcde").valor)
    }

    @Test fun `AttemptId nuevo nunca trae una racha de 12 digitos`() {
        val racha = Regex("[0-9]{12,}")
        repeat(10_000) { val id = AttemptId.nuevo().valor; assertFalse(id, racha.containsMatchIn(id)) }
    }

    @Test fun `attemptId rechaza una banda con D y un pan de 16`() {
        rechaza(LecturasDePrueba.PAN_VISA)
        rechaza("4761739001010010D2512201123456789")
        rechaza(LecturasDePrueba.banda(LecturasDePrueba.PAN_VISA))
    }

    @Test fun `attemptId rechaza 7 caracteres y fuera del juego`() {
        rechaza("abcdefg")
        rechaza("")
        rechaza("3F2A9C1E8B7D4E6FA1B2C3D4E5F60718") // mayúsculas
        rechaza("3f2a9c1e8b7d4e6fa1b2c3d4e5f6071g")
        rechaza("3f2a9c1e-8b7d-4e6f-a1b2-c3d4e5f60718") // con guiones (36)
        rechaza("3f2a9c1e8b7d4e6fa1b2c3d4e5f607189") // 33
        rechaza(" 3f2a9c1e8b7d4e6fa1b2c3d4e5f60718")
        rechaza("1234567890123abcdef0123456789abc") // racha de 13 dígitos
        rechaza("123456789012abcdef0123456789abcd") // racha de 12 dígitos
        rechaza("12345678901234567890123456789012") // sólo dígitos
    }

    @Test fun `lectura toString oculto`() {
        val lectura = LecturasDePrueba.visaPlasticoVenta()
        assertEquals("Lectura(oculta)", lectura.toString())
        val leida = ResultadoDeLectura.Leida(lectura).toString()
        for (secreto in listOf(LecturasDePrueba.PAN_VISA, "A1B2C3D4E5F60718", "06010A03A00000", "D2912101"))
            assertFalse(leida.contains(secreto))
    }

    @Test fun `lectura entrega bin ultimos 4 pais pan y huella de lectura`() {
        val lectura = LecturasDePrueba.visaPlasticoExtranjeraCancelacion()
        assertEquals("411111", lectura.bin())
        assertEquals("1111", lectura.ultimos4())
        assertEquals("0840", lectura.paisEmisor())
        assertEquals(LecturasDePrueba.PAN_VISA, lectura.pan())
        assertEquals("A1B2C3D4E5F60718" + "0001", lectura.huellaDeLectura())
        assertEquals("378282", LecturasDePrueba.amexPlasticoVenta().bin())
        assertEquals("0005", LecturasDePrueba.amexPlasticoVenta().ultimos4())
        val rota = Lectura(Marca.VISA, Entrada.SIN_CONTACTO, ResultadoKernel.PIDE_BANCO, 0, Cvm.SIN_CVM,
            mapOf(0x57 to "41111111D29", 0x5F28 to "84A"))
        assertNull(rota.pan()); assertNull(rota.bin()); assertNull(rota.ultimos4()); assertNull(rota.paisEmisor())
        assertNull(rota.huellaDeLectura())
    }

    /**
     * Ola final, 4ª parte · 2 (R24-3): `Lectura` guarda su propia copia de los tags. Si el lector reusa y cambia su mapa, la
     * lectura no cambia: el cuerpo y las huellas salen de la misma tarjeta. Tarjetas y criptogramas de prueba, falsos.
     */
    @Test fun `lectura no cambia si el lector modifica su mapa despues`() {
        val delLector = mutableMapOf(0x57 to "4111111111111111D29121011234567", 0x9F26 to "A1B2C3D4E5F60718", 0x9F36 to "0001")
        val lectura = Lectura(Marca.VISA, Entrada.SIN_CONTACTO, ResultadoKernel.PIDE_BANCO, 0, Cvm.SIN_CVM, delLector)
        delLector[0x57] = "5555555555554444D30121011234567"
        delLector[0x9F26] = "0F0E0D0C0B0A0908"
        delLector[0x9F36] = "0002"
        assertEquals("4111111111111111", lectura.pan())
        assertEquals("411111", lectura.bin())
        assertEquals("1111", lectura.ultimos4())
        assertEquals("A1B2C3D4E5F60718" + "0001", lectura.huellaDeLectura())
        assertEquals("4111111111111111D29121011234567", lectura.tags[0x57])
    }

    @Test fun `identidad arma su ambito con pos serie de Blumon y ambiente`() {
        val identidad = Identidad("9001", "90001", "0000000000", "BLU123", "A910S", "1.7.0", Ambiente.PRODUCCION)
        assertEquals("90001|BLU123|PRODUCCION", identidad.ambito())
    }

    @Test fun `solo los desenlaces son terminales`() {
        val id = AttemptId.nuevo()
        val identidad = Identidad("9001", "90001", "0000000000", "BLU123", "A910S", "1.7.0", Ambiente.SANDBOX)
        val noTerminales = listOf(EventoDeCobro.Iniciado(id, identidad, Operacion.VENTA, 100), EventoDeCobro.PideTarjeta,
            EventoDeCobro.Autorizando, EventoDeCobro.RevisandoConElBanco)
        val terminales = listOf(
            EventoDeCobro.Aprobado(1L, "478790", "pay_1", Marca.VISA, Medio.PLASTICO, "411111", "1111", "0484"),
            EventoDeCobro.Cancelada(28111711L), EventoDeCobro.Rechazado("05", "NO AUTORIZADA"),
            EventoDeCobro.NoSalioAlBanco(MotivoNoSalio.NO_ADMITIDA, null), EventoDeCobro.NoSeCobro,
            EventoDeCobro.PorConfirmar(100))
        noTerminales.forEach { assertFalse(it.toString(), it.esTerminal) }
        terminales.forEach { assertTrue(it.toString(), it.esTerminal) }
    }
}

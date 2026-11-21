package com.avoqado.cobropropio.cripto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DukptTest {
    // Vector público de ANSI X9.24-1: BDK 0123456789ABCDEFFEDCBA9876543210, KSN inicial FFFF9876543210E00000.
    private val ipek = "6AC292FAA1315B4D858AB3A3D7D5933A".hexABytes()
    private val base = "FFFF9876543210E00000".hexABytes()

    @Test fun `llave de transaccion del vector ANSI`() {
        assertEquals("FFFF9876543210E00001", Dukpt.ksnCon(base, 1).aHex())
        assertEquals("042666B49184CFA368DE9628D0397BC9", Dukpt.llaveDeTransaccion(ipek, Dukpt.ksnCon(base, 1)).aHex())
    }

    // Oráculo independiente: `openssl enc -des-ede3 -nopad` de 8 bytes en cero con la IPEK (K1K2K1) da AF8C074A….
    @Test fun `kcv de la llave`() = assertEquals("AF8C07", Dukpt.kcv(ipek))

    @Test fun `contador maximo es 21 bits`() {
        assertEquals(0x1FFFFF, Dukpt.CONTADOR_MAXIMO)
        assertEquals("FFFF9876543210FFFFFF", Dukpt.ksnCon(base, Dukpt.CONTADOR_MAXIMO).aHex())
        assertThrows(IllegalArgumentException::class.java) { Dukpt.ksnCon(base, Dukpt.CONTADOR_MAXIMO + 1) }
        assertThrows(IllegalArgumentException::class.java) { Dukpt.ksnCon(base, 0) }
    }

    @Test fun `cifrar track2 rellena con F a 48`() {
        val track2 = "4761739001010010D2512201123456789"
        val llave = Dukpt.llaveDeTransaccion(ipek, Dukpt.ksnCon(base, 1))
        val cifrado = Dukpt.cifrarTrack2(llave, track2)
        assertEquals(48, cifrado.length)
        val claro = Dukpt.tdes(Dukpt.llaveDeDatos(llave), cifrado.hexABytes(), cifrar = false).aHex()
        assertEquals(track2.padEnd(48, 'F'), claro)
    }

    // Valor conocido, sacado UNA vez con el DUKPT del piloto (lectorlab/Dukpt.kt) y confirmado con openssl.
    @Test fun `cifrar track2 da el valor conocido del piloto`() {
        val llave = Dukpt.llaveDeTransaccion(ipek, Dukpt.ksnCon(base, 1))
        assertEquals(
            "9D90FCD37A1D006681EFACF98745FA7E01FFC9A13D5FCF35",
            Dukpt.cifrarTrack2(llave, "4761739001010010D2512201123456789"),
        )
    }

    /**
     * R1-9: cada arreglo intermedio de llave queda en cero al terminar, el resultado no cambia, y ni la IPEK ni la llave de
     * la transacción que entra a `cifrarTrack2` se tocan. El gancho ve cada arreglo justo ANTES de borrarlo: la copia dice qué
     * era y la referencia, que quedó en cero.
     */
    @Test fun `los arreglos intermedios de llave quedan en cero y la llave que entra no cambia`() {
        val track2 = "4761739001010010D2512201123456789"
        // Lo que debe aparecer entre lo borrado, calculado ANTES de poner el gancho.
        val intermedia = Dukpt.llaveDeTransaccion(ipek, Dukpt.ksnCon(base, 2)) // contador 3 = paso del bit 2 y luego del bit 1
        val registro = Dukpt.ksnCon(base, 3).copyOfRange(2, 10)
        val ipekConMascara = ipek.mapIndexed { i, b -> (b.toInt() xor "C0C0C0C000000000C0C0C0C000000000".hexABytes()[i].toInt()).toByte() }
            .toByteArray()
        val llaveTx = Dukpt.llaveDeTransaccion(ipek, Dukpt.ksnCon(base, 1))
        val variante = llaveTx.mapIndexed { i, b -> (b.toInt() xor "0000000000FF00000000000000FF0000".hexABytes()[i].toInt()).toByte() }
            .toByteArray()
        val llaveDeDatos = Dukpt.llaveDeDatos(llaveTx)
        val ipekAntes = ipek.copyOf()

        val vistos = mutableListOf<Pair<ByteArray, ByteArray>>() // (la referencia, lo que tenía antes de borrarse)
        Dukpt.antesDeBorrar = { vistos += it to it.copyOf() }
        try {
            val deTres = Dukpt.llaveDeTransaccion(ipek, Dukpt.ksnCon(base, 3))
            val llave = Dukpt.llaveDeTransaccion(ipek, Dukpt.ksnCon(base, 1))
            val llaveAntes = llave.copyOf()
            assertEquals("9D90FCD37A1D006681EFACF98745FA7E01FFC9A13D5FCF35", Dukpt.cifrarTrack2(llave, track2))
            assertArrayEquals(llaveAntes, llave) // la que entra no se modifica: la borra quien la pidió
            assertArrayEquals(ipekAntes, ipek)
            assertFalse(deTres.all { it == 0.toByte() }) // la llave que se regresa no se borra
        } finally {
            Dukpt.antesDeBorrar = null
        }
        fun borrado(valor: ByteArray) = vistos.any { (_, antes) -> antes.contentEquals(valor) }
        assertTrue("llave intermedia del ciclo", borrado(intermedia))
        assertTrue("registro", borrado(registro))
        assertTrue("temporal de noReversible", borrado(ipekConMascara))
        assertTrue("variante", borrado(variante))
        assertTrue("llave de datos", borrado(llaveDeDatos))
        assertTrue("llave de 24 bytes de tdes", borrado(variante + variante.copyOfRange(0, 8)))
        assertTrue("todo lo borrado quedó en cero", vistos.all { (r, _) -> r.all { it == 0.toByte() } })
    }

    @Test fun `llave de transaccion rechaza el contador 0`() {
        assertThrows(IllegalArgumentException::class.java) { Dukpt.llaveDeTransaccion(ipek, base) }
    }

    @Test fun `base canonica ignora el contador`() {
        val esperado = "FFFF9876543210E00000"
        assertEquals(esperado, Dukpt.baseCanonica(base))
        assertEquals(esperado, Dukpt.baseCanonica(Dukpt.ksnCon(base, 1)))
        assertEquals(esperado, Dukpt.baseCanonica(Dukpt.ksnCon(base, Dukpt.CONTADOR_MAXIMO)))
    }

    @Test fun `crc32 sobre el texto hex`() = assertEquals("CBF43926", Dukpt.crc32("123456789"))
}

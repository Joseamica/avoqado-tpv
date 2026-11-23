package com.avoqado.motorlab

import com.avoqado.cobropropio.dominio.Ambiente
import com.avoqado.cobropropio.dominio.Identidad
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Bloque 4-L · L-d1: los tres campos tecleados y la llave de huellas, en privado. Valores inventados. */
class IdentidadTest {
    @get:Rule val carpeta = TemporaryFolder()

    @Test fun `la identidad tecleada tiene forma y se guarda en privado`() {
        for ((s, p, f) in listOf(Triple("12 34", "1", ""), Triple("1234", "12a", ""), Triple("1234", "", ""), Triple("12", "1", ""), Triple("1234", "1234567890", ""), Triple("1234", "1", "F 1"))) {
            assertTrue("$s / $p / $f", runCatching { IdentidadTecleada.de(s, p, f) }.isFailure)
        }
        val t = IdentidadTecleada.de(" SERIE0099 ", "4321", "")
        assertEquals(IdentidadTecleada("SERIE0099", "4321", "SERIE0099"), t) // física vacía: la misma serie
        assertEquals("IdentidadTecleada(oculta)", t.toString())
        assertEquals(Identidad(null, "4321", "FISICA01", "SERIE0099", "A910S", "motorlab-0.1.0", Ambiente.SANDBOX), IdentidadTecleada.de("SERIE0099", "4321", "FISICA01").identidad())
        val archivo = ArchivoDeIdentidad(carpeta.root)
        assertNull(archivo.leer())
        archivo.guardar(t)
        assertEquals(t, ArchivoDeIdentidad(carpeta.root).leer())
        File(carpeta.root, "identidad-del-laboratorio.txt").writeText("SERIE 0099\n4321\nSERIE0099\n")
        assertNull(archivo.leer()) // sin forma: se vuelve a teclear
    }

    @Test fun `la llave de huellas nace una vez y se relee igual`() {
        val a = llaveDeHuellas(carpeta.root)
        assertEquals(32, a.encoded.size)
        assertArrayEquals(a.encoded, llaveDeHuellas(carpeta.root).encoded)
        File(carpeta.root, "llave-de-huellas").writeBytes(ByteArray(5))
        assertTrue(runCatching { llaveDeHuellas(carpeta.root) }.isFailure) // nunca se reemplaza
        assertEquals(5, File(carpeta.root, "llave-de-huellas").length())
    }
}

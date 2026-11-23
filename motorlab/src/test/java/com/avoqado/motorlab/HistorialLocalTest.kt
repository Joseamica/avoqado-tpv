package com.avoqado.motorlab

import com.avoqado.cobropropio.dominio.AttemptId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Bloque 4-L · L-d1: un archivo por folio; las notas, en memoria. */
class HistorialLocalTest {
    @get:Rule val carpeta = TemporaryFolder()

    private fun dir() = File(carpeta.root, "historial")

    /** Cada folio está ENTERO en su archivo antes de regresar (otra instancia lo ve, en orden); las notas sólo de folios anotados. */
    @Test fun `cada folio queda entero en su archivo antes de regresar y en orden`() {
        val (a, b) = List(2) { AttemptId.nuevo() }
        val h = HistorialLocal(dir())
        h.anotarFolio(a, 2_537)
        h.anotarFolio(b, 1_000)
        h.nota(a, 41, "Autorizando")
        h.nota(AttemptId.nuevo(), 51, "de otro folio")
        assertEquals(listOf(a to 2_537L, b to 1_000L), HistorialLocal(dir()).leer().map { it.folio to it.importeCentavos })
        assertEquals(listOf(41L to "Autorizando"), h.leer().first().notas)
        assertEquals(emptyList<Pair<Long, String>>(), HistorialLocal(dir()).leer().first().notas)
    }

    /** El proceso murió escribiendo: el folio se lista sin importe (se exige el formato COMPLETO) y el siguiente, en la MISMA instancia, no se pierde. */
    @Test fun `un archivo a medias se lista sin importe y no se come al siguiente`() {
        val h = HistorialLocal(dir())
        val (a, b, c) = List(3) { AttemptId.nuevo() }
        h.anotarFolio(a, 2_537)
        h.anotarFolio(b, 2_537)
        val (fa, fb) = dir().listFiles()!!.sortedBy { it.name }
        fa.writeText("25")
        fb.writeText("2537") // sin su salto: tampoco está completo
        h.anotarFolio(c, 1_000)
        val leidos = HistorialLocal(dir()).leer()
        assertEquals(listOf(a, b, c), leidos.map { it.folio })
        assertNull(leidos[0].importeCentavos)
        assertNull(leidos[1].importeCentavos)
        assertEquals(1_000L, leidos[2].importeCentavos)
    }
}

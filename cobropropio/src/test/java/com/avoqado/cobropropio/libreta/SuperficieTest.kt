package com.avoqado.cobropropio.libreta

import com.avoqado.cobropropio.servidor.ColaDeResultados
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * r2 familia A: el motor es el ÚNICO dueño de su libreta. Por fuera del módulo sólo se lee; nada cambia una fila sin pasar
 * por el motor. Una función `internal` de Kotlin sale con el nombre del módulo pegado (`anotar$cobropropio_debug`), así que
 * aquí sólo quedan las públicas. `AttemptId` es una clase de valor: su función sale con un sufijo (`buscar-AbCd`), que se
 * quita. El constructor `internal` sale público en el bytecode: ése lo cuida el compilador.
 */
class SuperficieTest {

    private fun publicas(c: Class<*>) =
        c.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && '$' !in it.name }.map { it.name.substringBefore('-') }.toSet()

    @Test fun `la libreta y la cola solo dejan leer por fuera del motor`() {
        assertEquals(setOf("buscar", "pendientesDeReporte"), publicas(Libreta::class.java))
        assertEquals(emptySet<String>(), publicas(ColaDeResultados::class.java))
    }

    /**
     * R3-5: la base y el DAO de Room tampoco salen del módulo (con ellos se escribe una fila sin el motor). Un `internal` de
     * Kotlin sale público en el bytecode y el módulo no trae kotlin-reflect: se lee la declaración en el fuente.
     */
    @Test fun `la base y el dao de la libreta no salen del modulo`() {
        val libreta = File("src/main/java/com/avoqado/cobropropio/libreta")
        assertTrue(File(libreta, "LibretaDatabase.kt").readLines().any { it.startsWith("internal abstract class LibretaDatabase") })
        assertTrue(File(libreta, "LibretaDao.kt").readLines().any { it.startsWith("internal interface LibretaDao") })
    }

    /** R2-1: la recuperación exige la lista de vivos; sin valor por defecto nadie la llama «vacía» por descuido. */
    @Test fun `recuperar al arrancar no tiene lista de vivos por defecto`() {
        assertTrue(Libreta::class.java.declaredMethods.none { it.name.startsWith("recuperarAlArrancar") && it.name.endsWith("\$default") })
    }
}

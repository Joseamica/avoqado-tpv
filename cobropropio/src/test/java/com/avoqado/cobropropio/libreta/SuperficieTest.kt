package com.avoqado.cobropropio.libreta

import com.avoqado.cobropropio.servidor.ColaDeResultados
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    /**
     * Revisión final, recomendación 5: los enums que guarda la libreta se AGREGAN, nunca se renombran ni se quitan (las filas
     * viejas traen el nombre viejo). Una constante nueva se agrega aquí, y desde ese momento también queda congelada.
     */
    @Test fun `los enums que guarda la libreta solo se agregan`() {
        val congelados = mapOf(
            "Operacion" to listOf("VENTA", "CANCELACION"),
            "Ambiente" to listOf("SANDBOX", "PRODUCCION"),
            "Origen" to listOf("VINCULADO_AL_POS", "LOCAL_SIN_SOLICITUD"),
            "EstadoDelIntento" to listOf("ANOTADO", "ENVIADO", "APROBADO", "RECHAZADO", "NO_SALIO", "NO_SE", "NO_SE_COBRO", "CANCELADA"),
            "MotivoNoSalio" to listOf(
                "SIN_RED", "LECTURA_CORTADA", "LECTURA_VENCIDA", "CANCELADA_EN_LECTOR", "NO_ADMITIDA", "TERMINAL_OCUPADA", "SIN_LLAVES",
                "CONTADOR_AGOTADO", "INTERRUMPIDO", "CANCELACION_YA_EXISTE", "SIN_VENTA_APROBADA", "OTRA_TARJETA", "OTRO_AMBITO",
                "LECTURA_REPETIDA", "CANCELACION_NO_PERMITIDA",
            ),
            "EstadoDelReverso" to listOf("NINGUNO", "COMPROMETIDO", "RECLAMADO", "CONFIRMADO", "RECHAZADO", "NO_CONCLUYENTE"),
            "EstadoDelReporte" to listOf("NO_APLICA", "PENDIENTE", "ENTREGADO", "CONCILIAR", "FINAL_SIN_ENTREGAR"),
            "ResultadoParaReportar" to listOf("CONFIRMADO", "RECHAZADO", "PUDO_ENTRAR"),
            "EstadoDelReversoEnServidor" to listOf("REVERSING", "REVERSED", "REVERSE_FAILED", "REVERSE_UNCONFIRMED", "CONTRADICTION"),
            "OrigenDeLaAprobacion" to listOf("BANCO", "SERVIDOR"),
            "Marca" to listOf("VISA", "MASTERCARD", "AMEX"),
            "Medio" to listOf("PLASTICO", "CELULAR"),
        )
        val tipos = (IntentoEntity::class.java.declaredFields + ContadorEntity::class.java.declaredFields)
            .map { it.type }.filter { it.isEnum }.distinct()
        assertEquals("cada enum de una columna está aquí", congelados.keys, tipos.map { it.simpleName }.toSet())
        for (t in tipos) {
            val hoy = t.enumConstants!!.map { (it as Enum<*>).name }
            val antes = congelados.getValue(t.simpleName)
            for (n in antes - hoy.toSet()) fail("${t.simpleName}.$n se renombró o se quitó: las filas viejas la traen (se agrega, nunca se renombra)")
            for (n in hoy - antes.toSet()) fail("${t.simpleName}.$n es nueva: agrégala a esta lista")
        }
    }
}

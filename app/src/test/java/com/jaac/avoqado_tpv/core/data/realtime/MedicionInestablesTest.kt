package com.jaac.avoqado_tpv.core.data.realtime

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.JUnitCore
import org.junit.runner.Request
import org.junit.runner.manipulation.Filter

/**
 * TEMPORAL (encargo 5053f3 de la nube, 18-nov-2026): mide las pruebas S5 de `SocketManagerTest` (las que leen listas que llena
 * el hilo de IO), corriéndolas muchas veces con la máquina ocupada (hilos que giran en todos los núcleos, como un runner
 * cargado). Siempre termina en rojo A PROPÓSITO para que el CI suba el reporte con la cuenta. Se borra antes de unir.
 */
class MedicionInestablesTest {

    @Test
    fun medicion() {
        val vueltas = 200
        val soloS5 = object : Filter() {
            override fun shouldRun(d: Description) = d.methodName == null || listOf("S5", "N0", "durable ACK", "P2 ").any { it in d.methodName }
            override fun describe() = "pruebas S5 y vecinas"
        }
        val parar = AtomicBoolean(false)
        val carga = List(Runtime.getRuntime().availableProcessors() * 2) {
            Thread {
                var x = 0L
                while (!parar.get()) x++
                if (x == -1L) println(x)
            }.apply { isDaemon = true; start() }
        }
        val fallas = mutableListOf<String>()
        var corridas = 0
        val inicio = System.currentTimeMillis()
        try {
            repeat(vueltas) { i ->
                val r = JUnitCore().run(Request.aClass(SocketManagerTest::class.java).filterWith(soloS5))
                corridas += r.runCount
                r.failures.forEach { fallas += "vuelta $i ${it.description.methodName}: ${it.exception}" }
            }
        } finally {
            parar.set(true)
            carga.forEach { it.join() }
        }
        val resumen = "MEDICION SocketManagerTest S5: ${fallas.size} rojas de $corridas corridas ($vueltas vueltas) con carga " +
            "(${System.currentTimeMillis() - inicio} ms)\n" + fallas.take(5).joinToString("\n")
        println(resumen)
        fail(resumen)
    }
}

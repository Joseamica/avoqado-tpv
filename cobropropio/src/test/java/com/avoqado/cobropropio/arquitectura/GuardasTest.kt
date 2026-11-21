package com.avoqado.cobropropio.arquitectura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * Guardas de arquitectura (piezas comunes 2 y 3) sobre los fuentes de `src/main`. Una línea nueva que no está en la lista
 * truena: para agregarla hay que escribir aquí su razón.
 */
class GuardasTest {

    private val raiz = File("src/main/java/com/avoqado/cobropropio")

    /** (archivo relativo, línea con el patrón) de cada aparición, sin comentarios. */
    private fun apariciones(patron: Regex): List<Pair<String, String>> = raiz.walk().filter { it.extension == "kt" }.flatMap { f ->
        f.readLines().map { it.trim() }
            .filter { !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") && !it.startsWith("import ") }
            .filter { patron.containsMatchIn(it.substringBefore(" // ")) }
            .map { f.relativeTo(raiz).path to it }
    }.toList()

    /**
     * Cada aparición casa con una entrada de la lista (la de trozo más largo, si una línea contiene a otra), y cada entrada
     * casa con al menos una aparición (sin entradas viejas).
     */
    private fun revisar(patron: Regex, lista: List<Triple<String, String, String>>) {
        val vistas = apariciones(patron)
        fun suya(archivo: String, linea: String) =
            lista.filter { (a, trozo, _) -> a == archivo && trozo in linea }.maxByOrNull { it.second.length }
        for ((archivo, linea) in vistas) assertTrue("sin razón en la lista: $archivo: $linea", suya(archivo, linea) != null)
        for (e in lista) assertTrue("entrada vieja: ${e.first} «${e.second}»", vistas.any { (a, l) -> suya(a, l) == e })
    }

    /**
     * Pieza 2 · lo que llegó nunca lo tira un reloj (R3-2). Cada `withTimeout*`, `Call.timeout()` y `callTimeout` de
     * `src/main`, con lo que hace al vencer. Los que envuelven red terminan en «lo que llegó», o la razón dice por qué lo que
     * se pierde no es evidencia.
     */
    @Test fun `ningun reloj nuevo sin su razon`() = revisar(
        Regex("""withTimeout|\.timeout\(\)|callTimeout"""),
        listOf(
            Triple("red/EnvioUnico.kt", ".callTimeout(", "el reloj del transporte: corta ANTES de tener el cuerpo completo; lo leído se entrega por alLeer y por la reanudación"),
            Triple("motor/MotorDeCobro.kt", "withTimeoutOrNull(relojes.esperaLectura)", "la lectura de la tarjeta: sin red"),
            Triple("motor/MotorDeCobro.kt", "withTimeoutOrNull(limite - ahora()) {", "reservarSesion: sólo el Mutex de la sesión, sin red"),
            Triple("motor/MotorDeCobro.kt", "withTimeoutOrNull(limite - ahora()) { candadoDeSesion.withLock { sesionConCandado", "sesionPara: el Mutex y la cadena de init; una sesión nacida se instala antes de cualquier suspensión (R3-4) y lo cortado antes no es evidencia de dinero"),
            Triple("motor/MotorDeCobro.kt", "withTimeoutOrNull(espera) { candadoDeSesion.withLock { renovarTokenConCandado", "tokenNuevo: el token se instala dentro del candado; un token perdido no es evidencia"),
            Triple("motor/MotorDeCobro.kt", "withTimeoutOrNull(espera) { intentar { blumon.renovarToken", "renovar el token: un token perdido no es evidencia, se pide otro"),
            Triple("motor/MotorDeCobro.kt", "withTimeoutOrNull(t0 + relojes.esperaRespuesta - ahora()) { venta.await() }", "la venta sigue en su async: lo que llegue tarde lo guarda guardarRespuestaTardia"),
            Triple("motor/MotorDeCobro.kt", "withTimeoutOrNull(espera) { intentar { llamada(", "conBuzon (T9): el preflight y la cancelación; lo que el transporte leyó ya está en el buzón y se clasifica aunque venza (R3-2), con el mismo plazo que la llamada"),
            Triple("motor/MotorDeCobro.kt", "withTimeoutOrNull(relojes.esperaLibreta) { listo.await() }", "conTope (S1–S3): no envuelve red ni Room, sólo la espera de un Deferred; al vencer da lo más conservador (PorConfirmar o SIN_CONFIRMAR) y lo que llega tarde no admite ni contesta nada"),
            Triple("motor/CaminoDelReverso.kt", "withTimeoutOrNull(relojes.esperaPermiso + CERCA_DEL_PERMISO)", "la cerca del permiso (p3 c5): sólo vence si el cliente rompe su plazo y da ErrorDelServidor, que nunca reversa"),
            Triple("motor/CaminoDelReverso.kt", "withTimeoutOrNull(relojes.esperaReverso)", "una vuelta del reverso: lo que el transporte leyó ya está clasificado en el buzón (R3-2)"),
            Triple("motor/CaminoDelReverso.kt", "withTimeoutOrNull(t0 + relojes.peorCasoCajera - ahora()) { entrega.await() }", "el acuse sigue en su async: se aplica a la libreta y se publica tarde"),
            Triple("servidor/ReversoDelServidorHttp.kt", "withTimeoutOrNull(espera) { credencial.renovar", "renovar el JWT (permiso y /resultado): la app guarda el nuevo; perderlo no pierde evidencia (v5 §2.5)"),
            Triple("servidor/ReversoDelServidorHttp.kt", "llamada.timeout().timeout(", "red de seguridad de OkHttp, del mismo largo que el reloj de quien pide"),
            Triple("servidor/ReversoDelServidorHttp.kt", "withTimeoutOrNull(espera) { enCurso() } ?: llegada.get()", "el permiso y /resultado (R4-4): al vencer sale el código que llegó (r2 familia B)"),
        ),
    )

    /**
     * Pieza 3 · fuera del lector nada parsea cuerpos de Blumon. `ReversoDelServidorHttp` lee cuerpos del SERVIDOR (no de
     * Blumon) con su propio árbol; `ArmadoDelMensaje` y `ClienteBlumon` sólo ARMAN JSON.
     */
    @Test fun `solo el lector parsea cuerpos de Blumon`() = revisar(
        Regex("""JsonReader|JsonParser|fromJson|getAdapter\(|asJsonObject|Gson\("""),
        listOf(
            Triple("red/LectorJson.kt", "JsonReader(", "el único lector de cuerpos de Blumon"),
            Triple("servidor/ReversoDelServidorHttp.kt", "JsonParser.parseString", "cuerpos del SERVIDOR, no de Blumon"),
            Triple("servidor/ReversoDelServidorHttp.kt", "fun JsonObject.objeto(", "cuerpos del SERVIDOR, no de Blumon"),
        ),
    )

    /** Fase 3, bloque 1 (Codex r3 #3, vara 2): toda fila de la libreta se lee por UN punto validado; ninguna lectura cruda más. */
    @Test fun `la libreta lee filas por un solo punto validado`() {
        // `apariciones` ya salta comentarios: cuenta líneas de código, una por lectura cruda.
        for (lectura in listOf("dao.buscar(", "dao.cancelacionesDe(", "dao.porRowid(")) {
            assertEquals("lecturas crudas «$lectura»", listOf("libreta/Libreta.kt"), apariciones(Regex(Regex.escape(lectura))).map { it.first })
        }
        assertTrue("dao::buscar volvió", apariciones(Regex("""dao::buscar""")).isEmpty())
        val fuera = apariciones(Regex("""\bdao\b""")).filterNot { it.first.startsWith("libreta/") }
        assertTrue("el dao fuera de la libreta: $fuera", fuera.isEmpty())
    }

    @Test fun `motor y libreta no tocan Gson`() {
        for (carpeta in listOf("motor", "libreta")) {
            val conGson = File(raiz, carpeta).walk().filter { it.extension == "kt" && "com.google.gson" in it.readText() }.toList()
            assertEquals(carpeta, emptyList<File>(), conGson)
        }
    }

    /** D8: ningún gancho de prueba mutable vive en el código de cifrado de producción. */
    @Test fun `cripto no guarda ganchos de prueba`() {
        val dukpt = File(raiz, "cripto/Dukpt.kt").readText()
        assertTrue("antesDeBorrar volvió", "antesDeBorrar" !in dukpt)
        // Sólo miembros del objeto (4 espacios): las `var` locales de las funciones (8 espacios) no cuentan.
        assertTrue("una variable mutable en Dukpt", Regex("""^ {4}(?:@\S+ )*(?:internal |private )?var """, RegexOption.MULTILINE).find(dukpt) == null)
    }

    /**
     * Lo que Java ve PÚBLICO de [c]: métodos y campos públicos y no sintéticos. Ola final, 2ª parte (Codex r22): un `internal`
     * deja en la JVM un accesor público con sufijo (`getTags$cobropropio_debug`) que Java sí llama, así que ya NO se ignoran los
     * nombres con `$`: se cuentan con el nombre sin el sufijo. Sólo `@JvmSynthetic` los saca de aquí.
     */
    private fun publicos(c: Class<*>) = (c.declaredMethods.map { it.name to it.modifiers to it.isSynthetic } +
        c.declaredFields.map { it.name to it.modifiers to it.isSynthetic })
        .filter { (nm, sintetico) -> Modifier.isPublic(nm.second) && !sintetico }.map { it.first.first.substringBefore('$') }.toSet()

    /**
     * Ola final · 2 (Codex r21): ningún miembro PÚBLICO entrega la banda (tag 57), el PAN completo, el criptograma ni material
     * de llaves. Por nombre, para que nadie los vuelva a abrir sin darse cuenta; y la lista exacta de lo público de cada clase
     * que los guarda, para que un miembro nuevo también pase por aquí. El constructor de `Lectura` sigue público: es el borde
     * contra el que la fase 3 escribe el lector de la PAX desde `:app`.
     */
    @Test fun `nada publico entrega la tarjeta ni las llaves`() {
        val lectura = Class.forName("com.avoqado.cobropropio.dominio.Lectura")
        val material = Class.forName("com.avoqado.cobropropio.blumon.MaterialDukpt")
        val cuerpo = Class.forName("com.avoqado.cobropropio.blumon.CuerpoDeBlumon")
        for ((clase, cerrados) in listOf(
            lectura to listOf("getTags", "pan", "huellaDeLectura"),
            material to listOf("getIpek"),
            cuerpo to listOf("comoRequestBody"),
        )) for (m in cerrados) assertTrue("${clase.simpleName}.$m es público", m !in publicos(clase))

        assertEquals(setOf("getKernel", "getEntrada", "getResultado", "getCodigoKernel", "getCvm", "bin", "ultimos4", "paisEmisor", "toString"),
            publicos(lectura))
        assertEquals(setOf("getKsnBase", "getContador", "toString"), publicos(material))
        assertEquals(setOf("conMotivoDeReverso", "toString"), publicos(cuerpo) - "Companion") // el campo del `internal companion`
        assertTrue("el constructor de Lectura dejó de ser público", lectura.constructors.any { Modifier.isPublic(it.modifiers) })

        // 2ª parte: las otras clases que guardan el token, la credencial Basic, la IPEK, la llave derivada o la banda cifrada.
        for ((nombre, cerrados) in listOf(
            "com.avoqado.cobropropio.cripto.Dukpt" to listOf("llaveDeTransaccion", "cifrarTrack2", "llaveDeDatos", "tdes", "getCEROS", "CEROS"),
            // 3ª parte (Codex r23): los `internal` que Java todavía veía.
            "com.avoqado.cobropropio.motor.MotorDeCobro" to listOf("tokenNuevo"),
            "com.avoqado.cobropropio.red.EnvioUnico" to listOf("getBase"),
            "com.avoqado.cobropropio.red.Respuesta\$Http" to listOf("apariciones"),
            "com.avoqado.cobropropio.blumon.TokenDeBlumon" to listOf("getValor", "valor"),
            // 4ª parte (R24-4): la huella recibe el PAN y el criptograma; sólo el motor la llama.
            "com.avoqado.cobropropio.privacidad.HuellaHmac" to listOf("de"),
            // 4ª parte · 6: el cliente HTTP del SERVIDOR lleva el JWT de las llamadas en curso.
            "com.avoqado.cobropropio.servidor.ReversoDelServidorHttp" to listOf("getCliente", "cliente"),
            "com.avoqado.cobropropio.blumon.CredencialDelCliente" to listOf("getUsuario", "getContrasena", "usuario", "contrasena"),
            "com.avoqado.cobropropio.blumon.MaterialDeSesion" to listOf("usar", "ipek", "ksn"),
            "com.avoqado.cobropropio.blumon.SesionBlumon" to listOf("getToken", "token"),
            "com.avoqado.cobropropio.blumon.CuerpoCongelado" to listOf("getToken", "getCuerpo", "token", "cuerpo"),
            "com.avoqado.cobropropio.blumon.CuerpoDeBlumon" to listOf("json"),
            "com.avoqado.cobropropio.dominio.Lectura" to listOf("tags"),
            "com.avoqado.cobropropio.blumon.MaterialDukpt" to listOf("ipek"),
        )) {
            val clase = Class.forName(nombre)
            for (m in cerrados) assertTrue("${clase.simpleName}.$m lo ve Java", m !in publicos(clase))
        }

        // 3ª parte: `renovarToken` sigue en la interfaz pública, pero entrega el token opaco (en la JVM un `suspend` regresa `Object`,
        // así que se lee su declaración en el fuente).
        val renovar = File(raiz, "blumon/PuertoBlumon.kt").readLines().single { "fun renovarToken(" in it }
        assertTrue(renovar, renovar.trim() == "suspend fun renovarToken(espera: Duration): TokenDeBlumon?")

        // 4ª parte (R24-4): el puerto de huellas se cerró. `HuellaHmac` es final y el motor la pide a ella, no a una interfaz que
        // cualquiera implemente (y que recibiría el PAN y el criptograma, y escribiría lo que quisiera en la libreta).
        val hmac = Class.forName("com.avoqado.cobropropio.privacidad.HuellaHmac")
        assertTrue("HuellaHmac dejó de ser final", Modifier.isFinal(hmac.modifiers))
        val motor = Class.forName("com.avoqado.cobropropio.motor.MotorDeCobro")
        assertTrue("el motor no recibe HuellaHmac", motor.constructors.filter { Modifier.isPublic(it.modifiers) }.all { hmac in it.parameterTypes })
        assertTrue("sobra el puerto HuellaDeTarjeta", runCatching { Class.forName("com.avoqado.cobropropio.privacidad.HuellaDeTarjeta") }.isFailure)

        // `ArmadoDelMensaje` (arma la banda y cifra con la IPEK) es `internal`: en la JVM sigue siendo pública, así que se lee
        // su declaración en el fuente.
        val declaracion = File(raiz, "blumon/ArmadoDelMensaje.kt").readLines().single { "class ArmadoDelMensaje(" in it }
        assertTrue(declaracion, declaracion.startsWith("internal class ArmadoDelMensaje("))
    }
}

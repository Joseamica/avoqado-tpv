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

    /**
     * Fase 3, bloque 1 (Codex r3 #3, vara 2): toda fila de la libreta se lee por UN punto validado; ninguna lectura cruda más.
     * Codex b1 r1 (C3): también por `db.dao()`, y el DAO no tiene otra forma de entregar la entidad que esas tres.
     */
    @Test fun `la libreta lee filas por un solo punto validado`() {
        // `apariciones` ya salta comentarios: cuenta líneas de código, una por lectura cruda (por `dao.` o por `dao().`).
        for (lectura in listOf("buscar", "cancelacionesDe", "porRowid")) {
            val cruda = Regex("""\bdao(?:\(\))?\.$lectura\(""")
            assertEquals("lecturas crudas «$lectura»", listOf("libreta/Libreta.kt"), apariciones(cruda).map { it.first })
        }
        // C3 (b): los métodos del DAO que devuelven la entidad (sola, anulable o en lista) son EXACTAMENTE esos tres.
        val dao = Class.forName("com.avoqado.cobropropio.libreta.LibretaDao")
        assertEquals(
            setOf("buscar", "cancelacionesDe", "porRowid"),
            dao.declaredMethods.filter { "libreta.IntentoEntity" in it.genericReturnType.typeName }.map { it.name }.toSet(),
        )
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
        // Sólo miembros del objeto (4 espacios): las `var` locales de las funciones (8 espacios) no cuentan. m5: con cualquier
        // visibilidad, y también `lateinit`.
        val mutable = Regex("""^ {4}(?:@\S+ )*(?:(?:internal|private|public|protected|lateinit) )*var """, RegexOption.MULTILINE)
        assertTrue("una variable mutable en Dukpt", mutable.find(dukpt) == null)
    }

    /**
     * Fase 3, bloque 1 (revisión de una tarea anterior): ningún llamador de producción le pasa a `Dukpt` un borrador que no
     * borre. El parámetro `borrador` sólo se nombra dentro de `cripto/Dukpt.kt`; las pruebas inyectan el suyo.
     */
    @Test fun `borrador no aparece en src main fuera de Dukpt`() {
        val fuera = apariciones(Regex("""\bborrador\b""")).filterNot { it.first == "cripto/Dukpt.kt" }
        assertTrue("borrador fuera de cripto/Dukpt.kt: $fuera", fuera.isEmpty())
        // Ronda 1 de la revisión de la tarea 7: el borrador es el último parámetro y de tipo función, así que una lambda final
        // (`Dukpt.llaveDeTransaccion(ipek, ksn) { }`) lo cambia sin nombrarlo. Cada llamada a las cuatro funciones que lo reciben
        // (o una referencia `Dukpt::`) es EXACTAMENTE uno de estos renglones: una lambda final, un argumento de más o una llamada
        // partida en líneas cambian el renglón y lo ponen rojo.
        val llamadas = apariciones(Regex("""Dukpt\.(?:llaveDeTransaccion|cifrarTrack2|llaveDeDatos|tdes)\b|Dukpt::"""))
            .filterNot { it.first == "cripto/Dukpt.kt" }
        assertEquals(
            listOf(
                "blumon/ArmadoDelMensaje.kt" to "val llave = Dukpt.llaveDeTransaccion(dukpt.ipek, ksn)",
                "blumon/ArmadoDelMensaje.kt" to "try { cifrada = Dukpt.cifrarTrack2(llave, banda) } finally { llave.fill(0) }",
                "blumon/ClienteBlumon.kt" to "val material = MaterialDeSesion(Dukpt.tdes(tk, llave.hexABytes(), cifrar = false), base.hexABytes())",
            ).sortedBy { it.toString() },
            llamadas.sortedBy { it.toString() },
        )
        // C5 (Codex b1 r1): y quien no lo nombra recibe el que borra: las cuatro lo declaran con `CEROS` por defecto.
        val dukpt = File(raiz, "cripto/Dukpt.kt").readLines()
        for (f in listOf("llaveDeTransaccion", "cifrarTrack2", "llaveDeDatos", "tdes")) {
            val firma = dukpt.single { "fun $f(" in it }
            assertTrue("$f: el borrador por defecto no es CEROS: $firma", "borrador: (ByteArray) -> Unit = CEROS)" in firma)
        }
    }

    /**
     * Fase 3, bloque 1: lo que la app puede tocar es ESTA lista (los puertos de Blumon, del transporte y del SERVIDOR ya no).
     * Con `explicitApi()`, cada declaración pública lleva `public` escrito: aquí se leen las de primer nivel. `FilaIlegible`
     * entra porque `Libreta.buscar` la lanza y la app la tiene que poder atrapar.
     */
    @Test fun `la superficie publica es la lista cerrada`() {
        // Un renglón de código, no un comentario: sin `//` antes de la llamada.
        val explicito = Regex("""^[^/\n]*\bexplicitApi\(\)""", RegexOption.MULTILINE)
        assertTrue("explicitApi() en el módulo", explicito.containsMatchIn(File("build.gradle.kts").readText()))
        // Ronda 1: toda línea de primer nivel que empieza con `public` (con anotaciones antes, o no) se tiene que leer aquí; la
        // que no se lee truena, para que una forma que nadie pensó no se cuele.
        val publico = Regex("""^(?:@.*?\s+)?public\b""")
        val declaracion = Regex(
            """^(?:@.*?\s+)?public\b[^(=:{]*?\b(?:class|interface|object|fun|val|var|typealias)\s+(?:<[^>]*>\s*)?(?:\w+\.)?(\w+)""",
        )
        // C3 (Codex b1 r1): «de primer nivel» es por profundidad, no por columna: una declaración con sangría fuera de toda llave
        // y paréntesis también cuenta.
        val lineas = raiz.walk().filter { it.extension == "kt" }.flatMap { f -> primerNivel(f).map { f.relativeTo(raiz).path to it } }
            .filter { publico.containsMatchIn(it.second) }.toList()
        val sinLeer = lineas.filter { declaracion.find(it.second) == null }
        assertTrue("líneas públicas que la guardia no sabe leer: $sinLeer", sinLeer.isEmpty())
        val publicas = lineas.map { declaracion.find(it.second)!!.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "AttemptId", "Marca", "Medio", "Entrada", "Operacion", "Ambiente", "ResultadoKernel", "Cvm", "Identidad", "Lectura",
                "ResultadoDeLectura", "LectorDeTarjeta", "Red", "Origen", "Solicitud", "SolicitudDeVenta", "SolicitudDeCancelacion",
                "EventoDeCobro", "MotivoNoSalio", "MotivoNoAdmitida", "ResultadoDelCorte", "MotorDeCobro", "crearMotorDeCobro",
                "Libreta", "SaludDeLaLibreta", "FilaIlegible", "IntentoEntity", "EstadoDelIntento", "EstadoDelReverso",
                "EstadoDelReporte", "ResultadoParaReportar", "OrigenDeLaAprobacion", "EstadoDelReversoEnServidor",
                "CredencialDelCliente", "CredencialDelServidor",
            ),
            publicas,
        )
    }

    /**
     * Las líneas de [f] que empiezan fuera de toda llave y paréntesis, sin su sangría. Las líneas de comentario y lo que va
     * después de ` // ` no cuentan para la profundidad. Si la cuenta se pierde (baja de cero, o el archivo no cierra en cero:
     * p. ej. una llave suelta en una cadena), truena: la guardia no sabe leer ese archivo.
     */
    private fun primerNivel(f: File): List<String> {
        var nivel = 0
        val fuera = mutableListOf<String>()
        for (linea in f.readLines()) {
            val t = linea.trim()
            if (nivel == 0) fuera += t
            if (t.startsWith("*") || t.startsWith("/*") || t.startsWith("//")) continue
            val codigo = linea.substringBefore(" // ")
            nivel += codigo.count { it == '{' || it == '(' } - codigo.count { it == '}' || it == ')' }
            assertTrue("${f.name}: la cuenta de llaves bajó de cero", nivel >= 0)
        }
        assertEquals("${f.name}: la cuenta de llaves no cierra", 0, nivel)
        return fuera
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
            // Fase 3, bloque 1 (A3): con la base, cualquiera escribe una fila sin pasar por el motor.
            "com.avoqado.cobropropio.libreta.Libreta" to listOf("getDb", "db"),
            "com.avoqado.cobropropio.libreta.LibretaDatabase\$Companion" to listOf("constructor"),
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

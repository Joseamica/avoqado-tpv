package com.avoqado.cobropropio.blumon

import com.avoqado.cobropropio.cripto.Dukpt
import com.avoqado.cobropropio.cripto.aHex
import com.avoqado.cobropropio.cripto.hexABytes
import com.avoqado.cobropropio.dominio.Ambiente
import com.avoqado.cobropropio.dominio.Identidad
import com.avoqado.cobropropio.red.Endpoints
import com.avoqado.cobropropio.red.EnvioUnico
import com.avoqado.cobropropio.red.Respuesta
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import kotlin.time.Duration.Companion.seconds

class ClienteBlumonTest {

    private class Pedida(val ruta: String, val autorizacion: String?, val tipo: String?, val cuerpo: String)

    private val localhost = InetAddress.getByName("localhost")
    private val certificado = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val delServidor = HandshakeCertificates.Builder().heldCertificate(certificado).build()
    private val delCliente = HandshakeCertificates.Builder().addTrustedCertificate(certificado.certificate).build()
    private val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
    private val ipek = "6AC292FAA1315B4D858AB3A3D7D5933A".hexABytes() // vector público ANSI X9.24
    private val pedidas = CopyOnWriteArrayList<Pedida>()

    private var identidadDeBlumon =
        """{"status":true,"dataResponse":{"posId":12345,"commerceName":"COMERCIO DE PRUEBA","contactless":true,"initializeKeys":true,"qps":false}}"""
    private var kcv: String? = Dukpt.kcv(ipek)
    private var token = """{"access_token":"tok-de-prueba"}"""
    private var llaves: (String) -> String = { it }

    private val server = MockWebServer().apply {
        useHttps(delServidor.sslSocketFactory(), false)
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val texto = request.body.readUtf8()
                val ruta = request.path.orEmpty()
                pedidas += Pedida(ruta, request.getHeader("Authorization"), request.getHeader("Content-Type"), texto)
                val cuerpo = when {
                    ruta.startsWith("/oauth/token") -> token
                    ruta == "/device/init" -> identidadDeBlumon
                    ruta == "/device/getKey" -> """{"status":true,"dataResponse":{"rsa":"${rsa.public.encoded.aHex()}"}}"""
                    ruta == "/device/initDukptKeys" -> {
                        val cifrada = JsonParser.parseString(texto).asJsonObject.get("rsa").asString.hexABytes()
                        val tk = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, rsa.private) }.doFinal(cifrada)
                        val llave = Dukpt.tdes(tk, ipek).aHex()
                        JsonObject().apply {
                            addProperty("status", true)
                            add("dataResponse", JsonObject().apply {
                                addProperty("key", llave)
                                addProperty("ksn", "FFFF9876543210E00000")
                                kcv?.let { addProperty("keyCheckValue", it) }
                            })
                        }.toString().let(llaves)
                    }
                    else -> """{"status":true,"id":1,"dataResponse":{"simulation":false}}"""
                }
                return MockResponse().setBody(cuerpo)
            }
        }
        start(localhost, 0)
    }

    @After fun cerrar() = server.shutdown()

    private val credencial = CredencialDelCliente("usuario-de-prueba", "clave-de-prueba")
    private val ahora = { System.nanoTime() / 1_000_000 }

    /** Bloque 4-L · L-b: el nombre del comercio puede faltar (`null`) y el modelo entra a la contraseña del token. */
    private fun identidad(ambiente: Ambiente, comercio: String?, modelo: String) =
        Identidad(comercio, "12345", "0000000001", "00000000011", modelo, "1", ambiente)

    private fun cliente(
        ambiente: Ambiente = Ambiente.SANDBOX,
        comercio: String? = "COMERCIO DE PRUEBA",
        modelo: String = "A910S",
    ): ClienteBlumon {
        val endpoints = Endpoints("https://localhost:${server.port}/oauth/token", "https://localhost:${server.port}/")
        val envio = EnvioUnico(endpoints, ahora, delCliente.trustManager, object : Dns { override fun lookup(hostname: String) = listOf(localhost) })
        return ClienteBlumon(endpoints, envio, credencial, identidad(ambiente, comercio, modelo), SecureRandom())
    }

    private fun rutas() = pedidas.map { it.ruta.substringBefore('?') }

    @Test fun `sesion con KCV que cuadra trae la base y el contador de Blumon`() {
        val sesion = runBlocking { cliente().iniciarSesion(10.seconds) }
        assertNotNull(sesion)
        sesion!!
        assertEquals(Dukpt.kcv(ipek), sesion.material.usar { i, _ -> Dukpt.kcv(i) })
        assertEquals("FFFF9876543210E00000", sesion.ksnBaseCanonica)
        assertEquals(0, sesion.ultimoContadorDeBlumon)
        assertEquals(listOf("/oauth/token", "/device/init", "/device/getKey", "/device/initDukptKeys"), rutas())
        assertTrue(pedidas.drop(1).all { it.autorizacion == "Bearer tok-de-prueba" })
        assertFalse(sesion.toString().contains("tok-de-prueba"))
        val llaves = JsonParser.parseString(pedidas[3].cuerpo).asJsonObject
        assertEquals(Dukpt.crc32(llaves.get("rsa").asString), llaves.get("crc32").asString)
        assertEquals(6, llaves.get("checkValue").asString.length)
    }

    @Test fun `sesion null si qps no es false`() {
        for (qps in listOf("true", "\"false\"", null)) {
            pedidas.clear()
            val campo = if (qps == null) "" else ""","qps":$qps"""
            identidadDeBlumon =
                """{"status":true,"dataResponse":{"posId":12345,"commerceName":"COMERCIO DE PRUEBA","contactless":true,"initializeKeys":true$campo}}"""
            assertNull("$qps", runBlocking { cliente().iniciarSesion(10.seconds) })
            assertFalse("no se piden llaves con qps=$qps", rutas().contains("/device/getKey"))
        }
    }

    @Test fun `sesion null si no coinciden posId o comercio`() {
        for (otra in listOf(
            """{"status":true,"dataResponse":{"posId":99999,"commerceName":"COMERCIO DE PRUEBA","contactless":true,"initializeKeys":true,"qps":false}}""",
            """{"status":true,"dataResponse":{"posId":12345,"commerceName":"OTRO COMERCIO","contactless":true,"initializeKeys":true,"qps":false}}""",
            """{"status":true,"dataResponse":{"posId":12345,"commerceName":"COMERCIO DE PRUEBA","contactless":false,"initializeKeys":true,"qps":false}}""",
            """{"status":false,"dataResponse":{"posId":12345,"commerceName":"COMERCIO DE PRUEBA","contactless":true,"initializeKeys":true,"qps":false}}""",
        )) {
            pedidas.clear()
            identidadDeBlumon = otra
            assertNull(otra, runBlocking { cliente().iniciarSesion(10.seconds) })
            assertFalse(rutas().contains("/device/getKey"))
        }
    }

    /**
     * Bloque 4-L · L-b: el laboratorio no sabe el nombre del comercio. Sin nombre en la identidad, la sesión la decide el
     * `posId` (siempre se compara) junto con las demás guardas de `device/init`; con nombre, `commerceName` se sigue comparando.
     */
    @Test fun `sin nombre del comercio la sesion la decide el posId`() {
        // Sin nombre: el mismo posId da sesión, venga el nombre de Blumon, otro o ninguno.
        for (init in listOf(
            """{"status":true,"dataResponse":{"posId":12345,"commerceName":"COMERCIO DE PRUEBA","contactless":true,"initializeKeys":true,"qps":false}}""",
            """{"status":true,"dataResponse":{"posId":12345,"commerceName":"OTRO COMERCIO","contactless":true,"initializeKeys":true,"qps":false}}""",
            """{"status":true,"dataResponse":{"posId":12345,"contactless":true,"initializeKeys":true,"qps":false}}""",
        )) {
            pedidas.clear()
            identidadDeBlumon = init
            assertNotNull(init, runBlocking { cliente(comercio = null).iniciarSesion(10.seconds) })
            assertEquals(init, listOf("/oauth/token", "/device/init", "/device/getKey", "/device/initDukptKeys"), rutas())
        }
        // Sin nombre: otro posId, ninguno, una guarda de siempre que falla (qps), o un `commerceName` que aparece en dos rutas
        // (Codex r1 #1: «el campo en una sola ruta» se sigue exigiendo aunque no se compare) no dan sesión. Se para justo en la
        // guarda: después de `device/init`, sin pedir llaves.
        for (init in listOf(
            """{"status":true,"dataResponse":{"posId":99999,"commerceName":"COMERCIO DE PRUEBA","contactless":true,"initializeKeys":true,"qps":false}}""",
            """{"status":true,"dataResponse":{"commerceName":"COMERCIO DE PRUEBA","contactless":true,"initializeKeys":true,"qps":false}}""",
            """{"status":true,"dataResponse":{"posId":12345,"contactless":true,"initializeKeys":true,"qps":true}}""",
            """{"status":true,"dataResponse":{"posId":12345,"commerceName":"COMERCIO DE PRUEBA","contactless":true,"initializeKeys":true,"qps":false},"extra":{"commerceName":"OTRO COMERCIO"}}""",
        )) {
            pedidas.clear()
            identidadDeBlumon = init
            assertNull(init, runBlocking { cliente(comercio = null).iniciarSesion(10.seconds) })
            assertEquals(init, listOf("/oauth/token", "/device/init"), rutas())
        }
        // Con nombre, un `device/init` que no lo manda no da sesión: el nombre que viene se sigue comparando.
        pedidas.clear()
        identidadDeBlumon = """{"status":true,"dataResponse":{"posId":12345,"contactless":true,"initializeKeys":true,"qps":false}}"""
        assertNull(runBlocking { cliente().iniciarSesion(10.seconds) })
        assertEquals(listOf("/oauth/token", "/device/init"), rutas())
    }

    @Test fun `sesion null si KCV no cuadra`() {
        for (malo in listOf(null, "", "AA", "000000", "ZZZZZZ")) {
            kcv = malo
            assertNull("$malo", runBlocking { cliente().iniciarSesion(10.seconds) })
        }
    }

    @Test fun `renovar el token solo pide el tokener`() {
        for (ambiente in Ambiente.values()) {
            pedidas.clear()
            assertEquals("$ambiente", "tok-de-prueba", runBlocking { cliente(ambiente).renovarToken(10.seconds)?.valor })
            assertEquals("$ambiente", listOf("/oauth/token"), rutas()) // ni init, ni getKey, ni initDukptKeys
        }
    }

    @Test fun `token produccion por query y sandbox por formulario`() {
        val esperada = MessageDigest.getInstance("SHA-256").digest("00000000011PAXA910S".toByteArray()).aHex()
        val basic = "Basic " + Base64.getEncoder().encodeToString("usuario-de-prueba:clave-de-prueba".toByteArray())

        runBlocking { cliente(Ambiente.PRODUCCION).iniciarSesion(10.seconds) }
        val produccion = pedidas.first()
        assertEquals("/oauth/token?grant_type=password&username=00000000011&password=$esperada", produccion.ruta)
        assertEquals(basic, produccion.autorizacion)
        assertEquals("{}", produccion.cuerpo)

        pedidas.clear()
        runBlocking { cliente(Ambiente.SANDBOX).iniciarSesion(10.seconds) }
        val sandbox = pedidas.first()
        assertEquals("/oauth/token", sandbox.ruta)
        assertEquals(basic, sandbox.autorizacion)
        assertTrue(sandbox.tipo.orEmpty().startsWith("application/x-www-form-urlencoded"))
        assertEquals("grant_type=password&username=00000000011&password=$esperada", sandbox.cuerpo)
    }

    /**
     * Bloque 4-L · L-b: la contraseña del token es SHA-256 de la serie de Blumon + "PAX" + el modelo de la identidad. Con
     * `A910S` es la de siempre (la misma cadena que fija `token produccion por query y sandbox por formulario`).
     */
    @Test fun `la contrasena del token usa la serie PAX y el modelo de la identidad`() {
        fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).aHex()
        for ((modelo, clave) in listOf("A910S" to sha("00000000011PAXA910S"), "A920" to sha("00000000011PAXA920"))) {
            pedidas.clear()
            runBlocking { cliente(modelo = modelo).renovarToken(10.seconds) }
            assertEquals(modelo, "grant_type=password&username=00000000011&password=$clave", pedidas.single().cuerpo)
        }
    }

    @Test fun `reverso usa el cuerpo y el token congelados`() {
        val venta = CuerpoDeBlumon.desde(JsonObject().apply {
            addProperty("reference", "0f8fad5bd9cb469fa165b0867728950e")
            addProperty("amount", BigDecimal("1.00"))
        })
        val congelado = CuerpoCongelado(venta, "tok-congelado")
        assertFalse(congelado.toString().contains("tok-congelado"))
        val r = runBlocking { cliente().reversar(congelado, 5.seconds, ahora() + 10_000) {} }
        assertTrue(r is Respuesta.Http)
        val pedida = pedidas.single()
        assertEquals("Bearer tok-congelado", pedida.autorizacion)
        val cuerpo = JsonParser.parseString(pedida.cuerpo).asJsonObject
        assertEquals("0f8fad5bd9cb469fa165b0867728950e", cuerpo.get("reference").asString)
        assertEquals(MOTIVO_DEL_REVERSO, cuerpo.get("reverseReason").asString)
        assertEquals("1.00", cuerpo.get("amount").asString)
        assertTrue(cuerpo.get("sendReverse").asBoolean)
    }

    @Test fun `reverso a retail present reverse con barra final`() {
        val congelado = CuerpoCongelado(CuerpoDeBlumon.desde(JsonObject().apply { addProperty("amount", 1) }), "tok")
        runBlocking { cliente().reversar(congelado, 5.seconds, ahora() + 10_000) {} }
        assertEquals("/retail/present/reverse/", pedidas.single().ruta)
    }

    @Test fun `cancelar a retail present cancel op`() {
        val sesion = SesionBlumon("tok-sesion", ByteArray(16), ByteArray(10), 0)
        val cuerpo = CuerpoDeBlumon.desde(JsonObject().apply { addProperty("amount", 1) })
        runBlocking {
            cliente().validarCancelacion(sesion, 12345, 5.seconds)
            cliente().cancelar(sesion, 12345, cuerpo, 5.seconds)
            cliente().cobrar(sesion, cuerpo, 5.seconds, ahora() + 10_000)
        }
        assertEquals(listOf("/device/validateCancelation", "/retail/present/cancel/12345", "/retail/present/charge"), rutas())
        assertTrue(pedidas.all { it.autorizacion == "Bearer tok-sesion" })
        val preflight = JsonParser.parseString(pedidas[0].cuerpo).asJsonObject
        assertEquals(12345, preflight.get("posId").asInt)
        assertEquals(12345L, preflight.get("operationNumber").asLong)
        // Un folio sin forma no sale.
        assertEquals(Respuesta.NoEnviada, runBlocking { cliente().cancelar(sesion, 0, cuerpo, 5.seconds) })
        assertEquals(3, pedidas.size)
    }

    @Test fun `descubrir no compara la identidad y solo trae kcv y base`() {
        identidadDeBlumon =
            """{"status":true,"dataResponse":{"posId":99999,"commerceName":"OTRO COMERCIO","contactless":true,"initializeKeys":true,"qps":true}}"""
        val d = runBlocking { cliente().descubrir(10.seconds) }!!
        assertEquals("99999", d.posId)
        assertEquals("OTRO COMERCIO", d.comercio)
        assertTrue(d.kcvCuadra)
        assertEquals("FFFF9876543210E00000", d.baseKsn)
        // Las llaves se piden con el posId que devolvió Blumon, no con el de la identidad.
        assertEquals(99999, JsonParser.parseString(pedidas.first { it.ruta == "/device/getKey" }.cuerpo).asJsonObject.get("posId").asInt)

        kcv = "000000"
        pedidas.clear()
        val malo = runBlocking { cliente().descubrir(10.seconds) }!!
        assertFalse(malo.kcvCuadra)
        assertEquals("FFFF9876543210E00000", malo.baseKsn)
    }

    @Test fun `credencial y sesion no se imprimen`() {
        assertFalse(credencial.toString().contains("clave-de-prueba"))
        assertFalse(credencial.toString().contains("usuario-de-prueba"))
        assertFalse(SesionBlumon("tok-x", ByteArray(16), ByteArray(10), 0).toString().contains("tok-x"))
        val material = MaterialDeSesion(ByteArray(16) { 0x4F }, ByteArray(10))
        assertFalse(material.toString().contains("4F") || material.toString().contains("79"))
    }

    // ---------- Piezas comunes · 3: lectores tipados de la sesión ----------

    @Test fun `init con qps repetido o con status repetido no da sesion`() {
        for (otra in listOf(
            """{"status":true,"dataResponse":{"posId":12345,"commerceName":"COMERCIO DE PRUEBA","contactless":true,"initializeKeys":true,"qps":true,"qps":false}}""",
            """{"status":true,"status":true,"dataResponse":{"posId":12345,"commerceName":"COMERCIO DE PRUEBA","contactless":true,"initializeKeys":true,"qps":false}}""",
            """{"status":true,"dataResponse":{"posId":12345,"commerceName":"COMERCIO DE PRUEBA","contactless":true,"initializeKeys":true,"qps":false},"extra":{"qps":false}}""",
        )) {
            pedidas.clear()
            identidadDeBlumon = otra
            assertNull(otra, runBlocking { cliente().iniciarSesion(10.seconds) })
            assertFalse(rutas().contains("/device/getKey"))
        }
    }

    @Test fun `token con access_token repetido o sin forma es null`() {
        for (t in listOf("""{"access_token":"a","access_token":"b"}""", """{"access_token":"con espacio"}""", """{"x":{"access_token":"a"}}""", """{"access_token":1}""")) {
            token = t
            assertNull(t, runBlocking { cliente().renovarToken(10.seconds) })
        }
    }

    @Test fun `initDukptKeys con status false o una clave repetida no da sesion`() {
        llaves = { it.replace("\"status\":true", "\"status\":false") }
        assertNull(runBlocking { cliente().iniciarSesion(10.seconds) })
        llaves = { it.replace("\"keyCheckValue\"", "\"keyCheckValue\":\"000000\",\"keyCheckValue\"") }
        assertNull(runBlocking { cliente().iniciarSesion(10.seconds) })
    }

    // ---------- Piezas comunes · 4: el material nace con su borrado (R3-4, v5-10) ----------

    @Test fun `el KCV incorrecto borra la IPEK`() {
        kcv = "000000"
        var nacida: ByteArray? = null
        val c = cliente().apply { conElMaterialNacido = { m -> nacida = m.usar { i, _ -> i } } }
        assertNull(runBlocking { c.iniciarSesion(10.seconds) })
        assertTrue("nació", nacida != null)
        assertTrue("en ceros", nacida!!.all { it == 0.toByte() })
    }

    /**
     * Un reloj que vence con el material ya nacido nunca lo deja suelto: o lo entrega o lo pone en ceros. Hoy no hay
     * `withTimeout` entre el nacimiento y el dueño, así que lo entrega aunque la espera ya pasó.
     */
    @Test fun `un plazo vencido despues de crear el material lo entrega o lo borra`() {
        var nacido: MaterialDeSesion? = null
        // Ola final, 4ª parte: 4 s (antes 1 s) para que las cuatro llamadas alcancen a crear el material en una máquina lenta; el
        // gancho sigue pasando el plazo con holgura, que es lo que se prueba.
        val c = cliente().apply { conElMaterialNacido = { m -> nacido = m; kotlinx.coroutines.delay(6_000) } }
        val sesion = runBlocking { c.iniciarSesion(4.seconds) }
        val m = requireNotNull(nacido)
        val entregado = sesion?.material === m && m.usar { i, _ -> i.any { it != 0.toByte() } } == true
        val borrado = m.usar { i, _ -> i } == null
        assertTrue("entregado o borrado", entregado || borrado)
    }

    @Test fun `una cancelacion con el material nacido lo borra`() {
        val nacido = kotlinx.coroutines.CompletableDeferred<ByteArray>()
        val c = cliente().apply {
            conElMaterialNacido = { m -> nacido.complete(m.usar { i, _ -> i }!!); kotlinx.coroutines.awaitCancellation() }
        }
        runBlocking {
            val trabajo = launch { c.iniciarSesion(10.seconds) }
            val ipek = nacido.await()
            trabajo.cancel()
            trabajo.join()
            assertTrue(ipek.all { it == 0.toByte() })
        }
    }
}

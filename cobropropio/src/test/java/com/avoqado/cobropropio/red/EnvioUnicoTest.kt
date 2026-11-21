package com.avoqado.cobropropio.red

import com.avoqado.cobropropio.blumon.CuerpoDeBlumon
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.Protocol
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

class EnvioUnicoTest {

    private val localhost = InetAddress.getByName("localhost")
    private val certificado = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val delServidor = HandshakeCertificates.Builder().heldCertificate(certificado).build()
    private val delCliente = HandshakeCertificates.Builder().addTrustedCertificate(certificado.certificate).build()
    private val server = MockWebServer().apply {
        useHttps(delServidor.sslSocketFactory(), false)
        start(localhost, 0)
    }
    private val ahora = { System.nanoTime() / 1_000_000 }
    private val dnsFijo = dns { listOf(localhost) }

    @After fun cerrar() = server.shutdown()

    private fun endpoints(puerto: Int) = Endpoints("https://localhost:$puerto/oauth/token", "https://localhost:$puerto/")
    private fun envio(puerto: Int = server.port, dns: Dns = dnsFijo, reloj: () -> Long = ahora) =
        EnvioUnico(endpoints(puerto), reloj, delCliente.trustManager, dns)

    /**
     * Ola final, 5ª parte (R24-5, r26): un reloj de PRUEBA para las pruebas del plazo. No avanza solo: la prueba lo mueve en el
     * punto exacto que quiere (el DNS o el gancho antes de escribir), así que ninguna pausa del runner cambia el veredicto. El
     * plazo queda 60 s adelante en ese reloj, lejos de cualquier temporizador real.
     */
    private class RelojDePrueba {
        val ms = AtomicLong(1_000_000)
        val plazo = ms.get() + 60_000
        fun vencer() = ms.set(plazo + 1)
    }
    private fun url(puerto: Int = server.port, ruta: String = "retail/present/charge") = "https://localhost:$puerto/$ruta"
    private fun cuerpo(relleno: Int = 0) = CuerpoDeBlumon.desde(
        JsonObject().apply {
            addProperty("reference", "0f8fad5bd9cb469fa165b0867728950e")
            if (relleno > 0) addProperty("relleno", "a".repeat(relleno))
        },
    )

    private fun dns(resolver: () -> List<InetAddress>) = object : Dns {
        override fun lookup(hostname: String) = resolver()
    }

    @Test fun `un 503 con Retry-After 0 no se reenvia`() {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setBody("{}"))
        val r = runBlocking { envio().post(url(), "Bearer prueba", cuerpo(), 5.seconds, null) }
        assertEquals(503, (r as Respuesta.Http).codigo)
        assertEquals(1, server.requestCount)
    }

    /** R1-3 / v5 §2.2: el cliente que copia cada llamada no trae nada ajeno (sólo el pinning y los plazos cambian). */
    @Test fun `el cliente base no trae nada ajeno`() {
        val c = envio().base
        assertTrue(c.interceptors.isEmpty() && c.networkInterceptors.isEmpty())
        assertSame(okhttp3.Authenticator.NONE, c.authenticator)
        assertSame(okhttp3.Authenticator.NONE, c.proxyAuthenticator)
        assertSame(okhttp3.CookieJar.NO_COOKIES, c.cookieJar)
        assertEquals(null, c.cache)
        assertFalse(c.followRedirects || c.followSslRedirects || c.retryOnConnectionFailure)
        assertTrue(c.dispatcher.maxRequestsPerHost >= 64)
        assertSame(okhttp3.EventListener.NONE, c.eventListenerFactory.create(c.newCall(okhttp3.Request.Builder().url(url()).build())))
        assertEquals(listOf(Protocol.HTTP_1_1), c.protocols)
    }

    @Test fun `no sigue redirecciones`() {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/device/init"))
        server.enqueue(MockResponse().setBody("{}"))
        val r = runBlocking { envio().post(url(), "Bearer prueba", cuerpo(), 5.seconds, null) }
        assertEquals(307, (r as Respuesta.Http).codigo)
        assertEquals(1, server.requestCount)
    }

    @Test fun `mas de 64 KB es SinRespuesta`() {
        server.enqueue(MockResponse().setBody("{\"x\":\"" + "a".repeat(70_000) + "\"}"))
        val r = runBlocking { envio().post(url(), "Bearer prueba", cuerpo(), 5.seconds, null) }
        assertTrue(r is Respuesta.SinRespuesta)
        assertEquals(1, server.requestCount)
    }

    @Test fun `origen fuera de la lista no sale`() {
        val otro = MockWebServer().apply { useHttps(delServidor.sslSocketFactory(), false); start(localhost, 0) }
        try {
            val e = envio()
            for (destino in listOf(
                url(puerto = otro.port),
                "https://example.com/retail/present/charge",
                url(ruta = "device/otra"),
                url(ruta = "retail/present/reverse"),
                url(ruta = "device/init?x=1"),
                "https://usuario:clave@localhost:${server.port}/retail/present/charge",
                "no es url",
            )) {
                assertSame(destino, Respuesta.NoEnviada, runBlocking { e.post(destino, null, cuerpo(), 5.seconds, null) })
            }
            assertEquals(0, server.requestCount)
            assertEquals(0, otro.requestCount)
        } finally { otro.shutdown() }
    }

    @Test fun `http sin s no sale`() {
        val r = runBlocking { envio().post("http://localhost:${server.port}/retail/present/charge", null, cuerpo(), 5.seconds, null) }
        assertSame(Respuesta.NoEnviada, r)
        assertEquals(0, server.requestCount)
        assertThrows(IllegalArgumentException::class.java) { Endpoints("http://localhost/oauth/token", "https://localhost/") }
        assertThrows(IllegalArgumentException::class.java) { Endpoints("https://localhost/oauth/token", "http://localhost/") }
    }

    @Test fun `cancelar la corrutina cancela la llamada`() {
        ServidorCrudo(delServidor).use { crudo ->
            runBlocking {
                val llamada = async { envio(puerto = crudo.puerto).post(url(crudo.puerto), "Bearer prueba", cuerpo(), 30.seconds, null) }
                // 5ª parte: estas dos miden tiempo real por naturaleza (que la petición llegue y que cancelar sea pronto); sólo se
                // hicieron holgadas. Sin cancelar de verdad, la llamada esperaría sus 30 s.
                val limite = ahora() + 20_000
                while (crudo.leidos.get() <= 0 && ahora() < limite) delay(10)
                assertTrue("la peticion llego", crudo.leidos.get() > 0)
                val antes = ahora()
                llamada.cancel()
                val cancelada = try { llamada.await(); false } catch (_: CancellationException) { true }
                assertTrue(cancelada)
                assertTrue("cancela pronto", ahora() - antes < 10_000)
            }
            // El servidor ve el socket cerrado: la llamada de OkHttp se canceló, no sólo la corrutina.
            assertTrue(crudo.terminado.get(3, TimeUnit.SECONDS) > 0)
        }
    }

    @Test fun `noEmpezarDespuesDe vencido da NoEnviada sin peticion`() {
        val r = runBlocking { envio().post(url(), "Bearer prueba", cuerpo(), 5.seconds, ahora() - 1) }
        assertSame(Respuesta.NoEnviada, r)
        assertEquals(0, server.requestCount)
    }

    @Test fun `techo en el transporte cancela antes de los headers`() {
        // Ola final, 5ª parte (r26): el plazo vence EN el DNS, con el transporte ya adentro (el medidor ya existe), en el reloj de
        // prueba. El techo real de la llamada (1 s: `espera`) la corta mientras el DNS sigue colgado. El veredicto no depende de
        // cuánto tarde ese techo: si llegara tarde y el DNS volviera, el primer write encuentra el plazo vencido y tampoco sale
        // un byte. La pausa del DNS sólo deja que el techo actúe primero.
        val reloj = RelojDePrueba()
        val lento = dns { reloj.vencer(); Thread.sleep(2_000); listOf(localhost) }
        val e = envio(dns = lento, reloj = reloj.ms::get)
        val r = runBlocking { e.post(url(), "Bearer prueba", cuerpo(), 1.seconds, reloj.plazo) }
        assertSame(Respuesta.NoEnviada, r)
        assertEquals(0L, e.bytesDelUltimoEnvio)
        Thread.sleep(200)
        assertEquals(0, server.requestCount)
    }

    @Test fun `pausa despues del ultimo chequeo no escribe`() {
        ServidorCrudo(delServidor).use { crudo ->
            val reloj = RelojDePrueba()
            val e = envio(puerto = crudo.puerto, reloj = reloj.ms::get)
            // Ya pasó la revisión previa al envío y el saludo TLS: justo antes del primer write, el plazo vence (5ª parte: en el
            // reloj de prueba, sin esperar tiempo real).
            e.ganchoAntesDeEscribir = { escritos -> if (escritos == 0L) reloj.vencer() }
            val r = runBlocking { e.post(url(crudo.puerto), "Bearer prueba", cuerpo(), 10.seconds, reloj.plazo) }
            assertSame(Respuesta.NoEnviada, r)
            assertEquals(0L, e.bytesDelUltimoEnvio)
            assertEquals("ni un byte de aplicacion", 0L, crudo.terminado.get(5, TimeUnit.SECONDS))
        }
    }

    @Test fun `el cliente de Blumon solo habla HTTP 1 1 y no reusa conexiones`() {
        server.protocols = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))
        val e = envio()
        runBlocking {
            e.post(url(), "Bearer prueba", cuerpo(), 5.seconds, null)
            e.post(url(), "Bearer prueba", cuerpo(), 5.seconds, null)
        }
        repeat(2) {
            val pedida = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertTrue(pedida.requestLine, pedida.requestLine.endsWith("HTTP/1.1"))
            assertEquals("cada llamada abre su propio socket", 0, pedida.sequenceNumber)
        }
    }

    @Test fun `plazo vencido despues de empezar a escribir es SinRespuesta y no NoEnviada`() {
        server.enqueue(MockResponse().setBody("{}"))
        val reloj = RelojDePrueba()
        val e = envio(reloj = reloj.ms::get)
        val pausado = AtomicBoolean(false)
        // 5ª parte: el plazo vence en el reloj de prueba después de los primeros bytes, sin esperar tiempo real.
        e.ganchoAntesDeEscribir = { escritos -> if (escritos > 0 && pausado.compareAndSet(false, true)) reloj.vencer() }
        val r = runBlocking { e.post(url(), "Bearer prueba", cuerpo(relleno = 200_000), 10.seconds, reloj.plazo) }
        assertTrue(r.toString(), r is Respuesta.SinRespuesta)
        assertTrue(e.bytesDelUltimoEnvio > 0)
    }

    @Test fun `cero bytes sin plazo vencido es SinRespuesta y no NoEnviada`() {
        val cerrado = java.net.ServerSocket(0, 1, localhost).use { it.localPort }
        val e = envio(puerto = cerrado)
        val r = runBlocking { e.post(url(cerrado), "Bearer prueba", cuerpo(), 5.seconds, ahora() + 60_000) }
        assertTrue(r.toString(), r is Respuesta.SinRespuesta)
        assertEquals(0L, e.bytesDelUltimoEnvio)
    }

    @Test fun `SinRespuesta no trae cuerpo ni url`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val r = runBlocking { envio().post(url(), "Bearer secreto", cuerpo(), 5.seconds, null) }
        assertTrue(r is Respuesta.SinRespuesta)
        val texto = r.toString()
        for (prohibido in listOf("localhost", "${server.port}", "retail", "secreto", "reference", "0f8fad5b")) {
            assertFalse(texto, texto.contains(prohibido))
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun `Http toString oculto`() {
        val texto = Respuesta.Http(200, "{\"data\":\"4111111111111111\",\"access_token\":\"tok\"}").toString()
        assertFalse(texto, texto.contains("4111"))
        assertFalse(texto, texto.contains("tok"))
        assertTrue(texto, texto.contains("200"))
    }

    /** Un servidor TLS crudo que sólo cuenta los bytes de aplicación que recibe (después del saludo TLS). */
    private class ServidorCrudo(certificados: HandshakeCertificates) : AutoCloseable {
        private val socket = certificados.sslContext().serverSocketFactory
            .createServerSocket(0, 1, InetAddress.getByName("localhost")) as SSLServerSocket
        val puerto: Int get() = socket.localPort
        val leidos = AtomicLong(0)
        val terminado = CompletableFuture<Long>()

        init {
            thread(isDaemon = true) {
                try {
                    (socket.accept() as SSLSocket).use { s ->
                        s.startHandshake()
                        val buf = ByteArray(4096)
                        try {
                            while (true) {
                                val k = s.inputStream.read(buf)
                                if (k < 0) break
                                leidos.addAndGet(k.toLong())
                            }
                        } catch (_: IOException) {
                        }
                        terminado.complete(leidos.get())
                    }
                } catch (_: IOException) {
                    terminado.complete(-1)
                }
            }
        }

        override fun close() = socket.close()
    }
}

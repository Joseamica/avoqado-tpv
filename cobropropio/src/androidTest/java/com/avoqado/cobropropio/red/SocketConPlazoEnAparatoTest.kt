package com.avoqado.cobropropio.red

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avoqado.cobropropio.blumon.CuerpoDeBlumon
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * Recomendación 2 de la revisión final de la fase 2: `SocketConPlazo` sobre el TLS REAL de Android (Conscrypt), que en la JVM no
 * se prueba. Sin red de afuera: un servidor TLS en 127.0.0.1 del propio aparato. Se corre en la PAX con
 * `./gradlew :cobropropio:connectedDebugAndroidTest`, con el aparato apartado (paso aparte). Nombres sin espacios: minSdk 27.
 */
@RunWith(AndroidJUnit4::class)
class SocketConPlazoEnAparatoTest {

    private val ip: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    /**
     * Codex r4 #6: Conscrypt de Android 9 no manda SNI para un nombre sin punto (`localhost`). El nombre del servidor de prueba
     * es `blumon.test`, que el DNS fijo resuelve a 127.0.0.1; va igual en la URL, en el certificado y en la aserción.
     */
    private val host = "blumon.test"
    private val certificado = HeldCertificate.Builder().addSubjectAlternativeName(host).build()
    private val delServidor = HandshakeCertificates.Builder().heldCertificate(certificado).build()
    private val delCliente = HandshakeCertificates.Builder().addTrustedCertificate(certificado.certificate).build()

    /** El reloj de la prueba: no avanza solo. El plazo de cada prueba se mide en ESTE reloj. */
    private val reloj = AtomicLong(1_000_000)

    private fun envio(puerto: Int) = EnvioUnico(
        Endpoints("https://$host:$puerto/oauth/token", "https://$host:$puerto/"), reloj::get, delCliente.trustManager,
        object : Dns {
            override fun lookup(hostname: String) = listOf(ip)
        },
    )

    private fun url(puerto: Int) = "https://$host:$puerto/retail/present/charge"

    private fun cuerpo() = CuerpoDeBlumon.desde(JsonObject().apply { addProperty("reference", "0f8fad5bd9cb469fa165b0867728950e") })

    /**
     * Una conexión: anota el SNI, lee los headers Y el cuerpo completo (`Content-Length`), y sólo entonces contesta `200 {}`.
     * [bytes] = los bytes de aplicación que llegaron, contados AL leerlos: si el socket falla a la mitad, conserva lo que ya llegó.
     * C6 (Codex b1 r1): [cabeceras] se leen byte por byte hasta su delimitador, SIN consumir nada del cuerpo; [cuerpo] se lee
     * después, aparte, exactamente `Content-Length` bytes. Lo que no alcanzó a llegar queda como cadena vacía.
     */
    private inner class Servidor : AutoCloseable {
        private val socket = delServidor.sslContext().serverSocketFactory.createServerSocket(0, 1, ip) as SSLServerSocket
        val puerto: Int get() = socket.localPort
        private val leidos = AtomicLong(0)
        val sni = CompletableFuture<List<SNIServerName>>()
        val cabeceras = CompletableFuture<String>()
        val cuerpo = CompletableFuture<String>()
        val bytes = CompletableFuture<Long>()

        init {
            thread(isDaemon = true) {
                try {
                    (socket.accept() as SSLSocket).use { s ->
                        s.startHandshake()
                        sni.complete((s.session as? ExtendedSSLSession)?.requestedServerNames.orEmpty())
                        val entrada = s.inputStream
                        val cab = StringBuilder()
                        while (!cab.endsWith("\r\n\r\n")) {
                            val b = entrada.read()
                            if (b < 0) return@use
                            leidos.incrementAndGet()
                            cab.append(b.toChar()) // ISO-8859-1: un byte, un carácter
                        }
                        cabeceras.complete(cab.toString())
                        val largo = Regex("(?i)\r\ncontent-length: *([0-9]+)").find(cab)?.groupValues?.get(1)?.toInt() ?: 0
                        val leido = ByteArrayOutputStream()
                        val buf = ByteArray(4096)
                        while (leido.size() < largo) {
                            val k = entrada.read(buf, 0, minOf(buf.size, largo - leido.size()))
                            if (k < 0) return@use
                            leidos.addAndGet(k.toLong())
                            leido.write(buf, 0, k)
                        }
                        cuerpo.complete(leido.toString("ISO-8859-1"))
                        s.outputStream.write(
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}"
                                .toByteArray(Charsets.ISO_8859_1),
                        )
                        s.outputStream.flush()
                    }
                } catch (_: IOException) {
                } finally {
                    sni.complete(emptyList())
                    cabeceras.complete("")
                    cuerpo.complete("")
                    bytes.complete(leidos.get())
                }
            }
        }

        override fun close() = socket.close()
    }

    /** El saludo TLS con SNI y un POST COMPLETO (headers + cuerpo) por HTTP/1.1 con su respuesta, contando bytes. */
    @Test fun ventaSalePorHttp11ConSniYCuentaSusBytes() {
        Servidor().use { srv ->
            val e = envio(srv.puerto)
            val r = runBlocking { e.post(url(srv.puerto), "Bearer prueba", cuerpo(), 10.seconds, reloj.get() + 60_000) }
            assertTrue(r.toString(), r is Respuesta.Http && r.codigo == 200)
            val cabeceras = srv.cabeceras.get(5, TimeUnit.SECONDS)
            assertTrue(cabeceras, cabeceras.startsWith("POST /retail/present/charge HTTP/1.1\r\n"))
            val esperado = "{\"reference\":\"0f8fad5bd9cb469fa165b0867728950e\"}"
            val declarado = Regex("(?i)\r\ncontent-length: *([0-9]+)").find(cabeceras)?.groupValues?.get(1)?.toInt()
            assertEquals("Content-Length", esperado.length, declarado)
            assertEquals("el cuerpo exacto, leído aparte de las cabeceras", esperado, srv.cuerpo.get(5, TimeUnit.SECONDS))
            assertTrue("SNI", SNIHostName(host) in srv.sni.get(5, TimeUnit.SECONDS))
            assertTrue(e.bytesDelUltimoEnvio > 0)
        }
    }

    /** Una escritura después del plazo no llega al servidor: `NoEnviada`, cero bytes contados por los dos lados. */
    @Test fun unaEscrituraDespuesDelPlazoNoLlegaAlServidor() {
        Servidor().use { srv ->
            val plazo = reloj.get() + 60_000
            val e = envio(srv.puerto)
            e.ganchoAntesDeEscribir = { escritos -> if (escritos == 0L) reloj.set(plazo + 1) }
            val r = runBlocking { e.post(url(srv.puerto), "Bearer prueba", cuerpo(), 10.seconds, plazo) }
            assertSame(Respuesta.NoEnviada, r)
            assertEquals(0L, e.bytesDelUltimoEnvio)
            assertEquals("ni un byte de aplicación", 0L, srv.bytes.get(5, TimeUnit.SECONDS))
        }
    }
}

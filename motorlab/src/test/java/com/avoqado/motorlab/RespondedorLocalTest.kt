package com.avoqado.motorlab

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InterruptedIOException
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Bloque 4-L · L-d1: a toda petición, `404` sin cuerpo DESPUÉS de leerla entera (Ruling 2). Bloque P0: con el mudo, la lee
 * entera y no contesta, con plazo ABSOLUTO.
 */
class RespondedorLocalTest {
    private val respondedor = RespondedorLocal()
    private val cliente = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()

    @After fun cerrar() = respondedor.close()

    private fun post(ruta: String, cuerpo: String, r: RespondedorLocal = respondedor, c: OkHttpClient = cliente): Pair<Int, String> =
        c.newCall(
            Request.Builder().url("http://127.0.0.1:${r.puerto}$ruta").header("Authorization", "Bearer laboratorio")
                .post(cuerpo.toRequestBody("application/json; charset=utf-8".toMediaType())).build(),
        ).execute().use { it.code to it.body!!.string() }

    /** Las dos rutas que arma el motor (`ReversoDelServidorHttp.kt:187-191`): 404 SIN cuerpo; lo demás cuenta como «otra». */
    @Test fun `las dos rutas del motor reciben 404 sin cuerpo y se cuentan`() {
        val base = "/api/v1/tpv/venues/$VENUE/terminal-payment/attempts/0123456789abcdef0123456789abcdef/reverso"
        assertEquals(404 to "", post("$base/intencion", "{}"))
        assertEquals(404 to "", post("$base/resultado", """{"descripcion":"REVERSADA","reversadoEn":"2026-11-23T00:00:00Z"}"""))
        assertEquals(404 to "", post("/api/v1/otra", "{}"))
        assertEquals("intencion 1 · resultado 1 · otra 1 · mudo 0", respondedor.resumen())
    }

    /** Hasta el ÚLTIMO byte del cuerpo y ni uno más; un cuerpo cortado no se contesta. Sin relojes. */
    @Test fun `la peticion se lee entera antes de contestar`() {
        val cabeza = "POST /x/reverso/intencion HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 10\r\n\r\n"
        val entera = ByteArrayInputStream((cabeza + "0123456789" + "SIGUE").toByteArray(Charsets.US_ASCII))
        assertEquals("/x/reverso/intencion", leerPeticion(entera))
        assertEquals(5, entera.available())
        assertNull(leerPeticion(ByteArrayInputStream((cabeza + "01234").toByteArray(Charsets.US_ASCII))))
    }

    /**
     * Sin forma no se cuenta ni se contesta (el contador «intencion» de las actas no miente). La cabecera termina en `\n` a secas:
     * así sus caracteres son exactamente los que mide el tope de 8 KiB.
     */
    @Test fun `una linea de mas de 8 KiB o un Content-Length sin forma no tiene ruta`() {
        fun conCabecera(c: String, cuerpo: String = "") =
            leerPeticion(ByteArrayInputStream("POST /x HTTP/1.1\r\n$c\n\r\n$cuerpo".toByteArray(Charsets.US_ASCII)))
        assertEquals("/x", conCabecera("X: " + "a".repeat(8_192 - 3)))
        assertNull(conCabecera("X: " + "a".repeat(8_193 - 3)))
        for (malo in listOf("abc", "-1")) assertNull(malo, conCabecera("Content-Length: $malo"))
        // El cuerpo llega completo: sólo el tope de 64 KiB lo rechaza, no el fin del flujo.
        assertNull("65537", conCabecera("Content-Length: 65537", "a".repeat(65_537)))
    }

    /** Sólo en 127.0.0.1; cerrado, ya no acepta (en 127.0.0.1 el rechazo es inmediato). */
    @Test fun `escucha solo en 127 0 0 1 y cerrado ya no acepta`() {
        assertEquals(LOCAL, respondedor.direccion)
        Socket(LOCAL, respondedor.puerto).close()
        respondedor.close()
        assertTrue(runCatching { Socket(LOCAL, respondedor.puerto).close() }.isFailure)
    }

    /** Lo que el motor manda tras un reverso confirmado (`ReversoDelServidorHttp.kt:47`, 10 s de plazo). */
    @Test fun `mudo acepta y no contesta`() {
        respondedor.mudo = true
        val impaciente = cliente.newBuilder().callTimeout(2, TimeUnit.SECONDS).build()
        val falla = runCatching { post(RESULTADO, "{}", c = impaciente) }.exceptionOrNull()
        assertTrue("$falla", falla is InterruptedIOException)
        assertEquals(1, respondedor.contestadas("mudo"))
        assertEquals(0, respondedor.contestadas("resultado"))
        val r = respondedor.retencion().toString()
        assertTrue(r, r.startsWith("retenida desde") || r.startsWith("última retención"))
    }

    /** Un cliente que gotea un byte cada 200 ms no retiene el hilo: el plazo cuenta desde que empezó la retención, no por lectura. */
    @Test fun `la retencion tiene plazo absoluto`() {
        RespondedorLocal(retencionMs = 1_000).use { r ->
            r.mudo = true
            val desde = System.nanoTime()
            val crudo = crudo(r)
            val gotero = thread(isDaemon = true) {
                runCatching { while (true) { Thread.sleep(200); crudo.getOutputStream().apply { write('x'.code); flush() } } }
            }
            try {
                esperaRetencion(r)
                r.mudo = false
                assertEquals(404 to "", post(RESULTADO, "{}", r))
                val ms = (System.nanoTime() - desde) / 1_000_000
                assertTrue("$ms ms", ms < 3_000)
            } finally {
                gotero.interrupt()
                crudo.close()
            }
        }
    }

    /** Apagar el mudo no suelta la retenida; la siguiente petición sí recibe su 404. */
    @Test fun `quitar el mudo vuelve a contestar 404`() {
        `mudo acepta y no contesta`()
        respondedor.mudo = false
        val desde = System.nanoTime()
        assertEquals(404 to "", post(RESULTADO, "{}"))
        val ms = (System.nanoTime() - desde) / 1_000_000
        assertTrue("$ms ms", ms < 2_000)
        val r = respondedor.retencion().toString()
        assertTrue(r, r.startsWith("última retención"))
    }

    /** Sin carrera: el socket se cierra YA retenido; el 404 llega muy antes de los 10 s del plazo, así que lo soltó la salida. */
    @Test fun `un cliente que se va libera el hilo`() {
        RespondedorLocal(retencionMs = 10_000).use { r ->
            r.mudo = true
            val crudo = crudo(r)
            esperaRetencion(r)
            crudo.close()
            r.mudo = false
            val desde = System.nanoTime()
            assertEquals(404 to "", post(RESULTADO, "{}", r))
            val ms = (System.nanoTime() - desde) / 1_000_000
            assertTrue("$ms ms", ms < 2_000)
        }
    }

    /** Una petición completa por un socket crudo, que queda abierto. */
    private fun crudo(r: RespondedorLocal) = Socket(LOCAL, r.puerto).apply {
        getOutputStream().apply {
            write("POST $RESULTADO HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 2\r\n\r\n{}".toByteArray(Charsets.US_ASCII))
            flush()
        }
    }

    /** Hasta 2 s a que [r] empiece a retener (se cuenta «mudo» al empezar): después, la conexión ya está retenida. */
    private fun esperaRetencion(r: RespondedorLocal) {
        val hasta = System.nanoTime() + 2_000_000_000L
        while (r.contestadas("mudo") < 1) {
            assertTrue("no empezó a retener en 2 s", System.nanoTime() < hasta)
            Thread.sleep(10)
        }
    }

    private companion object {
        const val RESULTADO = "/x/reverso/resultado"
    }
}

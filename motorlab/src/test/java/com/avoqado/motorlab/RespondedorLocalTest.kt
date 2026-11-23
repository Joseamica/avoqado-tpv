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
import java.net.Proxy
import java.net.Socket

/** Bloque 4-L · L-d1: a toda petición, `404` sin cuerpo DESPUÉS de leerla entera (Ruling 2). */
class RespondedorLocalTest {
    private val respondedor = RespondedorLocal()
    private val cliente = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()

    @After fun cerrar() = respondedor.close()

    private fun post(ruta: String, cuerpo: String): Pair<Int, String> = cliente.newCall(
        Request.Builder().url("http://127.0.0.1:${respondedor.puerto}$ruta").header("Authorization", "Bearer laboratorio")
            .post(cuerpo.toRequestBody("application/json; charset=utf-8".toMediaType())).build(),
    ).execute().use { it.code to it.body!!.string() }

    /** Las dos rutas que arma el motor (`ReversoDelServidorHttp.kt:187-191`): 404 SIN cuerpo; lo demás cuenta como «otra». */
    @Test fun `las dos rutas del motor reciben 404 sin cuerpo y se cuentan`() {
        val base = "/api/v1/tpv/venues/$VENUE/terminal-payment/attempts/0123456789abcdef0123456789abcdef/reverso"
        assertEquals(404 to "", post("$base/intencion", "{}"))
        assertEquals(404 to "", post("$base/resultado", """{"descripcion":"REVERSADA","reversadoEn":"2026-11-23T00:00:00Z"}"""))
        assertEquals(404 to "", post("/api/v1/otra", "{}"))
        assertEquals("intencion 1 · resultado 1 · otra 1", respondedor.resumen())
    }

    /** Hasta el ÚLTIMO byte del cuerpo y ni uno más; un cuerpo cortado no se contesta. Sin relojes. */
    @Test fun `la peticion se lee entera antes de contestar`() {
        val cabeza = "POST /x/reverso/intencion HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 10\r\n\r\n"
        val entera = ByteArrayInputStream((cabeza + "0123456789" + "SIGUE").toByteArray(Charsets.US_ASCII))
        assertEquals("/x/reverso/intencion", leerPeticion(entera))
        assertEquals(5, entera.available())
        assertNull(leerPeticion(ByteArrayInputStream((cabeza + "01234").toByteArray(Charsets.US_ASCII))))
    }

    /** Sólo en 127.0.0.1; cerrado, ya no acepta (en 127.0.0.1 el rechazo es inmediato). */
    @Test fun `escucha solo en 127 0 0 1 y cerrado ya no acepta`() {
        assertEquals(LOCAL, respondedor.direccion)
        Socket(LOCAL, respondedor.puerto).close()
        respondedor.close()
        assertTrue(runCatching { Socket(LOCAL, respondedor.puerto).close() }.isFailure)
    }
}

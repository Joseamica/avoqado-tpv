package com.jaac.avoqado_campo.cola

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SubidorDeFotosTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer().apply { start() }
    private val fotos = SubidorDeFotos(OkHttpClient(), server.url("/").toString())
    private val ruta = "dev/venues/v1/campo/checada/2026-11-10/k_selfie.jpg"

    @After fun cerrar() = server.shutdown()

    private fun foto(bytes: Int): FotoPorSubir {
        val f = tmp.newFile().also { it.writeBytes(ByteArray(bytes) { 3 }) }
        return FotoPorSubir(f.absolutePath, ruta, urlDe(ruta), bytes.toLong(), subida = false)
    }

    private fun subir(r: MockResponse, f: FotoPorSubir = foto(19_945)) = runBlocking {
        server.enqueue(r)
        fotos.subir(f)
    }

    @Test fun `la url va sin token y con la ruta codificada como componente`() {
        assertEquals(
            "https://firebasestorage.googleapis.com/v0/b/avoqado-d0a24.appspot.com/o/" +
                "dev%2Fvenues%2Fv1%2Fcampo%2Fchecada%2F2026-11-10%2Fk_selfie.jpg?alt=media",
            urlDe(ruta),
        )
    }

    @Test fun `200 con contentType y size que cuadran es Subida y manda los bytes como image jpeg`() {
        val f = foto(19_945)
        assertEquals(ResultadoFoto.Subida, subir(MockResponse().setBody("""{"contentType":"image/jpeg","size":"19945"}"""), f))
        val p = server.takeRequest()
        assertEquals("POST", p.method)
        assertEquals("/v0/b/avoqado-d0a24.appspot.com/o?name=dev%2Fvenues%2Fv1%2Fcampo%2Fchecada%2F2026-11-10%2Fk_selfie.jpg", p.path)
        assertEquals("image/jpeg", p.getHeader("Content-Type"))
        assertArrayEquals(java.io.File(f.archivo).readBytes(), p.body.readByteArray())
    }

    @Test fun `size como numero tambien cuadra`() =
        assertEquals(ResultadoFoto.Subida, subir(MockResponse().setBody("""{"contentType":"image/jpeg","size":19945}""")))

    @Test fun `200 que no cuadra, html, 5xx o 429 es Reintentable`() {
        listOf(
            MockResponse().setBody("""{"contentType":"image/jpeg","size":"10"}"""),
            MockResponse().setBody("""{"contentType":"text/plain","size":"19945"}"""),
            MockResponse().setBody("<html><body>WiFi</body></html>"),
            MockResponse().setResponseCode(503),
            MockResponse().setResponseCode(429),
        ).forEach { assertEquals(ResultadoFoto.Reintentable, subir(it)) }
    }

    @Test fun `403 es Definitiva`() =
        assertTrue(subir(MockResponse().setResponseCode(403).setBody("""{"error":{"code":403}}""")) is ResultadoFoto.Definitiva)

    @Test fun `sin red es Reintentable`() {
        server.shutdown()
        assertEquals(ResultadoFoto.Reintentable, runBlocking { fotos.subir(foto(5)) })
    }

    @Test fun `el archivo que ya no existe es Definitiva sin llamar`() = runBlocking {
        val f = foto(5).also { java.io.File(it.archivo).delete() }
        assertEquals(ResultadoFoto.Definitiva("La foto ya no está en el celular"), fotos.subir(f))
        assertEquals(0, server.requestCount)
    }
}

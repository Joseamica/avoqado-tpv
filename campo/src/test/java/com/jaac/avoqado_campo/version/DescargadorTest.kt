package com.jaac.avoqado_campo.version

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DescargadorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer().apply { start() }
    private val bytes = ByteArray(200_000) { (it % 251).toByte() }

    @After fun cerrar() = server.shutdown()

    private fun carpeta() = File(tmp.root, "actualizacion")
    private fun version(url: String = server.url("/campo-4.apk").toString(), tamano: Long = bytes.size.toLong()) =
        VersionNueva(4, "0.3.1", url, "ab".repeat(32), tamano, false, null)

    private fun archivos() = carpeta().listFiles()?.map { it.name }?.sorted().orEmpty()

    @Test fun `completa queda como apk y sin parcial`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val b = Descargador(carpeta(), soloHttps = false).bajar(version())
        assertTrue(b is Bajada.Lista)
        assertArrayEquals(bytes, (b as Bajada.Lista).archivo.readBytes())
        assertEquals(listOf("campo-4.apk"), archivos())
    }

    @Test fun `cuerpo cortado no deja apk ni parcial y es SinRed`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        assertEquals(Bajada.SinRed, Descargador(carpeta(), soloHttps = false).bajar(version()))
        assertEquals(emptyList<String>(), archivos())
    }

    @Test fun `un archivo de otro tamano que el anunciado no se queda (SinRed)`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes.copyOf(1000))))
        assertEquals(Bajada.SinRed, Descargador(carpeta(), soloHttps = false).bajar(version()))
        assertEquals(emptyList<String>(), archivos())
    }

    @Test fun `borra lo previo antes de bajar`() = runBlocking {
        carpeta().mkdirs()
        File(carpeta(), "campo-3.apk").writeText("vieja")
        File(carpeta(), "campo-4.part").writeText("a medias")
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        Descargador(carpeta(), soloHttps = false).bajar(version())
        assertEquals(listOf("campo-4.apk"), archivos())
    }

    @Test fun `http en release es Mala y no toca la red`() = runBlocking {
        val b = Descargador(carpeta(), soloHttps = true).bajar(version())
        assertTrue(b is Bajada.Mala)
        assertEquals(0, server.requestCount)
    }

    @Test fun `5xx es SinRed y 404 es Mala`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(Bajada.SinRed, Descargador(carpeta(), soloHttps = false).bajar(version()))
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(Descargador(carpeta(), soloHttps = false).bajar(version()) is Bajada.Mala)
        assertEquals(emptyList<String>(), archivos())
    }

    @Test fun `limpiar borra la carpeta`() {
        carpeta().mkdirs()
        File(carpeta(), "campo-4.apk").writeText("x")
        Descargador(carpeta(), soloHttps = false).limpiar()
        assertEquals(emptyList<String>(), archivos())
    }
}

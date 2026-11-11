package com.jaac.avoqado_campo.version

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.jaac.avoqado_campo.BuildConfig
import com.jaac.avoqado_campo.red.CampoApi
import com.jaac.avoqado_campo.red.RedModule
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RevisorDeVersionTest {
    private val server = MockWebServer()
    private lateinit var api: CampoApi
    private lateinit var prefs: PrefsQueCuentan
    private var mono = 1_000L

    @Before fun setUp() {
        server.start()
        api = RedModule.crearRetrofit(server.url("/api/v1/").toString(), OkHttpClient()).create(CampoApi::class.java)
        val real = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("version_de_campo", Context.MODE_PRIVATE)
        real.edit().clear().commit()
        prefs = PrefsQueCuentan(real)
    }

    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun revisor(instalada: Int = 3, p: SharedPreferences = prefs) = RevisorDeVersion(api, p, instalada, "SANDBOX") { mono }

    private fun sinActualizacion() = MockResponse().setBody("""{"success":true,"hasUpdate":false,"message":"You are on the latest version"}""")

    private fun conActualizacion(code: Int, min: Int?, sha: String = "ab".repeat(32), modo: String = "BANNER") = MockResponse().setBody(
        """{"success":true,"hasUpdate":true,"minVersionCode":${min ?: "null"},"update":{"id":"u$code","versionName":"0.3.$code",
           "versionCode":$code,"downloadUrl":"https://storage.example/campo-$code.apk","fileSize":"1234","checksum":"$sha",
           "releaseNotes":"notas $code","updateMode":"$modo","minAndroidSdk":27,"publishedAt":"2026-11-11T00:00:00.000Z"}}""",
    )

    @Test fun `revisar manda platform ANDROID_CAMPO, environment y currentVersion de la app, sin cabeceras de version`() = runBlocking {
        server.enqueue(sinActualizacion())
        assertTrue(RevisorDeVersion(api, prefs) { mono }.revisar())
        val r = server.takeRequest()
        assertEquals("/api/v1/tpv/check-update", r.requestUrl!!.encodedPath)
        assertEquals("ANDROID_CAMPO", r.requestUrl!!.queryParameter("platform"))
        assertEquals(BuildConfig.AMBIENTE_DE_VERSIONES, r.requestUrl!!.queryParameter("environment"))
        assertEquals(BuildConfig.VERSION_CODE.toString(), r.requestUrl!!.queryParameter("currentVersion"))
        assertNull(r.getHeader("X-App-Version-Code"))
        assertNull(r.getHeader("X-App-Version-Name"))
    }

    @Test fun `con version nueva la ofrece con sus datos`() = runBlocking {
        server.enqueue(conActualizacion(4, min = null))
        val rv = revisor()
        rv.revisar()
        assertEquals(VersionNueva(4, "0.3.4", "https://storage.example/campo-4.apk", "ab".repeat(32), 1234, false, "notas 4"), rv.nueva.value)
        assertFalse(rv.obligatoria.value)
    }

    @Test fun `hasUpdate false limpia la nueva y la obligacion`() = runBlocking {
        server.enqueue(conActualizacion(4, min = 4))
        server.enqueue(sinActualizacion())
        val rv = revisor()
        rv.revisar()
        assertTrue(rv.obligatoria.value)
        rv.revisar(forzar = true)
        assertNull(rv.nueva.value)
        assertFalse(rv.obligatoria.value)
        assertNull(revisor().nueva.value) // y tampoco vuelve al arrancar
        assertFalse(revisor().obligatoria.value)
    }

    @Test fun `sin red conserva la ultima guardada`() = runBlocking {
        server.enqueue(conActualizacion(4, min = 4))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val rv = revisor()
        rv.revisar()
        assertFalse(rv.revisar(forzar = true))
        assertEquals(4, rv.nueva.value?.versionCode)
        assertTrue(rv.obligatoria.value)
    }

    @Test fun `un portal cautivo (html) tampoco borra lo guardado`() = runBlocking {
        server.enqueue(conActualizacion(4, min = 4))
        server.enqueue(MockResponse().setBody("<html>WiFi</html>"))
        val rv = revisor()
        rv.revisar()
        assertFalse(rv.revisar(forzar = true))
        assertEquals(4, rv.nueva.value?.versionCode)
        assertTrue(rv.obligatoria.value)
    }

    @Test fun `no consulta dos veces en 30 min, salvo forzar`() = runBlocking {
        repeat(4) { server.enqueue(sinActualizacion()) }
        val rv = revisor()
        assertTrue(rv.revisar())
        mono += 29 * 60_000L
        assertFalse(rv.revisar())
        assertEquals(1, server.requestCount)
        assertTrue(rv.revisar(forzar = true))
        assertEquals(2, server.requestCount)
        mono += 30 * 60_000L
        assertTrue(rv.revisar())
        assertEquals(3, server.requestCount)
    }

    @Test fun `minVersionCode mayor que la instalada la hace obligatoria aunque la ofrecida sea BANNER`() = runBlocking {
        // FORCE 4 y despues BANNER 5, con la 3 instalada: se ofrece la 5 y sigue obligada (Codex r1 #3).
        server.enqueue(conActualizacion(5, min = 4, modo = "BANNER"))
        val rv = revisor()
        rv.revisar()
        assertEquals(5, rv.nueva.value?.versionCode)
        assertTrue(rv.nueva.value!!.obligatoria)
        assertTrue(rv.obligatoria.value)
    }

    @Test fun `sin minVersionCode no es obligatoria aunque la fila sea FORCE`() = runBlocking {
        server.enqueue(conActualizacion(4, min = null, modo = "FORCE"))
        val rv = revisor()
        rv.revisar()
        assertFalse(rv.nueva.value!!.obligatoria)
        assertFalse(rv.obligatoria.value)
    }

    @Test fun `FORCE 4 y BANNER 5 invalida - sigue obligatoria y no vuelve a ofrecer bajar la 5`() = runBlocking {
        repeat(2) { server.enqueue(conActualizacion(5, min = 4)) }
        val rv = revisor()
        rv.revisar()
        rv.marcarInvalida(5)
        assertNull(rv.nueva.value)
        assertTrue(rv.obligatoria.value)
        rv.revisar(forzar = true) // el SERVIDOR la sigue mandando
        assertNull(rv.nueva.value)
        assertTrue(rv.obligatoria.value)
        val otroArranque = revisor()
        assertNull(otroArranque.nueva.value)
        assertTrue(otroArranque.obligatoria.value)
    }

    @Test fun `el minimo obligatorio se guarda con commit - proceso nuevo sin red sigue obligada`() = runBlocking {
        server.enqueue(conActualizacion(4, min = 4))
        revisor().revisar()
        assertTrue("el mínimo va en un commit, no en apply", prefs.clavesConCommit.contains(RevisorDeVersion.MINIMO))
        assertFalse(prefs.clavesConApply.contains(RevisorDeVersion.MINIMO))
        server.shutdown() // proceso nuevo, sin red
        val nuevo = revisor()
        assertTrue(nuevo.obligatoria.value)
        assertFalse(nuevo.revisar(forzar = true))
        assertTrue(nuevo.obligatoria.value)
    }

    @Test fun `si el commit del minimo falla, el candado sigue en memoria`() = runBlocking {
        server.enqueue(conActualizacion(4, min = 4))
        prefs.commitFalla = true
        val rv = revisor()
        rv.revisar()
        assertTrue(rv.obligatoria.value)
    }

    @Test fun `respuesta guardada con versionCode igual o menor que la instalada se descarta al arrancar sin red`() = runBlocking {
        // La 3 guardó «FORCE 4»; se actualizó a la 4 y el proceso nuevo arranca sin red: ni aviso ni candado.
        server.enqueue(conActualizacion(4, min = 4))
        revisor(instalada = 3).revisar()
        server.shutdown()
        val ya4 = revisor(instalada = 4)
        assertNull(ya4.nueva.value)
        assertFalse(ya4.obligatoria.value)
        assertFalse(ya4.revisar(forzar = true))
        assertNull(ya4.nueva.value)
        assertFalse(ya4.obligatoria.value)
    }

    @Test fun `un minimo ya cumplido se descarta aunque quede una opcional mas nueva`() = runBlocking {
        server.enqueue(conActualizacion(5, min = 4))
        revisor(instalada = 3).revisar()
        server.shutdown()
        val ya4 = revisor(instalada = 4)
        assertNotNull(ya4.nueva.value)
        assertFalse(ya4.nueva.value!!.obligatoria)
        assertFalse(ya4.obligatoria.value)
    }
}

/** SharedPreferences reales que anotan qué claves se escribieron con `commit` y cuáles con `apply` (y pueden fallar el commit). */
class PrefsQueCuentan(private val real: SharedPreferences) : SharedPreferences by real {
    val clavesConCommit = mutableSetOf<String>()
    val clavesConApply = mutableSetOf<String>()
    var commitFalla = false

    override fun edit(): SharedPreferences.Editor = Editor(real.edit())

    private inner class Editor(private val e: SharedPreferences.Editor) : SharedPreferences.Editor by e {
        private val claves = mutableSetOf<String>()
        override fun putString(k: String, v: String?) = apply { claves += k; e.putString(k, v) }
        override fun putStringSet(k: String, v: MutableSet<String>?) = apply { claves += k; e.putStringSet(k, v) }
        override fun putInt(k: String, v: Int) = apply { claves += k; e.putInt(k, v) }
        override fun putLong(k: String, v: Long) = apply { claves += k; e.putLong(k, v) }
        override fun remove(k: String) = apply { claves += k; e.remove(k) }
        override fun commit(): Boolean {
            clavesConCommit += claves
            return if (commitFalla) false else e.commit()
        }
        override fun apply() {
            clavesConApply += claves
            e.apply()
        }
    }
}

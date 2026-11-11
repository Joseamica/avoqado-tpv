package com.jaac.avoqado_campo.version

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jaac.avoqado_campo.red.CampoApi
import com.jaac.avoqado_campo.red.RedModule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Bajar → comprobar → instalar, con el Revisor de verdad (FORCE 4 + BANNER 5 con la 3 instalada). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActualizadorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private lateinit var revisor: RevisorDeVersion
    private val descargador = mockk<Descargador>()
    private val comprobador = mockk<Comprobador>()
    private val instalador = mockk<Instalador>(relaxed = true)
    private lateinit var act: Actualizador
    private lateinit var archivo: File

    @Before fun setUp() = runBlocking {
        server.start()
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"hasUpdate":true,"minVersionCode":4,"update":{"versionName":"0.3.5","versionCode":5,
                   "downloadUrl":"https://storage.example/campo-5.apk","fileSize":"10","checksum":"${"ab".repeat(32)}","updateMode":"BANNER"}}""",
            ),
        )
        val api = RedModule.crearRetrofit(server.url("/api/v1/").toString(), OkHttpClient()).create(CampoApi::class.java)
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("version_de_campo", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        revisor = RevisorDeVersion(api, prefs, 3, "SANDBOX") { 0L }
        revisor.revisar()
        archivo = tmp.newFile("campo-5.apk").also { it.writeText("apk") }
        act = Actualizador(revisor, descargador, comprobador, instalador, CoroutineScope(Dispatchers.Unconfined))
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `la que no pasa la revision queda invalida, se borra, no se instala y la obligatoria sigue`() = runBlocking {
        coEvery { descargador.bajar(any()) } returns Bajada.Lista(archivo)
        every { comprobador.comprobar(archivo, any()) } returns Bajada.Mala("el archivo está firmado con otra llave")
        act.actualizar()
        assertNull("no se vuelve a ofrecer", revisor.nueva.value)
        assertTrue(revisor.obligatoria.value)
        assertEquals(PasoDeActualizacion.Fallo(NO_SE_PUDO_COMPROBAR), act.paso.value)
        assertFalse(archivo.exists())
        coVerify(exactly = 0) { instalador.instalar(any()) }
    }

    @Test fun `una liga mala al bajar tambien la deja invalida`() = runBlocking {
        coEvery { descargador.bajar(any()) } returns Bajada.Mala("la liga no es https")
        act.actualizar()
        assertNull(revisor.nueva.value)
        assertEquals(PasoDeActualizacion.Fallo(NO_SE_PUDO_COMPROBAR), act.paso.value)
    }

    @Test fun `sin red al bajar no la marca invalida y se puede reintentar`() = runBlocking {
        coEvery { descargador.bajar(any()) } returns Bajada.SinRed
        act.actualizar()
        assertEquals(5, revisor.nueva.value?.versionCode)
        assertEquals(PasoDeActualizacion.Fallo(NO_SE_PUDO_BAJAR), act.paso.value)
        coVerify(exactly = 0) { instalador.instalar(any()) }
    }

    @Test fun `buena se manda a instalar`() = runBlocking {
        coEvery { descargador.bajar(any()) } returns Bajada.Lista(archivo)
        every { comprobador.comprobar(archivo, any()) } returns Bajada.Lista(archivo)
        coEvery { instalador.instalar(archivo) } returns ResultadoInstalacion.Enviado
        act.actualizar()
        assertEquals(PasoDeActualizacion.Instalando, act.paso.value)
        coVerify(exactly = 1) { instalador.instalar(archivo) }
    }

    @Test fun `sin permiso espera, y al regresar con permiso instala sin volver a bajar`() = runBlocking {
        coEvery { descargador.bajar(any()) } returns Bajada.Lista(archivo)
        every { comprobador.comprobar(archivo, any()) } returns Bajada.Lista(archivo)
        coEvery { instalador.instalar(archivo) } returnsMany listOf(ResultadoInstalacion.FaltaPermiso, ResultadoInstalacion.Enviado)
        act.actualizar()
        assertEquals(PasoDeActualizacion.FaltaPermiso, act.paso.value)
        every { instalador.puedeInstalar() } returns true
        act.alRegresar()
        assertEquals(PasoDeActualizacion.Instalando, act.paso.value)
        coVerify(exactly = 1) { descargador.bajar(any()) }
        coVerify(exactly = 2) { instalador.instalar(archivo) }
    }

    @Test fun `si lo niega, al regresar no insiste ni abre Ajustes otra vez y el aviso sigue`() = runBlocking {
        coEvery { descargador.bajar(any()) } returns Bajada.Lista(archivo)
        every { comprobador.comprobar(archivo, any()) } returns Bajada.Lista(archivo)
        coEvery { instalador.instalar(archivo) } returns ResultadoInstalacion.FaltaPermiso
        act.actualizar()
        every { instalador.puedeInstalar() } returns false
        act.alRegresar()
        assertEquals(PasoDeActualizacion.FaltaPermiso, act.paso.value)
        coVerify(exactly = 1) { instalador.instalar(archivo) }
        assertEquals(5, revisor.nueva.value?.versionCode)
    }

    @Test fun `el sistema avisa que no se instalo - el aviso dice su texto`() {
        act.alFallarLaInstalacion(ResultadoInstalacion.Fallo("INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
        assertEquals(PasoDeActualizacion.Fallo("No se instaló: INSTALL_FAILED_UPDATE_INCOMPATIBLE"), act.paso.value)
    }

    @Test fun `al arrancar abandona sesiones y borra lo bajado`() {
        every { descargador.limpiar() } returns Unit
        act.alArrancar()
        verify { instalador.abandonarLasAbiertas() }
        verify { descargador.limpiar() }
    }
}

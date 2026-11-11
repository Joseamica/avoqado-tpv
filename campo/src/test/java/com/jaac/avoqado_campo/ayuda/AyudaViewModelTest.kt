package com.jaac.avoqado_campo.ayuda

import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_campo.cola.PiezaDao
import com.jaac.avoqado_campo.copia.RepositorioDeCopia
import com.jaac.avoqado_campo.identidad.AlmacenEnMemoria
import com.jaac.avoqado_campo.identidad.BorradoDelCelular
import com.jaac.avoqado_campo.identidad.DatosDeActivacion
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.identidad.ResultadoDelBorrado
import com.jaac.avoqado_campo.identidad.Sesion
import com.jaac.avoqado_campo.identidad.Sucursal
import com.jaac.avoqado_campo.latido.Latido
import com.jaac.avoqado_campo.red.CampoApi
import com.jaac.avoqado_campo.red.Conectividad
import com.jaac.avoqado_campo.red.PorSubir
import com.jaac.avoqado_campo.red.RedModule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AyudaViewModelTest {
    private val server = MockWebServer()
    private val quienSoy = QuienSoy(AlmacenEnMemoria())
    private val porSubir = MutableStateFlow(PorSubir(0))
    private val dao = mockk<PiezaDao>()
    private val copia = mockk<RepositorioDeCopia>()
    private val latido = mockk<Latido>()
    private val borrado = mockk<BorradoDelCelular>()
    private val conectividad = mockk<Conectividad>()
    private lateinit var vm: AyudaViewModel

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.start()
        quienSoy.guardarActivacion(
            DatosDeActivacion("c1", "c".repeat(64), "s1", "Ana", Sesion("acc", "r".repeat(64), "v1"), listOf(Sucursal("v1", "Centro", null))),
        )
        every { dao.cuantasPorSubir() } returns porSubir
        every { conectividad.hayRed() } returns true
        coEvery { borrado.trasDesvincular(any()) } returns ResultadoDelBorrado.BORRADO
        coEvery { latido.mandar() } returns true
        coEvery { copia.actualizar(any()) } returns true
        val api = RedModule.crearRetrofit(server.url("/api/v1/").toString(), OkHttpClient()).create(CampoApi::class.java)
        vm = AyudaViewModel(quienSoy, dao, copia, latido, api, borrado, conectividad)
    }

    @After fun tearDown() {
        vm.viewModelScope.cancel()
        server.shutdown()
        Dispatchers.resetMain()
    }

    private suspend fun esperarQueTermine() = withTimeout(5_000) { while (vm.estado.value.trabajando) delay(10) }

    @Test fun `desvincular con una pieza por subir no llama al servidor`() = runBlocking {
        porSubir.value = PorSubir(1)
        vm.desvincular()
        esperarQueTermine()
        assertEquals(0, server.requestCount)
        assertEquals("Primero sube lo pendiente (1)", vm.estado.value.mensaje)
        coVerify(exactly = 0) { borrado.trasDesvincular(any()) }
    }

    @Test fun `desvincular con la cola vacia manda porSubir 0 y borra el celular`() = runBlocking {
        val generacion = quienSoy.generacion
        server.enqueue(MockResponse().setBody("""{"yaEstaba":false}"""))
        vm.desvincular()
        esperarQueTermine()
        val r = server.takeRequest()
        assertEquals("/api/v1/tpv/campo/desvincular", r.path)
        assertEquals("c".repeat(64), r.getHeader(CampoApi.CREDENCIAL))
        assertEquals("""{"porSubir":0}""", r.body.readUtf8())
        coVerify(exactly = 1) { borrado.trasDesvincular(generacion) } // la persona del toque, no la de después
    }

    @Test fun `desvincular sin senal no borra nada`() = runBlocking {
        every { conectividad.hayRed() } returns false
        vm.desvincular()
        esperarQueTermine()
        assertEquals(0, server.requestCount)
        assertEquals("Necesitas señal para esto", vm.estado.value.mensaje)
        coVerify(exactly = 0) { borrado.trasDesvincular(any()) }
    }

    @Test fun `desvincular con 409 TIENE_POR_SUBIR no borra`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"TIENE_POR_SUBIR","message":"x"}"""))
        vm.desvincular()
        esperarQueTermine()
        assertEquals("Primero sube lo pendiente.", vm.estado.value.mensaje)
        coVerify(exactly = 0) { borrado.trasDesvincular(any()) }
    }

    @Test fun `una excepcion no cierra la app y lo dice`() = runBlocking {
        io.mockk.mockkStatic(android.util.Log::class) // prueba JVM: android.util.Log no existe aquí
        every { android.util.Log.w(any(), any<String>(), any()) } returns 0
        coEvery { copia.actualizar(any()) } throws java.io.IOException("disco lleno")
        vm.actualizarDatos()
        esperarQueTermine()
        assertEquals("No se pudo, intenta otra vez", vm.estado.value.mensaje)
        io.mockk.unmockkStatic(android.util.Log::class)
    }

    @Test fun `cerrar sesion bloquea pero no borra los tokens`() {
        vm.cerrarSesion()
        assertTrue(quienSoy.bloqueada)
        assertNotNull(quienSoy.sesion)
        assertEquals("acc", quienSoy.sesion!!.accessToken)
        assertEquals("c".repeat(64), quienSoy.credencial)
    }

    @Test fun `actualizar datos sin senal lo dice y no baja nada`() = runBlocking {
        every { conectividad.hayRed() } returns false
        vm.actualizarDatos()
        esperarQueTermine()
        assertEquals("Necesitas señal para esto", vm.estado.value.mensaje)
        coVerify(exactly = 0) { copia.actualizar(any()) }
    }
}

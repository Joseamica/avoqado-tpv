package com.jaac.avoqado_campo.entrada

import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_campo.identidad.AlmacenEnMemoria
import com.jaac.avoqado_campo.identidad.DatosDeActivacion
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.identidad.Sesion
import com.jaac.avoqado_campo.identidad.Sucursal
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EntradaViewModelTest {
    private val repo = mockk<RepositorioDeEntrada>()
    private val quienSoy = QuienSoy(AlmacenEnMemoria())
    private lateinit var vm: EntradaViewModel

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { repo.activacionPendiente() } returns false
        quienSoy.guardarActivacion(
            DatosDeActivacion("cel", "c".repeat(64), "s1", "Ana", Sesion("a", "1".repeat(64), "v1"),
                listOf(Sucursal("v1", "Centro", null), Sucursal("v2", "Norte", null))),
        )
    }

    @After fun tearDown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private fun vm() = EntradaViewModel(repo, quienSoy).also { vm = it }

    @Test fun `arranca con el nombre, las sucursales y la de la sesion elegida`() {
        val e = vm().estado.value
        assertEquals("Ana", e.nombre)
        assertEquals(2, e.sucursales.size)
        assertEquals("v1", e.venueIdElegido)
    }

    @Test fun `rechazo muestra el mensaje y suelta el boton`() = runTest {
        coEvery { repo.entrar("0000", "v1") } returns ResultadoEntrada.Rechazo("PIN incorrecto. Te quedan 3 intentos.", 3)
        val v = vm()
        v.entrar("0000")
        assertEquals("PIN incorrecto. Te quedan 3 intentos.", v.estado.value.mensaje)
        assertFalse(v.estado.value.trabajando)
        assertFalse(v.estado.value.dentro)
    }

    @Test fun `bloqueado dice cuantos minutos`() = runTest {
        coEvery { repo.entrar(any(), any()) } returns ResultadoEntrada.Bloqueado(120)
        val v = vm()
        v.entrar("1234")
        assertEquals("Espera 2 min para volver a intentar.", v.estado.value.mensaje)
    }

    @Test fun `ELIGE_SUCURSAL pide elegir y luego entra a la elegida`() = runTest {
        val dos = listOf(Sucursal("v1", "Centro", null), Sucursal("v2", "Norte", null))
        coEvery { repo.entrar("1234", "v1") } returns ResultadoEntrada.EligeSucursal(dos)
        coEvery { repo.entrar("1234", "v2") } returns ResultadoEntrada.Dentro
        val v = vm()
        v.entrar("1234")
        assertNull(v.estado.value.venueIdElegido)
        assertEquals(dos, v.estado.value.sucursales)
        v.elegirSucursal("v2")
        v.entrar("1234")
        assertTrue(v.estado.value.dentro)
    }

    @Test fun `sin señal entra y lo dice`() = runTest {
        coEvery { repo.entrar(any(), any()) } returns ResultadoEntrada.DentroSinSenal("Centro")
        val v = vm()
        v.entrar("1234")
        assertTrue(v.estado.value.dentro)
        assertTrue(v.estado.value.dentroSinSenal)
    }

    @Test fun `doble toque en activar hace una sola llamada`() = runTest {
        val espera = CompletableDeferred<ResultadoEntrada>()
        coEvery { repo.activar(any(), any()) } coAnswers { espera.await() }
        val v = vm()
        v.activar("ABC123", "1234")
        assertTrue(v.estado.value.trabajando)
        v.activar("ABC123", "1234")
        espera.complete(ResultadoEntrada.ColaNoVacia)
        coVerify(exactly = 1) { repo.activar(any(), any()) }
        assertEquals("Primero sube lo pendiente de este celular.", v.estado.value.mensaje)
    }

    @Test fun `activacion pendiente se refleja tras cada intento`() = runTest {
        coEvery { repo.activar(any(), any()) } returns ResultadoEntrada.Rechazo("Para activar el celular necesitas señal.", null)
        val v = vm()
        every { repo.activacionPendiente() } returns true
        v.activar("ABC123", "1234")
        assertTrue(v.estado.value.terminandoActivacion)
        assertEquals("Para activar el celular necesitas señal.", v.estado.value.mensaje)
    }
}

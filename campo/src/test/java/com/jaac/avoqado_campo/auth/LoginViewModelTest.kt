package com.jaac.avoqado_campo.auth

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class RepoFalso(private val respuesta: ResultadoLogin) : RepositorioAuthCampo {
    override suspend fun entrar(correo: String, contrasena: String) = respuesta
}

class LoginViewModelTest {

    @Before fun antes() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun despues() { Dispatchers.resetMain() }

    @Test
    fun `credenciales malas muestran el error y NO cierran nada`() = runTest {
        val vm = LoginViewModel(RepoFalso(ResultadoLogin.Falla("Credenciales incorrectas")))
        vm.entrar("promotor@ejemplo.com", "mala")
        val estado = vm.estado.value
        assertTrue("esperaba Error, fue $estado", estado is EstadoLogin.Error)
        assertTrue((estado as EstadoLogin.Error).mensaje.contains("Credenciales"))
        vm.viewModelScope.cancel()
    }

    @Test
    fun `credenciales buenas entran y exponen el venue`() = runTest {
        val vm = LoginViewModel(RepoFalso(ResultadoLogin.Ok("venue-123")))
        vm.entrar("promotor@ejemplo.com", "buena")
        assertEquals(EstadoLogin.Dentro("venue-123"), vm.estado.value)
        vm.viewModelScope.cancel()
    }
}

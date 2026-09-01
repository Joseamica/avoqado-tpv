package com.jaac.avoqado_campo.auth

import com.jaac.avoqado_campo.red.CampoApi
import com.jaac.avoqado_campo.red.LoginBody
import com.jaac.avoqado_campo.red.LoginRespuesta
import com.jaac.avoqado_campo.red.UsuarioDto
import com.jaac.avoqado_campo.red.VenueDto
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

private class ApiFalsa(private val respuesta: Response<LoginRespuesta>) : CampoApi {
    override suspend fun login(body: LoginBody): Response<LoginRespuesta> = respuesta
}

private class ApiQueLanza(private val excepcion: Throwable) : CampoApi {
    override suspend fun login(body: LoginBody): Response<LoginRespuesta> = throw excepcion
}

class RepositorioAuthCampoImplTest {

    @Test
    fun `200 con selectedVenueId usa ESE campo, no el primer venue del arreglo`() = runTest {
        // 🔴 El arreglo trae OTRA sucursal PRIMERO a propósito: si alguien vuelve a deducir
        // la sucursal de venues[].firstOrNull(), este test debe fallar.
        val cuerpo = LoginRespuesta(
            success = true,
            message = null,
            user = UsuarioDto(
                id = "staff-1",
                firstName = "Ana",
                lastName = "Lopez",
                venues = listOf(
                    VenueDto(id = "venue-suspendida", name = "Suspendida", role = "PROMOTER", roleDisplayName = "Promotor"),
                    VenueDto(id = "venue-abc", name = "Activa", role = "PROMOTER", roleDisplayName = "Promotor"),
                ),
            ),
            accessToken = "token-acceso",
            refreshToken = "token-refresco",
            selectedVenueId = "venue-abc",
        )
        val repo = RepositorioAuthCampoImpl(ApiFalsa(Response.success(cuerpo)))

        val resultado = repo.entrar("promotor@ejemplo.com", "buena")

        assertEquals(ResultadoLogin.Ok("venue-abc"), resultado)
    }

    @Test
    fun `200 sin selectedVenueId es una Falla, nunca se adivina la sucursal`() = runTest {
        val cuerpo = LoginRespuesta(
            success = true,
            message = null,
            user = null,
            accessToken = "token-acceso",
            refreshToken = "token-refresco",
            selectedVenueId = null,
        )
        val repo = RepositorioAuthCampoImpl(ApiFalsa(Response.success(cuerpo)))

        val resultado = repo.entrar("promotor@ejemplo.com", "buena")

        assertEquals(
            ResultadoLogin.Falla("El servidor no indicó la sucursal de la sesión."),
            resultado,
        )
    }

    @Test
    fun `401 con JSON de error usa el campo message`() = runTest {
        val json = """{"message":"Credenciales inválidas","errorName":"Error"}"""
        val cuerpoError = json.toResponseBody("application/json".toMediaType())
        val repo = RepositorioAuthCampoImpl(ApiFalsa(Response.error(401, cuerpoError)))

        val resultado = repo.entrar("promotor@ejemplo.com", "mala")

        assertEquals(ResultadoLogin.Falla("Credenciales inválidas"), resultado)
    }

    @Test
    fun `401 con cuerpo que NO es JSON cae al respaldo generico, sin lanzar`() = runTest {
        val cuerpoError = "<html>Bad Gateway</html>".toResponseBody("text/html".toMediaType())
        val repo = RepositorioAuthCampoImpl(ApiFalsa(Response.error(401, cuerpoError)))

        val resultado = repo.entrar("promotor@ejemplo.com", "mala")

        assertEquals(ResultadoLogin.Falla("No se pudo entrar (401)"), resultado)
    }

    @Test
    fun `sin conexion la API lanza IOException y el repositorio lo convierte en Falla`() = runTest {
        val repo = RepositorioAuthCampoImpl(ApiQueLanza(IOException("Network unreachable")))

        val resultado = repo.entrar("promotor@ejemplo.com", "buena")

        assertTrue(resultado is ResultadoLogin.Falla)
        assertTrue(
            (resultado as ResultadoLogin.Falla).mensaje.contains("Sin conexión"),
        )
    }
}

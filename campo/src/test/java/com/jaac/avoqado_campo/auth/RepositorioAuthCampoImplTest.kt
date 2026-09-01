package com.jaac.avoqado_campo.auth

import com.jaac.avoqado_campo.red.CampoApi
import com.jaac.avoqado_campo.red.LoginBody
import com.jaac.avoqado_campo.red.LoginRespuesta
import com.jaac.avoqado_campo.red.UsuarioDto
import com.jaac.avoqado_campo.red.VenueDto
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
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

    @Test
    fun `200 con un cuerpo que no tiene forma de objeto es una Falla, nunca un crash`() = runTest {
        // 🔴 Reproducido con MockWebServer + Retrofit/Gson REALES, no con un fake: el fake
        // `ApiFalsa` recibe un Response ya construido y nunca pasa por el parseo de Retrofit,
        // así que no puede ejercitar este defecto.
        //
        // 🔑 Medido en este repo (Gson 2.8.5, la versión real que resuelve `converter-gson
        // 2.9.0`): una página HTML cruda de portal cautivo SÍ revienta el parseo, pero Gson
        // la tokeniza mal y lanza `MalformedJsonException` — que ES una `IOException` y el
        // catch de arriba YA la atrapaba (falla asertada primero con ese cuerpo, verificado
        // antes de escribir esto). El caso que de verdad escapa como el `catch (IOException)`
        // no ve es cuando el cuerpo ES JSON válido pero de la forma equivocada — un arreglo
        // en vez de un objeto — algo que un proxy/gateway que intercepta la conexión (el de
        // una red WiFi corporativa o pública, no sólo un portal cautivo con HTML) puede
        // devolver con Content-Type application/json. Ahí Gson sí abre el objeto
        // (`beginObject()`), encuentra `BEGIN_ARRAY` y lanza `IllegalStateException`, que
        // `ReflectiveTypeAdapterFactory` envuelve en `JsonSyntaxException` — una
        // `RuntimeException`, no una `IOException`.
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("[]"),
        )
        server.start()
        try {
            val api = Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(CampoApi::class.java)
            val repo = RepositorioAuthCampoImpl(api)

            val resultado = repo.entrar("promotor@ejemplo.com", "buena")

            assertTrue("esperaba Falla, fue $resultado", resultado is ResultadoLogin.Falla)
            assertEquals(
                "No se pudo conectar con Avoqado. Si estás en un WiFi público, revisa que tengas internet.",
                (resultado as ResultadoLogin.Falla).mensaje,
            )
        } finally {
            server.shutdown()
        }
    }
}

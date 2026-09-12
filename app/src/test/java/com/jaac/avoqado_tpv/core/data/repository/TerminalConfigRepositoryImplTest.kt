package com.jaac.avoqado_tpv.core.data.repository

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Cubre [TerminalConfigRepositoryImpl.mapNetworkErrorToUserMessage] — Task 8 / F-5.
 *
 * Antes esto clasificaba por `e.message?.contains("timeout"|"no connection"|"network")`.
 * Estos tests prueban los dos casos reales donde eso fallaba en silencio
 * (SocketTimeoutException cuyo mensaje no contiene la palabra "timeout";
 * UnknownHostException cuyo mensaje es solo el hostname) y que un mensaje vacío o en
 * otro idioma ya no rompe la clasificación ahora que es por TIPO de excepción.
 */
class TerminalConfigRepositoryImplTest {

    private val repository = TerminalConfigRepositoryImpl(
        apiService = mockk<ApiService>(relaxed = true),
        secureStorage = mockk<SecureStorage>(relaxed = true),
    )

    @Test
    fun `SocketTimeoutException sin la palabra timeout en el mensaje SI se clasifica como timeout`() {
        // Mensaje real de OkHttp en un connect-timeout: no contiene "timeout".
        val e = SocketTimeoutException("failed to connect to /1.2.3.4 (port 443) after 10000ms")
        val message = repository.mapNetworkErrorToUserMessage(e)
        assertThat(message).contains("Tiempo de espera agotado")
    }

    @Test
    fun `UnknownHostException cuyo mensaje es solo el hostname SI se clasifica como sin conexion`() {
        // Mensaje real de UnknownHostException: literalmente el hostname, sin "network"
        // ni "no connection" en ningun lado.
        val e = UnknownHostException("api.avoqado.io")
        val message = repository.mapNetworkErrorToUserMessage(e)
        assertThat(message).contains("Sin conexión a internet")
    }

    @Test
    fun `ConnectException tambien se clasifica como sin conexion`() {
        val e = ConnectException("Connection refused")
        val message = repository.mapNetworkErrorToUserMessage(e)
        assertThat(message).contains("Sin conexión a internet")
    }

    @Test
    fun `IOException generico se clasifica como sin conexion`() {
        val e = IOException("stream was reset")
        val message = repository.mapNetworkErrorToUserMessage(e)
        assertThat(message).contains("Sin conexión a internet")
    }

    @Test
    fun `mensaje vacio o nulo no rompe la clasificacion (es por tipo, no texto)`() {
        assertThat(repository.mapNetworkErrorToUserMessage(SocketTimeoutException("")))
            .contains("Tiempo de espera agotado")
        assertThat(repository.mapNetworkErrorToUserMessage(UnknownHostException(null)))
            .contains("Sin conexión a internet")
    }

    @Test
    fun `una excepcion no relacionada con red cae al mensaje generico`() {
        val e = IllegalStateException("unexpected null field")
        val message = repository.mapNetworkErrorToUserMessage(e)
        assertThat(message).contains("Error inesperado")
    }

    // ── T26 (Testarudo, 2026-09-11) ──────────────────────────────────────────
    // Sin red, el `catch` convertía el UnknownHostException en `Exception(msg)` SIN causa:
    // AngelPay no podía distinguir «no pude preguntar» de «pregunté y no hay credenciales»,
    // mostraba «credentials missing» y caía a la cuenta primaria del venue.

    @Test
    fun `P1 fetchConfig sin red conserva la causa y marca el servidor como inalcanzable`() = runTest {
        val api = mockk<ApiService>()
        coEvery { api.getTerminalConfig(any()) } throws UnknownHostException("api.avoqado.io")
        val repo = TerminalConfigRepositoryImpl(apiService = api, secureStorage = mockk(relaxed = true))

        val error = repo.fetchConfig("N860W173397").exceptionOrNull()

        assertThat(error).isInstanceOf(TerminalConfigUnreachableException::class.java)
        assertThat(error!!.cause).isInstanceOf(UnknownHostException::class.java)
        // El texto para el operador no cambia.
        assertThat(error.message).contains("Sin conexión a internet")
    }

    @Test
    fun `fetchConfig con 503 tambien marca el servidor como inalcanzable`() = runTest {
        val api = mockk<ApiService>()
        coEvery { api.getTerminalConfig(any()) } returns Response.error(503, "{}".toResponseBody())
        val repo = TerminalConfigRepositoryImpl(apiService = api, secureStorage = mockk(relaxed = true))

        val error = repo.fetchConfig("N860W173397").exceptionOrNull()

        assertThat(error).isInstanceOf(TerminalConfigUnreachableException::class.java)
        assertThat(error!!.message).contains("Error del servidor")
    }

    @Test
    fun `fetchConfig con 404 NO es servidor inalcanzable`() = runTest {
        val api = mockk<ApiService>()
        coEvery { api.getTerminalConfig(any()) } returns Response.error(404, "{}".toResponseBody())
        val repo = TerminalConfigRepositoryImpl(apiService = api, secureStorage = mockk(relaxed = true))

        val error = repo.fetchConfig("N860W173397").exceptionOrNull()

        assertThat(error).isNotNull()
        assertThat(error).isNotInstanceOf(TerminalConfigUnreachableException::class.java)
    }
}

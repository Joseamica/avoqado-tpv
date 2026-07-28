package com.jaac.avoqado_tpv.core.data.repository

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import io.mockk.mockk
import org.junit.Test
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
}

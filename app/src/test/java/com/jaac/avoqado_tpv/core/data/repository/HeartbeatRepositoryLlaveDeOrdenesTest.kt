package com.jaac.avoqado_tpv.core.data.repository

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.dto.CommandCredentialDto
import com.jaac.avoqado_tpv.core.data.network.dto.CommandsReadyDto
import com.jaac.avoqado_tpv.core.domain.models.Result
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * La «llave de órdenes» rechazada (401 en `tpv/commands/ready`) se repara dándola de alta otra vez con la sesión del cajero,
 * una sola vez por proceso, cuando el servidor sí contestó: el latido llama esto seguido y antes se volvía un bucle con «Verificando sesión...» encima.
 */
class HeartbeatRepositoryLlaveDeOrdenesTest {
    private val api = mockk<ApiService>()
    private val storage = mockk<SecureStorage>(relaxed = true)
    private val repo = HeartbeatRepository(api, storage, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
    private val serial = "AVQD-N860W175781"
    private val llave = "b".repeat(64)

    private val rechazo = Response.error<CommandsReadyDto>(401, "".toResponseBody(null))
    private val sinOrdenes = Response.success(CommandsReadyDto(emptyList()))
    private val alta = Response.success(CommandCredentialDto(llave))

    @Before
    fun setup() {
        every { storage.getString("tpv_command_enrolled", any()) } returns "true"
        every { storage.getString("tpv_command_token", any()) } returns "a".repeat(64)
        every { storage.getToken() } returns "jwt-del-cajero"
    }

    @Test
    fun `una llave rechazada se da de alta otra vez y se reintenta una vez`() = runBlocking {
        coEvery { api.commandsReady(any()) } returnsMany listOf(rechazo, sinOrdenes)
        coEvery { api.provisionCommandCredential(any()) } returns alta

        val resultado = repo.commandsReady(serial)

        assertTrue(resultado is Result.Success)
        coVerify(exactly = 1) { api.provisionCommandCredential(mapOf("terminalId" to serial)) }
        coVerify(exactly = 2) { api.commandsReady(any()) }
        verify { storage.putStringDurably("tpv_command_token", llave) }
    }

    @Test
    fun `si la llave sigue rechazada no insiste en los siguientes latidos`() = runBlocking {
        coEvery { api.commandsReady(any()) } returns rechazo
        coEvery { api.provisionCommandCredential(any()) } returns alta

        assertTrue(repo.commandsReady(serial) is Result.Error)
        assertTrue(repo.commandsReady(serial) is Result.Error)
        assertTrue(repo.commandsReady(serial) is Result.Error)

        coVerify(exactly = 1) { api.provisionCommandCredential(any()) }
        coVerify(exactly = 4) { api.commandsReady(any()) }
    }

    @Test
    fun `si el servidor niega el alta no reintenta ni cambia la llave`() = runBlocking {
        coEvery { api.commandsReady(any()) } returns rechazo
        coEvery { api.provisionCommandCredential(any()) } returns Response.error(403, "".toResponseBody(null))

        assertTrue(repo.commandsReady(serial) is Result.Error)
        assertTrue(repo.commandsReady(serial) is Result.Error)

        coVerify(exactly = 1) { api.provisionCommandCredential(any()) }
        coVerify(exactly = 2) { api.commandsReady(any()) }
        verify(exactly = 0) { storage.putStringDurably("tpv_command_token", any()) }
    }

    @Test
    fun `sin sesion del cajero no pide el alta ni gasta su unico intento`() = runBlocking {
        every { storage.getToken() } returns null
        coEvery { api.commandsReady(any()) } returnsMany listOf(rechazo, rechazo, sinOrdenes)
        coEvery { api.provisionCommandCredential(any()) } returns alta

        assertTrue(repo.commandsReady(serial) is Result.Error)
        coVerify(exactly = 0) { api.provisionCommandCredential(any()) }

        every { storage.getToken() } returns "jwt-del-cajero"
        assertTrue(repo.commandsReady(serial) is Result.Success)
        coVerify(exactly = 1) { api.provisionCommandCredential(any()) }
    }

    @Test
    fun `una falla de red al pedir la llave no gasta el intento`() = runBlocking {
        coEvery { api.commandsReady(any()) } returnsMany listOf(rechazo, rechazo, sinOrdenes)
        coEvery { api.provisionCommandCredential(any()) } throws java.io.IOException("sin red") andThen alta

        assertTrue(repo.commandsReady(serial) is Result.Error)
        assertTrue(repo.commandsReady(serial) is Result.Success)

        coVerify(exactly = 2) { api.provisionCommandCredential(any()) }
    }

    @Test
    fun `un 503 o un 401 al pedir la llave no gastan el intento`() = runBlocking {
        coEvery { api.commandsReady(any()) } returnsMany listOf(rechazo, rechazo, rechazo, sinOrdenes)
        coEvery { api.provisionCommandCredential(any()) } returnsMany listOf(
            Response.error(503, "".toResponseBody(null)),
            Response.error(401, "".toResponseBody(null)),
            alta,
        )

        assertTrue(repo.commandsReady(serial) is Result.Error)
        assertTrue(repo.commandsReady(serial) is Result.Error)
        assertTrue(repo.commandsReady(serial) is Result.Success)

        coVerify(exactly = 3) { api.provisionCommandCredential(any()) }
    }

    @Test
    fun `otro error del servidor no toca la llave`() = runBlocking {
        coEvery { api.commandsReady(any()) } returns Response.error(500, "".toResponseBody(null))

        assertTrue(repo.commandsReady(serial) is Result.Error)

        coVerify(exactly = 0) { api.provisionCommandCredential(any()) }
    }
}

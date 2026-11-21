package com.jaac.avoqado_tpv.features.modules.data.repository

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.features.modules.data.dto.ModuleConfigDto
import com.jaac.avoqado_tpv.features.modules.data.dto.ModulesApiResponse
import com.jaac.avoqado_tpv.features.modules.data.dto.VenueModuleDto
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * Encargo 097e01 (Codex r1 #4): con el logo que ya no espera al SERVIDOR, la consulta de módulos del arranque puede seguir
 * al fondo mientras el PIN pide la suya. Si la del arranque (la más vieja) contesta DESPUÉS, no debe pisar a la del PIN,
 * ni volver a llenar lo que un cierre de sesión ya borró.
 */
class ModulosSinRespuestaViejaTest {

    private val api = mockk<ApiService>()
    private val storage = mockk<SecureStorage>(relaxed = true)
    private val respuestas = java.util.concurrent.ConcurrentLinkedDeque<CompletableDeferred<Response<ModulesApiResponse>>>()

    private fun conModulos(vararg codigos: String): Response<ModulesApiResponse> =
        Response.success(ModulesApiResponse(codigos.map { VenueModuleDto(it, ModuleConfigDto()) }))

    @Before
    fun setUp() {
        every { storage.getCachedModules() } returns emptyList()
        every { storage.getVenueId() } returns "venue-1"
        coEvery { api.getModules("venue-1") } coAnswers { respuestas.pollFirst()!!.await() }
    }

    private fun repo() = ModulesRepositoryImpl(api, storage)

    @Test
    fun `P1 la respuesta vieja que llega despues no pisa a la nueva`() = runBlocking {
        val repo = repo()
        val vieja = CompletableDeferred<Response<ModulesApiResponse>>()
        val nueva = CompletableDeferred<Response<ModulesApiResponse>>()
        respuestas.addLast(vieja); respuestas.addLast(nueva)

        val primera = async { repo.fetchAndCache() }
        while (respuestas.size > 1) kotlinx.coroutines.delay(5)
        val segunda = async { repo.fetchAndCache() }
        while (respuestas.isNotEmpty()) kotlinx.coroutines.delay(5)

        nueva.complete(conModulos("B")); segunda.await()
        vieja.complete(conModulos("A")); primera.await()

        assertThat(repo.modules.value.map { it.moduleCode }).containsExactly("B")
        verify(exactly = 0) { storage.saveModules(match { l -> l.any { it.code == "A" } }) }
    }

    @Test
    fun `si la nueva fallo, la vieja que llega despues si se guarda`() = runBlocking {
        val repo = repo()
        val vieja = CompletableDeferred<Response<ModulesApiResponse>>()
        val nueva = CompletableDeferred<Response<ModulesApiResponse>>()
        respuestas.addLast(vieja); respuestas.addLast(nueva)

        val primera = async { repo.fetchAndCache() }
        while (respuestas.size > 1) kotlinx.coroutines.delay(5)
        val segunda = async { repo.fetchAndCache() }
        while (respuestas.isNotEmpty()) kotlinx.coroutines.delay(5)

        nueva.complete(Response.error(503, "".toResponseBody())); segunda.await()
        vieja.complete(conModulos("A")); primera.await()

        assertThat(repo.modules.value.map { it.moduleCode }).containsExactly("A")
    }

    @Test
    fun `P1 una respuesta que llega despues de cerrar sesion no vuelve a llenar los modulos`() = runBlocking {
        val repo = repo()
        val enVuelo = CompletableDeferred<Response<ModulesApiResponse>>()
        respuestas.addLast(enVuelo)

        val peticion = async { repo.fetchAndCache() }
        while (respuestas.isNotEmpty()) kotlinx.coroutines.delay(5)
        repo.clearCache()
        enVuelo.complete(conModulos("A")); peticion.await()

        assertThat(repo.modules.value).isEmpty()
        verify(exactly = 0) { storage.saveModules(any()) }
    }

    @Test
    fun `una sola peticion se guarda como siempre`() = runBlocking {
        val repo = repo()
        respuestas.addLast(CompletableDeferred(conModulos("A", "C")))

        repo.fetchAndCache()

        assertThat(repo.modules.value.map { it.moduleCode }).containsExactly("A", "C")
        verify(exactly = 1) { storage.saveModules(any()) }
    }
}

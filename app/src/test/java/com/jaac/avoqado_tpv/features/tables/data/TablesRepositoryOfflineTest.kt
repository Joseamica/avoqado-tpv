package com.jaac.avoqado_tpv.features.tables.data

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.jaac.avoqado_tpv.features.tables.data.api.TablesApiService
import com.jaac.avoqado_tpv.features.tables.data.api.dto.AddItemsRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.AddOrderItemRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.OrderDetailResponse
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncIntentTypes
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetail
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * `TablesRepository.addItems` — el outbox write-ahead (Plan C, Task 4;
 * endurecido por un fix P1: "el outbox de Mesas no era write-ahead de
 * verdad").
 *
 * Contrato bajo prueba, POST-fix: el intent SIEMPRE se escribe ([SyncOutbox.enqueue])
 * ANTES del intento online — nunca después de que falle. Lo que varía por
 * resultado es si ese intent write-ahead se DESCARTA después:
 * - Éxito online, o rechazo/fallo NO transitorio (Propagate): se descarta
 *   ([SyncOutbox.discardPending]) — no hay nada que reproducir.
 * - Infraestructura transitoria (Retryable): se deja PENDING — el próximo
 *   replay lo reproduce solo.
 *
 * A propósito NO reusa la clasificación de Pagos (`SyncOutcome.kt` / Plan
 * A): ese clasificador es permisivo ante la duda (la tarjeta ya se cobró) y
 * trataba 401/403/405/5xx/desconocido como `Retryable` — un hallazgo de
 * auditoría independiente. Reusarlo aquí convertía un 401 de sesión, un 500
 * real del server, o hasta un bug de programación propio (NPE) en un
 * "guardado offline" silencioso: el mesero ve la ronda como enviada y nunca
 * existió. Mesas usa su PROPIO clasificador (`TablesSyncOutcome.kt`) con el
 * sesgo opuesto: ante la duda, PROPAGA.
 *
 * `api`/`outbox` son mocks ESTRICTOS (no `relaxed`), y cada test hace
 * `coVerify(exactly = ...)` explícito — la vacuous-test trap de este repo
 * (un mock relajado satisfecho por una llamada de construcción bajo
 * `UnconfinedTestDispatcher`, verde durante meses sin proteger nada) se
 * evita exigiendo el conteo exacto, no solo "fue llamado alguna vez".
 */
class TablesRepositoryOfflineTest {

    private val api = mockk<TablesApiService>()
    private val outbox = mockk<SyncOutbox>()
    private lateinit var repo: TablesRepository

    private val venueId = "venue-1"
    private val orderId = "order-1"
    private val items = listOf(AddOrderItemRequest(productId = "prod-1", quantity = 2))

    @Before
    fun setUp() {
        clearMocks(api, outbox)
        coEvery { outbox.enqueue(any(), any(), any(), any()) } returns "intent-id"
        coEvery { outbox.discardPending(any()) } returns Unit
        repo = TablesRepository(api, outbox)
    }

    // region — write-ahead: el intent SIEMPRE se escribe ANTES del intento online

    @Test
    fun el_intent_se_escribe_write_ahead_ANTES_del_intento_online() = runTest {
        coEvery { api.addItems(any(), any(), any()) } returns successResponse()

        repo.addItems(venueId, orderId, items, version = 1)

        coVerifyOrder {
            outbox.enqueue(venueId, SyncIntentTypes.ADD_ITEMS, any(), any())
            api.addItems(any(), any(), any())
        }
    }

    @Test
    fun el_intent_write_ahead_se_escribe_incluso_si_el_intento_online_falla() = runTest {
        // 🔴 El bug real: antes, el intent SOLO se creaba después de que el
        // intento online fallara. Aquí probamos que ahora se escribe SIEMPRE,
        // sea cual sea el resultado.
        coEvery { api.addItems(any(), any(), any()) } throws IOException("sin red")

        repo.addItems(venueId, orderId, items, version = 1)

        coVerify(exactly = 1) { outbox.enqueue(venueId, SyncIntentTypes.ADD_ITEMS, any(), any()) }
    }

    // endregion

    // region — DROP_RESPONSE: la misma llave de idempotencia viaja en ambos caminos

    @Test
    fun DROP_RESPONSE_el_mismo_externalId_por_linea_viaja_en_el_intento_online_y_en_lo_encolado() = runTest {
        // El escenario que rompía el módulo: el server SÍ aplica la ronda,
        // pero la respuesta se pierde (aquí simulado con IOException) — el
        // cliente lo trata como "sin red" y el intent queda para reproducir
        // después. Si el intent reproducido no llevara la MISMA llave de
        // idempotencia que el intento online perdido, el server no tendría
        // forma de reconocer que ya se aplicó y la ronda se duplicaría.
        val queuedPayload = slot<JsonObject>()
        val onlineRequest = slot<AddItemsRequest>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.ADD_ITEMS, capture(queuedPayload), any()) } returns "intent-id"
        coEvery { api.addItems(venueId, orderId, capture(onlineRequest)) } throws IOException("respuesta perdida")

        repo.addItems(venueId, orderId, items, version = 1)

        val onlineExternalIds = onlineRequest.captured.items.map { it.externalId }
        val queuedItems = queuedPayload.captured.getAsJsonArray("items")
        val queuedExternalIds = queuedItems.map { it.asJsonObject.get("externalId")?.takeUnless { el -> el.isJsonNull }?.asString }

        assertThat(onlineExternalIds).isEqualTo(queuedExternalIds)
        assertThat(onlineExternalIds).doesNotContain(null) // de verdad se generó una llave, no quedó vacía
    }

    // endregion

    // region — infraestructura transitoria: el intent se DEJA pendiente, NO se descarta

    @Test
    fun sin_red_el_intent_write_ahead_queda_PENDING_y_NO_es_error() = runTest {
        coEvery { api.addItems(any(), any(), any()) } throws IOException("sin red")

        val result = repo.addItems(venueId, orderId, items, version = 1)

        assertThat(result.isSuccess).isTrue() // el mesero sigue trabajando
        coVerify(exactly = 1) { outbox.enqueue(venueId, SyncIntentTypes.ADD_ITEMS, any(), any()) }
        coVerify(exactly = 0) { outbox.discardPending(any()) } // nada que descartar — sigue PENDING
    }

    @Test
    fun codigos_de_infraestructura_transitoria_dejan_el_intent_write_ahead_PENDING() = runTest {
        // 502/503/504 = gateway/servicio caído; 408/429 = timeout o rate-limit
        // (nuestra propia ráfaga de intents al reconectar puede provocar el 429).
        // Ninguno de estos es "el server dijo que no".
        for (code in listOf(502, 503, 504, 408, 429)) {
            clearMocks(api, outbox)
            coEvery { outbox.enqueue(any(), any(), any(), any()) } returns "intent-id"
            coEvery { outbox.discardPending(any()) } returns Unit
            coEvery { api.addItems(any(), any(), any()) } returns errorResponse(code)

            val result = repo.addItems(venueId, orderId, items, version = 1)

            assertThat(result.isSuccess).isTrue()
            coVerify(exactly = 1) { outbox.enqueue(venueId, SyncIntentTypes.ADD_ITEMS, any(), any()) }
            coVerify(exactly = 0) { outbox.discardPending(any()) }
        }
    }

    // endregion

    // region — RECHAZO del server (o fallo desconocido): el intent write-ahead se DESCARTA, error normal

    @Test
    fun un_500_del_server_descarta_el_intent_write_ahead_y_se_propaga() = runTest {
        // 🔴 El defecto real: reusar el clasificador de Pagos trataba 500 como
        // Retryable → se encolaba como "éxito offline" silencioso. Un 500 puede ser
        // un bug real del server; el mesero viendo el error y reintentando a mano es
        // más seguro que un encolado silencioso.
        coEvery { api.addItems(any(), any(), any()) } returns errorResponse(500)

        val result = repo.addItems(venueId, orderId, items, version = 1)

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 1) { outbox.enqueue(venueId, SyncIntentTypes.ADD_ITEMS, any(), any()) } // write-ahead
        coVerify(exactly = 1) { outbox.discardPending(any()) } // nada que reproducir
    }

    @Test
    fun codigos_401_y_403_descartan_el_intent_write_ahead_y_se_propagan() = runTest {
        // 🔴 El defecto real: el clasificador de Pagos trata 401/403 como Retryable
        // A PROPÓSITO (ahí casi siempre es la sesión, no el pago). En Mesas NO hay
        // nada irreversible que proteger, así que el usuario debe ver el rechazo de
        // auth/permiso, no que se disfrace de "guardado offline".
        for (code in listOf(401, 403)) {
            clearMocks(api, outbox)
            coEvery { outbox.enqueue(any(), any(), any(), any()) } returns "intent-id"
            coEvery { outbox.discardPending(any()) } returns Unit
            coEvery { api.addItems(any(), any(), any()) } returns errorResponse(code)

            val result = repo.addItems(venueId, orderId, items, version = 1)

            assertThat(result.isFailure).isTrue()
            coVerify(exactly = 1) { outbox.discardPending(any()) }
        }
    }

    @Test
    fun si_el_server_RECHAZA_descarta_el_intent_write_ahead_y_es_un_error_normal() = runTest {
        // "El server dijo que no" != "no hay red". Dejar encolado un rechazo de
        // negocio lo haría reintentarse para siempre contra una regla que no va a cambiar.
        coEvery { api.addItems(any(), any(), any()) } returns errorResponse(400)

        val result = repo.addItems(venueId, orderId, items, version = 1)

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 1) { outbox.discardPending(any()) }
    }

    @Test
    fun otros_4xx_de_negocio_descartan_el_intent_write_ahead() = runTest {
        // 404/405/422: rechazo de negocio, ninguno "no hay red".
        for (code in listOf(404, 405, 422)) {
            clearMocks(api, outbox)
            coEvery { outbox.enqueue(any(), any(), any(), any()) } returns "intent-id"
            coEvery { outbox.discardPending(any()) } returns Unit
            coEvery { api.addItems(any(), any(), any()) } returns errorResponse(code)

            val result = repo.addItems(venueId, orderId, items, version = 1)

            assertThat(result.isFailure).isTrue()
            coVerify(exactly = 1) { outbox.discardPending(any()) }
        }
    }

    @Test
    fun un_409_de_version_conflict_descarta_el_intent_write_ahead() = runTest {
        // 409 en la llamada ONLINE de addItems es un choque de `version` (CAS) —
        // classifyTablesSyncFailure() lo clasifica como Propagate (no es ninguno de
        // los códigos de infraestructura transitoria): se propaga, no se deja
        // encolado. Dejarlo pendiente reintentaría para siempre contra una version
        // vieja; Task 7 es quien recarga la cuenta y avisa.
        coEvery { api.addItems(any(), any(), any()) } returns errorResponse(409)

        val result = repo.addItems(venueId, orderId, items, version = 1)

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 1) { outbox.discardPending(any()) }
    }

    @Test
    fun un_throwable_desconocido_descarta_el_intent_write_ahead_y_se_propaga() = runTest {
        // 🔴 EL CASO PRINCIPAL del defecto original: un bug de programación propio
        // (aquí simulado con NPE) NUNCA debe disfrazarse de "guardado offline". El
        // clasificador de Pagos (Plan A) caía a Retryable en su `else` — cualquier
        // throwable desconocido se encolaba en silencio. El de Mesas hace lo
        // opuesto: ante la duda, PROPAGA (y descarta el write-ahead).
        coEvery { api.addItems(any(), any(), any()) } throws NullPointerException("bug propio")

        val result = repo.addItems(venueId, orderId, items, version = 1)

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 1) { outbox.discardPending(any()) }
    }

    // endregion

    // region — camino feliz: el intent write-ahead se descarta, nunca se reproduce

    @Test
    fun un_exito_online_descarta_el_intent_write_ahead() = runTest {
        coEvery { api.addItems(any(), any(), any()) } returns successResponse()

        val result = repo.addItems(venueId, orderId, items, version = 1)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.id).isEqualTo(orderId)
        coVerify(exactly = 1) { outbox.enqueue(venueId, SyncIntentTypes.ADD_ITEMS, any(), any()) } // write-ahead
        coVerify(exactly = 1) { outbox.discardPending(any()) } // ya no hace falta reproducirlo
    }

    @Test
    fun la_cancelacion_se_repropaga_y_NO_se_traga() = runTest {
        // CancellationException ES una RuntimeException — un catch(Exception) desnudo
        // se la traga. Debe repropagarse intacta. El intent write-ahead ya se
        // escribió antes del intento online — se queda PENDING (no sabemos si el
        // intento hubiera tenido éxito), nunca se descarta a ciegas.
        coEvery { api.addItems(any(), any(), any()) } throws CancellationException("cancelado")

        var propagated = false
        try {
            repo.addItems(venueId, orderId, items, version = 1)
        } catch (e: CancellationException) {
            propagated = true
        }

        assertThat(propagated).isTrue()
        coVerify(exactly = 1) { outbox.enqueue(venueId, SyncIntentTypes.ADD_ITEMS, any(), any()) }
        coVerify(exactly = 0) { outbox.discardPending(any()) }
    }

    // endregion

    private fun successResponse(): Response<OrderDetailResponse> = Response.success(
        OrderDetailResponse(success = true, data = OrderDetail(id = orderId, version = 2)),
    )

    private fun errorResponse(code: Int): Response<OrderDetailResponse> =
        Response.error(code, "{}".toResponseBody("application/json".toMediaTypeOrNull()))
}

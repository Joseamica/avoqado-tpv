package com.jaac.avoqado_tpv.features.tables.data

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.jaac.avoqado_tpv.features.tables.data.api.TablesApiService
import com.jaac.avoqado_tpv.features.tables.data.api.dto.OpenTableResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.OpenTableResult
import com.jaac.avoqado_tpv.features.tables.data.api.dto.OpenedOrder
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncIntentTypes
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * `TablesRepository.openTable` (Plan C, Task 7). A diferencia de [TablesRepository.addItems],
 * NO necesita write-ahead: `assignTable` (el servicio detrás de esta ruta,
 * verificado contra `table.tpv.service.ts` 2026-07-29) es idempotente POR
 * MESA — reusa la orden activa en vez de crear una segunda — así que un
 * DROP_RESPONSE reintentado offline sobre la MISMA mesa nunca duplica la
 * orden. Ver KDoc del método para el razonamiento completo.
 */
class TablesRepositoryOpenTableTest {

    private val api = mockk<TablesApiService>()
    private val outbox = mockk<SyncOutbox>()
    private lateinit var repo: TablesRepository

    private val venueId = "venue-1"
    private val tableId = "table-1"

    @Before
    fun setUp() {
        clearMocks(api, outbox)
        coEvery { outbox.enqueue(any(), any(), any(), any()) } returns "intent-id"
        repo = TablesRepository(api, outbox)
    }

    @Test
    fun exito_online_regresa_el_id_real_no_provisional() = runTest {
        coEvery { api.openTable(venueId, tableId, any()) } returns Response.success(
            OpenTableResponse(data = OpenTableResult(order = OpenedOrder(id = "order-real-1", orderNumber = "A-100", version = 2))),
        )

        val result = repo.openTable(venueId, tableId, covers = 4)

        assertThat(result.isSuccess).isTrue()
        val outcome = result.getOrNull()!!
        assertThat(outcome.orderId).isEqualTo("order-real-1")
        assertThat(outcome.orderNumber).isEqualTo("A-100")
        assertThat(outcome.version).isEqualTo(2)
        assertThat(outcome.isProvisional).isFalse()
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any()) }
    }

    @Test
    fun sin_red_abre_PROVISIONAL_y_encola_OPEN_TABLE() = runTest {
        coEvery { api.openTable(venueId, tableId, any()) } throws IOException("sin red")

        val result = repo.openTable(venueId, tableId, covers = 2)

        assertThat(result.isSuccess).isTrue() // el mesero sigue trabajando
        val outcome = result.getOrNull()!!
        assertThat(outcome.isProvisional).isTrue()
        assertThat(outcome.orderId).isNotEmpty() // UUID local generado
        assertThat(outcome.version).isEqualTo(1)
        // 🔴 `id` tiene un default (`UUID.randomUUID()`) — omitirlo en el verify
        // hace que Kotlin calcule SU PROPIO default (un UUID nuevo, distinto al
        // de la llamada real) y el matcher nunca calza. Siempre `any()` explícito
        // para params con default (mismo patrón que TablesRepositoryOfflineTest).
        coVerify(exactly = 1) { outbox.enqueue(venueId, SyncIntentTypes.OPEN_TABLE, any(), any()) }
    }

    @Test
    fun el_payload_encolado_trae_tableId_covers_y_el_localOrderId_generado() = runTest {
        coEvery { api.openTable(venueId, tableId, any()) } throws IOException("sin red")
        val payloadSlot = slot<JsonObject>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.OPEN_TABLE, capture(payloadSlot), any()) } returns "intent-id"

        val result = repo.openTable(venueId, tableId, covers = 5)

        val outcome = result.getOrNull()!!
        assertThat(payloadSlot.captured["tableId"].asString).isEqualTo(tableId)
        assertThat(payloadSlot.captured["covers"].asInt).isEqualTo(5)
        assertThat(payloadSlot.captured["localOrderId"].asString).isEqualTo(outcome.orderId)
    }

    @Test
    fun un_rechazo_de_negocio_TABLE_OWNED_BY_OTHER_se_propaga_sin_abrir_offline() = runTest {
        // 403 — mesa de otro mesero con propiedad forzada. Abrirla offline
        // sería una regla propia inventada por el cliente: el server ya dijo
        // que no, y eso se propaga tal cual (regla dura de Mesas).
        coEvery { api.openTable(venueId, tableId, any()) } returns errorResponse(403)

        val result = repo.openTable(venueId, tableId, covers = 2)

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any()) }
    }

    @Test
    fun un_throwable_desconocido_se_propaga_nunca_se_abre_offline() = runTest {
        coEvery { api.openTable(venueId, tableId, any()) } throws NullPointerException("bug propio")

        val result = repo.openTable(venueId, tableId, covers = 2)

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any()) }
    }

    private fun errorResponse(code: Int): Response<OpenTableResponse> =
        Response.error(code, "{}".toResponseBody("application/json".toMediaTypeOrNull()))
}

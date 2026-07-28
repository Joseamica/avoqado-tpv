package com.jaac.avoqado_tpv.features.tables.data.sync

import com.jaac.avoqado_tpv.features.tables.data.api.TablesApiService
import com.jaac.avoqado_tpv.features.tables.data.api.dto.AddItemsRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.ApplyDiscountRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.ApplyDiscountResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.ApplyServiceChargeRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.ApplyServiceChargeResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.FloorElementsResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.MergeOrdersRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.MergeOrdersResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.OpenTableRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.OpenTableResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.OrderDetailResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SimpleSuccessResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SplitOrderBySeatResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SplitOrderRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SplitOrderResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentsRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentsResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.TablesResponse
import retrofit2.Response

/**
 * Dobles de prueba de [TablesApiService] compartidos por los tests del outbox
 * (Task 3). Viven en `androidTest` porque `SyncOutboxTest`/`SyncOutboxReplayTest`
 * corren contra Room real (`Room.inMemoryDatabaseBuilder`), no hay MockK en este
 * source set (ver `app/build.gradle.kts` — solo `testImplementation`, nunca
 * `androidTestImplementation("io.mockk:mockk-android")`).
 */

/**
 * Revienta con [UnsupportedOperationException] en CUALQUIER llamada. Base de
 * [RecordingTablesApiService] vía delegación (`by`) — cada test override solo lo
 * que necesita, y una llamada no anticipada truena fuerte en vez de devolver un
 * default silencioso que enmascararía el bug.
 */
object UnimplementedTablesApiService : TablesApiService {
    private fun boom(): Nothing = throw UnsupportedOperationException(
        "UnimplementedTablesApiService no debe recibir esta llamada en este test.",
    )

    override suspend fun getTables(venueId: String): Response<TablesResponse> = boom()
    override suspend fun getFloorElements(venueId: String): Response<FloorElementsResponse> = boom()
    override suspend fun openTable(venueId: String, tableId: String, body: OpenTableRequest): Response<OpenTableResponse> = boom()
    override suspend fun clearTable(venueId: String, tableId: String): Response<SimpleSuccessResponse> = boom()
    override suspend fun getOrder(venueId: String, orderId: String): Response<OrderDetailResponse> = boom()
    override suspend fun addItems(venueId: String, orderId: String, body: AddItemsRequest): Response<OrderDetailResponse> = boom()
    override suspend fun splitOrder(venueId: String, orderId: String, body: SplitOrderRequest): Response<SplitOrderResponse> = boom()
    override suspend fun splitOrderBySeat(venueId: String, orderId: String): Response<SplitOrderBySeatResponse> = boom()
    override suspend fun mergeOrders(venueId: String, orderId: String, body: MergeOrdersRequest): Response<MergeOrdersResponse> = boom()
    override suspend fun applyServiceCharge(
        venueId: String,
        orderId: String,
        body: ApplyServiceChargeRequest,
    ): Response<ApplyServiceChargeResponse> = boom()
    override suspend fun applyDiscount(venueId: String, orderId: String, body: ApplyDiscountRequest): Response<ApplyDiscountResponse> =
        boom()
    override suspend fun syncIntents(venueId: String, body: SyncIntentsRequest): Response<SyncIntentsResponse> = boom()
}

/**
 * Captura cada `syncIntents` request (para verificar qué id viajó realmente al
 * server) y responde según una cola programable de [Answer] — uno por llamada,
 * el último se reusa si se agotan.
 *
 * El caso que importa de verdad es el reproducido en hardware real en Plan A:
 * el request LLEGA al server y el efecto SÍ ocurre, pero la respuesta se pierde
 * (`Answer.DropResponse`, simula un `IOException` de vuelta) — el cliente
 * reintenta, y lo único que evita cobrar dos veces es que el id (=idempotencyKey)
 * viaje IDÉNTICO en el segundo intento. Un fake que solo supiera devolver
 * éxito/fracaso no probaría ese escenario.
 */
class RecordingTablesApiService : TablesApiService by UnimplementedTablesApiService {
    val capturedRequests = mutableListOf<SyncIntentsRequest>()
    private val answers = ArrayDeque<Answer>()

    sealed class Answer {
        data class Reply(val response: SyncIntentsResponse) : Answer()

        /** El request YA surtió efecto en el server (o pudo haberlo hecho) pero la respuesta se pierde. */
        data object DropResponse : Answer()
    }

    fun enqueueReply(response: SyncIntentsResponse) {
        answers.addLast(Answer.Reply(response))
    }

    fun enqueueDroppedResponse() {
        answers.addLast(Answer.DropResponse)
    }

    override suspend fun syncIntents(venueId: String, body: SyncIntentsRequest): Response<SyncIntentsResponse> {
        capturedRequests.add(body)
        return when (val next = if (answers.isEmpty()) null else answers.removeFirst()) {
            is Answer.Reply -> Response.success(next.response)
            Answer.DropResponse -> throw java.io.IOException("Respuesta perdida (red intermitente) — el efecto pudo haber ocurrido")
            null -> throw IllegalStateException("RecordingTablesApiService sin respuesta programada para esta llamada")
        }
    }
}

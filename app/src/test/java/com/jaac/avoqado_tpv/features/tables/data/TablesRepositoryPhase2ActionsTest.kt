package com.jaac.avoqado_tpv.features.tables.data

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.features.tables.data.api.TablesApiService
import com.jaac.avoqado_tpv.features.tables.data.api.dto.ActiveStaffEntryDto
import com.jaac.avoqado_tpv.features.tables.data.api.dto.ActiveStaffPersonDto
import com.jaac.avoqado_tpv.features.tables.data.api.dto.ActiveStaffResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.MergeOrdersRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.MergeOrdersResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.MergeOrdersResult
import com.jaac.avoqado_tpv.features.tables.data.api.dto.MergedOrderSummary
import com.jaac.avoqado_tpv.features.tables.data.api.dto.OrderMoneySummary
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentAck
import com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentEntity
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncIntentTypes
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.math.BigDecimal

/**
 * Fase 2 (2026-08-03) — las 3 acciones que quedaban de la auditoría de
 * completitud original: `mergeOrders` / `cancelOrder` / `assignOrder`. Mismo
 * patrón que `TablesRepositoryPhase1ActionsTest`: mocks ESTRICTOS de
 * `api`/`outbox` con `coVerify(exactly = ...)` explícito.
 */
class TablesRepositoryPhase2ActionsTest {

    private val api = mockk<TablesApiService>()
    private val outbox = mockk<SyncOutbox>()
    private lateinit var repo: TablesRepository

    private val venueId = "venue-1"
    private val orderId = "order-1"

    @Before
    fun setUp() {
        clearMocks(api, outbox)
        coEvery { outbox.enqueue(any(), any(), any(), any()) } returns "intent-id"
        repo = TablesRepository(api, outbox)
    }

    // ══════════════════════════════════════════════════════════════════
    // mergeOrders — online-first, ruta dedicada `orders/{orderId}/merge`
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun mergeOrders_online_exitoso_no_encola_nada() = runTest {
        coEvery { api.mergeOrders(venueId, orderId, MergeOrdersRequest("order-2")) } returns Response.success(
            MergeOrdersResponse(
                data = MergeOrdersResult(
                    target = OrderMoneySummary(id = orderId, total = BigDecimal("560.00"), version = 5),
                    merged = MergedOrderSummary(id = "order-2", orderNumber = "A-202", items = 3),
                    tableFreed = true,
                ),
            ),
        )

        val result = repo.mergeOrders(venueId, orderId, "order-2")

        assertThat(result.isSuccess).isTrue()
        val outcome = result.getOrThrow()
        assertThat(outcome.queued).isFalse()
        assertThat(outcome.targetTotal).isEqualTo(BigDecimal("560.00"))
        assertThat(outcome.mergedOrderNumber).isEqualTo("A-202")
        assertThat(outcome.tableFreed).isTrue()
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any()) }
    }

    @Test
    fun mergeOrders_sourceOrderId_vacio_falla_sin_llamar_a_la_api() = runTest {
        val result = repo.mergeOrders(venueId, orderId, "")

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) { api.mergeOrders(any(), any(), any()) }
    }

    @Test
    fun mergeOrders_sin_red_encola_MERGE_ORDERS_con_target_y_source() = runTest {
        coEvery { api.mergeOrders(venueId, orderId, any()) } throws IOException("sin red")
        val payloadSlot = slot<JsonObject>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.MERGE_ORDERS, capture(payloadSlot), any()) } returns "intent-id"

        val result = repo.mergeOrders(venueId, orderId, "order-2")

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().queued).isTrue()
        assertThat(payloadSlot.captured["orderId"].asString).isEqualTo(orderId)
        assertThat(payloadSlot.captured["sourceOrderId"].asString).isEqualTo("order-2")
    }

    @Test
    fun mergeOrders_rechazo_de_negocio_NUNCA_se_encola() = runTest {
        coEvery { api.mergeOrders(venueId, orderId, any()) } returns
            errorResponse<MergeOrdersResponse>(400, "{\"message\":\"La cuenta origen ya está cerrada\"}")

        val result = repo.mergeOrders(venueId, orderId, "order-2")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(BackendHttpException::class.java)
        assertThat(result.exceptionOrNull()?.message).contains("ya está cerrada")
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any()) }
    }

    // ══════════════════════════════════════════════════════════════════
    // cancelOrder — SIEMPRE vía intent, mismo patrón que moveOrder/payCash
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun cancelOrder_se_escribe_write_ahead_ANTES_de_intentar_el_replay() = runTest {
        coEvery { outbox.replayNow(venueId, any()) } returns Unit

        repo.cancelOrder(venueId, orderId, isProvisional = false, reason = "Cliente se fue")

        coVerifyOrder {
            outbox.enqueue(venueId, SyncIntentTypes.CANCEL_ORDER, any(), any())
            outbox.replayNow(venueId, any())
        }
    }

    @Test
    fun cancelOrder_incluye_la_razon_cuando_se_da() = runTest {
        val payloadSlot = slot<JsonObject>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.CANCEL_ORDER, capture(payloadSlot), any()) } returns "intent-id"
        coEvery { outbox.replayNow(venueId, any()) } returns Unit

        repo.cancelOrder(venueId, orderId, isProvisional = false, reason = "Cliente se fue")

        assertThat(payloadSlot.captured["orderId"].asString).isEqualTo(orderId)
        assertThat(payloadSlot.captured["reason"].asString).isEqualTo("Cliente se fue")
    }

    @Test
    fun cancelOrder_sin_razon_NO_manda_el_campo_reason() = runTest {
        val payloadSlot = slot<JsonObject>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.CANCEL_ORDER, capture(payloadSlot), any()) } returns "intent-id"
        coEvery { outbox.replayNow(venueId, any()) } returns Unit

        repo.cancelOrder(venueId, orderId, isProvisional = false, reason = null)

        assertThat(payloadSlot.captured.has("reason")).isFalse()
    }

    @Test
    fun cancelOrder_ack_ACKED_regresa_queued_false() = runTest {
        val idSlot = slot<String>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.CANCEL_ORDER, any(), capture(idSlot)) } answers { idSlot.captured }
        coEvery { outbox.replayNow(venueId, any()) } coAnswers {
            val onAck = secondArg<suspend (SyncIntentEntity, SyncIntentAck) -> Unit>()
            onAck(
                SyncIntentEntity(id = idSlot.captured, venueId = venueId, seq = 1, type = SyncIntentTypes.CANCEL_ORDER, payloadJson = "{}"),
                SyncIntentAck(id = idSlot.captured, status = "ACKED", result = jsonOf("orderId" to orderId)),
            )
        }

        val result = repo.cancelOrder(venueId, orderId, isProvisional = false, reason = null)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().queued).isFalse()
    }

    @Test
    fun cancelOrder_sin_ack_se_ve_como_encolado_NUNCA_error() = runTest {
        coEvery { outbox.replayNow(venueId, any()) } returns Unit

        val result = repo.cancelOrder(venueId, orderId, isProvisional = false, reason = null)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().queued).isTrue()
    }

    @Test
    fun cancelOrder_ack_REJECTED_propaga_CancelOrderRejectedException() = runTest {
        val idSlot = slot<String>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.CANCEL_ORDER, any(), capture(idSlot)) } answers { idSlot.captured }
        coEvery { outbox.replayNow(venueId, any()) } coAnswers {
            val onAck = secondArg<suspend (SyncIntentEntity, SyncIntentAck) -> Unit>()
            onAck(
                SyncIntentEntity(id = idSlot.captured, venueId = venueId, seq = 1, type = SyncIntentTypes.CANCEL_ORDER, payloadJson = "{}"),
                SyncIntentAck(id = idSlot.captured, status = "REJECTED", errorCode = "BUSINESS_RULE", message = "Cannot cancel a paid order"),
            )
        }

        val result = repo.cancelOrder(venueId, orderId, isProvisional = false, reason = null)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(TablesRepository.CancelOrderRejectedException::class.java)
        assertThat(result.exceptionOrNull()?.message).contains("paid order")
    }

    @Test
    fun cancelOrder_sesion_provisional_manda_localOrderId_en_vez_de_orderId() = runTest {
        val payloadSlot = slot<JsonObject>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.CANCEL_ORDER, capture(payloadSlot), any()) } returns "intent-id"
        coEvery { outbox.replayNow(venueId, any()) } returns Unit

        repo.cancelOrder(venueId, "local-uuid-123", isProvisional = true, reason = null)

        assertThat(payloadSlot.captured.has("localOrderId")).isTrue()
        assertThat(payloadSlot.captured["localOrderId"].asString).isEqualTo("local-uuid-123")
        assertThat(payloadSlot.captured.has("orderId")).isFalse()
    }

    // ══════════════════════════════════════════════════════════════════
    // assignOrder — SIEMPRE vía intent; el staffId manda la atribución de
    // propina/comisión, así que va sin transformar.
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun assignOrder_staffId_vacio_falla_sin_encolar_nada() = runTest {
        val result = repo.assignOrder(venueId, orderId, isProvisional = false, staffId = "")

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any()) }
    }

    @Test
    fun assignOrder_se_escribe_write_ahead_ANTES_de_intentar_el_replay() = runTest {
        coEvery { outbox.replayNow(venueId, any()) } returns Unit

        repo.assignOrder(venueId, orderId, isProvisional = false, staffId = "staff-9")

        coVerifyOrder {
            outbox.enqueue(venueId, SyncIntentTypes.ASSIGN_ORDER, any(), any())
            outbox.replayNow(venueId, any())
        }
    }

    @Test
    fun assignOrder_manda_el_staffId_EXACTO_recibido_sin_transformarlo() = runTest {
        val payloadSlot = slot<JsonObject>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.ASSIGN_ORDER, capture(payloadSlot), any()) } returns "intent-id"
        coEvery { outbox.replayNow(venueId, any()) } returns Unit

        repo.assignOrder(venueId, orderId, isProvisional = false, staffId = "staff-9")

        assertThat(payloadSlot.captured["orderId"].asString).isEqualTo(orderId)
        assertThat(payloadSlot.captured["staffId"].asString).isEqualTo("staff-9")
    }

    @Test
    fun assignOrder_ack_ACKED_regresa_queued_false_con_version() = runTest {
        val idSlot = slot<String>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.ASSIGN_ORDER, any(), capture(idSlot)) } answers { idSlot.captured }
        coEvery { outbox.replayNow(venueId, any()) } coAnswers {
            val onAck = secondArg<suspend (SyncIntentEntity, SyncIntentAck) -> Unit>()
            onAck(
                SyncIntentEntity(id = idSlot.captured, venueId = venueId, seq = 1, type = SyncIntentTypes.ASSIGN_ORDER, payloadJson = "{}"),
                SyncIntentAck(id = idSlot.captured, status = "ACKED", result = jsonOf("orderId" to orderId, "version" to 7)),
            )
        }

        val result = repo.assignOrder(venueId, orderId, isProvisional = false, staffId = "staff-9")

        assertThat(result.isSuccess).isTrue()
        val outcome = result.getOrThrow()
        assertThat(outcome.queued).isFalse()
        assertThat(outcome.version).isEqualTo(7)
    }

    @Test
    fun assignOrder_sin_ack_se_ve_como_encolado_NUNCA_error() = runTest {
        coEvery { outbox.replayNow(venueId, any()) } returns Unit

        val result = repo.assignOrder(venueId, orderId, isProvisional = false, staffId = "staff-9")

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().queued).isTrue()
    }

    @Test
    fun assignOrder_ack_REJECTED_por_permiso_propaga_AssignOrderRejectedException() = runTest {
        val idSlot = slot<String>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.ASSIGN_ORDER, any(), capture(idSlot)) } answers { idSlot.captured }
        coEvery { outbox.replayNow(venueId, any()) } coAnswers {
            val onAck = secondArg<suspend (SyncIntentEntity, SyncIntentAck) -> Unit>()
            onAck(
                SyncIntentEntity(id = idSlot.captured, venueId = venueId, seq = 1, type = SyncIntentTypes.ASSIGN_ORDER, payloadJson = "{}"),
                SyncIntentAck(id = idSlot.captured, status = "REJECTED", errorCode = "PERMISSION_DENIED", message = "No tienes permiso para reproducir ASSIGN_ORDER."),
            )
        }

        val result = repo.assignOrder(venueId, orderId, isProvisional = false, staffId = "staff-9")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(TablesRepository.AssignOrderRejectedException::class.java)
        assertThat((result.exceptionOrNull() as TablesRepository.AssignOrderRejectedException).errorCode).isEqualTo("PERMISSION_DENIED")
    }

    @Test
    fun assignOrder_sesion_provisional_manda_localOrderId_en_vez_de_orderId() = runTest {
        val payloadSlot = slot<JsonObject>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.ASSIGN_ORDER, capture(payloadSlot), any()) } returns "intent-id"
        coEvery { outbox.replayNow(venueId, any()) } returns Unit

        repo.assignOrder(venueId, "local-uuid-123", isProvisional = true, staffId = "staff-9")

        assertThat(payloadSlot.captured.has("localOrderId")).isTrue()
        assertThat(payloadSlot.captured["localOrderId"].asString).isEqualTo("local-uuid-123")
        assertThat(payloadSlot.captured.has("orderId")).isFalse()
    }

    // ══════════════════════════════════════════════════════════════════
    // getActiveStaff — lectura pura para el picker de ASSIGN_ORDER
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun getActiveStaff_mapea_nombre_y_ON_BREAK_a_dominio() = runTest {
        coEvery { api.getActiveStaff(venueId) } returns Response.success(
            ActiveStaffResponse(
                data = listOf(
                    ActiveStaffEntryDto(id = "te1", staffId = "staff-1", status = "CLOCKED_IN", staff = ActiveStaffPersonDto(firstName = "Fátima", lastName = "Flores", employeeCode = "EMP-001")),
                    ActiveStaffEntryDto(id = "te2", staffId = "staff-2", status = "ON_BREAK", staff = ActiveStaffPersonDto(firstName = "Diego", lastName = "Ruiz")),
                ),
            ),
        )

        val result = repo.getActiveStaff(venueId)

        assertThat(result.isSuccess).isTrue()
        val staff = result.getOrThrow()
        assertThat(staff).hasSize(2)
        assertThat(staff[0].staffId).isEqualTo("staff-1")
        assertThat(staff[0].name).isEqualTo("Fátima Flores")
        assertThat(staff[0].onBreak).isFalse()
        assertThat(staff[1].onBreak).isTrue()
    }

    @Test
    fun getActiveStaff_403_por_falta_de_permiso_se_propaga_como_fallo() = runTest {
        coEvery { api.getActiveStaff(venueId) } returns errorResponse<ActiveStaffResponse>(403, "{\"message\":\"Forbidden\"}")

        val result = repo.getActiveStaff(venueId)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(BackendHttpException::class.java)
    }

    private fun jsonOf(vararg pairs: Pair<String, Any>): JsonObject =
        JsonObject().apply {
            pairs.forEach { (k, v) ->
                when (v) {
                    is Int -> addProperty(k, v)
                    else -> addProperty(k, v.toString())
                }
            }
        }

    private inline fun <reified T> errorResponse(code: Int, body: String = "{}"): Response<T> =
        Response.error(code, body.toResponseBody("application/json".toMediaTypeOrNull()))
}

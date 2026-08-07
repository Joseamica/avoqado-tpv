package com.jaac.avoqado_tpv.features.tables.data

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.features.tables.data.api.TablesApiService
import com.jaac.avoqado_tpv.features.tables.data.api.dto.ApplyDiscountResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.AvailableDiscountDto
import com.jaac.avoqado_tpv.features.tables.data.api.dto.AvailableDiscountsResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.CompOrderResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.CompOrderResult
import com.jaac.avoqado_tpv.features.tables.data.api.dto.DiscountApplyResult
import com.jaac.avoqado_tpv.features.tables.data.api.dto.OrderMoneySummary
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SplitOrderRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SplitOrderResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SplitOrderResult
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
 * Fase 1 (2026-08-03) — las 4 acciones que la auditoría de completitud
 * encontró sin UI: `splitOrder` / `applyDiscount`+`getAvailableDiscounts` /
 * `compOrder` / `moveOrder`. Mismo patrón que `TablesRepositoryPayCashTest`/
 * `TablesRepositoryOpenTableTest`: mocks ESTRICTOS de `api`/`outbox` con
 * `coVerify(exactly = ...)` explícito — nunca un mock relajado que pase en
 * falso.
 */
class TablesRepositoryPhase1ActionsTest {

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
    // splitOrder — TODO-O-NADA: una sola llamada con TODOS los itemIds
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun splitOrder_online_exitoso_no_encola_nada() = runTest {
        coEvery { api.splitOrder(venueId, orderId, SplitOrderRequest(listOf("i1", "i2"))) } returns Response.success(
            SplitOrderResponse(
                data = SplitOrderResult(
                    source = OrderMoneySummary(id = orderId, total = BigDecimal("50.00"), version = 3),
                    created = OrderMoneySummary(id = "order-2", orderNumber = "A-101", total = BigDecimal("30.00")),
                ),
            ),
        )

        val result = repo.splitOrder(venueId, orderId, listOf("i1", "i2"))

        assertThat(result.isSuccess).isTrue()
        val outcome = result.getOrThrow()
        assertThat(outcome.queued).isFalse()
        assertThat(outcome.createdOrderId).isEqualTo("order-2")
        assertThat(outcome.createdOrderNumber).isEqualTo("A-101")
        assertThat(outcome.sourceVersion).isEqualTo(3)
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any()) }
    }

    /** El caso que el enunciado pide explícitamente: split todo-o-nada, NUNCA una llamada por artículo. */
    @Test
    fun splitOrder_manda_TODOS_los_itemIds_en_UNA_sola_llamada_nunca_por_articulo() = runTest {
        coEvery { api.splitOrder(venueId, orderId, any()) } returns Response.success(
            SplitOrderResponse(data = SplitOrderResult(source = OrderMoneySummary(id = orderId), created = OrderMoneySummary(id = "order-2"))),
        )

        repo.splitOrder(venueId, orderId, listOf("i1", "i2", "i3"))

        coVerify(exactly = 1) { api.splitOrder(venueId, orderId, SplitOrderRequest(listOf("i1", "i2", "i3"))) }
    }

    @Test
    fun splitOrder_sin_red_encola_SPLIT_ORDER_con_los_itemRefs_completos() = runTest {
        coEvery { api.splitOrder(venueId, orderId, any()) } throws IOException("sin red")
        val payloadSlot = slot<JsonObject>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.SPLIT_ORDER, capture(payloadSlot), any()) } returns "intent-id"

        val result = repo.splitOrder(venueId, orderId, listOf("i1", "i2"))

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().queued).isTrue()
        assertThat(payloadSlot.captured["orderId"].asString).isEqualTo(orderId)
        val itemRefs = payloadSlot.captured.getAsJsonArray("itemRefs").map { it.asString }
        assertThat(itemRefs).containsExactly("i1", "i2")
    }

    @Test
    fun splitOrder_itemIds_vacio_falla_sin_llamar_a_la_api() = runTest {
        val result = repo.splitOrder(venueId, orderId, emptyList())

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) { api.splitOrder(any(), any(), any()) }
    }

    @Test
    fun splitOrder_rechazo_de_negocio_ITEMS_NOT_RESOLVED_NUNCA_se_encola() = runTest {
        coEvery { api.splitOrder(venueId, orderId, any()) } returns errorResponse<SplitOrderResponse>(400)

        val result = repo.splitOrder(venueId, orderId, listOf("i1"))

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(BackendHttpException::class.java)
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any()) }
    }

    // ══════════════════════════════════════════════════════════════════
    // applyDiscount / getAvailableDiscounts
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun applyDiscount_online_exitoso_no_encola_nada() = runTest {
        coEvery { api.applyDiscount(venueId, orderId, any()) } returns Response.success(
            ApplyDiscountResponse(data = DiscountApplyResult(amount = BigDecimal("50.00"), newOrderTotal = BigDecimal("450.00"))),
        )

        val result = repo.applyDiscount(venueId, orderId, "d1")

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().amount).isEqualTo(BigDecimal("50.00"))
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any()) }
    }

    @Test
    fun applyDiscount_sin_red_encola_APPLY_DISCOUNT_con_discountId() = runTest {
        coEvery { api.applyDiscount(venueId, orderId, any()) } throws IOException("sin red")
        val payloadSlot = slot<JsonObject>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.APPLY_DISCOUNT, capture(payloadSlot), any()) } returns "intent-id"

        val result = repo.applyDiscount(venueId, orderId, "d1")

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().queued).isTrue()
        assertThat(payloadSlot.captured["discountId"].asString).isEqualTo("d1")
    }

    @Test
    fun applyDiscount_rechazo_de_negocio_NUNCA_se_encola() = runTest {
        coEvery { api.applyDiscount(venueId, orderId, any()) } returns errorResponse<ApplyDiscountResponse>(400)

        val result = repo.applyDiscount(venueId, orderId, "d1")

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any()) }
    }

    @Test
    fun getAvailableDiscounts_mapea_el_dto_completo_a_dominio() = runTest {
        coEvery { api.getAvailableDiscounts(venueId, orderId) } returns Response.success(
            AvailableDiscountsResponse(
                data = listOf(
                    AvailableDiscountDto(
                        id = "d1",
                        name = "Cliente frecuente",
                        type = "PERCENTAGE",
                        value = BigDecimal("10"),
                        requiresApproval = false,
                        estimatedSavings = BigDecimal("45.00"),
                    ),
                ),
                count = 1,
            ),
        )

        val result = repo.getAvailableDiscounts(venueId, orderId)

        assertThat(result.isSuccess).isTrue()
        val discount = result.getOrThrow().single()
        assertThat(discount.id).isEqualTo("d1")
        assertThat(discount.requiresApproval).isFalse()
        assertThat(discount.estimatedSavings).isEqualTo(BigDecimal("45.00"))
    }

    // ══════════════════════════════════════════════════════════════════
    // removeDiscount — paridad Android 2026-08-06 (paridad-android-tpv.md,
    // Hallazgo #4): SIEMPRE online, un fallo de red NUNCA se encola — misma
    // asimetría que compOrder(itemIds no vacío) de abajo, no la de
    // applyDiscount de arriba.
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun removeDiscount_online_exitoso_no_encola_nada() = runTest {
        coEvery { api.removeDiscount(venueId, orderId, "d1") } returns Response.success(
            ApplyDiscountResponse(data = DiscountApplyResult(amount = BigDecimal("50.00"), newOrderTotal = BigDecimal("400.00"))),
        )

        val result = repo.removeDiscount(venueId, orderId, "d1")

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().queued).isFalse()
        assertThat(result.getOrThrow().newOrderTotal).isEqualTo(BigDecimal("400.00"))
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any()) }
    }

    @Test
    fun removeDiscount_sin_red_NUNCA_se_encola_se_propaga_como_necesita_conexion() = runTest {
        // A diferencia de applyDiscount (SÍ tiene intent APPLY_DISCOUNT),
        // "quitar" no tiene equivalente offline en el contrato de 14 tipos —
        // encolarlo silenciosamente sería un intent que el reducer nunca
        // sabría aplicar.
        coEvery { api.removeDiscount(venueId, orderId, "d1") } throws IOException("sin red")

        val result = repo.removeDiscount(venueId, orderId, "d1")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(TablesRepository.RemoveDiscountRequiresConnectionException::class.java)
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any()) }
    }

    @Test
    fun removeDiscount_rechazo_de_negocio_se_propaga_tal_cual_nunca_como_necesita_conexion() = runTest {
        // Ej. orden ya pagada, o el descuento ya lo quitó otro dispositivo —
        // esto NO es "necesita internet", es un rechazo real del server.
        coEvery { api.removeDiscount(venueId, orderId, "d1") } returns errorResponse<ApplyDiscountResponse>(400)

        val result = repo.removeDiscount(venueId, orderId, "d1")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(BackendHttpException::class.java)
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any()) }
    }

    // ══════════════════════════════════════════════════════════════════
    // compOrder — la asimetría: "toda la cuenta" SÍ encola, "artículo
    // puntual" NUNCA (el reducer de replay no acepta itemIds)
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun compOrder_toda_la_cuenta_online_exitoso_no_encola_nada() = runTest {
        coEvery { api.compOrder(venueId, orderId, any()) } returns Response.success(
            CompOrderResponse(data = CompOrderResult(id = orderId, total = BigDecimal.ZERO, version = 2)),
        )

        val result = repo.compOrder(venueId, orderId, emptyList(), "Reclamo del cliente", "staff-1")

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().queued).isFalse()
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any()) }
    }

    @Test
    fun compOrder_toda_la_cuenta_sin_red_encola_COMP_ORDER() = runTest {
        coEvery { api.compOrder(venueId, orderId, any()) } throws IOException("sin red")
        val payloadSlot = slot<JsonObject>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.COMP_ORDER, capture(payloadSlot), any()) } returns "intent-id"

        val result = repo.compOrder(venueId, orderId, emptyList(), "Reclamo del cliente", "staff-1")

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().queued).isTrue()
        assertThat(payloadSlot.captured["reason"].asString).isEqualTo("Reclamo del cliente")
    }

    /**
     * 🔴 La garantía crítica de dinero de esta acción: el reducer de replay
     * (`COMP_ORDER`) NUNCA lee `itemIds` — encolar una cortesía de artículo
     * puntual aplicaría cortesía a la cuenta ENTERA cuando por fin reproduzca.
     * `compOrder` con `itemIds` no vacío jamás debe llamar `outbox.enqueue`,
     * sin importar la clasificación del fallo.
     */
    @Test
    fun compOrder_articulo_puntual_sin_red_NUNCA_se_encola_se_propaga_con_razon_clara() = runTest {
        coEvery { api.compOrder(venueId, orderId, any()) } throws IOException("sin red")

        val result = repo.compOrder(venueId, orderId, listOf("i1"), "Error de entrada", "staff-1")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(TablesRepository.ItemCompRequiresConnectionException::class.java)
        assertThat(result.exceptionOrNull()?.message).contains("conexión")
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any()) }
    }

    @Test
    fun compOrder_articulo_puntual_rechazo_de_negocio_propaga_el_mensaje_original() = runTest {
        coEvery { api.compOrder(venueId, orderId, any()) } returns errorResponse<CompOrderResponse>(400, "{\"message\":\"El artículo ya está dado de cortesía\"}")

        val result = repo.compOrder(venueId, orderId, listOf("i1"), "Error de entrada", "staff-1")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(BackendHttpException::class.java)
        assertThat(result.exceptionOrNull()?.message).contains("ya está dado de cortesía")
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any()) }
    }

    // ══════════════════════════════════════════════════════════════════
    // moveOrder — SIEMPRE vía intent (sin ruta online), mismo patrón que payCash
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun moveOrder_se_escribe_write_ahead_ANTES_de_intentar_el_replay() = runTest {
        coEvery { outbox.replayNow(venueId, any()) } returns Unit

        repo.moveOrder(venueId, orderId, isProvisional = false, targetTableId = "mesa-9")

        coVerifyOrder {
            outbox.enqueue(venueId, SyncIntentTypes.MOVE_ORDER, any(), any())
            outbox.replayNow(venueId, any())
        }
    }

    @Test
    fun moveOrder_ack_ACKED_regresa_queued_false_con_version() = runTest {
        val idSlot = slot<String>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.MOVE_ORDER, any(), capture(idSlot)) } answers { idSlot.captured }
        coEvery { outbox.replayNow(venueId, any()) } coAnswers {
            val onAck = secondArg<suspend (SyncIntentEntity, SyncIntentAck) -> Unit>()
            onAck(
                SyncIntentEntity(id = idSlot.captured, venueId = venueId, seq = 1, type = SyncIntentTypes.MOVE_ORDER, payloadJson = "{}"),
                SyncIntentAck(id = idSlot.captured, status = "ACKED", result = jsonOf("orderId" to orderId, "version" to 4)),
            )
        }

        val result = repo.moveOrder(venueId, orderId, isProvisional = false, targetTableId = "mesa-9")

        assertThat(result.isSuccess).isTrue()
        val outcome = result.getOrThrow()
        assertThat(outcome.queued).isFalse()
        assertThat(outcome.targetTableId).isEqualTo("mesa-9")
        assertThat(outcome.version).isEqualTo(4)
    }

    @Test
    fun moveOrder_sin_ack_se_ve_como_encolado_NUNCA_error() = runTest {
        coEvery { outbox.replayNow(venueId, any()) } returns Unit

        val result = repo.moveOrder(venueId, orderId, isProvisional = false, targetTableId = "mesa-9")

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().queued).isTrue()
    }

    @Test
    fun moveOrder_ack_REJECTED_propaga_MoveOrderRejectedException() = runTest {
        val idSlot = slot<String>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.MOVE_ORDER, any(), capture(idSlot)) } answers { idSlot.captured }
        coEvery { outbox.replayNow(venueId, any()) } coAnswers {
            val onAck = secondArg<suspend (SyncIntentEntity, SyncIntentAck) -> Unit>()
            onAck(
                SyncIntentEntity(id = idSlot.captured, venueId = venueId, seq = 1, type = SyncIntentTypes.MOVE_ORDER, payloadJson = "{}"),
                SyncIntentAck(id = idSlot.captured, status = "REJECTED", errorCode = "BUSINESS_RULE", message = "La mesa 9 ya tiene una cuenta abierta"),
            )
        }

        val result = repo.moveOrder(venueId, orderId, isProvisional = false, targetTableId = "mesa-9")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(TablesRepository.MoveOrderRejectedException::class.java)
        assertThat(result.exceptionOrNull()?.message).contains("ya tiene una cuenta")
    }

    @Test
    fun moveOrder_sesion_provisional_manda_localOrderId_en_vez_de_orderId() = runTest {
        val payloadSlot = slot<JsonObject>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.MOVE_ORDER, capture(payloadSlot), any()) } returns "intent-id"
        coEvery { outbox.replayNow(venueId, any()) } returns Unit

        repo.moveOrder(venueId, "local-uuid-123", isProvisional = true, targetTableId = "mesa-9")

        assertThat(payloadSlot.captured.has("localOrderId")).isTrue()
        assertThat(payloadSlot.captured["localOrderId"].asString).isEqualTo("local-uuid-123")
        assertThat(payloadSlot.captured.has("orderId")).isFalse()
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

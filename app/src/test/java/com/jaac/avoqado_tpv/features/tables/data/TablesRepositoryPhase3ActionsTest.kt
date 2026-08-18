package com.jaac.avoqado_tpv.features.tables.data

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import com.jaac.avoqado_tpv.features.tables.data.api.TablesApiService
import com.jaac.avoqado_tpv.features.tables.data.api.dto.ApplyServiceChargeRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.ApplyServiceChargeResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.AvailableServiceChargeDto
import com.jaac.avoqado_tpv.features.tables.data.api.dto.AvailableServiceChargesResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.OrderMoneySummary
import com.jaac.avoqado_tpv.features.tables.data.api.dto.OrderTotals
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SplitBySeatResult
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SplitOrderBySeatResponse
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
 * Fase 3 (2026-08-03) — los 3 últimos intents de los 14: `applyServiceCharge` /
 * `splitOrderBySeat` / `updateOrderDetails`. Mismo patrón que
 * `TablesRepositoryPhase1ActionsTest`/`TablesRepositoryPhase2ActionsTest`: mocks
 * ESTRICTOS de `api`/`outbox` con `coVerify(exactly = ...)` explícito.
 */
class TablesRepositoryPhase3ActionsTest {

    private val api = mockk<TablesApiService>()
    private val outbox = mockk<SyncOutbox>()
    /** `tables:pay-any` — irrelevante para estos tests; el default niega el override. */
    private val permissions = mockk<PermissionsRepository>(relaxed = true)  // relaxed → hasPermission = false
    private lateinit var repo: TablesRepository

    private val venueId = "venue-1"
    private val orderId = "order-1"

    @Before
    fun setUp() {
        clearMocks(api, outbox)
        coEvery { outbox.enqueue(any(), any(), any(), any()) } returns "intent-id"
        repo = TablesRepository(api, outbox, permissions)
    }

    // ══════════════════════════════════════════════════════════════════
    // getAvailableServiceCharges — lectura pura, catálogo del venue
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun getAvailableServiceCharges_mapea_el_catalogo_completo_a_dominio() = runTest {
        coEvery { api.getServiceCharges(venueId) } returns Response.success(
            AvailableServiceChargesResponse(
                data = listOf(
                    AvailableServiceChargeDto(id = "sc1", name = "Propina automática", type = "PERCENTAGE", value = BigDecimal("15"), taxable = true, autoApplyMinCovers = 6),
                    AvailableServiceChargeDto(id = "sc2", name = "Descorche", type = "FIXED_AMOUNT", value = BigDecimal("150.00"), taxable = true, autoApplyMinCovers = null),
                ),
            ),
        )

        val result = repo.getAvailableServiceCharges(venueId)

        assertThat(result.isSuccess).isTrue()
        val charges = result.getOrThrow()
        assertThat(charges).hasSize(2)
        assertThat(charges[0].id).isEqualTo("sc1")
        assertThat(charges[0].autoApplyMinCovers).isEqualTo(6)
        assertThat(charges[1].autoApplyMinCovers).isNull()
        // Dinero: BigDecimal EXACTO, no vía Double — "150.00" (escala 2) es el
        // caso que `BigDecimal(Double)`/`.toBigDecimal()` rompe en silencio
        // (colapsa a "150", escala 0 — `equals()` de BigDecimal compara escala,
        // así que un round-trip por Double SÍ se detecta aquí aunque el valor
        // impreso "se vea igual").
        assertThat(charges[0].value).isEqualTo(BigDecimal("15"))
        assertThat(charges[1].value).isEqualTo(BigDecimal("150.00"))
    }

    @Test
    fun getAvailableServiceCharges_fallo_del_server_se_propaga() = runTest {
        coEvery { api.getServiceCharges(venueId) } returns errorResponse<AvailableServiceChargesResponse>(500, "{\"message\":\"boom\"}")

        val result = repo.getAvailableServiceCharges(venueId)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(BackendHttpException::class.java)
    }

    // ══════════════════════════════════════════════════════════════════
    // applyServiceCharge — online-first, vive en Cobrar (afecta el total)
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun applyServiceCharge_serviceChargeId_vacio_falla_sin_llamar_a_la_api() = runTest {
        val result = repo.applyServiceCharge(venueId, orderId, "")

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) { api.applyServiceCharge(any(), any(), any()) }
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any()) }
    }

    @Test
    fun applyServiceCharge_online_exitoso_no_encola_nada() = runTest {
        coEvery { api.applyServiceCharge(venueId, orderId, ApplyServiceChargeRequest(serviceChargeId = "sc1")) } returns Response.success(
            ApplyServiceChargeResponse(data = OrderTotals(total = BigDecimal("219.50"), serviceChargeAmount = BigDecimal("19.50"), version = 3)),
        )

        val result = repo.applyServiceCharge(venueId, orderId, "sc1")

        assertThat(result.isSuccess).isTrue()
        val outcome = result.getOrThrow()
        assertThat(outcome.queued).isFalse()
        assertThat(outcome.total).isEqualTo(BigDecimal("219.50"))
        assertThat(outcome.serviceChargeAmount).isEqualTo(BigDecimal("19.50"))
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any()) }
    }

    @Test
    fun applyServiceCharge_sin_red_encola_APPLY_SERVICE_CHARGE_con_orderId_y_serviceChargeId() = runTest {
        coEvery { api.applyServiceCharge(venueId, orderId, any()) } throws IOException("sin red")
        val payloadSlot = slot<JsonObject>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.APPLY_SERVICE_CHARGE, capture(payloadSlot), any()) } returns "intent-id"

        val result = repo.applyServiceCharge(venueId, orderId, "sc1")

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().queued).isTrue()
        assertThat(payloadSlot.captured["orderId"].asString).isEqualTo(orderId)
        assertThat(payloadSlot.captured["serviceChargeId"].asString).isEqualTo("sc1")
    }

    @Test
    fun applyServiceCharge_rechazo_de_negocio_duplicado_NUNCA_se_encola() = runTest {
        coEvery { api.applyServiceCharge(venueId, orderId, any()) } returns
            errorResponse<ApplyServiceChargeResponse>(400, "{\"message\":\"Ese cobro ya está aplicado a la cuenta\"}")

        val result = repo.applyServiceCharge(venueId, orderId, "sc1")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("ya está aplicado")
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any()) }
    }

    // ══════════════════════════════════════════════════════════════════
    // removeServiceCharge — paridad Android 2026-08-06 (paridad-android-tpv.md,
    // Hallazgo #4, último hueco cerrado por avoqado-server commit a0470a74):
    // SIEMPRE online, un fallo de red NUNCA se encola — misma asimetría que
    // removeDiscount (TablesRepositoryPhase1ActionsTest), no la de
    // applyServiceCharge de arriba.
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun removeServiceCharge_online_exitoso_no_encola_nada() = runTest {
        coEvery { api.removeServiceCharge(venueId, orderId, "sc1") } returns Response.success(
            ApplyServiceChargeResponse(data = OrderTotals(total = BigDecimal("200.00"), serviceChargeAmount = BigDecimal.ZERO, version = 4)),
        )

        val result = repo.removeServiceCharge(venueId, orderId, "sc1")

        assertThat(result.isSuccess).isTrue()
        val outcome = result.getOrThrow()
        assertThat(outcome.queued).isFalse()
        // 🔴 Aserción de dinero explícita — un sabotaje que solo revise `queued`
        // pasaría en falso si el mapeo de `totals.total`/`serviceChargeAmount`
        // se rompiera (p.ej. si alguien confundiera el orden de los campos).
        assertThat(outcome.total).isEqualTo(BigDecimal("200.00"))
        assertThat(outcome.serviceChargeAmount).isEqualTo(BigDecimal.ZERO)
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any()) }
    }

    @Test
    fun removeServiceCharge_sin_red_NUNCA_se_encola_se_propaga_como_necesita_conexion() = runTest {
        // A diferencia de applyServiceCharge (SÍ tiene intent
        // APPLY_SERVICE_CHARGE), "quitar" no tiene equivalente offline en el
        // contrato de 14 tipos — encolarlo silenciosamente sería un intent que
        // el reducer nunca sabría aplicar.
        coEvery { api.removeServiceCharge(venueId, orderId, "sc1") } throws IOException("sin red")

        val result = repo.removeServiceCharge(venueId, orderId, "sc1")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(TablesRepository.RemoveServiceChargeRequiresConnectionException::class.java)
        assertThat(result.exceptionOrNull()?.message).contains("conexión")
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any()) }
    }

    @Test
    fun removeServiceCharge_rechazo_de_negocio_se_propaga_tal_cual_nunca_como_necesita_conexion() = runTest {
        // Ej. orden ya pagada, o el cargo ya lo quitó otro dispositivo — esto
        // NO es "necesita internet", es un rechazo real del server.
        coEvery { api.removeServiceCharge(venueId, orderId, "sc1") } returns
            errorResponse<ApplyServiceChargeResponse>(400, "{\"message\":\"No se puede modificar una orden ya pagada\"}")

        val result = repo.removeServiceCharge(venueId, orderId, "sc1")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(BackendHttpException::class.java)
        assertThat(result.exceptionOrNull()?.message).contains("ya pagada")
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any()) }
    }

    // ══════════════════════════════════════════════════════════════════
    // splitOrderBySeat — todo-o-nada GARANTIZADO por el server (una sola
    // transacción, sin body); el cliente nunca fragmenta la llamada.
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun splitOrderBySeat_online_exitoso_no_encola_nada() = runTest {
        coEvery { api.splitOrderBySeat(venueId, orderId) } returns Response.success(
            SplitOrderBySeatResponse(
                data = SplitBySeatResult(
                    source = OrderMoneySummary(id = orderId, total = BigDecimal("40.00"), seat = 1),
                    created = listOf(
                        OrderMoneySummary(id = "order-2", orderNumber = "A-101-S2", total = BigDecimal("25.00"), seat = 2),
                        OrderMoneySummary(id = "order-3", orderNumber = "A-101-S3", total = BigDecimal("30.00"), seat = 3),
                    ),
                ),
            ),
        )

        val result = repo.splitOrderBySeat(venueId, orderId)

        assertThat(result.isSuccess).isTrue()
        val outcome = result.getOrThrow()
        assertThat(outcome.queued).isFalse()
        assertThat(outcome.createdCount).isEqualTo(2)
        assertThat(outcome.sourceSeat).isEqualTo(1)
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any()) }
    }

    /**
     * 🔴 La garantía "todo o nada" pedida explícitamente: UNA sola llamada por
     * acción, nunca una por asiento — la atomicidad la da la transacción del
     * server, el cliente no debe fragmentarla jamás (ni con reintentos
     * silenciosos ni con un loop "por si acaso").
     */
    @Test
    fun splitOrderBySeat_manda_UNA_sola_llamada_nunca_fragmentada_por_asiento() = runTest {
        coEvery { api.splitOrderBySeat(venueId, orderId) } returns Response.success(
            SplitOrderBySeatResponse(data = SplitBySeatResult(source = OrderMoneySummary(id = orderId, seat = 1), created = emptyList())),
        )

        repo.splitOrderBySeat(venueId, orderId)

        coVerify(exactly = 1) { api.splitOrderBySeat(venueId, orderId) }
    }

    @Test
    fun splitOrderBySeat_rechazo_por_menos_de_dos_asientos_NUNCA_se_encola() = runTest {
        coEvery { api.splitOrderBySeat(venueId, orderId) } returns
            errorResponse<SplitOrderBySeatResponse>(400, "{\"message\":\"Se necesitan al menos dos asientos con artículos para dividir por puesto\"}")

        val result = repo.splitOrderBySeat(venueId, orderId)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("al menos dos asientos")
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any()) }
    }

    @Test
    fun splitOrderBySeat_sin_red_encola_SPLIT_BY_SEAT_con_orderId() = runTest {
        coEvery { api.splitOrderBySeat(venueId, orderId) } throws IOException("sin red")
        val payloadSlot = slot<JsonObject>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.SPLIT_BY_SEAT, capture(payloadSlot), any()) } returns "intent-id"

        val result = repo.splitOrderBySeat(venueId, orderId)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().queued).isTrue()
        assertThat(payloadSlot.captured["orderId"].asString).isEqualTo(orderId)
    }

    // ══════════════════════════════════════════════════════════════════
    // updateOrderDetails — SIEMPRE vía intent, mismo patrón que moveOrder/cancelOrder
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun updateOrderDetails_sin_ningun_campo_falla_sin_encolar_nada() = runTest {
        val result = repo.updateOrderDetails(venueId, orderId, isProvisional = false, name = null, covers = null)

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any()) }
    }

    @Test
    fun updateOrderDetails_se_escribe_write_ahead_ANTES_de_intentar_el_replay() = runTest {
        coEvery { outbox.replayNow(venueId, any()) } returns Unit

        repo.updateOrderDetails(venueId, orderId, isProvisional = false, name = "Cumpleaños de Ana", covers = null)

        coVerifyOrder {
            outbox.enqueue(venueId, SyncIntentTypes.UPDATE_DETAILS, any(), any())
            outbox.replayNow(venueId, any())
        }
    }

    @Test
    fun updateOrderDetails_manda_solo_los_campos_no_nulos() = runTest {
        val payloadSlot = slot<JsonObject>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.UPDATE_DETAILS, capture(payloadSlot), any()) } returns "intent-id"
        coEvery { outbox.replayNow(venueId, any()) } returns Unit

        repo.updateOrderDetails(venueId, orderId, isProvisional = false, name = null, covers = 6)

        assertThat(payloadSlot.captured.has("covers")).isTrue()
        assertThat(payloadSlot.captured["covers"].asInt).isEqualTo(6)
        assertThat(payloadSlot.captured.has("name")).isFalse()
    }

    @Test
    fun updateOrderDetails_sesion_provisional_manda_localOrderId_en_vez_de_orderId() = runTest {
        val payloadSlot = slot<JsonObject>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.UPDATE_DETAILS, capture(payloadSlot), any()) } returns "intent-id"
        coEvery { outbox.replayNow(venueId, any()) } returns Unit

        repo.updateOrderDetails(venueId, "local-uuid-123", isProvisional = true, name = "Mesa 5", covers = null)

        assertThat(payloadSlot.captured.has("localOrderId")).isTrue()
        assertThat(payloadSlot.captured["localOrderId"].asString).isEqualTo("local-uuid-123")
        assertThat(payloadSlot.captured.has("orderId")).isFalse()
    }

    @Test
    fun updateOrderDetails_ack_ACKED_regresa_queued_false() = runTest {
        val idSlot = slot<String>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.UPDATE_DETAILS, any(), capture(idSlot)) } answers { idSlot.captured }
        coEvery { outbox.replayNow(venueId, any()) } coAnswers {
            val onAck = secondArg<suspend (SyncIntentEntity, SyncIntentAck) -> Unit>()
            onAck(
                SyncIntentEntity(id = idSlot.captured, venueId = venueId, seq = 1, type = SyncIntentTypes.UPDATE_DETAILS, payloadJson = "{}"),
                SyncIntentAck(id = idSlot.captured, status = "ACKED", result = jsonOf("orderId" to orderId)),
            )
        }

        val result = repo.updateOrderDetails(venueId, orderId, isProvisional = false, name = "Ana", covers = null)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().queued).isFalse()
    }

    @Test
    fun updateOrderDetails_sin_ack_se_ve_como_guardado_NUNCA_error() = runTest {
        coEvery { outbox.replayNow(venueId, any()) } returns Unit

        val result = repo.updateOrderDetails(venueId, orderId, isProvisional = false, name = "Ana", covers = null)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().queued).isTrue()
    }

    @Test
    fun updateOrderDetails_ack_REJECTED_propaga_UpdateDetailsRejectedException() = runTest {
        val idSlot = slot<String>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.UPDATE_DETAILS, any(), capture(idSlot)) } answers { idSlot.captured }
        coEvery { outbox.replayNow(venueId, any()) } coAnswers {
            val onAck = secondArg<suspend (SyncIntentEntity, SyncIntentAck) -> Unit>()
            onAck(
                SyncIntentEntity(id = idSlot.captured, venueId = venueId, seq = 1, type = SyncIntentTypes.UPDATE_DETAILS, payloadJson = "{}"),
                SyncIntentAck(id = idSlot.captured, status = "REJECTED", errorCode = "INVALID_PAYLOAD", message = "covers inválido"),
            )
        }

        val result = repo.updateOrderDetails(venueId, orderId, isProvisional = false, name = null, covers = 999)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(TablesRepository.UpdateDetailsRejectedException::class.java)
        assertThat(result.exceptionOrNull()?.message).contains("covers inválido")
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

package com.jaac.avoqado_tpv.features.tables.data

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import com.jaac.avoqado_tpv.features.tables.data.api.TablesApiService
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SimpleSuccessResponse
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
 * `TablesRepository.payCash`/`clearTable` — Plan C, Task 8 ("la costura de
 * pago", la tarea más delicada del plan: dinero real).
 *
 * `payCash` NO tiene una ruta online dedicada bajo `/tpv` (la única genérica,
 * `POST tpv/venues/{venueId}/orders/{orderId}`, es territorio de "Cobrar" —
 * intocable). En vez de eso viaja SIEMPRE como intent `PAY_CASH`: se escribe
 * write-ahead ([SyncOutbox.enqueue], mismo patrón que `addItems`) y se intenta
 * reproducir de inmediato ([SyncOutbox.replayNow]). Su `id` ES el
 * `idempotencyKey` que el server usa tal cual (`applyPayCash` →
 * `payCashOrder({ idempotencyKey: intent.id })`) — la regla dura del founder:
 * "PAY_CASH carries an idempotencyKey; the server dedupes on
 * [venueId, idempotencyKey]. Without it a retry charges twice."
 *
 * `api`/`syncOutbox` son mocks ESTRICTOS con `coVerify(exactly = ...)`
 * explícito en cada test — la vacuous-test trap ya documentada en
 * `TablesRepositoryOfflineTest.kt` (mock relajado + `UnconfinedTestDispatcher`
 * = verde sin proteger nada).
 */
class TablesRepositoryPayCashTest {

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
        repo = TablesRepository(api, outbox, permissions)
    }

    // region — write-ahead + idempotencyKey

    @Test
    fun el_intent_PAY_CASH_se_escribe_write_ahead_ANTES_de_intentar_el_replay() = runTest {
        val idSlot = slot<String>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.PAY_CASH, any(), capture(idSlot)) } answers { idSlot.captured }
        coEvery { outbox.replayNow(venueId, any()) } returns Unit

        repo.payCash(venueId, orderId, isProvisional = false, amount = BigDecimal("150.00"))

        coVerifyOrder {
            outbox.enqueue(venueId, SyncIntentTypes.PAY_CASH, any(), any())
            outbox.replayNow(venueId, any())
        }
    }

    @Test
    fun el_MISMO_id_viaja_como_idempotencyKey_al_intent_escrito_y_al_ack_reproducido() = runTest {
        // El id que enqueue() recibe (write-ahead) es el MISMO que applyPayCash
        // del server usa como idempotencyKey — este test prueba que
        // TablesRepository nunca genera un segundo id para el replay: es el
        // mecanismo COMPLETO de la regla dura del founder.
        val idSlot = slot<String>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.PAY_CASH, any(), capture(idSlot)) } answers { idSlot.captured }
        coEvery { outbox.replayNow(venueId, any()) } coAnswers {
            val onAck = secondArg<suspend (SyncIntentEntity, SyncIntentAck) -> Unit>()
            // Simula el server respondiendo el MISMO id que se escribió — si
            // TablesRepository generara un id distinto para el replay, este
            // ack (con el id capturado de verdad) nunca haría match y el
            // resultado se vería como "sin ack" (queued) en vez de ACKED.
            onAck(
                SyncIntentEntity(id = idSlot.captured, venueId = venueId, seq = 1, type = SyncIntentTypes.PAY_CASH, payloadJson = "{}"),
                SyncIntentAck(id = idSlot.captured, status = "ACKED", result = jsonOf("paymentId" to "pay-1", "orderNumber" to "A-100")),
            )
        }

        val result = repo.payCash(venueId, orderId, isProvisional = false, amount = BigDecimal("150.00"))

        assertThat(result.isSuccess).isTrue()
        val outcome = result.getOrThrow()
        assertThat(outcome.queued).isFalse()
        assertThat(outcome.paymentId).isEqualTo("pay-1")
        assertThat(outcome.orderNumber).isEqualTo("A-100")
    }

    // endregion

    // region — sin red / RETRY: se ve como éxito encolado, NUNCA error

    @Test
    fun sin_ack_para_nuestro_intent_el_cobro_se_ve_como_exito_encolado() = runTest {
        // replayNow no lanza cuando no hay red (mismo contrato documentado en
        // su KDoc) — simplemente nunca invoca el callback para nuestro id.
        coEvery { outbox.enqueue(any(), any(), any(), any()) } returns "intent-1"
        coEvery { outbox.replayNow(venueId, any()) } returns Unit

        val result = repo.payCash(venueId, orderId, isProvisional = false, amount = BigDecimal("150.00"))

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().queued).isTrue()
    }

    @Test
    fun un_ack_RETRY_se_ve_como_exito_encolado_NUNCA_error() = runTest {
        val idSlot = slot<String>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.PAY_CASH, any(), capture(idSlot)) } answers { idSlot.captured }
        coEvery { outbox.replayNow(venueId, any()) } coAnswers {
            val onAck = secondArg<suspend (SyncIntentEntity, SyncIntentAck) -> Unit>()
            onAck(
                SyncIntentEntity(id = idSlot.captured, venueId = venueId, seq = 1, type = SyncIntentTypes.PAY_CASH, payloadJson = "{}"),
                SyncIntentAck(id = idSlot.captured, status = "RETRY", errorCode = "VERSION_CONFLICT"),
            )
        }

        val result = repo.payCash(venueId, orderId, isProvisional = false, amount = BigDecimal("150.00"))

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().queued).isTrue()
    }

    // endregion

    // region — REJECTED: rechazo de negocio, se propaga tal cual

    @Test
    fun un_ack_REJECTED_propaga_PayCashRejectedException_con_el_mensaje_del_server() = runTest {
        val idSlot = slot<String>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.PAY_CASH, any(), capture(idSlot)) } answers { idSlot.captured }
        coEvery { outbox.replayNow(venueId, any()) } coAnswers {
            val onAck = secondArg<suspend (SyncIntentEntity, SyncIntentAck) -> Unit>()
            onAck(
                SyncIntentEntity(id = idSlot.captured, venueId = venueId, seq = 1, type = SyncIntentTypes.PAY_CASH, payloadJson = "{}"),
                SyncIntentAck(id = idSlot.captured, status = "REJECTED", errorCode = "INVALID_PAYLOAD", message = "PAY_CASH requiere amountCents > 0"),
            )
        }

        val result = repo.payCash(venueId, orderId, isProvisional = false, amount = BigDecimal("150.00"))

        assertThat(result.isFailure).isTrue()
        val error = result.exceptionOrNull()
        assertThat(error).isInstanceOf(TablesRepository.PayCashRejectedException::class.java)
        assertThat(error?.message).contains("amountCents")
    }

    // endregion

    // region — dinero: BigDecimal exacto → centavos enteros, y localOrderId vs orderId

    @Test
    fun amount_y_tip_se_mandan_como_centavos_enteros_EXACTOS() = runTest {
        // 150.55 pesos + 10.05 de propina — un boundary Double (150.55 * 100)
        // puede aterrizar en 15054.999999999998 y truncar a 15054. BigDecimal
        // debe dar EXACTO 15055/1005.
        val payloadSlot = slot<JsonObject>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.PAY_CASH, capture(payloadSlot), any()) } returns "intent-1"
        coEvery { outbox.replayNow(venueId, any()) } returns Unit

        repo.payCash(venueId, orderId, isProvisional = false, amount = BigDecimal("150.55"), tip = BigDecimal("10.05"))

        assertThat(payloadSlot.captured.get("amountCents").asInt).isEqualTo(15055)
        assertThat(payloadSlot.captured.get("tipCents").asInt).isEqualTo(1005)
        assertThat(payloadSlot.captured.get("method").asString).isEqualTo("CASH")
    }

    @Test
    fun sesion_provisional_manda_localOrderId_en_vez_de_orderId() = runTest {
        val payloadSlot = slot<JsonObject>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.PAY_CASH, capture(payloadSlot), any()) } returns "intent-1"
        coEvery { outbox.replayNow(venueId, any()) } returns Unit

        repo.payCash(venueId, "local-uuid-123", isProvisional = true, amount = BigDecimal("50.00"))

        assertThat(payloadSlot.captured.has("localOrderId")).isTrue()
        assertThat(payloadSlot.captured.get("localOrderId").asString).isEqualTo("local-uuid-123")
        assertThat(payloadSlot.captured.has("orderId")).isFalse()
    }

    // endregion

    // region — 🧾 digitalReceipt: applyPayCash (server) YA lo regresaba en el ack;
    // antes de este cambio TablesRepository lo descartaba por completo (solo leía
    // paymentId/orderNumber) — cierra el hueco "EFECTIVO nunca produce recibo".

    @Test
    fun un_ack_ACKED_con_digitalReceipt_puebla_receiptUrl_y_autofacturaAvailable() = runTest {
        val idSlot = slot<String>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.PAY_CASH, any(), capture(idSlot)) } answers { idSlot.captured }
        coEvery { outbox.replayNow(venueId, any()) } coAnswers {
            val onAck = secondArg<suspend (SyncIntentEntity, SyncIntentAck) -> Unit>()
            val result = JsonParser.parseString(
                """{"orderId":"$orderId","paymentId":"pay-1","orderNumber":"A-100",
                    |"digitalReceipt":{"accessKey":"key-1","receiptUrl":"https://avoqado.io/r/key-1","autofacturaAvailable":true}}
                """.trimMargin(),
            ).asJsonObject
            onAck(
                SyncIntentEntity(id = idSlot.captured, venueId = venueId, seq = 1, type = SyncIntentTypes.PAY_CASH, payloadJson = "{}"),
                SyncIntentAck(id = idSlot.captured, status = "ACKED", result = result),
            )
        }

        val result = repo.payCash(venueId, orderId, isProvisional = false, amount = BigDecimal("150.00"))

        val outcome = result.getOrThrow()
        assertThat(outcome.receiptUrl).isEqualTo("https://avoqado.io/r/key-1")
        assertThat(outcome.autofacturaAvailable).isTrue()
    }

    @Test
    fun un_ack_ACKED_sin_digitalReceipt_deja_receiptUrl_null_y_autofactura_en_false_sin_crashear() = runTest {
        val idSlot = slot<String>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.PAY_CASH, any(), capture(idSlot)) } answers { idSlot.captured }
        coEvery { outbox.replayNow(venueId, any()) } coAnswers {
            val onAck = secondArg<suspend (SyncIntentEntity, SyncIntentAck) -> Unit>()
            onAck(
                SyncIntentEntity(id = idSlot.captured, venueId = venueId, seq = 1, type = SyncIntentTypes.PAY_CASH, payloadJson = "{}"),
                SyncIntentAck(id = idSlot.captured, status = "ACKED", result = jsonOf("paymentId" to "pay-1", "orderNumber" to "A-100")),
            )
        }

        val result = repo.payCash(venueId, orderId, isProvisional = false, amount = BigDecimal("150.00"))

        val outcome = result.getOrThrow()
        assertThat(outcome.receiptUrl).isNull()
        assertThat(outcome.autofacturaAvailable).isFalse()
    }

    @Test
    fun un_cobro_encolado_sin_ack_no_tiene_receiptUrl_todavia() = runTest {
        // queued=true (sin ack) — el server ni siquiera vio el intent, así que
        // NUNCA puede haber generado un digitalReceipt. receiptUrl=null aquí
        // NO es un bug: la UI lo distingue con `queued` ("se sincronizará
        // solo"), nunca lo confunde con "el pago no generó recibo".
        coEvery { outbox.enqueue(any(), any(), any(), any()) } returns "intent-1"
        coEvery { outbox.replayNow(venueId, any()) } returns Unit

        val result = repo.payCash(venueId, orderId, isProvisional = false, amount = BigDecimal("150.00"))

        val outcome = result.getOrThrow()
        assertThat(outcome.queued).isTrue()
        assertThat(outcome.receiptUrl).isNull()
    }

    // endregion

    // region — clearTable: mismo patrón idempotente-online-u-offline que openTable

    @Test
    fun clearTable_online_exitoso_no_encola_nada() = runTest {
        coEvery { api.clearTable(venueId, "mesa-1") } returns Response.success(SimpleSuccessResponse(success = true))

        val result = repo.clearTable(venueId, "mesa-1")

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any()) }
    }

    @Test
    fun clearTable_sin_red_encola_CLEAR_TABLE_y_regresa_exito() = runTest {
        coEvery { api.clearTable(venueId, "mesa-1") } throws IOException("sin red")
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.CLEAR_TABLE, any(), any()) } returns "intent-2"

        val result = repo.clearTable(venueId, "mesa-1")

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 1) { outbox.enqueue(venueId, SyncIntentTypes.CLEAR_TABLE, any(), any()) }
    }

    @Test
    fun clearTable_rechazo_de_negocio_NUNCA_se_encola_se_propaga() = runTest {
        // "Cuenta sin pagar" — el server rechaza liberar la mesa (400/409), no
        // es infraestructura transitoria. Disfrazarlo de éxito encolado
        // liberaría una mesa que en realidad todavía debe.
        coEvery { api.clearTable(venueId, "mesa-1") } returns Response.error(
            400,
            "{\"error\":\"La mesa tiene una cuenta sin pagar\"}".toResponseBody("application/json".toMediaTypeOrNull()),
        )

        val result = repo.clearTable(venueId, "mesa-1")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(BackendHttpException::class.java)
        coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any()) }
    }

    // endregion

    private fun jsonOf(vararg pairs: Pair<String, Any>): JsonObject =
        JsonParser.parseString("{${pairs.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" }}}").asJsonObject
}

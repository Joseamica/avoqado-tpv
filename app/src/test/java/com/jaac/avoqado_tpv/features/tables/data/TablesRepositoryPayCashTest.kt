package com.jaac.avoqado_tpv.features.tables.data

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import com.jaac.avoqado_tpv.features.tables.data.api.TablesApiService
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SimpleSuccessResponse
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
    fun el_intent_PAY_CASH_se_escribe_write_ahead_ANTES_de_decidir_su_desenlace() = runTest {
        val idSlot = slot<String>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.PAY_CASH, any(), capture(idSlot)) } answers { idSlot.captured }
        coEvery { outbox.reproducirYResolver(venueId, any()) } returns SyncOutbox.DesenlaceDelIntent.EnEspera

        repo.payCash(venueId, orderId, isProvisional = false, amount = BigDecimal("150.00"))

        coVerifyOrder {
            outbox.enqueue(venueId, SyncIntentTypes.PAY_CASH, any(), any())
            outbox.reproducirYResolver(venueId, idSlot.captured)
        }
    }

    @Test
    fun el_MISMO_id_viaja_como_idempotencyKey_y_es_el_que_se_resuelve() = runTest {
        // El id que enqueue() recibe (write-ahead) es el MISMO que applyPayCash del server usa como idempotencyKey, y el MISMO
        // cuyo desenlace se pide: TablesRepository nunca genera un segundo id.
        val idSlot = slot<String>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.PAY_CASH, any(), capture(idSlot)) } answers { idSlot.captured }
        coEvery { outbox.reproducirYResolver(venueId, any()) } answers {
            if (secondArg<String>() == idSlot.captured) {
                SyncOutbox.DesenlaceDelIntent.Aceptado(jsonOf("paymentId" to "pay-1", "orderNumber" to "A-100"))
            } else {
                SyncOutbox.DesenlaceDelIntent.NoSeSabe("otro id")
            }
        }

        val result = repo.payCash(venueId, orderId, isProvisional = false, amount = BigDecimal("150.00"))

        val outcome = result.getOrThrow()
        assertThat(outcome.queued).isFalse()
        assertThat(outcome.paymentId).isEqualTo("pay-1")
        assertThat(outcome.orderNumber).isEqualTo("A-100")
    }

    // endregion

    // region — en espera (sin red, RETRY): éxito encolado, NUNCA error

    @Test
    fun en_espera_el_cobro_se_ve_como_exito_encolado() = runTest {
        coEvery { outbox.enqueue(any(), any(), any(), any()) } returns "intent-1"
        coEvery { outbox.reproducirYResolver(venueId, any()) } returns SyncOutbox.DesenlaceDelIntent.EnEspera

        val result = repo.payCash(venueId, orderId, isProvisional = false, amount = BigDecimal("150.00"))

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().queued).isTrue()
    }

    // endregion

    // region — D19: lo que contestó el SERVIDOR, aunque lo haya mandado otro reproductor

    @Test
    fun P1_rechazado_propaga_PayCashRejectedException_con_el_mensaje_del_server_nunca_guardado() = runTest {
        coEvery { outbox.enqueue(any(), any(), any(), any()) } returns "intent-1"
        coEvery { outbox.reproducirYResolver(venueId, any()) } returns
            SyncOutbox.DesenlaceDelIntent.Rechazado("BUSINESS_RULE", "Order not found")

        val result = repo.payCash(venueId, orderId, isProvisional = false, amount = BigDecimal("203.00"))

        val error = result.exceptionOrNull()
        assertThat(error).isInstanceOf(TablesRepository.PayCashRejectedException::class.java)
        assertThat(error?.message).isEqualTo("Order not found")
        assertThat((error as TablesRepository.PayCashRejectedException).errorCode).isEqualTo("BUSINESS_RULE")
    }

    @Test
    fun un_rechazo_sin_texto_del_servidor_se_explica_con_su_codigo() = runTest {
        // El acuse repetido del SERVIDOR (`ackFromExisting`) trae sólo el código.
        coEvery { outbox.enqueue(any(), any(), any(), any()) } returns "intent-1"
        coEvery { outbox.reproducirYResolver(venueId, any()) } returns SyncOutbox.DesenlaceDelIntent.Rechazado("PERMISSION_DENIED", null)

        val error = repo.payCash(venueId, orderId, isProvisional = false, amount = BigDecimal("50.00")).exceptionOrNull()

        assertThat(error).isInstanceOf(TablesRepository.PayCashRejectedException::class.java)
        assertThat(error?.message).contains("PERMISSION_DENIED")
        assertThat(error?.message).contains("Acciones rechazadas")
    }

    @Test
    fun P1_sin_saber_que_paso_es_una_duda_nunca_guardado() = runTest {
        coEvery { outbox.enqueue(any(), any(), any(), any()) } returns "intent-1"
        coEvery { outbox.reproducirYResolver(venueId, any()) } returns SyncOutbox.DesenlaceDelIntent.NoSeSabe("base")

        val error = repo.payCash(venueId, orderId, isProvisional = false, amount = BigDecimal("50.00")).exceptionOrNull()

        assertThat(error).isInstanceOf(TablesRepository.PayCashSinConfirmarException::class.java)
        assertThat(error?.message).contains("NO lo vuelvas a cobrar")
    }

    @Test
    fun si_el_cobro_no_se_pudo_escribir_dice_por_que_y_no_sale_una_excepcion() = runTest {
        // `guardarNuevaObligacionDeCaja` lanza a propósito con la caja cerrada: antes la excepción salía y cerraba la pantalla.
        coEvery { outbox.enqueue(any(), any(), any(), any()) } throws IllegalStateException("Abre la caja antes de iniciar otra venta.")

        val result = repo.payCash(venueId, orderId, isProvisional = false, amount = BigDecimal("50.00"))

        val error = result.exceptionOrNull()
        assertThat(error).isInstanceOf(TablesRepository.PayCashNoGuardadoException::class.java)
        assertThat(error?.message).isEqualTo("Abre la caja antes de iniciar otra venta.")
        coVerify(exactly = 0) { outbox.reproducirYResolver(any(), any()) }
    }

    @Test(expected = kotlinx.coroutines.CancellationException::class)
    fun una_cancelacion_al_escribir_sigue_saliendo() = runTest {
        coEvery { outbox.enqueue(any(), any(), any(), any()) } throws kotlinx.coroutines.CancellationException("pantalla cerrada")

        repo.payCash(venueId, orderId, isProvisional = false, amount = BigDecimal("50.00"))
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
        coEvery { outbox.reproducirYResolver(venueId, any()) } returns SyncOutbox.DesenlaceDelIntent.EnEspera

        repo.payCash(venueId, orderId, isProvisional = false, amount = BigDecimal("150.55"), tip = BigDecimal("10.05"))

        assertThat(payloadSlot.captured.get("amountCents").asInt).isEqualTo(15055)
        assertThat(payloadSlot.captured.get("tipCents").asInt).isEqualTo(1005)
        assertThat(payloadSlot.captured.get("method").asString).isEqualTo("CASH")
    }

    @Test
    fun sesion_provisional_manda_localOrderId_en_vez_de_orderId() = runTest {
        val payloadSlot = slot<JsonObject>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.PAY_CASH, capture(payloadSlot), any()) } returns "intent-1"
        coEvery { outbox.reproducirYResolver(venueId, any()) } returns SyncOutbox.DesenlaceDelIntent.EnEspera

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
    fun aceptado_con_digitalReceipt_puebla_receiptUrl_y_autofacturaAvailable() = runTest {
        coEvery { outbox.enqueue(any(), any(), any(), any()) } returns "intent-1"
        val result = JsonParser.parseString(
            """{"orderId":"$orderId","paymentId":"pay-1","orderNumber":"A-100",
                |"digitalReceipt":{"accessKey":"key-1","receiptUrl":"https://avoqado.io/r/key-1","autofacturaAvailable":true}}
            """.trimMargin(),
        ).asJsonObject
        coEvery { outbox.reproducirYResolver(venueId, any()) } returns SyncOutbox.DesenlaceDelIntent.Aceptado(result)

        val outcome = repo.payCash(venueId, orderId, isProvisional = false, amount = BigDecimal("150.00")).getOrThrow()

        assertThat(outcome.receiptUrl).isEqualTo("https://avoqado.io/r/key-1")
        assertThat(outcome.autofacturaAvailable).isTrue()
    }

    @Test
    fun aceptado_sin_digitalReceipt_deja_receiptUrl_null_y_autofactura_en_false_sin_crashear() = runTest {
        coEvery { outbox.enqueue(any(), any(), any(), any()) } returns "intent-1"
        coEvery { outbox.reproducirYResolver(venueId, any()) } returns
            SyncOutbox.DesenlaceDelIntent.Aceptado(jsonOf("paymentId" to "pay-1", "orderNumber" to "A-100"))

        val outcome = repo.payCash(venueId, orderId, isProvisional = false, amount = BigDecimal("150.00")).getOrThrow()

        assertThat(outcome.receiptUrl).isNull()
        assertThat(outcome.autofacturaAvailable).isFalse()
    }

    @Test
    fun aceptado_sin_result_no_crashea() = runTest {
        // Aceptado leído de una fila cuyo `resultJson` no se pudo leer.
        coEvery { outbox.enqueue(any(), any(), any(), any()) } returns "intent-1"
        coEvery { outbox.reproducirYResolver(venueId, any()) } returns SyncOutbox.DesenlaceDelIntent.Aceptado(null)

        val outcome = repo.payCash(venueId, orderId, isProvisional = false, amount = BigDecimal("150.00")).getOrThrow()

        assertThat(outcome.queued).isFalse()
        assertThat(outcome.paymentId).isNull()
    }

    @Test
    fun un_cobro_en_espera_no_tiene_receiptUrl_todavia() = runTest {
        // queued=true — el server ni siquiera contestó todavía, así que NUNCA puede haber generado un digitalReceipt.
        coEvery { outbox.enqueue(any(), any(), any(), any()) } returns "intent-1"
        coEvery { outbox.reproducirYResolver(venueId, any()) } returns SyncOutbox.DesenlaceDelIntent.EnEspera

        val outcome = repo.payCash(venueId, orderId, isProvisional = false, amount = BigDecimal("150.00")).getOrThrow()

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
    fun D79_clearTable_sin_red_nombra_la_cuenta_real_que_solto() = runTest {
        coEvery { api.clearTable(venueId, "mesa-1") } throws IOException("sin red")
        val payload = slot<JsonObject>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.CLEAR_TABLE, capture(payload), any()) } returns "intent-3"

        repo.clearTable(venueId, "mesa-1", orderId = "orden-real-1", isProvisional = false)

        assertThat(payload.captured.get("tableId").asString).isEqualTo("mesa-1")
        assertThat(payload.captured.get("orderId").asString).isEqualTo("orden-real-1")
        assertThat(payload.captured.has("localOrderId")).isFalse()
    }

    @Test
    fun D79_clearTable_sin_red_con_cuenta_provisional_la_nombra_como_localOrderId() = runTest {
        coEvery { api.clearTable(venueId, "mesa-1") } throws IOException("sin red")
        val payload = slot<JsonObject>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.CLEAR_TABLE, capture(payload), any()) } returns "intent-4"

        repo.clearTable(venueId, "mesa-1", orderId = "local-uuid-9", isProvisional = true)

        assertThat(payload.captured.get("localOrderId").asString).isEqualTo("local-uuid-9")
        assertThat(payload.captured.has("orderId")).isFalse()
    }

    @Test
    fun D79_clearTable_sin_cuenta_manda_solo_la_mesa_como_antes() = runTest {
        coEvery { api.clearTable(venueId, "mesa-1") } throws IOException("sin red")
        val payload = slot<JsonObject>()
        coEvery { outbox.enqueue(venueId, SyncIntentTypes.CLEAR_TABLE, capture(payload), any()) } returns "intent-5"

        repo.clearTable(venueId, "mesa-1")

        assertThat(payload.captured.keySet()).containsExactly("tableId")
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

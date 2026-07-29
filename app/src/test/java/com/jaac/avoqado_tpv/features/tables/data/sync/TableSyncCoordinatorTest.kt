package com.jaac.avoqado_tpv.features.tables.data.sync

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.jaac.avoqado_tpv.features.tables.data.TableSession
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentAck
import com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentEntity
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/**
 * `TableSyncCoordinator` (Plan C, Task 5) — reconcilia `TableSession` con los
 * acks que `SyncOutbox.replayNow` aplica. `SyncOutbox` es un mock ESTRICTO
 * (la vacuous-test trap de este repo: un mock relajado satisfecho por una
 * llamada de construcción, verde durante meses sin proteger nada — ver
 * `TablesRepositoryOfflineTest.kt`), `TableSession` es la clase REAL (sin
 * dependencias, no necesita mock).
 *
 * Dos grupos de tests:
 * 1. `onAck` — la reconciliación pública, literal del plan (Task 5, Step 1),
 *    sin levantar `replayNow`/Room/Retrofit.
 * 2. `replay` — que el wiring hacia `SyncOutbox.replayNow` es correcto y que
 *    el coordinador NUNCA rompe las garantías que `SyncOutbox` (Task 3,
 *    `SyncOutboxReplayTest`, 6/6 verificado en hardware) ya prueba: FIFO,
 *    `RETRY` corta el batch y no muta la sesión, `REJECTED` no muta la
 *    sesión ni descarta la cuarentena.
 */
class TableSyncCoordinatorTest {

    private val outbox = mockk<SyncOutbox>()
    private lateinit var session: TableSession
    private lateinit var coordinator: TableSyncCoordinator

    @Before
    fun setUp() {
        clearMocks(outbox)
        session = TableSession()
        coordinator = TableSyncCoordinator(outbox, session)
    }

    // region — onAck(): literal del plan (Task 5, Step 1)

    @Test
    fun el_ack_de_OPEN_TABLE_promueve_el_uuid_local_al_orderId_real() {
        val localId = "local-uuid-123"
        session.open(tableId = "mesa-1", localOrderId = localId)

        coordinator.onAck(
            intentType = SyncIntentTypes.OPEN_TABLE,
            localOrderId = localId,
            serverOrderId = "orden-real-456",
            orderNumber = "A-100",
            version = 3,
        )

        val active = session.current()!!
        assertThat(active.orderId).isEqualTo("orden-real-456")
        assertThat(active.isProvisional).isFalse()
        assertThat(active.version).isEqualTo(3)
    }

    @Test
    fun los_intents_posteriores_usan_el_orderId_ya_promovido() {
        val localId = "local-uuid-123"
        session.open(tableId = "mesa-1", localOrderId = localId)
        coordinator.onAck(SyncIntentTypes.OPEN_TABLE, localId, "orden-real-456", version = 1)

        val payload = session.buildAddItemsPayload(emptyList())

        assertThat(payload["orderId"].asString).isEqualTo("orden-real-456")
    }

    @Test
    fun un_ack_OPEN_TABLE_cuyo_localOrderId_no_coincide_con_la_sesion_activa_es_no_op() {
        session.open(tableId = "mesa-1", localOrderId = "local-A")

        coordinator.onAck(SyncIntentTypes.OPEN_TABLE, localOrderId = "local-B", serverOrderId = "orden-otra", version = 1)

        val active = session.current()!!
        assertThat(active.orderId).isEqualTo("local-A")
        assertThat(active.isProvisional).isTrue()
    }

    @Test
    fun un_ack_de_ADD_ITEMS_de_la_sesion_activa_refresca_solo_la_version() {
        session.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-real-456", version = 1, total = BigDecimal("300.0")))

        coordinator.onAck(SyncIntentTypes.ADD_ITEMS, localOrderId = null, serverOrderId = "orden-real-456", version = 5)

        val active = session.current()!!
        assertThat(active.version).isEqualTo(5)
        assertThat(active.total).isEqualTo(BigDecimal("300.0")) // ADD_ITEMS ack no trae total — no debe tocarlo
    }

    @Test
    fun un_ack_de_una_orden_distinta_no_toca_la_sesion_activa() {
        // Otra mesa, u otro dispositivo del mismo venue — nunca debe pisar la sesión local.
        session.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-real-456", version = 1))

        coordinator.onAck(SyncIntentTypes.ADD_ITEMS, localOrderId = null, serverOrderId = "orden-DE-OTRA-MESA", version = 99)

        assertThat(session.current()!!.version).isEqualTo(1)
    }

    @Test
    fun un_ack_con_ambos_campos_pero_intentType_distinto_de_OPEN_TABLE_NO_promueve() {
        // Defensa: solo OPEN_TABLE promueve, aunque por accidente algún otro
        // tipo trajera localOrderId+orderId en su result.
        session.open(tableId = "mesa-1", localOrderId = "local-A")

        coordinator.onAck(SyncIntentTypes.ADD_ITEMS, localOrderId = "local-A", serverOrderId = "orden-real", version = 1)

        assertThat(session.current()!!.orderId).isEqualTo("local-A")
        assertThat(session.current()!!.isProvisional).isTrue()
    }

    // endregion

    // region — replay(): wiring + garantías heredadas de SyncOutbox (FIFO, RETRY, REJECTED)

    @Test
    fun replay_delega_en_SyncOutbox_replayNow_para_el_venue_correcto() = runTest {
        coEvery { outbox.replayNow(any(), any()) } returns Unit

        coordinator.replay("venue-1")

        coVerify(exactly = 1) { outbox.replayNow("venue-1", any()) }
    }

    @Test
    fun los_acks_de_un_batch_se_reconcilian_en_el_MISMO_orden_FIFO_que_entrega_replayNow() = runTest {
        // FIFO: el OPEN_TABLE se procesa ANTES que el ADD_ITEMS que depende de
        // su orderId promovido — si se procesaran fuera de orden, la `version`
        // del ADD_ITEMS (2) nunca aplicaría sobre la orden ya promovida.
        session.open(tableId = "mesa-1", localOrderId = "local-A")
        val openIntent = intentEntity(id = "id-1", type = SyncIntentTypes.OPEN_TABLE)
        val addIntent = intentEntity(id = "id-2", type = SyncIntentTypes.ADD_ITEMS)

        coEvery { outbox.replayNow("venue-1", any()) } coAnswers {
            val onAck = secondArg<suspend (SyncIntentEntity, SyncIntentAck) -> Unit>()
            onAck(openIntent, ackAcked(id = "id-1", result = jsonOf("localOrderId" to "local-A", "orderId" to "real-1", "version" to 1)))
            onAck(addIntent, ackAcked(id = "id-2", result = jsonOf("orderId" to "real-1", "version" to 2)))
        }

        coordinator.replay("venue-1")

        val active = session.current()!!
        assertThat(active.orderId).isEqualTo("real-1")
        assertThat(active.isProvisional).isFalse()
        assertThat(active.version).isEqualTo(2)
    }

    @Test
    fun localOrderId_resuelve_para_el_ADD_ITEMS_que_sigue_a_OPEN_TABLE_en_el_mismo_batch() = runTest {
        // Mismo escenario que arriba, visto desde el payload que Task 7
        // construiría para la SIGUIENTE ronda tras este replay: debe usar el
        // orderId real, no el uuid local — prueba end-to-end de la promoción
        // dentro de un solo batch.
        session.open(tableId = "mesa-1", localOrderId = "local-A")
        val openIntent = intentEntity(id = "id-1", type = SyncIntentTypes.OPEN_TABLE)
        val addIntent = intentEntity(id = "id-2", type = SyncIntentTypes.ADD_ITEMS)

        coEvery { outbox.replayNow("venue-1", any()) } coAnswers {
            val onAck = secondArg<suspend (SyncIntentEntity, SyncIntentAck) -> Unit>()
            onAck(openIntent, ackAcked(id = "id-1", result = jsonOf("localOrderId" to "local-A", "orderId" to "real-1", "version" to 1)))
            onAck(addIntent, ackAcked(id = "id-2", result = jsonOf("orderId" to "real-1", "version" to 2)))
        }

        coordinator.replay("venue-1")

        val payload = session.buildAddItemsPayload(emptyList())
        assertThat(payload["orderId"].asString).isEqualTo("real-1")
        assertThat(payload.has("localOrderId")).isFalse()
    }

    @Test
    fun un_RETRY_no_muta_la_sesion() = runTest {
        // Refleja lo que SyncOutbox.replayNow YA garantiza (Task 3): el server
        // nunca manda acks después de un RETRY en la misma respuesta — el fake
        // de abajo solo entrega ESE ack, igual que el server real cortaría ahí.
        session.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-real-456", version = 1))
        val addIntent = intentEntity(id = "id-1", type = SyncIntentTypes.ADD_ITEMS)

        coEvery { outbox.replayNow("venue-1", any()) } coAnswers {
            val onAck = secondArg<suspend (SyncIntentEntity, SyncIntentAck) -> Unit>()
            onAck(addIntent, SyncIntentAck(id = "id-1", status = "RETRY", errorCode = "VERSION_CONFLICT"))
        }

        coordinator.replay("venue-1")

        assertThat(session.current()!!.version).isEqualTo(1)
    }

    @Test
    fun un_REJECTED_no_muta_la_sesion_activa_ni_descarta_la_cuarentena() = runTest {
        session.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-real-456", version = 1))
        val payIntent = intentEntity(id = "id-1", type = SyncIntentTypes.PAY_CASH)

        coEvery { outbox.replayNow("venue-1", any()) } coAnswers {
            val onAck = secondArg<suspend (SyncIntentEntity, SyncIntentAck) -> Unit>()
            onAck(payIntent, SyncIntentAck(id = "id-1", status = "REJECTED", errorCode = "TABLE_OWNED_BY_OTHER", message = "otro mesero"))
        }

        coordinator.replay("venue-1")

        val active = session.current()!!
        assertThat(active.orderId).isEqualTo("orden-real-456")
        assertThat(active.version).isEqualTo(1)
        // El coordinador jamás debe descartar una cuarentena por su cuenta — eso es del mesero/gerente (Task 9).
        coVerify(exactly = 0) { outbox.dismissRejected(any(), any()) }
    }

    // endregion

    private fun intentEntity(id: String, type: String) =
        SyncIntentEntity(id = id, venueId = "venue-1", seq = 1, type = type, payloadJson = "{}")

    private fun ackAcked(id: String, result: JsonObject) = SyncIntentAck(id = id, status = "ACKED", result = result)

    private fun jsonOf(vararg pairs: Pair<String, Any>): JsonObject = JsonObject().apply {
        pairs.forEach { (key, value) ->
            when (value) {
                is Int -> addProperty(key, value)
                is String -> addProperty(key, value)
                else -> error("tipo no soportado en jsonOf: $value")
            }
        }
    }
}

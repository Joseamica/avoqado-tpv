package com.jaac.avoqado_tpv.features.tables.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentAck
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentsResponse
import com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentDao
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Tests de [SyncOutbox.replayNow] contra [RecordingTablesApiService] — la mitad del
 * contrato que `SyncOutboxTest.kt` NO cubre (ese archivo solo llama
 * `enqueue`/`applyAck` directo, nunca sube al wire). Estos 6 SÍ ejercen el camino
 * completo: outbox → `SyncIntentsRequest` real → ack → persistencia.
 *
 * Las dos garantías de dinero que este archivo existe para probar (pedidas
 * explícitamente para la Task 3, las dos son bugs reales de "P1"/pérdida de dinero
 * si se rompen):
 *
 * 1. **El id del intent SOBREVIVE un reintento** ([el_id_del_intent_ES_el_idempotencyKey_que_viaja_al_server],
 *    [un_reintento_tras_perder_la_respuesta_manda_EL_MISMO_id_no_uno_nuevo]) — el
 *    escenario que importa es el reproducido en hardware real en Plan A: el
 *    request LLEGA al server y el efecto SÍ ocurre, pero la respuesta se pierde
 *    ([RecordingTablesApiService.enqueueDroppedResponse]). El cliente reintenta;
 *    lo único que evita cobrar dos veces es que el id (=idempotencyKey) viaje
 *    IDÉNTICO la segunda vez.
 * 2. **RETRY nunca se confunde con REJECTED** ([RETRY_deja_el_intent_PENDING_y_corta_el_batch_FIFO],
 *    [un_REJECTED_no_corta_el_batch_a_diferencia_de_RETRY]) — un RETRY dejaría el
 *    intent PENDING y corta el batch (los posteriores dependen de él por FIFO); un
 *    REJECTED lo manda a cuarentena y el resto del batch SIGUE aplicándose. Tratar
 *    uno como el otro pierde el intent para siempre o rompe el orden FIFO.
 */
class SyncOutboxReplayTest {

    private lateinit var db: AvoqadoDatabase
    private lateinit var dao: SyncIntentDao
    private lateinit var api: RecordingTablesApiService
    private lateinit var outbox: SyncOutbox

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AvoqadoDatabase::class.java,
        ).build()
        dao = db.syncIntentDao()
        api = RecordingTablesApiService()
        outbox = SyncOutbox(
            dao = dao,
            api = api,
            secureStorage = SecureStorage(ApplicationProvider.getApplicationContext()),
        )
    }

    @After
    fun teardown() = db.close()

    @Test
    fun el_id_del_intent_ES_el_idempotencyKey_que_viaja_al_server() = runTest {
        val id = outbox.enqueue("venue-a", SyncIntentTypes.PAY_CASH, payload())
        api.enqueueReply(SyncIntentsResponse(data = listOf(SyncIntentAck(id = id, status = "ACKED"))))

        outbox.replayNow("venue-a")

        assertThat(api.capturedRequests).hasSize(1)
        assertThat(api.capturedRequests.single().intents.single().id).isEqualTo(id)
    }

    @Test
    fun un_reintento_tras_perder_la_respuesta_manda_EL_MISMO_id_no_uno_nuevo() = runTest {
        // El caso real de Plan A: el PAY_CASH SÍ pudo haber cobrado en el server,
        // pero la respuesta nunca llegó. El cliente reintenta — si el id cambiara
        // entre intentos, el server vería DOS PAY_CASH distintos y cobraría dos veces.
        val id = outbox.enqueue("venue-a", SyncIntentTypes.PAY_CASH, payload())

        api.enqueueDroppedResponse()
        outbox.replayNow("venue-a") // intento 1: la respuesta se pierde
        assertThat(dao.pendingForVenue("venue-a")).hasSize(1) // nunca se marcó, sigue PENDING

        api.enqueueReply(SyncIntentsResponse(data = listOf(SyncIntentAck(id = id, status = "ACKED"))))
        outbox.replayNow("venue-a") // intento 2: reintento tras la caída

        assertThat(api.capturedRequests).hasSize(2)
        val idIntento1 = api.capturedRequests[0].intents.single().id
        val idIntento2 = api.capturedRequests[1].intents.single().id
        assertThat(idIntento1).isEqualTo(id)
        assertThat(idIntento2).isEqualTo(id) // 🔴 EL MISMO id — la garantía de dinero
        assertThat(dao.pendingForVenue("venue-a")).isEmpty() // el intento 2 sí se aplicó
    }

    @Test
    fun fallo_de_red_deja_el_batch_intacto_sin_lanzar() = runTest {
        val id = outbox.enqueue("venue-a", SyncIntentTypes.ADD_ITEMS, payload())
        api.enqueueDroppedResponse()

        outbox.replayNow("venue-a") // no debe lanzar

        assertThat(dao.pendingForVenue("venue-a")).hasSize(1)
        assertThat(dao.pendingForVenue("venue-a").first().id).isEqualTo(id)
    }

    @Test
    fun RETRY_deja_el_intent_PENDING_y_corta_el_batch_FIFO() = runTest {
        // El server (processIntents) corta el batch en el primer RETRY — los
        // intents posteriores dependen de este por FIFO y NUNCA reciben ack en la
        // misma respuesta. id3 se queda PENDING sin que el server lo haya tocado.
        val id1 = outbox.enqueue("venue-a", SyncIntentTypes.OPEN_TABLE, payload())
        val id2 = outbox.enqueue("venue-a", SyncIntentTypes.ADD_ITEMS, payload())
        val id3 = outbox.enqueue("venue-a", SyncIntentTypes.PAY_CASH, payload())

        api.enqueueReply(
            SyncIntentsResponse(
                data = listOf(
                    SyncIntentAck(id = id1, status = "ACKED"),
                    SyncIntentAck(id = id2, status = "RETRY", errorCode = "VERSION_CONFLICT"),
                    // id3: el server ni lo intentó — no aparece en la respuesta.
                ),
            ),
        )

        outbox.replayNow("venue-a")

        assertThat(api.capturedRequests).hasSize(1) // sin hot-loop sobre el mismo PENDING
        val pending = dao.pendingForVenue("venue-a")
        assertThat(pending.map { it.id }).containsExactly(id2, id3).inOrder() // FIFO preservado
        assertThat(dao.rejectedForVenue("venue-a")).isEmpty() // RETRY no es rechazo
    }

    @Test
    fun un_REJECTED_no_corta_el_batch_a_diferencia_de_RETRY() = runTest {
        // Un rechazo de NEGOCIO (id1) es individual — a diferencia de RETRY, no
        // detiene el resto del batch: id2 se sigue aplicando en la MISMA respuesta.
        val id1 = outbox.enqueue("venue-a", SyncIntentTypes.ADD_ITEMS, payload())
        val id2 = outbox.enqueue("venue-a", SyncIntentTypes.PAY_CASH, payload())

        api.enqueueReply(
            SyncIntentsResponse(
                data = listOf(
                    SyncIntentAck(id = id1, status = "REJECTED", errorCode = "TABLE_OWNED_BY_OTHER"),
                    SyncIntentAck(id = id2, status = "ACKED"),
                ),
            ),
        )

        outbox.replayNow("venue-a")

        assertThat(dao.pendingForVenue("venue-a")).isEmpty() // ninguno se quedó PENDING
        assertThat(dao.rejectedForVenue("venue-a").map { it.id }).containsExactly(id1) // solo el rechazado
    }

    @Test
    fun split_order_rechazado_va_completo_a_cuarentena_nunca_parcial() = runTest {
        // "Todo-o-nada" del lado del server (una referencia que no resuelve rechaza
        // el intent COMPLETO) — este test prueba que el cliente respeta eso: un
        // SPLIT_ORDER rechazado es UNA fila, completa, en cuarentena — nunca un
        // estado intermedio o parcialmente aplicado en el outbox local.
        val id = outbox.enqueue("venue-a", SyncIntentTypes.SPLIT_ORDER, payload())
        api.enqueueReply(
            SyncIntentsResponse(
                data = listOf(
                    SyncIntentAck(
                        id = id,
                        status = "REJECTED",
                        errorCode = "INVALID_PAYLOAD",
                        message = "Una referencia del split no resolvió",
                    ),
                ),
            ),
        )

        outbox.replayNow("venue-a")

        assertThat(dao.allForVenue("venue-a")).hasSize(1) // ni fantasmas ni filas partidas
        val quarantined = dao.rejectedForVenue("venue-a").single()
        assertThat(quarantined.id).isEqualTo(id)
        assertThat(quarantined.type).isEqualTo(SyncIntentTypes.SPLIT_ORDER)
        assertThat(quarantined.errorCode).isEqualTo("INVALID_PAYLOAD")
    }

    private fun payload(): JsonObject = JsonObject().apply { addProperty("marker", 1) }
}

package com.jaac.avoqado_tpv.features.tables.data.sync

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.features.tables.data.api.TablesApiService
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentAck
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentsResponse
import com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentDao
import com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentEntity
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * `SyncOutbox.replayNow`'s `onAck` callback (Plan C, Task 5 addition to Task
 * 3's `SyncOutbox`) — SIN Room/hardware: `SyncIntentDao` es una interfaz
 * mockeable directo, así que esta clase se puede construir de verdad en un
 * test JVM (a diferencia de `SyncOutboxReplayTest.kt`, que necesita Room real
 * y por eso vive en `androidTest`).
 *
 * Prueba lo que `TableSyncCoordinatorTest` NO puede (ahí `SyncOutbox` está
 * mockeado ENTERO — el wiring real de [SyncOutbox.replayNow] nunca se
 * ejerce): que la implementación REAL invoca el callback en el MISMO orden
 * FIFO en que el server acka, con el [SyncIntentEntity] original correcto
 * (para su `type`), y NUNCA para un intent posterior a un `RETRY` en la
 * misma respuesta — ver `SyncOutboxReplayTest.RETRY_deja_el_intent_PENDING_y_corta_el_batch_FIFO`
 * (Task 3), cuya garantía este callback hereda sin reimplementarla.
 */
class SyncOutboxOnAckCallbackTest {

    private val dao = mockk<SyncIntentDao>()
    private val api = mockk<TablesApiService>()
    private val secureStorage = mockk<SecureStorage>()
    private lateinit var outbox: SyncOutbox

    @Before
    fun setUp() {
        clearMocks(dao, api, secureStorage)
        every { secureStorage.getSerialNumber() } returns "term-1"
        coEvery { dao.resolve(any(), any(), any(), any(), any()) } returns 1
        outbox = SyncOutbox(dao, api, secureStorage)
    }

    @Test
    fun el_callback_recibe_cada_ack_en_orden_FIFO_con_su_intent_original() = runTest {
        val openEntity = entity(id = "id-1", type = SyncIntentTypes.OPEN_TABLE, seq = 1)
        val addEntity = entity(id = "id-2", type = SyncIntentTypes.ADD_ITEMS, seq = 2)
        // Segunda vuelta del while(true) de replayNow: ya no queda nada PENDING.
        coEvery { dao.pendingForVenue("venue-1") } returnsMany listOf(listOf(openEntity, addEntity), emptyList())
        coEvery { api.syncIntents("venue-1", any()) } returns Response.success(
            SyncIntentsResponse(
                data = listOf(
                    SyncIntentAck(id = "id-1", status = "ACKED"),
                    SyncIntentAck(id = "id-2", status = "ACKED"),
                ),
            ),
        )

        val seen = mutableListOf<Pair<String, String>>()
        outbox.replayNow("venue-1") { intent, ack -> seen.add(intent.type to ack.id) }

        assertThat(seen).containsExactly(
            SyncIntentTypes.OPEN_TABLE to "id-1",
            SyncIntentTypes.ADD_ITEMS to "id-2",
        ).inOrder()
    }

    @Test
    fun el_callback_NUNCA_se_invoca_para_un_intent_posterior_a_un_RETRY() = runTest {
        val openEntity = entity(id = "id-1", type = SyncIntentTypes.OPEN_TABLE, seq = 1)
        val addEntity = entity(id = "id-2", type = SyncIntentTypes.ADD_ITEMS, seq = 2)
        val payEntity = entity(id = "id-3", type = SyncIntentTypes.PAY_CASH, seq = 3)
        coEvery { dao.pendingForVenue("venue-1") } returns listOf(openEntity, addEntity, payEntity)
        coEvery { api.syncIntents("venue-1", any()) } returns Response.success(
            SyncIntentsResponse(
                data = listOf(
                    SyncIntentAck(id = "id-1", status = "ACKED"),
                    SyncIntentAck(id = "id-2", status = "RETRY", errorCode = "VERSION_CONFLICT"),
                    // id-3: el server ni lo intentó — no aparece en la respuesta (igual que SyncOutboxReplayTest).
                ),
            ),
        )

        val seenIds = mutableListOf<String>()
        outbox.replayNow("venue-1") { _, ack -> seenIds.add(ack.id) }

        assertThat(seenIds).containsExactly("id-1", "id-2").inOrder() // NUNCA id-3
    }

    @Test
    fun sin_callback_explicito_replayNow_sigue_funcionando_igual_que_antes() = runTest {
        // El default `{ _, _ -> }` no debe cambiar el comportamiento para los
        // callers existentes (Task 3/4, `SyncOutboxReplayTest`): la persistencia
        // del ack sigue ocurriendo aunque nadie pida el callback.
        coEvery { dao.pendingForVenue("venue-1") } returnsMany listOf(listOf(entity("id-1", SyncIntentTypes.ADD_ITEMS, 1)), emptyList())
        coEvery { api.syncIntents("venue-1", any()) } returns Response.success(
            SyncIntentsResponse(data = listOf(SyncIntentAck(id = "id-1", status = "ACKED"))),
        )

        outbox.replayNow("venue-1") // sin trailing lambda

        coVerify(exactly = 1) { dao.resolve("id-1", "ACKED", null, null, null) }
    }

    private fun entity(id: String, type: String, seq: Long) =
        SyncIntentEntity(id = id, venueId = "venue-1", seq = seq, type = type, payloadJson = "{}")
}

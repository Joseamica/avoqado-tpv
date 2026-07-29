package com.jaac.avoqado_tpv.features.tables.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentDao
import com.jaac.avoqado_tpv.features.tables.data.local.TablesDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Instrumented tests del outbox de Mesas (Plan C Task 3) — literal de la Task 3 del
 * plan (`docs/superpowers/plans/2026-07-24-tpv-plan-c-modulo-mesas.md`) más
 * cobertura propia de cuarentena y aislamiento por venue.
 *
 * Solo ejercen [SyncOutbox.enqueue] / [SyncOutbox.applyAck] / [SyncOutbox.rejectedIntents]
 * / [SyncOutbox.dismissRejected] — nada de HTTP real, así que [UnimplementedTablesApiService]
 * revienta a propósito si algo llegara a llamar a la red sin querer. La cobertura de
 * [SyncOutbox.replayNow] (idempotencia contra retry, RETRY-vs-REJECTED end-to-end,
 * corte de batch FIFO, rechazo de SPLIT_ORDER todo-o-nada) vive en
 * `SyncOutboxReplayTest.kt`, con [RecordingTablesApiService].
 */
class SyncOutboxTest {

    private lateinit var db: TablesDatabase
    private lateinit var dao: SyncIntentDao
    private lateinit var outbox: SyncOutbox

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TablesDatabase::class.java,
        ).build()
        dao = db.syncIntentDao()
        outbox = SyncOutbox(
            dao = dao,
            api = UnimplementedTablesApiService,
            secureStorage = SecureStorage(ApplicationProvider.getApplicationContext()),
        )
    }

    @After
    fun teardown() = db.close()

    @Test
    fun el_seq_se_asigna_en_transaccion_y_respeta_el_orden() = runTest {
        // Sin @Transaction, dos enqueue concurrentes pueden tomar el mismo seq y el
        // replay se desordena: un ADD_ITEMS llegaria ANTES de su OPEN_TABLE.
        val ids = (1..50).map { i ->
            async { outbox.enqueue("venue-a", SyncIntentTypes.ADD_ITEMS, payload(i)) }
        }.awaitAll()

        val seqs = dao.allForVenue("venue-a").map { it.seq }
        assertThat(seqs).containsNoDuplicates()
        assertThat(seqs).isInStrictOrder()
        assertThat(ids.toSet()).hasSize(50) // cada intent con su propio UUID
    }

    @Test
    fun un_intent_ACKED_sale_de_la_cola() = runTest {
        val id = outbox.enqueue("venue-a", SyncIntentTypes.OPEN_TABLE, payload(1))
        outbox.applyAck(id, status = "ACKED", errorCode = null)

        assertThat(dao.pendingForVenue("venue-a")).isEmpty()
    }

    @Test
    fun un_intent_RETRY_se_queda_PENDING_y_no_se_pierde() = runTest {
        // VERSION_CONFLICT es transitorio: el server dice "reintenta", no "no".
        val id = outbox.enqueue("venue-a", SyncIntentTypes.ADD_ITEMS, payload(1))
        outbox.applyAck(id, status = "RETRY", errorCode = "VERSION_CONFLICT")

        assertThat(dao.pendingForVenue("venue-a")).hasSize(1)
        assertThat(dao.pendingForVenue("venue-a").first().id).isEqualTo(id)
    }

    @Test
    fun un_intent_REJECTED_va_a_cuarentena_visible() = runTest {
        // Rechazo de negocio permanente: NO se reintenta y NO desaparece — el mesero
        // tiene que poder verlo y decidir.
        val id = outbox.enqueue("venue-a", SyncIntentTypes.ADD_ITEMS, payload(1))
        outbox.applyAck(id, status = "REJECTED", errorCode = "TABLE_OWNED_BY_OTHER")

        assertThat(dao.pendingForVenue("venue-a")).isEmpty()
        assertThat(outbox.rejectedIntents("venue-a")).hasSize(1)
        assertThat(outbox.rejectedIntents("venue-a").first().errorCode).isEqualTo("TABLE_OWNED_BY_OTHER")
    }

    @Test
    fun reproducir_el_mismo_intent_dos_veces_tiene_un_solo_efecto() = runTest {
        val id = outbox.enqueue("venue-a", SyncIntentTypes.PAY_CASH, payload(1))
        outbox.applyAck(id, status = "ACKED", errorCode = null)
        outbox.applyAck(id, status = "ACKED", errorCode = null) // duplicado

        assertThat(dao.allForVenue("venue-a").count { it.id == id }).isEqualTo(1)
    }

    // --- Cobertura propia, más allá del literal del plan ---

    @Test
    fun un_rechazo_descartado_sale_de_la_cuarentena() = runTest {
        val id = outbox.enqueue("venue-a", SyncIntentTypes.MOVE_ORDER, payload(1))
        outbox.applyAck(id, status = "REJECTED", errorCode = "TABLE_OWNED_BY_OTHER")

        outbox.dismissRejected("venue-a", id)

        assertThat(outbox.rejectedIntents("venue-a")).isEmpty()
        assertThat(dao.allForVenue("venue-a")).isEmpty() // se borró, no quedó fantasma
    }

    @Test
    fun el_seq_es_independiente_por_venue() = runTest {
        // Dos venues nunca deben pisarse el contador de seq (multi-tenant real: un
        // POS puede tener sesiones de más de un venue a lo largo del día).
        outbox.enqueue("venue-a", SyncIntentTypes.OPEN_TABLE, payload(1))
        outbox.enqueue("venue-b", SyncIntentTypes.OPEN_TABLE, payload(1))
        outbox.enqueue("venue-a", SyncIntentTypes.ADD_ITEMS, payload(2))

        assertThat(dao.allForVenue("venue-a").map { it.seq }).isEqualTo(listOf(1L, 2L))
        assertThat(dao.allForVenue("venue-b").map { it.seq }).isEqualTo(listOf(1L))
    }

    @Test
    fun un_status_de_ack_desconocido_no_se_persiste_ni_se_pierde() = runTest {
        // Un server más nuevo que mande un status que este cliente no conoce jamas
        // debe interpretarse como éxito ni como rechazo permanente por default.
        val id = outbox.enqueue("venue-a", SyncIntentTypes.OPEN_TABLE, payload(1))
        outbox.applyAck(id, status = "ALGO_QUE_NO_EXISTE_TODAVIA", errorCode = null)

        assertThat(dao.pendingForVenue("venue-a")).hasSize(1)
        assertThat(outbox.rejectedIntents("venue-a")).isEmpty()
    }

    // --- write-ahead (P1 fix): enqueue() con id pre-generado + discardPending() ---

    @Test
    fun enqueue_acepta_un_id_pre_generado_para_el_patron_write_ahead() = runTest {
        // TablesRepository.addItems genera el idempotencyKey ANTES de escribir el
        // intent, para poder incrustar el MISMO externalId en el intento online —
        // enqueue() debe usar TAL CUAL el id que se le pasa, no generar uno propio.
        val preGeneratedId = "intent-fijo-123"

        val returnedId = outbox.enqueue("venue-a", SyncIntentTypes.ADD_ITEMS, payload(1), id = preGeneratedId)

        assertThat(returnedId).isEqualTo(preGeneratedId)
        assertThat(dao.pendingForVenue("venue-a").single().id).isEqualTo(preGeneratedId)
    }

    @Test
    fun discardPending_borra_un_intent_write_ahead_que_ya_no_hace_falta_reproducir() = runTest {
        // Camino feliz del fix write-ahead: el intento online SÍ tuvo éxito (o el
        // server lo rechazó de forma permanente) — el intent que se escribió por
        // adelantado nunca debe reproducirse.
        val id = outbox.enqueue("venue-a", SyncIntentTypes.ADD_ITEMS, payload(1))
        assertThat(dao.pendingForVenue("venue-a")).hasSize(1)

        outbox.discardPending(id)

        assertThat(dao.pendingForVenue("venue-a")).isEmpty()
        assertThat(dao.allForVenue("venue-a")).isEmpty() // se borró, no quedó fantasma
    }

    @Test
    fun discardPending_nunca_toca_un_intent_ya_terminal_ACKED() = runTest {
        // Acotado a PENDING a propósito, igual que dismiss() — nunca debe poder
        // borrar el registro de un intent ya aplicado por error de llamada.
        val id = outbox.enqueue("venue-a", SyncIntentTypes.ADD_ITEMS, payload(1))
        outbox.applyAck(id, status = "ACKED", errorCode = null)

        outbox.discardPending(id)

        assertThat(dao.allForVenue("venue-a").map { it.id }).containsExactly(id)
    }

    @Test
    fun discardPending_de_un_id_que_no_existe_es_no_op_seguro() = runTest {
        // Un replay concurrente pudo haber ganado de mano (ACKED/REJECTED ya
        // resuelto y limpiado) — descartar un id que ya no existe nunca debe tronar.
        outbox.discardPending("id-que-nunca-existio")

        assertThat(dao.allForVenue("venue-a")).isEmpty()
    }

    private fun payload(i: Int): JsonObject = JsonObject().apply { addProperty("seq_marker", i) }
}

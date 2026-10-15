package com.jaac.avoqado_tpv.features.tables.data.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentEntity
import com.jaac.avoqado_tpv.features.tables.data.local.TablesDatabase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D1-S (Mesas40, Codex r3 hallazgo 6): la secuencia del outbox NO se reutiliza.
 *
 * Antes, `dismiss` (cuarentena «Marcar resuelta») y `discardPending` (write-ahead) BORRABAN la fila y `insertWithSeq`
 * toma `MAX(seq)+1`: si la fila era la más alta, la siguiente venta reutilizaba su `seq` y el server
 * (`sync.mobile.service.ts`, `latestForDevice.seq >= intent.seq`) la rechazaba `STALE_DEVICE_SEQUENCE` — un cobro en
 * efectivo caía a cuarentena sin cobrarse. Ahora esas dos operaciones MARCAN la fila (`DISMISSED`/`DISCARDED`) y
 * `resolve` no la toca, así que un ack tardío tampoco la revive ni la manda a la cuarentena.
 *
 * Room real (archivo, para poder CERRAR y REABRIR la base como lo hace un reinicio) bajo Robolectric: es el único modo de
 * probar `MAX(seq)` contra SQLite de verdad sin un aparato.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class SyncOutboxSecuenciaNoSeReutilizaTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val secureStorage = mockk<SecureStorage>().also { every { it.getSerialNumber() } returns "term-1" }
    private val abiertas = mutableListOf<TablesDatabase>()

    @After
    fun limpiar() {
        abiertas.forEach { runCatching { it.close() } }
        context.deleteDatabase(DB_NAME)
    }

    private fun abrir(): TablesDatabase =
        Room.databaseBuilder(context, TablesDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
            .also { abiertas += it }

    private fun outboxDe(db: TablesDatabase) = SyncOutbox(db.syncIntentDao(), mockk(relaxed = true), secureStorage)

    private suspend fun SyncOutbox.encola(): String = enqueue(VENUE, SyncIntentTypes.PAY_CASH, JsonObject())

    private suspend fun TablesDatabase.fila(id: String): SyncIntentEntity =
        syncIntentDao().allForVenue(VENUE).first { it.id == id }

    private suspend fun TablesDatabase.maxSeq(): Long = syncIntentDao().maxSeq(VENUE) ?: 0L

    @Test
    fun `un rechazo con el seq mas alto, resuelto y con la base reabierta, no deja reutilizar su seq`() = runTest {
        var db = abrir()
        var outbox = outboxDe(db)
        val ids = (1..3).map { outbox.encola() }
        outbox.applyAck(ids[0], SyncIntentEntity.STATUS_ACKED, null)
        outbox.applyAck(ids[1], SyncIntentEntity.STATUS_ACKED, null)
        outbox.applyAck(ids[2], SyncIntentEntity.STATUS_REJECTED, "PERMISSION_DENIED", "sin permiso")
        outbox.dismissRejected(VENUE, ids[2])
        db.close()

        db = abrir()
        outbox = outboxDe(db)
        val nuevo = outbox.encola()

        assertThat(db.fila(nuevo).seq).isEqualTo(4L)
    }

    @Test
    fun `descartar el PENDING mas alto no deja reutilizar su seq`() = runTest {
        val db = abrir()
        val outbox = outboxDe(db)
        outbox.encola()
        val segundo = outbox.encola()
        outbox.discardPending(segundo)

        val nuevo = outbox.encola()

        assertThat(db.fila(nuevo).seq).isEqualTo(3L)
    }

    @Test
    fun `las filas marcadas no aparecen en pendientes ni en cuarentena, pero allForVenue las conserva`() = runTest {
        val db = abrir()
        val outbox = outboxDe(db)
        val rechazada = outbox.encola()
        val descartada = outbox.encola()
        val viva = outbox.encola()
        outbox.applyAck(rechazada, SyncIntentEntity.STATUS_REJECTED, "INVALID_PAYLOAD")
        outbox.dismissRejected(VENUE, rechazada)
        outbox.discardPending(descartada)

        assertThat(outbox.rejectedIntents(VENUE)).isEmpty()
        assertThat(db.syncIntentDao().pendingForVenue(VENUE).map { it.id }).containsExactly(viva)
        val todas = db.syncIntentDao().allForVenue(VENUE)
        assertThat(todas.map { it.id }).containsExactly(rechazada, descartada, viva).inOrder()
        assertThat(db.fila(rechazada).status).isEqualTo(SyncIntentEntity.STATUS_DISMISSED)
        assertThat(db.fila(descartada).status).isEqualTo(SyncIntentEntity.STATUS_DISCARDED)
    }

    @Test
    fun `dismiss no toca PENDING ni ACKED y discardPending no toca REJECTED ni ACKED`() = runTest {
        val db = abrir()
        val outbox = outboxDe(db)
        val pendiente = outbox.encola()
        val aceptada = outbox.encola()
        val rechazada = outbox.encola()
        outbox.applyAck(aceptada, SyncIntentEntity.STATUS_ACKED, null)
        outbox.applyAck(rechazada, SyncIntentEntity.STATUS_REJECTED, "INVALID_PAYLOAD")

        outbox.dismissRejected(VENUE, pendiente)
        outbox.dismissRejected(VENUE, aceptada)
        outbox.discardPending(aceptada)
        outbox.discardPending(rechazada)

        assertThat(db.fila(pendiente).status).isEqualTo(SyncIntentEntity.STATUS_PENDING)
        assertThat(db.fila(aceptada).status).isEqualTo(SyncIntentEntity.STATUS_ACKED)
        assertThat(db.fila(rechazada).status).isEqualTo(SyncIntentEntity.STATUS_REJECTED)
    }

    @Test
    fun `la secuencia asignada siempre es mayor que la de cualquier fila, marcada o no`() = runTest {
        val db = abrir()
        val outbox = outboxDe(db)
        var anterior = 0L
        repeat(6) { vuelta ->
            val id = outbox.encola()
            val seq = db.fila(id).seq
            assertThat(seq).isGreaterThan(anterior)
            assertThat(seq).isGreaterThan(db.maxSeqSin(id))
            anterior = seq
            when (vuelta % 3) {
                0 -> outbox.discardPending(id)
                1 -> {
                    outbox.applyAck(id, SyncIntentEntity.STATUS_REJECTED, "INVALID_PAYLOAD")
                    outbox.dismissRejected(VENUE, id)
                }
                else -> outbox.applyAck(id, SyncIntentEntity.STATUS_ACKED, null)
            }
        }
    }

    private suspend fun TablesDatabase.maxSeqSin(id: String): Long =
        syncIntentDao().allForVenue(VENUE).filter { it.id != id }.maxOfOrNull { it.seq } ?: 0L

    @Test
    fun `un ack tardio sobre una fila descartada o resuelta no la revive ni la manda a la cuarentena`() = runTest {
        val db = abrir()
        val outbox = outboxDe(db)
        val descartada = outbox.encola()
        outbox.discardPending(descartada)
        val resuelta = outbox.encola()
        outbox.applyAck(resuelta, SyncIntentEntity.STATUS_REJECTED, "INVALID_PAYLOAD")
        outbox.dismissRejected(VENUE, resuelta)

        listOf(descartada, resuelta).forEach { id ->
            outbox.applyAck(id, SyncIntentEntity.STATUS_ACKED, null, resultJson = "{}")
            outbox.applyAck(id, SyncIntentEntity.STATUS_REJECTED, "STALE_DEVICE_SEQUENCE", "tarde")
        }

        assertThat(db.fila(descartada).status).isEqualTo(SyncIntentEntity.STATUS_DISCARDED)
        assertThat(db.fila(resuelta).status).isEqualTo(SyncIntentEntity.STATUS_DISMISSED)
        assertThat(db.fila(resuelta).errorCode).isEqualTo("INVALID_PAYLOAD") // el ack tardío no sobrescribe nada
        assertThat(outbox.rejectedIntents(VENUE)).isEmpty()
    }

    @Test
    fun `el mismo ack dos veces es idempotente`() = runTest {
        val db = abrir()
        val outbox = outboxDe(db)
        val id = outbox.encola()

        repeat(2) { outbox.applyAck(id, SyncIntentEntity.STATUS_ACKED, null, resultJson = """{"orderId":"o1"}""") }

        val fila = db.fila(id)
        assertThat(fila.status).isEqualTo(SyncIntentEntity.STATUS_ACKED)
        assertThat(fila.resultJson).isEqualTo("""{"orderId":"o1"}""")
        assertThat(db.maxSeq()).isEqualTo(1L)
    }

    private companion object {
        const val VENUE = "venue-1"
        const val DB_NAME = "d1s-secuencia-test-db"
    }
}

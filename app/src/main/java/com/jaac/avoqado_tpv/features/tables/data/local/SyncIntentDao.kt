package com.jaac.avoqado_tpv.features.tables.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/**
 * DAO del outbox de Mesas. Contrato literal de la Task 3 del plan (`maxSeq`,
 * `insert`, `insertWithSeq`, `pendingForVenue`, `rejectedForVenue`, `allForVenue`)
 * más lo que [com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox] necesita
 * para aplicar acks y descartar cuarentena (`resolve`, `dismiss`).
 */
@Dao
interface SyncIntentDao {

    /**
     * Inserta el intent tomando el siguiente [SyncIntentEntity.seq] DENTRO de la
     * misma transacción.
     *
     * Si el seq se leyera fuera, dos enqueue() concurrentes tomarían el mismo
     * número y el replay se desordenaría: un ADD_ITEMS podría ordenarse antes de
     * su OPEN_TABLE y el reducer lo rechazaría. `@Transaction` serializa el par
     * lectura+escritura.
     *
     * @return el seq asignado.
     */
    @Transaction
    suspend fun insertWithSeq(intent: SyncIntentEntity): Long {
        val next = (maxSeq(intent.venueId) ?: 0L) + 1
        insert(intent.copy(seq = next))
        return next
    }

    @Query("SELECT MAX(seq) FROM sync_intents WHERE venue_id = :venueId")
    suspend fun maxSeq(venueId: String): Long?

    @Insert
    suspend fun insert(intent: SyncIntentEntity)

    @Query("SELECT * FROM sync_intents WHERE venue_id = :venueId AND status = 'PENDING' ORDER BY seq ASC")
    suspend fun pendingForVenue(venueId: String): List<SyncIntentEntity>

    /** Cuarentena: rechazos de negocio permanentes, visibles hasta que el mesero/gerente los descarte. */
    @Query("SELECT * FROM sync_intents WHERE venue_id = :venueId AND status = 'REJECTED' ORDER BY seq ASC")
    suspend fun rejectedForVenue(venueId: String): List<SyncIntentEntity>

    @Query("SELECT * FROM sync_intents WHERE venue_id = :venueId ORDER BY seq ASC")
    suspend fun allForVenue(venueId: String): List<SyncIntentEntity>

    /**
     * Resuelve un intent a un estado TERMINAL (`ACKED`/`REJECTED`). `RETRY` NUNCA
     * pasa por aquí — [com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox.applyAck]
     * lo intercepta antes y deja la fila intacta.
     *
     * Se puede llamar dos veces con el mismo ack sin efecto extra (UPDATE por
     * `id`, no INSERT) — reproducir el mismo ack no duplica ni corrompe la fila.
     *
     * @return filas afectadas (0 = el id no existe, p.ej. ya se descartó).
     */
    @Query(
        """
        UPDATE sync_intents
        SET status = :status, error_code = :errorCode, message = :message, result_json = :resultJson
        WHERE id = :id
        """,
    )
    suspend fun resolve(id: String, status: String, errorCode: String?, message: String?, resultJson: String?): Int

    /**
     * El mesero/gerente descartó un rechazo de la cuarentena tras resolverlo a
     * mano. Acotado a `status = 'REJECTED'` a propósito: [dismiss] nunca debe
     * poder borrar un intent PENDING o ACKED por error de UI.
     */
    @Query("DELETE FROM sync_intents WHERE id = :id AND venue_id = :venueId AND status = 'REJECTED'")
    suspend fun dismiss(id: String, venueId: String): Int

    /**
     * Descarta un intent PENDING que resultó innecesario — patrón write-ahead
     * (Task 4 P1 fix): el caller escribe el intent ANTES de intentar el
     * camino online, y si ESE intento SÍ tiene éxito (o el server lo rechaza
     * de forma permanente), el intent nunca debe reproducirse. Acotado a
     * `status = 'PENDING'` a propósito, igual que [dismiss]: nunca debe poder
     * borrar un intent ya terminal (ACKED/REJECTED) por error de llamada —
     * eso perdería el registro de lo que pasó.
     *
     * @return filas afectadas (0 = ya no estaba PENDING, p.ej. el replay lo
     *   ganó de mano — no-op seguro, nunca un error).
     */
    @Query("DELETE FROM sync_intents WHERE id = :id AND status = 'PENDING'")
    suspend fun discardPending(id: String): Int
}

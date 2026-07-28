package com.jaac.avoqado_tpv.features.tables.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Offline-first Mesas — el OUTBOX de intents. Espejo por diseño de
 * `avoqado-android/core/data/local/database/SyncIntentEntity.kt` (ya probado en
 * producción), adaptado al nombre de tabla que Task 3 fijó (`sync_intents`, no
 * `pos_sync_intents`).
 *
 * Toda mutación de mesas hecha sin red se escribe aquí ANTES de cualquier efecto
 * (write-ahead) y se reproduce FIFO por [seq] contra
 * `POST /tpv/venues/:id/sync/intents` al reconectar.
 *
 * `id` = UUID generado en el dispositivo = `idempotencyKey` que el server usa
 * TAL CUAL (ver `applyPayCash` en `avoqado-server/src/services/mobile/sync.mobile.service.ts`,
 * que pasa `intent.id` directo a `payCashOrder({ idempotencyKey: intent.id, ... })`)
 * — un reintento del MISMO id nunca duplica el efecto, sin que este cliente tenga
 * que inyectar un campo de idempotencia aparte en el payload.
 *
 * `RETRY` (transitorio, hoy solo `VERSION_CONFLICT`) NUNCA se escribe en [status] —
 * el intent se deja tal cual (PENDING) para que el próximo replay lo reintente
 * solo. Solo `ACKED`/`REJECTED` son estados terminales persistidos. Convertir un
 * RETRY en REJECTED aquí perdería el intent para siempre (P1 real, ver
 * `avoqado-server/.claude/rules/offline-first-y-hub-lan.md` §2.2).
 */
@Entity(
    tableName = "sync_intents",
    indices = [
        Index(value = ["venue_id", "status", "seq"]),
        // Defensa en profundidad del test de concurrencia (Task 3): si alguna vez
        // se rompe el @Transaction de SyncIntentDao.insertWithSeq y dos enqueue()
        // concurrentes leen el mismo maxSeq, este índice hace que el segundo INSERT
        // truene con SQLiteConstraintException en vez de duplicar el seq en
        // silencio — un desorden de FIFO (ADD_ITEMS antes que su OPEN_TABLE) es
        // mucho más caro de diagnosticar que un crash inmediato.
        Index(value = ["venue_id", "seq"], unique = true),
    ],
)
data class SyncIntentEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "venue_id")
    val venueId: String,
    @ColumnInfo(name = "seq")
    val seq: Long,
    /** Espejo EXACTO de `SyncIntentType` del server — ver [com.jaac.avoqado_tpv.features.tables.data.sync.SyncIntentTypes]. */
    @ColumnInfo(name = "type")
    val type: String,
    /** JSON del payload específico del tipo (`com.google.gson.JsonObject` serializado). */
    @ColumnInfo(name = "payload_json")
    val payloadJson: String,
    @ColumnInfo(name = "status", defaultValue = STATUS_PENDING)
    val status: String = STATUS_PENDING,
    @ColumnInfo(name = "error_code")
    val errorCode: String? = null,
    @ColumnInfo(name = "message")
    val message: String? = null,
    /** JSON del `result` del server cuando ACKED (ids reales, versión...). */
    @ColumnInfo(name = "result_json")
    val resultJson: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_ACKED = "ACKED"
        const val STATUS_REJECTED = "REJECTED"
    }
}

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
    @Query("SELECT * FROM sync_intents WHERE venue_id = :venueId AND id = :id LIMIT 1")
    suspend fun leer(venueId: String, id: String): SyncIntentEntity?
    @Query("UPDATE sync_intents SET payload_json = :wire WHERE venue_id = :venueId AND id = :id AND status = 'PENDING' AND payload_json = :original")
    suspend fun guardarReferencia(venueId: String, id: String, original: String, wire: String): Int


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

    /** Sólo el prefijo FIFO que cabe en una pasada; el límite se aplica en SQLite. */
    @Query("SELECT * FROM sync_intents WHERE venue_id = :venueId AND status = 'PENDING' ORDER BY seq ASC LIMIT :limit")
    suspend fun pendingBatchForVenue(venueId: String, limit: Int): List<SyncIntentEntity>

    /**
     * D4 (encargo dd4185): lo que BAJAR DE VERSIÓN borraría sin que el servidor lo tenga, de TODAS las sucursales del aparato
     * (la base se borra entera): lo que espera subir (PENDING) y los rechazos que nadie ha resuelto en la cuarentena
     * (REJECTED: el efectivo que el servidor no aceptó sólo existe aquí). Lo lee
     * [com.jaac.avoqado_tpv.features.remote_command.domain.hayAlgoQueBajarDeVersionBorraria].
     */
    @Query("SELECT COUNT(*) FROM sync_intents WHERE status IN ('PENDING', 'REJECTED')")
    suspend fun contarQueBajarDeVersionBorraria(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM sync_intents WHERE venue_id = :venueId AND status = 'PENDING')")
    suspend fun hasPendingForVenue(venueId: String): Boolean

    @Query("UPDATE sync_intents SET seq = :seq WHERE venue_id = :venueId AND id = :id AND status = 'PENDING'")
    suspend fun cambiarSeqPendiente(venueId: String, id: String, seq: Long): Int

    /**
     * D6: el SERVIDOR rechazó por número (`STALE_DEVICE_SEQUENCE`) porque ya vio [piso] de este aparato, MÁS ALTO que
     * cualquier número de esta base: la base se reinstaló o se borró y volvió a contar desde 1. Quien llama lo comprueba
     * ([com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox]); sólo así ninguna fila de esta base llegó a entrar
     * ni a quedar apartada en el servidor, y cambiarles el número no rompe una recuperación (el servidor retoma una
     * reserva sólo con su número original) ni reordena nada.
     *
     * Mueve TODO lo que sigue en espera arriba del piso y de cualquier fila de la base, en el mismo orden. Una sola
     * transacción: si el proceso muere, o queda como estaba o queda renumerado. Los números nuevos son mayores que todos
     * los de la base, así que ningún paso choca con el índice único. Las filas resueltas no se tocan (y una que dejó de
     * estar en espera a media transacción, tampoco).
     *
     * @return cuántas se renumeraron.
     */
    @Transaction
    suspend fun renumerarPendientesSobre(venueId: String, piso: Long): Int {
        var siguiente = maxOf(piso, maxSeq(venueId) ?: 0L) + 1
        var renumeradas = 0
        pendingForVenue(venueId).forEach { fila ->
            if (cambiarSeqPendiente(venueId, fila.id, siguiente) == 1) {
                siguiente++
                renumeradas++
            }
        }
        return renumeradas
    }

    /** Cuarentena: rechazos de negocio permanentes, visibles hasta que el mesero/gerente los descarte. */
    /**
     * D86: las filas vivas (PENDING o REJECTED) cuyo payload MENCIONA [texto] — p. ej. el id local de una cuenta abierta sin
     * red. Es sólo un filtro grueso para no leer toda la cola: quien llama revisa el JSON y se queda con las que de verdad
     * nombran esa cuenta.
     */
    @Query(
        "SELECT * FROM sync_intents WHERE venue_id = :venueId AND status IN ('PENDING', 'REJECTED') " +
            "AND instr(payload_json, :texto) > 0 ORDER BY seq ASC",
    )
    suspend fun vivasQueMencionan(venueId: String, texto: String): List<SyncIntentEntity>

    /**
     * D86 (Codex r7 H2–H4): emite cada vez que alguien escribe la cola de Mesas (encolar, ACK, rechazo, descarte, desde
     * cualquier camino: la pantalla, el cobro o el trabajador). Room vuelve a correr la consulta en cada escritura de la
     * tabla; el número da igual, importa el aviso.
     */
    @Query("SELECT COUNT(*) FROM sync_intents WHERE venue_id = :venueId")
    fun observarCambios(venueId: String): kotlinx.coroutines.flow.Flow<Int>

    /** D1 (Codex D88-D90 r7): la secuencia más alta de la cola de Mesas del venue. Sube con cada intención nueva; un ACK no la mueve. */
    @Query("SELECT MAX(seq) FROM sync_intents WHERE venue_id = :venueId")
    suspend fun ultimaSecuencia(venueId: String): Long?

    /** D86: aperturas (OPEN_TABLE) ya confirmadas cuyo payload menciona [texto] — el id local de una cuenta abierta sin red. */
    @Query(
        "SELECT * FROM sync_intents WHERE venue_id = :venueId AND type = 'OPEN_TABLE' AND status = 'ACKED' " +
            "AND instr(payload_json, :texto) > 0 ORDER BY seq DESC LIMIT 5",
    )
    suspend fun aperturasConfirmadasQueMencionan(venueId: String, texto: String): List<SyncIntentEntity>

    /**
     * D86 (Codex r1 #2): aperturas (OPEN_TABLE) confirmadas cuyo RESULTADO menciona [texto] — el id real de una cuenta —, para
     * saber con qué id local la abrió esta terminal aunque la sesión ya no lo recuerde («Ver cuenta» desde el plano).
     */
    @Query(
        "SELECT * FROM sync_intents WHERE venue_id = :venueId AND type = 'OPEN_TABLE' AND status = 'ACKED' " +
            "AND result_json IS NOT NULL AND instr(result_json, :texto) > 0 ORDER BY seq DESC LIMIT 5",
    )
    suspend fun aperturasConfirmadasConResultado(venueId: String, texto: String): List<SyncIntentEntity>

    /**
     * D16: rondas (ADD_ITEMS) confirmadas cuyo RESULTADO menciona una cuenta aparte y [texto] — el id de la cuenta que pidió esta
     * terminal. Filtro grueso; quien llama revisa el JSON ([com.jaac.avoqado_tpv.features.tables.data.cuentaAparteDe]).
     */
    @Query(
        "SELECT * FROM sync_intents WHERE venue_id = :venueId AND type = 'ADD_ITEMS' AND status = 'ACKED' " +
            "AND result_json IS NOT NULL AND instr(result_json, 'cuentaAparte') > 0 AND instr(result_json, :texto) > 0 " +
            "ORDER BY seq DESC LIMIT 20",
    )
    suspend fun confirmadasConCuentaAparte(venueId: String, texto: String): List<SyncIntentEntity>

    @Query("SELECT * FROM sync_intents WHERE venue_id = :venueId AND status = 'REJECTED' ORDER BY seq ASC")
    suspend fun rejectedForVenue(venueId: String): List<SyncIntentEntity>

    @Query("SELECT * FROM sync_intents WHERE venue_id = :venueId ORDER BY seq ASC")
    suspend fun allForVenue(venueId: String): List<SyncIntentEntity>

    /**
     * Resuelve un intent a un estado TERMINAL (`ACKED`/`REJECTED`). `RETRY` NUNCA
     * pasa por aquí — [com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox.applyAck]
     * lo intercepta antes y deja la fila intacta.
     *
     * CAS PENDING→terminal: repetir el ack es un no-op. Una fila ya terminal
     * no cuenta como avance nuevo ni puede ser reetiquetada por un ack tardío.
     *
     * @return filas afectadas (0 = el id no existe, p.ej. ya se descartó).
     */
    @Query(
        """
        UPDATE sync_intents
        SET status = :status, error_code = :errorCode, message = :message, result_json = :resultJson
        WHERE id = :id AND status = 'PENDING'
        """,
    )
    suspend fun resolve(id: String, status: String, errorCode: String?, message: String?, resultJson: String?): Int

    /**
     * El mesero/gerente descartó un rechazo de la cuarentena tras resolverlo a
     * mano. Acotado a `status = 'REJECTED'` a propósito: [dismiss] nunca debe
     * poder tocar un intent PENDING o ACKED por error de UI.
     *
     * MARCA la fila (`DISMISSED`) en vez de borrarla: [insertWithSeq] toma
     * `MAX(seq)+1` y el server ya vio ese `seq` — con la fila borrada, la
     * siguiente venta lo reutilizaba y salía `STALE_DEVICE_SEQUENCE`
     * (D1-S, Mesas40). Una fila marcada no la ve ningún lector operativo
     * (`pendingForVenue`/`rejectedForVenue` filtran estado exacto); sólo
     * `MAX(seq)` y [allForVenue].
     */
    @Query("UPDATE sync_intents SET status = 'DISMISSED' WHERE id = :id AND venue_id = :venueId AND status = 'REJECTED'")
    suspend fun dismiss(id: String, venueId: String): Int

    /**
     * nab0e38e: como [dismiss], pero lo hace la terminal sola y deja escrita la RAZÓN en `message` (el `error_code` del
     * SERVIDOR se conserva). Nada se quita de la cuarentena en silencio. Sólo sobre `REJECTED`.
     */
    @Query("UPDATE sync_intents SET status = 'DISMISSED', message = :mensaje WHERE id = :id AND venue_id = :venueId AND status = 'REJECTED'")
    suspend fun dismissConRazon(id: String, venueId: String, mensaje: String): Int

    /**
     * Descarta un intent PENDING que resultó innecesario — patrón write-ahead
     * (Task 4 P1 fix): el caller escribe el intent ANTES de intentar el
     * camino online, y si ESE intento SÍ tiene éxito (o el server lo rechaza
     * de forma permanente), el intent nunca debe reproducirse. Acotado a
     * `status = 'PENDING'` a propósito, igual que [dismiss]: nunca debe poder
     * tocar un intent ya terminal (ACKED/REJECTED) por error de llamada —
     * eso perdería el registro de lo que pasó.
     *
     * MARCA la fila (`DISCARDED`), no la borra, por la misma razón que
     * [dismiss]: un replay con respuesta perdida pudo hacer que el server ya
     * viera su `seq`.
     *
     * @return filas afectadas (0 = ya no estaba PENDING, p.ej. el replay lo
     *   ganó de mano — no-op seguro, nunca un error).
     */
    @Query("UPDATE sync_intents SET status = 'DISCARDED' WHERE id = :id AND status = 'PENDING'")
    suspend fun discardPending(id: String): Int
}

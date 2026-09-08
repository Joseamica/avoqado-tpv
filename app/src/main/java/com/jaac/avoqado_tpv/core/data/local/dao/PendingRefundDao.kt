package com.jaac.avoqado_tpv.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.jaac.avoqado_tpv.core.data.local.entity.PendingRefundEntity

/**
 * Acceso a la cola durable de REEMBOLSOS ([PendingRefundEntity]).
 *
 * Espeja a `PendingPaymentDao` a propósito —mismo claim por token, mismo lease, mismo CAS— para
 * no estrenar semántica en la ruta del dinero. Lo único que cambia de forma es la llave: aquí la
 * PK es la `idempotencyKey` (un `String`), no un autoincremento.
 */
@Dao
interface PendingRefundDao {

    /**
     * Encola un reembolso. `IGNORE` sobre la PK: reintentar el encolado del MISMO reembolso no
     * duplica la fila ni pisa la que ya está en vuelo.
     *
     * @return el rowid insertado, o -1 si ya existía (o sea, ya estaba encolado).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(row: PendingRefundEntity): Long

    /**
     * Reclama hasta [limit] filas de forma atómica y devuelve exactamente las que quedaron
     * marcadas con [token].
     *
     * Toma PENDING y también SYNCING abandonadas (`claimed_at < staleBefore`), para que un worker
     * muerto no deje reembolsos atorados para siempre — que aquí significa dinero que salió del
     * cajón y nunca se registró.
     */
    @Transaction
    suspend fun claimBatch(limit: Int, token: String, now: Long, staleBefore: Long): List<PendingRefundEntity> {
        markClaimed(limit, token, now, staleBefore)
        return getClaimed(token)
    }

    @Query(
        """
        UPDATE pending_refunds
        SET sync_status = 'SYNCING', claim_token = :token, claimed_at = :now
        WHERE idempotency_key IN (
            SELECT idempotency_key FROM pending_refunds
            WHERE sync_status = 'PENDING'
               OR (sync_status = 'SYNCING' AND claimed_at IS NOT NULL AND claimed_at < :staleBefore)
            ORDER BY created_at ASC
            LIMIT :limit
        )
    """
    )
    suspend fun markClaimed(limit: Int, token: String, now: Long, staleBefore: Long)

    @Query("SELECT * FROM pending_refunds WHERE claim_token = :token ORDER BY created_at ASC")
    suspend fun getClaimed(token: String): List<PendingRefundEntity>

    /**
     * El servidor confirmó el registro. CAS sobre [token] por lo mismo que en los cobros: un
     * worker pasmado que termina tarde no puede pisar la fila que otro ya reclamó.
     *
     * @return filas afectadas (0 = la fila ya no es de este worker; NO reintentar el write).
     */
    @Query(
        """
        UPDATE pending_refunds
        SET sync_status = 'SUCCESS', claim_token = NULL, claimed_at = NULL, last_error = NULL
        WHERE idempotency_key = :key AND claim_token = :token
    """
    )
    suspend fun markSuccess(key: String, token: String): Int

    /**
     * Fallo TRANSITORIO: vuelve a PENDING con el intento contado. SIEMPRE a PENDING.
     *
     * 🔴 Antes pasaba a FAILED al llegar a MAX_RETRY_ATTEMPTS (auditoría de Codex F4, 4-sep-2026): como
     * no era `permanent`, nadie la reclamaba (el claim sólo toma PENDING/SYNCING), nadie podía
     * reconocerla (sólo se reconocen rechazos permanentes) y bloqueaba el cierre para siempre. Un
     * fallo que el servidor nunca rechazó no es definitivo: se sigue intentando, el servidor deduplica.
     *
     * 🔴 Incluye 401/403 a propósito: casi siempre son el token de sesión atorado, no el reembolso.
     * Tratarlos como definitivos tiraría dinero que sí se podía registrar.
     */
    @Query(
        """
        UPDATE pending_refunds
        SET retry_count = :retryCount,
            last_error = :error,
            claim_token = NULL,
            claimed_at = NULL,
            sync_status = 'PENDING'
        WHERE idempotency_key = :key AND claim_token = :token
    """
    )
    suspend fun release(key: String, token: String, retryCount: Int, error: String): Int

    /**
     * Fallo DEFINITIVO de negocio (400/404/422 solamente). Marca `permanent` para que un reintento
     * masivo no lo resucite solo.
     *
     * 🔴 No se borra la fila: sigue **bloqueando el cierre de turno** hasta que un humano la vea.
     * Borrarla en silencio devolvería el defecto original — dinero devuelto sin registro y nadie
     * enterado.
     */
    @Query(
        """
        UPDATE pending_refunds
        SET sync_status = 'FAILED', permanent = 1, last_error = :error, claim_token = NULL, claimed_at = NULL
        WHERE idempotency_key = :key AND claim_token = :token
    """
    )
    suspend fun markPermanentlyFailed(key: String, token: String, error: String): Int

    /**
     * El cajero vio el aviso del reembolso rechazado y liberó el cierre.
     *
     * 🔴 Sin CAS de token a propósito: esto lo dispara un TOQUE MANUAL, no el worker, y la fila no
     * está reclamada por nadie cuando ocurre. Es la salida de emergencia de la barrera: sin ella,
     * un rechazo definitivo dejaría la caja sin poder cerrarse jamás.
     */
    @Query("UPDATE pending_refunds SET acknowledged = 1, acknowledged_by = :by, acknowledged_at = :at WHERE idempotency_key = :key")
    suspend fun acknowledge(key: String, by: String?, at: Long): Int

    /**
     * Lo que este pago original tiene sin resolver (todo lo que no es SUCCESS, reconocido o no).
     *
     * 🔴 Es el candado contra un SEGUNDO reembolso manual (auditoría de Codex F7): mientras el primero
     * no esté en el servidor, el saldo remoto sigue pareciendo reembolsable, y un segundo intento
     * nacería con otra llave — el cliente recibiría el dinero dos veces.
     */
    @Query("SELECT * FROM pending_refunds WHERE original_payment_id = :originalPaymentId AND sync_status != 'SUCCESS' ORDER BY created_at ASC")
    suspend fun unresolvedForPayment(originalPaymentId: String): List<PendingRefundEntity>

    /**
     * Lo que BLOQUEA el cierre de turno de este negocio.
     *
     * Bloquea todo lo que no llegó a registrarse y que nadie ha reconocido: lo pendiente, lo que
     * está en vuelo, y lo rechazado que el cajero todavía no vio. Deja pasar lo registrado
     * (`SUCCESS`) y lo rechazado ya reconocido.
     *
     * 🔴 Filtra por `venue_id`: aislamiento de tenant, regla dura del repo.
     */
    @Query(
        """
        SELECT * FROM pending_refunds
        WHERE venue_id = :venueId AND sync_status != 'SUCCESS' AND acknowledged = 0
        ORDER BY created_at ASC
    """
    )
    suspend fun blockingForVenue(venueId: String): List<PendingRefundEntity>

    @Query("SELECT COUNT(*) FROM pending_refunds WHERE sync_status IN ('PENDING', 'SYNCING')")
    suspend fun getPendingCount(): Int

    /**
     * Cuenta lo rechazado que NADIE ha reconocido — el mismo criterio que [blockingForVenue].
     * Antes contaba todo `FAILED`, incluido lo ya reconocido con «Ya lo vi»: el banner decía
     * «1 devolución rechazada» para siempre y su «Reintentar» no tenía nada que reintentar
     * (founder, N86, 7-sep-2026). Lo reconocido sigue en la tabla como rastro, pero ya no avisa.
     */
    @Query("SELECT COUNT(*) FROM pending_refunds WHERE sync_status = 'FAILED' AND acknowledged = 0")
    suspend fun getFailedCount(): Int

    /** Limpieza: sólo lo YA registrado y viejo. Nunca borra pendientes ni rechazados. */
    @Query("DELETE FROM pending_refunds WHERE sync_status = 'SUCCESS' AND created_at < :cutoff")
    suspend fun deleteOldSuccess(cutoff: Long): Int
}

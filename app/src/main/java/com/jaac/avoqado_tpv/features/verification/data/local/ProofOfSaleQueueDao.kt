package com.jaac.avoqado_tpv.features.verification.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Cola de fotos/números. Claim por token y CAS, igual que `PendingRefundDao`. */
@Dao
interface ProofOfSaleQueueDao {
    /** @return rowid, o -1 si la llave ya existía (IGNORE). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRow(row: ProofOfSaleQueueEntity): Long

    @Transaction
    suspend fun claimBatch(limit: Int, token: String, now: Long, staleBefore: Long): List<ProofOfSaleQueueEntity> {
        markClaimed(limit, token, now, staleBefore)
        return getClaimed(token)
    }

    // Lo que ya falló se forma al final (retry_count ASC): diez filas que siempre fallan no tapan una foto nueva.
    @Query(
        """
        UPDATE proof_of_sale_queue
        SET sync_status = 'SYNCING', claim_token = :token, claimed_at = :now
        WHERE idempotency_key IN (
            SELECT idempotency_key FROM proof_of_sale_queue
            WHERE sync_status = 'PENDING'
               OR (sync_status = 'SYNCING' AND claimed_at IS NOT NULL AND claimed_at < :staleBefore)
            ORDER BY retry_count ASC, created_at ASC
            LIMIT :limit
        )
    """
    )
    suspend fun markClaimed(limit: Int, token: String, now: Long, staleBefore: Long)

    @Query("SELECT * FROM proof_of_sale_queue WHERE claim_token = :token ORDER BY retry_count ASC, created_at ASC")
    suspend fun getClaimed(token: String): List<ProofOfSaleQueueEntity>

    @Query(
        """
        UPDATE proof_of_sale_queue
        SET sync_status = 'SUCCESS', claim_token = NULL, claimed_at = NULL, last_error = NULL
        WHERE idempotency_key = :key AND claim_token = :token
    """
    )
    suspend fun markSuccess(key: String, token: String): Int

    /** Fallo transitorio: siempre vuelve a PENDING con el intento contado. */
    @Query(
        """
        UPDATE proof_of_sale_queue
        SET retry_count = :retryCount, last_error = :error, claim_token = NULL, claimed_at = NULL, sync_status = 'PENDING'
        WHERE idempotency_key = :key AND claim_token = :token
    """
    )
    suspend fun release(key: String, token: String, retryCount: Int, error: String): Int

    @Query(
        """
        UPDATE proof_of_sale_queue
        SET sync_status = 'FAILED', permanent = 1, outcome = :outcome, last_error = :error, claim_token = NULL, claimed_at = NULL
        WHERE idempotency_key = :key AND claim_token = :token
    """
    )
    suspend fun markPermanentlyFailed(key: String, token: String, outcome: String, error: String): Int

    /** El servidor contestó algo que una persona debe ver; no se vuelve a reclamar. */
    @Query(
        """
        UPDATE proof_of_sale_queue
        SET sync_status = 'NEEDS_ATTENTION', outcome = :outcome, claim_token = NULL, claimed_at = NULL
        WHERE idempotency_key = :key AND claim_token = :token
    """
    )
    suspend fun markNeedsAttention(key: String, token: String, outcome: String): Int

    /** La foto ya está completa en su ruta final: la fila reservada al encolar pasa a PENDING y un worker ya la puede reclamar. */
    @Query(
        """
        UPDATE proof_of_sale_queue
        SET sync_status = 'PENDING', claim_token = NULL, claimed_at = NULL
        WHERE idempotency_key = :key AND claim_token = :token AND sync_status = 'SYNCING'
    """
    )
    suspend fun releaseReservation(key: String, token: String): Int

    @Query("DELETE FROM proof_of_sale_queue WHERE idempotency_key = :key")
    suspend fun delete(key: String): Int

    @Query("UPDATE proof_of_sale_queue SET photo_url = :url WHERE idempotency_key = :key AND claim_token = :token")
    suspend fun savePhotoUrl(key: String, token: String, url: String): Int

    /** Los archivos que todavía tienen fila (el barrido de huérfanos no los toca). */
    @Query("SELECT local_path FROM proof_of_sale_queue WHERE local_path IS NOT NULL")
    suspend fun localPaths(): List<String>

    @Query("SELECT * FROM proof_of_sale_queue WHERE staff_id = :staffId ORDER BY created_at DESC")
    fun observeStaff(staffId: String): Flow<List<ProofOfSaleQueueEntity>>

    @Query("SELECT * FROM proof_of_sale_queue WHERE idempotency_key = :key LIMIT 1")
    fun observeKey(key: String): Flow<ProofOfSaleQueueEntity?>
}

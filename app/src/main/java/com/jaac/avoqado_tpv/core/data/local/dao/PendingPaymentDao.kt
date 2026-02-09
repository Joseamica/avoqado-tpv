package com.jaac.avoqado_tpv.core.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity

/**
 * Data Access Object for offline payment queue.
 *
 * **Usage Pattern:**
 * ```kotlin
 * // 1. Insert failed payment
 * val id = dao.insert(PendingPaymentEntity(...))
 *
 * // 2. Worker fetches pending payments
 * val pending = dao.getAllPending()
 *
 * // 3. Process each payment
 * pending.forEach { payment ->
 *     val result = recordPaymentUseCase(...)
 *
 *     if (result.isSuccess) {
 *         dao.markSynced(payment.id)
 *     } else {
 *         dao.updateRetry(payment.id, payment.retryCount + 1, errorMessage)
 *     }
 * }
 *
 * // 4. Cleanup old synced payments
 * dao.deleteOldSyncedPayments(sevenDaysAgo)
 * ```
 */
@Dao
interface PendingPaymentDao {

    /**
     * Insert a new pending payment.
     *
     * **Conflict Strategy:** IGNORE
     * - If payment with same referenceNumber already exists, skip insert
     * - Prevents duplicate queue entries when user retries quickly
     *
     * @return Row ID of inserted payment (0 if conflict)
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(payment: PendingPaymentEntity): Long

    /**
     * Get all pending payments ordered by creation time (FIFO).
     *
     * **Use Case:** PaymentSyncWorker fetches these for retry
     *
     * @return List of payments with status = PENDING, oldest first
     */
    @Query("""
        SELECT * FROM pending_payments
        WHERE sync_status = 'PENDING'
        ORDER BY created_at ASC
    """)
    suspend fun getAllPending(): List<PendingPaymentEntity>

    /**
     * Mark payment as successfully synced.
     *
     * **Post-Sync Cleanup:** Payments marked as SUCCESS can be safely deleted after 7 days
     *
     * @param id Row ID of payment
     */
    @Query("""
        UPDATE pending_payments
        SET sync_status = 'SUCCESS'
        WHERE id = :id
    """)
    suspend fun markSynced(id: Long)

    /**
     * Update retry count and last error message after failed attempt.
     *
     * **Retry Logic:**
     * - Retry count < 3 → Status stays PENDING (will retry)
     * - Retry count >= 3 → Update to FAILED (manual review needed)
     *
     * @param id Row ID of payment
     * @param retryCount New retry count (incremented)
     * @param error Last error message for debugging
     */
    @Query("""
        UPDATE pending_payments
        SET retry_count = :retryCount,
            last_error = :error,
            sync_status = CASE
                WHEN :retryCount >= ${PendingPaymentEntity.MAX_RETRY_ATTEMPTS} THEN 'FAILED'
                ELSE sync_status
            END
        WHERE id = :id
    """)
    suspend fun updateRetry(id: Long, retryCount: Int, error: String)

    /**
     * Get count of pending payments (for UI badge).
     *
     * **Use Case:** Show "3 payments pending sync" in app UI
     *
     * @return Number of payments with status = PENDING
     */
    @Query("""
        SELECT COUNT(*) FROM pending_payments
        WHERE sync_status = 'PENDING'
    """)
    suspend fun getPendingCount(): Int

    /**
     * Get count of failed payments (for UI warning).
     *
     * **Use Case:** Show alert "2 payments failed - contact support"
     *
     * @return Number of payments with status = FAILED
     */
    @Query("""
        SELECT COUNT(*) FROM pending_payments
        WHERE sync_status = 'FAILED'
    """)
    suspend fun getFailedCount(): Int

    /**
     * Delete old synced payments (cleanup after 7 days).
     *
     * **Use Case:** Prevent database bloat from old successful syncs
     *
     * @param timestampMs Unix timestamp (e.g., 7 days ago)
     */
    @Query("""
        DELETE FROM pending_payments
        WHERE sync_status = 'SUCCESS'
        AND created_at < :timestampMs
    """)
    suspend fun deleteOldSyncedPayments(timestampMs: Long)

    /**
     * Delete a specific payment (manual cleanup or after max retries).
     *
     * @param payment Payment entity to delete
     */
    @Delete
    suspend fun delete(payment: PendingPaymentEntity)

    /**
     * Get all failed payments (for manual review screen).
     *
     * **Use Case:** Admin screen to review permanently failed payments
     *
     * @return List of payments with status = FAILED
     */
    @Query("""
        SELECT * FROM pending_payments
        WHERE sync_status = 'FAILED'
        ORDER BY created_at DESC
    """)
    suspend fun getAllFailed(): List<PendingPaymentEntity>

    /**
     * Reset all failed payments back to PENDING for retry.
     *
     * **Use Case:** When connection is restored, give failed payments another chance.
     * Resets retry_count to 0 so they get full retry attempts.
     *
     * @return Number of payments reset
     */
    @Query("""
        UPDATE pending_payments
        SET sync_status = 'PENDING', retry_count = 0
        WHERE sync_status = 'FAILED'
    """)
    suspend fun resetAllFailed(): Int
}

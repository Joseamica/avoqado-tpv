package com.jaac.avoqado_tpv.features.verification.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for verification queue operations.
 *
 * Provides methods to manage offline verification queue:
 * - Insert new verifications
 * - Query pending items for sync
 * - Update sync status
 * - Clean up synced items
 */
@Dao
interface VerificationQueueDao {

    /**
     * Insert a new verification into the queue.
     * Uses REPLACE strategy to update existing records with same ID.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: VerificationQueueEntity)

    /**
     * Update an existing verification record.
     */
    @Update
    suspend fun update(entity: VerificationQueueEntity)

    /**
     * Delete a verification record.
     */
    @Delete
    suspend fun delete(entity: VerificationQueueEntity)

    /**
     * Get all pending verifications that need to be synced.
     * Returns items with status: PENDING, UPLOADING_PHOTOS, SYNCING, or FAILED
     */
    @Query("""
        SELECT * FROM verification_queue
        WHERE syncStatus IN ('PENDING', 'UPLOADING_PHOTOS', 'SYNCING', 'FAILED')
        ORDER BY createdAt ASC
    """)
    suspend fun getPendingVerifications(): List<VerificationQueueEntity>

    /**
     * Get pending verifications as Flow for reactive updates.
     */
    @Query("""
        SELECT * FROM verification_queue
        WHERE syncStatus IN ('PENDING', 'UPLOADING_PHOTOS', 'SYNCING', 'FAILED')
        ORDER BY createdAt ASC
    """)
    fun observePendingVerifications(): Flow<List<VerificationQueueEntity>>

    /**
     * Get a specific verification by ID.
     */
    @Query("SELECT * FROM verification_queue WHERE id = :id")
    suspend fun getById(id: String): VerificationQueueEntity?

    /**
     * Get verifications for a specific payment.
     */
    @Query("SELECT * FROM verification_queue WHERE paymentId = :paymentId")
    suspend fun getByPaymentId(paymentId: String): List<VerificationQueueEntity>

    /**
     * Update sync status for a verification.
     */
    @Query("UPDATE verification_queue SET syncStatus = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    /**
     * Update photo URLs after Firebase upload success.
     */
    @Query("UPDATE verification_queue SET photoUrls = :urls, syncStatus = :status WHERE id = :id")
    suspend fun updatePhotoUrls(id: String, urls: String, status: String)

    /**
     * Increment sync attempts and record error.
     */
    @Query("""
        UPDATE verification_queue
        SET syncAttempts = syncAttempts + 1,
            lastSyncError = :error,
            syncStatus = 'FAILED'
        WHERE id = :id
    """)
    suspend fun recordSyncFailure(id: String, error: String)

    /**
     * Get count of pending verifications.
     */
    @Query("""
        SELECT COUNT(*) FROM verification_queue
        WHERE syncStatus IN ('PENDING', 'UPLOADING_PHOTOS', 'SYNCING', 'FAILED')
    """)
    suspend fun getPendingCount(): Int

    /**
     * Delete all synced verifications (cleanup).
     */
    @Query("DELETE FROM verification_queue WHERE syncStatus = 'SYNCED'")
    suspend fun deleteSynced()

    /**
     * Delete verifications older than specified timestamp.
     * Useful for cleanup of very old failed items.
     */
    @Query("DELETE FROM verification_queue WHERE createdAt < :beforeTimestamp")
    suspend fun deleteOlderThan(beforeTimestamp: Long)

    /**
     * Get all verifications for a venue (for debugging/admin).
     */
    @Query("SELECT * FROM verification_queue WHERE venueId = :venueId ORDER BY createdAt DESC")
    suspend fun getByVenueId(venueId: String): List<VerificationQueueEntity>
}

package com.jaac.avoqado_tpv.features.payment.domain.repository

import com.jaac.avoqado_tpv.features.payment.domain.model.QueuedPayment

/**
 * Repository interface for offline payment queue.
 *
 * **Purpose:** Abstract Room database operations for payment queueing.
 *
 * **Responsibilities:**
 * - Enqueue failed payment recordings
 * - Retrieve pending payments for retry
 * - Update sync status after retry attempts
 * - Provide queue statistics for UI
 *
 * **Implementation:** PaymentQueueRepositoryImpl (maps between domain models and Room entities)
 *
 * **Injected Into:**
 * - PaymentViewModel: Enqueue payments when backend recording fails
 * - PaymentSyncWorker: Fetch and retry pending payments
 *
 * **World-Class Pattern:**
 * - Square Terminal: QueueManager interface with LocalQueueManager implementation
 * - Toast POS: OfflinePaymentRepository with Room persistence
 * - Stripe Terminal: TransactionQueue interface with SQLite backing
 */
interface PaymentQueueRepository {

    /**
     * Enqueue a failed payment for later sync.
     *
     * **Use Case:** PaymentViewModel calls this when backend recording fails
     *
     * **Example:**
     * ```kotlin
     * result.onFailure { error ->
     *     val queuedPayment = QueuedPayment(
     *         referenceNumber = "000000188231",
     *         venueId = "venue_123",
     *         staffId = "staff_456",
     *         amount = BigDecimal("50.00"),
     *         tip = BigDecimal("5.00"),
     *         cardDetails = cardDetails,
     *         authorizationNumber = "502511"
     *     )
     *     queueRepository.enqueue(queuedPayment)
     * }
     * ```
     *
     * **Idempotency:** Duplicate referenceNumber ignored (UNIQUE constraint in DB)
     *
     * @param payment Payment to queue
     * @return Result.success(Unit) if queued, Result.failure if error
     */
    suspend fun enqueue(payment: QueuedPayment): Result<Unit>

    /**
     * Get all pending payments ordered by creation time (FIFO).
     *
     * **Use Case:** PaymentSyncWorker fetches these for retry
     *
     * **Example:**
     * ```kotlin
     * val pending = queueRepository.getAllPending()
     * pending.forEach { payment ->
     *     val result = recordPaymentUseCase(payment.toContext(), ...)
     *     if (result.isSuccess) {
     *         queueRepository.markSynced(payment.queueId)
     *     }
     * }
     * ```
     *
     * @return List of queued payments (empty if none pending)
     */
    suspend fun getAllPending(): List<QueuedPayment>

    /**
     * Mark payment as successfully synced to backend.
     *
     * **Use Case:** PaymentSyncWorker calls this after successful retry
     *
     * **Post-Sync:** Payment status changed to SUCCESS (can be cleaned up after 7 days)
     *
     * @param queueId Unique queue ID (not referenceNumber)
     */
    suspend fun markSynced(queueId: Long)

    /**
     * Update retry count and error message after failed retry attempt.
     *
     * **Retry Logic:**
     * - If retryCount < 3 → Status stays PENDING (will retry)
     * - If retryCount >= 3 → Status becomes FAILED (manual review needed)
     *
     * **Use Case:** PaymentSyncWorker calls this after retry fails
     *
     * @param queueId Unique queue ID
     * @param retryCount New retry count (incremented)
     * @param error Error message for debugging
     */
    suspend fun updateRetry(queueId: Long, retryCount: Int, error: String)

    /**
     * Get count of pending payments (for UI badge).
     *
     * **Use Case:** Show "3 payments pending sync" badge in app
     *
     * **Example:**
     * ```kotlin
     * val count = queueRepository.getPendingCount()
     * if (count > 0) {
     *     showBadge("$count pagos pendientes")
     * }
     * ```
     *
     * @return Number of payments with status = PENDING
     */
    suspend fun getPendingCount(): Int

    /**
     * Get count of failed payments (for UI warning).
     *
     * **Use Case:** Show alert "2 payments failed - contact support"
     *
     * @return Number of payments with status = FAILED
     */
    suspend fun getFailedCount(): Int

    /**
     * Delete old synced payments (cleanup after 7 days).
     *
     * **Use Case:** Prevent database bloat from old successful syncs
     *
     * **Called By:** Background cleanup worker (runs daily)
     *
     * @param daysAgo How many days ago to delete (default 7)
     */
    suspend fun deleteOldSyncedPayments(daysAgo: Int = 7)
}

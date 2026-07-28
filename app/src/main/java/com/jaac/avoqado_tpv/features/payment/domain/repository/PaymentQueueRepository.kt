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
     * **Cancellation (F-9, Fix round 3):** the persistence write survives cancellation of the
     * calling scope (e.g. `viewModelScope` cleared mid-flow when a screen closes). The card is
     * already charged by the time this is called — the write is this payment's entire safety
     * net, not abortable work. Same guarantee `PaymentAttemptLedger`'s post-SDK marks give
     * (`NonCancellable + Dispatchers.IO`), applied here for the same reason.
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
     *         queueRepository.markSynced(payment.queueId, payment.claimToken!!)
     *     }
     * }
     * ```
     *
     * **Note:** PaymentSyncWorker no longer calls this directly — it uses [claimBatch] instead
     * (F-8), which returns only rows this call atomically claimed. [getAllPending] remains for
     * any other read-only caller that doesn't need claim semantics.
     *
     * @return List of queued payments (empty if none pending)
     */
    suspend fun getAllPending(): List<QueuedPayment>

    /**
     * Mark payment as successfully synced to backend — compare-and-swap on [token] (F-8, Fix
     * round 1). Pass the `claimToken` the row was obtained with (from `claimBatch`). Returns
     * 0 when another worker has since reclaimed the row (stale-claim race) — the caller MUST
     * treat that as "not my row anymore", never as a failure to retry: the backend call that
     * got you here already succeeded.
     *
     * **Use Case:** PaymentSyncWorker calls this after successful retry
     *
     * **Post-Sync:** Payment status changed to SUCCESS (can be cleaned up after 7 days)
     *
     * @param queueId Unique queue ID (not referenceNumber)
     * @param token The claim_token this worker obtained the row under
     * @return Rows affected (0 = another worker reclaimed this row first)
     */
    suspend fun markSynced(queueId: Long, token: String): Int

    /**
     * Get count of pending-or-in-flight payments (for UI badge).
     *
     * **Includes SYNCING (Fix round 2):** a claimed-but-unresolved row is still money not yet
     * in the books — see [PendingPaymentDao.getPendingCount] for the incident this fixes.
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
     * @return Number of payments with status = PENDING or SYNCING
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

    /**
     * Reset all TRANSIENT failed payments back to PENDING for retry — excludes rows marked
     * `permanent` (F-10): a genuine 400/404/422 business failure doesn't get fixed by
     * reconnecting, so resurrecting it would burn another 10 retry attempts on every
     * connectivity flap, forever, instead of settling into a stable FAILED state.
     *
     * **This is the AUTOMATIC path** — called on every reconnect
     * ([com.jaac.avoqado_tpv.core.presentation.viewmodels.HomeViewModel]). It must keep
     * excluding permanent rows; the escape hatch for those is
     * [resetAllFailedIncludingPermanent], gated behind a deliberate human tap.
     *
     * **Use Case:** When connection is restored, give transient failures another chance
     *
     * @return Number of payments reset
     */
    suspend fun resetAllFailed(): Int

    /**
     * Reset ALL failed payments — INCLUDING `permanent` ones — back to PENDING (F-10, fix
     * round 1). The deliberate HUMAN escape hatch: unlike [resetAllFailed] (the automatic
     * reconnect path, which must keep excluding permanent rows), this is only ever called
     * from an explicit operator action
     * ([com.jaac.avoqado_tpv.core.presentation.viewmodels.DeviceHealthViewModel.retryFailedPayments])
     * — e.g. after re-logging in past a stuck token refresh, or an admin fixing a venue
     * config. Also clears `permanent` back to `false`: if the underlying problem is still
     * there, the next sync attempt just re-marks it via [markPermanentlyFailed] — a
     * one-shot second chance, not blanket amnesty.
     *
     * @return Number of payments reset (transient + permanent)
     */
    suspend fun resetAllFailedIncludingPermanent(): Int

    /**
     * Marca un pago reclamado como fallo de negocio PERMANENTE — SOLO 400/404/422 (ver
     * `SyncOutcome.PERMANENT_HTTP_CODES`; 401/403 son Retryable desde fix round 1, casi
     * siempre son la sesión, no el pago) — compare-and-swap on [token] (F-8, F-10), igual
     * que [release]. A diferencia de [release], la fila NUNCA vuelve a PENDING
     * automáticamente: pasa directo a FAILED con `permanent = true`, para que
     * [resetAllFailed] no la resucite en cada reconexión (aunque
     * [resetAllFailedIncludingPermanent] sí puede, vía un tap humano deliberado).
     *
     * @param queueId Unique queue ID (not referenceNumber)
     * @param token El claim_token con el que este worker obtuvo la fila (de [claimBatch])
     * @param error Razón legible (de SyncOutcome.Permanent.reason)
     * @return Filas afectadas (0 = otro worker ya reclamó esta fila)
     */
    suspend fun markPermanentlyFailed(queueId: Long, token: String, error: String): Int

    /** Reclama hasta [limit] pagos para este worker. Ver spec §4.2 F-8. */
    suspend fun claimBatch(limit: Int): List<QueuedPayment>

    /**
     * Suelta un pago reclamado que falló de forma transitoria — compare-and-swap on [token]
     * (F-8, Fix round 1). [token] debe ser el `claimToken` con el que se obtuvo la fila (de
     * [claimBatch]). Devuelve 0 si otro worker ya reclamó esta fila (stale-claim race) — en
     * ese caso el llamador NUNCA debe reintentar el write, la fila ya no es suya.
     *
     * @return Filas afectadas (0 = otro worker ya reclamó esta fila)
     */
    suspend fun release(queueId: Long, token: String, retryCount: Int, error: String): Int
}

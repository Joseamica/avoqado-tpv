package com.jaac.avoqado_tpv.core.data.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jaac.avoqado_tpv.features.payment.domain.repository.PaymentQueueRepository
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordPaymentUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Payment Sync Worker
 *
 * Periodic background worker that retries failed payment recordings to the backend.
 * Follows Square Terminal and Toast POS patterns for offline payment queue.
 *
 * **Execution Pattern:**
 * - Runs every 15 minutes (configurable via Constraints)
 * - Fetches all pending payments from Room database
 * - Retries each payment with exponential backoff (3 attempts max)
 * - Updates queue status: SUCCESS or FAILED
 * - Continues to run even when app is closed (background worker)
 *
 * **Design Principles:**
 * - **Offline-first**: Payments succeed locally (Blumon SDK), then queue for backend sync
 * - **Idempotent**: Backend uses Blumon referenceNumber to prevent duplicates
 * - **Eventually consistent**: Payments eventually sync when network available
 * - **Graceful degradation**: Terminal works fine if sync fails (payments are already approved)
 *
 * **Lifecycle:**
 * 1. PaymentSyncScheduler.start() → Enqueues periodic work
 * 2. WorkManager triggers every 15 minutes
 * 3. Worker fetches pending payments from PaymentQueueRepository
 * 4. Worker retries each payment via RecordPaymentUseCase
 * 5. On success → markSynced() and continue to next payment
 * 6. On failure → updateRetry() with incremented count
 * 7. After 3 attempts → Status becomes FAILED (manual review needed)
 * 8. Worker returns Result.success() to schedule next run
 *
 * **Error Handling:**
 * - Network timeout → Retry with exponential backoff (1s, 2s, 4s)
 * - HTTP 409 (duplicate) → Mark as synced (idempotency success)
 * - HTTP 4xx (client error) → Mark as failed (won't fix itself)
 * - HTTP 5xx (server error) → Retry (might be temporary)
 *
 * **Usage:**
 * This worker is NOT called directly. Use PaymentSyncScheduler to manage it:
 * ```kotlin
 * PaymentSyncScheduler.start(context) // Start on login
 * PaymentSyncScheduler.stop(context)  // Stop on logout
 * ```
 *
 * **Example Flow:**
 * ```
 * 1. User pays $50.00 → Blumon approves → PaymentViewModel records to backend
 * 2. Backend network timeout → PaymentViewModel queues payment
 * 3. PaymentSyncWorker runs (15 min later)
 * 4. Fetches queued payment from Room DB
 * 5. Retries RecordPaymentUseCase
 *    - Attempt 1: Fails (network still down) → Wait 1s
 *    - Attempt 2: Fails → Wait 2s
 *    - Attempt 3: Success → markSynced()
 * 6. Payment now visible in dashboard
 * ```
 *
 * **World-Class References:**
 * - Square Terminal: OfflineSyncWorker with 3 retries
 * - Toast POS: PaymentQueueWorker with 15-min periodic sync
 * - Stripe Terminal: TransactionSyncService with exponential backoff
 */
@HiltWorker
class PaymentSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val paymentQueueRepository: PaymentQueueRepository,
    private val recordPaymentUseCase: RecordPaymentUseCase
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        /**
         * Unique worker name for WorkManager.
         * Use this to enqueue/cancel work.
         */
        const val WORKER_NAME = "payment_sync_worker"

        /**
         * Maximum retry attempts per payment before marking as FAILED.
         * Matches PendingPaymentEntity.MAX_RETRY_ATTEMPTS = 10
         */
        private const val MAX_RETRY_ATTEMPTS = 10

        /**
         * Initial backoff delay in milliseconds (1 second).
         * Exponential backoff: 1s → 2s → 4s → ... capped at MAX_BACKOFF_MS
         */
        private const val INITIAL_BACKOFF_MS = 1000L

        /**
         * Maximum backoff delay cap (30 seconds).
         * Prevents exponential backoff from growing beyond reasonable limits.
         */
        private const val MAX_BACKOFF_MS = 30_000L

        /**
         * Maximum payments to process per worker run.
         * Prevents WorkManager 10-minute timeout when queue is large.
         * Remaining payments will be processed in the next periodic run (15 min).
         */
        private const val MAX_PAYMENTS_PER_RUN = 10
    }

    /**
     * Execute payment sync task
     *
     * **Return Values:**
     * - Result.success() → All pending payments processed (even if some failed)
     * - Result.retry() → Worker crashed, WorkManager should retry
     * - Result.failure() → Permanent failure (e.g., terminal not activated)
     *
     * **Retry Policy:**
     * This worker ALWAYS returns Result.success() to continue periodic runs.
     * Individual payment retries are handled internally with exponential backoff.
     *
     * **Important:** Unlike HeartbeatWorker, this worker processes ALL pending payments
     * in a single run. It doesn't stop at the first failure.
     */
    override suspend fun doWork(): Result {
        return try {
            Timber.d("💾 [Payment Sync] Worker started")

            // Fetch all pending payments from Room database
            val pendingPayments = paymentQueueRepository.getAllPending()

            if (pendingPayments.isEmpty()) {
                Timber.d("✅ [Payment Sync] No pending payments to sync")
                return Result.success()
            }

            Timber.i("🔄 [Payment Sync] Found ${pendingPayments.size} pending payments to sync")

            // Process payments in batches to avoid WorkManager 10-minute timeout
            val batch = pendingPayments.take(MAX_PAYMENTS_PER_RUN)
            if (batch.size < pendingPayments.size) {
                Timber.w("⚠️ [Payment Sync] Processing ${batch.size}/${pendingPayments.size} (batch limit) — rest in next run")
            }

            // Process each payment independently (one failure doesn't block others)
            var successCount = 0
            var failedCount = 0

            for (payment in batch) {
                val success = syncPayment(payment)
                if (success) {
                    successCount++
                } else {
                    failedCount++
                }
            }

            // Log summary
            Timber.i(
                "✅ [Payment Sync] Worker completed | " +
                        "success=$successCount | failed=$failedCount | batch=${batch.size} | total=${pendingPayments.size}"
            )

            // ALWAYS return success to continue periodic runs
            // Even if some payments failed, we want the worker to run again in 15 minutes
            Result.success()
        } catch (e: CancellationException) {
            throw e  // Worker cancelled — don't treat as error
        } catch (e: Exception) {
            // Unexpected error (e.g., database crash)
            Timber.e(e, "💥 [Payment Sync] Worker crashed, will retry")
            Result.retry()
        }
    }

    /**
     * Sync a single payment with exponential backoff retry.
     *
     * **Retry Logic:**
     * - Attempt 1: Immediate (0s delay)
     * - Attempt 2: After 1s delay (if attempt 1 fails)
     * - Attempt 3: After 2s delay (if attempt 2 fails)
     * - Attempt 4: After 4s delay (if attempt 3 fails)
     *
     * After MAX_RETRY_ATTEMPTS (3), the payment is marked as FAILED.
     *
     * **Error Handling:**
     * - HTTP 409 (duplicate) → Mark as synced (idempotency success)
     * - Network timeout → Retry with backoff
     * - HTTP 4xx → Mark as failed immediately (client error)
     * - HTTP 5xx → Retry with backoff (server error might be temporary)
     *
     * @param payment Queued payment to sync
     * @return true if synced successfully, false if failed
     */
    private suspend fun syncPayment(payment: com.jaac.avoqado_tpv.features.payment.domain.model.QueuedPayment): Boolean {
        Timber.d("🔄 [Payment Sync] Syncing payment | ref=${payment.referenceNumber} | queueId=${payment.queueId}")

        var currentAttempt = payment.retryCount + 1

        while (currentAttempt <= MAX_RETRY_ATTEMPTS) {
            try {
                // Calculate exponential backoff delay (except first attempt), capped at MAX_BACKOFF_MS
                if (currentAttempt > 1) {
                    val exponent = minOf(currentAttempt - 2, 14) // Prevent overflow for high retry counts
                    val backoffDelay = (INITIAL_BACKOFF_MS * (1L shl exponent)).coerceAtMost(MAX_BACKOFF_MS)
                    Timber.d("⏳ [Payment Sync] Retry attempt $currentAttempt after ${backoffDelay}ms backoff")
                    delay(backoffDelay)
                } else {
                    Timber.d("🔄 [Payment Sync] Attempt $currentAttempt/$MAX_RETRY_ATTEMPTS")
                }

                // Convert QueuedPayment to PaymentContext and CardDetails
                val paymentContext = payment.toPaymentContext()
                val cardDetails = payment.toCardDetails()

                // Call RecordPaymentUseCase (same flow as PaymentViewModel)
                val result = recordPaymentUseCase(
                    context = paymentContext,
                    cardDetails = cardDetails,
                    authorizationNumber = payment.authorizationNumber ?: "",
                    referenceNumber = payment.referenceNumber
                )

                // Handle result
                if (result.isSuccess) {
                    Timber.i(
                        "✅ [Payment Sync] Payment synced successfully | " +
                                "ref=${payment.referenceNumber} | queueId=${payment.queueId} | " +
                                "attempts=$currentAttempt"
                    )

                    // Mark as synced in database
                    paymentQueueRepository.markSynced(payment.queueId)
                    return true
                } else {
                    val error = result.exceptionOrNull()
                    val errorMessage = error?.message ?: "Unknown error"

                    // Check if error is idempotency success (HTTP 409 or duplicate message)
                    if (errorMessage.contains("duplicate", ignoreCase = true) ||
                        errorMessage.contains("409", ignoreCase = true) ||
                        errorMessage.contains("idempoten", ignoreCase = true)
                    ) {
                        Timber.i(
                            "✅ [Payment Sync] Payment already exists (idempotency success) | " +
                                    "ref=${payment.referenceNumber} | Marking as synced"
                        )
                        paymentQueueRepository.markSynced(payment.queueId)
                        return true
                    }

                    // Check if error is permanent (client error 4xx, except 409)
                    if (errorMessage.contains("400") ||
                        errorMessage.contains("401") ||
                        errorMessage.contains("403") ||
                        errorMessage.contains("404")
                    ) {
                        Timber.e(
                            error,
                            "❌ [Payment Sync] Permanent client error (won't retry) | " +
                                    "ref=${payment.referenceNumber} | error=$errorMessage"
                        )
                        paymentQueueRepository.updateRetry(
                            queueId = payment.queueId,
                            retryCount = MAX_RETRY_ATTEMPTS, // Force FAILED status
                            error = errorMessage
                        )
                        return false
                    }

                    // Temporary error (network timeout, 5xx, etc.) - retry with backoff
                    Timber.w(
                        error,
                        "⚠️ [Payment Sync] Attempt $currentAttempt/$MAX_RETRY_ATTEMPTS failed | " +
                                "ref=${payment.referenceNumber} | error=$errorMessage"
                    )

                    currentAttempt++

                    // If max attempts reached, mark as failed
                    if (currentAttempt > MAX_RETRY_ATTEMPTS) {
                        Timber.e(
                            "❌ [Payment Sync] Payment marked as FAILED after $MAX_RETRY_ATTEMPTS attempts | " +
                                    "ref=${payment.referenceNumber} | queueId=${payment.queueId}"
                        )
                        paymentQueueRepository.updateRetry(
                            queueId = payment.queueId,
                            retryCount = MAX_RETRY_ATTEMPTS,
                            error = errorMessage
                        )
                        return false
                    }

                    // Update retry count in database (for tracking)
                    paymentQueueRepository.updateRetry(
                        queueId = payment.queueId,
                        retryCount = currentAttempt - 1,
                        error = errorMessage
                    )
                }
            } catch (e: CancellationException) {
                throw e  // Worker cancelled — don't mark payment as failed
            } catch (e: Exception) {
                // Unexpected error during sync attempt
                Timber.e(
                    e,
                    "💥 [Payment Sync] Unexpected error during attempt $currentAttempt | " +
                            "ref=${payment.referenceNumber}"
                )

                currentAttempt++

                if (currentAttempt > MAX_RETRY_ATTEMPTS) {
                    paymentQueueRepository.updateRetry(
                        queueId = payment.queueId,
                        retryCount = MAX_RETRY_ATTEMPTS,
                        error = e.message ?: "Unexpected error"
                    )
                    return false
                }

                paymentQueueRepository.updateRetry(
                    queueId = payment.queueId,
                    retryCount = currentAttempt - 1,
                    error = e.message ?: "Unexpected error"
                )
            }
        }

        // Should never reach here (loop should exit via return)
        Timber.e("❌ [Payment Sync] Unexpected: max attempts reached without return")
        return false
    }
}

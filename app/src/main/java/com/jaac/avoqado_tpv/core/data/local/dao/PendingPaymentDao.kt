package com.jaac.avoqado_tpv.core.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity

/**
 * Data Access Object for offline payment queue.
 *
 * **Usage Pattern:**
 * ```kotlin
 * // 1. Insert failed payment
 * val id = dao.insert(PendingPaymentEntity(...))
 *
 * // 2. Worker claims a batch (F-8 — atomic, two workers never get the same rows)
 * val claimed = dao.claimBatch(limit = 10, token = workerToken, now = ..., staleBefore = ...)
 *
 * // 3. Process each payment
 * claimed.forEach { payment ->
 *     val result = recordPaymentUseCase(...)
 *
 *     if (result.isSuccess) {
 *         dao.markSynced(payment.id, payment.claimToken!!) // CAS — 0 rows if reclaimed since
 *     } else {
 *         dao.release(payment.id, payment.claimToken!!, payment.retryCount + 1, errorMessage)
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
     * Mark payment as successfully synced — compare-and-swap on [token] (F-8, Fix round 1).
     * Also clears `claim_token`/`claimed_at` (Fix round 2): SUCCESS is a terminal state, and
     * `markSynced` was the only terminal transition that left the claim behind — harmless for
     * claim decisions (only PENDING/stale-SYNCING rows are ever claimable) but needless litter
     * on a row nothing will touch again for up to 7 days.
     *
     * **Why CAS:** without the `claim_token` guard, a worker that stalls past the stale-claim
     * threshold can complete late and mark SUCCESS on a row a second worker has since
     * reclaimed and is actively recording — silently clobbering the second worker's live
     * claim. Affects 0 rows when [token] no longer matches (someone else reclaimed it);
     * the caller MUST NOT treat that as a failure to retry — the backend already has the
     * payment, only the local bookkeeping lost the race.
     *
     * **Post-Sync Cleanup:** Payments marked as SUCCESS can be safely deleted after 7 days
     *
     * @param id Row ID of payment
     * @param token The claim_token this worker obtained the row under (from claimBatch)
     * @return Rows affected (0 = another worker reclaimed this row first)
     */
    @Query("""
        UPDATE pending_payments
        SET sync_status = 'SUCCESS',
            claim_token = NULL,
            claimed_at = NULL
        WHERE id = :id AND claim_token = :token
    """)
    suspend fun markSynced(id: Long, token: String): Int

    /**
     * Update retry count and last error message after failed attempt, WITHOUT clearing the
     * claim — compare-and-swap on [token] (F-8, Fix round 2).
     *
     * 🔴 **No production caller since F-9 (Fix round 1).** This existed for the generic
     * `catch (e: Exception)` path in the OLD `PaymentSyncWorker.syncPayment()`, which looped
     * inside a single worker run and needed to update retry bookkeeping WITHOUT releasing the
     * claim — the same worker was about to try again on that same claimed row. F-9 deleted that
     * loop: every worker run now makes exactly one attempt per payment, and every outcome goes
     * through [release] (or `markSynced`), both of which DO clear the claim so the row is
     * claimable again on the next trigger. **If you need to record a failed sync attempt, use
     * [release] — not this.** This method is kept only for its own CAS regression test
     * (`PendingPaymentClaimTest`); the application-facing `PaymentQueueRepository` no longer
     * exposes it, precisely so nobody reaches for a claim-retaining write by accident.
     *
     * Same CAS risk as [release]/`markSynced` if a worker stalls past the stale-claim threshold
     * while a second worker has since reclaimed the row. Affects 0 rows when [token] no longer
     * matches; the caller MUST NOT retry that write — the row belongs to someone else now.
     *
     * **Retry Logic:**
     * - Retry count < MAX_RETRY_ATTEMPTS → Status stays whatever it currently is (claim NOT cleared)
     * - Retry count >= MAX_RETRY_ATTEMPTS → Status becomes FAILED (claim still NOT cleared — another
     *   reason this isn't what you want outside the old loop; [release] clears it in both cases)
     *
     * @param id Row ID of payment
     * @param token The claim_token this worker obtained the row under (from claimBatch)
     * @param retryCount New retry count (incremented)
     * @param error Last error message for debugging
     * @return Rows affected (0 = another worker reclaimed this row first)
     */
    @Query("""
        UPDATE pending_payments
        SET retry_count = :retryCount,
            last_error = :error,
            sync_status = CASE
                WHEN :retryCount >= ${PendingPaymentEntity.MAX_RETRY_ATTEMPTS} THEN 'FAILED'
                ELSE sync_status
            END
        WHERE id = :id AND claim_token = :token
    """)
    suspend fun updateRetry(id: Long, token: String, retryCount: Int, error: String): Int

    /**
     * Get count of pending-or-in-flight payments (for UI badge).
     *
     * **Includes SYNCING (Fix round 2 — Important 1):** a row a worker has claimed but not yet
     * resolved is still real money not yet in the books — excluding it made the "N pagos
     * pendientes" badge read 0 while a whole claimed batch (up to 10 payments) sat unrecorded,
     * exactly during the window (mid-batch worker death, WorkManager's execution cap, app kill)
     * where a supervisor most needs to see it.
     *
     * **Use Case:** Show "3 payments pending sync" in app UI
     *
     * @return Number of payments with status = PENDING or SYNCING
     */
    @Query("""
        SELECT COUNT(*) FROM pending_payments
        WHERE sync_status IN ('PENDING', 'SYNCING')
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
     * Marca una fila como fallo de negocio PERMANENTE (4xx que no se arregla solo) —
     * compare-and-swap on [token] (F-8, F-10). A diferencia de [release], nunca vuelve
     * a PENDING: pasa directo a FAILED con `permanent = 1`, así [resetAllFailed] la
     * excluye para siempre en vez de reintentarla en cada reconexión.
     *
     * **Por qué CAS:** mismo riesgo que [release]/[markSynced] — si el worker se queda
     * pasmado más allá del umbral de stale-claim y otro worker ya reclamó la fila, este
     * write no debe pisarle el claim vigente. Afecta 0 filas cuando [token] ya no
     * coincide (otro worker la reclamó primero); el llamador NUNCA debe reintentar ese
     * write — la fila ya no es suya. Ver spec §4.2 F-10.
     *
     * @param id Row ID of payment
     * @param token El claim_token con el que este worker obtuvo la fila (de claimBatch)
     * @param error Razón legible (de SyncOutcome.Permanent.reason), p.ej. "HTTP 404: Order not found"
     * @return Filas afectadas (0 = otro worker ya reclamó esta fila)
     */
    @Query("""
        UPDATE pending_payments
        SET sync_status = 'FAILED',
            permanent = 1,
            last_error = :error,
            claim_token = NULL,
            claimed_at = NULL
        WHERE id = :id AND claim_token = :token
    """)
    suspend fun markPermanentlyFailed(id: Long, token: String, error: String): Int

    /**
     * Reset all TRANSIENT failed payments back to PENDING for retry.
     *
     * **Use Case:** When connection is restored, give transient failures another chance.
     * Resets retry_count to 0 so they get full retry attempts.
     *
     * **Excludes permanent failures (F-10):** a row with `permanent = 1` failed on a
     * business rule (400/404/422) that reconnecting cannot fix. Resurrecting it here
     * would burn MAX_RETRY_ATTEMPTS (10) more attempts on every connectivity flap,
     * forever, instead of settling into a stable, inert FAILED state — which today is
     * surfaced only by the [getFailedCount] badge; [getAllFailed] has no caller yet, so
     * there is no dedicated review screen (that's a real gap, not solved by this flag).
     * Rows FAILED before v29 have `permanent = 0` (unknown → resurrectable by default,
     * see PendingPaymentEntity.permanent) and are unaffected by this change.
     *
     * **This is the AUTOMATIC path** (fires on every reconnect — see
     * [com.jaac.avoqado_tpv.core.presentation.viewmodels.HomeViewModel]). For the
     * deliberate human tap that should also un-stick permanent rows, use
     * [resetAllFailedIncludingPermanent] instead — see its KDoc, added fix round 1 after
     * a 401/403-misclassification bug showed this exclusion needed an escape hatch.
     *
     * @return Number of payments reset
     */
    @Query("""
        UPDATE pending_payments
        SET sync_status = 'PENDING', retry_count = 0
        WHERE sync_status = 'FAILED' AND permanent = 0
    """)
    suspend fun resetAllFailed(): Int

    /**
     * Reset ALL failed payments back to PENDING for retry — INCLUDING `permanent = 1`
     * rows (F-10, fix round 1). This is the deliberate HUMAN escape hatch: [resetAllFailed]
     * (what the automatic reconnect flow calls) excludes permanent rows on purpose, but an
     * operator who taps "reintentar" — after re-logging in, or an admin fixing the venue —
     * needs a way to actually un-stick a row that got wrongly stuck, or that a since-fixed
     * condition no longer blocks. Also resets `permanent` back to 0: if the underlying
     * problem is still there, the very next sync attempt re-marks it FAILED+permanent via
     * [markPermanentlyFailed] — this is a one-shot second chance, not blanket amnesty.
     *
     * @return Number of payments reset (transient + permanent)
     */
    @Query("""
        UPDATE pending_payments
        SET sync_status = 'PENDING', retry_count = 0, permanent = 0
        WHERE sync_status = 'FAILED'
    """)
    suspend fun resetAllFailedIncludingPermanent(): Int

    /**
     * Busca una fila por reference number, acotada al venue del pago que está
     * intentando encolarse.
     *
     * Se usa al chocar el índice único en [insert]: hay que distinguir "el mismo pago
     * ya está encolado y a salvo" (PENDING/SYNCING → OK) de "una fila vieja bloquea el
     * índice y este pago NO entró" (SUCCESS/FAILED → error). Ver spec §4.2 F-7.
     *
     * **Por qué lleva `venueId` (Fix round 1, finding 1):** el índice único de
     * `reference_number` es GLOBAL, pero esta tabla NO se vacía al cambiar de venue o
     * hacer logout (a propósito — guarda datos de dinero). Un terminal reasignado de un
     * venue a otro (patrón operativo real: promotores de relevo, re-parenting) puede
     * reusar un reference_number corto/secuencial que OTRO venue aún tiene vivo
     * localmente. Sin este filtro, esa fila ajena se leería como "el mismo pago,
     * ya a salvo" y el pago nuevo se perdería en silencio — el mismo bug que esta
     * función existe para cerrar, solo que cruzando venues en vez de cruzando el tiempo.
     */
    @Query("SELECT * FROM pending_payments WHERE reference_number = :reference AND venue_id = :venueId LIMIT 1")
    suspend fun findByReference(reference: String, venueId: String): PendingPaymentEntity?

    /**
     * Reclama hasta [limit] filas de forma atómica y devuelve exactamente las que
     * quedaron marcadas con [token].
     *
     * Toma filas PENDING, y también SYNCING abandonadas (claimed_at < staleBefore),
     * para que un worker muerto no deje pagos atorados para siempre.
     */
    @Transaction
    suspend fun claimBatch(limit: Int, token: String, now: Long, staleBefore: Long): List<PendingPaymentEntity> {
        markClaimed(limit, token, now, staleBefore)
        return getClaimed(token)
    }

    @Query("""
        UPDATE pending_payments
        SET sync_status = 'SYNCING', claim_token = :token, claimed_at = :now
        WHERE id IN (
            SELECT id FROM pending_payments
            WHERE sync_status = 'PENDING'
               OR (sync_status = 'SYNCING' AND claimed_at IS NOT NULL AND claimed_at < :staleBefore)
            ORDER BY created_at ASC
            LIMIT :limit
        )
    """)
    suspend fun markClaimed(limit: Int, token: String, now: Long, staleBefore: Long)

    @Query("SELECT * FROM pending_payments WHERE claim_token = :token ORDER BY created_at ASC")
    suspend fun getClaimed(token: String): List<PendingPaymentEntity>

    /**
     * Suelta una fila reclamada y la devuelve a PENDING con el retry incrementado
     * (o FAILED si llegó al tope) — compare-and-swap on [token] (F-8, Fix round 1).
     *
     * **Por qué CAS:** sin el candado de `claim_token`, un worker que se queda pasmado más
     * allá del umbral de stale-claim puede terminar tarde y soltar una fila que un segundo
     * worker ya reclamó y está registrando activamente — le borraría el claim en pleno
     * vuelo. Un tercer claim podría entonces tomar esa fila junto con el segundo worker:
     * dos workers registrando el mismo pago ya cobrado. Afecta 0 filas cuando [token] ya
     * no coincide (otro worker la reclamó primero); el llamador NUNCA debe reintentar ese
     * write — la fila ya no es suya.
     *
     * @param token El claim_token con el que este worker obtuvo la fila (de claimBatch)
     * @return Filas afectadas (0 = otro worker ya reclamó esta fila)
     */
    @Query("""
        UPDATE pending_payments
        SET retry_count = :retryCount,
            last_error = :error,
            claim_token = NULL,
            claimed_at = NULL,
            sync_status = CASE
                WHEN :retryCount >= ${PendingPaymentEntity.MAX_RETRY_ATTEMPTS} THEN 'FAILED'
                ELSE 'PENDING'
            END
        WHERE id = :id AND claim_token = :token
    """)
    suspend fun release(id: Long, token: String, retryCount: Int, error: String): Int
}

package com.jaac.avoqado_tpv.core.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.jaac.avoqado_tpv.features.payment.data.ledger.checkHandoff
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity
import com.jaac.avoqado_tpv.features.payment.data.ledger.HANDOFF_CONTRACT_CONFLICT
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
    @Query("""UPDATE pending_payments SET order_wire_request_json = :wire WHERE venue_id = :venueId AND id = :id
        AND claim_token = :token AND sync_status = 'SYNCING' AND order_request_json = :original
        AND order_wire_request_json IS :previous""")
    suspend fun guardarCopiaDeEnvio(venueId: String, id: Long, token: String, original: String, previous: String?, wire: String): Int


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
    suspend fun insertRow(payment: PendingPaymentEntity): Long

    @Query("""UPDATE payment_attempts SET last_error = CASE
        WHEN instr(COALESCE(last_error,''), :reason) > 0 THEN last_error
        ELSE substr(:reason || COALESCE(' | ' || last_error, ''), 1, 2000) END
        WHERE venue_id = :venueId AND attempt_id = :key""")
    suspend fun noteHandoffConflict(venueId: String, key: String, reason: String): Int

    @Query("SELECT * FROM payment_attempts WHERE venue_id = :venueId AND attempt_id = :key LIMIT 1")
    suspend fun ownerForHandoff(venueId: String, key: String): com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity?

    @Query("SELECT COUNT(*) FROM local_shift_sessions WHERE venue_id = :venueId AND local_session_id = :id AND local_status = 'OPEN'")
    suspend fun sessionOpen(venueId: String, id: String): Int

    @Query("""UPDATE payment_attempts SET state = 'ENTREGADA_A_COLA', state_version = state_version + 1, updated_at = :now
        WHERE venue_id = :venueId AND attempt_id = :key AND state IN ('AUTORIZADO','HOST_RESPONDIO','REGISTRO_FALLIDO','INDETERMINADO')""")
    suspend fun handoff(venueId: String, key: String, now: Long): Int

    /** El anterior dueño sólo se entrega cuando su sucesor quedó en ESTA transacción. */
    @Transaction
    suspend fun insert(payment: PendingPaymentEntity): Long {
        val owner = payment.idempotencyKey?.let { ownerForHandoff(payment.venueId, it) }
        val originalStaff = owner?.let { runCatching { com.google.gson.JsonParser.parseString(it.paymentContextJson).asJsonObject.get("staffId")?.takeUnless { it.isJsonNull }?.asString }.getOrNull() }
        val row = if (owner != null) payment.copy(staffId = originalStaff ?: payment.staffId, shiftMembership = owner.shiftMembership,
            localShiftSessionId = owner.localShiftSessionId, cashDrawerSessionId = owner.cashDrawerSessionId) else payment
        if (owner != null && payment.shiftMembership != "UNKNOWN") {
            if (payment.shiftMembership != owner.shiftMembership || payment.localShiftSessionId != owner.localShiftSessionId ||
                payment.cashDrawerSessionId != owner.cashDrawerSessionId) {
                noteHandoffConflict(payment.venueId, owner.attemptId, "handoff_contrato_contradictorio:membership")
                return HANDOFF_CONTRACT_CONFLICT
            }
        }
        val contract = owner?.checkHandoff(row)
        if (owner != null && contract?.conflict != null) {
            noteHandoffConflict(payment.venueId, owner.attemptId, "handoff_contrato_contradictorio:${contract.conflict}")
            return HANDOFF_CONTRACT_CONFLICT
        }
        val existing = findByReference(row.referenceNumber, row.venueId)
        if (existing == null && owner == null && row.shiftMembership == "MANAGED") {
            check(row.localShiftSessionId != null && sessionOpen(row.venueId, row.localShiftSessionId) == 1) { "La caja ya está cerrada en este aparato." }
        }
        val id = insertRow(row)
        val identical = existing != null && !row.idempotencyKey.isNullOrBlank() && existing.idempotencyKey == row.idempotencyKey &&
            existing.amount.toBigDecimal().compareTo(row.amount.toBigDecimal()) == 0 && existing.tip.toBigDecimal().compareTo(row.tip.toBigDecimal()) == 0 &&
            existing.staffId == row.staffId && existing.orderId == row.orderId && existing.paymentProcessor == row.paymentProcessor &&
            existing.terminalPaymentRequestId == row.terminalPaymentRequestId && existing.shiftMembership == row.shiftMembership &&
            existing.localShiftSessionId == row.localShiftSessionId && existing.cashDrawerSessionId == row.cashDrawerSessionId
        if (owner != null && contract?.proven == true && (id > 0 || identical)) {
            if (existing?.syncStatus == "SUCCESS") acknowledgeOwner(row.venueId, owner.attemptId, System.currentTimeMillis())
            else handoff(row.venueId, owner.attemptId, System.currentTimeMillis())
        }
        return id
    }

    /**
     * Get all pending payments ordered by creation time (FIFO).
     *
     * **Use Case:** PaymentSyncWorker fetches these for retry
     *
     * @return List of payments with status = PENDING, oldest first, incluidas las ventas locales con su pedido.
     */
    @Query("""
        SELECT * FROM pending_payments
        WHERE sync_status = 'PENDING'
        ORDER BY created_at ASC, id ASC
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
    suspend fun markSyncedRow(id: Long, token: String): Int

    @Query("SELECT * FROM pending_payments WHERE id = :id AND claim_token = :token LIMIT 1")
    suspend fun ownedRow(id: Long, token: String): PendingPaymentEntity?

    @Query("""UPDATE payment_attempts SET state = 'REGISTRADO', state_version = state_version + 1, updated_at = :now
        WHERE venue_id = :venueId AND attempt_id = :key AND state IN ('ENTREGADA_A_COLA','AUTORIZADO','HOST_RESPONDIO','REGISTRO_FALLIDO','INDETERMINADO')""" +
        " AND NOT " + PaymentAttemptEntity.SQL_REGISTRO_POR_VEREDICTO)
    suspend fun acknowledgeOwnerRow(venueId: String, key: String, now: Long): Int

    @Query("SELECT * FROM pending_payments WHERE venue_id = :venueId AND idempotency_key = :key LIMIT 2")
    suspend fun queuedSalesForAcknowledgement(venueId: String, key: String): List<PendingPaymentEntity>

    @Transaction
    suspend fun acknowledgeOwner(venueId: String, key: String, now: Long): Int {
        val owner = ownerForHandoff(venueId, key) ?: return 0
        val row = queuedSalesForAcknowledgement(venueId, key).singleOrNull()
        val contract = row?.let { owner.checkHandoff(it) }
        if (row?.syncStatus != "SUCCESS" || contract?.proven != true) {
            noteHandoffConflict(venueId, key, "handoff_contrato_contradictorio:${contract?.conflict ?: "unproven"}")
            return 0
        }
        return acknowledgeOwnerRow(venueId, key, now)
    }

    @Transaction
    suspend fun markSynced(id: Long, token: String): Int {
        val row = ownedRow(id, token) ?: return 0
        val changed = markSyncedRow(id, token)
        if (changed == 1) row.idempotencyKey?.let { acknowledgeOwner(row.venueId, it, System.currentTimeMillis()) }
        return changed
    }

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

    /** Efectivo nuevo y legacy del negocio, con la misma semántica de prefijos que QueuedPayment.isCash. */
    @Query("""
        SELECT COUNT(*) FROM pending_payments
        WHERE venue_id = :venueId AND sync_status IN ('PENDING', 'SYNCING')
        AND (UPPER(reference_number) LIKE 'CASH-%' OR UPPER(authorization_number) LIKE 'EFECTIVO%')
    """)
    fun contarVentasGuardadas(venueId: String): kotlinx.coroutines.flow.Flow<Int>

    @Query("""
        SELECT COUNT(*) FROM pending_payments
        WHERE venue_id = :venueId AND (sync_status = 'FAILED' OR requiere_revision = 1) AND acknowledged = 0
    """)
    fun contarConProblema(venueId: String): kotlinx.coroutines.flow.Flow<Int>

    /** Todas las obligaciones del negocio, también legacy y de otros turnos/métodos. No hidrata la cola para cerrar. */
    @Query("""
        SELECT COUNT(*) FROM pending_payments
        WHERE venue_id = :venueId AND (sync_status IN ('PENDING', 'SYNCING')
            OR ((sync_status = 'FAILED' OR requiere_revision = 1) AND acknowledged = 0))
    """)
    suspend fun contarBloqueanCierre(venueId: String): Int

    /** Página acotada; el total se consulta aparte y el id desambigua fechas iguales. */
    @Query("""
        SELECT * FROM pending_payments
        WHERE venue_id = :venueId AND (sync_status IN ('PENDING', 'SYNCING')
            OR ((sync_status = 'FAILED' OR requiere_revision = 1) AND acknowledged = 0))
        ORDER BY created_at ASC, id ASC, id ASC LIMIT 20 OFFSET :offset
    """)
    suspend fun bloqueanCierre(venueId: String, offset: Int): List<PendingPaymentEntity>

    @Query("""SELECT EXISTS(SELECT 1 FROM pending_payments WHERE venue_id = :venueId
        AND (idempotency_key = :key OR reference_number = :reference) LIMIT 1)""")
    suspend fun tieneDueno(venueId: String, key: String?, reference: String): Boolean

    /**
     * 🧾 Tienda híbrida (R78): ¿este aparato cobró esa cuenta y el cobro todavía no sube? Mientras tanto el SERVIDOR la ve «por
     * pagar», y releerla dejaría cobrar el mismo folio OTRA VEZ.
     */
    @Query("""SELECT EXISTS(SELECT 1 FROM pending_payments WHERE venue_id = :venueId AND order_id = :orderId
        AND sync_status IN ('PENDING', 'SYNCING') LIMIT 1)""")
    suspend fun hayCobroSubiendose(venueId: String, orderId: String): Boolean

    @Query("SELECT * FROM pending_payments WHERE id = :id AND venue_id = :venueId LIMIT 1")
    suspend fun leerParaReconocer(id: Long, venueId: String): PendingPaymentEntity?

    /** El mismo UPDATE veta un snapshot viejo: PENDING/SYNCING nunca se pueden dar por revisadas. */
    @Query("""
        UPDATE pending_payments SET acknowledged = 1, acknowledged_by = :staffId, acknowledged_at = :now
        WHERE id = :id AND venue_id = :venueId AND acknowledged = 0
            AND sync_status NOT IN ('PENDING', 'SYNCING') AND (sync_status = 'FAILED' OR requiere_revision = 1)
            AND :staffId IS NOT NULL AND LENGTH(TRIM(:staffId)) > 0
    """)
    suspend fun reconocer(id: Long, venueId: String, staffId: String?, now: Long): Int

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
     * Nunca borra una venta que subió con diferencia de cobro (`requiere_revision`) mientras nadie la marque «Revisado» (v43).
     *
     * @param timestampMs Unix timestamp (e.g., 7 days ago)
     */
    @Query("""
        DELETE FROM pending_payments
        WHERE sync_status = 'SUCCESS'
        AND created_at < :timestampMs
        AND NOT (requiere_revision = 1 AND acknowledged = 0)
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
        SET sync_status = 'PENDING', retry_count = 0, acknowledged = 0, acknowledged_by = NULL, acknowledged_at = NULL
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
        SET sync_status = 'PENDING', retry_count = 0, permanent = 0, acknowledged = 0, acknowledged_by = NULL, acknowledged_at = NULL
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
        normalizarClaimsFuturos(now, staleBefore)
        markClaimed(limit, token, now, staleBefore)
        return getClaimed(token)
    }

    // Una corrección grande del reloj conserva el dueño y le da un lease completo antes del relevo.
    @Query("""
        UPDATE pending_payments SET claimed_at = :now
        WHERE sync_status = 'SYNCING' AND claimed_at > :now + (:now - :staleBefore)
    """)
    suspend fun normalizarClaimsFuturos(now: Long, staleBefore: Long)

    /** Incluye ventas locales: el worker las sube con su pedido antes de registrar el pago. */
    @Query("""
        UPDATE pending_payments
        SET sync_status = 'SYNCING', claim_token = :token, claimed_at = :now,
            acknowledged = 0, acknowledged_by = NULL, acknowledged_at = NULL
        WHERE id IN (
            SELECT id FROM pending_payments
            WHERE (sync_status = 'PENDING'
               OR (sync_status = 'SYNCING' AND claimed_at IS NOT NULL AND claimed_at < :staleBefore))
            ORDER BY created_at ASC, id ASC
            LIMIT :limit
        )
    """)
    suspend fun markClaimed(limit: Int, token: String, now: Long, staleBefore: Long)

    @Query("SELECT * FROM pending_payments WHERE claim_token = :token ORDER BY created_at ASC, id ASC")
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

    /**
     * «Cobrar» sin red: la pantalla de éxito de una venta local llegó a verse. Las filas con `exito_mostrado = 0` al volver a abrir la
     * app avisan que la venta ya quedó cobrada y guardada. Devuelve las filas afectadas (0 = no hay fila con esa llave).
     */
    @Query("UPDATE pending_payments SET exito_mostrado = 1 WHERE idempotency_key = :idempotencyKey")
    suspend fun marcarExitoMostrado(idempotencyKey: String): Int

    /** Orden y evidencia de revision juntas, conservando el claim y cualquier revision previa. */
    @Query("""
        UPDATE pending_payments
        SET order_id = :orderId,
            order_number = :orderNumber,
            acknowledged = CASE WHEN :requiereRevision AND requiere_revision = 0 THEN 0 ELSE acknowledged END,
            acknowledged_by = CASE WHEN :requiereRevision AND requiere_revision = 0 THEN NULL ELSE acknowledged_by END,
            acknowledged_at = CASE WHEN :requiereRevision AND requiere_revision = 0 THEN NULL ELSE acknowledged_at END,
            requiere_revision = CASE WHEN :requiereRevision OR requiere_revision = 1 THEN 1 ELSE 0 END,
            last_error = CASE WHEN :requiereRevision OR requiere_revision = 1
                THEN 'DIFERENCIA_DE_COBRO: revisa los renglones y el importe de la venta guardada'
                ELSE last_error END
        WHERE id = :id AND claim_token = :claimToken
    """)
    suspend fun guardarOrdenDeVentaGuardada(id: Long, claimToken: String, orderId: String, orderNumber: String?, requiereRevision: Boolean): Int

    @Query("""
        SELECT * FROM pending_payments
        WHERE venue_id = :venueId AND exito_mostrado = 0 AND order_request_json IS NOT NULL
        ORDER BY created_at DESC, id DESC LIMIT 1
    """)
    suspend fun ultimaVentaSinMostrar(venueId: String): PendingPaymentEntity?

    /** ¿La venta del carrito ya tiene una fila durable? No restaurarla como si siguiera sin cobrar. */
    @Query("SELECT EXISTS(SELECT 1 FROM pending_payments WHERE idempotency_key = :idempotencyKey)")
    suspend fun existeFilaConLlave(idempotencyKey: String): Boolean
}

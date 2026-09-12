package com.jaac.avoqado_tpv.features.payment.data.ledger

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PaymentAttemptDao {

    @Query("""SELECT * FROM payment_attempts WHERE venue_id = :venueId
        AND processor = 'ANGELPAY' AND state IN ('AUTORIZANDO','INDETERMINADO')
        AND host_approved IS NULL AND created_at < :olderThan AND verify_attempts < 5
        AND (lease_until IS NULL OR lease_until < :now) ORDER BY created_at ASC LIMIT 50""")
    suspend fun getUnknownRecoveryCandidates(venueId: String, olderThan: Long, now: Long): List<PaymentAttemptEntity>

    @Query("""SELECT * FROM payment_attempts WHERE venue_id = :venueId
        AND state IN ('HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO')
        AND (host_approved = 1 OR state = 'AUTORIZADO') AND created_at < :olderThan AND verify_attempts < 5
        AND (lease_until IS NULL OR lease_until < :now) ORDER BY created_at ASC LIMIT 50""")
    suspend fun getApprovalRecoveryCandidates(venueId: String, olderThan: Long, now: Long): List<PaymentAttemptEntity>

    @Query("""UPDATE payment_attempts SET lease_until = :leaseUntil, verify_attempts = verify_attempts + 1
        WHERE attempt_id = :attemptId AND venue_id = :venueId AND host_approved IS NULL
        AND state IN ('AUTORIZANDO','INDETERMINADO')
        AND (lease_until IS NULL OR lease_until < :now)""")
    suspend fun claimUnknownRecovery(attemptId: String, venueId: String, now: Long, leaseUntil: Long): Int

    /**
     * 🔴 La terminal es UNA. Este es el candado de aparato, no de venue ni de orden.
     *
     * Incluye PREPARANDO y KERNEL_ACTIVO a propósito: sin ellos, dos intentos podían
     * reservar a la vez (medido: `expected:<1> but was:<2>`) y un ViewModel recreado
     * entraba al kernel contactless con la reserva anterior viva — dos cobros por un toque.
     *
     * Una fila HEREDADA (`legacy_shadow = 1`, la libreta SHADOW de 2.9.x) no aparta la terminal:
     * en esa versión no reservaba. Sigue visible en los conteos (ver [PaymentAttemptEntity.legacyShadow]).
     */
    @Query("""SELECT * FROM payment_attempts
        WHERE legacy_shadow = 0
        AND state IN ('PREPARANDO','KERNEL_ACTIVO','AUTORIZANDO','INDETERMINADO','HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO')
        LIMIT 1""")
    suspend fun findTerminalHold(): PaymentAttemptEntity?

    /**
     * 🔴 ¿Hay un cobro cuyo desenlace financiero NO se conoce?
     *
     * Hermana de [findTerminalHold] pero NO la misma pregunta, y confundirlas cuesta dinero
     * en las dos direcciones:
     *  - [findTerminalHold] contesta «¿está apartada la terminal?» e incluye PREPARANDO,
     *    porque para no admitir un segundo cobro basta con que alguien esté en la fila.
     *  - Ésta contesta «¿pudo haberse movido dinero?» y deja PREPARANDO FUERA a propósito:
     *    en ese estado, por construcción, ninguna llamada capaz de autorizar empezó, así que
     *    sí es prueba de que no hubo cobro. Meterlo aquí volvería incierta toda cancelación
     *    legítima antes de acercar la tarjeta.
     *
     * Sin filtro de venue: la terminal es UNA. Un cobro sin resolver del aparato importa
     * aunque el turno haya cambiado de sucursal.
     *
     * Excluye las filas HEREDADAS (`legacy_shadow = 1`): son de un APK anterior, ningún ViewModel
     * vivo puede ser su dueño, y adoptarlas como «mi cobro pendiente» (AngelPay recreado) o dejar que
     * tapen para siempre un «no se cobró» legítimo sería un bloqueo que en 2.9.2 no existía. Siguen
     * contadas en [observeUnresolvedCount].
     */
    @Query("""SELECT * FROM payment_attempts
        WHERE legacy_shadow = 0
        AND state IN ('KERNEL_ACTIVO','AUTORIZANDO','INDETERMINADO','HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO')
        ORDER BY updated_at DESC LIMIT 1""")
    suspend fun findUnresolvedCharge(): PaymentAttemptEntity?

    /**
     * Reserva atómica: comprobar-y-insertar en UNA sentencia.
     *
     * 🔴 Un `SELECT` seguido de un `INSERT` separado NO es una reserva: dos hilos leen
     * «libre» y los dos insertan. `INSERT … SELECT … WHERE NOT EXISTS` lo decide SQLite
     * en una sola sentencia, así que exactamente uno gana.
     *
     * Devuelve el rowid insertado, o -1 cuando otro intento ya tiene la terminal
     * (o cuando la orden ya tiene un cobro sin resolver, o cuando la solicitud remota está CERCADA).
     *
     * 🛑 CERCA (C.5 / H.3, 11-sep): ningún intento nuevo de una solicitud remota cuya fila en la bandeja
     * tenga lápida (`NOT_FOUND_ANSWERED`), cancelación aceptada (`cancel_accepted_at`) o un desenlace final
     * ya escrito (`final_emitted_at`). Va DENTRO de esta sentencia, no en una lectura previa: un reintento
     * y un cancel simultáneos no pueden ganar los dos. 🔴 A propósito NO exige «la bandeja está en
     * PROCESSING»: tras un rechazo reintentable la solicitud sigue en PROCESSING y el reintento es legítimo.
     *
     * Las filas HEREDADAS (`legacy_shadow = 1`) no cuentan para reservar: en 2.9.2 no reservaban.
     */
    @Query("""INSERT OR IGNORE INTO payment_attempts (
            attempt_id, venue_id, processor, kind, state, state_version,
            amount_cents, tip_cents, currency, recording_route, context_schema_version,
            payment_context_json, verify_attempts, created_at, updated_at)
        SELECT :attemptId, :venueId, :processor, :kind, 'PREPARANDO', 0,
            :amountCents, :tipCents, 'MXN', :recordingRoute, 1,
            :contextJson, 0, :now, :now
        WHERE NOT EXISTS (
            SELECT 1 FROM payment_attempts hold
            WHERE hold.legacy_shadow = 0
            AND hold.state IN ('PREPARANDO','KERNEL_ACTIVO','AUTORIZANDO','INDETERMINADO','HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO')
        )
        AND (:orderJsonFragment IS NULL OR NOT EXISTS (
            SELECT 1 FROM payment_attempts dup
            WHERE dup.venue_id = :venueId
            AND dup.legacy_shadow = 0
            AND dup.state IN ('PREPARANDO','KERNEL_ACTIVO','AUTORIZANDO','INDETERMINADO','HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO')
            AND instr(dup.payment_context_json, :orderJsonFragment) > 0
        ))
        AND (:terminalPaymentRequestId IS NULL OR NOT EXISTS (
            SELECT 1 FROM remote_payment_requests cerca
            WHERE cerca.request_id = :terminalPaymentRequestId
            AND (cerca.status = 'NOT_FOUND_ANSWERED' OR cerca.cancel_accepted_at IS NOT NULL
                 OR cerca.final_emitted_at IS NOT NULL)
        ))""")
    suspend fun reserveTerminal(
        attemptId: String, venueId: String, processor: String, kind: String,
        amountCents: Long, tipCents: Long, recordingRoute: String,
        contextJson: String, orderJsonFragment: String?, now: Long,
        terminalPaymentRequestId: String?,
    ): Long

    /**
     * Por qué una solicitud remota ya no admite otro intento, o 'LIBRE'. Sólo LEE la bandeja: la usa el
     * ViewModel para traducir un fallo de la cerca a lo que de verdad pasó («El POS canceló este cobro»
     * o «Este cobro ya se cerró»), nunca a «No se pudo guardar el intento». null = la bandeja no la tiene.
     */
    @Query("""SELECT CASE
            WHEN cancel_accepted_at IS NOT NULL THEN 'CANCELADA_POR_EL_POS'
            WHEN status = 'NOT_FOUND_ANSWERED' OR final_emitted_at IS NOT NULL THEN 'CERRADA'
            ELSE 'LIBRE' END
        FROM remote_payment_requests WHERE request_id = :requestId""")
    suspend fun cercaDeSolicitud(requestId: String): String?

    /**
     * C.5 — CAS del EFECTIVO/CRIPTO de un cobro remoto, ANTES de registrar. Gana sólo si la solicitud
     * sigue reclamada (PROCESSING) y nadie la canceló ni la cerró; desde aquí el cancel remoto contesta
     * ACTIVE. Idempotente para la misma solicitud (COALESCE): un segundo arranque del mismo cobro no
     * pierde. Devuelve 1 si puede arrancar, 0 si no.
     */
    @Query("""UPDATE remote_payment_requests
        SET execution_started_at = COALESCE(execution_started_at, :now), updated_at = :now
        WHERE request_id = :requestId AND status = 'PROCESSING'
        AND cancel_accepted_at IS NULL AND final_emitted_at IS NULL""")
    suspend fun iniciarEjecucionNoTarjeta(requestId: String, now: Long): Int

    @Query("""SELECT COUNT(*) FROM payment_attempts WHERE venue_id = :venueId
        AND state IN ('AUTORIZANDO','INDETERMINADO','HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO')""")
    fun observeUnresolvedCount(venueId: String): kotlinx.coroutines.flow.Flow<Int>

    @Query("""SELECT * FROM payment_attempts WHERE venue_id = :venueId
        AND state IN ('AUTORIZANDO','INDETERMINADO','HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO')
        AND instr(payment_context_json, :orderJsonFragment) > 0 LIMIT 1""")
    suspend fun findUnresolvedOrder(venueId: String, orderJsonFragment: String): PaymentAttemptEntity?

    @Query("""UPDATE payment_attempts SET lease_until = :leaseUntil, verify_attempts = verify_attempts + 1
        WHERE attempt_id = :attemptId AND venue_id = :venueId
        AND state IN ('HOST_RESPONDIO', 'AUTORIZADO', 'REGISTRO_FALLIDO')
        AND (lease_until IS NULL OR lease_until < :now)""")
    suspend fun claimRecovery(attemptId: String, venueId: String, now: Long, leaseUntil: Long): Int

    @Query("""UPDATE payment_attempts SET state = :newState, state_version = state_version + 1,
        updated_at = :now, last_error = :error
        WHERE attempt_id = :attemptId AND venue_id = :venueId AND lease_until = :ownedLease
        AND lease_until > :now AND state IN ('HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO')""")
    suspend fun completeRecovery(attemptId: String, venueId: String, ownedLease: Long,
        newState: String, now: Long, error: String?): Int

    @Query("""UPDATE payment_attempts SET state = 'HOST_RESPONDIO', state_version = state_version + 1,
        updated_at = :now, host_approved = 1, reference_number = :reference, auth_code = :authorization,
        lease_until = NULL, verify_attempts = 0
        WHERE attempt_id = :attemptId AND venue_id = :venueId AND lease_until = :ownedLease
        AND lease_until > :now AND host_approved IS NULL AND state IN ('AUTORIZANDO','INDETERMINADO')""")
    suspend fun completeUnknownRecovery(attemptId: String, venueId: String, ownedLease: Long,
        now: Long, reference: String, authorization: String): Int

    /** Returns -1 when the PK already exists (attemptId reuse — double-charge signal, never silent). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(attempt: PaymentAttemptEntity): Long

    @Query("SELECT * FROM payment_attempts WHERE attempt_id = :attemptId")
    suspend fun getById(attemptId: String): PaymentAttemptEntity?

    /**
     * The CAS everything rides on (spec §4.4): a transition only lands if the row
     * is still in one of the expected states. Returns 0 when it didn't match —
     * caller logs and moves on (worker/callback/manual races resolve themselves).
     */
    @Query(
        """UPDATE payment_attempts
           SET state = :newState, state_version = state_version + 1, updated_at = :now
           WHERE attempt_id = :attemptId AND state IN (:expectedStates)
           AND (:newState != 'AUTORIZANDO' OR NOT EXISTS (
               SELECT 1 FROM payment_attempts other WHERE other.attempt_id != :attemptId
               AND other.legacy_shadow = 0
               AND other.state IN ('KERNEL_ACTIVO','AUTORIZANDO','INDETERMINADO','HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO')
           ))"""
    )
    suspend fun casTransition(attemptId: String, expectedStates: List<String>, newState: String, now: Long): Int

    /** CAS + host outcome in one statement — used the instant the host responds. */
    @Query(
        """UPDATE payment_attempts
           SET state = :newState, state_version = state_version + 1, updated_at = :now,
               operation_id = :operationId, reference_number = :referenceNumber,
               auth_code = :authCode, host_approved = :hostApproved,
               lease_until = NULL, verify_attempts = 0
           WHERE attempt_id = :attemptId AND state IN (:expectedStates)"""
    )
    suspend fun casHostResponded(
        attemptId: String, expectedStates: List<String>, newState: String, now: Long,
        operationId: String?, referenceNumber: String?, authCode: String?, hostApproved: Boolean
    ): Int

    /** CAS + card details (available at record time). */
    @Query(
        """UPDATE payment_attempts
           SET state = :newState, state_version = state_version + 1, updated_at = :now,
               masked_pan = :maskedPan, card_brand = :cardBrand, entry_mode = :entryMode
           WHERE attempt_id = :attemptId AND state IN (:expectedStates)"""
    )
    suspend fun casWithCardDetails(
        attemptId: String, expectedStates: List<String>, newState: String, now: Long,
        maskedPan: String?, cardBrand: String?, entryMode: String?
    ): Int

    @Query(
        """UPDATE payment_attempts
           SET state = :newState, state_version = state_version + 1, updated_at = :now, last_error = :error
           WHERE attempt_id = :attemptId AND state IN (:expectedStates)"""
    )
    suspend fun casWithError(attemptId: String, expectedStates: List<String>, newState: String, now: Long, error: String?): Int

    // ── Sweep / shadow observability (ALWAYS venue-scoped — tenant isolation) ──

    @Query("SELECT * FROM payment_attempts WHERE venue_id = :venueId AND state IN (:states) AND created_at < :olderThan ORDER BY created_at ASC LIMIT 50")
    suspend fun getOpenOlderThan(venueId: String, states: List<String>, olderThan: Long): List<PaymentAttemptEntity>

    /** AUTORIZANDO stuck past the threshold = process died mid-auth → quarantine (spec §4.5). */
    @Query(
        """UPDATE payment_attempts
           SET state = 'INDETERMINADO', state_version = state_version + 1, updated_at = :now
           WHERE venue_id = :venueId AND state = 'AUTORIZANDO' AND updated_at < :olderThan"""
    )
    suspend fun quarantineStaleAuthorizing(venueId: String, olderThan: Long, now: Long): Int

    /**
     * 🔴 KERNEL_ACTIVO colgado = el proceso murió DENTRO de una llamada capaz de aprobar.
     * Va a INDETERMINADO, jamás a DESCARTADA: el dinero pudo moverse.
     */
    @Query(
        """UPDATE payment_attempts
           SET state = 'INDETERMINADO', state_version = state_version + 1, updated_at = :now
           WHERE venue_id = :venueId AND state = 'KERNEL_ACTIVO' AND updated_at < :olderThan"""
    )
    suspend fun quarantineStaleKernel(venueId: String, olderThan: Long, now: Long): Int

    /**
     * 🔴 Y su contrapeso, que es de DISPONIBILIDAD, no de dinero: ahora que PREPARANDO
     * retiene la terminal, una fila huérfana (proceso muerto entre la reserva y el kernel)
     * dejaría la caja sin poder cobrar para siempre. PREPARANDO significa, por construcción,
     * que ninguna llamada capaz de autorizar empezó — así que liberarla es seguro y es lo
     * ÚNICO que puede liberarse por tiempo.
     *
     * 🔴 NUNCA una fila HEREDADA (`legacy_shadow = 1`): en 2.9.2 el contactless entraba al kernel con
     * la fila en PREPARANDO, así que ese PREPARANDO no prueba que no hubo cobro. Tampoco reserva la
     * terminal, así que dejarla quieta no quita disponibilidad: sólo queda como obligación que conciliar.
     */
    @Query(
        """UPDATE payment_attempts
           SET state = 'DESCARTADA', state_version = state_version + 1, updated_at = :now,
               last_error = 'stale_preparing_released'
           WHERE venue_id = :venueId AND state = 'PREPARANDO' AND legacy_shadow = 0 AND updated_at < :olderThan"""
    )
    suspend fun discardStalePreparing(venueId: String, olderThan: Long, now: Long): Int

    /** Happy-path rows close silently after a day (spec §4.3). */
    @Query(
        """UPDATE payment_attempts
           SET state = 'CERRADA', state_version = state_version + 1, updated_at = :now
           WHERE venue_id = :venueId AND state = 'REGISTRADO' AND updated_at < :olderThan"""
    )
    suspend fun closeRecordedOlderThan(venueId: String, olderThan: Long, now: Long): Int

    /** Prune terminal rows at ~7 days (mirror of deleteOldSyncedPayments). INDETERMINADO is NEVER deleted. */
    @Query("DELETE FROM payment_attempts WHERE venue_id = :venueId AND state IN ('CERRADA','DESCARTADA') AND updated_at < :olderThan")
    suspend fun pruneTerminalOlderThan(venueId: String, olderThan: Long): Int
}

package com.jaac.avoqado_tpv.features.shift.domain

import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity

/** Una página de dueños contables, no una segunda libreta ni otro estado financiero. */
data class LocalShiftObligation(val owner: String, val ownerId: String, val createdAt: Long,
    val membership: String, val localSessionId: String?)

data class LocalShiftObligationPage(val total: Int, val rows: List<LocalShiftObligation>, val offset: Int)

/** Predicado único para conteo, página y reconocimiento: B sólo se excluye con sesión demostrada. */
internal const val SQL_SHIFT_OBLIGATIONS = """
SELECT owner, ownerId, createdAt, membership, localSessionId FROM (
  SELECT owner, ownerId, createdAt, localSessionId, venue,
    CASE WHEN membership = 'MANAGED' AND localSessionId IS NOT NULL AND EXISTS (
        SELECT 1 FROM local_shift_sessions s WHERE s.venue_id = venue AND s.local_session_id = localSessionId
          AND (drawerId IS NULL OR drawerId = s.cash_drawer_session_id)
          AND (remoteShiftId IS NULL OR remoteShiftId = s.server_shift_id)
    ) THEN 'MANAGED'
    WHEN membership = 'NO_SHIFT_SYSTEM' AND localSessionId IS NULL AND drawerId IS NULL AND remoteShiftId IS NULL THEN 'NO_SHIFT_SYSTEM'
    ELSE 'UNKNOWN' END membership
  FROM (
    SELECT 'ATTEMPT' owner, attempt_id ownerId, created_at createdAt, shift_membership membership,
        local_shift_session_id localSessionId, cash_drawer_session_id drawerId, NULL remoteShiftId, venue_id venue FROM payment_attempts
    WHERE state IN ('PREPARANDO','KERNEL_ACTIVO','AUTORIZANDO','HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO','INDETERMINADO')
       OR (state = 'DESCARTADA' AND (host_approved = 1 OR server_processor_evidence = 'APPROVED' OR server_veto IS NOT NULL))
       OR (state = 'ENTREGADA_A_COLA' AND (""" + PaymentAttemptEntity.SQL_REGISTRO_POR_VEREDICTO + """ OR instr(COALESCE(last_error,''), 'handoff_contrato_contradictorio') > 0 OR (NOT EXISTS (SELECT 1 FROM pending_payments p WHERE p.venue_id = payment_attempts.venue_id AND p.idempotency_key = payment_attempts.attempt_id)
           AND NOT EXISTS (SELECT 1 FROM pending_refunds r WHERE r.venue_id = payment_attempts.venue_id AND r.idempotency_key = payment_attempts.attempt_id))))
    UNION ALL
    SELECT 'PAYMENT', CAST(id AS TEXT), created_at, shift_membership, local_shift_session_id, cash_drawer_session_id, shift_id, venue_id FROM pending_payments
    WHERE sync_status IN ('PENDING','SYNCING') OR ((sync_status = 'FAILED' OR requiere_revision = 1) AND (acknowledged = 0 OR id = :recognizedPaymentId))
    UNION ALL
    SELECT 'REFUND', idempotency_key, created_at, shift_membership, local_shift_session_id, cash_drawer_session_id, NULL, venue_id FROM pending_refunds
    WHERE sync_status IN ('PENDING','SYNCING') OR (sync_status = 'FAILED' AND acknowledged = 0)
    UNION ALL
    SELECT 'REMOTE', request_id, created_at, shift_membership, local_shift_session_id, cash_drawer_session_id, NULL, venue_id FROM remote_payment_requests r
    WHERE status = 'PROCESSING'
      AND NOT EXISTS (SELECT 1 FROM payment_attempts a WHERE a.venue_id = r.venue_id AND a.terminal_payment_request_id = r.request_id AND (a.state != 'DESCARTADA' OR a.host_approved = 1 OR a.server_processor_evidence = 'APPROVED' OR a.server_veto IS NOT NULL))
      AND NOT EXISTS (SELECT 1 FROM pending_payments p WHERE p.venue_id = r.venue_id AND p.terminal_payment_request_id = r.request_id)
      AND NOT EXISTS (SELECT 1 FROM local_crypto_operations c WHERE c.venue_id = r.venue_id AND c.terminal_payment_request_id = r.request_id)
    UNION ALL
    SELECT 'TABLE_CASH', id, created_at, shift_membership, local_shift_session_id, cash_drawer_session_id, NULL, venue_id FROM sync_intents
    WHERE type = 'PAY_CASH' AND status IN ('PENDING','REJECTED')
    UNION ALL
    SELECT 'CRYPTO', idempotency_key, created_at, shift_membership, local_shift_session_id, cash_drawer_session_id, server_shift_id, venue_id FROM local_crypto_operations
    WHERE financial_recorded IS NOT 1 OR financial_status IS NOT 'COMPLETED'
  ) rawObligation
) obligation WHERE venue = :venueId
    AND (membership != 'MANAGED' OR localSessionId = :sessionId OR EXISTS (
        SELECT 1 FROM local_shift_sessions source JOIN local_shift_sessions target ON target.venue_id = source.venue_id
        WHERE source.venue_id = obligation.venue AND source.local_session_id = obligation.localSessionId
          AND target.local_session_id = :sessionId AND source.server_shift_id IS NOT NULL AND target.server_shift_id IS NOT NULL
          AND source.server_shift_id = target.server_shift_id
          AND (source.cash_drawer_session_id IS NULL OR target.cash_drawer_session_id IS NULL
              OR source.cash_drawer_session_id = target.cash_drawer_session_id)
    ))
"""

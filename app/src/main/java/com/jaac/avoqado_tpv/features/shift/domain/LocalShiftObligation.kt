package com.jaac.avoqado_tpv.features.shift.domain

import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity

/** Una página de dueños contables, no una segunda libreta ni otro estado financiero. */
data class LocalShiftObligation(val owner: String, val ownerId: String, val createdAt: Long,
    val membership: String, val localSessionId: String?)

data class LocalShiftObligationPage(val total: Int, val rows: List<LocalShiftObligation>, val offset: Int)

/**
 * 🔒 D3: lo que lee el cajero cuando el cierre sigue sin poder guardarse: qué falta y qué hacer. [sinRespuesta] = Avoqado no contestó
 * al preguntarle ([com.jaac.avoqado_tpv.features.shift.data.repository.ConsultaDelCierreDeCaja]): sin conexión se dice con todas
 * sus letras, y entonces lo único por hacer es recuperarla, porque la consulta se repite al volver a cerrar. Nunca dice «cobro»: lo
 * que está en duda también puede ser una devolución, porque la rama ATTEMPT de la obligación no filtra por tipo (M-1).
 */
internal fun textoDelCierreBloqueado(operaciones: Int, delPos: Int, sinRespuesta: Boolean): String {
    fun cuenta(cuantas: Int, cuales: String) = when (cuantas) {
        0 -> null
        1 -> "1 operación $cuales"
        else -> "$cuantas operaciones $cuales"
    }
    val falta = listOfNotNull(cuenta(operaciones, "con tarjeta sin confirmar"), cuenta(delPos, "del POS sin terminar")).joinToString(" y ")
    val varias = operaciones + delPos > 1
    val noRepetir = if (varias) "No las repitas." else "No la repitas."
    // D20 (founder, 17-nov: «lista»): lo que el aviso verde ya no nombra se declara en Pagos › «Por revisar»; soporte queda al final.
    // Sólo si hay operaciones con tarjeta: las del POS no salen ahí. «Búscala… si no aparece» (Codex r1 #2): aquí también cuentan una
    // devolución, un aprobado del banco o algo en curso, que «Por revisar» no ofrece declarar. Y sin «cobro» ni «venta» (M-1).
    val queHacer = when {
        sinRespuesta -> "Sin conexión con Avoqado no se puede confirmar qué pasó. $noRepetir Revisa el internet de la terminal y vuelve a cerrar."
        varias && operaciones > 0 -> "Las que vinieron del POS se responden ahí; las demás, búscalas en Pagos › «Por revisar». Si no aparecen ahí o siguen igual, llama a soporte. $noRepetir Después vuelve a cerrar."
        varias -> "Las que vinieron del POS se responden ahí; para las demás, o si siguen igual, llama a soporte. $noRepetir Después vuelve a cerrar."
        operaciones > 0 -> "Si vino del POS, responde ahí qué pasó; si no, búscala en Pagos › «Por revisar». Si no aparece ahí o sigue igual, llama a soporte. $noRepetir Después vuelve a cerrar."
        else -> "Si vino del POS, responde ahí qué pasó; si no, o si sigue igual, llama a soporte. $noRepetir Después vuelve a cerrar."
    }
    return "No se puede cerrar la caja: hay $falta.\n\n$queHacer"
}

/**
 * Predicado único para conteo, página y reconocimiento: B sólo se excluye con sesión demostrada.
 *
 * REMOTE (D12, Codex final-r1): una solicitud PROCESSING ya CERRADA PARA EJECUCIÓN (`execution_closed_at`) no puede arrancar nada,
 * así que no es una ejecución pendiente. Lo que tenga dinero o siga en duda lo cuenta la rama ATTEMPT (que no cambia) y lo demás
 * lo resuelve la venta en el SERVIDOR. Sin esto, la declaración «no se cobró» —que descarta el intento y deja la solicitud
 * PROCESSING con su cerca— bloqueaba el cierre del turno. Es una regla de CAJA: el aviso de pendientes
 * (`RemotePaymentRequestDao.observePendingObligations`) cuenta otra cosa (filas SIN ningún intento) y no cambia.
 */
/**
 * 🔴 F1 bloque 5 (I5): la parte de TARJETA de [SQL_SHIFT_OBLIGATIONS] — los intentos que el cierre de caja cuenta. UNA sola regla para
 * contar (Caja) y listar («Por revisar», [com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptDao.observarCobrosDeclarables]).
 * Sin alias: va en un `FROM payment_attempts` y sus subconsultas nombran `payment_attempts.` para la fila de afuera.
 */
const val SQL_INTENTO_QUE_CUENTA_CAJA = "(state IN ('PREPARANDO','KERNEL_ACTIVO','AUTORIZANDO','HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO','INDETERMINADO') " +
    "OR (state = 'DESCARTADA' AND (host_approved = 1 OR server_processor_evidence = 'APPROVED' OR server_veto IS NOT NULL)) " +
    "OR (state = 'ENTREGADA_A_COLA' AND (" + PaymentAttemptEntity.SQL_REGISTRO_POR_VEREDICTO +
    " OR instr(COALESCE(last_error,''), 'handoff_contrato_contradictorio') > 0 OR (NOT EXISTS (SELECT 1 FROM pending_payments p " +
    "WHERE p.venue_id = payment_attempts.venue_id AND p.idempotency_key = payment_attempts.attempt_id) " +
    "AND NOT EXISTS (SELECT 1 FROM pending_refunds r WHERE r.venue_id = payment_attempts.venue_id AND r.idempotency_key = payment_attempts.attempt_id)))))"

/**
 * 🔴 Codex final r1 #8: la parte REMOTE de [SQL_SHIFT_OBLIGATIONS] — la solicitud del POS que el cierre de caja sigue esperando: en
 * proceso, sin cerrar para ejecución y sin un intento, pago o cripto que responda por ella (un intento DESCARTADO sin dinero ya no
 * responde). UNA sola regla para contar (Caja) y listar («Por revisar»). Usa el alias `r` de `remote_payment_requests`.
 */
const val SQL_SOLICITUD_QUE_CUENTA_CAJA = "(r.status = 'PROCESSING' AND r.execution_closed_at IS NULL " +
    "AND NOT EXISTS (SELECT 1 FROM payment_attempts a WHERE a.venue_id = r.venue_id AND a.terminal_payment_request_id = r.request_id " +
    "AND (a.state != 'DESCARTADA' OR a.host_approved = 1 OR a.server_processor_evidence = 'APPROVED' OR a.server_veto IS NOT NULL)) " +
    "AND NOT EXISTS (SELECT 1 FROM pending_payments p WHERE p.venue_id = r.venue_id AND p.terminal_payment_request_id = r.request_id) " +
    "AND NOT EXISTS (SELECT 1 FROM local_crypto_operations c WHERE c.venue_id = r.venue_id AND c.terminal_payment_request_id = r.request_id))"

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
    WHERE """ + SQL_INTENTO_QUE_CUENTA_CAJA + """
    UNION ALL
    SELECT 'PAYMENT', CAST(id AS TEXT), created_at, shift_membership, local_shift_session_id, cash_drawer_session_id, shift_id, venue_id FROM pending_payments
    WHERE sync_status IN ('PENDING','SYNCING') OR ((sync_status = 'FAILED' OR requiere_revision = 1) AND (acknowledged = 0 OR id = :recognizedPaymentId))
    UNION ALL
    SELECT 'REFUND', idempotency_key, created_at, shift_membership, local_shift_session_id, cash_drawer_session_id, NULL, venue_id FROM pending_refunds
    WHERE sync_status IN ('PENDING','SYNCING') OR (sync_status = 'FAILED' AND acknowledged = 0)
    UNION ALL
    SELECT 'REMOTE', request_id, created_at, shift_membership, local_shift_session_id, cash_drawer_session_id, NULL, venue_id FROM remote_payment_requests r
    WHERE """ + SQL_SOLICITUD_QUE_CUENTA_CAJA + """
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

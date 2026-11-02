package com.jaac.avoqado_tpv.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.jaac.avoqado_tpv.core.data.local.entity.LocalShiftSessionEntity

@Dao
interface LocalShiftSessionDao {
    /** Observación remota: cierra admisión, sin inventar gesto CLOSE/conteo ni tocar captura. */
    @Query("""UPDATE local_shift_sessions SET local_status = 'CLOSED', open_slot = NULL,
        close_status = 'REVIEW', last_error = :reason, updated_at = :now
        WHERE venue_id = :venueId AND local_session_id = :id AND server_shift_id = :remoteId
        AND local_status = 'OPEN' AND opening_status = 'CONFIRMED' AND close_operation_id IS NULL""")
    suspend fun observarCierreRemoto(venueId: String, id: String, remoteId: String, reason: String, now: Long): Int

    @Query("SELECT * FROM local_shift_sessions WHERE venue_id = :venueId AND server_shift_id = :serverId ORDER BY created_at DESC, local_session_id DESC LIMIT 1")
    suspend fun porServidor(venueId: String, serverId: String): LocalShiftSessionEntity?
    @Query("UPDATE local_shift_sessions SET last_error = :message, updated_at = :now WHERE venue_id = :venueId AND local_session_id = :id")
    suspend fun anotarError(venueId: String, id: String, message: String, now: Long): Int

    @Query("""SELECT * FROM local_shift_sessions WHERE venue_id = :venueId
        AND (opening_status IN ('PENDING','SYNCING') OR close_status IN ('PENDING','SYNCING'))
        ORDER BY created_at, local_session_id LIMIT 20""")
    suspend fun porReproducir(venueId: String): List<LocalShiftSessionEntity>

    @Query("""SELECT COUNT(*) FROM local_shift_sessions a WHERE a.venue_id = :venueId
        AND (a.created_at < :createdAt OR (a.created_at = :createdAt AND a.local_session_id < :id))
        AND a.opening_status != 'REVIEW' AND a.close_status NOT IN ('CONFIRMED','REVIEW')
        AND NOT (a.opening_disposition = 'ADOPTED' AND a.adoption_accepted_at IS NOT NULL
            AND a.server_shift_id IS NOT NULL AND a.cash_drawer_session_id IS NOT NULL AND EXISTS (
                SELECT 1 FROM local_shift_sessions b WHERE b.venue_id = a.venue_id AND b.opening_kind = 'REMOTE'
                AND b.created_at = a.adoption_accepted_at AND b.server_shift_id = a.server_shift_id
                AND b.cash_drawer_session_id = a.cash_drawer_session_id))""")
    suspend fun anterioresSinResolver(venueId: String, createdAt: Long, id: String): Int

    @Query("""UPDATE local_shift_sessions SET claim_token = :token, claimed_at = :now, lease_until = :until,
        opening_status = CASE WHEN :opening THEN 'SYNCING' ELSE opening_status END,
        close_status = CASE WHEN :opening THEN close_status ELSE 'SYNCING' END, updated_at = :now
        WHERE venue_id = :venueId AND local_session_id = :id AND (claim_token IS NULL OR lease_until <= :now)
        AND ((:opening AND opening_status IN ('PENDING','SYNCING')) OR
            (NOT :opening AND opening_status = 'CONFIRMED' AND local_status = 'CLOSED' AND close_status IN ('PENDING','SYNCING')))""")
    suspend fun reclamar(venueId: String, id: String, token: String, now: Long, until: Long, opening: Boolean): Int

    @Query("""UPDATE local_shift_sessions SET opening_status = CASE WHEN opening_status = 'SYNCING' THEN :status ELSE opening_status END,
        close_status = CASE WHEN close_status = 'SYNCING' THEN :status ELSE close_status END,
        claim_token = NULL, claimed_at = NULL, lease_until = NULL, last_error = :error, updated_at = :now
        WHERE venue_id = :venueId AND local_session_id = :id AND claim_token = :token""")
    suspend fun liberar(venueId: String, id: String, token: String, status: String, error: String?, now: Long): Int

    @Query("""UPDATE local_shift_sessions SET close_wire_request_json = :wire WHERE venue_id = :venueId
        AND local_session_id = :id AND claim_token = :token AND local_status = 'CLOSED' AND close_status = 'SYNCING'
        AND close_request_json = :original AND (close_wire_request_json IS NULL OR close_wire_request_json = :wire)""")
    suspend fun fijarCierreDeEnvio(venueId: String, id: String, token: String, original: String, wire: String): Int

    @Query("""UPDATE local_shift_sessions SET close_status = :status, close_evidence_json = :evidence,
        updated_at = :now WHERE venue_id = :venueId AND local_session_id = :id AND claim_token = :token
        AND close_operation_id = :operationId AND close_status = 'SYNCING'""")
    suspend fun confirmarCierre(venueId: String, id: String, token: String, operationId: String, status: String, evidence: String, now: Long): Int
    @Insert suspend fun insertar(row: LocalShiftSessionEntity)

    @Query("SELECT * FROM local_shift_sessions WHERE venue_id = :venueId AND local_session_id = :id LIMIT 1")
    suspend fun leer(venueId: String, id: String): LocalShiftSessionEntity?

    @Query("SELECT * FROM local_shift_sessions WHERE venue_id = :venueId AND local_status = 'OPEN' ORDER BY created_at, local_session_id LIMIT 1")
    suspend fun abierta(venueId: String): LocalShiftSessionEntity?

    @Query("SELECT * FROM local_shift_sessions WHERE venue_id = :venueId ORDER BY created_at DESC, local_session_id DESC LIMIT 1")
    suspend fun ultima(venueId: String): LocalShiftSessionEntity?

    @Query("SELECT COUNT(*) FROM local_shift_sessions WHERE venue_id = :venueId")
    suspend fun contar(venueId: String): Int

    @Query("SELECT * FROM local_shift_sessions WHERE venue_id = :venueId ORDER BY created_at, local_session_id LIMIT 20 OFFSET :offset")
    suspend fun pagina(venueId: String, offset: Int): List<LocalShiftSessionEntity>

    @Query("""UPDATE local_shift_sessions SET local_status = 'CLOSED', open_slot = NULL, close_status = :status,
        close_operation_id = :operationId, close_request_json = :requestJson, closed_at_local = :closedAt,
        counted_cash = :countedCash, cash_reconciliation_action = :action, closed_by_staff_id = :staffId, updated_at = :now
        WHERE venue_id = :venueId AND local_session_id = :id AND local_status = 'OPEN' AND close_operation_id IS NULL""")
    suspend fun cerrar(venueId: String, id: String, operationId: String, requestJson: String, closedAt: String,
        countedCash: String?, action: String?, staffId: String, now: Long, status: String): Int

    /** La reserva/cierre y el POST usan la misma pertenencia probada; no es SQL_APARTA_EL_APARATO. */
    @Query("SELECT COUNT(*) FROM (" + com.jaac.avoqado_tpv.features.shift.domain.SQL_SHIFT_OBLIGATIONS + ") WHERE owner = 'ATTEMPT'")
    suspend fun intentosSinRepresentar(venueId: String, sessionId: String, recognizedPaymentId: Long? = null): Int

    @Query("SELECT COUNT(*) FROM (" + com.jaac.avoqado_tpv.features.shift.domain.SQL_SHIFT_OBLIGATIONS + ") WHERE owner = 'REMOTE'")
    suspend fun ejecucionesRemotas(venueId: String, sessionId: String, recognizedPaymentId: Long? = null): Int

    @Query("SELECT COUNT(*) FROM (" + com.jaac.avoqado_tpv.features.shift.domain.SQL_SHIFT_OBLIGATIONS + ")")
    suspend fun contarObligaciones(venueId: String, sessionId: String, recognizedPaymentId: Long? = null): Int

    @Query(com.jaac.avoqado_tpv.features.shift.domain.SQL_SHIFT_OBLIGATIONS + " ORDER BY createdAt, owner, ownerId LIMIT 20 OFFSET :offset")
    suspend fun paginaObligaciones(venueId: String, sessionId: String, offset: Int, recognizedPaymentId: Long? = null): List<com.jaac.avoqado_tpv.features.shift.domain.LocalShiftObligation>

    @Query("SELECT COUNT(*) FROM (" + com.jaac.avoqado_tpv.features.shift.domain.SQL_SHIFT_OBLIGATIONS + ") WHERE owner = :owner AND ownerId = :ownerId")
    suspend fun perteneceAlCierre(venueId: String, sessionId: String, owner: String, ownerId: String, recognizedPaymentId: Long? = null): Int

    @Query("""UPDATE local_shift_sessions SET server_shift_id = :serverShiftId, cash_drawer_session_id = :drawerId,
        opening_status = 'CONFIRMED', opening_disposition = :disposition, remote_starting_cash = :actualCash,
        opening_evidence_json = :evidenceJson, adoption_notice = :notice,
        close_status = CASE WHEN :disposition = 'ADOPTED' AND close_operation_id IS NOT NULL THEN 'REVIEW' ELSE close_status END,
        updated_at = :now WHERE venue_id = :venueId AND local_session_id = :id AND opening_kind = 'LOCAL'
        AND opening_status IN ('PENDING','SYNCING') AND opening_operation_id = :id
        AND (server_shift_id IS NULL OR server_shift_id = :serverShiftId)
        AND (cash_drawer_session_id IS NULL OR cash_drawer_session_id = :drawerId) AND claim_token IS :expectedClaimToken""")
    suspend fun confirmarApertura(venueId: String, id: String, serverShiftId: String, drawerId: String, disposition: String,
        actualCash: String, evidenceJson: String, notice: String?, now: Long, expectedClaimToken: String?): Int

    @Query("""UPDATE local_shift_sessions SET local_status = 'CLOSED', open_slot = NULL, adoption_accepted_at = :now,
        updated_at = :now WHERE venue_id = :venueId AND local_session_id = :id AND opening_disposition = 'ADOPTED'
        AND opening_status = 'CONFIRMED' AND server_shift_id = :serverShiftId AND cash_drawer_session_id = :drawerId
        AND adoption_accepted_at IS NULL""")
    suspend fun aceptarAdopcion(venueId: String, id: String, serverShiftId: String, drawerId: String, now: Long): Int
}

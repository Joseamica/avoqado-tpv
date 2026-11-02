package com.jaac.avoqado_tpv.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Historial durable de gestos. Nunca se limpia con la sesión de autenticación ni con cached_shift. */
@Entity(tableName = "local_shift_sessions", indices = [
    Index(value = ["venue_id", "open_slot"], unique = true),
    Index(value = ["venue_id", "local_status", "created_at", "local_session_id"]),
    Index(value = ["venue_id", "opening_status", "created_at", "local_session_id"]),
    Index(value = ["venue_id", "close_status", "created_at", "local_session_id"]),
    Index(value = ["venue_id", "server_shift_id"]),
])
data class LocalShiftSessionEntity(
    @PrimaryKey @ColumnInfo(name = "local_session_id") val localSessionId: String,
    @ColumnInfo(name = "venue_id") val venueId: String,
    @ColumnInfo(name = "opening_kind") val openingKind: String,
    @ColumnInfo(name = "server_shift_id") val serverShiftId: String? = null,
    @ColumnInfo(name = "cash_drawer_session_id") val cashDrawerSessionId: String? = null,
    @ColumnInfo(name = "opening_operation_id") val openingOperationId: String? = null,
    @ColumnInfo(name = "opening_request_json") val openingRequestJson: String? = null,
    @ColumnInfo(name = "opening_status") val openingStatus: String,
    @ColumnInfo(name = "original_opened_at") val originalOpenedAt: String,
    @ColumnInfo(name = "starting_cash") val startingCash: String?,
    @ColumnInfo(name = "opened_by_staff_id") val openedByStaffId: String,
    @ColumnInfo(name = "close_operation_id") val closeOperationId: String? = null,
    /** Petición local canónica; no contiene un shiftId provisional. T2 resuelve la pareja por CAS. */
    @ColumnInfo(name = "close_request_json") val closeRequestJson: String? = null,
    @ColumnInfo(name = "close_wire_request_json") val closeWireRequestJson: String? = null,
    @ColumnInfo(name = "closed_at_local") val closedAtLocal: String? = null,
    @ColumnInfo(name = "counted_cash") val countedCash: String? = null,
    @ColumnInfo(name = "cash_reconciliation_action") val cashReconciliationAction: String? = null,
    @ColumnInfo(name = "closed_by_staff_id") val closedByStaffId: String? = null,
    @ColumnInfo(name = "local_status") val localStatus: String,
    /** Sólo OPEN usa 1; CLOSED usa NULL. UNIQUE(venue,slot) admite cualquier cantidad de cierres. */
    @ColumnInfo(name = "open_slot") val openSlot: Int? = 1,
    @ColumnInfo(name = "close_status") val closeStatus: String = "NONE",
    @ColumnInfo(name = "claim_token") val claimToken: String? = null,
    @ColumnInfo(name = "claimed_at") val claimedAt: Long? = null,
    @ColumnInfo(name = "lease_until") val leaseUntil: Long? = null,
    @ColumnInfo(name = "opening_evidence_json") val openingEvidenceJson: String? = null,
    @ColumnInfo(name = "close_evidence_json") val closeEvidenceJson: String? = null,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "opening_disposition") val openingDisposition: String? = null,
    @ColumnInfo(name = "remote_starting_cash") val remoteStartingCash: String? = null,
    @ColumnInfo(name = "adoption_notice") val adoptionNotice: String? = null,
    @ColumnInfo(name = "adoption_accepted_at") val adoptionAcceptedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

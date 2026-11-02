package com.jaac.avoqado_tpv.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Dueño específico del inicio cripto; emitir un QR no acredita dinero registrado. Sin credenciales/processorData. */
@Entity(tableName = "local_crypto_operations", indices = [
    Index(value = ["venue_id", "local_shift_session_id", "created_at", "idempotency_key"]),
    Index(value = ["venue_id", "emission_status", "created_at", "idempotency_key"]),
    Index(value = ["venue_id", "request_id"]),
    Index(value = ["venue_id", "terminal_payment_request_id"]),
])
data class LocalCryptoOperationEntity(
    @PrimaryKey @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    @ColumnInfo(name = "venue_id") val venueId: String,
    @ColumnInfo(name = "staff_id") val staffId: String,
    @ColumnInfo(name = "request_json") val requestJson: String,
    @ColumnInfo(name = "request_id") val requestId: String? = null,
    @ColumnInfo(name = "terminal_payment_request_id") val terminalPaymentRequestId: String? = null,
    @ColumnInfo(name = "shift_membership", defaultValue = "'UNKNOWN'") val shiftMembership: String = "UNKNOWN",
    @ColumnInfo(name = "local_shift_session_id") val localShiftSessionId: String? = null,
    @ColumnInfo(name = "cash_drawer_session_id") val cashDrawerSessionId: String? = null,
    @ColumnInfo(name = "server_shift_id") val serverShiftId: String? = null,
    @ColumnInfo(name = "emission_status") val emissionStatus: String = "PREPARED",
    @ColumnInfo(name = "financial_status") val financialStatus: String? = null,
    @ColumnInfo(name = "financial_recorded") val financialRecorded: Boolean? = null,
    @ColumnInfo(name = "operation_result_json") val operationResultJson: String? = null,
    @ColumnInfo(name = "claim_token") val claimToken: String? = null,
    @ColumnInfo(name = "claimed_at") val claimedAt: Long? = null,
    @ColumnInfo(name = "lease_until") val leaseUntil: Long? = null,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

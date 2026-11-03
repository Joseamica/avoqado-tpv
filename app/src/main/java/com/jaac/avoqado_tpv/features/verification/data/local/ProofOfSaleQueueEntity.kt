package com.jaac.avoqado_tpv.features.verification.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Cola durable de la foto de comprobante y del número de vinculación (PlayTelecom, v45).
 * Cada foto o corrección es una fila NUEVA; la PK es la `idempotency_key`.
 */
@Entity(
    tableName = "proof_of_sale_queue",
    indices = [Index("venue_id"), Index("payment_id"), Index("sync_status"), Index("claim_token")],
)
data class ProofOfSaleQueueEntity(
    @PrimaryKey @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    val kind: String, // PHOTO | NUMBER_ONLY
    @ColumnInfo(name = "venue_id") val venueId: String,
    @ColumnInfo(name = "staff_id") val staffId: String,
    @ColumnInfo(name = "payment_id") val paymentId: String,
    @ColumnInfo(name = "verification_id") val verificationId: String?,
    val slot: String, // VINCULACION | PORTABILIDAD
    @ColumnInfo(name = "local_path") val localPath: String?,
    @ColumnInfo(name = "linking_number") val linkingNumber: String?,
    @ColumnInfo(name = "ported_number") val portedNumber: String?,
    @ColumnInfo(name = "captured_at") val capturedAt: Long,
    @ColumnInfo(name = "capture_seq") val captureSeq: Long,
    @ColumnInfo(name = "capture_device_id") val captureDeviceId: String,
    @ColumnInfo(name = "sync_status") val syncStatus: String = "PENDING", // PENDING | SYNCING | SUCCESS | FAILED | NEEDS_ATTENTION
    @ColumnInfo(name = "claim_token") val claimToken: String? = null,
    @ColumnInfo(name = "claimed_at") val claimedAt: Long? = null,
    @ColumnInfo(name = "retry_count") val retryCount: Int = 0,
    @ColumnInfo(name = "permanent", defaultValue = "0") val permanent: Boolean = false,
    @ColumnInfo(name = "outcome") val outcome: String? = null, // SALE_ALREADY_APPROVED | SALE_REJECTED | NUMBER_DISABLED | PERMANENT
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

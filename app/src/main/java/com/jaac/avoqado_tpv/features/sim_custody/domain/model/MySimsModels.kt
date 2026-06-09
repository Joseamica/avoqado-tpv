package com.jaac.avoqado_tpv.features.sim_custody.domain.model

import java.math.BigDecimal
import java.time.Instant

/**
 * Domain models for the "Mis SIMs" flow (plan §3.1).
 *
 * Mirrors the backend state machine: only PROMOTER_PENDING | PROMOTER_HELD |
 * SOLD reach the TPV — PROMOTER_REJECTED SIMs are already back with the
 * Supervisor and must not be surfaced to the Promoter.
 */
enum class SimCustodyState {
    PROMOTER_PENDING,
    PROMOTER_HELD,
    SOLD,
    UNKNOWN;

    companion object {
        fun fromWire(raw: String?): SimCustodyState = when (raw) {
            "PROMOTER_PENDING" -> PROMOTER_PENDING
            "PROMOTER_HELD" -> PROMOTER_HELD
            "SOLD" -> SOLD
            else -> UNKNOWN
        }
    }
}

/**
 * Back-office documentation review status for a SOLD SIM's proof-of-sale, mirrored
 * from backend `SaleVerificationStatus`. Lets "Mis SIMs" show the same state the
 * dashboard shows ("Revisar" / "En revisión"). NONE = no verification (or legacy
 * backend) → the SIM keeps its plain "Vendido" badge.
 *
 * PROCESSING is folded into PENDING — both mean "not yet reviewed" for the badge.
 */
enum class SimVerificationStatus {
    NONE,
    PENDING,
    COMPLETED,
    FAILED;

    companion object {
        fun fromWire(raw: String?): SimVerificationStatus = when (raw) {
            "PENDING", "PROCESSING" -> PENDING
            "COMPLETED" -> COMPLETED
            "FAILED" -> FAILED
            else -> NONE
        }
    }
}

/**
 * A single SIM in the promoter's inbox.
 *
 * @param suggestedPrice falls back to null when the category has no default
 *        price configured — the operator picks at sale time.
 */
data class MySim(
    val id: String,
    val serialNumber: String,
    val custodyState: SimCustodyState,
    val categoryId: String?,
    val categoryName: String?,
    val suggestedPrice: BigDecimal?,
    val assignedAt: Instant?,
    val acceptedAt: Instant?,
    val soldAt: Instant?,
    // Back-office documentation review (only meaningful when custodyState == SOLD).
    // Drives the "Revisar" / "En revisión" badge and the tap-to-correct deep link.
    val verificationStatus: SimVerificationStatus = SimVerificationStatus.NONE,
    val verificationId: String? = null,
    val rejectionReasons: List<String> = emptyList(),
    val reviewNotes: String? = null,
)

/** Summary returned by bulk endpoints. `failed > 0` signals partial success. */
data class BulkSummary(
    val total: Int,
    val succeeded: Int,
    val failed: Int,
)

/** A row inside a bulk response. */
data class BulkRowResult(
    val serialNumber: String,
    val isOk: Boolean,
    val errorCode: String?,
    val errorMessage: String?,
)

data class BulkResult(
    val summary: BulkSummary,
    val results: List<BulkRowResult>,
)

package com.jaac.avoqado_tpv.features.sim_custody.data.dto

import com.google.gson.annotations.SerializedName

/**
 * DTOs for the /tpv/sim-custody endpoints (backend plan §1.4).
 *
 * All money-bearing fields arrive as JSON strings and are parsed to BigDecimal
 * in the repository layer — keep these as String to avoid precision loss.
 */

data class MySimItemDto(
    val id: String,
    val serialNumber: String,
    val custodyState: String, // PROMOTER_PENDING | PROMOTER_HELD | SOLD
    val assignedPromoterAt: String?,
    val promoterAcceptedAt: String?,
    val soldAt: String?,
    val category: MySimCategoryDto?,
    // Back-office documentation review (PlayTelecom / Walmart) for SOLD SIMs.
    // Null for non-sold SIMs, non-serialized venues, or when talking to a legacy
    // backend that doesn't return these fields — the badge then stays "Vendido".
    val verificationStatus: String? = null, // PENDING | PROCESSING | COMPLETED | FAILED | null
    val verificationId: String? = null, // SaleVerification id — needed by the sale-correction flow
    val rejectionReasons: List<String>? = null, // ["REVIEW_MISSING_LINKING_IMAGE", ...] when FAILED
    val reviewNotes: String? = null, // Free-text feedback from back-office
)

data class MySimCategoryDto(
    val id: String,
    val name: String,
    val suggestedPrice: String?, // Decimal serialized as string
)

data class MySimsResponseDto(
    val items: List<MySimItemDto>,
)

data class AcceptRequestDto(
    val serialNumbers: List<String>,
)

data class RejectRequestDto(
    val serialNumber: String,
)

/** Mirrors the bulk response contract (plan §1.3). */
data class BulkSimCustodyResponseDto(
    val summary: BulkSummaryDto,
    val results: List<BulkResultRowDto>,
)

data class BulkSummaryDto(
    val total: Int,
    val succeeded: Int,
    val failed: Int,
)

data class BulkResultRowDto(
    val serialNumber: String,
    val status: String, // "ok" | "error"
    val event: String?,
    val eventId: String?,
    val code: String?,
    val message: String?,
)

data class RejectResponseDto(
    @SerializedName("custodyState")
    val custodyState: String,
)

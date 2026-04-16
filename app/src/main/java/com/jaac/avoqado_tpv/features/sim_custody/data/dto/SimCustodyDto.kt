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

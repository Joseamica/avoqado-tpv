package com.jaac.avoqado_tpv.features.reports.data.dto

import com.google.gson.annotations.SerializedName
import com.jaac.avoqado_tpv.features.reports.domain.models.HistoricalGrouping
import com.jaac.avoqado_tpv.features.reports.domain.models.HistoricalPeriod
import com.jaac.avoqado_tpv.features.reports.domain.models.PaginatedHistoricalData
import java.math.BigDecimal
import java.time.Instant

/**
 * Historical Reports API Response DTO
 *
 * Maps backend JSON response to domain models.
 *
 * **Backend Response Format**:
 * ```json
 * {
 *   "success": true,
 *   "data": {
 *     "periods": [...],
 *     "pagination": {
 *       "nextCursor": "2025-01-14T23:59:59Z",
 *       "hasMore": true
 *     }
 *   }
 * }
 * ```
 */
data class HistoricalReportsResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("data")
    val data: HistoricalDataDto
)

data class HistoricalDataDto(
    @SerializedName("periods")
    val periods: List<HistoricalPeriodDto>,

    @SerializedName("pagination")
    val pagination: PaginationDto
)

data class HistoricalPeriodDto(
    @SerializedName("periodStart")
    val periodStart: String,  // ISO 8601

    @SerializedName("periodEnd")
    val periodEnd: String,  // ISO 8601

    @SerializedName("grouping")
    val grouping: String,  // "DAILY", "WEEKLY", "MONTHLY", "QUARTERLY", "YEARLY"

    @SerializedName("label")
    val label: String,  // "15 Enero 2025"

    @SerializedName("subtitle")
    val subtitle: String,  // "Martes" or ""

    @SerializedName("totalSales")
    val totalSales: Double,

    @SerializedName("totalOrders")
    val totalOrders: Int,

    @SerializedName("totalProducts")
    val totalProducts: Int,

    @SerializedName("averageOrderValue")
    val averageOrderValue: Double,

    @SerializedName("salesChange")
    val salesChange: Double?,  // null if no previous period

    @SerializedName("ordersChange")
    val ordersChange: Double?  // null if no previous period
)

data class PaginationDto(
    @SerializedName("nextCursor")
    val nextCursor: String?,

    @SerializedName("hasMore")
    val hasMore: Boolean
)

// ══════════════════════════════════════════════════════════════════════
// MAPPERS (DTO → Domain)
// ══════════════════════════════════════════════════════════════════════

/**
 * Convert DTO to domain model
 */
fun HistoricalReportsResponse.toDomain(): PaginatedHistoricalData {
    return PaginatedHistoricalData(
        periods = data.periods.map { it.toDomain() },
        nextCursor = data.pagination.nextCursor,
        hasMore = data.pagination.hasMore
    )
}

/**
 * Convert period DTO to domain model
 */
fun HistoricalPeriodDto.toDomain(): HistoricalPeriod {
    return HistoricalPeriod(
        periodStart = Instant.parse(periodStart),
        periodEnd = Instant.parse(periodEnd),
        grouping = HistoricalGrouping.valueOf(grouping),
        label = label,
        subtitle = subtitle,
        totalSales = BigDecimal(totalSales.toString()),
        totalOrders = totalOrders,
        totalProducts = totalProducts,
        averageOrderValue = BigDecimal(averageOrderValue.toString()),
        salesChange = salesChange?.let { BigDecimal(it.toString()) },
        ordersChange = ordersChange?.let { BigDecimal(it.toString()) }
    )
}

package com.jaac.avoqado_tpv.features.reports.domain.models

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * Single historical period with aggregated metrics and period-over-period comparison
 *
 * Represents sales data for a specific time period (day, week, month, etc.)
 * with automatic comparison to the previous equivalent period.
 *
 * **Example**:
 * ```
 * HistoricalPeriod(
 *   label = "15 Enero 2025",
 *   subtitle = "Martes",
 *   totalSales = 12450.50,
 *   totalOrders = 145,
 *   salesChange = 12.5  // +12.5% vs 14 Enero 2025
 * )
 * ```
 */
data class HistoricalPeriod(
    val periodStart: Instant,
    val periodEnd: Instant,
    val grouping: HistoricalGrouping,

    // Display labels
    val label: String,              // "15 Enero 2025" or "Enero 2025"
    val subtitle: String,           // "Martes" or "Semana 3" or ""

    // Metrics
    val totalSales: BigDecimal,
    val totalOrders: Int,
    val totalProducts: Int,
    val averageOrderValue: BigDecimal,

    // Period-over-period comparison (null if no previous period)
    val salesChange: BigDecimal?,   // +12.5 (percentage)
    val ordersChange: BigDecimal?   // -3.2 (percentage)
) {
    /**
     * Format sales change as string with sign and arrow
     * Examples: "+12.5% ↑", "-3.2% ↓", ""
     */
    fun formatSalesChange(): String {
        return if (salesChange == null) ""
        else {
            val rounded = salesChange.setScale(1, RoundingMode.HALF_UP)
            val sign = if (rounded >= BigDecimal.ZERO) "+" else ""
            val arrow = if (rounded >= BigDecimal.ZERO) "↑" else "↓"
            "$sign$rounded% $arrow"
        }
    }

    /**
     * Format orders change as string with sign and arrow
     * Examples: "+9.8% ↑", "-1.5% ↓", ""
     */
    fun formatOrdersChange(): String {
        return if (ordersChange == null) ""
        else {
            val rounded = ordersChange.setScale(1, RoundingMode.HALF_UP)
            val sign = if (rounded >= BigDecimal.ZERO) "+" else ""
            val arrow = if (rounded >= BigDecimal.ZERO) "↑" else "↓"
            "$sign$rounded% $arrow"
        }
    }

    /**
     * Check if sales increased vs previous period
     */
    fun isSalesIncreasing(): Boolean {
        return salesChange != null && salesChange >= BigDecimal.ZERO
    }

    /**
     * Check if orders increased vs previous period
     */
    fun isOrdersIncreasing(): Boolean {
        return ordersChange != null && ordersChange >= BigDecimal.ZERO
    }

    /**
     * Format total sales as currency string
     * Example: "$12,450"
     */
    fun formatTotalSales(): String {
        return "$${totalSales.setScale(0, RoundingMode.HALF_UP).toPlainString()}"
    }

    /**
     * Format average order value as currency string
     * Example: "$86"
     */
    fun formatAverageOrderValue(): String {
        return "$${averageOrderValue.setScale(0, RoundingMode.HALF_UP).toPlainString()}"
    }

    /**
     * Generate unique key for this period
     *
     * Used for LazyColumn keys and selection state.
     * Includes timestamp, grouping, and hash to ensure uniqueness even with duplicate data.
     *
     * **Why hash is needed:**
     * - Backend may return duplicate periods (same timestamp)
     * - Hash of totalSales ensures uniqueness
     * - Prevents LazyColumn "duplicate key" crashes
     *
     * @return Unique key string (e.g., "1761717600000-DAILY-123456")
     */
    fun getUniqueKey(): String {
        val baseKey = "${periodStart.toEpochMilli()}-${grouping.name}"
        val hash = totalSales.hashCode()
        return "$baseKey-$hash"
    }
}

/**
 * Paginated list of historical periods with cursor-based pagination
 *
 * Used for infinite scroll or "Load More" button patterns.
 *
 * **Cache Support (Toast POS Pattern):**
 * - `isFromCache`: True if data was served from offline cache
 * - `cacheTimestamp`: When data was cached (for staleness detection)
 * - Show "Last updated: X minutes ago" banner if from cache
 */
data class PaginatedHistoricalData(
    val periods: List<HistoricalPeriod>,
    val nextCursor: String?,
    val hasMore: Boolean,
    val isFromCache: Boolean = false,
    val cacheTimestamp: Long? = null
) {
    /**
     * Check if cached data is stale (older than 24 hours)
     *
     * @return True if cache timestamp exists and is >24h old
     */
    fun isStale(): Boolean {
        if (cacheTimestamp == null) return false
        val now = System.currentTimeMillis()
        val ageMs = now - cacheTimestamp
        val ttlMs = 24 * 60 * 60 * 1000L // 24 hours
        return ageMs > ttlMs
    }

    /**
     * Get cache age as human-readable string
     *
     * Examples: "hace 5 minutos", "hace 2 horas", "hace 1 día"
     */
    fun getCacheAgeString(): String? {
        if (cacheTimestamp == null) return null

        val now = System.currentTimeMillis()
        val ageMs = now - cacheTimestamp

        return when {
            ageMs < 60_000 -> "hace menos de 1 minuto"
            ageMs < 3600_000 -> {
                val minutes = (ageMs / 60_000).toInt()
                "hace $minutes ${if (minutes == 1) "minuto" else "minutos"}"
            }
            ageMs < 86400_000 -> {
                val hours = (ageMs / 3600_000).toInt()
                "hace $hours ${if (hours == 1) "hora" else "horas"}"
            }
            else -> {
                val days = (ageMs / 86400_000).toInt()
                "hace $days ${if (days == 1) "día" else "días"}"
            }
        }
    }
}

package com.jaac.avoqado_tpv.core.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.jaac.avoqado_tpv.features.reports.domain.models.HistoricalGrouping
import com.jaac.avoqado_tpv.features.reports.domain.models.HistoricalPeriod
import java.math.BigDecimal
import java.time.Instant

/**
 * HistoricalPeriodEntity - Offline cache for historical sales data
 *
 * Room entity for caching historical reports with network-first + cache-fallback strategy:
 * - Fetch from network first (freshest data)
 * - Cache response locally (instant offline access)
 * - Serve cached data when offline
 * - Auto-expire after 24 hours (stale data detection)
 *
 * ## Cache Strategy (Toast POS Pattern)
 * - **Online**: Network-first, update cache after successful fetch
 * - **Offline**: Cache-only, show cached data with staleness indicator
 * - **Stale Detection**: Compare `cachedAt` timestamp (24h TTL)
 *
 * ## Data Lifecycle
 * 1. User opens Historial tab → Load from network
 * 2. Network success → Save to Room + display
 * 3. User goes offline → Load from Room cache
 * 4. Cache older than 24h → Show "Data may be outdated" banner
 *
 * ## Cache Invalidation
 * - New data from network replaces cached periods (upsert by composite key)
 * - Cache cleared when user logs out or changes venue
 * - Grouping change invalidates cache for that grouping
 *
 * @property id Auto-generated primary key (Room manages)
 * @property venueId Tenant isolation (cache scoped to venue)
 * @property grouping Time grouping (DAILY, WEEKLY, MONTHLY, etc.)
 * @property periodStart Period start timestamp (milliseconds since epoch)
 * @property periodEnd Period end timestamp
 * @property label Display label ("15 Enero 2025")
 * @property subtitle Display subtitle ("Martes")
 * @property totalSales Total sales as string (BigDecimal storage)
 * @property totalOrders Total order count
 * @property totalProducts Total product count
 * @property averageOrderValue Average order value as string
 * @property salesChange Percentage change vs previous period (nullable)
 * @property ordersChange Percentage change in orders (nullable)
 * @property cachedAt Timestamp when data was cached (for staleness detection)
 */
@Entity(
    tableName = "historical_periods",
    indices = [
        // Index for cache expiration queries
        Index(value = ["cached_at"]),
        // Unique composite index for efficient queries + duplicate prevention
        Index(value = ["venue_id", "grouping", "period_start"], unique = true)
    ]
)
data class HistoricalPeriodEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "venue_id")
    val venueId: String,

    @ColumnInfo(name = "grouping")
    val grouping: String, // HistoricalGrouping enum as string

    @ColumnInfo(name = "period_start")
    val periodStart: Long, // Unix timestamp (milliseconds)

    @ColumnInfo(name = "period_end")
    val periodEnd: Long,

    @ColumnInfo(name = "label")
    val label: String,

    @ColumnInfo(name = "subtitle")
    val subtitle: String,

    @ColumnInfo(name = "total_sales")
    val totalSales: String, // BigDecimal as String (Room doesn't support BigDecimal)

    @ColumnInfo(name = "total_orders")
    val totalOrders: Int,

    @ColumnInfo(name = "total_products")
    val totalProducts: Int,

    @ColumnInfo(name = "average_order_value")
    val averageOrderValue: String, // BigDecimal as String

    @ColumnInfo(name = "sales_change")
    val salesChange: String?, // BigDecimal as String (nullable)

    @ColumnInfo(name = "orders_change")
    val ordersChange: String?, // BigDecimal as String (nullable)

    @ColumnInfo(name = "cached_at")
    val cachedAt: Long // Timestamp when data was cached (for staleness detection)
) {
    companion object {
        /**
         * Cache TTL (Time To Live) - 24 hours
         * Data older than this is considered stale.
         */
        const val CACHE_TTL_MILLIS = 24 * 60 * 60 * 1000L // 24 hours

        /**
         * Convert domain model to Room entity
         *
         * @param period Domain model from network/repository
         * @param venueId Current venue ID
         * @return Room entity ready for database insertion
         */
        fun fromDomain(period: HistoricalPeriod, venueId: String): HistoricalPeriodEntity {
            return HistoricalPeriodEntity(
                venueId = venueId,
                grouping = period.grouping.name,
                periodStart = period.periodStart.toEpochMilli(),
                periodEnd = period.periodEnd.toEpochMilli(),
                label = period.label,
                subtitle = period.subtitle,
                totalSales = period.totalSales.toPlainString(),
                totalOrders = period.totalOrders,
                totalProducts = period.totalProducts,
                averageOrderValue = period.averageOrderValue.toPlainString(),
                salesChange = period.salesChange?.toPlainString(),
                ordersChange = period.ordersChange?.toPlainString(),
                cachedAt = System.currentTimeMillis()
            )
        }
    }

    /**
     * Convert Room entity to domain model
     *
     * @return Domain model for presentation layer
     */
    fun toDomain(): HistoricalPeriod {
        return HistoricalPeriod(
            periodStart = Instant.ofEpochMilli(periodStart),
            periodEnd = Instant.ofEpochMilli(periodEnd),
            grouping = HistoricalGrouping.valueOf(grouping),
            label = label,
            subtitle = subtitle,
            totalSales = BigDecimal(totalSales),
            totalOrders = totalOrders,
            totalProducts = totalProducts,
            averageOrderValue = BigDecimal(averageOrderValue),
            salesChange = salesChange?.let { BigDecimal(it) },
            ordersChange = ordersChange?.let { BigDecimal(it) }
        )
    }

    /**
     * Check if cached data is stale (older than 24 hours)
     *
     * @return True if data should be refreshed from network
     */
    fun isStale(): Boolean {
        val now = System.currentTimeMillis()
        return (now - cachedAt) > CACHE_TTL_MILLIS
    }
}

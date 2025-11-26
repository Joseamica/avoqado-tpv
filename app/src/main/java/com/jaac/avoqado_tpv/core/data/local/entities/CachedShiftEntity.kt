package com.jaac.avoqado_tpv.core.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus
import java.math.BigDecimal

/**
 * CachedShiftEntity - Offline cache for last known shift status
 *
 * Room entity for caching the last known shift state. Used when offline
 * to show the user the last known shift status instead of "Sin turno activo"
 * which is misleading when we simply can't reach the server.
 *
 * ## Cache Strategy (Square/Toast POS Pattern - Prevention)
 * - **Online**: Fetch from network, cache response locally
 * - **Offline**: Show cached data with "Último estado conocido (hace X min)"
 * - **Offline Actions**: BLOCKED - shift open/close requires connection
 * - **Reconnection**: Auto-sync via ConnectionEventManager (already implemented)
 *
 * ## Why Prevention Pattern?
 * - Square, Toast, and Clover block shift operations when offline
 * - Prevents payroll issues (shift duration affects pay)
 * - Prevents cash reconciliation errors
 * - Prevents multi-terminal conflicts (another terminal may have changed shift)
 *
 * ## Data Lifecycle
 * 1. User opens app with connection → Load shift from network → Cache locally
 * 2. User loses connection → Show cached state with timestamp
 * 3. User tries to open/close shift → Show "Requiere conexión" tooltip
 * 4. Connection restored → Auto-sync (listenToConnectionRestored already exists)
 *
 * ## Cache Invalidation
 * - Updated on every successful network fetch
 * - Cleared on logout (tenant isolation)
 * - No TTL (always show last known, no expiration)
 *
 * @property id Shift ID (primary key, same as server ID)
 * @property venueId Tenant isolation (cache scoped to venue)
 * @property status Shift status ("OPEN" or "CLOSED")
 * @property staffId Staff member who opened the shift
 * @property staffName Staff name for display
 * @property startTime Shift start timestamp (ISO 8601 string)
 * @property totalSales Total sales amount as string (BigDecimal storage)
 * @property totalOrders Total order count
 * @property durationMinutes Shift duration in minutes (null if just opened)
 * @property cachedAt Timestamp when data was cached (for "hace X min" display)
 *
 * @see Shift Domain model
 */
@Entity(
    tableName = "cached_shift",
    indices = [
        // Unique constraint: only one cached shift per venue (also provides fast lookup)
        Index(value = ["venue_id"], unique = true, name = "index_cached_shift_venue_id")
    ]
)
data class CachedShiftEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "venue_id")
    val venueId: String,

    @ColumnInfo(name = "status")
    val status: String, // "OPEN" or "CLOSED"

    @ColumnInfo(name = "staff_id")
    val staffId: String,

    @ColumnInfo(name = "staff_name")
    val staffName: String,

    @ColumnInfo(name = "start_time")
    val startTime: String, // ISO 8601 timestamp

    @ColumnInfo(name = "total_sales")
    val totalSales: String, // BigDecimal as String

    @ColumnInfo(name = "total_orders")
    val totalOrders: Int,

    @ColumnInfo(name = "duration_minutes")
    val durationMinutes: Int?,

    @ColumnInfo(name = "cached_at")
    val cachedAt: Long // System.currentTimeMillis() when cached
) {
    companion object {
        /**
         * Convert domain model to Room entity
         *
         * @param shift Domain model from network/repository
         * @param venueId Current venue ID
         * @return Room entity ready for database insertion
         */
        fun fromDomain(shift: Shift, venueId: String): CachedShiftEntity {
            return CachedShiftEntity(
                id = shift.id,
                venueId = venueId,
                status = shift.status.name,
                staffId = shift.staffId,
                staffName = shift.staffName,
                startTime = shift.startTime,
                totalSales = shift.totalSales.toPlainString(),
                totalOrders = shift.totalOrders,
                durationMinutes = shift.durationMinutes,
                cachedAt = System.currentTimeMillis()
            )
        }
    }

    /**
     * Check if cached shift is OPEN
     *
     * @return True if shift status is OPEN
     */
    fun isOpen(): Boolean = status == ShiftStatus.OPEN.name

    /**
     * Calculate minutes since data was cached
     *
     * Used for "Último estado conocido (hace X min)" display.
     *
     * @return Minutes since cache (minimum 0)
     */
    fun minutesSinceCached(): Int {
        val now = System.currentTimeMillis()
        val diff = now - cachedAt
        return (diff / (1000 * 60)).toInt().coerceAtLeast(0)
    }

    /**
     * Convert to partial domain model (for display only)
     *
     * Note: This returns a partial Shift with default values for
     * fields not stored in cache. Only use for offline display.
     *
     * @return Domain model for presentation layer
     */
    fun toDomainPartial(): Shift {
        return Shift(
            id = id,
            venueId = venueId,
            staffId = staffId,
            staffName = staffName,
            startTime = startTime,
            endTime = null,
            status = ShiftStatus.valueOf(status),
            startingCash = BigDecimal.ZERO, // Not cached
            endingCash = null,
            totalSales = BigDecimal(totalSales),
            totalTips = BigDecimal.ZERO, // Not cached
            totalOrders = totalOrders,
            totalCashPayments = BigDecimal.ZERO, // Not cached
            totalCardPayments = BigDecimal.ZERO, // Not cached
            totalVoucherPayments = BigDecimal.ZERO, // Not cached
            totalOtherPayments = BigDecimal.ZERO, // Not cached
            totalProductsSold = 0, // Not cached
            durationMinutes = durationMinutes
        )
    }
}

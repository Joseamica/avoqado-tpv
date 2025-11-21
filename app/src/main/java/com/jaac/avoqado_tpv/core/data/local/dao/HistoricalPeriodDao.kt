package com.jaac.avoqado_tpv.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jaac.avoqado_tpv.core.data.local.entities.HistoricalPeriodEntity
import kotlinx.coroutines.flow.Flow

/**
 * HistoricalPeriodDao - Room database access object for historical sales data cache
 *
 * Provides cache operations for historical reports with offline-first strategy.
 * All operations are suspend functions for Kotlin coroutines compatibility.
 *
 * ## Key Operations
 * - **getPeriods()**: Get cached periods for venue + grouping + time range
 * - **getPeriodsFlow()**: Reactive Flow for UI updates
 * - **insertPeriods()**: Upsert periods (replace on conflict)
 * - **deleteOldPeriods()**: Clear stale cache (older than 24h)
 * - **clearCacheForVenue()**: Remove all cached data for venue (logout/venue switch)
 *
 * ## Cache Strategy
 * - Network-first: Try API, cache response, serve cached on offline
 * - TTL: 24 hours (data older than 24h considered stale)
 * - Upsert: Prevents duplicates (unique index on venue_id + grouping + period_start)
 *
 * ## Indexing Strategy
 * Queries use indexed columns for performance:
 * - (venue_id, grouping, period_start): Composite index for range queries
 * - cached_at: Fast TTL-based cleanup queries
 *
 * @see HistoricalPeriodEntity
 */
@Dao
interface HistoricalPeriodDao {

    /**
     * Get cached periods for venue + grouping + time range.
     * Returns periods sorted by period_start DESC (newest first).
     *
     * **Usage**: Load cached data when offline or as temporary placeholder while fetching from network.
     *
     * @param venueId Tenant ID (cache scoped to venue)
     * @param grouping Time grouping (DAILY, WEEKLY, MONTHLY, etc.)
     * @param startDate Start of time range (milliseconds)
     * @param endDate End of time range (milliseconds)
     * @param limit Maximum number of periods to return
     * @return List of cached periods (may be empty)
     */
    @Query(
        """
        SELECT * FROM historical_periods
        WHERE venue_id = :venueId
          AND grouping = :grouping
          AND period_start >= :startDate
          AND period_start <= :endDate
        ORDER BY period_start DESC
        LIMIT :limit
        """
    )
    suspend fun getPeriods(
        venueId: String,
        grouping: String,
        startDate: Long,
        endDate: Long,
        limit: Int
    ): List<HistoricalPeriodEntity>

    /**
     * Get cached periods as Flow (reactive).
     * Useful for observing cache changes in UI (e.g., auto-refresh when network syncs).
     *
     * **Usage**: Subscribe in ViewModel to get live updates when cache is refreshed.
     *
     * @param venueId Tenant ID
     * @param grouping Time grouping
     * @param startDate Start of time range
     * @param endDate End of time range
     * @param limit Maximum number of periods
     * @return Flow that emits list of periods on every DB change
     */
    @Query(
        """
        SELECT * FROM historical_periods
        WHERE venue_id = :venueId
          AND grouping = :grouping
          AND period_start >= :startDate
          AND period_start <= :endDate
        ORDER BY period_start DESC
        LIMIT :limit
        """
    )
    fun getPeriodsFlow(
        venueId: String,
        grouping: String,
        startDate: Long,
        endDate: Long,
        limit: Int
    ): Flow<List<HistoricalPeriodEntity>>

    /**
     * Get all cached periods for venue + grouping (no time range filter).
     * Useful for loading entire cache without pagination.
     *
     * @param venueId Tenant ID
     * @param grouping Time grouping
     * @return List of all cached periods for this grouping
     */
    @Query(
        """
        SELECT * FROM historical_periods
        WHERE venue_id = :venueId
          AND grouping = :grouping
        ORDER BY period_start DESC
        """
    )
    suspend fun getAllPeriodsForGrouping(
        venueId: String,
        grouping: String
    ): List<HistoricalPeriodEntity>

    /**
     * Insert or update periods (upsert).
     * If period with same (venue_id, grouping, period_start) exists, replace it.
     *
     * **Usage**: Cache network response after successful API call.
     *
     * **Strategy**: REPLACE on conflict (unique index prevents duplicates)
     *
     * @param periods List of periods to cache
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeriods(periods: List<HistoricalPeriodEntity>)

    /**
     * Delete periods older than specified timestamp (cache cleanup).
     * Removes stale data to keep database size manageable.
     *
     * **Usage**: Run periodically (e.g., on app startup or when memory low).
     *
     * @param olderThan Unix timestamp in milliseconds (e.g., now - 24h)
     * @return Number of rows deleted
     */
    @Query("DELETE FROM historical_periods WHERE cached_at < :olderThan")
    suspend fun deleteOldPeriods(olderThan: Long): Int

    /**
     * Clear all cached periods for a venue.
     * Used when user logs out or switches venue.
     *
     * **Usage**: Prevent cache leakage between venues/sessions.
     *
     * @param venueId Venue ID to clear cache for
     * @return Number of rows deleted
     */
    @Query("DELETE FROM historical_periods WHERE venue_id = :venueId")
    suspend fun clearCacheForVenue(venueId: String): Int

    /**
     * Clear cache for specific grouping.
     * Used when user changes grouping (force refresh from network).
     *
     * @param venueId Venue ID
     * @param grouping Grouping to clear (DAILY, WEEKLY, etc.)
     * @return Number of rows deleted
     */
    @Query("DELETE FROM historical_periods WHERE venue_id = :venueId AND grouping = :grouping")
    suspend fun clearCacheForGrouping(venueId: String, grouping: String): Int

    /**
     * Check if cache exists for venue + grouping + time range.
     * Useful for deciding whether to show loading state or cached data immediately.
     *
     * @param venueId Venue ID
     * @param grouping Time grouping
     * @param startDate Start of time range
     * @param endDate End of time range
     * @return True if at least one period is cached
     */
    @Query(
        """
        SELECT COUNT(*) > 0 FROM historical_periods
        WHERE venue_id = :venueId
          AND grouping = :grouping
          AND period_start >= :startDate
          AND period_start <= :endDate
        """
    )
    suspend fun hasCachedData(
        venueId: String,
        grouping: String,
        startDate: Long,
        endDate: Long
    ): Boolean

    /**
     * Get timestamp of most recent cache entry for venue + grouping.
     * Useful for showing "Last updated: X minutes ago" in UI.
     *
     * @param venueId Venue ID
     * @param grouping Time grouping
     * @return Timestamp of most recent cache entry (milliseconds), or null if no cache exists
     */
    @Query(
        """
        SELECT MAX(cached_at) FROM historical_periods
        WHERE venue_id = :venueId AND grouping = :grouping
        """
    )
    suspend fun getLastCachedTimestamp(venueId: String, grouping: String): Long?
}

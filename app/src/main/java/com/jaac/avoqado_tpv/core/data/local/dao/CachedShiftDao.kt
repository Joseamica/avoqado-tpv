package com.jaac.avoqado_tpv.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jaac.avoqado_tpv.core.data.local.entities.CachedShiftEntity

/**
 * CachedShiftDao - Room DAO for cached shift status
 *
 * Provides cache operations for offline shift display following
 * the Square/Toast POS prevention pattern.
 *
 * ## Key Operations
 * - **getCachedShift()**: Get last known shift for venue
 * - **cacheShift()**: Save/update shift cache (upsert)
 * - **clearCache()**: Remove cache for venue (logout/venue switch)
 *
 * ## Cache Strategy (Prevention Pattern)
 * - Cache updated after every successful network fetch
 * - Cache read when offline to show "Último estado conocido"
 * - No TTL (always show last known state)
 * - Clear on logout (tenant isolation)
 *
 * ## Usage in ShiftViewModel
 * ```kotlin
 * // Online: Fetch from network and cache
 * when (val result = shiftRepository.getCurrentShift(venueId)) {
 *     is Result.Success -> {
 *         result.data?.let { shift ->
 *             cachedShiftDao.cacheShift(CachedShiftEntity.fromDomain(shift, venueId))
 *         }
 *         _state.value = ShiftState.ShiftActive(result.data, history)
 *     }
 *     is Result.Error -> { /* Show error */ }
 * }
 *
 * // Offline: Show cached state
 * val cached = cachedShiftDao.getCachedShift(venueId)
 * if (cached != null) {
 *     _cachedShiftInfo.value = CachedShiftInfo(
 *         isOpen = cached.isOpen(),
 *         staffName = cached.staffName,
 *         cachedMinutesAgo = cached.minutesSinceCached()
 *     )
 * }
 * ```
 *
 * @see CachedShiftEntity
 */
@Dao
interface CachedShiftDao {

    /**
     * Get cached shift for venue.
     *
     * Returns the last known shift state, or null if no cache exists.
     * Only one shift can be cached per venue (unique constraint).
     *
     * **Usage**: Called when offline to display last known state.
     *
     * @param venueId Tenant ID (cache scoped to venue)
     * @return Cached shift entity, or null if no cache
     */
    @Query("SELECT * FROM cached_shift WHERE venue_id = :venueId LIMIT 1")
    suspend fun getCachedShift(venueId: String): CachedShiftEntity?

    /**
     * Cache shift (upsert).
     *
     * Inserts new cache or replaces existing cache for venue.
     * Uses REPLACE strategy: if venue_id exists, replace entire row.
     *
     * **Usage**: Called after successful network fetch to update cache.
     *
     * @param shift Shift entity to cache
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun cacheShift(shift: CachedShiftEntity)

    /**
     * Clear cache for venue.
     *
     * Removes cached shift data for specified venue.
     * Used when user logs out or switches venue.
     *
     * **Usage**: Call on logout to prevent cache leakage.
     *
     * @param venueId Venue ID to clear cache for
     * @return Number of rows deleted (0 or 1)
     */
    @Query("DELETE FROM cached_shift WHERE venue_id = :venueId")
    suspend fun clearCache(venueId: String): Int

    /**
     * Clear all cached shifts.
     *
     * Removes all cached shift data (for global cleanup).
     * Used during full logout or database reset.
     *
     * @return Number of rows deleted
     */
    @Query("DELETE FROM cached_shift")
    suspend fun clearAllCache(): Int

    /**
     * Check if cache exists for venue.
     *
     * Lightweight check without loading full entity.
     *
     * @param venueId Venue ID to check
     * @return True if cache exists for this venue
     */
    @Query("SELECT COUNT(*) > 0 FROM cached_shift WHERE venue_id = :venueId")
    suspend fun hasCachedShift(venueId: String): Boolean

    /**
     * Get cache timestamp for venue.
     *
     * Returns when cache was last updated, or null if no cache.
     * Useful for displaying "hace X minutos" without loading full entity.
     *
     * @param venueId Venue ID
     * @return Unix timestamp (milliseconds), or null if no cache
     */
    @Query("SELECT cached_at FROM cached_shift WHERE venue_id = :venueId")
    suspend fun getCacheTimestamp(venueId: String): Long?
}

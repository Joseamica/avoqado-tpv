package com.jaac.avoqado_tpv.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jaac.avoqado_tpv.core.data.local.entities.FloorElementEntity

/**
 * DAO for Floor Element cache operations (floor plan decorations).
 */
@Dao
interface FloorElementDao {

    /**
     * Get all cached floor elements for a venue.
     */
    @Query(
        """
        SELECT * FROM floor_elements_cache
        WHERE venue_id = :venueId
        """
    )
    suspend fun getFloorElements(venueId: String): List<FloorElementEntity>

    /**
     * Upsert floor elements (replace on conflict).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFloorElements(elements: List<FloorElementEntity>)

    /**
     * Delete all cached floor elements for a venue.
     */
    @Query("DELETE FROM floor_elements_cache WHERE venue_id = :venueId")
    suspend fun deleteAll(venueId: String)

    /**
     * Latest cache timestamp for a venue.
     */
    @Query(
        """
        SELECT MAX(cached_at) FROM floor_elements_cache
        WHERE venue_id = :venueId
        """
    )
    suspend fun getLatestCacheTimestamp(venueId: String): Long?
}

package com.jaac.avoqado_tpv.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jaac.avoqado_tpv.core.data.local.entities.TableEntity

/**
 * DAO for Table cache operations (floor plan / table service).
 */
@Dao
interface TableDao {

    /**
     * Get all cached tables for a venue.
     */
    @Query(
        """
        SELECT * FROM tables_cache
        WHERE venue_id = :venueId
        ORDER BY number ASC
        """
    )
    suspend fun getTables(venueId: String): List<TableEntity>

    /**
     * Upsert tables (replace on conflict).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTables(tables: List<TableEntity>)

    /**
     * Delete all cached tables for a venue.
     */
    @Query("DELETE FROM tables_cache WHERE venue_id = :venueId")
    suspend fun deleteAll(venueId: String)

    /**
     * Latest cache timestamp for a venue.
     */
    @Query(
        """
        SELECT MAX(cached_at) FROM tables_cache
        WHERE venue_id = :venueId
        """
    )
    suspend fun getLatestCacheTimestamp(venueId: String): Long?
}

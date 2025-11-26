package com.jaac.avoqado_tpv.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jaac.avoqado_tpv.core.data.local.entities.ProductCategoryEntity

/**
 * DAO for ProductCategory cache operations.
 *
 * **Cache Strategy:**
 * - Cached with products (same refresh cycle)
 * - TTL: 24 hours
 * - Tenant isolation: Always filter by venue_id
 */
@Dao
interface ProductCategoryDao {

    /**
     * Get all cached categories for a venue.
     *
     * @param venueId Venue ID (tenant isolation)
     * @return List of categories sorted by display_order
     */
    @Query(
        """
        SELECT * FROM product_categories
        WHERE venue_id = :venueId
        ORDER BY display_order ASC
        """
    )
    suspend fun getAllCategories(venueId: String): List<ProductCategoryEntity>

    /**
     * Get a single category by ID.
     *
     * @param venueId Venue ID (tenant isolation)
     * @param categoryId Backend category ID
     * @return Category or null if not cached
     */
    @Query(
        """
        SELECT * FROM product_categories
        WHERE venue_id = :venueId AND category_id = :categoryId
        LIMIT 1
        """
    )
    suspend fun getCategoryById(venueId: String, categoryId: String): ProductCategoryEntity?

    /**
     * Upsert categories (batch insert/update).
     *
     * **Strategy:** REPLACE on conflict (venue_id, category_id unique index)
     *
     * @param categories List of categories to cache
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategories(categories: List<ProductCategoryEntity>)

    /**
     * Delete all categories for a venue (cache invalidation).
     *
     * @param venueId Venue ID to clear cache
     */
    @Query("DELETE FROM product_categories WHERE venue_id = :venueId")
    suspend fun deleteAllCategories(venueId: String)

    /**
     * Delete stale categories (TTL-based cleanup).
     *
     * @param cutoffTimestamp Unix timestamp (categories older than this are deleted)
     */
    @Query("DELETE FROM product_categories WHERE cached_at < :cutoffTimestamp")
    suspend fun deleteStaleCategories(cutoffTimestamp: Long)
}

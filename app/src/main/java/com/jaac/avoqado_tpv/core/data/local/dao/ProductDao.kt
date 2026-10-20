package com.jaac.avoqado_tpv.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.jaac.avoqado_tpv.core.data.local.entities.ProductEntity

/**
 * DAO for Product cache operations.
 *
 * **Cache Strategy (Toast POS Pattern):**
 * - Upsert products (replace on conflict)
 * - TTL: 24 hours
 * - Tenant isolation: Always filter by venue_id
 *
 * **Performance:**
 * - Indexed queries for fast category/availability filtering
 * - Batch upsert for network refresh (single transaction)
 */
@Dao
interface ProductDao {

    /**
     * Get all cached products for a venue.
     *
     * @param venueId Venue ID (tenant isolation)
     * @return List of products sorted by (category, display_order)
     */
    @Query(
        """
        SELECT * FROM products
        WHERE venue_id = :venueId
        ORDER BY category_id ASC, display_order ASC
        """
    )
    suspend fun getAllProducts(venueId: String): List<ProductEntity>

    /**
     * Get cached products by category.
     *
     * @param venueId Venue ID (tenant isolation)
     * @param categoryId Category ID to filter
     * @return List of products in category
     */
    @Query(
        """
        SELECT * FROM products
        WHERE venue_id = :venueId AND category_id = :categoryId
        ORDER BY display_order ASC
        """
    )
    suspend fun getProductsByCategory(venueId: String, categoryId: String): List<ProductEntity>

    /**
     * Get only available products (for order creation).
     *
     * @param venueId Venue ID (tenant isolation)
     * @return List of available products
     */
    @Query(
        """
        SELECT * FROM products
        WHERE venue_id = :venueId AND available = 1
        ORDER BY category_id ASC, display_order ASC
        """
    )
    suspend fun getAvailableProducts(venueId: String): List<ProductEntity>

    /**
     * Get a single product by ID.
     *
     * @param venueId Venue ID (tenant isolation)
     * @param productId Backend product ID
     * @return Product or null if not cached
     */
    @Query(
        """
        SELECT * FROM products
        WHERE venue_id = :venueId AND product_id = :productId
        LIMIT 1
        """
    )
    suspend fun getProductById(venueId: String, productId: String): ProductEntity?

    /**
     * Get a single product by SKU (barcode).
     *
     * ✅ BARCODE QUICK ADD: Fast lookup for scanned barcodes
     * Uses indexed query for O(1) search
     *
     * @param venueId Venue ID (tenant isolation)
     * @param sku SKU (barcode) to search
     * @return Product or null if not found in cache
     */
    @Query(
        """
        SELECT * FROM products
        WHERE venue_id = :venueId AND sku = :sku
        LIMIT 1
        """
    )
    suspend fun getProductBySku(venueId: String, sku: String): ProductEntity?

    /**
     * Upsert products (batch insert/update).
     *
     * **Strategy:** REPLACE on conflict (venue_id, product_id unique index)
     *
     * @param products List of products to cache
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProducts(products: List<ProductEntity>)

    /**
     * Reemplazo EXACTO del catálogo guardado de un negocio, en una sola transacción: lo que ya no manda el server desaparece.
     * No depende de la hora y no toca a otros negocios. Lo llama sólo `CatalogoGuardado`,
     * y sólo cuando el server contestó bien.
     */
    @Transaction
    suspend fun reemplazarCatalogo(venueId: String, entidades: List<ProductEntity>) {
        deleteAllProducts(venueId)
        upsertProducts(entidades)
    }

    /**
     * Delete all products for a venue (cache invalidation).
     *
     * **Use Cases:**
     * - Venue switch
     * - Logout
     * - Explicit refresh
     *
     * @param venueId Venue ID to clear cache
     */
    @Query("DELETE FROM products WHERE venue_id = :venueId")
    suspend fun deleteAllProducts(venueId: String)

    /**
     * Get cache timestamp (to check if refresh is needed).
     *
     * @param venueId Venue ID
     * @return Latest cache timestamp or null if no products cached
     */
    @Query(
        """
        SELECT MAX(cached_at) FROM products
        WHERE venue_id = :venueId
        """
    )
    suspend fun getLatestCacheTimestamp(venueId: String): Long?
}

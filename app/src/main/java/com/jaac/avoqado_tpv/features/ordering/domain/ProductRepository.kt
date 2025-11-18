package com.jaac.avoqado_tpv.features.ordering.domain

/**
 * ProductRepository - Repository interface for product operations
 *
 * Responsible for:
 * - Loading all products for venue from backend
 * - Filtering products by category
 * - Caching products locally for offline support (Future Phase)
 *
 * Clean Architecture:
 * - Domain layer interface
 * - Implemented in data layer (ProductRepositoryImpl)
 * - Consumed by MenuViewModel via use cases
 */
interface ProductRepository {

    /**
     * Get all products for venue
     *
     * @param venueId Venue identifier
     * @param categoryId Optional category filter (null = all categories)
     * @return Result with list of products or error
     */
    suspend fun getProducts(
        venueId: String,
        categoryId: String? = null
    ): Result<List<Product>>

    /**
     * Get all categories for venue
     *
     * @param venueId Venue identifier
     * @return Result with list of categories or error
     */
    suspend fun getCategories(
        venueId: String
    ): Result<List<ProductCategory>>

    /**
     * Get single product by ID
     *
     * @param venueId Venue identifier
     * @param productId Product identifier
     * @return Result with product or error
     */
    suspend fun getProduct(
        venueId: String,
        productId: String
    ): Result<Product?>
}

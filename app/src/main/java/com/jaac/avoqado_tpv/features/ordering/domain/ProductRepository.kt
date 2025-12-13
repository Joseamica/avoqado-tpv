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

    /**
     * Search product by barcode (SKU)
     *
     * ✅ BARCODE QUICK ADD: Find product by scanning barcode
     * Uses SKU field as barcode identifier
     *
     * @param venueId Venue identifier
     * @param barcode Scanned barcode value
     * @return Result with product or null if not found
     */
    suspend fun getProductByBarcode(
        venueId: String,
        barcode: String
    ): Result<Product?>

    /**
     * Create product quickly from barcode scan
     *
     * ✅ BARCODE QUICK ADD: When scanning unknown barcode, create product on-the-fly
     * (Square POS pattern)
     *
     * @param venueId Venue identifier
     * @param barcode Scanned barcode (will be used as SKU)
     * @param name Product name
     * @param price Product price
     * @param categoryId Optional category ID
     * @param trackInventory Whether to track inventory for this product
     * @return Result with created product or error
     */
    suspend fun createQuickAddProduct(
        venueId: String,
        barcode: String,
        name: String,
        price: java.math.BigDecimal,
        categoryId: String?,
        trackInventory: Boolean
    ): Result<Product>
}

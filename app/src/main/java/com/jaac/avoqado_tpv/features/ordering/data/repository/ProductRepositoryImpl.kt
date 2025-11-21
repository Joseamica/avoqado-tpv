package com.jaac.avoqado_tpv.features.ordering.data.repository

import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.features.ordering.data.mappers.toDomain
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory
import com.jaac.avoqado_tpv.features.ordering.domain.ProductRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ProductRepositoryImpl - Implementation of ProductRepository
 *
 * Responsibilities:
 * - Fetch products from backend API (/api/v1/venues/{venueId}/products)
 * - Map DTOs to domain models
 * - Handle errors and return Result<T>
 * - Log all operations for debugging
 *
 * Backend Integration:
 * - Uses ApiService.getProducts() → GET /api/v1/venues/{venueId}/products
 * - Uses ApiService.getCategories() → GET /api/v1/venues/{venueId}/categories
 * - Backend returns products with nested category, inventory, modifierGroups
 *
 * Future Improvements (Phase 8):
 * - Local caching with Room database
 * - Offline support
 * - Real-time updates via Socket.IO
 */
@Singleton
class ProductRepositoryImpl @Inject constructor(
    private val apiService: ApiService
) : ProductRepository {

    override suspend fun getProducts(
        venueId: String,
        categoryId: String?
    ): Result<List<Product>> = withContext(Dispatchers.IO) {
        try {
            // 🔍 [DIAGNOSTIC] Log venueId used to fetch products
            Timber.i("🔍 [Products] Fetching for venueId=$venueId | category=${categoryId ?: "all"}")

            val response = apiService.getProducts(venueId, categoryId)

            if (response.isSuccessful && response.body() != null) {
                val productsResponse = response.body()!!
                val products = productsResponse.data.map { it.toDomain() }

                Timber.i("✅ Loaded ${products.size} products from backend")
                Timber.d("📦 Products: ${products.joinToString { it.name }}")

                Result.success(products)
            } else {
                val error = "Failed to load products: HTTP ${response.code()} - ${response.message()}"
                Timber.e("❌ $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error loading products")
            Result.failure(e)
        }
    }

    override suspend fun getCategories(
        venueId: String
    ): Result<List<ProductCategory>> = withContext(Dispatchers.IO) {
        try {
            Timber.d("📂 Extracting categories from products for venue: $venueId")

            // Get products first
            val productsResult = getProducts(venueId)

            productsResult.fold(
                onSuccess = { products ->
                    // Extract unique categories from products
                    val categoryMap = mutableMapOf<String, ProductCategory>()

                    products.forEach { product ->
                        if (!categoryMap.containsKey(product.categoryId)) {
                            categoryMap[product.categoryId] = ProductCategory(
                                id = product.categoryId,
                                name = product.categoryName,
                                displayOrder = 0,  // Will be set based on first product
                                productCount = 0,  // Will be calculated
                                emoji = product.emoji
                            )
                        }
                    }

                    // Calculate product counts
                    val categories = categoryMap.values.map { category ->
                        category.copy(
                            productCount = products.count { it.categoryId == category.id }
                        )
                    }.sortedBy { it.name }

                    Timber.i("✅ Extracted ${categories.size} unique categories from ${products.size} products")
                    Timber.d("📂 Categories: ${categories.joinToString { "${it.name} (${it.productCount})" }}")

                    Result.success(categories)
                },
                onFailure = { error ->
                    Timber.e(error, "❌ Failed to extract categories from products")
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error extracting categories")
            Result.failure(e)
        }
    }

    override suspend fun getProduct(
        venueId: String,
        productId: String
    ): Result<Product?> = withContext(Dispatchers.IO) {
        try {
            Timber.d("📦 Fetching product: $productId for venue: $venueId")

            // Load all products and find the specific one
            // TODO: When backend supports GET /venues/{venueId}/products/{productId}, use that instead
            val productsResult = getProducts(venueId)

            productsResult.fold(
                onSuccess = { products ->
                    val product = products.find { it.id == productId }
                    if (product != null) {
                        Timber.i("✅ Found product: ${product.name}")
                        Result.success(product)
                    } else {
                        Timber.w("⚠️ Product not found: $productId")
                        Result.success(null)
                    }
                },
                onFailure = { error ->
                    Timber.e(error, "❌ Error loading product")
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error loading product")
            Result.failure(e)
        }
    }
}

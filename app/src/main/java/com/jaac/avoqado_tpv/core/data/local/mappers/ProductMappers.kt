package com.jaac.avoqado_tpv.core.data.local.mappers

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jaac.avoqado_tpv.core.data.local.entities.ProductCategoryEntity
import com.jaac.avoqado_tpv.core.data.local.entities.ProductEntity
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierGroup
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory
import java.math.BigDecimal

/**
 * Mappers for Product entity ↔ domain conversions.
 *
 * **Purpose:** Convert between Room entities (persistence) and domain models (business logic).
 *
 * **Strategy:**
 * - Entity → Domain: Deserialize JSON, parse BigDecimal
 * - Domain → Entity: Serialize JSON, convert BigDecimal to string
 */

private val gson = Gson()

/**
 * Convert ProductEntity to Product domain model.
 *
 * @receiver ProductEntity from Room database
 * @return Product domain model
 */
fun ProductEntity.toDomain(): Product {
    // Deserialize modifierGroups from JSON
    val modifierGroups: List<ModifierGroup> = if (modifierGroupsJson.isBlank() || modifierGroupsJson == "[]") {
        emptyList()
    } else {
        val type = object : TypeToken<List<ModifierGroup>>() {}.type
        gson.fromJson(modifierGroupsJson, type)
    }

    return Product(
        id = productId,
        name = name,
        sku = sku,
        price = BigDecimal(price),
        categoryId = categoryId,
        categoryName = categoryName,
        description = description,
        emoji = emoji,
        imageUrl = imageUrl,
        available = available,
        displayOrder = displayOrder,
        trackInventory = trackInventory,
        inventoryMethod = inventoryMethod,
        availableQuantity = availableQuantity,
        modifierGroups = modifierGroups
    )
}

/**
 * Convert Product domain model to ProductEntity.
 *
 * @receiver Product domain model
 * @param venueId Venue ID (tenant isolation)
 * @param cachedAt Cache timestamp (Unix milliseconds)
 * @return ProductEntity for Room storage
 */
fun Product.toEntity(venueId: String, cachedAt: Long): ProductEntity {
    return ProductEntity(
        productId = id,
        venueId = venueId,
        name = name,
        sku = sku,
        price = price.toString(),
        categoryId = categoryId,
        categoryName = categoryName,
        description = description,
        emoji = emoji,
        imageUrl = imageUrl,
        available = available,
        displayOrder = displayOrder,
        trackInventory = trackInventory,
        inventoryMethod = inventoryMethod,
        availableQuantity = availableQuantity,
        modifierGroupsJson = gson.toJson(modifierGroups),
        cachedAt = cachedAt
    )
}

/**
 * Convert list of ProductEntity to list of Product domain models.
 *
 * @receiver List of ProductEntity from Room
 * @return List of Product domain models
 */
fun List<ProductEntity>.toDomain(): List<Product> {
    return map { it.toDomain() }
}

/**
 * Convert list of Product domain models to list of ProductEntity.
 *
 * @receiver List of Product domain models
 * @param venueId Venue ID (tenant isolation)
 * @param cachedAt Cache timestamp (Unix milliseconds)
 * @return List of ProductEntity for Room storage
 */
fun List<Product>.toEntities(venueId: String, cachedAt: Long): List<ProductEntity> {
    return map { it.toEntity(venueId, cachedAt) }
}

/**
 * Convert ProductCategoryEntity to ProductCategory domain model.
 *
 * @receiver ProductCategoryEntity from Room database
 * @return ProductCategory domain model
 */
fun ProductCategoryEntity.toDomain(): ProductCategory {
    return ProductCategory(
        id = categoryId,
        name = name,
        displayOrder = displayOrder,
        productCount = productCount,
        emoji = emoji
    )
}

/**
 * Convert ProductCategory domain model to ProductCategoryEntity.
 *
 * @receiver ProductCategory domain model
 * @param venueId Venue ID (tenant isolation)
 * @param cachedAt Cache timestamp (Unix milliseconds)
 * @return ProductCategoryEntity for Room storage
 */
fun ProductCategory.toEntity(venueId: String, cachedAt: Long): ProductCategoryEntity {
    return ProductCategoryEntity(
        categoryId = id,
        venueId = venueId,
        name = name,
        displayOrder = displayOrder,
        productCount = productCount,
        emoji = emoji,
        cachedAt = cachedAt
    )
}

/**
 * Convert list of ProductCategoryEntity to list of ProductCategory domain models.
 *
 * @receiver List of ProductCategoryEntity from Room
 * @return List of ProductCategory domain models
 */
fun List<ProductCategoryEntity>.toCategoryDomain(): List<ProductCategory> {
    return map { it.toDomain() }
}

/**
 * Convert list of ProductCategory domain models to list of ProductCategoryEntity.
 *
 * @receiver List of ProductCategory domain models
 * @param venueId Venue ID (tenant isolation)
 * @param cachedAt Cache timestamp (Unix milliseconds)
 * @return List of ProductCategoryEntity for Room storage
 */
fun List<ProductCategory>.toCategoryEntities(venueId: String, cachedAt: Long): List<ProductCategoryEntity> {
    return map { it.toEntity(venueId, cachedAt) }
}

package com.jaac.avoqado_tpv.features.ordering.data.mappers

import com.jaac.avoqado_tpv.features.ordering.data.dto.CategoryDto
import com.jaac.avoqado_tpv.features.ordering.data.dto.ModifierDto
import com.jaac.avoqado_tpv.features.ordering.data.dto.ModifierGroupDto
import com.jaac.avoqado_tpv.features.ordering.data.dto.ProductDto
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierType
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory
import com.jaac.avoqado_tpv.features.ordering.domain.ProductModifier
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierGroup
import java.math.BigDecimal

/**
 * Product Mappers
 *
 * Maps DTOs from backend API to domain models.
 * Handles:
 * - Price conversion (Double → BigDecimal)
 * - Nested object flattening (category.name, modifierGroups.group.modifiers)
 * - Emoji extraction from category
 * - ModifierType enum mapping
 */

/**
 * Map ProductDto to Product domain model
 *
 * ✅ TOAST PATTERN: Uses unified availableQuantity field for both QUANTITY and RECIPE
 */
fun ProductDto.toDomain(): Product {
    return Product(
        id = id,
        name = name,
        sku = sku,
        price = BigDecimal(price.toString()),
        categoryId = categoryId,
        categoryName = category.name,
        description = description,
        emoji = category.emoji ?: getCategoryEmojiByName(category.name),
        imageUrl = imageUrl,
        available = active && hasStock(),
        displayOrder = displayOrder,
        // Inventory tracking (Toast POS pattern - unified field)
        trackInventory = trackInventory,
        inventoryMethod = inventoryMethod,
        availableQuantity = availableQuantity  // ✅ Backend calculates for both QUANTITY and RECIPE
    )
}

/**
 * Map CategoryDto to ProductCategory domain model
 */
fun CategoryDto.toDomain(): ProductCategory {
    return ProductCategory(
        id = id,
        name = name,
        displayOrder = displayOrder ?: 0,
        productCount = 0,  // Will be calculated when loading products
        emoji = emoji ?: getCategoryEmojiByName(name)
    )
}

/**
 * Map ModifierDto to ProductModifier domain model
 */
fun ModifierDto.toDomain(type: ModifierType): ProductModifier {
    return ProductModifier(
        id = id,
        name = name,
        priceAdjustment = BigDecimal(priceAdjustment.toString()),
        type = type,
        required = false,  // Individual modifiers are not required, groups can be
        displayOrder = displayOrder
    )
}

/**
 * Map ModifierGroupDto to ModifierGroup domain model
 */
fun ModifierGroupDto.toDomain(): ModifierGroup {
    val modifierType = when (type.uppercase()) {
        "SINGLE_CHOICE" -> ModifierType.SINGLE_CHOICE
        "MULTIPLE_CHOICE" -> ModifierType.MULTIPLE_CHOICE
        else -> ModifierType.MULTIPLE_CHOICE  // Default fallback
    }

    return ModifierGroup(
        id = id,
        name = name,
        modifiers = modifiers?.map { it.toDomain(modifierType) } ?: emptyList(),
        type = modifierType,
        required = required,
        displayOrder = displayOrder
    )
}

/**
 * Check if product has stock (for inventory-tracked products)
 *
 * ✅ TOAST PATTERN: Uses unified availableQuantity field
 *
 * Logic:
 * - trackInventory = false → Always available (unlimited stock)
 * - trackInventory = true → Check availableQuantity > 0
 *   - QUANTITY: Backend returns inventory.currentStock
 *   - RECIPE: Backend calculates portions from ingredients
 * - No availableQuantity → Treat as available (backward compatibility)
 */
private fun ProductDto.hasStock(): Boolean {
    return if (trackInventory) {
        (availableQuantity ?: 0) > 0  // ✅ Works for both QUANTITY and RECIPE
    } else {
        true  // No tracking → unlimited stock
    }
}

/**
 * Get default emoji for category based on name
 *
 * Fallback when category doesn't have emoji in database.
 * Uses Spanish category names (Bebidas, Comidas, Postres).
 */
private fun getCategoryEmojiByName(categoryName: String): String {
    return when {
        categoryName.contains("bebida", ignoreCase = true) -> "🥤"
        categoryName.contains("comida", ignoreCase = true) -> "🍽️"
        categoryName.contains("postre", ignoreCase = true) -> "🍰"
        categoryName.contains("entrada", ignoreCase = true) -> "🥗"
        categoryName.contains("plato", ignoreCase = true) -> "🍽️"
        categoryName.contains("pizza", ignoreCase = true) -> "🍕"
        categoryName.contains("hamburguesa", ignoreCase = true) -> "🍔"
        categoryName.contains("taco", ignoreCase = true) -> "🌮"
        categoryName.contains("sushi", ignoreCase = true) -> "🍣"
        categoryName.contains("café", ignoreCase = true) -> "☕"
        categoryName.contains("cerveza", ignoreCase = true) -> "🍺"
        categoryName.contains("vino", ignoreCase = true) -> "🍷"
        else -> "🍽️"  // Default food emoji
    }
}

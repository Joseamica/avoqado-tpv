package com.jaac.avoqado_tpv.features.ordering.data.dto

import com.google.gson.annotations.SerializedName

/**
 * Product Data Transfer Object
 *
 * Maps backend Product model (Prisma) to Android DTO.
 * Backend returns products with nested category, modifierGroups, inventory.
 *
 * Backend structure (from product.dashboard.service.ts):
 * ```typescript
 * {
 *   id: string
 *   name: string
 *   description?: string
 *   price: number
 *   type: ProductType (SIMPLE | VARIANT)
 *   sku: string
 *   categoryId: string
 *   active: boolean
 *   displayOrder: number
 *   trackInventory: boolean
 *   inventoryMethod: 'QUANTITY' | 'RECIPE' | null
 *   imageUrl?: string
 *   category: { id, name, venueId }
 *   inventory?: { stockQuantity, lowStockThreshold }
 *   modifierGroups: [{ group: { id, name, modifiers: [...] } }]
 * }
 * ```
 */
data class ProductDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("description")
    val description: String?,

    @SerializedName("price")
    val price: Double,

    @SerializedName("type")
    val type: String,  // SIMPLE, VARIANT

    @SerializedName("sku")
    val sku: String,

    @SerializedName("categoryId")
    val categoryId: String,

    @SerializedName("active")
    val active: Boolean,

    @SerializedName("displayOrder")
    val displayOrder: Int,

    @SerializedName("trackInventory")
    val trackInventory: Boolean,

    @SerializedName("inventoryMethod")
    val inventoryMethod: String?,  // QUANTITY, RECIPE, null

    @SerializedName("imageUrl")
    val imageUrl: String?,

    @SerializedName("category")
    val category: CategoryDto,

    @SerializedName("inventory")
    val inventory: InventoryDto?,

    @SerializedName("modifierGroups")
    val modifierGroups: List<ProductModifierGroupDto>?,

    // ✅ TOAST PATTERN: Unified field for both QUANTITY and RECIPE tracking
    // Backend calculates this for both types:
    // - QUANTITY: Returns inventory.currentStock
    // - RECIPE: Calculates available portions from ingredients
    @SerializedName("availableQuantity")
    val availableQuantity: Int?
)

/**
 * Category DTO
 *
 * Maps to backend MenuCategory model.
 * Includes optional color for UI theming (Square/Toast pattern).
 */
data class CategoryDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("venueId")
    val venueId: String,

    @SerializedName("displayOrder")
    val displayOrder: Int? = 0,

    @SerializedName("emoji")
    val emoji: String? = null,

    // UI theming - hex color string (e.g., "#4CAF50")
    // Backend: MenuCategory.color (already exists in Prisma schema)
    @SerializedName("color")
    val color: String? = null
)

/**
 * Inventory DTO - Maps to Inventory table (for QUANTITY tracking)
 *
 * Backend Schema (Prisma):
 * - currentStock: Decimal (Postgres) → Double (JSON)
 * - reservedStock: Decimal → Double
 * - minimumStock: Decimal → Double
 * - maximumStock: Decimal? → Double?
 */
data class InventoryDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("currentStock")
    val currentStock: Double,

    @SerializedName("reservedStock")
    val reservedStock: Double,

    @SerializedName("minimumStock")
    val minimumStock: Double,

    @SerializedName("maximumStock")
    val maximumStock: Double?
)

/**
 * Product Modifier Group DTO (join table)
 */
data class ProductModifierGroupDto(
    @SerializedName("group")
    val group: ModifierGroupDto
)

/**
 * Modifier Group DTO
 */
data class ModifierGroupDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("type")
    val type: String?,  // SINGLE_CHOICE, MULTIPLE_CHOICE (nullable - backend may not set this)

    @SerializedName("required")
    val required: Boolean,

    @SerializedName("displayOrder")
    val displayOrder: Int,

    @SerializedName("modifiers")
    val modifiers: List<ModifierDto>?
)

/**
 * Modifier DTO
 *
 * Backend Modifier model has "price" field, not "priceAdjustment".
 * This represents the ABSOLUTE price of the modifier (e.g., $12.50),
 * not a relative adjustment to product price.
 */
data class ModifierDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("price")  // ✅ FIXED: Backend sends "price", not "priceAdjustment"
    val priceAdjustment: Double,  // Keep property name for compatibility with existing code

    @SerializedName("displayOrder")
    val displayOrder: Int
)

/**
 * Products Response wrapper
 *
 * Backend returns: { message: string, data: Product[], correlationId: string }
 */
data class ProductsResponse(
    @SerializedName("message")
    val message: String?,

    @SerializedName("data")
    val data: List<ProductDto>,

    @SerializedName("correlationId")
    val correlationId: String?
)

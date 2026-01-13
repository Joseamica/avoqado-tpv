package com.jaac.avoqado_tpv.features.ordering.domain

import androidx.compose.runtime.Immutable
import java.math.BigDecimal

/**
 * Product domain model
 *
 * Represents a menu item (food, beverage, dessert) available for ordering.
 * Backend integration: Loads from /api/v1/dashboard/venues/{venueId}/products
 *
 * Inventory Tracking:
 * - trackInventory = false → No tracking (unlimited stock)
 * - trackInventory = true, inventoryMethod = QUANTITY → Simple count (e.g., wine bottles)
 * - trackInventory = true, inventoryMethod = RECIPE → Recipe-based (e.g., burgers with ingredients)
 *
 * **Performance Optimization (2025-11-24):**
 * - @Immutable annotation tells Compose to skip recomposition when data hasn't changed
 * - Reduces unnecessary recompositions in ProductGrid
 */
@Immutable
data class Product(
    val id: String,
    val name: String,
    val sku: String,
    val price: BigDecimal,
    val categoryId: String,
    val categoryName: String,
    val description: String?,
    val emoji: String,  // Visual representation (🍕, 🥤, 🍰)
    val imageUrl: String?,  // Future: Real product images from backend
    val available: Boolean,
    val displayOrder: Int = 0,

    // Inventory tracking (Toast POS pattern - unified field)
    val trackInventory: Boolean = false,
    val inventoryMethod: String? = null,  // QUANTITY | RECIPE | null

    // ✅ TOAST PATTERN: availableQuantity works for both types:
    // - QUANTITY: Shows actual stock count (e.g., "10 bottles")
    // - RECIPE: Shows calculated portions (e.g., "15 burgers" from ingredients)
    val availableQuantity: Int? = null,

    // Modifier groups (for product customization)
    // Backend returns this in ProductDto.modifierGroups
    val modifierGroups: List<ModifierGroup> = emptyList(),

    // Category color for visual distinction (Square/Toast pattern)
    // Used to render subtle left border in ProductCard
    // If null, auto-generated from categoryId
    val categoryColor: String? = null,

    // Category active status (from dashboard toggle)
    // Used to filter out inactive categories when extracting from products
    val categoryIsActive: Boolean = true
) {
    /**
     * Convenience property: Formatted price for UI
     * Used in ProductCard: "$12.00"
     */
    val formattedPrice: String
        get() = String.format("$%.2f", price)

    /**
     * Convenience property: Is product currently orderable?
     */
    val canOrder: Boolean
        get() = available

    /**
     * Convenience property: Does product have modifiers?
     * Used to decide whether to show ProductSelectorBottomSheet or quick-add
     */
    val hasModifiers: Boolean
        get() = modifierGroups.isNotEmpty()

    /**
     * Convenience property: Effective category color for UI
     * Uses defined categoryColor if available, otherwise auto-generates from categoryId
     */
    val effectiveCategoryColor: String
        get() = categoryColor ?: CategoryColors.getAutoColor(categoryId)
}

/**
 * Product category
 *
 * **Category Colors (Square/Toast Pattern):**
 * - color: Optional hex color string (e.g., "#4CAF50")
 * - If null, use [CategoryColors.getAutoColor] for automatic color generation
 * - Colors are used for visual distinction in ProductCard (left border)
 */
data class ProductCategory(
    val id: String,
    val name: String,
    val displayOrder: Int,
    val productCount: Int,
    val emoji: String = "",
    val color: String? = null,  // Hex color (e.g., "#4CAF50") or null for auto-color
    val isActive: Boolean = true  // Active status from dashboard toggle
) {
    /**
     * Get the effective color for this category.
     * Uses defined color if available, otherwise generates one automatically.
     */
    val effectiveColor: String
        get() = color ?: CategoryColors.getAutoColor(id)

    companion object {
        val ALL = ProductCategory(
            id = "all",
            name = "Todos",
            displayOrder = 0,
            productCount = 0,
            emoji = "🍽️",
            color = null,  // No border for "All" category
            isActive = true
        )
    }
}

/**
 * Auto-generated category colors (Square/Toast pattern)
 *
 * Provides a consistent color palette for categories that don't have
 * a manually assigned color. Uses hash-based selection for consistency.
 *
 * **Palette:**
 * - 12 harmonious colors optimized for dark theme
 * - Each category always gets the same color (deterministic)
 * - Colors are vibrant but not overwhelming (suitable for subtle borders)
 */
object CategoryColors {
    // Palette optimized for dark theme (vibrant but not too bright)
    private val palette = listOf(
        "#4CAF50",  // Green
        "#2196F3",  // Blue
        "#FF9800",  // Orange
        "#9C27B0",  // Purple
        "#E91E63",  // Pink
        "#00BCD4",  // Cyan
        "#FFEB3B",  // Yellow
        "#795548",  // Brown
        "#607D8B",  // Blue Grey
        "#FF5722",  // Deep Orange
        "#3F51B5",  // Indigo
        "#8BC34A"   // Light Green
    )

    /**
     * Get auto-generated color for a category ID.
     * Always returns the same color for the same ID (deterministic).
     */
    fun getAutoColor(categoryId: String): String {
        if (categoryId == "all") return "#808080"  // Neutral grey for "All"
        val index = kotlin.math.abs(categoryId.hashCode()) % palette.size
        return palette[index]
    }
}

/**
 * Product modifier
 *
 * Structured options that customize a product (e.g., "Extra cheese +$20", "No onions")
 * Can have additional price or be free.
 */
data class ProductModifier(
    val id: String,
    val name: String,
    val priceAdjustment: BigDecimal,  // 0 = free, positive = extra charge
    val type: ModifierType,
    val required: Boolean = false,  // If true, customer MUST select one option from group
    val displayOrder: Int = 0
) {
    val formattedPrice: String
        get() = when {
            priceAdjustment == BigDecimal.ZERO -> "Gratis"
            priceAdjustment > BigDecimal.ZERO -> String.format("+$%.2f", priceAdjustment)
            else -> String.format("-$%.2f", priceAdjustment.abs())  // Discount (rare)
        }
}

/**
 * Modifier type
 */
enum class ModifierType {
    SINGLE_CHOICE,  // Radio buttons (e.g., "Término: Rojo/Medio/Bien")
    MULTIPLE_CHOICE  // Checkboxes (e.g., "Extras: Queso/Tocino/Aguacate")
}

/**
 * Modifier group (e.g., "Término", "Extras", "Guarnición")
 *
 * Selection rules:
 * - required: If true, at least 1 selection is required
 * - minSelections: Minimum selections required (overrides required if set)
 * - maxSelections: Maximum selections allowed (null = unlimited)
 *
 * Examples:
 * - Término (single choice, required): minSelections=1, maxSelections=1
 * - Extras (multiple choice, optional): minSelections=null, maxSelections=null
 * - Toppings (multiple choice, pick 2-3): minSelections=2, maxSelections=3
 */
data class ModifierGroup(
    val id: String,
    val name: String,
    val modifiers: List<ProductModifier>,
    val type: ModifierType,
    val required: Boolean = false,
    val displayOrder: Int = 0,
    val minSelections: Int? = null,  // null = use 'required' logic (0 or 1)
    val maxSelections: Int? = null   // null = unlimited
) {
    /**
     * Effective minimum selections considering both 'required' and 'minSelections'
     */
    val effectiveMinSelections: Int
        get() = minSelections ?: if (required) 1 else 0

    /**
     * Effective maximum selections (null = unlimited)
     */
    val effectiveMaxSelections: Int?
        get() = maxSelections
}

/**
 * Mock product data for rapid UI development
 *
 * ⚠️ TEMPORARY: Replace with ProductRepository in Phase 8
 *
 * Contains 15 realistic restaurant products:
 * - 5 Bebidas (Beverages)
 * - 5 Comidas (Food)
 * - 5 Postres (Desserts)
 */
object MockProducts {

    // Category definitions
    val categories = listOf(
        ProductCategory(
            id = "cat_bebidas",
            name = "Bebidas",
            displayOrder = 1,
            productCount = 5,
            emoji = "🥤"
        ),
        ProductCategory(
            id = "cat_comidas",
            name = "Comidas",
            displayOrder = 2,
            productCount = 5,
            emoji = "🍽️"
        ),
        ProductCategory(
            id = "cat_postres",
            name = "Postres",
            displayOrder = 3,
            productCount = 5,
            emoji = "🍰"
        )
    )

    // Bebidas (Beverages)
    private val bebidas = listOf(
        Product(
            id = "prod_bebida_1",
            name = "Coca-Cola",
            sku = "BEB-001",
            price = BigDecimal("35.00"),
            categoryId = "cat_bebidas",
            categoryName = "Bebidas",
            description = "Refresco de cola 600ml",
            emoji = "🥤",
            imageUrl = null,
            available = true,
            displayOrder = 1
        ),
        Product(
            id = "prod_bebida_2",
            name = "Agua Natural",
            sku = "BEB-002",
            price = BigDecimal("25.00"),
            categoryId = "cat_bebidas",
            categoryName = "Bebidas",
            description = "Agua purificada 500ml",
            emoji = "💧",
            imageUrl = null,
            available = true,
            displayOrder = 2
        ),
        Product(
            id = "prod_bebida_3",
            name = "Cerveza Corona",
            sku = "BEB-003",
            price = BigDecimal("50.00"),
            categoryId = "cat_bebidas",
            categoryName = "Bebidas",
            description = "Cerveza clara 355ml",
            emoji = "🍺",
            imageUrl = null,
            available = true,
            displayOrder = 3
        ),
        Product(
            id = "prod_bebida_4",
            name = "Jugo de Naranja",
            sku = "BEB-004",
            price = BigDecimal("40.00"),
            categoryId = "cat_bebidas",
            categoryName = "Bebidas",
            description = "Jugo natural recién exprimido",
            emoji = "🍊",
            imageUrl = null,
            available = true,
            displayOrder = 4
        ),
        Product(
            id = "prod_bebida_5",
            name = "Café Americano",
            sku = "BEB-005",
            price = BigDecimal("30.00"),
            categoryId = "cat_bebidas",
            categoryName = "Bebidas",
            description = "Café americano caliente",
            emoji = "☕",
            imageUrl = null,
            available = true,
            displayOrder = 5
        )
    )

    // Comidas (Food)
    private val comidas = listOf(
        Product(
            id = "prod_comida_1",
            name = "Pizza Margherita",
            sku = "COM-001",
            price = BigDecimal("180.00"),
            categoryId = "cat_comidas",
            categoryName = "Comidas",
            description = "Pizza con tomate, mozzarella y albahaca",
            emoji = "🍕",
            imageUrl = null,
            available = true,
            displayOrder = 1
        ),
        Product(
            id = "prod_comida_2",
            name = "Hamburguesa Clásica",
            sku = "COM-002",
            price = BigDecimal("150.00"),
            categoryId = "cat_comidas",
            categoryName = "Comidas",
            description = "Hamburguesa con queso, lechuga, tomate",
            emoji = "🍔",
            imageUrl = null,
            available = true,
            displayOrder = 2
        ),
        Product(
            id = "prod_comida_3",
            name = "Ensalada César",
            sku = "COM-003",
            price = BigDecimal("120.00"),
            categoryId = "cat_comidas",
            categoryName = "Comidas",
            description = "Ensalada con pollo, crutones, parmesano",
            emoji = "🥗",
            imageUrl = null,
            available = true,
            displayOrder = 3
        ),
        Product(
            id = "prod_comida_4",
            name = "Tacos al Pastor",
            sku = "COM-004",
            price = BigDecimal("95.00"),
            categoryId = "cat_comidas",
            categoryName = "Comidas",
            description = "3 tacos con carne al pastor, piña, cilantro",
            emoji = "🌮",
            imageUrl = null,
            available = true,
            displayOrder = 4
        ),
        Product(
            id = "prod_comida_5",
            name = "Pasta Alfredo",
            sku = "COM-005",
            price = BigDecimal("165.00"),
            categoryId = "cat_comidas",
            categoryName = "Comidas",
            description = "Pasta con salsa alfredo cremosa",
            emoji = "🍝",
            imageUrl = null,
            available = true,
            displayOrder = 5
        )
    )

    // Postres (Desserts)
    private val postres = listOf(
        Product(
            id = "prod_postre_1",
            name = "Tiramisú",
            sku = "POS-001",
            price = BigDecimal("80.00"),
            categoryId = "cat_postres",
            categoryName = "Postres",
            description = "Postre italiano con café y mascarpone",
            emoji = "🍰",
            imageUrl = null,
            available = true,
            displayOrder = 1
        ),
        Product(
            id = "prod_postre_2",
            name = "Helado de Vainilla",
            sku = "POS-002",
            price = BigDecimal("60.00"),
            categoryId = "cat_postres",
            categoryName = "Postres",
            description = "2 bolas de helado de vainilla",
            emoji = "🍨",
            imageUrl = null,
            available = true,
            displayOrder = 2
        ),
        Product(
            id = "prod_postre_3",
            name = "Brownie con Helado",
            sku = "POS-003",
            price = BigDecimal("70.00"),
            categoryId = "cat_postres",
            categoryName = "Postres",
            description = "Brownie de chocolate caliente con helado",
            emoji = "🧁",
            imageUrl = null,
            available = true,
            displayOrder = 3
        ),
        Product(
            id = "prod_postre_4",
            name = "Flan Napolitano",
            sku = "POS-004",
            price = BigDecimal("55.00"),
            categoryId = "cat_postres",
            categoryName = "Postres",
            description = "Flan de vainilla con caramelo",
            emoji = "🍮",
            imageUrl = null,
            available = true,
            displayOrder = 4
        ),
        Product(
            id = "prod_postre_5",
            name = "Pay de Limón",
            sku = "POS-005",
            price = BigDecimal("65.00"),
            categoryId = "cat_postres",
            categoryName = "Postres",
            description = "Pay de limón con merengue",
            emoji = "🥧",
            imageUrl = null,
            available = true,
            displayOrder = 5
        )
    )

    /**
     * All products (15 total)
     */
    val allProducts: List<Product> = bebidas + comidas + postres

    /**
     * Get products by category
     */
    fun getProductsByCategory(categoryId: String): List<Product> {
        return when (categoryId) {
            "all" -> allProducts
            "cat_bebidas" -> bebidas
            "cat_comidas" -> comidas
            "cat_postres" -> postres
            else -> emptyList()
        }
    }

    /**
     * Get product by ID
     */
    fun getProductById(productId: String): Product? {
        return allProducts.find { it.id == productId }
    }

    /**
     * Search products by name
     */
    fun searchProducts(query: String): List<Product> {
        if (query.isBlank()) return allProducts
        val lowerQuery = query.lowercase()
        return allProducts.filter { product ->
            product.name.lowercase().contains(lowerQuery) ||
            product.description?.lowercase()?.contains(lowerQuery) == true
        }
    }

    /**
     * Mock modifiers for products
     *
     * ⚠️ TEMPORARY: Replace with backend API in Phase 8
     */
    fun getModifiersForProduct(productId: String): List<ModifierGroup> {
        return when (productId) {
            "prod_comida_1" -> listOf(  // Pizza Margherita
                ModifierGroup(
                    id = "group_pizza_extras",
                    name = "Extras",
                    type = ModifierType.MULTIPLE_CHOICE,
                    modifiers = listOf(
                        ProductModifier(
                            id = "mod_extra_queso",
                            name = "Extra queso",
                            priceAdjustment = BigDecimal("20.00"),
                            type = ModifierType.MULTIPLE_CHOICE
                        ),
                        ProductModifier(
                            id = "mod_sin_cebolla",
                            name = "Sin cebolla",
                            priceAdjustment = BigDecimal.ZERO,
                            type = ModifierType.MULTIPLE_CHOICE
                        ),
                        ProductModifier(
                            id = "mod_orilla_rellena",
                            name = "Orilla rellena",
                            priceAdjustment = BigDecimal("30.00"),
                            type = ModifierType.MULTIPLE_CHOICE
                        ),
                        ProductModifier(
                            id = "mod_doble_queso",
                            name = "Doble queso",
                            priceAdjustment = BigDecimal("40.00"),
                            type = ModifierType.MULTIPLE_CHOICE
                        )
                    )
                )
            )
            "prod_comida_2" -> listOf(  // Hamburguesa Clásica
                ModifierGroup(
                    id = "group_burger_extras",
                    name = "Extras",
                    type = ModifierType.MULTIPLE_CHOICE,
                    modifiers = listOf(
                        ProductModifier(
                            id = "mod_tocino",
                            name = "Tocino",
                            priceAdjustment = BigDecimal("25.00"),
                            type = ModifierType.MULTIPLE_CHOICE
                        ),
                        ProductModifier(
                            id = "mod_aguacate",
                            name = "Aguacate",
                            priceAdjustment = BigDecimal("20.00"),
                            type = ModifierType.MULTIPLE_CHOICE
                        ),
                        ProductModifier(
                            id = "mod_sin_cebolla_burger",
                            name = "Sin cebolla",
                            priceAdjustment = BigDecimal.ZERO,
                            type = ModifierType.MULTIPLE_CHOICE
                        )
                    )
                ),
                ModifierGroup(
                    id = "group_burger_termino",
                    name = "Término",
                    type = ModifierType.SINGLE_CHOICE,
                    required = true,
                    modifiers = listOf(
                        ProductModifier(
                            id = "mod_termino_rojo",
                            name = "Rojo",
                            priceAdjustment = BigDecimal.ZERO,
                            type = ModifierType.SINGLE_CHOICE
                        ),
                        ProductModifier(
                            id = "mod_termino_medio",
                            name = "Medio",
                            priceAdjustment = BigDecimal.ZERO,
                            type = ModifierType.SINGLE_CHOICE
                        ),
                        ProductModifier(
                            id = "mod_termino_bien",
                            name = "Bien cocido",
                            priceAdjustment = BigDecimal.ZERO,
                            type = ModifierType.SINGLE_CHOICE
                        )
                    )
                )
            )
            "prod_bebida_1" -> listOf(  // Coca-Cola
                ModifierGroup(
                    id = "group_bebida_opciones",
                    name = "Opciones",
                    type = ModifierType.MULTIPLE_CHOICE,
                    modifiers = listOf(
                        ProductModifier(
                            id = "mod_sin_hielo",
                            name = "Sin hielo",
                            priceAdjustment = BigDecimal.ZERO,
                            type = ModifierType.MULTIPLE_CHOICE
                        ),
                        ProductModifier(
                            id = "mod_extra_hielo",
                            name = "Extra hielo",
                            priceAdjustment = BigDecimal.ZERO,
                            type = ModifierType.MULTIPLE_CHOICE
                        )
                    )
                )
            )
            else -> emptyList()  // No modifiers for this product
        }
    }
}

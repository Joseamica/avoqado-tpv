package com.jaac.avoqado_tpv.features.ordering.domain

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
 */
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
    val modifierGroups: List<ModifierGroup> = emptyList()
) {
    /**
     * Convenience property: Formatted price for UI
     * Used in ProductCard: "$12.00"
     */
    val formattedPrice: String
        get() = "$$price"

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
}

/**
 * Product category
 */
data class ProductCategory(
    val id: String,
    val name: String,
    val displayOrder: Int,
    val productCount: Int,
    val emoji: String = ""
) {
    companion object {
        val ALL = ProductCategory(
            id = "all",
            name = "Todos",
            displayOrder = 0,
            productCount = 0,
            emoji = "🍽️"
        )
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
            priceAdjustment > BigDecimal.ZERO -> "+$$priceAdjustment"
            else -> "-$$priceAdjustment"  // Discount (rare)
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
 */
data class ModifierGroup(
    val id: String,
    val name: String,
    val modifiers: List<ProductModifier>,
    val type: ModifierType,
    val required: Boolean = false,
    val displayOrder: Int = 0
)

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

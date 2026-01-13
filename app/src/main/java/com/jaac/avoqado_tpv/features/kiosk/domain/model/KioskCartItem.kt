package com.jaac.avoqado_tpv.features.kiosk.domain.model

import com.jaac.avoqado_tpv.features.ordering.domain.ModifierGroup
import com.jaac.avoqado_tpv.features.ordering.domain.ProductModifier
import java.math.BigDecimal

/**
 * Cart item for kiosk mode
 *
 * Lightweight model for in-memory cart management.
 * Not persisted to database - cart is cleared on session end.
 *
 * @property selectedModifiers List of modifiers selected for this item (e.g., "Término: Medio", "Extra queso")
 */
data class KioskCartItem(
    val productId: String,
    val productName: String,
    val unitPrice: BigDecimal,  // Base price + modifier adjustments
    val quantity: Int = 1,
    val imageUrl: String? = null,
    val selectedModifiers: List<ProductModifier> = emptyList()
) {
    /**
     * Calculate line total (unitPrice * quantity)
     */
    val lineTotal: BigDecimal
        get() = unitPrice.multiply(BigDecimal(quantity))

    /**
     * Summary of selected modifiers for display in cart UI
     * e.g., "Medio, Extra queso, Tocino"
     */
    val modifiersSummary: String
        get() = selectedModifiers.joinToString(", ") { it.name }

    /**
     * Check if this item has any selected modifiers
     */
    val hasSelectedModifiers: Boolean
        get() = selectedModifiers.isNotEmpty()
}

/**
 * Simple product model for kiosk display
 *
 * @property modifierGroups List of modifier groups from backend (e.g., "Término", "Extras")
 */
data class KioskProduct(
    val id: String,
    val name: String,
    val price: BigDecimal,
    val categoryId: String?,
    val imageUrl: String? = null,
    val isAvailable: Boolean = true,
    val modifierGroups: List<ModifierGroup> = emptyList()
) {
    /**
     * Check if product has any modifier groups
     * Used to decide whether to show KioskModifierDialog or quick-add to cart
     */
    val hasModifiers: Boolean
        get() = modifierGroups.isNotEmpty()

    /**
     * Check if product has any required modifier groups
     * If true, user MUST select modifiers before adding to cart
     */
    val hasRequiredModifiers: Boolean
        get() = modifierGroups.any { it.required }
}

/**
 * Category model for kiosk filter bar
 */
data class KioskCategory(
    val id: String,
    val name: String,
    val sortOrder: Int = 0
)

/**
 * Kiosk UI State
 */
data class KioskState(
    val isLoading: Boolean = true,
    val products: List<KioskProduct> = emptyList(),
    val categories: List<KioskCategory> = emptyList(),
    val selectedCategoryId: String? = null,
    val error: String? = null,
    // 🔧 Blumon SDK initialization state
    val isBlumonInitializing: Boolean = true,  // True while SDK is initializing
    val isBlumonReady: Boolean = false,        // True when SDK is ready for payments
    val blumonInitError: String? = null        // Error message if initialization failed
) {
    /**
     * Products filtered by selected category
     */
    val filteredProducts: List<KioskProduct>
        get() = if (selectedCategoryId == null) {
            products.filter { it.isAvailable }
        } else {
            products.filter { it.categoryId == selectedCategoryId && it.isAvailable }
        }

    /**
     * All available products (for search - no category filter)
     */
    val allProducts: List<KioskProduct>
        get() = products.filter { it.isAvailable }
}

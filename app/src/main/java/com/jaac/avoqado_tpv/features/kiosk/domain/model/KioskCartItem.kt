package com.jaac.avoqado_tpv.features.kiosk.domain.model

import java.math.BigDecimal

/**
 * Cart item for kiosk mode
 *
 * Lightweight model for in-memory cart management.
 * Not persisted to database - cart is cleared on session end.
 */
data class KioskCartItem(
    val productId: String,
    val productName: String,
    val unitPrice: BigDecimal,
    val quantity: Int = 1,
    val imageUrl: String? = null
) {
    /**
     * Calculate line total (unitPrice * quantity)
     */
    val lineTotal: BigDecimal
        get() = unitPrice.multiply(BigDecimal(quantity))
}

/**
 * Simple product model for kiosk display
 */
data class KioskProduct(
    val id: String,
    val name: String,
    val price: BigDecimal,
    val categoryId: String?,
    val imageUrl: String? = null,
    val isAvailable: Boolean = true
)

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
    val error: String? = null
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
}

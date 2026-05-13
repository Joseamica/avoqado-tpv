package com.jaac.avoqado_tpv.features.checkout.domain.model

/**
 * Snapshot of a cart in the "saved for later" list. Persisted to
 * SharedPreferences by `SavedCartsRepository` via Gson.
 *
 * Distinct from a `DraftOrderEntity` (Room) — those represent in-progress
 * orders that may already be visible in the kitchen. A SavedCart is purely
 * local, never reaches the backend, and is intended for "set aside this
 * order, take the next customer" scenarios.
 *
 * Money is held in integer cents to match [CartItem]. The flattened discount
 * snapshot stores the BigDecimal value as a plain string so Gson can
 * round-trip it without custom type adapters.
 */
data class SavedCart(
    val id: String,
    val name: String,
    val items: List<SavedCartItem>,
    val orderDiscount: SavedDiscount? = null,
    val manualDiscountCents: Int = 0,
    val manualDiscountReason: String? = null,
    val orderNote: String? = null,
    val orderTaxPercent: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val itemCount: Int get() = items.sumOf { it.quantity }
}

data class SavedCartItem(
    val productId: String?,
    val name: String,
    val unitPriceCents: Int,
    val quantity: Int,
    val modifiers: List<SelectedModifier> = emptyList(),
    val note: String? = null,
    val isCortesia: Boolean = false,
    val cortesiaReason: String? = null,
    val priceAdjustmentCents: Int? = null,
    val itemDiscountId: String? = null,
)

/**
 * Minimal flattened snapshot of a TPV `Discount` for persistence. Values are
 * stored as plain strings so Gson can round-trip the BigDecimal without
 * needing a custom type adapter; the repository rehydrates them when loading.
 */
data class SavedDiscount(
    val id: String,
    val name: String,
    val typeName: String,    // "PERCENTAGE" | "FIXED"
    val value: String,        // BigDecimal serialized as plain string
)

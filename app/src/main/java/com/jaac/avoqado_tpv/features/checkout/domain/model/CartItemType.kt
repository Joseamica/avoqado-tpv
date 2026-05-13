package com.jaac.avoqado_tpv.features.checkout.domain.model

/**
 * Discriminates between cart items that reference a catalog product vs a
 * one-off "custom amount" entry (e.g. typing $150 on the keypad).
 *
 * - [ProductItem]: came from the catalog. `productId` is required so the order
 *   can later be reconciled against inventory and analytics.
 * - [CustomAmount]: a free-form amount with no catalog linkage. Used by the
 *   Teclado tab to support the legacy "Pago Rápido" flow inside the unified
 *   cart.
 */
sealed class CartItemType {
    data class ProductItem(val productId: String) : CartItemType()
    data object CustomAmount : CartItemType()
}

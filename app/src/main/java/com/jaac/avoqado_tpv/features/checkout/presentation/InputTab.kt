package com.jaac.avoqado_tpv.features.checkout.presentation

/**
 * The four input methods on the unified Checkout screen.
 *
 * Ported 1:1 from avoqado-android `checkout/CheckoutScreen.kt:78-83`.
 *
 * - [KEYPAD]: numeric pad for custom amounts (covers the legacy "Pago Rápido"
 *   use case).
 * - [SHORTCUTS]: configurable grid of frequently-used products or actions.
 * - [PRODUCTS]: full catalog grid.
 * - [MOSAIC]: configuration screen to populate the Shortcuts grid.
 */
enum class InputTab(val label: String) {
    KEYPAD("Teclado"),
    SHORTCUTS("Shortcuts"),
    PRODUCTS("Todos los productos"),
    MOSAIC("Configurar"),
}

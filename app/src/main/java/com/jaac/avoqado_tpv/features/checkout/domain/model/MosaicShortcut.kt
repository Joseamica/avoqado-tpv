package com.jaac.avoqado_tpv.features.checkout.domain.model

import androidx.compose.runtime.Immutable

/**
 * A configured shortcut tile in the "Shortcuts" tab of the Checkout screen.
 *
 * For Phase 3 a shortcut always points at a product from the catalog. Future
 * iterations may expand to discount shortcuts or cortesía shortcuts (already
 * supported in avoqado-android), but those add UI complexity that doesn't
 * fit in this ola.
 *
 * `position` is venue-scoped and unique — the grid renders shortcuts in
 * ascending position. The UI fills empty slots with "+" tiles in
 * `MosaicConfigView`.
 */
@Immutable
data class MosaicShortcut(
    val id: String,
    val venueId: String,
    val productId: String,
    val position: Int,
    val label: String,
    val colorHex: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

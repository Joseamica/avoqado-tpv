package com.jaac.avoqado_tpv.features.checkout.domain.model

import androidx.compose.runtime.Immutable

/**
 * A modifier that has been selected for a CartItem.
 *
 * Ported from avoqado-android `pos/data/model/SelectedModifier.kt`. Mirrors the
 * shape used when serializing a SavedCart so the same data can travel between
 * the active in-memory cart and the persisted "saved carts" feature without
 * needing additional mapping. Gson handles serialization automatically.
 */
@Immutable
data class SelectedModifier(
    val groupId: String,
    val groupName: String,
    val modifierId: String,
    val modifierName: String,
    val priceInCents: Int,
)

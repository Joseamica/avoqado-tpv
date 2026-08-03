package com.jaac.avoqado_tpv.features.tables.domain.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Producto del catálogo para `TableMenuScreen` (Plan C, Task 7) — mapeado de
 * `ProductCatalogDto` (`data/api/dto/MenuCatalogDto.kt`). Pesos (major units),
 * `BigDecimal` — NUNCA `Double` (ver `critical-warnings.md`, "Money = Decimal,
 * Never Float"; P1 real de este módulo con la suma 0.10+0.20+0.05).
 */
data class MenuProduct(
    val id: String,
    val name: String,
    val price: BigDecimal = BigDecimal.ZERO,
    val categoryId: String? = null,
    val imageUrl: String? = null,
    val color: String? = null,
    val active: Boolean = true,
    val modifierGroups: List<MenuModifierGroup> = emptyList(),
) {
    val hasModifiers: Boolean get() = modifierGroups.isNotEmpty()
    val priceDisplay: String get() = "$${price.setScale(2, RoundingMode.HALF_UP).toPlainString()}"
}

data class MenuModifierGroup(
    val id: String,
    val name: String,
    /** SINGLE_CHOICE | MULTIPLE_CHOICE. Un valor desconocido se trata como MULTIPLE_CHOICE (checkboxes). */
    val type: String? = null,
    val required: Boolean = false,
    val modifiers: List<MenuModifier> = emptyList(),
) {
    val isSingleChoice: Boolean get() = type == "SINGLE_CHOICE"
}

data class MenuModifier(
    val id: String,
    val name: String,
    val price: BigDecimal = BigDecimal.ZERO,
)

/** `GET /tpv/venues/{venueId}/categories` — filtro de categorías del grid. */
data class MenuCategory(
    val id: String,
    val name: String,
    val displayOrder: Int = 0,
    val color: String? = null,
    val active: Boolean = true,
)

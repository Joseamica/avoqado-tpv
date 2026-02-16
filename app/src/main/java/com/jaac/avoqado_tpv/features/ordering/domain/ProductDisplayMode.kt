package com.jaac.avoqado_tpv.features.ordering.domain

/**
 * Product catalog visualization modes for Menu tab.
 *
 * Pattern inspired by modern POS systems:
 * - Dense grid for fast tapping
 * - Medium grid for readability
 * - List for detailed scanning (name, SKU, price, stock)
 */
enum class ProductDisplayMode(
    val preferenceValue: String
) {
    GRID_3("grid_3"),
    GRID_2("grid_2"),
    LIST("list");

    companion object {
        fun fromPreference(value: String?): ProductDisplayMode {
            return values().firstOrNull { it.preferenceValue == value } ?: GRID_3
        }
    }
}

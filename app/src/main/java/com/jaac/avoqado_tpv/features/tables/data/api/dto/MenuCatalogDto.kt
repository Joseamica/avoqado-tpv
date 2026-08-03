package com.jaac.avoqado_tpv.features.tables.data.api.dto

import com.google.gson.annotations.SerializedName
import java.math.BigDecimal

/**
 * Catálogo de productos/categorías para `TableMenuScreen` (Plan C, Task 7) —
 * `GET /tpv/venues/{venueId}/products` y `GET /tpv/venues/{venueId}/categories`
 * (`menu.tpv.controller.ts`, verificado 2026-07-29 contra `tpv.routes.ts`).
 *
 * DTOs PROPIOS de `features/tables/` — a propósito NO se reusa
 * `features/ordering/data/dto/ProductDto.kt` (regla dura del plan: Mesas no
 * importa de `ordering`). Además ese `ProductDto.price` es `Double`; aquí es
 * `BigDecimal` (P1 de hoy: "Money in the Mesas domain is BigDecimal — never
 * Double, ever"). El shape JSON es el MISMO (`product.dashboard.service.ts`),
 * solo cambia el tipo Kotlin que Gson llena.
 */
data class ProductCatalogDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("price") val price: BigDecimal = BigDecimal.ZERO,
    @SerializedName("categoryId") val categoryId: String? = null,
    @SerializedName("active") val active: Boolean = true,
    @SerializedName("displayOrder") val displayOrder: Int = 0,
    @SerializedName("imageUrl") val imageUrl: String? = null,
    @SerializedName("color") val color: String? = null,
    @SerializedName("modifierGroups") val modifierGroups: List<ProductModifierGroupCatalogDto>? = null,
)

data class ProductModifierGroupCatalogDto(
    @SerializedName("group") val group: ModifierGroupCatalogDto,
)

data class ModifierGroupCatalogDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("type") val type: String? = null, // SINGLE_CHOICE | MULTIPLE_CHOICE
    @SerializedName("required") val required: Boolean = false,
    @SerializedName("displayOrder") val displayOrder: Int = 0,
    @SerializedName("modifiers") val modifiers: List<ModifierCatalogDto>? = null,
)

data class ModifierCatalogDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("price") val price: BigDecimal = BigDecimal.ZERO,
    @SerializedName("displayOrder") val displayOrder: Int = 0,
)

/** `{ message, data, correlationId }` — `getProductsHandler` reexportado tal cual bajo `/tpv`. */
data class ProductsCatalogResponse(
    @SerializedName("message") val message: String? = null,
    @SerializedName("data") val data: List<ProductCatalogDto> = emptyList(),
    @SerializedName("correlationId") val correlationId: String? = null,
)

/**
 * `GET /tpv/venues/{venueId}/categories` — arreglo PLANO (sin sobre), a
 * diferencia de `/products` — ver KDoc de `menu.tpv.controller.ts::getCategories`.
 */
data class CategoryCatalogDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("venueId") val venueId: String? = null,
    @SerializedName("displayOrder") val displayOrder: Int? = 0,
    @SerializedName("color") val color: String? = null,
    @SerializedName("active") val active: Boolean = true,
)

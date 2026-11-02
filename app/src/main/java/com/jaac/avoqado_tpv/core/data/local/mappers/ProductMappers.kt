package com.jaac.avoqado_tpv.core.data.local.mappers

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.jaac.avoqado_tpv.core.data.network.variantes.OpcionDeVarianteDto
import com.jaac.avoqado_tpv.core.data.network.variantes.OpcionesDeVarianteTolerantes
import com.jaac.avoqado_tpv.core.data.network.variantes.datosDeFamiliaDeDto
import com.jaac.avoqado_tpv.core.domain.variantes.DatosDeFamilia
import com.jaac.avoqado_tpv.core.data.local.entities.ProductCategoryEntity
import com.jaac.avoqado_tpv.core.data.local.entities.ProductEntity
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierGroup
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductCategory
import com.jaac.avoqado_tpv.features.ordering.domain.ProductModifier
import timber.log.Timber
import java.lang.reflect.Type
import java.math.BigDecimal

/**
 * Mappers for Product entity ↔ domain conversions.
 *
 * **Purpose:** Convert between Room entities (persistence) and domain models (business logic).
 *
 * **Strategy:**
 * - Entity → Domain: Deserialize JSON, parse BigDecimal
 * - Domain → Entity: Serialize JSON, convert BigDecimal to string
 */

private val gson = Gson()

/**
 * `variant_options_json` → opciones, con LA MISMA regla que el payload de la red (el adaptador tolerante): lo que no sea un arreglo de
 * objetos válidos, o un JSON roto, es `null` y el producto se pinta suelto. Nunca lanza: una fila rara no puede tumbar el catálogo.
 */
private fun opcionesDeJson(json: String?): List<OpcionDeVarianteDto>? =
    if (json.isNullOrBlank()) null
    else runCatching { OpcionesDeVarianteTolerantes().deserialize(JsonParser.parseString(json), null, null) }.getOrNull()

/** Opciones → JSON con los nombres de red (`@SerializedName` del DTO): sobreviven a R8, a diferencia de los campos del dominio. */
private fun opcionesAJson(f: DatosDeFamilia): String? =
    f.opciones?.map { OpcionDeVarianteDto(it.name, it.value, it.namePosition, it.valuePosition) }?.let(gson::toJson)

private val tipoDeGruposDeExtras = object : TypeToken<List<ModifierGroup>>() {}.type
private val tipoDeExtras = object : TypeToken<List<ProductModifier>>() {}.type

/**
 * JSON de extras guardado en la terminal → lista. Lee los nombres reales y los que R8 ponía antes de la v43 (los `alternate` de
 * [ModifierGroup] y [ProductModifier]). Nunca lanza: un JSON dañado de verdad sale como SIN extras en vez de tumbar a quien lo lee.
 * Gson no lanza con nombres desconocidos: deja en null campos que Kotlin da por no nulos y el que tronaría es la pantalla (o la
 * sincronización mandaría `modifierIds=[null]`), después. Por eso se revisan aquí.
 */
private fun <T> extrasSinTronar(json: String, tipo: Type, completo: (T) -> Boolean, dueno: String): List<T> {
    if (json.isBlank() || json == "[]") return emptyList()
    return runCatching {
        val lista: List<T> = gson.fromJson(json, tipo)
        check(lista.all(completo)) { "extra incompleto" }
        lista
    }.getOrElse { e ->
        Timber.w(e, "⚠️ [Extras guardados] Ilegibles en $dueno: sale sin extras")
        emptyList()
    }
}

/** `draft_order_items.modifiers` → extras del renglón, con la misma regla que el catálogo guardado. */
internal fun extrasDeRenglon(json: String, dueno: String): List<ProductModifier> =
    extrasSinTronar(json, tipoDeExtras, { it.estaCompleto() }, dueno)

private fun ProductEntity.gruposDeExtras(): List<ModifierGroup> =
    extrasSinTronar(modifierGroupsJson, tipoDeGruposDeExtras, { it.estaCompleto() }, "el producto $productId")

// `listOf<Any?>` a propósito: el compilador da estos campos por no nulos, pero Gson sí los deja en null. Un elemento null de la lista
// lanza aquí dentro, y también cuenta como dañado.
private fun ProductModifier.estaCompleto(): Boolean = listOf<Any?>(id, name, priceAdjustment, type).none { it == null }

private fun ModifierGroup.estaCompleto(): Boolean =
    listOf<Any?>(id, name, modifiers, type).none { it == null } && modifiers.all { it.estaCompleto() }

/**
 * Convert ProductEntity to Product domain model.
 *
 * @receiver ProductEntity from Room database
 * @return Product domain model
 */
fun ProductEntity.toDomain(): Product {
    return Product(
        id = productId,
        name = name,
        sku = sku,
        price = BigDecimal(price),
        categoryId = categoryId,
        categoryName = categoryName,
        description = description,
        emoji = emoji,
        imageUrl = imageUrl,
        available = available,
        displayOrder = displayOrder,
        trackInventory = trackInventory,
        inventoryMethod = inventoryMethod,
        availableQuantity = availableQuantity,
        modifierGroups = gruposDeExtras(),
        categoryColor = categoryColor,  // Pass through from cache (null = auto-generate in domain)
        categoryIsActive = categoryIsActive,  // Category active status from dashboard
        // Misma regla que la red: sin group id (o en blanco) no hay familia; opciones raras = null.
        familia = datosDeFamiliaDeDto(productGroupId, productGroupName, productGroupImageUrl, opcionesDeJson(variantOptionsJson)),
        gtin = gtin,
        catalogAt = catalogAt
    )
}

/**
 * Convert Product domain model to ProductEntity.
 *
 * @receiver Product domain model
 * @param venueId Venue ID (tenant isolation)
 * @param cachedAt Cache timestamp (Unix milliseconds)
 * @return ProductEntity for Room storage
 */
fun Product.toEntity(venueId: String, cachedAt: Long): ProductEntity {
    return ProductEntity(
        productId = id,
        venueId = venueId,
        name = name,
        sku = sku,
        price = price.toString(),
        categoryId = categoryId,
        categoryName = categoryName,
        description = description,
        emoji = emoji,
        imageUrl = imageUrl,
        available = available,
        displayOrder = displayOrder,
        trackInventory = trackInventory,
        inventoryMethod = inventoryMethod,
        availableQuantity = availableQuantity,
        modifierGroupsJson = gson.toJson(modifierGroups),
        categoryColor = categoryColor,  // Store backend color in cache
        categoryIsActive = categoryIsActive,  // Store category active status in cache
        cachedAt = cachedAt,
        productGroupId = familia?.groupId,
        productGroupName = familia?.groupName,
        productGroupImageUrl = familia?.groupImageUrl,
        variantOptionsJson = familia?.let(::opcionesAJson),
        gtin = gtin,
        catalogAt = catalogAt
    )
}

/**
 * Convert list of ProductEntity to list of Product domain models.
 *
 * @receiver List of ProductEntity from Room
 * @return List of Product domain models
 */
fun List<ProductEntity>.toDomain(): List<Product> {
    return map { it.toDomain() }
}

/**
 * Convert list of Product domain models to list of ProductEntity.
 *
 * @receiver List of Product domain models
 * @param venueId Venue ID (tenant isolation)
 * @param cachedAt Cache timestamp (Unix milliseconds)
 * @return List of ProductEntity for Room storage
 */
fun List<Product>.toEntities(venueId: String, cachedAt: Long): List<ProductEntity> {
    return map { it.toEntity(venueId, cachedAt) }
}

/**
 * Convert ProductCategoryEntity to ProductCategory domain model.
 *
 * @receiver ProductCategoryEntity from Room database
 * @return ProductCategory domain model
 */
fun ProductCategoryEntity.toDomain(): ProductCategory {
    return ProductCategory(
        id = categoryId,
        name = name,
        displayOrder = displayOrder,
        productCount = productCount,
        emoji = emoji,
        color = color,  // Pass through from cache (null = auto-generate in domain)
        isActive = isActive  // Active status from dashboard toggle
    )
}

/**
 * Convert ProductCategory domain model to ProductCategoryEntity.
 *
 * @receiver ProductCategory domain model
 * @param venueId Venue ID (tenant isolation)
 * @param cachedAt Cache timestamp (Unix milliseconds)
 * @return ProductCategoryEntity for Room storage
 */
fun ProductCategory.toEntity(venueId: String, cachedAt: Long): ProductCategoryEntity {
    return ProductCategoryEntity(
        categoryId = id,
        venueId = venueId,
        name = name,
        displayOrder = displayOrder,
        productCount = productCount,
        emoji = emoji,
        color = color,  // Store backend color in cache
        isActive = isActive,  // Store active status in cache
        cachedAt = cachedAt
    )
}

/**
 * Convert list of ProductCategoryEntity to list of ProductCategory domain models.
 *
 * @receiver List of ProductCategoryEntity from Room
 * @return List of ProductCategory domain models
 */
fun List<ProductCategoryEntity>.toCategoryDomain(): List<ProductCategory> {
    return map { it.toDomain() }
}

/**
 * Convert list of ProductCategory domain models to list of ProductCategoryEntity.
 *
 * @receiver List of ProductCategory domain models
 * @param venueId Venue ID (tenant isolation)
 * @param cachedAt Cache timestamp (Unix milliseconds)
 * @return List of ProductCategoryEntity for Room storage
 */
fun List<ProductCategory>.toCategoryEntities(venueId: String, cachedAt: Long): List<ProductCategoryEntity> {
    return map { it.toEntity(venueId, cachedAt) }
}

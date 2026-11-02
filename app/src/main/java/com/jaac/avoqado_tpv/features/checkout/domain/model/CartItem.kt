package com.jaac.avoqado_tpv.features.checkout.domain.model

import androidx.compose.runtime.Immutable
import java.util.UUID

/**
 * A line item in the unified cart.
 *
 * Money is held in integer cents to mirror avoqado-android. Conversion to
 * BigDecimal happens at the boundary where the cart talks to TPV's
 * [com.jaac.avoqado_tpv.features.ordering.domain.OrderRepository].
 */
@Immutable
data class CartItem(
    val id: String = UUID.randomUUID().toString(),
    val type: CartItemType,
    val name: String,
    val subtitle: String? = null,
    val unitPriceCents: Int,
    val quantity: Int = 1,
    val imageUrl: String? = null,
    val colorHex: String? = null,
    val categoryId: String? = null,

    // Customizations
    val selectedModifiers: List<SelectedModifier> = emptyList(),
    val itemNote: String? = null,
    val priceAdjustmentCents: Int? = null, // overrides unitPriceCents when set
    val isCortesia: Boolean = false,
    val cortesiaReason: String? = null,
    val itemDiscountId: String? = null,

    /**
     * `catalogAt` (hora del SERVIDOR, ISO) del catálogo con que se AGREGÓ esta línea. Se congela aquí al agregar el producto y no se
     * vuelve a leer del catálogo vigente: es lo que el servidor usa para reconocer el precio que vio el cliente. null = importe
     * tecleado, producto de un servidor viejo o un carrito guardado antes de este campo (viaja sin precio visto).
     */
    val catalogAt: String? = null,
) {
    init {
        // Cortesía already wipes the line to $0; layering a per-item discount
        // on top is meaningless and the backend rejects it with 400. Enforce
        // the mutex here so the UI cannot compose an invalid cart even by
        // programming error.
        require(!(isCortesia && itemDiscountId != null)) {
            "CartItem cannot be both cortesía and have an itemDiscountId"
        }
    }

    /** Modifiers contribute to price per-unit, multiplied by quantity. */
    val modifiersPriceCents: Int
        get() = selectedModifiers.sumOf { it.priceInCents } * quantity

    /** When the operator overrides the unit price, this wins. */
    val effectiveUnitPriceCents: Int
        get() = priceAdjustmentCents ?: unitPriceCents

    /**
     * Final line total in cents. Cortesía wipes the line to zero — operators
     * still see the original price in the UI but the math collapses to 0.
     */
    val totalPriceCents: Int
        get() = if (isCortesia) {
            0
        } else {
            (effectiveUnitPriceCents + selectedModifiers.sumOf { it.priceInCents }) * quantity
        }

    /** "Salsa picante, Sin cebolla" — for inline UI display under the name. */
    val modifiersSummary: String?
        get() {
            val names = selectedModifiers.map { it.modifierName }
            return if (names.isEmpty()) null else names.joinToString(", ")
        }

    val hasCustomizations: Boolean
        get() = selectedModifiers.isNotEmpty() ||
            itemNote != null ||
            priceAdjustmentCents != null ||
            isCortesia
}

/**
 * Bruto de la línea: (precio + extras) × cantidad ANTES de descuentos. En cortesía NO es 0 (el total sí). Lo usan el `subtotal` del
 * pedido y el renglón del ticket de una venta guardada.
 */
fun CartItem.grossLineCents(): Int =
    (effectiveUnitPriceCents + selectedModifiers.sumOf { it.priceInCents }) * quantity

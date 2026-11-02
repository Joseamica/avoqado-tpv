package com.jaac.avoqado_tpv.features.checkout.domain

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartItem
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartItemType
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartState
import com.jaac.avoqado_tpv.features.checkout.domain.model.ExtraDeTicket
import com.jaac.avoqado_tpv.features.checkout.domain.model.RenglonDeTicket
import com.jaac.avoqado_tpv.features.checkout.domain.model.grossLineCents
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierType
import com.jaac.avoqado_tpv.features.ordering.domain.Product

/**
 * 💵 «Cobrar» sin red: el cuerpo de `POST mobile/venues/{v}/orders` (`CreateOrderInput`), **en centavos**, armado del carrito.
 *
 * - `externalId` = [llave] en la raíz y `"<llave>:<i>"` por renglón ([externalIdDeRenglon]).
 * - `displayedUnitPrice` + `catalogAt` viajan SÓLO juntos: son lo que el renglón congeló al agregarse (nunca el catálogo vigente al
 *   cobrar); sin `catalogAt` (servidor viejo, carrito viejo) el renglón viaja sin precio visto y se cobra el vigente.
 *   `displayedModifierPrices` ({ idDelExtra: centavos por unidad }) sólo acompaña a un renglón con `catalogAt`.
 * - Un importe tecleado es una línea libre: `name` + `unitPrice`, sin `productId`.
 * - Con descuento de cuenta de catálogo viaja `orderDiscountId` y el `discount` libre queda en 0 (el servidor lo recalcula y, con
 *   id, no suma el libre). Sin él, `discount` es el descuento manual.
 * - Cortesía: `isCortesia` + `cortesiaReason` (nunca con `discountId`, `CartItem` ya lo impide).
 * - El carrito de Cobrar no maneja venta por peso: no se manda `weightQuantity`.
 *
 * [productos] no cambia el pedido (todo lo que viaja ya está congelado en el renglón); está en la firma para que el armado del
 * pedido y de los renglones del ticket ([renglonesDeTicket]) se llamen igual desde el mismo sitio.
 * [customerId] es el cliente ligado a la venta (opcional); la nota de la cuenta sale de [CartState.orderNote].
 */
@Suppress("UNUSED_PARAMETER")
fun armarPedidoParaMobile(
    cart: CartState,
    llave: String,
    staffId: String,
    shiftId: String?,
    createdAtLocal: Long,
    productos: Map<String, Product>,
    customerId: String? = null,
): String {
    val raiz = JsonObject()
    raiz.addProperty("staffId", staffId)
    raiz.addProperty("orderType", "TAKEOUT")
    raiz.addProperty("source", "TPV")
    raiz.addProperty("externalId", llave)
    customerId?.let { raiz.addProperty("customerId", it) }
    raiz.addProperty("subtotal", cart.items.sumOf { it.grossLineCents() })
    raiz.addProperty("tip", 0)
    raiz.addProperty("total", cart.totalCents)
    // Con descuento de catálogo el servidor recalcula; el libre sólo cuenta sin él.
    raiz.addProperty("discount", if (cart.orderDiscount != null) 0 else cart.manualDiscountCents)
    cart.orderDiscount?.let { raiz.addProperty("orderDiscountId", it.id) }
    cart.orderNote?.takeIf { it.isNotBlank() }?.let { raiz.addProperty("note", it) }
    raiz.addProperty("isOfflineReplay", true)
    raiz.addProperty("createdAtLocal", createdAtLocal)
    shiftId?.let { raiz.addProperty("shiftIdDeLaVenta", it) }

    val items = JsonArray()
    cart.items.forEachIndexed { i, item -> items.add(lineaDelPedido(item, externalIdDeRenglon(llave, i))) }
    raiz.add("items", items)
    return raiz.toString()
}

/**
 * Los renglones del ticket del MISMO carrito, con las MISMAS llaves por renglón que [armarPedidoParaMobile], en el mismo orden.
 * [productos] sólo sirve para el tipo de cada extra (`ModifierType.name`); un extra que ya no está en el catálogo cuenta como
 * `MULTIPLE_CHOICE`.
 */
fun renglonesDeTicket(cart: CartState, llave: String, productos: Map<String, Product>): List<RenglonDeTicket> =
    cart.items.mapIndexed { i, item ->
        val producto = (item.type as? CartItemType.ProductItem)?.productId?.let { productos[it] }
        RenglonDeTicket(
            externalId = externalIdDeRenglon(llave, i),
            nombre = item.name,
            cantidad = item.quantity,
            totalCents = item.totalPriceCents,
            brutoCents = item.grossLineCents(),
            extras = item.selectedModifiers.map { extra ->
                ExtraDeTicket(
                    id = extra.modifierId,
                    nombre = extra.modifierName,
                    precioCents = extra.priceInCents,
                    tipo = tipoDeExtra(producto, extra.modifierId),
                )
            },
            nota = item.itemNote,
        )
    }

/** La llave de un renglón: la de la venta y su posición en el carrito. */
fun externalIdDeRenglon(llave: String, indice: Int): String = "$llave:$indice"

private fun lineaDelPedido(item: CartItem, externalId: String): JsonObject {
    val linea = JsonObject()
    linea.addProperty("externalId", externalId)
    val productId = (item.type as? CartItemType.ProductItem)?.productId
    if (productId != null) {
        linea.addProperty("productId", productId)
    } else {
        // Importe tecleado: línea libre (nombre + precio en centavos, sin productId).
        linea.addProperty("name", item.name)
        linea.addProperty("unitPrice", item.effectiveUnitPriceCents)
    }
    linea.addProperty("quantity", item.quantity)
    val extras = JsonArray()
    item.selectedModifiers.forEach { extras.add(it.modifierId) }
    linea.add("modifierIds", extras)
    item.itemNote?.let { linea.addProperty("notes", it) }
    if (productId != null && item.catalogAt != null) {
        linea.addProperty("displayedUnitPrice", item.unitPriceCents)
        linea.addProperty("catalogAt", item.catalogAt)
        if (item.selectedModifiers.isNotEmpty()) {
            val precios = JsonObject()
            item.selectedModifiers.forEach { precios.addProperty(it.modifierId, it.priceInCents) }
            linea.add("displayedModifierPrices", precios)
        }
    }
    if (item.isCortesia) {
        linea.addProperty("isCortesia", true)
        item.cortesiaReason?.let { linea.addProperty("cortesiaReason", it) }
    }
    item.itemDiscountId?.let { linea.addProperty("discountId", it) }
    return linea
}

private fun tipoDeExtra(producto: Product?, modifierId: String): String {
    val grupos = producto?.modifierGroups.orEmpty()
    val modificador = grupos.asSequence().flatMap { it.modifiers.asSequence() }.firstOrNull { it.id == modifierId }
    return (modificador?.type ?: grupos.firstOrNull { g -> g.modifiers.any { it.id == modifierId } }?.type ?: ModifierType.MULTIPLE_CHOICE).name
}

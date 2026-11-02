package com.jaac.avoqado_tpv.features.checkout.domain

import com.jaac.avoqado_tpv.features.checkout.domain.model.VentaLocal
import com.jaac.avoqado_tpv.features.ordering.domain.KitchenStatus
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierType
import com.jaac.avoqado_tpv.features.ordering.domain.OrderItem
import com.jaac.avoqado_tpv.features.ordering.domain.ProductModifier
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * 💵 «Cobrar» sin red: los renglones del ticket de una venta guardada, como los pide el impresor (`PrinterManager.printReceipt` y el
 * ticket de AngelPay), armados de SUS renglones (`ticket_json`) y nunca del catálogo ni de la red. `orderId` = la llave (no hay orden
 * todavía); cada extra con su tipo guardado (`MULTIPLE_CHOICE` si el guardado no se reconoce). El importe del renglón es el que se
 * cobró (en cortesía, $0).
 */
fun renglonesParaImprimir(venta: VentaLocal): List<OrderItem> = venta.renglones.map { r ->
    OrderItem(
        id = r.externalId,
        orderId = venta.llave,
        productId = "",
        productName = r.nombre,
        productSku = null,
        quantity = r.cantidad,
        unitPrice = pesos(r.totalCents).divide(BigDecimal(r.cantidad.coerceAtLeast(1)), 2, RoundingMode.HALF_UP),
        totalPrice = pesos(r.totalCents),
        modifiers = r.extras.map { e ->
            ProductModifier(
                id = e.id,
                name = e.nombre,
                // Un extra gratis va como ZERO exacto: `formattedPrice` compara con `==` y "0.00" saldría «-$0.00».
                priceAdjustment = if (e.precioCents == 0) BigDecimal.ZERO else pesos(e.precioCents),
                type = runCatching { ModifierType.valueOf(e.tipo) }.getOrDefault(ModifierType.MULTIPLE_CHOICE),
            )
        },
        notes = r.nota,
        kitchenStatus = KitchenStatus.PENDING,
        createdAt = Instant.ofEpochMilli(venta.createdAtLocal),
        sentToKitchenAt = null,
    )
}

/** El descuento de la cuenta en el papel: lo que los renglones suman de más sobre lo cobrado (nunca negativo). */
fun descuentoParaImprimir(venta: VentaLocal): BigDecimal =
    pesos((venta.renglones.sumOf { it.totalCents } - venta.totalCents).coerceAtLeast(0))

private fun pesos(cents: Int): BigDecimal = BigDecimal.valueOf(cents.toLong(), 2)

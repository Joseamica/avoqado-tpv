package com.jaac.avoqado_tpv.features.tables.data.api.dto

import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetail

/**
 * Envelope `{ success, data }` compartido por:
 *  - `GET /tpv/venues/{venueId}/orders/{orderId}` (`orderController.getOrder`)
 *  - `PATCH /tpv/venues/{venueId}/orders/{orderId}/items` (`orderController.addItemsToOrder`,
 *    que además manda `message`)
 *
 * Los dos devuelven un `Order` (mismo modelo `OrderDetail`, ver su KDoc para
 * los campos que solo llegan en uno de los dos).
 */
data class OrderDetailResponse(
    val success: Boolean = true,
    val data: OrderDetail? = null,
    val message: String? = null,
)

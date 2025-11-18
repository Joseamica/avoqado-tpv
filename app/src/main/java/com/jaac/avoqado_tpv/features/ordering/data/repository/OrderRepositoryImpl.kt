package com.jaac.avoqado_tpv.features.ordering.data.repository

import com.jaac.avoqado_tpv.features.ordering.data.api.OrderApiService
import com.jaac.avoqado_tpv.features.ordering.data.dto.CreateOrderRequest
import com.jaac.avoqado_tpv.features.ordering.data.mappers.toOrder
import com.jaac.avoqado_tpv.features.ordering.domain.AddOrderItemRequest
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.OrderRepository
import com.jaac.avoqado_tpv.features.ordering.domain.OrderStatus
import com.jaac.avoqado_tpv.features.ordering.domain.OrderType
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Order repository implementation.
 *
 * Handles order operations via REST API.
 * Implements only getOrder() for now - other methods will be added as needed.
 */
@Singleton
class OrderRepositoryImpl @Inject constructor(
    private val apiService: OrderApiService
) : OrderRepository {

    /**
     * Get order by ID.
     *
     * Backend: GET /tpv/venues/{venueId}/orders/{orderId}
     */
    override suspend fun getOrder(
        venueId: String,
        orderId: String
    ): Result<Order> {
        return try {
            val response = apiService.getOrder(venueId, orderId)

            if (response.isSuccessful) {
                val body = response.body()
                if (body != null && body.success) {
                    Result.success(body.data.toOrder())
                } else {
                    Result.failure(Exception("Failed to fetch order"))
                }
            } else {
                val errorMessage = when (response.code()) {
                    401 -> "No autorizado. Por favor inicia sesión nuevamente."
                    403 -> "No tienes permisos para ver esta orden."
                    404 -> "Orden no encontrada."
                    else -> "Error al obtener orden: ${response.code()}"
                }
                Timber.e("getOrder failed: $errorMessage")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception in getOrder")
            Result.failure(e)
        }
    }

    /**
     * Get order by table ID.
     *
     * NOT IMPLEMENTED - Will be added when needed.
     */
    override suspend fun getOrderByTable(
        venueId: String,
        tableId: String
    ): Result<Order?> {
        return Result.failure(NotImplementedError("getOrderByTable not implemented yet"))
    }

    /**
     * Get all orders for venue.
     *
     * NOT IMPLEMENTED - Will be added when needed.
     */
    override suspend fun getOrders(
        venueId: String,
        status: OrderStatus?
    ): Result<List<Order>> {
        return Result.failure(NotImplementedError("getOrders not implemented yet"))
    }

    /**
     * Create new order.
     *
     * Backend: POST /tpv/venues/{venueId}/orders
     *
     * Backend generates:
     * - orderId (CUID format like "cmi1yg8mw00ad9kti6j7jy7f8")
     * - orderNumber (sequential like "ORD-0001234")
     */
    override suspend fun createOrder(
        venueId: String,
        tableId: String?,
        covers: Int,
        waiterId: String?,
        orderType: OrderType
    ): Result<Order> {
        return try {
            val request = CreateOrderRequest(
                tableId = tableId,
                covers = covers,
                waiterId = waiterId,
                orderType = when (orderType) {
                    OrderType.DINE_IN -> "DINE_IN"
                    OrderType.TAKEOUT -> "TAKEOUT"
                    OrderType.DELIVERY -> "DELIVERY"
                    OrderType.PICKUP -> "PICKUP"
                }
            )

            Timber.d("🆕 Creating order | venue=$venueId | type=$orderType | table=$tableId")

            val response = apiService.createOrder(venueId, request)

            if (response.isSuccessful) {
                val body = response.body()
                if (body != null && body.success) {
                    val order = body.data.toOrder()
                    Timber.i("✅ Order created | id=${order.id} | number=${order.orderNumber}")
                    Result.success(order)
                } else {
                    val errorMsg = "Failed to create order: Invalid response"
                    Timber.e(errorMsg)
                    Result.failure(Exception(errorMsg))
                }
            } else {
                val errorMessage = when (response.code()) {
                    401 -> "No autorizado. Por favor inicia sesión nuevamente."
                    400 -> "Solicitud inválida. Verifica los datos de la orden."
                    500 -> "Error del servidor. Por favor intenta nuevamente."
                    else -> "Error al crear orden: ${response.code()}"
                }
                Timber.e("createOrder failed: $errorMessage")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception in createOrder")
            Result.failure(e)
        }
    }

    /**
     * Add items to existing order.
     *
     * Backend: PATCH /tpv/venues/{venueId}/orders/{orderId}/items
     *
     * **Optimistic Concurrency Control:**
     * - Sends current version in request body
     * - Backend checks version matches
     * - If mismatch (409 Conflict), caller must refetch and retry
     * - Backend increments version on success
     */
    override suspend fun addItemsToOrder(
        venueId: String,
        orderId: String,
        items: List<AddOrderItemRequest>,
        currentVersion: Int
    ): Result<Order> {
        return try {
            // Convert domain AddOrderItemRequest to DTO AddItemDto
            val itemDtos = items.map { item ->
                com.jaac.avoqado_tpv.features.ordering.data.dto.AddItemDto(
                    productId = item.productId,
                    quantity = item.quantity,
                    notes = item.notes
                )
            }

            val request = com.jaac.avoqado_tpv.features.ordering.data.dto.AddItemsRequest(
                items = itemDtos,
                version = currentVersion
            )

            Timber.d("🛒 Adding ${items.size} items to order | orderId=$orderId | version=$currentVersion")

            val response = apiService.addItemsToOrder(venueId, orderId, request)

            if (response.isSuccessful) {
                val body = response.body()
                if (body != null && body.success) {
                    val order = body.data.toOrder()
                    Timber.i("✅ Items added successfully | newVersion=${order.version} | itemCount=${order.items.size}")
                    Result.success(order)
                } else {
                    val errorMsg = "Failed to add items: Invalid response"
                    Timber.e(errorMsg)
                    Result.failure(Exception(errorMsg))
                }
            } else {
                val errorMessage = when (response.code()) {
                    401 -> "No autorizado. Por favor inicia sesión nuevamente."
                    400 -> "Solicitud inválida. Verifica los productos seleccionados."
                    404 -> "Orden no encontrada."
                    409 -> {
                        Timber.w("⚠️ Version conflict - order was modified by another terminal")
                        "La orden fue modificada por otra terminal.\n\n" +
                        "Por favor, intenta nuevamente."
                    }
                    500 -> "Error del servidor. Por favor intenta nuevamente."
                    else -> "Error al agregar items: ${response.code()}"
                }
                Timber.e("addItemsToOrder failed: $errorMessage (HTTP ${response.code()})")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception in addItemsToOrder")
            Result.failure(e)
        }
    }

    /**
     * Remove item from order.
     *
     * NOT IMPLEMENTED - Will be added when needed.
     */
    override suspend fun removeOrderItem(
        venueId: String,
        orderId: String,
        orderItemId: String,
        currentVersion: Int
    ): Result<Order> {
        return Result.failure(NotImplementedError("removeOrderItem not implemented yet"))
    }

    /**
     * Update order item quantity.
     *
     * NOT IMPLEMENTED - Will be added when needed.
     */
    override suspend fun updateOrderItemQuantity(
        venueId: String,
        orderId: String,
        orderItemId: String,
        newQuantity: Int,
        currentVersion: Int
    ): Result<Order> {
        return Result.failure(NotImplementedError("updateOrderItemQuantity not implemented yet"))
    }

    /**
     * Send order to kitchen.
     *
     * NOT IMPLEMENTED - Will be added when needed.
     */
    override suspend fun sendToKitchen(
        venueId: String,
        orderId: String,
        currentVersion: Int
    ): Result<Order> {
        return Result.failure(NotImplementedError("sendToKitchen not implemented yet"))
    }

    /**
     * Update order status.
     *
     * NOT IMPLEMENTED - Will be added when needed.
     */
    override suspend fun updateOrderStatus(
        venueId: String,
        orderId: String,
        newStatus: OrderStatus,
        currentVersion: Int
    ): Result<Order> {
        return Result.failure(NotImplementedError("updateOrderStatus not implemented yet"))
    }
}

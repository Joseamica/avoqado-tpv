package com.jaac.avoqado_tpv.features.ordering.data.repository

import com.jaac.avoqado_tpv.features.ordering.data.api.CustomerApiService
import com.jaac.avoqado_tpv.features.ordering.data.api.OrderApiService
import com.jaac.avoqado_tpv.features.ordering.data.dto.AddCustomerToOrderRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.ApplyDiscountRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.CompItemsRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.CreateAndAddCustomerRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.CreateOrderRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.UpdateGuestRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.VoidItemsRequest
import com.jaac.avoqado_tpv.features.ordering.data.mappers.toOrder
import com.jaac.avoqado_tpv.features.ordering.data.mappers.toOrderCustomers
import com.jaac.avoqado_tpv.core.data.local.mappers.toEntity
import com.jaac.avoqado_tpv.core.data.local.mappers.toEntities
import com.jaac.avoqado_tpv.features.ordering.domain.AddOrderItemRequest
import com.jaac.avoqado_tpv.features.ordering.domain.ConflictException
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.OrderCustomer
import com.jaac.avoqado_tpv.features.ordering.domain.OrderRepository
import com.jaac.avoqado_tpv.features.ordering.domain.OrderStatus
import com.jaac.avoqado_tpv.features.ordering.domain.OrderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val apiService: OrderApiService,
    private val customerApiService: CustomerApiService,
    private val draftOrderDao: com.jaac.avoqado_tpv.core.data.local.dao.DraftOrderDao,
    private val draftOrderItemDao: com.jaac.avoqado_tpv.core.data.local.dao.DraftOrderItemDao
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
     * Get all open orders for venue.
     *
     * Backend: GET /tpv/venues/{venueId}/orders
     *
     * Returns orders with paymentStatus IN ['PENDING', 'PARTIAL'].
     * Backend automatically filters - status parameter ignored for now.
     */
    override suspend fun getOrders(
        venueId: String,
        status: OrderStatus?
    ): Result<List<Order>> {
        return try {
            Timber.d("📋 [OrderList] Fetching orders for venueId=$venueId")

            // Include pay-later orders (orders with customers linked)
            val response = apiService.getOrders(
                venueId = venueId,
                includePayLater = true
            )

            if (response.isSuccessful) {
                val body = response.body()
                if (body != null && body.success) {
                    val orders = body.data.map { it.toOrder() }
                    Timber.i("✅ [OrderList] Loaded ${orders.size} orders from backend")
                    Result.success(orders)
                } else {
                    Timber.e("❌ [OrderList] Invalid response body")
                    Result.failure(Exception("Invalid response from server"))
                }
            } else {
                val errorMessage = when (response.code()) {
                    401 -> "No autorizado. Por favor inicia sesión nuevamente."
                    500 -> "Error del servidor. Por favor intenta nuevamente."
                    else -> "Error al obtener órdenes: ${response.code()}"
                }
                Timber.e("❌ [OrderList] Failed: $errorMessage")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ [OrderList] Exception fetching orders")
            
            // Map technical exceptions to user-friendly messages
            val userFriendlyError = when (e) {
                is java.net.UnknownHostException, 
                is java.net.ConnectException -> Exception("Sin conexión a internet. Verifique su red.")
                is java.net.SocketTimeoutException -> Exception("El servidor tardó demasiado en responder.")
                else -> e
            }
            
            Result.failure(userFriendlyError)
        }
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
        orderType: OrderType,
        skipCaching: Boolean,
        source: String
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
                },
                source = source  // TPV, KIOSK, QR, WEB, etc.
            )

            // 🔍 [DIAGNOSTIC] Log venueId used to create order
            Timber.i("🔍 [Order] Creating for venueId=$venueId | type=$orderType | table=$tableId")

            val response = apiService.createOrder(venueId, request)

            if (response.isSuccessful) {
                val body = response.body()
                if (body != null && body.success) {
                    val order = body.data.toOrder()
                    Timber.i("✅ Order created | id=${order.id} | number=${order.orderNumber}")

                    // 💾 Cache order in local DB so PaymentViewModel can find it
                    // 🔄 [SYNC-FIX] Skip caching when called by OrderSyncCoordinator (prevents race condition)
                    if (!skipCaching) {
                        withContext(Dispatchers.IO) {
                            try {
                                Timber.d("💾 [Cache] Caching backend order to local DB | id=${order.id} | items=${order.items.size}")

                                // Convert order to entity
                                val draftOrderEntity = order.toEntity(
                                    syncStatus = com.jaac.avoqado_tpv.core.data.local.entities.DraftOrderEntity.SYNC_STATUS_SYNCED,
                                    isServerCreated = true
                                )

                                // Convert items to entities
                                val draftItemEntities = order.items.toEntities(
                                    syncStatus = com.jaac.avoqado_tpv.core.data.local.entities.DraftOrderItemEntity.SYNC_STATUS_SYNCED,
                                    isServerCreated = true
                                )

                                // Insert order first (parent)
                                draftOrderDao.insert(draftOrderEntity)

                                // Then insert items (children)
                                draftItemEntities.forEach { item ->
                                    draftOrderItemDao.insert(item)
                                }

                                Timber.i("✅ [Cache] Backend order cached successfully | id=${order.id}")
                            } catch (e: Exception) {
                                Timber.e(e, "❌ [Cache] Failed to cache backend order: ${order.id}")
                            }
                        }
                    } else {
                        Timber.d("⏭️ [Cache] Skipping order caching (sync coordinator will handle it)")
                    }

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
        currentVersion: Int,
        skipCaching: Boolean
    ): Result<Order> {
        return try {
            // Convert domain AddOrderItemRequest to DTO AddItemDto
            val itemDtos = items.map { item ->
                com.jaac.avoqado_tpv.features.ordering.data.dto.AddItemDto(
                    productId = item.productId,
                    quantity = item.quantity,
                    notes = item.notes,
                    modifierIds = item.modifierIds
                )
            }

            val request = com.jaac.avoqado_tpv.features.ordering.data.dto.AddItemsRequest(
                items = itemDtos,
                version = currentVersion
            )

            // 🔍 [DIAGNOSTIC] Log request details for backend validation
            Timber.i("🔍 [addItemsToOrder] venueId=$venueId | orderId=$orderId | version=$currentVersion | itemCount=${items.size}")
            items.forEachIndexed { index, item ->
                Timber.d("   [$index] productId=${item.productId} | qty=${item.quantity} | notes=${item.notes} | modifierIds=${item.modifierIds}")
            }

            val response = apiService.addItemsToOrder(venueId, orderId, request)

            if (response.isSuccessful) {
                val body = response.body()
                if (body != null && body.success) {
                    val order = body.data.toOrder()
                    Timber.i("✅ Items added successfully | newVersion=${order.version} | itemCount=${order.items.size}")

                    // Log modifier counts in response
                    order.items.forEach { item ->
                        if (item.modifiers.isNotEmpty()) {
                            Timber.d("  📦 [RESPONSE] OrderItem ${item.productName} has ${item.modifiers.size} modifiers")
                            item.modifiers.forEach { modifier ->
                                Timber.d("     - ${modifier.name}: \$${modifier.priceAdjustment}")
                            }
                        }
                    }

                    // 💾 Update order in local DB so PaymentViewModel can find it with items
                    // 🔄 [SYNC-FIX] Only cache if not skipped (prevents race with OrderSyncCoordinator)
                    if (!skipCaching) {
                        withContext(Dispatchers.IO) {
                            try {
                                Timber.d("💾 [Cache] Updating backend order in local DB | id=${order.id} | items=${order.items.size}")

                                // Convert order to entity
                                val draftOrderEntity = order.toEntity(
                                    syncStatus = com.jaac.avoqado_tpv.core.data.local.entities.DraftOrderEntity.SYNC_STATUS_SYNCED,
                                    isServerCreated = true
                                )

                                // Convert items to entities
                                val draftItemEntities = order.items.toEntities(
                                    syncStatus = com.jaac.avoqado_tpv.core.data.local.entities.DraftOrderItemEntity.SYNC_STATUS_SYNCED,
                                    isServerCreated = true
                                )

                                // Insert order first (parent)
                                draftOrderDao.insert(draftOrderEntity)

                                // Then insert items (children)
                                draftItemEntities.forEach { item ->
                                    draftOrderItemDao.insert(item)
                                }

                                Timber.i("✅ [Cache] Backend order updated in local DB | id=${order.id}")
                            } catch (e: Exception) {
                                Timber.e(e, "❌ [Cache] Failed to update backend order: ${order.id}")
                            }
                        }
                    } else {
                        Timber.d("⏭️ [Cache] Skipping caching (sync coordinator will handle it)")
                    }

                    Result.success(order)
                } else {
                    val errorMsg = "Failed to add items: Invalid response"
                    Timber.e(errorMsg)
                    Result.failure(Exception(errorMsg))
                }
            } else {
                if (response.code() == 409) {
                    Timber.w("⚠️ Version conflict - order was modified by another terminal")
                    Result.failure(ConflictException(serverVersion = "unknown", message = "La orden fue modificada por otra terminal."))
                } else {
                    val errorMessage = when (response.code()) {
                        401 -> "No autorizado. Por favor inicia sesión nuevamente."
                        400 -> "Solicitud inválida. Verifica los productos seleccionados."
                        404 -> "Orden no encontrada."
                        500 -> "Error del servidor. Por favor intenta nuevamente."
                        else -> "Error al agregar items: ${response.code()}"
                    }
                    Timber.e("addItemsToOrder failed: $errorMessage (HTTP ${response.code()})")
                    Result.failure(Exception(errorMessage))
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception in addItemsToOrder")
            Result.failure(e)
        }
    }

    /**
     * Remove item from order.
     *
     * Backend: DELETE /tpv/venues/{venueId}/orders/{orderId}/items/{itemId}?version={version}
     */
    override suspend fun removeOrderItem(
        venueId: String,
        orderId: String,
        orderItemId: String,
        currentVersion: Int
    ): Result<Order> {
        return try {
            Timber.d("🗑️ Removing item from order | orderId=$orderId | itemId=$orderItemId | version=$currentVersion")

            val response = apiService.removeOrderItem(venueId, orderId, orderItemId, currentVersion)

            if (response.isSuccessful) {
                val body = response.body()
                if (body != null && body.success) {
                    val order = body.data.toOrder()
                    Timber.i("✅ Item removed successfully | newVersion=${order.version} | itemCount=${order.items.size}")
                    Result.success(order)
                } else {
                    val errorMsg = "Failed to remove item: Invalid response"
                    Timber.e(errorMsg)
                    Result.failure(Exception(errorMsg))
                }
            } else {
                if (response.code() == 409) {
                    Timber.w("⚠️ Version conflict - order was modified by another terminal")
                    Result.failure(ConflictException(serverVersion = "unknown", message = "La orden fue modificada por otra terminal."))
                } else {
                    val errorMessage = when (response.code()) {
                        401 -> "No autorizado. Por favor inicia sesión nuevamente."
                        400 -> "La orden ya está pagada y no puede modificarse."
                        404 -> "Orden o item no encontrado."
                        500 -> "Error del servidor. Por favor intenta nuevamente."
                        else -> "Error al eliminar item: ${response.code()}"
                    }
                    Timber.e("removeOrderItem failed: $errorMessage (HTTP ${response.code()})")
                    Result.failure(Exception(errorMessage))
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception in removeOrderItem")
            Result.failure(e)
        }
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

    /**
     * Update guest information on order.
     *
     * Backend: PATCH /tpv/venues/{venueId}/orders/{orderId}/guest
     */
    override suspend fun updateGuest(
        venueId: String,
        orderId: String,
        covers: Int?,
        customerName: String?,
        customerPhone: String?,
        specialRequests: String?,
        customerId: String?
    ): Result<Order> {
        return try {
            val request = UpdateGuestRequest(
                covers = covers,
                customerName = customerName,
                customerPhone = customerPhone,
                specialRequests = specialRequests,
                customerId = customerId
            )

            Timber.d("👥 Updating guest info | orderId=$orderId | covers=$covers")

            val response = apiService.updateGuest(venueId, orderId, request)

            if (response.isSuccessful) {
                val body = response.body()
                if (body != null && body.success) {
                    val order = body.data.toOrder()
                    Timber.i("✅ Guest info updated successfully")
                    Result.success(order)
                } else {
                    val errorMsg = "Failed to update guest info: Invalid response"
                    Timber.e(errorMsg)
                    Result.failure(Exception(errorMsg))
                }
            } else {
                val errorMessage = when (response.code()) {
                    401 -> "No autorizado. Por favor inicia sesión nuevamente."
                    404 -> "Orden no encontrada."
                    500 -> "Error del servidor. Por favor intenta nuevamente."
                    else -> "Error al actualizar información del cliente: ${response.code()}"
                }
                Timber.e("updateGuest failed: $errorMessage (HTTP ${response.code()})")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception in updateGuest")
            Result.failure(e)
        }
    }

    /**
     * Comp items or entire order.
     *
     * Backend: POST /tpv/venues/{venueId}/orders/{orderId}/comp
     */
    override suspend fun compItems(
        venueId: String,
        orderId: String,
        itemIds: List<String>,
        reason: String,
        staffId: String,
        notes: String?
    ): Result<Order> {
        return try {
            val request = CompItemsRequest(
                itemIds = itemIds,
                reason = reason,
                staffId = staffId,
                notes = notes
            )

            val compType = if (itemIds.isEmpty()) "entire order" else "${itemIds.size} items"
            Timber.d("🎁 Comping $compType | orderId=$orderId | reason=$reason")

            val response = apiService.compItems(venueId, orderId, request)

            if (response.isSuccessful) {
                val body = response.body()
                if (body != null && body.success) {
                    val order = body.data.toOrder()
                    Timber.i("✅ Items comped successfully | discountAmount=${order.discountAmount}")
                    Result.success(order)
                } else {
                    val errorMsg = "Failed to comp items: Invalid response"
                    Timber.e(errorMsg)
                    Result.failure(Exception(errorMsg))
                }
            } else {
                val errorMessage = when (response.code()) {
                    401 -> "No autorizado. Por favor inicia sesión nuevamente."
                    400 -> "La orden ya está pagada y no puede modificarse."
                    404 -> "Orden no encontrada."
                    500 -> "Error del servidor. Por favor intenta nuevamente."
                    else -> "Error al aplicar cortesía: ${response.code()}"
                }
                Timber.e("compItems failed: $errorMessage (HTTP ${response.code()})")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception in compItems")
            Result.failure(e)
        }
    }

    /**
     * Void specific items from order.
     *
     * Backend: POST /tpv/venues/{venueId}/orders/{orderId}/void
     */
    override suspend fun voidItems(
        venueId: String,
        orderId: String,
        itemIds: List<String>,
        reason: String,
        staffId: String,
        currentVersion: Int
    ): Result<Order> {
        return try {
            val request = VoidItemsRequest(
                itemIds = itemIds,
                reason = reason,
                staffId = staffId,
                expectedVersion = currentVersion
            )

            Timber.d("❌ Voiding ${itemIds.size} items | orderId=$orderId | reason=$reason | version=$currentVersion")

            val response = apiService.voidItems(venueId, orderId, request)

            if (response.isSuccessful) {
                val body = response.body()
                if (body != null && body.success) {
                    val order = body.data.toOrder()
                    Timber.i("✅ Items voided successfully | newVersion=${order.version} | itemCount=${order.items.size}")
                    Result.success(order)
                } else {
                    val errorMsg = "Failed to void items: Invalid response"
                    Timber.e(errorMsg)
                    Result.failure(Exception(errorMsg))
                }
            } else {
                if (response.code() == 409) {
                    Timber.w("⚠️ Version conflict - order was modified by another terminal")
                    Result.failure(ConflictException(serverVersion = "unknown", message = "La orden fue modificada por otra terminal."))
                } else {
                    val errorMessage = when (response.code()) {
                        401 -> "No autorizado. Por favor inicia sesión nuevamente."
                        400 -> "La orden ya está pagada y no puede modificarse."
                        404 -> "Orden no encontrada."
                        500 -> "Error del servidor. Por favor intenta nuevamente."
                        else -> "Error al anular items: ${response.code()}"
                    }
                    Timber.e("voidItems failed: $errorMessage (HTTP ${response.code()})")
                    Result.failure(Exception(errorMessage))
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception in voidItems")
            Result.failure(e)
        }
    }

    /**
     * Apply discount to order or specific items.
     *
     * Backend: POST /tpv/venues/{venueId}/orders/{orderId}/discount
     */
    override suspend fun applyDiscount(
        venueId: String,
        orderId: String,
        type: String,
        value: Double,
        staffId: String,
        reason: String?,
        itemIds: List<String>?,
        currentVersion: Int
    ): Result<Order> {
        return try {
            val request = ApplyDiscountRequest(
                type = type,
                value = value,
                reason = reason,
                staffId = staffId,
                itemIds = itemIds,
                expectedVersion = currentVersion
            )

            val discountType = if (type == "PERCENTAGE") "$value%" else "$$value"
            val scope = if (itemIds == null) "order" else "${itemIds.size} items"
            Timber.d("💰 Applying $discountType discount to $scope | orderId=$orderId | version=$currentVersion")

            val response = apiService.applyDiscount(venueId, orderId, request)

            if (response.isSuccessful) {
                val body = response.body()
                if (body != null && body.success) {
                    val order = body.data.toOrder()
                    Timber.i("✅ Discount applied successfully | newVersion=${order.version} | discountAmount=${order.discountAmount}")
                    Result.success(order)
                } else {
                    val errorMsg = "Failed to apply discount: Invalid response"
                    Timber.e(errorMsg)
                    Result.failure(Exception(errorMsg))
                }
            } else {
                if (response.code() == 409) {
                    Timber.w("⚠️ Version conflict - order was modified by another terminal")
                    Result.failure(ConflictException(serverVersion = "unknown", message = "La orden fue modificada por otra terminal."))
                } else {
                    val errorMessage = when (response.code()) {
                        401 -> "No autorizado. Por favor inicia sesión nuevamente."
                        400 -> "Descuento inválido. Verifica el valor ingresado."
                        404 -> "Orden no encontrada."
                        500 -> "Error del servidor. Por favor intenta nuevamente."
                        else -> "Error al aplicar descuento: ${response.code()}"
                    }
                    Timber.e("applyDiscount failed: $errorMessage (HTTP ${response.code()})")
                    Result.failure(Exception(errorMessage))
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception in applyDiscount")
            Result.failure(e)
        }
    }

    // ========================================================================
    // ORDER-CUSTOMER OPERATIONS (Multi-Customer per Order)
    // ========================================================================

    /**
     * Get all customers associated with an order.
     *
     * Backend: GET /tpv/venues/{venueId}/orders/{orderId}/customers
     */
    override suspend fun getOrderCustomers(
        venueId: String,
        orderId: String
    ): Result<List<OrderCustomer>> {
        return try {
            Timber.d("👥 Fetching customers for order | orderId=$orderId")

            val response = customerApiService.getOrderCustomers(venueId, orderId)

            if (response.isSuccessful) {
                val body = response.body()
                if (body != null && body.success) {
                    val orderCustomers = body.data.toOrderCustomers()
                    Timber.i("✅ Fetched ${orderCustomers.size} customers for order")
                    Result.success(orderCustomers)
                } else {
                    val errorMsg = "Failed to fetch order customers: Invalid response"
                    Timber.e(errorMsg)
                    Result.failure(Exception(errorMsg))
                }
            } else {
                val errorMessage = when (response.code()) {
                    401 -> "No autorizado. Por favor inicia sesión nuevamente."
                    404 -> "Orden no encontrada."
                    500 -> "Error del servidor. Por favor intenta nuevamente."
                    else -> "Error al obtener clientes: ${response.code()}"
                }
                Timber.e("getOrderCustomers failed: $errorMessage (HTTP ${response.code()})")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception in getOrderCustomers")
            Result.failure(e)
        }
    }

    /**
     * Add an existing customer to an order.
     *
     * Backend: POST /tpv/venues/{venueId}/orders/{orderId}/customers
     */
    override suspend fun addCustomerToOrder(
        venueId: String,
        orderId: String,
        customerId: String
    ): Result<List<OrderCustomer>> {
        return try {
            Timber.d("➕ Adding customer to order | orderId=$orderId | customerId=$customerId")

            val request = AddCustomerToOrderRequest(customerId = customerId)
            val response = customerApiService.addCustomerToOrder(venueId, orderId, request)

            if (response.isSuccessful) {
                val body = response.body()
                if (body != null && body.success) {
                    val orderCustomers = body.data.toOrderCustomers()
                    Timber.i("✅ Customer added successfully | total=${orderCustomers.size}")
                    Result.success(orderCustomers)
                } else {
                    val errorMsg = body?.message ?: "Failed to add customer to order"
                    Timber.e(errorMsg)
                    Result.failure(Exception(errorMsg))
                }
            } else {
                val errorMessage = when (response.code()) {
                    401 -> "No autorizado. Por favor inicia sesión nuevamente."
                    404 -> "Orden o cliente no encontrado."
                    409 -> "Este cliente ya está agregado a la orden."
                    500 -> "Error del servidor. Por favor intenta nuevamente."
                    else -> "Error al agregar cliente: ${response.code()}"
                }
                Timber.e("addCustomerToOrder failed: $errorMessage (HTTP ${response.code()})")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception in addCustomerToOrder")
            Result.failure(e)
        }
    }

    /**
     * Remove a customer from an order.
     *
     * Backend: DELETE /tpv/venues/{venueId}/orders/{orderId}/customers/{customerId}
     */
    override suspend fun removeCustomerFromOrder(
        venueId: String,
        orderId: String,
        customerId: String
    ): Result<List<OrderCustomer>> {
        return try {
            Timber.d("➖ Removing customer from order | orderId=$orderId | customerId=$customerId")

            val response = customerApiService.removeCustomerFromOrder(venueId, orderId, customerId)

            if (response.isSuccessful) {
                val body = response.body()
                if (body != null && body.success) {
                    val orderCustomers = body.data.toOrderCustomers()
                    Timber.i("✅ Customer removed successfully | remaining=${orderCustomers.size}")
                    Result.success(orderCustomers)
                } else {
                    val errorMsg = body?.message ?: "Failed to remove customer from order"
                    Timber.e(errorMsg)
                    Result.failure(Exception(errorMsg))
                }
            } else {
                val errorMessage = when (response.code()) {
                    401 -> "No autorizado. Por favor inicia sesión nuevamente."
                    404 -> "Orden o cliente no encontrado."
                    500 -> "Error del servidor. Por favor intenta nuevamente."
                    else -> "Error al quitar cliente: ${response.code()}"
                }
                Timber.e("removeCustomerFromOrder failed: $errorMessage (HTTP ${response.code()})")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception in removeCustomerFromOrder")
            Result.failure(e)
        }
    }

    /**
     * Create a new customer and add to order.
     *
     * Backend: POST /tpv/venues/{venueId}/orders/{orderId}/customers/create
     */
    override suspend fun createAndAddCustomerToOrder(
        venueId: String,
        orderId: String,
        firstName: String?,
        phone: String?,
        email: String?
    ): Result<List<OrderCustomer>> {
        return try {
            Timber.d("🆕 Creating and adding customer | orderId=$orderId | name=$firstName | phone=$phone | email=$email")

            val request = CreateAndAddCustomerRequest(
                firstName = firstName,
                phone = phone,
                email = email
            )
            val response = customerApiService.createAndAddCustomerToOrder(venueId, orderId, request)

            if (response.isSuccessful) {
                val body = response.body()
                if (body != null && body.success) {
                    val orderCustomers = body.data.toOrderCustomers()
                    Timber.i("✅ Customer created and added | total=${orderCustomers.size}")
                    Result.success(orderCustomers)
                } else {
                    val errorMsg = body?.message ?: "Failed to create customer"
                    Timber.e(errorMsg)
                    Result.failure(Exception(errorMsg))
                }
            } else {
                val errorMessage = when (response.code()) {
                    401 -> "No autorizado. Por favor inicia sesión nuevamente."
                    400 -> "Se requiere al menos nombre, teléfono o email."
                    404 -> "Orden no encontrada."
                    500 -> "Error del servidor. Por favor intenta nuevamente."
                    else -> "Error al crear cliente: ${response.code()}"
                }
                Timber.e("createAndAddCustomerToOrder failed: $errorMessage (HTTP ${response.code()})")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception in createAndAddCustomerToOrder")
            Result.failure(e)
        }
    }
}

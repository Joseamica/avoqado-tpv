package com.jaac.avoqado_tpv.features.ordering.data.dto

import com.google.gson.annotations.SerializedName

/**
 * API Response Wrapper
 *
 * Standard response format from backend: { success: true, data: T }
 */
data class ApiResponse<T>(
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: T
)

/**
 * Waiter Info DTO
 *
 * Staff member serving the table.
 */
data class WaiterInfoDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String
)

/**
 * Order Item DTO
 *
 * Individual item in an order (for order preview).
 */
data class OrderItemDto(
    @SerializedName("id") val id: String,
    @SerializedName("productName") val productName: String,
    @SerializedName("quantity") val quantity: Int,
    @SerializedName("unitPrice") val unitPrice: Double,
    @SerializedName("total") val total: Double
)

/**
 * Product Info DTO (nested in OrderItemDetailDto)
 *
 * Product information returned by backend in OrderItem.
 * Backend includes full product object, not just flattened fields.
 */
data class ProductInfoDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("price") val price: Double? = null,
    @SerializedName("sku") val sku: String? = null
)

/**
 * Order Item Detail DTO
 *
 * Full individual item details with modifiers, notes, and kitchen status.
 * Used when fetching complete order details.
 */
data class OrderItemDetailDto(
    @SerializedName("id") val id: String,
    @SerializedName("orderId") val orderId: String,
    @SerializedName("productId") val productId: String? = null,  // ✅ NULLABLE: Backend may return null for deleted/orphaned items
    @SerializedName("product") val product: ProductInfoDto? = null,  // ✅ NULLABLE: Backend may return null for deleted products
    @SerializedName("productName") val productName: String? = null,
    @SerializedName("productSku") val productSku: String? = null,
    @SerializedName("categoryName") val categoryName: String? = null,
    @SerializedName("quantity") val quantity: Int,
    @SerializedName("unitPrice") val unitPrice: Double,
    @SerializedName("total") val totalPrice: Double,  // ✅ Backend uses "total" not "totalPrice"
    @SerializedName("modifiers") val modifiers: List<ProductModifierDto>? = null,
    @SerializedName("notes") val notes: String?,
    @SerializedName("kitchenStatus") val kitchenStatus: String? = null,  // ✅ NULLABLE: Backend doesn't have this field in OrderItem schema
    @SerializedName("createdAt") val createdAt: String,
    @SerializedName("sentToKitchenAt") val sentToKitchenAt: String?
)

/**
 * Product Modifier DTO
 *
 * Modifier applied to an order item (e.g., "Extra cheese", "No onions").
 *
 * **IMPORTANT**: Backend OrderItemModifier returns nested structure:
 * ```json
 * {
 *   "id": "oim_123",
 *   "modifier": {
 *     "id": "mod_456",
 *     "name": "BBQ",
 *     "price": 12.50  // ← Backend uses "price", not "priceAdjustment"
 *   },
 *   "price": 12.50,
 *   "quantity": 1
 * }
 * ```
 *
 * This DTO represents the nested `modifier` object.
 */
data class ProductModifierDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String?,  // ← Nullable: backend puede devolver null si modifier fue eliminado
    @SerializedName("price") val priceAdjustment: Double,  // ✅ Backend sends "price"
    @SerializedName("type") val type: String? = "MULTIPLE_CHOICE",
    @SerializedName("required") val required: Boolean? = false,
    @SerializedName("displayOrder") val displayOrder: Int? = 0
)

/**
 * Current Order Info DTO
 *
 * Summary of the active order on a table.
 */
data class CurrentOrderInfoDto(
    @SerializedName("id") val id: String,
    @SerializedName("orderNumber") val orderNumber: String,
    @SerializedName("covers") val covers: Int?,
    @SerializedName("total") val total: Double,
    @SerializedName("itemCount") val itemCount: Int,
    @SerializedName("items") val items: List<OrderItemDto>,
    @SerializedName("waiter") val waiter: WaiterInfoDto?,
    @SerializedName("createdAt") val createdAt: String
)

/**
 * Table DTO
 *
 * Represents table data from backend API.
 * Matches backend TableStatusResponse interface.
 */
data class TableDto(
    @SerializedName("id") val id: String,
    @SerializedName("number") val number: String,
    @SerializedName("capacity") val capacity: Int,
    @SerializedName("positionX") val positionX: Double?,
    @SerializedName("positionY") val positionY: Double?,
    @SerializedName("shape") val shape: String,
    @SerializedName("rotation") val rotation: Int,
    @SerializedName("status") val status: String,  // "AVAILABLE", "OCCUPIED", "RESERVED"
    @SerializedName("currentOrder") val currentOrder: CurrentOrderInfoDto?,
    @SerializedName("areaId") val areaId: String?,
    @SerializedName("areaName") val areaName: String?
)

/**
 * Create Order Request
 *
 * Request body for creating a new order.
 * Backend generates orderId (CUID) and orderNumber (sequential).
 *
 * @param source Order source: "TPV" for regular terminal, "KIOSK" for self-service.
 *               KIOSK orders are excluded from pay-later/open orders lists when abandoned.
 */
data class CreateOrderRequest(
    @SerializedName("tableId") val tableId: String? = null,
    @SerializedName("covers") val covers: Int = 1,
    @SerializedName("waiterId") val waiterId: String? = null,
    @SerializedName("orderType") val orderType: String = "TAKEOUT",  // "DINE_IN" or "TAKEOUT"
    @SerializedName("source") val source: String = "TPV",  // "TPV", "KIOSK", "QR", "WEB", etc.
    @SerializedName("externalId") val externalId: String? = null  // ✅ Idempotency key (client order ID)
)

// Single-call order creation used only by the new TPV Cobrar flow.
//
// All monetary fields are pesos as decimals, matching the rest of the
// /tpv API (e.g. $25.45 is serialized as 25.45, NOT as 2545). The
// CheckoutViewModel converts its internal integer-cents math to pesos at
// this boundary. Backend validates subtotal/discount/total with ±$0.01
// tolerance.
data class TpvCreateOrderWithItemsRequest(
    @SerializedName("items") val items: List<TpvCreateOrderWithItemsItemDto>,
    @SerializedName("staffId") val staffId: String,
    @SerializedName("orderType") val orderType: String = "TAKEOUT",
    @SerializedName("source") val source: String = "TPV",
    @SerializedName("tableId") val tableId: String? = null,
    @SerializedName("customerId") val customerId: String? = null,
    @SerializedName("discount") val discount: Double = 0.0,
    @SerializedName("orderDiscountId") val orderDiscountId: String? = null,
    @SerializedName("taxAmount") val taxAmount: Double = 0.0,
    @SerializedName("tip") val tip: Double = 0.0,
    @SerializedName("subtotal") val subtotal: Double,
    @SerializedName("total") val total: Double,
    @SerializedName("note") val note: String? = null,
    @SerializedName("externalId") val externalId: String? = null,
)

data class TpvCreateOrderWithItemsItemDto(
    @SerializedName("productId") val productId: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("quantity") val quantity: Int,
    @SerializedName("unitPrice") val unitPrice: Double? = null,
    @SerializedName("modifierIds") val modifierIds: List<String> = emptyList(),
    @SerializedName("notes") val notes: String? = null,
    @SerializedName("isCortesia") val isCortesia: Boolean = false,
    @SerializedName("cortesiaReason") val cortesiaReason: String? = null,
    @SerializedName("itemDiscountId") val itemDiscountId: String? = null,
)

/**
 * Assign Table Request
 *
 * Request body for assigning a table and creating/returning an order.
 */
data class AssignTableRequest(
    @SerializedName("tableId") val tableId: String,
    @SerializedName("staffId") val staffId: String,
    @SerializedName("covers") val covers: Int
)

/**
 * Assign Table Response Data
 *
 * Data from backend after assigning a table.
 * Contains the order (new or existing) and a flag indicating if it's new.
 * This is inside the ApiResponse wrapper: { success: true, data: { order, isNewOrder, message } }
 */
data class AssignTableResponseData(
    @SerializedName("order") val order: OrderDto,
    @SerializedName("isNewOrder") val isNewOrder: Boolean,
    @SerializedName("message") val message: String?
)

/**
 * Order DTO (for table assignment and order details)
 *
 * Full order data returned when assigning a table or fetching order details.
 */
data class OrderDto(
    @SerializedName("id") val id: String,
    @SerializedName("venueId") val venueId: String,
    @SerializedName("tableId") val tableId: String?,
    @SerializedName("tableName") val tableName: String? = null,
    @SerializedName("orderNumber") val orderNumber: String,
    @SerializedName("covers") val covers: Int?,
    @SerializedName("waiterId") val waiterId: String? = null,
    @SerializedName("waiterName") val waiterName: String? = null,
    @SerializedName("customerName") val customerName: String? = null,  // Guest name (TAKEOUT/DINE_IN)
    @SerializedName("customerPhone") val customerPhone: String? = null,  // Guest phone (TAKEOUT)
    @SerializedName("specialRequests") val specialRequests: String? = null,  // Allergies, dietary restrictions
    @SerializedName("status") val status: String,
    @SerializedName("paymentStatus") val paymentStatus: String,
    @SerializedName("kitchenStatus") val kitchenStatus: String? = "PENDING",  // ✅ NULLABLE with fallback
    @SerializedName("type") val orderType: String = "DINE_IN",  // Backend uses "type" not "orderType"
    @SerializedName("items") val items: List<OrderItemDetailDto> = emptyList(),
    @SerializedName("subtotal") val subtotal: Double,
    @SerializedName("discountAmount") val discountAmount: Double? = 0.0,
    @SerializedName("taxAmount") val taxAmount: Double,
    @SerializedName("total") val total: Double,
    @SerializedName("paidAmount") val paidAmount: Double? = 0.0,  // ⭐ Partial payment tracking
    @SerializedName("remainingBalance") val remainingBalance: Double? = null,  // ⭐ Amount left to pay (null = total)
    @SerializedName("notes") val notes: String? = null,
    @SerializedName("servedById") val servedById: String?,
    @SerializedName("createdAt") val createdAt: String,
    @SerializedName("updatedAt") val updatedAt: String,
    @SerializedName("version") val version: Int = 1,
    @SerializedName("lastSplitType") val lastSplitType: String? = null,  // ⭐ Split type restriction (PERPRODUCT, EQUALPARTS, etc.)
    @SerializedName("paidItemIds") val paidItemIds: List<String>? = null,  // ⭐ Items already paid (for SplitByProduct)
    @SerializedName("orderDiscounts") val orderDiscounts: List<OrderDiscountDto>? = null,  // 🎟️ Applied discounts
    @SerializedName("orderCustomers") val orderCustomers: List<OrderCustomerDto>? = null  // 💳 Customers linked to order (for pay-later)
)

/**
 * Update Table Position Request
 *
 * Request body for updating table position on floor plan.
 * Coordinates are normalized 0.0-1.0 values.
 */
data class UpdateTablePositionRequest(
    @SerializedName("positionX") val positionX: Double,
    @SerializedName("positionY") val positionY: Double
)

/**
 * Updated Table DTO
 *
 * Response from backend after updating table position.
 * Contains only the essential fields that were updated.
 */
data class UpdatedTableDto(
    @SerializedName("id") val id: String,
    @SerializedName("number") val number: String,
    @SerializedName("positionX") val positionX: Double,
    @SerializedName("positionY") val positionY: Double
)

/**
 * Create Table Request
 *
 * Request body for creating a new table.
 */
data class CreateTableRequest(
    @SerializedName("number") val number: String,
    @SerializedName("capacity") val capacity: Int,
    @SerializedName("shape") val shape: String,
    @SerializedName("rotation") val rotation: Int = 0,
    @SerializedName("positionX") val positionX: Double? = null,
    @SerializedName("positionY") val positionY: Double? = null,
    @SerializedName("areaId") val areaId: String? = null
)

/**
 * Update Table Request
 *
 * Request body for updating table properties (number, capacity, shape, rotation, areaId).
 * All fields are optional - only provided fields will be updated.
 */
data class UpdateTableRequest(
    @SerializedName("number") val number: String? = null,
    @SerializedName("capacity") val capacity: Int? = null,
    @SerializedName("shape") val shape: String? = null,
    @SerializedName("rotation") val rotation: Int? = null,
    @SerializedName("areaId") val areaId: String? = null
)

/**
 * Add Items Request
 *
 * Request body for adding items to an existing order.
 * Used by MenuViewModel after creating items locally.
 *
 * **Backend Endpoint:** PATCH /tpv/venues/{venueId}/orders/{orderId}/items
 *
 * **Optimistic Concurrency Control:**
 * The version field prevents lost updates when multiple terminals modify the same order.
 * If version mismatch occurs (409 Conflict), the ViewModel should:
 * 1. Fetch fresh order data
 * 2. Retry the operation with new version
 *
 * **Example:**
 * ```json
 * {
 *   "items": [
 *     {
 *       "productId": "prod_123",
 *       "quantity": 2,
 *       "notes": "Sin cebolla"
 *     }
 *   ],
 *   "version": 1
 * }
 * ```
 */
data class AddItemsRequest(
    @SerializedName("items") val items: List<AddItemDto>,
    @SerializedName("version") val version: Int
)

/**
 * Add Item DTO
 *
 * Individual item to add to an order.
 * Part of AddItemsRequest.
 */
data class AddItemDto(
    @SerializedName("productId") val productId: String,
    @SerializedName("quantity") val quantity: Int,
    @SerializedName("notes") val notes: String? = null,
    @SerializedName("modifierIds") val modifierIds: List<String>? = null,
    @SerializedName("externalId") val externalId: String? = null
)

/**
 * Update Guest Info Request
 *
 * Request body for updating guest information on an order.
 * Used for both DINE_IN (covers, customer info, special requests) and TAKEOUT (customer contact info).
 *
 * **Backend Endpoint:** PATCH /tpv/venues/{venueId}/orders/{orderId}/guest
 *
 * **Example (DINE_IN):**
 * ```json
 * {
 *   "covers": 4,
 *   "customerName": "John Doe",
 *   "customerPhone": "555-1234",
 *   "specialRequests": "No onions, extra sauce"
 * }
 * ```
 */
data class UpdateGuestRequest(
    @SerializedName("covers") val covers: Int? = null,
    @SerializedName("customerName") val customerName: String? = null,
    @SerializedName("customerPhone") val customerPhone: String? = null,
    @SerializedName("specialRequests") val specialRequests: String? = null,
    @SerializedName("customerId") val customerId: String? = null
)

/**
 * Comp Items Request
 *
 * Request body for comping items or entire order.
 * Empty itemIds array = comp entire order.
 *
 * **Backend Endpoint:** POST /tpv/venues/{venueId}/orders/{orderId}/comp
 *
 * **Example (comp entire order):**
 * ```json
 * {
 *   "itemIds": [],
 *   "reason": "Service recovery - food quality issue",
 *   "staffId": "staff_123",
 *   "notes": "Customer complained about cold food"
 * }
 * ```
 *
 * **Example (comp specific items):**
 * ```json
 * {
 *   "itemIds": ["item_1", "item_2"],
 *   "reason": "Long wait time",
 *   "staffId": "staff_123"
 * }
 * ```
 */
data class CompItemsRequest(
    @SerializedName("itemIds") val itemIds: List<String> = emptyList(),
    @SerializedName("reason") val reason: String,
    @SerializedName("staffId") val staffId: String,
    @SerializedName("notes") val notes: String? = null
)

/**
 * Void Items Request
 *
 * Request body for voiding specific items (cancel/remove with audit trail).
 * Used when customer changes mind or item was entered incorrectly.
 *
 * **Backend Endpoint:** POST /tpv/venues/{venueId}/orders/{orderId}/void
 *
 * **Optimistic Concurrency Control:**
 * The expectedVersion field prevents lost updates.
 *
 * **Example:**
 * ```json
 * {
 *   "itemIds": ["item_1"],
 *   "reason": "Customer changed mind",
 *   "staffId": "staff_123",
 *   "expectedVersion": 2
 * }
 * ```
 */
data class VoidItemsRequest(
    @SerializedName("itemIds") val itemIds: List<String>,
    @SerializedName("reason") val reason: String,
    @SerializedName("staffId") val staffId: String,
    @SerializedName("expectedVersion") val expectedVersion: Int
)

/**
 * Apply Discount Request
 *
 * Request body for applying discount to order or specific items.
 * Supports percentage (1-100) or fixed amount discounts.
 *
 * **Backend Endpoint:** POST /tpv/venues/{venueId}/orders/{orderId}/discount
 *
 * **Optimistic Concurrency Control:**
 * The expectedVersion field prevents lost updates.
 *
 * **Example (20% discount on entire order):**
 * ```json
 * {
 *   "type": "PERCENTAGE",
 *   "value": 20,
 *   "reason": "Happy hour discount",
 *   "staffId": "staff_123",
 *   "itemIds": null,
 *   "expectedVersion": 2
 * }
 * ```
 *
 * **Example ($10 fixed discount):**
 * ```json
 * {
 *   "type": "FIXED_AMOUNT",
 *   "value": 10,
 *   "reason": "Loyalty discount",
 *   "staffId": "staff_123",
 *   "expectedVersion": 2
 * }
 * ```
 */
data class ApplyDiscountRequest(
    @SerializedName("type") val type: String,  // "PERCENTAGE" or "FIXED_AMOUNT"
    @SerializedName("value") val value: Double,  // 0-100 for percentage, dollar amount for fixed
    @SerializedName("reason") val reason: String? = null,
    @SerializedName("staffId") val staffId: String,
    @SerializedName("itemIds") val itemIds: List<String>? = null,  // null = order-level discount
    @SerializedName("expectedVersion") val expectedVersion: Int
)

package com.jaac.avoqado_tpv.features.ordering.data.dto

import com.google.gson.annotations.SerializedName

/**
 * Customer Data Transfer Object
 *
 * Maps backend Customer model (from customer.tpv.service.ts) to Android DTO.
 *
 * Backend structure (CustomerSearchResult):
 * ```typescript
 * {
 *   id: string
 *   firstName: string | null
 *   lastName: string | null
 *   email: string | null
 *   phone: string | null
 *   loyaltyPoints: number
 *   totalVisits: number
 *   totalSpent: number
 *   customerGroup: {
 *     id: string
 *     name: string
 *     color: string | null
 *   } | null
 * }
 * ```
 *
 * @see avoqado-server/docs/clients&promotions/CUSTOMER_LOYALTY_PROMOTIONS_REFERENCE.md
 */
data class CustomerDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("firstName")
    val firstName: String?,

    @SerializedName("lastName")
    val lastName: String?,

    @SerializedName("email")
    val email: String?,

    @SerializedName("phone")
    val phone: String?,

    @SerializedName("loyaltyPoints")
    val loyaltyPoints: Int,

    @SerializedName("totalVisits")
    val totalVisits: Int,

    @SerializedName("totalSpent")
    val totalSpent: Double,

    @SerializedName("customerGroup")
    val customerGroup: CustomerGroupDto?
)

/**
 * Customer Group DTO
 *
 * Represents customer segment (VIP, Employee, Regular, etc.)
 */
data class CustomerGroupDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("color")
    val color: String?
)

/**
 * Quick Create Customer Request
 *
 * Minimal data for creating a customer during checkout.
 */
data class QuickCreateCustomerRequest(
    @SerializedName("firstName")
    val firstName: String?,

    @SerializedName("lastName")
    val lastName: String?,

    @SerializedName("phone")
    val phone: String?,

    @SerializedName("email")
    val email: String?
)

/**
 * Customer Search Response wrapper
 *
 * Backend returns: { message: string, data: Customer[], correlationId: string }
 */
data class CustomerSearchResponse(
    @SerializedName("message")
    val message: String?,

    @SerializedName("data")
    val data: List<CustomerDto>,

    @SerializedName("correlationId")
    val correlationId: String?
)

/**
 * Single Customer Response wrapper
 *
 * Backend returns: { message: string, data: Customer, correlationId: string }
 */
data class CustomerResponse(
    @SerializedName("message")
    val message: String?,

    @SerializedName("data")
    val data: CustomerDto,

    @SerializedName("correlationId")
    val correlationId: String?
)

// ============================================================================
// Order-Customer Relationship DTOs (Multi-Customer Support)
// ============================================================================

/**
 * Order-Customer DTO
 *
 * Represents the junction between an order and a customer (many-to-many).
 * Backend response from: GET/POST /orders/{orderId}/customers
 */
data class OrderCustomerDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("orderId")
    val orderId: String,

    @SerializedName("customerId")
    val customerId: String,

    @SerializedName("customer")
    val customer: CustomerDto,

    @SerializedName("isPrimary")
    val isPrimary: Boolean,

    @SerializedName("addedAt")
    val addedAt: String  // ISO datetime string
)

/**
 * Add Customer to Order Request
 *
 * POST /tpv/venues/{venueId}/orders/{orderId}/customers
 */
data class AddCustomerToOrderRequest(
    @SerializedName("customerId")
    val customerId: String
)

/**
 * Create and Add Customer to Order Request
 *
 * POST /tpv/venues/{venueId}/orders/{orderId}/customers/create
 * At least one field (firstName, phone, or email) is required.
 */
data class CreateAndAddCustomerRequest(
    @SerializedName("firstName")
    val firstName: String? = null,

    @SerializedName("phone")
    val phone: String? = null,

    @SerializedName("email")
    val email: String? = null
)

/**
 * Order Customers Response wrapper
 *
 * Backend returns: { success: true, data: OrderCustomer[] }
 */
data class OrderCustomersResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("data")
    val data: List<OrderCustomerDto>,

    @SerializedName("message")
    val message: String?
)

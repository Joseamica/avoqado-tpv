package com.jaac.avoqado_tpv.features.ordering.data.api

import com.jaac.avoqado_tpv.features.ordering.data.dto.AddCustomerToOrderRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.CreateAndAddCustomerRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.CustomerResponse
import com.jaac.avoqado_tpv.features.ordering.data.dto.CustomerSearchResponse
import com.jaac.avoqado_tpv.features.ordering.data.dto.OrderCustomersResponse
import com.jaac.avoqado_tpv.features.ordering.data.dto.QuickCreateCustomerRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit service interface for customer management endpoints.
 *
 * **Base URL:** https://api.avoqado.io/api/v1/ (production)
 *              https://patchiest-noncommemorational-willia.ngrok-free.dev/api/v1/ (development)
 *
 * **Authentication:** All requests require Bearer token in header.
 * ```
 * Authorization: Bearer {access_token}
 * ```
 *
 * @see avoqado-server/docs/clients&promotions/CUSTOMER_LOYALTY_PROMOTIONS_REFERENCE.md
 */
interface CustomerApiService {

    /**
     * Search customers by phone number.
     *
     * **Endpoint:** GET /tpv/venues/{venueId}/customers/search/phone
     *
     * **Backend Behavior:**
     * 1. Normalizes phone (removes spaces, dashes, parentheses)
     * 2. Searches with partial match (CONTAINS)
     * 3. Returns customers ordered by totalVisits (most frequent first)
     * 4. Includes customer group information
     *
     * **Use Case:** Primary lookup method at checkout
     *
     * **Success Response (200):**
     * ```json
     * {
     *   "message": "Found 3 customers",
     *   "data": [
     *     {
     *       "id": "cust_123",
     *       "firstName": "Juan",
     *       "lastName": "Pérez",
     *       "phone": "5512345678",
     *       "loyaltyPoints": 1250,
     *       "totalVisits": 15,
     *       "totalSpent": 4500.00,
     *       "customerGroup": { "id": "grp_1", "name": "VIP", "color": "#FFD700" }
     *     }
     *   ]
     * }
     * ```
     *
     * @param venueId ID of the venue (for tenant isolation)
     * @param phone Phone number to search (partial match supported)
     * @param limit Max results to return (default 5)
     * @return Response with matching customers
     */
    @GET("tpv/venues/{venueId}/customers/search/phone")
    suspend fun searchByPhone(
        @Path("venueId") venueId: String,
        @Query("phone") phone: String,
        @Query("limit") limit: Int = 5
    ): Response<CustomerSearchResponse>

    /**
     * Search customers by email address.
     *
     * **Endpoint:** GET /tpv/venues/{venueId}/customers/search/email
     *
     * **Backend Behavior:**
     * 1. Case-insensitive partial match
     * 2. Returns customers ordered by totalVisits
     *
     * @param venueId ID of the venue
     * @param email Email to search (partial match supported)
     * @param limit Max results to return (default 5)
     * @return Response with matching customers
     */
    @GET("tpv/venues/{venueId}/customers/search/email")
    suspend fun searchByEmail(
        @Path("venueId") venueId: String,
        @Query("email") email: String,
        @Query("limit") limit: Int = 5
    ): Response<CustomerSearchResponse>

    /**
     * General customer search (name, email, or phone).
     *
     * **Endpoint:** GET /tpv/venues/{venueId}/customers/search
     *
     * **Backend Behavior:**
     * 1. Searches across firstName, lastName, email, phone
     * 2. Requires minimum 2 characters
     * 3. Returns customers ordered by totalVisits (most frequent first)
     *
     * **Use Case:** Search box in TPV that accepts any input
     *
     * @param venueId ID of the venue
     * @param query Search query (min 2 chars)
     * @param limit Max results to return (default 10)
     * @return Response with matching customers
     */
    @GET("tpv/venues/{venueId}/customers/search")
    suspend fun searchCustomers(
        @Path("venueId") venueId: String,
        @Query("q") query: String,
        @Query("limit") limit: Int = 10
    ): Response<CustomerSearchResponse>

    /**
     * Get customer by ID.
     *
     * **Endpoint:** GET /tpv/venues/{venueId}/customers/{customerId}
     *
     * **Backend Behavior:**
     * 1. Returns customer with full details
     * 2. Includes customer group
     * 3. Validates customer belongs to venue
     *
     * **Use Case:** Retrieve customer details after selection
     *
     * @param venueId ID of the venue
     * @param customerId ID of the customer
     * @return Response with customer data
     */
    @GET("tpv/venues/{venueId}/customers/{customerId}")
    suspend fun getCustomer(
        @Path("venueId") venueId: String,
        @Path("customerId") customerId: String
    ): Response<CustomerResponse>

    /**
     * Get recent customers for quick selection.
     *
     * **Endpoint:** GET /tpv/venues/{venueId}/customers/recent
     *
     * **Backend Behavior:**
     * 1. Returns customers with recent visits
     * 2. Ordered by lastVisitAt (most recent first)
     * 3. Only includes customers with at least one visit
     *
     * **Use Case:** Quick selection in TPV without typing
     *
     * @param venueId ID of the venue
     * @param limit Max results to return (default 10)
     * @return Response with recent customers
     */
    @GET("tpv/venues/{venueId}/customers/recent")
    suspend fun getRecentCustomers(
        @Path("venueId") venueId: String,
        @Query("limit") limit: Int = 10
    ): Response<CustomerSearchResponse>

    /**
     * Quick create a customer during checkout.
     *
     * **Endpoint:** POST /tpv/venues/{venueId}/customers/quick
     *
     * **Backend Behavior:**
     * 1. Validates at least phone OR email provided
     * 2. Checks for duplicate phone/email
     * 3. If duplicate found, returns existing customer (no error)
     * 4. Creates new customer with minimal data
     *
     * **Use Case:** Fast customer creation at checkout (can complete details later in dashboard)
     *
     * **Success Response (201):**
     * ```json
     * {
     *   "message": "Customer created",
     *   "data": {
     *     "id": "cust_new",
     *     "firstName": "Juan",
     *     "phone": "5512345678",
     *     "loyaltyPoints": 0,
     *     "totalVisits": 0,
     *     "totalSpent": 0
     *   }
     * }
     * ```
     *
     * **Error Responses:**
     * - 400: At least phone or email required
     * - 401: Unauthorized
     *
     * @param venueId ID of the venue
     * @param request Customer data (at least phone or email required)
     * @return Response with created/existing customer
     */
    @POST("tpv/venues/{venueId}/customers/quick")
    suspend fun quickCreateCustomer(
        @Path("venueId") venueId: String,
        @Body request: QuickCreateCustomerRequest
    ): Response<CustomerResponse>

    // ========================================================================
    // ORDER-CUSTOMER ENDPOINTS (Multi-Customer per Order)
    // ========================================================================

    /**
     * Get all customers associated with an order.
     *
     * **Endpoint:** GET /tpv/venues/{venueId}/orders/{orderId}/customers
     *
     * **Backend Behavior:**
     * 1. Returns all OrderCustomer junction records with full Customer details
     * 2. Ordered by addedAt (first added first)
     * 3. Includes isPrimary flag (first customer added = primary, receives loyalty points)
     *
     * **Use Case:** Load customer chips when viewing order in GuestTab
     *
     * @param venueId ID of the venue
     * @param orderId ID of the order
     * @return Response with list of OrderCustomer records
     */
    @GET("tpv/venues/{venueId}/orders/{orderId}/customers")
    suspend fun getOrderCustomers(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String
    ): Response<OrderCustomersResponse>

    /**
     * Add an existing customer to an order.
     *
     * **Endpoint:** POST /tpv/venues/{venueId}/orders/{orderId}/customers
     *
     * **Backend Behavior:**
     * 1. Creates OrderCustomer junction record
     * 2. First customer added becomes isPrimary=true
     * 3. Subsequent customers are isPrimary=false
     * 4. Duplicate check: Same customer cannot be added twice to same order
     *
     * **Use Case:** Click on search result → customer is immediately added
     *
     * @param venueId ID of the venue
     * @param orderId ID of the order
     * @param request Request containing customerId
     * @return Response with updated list of OrderCustomer records
     */
    @POST("tpv/venues/{venueId}/orders/{orderId}/customers")
    suspend fun addCustomerToOrder(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body request: AddCustomerToOrderRequest
    ): Response<OrderCustomersResponse>

    /**
     * Create a new customer and add to order in one operation.
     *
     * **Endpoint:** POST /tpv/venues/{venueId}/orders/{orderId}/customers/create
     *
     * **Backend Behavior:**
     * 1. Validates at least one field (firstName, phone, or email)
     * 2. Creates new Customer record
     * 3. Creates OrderCustomer junction record
     * 4. First customer = isPrimary=true
     *
     * **Use Case:** Inline customer creation form in GuestTab
     *
     * @param venueId ID of the venue
     * @param orderId ID of the order
     * @param request Request containing firstName, phone, and/or email (at least one required)
     * @return Response with updated list of OrderCustomer records
     */
    @POST("tpv/venues/{venueId}/orders/{orderId}/customers/create")
    suspend fun createAndAddCustomerToOrder(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body request: CreateAndAddCustomerRequest
    ): Response<OrderCustomersResponse>

    /**
     * Remove a customer from an order.
     *
     * **Endpoint:** DELETE /tpv/venues/{venueId}/orders/{orderId}/customers/{customerId}
     *
     * **Backend Behavior:**
     * 1. Deletes OrderCustomer junction record
     * 2. If removed customer was isPrimary, promotes next oldest customer to primary
     * 3. Does NOT delete the Customer record itself
     *
     * **Use Case:** Click (X) on customer chip in GuestTab
     *
     * @param venueId ID of the venue
     * @param orderId ID of the order
     * @param customerId ID of the customer to remove
     * @return Response with updated list of OrderCustomer records
     */
    @DELETE("tpv/venues/{venueId}/orders/{orderId}/customers/{customerId}")
    suspend fun removeCustomerFromOrder(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Path("customerId") customerId: String
    ): Response<OrderCustomersResponse>
}

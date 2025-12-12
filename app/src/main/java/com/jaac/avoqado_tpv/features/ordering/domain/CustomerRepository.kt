package com.jaac.avoqado_tpv.features.ordering.domain

/**
 * CustomerRepository - Repository interface for customer operations
 *
 * Responsible for:
 * - Searching customers by phone, email, or general query
 * - Creating customers during checkout (quick create)
 * - Fetching customer details for loyalty display
 * - Getting recent customers for quick selection
 *
 * Clean Architecture:
 * - Domain layer interface
 * - Implemented in data layer (CustomerRepositoryImpl)
 * - Consumed by MenuViewModel/GuestTab for customer lookup
 *
 * @see avoqado-server/docs/clients&promotions/CUSTOMER_LOYALTY_PROMOTIONS_REFERENCE.md
 */
interface CustomerRepository {

    /**
     * Search customers by phone number
     *
     * Primary lookup method at checkout.
     * Returns customers ordered by totalVisits (most frequent first).
     *
     * @param venueId Venue identifier (tenant isolation)
     * @param phone Phone number to search (partial match supported)
     * @param limit Max results to return (default 5)
     * @return Result with list of matching customers
     */
    suspend fun searchByPhone(
        venueId: String,
        phone: String,
        limit: Int = 5
    ): Result<List<Customer>>

    /**
     * Search customers by email address
     *
     * @param venueId Venue identifier
     * @param email Email to search (partial match supported)
     * @param limit Max results to return (default 5)
     * @return Result with list of matching customers
     */
    suspend fun searchByEmail(
        venueId: String,
        email: String,
        limit: Int = 5
    ): Result<List<Customer>>

    /**
     * General customer search (name, email, or phone)
     *
     * Searches across firstName, lastName, email, phone.
     * Used for search box that accepts any input.
     *
     * @param venueId Venue identifier
     * @param query Search query (min 2 chars required)
     * @param limit Max results to return (default 10)
     * @return Result with list of matching customers
     */
    suspend fun searchCustomers(
        venueId: String,
        query: String,
        limit: Int = 10
    ): Result<List<Customer>>

    /**
     * Get customer by ID
     *
     * Fetches full customer details including loyalty info.
     *
     * @param venueId Venue identifier
     * @param customerId Customer identifier
     * @return Result with customer or error if not found
     */
    suspend fun getCustomer(
        venueId: String,
        customerId: String
    ): Result<Customer>

    /**
     * Get recent customers for quick selection
     *
     * Returns customers with recent visits, ordered by lastVisitAt.
     * Used for quick selection without typing.
     *
     * @param venueId Venue identifier
     * @param limit Max results to return (default 10)
     * @return Result with list of recent customers
     */
    suspend fun getRecentCustomers(
        venueId: String,
        limit: Int = 10
    ): Result<List<Customer>>

    /**
     * Quick create a customer during checkout
     *
     * Creates customer with minimal data (can be completed later in dashboard).
     * If duplicate phone/email found, returns existing customer.
     *
     * @param venueId Venue identifier
     * @param firstName Optional first name
     * @param lastName Optional last name
     * @param phone Optional phone (at least phone or email required)
     * @param email Optional email (at least phone or email required)
     * @return Result with created/existing customer
     */
    suspend fun quickCreateCustomer(
        venueId: String,
        firstName: String?,
        lastName: String?,
        phone: String?,
        email: String?
    ): Result<Customer>
}

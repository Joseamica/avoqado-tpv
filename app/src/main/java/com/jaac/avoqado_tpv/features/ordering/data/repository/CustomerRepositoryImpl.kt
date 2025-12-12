package com.jaac.avoqado_tpv.features.ordering.data.repository

import com.jaac.avoqado_tpv.features.ordering.data.api.CustomerApiService
import com.jaac.avoqado_tpv.features.ordering.data.dto.QuickCreateCustomerRequest
import com.jaac.avoqado_tpv.features.ordering.data.mappers.toCustomer
import com.jaac.avoqado_tpv.features.ordering.data.mappers.toCustomers
import com.jaac.avoqado_tpv.features.ordering.domain.Customer
import com.jaac.avoqado_tpv.features.ordering.domain.CustomerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CustomerRepositoryImpl - Implementation of CustomerRepository
 *
 * Responsibilities:
 * - Search customers via backend API
 * - Quick create customers during checkout
 * - Map DTOs to domain models
 * - Handle errors and return Result<T>
 *
 * Backend Integration:
 * - Uses CustomerApiService for all operations
 * - Endpoints: /tpv/venues/{venueId}/customers/...
 *
 * @see avoqado-server/docs/clients&promotions/CUSTOMER_LOYALTY_PROMOTIONS_REFERENCE.md
 */
@Singleton
class CustomerRepositoryImpl @Inject constructor(
    private val apiService: CustomerApiService
) : CustomerRepository {

    override suspend fun searchByPhone(
        venueId: String,
        phone: String,
        limit: Int
    ): Result<List<Customer>> = withContext(Dispatchers.IO) {
        try {
            Timber.d("🔍 [Customer] Searching by phone: $phone (venueId=$venueId)")

            val response = apiService.searchByPhone(venueId, phone, limit)

            if (response.isSuccessful && response.body() != null) {
                val customers = response.body()!!.data.toCustomers()
                Timber.i("✅ Found ${customers.size} customers by phone")
                Result.success(customers)
            } else {
                val error = "Failed to search customers: HTTP ${response.code()}"
                Timber.e("❌ $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error searching customers by phone")
            Result.failure(e)
        }
    }

    override suspend fun searchByEmail(
        venueId: String,
        email: String,
        limit: Int
    ): Result<List<Customer>> = withContext(Dispatchers.IO) {
        try {
            Timber.d("🔍 [Customer] Searching by email: $email (venueId=$venueId)")

            val response = apiService.searchByEmail(venueId, email, limit)

            if (response.isSuccessful && response.body() != null) {
                val customers = response.body()!!.data.toCustomers()
                Timber.i("✅ Found ${customers.size} customers by email")
                Result.success(customers)
            } else {
                val error = "Failed to search customers: HTTP ${response.code()}"
                Timber.e("❌ $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error searching customers by email")
            Result.failure(e)
        }
    }

    override suspend fun searchCustomers(
        venueId: String,
        query: String,
        limit: Int
    ): Result<List<Customer>> = withContext(Dispatchers.IO) {
        try {
            // Skip search if query is too short
            if (query.length < 2) {
                Timber.d("🔍 [Customer] Query too short: $query")
                return@withContext Result.success(emptyList())
            }

            Timber.d("🔍 [Customer] General search: $query (venueId=$venueId)")

            val response = apiService.searchCustomers(venueId, query, limit)

            if (response.isSuccessful && response.body() != null) {
                val customers = response.body()!!.data.toCustomers()
                Timber.i("✅ Found ${customers.size} customers for query: $query")
                Result.success(customers)
            } else {
                val error = "Failed to search customers: HTTP ${response.code()}"
                Timber.e("❌ $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error searching customers")
            Result.failure(e)
        }
    }

    override suspend fun getCustomer(
        venueId: String,
        customerId: String
    ): Result<Customer> = withContext(Dispatchers.IO) {
        try {
            Timber.d("👤 [Customer] Getting customer: $customerId (venueId=$venueId)")

            val response = apiService.getCustomer(venueId, customerId)

            if (response.isSuccessful && response.body() != null) {
                val customer = response.body()!!.data.toCustomer()
                Timber.i("✅ Got customer: ${customer.displayName}")
                Result.success(customer)
            } else {
                val error = when (response.code()) {
                    404 -> "Customer not found"
                    else -> "Failed to get customer: HTTP ${response.code()}"
                }
                Timber.e("❌ $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error getting customer")
            Result.failure(e)
        }
    }

    override suspend fun getRecentCustomers(
        venueId: String,
        limit: Int
    ): Result<List<Customer>> = withContext(Dispatchers.IO) {
        try {
            Timber.d("🕐 [Customer] Getting recent customers (venueId=$venueId)")

            val response = apiService.getRecentCustomers(venueId, limit)

            if (response.isSuccessful && response.body() != null) {
                val customers = response.body()!!.data.toCustomers()
                Timber.i("✅ Got ${customers.size} recent customers")
                Result.success(customers)
            } else {
                val error = "Failed to get recent customers: HTTP ${response.code()}"
                Timber.e("❌ $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error getting recent customers")
            Result.failure(e)
        }
    }

    override suspend fun quickCreateCustomer(
        venueId: String,
        firstName: String?,
        lastName: String?,
        phone: String?,
        email: String?
    ): Result<Customer> = withContext(Dispatchers.IO) {
        try {
            // Validate at least phone or email provided
            if (phone.isNullOrBlank() && email.isNullOrBlank()) {
                val error = "At least phone or email is required"
                Timber.e("❌ $error")
                return@withContext Result.failure(Exception(error))
            }

            Timber.d("🆕 [Customer] Quick create: phone=$phone, email=$email (venueId=$venueId)")

            val request = QuickCreateCustomerRequest(
                firstName = firstName?.takeIf { it.isNotBlank() },
                lastName = lastName?.takeIf { it.isNotBlank() },
                phone = phone?.takeIf { it.isNotBlank() },
                email = email?.takeIf { it.isNotBlank() }
            )

            val response = apiService.quickCreateCustomer(venueId, request)

            if (response.isSuccessful && response.body() != null) {
                val customer = response.body()!!.data.toCustomer()
                Timber.i("✅ Customer created/found: ${customer.displayName} (id=${customer.id})")
                Result.success(customer)
            } else {
                val error = when (response.code()) {
                    400 -> "Invalid customer data"
                    else -> "Failed to create customer: HTTP ${response.code()}"
                }
                Timber.e("❌ $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error creating customer")
            Result.failure(e)
        }
    }
}

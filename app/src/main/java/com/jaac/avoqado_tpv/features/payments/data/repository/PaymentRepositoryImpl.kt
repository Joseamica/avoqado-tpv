package com.jaac.avoqado_tpv.features.payments.data.repository

import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.features.payments.data.dto.PaymentHistoryRequestBody
import com.jaac.avoqado_tpv.features.payments.data.dto.toDomain
import com.jaac.avoqado_tpv.features.payments.domain.models.PaginatedPayments
import com.jaac.avoqado_tpv.features.payments.domain.repository.PaymentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Payment Repository Implementation
 *
 * Fetches payment history from backend API.
 * Handles pagination, filtering, and error handling.
 *
 * **Data Flow:**
 * 1. Build request with pagination params and filters
 * 2. Call ApiService.getPaymentHistory()
 * 3. Map DTOs to domain models
 * 4. Return Result<PaginatedPayments>
 *
 * **Error Handling:**
 * - Network errors → ApiException.NetworkError
 * - HTTP errors → ApiException.HttpError
 * - Parse errors → ApiException.ParseError
 *
 * @param apiService Retrofit API service for network calls
 */
@Singleton
class PaymentRepositoryImpl @Inject constructor(
    private val apiService: ApiService
) : PaymentRepository {

    override suspend fun getPaymentHistory(
        venueId: String,
        pageNumber: Int,
        pageSize: Int,
        fromDate: Instant?,
        toDate: Instant?,
        staffId: String?
    ): Result<PaginatedPayments> = withContext(Dispatchers.IO) {
        try {
            Timber.d("💳 [PaymentRepository] Fetching payments (page=$pageNumber, size=$pageSize)")

            // Build request body (only filters)
            val requestBody = PaymentHistoryRequestBody(
                fromDate = fromDate?.let { formatInstant(it) },
                toDate = toDate?.let { formatInstant(it) },
                staffId = staffId
            )

            Timber.d("📤 [PaymentRepository] Request: pageSize=$pageSize, pageNumber=$pageNumber, fromDate=${requestBody.fromDate}, toDate=${requestBody.toDate}, staffId=$staffId")

            // Call API (pagination as query params, filters in body)
            val response = apiService.getPaymentHistory(
                venueId = venueId,
                pageSize = pageSize,
                pageNumber = pageNumber,
                request = requestBody
            )

            // Handle response
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!

                Timber.d("📥 [PaymentRepository] Response: success=${body.success}, count=${body.data.size}, total=${body.meta.total}")

                // Map DTOs to domain models
                val payments = body.data.map { it.toDomain() }

                val result = PaginatedPayments(
                    payments = payments,
                    total = body.meta.total,
                    page = body.meta.pageNumber,
                    pageSize = body.meta.pageSize
                )

                Timber.i("✅ [PaymentRepository] Fetched ${payments.size} payments (total: ${body.meta.total})")

                Result.Success(result)
            } else {
                val errorCode = response.code()
                val errorMessage = response.message()

                Timber.e("❌ [PaymentRepository] HTTP error: $errorCode - $errorMessage")

                Result.Error(ApiException.HttpError(errorCode, errorMessage))
            }
        } catch (e: java.io.IOException) {
            Timber.e(e, "❌ [PaymentRepository] Network error")
            Result.Error(ApiException.NetworkError(e))
        } catch (e: Exception) {
            Timber.e(e, "❌ [PaymentRepository] Unknown error")
            Result.Error(ApiException.Unknown(e))
        }
    }

    /**
     * Format Instant to ISO 8601 string for API
     */
    private fun formatInstant(instant: Instant): String {
        return DateTimeFormatter.ISO_INSTANT.format(instant)
    }
}

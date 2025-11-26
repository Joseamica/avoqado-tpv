package com.jaac.avoqado_tpv.features.payments.domain.repository

import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.features.payments.domain.models.PaginatedPayments
import java.time.Instant

/**
 * Payment Repository Interface
 *
 * Contract for fetching payment history data.
 * Implementation will use ApiService to fetch from backend.
 *
 * Clean Architecture: Domain layer defines the contract, Data layer implements it.
 */
interface PaymentRepository {

    /**
     * Get payment history for a venue with pagination and filters
     *
     * **Performance**: Designed for 1GB RAM devices (PAX A80)
     * - Limit: 20 payments per page
     * - Cursor-based pagination for efficient scrolling
     * - Filters applied server-side to reduce payload
     *
     * **Pattern**: Toast POS + Square Terminal
     * - Paginated list with "Load More" button
     * - Date range filter for reconciliation
     * - Staff filter for audit trail
     *
     * @param venueId Venue ID (tenant isolation)
     * @param pageNumber Page number (1-indexed)
     * @param pageSize Number of items per page (default 20)
     * @param fromDate Filter start date (optional)
     * @param toDate Filter end date (optional)
     * @param staffId Filter by staff member (optional)
     * @return Paginated payment list with metadata
     */
    suspend fun getPaymentHistory(
        venueId: String,
        pageNumber: Int = 1,
        pageSize: Int = 20,
        fromDate: Instant? = null,
        toDate: Instant? = null,
        staffId: String? = null
    ): Result<PaginatedPayments>
}

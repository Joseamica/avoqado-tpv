package com.jaac.avoqado_tpv.features.reports.domain.repository

import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.features.reports.domain.models.ComparisonMetrics
import com.jaac.avoqado_tpv.features.reports.domain.models.PaymentMethodBreakdown
import com.jaac.avoqado_tpv.features.reports.domain.models.ReportPeriod
import com.jaac.avoqado_tpv.features.reports.domain.models.SalesSummary
import com.jaac.avoqado_tpv.features.shift.domain.Shift

/**
 * Reports Repository Interface
 *
 * Contract for fetching and aggregating report data.
 * Implementation will use ShiftRepository and OrderRepository as data sources.
 *
 * Clean Architecture: Domain layer defines the contract, Data layer implements it.
 */
interface ReportsRepository {
    /**
     * Get sales summary for a specific period
     *
     * @param venueId Venue ID (tenant isolation)
     * @param period Report period (time range)
     * @return Sales summary with aggregated metrics
     */
    suspend fun getSalesSummary(
        venueId: String,
        period: ReportPeriod
    ): Result<SalesSummary>

    /**
     * Get payment method breakdown for a specific period
     *
     * @param venueId Venue ID (tenant isolation)
     * @param period Report period (time range)
     * @return Payment method distribution
     */
    suspend fun getPaymentMethodBreakdown(
        venueId: String,
        period: ReportPeriod
    ): Result<PaymentMethodBreakdown>

    /**
     * Get shift history for a specific period
     *
     * @param venueId Venue ID (tenant isolation)
     * @param period Report period (time range)
     * @return List of shifts in the period
     */
    suspend fun getShiftHistory(
        venueId: String,
        period: ReportPeriod
    ): Result<List<Shift>>

    /**
     * Get comparison metrics (current vs previous period)
     *
     * @param venueId Venue ID (tenant isolation)
     * @param period Report period with comparison enabled
     * @return Comparison metrics with percentage changes
     */
    suspend fun getComparisonMetrics(
        venueId: String,
        period: ReportPeriod
    ): Result<ComparisonMetrics>

    /**
     * Get historical sales summaries grouped by time period
     *
     * Fetches aggregated sales data for multiple periods with automatic
     * period-over-period comparisons. Used for historical trend analysis.
     *
     * **Pattern**: Toast POS + Square Dashboard + Stripe Terminal
     * - Efficient pagination with cursor
     * - Automatic comparison calculations
     * - Room caching for offline support
     *
     * @param venueId Venue ID (tenant isolation)
     * @param grouping Time grouping (DAILY, WEEKLY, MONTHLY, QUARTERLY, YEARLY)
     * @param startDate Start of historical range
     * @param endDate End of historical range
     * @param cursor Pagination cursor (timestamp-based)
     * @param limit Number of periods to fetch (default 20)
     * @return Paginated list of historical periods with comparisons
     */
    suspend fun getHistoricalSummaries(
        venueId: String,
        grouping: com.jaac.avoqado_tpv.features.reports.domain.models.HistoricalGrouping,
        startDate: java.time.Instant,
        endDate: java.time.Instant,
        cursor: String? = null,
        limit: Int = 20
    ): Result<com.jaac.avoqado_tpv.features.reports.domain.models.PaginatedHistoricalData>
}

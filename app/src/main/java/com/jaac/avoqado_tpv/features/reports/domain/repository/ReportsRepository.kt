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
}

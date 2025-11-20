package com.jaac.avoqado_tpv.features.reports.data.aggregators

import com.jaac.avoqado_tpv.features.reports.domain.models.ComparisonMetrics
import com.jaac.avoqado_tpv.features.reports.domain.models.ReportPeriod
import com.jaac.avoqado_tpv.features.reports.domain.models.SalesSummary
import com.jaac.avoqado_tpv.features.shift.domain.Shift

/**
 * Comparison Calculator
 *
 * Utility for calculating period-over-period comparisons.
 * Computes percentage changes and trend indicators.
 *
 * Used by ReportsRepositoryImpl to generate comparison metrics.
 */
object ComparisonCalculator {
    /**
     * Calculate comparison metrics for a given period
     *
     * Requires period with comparison enabled (previousPeriodStart/End not null).
     *
     * @param allShifts List of all shifts
     * @param period Report period with comparison dates
     * @return Comparison metrics or null if comparison not enabled
     */
    fun calculateComparison(
        allShifts: List<Shift>,
        period: ReportPeriod
    ): ComparisonMetrics? {
        // Check if comparison mode is enabled
        if (period.previousPeriodStart == null || period.previousPeriodEnd == null) {
            return null
        }

        // Create periods for current and previous
        val currentPeriod = ReportPeriod.custom(period.startDate, period.endDate)
        val previousPeriod = ReportPeriod.custom(
            period.previousPeriodStart,
            period.previousPeriodEnd
        )

        // Get summaries for both periods
        val currentSummary = ShiftAggregator.getSummaryForPeriod(allShifts, currentPeriod)
        val previousSummary = ShiftAggregator.getSummaryForPeriod(allShifts, previousPeriod)

        // Calculate comparison
        return ComparisonMetrics.from(currentSummary, previousSummary)
    }

    /**
     * Get current period summary
     *
     * @param allShifts List of all shifts
     * @param period Report period
     * @return Sales summary for current period
     */
    fun getCurrentPeriodSummary(allShifts: List<Shift>, period: ReportPeriod): SalesSummary {
        return ShiftAggregator.getSummaryForPeriod(allShifts, period)
    }

    /**
     * Get previous period summary
     *
     * @param allShifts List of all shifts
     * @param period Report period with previous dates
     * @return Sales summary for previous period or null if not available
     */
    fun getPreviousPeriodSummary(allShifts: List<Shift>, period: ReportPeriod): SalesSummary? {
        if (period.previousPeriodStart == null || period.previousPeriodEnd == null) {
            return null
        }

        val previousPeriod = ReportPeriod.custom(
            period.previousPeriodStart,
            period.previousPeriodEnd
        )

        return ShiftAggregator.getSummaryForPeriod(allShifts, previousPeriod)
    }
}

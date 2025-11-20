package com.jaac.avoqado_tpv.features.reports.data.aggregators

import com.jaac.avoqado_tpv.features.reports.domain.models.PaymentMethodBreakdown
import com.jaac.avoqado_tpv.features.reports.domain.models.ReportPeriod
import com.jaac.avoqado_tpv.features.reports.domain.models.SalesSummary
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import java.time.Instant

/**
 * Shift Aggregator
 *
 * Utility for aggregating shift data into report metrics.
 * Filters shifts by time period and calculates summary statistics.
 *
 * Used by ReportsRepositoryImpl to transform raw shift data into domain models.
 */
object ShiftAggregator {
    /**
     * Filter shifts by period
     *
     * Only includes shifts that started within the period boundaries.
     *
     * @param shifts List of all shifts
     * @param period Report period filter
     * @return Filtered list of shifts
     */
    fun filterShiftsByPeriod(shifts: List<Shift>, period: ReportPeriod): List<Shift> {
        return shifts.filter { shift ->
            try {
                val shiftStart = Instant.parse(shift.startTime)
                shiftStart.isAfter(period.startDate) && shiftStart.isBefore(period.endDate)
            } catch (e: Exception) {
                // If parsing fails, exclude this shift
                false
            }
        }
    }

    /**
     * Aggregate shifts into sales summary
     *
     * @param shifts List of shifts to aggregate
     * @return Sales summary with totals and averages
     */
    fun aggregateSalesSummary(shifts: List<Shift>): SalesSummary {
        return SalesSummary.fromShifts(shifts)
    }

    /**
     * Aggregate shifts into payment method breakdown
     *
     * @param shifts List of shifts to aggregate
     * @return Payment method distribution
     */
    fun aggregatePaymentBreakdown(shifts: List<Shift>): PaymentMethodBreakdown {
        return PaymentMethodBreakdown.fromShifts(shifts)
    }

    /**
     * Get sales summary for a specific period
     *
     * Convenience method that filters and aggregates in one call.
     *
     * @param allShifts List of all shifts
     * @param period Period to filter by
     * @return Sales summary for the period
     */
    fun getSummaryForPeriod(allShifts: List<Shift>, period: ReportPeriod): SalesSummary {
        val filteredShifts = filterShiftsByPeriod(allShifts, period)
        return aggregateSalesSummary(filteredShifts)
    }

    /**
     * Get payment breakdown for a specific period
     *
     * Convenience method that filters and aggregates in one call.
     *
     * @param allShifts List of all shifts
     * @param period Period to filter by
     * @return Payment breakdown for the period
     */
    fun getPaymentBreakdownForPeriod(
        allShifts: List<Shift>,
        period: ReportPeriod
    ): PaymentMethodBreakdown {
        val filteredShifts = filterShiftsByPeriod(allShifts, period)
        return aggregatePaymentBreakdown(filteredShifts)
    }
}

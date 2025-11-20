package com.jaac.avoqado_tpv.features.reports.data.repository

import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.features.reports.data.aggregators.ComparisonCalculator
import com.jaac.avoqado_tpv.features.reports.data.aggregators.ShiftAggregator
import com.jaac.avoqado_tpv.features.reports.domain.models.ComparisonMetrics
import com.jaac.avoqado_tpv.features.reports.domain.models.PaymentMethodBreakdown
import com.jaac.avoqado_tpv.features.reports.domain.models.ReportPeriod
import com.jaac.avoqado_tpv.features.reports.domain.models.SalesSummary
import com.jaac.avoqado_tpv.features.reports.domain.repository.ReportsRepository
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reports Repository Implementation
 *
 * Aggregates shift data into report metrics.
 * Uses ShiftRepository as data source and client-side aggregation.
 *
 * **Data Flow:**
 * 1. Fetch all shifts from ShiftRepository
 * 2. Filter shifts by report period
 * 3. Aggregate using ShiftAggregator
 * 4. Return domain models
 *
 * **Performance Considerations:**
 * - Fetches large batch of shifts (limit = 200) for historical data
 * - Client-side filtering and aggregation
 * - Future optimization: Backend reporting endpoint
 *
 * @param shiftRepository Source for shift data
 */
@Singleton
class ReportsRepositoryImpl @Inject constructor(
    private val shiftRepository: ShiftRepository
) : ReportsRepository {

    /**
     * Get sales summary for a specific period
     */
    override suspend fun getSalesSummary(
        venueId: String,
        period: ReportPeriod
    ): Result<SalesSummary> = withContext(Dispatchers.IO) {
        try {
            Timber.d("📊 Fetching sales summary for period: ${period.getLabel()}")

            // Fetch shifts from repository
            val shiftsResult = fetchShiftsForPeriod(venueId, period)

            when (shiftsResult) {
                is Result.Success -> {
                    val shifts = shiftsResult.data

                    // Aggregate using ShiftAggregator
                    val summary = ShiftAggregator.getSummaryForPeriod(shifts, period)

                    Timber.i("✅ Sales summary: ${summary.totalSales}, ${summary.totalOrders} orders")
                    Result.Success(summary)
                }

                is Result.Error -> {
                    Timber.e("❌ Failed to fetch shifts: ${shiftsResult.exception}")
                    Result.Error(shiftsResult.exception)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error calculating sales summary")
            Result.Error(ApiException.Unknown(e))
        }
    }

    /**
     * Get payment method breakdown for a specific period
     */
    override suspend fun getPaymentMethodBreakdown(
        venueId: String,
        period: ReportPeriod
    ): Result<PaymentMethodBreakdown> = withContext(Dispatchers.IO) {
        try {
            Timber.d("💳 Fetching payment breakdown for period: ${period.getLabel()}")

            // Fetch shifts from repository
            val shiftsResult = fetchShiftsForPeriod(venueId, period)

            when (shiftsResult) {
                is Result.Success -> {
                    val shifts = shiftsResult.data

                    // Aggregate using ShiftAggregator
                    val breakdown = ShiftAggregator.getPaymentBreakdownForPeriod(shifts, period)

                    Timber.i("✅ Payment breakdown: Cash ${breakdown.cashPercentage}%, Card ${breakdown.cardPercentage}%")
                    Result.Success(breakdown)
                }

                is Result.Error -> {
                    Timber.e("❌ Failed to fetch shifts: ${shiftsResult.exception}")
                    Result.Error(shiftsResult.exception)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error calculating payment breakdown")
            Result.Error(ApiException.Unknown(e))
        }
    }

    /**
     * Get shift history for a specific period
     */
    override suspend fun getShiftHistory(
        venueId: String,
        period: ReportPeriod
    ): Result<List<Shift>> = withContext(Dispatchers.IO) {
        try {
            Timber.d("📋 Fetching shift history for period: ${period.getLabel()}")

            // Fetch shifts from repository
            val shiftsResult = fetchShiftsForPeriod(venueId, period)

            when (shiftsResult) {
                is Result.Success -> {
                    val shifts = shiftsResult.data

                    // Filter by period (already done in fetchShiftsForPeriod)
                    Timber.i("✅ Found ${shifts.size} shifts in period")
                    Result.Success(shifts)
                }

                is Result.Error -> {
                    Timber.e("❌ Failed to fetch shifts: ${shiftsResult.exception}")
                    Result.Error(shiftsResult.exception)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error fetching shift history")
            Result.Error(ApiException.Unknown(e))
        }
    }

    /**
     * Get comparison metrics (current vs previous period)
     */
    override suspend fun getComparisonMetrics(
        venueId: String,
        period: ReportPeriod
    ): Result<ComparisonMetrics> = withContext(Dispatchers.IO) {
        try {
            Timber.d("📈 Calculating comparison metrics for period: ${period.getLabel()}")

            // Check if comparison is enabled
            if (period.previousPeriodStart == null || period.previousPeriodEnd == null) {
                Timber.w("⚠️ Comparison not enabled for this period")
                return@withContext Result.Error(
                    ApiException.ValidationError("Comparison not enabled - previousPeriod dates are null")
                )
            }

            // Fetch all shifts (need both current and previous periods)
            // Use larger limit to cover both periods
            val shiftsResult = shiftRepository.getShiftHistory(venueId, limit = 200)

            when (shiftsResult) {
                is Result.Success -> {
                    val allShifts = shiftsResult.data

                    // Calculate comparison using ComparisonCalculator
                    val comparison = ComparisonCalculator.calculateComparison(allShifts, period)

                    if (comparison != null) {
                        Timber.i(
                            "✅ Comparison: Sales ${comparison.salesChange}%, " +
                                    "Orders ${comparison.ordersChange}%"
                        )
                        Result.Success(comparison)
                    } else {
                        Timber.w("⚠️ Failed to calculate comparison")
                        Result.Error(ApiException.ValidationError("Failed to calculate comparison metrics"))
                    }
                }

                is Result.Error -> {
                    Timber.e("❌ Failed to fetch shifts: ${shiftsResult.exception}")
                    Result.Error(shiftsResult.exception)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error calculating comparison metrics")
            Result.Error(ApiException.Unknown(e))
        }
    }

    /**
     * Fetch shifts for a given period
     *
     * Fetches a large batch of shifts and filters by period client-side.
     * Uses limit = 200 to cover most reporting periods (max ~6 months).
     *
     * @param venueId Venue ID for tenant isolation
     * @param period Report period filter
     * @return Result with filtered list of shifts
     */
    private suspend fun fetchShiftsForPeriod(
        venueId: String,
        period: ReportPeriod
    ): Result<List<Shift>> {
        // Fetch large batch of shifts (backend returns paginated)
        // TODO: Optimize with backend filtering by date range
        val shiftsResult = shiftRepository.getShiftHistory(venueId, limit = 200)

        return when (shiftsResult) {
            is Result.Success -> {
                val allShifts = shiftsResult.data

                // Filter shifts by period
                val filteredShifts = ShiftAggregator.filterShiftsByPeriod(allShifts, period)

                Timber.d("Filtered ${filteredShifts.size} shifts (from ${allShifts.size} total)")
                Result.Success(filteredShifts)
            }

            is Result.Error -> {
                shiftsResult
            }
        }
    }
}

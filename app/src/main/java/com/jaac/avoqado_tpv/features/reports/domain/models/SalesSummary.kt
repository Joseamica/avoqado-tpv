package com.jaac.avoqado_tpv.features.reports.domain.models

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Sales Summary Model
 *
 * Aggregated metrics for a given time period.
 * Calculated from multiple shifts or orders.
 *
 * Used in ReportsScreen to display key performance indicators (KPIs).
 *
 * @property totalSales Total revenue (sum of all shift.totalSales)
 * @property totalOrders Total number of orders processed
 * @property totalProductsSold Total quantity of products sold
 * @property totalTips Total tips collected
 * @property totalShifts Number of shifts in the period
 * @property averageOrderValue Average order value (totalSales / totalOrders)
 * @property averageProductsPerOrder Average products per order
 */
/**
 * Per-waiter tip breakdown
 */
data class WaiterTip(
    val staffId: String,
    val name: String,
    val amount: BigDecimal,
    val count: Int
)

data class SalesSummary(
    val totalSales: BigDecimal,
    val totalOrders: Int,
    val totalProductsSold: Int,
    val totalTips: BigDecimal,
    val totalShifts: Int,
    val averageOrderValue: BigDecimal,
    val averageProductsPerOrder: BigDecimal,
    val averageTipPercentage: BigDecimal = BigDecimal.ZERO,
    val ratingsCount: Int = 0,
    val waiterTips: List<WaiterTip> = emptyList()
) {
    companion object {
        /**
         * Create an empty summary (no data)
         */
        fun empty(): SalesSummary {
            return SalesSummary(
                totalSales = BigDecimal.ZERO,
                totalOrders = 0,
                totalProductsSold = 0,
                totalTips = BigDecimal.ZERO,
                totalShifts = 0,
                averageOrderValue = BigDecimal.ZERO,
                averageProductsPerOrder = BigDecimal.ZERO
            )
        }

        /**
         * Create summary from shift data
         *
         * @param shifts List of shifts to aggregate
         * @return Aggregated sales summary
         */
        fun fromShifts(shifts: List<com.jaac.avoqado_tpv.features.shift.domain.Shift>): SalesSummary {
            if (shifts.isEmpty()) {
                return empty()
            }

            val totalSales = shifts.sumOf { it.totalSales }
            val totalOrders = shifts.sumOf { it.totalOrders }
            val totalProductsSold = shifts.sumOf { it.totalProductsSold }
            val totalTips = shifts.sumOf { it.totalTips }

            val averageOrderValue = if (totalOrders > 0) {
                totalSales.divide(BigDecimal(totalOrders), 2, RoundingMode.HALF_UP)
            } else {
                BigDecimal.ZERO
            }

            val averageProductsPerOrder = if (totalOrders > 0) {
                BigDecimal(totalProductsSold).divide(BigDecimal(totalOrders), 2, RoundingMode.HALF_UP)
            } else {
                BigDecimal.ZERO
            }

            return SalesSummary(
                totalSales = totalSales,
                totalOrders = totalOrders,
                totalProductsSold = totalProductsSold,
                totalTips = totalTips,
                totalShifts = shifts.size,
                averageOrderValue = averageOrderValue,
                averageProductsPerOrder = averageProductsPerOrder
            )
        }
    }

    /**
     * Format total sales as currency string with thousands separator
     */
    fun formatTotalSales(): String {
        val amount = totalSales.setScale(2, RoundingMode.HALF_UP)
        return formatCurrency(amount)
    }

    /**
     * Format average order value as currency string with thousands separator
     */
    fun formatAverageOrderValue(): String {
        val amount = averageOrderValue.setScale(2, RoundingMode.HALF_UP)
        return formatCurrency(amount)
    }

    /**
     * Format currency with thousands separator
     * Examples: $1,234.56 | $10,000.00 | $0.00
     */
    private fun formatCurrency(amount: BigDecimal): String {
        val formatter = java.text.DecimalFormat("#,##0.00", java.text.DecimalFormatSymbols(java.util.Locale.US))
        return "$${formatter.format(amount)}"
    }
}

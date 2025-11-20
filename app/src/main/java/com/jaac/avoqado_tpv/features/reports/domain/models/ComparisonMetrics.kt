package com.jaac.avoqado_tpv.features.reports.domain.models

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Comparison Metrics
 *
 * Period-over-period comparison data.
 * Example: This week vs last week, this month vs last month.
 *
 * Used to show growth trends with percentage changes.
 *
 * @property currentPeriod Summary for current period
 * @property previousPeriod Summary for previous period
 * @property salesChange Percentage change in sales
 * @property ordersChange Percentage change in orders
 * @property productsChange Percentage change in products sold
 * @property avgOrderValueChange Percentage change in average order value
 */
data class ComparisonMetrics(
    val currentPeriod: SalesSummary,
    val previousPeriod: SalesSummary,
    val salesChange: BigDecimal,
    val ordersChange: BigDecimal,
    val productsChange: BigDecimal,
    val avgOrderValueChange: BigDecimal
) {
    /**
     * Comparison entry for a specific metric
     */
    data class ComparisonEntry(
        val currentValue: BigDecimal,
        val previousValue: BigDecimal,
        val changePercentage: BigDecimal,
        val trend: Trend
    ) {
        fun formatChange(): String {
            val sign = if (changePercentage >= BigDecimal.ZERO) "+" else ""
            return "$sign${changePercentage.setScale(1, RoundingMode.HALF_UP)}%"
        }
    }

    enum class Trend {
        UP,      // Positive growth
        DOWN,    // Decline
        FLAT     // No change (within ±0.1%)
    }

    companion object {
        /**
         * Calculate comparison metrics from current and previous summaries
         */
        fun from(current: SalesSummary, previous: SalesSummary): ComparisonMetrics {
            return ComparisonMetrics(
                currentPeriod = current,
                previousPeriod = previous,
                salesChange = calculatePercentageChange(previous.totalSales, current.totalSales),
                ordersChange = calculatePercentageChange(
                    BigDecimal(previous.totalOrders),
                    BigDecimal(current.totalOrders)
                ),
                productsChange = calculatePercentageChange(
                    BigDecimal(previous.totalProductsSold),
                    BigDecimal(current.totalProductsSold)
                ),
                avgOrderValueChange = calculatePercentageChange(
                    previous.averageOrderValue,
                    current.averageOrderValue
                )
            )
        }

        /**
         * Calculate percentage change between two values
         * Formula: ((current - previous) / previous) * 100
         */
        private fun calculatePercentageChange(previous: BigDecimal, current: BigDecimal): BigDecimal {
            if (previous == BigDecimal.ZERO) {
                return if (current > BigDecimal.ZERO) BigDecimal(100) else BigDecimal.ZERO
            }

            val difference = current - previous
            return (difference.divide(previous, 4, RoundingMode.HALF_UP) * BigDecimal(100))
                .setScale(2, RoundingMode.HALF_UP)
        }
    }

    /**
     * Get trend for a given percentage change
     */
    private fun getTrend(change: BigDecimal): Trend {
        return when {
            change > BigDecimal(0.1) -> Trend.UP
            change < BigDecimal(-0.1) -> Trend.DOWN
            else -> Trend.FLAT
        }
    }

    /**
     * Get sales comparison entry
     */
    fun getSalesComparison(): ComparisonEntry {
        return ComparisonEntry(
            currentValue = currentPeriod.totalSales,
            previousValue = previousPeriod.totalSales,
            changePercentage = salesChange,
            trend = getTrend(salesChange)
        )
    }

    /**
     * Get orders comparison entry
     */
    fun getOrdersComparison(): ComparisonEntry {
        return ComparisonEntry(
            currentValue = BigDecimal(currentPeriod.totalOrders),
            previousValue = BigDecimal(previousPeriod.totalOrders),
            changePercentage = ordersChange,
            trend = getTrend(ordersChange)
        )
    }

    /**
     * Get products comparison entry
     */
    fun getProductsComparison(): ComparisonEntry {
        return ComparisonEntry(
            currentValue = BigDecimal(currentPeriod.totalProductsSold),
            previousValue = BigDecimal(previousPeriod.totalProductsSold),
            changePercentage = productsChange,
            trend = getTrend(productsChange)
        )
    }

    /**
     * Get average order value comparison entry
     */
    fun getAvgOrderValueComparison(): ComparisonEntry {
        return ComparisonEntry(
            currentValue = currentPeriod.averageOrderValue,
            previousValue = previousPeriod.averageOrderValue,
            changePercentage = avgOrderValueChange,
            trend = getTrend(avgOrderValueChange)
        )
    }
}

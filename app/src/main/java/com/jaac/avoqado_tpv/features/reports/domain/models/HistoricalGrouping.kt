package com.jaac.avoqado_tpv.features.reports.domain.models

/**
 * Time grouping options for historical data aggregation
 *
 * Defines how sales data should be grouped when viewing historical trends.
 *
 * **World-Class Pattern (Toast POS + Square + Stripe)**:
 * - DAILY: Best for recent analysis (last 7-30 days)
 * - WEEKLY: Good for monthly/quarterly views
 * - MONTHLY: Standard for yearly analysis
 * - QUARTERLY: Financial reporting periods
 * - YEARLY: Long-term trend analysis
 */
enum class HistoricalGrouping {
    /**
     * Group by calendar day (00:00 to 23:59)
     * Label: "15 Enero 2025"
     * Subtitle: "Martes"
     */
    DAILY,

    /**
     * Group by calendar week (Monday to Sunday)
     * Label: "Semana 3, Enero 2025"
     * Subtitle: ""
     */
    WEEKLY,

    /**
     * Group by calendar month
     * Label: "Enero 2025"
     * Subtitle: ""
     */
    MONTHLY,

    /**
     * Group by quarter (Q1: Jan-Mar, Q2: Apr-Jun, Q3: Jul-Sep, Q4: Oct-Dec)
     * Label: "Q1 2025"
     * Subtitle: ""
     */
    QUARTERLY,

    /**
     * Group by calendar year
     * Label: "2025"
     * Subtitle: ""
     */
    YEARLY
}

/**
 * Get suggested grouping based on date range length
 *
 * Automatically selects appropriate grouping for optimal UX:
 * - < 7 days → DAILY
 * - 7-30 days → DAILY
 * - 31-90 days → WEEKLY
 * - 91-365 days → MONTHLY
 * - > 365 days → QUARTERLY
 */
fun suggestGrouping(days: Int): HistoricalGrouping {
    return when {
        days <= 30 -> HistoricalGrouping.DAILY
        days <= 90 -> HistoricalGrouping.WEEKLY
        days <= 365 -> HistoricalGrouping.MONTHLY
        else -> HistoricalGrouping.QUARTERLY
    }
}

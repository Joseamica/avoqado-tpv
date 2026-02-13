package com.jaac.avoqado_tpv.features.reports.domain.models

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Report Period Selector
 *
 * Represents different time ranges for filtering reports:
 * - Quick filters: Last 7/30/90 days
 * - Custom date range
 * - Comparison mode (current vs previous period)
 *
 * @property startDate Start of the period (inclusive)
 * @property endDate End of the period (inclusive)
 * @property type Type of period for UI display
 * @property previousPeriodStart Start of previous period (for comparison mode)
 * @property previousPeriodEnd End of previous period (for comparison mode)
 */
data class ReportPeriod(
    val startDate: Instant,
    val endDate: Instant,
    val type: PeriodType,
    val previousPeriodStart: Instant? = null,
    val previousPeriodEnd: Instant? = null
) {
    /**
     * Check if this period includes the current time
     * (used to determine if real-time updates should be enabled)
     */
    fun includesCurrentTime(): Boolean {
        val now = Instant.now()
        return now.isAfter(startDate) && now.isBefore(endDate.plus(1, ChronoUnit.DAYS))
    }

    /**
     * Get human-readable label for this period
     */
    fun getLabel(): String = when (type) {
        PeriodType.TODAY -> "Hoy"
        PeriodType.LAST_7_DAYS -> "Últimos 7 días"
        PeriodType.LAST_30_DAYS -> "Últimos 30 días"
        PeriodType.LAST_90_DAYS -> "Últimos 90 días"
        PeriodType.CUSTOM -> {
            // Use SimpleDateFormat for API < 26 compatibility
            val formatter = java.text.SimpleDateFormat("dd MMM", java.util.Locale("es", "ES")).apply { timeZone = java.util.TimeZone.getTimeZone("America/Mexico_City") }
            val start = formatter.format(java.util.Date.from(startDate))
            val end = formatter.format(java.util.Date.from(endDate))
            "$start - $end"
        }
    }

    companion object {
        /**
         * Create a period for today (current day from 00:00 to 23:59)
         *
         * **Default period** - Most managers check "today's sales" first thing
         */
        fun today(zoneId: ZoneId = ZoneId.of("America/Mexico_City")): ReportPeriod {
            val today = LocalDate.now(zoneId)

            val startOfDay = today.atStartOfDay(zoneId).toInstant()
            val endOfDay = today.atTime(23, 59, 59).atZone(zoneId).toInstant()

            return ReportPeriod(
                startDate = startOfDay,
                endDate = endOfDay,
                type = PeriodType.TODAY
            )
        }

        /**
         * Create a period for the last N days
         */
        fun last7Days(): ReportPeriod {
            val end = Instant.now()
            val start = end.minus(7, ChronoUnit.DAYS)
            return ReportPeriod(
                startDate = start,
                endDate = end,
                type = PeriodType.LAST_7_DAYS
            )
        }

        fun last30Days(): ReportPeriod {
            val end = Instant.now()
            val start = end.minus(30, ChronoUnit.DAYS)
            return ReportPeriod(
                startDate = start,
                endDate = end,
                type = PeriodType.LAST_30_DAYS
            )
        }

        fun last90Days(): ReportPeriod {
            val end = Instant.now()
            val start = end.minus(90, ChronoUnit.DAYS)
            return ReportPeriod(
                startDate = start,
                endDate = end,
                type = PeriodType.LAST_90_DAYS
            )
        }

        /**
         * Create a custom period with specified dates
         */
        fun custom(startDate: Instant, endDate: Instant): ReportPeriod {
            return ReportPeriod(
                startDate = startDate,
                endDate = endDate,
                type = PeriodType.CUSTOM
            )
        }

        /**
         * Create a comparison period (current vs previous)
         * Example: Last 7 days vs previous 7 days
         *
         * @param currentStart Start of current period
         * @param currentEnd End of current period
         * @param periodType Type of the base period (to preserve in result)
         */
        fun comparison(
            currentStart: Instant,
            currentEnd: Instant,
            periodType: PeriodType = PeriodType.LAST_7_DAYS
        ): ReportPeriod {
            val periodDays = ChronoUnit.DAYS.between(currentStart, currentEnd)
            val previousEnd = currentStart.minus(1, ChronoUnit.DAYS)
            val previousStart = previousEnd.minus(periodDays, ChronoUnit.DAYS)

            return ReportPeriod(
                startDate = currentStart,
                endDate = currentEnd,
                type = periodType,  // Preserve the original period type
                previousPeriodStart = previousStart,
                previousPeriodEnd = previousEnd
            )
        }
    }
}

/**
 * Types of report periods
 *
 * Note: Comparison is not a period type - it's a separate toggle that applies
 * to any of these period types (handled in ReportsViewModel.isComparisonEnabled)
 */
enum class PeriodType {
    TODAY,           // Default: Current day (00:00 to 23:59)
    LAST_7_DAYS,
    LAST_30_DAYS,
    LAST_90_DAYS,
    CUSTOM
}

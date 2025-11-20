package com.jaac.avoqado_tpv.features.reports.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.features.reports.domain.models.ComparisonMetrics
import com.jaac.avoqado_tpv.features.reports.domain.models.PaymentMethodBreakdown
import com.jaac.avoqado_tpv.features.reports.domain.models.PeriodType
import com.jaac.avoqado_tpv.features.reports.domain.models.ReportPeriod
import com.jaac.avoqado_tpv.features.reports.domain.models.SalesSummary
import com.jaac.avoqado_tpv.features.reports.domain.repository.ReportsRepository
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject

/**
 * Reports ViewModel
 *
 * Manages reports screen state and business logic.
 * Aggregates shift data into sales summaries, payment breakdowns, and comparisons.
 *
 * **Features:**
 * - Load reports by period (7d, 30d, 90d, custom, comparison)
 * - Real-time updates via Socket.IO (Toast POS pattern)
 * - Payment method breakdown charts
 * - Period-over-period comparisons
 *
 * **Socket.IO Integration:**
 * - Auto-refreshes reports when payment_completed or shift_updated events occur
 * - Only refreshes if viewing current period (today, this week)
 * - Prevents unnecessary reloads for historical reports
 *
 * **Usage:**
 * ```kotlin
 * @Composable
 * fun ReportsScreen(viewModel: ReportsViewModel = hiltViewModel()) {
 *     val state by viewModel.state.collectAsStateWithLifecycle()
 *
 *     when (val currentState = state) {
 *         is ReportsState.Loading -> AvoqadoLoadingOverlay()
 *         is ReportsState.Success -> ReportsContent(currentState.data)
 *         is ReportsState.Error -> ErrorMessage(currentState.message)
 *     }
 * }
 * ```
 */
@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val reportsRepository: ReportsRepository,
    private val secureStorage: SecureStorage,
    private val socketManager: SocketManager
) : ViewModel() {

    // ══════════════════════════════════════════════════════════════════════
    // STATE
    // ══════════════════════════════════════════════════════════════════════

    private val _state = MutableStateFlow<ReportsState>(ReportsState.Loading)
    val state: StateFlow<ReportsState> = _state.asStateFlow()

    private var currentPeriod: ReportPeriod = ReportPeriod.last7Days()

    // Cache: Store reports by period type to avoid re-fetching
    private val reportsCache = mutableMapOf<PeriodType, ReportsState.Success>()

    // ══════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ══════════════════════════════════════════════════════════════════════

    init {
        // Load initial reports (last 7 days)
        loadReports(currentPeriod)

        // Listen to Socket.IO events for real-time updates
        collectSocketEvents()
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC METHODS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Load reports for a specific period
     *
     * Fetches sales summary, payment breakdown, shift history, and optional comparison.
     * Uses cache to avoid unnecessary re-fetching of already loaded periods.
     *
     * @param period Report period (time range and type)
     */
    fun loadReports(period: ReportPeriod) {
        viewModelScope.launch {
            try {
                Timber.d("📊 Loading reports for period: ${period.getLabel()}")

                // Check cache first (except for comparison mode, which should always refresh)
                if (period.previousPeriodStart == null) {
                    reportsCache[period.type]?.let { cachedReport ->
                        Timber.d("✅ Using cached report for ${period.type}")
                        _state.value = cachedReport
                        currentPeriod = period
                        return@launch
                    }
                }

                _state.value = ReportsState.Loading

                // Store current period for refresh logic
                currentPeriod = period

                val venueId = secureStorage.getVenueId()
                if (venueId == null) {
                    Timber.e("❌ No venueId found in storage")
                    _state.value = ReportsState.Error("No se encontró información del local")
                    return@launch
                }

                // Fetch sales summary
                val summaryResult = reportsRepository.getSalesSummary(venueId, period)
                when (summaryResult) {
                    is Result.Error -> {
                        Timber.e("❌ Failed to load sales summary: ${summaryResult.exception}")
                        _state.value = ReportsState.Error(
                            summaryResult.exception.userMessage
                        )
                        return@launch
                    }
                    is Result.Success -> {
                        // Continue to next step
                    }
                }

                // Fetch payment breakdown
                val breakdownResult = reportsRepository.getPaymentMethodBreakdown(venueId, period)
                when (breakdownResult) {
                    is Result.Error -> {
                        Timber.e("❌ Failed to load payment breakdown: ${breakdownResult.exception}")
                        _state.value = ReportsState.Error(
                            breakdownResult.exception.userMessage
                        )
                        return@launch
                    }
                    is Result.Success -> {
                        // Continue to next step
                    }
                }

                // Fetch shift history
                val shiftsResult = reportsRepository.getShiftHistory(venueId, period)
                when (shiftsResult) {
                    is Result.Error -> {
                        Timber.e("❌ Failed to load shift history: ${shiftsResult.exception}")
                        _state.value = ReportsState.Error(
                            shiftsResult.exception.userMessage
                        )
                        return@launch
                    }
                    is Result.Success -> {
                        // Continue to next step
                    }
                }

                // Fetch comparison metrics (if comparison mode enabled)
                val comparison = if (period.previousPeriodStart != null) {
                    val comparisonResult = reportsRepository.getComparisonMetrics(venueId, period)
                    when (comparisonResult) {
                        is Result.Success -> comparisonResult.data
                        is Result.Error -> {
                            Timber.w("⚠️ Failed to load comparison, continuing without it")
                            null
                        }
                    }
                } else {
                    null
                }

                // Success - update state and cache
                val summary = (summaryResult as Result.Success<SalesSummary>).data
                val breakdown = (breakdownResult as Result.Success<PaymentMethodBreakdown>).data
                val shifts = (shiftsResult as Result.Success<List<Shift>>).data

                val successState = ReportsState.Success(
                    summary = summary,
                    paymentBreakdown = breakdown,
                    shifts = shifts,
                    comparison = comparison,
                    period = period,
                    lastUpdated = Instant.now()
                )

                _state.value = successState

                // Store in cache (only for non-comparison periods)
                if (period.previousPeriodStart == null) {
                    reportsCache[period.type] = successState
                }

                Timber.i("✅ Reports loaded successfully: ${summary.totalSales} in sales, ${shifts.size} shifts")

            } catch (e: Exception) {
                Timber.e(e, "❌ Unexpected error loading reports")
                _state.value = ReportsState.Error(
                    e.message ?: "Error inesperado al cargar reportes"
                )
            }
        }
    }

    /**
     * Change report period
     *
     * Called when user selects a different period (7d, 30d, 90d, custom, comparison).
     * For CUSTOM period, this sets a flag to show the date picker dialog.
     *
     * @param periodType Type of period to load
     */
    fun changePeriod(periodType: PeriodType) {
        if (periodType == PeriodType.CUSTOM) {
            // Don't load reports - UI will show date picker dialog
            Timber.d("📅 User requested custom date range - showing picker")
            return
        }

        val newPeriod = when (periodType) {
            PeriodType.LAST_7_DAYS -> ReportPeriod.last7Days()
            PeriodType.LAST_30_DAYS -> ReportPeriod.last30Days()
            PeriodType.LAST_90_DAYS -> ReportPeriod.last90Days()
            PeriodType.COMPARISON -> {
                // Create comparison for last 7 days vs previous 7 days
                ReportPeriod.comparison(
                    currentStart = Instant.now().minusSeconds(7 * 24 * 60 * 60),
                    currentEnd = Instant.now()
                )
            }
            PeriodType.CUSTOM -> return  // Already handled above
        }

        loadReports(newPeriod)
    }

    /**
     * Load custom date range
     *
     * Called when user confirms date range in date picker dialog.
     *
     * @param startDate Start date of the custom range
     * @param endDate End date of the custom range
     */
    fun loadCustomDateRange(startDate: Instant, endDate: Instant) {
        Timber.d("📅 Loading custom date range: ${startDate} to ${endDate}")
        val customPeriod = ReportPeriod(
            startDate = startDate,
            endDate = endDate,
            type = PeriodType.CUSTOM,
            previousPeriodStart = null,
            previousPeriodEnd = null
        )
        loadReports(customPeriod)
    }

    /**
     * Refresh reports
     *
     * Called by pull-to-refresh or manually by user.
     * Reloads current period data and clears cache for fresh data.
     */
    fun refresh() {
        // Clear cache to force fresh fetch
        reportsCache.remove(currentPeriod.type)
        loadReports(currentPeriod)
    }

    // ══════════════════════════════════════════════════════════════════════
    // SOCKET.IO REAL-TIME UPDATES (TOAST POS PATTERN)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Listen to Socket.IO events for real-time report updates
     *
     * **Events that trigger refresh:**
     * - `payment_completed` - Payment processed (affects sales totals)
     *
     * **Smart Refresh Logic:**
     * - Only refreshes if viewing current period (includesCurrentTime() == true)
     * - Historical reports don't auto-refresh (data is immutable)
     * - Debounces rapid events (max 1 refresh per 5 seconds)
     *
     * **Why this matters:**
     * - Multi-terminal sync: Terminal A processes payment → Terminal B sees updated report
     * - Live dashboard: Reports update in real-time during open shifts
     * - Matches Toast POS behavior (instant KPI updates)
     */
    private fun collectSocketEvents() {
        viewModelScope.launch {
            socketManager.events.collect { event ->
                when (event) {
                    is SocketEvent.PaymentCompleted -> {
                        Timber.i("💰 Payment completed - refreshing reports")
                        refreshIfViewingCurrentPeriod()
                    }

                    else -> {
                        // Ignore other events
                    }
                }
            }
        }
    }

    /**
     * Refresh reports only if viewing current period
     *
     * Prevents unnecessary refreshes for historical reports (immutable data).
     * Only refreshes if period includes current time (e.g., "last 7 days" includes today).
     */
    private fun refreshIfViewingCurrentPeriod() {
        if (currentPeriod.includesCurrentTime()) {
            Timber.d("🔄 Refreshing reports (viewing current period)")
            refresh()
        } else {
            Timber.d("⏸️ Skipping refresh (viewing historical period)")
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// REPORTS STATE
// ══════════════════════════════════════════════════════════════════════

/**
 * Reports Screen State
 *
 * Sealed class hierarchy for type-safe state management.
 * Follows Material Design state management patterns.
 */
sealed class ReportsState {
    /**
     * Loading state (fetching reports)
     */
    data object Loading : ReportsState()

    /**
     * Success state with report data
     *
     * @param summary Sales summary (totals, averages)
     * @param paymentBreakdown Payment method distribution
     * @param shifts List of shifts in the period
     * @param comparison Optional comparison metrics (current vs previous period)
     * @param period Report period being viewed
     * @param lastUpdated Timestamp of last data fetch (for "Updated X ago" display)
     */
    data class Success(
        val summary: SalesSummary,
        val paymentBreakdown: PaymentMethodBreakdown,
        val shifts: List<Shift>,
        val comparison: ComparisonMetrics?,
        val period: ReportPeriod,
        val lastUpdated: Instant
    ) : ReportsState()

    /**
     * Error state with user-friendly message
     *
     * @param message Error message to display
     */
    data class Error(val message: String) : ReportsState()
}

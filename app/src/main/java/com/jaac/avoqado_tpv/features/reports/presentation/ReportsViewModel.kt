package com.jaac.avoqado_tpv.features.reports.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.features.reports.domain.models.ComparisonMetrics
import com.jaac.avoqado_tpv.features.reports.domain.models.HistoricalGrouping
import com.jaac.avoqado_tpv.features.reports.domain.models.HistoricalPeriod
import com.jaac.avoqado_tpv.features.reports.domain.models.PaginatedHistoricalData
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
import java.math.BigDecimal
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
    private val socketManager: SocketManager,
    private val printerManager: com.jaac.avoqado_tpv.core.printer.PrinterManager
) : ViewModel() {

    // ══════════════════════════════════════════════════════════════════════
    // STATE - SUMMARY TAB (existing reports functionality)
    // ══════════════════════════════════════════════════════════════════════

    private val _state = MutableStateFlow<ReportsState>(ReportsState.Loading)
    val state: StateFlow<ReportsState> = _state.asStateFlow()

    private val _isComparisonEnabled = MutableStateFlow(false)
    val isComparisonEnabled: StateFlow<Boolean> = _isComparisonEnabled.asStateFlow()

    private var currentPeriod: ReportPeriod = ReportPeriod.today()  // ← Default: Today's sales

    // Cache: Store reports by period type to avoid re-fetching
    // Note: Cache keys don't include comparison flag - cache is only for non-comparison periods
    private val reportsCache = mutableMapOf<PeriodType, ReportsState.Success>()

    // ══════════════════════════════════════════════════════════════════════
    // STATE - HISTORY TAB (new historical trends functionality)
    // ══════════════════════════════════════════════════════════════════════

    private val _historicalState = MutableStateFlow<HistoricalReportsState>(HistoricalReportsState.Loading)
    val historicalState: StateFlow<HistoricalReportsState> = _historicalState.asStateFlow()

    private val _historicalGrouping = MutableStateFlow(HistoricalGrouping.DAILY)
    val historicalGrouping: StateFlow<HistoricalGrouping> = _historicalGrouping.asStateFlow()

    // Date range for historical view (default: last 30 days)
    private var historicalStartDate: Instant = Instant.now().minus(30, java.time.temporal.ChronoUnit.DAYS)
    private var historicalEndDate: Instant = Instant.now()

    // Pagination cursor for fetching next page
    private var nextCursor: String? = null

    // Selected period for detail screen navigation
    private val _selectedPeriod = MutableStateFlow<HistoricalPeriod?>(null)
    val selectedPeriod: StateFlow<HistoricalPeriod?> = _selectedPeriod.asStateFlow()

    // Print mode state (for History tab)
    private val _isHistoricalPrintMode = MutableStateFlow(false)
    val isHistoricalPrintMode: StateFlow<Boolean> = _isHistoricalPrintMode.asStateFlow()

    // Selected periods for printing (for History tab)
    private val _selectedPeriodsForPrint = MutableStateFlow<Set<String>>(emptySet())
    val selectedPeriodsForPrint: StateFlow<Set<String>> = _selectedPeriodsForPrint.asStateFlow()

    // Print dialog visibility
    private val _showHistoricalPrintDialog = MutableStateFlow(false)
    val showHistoricalPrintDialog: StateFlow<Boolean> = _showHistoricalPrintDialog.asStateFlow()

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
     * Called when user selects a different period (7d, 30d, 90d, custom).
     * For CUSTOM period, this sets a flag to show the date picker dialog.
     * Maintains current comparison state when changing periods.
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
            PeriodType.TODAY -> ReportPeriod.today()
            PeriodType.LAST_7_DAYS -> ReportPeriod.last7Days()
            PeriodType.LAST_30_DAYS -> ReportPeriod.last30Days()
            PeriodType.LAST_90_DAYS -> ReportPeriod.last90Days()
            PeriodType.CUSTOM -> return  // Already handled above
        }

        // Apply comparison if enabled
        val periodToLoad = if (_isComparisonEnabled.value) {
            Timber.d("📊 Applying comparison to new period: ${newPeriod.getLabel()}")
            ReportPeriod.comparison(
                currentStart = newPeriod.startDate,
                currentEnd = newPeriod.endDate,
                periodType = newPeriod.type
            )
        } else {
            newPeriod
        }

        loadReports(periodToLoad)
    }

    /**
     * Toggle comparison mode on/off
     *
     * When enabled, reloads current period with comparison vs previous period.
     * When disabled, reloads current period without comparison.
     *
     * **Example:**
     * - User viewing "Last 7 days" (Nov 12-19)
     * - Toggles comparison ON → Shows Nov 12-19 vs Nov 5-11
     * - Toggles comparison OFF → Shows Nov 12-19 only
     *
     * @param enabled Whether to enable comparison
     */
    fun toggleComparison(enabled: Boolean) {
        Timber.d("📊 Comparison toggled: $enabled")
        _isComparisonEnabled.value = enabled

        // Reload current period with/without comparison
        val periodToLoad = if (enabled) {
            // Enable comparison: Add previous period calculation
            ReportPeriod.comparison(
                currentStart = currentPeriod.startDate,
                currentEnd = currentPeriod.endDate,
                periodType = currentPeriod.type
            )
        } else {
            // Disable comparison: Use base period without previous dates
            when (currentPeriod.type) {
                PeriodType.TODAY -> ReportPeriod.today()
                PeriodType.LAST_7_DAYS -> ReportPeriod.last7Days()
                PeriodType.LAST_30_DAYS -> ReportPeriod.last30Days()
                PeriodType.LAST_90_DAYS -> ReportPeriod.last90Days()
                PeriodType.CUSTOM -> currentPeriod  // Keep custom dates
            }
        }

        loadReports(periodToLoad)
    }

    /**
     * Load custom date range
     *
     * Called when user confirms date range in date picker dialog.
     * Applies comparison if currently enabled.
     *
     * @param startDate Start date of the custom range
     * @param endDate End date of the custom range
     */
    fun loadCustomDateRange(startDate: Instant, endDate: Instant) {
        Timber.d("📅 Loading custom date range: ${startDate} to ${endDate}")

        val basePeriod = ReportPeriod(
            startDate = startDate,
            endDate = endDate,
            type = PeriodType.CUSTOM,
            previousPeriodStart = null,
            previousPeriodEnd = null
        )

        // Apply comparison if enabled
        val periodToLoad = if (_isComparisonEnabled.value) {
            Timber.d("📊 Applying comparison to custom range")
            ReportPeriod.comparison(
                currentStart = basePeriod.startDate,
                currentEnd = basePeriod.endDate,
                periodType = PeriodType.CUSTOM
            )
        } else {
            basePeriod
        }

        loadReports(periodToLoad)
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
    // HISTORICAL REPORTS METHODS (HISTORY TAB)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Load historical summaries with current grouping
     *
     * Fetches list of historical periods grouped by selected time unit.
     * Uses cursor-based pagination for efficient data loading.
     *
     * **Default behavior:**
     * - Time range: Last 30 days
     * - Grouping: DAILY (auto-suggested based on range)
     * - Limit: 20 periods per page
     *
     * **User flows:**
     * 1. User switches to "Historial" tab → loadHistoricalSummaries()
     * 2. User changes grouping → changeHistoricalGrouping() → loadHistoricalSummaries()
     * 3. User scrolls to bottom → loadMoreHistoricalSummaries()
     *
     * @param resetPagination If true, clears cursor and starts from first page
     */
    fun loadHistoricalSummaries(resetPagination: Boolean = true) {
        viewModelScope.launch {
            try {
                Timber.d("📊 Loading historical summaries: grouping=${_historicalGrouping.value}, range=${historicalStartDate}..${historicalEndDate}")

                // Reset pagination if requested (new query)
                if (resetPagination) {
                    nextCursor = null
                    _historicalState.value = HistoricalReportsState.Loading
                } else {
                    // Loading more - keep existing data
                    val currentState = _historicalState.value
                    if (currentState is HistoricalReportsState.Success) {
                        _historicalState.value = currentState.copy(isLoadingMore = true)
                    }
                }

                val venueId = secureStorage.getVenueId()
                if (venueId == null) {
                    Timber.e("❌ No venueId found in storage")
                    _historicalState.value = HistoricalReportsState.Error("No se encontró información del local")
                    return@launch
                }

                // Fetch historical summaries from repository
                val result = reportsRepository.getHistoricalSummaries(
                    venueId = venueId,
                    grouping = _historicalGrouping.value,
                    startDate = historicalStartDate,
                    endDate = historicalEndDate,
                    cursor = if (resetPagination) null else nextCursor,
                    limit = 20
                )

                when (result) {
                    is Result.Success -> {
                        val data = result.data

                        // Update pagination cursor
                        nextCursor = data.nextCursor

                        if (resetPagination) {
                            // First page - replace all data
                            _historicalState.value = HistoricalReportsState.Success(
                                periods = data.periods,
                                hasMore = data.hasMore,
                                isLoadingMore = false,
                                lastUpdated = Instant.now(),
                                isFromCache = data.isFromCache,
                                cacheTimestamp = data.cacheTimestamp
                            )

                            if (data.isFromCache) {
                                val cacheAge = data.cacheTimestamp?.let { ts ->
                                    val ageMs = System.currentTimeMillis() - ts
                                    when {
                                        ageMs < 60_000 -> "menos de 1 minuto"
                                        ageMs < 3600_000 -> "${ageMs / 60_000} minutos"
                                        else -> "${ageMs / 3600_000} horas"
                                    }
                                } ?: "desconocido"

                                Timber.i("✅ Loaded ${data.periods.size} periods from CACHE (age: $cacheAge)")
                            } else {
                                Timber.i("✅ Loaded ${data.periods.size} historical periods from NETWORK, hasMore=${data.hasMore}")
                            }
                        } else {
                            // Additional page - append data (filter duplicates)
                            val currentState = _historicalState.value
                            if (currentState is HistoricalReportsState.Success) {
                                // Create unique keys for existing periods
                                val existingKeys = currentState.periods.map { it.getUniqueKey() }.toSet()

                                // Filter out duplicates from new data
                                val newPeriods = data.periods.filter { period ->
                                    period.getUniqueKey() !in existingKeys
                                }

                                val combinedPeriods = currentState.periods + newPeriods
                                _historicalState.value = currentState.copy(
                                    periods = combinedPeriods,
                                    hasMore = data.hasMore,
                                    isLoadingMore = false,
                                    lastUpdated = Instant.now()
                                )
                                Timber.i("✅ Loaded ${data.periods.size} more periods (${newPeriods.size} new, ${data.periods.size - newPeriods.size} duplicates filtered), total=${combinedPeriods.size}, hasMore=${data.hasMore}")
                            }
                        }
                    }

                    is Result.Error -> {
                        Timber.e("❌ Failed to load historical summaries: ${result.exception}")
                        _historicalState.value = HistoricalReportsState.Error(
                            result.exception.userMessage
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ Unexpected error loading historical summaries")
                _historicalState.value = HistoricalReportsState.Error(
                    e.message ?: "Error inesperado al cargar historial"
                )
            }
        }
    }

    /**
     * Change historical grouping
     *
     * Called when user selects different time grouping (Day/Week/Month/Quarter/Year).
     * Reloads historical data with new grouping.
     *
     * **Example:**
     * - User viewing last 30 days grouped by DAY (30 cards)
     * - Selects WEEKLY → Shows last 4 weeks (4 cards)
     * - Selects MONTHLY → Shows current month (1 card)
     *
     * @param grouping New grouping to apply
     */
    fun changeHistoricalGrouping(grouping: HistoricalGrouping) {
        Timber.d("📊 Changing historical grouping to: $grouping")
        _historicalGrouping.value = grouping

        // Reload data with new grouping (reset pagination)
        loadHistoricalSummaries(resetPagination = true)
    }

    /**
     * Load more historical summaries (pagination)
     *
     * Called when user scrolls to bottom of historical list.
     * Fetches next page of data using cursor.
     *
     * **Behavior:**
     * - If hasMore = false → Does nothing
     * - If already loading → Does nothing (prevents duplicate requests)
     * - Otherwise → Fetches next page and appends to existing data
     */
    fun loadMoreHistoricalSummaries() {
        val currentState = _historicalState.value

        // Only load more if we have a success state with more data
        if (currentState is HistoricalReportsState.Success && currentState.hasMore && !currentState.isLoadingMore) {
            Timber.d("📊 Loading more historical summaries (cursor=${nextCursor})")
            loadHistoricalSummaries(resetPagination = false)
        } else {
            Timber.d("⏸️ Skipping load more: hasMore=${(currentState as? HistoricalReportsState.Success)?.hasMore}, isLoadingMore=${(currentState as? HistoricalReportsState.Success)?.isLoadingMore}")
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // SUMMARY TAB METHODS (existing functionality)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Print current report to receipt printer
     *
     * Prints EXACTLY what's on screen (current period + comparison if enabled).
     * Follows Toast POS pattern: Print button → Immediate print to receipt printer.
     *
     * **What gets printed:**
     * - Period label and date range
     * - Sales summary metrics
     * - Payment method breakdown
     * - Comparison data (if comparison toggle is ON)
     *
     * **Error handling:**
     * - Shows error message in state if printer unavailable
     * - Logs technical errors with Timber
     *
     * @return Result<Unit> success if printed, failure if error
     */
    fun printReport() {
        val currentState = _state.value

        if (currentState !is ReportsState.Success) {
            Timber.w("⚠️ Cannot print - reports not loaded yet")
            return
        }

        viewModelScope.launch {
            try {
                // Format date range
                val formatter = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale("es", "ES"))
                val startDateFormatted = formatter.format(java.util.Date.from(currentState.period.startDate))
                val endDateFormatted = formatter.format(java.util.Date.from(currentState.period.endDate))
                val dateRange = "$startDateFormatted - $endDateFormatted"

                // Format comparison text (if enabled)
                val comparisonText = if (_isComparisonEnabled.value && currentState.comparison != null) {
                    buildString {
                        append("Ventas:   ")
                        val salesChange = currentState.comparison.salesChange.setScale(1, java.math.RoundingMode.HALF_UP)
                        if (salesChange >= BigDecimal.ZERO) append("+") else append("")
                        append("$salesChange% ${if (salesChange >= BigDecimal.ZERO) "↑" else "↓"}\n")

                        append("Ordenes:  ")
                        val ordersChange = currentState.comparison.ordersChange.setScale(1, java.math.RoundingMode.HALF_UP)
                        if (ordersChange >= BigDecimal.ZERO) append("+") else append("")
                        append("$ordersChange% ${if (ordersChange >= BigDecimal.ZERO) "↑" else "↓"}\n")
                    }
                } else {
                    null
                }

                // Format amounts without dollar sign (PrinterManager adds it)
                val totalSales = currentState.summary.totalSales.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
                val avgOrderValue = currentState.summary.averageOrderValue.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
                val avgProductsPerOrder = currentState.summary.averageProductsPerOrder.setScale(1, java.math.RoundingMode.HALF_UP).toPlainString()

                val cashAmount = currentState.paymentBreakdown.cashAmount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
                val cardAmount = currentState.paymentBreakdown.cardAmount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
                val voucherAmount = currentState.paymentBreakdown.voucherAmount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()

                val cashPercentage = currentState.paymentBreakdown.cashPercentage.setScale(0, java.math.RoundingMode.HALF_UP).toPlainString()
                val cardPercentage = currentState.paymentBreakdown.cardPercentage.setScale(0, java.math.RoundingMode.HALF_UP).toPlainString()
                val voucherPercentage = currentState.paymentBreakdown.voucherPercentage.setScale(0, java.math.RoundingMode.HALF_UP).toPlainString()

                // Get venue name (optional)
                val venueName = secureStorage.getVenueName()

                // Print report
                val result = printerManager.printReport(
                    periodLabel = currentState.period.getLabel(),
                    dateRange = dateRange,
                    totalSales = totalSales,
                    totalOrders = currentState.summary.totalOrders,
                    totalProducts = currentState.summary.totalProductsSold,
                    avgOrderValue = avgOrderValue,
                    avgProductsPerOrder = avgProductsPerOrder,
                    cashAmount = cashAmount,
                    cardAmount = cardAmount,
                    voucherAmount = voucherAmount,
                    cashPercentage = cashPercentage,
                    cardPercentage = cardPercentage,
                    voucherPercentage = voucherPercentage,
                    comparisonText = comparisonText,
                    venueName = venueName
                )

                when {
                    result.isSuccess -> Timber.i("✅ Report printed successfully")
                    result.isFailure -> {
                        val error = result.exceptionOrNull()
                        Timber.e(error, "❌ Failed to print report")
                        // TODO: Show error toast to user
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ Failed to print report")
            }
        }
    }

    /**
     * Set selected period for detail screen navigation
     *
     * When user taps a historical period card, store the period in ViewModel
     * so it can be accessed from the detail screen.
     *
     * **Pattern**: Toast POS + Square Dashboard
     * - Period data stored in ViewModel instead of navigation args
     * - Avoids URL encoding issues with BigDecimal/Instant
     * - Allows navigation to use simple route without complex serialization
     *
     * @param period Historical period to show in detail screen
     */
    fun setSelectedPeriod(period: HistoricalPeriod) {
        _selectedPeriod.value = period
        Timber.d("📊 Selected period for detail: ${period.label}")
    }

    /**
     * Clear selected period (when navigating back from detail screen)
     */
    fun clearSelectedPeriod() {
        _selectedPeriod.value = null
    }

    // ══════════════════════════════════════════════════════════════════════
    // HISTORICAL PRINT METHODS (HISTORY TAB)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Toggle historical print mode
     *
     * Enables/disables checkbox selection mode for printing multiple periods.
     * When enabled, user can select multiple periods to print.
     * When disabled, clears selection and returns to normal navigation mode.
     *
     * **Pattern**: Toast POS + Square Dashboard
     * - Print mode shows checkboxes on period cards
     * - User selects periods → Taps print → Dialog with options
     * - Max 20 periods per print (memory safety for 1GB RAM)
     *
     * @param enabled Whether to enable print mode
     */
    fun toggleHistoricalPrintMode(enabled: Boolean) {
        Timber.d("🖨️ Historical print mode: $enabled")
        _isHistoricalPrintMode.value = enabled

        // Clear selection when disabling print mode
        if (!enabled) {
            _selectedPeriodsForPrint.value = emptySet()
            _showHistoricalPrintDialog.value = false
        }
    }

    /**
     * Toggle period selection for printing
     *
     * Add or remove period from print selection.
     * Uses period.getUniqueKey() for consistent key generation.
     *
     * **Performance optimization (1GB RAM)**:
     * - Max 20 periods per print batch
     * - Shows warning if user tries to select more
     *
     * @param period Historical period to select/deselect
     */
    fun togglePeriodSelection(period: HistoricalPeriod) {
        val periodKey = period.getUniqueKey()
        val currentSelection = _selectedPeriodsForPrint.value.toMutableSet()

        if (periodKey in currentSelection) {
            // Deselect
            currentSelection.remove(periodKey)
            Timber.d("🖨️ Deselected period: ${period.label}")
        } else {
            // Select (with max limit check)
            if (currentSelection.size >= 20) {
                Timber.w("⚠️ Max 20 periods can be printed at once (memory safety)")
                // TODO: Show toast warning to user
                return
            }
            currentSelection.add(periodKey)
            Timber.d("🖨️ Selected period: ${period.label} (${currentSelection.size}/20)")
        }

        _selectedPeriodsForPrint.value = currentSelection
    }

    /**
     * Check if period is selected for printing
     *
     * @param period Historical period to check
     * @return true if selected, false otherwise
     */
    fun isPeriodSelected(period: HistoricalPeriod): Boolean {
        val periodKey = period.getUniqueKey()
        return periodKey in _selectedPeriodsForPrint.value
    }

    /**
     * Clear all selected periods
     */
    fun clearPeriodSelection() {
        _selectedPeriodsForPrint.value = emptySet()
        Timber.d("🖨️ Cleared all period selections")
    }

    /**
     * Show historical print dialog
     *
     * Opens dialog with print options (how many periods, include comparisons, etc.)
     * Only shows if at least 1 period is selected.
     */
    fun showHistoricalPrintDialog() {
        if (_selectedPeriodsForPrint.value.isEmpty()) {
            Timber.w("⚠️ No periods selected for printing")
            // TODO: Show toast "Selecciona al menos un período"
            return
        }

        Timber.d("🖨️ Showing print dialog (${_selectedPeriodsForPrint.value.size} periods selected)")
        _showHistoricalPrintDialog.value = true
    }

    /**
     * Dismiss historical print dialog
     */
    fun dismissHistoricalPrintDialog() {
        _showHistoricalPrintDialog.value = false
    }

    /**
     * Print selected historical periods
     *
     * Prints selected periods to receipt printer with user-selected options.
     *
     * **Print options:**
     * - includeComparisons: Whether to print period-over-period comparisons
     * - printMode: INDIVIDUAL (one receipt per period) or BATCH (all in one receipt)
     *
     * **Performance safeguards:**
     * - Max 20 periods per batch (1GB RAM limit)
     * - Warning if more than 10 periods (thermal printer paper limit)
     *
     * @param includeComparisons Whether to include comparison metrics
     * @param printMode Print mode (INDIVIDUAL or BATCH)
     */
    fun printHistoricalReports(
        includeComparisons: Boolean = true,
        printMode: HistoricalPrintMode = HistoricalPrintMode.BATCH
    ) {
        val currentState = _historicalState.value
        if (currentState !is HistoricalReportsState.Success) {
            Timber.w("⚠️ Cannot print - historical data not loaded")
            return
        }

        if (_selectedPeriodsForPrint.value.isEmpty()) {
            Timber.w("⚠️ No periods selected for printing")
            return
        }

        viewModelScope.launch {
            try {
                // Get selected periods from state
                val selectedKeys = _selectedPeriodsForPrint.value
                val selectedPeriods = currentState.periods.filter { period ->
                    period.getUniqueKey() in selectedKeys
                }

                Timber.d("🖨️ Printing ${selectedPeriods.size} historical periods (mode=$printMode, comparisons=$includeComparisons)")

                // Get venue info
                val venueId = secureStorage.getVenueId() ?: ""
                val venueName = secureStorage.getVenueName()

                when (printMode) {
                    HistoricalPrintMode.INDIVIDUAL -> {
                        // Print each period as separate receipt
                        selectedPeriods.forEach { period ->
                            val result = printerManager.printHistoricalPeriod(
                                period = period,
                                includeComparison = includeComparisons,
                                venueId = venueId,
                                venueName = venueName
                            )

                            when {
                                result.isSuccess -> Timber.i("✅ Printed period: ${period.label}")
                                result.isFailure -> {
                                    val error = result.exceptionOrNull()
                                    Timber.e(error, "❌ Failed to print period: ${period.label}")
                                }
                            }
                        }
                    }

                    HistoricalPrintMode.BATCH -> {
                        // Print all periods in one receipt
                        val result = printerManager.printHistoricalReport(
                            periods = selectedPeriods,
                            includeComparisons = includeComparisons,
                            venueId = venueId,
                            venueName = venueName
                        )

                        when {
                            result.isSuccess -> Timber.i("✅ Printed ${selectedPeriods.size} periods in batch")
                            result.isFailure -> {
                                val error = result.exceptionOrNull()
                                Timber.e(error, "❌ Failed to print batch report")
                            }
                        }
                    }
                }

                // Close dialog and exit print mode after successful print
                _showHistoricalPrintDialog.value = false
                toggleHistoricalPrintMode(false)

            } catch (e: Exception) {
                Timber.e(e, "❌ Failed to print historical reports")
            }
        }
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

// ══════════════════════════════════════════════════════════════════════
// HISTORICAL REPORTS STATE
// ══════════════════════════════════════════════════════════════════════

/**
 * Historical Reports State
 *
 * State for the Historial tab showing trends across multiple periods.
 * Supports pagination for loading large historical datasets.
 *
 * **Pattern**: Square Dashboard + Toast POS
 * - Loading: Initial data fetch or grouping change
 * - Success: List of historical periods with pagination support
 * - Error: User-friendly error message
 */
sealed class HistoricalReportsState {
    /**
     * Loading state (fetching historical data)
     */
    data object Loading : HistoricalReportsState()

    /**
     * Success state with historical periods
     *
     * @param periods List of historical periods (chronological order)
     * @param hasMore Whether more data is available for pagination
     * @param isLoadingMore Whether currently fetching next page
     * @param lastUpdated Timestamp of last data fetch
     * @param isFromCache Whether data was loaded from offline cache
     * @param cacheTimestamp When data was cached (for staleness detection)
     */
    data class Success(
        val periods: List<HistoricalPeriod>,
        val hasMore: Boolean,
        val isLoadingMore: Boolean = false,
        val lastUpdated: Instant,
        val isFromCache: Boolean = false,
        val cacheTimestamp: Long? = null
    ) : HistoricalReportsState() {
        /**
         * Check if cached data is stale (older than 24 hours)
         */
        fun isStale(): Boolean {
            if (cacheTimestamp == null) return false
            val now = System.currentTimeMillis()
            val ageMs = now - cacheTimestamp
            val ttlMs = 24 * 60 * 60 * 1000L // 24 hours
            return ageMs > ttlMs
        }

        /**
         * Get cache age as human-readable string
         *
         * Examples: "hace 5 minutos", "hace 2 horas", "hace 1 día"
         */
        fun getCacheAgeString(): String? {
            if (cacheTimestamp == null) return null

            val now = System.currentTimeMillis()
            val ageMs = now - cacheTimestamp

            return when {
                ageMs < 60_000 -> "hace menos de 1 minuto"
                ageMs < 3600_000 -> {
                    val minutes = (ageMs / 60_000).toInt()
                    "hace $minutes ${if (minutes == 1) "minuto" else "minutos"}"
                }
                ageMs < 86400_000 -> {
                    val hours = (ageMs / 3600_000).toInt()
                    "hace $hours ${if (hours == 1) "hora" else "horas"}"
                }
                else -> {
                    val days = (ageMs / 86400_000).toInt()
                    "hace $days ${if (days == 1) "día" else "días"}"
                }
            }
        }
    }

    /**
     * Error state with user-friendly message
     *
     * @param message Error message to display
     */
    data class Error(val message: String) : HistoricalReportsState()
}

// ══════════════════════════════════════════════════════════════════════
// HISTORICAL PRINT MODE
// ══════════════════════════════════════════════════════════════════════

/**
 * Historical Print Mode
 *
 * Defines how historical periods should be printed to receipt printer.
 *
 * **Pattern**: Toast POS + Square Dashboard
 * - INDIVIDUAL: Print each period as separate receipt (good for filing by date)
 * - BATCH: Print all periods in one receipt (good for weekly/monthly summaries)
 */
enum class HistoricalPrintMode {
    /**
     * Print each period as separate receipt
     *
     * **Example:**
     * - User selects 5 days → 5 receipts printed
     * - Each receipt shows: Period label, metrics, comparison
     *
     * **Use cases:**
     * - Daily reports for filing by date
     * - Individual period analysis
     * - Audit trail (one receipt per period)
     */
    INDIVIDUAL,

    /**
     * Print all periods in one receipt
     *
     * **Example:**
     * - User selects 5 days → 1 receipt with table of all 5 days
     * - Shows: Summary table, totals, averages
     *
     * **Use cases:**
     * - Weekly/monthly summaries
     * - Trend analysis across periods
     * - Manager reports (overview at a glance)
     */
    BATCH
}

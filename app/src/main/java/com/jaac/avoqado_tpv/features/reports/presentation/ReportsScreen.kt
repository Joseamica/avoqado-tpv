package com.jaac.avoqado_tpv.features.reports.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoLoadingOverlay
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoPullToRefresh
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveScaffold
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.permissions.di.PermissionsEntryPoint
import com.jaac.avoqado_tpv.features.plan.domain.model.PlanFeatureCatalog
import com.jaac.avoqado_tpv.features.plan.domain.model.PlanTier
import com.jaac.avoqado_tpv.features.plan.domain.model.allowsFeature
import com.jaac.avoqado_tpv.features.plan.presentation.PlanGate
import com.jaac.avoqado_tpv.features.plan.presentation.PlanUpsellDialog
import com.jaac.avoqado_tpv.features.reports.domain.models.ComparisonMetrics
import com.jaac.avoqado_tpv.features.reports.domain.models.HistoricalGrouping
import com.jaac.avoqado_tpv.features.reports.domain.models.HistoricalPeriod
import com.jaac.avoqado_tpv.features.reports.domain.models.PaymentMethodBreakdown
import com.jaac.avoqado_tpv.features.reports.domain.models.PeriodType
import com.jaac.avoqado_tpv.features.reports.domain.models.ReportPeriod
import com.jaac.avoqado_tpv.features.reports.domain.models.SalesSummary
import com.jaac.avoqado_tpv.features.reports.presentation.components.ComparisonBadge
import com.jaac.avoqado_tpv.features.reports.presentation.components.ComparisonToggle
import com.jaac.avoqado_tpv.features.reports.presentation.components.ComparisonTrend
import com.jaac.avoqado_tpv.features.reports.presentation.components.DateRangePickerDialog
import com.jaac.avoqado_tpv.features.reports.presentation.components.HistoricalGroupingSelector
import com.jaac.avoqado_tpv.features.reports.presentation.components.HistoricalPeriodCard
import com.jaac.avoqado_tpv.features.reports.presentation.components.MetricCard
import com.jaac.avoqado_tpv.features.reports.presentation.components.PaymentMethodsChart
import com.jaac.avoqado_tpv.features.reports.presentation.components.PeriodFilterChips
import com.jaac.avoqado_tpv.features.reports.presentation.components.StaffFilterChips
import com.jaac.avoqado_tpv.features.reports.presentation.components.StaffSalesBreakdown
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import dagger.hilt.android.EntryPointAccessors
import java.math.BigDecimal
import java.time.Instant

/**
 * Report Tab Selection
 *
 * Defines the two main tabs in the Reports screen.
 *
 * **Tabs:**
 * - SUMMARY: Deep analysis of ONE specific period (existing view)
 * - HISTORY: Trends across MULTIPLE periods (new view)
 *
 * **Pattern**: Square Dashboard + Toast POS
 */
enum class ReportTab {
    SUMMARY,   // Resumen: Current view with period filters, metrics, charts
    HISTORY    // Historial: Chronological list of periods with comparisons
}

/**
 * Reports Screen
 *
 * Main dashboard for sales reports and analytics.
 * Displays sales summary, payment breakdown, and shift history.
 *
 * **Features:**
 * - Tab navigation (Resumen | Historial)
 * - Period filters (7d, 30d, 90d, custom, comparison) - Summary tab only
 * - Key metric cards (sales, orders, products, averages) - Summary tab
 * - Payment methods breakdown chart - Summary tab
 * - Shift history list - Summary tab
 * - Historical trends list (day/week/month/quarter/year) - History tab
 * - Real-time updates via Socket.IO
 *
 * **Design Pattern:**
 * - Follows Toast/Square POS reporting UX
 * - Responsive layout for all TPV device sizes
 * - Dark theme with high contrast for readability
 *
 * @param onNavigateBack Callback to navigate back
 * @param onNavigateToProductPerformance Callback to navigate to product performance screen
 * @param viewModel ReportsViewModel instance
 */
@Composable
fun ReportsScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToProductPerformance: () -> Unit = {},
    onNavigateToPeriodDetail: (HistoricalPeriod) -> Unit = {},
    viewModel: ReportsViewModel = hiltViewModel()
) {
    // Summary tab state
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val isComparisonEnabled by viewModel.isComparisonEnabled.collectAsStateWithLifecycle()
    var showDatePicker by remember { mutableStateOf(false) }

    // Historical tab state
    val historicalState by viewModel.historicalState.collectAsStateWithLifecycle()
    val historicalGrouping by viewModel.historicalGrouping.collectAsStateWithLifecycle()
    val isHistoricalPrintMode by viewModel.isHistoricalPrintMode.collectAsStateWithLifecycle()
    val selectedPeriodsForPrint by viewModel.selectedPeriodsForPrint.collectAsStateWithLifecycle()
    val showHistoricalPrintDialog by viewModel.showHistoricalPrintDialog.collectAsStateWithLifecycle()

    // Staff filter state
    val selectedStaffIds by viewModel.selectedStaffIds.collectAsStateWithLifecycle()
    val filteredSummary by viewModel.filteredSummary.collectAsStateWithLifecycle()

    val venueZoneId = remember { viewModel.venueZoneId }

    // Print options dialog state
    var showPrintOptionsDialog by remember { mutableStateOf(false) }
    var printIncludeWaiterTips by remember { mutableStateOf(false) }
    var printIncludeRatings by remember { mutableStateOf(false) }
    var printIncludeStaffSales by remember { mutableStateOf(false) }
    val isReviewsEnabled = remember { viewModel.isReviewsEnabled }

    // ⭐ Plan-tier gating (fail open — null plan info ⇒ nothing is locked).
    // Mirrors the platform rule: TODAY summary stays free; historical /
    // advanced views (7d/30d/90d/custom periods, comparison, Historial tab)
    // require ADVANCED_REPORTS (Plan Pro). Gated taps show an upsell dialog;
    // the Historial tab shows an inline teaser. Printing today's report and
    // everything else in the order flow is untouched.
    val context = LocalContext.current
    val planManager = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            PermissionsEntryPoint::class.java,
        ).planManager()
    }
    val planInfo by planManager.planInfo.collectAsStateWithLifecycle()
    val advancedReportsLocked = !planInfo.allowsFeature(PlanFeatureCatalog.ADVANCED_REPORTS)
    var showAdvancedReportsUpsell by remember { mutableStateOf(false) }

    if (showAdvancedReportsUpsell) {
        PlanUpsellDialog(
            featureTitle = "Reportes avanzados",
            tier = PlanTier.PRO,
            onDismiss = { showAdvancedReportsUpsell = false },
        )
    }

    if (showPrintOptionsDialog) {
        AlertDialog(
            onDismissRequest = { showPrintOptionsDialog = false },
            title = { Text("Opciones de impresion") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Selecciona las secciones adicionales:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Propinas por mesero")
                        Switch(
                            checked = printIncludeWaiterTips,
                            onCheckedChange = { printIncludeWaiterTips = it },
                            colors = switchColors()
                        )
                    }
                    if (isReviewsEnabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Resenas")
                            Switch(
                                checked = printIncludeRatings,
                                onCheckedChange = { printIncludeRatings = it },
                                colors = switchColors()
                            )
                        }
                    }
                    // Staff sales toggle (only if staff data available)
                    val hasStaffSales = (state as? ReportsState.Success)?.summary?.staffSales?.isNotEmpty() == true
                    if (hasStaffSales) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Ventas por usuario")
                            Switch(
                                checked = printIncludeStaffSales,
                                onCheckedChange = { printIncludeStaffSales = it },
                                colors = switchColors()
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPrintOptionsDialog = false
                        viewModel.printReport(
                            includeWaiterTips = printIncludeWaiterTips,
                            includeRatings = printIncludeRatings,
                            includeStaffSales = printIncludeStaffSales
                        )
                    }
                ) {
                    Text("Imprimir")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showPrintOptionsDialog = false }
                ) {
                    Text("Cancelar")
                }
            }
        )
    }

    ReportsScreenContent(
        state = state,
        isComparisonEnabled = isComparisonEnabled,
        isRefreshing = isRefreshing,
        venueZoneId = venueZoneId,
        selectedStaffIds = selectedStaffIds,
        filteredSummary = filteredSummary,
        historicalState = historicalState,
        historicalGrouping = historicalGrouping,
        isHistoricalPrintMode = isHistoricalPrintMode,
        selectedPeriodsForPrint = selectedPeriodsForPrint,
        showHistoricalPrintDialog = showHistoricalPrintDialog,
        advancedReportsLocked = advancedReportsLocked,
        onNavigateBack = onNavigateBack,
        onRefresh = { viewModel.refresh() },
        onPeriodSelected = { periodType ->
            if (advancedReportsLocked && periodType != PeriodType.TODAY) {
                // ⭐ Historical periods are Plan Pro — teaser dialog, no change.
                showAdvancedReportsUpsell = true
            } else if (periodType == PeriodType.CUSTOM) {
                showDatePicker = true
            } else {
                viewModel.changePeriod(periodType)
            }
        },
        onComparisonToggle = { enabled ->
            if (advancedReportsLocked && enabled) {
                // ⭐ Comparison loads a previous (historical) period — Plan Pro.
                showAdvancedReportsUpsell = true
            } else {
                viewModel.toggleComparison(enabled)
            }
        },
        onToggleStaffSelection = { staffId -> viewModel.toggleStaffSelection(staffId) },
        onClearStaffFilter = { viewModel.clearStaffFilter() },
        onPrintReport = {
            showPrintOptionsDialog = true
        },
        onNavigateToProductPerformance = onNavigateToProductPerformance,
        onHistoricalGroupingSelected = { grouping ->
            viewModel.changeHistoricalGrouping(grouping)
        },
        onLoadMoreHistorical = {
            viewModel.loadMoreHistoricalSummaries()
        },
        onNavigateToPeriodDetail = onNavigateToPeriodDetail,
        onLoadHistoricalSummaries = {
            viewModel.loadHistoricalSummaries()
        },
        onToggleHistoricalPrintMode = { enabled ->
            viewModel.toggleHistoricalPrintMode(enabled)
        },
        onTogglePeriodSelection = { period ->
            viewModel.togglePeriodSelection(period)
        },
        onShowHistoricalPrintDialog = {
            viewModel.showHistoricalPrintDialog()
        },
        onDismissHistoricalPrintDialog = {
            viewModel.dismissHistoricalPrintDialog()
        },
        onPrintHistoricalReports = { includeComparisons, printMode ->
            viewModel.printHistoricalReports(includeComparisons, printMode)
        }
    )

    // Custom date range picker dialog
    if (showDatePicker) {
        DateRangePickerDialog(
            onDismiss = { showDatePicker = false },
            onConfirm = { startDate, endDate ->
                showDatePicker = false
                viewModel.loadCustomDateRange(startDate, endDate)
            }
        )
    }
}

/**
 * Reports Screen Content
 *
 * Stateless UI component for reports screen with tab navigation.
 * Separated for preview and testing purposes.
 *
 * @param state Current reports state (Summary tab)
 * @param isComparisonEnabled Whether comparison mode is active (Summary tab)
 * @param historicalState Historical reports state (History tab)
 * @param historicalGrouping Current grouping for historical data (History tab)
 * @param onNavigateBack Callback to navigate back
 * @param onPeriodSelected Callback when period filter is selected (Summary tab)
 * @param onComparisonToggle Callback when comparison toggle is switched (Summary tab)
 * @param onPrintReport Callback to print current report (Summary tab)
 * @param onNavigateToProductPerformance Callback to navigate to product performance
 * @param onHistoricalGroupingSelected Callback when grouping is selected (History tab)
 * @param onLoadMoreHistorical Callback to load more historical data (History tab pagination)
 * @param onNavigateToPeriodDetail Callback to navigate to period detail screen
 * @param onLoadHistoricalSummaries Callback to load initial historical data
 */
@Composable
private fun ReportsScreenContent(
    state: ReportsState,
    isComparisonEnabled: Boolean,
    isRefreshing: Boolean = false,
    venueZoneId: java.time.ZoneId = java.time.ZoneId.of("America/Mexico_City"),
    selectedStaffIds: Set<String> = emptySet(),
    filteredSummary: SalesSummary? = null,
    historicalState: HistoricalReportsState,
    historicalGrouping: HistoricalGrouping,
    isHistoricalPrintMode: Boolean,
    selectedPeriodsForPrint: Set<String>,
    showHistoricalPrintDialog: Boolean,
    // ⭐ ADVANCED_REPORTS plan gate (Plan Pro). Default false → fail open,
    // previews and old callers behave as today.
    advancedReportsLocked: Boolean = false,
    onNavigateBack: () -> Unit,
    onRefresh: () -> Unit = {},
    onPeriodSelected: (PeriodType) -> Unit,
    onComparisonToggle: (Boolean) -> Unit,
    onToggleStaffSelection: (String) -> Unit = {},
    onClearStaffFilter: () -> Unit = {},
    onPrintReport: () -> Unit,
    onNavigateToProductPerformance: () -> Unit,
    onHistoricalGroupingSelected: (HistoricalGrouping) -> Unit,
    onLoadMoreHistorical: () -> Unit,
    onNavigateToPeriodDetail: (HistoricalPeriod) -> Unit,
    onLoadHistoricalSummaries: () -> Unit,
    onToggleHistoricalPrintMode: (Boolean) -> Unit,
    onTogglePeriodSelection: (HistoricalPeriod) -> Unit,
    onShowHistoricalPrintDialog: () -> Unit,
    onDismissHistoricalPrintDialog: () -> Unit,
    onPrintHistoricalReports: (Boolean, HistoricalPrintMode) -> Unit,
    modifier: Modifier = Modifier
) {
    // Tab state management
    var selectedTab by remember { mutableStateOf(ReportTab.SUMMARY) }

    Scaffold(
        topBar = {
            AvoqadoTopBar(
                title = "Reportes",
                subtitle = when (state) {
                    is ReportsState.Success -> {
                        // Only show subtitle for SUMMARY tab
                        if (selectedTab == ReportTab.SUMMARY) {
                            if (isComparisonEnabled && state.period.previousPeriodStart != null) {
                                // Show both periods when comparing
                                val formatter = java.text.SimpleDateFormat("dd MMM", java.util.Locale("es", "ES")).apply { timeZone = java.util.TimeZone.getTimeZone(venueZoneId.id) }
                                val currentStart = formatter.format(java.util.Date.from(state.period.startDate))
                                val currentEnd = formatter.format(java.util.Date.from(state.period.endDate))
                                val previousStart = formatter.format(java.util.Date.from(state.period.previousPeriodStart))
                                val previousEnd = formatter.format(java.util.Date.from(state.period.previousPeriodEnd))
                                "$currentStart - $currentEnd vs $previousStart - $previousEnd"
                            } else {
                                state.period.getLabel(venueZoneId)
                            }
                        } else {
                            null  // No subtitle for HISTORY tab
                        }
                    }
                    else -> null
                },
                onNavigationClick = onNavigateBack,
                actions = {
                    when (selectedTab) {
                        ReportTab.SUMMARY -> {
                            // Print button for Summary tab
                            if (state is ReportsState.Success) {
                                IconButton(onClick = onPrintReport) {
                                    Icon(
                                        imageVector = Icons.Default.Print,
                                        contentDescription = "Imprimir reporte",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                        ReportTab.HISTORY -> {
                            // Print mode toggle for History tab. Hidden while
                            // the plan gate is locked: the tab content is an
                            // inert blur-preview, but this button lives in the
                            // top bar (outside the gated area) and historical
                            // data can still reach Success behind the preview.
                            if (!advancedReportsLocked && historicalState is HistoricalReportsState.Success) {
                                if (isHistoricalPrintMode) {
                                    // Show "Done" button when in print mode
                                    androidx.compose.material3.TextButton(
                                        onClick = { onToggleHistoricalPrintMode(false) }
                                    ) {
                                        Text("Cancelar")
                                    }
                                } else {
                                    // Show print icon when not in print mode
                                    IconButton(onClick = { onToggleHistoricalPrintMode(true) }) {
                                        Icon(
                                            imageVector = Icons.Default.Print,
                                            contentDescription = "Seleccionar períodos para imprimir",
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            // Tab Row (Square Dashboard style)
            TabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = selectedTab == ReportTab.SUMMARY,
                    onClick = { selectedTab = ReportTab.SUMMARY },
                    text = {
                        Text(
                            text = "Resumen",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                )

                Tab(
                    selected = selectedTab == ReportTab.HISTORY,
                    onClick = { selectedTab = ReportTab.HISTORY },
                    text = {
                        Text(
                            text = "Historial",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                )
            }

            // Content based on selected tab
            ResponsiveScaffold(
                scrollable = false  // LazyColumn handles its own scrolling
            ) {
                when (state) {
                    is ReportsState.Loading -> {
                        AvoqadoLoadingOverlay(message = "Cargando reportes...")
                    }

                    is ReportsState.Success -> {
                        when (selectedTab) {
                            ReportTab.SUMMARY -> {
                                AvoqadoPullToRefresh(
                                    isRefreshing = isRefreshing,
                                    onRefresh = onRefresh
                                ) {
                                    ReportsSuccessContent(
                                        summary = state.summary,
                                        paymentBreakdown = state.paymentBreakdown,
                                        shifts = state.shifts,
                                        comparison = state.comparison,
                                        period = state.period,
                                        isComparisonEnabled = isComparisonEnabled,
                                        selectedStaffIds = selectedStaffIds,
                                        filteredSummary = filteredSummary,
                                        onPeriodSelected = onPeriodSelected,
                                        onComparisonToggle = onComparisonToggle,
                                        onToggleStaffSelection = onToggleStaffSelection,
                                        onClearStaffFilter = onClearStaffFilter,
                                        onNavigateToProductPerformance = onNavigateToProductPerformance
                                    )
                                }
                            }

                            ReportTab.HISTORY -> {
                                // ⭐ Plan gate — blur-preview paywall (mirror
                                // of the dashboard's FeatureGate): the tab
                                // stays visible and the REAL Historial UI
                                // renders behind the upsell card as a dimmed,
                                // non-interactive preview. The entitlement
                                // decision (advancedReportsLocked) is
                                // unchanged; whatever the tab naturally
                                // renders (loading/cache/data) is the
                                // backdrop.
                                PlanGate(
                                    locked = advancedReportsLocked,
                                    featureTitle = "Historial de reportes",
                                    tier = PlanTier.PRO,
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    HistoricalReportsContent(
                                        state = historicalState,
                                        grouping = historicalGrouping,
                                        isPrintMode = isHistoricalPrintMode,
                                        selectedPeriodsForPrint = selectedPeriodsForPrint,
                                        showPrintDialog = showHistoricalPrintDialog,
                                        onGroupingSelected = onHistoricalGroupingSelected,
                                        onLoadMore = onLoadMoreHistorical,
                                        onPeriodClick = onNavigateToPeriodDetail,
                                        onLoadInitial = onLoadHistoricalSummaries,
                                        onTogglePeriodSelection = onTogglePeriodSelection,
                                        onShowPrintDialog = onShowHistoricalPrintDialog,
                                        onDismissPrintDialog = onDismissHistoricalPrintDialog,
                                        onPrintHistoricalReports = onPrintHistoricalReports
                                    )
                                }
                            }
                        }
                    }

                    is ReportsState.Error -> {
                        ReportsErrorContent(
                            message = state.message,
                            onRetry = onRefresh
                        )
                    }
                }
            }
        }
    }
}

/**
 * Reports Success Content
 *
 * Displays report data when successfully loaded.
 * Shows metrics, charts, and shift history.
 */
@Composable
private fun ReportsSuccessContent(
    summary: SalesSummary,
    paymentBreakdown: PaymentMethodBreakdown,
    shifts: List<Shift>,
    comparison: ComparisonMetrics?,
    period: ReportPeriod,
    isComparisonEnabled: Boolean,
    selectedStaffIds: Set<String> = emptySet(),
    filteredSummary: SalesSummary? = null,
    onPeriodSelected: (PeriodType) -> Unit,
    onComparisonToggle: (Boolean) -> Unit,
    onToggleStaffSelection: (String) -> Unit = {},
    onClearStaffFilter: () -> Unit = {},
    onNavigateToProductPerformance: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current

    // Use filtered summary when staff filter is active
    val displaySummary = filteredSummary ?: summary

    // Memoize expensive formatting operations
    val formattedSales = remember(displaySummary.totalSales) { displaySummary.formatTotalSales() }
    val formattedAvgOrder = remember(displaySummary.averageOrderValue) { displaySummary.formatAverageOrderValue() }
    val formattedProductsPerOrder = remember(displaySummary.averageProductsPerOrder) {
        displaySummary.averageProductsPerOrder.setScale(1, java.math.RoundingMode.HALF_UP).toPlainString()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(sizes.spacingSmall)
    ) {
        // Period filter chips
        item {
            PeriodFilterChips(
                selectedPeriod = period.type,
                onPeriodSelected = onPeriodSelected
            )
        }

        // Comparison toggle (tight with chips — same control group)
        item {
            ComparisonToggle(
                isEnabled = isComparisonEnabled,
                onToggle = onComparisonToggle
            )
        }

        // Staff filter chips (only when there are staff members to filter)
        if (summary.staffSales.size > 1) {
            item(key = "staff_filter") {
                StaffFilterChips(
                    staffSales = summary.staffSales,
                    selectedStaffIds = selectedStaffIds,
                    onToggleStaff = onToggleStaffSelection,
                    onClearFilter = onClearStaffFilter
                )
            }
        }

        // Section header — extra top padding for section separation
        item(key = "header_sales") {
            Text(
                text = "RESUMEN DE VENTAS",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    top = sizes.spacingSmall,
                    start = sizes.paddingScreen,
                    end = sizes.paddingScreen
                )
            )
        }

        // Metric cards row 1
        item(key = "metrics_row_1") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = sizes.paddingScreen),
                horizontalArrangement = Arrangement.spacedBy(sizes.spacingSmall)
            ) {
                MetricCard(
                    value = formattedSales,
                    label = "Ventas",
                    icon = Icons.Default.AttachMoney,
                    comparisonText = comparison?.salesChange?.let { formatComparisonText(it) },
                    comparisonTrend = comparison?.getSalesComparison()?.trend?.toComparisonTrend()
                        ?: ComparisonTrend.NEUTRAL,
                    modifier = Modifier.weight(1f)
                )

                MetricCard(
                    value = displaySummary.totalOrders.toString(),
                    label = "Órdenes",
                    icon = Icons.Default.Receipt,
                    comparisonText = comparison?.ordersChange?.let { formatComparisonText(it) },
                    comparisonTrend = comparison?.getOrdersComparison()?.trend?.toComparisonTrend()
                        ?: ComparisonTrend.NEUTRAL,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Metric cards row 2
        item(key = "metrics_row_2") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = sizes.paddingScreen),
                horizontalArrangement = Arrangement.spacedBy(sizes.spacingSmall)
            ) {
                MetricCard(
                    value = displaySummary.totalProductsSold.toString(),
                    label = "Productos",
                    icon = Icons.Default.ShoppingCart,
                    comparisonText = comparison?.productsChange?.let { formatComparisonText(it) },
                    comparisonTrend = comparison?.getProductsComparison()?.trend?.toComparisonTrend()
                        ?: ComparisonTrend.NEUTRAL,
                    modifier = Modifier.weight(1f)
                )

                MetricCard(
                    value = formattedAvgOrder,
                    label = "Ticket Promedio",
                    icon = Icons.Default.Assessment,
                    comparisonText = comparison?.avgOrderValueChange?.let { formatComparisonText(it) },
                    comparisonTrend = comparison?.getAvgOrderValueComparison()?.trend?.toComparisonTrend()
                        ?: ComparisonTrend.NEUTRAL,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Metric cards row 3
        item(key = "metrics_row_3") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = sizes.paddingScreen),
                horizontalArrangement = Arrangement.spacedBy(sizes.spacingSmall)
            ) {
                MetricCard(
                    value = formattedProductsPerOrder,
                    label = "Productos/Orden",
                    comparisonText = null,
                    modifier = Modifier.weight(1f)
                )

                // Empty card to maintain grid alignment
                Spacer(modifier = Modifier.weight(1f))
            }
        }

        // Payment methods section — extra top padding for section separation
        item {
            PaymentMethodsChart(
                breakdown = paymentBreakdown,
                modifier = Modifier.padding(
                    top = sizes.spacingSmall,
                    start = sizes.paddingScreen,
                    end = sizes.paddingScreen
                )
            )
        }

        // Staff sales breakdown (always visible — collapsible section)
        item(key = "staff_sales_breakdown") {
            StaffSalesBreakdown(
                staffSales = summary.staffSales,
                selectedStaffIds = selectedStaffIds,
                modifier = Modifier.padding(top = sizes.spacingSmall)
            )
        }

        // Shift history section — extra top padding for section separation
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = sizes.spacingSmall),
                verticalArrangement = Arrangement.spacedBy(sizes.spacingSmall)
            ) {
                // Section header
                Text(
                    text = "HISTORIAL DE TURNOS",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = sizes.paddingScreen)
                )

                if (shifts.isEmpty()) {
                    Text(
                        text = "No hay turnos en este período",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = sizes.paddingScreen)
                    )
                }
            }
        }

        // Shift cards
        items(
            items = shifts,
            key = { shift -> shift.id }
        ) { shift ->
            androidx.compose.material3.Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = sizes.paddingScreen),
                onClick = { /* TODO: Show shift detail dialog */ }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(sizes.paddingCard)
                ) {
                    Text(
                        text = shift.staffName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Ventas: ${shift.totalSales}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Órdenes: ${shift.totalOrders}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // Bottom spacer
        item {
            Spacer(modifier = Modifier.height(sizes.spacingMedium))
        }
    }
}

/**
 * Reports Error Content
 *
 * Displays error message with retry button when reports fail to load.
 */
@Composable
private fun ReportsErrorContent(
    message: String,
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 🩹 Center when short, scroll when a long error message pushes the button off-screen.
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val minContentHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minContentHeight)
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Error al cargar reportes",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(24.dp))

            androidx.compose.material3.OutlinedButton(
                onClick = onRetry
            ) {
                Text("Reintentar")
            }
        }
    }
}

/**
 * Historical Reports Content
 *
 * Shows historical trends across multiple periods with grouping options.
 * Implements cursor-based pagination for efficient data loading.
 *
 * **Pattern**: Square Dashboard + Toast POS
 * - Grouping selector at top (Día/Semana/Mes/Trim./Año)
 * - Chronological list with comparison badges
 * - Load more on scroll (pagination)
 * - Tap to drill down to detail screen
 *
 * @param state Historical reports state
 * @param grouping Current time grouping
 * @param onGroupingSelected Callback when grouping changes
 * @param onLoadMore Callback to load next page
 * @param onPeriodClick Callback when period card is tapped
 * @param onLoadInitial Callback to load initial data
 */
@Composable
private fun HistoricalReportsContent(
    state: HistoricalReportsState,
    grouping: HistoricalGrouping,
    isPrintMode: Boolean,
    selectedPeriodsForPrint: Set<String>,
    showPrintDialog: Boolean,
    onGroupingSelected: (HistoricalGrouping) -> Unit,
    onLoadMore: () -> Unit,
    onPeriodClick: (HistoricalPeriod) -> Unit,
    onLoadInitial: () -> Unit,
    onTogglePeriodSelection: (HistoricalPeriod) -> Unit,
    onShowPrintDialog: () -> Unit,
    onDismissPrintDialog: () -> Unit,
    onPrintHistoricalReports: (Boolean, HistoricalPrintMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current

    // Load initial data on first composition
    LaunchedEffect(Unit) {
        onLoadInitial()
    }

    androidx.compose.foundation.layout.Box(
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Grouping selector (Día/Semana/Mes/Trim./Año)
            HistoricalGroupingSelector(
                selectedGrouping = grouping,
                onGroupingSelected = onGroupingSelected,
                modifier = Modifier.padding(vertical = sizes.spacingMedium)
            )

            // Content based on state
            when (state) {
                is HistoricalReportsState.Loading -> {
                    AvoqadoLoadingOverlay(message = "Cargando historial...")
                }

                is HistoricalReportsState.Success -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Cache indicator banner (if data is from cache)
                        if (state.isFromCache) {
                            CacheStalenessIndicator(
                                isStale = state.isStale(),
                                cacheAge = state.getCacheAgeString() ?: "",
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        HistoricalPeriodsListContent(
                            periods = state.periods,
                            hasMore = state.hasMore,
                            isLoadingMore = state.isLoadingMore,
                            isPrintMode = isPrintMode,
                            selectedPeriodsForPrint = selectedPeriodsForPrint,
                            onLoadMore = onLoadMore,
                            onPeriodClick = onPeriodClick,
                            onTogglePeriodSelection = onTogglePeriodSelection
                        )
                    }
                }

                is HistoricalReportsState.Error -> {
                    HistoricalErrorContent(message = state.message)
                }
            }
        }

        // Floating action button to confirm print (only when periods are selected)
        if (isPrintMode && selectedPeriodsForPrint.isNotEmpty()) {
            androidx.compose.material3.FloatingActionButton(
                onClick = onShowPrintDialog,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(sizes.paddingScreen)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Print,
                        contentDescription = "Imprimir seleccionados"
                    )
                    Text("${selectedPeriodsForPrint.size}")
                }
            }
        }

        // Print dialog
        if (showPrintDialog) {
            HistoricalPrintDialog(
                selectedCount = selectedPeriodsForPrint.size,
                onDismiss = onDismissPrintDialog,
                onConfirm = { includeComparisons, printMode ->
                    onPrintHistoricalReports(includeComparisons, printMode)
                }
            )
        }
    }
}

/**
 * Historical Periods List Content
 *
 * Scrollable list of historical periods with pagination.
 * Detects when user scrolls to bottom and triggers load more.
 *
 * @param periods List of historical periods to display
 * @param hasMore Whether more data is available
 * @param isLoadingMore Whether currently loading next page
 * @param onLoadMore Callback to load next page
 * @param onPeriodClick Callback when period is tapped
 */
@Composable
private fun HistoricalPeriodsListContent(
    periods: List<HistoricalPeriod>,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    isPrintMode: Boolean,
    selectedPeriodsForPrint: Set<String>,
    onLoadMore: () -> Unit,
    onPeriodClick: (HistoricalPeriod) -> Unit,
    onTogglePeriodSelection: (HistoricalPeriod) -> Unit,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current

    if (periods.isEmpty()) {
        // Empty state
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(sizes.paddingScreen),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "No hay datos históricos",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(sizes.spacingSmall))
            Text(
                text = "Los datos aparecerán una vez que haya ventas registradas",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = sizes.paddingScreen,
                end = sizes.paddingScreen,
                top = sizes.spacingMedium,
                bottom = if (isPrintMode) 88.dp else sizes.spacingMedium // Extra padding for FAB
            ),
            verticalArrangement = Arrangement.spacedBy(sizes.spacingMedium)
        ) {
            // Period cards
            items(
                items = periods,
                key = { period -> period.getUniqueKey() }
            ) { period ->
                if (isPrintMode) {
                    // Show card with checkbox for selection
                    // Calculate isSelected directly from the Set for reactivity
                    val isSelected = period.getUniqueKey() in selectedPeriodsForPrint

                    HistoricalPeriodCardWithCheckbox(
                        period = period,
                        isSelected = isSelected,
                        onToggleSelection = { onTogglePeriodSelection(period) }
                    )
                } else {
                    // Show normal card for navigation
                    HistoricalPeriodCard(
                        period = period,
                        onClick = { onPeriodClick(period) }
                    )
                }
            }

            // Load more button / loading indicator
            if (hasMore) {
                item(key = "load_more") {
                    if (isLoadingMore) {
                        // Loading indicator
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = sizes.spacingMedium),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    } else {
                        // Load more button
                        androidx.compose.material3.TextButton(
                            onClick = onLoadMore,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = sizes.spacingMedium)
                        ) {
                            Text(
                                text = "Cargar más",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }

                    // Also trigger auto-load when button becomes visible
                    LaunchedEffect(Unit) {
                        if (!isLoadingMore) {
                            onLoadMore()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Historical Error Content
 *
 * Error state for historical reports.
 * Shows user-friendly error message.
 *
 * @param message Error message to display
 */
@Composable
private fun HistoricalErrorContent(
    message: String,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(sizes.paddingScreen),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Error al cargar historial",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(sizes.spacingSmall))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}

/**
 * Convert domain ComparisonMetrics.Trend to UI ComparisonTrend
 */
private fun ComparisonMetrics.Trend.toComparisonTrend(): ComparisonTrend {
    return when (this) {
        ComparisonMetrics.Trend.UP -> ComparisonTrend.UP
        ComparisonMetrics.Trend.DOWN -> ComparisonTrend.DOWN
        ComparisonMetrics.Trend.FLAT -> ComparisonTrend.NEUTRAL
    }
}

/**
 * Format comparison percentage with correct +/- prefix
 */
private fun formatComparisonText(value: java.math.BigDecimal): String {
    return if (value >= java.math.BigDecimal.ZERO) "+${value}%" else "${value}%"
}

/**
 * Cache Staleness Indicator Banner
 *
 * Shows informational banner when historical data is loaded from offline cache.
 * Different colors indicate freshness:
 * - Fresh cache (<24h): Subtle info color
 * - Stale cache (>24h): Warning color with stronger emphasis
 *
 * **Performance Optimization (1GB RAM devices)**:
 * - Lightweight composable (no heavy operations)
 * - No animations or complex layouts
 * - Minimal memory footprint
 *
 * **Pattern**: Toast POS + Square Dashboard
 * - Non-blocking banner (doesn't prevent interaction)
 * - Clear, concise messaging
 * - Auto-dismisses when fresh data loads
 *
 * @param isStale Whether cached data is older than 24 hours
 * @param cacheAge Human-readable cache age (e.g., "hace 2 horas")
 * @param modifier Modifier for layout customization
 */
@Composable
private fun CacheStalenessIndicator(
    isStale: Boolean,
    cacheAge: String,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isStale) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    }

    val textColor = if (isStale) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    val icon = if (isStale) {
        Icons.Default.Warning
    } else {
        Icons.Default.CloudOff
    }

    val message = if (isStale) {
        "⚠️ Datos desactualizados (última actualización: $cacheAge)"
    } else {
        "Mostrando datos guardados (última actualización: $cacheAge)"
    }

    Surface(
        modifier = modifier,
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(20.dp)
            )

            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = textColor,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Historical Period Card With Checkbox
 *
 * Period card with checkbox for print mode selection.
 * Shows checkmark when selected, empty circle when not selected.
 *
 * @param period Historical period to display
 * @param isSelected Whether period is selected
 * @param onToggleSelection Callback when selection changes
 */
@Composable
private fun HistoricalPeriodCardWithCheckbox(
    period: HistoricalPeriod,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.material3.Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onToggleSelection
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Checkbox
            androidx.compose.material3.Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelection() }
            )

            // Period info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = period.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = period.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            // Sales amount
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$${period.formatTotalSales()}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (period.salesChange != null) {
                    ComparisonBadge(
                        text = period.formatSalesChange(),
                        trend = if (period.salesChange >= java.math.BigDecimal.ZERO) {
                            ComparisonTrend.UP
                        } else {
                            ComparisonTrend.DOWN
                        }
                    )
                }
            }
        }
    }
}

/**
 * Historical Print Dialog
 *
 * Dialog with print options for historical reports.
 * Allows user to configure print settings before printing.
 *
 * **Print options:**
 * - Include comparisons: Whether to print period-over-period metrics
 * - Print mode: INDIVIDUAL (one receipt per period) or BATCH (all in one)
 *
 * @param selectedCount Number of periods selected for printing
 * @param onDismiss Callback when dialog is dismissed
 * @param onConfirm Callback when print is confirmed (includeComparisons, printMode)
 */
@Composable
private fun HistoricalPrintDialog(
    selectedCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (Boolean, HistoricalPrintMode) -> Unit,
    modifier: Modifier = Modifier
) {
    var includeComparisons by remember { mutableStateOf(true) }
    var printMode by remember { mutableStateOf(HistoricalPrintMode.BATCH) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Imprimir Períodos")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Selected count
                Text(
                    text = "$selectedCount ${if (selectedCount == 1) "período seleccionado" else "períodos seleccionados"}",
                    style = MaterialTheme.typography.bodyMedium
                )

                androidx.compose.material3.HorizontalDivider()

                // Include comparisons toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Incluir comparaciones")
                    androidx.compose.material3.Switch(
                        checked = includeComparisons,
                        onCheckedChange = { includeComparisons = it },
                        colors = switchColors()
                    )
                }

                // Print mode selector
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Modo de impresión",
                        style = MaterialTheme.typography.labelLarge
                    )

                    // Individual mode radio button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = printMode == HistoricalPrintMode.INDIVIDUAL,
                            onClick = { printMode = HistoricalPrintMode.INDIVIDUAL }
                        )
                        Column {
                            Text(
                                text = "Individual",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Un recibo por período",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }

                    // Batch mode radio button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = printMode == HistoricalPrintMode.BATCH,
                            onClick = { printMode = HistoricalPrintMode.BATCH }
                        )
                        Column {
                            Text(
                                text = "Resumen",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Todos los períodos en un recibo",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }

                // Warning if more than 10 periods
                if (selectedCount > 10 && printMode == HistoricalPrintMode.INDIVIDUAL) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Se imprimirán $selectedCount recibos. Considera usar modo Resumen.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    onConfirm(includeComparisons, printMode)
                    onDismiss()
                }
            ) {
                Text("Imprimir")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        },
        modifier = modifier
    )
}

/**
 * Switch colors that are visible in both light and dark mode.
 * Default dark mode thumb uses DarkOutline (0xFF383838) which is invisible.
 */
@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.primary,
    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
)

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@Preview(showBackground = true, widthDp = 600, heightDp = 1024)
@Composable
private fun ReportsScreenPreview() {
    AvoqadoTheme {
        val summary = SalesSummary(
            totalSales = BigDecimal("12450.00"),
            totalOrders = 145,
            totalProductsSold = 348,
            totalTips = BigDecimal("1245.00"),
            totalShifts = 12,
            averageOrderValue = BigDecimal("85.86"),
            averageProductsPerOrder = BigDecimal("2.4")
        )

        val breakdown = PaymentMethodBreakdown(
            cashAmount = BigDecimal("6847.50"),
            cardAmount = BigDecimal("5229.00"),
            voucherAmount = BigDecimal("373.50"),
            otherAmount = BigDecimal.ZERO,
            totalAmount = BigDecimal("12450.00"),
            cashPercentage = BigDecimal("55.0"),
            cardPercentage = BigDecimal("42.0"),
            voucherPercentage = BigDecimal("3.0"),
            otherPercentage = BigDecimal.ZERO
        )

        val state = ReportsState.Success(
            summary = summary,
            paymentBreakdown = breakdown,
            shifts = emptyList(),
            comparison = null,
            period = ReportPeriod.last7Days(),
            lastUpdated = Instant.now()
        )

        ReportsScreenContent(
            state = state,
            isComparisonEnabled = false,
            historicalState = HistoricalReportsState.Loading,
            historicalGrouping = HistoricalGrouping.DAILY,
            isHistoricalPrintMode = false,
            selectedPeriodsForPrint = emptySet(),
            showHistoricalPrintDialog = false,
            onNavigateBack = {},
            onPeriodSelected = {},
            onComparisonToggle = {},
            onPrintReport = {},
            onNavigateToProductPerformance = {},
            onHistoricalGroupingSelected = {},
            onLoadMoreHistorical = {},
            onNavigateToPeriodDetail = {},
            onLoadHistoricalSummaries = {},
            onToggleHistoricalPrintMode = {},
            onTogglePeriodSelection = {},
            onShowHistoricalPrintDialog = {},
            onDismissHistoricalPrintDialog = {},
            onPrintHistoricalReports = { _, _ -> }
        )
    }
}

@Preview(showBackground = true, widthDp = 600, heightDp = 1024)
@Composable
private fun ReportsScreenLoadingPreview() {
    AvoqadoTheme {
        ReportsScreenContent(
            state = ReportsState.Loading,
            isComparisonEnabled = false,
            historicalState = HistoricalReportsState.Loading,
            historicalGrouping = HistoricalGrouping.DAILY,
            isHistoricalPrintMode = false,
            selectedPeriodsForPrint = emptySet(),
            showHistoricalPrintDialog = false,
            onNavigateBack = {},
            onPeriodSelected = {},
            onComparisonToggle = {},
            onPrintReport = {},
            onNavigateToProductPerformance = {},
            onHistoricalGroupingSelected = {},
            onLoadMoreHistorical = {},
            onNavigateToPeriodDetail = {},
            onLoadHistoricalSummaries = {},
            onToggleHistoricalPrintMode = {},
            onTogglePeriodSelection = {},
            onShowHistoricalPrintDialog = {},
            onDismissHistoricalPrintDialog = {},
            onPrintHistoricalReports = { _, _ -> }
        )
    }
}

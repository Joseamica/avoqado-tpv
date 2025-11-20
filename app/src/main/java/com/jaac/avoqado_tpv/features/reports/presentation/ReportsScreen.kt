package com.jaac.avoqado_tpv.features.reports.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoLoadingOverlay
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveScaffold
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.reports.domain.models.ComparisonMetrics
import com.jaac.avoqado_tpv.features.reports.domain.models.PaymentMethodBreakdown
import com.jaac.avoqado_tpv.features.reports.domain.models.PeriodType
import com.jaac.avoqado_tpv.features.reports.domain.models.ReportPeriod
import com.jaac.avoqado_tpv.features.reports.domain.models.SalesSummary
import com.jaac.avoqado_tpv.features.reports.presentation.components.ComparisonTrend
import com.jaac.avoqado_tpv.features.reports.presentation.components.DateRangePickerDialog
import com.jaac.avoqado_tpv.features.reports.presentation.components.MetricCard
import com.jaac.avoqado_tpv.features.reports.presentation.components.PaymentMethodsChart
import com.jaac.avoqado_tpv.features.reports.presentation.components.PeriodFilterChips
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import java.math.BigDecimal
import java.time.Instant

/**
 * Reports Screen
 *
 * Main dashboard for sales reports and analytics.
 * Displays sales summary, payment breakdown, and shift history.
 *
 * **Features:**
 * - Period filters (7d, 30d, 90d, custom, comparison)
 * - Key metric cards (sales, orders, products, averages)
 * - Payment methods breakdown chart
 * - Shift history list
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
    viewModel: ReportsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDatePicker by remember { mutableStateOf(false) }

    ReportsScreenContent(
        state = state,
        onNavigateBack = onNavigateBack,
        onPeriodSelected = { periodType ->
            if (periodType == PeriodType.CUSTOM) {
                showDatePicker = true
            } else {
                viewModel.changePeriod(periodType)
            }
        },
        onNavigateToProductPerformance = onNavigateToProductPerformance
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
 * Stateless UI component for reports screen.
 * Separated for preview and testing purposes.
 *
 * @param state Current reports state
 * @param onNavigateBack Callback to navigate back
 * @param onPeriodSelected Callback when period filter is selected
 * @param onNavigateToProductPerformance Callback to navigate to product performance
 */
@Composable
private fun ReportsScreenContent(
    state: ReportsState,
    onNavigateBack: () -> Unit,
    onPeriodSelected: (PeriodType) -> Unit,
    onNavigateToProductPerformance: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            AvoqadoTopBar(
                title = "Reportes",
                subtitle = when (state) {
                    is ReportsState.Success -> state.period.getLabel()
                    else -> null
                },
                onNavigationClick = onNavigateBack
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        ResponsiveScaffold(
            modifier = Modifier.padding(paddingValues),
            scrollable = false  // LazyColumn handles its own scrolling
        ) {
            when (state) {
                is ReportsState.Loading -> {
                    AvoqadoLoadingOverlay(message = "Cargando reportes...")
                }

                is ReportsState.Success -> {
                    ReportsSuccessContent(
                        summary = state.summary,
                        paymentBreakdown = state.paymentBreakdown,
                        shifts = state.shifts,
                        comparison = state.comparison,
                        period = state.period,
                        onPeriodSelected = onPeriodSelected,
                        onNavigateToProductPerformance = onNavigateToProductPerformance
                    )
                }

                is ReportsState.Error -> {
                    ReportsErrorContent(
                        message = state.message
                    )
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
    onPeriodSelected: (PeriodType) -> Unit,
    onNavigateToProductPerformance: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current

    // Memoize expensive formatting operations
    val formattedSales = remember(summary.totalSales) { summary.formatTotalSales() }
    val formattedAvgOrder = remember(summary.averageOrderValue) { summary.formatAverageOrderValue() }
    val formattedProductsPerOrder = remember(summary.averageProductsPerOrder) {
        summary.averageProductsPerOrder.setScale(1, java.math.RoundingMode.HALF_UP).toPlainString()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(sizes.spacingLarge)
    ) {
        // Period filter chips
        item {
            PeriodFilterChips(
                selectedPeriod = period.type,
                onPeriodSelected = onPeriodSelected
            )
        }

        // Section header
        item(key = "header_sales") {
            Text(
                text = "RESUMEN DE VENTAS",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = sizes.paddingScreen)
            )
        }

        // Metric cards row 1 (lazy composition)
        item(key = "metrics_row_1") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = sizes.paddingScreen),
                horizontalArrangement = Arrangement.spacedBy(sizes.spacingMedium)
            ) {
                MetricCard(
                    value = formattedSales,
                    label = "Ventas",
                    icon = Icons.Default.AttachMoney,
                    comparisonText = comparison?.salesChange?.let { "+${it}%" },
                    comparisonTrend = comparison?.getSalesComparison()?.trend?.toComparisonTrend()
                        ?: ComparisonTrend.NEUTRAL,
                    modifier = Modifier.weight(1f)
                )

                MetricCard(
                    value = summary.totalOrders.toString(),
                    label = "Órdenes",
                    icon = Icons.Default.Receipt,
                    comparisonText = comparison?.ordersChange?.let { "+${it}%" },
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
                horizontalArrangement = Arrangement.spacedBy(sizes.spacingMedium)
            ) {
                MetricCard(
                    value = summary.totalProductsSold.toString(),
                    label = "Productos",
                    icon = Icons.Default.ShoppingCart,
                    comparisonText = comparison?.productsChange?.let { "+${it}%" },
                    comparisonTrend = comparison?.getProductsComparison()?.trend?.toComparisonTrend()
                        ?: ComparisonTrend.NEUTRAL,
                    modifier = Modifier.weight(1f)
                )

                MetricCard(
                    value = formattedAvgOrder,
                    label = "Ticket Promedio",
                    icon = Icons.Default.Assessment,
                    comparisonText = comparison?.avgOrderValueChange?.let { "+${it}%" },
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
                horizontalArrangement = Arrangement.spacedBy(sizes.spacingMedium)
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

        // Payment methods section
        item {
            PaymentMethodsChart(
                breakdown = paymentBreakdown,
                modifier = Modifier.padding(horizontal = sizes.paddingScreen)
            )
        }

        // Shift history section
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(sizes.spacingMedium)
            ) {
                // Section header
                Text(
                    text = "HISTORIAL DE TURNOS",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
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
            key = { shift -> shift.id }  // ⚡ Performance: Stable key prevents full recomposition
        ) { shift ->
            // TODO: Create ShiftCard component or display shift info inline
            // For now, just show a placeholder card
            androidx.compose.material3.Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = sizes.paddingScreen),
                onClick = { /* TODO: Show shift detail dialog */ }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = shift.staffName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
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
            Spacer(modifier = Modifier.height(sizes.spacingLarge))
        }
    }
}

/**
 * Reports Error Content
 *
 * Displays error message when reports fail to load.
 */
@Composable
private fun ReportsErrorContent(
    message: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center
    ) {
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
            onNavigateBack = {},
            onPeriodSelected = {},
            onNavigateToProductPerformance = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 600, heightDp = 1024)
@Composable
private fun ReportsScreenLoadingPreview() {
    AvoqadoTheme {
        ReportsScreenContent(
            state = ReportsState.Loading,
            onNavigateBack = {},
            onPeriodSelected = {},
            onNavigateToProductPerformance = {}
        )
    }
}

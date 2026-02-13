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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveScaffold
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.reports.domain.models.HistoricalGrouping
import com.jaac.avoqado_tpv.features.reports.domain.models.HistoricalPeriod
import com.jaac.avoqado_tpv.features.reports.presentation.components.ComparisonBadge
import com.jaac.avoqado_tpv.features.reports.presentation.components.MetricCard
import java.math.BigDecimal
import java.time.Instant

/**
 * Historical Period Detail Screen
 *
 * Detailed view of a single historical period showing complete metrics,
 * comparisons, and payment method breakdown.
 *
 * **World-Class Pattern (Toast POS + Square Dashboard)**:
 * - Period header with label and date range
 * - Key metrics cards (sales, orders, products, average)
 * - Comparison badges vs previous period
 * - Visual hierarchy (most important metrics at top)
 *
 * **Design**:
 * - Clean card-based layout
 * - Responsive spacing for all TPV devices
 * - Dark theme with high contrast
 * - Touch-friendly card sizes
 *
 * @param period Historical period to display details for
 * @param onNavigateBack Callback to navigate back to historical list
 */
@Composable
fun HistoricalPeriodDetailScreen(
    period: HistoricalPeriod,
    onNavigateBack: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            AvoqadoTopBar(
                title = period.label,
                subtitle = period.subtitle.takeIf { it.isNotBlank() },
                onNavigationClick = onNavigateBack
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        ResponsiveScaffold(
            modifier = Modifier.padding(paddingValues),
            scrollable = false  // LazyColumn handles scrolling
        ) {
            HistoricalPeriodDetailContent(period = period)
        }
    }
}

/**
 * Historical Period Detail Content
 *
 * Scrollable content showing all period metrics and comparisons.
 *
 * @param period Historical period data
 */
@Composable
private fun HistoricalPeriodDetailContent(
    period: HistoricalPeriod,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(sizes.spacingLarge)
    ) {
        // Section header: Métricas Principales
        item(key = "header_metrics") {
            Text(
                text = "MÉTRICAS PRINCIPALES",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = sizes.paddingScreen)
            )
        }

        // Metric cards row 1: Sales and Orders
        item(key = "metrics_row_1") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = sizes.paddingScreen),
                horizontalArrangement = Arrangement.spacedBy(sizes.spacingMedium)
            ) {
                MetricCard(
                    value = period.formatTotalSales(),
                    label = "Ventas",
                    icon = Icons.Default.AttachMoney,
                    comparisonText = period.salesChange?.let {
                        val formatted = period.formatSalesChange()
                        if (formatted.isNotBlank()) formatted else null
                    },
                    comparisonTrend = if (period.isSalesIncreasing()) {
                        com.jaac.avoqado_tpv.features.reports.presentation.components.ComparisonTrend.UP
                    } else {
                        com.jaac.avoqado_tpv.features.reports.presentation.components.ComparisonTrend.DOWN
                    },
                    modifier = Modifier.weight(1f)
                )

                MetricCard(
                    value = period.totalOrders.toString(),
                    label = "Órdenes",
                    icon = Icons.Default.Receipt,
                    comparisonText = period.ordersChange?.let {
                        val sign = if (it >= BigDecimal.ZERO) "+" else ""
                        "${sign}${it.setScale(1, java.math.RoundingMode.HALF_UP)}%"
                    },
                    comparisonTrend = if (period.ordersChange != null && period.ordersChange >= BigDecimal.ZERO) {
                        com.jaac.avoqado_tpv.features.reports.presentation.components.ComparisonTrend.UP
                    } else {
                        com.jaac.avoqado_tpv.features.reports.presentation.components.ComparisonTrend.DOWN
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Metric cards row 2: Products and Average Order Value
        item(key = "metrics_row_2") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = sizes.paddingScreen),
                horizontalArrangement = Arrangement.spacedBy(sizes.spacingMedium)
            ) {
                MetricCard(
                    value = period.totalProducts.toString(),
                    label = "Productos",
                    icon = Icons.Default.ShoppingCart,
                    modifier = Modifier.weight(1f)
                )

                MetricCard(
                    value = period.formatAverageOrderValue(),
                    label = "Ticket Promedio",
                    icon = Icons.Default.Assessment,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Comparison section (if comparison data exists)
        if (period.salesChange != null || period.ordersChange != null) {
            item(key = "header_comparison") {
                Spacer(modifier = Modifier.height(sizes.spacingMedium))
                Text(
                    text = "COMPARACIÓN VS PERÍODO ANTERIOR",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = sizes.paddingScreen)
                )
            }

            item(key = "comparison_card") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = sizes.paddingScreen)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Sales change
                        if (period.salesChange != null) {
                            ComparisonRow(
                                label = "Ventas",
                                change = period.formatSalesChange(),
                                isPositive = period.isSalesIncreasing()
                            )
                        }

                        // Orders change
                        if (period.ordersChange != null) {
                            val ordersChangeFormatted = period.ordersChange.let {
                                val sign = if (it >= BigDecimal.ZERO) "+" else ""
                                "${sign}${it.setScale(1, java.math.RoundingMode.HALF_UP)}%"
                            }
                            ComparisonRow(
                                label = "Órdenes",
                                change = ordersChangeFormatted,
                                isPositive = period.ordersChange >= BigDecimal.ZERO
                            )
                        }
                    }
                }
            }
        }

        // Period info section
        item(key = "header_info") {
            Spacer(modifier = Modifier.height(sizes.spacingMedium))
            Text(
                text = "INFORMACIÓN DEL PERÍODO",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = sizes.paddingScreen)
            )
        }

        item(key = "info_card") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = sizes.paddingScreen)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    InfoRow(
                        label = "Inicio",
                        value = formatDate(period.periodStart)
                    )
                    InfoRow(
                        label = "Fin",
                        value = formatDate(period.periodEnd)
                    )
                    InfoRow(
                        label = "Agrupación",
                        value = period.grouping.getDisplayName()
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
 * Comparison Row
 *
 * Shows a single comparison metric with badge.
 *
 * @param label Metric label (e.g., "Ventas")
 * @param change Formatted change string (e.g., "+12.5%")
 * @param isPositive Whether change is positive
 */
@Composable
private fun ComparisonRow(
    label: String,
    change: String,
    isPositive: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        ComparisonBadge(
            change = change,
            isPositive = isPositive
        )
    }
}

/**
 * Info Row
 *
 * Shows a single information row (label: value).
 *
 * @param label Information label
 * @param value Information value
 */
@Composable
private fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Format date for display
 *
 * @param instant Date to format
 * @return Formatted date string (e.g., "15 Enero 2025")
 */
private fun formatDate(instant: Instant): String {
    val formatter = java.text.SimpleDateFormat("dd MMMM yyyy", java.util.Locale("es", "ES")).apply { timeZone = java.util.TimeZone.getTimeZone("America/Mexico_City") }
    return formatter.format(java.util.Date.from(instant))
}

/**
 * Get display name for grouping
 */
private fun HistoricalGrouping.getDisplayName(): String {
    return when (this) {
        HistoricalGrouping.DAILY -> "Diario"
        HistoricalGrouping.WEEKLY -> "Semanal"
        HistoricalGrouping.MONTHLY -> "Mensual"
        HistoricalGrouping.QUARTERLY -> "Trimestral"
        HistoricalGrouping.YEARLY -> "Anual"
    }
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@Preview(showBackground = true, widthDp = 600, heightDp = 1024)
@Composable
private fun HistoricalPeriodDetailScreenPreview() {
    AvoqadoTheme {
        HistoricalPeriodDetailScreen(
            period = HistoricalPeriod(
                periodStart = Instant.now(),
                periodEnd = Instant.now(),
                grouping = HistoricalGrouping.DAILY,
                label = "15 Enero 2025",
                subtitle = "Martes",
                totalSales = BigDecimal("12450.50"),
                totalOrders = 145,
                totalProducts = 348,
                averageOrderValue = BigDecimal("85.86"),
                salesChange = BigDecimal("12.5"),
                ordersChange = BigDecimal("9.8")
            ),
            onNavigateBack = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 600, heightDp = 1024)
@Composable
private fun HistoricalPeriodDetailScreenNegativePreview() {
    AvoqadoTheme {
        HistoricalPeriodDetailScreen(
            period = HistoricalPeriod(
                periodStart = Instant.now(),
                periodEnd = Instant.now(),
                grouping = HistoricalGrouping.WEEKLY,
                label = "Semana 2, Enero 2025",
                subtitle = "",
                totalSales = BigDecimal("45230.00"),
                totalOrders = 532,
                totalProducts = 1248,
                averageOrderValue = BigDecimal("85.02"),
                salesChange = BigDecimal("-5.3"),
                ordersChange = BigDecimal("-3.1")
            ),
            onNavigateBack = {}
        )
    }
}

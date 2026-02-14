package com.jaac.avoqado_tpv.features.reports.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.reports.domain.models.HistoricalGrouping
import com.jaac.avoqado_tpv.features.reports.domain.models.HistoricalPeriod
import java.math.BigDecimal
import java.time.Instant

/**
 * Historical Period Card Component
 *
 * Displays a single historical period with sales metrics and comparison badge.
 *
 * **World-Class Pattern (Toast POS + Square Dashboard)**:
 * - Clear visual hierarchy (label → metrics → comparison)
 * - Touch-friendly height (min 80dp)
 * - Comparison badge prominently displayed
 * - Icon badges for order/product count
 * - Tap gesture for drill-down
 *
 * **Design**:
 * ```
 * ┌─────────────────────────────────────┐
 * │ 15 Enero 2025         $12,450      │
 * │ Martes                +12.5% ↑     │
 * │ 145 órdenes · 348 productos    →   │
 * └─────────────────────────────────────┘
 * ```
 *
 * @param period Historical period data
 * @param onClick Callback when card is tapped (drill-down)
 * @param modifier Modifier for customization
 */
@Composable
fun HistoricalPeriodCard(
    period: HistoricalPeriod,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp), // Touch-friendly
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // LEFT: Period label and subtitle
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = period.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (period.subtitle.isNotBlank()) {
                    Text(
                        text = period.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            // RIGHT: Sales + comparison badge + metrics
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = period.formatTotalSales(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                // Comparison badge
                if (period.salesChange != null) {
                    ComparisonBadge(
                        text = period.formatSalesChange(),
                        trend = if (period.isSalesIncreasing()) ComparisonTrend.UP else ComparisonTrend.DOWN
                    )
                }

                // Order/product count badges
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricBadge(
                        icon = Icons.Default.Receipt,
                        value = "${period.totalOrders}"
                    )
                    MetricBadge(
                        icon = Icons.Default.ShoppingCart,
                        value = "${period.totalProducts}"
                    )
                }
            }
        }
    }
}

/**
 * Small metric badge with icon and value
 */
@Composable
private fun MetricBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@Preview(showBackground = true, widthDp = 600)
@Composable
private fun HistoricalPeriodCardPreview() {
    AvoqadoTheme {
        HistoricalPeriodCard(
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
            onClick = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 600)
@Composable
private fun HistoricalPeriodCardNegativePreview() {
    AvoqadoTheme {
        HistoricalPeriodCard(
            period = HistoricalPeriod(
                periodStart = Instant.now(),
                periodEnd = Instant.now(),
                grouping = HistoricalGrouping.DAILY,
                label = "14 Enero 2025",
                subtitle = "Lunes",
                totalSales = BigDecimal("11230.00"),
                totalOrders = 132,
                totalProducts = 312,
                averageOrderValue = BigDecimal("85.08"),
                salesChange = BigDecimal("-3.2"),
                ordersChange = BigDecimal("-5.1")
            ),
            onClick = {}
        )
    }
}

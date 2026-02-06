package com.jaac.avoqado_tpv.features.reports.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors

/**
 * Metric Card Component
 *
 * Displays a single metric with:
 * - Large number value
 * - Icon (optional)
 * - Label
 * - Comparison badge (optional) showing % change
 *
 * Used in ReportsScreen to show key performance indicators.
 *
 * **Design Pattern:**
 * - Large emphasized value (48sp)
 * - Icon for quick visual identification
 * - Comparison badge shows trend (green = up, red = down)
 *
 * @param value Main metric value (e.g., "$1,250.50" or "145")
 * @param label Metric label (e.g., "Ventas", "Órdenes")
 * @param icon Optional icon for visual identification
 * @param comparisonText Optional comparison text (e.g., "+12.5%", "-3.2%")
 * @param comparisonTrend Trend indicator for comparison color
 * @param modifier Modifier for customization
 */
@Composable
fun MetricCard(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    comparisonText: String? = null,
    comparisonTrend: ComparisonTrend = ComparisonTrend.NEUTRAL
) {
    Card(
        modifier = modifier.height(100.dp),  // Fixed height for consistency
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Icon + Label row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }

                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }

            // Main value
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )

            // Comparison badge (if provided)
            if (comparisonText != null) {
                ComparisonBadge(
                    text = comparisonText,
                    trend = comparisonTrend,
                    modifier = Modifier.align(Alignment.Start)
                )
            } else {
                Spacer(modifier = Modifier.height(18.dp))  // Maintain height consistency
            }
        }
    }
}

/**
 * Comparison Badge Component
 *
 * Shows percentage change with color-coded trend indicator.
 *
 * @param text Comparison text (e.g., "+12.5%", "-3.2%")
 * @param trend Trend indicator (UP = green, DOWN = red, NEUTRAL = gray)
 */
@Composable
fun ComparisonBadge(
    text: String,
    trend: ComparisonTrend,
    modifier: Modifier = Modifier
) {
    val backgroundColor = when (trend) {
        ComparisonTrend.UP -> MaterialTheme.avoqadoColors.statusSuccess.copy(alpha = 0.15f)
        ComparisonTrend.DOWN -> MaterialTheme.avoqadoColors.statusError.copy(alpha = 0.15f)
        ComparisonTrend.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
    }

    val textColor = when (trend) {
        ComparisonTrend.UP -> MaterialTheme.avoqadoColors.statusSuccess
        ComparisonTrend.DOWN -> MaterialTheme.avoqadoColors.statusError
        ComparisonTrend.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = textColor
            )
        }
    }
}

/**
 * Trend indicator for comparison badges
 */
enum class ComparisonTrend {
    UP,      // Positive growth (green)
    DOWN,    // Decline (red)
    NEUTRAL  // Flat or no change (gray)
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@Preview(showBackground = true, widthDp = 200, heightDp = 200)
@Composable
private fun MetricCardPreview() {
    AvoqadoTheme {
        MetricCard(
            value = "$12,450",
            label = "Ventas",
            icon = Icons.Default.AttachMoney,
            comparisonText = "+12.5%",
            comparisonTrend = ComparisonTrend.UP
        )
    }
}

@Preview(showBackground = true, widthDp = 200, heightDp = 200)
@Composable
private fun MetricCardDeclinePreview() {
    AvoqadoTheme {
        MetricCard(
            value = "85",
            label = "Órdenes",
            comparisonText = "-5.2%",
            comparisonTrend = ComparisonTrend.DOWN
        )
    }
}

@Preview(showBackground = true, widthDp = 200, heightDp = 200)
@Composable
private fun MetricCardNoComparisonPreview() {
    AvoqadoTheme {
        MetricCard(
            value = "348",
            label = "Productos",
            icon = Icons.Default.AttachMoney
        )
    }
}

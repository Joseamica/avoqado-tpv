package com.jaac.avoqado_tpv.features.reports.presentation.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors

/**
 * Trend indicator for comparison badges
 */
enum class ComparisonTrend {
    UP,      // Positive growth (green)
    DOWN,    // Decline (red)
    NEUTRAL  // Flat or no change (gray)
}

/**
 * Comparison Badge Component
 *
 * Displays period-over-period change with color-coded background.
 *
 * **Pattern (Stripe Terminal + Toast POS)**:
 * - Green background for positive changes (+12%)
 * - Red background for negative changes (-3%)
 * - Gray background for neutral/flat
 * - Compact design for list items and metric cards
 *
 * @param text Formatted change string (e.g., "+12.5%", "-3.2%")
 * @param trend Trend indicator (UP = green, DOWN = red, NEUTRAL = gray)
 * @param modifier Modifier for customization
 */
@Composable
fun ComparisonBadge(
    text: String,
    trend: ComparisonTrend,
    modifier: Modifier = Modifier
) {
    // Don't show badge if text is empty
    if (text.isBlank()) return

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

    Surface(
        modifier = modifier,
        color = backgroundColor,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = textColor
        )
    }
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@Preview(showBackground = true)
@Composable
private fun ComparisonBadgePositivePreview() {
    AvoqadoTheme {
        ComparisonBadge(
            text = "+12.5%",
            trend = ComparisonTrend.UP
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ComparisonBadgeNegativePreview() {
    AvoqadoTheme {
        ComparisonBadge(
            text = "-3.2%",
            trend = ComparisonTrend.DOWN
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ComparisonBadgeNeutralPreview() {
    AvoqadoTheme {
        ComparisonBadge(
            text = "0%",
            trend = ComparisonTrend.NEUTRAL
        )
    }
}

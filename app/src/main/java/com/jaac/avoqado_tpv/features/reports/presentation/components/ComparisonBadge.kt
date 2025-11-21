package com.jaac.avoqado_tpv.features.reports.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme

/**
 * Comparison Badge Component
 *
 * Displays period-over-period change with color-coded background and arrow indicator.
 *
 * **World-Class Pattern (Stripe Terminal + Toast POS)**:
 * - Green background for positive changes (+12% ↑)
 * - Red background for negative changes (-3% ↓)
 * - Clear visual hierarchy with background color
 * - Compact design for list items
 *
 * **Design**:
 * ```
 * ┌─────────┐
 * │ +12% ↑  │ ← Green background
 * └─────────┘
 *
 * ┌─────────┐
 * │ -3.2% ↓ │ ← Red background
 * └─────────┘
 * ```
 *
 * @param change Formatted change string (e.g., "+12.5% ↑", "-3.2% ↓")
 * @param isPositive Whether the change is positive (green) or negative (red)
 * @param modifier Modifier for customization
 */
@Composable
fun ComparisonBadge(
    change: String,
    isPositive: Boolean,
    modifier: Modifier = Modifier
) {
    // Don't show badge if change is empty
    if (change.isBlank()) return

    val backgroundColor = if (isPositive) {
        Color(0xFF10B981).copy(alpha = 0.1f)  // Green 500 with 10% opacity
    } else {
        Color(0xFFEF4444).copy(alpha = 0.1f)  // Red 500 with 10% opacity
    }

    val textColor = if (isPositive) {
        Color(0xFF10B981)  // Green 500
    } else {
        Color(0xFFEF4444)  // Red 500
    }

    Surface(
        modifier = modifier,
        color = backgroundColor,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = change,
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
            change = "+12.5% ↑",
            isPositive = true
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ComparisonBadgeNegativePreview() {
    AvoqadoTheme {
        ComparisonBadge(
            change = "-3.2% ↓",
            isPositive = false
        )
    }
}

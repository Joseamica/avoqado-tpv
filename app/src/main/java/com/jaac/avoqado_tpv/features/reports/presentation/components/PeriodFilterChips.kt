package com.jaac.avoqado_tpv.features.reports.presentation.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.reports.domain.models.PeriodType

/**
 * Period Filter Chips Component
 *
 * Quick filter buttons for selecting report time periods.
 * Displays: Hoy (default), 7d, 30d, 90d, Personalizado
 *
 * **Design:**
 * - Horizontal scrollable row (fits on small TPV screens)
 * - Selected chip is highlighted
 * - "Hoy" is default (managers check today's sales first)
 * - Icon for Personalizado (calendar)
 *
 * @param selectedPeriod Currently selected period type
 * @param onPeriodSelected Callback when period is selected
 * @param modifier Modifier for customization
 */
@Composable
fun PeriodFilterChips(
    selectedPeriod: PeriodType,
    onPeriodSelected: (PeriodType) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Today (default)
        PeriodChip(
            label = "Hoy",
            selected = selectedPeriod == PeriodType.TODAY,
            onClick = { onPeriodSelected(PeriodType.TODAY) }
        )

        // Last 7 days
        PeriodChip(
            label = "7d",
            selected = selectedPeriod == PeriodType.LAST_7_DAYS,
            onClick = { onPeriodSelected(PeriodType.LAST_7_DAYS) }
        )

        // Last 30 days
        PeriodChip(
            label = "30d",
            selected = selectedPeriod == PeriodType.LAST_30_DAYS,
            onClick = { onPeriodSelected(PeriodType.LAST_30_DAYS) }
        )

        // Last 90 days
        PeriodChip(
            label = "90d",
            selected = selectedPeriod == PeriodType.LAST_90_DAYS,
            onClick = { onPeriodSelected(PeriodType.LAST_90_DAYS) }
        )

        // Custom range
        PeriodChip(
            label = "Personalizado",
            icon = Icons.Default.CalendarMonth,
            selected = selectedPeriod == PeriodType.CUSTOM,
            onClick = { onPeriodSelected(PeriodType.CUSTOM) }
        )
    }
}

/**
 * Individual period filter chip
 *
 * @param label Chip label text
 * @param selected Whether chip is selected
 * @param onClick Callback when clicked
 * @param icon Optional icon (for Custom and Compare)
 */
@Composable
private fun PeriodChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        leadingIcon = if (icon != null) {
            {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
        } else null,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,  // ← Fix: Icon visible when selected
            containerColor = MaterialTheme.colorScheme.surface,
            labelColor = MaterialTheme.colorScheme.onSurface,
            iconColor = MaterialTheme.colorScheme.onSurface  // ← Icon color when not selected
        ),
        modifier = modifier
    )
}

/**
 * Comparison Toggle Component
 *
 * Toggle switch to enable/disable period-over-period comparison.
 * When enabled, shows current period vs previous period of same length.
 *
 * **UX Pattern (Square/Stripe):**
 * - Clean toggle with clear label
 * - Icon for visual recognition
 * - Placed below period chips for logical grouping
 *
 * @param isEnabled Whether comparison mode is active
 * @param onToggle Callback when toggle is switched
 * @param modifier Modifier for customization
 */
@Composable
fun ComparisonToggle(
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.BarChart,
                contentDescription = null,
                tint = if (isEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                }
            )
            Text(
                text = "Comparar con período anterior",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isEnabled) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isEnabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                }
            )
        }

        Switch(
            checked = isEnabled,
            onCheckedChange = onToggle
        )
    }
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@Preview(showBackground = true, widthDp = 600, heightDp = 100)
@Composable
private fun PeriodFilterChipsPreview() {
    AvoqadoTheme {
        PeriodFilterChips(
            selectedPeriod = PeriodType.LAST_7_DAYS,
            onPeriodSelected = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 600, heightDp = 100)
@Composable
private fun PeriodFilterChipsCustomSelectedPreview() {
    AvoqadoTheme {
        PeriodFilterChips(
            selectedPeriod = PeriodType.CUSTOM,
            onPeriodSelected = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 600, heightDp = 80)
@Composable
private fun ComparisonToggleEnabledPreview() {
    AvoqadoTheme {
        ComparisonToggle(
            isEnabled = true,
            onToggle = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 600, heightDp = 80)
@Composable
private fun ComparisonToggleDisabledPreview() {
    AvoqadoTheme {
        ComparisonToggle(
            isEnabled = false,
            onToggle = {}
        )
    }
}

package com.jaac.avoqado_tpv.features.reports.presentation.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.reports.domain.models.HistoricalGrouping

/**
 * Historical Grouping Selector Component
 *
 * Quick filter chips for selecting time grouping (Day/Week/Month/Quarter/Year).
 *
 * **Pattern**: Square Dashboard + Toast POS
 * - Horizontal scrollable row (fits PAX screens)
 * - Selected chip highlighted
 * - Compact labels (Día, Semana, Mes, Trim., Año)
 *
 * @param selectedGrouping Currently selected grouping
 * @param onGroupingSelected Callback when grouping is selected
 * @param modifier Modifier for customization
 */
@Composable
fun HistoricalGroupingSelector(
    selectedGrouping: HistoricalGrouping,
    onGroupingSelected: (HistoricalGrouping) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        GroupingChip(
            label = "Día",
            grouping = HistoricalGrouping.DAILY,
            selected = selectedGrouping == HistoricalGrouping.DAILY,
            onClick = onGroupingSelected
        )

        GroupingChip(
            label = "Semana",
            grouping = HistoricalGrouping.WEEKLY,
            selected = selectedGrouping == HistoricalGrouping.WEEKLY,
            onClick = onGroupingSelected
        )

        GroupingChip(
            label = "Mes",
            grouping = HistoricalGrouping.MONTHLY,
            selected = selectedGrouping == HistoricalGrouping.MONTHLY,
            onClick = onGroupingSelected
        )

        GroupingChip(
            label = "Trim.",
            grouping = HistoricalGrouping.QUARTERLY,
            selected = selectedGrouping == HistoricalGrouping.QUARTERLY,
            onClick = onGroupingSelected
        )

        GroupingChip(
            label = "Año",
            grouping = HistoricalGrouping.YEARLY,
            selected = selectedGrouping == HistoricalGrouping.YEARLY,
            onClick = onGroupingSelected
        )
    }
}

@Composable
private fun GroupingChip(
    label: String,
    grouping: HistoricalGrouping,
    selected: Boolean,
    onClick: (HistoricalGrouping) -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = { onClick(grouping) },
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            containerColor = MaterialTheme.colorScheme.surface,
            labelColor = MaterialTheme.colorScheme.onSurface
        ),
        modifier = modifier
    )
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@Preview(showBackground = true, widthDp = 600)
@Composable
private fun HistoricalGroupingSelectorDailyPreview() {
    AvoqadoTheme {
        HistoricalGroupingSelector(
            selectedGrouping = HistoricalGrouping.DAILY,
            onGroupingSelected = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 600)
@Composable
private fun HistoricalGroupingSelectorMonthlyPreview() {
    AvoqadoTheme {
        HistoricalGroupingSelector(
            selectedGrouping = HistoricalGrouping.MONTHLY,
            onGroupingSelected = {}
        )
    }
}

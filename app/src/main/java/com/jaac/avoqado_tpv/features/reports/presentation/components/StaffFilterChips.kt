package com.jaac.avoqado_tpv.features.reports.presentation.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.reports.domain.models.StaffSales
import java.math.BigDecimal

/**
 * Staff filter chips for per-staff sales breakdown.
 *
 * Multi-select horizontal scrollable chips — one per staff member.
 * Shows "Limpiar" chip when filter is active.
 *
 * @param staffSales List of staff with sales data
 * @param selectedStaffIds Currently selected staff IDs
 * @param onToggleStaff Toggle staff selection
 * @param onClearFilter Clear all staff filters
 */
@Composable
fun StaffFilterChips(
    staffSales: List<StaffSales>,
    selectedStaffIds: Set<String>,
    onToggleStaff: (String) -> Unit,
    onClearFilter: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = sizes.paddingScreen),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Clear filter chip (only shown when filter is active)
        if (selectedStaffIds.isNotEmpty()) {
            InputChip(
                selected = false,
                onClick = onClearFilter,
                label = { Text("Limpiar", style = MaterialTheme.typography.bodyMedium) },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Limpiar filtro",
                        modifier = Modifier.size(16.dp)
                    )
                },
                colors = InputChipDefaults.inputChipColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    labelColor = MaterialTheme.colorScheme.onErrorContainer,
                    trailingIconColor = MaterialTheme.colorScheme.onErrorContainer
                )
            )
        }

        // One chip per staff member
        for (staff in staffSales) {
            val isSelected = staff.staffId in selectedStaffIds
            // Show first name only to save space
            val displayName = staff.name.split(" ").firstOrNull() ?: staff.name

            FilterChip(
                selected = isSelected,
                onClick = { onToggleStaff(staff.staffId) },
                label = {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    containerColor = MaterialTheme.colorScheme.surface,
                    labelColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

private val previewStaffSales = listOf(
    StaffSales("1", "Juan Perez", BigDecimal("5000.00"), 42, BigDecimal("350.00")),
    StaffSales("2", "Maria Lopez", BigDecimal("3200.00"), 28, BigDecimal("180.00")),
    StaffSales("3", "Carlos Garcia", BigDecimal("1800.00"), 15, BigDecimal("90.00"))
)

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun StaffFilterChipsNoneSelectedPreview() {
    AvoqadoTheme {
        StaffFilterChips(
            staffSales = previewStaffSales,
            selectedStaffIds = emptySet(),
            onToggleStaff = {},
            onClearFilter = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun StaffFilterChipsWithSelectionPreview() {
    AvoqadoTheme {
        StaffFilterChips(
            staffSales = previewStaffSales,
            selectedStaffIds = setOf("1", "3"),
            onToggleStaff = {},
            onClearFilter = {}
        )
    }
}

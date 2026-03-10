package com.jaac.avoqado_tpv.features.reports.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.reports.domain.models.StaffSales
import java.math.BigDecimal

/**
 * Collapsible staff sales breakdown card.
 *
 * Always visible — matches PaymentMethodsChart card style.
 * Tap header to expand/collapse. Shows per-staff sales or empty state.
 */
@Composable
fun StaffSalesBreakdown(
    staffSales: List<StaffSales>,
    selectedStaffIds: Set<String>,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    val displayList = remember(staffSales, selectedStaffIds) {
        if (selectedStaffIds.isEmpty()) staffSales
        else staffSales.filter { it.staffId in selectedStaffIds }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Collapsible header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ventas por Usuario",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (staffSales.isNotEmpty()) {
                        Text(
                            text = "${staffSales.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Colapsar" else "Expandir",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Expandable content
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    if (displayList.isEmpty()) {
                        Text(
                            text = "Sin ventas en este período",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        displayList.forEachIndexed { index, staff ->
                            StaffSalesRow(staff = staff)
                            if (index < displayList.lastIndex) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StaffSalesRow(
    staff: StaffSales,
    modifier: Modifier = Modifier
) {
    val formattedSales = remember(staff.totalSales) {
        val formatter = java.text.DecimalFormat("#,##0.00", java.text.DecimalFormatSymbols(java.util.Locale.US))
        "$${formatter.format(staff.totalSales)}"
    }
    val formattedTips = remember(staff.totalTips) {
        val formatter = java.text.DecimalFormat("#,##0.00", java.text.DecimalFormatSymbols(java.util.Locale.US))
        "$${formatter.format(staff.totalTips)}"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: name + orders
        Column {
            Text(
                text = staff.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${staff.totalOrders} ${if (staff.totalOrders == 1) "orden" else "órdenes"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Right: sales + tips
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formattedSales,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (staff.totalTips > BigDecimal.ZERO) {
                Text(
                    text = "Propinas: $formattedTips",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

private val previewStaffSales = listOf(
    StaffSales("1", "Juan Perez", BigDecimal("5000.00"), 42, BigDecimal("350.00")),
    StaffSales("2", "Maria Lopez", BigDecimal("3200.00"), 28, BigDecimal("180.00")),
    StaffSales("3", "Carlos Garcia", BigDecimal("1800.00"), 15, BigDecimal.ZERO)
)

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun StaffSalesBreakdownPreview() {
    AvoqadoTheme {
        StaffSalesBreakdown(
            staffSales = previewStaffSales,
            selectedStaffIds = emptySet()
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun StaffSalesBreakdownEmptyPreview() {
    AvoqadoTheme {
        StaffSalesBreakdown(
            staffSales = emptyList(),
            selectedStaffIds = emptySet()
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun StaffSalesBreakdownFilteredPreview() {
    AvoqadoTheme {
        StaffSalesBreakdown(
            staffSales = previewStaffSales,
            selectedStaffIds = setOf("1")
        )
    }
}

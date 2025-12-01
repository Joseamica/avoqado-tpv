package com.jaac.avoqado_tpv.features.payment.presentation.split.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme

/**
 * Selectable Item Row
 *
 * Reusable component for split payment screens.
 * Used in both SplitByProductScreen and SplitByPersonScreen.
 *
 * **States:**
 * - Default: Outline border, empty circle icon
 * - Selected: Primary border, filled checkmark icon
 * - Paid/Disabled: Low opacity, checkmark icon, non-clickable
 *
 * Layout:
 * ```
 * ┌─────────────────────────────────────────────┐
 * │  ○/●/✓  Label                    $150.00   │
 * └─────────────────────────────────────────────┘
 * ```
 *
 * @param label Main text (product name or "Persona N")
 * @param trailingText Right-aligned text (price or "Pagado")
 * @param isSelected Whether item is currently selected
 * @param isPaid Whether item has been paid (shows as disabled)
 * @param onTap Click handler (disabled when isPaid)
 * @param modifier Optional modifier
 */
@Composable
fun SelectableItemRow(
    label: String,
    trailingText: String,
    isSelected: Boolean,
    isPaid: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Determine border color based on state
    val borderColor = when {
        isPaid -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        isSelected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }

    // Determine icon based on state
    val icon = when {
        isPaid -> Icons.Filled.CheckCircle
        isSelected -> Icons.Filled.CheckCircle
        else -> Icons.Outlined.Circle
    }

    // Determine icon tint
    val iconTint = when {
        isPaid -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        isSelected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (isPaid) 0.5f else 1f)
            .clickable(enabled = !isPaid) { onTap() },
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = if (isSelected && !isPaid) 2.dp else 1.dp,
            color = borderColor
        ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selection icon
            Icon(
                imageVector = icon,
                contentDescription = when {
                    isPaid -> "Pagado"
                    isSelected -> "Seleccionado"
                    else -> "No seleccionado"
                },
                tint = iconTint
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Label
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected && !isPaid) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isPaid) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )

            // Trailing text (price or "Pagado")
            Text(
                text = trailingText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isPaid) FontWeight.Normal else FontWeight.SemiBold,
                color = if (isPaid) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}

// ============================================================================
// Previews
// ============================================================================

@Preview(showBackground = true)
@Composable
private fun SelectableItemRowDefaultPreview() {
    AvoqadoTheme {
        SelectableItemRow(
            label = "Hamburguesa Doble",
            trailingText = "$150.00",
            isSelected = false,
            isPaid = false,
            onTap = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SelectableItemRowSelectedPreview() {
    AvoqadoTheme {
        SelectableItemRow(
            label = "Papas Fritas",
            trailingText = "$70.00",
            isSelected = true,
            isPaid = false,
            onTap = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SelectableItemRowPaidPreview() {
    AvoqadoTheme {
        SelectableItemRow(
            label = "Refresco",
            trailingText = "Pagado",
            isSelected = false,
            isPaid = true,
            onTap = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1C1C)
@Composable
private fun SelectableItemRowDarkPreview() {
    AvoqadoTheme {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
        ) {
            SelectableItemRow(
                label = "Hamburguesa Doble",
                trailingText = "$150.00",
                isSelected = false,
                isPaid = false,
                onTap = {}
            )
            SelectableItemRow(
                label = "Papas Fritas",
                trailingText = "$70.00",
                isSelected = true,
                isPaid = false,
                onTap = {}
            )
            SelectableItemRow(
                label = "Refresco",
                trailingText = "Pagado",
                isSelected = false,
                isPaid = true,
                onTap = {}
            )
        }
    }
}

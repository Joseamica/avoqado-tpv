package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.Spacing

/**
 * Avoqado Filter Chip
 *
 * Chip for filtering content with selected state
 *
 * @param label Chip label
 * @param selected Whether chip is selected
 * @param onClick Click handler
 * @param modifier Modifier for customization
 * @param leadingIcon Optional leading icon
 * @param enabled Whether chip is enabled
 */
@Composable
fun AvoqadoFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier,
        enabled = enabled,
        leadingIcon = if (leadingIcon != null) {
            {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                )
            }
        } else if (selected) {
            {
                Icon(
                    imageVector = Icons.Default.Done,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                )
            }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

/**
 * Avoqado Input Chip
 *
 * Chip for displaying input or tags with optional close action
 *
 * @param label Chip label
 * @param onClick Click handler
 * @param modifier Modifier for customization
 * @param onClose Optional close/remove handler
 * @param leadingIcon Optional leading icon
 * @param enabled Whether chip is enabled
 */
@Composable
fun AvoqadoInputChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true
) {
    InputChip(
        selected = false,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(InputChipDefaults.IconSize)
                )
            }
        },
        trailingIcon = if (onClose != null) {
            {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove",
                    modifier = Modifier.size(InputChipDefaults.IconSize)
                )
            }
        } else {
            null
        },
        colors = InputChipDefaults.inputChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

/**
 * Avoqado Suggestion Chip
 *
 * Chip for displaying suggestions or actions
 *
 * @param label Chip label
 * @param onClick Click handler
 * @param modifier Modifier for customization
 * @param icon Optional icon
 * @param enabled Whether chip is enabled
 */
@Composable
fun AvoqadoSuggestionChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    SuggestionChip(
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier,
        enabled = enabled,
        icon = icon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                )
            }
        }
    )
}

// ========== Previews ==========

@Preview(showBackground = true)
@Composable
private fun AvoqadoFilterChipPreview() {
    AvoqadoTheme {
        Column(
            modifier = Modifier.padding(Spacing.Space4),
            verticalArrangement = Arrangement.spacedBy(Spacing.Space2)
        ) {
            var selectedAll by remember { mutableStateOf(false) }
            var selectedPending by remember { mutableStateOf(true) }
            var selectedCompleted by remember { mutableStateOf(false) }

            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Space2)
            ) {
                AvoqadoFilterChip(
                    label = "All",
                    selected = selectedAll,
                    onClick = { selectedAll = !selectedAll }
                )

                AvoqadoFilterChip(
                    label = "Pending",
                    selected = selectedPending,
                    onClick = { selectedPending = !selectedPending }
                )

                AvoqadoFilterChip(
                    label = "Completed",
                    selected = selectedCompleted,
                    onClick = { selectedCompleted = !selectedCompleted }
                )
            }

            // With icons
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Space2)
            ) {
                var starSelected by remember { mutableStateOf(false) }
                AvoqadoFilterChip(
                    label = "Favorites",
                    selected = starSelected,
                    onClick = { starSelected = !starSelected },
                    leadingIcon = Icons.Default.Star
                )

                AvoqadoFilterChip(
                    label = "Disabled",
                    selected = false,
                    onClick = {},
                    enabled = false
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AvoqadoInputChipPreview() {
    AvoqadoTheme {
        Column(
            modifier = Modifier.padding(Spacing.Space4),
            verticalArrangement = Arrangement.spacedBy(Spacing.Space2)
        ) {
            // Basic input chips
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Space2)
            ) {
                AvoqadoInputChip(
                    label = "Table 5",
                    onClick = {},
                    onClose = { /* Remove tag */ }
                )

                AvoqadoInputChip(
                    label = "Burger",
                    onClick = {},
                    onClose = { /* Remove tag */ }
                )

                AvoqadoInputChip(
                    label = "Paid",
                    onClick = {}
                )
            }

            // With icons
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Space2)
            ) {
                AvoqadoInputChip(
                    label = "Priority",
                    onClick = {},
                    leadingIcon = Icons.Default.Star,
                    onClose = { /* Remove */ }
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AvoqadoSuggestionChipPreview() {
    AvoqadoTheme {
        Column(
            modifier = Modifier.padding(Spacing.Space4),
            verticalArrangement = Arrangement.spacedBy(Spacing.Space2)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Space2)
            ) {
                AvoqadoSuggestionChip(
                    label = "Quick Add",
                    onClick = {}
                )

                AvoqadoSuggestionChip(
                    label = "Popular Items",
                    onClick = {},
                    icon = Icons.Default.Star
                )

                AvoqadoSuggestionChip(
                    label = "Disabled",
                    onClick = {},
                    enabled = false
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "All Chip Types")
@Composable
private fun AllChipTypesPreview() {
    AvoqadoTheme {
        Column(
            modifier = Modifier.padding(Spacing.Space4),
            verticalArrangement = Arrangement.spacedBy(Spacing.Space3)
        ) {
            // Filter chips
            Text(
                text = "Filter Chips",
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Space2)
            ) {
                var selected1 by remember { mutableStateOf(true) }
                AvoqadoFilterChip(
                    label = "Active",
                    selected = selected1,
                    onClick = { selected1 = !selected1 }
                )

                var selected2 by remember { mutableStateOf(false) }
                AvoqadoFilterChip(
                    label = "Inactive",
                    selected = selected2,
                    onClick = { selected2 = !selected2 }
                )
            }

            // Input chips
            Text(
                text = "Input Chips",
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Space2)
            ) {
                AvoqadoInputChip(
                    label = "Tag 1",
                    onClick = {},
                    onClose = {}
                )

                AvoqadoInputChip(
                    label = "Tag 2",
                    onClick = {},
                    onClose = {}
                )
            }

            // Suggestion chips
            Text(
                text = "Suggestion Chips",
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Space2)
            ) {
                AvoqadoSuggestionChip(
                    label = "Suggestion 1",
                    onClick = {}
                )

                AvoqadoSuggestionChip(
                    label = "Suggestion 2",
                    onClick = {}
                )
            }
        }
    }
}

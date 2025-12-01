package com.jaac.avoqado_tpv.features.payment.presentation.split.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme

/**
 * Person Count Selector
 *
 * Control for selecting the number of people to split an order.
 * Only shown when no parts have been paid yet (party size can't change mid-split).
 *
 * Layout:
 * ```
 * ┌───────────────────────────────────────┐
 * │  [-]         4 partes          [+]   │
 * └───────────────────────────────────────┘
 * ```
 *
 * **Constraints:**
 * - Minimum: 2 people (splitting with 1 is meaningless)
 * - Maximum: 20 people (practical limit)
 * - Disabled when party has already started paying
 *
 * @param count Current number of people
 * @param onDecrement Decrease count (min 2)
 * @param onIncrement Increase count (max 20)
 * @param enabled Whether selector is interactive
 * @param modifier Optional modifier
 */
@Composable
fun PersonCountSelector(
    count: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Decrement button
        FilledIconButton(
            onClick = onDecrement,
            enabled = enabled && count > MIN_PERSONS,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            )
        ) {
            Icon(
                imageVector = Icons.Default.Remove,
                contentDescription = "Disminuir personas"
            )
        }

        // Count display
        Text(
            text = "$count partes",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = if (enabled) {
                MaterialTheme.colorScheme.onBackground
            } else {
                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            }
        )

        // Increment button
        FilledIconButton(
            onClick = onIncrement,
            enabled = enabled && count < MAX_PERSONS,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            )
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Aumentar personas"
            )
        }
    }
}

// Constraints
private const val MIN_PERSONS = 2
private const val MAX_PERSONS = 20

// ============================================================================
// Previews
// ============================================================================

@Preview(showBackground = true)
@Composable
private fun PersonCountSelectorPreview() {
    AvoqadoTheme {
        PersonCountSelector(
            count = 4,
            onDecrement = {},
            onIncrement = {},
            enabled = true
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PersonCountSelectorMinPreview() {
    AvoqadoTheme {
        PersonCountSelector(
            count = 2,
            onDecrement = {},
            onIncrement = {},
            enabled = true
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PersonCountSelectorDisabledPreview() {
    AvoqadoTheme {
        PersonCountSelector(
            count = 4,
            onDecrement = {},
            onIncrement = {},
            enabled = false
        )
    }
}

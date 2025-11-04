package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.Size
import com.jaac.avoqado_tpv.core.presentation.theme.Spacing

/**
 * Avoqado Card
 *
 * Standard card component with consistent styling
 *
 * @param modifier Modifier for customization
 * @param onClick Optional click handler (makes card clickable)
 * @param content Card content
 */
@Composable
fun AvoqadoCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Size.CardCornerRadius),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = Size.CardElevation,
                pressedElevation = Size.CardElevationPressed
            )
        ) {
            Column(
                modifier = Modifier.padding(Spacing.Space4),
                content = content
            )
        }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Size.CardCornerRadius),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = Size.CardElevation
            )
        ) {
            Column(
                modifier = Modifier.padding(Spacing.Space4),
                content = content
            )
        }
    }
}

// ========== Previews ==========

@Preview(showBackground = true)
@Composable
private fun AvoqadoCardPreview() {
    AvoqadoTheme {
        Column(
            modifier = Modifier.padding(Spacing.Space4),
            verticalArrangement = Arrangement.spacedBy(Spacing.Space4)
        ) {
            // Basic card
            AvoqadoCard {
                Text(
                    text = "Order #1234",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Table 5 • 3 items",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "$45.00",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Clickable card
            AvoqadoCard(
                onClick = { /* Handle click */ }
            ) {
                Text(
                    text = "Table 3",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "Tap to view orders",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

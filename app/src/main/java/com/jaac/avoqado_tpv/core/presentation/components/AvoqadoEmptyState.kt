package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.Spacing

/**
 * Avoqado Empty State
 *
 * Component for displaying empty states with icon, message, and optional action
 *
 * @param icon Icon to display
 * @param title Primary message
 * @param description Optional secondary message
 * @param actionText Optional action button text
 * @param onActionClick Optional action button click handler
 * @param modifier Modifier for customization
 */
@Composable
fun AvoqadoEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(Spacing.Space6)
        ) {
            // Icon
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )

            Spacer(Modifier.height(Spacing.Space4))

            // Title
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // Description
            if (description != null) {
                Spacer(Modifier.height(Spacing.Space2))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
            }

            // Action button
            if (actionText != null && onActionClick != null) {
                Spacer(Modifier.height(Spacing.Space5))
                AvoqadoButton(
                    text = actionText,
                    onClick = onActionClick,
                    icon = Icons.Default.Add
                )
            }
        }
    }
}

// ========== Previews ==========

@Preview(showBackground = true)
@Composable
private fun AvoqadoEmptyStatePreview() {
    AvoqadoTheme {
        AvoqadoEmptyState(
            icon = Icons.Default.ShoppingCart,
            title = "No items in cart",
            description = "Add items to your cart to get started"
        )
    }
}

@Preview(showBackground = true, name = "Empty State with Action")
@Composable
private fun EmptyStateWithActionPreview() {
    AvoqadoTheme {
        AvoqadoEmptyState(
            icon = Icons.Default.ShoppingCart,
            title = "No orders yet",
            description = "Start creating orders to see them here",
            actionText = "Create Order",
            onActionClick = { /* Create order */ }
        )
    }
}

@Preview(showBackground = true, name = "Search Empty State")
@Composable
private fun SearchEmptyStatePreview() {
    AvoqadoTheme {
        AvoqadoEmptyState(
            icon = Icons.Default.Search,
            title = "No results found",
            description = "Try adjusting your search or filter to find what you're looking for"
        )
    }
}

@Preview(showBackground = true, name = "Info Empty State")
@Composable
private fun InfoEmptyStatePreview() {
    AvoqadoTheme {
        AvoqadoEmptyState(
            icon = Icons.Default.Info,
            title = "No data available",
            description = "Connect to the internet to sync your data",
            actionText = "Retry",
            onActionClick = { /* Retry sync */ }
        )
    }
}

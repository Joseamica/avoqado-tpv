package com.jaac.avoqado_tpv.features.ordering.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.R
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveScaffold
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme

/**
 * Ordering Welcome Screen
 *
 * Entry point for the ordering system.
 * Shows three options:
 * - Quick Order: For retail/QSR without table assignment
 * - Table Service: For restaurant with floor plan and table management
 * - View Orders: See list of all orders with filters
 *
 * Similar to Square POS ordering entry screen.
 *
 * @param modifier Modifier for customization
 * @param onQuickOrderClick Navigate to quick order flow (no table)
 * @param onTableServiceClick Navigate to table service flow (floor plan)
 * @param onViewOrdersClick Navigate to order list screen
 * @param onNavigateBack Navigate back to home
 */
@Composable
fun OrderingWelcomeScreen(
    modifier: Modifier = Modifier,
    onQuickOrderClick: () -> Unit = {},
    onTableServiceClick: () -> Unit = {},
    onViewOrdersClick: () -> Unit = {},
    onNavigateBack: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            AvoqadoTopBar(
                title = stringResource(R.string.ordering_welcome_title),
                onNavigationClick = onNavigateBack
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        ResponsiveScaffold(
            modifier = Modifier.padding(paddingValues),
            scrollable = false,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val sizes = LocalResponsiveSizes.current

            Spacer(modifier = Modifier.height(sizes.spacingLarge))

            // Quick Order Button
            OrderingOptionCard(
                icon = {
                    Icon(
                        imageVector = Icons.Default.ShoppingCart,
                        contentDescription = stringResource(R.string.ordering_quick_order_title),
                        modifier = Modifier.size(sizes.iconSizeLarge),
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                title = stringResource(R.string.ordering_quick_order_title),
                subtitle = stringResource(R.string.ordering_quick_order_subtitle),
                onClick = onQuickOrderClick,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(sizes.spacingMedium))

            // Table Service Button
            OrderingOptionCard(
                icon = {
                    Icon(
                        imageVector = Icons.Default.Restaurant,
                        contentDescription = stringResource(R.string.ordering_table_service_title),
                        modifier = Modifier.size(sizes.iconSizeLarge),
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                title = stringResource(R.string.ordering_table_service_title),
                subtitle = stringResource(R.string.ordering_table_service_subtitle),
                onClick = onTableServiceClick,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(sizes.spacingMedium))

            // View Orders Button
            OrderingOptionCard(
                icon = {
                    Icon(
                        imageVector = Icons.Default.Receipt,
                        contentDescription = "Ver Órdenes",
                        modifier = Modifier.size(sizes.iconSizeLarge),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                },
                title = "Ver Órdenes",
                subtitle = "Lista de todas las órdenes",
                onClick = onViewOrdersClick,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Ordering Option Card
 *
 * Large tappable card for ordering flow selection.
 * Follows Avoqado dark theme design.
 *
 * @param icon Icon composable
 * @param title Main title text
 * @param subtitle Descriptive subtitle
 * @param onClick Click callback
 * @param modifier Modifier for customization
 */
@Composable
private fun OrderingOptionCard(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current

    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(sizes.spacingLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Icon
            icon()

            Spacer(modifier = Modifier.height(sizes.spacingMedium))

            // Title
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(sizes.spacingSmall))

            // Subtitle
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@Preview(showBackground = true, widthDp = 600, heightDp = 1024, name = "PAX A80 (Portrait)")
@Composable
private fun OrderingWelcomeScreenPreview() {
    AvoqadoTheme {
        OrderingWelcomeScreen(
            onQuickOrderClick = {},
            onTableServiceClick = {},
            onViewOrdersClick = {},
            onNavigateBack = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 720, heightDp = 1280, name = "PAX A920 (Portrait)")
@Composable
private fun OrderingWelcomeScreenPreviewLarge() {
    AvoqadoTheme {
        OrderingWelcomeScreen(
            onQuickOrderClick = {},
            onTableServiceClick = {},
            onViewOrdersClick = {},
            onNavigateBack = {}
        )
    }
}

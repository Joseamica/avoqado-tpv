package com.jaac.avoqado_tpv.features.ordering.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.R
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.ordering.presentation.components.PayLaterBanner
import com.jaac.avoqado_tpv.features.ordering.presentation.components.UnpaidTakeoutBanner
import timber.log.Timber

/**
 * Ordering Welcome Screen
 *
 * Entry point for the ordering system with improved visual hierarchy.
 *
 * **Layout Structure:**
 * ```
 * ┌─────────────────────────────┐
 * │ Nueva Orden (Primary)       │  ← Section Header (Primary color)
 * ├─────────────────────────────┤
 * │ [Pedido rápido]            │  ← Large Card
 * │ [Servicio de Mesa]         │  ← Large Card (conditional)
 * ├─────────────────────────────┤
 * │        ───────              │  ← Divider
 * ├─────────────────────────────┤
 * │ Gestión (Secondary)         │  ← Section Header (Muted)
 * ├─────────────────────────────┤
 * │ [Ver Órdenes]              │  ← Compact OutlinedCard
 * └─────────────────────────────┘
 * ```
 *
 * **Visual Hierarchy:**
 * - Primary actions (create orders): Large cards with icons centered
 * - Secondary actions (view orders): Compact horizontal card
 * - Clear section separation with headers and divider
 *
 * Similar to Square POS ordering entry screen.
 *
 * @param modifier Modifier for customization
 * @param showTableService Whether to show table service option (based on venue type)
 * @param onQuickOrderClick Navigate to quick order flow (no table)
 * @param onTableServiceClick Navigate to table service flow (floor plan)
 * @param onViewOrdersClick Navigate to order list screen
 * @param onNavigateBack Navigate back to home
 */
@Composable
fun OrderingWelcomeScreen(
    modifier: Modifier = Modifier,
    showTableService: Boolean = true,
    onQuickOrderClick: () -> Unit = {},
    onTableServiceClick: () -> Unit = {},
    onViewOrdersClick: () -> Unit = {},
    onViewUnpaidOrdersClick: () -> Unit = {},
    onViewPayLaterOrdersClick: () -> Unit = {},
    onNavigateBack: () -> Unit = {},
    viewModel: OrderingWelcomeViewModel = hiltViewModel()
) {
    val unpaidTakeoutCount by viewModel.unpaidTakeoutCount.collectAsStateWithLifecycle()
    val payLaterCount by viewModel.payLaterCount.collectAsStateWithLifecycle()

    // Refresh orders when screen is displayed
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Scaffold(
        topBar = {
            AvoqadoTopBar(
                title = stringResource(R.string.ordering_welcome_title),
                titleStyle = MaterialTheme.typography.titleMedium,
                onNavigationClick = onNavigateBack,
                actions = {
                    IconButton(onClick = onViewOrdersClick) {
                        Icon(
                            imageVector = Icons.Default.Receipt,
                            contentDescription = "Ver Órdenes"
                        )
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { paddingValues ->
        @Suppress("UnusedBoxWithConstraintsScope")
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val sizes = ResponsiveSizes.calculate(maxHeight, maxWidth)

            CompositionLocalProvider(LocalResponsiveSizes provides sizes) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // ═══════════════════════════════════════════════════════════
                    // SECTION: Unpaid Takeout Banner (Warning - Red) - FullWidth
                    // ═══════════════════════════════════════════════════════════
                    UnpaidTakeoutBanner(
                        count = unpaidTakeoutCount,
                        onClick = {
                            Timber.d("🔔 [OrderingWelcome] UNPAID_TAKEOUT banner tapped")
                            onViewUnpaidOrdersClick()
                        },
                        modifier = Modifier.fillMaxWidth()  // FullWidth, sin padding
                    )

                    // ═══════════════════════════════════════════════════════════
                    // SECTION: Pay Later Banner (Info - Blue) - FullWidth
                    // ═══════════════════════════════════════════════════════════
                    PayLaterBanner(
                        count = payLaterCount,
                        onClick = {
                            Timber.d("💳 [OrderingWelcome] PAY_LATER banner tapped")
                            onViewPayLaterOrdersClick()
                        },
                        modifier = Modifier.fillMaxWidth()  // FullWidth, sin padding
                    )

                    // Content wrapper with horizontal padding
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = sizes.paddingScreen)
                    ) {
                        // Spacing between banner and Nueva Orden section
                        Spacer(modifier = Modifier.height(sizes.spacingMedium))

                        // ═══════════════════════════════════════════════════════════
                        // SECTION: Nueva Orden (Primary Actions)
                        // ═══════════════════════════════════════════════════════════
                        Column(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Nueva Orden",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = sizes.spacingSmall)
                            )

                            // Primary actions in grid (same style as ActionsTab)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(sizes.spacingSmall)
                            ) {
                                // Quick Order Card (compact)
                                CompactActionCard(
                                    icon = Icons.Default.ShoppingCart,
                                    title = stringResource(R.string.ordering_quick_order_title),
                                    subtitle = stringResource(R.string.ordering_quick_order_subtitle),
                                    backgroundColor = MaterialTheme.colorScheme.surface,
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                    onClick = onQuickOrderClick,
                                    modifier = Modifier.weight(1f)
                                )

                                // Table Service Card (compact, conditional)
                                if (showTableService) {
                                    CompactActionCard(
                                        icon = Icons.Default.Restaurant,
                                        title = stringResource(R.string.ordering_table_service_title),
                                        subtitle = stringResource(R.string.ordering_table_service_subtitle),
                                        backgroundColor = MaterialTheme.colorScheme.surface,
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                        onClick = onTableServiceClick,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact Action Card - ActionsTab Style
 *
 * Compact card matching ActionsTab's ShortcutActionCard pattern:
 * - Icon in top-left
 * - Title + subtitle at bottom
 * - Colored background
 * - Responsive height (iconSizeLarge * 2.5f)
 *
 * @param icon Icon vector
 * @param title Main title text
 * @param subtitle Descriptive subtitle
 * @param backgroundColor Card background color
 * @param contentColor Color for text and icons (default: White)
 * @param onClick Click callback
 * @param modifier Modifier for customization
 */
@Composable
fun CompactActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    backgroundColor: Color,
    contentColor: Color = Color.White,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(sizes.iconSizeLarge * 2.5f), // Same as ActionsTab
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Icon in top-left
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(32.dp),
                tint = contentColor
            )

            // Title and subtitle at bottom
            Column(
                modifier = Modifier.align(Alignment.BottomStart)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.9f)
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@Preview(showBackground = true, widthDp = 600, heightDp = 1024, name = "PAX A80 (Portrait)")
@Composable
internal fun OrderingWelcomeScreenPreview() {
    AvoqadoTheme {
        OrderingWelcomeScreen(
            onQuickOrderClick = {},
            onTableServiceClick = {},
            onViewOrdersClick = {},
            onViewUnpaidOrdersClick = {},
            onNavigateBack = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 720, heightDp = 1280, name = "PAX A920 (Portrait)")
@Composable
internal fun OrderingWelcomeScreenPreviewLarge() {
    AvoqadoTheme {
        OrderingWelcomeScreen(
            onQuickOrderClick = {},
            onTableServiceClick = {},
            onViewOrdersClick = {},
            onViewUnpaidOrdersClick = {},
            onNavigateBack = {}
        )
    }
}

package com.jaac.avoqado_tpv.features.ordering.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CheckTabBadgeSize = 28.dp

/**
 * Top tab row for MenuScreen
 *
 * Follows Square POS pattern with text-only tabs (no icons).
 * Compact design maximizes vertical content space.
 *
 * @param currentTab Currently selected tab
 * @param onTabSelected Callback when tab is selected
 * @param orderItemCount Total units in order (sum of quantities) for CHECK tab badge
 * @param modifier Optional modifier
 */
@Composable
fun OrderTabRow(
    currentTab: OrderTab,
    onTabSelected: (OrderTab) -> Unit,
    orderItemCount: Int = 0,
    modifier: Modifier = Modifier
) {
    ScrollableTabRow(
        selectedTabIndex = currentTab.ordinal,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        edgePadding = 0.dp
    ) {
        OrderTab.entries.forEach { tab ->
            Tab(
                selected = currentTab == tab,
                onClick = { onTabSelected(tab) },
                text = {
                    // Show badge for CHECK tab when there are items
                    if (tab == OrderTab.CHECK && orderItemCount > 0) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                            val badgeText = if (orderItemCount > 99) "99+" else orderItemCount.toString()
                            val badgeFontSize = if (badgeText.length > 2) 10.sp else 12.sp
                            // Badge with item count
                            Box(
                                modifier = Modifier
                                    .size(CheckTabBadgeSize)
                                    .background(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = badgeText,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = badgeFontSize,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    } else {
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                    }
                }
            )
        }
    }
}

/**
 * Order navigation tabs
 *
 * Enum representing the 4 tabs in the MenuScreen.
 * Text-only labels (no icons) following Square POS design.
 */
enum class OrderTab(
    val label: String
) {
    /**
     * Menu tab - Browse products and add to order
     *
     * Shows CategoryTabs + ProductGrid (existing MenuScreen content).
     * Primary action: Add products to order.
     */
    MENU(label = "Menú"),

    /**
     * Check tab - View order details and manage items
     *
     * Shows order items in expanded state + item management actions.
     * Primary actions: Remove items, adjust quantities, send to kitchen.
     */
    CHECK(label = "Cuenta"),

    /**
     * Actions tab - Order-level operations
     *
     * Shows comp, void, discount buttons with confirmation dialogs.
     * Requires manager authorization for sensitive operations.
     * Primary actions: Comp order, void items, apply discounts.
     */
    ACTIONS(label = "Acciones"),

    /**
     * Guest tab - Update guest information
     *
     * Shows conditional form based on order type:
     * - DINE_IN: Covers, customer name, special requests (allergies)
     * - TAKEOUT: Customer name, phone, special requests
     * Primary action: Update guest information.
     */
    GUEST(label = "Cliente")
}

// ============================================================
// PREVIEWS
// ============================================================

@Preview(showBackground = true)
@Composable
private fun OrderTabRowPreview_MenuTab() {
    MaterialTheme {
        OrderTabRow(
            currentTab = OrderTab.MENU,
            onTabSelected = {},
            orderItemCount = 0
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OrderTabRowPreview_CheckTab() {
    MaterialTheme {
        OrderTabRow(
            currentTab = OrderTab.CHECK,
            onTabSelected = {},
            orderItemCount = 4  // Show badge with 4 items
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OrderTabRowPreview_CheckTabEmpty() {
    MaterialTheme {
        OrderTabRow(
            currentTab = OrderTab.CHECK,
            onTabSelected = {},
            orderItemCount = 0  // No badge when empty
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OrderTabRowPreview_ActionsTab() {
    MaterialTheme {
        OrderTabRow(
            currentTab = OrderTab.ACTIONS,
            onTabSelected = {},
            orderItemCount = 4
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OrderTabRowPreview_GuestTab() {
    MaterialTheme {
        OrderTabRow(
            currentTab = OrderTab.GUEST,
            onTabSelected = {},
            orderItemCount = 4
        )
    }
}

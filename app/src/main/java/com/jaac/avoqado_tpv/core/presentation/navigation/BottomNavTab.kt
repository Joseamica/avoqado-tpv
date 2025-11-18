package com.jaac.avoqado_tpv.core.presentation.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Bottom navigation tabs for ordering system
 *
 * Hybrid system combining Toast's ergonomics + Square's simplicity.
 * 4 tabs (instead of Square's 5) for minimal space usage.
 *
 * Bottom nav height: 56dp (compact)
 *
 * Design decision rationale:
 * - 4 tabs vs Square's 5: Removed "Tab list" as redundant with Órdenes
 * - Menú combines product selection + check (Toast pattern)
 * - Top panel in Menú tab eliminates need for separate Check tab
 *
 * @see com.jaac.avoqado_tpv.core.presentation.components.OrderBottomNavigation
 */
enum class BottomNavTab(
    val label: String,
    val icon: ImageVector,
    val route: String
) {
    /**
     * Mesas (Floor Plan)
     *
     * Visual floor plan showing all tables with status colors:
     * - 🟢 Green = Available (can start new order)
     * - 🔴 Red = Occupied (has open order)
     * - 🟡 Yellow = Reserved
     *
     * Tap table → Navigate to Menú tab with order loaded
     */
    MESAS(
        label = "Mesas",
        icon = Icons.Default.TableRestaurant,
        route = "floor_plan_tab"
    ),

    /**
     * Menú (Product Selection + Check)
     *
     * HYBRID FEATURE: Combines two views in one tab
     * - Product Grid (2 columns portrait, 3 columns landscape)
     * - Top Panel (collapsible check with 3 states)
     *
     * Top Panel States:
     * - COLLAPSED (48dp): Summary line "Mesa 5 • 3 items • $150.00"
     * - PEEK (200dp): Recent 2-3 items + action buttons
     * - EXPANDED (700dp): Full order review with edit controls
     *
     * Space efficiency: 78% of screen dedicated to products when collapsed
     *
     * User flow:
     * 1. Tap product → Add to order (top panel updates)
     * 2. Swipe up panel → Review items
     * 3. Tap "Enviar" → Send to kitchen
     * 4. Tap "Pagar" → Navigate to PaymentScreen
     */
    MENU(
        label = "Menú",
        icon = Icons.Default.Restaurant,
        route = "menu_tab"
    ),

    /**
     * Órdenes (Order List)
     *
     * List view of all orders with filters:
     * - [Pendientes] [Completadas] [Todas]
     *
     * Order cards show:
     * - Table name/number
     * - Status badge (🟡 Pendiente, 🟢 En Cocina, ✅ Servida)
     * - Item count + total
     * - Time elapsed
     *
     * Tap order → Navigate to Menú tab with order loaded
     *
     * Real-time updates via Socket.IO (ORDER_UPDATED event)
     */
    ORDENES(
        label = "Órdenes",
        icon = Icons.Default.Receipt,
        route = "orders_tab"
    ),

    /**
     * Más (Settings & Admin)
     *
     * Administrative functions:
     * - User info (current shift)
     * - Shifts management (open/close shift)
     * - Reports (sales, items)
     * - Printer configuration
     * - SuperAdmin tools (conditional visibility)
     * - About / Logout
     */
    MAS(
        label = "Más",
        icon = Icons.Default.MoreVert,
        route = "more_tab"
    );

    companion object {
        /**
         * Get tab by route
         */
        fun fromRoute(route: String?): BottomNavTab? {
            return values().find { it.route == route }
        }

        /**
         * All tabs in display order
         */
        val all: List<BottomNavTab> = values().toList()
    }
}

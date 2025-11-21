package com.jaac.avoqado_tpv.core.presentation.navigation

/**
 * Navigation routes for Avoqado TPV
 *
 * Sealed class defining all navigation destinations in the app.
 * Similar to Square POS navigation structure.
 */
sealed class NavRoute(val route: String) {
    /**
     * Splash screen - Initial loading and activation validation
     */
    data object Splash : NavRoute("splash")

    /**
     * Activation screen - Terminal activation with 6-char code
     * Required when device is not yet activated
     */
    data object Activation : NavRoute("activation")

    /**
     * Login screen - PIN authentication for staff
     * Shown after successful activation
     */
    data object Login : NavRoute("login")

    /**
     * Home screen - Main dashboard after login
     */
    data object Home : NavRoute("home")

    /**
     * Fast Payment Entry screen - Dedicated screen for entering fast payment amount
     * Replaces modal-based amount input with full-screen navigation
     */
    data object FastPaymentEntry : NavRoute("fast_payment_entry")

    /**
     * Shifts screen - Shift management (open/close shifts)
     */
    data object Shifts : NavRoute("shifts")

    /**
     * Settings screen
     */
    data object Settings : NavRoute("settings")

    /**
     * Payment screen - EMV chip card payment with online authorization
     */
    data object Payment : NavRoute("payment")

    /**
     * SuperAdmin screen - Testing and debugging tools
     * Access limited to superadmin users
     */
    data object SuperAdmin : NavRoute("superadmin")

    /**
     * Ordering Welcome screen - Entry point for ordering system
     * Shows two options: Quick Order (retail/QSR) or Table Service (restaurant)
     */
    data object OrderingWelcome : NavRoute("ordering_welcome")

    /**
     * Table Service screen - Floor plan with table status
     * Allows staff to select tables to start orders
     */
    data object TableService : NavRoute("table_service")

    /**
     * Floor Plan Canvas screen - Visual floor plan editor
     * Interactive canvas with zoom/pan gestures for table layout management
     */
    data object FloorPlan : NavRoute("floor_plan")

    /**
     * Menu screen - Product selection + Order check (Hybrid Toast + Square pattern)
     * Shows product grid with collapsible order panel
     *
     * @param orderId Order unique identifier
     */
    data object Menu : NavRoute("menu/{orderId}") {
        fun createRoute(orderId: String) = "menu/$orderId"
    }

    /**
     * Order List screen - List of all orders with filters
     * Shows all venue orders with filter chips (ALL, OPEN, IN_PROGRESS, COMPLETED)
     */
    data object OrderList : NavRoute("order_list")

    /**
     * Reports screen - Sales analytics and reports dashboard
     * Shows sales summary, payment breakdown, and shift history
     */
    data object Reports : NavRoute("reports")

    /**
     * Historical Period Detail screen - Detailed view of a single historical period
     * Shows complete metrics, comparisons, and payment method breakdown
     *
     * Navigation pattern: Reports → HistoricalPeriodDetail
     *
     * Period data is passed via ViewModel instead of navigation args to avoid:
     * - URL encoding issues with BigDecimal/Instant
     * - Navigation arg size limits
     * - Complex serialization logic
     */
    data object HistoricalPeriodDetail : NavRoute("historical_period_detail")
}

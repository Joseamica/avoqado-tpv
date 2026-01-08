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
     * Timeclock screen - Employee clock in/out and breaks
     * Accessed from Login screen with PIN (without creating full session)
     *
     * @param venueId Venue identifier
     * @param pin Staff PIN for verification
     */
    data object Timeclock : NavRoute("timeclock/{venueId}/{pin}") {
        fun createRoute(venueId: String, pin: String) = "timeclock/$venueId/$pin"
    }

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
     * Shows all venue orders with filter chips (ALL, OPEN, IN_PROGRESS, COMPLETED, UNPAID_TAKEOUT)
     *
     * @param filter Optional filter to apply (e.g., "UNPAID_TAKEOUT")
     */
    data object OrderList : NavRoute("order_list?filter={filter}") {
        fun createRoute(filter: String? = null) = if (filter != null) {
            "order_list?filter=$filter"
        } else {
            "order_list"
        }
    }

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

    /**
     * Payments screen - Payment history with pagination and filters
     * Shows complete payment transaction history with date range and method filters
     *
     * Pattern: Toast POS + Square Terminal
     * - Paginated list (20 items per page)
     * - Date range filter (7d, 30d, 90d, all time)
     * - Payment method filter (CASH, CARD, all)
     */
    data object Payments : NavRoute("payments")

    /**
     * Support screen - Help and support resources
     * Provides contact information, FAQs, documentation links, and app info
     *
     * Pattern: Toast POS + Square Terminal help screens
     * - Contact options (email, phone, WhatsApp)
     * - Quick actions (report bug, request feature)
     * - FAQ section
     * - App version and device information
     */
    data object Support : NavRoute("support")

    /**
     * Self-Update screen - Check and install app updates
     * Allows updating the app directly via Blumon SDK without using Blumon app
     *
     * Flow: CheckVersion → Download → Install via PAX SDK
     *
     * Note: After successful installation, terminal goes to PAX home menu
     */
    data object SelfUpdate : NavRoute("self_update")

    /**
     * Split by Product screen - Select specific products to pay
     * Used when splitting payment by selecting individual items
     *
     * @param orderId Order unique identifier
     */
    data object SplitByProduct : NavRoute("split_by_product/{orderId}") {
        fun createRoute(orderId: String) = "split_by_product/$orderId"
    }

    /**
     * Split by Person screen - Split order equally among N people
     * Used when splitting payment evenly among party members
     *
     * @param orderId Order unique identifier
     */
    data object SplitByPerson : NavRoute("split_by_person/{orderId}") {
        fun createRoute(orderId: String) = "split_by_person/$orderId"
    }

    /**
     * Refund Confirmation screen - Review and confirm refund before processing
     * User reviews refund details before card is presented
     *
     * Pattern: Square POS refund flow
     * - Select refund reason
     * - Confirm amount (full or partial)
     * - Present same card for refund
     *
     * @param paymentId Original payment ID to refund
     *
     * Note: Full Payment object is passed via savedStateHandle (too many fields for URL args)
     */
    data object RefundConfirmation : NavRoute("refund_confirmation/{paymentId}") {
        fun createRoute(paymentId: String) = "refund_confirmation/$paymentId"
    }

    // ==================== KIOSK MODE ROUTES ====================

    /**
     * Kiosk Welcome screen - "Touch to Start" entry point
     * Fullscreen customer-facing welcome with venue branding
     *
     * Contains hidden exit gesture (5 taps on logo) for staff
     */
    data object KioskWelcome : NavRoute("kiosk/welcome")

    /**
     * Kiosk Menu screen - Self-service product selection
     * Grid of products with category filter bar and floating cart button
     *
     * Pattern: McDonald's kiosk style
     * - Category bar at top
     * - Large product cards with photos
     * - Floating cart FAB with item count
     */
    data object KioskMenu : NavRoute("kiosk/menu")

    /**
     * Kiosk Cart screen - Order summary before payment
     * Shows cart items with +/- controls, total, and "Pay Now" button
     */
    data object KioskCart : NavRoute("kiosk/cart")

    /**
     * Kiosk Success screen - Thank you after payment
     * Shows order number and auto-resets to welcome after countdown
     *
     * @param orderNumber Display order number for customer
     */
    data object KioskSuccess : NavRoute("kiosk/success/{orderNumber}") {
        fun createRoute(orderNumber: String) = "kiosk/success/$orderNumber"
    }

    // ==================== SERIALIZED INVENTORY ROUTES ====================

    /**
     * Serialized Sale screen - Quick sell flow for serialized items
     * Scan barcode → Enter price → Create order → Payment
     *
     * Used for telecom SIMs, jewelry, electronics with unique serial numbers.
     * Flow: Barcode → Price → Payment (skips tip/review based on module config)
     */
    data object SerializedSale : NavRoute("serialized_sale")

    /**
     * Serialized Inventory Register screen - Batch registration of items
     * Select category → Scan multiple barcodes → Register batch
     *
     * Used for "Alta de Productos" flow to add inventory.
     * Allows registering multiple serial numbers at once.
     */
    data object SerializedInventoryRegister : NavRoute("serialized_inventory_register")
}

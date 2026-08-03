package com.jaac.avoqado_tpv.core.presentation.navigation

import android.net.Uri

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
    data object Timeclock : NavRoute("timeclock/{venueId}/{pin}?fromHome={fromHome}&autoAction={autoAction}") {
        fun createRoute(venueId: String, pin: String, fromHome: Boolean = false, autoAction: String = "") =
            "timeclock/$venueId/$pin?fromHome=$fromHome&autoAction=$autoAction"
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
     * Checkout screen - Unified cart inspired by avoqado-android.
     * 4 tabs: Teclado, Shortcuts, Todos los productos, Configurar.
     * Additive rollout — runs in parallel with FastPaymentEntry and OrderingWelcome
     * during validation. Old screens get removed in a separate ola (Phase 8).
     */
    data object Checkout : NavRoute("checkout")

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
    data object Payments : NavRoute("payments?autoOpenPaymentId={autoOpenPaymentId}") {
        /**
         * Build a Payments route URL. Pass [autoOpenPaymentId] to auto-open
         * the detail bottom sheet for that payment as soon as the list
         * finishes loading — used as a deep-link from the post-payment
         * success screen so the operator lands directly on the just-made
         * payment with refund options visible.
         */
        fun createRoute(autoOpenPaymentId: String? = null): String {
            return if (autoOpenPaymentId.isNullOrBlank()) {
                "payments"
            } else {
                "payments?autoOpenPaymentId=$autoOpenPaymentId"
            }
        }
    }

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

    /**
     * Messages screen - Full message inbox with filters and pagination
     * Shows all messages delivered to this terminal with filter chips (All, Unread, Read)
     */
    data object Messages : NavRoute("messages")

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

    // ==================== TRAINING / LMS ROUTES ====================

    /**
     * Training list screen - Shows available training modules
     * Card list with progress tracking and required badges
     */
    data object Trainings : NavRoute("trainings")

    /**
     * Training step viewer - Step-by-step training with media
     * Navigates to quiz after last step if quiz exists
     *
     * @param trainingId Training module identifier
     */
    data object TrainingViewer : NavRoute("training_viewer/{trainingId}") {
        fun createRoute(trainingId: String) = "training_viewer/$trainingId"
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
     * AngelPay Payment screen - Payment via AngelPay SDK on Nexgo terminals
     * with app-to-app fallback for rollout safety.
     * Isolated from Blumon PaymentScreen — separate ViewModel and state machine.
     */
    data object AngelPayPayment : NavRoute("angelpay_payment")

    /**
     * Unified processor transactions screen.
     * Supports post-operations (history/cancel/refund/tickets) via processor adapters.
     */
    data object PaymentTransactions : NavRoute("payment_transactions/{processorType}?autoRefundReference={autoRefundReference}") {
        fun createRoute(processorType: String, autoRefundReference: String? = null): String {
            val base = "payment_transactions/$processorType"
            return if (autoRefundReference.isNullOrBlank()) {
                base
            } else {
                "$base?autoRefundReference=${Uri.encode(autoRefundReference)}"
            }
        }
    }

    /**
     * My Sales screen - Promoter's serialized item sales history
     * Shows sales grouped by day with monthly totals
     *
     * Used by promoters to track their SIM/serialized item sales.
     */
    data object MySales : NavRoute("my_sales")

    /**
     * My Commissions ("Mis Comisiones") — the promoter's own cash-out balance +
     * same-day withdrawal. Gated by SERIALIZED_INVENTORY + cash-out:view_own.
     */
    data object MyCommissions : NavRoute("my_commissions")

    /**
     * Sale Correction screen — re-upload rejected documentation for a sale.
     *
     * Reached from "Mis Ventas" when the promoter taps a sale flagged
     * "Revisar documentación" (back-office rejected the proof-of-sale photos).
     * Loads the existing SaleVerification by id, shows the rejection reasons,
     * and lets the promoter re-take photos on the SAME sale (no duplicate).
     */
    data object SaleCorrection : NavRoute("sale_correction/{verificationId}") {
        fun createRoute(verificationId: String): String = "sale_correction/$verificationId"
    }

    /**
     * Serialized Inventory Register screen - Batch registration of items
     * Select category → Scan multiple barcodes → Register batch
     *
     * Used for "Alta de Productos" flow to add inventory.
     * Allows registering multiple serial numbers at once.
     */
    data object SerializedInventoryRegister : NavRoute("serialized_inventory_register")

    /**
     * Mis SIMs screen - Promoter's SIM custody inbox.
     *
     * Shows SIMs assigned by the Supervisor in three groupings:
     *  - Pendientes de aceptar (PROMOTER_PENDING) — requires accept/reject.
     *  - En mi poder (PROMOTER_HELD) — sellable inventory.
     *  - Vendidos hoy (SOLD in venue TZ).
     *
     * Gated by: role == WAITER && isSerializedInventoryMode &&
     * permission "tpv-sim-custody:accept". Reached from WelcomeScreen tile
     * and from the "SIM no aceptado" dialog in SerializedSaleViewModel.
     */
    data object MisSims : NavRoute("mis_sims")

    /**
     * Mesas — entry point for the new table-service module (`features/tables/`).
     *
     * Isolated from `features/ordering/` (legacy) and `features/checkout/` (Cobrar);
     * see `docs/superpowers/plans/2026-07-24-tpv-plan-c-modulo-mesas.md`. Only
     * reachable when `TpvSettings.restaurantModeEnabled` is ON, which also hides
     * the legacy "Órdenes" tile (spec D-4) — the two never coexist.
     *
     * Mounted (Plan C, Task 6): `TablesScreen` — canvas ⇄ lista, read-only
     * plano (editing lives in the dashboard). See `AppNavigation.kt`.
     */
    data object Tables : NavRoute("tables")

    /**
     * `TableOrderScreen` (Plan C, Task 7) — lo ya enviado a cocina (por
     * rondas) vs lo pendiente. Sin argumentos: lee la mesa activa de
     * `TableSession` (singleton), igual patrón que `Tables` no necesita un
     * `tableId` porque el plano entero se vuelve a pedir. Reachable SOLO tras
     * "Abrir mesa y ordenar" / "Ver cuenta" en `TableSheet`
     * (`TablesViewModel.openTableAndOrder`/`viewExistingCheck`).
     */
    data object TableOrder : NavRoute("table_order")

    /**
     * `TableMenuScreen` (Plan C, Task 7) — catálogo para armar la ronda,
     * empujado sobre `TableOrder` en el back stack (paso a paso, no dos
     * paneles). Escribe en `PendingRoundCart` (singleton compartido); "Listo"
     * regresa a `TableOrder` con el back stack normal (`popBackStack`).
     */
    data object TableMenu : NavRoute("table_menu")

    /**
     * `TableCheckoutScreen` (Plan C, Task 8) — la costura hacia el pago. Sin
     * argumentos: lee la mesa/orden activa de `TableSession`, mismo criterio
     * que `TableOrder`/`TableMenu`. EFECTIVO se resuelve DENTRO de esta
     * pantalla (nunca navega); TARJETA navega a `getPaymentRoute()` con
     * `entryPoint=tables` en el `savedStateHandle` (ver `AppNavigation.kt`) —
     * "Cobrar" en sí (`PaymentScreen`/`AngelPayPaymentScreen`) queda
     * intocado, regla dura del founder.
     */
    data object TableCheckout : NavRoute("table_checkout")
}

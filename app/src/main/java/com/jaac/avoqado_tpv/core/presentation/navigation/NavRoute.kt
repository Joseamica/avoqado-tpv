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
}

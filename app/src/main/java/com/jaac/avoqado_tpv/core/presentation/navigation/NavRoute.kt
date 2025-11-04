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
     * Settings screen
     */
    data object Settings : NavRoute("settings")
}

package com.jaac.avoqado_tpv.core.presentation.navigation

import androidx.navigation.NavController
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong

/**
 * SafeNavigationHelper
 *
 * Prevents race conditions and black screens from rapid back button clicks.
 *
 * **Problem**:
 * ```
 * // User clicks back 3 times rapidly (within 300ms)
 * Stack: [Welcome → FastPaymentEntry → Payment]
 * Click 1: [Welcome → FastPaymentEntry]  ✅
 * Click 2: [Welcome]                      ✅
 * Click 3: []                             ❌ EMPTY STACK → BLACK SCREEN
 * ```
 *
 * **Solution**:
 * - Debounce clicks (300ms window)
 * - Validate backstack is not empty before popping
 * - Fallback to safe route if stack would be empty
 *
 * **Usage**:
 * ```kotlin
 * // ❌ BEFORE (unsafe)
 * onNavigateBack = { navController.popBackStack() }
 *
 * // ✅ AFTER (safe)
 * onNavigateBack = { safePopBackStack(navController) }
 * ```
 *
 * Inspired by Square/Toast POS navigation patterns.
 */
object SafeNavigationHelper {
    /**
     * Last navigation timestamp for debouncing
     */
    private val lastNavigationTime = AtomicLong(0L)

    /**
     * Debounce window in milliseconds
     * 300ms is the standard "double-click prevention" window
     */
    private const val DEBOUNCE_DELAY_MS = 300L

    /**
     * Safe popBackStack with debouncing and empty stack protection
     *
     * @param navController NavController instance
     * @param fallbackRoute Route to navigate to if stack would be empty (default: Home)
     * @return true if navigation was successful, false if debounced/prevented
     */
    fun safePopBackStack(
        navController: NavController,
        fallbackRoute: String = NavRoute.Home.route
    ): Boolean {
        val currentTime = System.currentTimeMillis()
        val lastTime = lastNavigationTime.get()

        // 🚫 DEBOUNCE: Ignore if within debounce window
        if (currentTime - lastTime < DEBOUNCE_DELAY_MS) {
            Timber.w("🚫 [SafeNav] Debounced popBackStack (${currentTime - lastTime}ms since last navigation)")
            return false
        }

        // ✅ Safe to pop - update timestamp and execute
        try {
            val popped = navController.popBackStack()
            lastNavigationTime.set(currentTime)

            if (popped) {
                Timber.d("✅ [SafeNav] Successfully popped backstack")
            } else {
                // 🛡️ PROTECTION: If can't pop (already at root), navigate to fallback
                Timber.w("⚠️ [SafeNav] Can't pop (at root) - navigating to fallback: $fallbackRoute")
                try {
                    navController.navigate(fallbackRoute) {
                        // Clear backstack to prevent further issues
                        popUpTo(0) { inclusive = true }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "❌ [SafeNav] Failed to navigate to fallback route: $fallbackRoute")
                }
            }

            return popped
        } catch (e: Exception) {
            Timber.e(e, "❌ [SafeNav] Exception during popBackStack")
            return false
        }
    }

    /**
     * Safe popBackStack with custom route and inclusive flag
     *
     * @param navController NavController instance
     * @param route Route to pop back to
     * @param inclusive Whether to include the route in the pop
     * @return true if navigation was successful, false if debounced/prevented
     */
    fun safePopBackStack(
        navController: NavController,
        route: String,
        inclusive: Boolean
    ): Boolean {
        val currentTime = System.currentTimeMillis()
        val lastTime = lastNavigationTime.get()

        // 🚫 DEBOUNCE: Ignore if within debounce window
        if (currentTime - lastTime < DEBOUNCE_DELAY_MS) {
            Timber.w("🚫 [SafeNav] Debounced popBackStack(route) (${currentTime - lastTime}ms since last navigation)")
            return false
        }

        // ✅ Execute pop with route
        try {
            val popped = navController.popBackStack(route, inclusive)
            lastNavigationTime.set(currentTime)

            if (popped) {
                Timber.d("✅ [SafeNav] Successfully popped to route: $route (inclusive=$inclusive)")
            } else {
                Timber.w("⚠️ [SafeNav] Failed to pop to route: $route (route not in backstack?)")
            }

            return popped
        } catch (e: Exception) {
            Timber.e(e, "❌ [SafeNav] Exception during popBackStack(route)")
            return false
        }
    }

    /**
     * Reset debounce state (for testing)
     * DO NOT use in production code
     */
    @androidx.annotation.VisibleForTesting
    fun resetForTesting() {
        lastNavigationTime.set(0L)
    }
}

/**
 * Extension function for convenient safe navigation
 *
 * **Usage**:
 * ```kotlin
 * onNavigateBack = { navController.safePopBackStack() }
 * ```
 */
fun NavController.safePopBackStack(fallbackRoute: String = NavRoute.Home.route): Boolean {
    return SafeNavigationHelper.safePopBackStack(this, fallbackRoute)
}

/**
 * Extension function for safe navigation to specific route
 *
 * **Usage**:
 * ```kotlin
 * navController.safePopBackStack(NavRoute.Home.route, inclusive = false)
 * ```
 */
fun NavController.safePopBackStack(route: String, inclusive: Boolean): Boolean {
    return SafeNavigationHelper.safePopBackStack(this, route, inclusive)
}

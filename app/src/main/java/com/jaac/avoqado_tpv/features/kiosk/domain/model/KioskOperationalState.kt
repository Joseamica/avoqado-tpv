package com.jaac.avoqado_tpv.features.kiosk.domain.model

/**
 * Kiosk Operational State
 *
 * Represents the operational readiness of the kiosk.
 * Used to determine what screen to show to customers.
 *
 * Flow:
 * Checking → Ready (normal operation)
 *         → NoShift (turno cerrado)
 *         → AuthExpired (token expirado)
 *         → NetworkError (sin conexión persistente)
 *         → Disabled (kiosk apagado manualmente)
 */
sealed class KioskOperationalState {
    /**
     * Verifying operational status (initial state, checking shift, etc.)
     */
    data object Checking : KioskOperationalState()

    /**
     * Kiosk is ready to accept orders
     * All requirements met: shift open, auth valid, network ok
     */
    data object Ready : KioskOperationalState()

    /**
     * Shift is closed - kiosk cannot accept new orders
     * Admin can open shift from Admin Mode
     *
     * @param allowCurrentOrder If true, allow customer with existing cart to finish
     */
    data class NoShift(val allowCurrentOrder: Boolean = false) : KioskOperationalState()

    /**
     * Authentication token has expired
     * Admin needs to re-authenticate from Admin Mode
     */
    data object AuthExpired : KioskOperationalState()

    /**
     * Persistent network error (3+ failed checks)
     * Show unavailable until connection restored
     */
    data object NetworkError : KioskOperationalState()

    /**
     * Kiosk mode is disabled
     * This state means we should exit kiosk and return to staff mode
     */
    data object Disabled : KioskOperationalState()

    /**
     * Check if kiosk can accept new orders
     */
    fun canAcceptNewOrders(): Boolean = this is Ready

    /**
     * Check if current customer should be allowed to finish their order
     * (Used when shift closes mid-transaction)
     */
    fun canFinishCurrentOrder(): Boolean = when (this) {
        is Ready -> true
        is NoShift -> allowCurrentOrder
        else -> false
    }

    /**
     * Check if admin actions are available
     * (Always true except when disabled)
     */
    fun isAdminAccessible(): Boolean = this !is Disabled

    /**
     * Get user-facing message for unavailable states
     */
    fun getUnavailableReason(): UnavailableReason? = when (this) {
        is Ready, Checking -> null
        is NoShift -> UnavailableReason.SHIFT_CLOSED
        is AuthExpired -> UnavailableReason.AUTH_EXPIRED
        is NetworkError -> UnavailableReason.NETWORK_ERROR
        is Disabled -> UnavailableReason.DISABLED
    }
}

/**
 * Reasons why kiosk is unavailable
 * Used for displaying appropriate messages to customers
 */
enum class UnavailableReason {
    SHIFT_CLOSED,
    AUTH_EXPIRED,
    NETWORK_ERROR,
    DISABLED
}

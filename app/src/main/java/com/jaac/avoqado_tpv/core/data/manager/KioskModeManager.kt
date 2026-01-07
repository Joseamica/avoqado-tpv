package com.jaac.avoqado_tpv.core.data.manager

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.features.kiosk.domain.model.KioskOperationalState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kiosk Mode Manager - Self-Service Terminal State Management
 *
 * **WHY**: Provides centralized state management for kiosk/self-service mode.
 * When enabled, the terminal transforms into a customer-facing self-order kiosk.
 *
 * **Design Decision**: Use StateFlow + SecureStorage because:
 * - Kiosk state needs to survive screen rotations (StateFlow)
 * - State MUST persist across app restarts (SecureStorage)
 * - AppNavigation needs to observe mode to switch navigation graphs
 *
 * **v2 Architecture (Admin Mode)**:
 * - Added `operationalState` to track if kiosk can accept orders
 * - Added `hasOrderInProgress` to allow finishing orders when shift closes
 * - Admin Mode allows configuration without exiting kiosk
 *
 * **When in Kiosk Mode**:
 * - Shows simplified customer-facing UI (KioskNavGraph)
 * - Hides staff features (discounts, comps, order history, settings)
 * - All orders are TAKEOUT type
 * - Only card payments allowed
 * - Exit requires PIN + elevated role (ADMIN/OWNER/MANAGER)
 *
 * **Operational States**:
 * - Ready: Normal operation, accepting orders
 * - NoShift: Shift closed, shows unavailable screen
 * - AuthExpired: Token expired, needs re-auth via Admin Mode
 * - NetworkError: Can't verify status, shows unavailable
 * - Disabled: Kiosk turned off, return to staff mode
 *
 * @see KioskOperationalState Operational state sealed class
 * @see KioskNavigation Navigation graph for kiosk mode
 */
@Singleton
class KioskModeManager @Inject constructor(
    private val secureStorage: SecureStorage
) {

    /**
     * Whether the terminal is currently in kiosk mode
     * When true, AppNavigation shows KioskNavGraph instead of staff navigation
     * Initialized from SecureStorage to persist across app restarts
     */
    private val _isKioskMode = MutableStateFlow(secureStorage.getIsKioskMode())
    val isKioskMode: StateFlow<Boolean> = _isKioskMode.asStateFlow()

    /**
     * Operational state of the kiosk
     * Determines what screen to show (normal flow vs unavailable)
     */
    private val _operationalState = MutableStateFlow<KioskOperationalState>(
        if (secureStorage.getIsKioskMode()) KioskOperationalState.Checking
        else KioskOperationalState.Disabled
    )
    val operationalState: StateFlow<KioskOperationalState> = _operationalState.asStateFlow()

    /**
     * Flag to track if customer has an order in progress
     * When true and shift closes, allows customer to finish their order
     */
    private val _hasOrderInProgress = MutableStateFlow(false)
    val hasOrderInProgress: StateFlow<Boolean> = _hasOrderInProgress.asStateFlow()

    /**
     * Cached venue ID for kiosk operations
     * Set when entering kiosk mode, used for shift checks
     */
    private val _kioskVenueId = MutableStateFlow<String?>(secureStorage.getKioskVenueId())
    val kioskVenueId: StateFlow<String?> = _kioskVenueId.asStateFlow()

    /**
     * Counter for consecutive network failures
     * After 3 failures, show NetworkError state
     */
    private var networkFailureCount = 0
    private val MAX_NETWORK_FAILURES = 3

    init {
        // Log restored state on startup
        if (_isKioskMode.value) {
            Timber.i("🥝 [KIOSK] State restored from storage - Kiosk mode is ACTIVE")
            Timber.d("🥝 [KIOSK] VenueId: ${_kioskVenueId.value}")
        }
    }

    /**
     * Enter kiosk mode
     *
     * @param venueId The venue ID to use for kiosk operations (shift checks, orders)
     *
     * State is persisted to SecureStorage to survive app restarts.
     * When active:
     * - Navigation switches to KioskNavGraph
     * - Staff features are hidden
     * - Only customer self-service flow is available
     * - Operational state starts as Checking until verified
     */
    fun enterKioskMode(venueId: String) {
        _kioskVenueId.value = venueId
        _isKioskMode.value = true
        _operationalState.value = KioskOperationalState.Checking
        _hasOrderInProgress.value = false
        networkFailureCount = 0

        // Persist state
        secureStorage.saveKioskMode(isEnabled = true)
        secureStorage.saveKioskVenueId(venueId)

        Timber.i("🥝 [KIOSK] Entered kiosk mode (venueId: $venueId, persisted)")
    }

    /**
     * Exit kiosk mode
     *
     * State is persisted to SecureStorage to survive app restarts.
     * Requires staff with elevated role (ADMIN/OWNER/MANAGER) to exit.
     */
    fun exitKioskMode() {
        _isKioskMode.value = false
        _operationalState.value = KioskOperationalState.Disabled
        _hasOrderInProgress.value = false
        networkFailureCount = 0

        // Persist state (but keep venueId for potential re-activation)
        secureStorage.saveKioskMode(isEnabled = false)

        Timber.i("🥝 [KIOSK] Exited kiosk mode (persisted)")
    }

    /**
     * Update operational state based on shift status
     *
     * @param isShiftOpen Whether the shift is currently open
     *
     * Called periodically by KioskNavigation to verify operational status.
     * If shift closes while customer has order in progress, allows them to finish.
     */
    fun updateShiftStatus(isShiftOpen: Boolean) {
        val previousState = _operationalState.value
        val hasOrder = _hasOrderInProgress.value

        if (isShiftOpen) {
            // Shift is open - kiosk is ready
            networkFailureCount = 0
            _operationalState.value = KioskOperationalState.Ready
            Timber.d("🥝 [KIOSK] Shift is OPEN - operational state: Ready")
        } else {
            // Shift is closed
            if (hasOrder && previousState is KioskOperationalState.Ready) {
                // Customer had started an order - allow them to finish
                _operationalState.value = KioskOperationalState.NoShift(allowCurrentOrder = true)
                Timber.i("🥝 [KIOSK] Shift CLOSED but order in progress - allowing to finish")
            } else {
                // No order in progress - show unavailable
                _operationalState.value = KioskOperationalState.NoShift(allowCurrentOrder = false)
                Timber.i("🥝 [KIOSK] Shift is CLOSED - operational state: NoShift")
            }
        }
    }

    /**
     * Handle network error when checking operational status
     * After 3 consecutive failures, show NetworkError state
     */
    fun handleNetworkError() {
        networkFailureCount++
        Timber.w("🥝 [KIOSK] Network error (count: $networkFailureCount/$MAX_NETWORK_FAILURES)")

        if (networkFailureCount >= MAX_NETWORK_FAILURES) {
            _operationalState.value = KioskOperationalState.NetworkError
            Timber.e("🥝 [KIOSK] Max network failures reached - showing NetworkError")
        }
    }

    /**
     * Reset network failure count on successful operation
     */
    fun resetNetworkFailures() {
        if (networkFailureCount > 0) {
            Timber.d("🥝 [KIOSK] Network recovered - resetting failure count")
            networkFailureCount = 0
        }
    }

    /**
     * Handle authentication expiration
     * Called when 401 is received from backend
     */
    fun handleAuthExpired() {
        _operationalState.value = KioskOperationalState.AuthExpired
        Timber.e("🥝 [KIOSK] Auth token expired - showing AuthExpired")
    }

    /**
     * Set ready state after successful re-authentication
     */
    fun handleReauthenticated() {
        _operationalState.value = KioskOperationalState.Ready
        networkFailureCount = 0
        Timber.i("🥝 [KIOSK] Re-authenticated - operational state: Ready")
    }

    /**
     * Mark that customer has started an order
     * Called when items are added to cart
     */
    fun setOrderInProgress(inProgress: Boolean) {
        _hasOrderInProgress.value = inProgress
        Timber.d("🥝 [KIOSK] Order in progress: $inProgress")
    }

    /**
     * Set operational state to Ready
     * Called after initial check confirms everything is OK
     */
    fun setReady() {
        _operationalState.value = KioskOperationalState.Ready
        networkFailureCount = 0
        Timber.i("🥝 [KIOSK] Initial check passed - operational state: Ready")
    }

    /**
     * Check if terminal is currently in kiosk mode
     * Useful for guards before showing staff-only features
     */
    fun isCurrentlyInKioskMode(): Boolean = _isKioskMode.value

    /**
     * Check if kiosk can accept new orders
     */
    fun canAcceptNewOrders(): Boolean = _operationalState.value.canAcceptNewOrders()

    /**
     * Check if admin mode is accessible (gear icon should be visible)
     */
    fun isAdminAccessible(): Boolean = _operationalState.value.isAdminAccessible()
}

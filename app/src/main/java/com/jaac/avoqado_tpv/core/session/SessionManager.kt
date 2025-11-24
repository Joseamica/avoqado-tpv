package com.jaac.avoqado_tpv.core.session

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session Manager - Centralized Session State Management
 *
 * **WHY**: Provide a centralized way to manage and observe authentication session state.
 * This allows TokenAuthenticator to notify the UI when the session expires without
 * creating direct dependencies on navigation components.
 *
 * **Design Decision**: Use SharedFlow instead of StateFlow because:
 * - Session expiration is an EVENT (one-time occurrence), not STATE (continuous value)
 * - We don't want to replay "session expired" to late collectors
 * - Multiple screens can react to the same expiration event
 *
 * **Flow:**
 * ```
 * TokenAuthenticator.authenticate() (401)
 *   ↓ (refresh token fails)
 * SessionManager.notifySessionExpired()
 *   ↓ (emit event)
 * AppNavigation observes sessionEvents
 *   ↓
 * Navigate to Login screen
 * ```
 *
 * **Pattern:** Similar to Square/Toast POS - session events bubble up to UI layer
 *
 * @see TokenAuthenticator Where session expiration is detected
 * @see AppNavigation Where session events are observed
 */
@Singleton
class SessionManager @Inject constructor() {

    /**
     * Session events shared flow
     *
     * **replay = 0**: Don't replay events to late collectors (avoid duplicate logout)
     * **extraBufferCapacity = 1**: Buffer one event in case of temporary backpressure
     */
    private val _sessionEvents = MutableSharedFlow<SessionEvent>(
        replay = 0,
        extraBufferCapacity = 1
    )

    /**
     * Public read-only session events
     * Subscribe to this flow in AppNavigation or ViewModels
     */
    val sessionEvents: SharedFlow<SessionEvent> = _sessionEvents.asSharedFlow()

    /**
     * Session expiring state
     *
     * Used to show a loading overlay while session is being verified/refreshed/expired.
     * This provides visual feedback to the user instead of an abrupt transition.
     *
     * **Flow:**
     * 1. TokenAuthenticator detects 401 → sets isSessionExpiring = true
     * 2. User sees loading overlay ("Verificando sesión...")
     * 3. Token refresh completes (success or failure)
     * 4. If failure → SessionEvent.Expired emitted → navigate to Login
     * 5. isSessionExpiring reset to false after navigation
     */
    private val _isSessionExpiring = MutableStateFlow(false)
    val isSessionExpiring: StateFlow<Boolean> = _isSessionExpiring.asStateFlow()

    /**
     * Notify that session verification is in progress
     *
     * Called by TokenAuthenticator when 401 is detected, BEFORE attempting token refresh.
     * This shows a loading overlay to the user so they know something is happening.
     */
    fun notifySessionVerifying() {
        Timber.d("🔄 [SessionManager] Session verification in progress...")
        _isSessionExpiring.value = true
    }

    /**
     * Reset session expiring state
     *
     * Called after navigation to Login completes to hide the loading overlay.
     */
    fun resetSessionExpiringState() {
        _isSessionExpiring.value = false
    }

    /**
     * Notify that the session has expired
     *
     * Called by TokenAuthenticator when:
     * - Refresh token is invalid/expired
     * - Refresh token is missing
     * - Refresh token API call fails
     *
     * This will trigger navigation to Login screen in AppNavigation.
     *
     * **Thread-safe**: Can be called from TokenAuthenticator's background thread
     */
    fun notifySessionExpired() {
        Timber.w("🚪 [SessionManager] Session expired - emitting event")
        _sessionEvents.tryEmit(SessionEvent.Expired)
    }

    /**
     * Notify that the terminal has been deactivated
     *
     * Called by HeartbeatWorker when backend reports terminal status = RETIRED.
     * This is a security measure to remotely disable stolen or unauthorized terminals.
     *
     * This will trigger navigation to Activation screen and clear ALL data (including venueId).
     */
    fun notifyTerminalDeactivated() {
        Timber.e("🔐 [SessionManager] Terminal deactivated remotely - emitting event")
        _sessionEvents.tryEmit(SessionEvent.TerminalDeactivated)
    }
}

/**
 * Session Events
 *
 * Sealed class defining all possible session-related events
 */
sealed class SessionEvent {
    /**
     * Session expired - refresh token invalid/missing
     * Action: Navigate to Login screen
     */
    data object Expired : SessionEvent()

    /**
     * Terminal deactivated remotely by admin
     * Action: Navigate to Activation screen, clear ALL data
     */
    data object TerminalDeactivated : SessionEvent()
}

package com.jaac.avoqado_tpv.core.data.manager

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lock Screen Manager - Terminal Remote Lock State Management
 *
 * **WHY**: Provides centralized state management for remote terminal locking.
 * When an admin sends a LOCK command from the dashboard, this manager
 * updates state that triggers a full-screen blocking overlay.
 *
 * **Design Decision**: Use StateFlow + SecureStorage because:
 * - Lock state needs to survive screen rotations (StateFlow)
 * - State MUST persist across app restarts (SecureStorage) ← CRITICAL FIX 2025-12-01
 * - New collectors should see current lock state immediately
 * - UI needs to continuously observe lock state
 *
 * **State Persistence (2025-12-01 Fix)**:
 * - On app restart, state is restored from SecureStorage
 * - Dashboard sends LOCK → state persists even if user force-closes app
 * - Without persistence, restarting app would bypass lock (security risk!)
 *
 * **Security**: When locked, the terminal:
 * - Shows full-screen overlay blocking all interactions
 * - Cannot process payments
 * - Cannot access any features
 * - Can only be unlocked via remote UNLOCK command
 *
 * **Use Cases**:
 * - Stolen device: Admin locks immediately from dashboard
 * - Employee termination: Lock terminal until collected
 * - Security breach: Temporary lockdown during investigation
 *
 * @see CommandExecutor.executeLock Where lock command is executed
 * @see LockScreenOverlay UI overlay shown when locked
 */
@Singleton
class LockScreenManager @Inject constructor(
    private val secureStorage: SecureStorage
) {

    /**
     * Whether the terminal is currently locked
     * When true, a full-screen overlay blocks all interactions
     * Initialized from SecureStorage to persist across app restarts
     */
    private val _isLocked = MutableStateFlow(secureStorage.getIsLocked())
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    /**
     * Reason for the lock (e.g., "Security breach", "Device reported stolen")
     */
    private val _lockReason = MutableStateFlow<String?>(secureStorage.getLockReason())
    val lockReason: StateFlow<String?> = _lockReason.asStateFlow()

    /**
     * Custom message to display on lock screen (e.g., "Contact support at...")
     */
    private val _lockMessage = MutableStateFlow<String?>(secureStorage.getLockMessage())
    val lockMessage: StateFlow<String?> = _lockMessage.asStateFlow()

    /**
     * Who locked the terminal (user ID or name)
     */
    private val _lockedBy = MutableStateFlow<String?>(secureStorage.getLockedBy())
    val lockedBy: StateFlow<String?> = _lockedBy.asStateFlow()

    init {
        // Log restored state on startup
        if (_isLocked.value) {
            Timber.w("🔒 [LOCK] State restored from storage - Locked: reason=${_lockReason.value}, by=${_lockedBy.value}")
        }
    }

    /**
     * Lock the terminal remotely
     *
     * State is persisted to SecureStorage to survive app restarts.
     * This is CRITICAL for security - user cannot bypass lock by restarting app.
     *
     * @param reason Why the terminal was locked
     * @param message Custom message to display
     * @param lockedBy Who initiated the lock
     */
    fun lock(reason: String?, message: String?, lockedBy: String? = null) {
        _lockReason.value = reason
        _lockMessage.value = message
        _lockedBy.value = lockedBy
        _isLocked.value = true

        // Persist to survive app restart (SECURITY CRITICAL)
        secureStorage.saveLockState(
            isLocked = true,
            reason = reason,
            message = message,
            lockedBy = lockedBy
        )

        Timber.w("🔒 [LOCK] Terminal LOCKED - Reason: $reason, By: $lockedBy (persisted)")
    }

    /**
     * Unlock the terminal remotely
     * Clears all lock state and dismisses the overlay
     *
     * State is persisted to SecureStorage to survive app restarts.
     */
    fun unlock() {
        Timber.i("🔓 [UNLOCK] Terminal UNLOCKED (persisted)")

        _isLocked.value = false
        _lockReason.value = null
        _lockMessage.value = null
        _lockedBy.value = null

        // Persist to survive app restart
        secureStorage.saveLockState(
            isLocked = false,
            reason = null,
            message = null,
            lockedBy = null
        )
    }

    /**
     * Check if terminal is currently locked
     * Useful for guards before processing payments
     */
    fun isCurrentlyLocked(): Boolean = _isLocked.value
}

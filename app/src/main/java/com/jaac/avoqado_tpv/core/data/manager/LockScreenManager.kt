package com.jaac.avoqado_tpv.core.data.manager

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
 * **Design Decision**: Use StateFlow (not SharedFlow) because:
 * - Lock state is PERSISTENT (needs to survive screen rotations)
 * - New collectors should see current lock state immediately
 * - UI needs to continuously observe lock state
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
class LockScreenManager @Inject constructor() {

    /**
     * Whether the terminal is currently locked
     * When true, a full-screen overlay blocks all interactions
     */
    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    /**
     * Reason for the lock (e.g., "Security breach", "Device reported stolen")
     */
    private val _lockReason = MutableStateFlow<String?>(null)
    val lockReason: StateFlow<String?> = _lockReason.asStateFlow()

    /**
     * Custom message to display on lock screen (e.g., "Contact support at...")
     */
    private val _lockMessage = MutableStateFlow<String?>(null)
    val lockMessage: StateFlow<String?> = _lockMessage.asStateFlow()

    /**
     * Who locked the terminal (user ID or name)
     */
    private val _lockedBy = MutableStateFlow<String?>(null)
    val lockedBy: StateFlow<String?> = _lockedBy.asStateFlow()

    /**
     * Lock the terminal remotely
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
        Timber.w("🔒 [LOCK] Terminal LOCKED - Reason: $reason, By: $lockedBy")
    }

    /**
     * Unlock the terminal remotely
     * Clears all lock state and dismisses the overlay
     */
    fun unlock() {
        Timber.i("🔓 [UNLOCK] Terminal UNLOCKED")
        _isLocked.value = false
        _lockReason.value = null
        _lockMessage.value = null
        _lockedBy.value = null
    }

    /**
     * Check if terminal is currently locked
     * Useful for guards before processing payments
     */
    fun isCurrentlyLocked(): Boolean = _isLocked.value
}

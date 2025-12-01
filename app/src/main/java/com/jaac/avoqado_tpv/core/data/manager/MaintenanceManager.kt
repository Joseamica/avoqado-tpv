package com.jaac.avoqado_tpv.core.data.manager

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maintenance Manager - Terminal Maintenance Mode State Management
 *
 * **WHY**: Provides centralized state management for maintenance mode.
 * Unlike LOCK (security/emergency), maintenance mode is for planned
 * downtime like software updates, hardware checks, or configuration changes.
 *
 * **Difference from Lock**:
 * - LOCK: Emergency/security - NO user actions allowed, admin unlock only
 * - MAINTENANCE: Planned downtime - Staff can exit locally, no payments
 *
 * **Design Decision**: Use StateFlow because:
 * - Maintenance state needs to survive screen rotations
 * - New collectors should see current state immediately
 * - Multiple UI components may observe this state
 *
 * **When in Maintenance Mode**:
 * - Shows maintenance overlay with amber/warning styling
 * - Cannot process payments
 * - Staff can exit maintenance locally (button on overlay)
 * - Admin can also exit remotely via EXIT_MAINTENANCE command
 *
 * **Use Cases**:
 * - Software updates: Put terminal in maintenance before update
 * - Hardware maintenance: Temporarily disable for cleaning/repairs
 * - Configuration changes: Prevent payments during config sync
 * - End of shift: Maintenance mode until next shift starts
 *
 * @see CommandExecutor.executeMaintenanceMode Where maintenance is entered
 * @see MaintenanceOverlay UI overlay shown during maintenance
 */
@Singleton
class MaintenanceManager @Inject constructor() {

    /**
     * Whether the terminal is currently in maintenance mode
     */
    private val _isInMaintenance = MutableStateFlow(false)
    val isInMaintenance: StateFlow<Boolean> = _isInMaintenance.asStateFlow()

    /**
     * Reason for entering maintenance (optional)
     */
    private val _maintenanceReason = MutableStateFlow<String?>(null)
    val maintenanceReason: StateFlow<String?> = _maintenanceReason.asStateFlow()

    /**
     * Who put the terminal in maintenance mode
     */
    private val _initiatedBy = MutableStateFlow<String?>(null)
    val initiatedBy: StateFlow<String?> = _initiatedBy.asStateFlow()

    /**
     * Enter maintenance mode
     *
     * @param reason Why maintenance mode was entered
     * @param initiatedBy Who initiated (admin name or "system")
     */
    fun enterMaintenance(reason: String? = null, initiatedBy: String? = null) {
        _maintenanceReason.value = reason
        _initiatedBy.value = initiatedBy
        _isInMaintenance.value = true
        Timber.w("🛠️ [MAINTENANCE] Entering maintenance mode - Reason: $reason, By: $initiatedBy")
    }

    /**
     * Exit maintenance mode
     * Can be called locally by staff or remotely by admin
     */
    fun exitMaintenance() {
        Timber.i("✅ [MAINTENANCE] Exiting maintenance mode")
        _isInMaintenance.value = false
        _maintenanceReason.value = null
        _initiatedBy.value = null
    }

    /**
     * Check if terminal is currently in maintenance mode
     * Useful for guards before processing payments
     */
    fun isCurrentlyInMaintenance(): Boolean = _isInMaintenance.value
}

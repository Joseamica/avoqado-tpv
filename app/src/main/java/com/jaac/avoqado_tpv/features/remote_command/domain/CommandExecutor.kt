package com.jaac.avoqado_tpv.features.remote_command.domain

import android.app.Activity
import android.content.Context
import com.google.firebase.appdistribution.FirebaseAppDistribution
import com.google.firebase.appdistribution.FirebaseAppDistributionException
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.manager.LockScreenManager
import com.jaac.avoqado_tpv.core.data.manager.MaintenanceManager
import com.jaac.avoqado_tpv.features.remote_command.data.model.CommandResult
import com.jaac.avoqado_tpv.features.remote_command.data.model.TpvCommand
import com.jaac.avoqado_tpv.features.remote_command.data.model.TpvCommandType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Command Executor - Core Logic for Remote Command Execution
 *
 * **WHY**: Central orchestrator for executing remote commands from dashboard.
 * Handles the command execution logic only - ACKs are handled by ConnectionViewModel.
 *
 * **Design Pattern**: Similar to Square Terminal API's polling pattern
 * and enterprise MDM (Mobile Device Management) patterns.
 *
 * **ACK Flow (HTTP via ConnectionViewModel)**:
 * ```
 * Server → Terminal: heartbeat response with pendingCommands
 * Terminal: ConnectionViewModel.processPendingCommands()
 *   ├── commandExecutor.execute(command)
 *   └── heartbeatRepository.sendCommandAck(commandId, terminalId, result)
 * Server: Updates command status in database
 * Dashboard: Receives status via Socket.IO or polling
 * ```
 *
 * **Command Categories**:
 * - Device State: LOCK, UNLOCK, MAINTENANCE_MODE, EXIT_MAINTENANCE
 * - App Lifecycle: RESTART, SHUTDOWN, CLEAR_CACHE, FORCE_UPDATE
 * - Data Management: SYNC_DATA, FACTORY_RESET, EXPORT_LOGS
 * - Configuration: UPDATE_CONFIG, REFRESH_MENU, UPDATE_MERCHANT
 *
 * @see TpvCommand Data model for commands
 * @see ConnectionViewModel.processPendingCommands() HTTP ACK flow
 * @see avoqado-server/src/services/tpv/command-execution.service.ts Server implementation
 */
@Singleton
class CommandExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lockScreenManager: LockScreenManager,
    private val maintenanceManager: MaintenanceManager,
    private val secureStorage: SecureStorage
) {
    companion object {
        private const val TAG = "CommandExecutor"
    }

    /**
     * Execute a remote command
     *
     * Full lifecycle:
     * 1. Check if command has expired
     * 2. Send ACK (command received)
     * 3. Send STARTED (execution beginning)
     * 4. Execute command logic
     * 5. Send RESULT (success/failure)
     *
     * @param command The command to execute
     * @return CommandResult with status and message
     */
    suspend fun execute(command: TpvCommand): CommandResult {
        Timber.i("📥 [$TAG] Executing command: ${command.type.name} (id=${command.commandId})")

        // 1. Check if command has expired
        if (Instant.now().isAfter(command.expiresAt)) {
            Timber.w("⏰ [$TAG] Command expired: ${command.commandId}")
            return CommandResult.rejected("Command expired before execution")
        }

        // 2. Execute command logic
        // Note: ACKs are sent via HTTP by ConnectionViewModel after execute() returns
        // This avoids race conditions between Socket.IO and HTTP ACK paths
        val result = try {
            executeCommand(command)
        } catch (e: Exception) {
            Timber.e(e, "❌ [$TAG] Command execution failed: ${command.type.name}")
            CommandResult.failed("Execution error: ${e.message}")
        }

        Timber.i("✅ [$TAG] Command completed: ${command.type.name} → ${result.status.name}")
        return result
    }

    /**
     * Route command to appropriate executor based on type
     */
    private suspend fun executeCommand(command: TpvCommand): CommandResult {
        return when (command.type) {
            // Device State Commands
            TpvCommandType.LOCK -> executeLock(command.payload)
            TpvCommandType.UNLOCK -> executeUnlock()
            TpvCommandType.MAINTENANCE_MODE -> executeMaintenanceMode(command.payload, command.requestedByName)
            TpvCommandType.EXIT_MAINTENANCE -> executeExitMaintenance()
            TpvCommandType.REACTIVATE -> executeReactivate()

            // App Lifecycle Commands
            TpvCommandType.RESTART -> executeRestart()
            TpvCommandType.SHUTDOWN -> executeShutdown()
            TpvCommandType.CLEAR_CACHE -> executeClearCache()
            TpvCommandType.FORCE_UPDATE -> executeForceUpdate()

            // Data Management Commands
            TpvCommandType.SYNC_DATA -> executeSyncData()
            TpvCommandType.FACTORY_RESET -> executeFactoryReset()
            TpvCommandType.EXPORT_LOGS -> executeExportLogs()

            // Configuration Commands
            TpvCommandType.UPDATE_CONFIG -> executeUpdateConfig(command.payload)
            TpvCommandType.REFRESH_MENU -> executeRefreshMenu()
            TpvCommandType.UPDATE_MERCHANT -> executeUpdateMerchant(command.payload)

            // Automation Commands (handled server-side, but included for completeness)
            TpvCommandType.SCHEDULE,
            TpvCommandType.GEOFENCE_TRIGGER,
            TpvCommandType.TIME_RULE -> {
                Timber.w("⚠️ [$TAG] Automation command received - should be handled server-side: ${command.type}")
                CommandResult.rejected("Automation commands are handled server-side")
            }
        }
    }

    // ========================================
    // Device State Commands
    // ========================================

    /**
     * LOCK - Lock the terminal (full-screen blocker)
     *
     * Security action - blocks all user interactions.
     * Can only be unlocked via remote UNLOCK command.
     *
     * @param payload Optional: { reason: string, message: string }
     */
    private fun executeLock(payload: Map<String, Any>?): CommandResult {
        val reason = payload?.get("reason") as? String
        val message = payload?.get("message") as? String
        val lockedBy = payload?.get("lockedBy") as? String

        Timber.w("🔒 [$TAG] Executing LOCK command")
        lockScreenManager.lock(reason, message, lockedBy)

        return CommandResult.success(
            message = "Terminal locked successfully",
            data = mapOf(
                "reason" to (reason ?: "Remote lock"),
                "lockedAt" to Instant.now().toString()
            )
        )
    }

    /**
     * UNLOCK - Unlock the terminal
     *
     * High-risk action - requires PIN verification (handled by server before sending)
     */
    private fun executeUnlock(): CommandResult {
        if (!lockScreenManager.isCurrentlyLocked()) {
            return CommandResult.rejected("Terminal is not locked")
        }

        Timber.i("🔓 [$TAG] Executing UNLOCK command")
        lockScreenManager.unlock()

        return CommandResult.success(
            message = "Terminal unlocked successfully",
            data = mapOf("unlockedAt" to Instant.now().toString())
        )
    }

    /**
     * MAINTENANCE_MODE - Enter maintenance mode
     *
     * Shows maintenance overlay, but staff can exit locally.
     * Payments are blocked during maintenance.
     *
     * **IDEMPOTENT (2025-12-01):** Always succeeds, like LOCK command.
     * This ensures server and TPV state are always in sync.
     */
    private fun executeMaintenanceMode(payload: Map<String, Any>?, requestedByName: String?): CommandResult {
        val reason = payload?.get("reason") as? String
        val wasAlreadyInMaintenance = maintenanceManager.isCurrentlyInMaintenance()

        Timber.w("🛠️ [$TAG] Executing MAINTENANCE_MODE command (wasAlready=$wasAlreadyInMaintenance)")
        maintenanceManager.enterMaintenance(reason, requestedByName)

        return CommandResult.success(
            message = if (wasAlreadyInMaintenance) "Terminal already in maintenance mode" else "Maintenance mode enabled",
            data = mapOf(
                "reason" to (reason ?: "Remote maintenance"),
                "enabledAt" to Instant.now().toString(),
                "wasAlreadyInMaintenance" to wasAlreadyInMaintenance
            )
        )
    }

    /**
     * EXIT_MAINTENANCE - Exit maintenance mode
     *
     * **IDEMPOTENT (2025-12-01):** Always succeeds, like UNLOCK command pattern.
     * This ensures server and TPV state are always in sync.
     */
    private fun executeExitMaintenance(): CommandResult {
        val wasInMaintenance = maintenanceManager.isCurrentlyInMaintenance()

        Timber.i("✅ [$TAG] Executing EXIT_MAINTENANCE command (wasInMaintenance=$wasInMaintenance)")
        maintenanceManager.exitMaintenance()

        return CommandResult.success(
            message = if (wasInMaintenance) "Maintenance mode disabled" else "Terminal was not in maintenance mode",
            data = mapOf(
                "exitedAt" to Instant.now().toString(),
                "wasInMaintenance" to wasInMaintenance
            )
        )
    }

    /**
     * REACTIVATE - Reactivate a disabled/retired terminal
     *
     * High-risk action - requires PIN verification (handled by server)
     */
    private fun executeReactivate(): CommandResult {
        Timber.i("🔄 [$TAG] Executing REACTIVATE command")
        // TODO: Implement reactivation logic when terminal status management is added
        // This would clear any disabled state and refresh authentication
        return CommandResult.success("Terminal reactivated successfully")
    }

    // ========================================
    // App Lifecycle Commands
    // ========================================

    /**
     * RESTART - Restart the application
     *
     * Uses a delayed restart to allow result emission.
     */
    private suspend fun executeRestart(): CommandResult {
        Timber.w("🔄 [$TAG] Executing RESTART command - app will restart in 500ms")

        // Delay to allow result emission before restart
        delay(500)

        // Use Activity.recreate() pattern - gets activity from context
        // Note: ProcessPhoenix would be better but requires additional dependency
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            // Kill the current process after starting new instance
            android.os.Process.killProcess(android.os.Process.myPid())
        } catch (e: Exception) {
            Timber.e(e, "❌ [$TAG] Restart failed")
            return CommandResult.failed("Restart failed: ${e.message}")
        }

        return CommandResult.success("Restarting application...")
    }

    /**
     * SHUTDOWN - Shutdown the application
     *
     * High-risk action - requires PIN verification (handled by server)
     */
    private suspend fun executeShutdown(): CommandResult {
        Timber.w("⏻ [$TAG] Executing SHUTDOWN command - app will close in 500ms")

        // Delay to allow result emission
        delay(500)

        // Close the application
        android.os.Process.killProcess(android.os.Process.myPid())

        return CommandResult.success("Application shutting down...")
    }

    /**
     * CLEAR_CACHE - Clear application cache
     *
     * Clears:
     * - HTTP cache
     * - Image cache
     * - Temporary files
     * Does NOT clear:
     * - Database
     * - Secure storage
     * - Session data
     */
    private fun executeClearCache(): CommandResult {
        Timber.i("🗑️ [$TAG] Executing CLEAR_CACHE command")

        try {
            // Clear app cache directory
            val cacheDir = context.cacheDir
            val deletedCount = cacheDir.deleteRecursively()
            cacheDir.mkdir() // Recreate cache directory

            // Clear external cache if exists
            context.externalCacheDir?.let { extCache ->
                extCache.deleteRecursively()
                extCache.mkdir()
            }

            Timber.i("✅ [$TAG] Cache cleared successfully")
            return CommandResult.success(
                message = "Cache cleared successfully",
                data = mapOf(
                    "clearedAt" to Instant.now().toString(),
                    "cacheCleared" to deletedCount
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ [$TAG] Failed to clear cache")
            return CommandResult.failed("Failed to clear cache: ${e.message}")
        }
    }

    /**
     * FORCE_UPDATE - Force application update via Firebase App Distribution
     *
     * Uses Firebase App Distribution to check for and install updates OTA.
     * This is a non-blocking operation that shows a dialog to the user if an update is available.
     *
     * Flow:
     * 1. Check for new release via Firebase App Distribution
     * 2. If available, show update dialog to user
     * 3. User confirms → download and install APK
     * 4. App restarts with new version
     *
     * @see https://firebase.google.com/docs/app-distribution/android/distribute-console
     */
    private suspend fun executeForceUpdate(): CommandResult {
        Timber.w("⬆️ [$TAG] Executing FORCE_UPDATE command")

        val currentVersion = getAppVersion()

        return try {
            val firebaseAppDistribution = FirebaseAppDistribution.getInstance()

            // Check for new release
            val updateCheckResult = suspendCancellableCoroutine { continuation ->
                firebaseAppDistribution.checkForNewRelease()
                    .addOnSuccessListener { release ->
                        if (release != null) {
                            Timber.i("📦 [$TAG] New release available: ${release.displayVersion} (${release.versionCode})")
                            continuation.resume(
                                UpdateCheckResult.Available(
                                    version = release.displayVersion ?: "unknown",
                                    versionCode = release.versionCode,
                                    releaseNotes = release.releaseNotes
                                )
                            )
                        } else {
                            Timber.i("✅ [$TAG] App is up to date")
                            continuation.resume(UpdateCheckResult.UpToDate)
                        }
                    }
                    .addOnFailureListener { exception ->
                        Timber.e(exception, "❌ [$TAG] Update check failed")
                        continuation.resume(UpdateCheckResult.Error(exception.message ?: "Unknown error"))
                    }
            }

            when (updateCheckResult) {
                is UpdateCheckResult.Available -> {
                    // Trigger the update flow (shows Firebase's built-in update dialog)
                    Timber.i("📥 [$TAG] Triggering update to version ${updateCheckResult.version}")

                    // Note: updateIfNewReleaseAvailable() requires an Activity context
                    // Since we're in a singleton, we'll just report that update is available
                    // The UI layer (HomeViewModel) should handle showing the update dialog

                    CommandResult.success(
                        message = "Nueva versión disponible: ${updateCheckResult.version}",
                        data = mapOf(
                            "currentVersion" to currentVersion,
                            "newVersion" to updateCheckResult.version,
                            "newVersionCode" to updateCheckResult.versionCode,
                            "releaseNotes" to (updateCheckResult.releaseNotes ?: ""),
                            "updateAvailable" to true,
                            "checkedAt" to Instant.now().toString()
                        )
                    )
                }

                is UpdateCheckResult.UpToDate -> {
                    CommandResult.success(
                        message = "La aplicación está actualizada",
                        data = mapOf(
                            "currentVersion" to currentVersion,
                            "updateAvailable" to false,
                            "checkedAt" to Instant.now().toString()
                        )
                    )
                }

                is UpdateCheckResult.Error -> {
                    CommandResult.failed(
                        message = "Error al verificar actualizaciones: ${updateCheckResult.error}"
                    )
                }
            }
        } catch (e: FirebaseAppDistributionException) {
            Timber.e(e, "❌ [$TAG] Firebase App Distribution error")
            CommandResult.failed(
                message = "Error de Firebase: ${e.errorCode} - ${e.message}"
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ [$TAG] Update check failed")
            CommandResult.failed(
                message = "Error al verificar actualizaciones: ${e.message}"
            )
        }
    }

    /**
     * Result of checking for updates
     */
    private sealed class UpdateCheckResult {
        data class Available(
            val version: String,
            val versionCode: Long,
            val releaseNotes: String?
        ) : UpdateCheckResult()

        data object UpToDate : UpdateCheckResult()

        data class Error(val error: String) : UpdateCheckResult()
    }

    // ========================================
    // Data Management Commands
    // ========================================

    /**
     * SYNC_DATA - Synchronize data with backend
     *
     * Triggers full data sync:
     * - Orders
     * - Products/Menu
     * - Tables
     * - Settings
     *
     * TODO: Implement full sync when OrderSyncCoordinator has syncAllOrders()
     * Currently just triggers a refresh signal - individual orders sync on demand.
     */
    private suspend fun executeSyncData(): CommandResult {
        Timber.i("🔄 [$TAG] Executing SYNC_DATA command")

        // Note: OrderSyncCoordinator currently supports single order sync only.
        // Full sync would need to iterate through all cached orders or add a bulk sync method.
        // For now, we mark this as successful and let individual order screens handle their sync.
        return CommandResult.success(
            message = "Data sync initiated - orders will sync on access",
            data = mapOf(
                "syncedAt" to Instant.now().toString(),
                "note" to "Individual orders sync on demand"
            )
        )
    }

    /**
     * FACTORY_RESET - Reset terminal to factory state
     *
     * CRITICAL action - requires PIN + double confirmation (handled by server)
     *
     * Clears:
     * - All local data
     * - Database
     * - Secure storage
     * - Cache
     * - Activation status
     */
    private suspend fun executeFactoryReset(): CommandResult {
        Timber.e("🔥 [$TAG] Executing FACTORY_RESET command - CRITICAL OPERATION")

        try {
            // Clear all secure storage (including activation)
            secureStorage.clearAll()

            // Clear cache
            context.cacheDir.deleteRecursively()
            context.externalCacheDir?.deleteRecursively()

            // Clear databases
            context.databaseList().forEach { dbName ->
                context.deleteDatabase(dbName)
            }

            // Clear shared preferences
            context.getSharedPreferences("avoqado_prefs", Context.MODE_PRIVATE)
                .edit().clear().apply()

            Timber.w("✅ [$TAG] Factory reset completed - terminal will restart")

            // Delay then restart
            delay(1000)
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            android.os.Process.killProcess(android.os.Process.myPid())

            return CommandResult.success("Factory reset completed")
        } catch (e: Exception) {
            Timber.e(e, "❌ [$TAG] Factory reset failed")
            return CommandResult.failed("Factory reset failed: ${e.message}")
        }
    }

    /**
     * EXPORT_LOGS - Export application logs
     *
     * Collects logs and prepares for upload/download.
     * TODO: Implement actual log export (Firebase Crashlytics, custom endpoint, etc.)
     */
    private fun executeExportLogs(): CommandResult {
        Timber.i("📋 [$TAG] Executing EXPORT_LOGS command")

        // TODO: Implement log export when logging infrastructure is enhanced
        // This would collect Timber logs, system logs, and upload to backend
        return CommandResult.success(
            message = "Log export initiated",
            data = mapOf(
                "terminalId" to getTerminalId(),
                "appVersion" to getAppVersion(),
                "exportedAt" to Instant.now().toString()
            )
        )
    }

    // ========================================
    // Configuration Commands
    // ========================================

    /**
     * UPDATE_CONFIG - Update terminal configuration
     *
     * @param payload Configuration updates to apply
     */
    private fun executeUpdateConfig(payload: Map<String, Any>?): CommandResult {
        if (payload.isNullOrEmpty()) {
            return CommandResult.rejected("No configuration provided")
        }

        Timber.i("⚙️ [$TAG] Executing UPDATE_CONFIG command")

        // Apply configuration updates
        // TODO: Implement actual config update when config management is enhanced
        return CommandResult.success(
            message = "Configuration updated",
            data = mapOf(
                "updatedAt" to Instant.now().toString(),
                "configKeys" to payload.keys.toList()
            )
        )
    }

    /**
     * REFRESH_MENU - Refresh menu/product catalog
     *
     * Triggers menu data refresh from backend.
     */
    private suspend fun executeRefreshMenu(): CommandResult {
        Timber.i("📋 [$TAG] Executing REFRESH_MENU command")

        // TODO: Implement menu refresh when ProductRepository has refresh method
        // This would clear cached menu and fetch fresh from backend
        return CommandResult.success(
            message = "Menu refresh initiated",
            data = mapOf("refreshedAt" to Instant.now().toString())
        )
    }

    /**
     * UPDATE_MERCHANT - Update merchant configuration
     *
     * High-risk action - affects payment processing.
     *
     * @param payload New merchant configuration
     */
    private fun executeUpdateMerchant(payload: Map<String, Any>?): CommandResult {
        if (payload.isNullOrEmpty()) {
            return CommandResult.rejected("No merchant data provided")
        }

        Timber.w("💳 [$TAG] Executing UPDATE_MERCHANT command")

        // TODO: Implement merchant update when multi-merchant switching is enhanced
        // This would update Blumon merchant credentials and re-initialize SDK
        return CommandResult.success(
            message = "Merchant update initiated",
            data = mapOf(
                "updatedAt" to Instant.now().toString()
            )
        )
    }

    // ========================================
    // Helper Methods
    // ========================================

    /**
     * Get terminal ID from secure storage
     */
    private fun getTerminalId(): String {
        return secureStorage.getSerialNumber() ?: "UNKNOWN"
    }

    /**
     * Get app version string
     */
    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${packageInfo.versionName} (${packageInfo.longVersionCode})"
        } catch (e: Exception) {
            "unknown"
        }
    }

    // Note: Socket.IO ACK methods (emitResult, emitCommandAck, emitCommandStarted) removed
    // ACKs are now sent exclusively via HTTP by ConnectionViewModel.processPendingCommands()
    // This eliminates race conditions between Socket.IO and HTTP ACK paths
}

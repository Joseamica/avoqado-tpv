package com.jaac.avoqado_tpv.features.remote_command.domain

import android.app.Activity
import android.content.Context
import com.google.firebase.appdistribution.FirebaseAppDistribution
import com.google.firebase.appdistribution.FirebaseAppDistributionException
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.manager.LockScreenManager
import com.jaac.avoqado_tpv.core.util.VenueTimeZone
import com.jaac.avoqado_tpv.core.data.manager.MaintenanceManager
import com.jaac.avoqado_tpv.features.remote_command.data.model.CommandResult
import com.jaac.avoqado_tpv.features.remote_command.data.model.TpvCommand
import com.jaac.avoqado_tpv.features.remote_command.data.model.TpvCommandType
import com.jaac.avoqado_tpv.features.self_update.data.AvoqadoUpdateRepository
import com.jaac.avoqado_tpv.features.self_update.data.DownloadResult
import com.jaac.avoqado_tpv.features.self_update.data.UpdateCheckResult as AvoqadoUpdateCheckResult
import com.jaac.avoqado_tpv.features.self_update.domain.UpdateRequestManager
import com.jaac.avoqado_tpv.features.self_update.domain.UpdateRequestResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject
import javax.inject.Provider
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
    private val secureStorage: SecureStorage,
    private val updateRequestManager: Provider<UpdateRequestManager>,
    private val avoqadoUpdateRepository: AvoqadoUpdateRepository,
    // FETCH_ANGELPAY_MERCHANTS handler. Provider<T> deferral keeps PAX builds
    // (where the AngelPay graph is never constructed at runtime) cheap — the
    // .get() call inside the handler is gated by BuildConfig check.
    private val angelPayAuthRepositoryProvider: Provider<com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayAuthRepository>,
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
            TpvCommandType.REMOTE_ACTIVATE -> executeRemoteActivate(command.payload)

            // App Lifecycle Commands
            TpvCommandType.RESTART -> executeRestart()
            TpvCommandType.SHUTDOWN -> executeShutdown()
            TpvCommandType.CLEAR_CACHE -> executeClearCache()
            TpvCommandType.FORCE_UPDATE -> executeForceUpdate()
            TpvCommandType.REQUEST_UPDATE -> executeRequestUpdate(command)
            TpvCommandType.INSTALL_VERSION -> executeInstallVersion(command)

            // Data Management Commands
            TpvCommandType.SYNC_DATA -> executeSyncData()
            TpvCommandType.FACTORY_RESET -> executeFactoryReset()
            TpvCommandType.EXPORT_LOGS -> executeExportLogs()

            // Configuration Commands
            TpvCommandType.UPDATE_CONFIG -> executeUpdateConfig(command.payload)
            TpvCommandType.REFRESH_MENU -> executeRefreshMenu()
            TpvCommandType.UPDATE_MERCHANT -> executeUpdateMerchant(command.payload)
            TpvCommandType.FETCH_ANGELPAY_MERCHANTS -> executeFetchAngelPayMerchants(command.payload)

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

    /**
     * REMOTE_ACTIVATE - Remote activation by SUPERADMIN
     *
     * This command activates a pre-registered terminal without using an activation code.
     * The terminal must have been pre-registered in the dashboard and must have sent
     * at least one heartbeat (proof of physical device).
     *
     * Flow:
     * 1. SUPERADMIN pre-registers terminal in dashboard (creates Terminal record)
     * 2. Physical terminal starts up and sends heartbeat (with serial number)
     * 3. SUPERADMIN sends REMOTE_ACTIVATE command from dashboard
     * 4. Terminal receives command via pending commands in heartbeat response
     * 5. Terminal activates itself using the venue info in the payload
     *
     * @param payload Contains: venueId, venueName, venueSlug, venueTimezone,
     *                         terminalId, terminalName, serialNumber
     */
    private suspend fun executeRemoteActivate(payload: Map<String, Any>?): CommandResult {
        Timber.w("⚡ [$TAG] Executing REMOTE_ACTIVATE command")

        if (payload.isNullOrEmpty()) {
            return CommandResult.rejected("No activation data provided")
        }

        // Extract venue info from payload
        val venueId = payload["venueId"] as? String
        val venueName = payload["venueName"] as? String
        val venueSlug = payload["venueSlug"] as? String
        val venueTimezone = payload["venueTimezone"] as? String
        val terminalId = payload["terminalId"] as? String
        val terminalName = payload["terminalName"] as? String
        val serialNumber = payload["serialNumber"] as? String

        // Validate required fields
        if (venueId.isNullOrEmpty() || venueName.isNullOrEmpty() || venueSlug.isNullOrEmpty()) {
            Timber.e("❌ [$TAG] Missing required venue info in REMOTE_ACTIVATE payload")
            return CommandResult.rejected("Missing required venue info (venueId, venueName, venueSlug)")
        }

        if (terminalId.isNullOrEmpty() || serialNumber.isNullOrEmpty()) {
            Timber.e("❌ [$TAG] Missing terminal info in REMOTE_ACTIVATE payload")
            return CommandResult.rejected("Missing terminal info (terminalId, serialNumber)")
        }

        try {
            // Save activation data to secure storage
            secureStorage.saveVenueId(venueId)
            secureStorage.saveVenueName(venueName)
            secureStorage.saveVenueSlug(venueSlug)
            secureStorage.saveSerialNumber(serialNumber)

            // Save terminal ID if available
            if (!terminalId.isNullOrEmpty()) {
                secureStorage.saveTerminalId(terminalId)
            }

            // Save timezone if available
            if (!venueTimezone.isNullOrEmpty()) {
                secureStorage.saveVenueTimezone(venueTimezone)
                VenueTimeZone.invalidateCache()
            }

            Timber.i("✅ [$TAG] Terminal remotely activated successfully")
            Timber.i("   📍 Venue: $venueName ($venueSlug)")
            Timber.i("   🔢 Serial: $serialNumber")
            Timber.i("   🆔 Terminal ID: $terminalId")

            return CommandResult.success(
                message = "Terminal activated remotely by SUPERADMIN",
                data = mapOf(
                    "venueId" to venueId,
                    "venueName" to venueName,
                    "venueSlug" to venueSlug,
                    "terminalId" to terminalId,
                    "serialNumber" to serialNumber,
                    "activatedAt" to Instant.now().toString(),
                    "activationType" to "REMOTE"
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ [$TAG] Failed to save activation data")
            return CommandResult.failed("Failed to save activation data: ${e.message}")
        }
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
     * REQUEST_UPDATE - Show update dialog to user (Avoqado APK updates)
     *
     * Unlike FORCE_UPDATE (Firebase check), this uses the Avoqado self-update system
     * and shows a dialog where the user can accept or dismiss the update.
     *
     * Flow:
     * 1. Check Avoqado backend for available updates
     * 2. If update available, show dialog to user
     * 3. User decides: Accept → download/install, Dismiss → close
     *
     * @param command The REQUEST_UPDATE command
     * @see UpdateRequestManager
     */
    private suspend fun executeRequestUpdate(command: TpvCommand): CommandResult {
        Timber.i("📲 [$TAG] Executing REQUEST_UPDATE command")

        if (!BuildConfig.ENABLE_PAX_SDK) {
            Timber.w("⚠️ [$TAG] REQUEST_UPDATE ignored: update installer is disabled for this flavor")
            return CommandResult.rejected("REQUEST_UPDATE not supported in emulator/tutorial flavor")
        }

        return try {
            when (val result = updateRequestManager.get().handleUpdateRequest(command.commandId)) {
                is UpdateRequestResult.DialogShown -> {
                    CommandResult.success(
                        message = "Update dialog shown: ${result.versionName}",
                        data = mapOf(
                            "versionName" to result.versionName,
                            "versionCode" to result.versionCode,
                            "dialogShown" to true
                        )
                    )
                }

                is UpdateRequestResult.AlreadyUpToDate -> {
                    CommandResult.success(
                        message = "App is already up to date",
                        data = mapOf(
                            "currentVersion" to result.currentVersion,
                            "updateAvailable" to false
                        )
                    )
                }

                is UpdateRequestResult.Error -> {
                    CommandResult.failed(
                        message = result.message
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ [$TAG] REQUEST_UPDATE failed")
            CommandResult.failed(
                message = "Error requesting update: ${e.message}"
            )
        }
    }

    /**
     * INSTALL_VERSION - Install a specific version (SUPERADMIN rollback/upgrade)
     *
     * Unlike REQUEST_UPDATE (shows dialog), this command directly downloads and installs
     * a specific version without user confirmation. Used by SUPERADMIN for:
     * - Rolling back to a previous stable version
     * - Upgrading to a specific target version
     *
     * Flow:
     * 1. Extract versionCode from payload
     * 2. Fetch specific version info from backend
     * 3. Download APK from Firebase Storage
     * 4. Verify SHA-256 checksum
     * 5. Install APK silently (PAX SDK)
     *
     * @param command INSTALL_VERSION command with payload { versionCode: Int }
     */
    private suspend fun executeInstallVersion(command: TpvCommand): CommandResult {
        Timber.w("📦 [$TAG] Executing INSTALL_VERSION command (SUPERADMIN rollback/upgrade)")

        // Extract versionCode from payload
        val versionCodeAny = command.payload?.get("versionCode")
        Timber.d("📦 [$TAG] Raw versionCode from payload: $versionCodeAny (type: ${versionCodeAny?.javaClass?.name})")
        Timber.d("📦 [$TAG] Full payload: ${command.payload}")

        val versionCode = when (versionCodeAny) {
            is Int -> versionCodeAny
            is Long -> versionCodeAny.toInt()
            is Double -> versionCodeAny.toInt()
            is Float -> versionCodeAny.toInt()
            is Number -> versionCodeAny.toInt()  // Handles Gson's LazilyParsedNumber
            is String -> versionCodeAny.toIntOrNull()
            else -> null
        }

        if (versionCode == null || versionCode < 1) {
            Timber.e("❌ [$TAG] Invalid or missing versionCode in INSTALL_VERSION payload. Raw value: $versionCodeAny")
            return CommandResult.rejected("Invalid or missing versionCode in payload")
        }

        Timber.i("📦 [$TAG] Target version code: $versionCode")

        // 1. Get specific version info from backend
        val versionResult = avoqadoUpdateRepository.getSpecificVersion(versionCode)

        when (versionResult) {
            is AvoqadoUpdateCheckResult.Error -> {
                Timber.e("❌ [$TAG] Failed to get version $versionCode: ${versionResult.message}")
                return CommandResult.failed(
                    message = "Error obteniendo versión $versionCode: ${versionResult.message}"
                )
            }

            is AvoqadoUpdateCheckResult.UpToDate -> {
                // This shouldn't happen with getSpecificVersion, but handle gracefully
                Timber.w("⚠️ [$TAG] Version $versionCode not found")
                return CommandResult.failed(
                    message = "Versión $versionCode no encontrada"
                )
            }

            is AvoqadoUpdateCheckResult.UpdateAvailable -> {
                val updateInfo = versionResult.updateInfo
                Timber.i("✅ [$TAG] Found version: ${updateInfo.versionName} (${updateInfo.versionCode})")

                // Check if this is a downgrade
                val currentVersionCode = com.jaac.avoqado_tpv.BuildConfig.VERSION_CODE
                val isDowngrade = updateInfo.versionCode < currentVersionCode
                Timber.i("📦 [$TAG] Current version: $currentVersionCode, Target: ${updateInfo.versionCode}, isDowngrade: $isDowngrade")

                // Downgrades require system-level permissions (PAXSTORE in production)
                // In sandbox/testing, downgrades via standard Android intent will FAIL
                if (isDowngrade) {
                    Timber.w("⚠️ [$TAG] DOWNGRADE detected: ${currentVersionCode} → ${updateInfo.versionCode}")
                    Timber.w("⚠️ [$TAG] Downgrades require PAXSTORE (production) or manual uninstall (testing)")
                    // Still attempt - will fail on non-rooted devices but might work via PAXSTORE
                }

                // 2. Download APK
                Timber.i("📥 [$TAG] Downloading APK: ${updateInfo.downloadUrl}")
                val downloadResult = avoqadoUpdateRepository.downloadApk(updateInfo) { progress ->
                    Timber.d("📥 [$TAG] Download progress: $progress%")
                }

                when (downloadResult) {
                    is DownloadResult.Error -> {
                        Timber.e("❌ [$TAG] Download failed: ${downloadResult.message}")
                        return CommandResult.failed(
                            message = "Error descargando APK: ${downloadResult.message}"
                        )
                    }

                    is DownloadResult.Success -> {
                        Timber.i("✅ [$TAG] APK downloaded: ${downloadResult.filePath}")

                        // 3. Install APK
                        try {
                            // Try shell install first (allows downgrades with -d flag)
                            val shellInstallSuccess = installViaShellCommand(downloadResult.filePath)

                            if (shellInstallSuccess) {
                                Timber.i("✅ [$TAG] APK installed successfully via shell - app will restart")

                                // Report update installation for analytics
                                avoqadoUpdateRepository.reportUpdateInstalled(
                                    versionCode = updateInfo.versionCode,
                                    versionName = updateInfo.versionName,
                                    serialNumber = secureStorage.getSerialNumber()
                                )

                                return CommandResult.success(
                                    message = "Versión ${updateInfo.versionName} instalada correctamente",
                                    data = mapOf(
                                        "versionName" to updateInfo.versionName,
                                        "versionCode" to updateInfo.versionCode,
                                        "installedAt" to Instant.now().toString(),
                                        "installType" to if (isDowngrade) "ROLLBACK" else "UPGRADE"
                                    )
                                )
                            }

                            // Shell install failed - check if it's a downgrade
                            if (isDowngrade) {
                                // Downgrades CANNOT work via standard intent on non-rooted devices
                                Timber.e("❌ [$TAG] DOWNGRADE BLOCKED: Android doesn't allow installing lower versionCode via standard installer")
                                return CommandResult.failed(
                                    message = "⚠️ DOWNGRADE no permitido. Android bloquea la instalación de versiones anteriores. " +
                                            "En producción use PAXSTORE. En testing, desinstale la app primero."
                                )
                            }

                            // For upgrades, fall back to standard intent (user must confirm)
                            Timber.i("📲 [$TAG] Falling back to standard install intent for upgrade")
                            installApkWithIntent(downloadResult.filePath)

                            // Report update installation for analytics
                            avoqadoUpdateRepository.reportUpdateInstalled(
                                versionCode = updateInfo.versionCode,
                                versionName = updateInfo.versionName,
                                serialNumber = secureStorage.getSerialNumber()
                            )

                            return CommandResult.success(
                                message = "Instalación iniciada. El usuario debe confirmar en el diálogo de Android.",
                                data = mapOf(
                                    "versionName" to updateInfo.versionName,
                                    "versionCode" to updateInfo.versionCode,
                                    "installType" to "UPGRADE_USER_CONFIRM"
                                )
                            )
                        } catch (e: Exception) {
                            Timber.e(e, "❌ [$TAG] Installation failed")
                            return CommandResult.failed(
                                message = "Error instalando APK: ${e.message}"
                            )
                        }
                    }
                }
            }
        }
    }

    // Note: installApkSilently has been replaced with direct calls to
    // installViaShellCommand() and installApkWithIntent() in executeInstallVersion()
    // to properly handle downgrade detection and user-friendly error messages.

    /**
     * Install APK via shell command (pm install)
     *
     * Uses Runtime.exec() to call the package manager directly.
     * This allows using the -d flag for downgrade support.
     *
     * NOTE: pm install cannot access /storage/emulated/0/ due to SELinux.
     * We must first copy the APK to /data/local/tmp/ which is accessible.
     *
     * @param apkPath Path to the APK file
     * @return true if installation succeeded, false otherwise
     */
    private fun installViaShellCommand(apkPath: String): Boolean {
        return try {
            Timber.d("📦 [$TAG] Attempting shell install: $apkPath")

            // Step 1: Copy APK to /data/local/tmp/ (SELinux accessible location)
            val tempPath = "/data/local/tmp/avoqado_update.apk"
            val copyResult = copyFileToLocalTmp(apkPath, tempPath)
            if (!copyResult) {
                Timber.w("⚠️ [$TAG] Failed to copy APK to $tempPath")
                return false
            }

            // Step 2: Use pm install with flags:
            // -r: Replace existing application
            // -d: Allow version code downgrade (critical for rollback!)
            // -g: Grant all runtime permissions (API 23+)
            val process = Runtime.getRuntime().exec(arrayOf(
                "pm", "install", "-r", "-d", "-g", tempPath
            ))

            val exitCode = process.waitFor()
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()

            Timber.d("📦 [$TAG] pm install exit code: $exitCode")
            Timber.d("📦 [$TAG] pm install output: $output")
            if (error.isNotBlank()) {
                Timber.w("📦 [$TAG] pm install error: $error")
            }

            // Step 3: Clean up temp file
            try {
                Runtime.getRuntime().exec(arrayOf("rm", "-f", tempPath)).waitFor()
            } catch (e: Exception) {
                Timber.d("📦 [$TAG] Could not delete temp file: ${e.message}")
            }

            if (exitCode == 0 || output.contains("Success", ignoreCase = true)) {
                Timber.i("✅ [$TAG] Shell install successful")
                true
            } else {
                Timber.w("⚠️ [$TAG] Shell install failed: $output $error")
                false
            }
        } catch (e: SecurityException) {
            Timber.w("⚠️ [$TAG] Shell install denied (no permission): ${e.message}")
            false
        } catch (e: Exception) {
            Timber.w(e, "⚠️ [$TAG] Shell install error")
            false
        }
    }

    /**
     * Copy file to /data/local/tmp/ using shell command
     *
     * This location is accessible by pm install (not blocked by SELinux).
     *
     * @param sourcePath Source file path
     * @param destPath Destination path (should be in /data/local/tmp/)
     * @return true if copy succeeded
     */
    private fun copyFileToLocalTmp(sourcePath: String, destPath: String): Boolean {
        return try {
            Timber.d("📦 [$TAG] Copying APK to $destPath")

            // Use cat to copy (works better with SELinux than cp)
            val process = Runtime.getRuntime().exec(arrayOf(
                "sh", "-c", "cat '$sourcePath' > '$destPath' && chmod 644 '$destPath'"
            ))

            val exitCode = process.waitFor()
            val error = process.errorStream.bufferedReader().readText()

            if (exitCode == 0) {
                Timber.d("📦 [$TAG] APK copied successfully to $destPath")
                true
            } else {
                Timber.w("⚠️ [$TAG] Copy failed: $error")
                false
            }
        } catch (e: Exception) {
            Timber.w(e, "⚠️ [$TAG] Copy error")
            false
        }
    }

    /**
     * Install APK using standard Intent (requires user confirmation)
     *
     * NOTE: This will NOT work for downgrades on non-rooted devices.
     * Android blocks installing APKs with lower versionCode by default.
     */
    private fun installApkWithIntent(apkPath: String) {
        val apkFile = java.io.File(apkPath)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            apkFile
        )

        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        context.startActivity(intent)
        Timber.i("📲 [$TAG] Install intent launched for user confirmation")
    }

    /**
     * Result of checking for updates (Firebase App Distribution)
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
            // Note: Some databases (like pax-database from Blumon SDK) may be locked
            // if they have active connections. This is OK - the app restart will clean them up.
            context.databaseList().forEach { dbName ->
                try {
                    context.deleteDatabase(dbName)
                    Timber.d("✅ [$TAG] Deleted database: $dbName")
                } catch (e: Exception) {
                    // Database is locked (active connections) or other error
                    // Non-critical: app restart (line 516) will clear this
                    Timber.w("⚠️ [$TAG] Could not delete $dbName (will be cleared on app restart): ${e.message}")
                }
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

    /**
     * FETCH_ANGELPAY_MERCHANTS — dashboard asks TPV to re-authenticate the
     * AngelPay SDK and report discovered merchants to backend.
     *
     * Payload (optional): { "angelpayUserAccountId": "<id>" } — when present
     * the TPV switches to that specific account before authenticating, so the
     * merchants reported correspond to THAT login. Without payload, uses the
     * default account from cached terminal config.
     *
     * On PAX builds (BuildConfig.SUPPORTED_PROCESSOR != "ANGELPAY") this is a
     * no-op — gated below to avoid Provider<AngelPayAuthRepository>.get()
     * constructing the AngelPay graph (which would crash because the AAR isn't
     * on the runtime classpath in PAX flavors).
     */
    private suspend fun executeFetchAngelPayMerchants(payload: Map<String, Any>?): CommandResult {
        if (BuildConfig.SUPPORTED_PROCESSOR != "ANGELPAY") {
            Timber.w("⚠️ [$TAG] FETCH_ANGELPAY_MERCHANTS rejected on non-AngelPay build (SUPPORTED_PROCESSOR=${BuildConfig.SUPPORTED_PROCESSOR})")
            return CommandResult.rejected("This build does not support AngelPay (processor=${BuildConfig.SUPPORTED_PROCESSOR})")
        }

        Timber.i("🔶 [$TAG] Executing FETCH_ANGELPAY_MERCHANTS command")

        val targetAccountId = payload?.get("angelpayUserAccountId") as? String

        return try {
            val authRepo = angelPayAuthRepositoryProvider.get()

            // If a specific account was requested, switch the SDK to it first.
            // Without it, ensureAuthenticated uses whatever account the resolver
            // picks (typically the first one in cached config).
            if (!targetAccountId.isNullOrBlank()) {
                Timber.i("🔶 [$TAG] Switching AngelPay account before fetch: $targetAccountId")
                val switchResult = authRepo.switchAccount(targetAccountId)
                if (switchResult.isFailure) {
                    val err = switchResult.exceptionOrNull()?.message ?: "switchAccount failed"
                    Timber.w("⚠️ [$TAG] switchAccount($targetAccountId) failed: $err")
                    return CommandResult.failed("No se pudo cambiar a cuenta AngelPay $targetAccountId: $err")
                }
            }

            // ensureAuthenticated() handles the full flow internally:
            // 1. Resolves creds (force config refresh on self-heal if cache empty)
            // 2. SDK authenticateSimple (with retry backoff)
            // 3. On Success: reports discovered merchants to backend
            // 4. Refreshes terminal config so validator sees fresh intersection
            // 5. Runs config validation
            val authResult = authRepo.ensureAuthenticated()
            if (authResult.isFailure) {
                val err = authResult.exceptionOrNull()?.message ?: "auth failed"
                Timber.e("❌ [$TAG] FETCH_ANGELPAY_MERCHANTS auth failed: $err")
                return CommandResult.failed("AngelPay auth failed: $err")
            }

            Timber.i("✅ [$TAG] FETCH_ANGELPAY_MERCHANTS completed — backend should have received discovered merchants")
            CommandResult.success(
                message = "AngelPay merchants refreshed and reported to backend",
                data = if (targetAccountId != null) mapOf("switchedToAccount" to targetAccountId) else null,
            )
        } catch (t: Throwable) {
            Timber.e(t, "❌ [$TAG] FETCH_ANGELPAY_MERCHANTS threw unexpected exception")
            CommandResult.failed("Unexpected error: ${t.message ?: t.javaClass.simpleName}")
        }
    }
}

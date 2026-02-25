package com.jaac.avoqado_tpv.features.self_update.domain

import com.blumonpay.pax.shared.installer.domain.use_case.InstallerAppUseCase
import com.blumonpay.pax.shared.installer.domain.use_case.InstallerParams
import com.jaac.avoqado_tpv.core.data.network.AvoqadoUpdateInfo
import android.content.Context
import com.jaac.avoqado_tpv.features.self_update.data.AvoqadoUpdateRepository
import com.jaac.avoqado_tpv.features.self_update.data.DownloadResult
import com.jaac.avoqado_tpv.features.self_update.data.UpdateCheckResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UpdateRequestManager - Manages remote update request commands
 *
 * Handles the REQUEST_UPDATE remote command from dashboard.
 * Shows a dialog to the user asking if they want to update.
 * User can accept (triggers Avoqado update) or dismiss.
 *
 * Flow:
 * 1. Dashboard sends REQUEST_UPDATE command
 * 2. CommandExecutor calls handleUpdateRequest()
 * 3. Manager checks for Avoqado updates
 * 4. If update available, emits ShowDialog state
 * 5. UI shows dialog to user
 * 6. User accepts → download + install via PAX SDK
 * 7. User dismisses → close dialog, command completes
 *
 * @see com.jaac.avoqado_tpv.features.remote_command.domain.CommandExecutor
 */
@Singleton
class UpdateRequestManager @Inject constructor(
    private val avoqadoUpdateRepository: AvoqadoUpdateRepository,
    private val installerAppUseCase: InstallerAppUseCase,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "UpdateRequestManager"
    }

    private val _updateRequestState = MutableStateFlow<UpdateRequestState>(UpdateRequestState.Idle)
    val updateRequestState: StateFlow<UpdateRequestState> = _updateRequestState.asStateFlow()

    // Track current command for ACK
    private var currentCommandId: String? = null
    private var currentUpdateInfo: AvoqadoUpdateInfo? = null
    private var downloadedApkPath: String? = null

    /**
     * Handle REQUEST_UPDATE command from CommandExecutor
     *
     * Checks for Avoqado updates and shows dialog if available.
     *
     * @param commandId Command ID for tracking
     * @return Result message for command ACK
     */
    suspend fun handleUpdateRequest(commandId: String): UpdateRequestResult {
        Timber.i("📥 [$TAG] Handling REQUEST_UPDATE command: $commandId")

        currentCommandId = commandId
        _updateRequestState.value = UpdateRequestState.Checking

        return try {
            when (val result = avoqadoUpdateRepository.checkForUpdate()) {
                is UpdateCheckResult.UpdateAvailable -> {
                    val update = result.updateInfo
                    currentUpdateInfo = update
                    Timber.i("✅ [$TAG] Update available: ${update.versionName} (${update.versionCode})")

                    _updateRequestState.value = UpdateRequestState.ShowDialog(
                        commandId = commandId,
                        updateInfo = update
                    )

                    UpdateRequestResult.DialogShown(
                        message = "Update dialog shown to user",
                        versionName = update.versionName,
                        versionCode = update.versionCode
                    )
                }

                is UpdateCheckResult.UpToDate -> {
                    Timber.i("✅ [$TAG] App is already up to date")
                    _updateRequestState.value = UpdateRequestState.Idle

                    UpdateRequestResult.AlreadyUpToDate(
                        message = "App is already up to date",
                        currentVersion = result.currentVersion
                    )
                }

                is UpdateCheckResult.Error -> {
                    Timber.e("❌ [$TAG] Check update failed: ${result.message}")
                    _updateRequestState.value = UpdateRequestState.Idle

                    UpdateRequestResult.Error(
                        message = "Failed to check for updates: ${result.message}"
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ [$TAG] Exception handling update request")
            _updateRequestState.value = UpdateRequestState.Idle

            UpdateRequestResult.Error(
                message = "Error: ${e.message}"
            )
        }
    }

    /**
     * User accepted the update - start download and install
     */
    suspend fun acceptUpdate() {
        val updateInfo = currentUpdateInfo ?: run {
            Timber.w("[$TAG] acceptUpdate called but no update info available")
            _updateRequestState.value = UpdateRequestState.Idle
            return
        }

        Timber.i("📥 [$TAG] User accepted update - starting download")
        _updateRequestState.value = UpdateRequestState.Downloading(progress = 0)

        try {
            // Download APK
            val downloadResult = avoqadoUpdateRepository.downloadApk(updateInfo) { progress ->
                _updateRequestState.value = UpdateRequestState.Downloading(progress = progress)
            }

            when (downloadResult) {
                is DownloadResult.Success -> {
                    downloadedApkPath = downloadResult.filePath
                    Timber.i("✅ [$TAG] Download complete: ${downloadResult.filePath}")

                    // Install APK
                    installUpdate(downloadResult.filePath, updateInfo.versionName)
                }

                is DownloadResult.Error -> {
                    Timber.e("❌ [$TAG] Download failed: ${downloadResult.message}")
                    _updateRequestState.value = UpdateRequestState.Error(
                        message = downloadResult.message
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ [$TAG] Download exception")
            _updateRequestState.value = UpdateRequestState.Error(
                message = "Download error: ${e.message}"
            )
        }
    }

    /**
     * Install downloaded APK via PAX SDK
     */
    private suspend fun installUpdate(apkPath: String, versionName: String) {
        Timber.i("📦 [$TAG] Installing APK: $apkPath")
        _updateRequestState.value = UpdateRequestState.Installing

        try {
            // Copy APK to internal storage (ext4, NOT FUSE) before installing.
            // PAX ISys.installApp() runs in a separate system process that cannot read
            // from getExternalFilesDir on Android 10 due to FUSE restrictions.
            val apkFile = File(apkPath)
            val installDir = File(context.filesDir, "apk_install")
            installDir.mkdirs()
            val installApk = File(installDir, apkFile.name)
            withContext(Dispatchers.IO) {
                apkFile.copyTo(installApk, overwrite = true)
            }
            installApk.setReadable(true, false)
            installDir.setReadable(true, false)
            installDir.setExecutable(true, false)
            context.filesDir.setReadable(true, false)
            context.filesDir.setExecutable(true, false)

            val params = InstallerParams(installApk.absolutePath)

            val result = withContext(Dispatchers.IO) {
                installerAppUseCase.run(params)
            }

            if (result.isLeft) {
                val failure = result.leftValue()
                Timber.e("❌ [$TAG] Installation failed: $failure")
                _updateRequestState.value = UpdateRequestState.Error(
                    message = "Installation failed: $failure"
                )
                cleanupApk()
                return
            }

            Timber.i("✅ [$TAG] Installation complete - terminal will restart")
            _updateRequestState.value = UpdateRequestState.InstallComplete(versionName)
            cleanupApk()
            // Note: Terminal will go to PAX home menu after install

        } catch (e: Exception) {
            Timber.e(e, "❌ [$TAG] Installation exception")
            _updateRequestState.value = UpdateRequestState.Error(
                message = "Installation error: ${e.message}"
            )
            cleanupApk()
        }
    }

    /**
     * User dismissed the update dialog
     */
    fun dismissUpdate() {
        Timber.i("[$TAG] User dismissed update dialog")
        _updateRequestState.value = UpdateRequestState.Idle
        currentCommandId = null
        currentUpdateInfo = null
    }

    /**
     * Reset state (e.g., after error acknowledgment)
     */
    fun resetState() {
        _updateRequestState.value = UpdateRequestState.Idle
        currentCommandId = null
        currentUpdateInfo = null
        cleanupApk()
    }

    private fun cleanupApk() {
        downloadedApkPath?.let { path ->
            try {
                File(path).delete()
                Timber.d("🗑️ [$TAG] Deleted APK: $path")
            } catch (e: Exception) {
                Timber.w(e, "[$TAG] Failed to delete APK")
            }
        }
        downloadedApkPath = null
        try {
            val installDir = File(context.filesDir, "apk_install")
            installDir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {}
    }
}

/**
 * State for update request dialog
 */
sealed class UpdateRequestState {
    /** No update request active */
    data object Idle : UpdateRequestState()

    /** Checking for updates */
    data object Checking : UpdateRequestState()

    /** Show dialog to user - update available */
    data class ShowDialog(
        val commandId: String,
        val updateInfo: AvoqadoUpdateInfo
    ) : UpdateRequestState()

    /** Downloading APK */
    data class Downloading(val progress: Int) : UpdateRequestState()

    /** Installing APK */
    data object Installing : UpdateRequestState()

    /** Installation complete */
    data class InstallComplete(val versionName: String) : UpdateRequestState()

    /** Error occurred */
    data class Error(val message: String) : UpdateRequestState()
}

/**
 * Result of handling update request command
 */
sealed class UpdateRequestResult {
    /** Dialog shown to user - update available */
    data class DialogShown(
        val message: String,
        val versionName: String,
        val versionCode: Int
    ) : UpdateRequestResult()

    /** No update available */
    data class AlreadyUpToDate(
        val message: String,
        val currentVersion: String
    ) : UpdateRequestResult()

    /** Error checking for updates */
    data class Error(val message: String) : UpdateRequestResult()
}

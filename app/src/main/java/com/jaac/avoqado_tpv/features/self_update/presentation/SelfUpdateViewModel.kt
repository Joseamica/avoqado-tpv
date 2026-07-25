package com.jaac.avoqado_tpv.features.self_update.presentation

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.clean_lib_services.shared.core.domain.use_case.remote_download.check_version.CheckVersionUseCase
import com.example.clean_lib_services.shared.core.domain.use_case.remote_download.check_version.CheckVersionParams
import com.example.clean_lib_services.shared.core.domain.use_case.remote_download.downloadFile.DownloadFileUseCase
import com.example.clean_lib_services.shared.core.domain.use_case.remote_download.downloadFile.DownloadFileParams
import com.example.clean_lib_services.shared.initializer.domain.use_case.get_init_data.GetInitDataParams
import com.example.clean_lib_services.shared.initializer.domain.use_case.get_init_data.GetInitDataUseCase
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.data.network.AvoqadoUpdateInfo
import com.jaac.avoqado_tpv.core.observability.ObservabilityManager
import com.jaac.avoqado_tpv.features.self_update.data.AvoqadoUpdateRepository
import com.jaac.avoqado_tpv.features.self_update.data.DownloadResult
import com.jaac.avoqado_tpv.features.self_update.data.UpdateCheckResult
import com.jaac.avoqado_tpv.features.self_update.domain.ApkInstaller
import com.jaac.avoqado_tpv.features.self_update.domain.InstallResult
import com.jaac.avoqado_tpv.core.util.UpdateCheckManager
import com.jaac.avoqado_tpv.core.data.network.UpdateMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * SelfUpdateViewModel - Handles app self-update via dual sources
 *
 * **Dual Update System:**
 * - **Blumon (Provider)**: Updates managed by Blumon/PAX
 * - **Avoqado (Self-managed)**: Updates uploaded via Superadmin dashboard
 *
 * Flow: CheckVersion → DownloadFile → InstallerApp
 *
 * Uses:
 * - Blumon SDK use cases for provider updates
 * - AvoqadoUpdateRepository for self-managed updates
 * - ApkInstaller for APK installation (PackageInstaller Session API + PAX SDK fallback)
 *
 * Note: After successful installation, PAX terminal goes to home menu.
 * User must manually reopen the app.
 */
@HiltViewModel
class SelfUpdateViewModel @Inject constructor(
    private val checkVersionUseCase: CheckVersionUseCase,
    private val downloadFileUseCase: DownloadFileUseCase,
    private val apkInstaller: ApkInstaller,
    private val getInitDataUseCase: GetInitDataUseCase,
    private val avoqadoUpdateRepository: AvoqadoUpdateRepository,
    private val updateCheckManager: UpdateCheckManager,
    private val observability: ObservabilityManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "SelfUpdateVM"
    }

    private val _state = MutableStateFlow<SelfUpdateState>(SelfUpdateState.Idle)
    val state: StateFlow<SelfUpdateState> = _state.asStateFlow()

    // Store update info for download step
    private var currentUpdate: UpdateInfo? = null
    private var currentAvoqadoUpdate: AvoqadoUpdateInfo? = null
    private var downloadedApkPath: String? = null
    private var currentUpdateSource: UpdateSource = UpdateSource.BLUMON

    init {
        // Auto-start Avoqado update if there's a pending forced update
        // This triggers when user clicks "Actualizar Ahora" from ForceUpdateDialog
        checkAndAutoStartPendingUpdate()

        // No pending forced update: entering the screen means the user asked for
        // "Buscar actualizaciones", so check Avoqado right away instead of showing
        // an idle screen that requires a second tap.
        if (_state.value is SelfUpdateState.Idle) {
            checkForUpdateAvoqado()
        }
    }

    /**
     * Check if there's a pending forced update and auto-start the download flow
     *
     * Called on ViewModel init to automatically start the Avoqado update process
     * when navigating from ForceUpdateDialog.
     */
    private fun checkAndAutoStartPendingUpdate() {
        val pendingUpdate = updateCheckManager.pendingUpdate.value
        if (pendingUpdate != null && (pendingUpdate.updateMode == UpdateMode.FORCE || pendingUpdate.updateMode == UpdateMode.BANNER)) {
            Timber.i("📦 [SelfUpdate] Auto-starting Avoqado update: ${pendingUpdate.versionName} (mode=${pendingUpdate.updateMode})")

            // Set state and start the update flow directly
            currentAvoqadoUpdate = pendingUpdate
            currentUpdateSource = UpdateSource.AVOQADO
            _state.value = SelfUpdateState.AvoqadoUpdateAvailable(pendingUpdate)

            // Auto-start download if FORCE mode (skip showing update info, go straight to download)
            if (pendingUpdate.updateMode == UpdateMode.FORCE) {
                Timber.i("📦 [SelfUpdate] FORCE mode - auto-starting download")
                viewModelScope.launch {
                    delay(500) // Brief delay for UI to render
                    downloadUpdate()
                }
            }
        }
    }

    /**
     * Check if an update is available from Blumon servers (Provider)
     */
    fun checkForUpdateBlumon() {
        currentUpdateSource = UpdateSource.BLUMON
        viewModelScope.launch {
            _state.value = SelfUpdateState.Checking

            try {
                // Get posId from SDK init data
                val posId = getPosId()
                if (posId == null) {
                    _state.value = SelfUpdateState.Error(
                        code = UpdateErrorCode.TERMINAL_NOT_CONFIGURED,
                        message = "Terminal no configurado. Inicializa primero."
                    )
                    return@launch
                }

                // Strip build variant suffix (-sandbox, -debug, etc.) for Blumon API
                // Blumon expects clean version like "1.1.0", not "1.1.0-sandbox"
                val cleanVersion = BuildConfig.VERSION_NAME
                    .replace("-sandbox", "")
                    .replace("-debug", "")
                    .replace("-production", "")

                val params = CheckVersionParams(
                    posId = posId,
                    version = cleanVersion
                )

                Timber.d("🔍 [Blumon] Checking for updates: posId=$posId, version=$cleanVersion (raw: ${BuildConfig.VERSION_NAME})")

                val result = withContext(Dispatchers.IO) {
                    checkVersionUseCase.run(params)
                }

                if (result.isLeft) {
                    val failure = result.leftValue()
                    Timber.e("❌ [Blumon] CheckVersion failed: $failure")
                    // GenericFailure usually means no update available or app not registered
                    // Treat as "up to date" instead of showing technical error
                    val failureString = failure.toString()
                    if (failureString.contains("GenericFailure")) {
                        Timber.i("ℹ️ [Blumon] No updates registered for this app - treating as up to date")
                        _state.value = SelfUpdateState.UpToDate(BuildConfig.VERSION_NAME, UpdateSource.BLUMON)
                    } else {
                        _state.value = SelfUpdateState.Error(
                            code = UpdateErrorCode.SERVER_ERROR,
                            message = "Error al verificar: $failure",
                            retryAction = RetryAction.CHECK_VERSION_BLUMON
                        )
                    }
                    return@launch
                }

                val response = result.rightValue()
                handleCheckVersionResponse(response)

            } catch (e: Exception) {
                Timber.e(e, "❌ [Blumon] CheckVersion exception")
                _state.value = SelfUpdateState.Error(
                    code = UpdateErrorCode.UNKNOWN,
                    message = "Error inesperado: ${e.message}"
                )
            }
        }
    }

    /**
     * Check if an update is available from Avoqado backend (Self-managed)
     */
    fun checkForUpdateAvoqado() {
        currentUpdateSource = UpdateSource.AVOQADO
        viewModelScope.launch {
            _state.value = SelfUpdateState.Checking

            try {
                Timber.d("🔍 [Avoqado] Checking for updates...")

                when (val result = avoqadoUpdateRepository.checkForUpdate()) {
                    is UpdateCheckResult.UpToDate -> {
                        Timber.i("✅ [Avoqado] App is up to date")
                        _state.value = SelfUpdateState.UpToDate(
                            currentVersion = result.currentVersion,
                            source = UpdateSource.AVOQADO
                        )
                    }
                    is UpdateCheckResult.UpdateAvailable -> {
                        val update = result.updateInfo
                        currentAvoqadoUpdate = update
                        Timber.i("✅ [Avoqado] Update available: ${update.versionName} (${update.versionCode})")
                        _state.value = SelfUpdateState.AvoqadoUpdateAvailable(update)
                    }
                    is UpdateCheckResult.Error -> {
                        Timber.e("❌ [Avoqado] Check failed: ${result.message}")
                        _state.value = SelfUpdateState.Error(
                            code = UpdateErrorCode.SERVER_ERROR,
                            message = result.message,
                            retryAction = RetryAction.CHECK_VERSION_AVOQADO
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ [Avoqado] CheckVersion exception")
                _state.value = SelfUpdateState.Error(
                    code = UpdateErrorCode.UNKNOWN,
                    message = "Error inesperado: ${e.message}"
                )
            }
        }
    }

    private fun handleCheckVersionResponse(response: Any) {
        try {
            // Use reflection to access dataResponse since we don't have exact class access
            val dataResponseField = response::class.java.getDeclaredField("dataResponse")
            dataResponseField.isAccessible = true
            val dataResponse = dataResponseField.get(response)

            if (dataResponse == null) {
                Timber.i("✅ [Blumon] Already on latest version (no dataResponse)")
                _state.value = SelfUpdateState.UpToDate(BuildConfig.VERSION_NAME, UpdateSource.BLUMON)
                return
            }

            // Get fields from dataResponse
            val descriptionField = dataResponse::class.java.getDeclaredField("description")
            descriptionField.isAccessible = true
            val description = descriptionField.get(dataResponse) as? String

            val urlFileField = dataResponse::class.java.getDeclaredField("urlFile")
            urlFileField.isAccessible = true
            val urlFile = urlFileField.get(dataResponse) as? String

            val zipFileField = dataResponse::class.java.getDeclaredField("zipFile")
            zipFileField.isAccessible = true
            val zipFile = zipFileField.get(dataResponse) as? String

            // Based on Edgardo's code: if description is null, update is available
            if (description == null && urlFile != null && zipFile != null) {
                // Update available
                val update = UpdateInfo(
                    currentVersion = BuildConfig.VERSION_NAME,
                    newVersion = zipFile,
                    urlFile = urlFile,
                    zipFile = zipFile
                )
                currentUpdate = update
                Timber.i("✅ [Blumon] Update available: ${update.zipFile}")
                _state.value = SelfUpdateState.BlumonUpdateAvailable(update)
            } else {
                // No update or error message
                if (description != null) {
                    Timber.i("ℹ️ [Blumon] No update available: $description")
                } else {
                    Timber.i("✅ [Blumon] Already on latest version")
                }
                _state.value = SelfUpdateState.UpToDate(BuildConfig.VERSION_NAME, UpdateSource.BLUMON)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error parsing CheckVersion response")
            _state.value = SelfUpdateState.UpToDate(BuildConfig.VERSION_NAME, UpdateSource.BLUMON)
        }
    }

    /**
     * Download the available update (routes to correct source)
     */
    fun downloadUpdate() {
        when (currentUpdateSource) {
            UpdateSource.BLUMON -> downloadUpdateBlumon()
            UpdateSource.AVOQADO -> downloadUpdateAvoqado()
        }
    }

    /**
     * Download update from Blumon servers
     */
    private fun downloadUpdateBlumon() {
        val update = currentUpdate ?: return

        viewModelScope.launch {
            _state.value = SelfUpdateState.Downloading(progress = 0, source = UpdateSource.BLUMON)

            try {
                // App-scoped external storage: no permissions needed on any Android version,
                // readable by PAX system installer
                val downloadDir = File(context.getExternalFilesDir(null), "apk_updates").also {
                    if (!it.exists()) it.mkdirs()
                }

                val params = DownloadFileParams(
                    urlFile = update.urlFile,
                    zipFile = update.zipFile,
                    pathToDownloadFile = downloadDir
                )

                Timber.d("📥 [Blumon] Downloading update: ${update.urlFile}")

                val result = withContext(Dispatchers.IO) {
                    downloadFileUseCase.run(params)
                }

                if (result.isLeft) {
                    val failure = result.leftValue()
                    Timber.e("❌ [Blumon] Download failed: $failure")
                    _state.value = SelfUpdateState.Error(
                        code = UpdateErrorCode.DOWNLOAD_FAILED,
                        message = "Error de descarga: $failure",
                        retryAction = RetryAction.DOWNLOAD
                    )
                    return@launch
                }

                // The downloaded file is the zipFile name in the download directory
                val apkPath = File(downloadDir, update.zipFile).absolutePath
                downloadedApkPath = apkPath
                Timber.i("✅ [Blumon] Download complete: $apkPath")
                _state.value = SelfUpdateState.ReadyToInstall(
                    apkPath = apkPath,
                    versionName = update.newVersion,
                    source = UpdateSource.BLUMON
                )

            } catch (e: Exception) {
                Timber.e(e, "❌ [Blumon] Download exception")
                _state.value = SelfUpdateState.Error(
                    code = UpdateErrorCode.DOWNLOAD_FAILED,
                    message = "Error de descarga: ${e.message}"
                )
            }
        }
    }

    /**
     * Download update from Avoqado backend (Firebase Storage)
     */
    private fun downloadUpdateAvoqado() {
        val update = currentAvoqadoUpdate ?: return

        viewModelScope.launch {
            _state.value = SelfUpdateState.Downloading(progress = 0, source = UpdateSource.AVOQADO)

            try {
                Timber.d("📥 [Avoqado] Downloading update: ${update.downloadUrl}")

                val result = avoqadoUpdateRepository.downloadApk(update) { progress ->
                    _state.value = SelfUpdateState.Downloading(progress = progress, source = UpdateSource.AVOQADO)
                }

                when (result) {
                    is DownloadResult.Success -> {
                        downloadedApkPath = result.filePath
                        Timber.i("✅ [Avoqado] Download complete: ${result.filePath}")
                        _state.value = SelfUpdateState.ReadyToInstall(
                            apkPath = result.filePath,
                            versionName = update.versionName,
                            source = UpdateSource.AVOQADO
                        )
                    }
                    is DownloadResult.Error -> {
                        Timber.e("❌ [Avoqado] Download failed: ${result.message}")
                        _state.value = SelfUpdateState.Error(
                            code = UpdateErrorCode.DOWNLOAD_FAILED,
                            message = result.message,
                            retryAction = RetryAction.DOWNLOAD
                        )
                    }
                }

            } catch (e: Exception) {
                Timber.e(e, "❌ [Avoqado] Download exception")
                _state.value = SelfUpdateState.Error(
                    code = UpdateErrorCode.DOWNLOAD_FAILED,
                    message = "Error de descarga: ${e.message}"
                )
            }
        }
    }

    /**
     * Install the downloaded APK via PackageInstaller Session API (with PAX SDK fallback)
     *
     * Note: After successful installation, the terminal will go to PAX home menu.
     * This is expected behavior and cannot be changed.
     */
    fun installUpdate() {
        val apkPath = downloadedApkPath ?: return
        val versionName = currentAvoqadoUpdate?.versionName ?: currentUpdate?.newVersion ?: "unknown"
        val updateSource = currentUpdateSource.name

        viewModelScope.launch {
            _state.value = SelfUpdateState.Installing
            val startTime = System.currentTimeMillis()

            observability.logInfo(TAG, "Install started from SelfUpdateScreen", mapOf(
                "apkPath" to apkPath,
                "versionName" to versionName,
                "updateSource" to updateSource
            ))

            try {
                val result = apkInstaller.install(apkPath)
                val durationMs = System.currentTimeMillis() - startTime

                when (result) {
                    is InstallResult.Success -> {
                        observability.logInfo(TAG, "Install completed successfully", mapOf(
                            "versionName" to versionName,
                            "updateSource" to updateSource,
                            "strategy" to result.strategy,
                            "durationMs" to durationMs
                        ))
                        _state.value = SelfUpdateState.InstallComplete

                        // Report success to backend API
                        reportInstallAttemptToBackend(
                            versionName = versionName,
                            success = true,
                            strategy = result.strategy,
                            errorMessage = null,
                            durationMs = durationMs,
                            updateSource = updateSource
                        )
                    }
                    is InstallResult.Error -> {
                        observability.logCritical(TAG, "Install failed", metadata = mapOf(
                            "versionName" to versionName,
                            "updateSource" to updateSource,
                            "error" to result.message,
                            "durationMs" to durationMs
                        ))
                        _state.value = SelfUpdateState.Error(
                            code = UpdateErrorCode.INSTALL_FAILED,
                            message = result.message,
                            retryAction = RetryAction.INSTALL
                        )

                        // Report failure to backend API
                        reportInstallAttemptToBackend(
                            versionName = versionName,
                            success = false,
                            strategy = "BOTH_FAILED",
                            errorMessage = result.message,
                            durationMs = durationMs,
                            updateSource = updateSource
                        )
                    }
                }
                cleanupApk()

            } catch (e: Exception) {
                val durationMs = System.currentTimeMillis() - startTime
                observability.logCritical(TAG, "Install exception", e, mapOf(
                    "versionName" to versionName,
                    "updateSource" to updateSource,
                    "exception" to (e.message ?: "unknown"),
                    "durationMs" to durationMs
                ))
                _state.value = SelfUpdateState.Error(
                    code = UpdateErrorCode.INSTALL_FAILED,
                    message = "Error de instalacion: ${e.message}"
                )

                // Report exception to backend API
                reportInstallAttemptToBackend(
                    versionName = versionName,
                    success = false,
                    strategy = "EXCEPTION",
                    errorMessage = "Exception: ${e.message}",
                    durationMs = durationMs,
                    updateSource = updateSource
                )
                cleanupApk()
            }
        }
    }

    /** Report install attempt to backend API (best-effort, non-blocking) */
    private fun reportInstallAttemptToBackend(
        versionName: String,
        success: Boolean,
        strategy: String,
        errorMessage: String?,
        durationMs: Long,
        updateSource: String
    ) {
        viewModelScope.launch {
            avoqadoUpdateRepository.reportInstallAttempt(
                versionName = versionName,
                serialNumber = null,
                success = success,
                strategy = strategy,
                errorMessage = errorMessage,
                androidVersion = Build.VERSION.SDK_INT,
                durationMs = durationMs,
                updateSource = updateSource,
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                packageName = context.packageName
            )
        }
    }

    /**
     * Cancel and reset to idle state
     */
    fun cancel() {
        cleanupApk()
        currentUpdate = null
        currentAvoqadoUpdate = null
        _state.value = SelfUpdateState.Idle
    }

    /**
     * Retry the last failed operation
     */
    fun retry() {
        when (val currentState = _state.value) {
            is SelfUpdateState.Error -> {
                when (currentState.retryAction) {
                    RetryAction.CHECK_VERSION_BLUMON -> checkForUpdateBlumon()
                    RetryAction.CHECK_VERSION_AVOQADO -> checkForUpdateAvoqado()
                    RetryAction.DOWNLOAD -> downloadUpdate()
                    RetryAction.INSTALL -> installUpdate()
                    null -> _state.value = SelfUpdateState.Idle
                }
            }
            else -> _state.value = SelfUpdateState.Idle
        }
    }

    private suspend fun getPosId(): String? {
        return try {
            val result = withContext(Dispatchers.IO) {
                getInitDataUseCase.run(GetInitDataParams())
            }

            if (result.isLeft) {
                Timber.w("Failed to get init data: ${result.leftValue()}")
                return null
            }

            val response = result.rightValue()
            // Use reflection to access initData.posId
            try {
                val initDataField = response::class.java.getDeclaredField("initData")
                initDataField.isAccessible = true
                val initData = initDataField.get(response) ?: return null

                val posIdField = initData::class.java.getDeclaredField("posId")
                posIdField.isAccessible = true
                posIdField.get(initData) as? String
            } catch (e: Exception) {
                Timber.e(e, "Error accessing initData.posId via reflection")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to get posId")
            null
        }
    }

    private fun cleanupApk() {
        downloadedApkPath?.let { path ->
            try {
                File(path).delete()
                Timber.d("Deleted APK: $path")
            } catch (e: Exception) {
                Timber.w(e, "Failed to delete APK")
            }
        }
        downloadedApkPath = null
    }
}

/**
 * State for self-update feature
 */
sealed class SelfUpdateState {
    /** Initial state - no check performed */
    data object Idle : SelfUpdateState()

    /** Checking for updates with either source */
    data object Checking : SelfUpdateState()

    /** No update available - current version is latest */
    data class UpToDate(
        val currentVersion: String,
        val source: UpdateSource = UpdateSource.BLUMON
    ) : SelfUpdateState()

    /** Update available from Blumon - ready to download */
    data class BlumonUpdateAvailable(val update: UpdateInfo) : SelfUpdateState()

    /** Update available from Avoqado - ready to download */
    data class AvoqadoUpdateAvailable(val update: AvoqadoUpdateInfo) : SelfUpdateState()

    /** Downloading APK with progress */
    data class Downloading(
        val progress: Int,
        val source: UpdateSource = UpdateSource.BLUMON
    ) : SelfUpdateState()

    /** Download complete - ready to install */
    data class ReadyToInstall(
        val apkPath: String,
        val versionName: String,
        val source: UpdateSource = UpdateSource.BLUMON
    ) : SelfUpdateState()

    /** Installing APK via PAX SDK */
    data object Installing : SelfUpdateState()

    /** Installation complete - terminal will go to home menu */
    data object InstallComplete : SelfUpdateState()

    /** Error occurred */
    data class Error(
        val code: UpdateErrorCode,
        val message: String,
        val retryAction: RetryAction? = null
    ) : SelfUpdateState()
}

/**
 * Update source for dual update system
 */
enum class UpdateSource {
    BLUMON,   // Provider-managed updates
    AVOQADO   // Self-managed updates
}

/**
 * Update information from Blumon checkVersion response
 */
data class UpdateInfo(
    val currentVersion: String,
    val newVersion: String,
    val urlFile: String,
    val zipFile: String
)

/**
 * Error codes for update failures
 */
enum class UpdateErrorCode {
    NETWORK_ERROR,
    SERVER_ERROR,
    TERMINAL_NOT_CONFIGURED,
    DOWNLOAD_FAILED,
    INSTALL_FAILED,
    PERMISSION_DENIED,
    UNKNOWN
}

/**
 * Action to retry on error
 */
enum class RetryAction {
    CHECK_VERSION_BLUMON,
    CHECK_VERSION_AVOQADO,
    DOWNLOAD,
    INSTALL
}

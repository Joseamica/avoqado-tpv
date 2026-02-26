package com.jaac.avoqado_tpv.features.self_update.domain

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.blumonpay.pax.shared.installer.domain.use_case.InstallerAppUseCase
import com.blumonpay.pax.shared.installer.domain.use_case.InstallerParams
import com.jaac.avoqado_tpv.core.observability.ObservabilityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ApkInstaller - Centralized APK installation with two strategies
 *
 * **Problem**: PAX `ISys.installApp()` runs inside NeptuneService (a separate system process).
 * On Android 10+ with FUSE and SELinux Enforcing, NeptuneService cannot read APK files
 * owned by the app — regardless of file path. The misleading "Unzip error" from
 * `MiscSettings.update()` is actually a file-access failure.
 *
 * **Solution**: Android PackageInstaller Session API streams APK bytes via IPC.
 * The app reads its own file (which it CAN do) and writes bytes into a session.
 * No cross-process file path access needed. Works on all Android versions we support (minSdk 27+).
 *
 * **Strategy**:
 * 1. Primary — PackageInstaller Session API (all Android versions)
 * 2. Fallback — PAX SDK InstallerAppUseCase (all versions, likely fails on Android 10+ due to FUSE)
 *
 * **Observability**: All install steps are logged to Crashlytics + Socket.IO + File via ObservabilityManager.
 */
@Singleton
class ApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val installerAppUseCase: InstallerAppUseCase,
    private val observability: ObservabilityManager
) {
    companion object {
        private const val TAG = "ApkInstaller"
        private const val BUFFER_SIZE = 65536 // 64KB
        private const val INSTALL_TIMEOUT_MS = 120_000L // 2 minutes
        internal const val EXTRA_SESSION_ID = "com.jaac.avoqado_tpv.EXTRA_SESSION_ID"
    }

    /**
     * Active install session: sessionId + deferred result.
     * Validated by sessionId to prevent stale callbacks from completing the wrong install.
     */
    internal data class PendingInstall(
        val sessionId: Int,
        val deferred: CompletableDeferred<InstallResult>
    )

    @Volatile
    internal var pendingInstall: PendingInstall? = null

    // Prevents concurrent installs from SelfUpdateViewModel and UpdateRequestManager
    private val installMutex = Mutex()

    /** Device metadata included in every log entry */
    private fun deviceMetadata(): Map<String, Any?> = mapOf(
        "androidVersion" to Build.VERSION.SDK_INT,
        "androidRelease" to Build.VERSION.RELEASE,
        "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "packageName" to context.packageName
    )

    /**
     * Install an APK file using the best available strategy.
     * Serialized via mutex — concurrent calls wait for the previous install to finish.
     *
     * @param apkPath Absolute path to the APK file
     * @return InstallResult indicating success or failure
     */
    suspend fun install(apkPath: String): InstallResult = installMutex.withLock {
        withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val apkFile = File(apkPath)

        val fileMetadata = deviceMetadata() + mapOf(
            "apkPath" to apkPath,
            "fileExists" to apkFile.exists(),
            "fileReadable" to if (apkFile.exists()) apkFile.canRead() else false,
            "fileSize" to if (apkFile.exists()) apkFile.length() else 0L
        )

        observability.logInfo(TAG, "Install started", fileMetadata)

        if (!apkFile.exists()) {
            val error = "Archivo APK no encontrado"
            observability.logCritical(TAG, "APK file does not exist", metadata = fileMetadata + mapOf("error" to error))
            return@withContext InstallResult.Error(error)
        }

        if (!apkFile.canRead()) {
            val error = "No se puede leer el archivo APK"
            observability.logCritical(TAG, "APK file not readable", metadata = fileMetadata + mapOf("error" to error))
            return@withContext InstallResult.Error(error)
        }

        observability.logInfo(TAG, "APK file validated", fileMetadata)

        // Strategy 1: PackageInstaller Session API (works on all Android versions)
        observability.logInfo(TAG, "Trying Strategy 1: PackageInstaller Session API", deviceMetadata())
        val sessionResult = tryPackageInstallerSession(apkFile)
        if (sessionResult is InstallResult.Success) {
            val durationMs = System.currentTimeMillis() - startTime
            observability.logInfo(TAG, "Install SUCCESS via PackageInstaller", fileMetadata + mapOf(
                "strategy" to "PACKAGE_INSTALLER",
                "durationMs" to durationMs
            ))
            return@withContext sessionResult
        }

        val sessionError = (sessionResult as InstallResult.Error).message
        val sessionDurationMs = System.currentTimeMillis() - startTime
        observability.logWarning(TAG, "PackageInstaller failed, trying fallback", fileMetadata + mapOf(
            "strategy" to "PACKAGE_INSTALLER",
            "error" to sessionError,
            "durationMs" to sessionDurationMs
        ))

        // Strategy 2: PAX SDK fallback (last resort on all Android versions)
        observability.logInfo(TAG, "Trying Strategy 2: PAX SDK fallback", deviceMetadata())
        val paxResult = tryPaxSdkInstall(apkFile)
        if (paxResult is InstallResult.Success) {
            val durationMs = System.currentTimeMillis() - startTime
            observability.logInfo(TAG, "Install SUCCESS via PAX SDK fallback", fileMetadata + mapOf(
                "strategy" to "PAX_SDK",
                "durationMs" to durationMs
            ))
            return@withContext paxResult
        }

        val paxError = (paxResult as InstallResult.Error).message
        val totalDurationMs = System.currentTimeMillis() - startTime

        // Both strategies failed — this is CRITICAL
        observability.logCritical(TAG, "ALL install strategies failed", metadata = fileMetadata + mapOf(
            "strategy" to "BOTH_FAILED",
            "packageInstallerError" to sessionError,
            "paxSdkError" to paxError,
            "durationMs" to totalDurationMs
        ))

        InstallResult.Error("Error de instalacion: $sessionError")
    } }  // withContext + withLock

    /**
     * Install APK using Android PackageInstaller Session API.
     *
     * This streams APK bytes via IPC — the app reads its own file and writes
     * bytes into a session. No cross-process file path access needed.
     */
    private suspend fun tryPackageInstallerSession(apkFile: File): InstallResult {
        var receiver: InstallResultReceiver? = null
        return try {
            val packageInstaller = context.packageManager.packageInstaller

            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setSize(apkFile.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }

            val sessionId = packageInstaller.createSession(params)
            observability.logInfo(TAG, "PackageInstaller session created", mapOf(
                "sessionId" to sessionId,
                "fileSize" to apkFile.length()
            ))

            packageInstaller.openSession(sessionId).use { session ->
                val writeStartTime = System.currentTimeMillis()
                session.openWrite("avoqado_update.apk", 0, apkFile.length()).use { outputStream ->
                    FileInputStream(apkFile).use { inputStream ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        var totalBytesWritten = 0L
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalBytesWritten += bytesRead
                        }
                        session.fsync(outputStream)

                        val writeDurationMs = System.currentTimeMillis() - writeStartTime
                        observability.logInfo(TAG, "APK bytes written to session", mapOf(
                            "sessionId" to sessionId,
                            "bytesWritten" to totalBytesWritten,
                            "writeDurationMs" to writeDurationMs
                        ))
                    }
                }

                val deferred = CompletableDeferred<InstallResult>()
                pendingInstall = PendingInstall(sessionId, deferred)

                // Register receiver BEFORE commit — unregistered in finally block
                receiver = InstallResultReceiver()
                val intentFilter = android.content.IntentFilter(InstallResultReceiver.ACTION_INSTALL_RESULT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(receiver, intentFilter)
                }

                val intent = Intent(InstallResultReceiver.ACTION_INSTALL_RESULT)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_SESSION_ID, sessionId)
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )

                session.commit(pendingIntent.intentSender)
                observability.logInfo(TAG, "Session committed, awaiting result", mapOf(
                    "sessionId" to sessionId,
                    "timeoutMs" to INSTALL_TIMEOUT_MS
                ))

                val result = withTimeoutOrNull(INSTALL_TIMEOUT_MS) {
                    deferred.await()
                }

                result ?: run {
                    val error = "Instalacion sin respuesta (timeout)"
                    observability.logCritical(TAG, "Install timed out — no callback received", metadata = mapOf(
                        "sessionId" to sessionId,
                        "timeoutMs" to INSTALL_TIMEOUT_MS,
                        "error" to error
                    ))
                    InstallResult.Error(error)
                }
            }
        } catch (e: Exception) {
            observability.logCritical(TAG, "PackageInstaller session exception", e, deviceMetadata() + mapOf(
                "exception" to (e.message ?: "unknown"),
                "exceptionType" to e.javaClass.simpleName
            ))
            InstallResult.Error("PackageInstaller: ${e.message}")
        } finally {
            // Always clean up receiver and pending state
            receiver?.let {
                try { context.unregisterReceiver(it) } catch (_: Exception) {}
            }
            pendingInstall = null
        }
    }

    /**
     * Fallback: Install APK using PAX SDK InstallerAppUseCase.
     *
     * On Android 10+ this will likely fail due to FUSE cross-process restrictions,
     * but PAX firmware may have vendor-specific workarounds.
     */
    private suspend fun tryPaxSdkInstall(apkFile: File): InstallResult {
        return try {
            apkFile.setReadable(true, false)

            observability.logInfo(TAG, "PAX SDK install starting", mapOf(
                "apkPath" to apkFile.absolutePath,
                "fileSize" to apkFile.length(),
                "worldReadable" to apkFile.canRead()
            ))

            val params = InstallerParams(apkFile.absolutePath)
            val result = installerAppUseCase.run(params)

            if (result.isLeft) {
                val failure = result.leftValue()
                observability.logError(TAG, "PAX SDK install failed", metadata = mapOf(
                    "failure" to failure.toString(),
                    "apkPath" to apkFile.absolutePath
                ))
                InstallResult.Error("PAX SDK: $failure")
            } else {
                observability.logInfo(TAG, "PAX SDK install success", mapOf(
                    "apkPath" to apkFile.absolutePath
                ))
                InstallResult.Success(strategy = "PAX_SDK")
            }
        } catch (e: Exception) {
            observability.logCritical(TAG, "PAX SDK install exception", e, mapOf(
                "apkPath" to apkFile.absolutePath,
                "exception" to (e.message ?: "unknown"),
                "exceptionType" to e.javaClass.simpleName
            ))
            InstallResult.Error("PAX SDK: ${e.message}")
        }
    }
}

/**
 * Result of APK installation attempt.
 */
sealed class InstallResult {
    /** @param strategy Which strategy succeeded: "PACKAGE_INSTALLER" or "PAX_SDK" */
    data class Success(val strategy: String) : InstallResult()
    data class Error(val message: String) : InstallResult()
}

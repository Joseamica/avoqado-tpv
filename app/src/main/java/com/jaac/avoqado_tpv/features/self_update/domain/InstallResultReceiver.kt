package com.jaac.avoqado_tpv.features.self_update.domain

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.jaac.avoqado_tpv.core.observability.ObservabilityManager
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import timber.log.Timber

/**
 * InstallResultReceiver - Receives PackageInstaller session results
 *
 * Dynamically registered (not in manifest) by ApkInstaller when committing
 * a PackageInstaller session. Validates sessionId to prevent stale callbacks
 * from completing the wrong install, then completes the CompletableDeferred.
 *
 * Handles:
 * - STATUS_SUCCESS: Installation completed successfully
 * - STATUS_PENDING_USER_ACTION: Launches system confirm dialog, waits for follow-up callback
 * - STATUS_FAILURE_*: Various failure codes mapped to error messages
 *
 * All results are logged to Crashlytics + Socket.IO + File via ObservabilityManager.
 */
class InstallResultReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_INSTALL_RESULT = "com.jaac.avoqado_tpv.INSTALL_RESULT"
        private const val TAG = "InstallResultReceiver"
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface InstallerEntryPoint {
        fun apkInstaller(): ApkInstaller
        fun observabilityManager(): ObservabilityManager
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        val sessionId = intent.getIntExtra(ApkInstaller.EXTRA_SESSION_ID, -1)

        val statusName = statusToName(status)

        Timber.i("[$TAG] Install result: status=$status ($statusName), sessionId=$sessionId, message=$message")

        val entryPoint = try {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                InstallerEntryPoint::class.java
            )
        } catch (e: Exception) {
            Timber.e(e, "[$TAG] Failed to get EntryPoint — cannot process install result")
            return
        }

        val apkInstaller = entryPoint.apkInstaller()
        val observability = entryPoint.observabilityManager()

        val baseMetadata = mapOf<String, Any?>(
            "sessionId" to sessionId,
            "statusCode" to status,
            "statusName" to statusName,
            "statusMessage" to message,
            "androidVersion" to Build.VERSION.SDK_INT,
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}"
        )

        observability.logInfo(TAG, "Received install callback", baseMetadata)

        // Validate sessionId matches the active install to prevent stale callbacks
        val pending = apkInstaller.pendingInstall
        if (pending == null) {
            observability.logWarning(TAG, "No pending install — ignoring stale callback", baseMetadata)
            return
        }
        if (sessionId != -1 && sessionId != pending.sessionId) {
            observability.logWarning(TAG, "SessionId mismatch — ignoring", baseMetadata + mapOf(
                "expectedSessionId" to pending.sessionId
            ))
            return
        }

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                observability.logInfo(TAG, "Installation successful", baseMetadata)
                pending.deferred.complete(InstallResult.Success(strategy = "PACKAGE_INSTALLER"))
            }

            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // System requires user confirmation. This happens when the app is NOT
                // signed with the platform key (e.g., debug builds). PAX-signed production
                // APKs get INSTALL_PACKAGES and skip this.
                //
                // Launch the system confirm dialog. Do NOT complete the deferred —
                // the system will send another broadcast (SUCCESS or FAILURE) after the
                // user confirms or rejects. The 120s timeout in ApkInstaller protects
                // against the user never responding.
                observability.logWarning(TAG, "User confirmation required (STATUS_PENDING_USER_ACTION)", baseMetadata)
                apkInstaller.pendingUserConfirmation = true

                @Suppress("DEPRECATION")
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    observability.logInfo(TAG, "Launching system install confirmation dialog", baseMetadata)
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirmIntent)
                        // Return WITHOUT completing — wait for follow-up callback
                        return
                    } catch (e: Exception) {
                        val error = "No se pudo mostrar dialogo de confirmacion"
                        observability.logCritical(TAG, "Failed to launch confirmation activity", e, baseMetadata + mapOf(
                            "error" to error,
                            "exception" to (e.message ?: "unknown")
                        ))
                        pending.deferred.complete(InstallResult.Error(error))
                    }
                } else {
                    val error = "Confirmacion de usuario requerida"
                    observability.logCritical(TAG, "STATUS_PENDING_USER_ACTION but no confirmation intent", metadata = baseMetadata + mapOf(
                        "error" to error
                    ))
                    pending.deferred.complete(InstallResult.Error(error))
                }
            }

            PackageInstaller.STATUS_FAILURE -> {
                val error = "Instalacion fallida: $message"
                observability.logCritical(TAG, "Installation FAILED", metadata = baseMetadata + mapOf("error" to error))
                pending.deferred.complete(InstallResult.Error(error))
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                val error = "Instalacion cancelada"
                observability.logError(TAG, "Installation aborted", metadata = baseMetadata + mapOf("error" to error))
                pending.deferred.complete(InstallResult.Error(error))
            }

            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                val error = "Instalacion bloqueada por el sistema"
                observability.logCritical(TAG, "Installation BLOCKED by system", metadata = baseMetadata + mapOf("error" to error))
                pending.deferred.complete(InstallResult.Error(error))
            }

            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                val error = "Conflicto con app existente"
                observability.logCritical(TAG, "Installation CONFLICT with existing app", metadata = baseMetadata + mapOf("error" to error))
                pending.deferred.complete(InstallResult.Error(error))
            }

            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                val error = "APK incompatible con este dispositivo"
                observability.logCritical(TAG, "APK INCOMPATIBLE with device", metadata = baseMetadata + mapOf("error" to error))
                pending.deferred.complete(InstallResult.Error(error))
            }

            PackageInstaller.STATUS_FAILURE_INVALID -> {
                val error = "APK invalido"
                observability.logCritical(TAG, "APK INVALID", metadata = baseMetadata + mapOf("error" to error))
                pending.deferred.complete(InstallResult.Error(error))
            }

            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                val error = "Espacio insuficiente"
                observability.logCritical(TAG, "STORAGE ERROR — insufficient space", metadata = baseMetadata + mapOf("error" to error))
                pending.deferred.complete(InstallResult.Error(error))
            }

            else -> {
                val error = "Error desconocido de instalacion (code=$status)"
                observability.logCritical(TAG, "Unknown install status code", metadata = baseMetadata + mapOf("error" to error))
                pending.deferred.complete(InstallResult.Error(error))
            }
        }
    }

    /** Map PackageInstaller status codes to human-readable names for logging */
    private fun statusToName(status: Int): String = when (status) {
        PackageInstaller.STATUS_SUCCESS -> "SUCCESS"
        PackageInstaller.STATUS_PENDING_USER_ACTION -> "PENDING_USER_ACTION"
        PackageInstaller.STATUS_FAILURE -> "FAILURE"
        PackageInstaller.STATUS_FAILURE_ABORTED -> "FAILURE_ABORTED"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "FAILURE_BLOCKED"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "FAILURE_CONFLICT"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "FAILURE_INCOMPATIBLE"
        PackageInstaller.STATUS_FAILURE_INVALID -> "FAILURE_INVALID"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "FAILURE_STORAGE"
        else -> "UNKNOWN($status)"
    }
}

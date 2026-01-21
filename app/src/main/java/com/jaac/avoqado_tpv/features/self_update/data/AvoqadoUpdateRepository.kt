package com.jaac.avoqado_tpv.features.self_update.data

import android.os.Environment
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.AvoqadoUpdateInfo
import com.jaac.avoqado_tpv.core.data.network.ReportUpdateInstalledRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for Avoqado self-managed app updates
 *
 * Part of the dual update system:
 * - Blumon: Provider-managed updates (via Blumon SDK)
 * - Avoqado: Self-managed updates (via this repository)
 *
 * Flow:
 * 1. checkForUpdate() - Query backend for latest version
 * 2. downloadApk() - Download APK from Firebase Storage
 * 3. verifyChecksum() - Verify SHA-256 hash
 * 4. Install via PAX SDK (handled by ViewModel)
 */
@Singleton
class AvoqadoUpdateRepository @Inject constructor(
    private val apiService: ApiService,
    // Note: We DON'T use the injected okHttpClient for downloads
    // because it has auth interceptors that break Firebase Storage downloads
) {
    // Plain OkHttpClient without auth interceptors for external URL downloads
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
        .writeTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
        .build()
    /**
     * Check if an update is available from Avoqado backend
     *
     * @return UpdateCheckResult with update info if available
     */
    suspend fun checkForUpdate(): UpdateCheckResult {
        return withContext(Dispatchers.IO) {
            try {
                // Determine environment from build variant
                val environment = if (BuildConfig.BLUMON_ENV == "SAND") "SANDBOX" else "PRODUCTION"
                val currentVersionCode = BuildConfig.VERSION_CODE

                Timber.d("🔍 [Avoqado] Checking for updates: versionCode=$currentVersionCode, env=$environment")

                val response = apiService.checkForAvoqadoUpdate(
                    currentVersion = currentVersionCode,
                    environment = environment
                )

                if (!response.isSuccessful) {
                    val errorBody = response.errorBody()?.string()
                    Timber.e("❌ [Avoqado] Check update failed: ${response.code()} - $errorBody")
                    return@withContext UpdateCheckResult.Error(
                        message = "Error del servidor: ${response.code()}"
                    )
                }

                val body = response.body()
                if (body == null) {
                    Timber.e("❌ [Avoqado] Empty response body")
                    return@withContext UpdateCheckResult.Error(
                        message = "Respuesta vacía del servidor"
                    )
                }

                if (!body.success) {
                    Timber.e("❌ [Avoqado] Server returned success=false: ${body.message}")
                    return@withContext UpdateCheckResult.Error(
                        message = body.message ?: "Error desconocido"
                    )
                }

                if (!body.hasUpdate || body.update == null) {
                    Timber.i("✅ [Avoqado] App is up to date")
                    return@withContext UpdateCheckResult.UpToDate(
                        currentVersion = BuildConfig.VERSION_NAME
                    )
                }

                val update = body.update
                Timber.i("✅ [Avoqado] Update available: ${update.versionName} (${update.versionCode})")

                return@withContext UpdateCheckResult.UpdateAvailable(
                    updateInfo = update
                )

            } catch (e: Exception) {
                Timber.e(e, "❌ [Avoqado] Exception checking for updates")
                return@withContext UpdateCheckResult.Error(
                    message = "Error de conexión: ${e.message}"
                )
            }
        }
    }

    /**
     * Get a specific version by versionCode (for INSTALL_VERSION command)
     *
     * Used for rollback/upgrade to a specific version.
     * SUPERADMIN can trigger this via INSTALL_VERSION command.
     *
     * @param versionCode Target versionCode (e.g., 5)
     * @return UpdateCheckResult with version info if found and active
     */
    suspend fun getSpecificVersion(versionCode: Int): UpdateCheckResult {
        return withContext(Dispatchers.IO) {
            try {
                // Determine environment from build variant
                val environment = if (BuildConfig.BLUMON_ENV == "SAND") "SANDBOX" else "PRODUCTION"

                Timber.d("🔍 [Avoqado] Getting specific version: versionCode=$versionCode, env=$environment")

                val response = apiService.getSpecificVersion(
                    versionCode = versionCode,
                    environment = environment
                )

                if (!response.isSuccessful) {
                    val errorBody = response.errorBody()?.string()
                    Timber.e("❌ [Avoqado] Get version failed: ${response.code()} - $errorBody")
                    return@withContext UpdateCheckResult.Error(
                        message = "Error del servidor: ${response.code()}"
                    )
                }

                val body = response.body()
                if (body == null) {
                    Timber.e("❌ [Avoqado] Empty response body")
                    return@withContext UpdateCheckResult.Error(
                        message = "Respuesta vacía del servidor"
                    )
                }

                if (!body.success) {
                    Timber.e("❌ [Avoqado] Server returned success=false: ${body.message}")
                    return@withContext UpdateCheckResult.Error(
                        message = body.message ?: "Error desconocido"
                    )
                }

                if (!body.found || body.version == null) {
                    Timber.w("⚠️ [Avoqado] Version $versionCode not found or not active")
                    return@withContext UpdateCheckResult.Error(
                        message = body.message ?: "Versión $versionCode no encontrada"
                    )
                }

                val version = body.version
                Timber.i("✅ [Avoqado] Found version: ${version.versionName} (${version.versionCode})")

                return@withContext UpdateCheckResult.UpdateAvailable(
                    updateInfo = version
                )

            } catch (e: Exception) {
                Timber.e(e, "❌ [Avoqado] Exception getting specific version")
                return@withContext UpdateCheckResult.Error(
                    message = "Error de conexión: ${e.message}"
                )
            }
        }
    }

    /**
     * Download APK from Firebase Storage URL
     *
     * @param updateInfo Update info with download URL
     * @param onProgress Progress callback (0-100)
     * @return Downloaded file path or error
     */
    suspend fun downloadApk(
        updateInfo: AvoqadoUpdateInfo,
        onProgress: (Int) -> Unit = {}
    ): DownloadResult {
        return withContext(Dispatchers.IO) {
            try {
                Timber.d("📥 [Avoqado] Downloading APK: ${updateInfo.downloadUrl}")

                val request = Request.Builder()
                    .url(updateInfo.downloadUrl)
                    .build()

                val response = downloadClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    Timber.e("❌ [Avoqado] Download failed: ${response.code}")
                    return@withContext DownloadResult.Error(
                        message = "Error de descarga: ${response.code}"
                    )
                }

                val body = response.body
                if (body == null) {
                    return@withContext DownloadResult.Error(
                        message = "Respuesta de descarga vacía"
                    )
                }

                // Create download directory
                val downloadDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                if (!downloadDir.exists()) {
                    downloadDir.mkdirs()
                }

                // Create output file
                val fileName = "avoqado-tpv-${updateInfo.versionName}.apk"
                val outputFile = File(downloadDir, fileName)

                // Download with progress
                val totalBytes = updateInfo.fileSize.toLongOrNull() ?: body.contentLength()
                var downloadedBytes = 0L

                FileOutputStream(outputFile).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            if (totalBytes > 0) {
                                val progress = ((downloadedBytes * 100) / totalBytes).toInt()
                                onProgress(progress.coerceIn(0, 100))
                            }
                        }
                    }
                }

                Timber.i("✅ [Avoqado] Download complete: ${outputFile.absolutePath}")

                // Verify checksum
                val actualChecksum = calculateSha256(outputFile)
                if (actualChecksum != updateInfo.checksum) {
                    Timber.e("❌ [Avoqado] Checksum mismatch! Expected: ${updateInfo.checksum}, Got: $actualChecksum")
                    outputFile.delete()
                    return@withContext DownloadResult.Error(
                        message = "Verificación de integridad falló"
                    )
                }

                Timber.i("✅ [Avoqado] Checksum verified")

                return@withContext DownloadResult.Success(
                    filePath = outputFile.absolutePath
                )

            } catch (e: Exception) {
                Timber.e(e, "❌ [Avoqado] Download exception")
                return@withContext DownloadResult.Error(
                    message = "Error de descarga: ${e.message}"
                )
            }
        }
    }

    /**
     * Report successful update installation for analytics
     */
    suspend fun reportUpdateInstalled(
        versionCode: Int,
        versionName: String,
        serialNumber: String?
    ) {
        withContext(Dispatchers.IO) {
            try {
                apiService.reportUpdateInstalled(
                    ReportUpdateInstalledRequest(
                        versionCode = versionCode,
                        versionName = versionName,
                        updateSource = "AVOQADO",
                        serialNumber = serialNumber
                    )
                )
                Timber.i("✅ [Avoqado] Reported update installation")
            } catch (e: Exception) {
                Timber.w(e, "Failed to report update installation")
                // Don't throw - this is just analytics
            }
        }
    }

    /**
     * Calculate SHA-256 hash of a file
     */
    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Result of update check
 */
sealed class UpdateCheckResult {
    data class UpToDate(val currentVersion: String) : UpdateCheckResult()
    data class UpdateAvailable(val updateInfo: AvoqadoUpdateInfo) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

/**
 * Result of APK download
 */
sealed class DownloadResult {
    data class Success(val filePath: String) : DownloadResult()
    data class Error(val message: String) : DownloadResult()
}

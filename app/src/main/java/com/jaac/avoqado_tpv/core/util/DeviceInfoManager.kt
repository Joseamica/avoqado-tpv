package com.jaac.avoqado_tpv.core.util

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.dto.ActivationStatusResponse
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DeviceInfoManager
 *
 * Manages device-specific information for terminal identification and activation.
 * Similar to Square POS device identification pattern.
 *
 * Usage:
 * ```kotlin
 * val deviceInfo = deviceInfoManager.getDeviceInfo()
 * val serialNumber = deviceInfo.serialNumber // e.g., "AVQD-2841548417"
 * ```
 *
 * Security Note:
 * - Uses Build.SERIAL (hardware serial) which persists forever (even after factory reset)
 * - Requires READ_PHONE_STATE permission on Android 8+
 * - Fallback to ANDROID_ID if permission denied (changes on app reinstall)
 * - This ensures terminals maintain fixed identification across app reinstalls
 */
@Singleton
class DeviceInfoManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureStorage: SecureStorage,
    private val apiService: ApiService
) {

    /**
     * Get comprehensive device information
     */
    fun getDeviceInfo(): DeviceInfo {
        return DeviceInfo(
            serialNumber = getSerialNumber(),
            deviceModel = getDeviceModel(),
            androidVersion = getAndroidVersion(),
            deviceManufacturer = getDeviceManufacturer(),
            deviceBrand = getDeviceBrand()
        )
    }

    /**
     * Get device serial number for terminal identification
     *
     * Format: AVQD-{Build.SERIAL}
     * Example: AVQD-2841548417
     *
     * This is the primary identifier for terminal activation.
     * Uses hardware serial (Build.SERIAL) which persists forever, even after:
     * - App uninstall/reinstall
     * - Factory reset
     * - OS updates
     *
     * Professional POS systems (Square Terminal, Toast POS, Clover) use hardware serial
     * instead of ANDROID_ID to maintain terminal identification across app lifecycle.
     *
     * **SECURITY REQUIREMENT:**
     * - ALWAYS uses hardware serial (no ANDROID_ID fallback)
     * - Throws SecurityException if READ_PHONE_STATE permission not granted
     * - App must request permission in runtime before calling this method
     *
     * @return Serial number with "AVQD-" prefix (uppercase)
     * @throws SecurityException if READ_PHONE_STATE permission not granted (Android 8+)
     */
    fun getSerialNumber(): String {
        val hardwareSerial = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8+: Requires READ_PHONE_STATE permission
            // ⚠️ NO FALLBACK - Permission must be granted before calling this
            try {
                Build.getSerial()
            } catch (e: SecurityException) {
                Timber.e(e, "❌ CRITICAL: READ_PHONE_STATE permission not granted - cannot get hardware serial")
                throw SecurityException(
                    "READ_PHONE_STATE permission required to obtain hardware serial number. " +
                    "Please grant permission in app settings."
                )
            }
        } else {
            // Android 7 and below: No permission required
            @Suppress("DEPRECATION")
            Build.SERIAL
        }

        return "AVQD-${hardwareSerial.uppercase()}"
    }

    /**
     * Get device model
     * Example: "Pixel 6", "Galaxy S23"
     */
    fun getDeviceModel(): String {
        return Build.MODEL
    }

    /**
     * Get Android version
     * Example: "13", "14"
     */
    fun getAndroidVersion(): String {
        return Build.VERSION.RELEASE
    }

    /**
     * Get device manufacturer
     * Example: "Google", "Samsung"
     */
    fun getDeviceManufacturer(): String {
        return Build.MANUFACTURER
    }

    /**
     * Get device brand
     * Example: "google", "samsung"
     */
    fun getDeviceBrand(): String {
        return Build.BRAND
    }

    /**
     * Check if the device is already activated
     *
     * Checks SecureStorage for venueId presence.
     * If venueId exists, device has been successfully activated.
     *
     * @return true if device is activated (has venueId), false otherwise
     */
    fun isDeviceActivated(): Boolean {
        val venueId = secureStorage.getVenueId()
        return venueId != null
    }

    /**
     * Check activation status with backend
     *
     * **Square/Toast Pattern:**
     * Verifies terminal is activated on backend (activatedAt !== null).
     * Used by SplashScreen to prevent routing to LoginScreen when not activated.
     *
     * **Security:**
     * - Detects RETIRED terminals (stolen devices)
     * - Clears local venueId if backend reports not activated
     * - Returns user-friendly Spanish error messages
     *
     * **Offline Handling:**
     * - On network error → trust local venueId (allow app to work)
     * - User can still access app offline if previously activated
     *
     * @return Result.Success(ActivationStatusResponse) if API call succeeds
     * @return Result.Error on network failure or server error
     */
    suspend fun checkActivationStatusWithBackend(): Result<ActivationStatusResponse> {
        return try {
            val serialNumber = getSerialNumber()
            Timber.d("🔍 Checking activation status with backend for serial: $serialNumber")

            val response = apiService.checkActivationStatus(serialNumber)

            if (response.isSuccessful && response.body() != null) {
                val status = response.body()!!
                Timber.d("✅ Backend activation check: isActivated=${status.isActivated}, status=${status.status}")

                // 🚨 SECURITY: Clear local data if backend says not activated or RETIRED
                if (!status.isActivated || status.status == "RETIRED") {
                    Timber.w("⚠️ Terminal not activated or RETIRED on backend - clearing local data")
                    secureStorage.clearAll()
                }

                Result.Success(status)
            } else {
                val errorMessage = "Error checking activation: ${response.code()} ${response.message()}"
                Timber.e("❌ Backend activation check failed: $errorMessage")
                Result.Error(ApiException.HttpError(response.code(), errorMessage))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Network error checking activation status")
            // Offline: Trust local venueId (don't block app)
            Result.Error(ApiException.NetworkError(e))
        }
    }

    /**
     * Get venue ID from secure storage
     *
     * Used for tenant isolation in navigation and API calls.
     *
     * @return Venue ID or null if not activated
     */
    fun getVenueId(): String? {
        return secureStorage.getVenueId()
    }
}

/**
 * Data class containing comprehensive device information
 */
data class DeviceInfo(
    val serialNumber: String,
    val deviceModel: String,
    val androidVersion: String,
    val deviceManufacturer: String,
    val deviceBrand: String
) {
    /**
     * Get a human-readable device description
     * Example: "Google Pixel 6 (Android 13)"
     */
    fun getDescription(): String {
        return "$deviceManufacturer $deviceModel (Android $androidVersion)"
    }
}

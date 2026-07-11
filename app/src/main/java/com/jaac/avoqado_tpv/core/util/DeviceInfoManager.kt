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
import java.math.BigInteger
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
        // Demo-build serial override ("Avoqado Demo" / gymDemo flavor): report a specific
        // backend Terminal serial instead of the hardware serial, so the demo build can point
        // at the prod gym venue (avoqado-fitness) without re-flashing the device. Gated behind
        // BuildConfig.DEBUG (a release build ignores it) and OVERRIDE_TERMINAL_SERIAL is "" on
        // every normal flavor → inert everywhere except gymDemoDebug.
        if (com.jaac.avoqado_tpv.BuildConfig.DEBUG && com.jaac.avoqado_tpv.BuildConfig.OVERRIDE_TERMINAL_SERIAL.isNotBlank()) {
            Timber.i("📟 [DemoOverride] Using OVERRIDE_TERMINAL_SERIAL=${com.jaac.avoqado_tpv.BuildConfig.OVERRIDE_TERMINAL_SERIAL}")
            return com.jaac.avoqado_tpv.BuildConfig.OVERRIDE_TERMINAL_SERIAL
        }

        // Emulator/tutorial flavor on emulator: Build.getSerial() is blocked.
        // On real devices (Nexgo, etc.) with PAX SDK disabled, still try hardware serial.
        if (!com.jaac.avoqado_tpv.BuildConfig.ENABLE_PAX_SDK && isEmulator()) {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )?.uppercase() ?: "0"
            val emulatorSerial = generateTutorialSerialSuffix(androidId)
            return "AVQD-$emulatorSerial"
        }

        // Nexgo stores the real serial in ro.ums.manufacturer.info (format: "XGD|新国都|N860W175781|1")
        // Build.getSerial() returns placeholder "11111111111111" on Nexgo devices
        val nexgoSerial = getNexgoSerial()
        if (nexgoSerial != null) {
            Timber.d("📟 Nexgo serial detected: $nexgoSerial")
            return "AVQD-${nexgoSerial.uppercase()}"
        }

        val hardwareSerial = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8+: Requires READ_PHONE_STATE permission
            try {
                Build.getSerial()
            } catch (e: SecurityException) {
                // On non-PAX devices (Nexgo, etc.) permission may not be granted.
                // Fall back to ANDROID_ID to avoid crash.
                Timber.w(e, "⚠️ READ_PHONE_STATE not granted - falling back to ANDROID_ID")
                val androidId = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID
                )?.uppercase() ?: "UNKNOWN"
                androidId
            }
        } else {
            // Android 7 and below: No permission required
            @Suppress("DEPRECATION")
            Build.SERIAL
        }

        return "AVQD-${hardwareSerial.uppercase()}"
    }

    /**
     * Extract serial from Nexgo's manufacturer info property.
     * Format: "XGD|新国都|N860W175781|1" → "N860W175781"
     * Returns null on non-Nexgo devices.
     */
    private fun getNexgoSerial(): String? {
        val model = Build.MODEL?.uppercase() ?: ""
        if (!model.startsWith("N86") && !model.startsWith("N6") && !model.startsWith("N5") && !model.contains("NEXGO") && !model.contains("XGD")) {
            return null
        }
        return try {
            val mfgInfo = Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, "ro.ums.manufacturer.info", "") as? String
            // Format: "XGD|新国都|SERIAL|1"
            val parts = mfgInfo?.split("|")
            val serial = parts?.getOrNull(2)?.trim()
            if (!serial.isNullOrBlank() && serial != "unknown") serial else null
        } catch (e: Exception) {
            Timber.w(e, "⚠️ Could not read Nexgo manufacturer info")
            null
        }
    }

    /** Detect emulator vs real hardware */
    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for") ||
            Build.MANUFACTURER.contains("Genymotion") ||
            Build.PRODUCT.contains("sdk") ||
            Build.PRODUCT.contains("emulator")
    }

    /**
     * Convert emulator ANDROID_ID into a short numeric serial for tutorial screenshots.
     * Keeps format similar to real terminals (10 digits) while remaining deterministic.
     */
    private fun generateTutorialSerialSuffix(androidId: String): String {
        val normalized = androidId.filter { it.isLetterOrDigit() }.ifEmpty { "0" }
        return try {
            BigInteger(normalized, 16)
                .toString(10)
                .takeLast(10)
                .padStart(10, '0')
        } catch (_: NumberFormatException) {
            ((normalized.hashCode().toLong() and 0x7FFFFFFF).toString())
                .takeLast(10)
                .padStart(10, '0')
        }
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

            val response = apiService.checkActivationStatus(
                serialNumber = serialNumber,
                environment = com.jaac.avoqado_tpv.BuildConfig.BLUMON_ENV
            )

            if (response.isSuccessful && response.body() != null) {
                val status = response.body()!!
                Timber.d("✅ Backend activation check: isActivated=${status.isActivated}, status=${status.status}")

                // 🚨 SECURITY: Only clear local data if terminal is RETIRED (stolen/revoked)
                // DO NOT clear on !isActivated - could be temporary server issue
                // This prevents losing venueId when server is down and comes back up
                if (status.status == "RETIRED") {
                    Timber.w("🚨 Terminal RETIRED by administrator - clearing all local data")
                    secureStorage.clearAll()
                } else if (!status.isActivated) {
                    // Terminal not activated but NOT retired - preserve venueId for recovery
                    // User will be sent to Activation screen but can recover when server confirms
                    Timber.w("⚠️ Backend says terminal not activated - preserving venueId for recovery")
                    // NOTE: Do NOT clear secureStorage here - allows recovery when server confirms
                } else {
                    // ✅ Square/Toast pattern: Restore local cache from backend if missing
                    // This handles: app reinstall, cache clear, data migration, variant switch
                    val localVenueId = secureStorage.getVenueId()
                    if (localVenueId == null && status.venueId != null) {
                        Timber.w("🔄 Restoring activation data from backend (local cache was empty)")
                        Timber.d("   → venueId: ${status.venueId}")
                        Timber.d("   → serialNumber: $serialNumber")
                        secureStorage.saveVenueId(status.venueId)
                        secureStorage.saveSerialNumber(serialNumber)
                    }
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

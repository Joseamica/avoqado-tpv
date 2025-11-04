package com.jaac.avoqado_tpv.core.util

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * val serialNumber = deviceInfo.serialNumber // e.g., "AVQD-1A2B3C4D5E6F"
 * ```
 *
 * Security Note:
 * - ANDROID_ID is unique per app installation and persists across app updates
 * - It changes on factory reset or when the app is uninstalled/reinstalled
 * - This is sufficient for terminal activation as terminals are tied to physical devices
 */
@Singleton
class DeviceInfoManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureStorage: SecureStorage
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
     * Format: AVQD-{androidId}
     * Example: AVQD-1A2B3C4D5E6F
     *
     * This is the primary identifier for terminal activation.
     */
    fun getSerialNumber(): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"

        return "AVQD-${androidId.uppercase()}"
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

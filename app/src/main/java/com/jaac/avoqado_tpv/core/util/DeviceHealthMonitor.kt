package com.jaac.avoqado_tpv.core.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device Health Monitor
 *
 * Collects system health metrics for heartbeat reporting.
 * Follows Square POS and Toast POS patterns for device monitoring.
 *
 * **Metrics Collected:**
 * - Battery level and charging status
 * - Storage availability
 * - Memory availability
 * - App uptime
 *
 * **Usage:**
 * ```kotlin
 * val health = deviceHealthMonitor.getSystemHealth()
 * println("Battery: ${health.batteryLevel}%")
 * println("Storage: ${health.storageAvailableGB} GB")
 * ```
 *
 * **Why These Metrics?**
 * - **Battery**: Prevent shutdowns during transactions (Square pattern)
 * - **Storage**: Prevent "disk full" crashes (Toast pattern)
 * - **Memory**: Detect performance issues proactively
 * - **Uptime**: Correlate crashes with restart patterns
 */
@Singleton
class DeviceHealthMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val startTime = System.currentTimeMillis()

    /**
     * Get comprehensive system health metrics
     */
    fun getSystemHealth(): SystemHealth {
        return SystemHealth(
            platform = "Android",
            osVersion = "Android ${Build.VERSION.RELEASE}",
            deviceModel = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            batteryLevel = getBatteryLevel(),
            batteryCharging = isBatteryCharging(),
            storageAvailableGB = getStorageAvailableGB(),
            memoryAvailableMB = getMemoryAvailableMB(),
            uptime = System.currentTimeMillis() - startTime
        )
    }

    /**
     * Get battery level (0-100)
     *
     * Critical for preventing mid-transaction shutdowns.
     * Square triggers warnings at <20%, critical alerts at <10%.
     */
    private fun getBatteryLevel(): Int {
        return try {
            val batteryManager = ContextCompat.getSystemService(context, BatteryManager::class.java)
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        } catch (e: Exception) {
            Timber.w(e, "Failed to get battery level")
            -1 // Unknown
        }
    }

    /**
     * Check if device is charging
     *
     * Used to adjust heartbeat frequency:
     * - Charging + WiFi = 15s intervals (aggressive monitoring)
     * - Not charging + low battery = 120s intervals (conserve power)
     */
    private fun isBatteryCharging(): Boolean {
        return try {
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, intentFilter)

            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        } catch (e: Exception) {
            Timber.w(e, "Failed to check charging status")
            false
        }
    }

    /**
     * Get available storage in GB
     *
     * Prevents "disk full" errors during transaction logging.
     * Toast triggers alerts at <5GB, critical at <1GB.
     */
    private fun getStorageAvailableGB(): Float {
        return try {
            val dataDir = context.filesDir
            val stat = StatFs(dataDir.absolutePath)

            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            val availableGB = availableBytes / (1024f * 1024f * 1024f)

            // Round to 2 decimal places
            (availableGB * 100).toInt() / 100f
        } catch (e: Exception) {
            Timber.w(e, "Failed to get storage info")
            -1f // Unknown
        }
    }

    /**
     * Get available memory in MB
     *
     * Used to detect memory pressure that could cause crashes.
     * Low memory can indicate memory leaks or need to restart app.
     */
    private fun getMemoryAvailableMB(): Long {
        return try {
            val runtime = Runtime.getRuntime()
            val maxMemory = runtime.maxMemory()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val availableMemory = maxMemory - usedMemory

            availableMemory / (1024 * 1024) // Convert to MB
        } catch (e: Exception) {
            Timber.w(e, "Failed to get memory info")
            -1L // Unknown
        }
    }

    /**
     * Check if device health is critical
     *
     * Critical conditions (Toast/Square pattern):
     * - Battery <10% and not charging
     * - Storage <1GB
     * - Memory <100MB
     */
    fun isCriticalHealth(): Boolean {
        val health = getSystemHealth()
        return (health.batteryLevel in 0..10 && !health.batteryCharging) ||
               (health.storageAvailableGB in 0f..1f) ||
               (health.memoryAvailableMB in 0..100)
    }

    /**
     * Check if device health is in warning state
     *
     * Warning conditions:
     * - Battery <20% and not charging
     * - Storage <5GB
     * - Memory <500MB
     */
    fun isWarningHealth(): Boolean {
        val health = getSystemHealth()
        return (health.batteryLevel in 0..20 && !health.batteryCharging) ||
               (health.storageAvailableGB in 0f..5f) ||
               (health.memoryAvailableMB in 0..500)
    }
}

/**
 * System health metrics data class
 *
 * Matches backend HeartbeatData.systemInfo structure
 */
data class SystemHealth(
    val platform: String,              // "Android"
    val osVersion: String,             // "Android 13"
    val deviceModel: String,           // "Google Pixel 6"
    val manufacturer: String,          // "Google"
    val batteryLevel: Int,             // 0-100 (-1 if unknown)
    val batteryCharging: Boolean,
    val storageAvailableGB: Float,     // Available storage in GB
    val memoryAvailableMB: Long,       // Available memory in MB
    val uptime: Long                   // Milliseconds since app start
) {
    /**
     * Get human-readable health status
     */
    fun getHealthStatus(): String {
        return when {
            batteryLevel in 0..10 && !batteryCharging -> "CRITICAL: Battery low"
            storageAvailableGB in 0f..1f -> "CRITICAL: Storage low"
            memoryAvailableMB in 0..100 -> "CRITICAL: Memory low"
            batteryLevel in 0..20 && !batteryCharging -> "WARNING: Battery low"
            storageAvailableGB in 0f..5f -> "WARNING: Storage low"
            memoryAvailableMB in 0..500 -> "WARNING: Memory low"
            else -> "HEALTHY"
        }
    }
}

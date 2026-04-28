package com.jaac.avoqado_tpv.core.observability.monitor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import java.io.File

/**
 * HealthMonitor - Proactive terminal health monitoring and heartbeat
 *
 * Inspired by Square Terminal and Clover device management.
 * Sends periodic health metrics to backend for proactive issue detection.
 *
 * ## Monitored Metrics:
 * - 🧠 Memory: Available RAM, memory pressure
 * - 💾 Storage: Free disk space
 * - 🔋 Battery: Level, charging status, temperature
 * - 📡 Connectivity: Online/offline status, Socket.IO connection
 * - ⏱️ Uptime: App uptime, last payment timestamp
 * - 📱 Device Info: Model, OS version, app version
 *
 * ## Socket.IO Event:
 * Event: `tpv:heartbeat`
 * Interval: Every 5 minutes
 * Payload: { terminalId, venueId, health: {...}, timestamp }
 *
 * ## Health Score Calculation:
 * - **Healthy** (90-100): All systems normal
 * - **Degraded** (70-89): Low memory OR low battery
 * - **Critical** (<70): Multiple issues OR offline >30min
 *
 * @see ObservabilityManager for usage
 */
@Singleton
class HealthMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val socketManager: SocketManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitoringJob: Job? = null
    private var venueId: String? = null
    private var terminalId: String? = null

    // Configuration
    private val heartbeatIntervalMs = 5 * 60 * 1000L // 5 minutes
    private var appStartTime = System.currentTimeMillis()

    fun startMonitoring(venueId: String, terminalId: String) {
        this.venueId = venueId
        this.terminalId = terminalId
        this.appStartTime = System.currentTimeMillis()

        stopMonitoring() // Stop existing job if any

        monitoringJob = scope.launch {
            while (isActive) {
                try {
                    sendHeartbeat()
                } catch (e: Exception) {
                    Timber.e(e, "Failed to send heartbeat")
                }
                delay(heartbeatIntervalMs)
            }
        }

        Timber.d("HealthMonitor started for terminal $terminalId")
    }

    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        Timber.d("HealthMonitor stopped")
    }

    private suspend fun sendHeartbeat() {
        val healthData = collectHealthData()
        val healthScore = calculateHealthScore(healthData)

        val payload = mapOf(
            "terminalId" to terminalId,
            "venueId" to venueId,
            "health" to healthData,
            "healthScore" to healthScore,
            "timestamp" to System.currentTimeMillis()
        )

        try {
            socketManager.emit("tpv:heartbeat", payload)
            Timber.d("Heartbeat sent: score=$healthScore")
        } catch (e: Exception) {
            Timber.e(e, "Failed to send heartbeat")
        }
    }

    private fun collectHealthData(): Map<String, Any?> {
        return mapOf(
            "memory" to getMemoryInfo(),
            "storage" to getStorageInfo(),
            "battery" to getBatteryInfo(),
            "connectivity" to getConnectivityInfo(),
            "device" to getDeviceInfo(),
            "uptime" to getUptimeInfo()
        )
    }

    private fun getMemoryInfo(): Map<String, Any> {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val totalMB = memoryInfo.totalMem / (1024 * 1024)
        val availableMB = memoryInfo.availMem / (1024 * 1024)
        val usedMB = totalMB - availableMB
        val usagePercent = (usedMB.toFloat() / totalMB * 100).toInt()

        return mapOf(
            "totalMB" to totalMB,
            "availableMB" to availableMB,
            "usedMB" to usedMB,
            "usagePercent" to usagePercent,
            "lowMemory" to memoryInfo.lowMemory
        )
    }

    private fun getStorageInfo(): Map<String, Any> {
        val stat = StatFs(context.filesDir.path)
        val totalBytes = stat.blockCountLong * stat.blockSizeLong
        val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
        val usedBytes = totalBytes - availableBytes

        val totalMB = totalBytes / (1024 * 1024)
        val availableMB = availableBytes / (1024 * 1024)
        val usagePercent = (usedBytes.toFloat() / totalBytes * 100).toInt()

        return mapOf(
            "totalMB" to totalMB,
            "availableMB" to availableMB,
            "usagePercent" to usagePercent,
            "lowStorage" to (availableMB < 100) // < 100MB
        )
    }

    private fun getBatteryInfo(): Map<String, Any?> {
        val batteryIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPercent = if (level >= 0 && scale > 0) {
            (level.toFloat() / scale * 100).toInt()
        } else {
            null
        }

        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val temperature = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        val tempCelsius = if (temperature != null && temperature > 0) {
            temperature / 10.0f
        } else {
            null
        }

        return mapOf(
            "level" to batteryPercent,
            "isCharging" to isCharging,
            "temperatureCelsius" to tempCelsius,
            "lowBattery" to (batteryPercent != null && batteryPercent < 20)
        )
    }

    private fun getConnectivityInfo(): Map<String, Any> {
        return mapOf(
            "socketConnected" to socketManager.isCurrentlyConnected(),
            "online" to true // TODO: Add network connectivity check
        )
    }

    private fun getDeviceInfo(): Map<String, Any> {
        return mapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "osVersion" to Build.VERSION.RELEASE,
            "sdkInt" to Build.VERSION.SDK_INT,
            "appVersion" to BuildConfig.VERSION_NAME,
            "appVersionCode" to BuildConfig.VERSION_CODE,
            "blumonEnv" to BuildConfig.BLUMON_ENV
        )
    }

    private fun getUptimeInfo(): Map<String, Any> {
        val uptime = System.currentTimeMillis() - appStartTime
        val uptimeMinutes = uptime / (60 * 1000)

        return mapOf(
            "uptimeMs" to uptime,
            "uptimeMinutes" to uptimeMinutes,
            "startedAt" to appStartTime
        )
    }

    /**
     * Calculate overall health score (0-100)
     *
     * Scoring:
     * - Memory usage > 90% → -20 points
     * - Memory usage > 80% → -10 points
     * - Battery < 20% and not charging → -20 points
     * - Battery < 10% → -30 points
     * - Storage < 100MB → -15 points
     * - Socket disconnected → -10 points
     * - Low memory warning → -20 points
     */
    private fun calculateHealthScore(health: Map<String, Any?>): Int {
        var score = 100

        // Memory penalties
        val memory = health["memory"] as? Map<*, *>
        val memoryUsage = memory?.get("usagePercent") as? Int ?: 0
        val lowMemory = memory?.get("lowMemory") as? Boolean ?: false

        if (lowMemory) score -= 20
        else if (memoryUsage > 90) score -= 20
        else if (memoryUsage > 80) score -= 10

        // Storage penalties
        val storage = health["storage"] as? Map<*, *>
        val lowStorage = storage?.get("lowStorage") as? Boolean ?: false
        if (lowStorage) score -= 15

        // Battery penalties
        val battery = health["battery"] as? Map<*, *>
        val batteryLevel = battery?.get("level") as? Int
        val isCharging = battery?.get("isCharging") as? Boolean ?: false
        val lowBattery = battery?.get("lowBattery") as? Boolean ?: false

        if (batteryLevel != null) {
            if (batteryLevel < 10) score -= 30
            else if (lowBattery && !isCharging) score -= 20
        }

        // Connectivity penalties
        val connectivity = health["connectivity"] as? Map<*, *>
        val socketConnected = connectivity?.get("socketConnected") as? Boolean ?: false
        if (!socketConnected) score -= 10

        return score.coerceIn(0, 100)
    }
}

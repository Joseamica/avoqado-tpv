package com.jaac.avoqado_tpv.core.data.network.dto

import com.google.gson.annotations.SerializedName
import com.jaac.avoqado_tpv.core.domain.models.Heartbeat
import com.jaac.avoqado_tpv.core.domain.models.TerminalStatus
import com.jaac.avoqado_tpv.core.util.NetworkInfo
import com.jaac.avoqado_tpv.core.util.NetworkType
import com.jaac.avoqado_tpv.core.util.SystemHealth

/**
 * Heartbeat Request DTO
 *
 * Matches backend HeartbeatData interface in src/services/tpv/tpv-health.service.ts
 *
 * **Backend Contract:**
 * ```typescript
 * interface HeartbeatData {
 *   terminalId: string
 *   timestamp: string
 *   status: 'ACTIVE' | 'MAINTENANCE'
 *   version?: string
 *   systemInfo?: {
 *     platform?: string
 *     memory?: any
 *     uptime?: number
 *     [key: string]: any
 *   }
 * }
 * ```
 *
 * **Extended with Network Info:**
 * We send additional network metrics for future analytics.
 * Backend will accept and store them in systemInfo JSON field.
 */
data class HeartbeatRequestDto(
    @SerializedName("terminalId")
    val terminalId: String,

    @SerializedName("timestamp")
    val timestamp: String,

    @SerializedName("status")
    val status: String, // "ACTIVE" or "MAINTENANCE"

    @SerializedName("version")
    val version: String?,

    @SerializedName("systemInfo")
    val systemInfo: SystemInfoDto?
)

/**
 * Memory Info DTO
 *
 * Detailed memory metrics for dashboard display.
 * Matches frontend expectation: { total, used, free }
 */
data class MemoryDto(
    @SerializedName("total")
    val total: Long,  // Total memory in MB

    @SerializedName("used")
    val used: Long,   // Used memory in MB

    @SerializedName("free")
    val free: Long    // Free memory in MB
)

/**
 * System Info DTO
 *
 * Extended system information including device health + network metrics.
 * Backend stores this as JSON in Terminal.systemInfo field.
 *
 * **Changes:**
 * - Memory now sends { total, used, free } instead of single memoryAvailableMB
 * - Uptime is in SECONDS (not milliseconds) for frontend compatibility
 */
data class SystemInfoDto(
    @SerializedName("platform")
    val platform: String,

    @SerializedName("osVersion")
    val osVersion: String,

    @SerializedName("deviceModel")
    val deviceModel: String,

    @SerializedName("manufacturer")
    val manufacturer: String,

    @SerializedName("batteryLevel")
    val batteryLevel: Int,

    @SerializedName("batteryCharging")
    val batteryCharging: Boolean,

    @SerializedName("storageAvailableGB")
    val storageAvailableGB: Float,

    @SerializedName("memory")
    val memory: MemoryDto,  // Changed from memoryAvailableMB to full memory object

    @SerializedName("uptime")
    val uptime: Long,  // In SECONDS (converted from milliseconds)

    // Network metrics (extension)
    @SerializedName("networkType")
    val networkType: String,

    @SerializedName("networkMetered")
    val networkMetered: Boolean,

    @SerializedName("networkConnected")
    val networkConnected: Boolean,

    @SerializedName("signalStrength")
    val signalStrength: Int?
)

/**
 * Heartbeat Response DTO
 *
 * Matches backend response from POST /tpv/heartbeat
 */
data class HeartbeatResponseDto(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("message")
    val message: String,

    @SerializedName("serverStatus")
    val serverStatus: String?, // Server's view of terminal status

    @SerializedName("timestamp")
    val timestamp: String
)

// ========== Mappers ==========

/**
 * Convert domain Heartbeat to DTO for API request
 *
 * **Transformations:**
 * - Memory: Split into { total, used, free } object
 * - Uptime: Convert from milliseconds to seconds for frontend
 */
fun Heartbeat.toDto(): HeartbeatRequestDto {
    return HeartbeatRequestDto(
        terminalId = terminalId,
        timestamp = timestamp,
        status = status.toApiString(),
        version = version,
        systemInfo = SystemInfoDto(
            platform = systemInfo.platform,
            osVersion = systemInfo.osVersion,
            deviceModel = systemInfo.deviceModel,
            manufacturer = systemInfo.manufacturer,
            batteryLevel = systemInfo.batteryLevel,
            batteryCharging = systemInfo.batteryCharging,
            storageAvailableGB = systemInfo.storageAvailableGB,
            memory = MemoryDto(
                total = systemInfo.memoryInfo.totalMB,
                used = systemInfo.memoryInfo.usedMB,
                free = systemInfo.memoryInfo.freeMB
            ),
            uptime = systemInfo.uptime / 1000,  // Convert ms to seconds
            networkType = networkInfo.type.name,
            networkMetered = networkInfo.isMetered,
            networkConnected = networkInfo.isConnected,
            signalStrength = networkInfo.signalStrength
        )
    )
}

/**
 * Convert TerminalStatus enum to API string
 * Backend expects: "ACTIVE" or "MAINTENANCE"
 */
fun TerminalStatus.toApiString(): String {
    return when (this) {
        TerminalStatus.ACTIVE -> "ACTIVE"
        TerminalStatus.IDLE -> "ACTIVE" // Treat IDLE as ACTIVE for backend
        TerminalStatus.MAINTENANCE -> "MAINTENANCE"
    }
}

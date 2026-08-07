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
    val systemInfo: SystemInfoDto?,

    // 📊 Task 6 (plan `2026-08-04-event-loop-no-bloqueante-reportes`) — local telemetry
    // of card-authorization attempts, batched on-device and attached here ONLY when no
    // charge is in flight (see HeartbeatRepository.sendHeartbeat). Additive + defaulted
    // to null so old callers of this constructor (and old TPV builds the backend still
    // supports) are unaffected. NEVER card data / amounts / references — see
    // AuthAttemptDto's own KDoc.
    @SerializedName("authAttempts")
    val authAttempts: List<AuthAttemptDto>? = null
)

/**
 * ONE recorded authorization attempt, wire-shape for the heartbeat request.
 *
 * PRIVACY: only a short result/error [code], the call's [durationMs], the [rail]
 * ("BLUMON" | "ANGELPAY"), and an ISO-8601 [timestamp]. Mirrors
 * `com.jaac.avoqado_tpv.features.payment.data.local.AuthAttemptRecord` field-for-field
 * — see that class's KDoc for why nothing else may ever be added here.
 */
data class AuthAttemptDto(
    @SerializedName("code")
    val code: String,

    @SerializedName("durationMs")
    val durationMs: Long,

    @SerializedName("rail")
    val rail: String,

    @SerializedName("timestamp")
    val timestamp: String
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
    val signalStrength: Int?,

    // Version code (for downgrade detection in dashboard)
    // Added for INSTALL_VERSION command to detect downgrades
    @SerializedName("versionCode")
    val versionCode: Int
)

/**
 * Pending Command DTO
 *
 * Command delivered via heartbeat response (Square Terminal API polling pattern).
 * Terminal doesn't need socket connection to receive commands.
 */
data class PendingCommandDto(
    @SerializedName("commandId")
    val commandId: String,

    @SerializedName("correlationId")
    val correlationId: String,

    @SerializedName("type")
    val type: String, // LOCK, UNLOCK, MAINTENANCE_MODE, EXIT_MAINTENANCE, etc.

    // Using JsonObject instead of Map<String, Any> to avoid Gson deserialization issues
    // Gson can't properly deserialize Map<String, Any> because it doesn't know what types to use
    @SerializedName("payload")
    val payload: com.google.gson.JsonObject?,

    @SerializedName("priority")
    val priority: String, // LOW, NORMAL, HIGH, CRITICAL

    @SerializedName("requiresPin")
    val requiresPin: Boolean,

    @SerializedName("expiresAt")
    val expiresAt: String?,

    @SerializedName("requestedBy")
    val requestedBy: String,

    @SerializedName("requestedByName")
    val requestedByName: String?,

    @SerializedName("createdAt")
    val createdAt: String
)

/**
 * Force Update DTO
 *
 * Included in heartbeat response when there's a FORCE update that cannot be bypassed.
 * Terminal MUST install this update - user cannot dismiss or ignore it.
 *
 * **Backend Enforcement Pattern:**
 * This is included in EVERY heartbeat response (every 30 seconds) until the
 * terminal updates. This prevents users from bypassing forced updates.
 */
data class ForceUpdateDto(
    @SerializedName("versionName")
    val versionName: String,

    @SerializedName("versionCode")
    val versionCode: Int,

    @SerializedName("downloadUrl")
    val downloadUrl: String,

    @SerializedName("releaseNotes")
    val releaseNotes: String?,

    @SerializedName("updateMode")
    val updateMode: String  // Always "FORCE" for this DTO
)

/**
 * Heartbeat Response DTO
 *
 * Matches backend response from POST /tpv/heartbeat
 *
 * **Square/Toast Pattern:** Includes pending commands for polling-based delivery.
 * Terminal receives commands via HTTP heartbeat response instead of socket.
 */
data class HeartbeatResponseDto(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("message")
    val message: String,

    @SerializedName("serverStatus")
    val serverStatus: String?, // Server's view of terminal status

    @SerializedName("timestamp")
    val timestamp: String,

    // Square/Toast pattern: Commands delivered via heartbeat response
    @SerializedName("pendingCommands")
    val pendingCommands: List<PendingCommandDto>?,

    // 🚨 Backend Enforcement: Force update that cannot be bypassed
    // When present, terminal MUST show ForceUpdateDialog until update is installed
    @SerializedName("forceUpdate")
    val forceUpdate: ForceUpdateDto?
)

/**
 * Command Acknowledgment Request DTO
 *
 * Sent to backend after processing a command received via heartbeat.
 *
 * **Security: Terminal Ownership Validation**
 * The terminalId is required so backend can verify that the terminal
 * sending the ACK is the one that owns the command.
 * This prevents attackers from spoofing ACKs for commands they don't own.
 */
data class CommandAckRequestDto(
    @SerializedName("commandId")
    val commandId: String,

    @SerializedName("terminalId")
    val terminalId: String, // Required for security validation

    @SerializedName("resultStatus")
    val resultStatus: String, // SUCCESS, FAILED, REJECTED, TIMEOUT

    @SerializedName("resultMessage")
    val resultMessage: String?,

    @SerializedName("resultPayload")
    val resultPayload: Map<String, Any>?
)

/**
 * Command Acknowledgment Response DTO
 *
 * Backend response after receiving command ACK.
 */
data class CommandAckResponseDto(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("message")
    val message: String,

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
            signalStrength = networkInfo.signalStrength,
            versionCode = versionCode  // For dashboard downgrade detection
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

/**
 * Convert PendingCommandDto to TpvCommand domain model
 *
 * **Transformations:**
 * - type: String → TpvCommandType enum (with null fallback for unknown types)
 * - priority: String → TpvCommandPriority enum
 * - expiresAt: String? → Instant (defaults to 5 min from now if null)
 *
 * @return TpvCommand if type is valid, null if unknown command type
 */
fun PendingCommandDto.toTpvCommand(): com.jaac.avoqado_tpv.features.remote_command.data.model.TpvCommand? {
    val commandType = com.jaac.avoqado_tpv.features.remote_command.data.model.TpvCommandType.fromString(type)
        ?: return null  // Unknown command type, skip

    val priority = com.jaac.avoqado_tpv.features.remote_command.data.model.TpvCommandPriority.fromString(priority)

    // Parse expiry time (default to 5 minutes from now if not specified)
    val expiresAtInstant = try {
        if (expiresAt != null) {
            java.time.Instant.parse(expiresAt)
        } else {
            java.time.Instant.now().plusSeconds(300) // 5 minutes default
        }
    } catch (e: Exception) {
        java.time.Instant.now().plusSeconds(300) // Fallback on parse error
    }

    // Convert JsonObject to Map<String, Any>? for domain model
    val payloadMap: Map<String, Any>? = payload?.let { jsonObj ->
        val map = mutableMapOf<String, Any>()
        for (entry in jsonObj.entrySet()) {
            val value = when {
                entry.value.isJsonPrimitive -> {
                    val primitive = entry.value.asJsonPrimitive
                    when {
                        primitive.isBoolean -> primitive.asBoolean
                        primitive.isNumber -> primitive.asNumber
                        primitive.isString -> primitive.asString
                        else -> primitive.toString()
                    }
                }
                entry.value.isJsonNull -> null
                else -> entry.value.toString() // For nested objects/arrays, keep as string
            }
            if (value != null) {
                map[entry.key] = value
            }
        }
        if (map.isEmpty()) null else map
    }

    return com.jaac.avoqado_tpv.features.remote_command.data.model.TpvCommand(
        commandId = commandId,
        correlationId = correlationId,
        type = commandType,
        payload = payloadMap,
        requiresPin = requiresPin,
        priority = priority,
        expiresAt = expiresAtInstant,
        requestedBy = requestedBy,
        requestedByName = requestedByName
    )
}

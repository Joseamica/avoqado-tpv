package com.jaac.avoqado_tpv.features.remote_command.data.model

import java.time.Instant

/**
 * TPV Command - Remote command from dashboard/server
 *
 * Matches server payload from `tpv_command` Socket.IO event.
 * @see avoqado-server/src/services/tpv/command-execution.service.ts:CommandPayload
 */
data class TpvCommand(
    val commandId: String,
    val correlationId: String,
    val type: TpvCommandType,
    val payload: Map<String, Any>?,
    val requiresPin: Boolean,
    val priority: TpvCommandPriority,
    val expiresAt: Instant,
    val requestedBy: String,
    val requestedByName: String?
)

/**
 * Command types matching server enum `TpvCommandType`
 * @see avoqado-server/prisma/schema.prisma:TpvCommandType
 */
enum class TpvCommandType {
    // Device State (6)
    LOCK,
    UNLOCK,
    MAINTENANCE_MODE,
    EXIT_MAINTENANCE,
    REACTIVATE,
    REMOTE_ACTIVATE,  // Remote activation by SUPERADMIN (pre-registered terminal)

    // App Lifecycle (4)
    RESTART,
    SHUTDOWN,
    CLEAR_CACHE,
    FORCE_UPDATE,

    // Data Management (3)
    SYNC_DATA,
    FACTORY_RESET,
    EXPORT_LOGS,

    // Configuration (3)
    UPDATE_CONFIG,
    REFRESH_MENU,
    UPDATE_MERCHANT,

    // Automation (3) - Server-side only, but included for completeness
    SCHEDULE,
    GEOFENCE_TRIGGER,
    TIME_RULE;

    companion object {
        /**
         * Parse command type from string (case-insensitive)
         * Returns null if unknown type (graceful degradation)
         */
        fun fromString(value: String): TpvCommandType? {
            return entries.find { it.name.equals(value, ignoreCase = true) }
        }
    }
}

/**
 * Command priority levels
 * @see avoqado-server/prisma/schema.prisma:TpvCommandPriority
 */
enum class TpvCommandPriority {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL;

    companion object {
        fun fromString(value: String): TpvCommandPriority {
            return entries.find { it.name.equals(value, ignoreCase = true) }
                ?: NORMAL
        }
    }
}

/**
 * Command execution result status
 * @see avoqado-server/prisma/schema.prisma:TpvCommandResultStatus
 */
enum class TpvCommandResultStatus {
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILED,
    TIMEOUT,
    REJECTED
}

/**
 * Result of command execution
 */
data class CommandResult(
    val status: TpvCommandResultStatus,
    val message: String?,
    val data: Map<String, Any>? = null
) {
    companion object {
        fun success(message: String, data: Map<String, Any>? = null) =
            CommandResult(TpvCommandResultStatus.SUCCESS, message, data)

        fun failed(message: String, data: Map<String, Any>? = null) =
            CommandResult(TpvCommandResultStatus.FAILED, message, data)

        fun rejected(message: String) =
            CommandResult(TpvCommandResultStatus.REJECTED, message)

        fun timeout(message: String = "Command execution timed out") =
            CommandResult(TpvCommandResultStatus.TIMEOUT, message)
    }
}

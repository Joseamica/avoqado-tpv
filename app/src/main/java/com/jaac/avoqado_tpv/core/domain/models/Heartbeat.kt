package com.jaac.avoqado_tpv.core.domain.models

import com.jaac.avoqado_tpv.core.util.NetworkInfo
import com.jaac.avoqado_tpv.core.util.SystemHealth
import java.time.Instant

/**
 * Heartbeat Domain Model
 *
 * Represents a terminal health report sent to the backend.
 * Matches backend HeartbeatData interface in tpv-health.service.ts
 *
 * **Design Pattern:** Rich domain model (Square/Toast pattern)
 * - Not just a "ping" - includes actionable metrics
 * - Backend can proactively detect issues before they cause downtime
 *
 * **Example:**
 * ```kotlin
 * val heartbeat = Heartbeat(
 *     terminalId = "6d52cb5103bb42dc",
 *     timestamp = Instant.now().toString(),
 *     status = TerminalStatus.ACTIVE,
 *     version = "1.0.0",
 *     systemInfo = systemHealth,
 *     networkInfo = networkInfo
 * )
 * ```
 */
data class Heartbeat(
    /**
     * Terminal identifier (serial number)
     * Format: "6d52cb5103bb42dc" (NOT "AVQD-..." prefix)
     *
     * Backend accepts:
     * 1. Internal database ID
     * 2. Serial number (this)
     * 3. Menta terminal ID (legacy)
     */
    val terminalId: String,

    /**
     * Timestamp in ISO 8601 UTC format
     * Example: "2025-11-03T12:30:00.000Z"
     */
    val timestamp: String,

    /**
     * Current terminal status
     */
    val status: TerminalStatus,

    /**
     * App version (semver)
     * Example: "1.0.0"
     */
    val version: String,

    /**
     * System health metrics
     * Includes battery, storage, memory, uptime
     */
    val systemInfo: SystemHealth,

    /**
     * Network information
     * Includes type, quality, metered status
     */
    val networkInfo: NetworkInfo
)

/**
 * Terminal Status Enumeration
 *
 * Matches backend TerminalStatus enum
 */
enum class TerminalStatus {
    /**
     * Terminal is actively processing orders
     */
    ACTIVE,

    /**
     * Terminal is idle (no activity for 15+ minutes)
     * Not implemented in Phase 1 - always send ACTIVE
     */
    IDLE,

    /**
     * Terminal is in maintenance mode (manually set)
     */
    MAINTENANCE
}

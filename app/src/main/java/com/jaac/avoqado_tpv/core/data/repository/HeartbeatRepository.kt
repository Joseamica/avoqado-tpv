package com.jaac.avoqado_tpv.core.data.repository

import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.dto.AuthAttemptDto
import com.jaac.avoqado_tpv.core.data.network.dto.CommandAckRequestDto
import com.jaac.avoqado_tpv.core.data.network.dto.HeartbeatResponseDto
import com.jaac.avoqado_tpv.core.data.network.dto.toDto
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Heartbeat
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.features.payment.data.local.AuthAttemptRecord
import com.jaac.avoqado_tpv.features.payment.data.local.AuthAttemptTelemetryStore
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateProvider
import com.jaac.avoqado_tpv.features.remote_command.data.model.CommandResult
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Wire-shape mapper — kept next to its only caller (sendHeartbeat) rather than in the
 *  DTO file, so `core.data.network.dto` doesn't take a dependency on `features.payment`. */
private fun AuthAttemptRecord.toDto(): AuthAttemptDto = AuthAttemptDto(
    code = code,
    durationMs = durationMs,
    rail = rail,
    timestamp = timestamp,
)

/**
 * Heartbeat Repository
 *
 * Handles sending device health metrics to the backend.
 * Follows offline-first pattern (Toast POS architecture).
 *
 * **Responsibilities:**
 * - Send heartbeat to backend API
 * - Handle network errors gracefully
 * - Return server status for synchronization
 *
 * **Phase 1:** Simple HTTP POST implementation
 * **Phase 2:** Add offline queue + batch sync (Future)
 *
 * **Usage:**
 * ```kotlin
 * val result = heartbeatRepository.sendHeartbeat(heartbeat)
 * when (result) {
 *     is Result.Success -> {
 *         Timber.d("Heartbeat sent: ${result.data.serverStatus}")
 *     }
 *     is Result.Error -> {
 *         Timber.w("Heartbeat failed: ${result.exception.message}")
 *         // WorkManager will retry automatically
 *     }
 * }
 * ```
 */
@Singleton
class HeartbeatRepository @Inject constructor(
    private val apiService: ApiService,
    // 📊 Task 6 — local batch of card-authorization-attempt telemetry, attached below
    // ONLY when no charge is in flight. Never its own network call — purely additive
    // to the heartbeat this repository already sends.
    private val authAttemptTelemetryStore: AuthAttemptTelemetryStore,
    // 🔒 Same "is a charge in progress?" signal both payment rails already publish
    // (PaymentStateHolder, via this interface) — reused here rather than inventing a
    // second one. See PaymentStateProvider's KDoc.
    private val paymentStateProvider: PaymentStateProvider
) {

    /**
     * Send heartbeat to backend
     *
     * **Network Failure Handling:**
     * - Returns Result.Error on network failures
     * - WorkManager handles exponential backoff retry
     * - Terminal continues to function offline
     *
     * **Success Response:**
     * - Backend confirms heartbeat received
     * - Returns server's view of terminal status
     * - Can detect status mismatches (e.g., server set to MAINTENANCE)
     *
     * @param heartbeat Domain model with health metrics
     * @return Result with server response or error
     */
    suspend fun sendHeartbeat(heartbeat: Heartbeat): Result<HeartbeatResponseDto> {
        return try {
            Timber.d("📡 Sending heartbeat for terminal ${heartbeat.terminalId}")

            // 📊 Task 6 — attach the locally-batched auth-attempt telemetry, if any.
            // batchForHeartbeat already enforces "never while a charge is active" AND
            // "never an empty list" (null either way) — this call is the ONLY place
            // that invariant is read, so a heartbeat that fires mid-charge goes out
            // with authAttempts = null, same as if the buffer were empty.
            val telemetryBatch = authAttemptTelemetryStore.batchForHeartbeat(
                chargeInProgress = paymentStateProvider.isCharging()
            )
            val request = heartbeat.toDto().copy(
                authAttempts = telemetryBatch?.map { it.toDto() }
            )
            val response = apiService.sendHeartbeat(request)

            if (response.isSuccessful && response.body() != null) {
                val responseBody = response.body()!!
                Timber.d("✅ Heartbeat accepted. Server status: ${responseBody.serverStatus}")

                // 🔍 DEBUG: Log raw response body to see what backend actually sent
                // Note: response.raw() gives us the Request/Response info, not the body
                Timber.d("📥 Raw response info: ${response.raw()}")
                Timber.d("📥 Response headers: ${response.headers()}")

                // 🔍 DEBUG: Log what Gson actually deserialized for pendingCommands
                Timber.d("📥 Heartbeat response - success: ${responseBody.success}")
                Timber.d("📥 Heartbeat response - message: ${responseBody.message}")
                Timber.d("📥 Heartbeat response - serverStatus: ${responseBody.serverStatus}")
                Timber.d("📥 Heartbeat response - timestamp: ${responseBody.timestamp}")
                Timber.d("📥 Heartbeat response - pendingCommands: ${responseBody.pendingCommands}")
                Timber.d("📥 Heartbeat response - pendingCommands size: ${responseBody.pendingCommands?.size ?: 0}")
                if (!responseBody.pendingCommands.isNullOrEmpty()) {
                    responseBody.pendingCommands.forEachIndexed { index, cmd ->
                        Timber.d("📥 Command[$index]: type=${cmd.type}, id=${cmd.commandId}, priority=${cmd.priority}")
                    }
                }

                Result.Success(responseBody)
            } else {
                // Read error body for detailed error message (e.g., "Terminal not found")
                val errorBody = try {
                    response.errorBody()?.string() ?: response.message()
                } catch (e: Exception) {
                    response.message()
                }
                Timber.w("⚠️ Heartbeat rejected: HTTP ${response.code()} - $errorBody")
                Result.Error(ApiException.HttpError(response.code(), errorBody))
            }
        } catch (e: Exception) {
            // Network errors, JSON parsing errors, etc.
            Timber.w(e, "❌ Heartbeat failed")
            Result.Error(ApiException.NetworkError(e))
        }
    }

    /**
     * Build heartbeat from current device state
     *
     * **Design Pattern:** Factory method in repository
     * - Keeps domain logic separate from data layer
     * - Worker just calls this method, doesn't need to know how to build heartbeat
     */
    // Note: This will be moved to HeartbeatWorker for cleaner separation

    /**
     * Send command acknowledgment to backend
     *
     * **Square Terminal API Pattern:**
     * Commands are received via heartbeat response (polling).
     * After executing a command, terminal sends ACK via HTTP (not socket).
     *
     * **Why HTTP instead of Socket?**
     * - Works even when socket is not connected (login screen)
     * - More reliable delivery (HTTP retry vs socket fire-and-forget)
     * - Follows proven Square/Toast enterprise patterns
     *
     * **Security: Terminal Ownership Validation**
     * The terminalId is required so backend can verify that the terminal
     * sending the ACK is the one that owns the command.
     *
     * @param commandId The command ID to acknowledge
     * @param terminalId The terminal's serial number (for security validation)
     * @param result The execution result from CommandExecutor
     * @return Result indicating success or error
     */
    suspend fun sendCommandAck(commandId: String, terminalId: String, result: CommandResult): Result<Unit> {
        return try {
            Timber.d("📤 Sending command ACK: $commandId → ${result.status.name} (terminal: $terminalId)")

            val request = CommandAckRequestDto(
                commandId = commandId,
                terminalId = terminalId,
                resultStatus = result.status.name,
                resultMessage = result.message,
                resultPayload = result.data
            )

            val response = apiService.sendCommandAck(request)

            if (response.isSuccessful) {
                Timber.d("✅ Command ACK accepted for: $commandId")
                Result.Success(Unit)
            } else {
                val errorBody = try {
                    response.errorBody()?.string() ?: response.message()
                } catch (e: Exception) {
                    response.message()
                }
                Timber.w("⚠️ Command ACK rejected: HTTP ${response.code()} - $errorBody")
                Result.Error(ApiException.HttpError(response.code(), errorBody))
            }
        } catch (e: Exception) {
            Timber.w(e, "❌ Command ACK failed for: $commandId")
            Result.Error(ApiException.NetworkError(e))
        }
    }
}

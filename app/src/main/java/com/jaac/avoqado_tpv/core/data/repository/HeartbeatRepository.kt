package com.jaac.avoqado_tpv.core.data.repository

import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.dto.HeartbeatResponseDto
import com.jaac.avoqado_tpv.core.data.network.dto.toDto
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Heartbeat
import com.jaac.avoqado_tpv.core.domain.models.Result
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

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
    private val apiService: ApiService
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

            val request = heartbeat.toDto()
            val response = apiService.sendHeartbeat(request)

            if (response.isSuccessful && response.body() != null) {
                val responseBody = response.body()!!
                Timber.d("✅ Heartbeat accepted. Server status: ${responseBody.serverStatus}")
                Result.Success(responseBody)
            } else {
                Timber.w("⚠️ Heartbeat rejected: HTTP ${response.code()}")
                Result.Error(ApiException.HttpError(response.code(), response.message()))
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
}

package com.jaac.avoqado_tpv.core.data.repository

import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.dto.PromoterLocationPingRequestDto
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Promoter Location Repository ("cambaceo")
 *
 * Sends the hourly location ping captured by PromoterLocationWorker.
 * One ping per worker run; no local queue (spec v1 — WorkManager backoff
 * covers transient network failures).
 *
 * Error taxonomy (the worker depends on it):
 * - [Result.Success] -> ping stored
 * - [ApiException.NetworkError] -> transient, worker retries
 * - [ApiException.HttpError] (e.g. 403 = venue flag off server-side) -> worker
 *   must NOT retry; it no-ops until the next scheduled run
 */
@Singleton
class PromoterLocationRepository @Inject constructor(
    private val apiService: ApiService,
) {

    suspend fun sendPing(
        latitude: Double,
        longitude: Double,
        accuracy: Float?,
        capturedAt: Instant,
    ): Result<Unit> {
        return try {
            val response = apiService.sendPromoterLocationPing(
                PromoterLocationPingRequestDto(
                    latitude = latitude,
                    longitude = longitude,
                    accuracy = accuracy,
                    capturedAt = capturedAt.toString(),
                ),
            )
            if (response.isSuccessful) {
                Timber.d("📍 Promoter ping accepted")
                Result.Success(Unit)
            } else {
                val errorBody = try {
                    response.errorBody()?.string() ?: response.message()
                } catch (e: Exception) {
                    response.message()
                }
                Timber.w("⚠️ Promoter ping rejected: HTTP ${response.code()} - $errorBody")
                Result.Error(ApiException.HttpError(response.code(), errorBody))
            }
        } catch (e: Exception) {
            Timber.w(e, "📴 Promoter ping failed (network)")
            Result.Error(ApiException.NetworkError(e))
        }
    }
}

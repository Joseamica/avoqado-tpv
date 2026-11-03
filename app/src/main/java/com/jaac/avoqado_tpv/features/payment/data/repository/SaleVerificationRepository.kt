package com.jaac.avoqado_tpv.features.payment.data.repository

import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.PendingVerificationDto
import com.jaac.avoqado_tpv.core.data.network.VerificationDetailDto
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SaleVerificationRepository @Inject constructor(
    private val apiService: ApiService
) {
    suspend fun getPendingVerifications(): Result<List<PendingVerificationDto>> {
        return try {
            val response = apiService.getPendingVerifications()
            if (response.isSuccessful) {
                val body = response.body()
                if (body?.success == true) {
                    Result.success(body.data)
                } else {
                    Result.failure(Exception("Failed to get pending verifications"))
                }
            } else {
                Result.failure(Exception("HTTP ${response.code()}: ${response.message()}"))
            }
        } catch (e: Exception) {
            Timber.e(e, "📸 [SaleVerificationRepo] Error getting pending verifications")
            Result.failure(e)
        }
    }

    /**
     * Fetch a single verification owned by the current staff member.
     * Used by the sale-correction flow to re-upload rejected documentation.
     */
    suspend fun getVerificationDetail(verificationId: String): Result<VerificationDetailDto> {
        return try {
            val response = apiService.getVerificationDetail(verificationId)
            if (response.isSuccessful) {
                val body = response.body()
                val data = body?.data
                if (body?.success == true && data != null) {
                    Result.success(data)
                } else {
                    Result.failure(Exception("Failed to get verification detail"))
                }
            } else {
                Result.failure(Exception("HTTP ${response.code()}: ${response.message()}"))
            }
        } catch (e: Exception) {
            Timber.e(e, "📸 [SaleVerificationRepo] Error getting verification detail")
            Result.failure(e)
        }
    }
    // La subida de fotos ya no vive aquí: «Pendientes» y la corrección encolan en ProofOfSaleQueueRepository (C6).
}

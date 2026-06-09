package com.jaac.avoqado_tpv.features.sim_custody.data.repository

import android.util.Log
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.features.sim_custody.data.dto.AcceptRequestDto
import com.jaac.avoqado_tpv.features.sim_custody.data.dto.BulkResultRowDto
import com.jaac.avoqado_tpv.features.sim_custody.data.dto.BulkSimCustodyResponseDto
import com.jaac.avoqado_tpv.features.sim_custody.data.dto.BulkSummaryDto
import com.jaac.avoqado_tpv.features.sim_custody.data.dto.MySimItemDto
import com.jaac.avoqado_tpv.features.sim_custody.data.dto.RejectRequestDto
import com.jaac.avoqado_tpv.features.sim_custody.domain.model.BulkResult
import com.jaac.avoqado_tpv.features.sim_custody.domain.model.BulkRowResult
import com.jaac.avoqado_tpv.features.sim_custody.domain.model.BulkSummary
import com.jaac.avoqado_tpv.features.sim_custody.domain.model.MySim
import com.jaac.avoqado_tpv.features.sim_custody.domain.model.SimCustodyState
import com.jaac.avoqado_tpv.features.sim_custody.domain.model.SimVerificationStatus
import com.jaac.avoqado_tpv.features.sim_custody.domain.repository.SimCustodyRepository
import java.math.BigDecimal
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Repository implementation for "Mis SIMs" (plan §3.1–§3.3).
 *
 * Every mutation sends an Idempotency-Key so accidental retries (double-tap,
 * network flake) collapse to a single backend effect.
 *
 * Money is parsed as BigDecimal (project rule — never Float). Timestamps are
 * parsed as UTC Instants and converted to venue TZ in the presentation layer.
 */
@Singleton
class SimCustodyRepositoryImpl @Inject constructor(
    private val apiService: ApiService,
) : SimCustodyRepository {

    companion object {
        private const val TAG = "SimCustodyRepo"
    }

    override suspend fun getMySims(): Result<List<MySim>> = safeCall("getMySims") {
        val response = apiService.getMySims()
        if (!response.isSuccessful) {
            return@safeCall Result.failure(Exception("getMySims failed: ${response.code()}"))
        }
        val body = response.body() ?: return@safeCall Result.failure(Exception("Empty body"))
        Result.success(body.items.map { it.toDomain() })
    }

    override suspend fun accept(serialNumbers: List<String>, idempotencyKey: String): Result<BulkResult> =
        safeCall("accept") {
            val response = apiService.acceptSims(
                idempotencyKey = idempotencyKey,
                request = AcceptRequestDto(serialNumbers = serialNumbers),
            )
            if (!response.isSuccessful) {
                return@safeCall Result.failure(Exception("accept failed: ${response.code()}"))
            }
            val body = response.body() ?: return@safeCall Result.failure(Exception("Empty body"))
            Result.success(body.toDomain())
        }

    override suspend fun reject(serialNumber: String): Result<SimCustodyState> = safeCall("reject") {
        val response = apiService.rejectSim(RejectRequestDto(serialNumber = serialNumber))
        if (!response.isSuccessful) {
            return@safeCall Result.failure(Exception("reject failed: ${response.code()}"))
        }
        val body = response.body() ?: return@safeCall Result.failure(Exception("Empty body"))
        Result.success(SimCustodyState.fromWire(body.custodyState))
    }

    private inline fun <T> safeCall(op: String, block: () -> Result<T>): Result<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e // must propagate to allow coroutine cancellation
        } catch (e: Exception) {
            Log.e(TAG, "$op failed", e)
            Result.failure(e)
        }

    // ==========================================
    // MAPPERS
    // ==========================================

    private fun MySimItemDto.toDomain(): MySim = MySim(
        id = id,
        serialNumber = serialNumber,
        custodyState = SimCustodyState.fromWire(custodyState),
        categoryId = category?.id,
        categoryName = category?.name,
        suggestedPrice = category?.suggestedPrice?.let { parseDecimal(it) },
        assignedAt = parseInstant(assignedPromoterAt),
        acceptedAt = parseInstant(promoterAcceptedAt),
        soldAt = parseInstant(soldAt),
        verificationStatus = SimVerificationStatus.fromWire(verificationStatus),
        verificationId = verificationId,
        rejectionReasons = rejectionReasons.orEmpty(),
        reviewNotes = reviewNotes,
    )

    private fun BulkSimCustodyResponseDto.toDomain(): BulkResult = BulkResult(
        summary = summary.toDomain(),
        results = results.map { it.toDomain() },
    )

    private fun BulkSummaryDto.toDomain(): BulkSummary =
        BulkSummary(total = total, succeeded = succeeded, failed = failed)

    private fun BulkResultRowDto.toDomain(): BulkRowResult = BulkRowResult(
        serialNumber = serialNumber,
        isOk = status == "ok",
        errorCode = code,
        errorMessage = message,
    )

    private fun parseDecimal(raw: String): BigDecimal? =
        try { BigDecimal(raw) } catch (_: Exception) { null }

    private fun parseInstant(raw: String?): Instant? =
        raw?.let {
            try { Instant.parse(it) } catch (_: Exception) { null }
        }
}

package com.jaac.avoqado_tpv.features.sim_custody.domain.repository

import com.jaac.avoqado_tpv.features.sim_custody.domain.model.BulkResult
import com.jaac.avoqado_tpv.features.sim_custody.domain.model.MySim
import com.jaac.avoqado_tpv.features.sim_custody.domain.model.SimCustodyState

/**
 * Repository interface for SIM custody operations (plan §3.1–§3.3).
 *
 * Every mutating call supplies an Idempotency-Key so the backend can dedupe
 * retries (see `simCustodyIdempotency` middleware on the server).
 */
interface SimCustodyRepository {
    suspend fun getMySims(): Result<List<MySim>>

    /** Bulk accept. Returns a partial-success summary with per-row codes. */
    suspend fun accept(serialNumbers: List<String>, idempotencyKey: String): Result<BulkResult>

    /** Single reject. Backend returns the new custody state (PROMOTER_REJECTED). */
    suspend fun reject(serialNumber: String): Result<SimCustodyState>
}

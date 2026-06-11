package com.jaac.avoqado_tpv.features.plan.data.dto

import com.google.gson.annotations.SerializedName
import com.jaac.avoqado_tpv.features.plan.domain.model.PlanTier
import com.jaac.avoqado_tpv.features.plan.domain.model.VenuePlanInfo

/**
 * Optional `plan` object on the terminal-config payload.
 *
 * Backend: GET /tpv/terminals/{serialNumber}/config → data.plan
 * (avoqado-server terminal.tpv.controller.ts, additive 2026-06).
 *
 * ```json
 * { "tier": "PRO", "grandfathered": false, "exempt": false }
 * ```
 *
 * All fields nullable: old servers omit the whole object, and a partial/
 * malformed object must degrade to "no plan info" (fail open), never crash.
 */
data class PlanInfoDto(
    @SerializedName("tier")
    val tier: String? = null,

    @SerializedName("grandfathered")
    val grandfathered: Boolean? = null,

    @SerializedName("exempt")
    val exempt: Boolean? = null,
)

/**
 * Convert to domain, or null when the tier is absent/unknown — callers treat
 * null as "no plan info" and FAIL OPEN (no gates, app behaves as today).
 */
fun PlanInfoDto.toDomainOrNull(): VenuePlanInfo? {
    val parsedTier = PlanTier.fromRaw(tier) ?: return null
    return VenuePlanInfo(
        tier = parsedTier,
        grandfathered = grandfathered == true,
        exempt = exempt == true,
    )
}

package com.jaac.avoqado_tpv.features.plan.data.dto

import com.google.gson.annotations.SerializedName
import java.time.Instant
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
 * malformed object is ignored, preserving the last complete observation.
 */
data class PlanInfoDto(
    @SerializedName("tier")
    val tier: String? = null,

    @SerializedName("grandfathered")
    val grandfathered: Boolean? = null,

    @SerializedName("exempt")
    val exempt: Boolean? = null,
    @SerializedName("accessSchemaVersion") val accessSchemaVersion: Int? = null,
    @SerializedName("accessObservedAt") val accessObservedAt: String? = null,
    @SerializedName("grantedFeatureCodes") val grantedFeatureCodes: List<String>? = null,
)

/**
 * Convert to domain, or null when the tier is absent/unknown — callers treat
 * null as an invalid observation and retain the last known plan.
 */
fun PlanInfoDto.toDomainOrNull(): VenuePlanInfo? = runCatching {
    val parsedTier = PlanTier.fromRaw(tier) ?: return@runCatching null
    if (accessSchemaVersion != null) {
        if (accessSchemaVersion != 1) return@runCatching null
        Instant.parse(accessObservedAt)
        val codes = grantedFeatureCodes ?: return@runCatching null
        if (codes.size > 100 || codes.distinct().size != codes.size || codes.any { !it.matches(Regex("[A-Z][A-Z0-9_]{0,63}")) }) return@runCatching null
    }
    VenuePlanInfo(tier = parsedTier, grandfathered = grandfathered == true, exempt = exempt == true,
        accessSchemaVersion = accessSchemaVersion, accessObservedAt = accessObservedAt, grantedFeatureCodes = grantedFeatureCodes)
}.getOrNull()

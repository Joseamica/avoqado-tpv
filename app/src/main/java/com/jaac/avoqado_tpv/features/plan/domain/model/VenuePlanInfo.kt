package com.jaac.avoqado_tpv.features.plan.domain.model

/**
 * Venue plan tier — mirrors the backend's `plan.tier` on the terminal-config
 * payload (GET /tpv/terminals/{serialNumber}/config → data.plan).
 *
 * Ranked: FREE < PRO < PREMIUM < ENTERPRISE. A venue on tier X has every
 * feature whose minimum tier is <= X.
 *
 * IMPORTANT: tier names are mirrored by EXACT name with the backend
 * (avoqado-server basePlan.service ClientPlanTier). A mismatch fails silently.
 */
enum class PlanTier(val displayName: String) {
    FREE("Gratis"),
    PRO("Pro"),
    PREMIUM("Premium"),
    ENTERPRISE("Enterprise");

    companion object {
        /**
         * Parse the backend tier string. Unknown/absent values return null so
         * callers FAIL OPEN (treat as "no plan info" → no gates), never lock
         * the operator out because of a tier this app version doesn't know.
         */
        fun fromRaw(raw: String?): PlanTier? =
            raw?.trim()?.uppercase()?.let { value -> entries.firstOrNull { it.name == value } }
    }
}

/**
 * The venue's plan info as delivered by the backend.
 *
 * @param tier active plan tier (FREE when no paid base plan)
 * @param grandfathered legacy venue exempted from seat caps (informational)
 * @param exempt grandfathered OR demo venue → the TPV must skip ALL plan
 *   gating: no gates, no badges (behaves exactly as today)
 */
data class VenuePlanInfo(
    val tier: PlanTier,
    val grandfathered: Boolean,
    val exempt: Boolean,
)

/**
 * Feature-code → minimum-tier catalog. Codes are mirrored by EXACT name with
 * the backend (avoqado-server feature codes). Codes NOT in this catalog are
 * never gated (fail open) — order-flow features (charging, applying existing
 * discounts/coupons, printing, payment auth, basic history) are deliberately
 * absent and must never be added here.
 */
object PlanFeatureCatalog {
    // PRO features
    const val REFERRAL_PROGRAM = "REFERRAL_PROGRAM"
    const val PROMOTIONS = "PROMOTIONS"
    const val ADVANCED_REPORTS = "ADVANCED_REPORTS"
    const val CASH_RECONCILIATION = "CASH_RECONCILIATION"
    // Mesas module (table service). Mirrors the backend's checkFeatureAccess('TABLE_SERVICE')
    // (src/routes/tpv.routes.ts) — a Feature code, NOT in PREMIUM_ONLY_CODES, so PRO already
    // includes it (spec 2026-07-24-tpv-mesas-offline-first-design.md D-1).
    const val TABLE_SERVICE = "TABLE_SERVICE"

    // PREMIUM features
    const val SERIALIZED_INVENTORY = "SERIALIZED_INVENTORY"
    const val INVENTORY_TRACKING = "INVENTORY_TRACKING"
    const val CFDI = "CFDI"

    private val MIN_TIER: Map<String, PlanTier> = mapOf(
        REFERRAL_PROGRAM to PlanTier.PRO,
        PROMOTIONS to PlanTier.PRO,
        ADVANCED_REPORTS to PlanTier.PRO,
        CASH_RECONCILIATION to PlanTier.PRO,
        TABLE_SERVICE to PlanTier.PRO,
        SERIALIZED_INVENTORY to PlanTier.PREMIUM,
        INVENTORY_TRACKING to PlanTier.PREMIUM,
        CFDI to PlanTier.PREMIUM,
    )

    /** Minimum tier required for [code], or null when the code is not plan-gated. */
    fun minTierFor(code: String): PlanTier? = MIN_TIER[code]
}

/**
 * Single source of truth for plan gating. Receiver is nullable on purpose:
 *
 * - `null` (no plan info: old server, fetch/parse failed, never fetched)
 *   → **true** (FAIL OPEN — the app behaves exactly as today)
 * - `exempt == true` (grandfathered legacy / demo venue) → **true** (no gates)
 * - unknown feature code → **true** (fail open)
 * - otherwise → venue tier rank >= the feature's minimum tier rank
 */
fun VenuePlanInfo?.allowsFeature(code: String): Boolean {
    val info = this ?: return true
    if (info.exempt) return true
    val required = PlanFeatureCatalog.minTierFor(code) ?: return true
    return info.tier.ordinal >= required.ordinal
}

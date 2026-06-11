package com.jaac.avoqado_tpv.features.plan.data

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.features.plan.data.dto.PlanInfoDto
import com.jaac.avoqado_tpv.features.plan.data.dto.toDomainOrNull
import com.jaac.avoqado_tpv.features.plan.domain.model.PlanFeatureCatalog
import com.jaac.avoqado_tpv.features.plan.domain.model.PlanTier
import com.jaac.avoqado_tpv.features.plan.domain.model.VenuePlanInfo
import com.jaac.avoqado_tpv.features.plan.domain.model.allowsFeature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plan Manager — venue plan-tier gating for the TPV.
 *
 * Mirrors the dashboard's tier gating (FREE < PRO < PREMIUM < ENTERPRISE)
 * using the optional `plan` field that the backend now ships on the
 * terminal-config payload. Cached in [SecureStorage] alongside the TPV
 * settings so gating works offline.
 *
 * **GOLDEN RULES (do not violate):**
 * - FAIL OPEN: no plan info (old server / fetch failed / parse failed) →
 *   [hasFeature] returns true for everything — the app behaves EXACTLY as
 *   today. Never block the operator because plan info is missing.
 * - `exempt == true` (grandfathered legacy / demo venues, e.g. PlayTelecom) →
 *   no gates, no badges.
 * - Order-flow is untouchable: charging, applying EXISTING discounts/coupons,
 *   printing, payment auth and basic history are NEVER plan-gated — their
 *   codes are deliberately absent from [PlanFeatureCatalog].
 *
 * **Update path:** [TpvSettingsRepository.refreshFromTerminalConfig] calls
 * [update] with the parsed DTO on every successful terminal-config fetch
 * (app startup / login), and [clearCache] on venue switch / logout.
 *
 * **Usage (Compose, reactive):**
 * ```kotlin
 * val planInfo by planManager.planInfo.collectAsStateWithLifecycle()
 * val referralAllowed = planInfo.allowsFeature(PlanFeatureCatalog.REFERRAL_PROGRAM)
 * ```
 */
@Singleton
class PlanManager @Inject constructor(
    private val secureStorage: SecureStorage,
) {
    companion object {
        private const val KEY_PLAN_TIER = "venue_plan_tier"
        private const val KEY_PLAN_GRANDFATHERED = "venue_plan_grandfathered"
        private const val KEY_PLAN_EXEMPT = "venue_plan_exempt"
    }

    /**
     * Current plan info, null when unknown (→ fail open).
     * Initialized from SecureStorage cache so gating survives app restarts offline.
     */
    private val _planInfo = MutableStateFlow(readCache())
    val planInfo: StateFlow<VenuePlanInfo?> = _planInfo.asStateFlow()

    /**
     * Update from a freshly-fetched terminal config.
     *
     * Absent or unparseable [dto] (old server, unknown tier) clears the cache
     * → fail open. Called only on SUCCESSFUL fetches — network errors keep
     * the last cached value (offline-first, same as TpvSettingsRepository).
     */
    fun update(dto: PlanInfoDto?) {
        val info = dto?.toDomainOrNull()
        if (info == null) {
            if (_planInfo.value != null) {
                Timber.i("📋 Plan info absent/unparseable in terminal config — clearing (fail open)")
            }
            clearCache()
            return
        }
        secureStorage.putString(KEY_PLAN_TIER, info.tier.name)
        secureStorage.putBoolean(KEY_PLAN_GRANDFATHERED, info.grandfathered)
        secureStorage.putBoolean(KEY_PLAN_EXEMPT, info.exempt)
        _planInfo.value = info
        Timber.i("📋 Plan info updated: tier=${info.tier}, grandfathered=${info.grandfathered}, exempt=${info.exempt}")
    }

    /**
     * Whether the venue's plan includes [code] (see [allowsFeature] for the
     * exact fail-open semantics). Synchronous — reads the cached value only.
     */
    fun hasFeature(code: String): Boolean = _planInfo.value.allowsFeature(code)

    /** Minimum tier required for [code], or null when the code is not plan-gated. */
    fun requiredTierFor(code: String): PlanTier? = PlanFeatureCatalog.minTierFor(code)

    /**
     * Clear cached plan info → unknown → fail open.
     * Call on logout / venue switch (wired from TpvSettingsRepository.clearCache).
     */
    fun clearCache() {
        secureStorage.remove(KEY_PLAN_TIER)
        secureStorage.remove(KEY_PLAN_GRANDFATHERED)
        secureStorage.remove(KEY_PLAN_EXEMPT)
        _planInfo.value = null
    }

    private fun readCache(): VenuePlanInfo? {
        val tier = PlanTier.fromRaw(secureStorage.getString(KEY_PLAN_TIER, null)) ?: return null
        return VenuePlanInfo(
            tier = tier,
            grandfathered = secureStorage.getBoolean(KEY_PLAN_GRANDFATHERED, false),
            exempt = secureStorage.getBoolean(KEY_PLAN_EXEMPT, false),
        )
    }
}

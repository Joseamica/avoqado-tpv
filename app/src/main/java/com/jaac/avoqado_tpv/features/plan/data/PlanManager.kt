package com.jaac.avoqado_tpv.features.plan.data

import com.google.gson.Gson
import java.time.Instant
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

/** Venue-scoped paid access, persisted before publication so it survives an offline restart.
 * Missing/invalid responses retain the last observation. Legacy tiers are used only before v1.
 * Order/payment capabilities absent from PlanFeatureCatalog remain available during service.
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

    private val gson = Gson()
    private var boundVenueId = secureStorage.getVenueId()
    private fun snapshotKey(venueId: String) = "venue_plan_snapshot.$venueId"

    /**
     * Current plan info, null when unknown (→ fail open).
     * Initialized from SecureStorage cache so gating survives app restarts offline.
     */
    private val _planInfo = MutableStateFlow(readCache())
    val planInfo: StateFlow<VenuePlanInfo?> = _planInfo.asStateFlow()

    @Synchronized
    fun update(dto: PlanInfoDto?, venueId: String? = secureStorage.getVenueId()) {
        if (venueId == null || venueId != secureStorage.getVenueId()) return
        if (boundVenueId != venueId) {
            boundVenueId = venueId
            _planInfo.value = readCache()
        }
        val info = dto?.toDomainOrNull() ?: return
        val previous = _planInfo.value
        if (previous?.accessSchemaVersion == 1) {
            if (info.accessSchemaVersion != 1) return
            if (!Instant.parse(info.accessObservedAt).isAfter(Instant.parse(previous.accessObservedAt))) return
        }
        secureStorage.putStringDurably(snapshotKey(venueId), gson.toJson(dto))
        clearLegacyKeys()
        _planInfo.value = info
        Timber.i("Plan access snapshot updated for the current venue")
    }

    /**
     * Whether the venue's plan includes [code] (see [allowsFeature] for the
     * exact fail-open semantics). Synchronous — reads the cached value only.
     */
    fun hasFeature(code: String): Boolean =
        (if (boundVenueId == secureStorage.getVenueId()) _planInfo.value else readCache(secureStorage.getVenueId())).allowsFeature(code)

    /** Minimum tier required for [code], or null when the code is not plan-gated. */
    fun requiredTierFor(code: String): PlanTier? = PlanFeatureCatalog.minTierFor(code)

    /**
     * Clear cached plan info → unknown → fail open.
     * Call on logout / venue switch (wired from TpvSettingsRepository.clearCache).
     */
    @Synchronized
    fun clearCache() {
        boundVenueId?.let { secureStorage.remove(snapshotKey(it)) }
        clearLegacyKeys()
        boundVenueId = secureStorage.getVenueId()
        _planInfo.value = readCache()
    }

    private fun clearLegacyKeys() {
        secureStorage.remove(KEY_PLAN_TIER)
        secureStorage.remove(KEY_PLAN_GRANDFATHERED)
        secureStorage.remove(KEY_PLAN_EXEMPT)
    }

    private fun readCache(venueId: String? = boundVenueId): VenuePlanInfo? {
        if (venueId == null) return null
        val saved = secureStorage.getString(snapshotKey(venueId), null)
        if (saved != null) return runCatching { gson.fromJson(saved, PlanInfoDto::class.java)?.toDomainOrNull() }.getOrNull()
        val tier = PlanTier.fromRaw(secureStorage.getString(KEY_PLAN_TIER, null)) ?: return null
        return VenuePlanInfo(tier, secureStorage.getBoolean(KEY_PLAN_GRANDFATHERED, false), secureStorage.getBoolean(KEY_PLAN_EXEMPT, false))
    }
}

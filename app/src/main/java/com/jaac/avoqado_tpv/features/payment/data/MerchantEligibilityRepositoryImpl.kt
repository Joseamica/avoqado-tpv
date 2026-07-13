package com.jaac.avoqado_tpv.features.payment.data

import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.dto.MerchantEligibilityRequest
import com.jaac.avoqado_tpv.core.location.LocationService
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantEligibility
import com.jaac.avoqado_tpv.features.payment.domain.repository.MerchantEligibilityRepository
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [MerchantEligibilityRepository].
 *
 * Calls the backend eligibility endpoint, attaches terminal location for geofence
 * rules, and applies a LOCAL circuit breaker (config comes from the server per
 * merchant; the counting/enforcement happens here so it works offline and reacts
 * to SDK failures instantly).
 *
 * Fail-open: any failure returns a result whose [MerchantEligibility.shouldShowAll]
 * is true — a routing rule never blocks a sale.
 */
@Singleton
class MerchantEligibilityRepositoryImpl @Inject constructor(
    private val apiService: ApiService,
    private val locationService: LocationService,
    private val deviceInfoManager: DeviceInfoManager,
) : MerchantEligibilityRepository {

    /** Per-merchant circuit-breaker state (in-memory; no persistence needed). */
    private data class BreakerState(
        var consecutiveFailures: Int = 0,
        var trippedUntilMs: Long = 0L,
        var threshold: Int = 0,     // from server config; 0 = breaker not configured for this merchant
        var cooldownMs: Long = 0L,
    )

    private val breakers = ConcurrentHashMap<String, BreakerState>()

    override suspend fun evaluate(
        totalAmount: BigDecimal,
        staffId: String?,
        includeLocation: Boolean,
    ): MerchantEligibility {
        val venueId = deviceInfoManager.getVenueId()
        if (venueId.isNullOrBlank()) {
            Timber.w("🧭 [Eligibility] No venueId — fail-open (show all)")
            return MerchantEligibility.disabled()
        }

        // Whole evaluation is best-effort and short — never stall a payment on it.
        val result = withTimeoutOrNull(3_000) {
            evaluateInternal(venueId, totalAmount, staffId, includeLocation)
        }
        if (result == null) {
            Timber.w("🧭 [Eligibility] Evaluation timed out — fail-open (show all)")
            return MerchantEligibility(
                evaluated = false,
                routingFeatureActive = true, // timeout on a real call → treat as "expected rules, couldn't verify"
                eligibleMerchantAccountIds = emptySet(),
                autoSelectMerchantAccountId = null,
                fallbackAll = false,
            )
        }
        return result
    }

    private suspend fun evaluateInternal(
        venueId: String,
        totalAmount: BigDecimal,
        staffId: String?,
        includeLocation: Boolean,
    ): MerchantEligibility {
        // Terminal location for geofence rules (optional; omit on denial/failure).
        val location = if (includeLocation) {
            runCatching { locationService.getCurrentLocation(timeoutMs = 2_000) }.getOrNull()
        } else null

        val request = MerchantEligibilityRequest(
            amount = totalAmount.toDouble(), // PESOS (major units) — backend engine is pesos 1:1
            staffId = staffId?.takeIf { it.isNotBlank() },
            lat = location?.latitude,
            lng = location?.longitude,
            terminalSerial = deviceInfoManager.getSerialNumber().takeIf { it.isNotBlank() },
        )

        // Retrofit suspend calls are main-safe (OkHttp dispatches off-thread) — no withContext needed.
        val response = try {
            apiService.getMerchantEligibility(venueId, request)
        } catch (e: Exception) {
            Timber.w(e, "🧭 [Eligibility] Network error — fail-open (show all)")
            return failOpen()
        }

        val body = response.body()
        if (!response.isSuccessful || body?.data == null) {
            Timber.w("🧭 [Eligibility] HTTP ${response.code()} — fail-open (show all)")
            return failOpen()
        }

        val data = body.data
        val allIds = data.merchants.map { it.merchantAccountId }.toSet()

        // Store per-merchant breaker config as we learn it from the server.
        data.merchants.forEach { item ->
            item.circuitBreaker?.let { cb ->
                val state = breakers.getOrPut(item.merchantAccountId) { BreakerState() }
                synchronized(state) {
                    state.threshold = cb.consecutiveFailures
                    state.cooldownMs = cb.cooldownMinutes * 60_000L
                }
            }
        }

        if (!data.routingFeatureActive) {
            // Feature off → all shown, no filtering, no banner.
            return MerchantEligibility(
                evaluated = true,
                routingFeatureActive = false,
                eligibleMerchantAccountIds = allIds,
                autoSelectMerchantAccountId = null,
                fallbackAll = false,
            )
        }

        val now = System.currentTimeMillis()
        val serverEligible = data.merchants.filter { it.eligible }.map { it.merchantAccountId }.toSet()
        val effectiveEligible = serverEligible.filterNot { isTripped(it, now) }.toSet()

        // Server said 0 matched, OR the local breaker knocked out the last option → show all + banner.
        if (data.fallbackAll || effectiveEligible.isEmpty()) {
            return MerchantEligibility(
                evaluated = true,
                routingFeatureActive = true,
                eligibleMerchantAccountIds = allIds,
                autoSelectMerchantAccountId = null,
                fallbackAll = true,
            )
        }

        // Auto-select when exactly one survives; otherwise keep the server's pick if it survived.
        val autoSelect = when {
            effectiveEligible.size == 1 -> effectiveEligible.first()
            data.autoSelectMerchantAccountId != null && effectiveEligible.contains(data.autoSelectMerchantAccountId) ->
                data.autoSelectMerchantAccountId
            else -> null
        }

        return MerchantEligibility(
            evaluated = true,
            routingFeatureActive = true,
            eligibleMerchantAccountIds = effectiveEligible,
            autoSelectMerchantAccountId = autoSelect,
            fallbackAll = false,
        )
    }

    private fun failOpen(): MerchantEligibility = MerchantEligibility(
        evaluated = false,
        routingFeatureActive = true, // a real API attempt failed → "expected rules, couldn't verify" → banner
        eligibleMerchantAccountIds = emptySet(),
        autoSelectMerchantAccountId = null,
        fallbackAll = false,
    )

    private fun isTripped(merchantAccountId: String, now: Long): Boolean {
        val state = breakers[merchantAccountId] ?: return false
        synchronized(state) {
            if (state.trippedUntilMs > now) return true
            // Cooldown elapsed → auto-reset so the merchant can be offered again.
            if (state.trippedUntilMs != 0L) {
                state.trippedUntilMs = 0L
                state.consecutiveFailures = 0
            }
            return false
        }
    }

    override fun recordChargeFailure(merchantAccountId: String) {
        val state = breakers.getOrPut(merchantAccountId) { BreakerState() }
        synchronized(state) {
            if (state.threshold <= 0) return // no breaker configured for this merchant
            state.consecutiveFailures += 1
            if (state.consecutiveFailures >= state.threshold) {
                state.trippedUntilMs = System.currentTimeMillis() + state.cooldownMs
                Timber.w(
                    "🔌 [Eligibility] Circuit breaker TRIPPED for %s (%d fails ≥ %d) — hidden for %d min",
                    merchantAccountId, state.consecutiveFailures, state.threshold, state.cooldownMs / 60_000L
                )
            }
        }
    }

    override fun recordChargeSuccess(merchantAccountId: String) {
        val state = breakers[merchantAccountId] ?: return
        synchronized(state) {
            state.consecutiveFailures = 0
            state.trippedUntilMs = 0L
        }
    }
}

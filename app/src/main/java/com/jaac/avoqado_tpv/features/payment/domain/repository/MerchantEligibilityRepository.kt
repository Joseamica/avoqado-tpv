package com.jaac.avoqado_tpv.features.payment.domain.repository

import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantEligibility
import java.math.BigDecimal

/**
 * Repository for MERCHANT_ROUTING_RULES eligibility (PREMIUM feature).
 *
 * Wraps the backend eligibility endpoint + terminal location + a local circuit
 * breaker. The terminal calls [evaluate] right before showing the merchant
 * selector, with the total amount already known.
 *
 * Fail-open by contract: any failure (offline, error, missing venue) returns a
 * result whose [MerchantEligibility.shouldShowAll] is true — a routing rule never
 * blocks a sale.
 */
interface MerchantEligibilityRepository {

    /**
     * Evaluate which merchant accounts to offer for a charge of [totalAmount] PESOS.
     *
     * @param totalAmount ticket total in PESOS (major units) — converted to the API's pesos field.
     * @param staffId who is charging (optional; drives the staff condition).
     * @param includeLocation whether to attach the terminal GPS location (for geofence rules).
     *                        A denied/failed location simply omits it (that condition then fails).
     * @return eligibility result; never throws — failures map to a fail-open result.
     */
    suspend fun evaluate(
        totalAmount: BigDecimal,
        staffId: String?,
        includeLocation: Boolean = true,
    ): MerchantEligibility

    /**
     * Record a TECHNICAL charge failure (SDK/processor error, not a normal decline)
     * for a merchant, feeding the local circuit breaker. When consecutive failures
     * reach the merchant's configured threshold, [evaluate] hides it until cooldown.
     */
    fun recordChargeFailure(merchantAccountId: String)

    /** Record a successful charge → resets the merchant's circuit-breaker counter. */
    fun recordChargeSuccess(merchantAccountId: String)
}

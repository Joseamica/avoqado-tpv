package com.jaac.avoqado_tpv.core.data.network.dto

import com.google.gson.annotations.SerializedName

/**
 * Request/response DTOs for MERCHANT_ROUTING_RULES eligibility
 * (POST /tpv/venues/{venueId}/merchant-eligibility).
 *
 * The backend decides which merchant accounts the terminal should offer for the
 * charge in progress, given the amount, and optionally the staff and terminal
 * location. Server-side gating: a venue WITHOUT the PREMIUM feature returns all
 * merchants eligible ([routingFeatureActive] = false) so the terminal behaves
 * exactly as before.
 *
 * **Money:** [amount] is in PESOS (major units, e.g. 250.50), NOT cents — the
 * backend engine works in pesos 1:1. Convert from the payment BigDecimal at the
 * call site.
 */
data class MerchantEligibilityRequest(
    @SerializedName("amount")
    val amount: Double,
    @SerializedName("staffId")
    val staffId: String? = null,
    @SerializedName("lat")
    val lat: Double? = null,
    @SerializedName("lng")
    val lng: Double? = null,
    @SerializedName("terminalSerial")
    val terminalSerial: String? = null,
)

data class MerchantEligibilityResponse(
    @SerializedName("success")
    val success: Boolean,
    @SerializedName("data")
    val data: MerchantEligibilityData?,
)

data class MerchantEligibilityData(
    /** false = the PREMIUM feature is not active on this venue → all shown, no filtering. */
    @SerializedName("routingFeatureActive")
    val routingFeatureActive: Boolean = false,
    @SerializedName("merchants")
    val merchants: List<MerchantEligibilityItem> = emptyList(),
    /** Set when exactly one merchant is eligible → the terminal pre-selects it. */
    @SerializedName("autoSelectMerchantAccountId")
    val autoSelectMerchantAccountId: String? = null,
    /** true when NO merchant matched → show all with a warning (a rule never blocks a sale). */
    @SerializedName("fallbackAll")
    val fallbackAll: Boolean = false,
    @SerializedName("evaluatedAt")
    val evaluatedAt: String? = null,
)

data class MerchantEligibilityItem(
    @SerializedName("merchantAccountId")
    val merchantAccountId: String,
    @SerializedName("eligible")
    val eligible: Boolean,
    @SerializedName("reasons")
    val reasons: List<String> = emptyList(),
    /** Circuit-breaker config for this merchant, applied locally by the terminal. */
    @SerializedName("circuitBreaker")
    val circuitBreaker: MerchantCircuitBreakerDto? = null,
)

data class MerchantCircuitBreakerDto(
    @SerializedName("consecutiveFailures")
    val consecutiveFailures: Int,
    @SerializedName("cooldownMinutes")
    val cooldownMinutes: Int,
)

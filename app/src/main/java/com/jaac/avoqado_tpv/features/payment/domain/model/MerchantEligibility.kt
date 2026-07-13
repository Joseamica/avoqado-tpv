package com.jaac.avoqado_tpv.features.payment.domain.model

/**
 * Domain result of a MERCHANT_ROUTING_RULES eligibility evaluation for the charge
 * in progress. Produced by [com.jaac.avoqado_tpv.features.payment.domain.repository.MerchantEligibilityRepository].
 *
 * Consumed by PaymentViewModel to decide which merchant accounts to show, whether
 * to pre-select one, and whether to show the "showing all accounts" banner.
 *
 * Golden rule: a routing rule NEVER blocks a sale. On feature-off, network error,
 * or zero eligible, the terminal shows all merchants (see [shouldShowAll]).
 */
data class MerchantEligibility(
    /** false when the API/network failed → fail-open (behave like today, show all). */
    val evaluated: Boolean,
    /** false when the venue lacks the PREMIUM feature → no filtering. */
    val routingFeatureActive: Boolean,
    /** Merchant account ids the terminal should OFFER (server eligible, minus locally-tripped). */
    val eligibleMerchantAccountIds: Set<String>,
    /** Set when exactly one merchant is eligible → terminal pre-selects it. Null otherwise. */
    val autoSelectMerchantAccountId: String?,
    /** true when NO merchant matched the rules → show all with a warning. */
    val fallbackAll: Boolean,
) {
    /**
     * Whether the terminal should show ALL configured merchants unfiltered.
     * True when: couldn't evaluate (offline/error), feature off, or fallback (0 eligible).
     */
    val shouldShowAll: Boolean
        get() = !evaluated || !routingFeatureActive || fallbackAll

    /**
     * Whether to surface the "showing all accounts (rules not applicable)" warning.
     * Only when the feature IS active but nothing matched, or the check failed on a
     * terminal that expected rules. Not shown when the feature is simply off.
     */
    val showFallbackBanner: Boolean
        get() = fallbackAll || (!evaluated && routingFeatureActive)

    companion object {
        /** Fail-open result: no filtering, no banner — used when the venue id is missing. */
        fun disabled(): MerchantEligibility = MerchantEligibility(
            evaluated = false,
            routingFeatureActive = false,
            eligibleMerchantAccountIds = emptySet(),
            autoSelectMerchantAccountId = null,
            fallbackAll = false,
        )
    }
}

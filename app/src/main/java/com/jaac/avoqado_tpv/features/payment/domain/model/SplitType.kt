package com.jaac.avoqado_tpv.features.payment.domain.model

/**
 * Split Payment Type
 *
 * Defines how a payment is being split across an order.
 * Maps to backend SplitType enum.
 *
 * **Modes:**
 * - PERPRODUCT: Pay for specific products (checkbox selection)
 * - EQUALPARTS: Split total equally among N people
 * - CUSTOMAMOUNT: Pay a custom amount
 * - FULLPAYMENT: Pay the entire remaining balance
 *
 * @property value Backend string value for API requests
 */
enum class SplitType(val value: String) {
    /**
     * Pay for specific products only.
     * User selects which items to pay for from the order.
     * Backend marks those items as paid.
     */
    PERPRODUCT("PERPRODUCT"),

    /**
     * Split total equally among N people.
     * Example: $200 order / 4 people = $50 each.
     * User selects how many parts to pay (1, 2, etc.)
     */
    EQUALPARTS("EQUALPARTS"),

    /**
     * Pay a custom amount (partial payment).
     * User enters any amount up to the remaining balance.
     * Useful for uneven splits or deposits.
     */
    CUSTOMAMOUNT("CUSTOMAMOUNT"),

    /**
     * Pay the entire remaining balance.
     * Default mode for regular payments.
     */
    FULLPAYMENT("FULLPAYMENT");

    companion object {
        /**
         * Parse backend string to SplitType.
         * Defaults to FULLPAYMENT if unknown value.
         *
         * @param value Backend string value
         * @return Corresponding SplitType
         */
        fun fromValue(value: String): SplitType =
            entries.find { it.value.equals(value, ignoreCase = true) } ?: FULLPAYMENT
    }
}

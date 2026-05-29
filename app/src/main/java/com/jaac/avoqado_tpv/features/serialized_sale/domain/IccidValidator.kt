package com.jaac.avoqado_tpv.features.serialized_sale.domain

/**
 * Shared Mexican ICCID validation, used by both the Alta (registro) flow and the
 * Venta (sale) flow. Mirrors the backend guard (serializedInventory.service.ts
 * isValidMxIccid) and the original SerializedInventoryViewModel companion.
 */
object IccidValidator {
    /**
     * Mexican ICCID per ITU-T E.118 + GSM Phase 1: `8952` (MII 89 + MX 52) +
     * 15-16 digits + optional trailing `F` (BCD padding). Verified against 1,021
     * real ALTAN SIMs (100% match). This is the "20 digits starting 8952" rule.
     */
    val MX_ICCID_REGEX = Regex("^8952\\d{15,16}F?$")

    /** Trim + uppercase so `f` and `F` collide. Does NOT strip trailing F. */
    fun canonicalize(raw: String): String = raw.trim().uppercase()

    fun isValidFormat(raw: String): Boolean = MX_ICCID_REGEX.matches(canonicalize(raw))

    /**
     * Luhn mod-10 (ISO/IEC 7812). Strip trailing F before calling. Last digit IS
     * the check digit. ~1 in 1,000 valid carrier SIMs fail Luhn, so callers treat
     * a failure as a soft warning, not a hard reject.
     */
    fun isLuhnValid(digits: String): Boolean {
        if (digits.isEmpty() || !digits.all { it.isDigit() }) return false
        var sum = 0
        for ((i, c) in digits.reversed().withIndex()) {
            val d = c.digitToInt()
            sum += if (i % 2 == 1) { val doubled = d * 2; if (doubled > 9) doubled - 9 else doubled } else d
        }
        return sum % 10 == 0
    }
}

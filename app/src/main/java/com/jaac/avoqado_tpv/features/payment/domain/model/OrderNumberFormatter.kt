package com.jaac.avoqado_tpv.features.payment.domain.model

/**
 * Utility to format order numbers for UI display and references.
 *
 * - Display: last 6 digits (if any)
 * - Reference: last 8 digits (if any)
 */
object OrderNumberFormatter {
    fun display(orderNumber: String?): String? = formatDigits(orderNumber, 6)

    fun reference(orderNumber: String?): String? = formatDigits(orderNumber, 8)

    fun buildReference(orderNumber: String?): String {
        return reference(orderNumber)
            ?: System.currentTimeMillis().toString().takeLast(8)
    }

    private fun formatDigits(orderNumber: String?, length: Int): String? {
        if (orderNumber.isNullOrBlank()) return null
        val digits = orderNumber.filter { it.isDigit() }
        if (digits.isBlank()) return orderNumber
        return if (digits.length >= length) digits.takeLast(length) else digits
    }
}

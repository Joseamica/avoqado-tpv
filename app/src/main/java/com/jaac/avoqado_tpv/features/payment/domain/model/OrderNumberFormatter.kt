package com.jaac.avoqado_tpv.features.payment.domain.model

/**
 * Utility to format order numbers for UI display and references.
 *
 * - Display: last 6 digits (if any)
 * - Reference: last 8 digits (if any)
 * - Un folio de la tienda en línea («W7K3P», R61) se deja tal cual: sus dígitos sueltos («73») no dicen nada.
 */
object OrderNumberFormatter {
    fun display(orderNumber: String?): String? = formatDigits(orderNumber, 6)

    fun reference(orderNumber: String?): String? = formatDigits(orderNumber, 8)

    fun buildReference(orderNumber: String?): String {
        return reference(orderNumber)
            ?: System.currentTimeMillis().toString().takeLast(8)
    }

    private val FOLIO = Regex("^W[A-Z2-9]{4}$")

    private fun formatDigits(orderNumber: String?, length: Int): String? {
        if (orderNumber.isNullOrBlank()) return null
        if (FOLIO.matches(orderNumber)) return orderNumber
        val digits = orderNumber.filter { it.isDigit() }
        if (digits.isBlank()) return orderNumber
        return if (digits.length >= length) digits.takeLast(length) else digits
    }
}

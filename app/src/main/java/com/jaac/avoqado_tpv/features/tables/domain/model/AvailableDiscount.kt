package com.jaac.avoqado_tpv.features.tables.domain.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Un descuento elegible para la cuenta actual — espejo de
 * `AvailableDiscount` (`avoqado-server src/services/tpv/discount.tpv.service.ts:21-31`),
 * ya filtrado por el server (elegibilidad + no repetir uno ya aplicado). Ver
 * KDoc de [com.jaac.avoqado_tpv.features.tables.data.api.dto.AvailableDiscountDto]
 * para el porqué de [requiresApproval].
 */
data class AvailableDiscount(
    val id: String,
    val name: String,
    /** PERCENTAGE | FIXED_AMOUNT | COMP. */
    val type: String,
    val value: BigDecimal,
    val requiresApproval: Boolean,
    val estimatedSavings: BigDecimal,
) {
    val valueDisplay: String
        get() = when (type) {
            "PERCENTAGE" -> "${value.setScale(0, RoundingMode.HALF_UP).toPlainString()}%"
            "COMP" -> "Cortesía"
            else -> "$${value.setScale(2, RoundingMode.HALF_UP).toPlainString()}"
        }

    val savingsDisplay: String
        get() = "-$${estimatedSavings.setScale(2, RoundingMode.HALF_UP).toPlainString()}"
}

package com.jaac.avoqado_tpv.features.tables.domain.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Un cargo por servicio del catálogo del venue — espejo de `listServiceCharges`
 * (`avoqado-server src/services/mobile/service-charge.mobile.service.ts`). Ver
 * KDoc de [com.jaac.avoqado_tpv.features.tables.data.api.dto.AvailableServiceChargeDto]
 * para el porqué de [autoApplyMinCovers] y la diferencia con [AvailableDiscount]
 * (esta lista NO está filtrada por elegibilidad de una cuenta puntual).
 */
data class AvailableServiceCharge(
    val id: String,
    val name: String,
    /** PERCENTAGE | FIXED_AMOUNT. */
    val type: String,
    val value: BigDecimal,
    val taxable: Boolean,
    val autoApplyMinCovers: Int?,
) {
    val valueDisplay: String
        get() = when (type) {
            "PERCENTAGE" -> "${value.setScale(0, RoundingMode.HALF_UP).toPlainString()}%"
            else -> "$${value.setScale(2, RoundingMode.HALF_UP).toPlainString()}"
        }

    /** "Automático desde 6 personas" — null cuando el cargo es SOLO manual. */
    val autoApplyDisplay: String?
        get() = autoApplyMinCovers?.let { "Automático desde $it personas" }
}

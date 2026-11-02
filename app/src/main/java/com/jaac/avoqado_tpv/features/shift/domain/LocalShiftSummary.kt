package com.jaac.avoqado_tpv.features.shift.domain

import java.math.BigDecimal

/** T3 presenta sólo estos hechos del aparato. No suma acumulados remotos con filas locales. */
data class LocalShiftSummary(
    val startingCash: BigDecimal?,
    val countedCash: BigDecimal?,
    val localStatus: String,
    val openingStatus: String,
    val closeStatus: String,
    val adoptionNotice: String?,
    val expectedCash: BigDecimal? = null,
    val salesTotal: BigDecimal? = null,
)

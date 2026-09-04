package com.jaac.avoqado_tpv.features.reports.data.dto

import com.google.gson.annotations.SerializedName
import com.jaac.avoqado_tpv.features.reports.domain.models.TenderRow
import java.math.BigDecimal

/**
 * Respuesta de `GET /mobile/venues/{venueId}/cash-drawer/tender-breakdown`.
 *
 * Envuelta en `{ success, data }` — verificado en el controlador del servidor
 * (`cash-drawer.mobile.controller.ts`), que aquí NO es como su hermano `getHistory`,
 * que sí devuelve el cuerpo plano. Leerlo como si fuera plano da `null` sin un solo
 * error: es el defecto que dejó el detalle de anuncios cargando para siempre.
 */
data class TenderBreakdownResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: TenderBreakdownData
)

data class TenderBreakdownData(
    @SerializedName("tenderBreakdown") val tenderBreakdown: List<TenderRowDto>? = null,
    @SerializedName("totalTips") val totalTips: Double? = null,
    @SerializedName("from") val from: String? = null,
    @SerializedName("to") val to: String? = null
)

data class TenderRowDto(
    /** Valor crudo del enum del servidor. `null` no puede pasar: el server usa 'OTHER'. */
    @SerializedName("method") val method: String? = null,
    /** Venta + propina, en pesos (no centavos). */
    @SerializedName("total") val total: Double? = null,
    /** Cuánto de `total` fue propina. */
    @SerializedName("tips") val tips: Double? = null
)

/**
 * Mapea la respuesta al dominio.
 *
 * 🔴 Un renglón ilegible invalida el desglose ENTERO (`null`), no se salta.
 * Saltárselo (`mapNotNull`) tiene dos formas de mentir, las dos silenciosas: si
 * fallan TODOS, la lista sale vacía y el ticket la lee como "no hubo cobros"; y si
 * falla sólo alguno, el reporte subestima las ventas sin decirlo. Es la misma regla
 * —y por el mismo motivo— que ya está escrita en `CashDrawerRepository` de
 * avoqado-android tras la 4ª auditoría de Codex.
 */
fun TenderBreakdownData.toTenderRows(): List<TenderRow>? {
    val filas = tenderBreakdown ?: return null
    val mapeadas = filas.map { dto ->
        val method = dto.method ?: return@map null
        val total = dto.total ?: return@map null
        // `tips` ausente se lee como CERO a propósito: es aditivo y un servidor viejo
        // podría no mandarlo. Cero es la lectura conservadora — nunca inventa propina.
        TenderRow(
            method = method,
            total = BigDecimal.valueOf(total),
            tips = BigDecimal.valueOf(dto.tips ?: 0.0)
        )
    }
    if (mapeadas.any { it == null }) return null
    return mapeadas.filterNotNull()
}

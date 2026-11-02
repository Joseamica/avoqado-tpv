package com.jaac.avoqado_tpv.features.shift.presentation

import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.core.data.local.entity.LocalShiftSessionEntity
import com.jaac.avoqado_tpv.features.shift.domain.LocalShiftSummary
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import java.math.BigDecimal
import java.math.RoundingMode

/** Sólo presenta hechos existentes. Nunca calcula ventas, esperado ni diferencia. */
data class CajaLocalPresentation(val session: LocalShiftSessionEntity, val summary: LocalShiftSummary) {
    val hasLocalClose get() = session.closeOperationId != null
    val canAdopt get() = session.openingDisposition == "ADOPTED" && session.adoptionAcceptedAt == null
    val title: String get() = when {
        !hasLocalClose && session.localStatus == "CLOSED" -> "Caja cerrada o cerrándose en otro aparato"
        session.closeStatus == "REVIEW" || session.openingStatus == "REVIEW" -> "Caja pendiente de revisión"
        session.closeStatus == "CONFIRMED" -> "Caja cerrada"
        hasLocalClose -> "Cierre pendiente"
        canAdopt -> "Apertura vinculada a otra caja"
        else -> "Caja abierta"
    }
    private val ignoredCount: Boolean get() = runCatching {
        val evidence = JsonParser.parseString(session.closeEvidenceJson).asJsonObject
        evidence.getAsJsonObject("operation")?.get("countApplied")?.takeUnless { it.isJsonNull }?.asBoolean == false ||
            evidence.getAsJsonObject("reconciliation")?.get("outcome")?.asString?.startsWith("IGNORED_") == true
    }.getOrDefault(false)
    val notice: String? get() = when {
        !hasLocalClose && session.localStatus == "CLOSED" -> session.lastError ?: "Esta caja se cerró en otro aparato."
        session.closeStatus == "REVIEW" || session.openingStatus == "REVIEW" -> session.lastError ?: "Hay cambios pendientes de revisar."
        session.closeStatus == "CONFIRMED" -> if (ignoredCount && session.cashReconciliationAction == "COUNTED")
            "El conteo no se aplicó al cierre." else null
        hasLocalClose -> "Pendiente de confirmar. Se enviará después de las ventas."
        canAdopt -> "Hay otra caja abierta. Revisa los dos fondos antes de usarla."
        session.openingStatus != "CONFIRMED" -> "Apertura guardada. Pendiente de confirmar."
        else -> null
    }
    val description: String get() = when {
        !hasLocalClose && session.localStatus == "CLOSED" -> session.lastError ?: "Se observó un cierre remoto. No se capturó un conteo en este aparato."
        session.closeStatus == "CONFIRMED" && ignoredCount -> "El cierre se confirmó; no se aplicó un conteo. Revisa el detalle del servidor."
        session.closeStatus == "REVIEW" -> session.lastError ?: "Se conserva la intención original; requiere revisión."
        hasLocalClose && session.closeStatus != "CONFIRMED" -> "Las ventas pendientes se enviarán antes del cierre. El cierre permanece guardado; todavía no es un corte definitivo."
        canAdopt -> "El servidor ya tenía otra caja. Los dos fondos se conservan separados. Adoptarla sólo cambia los gestos nuevos."
        session.openingStatus != "CONFIRMED" -> "Se enviará al recuperar la conexión. Otros aparatos todavía pueden mostrar otra caja."
        else -> "Los importes completos del corte se consultan en el servidor."
    }
    val knownLines: List<String> get() = buildList {
        val fundLabel = if (session.openingKind == "REMOTE") "Fondo de la caja" else "Fondo capturado"
        add(summary.startingCash?.let { "$fundLabel: ${money(it)}" } ?: "Fondo no disponible")
        if (session.openingDisposition == "ADOPTED") add(session.remoteStartingCash?.toBigDecimalOrNull()?.let {
            "Fondo de la caja vinculada: ${money(it)}"
        } ?: "Fondo de la caja vinculada no disponible")
        when (session.cashReconciliationAction) {
            "COUNTED" -> add(summary.countedCash?.let { "Conteo capturado: ${money(it)}" } ?: "Conteo no disponible")
            "SKIPPED" -> add("Cierre sin conteo")
        }
        add("Ventas, efectivo esperado y diferencia: no disponibles aquí")
    }
    fun paper(): String = buildString {
        append("RESUMEN DE CAJA\nPROVISIONAL\n\n")
        append(title).append("\n\n")
        knownLines.forEach { append(it).append("\n\n") }
        append(description).append("\n\n")
        append("NO ES UN CORTE DEFINITIVO\n\n")
        append("Caja local:\n").append(session.localSessionId).append("\n\n")
        append("Apertura UTC:\n").append(session.originalOpenedAt).append("\n\n")
        session.closedAtLocal?.let { append("Captura cierre UTC:\n").append(it).append('\n') }
    }
    private fun money(value: BigDecimal) = "$${value.setScale(2, RoundingMode.UNNECESSARY).toPlainString()}"
}

/** Gson omite defaults Kotlin en cachés viejas: null conserva el contrato anterior. */
fun Shift.hasKnownFinancialTotals(): Boolean = financialTotalsKnown != false

/** Texto de las métricas del DTO acreditado; null nunca se transforma en cuatro ceros de cache. */
fun Shift.financialDisplayLines(): List<Pair<String, String>>? {
    if (!hasKnownFinancialTotals()) return null
    fun money(value: BigDecimal) = "$${value.setScale(2, RoundingMode.HALF_UP).toPlainString()}"
    return listOf("Ventas" to money(totalSales), "Productos" to totalProductsSold.toString(),
        "Órdenes" to totalOrders.toString(), "Propinas" to money(totalTips))
}

data class LocalCajaReview(
    val sessionId: String,
    val page: com.jaac.avoqado_tpv.features.shift.domain.LocalShiftObligationPage? = null,
    val cashEligible: Set<Long> = emptySet(),
    val busy: Boolean = false,
    val error: String? = null,
)

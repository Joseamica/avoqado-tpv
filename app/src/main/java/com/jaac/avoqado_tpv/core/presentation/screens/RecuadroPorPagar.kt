package com.jaac.avoqado_tpv.core.presentation.screens

import com.jaac.avoqado_tpv.features.onlineorders.domain.EstadoDeFolios

/** Lo que el recuadro «Pedidos por pagar» del inicio pinta. La pantalla sólo pinta; el latido lo decide el vigía. */
data class RecuadroPorPagar(
    val habilitado: Boolean,
    /** Insignia de arriba a la derecha: el número de folios por pagar, o el motivo si está deshabilitado. */
    val badge: String?,
    /** Renglón de abajo: «N por entregar» o un aviso. */
    val subtitulo: String?,
    /** true = el renglón es un aviso (se pinta en naranja). */
    val subtituloEsAviso: Boolean,
)

/** `null` si el servidor no pidió el recuadro. Misma condición que «Cobrar»: turno abierto y entrada registrada. */
fun recuadroPorPagar(
    estado: EstadoDeFolios,
    canOperate: Boolean,
    canWork: Boolean,
    motivoSinTurno: String?,
): RecuadroPorPagar? {
    if (!estado.showTile) return null
    if (!canWork) return RecuadroPorPagar(false, "Registra tu entrada", null, false)
    if (!canOperate) return RecuadroPorPagar(false, motivoSinTurno ?: "Abre la caja primero", null, false)

    // Tras un 4xx la lista es la última buena pero ya no es fiable: no se enseña su número como si fuera de hoy.
    val badge = if (estado.desactualizado) null else estado.counts.porPagar.toString()
    return when {
        estado.sinConexion -> RecuadroPorPagar(true, badge, "Sin conexión", true)
        estado.desactualizado -> RecuadroPorPagar(true, null, "Sin actualizar", true)
        estado.counts.porEntregar > 0 -> RecuadroPorPagar(true, badge, "${estado.counts.porEntregar} por entregar", false)
        else -> RecuadroPorPagar(true, badge, null, false)
    }
}

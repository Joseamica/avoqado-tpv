package com.jaac.avoqado_tpv.core.presentation.screens

import com.jaac.avoqado_tpv.features.giftcards.data.SolicitudDeSaldo
import com.jaac.avoqado_tpv.features.giftcards.data.TextosDeSaldo
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
    // Con 0 por pagar no hay insignia (un «0» parece un aviso); «N por entregar» se queda abajo.
    val badge = if (estado.desactualizado || estado.counts.porPagar == 0) null else estado.counts.porPagar.toString()
    return when {
        estado.sinConexion -> RecuadroPorPagar(true, badge, "Sin conexión", true)
        estado.desactualizado -> RecuadroPorPagar(true, null, "Sin actualizar", true)
        estado.counts.porEntregar > 0 -> RecuadroPorPagar(true, badge, "${estado.counts.porEntregar} por entregar", false)
        else -> RecuadroPorPagar(true, badge, null, false)
    }
}

/**
 * D12: el aviso del Inicio, Cobrar y Pago rápido cuando hay un cobro con saldo guardado sin desenlace y la lista de folios no está al
 * día (la app arrancó sin red, se cayó la red o el SERVIDOR contestó un 4xx): sin lista no hay recuadro ni marca, y la cajera podía
 * cobrar el folio otra vez como venta normal. Con la lista al día no sale: la marca ya está en «Pedidos por pagar». null = nada.
 */
fun avisoDeSaldoSinConfirmar(estado: EstadoDeFolios, pendientes: List<SolicitudDeSaldo>): String? {
    val listaAlDia = estado.actualizadoEnMs != null && !estado.sinConexion && !estado.desactualizado
    if (listaAlDia || pendientes.isEmpty()) return null
    val cuales = pendientes.map { s ->
        val donde = s.folio?.let { "el folio $it" } ?: "un folio de la tienda"
        s.montoCents?.let { "$donde (${TextosDeSaldo.pesos(it)})" } ?: donde
    }
    val lista = if (cuales.size == 1) cuales.single() else cuales.dropLast(1).joinToString(", ") + " y " + cuales.last()
    return if (cuales.size == 1) {
        "Cobro con saldo sin confirmar en $lista. No lo cobres otra vez: se comprueba en «Pedidos por pagar» cuando haya conexión."
    } else {
        "Cobros con saldo sin confirmar en $lista. No los cobres otra vez: se comprueban en «Pedidos por pagar» cuando haya conexión."
    }
}

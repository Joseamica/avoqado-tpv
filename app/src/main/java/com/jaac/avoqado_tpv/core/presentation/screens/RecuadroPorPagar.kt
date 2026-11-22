package com.jaac.avoqado_tpv.core.presentation.screens

import com.jaac.avoqado_tpv.core.presentation.navigation.NavRoute
import com.jaac.avoqado_tpv.features.giftcards.data.SolicitudDeSaldo
import com.jaac.avoqado_tpv.features.giftcards.data.TextosDeSaldo
import com.jaac.avoqado_tpv.features.onlineorders.domain.ComandaPorImprimir
import com.jaac.avoqado_tpv.features.onlineorders.domain.EstadoDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.tarjetasQueAvisan
import com.jaac.avoqado_tpv.features.onlineorders.presentation.TextosPorPagar

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

/**
 * `null` si el servidor no pidió el recuadro. Misma condición que «Cobrar»: turno abierto y entrada registrada.
 * D661: [ahoraMs] decide si los pagados en línea de la lista avisan ([tarjetasQueAvisan]).
 */
fun recuadroPorPagar(
    estado: EstadoDeFolios,
    canOperate: Boolean,
    canWork: Boolean,
    motivoSinTurno: String?,
    ahoraMs: Long = System.currentTimeMillis(),
): RecuadroPorPagar? {
    // D661: los pagados en línea que avisan (lectura en vivo, sin los que otra caja cobra) cuentan en la insignia y se dicen abajo:
    // es por lo que la terminal suena. Mientras haya uno, el recuadro sigue aunque la tienda cambie de modo o se apague.
    val porAbrir = tarjetasQueAvisan(estado, ahoraMs).size
    val pagadosEnLinea = if (porAbrir > 0) TextosPorPagar.pagadosEnLinea(porAbrir) else null
    if (!estado.showTile && porAbrir == 0) return null
    if (!canWork) return RecuadroPorPagar(false, "Registra tu entrada", pagadosEnLinea, false)
    // Ruling 67: sin turno sigue apagado (el turno se abre primero) y, si suena uno pagado en línea, dice por dónde se entra.
    if (!canOperate) {
        val entrada = if (porAbrir > 0) TextosPorPagar.abreTuTurno(porAbrir) else null
        return RecuadroPorPagar(false, motivoSinTurno ?: "Abre la caja primero", entrada, entrada != null)
    }

    // Tras un 4xx la lista es la última buena pero ya no es fiable: no se enseña su número como si fuera de hoy.
    // Con 0 por pagar no hay insignia (un «0» parece un aviso); «N por entregar» se queda abajo.
    val numero = estado.counts.porPagar + porAbrir
    val badge = if (estado.desactualizado || numero == 0) null else numero.toString()
    return when {
        estado.sinConexion -> RecuadroPorPagar(true, badge, "Sin conexión", true)
        estado.desactualizado -> RecuadroPorPagar(true, null, "Sin actualizar", true)
        pagadosEnLinea != null -> RecuadroPorPagar(true, badge, pagadosEnLinea, false)
        estado.counts.porEntregar > 0 -> RecuadroPorPagar(true, badge, "${estado.counts.porEntregar} por entregar", false)
        else -> RecuadroPorPagar(true, badge, null, false)
    }
}

/**
 * D12: el aviso del Inicio, Cobrar y Pago rápido cuando hay un cobro con saldo guardado sin desenlace y la lista de folios no está al
 * día (la app arrancó sin red, se cayó la red o el SERVIDOR contestó un 4xx): sin lista no hay recuadro ni marca, y la cajera podía
 * cobrar el folio otra vez como venta normal. [pendientes] null = no se pudo leer el almacén: se dice SIEMPRE, también con la lista al
 * día (la lista tampoco puede marcar nada: lee el mismo almacén), y nunca se toma por «no hay» (D12 r3).
 *
 * Con la lista al día no sale: si el folio viene en ella, la marca está en «Pedidos por pagar»; si ya no viene, ya no se puede cobrar
 * como folio ni deber nada (el SERVIDOR nunca vence una cuenta con dinero y rechaza cobrar una cuenta vencida; uno pagado completo sale
 * al entregarse). ponytail: una solicitud de un folio que venció tras la duda nunca se borra y vuelve a salir aquí en cada apagón
 * (deuda de la parte 2, en `docs/audit/deuda-encontrada.md`); se quita cuando la comprobación sepa cerrar un folio vencido.
 */
fun avisoDeSaldoSinConfirmar(estado: EstadoDeFolios, pendientes: List<SolicitudDeSaldo>?): String? {
    if (pendientes == null) return SALDO_GUARDADO_ILEGIBLE
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

/** D661: la banda de los pagados en línea. [llevaAPorPagar] = su «Ver» abre «Pedidos por pagar»; si no, sólo informa. */
data class AvisoDePagados(val texto: String, val llevaAPorPagar: Boolean)

/**
 * D661 (Ruling 19 · 67 · 68): mientras suena un pedido pagado en línea, la banda lo dice arriba de la pantalla si [aLaVista]
 * ([avisoALaVista]). null = no hay nada que avisar ahí (o la lista no es de una lectura en vivo: sin red o vieja, no).
 */
fun avisoDePagadosEnLinea(estado: EstadoDeFolios, ahoraMs: Long, aLaVista: Boolean): AvisoDePagados? {
    if (!aLaVista) return null
    val n = tarjetasQueAvisan(estado, ahoraMs).size.takeIf { it > 0 } ?: return null
    // Ruling 67: sin entrada (o sin turno) dice por dónde se entra y sólo informa: nunca entra a «Pedidos por pagar» saltándose el Inicio.
    // Ronda 4: en el orden del recuadro del Inicio, que pide primero la entrada y después el turno.
    return when {
        estado.sinEntrada -> AvisoDePagados(TextosPorPagar.registraTuEntrada(n), llevaAPorPagar = false)
        estado.sinTurno -> AvisoDePagados(TextosPorPagar.abreTuTurno(n), llevaAPorPagar = false)
        else -> AvisoDePagados(TextosPorPagar.avisoDePagadosEnLinea(n), llevaAPorPagar = true)
    }
}

/**
 * D661 (Ruling 68, enmendado): ¿la cajera puede VER aquí el aviso de los pagados en línea? Sólo entonces hay banda y la caja se
 * anuncia como «puedo avisar». [ventaEnCurso]: hay una venta a medias en el cobro con tarjeta (método, propina, calificación,
 * leyendo la tarjeta, una devolución hablando con el procesador); su recibo o su rechazo ya no es venta en curso. Con la app bloqueada
 * (sólo se quita a distancia), en mantenimiento, en el kiosco o antes de la sesión, la cajera no puede usar la terminal: no.
 */
fun avisoALaVista(ruta: String?, ventaEnCurso: Boolean, bloqueada: Boolean, mantenimiento: Boolean): Boolean =
    ruta != null && ruta !in NavRoute.RUTAS_SIN_AVISO_DE_PAGADOS_EN_LINEA && !bloqueada && !mantenimiento &&
        !(ventaEnCurso && ruta in NavRoute.RUTAS_DEL_COBRO_CON_TARJETA)

/** D661 (Ruling 69, enmendado): UNA banda para todas las comandas que no salieron aquí. null = ninguna. */
fun avisoDeComandasSinSalir(comandas: List<ComandaPorImprimir>): String? = when (comandas.size) {
    0 -> null
    1 -> TextosPorPagar.comandaNoSalio(comandas.single().folio)
    else -> TextosPorPagar.comandasNoSalieron(comandas.size)
}

/** D12 (Codex r1 #2, r3): el almacén cifrado no se pudo leer; no se sabe si hay un cobro con saldo en duda. */
const val SALDO_GUARDADO_ILEGIBLE =
    "Esta terminal no pudo leer sus cobros con saldo guardados. Antes de cobrar un folio de la tienda, aquí o como venta normal, " +
        "revisa en el panel si ya está pagado."

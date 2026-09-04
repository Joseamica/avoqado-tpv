package com.jaac.avoqado_tpv.features.reports.domain.models

import java.math.BigDecimal

/**
 * Cobros agrupados por método de pago, **con la propina desglosada**.
 *
 * 🔴 Es EL MISMO dato que imprime el corte de caja de la tablet: los dos salen de
 * `GET mobile/venues/{venueId}/cash-drawer/tender-breakdown`. Por eso los dos
 * tickets quedan homologados **por construcción** y no porque alguien se acuerde de
 * mantenerlos parecidos — que era justo lo que había fallado.
 *
 * El otro camino (`shifts-summary`, que sigue alimentando el resto del reporte) sólo
 * manda `payment.amount` por método: **no trae la propina por método**, y sin ella el
 * desglose "Venta / Propina / Total" que pidió el cliente no se puede armar.
 */
data class TenderRow(
    /** Valor crudo del enum del servidor: `CASH`, `CREDIT_CARD`, `DEBIT_CARD`… */
    val method: String,
    /** Lo que entró por ese método: venta + propina. Es lo que manda el servidor. */
    val total: BigDecimal,
    /** Cuánto de ese total fue propina. */
    val tips: BigDecimal
) {
    /**
     * La venta sin propina.
     *
     * Se DERIVA de `total - tips` en vez de pedirse aparte: así los tres números del
     * ticket siempre suman entre sí. Si el servidor mandara los tres, cualquier
     * desfase de redondeo saldría impreso como una resta que no cuadra.
     */
    val sale: BigDecimal get() = total - tips
}

/**
 * Resultado de consultar el desglose. **Tres estados, no dos** — la lección que ya
 * costó un defecto visible para el founder en avoqado-android (28-ago): una lista
 * vacía es un dato legítimo ("no hubo cobros") y NO es lo mismo que no haber podido
 * preguntar. El papel se queda en el cajón como comprobante: decir "sin conexión"
 * cuando sí la había vuelve el ticket una prueba falsa.
 */
sealed interface TenderBreakdownResult {
    /** El servidor contestó. La lista puede venir vacía: no hubo cobros. */
    data class Available(val rows: List<TenderRow>) : TenderBreakdownResult

    /** No se pudo consultar (sin red, 4xx/5xx, cuerpo ilegible). */
    data object Unavailable : TenderBreakdownResult
}

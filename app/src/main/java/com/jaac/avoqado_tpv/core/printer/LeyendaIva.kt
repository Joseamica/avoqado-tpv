package com.jaac.avoqado_tpv.core.printer

/**
 * IVA por producto, plan 7 (D3 y 6.2): el ticket NO calcula IVA. En México el precio ya lo trae (LFPC art. 7 Bis) y la
 * cantidad exacta por tasa vive en la factura. Una sola constante para la PAX (`PrinterManager`) y la Nexgo
 * (`AngelPayTicketBuilder`): es el mismo texto que imprimen Android, iOS y el intérprete del servidor.
 */
object LeyendaIva {
    const val TEXTO = "IVA incluido cuando aplica"

    /** Lo que la PAX imprime en el lugar del IVA. Una devolución no lleva leyenda, como hoy no llevaba desglose. */
    fun renglonesDelTicket(esDevolucion: Boolean): List<String> = if (esDevolucion) emptyList() else listOf(TEXTO)
}

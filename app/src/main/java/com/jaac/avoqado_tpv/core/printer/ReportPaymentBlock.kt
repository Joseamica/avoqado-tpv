package com.jaac.avoqado_tpv.core.printer

import com.jaac.avoqado_tpv.features.reports.domain.models.PaymentMethodBreakdown
import com.jaac.avoqado_tpv.features.reports.domain.models.TenderBreakdownResult
import com.jaac.avoqado_tpv.features.reports.domain.models.TenderRow
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Arma el bloque "DESGLOSE POR METODO DE PAGO" del ticket de Reporte de ventas.
 *
 * Vive FUERA de `PrinterManager` porque `PrinterManager` necesita el SDK de PAX para
 * existir y no se puede instanciar en una prueba unitaria: aquí lo que sale en el
 * papel se puede leer sin una impresora enfrente. Mismo motivo por el que
 * `CorteTicketBuilder` vive fuera del ViewModel en avoqado-android.
 *
 * ## Por qué existe (queja real de Testarudo Café, 3-sep-2026)
 *
 * *"No cuadra el reporte del ticket (TPV) con el corte parcial… homologar efectivo,
 * tarjeta de crédito y tarjeta de débito…. No poner solo 'tarjeta'"*. Decisión final
 * del founder: **"que salga todo desglosado perfecto… con y sin propina más
 * especificado"** — cada método imprime sus tres números y nadie tiene que restar.
 *
 * Eran tres defectos, y ninguno era dinero mal calculado:
 *
 * 1. Crédito y débito llegaban separados del servidor y la app los sumaba en un solo
 *    renglón "Tarjeta".
 * 2. Los métodos que no son efectivo/tarjeta/vale (billetera, transferencia, cripto)
 *    **no se imprimían** pero SÍ entraban en el total: el desglose no cuadraba con su
 *    propia suma y nadie podía explicar el hueco.
 * 3. Los dos tickets medían cosas distintas y ninguno lo decía. Verificado leyendo el
 *    servidor, no supuesto:
 *
 * | Ticket | Endpoint | Importe por método |
 * |---|---|---|
 * | Reporte de ventas (antes) | `tpv/venues/:id/shifts-summary` | `payment.amount` — SIN propina |
 * | Corte de caja (tablet) | `…/cash-drawer/tender-breakdown` | `amount + tipAmount` — CON propina |
 *
 * 🔴 **La homologación se logra usando EL MISMO endpoint que la tablet**, no imitando
 * su formato: los dos leen `tender-breakdown`, así que no pueden divergir aunque
 * nadie se acuerde de mantenerlos iguales.
 *
 * ## Ancho: 32 columnas, medidas
 *
 * Papel térmico de la PAX (`PrinterManager.LINE_WIDTH`). Una línea de más se corta
 * **sin avisar**, así que los tres importes de cada método van en RENGLONES y no en
 * columnas: una tabla de etiqueta + 3 importes pide ~38 columnas y no cabe. El
 * renglón más ancho que puede salir aquí es `TOTAL COBRADO` + `$21,606.40` = 23 de 32.
 * Y [dosColumnas] nunca recorta un importe: si no cabe, la etiqueta se va sola.
 */
object ReportPaymentBlock {

    /** Papel térmico de la PAX A910S. Espejo de `PrinterManager.LINE_WIDTH`. */
    const val WIDTH = 32

    /**
     * Etiquetas espejo de `tenderLabel()` en avoqado-android
     * (`cashdrawer/presentation/EndOfDayScreen.kt`), que es lo que imprime el corte de
     * caja. Se copian palabra por palabra —sin acentos, que es la convención de ESTE
     * ticket: no hay una sola tilde en `printReceipt` ni en `printReport`— para que
     * los dos papeles se lean igual y el cajero no tenga que traducir.
     *
     * 🔴 Si allá cambian, aquí cambian: que hablen igual es el objetivo entero.
     */
    fun tenderLabel(method: String): String = when (method.uppercase()) {
        "CASH" -> "Efectivo"
        "CREDIT_CARD" -> "Tarjeta de credito"
        "DEBIT_CARD" -> "Tarjeta de debito"
        "DIGITAL_WALLET" -> "Billetera digital"
        "BANK_TRANSFER" -> "Transferencia"
        "CARD" -> "Tarjeta"
        "VOUCHER" -> "Vale"
        else -> "Otros"
    }

    const val TITLE = "DESGLOSE POR METODO DE PAGO"

    private val DIVIDER = "-".repeat(WIDTH)

    /**
     * Renglones del bloque, listos para `printStr` (sin `\n`: lo pone quien imprime).
     *
     * @param tenders desglose con propina por método — el mismo dato que el corte de
     *   la tablet. `Unavailable` cuando no se pudo consultar.
     * @param fallback desglose de `shifts-summary`, que SÍ separa crédito de débito
     *   pero no trae la propina por método. Sólo se usa si [tenders] no está.
     */
    fun lines(tenders: TenderBreakdownResult, fallback: PaymentMethodBreakdown): List<String> {
        val out = mutableListOf<String>()
        out += DIVIDER
        out += TITLE
        out += ""

        when (tenders) {
            is TenderBreakdownResult.Available -> {
                // Cero cobros es un dato, no un fallo. Mismo texto que el corte de la
                // tablet ("No hubo cobros en este corte."), para que se lean igual.
                if (tenders.rows.isEmpty()) {
                    out += "No hubo cobros en este periodo."
                    out += ""
                    return out
                }
                // Mayor primero, igual que el corte de la tablet.
                tenders.rows.sortedByDescending { it.total }.forEach { row ->
                    out += tenderLabel(row.method)
                    out += dosColumnas("  Venta", money(row.sale))
                    out += dosColumnas("  Propina", money(row.tips))
                    out += dosColumnas("  Total", money(row.total))
                    out += ""
                }
                val venta = tenders.rows.fold(BigDecimal.ZERO) { acc, r -> acc + r.sale }
                val propina = tenders.rows.fold(BigDecimal.ZERO) { acc, r -> acc + r.tips }
                out += DIVIDER
                out += dosColumnas("TOTAL VENTA", money(venta))
                out += dosColumnas("TOTAL PROPINA", money(propina))
                out += dosColumnas("TOTAL COBRADO", money(venta + propina))
            }

            TenderBreakdownResult.Unavailable -> {
                // 🔴 Se imprime lo que SÍ se sabe (la venta por método, que ya venía en
                // el reporte) y se DICE qué falta. Callarlo dejaría un ticket que se ve
                // completo y no lo está — el defecto que este bloque vino a cerrar.
                val filas = fallbackRows(fallback)
                if (filas.isEmpty()) {
                    out += "No hubo cobros en este periodo."
                    out += ""
                    return out
                }
                filas.forEach { (label, amount) ->
                    out += dosColumnas(label, money(amount))
                }
                out += DIVIDER
                out += dosColumnas("TOTAL VENTA", money(filas.fold(BigDecimal.ZERO) { a, r -> a + r.second }))
                out += ""
                out += "No se pudo consultar la"
                out += "propina por metodo. Los"
                out += "importes son SIN propina."
            }
        }

        out += ""
        return out
    }

    /**
     * Venta por método desde `shifts-summary`, sólo para el camino de respaldo.
     *
     * 🔴 Crédito y débito son DOS entradas: no se suman ni aquí ni en el papel.
     * `cardAmount` (el agregado) no se usa a propósito — imprimirlo junto a las dos
     * filas contaría el mismo dinero dos veces.
     *
     * Se omite lo que está exactamente en cero; un método NEGATIVO (más reembolsos que
     * cobros) sí se imprime, que es justo cuando alguien quiere verlo.
     */
    fun fallbackRows(b: PaymentMethodBreakdown): List<Pair<String, BigDecimal>> = listOf(
        tenderLabel("CASH") to b.cashAmount,
        tenderLabel("CREDIT_CARD") to b.creditCardAmount,
        tenderLabel("DEBIT_CARD") to b.debitCardAmount,
        tenderLabel("CARD") to b.unspecifiedCardAmount,
        tenderLabel("VOUCHER") to b.voucherAmount,
        tenderLabel("OTHER") to b.otherAmount,
    ).filter { it.second.compareTo(BigDecimal.ZERO) != 0 }

    /**
     * Etiqueta a la izquierda, importe a la derecha, exactamente [width] columnas.
     *
     * 🔴 Si no caben juntos NO se recorta el número: la etiqueta se va sola a su
     * renglón y el importe queda alineado a la derecha debajo. Recortar un importe en
     * un ticket de dinero es exactamente el defecto que este bloque vino a cerrar.
     */
    fun dosColumnas(left: String, right: String, width: Int = WIDTH): String =
        if (left.length + right.length < width) {
            left + " ".repeat(width - left.length - right.length) + right
        } else if (left.isEmpty()) {
            right.padStart(width)
        } else {
            left + "\n" + right.padStart(width)
        }

    /** `$8,966.50` — con separador de miles, que es como los leyó el founder. */
    fun money(value: BigDecimal): String =
        "$" + String.format(Locale.US, "%,.2f", value.setScale(2, RoundingMode.HALF_UP))
}

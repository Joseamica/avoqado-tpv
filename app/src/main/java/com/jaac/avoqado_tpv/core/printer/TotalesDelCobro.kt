package com.jaac.avoqado_tpv.core.printer

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Los renglones de dinero del papel del COBRO de la PAX (IVA por producto, plan 7a, Tarea 6 ronda 1). Misma regla que el papel del
 * cobro de las apps (`TotalesDelTicket.desdeLoCobrado`) y el recibo del servidor: Subtotal BRUTO − Descuento + Propina = TOTAL = lo
 * cobrado. La terminal no sabe si este cobro es el único de la cuenta: el descuento sólo se desglosa cuando los renglones de la cuenta
 * menos su descuento dan EXACTAMENTE lo cobrado. Si no (pago parcial, sin renglones, cargo o IVA aparte que la terminal no ve), el
 * subtotal es lo cobrado y no hay renglón de descuento: nunca se prorratea ni se adivina, y nunca sale un renglón de $0.00.
 */
object TotalesDelCobro {
    data class Papel(val subtotal: BigDecimal, val descuento: BigDecimal, val propina: BigDecimal, val total: BigDecimal)

    /** [total] es lo cobrado CON propina (como el `amount` de `PrinterManager.printReceipt`). */
    fun desdeLoCobrado(total: BigDecimal, propina: BigDecimal, descuento: BigDecimal, renglones: List<BigDecimal>): Papel {
        val cobrado = total - propina
        val bruto = renglones.fold(BigDecimal.ZERO, BigDecimal::add)
        val cubreLaCuenta = descuento > BigDecimal.ZERO && renglones.isNotEmpty() && (bruto - descuento).compareTo(cobrado) == 0
        return if (cubreLaCuenta) Papel(bruto, descuento, propina, total) else Papel(cobrado, BigDecimal.ZERO, propina, total)
    }

    /** Lo que imprime la PAX antes del divisor y el TOTAL, en el orden de las apps: Subtotal → Descuento → leyenda → Propina. */
    fun renglonesPax(p: Papel, leyenda: List<String>): List<String> = buildList {
        add(renglon("Subtotal:", "$${formato(p.subtotal)} MXN"))
        if (p.descuento > BigDecimal.ZERO) add(renglon("Descuento:", "-$${formato(p.descuento)} MXN"))
        addAll(leyenda)
        if (p.propina > BigDecimal.ZERO) add(renglon("Propina:", "$${formato(p.propina)} MXN"))
    }

    private fun renglon(etiqueta: String, importe: String) = etiqueta.padEnd(22, ' ') + importe

    private fun formato(v: BigDecimal) = v.setScale(2, RoundingMode.HALF_UP).toPlainString()
}

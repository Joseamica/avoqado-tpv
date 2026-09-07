package com.jaac.avoqado_tpv.features.reports.domain.models

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Payment Method Breakdown
 *
 * Distribution of payment methods used during a period.
 * Used for reconciliation and commission tracking.
 *
 * 🔴 **Crédito y débito viajan SEPARADOS hasta el ticket.** El servidor los manda
 * como métodos distintos (`CREDIT_CARD` / `DEBIT_CARD`, ver el enum `PaymentMethod`
 * de `avoqado-server`) y esta app los colapsaba en una sola cifra "Tarjeta". El
 * dueño de Testarudo Café lo pidió con esas palabras (3-sep-2026): *"homologar
 * efectivo, tarjeta de crédito y tarjeta de débito… no poner solo tarjeta"*.
 * `cardAmount` sigue existiendo como la SUMA de las tres, para quien necesite el
 * agregado — pero no se imprime junto al desglose: sería contar dos veces.
 *
 * ⚠️ Los importes son **ventas SIN propina**: vienen de `payment.amount` del
 * endpoint `shifts-summary`. El corte de caja de avoqado-android usa otro endpoint
 * (`tender-breakdown`) que suma `amount + tipAmount`, así que sus cifras por método
 * son MAYORES. Los dos tickets tienen razón; lo que faltaba era decirlo.
 *
 * @property cashAmount Total cash payments
 * @property creditCardAmount Total de tarjeta de CRÉDITO (`CREDIT_CARD`)
 * @property debitCardAmount Total de tarjeta de DÉBITO (`DEBIT_CARD`)
 * @property unspecifiedCardAmount Tarjeta sin especificar: el método `CARD` a secas
 *   y los turnos agregados (`fromShifts`), donde el desglose crédito/débito no existe
 * @property voucherAmount Total voucher/wallet payments
 * @property otherAmount Total other payment methods (billetera, transferencia, cripto…)
 * @property totalAmount Total of all payments (for validation)
 */
data class PaymentMethodBreakdown(
    val cashAmount: BigDecimal,
    val creditCardAmount: BigDecimal,
    val debitCardAmount: BigDecimal,
    val unspecifiedCardAmount: BigDecimal,
    val voucherAmount: BigDecimal,
    val otherAmount: BigDecimal,
    val totalAmount: BigDecimal,
    val cashPercentage: BigDecimal,
    val creditCardPercentage: BigDecimal,
    val debitCardPercentage: BigDecimal,
    val unspecifiedCardPercentage: BigDecimal,
    val voucherPercentage: BigDecimal,
    val otherPercentage: BigDecimal
) {
    /**
     * Toda la tarjeta junta (crédito + débito + sin especificar).
     *
     * Es un DERIVADO, no un campo: así nadie puede construir una instancia donde el
     * agregado no cuadre con el desglose. No se imprime en el mismo bloque que las
     * tres filas — juntos serían el doble del dinero real.
     */
    val cardAmount: BigDecimal
        get() = creditCardAmount + debitCardAmount + unspecifiedCardAmount

    val cardPercentage: BigDecimal
        get() = creditCardPercentage + debitCardPercentage + unspecifiedCardPercentage

    /**
     * Individual payment method entry for chart display
     */
    data class PaymentMethodEntry(
        val method: PaymentMethod,
        val amount: BigDecimal,
        val percentage: BigDecimal,
        val label: String
    ) {
        fun formatAmount(): String {
            return "$${amount.setScale(2, RoundingMode.HALF_UP)}"
        }

        fun formatPercentage(): String {
            return "${percentage.setScale(1, RoundingMode.HALF_UP)}%"
        }
    }

    enum class PaymentMethod {
        CASH,
        CARD,
        VOUCHER,
        OTHER
    }

    companion object {
        /**
         * Create breakdown from shift data
         *
         * ⚠️ El turno guarda `totalCardPayments` YA colapsado: a esta granularidad el
         * desglose crédito/débito no existe. Cae entero en "tarjeta sin especificar"
         * en vez de inventarse un reparto — el ticket lo imprimirá como "Tarjeta".
         * Este camino es sólo el respaldo de `shifts-summary` (ver `ReportsRepositoryImpl`).
         */
        fun fromShifts(shifts: List<com.jaac.avoqado_tpv.features.shift.domain.Shift>): PaymentMethodBreakdown {
            if (shifts.isEmpty()) {
                return empty()
            }

            val cashAmount = shifts.sumOf { it.totalCashPayments }
            val unspecifiedCardAmount = shifts.sumOf { it.totalCardPayments }
            val voucherAmount = shifts.sumOf { it.totalVoucherPayments }
            val otherAmount = shifts.sumOf { it.totalOtherPayments }
            val totalAmount = cashAmount + unspecifiedCardAmount + voucherAmount + otherAmount

            fun pct(part: BigDecimal): BigDecimal = if (totalAmount > BigDecimal.ZERO) {
                part.divide(totalAmount, 4, RoundingMode.HALF_UP) * BigDecimal(100)
            } else {
                BigDecimal.ZERO
            }

            return PaymentMethodBreakdown(
                cashAmount = cashAmount,
                creditCardAmount = BigDecimal.ZERO,
                debitCardAmount = BigDecimal.ZERO,
                unspecifiedCardAmount = unspecifiedCardAmount,
                voucherAmount = voucherAmount,
                otherAmount = otherAmount,
                totalAmount = totalAmount,
                cashPercentage = pct(cashAmount),
                creditCardPercentage = BigDecimal.ZERO,
                debitCardPercentage = BigDecimal.ZERO,
                unspecifiedCardPercentage = pct(unspecifiedCardAmount),
                voucherPercentage = pct(voucherAmount),
                otherPercentage = pct(otherAmount)
            )
        }

        fun empty(): PaymentMethodBreakdown {
            return PaymentMethodBreakdown(
                cashAmount = BigDecimal.ZERO,
                creditCardAmount = BigDecimal.ZERO,
                debitCardAmount = BigDecimal.ZERO,
                unspecifiedCardAmount = BigDecimal.ZERO,
                voucherAmount = BigDecimal.ZERO,
                otherAmount = BigDecimal.ZERO,
                totalAmount = BigDecimal.ZERO,
                cashPercentage = BigDecimal.ZERO,
                creditCardPercentage = BigDecimal.ZERO,
                debitCardPercentage = BigDecimal.ZERO,
                unspecifiedCardPercentage = BigDecimal.ZERO,
                voucherPercentage = BigDecimal.ZERO,
                otherPercentage = BigDecimal.ZERO
            )
        }
    }

    /**
     * Get list of payment method entries (for chart display)
     * Only includes methods with non-zero amounts
     *
     * ⚠️ La GRÁFICA de la pantalla sigue enseñando UNA rebanada "Tarjeta" con el
     * agregado. Es menos detalle que el ticket, no una cifra distinta: los totales
     * coinciden. Partirla en dos rebanadas es un cambio de UI que hay que ver en una
     * PAX A910S (360x640dp) antes de soltarlo, y la queja del cliente era el papel.
     */
    fun getEntries(): List<PaymentMethodEntry> {
        val entries = mutableListOf<PaymentMethodEntry>()

        if (cashAmount > BigDecimal.ZERO) {
            entries.add(
                PaymentMethodEntry(
                    method = PaymentMethod.CASH,
                    amount = cashAmount,
                    percentage = cashPercentage,
                    label = "Efectivo"
                )
            )
        }

        if (cardAmount > BigDecimal.ZERO) {
            entries.add(
                PaymentMethodEntry(
                    method = PaymentMethod.CARD,
                    amount = cardAmount,
                    percentage = cardPercentage,
                    label = "Tarjeta"
                )
            )
        }

        if (voucherAmount > BigDecimal.ZERO) {
            entries.add(
                PaymentMethodEntry(
                    method = PaymentMethod.VOUCHER,
                    amount = voucherAmount,
                    percentage = voucherPercentage,
                    label = "Vale"
                )
            )
        }

        if (otherAmount > BigDecimal.ZERO) {
            entries.add(
                PaymentMethodEntry(
                    method = PaymentMethod.OTHER,
                    amount = otherAmount,
                    percentage = otherPercentage,
                    label = "Otro"
                )
            )
        }

        return entries
    }
}

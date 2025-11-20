package com.jaac.avoqado_tpv.features.reports.domain.models

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Payment Method Breakdown
 *
 * Distribution of payment methods used during a period.
 * Used for reconciliation and commission tracking.
 *
 * @property cashAmount Total cash payments
 * @property cardAmount Total card payments
 * @property voucherAmount Total voucher/wallet payments
 * @property otherAmount Total other payment methods
 * @property totalAmount Total of all payments (for validation)
 * @property cashPercentage Percentage of cash payments
 * @property cardPercentage Percentage of card payments
 * @property voucherPercentage Percentage of voucher payments
 * @property otherPercentage Percentage of other payments
 */
data class PaymentMethodBreakdown(
    val cashAmount: BigDecimal,
    val cardAmount: BigDecimal,
    val voucherAmount: BigDecimal,
    val otherAmount: BigDecimal,
    val totalAmount: BigDecimal,
    val cashPercentage: BigDecimal,
    val cardPercentage: BigDecimal,
    val voucherPercentage: BigDecimal,
    val otherPercentage: BigDecimal
) {
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
         */
        fun fromShifts(shifts: List<com.jaac.avoqado_tpv.features.shift.domain.Shift>): PaymentMethodBreakdown {
            if (shifts.isEmpty()) {
                return empty()
            }

            val cashAmount = shifts.sumOf { it.totalCashPayments }
            val cardAmount = shifts.sumOf { it.totalCardPayments }
            val voucherAmount = shifts.sumOf { it.totalVoucherPayments }
            val otherAmount = shifts.sumOf { it.totalOtherPayments }
            val totalAmount = cashAmount + cardAmount + voucherAmount + otherAmount

            // Calculate percentages
            val cashPercentage = if (totalAmount > BigDecimal.ZERO) {
                (cashAmount.divide(totalAmount, 4, RoundingMode.HALF_UP) * BigDecimal(100))
            } else {
                BigDecimal.ZERO
            }

            val cardPercentage = if (totalAmount > BigDecimal.ZERO) {
                (cardAmount.divide(totalAmount, 4, RoundingMode.HALF_UP) * BigDecimal(100))
            } else {
                BigDecimal.ZERO
            }

            val voucherPercentage = if (totalAmount > BigDecimal.ZERO) {
                (voucherAmount.divide(totalAmount, 4, RoundingMode.HALF_UP) * BigDecimal(100))
            } else {
                BigDecimal.ZERO
            }

            val otherPercentage = if (totalAmount > BigDecimal.ZERO) {
                (otherAmount.divide(totalAmount, 4, RoundingMode.HALF_UP) * BigDecimal(100))
            } else {
                BigDecimal.ZERO
            }

            return PaymentMethodBreakdown(
                cashAmount = cashAmount,
                cardAmount = cardAmount,
                voucherAmount = voucherAmount,
                otherAmount = otherAmount,
                totalAmount = totalAmount,
                cashPercentage = cashPercentage,
                cardPercentage = cardPercentage,
                voucherPercentage = voucherPercentage,
                otherPercentage = otherPercentage
            )
        }

        fun empty(): PaymentMethodBreakdown {
            return PaymentMethodBreakdown(
                cashAmount = BigDecimal.ZERO,
                cardAmount = BigDecimal.ZERO,
                voucherAmount = BigDecimal.ZERO,
                otherAmount = BigDecimal.ZERO,
                totalAmount = BigDecimal.ZERO,
                cashPercentage = BigDecimal.ZERO,
                cardPercentage = BigDecimal.ZERO,
                voucherPercentage = BigDecimal.ZERO,
                otherPercentage = BigDecimal.ZERO
            )
        }
    }

    /**
     * Get list of payment method entries (for chart display)
     * Only includes methods with non-zero amounts
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

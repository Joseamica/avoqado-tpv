package com.jaac.avoqado_tpv.features.reports.data.dto

import com.google.gson.annotations.SerializedName
import com.jaac.avoqado_tpv.features.reports.domain.models.PaymentMethodBreakdown
import com.jaac.avoqado_tpv.features.reports.domain.models.SalesSummary
import com.jaac.avoqado_tpv.features.reports.domain.models.StaffSales
import com.jaac.avoqado_tpv.features.reports.domain.models.WaiterTip
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Response wrapper for GET /tpv/venues/{venueId}/shifts-summary
 *
 * Backend-aggregated sales data that includes both shift-based
 * and orphan payments (venues without shifts module).
 */
data class ShiftsSummaryResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: ShiftsSummaryData
)

data class ShiftsSummaryData(
    @SerializedName("summary") val summary: SummaryDto,
    @SerializedName("paymentMethods") val paymentMethods: List<PaymentMethodDto>,
    @SerializedName("waiterTips") val waiterTips: List<WaiterTipDto>? = null,
    @SerializedName("salesTrend") val salesTrend: List<SalesTrendPointDto>? = null,
    @SerializedName("staffSales") val staffSales: List<StaffSalesDto>? = null
)

data class SummaryDto(
    @SerializedName("totalSales") val totalSales: Double,
    @SerializedName("totalTips") val totalTips: Double,
    @SerializedName("ordersCount") val ordersCount: Int,
    @SerializedName("averageTipPercentage") val averageTipPercentage: Double,
    @SerializedName("ratingsCount") val ratingsCount: Int? = 0
)

data class PaymentMethodDto(
    @SerializedName("method") val method: String,
    @SerializedName("total") val total: Double,
    @SerializedName("percentage") val percentage: Double
)

data class WaiterTipDto(
    @SerializedName("staffId") val staffId: String,
    @SerializedName("name") val name: String,
    @SerializedName("amount") val amount: Double,
    @SerializedName("count") val count: Int
)

data class StaffSalesDto(
    @SerializedName("staffId") val staffId: String,
    @SerializedName("name") val name: String,
    @SerializedName("totalSales") val totalSales: Double,
    @SerializedName("totalOrders") val totalOrders: Int,
    @SerializedName("totalTips") val totalTips: Double
)

data class SalesTrendPointDto(
    @SerializedName("label") val label: String,
    @SerializedName("value") val value: Double
)

/**
 * Map backend shifts-summary to domain SalesSummary
 */
fun ShiftsSummaryData.toSalesSummary(): SalesSummary {
    val totalSales = BigDecimal.valueOf(summary.totalSales)
    val totalOrders = summary.ordersCount
    val totalTips = BigDecimal.valueOf(summary.totalTips)

    val averageOrderValue = if (totalOrders > 0) {
        totalSales.divide(BigDecimal(totalOrders), 2, RoundingMode.HALF_UP)
    } else {
        BigDecimal.ZERO
    }

    val mappedWaiterTips = waiterTips?.map { wt ->
        WaiterTip(
            staffId = wt.staffId,
            name = wt.name,
            amount = BigDecimal.valueOf(wt.amount),
            count = wt.count
        )
    } ?: emptyList()

    val mappedStaffSales = staffSales?.map { ss ->
        StaffSales(
            staffId = ss.staffId,
            name = ss.name,
            totalSales = BigDecimal.valueOf(ss.totalSales),
            totalOrders = ss.totalOrders,
            totalTips = BigDecimal.valueOf(ss.totalTips)
        )
    } ?: emptyList()

    return SalesSummary(
        totalSales = totalSales,
        totalOrders = totalOrders,
        // `shifts-summary` no manda productos. Salian SIEMPRE en 0 y por eso el
        // ticket dejo de imprimirlos (decision del founder, 3-sep-2026). El campo
        // se conserva porque el camino de TURNOS (`SalesSummary.fromShifts`) si lo
        // llena y la pantalla de reportes lo usa.
        totalProductsSold = 0,
        totalTips = totalTips,
        totalShifts = 0,
        averageOrderValue = averageOrderValue,
        averageProductsPerOrder = BigDecimal.ZERO,
        averageTipPercentage = BigDecimal.valueOf(summary.averageTipPercentage),
        ratingsCount = summary.ratingsCount ?: 0,
        waiterTips = mappedWaiterTips,
        staffSales = mappedStaffSales
    )
}

/**
 * Map backend shifts-summary payment methods to domain PaymentMethodBreakdown
 */
fun ShiftsSummaryData.toPaymentBreakdown(): PaymentMethodBreakdown {
    var cashAmount = BigDecimal.ZERO
    var creditCardAmount = BigDecimal.ZERO
    var debitCardAmount = BigDecimal.ZERO
    var unspecifiedCardAmount = BigDecimal.ZERO
    var voucherAmount = BigDecimal.ZERO
    var otherAmount = BigDecimal.ZERO

    for (pm in paymentMethods) {
        val amount = BigDecimal.valueOf(pm.total)
        when (pm.method.uppercase()) {
            "CASH" -> cashAmount += amount
            // 🔴 CREDIT_CARD y DEBIT_CARD NO se suman entre sí. El servidor ya los
            // manda separados (son dos valores distintos del enum `PaymentMethod`)
            // y era ESTA app la que los colapsaba en un solo renglón "Tarjeta".
            "CREDIT_CARD" -> creditCardAmount += amount
            "DEBIT_CARD" -> debitCardAmount += amount
            // "CARD" a secas no existe en el enum del servidor; se acepta por si
            // alguna versión vieja o algún otro origen lo manda. Cae en "tarjeta
            // sin especificar" —se imprime como "Tarjeta"— en vez de adivinar.
            "CARD" -> unspecifiedCardAmount += amount
            "VOUCHER" -> voucherAmount += amount
            // DIGITAL_WALLET, BANK_TRANSFER, CRYPTOCURRENCY y OTHER caen aquí.
            // 🔴 Hasta hoy el ticket NO imprimía este renglón pero SÍ lo sumaba en
            // el total: el desglose no cuadraba con su propia suma y nadie podía
            // explicar el hueco. Ahora se imprime como "Otros".
            else -> otherAmount += amount
        }
    }

    val totalAmount = cashAmount + creditCardAmount + debitCardAmount +
        unspecifiedCardAmount + voucherAmount + otherAmount

    fun pct(part: BigDecimal): BigDecimal {
        return if (totalAmount > BigDecimal.ZERO) {
            part.divide(totalAmount, 4, RoundingMode.HALF_UP) * BigDecimal(100)
        } else BigDecimal.ZERO
    }

    return PaymentMethodBreakdown(
        cashAmount = cashAmount,
        creditCardAmount = creditCardAmount,
        debitCardAmount = debitCardAmount,
        unspecifiedCardAmount = unspecifiedCardAmount,
        voucherAmount = voucherAmount,
        otherAmount = otherAmount,
        totalAmount = totalAmount,
        cashPercentage = pct(cashAmount),
        creditCardPercentage = pct(creditCardAmount),
        debitCardPercentage = pct(debitCardAmount),
        unspecifiedCardPercentage = pct(unspecifiedCardAmount),
        voucherPercentage = pct(voucherAmount),
        otherPercentage = pct(otherAmount)
    )
}

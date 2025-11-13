package com.jaac.avoqado_tpv.features.shift.domain

import java.math.BigDecimal

/**
 * Shift Domain Model
 *
 * Represents a work shift (turno) in the TPV system.
 * Automatic calculation of sales, products, inventory, and payments.
 *
 * Similar to Toast/Square shift management with automatic metrics.
 *
 * @property id Shift unique identifier (CUID)
 * @property venueId Venue identifier for tenant isolation
 * @property staffId Staff member who opened the shift
 * @property staffName Staff member's full name (for UI display)
 * @property startTime Shift start timestamp (ISO 8601)
 * @property endTime Shift end timestamp (null if currently open)
 * @property status Shift status (OPEN, CLOSED)
 * @property startingCash Initial cash amount in drawer
 * @property endingCash Final cash amount after close (null if open)
 * @property totalSales Total sales amount (automatic calculation)
 * @property totalTips Total tips collected (automatic calculation)
 * @property totalOrders Total number of orders (automatic calculation)
 * @property totalCashPayments Cash payments total (automatic)
 * @property totalCardPayments Card payments total (automatic)
 * @property totalVoucherPayments Voucher/wallet payments total (automatic)
 * @property totalOtherPayments Other payment methods total (automatic)
 * @property totalProductsSold Total quantity of products sold
 * @property durationMinutes Shift duration in minutes (null if open)
 */
data class Shift(
    val id: String,
    val venueId: String,
    val staffId: String,
    val staffName: String,
    val startTime: String,
    val endTime: String?,
    val status: ShiftStatus,
    val startingCash: BigDecimal,
    val endingCash: BigDecimal?,
    val totalSales: BigDecimal,
    val totalTips: BigDecimal,
    val totalOrders: Int,
    val totalCashPayments: BigDecimal,
    val totalCardPayments: BigDecimal,
    val totalVoucherPayments: BigDecimal,
    val totalOtherPayments: BigDecimal,
    val totalProductsSold: Int,
    val durationMinutes: Int?
)

/**
 * Shift Status
 *
 * Represents the current state of a shift.
 */
enum class ShiftStatus {
    /**
     * Shift is currently active
     */
    OPEN,

    /**
     * Shift has been closed
     */
    CLOSED
}

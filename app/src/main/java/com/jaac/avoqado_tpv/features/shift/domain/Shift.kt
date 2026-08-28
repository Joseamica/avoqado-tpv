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
 * @property status Shift status (OPEN, CLOSING, CLOSED)
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
    val durationMinutes: Int?,
    /** Physical cash declared at close. Absent on old/open shift payloads. */
    val cashDeclared: BigDecimal? = null,
    /** Physical count minus expected cash. Exact zero is a valid balanced result. */
    val cashDifference: BigDecimal? = null,
    /**
     * Outcome returned by the close request that produced this instance.
     *
     * This is deliberately not inferred from persisted nullable shift columns: history and old
     * server responses leave it null because skip/ignore intent is request-scoped.
     */
    val reconciliation: CashReconciliationResult? = null
)

/** Explicit additive close action. Null means the unchanged legacy close contract. */
enum class CashReconciliationAction {
    COUNTED,
    SKIPPED
}

/** Every outcome documented by the additive H0.6 backend close contract. */
enum class CashReconciliationOutcome {
    APPLIED,
    SKIPPED,
    LEGACY_APPLIED,
    IGNORED_DISABLED,
    IGNORED_INVALID,
    IGNORED_OVERFLOW,
    NOT_REQUESTED
}

/** Request-scoped result returned at the root of a successful close response. */
data class CashReconciliationResult(
    val outcome: CashReconciliationOutcome,
    val cashDeclared: BigDecimal? = null,
    val cashDifference: BigDecimal? = null,
    /** Fase 5 de la unificación de caja: el arqueo del cajón físico (Android + esta TPV al cobrar). */
    val cashDrawer: CashDrawerSummary? = null
)

/** Lo que el cajón físico registró para este turno. `counted=false` ⇒ se cerró sin contar. */
data class CashDrawerSummary(
    /** P1 Codex: la PAX debe poder decir DE QUÉ cajón es el número — id, aparato y horario. */
    val sessionId: String? = null,
    val status: String? = null,
    val deviceName: String? = null,
    val openedAt: String? = null,
    val closedAt: String? = null,
    val expectedAmount: BigDecimal,
    val counted: Boolean,
    val actualAmount: BigDecimal? = null,
    val overShort: BigDecimal? = null
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
     * Server owns an in-flight close claim; sale and close actions stay disabled.
     */
    CLOSING,

    /**
     * Shift has been closed
     */
    CLOSED
}

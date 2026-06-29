package com.jaac.avoqado_tpv.features.cash_out.data

/**
 * DTOs for the promoter cash-out self-service ("Mis Comisiones").
 * Mirror the backend responses exactly (avoqado-server tpv.routes.ts):
 *   GET  tpv/cash-out/my-saldo → { data: PromoterCashOutDto }
 *   POST tpv/cash-out/withdraw → { data: WithdrawResultDto }
 * Money is PESOS, 1:1 — amounts arrive as decimal strings ("30"), parsed to BigDecimal for display.
 */

/** GET tpv/cash-out/my-saldo response wrapper. */
data class PromoterCashOutResponse(
    val data: PromoterCashOutDto?,
)

data class PromoterCashOutDto(
    /** Available balance, pesos 1:1, as a decimal string (e.g. "30"). */
    val saldo: String,
    /** Whether TODAY (venue-local) is a configured active withdrawal day. */
    val activeToday: Boolean,
    /** The venue-local calendar day used for activeToday (yyyy-MM-dd). */
    val businessDate: String,
)

/** POST tpv/cash-out/withdraw response wrapper. */
data class WithdrawResponse(
    val data: WithdrawResultDto?,
)

data class WithdrawResultDto(
    val folio: String,
    val netAmount: String,
    val grossAmount: String,
    val entries: Int,
)

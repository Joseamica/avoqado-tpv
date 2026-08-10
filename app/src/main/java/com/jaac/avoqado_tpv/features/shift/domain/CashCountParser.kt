package com.jaac.avoqado_tpv.features.shift.domain

import java.math.BigDecimal
import java.math.RoundingMode

private val MAX_CASH_COUNT = BigDecimal("99999999.99")
private val CASH_COUNT_FORMAT = Regex("^\\d+(?:[.,]\\d+)?$")

enum class CashCountParseError {
    BLANK,
    INVALID_FORMAT,
    NEGATIVE,
    TOO_MANY_DECIMALS,
    OVERFLOW
}

sealed interface CashCountParseResult {
    data class Success(val value: BigDecimal) : CashCountParseResult
    data class Error(val reason: CashCountParseError) : CashCountParseResult
}

/**
 * Parses cashier input without floating-point conversion.
 *
 * Both local decimal separators are accepted, while signs, exponent notation and more than two
 * fractional digits are rejected. Successful values always have scale 2 so `toPlainString()` is a
 * backend-ready canonical cash count (including `0.00`).
 */
fun parseCashCount(raw: String): CashCountParseResult {
    val input = raw.trim()
    if (input.isEmpty()) return CashCountParseResult.Error(CashCountParseError.BLANK)
    if (input.startsWith('-')) return CashCountParseResult.Error(CashCountParseError.NEGATIVE)
    if (!CASH_COUNT_FORMAT.matches(input)) {
        return CashCountParseResult.Error(CashCountParseError.INVALID_FORMAT)
    }

    val separatorIndex = maxOf(input.lastIndexOf('.'), input.lastIndexOf(','))
    if (separatorIndex >= 0 && input.length - separatorIndex - 1 > 2) {
        return CashCountParseResult.Error(CashCountParseError.TOO_MANY_DECIMALS)
    }

    val value = try {
        BigDecimal(input.replace(',', '.')).setScale(2, RoundingMode.UNNECESSARY)
    } catch (_: ArithmeticException) {
        return CashCountParseResult.Error(CashCountParseError.TOO_MANY_DECIMALS)
    } catch (_: NumberFormatException) {
        return CashCountParseResult.Error(CashCountParseError.INVALID_FORMAT)
    }

    if (value > MAX_CASH_COUNT) {
        return CashCountParseResult.Error(CashCountParseError.OVERFLOW)
    }

    return CashCountParseResult.Success(value)
}

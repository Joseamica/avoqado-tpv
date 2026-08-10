package com.jaac.avoqado_tpv.features.shift.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.math.BigDecimal

class CashCountParserTest {

    @Test
    fun `blank is a typed error`() {
        assertError("", CashCountParseError.BLANK)
        assertError("   ", CashCountParseError.BLANK)
    }

    @Test
    fun `zero is valid and canonicalized to two decimal places`() {
        assertSuccess("0", "0.00")
    }

    @Test
    fun `integer is valid`() {
        assertSuccess("6000", "6000.00")
    }

    @Test
    fun `comma input is accepted and normalized`() {
        assertSuccess("6000,5", "6000.50")
    }

    @Test
    fun `two decimal places are preserved`() {
        assertSuccess("6000.25", "6000.25")
    }

    @Test
    fun `negative values are rejected explicitly`() {
        assertError("-0.01", CashCountParseError.NEGATIVE)
    }

    @Test
    fun `exponent notation is rejected`() {
        assertError("6e3", CashCountParseError.INVALID_FORMAT)
    }

    @Test
    fun `three decimal places are rejected explicitly`() {
        assertError("1.001", CashCountParseError.TOO_MANY_DECIMALS)
    }

    @Test
    fun `non numeric input is rejected`() {
        assertError("seis mil", CashCountParseError.INVALID_FORMAT)
    }

    @Test
    fun `Decimal 10 2 maximum is accepted`() {
        assertSuccess("99999999.99", "99999999.99")
    }

    @Test
    fun `values beyond Decimal 10 2 are rejected as overflow`() {
        assertError("100000000", CashCountParseError.OVERFLOW)
    }

    private fun assertSuccess(raw: String, expected: String) {
        val result = parseCashCount(raw)
        assertThat(result).isInstanceOf(CashCountParseResult.Success::class.java)
        assertThat((result as CashCountParseResult.Success).value).isEqualTo(BigDecimal(expected))
        assertThat(result.value.toPlainString()).isEqualTo(expected)
    }

    private fun assertError(raw: String, expected: CashCountParseError) {
        val result = parseCashCount(raw)
        assertThat(result).isEqualTo(CashCountParseResult.Error(expected))
    }
}

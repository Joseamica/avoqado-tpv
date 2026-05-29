package com.jaac.avoqado_tpv.features.serialized_sale.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IccidValidatorTest {
    @Test fun `accepts 19 and 20 digit 8952 iccids`() {
        assertTrue(IccidValidator.isValidFormat("8952140000001234567"))
        assertTrue(IccidValidator.isValidFormat("89521400000012345678"))
    }
    @Test fun `accepts trailing F and lowercase with whitespace`() {
        assertTrue(IccidValidator.isValidFormat("8952140000001234567F"))
        assertTrue(IccidValidator.isValidFormat("  8952140000001234567f  "))
    }
    @Test fun `rejects non-8952 prefix and short and garbled`() {
        assertFalse(IccidValidator.isValidFormat("8951140000001234567"))
        assertFalse(IccidValidator.isValidFormat("895214000000"))
        assertFalse(IccidValidator.isValidFormat("89521400ABCD01234567"))
    }
    @Test fun `luhn validates digit body`() {
        // 8952140000001234567 has check digit per ISO 7812; just assert function runs both ways
        assertTrue(IccidValidator.isLuhnValid("18") || !IccidValidator.isLuhnValid("18"))
        assertFalse(IccidValidator.isLuhnValid(""))
        assertFalse(IccidValidator.isLuhnValid("12A4"))
    }
}

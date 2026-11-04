package com.jaac.avoqado_tpv.features.payment.domain.model

import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantAccountTest {
    private fun cuenta(id: String?, provider: ProcessorType = ProcessorType.ANGELPAY) = MerchantAccount(
        id = "m1", serialNumber = "N86", displayName = "Tienda", processorType = provider, externalMerchantId = id,
    )

    @Test
    fun `D4 solo ids AngelPay de digitos positivos dentro del rango SDK son cobrables`() {
        for (id in listOf("1", "2147483647", "00011")) {
            assertEquals(id.toInt(), cuenta(id).requireAngelpayMerchantId())
        }
        for (id in listOf(null, "", "0", "000", "-1", "+1", " 1", "1 ", "1.0", "1\n", "AWAITING_11", "2147483648", "999999999999999999999", "١")) {
            assertTrue("Debe rechazar '$id'", runCatching { cuenta(id).requireAngelpayMerchantId() }.isFailure)
        }
        assertTrue(runCatching { cuenta("11", ProcessorType.BLUMON).requireAngelpayMerchantId() }.isFailure)
    }
}

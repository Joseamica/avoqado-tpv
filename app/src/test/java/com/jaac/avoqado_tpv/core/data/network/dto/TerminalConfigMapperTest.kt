package com.jaac.avoqado_tpv.core.data.network.dto

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalConfigMapperTest {

    @Test
    fun `toDomain maps MSI promotions from provider config`() {
        val dto = merchantAccountDto(
            providerConfig = mapOf(
                "promotions" to mapOf(
                    "msi" to listOf(6, "12", 9.0, -3, "bad", 6L)
                )
            )
        )

        val account = dto.toDomain()

        assertEquals(listOf(6, 9, 12), account.availableMsiMonths)
    }

    @Test
    fun `toDomain maps MSI promotions from nested dataResponse`() {
        val dto = merchantAccountDto(
            providerConfig = mapOf(
                "dataResponse" to mapOf(
                    "promotions" to mapOf(
                        "msi" to listOf(3, 6)
                    )
                )
            )
        )

        val account = dto.toDomain()

        assertEquals(listOf(3, 6), account.availableMsiMonths)
    }

    @Test
    fun `toDomain ignores non MSI promotions`() {
        val dto = merchantAccountDto(
            providerConfig = mapOf(
                "promotions" to mapOf(
                    "cashback" to listOf(5),
                    "points" to listOf(2)
                )
            )
        )

        val account = dto.toDomain()

        assertEquals(emptyList<Int>(), account.availableMsiMonths)
    }

    private fun merchantAccountDto(
        providerConfig: Map<String, Any>?
    ): MerchantAccountDto {
        return MerchantAccountDto(
            id = "merchant-account-id",
            displayName = "Merchant",
            providerCode = "BLUMON",
            serialNumber = "1234567890",
            posId = "1223",
            environment = "SANDBOX",
            merchantId = "merchant-id",
            credentials = null,
            providerConfig = providerConfig
        )
    }
}

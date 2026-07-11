package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.angelpay.angelpaysdk.models.MerchantSummary
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.jaac.avoqado_tpv.core.data.network.dto.MerchantAccountDto
import com.jaac.avoqado_tpv.core.data.network.dto.TerminalConfigData
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for AngelPayConfigValidator (Task 29 — spec §6.9, §18.4).
 *
 * D5 intersection policy: compares SDK user-merchant list against the AngelPay
 * subset of TerminalConfigData.merchantAccounts. Outcomes:
 *   - AllClear → no drift, no Crashlytics noise
 *   - PartialOperable → non-empty intersection with drift; non-fatal recorded
 *   - HardBlock → empty intersection; non-fatal recorded
 *   - Unavailable → the SDK merchant QUERY failed (transport) — drift unknown,
 *     caller keeps previous state, never blocks (P1 fix 2026-07-09)
 */
class AngelPayConfigValidationTest {

    private lateinit var sdkGateway: AngelPaySdkGateway
    private lateinit var crashlytics: FirebaseCrashlytics
    private lateinit var validator: AngelPayConfigValidator

    @Before
    fun setup() {
        sdkGateway = mockk()
        crashlytics = mockk(relaxed = true)
        validator = AngelPayConfigValidator(sdkGateway, crashlytics)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun sdkMerchant(id: Int) = MerchantSummary(
        id = id,
        name = "M$id",
        affiliationNumber = "100$id",
        isActive = true,
    )

    private fun avoqadoMerchant(
        externalId: Int,
        providerCode: String = "ANGELPAY",
        isActive: Boolean = true,
    ): MerchantAccountDto = mockk {
        every { this@mockk.providerCode } returns providerCode
        every { this@mockk.isActive } returns isActive
        every { this@mockk.externalMerchantId } returns externalId.toString()
    }

    private fun configWith(merchants: List<MerchantAccountDto>): TerminalConfigData = mockk {
        every { merchantAccounts } returns merchants
    }

    @Test
    fun `all merchants match returns AllClear`() = runTest {
        coEvery { sdkGateway.getUserMerchants() } returns Result.success(
            listOf(sdkMerchant(1), sdkMerchant(2)),
        )
        val config = configWith(listOf(avoqadoMerchant(1), avoqadoMerchant(2)))

        val result = validator.validate(config)

        assertTrue("expected AllClear, got $result", result is ValidationResult.AllClear)
        verify(exactly = 0) { crashlytics.recordException(any()) }
    }

    @Test
    fun `SDK has extra merchant returns PartialOperable with warning`() = runTest {
        coEvery { sdkGateway.getUserMerchants() } returns Result.success(
            listOf(sdkMerchant(1), sdkMerchant(2), sdkMerchant(99)),
        )
        val config = configWith(listOf(avoqadoMerchant(1), avoqadoMerchant(2)))

        val result = validator.validate(config)

        assertTrue("expected PartialOperable, got $result", result is ValidationResult.PartialOperable)
        val partial = result as ValidationResult.PartialOperable
        assertEquals(setOf(1, 2), partial.operableIds)
        assertEquals(setOf(99), partial.onlyInSdk)
        assertEquals(emptySet<Int>(), partial.onlyInAvoqado)
        verify(atLeast = 1) { crashlytics.recordException(any<AngelPayConfigMismatchInfo>()) }
    }

    @Test
    fun `Avoqado has extra merchant returns PartialOperable with warning`() = runTest {
        coEvery { sdkGateway.getUserMerchants() } returns Result.success(
            listOf(sdkMerchant(1)),
        )
        val config = configWith(listOf(avoqadoMerchant(1), avoqadoMerchant(42)))

        val result = validator.validate(config)

        assertTrue("expected PartialOperable, got $result", result is ValidationResult.PartialOperable)
        val partial = result as ValidationResult.PartialOperable
        assertEquals(setOf(1), partial.operableIds)
        assertEquals(emptySet<Int>(), partial.onlyInSdk)
        assertEquals(setOf(42), partial.onlyInAvoqado)
        verify(atLeast = 1) { crashlytics.recordException(any<AngelPayConfigMismatchInfo>()) }
    }

    @Test
    fun `empty intersection returns HardBlock`() = runTest {
        coEvery { sdkGateway.getUserMerchants() } returns Result.success(
            listOf(sdkMerchant(1), sdkMerchant(2)),
        )
        val config = configWith(listOf(avoqadoMerchant(99), avoqadoMerchant(100)))

        val result = validator.validate(config)

        assertTrue("expected HardBlock, got $result", result is ValidationResult.HardBlock)
        verify(atLeast = 1) { crashlytics.recordException(any<AngelPayConfigMismatchInfo>()) }
    }

    @Test
    fun `empty SDK list returns HardBlock`() = runTest {
        coEvery { sdkGateway.getUserMerchants() } returns Result.success(emptyList())
        val config = configWith(listOf(avoqadoMerchant(1)))

        val result = validator.validate(config)

        assertTrue("expected HardBlock, got $result", result is ValidationResult.HardBlock)
    }

    @Test
    fun `transport failure returns Unavailable - never a HardBlock`() = runTest {
        // A network/SDK error is NOT "the session has zero merchants". Before the
        // 2026-07-09 fix this collapsed into an empty set → false HardBlock that
        // stopped payments with a misleading "config mismatch" banner.
        coEvery { sdkGateway.getUserMerchants() } returns Result.failure(RuntimeException("timeout"))
        val config = configWith(listOf(avoqadoMerchant(1)))

        val result = validator.validate(config)

        assertTrue("expected Unavailable, got $result", result is ValidationResult.Unavailable)
        assertEquals("timeout", (result as ValidationResult.Unavailable).reason)
        // Transient transport noise must not spam Crashlytics as config mismatch.
        verify(exactly = 0) { crashlytics.recordException(any()) }
    }
}

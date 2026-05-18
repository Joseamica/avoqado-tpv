package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.angelpay.angelpaysdk.AngelPaySDK
import com.angelpay.angelpaysdk.models.MerchantSummary
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for AngelPaySdkGateway multi-merchant primitives (Task 26 — spec §6.5, §18.1).
 *
 * Covers the two new methods that wrap AngelPay SDK 1.0.5:
 *   - getUserMerchants(): returns the merchant list the authenticated user can switch between
 *   - switchMerchant(): swaps active merchant without re-authentication
 *
 * Both surfaces categorize SDK errors as AngelPayAuthExpiredError / AngelPayNetworkError /
 * pass-through, so Task 30's AuthRepository can drive the retry / re-auth state machine.
 *
 * AngelPaySDK is a Kotlin object — mock via `mockkObject`.
 */
class AngelPaySdkGatewayTest {

    private lateinit var gateway: AngelPaySdkGateway

    @Before
    fun setup() {
        mockkObject(AngelPaySDK)
        gateway = AngelPaySdkGateway()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `getUserMerchants returns SDK list on success`() = runTest {
        val sdkList = listOf(
            MerchantSummary(id = 42, name = "Acme Café", affiliationNumber = "1234567", isActive = true),
            MerchantSummary(id = 99, name = "Acme Bar",  affiliationNumber = "7654321", isActive = false),
        )
        coEvery { AngelPaySDK.getUserMerchants() } returns Result.success(sdkList)

        val result = gateway.getUserMerchants()

        assertTrue("expected success, got ${result.exceptionOrNull()}", result.isSuccess)
        val merchants = result.getOrThrow()
        assertEquals(2, merchants.size)
        assertEquals(42, merchants[0].id)
        assertEquals("Acme Café", merchants[0].name)
        assertEquals("1234567", merchants[0].affiliationNumber)
        assertEquals(true, merchants[0].isActive)
        assertEquals(false, merchants[1].isActive)
    }

    @Test
    fun `getUserMerchants returns failure when SDK fails`() = runTest {
        val sdkError = IllegalStateException("something unexpected")
        coEvery { AngelPaySDK.getUserMerchants() } returns Result.failure<List<MerchantSummary>>(sdkError)

        val result = gateway.getUserMerchants()

        assertTrue("expected failure", result.isFailure)
        // Generic exception should be passed through (not auth or network).
        val err = result.exceptionOrNull()
        assertEquals(sdkError, err)
    }

    @Test
    fun `switchMerchant SDK success returns Result Unit`() = runTest {
        coEvery { AngelPaySDK.switchMerchant(merchantId = 42) } returns Result.success(Unit)

        val result = gateway.switchMerchant(merchantId = 42)

        assertTrue("expected success, got ${result.exceptionOrNull()}", result.isSuccess)
    }

    @Test
    fun `switchMerchant SDK auth error returns categorized AuthExpired failure`() = runTest {
        val sdkError = RuntimeException("401 Unauthorized — JWT expired")
        coEvery { AngelPaySDK.switchMerchant(merchantId = 42) } returns Result.failure<Unit>(sdkError)

        val result = gateway.switchMerchant(merchantId = 42)

        assertTrue("expected failure", result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(
            "expected AngelPayAuthExpiredError, got ${err?.javaClass?.simpleName}: ${err?.message}",
            err is AngelPayAuthExpiredError,
        )
        assertEquals(sdkError, err?.cause)
    }

    @Test
    fun `switchMerchant SDK network error returns categorized Network failure`() = runTest {
        val sdkError = IOException("connection timeout while reaching gateway")
        coEvery { AngelPaySDK.switchMerchant(merchantId = 7) } returns Result.failure<Unit>(sdkError)

        val result = gateway.switchMerchant(merchantId = 7)

        assertTrue("expected failure", result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(
            "expected AngelPayNetworkError, got ${err?.javaClass?.simpleName}: ${err?.message}",
            err is AngelPayNetworkError,
        )
        assertEquals(sdkError, err?.cause)
    }

    @Test
    fun `switchMerchant SDK generic error returns generic failure`() = runTest {
        val sdkError = IllegalStateException("merchant not found in account")
        coEvery { AngelPaySDK.switchMerchant(merchantId = 999) } returns Result.failure<Unit>(sdkError)

        val result = gateway.switchMerchant(merchantId = 999)

        assertTrue("expected failure", result.isFailure)
        val err = result.exceptionOrNull()
        // Generic error should be passed through unchanged — Task 30 falls back to surfacing
        // it to the user without any retry logic.
        assertEquals(sdkError, err)
        assertTrue("should not be categorized", err !is AngelPayAuthExpiredError)
        assertTrue("should not be categorized", err !is AngelPayNetworkError)
    }
}

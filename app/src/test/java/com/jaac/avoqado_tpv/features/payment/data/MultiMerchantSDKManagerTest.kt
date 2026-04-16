package com.jaac.avoqado_tpv.features.payment.data

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.domain.TerminalConfig
import com.jaac.avoqado_tpv.core.util.CriticalNetworkOperationManager
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantEnvironment
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * MultiMerchantSDKManagerTest
 *
 * Tests merchant switching logic: same merchant no-op, serial update,
 * rollback on failure, inactive account rejection, and error translation.
 *
 * MultiMerchantSDKManager orchestrates:
 * 1. TerminalConfig.serialNumber update
 * 2. InitializationManager.forceReinitialize() (OAuth + DUKPT keys)
 * 3. Rollback on failure
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiMerchantSDKManagerTest {

    // Mocks
    private lateinit var mockInitializationManager: InitializationManager
    private lateinit var mockCriticalNetworkOperationManager: CriticalNetworkOperationManager

    // System under test
    private lateinit var manager: MultiMerchantSDKManager

    // Test data
    private val defaultSerial = "2841548417"
    private val merchantA = MerchantAccount(
        id = "merchant_a",
        serialNumber = "2841548417",
        posId = "376",
        displayName = "Account A",
        environment = MerchantEnvironment.SANDBOX,
        isActive = true
    )
    private val merchantB = MerchantAccount(
        id = "merchant_b",
        serialNumber = "2841548418",
        posId = "378",
        displayName = "Account B",
        environment = MerchantEnvironment.SANDBOX,
        isActive = true
    )
    private val inactiveMerchant = MerchantAccount(
        id = "merchant_inactive",
        serialNumber = "9999999999",
        posId = "999",
        displayName = "Inactive Account",
        environment = MerchantEnvironment.SANDBOX,
        isActive = false
    )

    @Before
    fun setup() {
        mockkObject(TerminalConfig)
        every { TerminalConfig.serialNumber } returns defaultSerial
        every { TerminalConfig.updateSerial(any()) } answers {
            every { TerminalConfig.serialNumber } returns firstArg()
        }
        every { TerminalConfig.reset() } answers {
            every { TerminalConfig.serialNumber } returns defaultSerial
        }

        mockInitializationManager = mockk(relaxed = true)
        mockCriticalNetworkOperationManager = mockk(relaxed = true)
        coEvery { mockInitializationManager.forceReinitialize(any()) } returns Result.success(Unit)

        manager = MultiMerchantSDKManager(
            initializationManager = mockInitializationManager,
            criticalNetworkOperationManager = mockCriticalNetworkOperationManager
        )
    }

    @After
    fun tearDown() {
        unmockkObject(TerminalConfig)
        unmockkAll()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 1: Same merchant is no-op
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `switchMerchant to same merchant is no-op`() = runTest {
        // merchantA.serialNumber == TerminalConfig.serialNumber
        val result = manager.switchMerchant(merchantA)

        assertThat(result.isSuccess).isTrue()
        // Should NOT call forceReinitialize (already on same merchant)
        coVerify(exactly = 0) { mockInitializationManager.forceReinitialize(any()) }
        assertThat(manager.getCurrentMerchant()).isEqualTo(merchantA)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 2: Different merchant updates TerminalConfig
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `switchMerchant to different merchant updates TerminalConfig`() = runTest {
        val result = manager.switchMerchant(merchantB)

        assertThat(result.isSuccess).isTrue()
        verify { TerminalConfig.updateSerial("2841548418") }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 3: Calls forceReinitialize with posId
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `switchMerchant calls forceReinitialize with posId`() = runTest {
        manager.switchMerchant(merchantB)

        coVerify(exactly = 1) { mockInitializationManager.forceReinitialize(merchantPosId = "378") }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 4: Success updates currentMerchant
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `switchMerchant success updates currentMerchant`() = runTest {
        assertThat(manager.getCurrentMerchant()).isNull()

        manager.switchMerchant(merchantB)

        assertThat(manager.getCurrentMerchant()).isEqualTo(merchantB)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 5: Rejects inactive account
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `switchMerchant rejects inactive account`() = runTest {
        val result = manager.switchMerchant(inactiveMerchant)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("inactiva")
        // Should NOT call forceReinitialize
        coVerify(exactly = 0) { mockInitializationManager.forceReinitialize(any()) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 6: Rolls back TerminalConfig on init failure
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `switchMerchant rolls back TerminalConfig on init failure`() = runTest {
        coEvery { mockInitializationManager.forceReinitialize(any()) } returns
            Result.failure(Exception("Init failed"))

        val result = manager.switchMerchant(merchantB)

        assertThat(result.isFailure).isTrue()
        // TerminalConfig should be rolled back to original serial
        verify { TerminalConfig.updateSerial(defaultSerial) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 7: Translates timeout error
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `switchMerchant translates timeout error`() = runTest {
        coEvery { mockInitializationManager.forceReinitialize(any()) } returns
            Result.failure(Exception("Connection timed out"))

        val result = manager.switchMerchant(merchantB)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("tardó demasiado")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 8: Translates auth error
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `switchMerchant translates auth error`() = runTest {
        coEvery { mockInitializationManager.forceReinitialize(any()) } returns
            Result.failure(Exception("OAuth authentication failed"))

        val result = manager.switchMerchant(merchantB)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("autenticar")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 9: Translates network error
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `switchMerchant translates network error`() = runTest {
        coEvery { mockInitializationManager.forceReinitialize(any()) } returns
            Result.failure(Exception("No connection available"))

        val result = manager.switchMerchant(merchantB)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("conectar")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 10: resetToDefault clears currentMerchant and re-inits
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `resetToDefault clears currentMerchant and re-inits`() = runTest {
        // First set a merchant
        manager.switchMerchant(merchantA)
        assertThat(manager.getCurrentMerchant()).isNotNull()

        // Reset
        val result = manager.resetToDefault()

        assertThat(result.isSuccess).isTrue()
        assertThat(manager.getCurrentMerchant()).isNull()
        verify { TerminalConfig.reset() }
        coVerify { mockInitializationManager.forceReinitialize(merchantPosId = null) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 11: getStatusMessage with merchant shows name
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `getStatusMessage with merchant shows name`() = runTest {
        manager.switchMerchant(merchantA)

        val message = manager.getStatusMessage()

        assertThat(message).contains("Account A")
        assertThat(message).contains("2841548417")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 12: getStatusMessage without merchant shows default
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `getStatusMessage without merchant shows default`() {
        val message = manager.getStatusMessage()

        assertThat(message).contains("Default Merchant")
        assertThat(message).contains(defaultSerial)
    }
}

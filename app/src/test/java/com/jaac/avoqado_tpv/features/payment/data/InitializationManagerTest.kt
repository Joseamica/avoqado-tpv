package com.jaac.avoqado_tpv.features.payment.data

import android.content.Context
import com.example.clean_lib_services.shared.core.domain.entity.init.InitData
import com.example.clean_lib_services.shared.initializer.domain.use_case.get_init_data.GetInitDataParams
import com.example.clean_lib_services.shared.initializer.domain.use_case.get_init_data.GetInitDataResponse
import com.example.clean_lib_services.shared.initializer.domain.use_case.get_init_data.GetInitDataUseCase
import com.example.clean_lib_services.shared.initializer.domain.use_case.initializer.InitializerFailure
import com.example.clean_lib_services.shared.initializer.domain.use_case.initializer.InitializerParams
import com.example.clean_lib_services.shared.initializer.domain.use_case.initializer.InitializerResponse
import com.example.clean_lib_services.shared.initializer.domain.use_case.initializer.InitializerUseCase
import com.example.clean_lib_services.shared.initializer.domain.use_case.insert_init.InsertInitParams
import com.example.clean_lib_services.shared.initializer.domain.use_case.insert_init.InsertInitResponse
import com.example.clean_lib_services.shared.initializer.domain.use_case.insert_init.InsertInitUseCase
import com.example.clean_lib_services.utils.clean.Either
import com.example.clean_lib_services.utils.clean.Failure
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.domain.TerminalConfig
import com.jaac.avoqado_tpv.core.util.CriticalNetworkOperationManager
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * InitializationManagerTest
 *
 * Tests Blumon SDK initialization flow: 24h caching, force re-init,
 * concurrency protection, and failure handling at each step.
 *
 * The InitializationManager orchestrates 3 SDK use cases in sequence:
 * 1. InitializerUseCase - OAuth + DUKPT key download
 * 2. InsertInitUseCase - Fix posId bug (SDK stores serial instead of server posId)
 * 3. GetInitDataUseCase - Verification (check posId is correct)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InitializationManagerTest {

    // Mocks
    private lateinit var mockContext: Context
    private lateinit var mockSecureStorage: SecureStorage
    private lateinit var mockInitializerUseCase: InitializerUseCase
    private lateinit var mockInsertInitUseCase: InsertInitUseCase
    private lateinit var mockGetInitDataUseCase: GetInitDataUseCase
    private lateinit var mockCriticalNetworkOperationManager: CriticalNetworkOperationManager

    // System under test
    private lateinit var initializationManager: InitializationManager

    // Constants
    private val TWENTY_FOUR_HOURS_MS = 86_400_000L

    @Before
    fun setup() {
        mockkObject(TerminalConfig)
        every { TerminalConfig.serialNumber } returns "TEST-SERIAL-123"
        every { TerminalConfig.brand } returns "PAX"
        every { TerminalConfig.model } returns "A80"

        mockContext = mockk(relaxed = true)
        mockSecureStorage = mockk(relaxed = true)
        mockInitializerUseCase = mockk(relaxed = true)
        mockInsertInitUseCase = mockk(relaxed = true)
        mockGetInitDataUseCase = mockk(relaxed = true)
        mockCriticalNetworkOperationManager = mockk(relaxed = true)

        // Default: no previous init timestamp
        every { mockSecureStorage.getLastBlumonInitTimestamp() } returns null
    }

    @After
    fun tearDown() {
        unmockkObject(TerminalConfig)
        unmockkAll()
    }

    /**
     * Create fresh InitializationManager for each test.
     * Must be called after mocks are configured.
     */
    private fun createManager(): InitializationManager {
        return InitializationManager(
            context = mockContext,
            secureStorage = mockSecureStorage,
            initializerUseCase = mockInitializerUseCase,
            insertInitUseCase = mockInsertInitUseCase,
            getInitDataUseCase = mockGetInitDataUseCase,
            criticalNetworkOperationManager = mockCriticalNetworkOperationManager
        )
    }

    /**
     * Configure all 3 SDK use cases to succeed.
     * Uses Blumon SDK's Either type: isLeft=failure, isRight=success.
     */
    private fun configureAllUseCasesSuccess() {
        // Step 1: InitializerUseCase succeeds
        val initEither = mockk<Either<InitializerFailure, InitializerResponse>>()
        every { initEither.isLeft } returns false
        every { initEither.isRight } returns true
        coEvery { mockInitializerUseCase.run(any<InitializerParams>()) } returns initEither

        // Step 2: InsertInitUseCase succeeds
        val insertEither = mockk<Either<Failure, InsertInitResponse>>()
        every { insertEither.isLeft } returns false
        every { insertEither.isRight } returns true
        coEvery { mockInsertInitUseCase.run(any<InsertInitParams>()) } returns insertEither

        // Step 3: GetInitDataUseCase succeeds (returns initData with posId)
        val mockInitData = mockk<InitData>(relaxed = true)
        every { mockInitData.posId } returns "376"
        every { mockInitData.commerceName } returns "Test Commerce"

        val mockResponse = mockk<GetInitDataResponse>(relaxed = true)
        every { mockResponse.initData } returns mockInitData

        val getInitEither = mockk<Either<Failure, GetInitDataResponse>>()
        every { getInitEither.isLeft } returns false
        every { getInitEither.isRight } returns true
        every { getInitEither.rightValue() } returns mockResponse
        coEvery { mockGetInitDataUseCase.run(any<GetInitDataParams>()) } returns getInitEither
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 1: First-time initialization (no timestamp)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ensureInitialized first time runs full init`() = runTest {
        every { mockSecureStorage.getLastBlumonInitTimestamp() } returns null
        configureAllUseCasesSuccess()
        initializationManager = createManager()

        val result = initializationManager.ensureInitialized()

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 1) { mockInitializerUseCase.run(any()) }
        coVerify(exactly = 1) { mockInsertInitUseCase.run(any()) }
        // GetInitDataUseCase called twice: once in step 1.6 (posId fetch) + once in step 3 (verification)
        coVerify(atLeast = 1) { mockGetInitDataUseCase.run(any()) }
        verify { mockSecureStorage.saveLastBlumonInitTimestamp(any()) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 2: Skip init within 24 hours
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ensureInitialized within 24h skips init`() = runTest {
        // Timestamp from 1 hour ago
        val oneHourAgo = System.currentTimeMillis() - (1 * 60 * 60 * 1000)
        every { mockSecureStorage.getLastBlumonInitTimestamp() } returns oneHourAgo
        initializationManager = createManager()

        val result = initializationManager.ensureInitialized()

        assertThat(result.isSuccess).isTrue()
        assertThat(initializationManager.isInitialized.value).isTrue()
        // Should NOT call any SDK use cases (skipped)
        coVerify(exactly = 0) { mockInitializerUseCase.run(any()) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 3: Re-init after 24 hours
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ensureInitialized after 24h runs full init`() = runTest {
        // Timestamp from 25 hours ago
        val twentyFiveHoursAgo = System.currentTimeMillis() - (25 * 60 * 60 * 1000)
        every { mockSecureStorage.getLastBlumonInitTimestamp() } returns twentyFiveHoursAgo
        configureAllUseCasesSuccess()
        initializationManager = createManager()

        val result = initializationManager.ensureInitialized()

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 1) { mockInitializerUseCase.run(any()) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 4: merchantPosId forces re-init even within 24h
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ensureInitialized with merchantPosId forces re-init even within 24h`() = runTest {
        // Recent timestamp (1 hour ago) - would normally skip
        val oneHourAgo = System.currentTimeMillis() - (1 * 60 * 60 * 1000)
        every { mockSecureStorage.getLastBlumonInitTimestamp() } returns oneHourAgo
        configureAllUseCasesSuccess()
        initializationManager = createManager()

        val result = initializationManager.ensureInitialized(defaultMerchantPosId = "378")

        assertThat(result.isSuccess).isTrue()
        // MUST run full init even within 24h when merchantPosId is provided
        coVerify(exactly = 1) { mockInitializerUseCase.run(any()) }
        coVerify(exactly = 1) { mockInsertInitUseCase.run(any()) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 5: Verify 3 SDK use cases called in order
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `executeInitialization calls SDK use cases in order`() = runTest {
        configureAllUseCasesSuccess()
        initializationManager = createManager()

        val result = initializationManager.ensureInitialized()

        assertThat(result.isSuccess).isTrue()
        coVerifyOrder {
            mockInitializerUseCase.run(any())
            // GetInitDataUseCase called for posId fetch (step 1.6)
            mockGetInitDataUseCase.run(any())
            mockInsertInitUseCase.run(any())
            // GetInitDataUseCase called again for verification (step 3)
            mockGetInitDataUseCase.run(any())
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 6: Timestamp saved on success
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `executeInitialization saves timestamp on success`() = runTest {
        configureAllUseCasesSuccess()
        initializationManager = createManager()

        initializationManager.ensureInitialized()

        verify(exactly = 1) { mockSecureStorage.saveLastBlumonInitTimestamp(any()) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 7: InitializerUseCase failure
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `executeInitialization returns failure when InitializerUseCase fails`() = runTest {
        // Step 1 fails
        val failureValue = mockk<InitializerFailure>(relaxed = true)
        every { failureValue.toString() } returns "OAuth failure"
        val failedEither = mockk<Either<InitializerFailure, InitializerResponse>>()
        every { failedEither.isLeft } returns true
        every { failedEither.leftValue() } returns failureValue
        coEvery { mockInitializerUseCase.run(any<InitializerParams>()) } returns failedEither
        initializationManager = createManager()

        val result = initializationManager.ensureInitialized()

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("InitializerUseCase failed")
        // Should NOT proceed to step 2
        coVerify(exactly = 0) { mockInsertInitUseCase.run(any()) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 8: InsertInitUseCase failure
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `executeInitialization returns failure when InsertInitUseCase fails`() = runTest {
        // Step 1 succeeds
        val step1Either = mockk<Either<InitializerFailure, InitializerResponse>>()
        every { step1Either.isLeft } returns false
        every { step1Either.isRight } returns true
        coEvery { mockInitializerUseCase.run(any<InitializerParams>()) } returns step1Either

        // GetInitDataUseCase succeeds (step 1.6 posId fetch)
        val mockInitData = mockk<InitData>(relaxed = true)
        every { mockInitData.posId } returns "376"
        val mockResponse = mockk<GetInitDataResponse>(relaxed = true)
        every { mockResponse.initData } returns mockInitData
        val getInitEither = mockk<Either<Failure, GetInitDataResponse>>()
        every { getInitEither.isLeft } returns false
        every { getInitEither.isRight } returns true
        every { getInitEither.rightValue() } returns mockResponse
        coEvery { mockGetInitDataUseCase.run(any<GetInitDataParams>()) } returns getInitEither

        // Step 2 fails
        val step2Either = mockk<Either<Failure, InsertInitResponse>>()
        every { step2Either.isLeft } returns true
        coEvery { mockInsertInitUseCase.run(any<InsertInitParams>()) } returns step2Either
        initializationManager = createManager()

        val result = initializationManager.ensureInitialized()

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("InsertInitUseCase failed")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 9: GetInitDataUseCase failure (verification step)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `executeInitialization returns failure when GetInitDataUseCase fails at verification`() = runTest {
        // Step 1 succeeds
        val step1Either = mockk<Either<InitializerFailure, InitializerResponse>>()
        every { step1Either.isLeft } returns false
        every { step1Either.isRight } returns true
        coEvery { mockInitializerUseCase.run(any<InitializerParams>()) } returns step1Either

        // Step 2 succeeds
        val step2Either = mockk<Either<Failure, InsertInitResponse>>()
        every { step2Either.isLeft } returns false
        every { step2Either.isRight } returns true
        coEvery { mockInsertInitUseCase.run(any<InsertInitParams>()) } returns step2Either

        // GetInitDataUseCase: first call (step 1.6) succeeds, second call (step 3 verification) fails
        val mockInitData = mockk<InitData>(relaxed = true)
        every { mockInitData.posId } returns "376"
        val successResponse = mockk<GetInitDataResponse>(relaxed = true)
        every { successResponse.initData } returns mockInitData

        val firstCallResult = mockk<Either<Failure, GetInitDataResponse>>()
        every { firstCallResult.isLeft } returns false
        every { firstCallResult.isRight } returns true
        every { firstCallResult.rightValue() } returns successResponse

        val failureValue = mockk<Failure>(relaxed = true)
        every { failureValue.toString() } returns "Verification failed"

        val secondCallResult = mockk<Either<Failure, GetInitDataResponse>>()
        every { secondCallResult.isLeft } returns true
        every { secondCallResult.leftValue() } returns failureValue

        coEvery { mockGetInitDataUseCase.run(any<GetInitDataParams>()) } returnsMany listOf(
            firstCallResult,
            secondCallResult
        )
        initializationManager = createManager()

        val result = initializationManager.ensureInitialized()

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("GetInitDataUseCase failed")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 10: isInitialized StateFlow updates on success
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `isInitialized StateFlow updates on success`() = runTest {
        configureAllUseCasesSuccess()
        initializationManager = createManager()

        assertThat(initializationManager.isInitialized.value).isFalse()

        initializationManager.ensureInitialized()

        assertThat(initializationManager.isInitialized.value).isTrue()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 11: isInitialized stays false on failure
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `isInitialized stays false on failure`() = runTest {
        // All use cases fail
        coEvery { mockInitializerUseCase.run(any<InitializerParams>()) } returns mockk {
            every { isLeft } returns true
            every { leftValue() } returns mockk(relaxed = true)
        }
        initializationManager = createManager()

        initializationManager.ensureInitialized()

        assertThat(initializationManager.isInitialized.value).isFalse()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 12: forceReinitialize resets isInitialized then re-inits
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `forceReinitialize resets isInitialized then re-inits`() = runTest {
        configureAllUseCasesSuccess()
        initializationManager = createManager()

        // First init
        initializationManager.ensureInitialized()
        assertThat(initializationManager.isInitialized.value).isTrue()

        // Force re-init
        val result = initializationManager.forceReinitialize()

        assertThat(result.isSuccess).isTrue()
        assertThat(initializationManager.isInitialized.value).isTrue()
        // InitializerUseCase called twice (first init + force re-init)
        coVerify(exactly = 2) { mockInitializerUseCase.run(any()) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 13: forceReinitialize passes merchantPosId
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `forceReinitialize passes merchantPosId to bypass GetInitDataUseCase for posId`() = runTest {
        // Step 1 succeeds
        coEvery { mockInitializerUseCase.run(any<InitializerParams>()) } returns mockk {
            every { isLeft } returns false
            every { isRight } returns true
        }
        // Step 2 succeeds
        coEvery { mockInsertInitUseCase.run(any<InsertInitParams>()) } returns mockk {
            every { isLeft } returns false
            every { isRight } returns true
        }
        // Step 3 verification succeeds
        val mockInitData = mockk<com.example.clean_lib_services.shared.core.domain.entity.init.InitData>(relaxed = true) {
            every { posId } returns "378"
            every { commerceName } returns "Test"
        }
        coEvery { mockGetInitDataUseCase.run(any<GetInitDataParams>()) } returns mockk {
            every { isLeft } returns false
            every { isRight } returns true
            every { rightValue() } returns mockk(relaxed = true) {
                every { initData } returns mockInitData
            }
        }

        initializationManager = createManager()

        val result = initializationManager.forceReinitialize(merchantPosId = "378")

        assertThat(result.isSuccess).isTrue()
        // When merchantPosId is provided, GetInitDataUseCase is only called once (step 3 verification)
        // NOT called for step 1.6 (posId fetch) — it's bypassed by the provided merchantPosId
        coVerify(exactly = 1) { mockGetInitDataUseCase.run(any()) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 14: Concurrent calls don't duplicate init
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `concurrent ensureInitialized calls dont duplicate init`() = runTest {
        // Make init take some time
        coEvery { mockInitializerUseCase.run(any<InitializerParams>()) } coAnswers {
            delay(100)
            mockk {
                every { isLeft } returns false
                every { isRight } returns true
            }
        }
        coEvery { mockInsertInitUseCase.run(any<InsertInitParams>()) } returns mockk {
            every { isLeft } returns false
            every { isRight } returns true
        }
        val mockInitData = mockk<com.example.clean_lib_services.shared.core.domain.entity.init.InitData>(relaxed = true) {
            every { posId } returns "376"
            every { commerceName } returns "Test"
        }
        coEvery { mockGetInitDataUseCase.run(any<GetInitDataParams>()) } returns mockk {
            every { isLeft } returns false
            every { isRight } returns true
            every { rightValue() } returns mockk(relaxed = true) {
                every { initData } returns mockInitData
            }
        }
        initializationManager = createManager()

        // Launch two concurrent calls
        val result1 = async { initializationManager.ensureInitialized() }
        val result2 = async { initializationManager.ensureInitialized() }

        assertThat(result1.await().isSuccess).isTrue()
        assertThat(result2.await().isSuccess).isTrue()
        // InitializerUseCase should only be called ONCE (not twice)
        coVerify(atMost = 1) { mockInitializerUseCase.run(any()) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 15: Fast path returns immediately when already initialized
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ensureInitialized fast path returns immediately when already initialized`() = runTest {
        configureAllUseCasesSuccess()
        initializationManager = createManager()

        // First call: full init
        initializationManager.ensureInitialized()
        assertThat(initializationManager.isInitialized.value).isTrue()

        // Second call: fast path (no SDK calls)
        val result = initializationManager.ensureInitialized()

        assertThat(result.isSuccess).isTrue()
        // InitializerUseCase called only once (first init), not on second call
        coVerify(exactly = 1) { mockInitializerUseCase.run(any()) }
    }
}

package com.jaac.avoqado_tpv.features.shift.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.MainDispatcherRule
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.dao.CachedShiftDao
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.core.util.ConnectionEventManager
import com.jaac.avoqado_tpv.core.util.ConnectionRestoredEvent
import com.jaac.avoqado_tpv.core.util.ConnectivityObserver
import com.jaac.avoqado_tpv.core.util.NetworkStatus
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal

/**
 * ShiftViewModelTest
 *
 * Safety-net tests for ShiftViewModel covering:
 * - Shift loading (active, no shift, error)
 * - Open/close shift operations
 * - Validation guards
 * - Settings (shift system enabled)
 * - Initial loading state
 * - Offline mode detection
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShiftViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // Mocks
    private lateinit var shiftRepository: ShiftRepository
    private lateinit var secureStorage: SecureStorage
    private lateinit var connectionEventManager: ConnectionEventManager
    private lateinit var cachedShiftDao: CachedShiftDao
    private lateinit var connectivityObserver: ConnectivityObserver

    // Flows we control
    private val fakeConnectionRestoredEvents = MutableSharedFlow<ConnectionRestoredEvent>()
    private val fakeNetworkStatus = MutableSharedFlow<NetworkStatus>()

    private val testShift = Shift(
        id = "shift-123",
        venueId = "venue-123",
        staffId = "staff-123",
        staffName = "Maria Garcia",
        startTime = "2025-01-15T08:00:00Z",
        endTime = null,
        status = ShiftStatus.OPEN,
        startingCash = BigDecimal("500.00"),
        endingCash = null,
        totalSales = BigDecimal("1500.00"),
        totalTips = BigDecimal("200.00"),
        totalOrders = 25,
        totalCashPayments = BigDecimal("800.00"),
        totalCardPayments = BigDecimal("700.00"),
        totalVoucherPayments = BigDecimal.ZERO,
        totalOtherPayments = BigDecimal.ZERO,
        totalProductsSold = 50,
        durationMinutes = 120
    )

    @Before
    fun setup() {
        shiftRepository = mockk(relaxed = true)
        secureStorage = mockk(relaxed = true)
        connectionEventManager = mockk(relaxed = true)
        cachedShiftDao = mockk(relaxed = true)
        connectivityObserver = mockk(relaxed = true)

        // Default returns
        every { secureStorage.getVenueId() } returns "venue-123"
        every { secureStorage.getStaffId() } returns "staff-123"
        every { secureStorage.isShiftSystemEnabled() } returns true
        every { connectionEventManager.connectionRestoredEvents } returns fakeConnectionRestoredEvents
        every { connectivityObserver.observe() } returns fakeNetworkStatus

        // Default: no active shift
        coEvery { shiftRepository.getCurrentShift(any()) } returns Result.Success(null)
        coEvery { shiftRepository.getShiftHistory(any(), any()) } returns Result.Success(emptyList())
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun createViewModel(): ShiftViewModel {
        return ShiftViewModel(
            shiftRepository = shiftRepository,
            secureStorage = secureStorage,
            connectionEventManager = connectionEventManager,
            cachedShiftDao = cachedShiftDao,
            connectivityObserver = connectivityObserver
        )
    }

    // ========================================
    // LOAD SHIFT TESTS
    // ========================================

    @Test
    fun `loads ShiftActive when shift exists`() = runTest {
        // Given
        coEvery { shiftRepository.getCurrentShift("venue-123") } returns Result.Success(testShift)

        // When
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Then
        val state = viewModel.state.value
        assertThat(state).isInstanceOf(ShiftState.ShiftActive::class.java)
        assertThat((state as ShiftState.ShiftActive).shift.id).isEqualTo("shift-123")
    }

    @Test
    fun `loads NoActiveShift when no shift`() = runTest {
        // Given
        coEvery { shiftRepository.getCurrentShift("venue-123") } returns Result.Success(null)

        // When
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Then
        assertThat(viewModel.state.value).isInstanceOf(ShiftState.NoActiveShift::class.java)
    }

    @Test
    fun `Error on missing venueId`() = runTest {
        // Given
        every { secureStorage.getVenueId() } returns null

        // When
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Then
        val state = viewModel.state.value
        assertThat(state).isInstanceOf(ShiftState.Error::class.java)
        assertThat((state as ShiftState.Error).message).contains("local")
    }

    @Test
    fun `Error on repository failure`() = runTest {
        // Given
        coEvery { shiftRepository.getCurrentShift("venue-123") } returns Result.Error(
            ApiException.NetworkError(RuntimeException("NetworkError: Connection refused"))
        )

        // When
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Then
        val state = viewModel.state.value
        assertThat(state).isInstanceOf(ShiftState.Error::class.java)
    }

    // ========================================
    // OPEN SHIFT TESTS
    // ========================================

    @Test
    fun `openShift calls repository`() = runTest {
        // Given
        coEvery { shiftRepository.openShift("venue-123", "staff-123", 500.0) } returns Result.Success(testShift)
        coEvery { shiftRepository.getCurrentShift("venue-123") } returns Result.Success(testShift)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // When
        viewModel.openShift(500.0)
        advanceUntilIdle()

        // Then
        coVerify { shiftRepository.openShift("venue-123", "staff-123", 500.0) }
    }

    @Test
    fun `openShift Error on missing credentials`() = runTest {
        // Given: missing staffId
        every { secureStorage.getStaffId() } returns null
        coEvery { shiftRepository.getCurrentShift("venue-123") } returns Result.Success(null)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // When
        viewModel.openShift(500.0)
        advanceUntilIdle()

        // Then
        val state = viewModel.state.value
        assertThat(state).isInstanceOf(ShiftState.Error::class.java)
        assertThat((state as ShiftState.Error).message).contains("sesión")
    }

    // ========================================
    // CLOSE SHIFT TESTS
    // ========================================

    @Test
    fun `closeShift calls repository from active state`() = runTest {
        // Given: active shift loaded
        coEvery { shiftRepository.getCurrentShift("venue-123") } returns Result.Success(testShift)
        coEvery { shiftRepository.closeShift("venue-123", "shift-123") } returns Result.Success(
            testShift.copy(status = ShiftStatus.CLOSED, endTime = "2025-01-15T16:00:00Z")
        )

        val viewModel = createViewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value).isInstanceOf(ShiftState.ShiftActive::class.java)

        // When
        viewModel.closeShift()
        advanceUntilIdle()

        // Then
        coVerify { shiftRepository.closeShift("venue-123", "shift-123") }
    }

    @Test
    fun `closeShift no-op from non-active state`() = runTest {
        // Given: no active shift
        coEvery { shiftRepository.getCurrentShift("venue-123") } returns Result.Success(null)

        val viewModel = createViewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value).isInstanceOf(ShiftState.NoActiveShift::class.java)

        // When
        viewModel.closeShift()
        advanceUntilIdle()

        // Then: closeShift was NOT called on repository
        coVerify(exactly = 0) { shiftRepository.closeShift(any(), any()) }
    }

    // ========================================
    // SETTINGS TESTS
    // ========================================

    @Test
    fun `isShiftSystemEnabled reflects settings`() = runTest {
        // Given
        every { secureStorage.isShiftSystemEnabled() } returns false

        // When
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Then
        assertThat(viewModel.isShiftSystemEnabled.value).isFalse()
    }

    // ========================================
    // INITIAL LOADING TESTS
    // ========================================

    @Test
    fun `isInitialLoading becomes false after load`() = runTest {
        // Given
        coEvery { shiftRepository.getCurrentShift("venue-123") } returns Result.Success(null)

        // When
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Then
        assertThat(viewModel.isInitialLoading.value).isFalse()
    }

    // ========================================
    // OFFLINE MODE TESTS
    // ========================================

    @Test
    fun `isOffline updates on connectivity change`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Initially online
        assertThat(viewModel.isOffline.value).isFalse()

        // When: network lost
        fakeNetworkStatus.emit(NetworkStatus.Unavailable)
        advanceUntilIdle()

        // Then
        assertThat(viewModel.isOffline.value).isTrue()

        // When: network restored
        fakeNetworkStatus.emit(NetworkStatus.Available)
        advanceUntilIdle()

        // Then
        assertThat(viewModel.isOffline.value).isFalse()
    }
}

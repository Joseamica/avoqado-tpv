package com.jaac.avoqado_tpv.core.presentation.viewmodels

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.bluetooth.BluetoothPaymentService
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.dao.ProductCategoryDao
import com.jaac.avoqado_tpv.core.data.local.dao.ProductDao
import com.jaac.avoqado_tpv.core.data.manager.LockScreenManager
import com.jaac.avoqado_tpv.core.data.manager.MaintenanceManager
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.repository.HeartbeatRepository
import com.jaac.avoqado_tpv.core.domain.events.VenueStatusEvent
import com.jaac.avoqado_tpv.core.domain.repository.TerminalConfigRepository
import com.jaac.avoqado_tpv.core.session.SessionManager
import com.jaac.avoqado_tpv.core.util.ConnectionEventManager
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.core.util.UpdateCheckManager
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.authentication.domain.models.VenueStatus
import com.jaac.avoqado_tpv.features.ordering.domain.ProductRepository
import com.jaac.avoqado_tpv.features.payment.data.InitializationManager
import com.jaac.avoqado_tpv.features.payment.domain.repository.MerchantRepository
import com.jaac.avoqado_tpv.features.payment.domain.use_case.GetMerchantsUseCase
import com.jaac.avoqado_tpv.features.remote_command.domain.CommandExecutor
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Uses UnconfinedTestDispatcher so init coroutines run eagerly through
 * their first iteration, then suspend at delay(). Each test cancels
 * viewModelScope at the end to prevent runTest from hanging.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var authRepository: AuthRepository
    private lateinit var socketManager: SocketManager
    private lateinit var secureStorage: SecureStorage
    private lateinit var initializationManager: InitializationManager
    private lateinit var getMerchantsUseCase: GetMerchantsUseCase
    private lateinit var commandExecutor: CommandExecutor
    private lateinit var heartbeatRepository: HeartbeatRepository
    private lateinit var lockScreenManager: LockScreenManager
    private lateinit var maintenanceManager: MaintenanceManager
    private lateinit var productRepository: ProductRepository
    private lateinit var productDao: ProductDao
    private lateinit var productCategoryDao: ProductCategoryDao
    private lateinit var apiService: ApiService
    private lateinit var connectionEventManager: ConnectionEventManager
    private lateinit var terminalConfigRepository: TerminalConfigRepository
    private lateinit var merchantRepository: MerchantRepository
    private lateinit var deviceInfoManager: DeviceInfoManager
    private lateinit var bluetoothPaymentService: BluetoothPaymentService
    private lateinit var updateCheckManager: UpdateCheckManager
    private lateinit var sessionManager: SessionManager

    private val fakeSessionEvents = MutableSharedFlow<com.jaac.avoqado_tpv.core.session.SessionEvent>()
    private val fakeSocketEvents = MutableSharedFlow<com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent>()
    private val fakeConnectionRestoredEvents = MutableSharedFlow<com.jaac.avoqado_tpv.core.util.ConnectionRestoredEvent>()
    private val fakePendingUpdate = MutableStateFlow<com.jaac.avoqado_tpv.core.data.network.AvoqadoUpdateInfo?>(null)
    private val fakeIsConnected = MutableSharedFlow<Boolean>()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        authRepository = mockk(relaxed = true)
        socketManager = mockk(relaxed = true)
        secureStorage = mockk(relaxed = true)
        initializationManager = mockk(relaxed = true)
        getMerchantsUseCase = mockk(relaxed = true)
        commandExecutor = mockk(relaxed = true)
        heartbeatRepository = mockk(relaxed = true)
        lockScreenManager = mockk(relaxed = true)
        maintenanceManager = mockk(relaxed = true)
        productRepository = mockk(relaxed = true)
        productDao = mockk(relaxed = true)
        productCategoryDao = mockk(relaxed = true)
        apiService = mockk(relaxed = true)
        connectionEventManager = mockk(relaxed = true)
        terminalConfigRepository = mockk(relaxed = true)
        merchantRepository = mockk(relaxed = true)
        deviceInfoManager = mockk(relaxed = true)
        bluetoothPaymentService = mockk(relaxed = true)
        updateCheckManager = mockk(relaxed = true)
        sessionManager = mockk(relaxed = true)

        every { sessionManager.sessionEvents } returns fakeSessionEvents
        every { socketManager.events } returns fakeSocketEvents
        every { socketManager.isConnected } returns fakeIsConnected
        every { connectionEventManager.connectionRestoredEvents } returns fakeConnectionRestoredEvents
        every { updateCheckManager.pendingUpdate } returns fakePendingUpdate

        every { secureStorage.getVenueStatus() } returns VenueStatus.ACTIVE
        every { secureStorage.getVenueId() } returns "venue-123"
        every { secureStorage.getToken() } returns "jwt-token"
        // Note: socketManager.isConnected() function returns false by default (relaxed mock)
        // Don't mock it explicitly — conflicts with the isConnected SharedFlow property
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(): HomeViewModel {
        return HomeViewModel(
            authRepository = authRepository,
            socketManager = socketManager,
            secureStorage = secureStorage,
            initializationManager = initializationManager,
            getMerchantsUseCase = getMerchantsUseCase,
            commandExecutor = commandExecutor,
            heartbeatRepository = heartbeatRepository,
            lockScreenManager = lockScreenManager,
            maintenanceManager = maintenanceManager,
            productRepository = productRepository,
            productDao = productDao,
            productCategoryDao = productCategoryDao,
            apiService = apiService,
            connectionEventManager = connectionEventManager,
            terminalConfigRepository = terminalConfigRepository,
            merchantRepository = merchantRepository,
            deviceInfoManager = deviceInfoManager,
            bluetoothPaymentService = bluetoothPaymentService,
            updateCheckManager = updateCheckManager,
            sessionManager = sessionManager
        )
    }

    // ========================================
    // BLUMON SDK INITIALIZATION TESTS
    // ========================================
    // NOTE: Blumon SDK init tests removed — the init coroutine uses Dispatchers.IO
    // with delay(2000), which can't be controlled via test dispatcher. These would
    // require injecting a dispatcher parameter to test properly.

    // ========================================
    // STAFF NAME LOADING TESTS
    // ========================================

    @Test
    fun `staffName gets loaded from authRepository`() = runTest(testDispatcher) {
        coEvery { authRepository.getStaffName() } returns "Maria Garcia"

        val viewModel = createViewModel()

        assertThat(viewModel.staffName.value).isEqualTo("Maria Garcia")
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `staffName defaults to Usuario when null`() = runTest(testDispatcher) {
        coEvery { authRepository.getStaffName() } returns null

        val viewModel = createViewModel()

        assertThat(viewModel.staffName.value).isEqualTo("Usuario")
        viewModel.viewModelScope.cancel()
    }

    // ========================================
    // LOGOUT TESTS
    // ========================================

    @Test
    fun `logout disconnects socket and calls authRepository`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        viewModel.logout()

        verify { socketManager.disconnect() }
        verify { authRepository.logout() }
        viewModel.viewModelScope.cancel()
    }

    // ========================================
    // VENUE STATUS TESTS
    // ========================================

    @Test
    fun `venueStatus is loaded from secureStorage`() = runTest(testDispatcher) {
        every { secureStorage.getVenueStatus() } returns VenueStatus.TRIAL

        val viewModel = createViewModel()

        assertThat(viewModel.venueStatus.value).isEqualTo(VenueStatus.TRIAL)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `checkVenueStatusChange emits event when status changes`() = runTest(testDispatcher) {
        every { secureStorage.getVenueStatus() } returns VenueStatus.ACTIVE
        val viewModel = createViewModel()

        viewModel.venueStatusEvents.test {
            viewModel.checkVenueStatusChange(VenueStatus.SUSPENDED)

            val event = awaitItem()
            assertThat(event).isInstanceOf(VenueStatusEvent.VenueSuspended::class.java)
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `checkVenueStatusChange does not emit when unchanged`() = runTest(testDispatcher) {
        every { secureStorage.getVenueStatus() } returns VenueStatus.ACTIVE
        val viewModel = createViewModel()

        viewModel.venueStatusEvents.test {
            viewModel.checkVenueStatusChange(VenueStatus.ACTIVE)

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.viewModelScope.cancel()
    }

    // ========================================
    // MAINTENANCE MODE TESTS
    // ========================================

    @Test
    fun `exitMaintenance calls maintenanceManager`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        viewModel.exitMaintenance()

        verify { maintenanceManager.exitMaintenance() }
        viewModel.viewModelScope.cancel()
    }
}

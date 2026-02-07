package com.jaac.avoqado_tpv.core.presentation.viewmodels

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.network.dto.HeartbeatResponseDto
import com.jaac.avoqado_tpv.core.data.repository.HeartbeatRepository
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.core.util.ConnectionEventManager
import com.jaac.avoqado_tpv.core.util.ConnectionStateManager
import com.jaac.avoqado_tpv.core.util.ConnectivityObserver
import com.jaac.avoqado_tpv.core.util.DeviceHealthMonitor
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.core.util.MemoryInfo
import com.jaac.avoqado_tpv.core.util.NetworkInfo
import com.jaac.avoqado_tpv.core.util.NetworkMonitor
import com.jaac.avoqado_tpv.core.util.NetworkStatus
import com.jaac.avoqado_tpv.core.util.NetworkType
import com.jaac.avoqado_tpv.core.util.SystemHealth
import com.jaac.avoqado_tpv.features.remote_command.domain.CommandExecutor
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Uses UnconfinedTestDispatcher so init coroutines (including while(true) loops)
 * run eagerly through their first iteration, then suspend at delay().
 * Each test cancels viewModelScope at the end to prevent runTest from hanging.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var connectivityObserver: ConnectivityObserver
    private lateinit var heartbeatRepository: HeartbeatRepository
    private lateinit var deviceInfoManager: DeviceInfoManager
    private lateinit var deviceHealthMonitor: DeviceHealthMonitor
    private lateinit var connectionEventManager: ConnectionEventManager
    private lateinit var commandExecutor: CommandExecutor
    private lateinit var connectionStateManager: ConnectionStateManager

    private val fakeNetworkStatus = MutableSharedFlow<NetworkStatus>()

    private val connectedNetworkInfo = NetworkInfo(
        type = NetworkType.WIFI,
        isMetered = false,
        isConnected = true,
        signalStrength = 3
    )

    private val disconnectedNetworkInfo = NetworkInfo(
        type = NetworkType.NONE,
        isMetered = false,
        isConnected = false,
        signalStrength = null
    )

    private val fakeSystemHealth = SystemHealth(
        platform = "Android",
        osVersion = "Android 13",
        deviceModel = "PAX A80",
        manufacturer = "PAX",
        batteryLevel = 80,
        batteryCharging = false,
        storageAvailableGB = 5.0f,
        memoryInfo = MemoryInfo(totalMB = 1024, usedMB = 512, freeMB = 512),
        uptime = 100000L
    )

    private val fakeHeartbeatResponse = HeartbeatResponseDto(
        success = true,
        message = "OK",
        serverStatus = null,
        timestamp = "2025-01-01T00:00:00Z",
        pendingCommands = null,
        forceUpdate = null
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        networkMonitor = mockk(relaxed = true)
        connectivityObserver = mockk(relaxed = true)
        heartbeatRepository = mockk(relaxed = true)
        deviceInfoManager = mockk(relaxed = true)
        deviceHealthMonitor = mockk(relaxed = true)
        connectionEventManager = mockk(relaxed = true)
        commandExecutor = mockk(relaxed = true)
        connectionStateManager = mockk(relaxed = true)

        every { networkMonitor.getCurrentNetworkInfo() } returns connectedNetworkInfo
        every { deviceHealthMonitor.getSystemHealth() } returns fakeSystemHealth
        every { deviceInfoManager.getSerialNumber() } returns "TEST-SERIAL"
        every { deviceInfoManager.isDeviceActivated() } returns true
        every { connectivityObserver.observe() } returns fakeNetworkStatus

        coEvery { heartbeatRepository.sendHeartbeat(any()) } returns Result.Success(fakeHeartbeatResponse)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(): ConnectionViewModel {
        return ConnectionViewModel(
            networkMonitor = networkMonitor,
            connectivityObserver = connectivityObserver,
            heartbeatRepository = heartbeatRepository,
            deviceInfoManager = deviceInfoManager,
            deviceHealthMonitor = deviceHealthMonitor,
            connectionEventManager = connectionEventManager,
            commandExecutor = commandExecutor,
            connectionStateManager = connectionStateManager
        )
    }

    // ========================================
    // CONNECTION STATE TESTS
    // ========================================

    @Test
    fun `state becomes Connected after successful heartbeat`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        assertThat(viewModel.state.value).isEqualTo(ConnectionState.Connected)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `state becomes DisconnectedNoInternet when no network`() = runTest(testDispatcher) {
        every { networkMonitor.getCurrentNetworkInfo() } returns disconnectedNetworkInfo
        val viewModel = createViewModel()
        assertThat(viewModel.state.value).isEqualTo(ConnectionState.DisconnectedNoInternet)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `state becomes DisconnectedServerDown when heartbeat fails`() = runTest(testDispatcher) {
        coEvery { heartbeatRepository.sendHeartbeat(any()) } returns Result.Error(
            ApiException.NetworkError(RuntimeException("Server down"))
        )
        val viewModel = createViewModel()
        assertThat(viewModel.state.value).isEqualTo(ConnectionState.DisconnectedServerDown)
        viewModel.viewModelScope.cancel()
    }

    // ========================================
    // DISMISS / FORCE CHECK TESTS
    // ========================================

    @Test
    fun `dismissBanner sets Dismissed state`() = runTest(testDispatcher) {
        every { networkMonitor.getCurrentNetworkInfo() } returns disconnectedNetworkInfo
        val viewModel = createViewModel()
        assertThat(viewModel.state.value).isEqualTo(ConnectionState.DisconnectedNoInternet)

        viewModel.dismissBanner()

        assertThat(viewModel.state.value).isEqualTo(ConnectionState.Dismissed)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `forceCheck clears dismissed and rechecks`() = runTest(testDispatcher) {
        every { networkMonitor.getCurrentNetworkInfo() } returns disconnectedNetworkInfo
        val viewModel = createViewModel()
        viewModel.dismissBanner()
        assertThat(viewModel.state.value).isEqualTo(ConnectionState.Dismissed)

        // Restore network
        every { networkMonitor.getCurrentNetworkInfo() } returns connectedNetworkInfo
        coEvery { heartbeatRepository.sendHeartbeat(any()) } returns Result.Success(fakeHeartbeatResponse)

        viewModel.forceCheck()

        // After forceCheck, state transitions to Reconnected (delay(2000) before Connected)
        // because reconnectionAttempts > 0 from the initial disconnected state
        assertThat(viewModel.state.value).isEqualTo(ConnectionState.Reconnected)
        viewModel.viewModelScope.cancel()
    }

    // ========================================
    // TERMINAL ACTIVATION GUARD
    // ========================================

    @Test
    fun `skip heartbeat when terminal not activated`() = runTest(testDispatcher) {
        every { deviceInfoManager.isDeviceActivated() } returns false
        val viewModel = createViewModel()

        assertThat(viewModel.state.value).isEqualTo(ConnectionState.Connected)
        coVerify(exactly = 0) { heartbeatRepository.sendHeartbeat(any()) }
        viewModel.viewModelScope.cancel()
    }

    // ========================================
    // PENDING COMMANDS PROCESSING
    // ========================================

    @Test
    fun `pending commands from heartbeat are processed`() = runTest(testDispatcher) {
        val commandDto = com.jaac.avoqado_tpv.core.data.network.dto.PendingCommandDto(
            commandId = "cmd-123",
            correlationId = "corr-123",
            type = "LOCK",
            payload = null,
            requiresPin = false,
            priority = "NORMAL",
            expiresAt = "2099-01-01T00:00:00Z",
            requestedBy = "admin@test.com",
            requestedByName = "Admin",
            createdAt = "2025-01-01T00:00:00Z"
        )
        coEvery { heartbeatRepository.sendHeartbeat(any()) } returns Result.Success(
            fakeHeartbeatResponse.copy(pendingCommands = listOf(commandDto))
        )

        val viewModel = createViewModel()

        coVerify { commandExecutor.execute(any()) }
        viewModel.viewModelScope.cancel()
    }
}

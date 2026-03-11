package com.jaac.avoqado_tpv.core.presentation.viewmodels

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.network.AvoqadoUpdateInfo
import com.jaac.avoqado_tpv.core.data.network.UpdateMode
import com.jaac.avoqado_tpv.core.util.ConnectionStateManager
import com.jaac.avoqado_tpv.core.util.ConnectivityObserver
import com.jaac.avoqado_tpv.core.util.DeviceHealthMonitor
import com.jaac.avoqado_tpv.core.util.MemoryInfo
import com.jaac.avoqado_tpv.core.util.NetworkInfo
import com.jaac.avoqado_tpv.core.util.NetworkMonitor
import com.jaac.avoqado_tpv.core.util.NetworkStatus
import com.jaac.avoqado_tpv.core.util.NetworkType
import com.jaac.avoqado_tpv.core.util.SimulatedAlertsManager
import com.jaac.avoqado_tpv.core.util.SystemHealth
import com.jaac.avoqado_tpv.core.util.UpdateCheckManager
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
class DeviceHealthViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var deviceHealthMonitor: DeviceHealthMonitor
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var connectivityObserver: ConnectivityObserver
    private lateinit var simulatedAlertsManager: SimulatedAlertsManager
    private lateinit var connectionStateManager: ConnectionStateManager
    private lateinit var updateCheckManager: UpdateCheckManager
    private lateinit var paymentQueueStateManager: com.jaac.avoqado_tpv.core.util.PaymentQueueStateManager
    private lateinit var paymentQueueRepository: com.jaac.avoqado_tpv.features.payment.domain.repository.PaymentQueueRepository

    private val fakeNetworkStatus = MutableSharedFlow<NetworkStatus>()
    private val fakeSimulatedAlerts = MutableStateFlow<Set<DeviceAlert>>(emptySet())
    private val fakeConnectionState = MutableStateFlow(
        com.jaac.avoqado_tpv.core.util.ConnectionState(hasInternet = true, hasServer = true)
    )
    private val fakePendingUpdate = MutableStateFlow<AvoqadoUpdateInfo?>(null)

    private fun healthySystem() = SystemHealth(
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

    private val connectedNetworkInfo = NetworkInfo(
        type = NetworkType.WIFI,
        isMetered = false,
        isConnected = true,
        signalStrength = 3
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        deviceHealthMonitor = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        connectivityObserver = mockk(relaxed = true)
        simulatedAlertsManager = mockk(relaxed = true)
        connectionStateManager = mockk(relaxed = true)
        updateCheckManager = mockk(relaxed = true)
        paymentQueueStateManager = com.jaac.avoqado_tpv.core.util.PaymentQueueStateManager()
        paymentQueueRepository = mockk(relaxed = true)

        every { deviceHealthMonitor.getSystemHealth() } returns healthySystem()
        every { networkMonitor.getCurrentNetworkInfo() } returns connectedNetworkInfo
        every { connectivityObserver.observe() } returns fakeNetworkStatus
        every { simulatedAlertsManager.simulatedAlerts } returns fakeSimulatedAlerts
        every { connectionStateManager.connectionState } returns fakeConnectionState
        every { updateCheckManager.pendingUpdate } returns fakePendingUpdate
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(): DeviceHealthViewModel {
        return DeviceHealthViewModel(
            deviceHealthMonitor = deviceHealthMonitor,
            networkMonitor = networkMonitor,
            connectivityObserver = connectivityObserver,
            simulatedAlertsManager = simulatedAlertsManager,
            connectionStateManager = connectionStateManager,
            updateCheckManager = updateCheckManager,
            paymentQueueStateManager = paymentQueueStateManager,
            paymentQueueRepository = paymentQueueRepository
        )
    }

    // ========================================
    // HEALTHY DEVICE TESTS
    // ========================================

    @Test
    fun `no alerts when device is healthy`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        assertThat(viewModel.activeAlerts.value).isEmpty()
        viewModel.viewModelScope.cancel()
    }

    // ========================================
    // BATTERY ALERT TESTS
    // ========================================

    @Test
    fun `battery critical alert at 5 percent`() = runTest(testDispatcher) {
        every { deviceHealthMonitor.getSystemHealth() } returns healthySystem().copy(
            batteryLevel = 5,
            batteryCharging = false
        )

        val viewModel = createViewModel()

        val alerts = viewModel.activeAlerts.value
        assertThat(alerts).hasSize(1)
        assertThat(alerts.first()).isInstanceOf(DeviceAlert.BatteryCritical::class.java)
        assertThat((alerts.first() as DeviceAlert.BatteryCritical).level).isEqualTo(5)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `battery low alert at 15 percent`() = runTest(testDispatcher) {
        every { deviceHealthMonitor.getSystemHealth() } returns healthySystem().copy(
            batteryLevel = 15,
            batteryCharging = false
        )

        val viewModel = createViewModel()

        val alerts = viewModel.activeAlerts.value
        assertThat(alerts).hasSize(1)
        assertThat(alerts.first()).isInstanceOf(DeviceAlert.BatteryLow::class.java)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `no battery alert when charging`() = runTest(testDispatcher) {
        every { deviceHealthMonitor.getSystemHealth() } returns healthySystem().copy(
            batteryLevel = 5,
            batteryCharging = true
        )

        val viewModel = createViewModel()

        val batteryAlerts = viewModel.activeAlerts.value.filter {
            it is DeviceAlert.BatteryCritical || it is DeviceAlert.BatteryLow
        }
        assertThat(batteryAlerts).isEmpty()
        viewModel.viewModelScope.cancel()
    }

    // ========================================
    // PRIORITY & SORTING TESTS
    // ========================================

    @Test
    fun `alerts sorted by priority`() = runTest(testDispatcher) {
        every { deviceHealthMonitor.getSystemHealth() } returns healthySystem().copy(
            batteryLevel = 5,
            batteryCharging = false,
            memoryInfo = MemoryInfo(totalMB = 1024, usedMB = 960, freeMB = 64)
        )
        fakeConnectionState.value = com.jaac.avoqado_tpv.core.util.ConnectionState(
            hasInternet = false,
            hasServer = false
        )

        val viewModel = createViewModel()

        val alerts = viewModel.activeAlerts.value
        assertThat(alerts.size).isAtLeast(2)
        for (i in 0 until alerts.size - 1) {
            assertThat(alerts[i].priority).isAtMost(alerts[i + 1].priority)
        }
        assertThat(alerts.first()).isInstanceOf(DeviceAlert.NoInternet::class.java)
        viewModel.viewModelScope.cancel()
    }

    // ========================================
    // DISMISS TESTS
    // ========================================

    @Test
    fun `critical alerts cannot be dismissed`() = runTest(testDispatcher) {
        fakeConnectionState.value = com.jaac.avoqado_tpv.core.util.ConnectionState(
            hasInternet = false,
            hasServer = false
        )

        val viewModel = createViewModel()

        val alerts = viewModel.activeAlerts.value
        assertThat(alerts).isNotEmpty()

        viewModel.dismissAlert(alerts.first())

        assertThat(viewModel.activeAlerts.value).contains(alerts.first())
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `non-critical alerts can be dismissed`() = runTest(testDispatcher) {
        every { deviceHealthMonitor.getSystemHealth() } returns healthySystem().copy(
            memoryInfo = MemoryInfo(totalMB = 1024, usedMB = 960, freeMB = 64)
        )

        val viewModel = createViewModel()

        val alerts = viewModel.activeAlerts.value
        assertThat(alerts).hasSize(1)
        assertThat(alerts.first()).isInstanceOf(DeviceAlert.MemoryLow::class.java)

        viewModel.dismissAlert(alerts.first())

        assertThat(viewModel.activeAlerts.value).isEmpty()
        viewModel.viewModelScope.cancel()
    }

    // ========================================
    // UI STATE TESTS
    // ========================================

    @Test
    fun `toggleExpanded works`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        assertThat(viewModel.isExpanded.value).isFalse()

        viewModel.toggleExpanded()
        assertThat(viewModel.isExpanded.value).isTrue()

        viewModel.toggleExpanded()
        assertThat(viewModel.isExpanded.value).isFalse()
        viewModel.viewModelScope.cancel()
    }

    // ========================================
    // UPDATE ALERT TESTS
    // ========================================

    @Test
    fun `update available has highest priority`() = runTest(testDispatcher) {
        every { deviceHealthMonitor.getSystemHealth() } returns healthySystem().copy(
            batteryLevel = 15,
            batteryCharging = false
        )
        fakePendingUpdate.value = AvoqadoUpdateInfo(
            id = "update-1",
            versionName = "2.0.0",
            versionCode = 200,
            downloadUrl = "https://example.com/update.apk",
            fileSize = "50000000",
            checksum = "abc123",
            releaseNotes = "New features",
            updateMode = UpdateMode.BANNER,
            minAndroidSdk = 28,
            publishedAt = "2025-01-01T00:00:00Z"
        )

        val viewModel = createViewModel()

        val alerts = viewModel.activeAlerts.value
        assertThat(alerts.size).isAtLeast(2)
        assertThat(alerts[0]).isInstanceOf(DeviceAlert.UpdateAvailable::class.java)
        assertThat(alerts[1]).isInstanceOf(DeviceAlert.BatteryLow::class.java)
        viewModel.viewModelScope.cancel()
    }

    // ========================================
    // CONNECTION STATE ALERT TESTS
    // ========================================

    @Test
    fun `no internet alert when disconnected`() = runTest(testDispatcher) {
        fakeConnectionState.value = com.jaac.avoqado_tpv.core.util.ConnectionState(
            hasInternet = false,
            hasServer = false
        )

        val viewModel = createViewModel()

        val alerts = viewModel.activeAlerts.value
        assertThat(alerts.any { it is DeviceAlert.NoInternet }).isTrue()
        viewModel.viewModelScope.cancel()
    }

    // ========================================
    // SLOW CONNECTION ALERT TESTS
    // ========================================

    @Test
    fun `slow connection alert when isSlowConnection is true`() = runTest(testDispatcher) {
        fakeConnectionState.value = com.jaac.avoqado_tpv.core.util.ConnectionState(
            hasInternet = true,
            hasServer = true,
            latencyMs = 3732,
            isSlowConnection = true
        )

        val viewModel = createViewModel()

        val alerts = viewModel.activeAlerts.value
        assertThat(alerts.any { it is DeviceAlert.SlowConnection }).isTrue()
        val slowAlert = alerts.first { it is DeviceAlert.SlowConnection } as DeviceAlert.SlowConnection
        assertThat(slowAlert.latencyMs).isEqualTo(3732)
        assertThat(slowAlert.message).isEqualTo("Internet lento")
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `no slow connection alert when isSlowConnection is false`() = runTest(testDispatcher) {
        fakeConnectionState.value = com.jaac.avoqado_tpv.core.util.ConnectionState(
            hasInternet = true,
            hasServer = true,
            latencyMs = 1578,
            isSlowConnection = false
        )

        val viewModel = createViewModel()

        val alerts = viewModel.activeAlerts.value
        assertThat(alerts.none { it is DeviceAlert.SlowConnection }).isTrue()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `slow connection alert with null latencyMs during cooldown does not crash`() = runTest(testDispatcher) {
        // During hysteresis cooldown, isSlowConnection can be true while latencyMs is null
        fakeConnectionState.value = com.jaac.avoqado_tpv.core.util.ConnectionState(
            hasInternet = true,
            hasServer = true,
            latencyMs = null,
            isSlowConnection = true
        )

        val viewModel = createViewModel()

        val alerts = viewModel.activeAlerts.value
        assertThat(alerts.any { it is DeviceAlert.SlowConnection }).isTrue()
        val slowAlert = alerts.first { it is DeviceAlert.SlowConnection } as DeviceAlert.SlowConnection
        assertThat(slowAlert.latencyMs).isEqualTo(0L)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `no slow connection alert when disconnected even with slow flag`() = runTest(testDispatcher) {
        fakeConnectionState.value = com.jaac.avoqado_tpv.core.util.ConnectionState(
            hasInternet = false,
            hasServer = false,
            latencyMs = 10000,
            isSlowConnection = true
        )

        val viewModel = createViewModel()

        val alerts = viewModel.activeAlerts.value
        // Should have NoInternet, not SlowConnection
        assertThat(alerts.none { it is DeviceAlert.SlowConnection }).isTrue()
        assertThat(alerts.any { it is DeviceAlert.NoInternet }).isTrue()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `slow connection alert is dismissable`() = runTest(testDispatcher) {
        fakeConnectionState.value = com.jaac.avoqado_tpv.core.util.ConnectionState(
            hasInternet = true,
            hasServer = true,
            latencyMs = 6000,
            isSlowConnection = true
        )

        val viewModel = createViewModel()

        val alerts = viewModel.activeAlerts.value
        assertThat(alerts.any { it is DeviceAlert.SlowConnection }).isTrue()

        viewModel.dismissAlert(alerts.first { it is DeviceAlert.SlowConnection })

        assertThat(viewModel.activeAlerts.value.none { it is DeviceAlert.SlowConnection }).isTrue()
        viewModel.viewModelScope.cancel()
    }

    // ========================================
    // PENDING PAYMENTS ALERT TESTS
    // ========================================

    @Test
    fun `pending payments alert when queue has pending items`() = runTest(testDispatcher) {
        paymentQueueStateManager.refreshCounts(pendingCount = 3, failedCount = 0)

        val viewModel = createViewModel()

        val alerts = viewModel.activeAlerts.value
        assertThat(alerts.any { it is DeviceAlert.PendingPayments }).isTrue()
        val paymentAlert = alerts.first { it is DeviceAlert.PendingPayments } as DeviceAlert.PendingPayments
        assertThat(paymentAlert.pendingCount).isEqualTo(3)
        assertThat(paymentAlert.failedCount).isEqualTo(0)
        assertThat(paymentAlert.message).isEqualTo("3 pagos pendientes")
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `pending payments alert shows failed count when failures exist`() = runTest(testDispatcher) {
        paymentQueueStateManager.refreshCounts(pendingCount = 1, failedCount = 2)

        val viewModel = createViewModel()

        val alerts = viewModel.activeAlerts.value
        assertThat(alerts.any { it is DeviceAlert.PendingPayments }).isTrue()
        val paymentAlert = alerts.first { it is DeviceAlert.PendingPayments } as DeviceAlert.PendingPayments
        assertThat(paymentAlert.failedCount).isEqualTo(2)
        assertThat(paymentAlert.message).isEqualTo("2 pagos fallidos")
        assertThat(paymentAlert.description).isEqualTo("Toca para reintentar sincronización")
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `no pending payments alert when queue is empty`() = runTest(testDispatcher) {
        // Default state — no payments
        val viewModel = createViewModel()

        val alerts = viewModel.activeAlerts.value
        assertThat(alerts.none { it is DeviceAlert.PendingPayments }).isTrue()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `pending payments alert is dismissable`() = runTest(testDispatcher) {
        paymentQueueStateManager.refreshCounts(pendingCount = 2, failedCount = 0)

        val viewModel = createViewModel()

        val alerts = viewModel.activeAlerts.value
        assertThat(alerts.any { it is DeviceAlert.PendingPayments }).isTrue()

        viewModel.dismissAlert(alerts.first { it is DeviceAlert.PendingPayments })

        assertThat(viewModel.activeAlerts.value.none { it is DeviceAlert.PendingPayments }).isTrue()
        viewModel.viewModelScope.cancel()
    }
}

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
import com.jaac.avoqado_tpv.core.util.ControlDeWifi
import com.jaac.avoqado_tpv.core.util.EstadoWifiSinSalida
import com.jaac.avoqado_tpv.core.util.ResultadoReinicio
import com.jaac.avoqado_tpv.core.util.VeredictoWifi
import com.jaac.avoqado_tpv.core.util.WifiSinSalidaMonitor
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.payment.domain.model.CellularFailoverMode
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings
import com.jaac.avoqado_tpv.features.remote_command.domain.CommandExecutor
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
    private lateinit var tpvSettingsRepository: TpvSettingsRepository
    private lateinit var wifiSinSalidaMonitor: WifiSinSalidaMonitor
    private lateinit var controlDeWifi: ControlDeWifi

    private val fakeNetworkStatus = MutableSharedFlow<NetworkStatus>()
    private lateinit var connectionSnapshotState: MutableStateFlow<com.jaac.avoqado_tpv.core.util.ConnectionState>

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
        tpvSettingsRepository = mockk(relaxed = true)
        wifiSinSalidaMonitor = mockk(relaxed = true)
        controlDeWifi = mockk(relaxed = true)

        connectionSnapshotState = MutableStateFlow(
            com.jaac.avoqado_tpv.core.util.ConnectionState(
                hasInternet = true,
                hasServer = true,
                latencyMs = null,
                isSlowConnection = false
            )
        )

        every { networkMonitor.getCurrentNetworkInfo() } returns connectedNetworkInfo
        every { deviceHealthMonitor.getSystemHealth() } returns fakeSystemHealth
        every { deviceInfoManager.getSerialNumber() } returns "TEST-SERIAL"
        every { deviceInfoManager.isDeviceActivated() } returns true
        every { connectivityObserver.observe() } returns fakeNetworkStatus
        every { connectionStateManager.connectionState } returns connectionSnapshotState
        every { connectionStateManager.updateState(any(), any(), any()) } answers {
            connectionSnapshotState.value = connectionSnapshotState.value.copy(
                hasInternet = firstArg(),
                hasServer = secondArg(),
                latencyMs = thirdArg()
            )
        }
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings.DEFAULT
        coEvery { wifiSinSalidaMonitor.evaluar(any(), any(), any()) } returns VeredictoWifi.SANO
        coEvery { controlDeWifi.reiniciar(any(), any()) } returns ResultadoReinicio.NoSeReinicio("prueba")
        coEvery { controlDeWifi.restaurarSiQuedoApagado() } returns true
        every { connectionStateManager.setWifiSinSalida(any()) } answers {
            connectionSnapshotState.value = connectionSnapshotState.value.copy(wifiSinSalida = firstArg())
        }

        coEvery { heartbeatRepository.sendHeartbeat(any()) } returns Result.Success(fakeHeartbeatResponse)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    /**
     * [esPax] = false describe la Nexgo (las pruebas corren en sandboxDebug, que ES PAX). Con [UnconfinedTestDispatcher]
     * el `init` evalúa DENTRO del constructor: se retiene esa primera evaluación en su primera suspensión
     * (`restaurarSiQuedoApagado`) hasta fijar la costura, para que ninguna evaluación lea el BuildConfig.
     */
    private fun createViewModel(esPax: Boolean = false): ConnectionViewModel {
        val costuraFijada = CompletableDeferred<Unit>()
        coEvery { controlDeWifi.restaurarSiQuedoApagado() } coAnswers { costuraFijada.await(); true }
        return ConnectionViewModel(
            networkMonitor = networkMonitor,
            connectivityObserver = connectivityObserver,
            heartbeatRepository = heartbeatRepository,
            deviceInfoManager = deviceInfoManager,
            deviceHealthMonitor = deviceHealthMonitor,
            connectionEventManager = connectionEventManager,
            commandExecutor = commandExecutor,
            connectionStateManager = connectionStateManager,
            tpvSettingsRepository = tpvSettingsRepository,
            wifiSinSalidaMonitor = wifiSinSalidaMonitor,
            controlDeWifi = controlDeWifi,
        ).also {
            it.esPax = esPax
            costuraFijada.complete(Unit)
        }
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
        // Grace period: offline not declared immediately (hysteresis)
        advanceTimeBy(ConnectionViewModel.OFFLINE_GRACE_MS + 1)
        assertThat(viewModel.state.value).isEqualTo(ConnectionState.DisconnectedNoInternet)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `single heartbeat failure does NOT show DisconnectedServerDown (hysteresis)`() = runTest(testDispatcher) {
        // First call fails, second call succeeds → hysteresis recovers without painting banner
        coEvery { heartbeatRepository.sendHeartbeat(any()) } returnsMany listOf(
            Result.Error(ApiException.NetworkError(RuntimeException("First failure"))),
            Result.Success(fakeHeartbeatResponse)
        )

        val viewModel = createViewModel()

        // After init heartbeat failure (count=1, below threshold=2), state must NOT be DisconnectedServerDown.
        // Hysteresis filters single failures (HTTP/2 stale streams that pingInterval evicts in <=15s).
        assertThat(viewModel.state.value).isNotEqualTo(ConnectionState.DisconnectedServerDown)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `state becomes DisconnectedServerDown after TWO consecutive heartbeat failures`() = runTest(testDispatcher) {
        coEvery { heartbeatRepository.sendHeartbeat(any()) } returns Result.Error(
            ApiException.NetworkError(RuntimeException("Server down"))
        )

        val viewModel = createViewModel()

        // First failure: init heartbeat → schedules fast re-probe (~2s) instead of painting banner.
        // Second failure: triggered by fast re-probe → reaches SERVER_DOWN_FAILURE_THRESHOLD=2 → banner shows.
        advanceTimeBy(2_500)
        runCurrent()

        assertThat(viewModel.state.value).isEqualTo(ConnectionState.DisconnectedServerDown)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `forceCheck failure does NOT leave UI stuck on Reconnecting (single-failure hysteresis)`() = runTest(testDispatcher) {
        coEvery { heartbeatRepository.sendHeartbeat(any()) } returns Result.Error(
            ApiException.NetworkError(RuntimeException("Server down"))
        )
        val viewModel = createViewModel()
        // Init heartbeat fails → state may go through Reconnecting/Checking briefly.
        runCurrent()

        // Tap Reintentar manually — state goes to Reconnecting, probe fires, fails (single failure below threshold).
        // Without the fix this would leave state stuck on Reconnecting until the next monitoring cycle.
        // With the fix, state reverts to lastConfirmedConnectionState (Connected at startup default).
        viewModel.forceCheck()
        runCurrent()

        // State must NOT be Reconnecting (the bug we're guarding against — stuck spinner).
        assertThat(viewModel.state.value).isNotEqualTo(ConnectionState.Reconnecting)
        viewModel.viewModelScope.cancel()
    }

    // ========================================
    // DISMISS / FORCE CHECK TESTS
    // ========================================

    @Test
    fun `dismissBanner sets Dismissed state`() = runTest(testDispatcher) {
        every { networkMonitor.getCurrentNetworkInfo() } returns disconnectedNetworkInfo
        val viewModel = createViewModel()
        // Wait for grace period to declare offline
        advanceTimeBy(ConnectionViewModel.OFFLINE_GRACE_MS + 1)
        assertThat(viewModel.state.value).isEqualTo(ConnectionState.DisconnectedNoInternet)

        viewModel.dismissBanner()

        assertThat(viewModel.state.value).isEqualTo(ConnectionState.Dismissed)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `forceCheck clears dismissed and rechecks`() = runTest(testDispatcher) {
        every { networkMonitor.getCurrentNetworkInfo() } returns disconnectedNetworkInfo
        val viewModel = createViewModel()
        // Wait for grace period, then dismiss
        advanceTimeBy(ConnectionViewModel.OFFLINE_GRACE_MS + 1)
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
    fun `recovered connection refreshes paid access once without a settings polling loop`() = runTest(testDispatcher) {
        every { networkMonitor.getCurrentNetworkInfo() } returns disconnectedNetworkInfo
        coEvery { tpvSettingsRepository.refreshFromTerminalConfig(any()) } returns kotlin.Result.success(TpvSettings.DEFAULT)
        val viewModel = createViewModel()
        try {
            advanceTimeBy(ConnectionViewModel.OFFLINE_GRACE_MS + 1)
            coVerify(exactly = 0) { tpvSettingsRepository.refreshFromTerminalConfig(any()) }
            every { networkMonitor.getCurrentNetworkInfo() } returns connectedNetworkInfo
            viewModel.forceCheck()
            runCurrent()
            coVerify(exactly = 1) { tpvSettingsRepository.refreshFromTerminalConfig("TEST-SERIAL") }
            advanceTimeBy(2100)
            viewModel.forceCheck()
            runCurrent()
            coVerify(exactly = 1) { tpvSettingsRepository.refreshFromTerminalConfig("TEST-SERIAL") }
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

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

        coVerify { commandExecutor.recoverPending() }
        viewModel.viewModelScope.cancel()
    }

    // ========================================
    // HYSTERESIS / GRACE PERIOD TESTS
    // Uses StandardTestDispatcher for virtual time control
    // ========================================

    @Test
    fun `offline NOT declared before grace period expires`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))

        every { networkMonitor.getCurrentNetworkInfo() } returns connectedNetworkInfo
        coEvery { heartbeatRepository.sendHeartbeat(any()) } returns Result.Success(fakeHeartbeatResponse)

        val viewModel = createViewModel()
        runCurrent() // Init: heartbeat succeeds → Connected
        assertThat(viewModel.state.value).isEqualTo(ConnectionState.Connected)

        // Network lost
        every { networkMonitor.getCurrentNetworkInfo() } returns disconnectedNetworkInfo
        fakeNetworkStatus.emit(NetworkStatus.Unavailable)
        runCurrent() // scheduleOfflineTransition starts delay

        // Within grace period — should NOT be offline
        advanceTimeBy(ConnectionViewModel.OFFLINE_GRACE_MS - 1)
        assertThat(viewModel.state.value).isNotEqualTo(ConnectionState.DisconnectedNoInternet)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `offline declared after grace period when network stays down`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))

        every { networkMonitor.getCurrentNetworkInfo() } returns connectedNetworkInfo
        coEvery { heartbeatRepository.sendHeartbeat(any()) } returns Result.Success(fakeHeartbeatResponse)

        val viewModel = createViewModel()
        runCurrent()
        assertThat(viewModel.state.value).isEqualTo(ConnectionState.Connected)

        // Network lost — stays down
        every { networkMonitor.getCurrentNetworkInfo() } returns disconnectedNetworkInfo
        fakeNetworkStatus.emit(NetworkStatus.Unavailable)
        runCurrent()

        // Past grace period — re-validation confirms offline
        advanceTimeBy(ConnectionViewModel.OFFLINE_GRACE_MS + 1)
        assertThat(viewModel.state.value).isEqualTo(ConnectionState.DisconnectedNoInternet)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `offline transition cancelled when network recovers within grace period`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))

        every { networkMonitor.getCurrentNetworkInfo() } returns connectedNetworkInfo
        coEvery { heartbeatRepository.sendHeartbeat(any()) } returns Result.Success(fakeHeartbeatResponse)

        val viewModel = createViewModel()
        runCurrent()
        assertThat(viewModel.state.value).isEqualTo(ConnectionState.Connected)

        // Network lost
        every { networkMonitor.getCurrentNetworkInfo() } returns disconnectedNetworkInfo
        fakeNetworkStatus.emit(NetworkStatus.Unavailable)
        runCurrent()

        // Network recovers before grace period — re-validation will see connected
        advanceTimeBy(1_500)
        every { networkMonitor.getCurrentNetworkInfo() } returns connectedNetworkInfo
        fakeNetworkStatus.emit(NetworkStatus.Available)
        runCurrent() // cancelOfflineTransition() + starts ONLINE_STABILIZATION_MS delay

        // Grace period would have expired — but job was cancelled
        advanceTimeBy(ConnectionViewModel.OFFLINE_GRACE_MS)

        // Online stabilization completes → probe succeeds
        advanceTimeBy(ConnectionViewModel.ONLINE_STABILIZATION_MS)

        // Should never have gone offline
        assertThat(viewModel.state.value).isNotEqualTo(ConnectionState.DisconnectedNoInternet)

        viewModel.viewModelScope.cancel()
    }

    // ========================================
    // WIFI SIN SALIDA (29-sep-2026): detectar siempre, reiniciar sólo en AUTO_ENFORCED fuera de PAX
    // ========================================

    private fun modo(m: CellularFailoverMode) {
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings.DEFAULT.copy(cellularFailoverMode = m)
    }

    @Test
    fun `P1 wifi sin salida en enforced pide el reinicio y avisa REINICIANDO`() = runTest(testDispatcher) {
        modo(CellularFailoverMode.AUTO_ENFORCED)
        coEvery { wifiSinSalidaMonitor.evaluar(any(), any(), any()) } returns VeredictoWifi.WIFI_SIN_SALIDA
        coEvery { controlDeWifi.reiniciar(any(), any()) } returns ResultadoReinicio.Reiniciado
        val vm = createViewModel(); runCurrent()
        coVerifyOrder {
            connectionStateManager.setWifiSinSalida(EstadoWifiSinSalida.REINICIANDO)
            controlDeWifi.reiniciar(any(), any())
            connectionStateManager.setWifiSinSalida(EstadoWifiSinSalida.DETECTADO)
        }
        coVerify { wifiSinSalidaMonitor.trasReiniciar() }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `P2 si el incidente se cerro durante el reinicio no se resucita DETECTADO`() = runTest(testDispatcher) {
        // Otra evaluación cerró el incidente (publicó NO) mientras el reinicio estaba suspendido (Codex código #4).
        modo(CellularFailoverMode.AUTO_ENFORCED)
        coEvery { wifiSinSalidaMonitor.evaluar(any(), any(), any()) } returns VeredictoWifi.WIFI_SIN_SALIDA
        coEvery { controlDeWifi.reiniciar(any(), any()) } coAnswers {
            connectionSnapshotState.value = connectionSnapshotState.value.copy(wifiSinSalida = EstadoWifiSinSalida.NO)
            ResultadoReinicio.NoSeReinicio("el_enlace_volvio")
        }
        val vm = createViewModel(); runCurrent()
        // finally: si una aserción falla sin cancelar, runTest drena el while(true) del VM hasta agotar la memoria.
        try {
            coVerify { controlDeWifi.reiniciar(any(), any()) }
            verify(exactly = 0) { connectionStateManager.setWifiSinSalida(EstadoWifiSinSalida.DETECTADO) }
            assertThat(connectionSnapshotState.value.wifiSinSalida).isEqualTo(EstadoWifiSinSalida.NO)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `P1 sin veredicto no se reinicia aunque no haya internet`() = runTest(testDispatcher) {
        // Codex v3 #4: sin red real (no sólo el estado), porque con red y heartbeat OK el arranque reescribe ambos a true.
        modo(CellularFailoverMode.AUTO_ENFORCED)
        every { networkMonitor.getCurrentNetworkInfo() } returns disconnectedNetworkInfo
        connectionSnapshotState.value = com.jaac.avoqado_tpv.core.util.ConnectionState(hasInternet = false, hasServer = false)
        val vm = createViewModel(); runCurrent()
        assertThat(connectionSnapshotState.value.hasInternet).isFalse()
        coVerify(exactly = 0) { controlDeWifi.reiniciar(any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `P1 si el modo cambia antes de actuar no se reinicia`() = runTest(testDispatcher) {
        every { tpvSettingsRepository.getCurrentSettings() } returnsMany listOf(
            TpvSettings.DEFAULT.copy(cellularFailoverMode = CellularFailoverMode.AUTO_ENFORCED),
        ) andThen TpvSettings.DEFAULT   // OFF desde la segunda lectura
        coEvery { wifiSinSalidaMonitor.evaluar(any(), any(), any()) } returns VeredictoWifi.WIFI_SIN_SALIDA
        every { wifiSinSalidaMonitor.sigueSinSalida() } returns true
        coEvery { controlDeWifi.reiniciar(any(), any()) } coAnswers {
            val puedeActuar = secondArg<() -> Boolean>()
            if (puedeActuar()) ResultadoReinicio.Reiniciado else ResultadoReinicio.NoSeReinicio("ya_no_aplica")
        }
        val vm = createViewModel(); runCurrent()
        coVerify { wifiSinSalidaMonitor.registrarAccion("no_se_reinicio:ya_no_aplica") }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `sombra detecta y anota una vez pero nunca reinicia`() = runTest(testDispatcher) {
        modo(CellularFailoverMode.AUTO_SHADOW)
        coEvery { wifiSinSalidaMonitor.evaluar(any(), any(), any()) } returns VeredictoWifi.WIFI_SIN_SALIDA
        val vm = createViewModel(); runCurrent()
        vm.forceCheck(); runCurrent()
        coVerify(exactly = 0) { controlDeWifi.reiniciar(any(), any()) }
        coVerify(exactly = 1) { wifiSinSalidaMonitor.registrarAccion("habria_reiniciado_wifi") }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `al arrancar y en cada evaluacion restaura una marca heredada`() = runTest(testDispatcher) {
        modo(CellularFailoverMode.OFF)
        val vm = createViewModel(); runCurrent()
        coVerify(atLeast = 1) { controlDeWifi.restaurarSiQuedoApagado() }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `la causa guardada es la raiz, no el envoltorio`() = runTest(testDispatcher) {
        modo(CellularFailoverMode.OFF)
        coEvery { heartbeatRepository.sendHeartbeat(any()) } returns
            Result.Error(ApiException.NetworkError(java.net.UnknownHostException("api.avoqado.io")))
        val vm = createViewModel(); runCurrent()
        vm.forceCheck(); runCurrent()
        coVerify { wifiSinSalidaMonitor.evaluar(any(), "UnknownHostException", any()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `P1 en PAX enforced se comporta como sombra y nunca reinicia`() = runTest(testDispatcher) {
        modo(CellularFailoverMode.AUTO_ENFORCED)
        coEvery { wifiSinSalidaMonitor.evaluar(any(), any(), any()) } returns VeredictoWifi.WIFI_SIN_SALIDA
        val vm = createViewModel(esPax = true); runCurrent()
        coVerify(exactly = 0) { controlDeWifi.reiniciar(any(), any()) }
        coVerify(exactly = 1) { wifiSinSalidaMonitor.registrarAccion("habria_reiniciado_wifi") }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `modoEfectivoWifi degrada solo enforced y solo en PAX`() {
        CellularFailoverMode.values().forEach { m ->
            assertThat(modoEfectivoWifi(m, esPax = false)).isEqualTo(m)
            val esperado = if (m == CellularFailoverMode.AUTO_ENFORCED) CellularFailoverMode.AUTO_SHADOW else m
            assertThat(modoEfectivoWifi(m, esPax = true)).isEqualTo(esperado)
        }
    }
}

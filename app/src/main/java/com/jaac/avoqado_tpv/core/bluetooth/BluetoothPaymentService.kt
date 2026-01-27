package com.jaac.avoqado_tpv.core.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connected Device Info (currently active connection)
 */
data class ConnectedDevice(
    val address: String,
    val name: String?
)

/**
 * Known Device Info (persisted across app restarts)
 * Includes connection status for UI display
 */
data class KnownDevice(
    val address: String,
    val name: String?,
    val isConnected: Boolean,
    val lastConnectedAt: Long,
    val connectionCount: Int
)

/**
 * BluetoothPaymentService
 *
 * Singleton coordinator that manages BLE Payment Foreground Service.
 * This class provides a clean API for ViewModels while delegating actual
 * BLE operations to `BluetoothPaymentForegroundService`.
 *
 * **Architecture (Square/Toast Pattern):**
 * ```
 * ViewModel → BluetoothPaymentService (Coordinator) → BluetoothPaymentForegroundService (Android Service)
 *                   ↓                                              ↓
 *           Observes StateFlows                           Manages BluetoothPaymentServer
 *           Forwards payment events                       Shows persistent notification
 *                                                         Survives APK updates (START_STICKY)
 * ```
 *
 * **Why Foreground Service?**
 * - Regular Hilt singletons die when app process dies (APK update)
 * - Foreground Services with START_STICKY are restarted by Android automatically
 * - Shows persistent notification so user knows server is running
 * - Survives app updates without losing BLE connections
 *
 * **State Persistence:**
 * - Server state persists across app restarts via SecureStorage
 * - Android restarts the Foreground Service automatically (START_STICKY)
 * - Combined: BLE server survives both app restarts AND APK updates
 */
@Singleton
class BluetoothPaymentService @Inject constructor(
    private val secureStorage: SecureStorage,
    @ApplicationContext private val context: Context
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC STATE (delegates to Foreground Service's static StateFlows)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Whether BLE server is currently running
     */
    val isRunning: StateFlow<Boolean> = BluetoothPaymentForegroundService.isRunning

    /**
     * All connected devices (multi-device support)
     */
    val connectedDevicesMap: StateFlow<Map<String, BluetoothPaymentForegroundService.ConnectedDeviceInfo>> =
        BluetoothPaymentForegroundService.connectedDevices

    /**
     * Connected devices as a list (for easier UI consumption)
     */
    val connectedDevicesList: StateFlow<List<ConnectedDevice>> = connectedDevicesMap
        .map { devicesMap ->
            devicesMap.values.map { info ->
                ConnectedDevice(address = info.address, name = info.name)
            }
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    /**
     * Currently connected device name (null if none) - LEGACY
     */
    @Deprecated("Use connectedDevicesList for multi-device support")
    val connectedDeviceName: StateFlow<String?> = BluetoothPaymentForegroundService.connectedDeviceName

    /**
     * Currently connected device address (null if none) - LEGACY
     */
    @Deprecated("Use connectedDevicesList for multi-device support")
    val connectedDeviceAddress: StateFlow<String?> = BluetoothPaymentForegroundService.connectedDeviceAddress

    /**
     * First connected device (for backward compatibility with ExternalDevicesViewModel)
     * Returns the first device in the list, or null if no devices connected.
     */
    val connectedDevice: StateFlow<ConnectedDevice?> = connectedDevicesList
        .map { devices -> devices.firstOrNull() }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    // ═══════════════════════════════════════════════════════════════════════════
    // KNOWN DEVICES (Persists across APK updates - Square/Toast Pattern)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Trigger to refresh known devices list
     */
    private val _knownDevicesRefreshTrigger = MutableStateFlow(0)

    /**
     * All known devices with their current connection status
     *
     * This list PERSISTS across:
     * - App restarts
     * - APK updates
     * - Device reboots
     *
     * Devices are marked as "connected" if they're in the connectedDevicesMap.
     */
    val knownDevices: StateFlow<List<KnownDevice>> = combine(
        connectedDevicesMap,
        _knownDevicesRefreshTrigger
    ) { connectedMap, _ ->
        // Get persisted known devices
        val knownList = secureStorage.getKnownBleDevices()

        // Merge with connection status
        knownList.map { known ->
            val isCurrentlyConnected = connectedMap.containsKey(known.address)
            KnownDevice(
                address = known.address,
                name = known.name,
                isConnected = isCurrentlyConnected,
                lastConnectedAt = known.lastConnectedAt,
                connectionCount = known.connectionCount
            )
        }
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    /**
     * Refresh the known devices list from storage
     * Call this after making changes to known devices
     */
    fun refreshKnownDevices() {
        _knownDevicesRefreshTrigger.value++
    }

    /**
     * Remove a device from the known devices list AND remove system bond
     */
    fun forgetDevice(address: String) {
        // Remove from our app's storage
        secureStorage.removeKnownBleDevice(address)

        // Also remove system-level bond (so it disappears from Settings > Bluetooth)
        removeSystemBond(address)

        refreshKnownDevices()
        Timber.i("🔵 [BLE-Service] Forgot device: $address")
    }

    /**
     * Clear all known devices AND remove all system bonds
     */
    fun forgetAllDevices() {
        // Get all known devices before clearing
        val devices = secureStorage.getKnownBleDevices()

        // Remove system bonds for each device
        devices.forEach { device ->
            removeSystemBond(device.address)
        }

        secureStorage.clearKnownBleDevices()
        refreshKnownDevices()
        Timber.i("🔵 [BLE-Service] Forgot all devices")
    }

    /**
     * Remove the system-level Bluetooth bond for a device.
     *
     * This uses reflection because removeBond() is not a public API.
     * After this, the device will no longer appear in Settings > Bluetooth.
     */
    private fun removeSystemBond(address: String) {
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter ?: return

            val device = adapter.getRemoteDevice(address)
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                // removeBond() is hidden API - use reflection
                val removeBondMethod = device.javaClass.getMethod("removeBond")
                val result = removeBondMethod.invoke(device) as Boolean
                Timber.i("🔐 [BLE-Service] removeBond($address) result: $result")
            } else {
                Timber.d("🔐 [BLE-Service] Device $address not bonded, nothing to remove")
            }
        } catch (e: Exception) {
            Timber.w(e, "⚠️ [BLE-Service] Failed to remove system bond for $address")
        }
    }

    // 🔔 Payment events (global) - allows AppNavigation to start payment flow anywhere
    private val _paymentRequests = MutableSharedFlow<BlePaymentRequest>(extraBufferCapacity = 1)
    val paymentRequests: SharedFlow<BlePaymentRequest> = _paymentRequests.asSharedFlow()

    // 🔐 Pairing events (inform UI when the OS prompts for pairing)
    val pairingEvents: SharedFlow<PairingEvent> = BluetoothPaymentForegroundService.pairingEvents

    init {
        // Auto-clean old known devices to keep list manageable
        val removedCount = secureStorage.pruneKnownBleDevices(maxAgeDays = 30, maxDevices = 30)
        if (removedCount > 0) {
            Timber.i("🧹 [BLE-Service] Auto-clean removed $removedCount known devices")
        }

        // Forward payment events from Foreground Service
        scope.launch {
            BluetoothPaymentForegroundService.paymentEvents.collect { request ->
                if (request != null) {
                    Timber.i("💰 [BLE-Service] Forwarding payment event: ${request.amountCents} cents")
                    _paymentRequests.tryEmit(request)
                    BluetoothPaymentForegroundService.clearPaymentEvent()
                }
            }
        }

        // Refresh known devices when connection state changes
        scope.launch {
            connectedDevicesMap.collect { _ ->
                // Small delay to ensure SecureStorage has been updated
                kotlinx.coroutines.delay(100)
                refreshKnownDevices()
            }
        }

        // Initial load of known devices
        refreshKnownDevices()
        Timber.i("🔵 [BLE-Service] Loaded ${secureStorage.getKnownBleDevices().size} known devices from storage")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SERVER CONTROL
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Start BLE Payment Server (via Foreground Service)
     *
     * Starts a Foreground Service that:
     * - Shows persistent notification "Servidor BLE activo"
     * - Survives APK updates (START_STICKY)
     * - Restarts automatically after process death
     *
     * @param context Application context (NOT activity context)
     * @param onPaymentReceived Optional callback (payment events also available via paymentRequests SharedFlow)
     */
    fun startServer(context: Context, onPaymentReceived: ((BlePaymentRequest) -> Unit)? = null) {
        if (BluetoothPaymentForegroundService.isServerRunning()) {
            Timber.w("⚠️ [BLE-Service] Server already running")
            return
        }

        Timber.i("🔵 [BLE-Service] Starting BLE Payment Foreground Service...")

        val intent = Intent(context, BluetoothPaymentForegroundService::class.java).apply {
            action = BluetoothPaymentForegroundService.ACTION_START_SERVER
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }

        Timber.i("✅ [BLE-Service] Foreground Service start requested")
    }

    /**
     * Stop BLE Payment Server (via Foreground Service)
     */
    fun stopServer(context: Context) {
        if (!BluetoothPaymentForegroundService.isServerRunning()) {
            Timber.w("⚠️ [BLE-Service] Server not running")
            return
        }

        Timber.i("🔵 [BLE-Service] Stopping BLE Payment Foreground Service...")

        val intent = Intent(context, BluetoothPaymentForegroundService::class.java).apply {
            action = BluetoothPaymentForegroundService.ACTION_STOP_SERVER
        }
        context.startService(intent)

        Timber.i("✅ [BLE-Service] Foreground Service stop requested")
    }

    /**
     * Legacy stopServer() for backward compatibility
     * Note: Requires context, so this may not work in all cases
     */
    @Deprecated("Use stopServer(context) instead", ReplaceWith("stopServer(context)"))
    fun stopServer() {
        Timber.w("⚠️ [BLE-Service] stopServer() called without context - cannot stop foreground service")
        // Cannot stop foreground service without context
        // This is here for backward compatibility but won't actually stop the service
    }

    /**
     * Send payment result to connected client
     *
     * Note: This requires binding to the service, which adds complexity.
     * For now, this is a no-op. Payment results can be sent via other means.
     */
    fun sendPaymentResult(success: Boolean, transactionId: String?) {
        // TODO: Implement via service binding if needed
        Timber.d("🔵 [BLE-Service] sendPaymentResult called (success=$success, txId=$transactionId)")
    }

    /**
     * Try to restore BLE server state from previous session
     *
     * If server was running before app close, restart it automatically.
     * This provides seamless UX where the server persists across app restarts.
     *
     * Note: With Foreground Service, Android handles restart via START_STICKY.
     * This method is for explicit restoration when app starts fresh.
     *
     * @param context Application context
     * @param onPaymentReceived Optional callback
     * @return true if server was restored, false if it wasn't running before
     */
    fun tryRestoreState(context: Context, onPaymentReceived: ((BlePaymentRequest) -> Unit)? = null): Boolean {
        val wasRunning = secureStorage.getBleServerWasRunning()

        if (wasRunning && !BluetoothPaymentForegroundService.isServerRunning()) {
            Timber.i("🔄 [BLE-Service] Restoring BLE server from previous session...")
            try {
                startServer(context, onPaymentReceived)
                Timber.i("✅ [BLE-Service] Server restore requested")
                return true
            } catch (e: Exception) {
                Timber.e(e, "❌ [BLE-Service] Failed to restore server")
                secureStorage.saveBleServerWasRunning(false)
                return false
            }
        }

        Timber.d("🔵 [BLE-Service] No server state to restore (wasRunning=$wasRunning, isRunning=${BluetoothPaymentForegroundService.isServerRunning()})")
        return false
    }
}

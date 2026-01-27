package com.jaac.avoqado_tpv.core.bluetooth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jaac.avoqado_tpv.MainActivity
import com.jaac.avoqado_tpv.R
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject

/**
 * BluetoothPaymentForegroundService
 *
 * Android Foreground Service that runs the BLE GATT server.
 * This service persists across app updates (Square/Toast pattern).
 *
 * **Why Foreground Service?**
 * - Regular Hilt singletons die when app process dies (APK update)
 * - Foreground Services with START_STICKY are restarted by Android automatically
 * - Shows persistent notification so user knows server is running
 * - Survives app updates without losing BLE connections
 *
 * **Lifecycle:**
 * ```
 * User starts server → startForegroundService() → onStartCommand()
 *     ↓
 * Service creates notification + starts BLE GATT server
 *     ↓
 * APK Update → Android kills process
 *     ↓
 * Android restarts service (START_STICKY) → BLE server resumes
 *     ↓
 * User stops server → stopSelf() → onDestroy()
 * ```
 *
 * **Usage:**
 * ```kotlin
 * // Start service
 * val intent = Intent(context, BluetoothPaymentForegroundService::class.java)
 * intent.action = ACTION_START_SERVER
 * context.startForegroundService(intent)
 *
 * // Stop service
 * val intent = Intent(context, BluetoothPaymentForegroundService::class.java)
 * intent.action = ACTION_STOP_SERVER
 * context.startService(intent)
 * ```
 */
@AndroidEntryPoint
class BluetoothPaymentForegroundService : Service() {

    companion object {
        const val ACTION_START_SERVER = "com.jaac.avoqado_tpv.action.START_BLE_SERVER"
        const val ACTION_STOP_SERVER = "com.jaac.avoqado_tpv.action.STOP_BLE_SERVER"

        private const val NOTIFICATION_CHANNEL_ID = "ble_payment_server"
        private const val NOTIFICATION_ID = 1001

        // Android SDK stubs (34+) only expose PIN + PASSKEY_CONFIRMATION.
        // These numeric values are stable in AOSP and are used for other variants at runtime.
        private const val PAIRING_VARIANT_PASSKEY = 1
        private const val PAIRING_VARIANT_CONSENT = 3
        private const val PAIRING_VARIANT_DISPLAY_PASSKEY = 4
        private const val PAIRING_VARIANT_DISPLAY_PIN = 5

        // Static state for observing from ViewModels (survives service restarts)
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        // Multi-device support: Map of connected devices
        private val _connectedDevices = MutableStateFlow<Map<String, ConnectedDeviceInfo>>(emptyMap())
        val connectedDevices: StateFlow<Map<String, ConnectedDeviceInfo>> = _connectedDevices.asStateFlow()

        // Legacy single-device support (for backward compatibility)
        @Deprecated("Use connectedDevices instead for multi-device support")
        private val _connectedDeviceName = MutableStateFlow<String?>(null)
        @Deprecated("Use connectedDevices instead for multi-device support")
        val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

        @Deprecated("Use connectedDevices instead for multi-device support")
        private val _connectedDeviceAddress = MutableStateFlow<String?>(null)
        @Deprecated("Use connectedDevices instead for multi-device support")
        val connectedDeviceAddress: StateFlow<String?> = _connectedDeviceAddress.asStateFlow()

        // Payment events SharedFlow (for AppNavigation to observe)
        private val _paymentEvents = MutableStateFlow<BlePaymentRequest?>(null)
        val paymentEvents: StateFlow<BlePaymentRequest?> = _paymentEvents.asStateFlow()

        private val _pairingEvents = MutableSharedFlow<PairingEvent>(extraBufferCapacity = 1)
        val pairingEvents: SharedFlow<PairingEvent> = _pairingEvents.asSharedFlow()

        /**
         * Clear payment event after it's been consumed
         */
        fun clearPaymentEvent() {
            _paymentEvents.value = null
        }

        /**
         * Check if service is currently running
         */
        fun isServerRunning(): Boolean = _isRunning.value

        /**
         * Get connected device count
         */
        fun getConnectedDeviceCount(): Int = _connectedDevices.value.size
    }

    /**
     * Info about a connected device
     */
    data class ConnectedDeviceInfo(
        val address: String,
        val name: String?,
        val connectedAt: Long = System.currentTimeMillis()
    )

    @Inject
    lateinit var secureStorage: SecureStorage

    private var bleServer: BluetoothPaymentServer? = null
    private var pairingReceiver: BroadcastReceiver? = null

    /**
     * BroadcastReceiver that handles Bluetooth pairing requests.
     *
     * Since iOS doesn't send its device name over BLE (shows as "null" in system dialog),
     * we auto-accept the pairing silently. The user has already initiated the connection
     * from the iOS app, so no additional confirmation is needed.
     *
     * Security note: Real payment security comes from HTTPS to backend, not BLE encryption.
     */
    private fun createPairingReceiver(): BroadcastReceiver {
        return object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BluetoothDevice.ACTION_PAIRING_REQUEST) {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }

                    val pairingType = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                    val pairingKey = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, -1)

                    val pairingTypeLabel = pairingVariantToString(pairingType)
                    Timber.i("🔐 [BLE-Pairing] Pairing request from ${device?.address}, type=$pairingTypeLabel, key=$pairingKey")

                    _pairingEvents.tryEmit(PairingEvent(device?.address, pairingType, pairingKey))

                    // Check if this device is already in our connected devices list
                    // (meaning the user initiated connection from iOS app)
                    val isKnownConnection = device?.address?.let { addr ->
                        _connectedDevices.value.containsKey(addr)
                    } ?: false

                    if (!isKnownConnection) {
                        Timber.i("🔐 [BLE-Pairing] Device not yet in connected list (race) - still attempting auto-accept")
                    } else {
                        Timber.i("🔐 [BLE-Pairing] Known connection - attempting auto-accept")
                    }

                    val handled = handlePairingRequest(device, pairingType, pairingKey)
                    if (handled) {
                        Timber.i("🔐 [BLE-Pairing] Pairing handled programmatically - aborting broadcast")
                        abortBroadcast()
                    } else {
                        Timber.i("🔐 [BLE-Pairing] Could not auto-handle pairing - allowing system dialog")
                    }
                }
            }
        }
    }

    /**
     * Attempt to handle pairing request programmatically to avoid flaky system dialogs.
     *
     * iOS sometimes shows a passcode prompt; if we complete pairing here, the prompt stops repeating.
     */
    private fun handlePairingRequest(device: BluetoothDevice?, pairingType: Int, pairingKey: Int): Boolean {
        if (device == null) return false

        return try {
            when (pairingType) {
                BluetoothDevice.PAIRING_VARIANT_PIN,
                PAIRING_VARIANT_PASSKEY -> {
                    if (pairingKey >= 0) {
                        val pin = String.format(java.util.Locale.US, "%06d", pairingKey)
                        val setPinResult = trySetPin(device, pin)
                        Timber.i("🔐 [BLE-Pairing] setPin($pin) result: $setPinResult")
                    } else {
                        Timber.w("🔐 [BLE-Pairing] Missing pairingKey for PIN/PASSKEY variant")
                        return false
                    }
                    val confirmResult = device.setPairingConfirmation(true)
                    Timber.i("🔐 [BLE-Pairing] setPairingConfirmation(true) result: $confirmResult")
                    true
                }
                BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION,
                PAIRING_VARIANT_CONSENT -> {
                    val confirmResult = device.setPairingConfirmation(true)
                    Timber.i("🔐 [BLE-Pairing] setPairingConfirmation(true) result: $confirmResult")
                    true
                }
                PAIRING_VARIANT_DISPLAY_PASSKEY,
                PAIRING_VARIANT_DISPLAY_PIN -> {
                    // Needs user-visible UI - let system dialog handle it if shown
                    Timber.i("🔐 [BLE-Pairing] Display-only variant; letting system dialog handle it")
                    false
                }
                else -> {
                    Timber.w("🔐 [BLE-Pairing] Unknown pairing variant=$pairingType")
                    false
                }
            }
        } catch (e: SecurityException) {
            Timber.i("🔐 [BLE-Pairing] Can't auto-accept (needs BLUETOOTH_PRIVILEGED)")
            false
        } catch (e: Exception) {
            Timber.w(e, "⚠️ [BLE-Pairing] Failed to handle pairing request")
            false
        }
    }

    private fun trySetPin(device: BluetoothDevice, pin: String): Boolean {
        return try {
            val method = device.javaClass.getMethod("setPin", ByteArray::class.java)
            method.invoke(device, pin.toByteArray(Charsets.UTF_8)) as Boolean
        } catch (e: Exception) {
            Timber.w(e, "⚠️ [BLE-Pairing] Failed to set PIN via reflection")
            false
        }
    }

    private fun pairingVariantToString(variant: Int): String {
        return when (variant) {
            BluetoothDevice.PAIRING_VARIANT_PIN -> "PIN"
            PAIRING_VARIANT_PASSKEY -> "PASSKEY"
            BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION -> "PASSKEY_CONFIRMATION"
            PAIRING_VARIANT_CONSENT -> "CONSENT"
            PAIRING_VARIANT_DISPLAY_PASSKEY -> "DISPLAY_PASSKEY"
            PAIRING_VARIANT_DISPLAY_PIN -> "DISPLAY_PIN"
            else -> "UNKNOWN($variant)"
        }
    }

    private fun registerPairingReceiver() {
        if (pairingReceiver != null) return

        pairingReceiver = createPairingReceiver()
        val filter = IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST).apply {
            // High priority to intercept before system UI shows the dialog
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pairingReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(pairingReceiver, filter)
        }

        Timber.i("🔐 [BLE-Pairing] Pairing rejection receiver registered")
    }

    private fun unregisterPairingReceiver() {
        pairingReceiver?.let {
            try {
                unregisterReceiver(it)
                Timber.i("🔐 [BLE-Pairing] Pairing rejection receiver unregistered")
            } catch (e: Exception) {
                Timber.w(e, "⚠️ [BLE-Pairing] Could not unregister receiver")
            }
        }
        pairingReceiver = null
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SERVICE LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        Timber.i("🔵 [BLE-ForegroundService] onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("🔵 [BLE-ForegroundService] onStartCommand - action: ${intent?.action}")

        when (intent?.action) {
            ACTION_START_SERVER -> {
                startBleServer()
            }
            ACTION_STOP_SERVER -> {
                stopBleServer()
                stopSelf()
            }
            null -> {
                // Service restarted by Android after process death (START_STICKY)
                // Check if server was previously running and restart it
                if (secureStorage.getBleServerWasRunning()) {
                    Timber.i("🔄 [BLE-ForegroundService] Restarting BLE server after process death")
                    startBleServer()
                } else {
                    Timber.d("🔵 [BLE-ForegroundService] No previous server state to restore")
                    stopSelf()
                }
            }
        }

        // START_STICKY: Android will restart this service if killed
        return START_STICKY
    }

    override fun onDestroy() {
        Timber.i("🔵 [BLE-ForegroundService] onDestroy")
        stopBleServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder {
        return LocalBinder()
    }

    /**
     * Binder for local binding (optional - for direct communication)
     */
    inner class LocalBinder : Binder() {
        fun getService(): BluetoothPaymentForegroundService = this@BluetoothPaymentForegroundService
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BLE SERVER MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    private fun startBleServer() {
        if (bleServer != null) {
            Timber.w("⚠️ [BLE-ForegroundService] Server already running")
            return
        }

        // Start foreground with notification
        startForeground(NOTIFICATION_ID, createNotification("Servidor BLE activo", "Esperando conexiones..."))

        // Register pairing rejection receiver with HIGH priority to intercept before system UI
        registerPairingReceiver()

        // Create and start BLE server
        bleServer = BluetoothPaymentServer(this).apply {
            // Set venue info for handshake validation with iOS
            val venueId = secureStorage.getVenueId()
            val venueName = secureStorage.getVenueName()
            setVenueInfo(venueId, venueName)
            Timber.i("🔵 [BLE-ForegroundService] Venue for handshake: $venueName (${venueId?.take(10)}...)")

            start(
                onPaymentReceived = { request ->
                    Timber.i("💰 [BLE-ForegroundService] Payment received: ${request.amountCents} cents")
                    _paymentEvents.value = request
                },
                onDeviceConnected = { device ->
                    handleDeviceConnected(device)
                },
                onDeviceDisconnected = { device ->
                    handleDeviceDisconnected(device)
                },
                onClientInfoReceived = { device, info ->
                    handleClientInfo(device, info)
                }
            )
        }

        // Update state
        _isRunning.value = true
        secureStorage.saveBleServerWasRunning(true)

        Timber.i("✅ [BLE-ForegroundService] BLE server started")
    }

    private fun stopBleServer() {
        // Unregister pairing receiver
        unregisterPairingReceiver()

        bleServer?.stop()
        bleServer = null

        // Update state
        _isRunning.value = false
        _connectedDevices.value = emptyMap()
        _connectedDeviceName.value = null
        _connectedDeviceAddress.value = null
        secureStorage.saveBleServerWasRunning(false)

        Timber.i("✅ [BLE-ForegroundService] BLE server stopped")
    }

    private fun handleDeviceConnected(device: BluetoothDevice) {
        val deviceName = try {
            device.name ?: "Dispositivo desconocido"
        } catch (e: SecurityException) {
            "Dispositivo"
        }
        val deviceAddress = device.address

        // NOTE: We do NOT call createBond() proactively here because:
        // 1. The device name may not be available yet (shows "null" in pairing dialog)
        // 2. iOS will naturally trigger pairing when it enables notifications
        // 3. By that time, the device name will be properly exchanged

        // Add to connected devices map
        val updatedDevices = _connectedDevices.value.toMutableMap()
        updatedDevices[deviceAddress] = ConnectedDeviceInfo(
            address = deviceAddress,
            name = deviceName
        )
        _connectedDevices.value = updatedDevices

        // 🔵 PERSIST TO KNOWN DEVICES (Square/Toast Pattern)
        // This survives APK updates and app restarts
        secureStorage.addKnownBleDevice(deviceAddress, deviceName)

        // Legacy single-device support (uses first connected device)
        if (_connectedDeviceAddress.value == null) {
            _connectedDeviceName.value = deviceName
            _connectedDeviceAddress.value = deviceAddress
        }

        // Update notification to show connected device count
        val deviceCount = updatedDevices.size
        updateNotification(
            title = if (deviceCount == 1) "1 dispositivo conectado" else "$deviceCount dispositivos conectados",
            content = updatedDevices.values.joinToString(", ") { it.name ?: it.address }
        )

        Timber.i("✅ [BLE-ForegroundService] Device connected: $deviceName ($deviceAddress) - Total: $deviceCount")
    }

    private fun handleDeviceDisconnected(device: BluetoothDevice) {
        val deviceAddress = device.address

        // Remove from connected devices map
        val updatedDevices = _connectedDevices.value.toMutableMap()
        updatedDevices.remove(deviceAddress)
        _connectedDevices.value = updatedDevices

        // Legacy single-device support
        if (_connectedDeviceAddress.value == deviceAddress) {
            // Switch to another connected device if available
            val firstDevice = updatedDevices.values.firstOrNull()
            _connectedDeviceName.value = firstDevice?.name
            _connectedDeviceAddress.value = firstDevice?.address
        }

        // Update notification
        val deviceCount = updatedDevices.size
        if (deviceCount > 0) {
            updateNotification(
                title = if (deviceCount == 1) "1 dispositivo conectado" else "$deviceCount dispositivos conectados",
                content = updatedDevices.values.joinToString(", ") { it.name ?: it.address }
            )
        } else {
            updateNotification("Servidor BLE activo", "Esperando conexiones...")
        }

        Timber.i("🔵 [BLE-ForegroundService] Device disconnected: $deviceAddress - Remaining: $deviceCount")
    }

    private fun handleClientInfo(device: BluetoothDevice, info: BluetoothPaymentServer.ClientInfo) {
        val deviceAddress = device.address
        val resolvedName = info.deviceName?.takeIf { it.isNotBlank() }
            ?: info.deviceModel?.takeIf { it.isNotBlank() }

        if (resolvedName.isNullOrBlank()) {
            Timber.w("⚠️ [BLE-ClientInfo] Missing device name for $deviceAddress")
            return
        }

        // Update connected devices map with the new name
        val updatedDevices = _connectedDevices.value.toMutableMap()
        val existing = updatedDevices[deviceAddress]
        updatedDevices[deviceAddress] = (existing ?: ConnectedDeviceInfo(deviceAddress, resolvedName))
            .copy(name = resolvedName)
        _connectedDevices.value = updatedDevices

        // Update known devices storage without increasing connection count
        secureStorage.updateKnownBleDeviceName(deviceAddress, resolvedName)

        // Legacy single-device support
        if (_connectedDeviceAddress.value == deviceAddress) {
            _connectedDeviceName.value = resolvedName
        }

        // Refresh notification to display the friendly name
        val deviceCount = updatedDevices.size
        if (deviceCount > 0) {
            updateNotification(
                title = if (deviceCount == 1) "1 dispositivo conectado" else "$deviceCount dispositivos conectados",
                content = updatedDevices.values.joinToString(", ") { it.name ?: it.address }
            )
        }

        Timber.i("✅ [BLE-ClientInfo] Updated device name for $deviceAddress → $resolvedName")
    }

    /**
     * Send payment result back to connected device
     */
    fun sendPaymentResult(success: Boolean, transactionId: String?) {
        bleServer?.sendPaymentResult(success, transactionId)
    }

    /**
     * Try to proactively initiate bonding with "Just Works" method.
     *
     * Why? iOS may trigger pairing when enabling notifications on a characteristic.
     * By proactively bonding from Android side BEFORE iOS tries, we can:
     * 1. Complete bonding silently using "Just Works" (no PIN dialog)
     * 2. Satisfy iOS's security requirement
     * 3. Prevent iOS from showing its pairing dialog
     *
     * "Just Works" bonding happens when the peripheral declares no IO capabilities,
     * which is the default for Android GATT servers.
     */
    private fun tryProactiveBonding(device: BluetoothDevice) {
        try {
            val bondState = device.bondState
            Timber.i("🔐 [BLE-Bonding] Device ${device.address} bondState: $bondState")

            when (bondState) {
                BluetoothDevice.BOND_BONDED -> {
                    Timber.i("🔐 [BLE-Bonding] Already bonded with ${device.address}")
                }
                BluetoothDevice.BOND_BONDING -> {
                    Timber.i("🔐 [BLE-Bonding] Already bonding with ${device.address}")
                }
                BluetoothDevice.BOND_NONE -> {
                    // Not bonded - try to initiate "Just Works" bonding
                    Timber.i("🔐 [BLE-Bonding] Initiating proactive bonding with ${device.address}...")

                    // createBond() uses "Just Works" for BLE devices without IO capabilities
                    val result = device.createBond()

                    Timber.i("🔐 [BLE-Bonding] createBond() result: $result")
                }
            }
        } catch (e: SecurityException) {
            Timber.w(e, "⚠️ [BLE-Bonding] SecurityException - need BLUETOOTH_CONNECT permission")
        } catch (e: Exception) {
            Timber.w(e, "⚠️ [BLE-Bonding] Failed to initiate bonding")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NOTIFICATION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Servidor de Pagos BLE",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificación del servidor de pagos Bluetooth"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, content: String): Notification {
        // Intent to open app when notification is tapped
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Action to stop server
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BluetoothPaymentForegroundService::class.java).apply {
                action = ACTION_STOP_SERVER
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher) // Use app icon
            .setOngoing(true) // Cannot be dismissed
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Detener",
                stopIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(title, content))
    }
}

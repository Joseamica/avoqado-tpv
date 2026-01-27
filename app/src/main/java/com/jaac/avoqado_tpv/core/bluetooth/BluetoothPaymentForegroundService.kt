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
import kotlinx.coroutines.flow.StateFlow
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
     * BroadcastReceiver that automatically ACCEPTS Bluetooth pairing requests.
     * This prevents the pairing dialogs from appearing while keeping the connection stable.
     *
     * Why accept instead of reject?
     * - Rejecting pairing causes iOS to disconnect (treats it as connection failure)
     * - Accepting silently completes the pairing without user interaction
     * - Our BLE communication works with or without bonding, but iOS prefers bonded connections
     *
     * Security note: Payment data security is handled via HTTPS to the backend,
     * not via BLE encryption.
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

                    Timber.i("🔐 [BLE-Pairing] Pairing request received from ${device?.address}, type=$pairingType, key=$pairingKey - AUTO-ACCEPTING")

                    // Abort the broadcast to prevent system dialog from showing
                    abortBroadcast()

                    // Auto-accept the pairing
                    try {
                        device?.let {
                            // For "Just Works" and numeric comparison (type 0, 2)
                            // Call setPairingConfirmation(true) to accept
                            val result = it.setPairingConfirmation(true)
                            Timber.i("🔐 [BLE-Pairing] setPairingConfirmation(true) result: $result for ${it.address}")
                        }
                    } catch (e: SecurityException) {
                        Timber.w(e, "⚠️ [BLE-Pairing] SecurityException - need BLUETOOTH_PRIVILEGED permission")
                        // On non-rooted devices, we can't auto-accept without BLUETOOTH_PRIVILEGED
                        // The dialog will still appear, but at least we tried
                    } catch (e: Exception) {
                        Timber.w(e, "⚠️ [BLE-Pairing] Could not auto-accept pairing")
                    }
                }
            }
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

    /**
     * Send payment result back to connected device
     */
    fun sendPaymentResult(success: Boolean, transactionId: String?) {
        bleServer?.sendPaymentResult(success, transactionId)
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

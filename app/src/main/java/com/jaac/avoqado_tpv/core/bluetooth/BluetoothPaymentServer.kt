package com.jaac.avoqado_tpv.core.bluetooth

import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import timber.log.Timber
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * BluetoothPaymentServer
 *
 * BLE GATT Server that allows external devices (iPad, tablet) to initiate payments
 * by sending payment amount via Bluetooth Low Energy.
 *
 * **Architecture**:
 * ```
 * iPad/Tablet (Central)  ←→  PAX Terminal (Peripheral)
 *      ↓                          ↓
 *   Scan devices              Advertise service
 *      ↓                          ↓
 *   Connect to PAX            Accept connection
 *      ↓                          ↓
 *   Write amount           Receive & process payment
 *      ↓                          ↓
 *   Read result              Send result notification
 * ```
 *
 * **Based on Stripe Terminal SDK pattern**:
 * - Service UUID: Unique identifier for payment service
 * - Characteristic UUID: Channel for amount/result data
 * - Notifications: Push payment results to client
 *
 * **Usage**:
 * ```kotlin
 * val server = BluetoothPaymentServer(context)
 * server.start { amount ->
 *     // Process payment with amount
 *     paymentViewModel.processPayment(amount)
 * }
 * ```
 */
class BluetoothPaymentServer(private val context: Context) {

    companion object {
        // Service UUID - Identifies our payment service
        // Generate with: uuidgen (macOS) or UUID.randomUUID() (Java)
        private val SERVICE_UUID = UUID.fromString("00001234-0000-1000-8000-00805f9b34fb")

        // Characteristic UUID - Channel for payment data
        private val PAYMENT_CHARACTERISTIC_UUID = UUID.fromString("00001235-0000-1000-8000-00805f9b34fb")

        // Descriptor UUID - Standard Client Characteristic Configuration Descriptor
        private val CLIENT_CONFIG_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager: BluetoothManager? by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager?.adapter
    }

    private var gattServer: BluetoothGattServer? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var paymentCallback: ((BlePaymentRequest) -> Unit)? = null
    private var deviceConnectedCallback: ((BluetoothDevice) -> Unit)? = null
    private var deviceDisconnectedCallback: ((BluetoothDevice) -> Unit)? = null
    private var clientInfoCallback: ((BluetoothDevice, ClientInfo) -> Unit)? = null
    private var paymentCharacteristic: BluetoothGattCharacteristic? = null

    // Multi-device support: Map of connected devices by address
    private val connectedDevices = mutableMapOf<String, BluetoothDevice>()

    // Venue info for handshake - set by the service/viewModel
    private var venueId: String? = null
    private var venueName: String? = null

    data class ClientInfo(
        val deviceName: String?,
        val deviceModel: String?,
        val systemVersion: String?
    )

    /**
     * Set the venue info that will be sent in the handshake to connecting devices.
     * This should be called when the server starts or when the user logs in.
     */
    fun setVenueInfo(venueId: String?, venueName: String?) {
        this.venueId = venueId
        this.venueName = venueName
        Timber.i("🔵 [BLE-Server] Venue set for handshake: ${venueName ?: "unknown"} (${venueId?.take(10)}...)")
    }

    /**
     * Start BLE payment server
     *
     * @param onPaymentReceived Callback when payment amount is received from client
     * @param onDeviceConnected Callback when device connects
     * @param onDeviceDisconnected Callback when device disconnects
     */
    fun start(
        onPaymentReceived: (BlePaymentRequest) -> Unit,
        onDeviceConnected: (BluetoothDevice) -> Unit = {},
        onDeviceDisconnected: (BluetoothDevice) -> Unit = {},
        onClientInfoReceived: (BluetoothDevice, ClientInfo) -> Unit = { _, _ -> }
    ) {
        paymentCallback = onPaymentReceived
        deviceConnectedCallback = onDeviceConnected
        deviceDisconnectedCallback = onDeviceDisconnected
        clientInfoCallback = onClientInfoReceived

        Timber.i("🔵 [BLE-Server] Starting Bluetooth Payment Server...")

        if (bluetoothAdapter == null) {
            Timber.e("❌ [BLE-Server] Bluetooth adapter not available")
            return
        }

        if (!bluetoothAdapter!!.isEnabled) {
            Timber.e("❌ [BLE-Server] Bluetooth is disabled")
            return
        }

        // Set device name FIRST before any BLE operations
        // This ensures the name is set before pairing dialogs appear
        setDeviceName()

        // Create GATT Server - advertising will start automatically when service is added
        createGattServer()
    }

    /**
     * Set the Bluetooth adapter name to "Avoqado-TPV"
     * This is called early to ensure the name appears in pairing dialogs
     */
    private fun setDeviceName() {
        try {
            val currentName = bluetoothAdapter?.name
            Timber.i("📱 [BLE-Server] Current Bluetooth adapter name: '$currentName'")

            if (currentName != "Avoqado-TPV") {
                val result = bluetoothAdapter?.setName("Avoqado-TPV")
                Timber.i("📱 [BLE-Server] setName('Avoqado-TPV') result: $result")

                // Verify the name was set
                val newName = bluetoothAdapter?.name
                Timber.i("📱 [BLE-Server] Bluetooth adapter name after set: '$newName'")
            } else {
                Timber.i("📱 [BLE-Server] Name already set to 'Avoqado-TPV'")
            }
        } catch (e: SecurityException) {
            Timber.e(e, "❌ [BLE-Server] SecurityException setting device name - need BLUETOOTH_CONNECT permission")
        } catch (e: Exception) {
            Timber.e(e, "❌ [BLE-Server] Failed to set device name")
        }
    }

    /**
     * Stop BLE payment server
     */
    fun stop() {
        Timber.i("🔵 [BLE-Server] Stopping Bluetooth Payment Server...")

        stopAdvertising()
        gattServer?.close()
        gattServer = null
        connectedDevices.clear()

        Timber.i("✅ [BLE-Server] Server stopped")
    }

    /**
     * Get all currently connected devices
     */
    fun getConnectedDevices(): List<BluetoothDevice> = connectedDevices.values.toList()

    /**
     * Get connected device count
     */
    fun getConnectedDeviceCount(): Int = connectedDevices.size

    /**
     * Send handshake to a specific device with venue info for validation
     * Called when a device enables notifications
     */
    private fun sendHandshake(device: BluetoothDevice) {
        val currentVenueId = venueId
        if (currentVenueId == null) {
            Timber.w("⚠️ [BLE-Server] Cannot send handshake - venueId not set")
            return
        }

        if (paymentCharacteristic == null) {
            Timber.w("⚠️ [BLE-Server] Cannot send handshake - characteristic not available")
            return
        }

        // Include venueName for user-friendly error messages on iOS
        val currentVenueName = venueName ?: "Desconocido"
        val handshake = """{"type":"HANDSHAKE","venueId":"$currentVenueId","venueName":"$currentVenueName"}"""
        Timber.i("🤝 [BLE-Server] Sending handshake to ${device.address}: $handshake")

        paymentCharacteristic!!.value = handshake.toByteArray(StandardCharsets.UTF_8)

        try {
            gattServer?.notifyCharacteristicChanged(
                device,
                paymentCharacteristic,
                false
            )
            Timber.i("✅ [BLE-Server] Handshake sent successfully")
        } catch (e: Exception) {
            Timber.e(e, "❌ [BLE-Server] Failed to send handshake")
        }
    }

    /**
     * Send payment result back to a specific client or all clients
     *
     * @param success Whether payment was successful
     * @param transactionId Transaction ID if successful
     * @param targetDeviceAddress Optional: specific device to send to. If null, sends to all.
     */
    fun sendPaymentResult(success: Boolean, transactionId: String?, targetDeviceAddress: String? = null) {
        if (connectedDevices.isEmpty()) {
            Timber.w("⚠️ [BLE-Server] No connected devices to send result")
            return
        }

        if (paymentCharacteristic == null) {
            Timber.w("⚠️ [BLE-Server] Payment characteristic not available")
            return
        }

        val result = if (success) {
            """{"status":"success","transactionId":"$transactionId"}"""
        } else {
            """{"status":"error"}"""
        }

        paymentCharacteristic!!.value = result.toByteArray(StandardCharsets.UTF_8)

        // Determine which devices to notify
        val devicesToNotify = if (targetDeviceAddress != null) {
            connectedDevices[targetDeviceAddress]?.let { listOf(it) } ?: emptyList()
        } else {
            connectedDevices.values.toList()
        }

        devicesToNotify.forEach { device ->
            try {
                gattServer?.notifyCharacteristicChanged(
                    device,
                    paymentCharacteristic,
                    false
                )
                Timber.i("✅ [BLE-Server] Payment result sent to ${device.address}: $result")
            } catch (e: Exception) {
                Timber.e(e, "❌ [BLE-Server] Failed to send payment result to ${device.address}")
            }
        }
    }

    /**
     * Create GATT Server with payment service
     */
    private fun createGattServer() {
        // Log current adapter name before attempting to change it
        val nameBefore = try {
            bluetoothAdapter?.name
        } catch (e: SecurityException) {
            "unknown (no permission)"
        }
        Timber.i("📱 [BLE-Server] Current adapter name BEFORE: '$nameBefore'")

        // Set device name BEFORE opening GATT server (fixes "null" in pairing dialog)
        try {
            val setResult = bluetoothAdapter?.setName("Avoqado-TPV")
            val nameAfter = bluetoothAdapter?.name
            Timber.i("📱 [BLE-Server] setName() result: $setResult, name AFTER: '$nameAfter'")
        } catch (e: SecurityException) {
            Timber.e(e, "❌ [BLE-Server] Failed to set device name - BLUETOOTH_CONNECT permission required")
        } catch (e: Exception) {
            Timber.e(e, "❌ [BLE-Server] Failed to set device name: ${e.message}")
        }

        gattServer = bluetoothManager?.openGattServer(context, gattServerCallback)

        if (gattServer == null) {
            Timber.e("❌ [BLE-Server] Failed to create GATT server")
            return
        }

        // Create payment service
        val service = BluetoothGattService(
            SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // Create payment characteristic (read, write, notify)
        paymentCharacteristic = BluetoothGattCharacteristic(
            PAYMENT_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or
                    BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        // Add Client Configuration Descriptor for notifications
        val configDescriptor = BluetoothGattDescriptor(
            CLIENT_CONFIG_DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        paymentCharacteristic!!.addDescriptor(configDescriptor)

        service.addCharacteristic(paymentCharacteristic)

        Timber.i("🔵 [BLE-Server] Adding payment service to GATT server...")
        gattServer!!.addService(service)
        // Service addition is async - will start advertising in onServiceAdded() callback
    }

    /**
     * Start BLE advertising to announce presence
     *
     * Uses AdvertisingSet API on Android 8+ (API 26) with legacy mode to try to get
     * a more stable BLE address. Falls back to legacy API on older devices.
     */
    private fun startAdvertising() {
        bleAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser

        if (bleAdvertiser == null) {
            Timber.e("❌ [BLE-Server] BLE Advertiser not available")
            return
        }

        // Log current adapter name and address for debugging
        val currentName = try {
            bluetoothAdapter?.name
        } catch (e: SecurityException) {
            "unknown (no permission)"
        }
        val currentAddress = try {
            bluetoothAdapter?.address
        } catch (e: SecurityException) {
            "unknown"
        }
        Timber.i("📱 [BLE-Server] Starting advertising. Name: $currentName, Address: $currentAddress")

        // Main advertise data with service UUID
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        // Scan response with device name
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        // Use new AdvertisingSet API on Android 8+ for better address control
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startAdvertisingWithNewApi(advertiseData, scanResponse)
        } else {
            startAdvertisingWithLegacyApi(advertiseData, scanResponse)
        }
    }

    /**
     * Start advertising using the newer AdvertisingSet API (Android 8+)
     * Uses legacy mode which tends to use a more stable BLE address
     */
    private fun startAdvertisingWithNewApi(advertiseData: AdvertiseData, scanResponse: AdvertiseData) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val parameters = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)  // Legacy mode uses more stable address
            .setConnectable(true)
            .setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)  // ~100ms
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .build()

        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(
                advertisingSet: AdvertisingSet?,
                txPower: Int,
                status: Int
            ) {
                if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                    Timber.i("✅ [BLE-Server] AdvertisingSet started successfully (txPower=$txPower)")
                } else {
                    Timber.e("❌ [BLE-Server] AdvertisingSet failed to start, status=$status, falling back to legacy")
                    // Fallback to legacy API
                    startAdvertisingWithLegacyApi(advertiseData, scanResponse)
                }
            }

            override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
                Timber.i("🔵 [BLE-Server] AdvertisingSet stopped")
            }
        }

        try {
            bleAdvertiser?.startAdvertisingSet(
                parameters,
                advertiseData,
                scanResponse,
                null,  // No periodic advertising
                null,  // No periodic advertising
                callback
            )
            Timber.i("✅ [BLE-Server] BLE advertising started with new API (legacy mode)")
        } catch (e: Exception) {
            Timber.e(e, "❌ [BLE-Server] Failed to start AdvertisingSet, falling back to legacy")
            startAdvertisingWithLegacyApi(advertiseData, scanResponse)
        }
    }

    /**
     * Start advertising using the legacy API (Android 5+)
     */
    private fun startAdvertisingWithLegacyApi(advertiseData: AdvertiseData, scanResponse: AdvertiseData) {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        bleAdvertiser?.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
        Timber.i("✅ [BLE-Server] BLE advertising started with legacy API")
    }

    /**
     * Stop BLE advertising
     */
    private fun stopAdvertising() {
        bleAdvertiser?.stopAdvertising(advertiseCallback)
        bleAdvertiser = null
        Timber.i("🔵 [BLE-Server] BLE advertising stopped")
    }

    /**
     * GATT Server Callback - Handles client connections and data writes
     */
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)

            Timber.d("🔵 [BLE-Server] onConnectionStateChange - status=$status, newState=$newState, device=${device?.address}")

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    device?.let {
                        connectedDevices[it.address] = it
                        Timber.i("✅ [BLE-Server] Client connected: ${it.address} (total: ${connectedDevices.size})")

                        // NOTE: We intentionally do NOT call createBond() here.
                        // Reason: iOS cannot programmatically "forget" a bonded device - users would
                        // need to go to Settings > Bluetooth to unpair. By skipping bonding,
                        // the "Olvidar terminal" button in the iOS app works as expected.
                        // Security is handled via HTTPS to the backend, not BLE pairing.
                        val bondState = try {
                            when (it.bondState) {
                                BluetoothDevice.BOND_BONDED -> "bonded"
                                BluetoothDevice.BOND_BONDING -> "bonding"
                                else -> "none"
                            }
                        } catch (e: SecurityException) {
                            "unknown"
                        }
                        Timber.i("🔐 [BLE-Server] Device bond state: $bondState (not initiating bonding)")

                        deviceConnectedCallback?.invoke(it)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    device?.let {
                        connectedDevices.remove(it.address)
                        Timber.i("🔵 [BLE-Server] Client disconnected: ${it.address}, status=$status (remaining: ${connectedDevices.size})")
                        deviceDisconnectedCallback?.invoke(it)
                    }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            super.onMtuChanged(device, mtu)
            Timber.i("📡 [BLE-Server] MTU changed to $mtu for device ${device?.address}")
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onCharacteristicWriteRequest(
                device, requestId, characteristic, preparedWrite, responseNeeded, offset, value
            )

            if (characteristic?.uuid == PAYMENT_CHARACTERISTIC_UUID) {
                val data = value?.toString(StandardCharsets.UTF_8) ?: ""
                Timber.i("📥 [BLE-Server] Received payment data: $data")

                val messageType = parseString(data, "type")
                if (messageType != null) {
                    if (messageType == "CLIENT_INFO") {
                        val deviceName = parseString(data, "deviceName")
                        val deviceModel = parseString(data, "deviceModel")
                        val systemVersion = parseString(data, "systemVersion")

                        Timber.i("📛 [BLE-Server] Client info from ${device?.address}: name=$deviceName model=$deviceModel")

                        if (device != null) {
                            clientInfoCallback?.invoke(
                                device,
                                ClientInfo(
                                    deviceName = deviceName,
                                    deviceModel = deviceModel,
                                    systemVersion = systemVersion
                                )
                            )
                        }

                        if (responseNeeded) {
                            gattServer?.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_SUCCESS,
                                offset,
                                value
                            )
                        }
                        return
                    } else {
                        Timber.w("⚠️ [BLE-Server] Unknown message type: $messageType")
                        if (responseNeeded) {
                            gattServer?.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_SUCCESS,
                                offset,
                                value
                            )
                        }
                        return
                    }
                }

                // Parse request from JSON: {"amount":1500,"tip":200,"rating":5,"skipReview":true}
                try {
                    val request = parsePaymentRequest(data)
                    Timber.i("💰 [BLE-Server] Payment amount: ${request.amountCents} cents")

                    // Trigger payment callback
                    paymentCallback?.invoke(request)

                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            offset,
                            value
                        )
                    }
                } catch (e: Exception) {
                    Timber.e(e, "❌ [BLE-Server] Failed to parse payment amount")
                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_FAILURE,
                            offset,
                            null
                        )
                    }
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)

            Timber.d("📤 [BLE-Server] onCharacteristicReadRequest - UUID: ${characteristic?.uuid}, offset=$offset")

            if (characteristic?.uuid == PAYMENT_CHARACTERISTIC_UUID) {
                Timber.i("📤 [BLE-Server] Client reading payment characteristic")
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    characteristic.value
                )
            } else {
                // Send response for unknown characteristics too
                Timber.w("⚠️ [BLE-Server] Unknown characteristic read: ${characteristic?.uuid}")
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    byteArrayOf()
                )
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onDescriptorWriteRequest(
                device, requestId, descriptor, preparedWrite, responseNeeded, offset, value
            )

            Timber.d("🔔 [BLE-Server] onDescriptorWriteRequest - UUID: ${descriptor?.uuid}, responseNeeded=$responseNeeded")

            if (descriptor?.uuid == CLIENT_CONFIG_DESCRIPTOR_UUID) {
                Timber.i("🔔 [BLE-Server] Client configured notifications")
                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        value
                    )
                }

                // Send handshake with venueId when client enables notifications
                device?.let { sendHandshake(it) }
            } else {
                // Respond to unknown descriptors too
                if (responseNeeded) {
                    Timber.w("⚠️ [BLE-Server] Unknown descriptor write: ${descriptor?.uuid}")
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        value
                    )
                }
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor?
        ) {
            super.onDescriptorReadRequest(device, requestId, offset, descriptor)

            Timber.d("📖 [BLE-Server] onDescriptorReadRequest - UUID: ${descriptor?.uuid}, offset=$offset")

            // Always send a response
            gattServer?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                descriptor?.value ?: byteArrayOf()
            )
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            super.onServiceAdded(status, service)

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Timber.i("✅ [BLE-Server] Service added successfully - UUID: ${service?.uuid}")
                // Now that service is added, start advertising
                startAdvertising()
            } else {
                Timber.e("❌ [BLE-Server] Failed to add service - status: $status")
            }
        }
    }

    /**
     * Advertise Callback - Handles advertising start/failure
     */
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            Timber.i("✅ [BLE-Server] Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Timber.e("❌ [BLE-Server] Advertising failed with error: $errorCode")
        }
    }

    /**
     * Parse amount from JSON payload
     *
     * Dual-mode payment support:
     * - Quick Payment: {"amount": 1500, "tip": 200, "rating": 5}
     * - Order Payment: {"orderId": "xyz", "amount": 1500, "tip": 200, "rating": 5}
     */
    private fun parsePaymentRequest(json: String): BlePaymentRequest {
        // Simple JSON parsing (can use Gson/Moshi for production)
        val amount = parseLong(json, "amount")
            ?: throw IllegalArgumentException("Invalid payment data format (amount required)")

        val tip = parseLong(json, "tip") ?: parseLong(json, "tipAmount")
        val ratingRaw = parseInt(json, "rating")
        val rating = ratingRaw?.takeIf { it in 1..5 }
        if (ratingRaw != null && rating == null) {
            Timber.w("⚠️ [BLE-Server] Ignoring invalid rating: $ratingRaw")
        }
        val skipReview = parseBoolean(json, "skipReview") ?: false

        // Dual-mode: Parse orderId if present (null = FastPayment, set = OrderPayment)
        val orderId = parseString(json, "orderId")
        if (orderId != null) {
            Timber.i("📦 [BLE-Server] ORDER PAYMENT detected | orderId=$orderId")
        } else {
            Timber.i("💨 [BLE-Server] QUICK PAYMENT mode (no orderId)")
        }

        return BlePaymentRequest(
            amountCents = amount,
            tipCents = tip,
            rating = rating,
            skipReview = skipReview,
            orderId = orderId
        )
    }

    private fun parseLong(json: String, key: String): Long? {
        val match = Regex(""""$key"\s*:\s*(\d+)""").find(json)
        return match?.groupValues?.get(1)?.toLong()
    }

    private fun parseInt(json: String, key: String): Int? {
        val match = Regex(""""$key"\s*:\s*(\d+)""").find(json)
        return match?.groupValues?.get(1)?.toInt()
    }

    private fun parseBoolean(json: String, key: String): Boolean? {
        val match = Regex(""""$key"\s*:\s*(true|false)""", RegexOption.IGNORE_CASE).find(json)
        return match?.groupValues?.get(1)?.toBoolean()
    }

    private fun parseString(json: String, key: String): String? {
        val match = Regex(""""$key"\s*:\s*"([^"]+)"""").find(json)
        return match?.groupValues?.get(1)
    }
}

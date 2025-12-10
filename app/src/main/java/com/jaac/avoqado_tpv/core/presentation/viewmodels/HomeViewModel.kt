package com.jaac.avoqado_tpv.core.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.manager.LockScreenManager
import com.jaac.avoqado_tpv.core.data.manager.MaintenanceManager
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.core.domain.TerminalConfig
import com.jaac.avoqado_tpv.core.domain.events.VenueStatusEvent
import com.jaac.avoqado_tpv.features.authentication.domain.models.VenueStatus
import com.jaac.avoqado_tpv.core.util.HeartbeatScheduler
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.payment.data.InitializationManager
import com.jaac.avoqado_tpv.features.payment.domain.use_case.GetMerchantsUseCase
import com.jaac.avoqado_tpv.features.remote_command.data.model.TpvCommand
import com.jaac.avoqado_tpv.features.remote_command.data.model.TpvCommandPriority
import com.jaac.avoqado_tpv.features.remote_command.data.model.TpvCommandType
import com.jaac.avoqado_tpv.features.remote_command.domain.CommandExecutor
import com.jaac.avoqado_tpv.core.data.repository.HeartbeatRepository
import com.jaac.avoqado_tpv.core.domain.models.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject

/**
 * HomeViewModel
 *
 * Handles home screen actions including logout.
 * Also handles Socket.IO events for system alerts, admin commands, and hardware status.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val socketManager: SocketManager,
    private val secureStorage: SecureStorage,
    // 🔧 Blumon SDK Initialization - Triggered after login for early payment readiness
    private val initializationManager: InitializationManager,
    // 🏪 Get merchants from backend to use correct serial for SDK init
    private val getMerchantsUseCase: GetMerchantsUseCase,
    // 🎮 Remote Command System
    private val commandExecutor: CommandExecutor,
    // 📡 HTTP ACK for commands received via Socket.IO
    private val heartbeatRepository: HeartbeatRepository,
    val lockScreenManager: LockScreenManager,
    val maintenanceManager: MaintenanceManager
) : ViewModel() {

    // ═══════════════════════════════════════════════════════════════════════════
    // USER PROFILE STATE
    // ═══════════════════════════════════════════════════════════════════════════

    // Staff name for personalized greeting
    private val _staffName = MutableStateFlow("Usuario")
    val staffName: StateFlow<String> = _staffName.asStateFlow()

    // Clock-in time (placeholder for future clock-in feature)
    private val _clockInTime = MutableStateFlow<String?>(null)
    val clockInTime: StateFlow<String?> = _clockInTime.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════════════
    // SYSTEM ALERTS & ADMIN COMMANDS
    // ═══════════════════════════════════════════════════════════════════════════

    // System alerts for UI (info, warning, error, critical)
    private val _systemAlerts = MutableSharedFlow<SocketEvent.SystemAlert>(replay = 0, extraBufferCapacity = 10)
    val systemAlerts: SharedFlow<SocketEvent.SystemAlert> = _systemAlerts.asSharedFlow()

    // Admin commands from dashboard (maintenance mode, reload, disable, shutdown)
    private val _adminCommands = MutableSharedFlow<SocketEvent.TPVCommand>(replay = 0, extraBufferCapacity = 5)
    val adminCommands: SharedFlow<SocketEvent.TPVCommand> = _adminCommands.asSharedFlow()

    // Inventory alerts (low stock, out of stock)
    private val _inventoryAlerts = MutableSharedFlow<SocketEvent>(replay = 0, extraBufferCapacity = 10)
    val inventoryAlerts: SharedFlow<SocketEvent> = _inventoryAlerts.asSharedFlow()

    // Hardware status updates (printer, card reader)
    private val _hardwareStatus = MutableSharedFlow<SocketEvent>(replay = 0, extraBufferCapacity = 10)
    val hardwareStatus: SharedFlow<SocketEvent> = _hardwareStatus.asSharedFlow()

    // ═══════════════════════════════════════════════════════════════════════════
    // VENUE STATUS (for mid-session detection)
    // ═══════════════════════════════════════════════════════════════════════════

    // Current venue status from SecureStorage
    private val _venueStatus = MutableStateFlow(secureStorage.getVenueStatus())
    val venueStatus: StateFlow<VenueStatus> = _venueStatus.asStateFlow()

    // Venue status change events (for UI reactions like forced logout)
    private val _venueStatusEvents = MutableSharedFlow<VenueStatusEvent>(replay = 0, extraBufferCapacity = 5)
    val venueStatusEvents: SharedFlow<VenueStatusEvent> = _venueStatusEvents.asSharedFlow()

    init {
        loadStaffInfo()
        // 🔌 Connect Socket.IO if session was restored (app restart)
        connectSocketIfNeeded()
        collectSocketEvents()
        // 🔧 Initialize Blumon SDK in background (so it's ready when user opens payment)
        initializeBlumonSDK()
    }

    /**
     * 👤 Load Staff Information
     *
     * Loads the current logged-in staff member's name for personalized greeting.
     * Clock-in time is a placeholder for future implementation.
     */
    private fun loadStaffInfo() {
        viewModelScope.launch {
            val name = authRepository.getStaffName() ?: "Usuario"
            _staffName.value = name
            Timber.d("👤 [HomeViewModel] Staff name loaded: $name")

            // Clock-in time - placeholder for now
            // TODO: Implement clock-in feature and load actual clock-in time
            _clockInTime.value = null
        }
    }

    /**
     * 🔌 Connect Socket.IO if session was restored
     *
     * When the app restarts with a valid session, LoginViewModel is bypassed,
     * so we need to connect to Socket.IO from HomeViewModel.
     *
     * This ensures:
     * - Real-time events work after app restart
     * - TPV commands are received from dashboard
     * - System alerts are delivered
     *
     * Pattern: Check if already connected, if not, connect using stored credentials.
     */
    private fun connectSocketIfNeeded() {
        viewModelScope.launch {
            try {
                // Check if socket is already connected (e.g., from LoginViewModel)
                if (socketManager.isConnected()) {
                    Timber.d("🔌 [Socket.IO] Already connected - skipping")
                    return@launch
                }

                // Get stored credentials
                val jwtToken = secureStorage.getToken()
                val venueId = secureStorage.getVenueId()

                if (jwtToken == null || venueId == null) {
                    Timber.w("⚠️ [Socket.IO] No credentials found - cannot connect")
                    return@launch
                }

                // Use BLUMON_ENV to determine URL (matches NetworkModule logic)
                val socketUrl = if (BuildConfig.BLUMON_ENV == "PROD") {
                    BuildConfig.SOCKET_URL  // Production: https://api.avoqado.io
                } else {
                    BuildConfig.SOCKET_URL_DEV  // Sandbox: ngrok URL
                }

                Timber.d("🔌 [Socket.IO] Connecting on session restore...")
                Timber.d("🔌 [Socket.IO] URL: $socketUrl")
                Timber.d("🔌 [Socket.IO] Venue ID: $venueId")

                // Connect with JWT authentication
                socketManager.connect(
                    url = socketUrl,
                    token = jwtToken,
                    reconnection = true,
                    reconnectionAttempts = 5
                )

                // Wait for connection and join venue room
                viewModelScope.launch {
                    socketManager.isConnected.collect { connected ->
                        if (connected) {
                            Timber.i("✅ [Socket.IO] Connected on session restore")
                            socketManager.joinVenueRoom(venueId)
                            Timber.i("✅ [Socket.IO] Joined venue room: $venueId")
                        }
                    }
                }

            } catch (e: Exception) {
                Timber.e(e, "❌ [Socket.IO] Connection failed on session restore")
                // Don't block home screen on socket failure - app can work without real-time events
            }
        }
    }

    /**
     * 🔧 Initialize Blumon SDK after successful login
     *
     * Starts SDK initialization in background so it's ready when user opens payment screen.
     * Uses 3 second delay to let other operations (Socket.IO, ShiftRepository) settle first.
     *
     * **Why in HomeViewModel (not LoginViewModel)?**
     * - LoginViewModel gets destroyed when navigating to HomeScreen
     * - HomeViewModel persists throughout the logged-in session
     * - No risk of coroutine cancellation due to navigation
     *
     * **Flow:**
     * 1. Wait 3 seconds for other operations to settle
     * 2. Fetch merchants from backend (to get real serial numbers)
     * 3. Use first merchant's serial for TerminalConfig
     * 4. Initialize SDK with correct serial
     *
     * Benefits:
     * - SDK ready before user opens payment (no loading delay)
     * - OAuth + DUKPT keys downloaded in advance
     * - Uses real merchant serial (not hardcoded default)
     * - If initialization fails, payment screen will retry (graceful fallback)
     */
    private fun initializeBlumonSDK() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // ⏳ Wait 3 seconds for other operations to settle
                // (Socket.IO, HeartbeatScheduler, ShiftRepository all start on login)
                // This prevents resource contention that causes GenericFailure
                delay(3000)

                Timber.i("🔧 [Blumon] Starting SDK initialization after login...")

                // Step 1: Fetch merchants from backend to get real serial numbers
                val merchants = getMerchantsUseCase().firstOrNull()
                if (merchants.isNullOrEmpty()) {
                    Timber.w("⚠️ [Blumon] No merchants found - SDK init will use default serial")
                } else {
                    // Step 2: Use first merchant's serial for TerminalConfig
                    val defaultMerchant = merchants.first()
                    Timber.i("🏪 [Blumon] Using merchant for SDK init: ${defaultMerchant.displayName} (${defaultMerchant.serialNumber})")
                    TerminalConfig.updateSerial(defaultMerchant.serialNumber)
                }

                // Step 3: Initialize SDK with correct serial
                initializationManager.ensureInitialized()
                    .onSuccess {
                        Timber.i("✅ [Blumon] SDK initialized successfully - ready for payments")
                    }
                    .onFailure { error ->
                        Timber.w(error, "⚠️ [Blumon] SDK initialization failed - will retry when opening payment")
                        // Don't block home screen - payment screen will retry if needed
                    }

            } catch (e: Exception) {
                Timber.e(e, "❌ [Blumon] Unexpected error during SDK initialization")
                // Don't block home screen on SDK failure - app can work, payment will retry
            }
        }
    }

    /**
     * 🔌 Collect Socket.IO Real-time Events
     *
     * Listen to system-wide events:
     * - SystemAlert: Critical alerts from server (info, warning, error, critical)
     * - TPVCommand: Admin commands from dashboard (maintenance mode, reload, disable, shutdown, restart)
     * - InventoryLowStock/OutOfStock: Stock alerts for venue
     * - PrinterStatus/CardReaderStatus/PeripheralError: Hardware status updates
     *
     * Pattern: Centralized event handling for global app state
     */
    private fun collectSocketEvents() {
        viewModelScope.launch {
            socketManager.events.collect { event ->
                when (event) {
                    // ═══════════════════════════════════════════════════════════
                    // SYSTEM ALERTS
                    // ═══════════════════════════════════════════════════════════
                    is SocketEvent.SystemAlert -> {
                        Timber.i("🚨 [Socket] System Alert [${event.level}]: ${event.title} - ${event.message}")
                        _systemAlerts.tryEmit(event)

                        // Log based on severity
                        when (event.level) {
                            "critical" -> Timber.e("🔴 [CRITICAL] ${event.title}: ${event.message}")
                            "error" -> Timber.e("❌ [ERROR] ${event.title}: ${event.message}")
                            "warning" -> Timber.w("⚠️ [WARNING] ${event.title}: ${event.message}")
                            "info" -> Timber.i("ℹ️ [INFO] ${event.title}: ${event.message}")
                        }
                    }

                    // ═══════════════════════════════════════════════════════════
                    // TPV ADMIN COMMANDS (from dashboard)
                    // ═══════════════════════════════════════════════════════════
                    is SocketEvent.TPVCommand -> {
                        Timber.w("⚙️ [Socket] TPV Command received: ${event.commandType} (requested by: ${event.requestedBy})")
                        _adminCommands.tryEmit(event)

                        // Execute command via CommandExecutor
                        executeRemoteCommand(event)
                    }

                    // TPV COMMAND CANCELLED (command cancelled before execution)
                    is SocketEvent.TPVCommandCancelled -> {
                        Timber.w("🚫 [Socket] Command cancelled: ${event.commandId} by ${event.cancelledBy}")
                        // Command was cancelled - no action needed, just log
                    }

                    // ═══════════════════════════════════════════════════════════
                    // INVENTORY ALERTS
                    // ═══════════════════════════════════════════════════════════
                    is SocketEvent.InventoryLowStock -> {
                        Timber.w("📦 [Socket] Low stock alert: ${event.rawMaterialName} (${event.currentStock} ${event.unit})")
                        _inventoryAlerts.tryEmit(event)
                    }

                    is SocketEvent.InventoryOutOfStock -> {
                        Timber.e("🚫 [Socket] OUT OF STOCK: ${event.rawMaterialName}")
                        _inventoryAlerts.tryEmit(event)
                    }

                    is SocketEvent.InventoryUpdated -> {
                        Timber.d("📦 [Socket] Inventory updated: ${event.rawMaterialName} (${event.currentStock} ${event.unit})")
                        _inventoryAlerts.tryEmit(event)
                    }

                    // ═══════════════════════════════════════════════════════════
                    // HARDWARE STATUS
                    // ═══════════════════════════════════════════════════════════
                    is SocketEvent.PrinterStatus -> {
                        Timber.d("🖨️ [Socket] Printer status: ${event.status} (${event.printerType})")
                        _hardwareStatus.tryEmit(event)

                        if (event.status in listOf("OFFLINE", "PAPER_OUT", "ERROR")) {
                            Timber.e("❌ [Printer] ${event.status}: ${event.errorMessage ?: "No details"}")
                        }
                    }

                    is SocketEvent.CardReaderStatus -> {
                        Timber.d("💳 [Socket] Card reader status: ${event.status} (${event.readerType})")
                        _hardwareStatus.tryEmit(event)

                        if (event.status in listOf("ERROR", "DISCONNECTED")) {
                            Timber.e("❌ [Card Reader] ${event.status}: ${event.errorMessage ?: "No details"}")
                        }
                    }

                    is SocketEvent.PeripheralError -> {
                        Timber.e("⚠️ [Socket] Peripheral error: ${event.peripheralType} - ${event.errorMessage} (severity: ${event.severity})")
                        _hardwareStatus.tryEmit(event)
                    }

                    // Other events handled by other ViewModels (PaymentViewModel, OrderViewModel, etc.)
                    else -> {
                        // Ignore events not relevant to HomeViewModel
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REMOTE COMMAND EXECUTION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 🎮 Execute Remote Command
     *
     * Converts Socket.IO event to TpvCommand and executes via CommandExecutor.
     * Handles full lifecycle: ACK → STARTED → EXECUTE → RESULT
     *
     * @param event The TPVCommand socket event received from server
     */
    private fun executeRemoteCommand(event: SocketEvent.TPVCommand) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Parse command type
                val commandType = TpvCommandType.fromString(event.commandType)
                if (commandType == null) {
                    Timber.e("❌ [Command] Unknown command type: ${event.commandType}")
                    return@launch
                }

                // Convert SocketEvent to TpvCommand
                val command = TpvCommand(
                    commandId = event.commandId,
                    correlationId = event.correlationId,
                    type = commandType,
                    payload = event.payload,
                    requiresPin = event.requiresPin,
                    priority = TpvCommandPriority.fromString(event.priority),
                    expiresAt = try {
                        Instant.parse(event.expiresAt)
                    } catch (e: Exception) {
                        Instant.now().plusSeconds(3600) // Default 1 hour if parse fails
                    },
                    requestedBy = event.requestedBy,
                    requestedByName = event.requestedByName
                )

                // Execute via CommandExecutor
                val result = commandExecutor.execute(command)

                Timber.i("✅ [Command] Executed ${command.type.name}: ${result.status.name} - ${result.message}")

                // **CRITICAL FIX (2025-12-01):**
                // Send HTTP ACK to server so it can update command status and sync terminal state.
                // Without this, commands received via Socket.IO would execute locally but server
                // would never know the result, causing dashboard/TPV state desync.
                val terminalId = secureStorage.getSerialNumber() ?: run {
                    Timber.e("❌ [Command] Cannot send ACK - no terminal serial number")
                    return@launch
                }

                val ackResult = heartbeatRepository.sendCommandAck(command.commandId, terminalId, result)
                ackResult.onSuccess {
                    Timber.i("✅ [Command] ACK sent for ${command.type.name}")
                }.onError { exception ->
                    Timber.w("⚠️ [Command] ACK failed for ${command.commandId}: ${exception.message}")
                }

            } catch (e: Exception) {
                Timber.e(e, "❌ [Command] Failed to execute command: ${event.commandType}")
            }
        }
    }

    /**
     * 🛠️ Exit Maintenance Mode (Local Action)
     *
     * Called when staff clicks "Exit Maintenance" button on overlay.
     * Staff can exit maintenance mode locally without admin intervention.
     */
    fun exitMaintenance() {
        Timber.i("🛠️ [Maintenance] Staff exiting maintenance mode locally")
        maintenanceManager.exitMaintenance()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VENUE STATUS CHANGE DETECTION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 📊 Check for Venue Status Changes
     *
     * Called after token refresh to detect if venue status changed mid-session.
     * Emits appropriate VenueStatusEvent based on the change.
     *
     * @param newStatus The new venue status from server response
     */
    fun checkVenueStatusChange(newStatus: VenueStatus) {
        viewModelScope.launch {
            val oldStatus = secureStorage.getVenueStatus()

            if (oldStatus != newStatus) {
                Timber.w("📊 [VenueStatus] Status changed: $oldStatus → $newStatus")

                // Update storage and state
                secureStorage.saveVenueStatus(newStatus)
                _venueStatus.value = newStatus

                // Emit appropriate event based on new status
                when {
                    // Venue suspended - force logout
                    newStatus == VenueStatus.SUSPENDED || newStatus == VenueStatus.ADMIN_SUSPENDED -> {
                        Timber.e("🚫 [VenueStatus] Venue suspended - forcing logout")
                        _venueStatusEvents.emit(VenueStatusEvent.VenueSuspended)
                    }

                    // Venue closed - force logout
                    newStatus == VenueStatus.CLOSED -> {
                        Timber.e("🚫 [VenueStatus] Venue closed - forcing logout")
                        _venueStatusEvents.emit(VenueStatusEvent.VenueClosed)
                    }

                    // Venue activated - show success
                    newStatus == VenueStatus.ACTIVE && oldStatus != VenueStatus.ACTIVE -> {
                        Timber.i("✅ [VenueStatus] Venue activated!")
                        _venueStatusEvents.emit(VenueStatusEvent.VenueActivated)
                    }

                    // Other status changes - just notify
                    else -> {
                        Timber.i("📊 [VenueStatus] Status changed: $oldStatus → $newStatus")
                        _venueStatusEvents.emit(VenueStatusEvent.StatusChanged(oldStatus, newStatus))
                    }
                }
            }
        }
    }

    /**
     * Force logout due to venue status change
     *
     * Called when venue becomes SUSPENDED or CLOSED mid-session.
     */
    fun forceLogoutDueToVenueStatus() {
        Timber.w("🚫 [VenueStatus] Force logout triggered")
        logout()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOGOUT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Logout user
     *
     * Clears session, stops heartbeat, and disconnects Socket.IO.
     */
    fun logout() {
        Timber.d("🚪 User initiated logout")

        // 🔌 Disconnect Socket.IO
        socketManager.disconnect()
        Timber.d("🔌 Socket.IO disconnected")

        // Clear session from SecureStorage
        authRepository.logout()

        Timber.d("✅ Logout complete")
    }
}

package com.jaac.avoqado_tpv.core.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.core.util.HeartbeatScheduler
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
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
    private val socketManager: SocketManager
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

    init {
        loadStaffInfo()
        collectSocketEvents()
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

                        // Handle specific commands
                        when (event.commandType) {
                            "MAINTENANCE_MODE" -> {
                                Timber.w("🛠️ [TPV Command] Entering maintenance mode...")
                                // TODO: Show maintenance UI, disable payment processing
                            }
                            "RELOAD" -> {
                                Timber.w("🔄 [TPV Command] Reloading app configuration...")
                                // TODO: Reload terminal config, refresh merchant accounts
                            }
                            "DISABLE" -> {
                                Timber.e("🚫 [TPV Command] Disabling terminal...")
                                // TODO: Lock UI, show "Terminal Disabled" screen
                            }
                            "SHUTDOWN" -> {
                                Timber.e("⛔ [TPV Command] Shutdown requested...")
                                // TODO: Close app gracefully
                            }
                            "RESTART" -> {
                                Timber.w("🔁 [TPV Command] Restart requested...")
                                // TODO: Restart app
                            }
                        }
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

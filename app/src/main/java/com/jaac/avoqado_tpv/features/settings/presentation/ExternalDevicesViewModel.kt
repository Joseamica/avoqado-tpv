package com.jaac.avoqado_tpv.features.settings.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.bluetooth.BluetoothPaymentService
import com.jaac.avoqado_tpv.core.bluetooth.ConnectedDevice
import com.jaac.avoqado_tpv.core.bluetooth.KnownDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ExternalDevicesViewModel
 *
 * Manages BLE Payment Server for external device connections (iPad, tablets)
 * Following Square/Toast/Stripe pattern for device linking.
 */
@HiltViewModel
class ExternalDevicesViewModel @Inject constructor(
    private val bluetoothPaymentService: BluetoothPaymentService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(ExternalDevicesState())
    val state: StateFlow<ExternalDevicesState> = _state.asStateFlow()

    init {
        observeBleServerState()
        // Note: Auto-restore is now handled by MainActivity.onCreate()
        // No need to restore here - this screen just observes server state
    }

    /**
     * Observe BLE server state changes from singleton service
     */
    private fun observeBleServerState() {
        viewModelScope.launch {
            bluetoothPaymentService.isRunning.collect { isRunning ->
                _state.value = _state.value.copy(
                    isServerRunning = isRunning,
                    wizardStep = if (!isRunning) WizardStep.NONE else _state.value.wizardStep
                )
            }
        }

        viewModelScope.launch {
            bluetoothPaymentService.connectedDevicesList.collect { devices ->
                val currentWizardStep = _state.value.wizardStep
                val previousCount = _state.value.connectedDevices.size
                val newCount = devices.size

                _state.value = _state.value.copy(
                    connectedDevices = devices,
                    // Auto-advance wizard when first device connects while waiting
                    wizardStep = if (newCount > 0 && previousCount == 0 && currentWizardStep == WizardStep.WAITING_CONNECTION) {
                        Timber.i("✅ [ExternalDevices] First device connected, advancing wizard to CONNECTED")
                        WizardStep.CONNECTED
                    } else {
                        currentWizardStep
                    },
                    message = if (newCount > previousCount && currentWizardStep == WizardStep.WAITING_CONNECTION) {
                        "¡Dispositivo conectado exitosamente!"
                    } else if (newCount > previousCount && newCount > 1) {
                        "Nuevo dispositivo conectado (${newCount} total)"
                    } else {
                        _state.value.message
                    }
                )
            }
        }

        // Observe known devices (persists across APK updates)
        viewModelScope.launch {
            bluetoothPaymentService.knownDevices.collect { devices ->
                Timber.d("🔵 [ExternalDevices] Known devices updated: ${devices.size} devices")
                _state.value = _state.value.copy(knownDevices = devices)
            }
        }

        // Pairing events (system dialog may appear on TPV)
        viewModelScope.launch {
            bluetoothPaymentService.pairingEvents.collect { event ->
                val needsPinOnTpv = event.key < 0
                _state.value = _state.value.copy(
                    wizardStep = WizardStep.NONE, // avoid blocking system dialog with our modal
                    message = if (needsPinOnTpv) {
                        "Se requiere ingresar el PIN que aparece en el iPad.\n\n" +
                            "Si no aparece el diálogo del sistema en el TPV, sal de esta pantalla y vuelve a intentarlo desde Inicio."
                    } else {
                        "Se está confirmando el enlace Bluetooth…"
                    },
                    isError = false
                )
            }
        }
    }

    /**
     * Forget a known device (remove from persistent storage)
     */
    fun forgetDevice(address: String) {
        bluetoothPaymentService.forgetDevice(address)
        _state.value = _state.value.copy(
            message = "Dispositivo olvidado"
        )
    }

    /**
     * Forget all known devices
     */
    fun forgetAllDevices() {
        bluetoothPaymentService.forgetAllDevices()
        _state.value = _state.value.copy(
            message = "Todos los dispositivos olvidados"
        )
    }

    /**
     * Toggle BLE Payment Server on/off
     *
     * Uses Foreground Service so BLE server persists across APK updates (Square pattern).
     */
    fun toggleServer(context: Context) {
        viewModelScope.launch {
            try {
                if (_state.value.isServerRunning) {
                    // Stop server (Foreground Service)
                    Timber.i("🔵 [ExternalDevices] Stopping BLE Foreground Service...")
                    bluetoothPaymentService.stopServer(context)

                    _state.value = _state.value.copy(
                        message = "Servidor BLE detenido",
                        isError = false,
                        wizardStep = WizardStep.NONE
                    )
                } else {
                    // Start server (Foreground Service)
                    Timber.i("🔵 [ExternalDevices] Starting BLE Foreground Service...")

                    bluetoothPaymentService.startServer(context) { request ->
                        Timber.i("💰 [ExternalDevices] Payment received: ${request.amountCents} cents")
                        handlePaymentReceived(request)
                    }

                    _state.value = _state.value.copy(
                        message = "Servidor BLE activo (servicio en segundo plano). Esperando conexiones...",
                        isError = false
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ [ExternalDevices] Failed to toggle BLE server")
                _state.value = _state.value.copy(
                    message = "Error: ${e.message}\n\nVerifica que Bluetooth esté activado y los permisos concedidos.",
                    isError = true
                )
            }
        }
    }

    /**
     * Start device linking wizard
     */
    fun startLinkingWizard() {
        _state.value = _state.value.copy(
            wizardStep = WizardStep.PREPARE_EXTERNAL,
            message = ""
        )
    }

    /**
     * Navigate wizard to next step
     */
    fun nextWizardStep() {
        val currentStep = _state.value.wizardStep
        val nextStep = when (currentStep) {
            WizardStep.PREPARE_EXTERNAL -> WizardStep.WAITING_CONNECTION
            WizardStep.WAITING_CONNECTION -> WizardStep.CONNECTED
            else -> WizardStep.NONE
        }

        _state.value = _state.value.copy(
            wizardStep = nextStep,
            message = if (nextStep == WizardStep.CONNECTED) "¡Dispositivo conectado exitosamente!" else ""
        )
    }

    /**
     * Cancel wizard and close
     */
    fun cancelWizard() {
        _state.value = _state.value.copy(
            wizardStep = WizardStep.NONE,
            message = ""
        )
    }

    /**
     * Clear message
     */
    fun clearMessage() {
        _state.value = _state.value.copy(message = "")
    }

    /**
     * Handle payment amount received from external device
     */
    private fun handlePaymentReceived(request: com.jaac.avoqado_tpv.core.bluetooth.BlePaymentRequest) {
        viewModelScope.launch {
            val tipInfo = if (request.tipCents != null) " + propina ${request.tipCents}c" else ""
            val ratingInfo = request.rating?.let { " | rating $it⭐" } ?: ""
            _state.value = _state.value.copy(
                lastPaymentAmount = request.amountCents,
                message = "💰 Pago recibido: $${request.amountCents / 100.0}$tipInfo$ratingInfo\n\nMonto: ${request.amountCents} centavos\n¡Listo para procesar!"
            )

            // TODO: Navigate to payment screen with amount
            // For now, just show message
        }
    }
}

/**
 * External Devices Screen State
 */
data class ExternalDevicesState(
    val isServerRunning: Boolean = false,
    val connectedDevices: List<ConnectedDevice> = emptyList(),
    val knownDevices: List<KnownDevice> = emptyList(),  // Persists across APK updates
    val wizardStep: WizardStep = WizardStep.NONE,
    val message: String = "",
    val isError: Boolean = false,
    val lastPaymentAmount: Long? = null
) {
    /**
     * First connected device (for backward compatibility)
     */
    val connectedDevice: ConnectedDevice?
        get() = connectedDevices.firstOrNull()

    /**
     * Number of connected devices
     */
    val connectedDeviceCount: Int
        get() = connectedDevices.size

    /**
     * Number of known devices (including disconnected)
     */
    val knownDeviceCount: Int
        get() = knownDevices.size
}

/**
 * Wizard Steps for Device Linking
 */
enum class WizardStep {
    NONE,                   // No wizard active
    PREPARE_EXTERNAL,       // Step 1: Prepare external device
    WAITING_CONNECTION,     // Step 2: Wait for BLE connection
    CONNECTED              // Step 3: Connection successful
}

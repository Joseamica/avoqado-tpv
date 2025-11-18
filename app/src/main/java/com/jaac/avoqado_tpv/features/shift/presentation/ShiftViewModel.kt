package com.jaac.avoqado_tpv.features.shift.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.core.util.ConnectionEventManager
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Shift ViewModel
 *
 * Manages shift state and business logic for shift management screens.
 * Follows Toast/Square POS pattern with automatic calculations.
 *
 * **Responsibilities:**
 * - Load current active shift
 * - Open new shifts
 * - Close existing shifts
 * - Provide UI state for shift status banner and full screen
 *
 * **State Management:**
 * - Uses StateFlow for reactive UI updates
 * - Immutable state objects
 * - Single source of truth
 *
 * **Usage:**
 * ```kotlin
 * @Composable
 * fun ShiftScreen(viewModel: ShiftViewModel = hiltViewModel()) {
 *     val state by viewModel.state.collectAsStateWithLifecycle()
 *
 *     when (val currentState = state) {
 *         is ShiftState.Idle -> { /* Show empty state */ }
 *         is ShiftState.Loading -> { /* Show loading */ }
 *         is ShiftState.ShiftActive -> { /* Show active shift */ }
 *         is ShiftState.NoActiveShift -> { /* Show open shift button */ }
 *         is ShiftState.Error -> { /* Show error message */ }
 *     }
 * }
 * ```
 */
@HiltViewModel
class ShiftViewModel @Inject constructor(
    private val shiftRepository: ShiftRepository,
    private val secureStorage: SecureStorage,
    private val connectionEventManager: ConnectionEventManager
) : ViewModel() {

    // ══════════════════════════════════════════════════════════════════════
    // STATE
    // ══════════════════════════════════════════════════════════════════════

    private val _state = MutableStateFlow<ShiftState>(ShiftState.Idle)
    val state: StateFlow<ShiftState> = _state.asStateFlow()

    // ══════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ══════════════════════════════════════════════════════════════════════

    init {
        loadCurrentShift()
        listenToConnectionRestored()
    }

    /**
     * 🔄 Listen to Connection Restored Events
     *
     * When backend connection is restored after being offline, automatically
     * reload shift data to sync with server state.
     *
     * **Pattern (Toast POS / Square POS):**
     * - Backend goes down → App works offline with cached data
     * - Backend comes back → Heartbeat succeeds → Trigger "reconciliation sync"
     * - Reload: Shift status, orders, config, etc.
     *
     * **Critical for:**
     * - Fixing "Sin turno activo" bug when shift was opened during offline mode
     * - Syncing shift closures made by other terminals
     * - Updating shift totals (sales, cash drawer)
     */
    private fun listenToConnectionRestored() {
        viewModelScope.launch {
            connectionEventManager.connectionRestoredEvents.collect { event ->
                Timber.i("🔄 [ShiftViewModel] Connection restored - syncing shift data")
                Timber.d("   Reconnected after ${event.attemptsBeforeReconnection} attempts at ${event.timestamp}")
                loadCurrentShift()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC METHODS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Load current active shift and shift history
     *
     * Called on ViewModel initialization and after open/close operations.
     * Updates state with current shift (or no shift) + shift history.
     *
     * **Pattern (Square/Toast POS):**
     * - Always load history to show in bottom section
     * - History shows last 10 closed shifts
     * - Helps staff review past shifts quickly
     *
     * **Anti-flash fix:**
     * - Only sets Loading state if current state is Idle or Error
     * - If already ShiftActive, keeps the shift visible while reloading
     * - Prevents "flash of no shift" when navigating back to WelcomeScreen
     */
    fun loadCurrentShift() {
        viewModelScope.launch {
            // ✅ Anti-flash fix: Only show Loading if we don't already have a shift
            // This prevents the "flash of inactive shift" when navigating back to WelcomeScreen
            val currentState = _state.value
            if (currentState !is ShiftState.ShiftActive) {
                _state.value = ShiftState.Loading
            }

            val venueId = secureStorage.getVenueId()
            if (venueId == null) {
                Timber.e("❌ No venueId found in storage")
                _state.value = ShiftState.Error("No se encontró información del local")
                return@launch
            }

            // Load current shift
            val currentShiftResult = shiftRepository.getCurrentShift(venueId)

            // Load shift history (last 10 closed shifts)
            val historyResult = shiftRepository.getShiftHistory(venueId, limit = 10)

            // Extract history (default to empty list on error)
            val shiftHistory = when (historyResult) {
                is Result.Success -> historyResult.data
                is Result.Error -> {
                    Timber.w("⚠️ Failed to load shift history, continuing without it")
                    emptyList()
                }
            }

            // Update state based on current shift result
            when (currentShiftResult) {
                is Result.Success -> {
                    val shift = currentShiftResult.data
                    if (shift != null) {
                        Timber.i("✅ Active shift loaded: ${shift.id}")
                        _state.value = ShiftState.ShiftActive(shift, shiftHistory)
                    } else {
                        Timber.i("ℹ️ No active shift")
                        _state.value = ShiftState.NoActiveShift(shiftHistory)
                    }
                }
                is Result.Error -> {
                    val errorMessage = translateError(currentShiftResult.exception)
                    Timber.e("❌ Failed to load shift: $errorMessage")
                    _state.value = ShiftState.Error(errorMessage)
                }
            }
        }
    }

    /**
     * Open a new shift
     *
     * Creates a new shift with starting cash amount.
     * Backend sets status = OPEN and begins tracking.
     *
     * @param startingCash Initial cash amount in drawer
     */
    fun openShift(startingCash: Double) {
        viewModelScope.launch {
            _state.value = ShiftState.Loading

            val venueId = secureStorage.getVenueId()
            val staffId = secureStorage.getStaffId()

            if (venueId == null || staffId == null) {
                Timber.e("❌ Missing venueId or staffId")
                _state.value = ShiftState.Error("Información de sesión incompleta")
                return@launch
            }

            Timber.i("🟢 Opening shift with starting cash: $$startingCash")

            when (val result = shiftRepository.openShift(venueId, staffId, startingCash)) {
                is Result.Success -> {
                    val shift = result.data
                    Timber.i("✅ Shift opened successfully: ${shift.id}")
                    // Reload to get fresh state with history
                    loadCurrentShift()
                }
                is Result.Error -> {
                    val errorMessage = translateError(result.exception)
                    Timber.e("❌ Failed to open shift: $errorMessage")
                    _state.value = ShiftState.Error(errorMessage)
                }
            }
        }
    }

    /**
     * Close the current shift
     *
     * Closes the active shift with automatic calculations:
     * - Payment breakdown (cash, card, voucher, other)
     * - Products sold count
     * - Inventory consumed (FIFO batches)
     * - Total sales, tips, orders
     */
    fun closeShift() {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState !is ShiftState.ShiftActive) {
                Timber.w("⚠️ Cannot close shift: No active shift")
                return@launch
            }

            _state.value = ShiftState.Loading

            val venueId = secureStorage.getVenueId()
            if (venueId == null) {
                Timber.e("❌ No venueId found")
                _state.value = ShiftState.Error("No se encontró información del local")
                return@launch
            }

            val shiftId = currentState.shift.id
            Timber.i("🔴 Closing shift: $shiftId")

            when (val result = shiftRepository.closeShift(venueId, shiftId)) {
                is Result.Success -> {
                    val closedShift = result.data
                    Timber.i("✅ Shift closed. Sales: $${closedShift.totalSales}, Products: ${closedShift.totalProductsSold}")

                    // Load updated history (now includes the just-closed shift)
                    val historyResult = shiftRepository.getShiftHistory(venueId, limit = 10)
                    val shiftHistory = when (historyResult) {
                        is Result.Success -> historyResult.data
                        is Result.Error -> emptyList()
                    }

                    // Show closed shift briefly with updated history
                    _state.value = ShiftState.ShiftClosed(closedShift, shiftHistory)

                    // After 2 seconds, reload to show "no active shift" state
                    kotlinx.coroutines.delay(2000)
                    loadCurrentShift()
                }
                is Result.Error -> {
                    val errorMessage = translateError(result.exception)
                    Timber.e("❌ Failed to close shift: $errorMessage")
                    _state.value = ShiftState.Error(errorMessage)
                }
            }
        }
    }

    /**
     * Retry after error
     *
     * Reloads current shift state after network error.
     */
    fun retry() {
        loadCurrentShift()
    }

    // ══════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Translate API errors to user-friendly messages
     *
     * CRITICAL: Never show technical errors to users.
     */
    private fun translateError(exception: Exception): String {
        return when {
            exception.message?.contains("400", ignoreCase = true) == true -> {
                "Ya existe un turno abierto.\n\n" +
                "Por favor, cierra el turno actual antes de abrir uno nuevo."
            }
            exception.message?.contains("404", ignoreCase = true) == true -> {
                "No se encontró el turno.\n\n" +
                "Es posible que ya haya sido cerrado."
            }
            exception.message?.contains("NetworkError", ignoreCase = true) == true -> {
                "No se pudo conectar al servidor.\n\n" +
                "Verifica tu conexión a internet e intenta nuevamente."
            }
            exception.message?.contains("timeout", ignoreCase = true) == true -> {
                "Tiempo de espera agotado.\n\n" +
                "Por favor, intenta nuevamente."
            }
            else -> {
                "Error al procesar la solicitud.\n\n" +
                "Por favor, intenta nuevamente."
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// STATE DEFINITIONS
// ══════════════════════════════════════════════════════════════════════

/**
 * Shift UI State
 *
 * Sealed class representing all possible UI states for shift management.
 * Follows Material Design state management patterns.
 */
sealed class ShiftState {
    /**
     * Initial state before first load
     */
    data object Idle : ShiftState()

    /**
     * Loading state (opening, closing, or fetching shift)
     */
    data object Loading : ShiftState()

    /**
     * Active shift state (shift is currently open)
     *
     * @param shift Current active shift with live metrics
     * @param shiftHistory List of recent closed shifts (for history display)
     */
    data class ShiftActive(
        val shift: Shift,
        val shiftHistory: List<Shift> = emptyList()
    ) : ShiftState()

    /**
     * No active shift state (ready to open new shift)
     *
     * @param shiftHistory List of recent closed shifts (for history display)
     */
    data class NoActiveShift(
        val shiftHistory: List<Shift> = emptyList()
    ) : ShiftState()

    /**
     * Shift closed state (temporary state showing close summary)
     *
     * @param shift Closed shift with final calculations
     * @param shiftHistory List of recent closed shifts (for history display)
     */
    data class ShiftClosed(
        val shift: Shift,
        val shiftHistory: List<Shift> = emptyList()
    ) : ShiftState()

    /**
     * Error state with user-friendly message
     *
     * @param message Translated error message for user display
     */
    data class Error(val message: String) : ShiftState()
}

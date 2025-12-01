package com.jaac.avoqado_tpv.features.timeclock.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.timeclock.domain.model.TimeEntry
import com.jaac.avoqado_tpv.features.timeclock.domain.model.TimeEntryStatus
import com.jaac.avoqado_tpv.features.timeclock.domain.repository.TimeEntryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class TimeclockViewModel @Inject constructor(
    private val timeEntryRepository: TimeEntryRepository,
    private val authRepository: AuthRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val venueId: String = savedStateHandle.get<String>("venueId") ?: ""
    private val pin: String = savedStateHandle.get<String>("pin") ?: ""

    private val _state = MutableStateFlow<TimeclockState>(TimeclockState.Loading)
    val state: StateFlow<TimeclockState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TimeclockEvent>()
    val events: SharedFlow<TimeclockEvent> = _events.asSharedFlow()

    // Store staff info after verification
    private var currentStaffId: String? = null
    private var currentStaffName: String? = null

    init {
        if (venueId.isNotEmpty() && pin.isNotEmpty()) {
            verifyPinAndLoadStatus()
        } else {
            _state.value = TimeclockState.Error("Datos de sesión inválidos")
        }
    }

    /**
     * Verify PIN by attempting login (without saving tokens)
     * Then load the current timeclock status for this staff member
     */
    private fun verifyPinAndLoadStatus() {
        viewModelScope.launch {
            _state.value = TimeclockState.Loading

            try {
                // Step 1: Verify PIN using auth endpoint
                val authResult = authRepository.verifyPinOnly(venueId, pin)

                authResult.fold(
                    onSuccess = { staffInfo ->
                        currentStaffId = staffInfo.id
                        currentStaffName = staffInfo.name
                        Timber.i("✅ PIN verified for staff: ${staffInfo.name}")

                        // Step 2: Load current timeclock status
                        loadTimeclockStatus(staffInfo.id, staffInfo.name)
                    },
                    onFailure = { error ->
                        Timber.e("❌ PIN verification failed: ${error.message}")
                        _state.value = TimeclockState.InvalidPin(
                            error.message ?: "PIN incorrecto"
                        )
                    }
                )
            } catch (e: Exception) {
                Timber.e(e, "❌ Error verifying PIN")
                _state.value = TimeclockState.Error("Error de conexión")
            }
        }
    }

    /**
     * Load current timeclock status for the verified staff member
     */
    private suspend fun loadTimeclockStatus(staffId: String, staffName: String) {
        try {
            // Get today's date range for filtering
            val today = LocalDate.now()
            val startDate = today.atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            val endDate = today.plusDays(1).atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

            // Fetch time entries for this staff member today
            val entriesResult = timeEntryRepository.getTimeEntries(
                venueId = venueId,
                staffId = staffId,
                startDate = startDate,
                endDate = endDate,
                limit = 10
            )

            entriesResult.fold(
                onSuccess = { entries ->
                    // Find active entry (CLOCKED_IN or ON_BREAK)
                    val activeEntry = entries.firstOrNull {
                        it.status == TimeEntryStatus.CLOCKED_IN || it.status == TimeEntryStatus.ON_BREAK
                    }

                    // Calculate total hours worked today
                    val totalHours = entries
                        .filter { it.status == TimeEntryStatus.CLOCKED_OUT }
                        .mapNotNull { it.totalHours }
                        .fold(BigDecimal.ZERO) { acc, hours -> acc + hours }

                    _state.value = TimeclockState.Ready(
                        staffId = staffId,
                        staffName = staffName,
                        currentEntry = activeEntry,
                        recentEntries = entries,
                        totalHoursToday = totalHours
                    )
                },
                onFailure = { error ->
                    // If no entries found, still show Ready state (not clocked in)
                    _state.value = TimeclockState.Ready(
                        staffId = staffId,
                        staffName = staffName,
                        currentEntry = null,
                        recentEntries = emptyList(),
                        totalHoursToday = BigDecimal.ZERO
                    )
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ Error loading timeclock status")
            _state.value = TimeclockState.Error("Error cargando estado")
        }
    }

    /**
     * Clock in the current staff member
     */
    fun clockIn() {
        val staffId = currentStaffId ?: return
        val staffName = currentStaffName ?: return

        viewModelScope.launch {
            _state.value = TimeclockState.Processing("Registrando entrada...")

            val result = timeEntryRepository.clockIn(
                venueId = venueId,
                staffId = staffId,
                pin = pin
            )

            result.fold(
                onSuccess = { entry ->
                    Timber.i("✅ Clock in successful")
                    _events.emit(TimeclockEvent.ClockInSuccess(entry))
                    loadTimeclockStatus(staffId, staffName)
                },
                onFailure = { error ->
                    Timber.e("❌ Clock in failed: ${error.message}")
                    _events.emit(TimeclockEvent.Error(error.message ?: "Error al registrar entrada"))
                    // Reload status to refresh UI
                    loadTimeclockStatus(staffId, staffName)
                }
            )
        }
    }

    /**
     * Clock out the current staff member
     */
    fun clockOut() {
        val staffId = currentStaffId ?: return
        val staffName = currentStaffName ?: return

        viewModelScope.launch {
            _state.value = TimeclockState.Processing("Registrando salida...")

            val result = timeEntryRepository.clockOut(
                venueId = venueId,
                staffId = staffId,
                pin = pin
            )

            result.fold(
                onSuccess = { entry ->
                    Timber.i("✅ Clock out successful, hours: ${entry.totalHours}")
                    _events.emit(TimeclockEvent.ClockOutSuccess(entry, entry.totalHours))
                    loadTimeclockStatus(staffId, staffName)
                },
                onFailure = { error ->
                    Timber.e("❌ Clock out failed: ${error.message}")
                    _events.emit(TimeclockEvent.Error(error.message ?: "Error al registrar salida"))
                    loadTimeclockStatus(staffId, staffName)
                }
            )
        }
    }

    /**
     * Start a break
     */
    fun startBreak() {
        val currentState = _state.value as? TimeclockState.Ready ?: return
        val timeEntryId = currentState.currentEntry?.id ?: return
        val staffId = currentStaffId ?: return
        val staffName = currentStaffName ?: return

        viewModelScope.launch {
            _state.value = TimeclockState.Processing("Iniciando descanso...")

            val result = timeEntryRepository.startBreak(timeEntryId)

            result.fold(
                onSuccess = { entry ->
                    Timber.i("✅ Break started")
                    _events.emit(TimeclockEvent.BreakStarted(entry))
                    loadTimeclockStatus(staffId, staffName)
                },
                onFailure = { error ->
                    Timber.e("❌ Start break failed: ${error.message}")
                    _events.emit(TimeclockEvent.Error(error.message ?: "Error al iniciar descanso"))
                    loadTimeclockStatus(staffId, staffName)
                }
            )
        }
    }

    /**
     * End a break
     */
    fun endBreak() {
        val currentState = _state.value as? TimeclockState.Ready ?: return
        val timeEntryId = currentState.currentEntry?.id ?: return
        val staffId = currentStaffId ?: return
        val staffName = currentStaffName ?: return

        viewModelScope.launch {
            _state.value = TimeclockState.Processing("Finalizando descanso...")

            val result = timeEntryRepository.endBreak(timeEntryId)

            result.fold(
                onSuccess = { entry ->
                    Timber.i("✅ Break ended")
                    _events.emit(TimeclockEvent.BreakEnded(entry))
                    loadTimeclockStatus(staffId, staffName)
                },
                onFailure = { error ->
                    Timber.e("❌ End break failed: ${error.message}")
                    _events.emit(TimeclockEvent.Error(error.message ?: "Error al finalizar descanso"))
                    loadTimeclockStatus(staffId, staffName)
                }
            )
        }
    }

    /**
     * Navigate to login (Done button)
     */
    fun navigateToLogin() {
        viewModelScope.launch {
            _events.emit(TimeclockEvent.NavigateToLogin)
        }
    }

    /**
     * Retry loading after error
     */
    fun retry() {
        verifyPinAndLoadStatus()
    }
}

/**
 * Staff info returned from PIN verification
 */
data class StaffInfo(
    val id: String,
    val name: String
)

package com.jaac.avoqado_tpv.features.sim_custody.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.features.sim_custody.domain.model.BulkResult
import com.jaac.avoqado_tpv.features.sim_custody.domain.model.MySim
import com.jaac.avoqado_tpv.features.sim_custody.domain.model.SimCustodyState
import com.jaac.avoqado_tpv.features.sim_custody.domain.repository.SimCustodyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel for "Mis SIMs" (plan §3.1).
 *
 * Source-of-truth list is loaded from /tpv/sim-custody/my-sims; mutations
 * optimistically update local state, then reconcile with the backend response.
 *
 * Venue timezone is hardcoded to America/Mexico_City for now; a richer
 * implementation reads it via `VenueTimeZone.get(secureStorage)` (see
 * avoqado-tpv/.claude/rules/critical-warnings.md). Using the fixed zone avoids
 * pulling SecureStorage into this MVP and matches fallback behavior.
 */
private val VENUE_ZONE: ZoneId = ZoneId.of("America/Mexico_City")

private fun MySim.isSoldToday(): Boolean {
    if (custodyState != SimCustodyState.SOLD) return false
    val soldInstant = soldAt ?: return false
    return soldInstant.atZone(VENUE_ZONE).toLocalDate() == LocalDate.now(VENUE_ZONE)
}

data class MisSimsUiState(
    val isLoading: Boolean = true,
    val searchQuery: String = "",
    val filter: MisSimsFilter = MisSimsFilter.ALL,
    val all: List<MySim> = emptyList(),
    val pendingCount: Int = 0,
    val acceptingSerials: Set<String> = emptySet(),
    val rejectingSerials: Set<String> = emptySet(),
    val confirmAcceptAll: Boolean = false,
    val message: UiMessage? = null,
    val error: String? = null,
) {
    val filtered: List<MySim>
        get() {
            val byFilter = when (filter) {
                MisSimsFilter.ALL -> all
                MisSimsFilter.PENDING -> all.filter { it.custodyState == SimCustodyState.PROMOTER_PENDING }
                MisSimsFilter.MINE -> all.filter { it.custodyState == SimCustodyState.PROMOTER_HELD }
                MisSimsFilter.SOLD_TODAY -> all.filter { it.isSoldToday() }
            }
            if (searchQuery.isBlank()) return byFilter
            val q = searchQuery.trim()
            return byFilter.filter { it.serialNumber.contains(q, ignoreCase = true) }
        }
}

enum class MisSimsFilter { ALL, PENDING, MINE, SOLD_TODAY }

sealed interface UiMessage {
    data class Info(val text: String) : UiMessage
    data class Error(val text: String) : UiMessage
}

@HiltViewModel
class MisSimsViewModel @Inject constructor(
    private val repo: SimCustodyRepository,
    private val socketManager: SocketManager,
) : ViewModel() {

    companion object {
        private const val TAG = "MisSimsViewModel"
    }

    private val _state = MutableStateFlow(MisSimsUiState())
    val state: StateFlow<MisSimsUiState> = _state.asStateFlow()

    init {
        refresh()
        observeSocketEvents()
    }

    /**
     * Subscribes to sim-custody socket events. Any event triggers a fresh
     * `GET /my-sims` so the inbox stays in sync when the Supervisor assigns
     * or recollects while the promoter has the screen open. Also acts as a
     * fallback when FCM fires but the socket was briefly disconnected.
     *
     * `SaleVerificationReviewed` (back-office approves/rejects the proof-of-sale
     * documentation) also refreshes, so a SOLD SIM's badge flips to "Revisar" /
     * "Vendido" in real time — same event "Mis Ventas" already listens to.
     */
    private fun observeSocketEvents() {
        viewModelScope.launch {
            socketManager.events
                .onEach { event ->
                    when (event) {
                        is SocketEvent.SimCustodyAssignedToPromoter,
                        is SocketEvent.SimCustodyRecollectedFromPromoter,
                        is SocketEvent.SaleVerificationReviewed,
                        -> {
                            Timber.tag(TAG).d("sim-custody/verification event received, refreshing: $event")
                            refresh()
                        }
                        else -> Unit
                    }
                }
                .collect {}
        }
    }

    fun refresh() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            repo.getMySims()
                .onSuccess { sims ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            all = sims,
                            pendingCount = sims.count { s -> s.custodyState == SimCustodyState.PROMOTER_PENDING },
                        )
                    }
                }
                .onFailure { e ->
                    Timber.tag(TAG).e(e, "getMySims failed")
                    _state.update { it.copy(isLoading = false, error = e.message ?: "Error desconocido") }
                }
        }
    }

    fun onSearchChange(q: String) = _state.update { it.copy(searchQuery = q) }
    fun onFilterChange(f: MisSimsFilter) = _state.update { it.copy(filter = f) }

    fun requestAcceptAll() {
        val pending = _state.value.all.count { it.custodyState == SimCustodyState.PROMOTER_PENDING }
        if (pending == 0) return
        _state.update { it.copy(confirmAcceptAll = true) }
    }

    fun dismissAcceptAllConfirm() = _state.update { it.copy(confirmAcceptAll = false) }

    fun confirmAcceptAll() {
        val pending = _state.value.all.filter { it.custodyState == SimCustodyState.PROMOTER_PENDING }
        _state.update { it.copy(confirmAcceptAll = false) }
        if (pending.isEmpty()) return
        performAccept(pending.map { it.serialNumber })
    }

    fun acceptOne(serialNumber: String) = performAccept(listOf(serialNumber))

    private fun performAccept(serials: List<String>) {
        val key = UUID.randomUUID().toString()
        _state.update { it.copy(acceptingSerials = it.acceptingSerials + serials) }
        viewModelScope.launch {
            repo.accept(serials, idempotencyKey = key)
                .onSuccess { result -> afterAccept(serials, result) }
                .onFailure { e ->
                    Timber.tag(TAG).e(e, "accept failed")
                    _state.update {
                        it.copy(
                            acceptingSerials = it.acceptingSerials - serials.toSet(),
                            message = UiMessage.Error("No se pudo aceptar los SIMs. Intenta de nuevo."),
                        )
                    }
                }
        }
    }

    private fun afterAccept(requested: List<String>, result: BulkResult) {
        val okSerials = result.results.filter { it.isOk }.map { it.serialNumber }.toSet()
        val updated = _state.value.all.map { sim ->
            if (okSerials.contains(sim.serialNumber)) sim.copy(custodyState = SimCustodyState.PROMOTER_HELD) else sim
        }
        val msg = when {
            result.summary.failed == 0 -> UiMessage.Info("${result.summary.succeeded} SIM(s) aceptado(s).")
            result.summary.succeeded == 0 -> UiMessage.Error("No se pudo aceptar ningún SIM.")
            else -> UiMessage.Info("${result.summary.succeeded} aceptados, ${result.summary.failed} con error.")
        }
        _state.update {
            it.copy(
                acceptingSerials = it.acceptingSerials - requested.toSet(),
                all = updated,
                pendingCount = updated.count { s -> s.custodyState == SimCustodyState.PROMOTER_PENDING },
                message = msg,
            )
        }
    }

    fun rejectOne(serialNumber: String) {
        _state.update { it.copy(rejectingSerials = it.rejectingSerials + serialNumber) }
        viewModelScope.launch {
            repo.reject(serialNumber)
                .onSuccess { _ ->
                    val updated = _state.value.all.filterNot { it.serialNumber == serialNumber }
                    _state.update {
                        it.copy(
                            rejectingSerials = it.rejectingSerials - serialNumber,
                            all = updated,
                            pendingCount = updated.count { s -> s.custodyState == SimCustodyState.PROMOTER_PENDING },
                            message = UiMessage.Info("SIM rechazado."),
                        )
                    }
                }
                .onFailure { e ->
                    Timber.tag(TAG).e(e, "reject failed")
                    _state.update {
                        it.copy(
                            rejectingSerials = it.rejectingSerials - serialNumber,
                            message = UiMessage.Error("No se pudo rechazar el SIM."),
                        )
                    }
                }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}

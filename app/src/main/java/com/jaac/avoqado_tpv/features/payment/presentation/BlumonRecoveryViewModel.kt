package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.features.payment.data.ledger.BlumonAttemptResolver
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BlumonRecoveryViewModel @Inject constructor(
    private val resolver: BlumonAttemptResolver,
    private val ledger: PaymentAttemptLedger,
    private val storage: SecureStorage,
    private val socketManager: SocketManager,
) : ViewModel() {
    data class UiState(
        val result: BlumonAttemptResolver.Result = BlumonAttemptResolver.Result(BlumonAttemptResolver.State.PENDING),
        val busy: Boolean = true,
        val secondsRemaining: Int = 10,
        val hasPermission: Boolean = false,
        val hasCheckPermission: Boolean = false,
    )
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private var attemptId: String? = null
    private var venueId: String? = null
    private var job: Job? = null
    private var observer: Job? = null

    fun start(id: String) {
        if (attemptId == id) return
        job?.cancel()
        observer?.cancel()
        attemptId = id
        venueId = storage.getVenueId()
        val venue = venueId ?: run { _state.value = UiState(BlumonAttemptResolver.Result(BlumonAttemptResolver.State.UNAVAILABLE), false, 0); return }
        _state.value = UiState()
        ledger.anotarEnDuda(id)
        // Room invalidation also catches an approval arriving after an operator declaration.
        observer = viewModelScope.launch {
            resolver.observe(venue, id).collect { result ->
                _state.value = _state.value.copy(result = result.copy(serverAnswered = _state.value.result.serverAnswered), hasPermission = resolver.hasPermission(), hasCheckPermission = resolver.hasCheckPermission())
            }
        }
        consultAgain()
    }

    fun consultAgain() {
        if (job?.isActive == true) return
        val id = attemptId ?: return
        val venue = venueId ?: return
        job = viewModelScope.launch {
            var answeredDuringWindow = false
            do {
                _state.value = _state.value.copy(busy = true)
                val result = resolver.consult(venue, id)
                result.resultJson?.let(socketManager::emitDurableTerminalPaymentResult)
                answeredDuringWindow = answeredDuringWindow || result.serverAnswered
                val window = if (result.row?.terminalPaymentRequestId == null) 10_000L else 45_000L
                val remaining = ledger.faltaParaLiberarSola(id, window)
                _state.value = UiState(result.copy(serverAnswered = answeredDuringWindow), false, ((remaining + 999) / 1000).toInt(), resolver.hasPermission(), resolver.hasCheckPermission())
                if (result.state != BlumonAttemptResolver.State.PENDING || remaining == 0L) break
                delay(minOf(5_000L, remaining))
            } while (true)
        }
    }

    fun declare(checked: Boolean = false) {
        if (job?.isActive == true || _state.value.secondsRemaining > 0) return
        val id = attemptId ?: return
        val venue = venueId ?: return
        job = viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = if (checked) resolver.declareChecked(venue, id) else resolver.declare(venue, id)
            result.resultJson?.let(socketManager::emitDurableTerminalPaymentResult)
            _state.value = UiState(result, false, 0, resolver.hasPermission(), resolver.hasCheckPermission())
        }
    }
}

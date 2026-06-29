package com.jaac.avoqado_tpv.features.cash_out.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.jaac.avoqado_tpv.core.data.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.math.BigDecimal
import javax.inject.Inject

/**
 * "Mis Comisiones" — the promoter's own cash-out balance + same-day withdrawal.
 * Self-scoped: the backend reads the staffId/venueId from the authenticated TPV
 * session, so this screen never sends an identity. Money is pesos (BigDecimal).
 * The "Retirar" action is only allowed on a configured active day (the backend
 * enforces it; the UI mirrors the flag to enable/disable the button).
 */
data class MyCommissionsUiState(
    val isLoading: Boolean = true,
    val saldo: BigDecimal = BigDecimal.ZERO,
    val activeToday: Boolean = false,
    val isWithdrawing: Boolean = false,
    val lastWithdrawalFolio: String? = null, // set after a successful "Retirar"
    val error: String? = null,
) {
    val canWithdraw: Boolean
        get() = !isWithdrawing && activeToday && saldo > BigDecimal.ZERO
}

private data class ApiErrorBody(val message: String? = null)

@HiltViewModel
class MyCommissionsViewModel @Inject constructor(
    private val apiService: ApiService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MyCommissionsUiState())
    val uiState: StateFlow<MyCommissionsUiState> = _uiState.asStateFlow()

    private val gson = Gson()

    init {
        loadSaldo()
    }

    fun loadSaldo() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val response = apiService.getPromoterCashOut()
                val body = response.body()?.data
                if (response.isSuccessful && body != null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        saldo = parseAmount(body.saldo),
                        activeToday = body.activeToday,
                        error = null,
                    )
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = extractError(response.errorBody()?.string()))
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load cash-out saldo")
                _uiState.value = _uiState.value.copy(isLoading = false, error = "Error de conexión: ${e.message}")
            }
        }
    }

    fun withdraw() {
        // Guard against double-taps / invalid states (button is also disabled in the UI).
        if (!_uiState.value.canWithdraw) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isWithdrawing = true, error = null)
            try {
                val response = apiService.withdrawCashOut()
                val body = response.body()?.data
                if (response.isSuccessful && body != null) {
                    _uiState.value = _uiState.value.copy(isWithdrawing = false, lastWithdrawalFolio = body.folio)
                    loadSaldo() // refresh — saldo should drop to 0
                } else {
                    _uiState.value = _uiState.value.copy(isWithdrawing = false, error = extractError(response.errorBody()?.string()))
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to withdraw cash-out")
                _uiState.value = _uiState.value.copy(isWithdrawing = false, error = "Error de conexión: ${e.message}")
            }
        }
    }

    /** Dismiss the success/error banners. */
    fun clearMessages() {
        _uiState.value = _uiState.value.copy(lastWithdrawalFolio = null, error = null)
    }

    private fun parseAmount(raw: String): BigDecimal = try {
        BigDecimal(raw)
    } catch (e: Exception) {
        BigDecimal.ZERO
    }

    /** Surface the backend's Spanish message (e.g. "Hoy no es un día habilitado…") when present. */
    private fun extractError(body: String?): String = try {
        gson.fromJson(body, ApiErrorBody::class.java)?.message ?: "No se pudo completar la operación"
    } catch (e: Exception) {
        "No se pudo completar la operación"
    }
}

package com.jaac.avoqado_campo.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface EstadoLogin {
    data object Inicial : EstadoLogin
    data object Cargando : EstadoLogin
    data class Error(val mensaje: String) : EstadoLogin
    data class Dentro(val venueId: String) : EstadoLogin
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val repositorio: RepositorioAuthCampo,
) : ViewModel() {

    private val _estado = MutableStateFlow<EstadoLogin>(EstadoLogin.Inicial)
    val estado: StateFlow<EstadoLogin> = _estado.asStateFlow()

    fun entrar(correo: String, contrasena: String) {
        _estado.value = EstadoLogin.Cargando
        viewModelScope.launch {
            _estado.value = when (val r = repositorio.entrar(correo, contrasena)) {
                is ResultadoLogin.Ok -> EstadoLogin.Dentro(r.venueId)
                is ResultadoLogin.Falla -> EstadoLogin.Error(r.mensaje)
            }
        }
    }
}

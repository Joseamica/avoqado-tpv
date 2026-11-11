package com.jaac.avoqado_campo.entrada

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.identidad.Sucursal
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EstadoEntrada(
    val nombre: String? = null,
    /** Con más de una, la pantalla del PIN deja elegir. */
    val sucursales: List<Sucursal> = emptyList(),
    val venueIdElegido: String? = null,
    /** Mientras corre, el botón está deshabilitado (R3-3). */
    val trabajando: Boolean = false,
    val mensaje: String? = null,
    /** B14: hay una activación cuyo resultado no se supo; la pantalla no deja escribir otra. */
    val terminandoActivacion: Boolean = false,
    val dentro: Boolean = false,
    val dentroSinSenal: Boolean = false,
)

@HiltViewModel
class EntradaViewModel @Inject constructor(
    private val repo: RepositorioDeEntrada,
    quienSoy: QuienSoy,
) : ViewModel() {
    private val _estado = MutableStateFlow(
        quienSoy.sucursales.let { s ->
            EstadoEntrada(
                nombre = quienSoy.nombre,
                sucursales = s,
                venueIdElegido = quienSoy.sesion?.venueId?.takeIf { v -> s.any { it.venueId == v } } ?: s.singleOrNull()?.venueId,
                terminandoActivacion = repo.activacionPendiente(),
            )
        },
    )
    val estado: StateFlow<EstadoEntrada> = _estado

    fun activar(codigo: String, pin: String) = correr { repo.activar(codigo, pin) }

    fun entrar(pin: String) = correr { repo.entrar(pin, _estado.value.venueIdElegido) }

    fun elegirSucursal(venueId: String) = _estado.update { it.copy(venueIdElegido = venueId, mensaje = null) }

    private fun correr(accion: suspend () -> ResultadoEntrada) {
        if (_estado.value.trabajando) return // doble toque: una sola llamada
        _estado.update { it.copy(trabajando = true, mensaje = null) }
        viewModelScope.launch {
            val r = accion()
            _estado.update { aplicar(it.copy(trabajando = false, terminandoActivacion = repo.activacionPendiente()), r) }
        }
    }

    private fun aplicar(e: EstadoEntrada, r: ResultadoEntrada): EstadoEntrada = when (r) {
        ResultadoEntrada.Dentro -> e.copy(dentro = true)
        is ResultadoEntrada.DentroSinSenal -> e.copy(dentro = true, dentroSinSenal = true)
        is ResultadoEntrada.EligeSucursal ->
            e.copy(sucursales = r.sucursales, venueIdElegido = null, mensaje = "Elige en qué sucursal vas a trabajar.")
        is ResultadoEntrada.Rechazo -> e.copy(mensaje = r.mensaje)
        is ResultadoEntrada.Bloqueado -> e.copy(mensaje = "Espera ${maxOf(1, (r.reintentaEnSeg + 59) / 60)} min para volver a intentar.")
        ResultadoEntrada.ColaNoVacia -> e.copy(mensaje = "Primero sube lo pendiente de este celular.")
        ResultadoEntrada.SinSenalSinVerificador ->
            e.copy(mensaje = "Sin señal todavía no puedes entrar aquí. Entra una vez con señal.")
    }
}

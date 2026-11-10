package com.jaac.avoqado_campo.checada

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_campo.cola.CelularBorrandose
import com.jaac.avoqado_campo.cola.ChecadaNueva
import com.jaac.avoqado_campo.cola.Disparador
import com.jaac.avoqado_campo.cola.Encolador
import com.jaac.avoqado_campo.cola.PiezaDao
import com.jaac.avoqado_campo.copia.RepositorioDeCopia
import com.jaac.avoqado_campo.gps.ControlDeGps
import com.jaac.avoqado_campo.gps.EstadoDelGps
import com.jaac.avoqado_campo.gps.ResultadoGps
import com.jaac.avoqado_campo.identidad.QuienSoy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

enum class TipoChecada { ENTRADA, SALIDA }

data class EstadoChecada(
    /** La entrada abierta de este celular y de esta persona; null = no hay (B10). */
    val entrada: RefEntrada? = null,
    /** La pantalla abre la cámara frontal para esta checada (B9). */
    val pideSelfie: TipoChecada? = null,
    val ocupado: Boolean = false,
    val buscandoUbicacion: Boolean = false,
    val avisoUbicacion: String? = null,
    /** Lo que pasó con la checada que se acaba de guardar (incluido el GPS que no sigue, R2-7). */
    val aviso: String? = null,
    val error: String? = null,
    /** El último estado del seguimiento (R4-2): con turno abierto y `NoSigue`, la pantalla lo dice. */
    val gps: ResultadoGps? = null,
)

@HiltViewModel
class ChecadaViewModel @Inject constructor(
    private val encolador: Encolador,
    private val ubicacion: Ubicacion,
    private val gps: ControlDeGps,
    estadoDelGps: EstadoDelGps,
    private val copia: RepositorioDeCopia,
    dao: PiezaDao,
    private val quienSoy: QuienSoy,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoChecada())
    val estado: StateFlow<EstadoChecada> = _estado

    init {
        viewModelScope.launch { dao.turno().collect { t -> _estado.update { it.copy(entrada = refEntrada(t, quienSoy.staffId)) } } }
        viewModelScope.launch { estadoDelGps.estado.collect { g -> _estado.update { it.copy(gps = g) } } }
    }

    fun marcarEntrada() = empezar(TipoChecada.ENTRADA)

    fun marcarSalida() = empezar(TipoChecada.SALIDA)

    /** [archivo] = la selfie ya comprimida; la checada sigue sola. */
    fun alTomarSelfie(archivo: String) {
        val tipo = _estado.value.pideSelfie
        if (tipo == null) File(archivo).delete() else checar(tipo, archivo)
    }

    fun cancelarSelfie() = _estado.update { it.copy(pideSelfie = null) }

    private fun empezar(tipo: TipoChecada) {
        val e = _estado.value
        if (e.ocupado || e.pideSelfie != null) return
        val error = when {
            quienSoy.sesion?.venueId == null -> "Entra con tu PIN para checar."
            tipo == TipoChecada.ENTRADA && e.entrada != null -> YA_HAY_ENTRADA
            tipo == TipoChecada.SALIDA && e.entrada == null -> SIN_ENTRADA
            else -> null
        }
        if (error != null) { _estado.update { it.copy(error = error, aviso = null) }; return }
        val config = copia.copia.value?.config
        val selfie = if (tipo == TipoChecada.ENTRADA) config?.fotoEntrada == true else config?.fotoSalida == true
        if (selfie) _estado.update { it.copy(pideSelfie = tipo, error = null, aviso = null) } else checar(tipo, null)
    }

    /** Doble toque → una pieza: [EstadoChecada.ocupado] se pone antes de lanzar nada. */
    private fun checar(tipo: TipoChecada, foto: String?) {
        val venueId = quienSoy.sesion?.venueId
        if (_estado.value.ocupado || venueId == null) return
        _estado.update {
            it.copy(ocupado = true, pideSelfie = null, buscandoUbicacion = true, avisoUbicacion = null, aviso = null, error = null)
        }
        viewModelScope.launch {
            val l = ubicacion.leer()
            _estado.update { it.copy(buscandoUbicacion = false, avisoUbicacion = if (l == null) "Se checa sin ubicación" else null) }
            val nueva = ChecadaNueva(
                venueId, l?.lat, l?.lon, l?.precision?.toDouble(), l?.falsa, foto,
                if (tipo == TipoChecada.ENTRADA) "Entrada" else "Salida",
            )
            val error = try {
                withContext(NonCancellable) { if (tipo == TipoChecada.ENTRADA) encolador.entrada(nueva) else encolador.salida(nueva) }
                null
            } catch (c: CancellationException) {
                throw c
            } catch (_: CelularBorrandose) {
                "Espera un momento."
            } catch (_: IllegalStateException) {
                if (tipo == TipoChecada.ENTRADA) YA_HAY_ENTRADA else SIN_ENTRADA
            } catch (_: IllegalArgumentException) {
                "Tu entrada es de otra sucursal: marca la salida ahí."
            } catch (x: Exception) {
                "No se guardó la checada. ${x.message.orEmpty()}".trim()
            }
            if (error != null) {
                foto?.let { File(it).delete() } // no se guardó: la selfie no se queda en el celular
                _estado.update { it.copy(ocupado = false, error = error) }
                return@launch
            }
            // Ya está guardada: nada de aquí abajo la deshace.
            val aviso = withContext(NonCancellable) {
                val r = despues { if (tipo == TipoChecada.ENTRADA) gps.iniciar() else { gps.detener(); null } }
                despues { Disparador.ahora(context) }
                (r as? ResultadoGps.NoSigue)?.let { "La checada quedó guardada · no estamos siguiendo tu ubicación: ${it.motivo}" }
                    ?: if (tipo == TipoChecada.ENTRADA) "Entrada guardada · se sube sola" else "Salida guardada · se sube sola"
            }
            _estado.update { it.copy(ocupado = false, aviso = aviso) }
        }
    }

    private fun <T> despues(f: () -> T): T? = try { f() } catch (x: Exception) { Log.w("Checada", "checada guardada; falló lo de después", x); null }

    private companion object {
        const val YA_HAY_ENTRADA = "Ya tienes una entrada abierta"
        const val SIN_ENTRADA = "Marca tu salida donde marcaste la entrada"
    }
}

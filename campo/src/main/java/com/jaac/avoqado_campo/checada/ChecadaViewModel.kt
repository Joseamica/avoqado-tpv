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
import com.jaac.avoqado_campo.cola.TipoPieza
import com.jaac.avoqado_campo.copia.RepositorioDeCopia
import com.jaac.avoqado_campo.gps.ControlDeGps
import com.jaac.avoqado_campo.gps.EstadoDelGps
import com.jaac.avoqado_campo.gps.ResultadoGps
import com.jaac.avoqado_campo.identidad.QuienSoy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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
        // Sin copia no se sabe lo que pide la sucursal: la selfie se pide igual (B9).
        val config = copia.copia.value?.config
        val selfie = config == null || (if (tipo == TipoChecada.ENTRADA) config.fotoEntrada else config.fotoSalida)
        if (selfie) _estado.update { it.copy(pideSelfie = tipo, error = null, aviso = null) } else checar(tipo, null)
    }

    /** Doble toque → una pieza: [EstadoChecada.ocupado] se pone antes de lanzar nada. */
    private fun checar(tipo: TipoChecada, foto: String?) {
        // R6-A1 + r3: generación y sesión de UNA instantánea: lo que se guarde es de ESTA persona.
        val yo = quienSoy.instantanea()
        val venueId = yo.sesion?.venueId
        // H5: no se checa; la selfie ya tomada no se queda en el celular.
        if (_estado.value.ocupado || venueId == null) { foto?.let { File(it).delete() }; return }
        _estado.update {
            it.copy(ocupado = true, pideSelfie = null, buscandoUbicacion = true, avisoUbicacion = null, aviso = null, error = null)
        }
        // Salir de la pantalla durante la espera del GPS no tira la checada: todo (ubicación y guardado) sigue (NonCancellable).
        // UNDISPATCHED: corre aunque la pantalla ya se haya cerrado.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { checarYa(tipo, foto, venueId, yo.generacion) }
        }
    }

    private suspend fun checarYa(tipo: TipoChecada, foto: String?, venueId: String, generacion: String) {
        val pieza = if (tipo == TipoChecada.ENTRADA) TipoPieza.ENTRADA else TipoPieza.SALIDA
        // r5: lo que la persona tocó se guarda ANTES de esperar el GPS (APARTADA, con la selfie): si Android mata la app aquí, al
        // abrir se completa sin ubicación, y un borrado la cuenta como viva.
        val apartada = try {
            encolador.apartarChecada(generacion, pieza, venueId, foto, if (tipo == TipoChecada.ENTRADA) "Entrada" else "Salida")
        } catch (c: CancellationException) {
            throw c
        } catch (x: Exception) {
            foto?.let { File(it).delete() } // no se guardó: la selfie no se queda en el celular
            _estado.update { it.copy(ocupado = false, buscandoUbicacion = false, error = porque(x, tipo)) }
            return
        }
        // B9: si no llega, se checa igual. Un error del GPS (p. ej. el permiso retirado a media lectura) es «sin ubicación»: no
        // deja la pantalla ocupada para siempre.
        val l = try { ubicacion.leer() } catch (c: CancellationException) { throw c } catch (x: Exception) { null }
        _estado.update { it.copy(buscandoUbicacion = false, avisoUbicacion = if (l == null) "Se checa sin ubicación" else null) }
        val fallo = completarAunqueHayaUnBorrado(generacion, apartada, ChecadaNueva(venueId, l?.lat, l?.lon, l?.precision?.toDouble(), l?.falsa, null, ""))
        if (fallo != null) {
            val error = porque(fallo, tipo)
            // r6: no se borra (invariante 2 de BorradoDelCelular): queda en «Mis pendientes», con su foto. Si ya no existe es
            // porque un borrado que sí siguió la borró.
            encolador.rechazarApartada(apartada, error)
            _estado.update { it.copy(ocupado = false, error = error) }
            return
        }
        // Ya está guardada: nada de aquí abajo la deshace.
        val aviso = run {
            val r = despues { if (tipo == TipoChecada.ENTRADA) gps.iniciar() else { gps.detener(); null } }
            despues { Disparador.ahora(context) }
            (r as? ResultadoGps.NoSigue)?.let { "La checada quedó guardada · no estamos siguiendo tu ubicación: ${it.motivo}" }
                ?: if (tipo == TipoChecada.ENTRADA) "Entrada guardada · se sube sola" else "Salida guardada · se sube sola"
        }
        _estado.update { it.copy(ocupado = false, aviso = aviso) }
    }

    // inline: deja llamar `gps.iniciar()`, que lee el turno (suspend). Corre en NonCancellable: no hay cancelación que tragar.
    private inline fun <T> despues(f: () -> T): T? = try { f() } catch (x: Exception) { Log.w("Checada", "checada guardada; falló lo de después", x); null }

    /**
     * r6: un borrado en su ventana (la marca puesta) hace fallar el completar con `CelularBorrandose`: se espera a que termine
     * y se repite. Si se negó, la generación volvió a ser la del toque y esto la completa; si siguió, la fila ya no existe
     * (la borró él) y se sale. Un `CelularBorrandose` sin borrado en curso, ya repetido, no es transitorio: se devuelve.
     * null = completada. ponytail: espera revisando cada medio segundo; el borrado dura lo que tarda en vaciar Room.
     */
    private suspend fun completarAunqueHayaUnBorrado(generacion: String, id: Long, u: ChecadaNueva): Exception? {
        var repetida = false
        while (true) {
            val x = try { encolador.completarChecada(generacion, id, u); return null } catch (c: CancellationException) { throw c } catch (x: Exception) { x }
            if (x !is CelularBorrandose || (!quienSoy.limpiando && repetida)) return x
            repetida = true
            while (quienSoy.limpiando) delay(500)
        }
    }

    private fun porque(x: Exception, tipo: TipoChecada) = when (x) {
        is CelularBorrandose -> "Espera un momento."
        is IllegalStateException -> if (tipo == TipoChecada.ENTRADA) YA_HAY_ENTRADA else SIN_ENTRADA
        is IllegalArgumentException -> "Tu entrada es de otra sucursal: marca la salida ahí."
        else -> "No se guardó la checada. ${x.message.orEmpty()}".trim()
    }

    private companion object {
        const val YA_HAY_ENTRADA = "Ya tienes una entrada abierta"
        const val SIN_ENTRADA = "Marca tu salida donde marcaste la entrada"
    }
}

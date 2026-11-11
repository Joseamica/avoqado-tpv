package com.jaac.avoqado_campo.ayuda

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_campo.cola.PiezaDao
import com.jaac.avoqado_campo.copia.RepositorioDeCopia
import com.jaac.avoqado_campo.identidad.BorradoDelCelular
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.latido.Latido
import com.jaac.avoqado_campo.red.CampoApi
import com.jaac.avoqado_campo.red.Conectividad
import com.jaac.avoqado_campo.red.DesvincularBody
import com.jaac.avoqado_campo.red.Llamada
import com.jaac.avoqado_campo.red.codigoDe
import com.jaac.avoqado_campo.red.llamarSeguro
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

data class EstadoAyuda(val porSubir: Int = 0, val trabajando: Boolean = false, val mensaje: String? = null)

private const val SIN_SENAL = "Necesitas señal para esto"

/** Spec §4.7: actualizar datos, cerrar sesión (B5: no borra tokens) y desvincular (sólo con la cola vacía y señal). */
@HiltViewModel
class AyudaViewModel @Inject constructor(
    private val quienSoy: QuienSoy,
    private val dao: PiezaDao,
    private val copia: RepositorioDeCopia,
    private val latido: Latido,
    private val api: CampoApi,
    private val borrado: BorradoDelCelular,
    private val conectividad: Conectividad,
) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoAyuda())
    val estado: StateFlow<EstadoAyuda> = _estado

    init {
        viewModelScope.launch { dao.cuantasPorSubir().collect { n -> _estado.update { it.copy(porSubir = n.total) } } }
    }

    fun actualizarDatos() = correr {
        if (!conectividad.hayRed()) return@correr SIN_SENAL
        val v = quienSoy.sesion?.venueId
        val ok = v != null && copia.actualizar(v)
        latido.mandar()
        if (ok) "Listo: tus datos están al día." else "No se pudo actualizar. Intenta otra vez."
    }

    /** B5: la próxima vez pide el PIN; la cola sigue subiendo con los tokens. */
    fun cerrarSesion() = quienSoy.marcarBloqueada(true)

    /**
     * r3 #1: la persona que se desvincula es la de la instantánea del toque; con el 200, el borrado vuelve a contar la cola con
     * su marca puesta y, si en el vuelo se guardó algo, no borra: el celular queda DESVINCULADO y eso sube por la gracia.
     */
    fun desvincular() = correr {
        val yo = quienSoy.instantanea()
        if (yo.limpiando) return@correr "Espera un momento."
        val n = dao.cuantasPorSubir().first().total
        if (n > 0) return@correr "Primero sube lo pendiente ($n)"
        if (!conectividad.hayRed()) return@correr SIN_SENAL
        val credencial = yo.credencial ?: return@correr "Este celular no está activado."
        when (val ll = llamarSeguro { api.desvincular(credencial, DesvincularBody(porSubir = 0)) }) {
            is Llamada.Contesto -> when {
                ll.r.isSuccessful -> {
                    ll.r.body()?.close()
                    if (borrado.trasDesvincular(yo.generacion)) null
                    else "Se guardó algo mientras se desvinculaba: se sube para que tu supervisor lo revise."
                }
                codigoDe(ll.r.errorBody()?.string()) == "TIENE_POR_SUBIR" -> "Primero sube lo pendiente."
                else -> "No se pudo desvincular (HTTP ${ll.r.code()}). Intenta otra vez."
            }
            else -> SIN_SENAL
        }
    }

    private fun correr(bloque: suspend () -> String?) {
        if (_estado.value.trabajando) return
        _estado.update { it.copy(trabajando = true, mensaje = null) }
        viewModelScope.launch {
            var m: String? = null
            try {
                m = bloque()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Ayuda", "acción de Ayuda", e)
                m = "No se pudo, intenta otra vez"
            } finally {
                _estado.update { it.copy(trabajando = false, mensaje = m) }
            }
        }
    }
}

package com.jaac.avoqado_campo.gps

import com.jaac.avoqado_campo.identidad.AlmacenSeguro
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Lo último que se supo del seguimiento (R4-2), guardado en el almacén: lo leen el Inicio y la checada. */
class EstadoDelGps(private val almacen: AlmacenSeguro) {
    private val _estado = MutableStateFlow(leer())
    val estado: StateFlow<ResultadoGps> = _estado

    @Synchronized fun publicar(r: ResultadoGps) {
        almacen.escribir(mapOf(CLAVE to if (r is ResultadoGps.NoSigue) NO_SIGUE + r.motivo else SIGUIENDO))
        _estado.value = r
    }

    /** Lo llama el borrado del celular. */
    @Synchronized fun reiniciar() {
        almacen.escribir(mapOf(CLAVE to null))
        _estado.value = SIN_INICIAR
    }

    private fun leer(): ResultadoGps = when (val v = almacen.leer(CLAVE)) {
        null -> SIN_INICIAR
        SIGUIENDO -> ResultadoGps.Siguiendo
        else -> ResultadoGps.NoSigue(v.removePrefix(NO_SIGUE))
    }

    companion object {
        val SIN_INICIAR = ResultadoGps.NoSigue("todavía no se inicia")
        private const val CLAVE = "gps.estado"
        private const val SIGUIENDO = "SIGUIENDO"
        private const val NO_SIGUE = "NO_SIGUE:"
    }
}

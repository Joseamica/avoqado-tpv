package com.jaac.avoqado_campo.gps

import com.jaac.avoqado_campo.identidad.AlmacenSeguro
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Lo último que se supo del seguimiento (R4-2), guardado en el almacén: lo leen el Inicio y la checada. */
class EstadoDelGps(private val almacen: AlmacenSeguro) {
    private val _estado = MutableStateFlow(leer())
    val estado: StateFlow<ResultadoGps> = _estado

    @Synchronized fun publicar(r: ResultadoGps) {
        val valor = when (r) {
            is ResultadoGps.NoSigue -> NO_SIGUE + r.motivo
            ResultadoGps.Siguiendo -> SIGUIENDO
            ResultadoGps.NoLoUsaLaTienda -> NO_LO_USA
        }
        runCatching { almacen.escribir(mapOf(CLAVE to valor)) } // r10: sólo lo que muestra la pantalla; si no se guarda, en memoria sí
        _estado.value = r
    }

    /** Lo llama el borrado del celular. */
    @Synchronized fun reiniciar() {
        runCatching { almacen.escribir(mapOf(CLAVE to null)) }
        _estado.value = SIN_INICIAR
    }

    private fun leer(): ResultadoGps = when (val v = almacen.leer(CLAVE)) {
        null -> SIN_INICIAR
        SIGUIENDO -> ResultadoGps.Siguiendo
        NO_LO_USA -> ResultadoGps.NoLoUsaLaTienda
        else -> ResultadoGps.NoSigue(v.removePrefix(NO_SIGUE))
    }

    companion object {
        val SIN_INICIAR = ResultadoGps.NoSigue("todavía no se inicia")
        private const val CLAVE = "gps.estado"
        private const val SIGUIENDO = "SIGUIENDO"
        private const val NO_SIGUE = "NO_SIGUE:"
        private const val NO_LO_USA = "NO_LO_USA_LA_TIENDA"
    }
}

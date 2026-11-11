package com.jaac.avoqado_campo.identidad

import android.os.SystemClock
import java.time.Instant

data class HoraDeCampo(val iso: String, val origen: String /* RELOJ_ANCLADO | RELOJ_DEL_CELULAR */, val epochMs: Long)

/**
 * Hora del servidor que sigue corriendo con el reloj monotónico: cambiar la hora del celular no la mueve.
 * Si el celular se reinició o el monotónico retrocedió, el ancla ya no sirve y se usa la hora del celular.
 */
class RelojAnclado(
    private val almacen: AlmacenSeguro,
    private val ahoraDelCelular: () -> Long = System::currentTimeMillis,
    private val transcurrido: () -> Long = SystemClock::elapsedRealtime,
    private val arranque: () -> Int,
) {
    fun anclar(horaDelServidorIso: String) {
        val ms = Instant.parse(horaDelServidorIso).toEpochMilli()
        almacen.escribir(mapOf(CLAVE to "$ms|${transcurrido()}|${arranque()}"))
    }

    fun ahora(): HoraDeCampo {
        val p = almacen.leer(CLAVE)?.split("|")
        if (p != null && p.size == 3) {
            val servidor = p[0].toLongOrNull()
            val t0 = p[1].toLongOrNull()
            val a = p[2].toIntOrNull()
            val t = transcurrido()
            if (servidor != null && t0 != null && a == arranque() && t >= t0) {
                return hora(servidor + (t - t0), "RELOJ_ANCLADO")
            }
        }
        return hora(ahoraDelCelular(), "RELOJ_DEL_CELULAR")
    }

    private fun hora(ms: Long, origen: String) = HoraDeCampo(Instant.ofEpochMilli(ms).toString(), origen, ms)

    private companion object { const val CLAVE = "reloj.ancla" }
}

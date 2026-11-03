package com.jaac.avoqado_tpv.core.util

import android.os.Bundle
import java.util.UUID

/**
 * 🔒 D1 y D2 («ninguna duda de cobro sin salida», QA en la N86, 2-nov): un proceso NUEVO nunca restaura una pantalla.
 *
 * La app moría con la pantalla del SDK de AngelPay arriba y Android la revivía en ~1 s restaurando «Método de Pago» del MISMO
 * cobro: sólo el Inicio y el Login conectan el socket, así que sin socket no había sonda y la terminal no se soltaba sola (D1), y
 * esa pantalla dejaba tocar «Tarjeta» y abrir OTRO lector de la misma solicitud con el intento anterior en duda (D2). Lo que debe
 * sobrevivir a la muerte del proceso ya vive en Room (libreta, bandeja, cola, venta guardada): la app arranca como en frío
 * (Splash → Inicio), y el Inicio conecta, certifica el cese y suelta la terminal.
 *
 * Quien guardó el estado lo FIRMA con el token de su proceso, en vez de contar «la primera creación del proceso»: esa cuenta la
 * gastaría la copia que el launcher de AngelPay crea y cierra enseguida (`MainActivity.onCreate`), y con dos `MainActivity`
 * apiladas la segunda restauraría la pantalla de cobro. Una rotación o recreación dentro del MISMO proceso conserva su estado.
 */
internal object EstadoDelProceso {
    const val CLAVE = "avoqado_proceso_que_guardo_este_estado"

    /** Nace con el proceso y muere con él. */
    private val ESTE_PROCESO = UUID.randomUUID().toString()

    /** Va en `onSaveInstanceState`: sin firma, la recreación dentro del mismo proceso también se descartaría. */
    fun firmar(estado: Bundle) = estado.putString(CLAVE, ESTE_PROCESO)

    /** Lo que se le pasa a `super.onCreate`: el estado sólo si lo guardó ESTE proceso; si no, null, como un arranque en frío. */
    fun paraCrear(guardado: Bundle?, esteProceso: String = ESTE_PROCESO): Bundle? =
        guardado?.takeIf { it.getString(CLAVE) == esteProceso }
}

package com.jaac.avoqado_tpv.core.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import timber.log.Timber
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

/**
 * 🔒 I-1 (revisión de D1/D2, 3-nov): la pantalla del SDK de AngelPay tampoco se restaura en un proceso nuevo.
 *
 * Si `PaymentActivity` estaba DETENIDA al morir el proceso (pantalla apagada u Home esperando tarjeta), Android la recrea con su Intent.
 * Su `onCreate` (AAR 1.0.22) le pasa el estado sólo a `super` y relanza el cobro con `angelpay_req`, sin pasar por la libreta: otro lector
 * de la misma solicitud con el intento anterior en duda, y un aprobado que ya no le llega a nadie, porque `MainActivity` arranca como en
 * frío ([EstadoDelProceso]).
 *
 * Aquí se le quita ese extra al Intent. `onActivityCreated` corre dentro de `super.onCreate`, ANTES de que el AAR lo lea, y sin él termina
 * con «Missing request» sin tocar el lector. El intento sigue en duda en la libreta, sin «no se cobró». Una recreación con el proceso vivo
 * va firmada y sigue con su cobro. La clase va por nombre porque en la PAX no existe. Depende de ese `onCreate`: lo fija
 * `EstadoDelProcesoTest` contra el AAR real, y subir el AAR obliga a revisarlo.
 */
internal class PantallaDelSdkNoSeRestaura(
    private val pantallaDelSdk: String = NOMBRE,
) : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, guardado: Bundle?) {
        if (activity.javaClass.name == pantallaDelSdk && guardado != null && EstadoDelProceso.paraCrear(guardado) == null) {
            Timber.w("🧭 [PantallaDelSdk] Proceso nuevo: la pantalla del SDK restaurada ya no vuelve a abrir el lector")
            activity.intent?.removeExtra(EXTRA_DEL_COBRO)
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        if (activity.javaClass.name == pantallaDelSdk) EstadoDelProceso.firmar(outState)
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        const val NOMBRE = "com.angelpay.angelpaysdk.ui.PaymentActivity"
        const val EXTRA_DEL_COBRO = "angelpay_req"
    }
}

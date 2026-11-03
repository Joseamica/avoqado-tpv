package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.ActivityManager
import android.content.Context
import com.jaac.avoqado_tpv.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * 🔒 Codex TPV r1 #1 (22-oct, «ninguna duda de cobro sin salida»): las pantallas que la app tiene apiladas AHORA.
 *
 * El certificado de cese ([PaymentAttemptLedger.cerrarSolicitudSiCeso]) supone que un intento de un proceso muerto ya no puede
 * volver a ejecutarse. Eso es falso si Android puede RESTAURAR la pantalla del SDK: `PaymentActivity.onCreate` del AAR de
 * AngelPay 1.0.21 recupera `angelpay_req` de su Intent y vuelve a lanzar el cobro, sin distinguir una restauración
 * (`savedInstanceState`) de un arranque. Con dos `MainActivity` apiladas y la pantalla del SDK entre ellas, matar el proceso y
 * restaurar la de arriba conectaba el socket y certificaba; al volver atrás, el SDK relanzaba el cobro de una solicitud ya
 * cerrada, sin pasar por la libreta. Por eso la libreta sólo certifica cuando la app tiene UNA pantalla por tarea y es la nuestra.
 * Desde I-1 (3-nov) esa pantalla ya no relanza en un proceso nuevo (`PantallaDelSdkNoSeRestaura`); esto queda de defensa: mientras
 * siga apilada no se certifica.
 *
 * Lanza si no se puede leer: quien pregunta lo trata como «no certifica» (fail-closed).
 */
fun interface PilaDePantallas {
    fun tareas(): List<TareaDeLaApp>

    companion object {
        /** Sin cablear (un `new` a mano, sin Hilt): no se sabe qué hay apilado, así que nunca certifica. */
        val SIN_CABLEAR = PilaDePantallas { error("pila de pantallas sin cablear") }
    }
}

/** Una tarea de la app: cuántas pantallas tiene y si la de arriba es nuestra [MainActivity]. */
data class TareaDeLaApp(val pantallas: Int, val arribaLaPrincipal: Boolean)

/** La pila real, de `ActivityManager.appTasks` (`numActivities` y `topActivity` de cada tarea). */
class PilaDePantallasDeAndroid @Inject constructor(
    @ApplicationContext private val context: Context,
) : PilaDePantallas {
    override fun tareas(): List<TareaDeLaApp> {
        val manager = context.getSystemService(ActivityManager::class.java) ?: error("sin ActivityManager")
        return manager.appTasks.map { tarea ->
            val info: ActivityManager.RecentTaskInfo = tarea.taskInfo
            TareaDeLaApp(
                pantallas = info.numActivities,
                arribaLaPrincipal = info.topActivity?.className == MainActivity::class.java.name,
            )
        }
    }
}

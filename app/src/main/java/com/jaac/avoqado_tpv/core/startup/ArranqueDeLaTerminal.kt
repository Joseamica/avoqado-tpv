package com.jaac.avoqado_tpv.core.startup

import com.jaac.avoqado_tpv.core.data.network.dto.ActivationStatusResponse
import com.jaac.avoqado_tpv.core.domain.models.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Qué pantalla sigue al logo de «Avoqado TPV» (la SplashScreen de AppNavigation), sin dejar al cajero
 * esperando a un SERVIDOR que no contesta (encargo 097e01, 21-nov-2026).
 *
 * **Antes:** el logo esperaba DOS consultas en fila —activación y módulos—, cada una con el tope de 25 s de
 * OkHttp (`callTimeout`), que además no cuenta el tiempo que la consulta pasa haciendo fila detrás de otras al
 * mismo servidor (config, latido, cola de cobros). Con el SERVIDOR mudo (Wi-Fi bien, nadie contesta) se midieron
 * ~80 s de logo en la PAX.
 *
 * **Ahora:**
 * - Si la terminal ya está activada EN EL APARATO, las dos consultas comparten una espera de a lo más [ESPERA_MAXIMA].
 *   Si el servidor contesta a tiempo, todo es como antes. Si no:
 *   - la consulta de activación sigue al fondo SIN efectos (Codex r1 #2 y r3): una respuesta tardía nunca borra la
 *     sesión ni repone datos con el cajero ya cobrando; sólo se anota. Una baja (RETIRED) la sigue atrapando el latido;
 *   - los módulos se piden (o se siguen pidiendo) al fondo, una vez, como antes; si fallan, se quedan los guardados.
 *     El repositorio sólo guarda la respuesta más nueva (Codex r1 #4).
 *   Y se sigue con lo guardado, igual que el camino «sin red» de siempre: Inicio con sesión, PIN sin ella.
 * - Si el aparato NO tiene la activación, se espera al servidor completo, como antes: sólo él puede restaurarla
 *   (reinstalación, caché borrada) y sin ella no hay a dónde ir más que a Activar.
 *
 * Lo que se cede: si el servidor tarda más de [ESPERA_MAXIMA], un «no activada» o «RETIRED» de ESA consulta ya no se
 * aplica en este arranque (pasaba igual con el servidor caído).
 */
object ArranqueDeLaTerminal {

    enum class Destino { ACTIVACION, LOGIN, INICIO }

    /** Lo más que el logo espera al SERVIDOR cuando el aparato ya sabe a qué negocio pertenece. */
    val ESPERA_MAXIMA: Duration = 4.seconds

    /**
     * Donde siguen los módulos cuando se vence la espera: vive con el PROCESO, no con la pantalla ni la actividad
     * (Codex r1 #6: recrear la actividad no la cancela). Muere con el proceso; el siguiente arranque vuelve a pedir.
     */
    val alFondoDelProceso: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * @param alFondo alcance donde los módulos siguen si se vence la espera ([alFondoDelProceso] en la app).
     * @param consultarActivacion sólo pregunta, sin efectos (`consultarActivacionSinEfectos`).
     * @param aplicarActivacion lo que esa respuesta cambia en el aparato (`aplicarRespuestaDeActivacion`): sólo se aplica
     *   a una respuesta que llegó dentro de la espera.
     * @param traerModulos `modulesRepository.fetchAndCache()`; si falla, se quedan los módulos guardados.
     * @param hiloDeRed donde corre la consulta de activación (IO: sin congelar la animación del logo; las pruebas ponen el
     *   suyo para el tiempo virtual).
     */
    suspend fun decidir(
        alFondo: CoroutineScope,
        activadaEnElAparato: () -> Boolean,
        haySesion: () -> Boolean,
        consultarActivacion: suspend () -> Result<ActivationStatusResponse>,
        aplicarActivacion: (ActivationStatusResponse) -> Unit,
        traerModulos: suspend () -> Unit,
        esperaMaxima: Duration = ESPERA_MAXIMA,
        hiloDeRed: CoroutineContext = Dispatchers.IO,
    ): Destino {
        fun segunLaSesion() = if (haySesion()) Destino.INICIO else Destino.LOGIN

        // Sin la activación en el aparato sólo el servidor sabe a dónde ir: se le espera completo, como antes.
        if (!activadaEnElAparato()) {
            val respuesta = withContext(hiloDeRed) { consultarActivacion() }
            if (respuesta is Result.Success) aplicarActivacion(respuesta.data)
            return when (respuesta) {
                is Result.Success -> if (!respuesta.data.isActivated) Destino.ACTIVACION else {
                    traerModulos()
                    segunLaSesion()
                }
                is Result.Error -> if (!activadaEnElAparato()) Destino.ACTIVACION else {
                    traerModulos()
                    segunLaSesion()
                }
            }
        }

        // UNA espera para las dos consultas (Codex r3: rediseño). La consulta de activación NUNCA se cancela: corre al fondo
        // y sólo pregunta. Al vencer la espera se mira lo que ya contestó: si contestó, se aplica y se decide como antes;
        // si no, cuenta como tarde (aunque sea por milisegundos) y lo que diga después sólo se anota. Así ninguna respuesta
        // que llegó a tiempo se pierde en una cancelación, y ninguna tardía borra la sesión con el cajero cobrando.
        val contestada = AtomicReference<Result<ActivationStatusResponse>?>(null)
        val consulta = alFondo.async { withContext(hiloDeRed) { consultarActivacion() }.also { contestada.set(it) } }
        var modulos: Deferred<Unit>? = null
        val aTiempo = withTimeoutOrNull(esperaMaxima) {
            val r = consulta.await()
            if (r is Result.Success && !r.data.isActivated) return@withTimeoutOrNull
            val enCamino = modulosDelProceso(alFondo, traerModulos)
            modulos = enCamino
            enCamino.await()
        } != null

        val respuesta = contestada.get()
        if (respuesta == null) {
            Timber.w("🚀 [Arranque] El SERVIDOR no contestó en ${esperaMaxima.inWholeSeconds} s: se sigue con lo guardado")
            consulta.invokeOnCompletion { error ->
                val tarde = contestada.get()
                if (error == null && tarde is Result.Success) {
                    Timber.w("🚀 [Arranque] El SERVIDOR contestó tarde (isActivated=${tarde.data.isActivated}, status=${tarde.data.status}): no cambia este arranque")
                }
            }
        } else if (respuesta is Result.Success) {
            aplicarActivacion(respuesta.data)
            if (!respuesta.data.isActivated) return Destino.ACTIVACION
        }
        if (!aTiempo && modulos == null) modulosDelProceso(alFondo, traerModulos)
        else if (!aTiempo) Timber.w("🚀 [Arranque] Los módulos siguen al fondo")

        // RETIRED (aplicado arriba) borra los datos: se vuelve a mirar el aparato antes de decidir.
        if (!activadaEnElAparato()) return Destino.ACTIVACION
        return segunLaSesion()
    }

    /**
     * Una sola consulta de módulos del arranque a la vez en el proceso (Codex r2 #4): si la actividad se recrea con el logo
     * visible, el logo nuevo espera la que ya iba en camino en vez de lanzar otra.
     */
    private var modulosEnCamino: Deferred<Unit>? = null

    private fun modulosDelProceso(alFondo: CoroutineScope, traerModulos: suspend () -> Unit): Deferred<Unit> =
        synchronized(this) {
            modulosEnCamino?.takeIf { it.isActive }
                ?: alFondo.async { traerModulos() }.also { modulosEnCamino = it }
        }
}

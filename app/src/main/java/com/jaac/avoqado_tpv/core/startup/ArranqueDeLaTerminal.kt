package com.jaac.avoqado_tpv.core.startup

import com.jaac.avoqado_tpv.core.data.network.dto.ActivationStatusResponse
import com.jaac.avoqado_tpv.core.domain.models.Result
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Qué pantalla sigue al logo de «Avoqado TPV» (la SplashScreen de AppNavigation).
 */
object ArranqueDeLaTerminal {

    enum class Destino { ACTIVACION, LOGIN, INICIO }

    val ESPERA_MAXIMA: Duration = 4.seconds

    suspend fun decidir(
        alFondo: CoroutineScope,
        activadaEnElAparato: () -> Boolean,
        haySesion: () -> Boolean,
        consultarActivacion: suspend () -> Result<ActivationStatusResponse>,
        traerModulos: suspend () -> Unit,
        esperaMaxima: Duration = ESPERA_MAXIMA,
    ): Destino {
        return when (val respuesta = consultarActivacion()) {
            is Result.Success -> if (!respuesta.data.isActivated) Destino.ACTIVACION else {
                traerModulos()
                if (haySesion()) Destino.INICIO else Destino.LOGIN
            }
            is Result.Error -> if (!activadaEnElAparato()) Destino.ACTIVACION else {
                traerModulos()
                if (haySesion()) Destino.INICIO else Destino.LOGIN
            }
        }
    }
}

package com.jaac.avoqado_tpv.core.presentation.navigation

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.withStarted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 🪑 hz:1450a1e9:D4 (25-nov) · TPV SOLA → MESAS. Tras cobrar una mesa, «Nueva Orden» libera la mesa por red y vuelve al plano. Antes las
 * dos cosas corrían en el alcance de la composición de la ruta de pago: si en ese rato se abría otra pantalla encima (el POS pide abrir
 * una devolución), Compose cancelaba la corrutina; la mesa no se liberaba y nadie volvía al plano (al regresar, la PAX se quedaba en «Un
 * momento…» y la Nexgo volvía a ofrecer la cuenta pagada).
 *
 * Se lanza en [alcance] (el ViewModel de la entrada: vive mientras la entrada siga en la pila):
 * - [liberar] siempre termina (`NonCancellable`), aunque el cajero salga a mano o la entrada se cierre a media espera;
 * - [alPlano] corre cuando la pantalla de cobro ([entrada]) está arriba (`withStarted`): con otra pantalla encima espera a que vuelva, y
 *   si la entrada ya se cerró no corre.
 */
internal fun salirDeMesaTrasCobrar(
    alcance: CoroutineScope,
    entrada: LifecycleOwner,
    liberar: suspend () -> Unit,
    alPlano: () -> Unit,
): Job = alcance.launch {
    withContext(NonCancellable) { liberar() }
    entrada.withStarted { alPlano() }
}

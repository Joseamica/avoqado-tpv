package com.jaac.avoqado_campo.cola

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jaac.avoqado_campo.identidad.EstadoDelCelular
import com.jaac.avoqado_campo.identidad.QuienSoy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Lo corre WorkManager (con red). Vinculado: la ruta normal, y si a media pasada lo desvinculan, la gracia en el mismo trabajo.
 * Desvinculado o dado de baja: la gracia. El latido después de la pasada lo agrega la Tarea 8.
 */
@HiltWorker
class TrabajoDeSubida @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val subidor: Subidor,
    private val gracia: SubidorDeGracia,
    private val quienSoy: QuienSoy,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val r = when (quienSoy.identidad.value.estado) {
            EstadoDelCelular.VINCULADO -> subidor.vaciar().let { if (it.modoGracia) gracia.vaciar() else it }
            EstadoDelCelular.DESVINCULADO, EstadoDelCelular.DADO_DE_BAJA -> gracia.vaciar()
            else -> return Result.success()
        }
        // Red, una pieza en espera o la gracia que devolvió la sesión (USA_TU_SESION: sigue la ruta normal) vuelven solos;
        // un PIN o una sucursal piden a la persona.
        return if (r.cortadoPor in REINTENTAR) Result.retry() else Result.success()
    }

    private companion object { val REINTENTAR = setOf("SIN_RED", "REINTENTABLE", SubidorDeGracia.USA_TU_SESION) }
}

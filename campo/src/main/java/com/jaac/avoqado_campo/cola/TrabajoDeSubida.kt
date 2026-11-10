package com.jaac.avoqado_campo.cola

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jaac.avoqado_campo.identidad.EstadoDelCelular
import com.jaac.avoqado_campo.identidad.QuienSoy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Lo corre WorkManager (con red). La rama de la gracia la agrega la Tarea 7 y el latido después de la pasada, la Tarea 8. */
@HiltWorker
class TrabajoDeSubida @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val subidor: Subidor,
    private val quienSoy: QuienSoy,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (quienSoy.identidad.value.estado != EstadoDelCelular.VINCULADO) return Result.success()
        val r = subidor.vaciar()
        // Sólo red o una pieza en espera vuelven a intentar solos; un PIN o una sucursal piden a la persona.
        return if (r.cortadoPor == "SIN_RED" || r.cortadoPor == "REINTENTABLE") Result.retry() else Result.success()
    }
}

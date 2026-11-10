package com.jaac.avoqado_campo.cola

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jaac.avoqado_campo.copia.RepositorioDeCopia
import com.jaac.avoqado_campo.identidad.EstadoDelCelular
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.latido.Latido
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Lo corre WorkManager (con red). Vinculado: la ruta normal, y si a media pasada lo desvinculan, la gracia en el mismo trabajo.
 * Desvinculado o dado de baja: la gracia. Después de la pasada, el latido; y si subió una venta o una custodia, la copia.
 */
@HiltWorker
class TrabajoDeSubida @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val subidor: Subidor,
    private val gracia: SubidorDeGracia,
    private val quienSoy: QuienSoy,
    private val latido: Latido,
    private val copia: RepositorioDeCopia,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val r = when (quienSoy.identidad.value.estado) {
            EstadoDelCelular.VINCULADO -> subidor.vaciar().let { normal ->
                // Pasó a la gracia a media pasada: lo que la ruta normal ya subió también pide refrescar la copia.
                if (normal.modoGracia) gracia.vaciar().let { it.copy(copiaPorRefrescar = it.copiaPorRefrescar || normal.copiaPorRefrescar) }
                else normal
            }
            EstadoDelCelular.DESVINCULADO, EstadoDelCelular.DADO_DE_BAJA -> gracia.vaciar()
            else -> return Result.success()
        }
        // Lo de después de la pasada no cambia la decisión de reintentar (un disco lleno no frena la cola).
        try {
            latido.mandar()
            // Sin sesión (gracia) no se puede bajar, y un 401 de mis-sims le borraría a la gracia su `graciaHasta`.
            val id = quienSoy.identidad.value
            if (r.copiaPorRefrescar && id.estado == EstadoDelCelular.VINCULADO) id.venueIdDeLaSesion?.let { copia.actualizar(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("TrabajoDeSubida", "latido o copia después de la pasada", e)
        }
        // Red, una pieza en espera o la gracia que devolvió la sesión (USA_TU_SESION: sigue la ruta normal) vuelven solos;
        // un PIN o una sucursal piden a la persona.
        return if (r.cortadoPor in REINTENTAR) Result.retry() else Result.success()
    }

    private companion object { val REINTENTAR = setOf("SIN_RED", "REINTENTABLE", SubidorDeGracia.USA_TU_SESION) }
}

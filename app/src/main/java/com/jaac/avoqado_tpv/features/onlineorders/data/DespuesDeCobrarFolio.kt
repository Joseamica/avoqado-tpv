package com.jaac.avoqado_tpv.features.onlineorders.data

import androidx.annotation.VisibleForTesting
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.features.onlineorders.domain.ComandaDeFolio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tienda híbrida (P4): lo que hace la terminal cuando el cobro de un folio YA quedó registrado en el SERVIDOR.
 *
 * El ViewModel de cobro (PAX y Nexgo) llama [alConfirmarse] UNA vez, con el pago registrado y antes del recibo de siempre:
 *  1. «aceptar» el folio (T2/T9): pasa a «Por entregar» ya. Si falla (sin red, sin permiso), no pasa nada: la sincronización del
 *     SERVIDOR (`syncPaidFolios`) lo pasa igual la próxima vez que alguien lee la lista. Por eso NO se encola ni se reintenta.
 *  2. La comanda de apoyo ([ComandaDeApoyo]), si hay impresora.
 *
 * No espera ni devuelve nada: corre en su propio alcance, que vive con la app y no con la pantalla del cobro (salir del éxito no
 * corta la comanda). Un cobro que quedó en la cola sin red NO la llama: el SERVIDOR todavía no ve el folio pagado.
 */
@Singleton
class DespuesDeCobrarFolio @Inject constructor(
    private val folios: FoliosRepository,
    private val comanda: ComandaDeApoyo,
    private val storage: SecureStorage,
) {

    @VisibleForTesting
    internal var alcance: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Timber.e(e, "🧾 [Folios] Después de cobrar falló") },
    )

    fun alConfirmarse(folio: ComandaDeFolio) {
        val venueId = storage.getVenueId()?.takeIf { it.isNotBlank() } ?: return
        alcance.launch {
            try {
                folios.aceptar(venueId, folio.orderId)
                    .onFailure { Timber.w(it, "🧾 [Folios] No se aceptó %s: el servidor lo pasa a «Por entregar» al sincronizar", folio.orderNumber) }
                comanda.intentar(venueId, folio)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "🧾 [Folios] Después de cobrar %s falló; el folio sigue en «Por entregar»", folio.orderNumber)
            }
        }
    }
}

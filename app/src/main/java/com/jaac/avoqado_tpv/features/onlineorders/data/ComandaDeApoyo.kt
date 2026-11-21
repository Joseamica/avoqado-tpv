package com.jaac.avoqado_tpv.features.onlineorders.data

import android.os.SystemClock
import com.jaac.avoqado_tpv.core.printer.ComandaNoEmpezada
import com.jaac.avoqado_tpv.core.printer.PrinterManager
import com.jaac.avoqado_tpv.features.onlineorders.domain.ComandaDeFolio
import com.jaac.avoqado_tpv.features.ordering.domain.KitchenStatus
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierType
import com.jaac.avoqado_tpv.features.ordering.domain.OrderItem
import com.jaac.avoqado_tpv.features.ordering.domain.ProductModifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.math.BigDecimal
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Qué pasó al intentar la comanda en ESTE aparato. */
enum class ResultadoDeComanda {
    /** Salió el papel (y se pidió confirmarla). */
    IMPRESA,

    /** Otra caja ganó el reclamo, o no se pudo reclamar (sin red, error): aquí no se imprime. */
    OTRA_CAJA,

    /** Aquí no salió: sin impresora o la impresora falló. */
    NO_SALIO,

    /**
     * D661 · Ruling 50: esperó tanto la impresora que su reclamo ya iba a vencer: no salió ni un renglón y no se confirma. Sin descanso:
     * el siguiente sondeo la vuelve a reclamar (el SERVIDOR la niega si se está devolviendo, o la da como «POSIBLE REPETIDA»).
     */
    RECLAMO_VENCIDO,
}

/**
 * La comanda de un folio ya cobrado (P4, decisión 18 del diseño): un APOYO que nadie vigila. Lo que cuenta es «Por entregar».
 *
 * Reclama (`print/claim`: si otra caja ya la imprime, ésta no), imprime y confirma (`print/confirm`) SÓLO si el papel salió — al
 * revés del defecto de `MenuViewModel.printFullComanda`, que daba la comanda por impresa aunque fallara. Sin impresora, con el
 * reclamo perdido o con el papel fallido no hace nada más: el folio sigue en «Por entregar» y el reclamo vence solo en el SERVIDOR.
 *
 * D661: también la de un pedido pagado con tarjeta en línea (la pide [VigiaDeFolios] de una lectura fresca). Ruling 9: el reclamo es
 * «al menos una vez»; si se concede sobre otro que venció sin confirmarse, arriba sale «POSIBLE REPETIDA» (en cualquier reclamo, porque
 * esta caja se lo dice al SERVIDOR en todos, Ruling 37). Reclamo, papel y confirmación corren sin cancelarse: cerrar la pantalla o la
 * actividad a media impresión no deja un papel sin confirmar.
 *
 * Constructor primario `internal` (con el reloj monótono) para las pruebas; el que usa Hilt es el secundario.
 */
@Singleton
class ComandaDeApoyo internal constructor(
    private val folios: FoliosRepository,
    private val impresora: PrinterManager,
    /** Reloj MONÓTONO (no salta si cambian la hora del aparato): de él sale la edad del reclamo (Ruling 50). */
    private val monotonico: () -> Long,
) {
    @Inject
    constructor(folios: FoliosRepository, impresora: PrinterManager) : this(folios, impresora, SystemClock::elapsedRealtime)

    /** ¿Esta terminal tiene impresora? (un modelo sin impresora no lee ningún pedido para su comanda). */
    fun puedeImprimir(): Boolean = impresora.isPrinterAvailable()

    /**
     * [edadMaximaDelReclamo] (D661 · Ruling 50): con él, si al quedar libre la impresora el reclamo ya tiene esa edad, no se imprime
     * ([ResultadoDeComanda.RECLAMO_VENCIDO]). Lo pasa sólo quien tiene un siguiente sondeo que la vuelve a pedir ([VigiaDeFolios]); sin
     * él (la comanda de un folio pagado en caja) sale como siempre: nadie más la pediría.
     */
    suspend fun intentar(
        venueId: String,
        folio: ComandaDeFolio,
        edadMaximaDelReclamo: Long? = null,
    ): ResultadoDeComanda = withContext(NonCancellable) {
        if (!impresora.isPrinterAvailable()) return@withContext ResultadoDeComanda.NO_SALIO
        // Ruling 50: la edad se cuenta desde ANTES de pedir el reclamo (el SERVIDOR lo concede después): nunca se queda corta.
        val pedidoEn = monotonico()
        val sigueVigente: (() -> Boolean)? = edadMaximaDelReclamo?.let { maxima -> { monotonico() - pedidoEn < maxima } }
        val reclamo = folios.reclamarImpresion(venueId, folio.orderId).getOrNull()
        if (reclamo?.reclamado != true) return@withContext ResultadoDeComanda.OTRA_CAJA
        val papel = try {
            impresora.printKitchenTicket(
                orderNumber = folio.orderNumber,
                orderItems = folio.renglonesDeCocina(),
                cliente = folio.nombre,
                notaDelPedido = folio.nota,
                esComandaDeFolio = true,
                posibleRepetida = reclamo.posibleRepetida,
                sigueVigente = sigueVigente,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        if (papel.exceptionOrNull() is ComandaNoEmpezada) {
            Timber.w("🧾 [Folios] La comanda de %s esperó la impresora y su reclamo ya casi vence: no sale", folio.orderNumber)
            return@withContext ResultadoDeComanda.RECLAMO_VENCIDO
        }
        if (papel.isFailure) {
            Timber.w(papel.exceptionOrNull(), "🧾 [Folios] La comanda de %s no salió: el folio sigue en «Por entregar»", folio.orderNumber)
            return@withContext ResultadoDeComanda.NO_SALIO
        }
        folios.confirmarImpresion(venueId, folio.orderId)
        ResultadoDeComanda.IMPRESA
    }

    companion object {
        /** D661 · Ruling 50: el reclamo dura 90 s en el SERVIDOR; con 15 s de margen, a los 75 s ya no se empieza el papel. */
        const val EDAD_MAXIMA_DEL_RECLAMO_MS = 75_000L
    }
}

/** La impresora sólo sabe de renglones de cuenta: sin precios (la cocina no los necesita) y con las extras como modificadores. */
private fun ComandaDeFolio.renglonesDeCocina(): List<OrderItem> = renglones.mapIndexed { i, r ->
    OrderItem(
        id = "$orderId-$i",
        orderId = orderId,
        productId = "",
        productName = r.nombre,
        productSku = null,
        quantity = r.cantidad,
        unitPrice = BigDecimal.ZERO,
        totalPrice = BigDecimal.ZERO,
        modifiers = r.extras.map {
            ProductModifier(
                id = "",
                name = if (it.cantidad > 1) "${it.cantidad}x ${it.nombre}" else it.nombre,
                priceAdjustment = BigDecimal.ZERO,
                type = ModifierType.MULTIPLE_CHOICE,
            )
        },
        notes = r.nota,
        kitchenStatus = KitchenStatus.PENDING,
        createdAt = Instant.EPOCH,
        sentToKitchenAt = null,
    )
}

package com.jaac.avoqado_tpv.features.onlineorders.data

import com.jaac.avoqado_tpv.core.printer.PrinterManager
import com.jaac.avoqado_tpv.features.onlineorders.domain.ComandaDeFolio
import com.jaac.avoqado_tpv.features.ordering.domain.KitchenStatus
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierType
import com.jaac.avoqado_tpv.features.ordering.domain.OrderItem
import com.jaac.avoqado_tpv.features.ordering.domain.ProductModifier
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.math.BigDecimal
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * La comanda de un folio ya cobrado (P4, decisión 18 del diseño): un APOYO que nadie vigila. Lo que cuenta es «Por entregar».
 *
 * Reclama (`print/claim`: si otra caja ya la imprime, ésta no), imprime y confirma (`print/confirm`) SÓLO si el papel salió — al
 * revés del defecto de `MenuViewModel.printFullComanda`, que daba la comanda por impresa aunque fallara. Sin impresora, con el
 * reclamo perdido o con el papel fallido no hace nada más: el folio sigue en «Por entregar» y el reclamo vence solo en el SERVIDOR.
 */
@Singleton
class ComandaDeApoyo @Inject constructor(
    private val folios: FoliosRepository,
    private val impresora: PrinterManager,
) {

    suspend fun intentar(venueId: String, folio: ComandaDeFolio) {
        if (!impresora.isPrinterAvailable()) return
        if (folios.reclamarImpresion(venueId, folio.orderId).getOrNull() != true) return
        val papel = try {
            impresora.printKitchenTicket(
                orderNumber = folio.orderNumber,
                orderItems = folio.renglonesDeCocina(),
                cliente = folio.nombre,
                notaDelPedido = folio.nota,
                esComandaDeFolio = true,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        papel.onFailure {
            Timber.w(it, "🧾 [Folios] La comanda de %s no salió: el folio sigue en «Por entregar»", folio.orderNumber)
            return
        }
        folios.confirmarImpresion(venueId, folio.orderId)
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

package com.jaac.avoqado_tpv.features.checkout.data

import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.printer.PrinterManager
import com.jaac.avoqado_tpv.core.util.VenueTimeZone
import com.jaac.avoqado_tpv.features.checkout.domain.descuentoParaImprimir
import com.jaac.avoqado_tpv.features.checkout.domain.model.VentaLocal
import com.jaac.avoqado_tpv.features.checkout.domain.renglonesParaImprimir
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPaySdkGateway
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayTicketBuilder
import com.jaac.avoqado_tpv.features.payment.domain.model.CardDetails
import com.jaac.avoqado_tpv.features.payment.domain.referenciaDeEfectivo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.math.BigDecimal
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * 💵 «Cobrar» sin red: el ticket de una venta guardada, incluida la venta en $0, con el MISMO impresor que
 * la pantalla de éxito de cada sabor — PAX: `PrinterManager.printReceipt`; Nexgo: el ticket de `AngelPayTicketBuilder` por el SDK —, con
 * los datos del negocio de `SecureStorage` y los renglones de la venta guardada (nunca de la red). Sin folio ni QR, aunque ya se haya subido.
 *
 * El gateway de AngelPay va con [Provider]: inyectarlo directo lo construye al arrancar también en PAX (`NexgoFlavorHiltGraphTest`).
 */
@Singleton
class ImpresoraDeVentaGuardada @Inject constructor(
    private val printerManager: PrinterManager,
    private val ticketBuilder: AngelPayTicketBuilder,
    private val angelPaySdkGateway: Provider<AngelPaySdkGateway>,
    private val secureStorage: SecureStorage,
) {
    companion object {
        suspend fun imprimirEnPax(
            venta: VentaLocal,
            propinaCents: Int,
            printerManager: PrinterManager,
            secureStorage: SecureStorage,
        ): Result<Unit> = withContext(Dispatchers.IO) {
            printerManager.printReceipt(
                receiptUrl = null,
                amount = BigDecimal.valueOf(venta.totalCents.toLong() + propinaCents, 2).toPlainString(),
                authCode = "EFECTIVO",
                tipAmount = BigDecimal.valueOf(propinaCents.toLong(), 2).toPlainString(),
                cardDetails = CardDetails.CASH,
                referenceNumber = referenciaDeEfectivo(venta.llave),
                venueName = secureStorage.getVenueName(),
                venueLogoUrl = secureStorage.getVenueLogo(),
                venueLegalName = secureStorage.getVenueLegalName(),
                venueRfc = secureStorage.getVenueRfc(),
                venueAddress = secureStorage.getVenueAddress(),
                venueCity = secureStorage.getVenueCity(),
                venueState = secureStorage.getVenueState(),
                venueZipCode = secureStorage.getVenueZipCode(),
                staffName = secureStorage.getStaffName(),
                orderNumber = null,
                orderItems = renglonesParaImprimir(venta),
                discountAmount = descuentoParaImprimir(venta).toPlainString(),
                ventaGuardadaSinConexion = true,
            )
        }
    }

    suspend fun imprimir(venta: VentaLocal, propinaCents: Int = 0): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val renglones = renglonesParaImprimir(venta)
            val total = BigDecimal.valueOf(venta.totalCents.toLong(), 2)
            val descuento = descuentoParaImprimir(venta)
            val referencia = referenciaDeEfectivo(venta.llave)
            if (BuildConfig.ENABLE_PAX_SDK) {
                imprimirEnPax(venta, propinaCents, printerManager, secureStorage)
            } else {
                val ticket = ticketBuilder.buildPaymentTicket(
                    amount = total,
                    tipAmount = BigDecimal.valueOf(propinaCents.toLong(), 2),
                    authCode = "EFECTIVO",
                    referenceNumber = referencia,
                    venueName = secureStorage.getVenueName(),
                    venueLegalName = secureStorage.getVenueLegalName(),
                    venueRfc = secureStorage.getVenueRfc(),
                    venueAddress = secureStorage.getVenueAddress(),
                    venueCity = secureStorage.getVenueCity(),
                    venueState = secureStorage.getVenueState(),
                    venueZipCode = secureStorage.getVenueZipCode(),
                    venueTimeZone = TimeZone.getTimeZone(VenueTimeZone.get(secureStorage)),
                    staffName = secureStorage.getStaffName(),
                    orderNumber = null,
                    appVersionName = BuildConfig.VERSION_NAME,
                    orderItems = renglones,
                    discountAmount = descuento,
                    isCash = true,
                    ventaGuardadaSinConexion = true,
                )
                angelPaySdkGateway.get().printTicket(ticket)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.e(e, "🖨️ [VentaGuardada] No se pudo imprimir el ticket de la venta guardada")
            Result.failure(e)
        }
    }
}

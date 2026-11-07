package com.jaac.avoqado_tpv.features.onlineorders.data

import com.google.gson.JsonElement
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.core.data.network.parseBackendErrorCode
import com.jaac.avoqado_tpv.core.data.network.parseBackendErrorMessage
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.features.onlineorders.domain.ConteoDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.Folio
import com.jaac.avoqado_tpv.features.onlineorders.domain.FolioModificador
import com.jaac.avoqado_tpv.features.onlineorders.domain.FolioProducto
import com.jaac.avoqado_tpv.features.onlineorders.domain.PaginaDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.SeccionDeFolios
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Los folios de la tienda en línea («Pedidos por pagar») contra el SERVIDOR. Todo regresa `kotlin.Result`: sin red o con un
 * error nunca lanza (la cancelación sí se repropaga). Un error HTTP llega como [BackendHttpException] con su `errorCode`
 * (`PAYMENT_REQUIRED`, `ORDER_STATE_CHANGED`, `ORDER_HAS_PAYMENTS`…).
 */
@Singleton
class FoliosRepository @Inject constructor(
    private val api: OnlineOrdersApiService,
    private val deviceInfoManager: DeviceInfoManager,
) {

    /** La lista de siempre. [puedeCobrar] manda el latido (`x-online-orders-to-pay: 1`); false no manda la cabecera. */
    suspend fun listar(venueId: String, puedeCobrar: Boolean): Result<PaginaDeFolios> =
        llamar { api.toPay(venueId, if (puedeCobrar) "1" else null) }.mapCatching { it.aPagina() }

    /** Más de una sección, desde su cursor. Paginar nunca late. */
    suspend fun cargarMas(venueId: String, seccion: SeccionDeFolios, cursor: String): Result<PaginaDeFolios> =
        llamar { api.toPay(venueId, null, seccion.clave, cursor) }.mapCatching { it.aPagina() }

    suspend fun aceptar(venueId: String, orderId: String): Result<Unit> = llamar { api.accept(venueId, orderId) }.map { }
    suspend fun listo(venueId: String, orderId: String): Result<Unit> = llamar { api.ready(venueId, orderId) }.map { }
    suspend fun entregado(venueId: String, orderId: String): Result<Unit> = llamar { api.delivered(venueId, orderId) }.map { }
    suspend fun cancelar(venueId: String, orderId: String): Result<Unit> = llamar { api.cancel(venueId, orderId) }.map { }

    /** true = este aparato ganó el reclamo y debe imprimir. */
    suspend fun reclamarImpresion(venueId: String, orderId: String): Result<Boolean> =
        conSerie { serie -> llamar { api.printClaim(venueId, orderId, serie) }.map { it?.data?.claimed == true } }

    /** false = ya estaba sellada o este aparato perdió el reclamo; NO significa «reimprime». */
    suspend fun confirmarImpresion(venueId: String, orderId: String): Result<Boolean> =
        conSerie { serie -> llamar { api.printConfirm(venueId, orderId, serie) }.map { it?.data?.ok == true } }

    private suspend fun <T> conSerie(bloque: suspend (String) -> Result<T>): Result<T> {
        val serie = try {
            deviceInfoManager.getSerialNumber()
        } catch (e: SecurityException) {
            return Result.failure(e)
        }
        return bloque(serie)
    }

    private suspend fun <T> llamar(peticion: suspend () -> Response<T>): Result<T?> = try {
        val respuesta = peticion()
        if (respuesta.isSuccessful) {
            Result.success(respuesta.body())
        } else {
            val cuerpo = respuesta.errorBody()?.string()
            Result.failure(
                BackendHttpException(
                    statusCode = respuesta.code(),
                    message = parseBackendErrorMessage(cuerpo, respuesta.message()),
                    errorCode = parseBackendErrorCode(cuerpo),
                ),
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}

private fun ToPayEnvelope?.aPagina(): PaginaDeFolios {
    val d = this?.data ?: throw IllegalStateException("Respuesta de folios sin datos")
    val porPagar = d.toPay.orEmpty().mapNotNull { it?.aFolio() }
    val porEntregar = d.toDeliver.orEmpty().mapNotNull { it?.aFolio() }
    return PaginaDeFolios(
        horaDelServidor = d.serverTime.aInstante(),
        showTile = d.showTile == true,
        pollSeconds = d.pollSeconds,
        conteo = ConteoDeFolios(d.counts?.toPay ?: porPagar.size, d.counts?.toDeliver ?: porEntregar.size),
        porPagar = porPagar,
        porEntregar = porEntregar,
        siguientes = buildMap {
            d.nextCursors?.toPay?.takeIf { it.isNotBlank() }?.let { put(SeccionDeFolios.POR_PAGAR, it) }
            d.nextCursors?.toDeliver?.takeIf { it.isNotBlank() }?.let { put(SeccionDeFolios.POR_ENTREGAR, it) }
        },
    )
}

/** Un folio sin cuenta o sin folio no se puede cobrar ni buscar: se descarta (null) en vez de tumbar la lista. */
private fun FolioDto.aFolio(): Folio? {
    val cuenta = orderId?.takeIf { it.isNotBlank() } ?: return null
    val numero = orderNumber?.takeIf { it.isNotBlank() } ?: return null
    return Folio(
        orderId = cuenta,
        onlineOrderId = onlineOrderId,
        folio = numero,
        estado = status.orEmpty(),
        nombre = customerName?.takeIf { it.isNotBlank() },
        telefono = customerPhone,
        nota = note?.takeIf { it.isNotBlank() },
        creadoEn = createdAt.aInstante(),
        venceEn = acceptDeadlineAt.aInstante(),
        pagadoEn = paidAt.aInstante(),
        total = (total ?: BigDecimal.ZERO).enPesos(),
        falta = (remaining ?: BigDecimal.ZERO).enPesos(),
        estadoDePago = paymentStatus,
        necesitaImprimir = needsPrint == true,
        productos = items.orEmpty().filterNotNull().map { i ->
            FolioProducto(
                nombre = i.name.orEmpty(),
                cantidad = i.quantity ?: 1,
                nota = i.note?.takeIf { it.isNotBlank() },
                extras = i.modifiers.orEmpty().filterNotNull().map { FolioModificador(it.name.orEmpty(), it.quantity ?: 1) },
                precioUnitario = i.unitPrice.aPesos(),
                total = i.total.aPesos(),
            )
        },
    )
}

private fun BigDecimal.enPesos(): BigDecimal = setScale(2, RoundingMode.HALF_UP)

/** Un número (o texto numérico) en pesos con 2 decimales; null si falta, es null o no es un número. Sin pasar por Double. */
private fun JsonElement?.aPesos(): BigDecimal? =
    this?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBigDecimal.enPesos() }.getOrNull() }

private fun String?.aInstante(): Instant? = this?.let { runCatching { Instant.parse(it) }.getOrNull() }

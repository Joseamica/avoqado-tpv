package com.jaac.avoqado_tpv.features.onlineorders.data

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.core.data.network.parseBackendErrorCode
import com.jaac.avoqado_tpv.core.data.network.parseBackendErrorMessage
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.features.onlineorders.domain.BeneficioDeFolio
import com.jaac.avoqado_tpv.features.onlineorders.domain.ComandaPorImprimir
import com.jaac.avoqado_tpv.features.onlineorders.domain.ConteoDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.Folio
import com.jaac.avoqado_tpv.features.onlineorders.domain.FolioModificador
import com.jaac.avoqado_tpv.features.onlineorders.domain.FolioProducto
import com.jaac.avoqado_tpv.features.onlineorders.domain.MarcaDeCierre
import com.jaac.avoqado_tpv.features.onlineorders.domain.PaginaDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.SeccionDeFolios
import com.jaac.avoqado_tpv.features.onlineorders.domain.TarjetaPorAbrir
import com.jaac.avoqado_tpv.features.onlineorders.domain.marcaDeCierre
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
 * (`PAYMENT_REQUIRED`, `ORDER_HAS_PAYMENTS`…); el 409 `ORDER_STATE_CHANGED` llega como [PedidoYaCambio], con el estado real.
 */
@Singleton
class FoliosRepository @Inject constructor(
    private val api: OnlineOrdersApiService,
    private val deviceInfoManager: DeviceInfoManager,
) {

    /**
     * La lista de siempre. [puedeCobrar] manda el latido (`x-online-orders-to-pay: 1`); false no manda la cabecera.
     * D661 (Ruling 45): [avisaTarjetas] manda `x-online-orders-card-alert: 1` («esta caja suena y enseña los pagados en línea»);
     * sólo lo pide el sondeo del vigía con la app al frente. La pantalla y «Cargar más» nunca.
     */
    suspend fun listar(venueId: String, puedeCobrar: Boolean, avisaTarjetas: Boolean = false): Result<PaginaDeFolios> =
        llamar { api.toPay(venueId, if (puedeCobrar) "1" else null, avisaTarjetas = if (avisaTarjetas) "1" else null) }
            .mapCatching { it.aPagina() }

    /** Más de una sección, desde su cursor. Paginar nunca late. */
    suspend fun cargarMas(venueId: String, seccion: SeccionDeFolios, cursor: String): Result<PaginaDeFolios> =
        llamar { api.toPay(venueId, null, seccion.clave, cursor) }.mapCatching { it.aPagina() }

    /**
     * Aceptar con el cuerpo `{}` de siempre. D661: [abrirPagadoEnLinea] = «Abrir y cobrar» un pedido pagado con tarjeta en línea (el
     * SERVIDOR cobra la tarjeta apartada); sólo lo manda ese botón.
     */
    suspend fun aceptar(venueId: String, orderId: String, abrirPagadoEnLinea: Boolean = false): Result<Unit> =
        llamar { api.accept(venueId, orderId, if (abrirPagadoEnLinea) mapOf("abrirPagadoEnLinea" to true) else emptyMap()) }.map { }
    suspend fun listo(venueId: String, orderId: String): Result<Unit> = llamar { api.ready(venueId, orderId) }.map { }
    suspend fun entregado(venueId: String, orderId: String): Result<Unit> = llamar { api.delivered(venueId, orderId) }.map { }
    suspend fun cancelar(venueId: String, orderId: String): Result<Unit> = llamar { api.cancel(venueId, orderId) }.map { }

    /** D661 (Ruling 14): UN pedido, completo (con renglones y `needsPrint`), por su propio camino. */
    suspend fun leerPedido(venueId: String, orderId: String): Result<Folio> =
        llamar { api.order(venueId, orderId) }.mapCatching { it?.data?.aFolio() ?: throw IllegalStateException("Respuesta del pedido sin datos") }

    /**
     * [ReclamoDeComanda.reclamado] = este aparato ganó el reclamo y debe imprimir. Ruling 37: siempre dice «esta caja imprime POSIBLE
     * REPETIDA» (`x-online-orders-card-alert: 1`), porque [ComandaDeApoyo] la imprime en cualquier reclamo repetido.
     */
    suspend fun reclamarImpresion(venueId: String, orderId: String): Result<ReclamoDeComanda> =
        conSerie { serie ->
            llamar { api.printClaim(venueId, orderId, serie, imprimeLaMarca = "1") }
                .map { ReclamoDeComanda(it?.data?.claimed == true, it?.data?.posibleRepetida == true) }
        }

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
            val codigo = parseBackendErrorCode(cuerpo)
            val mensaje = parseBackendErrorMessage(cuerpo, respuesta.message())
            Result.failure(
                // Ruling 71: `ORDER_CLOSED_NOT_DELIVERED` (cerrado por devolución o «No lo recogió») se lee igual, y nunca es «hecho».
                if (codigo == "ORDER_CLOSED_NOT_DELIVERED" || (respuesta.code() == 409 && codigo == "ORDER_STATE_CHANGED")) {
                    pedidoYaCambio(cuerpo, mensaje, cerradoSinEntregar = codigo == "ORDER_CLOSED_NOT_DELIVERED")
                } else {
                    BackendHttpException(statusCode = respuesta.code(), message = mensaje, errorCode = codigo)
                },
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/** El reclamo de la comanda. [posibleRepetida] (Ruling 9): se concedió sobre otro que venció sin confirmarse; el papel pudo haber salido. */
data class ReclamoDeComanda(val reclamado: Boolean, val posibleRepetida: Boolean = false)

/**
 * D661 (Ruling 36): un 409 `ORDER_STATE_CHANGED`: el SERVIDOR no aplicó la acción. [estado] es el estado real del pedido y [marca], si se
 * cerró o se está devolviendo («No lo recogió», «Devolviendo…», «Devuelto»); [message] es su texto, en español.
 */
class PedidoYaCambio(
    val estado: String?,
    val marca: MarcaDeCierre?,
    override val message: String,
    /** Ruling 71: se cerró sin entregarse (devuelto, devolviéndose o «No lo recogió»): nunca es «hecho». */
    val cerradoSinEntregar: Boolean = marca != null,
) : Exception(message)

/** El 409 trae `status` y, opcionales, las marcas de cierre (ISO o null): un SERVIDOR viejo sólo `status`, y entonces no hay marca. */
private fun pedidoYaCambio(cuerpo: String?, mensaje: String, cerradoSinEntregar: Boolean): PedidoYaCambio {
    val json = runCatching { JsonParser.parseString(cuerpo.orEmpty()).asJsonObject }.getOrNull()
    fun texto(llave: String) = json?.get(llave)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }
    fun fecha(llave: String) = texto(llave).aInstante() != null
    val marca = marcaDeCierre(fecha("notPickedUpAt"), fecha("refundRequestedAt"), fecha("refundedAt"))
    return PedidoYaCambio(texto("status"), marca, mensaje, cerradoSinEntregar = cerradoSinEntregar || marca != null)
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
        tarjetasPorAbrir = d.tarjetasPorAbrir.orEmpty().mapNotNull { it?.aTarjeta() },
        comandasPorImprimir = d.comandasPorImprimir.orEmpty().mapNotNull { c ->
            c?.orderId?.takeIf { it.isNotBlank() }?.let { ComandaPorImprimir(it, c.orderNumber.orEmpty()) }
        },
    )
}

/** Un pagado en línea sin cuenta o sin folio no se puede abrir: se descarta (null) en vez de tumbar la lista. */
private fun TarjetaPorAbrirDto.aTarjeta(): TarjetaPorAbrir? {
    val cuenta = orderId?.takeIf { it.isNotBlank() } ?: return null
    val numero = orderNumber?.takeIf { it.isNotBlank() } ?: return null
    return TarjetaPorAbrir(
        orderId = cuenta,
        folio = numero,
        nombre = customerName?.takeIf { it.isNotBlank() },
        total = (total ?: BigDecimal.ZERO).enPesos(),
        creadoEn = createdAt.aInstante(),
        cobrando = cobrando == true,
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
        cliente = cliente?.nombre?.takeIf { it.isNotBlank() },
        beneficios = beneficios.orEmpty().filterNotNull().mapNotNull { b ->
            b.monto.aPesos()?.let { BeneficioDeFolio(b.nombre?.takeIf { it.isNotBlank() } ?: "Descuento", it) }
        },
        beneficiosTotal = beneficiosTotal ?: 0,
        // Ruling 36: una fecha que no se lee cuenta como ausente (igual que el POS).
        marca = marcaDeCierre(notPickedUpAt.aInstante() != null, refundRequestedAt.aInstante() != null, refundedAt.aInstante() != null),
    )
}

private fun BigDecimal.enPesos(): BigDecimal = setScale(2, RoundingMode.HALF_UP)

/** Un número (o texto numérico) en pesos con 2 decimales; null si falta, es null o no es un número. Sin pasar por Double. */
private fun JsonElement?.aPesos(): BigDecimal? =
    this?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBigDecimal.enPesos() }.getOrNull() }

private fun String?.aInstante(): Instant? = this?.let { runCatching { Instant.parse(it) }.getOrNull() }

package com.jaac.avoqado_tpv.features.payment.domain.usecase

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.features.checkout.domain.model.RenglonDeTicket
import com.jaac.avoqado_tpv.features.payment.data.api.PagoDeVentaGuardadaRequest
import com.jaac.avoqado_tpv.features.payment.data.api.VentaGuardadaApi
import com.jaac.avoqado_tpv.features.payment.data.repository.toQueuedPayment
import com.jaac.avoqado_tpv.features.payment.domain.model.QueuedPayment
import com.jaac.avoqado_tpv.features.payment.domain.sync.SyncOutcome
import com.jaac.avoqado_tpv.features.payment.domain.sync.classifySyncFailure
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

class SubirVentaGuardadaUseCase @Inject constructor(
    private val api: VentaGuardadaApi,
    private val dao: PendingPaymentDao,
    @ApplicationContext private val context: Context,
    private val shiftReplayGate: com.jaac.avoqado_tpv.features.shift.data.repository.ShiftReplayGate? = null,
) {
    private val gson = Gson()

    /** No cierra/suelta el claim: la pantalla y el worker conservan esa responsabilidad. Nunca registra por /fast. */
    suspend operator fun invoke(fila: QueuedPayment): SyncOutcome {
        val token = fila.claimToken?.takeIf { it.isNotBlank() } ?: return SyncOutcome.Retryable
        try {
            val guardada = dao.findByReference(fila.referenceNumber, fila.venueId)
                ?: return SyncOutcome.Permanent("No se encontró la venta guardada")
            if (guardada.id != fila.queueId || guardada.claimToken != token) return SyncOutcome.Retryable
            // El dinero, la identidad y las marcas salen de Room, también al reintentar sólo el pago.
            val actual = guardada.toQueuedPayment()
            val mapping = if (guardada.shiftMembership == "MANAGED") {
                (shiftReplayGate ?: throw com.jaac.avoqado_tpv.features.shift.data.repository.ShiftMappingPendingException()).sale(guardada, token)
            } else null
            val pedido = mapping?.requestJson ?: actual.orderRequestJson ?: return SyncOutcome.Permanent("La venta no tiene su pedido guardado")
            val resolvedShift = mapping?.shiftId ?: actual.shiftId
            val llave = actual.idempotencyKey?.takeIf { it.isNotBlank() }
                ?: return SyncOutcome.Permanent("La venta no tiene su llave de reintento")
            val amount = actual.amount.movePointRight(2).intValueExact()
            val tip = actual.tip.movePointRight(2).intValueExact()
            if (amount < 0 || tip < 0) return SyncOutcome.Permanent("La venta tiene un importe inválido")
            val renglones = runCatching {
                gson.fromJson<List<RenglonDeTicket>>(actual.ticketJson, object : TypeToken<List<RenglonDeTicket>>() {}.type)
            }.getOrNull()
            if (renglones.isNullOrEmpty() || renglones.any { it.cantidad <= 0 }) {
                return SyncOutcome.Permanent("La venta no tiene un ticket válido")
            }
            val cobradoCents = actual.amount.add(actual.tip).movePointRight(2)
            val tolerancia = BigDecimal.valueOf(renglones.sumOf { it.cantidad.toLong() })
            var revision = actual.requiereRevision
            var orderId = actual.orderId
            var orderNumber = actual.orderNumber
            var omitirPago = false
            // Una cortesía puede haber quedado PAID sin Payment. Recuperar con la misma llave sobrevive a morir tras el CAS.
            if (orderId.isNullOrBlank() || (amount == 0 && tip == 0)) {
                val respuesta = api.crearOrden(actual.venueId, pedido.toRequestBody("application/json; charset=utf-8".toMediaType()))
                falloHttp(respuesta)?.let { return it }
                val cuerpo = respuesta.body()
                val orden = cuerpo?.get("order")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: return SyncOutcome.Permanent("El servidor no devolvió la orden creada")
                val idRecibido = orden.texto("id")
                if (cuerpo?.get("success")?.asBoolean != true || idRecibido.isNullOrBlank()) {
                    return SyncOutcome.Permanent("El servidor no devolvió el id de la orden creada")
                }
                if (!orderId.isNullOrBlank() && orderId != idRecibido) {
                    return SyncOutcome.Permanent("El servidor devolvió otra orden para esta venta guardada")
                }
                orderId = idRecibido
                orderNumber = orden.texto("orderNumber")
                revision = revision || renglonesDifieren(orden, renglones) ||
                    (orden.get("priceChanges")?.takeIf { !it.isJsonNull }?.let { !it.isJsonArray || it.asJsonArray.size() > 0 } == true)
                omitirPago = amount == 0 && tip == 0 && orden.texto("paymentStatus") == "PAID"
                if (omitirPago) {
                    val total = orden.centavosDesdePesos("total")
                    revision = revision || total == null || total.subtract(cobradoCents).abs() > tolerancia
                }
                // La revisión y la orden se comprometen juntas ANTES del pago: un fallo de red no borra esa evidencia.
                if (dao.guardarOrdenDeVentaGuardada(actual.queueId, token, orderId, orderNumber, revision) != 1) {
                    return SyncOutcome.Retryable
                }
            }

            val idDeLaOrden = orderId ?: return SyncOutcome.Permanent("La venta no tiene orden")
            if (!omitirPago) {
                if (mapping != null) shiftReplayGate!!.sale(guardada, token)
                val respuesta = api.pagar(actual.venueId, idDeLaOrden, PagoDeVentaGuardadaRequest(
                    venueId = actual.venueId, amount = amount, tip = tip, staffId = actual.staffId, idempotencyKey = llave,
                    shiftIdDeLaVenta = resolvedShift, createdAtLocal = actual.createdAt, edadDeLaVentaMs = edadDeLaVenta(actual),
                ))
                falloHttp(respuesta)?.let { return it }
                val cuerpo = respuesta.body()
                val pago = cuerpo?.get("payment")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: return SyncOutcome.Permanent("El servidor no devolvió el pago")
                if (cuerpo?.get("success")?.asBoolean != true || pago.texto("paymentId").isNullOrBlank() ||
                    pago.texto("orderId") != idDeLaOrden || pago.texto("status") != "COMPLETED") {
                    return SyncOutcome.Permanent("El servidor no confirmó el pago de esta venta")
                }
                val total = runCatching { pago.get("orderTotalCents")?.asBigDecimal }.getOrNull()
                revision = revision || total == null || total.subtract(cobradoCents).abs() > tolerancia
            }
            if (revision && dao.guardarOrdenDeVentaGuardada(actual.queueId, token, idDeLaOrden, orderNumber, true) != 1) {
                return SyncOutcome.Retryable
            }
            if (mapping != null) shiftReplayGate!!.sale(guardada, token)
            return SyncOutcome.Synced
        } catch (e: CancellationException) {
            throw e
        } catch (e: ArithmeticException) {
            return SyncOutcome.Permanent("El importe guardado no se puede expresar en centavos")
        } catch (e: Exception) {
            return classifySyncFailure(e)
        }
    }

    private fun edadDeLaVenta(fila: QueuedPayment): Long? {
        val inicio = fila.relojMonotonoAlVender ?: return null
        val arranque = fila.arranqueAlVender ?: return null
        val actual = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrNull()
        if (actual == null || actual < 0 || actual != arranque || inicio < 0) return null
        return (SystemClock.elapsedRealtime() - inicio).takeIf { it >= 0 }
    }

    private fun renglonesDifieren(orden: JsonObject, ticket: List<RenglonDeTicket>): Boolean {
        val items = orden.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: return true
        val locales = ticket.associateBy { it.externalId }
        val vistos = mutableSetOf<String>()
        if (locales.size != ticket.size || items.size() != ticket.size) return true
        return items.any { item ->
            val linea = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@any true
            val id = linea.texto("externalId") ?: return@any true
            val local = locales[id] ?: return@any true
            val total = linea.centavosDesdePesos("total") ?: return@any true
            !vistos.add(id) || total.subtract(BigDecimal(local.brutoCents)).abs() > BigDecimal.ONE
        } || vistos.size != locales.size
    }

    private fun JsonObject.texto(nombre: String): String? =
        get(nombre)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it.isNotBlank() }

    private fun JsonObject.centavosDesdePesos(nombre: String): BigDecimal? =
        runCatching { get(nombre)?.asBigDecimal?.movePointRight(2)?.setScale(0, RoundingMode.HALF_UP) }.getOrNull()

    private fun falloHttp(respuesta: Response<JsonObject>): SyncOutcome? {
        if (respuesta.isSuccessful) return null
        respuesta.errorBody()?.close()
        return classifySyncFailure(BackendHttpException(respuesta.code(), respuesta.message()))
    }
}

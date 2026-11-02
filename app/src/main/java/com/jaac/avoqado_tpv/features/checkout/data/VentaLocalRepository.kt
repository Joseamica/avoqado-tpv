package com.jaac.avoqado_tpv.features.checkout.data

import com.google.gson.Gson
import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity
import com.jaac.avoqado_tpv.features.checkout.domain.model.VentaLocal
import com.jaac.avoqado_tpv.features.payment.domain.referenciaDeEfectivo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** La llave de esta venta ya tiene fila (o su referencia de efectivo ya existe): no se guardó otra. */
class VentaLocalYaGuardadaException(llave: String) :
    IllegalStateException("La venta $llave ya está guardada en la terminal")

/** La fila recién insertada. [claimToken] viene sólo si se insertó ya reclamada. */
data class VentaGuardada(val id: Long, val claimToken: String?)

/**
 * 💵 «Cobrar» sin red: guarda una [VentaLocal] como UNA fila de efectivo de `pending_payments` (una sola escritura):
 * el pedido adentro (`order_request_json`, tal cual), los renglones del ticket (`ticket_json`), la llave como `idempotency_key`, la
 * referencia `CASH-…` derivada de la llave, sin orden (`order_id` null: la crea el servidor al subir) y con `exito_mostrado = 0`
 * (si la app muere antes de ver la pantalla de éxito, al volver se avisa que la venta ya quedó guardada).
 *
 * Con [guardar] `reclamada = true` la fila nace SYNCING con su `claim_token` (el efectivo de C3 la sube desde la pantalla sin
 * competir con el worker); con `false` nace PENDING y el worker la sube sola (el carrito en $0).
 *
 * El monto y la propina siguen el formato de la columna: pesos en texto con dos decimales (lo que hoy escribe una fila de efectivo).
 */
@Singleton
class VentaLocalRepository @Inject constructor(
    private val dao: PendingPaymentDao,
    private val shiftRepository: com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository,
) {
    private val gson = Gson()

    suspend fun guardar(
        venta: VentaLocal,
        /** `PendingPaymentEntity.PROCESSOR_BLUMON` o `PROCESSOR_ANGELPAY`: sin default, el que cobra en ESTE aparato. */
        paymentProcessor: String,
        /** En Nexgo (AngelPay) va `""`, como la fila de efectivo de `AngelPayPaymentViewModel`. */
        blumonSerialNumber: String,
        deviceSerialNumber: String?,
        reclamada: Boolean = false,
        propinaCents: Int = 0,
        relojMonotonoAlVender: Long? = null,
        arranqueAlVender: Int? = null,
    ): Result<VentaGuardada> = withContext(NonCancellable + Dispatchers.IO) {
        try {
            require(venta.llave.isNotBlank()) { "La venta necesita su llave" }
            require(
                paymentProcessor == PendingPaymentEntity.PROCESSOR_BLUMON || paymentProcessor == PendingPaymentEntity.PROCESSOR_ANGELPAY,
            ) { "Procesador desconocido: $paymentProcessor" }
            val token = if (reclamada) UUID.randomUUID().toString() else null
            val fila = PendingPaymentEntity(
                referenceNumber = referenciaDeEfectivo(venta.llave),
                venueId = venta.venueId,
                staffId = venta.staffId,
                amount = pesos(venta.totalCents),
                tip = pesos(propinaCents),
                rating = null,
                // "" es el centinela histórico del efectivo (sin procesador).
                merchantAccountId = "",
                blumonSerialNumber = blumonSerialNumber,
                deviceSerialNumber = deviceSerialNumber,
                maskedPan = null,
                cardBrand = null,
                entryMode = "MANUAL",
                isInternational = false,
                authorizationNumber = "EFECTIVO",
                idempotencyKey = venta.llave,
                paymentProcessor = paymentProcessor,
                orderId = null,
                shiftId = venta.shiftId,
                shiftMembership = venta.shiftMembership ?: "UNKNOWN",
                localShiftSessionId = venta.localShiftSessionId,
                cashDrawerSessionId = venta.cashDrawerSessionId,
                createdAt = venta.createdAtLocal,
                syncStatus = if (reclamada) PendingPaymentEntity.SYNC_STATUS_SYNCING else PendingPaymentEntity.SYNC_STATUS_PENDING,
                claimToken = token,
                claimedAt = if (reclamada) System.currentTimeMillis() else null,
                orderRequestJson = venta.pedidoJson,
                exitoMostrado = false,
                ticketJson = gson.toJson(venta.renglones),
                relojMonotonoAlVender = relojMonotonoAlVender,
                arranqueAlVender = arranqueAlVender,
            )
            val id = shiftRepository.guardarNuevaObligacionDeCaja(venta.venueId) { belonging ->
                dao.insert(fila.copy(shiftMembership = belonging.kind, localShiftSessionId = belonging.localSessionId,
                    cashDrawerSessionId = belonging.cashDrawerSessionId, shiftId = if (belonging.kind == "UNKNOWN") fila.shiftId else belonging.serverShiftId))
            }
            // IGNORE por la referencia única: -1 = ya había una fila de esta llave. Nunca se pisa lo guardado.
            if (id <= 0) Result.failure(VentaLocalYaGuardadaException(venta.llave)) else Result.success(VentaGuardada(id, token))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun pesos(cents: Int): String = BigDecimal.valueOf(cents.toLong(), 2).toPlainString()
}

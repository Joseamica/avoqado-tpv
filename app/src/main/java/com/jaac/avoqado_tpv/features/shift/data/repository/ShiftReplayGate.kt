package com.jaac.avoqado_tpv.features.shift.data.repository

import androidx.room.withTransaction
import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentEntity
import java.io.IOException
import javax.inject.Inject

/** Esperar correspondencia no consume intentos de transporte ni autoriza otro cobro. */
class ShiftMappingPendingException(message: String = "La venta espera la apertura de su caja.") : IOException(message)

class ShiftReplayGate @Inject constructor(private val db: AvoqadoDatabase, private val storage: SecureStorage) {
    private suspend fun resolve(venue: String, kind: String?, local: String?, capturedShift: String?, capturedDrawer: String?): String? {
        if (kind != "MANAGED") {
            if (local != null || (kind == "NO_SHIFT_SYSTEM" && (capturedShift != null || capturedDrawer != null)))
                throw ShiftMappingPendingException("La referencia original de caja requiere revisión.")
            return capturedShift // Legacy y NO_SHIFT conservan su contrato, sin esperar otra apertura.
        }
        if (storage.getVenueId() != venue || storage.getToken().isNullOrBlank() || local.isNullOrBlank())
            throw ShiftMappingPendingException("Verifica la sesión de la sucursal original.")
        val row = db.localShiftSessionDao().leer(venue, local) ?: throw ShiftMappingPendingException()
        val remote = row.serverShiftId
        if (row.openingStatus != "CONFIRMED" || remote.isNullOrBlank() || remote == local || !remote.matches(Regex("c[a-z0-9]{24}")))
            throw ShiftMappingPendingException()
        if ((capturedShift != null && capturedShift != remote) || (capturedDrawer != null && capturedDrawer != row.cashDrawerSessionId))
            throw ShiftMappingPendingException("La referencia original contradice la correspondencia de su caja.")
        return remote
    }

    suspend fun reference(context: PaymentContext, reference: String): String? = db.withTransaction {
        if (context.queueClaimToken != null) {
            val row = db.pendingPaymentDao().findByReference(reference, context.venueId) ?: throw ShiftMappingPendingException()
            checkClaim(row, context.queueClaimToken!!)
            if (row.idempotencyKey != context.idempotencyKey || row.staffId != context.staffId ||
                row.amount.toBigDecimal().compareTo(context.amount) != 0 || row.tip.toBigDecimal().compareTo(context.tip) != 0 ||
                row.shiftMembership != context.shiftMembership || row.localShiftSessionId != context.localShiftSessionId ||
                row.cashDrawerSessionId != context.cashDrawerSessionId || row.shiftId != context.shiftId)
                throw ShiftMappingPendingException("El registro original de la venta requiere revisión.")
        }
        resolve(context.venueId, context.shiftMembership, context.localShiftSessionId, context.shiftId, context.cashDrawerSessionId)
    }

    data class Sale(val row: PendingPaymentEntity, val shiftId: String?, val requestJson: String)
    suspend fun sale(row: PendingPaymentEntity, token: String): Sale = db.withTransaction {
        val actual = db.pendingPaymentDao().findByReference(row.referenceNumber, row.venueId) ?: throw ShiftMappingPendingException()
        checkClaim(actual, token)
        if (actual.id != row.id || actual.idempotencyKey != row.idempotencyKey || actual.orderRequestJson != row.orderRequestJson ||
            actual.shiftMembership != row.shiftMembership || actual.localShiftSessionId != row.localShiftSessionId ||
            actual.shiftId != row.shiftId || actual.cashDrawerSessionId != row.cashDrawerSessionId)
            throw ShiftMappingPendingException("El pedido o su referencia cambiaron durante la sincronización.")
        val shift = resolve(actual.venueId, actual.shiftMembership, actual.localShiftSessionId, actual.shiftId, actual.cashDrawerSessionId)
        val original = actual.orderRequestJson ?: throw ShiftMappingPendingException("La venta no conserva su pedido.")
        if (actual.shiftMembership != "MANAGED") return@withTransaction Sale(actual, shift, original)
        val wire = JsonParser.parseString(original).asJsonObject.apply { addProperty("shiftIdDeLaVenta", shift) }.toString()
        if (actual.orderWireRequestJson != null && actual.orderWireRequestJson != wire)
            throw ShiftMappingPendingException("La copia de envío contradice la caja original.")
        if (db.pendingPaymentDao().guardarCopiaDeEnvio(actual.venueId, actual.id, token, original, actual.orderWireRequestJson, wire) != 1)
            throw ShiftMappingPendingException("Otra sincronización recuperó esta venta.")
        val saved = db.pendingPaymentDao().findByReference(actual.referenceNumber, actual.venueId) ?: throw ShiftMappingPendingException()
        checkClaim(saved, token)
        if (saved.orderWireRequestJson != wire || resolve(saved.venueId, saved.shiftMembership, saved.localShiftSessionId, saved.shiftId, saved.cashDrawerSessionId) != shift)
            throw ShiftMappingPendingException("La copia de envío cambió.")
        Sale(saved, shift, wire)
    }

    suspend fun table(row: SyncIntentEntity): SyncIntentEntity = db.withTransaction {
        val actual = db.syncIntentDao().leer(row.venueId, row.id) ?: throw ShiftMappingPendingException()
        if (actual != row || actual.status != "PENDING") throw ShiftMappingPendingException("El intent ya cambió.")
        val shift = resolve(row.venueId, row.shiftMembership, row.localShiftSessionId, null, row.cashDrawerSessionId)
        if (row.shiftMembership != "MANAGED") return@withTransaction row
        val json = JsonParser.parseString(row.payloadJson).asJsonObject
        val previous = json.get("shiftIdDeLaVenta")?.takeUnless { it.isJsonNull }?.asString
        if (previous != null && previous != shift) throw ShiftMappingPendingException("El intent contradice su caja original.")
        json.addProperty("shiftIdDeLaVenta", shift)
        val wire = json.toString()
        if (db.syncIntentDao().guardarReferencia(row.venueId, row.id, row.payloadJson, wire) != 1) throw ShiftMappingPendingException()
        row.copy(payloadJson = wire)
    }

    private fun checkClaim(row: PendingPaymentEntity, token: String) {
        if (token.isBlank() || row.claimToken != token || row.syncStatus != "SYNCING")
            throw ShiftMappingPendingException("Otra sincronización recuperó esta venta.")
    }
}

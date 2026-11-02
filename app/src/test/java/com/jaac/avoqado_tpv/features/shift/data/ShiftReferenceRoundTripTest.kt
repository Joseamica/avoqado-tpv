package com.jaac.avoqado_tpv.features.shift.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.util.CuidV1
import com.jaac.avoqado_tpv.features.payment.data.repository.PaymentQueueRepositoryImpl
import com.jaac.avoqado_tpv.features.payment.domain.model.*
import com.jaac.avoqado_tpv.features.shift.data.dto.*
import com.jaac.avoqado_tpv.features.shift.domain.ShiftBelonging
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class ShiftReferenceRoundTripTest {
    private val gson = Gson()
    private val local = CuidV1.next()

    @Test fun `los cuatro snapshots conservan pertenencia y un JSON legacy ausente significa UNKNOWN`() {
        for (original in contexts()) {
            val json = gson.toJsonTree(original).asJsonObject
            val saved = gson.fromJson(json, original.javaClass)
            assertThat(saved.localShiftSessionId).isEqualTo(local)
            assertThat(saved.shiftMembership).isEqualTo("MANAGED")
            assertThat(saved.shiftId).isEqualTo("remote-shift")
            assertThat(saved.cashDrawerSessionId).isEqualTo("remote-drawer")
            json.remove("shiftMembership"); json.remove("localShiftSessionId"); json.remove("cashDrawerSessionId")
            val old = gson.fromJson(json, original.javaClass)
            assertThat(ShiftBelonging.fromStored(old.shiftMembership, old.localShiftSessionId, old.shiftId, old.cashDrawerSessionId).kind).isEqualTo("UNKNOWN")
            assertThat(old.shiftId).isEqualTo("remote-shift")
        }
    }

    @Test fun `fila real de cola y replay conservan referencias sin leer config ni turno actual`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AvoqadoDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.localShiftSessionDao().insertar(com.jaac.avoqado_tpv.core.data.local.entity.LocalShiftSessionEntity(
                localSessionId = local, venueId = "v1", openingKind = "LOCAL", openingOperationId = local,
                openingStatus = "CONFIRMED", originalOpenedAt = "2026-10-28T00:00:00Z", startingCash = "0.00",
                openedByStaffId = "original-actor", localStatus = "OPEN", serverShiftId = "remote-shift", cashDrawerSessionId = "remote-drawer",
                createdAt = 1, updatedAt = 1))
            val queue = PaymentQueueRepositoryImpl(db.pendingPaymentDao())
            for ((i, original) in contexts().filterNot { it is PaymentContext.RefundPayment }.withIndex()) {
                val row = QueuedPayment.desdeContexto(original, "folio", CardDetails.CASH, "EFECTIVO", "CASH-$i", null)
                assertThat(queue.enqueue(row).isSuccess).isTrue()
                val read = queue.getAllPending().single { it.referenceNumber == "CASH-$i" }
                assertThat(read.shiftMembership).isEqualTo("MANAGED")
                assertThat(read.localShiftSessionId).isEqualTo(local)
                assertThat(read.cashDrawerSessionId).isEqualTo("remote-drawer")
                val replay = read.toPaymentContext()
                assertThat(replay.shiftId).isEqualTo("remote-shift")
                assertThat(replay.localShiftSessionId).isEqualTo(local)
                assertThat(replay.shiftMembership).isEqualTo("MANAGED")
                assertThat(replay.cashDrawerSessionId).isEqualTo("remote-drawer")
            }
        } finally { db.close() }
    }

    @Test fun `DTO parcial de OPEN no inventa fondo ni totales ni countApplied`() {
        val response = gson.fromJson("""{"success":true,"operation":{"operationId":"$local","kind":"OPEN","state":"APPLIED","openingDisposition":"ADOPTED"},"shift":null,"reconciliation":null}""", ShiftOperationResponse::class.java)
        assertThat(response.shift).isNull()
        assertThat(response.operation?.countApplied).isNull()
        val crypto = gson.fromJson("""{"success":true,"data":{"paymentId":"p","requestId":null,"paymentUrl":null,"shiftId":null,"operation":{"emissionStatus":"EMITTING","financialRecorded":false}}}""", CryptoOperationResponse::class.java)
        assertThat(crypto.data?.amount).isNull()
        assertThat(crypto.data?.paymentUrl).isNull()
        assertThat(crypto.data?.operation?.financialRecorded).isFalse()
    }

    @Test fun `el cierre resuelto conserva llaves fecha actor y conteo originales sin id local en shiftId`() {
        val draft = LocalCloseShiftRequest("v1", CuidV1.next(), "2026-10-28T15:00:00Z", "original-actor", local,
            com.jaac.avoqado_tpv.features.shift.domain.CashReconciliationAction.COUNTED, "987.25")
        val persisted = gson.fromJson(gson.toJson(draft), LocalCloseShiftRequest::class.java)
        assertThat(JsonParser.parseString(gson.toJson(persisted)).asJsonObject.has("shiftId")).isFalse()
        val wire = persisted.resolve("remote-shift", "remote-drawer")
        assertThat(wire.shiftId).isEqualTo("remote-shift")
        assertThat(wire.openingOperationId).isEqualTo(local)
        assertThat(wire.staffId).isEqualTo("original-actor")
        assertThat(wire.closedAtLocal).isEqualTo(draft.closedAtLocal)
        assertThat(wire.countedCash).isEqualTo("987.25")
        assertThat(wire.operationId).isEqualTo(draft.operationId)
    }

    private fun contexts(): List<PaymentContext> = listOf(
        PaymentContext.FastPayment("v1", "original-actor", "remote-shift", BigDecimal("150.25"), merchantAccountId = null,
            shiftMembership = "MANAGED", localShiftSessionId = local, cashDrawerSessionId = "remote-drawer"),
        PaymentContext.OrderPayment("v1", "original-actor", "remote-shift", "order", BigDecimal("150.25"), merchantAccountId = null,
            shiftMembership = "MANAGED", localShiftSessionId = local, cashDrawerSessionId = "remote-drawer"),
        PaymentContext.AngelPayPayment("v1", "original-actor", "remote-shift", BigDecimal("150.25"),
            shiftMembership = "MANAGED", localShiftSessionId = local, cashDrawerSessionId = "remote-drawer"),
        PaymentContext.RefundPayment("v1", "original-actor", "remote-shift", BigDecimal("50.00"), merchantAccountId = "merchant",
            blumonSerialNumber = "serial", originalPaymentId = "payment", originalOrderId = "order", originalTotalAmount = BigDecimal("150.25"),
            refundReason = RefundReason.CUSTOMER_REQUEST, originalOperationNumber = 7,
            shiftMembership = "MANAGED", localShiftSessionId = local, cashDrawerSessionId = "remote-drawer"),
    )
}

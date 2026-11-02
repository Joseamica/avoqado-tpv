package com.jaac.avoqado_tpv.features.payment.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.network.CryptoPaymentRequest
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.core.util.CuidV1
import com.jaac.avoqado_tpv.features.shift.data.dto.CryptoOperationDto
import com.jaac.avoqado_tpv.features.shift.data.dto.CryptoOperationResponse
import com.jaac.avoqado_tpv.features.shift.data.dto.CryptoOperationResultDto
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import retrofit2.Response
import java.util.concurrent.CopyOnWriteArrayList

internal enum class CryptoGestureCase { FAILED_GET, PENDING_GET, SAME_BODY, LATE_CALLBACK, CANCEL_RETRY }
internal data class CryptoGestureUi(val phase: String, val requestId: String? = null, val paymentId: String? = null, val url: String? = null)

/** Transporte controlado, VM/repositorio/Room reales. El reset es el que ejecuta PaymentScreen en kiosco. */
internal suspend fun checkCryptoGestureLifecycle(
    scenario: CryptoGestureCase, db: AvoqadoDatabase, api: ApiService, venue: String, staff: String,
    begin: suspend (String) -> Unit, pay: () -> Unit, cancel: () -> Unit, reset: () -> Unit,
    confirm: suspend (SocketEvent.CryptoPaymentConfirmed) -> Unit, ui: () -> CryptoGestureUi,
) {
    val posted = CopyOnWriteArrayList<CryptoPaymentRequest>()
    val queried = CopyOnWriteArrayList<String>()
    val oldGet = CompletableDeferred<CryptoOperationResultDto>()
    fun data(key: String, recorded: Boolean = false) = CryptoOperationResultDto(
        paymentId = key, requestId = "provider-$key", paymentUrl = "https://example.test/$key",
        expiresAt = "2026-10-29T00:00:00Z", expiresInSeconds = 60,
        operation = CryptoOperationDto(key, "ISSUED", if (recorded) "COMPLETED" else "PENDING", recorded))
    coEvery { api.initiateDurableCryptoPayment(venue, any()) } coAnswers {
        val request = secondArg<CryptoPaymentRequest>()
        posted.add(request)
        val row = db.localCryptoOperationDao().leer(venue, request.idempotencyKey!!)!!
        assertThat(row.staffId).isEqualTo(staff)
        assertThat(row.shiftMembership).isEqualTo("NO_SHIFT_SYSTEM")
        assertThat(db.inTransaction()).isFalse()
        Response.success(CryptoOperationResponse(true, data(request.idempotencyKey!!)))
    }
    coEvery { api.getCryptoOperation(venue, any()) } coAnswers {
        val key = secondArg<String>()
        queried.add(key)
        val result = if (key == posted.first().idempotencyKey && scenario != CryptoGestureCase.CANCEL_RETRY) {
            if (scenario == CryptoGestureCase.FAILED_GET) throw java.io.IOException("GET A sin red")
            oldGet.await()
        } else data(key)
        Response.success(CryptoOperationResponse(true, result))
    }
    coEvery { api.cancelCryptoPayment(venue, any()) } returns Response.success(mockk(relaxed = true))
    suspend fun waitFor(predicate: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(10_000) { while (!predicate()) delay(10) }
    }
    suspend fun qr(): CryptoGestureUi {
        waitFor { ui().phase == "QR" || ui().phase == "ERROR" }
        assertThat(ui().phase).isEqualTo("QR")
        return ui()
    }
    fun event(key: String) = SocketEvent.CryptoPaymentConfirmed(
        "provider-$key", key, 1000, "MXN", "tx-$key", "1", "BTC", 1,
        null, null, null, null, venue, "2026-10-28T00:00:00Z")
    val orderA = CuidV1.next()
    try {
        begin(orderA)
        pay()
        val firstQr = qr()
        val keyA = posted.single().idempotencyKey!!
        val beforeA = db.localCryptoOperationDao().leer(venue, keyA)!!
        if (scenario == CryptoGestureCase.CANCEL_RETRY) {
            cancel()
            waitFor { ui().phase == "SELECT" }
            pay()
            assertThat(qr()).isEqualTo(firstQr)
            assertThat(posted).hasSize(1)
            assertThat(queried).containsExactly(keyA)
            assertThat(db.localCryptoOperationDao().contar(venue)).isEqualTo(1)
            assertThat(db.localShiftSessionDao().contarObligaciones(venue, "no-managed-session")).isEqualTo(1)
            return
        }
        if (scenario != CryptoGestureCase.LATE_CALLBACK) {
            confirm(event(keyA))
            waitFor { ui().phase == "SUCCESS" && queried.contains(keyA) }
        }
        reset()
        assertThat(ui().phase).isEqualTo("IDLE")
        val afterReset = db.localCryptoOperationDao().leer(venue, keyA)!!
        assertThat(afterReset.requestJson).isEqualTo(beforeA.requestJson)
        assertThat(afterReset.staffId).isEqualTo(beforeA.staffId)
        assertThat(afterReset.shiftMembership).isEqualTo(beforeA.shiftMembership)
        assertThat(afterReset.financialRecorded).isFalse()
        begin(if (scenario == CryptoGestureCase.SAME_BODY) orderA else CuidV1.next())
        pay()
        if (scenario == CryptoGestureCase.SAME_BODY) {
            // Fail on the extra GET of A instead of hanging behind its suspended response.
            waitFor { ui().phase in listOf("QR", "ERROR") || queried.count { it == keyA } > 1 }
            assertThat(posted).hasSize(2)
        }
        val secondQr = qr() // Regression: old K rejects B's contract, or returns A's QR for an equal body.
        assertThat(posted).hasSize(2)
        val keyB = posted.last().idempotencyKey!!
        assertThat(keyB).isNotEqualTo(keyA)
        assertThat(secondQr.paymentId).isEqualTo(keyB)
        assertThat(secondQr.requestId).isNotEqualTo(firstQr.requestId)
        assertThat(secondQr.url).isNotEqualTo(firstQr.url)
        assertThat(db.localShiftSessionDao().contarObligaciones(venue, "no-managed-session")).isEqualTo(2)
        if (scenario != CryptoGestureCase.FAILED_GET) {
            if (scenario == CryptoGestureCase.LATE_CALLBACK) confirm(event(keyA))
            oldGet.complete(data(keyA, recorded = true))
            waitFor { kotlinx.coroutines.runBlocking { db.localCryptoOperationDao().leer(venue, keyA)?.financialRecorded == true } }
            assertThat(ui()).isEqualTo(secondQr)
            // Exercise B's identity by cancelling/retrying it; looking at a private field would miss a stale clear.
            cancel()
            waitFor { ui().phase == "SELECT" }
            pay()
            assertThat(qr()).isEqualTo(secondQr)
            assertThat(posted).hasSize(2)
            assertThat(queried.last()).isEqualTo(keyB)
            assertThat(db.localShiftSessionDao().contarObligaciones(venue, "no-managed-session")).isEqualTo(1)
        }
        val afterA = db.localCryptoOperationDao().leer(venue, keyA)!!
        assertThat(afterA.requestJson).isEqualTo(beforeA.requestJson)
        assertThat(afterA.staffId).isEqualTo(beforeA.staffId)
        assertThat(afterA.terminalPaymentRequestId).isEqualTo(beforeA.terminalPaymentRequestId)
        assertThat(afterA.localShiftSessionId).isEqualTo(beforeA.localShiftSessionId)
        coVerify(exactly = 0) { api.initiateCryptoPayment(any(), any()) }
    } finally {
        // NonCancellable reconciliation must be released even if a functional assertion is RED.
        posted.firstOrNull()?.idempotencyKey?.let { key ->
            if (scenario != CryptoGestureCase.FAILED_GET && queried.contains(key)) {
                oldGet.complete(data(key, recorded = true))
                if (scenario != CryptoGestureCase.CANCEL_RETRY) {
                    waitFor { kotlinx.coroutines.runBlocking { db.localCryptoOperationDao().leer(venue, key)?.financialRecorded == true } }
                }
            } else oldGet.complete(data(key))
        }
    }
}

/** SavedState sólo apunta a K; el contrato y la responsabilidad siguen en Room. */
internal suspend fun checkCryptoRestoredEntry(
    db: AvoqadoDatabase, api: ApiService, venue: String,
    create: (androidx.lifecycle.SavedStateHandle) -> Unit, destroy: () -> Unit,
    begin: suspend (String) -> Unit, pay: () -> Unit, reset: () -> Unit,
    ui: () -> CryptoGestureUi, waitFor: suspend (() -> Boolean) -> Unit,
) {
    val posted = CopyOnWriteArrayList<CryptoPaymentRequest>()
    val queried = CopyOnWriteArrayList<String>()
    fun data(key: String) = CryptoOperationResultDto(paymentId = key, requestId = "provider-$key",
        paymentUrl = "https://example.test/$key", expiresAt = "2026-10-29T00:00:00Z", expiresInSeconds = 60,
        operation = CryptoOperationDto(key, "ISSUED", "PENDING", false))
    coEvery { api.initiateDurableCryptoPayment(venue, any()) } coAnswers {
        val request = secondArg<CryptoPaymentRequest>(); posted += request
        assertThat(db.localCryptoOperationDao().leer(venue, request.idempotencyKey!!)!!.requestJson).isNotEmpty()
        Response.success(CryptoOperationResponse(true, data(request.idempotencyKey!!)))
    }
    coEvery { api.getCryptoOperation(venue, any()) } coAnswers {
        val key = secondArg<String>(); queried += key
        Response.success(CryptoOperationResponse(true, data(key)))
    }
    val order = CuidV1.next()
    val firstHandle = androidx.lifecycle.SavedStateHandle()
    create(firstHandle); begin(order); pay(); waitFor { ui().phase in listOf("QR", "ERROR") }
    assertThat(ui().phase).isEqualTo("QR")
    val keyA = posted.single().idempotencyKey!!
    val original = db.localCryptoOperationDao().leer(venue, keyA)!!
    val saved = firstHandle.keys().associateWith { firstHandle.get<Any?>(it) }
    assertThat(saved["b2_crypto_operation_key"]).isEqualTo(keyA)
    destroy()
    // Android no guardó bundle: iniciar la pantalla no vuelve a emitir A ni elige por monto.
    create(androidx.lifecycle.SavedStateHandle()); begin(order)
    assertThat(posted).hasSize(1)
    assertThat(db.localCryptoOperationDao().leer(venue, keyA)).isEqualTo(original)
    destroy()
    create(androidx.lifecycle.SavedStateHandle(saved)); begin(order); pay()
    waitFor { ui().phase in listOf("QR", "ERROR") }
    assertThat(ui().phase).isEqualTo("QR")
    assertThat(ui().paymentId).isEqualTo(keyA)
    assertThat(posted).hasSize(1)
    assertThat(queried).containsExactly(keyA)
    val restored = db.localCryptoOperationDao().leer(venue, keyA)!!
    assertThat(restored.requestJson).isEqualTo(original.requestJson)
    assertThat(restored.staffId).isEqualTo(original.staffId)
    assertThat(restored.localShiftSessionId).isEqualTo(original.localShiftSessionId)
    assertThat(restored.financialRecorded).isFalse()
    reset(); begin(order); pay(); waitFor { posted.size == 2 && ui().phase in listOf("QR", "ERROR") }
    assertThat(ui().phase).isEqualTo("QR")
    assertThat(posted.last().idempotencyKey).isNotEqualTo(keyA)
    assertThat(db.localCryptoOperationDao().contar(venue)).isEqualTo(2)
    coVerify(exactly = 0) { api.initiateCryptoPayment(any(), any()) }
}

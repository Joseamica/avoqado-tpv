package com.jaac.avoqado_tpv.features.payment.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.features.payment.data.repository.FastPaymentRecorder
import com.jaac.avoqado_tpv.features.payment.data.repository.OrderPaymentRecorder
import com.jaac.avoqado_tpv.features.payment.domain.model.CardDetails
import com.jaac.avoqado_tpv.features.payment.domain.model.CardBrand
import com.jaac.avoqado_tpv.features.payment.domain.model.CardEntryMode
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt
import com.jaac.avoqado_tpv.features.payment.domain.model.QueuedPayment
import com.jaac.avoqado_tpv.features.payment.domain.model.SplitType
import com.jaac.avoqado_tpv.features.payment.domain.model.SyncStatus
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import com.jaac.avoqado_tpv.features.payment.domain.repository.PaymentQueueRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal

/**
 * Cubre [RecordPaymentUseCase.isRetryable] — la función que decide si el bucle de retry
 * (5 intentos, backoff exponencial) sigue intentando o se rinde.
 *
 * Task 8 / F-5: antes esto era `isRetriableError`, una cadena de ~18
 * `message.contains(...)` incluido `Regex("5\\d{2}")`. Ahora delega en
 * [com.jaac.avoqado_tpv.features.payment.domain.sync.classifySyncFailure]. Estos tests
 * prueban que la delegación quedó bien cableada Y que sobrevive exactamente los casos que
 * rompían la versión vieja (dígitos engañosos dentro del mensaje, wording distinto,
 * idioma distinto, mensaje vacío).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordPaymentUseCaseTest {

    private val fastRecorder = mockk<FastPaymentRecorder>(relaxed = true)
    private val orderRecorder = mockk<OrderPaymentRecorder>(relaxed = true)
    private val queue = mockk<PaymentQueueRepository>(relaxed = true)
    private val useCase = RecordPaymentUseCase(
        fastPaymentRecorder = fastRecorder,
        orderPaymentRecorder = orderRecorder,
        paymentQueueRepository = queue,
    )

    // --- Delegación correcta a classifySyncFailure -------------------------------------

    @Test
    fun `5xx es reintentable`() {
        assertThat(useCase.isRetryable(BackendHttpException(500, "Error del servidor (500)"))).isTrue()
        assertThat(useCase.isRetryable(BackendHttpException(503, "Service unavailable"))).isTrue()
    }

    @Test
    fun `400, 404 y 422 NO son reintentables`() {
        assertThat(useCase.isRetryable(BackendHttpException(400, "Bad request"))).isFalse()
        assertThat(useCase.isRetryable(BackendHttpException(404, "Venue no encontrado"))).isFalse()
        assertThat(useCase.isRetryable(BackendHttpException(422, "Unprocessable"))).isFalse()
    }

    @Test
    fun `un 409 SI se reintenta — no afirma que el pago quedo registrado`() {
        // Semántica nueva (fix de la cola, mismo que android 73b7f40 / ios d336599):
        // el reintento idempotente REAL del backend responde 200 con el pago existente,
        // nunca 409 (payment.tpv.service.ts, "Idempotent retry detected"). Cerrar la
        // fila con un 409 marcaba como sincronizada una venta que quizá no lo está —
        // irreversible: dejaba de reintentarse y se borraba a los 7 días. Ver el KDoc
        // del 409 en classifySyncFailure. NO revertir a isFalse.
        assertThat(useCase.isRetryable(BackendHttpException(409, "Duplicate payment"))).isTrue()
    }

    @Test
    fun `IOException de red es reintentable`() {
        assertThat(useCase.isRetryable(IOException("Unable to resolve host"))).isTrue()
    }

    @Test
    fun `null es reintentable (ante la duda, Retryable)`() {
        assertThat(useCase.isRetryable(null)).isTrue()
    }

    // --- Cambios de comportamiento DELIBERADOS vs. la version vieja --------------------
    // (ver KDoc de isRetryable / SyncOutcome — fix Critico de Task 1, NO revertir)

    @Test
    fun `401 ahora es reintentable (antes paraba el retry)`() {
        assertThat(useCase.isRetryable(BackendHttpException(401, "Unauthorized"))).isTrue()
    }

    @Test
    fun `403 ahora es reintentable (antes paraba el retry)`() {
        assertThat(useCase.isRetryable(BackendHttpException(403, "Forbidden"))).isTrue()
    }

    @Test
    fun `429 ahora es reintentable (antes paraba el retry)`() {
        // El propio retry loop implementa backoff exponencial - exactamente lo que pide
        // un 429 ("espera y reintenta", no "nunca").
        assertThat(useCase.isRetryable(BackendHttpException(429, "Demasiadas solicitudes"))).isTrue()
    }

    // --- El bug que esto reemplaza: digitos enganosos y wording -------------------------

    @Test
    fun `una referencia con 500 en un 400 NO se clasifica como servidor caido`() {
        // El Regex("5\\d{2}") viejo hacia match con "500" dentro de CUALQUIER texto.
        // Con classifySyncFailure solo importa el codigo HTTP real: un 400 (permanente)
        // sigue siendo permanente aunque la referencia traiga "500" adentro. (Antes este
        // caso usaba un 409, pero el 409 hoy es Retryable por diseño — con o sin dígitos
        // engañosos — así que ya no discrimina texto-vs-código; el 400 sí.)
        val error = BackendHttpException(400, "Bad request ref=000000500231")
        assertThat(useCase.isRetryable(error)).isFalse()
    }

    @Test
    fun `un monto que contiene 400 en un 503 SI se reintenta (clasifica por codigo, no texto)`() {
        val error = BackendHttpException(503, "Servicio no disponible al cobrar 400.00")
        assertThat(useCase.isRetryable(error)).isTrue()
    }

    @Test
    fun `wording distinto del mismo codigo no cambia la clasificacion`() {
        assertThat(useCase.isRetryable(BackendHttpException(503, "Servicio temporalmente no disponible"))).isTrue()
        assertThat(useCase.isRetryable(BackendHttpException(503, "Service temporarily unavailable"))).isTrue()
        assertThat(useCase.isRetryable(BackendHttpException(503, ""))).isTrue()
    }

    @Test
    fun `401 sin importar el idioma del mensaje sigue siendo reintentable`() {
        assertThat(useCase.isRetryable(BackendHttpException(401, "No autorizado"))).isTrue()
        assertThat(useCase.isRetryable(BackendHttpException(401, "Unauthorized"))).isTrue()
        assertThat(useCase.isRetryable(BackendHttpException(401, ""))).isTrue()
    }


    // Dinero confirmado: la escritura durable tiene que terminar ANTES del primer POST.
    private val cashContext = PaymentContext.FastPayment(
        venueId = "FULLTEST-venue",
        staffId = "FULLTEST-staff",
        shiftId = "FULLTEST-shift",
        amount = BigDecimal("187.00"),
        tip = BigDecimal("10.00"),
        rating = 4,
        merchantAccountId = null,
        deviceSerialNumber = "FULLTEST-terminal",
        idempotencyKey = "de9d91c6-13fc-40e8-8044-27e129096ca8",
        terminalPaymentRequestId = "FULLTEST-request",
    )
    private val receipt = PaymentReceipt(
        paymentId = "FULLTEST-payment",
        receiptUrl = "https://example.invalid/FULLTEST-receipt",
        accessKey = "FULLTEST-access",
        amount = BigDecimal("187.00"),
        tipAmount = BigDecimal("10.00"),
    )
    private val reference = "CASH-FULLTEST-reference"
    private val authorization = "EFECTIVO-FULLTEST"
    private val ownToken = "FULLTEST-own-claim"
    private var stored: QueuedPayment? = null

    private fun stubClaim() {
        coEvery { queue.enqueueAndClaim(any()) } coAnswers {
            val payment = firstArg<QueuedPayment>()
            val claimed = payment.copy(queueId = 7L, claimToken = ownToken, syncStatus = SyncStatus.SYNCING)
            stored = claimed
            Result.success(claimed)
        }
        coEvery { queue.markSynced(7L, ownToken) } returns 1
    }

    private fun assertCashFields(payment: QueuedPayment) {
        assertThat(payment.referenceNumber).isEqualTo("CASH-FULLTEST-reference")
        assertThat(payment.authorizationNumber).isEqualTo("EFECTIVO-FULLTEST")
        assertThat(payment.venueId).isEqualTo("FULLTEST-venue")
        assertThat(payment.staffId).isEqualTo("FULLTEST-staff")
        assertThat(payment.shiftId).isEqualTo("FULLTEST-shift")
        assertThat(payment.amount).isEqualTo(BigDecimal("187.00"))
        assertThat(payment.tip).isEqualTo(BigDecimal("10.00"))
        assertThat(payment.rating).isEqualTo(4)
        assertThat(payment.idempotencyKey).isEqualTo("de9d91c6-13fc-40e8-8044-27e129096ca8")
        assertThat(payment.terminalPaymentRequestId).isEqualTo("FULLTEST-request")
        assertThat(payment.deviceSerialNumber).isEqualTo("FULLTEST-terminal")
        assertThat(payment.merchantAccountId).isEmpty()
        assertThat(payment.maskedPan).isNull()
        assertThat(payment.cardBrand).isNull()
        assertThat(payment.entryMode).isEqualTo("MANUAL")
        assertThat(payment.isInternational).isFalse()
        assertThat(payment.createdAt).isGreaterThan(0L)
    }

    private suspend fun TestScope.assertWaitsForClaim(context: PaymentContext, angelPay: Boolean) {
        val committed = CompletableDeferred<Unit>()
        var sent = false
        coEvery { queue.enqueueAndClaim(any()) } coAnswers {
            val payment = firstArg<QueuedPayment>()
            assertCashFields(payment)
            assertThat(payment.orderId).isEqualTo(if (angelPay) "FULLTEST-order" else null)
            assertThat(payment.orderNumber).isEqualTo(if (angelPay) "FULLTEST-801" else null)
            assertThat(payment.processor).isEqualTo(if (angelPay) ProcessorType.ANGELPAY else ProcessorType.BLUMON)
            assertThat(payment.splitType).isEqualTo(SplitType.FULLPAYMENT)
            assertThat(payment.paidProductIds).isEmpty()
            assertThat(payment.equalPartsPartySize).isNull()
            assertThat(payment.equalPartsPayedFor).isNull()
            committed.await()
            val claimed = payment.copy(queueId = 7L, claimToken = ownToken, syncStatus = SyncStatus.SYNCING)
            stored = claimed
            Result.success(claimed)
        }
        coEvery { queue.markSynced(7L, ownToken) } returns 1
        coEvery { fastRecorder.recordPayment(any(), any(), any(), any()) } coAnswers {
            sent = true
            Result.success(receipt)
        }
        coEvery { orderRecorder.recordPayment(any(), any(), any(), any()) } coAnswers {
            sent = true
            Result.success(receipt)
        }
        val action = launch { useCase(context, CardDetails.CASH, authorization, reference) }
        try {
            runCurrent()
            coVerify(exactly = 1) { queue.enqueueAndClaim(any()) }
            assertThat(sent).isFalse()
            assertThat(action.isCompleted).isFalse()
            coVerify(exactly = 0) { fastRecorder.recordPayment(any(), any(), any(), any()) }
            coVerify(exactly = 0) { orderRecorder.recordPayment(any(), any(), any(), any()) }
            committed.complete(Unit)
            action.join()
            assertThat(sent).isTrue()
            assertThat(stored?.queueId).isEqualTo(7L)
            assertThat(stored?.claimToken).isEqualTo(ownToken)
            if (angelPay) {
                coVerify(exactly = 1) { orderRecorder.recordPayment(context, CardDetails.CASH, authorization, reference) }
                coVerify(exactly = 0) { fastRecorder.recordPayment(any(), any(), any(), any()) }
            } else {
                coVerify(exactly = 1) { fastRecorder.recordPayment(context, CardDetails.CASH, authorization, reference) }
                coVerify(exactly = 0) { orderRecorder.recordPayment(any(), any(), any(), any()) }
            }
        } finally {
            committed.complete(Unit)
            action.cancel()
            action.join()
        }
    }

    @Test
    fun `P1 efectivo rapido espera la fila durable antes del primer POST`() = runTest {
        assertWaitsForClaim(cashContext, angelPay = false)
    }

    @Test
    fun `P1 efectivo AngelPay de orden espera la fila y preserva su destino antes del POST`() = runTest {
        val context = PaymentContext.AngelPayPayment(
            venueId = "FULLTEST-venue", staffId = "FULLTEST-staff", shiftId = "FULLTEST-shift",
            amount = BigDecimal("187.00"), tip = BigDecimal("10.00"), rating = 4,
            deviceSerialNumber = "FULLTEST-terminal",
            idempotencyKey = "de9d91c6-13fc-40e8-8044-27e129096ca8",
            terminalPaymentRequestId = "FULLTEST-request",
            orderId = "FULLTEST-order", orderNumber = "FULLTEST-801",
            cardDetails = CardDetails.CASH, authorizationCode = authorization, referenceNumber = reference,
        )
        assertWaitsForClaim(context, angelPay = true)
    }

    @Test
    fun `P1 si la escritura durable falla no se hace ningun POST`() = runTest {
        val failure = IOException("FULLTEST disco no disponible")
        coEvery { queue.enqueueAndClaim(any()) } returns Result.failure(failure)
        coEvery { fastRecorder.recordPayment(any(), any(), any(), any()) } returns Result.success(receipt)
        val result = useCase(cashContext, CardDetails.CASH, authorization, reference)
        assertThat(result.exceptionOrNull()).isSameInstanceAs(failure)
        coVerify(exactly = 1) { queue.enqueueAndClaim(any()) }
        coVerify(exactly = 0) { fastRecorder.recordPayment(any(), any(), any(), any()) }
        coVerify(exactly = 0) { orderRecorder.recordPayment(any(), any(), any(), any()) }
        coVerify(exactly = 0) { queue.markSynced(any(), any()) }
        coVerify(exactly = 0) { queue.release(any(), any(), any(), any()) }
    }

    @Test
    fun `P1 cancelar el recorder conserva la fila SYNCING y su claim`() = runTest {
        stubClaim()
        val cancellation = CancellationException("FULLTEST pantalla cerrada")
        coEvery { fastRecorder.recordPayment(any(), any(), any(), any()) } throws cancellation
        val result = runCatching { useCase(cashContext, CardDetails.CASH, authorization, reference) }
        assertThat(result.exceptionOrNull()).isSameInstanceAs(cancellation)
        coVerify(exactly = 1) { queue.enqueueAndClaim(any()) }
        coVerify(exactly = 1) { fastRecorder.recordPayment(cashContext, CardDetails.CASH, authorization, reference) }
        assertThat(stored?.queueId).isEqualTo(7L)
        assertThat(stored?.claimToken).isEqualTo(ownToken)
        assertThat(stored?.syncStatus).isEqualTo(SyncStatus.SYNCING)
        coVerify(exactly = 0) { queue.markSynced(any(), any()) }
        coVerify(exactly = 0) { queue.release(any(), any(), any(), any()) }
        coVerify(exactly = 0) { queue.markPermanentlyFailed(any(), any(), any()) }
        coVerify(exactly = 0) { queue.deleteOldSyncedPayments(any()) }
    }

    @Test
    fun `P1 exito cierra por CAS la fila propia y conserva el split de la orden`() = runTest {
        stubClaim()
        val context = PaymentContext.OrderPayment(
            venueId = "FULLTEST-venue", staffId = "FULLTEST-staff", shiftId = "FULLTEST-shift",
            orderId = "FULLTEST-order", amount = BigDecimal("187.00"), tip = BigDecimal("10.00"), rating = 4,
            merchantAccountId = null, deviceSerialNumber = "FULLTEST-terminal",
            idempotencyKey = "de9d91c6-13fc-40e8-8044-27e129096ca8",
            terminalPaymentRequestId = "FULLTEST-request", splitType = SplitType.PERPRODUCT,
            paidProductIds = listOf("FULLTEST-item-1", "FULLTEST-item-2"),
            equalPartsPartySize = 4, equalPartsPayedFor = 2,
            isPortabilidad = true, serialNumbers = listOf("FULLTEST-serial"),
        )
        coEvery { orderRecorder.recordPayment(any(), any(), any(), any()) } returns Result.success(receipt)
        val result = useCase(context, CardDetails.CASH, authorization, reference)
        assertThat(result.getOrNull()).isSameInstanceAs(receipt)
        coVerifyOrder {
            queue.enqueueAndClaim(any())
            orderRecorder.recordPayment(context, CardDetails.CASH, authorization, reference)
            queue.markSynced(7L, ownToken)
        }
        coVerify(exactly = 1) { queue.enqueueAndClaim(any()) }
        coVerify(exactly = 1) { queue.markSynced(7L, ownToken) }
        val row = requireNotNull(stored)
        assertCashFields(row)
        assertThat(row.orderId).isEqualTo("FULLTEST-order")
        assertThat(row.processor).isEqualTo(ProcessorType.BLUMON)
        assertThat(row.splitType).isEqualTo(SplitType.PERPRODUCT)
        assertThat(row.paidProductIds).containsExactly("FULLTEST-item-1", "FULLTEST-item-2").inOrder()
        assertThat(row.equalPartsPartySize).isEqualTo(4)
        assertThat(row.equalPartsPayedFor).isEqualTo(2)
        assertThat(row.isPortabilidad).isTrue()
        assertThat(row.serialNumbers).containsExactly("FULLTEST-serial")
        coVerify(exactly = 0) { queue.release(any(), any(), any(), any()) }
        coVerify(exactly = 0) { fastRecorder.recordPayment(any(), any(), any(), any()) }
    }

    @Test
    fun `P1 fallo permanente suelta el claim propio sin borrar ni acreditar el pago`() = runTest {
        stubClaim()
        val failure = BackendHttpException(422, "FULLTEST orden cerrada")
        coEvery { fastRecorder.recordPayment(any(), any(), any(), any()) } returns Result.failure(failure)
        coEvery { queue.release(7L, ownToken, any(), any()) } coAnswers {
            stored = stored?.copy(syncStatus = SyncStatus.PENDING, claimToken = null, lastError = arg(3))
            1
        }
        val result = useCase(cashContext, CardDetails.CASH, authorization, reference)
        assertThat(result.exceptionOrNull()).isSameInstanceAs(failure)
        coVerify(exactly = 1) { queue.enqueueAndClaim(any()) }
        coVerify(exactly = 1) { fastRecorder.recordPayment(cashContext, CardDetails.CASH, authorization, reference) }
        coVerify(exactly = 1) { queue.release(7L, ownToken, any(), match { it.contains("FULLTEST orden cerrada") }) }
        assertThat(stored?.queueId).isEqualTo(7L)
        assertThat(stored?.referenceNumber).isEqualTo(reference)
        assertThat(stored?.lastError).contains("FULLTEST orden cerrada")
        coVerify(exactly = 0) { queue.markSynced(any(), any()) }
        coVerify(exactly = 0) { queue.deleteOldSyncedPayments(any()) }
    }


    @Test
    fun `P1 replay ya encolado conserva UUID y deja al worker cerrar o liberar su claim`() = runTest {
        val failure = BackendHttpException(422, "FULLTEST orden cerrada en replay")
        coEvery { fastRecorder.recordPayment(any(), any(), any(), any()) } returnsMany listOf(
            Result.success(receipt), Result.failure(failure),
        )
        val success = useCase(cashContext, CardDetails.CASH, authorization, reference, alreadyQueued = true)
        val rejected = useCase(cashContext, CardDetails.CASH, authorization, reference, alreadyQueued = true)

        assertThat(success.getOrNull()).isSameInstanceAs(receipt)
        assertThat(rejected.exceptionOrNull()).isSameInstanceAs(failure)
        assertThat(cashContext.idempotencyKey).isEqualTo("de9d91c6-13fc-40e8-8044-27e129096ca8")
        coVerify(exactly = 2) {
            fastRecorder.recordPayment(
                match { it === cashContext && it.idempotencyKey == "de9d91c6-13fc-40e8-8044-27e129096ca8" },
                CardDetails.CASH, authorization, reference,
            )
        }
        coVerify(exactly = 0) { orderRecorder.recordPayment(any(), any(), any(), any()) }
        coVerify(exactly = 0) { queue.enqueue(any()) }
        coVerify(exactly = 0) { queue.enqueueAndClaim(any()) }
        coVerify(exactly = 0) { queue.markSynced(any(), any()) }
        coVerify(exactly = 0) { queue.release(any(), any(), any(), any()) }
        coVerify(exactly = 0) { queue.markPermanentlyFailed(any(), any(), any()) }
    }

    @Test
    fun `P1 tarjeta conserva el registro y UUID sin entrar a la cola de efectivo`() = runTest {
        val context = cashContext.copy(merchantAccountId = "FULLTEST-merchant")
        val card = CardDetails(
            maskedPan = "411111******1111",
            cardBrand = CardBrand.VISA,
            entryMode = CardEntryMode.CHIP,
            isCash = false,
        )
        val failure = BackendHttpException(422, "FULLTEST rechazo de registro de tarjeta")
        coEvery { fastRecorder.recordPayment(any(), any(), any(), any()) } returnsMany listOf(
            Result.success(receipt), Result.failure(failure),
        )
        // Cuatro argumentos: el valor por defecto NO debe convertir una tarjeta en efectivo.
        val success = useCase(context, card, "FULLTEST-auth-card", "FULLTEST-reference-card")
        val rejected = useCase(context, card, "FULLTEST-auth-card", "FULLTEST-reference-card")

        assertThat(card.isCash).isFalse()
        assertThat(success.getOrNull()).isSameInstanceAs(receipt)
        assertThat(rejected.exceptionOrNull()).isSameInstanceAs(failure)
        coVerify(exactly = 2) {
            fastRecorder.recordPayment(
                match { it === context && it.idempotencyKey == "de9d91c6-13fc-40e8-8044-27e129096ca8" },
                card, "FULLTEST-auth-card", "FULLTEST-reference-card",
            )
        }
        coVerify(exactly = 0) { orderRecorder.recordPayment(any(), any(), any(), any()) }
        coVerify(exactly = 0) { queue.enqueue(any()) }
        coVerify(exactly = 0) { queue.enqueueAndClaim(any()) }
        coVerify(exactly = 0) { queue.markSynced(any(), any()) }
        coVerify(exactly = 0) { queue.release(any(), any(), any(), any()) }
        coVerify(exactly = 0) { queue.markPermanentlyFailed(any(), any(), any()) }
    }
}

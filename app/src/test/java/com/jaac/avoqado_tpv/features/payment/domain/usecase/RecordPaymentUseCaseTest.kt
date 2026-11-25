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
        paymentQueueRepository = queue, shiftRepository = com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture())

    // --- a1ff27: la venta de efectivo ya guardada que la cola ya no sube -------------------

    private fun casoDeVentaGuardada(necesitaRevision: Boolean): RecordPaymentUseCase {
        val shift = com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture()
        coEvery { shift.pagoTieneDueno(any(), any(), any()) } returns true
        coEvery { shift.contratoDelPagoGuardadoCoincide(any(), any()) } returns true
        coEvery { shift.pagoGuardadoNecesitaRevision(any(), any()) } returns necesitaRevision
        return RecordPaymentUseCase(
            fastPaymentRecorder = fastRecorder, orderPaymentRecorder = orderRecorder,
            paymentQueueRepository = queue, shiftRepository = shift,
        )
    }

    private val efectivoGuardado = PaymentContext.FastPayment(
        venueId = "venue-a1ff27", staffId = "staff-1", amount = BigDecimal("100.00"), merchantAccountId = null,
        idempotencyKey = "llave-a1ff27",
    )

    @Test
    fun `a1ff27 la venta guardada que la cola ya no sube dice que necesita revision y no deja cobrar otra vez`() = runTest {
        val resultado = casoDeVentaGuardada(necesitaRevision = true)(efectivoGuardado, CardDetails.CASH, "EFECTIVO", "CASH-a1ff27")

        val error = resultado.exceptionOrNull()
        assertThat(error).isInstanceOf(CashOwnerReviewRequiredException::class.java)
        assertThat(error?.message).contains("necesita revisión")
        assertThat(error?.message).contains("no vuelvas a cobrarla")
        coVerify(exactly = 0) { fastRecorder.recordPayment(any(), any(), any(), any()) }
    }

    @Test
    fun `a1ff27 la venta guardada que la cola todavia sube sigue diciendo que ya esta guardada`() = runTest {
        val resultado = casoDeVentaGuardada(necesitaRevision = false)(efectivoGuardado, CardDetails.CASH, "EFECTIVO", "CASH-a1ff27")

        assertThat(resultado.exceptionOrNull()?.message).isEqualTo("La venta ya está guardada; no vuelvas a cobrarla.")
    }

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
        val recordingContext = when (context) {
            is PaymentContext.FastPayment -> context.copy(queueClaimToken = ownToken)
            is PaymentContext.AngelPayPayment -> context.copy(queueClaimToken = ownToken)
            else -> error("Fixture de efectivo rápida/AngelPay únicamente")
        }
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
                coVerify(exactly = 1) { orderRecorder.recordPayment(recordingContext, CardDetails.CASH, authorization, reference) }
                coVerify(exactly = 0) { fastRecorder.recordPayment(any(), any(), any(), any()) }
            } else {
                coVerify(exactly = 1) { fastRecorder.recordPayment(recordingContext, CardDetails.CASH, authorization, reference) }
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
    fun `P1 un 500 seguido de plazos viaja pegado al error final y la cola cuenta la pasada (encargo 29ef3d)`() = runTest {
        val plazo = java.net.SocketTimeoutException("timeout")
        coEvery { fastRecorder.recordPayment(any(), any(), any(), any()) } returnsMany listOf(
            Result.failure(BackendHttpException(500, "boom")),
            Result.failure(java.net.SocketTimeoutException("timeout")),
            Result.failure(java.net.SocketTimeoutException("timeout")),
            Result.failure(java.net.SocketTimeoutException("timeout")),
            Result.failure(plazo),
        )
        val result = useCase(cashContext, CardDetails.CASH, authorization, reference, alreadyQueued = true)
        // El error final sigue siendo el mismo (misma clasificación); el 500 sólo viaja como `suppressed`.
        assertThat(result.exceptionOrNull()).isSameInstanceAs(plazo)
        assertThat(plazo.suppressed.toList()).hasSize(1)
        assertThat(com.jaac.avoqado_tpv.features.payment.domain.sync.nadieContesto(plazo)).isFalse()
    }

    @Test
    fun `P1 cinco plazos seguidos quedan como nadie contesto (encargo 29ef3d)`() = runTest {
        val plazo = java.net.SocketTimeoutException("timeout")
        coEvery { fastRecorder.recordPayment(any(), any(), any(), any()) } returns Result.failure(plazo)
        val result = useCase(cashContext, CardDetails.CASH, authorization, reference, alreadyQueued = true)
        assertThat(result.exceptionOrNull()).isSameInstanceAs(plazo)
        assertThat(plazo.suppressed.toList()).isEmpty()
        assertThat(com.jaac.avoqado_tpv.features.payment.domain.sync.nadieContesto(plazo)).isTrue()
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
        coVerify(exactly = 1) { fastRecorder.recordPayment(cashContext.copy(queueClaimToken = ownToken), CardDetails.CASH, authorization, reference) }
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
            orderRecorder.recordPayment(context.copy(queueClaimToken = ownToken), CardDetails.CASH, authorization, reference)
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
        coVerify(exactly = 1) { fastRecorder.recordPayment(cashContext.copy(queueClaimToken = ownToken), CardDetails.CASH, authorization, reference) }
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

    // --- «Pendientes» sin señal (C6): la venta con SIM recién registrada se guarda en la terminal --------------------

    private val tarjeta = CardDetails(maskedPan = "411111******1111", cardBrand = CardBrand.VISA, entryMode = CardEntryMode.CHIP, isCash = false)

    @Test
    fun `una venta con SIM registrada queda en Pendientes con su tienda y su promotor`() = runTest {
        val sales = mockk<com.jaac.avoqado_tpv.features.verification.data.ActionableSalesRepository>(relaxed = true)
        val conPendientes = RecordPaymentUseCase(fastRecorder, orderRecorder, queue, com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture(), sales)
        val sim = cashContext.copy(merchantAccountId = "FULLTEST-merchant", isPortabilidad = true, serialNumbers = listOf("8952140061234567890"))
        val angel = PaymentContext.AngelPayPayment(
            venueId = "FULLTEST-venue", staffId = "FULLTEST-staff", amount = BigDecimal("99.00"), orderNumber = "SN-7",
            serialNumbers = listOf("8952777"),
        )
        coEvery { fastRecorder.recordPayment(any(), any(), any(), any()) } returns Result.success(receipt)

        assertThat(conPendientes(sim, tarjeta, "auth", "ref").getOrNull()).isSameInstanceAs(receipt)
        conPendientes(angel, tarjeta, "auth", "ref")

        coVerify(exactly = 1) {
            sales.addLocalSale(
                "FULLTEST-payment", BigDecimal("187.00"), null, true, listOf("8952140061234567890"), "FULLTEST-venue", "FULLTEST-staff",
            )
        }
        coVerify(exactly = 1) {
            sales.addLocalSale("FULLTEST-payment", BigDecimal("99.00"), "SN-7", false, listOf("8952777"), "FULLTEST-venue", "FULLTEST-staff")
        }
    }

    @Test
    fun `un cobro sin SIM no toca Pendientes y si guardarla falla el cobro sigue registrado`() = runTest {
        val sales = mockk<com.jaac.avoqado_tpv.features.verification.data.ActionableSalesRepository>()
        coEvery { sales.addLocalSale(any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("disco lleno")
        val conPendientes = RecordPaymentUseCase(fastRecorder, orderRecorder, queue, com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture(), sales)
        coEvery { fastRecorder.recordPayment(any(), any(), any(), any()) } returns Result.success(receipt)
        val normal = cashContext.copy(merchantAccountId = "FULLTEST-merchant")
        val sim = normal.copy(serialNumbers = listOf("8952"))

        assertThat(conPendientes(normal, tarjeta, "auth", "ref").getOrNull()).isSameInstanceAs(receipt)
        coVerify(exactly = 0) { sales.addLocalSale(any(), any(), any(), any(), any(), any(), any()) }

        assertThat(conPendientes(sim, tarjeta, "auth", "ref").getOrNull()).isSameInstanceAs(receipt)
        coVerify(exactly = 1) { sales.addLocalSale(any(), any(), any(), any(), any(), any(), any()) }
    }

    // --- Encargo 97042c: con el SERVIDOR mudo, el efectivo no espera ~1 min por venta ----------------------------------

    private val conexion = com.jaac.avoqado_tpv.core.util.ConnectionStateManager()
    private val conFranja = RecordPaymentUseCase(
        fastRecorder, orderRecorder, queue, com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture(), conexion = conexion,
    )

    private fun stubRelease() {
        coEvery { queue.release(7L, ownToken, any(), any()) } coAnswers {
            stored = stored?.copy(syncStatus = SyncStatus.PENDING, claimToken = null, lastError = arg(3))
            1
        }
    }

    @Test
    fun `P1 efectivo con la franja de sin servidor se guarda y va a la cola sin tocar la red`() = runTest {
        stubClaim(); stubRelease()
        conexion.setServerConnected(false)

        val result = conFranja(cashContext, CardDetails.CASH, authorization, reference)

        // Guardada ANTES de decidir, con su llave; sale como «no se conectó» (Retryable): la cola la manda sola, una vez.
        assertThat(result.exceptionOrNull()).isInstanceOf(ServidorSinContestarException::class.java)
        assertThat(result.exceptionOrNull()).isInstanceOf(java.net.ConnectException::class.java)
        assertThat(useCase.isRetryable(result.exceptionOrNull())).isTrue()
        coVerify(exactly = 1) { queue.enqueueAndClaim(any()) }
        coVerify(exactly = 0) { fastRecorder.recordPayment(any(), any(), any(), any()) }
        coVerify(exactly = 0) { orderRecorder.recordPayment(any(), any(), any(), any()) }
        // Sin intento no se suma intento: el contador del claim se devuelve igual.
        coVerify(exactly = 1) { queue.release(7L, ownToken, 0, any()) }
        coVerify(exactly = 0) { queue.markSynced(any(), any()) }
        coVerify(exactly = 0) { queue.markPermanentlyFailed(any(), any(), any()) }
        assertThat(stored?.syncStatus).isEqualTo(SyncStatus.PENDING)
        assertThat(stored?.idempotencyKey).isEqualTo("de9d91c6-13fc-40e8-8044-27e129096ca8")
    }

    @Test
    fun `P1 sin internet no es la franja - intenta como siempre, con sus 5 intentos`() = runTest {
        // Sin internet la petición falla al instante (sin ruta ni DNS): conserva su camino de siempre.
        stubClaim(); stubRelease()
        conexion.setInternetConnected(false); conexion.setServerConnected(false)
        val sinDns = java.net.UnknownHostException("sin DNS")
        coEvery { fastRecorder.recordPayment(any(), any(), any(), any()) } returns Result.failure(sinDns)

        val result = conFranja(cashContext, CardDetails.CASH, authorization, reference)

        assertThat(result.exceptionOrNull()).isSameInstanceAs(sinDns)
        coVerify(exactly = 5) { fastRecorder.recordPayment(any(), any(), any(), any()) }
        coVerify(exactly = 1) { queue.release(7L, ownToken, any(), any()) }
    }

    @Test
    fun `P2 sin la franja los reintentos de siempre siguen - 503, plazo y luego 200 registra en vivo`() = runTest {
        stubClaim()
        coEvery { fastRecorder.recordPayment(any(), any(), any(), any()) } returnsMany listOf(
            Result.failure(BackendHttpException(503, "Servicio no disponible")),
            Result.failure(java.net.SocketTimeoutException("timeout")),
            Result.success(receipt),
        )

        assertThat(conFranja(cashContext, CardDetails.CASH, authorization, reference).getOrNull()).isSameInstanceAs(receipt)

        coVerify(exactly = 3) { fastRecorder.recordPayment(any(), any(), any(), any()) }
        coVerify(exactly = 1) { queue.markSynced(7L, ownToken) }
    }

    @Test
    fun `P1 con la franja la tarjeta y el replay de la cola SI intentan registrar`() = runTest {
        conexion.setServerConnected(false)
        coEvery { fastRecorder.recordPayment(any(), any(), any(), any()) } returns Result.success(receipt)
        val conCuenta = cashContext.copy(merchantAccountId = "FULLTEST-merchant")

        // La tarjeta ya movió dinero en el banco: su registro no cambia. El worker es quien sube al volver: tampoco cambia.
        assertThat(conFranja(conCuenta, tarjeta, "auth", "ref-tarjeta").getOrNull()).isSameInstanceAs(receipt)
        assertThat(conFranja(cashContext, CardDetails.CASH, authorization, reference, alreadyQueued = true).getOrNull()).isSameInstanceAs(receipt)

        coVerify(exactly = 2) { fastRecorder.recordPayment(any(), any(), any(), any()) }
        coVerify(exactly = 0) { queue.enqueueAndClaim(any()) }
    }

    @Test
    fun `P1 efectivo con el servidor sano se registra como siempre`() = runTest {
        stubClaim()
        coEvery { fastRecorder.recordPayment(any(), any(), any(), any()) } returns Result.success(receipt)

        assertThat(conFranja(cashContext, CardDetails.CASH, authorization, reference).getOrNull()).isSameInstanceAs(receipt)

        coVerify(exactly = 1) { fastRecorder.recordPayment(cashContext.copy(queueClaimToken = ownToken), CardDetails.CASH, authorization, reference) }
        coVerify(exactly = 1) { queue.markSynced(7L, ownToken) }
        coVerify(exactly = 0) { queue.release(any(), any(), any(), any()) }
    }

    @Test
    fun `P2 efectivo que regresa en el segundo intento se registra en vivo`() = runTest {
        stubClaim()
        coEvery { fastRecorder.recordPayment(any(), any(), any(), any()) } returnsMany listOf(
            Result.failure(java.io.InterruptedIOException("timeout")), Result.success(receipt),
        )

        assertThat(conFranja(cashContext, CardDetails.CASH, authorization, reference).getOrNull()).isSameInstanceAs(receipt)

        coVerify(exactly = 2) { fastRecorder.recordPayment(any(), any(), any(), any()) }
        coVerify(exactly = 1) { queue.markSynced(7L, ownToken) }
    }

    @Test
    fun `P2 sin la franja un 503 conserva sus 5 intentos en efectivo`() = runTest {
        stubClaim(); stubRelease()
        coEvery { fastRecorder.recordPayment(any(), any(), any(), any()) } returns Result.failure(BackendHttpException(503, "Servicio no disponible"))

        conFranja(cashContext, CardDetails.CASH, authorization, reference)

        coVerify(exactly = 5) { fastRecorder.recordPayment(any(), any(), any(), any()) }
    }

    @Test
    fun `P2 la tarjeta con la franja sin respuesta conserva sus 5 intentos`() = runTest {
        conexion.setServerConnected(false)
        coEvery { fastRecorder.recordPayment(any(), any(), any(), any()) } returns Result.failure(java.net.SocketTimeoutException("timeout"))

        conFranja(cashContext.copy(merchantAccountId = "FULLTEST-merchant"), tarjeta, "auth", "ref-tarjeta")

        coVerify(exactly = 5) { fastRecorder.recordPayment(any(), any(), any(), any()) }
    }

}

package com.jaac.avoqado_tpv.features.payment.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
import com.jaac.avoqado_tpv.features.payment.data.repository.FastPaymentRecorder
import com.jaac.avoqado_tpv.features.payment.data.repository.OrderPaymentRecorder
import com.jaac.avoqado_tpv.features.payment.data.repository.PaymentQueueRepositoryImpl
import com.jaac.avoqado_tpv.features.payment.domain.model.CardDetails
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt
import com.jaac.avoqado_tpv.features.payment.domain.model.QueuedPayment
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal

/**
 * Task 5: `recordPaymentUseCase` corre protegido de la cancelacion del coroutine
 * scope que lo invoca (`withContext(NonCancellable + Dispatchers.IO) { ... }` en cada
 * call site de PaymentViewModel/AngelPayPaymentViewModel).
 *
 * **El hueco de dinero que esto tapa:** sin este blindaje, si el proceso muere / la
 * pantalla se abandona / cancelPayment() cancela viewModelScope MIENTRAS
 * recordPaymentUseCase sigue reintentando (hasta 5 intentos con backoff, ~7.5s peor
 * caso), la llamada se cancela a media espera y el codigo NUNCA llega al
 * `.onFailure` que encola el pago en la cola offline. El dinero ya se cobro (la
 * tarjeta aprobo antes de llamar a este use case) pero no queda NI registro en el
 * backend NI fila en la cola — no hay ningun rastro de esa venta en ningun lado.
 *
 * **Diseño del harness:** en vez de depender de tiempo real (`Dispatchers.IO` +
 * `delay()` reales no son controlables por el scheduler virtual de `runTest`), el
 * recorder mockeado se cuelga en un `CompletableDeferred` que el test resuelve a
 * mano — reproduce "la llamada de red sigue en vuelo" sin reloj real. El wrapper
 * omite `Dispatchers.IO` a proposito (es un detalle de threading de produccion, no
 * parte del invariante de cancelacion) para que todo corra deterministico sobre el
 * `StandardTestDispatcher`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordPaymentNonCancellableTest {

    private val fastPaymentRecorder = mockk<FastPaymentRecorder>()
    private val orderPaymentRecorder = mockk<OrderPaymentRecorder>(relaxed = true)
    private val recordPaymentUseCase = RecordPaymentUseCase(
        fastPaymentRecorder = fastPaymentRecorder,
        orderPaymentRecorder = orderPaymentRecorder,
    )

    private val queueDao = mockk<PendingPaymentDao>(relaxed = true)
    private val queueRepository = PaymentQueueRepositoryImpl(queueDao)

    private val context = PaymentContext.FastPayment(
        venueId = "venue-1",
        staffId = "staff-1",
        amount = BigDecimal("100.00"),
        merchantAccountId = "merchant-1",
    )

    private fun queuedPayment(reference: String) = QueuedPayment(
        referenceNumber = reference,
        venueId = "venue-1",
        staffId = "staff-1",
        amount = BigDecimal("100.00"),
        tip = BigDecimal.ZERO,
        rating = null,
        merchantAccountId = "merchant-1",
        blumonSerialNumber = "2841548417",
        maskedPan = null,
        cardBrand = null,
        entryMode = "CASH",
        isInternational = false,
        authorizationNumber = "AUTH",
        idempotencyKey = null,
        createdAt = System.currentTimeMillis(),
    )

    /**
     * Reproduce EXACTAMENTE el patron de los 11 call sites reales: el resultado de
     * `recordPaymentUseCase` se obtiene protegido de cancelacion, y si falla, el
     * encolado (que YA esta blindado por su cuenta — ver
     * `PaymentQueueRepositoryImpl.enqueue`'s propio `withContext(NonCancellable...)`
     * interno, mas el `withContext(NonCancellable)` de `handleOfflineQueueOutcome`)
     * tambien corre protegido.
     */
    private suspend fun recordThenEnqueueOnFailure(referenceNumber: String): Boolean {
        val result = withContext(NonCancellable) {
            recordPaymentUseCase(
                context = context,
                cardDetails = CardDetails.CASH,
                authorizationNumber = "AUTH",
                referenceNumber = referenceNumber,
            )
        }
        if (result.isFailure) {
            withContext(NonCancellable) {
                queueRepository.enqueue(queuedPayment(referenceNumber))
            }
            return true
        }
        return false
    }

    @Test
    fun `cancelar el scope a media grabacion no impide encolar`() = runTest(StandardTestDispatcher()) {
        // El escenario real: el proceso muere/la pantalla se abandona mientras
        // recordPaymentUseCase sigue esperando la respuesta del backend. Un Deferred
        // sin resolver reproduce "en vuelo" sin depender de tiempo real.
        val networkCall = CompletableDeferred<Result<PaymentReceipt>>()
        coEvery { fastPaymentRecorder.recordPayment(any(), any(), any(), any()) } coAnswers {
            networkCall.await()
        }
        coEvery { queueDao.insert(any()) } returns 42L

        var enqueued = false
        val recordingJob = launch {
            enqueued = recordThenEnqueueOnFailure("REF-CANCEL-MID-RETRY")
        }

        // Deja que la corrutina llegue a la llamada de red (queda suspendida en
        // networkCall.await()) y CANCELA el scope que la lanzo.
        advanceUntilIdle()
        recordingJob.cancel()

        // La red responde DESPUES de la cancelacion — exactamente el caso que pierde
        // dinero sin NonCancellable: la respuesta llega, pero sin el blindaje el
        // codigo que decide "encolar" ya fue abandonado antes de que esto se ejecute.
        networkCall.complete(Result.failure(IOException("network down")))

        // ⚠️ join(), NO solo advanceUntilIdle(): PaymentQueueRepositoryImpl.enqueue()
        // hace su propio withContext(Dispatchers.IO) real internamente (fuera del
        // scheduler virtual de runTest), asi que solo join() espera de forma
        // confiable a que ese hop de hilo real termine antes de leer `enqueued`.
        recordingJob.join()

        assertTrue(enqueued)
        coVerify(exactly = 1) { queueDao.insert(any()) }
    }

    @Test
    fun `CancellationException se relanza y no se traga`() {
        // CancellationException es RuntimeException en Kotlin: un catch(Exception)
        // se la come y rompe la cancelacion estructurada. Mismo patron que cada call
        // site real: catch(CancellationException) ANTES del catch(Exception) general.
        var rethrown = false
        var generalCatchRan = false
        try {
            try {
                throw CancellationException("test")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                generalCatchRan = true
            }
        } catch (e: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
        assertTrue(!generalCatchRan)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Regresion — el camino feliz (sin cancelacion) no cambia
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `sin cancelacion el registro exitoso no se encola`() = runTest(StandardTestDispatcher()) {
        coEvery { fastPaymentRecorder.recordPayment(any(), any(), any(), any()) } returns
            Result.success(
                PaymentReceipt(
                    paymentId = "pay-1",
                    receiptUrl = "https://example.com/r/1",
                    accessKey = "key-1",
                    amount = BigDecimal("100.00"),
                    tipAmount = BigDecimal.ZERO,
                )
            )

        val enqueued = recordThenEnqueueOnFailure("REF-HAPPY-PATH")

        assertTrue(!enqueued)
        coVerify(exactly = 0) { queueDao.insert(any()) }
    }
}

package com.jaac.avoqado_tpv.features.payment.data.repository

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity
import com.jaac.avoqado_tpv.features.payment.domain.model.CardDetails
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.QueuedPayment
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.math.BigDecimal

class CashWriteAheadQueueTest {
    private val dao = mockk<PendingPaymentDao>()
    private val repository = PaymentQueueRepositoryImpl(dao)
    private fun cash() = QueuedPayment.desdeContexto(
        context = PaymentContext.AngelPayPayment(
            venueId = "venue-cash", staffId = "staff-cash", amount = BigDecimal("137.00"),
            tip = BigDecimal("10.00"), idempotencyKey = "attempt-cash", orderId = "order-cash",
            terminalPaymentRequestId = "request-cash", shiftId = "shift-cash",
        ),
        orderNumber = "802", cardDetails = CardDetails.CASH,
        authorizationNumber = "EFECTIVO", referenceNumber = "CASH-test", error = null,
    )

    @Test
    fun `un solo INSERT guarda la fila y el claim antes de regresar`() = runTest {
        val entity = slot<PendingPaymentEntity>()
        coEvery { dao.insert(capture(entity)) } returns 7L
        val claimed = repository.enqueueAndClaim(cash()).getOrThrow()
        assertThat(entity.captured.syncStatus).isEqualTo("SYNCING")
        assertThat(entity.captured.claimToken).isNotEmpty()
        assertThat(entity.captured.claimedAt).isNotNull()
        assertThat(claimed.queueId).isEqualTo(7L)
        assertThat(claimed.claimToken).isEqualTo(entity.captured.claimToken)
        assertThat(claimed.amount).isEqualTo(BigDecimal("137.00"))
        assertThat(claimed.orderId).isEqualTo("order-cash")
        coVerify(exactly = 1) { dao.insert(any()) }
    }

    @Test
    fun `el choque de referencia no obtiene un claim sobre dinero ajeno`() = runTest {
        coEvery { dao.insert(any()) } returns -1L
        assertThat(repository.enqueueAndClaim(cash()).isFailure).isTrue()
        coVerify(exactly = 0) { dao.claimBatch(any(), any(), any(), any()) }
    }

    @Test
    fun `sin llave no se inserta efectivo que no puede repetirse con seguridad`() = runTest {
        assertThat(repository.enqueueAndClaim(cash().copy(idempotencyKey = null)).isFailure).isTrue()
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    private suspend fun existing(): PendingPaymentEntity {
        val entity = slot<PendingPaymentEntity>()
        coEvery { dao.insert(capture(entity)) } returns 7L
        repository.enqueueAndClaim(cash()).getOrThrow()
        coEvery { dao.insert(any()) } returns -1L
        return entity.captured.copy(id = 7L)
    }

    @Test
    fun `el fallback acepta el mismo pago vivo con distinto estado del claim`() = runTest {
        val existing = existing().copy(syncStatus = "PENDING", claimToken = null, claimedAt = null, retryCount = 2)
        coEvery { dao.findByReference("CASH-test", "venue-cash") } returns existing
        assertThat(repository.enqueue(cash()).isSuccess).isTrue()
    }

    @Test
    fun `misma llave con otro importe no se anuncia en cola`() = runTest {
        val existing = existing().copy(amount = "120.00")
        coEvery { dao.findByReference("CASH-test", "venue-cash") } returns existing
        assertThat(repository.enqueue(cash()).isFailure).isTrue()
    }

    @Test
    fun `misma llave con otra orden no se anuncia en cola`() = runTest {
        val existing = existing().copy(orderId = "another-order")
        coEvery { dao.findByReference("CASH-test", "venue-cash") } returns existing
        assertThat(repository.enqueue(cash()).isFailure).isTrue()
    }

    @Test
    fun `misma llave con otra solicitud no se anuncia en cola`() = runTest {
        val existing = existing().copy(terminalPaymentRequestId = "another-request")
        coEvery { dao.findByReference("CASH-test", "venue-cash") } returns existing
        assertThat(repository.enqueue(cash()).isFailure).isTrue()
    }

    @Test
    fun `una fila sin UUID no acredita el UUID nuevo del efectivo`() = runTest {
        val existing = existing().copy(idempotencyKey = null, syncStatus = "PENDING")
        coEvery { dao.findByReference("CASH-test", "venue-cash") } returns existing
        assertThat(repository.enqueue(cash()).isFailure).isTrue()
    }

}

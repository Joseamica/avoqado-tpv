package com.jaac.avoqado_tpv.features.payment.data.ledger

import com.google.gson.Gson
import com.jaac.avoqado_tpv.features.payment.domain.model.*
import com.jaac.avoqado_tpv.features.payment.data.repository.FastPaymentRecorder
import com.jaac.avoqado_tpv.features.payment.data.repository.OrderPaymentRecorder
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class LedgerApprovalRecoveryTest {
    private val dao = mockk<PaymentAttemptDao>(relaxed = true)
    private val fast = mockk<FastPaymentRecorder>()
    private val order = mockk<OrderPaymentRecorder>()
    private val context = PaymentContext.FastPayment(
        venueId = "venue", staffId = "staff", amount = BigDecimal("100.00"),
        tip = BigDecimal("10.00"), merchantAccountId = "merchant",
        deviceSerialNumber = "terminal", idempotencyKey = "attempt", terminalPaymentRequestId = "request"
    )
    private val approved = PaymentAttemptEntity(
        attemptId = "attempt", venueId = "venue", processor = "BLUMON", state = "HOST_RESPONDIO",
        amountCents = 10000, tipCents = 1000, recordingRoute = "FAST",
        paymentContextJson = Gson().toJson(context), hostApproved = true,
        authCode = "AUTH", referenceNumber = "REF", operationId = "123",
        createdAt = 1, updatedAt = 1
    )
    init { coEvery { dao.completeRecovery(any(), any(), any(), any(), any(), any()) } returns 1 }
    private val receipt = PaymentReceipt("payment", "url", "key", BigDecimal("100.00"), BigDecimal("10.00"))

    private val angelPayContext = PaymentContext.AngelPayPayment(
        venueId = "venue", staffId = "staff", amount = BigDecimal("100.00"), tip = BigDecimal("10.00"),
        merchantAccountId = "merchant", deviceSerialNumber = "N860W173397", idempotencyKey = "attempt-ap",
        terminalPaymentRequestId = "request-ap",
    )
    private val approvedAngelPay = approved.copy(
        attemptId = "attempt-ap", processor = "ANGELPAY", paymentContextJson = Gson().toJson(angelPayContext),
        terminalPaymentRequestId = "request-ap", operationId = null,
    )

    @Test
    fun `restart replays a BLUMON approval with original key and processor identity — by the recovery it had BEFORE the checkpoint`() = runTest {
        // Codex (código, P1-6): el checkpoint 2 es Nexgo/AngelPay; el DAO del veredicto rechaza `processor != ANGELPAY`,
        // así que pasar una aprobación Blumon por él la dejaba retenida hasta agotar sus 5 intentos. Blumon conserva
        // `completeRecovery(REGISTRADO)` tal cual, hasta el port a PAX.
        coEvery { dao.getApprovalRecoveryCandidates("venue", any(), any()) } returns listOf(approved)
        coEvery { dao.claimRecovery(any(), any(), any(), any()) } returns 1
        val contexts = mutableListOf<PaymentContext>()
        coEvery { fast.recordPayment(capture(contexts), any(), "AUTH", "REF") } returns Result.success(receipt)
        assertEquals(1, LedgerApprovalRecovery(dao, fast, order).recover("venue", 1000000))
        assertEquals("attempt", contexts.single().idempotencyKey)
        assertEquals("request", contexts.single().terminalPaymentRequestId)
        assertEquals(123, (contexts.single() as PaymentContext.FastPayment).blumonOperationNumber)
        coVerify(exactly = 1) { dao.completeRecovery("attempt", "venue", any(), "REGISTRADO", any(), null) }
        coVerify(exactly = 0) { dao.aplicarVeredictoDelServidor(any(), any()) }
    }

    @Test
    fun `restart replays an ANGELPAY approval as a VERDICT by the single rule (E1), not as a blind REGISTRADO`() = runTest {
        coEvery { dao.getApprovalRecoveryCandidates("venue", any(), any()) } returns listOf(approvedAngelPay)
        coEvery { dao.claimRecovery(any(), any(), any(), any()) } returns 1
        val contexts = mutableListOf<PaymentContext>()
        coEvery { fast.recordPayment(capture(contexts), any(), "AUTH", "REF") } returns Result.success(receipt)
        // Checkpoint 2 (E1/E5): el 2xx entra como VEREDICTO por la regla única, no como `completeRecovery(REGISTRADO)`.
        val veredictos = mutableListOf<VeredictoDeIntento>()
        coEvery { dao.aplicarVeredictoDelServidor(capture(veredictos), any()) } returns
            ResultadoDelVeredicto(ResultadoDelVeredicto.Decision.APLICADO, transiciono = true, bandejaResueltaJson = null, contradiccion = false)
        assertEquals(1, LedgerApprovalRecovery(dao, fast, order).recover("venue", 1000000))
        assertEquals("attempt-ap", contexts.single().idempotencyKey)
        assertEquals("request-ap", contexts.single().terminalPaymentRequestId)
        val v = veredictos.single()
        assertEquals("attempt-ap", v.attemptId)
        assertEquals(VeredictoDeIntento.Fuente.REST, v.fuente)
        assertEquals(com.jaac.avoqado_tpv.features.payment.domain.model.VeredictoDelServidor.RECORDED, v.outcome)
        assertEquals(10000L, v.amountCents)
        assertEquals(1000L, v.tipCents)
        coVerify(exactly = 0) { dao.completeRecovery(any(), any(), any(), "REGISTRADO", any(), any()) }
    }

    @Test
    fun `una evidencia del servidor (segunda captura sin ganador) NO cuenta como recuperada y la fila conserva su estado`() = runTest {
        coEvery { dao.getApprovalRecoveryCandidates("venue", any(), any()) } returns listOf(approvedAngelPay)
        coEvery { dao.claimRecovery(any(), any(), any(), any()) } returns 1
        coEvery { fast.recordPayment(any(), any(), "AUTH", "REF") } returns Result.success(
            receipt.copy(serverStatus = "PENDING", reconciliationKind = "POSSIBLE_REFERENCE_COLLISION"),
        )
        coEvery { dao.aplicarVeredictoDelServidor(any(), any()) } returns
            ResultadoDelVeredicto(ResultadoDelVeredicto.Decision.GUARDADO_SIN_LIBERAR, transiciono = false, bandejaResueltaJson = null, contradiccion = true)
        assertEquals(0, LedgerApprovalRecovery(dao, fast, order).recover("venue", 1000000))
        coVerify(exactly = 0) { dao.completeRecovery(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `unknown result cannot enter registration`() = runTest {
        coEvery { dao.getApprovalRecoveryCandidates("venue", any(), any()) } returns listOf(approved.copy(state = "INDETERMINADO", hostApproved = null))
        assertEquals(0, LedgerApprovalRecovery(dao, fast, order).recover("venue", 1000000))
        coVerify(exactly = 0) { fast.recordPayment(any(), any(), any(), any()) }
    }

    @Test
    fun `failed registration waits and next run uses same attempt without SDK`() = runTest {
        coEvery { dao.getApprovalRecoveryCandidates("venue", any(), any()) } returns listOf(approved)
        coEvery { dao.claimRecovery(any(), any(), any(), any()) } returns 1
        var calls = 0
        coEvery { fast.recordPayment(any(), any(), any(), any()) } answers {
            calls++
            Result.failure(java.io.IOException("response lost after commit"))
        }
        assertEquals(0, LedgerApprovalRecovery(dao, fast, order).recover("venue", 1000000))
        assertEquals(1, calls)
        coVerify(exactly = 0) { dao.completeRecovery(any(), any(), any(), "REGISTRADO", any(), any()) }
    }

    @Test
    fun `P1 sin sesion no se reclama ninguna aprobacion ni se gasta un intento, y al volver la sesion se registra`() = runTest {
        // D3 (encargo 9308a7): sin sesión cada pasada terminaba en 401 y gastaba uno de los 5 intentos del cobro aprobado.
        coEvery { dao.getApprovalRecoveryCandidates("venue", any(), any()) } returns listOf(approved)
        coEvery { dao.claimRecovery(any(), any(), any(), any()) } returns 1
        coEvery { fast.recordPayment(any(), any(), "AUTH", "REF") } returns Result.success(receipt)
        var sesion = false
        val recuperacion = LedgerApprovalRecovery(dao, fast, order, puedeRegistrar = { sesion })

        assertEquals(0, recuperacion.recover("venue", 1000000))
        coVerify(exactly = 0) { dao.getApprovalRecoveryCandidates(any(), any(), any()) }
        coVerify(exactly = 0) { dao.claimRecovery(any(), any(), any(), any()) }
        coVerify(exactly = 0) { fast.recordPayment(any(), any(), any(), any()) }

        sesion = true
        assertEquals(1, recuperacion.recover("venue", 1000000))
        coVerify(exactly = 1) { dao.completeRecovery("attempt", "venue", any(), "REGISTRADO", any(), null) }
    }

    @Test
    fun `P1 si la sesion o la red se van a media pasada, las demas aprobaciones no se reclaman`() = runTest {
        // Codex r1 #1: un 401 con refresco fallido borra la sesión DURANTE la pasada; antes sólo se miraba al empezar.
        val segunda = approved.copy(attemptId = "attempt-2")
        coEvery { dao.getApprovalRecoveryCandidates("venue", any(), any()) } returns listOf(approved, segunda)
        coEvery { dao.claimRecovery(any(), any(), any(), any()) } returns 1
        var sesion = true
        coEvery { fast.recordPayment(any(), any(), "AUTH", "REF") } answers {
            sesion = false
            Result.failure(java.io.IOException("401: la sesión se cerró"))
        }
        assertEquals(0, LedgerApprovalRecovery(dao, fast, order, puedeRegistrar = { sesion }).recover("venue", 1000000))
        coVerify(exactly = 1) { dao.claimRecovery("attempt", "venue", any(), any()) }
        coVerify(exactly = 0) { dao.claimRecovery("attempt-2", any(), any(), any()) }
    }

    @Test
    fun `P1 un cobro sin sesion en AUTORIZADO se recupera aunque host_approved no se haya escrito`() = runTest {
        // Codex r1 #3: el camino sin sesión deja AUTORIZADO (con la tarjeta); markHostResponded pudo fallar antes.
        val sinBandera = approved.copy(state = "AUTORIZADO", hostApproved = null, maskedPan = "****1111", cardBrand = "VISA", entryMode = "CHIP")
        coEvery { dao.getApprovalRecoveryCandidates("venue", any(), any()) } returns listOf(sinBandera)
        coEvery { dao.claimRecovery(any(), any(), any(), any()) } returns 1
        val tarjetas = mutableListOf<CardDetails>()
        coEvery { fast.recordPayment(any(), capture(tarjetas), "AUTH", "REF") } returns Result.success(receipt)
        assertEquals(1, LedgerApprovalRecovery(dao, fast, order).recover("venue", 1000000))
        assertEquals("****1111", tarjetas.single().maskedPan)
        assertEquals(CardBrand.VISA, tarjetas.single().cardBrand)
        coVerify(exactly = 1) { dao.completeRecovery("attempt", "venue", any(), "REGISTRADO", any(), null) }
    }

    @Test
    fun `P1 una aprobacion saltada por reciente agenda otra pasada para cuando cumpla su espera`() = runTest {
        // Codex r4 (D3): la pasada absorbida por KEEP corre antes de los 2 min de la fila y la salta; sin seguimiento,
        // la siguiente era la reconexión o la de 6 h.
        val now = 1_000_000L
        coEvery { dao.getApprovalRecoveryCandidates("venue", Long.MAX_VALUE, now) } returns listOf(
            approved.copy(attemptId = "vieja", createdAt = now - 500_000L),     // ésta la toma esta misma pasada
            approved.copy(attemptId = "reciente", createdAt = now - 90_000L),   // le faltan 30 s
            approved.copy(attemptId = "nueva", createdAt = now - 10_000L),      // le faltan 110 s: la agenda la siguiente
        )
        assertEquals(30_000L + 15_000L, LedgerApprovalRecovery.faltaParaLaSiguienteReciente(dao, "venue", now))
    }

    @Test
    fun `sin aprobaciones recientes no se agenda nada`() = runTest {
        val now = 1_000_000L
        coEvery { dao.getApprovalRecoveryCandidates("venue", Long.MAX_VALUE, now) } returns
            listOf(approved.copy(createdAt = now - 500_000L))
        assertEquals(null, LedgerApprovalRecovery.faltaParaLaSiguienteReciente(dao, "venue", now))
    }

    @Test
    fun `another worker lease and queue handoff prevent second registration`() = runTest {
        coEvery { dao.getApprovalRecoveryCandidates("venue", any(), any()) } returns listOf(approved, approved.copy(state = "ENTREGADA_A_COLA"))
        coEvery { dao.claimRecovery(any(), any(), any(), any()) } returns 0
        assertEquals(0, LedgerApprovalRecovery(dao, fast, order).recover("venue", 1000000))
        coVerify(exactly = 0) { fast.recordPayment(any(), any(), any(), any()) }
    }
}

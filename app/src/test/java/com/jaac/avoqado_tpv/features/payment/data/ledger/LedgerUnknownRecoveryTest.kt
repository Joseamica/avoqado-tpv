package com.jaac.avoqado_tpv.features.payment.data.ledger

import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LedgerUnknownRecoveryTest {
    private val dao = mockk<PaymentAttemptDao>(relaxed = true)
    private val ledger = mockk<PaymentAttemptLedger>(relaxed = true)
    init { coEvery { ledger.aprobadoFinalAngelPay(any(), any(), any(), any(), any()) } returns AprobadoFinal.REGISTRABLE }
    private val verifier = mockk<AngelPayChargeVerifier>()
    private val row = PaymentAttemptEntity("attempt", "venue", "ANGELPAY", state = "INDETERMINADO",
        amountCents = 10000, tipCents = 1000, recordingRoute = "ORDER",
        paymentContextJson = """{"venueId":"venue","staffId":"staff","orderId":"order","idempotencyKey":"attempt","deviceSerialNumber":"terminal","processorAffiliation":"affiliation","amount":100,"tip":10,"merchantAccountId":"merchant"}""",
        createdAt = 1000, updatedAt = 1000)

    @Test fun `restart queries saved identity once and persists exact positive approval`() = runTest {
        coEvery { dao.getUnknownRecoveryCandidates("venue", any(), any(), any(), any(), any()) } returns listOf(row)
        coEvery { dao.claimUnknownRecovery(any(), any(), any(), any()) } returns 1
        coEvery { verifier.verificar("attempt", "terminal", any(), 1, 0, "affiliation") } returns
            VerificacionDelCobro.Cobrado("AUTH", "REF", null)
        assertEquals(1, LedgerUnknownRecovery(dao, ledger, verifier).recover("venue", 1000000))
        coVerify(exactly = 1) { ledger.aprobadoFinalAngelPay("attempt", "venue", "REF", "AUTH", OrigenDelAprobado.HISTORIAL_RECUPERACION) }
    }

    @Test fun `missing history cannot clear durable uncertainty`() = runTest {
        coEvery { dao.getUnknownRecoveryCandidates("venue", any(), any(), any(), any(), any()) } returns listOf(row)
        coEvery { dao.claimUnknownRecovery(any(), any(), any(), any()) } returns 1
        coEvery { verifier.verificar(any(), any(), any(), any(), any(), any()) } returns
            VerificacionDelCobro.NoSePudoVerificar("empty history")
        assertEquals(0, LedgerUnknownRecovery(dao, ledger, verifier).recover("venue", 1000000))
        coVerify(exactly = 0) { ledger.aprobadoFinalAngelPay(any(), any(), any(), any(), any()) }
        // F1 bloque 4: un fallo técnico pausa SIN gastar turno.
        coVerify(exactly = 1) { dao.pausarConsultaSinGastarTurno("attempt", "venue", any(), any()) }
        coVerify(exactly = 0) { dao.terminarConsultaSinCoincidencia(any(), any(), any(), any()) }
    }

    @Test fun `Blumon and wrong venue never enter AngelPay recovery`() = runTest {
        coEvery { dao.getUnknownRecoveryCandidates("venue", any(), any(), any(), any(), any()) } returns listOf(row.copy(processor = "BLUMON"), row.copy(venueId = "other"))
        assertEquals(0, LedgerUnknownRecovery(dao, ledger, verifier).recover("venue", 1000000))
        coVerify(exactly = 0) { verifier.verificar(any(), any(), any(), any(), any(), any()) }
    }
}

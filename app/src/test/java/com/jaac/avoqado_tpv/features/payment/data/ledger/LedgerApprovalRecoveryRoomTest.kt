package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.features.payment.data.repository.FastPaymentRecorder
import com.jaac.avoqado_tpv.features.payment.data.repository.OrderPaymentRecorder
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

/**
 * Codex (código del checkpoint 2, P1-6, ronda 2): la recuperación por aprobación sobre Room REAL — sin simular
 * `completeRecovery` ni el DAO del veredicto — para demostrar que una aprobación Blumon con REST exitoso llega a
 * REGISTRADO por el camino de ANTES del checkpoint, y que una AngelPay llega por el veredicto (E1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class LedgerApprovalRecoveryRoomTest {
    private lateinit var db: AvoqadoDatabase
    private lateinit var dao: PaymentAttemptDao
    private val fast = mockk<FastPaymentRecorder>()
    private val order = mockk<OrderPaymentRecorder>()
    private val now = 1_700_000_000_000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.paymentAttemptDao()
    }

    @After fun tearDown() { db.close() }

    private fun blumon(attemptId: String) = PaymentAttemptEntity(
        attemptId = attemptId, venueId = "venue", processor = "BLUMON", state = "HOST_RESPONDIO",
        amountCents = 10000, tipCents = 1000, recordingRoute = "FAST",
        paymentContextJson = Gson().toJson(PaymentContext.FastPayment(
            venueId = "venue", staffId = "staff", amount = BigDecimal("100.00"), tip = BigDecimal("10.00"),
            merchantAccountId = "merchant", deviceSerialNumber = "terminal", idempotencyKey = attemptId)),
        hostApproved = true, authCode = "AUTH", referenceNumber = "REF", operationId = "123",
        createdAt = now - 900_000, updatedAt = now - 900_000,
    )

    private fun angelPay(attemptId: String) = PaymentAttemptEntity(
        attemptId = attemptId, venueId = "venue", processor = "ANGELPAY", state = "HOST_RESPONDIO",
        amountCents = 10000, tipCents = 1000, recordingRoute = "FAST",
        paymentContextJson = Gson().toJson(PaymentContext.AngelPayPayment(
            venueId = "venue", staffId = "staff", amount = BigDecimal("100.00"), tip = BigDecimal("10.00"),
            merchantAccountId = "merchant", deviceSerialNumber = "N860W173397", idempotencyKey = attemptId,
            terminalPaymentRequestId = "req-$attemptId")),
        hostApproved = true, authCode = "AUTH", referenceNumber = "REF",
        createdAt = now - 900_000, updatedAt = now - 900_000, terminalPaymentRequestId = "req-$attemptId",
    )

    private val receipt = PaymentReceipt("payment", "url", "key", BigDecimal("100.00"), BigDecimal("10.00"))

    @Test fun `P1-6 una aprobacion BLUMON con REST exitoso llega a REGISTRADO por la recuperacion de ANTES del checkpoint (Room real)`() = runTest {
        dao.insert(blumon("b1"))
        coEvery { fast.recordPayment(any(), any(), "AUTH", "REF") } returns Result.success(receipt)
        assertThat(LedgerApprovalRecovery(dao, fast, order).recover("venue", now)).isEqualTo(1)
        val fila = dao.getById("b1")!!
        assertThat(fila.state).isEqualTo("REGISTRADO")
        assertThat(fila.serverOutcome).isNull() // el veredicto del checkpoint no toca a Blumon (port posterior)
        // Segunda pasada: ya no es candidata; el REST no se repite.
        assertThat(LedgerApprovalRecovery(dao, fast, order).recover("venue", now + 1)).isEqualTo(0)
        coVerify(exactly = 1) { fast.recordPayment(any(), any(), "AUTH", "REF") }
    }

    @Test fun `una aprobacion ANGELPAY con REST exitoso llega a REGISTRADO por el veredicto (E1) y deja la evidencia del servidor (Room real)`() = runTest {
        dao.insert(angelPay("a1"))
        coEvery { fast.recordPayment(any(), any(), "AUTH", "REF") } returns Result.success(receipt.copy(solicitudLigada = "req-a1"))
        assertThat(LedgerApprovalRecovery(dao, fast, order).recover("venue", now)).isEqualTo(1)
        val fila = dao.getById("a1")!!
        assertThat(fila.state).isEqualTo("REGISTRADO")
        assertThat(fila.serverOutcome).isEqualTo("RECORDED")
        assertThat(fila.serverPaymentId).isEqualTo("payment")
        assertThat(fila.serverWinnerPaymentId).isEqualTo("payment")
    }

    @Test fun `una aprobacion ANGELPAY cuyo 2xx trae importes distintos NO se libera — la evidencia queda como contradiccion (Room real)`() = runTest {
        dao.insert(angelPay("a2"))
        coEvery { fast.recordPayment(any(), any(), "AUTH", "REF") } returns Result.success(receipt.copy(amount = BigDecimal("90.00")))
        assertThat(LedgerApprovalRecovery(dao, fast, order).recover("venue", now)).isEqualTo(0)
        val fila = dao.getById("a2")!!
        assertThat(fila.state).isEqualTo("HOST_RESPONDIO")
        assertThat(fila.serverAmountCents).isEqualTo(9000L)
        assertThat(dao.esContradiccion("a2")).isTrue()
    }
}

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

    @Test fun `D3 un cobro sin sesion cuyo host_approved no se escribio sigue recuperable tras un primer registro fallido (Room real)`() = runTest {
        // Codex r2 (encargo 9308a7): la escritura de «el banco aprobó» falló (la fila sigue en AUTORIZANDO sin la marca), la
        // sesión se cerró y la rama sin sesión la deja AUTORIZADO. Si la recuperación falla una vez, la fila pasa a
        // REGISTRO_FALLIDO: sin `host_approved = 1` esa consulta ya no la volvía a tomar. La rama sin sesión repite la marca.
        dao.insert(blumon("s1").copy(state = "AUTORIZANDO", hostApproved = null, authCode = null, referenceNumber = null, operationId = null))
        val ledger = PaymentAttemptLedger(dao, mockk(relaxed = true))
        assertThat(ledger.markHostResponded("s1", approved = true, operationId = "123", referenceNumber = "REF", authCode = "AUTH")).isTrue()
        ledger.markAuthorized("s1", "****1111", "VISA", "CHIP")
        assertThat(dao.getById("s1")!!.state).isEqualTo("AUTORIZADO")
        assertThat(dao.getById("s1")!!.hostApproved).isTrue()

        coEvery { fast.recordPayment(any(), any(), "AUTH", "REF") } returns Result.failure(java.io.IOException("503"))
        assertThat(LedgerApprovalRecovery(dao, fast, order).recover("venue", now)).isEqualTo(0)
        assertThat(dao.getById("s1")!!.state).isEqualTo("REGISTRO_FALLIDO")

        // Vencido el lease, la siguiente pasada la vuelve a tomar y la registra con la misma llave.
        coEvery { fast.recordPayment(any(), any(), "AUTH", "REF") } returns Result.success(receipt)
        val despues = System.currentTimeMillis() + 3_600_000L
        assertThat(LedgerApprovalRecovery(dao, fast, order).recover("venue", despues)).isEqualTo(1)
        assertThat(dao.getById("s1")!!.state).isEqualTo("REGISTRADO")
        coVerify(exactly = 2) { fast.recordPayment(any(), any(), "AUTH", "REF") }
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

    @Test fun `B2 recovery rechaza snapshot que contradice columnas sin perder owner ni evidencia`() = runTest {
        val original = blumon("contradictory")
        val snapshot = Gson().fromJson(original.paymentContextJson, PaymentContext.FastPayment::class.java).copy(shiftMembership = "NO_SHIFT_SYSTEM")
        dao.insert(original.copy(shiftMembership = "MANAGED", localShiftSessionId = "cabcdefghijklmnopqrstuvwx", paymentContextJson = Gson().toJson(snapshot)))
        coEvery { fast.recordPayment(any(), any(), any(), any()) } returns Result.success(receipt)
        assertThat(LedgerApprovalRecovery(dao, fast, order).recover("venue", now)).isEqualTo(0)
        val row = dao.getById("contradictory")!!
        assertThat(row.state).isEqualTo("HOST_RESPONDIO")
        assertThat(row.hostApproved).isTrue()
        assertThat(row.shiftMembership).isEqualTo("MANAGED")
        assertThat(row.lastError).isEqualTo("snapshot_caja_ilegible_o_contradictorio")
        coVerify(exactly = 0) { fast.recordPayment(any(), any(), any(), any()) }
    }

    /**
     * 🔴 Codex final r6 #4 (F1, 20-nov) · POS → N86: la ventana soltó la solicitud, después la recuperación encontró el aprobado de AngelPay
     * (referencia y autorización guardadas) y el único aviso de AngelPay se perdió: sólo la TPV puede registrarlo. Con el SERVIDOR caído, el
     * quinto fallo la sacaba de toda pasada (`verify_attempts < 5`): cargo aprobado con cero Payment. Un aprobado con evidencia nunca se
     * abandona: seis fallos seguidos y, de vuelta el SERVIDOR, el séptimo lo registra una sola vez.
     */
    @Test fun `F1 Codex r6 4 · un aprobado con evidencia nunca se abandona - seis fallos y al septimo se registra una vez`() = runTest {
        dao.insert(angelPay("a4").copy(state = "DESCARTADA", serverOutcome = "RELEASED_NO_EVIDENCE",
            lastError = "liberada_por_el_servidor:NO_EVIDENCE_AFTER_WINDOW"))
        var caido = true
        val registrados = mutableListOf<String>()
        coEvery { fast.recordPayment(any(), any(), "AUTH", "REF") } coAnswers {
            if (caido) Result.failure(java.io.IOException("503"))
            else { registrados += firstArg<PaymentContext>().idempotencyKey!!; Result.success(receipt.copy(solicitudLigada = "req-a4")) }
        }
        var t = System.currentTimeMillis()
        repeat(6) { t += 3_600_000L; LedgerApprovalRecovery(dao, fast, order).recover("venue", t) }
        assertThat(dao.getById("a4")!!.state).isEqualTo("REGISTRO_FALLIDO")
        assertThat(dao.getById("a4")!!.verifyAttempts).isEqualTo(6)

        caido = false
        t += 3_600_000L
        assertThat(LedgerApprovalRecovery(dao, fast, order).recover("venue", t)).isEqualTo(1)
        LedgerApprovalRecovery(dao, fast, order).recover("venue", t + 3_600_000L)
        assertThat(registrados).containsExactly("a4")
        assertThat(dao.getById("a4")!!.state).isEqualTo("REGISTRADO")
    }

    /**
     * 🔴 Codex final r7 #2: sin tope de intentos y eligiendo los 50 más viejos, 300 aprobados viejos cuyo registro falla siempre dejaban sin
     * turno a un aprobado NUEVO (B), con su evidencia completa: cero Payment aunque el SERVIDOR ya pudiera registrarlo. Reescrita por Codex
     * final r8 #6 (el orden va por el ÚLTIMO TOQUE, no por «menos intentos»): cada viejo que se intenta queda detrás de B, así que B entra a
     * más tardar en la pasada ⌈300 / 50⌉ + 1 = 7 (el peor caso, con las pausas vencidas en cada pasada, está en `CodexR8RecuperacionRoomTest`).
     */
    @Test fun `F1 Codex r7 2 · 300 aprobados viejos que fallan no dejan sin turno a uno nuevo`() = runTest {
        repeat(300) { i ->
            dao.insert(angelPay("viejo-%03d".format(i)).copy(state = "REGISTRO_FALLIDO", verifyAttempts = 7,
                createdAt = now - 86_400_000L - i, updatedAt = now - 86_400_000L))
        }
        dao.insert(angelPay("b-nuevo"))
        val registrados = mutableListOf<String>()
        coEvery { fast.recordPayment(any(), any(), "AUTH", "REF") } coAnswers {
            val llave = firstArg<PaymentContext>().idempotencyKey!!
            if (llave != "b-nuevo") Result.failure(java.io.IOException("503"))
            else { registrados += llave; Result.success(receipt.copy(solicitudLigada = "req-b-nuevo")) }
        }
        var pasadas = 0
        while (registrados.isEmpty() && pasadas < 20) { pasadas++; LedgerApprovalRecovery(dao, fast, order).recover("venue", now) }
        assertThat(registrados).containsExactly("b-nuevo")
        assertThat(pasadas).isAtMost(7)
        assertThat(dao.getById("b-nuevo")!!.state).isEqualTo("REGISTRADO")
    }

    @Test fun `B2 recovery legacy no demuestra OFF por un campo JSON redundante`() = runTest {
        val original = blumon("legacy-tag")
        val snapshot = Gson().fromJson(original.paymentContextJson, PaymentContext.FastPayment::class.java).copy(shiftMembership = "NO_SHIFT_SYSTEM")
        dao.insert(original.copy(paymentContextJson = Gson().toJson(snapshot)))
        coEvery { fast.recordPayment(any(), any(), any(), any()) } coAnswers {
            assertThat(firstArg<PaymentContext>().shiftMembership).isEqualTo("UNKNOWN")
            Result.success(receipt)
        }
        assertThat(LedgerApprovalRecovery(dao, fast, order).recover("venue", now)).isEqualTo(1)
        assertThat(dao.getById("legacy-tag")?.shiftMembership).isEqualTo("UNKNOWN")
    }

}

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

    /**
     * 🔴 Cobro simple · D5 mínimo (rescate de F1 `7d12a7c8`): con `verify_attempts < 5`, cinco fallos del SERVIDOR al registrar un aprobado
     * de la PAX lo sacaban de toda pasada: cargo aprobado con cero Payment. Un aprobado nunca se abandona: seis fallos y al séptimo entra,
     * una sola vez y con la llave del intento.
     */
    @Test fun `D5 minimo · un aprobado nunca se abandona - seis fallos y al septimo se registra una vez`() = runTest {
        dao.insert(blumon("b7"))
        var caido = true
        val registrados = mutableListOf<String>()
        coEvery { fast.recordPayment(any(), any(), "AUTH", "REF") } coAnswers {
            if (caido) Result.failure(java.io.IOException("503"))
            else { registrados += firstArg<PaymentContext>().idempotencyKey!!; Result.success(receipt) }
        }
        var t = System.currentTimeMillis()
        repeat(6) { t += 3_600_000L; LedgerApprovalRecovery(dao, fast, order).recover("venue", t) }
        assertThat(dao.getById("b7")!!.state).isEqualTo("REGISTRO_FALLIDO")
        assertThat(dao.getById("b7")!!.verifyAttempts).isEqualTo(6)

        caido = false
        t += 3_600_000L
        assertThat(LedgerApprovalRecovery(dao, fast, order).recover("venue", t)).isEqualTo(1)
        LedgerApprovalRecovery(dao, fast, order).recover("venue", t + 3_600_000L)
        assertThat(registrados).containsExactly("b7")
        assertThat(dao.getById("b7")!!.state).isEqualTo("REGISTRADO")
    }

    /**
     * 🔴 Cobro simple · D5 mínimo (rescate de F1 `a1cca768`): sin tope, por antigüedad 60 aprobados viejos que fallan siempre ocupaban las 50
     * filas de cada pasada y un aprobado NUEVO no entraba nunca. Por último toque, cada fila intentada queda detrás de las que ya esperaban.
     */
    @Test fun `D5 minimo · la recuperacion va por el ultimo toque - los viejos que fallan no tapan a uno nuevo`() = runTest {
        repeat(60) { i -> dao.insert(blumon("viejo-$i").copy(createdAt = now - 2_000_000L + i, updatedAt = now - 2_000_000L + i)) }
        dao.insert(blumon("nuevo").copy(createdAt = now - 900_000L, updatedAt = now - 900_000L))
        val registrados = mutableListOf<String>()
        coEvery { fast.recordPayment(any(), any(), "AUTH", "REF") } coAnswers {
            val llave = firstArg<PaymentContext>().idempotencyKey!!
            if (llave != "nuevo") Result.failure(java.io.IOException("503")) else { registrados += llave; Result.success(receipt) }
        }
        var t = System.currentTimeMillis()
        var pasadas = 0
        while (registrados.isEmpty() && pasadas < 10) { pasadas++; t += 3_600_000L; LedgerApprovalRecovery(dao, fast, order).recover("venue", t) }
        assertThat(registrados).containsExactly("nuevo")
        assertThat(pasadas).isAtMost(2)
    }

    /**
     * 🔴 Cobro simple · D5 mínimo (Codex r1 #9 del plan T2): tras un registro fallido la fila queda reservada hasta su pausa, y la consulta
     * de «¿hace falta otra pasada?» no la veía: si era la única, nadie volvía hasta la reconexión o las 6 h. Ahora la pasada de seguimiento se
     * agenda para cuando vence su pausa.
     */
    @Test fun `D5 minimo · tras un registro fallido la siguiente vuelta queda agendada para cuando vence su pausa`() = runTest {
        dao.insert(blumon("r1"))
        coEvery { fast.recordPayment(any(), any(), "AUTH", "REF") } returns Result.failure(java.io.IOException("503"))
        val t = System.currentTimeMillis()
        val recuperacion = LedgerApprovalRecovery(dao, fast, order)
        recuperacion.recover("venue", t)
        val pausa = dao.getById("r1")!!.leaseUntil!!
        assertThat(pausa).isGreaterThan(t)
        // La pasada corta no la cuenta (está reservada); el fin de su pausa sí.
        assertThat(recuperacion.cuandoHaceFaltaOtraPasada("venue", t)).isNull()
        assertThat(recuperacion.cuandoVenceLaPausa("venue", t)).isEqualTo(pausa - t + LedgerApprovalRecovery.MARGEN_MS)
        assertThat(LedgerApprovalRecovery(dao, fast, order, puedeRegistrar = { false }).cuandoVenceLaPausa("venue", t)).isNull()
        // Ya registrada, no queda nada que agendar.
        coEvery { fast.recordPayment(any(), any(), "AUTH", "REF") } returns Result.success(receipt)
        recuperacion.recover("venue", pausa + 1)
        assertThat(dao.getById("r1")!!.state).isEqualTo("REGISTRADO")
        assertThat(recuperacion.cuandoVenceLaPausa("venue", pausa + 1)).isNull()
    }

    /** 🔴 Codex d5-minimo r1 #5: quitar el tope es sólo de la PAX. Una venta de la Nexgo con sus 5 intentos sigue como hoy (sin POST). */
    @Test fun `D5 minimo · la Nexgo conserva su tope de cinco intentos`() = runTest {
        dao.insert(angelPay("n5").copy(state = "REGISTRO_FALLIDO", verifyAttempts = 5))
        coEvery { fast.recordPayment(any(), any(), any(), any()) } returns Result.success(receipt.copy(solicitudLigada = "req-n5"))
        val t = System.currentTimeMillis() + 3_600_000L
        assertThat(LedgerApprovalRecovery(dao, fast, order).recover("venue", t)).isEqualTo(0)
        coVerify(exactly = 0) { fast.recordPayment(any(), any(), any(), any()) }
        assertThat(LedgerApprovalRecovery(dao, fast, order).cuandoVenceLaPausa("venue", t)).isNull()
    }

}

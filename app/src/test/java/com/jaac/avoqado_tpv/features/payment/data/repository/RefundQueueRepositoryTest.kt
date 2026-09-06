package com.jaac.avoqado_tpv.features.payment.data.repository

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.dao.PendingRefundDao
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.QueuedRefund
import com.jaac.avoqado_tpv.features.payment.domain.model.RefundReason
import com.jaac.avoqado_tpv.features.payment.domain.model.toEntity
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import com.jaac.avoqado_tpv.features.payment.domain.sync.SyncOutcome
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal

/**
 * El repositorio de la cola de REEMBOLSOS.
 *
 * Lo que se fija aquí no es «que llame al recorder», sino las tres cosas sin las cuales la cola
 * haría MÁS daño que el defecto que arregla:
 *
 * 1. **El reintento manda la MISMA llave, el MISMO monto y la MISMA autorización/referencia.** El
 *    servidor deriva su huella de idempotencia de esos cinco datos; recalcular cualquiera haría
 *    que el reintento naciera como un reembolso NUEVO y duplicaría el dinero devuelto.
 * 2. **Un 401 se reintenta, un 422 no.** 401/403 son la sesión, no la operación: tratarlos como
 *    definitivos tiraría dinero que sí se podía registrar.
 * 3. **Encolar sobrevive a que la pantalla se muera** — que es exactamente cuando ocurre, porque
 *    el cajero se va justo después de leer «devolución aprobada».
 */
class RefundQueueRepositoryTest {

    private lateinit var dao: PendingRefundDao
    private lateinit var recorder: RefundRecorder
    private lateinit var repo: RefundQueueRepositoryImpl

    @Before fun setup() {
        dao = mockk(relaxed = true)
        recorder = mockk(relaxed = true)
        repo = RefundQueueRepositoryImpl(dao, recorder)
    }

    private fun fila(
        key: String = "k-1",
        amount: String = "50.00",
        isPartial: Boolean = true,
        processor: ProcessorType = ProcessorType.BLUMON,
        merchantAccountId: String = "m1",
    ) = QueuedRefund(
        idempotencyKey = key,
        venueId = "v1",
        staffId = "s1",
        processor = processor,
        originalPaymentId = "pay-orig",
        originalOrderId = null,
        amount = BigDecimal(amount),
        originalTotalAmount = BigDecimal("100.00"),
        tipRefundCents = null,
        isPartialRefund = isPartial,
        refundReason = RefundReason.CUSTOMER_REQUEST,
        merchantAccountId = merchantAccountId,
        blumonSerialNumber = "SER1",
        originalOperationNumber = 75656,
        authorizationNumber = "502511",
        referenceNumber = "000000188231",
        maskedPan = "411111******1111",
        cardBrand = "VISA",
        entryMode = "CHIP",
        createdAt = 1_000L,
    )

    private fun fallo(e: Throwable) {
        coEvery { recorder.recordRefund(any(), any(), any(), any(), any(), any()) } returns Result.failure(e)
    }

    // ─── Los tres desenlaces ────────────────────────────────────────────────────

    @Test
    fun un_500_se_reintenta() = runTest {
        fallo(BackendHttpException(500, "boom"))
        assertThat(repo.replay(fila())).isEqualTo(SyncOutcome.Retryable)
    }

    @Test
    fun P1_un_401_se_reintenta_porque_es_la_sesion_no_el_reembolso() = runTest {
        // Tratar un 401 como definitivo tiraría un reembolso que sí se podía registrar: casi
        // siempre es el token atorado, no la operación.
        fallo(BackendHttpException(401, "token"))
        assertThat(repo.replay(fila())).isEqualTo(SyncOutcome.Retryable)
    }

    @Test
    fun P1_un_422_es_permanente() = runTest {
        fallo(BackendHttpException(422, "regla de negocio"))
        assertThat(repo.replay(fila())).isInstanceOf(SyncOutcome.Permanent::class.java)
    }

    @Test
    fun sin_red_se_reintenta() = runTest {
        fallo(IOException("sin red"))
        assertThat(repo.replay(fila())).isEqualTo(SyncOutcome.Retryable)
    }

    @Test
    fun una_excepcion_lanzada_no_tumba_el_worker() = runTest {
        // Si `recordRefund` LANZA en vez de devolver failure, el worker no puede morirse: dejaría
        // el resto de la cola sin reproducir.
        coEvery { recorder.recordRefund(any(), any(), any(), any(), any(), any()) } throws IllegalStateException("boom")
        assertThat(repo.replay(fila())).isEqualTo(SyncOutcome.Retryable)
    }

    // ─── Lo que el reintento MANDA ──────────────────────────────────────────────

    @Test
    fun P1_el_replay_manda_la_MISMA_llave_monto_y_datos_del_SDK() = runTest {
        val contexto = slot<PaymentContext.RefundPayment>()
        val auth = slot<String>()
        val ref = slot<String>()
        coEvery {
            recorder.recordRefund(capture(contexto), any(), capture(auth), capture(ref), any(), any())
        } returns Result.success(mockk(relaxed = true))

        repo.replay(fila(key = "k-9", amount = "50.00", isPartial = true))

        assertThat(contexto.captured.idempotencyKey).isEqualTo("k-9")
        assertThat(contexto.captured.amount).isEqualTo(BigDecimal("50.00"))
        assertThat(contexto.captured.isPartialRefund).isTrue()
        assertThat(contexto.captured.originalOperationNumber).isEqualTo(75656)
        // 🔴 Del SDK, tal como se guardaron: el servidor los mete en su huella de idempotencia.
        assertThat(auth.captured).isEqualTo("502511")
        assertThat(ref.captured).isEqualTo("000000188231")
    }

    @Test
    fun el_procesador_viaja_solo_cuando_es_angelpay() = runTest {
        val tag = slot<String?>()
        coEvery {
            recorder.recordRefund(any(), any(), any(), any(), any(), captureNullable(tag))
        } returns Result.success(mockk(relaxed = true))

        repo.replay(fila(processor = ProcessorType.ANGELPAY))
        assertThat(tag.captured).isEqualTo("angelpay")

        repo.replay(fila(processor = ProcessorType.BLUMON))
        assertThat(tag.captured).isNull() // null = el backend usa 'blumon', su default histórico
    }

    // ─── Encolar ────────────────────────────────────────────────────────────────

    @Test
    fun encolar_persiste_la_fila() = runTest {
        coEvery { dao.insertIgnore(any()) } returns 1L

        val r = repo.enqueue(fila(key = "k-1"))

        assertThat(r.isSuccess).isTrue()
        coVerify(exactly = 1) { dao.insertIgnore(match { it.idempotencyKey == "k-1" }) }
    }

    @Test
    fun P1_encolar_guarda_el_monto_como_texto_nunca_como_decimal_de_maquina() = runTest {
        coEvery { dao.insertIgnore(any()) } returns 1L

        repo.enqueue(fila(amount = "50.00"))

        coVerify { dao.insertIgnore(match { it.amount == "50.00" }) }
    }

    @Test
    fun encolar_no_revienta_si_Room_falla() = runTest {
        // Es lo peor que puede pasar aquí (el reembolso se pierde), pero tumbar la app encima no
        // ayuda a nadie: se devuelve el fallo y queda el grito en el log.
        coEvery { dao.insertIgnore(any()) } throws IllegalStateException("disco lleno")

        val r = repo.enqueue(fila())

        assertThat(r.isFailure).isTrue()
    }

    // ─── Write-ahead y candado (auditoría de Codex, 4-sep) ─────────────────────

    @Test
    fun P1_el_write_ahead_nace_SYNCING_reclamado_por_el_intento_y_con_claimed_at() = runTest {
        coEvery { dao.insertIgnore(any()) } returns 1L

        repo.enqueueClaimed(fila(key = "k-wa"), token = "tok-vm")

        coVerify {
            dao.insertIgnore(match {
                it.idempotencyKey == "k-wa" && it.syncStatus == "SYNCING" && it.claimToken == "tok-vm" && it.claimedAt != null
            })
        }
    }

    @Test
    fun unresolvedForPayment_pregunta_al_DAO_por_el_pago_original() = runTest {
        coEvery { dao.unresolvedForPayment("pay-orig") } returns listOf(fila(key = "k-1").toEntity())

        val r = repo.unresolvedForPayment("pay-orig")

        assertThat(r.map { it.idempotencyKey }).containsExactly("k-1")
    }

    @Test
    fun acknowledge_guarda_quien_y_cuando() = runTest {
        repo.acknowledge("k-1", staffId = "staff-9")

        coVerify { dao.acknowledge("k-1", by = "staff-9", at = more(0L)) }
    }

    // ─── Sin merchantAccountId: definitivo, no bucle (revisión 5-sep-2026) ─────

    @Test
    fun P1_una_fila_sin_merchantAccountId_es_PERMANENTE_y_ni_toca_la_red() = runTest {
        // 🔴 `RefundRecorder` la rechaza con IllegalArgumentException ANTES de la red, y
        // `classifySyncFailure` clasifica eso `Retryable`: la fila volvía a PENDING en CADA pasada
        // del worker, para siempre, bloqueando el cierre del turno — y sin poder reconocerla, porque
        // `acknowledgeRefund` sólo acepta las permanentes. Ningún reintento va a inventar la cuenta.
        val outcome = repo.replay(fila(key = "k-sin-merchant", merchantAccountId = ""))

        assertThat(outcome).isInstanceOf(SyncOutcome.Permanent::class.java)
        assertThat((outcome as SyncOutcome.Permanent).reason).contains("cuenta de comerciante")
        coVerify(exactly = 0) { recorder.recordRefund(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun P1_con_merchantAccountId_el_replay_sigue_yendo_a_la_red_como_siempre() = runTest {
        // Control: la guarda de arriba no puede cortar filas sanas.
        coEvery { recorder.recordRefund(any(), any(), any(), any(), any(), any()) } returns
            Result.failure(BackendHttpException(500, "boom"))

        val outcome = repo.replay(fila(key = "k-con-merchant"))

        assertThat(outcome).isEqualTo(SyncOutcome.Retryable)
        coVerify(exactly = 1) { recorder.recordRefund(any(), any(), any(), any(), any(), any()) }
    }
}

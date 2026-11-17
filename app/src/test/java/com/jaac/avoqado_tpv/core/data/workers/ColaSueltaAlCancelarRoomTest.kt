package com.jaac.avoqado_tpv.core.data.workers

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity
import com.jaac.avoqado_tpv.core.data.local.entity.PendingRefundEntity
import com.jaac.avoqado_tpv.core.util.PaymentQueueStateManager
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger
import com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto
import com.jaac.avoqado_tpv.features.payment.data.repository.PaymentQueueRepositoryImpl
import com.jaac.avoqado_tpv.features.payment.data.repository.RefundQueueRepositoryImpl
import com.jaac.avoqado_tpv.features.payment.data.repository.RefundRecorder
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt
import com.jaac.avoqado_tpv.features.payment.domain.model.QueuedRefund
import com.jaac.avoqado_tpv.features.payment.domain.model.RefundReason
import com.jaac.avoqado_tpv.features.payment.domain.model.toEntity
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import com.jaac.avoqado_tpv.features.payment.domain.repository.PaymentQueueRepository
import com.jaac.avoqado_tpv.features.payment.domain.repository.RefundQueueRepository
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordPaymentUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * D15 (N86 con SIM, 16-nov): JobScheduler detuvo el worker a media subida porque la red parpadeó al volver WiFi y 4G, y la
 * fila se quedó SYNCING/reclamada hasta que venció `STALE_CLAIM_MS` — el cobro aprobado subió ~16 min tarde. Los cobros los
 * suelta el D7 (#43); aquí se prueba el caso más peligroso (la petición YA llegó al servidor) y los reembolsos (D15).
 *
 * Aquí la cancelación es REAL (`Job.cancel()`, como la de WorkManager), no una `CancellationException` lanzada por un
 * mock: así se prueba que soltar la fila sobrevive a un trabajo ya cancelado. El servidor de mentiras sigue la regla del
 * real (`recordFastPayment` / `recordRefund`: busca por llave y, si dos llegan juntas, el índice único da al ganador):
 * una llave = un registro.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class ColaSueltaAlCancelarRoomTest {
    private lateinit var context: Context
    private lateinit var db: AvoqadoDatabase

    /** Una llave = un registro, y cuenta cuántas veces llegó cada llave. */
    private class ServidorIdempotente {
        val registros = ConcurrentHashMap<String, Int>()
        val llegadas = CopyOnWriteArrayList<String>()
        fun recibir(llave: String) { llegadas += llave; registros.putIfAbsent(llave, 1) }
    }

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, AvoqadoDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    @Test
    fun `P1 detenido con el POST ya en el servidor, el cobro vuelve a la cola y la siguiente pasada lo sube UNA vez`() = runBlocking<Unit> {
        val dao = db.pendingPaymentDao()
        val pagos = PaymentQueueRepositoryImpl(dao)
        dao.insertRow(cobroEnCola())
        val servidor = ServidorIdempotente()
        val enVuelo = CompletableDeferred<Unit>()
        var primera = true
        val useCase = mockk<RecordPaymentUseCase>()
        coEvery { useCase(any(), any(), any(), any(), true) } coAnswers {
            servidor.recibir(checkNotNull(firstArg<PaymentContext>().idempotencyKey))
            if (primera) {
                // La petición ya llegó y el servidor creó el pago; la respuesta nunca se lee: Android detiene el trabajo.
                primera = false
                enVuelo.complete(Unit)
                awaitCancellation()
            }
            Result.success(recibo())
        }

        val pasada = launch(Dispatchers.Default) { worker(pagos, useCase).doWork() }
        withTimeout(10_000) { enVuelo.await() }
        pasada.cancelAndJoin()

        val trasDetenerse = checkNotNull(dao.findByReference(REF, VENUE))
        assertThat(trasDetenerse.syncStatus).isEqualTo(PendingPaymentEntity.SYNC_STATUS_PENDING)
        assertThat(trasDetenerse.claimToken).isNull()
        assertThat(trasDetenerse.claimedAt).isNull()
        // Detenerse no es un intento fallido del cobro: un parpadeo tras otro nunca debe llevarlo a FAILED.
        assertThat(trasDetenerse.retryCount).isEqualTo(0)
        // Codex r2: la pasada que WorkManager reprograma pudo arrancar antes de soltar; queda otra encadenada DESPUÉS.
        assertThat(pasadaEncadenada()).isTrue()

        // La siguiente pasada (WorkManager la vuelve a programar al regresar la red) la toma YA, sin esperar 15 min.
        assertThat(worker(pagos, useCase).doWork()).isEqualTo(ListenableWorker.Result.success())

        assertThat(checkNotNull(dao.findByReference(REF, VENUE)).syncStatus).isEqualTo(PendingPaymentEntity.SYNC_STATUS_SUCCESS)
        assertThat(servidor.llegadas).containsExactly(LLAVE, LLAVE)
        assertThat(servidor.registros).containsExactly(LLAVE, 1)
    }

    @Test
    fun `P1 detenido a media devolucion, el reembolso vuelve a la cola y la siguiente pasada lo registra UNA vez`() = runBlocking<Unit> {
        val refundDao = db.pendingRefundDao()
        val recorder = mockk<RefundRecorder>()
        val reembolsos = RefundQueueRepositoryImpl(refundDao, recorder, PaymentQueueStateManager())
        refundDao.insertIgnore(reembolsoEnCola().toEntity())
        val servidor = ServidorIdempotente()
        val enVuelo = CompletableDeferred<Unit>()
        var primera = true
        coEvery { recorder.recordRefund(any(), any(), any(), any(), any(), any()) } coAnswers {
            servidor.recibir(checkNotNull(firstArg<PaymentContext.RefundPayment>().idempotencyKey))
            if (primera) {
                primera = false
                enVuelo.complete(Unit)
                awaitCancellation()
            }
            Result.success(mockk(relaxed = true))
        }
        val pagos = PaymentQueueRepositoryImpl(db.pendingPaymentDao())

        val pasada = launch(Dispatchers.Default) { worker(pagos, mockk(), reembolsos).doWork() }
        withTimeout(10_000) { enVuelo.await() }
        pasada.cancelAndJoin()

        val trasDetenerse = checkNotNull(refundDao.existingRow(VENUE, LLAVE_REEMBOLSO))
        assertThat(trasDetenerse.syncStatus).isEqualTo(PendingRefundEntity.SYNC_STATUS_PENDING)
        assertThat(trasDetenerse.claimToken).isNull()
        assertThat(trasDetenerse.retryCount).isEqualTo(0)
        assertThat(pasadaEncadenada()).isTrue()

        assertThat(worker(pagos, mockk(), reembolsos).doWork()).isEqualTo(ListenableWorker.Result.success())

        assertThat(checkNotNull(refundDao.existingRow(VENUE, LLAVE_REEMBOLSO)).syncStatus).isEqualTo("SUCCESS")
        assertThat(servidor.llegadas).containsExactly(LLAVE_REEMBOLSO, LLAVE_REEMBOLSO)
        assertThat(servidor.registros).containsExactly(LLAVE_REEMBOLSO, 1)
    }

    // ─── Ayudantes ──────────────────────────────────────────────────────────────

    /** ¿Quedó pedida una pasada inmediata (la que recoge lo soltado) y sin correr todavía? */
    private fun pasadaEncadenada(): Boolean = androidx.work.WorkManager.getInstance(context)
        .getWorkInfosForUniqueWork(com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler.IMMEDIATE_WORK_NAME).get()
        .any { !it.state.isFinished }

    private fun worker(
        pagos: PaymentQueueRepository,
        useCase: RecordPaymentUseCase,
        reembolsos: RefundQueueRepository = RefundQueueRepositoryImpl(db.pendingRefundDao(), mockk(), PaymentQueueStateManager()),
    ): PaymentSyncWorker = TestListenableWorkerBuilder<PaymentSyncWorker>(context)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                PaymentSyncWorker(appContext, workerParameters, pagos, useCase, PaymentQueueStateManager(), reembolsos, libretaSinFila(),
                    mockk(), tableSyncCoordinator = com.jaac.avoqado_tpv.features.shift.data.emptyTableReplayFixture())
        })
        .build()

    /** La cola es anterior a la libreta (sin fila para la llave): el veredicto no cambia nada. */
    private fun libretaSinFila() = mockk<PaymentAttemptLedger>(relaxed = true).also {
        coEvery { it.aplicarVeredictoDelServidor(any()) } returns Result.success(
            ResultadoDelVeredicto(ResultadoDelVeredicto.Decision.SIN_FILA, false, null, false),
        )
    }

    private fun recibo() = PaymentReceipt(
        paymentId = "pmt-d15", receiptUrl = "", accessKey = "", amount = BigDecimal("10.00"), tipAmount = BigDecimal.ZERO,
        serverStatus = "COMPLETED",
    )

    private fun cobroEnCola(
        ref: String = REF,
        llave: String = LLAVE,
        estado: String = PendingPaymentEntity.SYNC_STATUS_PENDING,
        token: String? = null,
    ) = PendingPaymentEntity(
        referenceNumber = ref,
        venueId = VENUE,
        staffId = "staff-1",
        amount = "10.00",
        tip = "0.00",
        rating = null,
        merchantAccountId = "merchant-1",
        blumonSerialNumber = "N860W175781",
        maskedPan = "411111******1111",
        cardBrand = "VISA",
        entryMode = "CONTACTLESS",
        isInternational = false,
        authorizationNumber = "237886",
        idempotencyKey = llave,
        paymentProcessor = ProcessorType.BLUMON.name,
        createdAt = System.currentTimeMillis(),
        syncStatus = estado,
        claimToken = token,
        claimedAt = token?.let { System.currentTimeMillis() },
    )

    private fun reembolsoEnCola() = QueuedRefund(
        idempotencyKey = LLAVE_REEMBOLSO,
        venueId = VENUE,
        staffId = "staff-1",
        processor = ProcessorType.BLUMON,
        originalPaymentId = "pay-orig",
        originalOrderId = null,
        amount = BigDecimal("10.00"),
        originalTotalAmount = BigDecimal("10.00"),
        tipRefundCents = null,
        isPartialRefund = false,
        refundReason = RefundReason.CUSTOMER_REQUEST,
        merchantAccountId = "merchant-1",
        blumonSerialNumber = "SER1",
        originalOperationNumber = 75656,
        authorizationNumber = "502511",
        referenceNumber = "000000188231",
        maskedPan = "411111******1111",
        cardBrand = "VISA",
        entryMode = "CHIP",
        createdAt = 1_000L,
    )

    private companion object {
        const val VENUE = "venue-1"
        const val REF = "261116165121"
        const val LLAVE = "9604ca08-0000-4000-8000-000000000d15"
        const val LLAVE_REEMBOLSO = "refund-d15"
    }
}

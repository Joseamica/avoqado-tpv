package com.jaac.avoqado_tpv.core.data.workers

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.core.util.PaymentQueueStateManager
import com.jaac.avoqado_tpv.features.payment.data.repository.PaymentQueueRepositoryImpl
import com.jaac.avoqado_tpv.features.payment.domain.model.QueuedPayment
import com.jaac.avoqado_tpv.features.payment.domain.repository.PaymentQueueRepository
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordPaymentUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal
import kotlin.system.measureTimeMillis

/**
 * F-9: `PaymentSyncWorker.syncPayment()` debe hacer UN intento por pago por corrida,
 * nunca un loop con `delay()` interno — el retry lo posee WorkManager (periódico de
 * 15 min + constraint NetworkType.CONNECTED). Antes: 10 pagos x hasta 10 intentos x
 * hasta 30s de backoff ≈ 40 min contra el límite de ejecución de WorkManager (~10 min);
 * el worker moría a media tanda con `retry_count` ya inflado y pagos buenos acababan
 * en FAILED. Ver spec §4.2 F-9, PaymentSyncWorker.kt KDoc.
 *
 * El worker se construye DIRECTO (bypass de Hilt) vía [TestListenableWorkerBuilder] +
 * un [WorkerFactory] a medida que le pasa los dobles de prueba — no hay entry point
 * de Hilt en unit tests locales de este módulo.
 */
@OptIn(ExperimentalCoroutinesApi::class) // TestScope.currentTime (reloj virtual)
class PaymentSyncWorkerTest {

    // ------------------------------------------------------------------
    // Fallos que antes recorrían el loop (Result.failure Y excepción lanzada)
    // ------------------------------------------------------------------

    @Test
    fun `un fallo transitorio (Result-failure) hace UN intento, no diez`() = runTest {
        val repo = mockk<PaymentQueueRepository>(relaxed = true)
        val useCase = mockk<RecordPaymentUseCase>()
        val payment = queuedPayment(reference = "ref-1")
        coEvery { repo.claimBatch(any()) } returns listOf(payment)
        coEvery { useCase(any(), any(), any(), any()) } returns
            Result.failure(IOException("red caida"))

        val worker = buildWorker(repo, useCase)
        val virtualBefore = currentTime
        val wallClockMs = measureTimeMillis { worker.doWork() }

        // Un solo intento: el reintento lo hace WorkManager al volver la red.
        coVerify(exactly = 1) { useCase(any(), any(), any(), any()) }
        // 🔴 Fix round 1 (test gap 1): las DOS señales son complementarias, no sustitutas.
        // Reloj VIRTUAL — cubre un delay() programado en el TestScope (runTest lo salta en
        // tiempo real, así que measureTimeMillis solo NO lo detecta — verificado
        // empíricamente: un delay(3000) sin llamada extra pasaba con measureTimeMillis solo).
        assertThat(currentTime - virtualBefore).isEqualTo(0L)
        // Reloj de PARED — cubre el caso inverso: un delay() en un dispatcher real (p.ej.
        // withContext(Dispatchers.IO) { delay(30_000) }) escapa al scheduler virtual, deja
        // currentTime en 0, pero sí quema tiempo real. currentTime solo NO lo detecta.
        assertThat(wallClockMs).isLessThan(2_000)
        coVerify(exactly = 1) {
            repo.release(queueId = payment.queueId, token = any(), retryCount = 1, error = any())
        }
    }

    @Test
    fun `una excepcion inesperada (no Result-failure) TAMBIEN hace UN intento, nunca un loop`() = runTest {
        // El camino que el brief original dejaba fuera: recordPaymentUseCase() puede
        // LANZAR en vez de devolver Result.failure. Antes ese catch (e: Exception)
        // incrementaba currentAttempt y volvía a la cima del while — el mismo loop
        // que el otro camino, solo que entrando por otra puerta.
        val repo = mockk<PaymentQueueRepository>(relaxed = true)
        val useCase = mockk<RecordPaymentUseCase>()
        val payment = queuedPayment(reference = "ref-2")
        coEvery { repo.claimBatch(any()) } returns listOf(payment)
        coEvery { useCase(any(), any(), any(), any()) } throws IllegalStateException("boom inesperado")

        val worker = buildWorker(repo, useCase)
        val virtualBefore = currentTime
        val wallClockMs = measureTimeMillis { worker.doWork() }

        coVerify(exactly = 1) { useCase(any(), any(), any(), any()) }
        // Ver nota sobre currentTime + measureTimeMillis (complementarios) en el test anterior.
        assertThat(currentTime - virtualBefore).isEqualTo(0L)
        assertThat(wallClockMs).isLessThan(2_000)
        // classifySyncFailure(IllegalStateException) → Retryable (ante la duda, reintenta).
        coVerify(exactly = 1) {
            repo.release(queueId = payment.queueId, token = any(), retryCount = 1, error = any())
        }
    }

    @Test
    fun `el worker siempre devuelve success para no romper el periodico`() = runTest {
        val repo = mockk<PaymentQueueRepository>(relaxed = true)
        val useCase = mockk<RecordPaymentUseCase>()
        coEvery { repo.claimBatch(any()) } returns listOf(queuedPayment(reference = "ref-1"))
        coEvery { useCase(any(), any(), any(), any()) } returns
            Result.failure(IOException("red caida"))

        val result = buildWorker(repo, useCase).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    // ------------------------------------------------------------------
    // Regresión: los otros desenlaces de syncPayment() (Task 1/3) siguen intactos
    // ------------------------------------------------------------------

    @Test
    fun `un pago exitoso marca sincronizado, sin tocar release`() = runTest {
        val repo = mockk<PaymentQueueRepository>(relaxed = true)
        val useCase = mockk<RecordPaymentUseCase>()
        val payment = queuedPayment(reference = "ref-3")
        coEvery { repo.claimBatch(any()) } returns listOf(payment)
        coEvery { useCase(any(), any(), any(), any()) } returns
            Result.success(mockk(relaxed = true))

        buildWorker(repo, useCase).doWork()

        coVerify(exactly = 1) { repo.markSynced(payment.queueId, payment.claimToken.orEmpty()) }
        coVerify(exactly = 0) { repo.release(any(), any(), any(), any()) }
    }

    @Test
    fun `409 del backend se marca sincronizado (idempotencia), no reintentable`() = runTest {
        val repo = mockk<PaymentQueueRepository>(relaxed = true)
        val useCase = mockk<RecordPaymentUseCase>()
        val payment = queuedPayment(reference = "ref-4")
        coEvery { repo.claimBatch(any()) } returns listOf(payment)
        coEvery { useCase(any(), any(), any(), any()) } returns
            Result.failure(BackendHttpException(409, "Duplicate payment"))

        buildWorker(repo, useCase).doWork()

        coVerify(exactly = 1) { repo.markSynced(payment.queueId, payment.claimToken.orEmpty()) }
        coVerify(exactly = 0) { repo.release(any(), any(), any(), any()) }
    }

    @Test
    fun `un 4xx permanente marca FAILED permanente de una, no se reintenta`() = runTest {
        // F-10: el branch SyncOutcome.Permanent ya NO pasa por release() (que dejaba la
        // fila resucitable por resetAllFailed en cada reconexión) — va directo a
        // markPermanentlyFailed(), que la deja FAILED con permanent=true para siempre.
        val repo = mockk<PaymentQueueRepository>(relaxed = true)
        val useCase = mockk<RecordPaymentUseCase>()
        val payment = queuedPayment(reference = "ref-5")
        coEvery { repo.claimBatch(any()) } returns listOf(payment)
        coEvery { useCase(any(), any(), any(), any()) } returns
            Result.failure(BackendHttpException(404, "Order not found"))

        buildWorker(repo, useCase).doWork()

        coVerify(exactly = 1) {
            // Minor (fix round 1): pin the token like the success sibling above pins it
            // for markSynced() — `token = any()` would stay green even if a future edit
            // passed the WRONG token. Production's CAS would then silently miss (0 rows
            // affected, just a Timber.w), the row would stay SYNCING, get stale-reclaimed,
            // hit the same 4xx again, and repeat every 15 minutes — the exact loop F-9
            // removed, now silent instead of loud.
            repo.markPermanentlyFailed(
                queueId = payment.queueId,
                token = payment.claimToken.orEmpty(),
                error = any(),
            )
        }
        coVerify(exactly = 0) { repo.release(any(), any(), any(), any()) }
        coVerify(exactly = 0) { repo.markSynced(any(), any()) }
    }

    @Test
    fun `CancellationException se repropaga y no toca la fila (la reclama el barrido de stale-claim)`() = runTest {
        val repo = mockk<PaymentQueueRepository>(relaxed = true)
        val useCase = mockk<RecordPaymentUseCase>()
        coEvery { repo.claimBatch(any()) } returns listOf(queuedPayment(reference = "ref-6"))
        coEvery { useCase(any(), any(), any(), any()) } throws CancellationException("worker cancelado")

        val worker = buildWorker(repo, useCase)

        var propagated = false
        try {
            worker.doWork()
        } catch (e: CancellationException) {
            propagated = true
        }

        assertThat(propagated).isTrue()
        coVerify(exactly = 0) { repo.release(any(), any(), any(), any()) }
        coVerify(exactly = 0) { repo.markSynced(any(), any()) }
    }

    @Test
    fun `un release() que lanza en el pago del medio NO aborta el resto de la tanda ya reclamada`() = runTest {
        // Regresión para Important 1 (fix round 1): antes de este fix, release() era el
        // único write de PaymentQueueRepositoryImpl sin try/catch. Un throw ahí (disco
        // lleno, DB cerrada durante teardown) escapaba de releaseClaim -> syncPayment ->
        // el for (payment in batch) de doWork() -> el catch genérico -> Result.retry(),
        // abandonando el resto de una tanda YA reclamada (pagos ya cobrados) en SYNCING
        // hasta que venciera STALE_CLAIM_MS (15 min).
        //
        // 🔴 Usa el PaymentQueueRepositoryImpl REAL (no un mock de la interfaz) porque el
        // fix vive AHÍ, no en el worker: PaymentSyncWorker.releaseClaim() sigue sin try/catch
        // propio — confía en que la implementación jamás lanza (devuelve 0). Mockear
        // PaymentQueueRepository directamente para que lance saltaría el propio fix y
        // probaría una capa distinta (el worker nunca se blindó contra ESO, y no es lo que
        // cambió esta ronda). El DAO sí se mockea — es la frontera real donde un
        // SQLiteFullException ocurriría.
        val dao = mockk<PendingPaymentDao>(relaxed = true)
        val repo = PaymentQueueRepositoryImpl(dao)
        val useCase = mockk<RecordPaymentUseCase>()

        val entity1 = pendingEntity(reference = "ref-a", id = 101L)
        val entity2 = pendingEntity(reference = "ref-b", id = 102L)
        val entity3 = pendingEntity(reference = "ref-c", id = 103L)
        coEvery { dao.claimBatch(any(), any(), any(), any()) } returns listOf(entity1, entity2, entity3)
        coEvery { useCase(any(), any(), any(), any()) } returns Result.failure(IOException("red caida"))
        // Solo el release() del pago DEL MEDIO revienta a nivel DAO — entity1/entity3 usan
        // el comportamiento relajado normal (devuelven 0, no lanzan).
        coEvery {
            dao.release(entity2.id, token = any(), retryCount = any(), error = any())
        } throws RuntimeException("disco lleno")

        val worker = buildWorker(repo, useCase)
        val result = worker.doWork()

        // Los TRES pagos se intentaron: el throw de uno (atrapado dentro de
        // PaymentQueueRepositoryImpl.release(), ahora con try/catch) no detuvo el loop.
        coVerify(exactly = 3) { useCase(any(), any(), any(), any()) }
        coVerify(exactly = 1) { dao.release(entity1.id, token = any(), retryCount = any(), error = any()) }
        coVerify(exactly = 1) { dao.release(entity2.id, token = any(), retryCount = any(), error = any()) }
        coVerify(exactly = 1) { dao.release(entity3.id, token = any(), retryCount = any(), error = any()) }
        // El worker sigue sin explotar hacia afuera pese al throw interno.
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun `un release() que lanza CancellationException se repropaga y NO sigue con el resto de la tanda`() = runTest {
        // Regresión para Fix round 2: el catch (e: Exception) que Fix round 1 agregó a
        // release() TAMBIÉN atrapaba CancellationException — en Kotlin es un
        // RuntimeException/Exception — devolviendo 0 en vez de repropagar. Antes de round 1,
        // release() no tenía catch alguno, así que la cancelación llegaba intacta al
        // catch (e: CancellationException) { throw e } de doWork(). El invariante: un worker
        // cancelado DEBE dejar la fila reclamada para el barrido de stale-claim y NUNCA seguir
        // escribiendo/iterando sobre el resto de la tanda.
        //
        // Igual que el test anterior: PaymentQueueRepositoryImpl REAL sobre un DAO mockeado,
        // para ejercer el catch real (no el del worker, que no tiene uno propio aquí).
        val dao = mockk<PendingPaymentDao>(relaxed = true)
        val repo = PaymentQueueRepositoryImpl(dao)
        val useCase = mockk<RecordPaymentUseCase>()

        val entity1 = pendingEntity(reference = "ref-x", id = 201L)
        val entity2 = pendingEntity(reference = "ref-y", id = 202L)
        coEvery { dao.claimBatch(any(), any(), any(), any()) } returns listOf(entity1, entity2)
        coEvery { useCase(any(), any(), any(), any()) } returns Result.failure(IOException("red caida"))
        // El release() del PRIMER pago se cancela — nunca debería llegar al segundo.
        coEvery {
            dao.release(entity1.id, token = any(), retryCount = any(), error = any())
        } throws CancellationException("worker cancelado")

        val worker = buildWorker(repo, useCase)

        var propagated = false
        try {
            worker.doWork()
        } catch (e: CancellationException) {
            propagated = true
        }

        // No un Result.success()/retry() silencioso: la cancelación se repropagó de verdad.
        assertThat(propagated).isTrue()
        // NO siguió con el segundo pago de la tanda — el throw cortó el for loop de doWork()
        // en seco, antes de llegar a entity2.
        coVerify(exactly = 1) { useCase(any(), any(), any(), any()) }
        coVerify(exactly = 0) {
            dao.release(entity2.id, token = any(), retryCount = any(), error = any())
        }
    }

    // ------------------------------------------------------------------
    // El banner debe enterarse cuando el worker termina (bug real 2026-08-07)
    // ------------------------------------------------------------------

    /**
     * Bug real: los 2 pagos atorados sincronizaron (success=2) pero el banner
     * siguio diciendo "2 pagos pendientes" hasta reiniciar la app. El contador
     * (PaymentQueueStateManager) es un StateFlow de push manual: el boton de
     * reintentar lo refresca al RESETEAR, pero nadie lo refrescaba cuando el
     * worker TERMINABA. El worker debe empujar los conteos reales al acabar.
     */
    @Test
    fun `al terminar la tanda el worker refresca los conteos del banner`() = runTest {
        val repo = mockk<PaymentQueueRepository>(relaxed = true)
        val useCase = mockk<RecordPaymentUseCase>()
        val stateManager = PaymentQueueStateManager()
        // Estado viejo sembrado: el banner cree que hay 2 pendientes.
        stateManager.refreshCounts(pendingCount = 2, failedCount = 0)

        val payment = queuedPayment(reference = "ref-sync-ok")
        coEvery { repo.claimBatch(any()) } returns listOf(payment)
        coEvery { useCase(any(), any(), any(), any()) } returns Result.success(mockk(relaxed = true))
        // Tras sincronizar, la verdad en Room es 0 pendientes / 0 fallidos.
        coEvery { repo.getPendingCount() } returns 0
        coEvery { repo.getFailedCount() } returns 0

        buildWorker(repo, useCase, stateManager).doWork()

        // Sin el fix, el estado se queda en el 2 sembrado y el banner miente.
        assertThat(stateManager.queueState.value.pendingCount).isEqualTo(0)
        assertThat(stateManager.queueState.value.failedCount).isEqualTo(0)
        assertThat(stateManager.queueState.value.hasAnyPayments).isFalse()
    }

    /** Un fallo al CONTAR jamas debe tirar el worker: los pagos ya se procesaron. */
    @Test
    fun `si contar falla el worker igual termina en success`() = runTest {
        val repo = mockk<PaymentQueueRepository>(relaxed = true)
        val useCase = mockk<RecordPaymentUseCase>()
        val payment = queuedPayment(reference = "ref-count-boom")
        coEvery { repo.claimBatch(any()) } returns listOf(payment)
        coEvery { useCase(any(), any(), any(), any()) } returns Result.success(mockk(relaxed = true))
        coEvery { repo.getPendingCount() } throws IllegalStateException("db cerrada")

        val result = buildWorker(repo, useCase).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Construye el worker directo (bypass de Hilt) con los dobles de prueba dados. */
    private fun buildWorker(
        repo: PaymentQueueRepository,
        useCase: RecordPaymentUseCase,
        stateManager: PaymentQueueStateManager = PaymentQueueStateManager(),
    ): PaymentSyncWorker {
        val context = mockk<Context>(relaxed = true)
        return TestListenableWorkerBuilder<PaymentSyncWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = PaymentSyncWorker(appContext, workerParameters, repo, useCase, stateManager)
            })
            .build()
    }

    // No hay helper de prueba compartido para QueuedPayment en el módulo (Task 2 lo
    // define private dentro de PendingPaymentEnqueueTest) — se redefine aquí con el
    // mismo patrón de valores mínimos válidos.
    private fun queuedPayment(
        reference: String,
        retryCount: Int = 0,
        queueId: Long = 1L,
    ): QueuedPayment = QueuedPayment(
        queueId = queueId,
        referenceNumber = reference,
        venueId = "venue-1",
        staffId = "staff-1",
        amount = BigDecimal("100.00"),
        tip = BigDecimal("10.00"),
        rating = null,
        merchantAccountId = "merchant-1",
        blumonSerialNumber = "2841548417",
        maskedPan = "411111******1111",
        cardBrand = "VISA",
        entryMode = "CHIP",
        isInternational = false,
        authorizationNumber = "502511",
        createdAt = System.currentTimeMillis(),
        retryCount = retryCount,
        claimToken = "claim-token-1", // filas de claimBatch() siempre traen token
    )

    // Entity-level gemelo de queuedPayment(), para el test que pasa por el
    // PaymentQueueRepositoryImpl REAL (mismo patrón de valores mínimos que
    // PendingPaymentEnqueueTest.kt's entity()). syncStatus=SYNCING + claimToken puesto
    // porque así se ve una fila recién reclamada por claimBatch() en producción.
    private fun pendingEntity(
        reference: String,
        id: Long,
    ): PendingPaymentEntity = PendingPaymentEntity(
        id = id,
        referenceNumber = reference,
        venueId = "venue-1",
        staffId = "staff-1",
        amount = "100.00",
        tip = "10.00",
        rating = null,
        merchantAccountId = "merchant-1",
        blumonSerialNumber = "2841548417",
        maskedPan = "411111******1111",
        cardBrand = "VISA",
        entryMode = "CHIP",
        isInternational = false,
        authorizationNumber = "502511",
        createdAt = System.currentTimeMillis(),
        syncStatus = PendingPaymentEntity.SYNC_STATUS_SYNCING,
        claimToken = "claim-token-batch",
    )
}

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
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.core.util.PaymentQueueStateManager
import com.jaac.avoqado_tpv.features.checkout.data.VentaLocalRepository
import com.jaac.avoqado_tpv.features.checkout.domain.model.RenglonDeTicket
import com.jaac.avoqado_tpv.features.checkout.domain.model.VentaLocal
import com.jaac.avoqado_tpv.features.payment.data.api.VentaGuardadaApi
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger
import com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDelVeredicto
import com.jaac.avoqado_tpv.features.payment.data.repository.PaymentQueueRepositoryImpl
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt
import com.jaac.avoqado_tpv.features.payment.domain.referenciaDeEfectivo
import com.jaac.avoqado_tpv.features.payment.domain.repository.PaymentQueueRepository
import com.jaac.avoqado_tpv.features.payment.domain.repository.RefundQueueRepository
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordPaymentUseCase
import com.jaac.avoqado_tpv.features.payment.domain.usecase.SubirVentaGuardadaUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import timber.log.Timber

/** C4: Worker y reclamo Room reales; sólo la API externa se simula. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class PaymentSyncWorkerVentaGuardadaTest {
    private lateinit var context: Context
    private lateinit var db: AvoqadoDatabase
    private lateinit var queue: PaymentQueueRepositoryImpl
    private lateinit var api: VentaGuardadaApi
    private lateinit var legacy: RecordPaymentUseCase
    private val state = PaymentQueueStateManager()
    private val llave = "c000000000000000000000001"
    private val orderId = "c000000000000000000000002"
    private val venta = VentaLocal(
        llave = llave, venueId = "venue-1", staffId = "staff-1", shiftId = "shift-1",
        createdAtLocal = 1_760_000_000_000L, totalCents = 10000,
        pedidoJson = """{ "externalId":"c000000000000000000000001", "items":[{"externalId":"c000000000000000000000001:0","unitPrice":10000,"quantity":1}], "total":10000 }""",
        renglones = listOf(RenglonDeTicket("$llave:0", "Blusa", 1, 10000, 10000, emptyList(), null)),
    )

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AvoqadoDatabase::class.java).allowMainThreadQueries().build()
        queue = PaymentQueueRepositoryImpl(db.pendingPaymentDao())
        api = mockk()
        legacy = mockk()
        coEvery { api.crearOrden("venue-1", any()) } returns orden()
        coEvery { api.pagar("venue-1", orderId, any()) } returns pago()
        coEvery { legacy(any(), any(), any(), any(), true) } returns Result.failure(IOException("no debe usar legacy"))
    }

    @After fun tearDown() { db.close() }

    private suspend fun guardar() = VentaLocalRepository(db.pendingPaymentDao(), shiftRepository = com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture())
        .guardar(venta, PendingPaymentEntity.PROCESSOR_BLUMON, "", "AVQD-1").getOrThrow()

    private suspend fun fila() = db.pendingPaymentDao().findByReference(referenciaDeEfectivo(llave), "venue-1")!!

    private fun orden() = json("""{"success":true,"order":{"id":"$orderId","orderNumber":"V-101","status":"PENDING",
        "paymentStatus":"PENDING","subtotal":100,"taxAmount":0,"discountAmount":0,"total":100,
        "items":[{"id":"item-1","productId":"product-1","productName":"Blusa","quantity":1,"unitPrice":100,"total":100,
        "discountAmount":0,"appliedDiscountId":null,"orderPromotionId":null,"externalId":"$llave:0","modifiers":[]}],
        "promotions":[],"createdAt":"2026-10-22T12:00:00.000Z","priceChanges":[]}}""")

    private fun pago() = json("""{"success":true,"payment":{"paymentId":"payment-1","orderId":"$orderId","orderNumber":"V-101",
        "amount":10000,"tipAmount":0,"method":"CASH","status":"COMPLETED","changeCents":0,"digitalReceipt":null,
        "remainingBalanceCents":0,"orderPaymentStatus":"PAID","orderTotalCents":10000,"totalPaidCents":10000}}""")

    private fun json(text: String): Response<JsonObject> = Response.success(JsonParser.parseString(text).asJsonObject)
    private fun error(code: Int): Response<JsonObject> = Response.error(
        code, """{"code":"ORDER_NOT_FOUND","message":"No existe la orden"}""".toResponseBody("application/json".toMediaType()),
    )

    private fun worker(repository: PaymentQueueRepository = queue): PaymentSyncWorker {
        val refunds = mockk<RefundQueueRepository>(relaxed = true)
        coEvery { refunds.claimBatch(any()) } returns emptyList()
        val ledger = mockk<PaymentAttemptLedger>(relaxed = true)
        coEvery { ledger.aplicarVeredictoDelServidor(any()) } returns Result.success(
            ResultadoDelVeredicto(ResultadoDelVeredicto.Decision.SIN_FILA, false, null, false),
        )
        val subir = SubirVentaGuardadaUseCase(api, db.pendingPaymentDao(), context)
        return TestListenableWorkerBuilder<PaymentSyncWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    PaymentSyncWorker(appContext, workerParameters, repository, legacy, state, refunds, ledger, subir, tableSyncCoordinator = com.jaac.avoqado_tpv.features.shift.data.emptyTableReplayFixture())
            }).build()
    }

    // R1: el repositorio sólo simula el cero del cierre; Room conserva reclamos, filas y conteos reales.
    private class CierreNoAplicado(
        private val real: PaymentQueueRepository,
        private val sinCierre: Long,
    ) : PaymentQueueRepository by real {
        val cierres = mutableListOf<Pair<Long, String>>()
        val otrosWrites = mutableListOf<String>()

        override suspend fun markSynced(queueId: Long, token: String): Int {
            cierres += queueId to token
            return if (queueId == sinCierre) 0 else real.markSynced(queueId, token)
        }

        override suspend fun release(queueId: Long, token: String, retryCount: Int, error: String): Int {
            otrosWrites += "release:$queueId:$token"
            return real.release(queueId, token, retryCount, error)
        }

        override suspend fun markPermanentlyFailed(queueId: Long, token: String, error: String): Int {
            otrosWrites += "permanent:$queueId:$token"
            return real.markPermanentlyFailed(queueId, token, error)
        }
    }

    private suspend fun resumen(repository: PaymentQueueRepository): List<String> {
        val mensajes = CopyOnWriteArrayList<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (message.contains("[Payment Sync] Worker completed | ")) {
                    mensajes += message.substringAfter("Worker completed | ")
                }
            }
        }
        Timber.plant(tree)
        try {
            assertThat(worker(repository).doWork()).isEqualTo(ListenableWorker.Result.success())
        } finally {
            Timber.uproot(tree)
        }
        return mensajes.toList()
    }

    private suspend fun guardarLegacy(): PendingPaymentEntity {
        db.pendingPaymentDao().insert(PendingPaymentEntity(
            referenceNumber = "CASH-legacy-cas0", venueId = "venue-1", staffId = "staff-1",
            amount = "25.00", tip = "0.00", rating = null, merchantAccountId = "", blumonSerialNumber = "",
            maskedPan = null, cardBrand = null, entryMode = "MANUAL", isInternational = false,
            authorizationNumber = "EFECTIVO", createdAt = venta.createdAtLocal + 1,
            idempotencyKey = "c000000000000000000000003",
        ))
        coEvery { legacy(any(), any(), any(), "CASH-legacy-cas0", true) } returns Result.success(
            PaymentReceipt("payment-legacy", "", "", BigDecimal("25.00"), BigDecimal.ZERO),
        )
        return db.pendingPaymentDao().findByReference("CASH-legacy-cas0", "venue-1")!!
    }

    private suspend fun comprobarFilaConservada(antes: PendingPaymentEntity, repo: CierreNoAplicado) {
        val despues = db.pendingPaymentDao().findByReference(antes.referenceNumber, antes.venueId)!!
        assertThat(despues.syncStatus).isEqualTo("SYNCING")
        assertThat(despues.idempotencyKey).isEqualTo(antes.idempotencyKey)
        assertThat(despues.orderRequestJson).isEqualTo(antes.orderRequestJson)
        assertThat(despues.amount).isEqualTo(antes.amount)
        assertThat(despues.retryCount).isEqualTo(antes.retryCount)
        assertThat(despues.claimToken).isNotEmpty()
        assertThat(repo.cierres.filter { it.first == antes.id }).containsExactly(antes.id to despues.claimToken!!)
        assertThat(repo.otrosWrites).isEmpty()
        assertThat(queue.getPendingCount()).isEqualTo(1)
        assertThat(queue.getFailedCount()).isEqualTo(0)
    }

    @Test fun `un 2xx local cuyo cierre afecta cero cuenta fallo y conserva la fila sin repetir el write`() = runTest {
        guardar()
        val antes = fila()
        val repo = CierreNoAplicado(queue, antes.id)

        assertThat(resumen(repo)).containsExactly("success=0 | failed=1 | batch=1")

        comprobarFilaConservada(antes, repo)
        coVerify(exactly = 1) { api.crearOrden(any(), any()) }
        coVerify(exactly = 1) { api.pagar(any(), any(), any()) }
        coVerify(exactly = 0) { legacy(any(), any(), any(), any(), true) }
    }

    @Test fun `un cierre local de cero filas no abandona la siguiente fila de la misma tanda`() = runTest {
        guardar()
        val antes = fila()
        val siguiente = guardarLegacy()
        val repo = CierreNoAplicado(queue, antes.id)

        assertThat(resumen(repo)).containsExactly("success=1 | failed=1 | batch=2")

        comprobarFilaConservada(antes, repo)
        val cerrada = db.pendingPaymentDao().findByReference(siguiente.referenceNumber, siguiente.venueId)!!
        assertThat(cerrada.syncStatus).isEqualTo("SUCCESS")
        assertThat(cerrada.idempotencyKey).isEqualTo(siguiente.idempotencyKey)
        assertThat(repo.cierres.count { it.first == siguiente.id }).isEqualTo(1)
        coVerify(exactly = 1) { api.pagar(any(), any(), any()) }
        coVerify(exactly = 1) { legacy(any(), any(), any(), siguiente.referenceNumber, true) }
    }

    @Test fun `un 2xx legacy cuyo cierre afecta cero tampoco cuenta exito ni repite su write`() = runTest {
        val antes = guardarLegacy()
        val repo = CierreNoAplicado(queue, antes.id)

        assertThat(resumen(repo)).containsExactly("success=0 | failed=1 | batch=1")

        comprobarFilaConservada(antes, repo)
        coVerify(exactly = 1) { legacy(any(), any(), any(), antes.referenceNumber, true) }
        coVerify(exactly = 0) { api.crearOrden(any(), any()) }
        coVerify(exactly = 0) { api.pagar(any(), any(), any()) }
    }

    @Test fun `una venta guardada crea su orden paga y cierra su fila sin fast`() = runTest {
        guardar()
        val cuerpos = mutableListOf<String>()
        coEvery { api.crearOrden("venue-1", any()) } coAnswers {
            cuerpos += Buffer().also { secondArg<okhttp3.RequestBody>().writeTo(it) }.readUtf8()
            orden()
        }

        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())

        val registrada = fila()
        assertThat(registrada.syncStatus).isEqualTo("SUCCESS")
        assertThat(registrada.claimToken).isNull()
        assertThat(registrada.orderId).isEqualTo(orderId)
        assertThat(registrada.orderRequestJson).isEqualTo(venta.pedidoJson)
        assertThat(cuerpos).containsExactly(venta.pedidoJson)
        coVerify(exactly = 1) { api.pagar("venue-1", orderId, any()) }
        coVerify(exactly = 0) { legacy(any(), any(), any(), any(), true) }
    }

    @Test fun `un 404 al pagar la venta guardada conserva FAILED sin fallback a venta rapida`() = runTest {
        guardar()
        val dao = db.pendingPaymentDao()
        val reclamada = dao.claimBatch(1, "preparar", 1_000L, 0L).single()
        dao.guardarOrdenDeVentaGuardada(reclamada.id, "preparar", orderId, "V-101", false)
        dao.release(reclamada.id, "preparar", 0, "respuesta perdida al pagar")
        coEvery { api.pagar("venue-1", orderId, any()) } returns error(404)
        coEvery { legacy(any(), any(), any(), any(), true) } returns
            Result.failure(BackendHttpException(404, "Order not found", errorCode = "ORDER_NOT_FOUND"))

        worker().doWork()

        val rechazada = fila()
        assertThat(rechazada.syncStatus).isEqualTo("FAILED")
        assertThat(rechazada.permanent).isTrue()
        assertThat(rechazada.claimToken).isNull()
        assertThat(rechazada.orderRequestJson).isEqualTo(venta.pedidoJson)
        assertThat(queue.getFailedCount()).isEqualTo(1)
        coVerify(exactly = 1) { api.pagar("venue-1", orderId, any()) }
        coVerify(exactly = 0) { api.crearOrden(any(), any()) }
        coVerify(exactly = 0) { legacy(any(), any(), any(), any(), true) }
    }

    @Test fun `un fallo transitorio local no abandona la siguiente fila legacy de la tanda`() = runTest {
        guardar()
        db.pendingPaymentDao().insert(PendingPaymentEntity(
            referenceNumber = "CASH-legacy", venueId = "venue-1", staffId = "staff-1", amount = "25.00", tip = "0.00", rating = null,
            merchantAccountId = "", blumonSerialNumber = "", maskedPan = null, cardBrand = null, entryMode = "MANUAL",
            isInternational = false, authorizationNumber = "EFECTIVO", createdAt = venta.createdAtLocal + 1,
        ))
        coEvery { api.crearOrden("venue-1", any()) } throws IOException("red caida")
        coEvery { legacy(any(), any(), any(), "CASH-legacy", true) } returns Result.success(
            PaymentReceipt("payment-legacy", "", "", BigDecimal("25.00"), BigDecimal.ZERO),
        )

        assertThat(worker().doWork()).isEqualTo(ListenableWorker.Result.success())

        assertThat(fila().syncStatus).isEqualTo("PENDING")
        assertThat(fila().retryCount).isEqualTo(1)
        assertThat(fila().claimToken).isNull()
        assertThat(db.pendingPaymentDao().findByReference("CASH-legacy", "venue-1")!!.syncStatus).isEqualTo("SUCCESS")
        coVerify(exactly = 1) { api.crearOrden(any(), any()) }
        coVerify(exactly = 1) { legacy(any(), any(), any(), "CASH-legacy", true) }
        coVerify(exactly = 0) { legacy(any(), any(), any(), referenciaDeEfectivo(llave), true) }
    }

    @Test fun `diez intentos separados agotan la fila y la conservan contada como fallida`() = runTest {
        guardar()
        coEvery { api.crearOrden("venue-1", any()) } returns error(503)

        repeat(10) { indice ->
            worker().doWork()
            assertThat(fila().retryCount).isEqualTo(indice + 1)
            assertThat(fila().syncStatus).isEqualTo(if (indice == 9) "FAILED" else "PENDING")
        }
        worker().doWork()

        val fallida = fila()
        assertThat(fallida.claimToken).isNull()
        assertThat(fallida.claimedAt).isNull()
        assertThat(fallida.permanent).isFalse()
        assertThat(fallida.orderRequestJson).isEqualTo(venta.pedidoJson)
        assertThat(fallida.idempotencyKey).isEqualTo(llave)
        assertThat(fallida.amount).isEqualTo("100.00")
        assertThat(queue.getFailedCount()).isEqualTo(1)
        assertThat(queue.getPendingCount()).isEqualTo(0)
        assertThat(state.queueState.value.failedCount).isEqualTo(1)
        coVerify(exactly = 10) { api.crearOrden(any(), any()) }
        coVerify(exactly = 0) { api.pagar(any(), any(), any()) }
        coVerify(exactly = 0) { legacy(any(), any(), any(), any(), true) }
    }

    @Test fun `dos workers con reclamo Room real hacen una sola llamada a crear orden`() = runTest {
        guardar()
        val entro = CompletableDeferred<Unit>()
        val continuar = CompletableDeferred<Unit>()
        coEvery { api.crearOrden("venue-1", any()) } coAnswers {
            entro.complete(Unit)
            continuar.await()
            orden()
        }
        withContext(Dispatchers.Default) {
            withTimeout(10_000L) {
                val primero = async { worker().doWork() }
                try {
                    val envioEnCurso = select<Boolean> {
                        entro.onAwait { true }
                        primero.onAwait { false }
                    }
                    assertThat(envioEnCurso).isTrue()
                    val segundo = async { worker().doWork() }
                    assertThat(segundo.await()).isEqualTo(ListenableWorker.Result.success())
                    coVerify(exactly = 1) { api.crearOrden(any(), any()) }
                    assertThat(fila().syncStatus).isEqualTo("SYNCING")
                } finally { continuar.complete(Unit) }
                assertThat(primero.await()).isEqualTo(ListenableWorker.Result.success())
            }
        }
        assertThat(fila().syncStatus).isEqualTo("SUCCESS")
        coVerify(exactly = 1) { api.crearOrden(any(), any()) }
        coVerify(exactly = 1) { api.pagar(any(), any(), any()) }
        coVerify(exactly = 0) { legacy(any(), any(), any(), any(), true) }
    }

    // D7: antes la fila se quedaba SYNCING con su candado hasta el barrido de 15 min. Reenviar es
    // seguro: la orden lleva externalId = la llave (@@unique([venueId, externalId]) en el SERVIDOR)
    // y el pago la misma idempotencyKey.
    @Test fun `cancelar el worker suelta la venta guardada sin seguir enviando ni contar intento`() = runTest {
        guardar()
        val enVuelo = CompletableDeferred<Unit>()
        coEvery { api.crearOrden("venue-1", any()) } coAnswers {
            enVuelo.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }

        // Cancelación DE VERDAD del Job (como WorkManager): con Room real, soltar la fila sin
        // NonCancellable no correría.
        withContext(Dispatchers.Default) {
            withTimeout(10_000L) {
                val corrida = async { worker().doWork() }
                enVuelo.await()
                corrida.cancel(CancellationException("WorkManager detuvo el worker"))
                corrida.join()
                assertThat(corrida.isCancelled).isTrue()
            }
        }

        assertThat(fila().syncStatus).isEqualTo("PENDING")
        assertThat(fila().claimToken).isNull()
        assertThat(fila().retryCount).isEqualTo(0)
        assertThat(fila().orderRequestJson).isEqualTo(venta.pedidoJson)
        coVerify(exactly = 0) { api.pagar(any(), any(), any()) }
        coVerify(exactly = 0) { legacy(any(), any(), any(), any(), true) }
    }
}

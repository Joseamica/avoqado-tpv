package com.jaac.avoqado_tpv.features.shift.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.jaac.avoqado_tpv.MainDispatcherRule
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.entity.LocalShiftSessionEntity
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.workers.PaymentSyncWorker
import com.jaac.avoqado_tpv.core.util.PaymentQueueStateManager
import com.jaac.avoqado_tpv.core.util.PaymentSyncScheduler
import com.jaac.avoqado_tpv.features.payment.data.api.VentaGuardadaApi
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger
import com.jaac.avoqado_tpv.features.payment.data.repository.PaymentQueueRepositoryImpl
import com.jaac.avoqado_tpv.features.payment.data.repository.RefundQueueRepositoryImpl
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordPaymentUseCase
import com.jaac.avoqado_tpv.features.payment.domain.usecase.SubirVentaGuardadaUseCase
import com.jaac.avoqado_tpv.features.shift.data.dto.*
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftReplayGate
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import com.jaac.avoqado_tpv.features.shift.domain.CashReconciliationAction
import com.jaac.avoqado_tpv.features.tables.data.PendingRoundCart
import com.jaac.avoqado_tpv.features.tables.data.TableSession
import com.jaac.avoqado_tpv.features.tables.data.TablesRepository
import com.jaac.avoqado_tpv.features.tables.data.api.TablesApiService
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentAck
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentsRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentsResponse
import com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentEntity
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox
import com.jaac.avoqado_tpv.features.tables.data.sync.TableSyncCoordinator
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import retrofit2.Response
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** F1/F2: public writers, Room, Worker and table replayer real; no VM contributes recovery. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28],
    instrumentedPackages = ["com.jaac.avoqado_tpv.features.shift.data.repository"])
@LooperMode(LooperMode.Mode.PAUSED)
class Task6FixR1ReplayRoomTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val storage = mockk<SecureStorage>(relaxed = true)
    private val api = mockk<ApiService>()
    private val tableApi = mockk<TablesApiService>()
    private var actor = "capturador"
    private val venue = "v1"
    private val remoteA = "c000000000000000000000001"
    private val drawerA = "c000000000000000000000002"
    private val remoteB = "c000000000000000000000003"
    private val drawerB = "c000000000000000000000004"
    private val order = "c000000000000000000000005"
    private val localOrder = "c000000000000000000000006"
    private val exact = "2026-10-28T18:00:00Z"
    private val normalized = "2026-10-28T18:00:00.000Z"
    private val events = CopyOnWriteArrayList<String>()
    private val sent = CopyOnWriteArrayList<SyncIntentsRequest>()
    private lateinit var db: AvoqadoDatabase
    private lateinit var repo: ShiftRepository
    private lateinit var gate: ShiftReplayGate
    private lateinit var outbox: SyncOutbox
    private lateinit var coordinator: TableSyncCoordinator
    private lateinit var tableSession: TableSession
    private lateinit var tables: TablesRepository
    private var manager: WorkManager? = null
    private var aId: String? = null

    @Before fun setup() {
        android.os.SystemClock.setCurrentTimeMillis(Instant.parse(exact).toEpochMilli())
        every { storage.getVenueId() } returns venue
        every { storage.getStaffId() } answers { actor }
        every { storage.getToken() } returns "token"
        every { storage.getSerialNumber() } returns "SERIAL-ORIGINAL"
        every { storage.getKioskVenueId() } returns null
        every { storage.contains("enable_shifts") } returns true
        every { storage.getBoolean("enable_shifts", true) } returns true
        every { storage.getString("permissions_cache", null) } answers {
            "$venue|$actor\ntpv-shifts:create,tpv-shifts:close,shifts:read,payments:create,payments:refund"
        }
        reopen()
        coEvery { api.openShift(venue, any()) } coAnswers {
            val request = secondArg<OpenShiftRequest>()
            val a = aId == null || request.localId == aId
            events += "OPEN:${request.localId}"
            Response.success(ShiftResponse(true, remote(request.localId, if (a) remoteA else remoteB, if (a) drawerA else drawerB).copy(
                startTime = request.openedAt ?: exact, startingCash = if (a) "50.00" else "75.00", fondoAplicado = if (a) "50.00" else "75.00")))
        }
        coEvery { tableApi.syncIntents(venue, any()) } coAnswers {
            val request = secondArg<SyncIntentsRequest>(); sent += request
            Response.success(SyncIntentsResponse(data = request.intents.map { SyncIntentAck(it.id, "ACKED") }))
        }
        coEvery { api.closeShift(venue, any(), any()) } coAnswers {
            val request = thirdArg<CloseShiftRequest>()
            val row = db.localShiftSessionDao().porReproducir(venue).first { it.closeOperationId == request.operationId }
            events += "CLOSE:${row.localSessionId}"
            Response.success(ShiftResponse(true, remote(row.localSessionId).copy(status = "CLOSED"), operation = operation(row, row.closedAtLocal)))
        }
    }
    @After fun cleanup() {
        manager?.cancelAllWork()?.result?.get()
        androidx.work.impl.WorkManagerImpl.setDelegate(null)
        db.close(); context.deleteDatabase("task6-fix-r1")
    }
    private fun reopen() {
        db = Room.databaseBuilder(context, AvoqadoDatabase::class.java, "task6-fix-r1").allowMainThreadQueries().build()
        val state = PaymentQueueStateManager()
        val refunds = RefundQueueRepositoryImpl(db.pendingRefundDao(), mockk(), state)
        repo = ShiftRepository(api, db, storage, db.cachedShiftDao(), refunds, appContext = context)
        gate = ShiftReplayGate(db, storage)
        outbox = SyncOutbox(db.syncIntentDao(), tableApi, storage, repo, gate)
        tableSession = TableSession()
        coordinator = TableSyncCoordinator(outbox, tableSession, PendingRoundCart())
        tables = TablesRepository(tableApi, outbox, mockk(relaxed = true))
    }
    private fun recreate() { db.close(); reopen() }
    private fun remote(local: String?, id: String = remoteA, drawer: String = drawerA): ShiftDto = Gson().fromJson("""{
        "id":"$id","venueId":"$venue","staffId":"capturador","startTime":"2026-10-28T17:00:00.000Z","status":"OPEN",
        "startingCash":"50.00","totalSales":"0","totalTips":"0","totalOrders":0,"totalCashPayments":"0","totalCardPayments":"0",
        "totalVoucherPayments":"0","totalOtherPayments":"0","totalProductsSold":0,"cashDrawerSessionId":"$drawer",
        "localId":"$local","openingDisposition":"OWN","fondoAplicado":"50.00"} """, ShiftDto::class.java)
    private fun operation(row: LocalShiftSessionEntity, date: String?) = ShiftOperationDto(
        operationId = row.closeOperationId, kind = "CLOSE", state = "APPLIED", closedAtLocal = date,
        cashDrawerSessionId = row.cashDrawerSessionId, submittedCountedCash = row.countedCash, countApplied = true, pairStatus = "COMPLETE")
    private suspend fun openA(confirmed: Boolean = false): LocalShiftSessionEntity {
        val projected = repo.openShift(venue, actor, 50.0).getOrThrow()
        aId = projected.localShiftSessionId!!
        if (confirmed) repo.sincronizarAperturasLocales(venue)
        return db.localShiftSessionDao().leer(venue, aId!!)!!
    }
    private suspend fun closeA(): LocalShiftSessionEntity {
        repo.closeShift(venue, aId!!, CashReconciliationAction.COUNTED, BigDecimal("61.25")).getOrThrow()
        return db.localShiftSessionDao().leer(venue, aId!!)!!
    }
    private suspend fun pay(amount: String = "10.00", provisional: Boolean = false): SyncIntentEntity {
        val result = tables.payCash(venue, if (provisional) localOrder else order, provisional, BigDecimal(amount), BigDecimal("1.25")).getOrThrow()
        assertThat(result.queued).isTrue()
        return db.syncIntentDao().allForVenue(venue).last { it.type == "PAY_CASH" }
    }
    private fun worker(): PaymentSyncWorker {
        val state = PaymentQueueStateManager()
        val queue = PaymentQueueRepositoryImpl(db.pendingPaymentDao())
        val refunds = RefundQueueRepositoryImpl(db.pendingRefundDao(), mockk(), state)
        val ledger = PaymentAttemptLedger(db.paymentAttemptDao(), mockk(relaxed = true))
        val record = mockk<RecordPaymentUseCase>() // empty queue: an accidental call fails, never supplies financial truth
        val upload = SubirVentaGuardadaUseCase(mockk<VentaGuardadaApi>(), db.pendingPaymentDao(), context, gate)
        return TestListenableWorkerBuilder<PaymentSyncWorker>(context).setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: android.content.Context, workerClassName: String, params: WorkerParameters): ListenableWorker =
                PaymentSyncWorker(appContext, params, queue, record, state, refunds, ledger, upload, repo, gate, coordinator)
        }).build()
    }
    private fun blockedWork(): Int {
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        manager = WorkManager.getInstance(context)
        val blocker = OneTimeWorkRequestBuilder<PaymentSyncWorker>().setInitialDelay(1, TimeUnit.DAYS).build()
        manager!!.enqueueUniqueWork(PaymentSyncScheduler.IMMEDIATE_WORK_NAME, ExistingWorkPolicy.KEEP, blocker).result.get()
        return chainSize()
    }
    private fun chainSize() = manager!!.getWorkInfosForUniqueWork(PaymentSyncScheduler.IMMEDIATE_WORK_NAME).get().size
    private suspend fun assertCapture(row: LocalShiftSessionEntity) {
        val after = db.localShiftSessionDao().leer(venue, row.localSessionId)!!
        assertThat(after.closeOperationId).isEqualTo(row.closeOperationId)
        assertThat(after.closeRequestJson).isEqualTo(row.closeRequestJson)
        assertThat(after.closedAtLocal).isEqualTo(exact)
        assertThat(after.countedCash).isEqualTo("61.25")
        assertThat(after.closedByStaffId).isEqualTo("capturador")
    }

    @Test fun `F1 POST APPLIED normalizado reconoce mismo instante y permite B sin reescribir captura`() = runTest {
        openA(confirmed = true)
        val original = closeA()
        assertThat(original.closedAtLocal).isEqualTo(exact)
        val b = repo.openShift(venue, actor, 75.0).getOrThrow().localShiftSessionId!!
        coEvery { api.closeShift(venue, remoteA, any()) } coAnswers {
            val request = thirdArg<CloseShiftRequest>()
            assertThat(request.closedAtLocal).isEqualTo(exact)
            assertThat(request.operationId).isEqualTo(original.closeOperationId)
            Response.success(ShiftResponse(true, remote(aId).copy(status = "CLOSED"), operation = operation(original, normalized)))
        }
        worker().doWork()
        assertThat(db.localShiftSessionDao().leer(venue, aId!!)!!.closeStatus).isEqualTo("CONFIRMED")
        worker().doWork()
        assertThat(db.localShiftSessionDao().leer(venue, b)!!.openingStatus).isEqualTo("CONFIRMED")
        assertCapture(original)
        coVerify(exactly = 1) { api.closeShift(venue, remoteA, any()) }
    }

    @Test fun `F1 respuesta perdida recrea consulta misma K normalizada y avanza B sin segundo CLOSE`() = runTest {
        openA(confirmed = true); val original = closeA()
        val b = repo.openShift(venue, actor, 75.0).getOrThrow().localShiftSessionId!!
        coEvery { api.closeShift(venue, remoteA, any()) } throws IOException("APPLIED y se perdio respuesta")
        worker().doWork()
        val wire = db.localShiftSessionDao().leer(venue, aId!!)!!.closeWireRequestJson
        assertThat(wire).isNotNull()
        recreate(); actor = "recuperador"
        coEvery { api.getShiftOperation(venue, original.closeOperationId!!) } returns Response.success(
            ShiftOperationResponse(true, operation(original, normalized), ShiftOperationShiftDto(id = remoteA, status = "CLOSED")))
        worker().doWork()
        assertThat(db.localShiftSessionDao().leer(venue, aId!!)!!.closeStatus).isEqualTo("CONFIRMED")
        worker().doWork()
        assertThat(db.localShiftSessionDao().leer(venue, b)!!.openingStatus).isEqualTo("CONFIRMED")
        assertThat(db.localShiftSessionDao().leer(venue, aId!!)!!.closeWireRequestJson).isEqualTo(wire)
        assertCapture(original)
        coVerify(exactly = 1) { api.closeShift(venue, remoteA, any()) }
        coVerify(exactly = 1) { api.getShiftOperation(venue, original.closeOperationId!!) }
    }

    @Test fun `F1 fecha invalida null o distinto instante no acredita ni abre B`() = runTest {
        openA(confirmed = true); val original = closeA()
        val b = repo.openShift(venue, actor, 75.0).getOrThrow().localShiftSessionId!!
        for (date in listOf(null, "not-an-instant", "2026-10-28T18:00:00.001Z", "2026-10-28T18:00:00.000000001Z")) {
            coEvery { api.closeShift(venue, remoteA, any()) } returns Response.success(
                ShiftResponse(true, remote(aId).copy(status = "CLOSED"), operation = operation(original, date)))
            coEvery { api.getShiftOperation(venue, original.closeOperationId!!) } returns Response.success(
                ShiftOperationResponse(true, operation(original, date), ShiftOperationShiftDto(id = remoteA, status = "CLOSED")))
            worker().doWork()
            assertThat(db.localShiftSessionDao().leer(venue, aId!!)!!.closeStatus).isEqualTo("PENDING")
            assertThat(db.localShiftSessionDao().leer(venue, b)!!.openingStatus).isEqualTo("PENDING")
            assertCapture(original)
        }
        coVerify(exactly = 0) { api.openShift(venue, match { it.localId == b }) }
    }

    @Test fun `F2 public PAY_CASH espera OPEN y Home recreado recupera ACK antes CLOSE y OPEN B`() = runTest {
        val a = openA(); val cash = pay()
        coVerify(exactly = 0) { tableApi.syncIntents(any(), any()) }
        val original = closeA(); val b = repo.openShift(venue, actor, 75.0).getOrThrow().localShiftSessionId!!
        recreate(); actor = "recuperador"
        coEvery { tableApi.syncIntents(venue, any()) } coAnswers {
            val request = secondArg<SyncIntentsRequest>(); sent += request; events += "PAY:${request.intents.single().id}"
            assertThat(request.intents.single().id).isEqualTo(cash.id)
            assertThat(request.deviceId).isEqualTo("SERIAL-ORIGINAL")
            val payload = request.intents.single().payload
            assertThat(payload["shiftIdDeLaVenta"].asString).isEqualTo(remoteA)
            assertThat(payload["amountCents"].asInt).isEqualTo(1000)
            assertThat(payload["tipCents"].asInt).isEqualTo(125)
            assertThat(payload.has("staffId")).isFalse() // R11: authenticated replayer, no fabricated author
            Response.success(SyncIntentsResponse(data = listOf(SyncIntentAck(cash.id, "ACKED"))))
        }
        coEvery { api.closeShift(venue, remoteA, any()) } coAnswers {
            assertThat(db.syncIntentDao().leer(venue, cash.id)!!.status).isEqualTo("ACKED")
            val request = thirdArg<CloseShiftRequest>()
            assertThat(request.cashDrawerSessionId).isEqualTo(drawerA)
            events += "CLOSE:${a.localSessionId}"
            Response.success(ShiftResponse(true, remote(aId).copy(status = "CLOSED"),
                operation = operation(original, normalized).copy(cashDrawerSessionId = request.cashDrawerSessionId)))
        }
        worker().doWork()
        assertThat(db.syncIntentDao().leer(venue, cash.id)!!.status).isEqualTo("ACKED")
        assertThat(db.localShiftSessionDao().leer(venue, a.localSessionId)!!.closeStatus).isEqualTo("CONFIRMED")
        worker().doWork()
        assertThat(db.localShiftSessionDao().leer(venue, b)!!.openingStatus).isEqualTo("CONFIRMED")
        assertThat(events).containsExactly("OPEN:${a.localSessionId}", "PAY:${cash.id}", "CLOSE:${a.localSessionId}", "OPEN:$b").inOrder()
        val saved = db.syncIntentDao().leer(venue, cash.id)!!
        assertThat(saved.localShiftSessionId).isEqualTo(a.localSessionId)
        assertThat(saved.cashDrawerSessionId).isNull()
        assertThat(saved.createdAt).isEqualTo(cash.createdAt)
        assertCapture(original)
    }

    @Test fun `F2 mapping ya confirmado respuesta perdida y restart recupera misma K sin volver a Mesas`() = runTest {
        openA(confirmed = true)
        coEvery { tableApi.syncIntents(venue, any()) } coAnswers {
            sent += secondArg<SyncIntentsRequest>(); throw IOException("pago aplicado respuesta perdida")
        }
        val cash = pay(); val firstWire = sent.single()
        closeA(); recreate()
        coEvery { tableApi.syncIntents(venue, any()) } coAnswers {
            val request = secondArg<SyncIntentsRequest>(); sent += request
            Response.success(SyncIntentsResponse(data = listOf(SyncIntentAck(cash.id, "ACKED"))))
        }
        worker().doWork()
        assertThat(db.syncIntentDao().leer(venue, cash.id)!!.status).isEqualTo("ACKED")
        assertThat(sent).hasSize(2)
        assertThat(sent.last().intents).isEqualTo(firstWire.intents)
        assertThat(db.localShiftSessionDao().leer(venue, aId!!)!!.closeStatus).isEqualTo("CONFIRMED")
    }

    @Test fun `F2 RETRY conserva FIFO y responsabilidad hasta siguiente trigger productivo`() = runTest {
        openA(); val cash = pay(); val original = closeA()
        coEvery { tableApi.syncIntents(venue, any()) } coAnswers {
            val request = secondArg<SyncIntentsRequest>(); sent += request
            Response.success(SyncIntentsResponse(data = listOf(SyncIntentAck(cash.id, "RETRY", errorCode = "VERSION_CONFLICT"))))
        }
        worker().doWork()
        assertThat(sent).hasSize(1)
        assertThat(db.syncIntentDao().leer(venue, cash.id)!!.status).isEqualTo("PENDING")
        assertThat(db.localShiftSessionDao().leer(venue, aId!!)!!.closeStatus).isEqualTo("PENDING")
        coVerify(exactly = 0) { api.closeShift(any(), any(), any()) }
        coEvery { tableApi.syncIntents(venue, any()) } coAnswers {
            val request = secondArg<SyncIntentsRequest>(); sent += request
            Response.success(SyncIntentsResponse(data = listOf(SyncIntentAck(cash.id, "ACKED"))))
        }
        worker().doWork()
        assertThat(sent).hasSize(2)
        assertThat(sent[0].intents.single().id).isEqualTo(sent[1].intents.single().id)
        assertThat(db.syncIntentDao().leer(venue, cash.id)!!.status).isEqualTo("ACKED")
        assertCapture(original)
    }

    @Test fun `F2 lote cincuenta conserva prefijo y continuacion durable antes del pago de pagina siguiente`() = runTest {
        openA(confirmed = true)
        outbox.enqueue(venue, "OPEN_TABLE", JsonObject().apply { addProperty("localOrderId", localOrder) }, id = "c000000000000000000000007")
        repeat(49) { i -> outbox.enqueue(venue, "ADD_ITEMS", JsonObject().apply { addProperty("localOrderId", localOrder) }, id = "c" + (100 + i).toString().padStart(24, '0')) }
        tableSession.start(TableSession.Active("table", orderId = localOrder, isProvisional = true))
        coEvery { tableApi.syncIntents(venue, any()) } throws IOException("sin red al capturar")
        val cash = pay(provisional = true); closeA()
        val beforeChain = blockedWork()
        val ids = db.syncIntentDao().pendingForVenue(venue).map { it.id }
        coEvery { tableApi.syncIntents(venue, any()) } coAnswers {
            val request = secondArg<SyncIntentsRequest>(); sent += request
            assertThat(request.intents.size).isAtMost(50)
            Response.success(SyncIntentsResponse(data = request.intents.map { item -> SyncIntentAck(item.id, "ACKED", result =
                JsonObject().apply { addProperty("localOrderId", localOrder); addProperty("orderId", order); addProperty("version", 2) }) }))
        }
        worker().doWork()
        assertThat(sent).hasSize(1)
        assertThat(sent.single().intents.map { it.id }).containsExactlyElementsIn(ids.take(50)).inOrder()
        assertThat(db.syncIntentDao().pendingForVenue(venue).map { it.id }).containsExactly(cash.id)
        assertThat(tableSession.current()!!.orderId).isEqualTo(order)
        assertThat(chainSize()).isEqualTo(beforeChain + 1)
        assertThat(db.localShiftSessionDao().leer(venue, aId!!)!!.closeStatus).isEqualTo("PENDING")
        worker().doWork()
        assertThat(db.syncIntentDao().leer(venue, cash.id)!!.status).isEqualTo("ACKED")
        assertThat(sent.last().intents.single().id).isEqualTo(cash.id)
        assertThat(db.localShiftSessionDao().leer(venue, aId!!)!!.closeStatus).isEqualTo("CONFIRMED")
    }

    @Test fun `F2 fallo de persistir ACK no notifica reconciliacion ni gira o agenda sin avance`() = runTest {
        openA(confirmed = true)
        outbox.enqueue(venue, "OPEN_TABLE", JsonObject().apply { addProperty("localOrderId", localOrder) }, id = "c000000000000000000000007")
        tableSession.start(TableSession.Active("table", orderId = localOrder, isProvisional = true))
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER abort_table_ack BEFORE UPDATE OF status ON sync_intents WHEN NEW.status='ACKED' BEGIN SELECT RAISE(ABORT,'ack disk failure'); END")
        val beforeChain = blockedWork()
        coEvery { tableApi.syncIntents(venue, any()) } coAnswers {
            val request = secondArg<SyncIntentsRequest>(); sent += request
            Response.success(SyncIntentsResponse(data = listOf(SyncIntentAck(request.intents.single().id, "ACKED", result =
                JsonObject().apply { addProperty("localOrderId", localOrder); addProperty("orderId", order) }))))
        }
        worker().doWork()
        assertThat(sent).hasSize(1)
        assertThat(db.syncIntentDao().pendingForVenue(venue)).hasSize(1)
        assertThat(tableSession.current()!!.orderId).isEqualTo(localOrder)
        assertThat(chainSize()).isEqualTo(beforeChain)
    }

    @Test fun `F2 dos workers usan mismo mutex y no emiten segundo PAY_CASH en paralelo`() = runTest {
        openA(confirmed = true)
        coEvery { tableApi.syncIntents(venue, any()) } throws IOException("captura offline")
        val cash = pay()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        coEvery { tableApi.syncIntents(venue, any()) } coAnswers {
            val request = secondArg<SyncIntentsRequest>(); sent += request; entered.complete(Unit); release.await()
            Response.success(SyncIntentsResponse(data = listOf(SyncIntentAck(cash.id, "ACKED"))))
        }
        val secondAtBridge = CompletableDeferred<Unit>()
        val bridgeEntries = java.util.concurrent.atomic.AtomicInteger()
        coordinator = spyk(coordinator)
        coEvery { coordinator.replayCurrentVenueBatch() } coAnswers {
            if (bridgeEntries.incrementAndGet() == 2) secondAtBridge.complete(Unit)
            callOriginal() // Señal de entrada solamente: el coordinador/outbox/mutex y su verdad son reales.
        }
        val first = async(Dispatchers.Default) { worker().doWork() }
        try {
            val reached = select<String> { entered.onAwait { "HTTP" }; first.onAwait { "done" } }
            assertThat(reached).isEqualTo("HTTP")
            val second = async(Dispatchers.Default) { worker().doWork() }
            withContext(Dispatchers.Default) { withTimeout(10_000) { secondAtBridge.await() } }
            assertThat(first.isCompleted).isFalse()
            assertThat(second.isCompleted).isFalse()
            assertThat(sent).hasSize(1) // Segundo ya entró al puente y el primer HTTP continúa retenido.
            release.complete(Unit); first.await(); second.await()
            assertThat(sent).hasSize(1)
            assertThat(db.syncIntentDao().leer(venue, cash.id)!!.status).isEqualTo("ACKED")
        } finally { release.complete(Unit); first.await() }
    }

    @Test fun `F2 rechazo permanente queda cuarentena y sigue bloqueando CLOSE sin reintento automatico`() = runTest {
        openA(); val cash = pay(); closeA()
        coEvery { tableApi.syncIntents(venue, any()) } coAnswers {
            sent += secondArg<SyncIntentsRequest>()
            Response.success(SyncIntentsResponse(data = listOf(SyncIntentAck(cash.id, "REJECTED", errorCode = "ORDER_NOT_FOUND"))))
        }
        worker().doWork(); worker().doWork()
        assertThat(sent).hasSize(1)
        assertThat(db.syncIntentDao().leer(venue, cash.id)!!.status).isEqualTo("REJECTED")
        assertThat(repo.obligacionesDelCierreLocal(venue, aId!!).rows.map { it.owner }).contains("TABLE_CASH")
        coVerify(exactly = 0) { api.closeShift(any(), any(), any()) }
    }

    @Test fun `F2 mapping sin avance no manda ni agenda y conserva captura original`() = runTest {
        openA(); val cash = pay(); closeA()
        coEvery { api.openShift(venue, any()) } returns Response.error(503, okhttp3.ResponseBody.create(null, "sin red"))
        val beforeChain = blockedWork()
        worker().doWork()
        assertThat(db.syncIntentDao().leer(venue, cash.id)).isEqualTo(cash)
        assertThat(chainSize()).isEqualTo(beforeChain)
        assertThat(sent).isEmpty()
        coVerify(exactly = 0) { api.closeShift(any(), any(), any()) }
        every { storage.getVenueId() } returns null
        worker().doWork() // Sin venue vigente tampoco se emite ni se programa un bucle.
        assertThat(db.syncIntentDao().leer(venue, cash.id)).isEqualTo(cash)
        assertThat(chainSize()).isEqualTo(beforeChain)
        assertThat(sent).isEmpty()
    }
    @Test fun `F2 ACK duplicado o ajeno no consume siguiente fila ni crea hotloop`() = runTest {
        openA(); val first = pay(); val next = pay("20.00"); closeA()
        val alien = "c000000000000000000000009"
        outbox.enqueue("v2", "ADD_ITEMS", JsonObject(), id = alien)
        coEvery { tableApi.syncIntents(venue, any()) } coAnswers {
            val request = secondArg<SyncIntentsRequest>(); sent += request
            Response.success(SyncIntentsResponse(data = listOf(SyncIntentAck(alien, "ACKED"))))
        }
        worker().doWork()
        assertThat(sent).hasSize(1)
        assertThat(db.syncIntentDao().leer(venue, first.id)!!.status).isEqualTo("PENDING")
        assertThat(db.syncIntentDao().leer(venue, next.id)!!.status).isEqualTo("PENDING")
        assertThat(db.syncIntentDao().leer("v2", alien)!!.status).isEqualTo("PENDING")
        coEvery { tableApi.syncIntents(venue, any()) } coAnswers {
            val request = secondArg<SyncIntentsRequest>(); sent += request
            Response.success(SyncIntentsResponse(data = listOf(SyncIntentAck(first.id, "ACKED"),
                SyncIntentAck(first.id, "ACKED"), SyncIntentAck(alien, "ACKED"))))
        }
        worker().doWork()
        assertThat(sent).hasSize(2)
        assertThat(db.syncIntentDao().leer(venue, first.id)!!.status).isEqualTo("ACKED")
        assertThat(db.syncIntentDao().leer(venue, next.id)!!.status).isEqualTo("PENDING")
        assertThat(db.syncIntentDao().leer("v2", alien)!!.status).isEqualTo("PENDING")
        assertThat(db.localShiftSessionDao().leer(venue, aId!!)!!.closeStatus).isEqualTo("PENDING")
        coVerify(exactly = 0) { api.closeShift(any(), any(), any()) }
    }

}

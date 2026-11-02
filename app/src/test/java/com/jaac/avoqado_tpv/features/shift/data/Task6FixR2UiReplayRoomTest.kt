package com.jaac.avoqado_tpv.features.shift.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.data.workers.PaymentSyncWorker
import com.jaac.avoqado_tpv.core.util.PaymentQueueStateManager
import com.jaac.avoqado_tpv.features.payment.data.api.VentaGuardadaApi
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger
import com.jaac.avoqado_tpv.features.payment.data.repository.PaymentQueueRepositoryImpl
import com.jaac.avoqado_tpv.features.payment.data.repository.RefundQueueRepositoryImpl
import com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordPaymentUseCase
import com.jaac.avoqado_tpv.features.payment.domain.usecase.SubirVentaGuardadaUseCase
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftReplayGate
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import com.jaac.avoqado_tpv.features.tables.data.PendingRoundCart
import com.jaac.avoqado_tpv.features.tables.data.TableSession
import com.jaac.avoqado_tpv.features.tables.data.api.TablesApiService
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentAck
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentsRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentsResponse
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncIntentTypes
import com.jaac.avoqado_tpv.features.tables.data.sync.TableSyncCoordinator
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import retrofit2.Response
import java.io.Closeable
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** R18: only the UI StateFlow read is decorated; financial rows and UI writers stay real. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class Task6FixR2UiReplayRoomTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val storage = mockk<SecureStorage>(relaxed = true)
    private val api = mockk<TablesApiService>()
    private val venue = "venue-f3"
    private val localA = "c000000000000000000000011"
    private val remoteA = "c000000000000000000000012"
    private val remoteB = "c000000000000000000000013"
    private val openId = "c000000000000000000000014"
    private val addId = "c000000000000000000000015"
    private lateinit var db: AvoqadoDatabase
    private lateinit var repo: ShiftRepository
    private lateinit var gate: ShiftReplayGate
    private lateinit var outbox: SyncOutbox
    private lateinit var coordinator: TableSyncCoordinator
    private lateinit var session: TableSession
    private lateinit var cart: PendingRoundCart
    private lateinit var ui: UiOwner
    private val httpEntered = CompletableDeferred<Unit>()
    private var responseRelease: CompletableDeferred<Unit>? = null
    private val sent = CopyOnWriteArrayList<SyncIntentsRequest>()
    private val cartMutationOnUi = CopyOnWriteArrayList<Boolean>()
    private var ackStatus = "ACKED"

    @Before fun setup() {
        ui = UiOwner()
        Dispatchers.setMain(ui)
        db = Room.inMemoryDatabaseBuilder(context, AvoqadoDatabase::class.java).allowMainThreadQueries().build()
        repo = task5RoomShiftRepository(db, storage, mockk<ApiService>(), venue, "cashier", enabled = false)
        every { storage.getSerialNumber() } returns "SERIAL-F3"
        gate = ShiftReplayGate(db, storage)
        outbox = SyncOutbox(db.syncIntentDao(), api, storage, repo, gate)
        session = TableSession()
        cart = spyk(PendingRoundCart())
        every { cart.promote(any(), any()) } answers {
            cartMutationOnUi += ui.ownsThread()
            callOriginal()
        }
        coordinator = TableSyncCoordinator(outbox, session, cart)
        coEvery { api.syncIntents(venue, any()) } coAnswers {
            val request = secondArg<SyncIntentsRequest>()
            sent += request
            httpEntered.complete(Unit)
            responseRelease?.await()
            Response.success(SyncIntentsResponse(data = request.intents.map { intent ->
                SyncIntentAck(intent.id, ackStatus, result = JsonObject().apply {
                    addProperty("orderId", remoteA)
                    addProperty("version", if (intent.type == SyncIntentTypes.OPEN_TABLE) 3 else 99)
                    if (intent.type == SyncIntentTypes.OPEN_TABLE) addProperty("localOrderId", localA)
                })
            }))
        }
    }

    @After fun cleanup() {
        ui.callbackObserved.countDown()
        ui.gestureFinished.countDown()
        responseRelease?.complete(Unit)
        Dispatchers.resetMain()
        ui.close()
        db.close()
    }

    private suspend fun prepare(open: Boolean, add: Boolean = false) {
        withContext(ui) {
            if (open) session.open("table-A", localA)
            else session.start(TableSession.Active("table-A", orderId = remoteA, version = 3))
            cart.ensureOrder(if (open) localA else remoteA)
            cart.addSimple("product-A", "Articulo A", BigDecimal("10.00"))
            assertThat(session.current()).isEqualTo(session.active.value)
        }
        if (open) outbox.enqueue(venue, SyncIntentTypes.OPEN_TABLE, JsonObject().apply {
            addProperty("tableId", "table-A"); addProperty("localOrderId", localA)
        }, id = openId)
        if (!open || add) outbox.enqueue(venue, SyncIntentTypes.ADD_ITEMS, JsonObject().apply {
            addProperty("orderId", remoteA)
        }, id = addId)
    }

    private fun worker(): PaymentSyncWorker {
        val state = PaymentQueueStateManager()
        return TestListenableWorkerBuilder<PaymentSyncWorker>(context).setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: android.content.Context, workerClassName: String,
                params: WorkerParameters): ListenableWorker = PaymentSyncWorker(appContext, params,
                PaymentQueueRepositoryImpl(db.pendingPaymentDao()), mockk<RecordPaymentUseCase>(), state,
                RefundQueueRepositoryImpl(db.pendingRefundDao(), mockk(), state),
                PaymentAttemptLedger(db.paymentAttemptDao(), mockk(relaxed = true)),
                SubirVentaGuardadaUseCase(mockk<VentaGuardadaApi>(), db.pendingPaymentDao(), context, gate),
                repo, gate, coordinator)
        }).build()
    }

    /** Observe the exact same flow through both private accesses and the public `active` view. */
    private fun decorateRealUiRead() {
        val field = TableSession::class.java.getDeclaredField("_active").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val actual = field.get(session) as MutableStateFlow<TableSession.Active?>
        val intercepted = AtomicBoolean()
        field.set(session, object : MutableStateFlow<TableSession.Active?> by actual {
            override var value: TableSession.Active?
                get() {
                    val captured = actual.value
                    if (ui.armed.get() && !ui.ownsThread() && intercepted.compareAndSet(false, true)) {
                        ui.callbackObserved.countDown()
                        check(ui.gestureFinished.await(10, TimeUnit.SECONDS)) { "UI gesture did not complete" }
                    }
                    return captured
                }
                set(value) { actual.value = value }
        })
    }

    private fun enterB() {
        check(ui.ownsThread())
        session.start(TableSession.Active("table-B", orderId = remoteB, version = 7, total = BigDecimal("42.00")))
        cart.ensureOrder(remoteB)
        cart.addSimple("product-B", "Articulo B", BigDecimal("42.00"))
        assertThat(session.current()).isEqualTo(session.active.value)
        assertThat(session.active.value!!.orderId).isEqualTo(remoteB)
    }

    /** The UI gesture is already executing when ACK arrives: the old background callback reads A;
     * the fixed callback queues behind this gesture. No sleeps or scheduler luck are involved. */
    private suspend fun CoroutineScope.interleave(open: Boolean, action: () -> Unit) {
        prepare(open)
        decorateRealUiRead()
        responseRelease = CompletableDeferred()
        val ready = CompletableDeferred<Unit>()
        val gesture = async(ui) {
            ready.complete(Unit)
            check(ui.callbackObserved.await(10, TimeUnit.SECONDS)) { "Neither background read nor Main callback arrived" }
            // The callback signal comes AFTER SyncOutbox's real durable CAS, before any UI mutation.
            val id = if (open) openId else addId
            db.openHelper.readableDatabase.query("SELECT status FROM sync_intents WHERE id = ?", arrayOf(id)).use {
                check(it.moveToFirst()); assertThat(it.getString(0)).isEqualTo("ACKED")
            }
            action()
            ui.gestureFinished.countDown()
        }
        ready.await()
        val job = async(Dispatchers.Default) { worker().doWork() }
        try {
            httpEntered.await()
            ui.armed.set(true)
            responseRelease!!.complete(Unit)
            gesture.await()
            job.await()
        } finally {
            ui.armed.set(false)
            ui.callbackObserved.countDown(); ui.gestureFinished.countDown(); responseRelease!!.complete(Unit)
        }
        assertThat(sent).hasSize(1)
        assertThat(db.syncIntentDao().leer(venue, if (open) openId else addId)!!.status).isEqualTo("ACKED")
    }

    @Test fun `F3 Worker promocion A no reemplaza B ni sus renglones al llegar ACK`() = runBlocking<Unit> {
        interleave(open = true) { enterB() }
        withContext(ui) {
            assertThat(session.current()!!.orderId).isEqualTo(remoteB)
            assertThat(session.active.value!!.orderId).isEqualTo(remoteB)
            assertThat(session.active.value!!.version).isEqualTo(7)
            cart.ensureOrder(remoteB)
            assertThat(cart.lines.value.map { it.productId }).containsExactly("product-B")
        }
        assertThat(cartMutationOnUi).containsExactly(true)
    }

    @Test fun `F3 Worker promocion A no revive sesion tras clear real de UI`() = runBlocking<Unit> {
        interleave(open = true) { session.clear(); cart.clear(); assertThat(session.active.value).isNull() }
        withContext(ui) {
            assertThat(session.current()).isNull()
            assertThat(session.active.value).isNull()
            assertThat(cart.lines.value).isEmpty()
        }
        assertThat(cartMutationOnUi).containsExactly(true)
    }

    @Test fun `F3 Worker version de A nunca se escribe en B tras lectura retenida`() = runBlocking<Unit> {
        interleave(open = false) { enterB() }
        withContext(ui) {
            assertThat(session.current()!!.orderId).isEqualTo(remoteB)
            assertThat(session.current()!!.version).isEqualTo(7)
            assertThat(session.active.value!!.version).isEqualTo(7)
            assertThat(session.active.value!!.total).isEqualTo(BigDecimal("42.00"))
            cart.ensureOrder(remoteB)
            assertThat(cart.lines.value.map { it.productId }).containsExactly("product-B")
        }
    }

    @Test fun `F3 carrito B y reentrada ensureOrderB sobreviven con reconciliacion completa en Main`() = runBlocking<Unit> {
        interleave(open = true) { enterB() }
        withContext(ui) {
            // Re-enter B through the same public writers used by the screen, with its unsent line intact.
            session.start(TableSession.Active("table-B", orderId = remoteB, version = 7))
            cart.ensureOrder(remoteB)
            assertThat(cart.lines.value.map { it.productId }).containsExactly("product-B")
            assertThat(session.current()).isEqualTo(session.active.value)
        }
        assertThat(cartMutationOnUi).containsExactly(true)
    }

    @Test fun `F3 Worker A propia conserva carrito promocion version FIFO y ACK durable`() = runBlocking<Unit> {
        prepare(open = true, add = true)
        withContext(Dispatchers.Default) { worker().doWork() }
        withContext(ui) {
            assertThat(session.active.value!!.orderId).isEqualTo(remoteA)
            assertThat(session.current()).isEqualTo(session.active.value)
            assertThat(session.active.value!!.version).isEqualTo(99)
            assertThat(session.active.value!!.isProvisional).isFalse()
            cart.ensureOrder(remoteA)
            assertThat(cart.lines.value.map { it.productId }).containsExactly("product-A")
        }
        assertThat(db.syncIntentDao().allForVenue(venue).map { it.status }).containsExactly("ACKED", "ACKED")
        assertThat(sent).hasSize(1)
        assertThat(sent.single().intents.map { it.id }).containsExactly(openId, addId).inOrder()
        assertThat(cartMutationOnUi).containsExactly(true)
    }

    @Test fun `F3 Foreground Main usa misma reconciliacion y conserva API publica y carrito`() = runBlocking<Unit> {
        prepare(open = true, add = true)
        withContext(ui) { coordinator.replay(venue) }
        withContext(ui) {
            assertThat(session.current()).isEqualTo(session.active.value)
            assertThat(session.active.value!!.orderId).isEqualTo(remoteA)
            assertThat(session.active.value!!.version).isEqualTo(99)
            cart.ensureOrder(remoteA)
            assertThat(cart.lines.value.map { it.productId }).containsExactly("product-A")
            coordinator.onAck(SyncIntentTypes.ADD_ITEMS, null, remoteA, version = 100)
            assertThat(session.active.value!!.version).isEqualTo(100)
        }
        assertThat(sent).hasSize(1)
        assertThat(cartMutationOnUi).containsExactly(true)
    }

    @Test fun `F3 cancelar Worker esperando Main propaga y no reenvia ACK ya durable`() = runBlocking<Unit> {
        prepare(open = true)
        responseRelease = CompletableDeferred()
        val ready = CompletableDeferred<Unit>()
        val releaseUi = CountDownLatch(1)
        val gesture = async(ui) { ready.complete(Unit); check(releaseUi.await(10, TimeUnit.SECONDS)) }
        ready.await()
        val job = async(Dispatchers.Default) { worker().doWork() }
        try {
            httpEntered.await(); ui.armed.set(true); responseRelease!!.complete(Unit)
            val reached = select<String> { ui.callbackQueued.onAwait { "queued" }; job.onAwait { "completed" } }
            assertThat(reached).isEqualTo("queued")
            assertThat(db.syncIntentDao().leer(venue, openId)!!.status).isEqualTo("ACKED")
            job.cancel()
            assertThat(job.isCancelled).isTrue()
            releaseUi.countDown(); gesture.await(); job.join()
        } finally {
            ui.armed.set(false); releaseUi.countDown(); responseRelease!!.complete(Unit); gesture.await()
        }
        withContext(Dispatchers.Default) { worker().doWork() }
        assertThat(sent).hasSize(1)
        withContext(ui) {
            assertThat(session.active.value!!.orderId).isEqualTo(localA)
            assertThat(session.active.value!!.isProvisional).isTrue()
            assertThat(session.current()).isEqualTo(session.active.value)
            cart.ensureOrder(localA)
            assertThat(cart.lines.value.map { it.productId }).containsExactly("product-A")
        }
        assertThat(cartMutationOnUi).isEmpty()
    }

    @Test fun `F3 RETRY y REJECTED conservan sesion carrito y responsabilidad sin nueva emision`() = runBlocking<Unit> {
        prepare(open = true)
        ackStatus = "RETRY"
        withContext(Dispatchers.Default) { worker().doWork() }
        assertThat(db.syncIntentDao().leer(venue, openId)!!.status).isEqualTo("PENDING")
        ackStatus = "REJECTED"
        withContext(Dispatchers.Default) { worker().doWork() }
        assertThat(db.syncIntentDao().leer(venue, openId)!!.status).isEqualTo("REJECTED")
        withContext(Dispatchers.Default) { worker().doWork() }
        withContext(ui) {
            assertThat(session.active.value!!.orderId).isEqualTo(localA)
            assertThat(session.current()).isEqualTo(session.active.value)
            cart.ensureOrder(localA)
            assertThat(cart.lines.value.map { it.productId }).containsExactly("product-A")
        }
        assertThat(sent).hasSize(2)
        assertThat(sent[0].intents.single().id).isEqualTo(sent[1].intents.single().id)
        assertThat(cartMutationOnUi).isEmpty()
    }

    /** A real single UI owner, with a dispatch signal instead of sleeps or stress scheduling. */
    private class UiOwner : MainCoroutineDispatcher(), Closeable {
        @Volatile private var thread: Thread? = null
        val armed = AtomicBoolean()
        val callbackObserved = CountDownLatch(1)
        val callbackQueued = CompletableDeferred<Unit>()
        val gestureFinished = CountDownLatch(1)
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "Task6-F3-UI").also { thread = it }
        }
        fun ownsThread() = Thread.currentThread() === thread
        override val immediate: MainCoroutineDispatcher get() = this
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = !ownsThread()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (armed.get() && !ownsThread()) { callbackObserved.countDown(); callbackQueued.complete(Unit) }
            executor.execute(block)
        }
        override fun close() { executor.shutdown(); check(executor.awaitTermination(10, TimeUnit.SECONDS)) }
    }
}

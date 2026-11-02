package com.jaac.avoqado_tpv.features.shift.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.features.payment.domain.repository.RefundQueueRepository
import com.jaac.avoqado_tpv.features.shift.data.dto.OpenShiftRequest
import com.jaac.avoqado_tpv.features.shift.data.dto.CloseShiftRequest
import com.jaac.avoqado_tpv.features.shift.data.dto.ShiftDto
import com.jaac.avoqado_tpv.features.shift.data.dto.CurrentShiftResponse
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import com.jaac.avoqado_tpv.features.shift.domain.CashReconciliationAction
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class LocalShiftSessionRoomTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val storage = mockk<SecureStorage>(relaxed = true)
    private val api = mockk<ApiService>()
    private val refunds = mockk<RefundQueueRepository>(relaxed = true)
    private lateinit var db: AvoqadoDatabase
    private lateinit var repo: ShiftRepository
    private var duringQuery: (String) -> Unit = {}
    private var actor = "staff-a"
    private var venue = "v1"
    private var token = "token-a"
    private var mode = true
    private var allowed = true

    @Before fun setup() {
        every { storage.getVenueId() } answers { venue }
        every { storage.getStaffId() } answers { actor }
        every { storage.getToken() } answers { token }
        every { storage.getKioskVenueId() } returns null
        every { storage.isShiftSystemEnabled() } answers { mode }
        every { storage.contains("enable_shifts") } returns true
        every { storage.getBoolean("enable_shifts", true) } answers { mode }
        every { storage.getString("permissions_cache", null) } answers {
            "$venue|$actor\n" + if (allowed) "tpv-shifts:create,tpv-shifts:close,shifts:read,payments:create,payments:refund" else "shifts:read"
        }
        db = Room.databaseBuilder(context, AvoqadoDatabase::class.java, "task4-local-shifts")
            .allowMainThreadQueries().setQueryCallback({ sql, _ -> duringQuery(sql) }, Executor { it.run() }).build()
        repo = repository()
    }

    @After fun cleanup() { db.close(); context.deleteDatabase("task4-local-shifts") }
    private fun repository() = ShiftRepository(api, db, storage, db.cachedShiftDao(), refunds, appContext = androidx.test.core.app.ApplicationProvider.getApplicationContext())
    private suspend fun open(cash: String = "500.00") = repo.guardarAperturaLocal("v1", "staff-a", BigDecimal(cash)).value()

    @Test fun `apertura durable es una sola llave y peticion antes de cualquier HTTP`() = runTest {
        val a = open()
        val duplicate = open("600.00")
        assertThat(duplicate).isEqualTo(a)
        assertThat(a.localSessionId).matches("c[a-z0-9]{24}")
        assertThat(a.openingOperationId).isEqualTo(a.localSessionId)
        assertThat(a.serverShiftId).isNull()
        val request = Gson().fromJson(a.openingRequestJson, OpenShiftRequest::class.java)
        assertThat(request.operationId).isEqualTo(a.localSessionId)
        assertThat(request.localId).isEqualTo(a.localSessionId)
        assertThat(request.openedAt).isEqualTo(a.originalOpenedAt)
        assertThat(request.startingCash).isEqualTo(500.0)
        assertThat(db.localShiftSessionDao().contar("v1")).isEqualTo(1)
        coVerify(exactly = 0) { api.openShift(any(), any()) }
    }

    @Test fun `aperturas concurrentes tienen una sola proyeccion OPEN y la misma peticion original`() = runTest {
        val rows = (1..8).map { async(Dispatchers.Default) { open("$it.00") } }.awaitAll()
        assertThat(rows.map { it.localSessionId }.toSet()).hasSize(1)
        assertThat(rows.map { it.openingRequestJson }.toSet()).hasSize(1)
        assertThat(db.localShiftSessionDao().contar("v1")).isEqualTo(1)
    }

    @Test fun `reloj corregido hacia atras conserva B cerrada como ultima sin falsear hora ni peticion`() = runTest {
        val a = open("500.00")
        repo.guardarCierreLocal("v1", a.localSessionId, CashReconciliationAction.COUNTED, BigDecimal("615.25")).value()
        val future = System.currentTimeMillis() + 86_400_000L
        // Representa el timestamp histórico previo a corregir el reloj; nunca cambia el reloj del host.
        db.openHelper.writableDatabase.execSQL("UPDATE local_shift_sessions SET created_at = ? WHERE local_session_id = ?", arrayOf(future, a.localSessionId))
        val before = System.currentTimeMillis()
        val b = open("600.00")
        val after = System.currentTimeMillis()
        val request = Gson().fromJson(b.openingRequestJson, OpenShiftRequest::class.java)
        val openedAt = java.time.Instant.parse(b.originalOpenedAt).toEpochMilli()
        assertThat(openedAt).isAtLeast(before)
        assertThat(openedAt).isAtMost(after)
        assertThat(b.updatedAt).isAtLeast(before)
        assertThat(b.updatedAt).isAtMost(after)
        assertThat(request.openedAt).isEqualTo(b.originalOpenedAt)
        assertThat(request.localId).isEqualTo(b.localSessionId)
        assertThat(request.operationId).isEqualTo(b.localSessionId)
        assertThat(b.openingOperationId).isEqualTo(b.localSessionId)
        val closedB = repo.guardarCierreLocal("v1", b.localSessionId, CashReconciliationAction.COUNTED, BigDecimal("777.00")).value()
        val current = repo.proyeccionLocalDeCaja("v1")!!
        assertThat(current.localSessionId).isEqualTo(b.localSessionId)
        assertThat(current.countedCash).isEqualTo("777.00")
        assertThat(current).isEqualTo(closedB)
        assertThat(b.createdAt).isGreaterThan(future)
        assertThat(db.localShiftSessionDao().leer("v1", a.localSessionId)?.countedCash).isEqualTo("615.25")
        assertThat(db.localShiftSessionDao().leer("v1", b.localSessionId)?.openingRequestJson).isEqualTo(b.openingRequestJson)
    }

    @Test fun `cierre con PENDING guarda conteo y CLOSED sin POST y conserva original en doble toque`() = runTest {
        val a = open()
        db.pendingPaymentDao().insert(payment("cash-pending").copy(localShiftSessionId = a.localSessionId, shiftMembership = "MANAGED"))
        val closed = repo.guardarCierreLocal("v1", a.localSessionId, CashReconciliationAction.COUNTED, BigDecimal("615.25")).value()
        assertThat(closed.localStatus).isEqualTo("CLOSED")
        assertThat(closed.openSlot).isNull()
        assertThat(closed.closeOperationId).matches("c[a-z0-9]{24}")
        assertThat(closed.closeOperationId).isNotEqualTo(a.openingOperationId)
        assertThat(closed.countedCash).isEqualTo("615.25")
        val draft = com.google.gson.JsonParser.parseString(closed.closeRequestJson).asJsonObject
        assertThat(draft.has("shiftId")).isFalse()
        assertThat(draft.get("countedCash").asString).isEqualTo("615.25")
        assertThat(draft.get("operationId").asString).isEqualTo(closed.closeOperationId)
        assertThat(repo.guardarCierreLocal("v1", a.localSessionId, CashReconciliationAction.COUNTED, BigDecimal("900.00")).value()).isEqualTo(closed)
        coVerify(exactly = 0) { api.closeShift(any(), any(), any()) }
    }

    @Test fun `SKIPPED y legacy no inventan cero y conteo invalido no cambia proyeccion`() = runTest {
        for (action in listOf(null, CashReconciliationAction.SKIPPED)) {
            val a = open()
            assertThat(repo.guardarCierreLocal("v1", a.localSessionId, CashReconciliationAction.COUNTED, BigDecimal("1.001"))).isInstanceOf(Result.Error::class.java)
            val closed = repo.guardarCierreLocal("v1", a.localSessionId, action).value()
            assertThat(closed.countedCash).isNull()
        }
    }

    @Test fun `fallo o cancelacion durante INSERT revierte apertura y proyeccion juntas`() = runTest {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER task4_write_failure BEFORE INSERT ON local_shift_sessions BEGIN SELECT RAISE(ABORT, 'write failed'); END")
        assertThat(repo.guardarAperturaLocal("v1", "staff-a", BigDecimal("500.00"))).isInstanceOf(Result.Error::class.java)
        sql.execSQL("DROP TRIGGER task4_write_failure")
        assertThat(db.localShiftSessionDao().contar("v1")).isEqualTo(0)

        val gesture = async(start = CoroutineStart.LAZY) { repo.guardarAperturaLocal("v1", "staff-a", BigDecimal("500.00")) }
        duringQuery = { query -> if (query.startsWith("INSERT") && query.contains("local_shift_sessions")) gesture.cancel() }
        gesture.start()
        assertThat(runCatching { gesture.await() }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        duringQuery = {}
        assertThat(db.localShiftSessionDao().contar("v1")).isEqualTo(0)
    }

    @Test fun `PIN venue token o permiso cambia durante UPDATE de cierre y revierte gesto completo`() = runTest {
        val a = open()
        for (change in listOf("PIN", "venue", "token", "permission")) {
            actor = "staff-a"; venue = "v1"; token = "token-a"; allowed = true
            duringQuery = { sql -> if (sql.startsWith("UPDATE") && sql.contains("local_shift_sessions")) {
                when (change) { "PIN" -> actor = "staff-b"; "venue" -> venue = "v2"; "token" -> token = "token-b"; else -> allowed = false }
            } }
            assertThat(repo.guardarCierreLocal("v1", a.localSessionId, CashReconciliationAction.COUNTED, BigDecimal("123.00"))).isInstanceOf(Result.Error::class.java)
            duringQuery = {}
            assertThat(db.localShiftSessionDao().leer("v1", a.localSessionId)).isEqualTo(a)
        }
    }

    @Test fun `reabrir DB logout y cache borrada no pierden apertura ni conteo ni reabren A`() = runTest {
        val a = open()
        val closed = repo.guardarCierreLocal("v1", a.localSessionId, CashReconciliationAction.COUNTED, BigDecimal("777.00")).value()
        db.cachedShiftDao().clearCache("v1")
        db.close()
        db = Room.databaseBuilder(context, AvoqadoDatabase::class.java, "task4-local-shifts").allowMainThreadQueries().build()
        repo = repository()
        actor = ""; token = ""
        assertThat(db.localShiftSessionDao().leer("v1", a.localSessionId)).isEqualTo(closed)
        actor = "staff-b"; token = "token-b"
        assertThat(repo.proyeccionLocalDeCaja("v1")?.countedCash).isEqualTo("777.00")
        assertThat(repo.proyeccionLocalDeCaja("v1")?.localStatus).isEqualTo("CLOSED")
        assertThat(repo.proyeccionLocalDeCaja("v2")).isNull()
    }

    @Test fun `SecureStorage y SQL fallan cerrado sin borrar evidencia`() = runTest {
        val a = open()
        every { storage.getToken() } throws IllegalStateException("storage unavailable")
        assertThat(repo.guardarCierreLocal("v1", a.localSessionId)).isInstanceOf(Result.Error::class.java)
        assertThat(db.localShiftSessionDao().leer("v1", a.localSessionId)).isEqualTo(a)
        every { storage.getToken() } answers { token }
        db.openHelper.writableDatabase.execSQL("DROP TABLE payment_attempts")
        assertThat(repo.guardarCierreLocal("v1", a.localSessionId)).isInstanceOf(Result.Error::class.java)
        assertThat(db.localShiftSessionDao().leer("v1", a.localSessionId)).isEqualTo(a)
    }

    @Test fun `GET null B y cache vieja no pierden A ni reabren su cierre`() = runTest {
        val a = open()
        val closed = repo.guardarCierreLocal("v1", a.localSessionId, CashReconciliationAction.COUNTED, BigDecimal("888.00")).value()
        coEvery { api.getCurrentShift("v1") } returns retrofit2.Response.success(CurrentShiftResponse(null))
        repo.getCurrentShift("v1")
        assertThat(repo.registrarCajaRemotaConocida("v1", remote("B")).value()).isEqualTo(closed)
        assertThat(repo.proyeccionLocalDeCaja("v1")).isEqualTo(closed)
        val summary = repo.resumenLocalDeCaja("v1", a.localSessionId)!!
        assertThat(summary.countedCash).isEqualTo(BigDecimal("888.00"))
        assertThat(summary.expectedCash).isNull()
        assertThat(summary.salesTotal).isNull()
    }

    @Test fun `ADOPTED conserva fondos y conteo sin remapear cierre y adopcion explicita admite un gesto nuevo`() = runTest {
        val a = open("500.00")
        val closed = repo.guardarCierreLocal("v1", a.localSessionId, CashReconciliationAction.COUNTED, BigDecimal("615.25")).value()
        val future = System.currentTimeMillis() + 86_400_000L
        db.openHelper.writableDatabase.execSQL("UPDATE local_shift_sessions SET created_at = ? WHERE local_session_id = ?", arrayOf(future, a.localSessionId))
        val adopted = repo.registrarResultadoAperturaLocal("v1", a.localSessionId,
            remote("foreign").copy(localId = "c000000000000000000000000", openingDisposition = "ADOPTED", fondoAplicado = "200.00")).value()
        assertThat(adopted.localSessionId).isEqualTo(a.localSessionId)
        assertThat(adopted.openingOperationId).isEqualTo(a.openingOperationId)
        assertThat(adopted.closeOperationId).isEqualTo(closed.closeOperationId)
        assertThat(adopted.closeRequestJson).isEqualTo(closed.closeRequestJson)
        assertThat(adopted.countedCash).isEqualTo("615.25")
        assertThat(adopted.startingCash).isEqualTo("500.00")
        assertThat(adopted.remoteStartingCash).isEqualTo("200.00")
        assertThat(adopted.adoptionNotice).contains("500.00")
        assertThat(adopted.adoptionNotice).contains("200.00")
        assertThat(adopted.closeStatus).isEqualTo("REVIEW")
        val real = repo.adoptarCajaRemotaParaNuevosGestos("v1", a.localSessionId).value()
        assertThat(real.localSessionId).isNotEqualTo(a.localSessionId)
        assertThat(real.openingKind).isEqualTo("REMOTE")
        assertThat(real.openingOperationId).isNull()
        assertThat(real.serverShiftId).isEqualTo("foreign")
        assertThat(real.createdAt).isGreaterThan(future)
        assertThat(real.originalOpenedAt).isEqualTo(remote("foreign").startTime)
        assertThat(repo.guardarCierreLocal("v1", real.localSessionId, CashReconciliationAction.SKIPPED).value().closeStatus).isEqualTo("PENDING")
        assertThat(db.localShiftSessionDao().leer("v1", a.localSessionId)?.countedCash).isEqualTo("615.25")
    }

    @Test fun `confirmacion exige la llave propia y repeticion exacta conserva evidencia original`() = runTest {
        val a = open()
        val response = remote("own").copy(localId = a.localSessionId, openingDisposition = "OWN", fondoAplicado = "500.00")
        for (foreignId in listOf(null, "c000000000000000000000000")) {
            assertThat(repo.registrarResultadoAperturaLocal("v1", a.localSessionId, response.copy(localId = foreignId))).isInstanceOf(Result.Error::class.java)
            assertThat(db.localShiftSessionDao().leer("v1", a.localSessionId)).isEqualTo(a)
        }
        val confirmed = repo.registrarResultadoAperturaLocal("v1", a.localSessionId, response).value()
        assertThat(repo.registrarResultadoAperturaLocal("v1", a.localSessionId, response).value()).isEqualTo(confirmed)
        assertThat(repo.registrarResultadoAperturaLocal("v1", a.localSessionId, response.copy(fondoAplicado = "700.00"))).isInstanceOf(Result.Error::class.java)
        assertThat(db.localShiftSessionDao().leer("v1", a.localSessionId)).isEqualTo(confirmed)
    }

    @Test fun `caja remota conocida no finge apertura propia`() = runTest {
        val a = open()
        repo.guardarCierreLocal("v1", a.localSessionId).value()
        val future = System.currentTimeMillis() + 86_400_000L
        db.openHelper.writableDatabase.execSQL("UPDATE local_shift_sessions SET created_at = ?, opening_status = 'CONFIRMED', close_status = 'CONFIRMED' WHERE local_session_id = ?", arrayOf(future, a.localSessionId))
        val row = repo.registrarCajaRemotaConocida("v1", remote("remote")).value()
        assertThat(row.createdAt).isGreaterThan(future)
        assertThat(row.originalOpenedAt).isEqualTo(remote("remote").startTime)
        assertThat(row.openingKind).isEqualTo("REMOTE")
        assertThat(row.openingOperationId).isNull()
        assertThat(row.openingRequestJson).isNull()
        assertThat(row.serverShiftId).isEqualTo("remote")
        assertThat(repo.guardarCierreLocal("v1", row.localSessionId).value().closeOperationId).isNotNull()
    }

    @Test fun `reserva anterior a SDK gana la carrera y el cierre no se guarda`() = runTest {
        val a = open()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reservation = async(Dispatchers.Default) {
            repo.guardarNuevaObligacionDeCaja("v1") { belonging ->
                val id = db.paymentAttemptDao().insert(attempt("reserve-first").copy(shiftMembership = belonging.kind,
                    localShiftSessionId = belonging.localSessionId))
                entered.complete(Unit); release.await(); id
            }
        }
        entered.await()
        val closing = async(Dispatchers.Default) { repo.guardarCierreLocal("v1", a.localSessionId) }
        release.complete(Unit)
        assertThat(reservation.await()).isGreaterThan(0L)
        assertThat(closing.await()).isInstanceOf(Result.Error::class.java)
        assertThat(db.localShiftSessionDao().leer("v1", a.localSessionId)?.localStatus).isEqualTo("OPEN")
    }

    @Test fun `cierre gana y no entra una nueva reserva pero callback ya aceptado conserva su dinero`() = runTest {
        val a = open()
        // El callback repite una obligación ya registrada; no inventa un pago sin dueño después del cierre.
        db.paymentAttemptDao().insert(attempt("accepted-before-close").copy(state = "REGISTRADO", tipCents = 500,
            shiftMembership = "MANAGED", localShiftSessionId = a.localSessionId))
        val entered = CompletableDeferred<Unit>()
        val attempted = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        duringQuery = { query -> if (query.startsWith("UPDATE") && query.contains("local_shift_sessions")) {
            entered.complete(Unit); check(release.await(30, TimeUnit.SECONDS))
        } }
        val closing = async(Dispatchers.Default) { repo.guardarCierreLocal("v1", a.localSessionId) }
        entered.await()
        val reservation = async(Dispatchers.Default) {
            attempted.complete(Unit)
            runCatching { repo.guardarNuevaObligacionDeCaja("v1") { db.paymentAttemptDao().insert(attempt("too-late")) } }
        }
        attempted.await(); release.countDown()
        assertThat(closing.await()).isInstanceOf(Result.Success::class.java)
        duringQuery = {}
        assertThat(reservation.await().isFailure).isTrue()
        assertThat(db.paymentAttemptDao().getById("too-late")).isNull()
        // Callback ya aceptado: NO readmite ni vuelve a autorizar. El dueño existente conserva la referencia original.
        db.pendingPaymentDao().insert(payment("accepted-before-close").copy(idempotencyKey = "accepted-before-close", shiftMembership = "MANAGED", localShiftSessionId = a.localSessionId))
        assertThat(db.pendingPaymentDao().findByReference("accepted-before-close", "v1")?.localShiftSessionId).isEqualTo(a.localSessionId)
    }

    @Test fun `Mesas y Caja comparten rollback cuando PIN cambia durante INSERT`() = runTest {
        val a = open()
        duringQuery = { query -> if (query.startsWith("INSERT") && query.contains("sync_intents")) actor = "new-pin" }
        assertThat(runCatching {
            repo.guardarNuevaObligacionDeCaja("v1") { belonging ->
                db.syncIntentDao().insertWithSeq(com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentEntity("table-cash", "v1", 0,
                    "PAY_CASH", "{\"total\":150.25}", shiftMembership = belonging.kind, localShiftSessionId = belonging.localSessionId))
            }
        }.isFailure).isTrue()
        duringQuery = {}
        assertThat(db.syncIntentDao().allForVenue("v1")).isEmpty()
        assertThat(db.localShiftSessionDao().leer("v1", a.localSessionId)).isEqualTo(a)
    }

    @Test fun `OFF explicito conserva bypass y ausencia o fallo de modo no admite por omision`() = runTest {
        mode = false
        repo.guardarNuevaObligacionDeCaja("v1") { belonging ->
            assertThat(belonging.kind).isEqualTo("NO_SHIFT_SYSTEM")
            db.pendingPaymentDao().insert(payment("off").copy(shiftMembership = belonging.kind))
        }
        assertThat(db.localShiftSessionDao().contar("v1")).isEqualTo(0)
        every { storage.contains("enable_shifts") } returns false
        assertThat(runCatching { repo.guardarNuevaObligacionDeCaja("v1") { error("must not run") } }.isFailure).isTrue()
        every { storage.contains("enable_shifts") } returns true
        every { storage.getBoolean("enable_shifts", true) } throws IllegalStateException("read failed")
        assertThat(runCatching { repo.guardarNuevaObligacionDeCaja("v1") { error("must not run") } }.isFailure).isTrue()
        assertThat(db.pendingPaymentDao().findByReference("off", "v1")?.shiftMembership).isEqualTo("NO_SHIFT_SYSTEM")
    }

    @Test fun `devolucion sin OPEN conserva via UNKNOWN sin heredar la caja del pago viejo`() = runTest {
        repo.guardarNuevaObligacionDeCaja("v1", isRefund = true) { belonging ->
            assertThat(belonging.kind).isEqualTo("UNKNOWN")
            assertThat(belonging.localSessionId).isNull()
            db.paymentAttemptDao().insert(attempt("refund-no-shift").copy(kind = "REFUND", shiftMembership = belonging.kind))
        }
        assertThat(db.paymentAttemptDao().getById("refund-no-shift")?.localShiftSessionId).isNull()
        assertThat(db.localShiftSessionDao().contar("v1")).isEqualTo(0)
    }

    private fun attempt(id: String) = PaymentAttemptEntity(attemptId = id, venueId = "v1", processor = "BLUMON", state = "PREPARANDO",
        amountCents = 15025L, tipCents = 0, recordingRoute = "FAST", paymentContextJson = "{}", createdAt = 100L, updatedAt = 100L)

    private fun remote(id: String) = ShiftDto(id = id, venueId = "v1", staffId = "remote-actor", startTime = "2026-10-28T12:00:00Z",
        endTime = null, status = "OPEN", startingCash = "200.00", endingCash = null, totalSales = "0.00", totalTips = "0.00", totalOrders = 0,
        totalCashPayments = "0.00", totalCardPayments = "0.00", totalVoucherPayments = "0.00", totalOtherPayments = "0.00", totalProductsSold = 0,
        staff = null, cashDrawerSessionId = "drawer-$id")

    private fun payment(reference: String) = PendingPaymentEntity(referenceNumber = reference, venueId = "v1", staffId = "staff-a",
        amount = "150.25", tip = "5.00", rating = null, merchantAccountId = "", blumonSerialNumber = "", maskedPan = null,
        cardBrand = null, entryMode = "CASH", isInternational = false, authorizationNumber = null, createdAt = 100L)

    private fun <T> Result<T>.value(): T = when (this) {
        is Result.Success -> data
        is Result.Error -> throw AssertionError(exception)
        else -> error("Unexpected result")
    }
}

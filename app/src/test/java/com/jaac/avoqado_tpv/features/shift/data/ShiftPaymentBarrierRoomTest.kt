package com.jaac.avoqado_tpv.features.shift.data

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.MainDispatcherRule
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.util.ConnectionEventManager
import com.jaac.avoqado_tpv.core.util.ConnectivityObserver
import com.jaac.avoqado_tpv.core.util.NetworkStatus
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus
import com.jaac.avoqado_tpv.features.shift.presentation.ShiftState
import com.jaac.avoqado_tpv.features.shift.presentation.ShiftViewModel
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.features.payment.domain.repository.RefundQueueRepository
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import java.math.BigDecimal
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class ShiftPaymentBarrierRoomTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    @get:Rule val main = MainDispatcherRule()
    @Volatile private var duringQuery: (String) -> Unit = {}
    @Volatile private var sessionVenue: String? = "v1"
    @Volatile private var kioskVenue: String? = null
    @Volatile private var actor: String? = "reviewer"
    @Volatile private var sessionToken: String? = "session-a"
    private val viewModels = mutableListOf<ShiftViewModel>()
    private val db = Room.inMemoryDatabaseBuilder(context, AvoqadoDatabase::class.java).allowMainThreadQueries()
        .setQueryCallback({ sql, _ -> duringQuery(sql) }, Executor { it.run() }).build()
    private val dao = db.pendingPaymentDao()
    private val api = mockk<ApiService>()
    private val storage = mockk<SecureStorage>(relaxed = true)
    private val refunds = mockk<RefundQueueRepository>(relaxed = true)
    private val repository = ShiftRepository(api, db, storage, db.cachedShiftDao(), refunds, appContext = androidx.test.core.app.ApplicationProvider.getApplicationContext())

    init {
        coEvery { refunds.blockingForVenue(any()) } returns emptyList()
        every { storage.getVenueId() } answers { sessionVenue }
        every { storage.getKioskVenueId() } answers { kioskVenue }
        every { storage.getStaffId() } answers { actor }
        every { storage.getToken() } answers { sessionToken }
        every { storage.isShiftSystemEnabled() } returns true
        every { storage.contains("enable_shifts") } returns true
        every { storage.getBoolean("enable_shifts", true) } returns true
        every { storage.getString("permissions_cache", null) } answers { "$sessionVenue|$actor\ntpv-shifts:close,shifts:read,payments:create,payments:refund" }
        kotlinx.coroutines.runBlocking { db.cachedShiftDao().cacheShift(com.jaac.avoqado_tpv.core.data.local.entities.CachedShiftEntity(
            "c000000000000000000000032", "v1", "OPEN", "original", "", "2026-10-28T10:00:00Z", "0", 0, 0, 1)) }
        coEvery { api.closeShift(any(), any(), any()) } returns Response.error(500, "server unavailable".toResponseBody())
    }

    @After fun close() {
        viewModels.forEach { it.viewModelScope.cancel() }
        db.close()
    }

    @Test fun `PENDING aunque reconocido impide el POST de cierre`() = runTest {
        dao.insert(payment("pending").copy(acknowledged = true))
        repository.closeShift("v1", "c000000000000000000000032")
        repository.sincronizarCierresLocales("v1")
        coVerify(exactly = 0) { api.closeShift(any(), any(), any()) }
    }

    @Test fun `SYNCING de tarjeta legacy y otro turno impide el POST`() = runTest {
        dao.insert(payment("card").copy(syncStatus = "SYNCING", entryMode = "CHIP", shiftId = "old-shift"))
        repository.closeShift("v1", "c000000000000000000000032")
        repository.sincronizarCierresLocales("v1")
        coVerify(exactly = 0) { api.closeShift(any(), any(), any()) }
    }

    @Test fun `FAILED sin reconocer impide el POST`() = runTest {
        dao.insert(payment("failed").copy(syncStatus = "FAILED"))
        repository.closeShift("v1", "c000000000000000000000032")
        repository.sincronizarCierresLocales("v1")
        coVerify(exactly = 0) { api.closeShift(any(), any(), any()) }
    }

    @Test fun `SUCCESS con diferencia sin reconocer impide el POST`() = runTest {
        dao.insert(payment("difference").copy(syncStatus = "SUCCESS", requiereRevision = true))
        repository.closeShift("v1", "c000000000000000000000032")
        repository.sincronizarCierresLocales("v1")
        coVerify(exactly = 0) { api.closeShift(any(), any(), any()) }
    }

    @Test fun `una fila de otro negocio no bloquea y una revisada libera el POST`() = runTest {
        dao.insert(payment("other").copy(venueId = "v2"))
        dao.insert(payment("reviewed").copy(syncStatus = "FAILED", acknowledged = true))
        dao.insert(payment("reviewed-success").copy(syncStatus = "SUCCESS", requiereRevision = true, acknowledged = true))
        repository.closeShift("v1", "c000000000000000000000032")
        repository.sincronizarCierresLocales("v1")
        coVerify(exactly = 1) { api.closeShift("v1", "c000000000000000000000032", any()) }
    }

    @Test fun `reintentar una FAILED reconocida exige nueva revision si vuelve con diferencia`() = runTest {
        for (permanent in listOf(false, true)) {
            val reference = "reset-$permanent"
            dao.insert(payment(reference).copy(syncStatus = "FAILED", acknowledged = true, permanent = permanent,
                acknowledgedBy = "old-reviewer", acknowledgedAt = 1L))
            if (permanent) dao.resetAllFailedIncludingPermanent() else dao.resetAllFailed()
            val row = dao.findByReference(reference, "v1")!!
            assertThat(row.acknowledged).isFalse()
            assertThat(row.acknowledgedBy).isNull()
            assertThat(row.acknowledgedAt).isNull()
            dao.markClaimed(10, "worker-$reference", 200L, 100L)
            dao.guardarOrdenDeVentaGuardada(row.id, "worker-$reference", "order-$reference", "folio", true)
            dao.markSynced(row.id, "worker-$reference")
            repository.closeShift("v1", "c000000000000000000000032")
        repository.sincronizarCierresLocales("v1")
            coVerify(exactly = 0) { api.closeShift(any(), any(), any()) }
            dao.delete(dao.findByReference(reference, "v1")!!)
        }
    }

    @Test fun `SQL cuenta cada desenlace una vez por negocio y las paginas no truncan el total`() = runTest {
        val expected = mutableListOf<Long>()
        for (status in listOf("PENDING", "SYNCING", "FAILED", "SUCCESS")) {
            for (ack in listOf(false, true)) for (review in listOf(false, true)) {
                val id = dao.insert(payment("$status-$ack-$review").copy(syncStatus = status, acknowledged = ack, requiereRevision = review))
                val blocks = status in listOf("PENDING", "SYNCING") || ((status == "FAILED" || review) && !ack)
                if (blocks) expected += id
            }
        }
        repeat(51) { expected += dao.insert(payment("extra-$it").copy(entryMode = "CHIP", shiftId = "other-shift")) }
        dao.insert(payment("other-venue").copy(venueId = "v2"))
        assertThat(dao.contarBloqueanCierre("v1")).isEqualTo(expected.size)
        val pages = mutableListOf<Long>()
        var offset = 0
        while (offset < expected.size) {
            val page = repository.pagosQueBloqueanCierre("v1", offset)
            assertThat(page.total).isEqualTo(expected.size)
            assertThat(page.pagos.size).isAtMost(20)
            pages += page.pagos.map { it.id }
            offset += 20
        }
        assertThat(pages).containsExactlyElementsIn(expected).inOrder()
        assertThat(dao.contarBloqueanCierre("v2")).isEqualTo(1)
        assertThat(repository.pagosQueBloqueanCierre("v1", Int.MAX_VALUE).offset).isEqualTo(((expected.size - 1) / 20) * 20)
        assertThat(repository.pagosQueBloqueanCierre("v1", -20).offset).isEqualTo(0)
    }

    @Test fun `Revisado CAS no reconoce pendientes ni otra sucursal ni una fila ya reconocida`() = runTest {
        for (status in listOf("PENDING", "SYNCING", "SUCCESS")) {
            val id = dao.insert(payment("ineligible-$status").copy(syncStatus = status))
            assertThat(dao.reconocer(id, "v1", "reviewer", 300L)).isEqualTo(0)
        }
        val id = dao.insert(payment("review").copy(syncStatus = "FAILED", requiereRevision = true))
        assertThat(dao.reconocer(id, "v2", "reviewer", 300L)).isEqualTo(0)
        for (missing in listOf(null, "", "  ")) assertThat(dao.reconocer(id, "v1", missing, 300L)).isEqualTo(0)
        assertThat(dao.reconocer(id, "v1", "reviewer", 300L)).isEqualTo(1)
        assertThat(dao.reconocer(id, "v1", "second-reviewer", 400L)).isEqualTo(0)
        assertThat(dao.findByReference("review", "v1")!!.acknowledgedBy).isEqualTo("reviewer")
    }

    @Test fun `Revisado conserva dinero JSON llave ticket turno y vendedor y permite cerrar`() = runTest {
        for (status in listOf("FAILED", "SUCCESS")) {
            val original = payment("preserved-$status").copy(syncStatus = status, requiereRevision = true, shiftId = "original-shift",
                idempotencyKey = "original-key-$status", orderRequestJson = "{\"items\":[{\"name\":\"Taza\",\"quantity\":1}],\"total\":150.25}",
                ticketJson = "{\"name\":\"Taza\",\"amount\":150.25}")
            val id = dao.insert(original)
            assertThat(repository.reconocerPago(id, "v1", "reviewer", "session-a") { true }).isEqualTo(1)
            val read = dao.findByReference(original.referenceNumber, "v1")!!
            assertThat(read.staffId).isEqualTo("seller")
            assertThat(read.acknowledgedBy).isEqualTo("reviewer")
            assertThat(read.acknowledgedAt).isNotNull()
            assertThat(read.copy(id = 0, acknowledged = false, acknowledgedBy = null, acknowledgedAt = null)).isEqualTo(original)
        }
        repository.closeShift("v1", "c000000000000000000000032")
        repository.sincronizarCierresLocales("v1")
        coVerify(exactly = 1) { api.closeShift("v1", "c000000000000000000000032", any()) }
    }

    @Test fun `snapshot FAILED que ya volvio a PENDING no se puede reconocer`() = runTest {
        val id = dao.insert(payment("race").copy(syncStatus = "FAILED"))
        assertThat(repository.pagosQueBloqueanCierre("v1", 0).pagos.single().syncStatus).isEqualTo("FAILED")
        dao.resetAllFailed()
        assertThat(repository.reconocerPago(id, "v1", "reviewer", "session-a") { true }).isEqualTo(0)
        assertThat(dao.contarBloqueanCierre("v1")).isEqualTo(1)
    }

    @Test fun `cambio de actor venue o sesion DURANTE UPDATE revierte toda la revision`() = runTest {
        for (change in listOf("actor", "venue", "session")) {
            actor = "reviewer"; sessionVenue = "v1"; sessionToken = "session-a"
            val original = payment("identity-$change").copy(syncStatus = "FAILED", shiftId = "old", ticketJson = "{\"total\":150.25}",
                orderRequestJson = "{\"externalId\":\"unchanged\"}", idempotencyKey = "key-$change")
            val id = dao.insert(original)
            duringQuery = { sql -> if (sql.contains("SET acknowledged = 1")) {
                when (change) { "actor" -> actor = "new-reviewer"; "venue" -> sessionVenue = "v2"; else -> sessionToken = "session-b" }
            } }
            val result = runCatching { repository.reconocerPago(id, "v1", "reviewer", "session-a") { true } }
            assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
            assertThat(dao.findByReference(original.referenceNumber, "v1")!!.copy(id = 0)).isEqualTo(original)
        }
    }

    @Test fun `sin actor actual ni pantalla vigente no se escribe una revision`() = runTest {
        val id = dao.insert(payment("missing-actor").copy(syncStatus = "FAILED"))
        actor = null
        assertThat(runCatching { repository.reconocerPago(id, "v1", "reviewer", "session-a") { true } }.isFailure).isTrue()
        actor = "reviewer"
        assertThat(runCatching { repository.reconocerPago(id, "v1", "reviewer", "session-a") { false } }.isFailure).isTrue()
        assertThat(dao.findByReference("missing-actor", "v1")!!.acknowledged).isFalse()
    }

    @Test fun `cambio de turno y salida DURANTE UPDATE revierten y no publican error tardio en Caja`() = runTest {
        for (change in listOf("shift", "back")) {
            val reference = "screen-$change"
            val original = payment(reference).copy(syncStatus = "FAILED", shiftId = "original-shift", idempotencyKey = "key-$change",
                orderRequestJson = "{\"total\":155.25}", ticketJson = "{\"total\":155.25,\"seller\":\"seller\"}")
            val id = dao.insert(original)
            var activeShift = shift("old-$change")
            val spy = spyk(repository)
            coEvery { spy.getCurrentShift(any()) } answers { Result.Success(activeShift) }
            coEvery { spy.getShiftHistory(any(), any()) } returns Result.Success(emptyList())
            val vm = viewModel(spy)
            vm.state.first { it is ShiftState.ShiftActive }
            vm.closeShift()
            vm.state.first { it is ShiftState.CloseBlockedByPayments }
            val entered = CompletableDeferred<Unit>()
            val once = AtomicBoolean(false)
            duringQuery = { sql -> if (sql.contains("SET acknowledged = 1") && once.compareAndSet(false, true)) {
                if (change == "shift") activeShift = shift("new-$change")
                if (change == "back") vm.onShiftScreenLeft() else vm.loadCurrentShift()
                entered.complete(Unit)
            } }
            vm.acknowledgePayment(id)
            entered.await()
            db.withTransaction { }
            if (change == "back") vm.onShiftScreenEntered()
            val after = vm.state.first { it is ShiftState.ShiftActive }
            assertThat((after as ShiftState.ShiftActive).shift.id).isEqualTo(activeShift.id)
            assertThat(dao.findByReference(reference, "v1")!!.copy(id = 0)).isEqualTo(original)
            duringQuery = {}
            dao.delete(dao.findByReference(reference, "v1")!!)
            vm.viewModelScope.cancel()
        }
    }

    @Test fun `fallo SQL real al leer Room bloquea cierre`() = runTest {
        db.openHelper.writableDatabase.execSQL("DROP TABLE pending_payments")
        assertThat(repository.closeShift("v1", "c000000000000000000000032")).isInstanceOf(Result.Error::class.java)
        coVerify(exactly = 0) { api.closeShift(any(), any(), any()) }
    }

    @Test fun `cancelacion real durante Room revierte cierre y se propaga sin POST`() = runTest {
        lateinit var action: kotlinx.coroutines.Deferred<Result<Shift>>
        val once = AtomicBoolean(false)
        action = async(kotlinx.coroutines.Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            repository.closeShift("v1", "c000000000000000000000032")
        }
        duringQuery = { sql -> if (sql.contains("SELECT * FROM local_shift_sessions") && once.compareAndSet(false, true)) action.cancel() }
        action.start()
        assertThat(runCatching { action.await() }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        duringQuery = {}
        assertThat(db.localShiftSessionDao().contar("v1")).isEqualTo(0)
        coVerify(exactly = 0) { api.closeShift(any(), any(), any()) }
    }

    @Test fun `Kiosk conserva sucursal autenticada y nunca autoriza cierre por otra sesion`() = runTest {
        sessionVenue = "v2"; kioskVenue = "v1"
        assertThat(repository.closeShift("v1", "c000000000000000000000032")).isInstanceOf(Result.Error::class.java)
        coVerify(exactly = 0) { api.closeShift(any(), any(), any()) }
        sessionVenue = "v1"
        dao.insert(payment("outside-kiosk").copy(venueId = "v2"))
        repository.closeShift("v1", "c000000000000000000000032")
        repository.sincronizarCierresLocales("v1")
        coVerify(exactly = 1) { api.closeShift("v1", "c000000000000000000000032", any()) }
        dao.insert(payment("inside-kiosk"))
        repository.sincronizarCierresLocales("v1")
        coVerify(exactly = 1) { api.closeShift("v1", "c000000000000000000000032", any()) }
    }

    @Test fun `limpieza conserva SUCCESS sin revisar y nueva diferencia invalida un reconocimiento previo`() = runTest {
        val id = dao.insert(payment("new-difference").copy(syncStatus = "SYNCING", claimToken = "claim", acknowledged = true,
            acknowledgedBy = "old-reviewer", acknowledgedAt = 20L))
        dao.guardarOrdenDeVentaGuardada(id, "claim", "order", "folio", true)
        dao.markSynced(id, "claim")
        dao.deleteOldSyncedPayments(1_000L)
        val read = dao.findByReference("new-difference", "v1")!!
        assertThat(read.acknowledged).isFalse()
        assertThat(read.acknowledgedBy).isNull()
        assertThat(read.acknowledgedAt).isNull()
        assertThat(dao.contarBloqueanCierre("v1")).isEqualTo(1)
    }

    @Test fun `reclamar PENDING legacy limpia el reconocimiento antiguo antes del nuevo desenlace`() = runTest {
        dao.insert(payment("legacy-claim").copy(acknowledged = true, acknowledgedBy = "old-reviewer", acknowledgedAt = 1L))
        val claimed = dao.claimBatch(20, "new-claim", 200L, 50L).single()
        assertThat(claimed.acknowledged).isFalse()
        assertThat(claimed.acknowledgedBy).isNull()
        assertThat(claimed.acknowledgedAt).isNull()
        dao.release(claimed.id, "new-claim", PendingPaymentEntity.MAX_RETRY_ATTEMPTS, "rechazada otra vez")
        assertThat(dao.contarBloqueanCierre("v1")).isEqualTo(1)
    }

    @Test fun `cambio de sesion durante COUNT impide enviar cierre aun con cero pendientes`() = runTest {
        duringQuery = { sql -> if (sql.contains("COUNT(*) FROM") && sql.contains("pending_payments")) sessionToken = "session-b" }
        assertThat(repository.closeShift("v1", "c000000000000000000000032")).isInstanceOf(Result.Error::class.java)
        coVerify(exactly = 0) { api.closeShift(any(), any(), any()) }
    }

    @Test fun `sesion cambiada durante segunda COUNT no reemplaza T2 con error de T1`() = runTest { rechazoTardio("session") }

    @Test fun `PENDING nuevo en segunda COUNT no reemplaza T2 con error de T1`() = runTest { rechazoTardio("pending") }

    @Test fun `fallo SQL en segunda COUNT no reemplaza T2 con error de T1`() = runTest { rechazoTardio("sql") }

    @Test fun `salir y reentrar durante segunda COUNT conserva T2 ante rechazo tardio`() = runTest { rechazoTardio("exit") }

    private suspend fun rechazoTardio(mode: String) {
        // Sólo la caché visual es un mock: ambas COUNT y el rechazo vienen del repositorio y SQLite reales.
        val cache = mockk<com.jaac.avoqado_tpv.core.data.local.dao.CachedShiftDao>(relaxed = true)
        val real = ShiftRepository(api, db, storage, cache, refunds, appContext = androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val observed = spyk(real)
        coEvery { cache.getCachedShift("v1") } returns com.jaac.avoqado_tpv.core.data.local.entities.CachedShiftEntity(
            "T1", "v1", "OPEN", "original", "", "2026-10-28T10:00:00Z", "0", 0, 0, 1)
        var active = shift("T1")
        coEvery { observed.getCurrentShift(any()) } answers { Result.Success(active) }
        coEvery { observed.getShiftHistory(any(), any()) } returns Result.Success(emptyList())
        val returned = CompletableDeferred<Result<Shift>>()
        coEvery { observed.closeShift(any(), any(), any(), any()) } coAnswers {
            real.closeShift(firstArg(), secondArg(), thirdArg(), arg(3)).also { returned.complete(it) }
        }
        if (mode != "session") dao.insert(payment("late-obligation").copy(syncStatus = "SUCCESS"))
        val vm = viewModel(observed, cache)
        vm.state.first { it is ShiftState.ShiftActive }
        val count = AtomicInteger(0)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        duringQuery = { sql ->
            if (sql.contains("SELECT COUNT(*) FROM") && sql.contains("pending_payments") && count.incrementAndGet() == 2) {
                entered.complete(Unit)
                check(release.await(30, TimeUnit.SECONDS)) { "El test no liberó la segunda COUNT" }
                if (mode == "sql") db.openHelper.writableDatabase.execSQL("DROP TABLE pending_payments")
                else if (mode != "session") db.openHelper.writableDatabase.execSQL("UPDATE pending_payments SET sync_status = 'PENDING'")
            }
        }
        val before = vm.viewModelScope.coroutineContext[Job]!!.children.toSet()
        vm.closeShift()
        val close = vm.viewModelScope.coroutineContext[Job]!!.children.single { it !in before }
        entered.await()
        try {
            if (mode == "session") sessionToken = "session-b"
            active = shift("T2")
            if (mode == "exit") {
                vm.onShiftScreenLeft()
                vm.onShiftScreenEntered()
            } else vm.loadCurrentShift()
            vm.state.first { it is ShiftState.ShiftActive && it.shift.id == "T2" }
        } finally { release.countDown() }
        close.join() // Espera la publicación de VM, no sólo que SQLite termine.
        val result = returned.await()
        if (mode == "pending" || mode == "exit") {
            // B2 conserva el conteo local aunque el pago exija esperar antes del POST.
            assertThat(result).isInstanceOf(Result.Success::class.java)
            assertThat(db.localShiftSessionDao().ultima("v1")!!.localStatus).isEqualTo("CLOSED")
        } else {
            assertThat(result).isInstanceOf(Result.Error::class.java)
            assertThat((result as Result.Error).exception).isInstanceOf(ApiException.ValidationError::class.java)
        }
        coVerify(exactly = 0) { api.closeShift(any(), any(), any()) }
        assertThat(vm.state.value).isInstanceOf(ShiftState.ShiftActive::class.java)
        assertThat((vm.state.value as ShiftState.ShiftActive).shift.id).isEqualTo("T2")
        vm.viewModelScope.cancel()
    }

    private fun viewModel(repo: ShiftRepository,
        cache: com.jaac.avoqado_tpv.core.data.local.dao.CachedShiftDao = db.cachedShiftDao()): ShiftViewModel {
        // These UI race fixtures already replace current with T1/T2. Keep the added bootstrap read
        // in that same legacy fixture; it must not queue behind the deliberately held SQL transaction.
        // COUNT/UPDATE and the public close operation remain real, with the original assertions.
        coEvery { repo.leerCajaLocal(any()) } returns null
        coEvery { repo.getCachedOpenShift(any()) } returns null
        val connections = mockk<ConnectionEventManager>(relaxed = true)
        every { connections.connectionRestoredEvents } returns MutableSharedFlow()
        val connectivity = mockk<ConnectivityObserver>(relaxed = true)
        every { connectivity.observe() } returns MutableSharedFlow<NetworkStatus>()
        val permissions = mockk<PermissionsRepository>()
        coEvery { permissions.hasPermission(any()) } returns true
        return ShiftViewModel(repo, storage, connections, cache, connectivity, permissions, refunds).also { viewModels += it }
    }

    private fun shift(id: String) = Shift(id = id, venueId = "v1", staffId = "seller", staffName = "Vendedora",
        startTime = "2026-10-23T12:00:00Z", endTime = null, status = ShiftStatus.OPEN, startingCash = BigDecimal("500.00"),
        endingCash = null, totalSales = BigDecimal("150.25"), totalTips = BigDecimal.ZERO, totalOrders = 1,
        totalCashPayments = BigDecimal("150.25"), totalCardPayments = BigDecimal.ZERO, totalVoucherPayments = BigDecimal.ZERO,
        totalOtherPayments = BigDecimal.ZERO, totalProductsSold = 1, durationMinutes = 60)

    private fun payment(reference: String) = PendingPaymentEntity(
        referenceNumber = reference, venueId = "v1", staffId = "seller", amount = "150.25", tip = "5.00", rating = null,
        merchantAccountId = "merchant", blumonSerialNumber = "serial", maskedPan = null, cardBrand = null, entryMode = "CASH",
        isInternational = false, authorizationNumber = null, createdAt = 100L,
    )
}

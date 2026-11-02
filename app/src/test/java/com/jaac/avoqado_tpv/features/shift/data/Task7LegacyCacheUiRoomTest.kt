package com.jaac.avoqado_tpv.features.shift.data

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.MainDispatcherRule
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.core.util.*
import com.jaac.avoqado_tpv.features.checkout.data.*
import com.jaac.avoqado_tpv.features.checkout.domain.model.*
import com.jaac.avoqado_tpv.features.payment.domain.repository.RefundQueueRepository
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import com.jaac.avoqado_tpv.features.shift.domain.CashReconciliationAction
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import com.jaac.avoqado_tpv.features.shift.presentation.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class Task7LegacyCacheUiRoomTest {
    @get:Rule val main = MainDispatcherRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val storage = mockk<SecureStorage>(relaxed = true)
    private val api = mockk<ApiService>()
    private val refunds = mockk<RefundQueueRepository>(relaxed = true)
    private lateinit var db: AvoqadoDatabase
    private lateinit var repo: ShiftRepository
    private val vms = mutableListOf<ShiftViewModel>()
    @Before fun setup() {
        every { storage.getVenueId() } returns "venue"
        every { storage.getStaffId() } returns "authorA"
        every { storage.getToken() } returns "tokenA"
        every { storage.getKioskVenueId() } returns null
        every { storage.isShiftSystemEnabled() } returns true
        every { storage.contains("enable_shifts") } returns true
        every { storage.getBoolean("enable_shifts", true) } returns true
        every { storage.getString("permissions_cache", null) } returns "venue|authorA\ntpv-shifts:create,tpv-shifts:close,shifts:read,payments:create"
        reopen()
    }
    private fun reopen() {
        db = Room.databaseBuilder(context, AvoqadoDatabase::class.java, "task7-legacy-ui").allowMainThreadQueries().build()
        repo = ShiftRepository(api, db, storage, db.cachedShiftDao(), refunds, context)
    }
    private fun vm(): ShiftViewModel {
        val events = mockk<ConnectionEventManager>(relaxed = true)
        every { events.connectionRestoredEvents } returns MutableSharedFlow()
        val connectivity = mockk<ConnectivityObserver>(relaxed = true)
        every { connectivity.observe() } returns MutableSharedFlow()
        val permissions = mockk<PermissionsRepository>(relaxed = true)
        coEvery { permissions.hasPermission(any()) } returns true
        return ShiftViewModel(repo, storage, events, db.cachedShiftDao(), connectivity, permissions, refunds).also(vms::add)
    }
    @After fun cleanup() { vms.forEach { it.viewModelScope.cancel() }; db.close(); context.deleteDatabase("task7-legacy-ui"); unmockkAll() }
    private suspend fun cash(key: String) {
        val json = """{"externalId":"$key","staffId":"authorA","items":[{"externalId":"$key:0","quantity":1,"unitPrice":1000}],"total":1000}"""
        VentaLocalRepository(db.pendingPaymentDao(), shiftRepository = repo).guardar(VentaLocal(key, "venue", "authorA", null,
            123L, 1000, json, listOf(RenglonDeTicket("$key:0", "Producto", 1, 1000, 1000, emptyList(), null))),
            "BLUMON", "", "SERIAL", reclamada = false).getOrThrow()
    }

    private suspend fun legacyCache(): String {
        // A real NO_SHIFT cash operation predates re-enabling shifts; it conservatively blocks this close POST.
        every { storage.getBoolean("enable_shifts", true) } returns false
        every { storage.isShiftSystemEnabled() } returns false
        cash(CuidV1.next())
        every { storage.getBoolean("enable_shifts", true) } returns true
        every { storage.isShiftSystemEnabled() } returns true
        val id = CuidV1.next()
        db.cachedShiftDao().cacheShift(com.jaac.avoqado_tpv.core.data.local.entities.CachedShiftEntity(
            id, "venue", "OPEN", "authorA", "Ana", "2026-10-28T10:00:00Z", "123.45", 3, 10, System.currentTimeMillis()))
        assertThat(db.localShiftSessionDao().contar("venue")).isEqualTo(0)
        return id
    }

    @Test fun `legacy cached OPEN is presented before a retained current HTTP`() = runBlocking<Unit> {
        val id = legacyCache()
        val entered = CompletableDeferred<Unit>()
        val held = CompletableDeferred<retrofit2.Response<com.jaac.avoqado_tpv.features.shift.data.dto.CurrentShiftResponse>>()
        coEvery { api.getCurrentShift("venue") } coAnswers { entered.complete(Unit); held.await() }
        val a = vm()
        withTimeout(10_000) { entered.await() }
        assertThat((a.state.value as? ShiftState.ShiftActive)?.shift?.id).isEqualTo(id)
        assertThat((a.state.value as ShiftState.ShiftActive).shift.financialTotalsKnown).isFalse()
        assertThat(db.cachedShiftDao().getCachedShift("venue")!!.toDomainPartial().financialTotalsKnown).isNull()
        held.complete(retrofit2.Response.success(com.jaac.avoqado_tpv.features.shift.data.dto.CurrentShiftResponse(null)))
        withTimeout(10_000) { a.state.first { it is ShiftState.NoActiveShift } }
        assertThat(db.cachedShiftDao().getCachedShift("venue")).isNull()
    }

    @Test fun `legacy cached close keeps null fund counted cash pending barrier and recreation`() = runBlocking<Unit> {
        val id = legacyCache()
        val entered = CompletableDeferred<Unit>()
        val held = CompletableDeferred<retrofit2.Response<com.jaac.avoqado_tpv.features.shift.data.dto.CurrentShiftResponse>>()
        coEvery { api.getCurrentShift("venue") } coAnswers { entered.complete(Unit); held.await() }
        val a = vm()
        withTimeout(10_000) { entered.await() }
        assertThat((a.state.value as? ShiftState.ShiftActive)?.shift?.id).isEqualTo(id)
        a.closeShift(CashReconciliationAction.COUNTED, BigDecimal("72.00"))
        withTimeout(10_000) { a.state.first { it is ShiftState.ShiftClosed } }
        val saved = repo.proyeccionLocalDeCaja("venue")!!
        assertThat(saved.serverShiftId).isEqualTo(id)
        assertThat(saved.startingCash).isNull()
        assertThat(saved.countedCash).isEqualTo("72.00")
        assertThat(saved.closeStatus).isEqualTo("PENDING")
        assertThat(repo.obligacionesDelCierreLocal("venue", saved.localSessionId).total).isEqualTo(1)
        coVerify(exactly = 0) { api.closeShift(any(), any(), any()) }
        held.complete(retrofit2.Response.success(com.jaac.avoqado_tpv.features.shift.data.dto.CurrentShiftResponse(null)))
        a.viewModelScope.cancel(); a.viewModelScope.coroutineContext[Job]?.join()
        db.close(); reopen()
        val snapshot = repo.leerCajaLocal("venue")!!
        assertThat(snapshot.row.closeRequestJson).isEqualTo(saved.closeRequestJson)
        assertThat(snapshot.summary.startingCash).isNull()
        val paper = CajaLocalPresentation(snapshot.row, snapshot.summary).paper()
        assertThat(paper).contains("Fondo no disponible")
        assertThat(paper).contains("Conteo capturado: $72.00")
        assertThat(paper).doesNotContain("$0.00")
        val recreated = vm()
        withTimeout(10_000) { recreated.state.first { it is ShiftState.ShiftClosed } }
        assertThat(recreated.localCaja.value?.summary?.startingCash).isNull()
        assertThat(recreated.localCaja.value?.summary?.countedCash).isEqualTo(BigDecimal("72.00"))
    }
}

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
class Task7CajaRoomUiTest {
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
        db = Room.databaseBuilder(context, AvoqadoDatabase::class.java, "task7-ui").allowMainThreadQueries().build()
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
    @After fun cleanup() { vms.forEach { it.viewModelScope.cancel() }; db.close(); context.deleteDatabase("task7-ui"); unmockkAll() }
    private suspend fun cash(key: String) {
        val json = """{"externalId":"$key","staffId":"authorA","items":[{"externalId":"$key:0","quantity":1,"unitPrice":1000}],"total":1000}"""
        VentaLocalRepository(db.pendingPaymentDao(), shiftRepository = repo).guardar(VentaLocal(key, "venue", "authorA", null,
            123L, 1000, json, listOf(RenglonDeTicket("$key:0", "Producto", 1, 1000, 1000, emptyList(), null))),
            "BLUMON", "", "SERIAL", reclamada = false).getOrThrow()
    }

    @Test fun `pending cash before Cerrar persists exact count before history HTTP and recreation`() = runBlocking<Unit> {
        val opened = (repo.guardarAperturaLocal("venue", "authorA", BigDecimal("50.00")) as Result.Success).data
        cash("c000000000000000000000011")
        val historyHeld = CompletableDeferred<Nothing>()
        coEvery { api.getShiftHistory(any(), any(), any()) } coAnswers { historyHeld.await() }
        val a = vm()
        withTimeout(10_000) { a.state.first { it is ShiftState.ShiftActive } }
        a.closeShift(CashReconciliationAction.COUNTED, BigDecimal("72.00"))
        withTimeout(10_000) { a.state.first { it is ShiftState.ShiftClosed } }
        val saved = repo.proyeccionLocalDeCaja("venue")!!
        assertThat(saved.localSessionId).isEqualTo(opened.localSessionId)
        assertThat(saved.countedCash).isEqualTo("72.00")
        assertThat(saved.cashReconciliationAction).isEqualTo("COUNTED")
        assertThat(saved.closeStatus).isEqualTo("PENDING")
        assertThat(repo.obligacionesDelCierreLocal("venue", saved.localSessionId).total).isEqualTo(1)
        assertThat(repo.puedeEnviarCierreLocal("venue", saved.localSessionId)).isFalse()
        coVerify(exactly = 0) { api.closeShift(any(), any(), any()) }
        a.viewModelScope.cancel(); a.viewModelScope.coroutineContext[Job]?.join()
        db.close(); reopen()
        val snapshot = repo.leerCajaLocal("venue")!!
        assertThat(snapshot.row.closeRequestJson).isEqualTo(saved.closeRequestJson)
        assertThat(snapshot.summary.countedCash).isEqualTo(BigDecimal("72.00"))
        assertThat(CajaLocalPresentation(snapshot.row, snapshot.summary).paper()).contains("Conteo capturado: $72.00")
        assertThat(snapshot.shift.financialTotalsKnown).isFalse()
    }

    @Test fun `scoped review keeps exact total twenty page and only recognizable cash`() = runBlocking<Unit> {
        val opened = (repo.guardarAperturaLocal("venue", "authorA", BigDecimal("50.00")) as Result.Success).data
        repeat(25) { cash(CuidV1.next()) }
        val first = repo.obligacionesDelCierreLocal("venue", opened.localSessionId, 0)
        assertThat(first.total).isEqualTo(25)
        assertThat(first.rows).hasSize(20)
        val second = repo.obligacionesDelCierreLocal("venue", opened.localSessionId, 20)
        assertThat(second.total).isEqualTo(25)
        assertThat(second.rows).hasSize(5)
        assertThat(repo.efectivoRevisableDelCierre("venue", first)).isEmpty()
        val id = first.rows.first().ownerId.toLong()
        // Fixture de rechazo durable, sin acreditar ni inventar dinero remoto.
        db.openHelper.writableDatabase.execSQL("UPDATE pending_payments SET sync_status = 'FAILED', requiere_revision = 1 WHERE id = ?", arrayOf(id))
        assertThat(repo.efectivoRevisableDelCierre("venue", first)).containsExactly(id)
        assertThat(repo.efectivoRevisableDelCierre("otherVenue", first)).isEmpty()
        assertThat(repo.reconocerPagoEnCierreLocal(opened.localSessionId, id, "venue", "authorA", "tokenA") { true }).isEqualTo(1)
        assertThat(repo.obligacionesDelCierreLocal("venue", opened.localSessionId).total).isEqualTo(24)
    }

    @Test fun `fresh Caja VM reads crypto owner from Room and only GETs its K`() = runBlocking<Unit> {
        val opened = (repo.guardarAperturaLocal("venue", "authorA", BigDecimal("50.00")) as Result.Success).data
        val key = CuidV1.next()
        val request = """{"amount":1000,"tip":0,"staffId":"authorA","deviceSerialNumber":"SERIAL","idempotencyKey":"$key"}"""
        db.localCryptoOperationDao().insertar(com.jaac.avoqado_tpv.core.data.local.entity.LocalCryptoOperationEntity(
            key, "venue", "authorA", request, shiftMembership = "MANAGED", localShiftSessionId = opened.localSessionId,
            emissionStatus = "UNCERTAIN", financialStatus = "PENDING", financialRecorded = false, createdAt = 1, updatedAt = 1))
        val result = com.jaac.avoqado_tpv.features.shift.data.dto.CryptoOperationResultDto(paymentId = key,
            operation = com.jaac.avoqado_tpv.features.shift.data.dto.CryptoOperationDto(key, "UNCERTAIN", "PENDING", false))
        coEvery { api.getCryptoOperation("venue", key) } returns retrofit2.Response.success(
            com.jaac.avoqado_tpv.features.shift.data.dto.CryptoOperationResponse(true, result))
        val a = vm()
        withTimeout(10_000) { a.state.first { it is ShiftState.ShiftActive } }
        a.reviewLocalCaja(opened.localSessionId)
        withTimeout(10_000) { a.localReview.first { it?.page?.rows?.any { row -> row.ownerId == key } == true } }
        a.queryLocalCrypto(key)
        withTimeout(10_000) { a.localReview.first { it?.error?.contains("no está confirmado") == true } }
        val durable = db.localCryptoOperationDao().leer("venue", key)!!
        assertThat(durable.requestJson).isEqualTo(request)
        assertThat(durable.idempotencyKey).isEqualTo(key)
        assertThat(durable.financialRecorded).isFalse()
        coVerify(exactly = 1) { api.getCryptoOperation("venue", key) }
        coVerify(exactly = 0) { api.initiateDurableCryptoPayment(any(), any()) }
        coVerify(exactly = 0) { api.initiateCryptoPayment(any(), any()) }
        assertThat(repo.obligacionesDelCierreLocal("venue", opened.localSessionId).total).isEqualTo(1)
    }
}

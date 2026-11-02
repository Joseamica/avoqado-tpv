package com.jaac.avoqado_tpv.features.shift.presentation

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.MainDispatcherRule
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.dao.CachedShiftDao
import com.jaac.avoqado_tpv.core.data.local.entity.LocalShiftSessionEntity
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.core.util.ConnectionEventManager
import com.jaac.avoqado_tpv.core.util.ConnectivityObserver
import com.jaac.avoqado_tpv.core.util.NetworkStatus
import com.jaac.avoqado_tpv.features.payment.domain.repository.RefundQueueRepository
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import com.jaac.avoqado_tpv.features.shift.data.repository.PaginaPagosDelCierre
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import com.jaac.avoqado_tpv.features.shift.domain.*
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class Task7LocalShiftViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repo = mockk<ShiftRepository>(relaxed = true)
    private val storage = mockk<SecureStorage>(relaxed = true)
    private val events = mockk<ConnectionEventManager>(relaxed = true)
    private val cache = mockk<CachedShiftDao>(relaxed = true)
    private val connectivity = mockk<ConnectivityObserver>(relaxed = true)
    private val permissions = mockk<PermissionsRepository>(relaxed = true)
    private val refunds = mockk<RefundQueueRepository>(relaxed = true)
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 10)
    private val network = MutableSharedFlow<NetworkStatus>(extraBufferCapacity = 10)
    private val vms = mutableListOf<ShiftViewModel>()
    private val row = LocalShiftSessionEntity("clocal00000000000000000001", "venue", "LOCAL", openingStatus = "PENDING",
        originalOpenedAt = "2026-10-28T10:00:00Z", startingCash = "50.00", openedByStaffId = "staff",
        localStatus = "OPEN", createdAt = 1, updatedAt = 1)
    private val shift = Shift(row.localSessionId, "venue", "staff", "", row.originalOpenedAt, null, ShiftStatus.OPEN,
        BigDecimal("50.00"), null, BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, 0, null, localShiftSessionId = row.localSessionId, financialTotalsKnown = false)

    @Before fun setup() {
        every { storage.getVenueId() } returns "venue"
        every { storage.getStaffId() } returns "staff"
        every { storage.getToken() } returns "token"
        every { storage.isShiftSystemEnabled() } returns true
        every { repo.shiftChanges } returns changes
        every { events.connectionRestoredEvents } returns MutableSharedFlow()
        every { connectivity.observe() } returns network
        coEvery { permissions.hasPermission(any()) } returns true
        coEvery { repo.leerCajaLocal("venue") } returns com.jaac.avoqado_tpv.features.shift.data.repository.CajaLocalSnapshot(
            shift, row, LocalShiftSummary(BigDecimal("50.00"), null, "OPEN", "PENDING", "NONE", null))
        coEvery { repo.proyeccionLocalDeCaja("venue") } returns row
        coEvery { repo.resumenLocalDeCaja("venue", row.localSessionId) } returns
            LocalShiftSummary(BigDecimal("50.00"), null, "OPEN", "PENDING", "NONE", null)
        coEvery { repo.getCurrentShift("venue") } returns Result.Success(shift)
        coEvery { repo.getShiftHistory("venue", any()) } returns Result.Success(emptyList())
        coEvery { refunds.blockingForVenue("venue") } returns emptyList()
        coEvery { repo.pagosQueBloqueanCierre("venue", any()) } returns PaginaPagosDelCierre(0, emptyList(), 0)
    }
    @After fun cleanup() { vms.forEach { it.viewModelScope.cancel() }; unmockkAll() }
    private fun vm() = ShiftViewModel(repo, storage, events, cache, connectivity, permissions, refunds).also(vms::add)

    @Test fun `empty local storage and network failure allow a local opening without claiming remote absence`() = runTest {
        coEvery { repo.leerCajaLocal("venue") } returns null
        coEvery { repo.getCachedOpenShift("venue") } returns null
        coEvery { repo.getCurrentShift("venue") } returns Result.Error(ApiException.NetworkError(java.io.IOException("offline")))
        val vm = vm(); runCurrent()
        assertThat((vm.state.value as ShiftState.Error).canOpenLocally).isTrue()
        assertThat(vm.isOffline.value).isTrue()
        assertThat(vm.localCaja.value).isNull()
        coEvery { repo.openShift("venue", "staff", 750.0, null) } returns Result.Success(shift)
        vm.openShift(750.0); runCurrent()
        coVerify(exactly = 1) { repo.openShift("venue", "staff", 750.0, null) }
    }

    @Test fun `empty local storage does not bypass an explicit server denial`() = runTest {
        coEvery { repo.leerCajaLocal("venue") } returns null
        coEvery { repo.getCachedOpenShift("venue") } returns null
        coEvery { repo.getCurrentShift("venue") } returns Result.Error(ApiException.HttpError(403, "Forbidden"))
        val vm = vm(); runCurrent()
        assertThat((vm.state.value as ShiftState.Error).canOpenLocally).isFalse()
    }

    @Test fun `failed local read does not expose the offline opening path`() = runTest {
        coEvery { repo.leerCajaLocal("venue") } throws IllegalStateException("Room unavailable")
        val vm = vm(); runCurrent()
        assertThat((vm.state.value as ShiftState.Error).canOpenLocally).isFalse()
        coVerify(exactly = 0) { repo.getCurrentShift(any()) }
    }

    @Test fun `failed legacy cache read is not an empty local store`() = runTest {
        coEvery { repo.leerCajaLocal("venue") } returns null
        coEvery { repo.getCachedOpenShift("venue") } throws IllegalStateException("Cache unavailable")
        val vm = vm(); runCurrent()
        assertThat((vm.state.value as ShiftState.Error).canOpenLocally).isFalse()
        coVerify(exactly = 0) { repo.getCurrentShift(any()) }
    }

    @Test fun `offline opening alternative still denies a caller without create permission`() = runTest {
        coEvery { repo.leerCajaLocal("venue") } returns null
        coEvery { repo.getCachedOpenShift("venue") } returns null
        coEvery { repo.getCurrentShift("venue") } returns Result.Error(ApiException.NetworkError(java.io.IOException("offline")))
        coEvery { permissions.hasPermission("tpv-shifts:create") } returns false
        val vm = vm(); runCurrent()
        assertThat(vm.canOpenShift.value).isFalse()
        vm.openShift(750.0); runCurrent()
        coVerify(exactly = 0) { repo.openShift(any(), any(), any(), any()) }
    }

    @Test fun `late offline error after actor change cannot publish the opening alternative`() = runTest {
        coEvery { repo.leerCajaLocal("venue") } returns null
        coEvery { repo.getCachedOpenShift("venue") } returns null
        val response = CompletableDeferred<Result<Shift?>>()
        coEvery { repo.getCurrentShift("venue") } coAnswers { response.await() }
        val vm = vm(); runCurrent()
        every { storage.getStaffId() } returns "other-staff"
        response.complete(Result.Error(ApiException.NetworkError(java.io.IOException("offline")))); runCurrent()
        assertThat(vm.state.value).isEqualTo(ShiftState.Loading)
    }

    @Test fun `local caja is visible before history completes`() = runTest {
        val history = CompletableDeferred<Result<List<Shift>>>()
        coEvery { repo.getShiftHistory("venue", any()) } coAnswers { history.await() }
        val vm = vm(); runCurrent()
        assertThat((vm.state.value as? ShiftState.ShiftActive)?.shift?.localShiftSessionId).isEqualTo(row.localSessionId)
        assertThat(vm.isInitialLoading.value).isFalse()
    }

    @Test fun `local Success cannot clear server offline evidence`() = runTest {
        coEvery { repo.getCurrentShift("venue") } returns Result.Error(ApiException.NetworkError(java.io.IOException("offline")))
        val vm = vm(); runCurrent()
        assertThat(vm.isOffline.value).isTrue()
        coEvery { repo.getCurrentShift("venue") } returns Result.Success(shift)
        vm.loadCurrentShift(); runCurrent()
        assertThat(vm.isOffline.value).isTrue()
    }

    @Test fun `durable pending payment before close does not prevent local counted close`() = runTest {
        val vm = vm(); runCurrent()
        coEvery { repo.pagosQueBloqueanCierre("venue", any()) } returns PaginaPagosDelCierre(1, emptyList(), 0)
        val closed = shift.copy(status = ShiftStatus.CLOSED, endTime = "2026-10-28T11:00:00Z")
        coEvery { repo.closeShift("venue", shift.id, CashReconciliationAction.COUNTED, BigDecimal("72.00")) } returns Result.Success(closed)
        vm.closeShift(CashReconciliationAction.COUNTED, BigDecimal("72.00")); runCurrent()
        assertThat((vm.state.value as? ShiftState.ShiftClosed)?.shift?.status).isEqualTo(ShiftStatus.CLOSED)
        coVerify(exactly = 1) { repo.closeShift("venue", shift.id, CashReconciliationAction.COUNTED, BigDecimal("72.00")) }
    }

    @Test fun `local close summary does not wait for remote history`() = runTest {
        val vm = vm(); runCurrent()
        val history = CompletableDeferred<Result<List<Shift>>>()
        coEvery { repo.getShiftHistory("venue", any()) } coAnswers { history.await() }
        coEvery { repo.closeShift("venue", shift.id, CashReconciliationAction.SKIPPED, null) } returns
            Result.Success(shift.copy(status = ShiftStatus.CLOSED))
        vm.closeShift(CashReconciliationAction.SKIPPED); runCurrent()
        assertThat((vm.state.value as? ShiftState.ShiftClosed)?.reconciliationAction).isEqualTo(CashReconciliationAction.SKIPPED)
    }

    @Test fun `history A cannot overwrite a newer local B`() = runTest {
        val oldHistory = CompletableDeferred<Result<List<Shift>>>()
        coEvery { repo.getShiftHistory("venue", any()) } coAnswers { oldHistory.await() }
        val vm = vm(); runCurrent()
        val b = shift.copy(id = "remoteB", localShiftSessionId = "clocal00000000000000000002")
        val bRow = row.copy(localSessionId = b.localShiftSessionId!!, serverShiftId = "remoteB")
        coEvery { repo.leerCajaLocal("venue") } returns com.jaac.avoqado_tpv.features.shift.data.repository.CajaLocalSnapshot(
            b, bRow, LocalShiftSummary(BigDecimal("50.00"), null, "OPEN", "PENDING", "NONE", null))
        coEvery { repo.getCurrentShift("venue") } returns Result.Success(b)
        coEvery { repo.getShiftHistory("venue", any()) } returns Result.Success(emptyList())
        vm.loadCurrentShift(); runCurrent()
        oldHistory.complete(Result.Success(listOf(shift.copy(status = ShiftStatus.CLOSED))))
        runCurrent()
        assertThat((vm.state.value as ShiftState.ShiftActive).shift.localShiftSessionId).isEqualTo(b.localShiftSessionId)
        assertThat((vm.state.value as ShiftState.ShiftActive).shiftHistory).isEmpty()
    }

    @Test fun `local read failure cannot claim a successful caja or clear offline`() = runTest {
        coEvery { repo.leerCajaLocal("venue") } throws IllegalStateException("SQL read failed")
        val vm = vm(); runCurrent()
        assertThat(vm.state.value).isInstanceOf(ShiftState.Error::class.java)
        coVerify(exactly = 0) { repo.getCurrentShift(any()) }
    }

    @Test fun `counted close result A cannot replace explicitly adopted B on same remote pair`() = runTest {
        val closing = CompletableDeferred<Result<Shift>>()
        coEvery { repo.closeShift("venue", shift.id, CashReconciliationAction.COUNTED, BigDecimal("72.00")) } coAnswers { closing.await() }
        coEvery { repo.leerCajaLocal("venue") } returns com.jaac.avoqado_tpv.features.shift.data.repository.CajaLocalSnapshot(
            shift, row.copy(openingDisposition = "ADOPTED", remoteStartingCash = "100.00"),
            LocalShiftSummary(BigDecimal("50.00"), null, "OPEN", "CONFIRMED", "NONE", null))
        val vm = vm(); runCurrent()
        vm.closeShift(CashReconciliationAction.COUNTED, BigDecimal("72.00")); runCurrent()
        val b = shift.copy(id = shift.id, localShiftSessionId = "clocal00000000000000000002")
        val bRow = row.copy(localSessionId = b.localShiftSessionId!!)
        coEvery { repo.leerCajaLocal("venue") } returns com.jaac.avoqado_tpv.features.shift.data.repository.CajaLocalSnapshot(
            b, bRow, LocalShiftSummary(BigDecimal("100.00"), null, "OPEN", "CONFIRMED", "NONE", null))
        coEvery { repo.getCurrentShift("venue") } returns Result.Success(b)
        coEvery { repo.adoptarCajaRemotaParaNuevosGestos("venue", row.localSessionId) } returns Result.Success(bRow)
        vm.adoptRemoteCaja(); runCurrent()
        closing.complete(Result.Success(shift.copy(status = ShiftStatus.CLOSED)))
        runCurrent()
        assertThat((vm.state.value as ShiftState.ShiftActive).shift.localShiftSessionId).isEqualTo(b.localShiftSessionId)
    }

    @Test fun `actor changes while history awaits cannot publish old actor history`() = runTest {
        val history = CompletableDeferred<Result<List<Shift>>>()
        coEvery { repo.getShiftHistory("venue", any()) } coAnswers { history.await() }
        val vm = vm(); runCurrent()
        every { storage.getStaffId() } returns "newStaff"
        every { storage.getToken() } returns "newToken"
        history.complete(Result.Success(listOf(shift.copy(status = ShiftStatus.CLOSED))))
        runCurrent()
        assertThat((vm.state.value as ShiftState.ShiftActive).shiftHistory).isEmpty()
    }

    @Test fun `closed durable shift always defeats an open legacy cache for selling`() {
        assertThat(turnoPermiteVender(shift.copy(status = ShiftStatus.CLOSED), true, CachedShiftInfo(true, "old", 1))).isFalse()
        assertThat(turnoPermiteVender(shift.copy(status = ShiftStatus.CLOSING), true, CachedShiftInfo(true, "old", 1))).isFalse()
    }

    private fun obligation(owner: String, id: String) = LocalShiftObligation(owner, id, 1, "MANAGED", row.localSessionId)

    @Test fun `accredited current financial totals are not replaced by the local compatibility zero`() = runTest {
        val remote = "c000000000000000000000021"
        val projected = shift.copy(id = remote)
        val confirmed = row.copy(serverShiftId = remote, openingStatus = "CONFIRMED")
        coEvery { repo.leerCajaLocal("venue") } returns com.jaac.avoqado_tpv.features.shift.data.repository.CajaLocalSnapshot(
            projected, confirmed, LocalShiftSummary(BigDecimal("50.00"), null, "OPEN", "CONFIRMED", "NONE", null))
        val known = projected.copy(financialTotalsKnown = null, totalSales = BigDecimal("1500.00"), totalOrders = 25)
        coEvery { repo.getCurrentShift("venue") } returns Result.Success(known)
        val vm = vm(); runCurrent()
        val visible = (vm.state.value as ShiftState.ShiftActive).shift
        assertThat(visible.totalSales).isEqualTo(BigDecimal("1500.00"))
        assertThat(visible.hasKnownFinancialTotals()).isTrue()
    }

    @Test fun `accredited exact zero stays known through the connected text adapter`() = runTest {
        val remote = "c000000000000000000000021"
        val projected = shift.copy(id = remote)
        val confirmed = row.copy(serverShiftId = remote, openingStatus = "CONFIRMED")
        coEvery { repo.leerCajaLocal("venue") } returns com.jaac.avoqado_tpv.features.shift.data.repository.CajaLocalSnapshot(
            projected, confirmed, LocalShiftSummary(BigDecimal("50.00"), null, "OPEN", "CONFIRMED", "NONE", null))
        coEvery { repo.getCurrentShift("venue") } returns Result.Success(projected.copy(financialTotalsKnown = null))
        val vm = vm(); runCurrent()
        assertThat((vm.state.value as ShiftState.ShiftActive).shift.financialDisplayLines()).contains("Ventas" to "$0.00")
    }

    @Test fun `fresh close review or adopted B wins over accredited DTO A before publication`() = runTest {
        val remote = "c000000000000000000000021"
        val projected = shift.copy(id = remote)
        val confirmed = row.copy(serverShiftId = remote, openingStatus = "CONFIRMED")
        val initial = com.jaac.avoqado_tpv.features.shift.data.repository.CajaLocalSnapshot(projected, confirmed,
            LocalShiftSummary(BigDecimal("50.00"), null, "OPEN", "CONFIRMED", "NONE", null))
        for (kind in listOf("CLOSED", "REVIEW", "B")) {
            val freshRow = if (kind == "B") confirmed.copy(localSessionId = "clocal00000000000000000002", openingKind = "REMOTE", startingCash = "100.00")
                else confirmed.copy(localStatus = "CLOSED", closeStatus = kind.takeIf { it == "REVIEW" } ?: "PENDING",
                    closeOperationId = "c000000000000000000000022", countedCash = "72.00", cashReconciliationAction = "COUNTED")
            val freshShift = projected.copy(localShiftSessionId = freshRow.localSessionId,
                status = if (kind == "B") ShiftStatus.OPEN else ShiftStatus.CLOSED)
            val fresh = com.jaac.avoqado_tpv.features.shift.data.repository.CajaLocalSnapshot(freshShift, freshRow,
                LocalShiftSummary(freshRow.startingCash?.toBigDecimal(), freshRow.countedCash?.toBigDecimal(), freshRow.localStatus, "CONFIRMED", freshRow.closeStatus, null))
            val held = CompletableDeferred<com.jaac.avoqado_tpv.features.shift.data.repository.CajaLocalSnapshot?>()
            var reads = 0
            coEvery { repo.leerCajaLocal("venue") } coAnswers { if (++reads == 1) initial else held.await() }
            coEvery { repo.getCurrentShift("venue") } returns Result.Success(projected.copy(financialTotalsKnown = null, totalSales = BigDecimal("1500.00")))
            val vm = vm(); runCurrent()
            // A's financial DTO cannot appear while the current local row is still being revalidated.
            assertThat((vm.state.value as ShiftState.ShiftActive).shift.hasKnownFinancialTotals()).isFalse()
            held.complete(fresh); runCurrent()
            val visible = when (val state = vm.state.value) {
                is ShiftState.ShiftActive -> state.shift
                is ShiftState.ShiftClosed -> state.shift
                else -> error("Expected the fresh local projection")
            }
            assertThat(visible.localShiftSessionId).isEqualTo(freshRow.localSessionId)
            assertThat(visible.status).isEqualTo(freshShift.status)
            assertThat(visible.hasKnownFinancialTotals()).isFalse()
            assertThat(vm.localCaja.value?.session?.closeStatus).isEqualTo(freshRow.closeStatus)
            assertThat(vm.localCaja.value?.summary?.startingCash).isEqualTo(freshRow.startingCash?.toBigDecimal())
            vm.viewModelScope.cancel()
        }
    }

    @Test fun `new Room B beats legacy cache A read during bootstrap`() = runTest {
        coEvery { repo.leerCajaLocal("venue") } returns null
        val cacheRead = CompletableDeferred<Shift?>()
        coEvery { repo.getCachedOpenShift("venue") } coAnswers { cacheRead.await() }
        val currentHttp = CompletableDeferred<Result<Shift?>>()
        coEvery { repo.getCurrentShift("venue") } coAnswers { currentHttp.await() }
        val vm = vm(); runCurrent()
        val bRow = row.copy(localSessionId = "clocal00000000000000000002")
        val b = shift.copy(id = bRow.localSessionId, localShiftSessionId = bRow.localSessionId)
        coEvery { repo.leerCajaLocal("venue") } returns com.jaac.avoqado_tpv.features.shift.data.repository.CajaLocalSnapshot(
            b, bRow, LocalShiftSummary(BigDecimal("50.00"), null, "OPEN", "PENDING", "NONE", null))
        cacheRead.complete(shift.copy(id = "old-cache-A", localShiftSessionId = null)); runCurrent()
        assertThat((vm.state.value as ShiftState.ShiftActive).shift.localShiftSessionId).isEqualTo(bRow.localSessionId)
        assertThat(vm.localCaja.value?.session?.localSessionId).isEqualTo(bRow.localSessionId)
    }

    @Test fun `local review cannot acknowledge card refund remote table or crypto generically`() = runTest {
        val page = LocalShiftObligationPage(6, listOf(obligation("PAYMENT", "5"), obligation("PAYMENT", "6"),
            obligation("ATTEMPT", "attemptA"), obligation("REFUND", "refundA"), obligation("REMOTE", "remoteA"),
            obligation("CRYPTO", "cryptoA")), 0)
        coEvery { repo.obligacionesDelCierreLocal("venue", row.localSessionId, 0) } returns page
        coEvery { repo.efectivoRevisableDelCierre("venue", page) } returns setOf(5L)
        val vm = vm(); runCurrent(); vm.reviewLocalCaja(row.localSessionId); runCurrent()
        vm.acknowledgeLocalCash(6); vm.acknowledgeLocalCash(999); runCurrent()
        coVerify(exactly = 0) { repo.reconocerPagoEnCierreLocal(any(), any(), any(), any(), any(), any()) }
        assertThat(vm.localReview.value?.page?.total).isEqualTo(6)
        assertThat(vm.localReview.value?.cashEligible).containsExactly(5L)
    }

    @Test fun `local cash review checks current permission and preserves pending on denial`() = runTest {
        val page = LocalShiftObligationPage(1, listOf(obligation("PAYMENT", "5")), 0)
        coEvery { repo.obligacionesDelCierreLocal("venue", row.localSessionId, 0) } returns page
        coEvery { repo.efectivoRevisableDelCierre("venue", page) } returns setOf(5L)
        val vm = vm(); runCurrent(); vm.reviewLocalCaja(row.localSessionId); runCurrent()
        coEvery { permissions.hasPermission("tpv-shifts:close") } returns false
        vm.acknowledgeLocalCash(5); runCurrent()
        assertThat(vm.localReview.value?.busy).isFalse()
        assertThat(vm.localReview.value?.error).contains("permiso")
        coVerify(exactly = 0) { repo.reconocerPagoEnCierreLocal(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `local review page A cannot overwrite selected page B`() = runTest {
        val held = CompletableDeferred<LocalShiftObligationPage>()
        coEvery { repo.obligacionesDelCierreLocal("venue", row.localSessionId, 0) } coAnswers { held.await() }
        val b = "clocal00000000000000000002"
        val pageB = LocalShiftObligationPage(1, listOf(obligation("CRYPTO", "keyB")), 0)
        coEvery { repo.obligacionesDelCierreLocal("venue", b, 0) } returns pageB
        coEvery { repo.efectivoRevisableDelCierre("venue", any()) } returns emptySet()
        val vm = vm(); runCurrent(); vm.reviewLocalCaja(row.localSessionId); runCurrent()
        vm.reviewLocalCaja(b); runCurrent()
        held.complete(LocalShiftObligationPage(100, emptyList(), 0)); runCurrent()
        assertThat(vm.localReview.value?.sessionId).isEqualTo(b)
        assertThat(vm.localReview.value?.page?.total).isEqualTo(1)
    }

    @Test fun `cold UI recovers crypto by the selected Room owner K without issuing a QR`() = runTest {
        val key = "c000000000000000000000001"
        val page = LocalShiftObligationPage(1, listOf(obligation("CRYPTO", key)), 0)
        coEvery { repo.obligacionesDelCierreLocal("venue", row.localSessionId, 0) } returns page
        coEvery { repo.efectivoRevisableDelCierre("venue", page) } returns emptySet()
        coEvery { repo.consultarCriptoDurable("venue", key) } returns com.jaac.avoqado_tpv.features.shift.data.dto.CryptoOperationResultDto(
            paymentId = key, operation = com.jaac.avoqado_tpv.features.shift.data.dto.CryptoOperationDto(key, "UNCERTAIN", "PENDING", false))
        val vm = vm(); runCurrent(); vm.reviewLocalCaja(row.localSessionId); runCurrent()
        vm.queryLocalCrypto("not-the-owner"); vm.queryLocalCrypto(key); runCurrent()
        coVerify(exactly = 1) { repo.consultarCriptoDurable("venue", key) }
        coVerify(exactly = 0) { repo.consultarCriptoDurable("venue", "not-the-owner") }
        coVerify(exactly = 0) { repo.iniciarCriptoDurable(any(), any(), any()) }
        assertThat(vm.localReview.value?.error).contains("no está confirmado")
    }

    @Test fun `crypto query A cannot replace review B when its reply arrives late`() = runTest {
        val key = "c000000000000000000000001"
        val page = LocalShiftObligationPage(1, listOf(obligation("CRYPTO", key)), 0)
        coEvery { repo.obligacionesDelCierreLocal("venue", row.localSessionId, 0) } returns page
        coEvery { repo.efectivoRevisableDelCierre("venue", any()) } returns emptySet()
        val held = CompletableDeferred<com.jaac.avoqado_tpv.features.shift.data.dto.CryptoOperationResultDto>()
        coEvery { repo.consultarCriptoDurable("venue", key) } coAnswers { held.await() }
        val vm = vm(); runCurrent(); vm.reviewLocalCaja(row.localSessionId); runCurrent()
        vm.queryLocalCrypto(key); runCurrent()
        val b = "clocal00000000000000000002"
        coEvery { repo.obligacionesDelCierreLocal("venue", b, 0) } returns LocalShiftObligationPage(0, emptyList(), 0)
        vm.reviewLocalCaja(b); runCurrent()
        held.complete(com.jaac.avoqado_tpv.features.shift.data.dto.CryptoOperationResultDto(paymentId = key)); runCurrent()
        assertThat(vm.localReview.value?.sessionId).isEqualTo(b)
        assertThat(vm.localReview.value?.error).isNull()
    }
}

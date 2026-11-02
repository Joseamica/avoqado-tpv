package com.jaac.avoqado_tpv.features.kiosk.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.manager.KioskModeManager
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.features.shift.presentation.readCajaBootstrap
import com.jaac.avoqado_tpv.features.kiosk.domain.model.KioskOperationalState
import com.jaac.avoqado_tpv.features.shift.data.repository.CajaLocalSnapshot
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import com.jaac.avoqado_tpv.features.shift.domain.*
import com.jaac.avoqado_tpv.core.data.local.entity.LocalShiftSessionEntity
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.Test
import java.math.BigDecimal

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class Task7KioskCajaTest {
    private val repo = mockk<ShiftRepository>(relaxed = true)
    private val storage = mockk<SecureStorage>(relaxed = true)
    private val row = LocalShiftSessionEntity("c000000000000000000000001", "venue", "LOCAL", openingStatus = "PENDING",
        originalOpenedAt = "2026-10-28T10:00:00Z", startingCash = "50.00", openedByStaffId = "staff",
        localStatus = "OPEN", createdAt = 1, updatedAt = 1)
    private val shift = Shift(row.localSessionId, "venue", "staff", "", row.originalOpenedAt, null, ShiftStatus.OPEN,
        BigDecimal("50.00"), null, BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, 0, null, localShiftSessionId = row.localSessionId, financialTotalsKnown = false)
    private fun manager(): KioskModeManager {
        every { storage.getIsKioskMode() } returns true
        every { storage.getKioskVenueId() } returns "venue"
        every { storage.getVenueId() } returns "venue"
        every { storage.getStaffId() } returns "staff"
        every { storage.getToken() } returns "token"
        return KioskModeManager(storage)
    }
    @Test fun `kiosk sees durable closed before HTTP and never treats it as open`() = runTest {
        val manager = manager()
        val closed = snapshot(false)
        coEvery { repo.leerCajaLocal("venue") } returns closed
        val http = CompletableDeferred<Result<Shift?>>()
        coEvery { repo.getCurrentShift("venue") } coAnswers { http.await() }
        val call = async { checkShiftStatus(repo, manager, "venue", storage) }; runCurrent()
        assertThat(manager.operationalState.value).isInstanceOf(KioskOperationalState.NoShift::class.java)
        http.complete(Result.Success(closed.shift)); call.await()
        assertThat(manager.operationalState.value).isInstanceOf(KioskOperationalState.NoShift::class.java)
    }
    @Test fun `old kiosk HTTP A cannot replace B after PIN token changed`() = runTest {
        val manager = manager()
        coEvery { repo.leerCajaLocal("venue") } returns CajaLocalSnapshot(shift, row,
            LocalShiftSummary(BigDecimal("50.00"), null, "OPEN", "PENDING", "NONE", null))
        val http = CompletableDeferred<Result<Shift?>>()
        coEvery { repo.getCurrentShift("venue") } coAnswers { http.await() }
        val call = async { checkShiftStatus(repo, manager, "venue", storage) }; runCurrent()
        every { storage.getToken() } returns "newToken"
        manager.updateShiftStatus(false)
        http.complete(Result.Success(shift)); call.await()
        assertThat(manager.operationalState.value).isInstanceOf(KioskOperationalState.NoShift::class.java)
    }

    private fun cachedOpen() = shift.copy(id = "remoteA", localShiftSessionId = null, financialTotalsKnown = null)

    @Test fun `admin connected bootstrap shows last known legacy OPEN before held HTTP`() = runTest {
        coEvery { repo.leerCajaLocal("venue") } returns null
        coEvery { repo.getCachedOpenShift("venue") } returns cachedOpen()
        // Este es el adaptador consumido por AdminOption, no un texto construido en el test.
        val shown = readCajaBootstrap(repo, "venue")
        assertThat(shown.title).isEqualTo("Última caja abierta conocida")
        assertThat(shown.shift?.id).isEqualTo("remoteA")
        assertThat(shown.shift?.financialTotalsKnown).isFalse()
        coVerify(exactly = 0) { repo.getCurrentShift(any()) }
    }

    @Test fun `kiosk admission uses legacy OPEN before held HTTP without reporting network recovery`() = runTest {
        val manager = manager()
        coEvery { repo.leerCajaLocal("venue") } returns null
        coEvery { repo.getCachedOpenShift("venue") } returns cachedOpen()
        val http = CompletableDeferred<Result<Shift?>>()
        coEvery { repo.getCurrentShift("venue") } coAnswers { http.await() }
        val call = async { checkShiftStatus(repo, manager, "venue", storage) }; runCurrent()
        try {
            assertThat(manager.operationalState.value).isInstanceOf(KioskOperationalState.Ready::class.java)
        } finally { http.complete(Result.Success(null)); call.await() }
        assertThat(manager.operationalState.value).isInstanceOf(KioskOperationalState.NoShift::class.java)
    }

    @Test fun `admin bootstrap prefers new Room B CLOSED created during legacy cache read`() = runTest {
        val heldCache = CompletableDeferred<Shift?>()
        val closed = remoteClosedB()
        var fresh: CajaLocalSnapshot? = null
        coEvery { repo.leerCajaLocal("venue") } coAnswers { fresh }
        coEvery { repo.getCachedOpenShift("venue") } coAnswers { heldCache.await() }
        val call = async { readCajaBootstrap(repo, "venue") }; runCurrent()
        fresh = closed; heldCache.complete(cachedOpen())
        val shown = call.await()
        assertThat(shown.shift?.id).isEqualTo("remoteB")
        assertThat(shown.title).isEqualTo("Caja cerrada o cerrándose en otro aparato")
    }

    @Test fun `kiosk new Room B CLOSED wins over late HTTP OPEN A`() = runTest {
        val manager = manager()
        var current = CajaLocalSnapshot(shift, row,
            LocalShiftSummary(BigDecimal("50.00"), null, "OPEN", "PENDING", "NONE", null))
        coEvery { repo.leerCajaLocal("venue") } coAnswers { current }
        val http = CompletableDeferred<Result<Shift?>>()
        coEvery { repo.getCurrentShift("venue") } coAnswers { http.await() }
        val call = async { checkShiftStatus(repo, manager, "venue", storage) }; runCurrent()
        current = remoteClosedB()
        http.complete(Result.Success(shift)); call.await()
        assertThat(manager.operationalState.value).isInstanceOf(KioskOperationalState.NoShift::class.java)
    }

    @Test fun `kiosk old cached A cannot publish after PIN token changes during read`() = runTest {
        // Las tres mutaciones públicas del contexto se prueban por separado.
        for (change in listOf("token", "actor", "venue")) {
            val manager = manager()
            coEvery { repo.leerCajaLocal("venue") } returns null
            val heldCache = CompletableDeferred<Shift?>()
            coEvery { repo.getCachedOpenShift("venue") } coAnswers { heldCache.await() }
            coEvery { repo.getCurrentShift("venue") } returns Result.Error(ApiException.NetworkError(java.io.IOException("offline")))
            val call = async { checkShiftStatus(repo, manager, "venue", storage) }; runCurrent()
            when (change) {
                "token" -> every { storage.getToken() } returns "newToken"
                "actor" -> every { storage.getStaffId() } returns "newStaff"
                "venue" -> every { storage.getVenueId() } returns "newVenue"
            }
            manager.updateShiftStatus(false)
            heldCache.complete(cachedOpen()); call.await()
            assertThat(manager.operationalState.value).isInstanceOf(KioskOperationalState.NoShift::class.java)
            coVerify(exactly = 0) { repo.getCurrentShift(any()) }
        }
    }

    @Test fun `local OPEN never clears two HTTP failures before a third HTTP error`() = runTest {
        val manager = manager(); manager.handleNetworkError(); manager.handleNetworkError()
        coEvery { repo.leerCajaLocal("venue") } returns CajaLocalSnapshot(shift, row,
            LocalShiftSummary(BigDecimal("50.00"), null, "OPEN", "PENDING", "NONE", null))
        coEvery { repo.getCurrentShift("venue") } returns Result.Error(ApiException.NetworkError(java.io.IOException("offline")))
        checkShiftStatus(repo, manager, "venue", storage)
        assertThat(manager.operationalState.value).isInstanceOf(KioskOperationalState.NetworkError::class.java)
    }

    @Test fun `Room CLOSED B also wins when retained HTTP A ends in error`() = runTest {
        val manager = manager()
        var current = CajaLocalSnapshot(shift, row,
            LocalShiftSummary(BigDecimal("50.00"), null, "OPEN", "PENDING", "NONE", null))
        coEvery { repo.leerCajaLocal("venue") } coAnswers { current }
        val http = CompletableDeferred<Result<Shift?>>()
        coEvery { repo.getCurrentShift("venue") } coAnswers { http.await() }
        val call = async { checkShiftStatus(repo, manager, "venue", storage) }; runCurrent()
        current = remoteClosedB()
        http.complete(Result.Error(ApiException.NetworkError(java.io.IOException("offline")))); call.await()
        assertThat(manager.operationalState.value).isInstanceOf(KioskOperationalState.NoShift::class.java)
    }

    // Dobles tipados coherentes con el contrato T2, no una base Room real.
    private fun snapshot(open: Boolean = true): CajaLocalSnapshot {
        val closedAt = "2026-10-28T11:00:00Z"
        val current = if (open) row else row.copy(localStatus = "CLOSED", openSlot = null,
            closeOperationId = "c000000000000000000000013", cashReconciliationAction = "SKIPPED",
            closedAtLocal = closedAt, closedByStaffId = "staff", closeStatus = "PENDING")
        return CajaLocalSnapshot(shift.copy(status = if (open) ShiftStatus.OPEN else ShiftStatus.CLOSED,
            endTime = if (open) null else closedAt), current,
            LocalShiftSummary(BigDecimal("50.00"), null, current.localStatus, "PENDING", current.closeStatus, null))
    }
    private fun remoteClosedB(): CajaLocalSnapshot {
        val bId = "c000000000000000000000002"
        val current = row.copy(localSessionId = bId, openingKind = "REMOTE", serverShiftId = "remoteB",
            openingStatus = "CONFIRMED", startingCash = null, localStatus = "CLOSED", openSlot = null)
        return CajaLocalSnapshot(shift.copy(id = "remoteB", localShiftSessionId = bId, status = ShiftStatus.CLOSED,
            startingCash = BigDecimal.ZERO), current, LocalShiftSummary(null, null, "CLOSED", "CONFIRMED", "NONE", null))
    }
    private fun failures(manager: KioskModeManager, count: Int) { repeat(count) { manager.handleNetworkError() } }
    private fun assertLocalOpenState(manager: KioskModeManager, priorFailures: Int) {
        assertThat(manager.operationalState.value).isInstanceOf(
            if (priorFailures >= 3) KioskOperationalState.NetworkError::class.java else KioskOperationalState.Ready::class.java)
        assertThat(manager.canAcceptNewOrders()).isEqualTo(priorFailures < 3)
    }

    @Test fun `R24 local Successfalse cannot recover three or more HTTP errors`() = runTest {
        for (count in listOf(0, 2, 3, 4)) {
            val manager = manager(); failures(manager, count)
            coEvery { repo.leerCajaLocal("venue") } returns snapshot()
            coEvery { repo.getCurrentShift("venue") } returns Result.Success(shift)
            checkShiftStatus(repo, manager, "venue", storage)
            assertLocalOpenState(manager, count)
        }
    }

    @Test fun `R24 actual pulse OPEN preserves HTTP threshold zero two three and four`() = runTest {
        for (count in listOf(0, 2, 3, 4)) {
            val manager = manager(); failures(manager, count)
            coEvery { repo.leerCajaLocal("venue") } returns snapshot()
            refreshKioskCajaLocal(repo, manager, "venue", storage)
            assertLocalOpenState(manager, count)
        }
        coVerify(exactly = 0) { repo.getCurrentShift(any()) }
    }

    @Test fun `R24 legacy cache bootstrap preserves threshold while HTTP is retained`() = runTest {
        for (count in listOf(0, 2, 3, 4)) {
            val manager = manager(); failures(manager, count)
            coEvery { repo.leerCajaLocal("venue") } returns null
            coEvery { repo.getCachedOpenShift("venue") } returns cachedOpen()
            val http = CompletableDeferred<Result<Shift?>>()
            coEvery { repo.getCurrentShift("venue") } coAnswers { http.await() }
            val call = async { checkShiftStatus(repo, manager, "venue", storage) }; runCurrent()
            try { assertLocalOpenState(manager, count) }
            finally { http.complete(Result.Error(ApiException.NetworkError(java.io.IOException("offline")))); call.await() }
        }
    }

    @Test fun `R24 CLOSED pulse shows NoShift without erasing HTTP threshold for next OPEN`() = runTest {
        for (count in listOf(0, 2, 3, 4)) {
            val manager = manager(); failures(manager, count)
            coEvery { repo.leerCajaLocal("venue") } returns snapshot(false)
            refreshKioskCajaLocal(repo, manager, "venue", storage)
            assertThat(manager.operationalState.value).isInstanceOf(KioskOperationalState.NoShift::class.java)
            coEvery { repo.leerCajaLocal("venue") } returns snapshot()
            refreshKioskCajaLocal(repo, manager, "venue", storage)
            assertLocalOpenState(manager, count)
        }
    }

    @Test fun `R24 no snapshot pulse neither calls HTTP nor resets failure threshold`() = runTest {
        for (count in listOf(0, 2, 3, 4)) {
            val manager = manager(); failures(manager, count)
            coEvery { repo.leerCajaLocal("venue") } returns null
            coEvery { repo.getCachedOpenShift("venue") } returns null
            val before = manager.operationalState.value
            refreshKioskCajaLocal(repo, manager, "venue", storage)
            assertThat(manager.operationalState.value).isSameInstanceAs(before)
            coEvery { repo.getCurrentShift("venue") } returns Result.Error(ApiException.NetworkError(java.io.IOException("offline")))
            checkShiftStatus(repo, manager, "venue", storage)
            assertThat(manager.operationalState.value).isInstanceOf(
                if (count >= 2) KioskOperationalState.NetworkError::class.java else KioskOperationalState.Checking::class.java)
        }
    }

    @Test fun `R24 accredited HTTP OPEN recovers Ready and resets failure count`() = runTest {
        for (count in listOf(3, 4)) {
            val manager = manager(); failures(manager, count)
            val known = shift.copy(id = "remoteA", financialTotalsKnown = null)
            coEvery { repo.leerCajaLocal("venue") } returns snapshot().copy(shift = known.copy(financialTotalsKnown = false),
                row = row.copy(serverShiftId = "remoteA", openingStatus = "CONFIRMED"))
            coEvery { repo.getCurrentShift("venue") } returns Result.Success(known)
            checkShiftStatus(repo, manager, "venue", storage)
            assertThat(manager.operationalState.value).isInstanceOf(KioskOperationalState.Ready::class.java)
            failures(manager, 2)
            assertThat(manager.operationalState.value).isInstanceOf(KioskOperationalState.Ready::class.java)
            manager.handleNetworkError()
            assertThat(manager.operationalState.value).isInstanceOf(KioskOperationalState.NetworkError::class.java)
        }
    }

    @Test fun `R24 existing explicit readiness reauthentication and entry resets keep their contract`() = runTest {
        // Métodos públicos existentes. Sólo setReady tiene caller: shifts desactivados.
        // No se inventa un callback de autenticación o red para satisfacer esta prueba.
        for (reset in listOf("ready", "reauth", "entry", "counterOnly")) {
            val manager = manager(); failures(manager, 3)
            when (reset) {
                "ready" -> manager.setReady()
                "reauth" -> manager.handleReauthenticated()
                "entry" -> manager.enterKioskMode("venue")
                "counterOnly" -> manager.resetNetworkFailures()
            }
            if (reset == "counterOnly") assertThat(manager.operationalState.value).isInstanceOf(KioskOperationalState.NetworkError::class.java)
            manager.updateShiftStatus(true, serverAnswered = false)
            assertThat(manager.operationalState.value).isInstanceOf(KioskOperationalState.Ready::class.java)
            failures(manager, 2)
            assertThat(manager.operationalState.value).isInstanceOf(KioskOperationalState.Ready::class.java)
            manager.handleNetworkError()
            assertThat(manager.operationalState.value).isInstanceOf(KioskOperationalState.NetworkError::class.java)
        }
    }

    @Test fun `R24 retained HTTP cannot revive kiosk after exit`() = runTest {
        val manager = manager()
        coEvery { repo.leerCajaLocal("venue") } returns null
        coEvery { repo.getCachedOpenShift("venue") } returns null
        val http = CompletableDeferred<Result<Shift?>>()
        coEvery { repo.getCurrentShift("venue") } coAnswers { http.await() }
        val call = async { checkShiftStatus(repo, manager, "venue", storage) }; runCurrent()
        manager.exitKioskMode()
        http.complete(Result.Success(cachedOpen())); call.await()
        assertThat(manager.operationalState.value).isInstanceOf(KioskOperationalState.Disabled::class.java)
        assertThat(manager.canAcceptNewOrders()).isFalse()
    }

    @Test fun `R24 retained HTTP A cannot publish after exit and same pair reentry`() = runTest {
        val manager = manager()
        coEvery { repo.leerCajaLocal("venue") } returns null
        coEvery { repo.getCachedOpenShift("venue") } returns null
        val http = CompletableDeferred<Result<Shift?>>()
        coEvery { repo.getCurrentShift("venue") } coAnswers { http.await() }
        val call = async { checkShiftStatus(repo, manager, "venue", storage) }; runCurrent()
        manager.exitKioskMode(); manager.enterKioskMode("venue")
        val reentry = manager.operationalState.value
        http.complete(Result.Success(cachedOpen())); call.await()
        assertThat(manager.operationalState.value).isSameInstanceAs(reentry)
        assertThat(manager.operationalState.value).isInstanceOf(KioskOperationalState.Checking::class.java)
    }

    @Test fun `R24 held cache pulse cannot revive exited kiosk`() = runTest {
        val manager = manager()
        coEvery { repo.leerCajaLocal("venue") } returns null
        val cache = CompletableDeferred<Shift?>()
        coEvery { repo.getCachedOpenShift("venue") } coAnswers { cache.await() }
        val call = async { refreshKioskCajaLocal(repo, manager, "venue", storage) }; runCurrent()
        manager.exitKioskMode()
        cache.complete(cachedOpen()); call.await()
        assertThat(manager.operationalState.value).isInstanceOf(KioskOperationalState.Disabled::class.java)
    }

    @Test fun `R24 held cache pulse A cannot publish after same pair reentry`() = runTest {
        val manager = manager()
        coEvery { repo.leerCajaLocal("venue") } returns null
        val cache = CompletableDeferred<Shift?>()
        coEvery { repo.getCachedOpenShift("venue") } coAnswers { cache.await() }
        val call = async { refreshKioskCajaLocal(repo, manager, "venue", storage) }; runCurrent()
        manager.exitKioskMode(); manager.enterKioskMode("venue")
        val reentry = manager.operationalState.value
        cache.complete(cachedOpen()); call.await()
        assertThat(manager.operationalState.value).isSameInstanceAs(reentry)
        assertThat(manager.operationalState.value).isInstanceOf(KioskOperationalState.Checking::class.java)
    }

    @Test fun `R24 local pulse cannot clear explicitly expired authentication`() = runTest {
        // API pública existente, sin caller productivo hoy: no añadimos política401.
        val manager = manager(); manager.handleAuthExpired()
        coEvery { repo.leerCajaLocal("venue") } returns snapshot()
        refreshKioskCajaLocal(repo, manager, "venue", storage)
        assertThat(manager.operationalState.value).isInstanceOf(KioskOperationalState.AuthExpired::class.java)
        assertThat(manager.canAcceptNewOrders()).isFalse()
    }

    @Test fun `R24 positive HTTP resets network evidence while fresh CLOSED B still wins`() = runTest {
        val manager = manager(); failures(manager, 3)
        val http = CompletableDeferred<Result<Shift?>>()
        var current = snapshot()
        coEvery { repo.leerCajaLocal("venue") } coAnswers { current }
        coEvery { repo.getCurrentShift("venue") } coAnswers { http.await() }
        val call = async { checkShiftStatus(repo, manager, "venue", storage) }; runCurrent()
        current = remoteClosedB()
        http.complete(Result.Success(cachedOpen())); call.await()
        assertThat(manager.operationalState.value).isInstanceOf(KioskOperationalState.NoShift::class.java)
        assertThat(manager.canAcceptNewOrders()).isFalse()
        failures(manager, 2)
        assertThat(manager.operationalState.value).isInstanceOf(KioskOperationalState.NoShift::class.java)
        manager.handleNetworkError()
        assertThat(manager.operationalState.value).isInstanceOf(KioskOperationalState.NetworkError::class.java)
    }
}

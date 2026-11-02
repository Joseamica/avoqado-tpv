package com.jaac.avoqado_tpv.features.kiosk.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.entity.LocalShiftSessionEntity
import com.jaac.avoqado_tpv.core.data.manager.KioskModeManager
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.features.kiosk.domain.model.KioskOperationalState
import com.jaac.avoqado_tpv.features.kiosk.presentation.components.observeKioskAdminCajaChanges
import com.jaac.avoqado_tpv.features.kiosk.presentation.components.kioskAdminCajaTitle
import com.jaac.avoqado_tpv.features.kiosk.presentation.screens.getUnavailableMessages
import com.jaac.avoqado_tpv.features.shift.data.repository.CajaLocalSnapshot
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import com.jaac.avoqado_tpv.features.shift.domain.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Test
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class Task7FixR1KioskEventsTest {
    private class Fixture {
        val repo = mockk<ShiftRepository>(relaxed = true)
        val storage = mockk<SecureStorage>(relaxed = true)
        val events = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        var venue = "venue"; var staff = "staff"; var token = "token"
        var badCapture: String? = null
        var badGuard: Throwable? = null
        var escaped: Throwable? = null
        var adminShift: Shift? = null
        var adminError: String? = null
        val row = LocalShiftSessionEntity("c000000000000000000000031", "venue", "LOCAL",
            openingOperationId = "c000000000000000000000031", openingStatus = "PENDING",
            originalOpenedAt = "2026-10-29T09:00:00Z", startingCash = "50.00", openedByStaffId = "staff",
            localStatus = "OPEN", createdAt = 1, updatedAt = 1)
        val shift = Shift(row.localSessionId, "venue", "staff", "", row.originalOpenedAt, null, ShiftStatus.OPEN,
            BigDecimal("50.00"), null, BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, 0, null, localShiftSessionId = row.localSessionId, financialTotalsKnown = false)
        fun snapshot() = CajaLocalSnapshot(shift.copy(venueId = venue, staffId = staff),
            row.copy(venueId = venue, openedByStaffId = staff), LocalShiftSummary(BigDecimal("50.00"), null, "OPEN", "PENDING", "NONE", null))
        val manager: KioskModeManager
        init {
            every { storage.getIsKioskMode() } returns true
            every { storage.isShiftSystemEnabled() } answers { if (badCapture == "enabled") error("settings read") else true }
            every { storage.getKioskVenueId() } answers { if (badCapture == "venue") error("session venue read") else venue }
            every { storage.getVenueId() } answers { venue }
            every { storage.getStaffId() } answers { if (badCapture == "staff") error("session actor read") else staff }
            every { storage.getToken() } answers {
                badGuard?.let { throw it }
                if (badCapture == "token") error("session token read") else token
            }
            every { repo.shiftChanges } returns events
            coEvery { repo.leerCajaLocal(any()) } coAnswers { snapshot() }
            coEvery { repo.getCachedOpenShift(any()) } returns null
            coEvery { repo.getCurrentShift(any()) } returns Result.Success(shift)
            manager = KioskModeManager(storage)
        }
        fun start(scope: CoroutineScope, admin: Boolean = false): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (admin) observeKioskAdminCajaChanges(repo, storage, manager, "venue", "staff",
                    onCaja = { value, _ -> adminShift = value }, onError = { adminError = it })
                else observeKioskCajaChanges(repo, manager, storage)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: Exception) { escaped = failure; println("F1 collector escaped: ${failure.stackTraceToString()}") }
        }
    }

    private suspend fun TestScope.recoverAfterReadFailure(point: String) {
        val f = Fixture(); var fail = true; var reads = 0
        coEvery { f.repo.leerCajaLocal(any()) } coAnswers {
            reads++
            if (fail && (point == "room" || point == "reread" && reads == 2)) error("local read failed")
            if (fail) null else f.snapshot()
        }
        coEvery { f.repo.getCachedOpenShift(any()) } coAnswers { if (fail && point == "cache") error("cache read failed") else null }
        val listener = f.start(backgroundScope); runCurrent()
        try {
            f.events.emit(Unit); runCurrent()
            val afterFailure = f.manager.operationalState.value
            fail = false
            f.events.emit(Unit); runCurrent()
            assertThat(f.manager.operationalState.value).isInstanceOf(KioskOperationalState.Ready::class.java)
            assertThat(f.escaped).isNull()
            assertThat(listener.isActive).isTrue()
            assertThat(afterFailure.getUnavailableReason()?.name).isEqualTo("LOCAL_CAJA_READ_ERROR")
            coVerify(exactly = 0) { f.repo.getCurrentShift(any()) }
        } finally { listener.cancel(); runCurrent() }
    }
    @Test fun `F1 actual collector survives failed first Room read then valid event`() = runTest { recoverAfterReadFailure("room") }
    @Test fun `F1 actual collector survives failed cache read then valid event`() = runTest { recoverAfterReadFailure("cache") }
    @Test fun `F1 actual collector survives failed Room reread then valid event`() = runTest { recoverAfterReadFailure("reread") }

    @Test fun `F1 event session capture failures do not terminate collector or publish`() = runTest {
        for (field in listOf("venue", "staff", "token")) {
            val f = Fixture(); f.manager.updateShiftStatus(false); val before = f.manager.operationalState.value
            f.badCapture = field
            val listener = f.start(backgroundScope); runCurrent()
            try {
                f.events.emit(Unit); runCurrent()
                assertThat(f.manager.operationalState.value).isSameInstanceAs(before)
                f.badCapture = null; f.events.emit(Unit); runCurrent()
                assertThat(listener.isActive).isTrue(); assertThat(f.escaped).isNull()
                assertThat(f.manager.operationalState.value).isInstanceOf(KioskOperationalState.Ready::class.java)
            } finally { listener.cancel(); runCurrent() }
        }
    }

    @Test fun `F1 failing guard while handling read error is contained and next event works`() = runTest {
        val f = Fixture(); var fail = true
        coEvery { f.repo.leerCajaLocal(any()) } coAnswers {
            if (fail) { f.badGuard = IllegalStateException("session guard failed"); error("local read failed") }
            f.snapshot()
        }
        f.manager.updateShiftStatus(false); val before = f.manager.operationalState.value
        val listener = f.start(backgroundScope); runCurrent()
        try {
            f.events.emit(Unit); runCurrent()
            assertThat(f.manager.operationalState.value).isSameInstanceAs(before)
            fail = false; f.badGuard = null; f.events.emit(Unit); runCurrent()
            assertThat(listener.isActive).isTrue(); assertThat(f.escaped).isNull()
            assertThat(f.manager.operationalState.value).isInstanceOf(KioskOperationalState.Ready::class.java)
        } finally { listener.cancel(); runCurrent() }
    }

    @Test fun `F1 late read error cannot overwrite actor venue token exit or reentry target`() = runTest {
        for (change in listOf("staff", "venue", "token", "exit", "reentry")) {
            val f = Fixture(); val release = CompletableDeferred<Unit>(); var fail = true
            coEvery { f.repo.leerCajaLocal(any()) } coAnswers { if (fail) { release.await(); error("late A read failed") }; f.snapshot() }
            val listener = f.start(backgroundScope); runCurrent()
            try {
                f.events.emit(Unit); runCurrent()
                when (change) {
                    "staff" -> f.staff = "B"
                    "venue" -> f.venue = "B"
                    "token" -> f.token = "B"
                    "exit" -> f.manager.exitKioskMode()
                    "reentry" -> { f.manager.exitKioskMode(); f.manager.enterKioskMode("venue") }
                }
                if (change !in listOf("exit", "reentry")) f.manager.updateShiftStatus(false)
                val target = f.manager.operationalState.value
                release.complete(Unit); runCurrent()
                assertThat(f.manager.operationalState.value).isSameInstanceAs(target)
                assertThat(listener.isActive).isTrue(); assertThat(f.escaped).isNull()
            } finally { listener.cancel(); runCurrent() }
        }
    }

    @Test fun `F1 Admin collector survives capture error and processes next event`() = runTest {
        val f = Fixture(); f.badCapture = "token"
        val listener = f.start(backgroundScope, admin = true); runCurrent()
        try {
            assertThat(f.adminShift).isNull()
            f.badCapture = null; f.events.emit(Unit); runCurrent()
            assertThat(listener.isActive).isTrue(); assertThat(f.escaped).isNull()
            assertThat(f.adminShift?.id).isEqualTo(f.shift.id)
        } finally { listener.cancel(); runCurrent() }
    }

    @Test fun `F1 Admin error guard may fail without escaping its event`() = runTest {
        val f = Fixture(); var fail = true
        coEvery { f.repo.leerCajaLocal(any()) } coAnswers {
            if (fail) { f.badGuard = IllegalStateException("session guard failed"); error("local read failed") }
            f.snapshot()
        }
        val listener = f.start(backgroundScope, admin = true); runCurrent()
        try {
            fail = false; f.badGuard = null; f.events.emit(Unit); runCurrent()
            assertThat(listener.isActive).isTrue(); assertThat(f.escaped).isNull()
            assertThat(f.adminShift?.id).isEqualTo(f.shift.id)
        } finally { listener.cancel(); runCurrent() }
    }

    @Test fun `F1 cancellation propagates from read without error UI or next read`() = runTest {
        for (admin in listOf(false, true)) {
            val f = Fixture(); var reads = 0; var cancelledReads = 0
            val heldRead = CompletableDeferred<CajaLocalSnapshot?>()
            coEvery { f.repo.leerCajaLocal(any()) } coAnswers {
                reads++
                try { heldRead.await() }
                catch (cancelled: CancellationException) { cancelledReads++; throw cancelled }
            }
            val before = f.manager.operationalState.value
            val listener = f.start(backgroundScope, admin); runCurrent()
            if (!admin) { f.events.emit(Unit); runCurrent() }
            assertThat(reads).isEqualTo(1)
            listener.cancel(); runCurrent()
            assertThat(listener.isCancelled).isTrue(); assertThat(f.escaped).isNull()
            assertThat(cancelledReads).isEqualTo(1)
            assertThat(f.manager.operationalState.value).isSameInstanceAs(before); assertThat(f.adminError).isNull()
            f.events.emit(Unit); runCurrent(); assertThat(reads).isEqualTo(1)
        }
    }

    @Test fun `F1 local error text is connected and HTTP count and explicit auth remain intact`() = runTest {
        for (count in listOf(0, 2, 3)) {
            val f = Fixture(); repeat(count) { f.manager.handleNetworkError() }; var fail = true
            coEvery { f.repo.leerCajaLocal(any()) } coAnswers { if (fail) error("read failed"); f.snapshot() }
            val listener = f.start(backgroundScope); runCurrent()
            try {
                f.events.emit(Unit); runCurrent()
                val reason = requireNotNull(f.manager.operationalState.value.getUnavailableReason())
                assertThat(reason.name).isEqualTo("LOCAL_CAJA_READ_ERROR")
                val text = getUnavailableMessages(reason)
                assertThat(text.titleEs).isEqualTo("No se pudo leer la caja")
                assertThat(text.subtitleEs).contains("Administración")
                assertThat(f.manager.canAcceptNewOrders()).isFalse()
                assertThat(kioskAdminCajaTitle(false, null, null, text.titleEs)).isEqualTo("Caja sin consultar")
                fail = false; f.events.emit(Unit); runCurrent()
                assertThat(f.manager.operationalState.value).isInstanceOf(
                    if (count >= 3) KioskOperationalState.NetworkError::class.java else KioskOperationalState.Ready::class.java)
                if (count == 2) {
                    f.manager.handleNetworkError()
                    assertThat(f.manager.operationalState.value).isInstanceOf(KioskOperationalState.NetworkError::class.java)
                }
            } finally { listener.cancel(); runCurrent() }
        }
        val f = Fixture(); f.manager.handleAuthExpired()
        coEvery { f.repo.leerCajaLocal(any()) } throws IllegalStateException("local read failed")
        val listener = f.start(backgroundScope); runCurrent()
        try {
            f.events.emit(Unit); runCurrent()
            assertThat(f.manager.operationalState.value).isInstanceOf(KioskOperationalState.AuthExpired::class.java)
            f.manager.exitKioskMode(); f.events.emit(Unit); runCurrent()
            assertThat(f.manager.operationalState.value).isInstanceOf(KioskOperationalState.Disabled::class.java)
        } finally { listener.cancel(); runCurrent() }
    }

    @Test fun `F1 periodic capture config reads and failing error guard stay contained without HTTP inference`() = runTest {
        for (point in listOf("capture", "enabled", "room", "guard", "fresh")) {
            val f = Fixture(); var reads = 0
            when (point) { "capture" -> f.badCapture = "token"; "enabled" -> f.badCapture = "enabled" }
            coEvery { f.repo.leerCajaLocal(any()) } coAnswers {
                reads++
                if (point == "guard") f.badGuard = IllegalStateException("guard failed")
                if (point in listOf("room", "guard") || point == "fresh" && reads == 2) error("local read failed")
                f.snapshot()
            }
            val before = f.manager.operationalState.value
            checkCurrentKioskCajaContext(f.repo, f.manager, f.storage)
            if (point in listOf("capture", "guard")) assertThat(f.manager.operationalState.value).isSameInstanceAs(before)
            else assertThat(f.manager.operationalState.value.getUnavailableReason()?.name).isEqualTo("LOCAL_CAJA_READ_ERROR")
            coVerify(exactly = if (point == "fresh") 1 else 0) { f.repo.getCurrentShift(any()) }
            assertThat(f.manager.operationalState.value).isNotInstanceOf(KioskOperationalState.NetworkError::class.java)
        }
    }

    @Test fun `F1 Admin late error preserves current actor venue token exit and reentry`() = runTest {
        for (change in listOf("staff", "venue", "token", "exit", "reentry")) {
            val f = Fixture(); val release = CompletableDeferred<Unit>()
            coEvery { f.repo.leerCajaLocal(any()) } coAnswers { release.await(); error("late Admin A failed") }
            val listener = f.start(backgroundScope, admin = true); runCurrent()
            try {
                when (change) {
                    "staff" -> f.staff = "B"
                    "venue" -> f.venue = "B"
                    "token" -> f.token = "B"
                    "exit" -> f.manager.exitKioskMode()
                    "reentry" -> { f.manager.exitKioskMode(); f.manager.enterKioskMode("venue") }
                }
                if (change !in listOf("exit", "reentry")) f.manager.updateShiftStatus(false)
                val before = f.manager.operationalState.value
                release.complete(Unit); runCurrent()
                assertThat(f.manager.operationalState.value).isSameInstanceAs(before)
                assertThat(f.adminShift).isNull(); assertThat(f.adminError).isNull()
                assertThat(listener.isActive).isTrue(); assertThat(f.escaped).isNull()
            } finally { listener.cancel(); runCurrent() }
        }
    }

    @Test fun `F1 cancellation in error guard propagates without publishing local error`() = runTest {
        for (admin in listOf(false, true)) {
            val f = Fixture(); var reads = 0
            coEvery { f.repo.leerCajaLocal(any()) } coAnswers {
                reads++; f.badGuard = CancellationException("context cancelled"); error("local read failed")
            }
            val before = f.manager.operationalState.value
            val listener = f.start(backgroundScope, admin); runCurrent()
            if (!admin) { f.events.emit(Unit); runCurrent() }
            assertThat(f.manager.operationalState.value).isSameInstanceAs(before)
            assertThat(f.adminError).isNull(); assertThat(f.escaped).isNull()
            if (!admin) assertThat(listener.isCancelled).isTrue()
            // collectLatest child cancellation is not a screen-exit signal. Cancel its actual listener.
            listener.cancel(); runCurrent(); f.badGuard = null
            f.events.emit(Unit); runCurrent(); assertThat(reads).isEqualTo(1)
        }
    }
}

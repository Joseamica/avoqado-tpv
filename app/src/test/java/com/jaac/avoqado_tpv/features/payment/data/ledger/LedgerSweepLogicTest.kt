package com.jaac.avoqado_tpv.features.payment.data.ledger

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.observability.ObservabilityManager
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentLedgerMode
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the shadow-sweep pure logic (Task 6) — no WorkManager, no Robolectric.
 *
 * The sweep NEVER records payments, NEVER discards by absence, NEVER touches
 * pending_payments. In SHADOW it observes/logs open rows plus three internal
 * bookkeeping transitions (quarantine stale AUTORIZANDO, close old REGISTRADO,
 * prune terminal rows). INDETERMINADO is never deleted (DAO prune SQL only
 * covers CERRADA/DESCARTADA).
 */
class LedgerSweepLogicTest {

    private val dao = mockk<PaymentAttemptDao>(relaxed = true)
    private val observability = mockk<ObservabilityManager>(relaxed = true)
    private val settingsRepository = mockk<TpvSettingsRepository>()
    private val secureStorage = mockk<SecureStorage>()

    private val venueId = "venue-1"
    private val now = 1_700_000_000_000L

    private val tenMinutesMs = 10L * 60_000L
    private val twentyFourHoursMs = 24L * 3_600_000L
    private val sevenDaysMs = 7L * 86_400_000L

    private fun row(
        attemptId: String = "a1",
        state: String = PaymentAttemptEntity.STATE_AUTORIZANDO,
        processor: String = PaymentAttemptEntity.PROCESSOR_BLUMON,
        amountCents: Long = 10_000L,
        operationId: String? = null,
        createdAt: Long = now - 20L * 60_000L // 20 min old
    ) = PaymentAttemptEntity(
        attemptId = attemptId,
        venueId = venueId,
        processor = processor,
        state = state,
        amountCents = amountCents,
        tipCents = 0L,
        recordingRoute = PaymentAttemptEntity.ROUTE_FAST,
        paymentContextJson = "{}",
        operationId = operationId,
        createdAt = createdAt,
        updatedAt = createdAt
    )

    // ── sweepOnce: bookkeeping transitions with the spec thresholds ──

    @Test
    fun `quarantines stale AUTORIZANDO rows past the 10-minute threshold`() = runTest {
        coEvery { dao.quarantineStaleAuthorizing(any(), any(), any()) } returns 3
        val result = LedgerSweepLogic.sweepOnce(dao, venueId, observability, now)
        coVerify(exactly = 1) { dao.quarantineStaleAuthorizing(venueId, now - tenMinutesMs, now) }
        assertEquals(3, result.quarantined)
    }

    @Test
    fun `closes REGISTRADO rows older than 24 hours`() = runTest {
        coEvery { dao.closeRecordedOlderThan(any(), any(), any()) } returns 2
        val result = LedgerSweepLogic.sweepOnce(dao, venueId, observability, now)
        coVerify(exactly = 1) { dao.closeRecordedOlderThan(venueId, now - twentyFourHoursMs, now) }
        assertEquals(2, result.closed)
    }

    @Test
    fun `prunes terminal rows older than 7 days`() = runTest {
        coEvery { dao.pruneTerminalOlderThan(any(), any()) } returns 5
        val result = LedgerSweepLogic.sweepOnce(dao, venueId, observability, now)
        coVerify(exactly = 1) { dao.pruneTerminalOlderThan(venueId, now - sevenDaysMs) }
        assertEquals(5, result.pruned)
    }

    // ── sweepOnce: shadow observability of open rows ──

    @Test
    fun `scans open rows with OPEN_STATES and the stale cutoff`() = runTest {
        LedgerSweepLogic.sweepOnce(dao, venueId, observability, now)
        coVerify(exactly = 1) {
            dao.getOpenOlderThan(venueId, PaymentAttemptEntity.OPEN_STATES, now - tenMinutesMs)
        }
    }

    @Test
    fun `logs exactly one observability warning per open row`() = runTest {
        coEvery { dao.getOpenOlderThan(any(), any(), any()) } returns listOf(
            row("a1"),
            row("a2", state = PaymentAttemptEntity.STATE_REGISTRO_FALLIDO, operationId = "op-9")
        )
        val result = LedgerSweepLogic.sweepOnce(dao, venueId, observability, now)
        verify(exactly = 2) { observability.logWarning("LibretaShadowOpenRow", any(), any()) }
        assertEquals(2, result.openRowsLogged)
    }

    @Test
    fun `open-row log metadata carries attemptId state processor amountCents ageMinutes`() = runTest {
        coEvery { dao.getOpenOlderThan(any(), any(), any()) } returns listOf(
            row(
                attemptId = "att-42",
                state = PaymentAttemptEntity.STATE_HOST_RESPONDIO,
                processor = PaymentAttemptEntity.PROCESSOR_ANGELPAY,
                amountCents = 150_50L,
                operationId = "op-1",
                createdAt = now - 30L * 60_000L
            )
        )
        val captured = mutableListOf<Map<String, Any?>>()
        LedgerSweepLogic.sweepOnce(dao, venueId, observability, now)
        verify { observability.logWarning("LibretaShadowOpenRow", any(), capture(captured)) }
        val metadata = captured.single()
        assertEquals("att-42", metadata["attemptId"])
        assertEquals(PaymentAttemptEntity.STATE_HOST_RESPONDIO, metadata["state"])
        assertEquals(PaymentAttemptEntity.PROCESSOR_ANGELPAY, metadata["processor"])
        assertEquals(150_50L, metadata["amountCents"])
        assertEquals(30L, metadata["ageMinutes"])
    }

    @Test
    fun `null operationId is tagged never_launched and non-null as in_flight_evidence`() = runTest {
        // Addendum #1: AUTORIZANDO + operation_id NULL = never-launched signature
        // (Blumon posId-null early-return / AngelPay pre-launch failure) → low priority.
        coEvery { dao.getOpenOlderThan(any(), any(), any()) } returns listOf(
            row("never", operationId = null),
            row("flew", operationId = "op-7")
        )
        val captured = mutableListOf<Map<String, Any?>>()
        LedgerSweepLogic.sweepOnce(dao, venueId, observability, now)
        verify(exactly = 2) { observability.logWarning("LibretaShadowOpenRow", any(), capture(captured)) }
        val bySignature = captured.associateBy { it["attemptId"] }
        assertEquals("never_launched", bySignature.getValue("never")["signature"])
        assertEquals("in_flight_evidence", bySignature.getValue("flew")["signature"])
    }

    @Test
    fun `observes open rows BEFORE any bookkeeping mutation`() = runTest {
        // The shadow log must capture rows as found — a stale AUTORIZANDO row is
        // logged as AUTORIZANDO, not as the INDETERMINADO the quarantine turns it into.
        coEvery { dao.getOpenOlderThan(any(), any(), any()) } returns listOf(row("a1"))
        LedgerSweepLogic.sweepOnce(dao, venueId, observability, now)
        coVerifyOrder {
            dao.getOpenOlderThan(any(), any(), any())
            dao.quarantineStaleAuthorizing(any(), any(), any())
        }
    }

    @Test
    fun `INDETERMINADO rows are observed and logged but never deleted`() = runTest {
        coEvery { dao.getOpenOlderThan(any(), any(), any()) } returns listOf(
            row("stuck", state = PaymentAttemptEntity.STATE_INDETERMINADO, operationId = null)
        )
        LedgerSweepLogic.sweepOnce(dao, venueId, observability, now)
        // INDETERMINADO is part of the watched OPEN_STATES…
        assertTrue(PaymentAttemptEntity.OPEN_STATES.contains(PaymentAttemptEntity.STATE_INDETERMINADO))
        verify {
            observability.logWarning(
                "LibretaShadowOpenRow", any(),
                match { it["state"] == PaymentAttemptEntity.STATE_INDETERMINADO }
            )
        }
        // …and the ONLY delete the sweep ever issues is the terminal prune
        // (whose DAO SQL covers CERRADA/DESCARTADA exclusively).
        coVerify(exactly = 1) { dao.pruneTerminalOlderThan(any(), any()) }
    }

    @Test
    fun `no open rows means no observability logs`() = runTest {
        coEvery { dao.getOpenOlderThan(any(), any(), any()) } returns emptyList()
        val result = LedgerSweepLogic.sweepOnce(dao, venueId, observability, now)
        verify { observability wasNot Called }
        assertEquals(0, result.openRowsLogged)
    }

    // ── runGated: the worker's gate, JVM-testable without WorkManager ──

    @Test
    fun `mode OFF returns null with ZERO dao calls`() = runTest {
        every { settingsRepository.getCurrentSettings() } returns
            TpvSettings.DEFAULT.copy(paymentLedgerMode = PaymentLedgerMode.OFF)
        val result = LedgerSweepLogic.runGated(settingsRepository, secureStorage, dao, observability, now)
        assertNull(result)
        verify { dao wasNot Called }
        verify { secureStorage wasNot Called }
    }

    @Test
    fun `null venueId returns null with zero dao calls`() = runTest {
        every { settingsRepository.getCurrentSettings() } returns
            TpvSettings.DEFAULT.copy(paymentLedgerMode = PaymentLedgerMode.SHADOW)
        every { secureStorage.getVenueId() } returns null
        val result = LedgerSweepLogic.runGated(settingsRepository, secureStorage, dao, observability, now)
        assertNull(result)
        verify { dao wasNot Called }
    }

    @Test
    fun `SHADOW mode with venue runs the sweep`() = runTest {
        every { settingsRepository.getCurrentSettings() } returns
            TpvSettings.DEFAULT.copy(paymentLedgerMode = PaymentLedgerMode.SHADOW)
        every { secureStorage.getVenueId() } returns venueId
        coEvery { dao.quarantineStaleAuthorizing(any(), any(), any()) } returns 1
        val result = LedgerSweepLogic.runGated(settingsRepository, secureStorage, dao, observability, now)
        assertEquals(1, result?.quarantined)
        coVerify(exactly = 1) { dao.getOpenOlderThan(venueId, PaymentAttemptEntity.OPEN_STATES, now - tenMinutesMs) }
    }

    @Test
    fun `ACTIVE mode also runs the sweep`() = runTest {
        every { settingsRepository.getCurrentSettings() } returns
            TpvSettings.DEFAULT.copy(paymentLedgerMode = PaymentLedgerMode.ACTIVE)
        every { secureStorage.getVenueId() } returns venueId
        val result = LedgerSweepLogic.runGated(settingsRepository, secureStorage, dao, observability, now)
        assertEquals(LedgerSweepLogic.SweepResult(0, 0, 0, 0), result)
        coVerify(exactly = 1) { dao.pruneTerminalOlderThan(venueId, now - sevenDaysMs) }
    }
}

package com.jaac.avoqado_tpv.features.payment.data.ledger

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class BlumonAttemptResolverTest {
    private val ledger = mockk<PaymentAttemptLedger>(relaxed = true)
    private val recovery = mockk<LedgerServerRecovery>()
    private val api = mockk<TerminalAttemptApiService>()
    private val storage = mockk<SecureStorage>()
    private lateinit var resolver: BlumonAttemptResolver
    private var row = PaymentAttemptEntity(
        attemptId = "pax", venueId = "venue", processor = "BLUMON", state = "INDETERMINADO",
        amountCents = 10000, tipCents = 0, recordingRoute = "FAST", paymentContextJson = "{}",
        createdAt = 1, updatedAt = 2,
    )

    @Before fun setUp() {
        every { storage.getVenueId() } returns "venue"
        every { storage.getStaffId() } returns "manager"
        every { storage.getString("permissions_cache", null) } returns "venue|manager\npayments:resolve-no-instrument"
        coEvery { ledger.leerIntento("pax") } answers { row }
        every { ledger.tieneEvidenciaSinGuardar(any()) } returns false
        every { ledger.puedeConciliarBlumon(any(), any()) } returns true
        coEvery { recovery.recoverOne("venue", "pax", any(), false) } returns
            LedgerServerRecovery.LecturaDelIntento(null, servidorContesto = true)
        resolver = BlumonAttemptResolver(ledger, recovery, api, storage)
    }

    @Test fun `consultation publishes unresolved PAX without claiming absence of a charge`() = runTest {
        val result = resolver.consult("venue", "pax")
        assertThat(result.state).isEqualTo(BlumonAttemptResolver.State.PENDING)
        assertThat(result.row?.attemptId).isEqualTo("pax")
        assertThat(result.serverAnswered).isTrue()
        coVerify(exactly = 0) { api.resolveNoInstrument(any(), any(), any()) }
    }

    @Test fun `a money veto from S6 wins over an older released row even if saving it failed`() = runTest {
        row = row.copy(state = "DESCARTADA", serverOutcome = "OPERATOR_NO_INSTRUMENT")
        coEvery { recovery.recoverOne("venue", "pax", any(), false) } returns
            LedgerServerRecovery.LecturaDelIntento(null, evidenciaPositivaSinRegistro = true, servidorContesto = true)
        assertThat(resolver.consult("venue", "pax").state).isEqualTo(BlumonAttemptResolver.State.MONEY_EVIDENCE)
    }

    @Test fun `declaration rechecks effective permission before posting`() = runTest {
        every { storage.getString("permissions_cache", null) } returns "venue|manager\npayments:read"
        assertThat(resolver.declare("venue", "pax").state).isEqualTo(BlumonAttemptResolver.State.PERMISSION_REQUIRED)
        coVerify(exactly = 0) { api.resolveNoInstrument(any(), any(), any()) }
    }

    @Test fun `declaration refuses a live SDK attempt`() = runTest {
        row = row.copy(state = "AUTORIZANDO")
        assertThat(resolver.declare("venue", "pax").state).isEqualTo(BlumonAttemptResolver.State.UNAVAILABLE)
        coVerify(exactly = 0) { api.resolveNoInstrument(any(), any(), any()) }
    }

    @Test fun `declaration never announces release when the durable write failed`() = runTest {
        coEvery { api.resolveNoInstrument("venue", "pax", any()) } returns Response.success(
            TerminalAttemptStatusResponse(success = true, attemptId = "pax", attempt = TerminalAttemptResultDto(
                attemptId = "pax", outcome = "NOT_RECORDED",
            )),
        )
        coEvery { ledger.aplicarLiberacionDelServidor(any(), any()) } returns Result.success(false)
        assertThat(resolver.declare("venue", "pax").state).isEqualTo(BlumonAttemptResolver.State.PENDING)
        assertThat(row.state).isEqualTo("INDETERMINADO")
    }

    @Test fun `local and POS declarations preserve their saved request identity and stable replay id`() = runTest {
        val bodies = mutableListOf<NoInstrumentResolutionRequest>()
        coEvery { api.resolveNoInstrument("venue", "pax", any()) } coAnswers {
            bodies += thirdArg<NoInstrumentResolutionRequest>()
            throw java.io.IOException("connection lost")
        }
        resolver.declare("venue", "pax")
        resolver = BlumonAttemptResolver(ledger, recovery, api, storage)
        resolver.declare("venue", "pax")
        assertThat(bodies).hasSize(2)
        assertThat(bodies[0].requestId).isNull()
        assertThat(bodies[1].resolutionId).isEqualTo(bodies[0].resolutionId)
        row = row.copy(terminalPaymentRequestId = "pos-request")
        resolver.declare("venue", "pax")
        assertThat(bodies.last().requestId).isEqualTo("pos-request")
    }
    @Test fun `positive evidence in a declaration vetoes release and is saved`() = runTest {
        coEvery { api.resolveNoInstrument("venue", "pax", any()) } returns Response.success(
            TerminalAttemptStatusResponse(success = true, attemptId = "pax", attempt = TerminalAttemptResultDto(
                attemptId = "pax", outcome = "NOT_RECORDED", processorEvidence = "APPROVED",
            )),
        )
        coEvery { ledger.marcarEvidenciaPositivaDelServidor("venue", "pax", any()) } returns Result.success(true)
        assertThat(resolver.declare("venue", "pax").state).isEqualTo(BlumonAttemptResolver.State.MONEY_EVIDENCE)
        coVerify { ledger.marcarEvidenciaPositivaDelServidor("venue", "pax", any()) }
        coVerify(exactly = 0) { ledger.aplicarLiberacionDelServidor(any(), any()) }
    }

    @Test fun `an unrelated successful response cannot release the PAX`() = runTest {
        for ((topId, nestedId, requestId) in listOf(
            Triple("other", "pax", null), Triple("pax", "other", null), Triple("pax", "pax", "other-request"),
        )) {
            coEvery { api.resolveNoInstrument("venue", "pax", any()) } returns Response.success(
                TerminalAttemptStatusResponse(success = true, attemptId = topId, requestId = requestId,
                    attempt = TerminalAttemptResultDto(attemptId = nestedId, outcome = "NOT_RECORDED")),
            )
            assertThat(resolver.declare("venue", "pax").state).isEqualTo(BlumonAttemptResolver.State.PENDING)
        }
        coVerify(exactly = 0) { ledger.aplicarLiberacionDelServidor(any(), any()) }
    }

    @Test fun `a bank veto in the HTTP conflict stays blocked if the followup query fails`() = runTest {
        coEvery { api.resolveNoInstrument("venue", "pax", any()) } returns Response.error(409,
            okhttp3.ResponseBody.create(null, """{"code":"POSITIVE_EVIDENCE_EXISTS"}"""))
        coEvery { ledger.marcarEvidenciaPositivaDelServidor("venue", "pax", any()) } returns Result.failure(java.io.IOException())
        assertThat(resolver.declare("venue", "pax").state).isEqualTo(BlumonAttemptResolver.State.MONEY_EVIDENCE)
        assertThat(resolver.consult("venue", "pax").state).isEqualTo(BlumonAttemptResolver.State.MONEY_EVIDENCE)
        row = row.copy(state = "DESCARTADA", serverOutcome = "OPERATOR_NO_INSTRUMENT")
        assertThat(resolver.current("venue", "pax").state).isEqualTo(BlumonAttemptResolver.State.MONEY_EVIDENCE)
        coVerify(exactly = 0) { ledger.aplicarLiberacionDelServidor(any(), any()) }
    }

    @Test fun `the checked declaration never releases a POS request or an attempt Avoqado never answered`() = runTest {
        every { storage.getString("permissions_cache", null) } returns "venue|manager\npayments:reconcile-uncharged"
        coEvery { recovery.recoverOne("venue", "pax", any(), false) } returns LedgerServerRecovery.LecturaDelIntento(null)
        assertThat(resolver.declareChecked("venue", "pax").state).isEqualTo(BlumonAttemptResolver.State.PENDING)
        row = row.copy(serverAnsweredAt = 3, terminalPaymentRequestId = "pos-request")
        assertThat(resolver.declareChecked("venue", "pax").state).isEqualTo(BlumonAttemptResolver.State.PENDING)
        coVerify(exactly = 0) { ledger.declararSinCobroLocal(any(), any(), any()) }
    }

    @Test fun `the checked declaration asks the server first and closes a previously answered local attempt offline`() = runTest {
        every { storage.getString("permissions_cache", null) } returns "venue|manager\npayments:reconcile-uncharged"
        row = row.copy(serverAnsweredAt = 3)
        coEvery { recovery.recoverOne("venue", "pax", any(), false) } returns
            LedgerServerRecovery.LecturaDelIntento(null, servidorContesto = false)
        coEvery { ledger.declararSinCobroLocal("pax", "venue", "manager") } coAnswers {
            row = row.copy(state = "DESCARTADA", lastError = "declarado_sin_cobro:manager")
            true
        }
        assertThat(resolver.declareChecked("venue", "pax").state).isEqualTo(BlumonAttemptResolver.State.RELEASED)
        coVerify { recovery.recoverOne("venue", "pax", any(), false) }
    }

    /** Founder, 25-sep: el cajero deja constancia de que revisó, con o sin red (antes sólo sin red y sólo gerencia). */
    @Test fun `a cashier declares the checked terminal even when the server answers now`() = runTest {
        every { storage.getStaffId() } returns "cajero"
        every { storage.getString("permissions_cache", null) } returns "venue|cajero\npayments:reconcile-uncharged"
        row = row.copy(serverAnsweredAt = 3)
        coEvery { ledger.declararSinCobroLocal("pax", "venue", "cajero") } coAnswers {
            row = row.copy(state = "DESCARTADA", lastError = "declarado_sin_cobro:cajero")
            true
        }
        assertThat(resolver.declareChecked("venue", "pax").state).isEqualTo(BlumonAttemptResolver.State.RELEASED)
        coVerify { recovery.recoverOne("venue", "pax", any(), false) }
        coVerify(exactly = 0) { api.resolveNoInstrument(any(), any(), any()) }
    }

    @Test fun `the checked declaration needs payments reconcile-uncharged, not the no-card permission`() = runTest {
        // Son dos afirmaciones distintas: «no presentó tarjeta» (gerencia) no autoriza «revisé y no se cobró».
        row = row.copy(serverAnsweredAt = 3)
        assertThat(resolver.declareChecked("venue", "pax").state).isEqualTo(BlumonAttemptResolver.State.PERMISSION_REQUIRED)
        coVerify(exactly = 0) { ledger.declararSinCobroLocal(any(), any(), any()) }
    }

    @Test fun `the checked declaration refuses when Avoqado has money evidence`() = runTest {
        every { storage.getString("permissions_cache", null) } returns "venue|manager\npayments:reconcile-uncharged"
        row = row.copy(serverAnsweredAt = 3)
        coEvery { recovery.recoverOne("venue", "pax", any(), false) } returns
            LedgerServerRecovery.LecturaDelIntento(null, evidenciaPositivaSinRegistro = true, servidorContesto = true)
        assertThat(resolver.declareChecked("venue", "pax").state).isEqualTo(BlumonAttemptResolver.State.MONEY_EVIDENCE)
        coVerify(exactly = 0) { ledger.declararSinCobroLocal(any(), any(), any()) }
    }

    @Test fun `the checked declaration rechecks permission after the suspended consultation`() = runTest {
        every { storage.getString("permissions_cache", null) } returns "venue|manager\npayments:reconcile-uncharged"
        row = row.copy(serverAnsweredAt = 3)
        coEvery { recovery.recoverOne("venue", "pax", any(), false) } coAnswers {
            every { storage.getString("permissions_cache", null) } returns "venue|manager\npayments:read"
            LedgerServerRecovery.LecturaDelIntento(null)
        }
        assertThat(resolver.declareChecked("venue", "pax").state).isEqualTo(BlumonAttemptResolver.State.PERMISSION_REQUIRED)
        coVerify(exactly = 0) { ledger.declararSinCobroLocal(any(), any(), any()) }
    }

    @Test fun `server denial cannot be bypassed by the checked declaration`() = runTest {
        every { storage.getString("permissions_cache", null) } returns
            "venue|manager\npayments:resolve-no-instrument,payments:reconcile-uncharged"
        row = row.copy(serverAnsweredAt = 3)
        coEvery { api.resolveNoInstrument("venue", "pax", any()) } returns Response.error(403,
            okhttp3.ResponseBody.create(null, """{"code":"SUPERVISOR_AUTHORIZATION_REQUIRED"}"""))
        assertThat(resolver.declare("venue", "pax").state).isEqualTo(BlumonAttemptResolver.State.PERMISSION_REQUIRED)
        assertThat(resolver.declareChecked("venue", "pax").state).isEqualTo(BlumonAttemptResolver.State.PERMISSION_REQUIRED)
        coVerify(exactly = 0) { ledger.declararSinCobroLocal(any(), any(), any()) }
    }

    @Test fun `a session from another venue cannot query or resolve the old PAX attempt`() = runTest {
        every { storage.getVenueId() } returns "another-venue"
        assertThat(resolver.consult("venue", "pax").state).isEqualTo(BlumonAttemptResolver.State.UNAVAILABLE)
        coVerify(exactly = 0) { recovery.recoverOne(any(), any(), any(), any()) }
        coVerify(exactly = 0) { api.resolveNoInstrument(any(), any(), any()) }
    }

    @Test fun `a server permission denial invalidates the cached grant across resolver recreation`() = runTest {
        var cached: String? = "venue|manager\npayments:resolve-no-instrument,payments:reconcile-uncharged"
        every { storage.getString("permissions_cache", null) } answers { cached }
        every { storage.remove("permissions_cache") } answers { cached = null }
        every { storage.remove("permissions_cache_timestamp") } returns Unit
        row = row.copy(serverAnsweredAt = 3)
        coEvery { recovery.recoverOne("venue", "pax", any(), false) } returns LedgerServerRecovery.LecturaDelIntento(null)
        coEvery { api.resolveNoInstrument("venue", "pax", any()) } returns Response.error(403,
            okhttp3.ResponseBody.create(null, """{"code":"SUPERVISOR_AUTHORIZATION_REQUIRED"}"""))
        resolver.declare("venue", "pax")
        resolver = BlumonAttemptResolver(ledger, recovery, api, storage)
        assertThat(resolver.declareChecked("venue", "pax").state).isEqualTo(BlumonAttemptResolver.State.PERMISSION_REQUIRED)
        io.mockk.verify { storage.remove("permissions_cache") }
        coVerify(exactly = 0) { ledger.declararSinCobroLocal(any(), any(), any()) }
    }

    @Test fun `a delayed denial for the old staff preserves the new staff permission cache`() = runTest {
        var staff = "manager"
        var cached = "venue|manager\npayments:resolve-no-instrument"
        every { storage.getStaffId() } answers { staff }
        every { storage.getString("permissions_cache", null) } answers { cached }
        coEvery { api.resolveNoInstrument("venue", "pax", any()) } coAnswers {
            staff = "new-manager"
            cached = "venue|new-manager\npayments:resolve-no-instrument"
            Response.error(403, okhttp3.ResponseBody.create(null, """{"code":"SUPERVISOR_AUTHORIZATION_REQUIRED"}"""))
        }
        resolver.declare("venue", "pax")
        assertThat(resolver.hasPermission()).isTrue()
        io.mockk.verify(exactly = 0) { storage.remove("permissions_cache") }
    }

    @Test fun `a fresh permission download restores a grant denied earlier`() = runTest {
        var cached: String? = "venue|manager\npayments:resolve-no-instrument"
        every { storage.getString("permissions_cache", null) } answers { cached }
        every { storage.remove("permissions_cache") } answers { cached = null }
        every { storage.remove("permissions_cache_timestamp") } returns Unit
        every { storage.putString("permissions_cache", any()) } answers { cached = secondArg() }
        every { storage.putLong(any(), any()) } returns Unit
        coEvery { api.resolveNoInstrument("venue", "pax", any()) } returns Response.error(403,
            okhttp3.ResponseBody.create(null, "{}"))
        resolver.declare("venue", "pax")
        val permissionsApi = mockk<com.jaac.avoqado_tpv.core.data.network.ApiService>()
        coEvery { permissionsApi.getStaffPermissions() } returns Response.success(
            com.jaac.avoqado_tpv.features.authentication.data.dto.StaffPermissionsResponse(true,
                com.jaac.avoqado_tpv.features.authentication.data.dto.StaffPermissionsData(
                    "manager", "venue", "MANAGER", listOf("payments:resolve-no-instrument"))))
        com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository(permissionsApi, storage)
            .getPermissions(forceRefresh = true)
        assertThat(resolver.hasPermission()).isTrue()
    }

    @Test fun `a permission response started before denial cannot restore the stale grant`() = runTest {
        var cached: String? = "venue|manager\npayments:resolve-no-instrument"
        every { storage.getString("permissions_cache", null) } answers { cached }
        every { storage.remove("permissions_cache") } answers { cached = null }
        every { storage.remove("permissions_cache_timestamp") } returns Unit
        every { storage.putString("permissions_cache", any()) } answers { cached = secondArg() }
        every { storage.putLong(any(), any()) } returns Unit
        val permissionsApi = mockk<com.jaac.avoqado_tpv.core.data.network.ApiService>()
        val pending = CompletableDeferred<Unit>()
        coEvery { permissionsApi.getStaffPermissions() } coAnswers {
            pending.await()
            Response.success(com.jaac.avoqado_tpv.features.authentication.data.dto.StaffPermissionsResponse(true,
                com.jaac.avoqado_tpv.features.authentication.data.dto.StaffPermissionsData(
                    "manager", "venue", "MANAGER", listOf("payments:resolve-no-instrument"))))
        }
        val download = async {
            com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository(permissionsApi, storage)
                .getPermissions(forceRefresh = true)
        }
        runCurrent()
        coEvery { api.resolveNoInstrument("venue", "pax", any()) } returns Response.error(403,
            okhttp3.ResponseBody.create(null, "{}"))
        resolver.declare("venue", "pax")
        pending.complete(Unit)
        assertThat(download.await().isFailure).isTrue()
        assertThat(com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
            .enLaUltimaListaEfectiva(storage, "payments:resolve-no-instrument")).isFalse()
    }

}

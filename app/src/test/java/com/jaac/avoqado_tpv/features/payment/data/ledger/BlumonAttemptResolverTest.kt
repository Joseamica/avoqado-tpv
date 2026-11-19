package com.jaac.avoqado_tpv.features.payment.data.ledger

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
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
    /** F1 (tarea 3): la firma del gerente. */
    private val pin = "918273"
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
        every { ledger.puedeDeclararseEnDuda(any(), any()) } returns true
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

    // ── F1 bloque 7: `declare` (la salida «no presentó tarjeta» sin PIN) ya no existe. Sus 13 pruebas, rehechas sobre `declareChecked` ──
    // (el gerente tiene UNA sola opción, «No se cobró», con su PIN). Las de la caché de permisos siguen: la cuida el 401/403 genérico.

    private fun httpError(code: Int, cuerpo: String) = Response.error<TerminalAttemptStatusResponse>(code, okhttp3.ResponseBody.create(null, cuerpo))

    /** Era «declaration rechecks effective permission before posting»: la puerta es el PIN del gerente; sin él no sale nada. */
    @Test fun `B7 sin PIN no sale nada aunque la sesion no tenga el permiso, y con PIN decide Avoqado`() = runTest {
        every { storage.getString("permissions_cache", null) } returns "venue|manager\npayments:read"
        row = row.copy(serverAnsweredAt = 3)
        assertThat(resolver.declareChecked("venue", "pax", null).state).isEqualTo(BlumonAttemptResolver.State.SIGNATURE_REQUIRED)
        coVerify(exactly = 0) { api.resolveNoInstrument(any(), any(), any()) }
        coEvery { api.resolveNoInstrument("venue", "pax", any()) } answers { httpError(403, """{"code":"SUPERVISOR_AUTHORIZATION_REQUIRED"}""") }
        assertThat(resolver.declareChecked("venue", "pax", pin).state).isEqualTo(BlumonAttemptResolver.State.SIGNATURE_REQUIRED)
        coVerify(exactly = 1) { api.resolveNoInstrument("venue", "pax", any()) }
        coVerify(exactly = 0) { ledger.declararSinCobroLocal(any(), any(), any(), any()) }
    }

    /** Era «declaration refuses a live SDK attempt»: con el SDK dentro (o que puede regresar) no se firma ni se manda nada. */
    @Test fun `B7 la firma rechaza un intento con el SDK vivo o que todavia puede regresar`() = runTest {
        row = row.copy(state = "AUTORIZANDO", serverAnsweredAt = 3)
        assertThat(resolver.declareChecked("venue", "pax", pin).state).isEqualTo(BlumonAttemptResolver.State.UNAVAILABLE)
        row = row.copy(state = "INDETERMINADO")
        every { ledger.puedeDeclararseEnDuda(any(), any()) } returns false
        assertThat(resolver.declareChecked("venue", "pax", pin).state).isEqualTo(BlumonAttemptResolver.State.PENDING)
        coVerify(exactly = 0) { api.resolveNoInstrument(any(), any(), any()) }
        coVerify(exactly = 0) { ledger.declararSinCobroLocal(any(), any(), any(), any()) }
    }

    /** Era «declaration never announces release when the durable write failed»: un acuse firmado sin CAS local no es «liberada». */
    @Test fun `B7 con el acuse firmado pero sin escritura local no se anuncia la liberacion`() = runTest {
        cajeroQueRevisa()
        guardadaEnAvoqado(mutableListOf())
        coEvery { ledger.declararSinCobroLocal("pax", "venue", any(), any()) } returns false
        val r = resolver.declareChecked("venue", "pax", pin)
        assertThat(r.state).isEqualTo(BlumonAttemptResolver.State.PENDING)
        assertThat(r.serverAnswered).isTrue()
        assertThat(row.state).isEqualTo("INDETERMINADO")
    }

    /** Era «local and POS declarations preserve their saved request identity and stable replay id». */
    @Test fun `B7 la firma conserva un solo id tras recrear el resolvedor y nunca sale para una solicitud del POS`() = runTest {
        cajeroQueRevisa()
        val bodies = mutableListOf<NoInstrumentResolutionRequest>()
        coEvery { api.resolveNoInstrument("venue", "pax", any()) } coAnswers {
            bodies += thirdArg<NoInstrumentResolutionRequest>()
            throw java.io.IOException("connection lost")
        }
        resolver.declareChecked("venue", "pax", pin)
        resolver = BlumonAttemptResolver(ledger, recovery, api, storage)
        resolver.declareChecked("venue", "pax", pin)
        assertThat(bodies).hasSize(2)
        assertThat(bodies.map { it.requestId }).containsExactly(null, null)
        assertThat(bodies[1]).isEqualTo(bodies[0])
        row = row.copy(terminalPaymentRequestId = "pos-request")
        resolver.declareChecked("venue", "pax", pin)
        assertThat(bodies).hasSize(2)
    }

    /** Era «positive evidence in a declaration vetoes release and is saved»: el dinero del CUERPO sí es aprobación y se guarda. */
    @Test fun `B7 un 2xx con dinero del intento no libera y guarda la evidencia`() = runTest {
        cajeroQueRevisa()
        coEvery { api.resolveNoInstrument("venue", "pax", any()) } returns Response.success(
            TerminalAttemptStatusResponse(success = true, attemptId = "pax", attempt = TerminalAttemptResultDto(
                attemptId = "pax", outcome = "NOT_RECORDED", processorEvidence = "APPROVED",
            )),
        )
        coEvery { ledger.marcarEvidenciaPositivaDelServidor("venue", "pax", any()) } returns Result.success(true)
        assertThat(resolver.declareChecked("venue", "pax", pin).state).isEqualTo(BlumonAttemptResolver.State.MONEY_EVIDENCE)
        coVerify { ledger.marcarEvidenciaPositivaDelServidor("venue", "pax", any()) }
        coVerify(exactly = 0) { ledger.declararSinCobroLocal(any(), any(), any(), any()) }
        coVerify(exactly = 0) { ledger.aplicarLiberacionDelServidor(any(), any()) }
    }

    /** Era «an unrelated successful response cannot release the PAX». */
    @Test fun `B7 un 2xx de otro intento o de otra solicitud no libera`() = runTest {
        cajeroQueRevisa()
        for ((topId, nestedId, requestId) in listOf(
            Triple("other", "pax", null), Triple("pax", "other", null), Triple("pax", "pax", "other-request"),
        )) {
            coEvery { api.resolveNoInstrument("venue", "pax", any()) } coAnswers {
                val id = thirdArg<NoInstrumentResolutionRequest>().resolutionId
                val resolution = com.google.gson.JsonObject().apply {
                    addProperty("kind", "UNCHARGED_VERIFIED"); addProperty("id", id); addProperty("staffId", "gerente-1"); addProperty("by", "SUPERVISOR_PIN")
                }
                Response.success(TerminalAttemptStatusResponse(success = true, attemptId = topId, requestId = requestId,
                    attempt = TerminalAttemptResultDto(attemptId = nestedId, outcome = "NOT_RECORDED", resolution = resolution)))
            }
            assertThat(resolver.declareChecked("venue", "pax", pin).state).isEqualTo(BlumonAttemptResolver.State.PENDING)
        }
        coVerify(exactly = 0) { ledger.declararSinCobroLocal(any(), any(), any(), any()) }
    }

    /** Era «a bank veto in the HTTP conflict stays blocked if the followup query fails»: ahora el 409 es un impedimento. */
    @Test fun `B7 el 409 sigue frenando aunque su impedimento no se pueda escribir`() = runTest {
        cajeroQueRevisa()
        coEvery { api.resolveNoInstrument("venue", "pax", any()) } answers { httpError(409, """{"code":"POSITIVE_EVIDENCE_EXISTS"}""") }
        coEvery { ledger.marcarImpedimentoDelServidor("venue", "pax", any()) } returns Result.failure(java.io.IOException())
        assertThat(resolver.declareChecked("venue", "pax", pin).state).isEqualTo(BlumonAttemptResolver.State.MONEY_EVIDENCE)
        assertThat(resolver.consult("venue", "pax").state).isEqualTo(BlumonAttemptResolver.State.MONEY_EVIDENCE)
        row = row.copy(state = "DESCARTADA", serverOutcome = "OPERATOR_NO_INSTRUMENT")
        assertThat(resolver.current("venue", "pax").state).isEqualTo(BlumonAttemptResolver.State.MONEY_EVIDENCE)
        coVerify(exactly = 0) { ledger.marcarEvidenciaPositivaDelServidor(any(), any(), any()) }
        coVerify(exactly = 0) { ledger.declararSinCobroLocal(any(), any(), any(), any()) }
        coVerify(exactly = 0) { ledger.aplicarLiberacionDelServidor(any(), any()) }
    }

    /** Era «server denial cannot be bypassed by the checked declaration»: el 403 del PIN pide otra firma y no toca la caché. */
    @Test fun `B7 el 403 del PIN no se salta y no borra la lista de permisos de quien opera`() = runTest {
        cajeroQueRevisa()
        coEvery { api.resolveNoInstrument("venue", "pax", any()) } answers { httpError(403, """{"code":"SUPERVISOR_AUTHORIZATION_REQUIRED"}""") }
        assertThat(resolver.declareChecked("venue", "pax", pin).state).isEqualTo(BlumonAttemptResolver.State.SIGNATURE_REQUIRED)
        io.mockk.verify(exactly = 0) { storage.remove("permissions_cache") }
        coVerify(exactly = 0) { ledger.declararSinCobroLocal(any(), any(), any(), any()) }
    }

    /** Era «a server permission denial invalidates the cached grant across resolver recreation». */
    @Test fun `B7 un 403 de la sesion invalida la lista guardada tambien tras recrear el resolvedor`() = runTest {
        var cached: String? = "venue|manager\npayments:resolve-no-instrument,payments:reconcile-uncharged"
        every { storage.getString("permissions_cache", null) } answers { cached }
        every { storage.remove("permissions_cache") } answers { cached = null }
        every { storage.remove("permissions_cache_timestamp") } returns Unit
        row = row.copy(serverAnsweredAt = 3)
        coEvery { api.resolveNoInstrument("venue", "pax", any()) } answers { httpError(403, """{"code":"SESSION_NOT_IN_VENUE"}""") }
        assertThat(resolver.declareChecked("venue", "pax", pin).state).isEqualTo(BlumonAttemptResolver.State.PERMISSION_REQUIRED)
        resolver = BlumonAttemptResolver(ledger, recovery, api, storage)
        assertThat(resolver.hasPermission()).isFalse()
        assertThat(resolver.declareChecked("venue", "pax", pin).state).isNotEqualTo(BlumonAttemptResolver.State.RELEASED)
        io.mockk.verify { storage.remove("permissions_cache") }
        coVerify(exactly = 0) { ledger.declararSinCobroLocal(any(), any(), any(), any()) }
    }

    /** Era «a delayed denial for the old staff preserves the new staff permission cache». */
    @Test fun `B7 un 403 tardio de quien operaba antes no borra la lista de quien opera ahora`() = runTest {
        var staff = "manager"
        var cached = "venue|manager\npayments:resolve-no-instrument"
        every { storage.getStaffId() } answers { staff }
        every { storage.getString("permissions_cache", null) } answers { cached }
        row = row.copy(serverAnsweredAt = 3)
        coEvery { api.resolveNoInstrument("venue", "pax", any()) } coAnswers {
            staff = "new-manager"
            cached = "venue|new-manager\npayments:resolve-no-instrument"
            httpError(403, "{}")
        }
        resolver.declareChecked("venue", "pax", pin)
        assertThat(resolver.hasPermission()).isTrue()
        io.mockk.verify(exactly = 0) { storage.remove("permissions_cache") }
    }

    /** Era «a fresh permission download restores a grant denied earlier». */
    @Test fun `B7 una lista nueva de permisos devuelve lo que un 403 anterior quito`() = runTest {
        var cached: String? = "venue|manager\npayments:resolve-no-instrument"
        every { storage.getString("permissions_cache", null) } answers { cached }
        every { storage.remove("permissions_cache") } answers { cached = null }
        every { storage.remove("permissions_cache_timestamp") } returns Unit
        every { storage.putString("permissions_cache", any()) } answers { cached = secondArg() }
        every { storage.putLong(any(), any()) } returns Unit
        row = row.copy(serverAnsweredAt = 3)
        coEvery { api.resolveNoInstrument("venue", "pax", any()) } answers { httpError(403, "{}") }
        resolver.declareChecked("venue", "pax", pin)
        assertThat(resolver.hasPermission()).isFalse()
        val permissionsApi = mockk<com.jaac.avoqado_tpv.core.data.network.ApiService>()
        coEvery { permissionsApi.getStaffPermissions() } returns Response.success(
            com.jaac.avoqado_tpv.features.authentication.data.dto.StaffPermissionsResponse(true,
                com.jaac.avoqado_tpv.features.authentication.data.dto.StaffPermissionsData(
                    "manager", "venue", "MANAGER", listOf("payments:resolve-no-instrument"))))
        com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository(permissionsApi, storage)
            .getPermissions(forceRefresh = true)
        assertThat(resolver.hasPermission()).isTrue()
    }

    /** Era «the PAX screen does not change - it still only sees PAX attempts» (la firma revisada sí ve la Nexgo, a propósito). */
    @Test fun `B7 la pantalla de la PAX sigue viendo solo intentos de la PAX`() = runTest {
        row = row.copy(processor = "ANGELPAY", serverAnsweredAt = 3)
        assertThat(resolver.consult("venue", "pax").state).isEqualTo(BlumonAttemptResolver.State.UNAVAILABLE)
        assertThat(resolver.current("venue", "pax").state).isEqualTo(BlumonAttemptResolver.State.UNAVAILABLE)
        coVerify(exactly = 0) { recovery.recoverOne(any(), any(), any(), any()) }
        coVerify(exactly = 0) { api.resolveNoInstrument(any(), any(), any()) }
        every { ledger.observarIntento("pax", "venue") } returns kotlinx.coroutines.flow.flowOf(row)
        assertThat(resolver.observe("venue", "pax").first().state).isEqualTo(BlumonAttemptResolver.State.UNAVAILABLE)
    }

    /** Era «a permission response started before denial cannot restore the stale grant». */
    @Test fun `B7 una descarga de permisos que empezo antes del 403 no devuelve la lista vieja`() = runTest {
        var cached: String? = "venue|manager\npayments:resolve-no-instrument"
        every { storage.getString("permissions_cache", null) } answers { cached }
        every { storage.remove("permissions_cache") } answers { cached = null }
        every { storage.remove("permissions_cache_timestamp") } returns Unit
        every { storage.putString("permissions_cache", any()) } answers { cached = secondArg() }
        every { storage.putLong(any(), any()) } returns Unit
        row = row.copy(serverAnsweredAt = 3)
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
        coEvery { api.resolveNoInstrument("venue", "pax", any()) } answers { httpError(403, "{}") }
        resolver.declareChecked("venue", "pax", pin)
        pending.complete(Unit)
        assertThat(download.await().isFailure).isTrue()
        assertThat(com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
            .enLaUltimaListaEfectiva(storage, "payments:resolve-no-instrument")).isFalse()
    }

    @Test fun `the checked declaration never releases a POS request or an attempt Avoqado never answered`() = runTest {
        every { storage.getString("permissions_cache", null) } returns "venue|manager\npayments:reconcile-uncharged"
        coEvery { recovery.recoverOne("venue", "pax", any(), false) } returns LedgerServerRecovery.LecturaDelIntento(null)
        assertThat(resolver.declareChecked("venue", "pax", pin).state).isEqualTo(BlumonAttemptResolver.State.PENDING)
        row = row.copy(serverAnsweredAt = 3, terminalPaymentRequestId = "pos-request")
        assertThat(resolver.declareChecked("venue", "pax", pin).state).isEqualTo(BlumonAttemptResolver.State.PENDING)
        coVerify(exactly = 0) { ledger.declararSinCobroLocal(any(), any(), any(), any()) }
    }

    // F1 (tarea 3): reemplaza «cierra sin red una duda ya contestada»: la firma necesita conexión y A se conserva.
    @Test fun `F1 offline the checked declaration asks first, sends nothing and keeps A pending`() = runTest {
        row = row.copy(serverAnsweredAt = 3)
        coEvery { recovery.recoverOne("venue", "pax", any(), false) } returns
            LedgerServerRecovery.LecturaDelIntento(null, servidorContesto = false)
        val r = resolver.declareChecked("venue", "pax", pin)
        assertThat(r.state).isEqualTo(BlumonAttemptResolver.State.PENDING)
        assertThat(r.serverAnswered).isFalse()
        coVerify { recovery.recoverOne("venue", "pax", any(), false) }
        coVerify(exactly = 0) { api.resolveNoInstrument(any(), any(), any()) }
        coVerify(exactly = 0) { ledger.declararSinCobroLocal(any(), any(), any(), any()) }
    }

    /** F1 (tarea 3): reemplaza «el cajero declara aunque el envío falle»: si la firma no llega, nada se cierra. */
    @Test fun `F1 if the signed declaration gets no answer A stays pending and nothing closes locally`() = runTest {
        every { storage.getStaffId() } returns "cajero"
        row = row.copy(serverAnsweredAt = 3)
        coEvery { api.resolveNoInstrument("venue", "pax", any()) } throws java.io.IOException("sin red")
        val r = resolver.declareChecked("venue", "pax", pin)
        assertThat(r.state).isEqualTo(BlumonAttemptResolver.State.PENDING)
        assertThat(r.serverAnswered).isFalse()
        coVerify(exactly = 1) { api.resolveNoInstrument("venue", "pax", any()) }
        coVerify(exactly = 0) { ledger.declararSinCobroLocal(any(), any(), any(), any()) }
    }

    // ── D22 · A1 (founder, 17-nov: «Sí, A1»): la declaración revisada llega a Avoqado ──────────────────────────────────

    /** F1: la cajera opera la terminal SIN el permiso de conciliar; firma el gerente con su PIN. */
    private fun cajeroQueRevisa() {
        every { storage.getStaffId() } returns "cajero"
        every { storage.getString("permissions_cache", null) } returns "venue|cajero\npayments:create"
        row = row.copy(serverAnsweredAt = 3, amountCents = 1250, tipCents = 100)
    }

    private fun guardadaEnAvoqado(
        cuerpos: MutableList<NoInstrumentResolutionRequest>, kind: String = "UNCHARGED_VERIFIED", id: String? = null, by: String? = "SUPERVISOR_PIN",
        amountCents: Long? = null,
    ) {
        coEvery { api.resolveNoInstrument("venue", "pax", any()) } coAnswers {
            val cuerpo = thirdArg<NoInstrumentResolutionRequest>()
            cuerpos += cuerpo
            val resolution = com.google.gson.JsonObject().apply {
                addProperty("kind", kind)
                addProperty("id", id ?: cuerpo.resolutionId)
                addProperty("staffId", if (by == "SUPERVISOR_PIN") "gerente-1" else "cajero")
                if (by != null) addProperty("by", by)
                if (amountCents != null) addProperty("amountCents", amountCents)
            }
            Response.success(TerminalAttemptStatusResponse(success = true, attemptId = "pax",
                attempt = TerminalAttemptResultDto(attemptId = "pax", outcome = "NOT_RECORDED", resolution = resolution)))
        }
    }

    private fun cierraLaLibreta() {
        coEvery { ledger.declararSinCobroLocal("pax", "venue", any(), any()) } coAnswers {
            row = row.copy(state = "DESCARTADA", lastError = "declarado_sin_cobro:${arg<String>(2)}" + (if (arg<Boolean>(3)) ":avoqado" else ""))
            true
        }
    }

    @Test fun `D22 with network the checked declaration reaches Avoqado with the ledger amount and the ledger marks it`() = runTest {
        cajeroQueRevisa()
        val cuerpos = mutableListOf<NoInstrumentResolutionRequest>()
        guardadaEnAvoqado(cuerpos)
        cierraLaLibreta()
        val r = resolver.declareChecked("venue", "pax", pin)
        assertThat(r.state).isEqualTo(BlumonAttemptResolver.State.RELEASED)
        assertThat(PaymentAttemptEntity.declaradoEnAvoqado(r.row?.lastError)).isTrue()
        val cuerpo = cuerpos.single()
        assertThat(cuerpo.statement).isEqualTo("UNCHARGED_VERIFIED")
        assertThat(cuerpo.requestId).isNull()
        assertThat(cuerpo.supervisorPin).isEqualTo(pin)
        assertThat(cuerpo.amountCents).isEqualTo(1250)
        assertThat(cuerpo.tipCents).isEqualTo(100)
        assertThat(r.row?.lastError).isEqualTo("declarado_sin_cobro:gerente-1:avoqado") // F1: a nombre del gerente que firmó
        // The local CAS runs AFTER the server answered: evidence that arrives in between still wins inside it.
        io.mockk.coVerifyOrder {
            api.resolveNoInstrument("venue", "pax", any())
            ledger.declararSinCobroLocal("pax", "venue", "gerente-1", true)
        }
    }

    @Test fun `D22 the same declaration is sent with the same id and body after a new resolver (screen or process recreated)`() = runTest {
        cajeroQueRevisa()
        val cuerpos = mutableListOf<NoInstrumentResolutionRequest>()
        coEvery { api.resolveNoInstrument("venue", "pax", any()) } coAnswers {
            cuerpos += thirdArg<NoInstrumentResolutionRequest>()
            throw java.io.IOException("sin red")
        }
        resolver.declareChecked("venue", "pax", pin)
        resolver = BlumonAttemptResolver(ledger, recovery, api, storage)
        resolver.declareChecked("venue", "pax", pin)
        assertThat(cuerpos).hasSize(2)
        assertThat(cuerpos[1]).isEqualTo(cuerpos[0])
        // Not the manager's «no card was presented» declaration: they are two different statements.
        assertThat(cuerpos[0].resolutionId)
            .isNotEqualTo(java.util.UUID.nameUUIDFromBytes("pax:no-instrument:venue:pax".toByteArray(Charsets.UTF_8)).toString())
    }

    // F1 (tarea 3): reemplaza «se anota sólo en esta terminal»: sin el acuse firmado, nada se cierra en ningún lado.
    @Test fun `F1 without an answer, a 5xx or an older server, A stays pending and nothing is noted`() = runTest {
        cajeroQueRevisa()
        cierraLaLibreta()
        // null = sin respuesta (tope). 409/404: un servidor anterior rechaza la clase nueva por su esquema estricto.
        for ((codigo, contesto) in listOf<Pair<Int?, Boolean>>(null to false, 503 to false, 409 to true, 404 to true)) {
            coEvery { api.resolveNoInstrument("venue", "pax", any()) } answers {
                if (codigo == null) throw java.net.SocketTimeoutException("tope")
                Response.error(codigo, okhttp3.ResponseBody.create(null, """{"code":"ATTEMPT_NOT_ELIGIBLE"}"""))
            }
            val r = resolver.declareChecked("venue", "pax", pin)
            assertThat(r.state).isEqualTo(BlumonAttemptResolver.State.PENDING)
            assertThat(r.serverAnswered).isEqualTo(contesto)
        }
        coVerify(exactly = 0) { ledger.declararSinCobroLocal(any(), any(), any(), any()) }
    }

    // F1 (tarea 3 y Codex código r1 #3): un 2xx que no es el acuse de ESTA declaración (otra clase, otro id, otro importe, sin quién la
    // declaró o automática) no cierra A. El acuse viejo por la sesión que Avoqado ya guardó SÍ (ver DeclaracionDesdeElAvisoRoomTest).
    @Test fun `F1 a 2xx that is not this declaration (other kind, id or amount, no declarer, automatic) closes nothing`() = runTest {
        cajeroQueRevisa()
        cierraLaLibreta()
        for (respuesta in listOf<() -> Unit>(
            { guardadaEnAvoqado(mutableListOf(), kind = "NO_INSTRUMENT_PRESENTED") },
            { guardadaEnAvoqado(mutableListOf(), id = "otra-declaracion") },
            { guardadaEnAvoqado(mutableListOf(), amountCents = row.amountCents + 1) },
            { guardadaEnAvoqado(mutableListOf(), by = "AUTOMATIC") },
            { guardadaEnAvoqado(mutableListOf(), by = null) },
        )) {
            respuesta()
            assertThat(resolver.declareChecked("venue", "pax", pin).state).isEqualTo(BlumonAttemptResolver.State.PENDING)
        }
        coVerify(exactly = 0) { ledger.declararSinCobroLocal(any(), any(), any(), any()) }
    }

    @Test fun `D22 when Avoqado says the session cannot declare it, it is noted nowhere`() = runTest {
        cajeroQueRevisa()
        every { storage.remove(any()) } returns Unit
        coEvery { api.resolveNoInstrument("venue", "pax", any()) } returns Response.error(403,
            okhttp3.ResponseBody.create(null, """{"code":"SUPERVISOR_AUTHORIZATION_REQUIRED"}"""))
        // F1: con PIN, ese 403 dice que el PIN no firma; se vuelve a pedir.
        assertThat(resolver.declareChecked("venue", "pax", pin).state).isEqualTo(BlumonAttemptResolver.State.SIGNATURE_REQUIRED)
        coEvery { api.resolveNoInstrument("venue", "pax", any()) } returns Response.error(403,
            okhttp3.ResponseBody.create(null, """{"code":"SESSION_NOT_IN_VENUE"}"""))
        every { storage.getString("permissions_cache", null) } returns "venue|cajero\npayments:reconcile-uncharged"
        assertThat(resolver.declareChecked("venue", "pax", pin).state).isEqualTo(BlumonAttemptResolver.State.PERMISSION_REQUIRED)
        coVerify(exactly = 0) { ledger.declararSinCobroLocal(any(), any(), any(), any()) }
        assertThat(row.state).isEqualTo("INDETERMINADO")
    }

    /**
     * hz:nube0000:na1bd1b1 (Codex r1 #1): Avoqado contestó «sin evidencia» y, antes de mandar la declaración, el historial de
     * AngelPay confirmó el cobro y lo escribió DIRECTO en Room (sin pasar por el candado de la libreta). Antes se mandaba igual
     * con la copia vieja de la fila y Avoqado anotaba «no se cobró» sobre un cobro que la terminal ya tenía aprobado.
     */
    @Test fun `P1 na1bd1b1 an approval written right after the consultation stops the checked declaration from leaving`() = runTest {
        cajeroQueRevisa()
        row = row.copy(processor = "ANGELPAY")
        var consultaHecha = false
        var lecturasTrasConsulta = 0
        coEvery { recovery.recoverOne("venue", "pax", any(), false) } answers {
            consultaHecha = true
            LedgerServerRecovery.LecturaDelIntento(null, servidorContesto = true)
        }
        coEvery { ledger.leerIntento("pax") } answers {
            if (consultaHecha) lecturasTrasConsulta++
            // La lectura de la consulta todavía la ve en duda; la siguiente ya ve lo que escribió el historial.
            if (lecturasTrasConsulta >= 2) row.copy(state = "HOST_RESPONDIO", hostApproved = true) else row
        }
        guardadaEnAvoqado(mutableListOf())

        val r = resolver.declareChecked("venue", "pax", pin)

        assertThat(r.state).isEqualTo(BlumonAttemptResolver.State.MONEY_EVIDENCE)
        coVerify(exactly = 0) { api.resolveNoInstrument(any(), any(), any()) }
        coVerify(exactly = 0) { ledger.declararSinCobroLocal(any(), any(), any(), any()) }
    }

    /**
     * na1bd1b1 (Codex r2 #3 y r3 #4): Avoqado guardó la declaración y el cierre local no aplicó. El rastro «la declaración quedó
     * contradicha» sólo sale con dinero de ESTE intento; un veto por evidencia de otra terminal no lo es.
     */
    @Test fun `na1bd1b1 the contradiction trace needs this attempt's own money, not a foreign veto`() = runTest {
        cajeroQueRevisa()
        guardadaEnAvoqado(mutableListOf())
        val errores = mutableListOf<String>()
        val arbol = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority >= android.util.Log.ERROR) errores += message
            }
        }
        timber.log.Timber.plant(arbol)
        try {
            val enDuda = row
            coEvery { ledger.declararSinCobroLocal("pax", "venue", "gerente-1", any()) } coAnswers {
                row = row.copy(serverVeto = "LINK_VENUE_MISMATCH") // evidencia de OTRA terminal
                false
            }
            resolver.declareChecked("venue", "pax", pin)
            assertThat(errores.filter { "contradicha" in it }).isEmpty()

            row = enDuda
            coEvery { ledger.declararSinCobroLocal("pax", "venue", "gerente-1", any()) } coAnswers {
                row = row.copy(state = "HOST_RESPONDIO", hostApproved = true) // la aprobación del banco llegó con el envío en camino
                false
            }
            resolver.declareChecked("venue", "pax", pin)
            assertThat(errores.filter { "contradicha" in it }).hasSize(1)
        } finally {
            timber.log.Timber.uproot(arbol)
        }
    }

    // F1 bloque 7 (I4): antes el 409 se guardaba como aprobación del banco (`marcarEvidenciaPositivaDelServidor`). El 409 es GENÉRICO
    // (puede ser evidencia de otra terminal): se rechaza igual y se guarda como IMPEDIMENTO; la aprobación sólo con dinero en el cuerpo.
    @Test fun `D22 if Avoqado has money evidence the declaration is refused and the evidence saved`() = runTest {
        cajeroQueRevisa()
        coEvery { api.resolveNoInstrument("venue", "pax", any()) } returns Response.error(409,
            okhttp3.ResponseBody.create(null, """{"code":"POSITIVE_EVIDENCE_EXISTS"}"""))
        coEvery { ledger.marcarImpedimentoDelServidor("venue", "pax", any()) } returns Result.success(true)
        coEvery { ledger.marcarEvidenciaPositivaDelServidor("venue", "pax", any()) } returns Result.success(true)
        assertThat(resolver.declareChecked("venue", "pax", pin).state).isEqualTo(BlumonAttemptResolver.State.MONEY_EVIDENCE)
        coVerify { ledger.marcarImpedimentoDelServidor("venue", "pax", any()) }
        coVerify(exactly = 0) { ledger.marcarEvidenciaPositivaDelServidor(any(), any(), any()) }
        // A 2xx that carries money (the bank approved meanwhile) also refuses, and saves it.
        coEvery { api.resolveNoInstrument("venue", "pax", any()) } returns Response.success(
            TerminalAttemptStatusResponse(success = true, attemptId = "pax", attempt = TerminalAttemptResultDto(
                attemptId = "pax", outcome = "NOT_RECORDED", processorEvidence = "APPROVED",
            )),
        )
        assertThat(resolver.declareChecked("venue", "pax", pin).state).isEqualTo(BlumonAttemptResolver.State.MONEY_EVIDENCE)
        coVerify(exactly = 0) { ledger.declararSinCobroLocal(any(), any(), any(), any()) }
    }

    // F1 (tarea 3): reemplaza «necesita payments:reconcile-uncharged de la sesión»: lo que hace falta es la firma del gerente.
    @Test fun `F1 without the manager PIN the checked declaration asks nothing and sends nothing`() = runTest {
        row = row.copy(serverAnsweredAt = 3)
        for (sinPin in listOf<String?>(null, "", "  "))
            assertThat(resolver.declareChecked("venue", "pax", sinPin).state).isEqualTo(BlumonAttemptResolver.State.SIGNATURE_REQUIRED)
        coVerify(exactly = 0) { recovery.recoverOne(any(), any(), any(), any()) }
        coVerify(exactly = 0) { api.resolveNoInstrument(any(), any(), any()) }
        coVerify(exactly = 0) { ledger.declararSinCobroLocal(any(), any(), any(), any()) }
    }

    @Test fun `the checked declaration refuses when Avoqado has money evidence`() = runTest {
        every { storage.getString("permissions_cache", null) } returns "venue|manager\npayments:reconcile-uncharged"
        row = row.copy(serverAnsweredAt = 3)
        coEvery { recovery.recoverOne("venue", "pax", any(), false) } returns
            LedgerServerRecovery.LecturaDelIntento(null, evidenciaPositivaSinRegistro = true, servidorContesto = true)
        assertThat(resolver.declareChecked("venue", "pax", pin).state).isEqualTo(BlumonAttemptResolver.State.MONEY_EVIDENCE)
        coVerify(exactly = 0) { ledger.declararSinCobroLocal(any(), any(), any(), any()) }
    }

    // F1: reemplaza «revisa el permiso tras la consulta»: la sesión que cambia de negocio durante la consulta no firma nada.
    @Test fun `F1 a session that changes venue during the consultation sends nothing`() = runTest {
        row = row.copy(serverAnsweredAt = 3)
        coEvery { recovery.recoverOne("venue", "pax", any(), false) } coAnswers {
            every { storage.getVenueId() } returns "otro-negocio"
            LedgerServerRecovery.LecturaDelIntento(null, servidorContesto = true)
        }
        assertThat(resolver.declareChecked("venue", "pax", pin).state).isEqualTo(BlumonAttemptResolver.State.UNAVAILABLE)
        coVerify(exactly = 0) { api.resolveNoInstrument(any(), any(), any()) }
        coVerify(exactly = 0) { ledger.declararSinCobroLocal(any(), any(), any(), any()) }
    }

    @Test fun `a session from another venue cannot query or resolve the old PAX attempt`() = runTest {
        every { storage.getVenueId() } returns "another-venue"
        assertThat(resolver.consult("venue", "pax").state).isEqualTo(BlumonAttemptResolver.State.UNAVAILABLE)
        coVerify(exactly = 0) { recovery.recoverOne(any(), any(), any(), any()) }
        coVerify(exactly = 0) { api.resolveNoInstrument(any(), any(), any()) }
    }

    // ── El banner «No se cobró» (founder, 3-nov): la declaración revisada se abre a la Nexgo, y nada más cambia ─────────────

    private fun declaraEnLaNexgo() {
        every { storage.getString("permissions_cache", null) } returns "venue|manager\npayments:reconcile-uncharged"
        row = row.copy(processor = "ANGELPAY", serverAnsweredAt = 3)
    }

    @Test fun `the checked declaration passes on Nexgo under the same conditions as on PAX`() = runTest {
        declaraEnLaNexgo()
        guardadaEnAvoqado(mutableListOf())
        cierraLaLibreta()
        assertThat(resolver.declareChecked("venue", "pax", pin).state).isEqualTo(BlumonAttemptResolver.State.RELEASED)
        coVerify { recovery.recoverOne("venue", "pax", any(), false) }
    }

    @Test fun `on Nexgo a declaration without a previous answer, or with a POS request, closes nothing`() = runTest {
        declaraEnLaNexgo()
        row = row.copy(serverAnsweredAt = null)
        coEvery { recovery.recoverOne("venue", "pax", any(), false) } returns LedgerServerRecovery.LecturaDelIntento(null)
        assertThat(resolver.declareChecked("venue", "pax", pin).state).isEqualTo(BlumonAttemptResolver.State.PENDING)
        row = row.copy(serverAnsweredAt = 3, terminalPaymentRequestId = "pos-request")
        assertThat(resolver.declareChecked("venue", "pax", pin).state).isEqualTo(BlumonAttemptResolver.State.PENDING)
        coVerify(exactly = 0) { ledger.declararSinCobroLocal(any(), any(), any(), any()) }
    }

    @Test fun `on Nexgo money evidence and a veto that could not be saved both refuse`() = runTest {
        declaraEnLaNexgo()
        coEvery { recovery.recoverOne("venue", "pax", any(), false) } returns
            LedgerServerRecovery.LecturaDelIntento(null, evidenciaPositivaSinRegistro = true, servidorContesto = true)
        assertThat(resolver.declareChecked("venue", "pax", pin).state).isEqualTo(BlumonAttemptResolver.State.MONEY_EVIDENCE)
        coEvery { recovery.recoverOne("venue", "pax", any(), false) } returns
            LedgerServerRecovery.LecturaDelIntento(null, servidorContesto = true, vetoDelServidor = "PAYMENT_CONTRADICTION")
        assertThat(resolver.declareChecked("venue", "pax", pin).state).isEqualTo(BlumonAttemptResolver.State.MONEY_EVIDENCE)
        // El veto no quedó en la fila: se recuerda aunque la consulta siguiente ya no lo traiga.
        coEvery { recovery.recoverOne("venue", "pax", any(), false) } returns LedgerServerRecovery.LecturaDelIntento(null)
        assertThat(resolver.declareChecked("venue", "pax", pin).state).isEqualTo(BlumonAttemptResolver.State.MONEY_EVIDENCE)
        coVerify(exactly = 0) { ledger.declararSinCobroLocal(any(), any(), any(), any()) }
    }

    @Test fun `on Nexgo the live call guard of this process also refuses, and so does a missing permission`() = runTest {
        declaraEnLaNexgo()
        every { ledger.puedeDeclararseEnDuda(any(), any()) } returns false
        assertThat(resolver.declareChecked("venue", "pax", pin).state).isEqualTo(BlumonAttemptResolver.State.PENDING)
        // F1: lo que falta ahora es el PIN del gerente, no el permiso de la sesión.
        assertThat(resolver.declareChecked("venue", "pax", null).state).isEqualTo(BlumonAttemptResolver.State.SIGNATURE_REQUIRED)
        coVerify(exactly = 0) { ledger.declararSinCobroLocal(any(), any(), any(), any()) }
    }

    @Test fun `preparing asks first and tells what the row can do, on both processors`() = runTest {
        every { storage.getString("permissions_cache", null) } returns "venue|manager\npayments:reconcile-uncharged"
        for (procesador in listOf("BLUMON", "ANGELPAY")) {
            row = row.copy(processor = procesador, serverAnsweredAt = 3)
            every { ledger.puedeDeclararseEnDuda(any(), any()) } returns true
            assertThat(resolver.prepareChecked("venue", "pax").state).isEqualTo(BlumonAttemptResolver.State.PENDING)
            every { ledger.puedeDeclararseEnDuda(any(), any()) } returns false
            assertThat(resolver.prepareChecked("venue", "pax").state).isEqualTo(BlumonAttemptResolver.State.UNAVAILABLE)
        }
        coVerify(exactly = 0) { ledger.declararSinCobroLocal(any(), any(), any(), any()) }
    }

    // F1: reemplaza «sin el permiso ni pregunta»: la consulta se ofrece a quien sea; la firma la pone el gerente con su PIN.
    @Test fun `F1 preparing without the session permission still asks Avoqado`() = runTest {
        every { storage.getString("permissions_cache", null) } returns "venue|manager\npayments:read"
        assertThat(resolver.prepareChecked("venue", "pax").state).isEqualTo(BlumonAttemptResolver.State.PENDING)
        coVerify { recovery.recoverOne("venue", "pax", any(), false) }
    }

    @Test fun `a Nexgo row released by the server reads as released in the declaration`() = runTest {
        declaraEnLaNexgo()
        row = row.copy(state = "DESCARTADA", serverOutcome = "RELEASED_NO_EVIDENCE")
        assertThat(resolver.prepareChecked("venue", "pax").state).isEqualTo(BlumonAttemptResolver.State.RELEASED)
    }

}

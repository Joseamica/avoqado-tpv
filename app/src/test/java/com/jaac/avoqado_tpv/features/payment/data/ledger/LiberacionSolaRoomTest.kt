package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.gson.JsonObject
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.features.payment.domain.model.VeredictoDelServidor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

/**
 * 🔴 Ronda 20 (founder, 23-sep): «no debería trabarse nunca y todo es por webhook».
 *
 * Medido en la N86 ese día: al morir la app con el lector dentro, la fila quedaba `AUTORIZANDO` y la terminal no cobraba
 * durante 10 minutos (lo que tardaba la cuarentena por reloj) y después seguía apartada. Medido en producción: el aviso del
 * banco llega en 1.6 s (p50), 3.4 s (p99), 4.5 s como máximo, y lo mandan todos los cobros donde está configurado.
 *
 * Lo que fija esta suite:
 *  1. Al REABRIR, lo que dejó a medias un proceso muerto pasa al instante a «en duda» — sin esperar 10 minutos. Lo que
 *     abrió ESTE proceso no se toca (su lector puede seguir dentro).
 *  2. Una duda LOCAL (Pago rápido) de más de 10 s se libera SOLA si el servidor lo acepta: el aviso del banco de ese
 *     comercio está comprobado y no llegó. Sin aviso comprobado (el comercio que nunca lo configuró) NO: queda el botón.
 *  3. El dinero manda siempre: con evidencia, nada se libera.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class LiberacionSolaRoomTest {

    private lateinit var db: AvoqadoDatabase
    private lateinit var dao: PaymentAttemptDao
    private lateinit var ledger: PaymentAttemptLedger
    private val venue = "venue-1"
    private val now = 1_700_000_000_000L
    private val settings = mockk<com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository>(relaxed = true)

    /** Ronda 21: el reloj MONOTÓNICO del proceso, controlado por la prueba (el de pared es `now`). */
    private var mono = 1_000_000L
    private fun libreta() = PaymentAttemptLedger(dao, settings).also { it.relojMonotonico = { mono } }

    @Before fun abrir() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.paymentAttemptDao()
        ledger = libreta()
    }

    /** Ronda 21: la duda ya lleva la espera del aviso del banco en duda, medida con el reloj monotónico de ESTE proceso. */
    private fun yaEsperoElAviso(attemptId: String, libreta: PaymentAttemptLedger = ledger) {
        libreta.anotarEnDuda(attemptId)
        mono += LedgerServerRecovery.ESPERA_AL_AVISO_DEL_BANCO_MS
    }

    @After fun cerrar() = db.close()

    /** Una fila que dejó OTRO proceso (insertada directo: este `ledger` no la abrió). */
    private suspend fun filaDeOtroProceso(
        attemptId: String,
        state: String,
        orderId: String? = null,
        lastError: String? = null,
        updatedAt: Long = now - 600_000,
        legacy: Boolean = false,
        processor: String = "ANGELPAY",
        conComercio: Boolean = true,
    ) = dao.insert(
        PaymentAttemptEntity(
            attemptId = attemptId, venueId = venue, processor = processor, state = state, amountCents = 4000, tipCents = 0,
            recordingRoute = "FAST",
            paymentContextJson = when {
                !conComercio -> """{"amount":40.00}"""
                orderId == null -> """{"amount":40.00,"merchantAccountId":"m-1"}"""
                else -> """{"amount":40.00,"merchantAccountId":"m-1","orderId":"$orderId"}"""
            },
            lastError = lastError, legacyShadow = legacy, createdAt = updatedAt, updatedAt = updatedAt,
        ),
    )

    private suspend fun otroCobroEntra(id: String, orderId: String? = null): Boolean =
        dao.reserveTerminal(
            id, venue, "ANGELPAY", "SALE", 5000, 0, "FAST", """{"amount":50.00}""",
            orderId?.let { "\"orderId\":\"$it\"" }, now + 10_000, null, false,
        ) != -1L

    // ── 1 · La cuarentena de lo que dejó un proceso muerto ───────────────────────────────────────

    @Test fun `al reabrir, un cobro AUTORIZANDO de un proceso muerto pasa AL INSTANTE a en duda, con su motivo`() = runTest {
        filaDeOtroProceso("a", PaymentAttemptEntity.STATE_AUTORIZANDO, updatedAt = now - 5_000)   // hace 5 s: la cuarentena por reloj NO lo vería
        filaDeOtroProceso("k", PaymentAttemptEntity.STATE_KERNEL_ACTIVO, updatedAt = now - 5_000)

        assertThat(ledger.cuarentenaDeHuerfanos(now)).isEqualTo(2)

        for (id in listOf("a", "k")) {
            val f = dao.getById(id)!!
            assertThat(f.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
            assertThat(f.lastError).isEqualTo(PaymentAttemptEntity.LAST_ERROR_PROCESO_TERMINADO)
        }
    }

    @Test fun `lo que abrio ESTE proceso NO se toca aunque este AUTORIZANDO — su lector puede seguir dentro`() = runTest {
        assertThat(ledger.openAttempt("propio", venue, "ANGELPAY", 4000, 0, "FAST", """{"amount":40.00}""")).isTrue()
        assertThat(dao.casTransition("propio", listOf(PaymentAttemptEntity.STATE_PREPARANDO), PaymentAttemptEntity.STATE_AUTORIZANDO, now)).isEqualTo(1)

        assertThat(ledger.cuarentenaDeHuerfanos(now)).isEqualTo(0)
        assertThat(dao.getById("propio")!!.state).isEqualTo(PaymentAttemptEntity.STATE_AUTORIZANDO)

        // …y un proceso NUEVO (otra libreta, misma base) sí lo reconoce como huérfano.
        assertThat(libreta().cuarentenaDeHuerfanos(now)).isEqualTo(1)
        assertThat(dao.getById("propio")!!.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
    }

    @Test fun `un PREPARANDO huerfano se descarta al instante — ninguna llamada capaz de cobrar empezo`() = runTest {
        filaDeOtroProceso("p", PaymentAttemptEntity.STATE_PREPARANDO, updatedAt = now - 5_000)

        ledger.cuarentenaDeHuerfanos(now)

        assertThat(dao.getById("p")!!.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
        assertWithMessage("el aparato vuelve a cobrar").that(otroCobroEntra("nuevo")).isTrue()
    }

    @Test fun `una fila HEREDADA no se toca (en 2_9_2 el contactless entraba al kernel en PREPARANDO)`() = runTest {
        filaDeOtroProceso("h1", PaymentAttemptEntity.STATE_PREPARANDO, legacy = true)
        filaDeOtroProceso("h2", PaymentAttemptEntity.STATE_AUTORIZANDO, legacy = true)

        assertThat(ledger.cuarentenaDeHuerfanos(now)).isEqualTo(0)
        assertThat(dao.getById("h1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_PREPARANDO)
        assertThat(dao.getById("h2")!!.state).isEqualTo(PaymentAttemptEntity.STATE_AUTORIZANDO)
    }

    @Test fun `con orden, el huerfano solo cerca SU venta — el aparato vuelve a cobrar las demas`() = runTest {
        filaDeOtroProceso("o", PaymentAttemptEntity.STATE_AUTORIZANDO, orderId = "venta-1")
        assertWithMessage("antes: el AUTORIZANDO aparta el aparato entero").that(otroCobroEntra("antes")).isFalse()

        ledger.cuarentenaDeHuerfanos(now)

        // 🔴 Codex r19 (P3-1): la MISMA venta se comprueba PRIMERO. Reservada otra venta antes, su fila PREPARANDO apartaba el
        // aparato entero y este rechazo pasaba aunque la duda no cercara nada (un sabotaje que borraba la duda seguía en verde).
        assertWithMessage("la MISMA venta no").that(otroCobroEntra("misma", orderId = "venta-1")).isFalse()
        assertWithMessage("otra venta ya entra").that(otroCobroEntra("otra", orderId = "venta-2")).isTrue()
    }

    @Test fun `sin orden, el huerfano en duda SIGUE apartando el aparato hasta que algo lo resuelva`() = runTest {
        filaDeOtroProceso("s", PaymentAttemptEntity.STATE_AUTORIZANDO)
        ledger.cuarentenaDeHuerfanos(now)
        assertThat(otroCobroEntra("nuevo")).isFalse()
    }

    // ── 2 · La liberación SOLA ─────────────────────────────────────────────────────────────────

    private fun declaracionSola(kind: String = LedgerServerRecovery.STATEMENT_SIN_RASTRO, id: String = "l") = Response.success(
        TerminalAttemptStatusResponse(
            success = true, attemptId = id,
            attempt = TerminalAttemptResultDto(attemptId = id, outcome = "NOT_RECORDED", resolution = JsonObject().apply { addProperty("kind", kind) }),
        ),
    )
    private fun error(code: Int, codigo: String) =
        Response.error<TerminalAttemptStatusResponse>(code, """{"success":false,"code":"$codigo"}""".toResponseBody("application/json".toMediaTypeOrNull()))
    private val s6Limpia = Response.success(
        TerminalAttemptStatusResponse(success = true, attemptId = "l", attempt = TerminalAttemptResultDto(attemptId = "l", outcome = "NOT_RECORDED")),
    )
    private val s6ConDinero = Response.success(
        TerminalAttemptStatusResponse(
            success = true, attemptId = "l",
            attempt = TerminalAttemptResultDto(attemptId = "l", outcome = "RECORDED", paymentId = "pay-1", paymentStatus = "COMPLETED", recordedVia = "webhook", amountCents = 4000, tipCents = 0),
        ),
    )

    private fun api(declaracion: Response<TerminalAttemptStatusResponse>, s6: Response<TerminalAttemptStatusResponse> = s6Limpia) =
        mockk<TerminalAttemptApiService> {
            coEvery { resolveNoInstrument(any(), any(), any()) } returns declaracion
            coEvery { getAttemptStatus(any(), any()) } returns s6
        }

    @Test fun `una duda LOCAL se libera SOLA cuando el servidor la acepta — sin solicitud, con la declaracion AUTOMATICA y un id fijo`() = runTest {
        filaDeOtroProceso("l", PaymentAttemptEntity.STATE_INDETERMINADO, lastError = PaymentAttemptEntity.LAST_ERROR_PROCESO_TERMINADO)
        val api = api(declaracionSola())
        val cuerpo = slot<NoInstrumentResolutionRequest>()
        coEvery { api.resolveNoInstrument(any(), any(), capture(cuerpo)) } returns declaracionSola()
        yaEsperoElAviso("l")

        val r = LedgerServerRecovery(dao, ledger, api).liberarSinRastroDelBanco(venue, "l", now)

        assertThat(r).isEqualTo(LedgerServerRecovery.SinRastro.LIBERADA)
        assertThat(cuerpo.captured.requestId).isNull()
        assertWithMessage("Codex r19 P1-3: el comercio del cobro viaja — el servidor mide SU aviso").that(cuerpo.captured.merchantAccountId).isEqualTo("m-1")
        assertThat(cuerpo.captured.statement).isEqualTo("NO_BANK_TRACE_AFTER_WINDOW")
        assertThat(cuerpo.captured.supervisorPin).isNull()
        assertWithMessage("el mismo id en cada intento: el servidor lo trata como replay").that(cuerpo.captured.resolutionId)
            .isEqualTo(LedgerServerRecovery.idDeLaLiberacionSola("l"))
        val f = dao.getById("l")!!
        assertThat(f.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
        assertThat(f.serverOutcome).isEqualTo(PaymentAttemptEntity.SERVER_RELEASED_NO_EVIDENCE)
        assertThat(f.lastError).isEqualTo(PaymentAttemptEntity.LAST_ERROR_LIBERADA_PREFIX + "NO_EVIDENCE_AFTER_WINDOW")
        assertThat(otroCobroEntra("nuevo")).isTrue()
    }

    @Test fun `sin aviso del banco comprobado (409 WEBHOOK_NOT_CONFIRMED) NO se libera — queda el boton del cajero`() = runTest {
        filaDeOtroProceso("l", PaymentAttemptEntity.STATE_INDETERMINADO, lastError = PaymentAttemptEntity.LAST_ERROR_PROCESO_TERMINADO)
        yaEsperoElAviso("l")

        val r = LedgerServerRecovery(dao, ledger, api(error(409, "WEBHOOK_NOT_CONFIRMED"))).liberarSinRastroDelBanco(venue, "l", now)

        assertThat(r).isEqualTo(LedgerServerRecovery.SinRastro.SIN_AVISO_COMPROBADO)
        assertThat(dao.getById("l")!!.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
        assertThat(otroCobroEntra("nuevo")).isFalse()
    }

    @Test fun `EL DINERO MANDA — si el servidor tiene evidencia (409), se consulta y se guarda, y nada se libera`() = runTest {
        filaDeOtroProceso("l", PaymentAttemptEntity.STATE_INDETERMINADO, lastError = PaymentAttemptEntity.LAST_ERROR_PROCESO_TERMINADO)
        yaEsperoElAviso("l")

        val r = LedgerServerRecovery(dao, ledger, api(error(409, "POSITIVE_EVIDENCE_EXISTS"), s6ConDinero)).liberarSinRastroDelBanco(venue, "l", now)

        assertThat(r).isEqualTo(LedgerServerRecovery.SinRastro.CON_EVIDENCIA)
        val f = dao.getById("l")!!
        assertThat(f.serverOutcome).isEqualTo(PaymentAttemptEntity.SERVER_RECORDED)
        assertThat(f.serverPaymentId).isEqualTo("pay-1")
        assertThat(f.serverOutcome).isNotEqualTo(PaymentAttemptEntity.SERVER_RELEASED_NO_EVIDENCE)
    }

    @Test fun `una respuesta 200 que trae dinero del intento NO libera — se guarda el veredicto`() = runTest {
        filaDeOtroProceso("l", PaymentAttemptEntity.STATE_INDETERMINADO, lastError = PaymentAttemptEntity.LAST_ERROR_PROCESO_TERMINADO)
        yaEsperoElAviso("l")

        val r = LedgerServerRecovery(dao, ledger, api(s6ConDinero)).liberarSinRastroDelBanco(venue, "l", now)

        assertThat(r).isEqualTo(LedgerServerRecovery.SinRastro.CON_EVIDENCIA)
        assertThat(dao.getById("l")!!.serverOutcome).isEqualTo(PaymentAttemptEntity.SERVER_RECORDED)
    }

    @Test fun `una duda por RELOJ de ESTE proceso no se libera sola — su lector puede seguir dentro (sin POST)`() = runTest {
        assertThat(ledger.openAttempt("propio", venue, "ANGELPAY", 4000, 0, "FAST", """{"amount":40.00,"merchantAccountId":"m-1"}""")).isTrue()
        dao.casTransition("propio", listOf(PaymentAttemptEntity.STATE_PREPARANDO), PaymentAttemptEntity.STATE_AUTORIZANDO, now - 700_000)
        dao.quarantineStaleAuthorizing(venue, now, now)
        val api = api(declaracionSola())
        yaEsperoElAviso("propio")

        val r = LedgerServerRecovery(dao, ledger, api).liberarSinRastroDelBanco(venue, "propio", now)

        assertThat(r).isEqualTo(LedgerServerRecovery.SinRastro.NO_ELEGIBLE)
        coVerify(exactly = 0) { api.resolveNoInstrument(any(), any(), any()) }
        // …y un proceso NUEVO sí puede: el proceso que tenía el lector ya murió.
        val nuevo = libreta()
        yaEsperoElAviso("propio", nuevo)
        assertThat(LedgerServerRecovery(dao, nuevo, api).liberarSinRastroDelBanco(venue, "propio", now)).isEqualTo(LedgerServerRecovery.SinRastro.LIBERADA)
    }

    @Test fun `un cobro del POS (con solicitud) nunca se libera solo — lo libera la ventana del servidor`() = runTest {
        dao.insert(
            PaymentAttemptEntity(
                attemptId = "r", venueId = venue, processor = "ANGELPAY", state = PaymentAttemptEntity.STATE_INDETERMINADO, amountCents = 4000, tipCents = 0,
                recordingRoute = "FAST", paymentContextJson = """{"terminalPaymentRequestId":"req-r"}""", terminalPaymentRequestId = "req-r",
                lastError = PaymentAttemptEntity.LAST_ERROR_PROCESO_TERMINADO, createdAt = now - 600_000, updatedAt = now - 600_000,
            ),
        )
        val api = api(declaracionSola())
        assertThat(LedgerServerRecovery(dao, ledger, api).liberarSinRastroDelBanco(venue, "r", now)).isEqualTo(LedgerServerRecovery.SinRastro.NO_ELEGIBLE)
        coVerify(exactly = 0) { api.resolveNoInstrument(any(), any(), any()) }
    }

    // ── 3 · El worker: reabre, espera 10 s y libera ─────────────────────────────────────────────

    @Test fun `el worker pone en duda lo que dejo un proceso muerto y, pasados 10 s, lo libera solo`() = runTest {
        filaDeOtroProceso("l", PaymentAttemptEntity.STATE_AUTORIZANDO, updatedAt = now - 600_000)
        val api = api(declaracionSola())
        val recuperacion = LedgerServerRecovery(dao, ledger, api)

        recuperacion.recover(venue, now)                                   // la cuarentena la deja «en duda» AHORA
        assertThat(dao.getById("l")!!.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
        coVerify(exactly = 0) { api.resolveNoInstrument(any(), any(), any()) }   // todavía no pasan 10 s desde que quedó en duda

        mono += LedgerServerRecovery.ESPERA_AL_AVISO_DEL_BANCO_MS
        recuperacion.recover(venue, now + LedgerServerRecovery.ESPERA_AL_AVISO_DEL_BANCO_MS)
        assertThat(dao.getById("l")!!.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
        assertThat(otroCobroEntra("nuevo")).isTrue()
    }

    @Test fun `sin aviso comprobado el worker no martilla — vuelve a pedirlo a los 10 min, no en cada pasada`() = runTest {
        filaDeOtroProceso("l", PaymentAttemptEntity.STATE_INDETERMINADO, lastError = PaymentAttemptEntity.LAST_ERROR_PROCESO_TERMINADO)
        val api = api(error(409, "WEBHOOK_NOT_CONFIRMED"))
        val recuperacion = LedgerServerRecovery(dao, ledger, api)

        recuperacion.recover(venue, now)                        // la ve por primera vez: empieza a contar su espera
        coVerify(exactly = 0) { api.resolveNoInstrument(any(), any(), any()) }
        mono += LedgerServerRecovery.ESPERA_AL_AVISO_DEL_BANCO_MS
        recuperacion.recover(venue, now + 10_000)
        recuperacion.recover(venue, now + 60_000)
        coVerify(exactly = 1) { api.resolveNoInstrument(any(), any(), any()) }
        mono += LedgerServerRecovery.REINTENTO_SIN_AVISO_MS   // Ronda 22: el reintento se mide en el reloj monotónico
        recuperacion.recover(venue, now + 10_000 + LedgerServerRecovery.REINTENTO_SIN_AVISO_MS + 1)
        coVerify(exactly = 2) { api.resolveNoInstrument(any(), any(), any()) }
    }

    // ── Ronda 21 · Codex r19 ─────────────────────────────────────────────────────────────────────

    @Test fun `P1-3 · sin el comercio del cobro en su contexto NO se pide la liberacion sola — queda el boton del cajero`() = runTest {
        filaDeOtroProceso("l", PaymentAttemptEntity.STATE_INDETERMINADO, lastError = PaymentAttemptEntity.LAST_ERROR_PROCESO_TERMINADO, conComercio = false)
        val api = api(declaracionSola())
        yaEsperoElAviso("l")

        assertThat(LedgerServerRecovery(dao, ledger, api).liberarSinRastroDelBanco(venue, "l", now)).isEqualTo(LedgerServerRecovery.SinRastro.SIN_AVISO_COMPROBADO)
        coVerify(exactly = 0) { api.resolveNoInstrument(any(), any(), any()) }
        assertThat(dao.getById("l")!!.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
    }

    @Test fun `P2-1 · un reloj de pared que salta hacia ADELANTE no adelanta la liberacion — manda el monotonico`() = runTest {
        filaDeOtroProceso("l", PaymentAttemptEntity.STATE_AUTORIZANDO, updatedAt = now - 600_000)
        val api = api(declaracionSola())
        val recuperacion = LedgerServerRecovery(dao, ledger, api)

        recuperacion.recover(venue, now)                        // cuarentena: «en duda» AHORA
        mono += 2_000                                           // pasan 2 s reales…
        recuperacion.recover(venue, now + 3_600_000)            // …pero el aparato corrige su hora una hora hacia adelante
        coVerify(exactly = 0) { api.resolveNoInstrument(any(), any(), any()) }
        assertThat(dao.getById("l")!!.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)

        mono += LedgerServerRecovery.ESPERA_AL_AVISO_DEL_BANCO_MS
        recuperacion.recover(venue, now + 3_610_000)
        assertThat(dao.getById("l")!!.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
    }

    @Test fun `P2-1 · un reloj de pared que salta hacia ATRAS no deja la duda apartada`() = runTest {
        filaDeOtroProceso("l", PaymentAttemptEntity.STATE_AUTORIZANDO, updatedAt = now)
        val api = api(declaracionSola())
        val recuperacion = LedgerServerRecovery(dao, ledger, api)

        recuperacion.recover(venue, now)                        // cuarentena con la hora de hoy
        mono += LedgerServerRecovery.ESPERA_AL_AVISO_DEL_BANCO_MS
        recuperacion.recover(venue, now - 3_600_000)            // el aparato corrige su hora una hora hacia ATRÁS
        assertThat(dao.getById("l")!!.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
        assertThat(otroCobroEntra("nuevo")).isTrue()
    }

    @Test fun `P2-1 · una duda que este proceso ve por PRIMERA vez no se libera al instante — empieza a contar ahora`() = runTest {
        filaDeOtroProceso("l", PaymentAttemptEntity.STATE_INDETERMINADO, lastError = PaymentAttemptEntity.LAST_ERROR_PROCESO_TERMINADO)
        val api = api(declaracionSola())
        val recuperacion = LedgerServerRecovery(dao, ledger, api)

        assertThat(recuperacion.liberarSinRastroDelBanco(venue, "l", now)).isEqualTo(LedgerServerRecovery.SinRastro.TODAVIA_NO)
        coVerify(exactly = 0) { api.resolveNoInstrument(any(), any(), any()) }
        mono += LedgerServerRecovery.ESPERA_AL_AVISO_DEL_BANCO_MS
        assertThat(recuperacion.liberarSinRastroDelBanco(venue, "l", now)).isEqualTo(LedgerServerRecovery.SinRastro.LIBERADA)
    }

    @Test fun `P2-1 · la espera cuenta desde que NACE la incertidumbre en este proceso`() = runTest {
        assertThat(ledger.openAttempt("n", venue, "ANGELPAY", 4000, 0, "FAST", """{"amount":40.00,"merchantAccountId":"m-1"}""")).isTrue()
        dao.casTransition("n", listOf(PaymentAttemptEntity.STATE_PREPARANDO), PaymentAttemptEntity.STATE_AUTORIZANDO, now)
        ledger.markIndeterminate("n", "AngelPay sin veredicto")
        assertThat(dao.getById("n")!!.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)

        mono += LedgerServerRecovery.ESPERA_AL_AVISO_DEL_BANCO_MS
        assertThat(ledger.faltaParaLiberarSola("n", LedgerServerRecovery.ESPERA_AL_AVISO_DEL_BANCO_MS)).isEqualTo(0L)
    }

    @Test fun `P2-2 · la pasada de dudas locales dice cuanto falta para la siguiente`() = runTest {
        filaDeOtroProceso("l", PaymentAttemptEntity.STATE_AUTORIZANDO, updatedAt = now - 600_000)
        val api = api(declaracionSola())
        val recuperacion = LedgerServerRecovery(dao, ledger, api)
        ledger.cuarentenaDeHuerfanos(now)
        mono += 3_000

        val r = recuperacion.liberarDudasLocales(venue, now)

        assertThat(r.liberadas).isEqualTo(0)
        assertThat(r.proximaEnMs).isEqualTo(LedgerServerRecovery.ESPERA_AL_AVISO_DEL_BANCO_MS - 3_000)
        coVerify(exactly = 0) { api.resolveNoInstrument(any(), any(), any()) }
    }

    // ── P1-1 · el VETO que trae la misma respuesta que el veredicto se guarda con él — en los tres consumidores ──

    private fun s6ConPagoY(veto: String?) = Response.success(
        TerminalAttemptStatusResponse(
            success = true, attemptId = "d",
            attempt = TerminalAttemptResultDto(
                attemptId = "d", outcome = "RECORDED", paymentId = "pay-d", paymentStatus = "COMPLETED", recordedVia = "webhook",
                amountCents = 4000, tipCents = 0, isWinner = true,
                evidenceContradiction = veto == PaymentAttemptEntity.VETO_EVIDENCE_CONTRADICTION,
                unattributedEvidence = veto == PaymentAttemptEntity.VETO_UNATTRIBUTED_EVIDENCE,
            ),
        ),
    )

    /** Un Pago rápido que la terminal ya dio por «no se cobró» (DESCARTADA) y del que el servidor después tiene un Payment. */
    private suspend fun descartadaLocal(id: String = "d") = dao.insert(
        PaymentAttemptEntity(
            attemptId = id, venueId = venue, processor = "ANGELPAY", state = PaymentAttemptEntity.STATE_DESCARTADA, amountCents = 4000, tipCents = 0,
            recordingRoute = "FAST", paymentContextJson = """{"amount":40.00,"merchantAccountId":"m-1"}""",
            lastError = "sin_autorizacion", createdAt = now - 60_000, updatedAt = now - 60_000,
        ),
    )

    // ── Ronda 22 · Codex r20 ──────────────────────────────────────────────────────────────────────

    @Test fun `r20 P1-1 · RECORDED con veto sobre una duda NO la promueve a REGISTRADO — sigue como contradiccion y apartando`() = runTest {
        filaDeOtroProceso("d", PaymentAttemptEntity.STATE_INDETERMINADO, lastError = PaymentAttemptEntity.LAST_ERROR_PROCESO_TERMINADO)

        LedgerServerRecovery(dao, ledger, api(declaracionSola(), s6ConPagoY(PaymentAttemptEntity.VETO_EVIDENCE_CONTRADICTION))).recoverOne(venue, "d", now)

        val f = dao.getById("d")!!
        assertThat(f.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
        assertThat(f.serverVeto).isEqualTo(PaymentAttemptEntity.VETO_EVIDENCE_CONTRADICTION)
        assertThat(dao.esContradiccion("d")).isTrue()
        assertWithMessage("el aparato sigue apartado").that(otroCobroEntra("otro")).isFalse()
        assertThat(ledger.reconocerCobroRegistrado(venue, "d", "yo", now)).isFalse()
    }

    @Test fun `r20 P1-1 control · el mismo RECORDED SIN veto si la promueve a REGISTRADO`() = runTest {
        filaDeOtroProceso("d", PaymentAttemptEntity.STATE_INDETERMINADO, lastError = PaymentAttemptEntity.LAST_ERROR_PROCESO_TERMINADO)
        LedgerServerRecovery(dao, ledger, api(declaracionSola(), s6ConPagoY(null))).recoverOne(venue, "d", now)
        assertThat(dao.getById("d")!!.state).isEqualTo(PaymentAttemptEntity.STATE_REGISTRADO)
    }

    @Test fun `r20 P1-1 · un veto guardado ANTES y un veredicto limpio despues (REST o S5) tampoco la promueve ni entra al lote`() = runTest {
        filaDeOtroProceso("d", PaymentAttemptEntity.STATE_INDETERMINADO, lastError = PaymentAttemptEntity.LAST_ERROR_PROCESO_TERMINADO)
        assertThat(ledger.marcarVetoDelServidor(venue, "d", PaymentAttemptEntity.VETO_UNATTRIBUTED_EVIDENCE, now).getOrThrow()).isTrue()

        val limpio = VeredictoDeIntento(
            venueId = venue, attemptId = "d", requestId = null, outcome = VeredictoDelServidor.RECORDED, paymentId = "pay-d",
            recordedVia = "terminal", amountCents = 4000, tipCents = 0, ganadorAcreditado = "pay-d", fuente = VeredictoDeIntento.Fuente.REST,
        )
        val r = ledger.aplicarVeredictoDelServidor(limpio, now).getOrThrow()

        assertThat(r.decision).isEqualTo(ResultadoDelVeredicto.Decision.GUARDADO_SIN_LIBERAR)
        assertThat(dao.getById("d")!!.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
        assertThat(dao.esContradiccion("d")).isTrue()
        assertWithMessage("el lote de reaplicación no la arrastra (no la puede aplicar)").that(dao.veredictosPendientesDeAplicar(venue).map { it.attemptId }).doesNotContain("d")
        assertThat(otroCobroEntra("otro")).isFalse()
    }

    @Test fun `r20 P1-3 · tras un 409, la evidencia que trae la consulta manda aunque no se pudo guardar — nunca LIBERADA`() = runTest {
        filaDeOtroProceso("c", PaymentAttemptEntity.STATE_INDETERMINADO, lastError = PaymentAttemptEntity.LAST_ERROR_PROCESO_TERMINADO)
        yaEsperoElAviso("c")
        val s6ConAprobacion = Response.success(
            TerminalAttemptStatusResponse(
                success = true, attemptId = "c",
                attempt = TerminalAttemptResultDto(attemptId = "c", outcome = "NOT_RECORDED", processorEvidence = "APPROVED"),
            ),
        )
        val api = mockk<TerminalAttemptApiService> {
            coEvery { resolveNoInstrument(any(), any(), any()) } coAnswers {
                // Mientras el POST viaja, otra consulta aplica una liberación VIEJA del cajero sobre la fila…
                // (`runBlocking`, no suspender: una suspensión en el hilo de Room deja que `runTest` adelante su reloj virtual
                // hasta el tope de la consulta, y la prueba acabaría en SIN_RESPUESTA por el motivo equivocado.)
                val cerradas = kotlinx.coroutines.runBlocking {
                    dao.cerrarPorLiberacionLocalDelServidor(
                        "c", venue, PaymentAttemptEntity.SERVER_OPERATOR_NO_INSTRUMENT, PaymentAttemptEntity.LAST_ERROR_LIBERADA_PREFIX + "OPERATOR_RECONCILED", now,
                    )
                }
                check(cerradas == 1) { "la liberación vieja no se aplicó" }
                // …y desde aquí la libreta ya no puede escribir la evidencia del banco.
                db.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER escritura_rota BEFORE UPDATE OF server_processor_evidence ON payment_attempts BEGIN SELECT RAISE(ABORT, 'escritura rota'); END",
                )
                error(409, "RESOLUTION_CONFLICT")
            }
            coEvery { getAttemptStatus(any(), any()) } returns s6ConAprobacion
        }

        val r = LedgerServerRecovery(dao, ledger, api).liberarSinRastroDelBanco(venue, "c", now)

        assertThat(r).isEqualTo(LedgerServerRecovery.SinRastro.CON_EVIDENCIA)
        // El escenario de verdad ocurrió: la fila conserva la liberación vieja y la evidencia NO quedó escrita.
        assertThat(dao.getById("c")!!.serverOutcome).isEqualTo(PaymentAttemptEntity.SERVER_OPERATOR_NO_INSTRUMENT)
        assertThat(dao.getById("c")!!.serverProcessorEvidence).isNull()
    }

    @Test fun `r20 P2-1 · 100 dudas que no se pueden liberar no tapan para siempre a la 101`() = runTest {
        for (i in 0 until 100) {
            filaDeOtroProceso("x%03d".format(i), PaymentAttemptEntity.STATE_INDETERMINADO, lastError = PaymentAttemptEntity.LAST_ERROR_PROCESO_TERMINADO,
                updatedAt = now - 600_000 + i, conComercio = false)
        }
        filaDeOtroProceso("ultima", PaymentAttemptEntity.STATE_INDETERMINADO, lastError = PaymentAttemptEntity.LAST_ERROR_PROCESO_TERMINADO, updatedAt = now - 1_000)
        for (i in 0 until 100) ledger.anotarEnDuda("x%03d".format(i))
        ledger.anotarEnDuda("ultima")
        mono += LedgerServerRecovery.ESPERA_AL_AVISO_DEL_BANCO_MS
        val api = api(declaracionSola())
        val recuperacion = LedgerServerRecovery(dao, ledger, api)

        repeat(6) { recuperacion.liberarDudasLocales(venue, now) }

        assertThat(dao.getById("ultima")!!.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
    }

    // ── Ronda 23 · Codex r21 ──────────────────────────────────────────────────────────────────────

    @Test fun `r21 P1-1 · registro limpio PRIMERO y veto DESPUES — la fila registrada queda como contradiccion y sale en el aviso`() = runTest {
        filaDeOtroProceso("d", PaymentAttemptEntity.STATE_INDETERMINADO, lastError = PaymentAttemptEntity.LAST_ERROR_PROCESO_TERMINADO)
        val limpio = VeredictoDeIntento(
            venueId = venue, attemptId = "d", requestId = null, outcome = VeredictoDelServidor.RECORDED, paymentId = "pay-d",
            recordedVia = "webhook", amountCents = 4000, tipCents = 0, ganadorAcreditado = "pay-d", fuente = VeredictoDeIntento.Fuente.REST,
        )
        ledger.aplicarVeredictoDelServidor(limpio, now).getOrThrow()
        assertThat(dao.getById("d")!!.state).isEqualTo(PaymentAttemptEntity.STATE_REGISTRADO)

        // …y ahora aplica la consulta S6 que seguía en vuelo, con la contradicción.
        val tardio = VeredictoDeIntento.desdeConsultaS6(venue, "d", s6ConPagoY(PaymentAttemptEntity.VETO_EVIDENCE_CONTRADICTION).body()!!)!!
        ledger.aplicarVeredictoDelServidor(tardio, now).getOrThrow()

        assertThat(dao.getById("d")!!.serverVeto).isEqualTo(PaymentAttemptEntity.VETO_EVIDENCE_CONTRADICTION)
        assertWithMessage("el veto tardío no desaparece por haber llegado después del registro").that(dao.esContradiccion("d")).isTrue()
        val aviso = db.remotePaymentRequestDao().observePendingObligations(venue).first()
        assertWithMessage("sale en el aviso de Inicio como contradicción").that(aviso.count { it.contradiccion == 1 }).isEqualTo(1)
    }

    @Test fun `r21 P1-2 · un 409 POSITIVE_EVIDENCE_EXISTS veta DESDE que llega aunque la consulta siguiente falle — el cierre sin red ya no pasa`() = runTest {
        filaDeOtroProceso("e", PaymentAttemptEntity.STATE_INDETERMINADO, lastError = PaymentAttemptEntity.LAST_ERROR_PROCESO_TERMINADO)
        yaEsperoElAviso("e")
        // 🔴 Codex r22 (P3): la fila tiene que ser DECLARABLE antes (el servidor ya contestó limpio alguna vez), o «el cierre sin
        // red ya no pasa» pasaba por la falta de respuesta y no por la marca.
        dao.estamparRespuestaDelServidor("e", now - 60_000)
        assertWithMessage("control: antes del 409 SÍ era declarable sin red").that(ledger.retencionLocalDeclarable()?.attemptId).isEqualTo("e")
        val api = mockk<TerminalAttemptApiService> {
            coEvery { resolveNoInstrument(any(), any(), any()) } returns error(409, "POSITIVE_EVIDENCE_EXISTS")
            coEvery { getAttemptStatus(any(), any()) } throws java.io.IOException("se cortó la conexión")
        }

        val r = LedgerServerRecovery(dao, ledger, api).liberarSinRastroDelBanco(venue, "e", now)

        assertThat(r).isEqualTo(LedgerServerRecovery.SinRastro.CON_EVIDENCIA)
        assertThat(dao.getById("e")!!.serverProcessorEvidence).isEqualTo(PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED)
        assertWithMessage("el cierre sin red del gerente ya no pasa").that(ledger.declararSinCobroLocal("e", venue, "gerente")).isFalse()
        assertThat(ledger.retencionLocalDeclarable()).isNull()
    }

    /** Ronda 24: una duda DECLARABLE sin red (el servidor ya contestó limpio) y la escritura de la marca rota a partir de aquí. */
    private suspend fun dudaDeclarableConMarcaRota(id: String) {
        filaDeOtroProceso(id, PaymentAttemptEntity.STATE_INDETERMINADO, lastError = PaymentAttemptEntity.LAST_ERROR_PROCESO_TERMINADO)
        yaEsperoElAviso(id)
        dao.estamparRespuestaDelServidor(id, now - 60_000)
        assertWithMessage("control: antes del 409 SÍ era declarable sin red").that(ledger.retencionLocalDeclarable()?.attemptId).isEqualTo(id)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER marca_rota BEFORE UPDATE OF server_processor_evidence ON payment_attempts BEGIN SELECT RAISE(ABORT, 'escritura rota'); END",
        )
    }

    private fun api409SinConsulta() = mockk<TerminalAttemptApiService> {
        coEvery { resolveNoInstrument(any(), any(), any()) } returns error(409, "POSITIVE_EVIDENCE_EXISTS")
        coEvery { getAttemptStatus(any(), any()) } throws java.io.IOException("se cortó la conexión")
    }

    @Test fun `r22 P1-2 a · si la MARCA del 409 no se puede escribir, el cierre sin red sigue bloqueado`() = runTest {
        dudaDeclarableConMarcaRota("e")
        assertThat(LedgerServerRecovery(dao, ledger, api409SinConsulta()).liberarSinRastroDelBanco(venue, "e", now)).isEqualTo(LedgerServerRecovery.SinRastro.CON_EVIDENCIA)
        assertThat(dao.getById("e")!!.serverProcessorEvidence).isNull()   // la escritura sí falló…
        assertWithMessage("…pero el cierre sin red sigue bloqueado").that(ledger.declararSinCobroLocal("e", venue, "gerente")).isFalse()
    }

    @Test fun `r22 P1-2 b · si la MARCA del 409 no se puede escribir, ya no se ofrece el cierre sin red`() = runTest {
        dudaDeclarableConMarcaRota("e")
        LedgerServerRecovery(dao, ledger, api409SinConsulta()).liberarSinRastroDelBanco(venue, "e", now)
        assertThat(ledger.retencionLocalDeclarable()).isNull()
    }

    @Test fun `r22 P1-2 c · la marca que no se pudo escribir se escribe en la pasada siguiente, cuando la base se recupera`() = runTest {
        dudaDeclarableConMarcaRota("e")
        val recuperacion = LedgerServerRecovery(dao, ledger, api409SinConsulta())
        recuperacion.liberarSinRastroDelBanco(venue, "e", now)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER marca_rota")
        recuperacion.recover(venue, now + 1_000)
        assertThat(dao.getById("e")!!.serverProcessorEvidence).isEqualTo(PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED)
    }

    @Test fun `r22 P1-2 d · con la marca sin escribir, la liberacion sola no vuelve a pedir aunque el servidor ahora la aceptara`() = runTest {
        dudaDeclarableConMarcaRota("e")
        LedgerServerRecovery(dao, ledger, api409SinConsulta()).liberarSinRastroDelBanco(venue, "e", now)
        val aceptaria = api(declaracionSola(id = "e"))

        val r = LedgerServerRecovery(dao, ledger, aceptaria).liberarSinRastroDelBanco(venue, "e", now + 1_000)

        assertThat(r).isEqualTo(LedgerServerRecovery.SinRastro.NO_ELEGIBLE)
        coVerify(exactly = 0) { aceptaria.resolveNoInstrument(any(), any(), any()) }
        assertThat(dao.getById("e")!!.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
    }

    @Test fun `r21 P2 · 25 dudas con 503 persistente no acaparan cada pasada — la 26 recibe su turno`() = runTest {
        for (i in 0 until CANDIDATAS_POR_PAGINA) {
            filaDeOtroProceso("z%02d".format(i), PaymentAttemptEntity.STATE_INDETERMINADO, lastError = PaymentAttemptEntity.LAST_ERROR_PROCESO_TERMINADO, updatedAt = now - 60_000 + i)
            ledger.anotarEnDuda("z%02d".format(i))
        }
        filaDeOtroProceso("sana", PaymentAttemptEntity.STATE_INDETERMINADO, lastError = PaymentAttemptEntity.LAST_ERROR_PROCESO_TERMINADO, updatedAt = now - 1_000)
        ledger.anotarEnDuda("sana")
        mono += LedgerServerRecovery.ESPERA_AL_AVISO_DEL_BANCO_MS
        val api = mockk<TerminalAttemptApiService> {
            coEvery { resolveNoInstrument(any(), any(), any()) } returns error(503, "SERVICE_UNAVAILABLE")
            coEvery { resolveNoInstrument(any(), "sana", any()) } returns declaracionSola(id = "sana")
            coEvery { getAttemptStatus(any(), any()) } returns s6Limpia
        }
        val recuperacion = LedgerServerRecovery(dao, ledger, api)

        recuperacion.liberarDudasLocales(venue, now)
        // 🔴 Codex r22 (P2): el worker real vuelve a los 31 s (su seguimiento; antes, el backoff de WorkManager): para entonces las
        // 25 esperas VENCIERON.
        mono += LedgerServerRecovery.REINTENTO_SIN_RESPUESTA_MS + 1_000
        recuperacion.liberarDudasLocales(venue, now + 31_000)

        assertThat(dao.getById("sana")!!.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
    }

    @Test fun `r24 · tras 25 respuestas 503, la pasada inmediata siguiente no vuelve a martillar a las mismas`() = runTest {
        for (i in 0 until CANDIDATAS_POR_PAGINA) {
            filaDeOtroProceso("z%02d".format(i), PaymentAttemptEntity.STATE_INDETERMINADO, lastError = PaymentAttemptEntity.LAST_ERROR_PROCESO_TERMINADO, updatedAt = now - 60_000 + i)
            ledger.anotarEnDuda("z%02d".format(i))
        }
        filaDeOtroProceso("sana", PaymentAttemptEntity.STATE_INDETERMINADO, lastError = PaymentAttemptEntity.LAST_ERROR_PROCESO_TERMINADO, updatedAt = now - 1_000)
        ledger.anotarEnDuda("sana")
        mono += LedgerServerRecovery.ESPERA_AL_AVISO_DEL_BANCO_MS
        val api = mockk<TerminalAttemptApiService> {
            coEvery { resolveNoInstrument(any(), any(), any()) } returns error(503, "SERVICE_UNAVAILABLE")
            coEvery { resolveNoInstrument(any(), "sana", any()) } returns declaracionSola(id = "sana")
            coEvery { getAttemptStatus(any(), any()) } returns s6Limpia
        }
        val recuperacion = LedgerServerRecovery(dao, ledger, api)

        recuperacion.liberarDudasLocales(venue, now)
        recuperacion.liberarDudasLocales(venue, now)   // en el acto (proximaEnMs = 0): las 25 siguen esperando sus 30 s

        coVerify(exactly = 26) { api.resolveNoInstrument(any(), any(), any()) }   // 25 + la sana, ni una más
    }

    @Test fun `r22 P2 · 2 000 dudas que todavia esperan no dejan inalcanzable a la 2 001 — el cursor sigue entre pasadas`() = runTest {
        for (i in 0 until LedgerServerRecovery.SOLAS_LEIDAS_POR_PASADA) {
            filaDeOtroProceso("w%04d".format(i), PaymentAttemptEntity.STATE_INDETERMINADO, lastError = PaymentAttemptEntity.LAST_ERROR_PROCESO_TERMINADO, updatedAt = now - 600_000 + i)
        }
        filaDeOtroProceso("ultima", PaymentAttemptEntity.STATE_INDETERMINADO, lastError = PaymentAttemptEntity.LAST_ERROR_PROCESO_TERMINADO, updatedAt = now - 1_000)
        yaEsperoElAviso("ultima")   // sólo ésta ya cumplió su espera; las otras 2 000 se ven por primera vez (todavía no)
        val api = api(declaracionSola(id = "ultima"))
        val recuperacion = LedgerServerRecovery(dao, ledger, api)

        repeat(2) { recuperacion.liberarDudasLocales(venue, now) }

        assertThat(dao.getById("ultima")!!.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
    }

    @Test fun `r22 · con el cupo de la pasada lleno y dudas por pedir, la pasada pide volver YA`() = runTest {
        for (i in 0..CANDIDATAS_POR_PAGINA) {
            filaDeOtroProceso("y%02d".format(i), PaymentAttemptEntity.STATE_INDETERMINADO, lastError = PaymentAttemptEntity.LAST_ERROR_PROCESO_TERMINADO, updatedAt = now - 60_000 + i)
            ledger.anotarEnDuda("y%02d".format(i))
        }
        mono += LedgerServerRecovery.ESPERA_AL_AVISO_DEL_BANCO_MS

        val r = LedgerServerRecovery(dao, ledger, api(declaracionSola())).liberarDudasLocales(venue, now)

        assertThat(r.liberadas).isEqualTo(CANDIDATAS_POR_PAGINA)
        assertThat(r.proximaEnMs).isEqualTo(0L)
    }

    @Test fun `r20 P2-2 · el reintento de 10 min se mide en el reloj monotonico — una hora corrida hacia atras no lo alarga`() = runTest {
        filaDeOtroProceso("l", PaymentAttemptEntity.STATE_INDETERMINADO, lastError = PaymentAttemptEntity.LAST_ERROR_PROCESO_TERMINADO)
        yaEsperoElAviso("l")
        val api = api(error(409, "WEBHOOK_NOT_CONFIRMED"))
        val recuperacion = LedgerServerRecovery(dao, ledger, api)

        recuperacion.liberarDudasLocales(venue, now)
        coVerify(exactly = 1) { api.resolveNoInstrument(any(), any(), any()) }
        mono += 60_000
        assertWithMessage("mientras espera el reintento, dice cuánto le falta")
            .that(recuperacion.liberarDudasLocales(venue, now).proximaEnMs).isEqualTo(LedgerServerRecovery.REINTENTO_SIN_AVISO_MS - 60_000)
        mono += LedgerServerRecovery.REINTENTO_SIN_AVISO_MS
        recuperacion.liberarDudasLocales(venue, now - 3_600_000)   // pasaron 10 min reales, pero la hora se corrigió una hora hacia atrás
        coVerify(exactly = 2) { api.resolveNoInstrument(any(), any(), any()) }
    }

    @Test fun `P1-1 control · sin veto, el mismo RECORDED sobre la DESCARTADA SI se ofrece para Entendido`() = runTest {
        descartadaLocal()
        LedgerServerRecovery(dao, ledger, api(declaracionSola(), s6ConPagoY(null))).recoverOne(venue, "d", now)
        assertThat(dao.getById("d")!!.serverOutcome).isEqualTo(PaymentAttemptEntity.SERVER_RECORDED)
        assertThat(ledger.reconocerCobroRegistrado(venue, "d", "yo", now)).isTrue()
    }

    @Test fun `P1-1 · recoverOne — RECORDED con evidenceContradiction guarda el veto y NO deja confirmar`() = runTest {
        descartadaLocal()
        val lectura = LedgerServerRecovery(dao, ledger, api(declaracionSola(), s6ConPagoY(PaymentAttemptEntity.VETO_EVIDENCE_CONTRADICTION))).recoverOne(venue, "d", now)
        assertThat(dao.getById("d")!!.serverVeto).isEqualTo(PaymentAttemptEntity.VETO_EVIDENCE_CONTRADICTION)
        assertWithMessage("la pantalla recibe el veto aunque haya veredicto").that(lectura.vetoDelServidor).isEqualTo(PaymentAttemptEntity.VETO_EVIDENCE_CONTRADICTION)
        assertThat(ledger.reconocerCobroRegistrado(venue, "d", "yo", now)).isFalse()
    }

    @Test fun `P1-1 · recover (worker) — RECORDED con unattributedEvidence guarda el veto y NO deja confirmar`() = runTest {
        descartadaLocal()
        LedgerServerRecovery(dao, ledger, api(declaracionSola(), s6ConPagoY(PaymentAttemptEntity.VETO_UNATTRIBUTED_EVIDENCE))).recover(venue, now)
        assertThat(dao.getById("d")!!.serverVeto).isEqualTo(PaymentAttemptEntity.VETO_UNATTRIBUTED_EVIDENCE)
        assertThat(ledger.reconocerCobroRegistrado(venue, "d", "yo", now)).isFalse()
    }

    @Test fun `P1-1 · liberacion sola — un 200 con RECORDED y evidenceContradiction guarda el veto y NO deja confirmar`() = runTest {
        filaDeOtroProceso("d", PaymentAttemptEntity.STATE_INDETERMINADO, lastError = PaymentAttemptEntity.LAST_ERROR_PROCESO_TERMINADO)
        yaEsperoElAviso("d")

        val r = LedgerServerRecovery(dao, ledger, api(s6ConPagoY(PaymentAttemptEntity.VETO_EVIDENCE_CONTRADICTION))).liberarSinRastroDelBanco(venue, "d", now)

        assertThat(r).isEqualTo(LedgerServerRecovery.SinRastro.CON_EVIDENCIA)
        assertThat(dao.getById("d")!!.serverVeto).isEqualTo(PaymentAttemptEntity.VETO_EVIDENCE_CONTRADICTION)
        // 🔴 Codex r20 (P1-1): «no deja confirmar» pasaba porque la fila ya estaba REGISTRADO. Lo que importa es el efecto final.
        assertThat(dao.getById("d")!!.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
        assertThat(dao.esContradiccion("d")).isTrue()
        assertWithMessage("el aparato sigue apartado").that(otroCobroEntra("otro")).isFalse()
        assertThat(ledger.reconocerCobroRegistrado(venue, "d", "yo", now)).isFalse()
    }

    // ── 4 · Lo que publica el servidor ─────────────────────────────────────────────────────────

    @Test fun `S6 con la declaracion AUTOMATICA se lee como liberacion por ventana · una clase desconocida no libera`() {
        val conKind = { kind: String ->
            TerminalAttemptStatusResponse(
                success = true, attemptId = "l",
                attempt = TerminalAttemptResultDto(attemptId = "l", outcome = "NOT_RECORDED", resolution = JsonObject().apply { addProperty("kind", kind) }),
            )
        }
        assertThat(LiberacionDelServidor.desdeConsultaS6(venue, "l", conKind("NO_BANK_TRACE_AFTER_WINDOW"))?.evidencia).isEqualTo("NO_EVIDENCE_AFTER_WINDOW")
        assertThat(LiberacionDelServidor.desdeConsultaS6(venue, "l", conKind("NO_INSTRUMENT_PRESENTED"))?.evidencia).isEqualTo("OPERATOR_RECONCILED")
        assertThat(LiberacionDelServidor.desdeConsultaS6(venue, "l", conKind("OTRA_COSA"))).isNull()
        assertThat(LiberacionDelServidor.desdeDeclaracion(venue, "l", null, conKind("NO_BANK_TRACE_AFTER_WINDOW"))?.evidencia).isEqualTo("NO_EVIDENCE_AFTER_WINDOW")
        assertThat(LiberacionDelServidor.desdeDeclaracion(venue, "l", null, conKind("NO_INSTRUMENT_PRESENTED"))?.evidencia).isEqualTo("OPERATOR_RECONCILED")
    }
}

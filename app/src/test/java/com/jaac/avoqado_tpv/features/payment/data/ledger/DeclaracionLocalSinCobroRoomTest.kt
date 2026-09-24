package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import io.mockk.coEvery
import io.mockk.mockk
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
 * 🔴 «Ya revisé la terminal: no se cobró» para un cobro LOCAL.
 *
 * Decisión del founder (21-sep): *«los negocios normalmente cobran con tarjeta y no pueden quedar trabados»*.
 * Medido ese día en una N86: matar la app con el lector activo deja la fila `AUTORIZANDO` apartando el aparato,
 * y como un cobro local no tiene solicitud del POS, la declaración que ya existía **no aplicaba**. La terminal
 * se quedaba sin cobrar con tarjeta hasta que alguien escribía SQL — ocurrió tres veces.
 *
 * Lo que fija esta suite es el CANDADO, no la pantalla: la salida existe, pero **nunca por delante de la
 * evidencia**.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class DeclaracionLocalSinCobroRoomTest {

    private lateinit var db: AvoqadoDatabase
    private lateinit var dao: PaymentAttemptDao
    private lateinit var ledger: PaymentAttemptLedger
    private val venue = "venue-1"
    private val now = 1_700_000_000_000L

    @Before fun abrir() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.paymentAttemptDao()
        val settings = io.mockk.mockk<com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository>(relaxed = true)
        ledger = PaymentAttemptLedger(dao, settings)
    }

    @After fun cerrar() = db.close()

    /** Una fila que aparta el aparato. Por defecto: LOCAL, el servidor ya CONTESTÓ sin dinero ⇒ declarable. */
    private suspend fun fila(
        attemptId: String = "a1",
        state: String = PaymentAttemptEntity.STATE_AUTORIZANDO,
        requestId: String? = null,
        hostApproved: Boolean? = null,
        serverCheckedAt: Long? = now - 1_000,
        serverAnsweredAt: Long? = now - 1_000,
        serverOutcome: String? = null,
        evidencia: String? = null,
    ) = dao.insert(
        PaymentAttemptEntity(
            attemptId = attemptId, venueId = venue, processor = "ANGELPAY", state = state,
            amountCents = 4000, tipCents = 0, recordingRoute = "FAST", paymentContextJson = """{"amount":40.00}""",
            hostApproved = hostApproved, terminalPaymentRequestId = requestId,
            serverCheckedAt = serverCheckedAt, serverAnsweredAt = serverAnsweredAt, serverOutcome = serverOutcome,
            serverProcessorEvidence = evidencia,
            createdAt = now - 600_000, updatedAt = now - 600_000,
        ),
    )

    /** La recuperación por servidor REAL (la del worker) contra una S6 simulada que contesta [respuesta]. */
    private fun recuperacionQueRecibe(respuesta: Response<TerminalAttemptStatusResponse>) =
        LedgerServerRecovery(dao, ledger, mockk<TerminalAttemptApiService> { coEvery { getAttemptStatus(any(), any()) } returns respuesta })

    private val sinDinero = Response.success(
        TerminalAttemptStatusResponse(success = true, attemptId = "a1", attempt = TerminalAttemptResultDto(attemptId = "a1", outcome = "NOT_RECORDED")),
    )
    private fun http(code: Int) = Response.error<TerminalAttemptStatusResponse>(code, "{}".toResponseBody("application/json".toMediaTypeOrNull()))

    // ── El camino que destraba ────────────────────────────────────────────────────────────────

    @Test fun `P1 un cobro LOCAL consultado y sin dinero SE PUEDE declarar, y la terminal queda libre`() = runTest {
        fila()

        assertThat(ledger.retencionLocalDeclarable()?.attemptId).isEqualTo("a1")
        assertThat(ledger.declararSinCobroLocal("a1", venue, "Cajera Ana")).isTrue()

        assertThat(dao.getById("a1")!!.state).isEqualTo("DESCARTADA")
        assertThat(dao.getById("a1")!!.lastError).isEqualTo("declarado_sin_cobro:Cajera Ana")
        assertThat(dao.findTerminalHold()).isNull()   // ← lo que el cajero necesita: poder volver a cobrar
    }

    // ── Los cinco candados: cada uno, por separado, impide la salida ──────────────────────────

    @Test fun `P1 NO se declara sin haberle preguntado antes al servidor`() = runTest {
        fila(serverCheckedAt = null, serverAnsweredAt = null)

        assertThat(ledger.retencionLocalDeclarable()).isNull()
        assertThat(ledger.declararSinCobroLocal("a1", venue, "Ana")).isFalse()
        assertThat(dao.getById("a1")!!.state).isEqualTo("AUTORIZANDO")
    }

    @Test fun `P1 NO se declara si el servidor sabe de DINERO`() = runTest {
        fila(serverOutcome = PaymentAttemptEntity.SERVER_RECORDED)

        assertThat(ledger.retencionLocalDeclarable()).isNull()
        assertThat(ledger.declararSinCobroLocal("a1", venue, "Ana")).isFalse()
    }

    @Test fun `P1 el veto DURABLE del procesador manda sobre el testimonio de una persona`() = runTest {
        fila(evidencia = PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED)

        assertThat(ledger.retencionLocalDeclarable()).isNull()
        assertThat(ledger.declararSinCobroLocal("a1", venue, "Ana")).isFalse()
    }

    @Test fun `P1 NUNCA sobre una aprobacion del host`() = runTest {
        fila(hostApproved = true)

        assertThat(ledger.retencionLocalDeclarable()).isNull()
        assertThat(ledger.declararSinCobroLocal("a1", venue, "Ana")).isFalse()
    }

    @Test fun `P1 un cobro DEL POS no usa esta salida — conserva la suya, que ademas libera la ranura del servidor`() = runTest {
        fila(requestId = "req-1")

        assertThat(ledger.retencionLocalDeclarable()).isNull()
        assertThat(ledger.declararSinCobroLocal("a1", venue, "Ana")).isFalse()
        assertThat(dao.getById("a1")!!.state).isEqualTo("AUTORIZANDO")
    }

    // ── La carrera: el veredicto llega entre ofrecer el botón y tocarlo ───────────────────────

    @Test fun `P1 si la evidencia llega DESPUES de ofrecer el boton, el CAS lo rechaza igual`() = runTest {
        // 🔑 Por esto las cinco condiciones viven DENTRO del UPDATE: entre que la pantalla ofrece la salida y
        // el cajero la toca caben segundos, y en esos segundos puede aterrizar el veredicto del servidor.
        fila()
        assertThat(ledger.retencionLocalDeclarable()?.attemptId).isEqualTo("a1")   // se ofreció

        dao.marcarEvidenciaPositivaDelServidor("a1", venue, now)                   // …y llegó el dinero

        assertThat(ledger.declararSinCobroLocal("a1", venue, "Ana")).isFalse()
        assertThat(dao.getById("a1")!!.state).isEqualTo("AUTORIZANDO")
    }

    @Test fun `P1 una fila INDETERMINADO por cuarentena tambien se puede declarar`() = runTest {
        // Es el estado al que la manda el barrido de 6 h, y seguía apartando el aparato igual.
        fila(state = PaymentAttemptEntity.STATE_INDETERMINADO)

        assertThat(ledger.declararSinCobroLocal("a1", venue, "Ana")).isTrue()
        assertThat(dao.findTerminalHold()).isNull()
    }

    // ── Codex r7 · P2-5: la declaración sin red no se poda antes de conciliarse ─────────────────

    @Test fun `r7 P2-5 - una declaracion sin red NO se poda a los 7 dias si el servidor no la vigilo despues`() = runTest {
        val dia = 24L * 3_600_000
        fila()   // consultada al servidor ANTES de declarar, como exige el candado 5
        assertThat(dao.declararSinCobroLocal("a1", venue, "declarado_sin_cobro:Ana", now)).isEqualTo(1)

        // Ocho días después, sin ninguna consulta posterior (la terminal siguió sin red): la declaración se queda.
        assertThat(dao.pruneTerminalOlderThan(venue, now + 8 * dia)).isEqualTo(0)
        assertThat(dao.getById("a1")).isNotNull()

        // Con una semana de vigilancia del servidor DESPUÉS de declarar, sin dinero, ya se puede soltar como cualquier otra.
        // La vigilancia es que el servidor CONTESTE (r8 P2-4): pasa por la recuperación de verdad con un 2xx sin dinero.
        recuperacionQueRecibe(sinDinero).recover(venue, now + 7 * dia + 1)
        assertThat(dao.pruneTerminalOlderThan(venue, now + 8 * dia)).isEqualTo(1)
    }

    @Test fun `r7 P2-5 control - una DESCARTADA que no es declaracion se poda a los 7 dias como siempre`() = runTest {
        fila(state = PaymentAttemptEntity.STATE_DESCARTADA, serverCheckedAt = null)
        assertThat(dao.pruneTerminalOlderThan(venue, now + 8L * 24 * 3_600_000)).isEqualTo(1)
    }

    // ── Codex r8 · P2-4: «intenté consultar» no es «el servidor contestó» ─────────────────────────

    @Test fun `r8 P2-4 - un 503 que pasa por la recuperacion gasta el turno pero NO habilita la declaracion sin red`() = runTest {
        fila(serverCheckedAt = null, serverAnsweredAt = null)   // nunca consultada

        recuperacionQueRecibe(http(503)).recover(venue, now)

        assertThat(dao.getById("a1")!!.serverCheckedAt).isEqualTo(now)   // el turno de la recuperación SÍ se gastó
        assertThat(ledger.retencionLocalDeclarable()).isNull()
        assertThat(ledger.declararSinCobroLocal("a1", venue, "Ana")).isFalse()   // …pero Avoqado no contestó
        // Ronda 20: una fila AUTORIZANDO que NO abrió este proceso la dejó un proceso muerto — la recuperación la pone «en duda» al
        // instante. Lo que fija esta prueba no cambia: NO quedó liberada.
        val f = dao.getById("a1")!!
        assertThat(f.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
        assertThat(f.lastError).isEqualTo(PaymentAttemptEntity.LAST_ERROR_PROCESO_TERMINADO)
    }

    @Test fun `r8 P2-4 - tampoco un 401, 403 o 404 cuentan como respuesta`() = runTest {
        for (codigo in listOf(401, 403, 404)) {
            val id = "a-$codigo"
            fila(attemptId = id, serverCheckedAt = null, serverAnsweredAt = null)
            recuperacionQueRecibe(http(codigo)).recover(venue, now)
            assertThat(dao.getById(id)!!.serverCheckedAt).isEqualTo(now)
            assertThat(dao.declararSinCobroLocal(id, venue, "declarado_sin_cobro:Ana", now)).isEqualTo(0)
        }
    }

    @Test fun `r8 P2-4 control - un 2xx que pasa por la recuperacion SI habilita la declaracion`() = runTest {
        fila(serverCheckedAt = null, serverAnsweredAt = null)

        recuperacionQueRecibe(sinDinero).recover(venue, now)

        assertThat(dao.getById("a1")!!.serverAnsweredAt).isEqualTo(now)
        assertThat(ledger.declararSinCobroLocal("a1", venue, "Ana")).isTrue()
        assertThat(dao.findTerminalHold()).isNull()
    }

    // ── Codex r9 · P1-3: la poda no puede colarse entre «contestó» y guardar lo que esa respuesta trae ─────────

    private val conVeto = Response.success(
        TerminalAttemptStatusResponse(success = true, attemptId = "a1",
            attempt = TerminalAttemptResultDto(attemptId = "a1", outcome = "NOT_RECORDED", paymentContradiction = true)),
    )
    private val aprobadaSinPayment = Response.success(
        TerminalAttemptStatusResponse(success = true, attemptId = "a1",
            attempt = TerminalAttemptResultDto(attemptId = "a1", outcome = "NOT_RECORDED", processorEvidence = "APPROVED")),
    )

    /** Una declaración sin red de hace ocho días, sin vigilancia posterior: la poda la soltaría en cuanto el servidor CONTESTE. */
    private suspend fun declaracionDeHaceOchoDias(attemptId: String = "a1") {
        fila(attemptId = attemptId)
        assertThat(dao.declararSinCobroLocal(attemptId, venue, "declarado_sin_cobro:Ana", now)).isEqualTo(1)
    }

    @Test fun `r9 P1-3 - la poda intercalada ANTES de guardar el veto de la respuesta no borra la declaracion (worker y sondeo)`() = runTest {
        val dia = 24L * 3_600_000
        for ((entrada, intento) in listOf("worker" to "w1", "sondeo" to "s1")) {
            declaracionDeHaceOchoDias(intento)
            // La carrera de Codex: el barrido poda en paralelo, justo cuando la recuperación va a guardar el veto. Se inyecta con
            // un DAO DECORADOR: un `spyk` + `callOriginal()` de mockk devuelve COROUTINE_SUSPENDED cuando la libreta salta a IO
            // (la primera versión se colgaba y tumbaba el JVM por memoria).
            var podadas = -1
            val daoConPoda = object : PaymentAttemptDao by dao {
                override suspend fun marcarVetoDelServidor(attemptId: String, venueId: String, motivo: String, now: Long): Int {
                    podadas = dao.pruneTerminalOlderThan(venue, this@DeclaracionLocalSinCobroRoomTest.now + 8 * dia + 1)
                    return dao.marcarVetoDelServidor(attemptId, venueId, motivo, now)
                }
            }
            val api = mockk<TerminalAttemptApiService> { coEvery { getAttemptStatus(any(), any()) } returns conVeto }
            val recuperacion = LedgerServerRecovery(daoConPoda, PaymentAttemptLedger(daoConPoda, mockk(relaxed = true)), api)
            if (entrada == "worker") recuperacion.recover(venue, now + 7 * dia + 1)
            else recuperacion.recoverOne(venue, intento, now + 7 * dia + 1, estampar = false)

            assertWithMessage("$entrada: un 2xx recibido no acredita que su evidencia haya quedado guardada").that(podadas).isEqualTo(0)
            assertWithMessage(entrada).that(dao.getById(intento)?.serverVeto).isEqualTo(PaymentAttemptEntity.VETO_PAYMENT_CONTRADICTION)
            // Y ya con el veto durable, la poda de después tampoco la toca: es contradicción.
            assertWithMessage(entrada).that(dao.pruneTerminalOlderThan(venue, now + 8 * dia + 1)).isEqualTo(0)
        }
    }

    @Test fun `r9 P1-3 - tampoco antes de guardar la APROBACION bancaria que trae la respuesta`() = runTest {
        val dia = 24L * 3_600_000
        declaracionDeHaceOchoDias()
        var podadas = -1
        val daoConPoda = object : PaymentAttemptDao by dao {
            override suspend fun marcarEvidenciaPositivaDelServidor(attemptId: String, venueId: String, at: Long): Int {
                podadas = dao.pruneTerminalOlderThan(venue, now + 8 * dia + 1)
                return dao.marcarEvidenciaPositivaDelServidor(attemptId, venueId, at)
            }
        }
        val api = mockk<TerminalAttemptApiService> { coEvery { getAttemptStatus(any(), any()) } returns aprobadaSinPayment }
        LedgerServerRecovery(daoConPoda, PaymentAttemptLedger(daoConPoda, mockk(relaxed = true)), api).recover(venue, now + 7 * dia + 1)

        assertThat(podadas).isEqualTo(0)
        assertThat(dao.getById("a1")?.serverProcessorEvidence).isEqualTo(PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED)
    }

    @Test fun `r8 P2-4 - un 503 del dia ocho NO cuenta como vigilancia - la declaracion sin red no se poda`() = runTest {
        val dia = 24L * 3_600_000
        fila()
        assertThat(dao.declararSinCobroLocal("a1", venue, "declarado_sin_cobro:Ana", now)).isEqualTo(1)

        // Día ocho: la recuperación vuelve a preguntar por la declaración y el servidor contesta 503.
        recuperacionQueRecibe(http(503)).recover(venue, now + 8 * dia)

        assertThat(dao.pruneTerminalOlderThan(venue, now + 8 * dia + 1)).isEqualTo(0)
        assertThat(dao.getById("a1")).isNotNull()
    }
}

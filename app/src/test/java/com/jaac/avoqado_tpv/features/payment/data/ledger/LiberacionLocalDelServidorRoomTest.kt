package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.gson.JsonObject
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 🔴 «Ninguna terminal muerta» (founder, 22-sep), pieza C · el camino del SERVIDOR para un cobro LOCAL.
 *
 * Un **Pago rápido** —cobro iniciado EN la terminal— no tiene solicitud del POS, así que la señal de liberación que
 * la app lee hoy (`request.outcome = NOT_CHARGED`) no existe para él: `request` viene `null`. El servidor publica la
 * declaración del cajero en el campo ADITIVO `attempt.resolution` (pieza B), y esta suite fija que la terminal la lea
 * y cierre SU fila — que es lo que devuelve el aparato al cajero.
 *
 * 🔴 Lo que NO puede debilitarse, y por eso cada candado tiene su prueba: la liberación local cierra SÓLO filas sin
 * solicitud (las del POS conservan su camino, que además suelta la ranura del servidor), y el DINERO manda siempre —
 * una aprobación del host, la evidencia durable del procesador o un veredicto con dinero la rechazan.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class LiberacionLocalDelServidorRoomTest {

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

    /** Una fila que aparta el aparato. Por defecto: LOCAL (sin solicitud del POS) y sin nada que acredite dinero. */
    private suspend fun fila(
        attemptId: String = "a1",
        state: String = PaymentAttemptEntity.STATE_INDETERMINADO,
        requestId: String? = null,
        hostApproved: Boolean? = null,
        serverOutcome: String? = null,
        evidencia: String? = null,
    ) = dao.insert(
        PaymentAttemptEntity(
            attemptId = attemptId, venueId = venue, processor = "ANGELPAY", state = state,
            amountCents = 4000, tipCents = 0, recordingRoute = "FAST", paymentContextJson = """{"amount":40.00}""",
            hostApproved = hostApproved, terminalPaymentRequestId = requestId,
            serverOutcome = serverOutcome, serverProcessorEvidence = evidencia,
            createdAt = now - 600_000, updatedAt = now - 600_000,
        ),
    )

    /** La declaración tal como la publica el servidor en `attempt.resolution` (el `OperatorResolution` de la pieza B). */
    private fun declaracion(kind: String = "NO_INSTRUMENT_PRESENTED"): JsonObject = JsonObject().apply {
        addProperty("id", "res-1")
        addProperty("kind", kind)
        addProperty("acceptedAt", "2026-09-22T12:00:00.000Z")
        addProperty("by", "SESSION")
    }

    /** Respuesta de S6 para un cobro LOCAL: sin `requestId` y sin `request` — el servidor no tiene solicitud que proyectar. */
    private fun s6Local(resolution: JsonObject? = null, outcome: String = "NOT_RECORDED", evidencia: String = "NONE") =
        TerminalAttemptStatusResponse(
            success = true, attemptId = "a1", requestId = null, request = null,
            attempt = TerminalAttemptResultDto(attemptId = "a1", outcome = outcome, processorEvidence = evidencia, resolution = resolution),
        )

    // ── El camino que destraba ────────────────────────────────────────────────────────────────

    @Test fun `P1 el servidor declaro un cobro LOCAL - la terminal lo lee y queda libre`() = runTest {
        fila()

        val liberacion = LiberacionDelServidor.desdeConsultaS6(venue, "a1", s6Local(declaracion()))
        assertThat(liberacion).isNotNull()
        assertThat(liberacion!!.evidencia).isEqualTo("OPERATOR_RECONCILED")
        assertThat(liberacion.requestId).isNull()   // un cobro local no tiene solicitud: la pertenencia es «sin solicitud»

        assertThat(ledger.aplicarLiberacionDelServidor(liberacion, now).getOrNull()).isTrue()
        assertThat(dao.getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
        assertThat(dao.findTerminalHold()).isNull()   // ← lo que el cajero necesita: poder volver a cobrar
    }

    @Test fun `sin declaracion no hay liberacion - NOT_RECORDED es «no se» y nunca «no se cobro»`() = runTest {
        assertThat(LiberacionDelServidor.desdeConsultaS6(venue, "a1", s6Local(resolution = null))).isNull()
    }

    @Test fun `una declaracion de OTRA clase no libera nada`() = runTest {
        assertThat(LiberacionDelServidor.desdeConsultaS6(venue, "a1", s6Local(declaracion(kind = "OTRA_COSA")))).isNull()
    }

    // ── El dinero manda sobre el testimonio, igual que en el carril del POS ───────────────────

    @Test fun `P1 con dinero acreditado por el servidor NO hay liberacion aunque haya declaracion`() = runTest {
        // Una aprobación TARDÍA del banco no borra la declaración en el servidor: conviven. Aquí manda el dinero.
        assertThat(LiberacionDelServidor.desdeConsultaS6(venue, "a1", s6Local(declaracion(), evidencia = "APPROVED"))).isNull()
        assertThat(LiberacionDelServidor.desdeConsultaS6(venue, "a1", s6Local(declaracion(), outcome = "RECORDED"))).isNull()
    }

    @Test fun `P1 el CAS local rechaza una aprobacion del host, la evidencia durable y un veredicto con dinero`() = runTest {
        fila("a2", hostApproved = true)
        fila("a3", evidencia = PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED)
        fila("a4", serverOutcome = PaymentAttemptEntity.SERVER_RECORDED)

        for (id in listOf("a2", "a3", "a4")) {
            val l = LiberacionDelServidor(venue, id, null, "OPERATOR_RECONCILED")
            assertThat(ledger.aplicarLiberacionDelServidor(l, now).getOrNull()).isFalse()
            assertThat(dao.getById(id)!!.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
        }
    }

    // ── Pertenencia: la liberación local NO alcanza a un cobro del POS ───────────────────────

    @Test fun `P1 una fila CON solicitud del POS no se cierra por la via local`() = runTest {
        fila("a5", requestId = "req-1")

        val l = LiberacionDelServidor(venue, "a5", null, "OPERATOR_RECONCILED")
        assertThat(ledger.aplicarLiberacionDelServidor(l, now).getOrNull()).isFalse()
        assertThat(dao.getById("a5")!!.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
    }

    @Test fun `P1 y al reves - una liberacion CON solicitud no cierra una fila local`() = runTest {
        fila("a6")

        val l = LiberacionDelServidor(venue, "a6", "req-9", "OPERATOR_RECONCILED")
        assertThat(ledger.aplicarLiberacionDelServidor(l, now).getOrNull()).isFalse()
        assertThat(dao.getById("a6")!!.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
    }

    @Test fun `una fila de OTRO venue o heredada no se libera`() = runTest {
        dao.insert(
            PaymentAttemptEntity(
                attemptId = "a7", venueId = "otro-venue", processor = "ANGELPAY", state = PaymentAttemptEntity.STATE_INDETERMINADO,
                amountCents = 4000, tipCents = 0, recordingRoute = "FAST", paymentContextJson = "{}",
                createdAt = now, updatedAt = now,
            ),
        )
        dao.insert(
            PaymentAttemptEntity(
                attemptId = "a8", venueId = venue, processor = "ANGELPAY", state = PaymentAttemptEntity.STATE_INDETERMINADO,
                amountCents = 4000, tipCents = 0, recordingRoute = "FAST", paymentContextJson = "{}",
                legacyShadow = true, createdAt = now, updatedAt = now,
            ),
        )
        for (id in listOf("a7", "a8")) {
            assertThat(ledger.aplicarLiberacionDelServidor(LiberacionDelServidor(venue, id, null, "OPERATOR_RECONCILED"), now).getOrNull()).isFalse()
        }
    }

    // ── Codex r5-4: los avisos del servidor tienen que ser DURABLES, no vivir en RAM ─────────

    @Test fun `P1 r5-4 · un veto del servidor se GUARDA y bloquea una liberacion posterior`() = runTest {
        // Escenario real: tres consumidores concurrentes (sondeo de la pantalla, recuperación inmediata y worker).
        // Una consulta trae la contradicción; otra, LIMPIA y ATRASADA, llega después. Con el veto sólo en RAM, el CAS
        // dejaba pasar la respuesta vieja y liberaba la venta. Sólo el dinero propio tenía protección durable.
        fila("v1")
        assertThat(ledger.marcarVetoDelServidor(venue, "v1", PaymentAttemptEntity.VETO_PAYMENT_CONTRADICTION).getOrNull()).isTrue()

        val liberacionAtrasada = LiberacionDelServidor(venue, "v1", null, "OPERATOR_RECONCILED")
        assertThat(ledger.aplicarLiberacionDelServidor(liberacionAtrasada, now).getOrNull()).isFalse()
        assertThat(dao.getById("v1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
    }

    @Test fun `P1 r5-4b · el veto tampoco deja pasar la liberacion del carril CON solicitud`() = runTest {
        fila("v2", requestId = "req-v2")
        ledger.marcarVetoDelServidor(venue, "v2", PaymentAttemptEntity.VETO_UNATTRIBUTED_EVIDENCE)

        val l = LiberacionDelServidor(venue, "v2", "req-v2", "NO_EVIDENCE_AFTER_WINDOW")
        assertThat(ledger.aplicarLiberacionDelServidor(l, now).getOrNull()).isFalse()
        assertThat(dao.getById("v2")!!.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
    }

    @Test fun `r5-4c · el PRIMER veto no se pisa - conserva por qué se bloqueó`() = runTest {
        fila("v3")
        ledger.marcarVetoDelServidor(venue, "v3", PaymentAttemptEntity.VETO_PAYMENT_CONTRADICTION)
        ledger.marcarVetoDelServidor(venue, "v3", PaymentAttemptEntity.VETO_EVIDENCE_CONTRADICTION)
        assertThat(dao.getById("v3")!!.serverVeto).isEqualTo(PaymentAttemptEntity.VETO_PAYMENT_CONTRADICTION)
    }

    // ── Codex r6 (P1-3): el veto tiene que valer para las TRES salidas, no sólo para la del servidor ──

    @Test fun `P1 r6 P1-3 · el veto durable tambien bloquea la DECLARACION del cajero`() = runTest {
        // El veto existe porque el servidor vio algo que PODRÍA ser dinero de este intento. Que lo respete la
        // liberación del servidor y no la declaración de una persona es al revés de lo que pesa cada evidencia:
        // el testimonio del cajero es la más débil de las tres. Sin esto, un cobro con contradicción publicada se
        // soltaba con un toque — y el cliente puede haber pagado.
        dao.insert(
            PaymentAttemptEntity(
                attemptId = "d1", venueId = venue, processor = "ANGELPAY", state = PaymentAttemptEntity.STATE_INDETERMINADO,
                amountCents = 4000, tipCents = 0, recordingRoute = "FAST", paymentContextJson = "{}",
                serverCheckedAt = now - 60_000,   // al servidor SÍ se le preguntó y CONTESTÓ: los otros cinco candados pasan
                serverAnsweredAt = now - 60_000,
                createdAt = now - 600_000, updatedAt = now - 600_000,
            ),
        )
        assertThat(ledger.marcarVetoDelServidor(venue, "d1", PaymentAttemptEntity.VETO_UNATTRIBUTED_EVIDENCE).getOrNull()).isTrue()

        assertThat(ledger.declararSinCobroLocal("d1", venue, "Cajera Ana")).isFalse()
        assertThat(dao.getById("d1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
        assertThat(dao.findTerminalHold()).isNotNull()   // sigue apartada, que es lo correcto con dinero en duda
    }

    @Test fun `P1 r6 P1-3b · y el boton ni se ofrece`() = runTest {
        // Revalidar dentro del CAS es la garantía; no ofrecerlo es lo que evita que el cajero toque un botón que va
        // a fallar. Las dos capas, como en el resto de la libreta.
        dao.insert(
            PaymentAttemptEntity(
                attemptId = "d2", venueId = venue, processor = "ANGELPAY", state = PaymentAttemptEntity.STATE_INDETERMINADO,
                amountCents = 4000, tipCents = 0, recordingRoute = "FAST", paymentContextJson = "{}",
                serverCheckedAt = now - 60_000,
                serverAnsweredAt = now - 60_000,
                createdAt = now - 600_000, updatedAt = now - 600_000,
            ),
        )
        assertThat(ledger.retencionLocalDeclarable()?.attemptId).isEqualTo("d2")   // control: sin veto sí se ofrece

        ledger.marcarVetoDelServidor(venue, "d2", PaymentAttemptEntity.VETO_PAYMENT_CONTRADICTION)
        assertThat(ledger.retencionLocalDeclarable()).isNull()
    }

    // ── Codex r6 (P1-4): un veto que llega DESPUÉS de una liberación no la deshace, pero SÍ se ve ─────

    @Test fun `P1 r6 P1-4 · un veto posterior a la liberacion la vuelve CONTRADICCION`() = runTest {
        // Escenario de Codex: una respuesta limpia libera la fila; otra posterior trae la contradicción y guarda el veto.
        // La liberación NO se deshace a propósito —reabrirla apartaría el aparato por algo sin salida acreditada, que es
        // justo la terminal muerta que este trabajo elimina—, pero la fila no puede quedar diciendo «se puede volver a
        // cobrar»: entra al aviso como contradicción y sobrevive a la poda.
        fila("p1")
        val l = LiberacionDelServidor(venue, "p1", null, "OPERATOR_RECONCILED")
        assertThat(ledger.aplicarLiberacionDelServidor(l, now).getOrNull()).isTrue()
        assertThat(dao.esContradiccion("p1")).isFalse()   // control: liberada limpia NO es contradicción

        ledger.marcarVetoDelServidor(venue, "p1", PaymentAttemptEntity.VETO_PAYMENT_CONTRADICTION)

        assertThat(dao.esContradiccion("p1")).isTrue()
        assertThat(dao.getById("p1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)   // no se reabre
    }

    @Test fun `r6 P1-4b y r23 · un intento que acaba REGISTRADO con un veto sigue VISIBLE como contradiccion, sin apartar el aparato`() = runTest {
        fila("p2")
        ledger.marcarVetoDelServidor(venue, "p2", PaymentAttemptEntity.VETO_UNATTRIBUTED_EVIDENCE)
        assertThat(dao.esContradiccion("p2")).isTrue()

        dao.casTransition("p2", listOf(PaymentAttemptEntity.STATE_INDETERMINADO), PaymentAttemptEntity.STATE_REGISTRADO, now)
        // 🔴 Codex r21 (P1-1): la «escapatoria» de r6 (REGISTRADO deja de ser contradicción) hacía DESAPARECER un veto que llegaba
        // después del registro. Ahora sigue a la vista (aviso de Inicio, fuera de la poda) — pero no aparta el aparato: el cobro
        // está registrado, y apartarlo sin salida acreditada sería la terminal muerta.
        assertThat(dao.esContradiccion("p2")).isTrue()
        assertWithMessage("no aparta el aparato").that(
            dao.reserveTerminal("otro", venue, "ANGELPAY", "SALE", 5000, 0, "FAST", """{"amount":50.00}""", null, now + 10_000, null, false),
        ).isNotEqualTo(-1L)
    }

    @Test fun `la liberacion local es idempotente - la segunda vez no cambia nada`() = runTest {
        fila("a9")
        val l = LiberacionDelServidor(venue, "a9", null, "OPERATOR_RECONCILED")
        assertThat(ledger.aplicarLiberacionDelServidor(l, now).getOrNull()).isTrue()
        assertThat(ledger.aplicarLiberacionDelServidor(l, now + 1).getOrNull()).isFalse()
    }
}

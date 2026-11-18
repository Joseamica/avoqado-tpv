package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
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
 * 🔴 D22 · A1 (founder, 17-nov: «Sí, A1»): «Ya revisé la terminal: no se cobró» de un Pago rápido llega a Avoqado, que la publica
 * en S6 como `attempt.resolution.kind = UNCHARGED_VERIFIED`. Lo que esta suite fija, con la base de Room de verdad:
 *  · la clase nueva cierra la duda como la declaración del CAJERO (`declarado_sin_cobro:<quien>:avoqado`), nunca como «no presentó
 *    tarjeta» (`OPERATOR_NO_INSTRUMENT`) ni como el silencio del banco — la trampa de `desdeDeclaracion` (`?: OPERATOR_RECONCILED`);
 *  · si la app murió después del envío, la siguiente consulta la cierra (la recuperación);
 *  · si la respuesta se perdió y la libreta ya la había cerrado SIN la marca, la consulta sólo le agrega la marca (Codex plan r1);
 *  · el dinero manda, y un cobro del POS nunca se cierra por aquí.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class DeclaracionRevisadaEnAvoqadoRoomTest {

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

    private suspend fun fila(
        processor: String = "BLUMON",
        state: String = PaymentAttemptEntity.STATE_INDETERMINADO,
        requestId: String? = null,
        lastError: String? = null,
        serverAnsweredAt: Long? = now - 60_000,
        evidencia: String? = null,
    ) = dao.insert(
        PaymentAttemptEntity(
            attemptId = "a1", venueId = venue, processor = processor, state = state,
            amountCents = 1250, tipCents = 100, recordingRoute = "FAST", paymentContextJson = "{}",
            terminalPaymentRequestId = requestId, lastError = lastError, serverAnsweredAt = serverAnsweredAt,
            serverProcessorEvidence = evidencia, createdAt = now - 600_000, updatedAt = now - 600_000,
        ),
    )

    // F1 (tarea 3): la revisada cuenta FIRMADA por el PIN de un gerente; `staffId` es entonces el gerente.
    private fun revisada(staffId: String? = "cajero-1", by: String = "SUPERVISOR_PIN"): JsonObject = JsonObject().apply {
        addProperty("id", "res-d22")
        addProperty("kind", "UNCHARGED_VERIFIED")
        addProperty("acceptedAt", "2026-11-17T18:00:00.000Z")
        addProperty("by", by)
        if (staffId != null) addProperty("staffId", staffId)
        addProperty("amountCents", 1250)
        addProperty("tipCents", 100)
    }

    private fun s6Local(resolution: JsonObject?, evidencia: String = "NONE") = TerminalAttemptStatusResponse(
        success = true, attemptId = "a1", requestId = null, request = null,
        attempt = TerminalAttemptResultDto(attemptId = "a1", outcome = "NOT_RECORDED", processorEvidence = evidencia, resolution = resolution),
    )

    @Test fun `P1 la clase nueva se lee como la declaracion del cajero, con su autor, y nunca como no presento tarjeta`() {
        assertThat(LiberacionDelServidor.evidenciaDeLaDeclaracion("UNCHARGED_VERIFIED")).isEqualTo(LiberacionDelServidor.CAJERO_REVISO)
        val porS6 = LiberacionDelServidor.desdeConsultaS6(venue, "a1", s6Local(revisada()))!!
        assertThat(porS6.evidencia).isEqualTo(LiberacionDelServidor.CAJERO_REVISO)
        assertThat(porS6.quien).isEqualTo("cajero-1")
        assertThat(porS6.requestId).isNull()
        // La trampa: el 2xx de la declaración caía en «no presentó tarjeta» con una clase que no conocía.
        val porElPost = LiberacionDelServidor.desdeDeclaracion(venue, "a1", null, s6Local(revisada()))!!
        assertThat(porElPost.evidencia).isEqualTo(LiberacionDelServidor.CAJERO_REVISO)
        assertThat(porElPost.evidencia).isNotEqualTo("OPERATOR_RECONCILED")
        // F1 (Codex código r1 #3): el acuse viejo por la sesión que Avoqado ya guardó libera, a nombre de quien lo declaró.
        assertThat(LiberacionDelServidor.desdeConsultaS6(venue, "a1", s6Local(revisada(by = "SESSION")))?.quien).isEqualTo("cajero-1")
        assertThat(LiberacionDelServidor.desdeDeclaracion(venue, "a1", null, s6Local(revisada(by = "SESSION")))?.quien).isEqualTo("cajero-1")
        // Sin quién la declaró (automática, o una respuesta sin `by`) no libera, ni por S6 ni por el POST.
        assertThat(LiberacionDelServidor.desdeConsultaS6(venue, "a1", s6Local(revisada(by = "AUTOMATIC")))).isNull()
        assertThat(LiberacionDelServidor.desdeDeclaracion(venue, "a1", null, s6Local(revisada(by = "AUTOMATIC")))).isNull()
        assertThat(LiberacionDelServidor.desdeConsultaS6(venue, "a1", s6Local(revisada().apply { remove("by") }))).isNull()
    }

    @Test fun `P1 la app murio despues del envio - la consulta cierra la duda marcada en Avoqado (PAX y Nexgo)`() = runTest {
        for (processor in listOf("BLUMON", "ANGELPAY")) {
            db.clearAllTables()
            fila(processor = processor)
            val l = LiberacionDelServidor.desdeConsultaS6(venue, "a1", s6Local(revisada()))!!
            assertThat(ledger.aplicarLiberacionDelServidor(l, now).getOrNull()).isTrue()
            val f = dao.getById("a1")!!
            assertThat(f.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
            assertThat(f.lastError).isEqualTo("declarado_sin_cobro:cajero-1:avoqado")
            assertThat(f.serverOutcome).isNull()   // ni OPERATOR_NO_INSTRUMENT ni RELEASED_NO_EVIDENCE: no es lo que se afirmó
            assertThat(PaymentAttemptEntity.declaradoEnAvoqado(f.lastError)).isTrue()
            assertThat(ledger.retencionDelAparato()).isNull()
        }
    }

    @Test fun `P1 respuesta perdida - la libreta la cerro sin marca y la consulta sólo le agrega la marca (Codex plan r1)`() = runTest {
        fila(state = PaymentAttemptEntity.STATE_DESCARTADA, lastError = "declarado_sin_cobro:cajero-1")
        val antes = dao.getById("a1")!!
        val l = LiberacionDelServidor.desdeConsultaS6(venue, "a1", s6Local(revisada()))!!
        // No transiciona (ya estaba cerrada): no cuenta como liberación nueva.
        assertThat(ledger.aplicarLiberacionDelServidor(l, now).getOrNull()).isFalse()
        val f = dao.getById("a1")!!
        assertThat(f.lastError).isEqualTo("declarado_sin_cobro:cajero-1:avoqado")
        assertThat(f.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
        assertThat(f.updatedAt).isEqualTo(antes.updatedAt)   // la poda sigue midiendo desde la declaración
        // Idempotente: otra consulta no la duplica.
        ledger.aplicarLiberacionDelServidor(l, now + 1)
        assertThat(dao.getById("a1")!!.lastError).isEqualTo("declarado_sin_cobro:cajero-1:avoqado")
    }

    @Test fun `la marca no se agrega a otra cosa - una liberacion del servidor, un cobro del POS o una fila con dinero`() = runTest {
        fila(state = PaymentAttemptEntity.STATE_DESCARTADA, lastError = PaymentAttemptEntity.LAST_ERROR_LIBERADA_PREFIX + "OPERATOR_RECONCILED")
        assertThat(dao.marcarDeclaracionEnAvoqado("a1", venue, PaymentAttemptEntity.SUFIJO_DECLARADO_EN_AVOQADO)).isEqualTo(0)
        db.clearAllTables()
        fila(state = PaymentAttemptEntity.STATE_DESCARTADA, lastError = "declarado_sin_cobro:cajero-1", evidencia = "APPROVED")
        assertThat(dao.marcarDeclaracionEnAvoqado("a1", venue, PaymentAttemptEntity.SUFIJO_DECLARADO_EN_AVOQADO)).isEqualTo(0)
        db.clearAllTables()
        fila(state = PaymentAttemptEntity.STATE_DESCARTADA, lastError = "declarado_sin_cobro:cajero-1", requestId = "req-1")
        assertThat(dao.marcarDeclaracionEnAvoqado("a1", venue, PaymentAttemptEntity.SUFIJO_DECLARADO_EN_AVOQADO)).isEqualTo(0)
    }

    @Test fun `P1 nunca cierra un cobro del POS ni una duda con dinero por esta clase`() = runTest {
        fila(requestId = "req-1")
        val conSolicitud = LiberacionDelServidor("venue-1", "a1", "req-1", LiberacionDelServidor.CAJERO_REVISO, "cajero-1")
        assertThat(ledger.aplicarLiberacionDelServidor(conSolicitud, now).getOrNull()).isFalse()
        assertThat(dao.getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)

        db.clearAllTables()
        fila(evidencia = "APPROVED")
        // S6 con la aprobación del banco no produce liberación; y aunque llegara una, el CAS la rechaza.
        assertThat(LiberacionDelServidor.desdeConsultaS6(venue, "a1", s6Local(revisada(), evidencia = "APPROVED"))).isNull()
        val l = LiberacionDelServidor(venue, "a1", null, LiberacionDelServidor.CAJERO_REVISO, "cajero-1")
        assertThat(ledger.aplicarLiberacionDelServidor(l, now).getOrNull()).isFalse()
        assertThat(dao.getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
    }

    @Test fun `la declaracion local de hoy no cambia - sin red queda sin marca, con Avoqado lleva la marca`() = runTest {
        fila()
        assertThat(ledger.declararSinCobroLocal("a1", venue, "cajero-1")).isTrue()
        assertThat(dao.getById("a1")!!.lastError).isEqualTo("declarado_sin_cobro:cajero-1")
        db.clearAllTables()
        fila()
        assertThat(ledger.declararSinCobroLocal("a1", venue, "cajero-1", enAvoqado = true)).isTrue()
        assertThat(dao.getById("a1")!!.lastError).isEqualTo("declarado_sin_cobro:cajero-1:avoqado")
        assertThat(PaymentAttemptEntity.declaradoEnAvoqado("declarado_sin_cobro:cajero-1")).isFalse()
        assertThat(PaymentAttemptEntity.declaradoEnAvoqado(null)).isFalse()
    }
}

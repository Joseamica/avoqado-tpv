package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.gson.JsonObject
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 🔴 F1 cierre TPV (I4, `plan-cierre-familias.md` §2): UN validador ([LiberacionDelServidor.desdeAcuse]) decide si un acuse del SERVIDOR
 * libera esta fila, y UN escritor ([PaymentAttemptLedger.aplicarLiberacionDelServidor]) la cierra. Antes, la rama por solicitud de S6
 * liberaba `OPERATOR_RECONCILED` sin mirar el comprobante, y la ventana automática salía con el nombre de quien tenía la sesión.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class AplicarAcuseRoomTest {
    private val venue = "venue-1"
    private val now = 1_800_000_000_000L
    private lateinit var db: AvoqadoDatabase
    private lateinit var dao: PaymentAttemptDao
    private lateinit var ledger: PaymentAttemptLedger

    @Before fun abrir() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.paymentAttemptDao()
        ledger = PaymentAttemptLedger(dao, mockk(relaxed = true))
    }

    @After fun cerrar() = db.close()

    private suspend fun enDuda(attemptId: String, requestId: String?) = dao.insert(
        PaymentAttemptEntity(
            attemptId = attemptId, venueId = venue, processor = "ANGELPAY", kind = "SALE", state = "INDETERMINADO",
            amountCents = 10000, tipCents = 0, recordingRoute = "FAST",
            paymentContextJson = """{"venueId":"$venue"""" + (requestId?.let { ""","terminalPaymentRequestId":"$it"""" } ?: "") + "}",
            createdAt = now - 60_000, updatedAt = now - 60_000, terminalPaymentRequestId = requestId,
        ),
    )

    private fun recibo(
        kind: String = "UNCHARGED_VERIFIED", by: String? = "SUPERVISOR_PIN", staffId: String? = "gerente-1", scope: String = "REQUEST",
        attemptId: String? = null, requestId: String? = "req-1", humano: Boolean = true,
    ) = ComprobanteDeDeclaracion(
        id = "res-1", kind = kind, by = by, staffId = staffId, acceptedAt = "2026-11-19T12:00:00.000Z", scope = scope,
        attemptId = attemptId, requestId = requestId, humanSignature = humano, alreadyStored = true,
    )

    private fun s6PorSolicitud(evidencia: String, comprobante: ComprobanteDeDeclaracion?) = TerminalAttemptStatusResponse(
        success = true, declarationReceipt = comprobante, attemptId = "a1", requestId = "req-1",
        attempt = TerminalAttemptResultDto(attemptId = "a1", outcome = "NOT_RECORDED"),
        request = JsonObject().apply {
            addProperty("requestId", "req-1"); addProperty("outcome", "NOT_CHARGED"); addProperty("outcomeEvidence", evidencia)
        },
    )

    @Test fun `S6_por_solicitud_OPERATOR_RECONCILED_sin_autor_no_libera`() = runBlocking<Unit> {
        enDuda("a1", "req-1")
        for ((caso, comprobante) in listOf(
            "automático" to recibo(by = "AUTOMATIC", staffId = null, humano = false),
            "sin autor" to recibo(staffId = null),
            "sin firma humana" to recibo(humano = false),
            "de otra solicitud" to recibo(requestId = "req-2"),
            "de otro intento" to recibo(scope = "ATTEMPT", attemptId = "otro", requestId = null),
            "clase que no es de una persona" to recibo(kind = "BANK_DECLINED"),
        )) {
            assertWithMessage(caso).that(LiberacionDelServidor.desdeConsultaS6(venue, "a1", s6PorSolicitud("OPERATOR_RECONCILED", comprobante))).isNull()
        }
        assertThat(dao.getById("a1")!!.state).isEqualTo("INDETERMINADO")
        // ponytail (SERVIDOR anterior a declarationReceipt): sin la llave, la regla vieja sigue liberando por la solicitud.
        assertWithMessage("sin comprobante, la regla vieja")
            .that(LiberacionDelServidor.desdeConsultaS6(venue, "a1", s6PorSolicitud("OPERATOR_RECONCILED", null))).isNotNull()
    }

    @Test fun `S6_por_solicitud_con_comprobante_humano_libera_una_vez`() = runBlocking<Unit> {
        enDuda("a1", "req-1")
        val liberacion = LiberacionDelServidor.desdeConsultaS6(venue, "a1", s6PorSolicitud("OPERATOR_RECONCILED", recibo()))!!
        assertThat(liberacion.requestId).isEqualTo("req-1")
        assertWithMessage("a nombre de quien dice el comprobante").that(liberacion.quien).isEqualTo("gerente-1")
        // También con el comprobante del INTENTO (S6 da el del intento si lo tiene).
        assertThat(LiberacionDelServidor.desdeConsultaS6(venue, "a1", s6PorSolicitud("OPERATOR_RECONCILED", recibo(scope = "ATTEMPT", attemptId = "a1", requestId = null)))).isNotNull()
        assertThat(ledger.aplicarLiberacionDelServidor(liberacion, now).getOrThrow()).isTrue()
        assertWithMessage("una vez").that(ledger.aplicarLiberacionDelServidor(liberacion, now + 1).getOrThrow()).isFalse()
        val fila = dao.getById("a1")!!
        assertThat(fila.state).isEqualTo("DESCARTADA")
        assertThat(fila.serverOutcome).isEqualTo(PaymentAttemptEntity.SERVER_OPERATOR_NO_INSTRUMENT)
    }

    @Test fun `ventana_automatica_libera_sin_pedir_autor`() = runBlocking<Unit> {
        enDuda("local", null)
        enDuda("a1", "req-1")
        // La terminal declara el silencio del banco; el testimonio trae a quien tenía la sesión, el comprobante dice AUTOMATIC.
        val testimonio = JsonObject().apply {
            addProperty("kind", "NO_BANK_TRACE_AFTER_WINDOW"); addProperty("by", "AUTOMATIC"); addProperty("staffId", "cajero")
        }
        val automatico = recibo(kind = "NO_BANK_TRACE_AFTER_WINDOW", by = "AUTOMATIC", staffId = null, scope = "ATTEMPT", attemptId = "local", requestId = null, humano = false)
        val s6Local = TerminalAttemptStatusResponse(
            success = true, declarationReceipt = automatico, attemptId = "local",
            attempt = TerminalAttemptResultDto(attemptId = "local", outcome = "NOT_RECORDED", resolution = testimonio),
        )
        for ((caso, l) in listOf(
            "S6 de un Pago rápido" to LiberacionDelServidor.desdeConsultaS6(venue, "local", s6Local),
            "el 2xx de la liberación sola" to LiberacionDelServidor.desdeDeclaracion(venue, "local", null, s6Local),
            "sin comprobante (regla vieja)" to LiberacionDelServidor.desdeDeclaracion(venue, "local", null, s6Local.copy(declarationReceipt = null)),
        )) {
            assertWithMessage(caso).that(l?.evidencia).isEqualTo("NO_EVIDENCE_AFTER_WINDOW")
            assertWithMessage("$caso: no es la firma de nadie").that(l?.quien).isNull()
        }
        val porSolicitud = LiberacionDelServidor.desdeConsultaS6(
            venue, "a1", s6PorSolicitud("NO_EVIDENCE_AFTER_WINDOW", recibo(by = "AUTOMATIC", staffId = null, humano = false)),
        )
        assertWithMessage("la ventana del POS tampoco pide autor").that(porSolicitud?.evidencia).isEqualTo("NO_EVIDENCE_AFTER_WINDOW")
        assertThat(ledger.aplicarLiberacionDelServidor(LiberacionDelServidor.desdeConsultaS6(venue, "local", s6Local)!!, now).getOrThrow()).isTrue()
        assertThat(ledger.aplicarLiberacionDelServidor(porSolicitud!!, now).getOrThrow()).isTrue()
        for (id in listOf("local", "a1")) assertThat(dao.getById(id)!!.serverOutcome).isEqualTo(PaymentAttemptEntity.SERVER_RELEASED_NO_EVIDENCE)
    }
}

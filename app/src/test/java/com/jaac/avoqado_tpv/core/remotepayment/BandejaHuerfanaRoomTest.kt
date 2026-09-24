package com.jaac.avoqado_tpv.core.remotepayment

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 🔴 Pieza D — el aviso que NADIE podía quitar (hallazgo del QA en hardware, 22-sep-2026).
 *
 * Medido en una Nexgo N86: llevaba **25 horas** mostrando «Quedó un cobro de $50.00 sin confirmar», con el
 * contador subiendo. Los tres lados decían cosas distintas:
 *
 *  · **servidor** → la solicitud estaba `FAILED / OPERATOR_RECONCILED_NO_CHARGE`: resuelta el día anterior;
 *  · **bandeja de la terminal** → `PROCESSING`, como si siguiera en curso;
 *  · **libreta** → CERO intentos de esa solicitud.
 *
 * Y nada podía alcanzarla: toda la recuperación consulta por INTENTO, y esa fila no tiene intento. El cajero
 * veía un aviso permanente sobre dinero ya conciliado y no tenía forma de quitarlo — «Revisar» sólo lleva al
 * historial de pagos.
 *
 * 🔴 Lo que esta suite fija, y no puede debilitarse: **sólo se concilia lo que el servidor declara resuelto**.
 * Una solicitud en vuelo, o una que el servidor no reconoce como de esta terminal, se conserva intacta — cerrar
 * la bandeja de un cobro que podría estar vivo sería exactamente el defecto contrario, y ese cuesta dinero.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class BandejaHuerfanaRoomTest {

    private lateinit var db: AvoqadoDatabase
    private lateinit var dao: RemotePaymentRequestDao
    private val venue = "venue-1"
    private val now = 1_700_000_000_000L

    @Before fun abrir() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.remotePaymentRequestDao()
    }

    @After fun cerrar() = db.close()

    /** Una fila de bandeja en curso, como la que deja un cobro del POS que se reclamó. */
    private suspend fun enBandeja(
        requestId: String,
        status: String = RemotePaymentRequestEntity.STATUS_PROCESSING,
        // `desfase` separa los `updated_at` para que el orden de la rotación (P2-11) sea determinista y no dependa del
        // orden de inserción de SQLite ante empates.
        desfase: Long = 0,
    ) = dao.insert(
        RemotePaymentRequestEntity(
            requestId = requestId, venueId = venue, amountCents = 5000, tipCents = 0,
            rating = null, skipReview = true, orderId = null, processedByStaffId = null,
            senderDeviceName = "POS de pruebas", sourceTimestamp = "2026-09-22T12:00:00.000Z",
            status = status, createdAt = now - 25 * 3_600_000, updatedAt = now - 25 * 3_600_000 + desfase,
        ),
    )

    @Test fun `P1 D · una fila PROCESSING SIN intento es candidata a conciliar`() = runTest {
        enBandeja("req-huerfana")

        val candidatas = dao.candidatasHuerfanas(venue)
        assertThat(candidatas.map { it.requestId }).containsExactly("req-huerfana")
    }

    @Test fun `P1 D · el servidor la declara resuelta ⇒ la bandeja se cierra y el aviso desaparece`() = runTest {
        enBandeja("req-huerfana")
        assertThat(dao.observePendingObligationsCount(venue)).isEqualTo(1)   // el aviso está puesto

        val n = dao.conciliarHuerfanaConElServidor("req-huerfana", venue, "FAILED", "OPERATOR_RECONCILED_NO_CHARGE", now)
        assertThat(n).isEqualTo(1)

        assertThat(dao.getById("req-huerfana")!!.status).isEqualTo(RemotePaymentRequestEntity.STATUS_RESOLVED)
        assertThat(dao.observePendingObligationsCount(venue)).isEqualTo(0)   // ← lo que el cajero necesita
    }

    @Test fun `P1 D · una fila que SÍ tiene intento no se toca - esa la resuelve la libreta`() = runTest {
        enBandeja("req-con-intento")
        db.paymentAttemptDao().insert(
            com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity(
                attemptId = "a1", venueId = venue, processor = "ANGELPAY",
                state = com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity.STATE_INDETERMINADO,
                amountCents = 5000, tipCents = 0, recordingRoute = "FAST",
                paymentContextJson = """{"terminalPaymentRequestId":"req-con-intento"}""",
                createdAt = now, updatedAt = now,
            ),
        )

        assertThat(dao.candidatasHuerfanas(venue)).isEmpty()
    }

    @Test fun `P1 D · sólo se cierra lo que el servidor declara RESUELTO`() = runTest {
        enBandeja("req-viva")
        // Sin desenlace del servidor no se concilia: cerrar la bandeja de un cobro que puede estar vivo es el
        // defecto contrario, y ése cuesta dinero.
        assertThat(dao.conciliarHuerfanaConElServidor("req-viva", venue, "SENT", null, now)).isEqualTo(0)
        assertThat(dao.getById("req-viva")!!.status).isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
    }

    @Test fun `P1 D · una fila de OTRO venue no se concilia`() = runTest {
        enBandeja("req-ajena")
        assertThat(dao.conciliarHuerfanaConElServidor("req-ajena", "otro-venue", "FAILED", "NO_EVIDENCE_AFTER_WINDOW", now))
            .isEqualTo(0)
        assertThat(dao.getById("req-ajena")!!.status).isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
    }

    // ── Codex r6: los tres P2 de la pieza D ───────────────────────────────────────────────────

    @Test fun `P1 r6 P2-8 · un intento que aparece ENTRE seleccionar y cerrar impide la conciliacion`() = runTest {
        // Reproducido por Codex: la orfandad se comprobaba al SELECCIONAR y no al ESCRIBIR, así que el UPDATE devolvía 1
        // aunque ya hubiera un intento correlacionado — y entonces la fila no es de la pieza D: la resuelve la libreta,
        // que es la que sabe de dinero.
        enBandeja("req-carrera")
        assertThat(dao.candidatasHuerfanas(venue).map { it.requestId }).containsExactly("req-carrera")

        db.paymentAttemptDao().insert(
            com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity(
                attemptId = "tarde", venueId = venue, processor = "ANGELPAY",
                state = com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity.STATE_AUTORIZANDO,
                amountCents = 5000, tipCents = 0, recordingRoute = "FAST",
                paymentContextJson = """{"terminalPaymentRequestId":"req-carrera"}""",
                createdAt = now, updatedAt = now,
            ),
        )

        assertThat(dao.conciliarHuerfanaConElServidor("req-carrera", venue, "FAILED", "OPERATOR_RECONCILED_NO_CHARGE", now)).isEqualTo(0)
        assertThat(dao.getById("req-carrera")!!.status).isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
    }

    @Test fun `P1 r6 P2-9 · el resultado guardado es REPRODUCIBLE - lleva requestId y el vocabulario del servidor`() = runTest {
        // La bandeja emite ese JSON tal cual en reentregas y sondas. Sin `requestId` y con `FAILED` en vez de `failed`
        // el servidor lo rechazaba; dejarlo NULL era peor (la sonda contestaría ACTIVE por algo YA cerrado).
        enBandeja("req-json")
        assertThat(dao.conciliarHuerfanaConElServidor("req-json", venue, "FAILED", "OPERATOR_RECONCILED_NO_CHARGE", now)).isEqualTo(1)

        val json = org.json.JSONObject(dao.getById("req-json")!!.finalResultJson!!)
        assertThat(json.getString("requestId")).isEqualTo("req-json")
        assertThat(json.getString("status")).isEqualTo("failed")
        assertThat(json.getString("failureCode")).isEqualTo("OPERATOR_RECONCILED_NO_CHARGE")
        assertThat(json.getBoolean("conciliadaPorElServidor")).isTrue()
    }

    @Test fun `P1 r6 P2-9b · un COMPLETED NO lo conciliesa la pieza D - hay dinero y eso es de la libreta`() = runTest {
        enBandeja("req-pagada")
        assertThat(dao.conciliarHuerfanaConElServidor("req-pagada", venue, "COMPLETED", null, now)).isEqualTo(0)
        assertThat(dao.getById("req-pagada")!!.status).isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
    }

    @Test fun `P1 r6 P2-11 · la rotacion evita que 20 huerfanas viejas tapen a la 21`() = runTest {
        // Reproducido por Codex: ordenando por `created_at`, pasadas sucesivas devuelven SIEMPRE las mismas 20 y la 21
        // no se consulta nunca. Con la estampa, la consultada que no se pudo cerrar va al final de la fila.
        repeat(21) { i -> enBandeja("r$i", desfase = i.toLong()) }
        val primera = dao.candidatasHuerfanas(venue).map { it.requestId }
        assertThat(primera).hasSize(20)
        assertThat(primera).doesNotContain("r20")

        primera.forEach { dao.estamparConsultaHuerfana(it, venue, now + 1_000) }
        assertThat(dao.candidatasHuerfanas(venue).map { it.requestId }).contains("r20")
    }

    @Test fun `r6 P2-11b · la estampa no toca una fila de otro venue ni una ya resuelta`() = runTest {
        enBandeja("req-est", status = RemotePaymentRequestEntity.STATUS_RESOLVED)
        assertThat(dao.estamparConsultaHuerfana("req-est", venue, now + 1)).isEqualTo(0)
        enBandeja("req-est2")
        assertThat(dao.estamparConsultaHuerfana("req-est2", "otro-venue", now + 1)).isEqualTo(0)
    }

    @Test fun `D · una fila ya RESUELTA no se vuelve a tocar`() = runTest {
        enBandeja("req-lista", status = RemotePaymentRequestEntity.STATUS_RESOLVED)
        assertThat(dao.conciliarHuerfanaConElServidor("req-lista", venue, "FAILED", "NO_EVIDENCE_AFTER_WINDOW", now))
            .isEqualTo(0)
    }

    // ═══ Codex r7 · P1-4: cerrar la huérfana tiene que cerrar también la POSIBILIDAD DE EJECUTAR ═══

    /** Un intento de ESA solicitud, en el estado que se pida (la forma compacta que escribe Gson en la libreta). */
    private suspend fun intentoDe(requestId: String, attemptId: String, state: String) = db.paymentAttemptDao().insert(
        com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity(
            attemptId = attemptId, venueId = venue, processor = "ANGELPAY", state = state,
            amountCents = 5000, tipCents = 0, recordingRoute = "FAST",
            paymentContextJson = """{"terminalPaymentRequestId":"$requestId"}""",
            createdAt = now, updatedAt = now, terminalPaymentRequestId = requestId,
        ),
    )

    @Test fun `P1 r7 P1-4 · con un intento en PREPARANDO o KERNEL_ACTIVO de esa solicitud, D NO la cierra`() = runTest {
        // Reproducido por Codex con el SQL real: el `NOT EXISTS` revalidado omitía los dos estados donde la ejecución ya
        // empezó, y `KERNEL_ACTIVO` ya puede aprobar localmente. D seleccionaba la huérfana, aparecía el intento mientras
        // volaba el HTTP, y cerraba igual.
        for ((req, estado) in listOf(
            "req-prep" to com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity.STATE_PREPARANDO,
            "req-kernel" to com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity.STATE_KERNEL_ACTIVO,
        )) {
            enBandeja(req)
            intentoDe(req, "a-$req", estado)
            assertThat(dao.conciliarHuerfanaConElServidor(req, venue, "FAILED", "OPERATOR_RECONCILED_NO_CHARGE", now)).isEqualTo(0)
            assertThat(dao.getById(req)!!.status).isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
        }
    }

    @Test fun `P1 r7 P1-4 · al cerrar una huerfana queda la cerca - ningun intento nuevo de esa solicitud se reserva despues`() = runTest {
        // Codex: el cierre no escribía `final_emitted_at`, que es la marca que consulta la barrera de reserva; pudo reservar
        // un intento de la misma solicitud DESPUÉS de cerrarla. Todos los demás caminos que resuelven la bandeja la escriben.
        enBandeja("req-cerrada")
        assertThat(dao.conciliarHuerfanaConElServidor("req-cerrada", venue, "FAILED", "OPERATOR_RECONCILED_NO_CHARGE", now)).isEqualTo(1)

        val pagos = db.paymentAttemptDao()
        assertThat(pagos.cercaDeSolicitud("req-cerrada")).isEqualTo("CERRADA")
        val ledger = com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger(pagos, io.mockk.mockk(relaxed = true))
        assertThat(
            ledger.openAttempt("tarde", venue, "ANGELPAY", 5000, 0, "FAST", """{"terminalPaymentRequestId":"req-cerrada"}"""),
        ).isFalse()
    }

    // ═══ Codex r7 · P2-9: la rotación también cuando la respuesta REVIENTA (el `catch` del recuperador) ═══

    @Test fun `r7 P2-9 · veinte respuestas que revientan al leerse no tapan a la 21 - el recuperador tambien estampa en el catch`() = runTest {
        // Codex: se estampaban los HTTP fallidos pero no el `catch`. Veinte solicitudes cuya respuesta falla siempre al
        // deserializar quedaban primeras para siempre, y la 21 —cuya respuesta sería válida— no se consultaba nunca. La prueba
        // anterior estampaba a mano; ésta pasa por el recuperador de verdad, dos pasadas.
        repeat(21) { i -> enBandeja("r$i", desfase = i.toLong()) }
        val consultadas = mutableListOf<String>()
        val api = io.mockk.mockk<com.jaac.avoqado_tpv.features.payment.data.ledger.TerminalAttemptApiService>()
        io.mockk.coEvery { api.getRequestStatus(venue, any()) } answers {
            consultadas += secondArg<String>()
            throw com.google.gson.JsonSyntaxException("respuesta ilegible")
        }
        val recuperador = BandejaServerRecovery(dao, api)

        recuperador.conciliar(venue, now + 1_000)
        assertThat(consultadas).doesNotContain("r20")
        consultadas.clear()
        recuperador.conciliar(venue, now + 2_000)
        assertThat(consultadas).contains("r20")
    }
}

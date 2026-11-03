package com.jaac.avoqado_tpv.core.remotepayment

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
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

    /** Lo que deja una pasada cuando el servidor contesta [codigo] a la única fila. */
    private suspend fun pasadaConHttp(codigo: Int): BandejaServerRecovery.Resultado {
        enBandeja("req-http-$codigo")
        val api = io.mockk.mockk<com.jaac.avoqado_tpv.features.payment.data.ledger.TerminalAttemptApiService>()
        io.mockk.coEvery { api.getRequestStatus(venue, any()) } returns retrofit2.Response.error(codigo, "{}".toResponseBody(null))
        return BandejaServerRecovery(dao, api).conciliar(venue, now + 1_000)
    }

    @Test fun `M-2 un 5xx no es una respuesta para el cajero, y la pasada del worker no se repite por el`() = runTest {
        val r = pasadaConHttp(503)

        assertThat(r.consultadas).isEqualTo(0)
        assertThat(r.conErrorDelServidor).isEqualTo(1)
        // `sinRespuesta` es lo que repite la pasada a los 31 s: el worker no reintenta un 5xx, igual que el de los intentos.
        assertThat(r.sinRespuesta).isEqualTo(0)
    }

    @Test fun `M-2 un 404 si es una respuesta`() = runTest {
        val r = pasadaConHttp(404)

        assertThat(r.consultadas).isEqualTo(1)
        assertThat(r.conErrorDelServidor).isEqualTo(0)
        assertThat(r.sinRespuesta).isEqualTo(0)
    }

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
        // D6: esta fila es heredada. Lo que acredita el SERVIDOR sí la cierra, y cerrarla no le inventa procedencia.
        assertThat(dao.getById("req-huerfana")!!.durableBarrierVersion).isEqualTo(0)
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

    @Test fun `el banner consulta UNA solicitud - la alcanza aunque 20 huerfanas viejas ocupen la pagina, y no toca a las demas`() = runTest {
        repeat(25) { enBandeja("vieja-$it", desfase = it.toLong()) }
        enBandeja("la-del-banner", desfase = 100)
        val api = io.mockk.mockk<com.jaac.avoqado_tpv.features.payment.data.ledger.TerminalAttemptApiService>()
        io.mockk.coEvery { api.getRequestStatus(venue, "la-del-banner") } returns retrofit2.Response.success(
            com.jaac.avoqado_tpv.features.payment.data.ledger.TerminalRequestStatusResponse(
                success = true, requestId = "la-del-banner", resuelta = true,
                request = com.google.gson.JsonParser.parseString("""{"status":"FAILED","failureCode":"OPERATOR_RECONCILED_NO_CHARGE"}""").asJsonObject,
            ),
        )

        val r = BandejaServerRecovery(dao, api).conciliar(venue, now + 1_000, soloLaSolicitud = "la-del-banner")

        assertThat(r.consultadas).isEqualTo(1)
        assertThat(r.conciliadas).isEqualTo(1)
        assertThat(dao.getById("la-del-banner")!!.status).isEqualTo(RemotePaymentRequestEntity.STATUS_RESOLVED)
        assertThat(dao.getById("vieja-0")!!.status).isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
        io.mockk.coVerify(exactly = 1) { api.getRequestStatus(any(), any()) }
    }

    @Test fun `consultar una sola solicitud no salta las guardas - con intento no se cierra`() = runTest {
        enBandeja("con-intento")
        db.paymentAttemptDao().insert(
            com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity(
                attemptId = "a-1", venueId = venue, processor = "ANGELPAY", state = "KERNEL_ACTIVO", amountCents = 5000, tipCents = 0,
                recordingRoute = "FAST", paymentContextJson = """{"terminalPaymentRequestId":"con-intento"}""",
                terminalPaymentRequestId = "con-intento", createdAt = now, updatedAt = now,
            ),
        )
        val api = io.mockk.mockk<com.jaac.avoqado_tpv.features.payment.data.ledger.TerminalAttemptApiService>()
        io.mockk.coEvery { api.getRequestStatus(venue, "con-intento") } returns retrofit2.Response.success(
            com.jaac.avoqado_tpv.features.payment.data.ledger.TerminalRequestStatusResponse(
                success = true, requestId = "con-intento", resuelta = true,
                request = com.google.gson.JsonParser.parseString("""{"status":"FAILED"}""").asJsonObject,
            ),
        )

        val r = BandejaServerRecovery(dao, api).conciliar(venue, now + 1_000, soloLaSolicitud = "con-intento")

        assertThat(r.conciliadas).isEqualTo(0)
        assertThat(dao.getById("con-intento")!!.status).isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
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

    // ═══ D8 (QA en aparato, 3-nov): la TPV publicada declaró «no se cobró» y la bandeja se quedó PROCESSING ═══
    //
    // El intento quedó DESCARTADA y `execution_closed_at` nulo, así que Caja la cuenta como operación del POS sin terminar, y nadie
    // preguntaba por esa solicitud: el recuperador sólo miraba filas sin intento. Medido 3 veces, también tras reiniciar y a los 57 min.
    // El intento DESCARTADA ya terminó para la libreta: no impide preguntar ni cerrar. Lo único que cierra sigue siendo `resuelta` del servidor.

    private fun apiQueContesta(respuesta: suspend () -> retrofit2.Response<com.jaac.avoqado_tpv.features.payment.data.ledger.TerminalRequestStatusResponse>) =
        io.mockk.mockk<com.jaac.avoqado_tpv.features.payment.data.ledger.TerminalAttemptApiService>().also { api ->
            io.mockk.coEvery { api.getRequestStatus(venue, any()) } coAnswers { respuesta() }
        }

    private fun solicitud(requestId: String, resuelta: Boolean?, status: String = "FAILED") = retrofit2.Response.success(
        com.jaac.avoqado_tpv.features.payment.data.ledger.TerminalRequestStatusResponse(
            success = true, requestId = requestId, resuelta = resuelta,
            request = com.google.gson.JsonParser.parseString("""{"status":"$status","failureCode":"OPERATOR_RECONCILED_NO_CHARGE"}""").asJsonObject,
        ),
    )

    @Test fun `D8 una fila PROCESSING con su intento DESCARTADA es candidata, y con resuelta se cierra`() = runTest {
        enBandeja("req-d8")
        intentoDe("req-d8", "a-d8", "DESCARTADA")
        assertThat(dao.getById("req-d8")!!.executionClosedAt).isNull()
        assertThat(dao.candidatasHuerfanas(venue).map { it.requestId }).containsExactly("req-d8")

        val r = BandejaServerRecovery(dao, apiQueContesta { solicitud("req-d8", resuelta = true) }).conciliar(venue, now + 1_000)

        assertThat(r.conciliadas).isEqualTo(1)
        val fila = dao.getById("req-d8")!!
        assertThat(fila.status).isEqualTo(RemotePaymentRequestEntity.STATUS_RESOLVED)
        assertThat(fila.finalEmittedAt).isEqualTo(now + 1_000) // la cerca: esa solicitud ya no admite otro intento
        assertThat(org.json.JSONObject(fila.finalResultJson!!).getString("status")).isEqualTo("failed")
        assertThat(dao.candidatasHuerfanas(venue)).isEmpty()
    }

    @Test fun `D8 esa fila no sale en el aviso ni se ofrece declararla - sólo se le pregunta al servidor`() = runTest {
        enBandeja("req-d8")
        intentoDe("req-d8", "a-d8", "DESCARTADA")

        assertThat(dao.observePendingObligationsCount(venue)).isEqualTo(0)
        assertThat(dao.observePendingObligations(venue).first()).isEmpty()
        val ledger = com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger(db.paymentAttemptDao(), io.mockk.mockk(relaxed = true))
        assertThat(ledger.observarCobrosDeclarables(venue).first()).isEmpty()
    }

    @Test fun `D8 con resuelta falso, ausente, 404 o sin red no se cierra y se estampa la consulta`() = runTest {
        enBandeja("req-d8")
        intentoDe("req-d8", "a-d8", "DESCARTADA")
        val casos = listOf<Pair<String, suspend () -> retrofit2.Response<com.jaac.avoqado_tpv.features.payment.data.ledger.TerminalRequestStatusResponse>>>(
            "resuelta falso" to { solicitud("req-d8", resuelta = false) },
            "resuelta ausente" to { solicitud("req-d8", resuelta = null) },
            "404" to { retrofit2.Response.error(404, "{}".toResponseBody(null)) },
            "503" to { retrofit2.Response.error(503, "{}".toResponseBody(null)) },
            "sin red" to { throw java.io.IOException("sin red") },
        )
        casos.forEachIndexed { i, (nombre, respuesta) ->
            val cuando = now + 1_000 + i
            val r = BandejaServerRecovery(dao, apiQueContesta(respuesta)).conciliar(venue, cuando)

            assertWithMessage(nombre).that(r.conciliadas).isEqualTo(0)
            val fila = dao.getById("req-d8")!!
            assertWithMessage(nombre).that(fila.status).isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
            assertWithMessage(nombre).that(fila.finalEmittedAt).isNull()
            assertWithMessage(nombre).that(fila.updatedAt).isEqualTo(cuando)
        }
    }

    @Test fun `D8 un estado en vuelo o COMPLETED tampoco la cierra, aunque diga resuelta`() = runTest {
        enBandeja("req-d8")
        intentoDe("req-d8", "a-d8", "DESCARTADA")
        for (status in listOf("SENT", "UNKNOWN", "COMPLETED")) {
            BandejaServerRecovery(dao, apiQueContesta { solicitud("req-d8", resuelta = true, status = status) }).conciliar(venue, now + 1_000)
            assertWithMessage(status).that(dao.getById("req-d8")!!.status).isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
        }
    }

    @Test fun `D8 con un intento en cualquier otro estado sigue sin ser candidata y sin poder cerrarse`() = runTest {
        val estados = listOf("INDETERMINADO", "AUTORIZANDO", "REGISTRADO", "CERRADA", "HOST_RESPONDIO", "AUTORIZADO", "REGISTRO_FALLIDO", "ENTREGADA_A_COLA")
        for (estado in estados) {
            val req = "req-$estado"
            enBandeja(req)
            intentoDe(req, "a-$estado", estado)
            assertWithMessage(estado).that(dao.candidatasHuerfanas(venue).map { it.requestId }).doesNotContain(req)
            assertWithMessage(estado).that(dao.conciliarHuerfanaConElServidor(req, venue, "FAILED", "OPERATOR_RECONCILED_NO_CHARGE", now)).isEqualTo(0)
            assertWithMessage(estado).that(dao.getById(req)!!.status).isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
        }
    }

    @Test fun `D8 un intento DESCARTADA con otro de la misma solicitud en duda no abre nada`() = runTest {
        enBandeja("req-d8")
        intentoDe("req-d8", "a-descartado", "DESCARTADA")
        intentoDe("req-d8", "a-en-duda", "INDETERMINADO")

        assertThat(dao.candidatasHuerfanas(venue)).isEmpty()
        assertThat(dao.conciliarHuerfanaConElServidor("req-d8", venue, "FAILED", "OPERATOR_RECONCILED_NO_CHARGE", now)).isEqualTo(0)
    }

    @Test fun `D8 un intento que entra en PREPARANDO o KERNEL_ACTIVO junto a uno DESCARTADA tampoco deja cerrar`() = runTest {
        for (estado in listOf("PREPARANDO", "KERNEL_ACTIVO")) {
            val req = "req-$estado"
            enBandeja(req)
            intentoDe(req, "a-descartado-$estado", "DESCARTADA")
            intentoDe(req, "a-vivo-$estado", estado)
            assertWithMessage(estado).that(dao.conciliarHuerfanaConElServidor(req, venue, "FAILED", "OPERATOR_RECONCILED_NO_CHARGE", now)).isEqualTo(0)
        }
    }

    @Test fun `D8 una fila con efectivo arrancado sigue sin poder cerrarse, aunque su intento este DESCARTADA`() = runTest {
        enBandeja("req-d8")
        intentoDe("req-d8", "a-d8", "DESCARTADA")
        assertThat(db.paymentAttemptDao().iniciarEjecucionNoTarjeta("req-d8", now)).isEqualTo(1)

        assertThat(dao.conciliarHuerfanaConElServidor("req-d8", venue, "FAILED", "OPERATOR_RECONCILED_NO_CHARGE", now)).isEqualTo(0)
    }
}

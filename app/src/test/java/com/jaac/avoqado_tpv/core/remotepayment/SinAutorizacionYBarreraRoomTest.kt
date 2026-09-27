package com.jaac.avoqado_tpv.core.remotepayment

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.features.payment.data.ledger.CercaDeSolicitud
import com.jaac.avoqado_tpv.features.payment.data.ledger.LedgerUnknownRecovery
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayChargeVerifier
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.VerificacionDelCobro
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Libreta y bandeja REALES (Room) para las dos piezas del 18-sep:
 *
 *  - **SDK 1.0.19 — «no se cobró», cierto** ([PaymentAttemptLedger.markSinAutorizacion]): `AUTORIZANDO → DESCARTADA`
 *    SIN tocar `host_approved`, y por eso la bandeja deriva `PRE_AUTHORIZATION` y nunca `PROCESSOR_DECLINED`.
 *    Diseño `diseno-nexgo-sdk-1.0.19.md` §2.3 punto 4; T15-T20 y T38.
 *  - **§3.8 — el rechazo MUDO de la barrera**: una solicitud del POS que no pudo reservar la terminal se cierra con
 *    `PRE_AUTHORIZATION`, y es la BANDEJA la que comprueba en su transacción que esa solicitud no inició nada.
 *    (T32 —«la bandeja NO escribe el negativo si un intento de la MISMA solicitud está incierto»— ya existe:
 *    `CancelTrasReclamarRoomTest` › «un rechazo viejo NUNCA certifica un intento incierto - no se escribe desenlace».)
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class SinAutorizacionYBarreraRoomTest {

    private lateinit var db: AvoqadoDatabase
    private lateinit var inbox: RemotePaymentInbox
    private lateinit var coordinator: RemotePaymentCoordinator
    private lateinit var ledger: PaymentAttemptLedger

    private val motivo = "sin_autorizacion:sdk=1.0.19;code=U101;status=TIMEOUT"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        inbox = RemotePaymentInbox(db.remotePaymentRequestDao())
        coordinator = RemotePaymentCoordinator(inbox)
        ledger = PaymentAttemptLedger(db.paymentAttemptDao(), mockk(relaxed = true))
    }

    @After fun tearDown() = db.close()

    private fun solicitud(requestId: String) = SocketEvent.TerminalPaymentRequest(
        requestId = requestId, amountCents = 1_000, tipCents = 0, rating = null, skipReview = true,
        orderId = null, processedByStaffId = "staff-1", senderDeviceName = "CPad", venueId = "venue-1",
        timestamp = "2026-09-18T05:00:00Z",
    )

    /** Reclama la solicitud como lo hace la navegación (con prueba de propiedad en este proceso). */
    private suspend fun reclamar(requestId: String) {
        inbox.receive(solicitud(requestId))
        assertThat(coordinator.prepareSocketPaymentRequest(requestId, { true }, { "venue-1" }))
            .isEqualTo(RemotePaymentAdmission.READY)
    }

    private fun contextoDe(requestId: String) = """{"terminalPaymentRequestId":"$requestId"}"""

    /** Un intento AngelPay de Pago rápido (sin orden) ya en AUTORIZANDO: el SDK está dentro. */
    private suspend fun enAutorizacion(attemptId: String, contexto: String = "{}", venueId: String = "venue-1"): String {
        assertThat(ledger.openAttempt(attemptId, venueId, "ANGELPAY", 1_000, 0, "FAST", contexto)).isTrue()
        assertThat(ledger.markAuthorizing(attemptId)).isTrue()
        return attemptId
    }

    // ═══════════════════════════════ T15-T17 · el CAS nuevo: sólo AUTORIZANDO, sin evidencia ═══════════════════════════════

    @Test fun `P1 T15 markSinAutorizacion desde AUTORIZANDO deja DESCARTADA, host_approved NULL y el motivo`() = runTest {
        enAutorizacion("a1")

        assertThat(ledger.markSinAutorizacion("a1", "venue-1", motivo)).isTrue()

        val fila = db.paymentAttemptDao().getById("a1")!!
        assertThat(fila.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
        assertWithMessage("host_approved = 0 haría que la bandeja mande PROCESSOR_DECLINED — «lo rechazó el banco»")
            .that(fila.hostApproved).isNull()
        assertThat(fila.lastError).isEqualTo(motivo)
        assertThat(fila.stateVersion).isEqualTo(2) // PREPARANDO → AUTORIZANDO → DESCARTADA
        // Idempotente: una segunda llamada no vuelve a ganar.
        assertThat(ledger.markSinAutorizacion("a1", "venue-1", motivo)).isFalse()
    }

    @Test fun `P1 T16 markSinAutorizacion no toca ningun otro estado ni una fila ajena`() = runTest {
        val dao = db.paymentAttemptDao()
        fun fila(id: String, estado: String, venue: String = "venue-1", processor: String = "ANGELPAY",
                 kind: String = PaymentAttemptEntity.KIND_SALE, heredada: Boolean = false, error: String? = null) =
            PaymentAttemptEntity(
                attemptId = id, venueId = venue, processor = processor, kind = kind, state = estado,
                amountCents = 1_000, tipCents = 0, recordingRoute = "FAST", paymentContextJson = "{}",
                lastError = error, createdAt = 1, updatedAt = 1, legacyShadow = heredada,
            )
        val filas = listOf(
            fila("preparando", "PREPARANDO"),
            fila("kernel", "KERNEL_ACTIVO"),
            fila("indeterminado", "INDETERMINADO", error = "AngelPay sin veredicto"),
            fila("cuarentena", "INDETERMINADO", error = PaymentAttemptEntity.CUARENTENA_POR_ANTIGUEDAD),
            fila("host", "HOST_RESPONDIO"),
            fila("autorizado", "AUTORIZADO"),
            fila("registro-fallido", "REGISTRO_FALLIDO"),
            fila("registrado", "REGISTRADO"),
            fila("descartada", "DESCARTADA"),
            fila("heredada", "AUTORIZANDO", heredada = true),
            fila("blumon", "AUTORIZANDO", processor = "BLUMON"),
            fila("devolucion", "AUTORIZANDO", kind = PaymentAttemptEntity.KIND_REFUND),
            fila("otro-venue", "AUTORIZANDO", venue = "venue-2"),
        )
        filas.forEach { dao.insert(it) }

        filas.forEach { original ->
            assertWithMessage(original.attemptId)
                .that(ledger.markSinAutorizacion(original.attemptId, "venue-1", motivo)).isFalse()
            val despues = dao.getById(original.attemptId)!!
            assertWithMessage("${original.attemptId} cambió de estado").that(despues.state).isEqualTo(original.state)
            assertWithMessage("${original.attemptId} cambió su motivo").that(despues.lastError).isEqualTo(original.lastError)
        }
    }

    @Test fun `P1 T17 markSinAutorizacion no gana con un veredicto del host ni con evidencia del servidor`() = runTest {
        val dao = db.paymentAttemptDao()
        val base = PaymentAttemptEntity(
            attemptId = "x", venueId = "venue-1", processor = "ANGELPAY", state = "AUTORIZANDO",
            amountCents = 1_000, tipCents = 0, recordingRoute = "FAST", paymentContextJson = "{}", createdAt = 1, updatedAt = 1,
        )
        val conEvidencia = listOf(
            base.copy(attemptId = "host-aprobo", hostApproved = true),
            base.copy(attemptId = "host-rechazo", hostApproved = false),
            base.copy(attemptId = "con-payment", serverPaymentId = "pay-1"),
            base.copy(attemptId = "con-veredicto", serverOutcome = PaymentAttemptEntity.SERVER_RECORDED),
            base.copy(attemptId = "con-aprobacion-bancaria", serverProcessorEvidence = PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED),
        )
        conEvidencia.forEach { dao.insert(it) }

        conEvidencia.forEach { fila ->
            assertWithMessage("con evidencia de dinero «no se cobró» sería mentira: ${fila.attemptId}")
                .that(ledger.markSinAutorizacion(fila.attemptId, "venue-1", motivo)).isFalse()
            assertThat(dao.getById(fila.attemptId)!!.state).isEqualTo("AUTORIZANDO")
        }
    }

    // ═══════════════════════ T18 · T19 · T38: la terminal NO queda apartada por un «no se cobró» ═══════════════════════

    @Test fun `P1 T18 T38 tras un no se cobro el siguiente Pago rapido reserva la terminal, y tras un INDETERMINADO tambien`() = runTest {
        // La regresión de las 23:13-23:15 del 17-sep: un E618 en Pago rápido apartó la terminal y el siguiente cobro se rechazó.
        enAutorizacion("e618")
        assertThat(ledger.markSinAutorizacion("e618", "venue-1", "sin_autorizacion:sdk=1.0.19;code=E618;status=ERROR")).isTrue()
        assertWithMessage("un «no se cobró» cierto NO puede apartar la terminal")
            .that(ledger.openAttempt("siguiente", "venue-1", "ANGELPAY", 500, 0, "FAST", "{}")).isTrue()
        assertThat(ledger.markAuthorizing("siguiente")).isTrue()

        // T19 · founder, 25-sep («ninguna duda apaga la terminal»): lo INCIERTO ya no aparta el aparato — se avisa y queda
        // guardado para la recuperación.
        ledger.markIndeterminate("siguiente", "AngelPay sin veredicto: status=TIMEOUT code=G505")
        assertWithMessage("un INDETERMINADO sin orden ya no aparta la terminal")
            .that(ledger.openAttempt("tercero", "venue-1", "ANGELPAY", 500, 0, "FAST", "{}")).isTrue()
        assertThat(db.paymentAttemptDao().getById("siguiente")!!.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
    }

    // ═══════════════════════ T20: la bandeja deriva PRE_AUTHORIZATION de la libreta, nunca PROCESSOR_DECLINED ═══════════════════════

    @Test fun `P1 T20 tras un no se cobro la bandeja escribe failed mas PRE_AUTHORIZATION y cierra la solicitud`() = runTest {
        reclamar("req-1")
        enAutorizacion("a1", contextoDe("req-1"))
        assertThat(ledger.markSinAutorizacion("a1", "venue-1", motivo)).isTrue()

        // Lo que emite el ViewModel: `failed` con la evidencia de la pantalla; la bandeja la re-deriva de la libreta.
        val escrito = inbox.persistResult("req-1", """{"requestId":"req-1","status":"failed","outcomeEvidence":"PRE_AUTHORIZATION","errorMessage":"Nadie acercó una tarjeta: no se cobró."}""")

        val json = JSONObject(escrito!!)
        assertThat(json.optString("status")).isEqualTo("failed")
        assertThat(json.optString("outcomeEvidence")).isEqualTo("PRE_AUTHORIZATION")
        val fila = db.remotePaymentRequestDao().getById("req-1")!!
        assertThat(fila.status).isEqualTo(RemotePaymentRequestEntity.STATUS_RESOLVED)
        assertThat(fila.finalEmittedAt).isNotNull()
        // H.3: desde el final escrito la terminal ya no ejecuta esa solicitud.
        assertThat(ledger.cercaDeSolicitud("req-1")).isEqualTo(CercaDeSolicitud.CERRADA)
        assertThat(ledger.openAttempt("a2", "venue-1", "ANGELPAY", 1_000, 0, "FAST", contextoDe("req-1"))).isFalse()
    }

    // ═══════ Codex r2 (P1-2 residual): reabrir la fila del cierre cuando no se puede comprobar que no hubo dinero ═══════

    @Test fun `P1 reabrirSinAutorizacion devuelve la fila del cierre a INDETERMINADO - vuelve a vetar el negativo sin apartar la terminal`() = runTest {
        reclamar("req-r")
        enAutorizacion("a1", contextoDe("req-r"))
        assertThat(ledger.markSinAutorizacion("a1", "venue-1", motivo)).isTrue()
        val incertidumbres = mutableListOf<String>()
        ledger.onUncertaintyBorn = { incertidumbres += it }

        assertThat(ledger.reabrirSinAutorizacion("a1", "venue-1", motivo, "AngelPay sin veredicto: relectura fallida", veto = null)).isTrue()
        // Nace una incertidumbre: la recuperación por servidor (N4) le pregunta al servidor por ESTE intento.
        assertThat(incertidumbres).containsExactly("a1")

        val fila = db.paymentAttemptDao().getById("a1")!!
        assertThat(fila.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
        assertThat(fila.hostApproved).isNull()
        assertThat(fila.lastError).isEqualTo("AngelPay sin veredicto: relectura fallida")
        // Codex H3: la reapertura por simple fallo de relectura sigue SIN veto (no se sabe de dinero; es una duda).
        assertThat(fila.serverVeto).isNull()
        // La obligación VIVE: veta el negativo de su solicitud y el cancel no entra. Founder, 25-sep: ya no aparta la terminal.
        assertThat(ledger.openAttempt("siguiente", "venue-1", "ANGELPAY", 500, 0, "FAST", "{}")).isTrue()
        assertThat(inbox.persistResult("req-r", """{"requestId":"req-r","status":"failed"}""")).isNull()
        assertThat(inbox.cancel("req-r").disposition).isEqualTo(RemotePaymentCancelDisposition.ACTIVE)
        // Idempotente: una segunda llamada ya no encuentra la DESCARTADA de ese cierre (ni avisa otra incertidumbre).
        assertThat(ledger.reabrirSinAutorizacion("a1", "venue-1", motivo, "otra", veto = null)).isFalse()
        assertThat(incertidumbres).containsExactly("a1")
    }

    @Test fun `P1 reabrirSinAutorizacion solo reabre la fila que escribio ESE cierre`() = runTest {
        val dao = db.paymentAttemptDao()
        fun fila(id: String, estado: String = "DESCARTADA", error: String? = motivo, venue: String = "venue-1",
                 host: Boolean? = null, processor: String = "ANGELPAY", heredada: Boolean = false,
                 kind: String = PaymentAttemptEntity.KIND_SALE) = PaymentAttemptEntity(
            attemptId = id, venueId = venue, processor = processor, state = estado, amountCents = 1_000, tipCents = 0,
            recordingRoute = "FAST", paymentContextJson = "{}", lastError = error, hostApproved = host,
            createdAt = 1, updatedAt = 1, legacyShadow = heredada, kind = kind,
        )
        val ajenas = listOf(
            fila("otro-motivo", error = "sin_autorizacion:sdk=1.0.19;code=E618;status=ERROR"),
            fila("rechazo-del-host", host = false),
            fila("otro-venue", venue = "venue-2"),
            fila("autorizando", estado = "AUTORIZANDO"),
            fila("heredada", heredada = true),
            fila("blumon", processor = "BLUMON"),
            fila("devolucion", kind = PaymentAttemptEntity.KIND_REFUND),
        )
        ajenas.forEach { dao.insert(it) }

        ajenas.forEach { original ->
            assertWithMessage(original.attemptId)
                .that(ledger.reabrirSinAutorizacion(original.attemptId, "venue-1", motivo, "reabierta", veto = null)).isFalse()
            assertWithMessage("${original.attemptId} cambió de estado")
                .that(dao.getById(original.attemptId)!!.state).isEqualTo(original.state)
        }
    }

    @Test fun `P1 H3 la reapertura por dinero de S5 sin escribir deja el VETO en el mismo UPDATE - tras reiniciar la venta sigue cercada y otra entra`() = runTest {
        // Codex H3 (26-sep): S5 acreditó dinero de este intento, sus escrituras fallaron y la reapertura SÍ pasó. Sin veto, la fila
        // reabierta era una duda más: muerto el proceso (se lleva el veto en memoria), `SQL_CERCA_LA_VENTA` dejaba pasar su venta.
        enAutorizacion("a1", """{"orderId":"orden-7","terminalPaymentRequestId":"req-h3"}""")
        assertThat(ledger.markSinAutorizacion("a1", "venue-1", motivo)).isTrue()

        assertThat(ledger.reabrirSinAutorizacion("a1", "venue-1", motivo, "S5 acreditó dinero que no quedó escrito",
            veto = PaymentAttemptEntity.VETO_SDK_CONTRADICTION)).isTrue()
        val fila = db.paymentAttemptDao().getById("a1")!!
        assertThat(fila.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
        assertThat(fila.serverVeto).isEqualTo(PaymentAttemptEntity.VETO_SDK_CONTRADICTION)

        // Reinicio simulado: otra libreta es otro proceso (otro `process_token`), sin la memoria de la pantalla.
        val reiniciada = PaymentAttemptLedger(db.paymentAttemptDao(), mockk(relaxed = true))
        assertWithMessage("el dinero conocido sigue cercando SU venta tras reiniciar")
            .that(reiniciada.retencionDeLaVenta("orden-7")?.attemptId).isEqualTo("a1")
        assertThat(reiniciada.openAttempt("b1", "venue-1", "ANGELPAY", 1_000, 0, "ORDER", """{"orderId":"orden-7"}""")).isFalse()
        assertWithMessage("las demás cuentas cobran")
            .that(reiniciada.openAttempt("c1", "venue-1", "ANGELPAY", 1_000, 0, "ORDER", """{"orderId":"orden-8"}""")).isTrue()
    }

    @Test fun `P1 I-1 la recuperacion por historial NO sube a HOST_RESPONDIO una fila con veto - el aparato sigue libre y su venta cercada`() = runTest {
        // Re-revisión de F (I-1, 26-sep): la reapertura de H3 deja INDETERMINADO + veto con el token de ESTE proceso. Si la
        // recuperación por historial de AngelPay «encuentra» el cobro y la sube a HOST_RESPONDIO, el veto le impide llegar a
        // REGISTRADO y se queda ahí: `SQL_EJECUCION_VIVA` cuenta HOST_RESPONDIO de este proceso y apartaba el aparato hasta reiniciar.
        fun contexto(id: String, orden: String) = """{"venueId":"venue-1","staffId":"staff","orderId":"$orden","idempotencyKey":"$id",""" +
            """"deviceSerialNumber":"terminal","processorAffiliation":"afiliacion","amount":10.00,"tip":0,"merchantAccountId":"merchant"}"""
        val dao = db.paymentAttemptDao()
        enAutorizacion("vetada", contexto("vetada", "orden-7"))
        assertThat(ledger.markSinAutorizacion("vetada", "venue-1", motivo)).isTrue()
        assertThat(ledger.reabrirSinAutorizacion("vetada", "venue-1", motivo, "S5 acreditó dinero que no quedó escrito",
            veto = PaymentAttemptEntity.VETO_SDK_CONTRADICTION)).isTrue()
        // Control: una duda SIN veto, de OTRO proceso (su HOST_RESPONDIO no aparta a éste), sube como siempre.
        val otroProceso = PaymentAttemptLedger(dao, mockk(relaxed = true))
        assertThat(otroProceso.openAttempt("control", "venue-1", "ANGELPAY", 1_000, 0, "FAST", contexto("control", "orden-9"))).isTrue()
        assertThat(otroProceso.markAuthorizing("control")).isTrue()
        otroProceso.markIndeterminate("control", "AngelPay sin veredicto")

        val verificador = mockk<AngelPayChargeVerifier> {
            coEvery { verificar(any(), any(), any(), any(), any(), any()) } returns VerificacionDelCobro.Cobrado("AUTH", "REF", null)
        }
        // Diez minutos después: las dos filas ya pasaron los 2 min que la recuperación deja correr al SDK.
        LedgerUnknownRecovery(dao, ledger, verificador).recover("venue-1", System.currentTimeMillis() + 600_000)

        val vetada = dao.getById("vetada")!!
        assertWithMessage("la fila con veto se queda en duda (Avoqado la concilia)")
            .that(vetada.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
        assertThat(vetada.serverVeto).isEqualTo(PaymentAttemptEntity.VETO_SDK_CONTRADICTION)
        assertThat(vetada.hostApproved).isNull()
        assertWithMessage("ni se toma ni se le pregunta al historial").that(vetada.verifyAttempts).isEqualTo(0)
        coVerify(exactly = 0) { verificador.verificar("vetada", any(), any(), any(), any(), any()) }
        assertWithMessage("control: la duda sin veto sube a HOST_RESPONDIO como antes")
            .that(dao.getById("control")!!.state).isEqualTo(PaymentAttemptEntity.STATE_HOST_RESPONDIO)

        assertWithMessage("el MISMO proceso cobra otra cuenta: el aparato está libre")
            .that(ledger.openAttempt("otra", "venue-1", "ANGELPAY", 1_000, 0, "FAST", contexto("otra", "orden-8"))).isTrue()
        ledger.markDiscardedBeforeCharge("otra", "fin de la prueba")   // que su reserva no tape la cerca de abajo
        assertThat(ledger.retencionDeLaVenta("orden-7")?.attemptId).isEqualTo("vetada")
        assertWithMessage("la cuenta con dinero conocido sigue cercada")
            .that(ledger.openAttempt("repetida", "venue-1", "ANGELPAY", 1_000, 0, "FAST", contexto("repetida", "orden-7"))).isFalse()

        // El candado va DENTRO del UPDATE: un veto que llega entre tomar la fila y escribir tampoco la deja subir.
        val t = System.currentTimeMillis() + 900_000
        assertThat(dao.claimUnknownRecovery("vetada", "venue-1", t, t + 300_000)).isEqualTo(1)
        assertThat(dao.completeUnknownRecovery("vetada", "venue-1", t + 300_000, t + 1, "REF", "AUTH")).isEqualTo(0)
        assertThat(dao.getById("vetada")!!.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
    }

    // ═══════════════════════════ §3.8: el rechazo de la barrera se cierra con PRE_AUTHORIZATION ═══════════════════════════

    @Test fun `P1 T31 bandeja - una solicitud del POS que no pudo reservar la terminal se cierra con PRE_AUTHORIZATION y el cobro que la aparta no se toca`() = runTest {
        // Lo de las 21:45 del 17-sep fue un Pago rápido incierto. Founder, 25-sep («ninguna duda apaga la terminal»): una duda
        // ya no aparta el APARATO — lo único que llega a esta barrera es un cobro que ESTE proceso tiene corriendo.
        enAutorizacion("incierta")
        reclamar("req-2")

        assertWithMessage("la barrera rechaza el cobro del POS: la reserva no entra")
            .that(ledger.openAttempt("nuevo", "venue-1", "ANGELPAY", 1_000, 0, "FAST", contextoDe("req-2"))).isFalse()
        assertThat(ledger.cercaDeSolicitud("req-2")).isEqualTo(CercaDeSolicitud.LIBRE)
        // Lo que la pantalla NOMBRA: la fila que aparta el aparato.
        assertThat(ledger.retencionDelAparato()?.attemptId).isEqualTo("incierta")

        val escrito = inbox.persistResult("req-2", """{"requestId":"req-2","status":"failed","outcomeEvidence":"PRE_AUTHORIZATION"}""")

        val json = JSONObject(escrito!!)
        assertThat(json.optString("status")).isEqualTo("failed")
        assertThat(json.optString("outcomeEvidence")).isEqualTo("PRE_AUTHORIZATION")
        assertThat(db.remotePaymentRequestDao().getById("req-2")!!.status).isEqualTo(RemotePaymentRequestEntity.STATUS_RESOLVED)
        assertWithMessage("el cobro en curso que aparta la terminal NO se toca")
            .that(db.paymentAttemptDao().getById("incierta")!!.state).isEqualTo(PaymentAttemptEntity.STATE_AUTORIZANDO)
    }

    @Test fun `P1 T31 bandeja - si el intento de ESTA solicitud quedo en PREPARANDO, se descarta y sale PRE_AUTHORIZATION`() = runTest {
        reclamar("req-3")
        assertThat(ledger.openAttempt("propio", "venue-1", "ANGELPAY", 1_000, 0, "FAST",
            """{"terminalPaymentRequestId":"req-3","orderId":"venta-3"}""")).isTrue()
        // Entre la reserva y la barrera de autorización llegó el dinero de OTRO intento de la MISMA venta: `markAuthorizing`
        // pierde. (Hasta el 25-sep bastaba una duda sin venta de otro proceso; desde «ninguna duda apaga la terminal» ya no.)
        db.paymentAttemptDao().insert(
            PaymentAttemptEntity(
                attemptId = "ajena", venueId = "venue-1", processor = "ANGELPAY", state = "DESCARTADA",
                amountCents = 700, tipCents = 0, recordingRoute = "ORDER", paymentContextJson = """{"orderId":"venta-3"}""",
                lastError = "liberada_por_el_servidor:NO_EVIDENCE_AFTER_WINDOW", createdAt = 1, updatedAt = 1,
                serverProcessorEvidence = PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED, serverProcessorEvidenceAt = 1,
            ),
        )
        assertThat(ledger.markAuthorizing("propio")).isFalse()
        assertThat(db.paymentAttemptDao().getById("propio")!!.state).isEqualTo(PaymentAttemptEntity.STATE_PREPARANDO)

        val escrito = inbox.persistResult("req-3", """{"requestId":"req-3","status":"failed"}""")

        assertThat(JSONObject(escrito!!).optString("outcomeEvidence")).isEqualTo("PRE_AUTHORIZATION")
        assertThat(db.paymentAttemptDao().getById("propio")!!.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
        assertThat(ledger.markAuthorizing("propio")).isFalse()
    }

    // ═══════ Codex r8 (P1-1): el VETO durable que dejó el WORKER manda también sobre el «no se cobró» del SDK ═══════

    @Test fun `r8 P1-1 con el veto que dejo el worker, markSinAutorizacion NO cierra y el Pago rapido sigue apartando`() = runTest {
        enAutorizacion("a1")
        // El worker consultó S6 con el SDK dentro y guardó la contradicción. La pantalla nunca vio esa consulta.
        assertThat(db.paymentAttemptDao().marcarVetoDelServidor("a1", "venue-1", PaymentAttemptEntity.VETO_PAYMENT_CONTRADICTION, 5)).isEqualTo(1)

        assertWithMessage("con el servidor contradiciendo, «no se cobró» sería mentira")
            .that(ledger.markSinAutorizacion("a1", "venue-1", motivo)).isFalse()
        assertThat(db.paymentAttemptDao().getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_AUTORIZANDO)
        assertWithMessage("un Pago rápido incierto sin venta sigue apartando el aparato")
            .that(ledger.openAttempt("siguiente", "venue-1", "ANGELPAY", 500, 0, "FAST", "{}")).isFalse()
    }

    @Test fun `r8 P1-1 una DESCARTADA cuyo veto llego DESPUES del cierre bloquea el negativo y el cancel de su solicitud`() = runTest {
        reclamar("req-v")
        enAutorizacion("a1", contextoDe("req-v"))
        assertThat(ledger.markSinAutorizacion("a1", "venue-1", motivo)).isTrue()
        // El veto aterriza entre el cierre de la libreta y el final de la bandeja.
        assertThat(db.paymentAttemptDao().marcarVetoDelServidor("a1", "venue-1", PaymentAttemptEntity.VETO_PAYMENT_CONTRADICTION, 5)).isEqualTo(1)

        assertThat(db.remotePaymentRequestDao().contarIntentosBloqueadores("req-v")).isEqualTo(1)
        assertWithMessage("la bandeja no puede fabricar el negativo con una contradicción conocida")
            .that(inbox.persistResult("req-v", """{"requestId":"req-v","status":"failed","outcomeEvidence":"PRE_AUTHORIZATION"}""")).isNull()
        assertThat(inbox.cancel("req-v").disposition).isEqualTo(RemotePaymentCancelDisposition.ACTIVE)
        assertThat(db.remotePaymentRequestDao().getById("req-v")!!.status).isEqualTo(RemotePaymentRequestEntity.STATUS_PROCESSING)
    }

    // ═══════ Codex r9 (P1-1): el rechazo NORMAL del banco tampoco tapa el veto ni la aprobación que el servidor acreditó ═══════

    @Test fun `r9 P1-1 un rechazo NORMAL del banco NO cierra un intento con el veto del worker, y el siguiente cobro sigue apartado`() = runTest {
        enAutorizacion("a1")
        assertThat(db.paymentAttemptDao().marcarVetoDelServidor("a1", "venue-1", PaymentAttemptEntity.VETO_PAYMENT_CONTRADICTION, 5)).isEqualTo(1)

        // La reproducción de Codex con el SQL real: cerrar A = 1; reservar B = 1. Ninguna de las dos puede pasar.
        assertWithMessage("un DESCARTADA aquí es «Reintentar» sobre una contradicción conocida")
            .that(ledger.markHostResponded("a1", approved = false, operationId = null, referenceNumber = "R1", authCode = null)).isFalse()
        assertThat(db.paymentAttemptDao().getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_AUTORIZANDO)
        assertThat(ledger.openAttempt("siguiente", "venue-1", "ANGELPAY", 500, 0, "FAST", "{}")).isFalse()
    }

    @Test fun `r9 P1-1 con la aprobacion bancaria que el servidor acredito, el rechazo SE anota y el dinero se avisa`() = runTest {
        // Distinto del veto a propósito: el rechazo del host —un dato verdadero— se anota como siempre, y la marca APPROVED
        // queda: la fila es contradicción (se avisa) y cercaría SU venta. Founder, 25-sep: un Pago rápido sin venta con dinero
        // conocido ya no aparta el aparato — el siguiente cobro entra.
        enAutorizacion("a1")
        assertThat(db.paymentAttemptDao().marcarEvidenciaPositivaDelServidor("a1", "venue-1", 5)).isEqualTo(1)

        assertThat(ledger.markHostResponded("a1", approved = false, operationId = null, referenceNumber = "R1", authCode = null)).isTrue()
        assertThat(db.paymentAttemptDao().getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
        assertThat(db.paymentAttemptDao().esContradiccion("a1")).isTrue()
        assertThat(ledger.openAttempt("siguiente", "venue-1", "ANGELPAY", 500, 0, "FAST", "{}")).isTrue()
    }

    @Test fun `r10 - tambien un intento BLUMON con veto - el rechazo no lo cierra y el siguiente cobro sigue apartado`() = runTest {
        // Codex r10 (nota): la recuperación inmediata (`LedgerRecoveryTrigger → recoverOne`) no filtra procesador, así que una fila
        // de la PAX SÍ puede recibir el veto. El CAS no es de AngelPay: la misma guarda la protege.
        assertThat(ledger.openAttempt("b1", "venue-1", "BLUMON", 1_000, 0, "FAST", "{}")).isTrue()
        assertThat(ledger.markAuthorizing("b1")).isTrue()
        assertThat(db.paymentAttemptDao().marcarVetoDelServidor("b1", "venue-1", PaymentAttemptEntity.VETO_PAYMENT_CONTRADICTION, 5)).isEqualTo(1)

        assertThat(ledger.markHostResponded("b1", approved = false, operationId = null, referenceNumber = "R1", authCode = null)).isFalse()
        assertThat(db.paymentAttemptDao().getById("b1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_AUTORIZANDO)
        assertThat(ledger.openAttempt("siguiente", "venue-1", "BLUMON", 500, 0, "FAST", "{}")).isFalse()
    }

    @Test fun `r9 P1-1 control - un rechazo sin veto cierra como siempre, y una APROBACION aterriza aunque haya veto`() = runTest {
        enAutorizacion("a2")
        assertThat(ledger.markHostResponded("a2", false, null, "R2", null)).isTrue()
        assertThat(db.paymentAttemptDao().getById("a2")!!.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)

        enAutorizacion("a1")
        db.paymentAttemptDao().marcarVetoDelServidor("a1", "venue-1", PaymentAttemptEntity.VETO_PAYMENT_CONTRADICTION, 5)
        assertWithMessage("el dinero manda").that(ledger.markHostResponded("a1", true, null, "R1", "600287")).isTrue()
        assertThat(db.paymentAttemptDao().getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_HOST_RESPONDIO)
    }

    @Test fun `r8 P1-1 control - la MISMA DESCARTADA sin veto sigue sin bloquear el negativo`() = runTest {
        reclamar("req-c")
        enAutorizacion("a1", contextoDe("req-c"))
        assertThat(ledger.markSinAutorizacion("a1", "venue-1", motivo)).isTrue()

        assertThat(db.remotePaymentRequestDao().contarIntentosBloqueadores("req-c")).isEqualTo(0)
        assertThat(inbox.persistResult("req-c", """{"requestId":"req-c","status":"failed","outcomeEvidence":"PRE_AUTHORIZATION"}""")).isNotNull()
    }
}

package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentInbox
import com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentProbeDisposition
import com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentReceiveDecision
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 🔴 F1 bloque 2 (R1, `plan-minimo-v2-tpv.md` §2): en la PAX una persona sólo firma «no se cobró» cuando la terminal ya terminó con ESE
 * cobro y la libreta no tiene aprobado propio. Tres puertas, sobre los DAO y la libreta REALES: la firma local (TPV SOLA, «Por revisar»),
 * el certificado `declarable` de la sonda (la firma del POS, que el SERVIDOR exige a una PAX v2) y el aprobado tardío sobre una
 * declarada, que ya no se reabre.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class CeseDeLaPaxRoomTest {
    private val venue = "venue-1"
    private val marcaPax = MarcaDelBuild(procesador = "BLUMON", sdkAngelPayCompilado = false, respaldoAppAAppCompilado = false)
    private val unaSolaPantalla = PilaDePantallas { listOf(TareaDeLaApp(pantallas = 1, arribaLaPrincipal = true)) }
    private lateinit var db: AvoqadoDatabase

    @Before fun abrir() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun cerrar() = db.close()

    /** Un proceso de la app: su propio token. La base es la misma (un reinicio no la borra). */
    private fun libreta(dao: PaymentAttemptDao = db.paymentAttemptDao()) =
        PaymentAttemptLedger(dao, mockk(relaxed = true), unaSolaPantalla).apply { marcaDelBuild = marcaPax }

    private fun bandeja(l: PaymentAttemptLedger) = RemotePaymentInbox(
        db.remotePaymentRequestDao(), com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture(), l,
        com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder(),
    )

    private fun sql(s: String) = db.openHelper.writableDatabase.execSQL(s)

    private fun contexto(orden: String, solicitud: String?) =
        """{"venueId":"$venue","orderId":"$orden"""" + (solicitud?.let { ""","terminalPaymentRequestId":"$it"""" } ?: "") + "}"

    /** Abre el cobro de la PAX en ESTE proceso y lo deja autorizando (el SDK ya entró). */
    private suspend fun cobroEnCurso(l: PaymentAttemptLedger, id: String, orden: String = "orden-$id", solicitud: String? = null) {
        if (solicitud != null) {
            val b = bandeja(l)
            val solicitudDelPos = SocketEvent.TerminalPaymentRequest(
                requestId = solicitud, amountCents = 10000, tipCents = 0, rating = null, skipReview = true, orderId = orden,
                processedByStaffId = "staff-1", senderDeviceName = "POS", venueId = venue, timestamp = "2026-11-18T12:00:00Z",
            )
            assertThat(b.receive(solicitudDelPos)).isInstanceOf(RemotePaymentReceiveDecision.Deliver::class.java)
            assertThat(com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentCoordinator(b)
                .prepareSocketPaymentRequest(solicitud, { true }, { venue }))
                .isEqualTo(com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentAdmission.READY)
        }
        assertThat(l.openAttempt(id, venue, "BLUMON", 10_000, 0, "ORDER", contexto(orden, solicitud))).isTrue()
        assertThat(l.markAuthorizing(id)).isTrue()
    }

    /** La cuarentena por reloj del barrido: la misma ejecución puede seguir viva. Ya contestó Avoqado (se puede consultar). */
    private fun cuarentenaPorReloj(id: String) = sql(
        "UPDATE payment_attempts SET state = 'INDETERMINADO', last_error = 'cuarentena_por_antiguedad', server_answered_at = 1 " +
            "WHERE attempt_id = '$id'",
    )

    /** El catch de la pantalla la dejó en duda sin que el SDK regresara (otra forma de «misma ejecución viva»). */
    private suspend fun dudaSinRegreso(l: PaymentAttemptLedger, id: String) {
        l.markIndeterminate(id, "excepcion en la pantalla")
        sql("UPDATE payment_attempts SET server_answered_at = 1 WHERE attempt_id = '$id'")
    }

    private suspend fun firmaLocal(l: PaymentAttemptLedger, id: String): Boolean {
        val fila = db.paymentAttemptDao().getById(id)!!
        return l.puedeDeclararseEnDuda(fila, venue) && l.declararSinCobroLocal(id, venue, "gerente-1", enAvoqado = true)
    }

    private suspend fun enLaLista(l: PaymentAttemptLedger, id: String): CobroDeclarable =
        l.observarCobrosDeclarables(venue).first().single { it.attemptId == id }

    private fun declaracion(l: PaymentAttemptLedger) = DeclaracionDesdeElAviso(
        l, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
    )

    // ═══════════════════════════════════════════ Pruebas con nombre ═══════════════════════════════════════════

    @Test fun `PAX_misma_ejecucion_viva_en_cuarentena_no_se_firma_ni_local_ni_del_POS`() = runBlocking<Unit> {
        val l = libreta()
        // TPV SOLA, en el catch de la pantalla y en cuarentena por reloj: el SDK de ESTE intento puede regresar. (Un cobro vivo aparta
        // el lector de este proceso: la cuarentena por reloj va al final, como pasa en la terminal.)
        cobroEnCurso(l, "catch"); dudaSinRegreso(l, "catch")
        cobroEnCurso(l, "pos", solicitud = "req-1"); dudaSinRegreso(l, "pos")
        cobroEnCurso(l, "reloj"); cuarentenaPorReloj("reloj")
        for (id in listOf("reloj", "catch")) {
            val fila = enLaLista(l, id)
            assertWithMessage("$id: se ve en «Por revisar»").that(fila.declarable).isFalse()
            assertWithMessage("$id: sin [Firmar]").that(fila.ejecucionViva).isTrue()
            assertThat(declaracion(l).resultadoDe(venue, fila).texto())
                .isEqualTo("La terminal sigue trabajando en este cobro. Si no avanza, apaga y prende la terminal.")
            assertWithMessage("$id: la firma local no procede").that(firmaLocal(l, id)).isFalse()
            assertThat(db.paymentAttemptDao().getById(id)!!.state).isEqualTo("INDETERMINADO")
        }
        // POS → TPV: la sonda no certifica nada mientras puede seguir viva.
        val sonda = bandeja(l).probe("req-1", venue)
        assertThat(sonda.disposition).isEqualTo(RemotePaymentProbeDisposition.ACTIVE)
        assertWithMessage("la ejecución puede seguir viva").that(sonda.executionAlive).isTrue()
        assertWithMessage("sin certificado: la firma del POS no procede").that(sonda.declarable).isNull()

        // Regreso REAL del SDK: ahora sí, las dos.
        for (id in listOf("catch", "pos")) l.marcarSdkRegreso(id)
        assertWithMessage("tras el regreso del SDK, la firma local procede").that(firmaLocal(l, "catch")).isTrue()
        val trasRegreso = bandeja(l).probe("req-1", venue)
        assertThat(trasRegreso.executionAlive).isFalse()
        assertThat(trasRegreso.closedForExecution).isTrue()
        assertWithMessage("la PAX certifica que se puede declarar").that(trasRegreso.declarable).isTrue()
    }

    @Test fun `PAX_tras_reiniciar_se_firma_y_D5_escrito_sigue_su_camino`() = runBlocking<Unit> {
        val viejo = libreta()
        cobroEnCurso(viejo, "a1", solicitud = "req-1")
        cobroEnCurso(libreta(), "local")   // otro proceso que también murió con su cobro dentro
        // La app muere con el SDK dentro. Proceso nuevo: lo que dejó el muerto pasa a «en duda» al instante.
        val nuevo = libreta()
        nuevo.cuarentenaDeHuerfanos()
        sql("UPDATE payment_attempts SET server_answered_at = 1")
        assertWithMessage("tras reiniciar, la firma local procede sin esperar").that(firmaLocal(nuevo, "local")).isTrue()
        assertThat(bandeja(nuevo).probe("req-1", venue).declarable).isTrue()

        // D5: el banco aprobó y el chip rechazó el cierre; la duda quedó escrita y el SDK regresó. Sigue su camino: la firma el gerente.
        val l = libreta()
        cobroEnCurso(l, "d5")
        assertThat(l.markHostResponded("d5", true, "OP-1", "REF", "AUTH")).isTrue()
        l.markChipRechazoTrasAprobacion("d5")
        l.marcarSdkRegreso("d5")
        sql("UPDATE payment_attempts SET server_answered_at = 1 WHERE attempt_id = 'd5'")
        assertThat(db.paymentAttemptDao().getById("d5")!!.lastError).isEqualTo(PaymentAttemptEntity.MOTIVO_CHIP_RECHAZO_TRAS_APROBACION)
        assertWithMessage("D5 escrito se firma por su camino").that(firmaLocal(l, "d5")).isTrue()
    }

    @Test fun `PAX_con_aprobado_en_memoria_o_guardado_declarable_false`() = runBlocking<Unit> {
        // Guardado: el banco aprobó y el SDK regresó; falta registrar. Terminó con el lector, pero NO se declara.
        val l = libreta()
        cobroEnCurso(l, "guardado", solicitud = "req-1")
        assertThat(l.markHostResponded("guardado", true, "OP-1", "REF", "AUTH")).isTrue()
        l.marcarSdkRegreso("guardado")
        val sonda = bandeja(l).probe("req-1", venue)
        assertThat(sonda.executionAlive).isFalse()
        assertWithMessage("aprobado propio: terminó, pero no se puede declarar").that(sonda.declarable).isFalse()

        // En memoria: la aprobación llegó y Room no la pudo escribir; la duda ya estaba y el SDK regresó.
        val dao = spyk(db.paymentAttemptDao())
        coEvery { dao.casHostResponded(any(), any(), any(), any(), any(), any(), any(), any()) } throws java.io.IOException("disco")
        val m = libreta(dao)
        cobroEnCurso(m, "memoria", solicitud = "req-2")
        dudaSinRegreso(m, "memoria")
        assertThat(m.markHostResponded("memoria", true, "OP-2", "REF2", "AUTH2")).isFalse()
        assertThat(m.aprobacionPendienteDeGuardar("memoria")).isTrue()
        m.marcarSdkRegreso("memoria")
        assertWithMessage("aprobado sólo en memoria: no se certifica").that(bandeja(m).probe("req-2", venue).declarable).isFalse()
        cobroEnCurso(m, "local-memoria")
        dudaSinRegreso(m, "local-memoria")
        assertThat(m.markHostResponded("local-memoria", true, "OP-3", "REF3", "AUTH3")).isFalse()
        m.marcarSdkRegreso("local-memoria")
        assertWithMessage("ni la firma local").that(firmaLocal(m, "local-memoria")).isFalse()
    }

    @Test fun `PAX_E2_sobre_una_declarada_no_reabre_ni_registra`() = runBlocking<Unit> {
        // Una firma vieja (sin las puertas de F1) y DESPUÉS la aprobación del banco en este proceso: ya no se reabre.
        val l = libreta()
        cobroEnCurso(l, "a1")
        sql("UPDATE payment_attempts SET state = 'DESCARTADA', last_error = 'declarado_sin_cobro:gerente-1:avoqado' WHERE attempt_id = 'a1'")
        assertThat(l.markHostResponded("a1", true, "OP-1", "REF", "AUTH")).isFalse()
        val fila = db.paymentAttemptDao().getById("a1")!!
        assertThat(fila.state).isEqualTo("DESCARTADA")
        assertThat(fila.hostApproved).isNull()
        assertWithMessage("el registrador no la toma").that(
            db.paymentAttemptDao().getApprovalRecoveryCandidates(venue, Long.MAX_VALUE, System.currentTimeMillis() + 3_600_000),
        ).isEmpty()
        // Lo que acredite después el SERVIDOR (E3 APPROVED) marca y protege la cuenta, sin registrar.
        assertThat(l.marcarEvidenciaPositivaDelServidor(venue, "a1").getOrThrow()).isTrue()
        assertThat(l.retencionDeLaVenta("orden-a1")?.attemptId).isEqualTo("a1")
    }

    // ═══════════════════════════════════════════════ Modelo (lado PAX) ═══════════════════════════════════════════════

    /** vivo/cesó × con/sin aprobado × firma local/POS: se firma sólo si la ejecución cesó y no hay aprobado propio. */
    @Test fun `modelo PAX - vivo o ceso, con o sin aprobado, firma local o del POS`() = runBlocking<Unit> {
        var casos = 0
        for (vivo in listOf(true, false)) for (aprobado in listOf(true, false)) for (delPos in listOf(true, false)) {
            sql("DELETE FROM payment_attempts"); sql("DELETE FROM remote_payment_requests")
            val caso = "vivo=$vivo aprobado=$aprobado pos=$delPos"
            val l = libreta()
            cobroEnCurso(l, "a1", solicitud = if (delPos) "req-1" else null)
            if (aprobado) {
                // El aprobado del banco llega a la fila, y luego la ejecución queda en duda (registro que falló, cuarentena).
                l.markHostResponded("a1", true, "OP-1", "REF", "AUTH")
                sql("UPDATE payment_attempts SET state = 'INDETERMINADO', server_answered_at = 1 WHERE attempt_id = 'a1'")
            } else dudaSinRegreso(l, "a1")
            if (!vivo) l.marcarSdkRegreso("a1")
            val esperado = !vivo && !aprobado
            val firmo = if (delPos) bandeja(l).probe("req-1", venue).declarable == true else firmaLocal(l, "a1")
            assertWithMessage(caso).that(firmo).isEqualTo(esperado)
            if (!esperado) assertWithMessage("$caso: la fila sigue por revisar").that(db.paymentAttemptDao().getById("a1")!!.state)
                .isEqualTo("INDETERMINADO")
            casos++
        }
        assertThat(casos).isEqualTo(8)
    }
}

package com.jaac.avoqado_tpv.core.remotepayment

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent
import com.jaac.avoqado_tpv.features.payment.data.ledger.MarcaDelBuild
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger
import com.jaac.avoqado_tpv.features.payment.data.ledger.PilaDePantallas
import com.jaac.avoqado_tpv.features.payment.data.ledger.TOPE_LECTOR_EN_USO_MS
import com.jaac.avoqado_tpv.features.payment.data.ledger.TareaDeLaApp
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder
import com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D26 (22-nov, Testarudo: la PAX contestó «viva» 25 min y la Nexgo 8 min, hasta que las reiniciaron). Founder: «si dice rechazado,
 * ¿por qué tendríamos que congelar el pago?». Desde septiembre se cerraron, una por una, cinco puertas a ese estado, y el estado por
 * omisión seguía siendo «sigo cobrando»: cualquier camino no previsto caía ahí.
 *
 * EL INVARIANTE: la terminal sólo contesta «sigo cobrando» mientras un intento de ESTE proceso tiene el lector o el SDK en uso (un
 * hecho del proceso, `PaymentAttemptLedger.hayLectorEnUso*`, con tope), o mientras la pantalla dueña sigue abierta con algo que
 * reintentar (el rechazo con «Reintentar», founder 22-nov). Ninguna NOTA de la libreta aparta la terminal: la sonda y el cancel no
 * contestan «viva» y una venta nueva entra. Las notas siguen cercando SU venta y alimentando al barrido.
 *
 * La matriz recorre TODOS los estados de la libreta (por reflexión: un estado nuevo queda cubierto solo) × con/sin propiedad ×
 * cerrada/abierta para ejecución, con filas de ESTE proceso sin el fin del SDK (el peor caso). Nació del laboratorio
 * (`docs/audit/2026-11-22-terminal-sigue-cobrando/laboratorio/jvm/`), con Room real y la bandeja cableada como en Hilt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class D26PantallaQueSeFueRoomTest {
    private lateinit var db: AvoqadoDatabase
    private val marcaPax = MarcaDelBuild(procesador = "BLUMON", sdkAngelPayCompilado = false, respaldoAppAAppCompilado = false)
    private val unaSolaPantalla = PilaDePantallas { listOf(TareaDeLaApp(pantallas = 1, arribaLaPrincipal = true)) }
    private var reloj = 1_000_000L

    /** Un «proceso» de la app: su libreta (con su process_token y su lector en uso), su bandeja cableada como Hilt y su coordinador. */
    private inner class Proceso(pila: PilaDePantallas = unaSolaPantalla) {
        val banderas = PaymentStateHolder()
        val libreta = PaymentAttemptLedger(db.paymentAttemptDao(), mockk(relaxed = true), pila).apply {
            marcaDelBuild = marcaPax
            relojMonotonico = { reloj }
        }
        val bandeja = RemotePaymentInbox(db.remotePaymentRequestDao(), legacyShiftBoundaryFixture(), libreta, banderas)
        val coordinador = RemotePaymentCoordinator(bandeja)
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    private fun solicitud(id: String) = SocketEvent.TerminalPaymentRequest(
        requestId = id, amountCents = 21500, tipCents = 0, rating = 5, skipReview = true, orderId = null,
        processedByStaffId = "staff-1", senderDeviceName = "POS", venueId = "venue-1", timestamp = "2026-11-22T15:25:11Z",
    )

    private fun contexto(requestId: String?, orden: String) =
        if (requestId == null) """{"orderId":"$orden"}""" else """{"terminalPaymentRequestId":"$requestId","orderId":"$orden"}"""

    private suspend fun Proceso.llegaYSeReclama(id: String) {
        bandeja.receive(solicitud(id))
        assertThat(coordinador.prepareSocketPaymentRequest(id, { true }, { "venue-1" })).isEqualTo(RemotePaymentAdmission.READY)
    }

    private fun estadosDeLaLibreta(): List<String> = PaymentAttemptEntity::class.java.declaredFields
        .filter { it.name.startsWith("STATE_") && it.type == String::class.java }
        .map { it.isAccessible = true; it.get(null) as String }
        .sorted()

    private fun viva(disposicion: Any, ejecucionViva: Boolean) =
        (disposicion == RemotePaymentProbeDisposition.ACTIVE || disposicion == RemotePaymentCancelDisposition.ACTIVE) && ejecucionViva

    /**
     * EL INVARIANTE. Ninguna llamada al SDK en vuelo (las filas se insertan a mano: ningún intento tiene el lector) ⇒ la sonda y el
     * cancel NUNCA contestan «viva» y una venta nueva (otra cuenta) entra y llega al lector. Única excepción, a propósito: la
     * pantalla dueña abierta (propiedad), la solicitud sin cerrar y nada bloqueador (cero intentos, la reserva o un rechazo): ahí
     * el cajero puede volver a pasar la tarjeta, y el cancel del POS se ACEPTA.
     */
    @Test fun `D26 invariante - sin el lector en uso ninguna nota de la libreta deja la terminal viva y una venta nueva entra`() = runTest {
        val p = Proceso()
        val estados = estadosDeLaLibreta()
        assertWithMessage("la reflexión encontró los estados").that(estados).containsAtLeast(
            PaymentAttemptEntity.STATE_KERNEL_ACTIVO, PaymentAttemptEntity.STATE_AUTORIZANDO, PaymentAttemptEntity.STATE_INDETERMINADO,
            PaymentAttemptEntity.STATE_HOST_RESPONDIO, PaymentAttemptEntity.STATE_PREPARANDO, PaymentAttemptEntity.STATE_DESCARTADA,
        )
        var n = 0
        for (estado in estados) for (conPropiedad in listOf(true, false)) for (cerrada in listOf(false, true)) {
            n++
            val req = "req-$n"
            val caso = "estado=$estado propiedad=$conPropiedad cerrada=$cerrada"
            p.llegaYSeReclama(req)
            if (!conPropiedad) p.coordinador.soltarPropiedad(req)
            db.paymentAttemptDao().insert(PaymentAttemptEntity(
                attemptId = "a-$n", venueId = "venue-1", processor = "BLUMON", state = estado, amountCents = 21500, tipCents = 0,
                recordingRoute = "ORDER", paymentContextJson = contexto(req, "orden-$n"), createdAt = 1L, updatedAt = 1L,
                terminalPaymentRequestId = req, processToken = p.libreta.tokenDelProceso(),
            ))
            if (cerrada) assertWithMessage(caso).that(db.paymentAttemptDao().bandejaCerrarParaEjecucion(req, 2L)).isEqualTo(1)
            val puedeReintentar = conPropiedad && !cerrada && db.remotePaymentRequestDao().contarIntentosBloqueadores(req) == 0

            val sonda = p.coordinador.probeSocketPaymentRequest(req, "venue-1")
            val cancel = p.coordinador.cancelSocketPaymentRequest(req)
            if (puedeReintentar) {
                assertWithMessage("$caso: la pantalla dueña puede reintentar ⇒ sigue siendo de la terminal").that(viva(sonda.disposition, sonda.executionAlive)).isTrue()
                assertWithMessage("$caso: y el cancel del POS se acepta").that(cancel.disposition).isEqualTo(RemotePaymentCancelDisposition.ACCEPTED)
            } else {
                assertWithMessage("$caso: la sonda no contesta «viva» (${sonda.disposition}, viva=${sonda.executionAlive})")
                    .that(viva(sonda.disposition, sonda.executionAlive)).isFalse()
                assertWithMessage("$caso: el cancel no contesta «viva» (${cancel.disposition}, viva=${cancel.executionAlive})")
                    .that(viva(cancel.disposition, cancel.executionAlive)).isFalse()
            }
            assertWithMessage("$caso: nada aparta el aparato").that(p.libreta.retencionDelAparato()).isNull()
            assertWithMessage("$caso: una venta nueva reserva").that(p.libreta.openAttempt("nueva-$n", "venue-1", "BLUMON", 1000, 0, "FAST", contexto(null, "otra-$n"))).isTrue()
            assertWithMessage("$caso: …y llega al lector").that(p.libreta.markKernelEntered("nueva-$n")).isTrue()
            assertThat(p.libreta.markKernelRefused("nueva-$n", "fin_de_la_prueba")).isTrue()
        }
    }

    /**
     * Control del invariante: con una llamada al SDK EN VUELO (el intento reservó y entró al lector y nadie lo soltó) la terminal SÍ
     * sigue cobrando: «viva», no se certifica y otra venta no entra. Y deja de serlo al pasar el tope, sin reiniciar.
     */
    @Test fun `D26 control - con el lector en uso sigue viva, y al pasar el tope deja de apartar la terminal`() = runTest {
        val p = Proceso()
        p.llegaYSeReclama("req-1")
        assertThat(p.libreta.openAttempt("a1", "venue-1", "BLUMON", 21500, 0, "ORDER", contexto("req-1", "orden-1"))).isTrue()
        assertThat(p.libreta.markKernelEntered("a1")).isTrue()
        assertThat(p.libreta.markAuthorizing("a1")).isTrue()

        val sonda = p.coordinador.probeSocketPaymentRequest("req-1", "venue-1")
        assertThat(sonda.disposition).isEqualTo(RemotePaymentProbeDisposition.ACTIVE)
        assertThat(sonda.executionAlive).isTrue()
        assertThat(p.coordinador.cancelSocketPaymentRequest("req-1").executionAlive).isTrue()
        assertWithMessage("otra venta no entra mientras el lector está en uso")
            .that(p.libreta.openAttempt("otra", "venue-1", "BLUMON", 1000, 0, "FAST", contexto(null, "otra"))).isFalse()

        reloj += TOPE_LECTOR_EN_USO_MS + 1

        val tras = p.coordinador.probeSocketPaymentRequest("req-1", "venue-1")
        assertThat(tras.disposition).isEqualTo(RemotePaymentProbeDisposition.ACTIVE)
        assertThat(tras.executionAlive).isFalse()
        assertThat(tras.closedForExecution).isTrue()
        assertWithMessage("la nota sigue cercando SU venta").that(db.paymentAttemptDao().getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_AUTORIZANDO)
        assertThat(p.libreta.openAttempt("otra", "venue-1", "BLUMON", 1000, 0, "FAST", contexto(null, "otra"))).isTrue()
        assertWithMessage("…la misma cuenta no se cobra encima").that(p.libreta.openAttempt("misma", "venue-1", "BLUMON", 21500, 0, "ORDER", contexto(null, "orden-1"))).isFalse()
    }

    /** Control: la pantalla que terminó su flujo SUELTA el lector (el `finally` de la PAX) y la terminal queda libre en el acto. */
    @Test fun `D26 control - al soltar el lector la misma solicitud se certifica en el acto`() = runTest {
        val p = Proceso()
        p.llegaYSeReclama("req-1")
        assertThat(p.libreta.openAttempt("a1", "venue-1", "BLUMON", 21500, 0, "ORDER", contexto("req-1", "orden-1"))).isTrue()
        assertThat(p.libreta.markKernelEntered("a1")).isTrue()
        assertThat(p.coordinador.cancelSocketPaymentRequest("req-1").executionAlive).isTrue()

        p.libreta.sueltaElLector("a1")

        val cancel = p.coordinador.cancelSocketPaymentRequest("req-1")
        assertThat(cancel.disposition).isEqualTo(RemotePaymentCancelDisposition.ACTIVE)
        assertThat(cancel.executionAlive).isFalse()
        assertThat(cancel.closedForExecution).isTrue()
        assertWithMessage("la fila no se reescribe").that(db.paymentAttemptDao().getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_KERNEL_ACTIVO)
    }

    // Producción, PAX 2.16.0: el rechazo de Momentum quedó en duda (con el fin del SDK) y la pantalla «Estamos confirmando» con la
    // bandera de cobro activo arriba. La bandera ya no decide: la terminal certifica el cese.
    @Test fun `D26 la bandera de cobro activo de una pantalla ya no deja la terminal viva`() = runTest {
        val p = Proceso()
        p.llegaYSeReclama("req-1")
        assertThat(p.libreta.openAttempt("a1", "venue-1", "BLUMON", 21500, 0, "ORDER", contexto("req-1", "orden-1"))).isTrue()
        assertThat(p.libreta.markKernelEntered("a1")).isTrue()
        assertThat(p.libreta.markAuthorizing("a1")).isTrue()
        p.libreta.markIndeterminate("a1", "Blumon sin veredicto: MomentumFailure · TRANSACCION NO PERMITIDA")
        p.libreta.marcarSdkRegreso("a1")
        p.banderas.setChargeAttemptActive(true)

        val sonda = p.coordinador.probeSocketPaymentRequest("req-1", "venue-1")
        assertThat(sonda.executionAlive).isFalse()
        assertThat(sonda.closedForExecution).isTrue()
    }

    // Nexgo: la barrera pasó (`AUTORIZANDO`) y la pantalla murió ANTES de lanzar el SDK. «No se cobró» acreditado (no lanzado).
    @Test fun `D26 AUTORIZANDO sin lanzar el SDK - al cerrarlo como no lanzado y soltar la propiedad el cancel se acepta`() = runTest {
        val p = Proceso()
        p.llegaYSeReclama("req-1")
        assertThat(p.libreta.openAttempt("a1", "venue-1", "ANGELPAY", 21500, 0, "ORDER", contexto("req-1", "orden-1"))).isTrue()
        assertThat(p.libreta.markAuthorizing("a1")).isTrue()

        assertThat(p.libreta.markSinAutorizacion("a1", "venue-1", "no_lanzado:pantalla_cerrada")).isTrue()
        p.coordinador.soltarPropiedad("req-1")

        assertThat(p.coordinador.cancelSocketPaymentRequest("req-1").disposition).isEqualTo(RemotePaymentCancelDisposition.ACCEPTED)
        assertThat(db.paymentAttemptDao().getById("a1")!!.state).isEqualTo(PaymentAttemptEntity.STATE_DESCARTADA)
    }

    // La pantalla murió sin desenlace y sin intentos: con la propiedad en memoria la sonda decía «viva» para siempre.
    @Test fun `D26 sin intentos y la pantalla ida - soltar la propiedad deja que la sonda lo resuelva`() = runTest {
        val p = Proceso()
        p.llegaYSeReclama("req-1")
        assertThat(p.coordinador.probeSocketPaymentRequest("req-1", "venue-1").executionAlive).isTrue()

        p.coordinador.soltarPropiedad("req-1")

        val sonda = p.coordinador.probeSocketPaymentRequest("req-1", "venue-1")
        assertThat(sonda.disposition).isEqualTo(RemotePaymentProbeDisposition.RECEIVED_CANCELLED)
        assertThat(sonda.finalResultJson).contains("cancelled")
    }

    // Requisito del founder (22-nov): rechazo DEFINITIVO del banco con su pantalla abierta («Reintentar»): el cobro sigue siendo de la
    // terminal (la sonda no lo cierra), el cancel del POS SÍ se acepta, y la misma cuenta se puede volver a cobrar con la misma tarjeta.
    @Test fun `D26 rechazo definitivo - sigue de la terminal con Reintentar, el cancel se acepta y la misma cuenta se vuelve a cobrar`() = runTest {
        val p = Proceso()
        p.llegaYSeReclama("req-1")
        assertThat(p.libreta.openAttempt("a1", "venue-1", "BLUMON", 21500, 0, "ORDER", contexto("req-1", "orden-1"))).isTrue()
        assertThat(p.libreta.markKernelEntered("a1")).isTrue()
        assertThat(p.libreta.markAuthorizing("a1")).isTrue()
        p.libreta.markHostResponded("a1", false, null, null, null)
        p.libreta.marcarSdkRegreso("a1")

        val sonda = p.coordinador.probeSocketPaymentRequest("req-1", "venue-1")
        assertThat(sonda.executionAlive).isTrue()
        assertThat(sonda.closedForExecution).isFalse()
        assertWithMessage("«Reintentar» con la misma tarjeta: otro intento de la MISMA solicitud y cuenta")
            .that(p.libreta.openAttempt("a2", "venue-1", "BLUMON", 21500, 0, "ORDER", contexto("req-1", "orden-1"))).isTrue()
        assertThat(p.libreta.markDiscardedBeforeCharge("a2", "fin_de_la_prueba")).isTrue()
        assertThat(p.coordinador.cancelSocketPaymentRequest("req-1").disposition).isEqualTo(RemotePaymentCancelDisposition.ACCEPTED)
        assertWithMessage("el POS vuelve a mandar la misma cuenta: entra")
            .that(p.libreta.openAttempt("b1", "venue-1", "BLUMON", 21500, 0, "ORDER", contexto(null, "orden-1"))).isTrue()
    }

    // ── Codex r1 (22-nov) ───────────────────────────────────────────────────────────────────────────────────────────────────

    /** #1: el relanzamiento tras la sesión vencida (D308) no vuelve a reservar: sólo con el lector vigente y la solicitud abierta. */
    @Test fun `D26 Codex r1-1 el relanzamiento sin reservar sólo pasa con el lector vigente y la solicitud abierta`() = runTest {
        val p = Proceso()
        p.llegaYSeReclama("req-1")
        assertThat(p.libreta.openAttempt("a1", "venue-1", "ANGELPAY", 21500, 0, "ORDER", contexto("req-1", "orden-1"))).isTrue()
        assertThat(p.libreta.markAuthorizing("a1")).isTrue()
        assertWithMessage("con el lector vigente se relanza y el tope vuelve a contar").that(p.libreta.relanzaConElLector("a1")).isTrue()
        reloj += TOPE_LECTOR_EN_USO_MS - 1
        assertWithMessage("renovado: sigue viva").that(p.coordinador.probeSocketPaymentRequest("req-1", "venue-1").executionAlive).isTrue()

        reloj += TOPE_LECTOR_EN_USO_MS + 1   // la renovación de la sesión tardó de más: la sonda certifica el cese
        val sonda = p.coordinador.probeSocketPaymentRequest("req-1", "venue-1")
        assertThat(sonda.executionAlive).isFalse()
        assertThat(sonda.closedForExecution).isTrue()
        assertWithMessage("tras el certificado ya no se relanza").that(p.libreta.relanzaConElLector("a1")).isFalse()
    }

    @Test fun `D26 Codex r1-1 con la solicitud cerrada o el lector soltado no se relanza aunque el tope no haya vencido`() = runTest {
        val p = Proceso()
        p.llegaYSeReclama("req-1")
        assertThat(p.libreta.openAttempt("a1", "venue-1", "ANGELPAY", 21500, 0, "ORDER", contexto("req-1", "orden-1"))).isTrue()
        assertThat(p.libreta.markAuthorizing("a1")).isTrue()
        assertThat(db.paymentAttemptDao().bandejaCerrarParaEjecucion("req-1", 2L)).isEqualTo(1)
        assertThat(p.libreta.relanzaConElLector("a1")).isFalse()
        p.libreta.sueltaElLector("a1")   // a1 con el lector en uso apartaría el aparato para la reserva de a2

        p.llegaYSeReclama("req-2")
        assertThat(p.libreta.openAttempt("a2", "venue-1", "ANGELPAY", 21500, 0, "ORDER", contexto("req-2", "orden-2"))).isTrue()
        p.libreta.sueltaElLector("a2")
        assertThat(p.libreta.relanzaConElLector("a2")).isFalse()
    }

    /** #2: el flujo de la PAX que termina por una excepción a media llamada no suelta el lector: cuenta hasta el tope. */
    @Test fun `D26 Codex r1-2 al terminar el flujo con una llamada al SDK sin regreso comprobado el lector cuenta hasta el tope`() = runTest {
        val p = Proceso()
        p.llegaYSeReclama("req-1")
        assertThat(p.libreta.openAttempt("a1", "venue-1", "BLUMON", 21500, 0, "ORDER", contexto("req-1", "orden-1"))).isTrue()
        assertThat(p.libreta.markKernelEntered("a1")).isTrue()
        assertThat(p.libreta.markAuthorizing("a1")).isTrue()

        p.libreta.sueltaElLectorAlTerminarElFlujo("a1")
        assertWithMessage("AUTORIZANDO sin regreso: sigue viva").that(p.coordinador.probeSocketPaymentRequest("req-1", "venue-1").executionAlive).isTrue()
        reloj += TOPE_LECTOR_EN_USO_MS + 1
        assertWithMessage("…hasta el tope, sin reiniciar").that(p.coordinador.probeSocketPaymentRequest("req-1", "venue-1").executionAlive).isFalse()

        // Contraste: con el regreso del SDK escrito (duda con el fin del SDK) o sin haber entrado al lector, el flujo lo suelta en el acto.
        p.llegaYSeReclama("req-2")
        assertThat(p.libreta.openAttempt("b1", "venue-1", "BLUMON", 21500, 0, "ORDER", contexto("req-2", "orden-2"))).isTrue()
        p.libreta.sueltaElLectorAlTerminarElFlujo("b1")
        assertWithMessage("PREPARANDO: se suelta").that(p.libreta.hayLectorEnUso()).isFalse()
    }

    /** #4: la cuarentena por reloj de ESTE proceso sigue cercando SU cuenta aunque ningún intento tenga el lector. */
    @Test fun `D26 Codex r1-4 la cuarentena de este proceso cerca su cuenta sin lector en uso, y otra cuenta entra`() = runTest {
        val p = Proceso()
        db.paymentAttemptDao().insert(PaymentAttemptEntity(
            attemptId = "a1", venueId = "venue-1", processor = "BLUMON", state = PaymentAttemptEntity.STATE_INDETERMINADO,
            amountCents = 21500, tipCents = 0, recordingRoute = "ORDER", paymentContextJson = contexto(null, "orden-1"),
            createdAt = 1L, updatedAt = 1L, processToken = p.libreta.tokenDelProceso(), lastError = "cuarentena_por_antiguedad",
        ))
        assertThat(p.libreta.hayLectorEnUso()).isFalse()
        assertWithMessage("la misma cuenta no se cobra encima").that(p.libreta.openAttempt("b1", "venue-1", "BLUMON", 21500, 0, "ORDER", contexto(null, "orden-1"))).isFalse()
        assertWithMessage("otra cuenta entra").that(p.libreta.openAttempt("c1", "venue-1", "BLUMON", 1000, 0, "ORDER", contexto(null, "orden-2"))).isTrue()
        // El CAS al lector también la ve (B reservó antes de que A quedara en cuarentena).
        db.paymentAttemptDao().insert(PaymentAttemptEntity(
            attemptId = "b2", venueId = "venue-1", processor = "BLUMON", state = PaymentAttemptEntity.STATE_PREPARANDO,
            amountCents = 21500, tipCents = 0, recordingRoute = "ORDER", paymentContextJson = contexto(null, "orden-1"),
            createdAt = 2L, updatedAt = 2L, processToken = p.libreta.tokenDelProceso(),
        ))
        assertThat(p.libreta.markKernelEntered("b2")).isFalse()
    }

    /** Codex r2 #2: con el tope, el cese puede certificarse con la fila en el lector; después nada de esa solicitud entra a autorizar. */
    @Test fun `D26 Codex r2-2 tras el cese certificado la autorizacion se rechaza y un aprobado tardio no revive la marca`() = runTest {
        val p = Proceso()
        p.llegaYSeReclama("req-1")
        assertThat(p.libreta.openAttempt("a1", "venue-1", "BLUMON", 21500, 0, "ORDER", contexto("req-1", "orden-1"))).isTrue()
        assertThat(p.libreta.markKernelEntered("a1")).isTrue()
        reloj += TOPE_LECTOR_EN_USO_MS + 1
        assertThat(p.coordinador.probeSocketPaymentRequest("req-1", "venue-1").closedForExecution).isTrue()
        assertWithMessage("certificado el cese, la autorización no entra").that(p.libreta.markAuthorizing("a1")).isFalse()

        // El banco contesta DESPUÉS del certificado (la llamada ya estaba fuera): se guarda, y la marca no revive.
        p.llegaYSeReclama("req-2")
        assertThat(p.libreta.openAttempt("b1", "venue-1", "BLUMON", 1000, 0, "ORDER", contexto("req-2", "orden-2"))).isTrue()
        assertThat(p.libreta.markKernelEntered("b1")).isTrue()
        assertThat(p.libreta.markAuthorizing("b1")).isTrue()
        reloj += TOPE_LECTOR_EN_USO_MS + 1
        assertThat(p.coordinador.probeSocketPaymentRequest("req-2", "venue-1").closedForExecution).isTrue()
        assertWithMessage("el aprobado tardío se guarda").that(p.libreta.markHostResponded("b1", true, "op-1", "ref-1", "auth-1")).isTrue()
        assertWithMessage("y la solicitud certificada no vuelve a «viva»").that(p.coordinador.probeSocketPaymentRequest("req-2", "venue-1").executionAlive).isFalse()
    }

    /** Codex r3 #4: el aprobado que llega con la marca vencida pero SIN certificado todavía le da sus 150 s al cierre EMV. */
    @Test fun `D26 Codex r3-4 el aprobado tardio sin certificado previo renueva el lector para el cierre EMV`() = runTest {
        val p = Proceso()
        p.llegaYSeReclama("req-1")
        assertThat(p.libreta.openAttempt("a1", "venue-1", "BLUMON", 1000, 0, "ORDER", contexto("req-1", "orden-1"))).isTrue()
        assertThat(p.libreta.markKernelEntered("a1")).isTrue()
        assertThat(p.libreta.markAuthorizing("a1")).isTrue()
        reloj += TOPE_LECTOR_EN_USO_MS + 1   // el banco tardó y nadie preguntó en medio
        assertThat(p.libreta.markHostResponded("a1", true, "op-1", "ref-1", "auth-1")).isTrue()
        assertWithMessage("sigue el cierre EMV: viva").that(p.coordinador.probeSocketPaymentRequest("req-1", "venue-1").executionAlive).isTrue()
    }

    /** #7: con la pantalla del SDK de OTRA venta encima (pila apilada), una solicitud YA cerrada y sin lector en uso no vuelve a «viva». */
    @Test fun `D26 Codex r1-7 una solicitud ya cerrada no vuelve a viva por la pantalla de otra venta`() = runTest {
        val p = Proceso(pila = PilaDePantallas { listOf(TareaDeLaApp(pantallas = 2, arribaLaPrincipal = false)) })
        p.llegaYSeReclama("req-1")
        p.coordinador.soltarPropiedad("req-1")
        db.paymentAttemptDao().insert(PaymentAttemptEntity(
            attemptId = "a1", venueId = "venue-1", processor = "BLUMON", state = PaymentAttemptEntity.STATE_INDETERMINADO,
            amountCents = 21500, tipCents = 0, recordingRoute = "ORDER", paymentContextJson = contexto("req-1", "orden-1"),
            createdAt = 1L, updatedAt = 1L, terminalPaymentRequestId = "req-1", processToken = p.libreta.tokenDelProceso(),
        ))
        assertThat(db.paymentAttemptDao().bandejaCerrarParaEjecucion("req-1", 2L)).isEqualTo(1)
        val sonda = p.coordinador.probeSocketPaymentRequest("req-1", "venue-1")
        assertWithMessage("cerrada: ${sonda.disposition} viva=${sonda.executionAlive}").that(sonda.executionAlive).isFalse()
        assertThat(sonda.closedForExecution).isTrue()
    }
}

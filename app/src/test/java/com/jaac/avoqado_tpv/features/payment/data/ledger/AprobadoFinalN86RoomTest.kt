package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.gson.Gson
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayChargeVerifier
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.VerificacionDelCobro
import com.jaac.avoqado_tpv.features.payment.data.repository.FastPaymentRecorder
import com.jaac.avoqado_tpv.features.payment.data.repository.OrderPaymentRecorder
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

/**
 * 🔴 F1 bloques 3 y 4 (N86 · AngelPay, `plan-minimo-v2-tpv.md` §3): el aprobado final queda en SU intento con una obligación de registro
 * antes del POST, y una consulta al historial se gasta cuando su respuesta queda escrita. Todo sobre los DAO y la libreta REALES en una
 * SQLite en memoria; «morir» = descartar libreta, recuperaciones y corrutinas y seguir con la MISMA base (la memoria del proceso se
 * pierde, Room no). El registrador simulado CONSERVA lo que aceptó (como el SERVIDOR con su índice único por `idempotencyKey`): se
 * cuentan pagos por llave, no llamadas al mock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class AprobadoFinalN86RoomTest {
    private val venue = "venue-1"
    private lateinit var db: AvoqadoDatabase
    private lateinit var dao: PaymentAttemptDao

    @Before fun abrir() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.paymentAttemptDao()
    }

    @After fun cerrar() = db.close()

    /** Un proceso nuevo de la app: libreta con otro token, sin memoria. La base es la misma. */
    private fun libreta() = PaymentAttemptLedger(dao, mockk(relaxed = true))

    /** El SERVIDOR simulado: un `Payment` por llave; puede aceptar y perder la respuesta (el HTTP se corta tras el commit remoto). */
    private class Servidor {
        val pagos = mutableMapOf<String, Int>()   // llave → cuántos Payment (debe ser ≤ 1)
        var perderLaRespuesta = false
        fun registrar(ctx: PaymentContext): Result<PaymentReceipt> {
            val llave = ctx.idempotencyKey!!
            pagos[llave] = 1   // índice único: repetir la llave adopta el que ya existe
            if (perderLaRespuesta) { perderLaRespuesta = false; return Result.failure(java.io.IOException("respuesta perdida")) }
            return Result.success(PaymentReceipt("pay-$llave", "url", "key", ctx.amount, ctx.tip))
        }
    }

    private fun registrador(servidor: Servidor): LedgerApprovalRecovery {
        val order = mockk<OrderPaymentRecorder>()
        coEvery { order.recordPayment(any(), any(), any(), any()) } answers { servidor.registrar(firstArg()) }
        val fast = mockk<FastPaymentRecorder>()
        coEvery { fast.recordPayment(any(), any(), any(), any()) } answers { servidor.registrar(firstArg()) }
        return LedgerApprovalRecovery(dao, fast, order)
    }

    private fun contexto(id: String, orden: String = "orden-A"): String = Gson().toJsonTree(
        PaymentContext.AngelPayPayment(
            venueId = venue, staffId = "cajera", amount = BigDecimal("100.00"), tip = BigDecimal.ZERO, merchantAccountId = "merchant",
            deviceSerialNumber = "terminal", idempotencyKey = id, orderId = orden,
        ),
    ).asJsonObject.apply { addProperty("processorAffiliation", "afiliacion") }.toString()

    private val creado = System.currentTimeMillis() - 10 * 60_000

    private suspend fun dudaN86(id: String, orden: String = "orden-A", solicitud: String? = null) {
        val json = if (solicitud == null) contexto(id, orden)
            else com.google.gson.JsonParser.parseString(contexto(id, orden)).asJsonObject.apply { addProperty("terminalPaymentRequestId", solicitud) }.toString()
        dao.insert(
            PaymentAttemptEntity(
                attemptId = id, venueId = venue, processor = "ANGELPAY", state = "INDETERMINADO", amountCents = 10_000, tipCents = 0,
                recordingRoute = "ORDER", paymentContextJson = json, terminalPaymentRequestId = solicitud, serverAnsweredAt = 1L,
                createdAt = creado, updatedAt = creado,
            ),
        )
    }

    private fun sql(s: String) = db.openHelper.writableDatabase.execSQL(s)

    /** La firma del gerente («no se cobró», guardada en Avoqado), como la deja la declaración. */
    private fun firmar(id: String) =
        sql("UPDATE payment_attempts SET state = 'DESCARTADA', last_error = 'declarado_sin_cobro:gerente-1:avoqado' WHERE attempt_id = '$id'")

    /** La ventana automática del SERVIDOR (30 s sin evidencia) liberó la fila. */
    private fun ventana(id: String) = sql(
        "UPDATE payment_attempts SET state = 'DESCARTADA', server_outcome = 'RELEASED_NO_EVIDENCE', " +
            "last_error = 'liberada_por_el_servidor:NO_EVIDENCE_AFTER_WINDOW' WHERE attempt_id = '$id'",
    )

    private fun verificador(vararg respuestas: suspend () -> VerificacionDelCobro): Pair<AngelPayChargeVerifier, () -> Int> {
        var n = 0
        val v = mockk<AngelPayChargeVerifier>()
        coEvery { v.verificar(any(), any(), any(), any(), any(), any()) } coAnswers {
            val r = respuestas[minOf(n, respuestas.size - 1)]
            n++
            r()
        }
        return v to { n }
    }

    private val cobrado: suspend () -> VerificacionDelCobro = { VerificacionDelCobro.Cobrado("AUTH", "REF", null) }
    private val noAparece: suspend () -> VerificacionDelCobro = { VerificacionDelCobro.NoCobrado }
    /** El proceso muere con la respuesta en la mano, antes de escribirla. */
    private val muere: suspend () -> VerificacionDelCobro = { throw CancellationException("el proceso murió") }

    private suspend fun otraTarjetaDeA(l: PaymentAttemptLedger, id: String = "b-${System.nanoTime()}"): Boolean {
        val entro = l.openAttempt(id, venue, "ANGELPAY", 10_000, 0, "ORDER", """{"orderId":"orden-A"}""")
        if (entro) l.markDiscardedBeforeCharge(id, "fin de la prueba")
        return entro
    }

    private suspend fun consultasGastadas(id: String): Int =
        db.openHelper.readableDatabase.query("SELECT history_checks FROM payment_attempts WHERE attempt_id = '$id'").use {
            it.moveToFirst(); it.getInt(0)
        }

    private suspend fun dineroEscrito(id: String) {
        val fila = dao.getById(id)!!
        assertWithMessage("el aprobado queda en SU intento").that(fila.hostApproved).isTrue()
        assertThat(fila.referenceNumber).isEqualTo("REF")
        assertThat(fila.authCode).isEqualTo("AUTH")
    }

    // ═══════════════════════════════════════ Pruebas con nombre (Codex código r1–r3) ═══════════════════════════════════════

    @Test fun `N86_firma_antes_durante_y_despues_de_E1_conserva_ref_y_auth`() = runBlocking<Unit> {
        dudaN86("antes", orden = "orden-1"); firmar("antes")     // la firma llega antes de la primera consulta
        dudaN86("durante", orden = "orden-2")                    // la firma llega con la consulta en vuelo
        dudaN86("despues", orden = "orden-3")                    // «no aparece», luego la firma, luego «cobrado»
        val v = mockk<AngelPayChargeVerifier>()
        coEvery { v.verificar("antes", any(), any(), any(), any(), any()) } returns VerificacionDelCobro.Cobrado("AUTH", "REF", null)
        coEvery { v.verificar("durante", any(), any(), any(), any(), any()) } coAnswers {
            firmar("durante"); VerificacionDelCobro.Cobrado("AUTH", "REF", null)
        }
        coEvery { v.verificar("despues", any(), any(), any(), any(), any()) } returnsMany
            listOf(VerificacionDelCobro.NoCobrado, VerificacionDelCobro.Cobrado("AUTH", "REF", null))
        val t = System.currentTimeMillis() + 600_000
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t)
        firmar("despues")
        // Reinicio entre medias: la firma no repone turnos ni saca la fila de la consulta.
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t + 3_600_000)

        val servidor = Servidor()
        val tarde = t + 7_200_000
        registrador(servidor).recover(venue, tarde)
        registrador(servidor).recover(venue, tarde + 1)
        for (id in listOf("antes", "durante", "despues")) {
            dineroEscrito(id)
            assertWithMessage("$id: una venta, con la llave del intento").that(servidor.pagos[id]).isEqualTo(1)
        }
        assertThat(servidor.pagos.keys).containsExactly("antes", "durante", "despues")
        assertWithMessage("la firma no repone turnos: una respuesta sin coincidencia, un turno").that(consultasGastadas("despues")).isEqualTo(1)
    }

    @Test fun `N86_quinta_respuesta_muere_antes_del_commit_repite_el_turno_y_registra_una_venta`() = runBlocking<Unit> {
        dudaN86("a1"); firmar("a1")   // TPV SOLA: el gerente ya firmó
        var t = System.currentTimeMillis() + 600_000
        val (v, consultas) = verificador(noAparece, noAparece, noAparece, noAparece, muere, cobrado)
        repeat(4) { LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t); t += 3_600_000 }
        assertThat(consultas()).isEqualTo(4)
        // La quinta: AngelPay contesta «cobrado» y la app muere antes de escribir.
        runCatching { LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t) }
        assertThat(consultas()).isEqualTo(5)
        // Reinicio: la quinta vuelve.
        t += 3_600_000
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t)
        assertWithMessage("la consulta que murió sin respuesta se repite").that(consultas()).isEqualTo(6)
        dineroEscrito("a1")
        assertWithMessage("A queda protegida para otra tarjeta").that(otraTarjetaDeA(libreta())).isFalse()
        val servidor = Servidor()
        registrador(servidor).recover(venue, t + 3_600_000)
        assertThat(servidor.pagos).containsExactly("a1", 1)
        assertWithMessage("gastadas ≤ respuestas escritas (4 «no aparece») y ≤ 5").that(consultasGastadas("a1")).isAtMost(4)
    }

    /**
     * 🔴 Codex final r1 #3 · TPV N86: la ventana automática cierra la fila mientras AngelPay contesta la quinta consulta, y esa quinta
     * falla por la red. Un fallo técnico no gasta turno ni borra la marca de la lectura despachada: al vencer la pausa la quinta se
     * retoma aunque la ventana la cerró, y mientras tanto la tarjeta de A sigue frenada.
     */
    @Test fun `N86_quinta_consulta_falla_la_red_no_pierde_el_turno_ni_la_cerca`() = runBlocking<Unit> {
        dudaN86("a1")
        var t = System.currentTimeMillis() + 600_000
        val ventanaYSinRed: suspend () -> VerificacionDelCobro = { ventana("a1"); VerificacionDelCobro.NoSePudoVerificar("sin red") }
        val (v, consultas) = verificador(noAparece, noAparece, noAparece, noAparece, ventanaYSinRed, cobrado)
        repeat(4) { LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t); t += 3_600_000 }
        assertThat(consultasGastadas("a1")).isEqualTo(4)

        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t)
        assertThat(consultas()).isEqualTo(5)
        assertWithMessage("el fallo de red no gasta el turno").that(consultasGastadas("a1")).isEqualTo(4)
        assertWithMessage("la lectura sigue despachada").that(dao.getById("a1")!!.historyReadAt).isNotNull()
        assertWithMessage("la tarjeta de A sigue frenada").that(otraTarjetaDeA(libreta())).isFalse()

        // Vence la pausa: la quinta se retoma aunque la ventana cerró la fila.
        t += 3_600_000
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t)
        assertWithMessage("la quinta se retoma").that(consultas()).isEqualTo(6)
        dineroEscrito("a1")
        assertThat(dao.getById("a1")!!.serverOutcome).isEqualTo("RELEASED_NO_EVIDENCE")
        val servidor = Servidor()
        registrador(servidor).recover(venue, t + 3_600_000)
        assertWithMessage("una venta, con la llave del intento").that(servidor.pagos).containsExactly("a1", 1)
    }

    /**
     * 🔴 Codex final r2 #4 · TPV N86 (familia «turnos del barrido»): 50 lecturas despachadas que fallan para siempre (credenciales
     * inválidas: pausa sin gastar turno) no pueden dejar sin consulta a una venta B cuyo aprobado sólo aparecería en el historial. Tres
     * barridos separados por seis horas, como el del aparato (`LedgerSweepScheduler`): B entra a más tardar en el segundo.
     */
    @Test fun `N86_cincuenta_lecturas_que_fallan_siempre_no_dejan_sin_turno_a_B`() = runBlocking<Unit> {
        val ahora = System.currentTimeMillis()
        repeat(50) { i -> dudaN86("f-$i", orden = "orden-$i") }
        // Las 50 ya se despacharon hace 3 h (la lectura sigue anotada: su fallo técnico nunca se escribe como respuesta).
        sql("UPDATE payment_attempts SET history_read_at = ${ahora - 3 * 3_600_000L} WHERE attempt_id LIKE 'f-%'")
        // B llegó DESPUÉS de esa vuelta y nadie la ha consultado.
        dudaN86("b", orden = "orden-B")
        sql("UPDATE payment_attempts SET created_at = ${ahora - 2 * 3_600_000L} WHERE attempt_id = 'b'")
        var consultasDeB = 0
        val v = mockk<AngelPayChargeVerifier>()
        coEvery { v.verificar(any(), any(), any(), any(), any(), any()) } coAnswers {
            if (firstArg<String>() == "b") { consultasDeB++; VerificacionDelCobro.NoCobrado }
            else VerificacionDelCobro.NoSePudoVerificar("credenciales inválidas")
        }
        var t = ahora
        repeat(3) { LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t); t += 6 * 3_600_000L }
        assertWithMessage("B recibe su turno aunque 50 filas fallen siempre").that(consultasDeB).isAtLeast(1)
        assertWithMessage("las que fallan siguen despachadas (se retoman)").that(dao.getById("f-0")!!.historyReadAt).isNotNull()
        assertWithMessage("y sin gastar turno").that(consultasGastadas("f-0")).isEqualTo(0)
    }

    @Test fun `lease_viejo_con_aprobado_propio_conserva_dinero_sin_gastar_dos_turnos`() = runBlocking<Unit> {
        dudaN86("a1")
        val suelta = CompletableDeferred<Unit>()
        val entro = CompletableDeferred<Unit>()
        val (v, _) = verificador({ entro.complete(Unit); suelta.await(); VerificacionDelCobro.Cobrado("AUTH", "REF", null) }, noAparece)
        val t = System.currentTimeMillis() + 600_000
        // El primer trabajador toma la consulta y se queda esperando a AngelPay más que su reserva.
        val viejo = async(Dispatchers.Default) { LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t) }
        entro.await()
        // Vencida la reserva, otro trabajador la retoma y recibe «todavía no aparece».
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t + 6 * 60_000)
        // Entonces llega el «cobrado» del primero, con la reserva vieja.
        suelta.complete(Unit)
        viejo.await()
        dineroEscrito("a1")
        assertThat(dao.getById("a1")!!.state).isEqualTo("HOST_RESPONDIO")
        assertWithMessage("A queda protegida").that(otraTarjetaDeA(libreta())).isFalse()
        assertWithMessage("una sola respuesta sin coincidencia: un turno, no dos").that(consultasGastadas("a1")).isAtMost(1)
    }

    @Test fun `N86_K19a_ventana_cierra_antes_del_cobrado_muerte_antes_del_POST`() = runBlocking<Unit> {
        dudaN86("a1")
        val pantalla = libreta()
        // La pantalla consulta el historial (K19a) y, mientras, la ventana del SERVIDOR libera la fila.
        ventana("a1")
        // AngelPay contesta «cobrado»: la pantalla lo escribe por la única puerta del aprobado final…
        pantalla.markHostResponded("a1", true, null, "REF", "AUTH")
        // …y la app muere antes del POST directo.
        val reiniciada = libreta()
        dineroEscrito("a1")
        assertWithMessage("la ventana conserva su motivo").that(dao.getById("a1")!!.serverOutcome).isEqualTo("RELEASED_NO_EVIDENCE")
        assertWithMessage("A protegida desde ese commit").that(otraTarjetaDeA(reiniciada)).isFalse()
        assertThat(reiniciada.retencionDeLaVenta("orden-A")?.attemptId).isEqualTo("a1")
        val servidor = Servidor()
        registrador(servidor).recover(venue, System.currentTimeMillis() + 3_600_000)
        registrador(servidor).recover(venue, System.currentTimeMillis() + 3_600_001)
        assertWithMessage("un pago recuperable, una vez").that(servidor.pagos).containsExactly("a1", 1)
    }

    @Test fun `aprobado_de_A1_firmado_no_se_escribe_en_A2`() = runBlocking<Unit> {
        // POS → N86: A1 quedó en duda y el gerente la firmó; A2, de la MISMA solicitud, está en duda. La pantalla se recrea SIN la
        // llave del lanzamiento y vuelve el resultado de A1: la solicitud ya no identifica el intento.
        dudaN86("a1", solicitud = "r-1"); firmar("a1")
        dudaN86("a2", solicitud = "r-1")
        val l = libreta()
        assertWithMessage("con dos dueños posibles no se adopta por la solicitud").that(l.adoptarCobroDeLaSolicitud("r-1")).isNull()
        assertThat(dao.getById("a2")!!.hostApproved).isNull()
        // El aprobado de A1 lo trae el historial por SU llave.
        val (v, _) = verificador(cobrado)
        coEvery { v.verificar("a2", any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoCobrado
        LedgerUnknownRecovery(dao, l, v).recover(venue, System.currentTimeMillis() + 600_000)
        dineroEscrito("a1")
        assertThat(dao.getById("a2")!!.hostApproved).isNull()
        // Con un solo dueño posible, la adopción por solicitud sigue como antes.
        dudaN86("b1", orden = "orden-B", solicitud = "r-2")
        assertThat(l.adoptarCobroDeLaSolicitud("r-2")?.attemptId).isEqualTo("b1")
    }

    // ═══════════════════════════════════════════════ Modelo reducido ═══════════════════════════════════════════════

    private enum class Cierre { NINGUNO, FIRMA, VENTANA }
    private enum class Fuente { SDK, K19A, E1 }
    /** Dónde muere el proceso: antes de escribir el aprobado, después de escribirlo y antes del POST, o con el POST aceptado y la respuesta perdida. */
    private enum class Muerte { ANTES_DE_ESCRIBIR, ANTES_DEL_POST, RESPUESTA_PERDIDA }

    /**
     * 27 casos (3 cierres × 3 fuentes × 3 muertes) sobre A1 (el cobro), A2 (hermano de la misma solicitud) y B (otra cuenta del mismo
     * importe). Tras cada uno se comprueba: el aprobado escrito protege A para otra tarjeta y B entra; una venta por llave y ninguna de
     * A2 ni de B; las consultas gastadas no pasan de las respuestas escritas ni de 5. Más 3 casos sin aprobado: la firma sola no crea
     * ningún pago y no gasta turnos.
     */
    @Test fun `modelo reducido N86 - tres cierres, tres fuentes, tres muertes`() = runBlocking<Unit> {
        var casos = 0
        for (cierre in Cierre.entries) for (fuente in Fuente.entries) for (muerte in Muerte.entries) {
            sql("DELETE FROM payment_attempts")
            dudaN86("a1", solicitud = "r-1"); dudaN86("a2", solicitud = "r-1"); dudaN86("b", orden = "orden-B")
            firmar("a2")   // el hermano firmado por el segundo gerente: nunca recibe el dinero de A1
            val caso = "$cierre/$fuente/$muerte"
            val servidor = Servidor()
            var t = System.currentTimeMillis() + 600_000
            var respuestasEscritas = 0
            val proceso1 = libreta()
            // E1 consulta mientras sucede el cierre; SDK/K19a: el cierre llega antes de que su aprobado se escriba.
            fun cerrar() = when (cierre) { Cierre.NINGUNO -> Unit; Cierre.FIRMA -> firmar("a1"); Cierre.VENTANA -> ventana("a1") }
            when (fuente) {
                Fuente.E1 -> {
                    val (v, _) = verificador({ cerrar(); if (muerte == Muerte.ANTES_DE_ESCRIBIR) throw CancellationException("muere") else VerificacionDelCobro.Cobrado("AUTH", "REF", null) }, cobrado)
                    coEvery { v.verificar("a2", any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoCobrado
                    coEvery { v.verificar("b", any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoCobrado
                    runCatching { LedgerUnknownRecovery(dao, proceso1, v).recover(venue, t) }
                    respuestasEscritas += 2   // a2 y b contestaron «no aparece»
                    if (muerte == Muerte.ANTES_DE_ESCRIBIR) {
                        // reinicio: la lectura despachada de A1 vuelve primero
                        t += 3_600_000
                        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t)
                        respuestasEscritas += 2
                    }
                }
                Fuente.SDK, Fuente.K19A -> {
                    if (fuente == Fuente.K19A) proceso1.despacharLecturaDelHistorial("a1")
                    cerrar()
                    if (muerte == Muerte.ANTES_DE_ESCRIBIR) {
                        // murió con el aprobado en RAM: lo recupera E1 desde el historial (la lectura de K19a quedó despachada)
                        val (v, _) = verificador(cobrado)
                        coEvery { v.verificar("a2", any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoCobrado
                        coEvery { v.verificar("b", any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoCobrado
                        t += 3_600_000
                        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t)
                        respuestasEscritas += 2
                    } else {
                        proceso1.markHostResponded("a1", true, null, "REF", "AUTH")
                    }
                }
            }
            val reiniciada = libreta()
            val a1 = dao.getById("a1")!!
            val sinRecuperar = fuente == Fuente.SDK && muerte == Muerte.ANTES_DE_ESCRIBIR && cierre == Cierre.VENTANA
            if (!sinRecuperar) {
                assertWithMessage("$caso: el aprobado queda en A1").that(a1.hostApproved).isTrue()
                assertWithMessage("$caso: referencia").that(a1.referenceNumber).isEqualTo("REF")
                assertWithMessage("$caso: A protegida para otra tarjeta").that(otraTarjetaDeA(reiniciada)).isFalse()
            }
            assertWithMessage("$caso: B (otra cuenta, mismo importe) entra")
                .that(reiniciada.openAttempt("b-nueva", venue, "ANGELPAY", 10_000, 0, "ORDER", """{"orderId":"orden-B2"}""")).isTrue()
            reiniciada.markDiscardedBeforeCharge("b-nueva", "fin")
            assertWithMessage("$caso: el hermano firmado no recibe el dinero").that(dao.getById("a2")!!.hostApproved).isNull()
            // Registro: POST aceptado con la respuesta perdida, y se repite con la misma llave.
            if (muerte == Muerte.RESPUESTA_PERDIDA) servidor.perderLaRespuesta = true
            registrador(servidor).recover(venue, t + 3_600_000)
            registrador(servidor).recover(venue, t + 7_200_000)
            registrador(servidor).recover(venue, t + 7_200_001)
            if (!sinRecuperar) assertWithMessage("$caso: una venta de A1").that(servidor.pagos["a1"]).isEqualTo(1)
            assertWithMessage("$caso: ni A2 ni B").that(servidor.pagos.keys - "a1").isEmpty()
            for (id in listOf("a1", "a2", "b")) {
                assertWithMessage("$caso: consultas de $id ≤ 5").that(consultasGastadas(id)).isAtMost(5)
            }
            assertWithMessage("$caso: gastadas ≤ respuestas escritas")
                .that(consultasGastadas("a1") + consultasGastadas("a2") + consultasGastadas("b")).isAtMost(respuestasEscritas)
            casos++
        }
        // Sin aprobado: la firma sola crea CERO pagos y no gasta turnos.
        for (cierre in listOf(Cierre.FIRMA, Cierre.VENTANA, Cierre.NINGUNO)) {
            sql("DELETE FROM payment_attempts")
            dudaN86("a1")
            when (cierre) { Cierre.FIRMA -> firmar("a1"); Cierre.VENTANA -> ventana("a1"); Cierre.NINGUNO -> Unit }
            val servidor = Servidor()
            registrador(servidor).recover(venue, System.currentTimeMillis() + 3_600_000)
            assertWithMessage("$cierre sin aprobado: cero pagos").that(servidor.pagos).isEmpty()
            assertThat(consultasGastadas("a1")).isEqualTo(0)
            casos++
        }
        assertThat(casos).isEqualTo(30)
    }
}

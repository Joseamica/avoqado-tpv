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
import com.jaac.avoqado_tpv.features.payment.domain.processor.PaymentPostOperationsAdapter
import com.jaac.avoqado_tpv.features.payment.domain.processor.PostOperationsAdapterFactory
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import com.jaac.avoqado_tpv.features.payment.domain.processor.TransactionHistoryQuery
import com.jaac.avoqado_tpv.features.payment.domain.processor.UnifiedTransaction
import io.mockk.coEvery
import io.mockk.every
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

    /** [afiliacion] null = la sesión del SDK no la dio al abrir el intento (el ViewModel guarda `null` tal cual). */
    private fun contexto(id: String, orden: String = "orden-A", afiliacion: String? = "afiliacion"): String = Gson().toJsonTree(
        PaymentContext.AngelPayPayment(
            venueId = venue, staffId = "cajera", amount = BigDecimal("100.00"), tip = BigDecimal.ZERO, merchantAccountId = "merchant",
            deviceSerialNumber = "terminal", idempotencyKey = id, orderId = orden,
        ),
    ).asJsonObject.apply { addProperty("processorAffiliation", afiliacion) }.toString()

    private val creado = System.currentTimeMillis() - 10 * 60_000

    /** Un intento abierto hace 2.5 min: la recuperación ya lo toma (más de 2 min) y todavía no llega al techo del `05AP` (5 min). */
    private val reciente get() = System.currentTimeMillis() - 150_000

    private suspend fun dudaN86(
        id: String, orden: String = "orden-A", solicitud: String? = null, afiliacion: String? = "afiliacion", creadoEn: Long = creado,
    ) {
        val json = if (solicitud == null) contexto(id, orden, afiliacion)
            else com.google.gson.JsonParser.parseString(contexto(id, orden, afiliacion)).asJsonObject.apply { addProperty("terminalPaymentRequestId", solicitud) }.toString()
        dao.insert(
            PaymentAttemptEntity(
                attemptId = id, venueId = venue, processor = "ANGELPAY", state = "INDETERMINADO", amountCents = 10_000, tipCents = 0,
                recordingRoute = "ORDER", paymentContextJson = json, terminalPaymentRequestId = solicitud, serverAnsweredAt = 1L,
                createdAt = creadoEn, updatedAt = creadoEn,
            ),
        )
    }

    /**
     * El reloj avanza [ms] para el intento [id]: su `created_at` (y la marca, si guarda la respuesta `05AP`, que es su `created_at`) se
     * corre hacia atrás. La admisión lee la hora del aparato: así se prueba «sólo por el reloj», sin otra consulta ni red.
     */
    private fun envejecer(id: String, ms: Long) = sql(
        "UPDATE payment_attempts SET history_read_at = CASE WHEN history_read_at = created_at THEN history_read_at - $ms ELSE history_read_at END, " +
            "created_at = created_at - $ms WHERE attempt_id = '$id'",
    )

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

    private suspend fun otraTarjetaDeA(l: PaymentAttemptLedger, id: String = "b-${System.nanoTime()}"): Boolean = otraTarjeta(l, "orden-A", id)

    private suspend fun otraTarjeta(l: PaymentAttemptLedger, orden: String, id: String = "b-${System.nanoTime()}"): Boolean {
        val entro = l.openAttempt(id, venue, "ANGELPAY", 10_000, 0, "ORDER", """{"orderId":"$orden"}""")
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
        assertWithMessage("el aprobado final no deja la marca de la lectura").that(fila.historyReadAt).isNull()
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
        // Codex r9 «Igual 3» (R9-3): «no aparece» tampoco gasta turno durante el seguimiento (7 días); antes, uno.
        assertWithMessage("una respuesta sin coincidencia no gasta turno en el seguimiento").that(consultasGastadas("despues")).isEqualTo(0)
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
        // C3 (techo de la marca, 20-nov): la cerca por la lectura pendiente dura hasta el techo; el intento se abre hace 2.5 min para que
        // la quinta falle ANTES del techo (con 10 min ya lo había pasado y la marca, sola, ya no frenaba).
        dudaN86("a1", creadoEn = reciente)
        var t = System.currentTimeMillis() + 600_000
        val ventanaYSinRed: suspend () -> VerificacionDelCobro = { ventana("a1"); VerificacionDelCobro.NoSePudoVerificar("sin red") }
        val (v, consultas) = verificador(noAparece, noAparece, noAparece, noAparece, ventanaYSinRed, cobrado)
        repeat(4) { LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t); t += 3_600_000 }
        assertWithMessage("R9-3: «no aparece» no gasta turno en el seguimiento (antes 4)").that(consultasGastadas("a1")).isEqualTo(0)

        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t)
        assertThat(consultas()).isEqualTo(5)
        assertWithMessage("el fallo de red no gasta el turno").that(consultasGastadas("a1")).isEqualTo(0)
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

    // ═══════════════════ Familia «turnos y marca del historial», ronda 3 (Codex final r3 #1 y plan de rediseño #8) ═══════════════════

    /**
     * 🔴 Codex final r3 #1 · TPV N86: 50 ventas guardadas SIN afiliación de AngelPay (la sesión del SDK no la dio al abrir el intento) nunca
     * se pueden consultar y el barrido las salta sin tomar turno. Con la misma clave de orden que B (lectura ya despachada) llenaban la
     * página de 50 de cada barrido: B, cuyo aprobado sólo aparecería en el historial, entraba 0 de 3 veces. Ahora el barrido pagina desde
     * la última fila LEÍDA y su tope (50) cuenta consultas INICIADAS: B y las 49 en duda que siguen se lo reparten.
     */
    @Test fun `N86_cincuenta_sin_afiliacion_delante_no_dejan_sin_turno_a_B`() = runBlocking<Unit> {
        val clave = System.currentTimeMillis() - 3 * 3_600_000L
        repeat(50) { i -> dudaN86("f-%02d".format(i), orden = "orden-f$i", afiliacion = null) }
        sql("UPDATE payment_attempts SET created_at = $clave WHERE attempt_id LIKE 'f-%'")
        dudaN86("zz-b", orden = "orden-B")
        sql("UPDATE payment_attempts SET history_read_at = $clave WHERE attempt_id = 'zz-b'")   // despachada, misma clave que las 50
        repeat(50) { i -> dudaN86("zz-c-%02d".format(i), orden = "orden-c$i") }               // en duda, detrás de B
        val consultadas = mutableListOf<String>()
        val v = mockk<AngelPayChargeVerifier>()
        coEvery { v.verificar(any(), any(), any(), any(), any(), any()) } coAnswers {
            consultadas += firstArg<String>(); VerificacionDelCobro.NoCobrado
        }
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, System.currentTimeMillis() + 600_000)
        assertWithMessage("B recibe su consulta").that(consultadas).contains("zz-b")
        assertWithMessage("el tope cuenta consultas iniciadas, no filas leídas").that(consultadas).hasSize(50)
        assertWithMessage("las 50 sin afiliación no se consultan").that(consultadas.filter { it.startsWith("f-") }).isEmpty()
        assertWithMessage("ni se marcan (frenaría su tarjeta)").that(dao.getById("f-00")!!.historyReadAt).isNull()
        assertWithMessage("ni gastan turno").that(consultasGastadas("f-00")).isEqualTo(0)
    }

    /**
     * 🔴 Codex plan de rediseño #8 (a) · TPV N86: el historial toma la consulta de A y, mientras AngelPay contesta, el SDK vivo escribe el
     * rechazo EXPLÍCITO del banco (quita la reserva y deja `host_approved = 0`). La respuesta del historial ya no encuentra su reserva y A
     * ya no es candidata: la marca quedaba viva sin consulta que la terminara, y la poda nunca quitaba la fila. El rechazo es la escritura
     * que decide que A ya no necesita el historial: quita la marca en el mismo UPDATE.
     */
    @Test fun `N86_rechazo_del_banco_tras_tomar_la_consulta_no_deja_la_marca_viva`() = runBlocking<Unit> {
        dudaN86("a1")
        val pantalla = libreta()   // la pantalla del cobro, viva
        val v = mockk<AngelPayChargeVerifier>()
        coEvery { v.verificar(any(), any(), any(), any(), any(), any()) } coAnswers {
            assertWithMessage("la consulta quedó tomada").that(dao.getById("a1")!!.historyReadAt).isNotNull()
            assertThat(pantalla.markHostResponded("a1", false, "OP", "REF", null)).isTrue()
            VerificacionDelCobro.NoCobrado
        }
        val t = System.currentTimeMillis() + 600_000
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t)
        val a1 = dao.getById("a1")!!
        assertThat(a1.state).isEqualTo("DESCARTADA")
        assertThat(a1.hostApproved).isFalse()
        assertWithMessage("la marca no queda viva sin una consulta que la termine").that(a1.historyReadAt).isNull()
        assertWithMessage("A ya no es candidata del historial")
            .that(dao.getUnknownRecoveryCandidates(venue, t + 3_600_000, t + 3_600_000).map { it.attemptId }).doesNotContain("a1")
        assertWithMessage("la tarjeta de A entra: el banco la rechazó").that(otraTarjetaDeA(libreta())).isTrue()
        dao.pruneTerminalOlderThan(venue, t + 8 * 86_400_000L)
        assertWithMessage("y la poda de la libreta la puede quitar").that(dao.getById("a1")).isNull()
    }

    /**
     * 🔴 Codex plan de rediseño #8 (b) · TPV N86: la pantalla despachó su lectura del historial (K19a) de un intento guardado SIN
     * afiliación y la app murió; la recuperación nunca podrá hacer esa consulta. A (la ventana ya la liberó) frenaba su tarjeta para
     * siempre; D (sigue en duda) también, aun después de la firma del gerente. Ahora el barrido quita la marca en la escritura que decide
     * que nadie la puede hacer: A queda como la resolvió la ventana y D pasa a la revisión del gerente («Por revisar», firmable).
     */
    @Test fun `N86_lectura_despachada_sin_afiliacion_no_frena_la_tarjeta_para_siempre`() = runBlocking<Unit> {
        dudaN86("a1", afiliacion = null)
        assertThat(libreta().despacharLecturaDelHistorial("a1")).isNotNull()
        ventana("a1")
        dudaN86("d1", orden = "orden-D", afiliacion = null)
        assertThat(libreta().despacharLecturaDelHistorial("d1")).isNotNull()
        val (v, consultas) = verificador(noAparece)
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, System.currentTimeMillis() + 600_000)   // venció la reserva de la pantalla

        assertWithMessage("sin afiliación el historial no se pregunta").that(consultas()).isEqualTo(0)
        assertWithMessage("A: la marca sale").that(dao.getById("a1")!!.historyReadAt).isNull()
        assertWithMessage("A: la ventana ya la resolvió; su tarjeta entra").that(otraTarjetaDeA(libreta())).isTrue()
        val l = libreta()
        assertWithMessage("D: la marca sale").that(dao.getById("d1")!!.historyReadAt).isNull()
        assertWithMessage("D: en «Por revisar», firmable por el gerente")
            .that(l.renglonPorRevisar(venue, CobroDeclarable.CLASE_INTENTO, "d1")?.declarable).isTrue()
        assertWithMessage("D: su tarjeta espera al gerente").that(otraTarjeta(l, "orden-D")).isFalse()
        firmar("d1")
        assertWithMessage("D: con la firma, su tarjeta entra").that(otraTarjeta(l, "orden-D")).isTrue()
    }

    /**
     * 🔴 Familia «marca del historial», ronda 3 (tabla): el SERVIDOR registra el Payment de A mientras el historial la consulta y esa
     * consulta falla por la red. Con Payment, A ya no es candidata (`server_payment_id`): la pausa conservaba una marca que nadie iba a
     * terminar y la fila ya nunca se podaba. El veredicto con Payment es la escritura que lo decide: quita la marca.
     */
    @Test fun `N86_payment_del_servidor_con_la_consulta_en_vuelo_no_deja_la_marca_viva`() = runBlocking<Unit> {
        dudaN86("a1")
        val v = mockk<AngelPayChargeVerifier>()
        coEvery { v.verificar(any(), any(), any(), any(), any(), any()) } coAnswers {
            dao.guardarVeredictoDelServidor("a1", "pay-1", "RECORDED", "terminal", 10_000, 0, null, System.currentTimeMillis())
            VerificacionDelCobro.NoSePudoVerificar("sin red")
        }
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, System.currentTimeMillis() + 600_000)
        assertThat(dao.getById("a1")!!.serverPaymentId).isEqualTo("pay-1")
        assertWithMessage("la marca no queda viva sin una consulta que la termine").that(dao.getById("a1")!!.historyReadAt).isNull()
    }

    /**
     * 🔴 Familia «marca del historial», ronda 3 (tabla): la primera escritura del aprobado final no entró y la marca del banco se sella al
     * pasar a AUTORIZADO (`sellarAprobacionDelHost`). Con `host_approved` ninguna consulta al historial la vuelve a tomar: quita la marca.
     */
    @Test fun `N86_aprobado_sellado_al_autorizar_no_deja_la_marca_viva`() = runBlocking<Unit> {
        dudaN86("a1")
        sql("UPDATE payment_attempts SET state = 'AUTORIZADO', history_read_at = ${System.currentTimeMillis()} WHERE attempt_id = 'a1'")
        assertThat(dao.sellarAprobacionDelHost("a1", null, "REF", "AUTH", System.currentTimeMillis())).isEqualTo(1)
        assertWithMessage("la marca no queda viva sin una consulta que la termine").that(dao.getById("a1")!!.historyReadAt).isNull()
    }

    // ═══════════════════════════════ Admisión de la N86 (Codex plan A #1 y #2, 20-nov) ═══════════════════════════════

    /** Un proceso cuya base no puede escribir el aprobado final de AngelPay (disco lleno, E/S): el aprobado queda sólo en memoria. */
    private fun libretaQueNoEscribeElAprobado() = PaymentAttemptLedger(
        object : PaymentAttemptDao by dao {
            override suspend fun escribirAprobadoFinalAngelPay(
                venueId: String, attemptId: String, referencia: String?, autorizacion: String?, origen: String, now: Long,
            ): AprobadoFinal = throw android.database.sqlite.SQLiteFullException("disco lleno")
        },
        mockk(relaxed = true),
    )

    /**
     * 🔴 Codex plan A #1 · TPV SOLA en la N86: el gerente firmó A1 («no se cobró»); llega su aprobado tardío y las dos escrituras fallan.
     * La libreta lo recuerda en memoria, pero la reserva del lector miraba sólo la base: otra tarjeta de la MISMA cuenta entraba (con el
     * aprobado escrito, no). Ahora la memoria frena igual que la base; otra cuenta del mismo importe entra.
     */
    @Test fun `N86_aprobado_solo_en_memoria_frena_la_reserva_de_otra_tarjeta_de_la_cuenta`() = runBlocking<Unit> {
        dudaN86("a1"); firmar("a1")
        val l = libretaQueNoEscribeElAprobado()
        assertWithMessage("el aprobado no quedó escrito").that(l.markHostResponded("a1", true, null, "REF", "AUTH")).isFalse()
        assertThat(l.aprobacionPendienteDeGuardar("a1")).isTrue()
        assertWithMessage("otra tarjeta de A no reserva el lector").that(otraTarjetaDeA(l)).isFalse()
        assertWithMessage("la pantalla lo ve antes de encender el lector").that(l.otraCercaDeLaTarjeta("orden-A", "b-x")).isTrue()
        assertWithMessage("otra cuenta, mismo importe, entra").that(otraTarjeta(l, "orden-B")).isTrue()
    }

    /**
     * 🔴 Codex plan A #1 · la misma memoria entre reservar y autorizar: B ya reservó (la firma de A1 no frena) y entonces el aprobado
     * tardío de A1 queda sólo en memoria. B no entra al lector ni pide autorización. Otra cuenta, sí.
     */
    @Test fun `N86_aprobado_solo_en_memoria_frena_lector_y_autorizacion_de_la_tarjeta_ya_reservada`() = runBlocking<Unit> {
        dudaN86("a1"); firmar("a1")
        val l = libretaQueNoEscribeElAprobado()
        assertThat(l.openAttempt("b", venue, "ANGELPAY", 10_000, 0, "ORDER", """{"orderId":"orden-A"}""")).isTrue()
        l.markHostResponded("a1", true, null, "REF", "AUTH")
        assertWithMessage("B no entra al lector").that(l.markKernelEntered("b")).isFalse()
        assertWithMessage("B no pide autorización").that(l.markAuthorizing("b")).isFalse()
        assertThat(dao.getById("b")!!.state).isEqualTo("PREPARANDO")
        l.markDiscardedBeforeCharge("b", "fin de la prueba")
        assertThat(l.openAttempt("c", venue, "ANGELPAY", 10_000, 0, "ORDER", """{"orderId":"orden-C"}""")).isTrue()
        assertWithMessage("otra cuenta sí pide autorización").that(l.markAuthorizing("c")).isTrue()
    }

    /** Cuántas veces se le preguntó al historial de AngelPay ([historialReal]). */
    private var consultasAlHistorial = 0

    /** El verificador REAL frente a un historial de AngelPay simulado: la venta de ESTE intento con [estado] (null = no aparece). */
    private fun historialReal(estado: () -> String?): AngelPayChargeVerifier {
        val adapter = mockk<PaymentPostOperationsAdapter>()
        coEvery { adapter.getTransactionHistory(any()) } answers {
            consultasAlHistorial++
            val id = firstArg<TransactionHistoryQuery>().integratorReference!!
            Result.success(listOfNotNull(estado()?.let { ventaEnElHistorial(id, it) }))
        }
        val fabrica = mockk<PostOperationsAdapterFactory>()
        every { fabrica.get(ProcessorType.ANGELPAY) } returns adapter
        return AngelPayChargeVerifier(fabrica)
    }

    /** Una fila del historial de AngelPay de la venta [id], de esta terminal y afiliación (las de [contexto]). */
    private fun ventaEnElHistorial(id: String, estado: String) = UnifiedTransaction(
        processorType = ProcessorType.ANGELPAY, reference = "REF", amount = 100.0,
        authorizationCode = if (estado == "APROBADA") "AUTH" else "", status = estado, cardType = null, last4 = null,
        cardBin = "411111", entryMode = "CHIP", date = null, time = null, operationType = "VENTA", merchantName = null,
        affiliation = "afiliacion", terminal = "terminal", folio = "1", waiter = null, tip = null, issuingBank = null, cardMethod = null,
        creationDate = "", aid = null, arqc = null, postOperationType = null, postOperationReference = null,
        postOperationAuthorization = null, postOperationStatus = null, integratorReference = id,
    )

    /**
     * 🔴 Codex plan A #2 (AngelPay, 19-nov, punto 31): el historial trae la venta en `05AP` («rejected»): llegó al procesador y el banco no
     * ha contestado; puede volverse aprobada. Se trataba como «no aparece»: se gastaba el turno, se borraba la marca y, al soltarla la
     * ventana del SERVIDOR, otra tarjeta de la MISMA cuenta entraba. Ahora la marca se queda sin gastar turno y frena la tarjeta HASTA EL
     * TECHO ([PaymentAttemptEntity.TECHO_DE_LA_DUDA_DEL_POS_MS], Codex final r6, 20-nov). Ajustada por el techo: antes exigía «sigue frenada tras
     * siete respuestas 05AP» (sin límite); ahora pasado el techo la tarjeta entra sola y, si AngelPay la aprueba después, se registra una
     * venta con la llave del intento por el camino de una fila soltada por la ventana.
     */
    @Test fun `N86_venta_en_05AP_frena_su_tarjeta_hasta_el_techo_y_si_se_aprueba_despues_se_registra_una_vez`() = runBlocking<Unit> {
        dudaN86("a1", solicitud = "r-1", creadoEn = reciente)
        var estado = "rejected"   // «05AP DECLINADA»
        val v = historialReal { estado }
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, System.currentTimeMillis())
        ventana("a1")   // el SERVIDOR la suelta: no le llegó el aviso del procesador
        assertWithMessage("antes del techo, la tarjeta de A sigue frenada").that(otraTarjetaDeA(libreta())).isFalse()
        assertWithMessage("05AP no gasta turno").that(consultasGastadas("a1")).isEqualTo(0)
        envejecer("a1", PaymentAttemptEntity.TECHO_DE_LA_DUDA_DEL_POS_MS)
        assertWithMessage("pasado el techo, la tarjeta de A entra").that(otraTarjetaDeA(libreta())).isTrue()

        estado = "APROBADA"   // la respuesta del banco llegó tarde
        val t = System.currentTimeMillis() + 3_600_000
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t)
        dineroEscrito("a1")
        assertWithMessage("la ventana conserva su motivo (REGISTRABLE_TRAS_LA_VENTANA)").that(dao.getById("a1")!!.state).isEqualTo("DESCARTADA")
        assertWithMessage("el dinero vuelve a proteger la cuenta").that(otraTarjetaDeA(libreta())).isFalse()
        val servidor = Servidor()
        registrador(servidor).recover(venue, t + 3_600_000)
        registrador(servidor).recover(venue, t + 7_200_000)
        assertWithMessage("una venta, con la llave del intento").that(servidor.pagos).containsExactly("a1", 1)
    }

    // ═══════ Rediseño tras Codex r7 (20-nov): el freno mira estado, edad y firma; el seguimiento del aprobado tardío no depende del freno ═══════

    /**
     * Codex final r6 #2 (la firma vetada para siempre) en lo que toca a la TPV: la ventana soltó la solicitud, el historial trae la venta en
     * `05AP` y NADIE puede firmar. Antes del techo la tarjeta de A espera (y es firmable); pasado el techo entra SÓLO por el reloj: en otro
     * proceso (la app se mató y se reabrió), sin otra consulta al historial, sin red y sin firma.
     */
    @Test fun `N86_techo_05AP_pasado_el_techo_la_tarjeta_entra_sola_sin_consulta_ni_red_ni_firma`() = runBlocking<Unit> {
        dudaN86("a1", solicitud = "r-1", creadoEn = reciente)
        LedgerUnknownRecovery(dao, libreta(), historialReal { "rejected" }).recover(venue, System.currentTimeMillis())
        ventana("a1")
        assertWithMessage("antes del techo, frenada").that(otraTarjetaDeA(libreta())).isFalse()
        assertWithMessage("…y firmable por el gerente, como hoy")
            .that(libreta().renglonPorRevisar(venue, CobroDeclarable.CLASE_INTENTO, "a1")?.declarable).isTrue()

        val consultas = consultasAlHistorial
        envejecer("a1", PaymentAttemptEntity.TECHO_DE_LA_DUDA_DEL_POS_MS)
        val reabierta = libreta()
        assertWithMessage("pasado el techo, sólo por el reloj: entra").that(otraTarjetaDeA(reabierta)).isTrue()
        assertWithMessage("la lectura antes de encender el lector tampoco la frena").that(reabierta.otraCercaDeLaTarjeta("orden-A", "b-x")).isFalse()
        assertWithMessage("sin otra consulta al historial").that(consultasAlHistorial).isEqualTo(consultas)
        assertWithMessage("y ya no se ofrece firmarla").that(reabierta.renglonPorRevisar(venue, CobroDeclarable.CLASE_INTENTO, "a1")).isNull()
    }

    /**
     * El aprobado que llega por el SERVIDOR después del techo (el aviso de AngelPay creó el Payment): la TPV lo guarda y no registra una
     * segunda venta.
     */
    @Test fun `N86_techo_aprobado_por_el_servidor_despues_del_techo_no_se_registra_dos_veces`() = runBlocking<Unit> {
        dudaN86("a1", solicitud = "r-1", creadoEn = reciente)
        LedgerUnknownRecovery(dao, libreta(), historialReal { "rejected" }).recover(venue, System.currentTimeMillis())
        ventana("a1"); envejecer("a1", PaymentAttemptEntity.TECHO_DE_LA_DUDA_DEL_POS_MS)
        assertThat(otraTarjetaDeA(libreta())).isTrue()
        libreta().aplicarVeredictoDelServidor(
            VeredictoDeIntento(venue, "a1", "r-1", com.jaac.avoqado_tpv.features.payment.domain.model.VeredictoDelServidor.RECORDED,
                "pay-a1", "webhook", 10_000, 0, "pay-a1", VeredictoDeIntento.Fuente.CONSULTA_S6),
        )
        assertThat(dao.getById("a1")!!.serverPaymentId).isEqualTo("pay-a1")
        val servidor = Servidor()
        registrador(servidor).recover(venue, System.currentTimeMillis() + 3_600_000)
        assertWithMessage("el Payment es el del SERVIDOR: la TPV no registra otro").that(servidor.pagos).isEmpty()
    }

    /**
     * 🔴 Codex final r7 #1 · POS → N86: AngelPay contesta `05AP`, la ventana del SERVIDOR suelta la solicitud y se pierde el único aviso de
     * AngelPay. Con el techo, otra respuesta `05AP` pasado el techo borraba la marca y la fila soltada salía de TODA consulta: si después se
     * aprobaba, cero Payment. El seguimiento ya no depende del freno: «sin aprobar» no gasta turno durante 7 días; la fila sigue en la selección
     * (al terminar su pausa y a las 24 h) y a las 30 h, aprobada, se registra una vez. Pasados los 7 días, «sin aprobar» gasta turno y se acaba.
     */
    @Test fun `N86_r7_1_sin_aprobar_soltada_por_la_ventana_se_sigue_consultando_7_dias_y_si_se_aprueba_se_registra`() = runBlocking<Unit> {
        val c = System.currentTimeMillis() - 150_000
        dudaN86("a1", solicitud = "r-1", creadoEn = c)
        var estado = "rejected"
        val v = historialReal { estado }
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, System.currentTimeMillis())
        ventana("a1")
        for (t in listOf(c + 8 * 60_000L, c + 24 * 3_600_000L)) {
            assertWithMessage("sigue en la selección").that(dao.getUnknownRecoveryCandidates(venue, t, t).map { it.attemptId }).contains("a1")
            val antes = consultasAlHistorial
            LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t)
            assertThat(consultasAlHistorial - antes).isEqualTo(1)
            assertWithMessage("«sin aprobar» no gasta turno").that(consultasGastadas("a1")).isEqualTo(0)
            assertWithMessage("…y la marca se queda").that(dao.getById("a1")!!.historyReadAt).isNotNull()
        }
        estado = "APROBADA"
        val t30 = c + 30 * 3_600_000L
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t30)
        dineroEscrito("a1")
        val servidor = Servidor()
        registrador(servidor).recover(venue, t30 + 3_600_000)
        registrador(servidor).recover(venue, t30 + 7_200_000)
        assertWithMessage("una venta, con la llave del intento").that(servidor.pagos).containsExactly("a1", 1)

        // Pasados los 7 días: «sin aprobar» gasta turno y, con los cinco gastados, se acaba. (Ajustada por R9-3, Codex r9 «Igual 3»: la
        // soltada por la ventana sigue entre las candidatas sin la marca, así que gasta sus cinco turnos; antes salía con el primero.)
        estado = "rejected"
        dudaN86("d1", orden = "orden-D", solicitud = "r-2", creadoEn = c)
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, System.currentTimeMillis())
        ventana("d1")
        var t = c + LedgerUnknownRecovery.SEGUIMIENTO_DEL_HISTORIAL_MS + 3_600_000
        val antes = consultasAlHistorial
        repeat(8) { LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t); t += 6 * 3_600_000L }
        assertWithMessage("cinco consultas que gastan turno, y se acaba").that(consultasAlHistorial - antes).isEqualTo(5)
        assertThat(consultasGastadas("d1")).isEqualTo(5)
        assertThat(dao.getById("d1")!!.historyReadAt).isNull()
    }

    /**
     * 🔴 Codex final r7 #4 · el freno no mira la reserva de la lectura: con la reserva viva o vencida (un ajuste del reloj la hacía ver
     * vencida con la consulta corriendo) da lo mismo: antes del techo frena, pasado el techo entra.
     */
    @Test fun `N86_r7_4_el_freno_no_mira_la_reserva_de_la_lectura`() = runBlocking<Unit> {
        for (id in listOf("viva", "vencida")) {
            dudaN86(id, orden = "orden-$id", solicitud = "r-$id", creadoEn = reciente)
            assertThat(libreta().despacharLecturaDelHistorial(id)).isNotNull()   // lectura despachada, reserva de 5 min
            ventana(id)
        }
        vencerLaReserva("vencida")
        for (id in listOf("viva", "vencida")) assertWithMessage("$id: antes del techo, frena").that(otraTarjeta(libreta(), "orden-$id")).isFalse()
        for (id in listOf("viva", "vencida")) envejecer(id, PaymentAttemptEntity.TECHO_DE_LA_DUDA_DEL_POS_MS)
        for (id in listOf("viva", "vencida")) assertWithMessage("$id: pasado el techo, entra").that(otraTarjeta(libreta(), "orden-$id")).isTrue()
    }

    /**
     * 🔴 Codex final r7 #5 · la línea de tiempo de Codex: la pantalla consulta a 1:30, falla a 1:50 (deja la lectura despachada con su reserva
     * hasta 6:30) y la ventana suelta a 2:20. A 5:01 la tarjeta entra. Y con la firma válida de un gerente antes del techo, entra de inmediato.
     */
    @Test fun `N86_r7_5_la_consulta_que_fallo_en_la_pantalla_no_frena_pasado_el_techo_y_la_firma_suelta`() = runBlocking<Unit> {
        val ahora = System.currentTimeMillis()
        val a = ahora - 301_000
        dudaN86("a1", solicitud = "r-1", creadoEn = a)
        assertThat(libreta().despacharLecturaDelHistorial("a1", now = a + 90_000)).isNotNull()
        ventana("a1")
        assertWithMessage("a 5:01, entra").that(otraTarjetaDeA(libreta())).isTrue()

        val b = ahora - 150_000
        dudaN86("b1", orden = "orden-B", solicitud = "r-2", creadoEn = b)
        assertThat(libreta().despacharLecturaDelHistorial("b1", now = b + 90_000)).isNotNull()
        ventana("b1")
        assertWithMessage("sin firma, antes del techo: frena").that(otraTarjeta(libreta(), "orden-B")).isFalse()
        firmar("b1")
        assertWithMessage("con la firma de un gerente: entra de inmediato").that(otraTarjeta(libreta(), "orden-B")).isTrue()
    }

    private val falla: suspend () -> VerificacionDelCobro = { VerificacionDelCobro.NoSePudoVerificar("credenciales inválidas") }

    /** La reserva de la lectura venció (el proceso que la tomó murió hace rato, o el reloj se corrió). */
    private fun vencerLaReserva(id: String) =
        sql("UPDATE payment_attempts SET lease_until = ${System.currentTimeMillis() - 1_000} WHERE attempt_id = '$id'")

    /**
     * C3 · POS → N86: la ventana soltó la solicitud y el historial de AngelPay falla SIEMPRE (credenciales). Antes del techo frena; pasado el
     * techo entra sola. La verificación NO se rinde: cada barrido vuelve a preguntar SIN gastar turno (un fallo de red no es «no aparece»), y
     * cuando el historial por fin contesta aprobada, una venta.
     */
    @Test fun `N86_C3_fallo_tecnico_persistente_frena_hasta_el_techo_y_la_verificacion_sigue_sin_gastar_turno`() = runBlocking<Unit> {
        dudaN86("a1", solicitud = "r-1", creadoEn = reciente)
        val (v, consultas) = verificador(falla, falla, falla, falla, falla, falla, cobrado)
        var t = System.currentTimeMillis()
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t)
        ventana("a1")
        repeat(2) { t += 3_600_000; LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t) }
        assertThat(consultas()).isEqualTo(3)
        assertWithMessage("antes del techo, frenada").that(otraTarjetaDeA(libreta())).isFalse()
        assertWithMessage("el fallo técnico no gasta turno").that(consultasGastadas("a1")).isEqualTo(0)

        envejecer("a1", PaymentAttemptEntity.TECHO_DE_LA_DUDA_DEL_POS_MS)
        assertWithMessage("pasado el techo, sólo por el reloj: entra").that(otraTarjetaDeA(libreta())).isTrue()
        repeat(3) { t += 6 * 3_600_000L; LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t) }
        assertWithMessage("la verificación sigue preguntando").that(consultas()).isEqualTo(6)
        assertWithMessage("…sin gastar turno").that(consultasGastadas("a1")).isEqualTo(0)
        assertWithMessage("…y la tarjeta sigue entrando").that(otraTarjetaDeA(libreta())).isTrue()

        t += 6 * 3_600_000L
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t)   // por fin: aprobada
        dineroEscrito("a1")
        assertWithMessage("el dinero vuelve a proteger la cuenta").that(otraTarjetaDeA(libreta())).isFalse()
        val servidor = Servidor()
        registrador(servidor).recover(venue, t + 3_600_000)
        registrador(servidor).recover(venue, t + 7_200_000)
        assertWithMessage("una venta, con la llave del intento").that(servidor.pagos).containsExactly("a1", 1)
    }

    /** C3 · la lectura murió sin respuesta (la app se mató a media consulta) y se reabre: frena hasta el techo; después entra sola. */
    @Test fun `N86_C3_lectura_que_murio_sin_respuesta_frena_hasta_el_techo_y_tras_reabrir_entra_sola`() = runBlocking<Unit> {
        dudaN86("a1", solicitud = "r-1", creadoEn = reciente)
        runCatching { LedgerUnknownRecovery(dao, libreta(), verificador(muere).first).recover(venue, System.currentTimeMillis()) }
        ventana("a1")
        assertWithMessage("antes del techo, con la reserva viva: frenada").that(otraTarjetaDeA(libreta())).isFalse()
        vencerLaReserva("a1")
        assertWithMessage("antes del techo, muerta: frenada").that(otraTarjetaDeA(libreta())).isFalse()
        envejecer("a1", PaymentAttemptEntity.TECHO_DE_LA_DUDA_DEL_POS_MS)
        assertWithMessage("reabierta y pasado el techo: entra sola").that(otraTarjetaDeA(libreta())).isTrue()
        val (v, consultas) = verificador(cobrado)
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, System.currentTimeMillis())
        assertWithMessage("la consulta que murió se retoma").that(consultas()).isEqualTo(1)
        dineroEscrito("a1")
    }

    /**
     * C3 · la firma siempre suelta: con la fila cerrada por una persona, la lectura pendiente (aquí, con fallo técnico) deja de frenar la
     * tarjeta de su cuenta de inmediato (antes del techo). La consulta sigue intentando verificar, sin gastar turno. La duda de una venta
     * LOCAL sin firmar sigue esperando al gerente.
     */
    @Test fun `N86_C3_firmada_y_el_historial_falla_entra_de_inmediato`() = runBlocking<Unit> {
        dudaN86("a1", creadoEn = reciente); firmar("a1")             // firmada antes de la consulta
        dudaN86("b1", orden = "orden-B", creadoEn = reciente)        // firmada después del fallo
        val (v, consultas) = verificador(falla)
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, System.currentTimeMillis())
        assertThat(consultas()).isEqualTo(2)
        assertWithMessage("A: firmada y el historial falla: entra").that(otraTarjetaDeA(libreta())).isTrue()
        assertWithMessage("B: la duda sigue esperando al gerente").that(otraTarjeta(libreta(), "orden-B")).isFalse()
        firmar("b1")
        assertWithMessage("B: con la firma, entra").that(otraTarjeta(libreta(), "orden-B")).isTrue()
        assertThat(dao.getById("a1")!!.historyReadAt).isNotNull()
        assertThat(consultasGastadas("a1")).isEqualTo(0)
    }

    /**
     * Codex plan A #2 · la salida (c): la venta sigue en `05AP` y el gerente firma «no se cobró» en «Por revisar». Reescrita por el rediseño
     * de Codex r7: la firma de una persona suelta la tarjeta DE INMEDIATO (antes esperaba la siguiente respuesta). Y por Codex final r8 #3:
     * la firma no acorta el seguimiento: la siguiente respuesta «sin aprobar» no gasta turno y la marca se queda (antes contaba como «no
     * aparece»); la tarjeta sigue entrando.
     */
    @Test fun `N86_venta_en_05AP_firmada_por_el_gerente_suelta_su_tarjeta_de_inmediato`() = runBlocking<Unit> {
        dudaN86("a1", creadoEn = reciente)
        val v = historialReal { "rejected" }
        var t = System.currentTimeMillis()
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t)
        assertWithMessage("la duda espera al gerente").that(otraTarjetaDeA(libreta())).isFalse()
        firmar("a1")
        assertWithMessage("con la firma, entra de inmediato").that(otraTarjetaDeA(libreta())).isTrue()
        t += 3_600_000
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t)
        assertWithMessage("firmada, «sin aprobar» tampoco gasta turno (Codex r8 #3)").that(consultasGastadas("a1")).isEqualTo(0)
        assertWithMessage("…y se sigue buscando").that(dao.getById("a1")!!.historyReadAt).isNotNull()
        assertThat(otraTarjetaDeA(libreta())).isTrue()
    }

    // ═══════════════ R9-3 · Codex final r9 «Igual 3»: «no aparece» no acorta el seguimiento (el freno no cambia) ═══════════════

    /**
     * 🔴 Codex final r9 «Igual 3» · POS → N86: el historial de AngelPay tarda en mostrar el cobro. La pantalla pregunta a los 30 s y no aparece;
     * la ventana suelta la solicitud. Antes la fila salía de las consultas (soltada por la ventana y sin marca) y, si el cobro aparecía aprobado
     * después con el aviso perdido, cero Payment. Ahora se sigue preguntando en segundo plano a las 6 h y a las 12 h sin gastar turno y, aprobada
     * a las 30 h, se registra una vez. El FRENO no cambia: tras el primer «no aparece» la tarjeta de la cuenta ya entra.
     */
    @Test fun `N86_r9_3_no_aparece_no_acorta_el_seguimiento_y_aprobada_a_las_30_h_se_registra`() = runBlocking<Unit> {
        val c = System.currentTimeMillis() - 150_000
        dudaN86("a1", solicitud = "r-1", creadoEn = c)
        var estado: String? = null   // el historial todavía no la trae
        val v = historialReal { estado }
        val pantalla = libreta()
        val marca = pantalla.despacharLecturaDelHistorial("a1", now = c + 30_000)!!
        pantalla.terminarLecturaDelHistorial("a1", marca)   // a los 30 s: no aparece
        ventana("a1")
        assertWithMessage("tras el primer vacío la tarjeta de la cuenta entra (no espera)").that(otraTarjetaDeA(libreta())).isTrue()
        for (h in listOf(6L, 12L)) {
            val t = c + h * 3_600_000L
            assertWithMessage("a las $h h sigue entre las candidatas").that(dao.getUnknownRecoveryCandidates(venue, t, t).map { it.attemptId }).contains("a1")
            val antes = consultasAlHistorial
            LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t)
            assertWithMessage("a las $h h: una consulta").that(consultasAlHistorial - antes).isEqualTo(1)
            assertWithMessage("«no aparece» no gasta turno").that(consultasGastadas("a1")).isEqualTo(0)
            assertWithMessage("…y no deja nada que frene").that(dao.getById("a1")!!.historyReadAt).isNull()
            assertThat(otraTarjetaDeA(libreta())).isTrue()
        }
        estado = "APROBADA"
        val t30 = c + 30 * 3_600_000L
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t30)
        dineroEscrito("a1")
        val servidor = Servidor()
        registrador(servidor).recover(venue, t30 + 3_600_000)
        registrador(servidor).recover(venue, t30 + 7_200_000)
        assertWithMessage("una venta, con la llave del intento").that(servidor.pagos).containsExactly("a1", 1)
    }

    /** R9-3 · pasados los 7 días, «no aparece» gasta turno y, con los cinco gastados, se deja de preguntar. */
    @Test fun `N86_r9_3_pasados_7_dias_no_aparece_gasta_turno_y_se_acaba`() = runBlocking<Unit> {
        dudaN86("d1", creadoEn = System.currentTimeMillis() - LedgerUnknownRecovery.SEGUIMIENTO_DEL_HISTORIAL_MS - 3_600_000L)
        val v = historialReal { null }
        var t = System.currentTimeMillis()
        repeat(8) { LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t); t += 6 * 3_600_000L }
        assertThat(consultasAlHistorial).isEqualTo(5)
        assertThat(consultasGastadas("d1")).isEqualTo(5)
    }

    /**
     * R9-3 · el seguimiento no martilla y no deja a nadie sin turno: 60 ventas soltadas por la ventana con «no aparece». Cada pasada inicia a
     * lo más [LedgerUnknownRecovery.CONSULTAS_POR_CORRIDA]; a una fila no se le vuelve a preguntar antes de su pausa; y el orden es el último
     * toque: las 10 que quedaron detrás vuelven a tocar ANTES que las 50 de la primera pasada (por `created_at` se quedaban sin turno).
     */
    @Test fun `N86_r9_3_el_seguimiento_va_por_ultimo_toque_y_esta_acotado_por_pasada`() = runBlocking<Unit> {
        repeat(60) { i -> dudaN86("s%02d".format(i), orden = "orden-$i", solicitud = "r-$i", creadoEn = reciente); ventana("s%02d".format(i)) }
        val consultadas = mutableListOf<String>()
        val v = mockk<AngelPayChargeVerifier>()
        coEvery { v.verificar(any(), any(), any(), any(), any(), any()) } coAnswers { consultadas += firstArg<String>(); VerificacionDelCobro.NoCobrado }
        val t = System.currentTimeMillis()
        val tope = LedgerUnknownRecovery.CONSULTAS_POR_CORRIDA
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t)
        assertWithMessage("una pasada: a lo más $tope").that(consultadas).hasSize(tope)
        val atras = (0 until 60).map { "s%02d".format(it) } - consultadas.toSet()
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t + 60_000)
        assertWithMessage("un minuto después: sólo las 10 que no alcanzaron, ninguna repetida").that(consultadas).hasSize(60)
        assertThat(consultadas.toSet()).hasSize(60)
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t + 6 * 3_600_000L)
        assertWithMessage("a las 6 h, otra vez a lo más $tope").that(consultadas).hasSize(60 + tope)
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t + 12 * 3_600_000L)
        for (id in atras) assertWithMessage("$id: por último toque vuelve a tocar").that(consultadas.count { it == id }).isAtLeast(2)
        assertWithMessage("sin gastar turnos").that(consultasGastadas("s00")).isEqualTo(0)
    }

    /**
     * 🔴 Codex final r6 #3 · POS → N86: el intento se abrió el 31-ene a las 23:59:55 y AngelPay registró la venta el 1-feb a las 00:00:05.
     * La recuperación le pasaba al historial la fecha de apertura como «hoy» y buscaba [30-ene, 31-ene]: nunca la encontraba, gastaba sus
     * consultas y, perdido el único aviso de AngelPay, el cliente pagaba con cero Payment. Ahora busca también el día siguiente.
     */
    @Test fun `N86_medianoche_el_aprobado_que_AngelPay_registra_el_dia_siguiente_se_encuentra_y_se_registra`() = runBlocking<Unit> {
        val apertura = java.time.ZonedDateTime.of(2026, 1, 31, 23, 59, 55, 0, com.jaac.avoqado_tpv.core.util.VenueTimeZone.MEXICO)
            .toInstant().toEpochMilli()
        dudaN86("a1", solicitud = "r-1", creadoEn = apertura)
        ventana("a1")
        firmar("a1")   // el gerente firmó tras la ventana: la recuperación sigue preguntando
        val adapter = mockk<PaymentPostOperationsAdapter>()
        coEvery { adapter.getTransactionHistory(any()) } answers {
            val q = firstArg<TransactionHistoryQuery>()
            // AngelPay la tiene con fecha del 1-feb: sólo aparece si la consulta cubre ese día.
            Result.success(if (q.startDate <= "2026-02-01" && q.endDate >= "2026-02-01") listOf(ventaEnElHistorial(q.integratorReference!!, "APROBADA")) else emptyList())
        }
        val fabrica = mockk<PostOperationsAdapterFactory>()
        every { fabrica.get(ProcessorType.ANGELPAY) } returns adapter
        LedgerUnknownRecovery(dao, libreta(), AngelPayChargeVerifier(fabrica)).recover(venue, System.currentTimeMillis())
        dineroEscrito("a1")
        val servidor = Servidor()
        registrador(servidor).recover(venue, System.currentTimeMillis() + 3_600_000)
        assertWithMessage("una venta, con la llave del intento").that(servidor.pagos).containsExactly("a1", 1)
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

package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.angelpay.angelpaysdk.AngelPaySDK
import com.angelpay.angelpaysdk.models.TransactionHistoryResult
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.gson.Gson
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayChargeVerifier
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPaySdkPostOperationsAdapter
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.VerificacionDelCobro
import com.jaac.avoqado_tpv.features.payment.data.repository.FastPaymentRecorder
import com.jaac.avoqado_tpv.features.payment.data.repository.OrderPaymentRecorder
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt
import com.jaac.avoqado_tpv.features.payment.domain.model.VeredictoDelServidor
import com.jaac.avoqado_tpv.features.payment.domain.processor.PaymentPostOperationsAdapter
import com.jaac.avoqado_tpv.features.payment.domain.processor.PostOperationsAdapterFactory
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import com.jaac.avoqado_tpv.features.payment.domain.processor.TransactionHistoryQuery
import com.jaac.avoqado_tpv.features.payment.domain.processor.UnifiedTransaction
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

/**
 * 🔴 F1 · Codex final r8 (20-nov, `codex-final-r8-tpv.md`) en la TPV Nexgo N86: los casos donde F1 quedaba PEOR que producción (r8 #5,
 * #6, #7 y la mitad «(f)» del #1) y dos baratos (#3, #4). Cada prueba es el caso de Codex, sobre los DAO y la libreta REALES en una SQLite
 * en memoria; sólo el historial de AngelPay, el SERVIDOR y el reloj son de mentira. Como en `AprobadoFinalN86RoomTest`, el registrador
 * simulado CONSERVA lo que aceptó (un `Payment` por llave): se cuentan pagos por llave, no llamadas.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class CodexR8RecuperacionRoomTest {
    private val venue = "venue-1"
    private lateinit var db: AvoqadoDatabase
    private lateinit var dao: PaymentAttemptDao

    @Before fun abrir() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.paymentAttemptDao()
    }

    @After fun cerrar() {
        unmockkAll()
        db.close()
    }

    /** Un proceso nuevo de la app: libreta con otro token, sin memoria. La base es la misma. */
    private fun libreta() = PaymentAttemptLedger(dao, mockk(relaxed = true))

    /** El SERVIDOR simulado: un `Payment` por llave (su índice único por `idempotencyKey`). */
    private class Servidor {
        val pagos = mutableMapOf<String, Int>()
        val fallanSiempre = mutableSetOf<String>()
        fun registrar(ctx: PaymentContext): Result<PaymentReceipt> {
            val llave = ctx.idempotencyKey!!
            if (llave in fallanSiempre) return Result.failure(java.io.IOException("503"))
            pagos[llave] = 1
            return Result.success(PaymentReceipt("pay-$llave", "url", "key", ctx.amount, ctx.tip))
        }
    }

    private fun registrador(servidor: Servidor, puedeRegistrar: () -> Boolean = { true }, alRegistrar: () -> Unit = {}): LedgerApprovalRecovery {
        val order = mockk<OrderPaymentRecorder>()
        coEvery { order.recordPayment(any(), any(), any(), any()) } answers { alRegistrar(); servidor.registrar(firstArg()) }
        val fast = mockk<FastPaymentRecorder>()
        coEvery { fast.recordPayment(any(), any(), any(), any()) } answers { alRegistrar(); servidor.registrar(firstArg()) }
        return LedgerApprovalRecovery(dao, fast, order, puedeRegistrar)
    }

    private fun contexto(id: String, orden: String): String = Gson().toJsonTree(
        PaymentContext.AngelPayPayment(
            venueId = venue, staffId = "cajera", amount = BigDecimal("100.00"), tip = BigDecimal.ZERO, merchantAccountId = "merchant",
            deviceSerialNumber = "terminal", idempotencyKey = id, orderId = orden,
        ),
    ).asJsonObject.apply { addProperty("processorAffiliation", "afiliacion") }.toString()

    /** Un intento abierto hace 2.5 min: la recuperación ya lo toma (más de 2 min) y todavía no llega al techo (5 min). */
    private val reciente get() = System.currentTimeMillis() - 150_000

    /** POS → N86 (con [solicitud]) o TPV SOLA (sin ella): el cobro de $100 de la cuenta [orden] quedó en duda. */
    private suspend fun dudaN86(id: String, orden: String = "orden-A", solicitud: String? = "r-1", creadoEn: Long = reciente) {
        val json = if (solicitud == null) contexto(id, orden)
            else com.google.gson.JsonParser.parseString(contexto(id, orden)).asJsonObject.apply { addProperty("terminalPaymentRequestId", solicitud) }.toString()
        dao.insert(
            PaymentAttemptEntity(
                attemptId = id, venueId = venue, processor = "ANGELPAY", state = "INDETERMINADO", amountCents = 10_000, tipCents = 0,
                recordingRoute = "ORDER", paymentContextJson = json, terminalPaymentRequestId = solicitud, serverAnsweredAt = 1L,
                createdAt = creadoEn, updatedAt = creadoEn,
            ),
        )
    }

    /** Un aprobado de AngelPay con todo lo necesario para registrarlo (referencia, autorización, contexto), sin `Payment`. */
    private suspend fun aprobado(id: String, creadoEn: Long, intentos: Int = 0, reservaHasta: Long? = null) = dao.insert(
        PaymentAttemptEntity(
            attemptId = id, venueId = venue, processor = "ANGELPAY", state = "REGISTRO_FALLIDO", amountCents = 10_000, tipCents = 0,
            recordingRoute = "ORDER", paymentContextJson = contexto(id, "orden-$id"), hostApproved = true, authCode = "AUTH",
            referenceNumber = "REF", verifyAttempts = intentos, leaseUntil = reservaHasta, createdAt = creadoEn, updatedAt = creadoEn,
        ),
    )

    private fun sql(s: String) = db.openHelper.writableDatabase.execSQL(s)

    /** La ventana automática del SERVIDOR (30 s sin evidencia) liberó la fila. */
    private fun ventana(id: String) = sql(
        "UPDATE payment_attempts SET state = 'DESCARTADA', server_outcome = 'RELEASED_NO_EVIDENCE', " +
            "last_error = 'liberada_por_el_servidor:NO_EVIDENCE_AFTER_WINDOW' WHERE attempt_id = '$id'",
    )

    /** Y encima firmó un gerente con su PIN: la liberación de una PERSONA (como la deja `cerrarPorLiberacionDelServidor`). */
    private fun firmaTrasLaVentana(id: String) = sql(
        "UPDATE payment_attempts SET server_outcome = 'OPERATOR_NO_INSTRUMENT', " +
            "last_error = 'liberada_por_el_servidor:OPERATOR_RECONCILED' WHERE attempt_id = '$id'",
    )

    private suspend fun otraTarjeta(l: PaymentAttemptLedger, orden: String = "orden-A"): Boolean {
        val id = "b-${System.nanoTime()}"
        val entro = l.openAttempt(id, venue, "ANGELPAY", 10_000, 0, "ORDER", """{"orderId":"$orden"}""")
        if (entro) l.markDiscardedBeforeCharge(id, "fin de la prueba")
        return entro
    }

    private fun consultasGastadas(id: String): Int =
        db.openHelper.readableDatabase.query("SELECT history_checks FROM payment_attempts WHERE attempt_id = '$id'").use {
            it.moveToFirst(); it.getInt(0)
        }

    private var consultasAlHistorial = 0

    /** El verificador REAL frente a un historial de AngelPay simulado: la venta de ESTE intento con [estado] (null = no aparece). */
    private fun historialReal(estado: () -> String?): AngelPayChargeVerifier {
        val adapter = mockk<PaymentPostOperationsAdapter>()
        coEvery { adapter.getTransactionHistory(any()) } answers {
            consultasAlHistorial++
            val id = firstArg<TransactionHistoryQuery>().integratorReference!!
            Result.success(listOfNotNull(estado()?.let { ventaEnElHistorial(id, it) }))
        }
        return verificadorCon(adapter)
    }

    private fun verificadorCon(adapter: PaymentPostOperationsAdapter): AngelPayChargeVerifier {
        val fabrica = mockk<PostOperationsAdapterFactory>()
        every { fabrica.get(ProcessorType.ANGELPAY) } returns adapter
        return AngelPayChargeVerifier(fabrica)
    }

    private fun ventaEnElHistorial(id: String, estado: String) = UnifiedTransaction(
        processorType = ProcessorType.ANGELPAY, reference = "REF", amount = 100.0,
        authorizationCode = if (estado == "APROBADA") "AUTH" else "", status = estado, cardType = null, last4 = null,
        cardBin = "411111", entryMode = "CHIP", date = null, time = null, operationType = "VENTA", merchantName = null,
        affiliation = "afiliacion", terminal = "terminal", folio = "1", waiter = null, tip = null, issuingBank = null, cardMethod = null,
        creationDate = "", aid = null, arqc = null, postOperationType = null, postOperationReference = null,
        postOperationAuthorization = null, postOperationStatus = null, integratorReference = id,
    )

    // ═══════════════════════════════ A · r8 #5: el impedimento del 409 y el RECORDED del MISMO intento ═══════════════════════════════

    /**
     * 🔴 Codex final r8 #5 · POS → N86: cuenta de $200, abono de $100 con tarjeta. El gerente intenta firmar justo cuando Avoqado recibe la
     * evidencia positiva: `409 POSITIVE_EVIDENCE_EXISTS` y la terminal guarda el impedimento genérico. Después llega `RECORDED` de EL MISMO
     * intento, el mismo `Payment`, $100 exactos y propina cero. El impedimento impedía cerrar la fila y la tarjeta de esa cuenta quedaba
     * frenada incluso 30 días después: los $100 restantes no se podían cobrar con tarjeta. Ahora el Payment de ESTE intento resuelve el
     * impedimento y la fila queda registrada.
     */
    @Test fun `r8_5 A · el RECORDED de este intento resuelve el impedimento del 409 y el saldo restante se cobra`() = runBlocking<Unit> {
        dudaN86("a1")
        assertThat(libreta().marcarImpedimentoDelServidor(venue, "a1").getOrThrow()).isTrue()   // el 409 de la firma
        val recorded = VeredictoDeIntento(venue, "a1", "r-1", VeredictoDelServidor.RECORDED, "pay-a1", "webhook", 10_000, 0, "pay-a1",
            VeredictoDeIntento.Fuente.SOCKET_S5)
        libreta().aplicarVeredictoDelServidor(recorded).getOrThrow()
        libreta().aplicarVeredictoDelServidor(recorded.copy(fuente = VeredictoDeIntento.Fuente.CONSULTA_S6)).getOrThrow()   // volver a consultar
        val fila = dao.getById("a1")!!
        assertWithMessage("el abono quedó registrado").that(fila.state).isEqualTo("REGISTRADO")
        assertWithMessage("el impedimento genérico quedó resuelto").that(fila.serverVeto).isNull()
        assertWithMessage("el saldo de $100 se puede cobrar con tarjeta").that(otraTarjeta(libreta())).isTrue()
        sql("UPDATE payment_attempts SET created_at = created_at - ${30L * 86_400_000L} WHERE attempt_id = 'a1'")
        assertWithMessage("…también 30 días después").that(otraTarjeta(libreta())).isTrue()
    }

    /** El impedimento es genérico a propósito: con otro importe, o con un veto propio en la misma respuesta, se conserva y sigue frenando. */
    @Test fun `r8_5 A · con otro importe o con un veto propio el impedimento se conserva`() = runBlocking<Unit> {
        dudaN86("otro-importe", orden = "orden-1")
        dudaN86("veto-propio", orden = "orden-2", solicitud = "r-2")
        for (id in listOf("otro-importe", "veto-propio")) assertThat(libreta().marcarImpedimentoDelServidor(venue, id).getOrThrow()).isTrue()
        libreta().aplicarVeredictoDelServidor(VeredictoDeIntento(venue, "otro-importe", "r-1", VeredictoDelServidor.RECORDED, "pay-1",
            "webhook", 5_000, 0, "pay-1", VeredictoDeIntento.Fuente.CONSULTA_S6)).getOrThrow()
        libreta().aplicarVeredictoDelServidor(VeredictoDeIntento(venue, "veto-propio", "r-2", VeredictoDelServidor.RECORDED, "pay-2",
            "webhook", 10_000, 0, "pay-2", VeredictoDeIntento.Fuente.CONSULTA_S6,
            veto = PaymentAttemptEntity.VETO_PAYMENT_CONTRADICTION)).getOrThrow()
        assertThat(dao.getById("otro-importe")!!.serverVeto).isEqualTo(PaymentAttemptEntity.VETO_IMPEDIMENTO_DEL_SERVIDOR)
        assertWithMessage("el veto propio reemplaza al genérico, nunca lo borra")
            .that(dao.getById("veto-propio")!!.serverVeto).isEqualTo(PaymentAttemptEntity.VETO_PAYMENT_CONTRADICTION)
        for ((id, orden) in listOf("otro-importe" to "orden-1", "veto-propio" to "orden-2")) {
            assertWithMessage(id).that(dao.getById(id)!!.state).isNotEqualTo("REGISTRADO")
            assertWithMessage("$id: su cuenta sigue frenada").that(otraTarjeta(libreta(), orden)).isFalse()
        }
    }

    // ═══════════════════════════ B · r8 #6 y r7 #2: el orden de la recuperación de aprobados, por el último toque ═══════════════════════════

    /**
     * 🔴 Codex final r8 #6 · N86: B ya lleva 4 intentos de registro (su problema se resolvió) y en cada reconexión hay un aprobado más nuevo,
     * ya elegible, y la conexión sólo alcanza para registrar uno. Con «primero los de menos intentos» el nuevo ganaba siempre: 60
     * reconexiones, 60 nuevos registrados y B con cero Payment. Por el último toque, B entra en cuanto nadie trae un toque más viejo que el
     * fin de su pausa: aquí, en la TERCERA reconexión (los dos primeros nuevos nacieron antes de que terminara su pausa).
     */
    @Test fun `r8_6 B · el aprobado con 4 intentos entra aunque cada reconexion traiga uno nuevo`() = runBlocking<Unit> {
        val finDeLaPausa = System.currentTimeMillis() + 10 * 86_400_000L
        aprobado("b", creadoEn = finDeLaPausa - 3 * 3_600_000L, intentos = 4, reservaHasta = finDeLaPausa)
        val servidor = Servidor()
        var permitidos = 0
        val recuperacion = registrador(servidor, puedeRegistrar = { permitidos > 0 }, alRegistrar = { permitidos-- })
        var reconexionDeB: Int? = null
        for (k in 1..60) {
            val ahora = finDeLaPausa + k * 60_000L
            aprobado("x$k", creadoEn = ahora - 150_000L)
            permitidos = 1   // la conexión alcanza para registrar uno
            recuperacion.recover(venue, ahora)
            if (reconexionDeB == null && "b" in servidor.pagos) reconexionDeB = k
        }
        assertWithMessage("B se registra en las primeras reconexiones, no «nunca»").that(reconexionDeB).isNotNull()
        assertThat(reconexionDeB!!).isAtMost(3)
        assertWithMessage("una sola venta de B").that(servidor.pagos["b"]).isEqualTo(1)
    }

    /**
     * Codex final r7 #2 (el otro sentido): 300 aprobados viejos cuyo registro falla siempre, con su pausa vencida en cada pasada (el peor
     * caso), y B nuevo. Por el último toque cada viejo pasa delante de B a lo más UNA vez (al reintentarlo queda detrás): B entra a más tardar
     * en la pasada ⌈300 / 50⌉ + 1 = 7.
     */
    @Test fun `r7_2 B · 300 aprobados viejos que fallan siempre - B entra en a lo mas 7 pasadas`() = runBlocking<Unit> {
        val t0 = System.currentTimeMillis() + 10 * 86_400_000L
        val servidor = Servidor()
        repeat(300) { i ->
            val id = "v%03d".format(i)
            servidor.fallanSiempre += id
            aprobado(id, creadoEn = t0 - 86_400_000L - i, intentos = 7)
        }
        aprobado("b", creadoEn = t0 - 150_000L)
        val recuperacion = registrador(servidor)
        var pasadaDeB: Int? = null
        for (k in 1..20) {
            recuperacion.recover(venue, t0 + k * 40 * 60_000L)   // 40 min: la pausa de 32 min de cada viejo ya venció
            if (pasadaDeB == null && "b" in servidor.pagos) pasadaDeB = k
        }
        assertWithMessage("B entra en un número acotado de pasadas").that(pasadaDeB).isNotNull()
        assertThat(pasadaDeB!!).isAtMost(7)
        assertThat(servidor.pagos).containsExactly("b", 1)
    }

    // ═══════════════════════ C · r8 #7: el dinero que ya está en la mano antes que las consultas lentas ═══════════════════════

    /**
     * 🔴 Codex final r8 #7 · N86: tras reiniciar, B conserva su aprobado y todo lo necesario para registrarlo (su aviso se perdió). Hay 80
     * dudas de otras cuentas y cada consulta al historial tarda 20 s en fallar. El trabajador consultaba primero, una por una, y Android lo
     * detenía a los 10 min antes de llegar a B: cero Payment en cada pasada. Ahora registra primero los aprobados y las consultas tienen un
     * presupuesto de tiempo para iniciarse: B se registra en la PRIMERA pasada y el trabajador no llega a los 10 min.
     */
    @Test fun `r8_7 C · ochenta dudas lentas no impiden registrar el aprobado en la primera pasada`() = runBlocking<Unit> {
        val viejo = System.currentTimeMillis() - 3_600_000L
        repeat(80) { i -> dudaN86("d$i", orden = "orden-d$i", solicitud = null, creadoEn = viejo) }
        aprobado("b", creadoEn = viejo)
        var reloj = 0L   // monotónico, en ms, desde que arrancó el trabajador
        val v = mockk<AngelPayChargeVerifier>()
        coEvery { v.verificar(any(), any(), any(), any(), any(), any()) } coAnswers {
            reloj += 20_000L
            if (reloj > 600_000L) throw CancellationException("Android detuvo el trabajo a los 10 min")
            VerificacionDelCobro.NoSePudoVerificar("sin respuesta en 20 s")
        }
        val servidor = Servidor()
        var detenido = false
        try {
            LedgerSweepLogic.recuperarElDinero(registrador(servidor), LedgerUnknownRecovery(dao, libreta(), v), venue,
                ahora = { System.currentTimeMillis() }, reloj = { reloj })
        } catch (e: CancellationException) {
            detenido = true
        }
        assertWithMessage("B se registra en la primera pasada").that(servidor.pagos).containsExactly("b", 1)
        assertWithMessage("las consultas no se comen el trabajo: Android no lo detiene").that(detenido).isFalse()
        assertThat(reloj).isLessThan(600_000L)
    }

    // ═══════════════ R9-1 · Codex final r9 «Peor 1»: las dos fases del trabajador con su propio presupuesto ═══════════════

    /** TPV · PAGO RÁPIDO: un cobro de $100 sin cuenta que quedó en duda (su contexto no trae `orderId`; se registra por la ruta FAST). */
    private suspend fun pagoRapidoEnDuda(id: String, creadoEn: Long) = dao.insert(
        PaymentAttemptEntity(
            attemptId = id, venueId = venue, processor = "ANGELPAY", state = "INDETERMINADO", amountCents = 10_000, tipCents = 0,
            recordingRoute = "FAST", paymentContextJson = Gson().toJsonTree(
                PaymentContext.AngelPayPayment(
                    venueId = venue, staffId = "cajera", amount = BigDecimal("100.00"), tip = BigDecimal.ZERO, merchantAccountId = "merchant",
                    deviceSerialNumber = "terminal", idempotencyKey = id,
                ),
            ).asJsonObject.apply { addProperty("processorAffiliation", "afiliacion") }.toString(),
            serverAnsweredAt = 1L, createdAt = creadoEn, updatedAt = creadoEn,
        ),
    )

    /**
     * 🔴 Codex final r9 «Peor 1» · TPV · PAGO RÁPIDO: AngelPay aprobó un Pago rápido, la pantalla perdió el resultado y el aviso del banco no
     * llegó; está en el historial. Hay 32 aprobados de otras cuentas cuyo registro tarda 20 s y falla. Registrando primero sin presupuesto,
     * Android detenía la pasada a los 10 min antes de consultar el historial: cero Payment en cada pasada (la base consultaba primero). Ahora
     * cada fase tiene su tiempo: se consulta y se registra el Pago rápido en la PRIMERA pasada, y la pasada termina antes de los 10 min.
     */
    @Test fun `r9_1 · 32 aprobados lentos que fallan no impiden consultar y registrar el Pago rapido en la primera pasada`() = runBlocking<Unit> {
        val viejo = System.currentTimeMillis() - 3_600_000L
        pagoRapidoEnDuda("pr", creadoEn = viejo - 60_000L)
        val servidor = Servidor()
        repeat(32) { i -> aprobado("a$i", creadoEn = viejo + i); servidor.fallanSiempre += "a$i" }
        var reloj = 0L   // monotónico, en ms, desde que arrancó el trabajador
        val recuperacion = registrador(servidor, alRegistrar = {
            reloj += 20_000L
            if (reloj > 600_000L) throw CancellationException("Android detuvo el trabajo a los 10 min")
        })
        var detenido = false
        try {
            LedgerSweepLogic.recuperarElDinero(recuperacion, LedgerUnknownRecovery(dao, libreta(), historialReal { "APROBADA" }), venue,
                ahora = { System.currentTimeMillis() }, reloj = { reloj })
        } catch (e: CancellationException) {
            detenido = true
        }
        assertWithMessage("se consultó el historial del Pago rápido").that(consultasAlHistorial).isEqualTo(1)
        assertWithMessage("…y se registró en la PRIMERA pasada").that(servidor.pagos).containsExactly("pr", 1)
        assertWithMessage("Android no detiene el trabajo").that(detenido).isFalse()
        assertThat(reloj).isLessThan(600_000L)
    }

    /** R9-1 · sin carga, la pasada hace lo de siempre: registra el aprobado, consulta la duda y registra lo que encontró. */
    @Test fun `r9_1 · sin carga la pasada registra, consulta y registra lo encontrado como siempre`() = runBlocking<Unit> {
        val viejo = System.currentTimeMillis() - 3_600_000L
        aprobado("a", creadoEn = viejo)
        pagoRapidoEnDuda("pr", creadoEn = viejo)
        val servidor = Servidor()
        LedgerSweepLogic.recuperarElDinero(registrador(servidor), LedgerUnknownRecovery(dao, libreta(), historialReal { "APROBADA" }), venue)
        assertThat(servidor.pagos).containsExactly("a", 1, "pr", 1)
    }

    // ═══════════════════════════ D · r8 #4: una consulta FALLIDA de AngelPay no es «no aparece» ═══════════════════════════

    /**
     * 🔴 Codex final r8 #4 · POS → N86: A ya apareció en `05AP` y la ventana del SERVIDOR soltó la solicitud. La siguiente consulta devuelve,
     * dentro de una respuesta normal del SDK 1.0.23, `status=false`, lista vacía y un mensaje de error. El adaptador sólo leía la lista: la
     * terminal gastaba un turno, borraba la marca y dejaba de buscar A. Ahora es un fallo técnico: no gasta turno ni suelta nada.
     */
    @org.junit.Ignore("D en espera: falta medir en la N86 cómo contesta AngelPay una búsqueda legítima sin ventas (encargo 27dd77)")
    @Test fun `r8_4 D · status false del SDK es un fallo tecnico - la fila sigue en las consultas con su marca`() = runBlocking<Unit> {
        dudaN86("a1")
        LedgerUnknownRecovery(dao, libreta(), historialReal { "rejected" }).recover(venue, System.currentTimeMillis())
        ventana("a1")
        val marca = dao.getById("a1")!!.historyReadAt
        assertThat(marca).isNotNull()

        mockkObject(AngelPaySDK)
        coEvery { AngelPaySDK.getTransactionHistory(any(), any(), any(), any(), any()) } returns
            Result.success(TransactionHistoryResult(false, emptyList(), "Error al consultar el historial"))
        val sdkReal = verificadorCon(AngelPaySdkPostOperationsAdapter(ticketBuilder = mockk(relaxed = true), sdkGateway = mockk(relaxed = true)))
        val t = System.currentTimeMillis() + 3_600_000L
        LedgerUnknownRecovery(dao, libreta(), sdkReal).recover(venue, t)

        val fila = dao.getById("a1")!!
        assertWithMessage("no gasta turno").that(consultasGastadas("a1")).isEqualTo(0)
        assertWithMessage("la marca sigue").that(fila.historyReadAt).isNotNull()
        val despues = t + 3_600_000L
        assertWithMessage("sigue en las consultas").that(dao.getUnknownRecoveryCandidates(venue, despues, despues).map { it.attemptId }).contains("a1")
    }

    // ═══════════════════════════ E · r8 #3: la firma no acorta los 7 días de seguimiento ═══════════════════════════

    /**
     * 🔴 Codex final r8 #3 · POS → N86: el gerente firma después de que la ventana suelta A. A las 6, 12, 18, 24 y 30 h AngelPay sigue
     * contestando «existe, sin aprobar»: cada respuesta gastaba un turno porque la fila estaba firmada y a las 30 h A salía de las consultas.
     * Si el banco la aprobaba a la hora 31 (con su aviso perdido): cero Payment. La firma sólo decide el freno, nunca el seguimiento.
     */
    @Test fun `r8_3 E · firmada y sin aprobar se sigue 7 dias - aprobada a la hora 31 se registra una vez`() = runBlocking<Unit> {
        val c = reciente
        dudaN86("a1", creadoEn = c)
        var estado = "rejected"
        val v = historialReal { estado }
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, System.currentTimeMillis())
        ventana("a1")
        firmaTrasLaVentana("a1")
        assertThat(PaymentAttemptEntity.cerradaPorUnaPersona(dao.getById("a1"))).isTrue()
        assertWithMessage("con la firma, la tarjeta de A entra de inmediato").that(otraTarjeta(libreta())).isTrue()
        for (h in listOf(6L, 12L, 18L, 24L, 30L)) LedgerUnknownRecovery(dao, libreta(), v).recover(venue, c + h * 3_600_000L)
        assertWithMessage("«sin aprobar» no gasta turno, firmada o no").that(consultasGastadas("a1")).isEqualTo(0)

        estado = "APROBADA"
        val t31 = c + 31 * 3_600_000L
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t31)
        assertWithMessage("el aprobado queda en SU intento").that(dao.getById("a1")!!.hostApproved).isTrue()
        val servidor = Servidor()
        registrador(servidor).recover(venue, t31 + 3_600_000L)
        registrador(servidor).recover(venue, t31 + 7_200_000L)
        assertWithMessage("una venta, con la llave del intento").that(servidor.pagos).containsExactly("a1", 1)
    }

    // ═══════════════════ F · r8 #1 «(f)»: aceptar el rechazo del SERVIDOR no saca la fila del seguimiento ═══════════════════

    /**
     * 🔴 Codex final r8 #1, mitad «(f)» · POS → N86: la app muere antes de la primera consulta al historial. Al reiniciar, S6 trae que el
     * SERVIDOR soltó la solicitud por un rechazo del banco de ESTE intento (`NOT_CHARGED / PROCESSOR_DECLINED`, lo que llamamos 2c). El
     * SERVIDOR también llama rechazo al `rejected + 05AP`, que puede aprobarse después. La fila se descartaba ANTES de consultar el historial
     * y nadie la volvía a mirar; la base la conservaba y recuperaba ese aprobado. Ahora el rechazo suelta el freno, pero la fila sigue en el
     * seguimiento como una «sin aprobar» y, si el historial la trae aprobada, se registra una vez.
     */
    @Test fun `r8_1f F · el rechazo del SERVIDOR suelta el freno pero la fila sigue en el seguimiento del historial`() = runBlocking<Unit> {
        dudaN86("a1")
        assertThat(libreta().aplicarLiberacionDelServidor(LiberacionDelServidor(venue, "a1", "r-1", LiberacionDelServidor.RECHAZO_DEL_BANCO))
            .getOrThrow()).isTrue()
        assertThat(dao.getById("a1")!!.state).isEqualTo("DESCARTADA")
        assertWithMessage("el rechazo suelta el freno").that(otraTarjeta(libreta())).isTrue()

        var estado = "rejected"
        val v = historialReal { estado }
        val t = System.currentTimeMillis() + 3_600_000L
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t)
        assertWithMessage("se consulta aunque el rechazo la cerró").that(consultasAlHistorial).isEqualTo(1)
        assertWithMessage("«sin aprobar» no gasta turno").that(consultasGastadas("a1")).isEqualTo(0)

        estado = "APROBADA"
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t + 6 * 3_600_000L)
        assertWithMessage("el aprobado queda en SU intento").that(dao.getById("a1")!!.hostApproved).isTrue()
        val servidor = Servidor()
        registrador(servidor).recover(venue, t + 7 * 3_600_000L)
        registrador(servidor).recover(venue, t + 8 * 3_600_000L)
        assertWithMessage("una venta, con la llave del intento").that(servidor.pagos).containsExactly("a1", 1)
    }

    // ═══════════════ Codex final r10 (20-nov, `codex-final-r10-tpv.md`): los cuatro retrocesos de r9 frente a producción ═══════════════

    /**
     * 🔴 Codex final r10 #1 · TPV · PAGO RÁPIDO (N86): AngelPay aprobó, el aviso se perdió y el historial devuelve el cobro a los 75 s. El SDK
     * 1.0.23 espera hasta 140 s; la base esperaba 240 y registraba 1 pago en la primera pasada. R9-1 cortaba cada consulta a los 60 s: la duda
     * seguía y, con la misma demora toda la semana, 29 consultas y 0 pagos. Tiempo virtual: los 75 s no se esperan de verdad.
     */
    @Test fun `r10_1 · el historial que contesta a los 75 s registra el pago en la primera pasada`() = runTest {
        pagoRapidoEnDuda("pr", creadoEn = System.currentTimeMillis() - 3_600_000L)
        val adapter = mockk<PaymentPostOperationsAdapter>()
        coEvery { adapter.getTransactionHistory(any()) } coAnswers {
            consultasAlHistorial++
            delay(75_000L)
            Result.success(listOf(ventaEnElHistorial(firstArg<TransactionHistoryQuery>().integratorReference!!, "APROBADA")))
        }
        val servidor = Servidor()
        LedgerSweepLogic.recuperarElDinero(registrador(servidor), LedgerUnknownRecovery(dao, libreta(), verificadorCon(adapter)), venue,
            ahora = { System.currentTimeMillis() }, reloj = { testScheduler.currentTime })
        assertWithMessage("una consulta").that(consultasAlHistorial).isEqualTo(1)
        assertWithMessage("un pago, en la primera pasada").that(servidor.pagos).containsExactly("pr", 1)
    }

    /**
     * R10-1 · el tope del REGISTRO se queda en 1 min: un registro que el SERVIDOR sí guardó pero cuya respuesta no llega se corta, y el
     * reintento lleva la MISMA llave (la del intento), así que el SERVIDOR no hace un segundo `Payment` (índice único por `idempotencyKey`).
     */
    @Test fun `r10_1 · un registro cortado por el tope se reintenta con la misma llave y queda un solo pago`() = runTest {
        aprobado("b", creadoEn = System.currentTimeMillis() - 3_600_000L)
        val servidor = Servidor()
        val llaves = mutableListOf<String>()
        var primera = true
        val order = mockk<OrderPaymentRecorder>()
        coEvery { order.recordPayment(any(), any(), any(), any()) } coAnswers {
            val ctx = firstArg<PaymentContext>()
            llaves += ctx.idempotencyKey!!
            val respuesta = servidor.registrar(ctx)   // el SERVIDOR lo guarda…
            if (primera) { primera = false; delay(10 * 60_000L) }   // …y la respuesta no llega
            respuesta
        }
        val recuperacion = LedgerApprovalRecovery(dao, mockk<FastPaymentRecorder>(), order, { true })
        recuperacion.recover(venue, System.currentTimeMillis(), { testScheduler.currentTime })
        assertWithMessage("cortado: sigue sin registrar").that(dao.getById("b")!!.state).isNotEqualTo("REGISTRADO")
        recuperacion.recover(venue, System.currentTimeMillis() + 3_600_000L, { testScheduler.currentTime })
        assertWithMessage("dos intentos, la misma llave").that(llaves).containsExactly("b", "b")
        assertWithMessage("un solo Payment").that(servidor.pagos).containsExactly("b", 1)
        assertThat(dao.getById("b")!!.state).isEqualTo("REGISTRADO")
    }

    /**
     * 🔴 Codex final r10 #3 · POS → N86: el SERVIDOR soltó la cuenta a los 30 s, sin consulta pendiente. A los 2:30 la base y r9 admiten otra
     * tarjeta. Con R9-3 la consulta de SEGUIMIENTO del trabajador la volvía a marcar y, con menos de 5 min, la tarjeta se frenaba otra vez.
     */
    @Test fun `r10_3 · la cuenta que solto el SERVIDOR no se vuelve a frenar cuando el trabajador la sigue`() = runBlocking<Unit> {
        dudaN86("a1")
        ventana("a1")
        assertWithMessage("a los 2:30 la tarjeta de la cuenta entra").that(otraTarjeta(libreta())).isTrue()
        var consultas = 0
        val v = mockk<AngelPayChargeVerifier>()
        coEvery { v.verificar(any(), any(), any(), any(), any(), any()) } coAnswers { consultas++; VerificacionDelCobro.NoSePudoVerificar("sin red") }
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, System.currentTimeMillis())
        assertWithMessage("…también después de la pasada del trabajador").that(otraTarjeta(libreta())).isTrue()
        // Pasado el techo el seguimiento sigue (no se pierde) y tampoco frena.
        sql("UPDATE payment_attempts SET created_at = created_at - 600000 WHERE attempt_id = 'a1'")
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, System.currentTimeMillis() + 3_600_000L)
        assertWithMessage("se sigue consultando").that(consultas).isAtLeast(1)
        assertThat(otraTarjeta(libreta())).isTrue()
    }

    /** R10-3 · el caso contrario: la PRIMERA consulta de una duda viva, en vuelo, sigue frenando aunque el SERVIDOR la suelte antes del techo. */
    @Test fun `r10_3 · una duda viva con su primera consulta en vuelo sigue frenando aunque la suelten`() = runBlocking<Unit> {
        dudaN86("a1")
        val v = mockk<AngelPayChargeVerifier>()
        coEvery { v.verificar(any(), any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("sin respuesta")
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, System.currentTimeMillis())
        assertWithMessage("la lectura sigue despachada (en vuelo)").that(dao.getById("a1")!!.historyReadAt).isNotNull()
        ventana("a1")
        assertWithMessage("antes del techo, la lectura en vuelo sigue frenando").that(otraTarjeta(libreta())).isFalse()
    }

    /**
     * 🔴 Codex final r10 #4 · N86: tres ventas soltadas hace 10 días cuya lectura del historial nunca contesta (fallo técnico) conservaban la
     * marca, esquivaban la poda y en cada pasada gastaban el presupuesto de consultas (50 s cada una): un aprobado nuevo quedaba sin
     * consultar. La base ya las había podado. Ahora, pasados los 7 días, el fallo técnico gasta turno y la soltada se poda aunque conserve la
     * marca: en la pasada siguiente ya no están y el aprobado nuevo se consulta y se registra.
     */
    @Test fun `r10_4 · pasado el dia 7 el fallo tecnico gasta turno, la soltada se poda y el aprobado nuevo se consulta`() = runBlocking<Unit> {
        val hace10Dias = System.currentTimeMillis() - 10 * 86_400_000L
        for (id in listOf("f1", "f2", "f3", "f4")) {
            dudaN86(id, orden = "orden-$id", solicitud = "r-$id", creadoEn = hace10Dias)
            ventana(id)
            sql("UPDATE payment_attempts SET history_read_at = created_at + 30000 WHERE attempt_id = '$id'")
        }
        // f4 no alcanza consulta en esta pasada (su reserva sigue): sólo la poda la quita.
        sql("UPDATE payment_attempts SET lease_until = ${System.currentTimeMillis() + 86_400_000L} WHERE attempt_id = 'f4'")
        var reloj = 0L
        val adapter = mockk<PaymentPostOperationsAdapter>()
        coEvery { adapter.getTransactionHistory(any()) } coAnswers {
            val id = firstArg<TransactionHistoryQuery>().integratorReference!!
            consultasAlHistorial++
            if (id == "n") Result.success(listOf(ventaEnElHistorial(id, "APROBADA")))
            else { reloj += 50_000L; Result.failure(java.io.IOException("sin respuesta")) }
        }
        val servidor = Servidor()
        val observability = mockk<com.jaac.avoqado_tpv.core.observability.ObservabilityManager>(relaxed = true)
        suspend fun pasada(ahora: Long) {
            reloj = 0L
            LedgerSweepLogic.recuperarElDinero(registrador(servidor), LedgerUnknownRecovery(dao, libreta(), verificadorCon(adapter)), venue,
                ahora = { ahora }, reloj = { reloj })
            LedgerSweepLogic.sweepOnce(dao, venue, observability, ahora)
        }
        val t1 = System.currentTimeMillis()
        pasada(t1)
        for (id in listOf("f1", "f2", "f3", "f4")) assertWithMessage("$id: podada").that(dao.getById(id)).isNull()
        // 6 h después nace un aprobado nuevo (aviso perdido): se consulta y se registra en ESA pasada.
        val t2 = t1 + 6 * 3_600_000L
        pagoRapidoEnDuda("n", creadoEn = t2 - 180_000L)
        pasada(t2)
        assertWithMessage("el aprobado nuevo se registra").that(servidor.pagos).containsExactly("n", 1)
    }

    /** R10-4 · dentro de los 7 días nada cambia: el fallo técnico no gasta turno, la marca se queda y la poda no la toca. */
    @Test fun `r10_4 · dentro de los 7 dias el fallo tecnico no gasta turno y no se poda`() = runBlocking<Unit> {
        val hace6Dias = System.currentTimeMillis() - 6 * 86_400_000L
        dudaN86("f1", creadoEn = hace6Dias)
        ventana("f1")
        sql("UPDATE payment_attempts SET history_read_at = created_at + 30000 WHERE attempt_id = 'f1'")
        val v = mockk<AngelPayChargeVerifier>()
        coEvery { v.verificar(any(), any(), any(), any(), any(), any()) } returns VerificacionDelCobro.NoSePudoVerificar("sin respuesta")
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, System.currentTimeMillis())
        LedgerSweepLogic.sweepOnce(dao, venue, mockk(relaxed = true), System.currentTimeMillis())
        assertThat(dao.getById("f1")).isNotNull()
        assertThat(consultasGastadas("f1")).isEqualTo(0)
        assertThat(dao.getById("f1")!!.historyReadAt).isNotNull()
    }
}

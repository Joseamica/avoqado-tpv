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
import kotlinx.coroutines.runBlocking
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
}

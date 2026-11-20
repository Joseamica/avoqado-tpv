package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.gson.Gson
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.remotepayment.BandejaServerRecovery
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayChargeVerifier
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.processor.PaymentPostOperationsAdapter
import com.jaac.avoqado_tpv.features.payment.domain.processor.PostOperationsAdapterFactory
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import com.jaac.avoqado_tpv.features.payment.domain.processor.TransactionHistoryQuery
import com.jaac.avoqado_tpv.features.payment.domain.processor.UnifiedTransaction
import com.jaac.avoqado_tpv.features.tables.data.DineroDeTarjetaDeLaCuenta
import com.jaac.avoqado_tpv.features.tables.data.TARJETA_REGISTRANDOSE
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.math.BigDecimal

/**
 * 🔴 F1 · salida del gerente para el `05AP` (20-nov, `decisiones-codigo-r1.md` «20-nov 06:30»), POS → N86 de punta a punta sobre Room,
 * libreta, recuperación por servidor, recuperación por historial y la declaración del aviso REALES; sólo el transporte y el historial de
 * AngelPay son de mentira.
 *
 * El historial trae la venta sin aprobar (`05AP`): la marca de lectura se queda y la tarjeta de su cuenta sigue frenada aunque la VENTANA
 * del SERVIDOR suelte la solicitud. Antes esa fila salía en «Por revisar» sólo para consulta (la firma cerraba INDETERMINADO y el SERVIDOR no
 * aceptaba declarar un `NO_EVIDENCE_AFTER_WINDOW`): una tarjeta frenada SIN salida. Ahora es firmable; la firma va al SERVIDOR como
 * cualquier otra y, con el comprobante de una persona, la siguiente respuesta del historial cuenta como «no aparece» y suelta la tarjeta.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class FirmaTrasLaVentana05APRoomTest {
    private val venue = "venue-1"
    private lateinit var db: AvoqadoDatabase
    private lateinit var dao: PaymentAttemptDao
    private val api = mockk<TerminalAttemptApiService>()
    private val storage = mockk<SecureStorage>(relaxed = true)
    private val socket = mockk<SocketManager>(relaxed = true)

    @Before fun abrir() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.paymentAttemptDao()
        every { storage.getVenueId() } returns venue
        every { storage.getStaffId() } returns "cajera"
    }

    @After fun cerrar() = db.close()

    /** Un proceso de la app: su propia libreta (token), sin memoria. */
    private fun libreta() = PaymentAttemptLedger(dao, mockk(relaxed = true))

    private fun declaracionCon(l: PaymentAttemptLedger): DeclaracionDesdeElAviso {
        val recuperacion = LedgerServerRecovery(dao, l, api)
        return DeclaracionDesdeElAviso(
            l, BlumonAttemptResolver(l, recuperacion, api, storage), recuperacion,
            BandejaServerRecovery(db.remotePaymentRequestDao(), api), api, dagger.Lazy { socket },
        )
    }

    private fun contexto(id: String, orden: String, solicitud: String?): String = Gson().toJsonTree(
        PaymentContext.AngelPayPayment(
            venueId = venue, staffId = "cajera", amount = BigDecimal("100.00"), tip = BigDecimal.ZERO, merchantAccountId = "merchant",
            deviceSerialNumber = "terminal", idempotencyKey = id, terminalPaymentRequestId = solicitud, orderId = orden,
        ),
    ).asJsonObject.apply { addProperty("processorAffiliation", "afiliacion") }.toString()

    private val creado = System.currentTimeMillis() - 10 * 60_000

    /** POS → N86: el cobro de la cuenta [orden] quedó en duda (sin respuesta del banco). */
    private suspend fun duda(id: String, solicitud: String? = "r-1", orden: String = "orden-A") = dao.insert(
        PaymentAttemptEntity(
            attemptId = id, venueId = venue, processor = "ANGELPAY", state = "INDETERMINADO", amountCents = 10_000, tipCents = 0,
            recordingRoute = "ORDER", paymentContextJson = contexto(id, orden, solicitud), terminalPaymentRequestId = solicitud,
            serverAnsweredAt = 1L, createdAt = creado, updatedAt = creado,
        ),
    )

    /** El historial de AngelPay con la venta de ESTE intento en [estado] ("rejected" = `05AP DECLINADA`). */
    private fun historial(estado: () -> String): AngelPayChargeVerifier {
        val adapter = mockk<PaymentPostOperationsAdapter>()
        coEvery { adapter.getTransactionHistory(any()) } answers {
            val id = firstArg<TransactionHistoryQuery>().integratorReference!!
            Result.success(listOf(
                UnifiedTransaction(
                    processorType = ProcessorType.ANGELPAY, reference = "REF", amount = 100.0,
                    authorizationCode = if (estado() == "APROBADA") "AUTH" else "", status = estado(), cardType = null, last4 = null,
                    cardBin = "411111", entryMode = "CHIP", date = null, time = null, operationType = "VENTA", merchantName = null,
                    affiliation = "afiliacion", terminal = "terminal", folio = "1", waiter = null, tip = null, issuingBank = null,
                    cardMethod = null, creationDate = "", aid = null, arqc = null, postOperationType = null, postOperationReference = null,
                    postOperationAuthorization = null, postOperationStatus = null, integratorReference = id,
                ),
            ))
        }
        val fabrica = mockk<PostOperationsAdapterFactory>()
        every { fabrica.get(ProcessorType.ANGELPAY) } returns adapter
        return AngelPayChargeVerifier(fabrica)
    }

    /** Lo que publica el SERVIDOR (S6) de la solicitud que soltó la ventana; con [firma], el comprobante de una persona encima. */
    private fun s6Ventana(id: String, firma: String? = null): Response<TerminalAttemptStatusResponse> = Response.success(Gson().fromJson(
        """{"success":true,"attemptId":"$id","requestId":"r-1","attempt":{"attemptId":"$id","outcome":"NOT_RECORDED","paymentId":null,""" +
            """"processorEvidence":"NONE","paymentContradiction":false,"evidenceContradiction":false,"unattributedEvidence":false,""" +
            """"resolution":null},"request":{"requestId":"r-1","status":"FAILED","failureCode":"NO_EVIDENCE_AFTER_WINDOW",""" +
            """"outcome":"NOT_CHARGED","outcomeEvidence":"NO_EVIDENCE_AFTER_WINDOW"},"declarationReceipt":${firma ?: "null"}}""",
        TerminalAttemptStatusResponse::class.java,
    ))

    private fun comprobante(alreadyStored: Boolean, humano: Boolean = true) =
        """{"id":"res-1","kind":"UNCHARGED_VERIFIED","by":"SUPERVISOR_PIN","staffId":"gerente-1",""" +
            """"acceptedAt":"2026-11-20T12:00:00.000Z","scope":"REQUEST","attemptId":null,"requestId":"r-1",""" +
            """"humanSignature":$humano,"alreadyStored":$alreadyStored}"""

    /** El 2xx de la firma: el SERVIDOR guardó el asiento y la solicitud sigue liberada por la ventana. */
    private fun firmaAceptada(): Response<DeclaracionSinCobroResponse> = Response.success(Gson().fromJson(
        """{"success":true,"released":true,"status":"FAILED","requestId":"r-1","paymentId":null,"outcome":"NOT_CHARGED",""" +
            """"outcomeEvidence":"NO_EVIDENCE_AFTER_WINDOW","declarationReceipt":${comprobante(alreadyStored = false)},"message":"x"}""",
        DeclaracionSinCobroResponse::class.java,
    ))

    private suspend fun otraTarjeta(l: PaymentAttemptLedger, orden: String = "orden-A"): Boolean {
        val id = "b-${System.nanoTime()}"
        val entro = l.openAttempt(id, venue, "ANGELPAY", 10_000, 0, "ORDER", """{"orderId":"$orden"}""")
        if (entro) l.markDiscardedBeforeCharge(id, "fin de la prueba")
        return entro
    }

    private suspend fun renglon(l: PaymentAttemptLedger, id: String = "a1") = l.renglonPorRevisar(venue, CobroDeclarable.CLASE_INTENTO, id)

    private fun consultasGastadas(id: String): Int =
        db.openHelper.readableDatabase.query("SELECT history_checks FROM payment_attempts WHERE attempt_id = '$id'").use {
            it.moveToFirst(); it.getInt(0)
        }

    /**
     * Hasta que la ventana la suelta: E1 lee `05AP` (la marca se queda) y la terminal se entera de la liberación por S6.
     * Devuelve el reloj de la última pasada del historial.
     */
    private suspend fun frenadaTrasLaVentana(v: AngelPayChargeVerifier): Long {
        duda("a1")
        val t = System.currentTimeMillis() + 600_000
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t)
        coEvery { api.getAttemptStatus(venue, "a1") } returns s6Ventana("a1")
        LedgerServerRecovery(dao, libreta(), api).recoverOne(venue, "a1", estampar = false)
        val fila = dao.getById("a1")!!
        assertWithMessage("la ventana la soltó").that(fila.serverOutcome).isEqualTo(PaymentAttemptEntity.SERVER_RELEASED_NO_EVIDENCE)
        assertWithMessage("la lectura 05AP sigue despachada").that(fila.historyReadAt).isNotNull()
        assertWithMessage("la tarjeta de A sigue frenada").that(otraTarjeta(libreta())).isFalse()
        return t
    }

    @Test fun `N86_05AP_tras_la_ventana_firmable_y_la_firma_suelta_la_tarjeta_en_la_siguiente_respuesta`() = runBlocking<Unit> {
        val v = historial { "rejected" }
        var t = frenadaTrasLaVentana(v)
        val l = libreta()
        val cobro = renglon(l)
        assertWithMessage("sale en «Por revisar» FIRMABLE (antes, sólo para consulta)").that(cobro?.declarable).isTrue()

        val pedidas = mutableListOf<Long>()
        l.alFirmarConLecturaPendiente = { pedidas += it }
        coEvery { api.declararSinCobro(venue, "r-1", any()) } returns firmaAceptada()
        coEvery { api.getAttemptStatus(venue, "a1") } returns s6Ventana("a1", comprobante(alreadyStored = true))
        val antes = System.currentTimeMillis()
        assertThat(declaracionCon(l).declarar(venue, cobro!!, supervisorPin = "1234")).isEqualTo(ResultadoDeLaDeclaracion.Declarado)
        val despues = System.currentTimeMillis()

        val firmada = dao.getById("a1")!!
        assertWithMessage("cerrada por una PERSONA, no por la ventana").that(PaymentAttemptEntity.cerradaPorUnaPersona(firmada)).isTrue()
        assertThat(firmada.serverOutcome).isEqualTo(PaymentAttemptEntity.SERVER_OPERATOR_NO_INSTRUMENT)
        assertWithMessage("la marca la termina la siguiente respuesta, no la firma").that(firmada.historyReadAt).isNotNull()
        assertWithMessage("se pide la pasada que la termina (no se espera al barrido de 6 h)").that(pedidas).hasSize(1)
        val reserva = firmada.leaseUntil!!
        assertWithMessage("…después de que venza la reserva de la lectura").that(despues + pedidas.single()).isAtLeast(reserva)
        assertWithMessage("…y en cuanto vence").that(pedidas.single()).isAtMost(reserva - antes + MARGEN_TRAS_LA_RESERVA_MS)
        assertWithMessage("ya no se ofrece firmar otra vez").that(renglon(l)?.declarable).isNotEqualTo(true)
        assertWithMessage("antes de la siguiente respuesta, la tarjeta todavía espera").that(otraTarjeta(l)).isFalse()

        t += 3_600_000
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t)   // AngelPay sigue en 05AP
        assertWithMessage("con la firma, 05AP cuenta como «no aparece»: la tarjeta entra").that(otraTarjeta(libreta())).isTrue()
        assertThat(dao.getById("a1")!!.historyReadAt).isNull()
        assertWithMessage("una respuesta con la firma gasta un turno").that(consultasGastadas("a1")).isEqualTo(1)
        assertWithMessage("y sale de «Por revisar»").that(renglon(libreta())).isNull()
    }

    @Test fun `N86_05AP_firmado_tras_la_ventana_si_AngelPay_lo_aprueba_despues_se_registra`() = runBlocking<Unit> {
        var estado = "rejected"
        val v = historial { estado }
        var t = frenadaTrasLaVentana(v)
        val l = libreta()
        coEvery { api.declararSinCobro(venue, "r-1", any()) } returns firmaAceptada()
        coEvery { api.getAttemptStatus(venue, "a1") } returns s6Ventana("a1", comprobante(alreadyStored = true))
        assertThat(declaracionCon(l).declarar(venue, renglon(l)!!, supervisorPin = "1234")).isEqualTo(ResultadoDeLaDeclaracion.Declarado)
        t += 3_600_000
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t)
        estado = "APROBADA"   // la respuesta del banco llegó tarde: la firma no apaga la consulta
        t += 3_600_000
        LedgerUnknownRecovery(dao, libreta(), v).recover(venue, t)
        val fila = dao.getById("a1")!!
        assertWithMessage("el aprobado queda en SU intento").that(fila.hostApproved).isTrue()
        assertThat(fila.referenceNumber).isEqualTo("REF")
        assertWithMessage("la cuenta vuelve a quedar protegida").that(otraTarjeta(libreta())).isFalse()
    }

    @Test fun `N86_05AP_tras_la_ventana_sin_red_no_hay_firma_la_tarjeta_espera_y_sigue_firmable`() = runBlocking<Unit> {
        frenadaTrasLaVentana(historial { "rejected" })
        val l = libreta()
        val pedidas = mutableListOf<Long>()
        l.alFirmarConLecturaPendiente = { pedidas += it }
        coEvery { api.declararSinCobro(venue, "r-1", any()) } throws java.io.IOException("sin red")
        assertThat(declaracionCon(l).declarar(venue, renglon(l)!!, supervisorPin = "1234")).isEqualTo(ResultadoDeLaDeclaracion.SinRed)

        assertThat(dao.getById("a1")!!.serverOutcome).isEqualTo(PaymentAttemptEntity.SERVER_RELEASED_NO_EVIDENCE)
        assertWithMessage("la tarjeta de esa cuenta espera").that(otraTarjeta(l)).isFalse()
        assertWithMessage("sigue firmable para cuando vuelva la red").that(renglon(l)?.declarable).isTrue()
        assertThat(pedidas).isEmpty()
        assertWithMessage("el efectivo de esa cuenta sigue").that(l.hayDineroEnJuegoDeLaVenta("orden-A")).isFalse()
        assertWithMessage("las demás cuentas cobran con tarjeta").that(otraTarjeta(l, "orden-B")).isTrue()

        // Vuelve la red: la MISMA firma pasa.
        coEvery { api.declararSinCobro(venue, "r-1", any()) } returns firmaAceptada()
        coEvery { api.getAttemptStatus(venue, "a1") } returns s6Ventana("a1", comprobante(alreadyStored = true))
        assertThat(declaracionCon(l).declarar(venue, renglon(l)!!, supervisorPin = "1234")).isEqualTo(ResultadoDeLaDeclaracion.Declarado)
        assertThat(PaymentAttemptEntity.cerradaPorUnaPersona(dao.getById("a1"))).isTrue()
        assertThat(pedidas).hasSize(1)
    }

    @Test fun `la ventana sola o un comprobante que no es de una persona nunca cierran como firma`() = runBlocking<Unit> {
        frenadaTrasLaVentana(historial { "rejected" })
        val l = libreta()
        for ((caso, recibo) in listOf(
            "sin firma humana" to comprobante(alreadyStored = true, humano = false),
            "de otra solicitud" to comprobante(alreadyStored = true).replace("\"requestId\":\"r-1\"", "\"requestId\":\"r-2\""),
            "clase del banco" to comprobante(alreadyStored = true).replace("UNCHARGED_VERIFIED", "BANK_DECLINED"),
        )) {
            // Un comprobante de otro alcance ni siquiera libera (null); los demás se quedan en la ventana, que ya estaba aplicada.
            val liberacion = LiberacionDelServidor.desdeConsultaS6(venue, "a1", s6Ventana("a1", recibo).body()!!)
            assertWithMessage(caso).that(liberacion?.evidencia).isNotEqualTo("OPERATOR_RECONCILED")
            if (liberacion != null) assertWithMessage("$caso: no cambia la fila").that(l.aplicarLiberacionDelServidor(liberacion).getOrThrow()).isFalse()
        }
        assertThat(dao.getById("a1")!!.serverOutcome).isEqualTo(PaymentAttemptEntity.SERVER_RELEASED_NO_EVIDENCE)
        // Por la SOLICITUD (la N86 que no ligó su intento): sólo la firma de una persona; el silencio, nunca.
        fun porSolicitud(recibo: String?) = LiberacionDelServidor.desdeConsultaPorSolicitud(venue, "a2", "r-1", Gson().fromJson(
            """{"success":true,"requestId":"r-1","resuelta":true,"request":{"requestId":"r-1","venueId":"$venue","status":"FAILED",""" +
                """"failureCode":"NO_EVIDENCE_AFTER_WINDOW","outcome":"NOT_CHARGED","outcomeEvidence":"NO_EVIDENCE_AFTER_WINDOW"},""" +
                """"declarationReceipt":${recibo ?: "null"}}""",
            TerminalRequestStatusResponse::class.java,
        ))
        assertWithMessage("el silencio de la ventana no cierra por la solicitud").that(porSolicitud(null)).isNull()
        assertThat(porSolicitud(comprobante(alreadyStored = true, humano = false))).isNull()
        val firmada = porSolicitud(comprobante(alreadyStored = true))
        assertThat(firmada?.evidencia).isEqualTo("OPERATOR_RECONCILED")
        assertThat(firmada?.quien).isEqualTo("gerente-1")
    }

    @Test fun `el CAS que pasa la ventana a firma no deshace dinero ni vetos ni vuelve atras`() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val ventana = PaymentAttemptEntity.LAST_ERROR_LIBERADA_PREFIX + "NO_EVIDENCE_AFTER_WINDOW"
        val firma = PaymentAttemptEntity.LAST_ERROR_LIBERADA_PREFIX + "OPERATOR_RECONCILED"
        for (id in listOf("limpia", "aprobada", "vetada")) {
            duda(id, orden = "orden-$id")
            assertThat(dao.cerrarPorLiberacionDelServidor(id, venue, "r-1", "RELEASED_NO_EVIDENCE", ventana, now, "otro")).isEqualTo(1)
        }
        db.openHelper.writableDatabase.execSQL("UPDATE payment_attempts SET host_approved = 1 WHERE attempt_id = 'aprobada'")
        db.openHelper.writableDatabase.execSQL("UPDATE payment_attempts SET server_veto = 'PAYMENT_CONTRADICTION' WHERE attempt_id = 'vetada'")
        assertWithMessage("la ventana no vuelve a cerrar lo que ya cerró")
            .that(dao.cerrarPorLiberacionDelServidor("limpia", venue, "r-1", "RELEASED_NO_EVIDENCE", ventana, now, "otro")).isEqualTo(0)
        assertWithMessage("con el aprobado escrito, no").that(dao.cerrarPorLiberacionDelServidor("aprobada", venue, "r-1", "OPERATOR_NO_INSTRUMENT", firma, now, "otro")).isEqualTo(0)
        assertWithMessage("con un veto, no").that(dao.cerrarPorLiberacionDelServidor("vetada", venue, "r-1", "OPERATOR_NO_INSTRUMENT", firma, now, "otro")).isEqualTo(0)
        assertWithMessage("de otra solicitud, no").that(dao.cerrarPorLiberacionDelServidor("limpia", venue, "r-9", "OPERATOR_NO_INSTRUMENT", firma, now, "otro")).isEqualTo(0)
        assertThat(dao.cerrarPorLiberacionDelServidor("limpia", venue, "r-1", "OPERATOR_NO_INSTRUMENT", firma, now, "otro")).isEqualTo(1)
        assertWithMessage("una vez").that(dao.cerrarPorLiberacionDelServidor("limpia", venue, "r-1", "OPERATOR_NO_INSTRUMENT", firma, now, "otro")).isEqualTo(0)
        assertWithMessage("y nunca de firma a ventana").that(dao.cerrarPorLiberacionDelServidor("limpia", venue, "r-1", "RELEASED_NO_EVIDENCE", ventana, now, "otro")).isEqualTo(0)
    }

    @Test fun `sin la marca del historial una liberada por la ventana no es firmable`() = runBlocking<Unit> {
        duda("a1")
        val ventana = PaymentAttemptEntity.LAST_ERROR_LIBERADA_PREFIX + "NO_EVIDENCE_AFTER_WINDOW"
        dao.cerrarPorLiberacionDelServidor("a1", venue, "r-1", "RELEASED_NO_EVIDENCE", ventana, System.currentTimeMillis(), "otro")
        assertThat(renglon(libreta())?.declarable).isNotEqualTo(true)
        assertWithMessage("y su tarjeta entra").that(otraTarjeta(libreta())).isTrue()
    }

    // ═══════════════ De paso (misma familia «la admisión ve la memoria»): las puertas del EFECTIVO de la cuenta ═══════════════

    /** Un proceso cuya base no puede escribir el aprobado final de AngelPay (disco lleno, E/S): el aprobado queda sólo en memoria. */
    private fun libretaQueNoEscribeElAprobado() = PaymentAttemptLedger(
        object : PaymentAttemptDao by dao {
            override suspend fun escribirAprobadoFinalAngelPay(
                venueId: String, attemptId: String, referencia: String?, autorizacion: String?, origen: String, now: Long,
            ): AprobadoFinal = throw android.database.sqlite.SQLiteFullException("disco lleno")
        },
        mockk(relaxed = true),
    )

    @Test fun `N86_aprobado_solo_en_memoria_no_ofrece_cobrar_la_cuenta_en_efectivo`() = runBlocking<Unit> {
        duda("a1", solicitud = null)
        val l = libretaQueNoEscribeElAprobado()
        assertWithMessage("sin dinero conocido, el efectivo sigue").that(l.hayDineroEnJuegoDeLaVenta("orden-A")).isFalse()
        assertThat(l.dineroDeTarjetaSinConfirmarDeLaCuenta(venue, "orden-A")).isNull()

        assertWithMessage("el aprobado no quedó escrito").that(l.markHostResponded("a1", true, null, "REF", "AUTH")).isFalse()
        assertThat(l.aprobacionPendienteDeGuardar("a1")).isTrue()

        assertWithMessage("«Pedidos por pagar»: la tarjeta pasó, no se cobra el folio en efectivo").that(l.hayDineroEnJuegoDeLaVenta("orden-A")).isTrue()
        assertWithMessage("Mesas: lo que falta espera a que la tarjeta se registre")
            .that(DineroDeTarjetaDeLaCuenta(l).motivo(venue, "orden-A")).isEqualTo(TARJETA_REGISTRANDOSE)
        assertWithMessage("otra cuenta, sin cambio").that(l.hayDineroEnJuegoDeLaVenta("orden-B")).isFalse()
        assertThat(l.dineroDeTarjetaSinConfirmarDeLaCuenta(venue, "orden-B")).isNull()
    }
}

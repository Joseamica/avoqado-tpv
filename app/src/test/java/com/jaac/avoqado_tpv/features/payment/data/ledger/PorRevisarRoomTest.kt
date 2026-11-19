package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 🔴 F1 bloque 5 (I5): «Por revisar» es la unión, por IDENTIDAD, de lo que cuenta Caja, lo que frena una tarjeta y las solicitudes sin
 * intento. Un renglón por intento: dos intentos de la misma solicitud son dos renglones. Orden estable y total exacto.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class PorRevisarRoomTest {
    private val venue = "venue-1"
    private lateinit var db: AvoqadoDatabase
    private lateinit var dao: PaymentAttemptDao
    private lateinit var ledger: PaymentAttemptLedger

    @Before fun abrir() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.paymentAttemptDao()
        ledger = PaymentAttemptLedger(dao, mockk(relaxed = true))
    }

    @After fun cerrar() = db.close()

    private suspend fun fila(id: String, estado: String, solicitud: String? = null, creado: Long = 1_000L, ajuste: String? = null) {
        dao.insert(
            PaymentAttemptEntity(
                attemptId = id, venueId = venue, processor = "ANGELPAY", state = estado, amountCents = 10_000, tipCents = 0,
                recordingRoute = "ORDER", terminalPaymentRequestId = solicitud,
                paymentContextJson = """{"orderId":"orden-$id"""" + (solicitud?.let { ""","terminalPaymentRequestId":"$it"""" } ?: "") + "}",
                createdAt = creado, updatedAt = creado,
            ),
        )
        ajuste?.let { db.openHelper.writableDatabase.execSQL("UPDATE payment_attempts SET $it WHERE attempt_id = '$id'") }
    }

    private suspend fun lista(limite: Int = DeclaracionDesdeElAviso.TOPE_DE_LA_CONSULTA) =
        DeclaracionDesdeElAviso.porRevisar(ledger.observarCobrosDeclarables(venue, limite).first())

    private fun declaracion() = DeclaracionDesdeElAviso(
        ledger, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
    )

    @Test fun `dos_dudas_misma_solicitud_dos_filas_anterior_con_dinero`() = runBlocking<Unit> {
        fila("a1", "DESCARTADA", solicitud = "r-1", creado = 1_000, ajuste = "server_processor_evidence = 'APPROVED'")
        fila("a2", "INDETERMINADO", solicitud = "r-1", creado = 2_000)
        val renglones = lista()
        assertWithMessage("un renglón por intento").that(renglones.map { it.attemptId }).containsExactly("a2", "a1").inOrder()
        assertWithMessage("el anterior, con dinero, sólo se consulta").that(renglones.single { it.attemptId == "a1" }.declarable).isFalse()
        assertWithMessage("y el hermano no se firma mientras el otro trae dinero")
            .that(renglones.single { it.attemptId == "a2" }.declarable).isFalse()
    }

    @Test fun `descartada_con_dinero_visible_aunque_hermano_registrado`() = runBlocking<Unit> {
        fila("a1", "DESCARTADA", solicitud = "r-1", ajuste = "server_processor_evidence = 'APPROVED'")
        fila("a2", "REGISTRADO", solicitud = "r-1")
        assertThat(lista().map { it.attemptId }).containsExactly("a1")
    }

    @Test fun `registro_fallido_y_handoff_huerfano_visibles`() = runBlocking<Unit> {
        fila("fallido", "REGISTRO_FALLIDO", creado = 1_000, ajuste = "host_approved = 1, reference_number = 'REF', auth_code = 'AUTH'")
        fila("huerfano", "ENTREGADA_A_COLA", creado = 2_000)   // en la libreta dice «en la cola», pero la cola no lo tiene
        val renglones = lista()
        assertThat(renglones.map { it.attemptId }).containsExactly("huerfano", "fallido").inOrder()
        assertThat(renglones.none { it.declarable }).isTrue()
        val d = declaracion()
        assertThat(d.resultadoDe(venue, renglones.single { it.attemptId == "fallido" }).texto())
            .isEqualTo("El banco autorizó este intento. Falta confirmar el registro.")
        assertThat(d.resultadoDe(venue, renglones.single { it.attemptId == "huerfano" }).texto())
            .isEqualTo("Este cobro ya está en la cola para subir a Avoqado. No lo vuelvas a cobrar.")
    }

    /**
     * 🔴 Codex final r1 #8 · POS → TPV: la solicitud sigue en proceso con la ejecución abierta y su único intento terminó rechazado sin
     * dinero. Caja la cuenta (el intento descartado ya no responde por ella) y «Por revisar» la escondía por tener un intento histórico.
     * La lista usa EXACTAMENTE la condición de Caja para las solicitudes.
     */
    @Test fun `solicitud_con_intento_rechazado_que_caja_cuenta_sale_en_por_revisar`() = runBlocking<Unit> {
        db.remotePaymentRequestDao().insert(
            com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentRequestEntity(
                requestId = "r-1", venueId = venue, amountCents = 10_000, tipCents = 0, rating = null, skipReview = true,
                orderId = "orden-a1", processedByStaffId = null, senderDeviceName = "POS", sourceTimestamp = "t",
                status = com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentRequestEntity.STATUS_PROCESSING,
                createdAt = 3_000, updatedAt = 3_000,
            ),
        )
        fila("a1", "DESCARTADA", solicitud = "r-1", ajuste = "last_error = 'rechazada_por_el_banco'")
        assertWithMessage("Caja la cuenta").that(db.localShiftSessionDao().ejecucionesRemotas(venue, "turno-1")).isEqualTo(1)
        val renglones = lista()
        assertWithMessage("«Por revisar» también").that(renglones.map { it.identidad }).containsExactly("REMOTE:r-1")
        assertThat(ledger.observarTotalPorRevisar(venue).first()).isEqualTo(1)
        assertThat(ledger.renglonPorRevisar(venue, CobroDeclarable.CLASE_SOLICITUD, "r-1")).isNotNull()
        // D8: tuvo un intento (descartado): la resuelve el SERVIDOR. Se ve y se consulta; no se firma, y mientras siga no se dice que ya no está.
        val renglon = renglones.single()
        assertThat(renglon.declarable).isFalse()
        assertThat(declaracion().resultadoDe(venue, renglon).texto())
            .isEqualTo("Este cobro todavía no se puede declarar. Vuelve a consultar en un momento.")

        // Con un intento vivo, la solicitud sale por su INTENTO (Caja tampoco la cuenta como solicitud): un renglón, no dos.
        fila("a2", "INDETERMINADO", solicitud = "r-1", creado = 4_000)
        assertThat(db.localShiftSessionDao().ejecucionesRemotas(venue, "turno-1")).isEqualTo(0)
        assertThat(lista().map { it.identidad }).containsExactly("ATTEMPT:a2")
    }

    @Test fun `fila_51_empate_de_fecha_y_caja_compartida_sin_huecos`() = runBlocking<Unit> {
        // 51 dudas con la MISMA hora, de dos en dos por solicitud (la misma cuenta del POS reintentada), repartidas en dos turnos.
        for (i in 0 until 51) fila(
            "a%02d".format(i), "INDETERMINADO", solicitud = "r-${i / 2}", creado = 5_000,
            ajuste = "local_shift_session_id = 'turno-${i % 2}', shift_membership = 'MANAGED'",
        )
        val total = ledger.observarTotalPorRevisar(venue).first()
        assertWithMessage("total exacto").that(total).isEqualTo(51)
        val pagina1 = lista(50)
        assertThat(pagina1.map { it.identidad }.toSet()).hasSize(50)
        assertWithMessage("falta traer").that(DeclaracionDesdeElAviso.faltanRenglones(pagina1, total)).isTrue()
        val pagina2 = lista(100)
        assertWithMessage("llega al último, sin huecos ni repetidos").that(pagina2.map { it.attemptId }.toSet()).hasSize(51)
        assertWithMessage("orden estable: la segunda vuelta empieza igual").that(pagina2.take(50)).isEqualTo(pagina1)
        assertThat(DeclaracionDesdeElAviso.faltanRenglones(pagina2, total)).isFalse()
        // Lo que cuenta Caja (la misma regla) son esos mismos 51.
        val enCaja = db.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM payment_attempts WHERE venue_id = '$venue' AND " +
                com.jaac.avoqado_tpv.features.shift.domain.SQL_INTENTO_QUE_CUENTA_CAJA,
        ).use { it.moveToFirst(); it.getInt(0) }
        assertThat(enCaja).isEqualTo(51)
    }
}

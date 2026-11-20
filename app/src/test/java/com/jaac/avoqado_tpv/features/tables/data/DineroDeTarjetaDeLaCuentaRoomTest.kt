package com.jaac.avoqado_tpv.features.tables.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D9 (Mesas, Codex r3, 16-nov) con la base real: la consulta ÚNICA que junta cola y libreta de una cuenta. En una sola sentencia,
 * el cobro con tarjeta se ve antes, durante y después del traspaso libreta → cola (en dos consultas podía no verse en ninguna).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class DineroDeTarjetaDeLaCuentaRoomTest {
    private lateinit var db: AvoqadoDatabase
    private val venue = "venue-1"
    private val token = "proceso-1"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    private fun fragmento(orderId: String) = "\"orderId\":\"$orderId\""

    private suspend fun consulta(orderId: String): String? =
        db.paymentAttemptDao().dineroDeTarjetaDeLaCuenta(venue, orderId, fragmento(orderId), token)

    private suspend fun intento(id: String, estado: String, orderId: String) {
        db.paymentAttemptDao().insert(
            PaymentAttemptEntity(
                attemptId = id, venueId = venue, processor = PaymentAttemptEntity.PROCESSOR_BLUMON, state = estado,
                amountCents = 5_000, tipCents = 0, recordingRoute = "FAST",
                paymentContextJson = """{"orderId":"$orderId","terminalPaymentRequestId":null}""",
                hostApproved = true, createdAt = 1_000L, updatedAt = 1_000L,
            ),
        )
    }

    private suspend fun enCola(referencia: String, estado: String, orderId: String, revisada: Boolean = false) {
        db.pendingPaymentDao().insert(
            PendingPaymentEntity(
                referenceNumber = referencia, venueId = venue, staffId = "staff-1", amount = "50.00", tip = "0.00", rating = null,
                merchantAccountId = "", blumonSerialNumber = "", maskedPan = null, cardBrand = null, entryMode = "CONTACTLESS",
                isInternational = false, authorizationNumber = "123456", orderId = orderId, createdAt = 1_000L,
                syncStatus = estado, acknowledged = revisada,
            ),
        )
    }

    @Test
    fun sin_nada_de_tarjeta_no_hay_nada() = runTest {
        assertThat(consulta("orden-1")).isNull()
    }

    @Test
    fun aprobada_y_registrandose_la_ve_la_libreta() = runTest {
        // «Aprobado» en la PAX: la libreta ya está en HOST_RESPONDIO (y luego AUTORIZADO) con el orderId de la mesa.
        intento("a1", PaymentAttemptEntity.STATE_HOST_RESPONDIO, "orden-1")
        assertThat(consulta("orden-1")).isEqualTo("LIBRETA")
        intento("a2", PaymentAttemptEntity.STATE_AUTORIZADO, "orden-2")
        assertThat(consulta("orden-2")).isEqualTo("LIBRETA")
    }

    @Test
    fun tras_el_traspaso_a_la_cola_la_ve_la_cola() = runTest {
        // El registro falló: la libreta queda ENTREGADA_A_COLA (ya no cerca) y la fila de la cola espera subir.
        intento("a1", PaymentAttemptEntity.STATE_ENTREGADA_A_COLA, "orden-1")
        enCola("REF-1", PendingPaymentEntity.SYNC_STATUS_PENDING, "orden-1")
        assertThat(consulta("orden-1")).isEqualTo("SUBIENDO")
    }

    @Test
    fun una_tarjeta_que_el_servidor_no_registro_y_nadie_reviso_pide_al_gerente() = runTest {
        intento("a1", PaymentAttemptEntity.STATE_ENTREGADA_A_COLA, "orden-1")
        enCola("REF-1", "FAILED", "orden-1")
        assertThat(consulta("orden-1")).isEqualTo("SIN_REGISTRAR")
    }

    @Test
    fun ya_registrada_o_ya_subida_no_frena() = runTest {
        intento("a1", PaymentAttemptEntity.STATE_REGISTRADO, "orden-1")
        enCola("REF-1", "SUCCESS", "orden-1")
        intento("a2", PaymentAttemptEntity.STATE_CERRADA, "orden-1")
        assertThat(consulta("orden-1")).isNull()
    }

    @Test
    fun lo_de_otra_cuenta_no_frena_esta() = runTest {
        intento("a1", PaymentAttemptEntity.STATE_AUTORIZADO, "orden-A")
        enCola("REF-1", PendingPaymentEntity.SYNC_STATUS_PENDING, "orden-A")
        assertThat(consulta("orden-B")).isNull()
        // Y una cuenta cuyo id empieza igual no se confunde (el fragmento cierra con comillas).
        assertThat(consulta("orden")).isNull()
    }

    // ═══════════════ F1 · Codex r9 R9-2 (TPV SOLA → Mesas): el EFECTIVO de una cuenta con una DUDA de tarjeta se cobra con K19c ═══════════════

    private val k19c = "Esta cuenta tiene un cobro por confirmar. Avísale al gerente."
    private fun dinero() = DineroDeTarjetaDeLaCuenta(PaymentAttemptLedger(db.paymentAttemptDao(), mockk(relaxed = true)))
    private fun sql(q: String) = db.openHelper.writableDatabase.execSQL(q)

    /** Un cobro con tarjeta de [orderId] en duda (sin respuesta del banco): local (TPV SOLA) o de una solicitud del POS ([solicitud]). */
    private suspend fun duda(id: String, orderId: String, solicitud: String? = null) {
        val ahora = System.currentTimeMillis()
        db.paymentAttemptDao().insert(
            PaymentAttemptEntity(
                attemptId = id, venueId = venue, processor = PaymentAttemptEntity.PROCESSOR_ANGELPAY,
                state = PaymentAttemptEntity.STATE_INDETERMINADO, amountCents = 5_000, tipCents = 0, recordingRoute = "ORDER",
                paymentContextJson = """{"orderId":"$orderId","terminalPaymentRequestId":${solicitud?.let { "\"$it\"" } ?: "null"}}""",
                terminalPaymentRequestId = solicitud, createdAt = ahora, updatedAt = ahora,
            ),
        )
    }

    @Test fun r9_2_duda_de_esta_cuenta_avisa_y_no_frena_el_efectivo() = runTest {
        assertThat(dinero().avisoDeDuda("orden-1")).isNull()
        duda("d1", "orden-1")
        assertThat(dinero().avisoDeDuda("orden-1")).isEqualTo(k19c)
        // No es dinero conocido: el efectivo no se frena (D10 «B»).
        assertThat(dinero().motivo(venue, "orden-1")).isNull()
    }

    @Test fun r9_2_la_duda_de_otra_mesa_no_avisa() = runTest {
        duda("d1", "orden-2")
        assertThat(dinero().avisoDeDuda("orden-2")).isEqualTo(k19c)
        assertThat(dinero().avisoDeDuda("orden-1")).isNull()
        assertThat(dinero().avisoDeDuda("orden")).isNull()
    }

    @Test fun r9_2_la_duda_firmada_por_el_gerente_no_avisa() = runTest {
        duda("d1", "orden-1")
        // Con la marca del historial viva, también.
        sql("UPDATE payment_attempts SET history_read_at = created_at WHERE attempt_id = 'd1'")
        assertThat(dinero().avisoDeDuda("orden-1")).isEqualTo(k19c)
        sql("UPDATE payment_attempts SET state = 'DESCARTADA', last_error = 'declarado_sin_cobro:gerente' WHERE attempt_id = 'd1'")
        assertThat(dinero().avisoDeDuda("orden-1")).isNull()
    }

    @Test fun r9_2_la_duda_de_un_cobro_del_POS_pasado_el_techo_no_avisa() = runTest {
        duda("d1", "orden-1", solicitud = "r-1")
        sql("UPDATE payment_attempts SET history_read_at = created_at WHERE attempt_id = 'd1'")
        assertThat(dinero().avisoDeDuda("orden-1")).isEqualTo(k19c)
        val techo = PaymentAttemptEntity.TECHO_DE_LA_DUDA_DEL_POS_MS + 60_000
        sql("UPDATE payment_attempts SET created_at = created_at - $techo, history_read_at = history_read_at - $techo WHERE attempt_id = 'd1'")
        assertThat(dinero().avisoDeDuda("orden-1")).isNull()
    }
}

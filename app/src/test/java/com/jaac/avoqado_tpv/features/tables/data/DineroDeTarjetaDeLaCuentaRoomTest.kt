package com.jaac.avoqado_tpv.features.tables.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity
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
}

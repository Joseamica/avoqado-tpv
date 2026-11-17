package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 🔴 D13 (encargo 5ffa66, tarjeta hz:72be07e6:D13; Codex r4 de D9): en la PAX, la respuesta del banco se guarda con
 * `markHostResponded` y su `false` se ignoraba: si la escritura fallaba (disco lleno, error de E/S) la pantalla publicaba un
 * «Aprobado» limpio y, si además el proceso moría antes de registrar o encolar, la libreta quedaba en AUTORIZANDO sin
 * `host_approved` — la cuarentena la pasaba a «en duda» y la venta volvía a ofrecer el total.
 *
 * Con Room real y un disparador que hace fallar la escritura de `host_approved = 1` (la falla de disco, inyectada). Sin el
 * arreglo, la primera prueba sale en rojo: la aprobación no queda en ninguna parte y el cobro siguiente entra.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class AprobacionSinGuardarRoomTest {

    private lateinit var db: AvoqadoDatabase
    private lateinit var dao: PaymentAttemptDao
    private lateinit var ledger: PaymentAttemptLedger
    private val venue = "venue-1"
    private val settings = mockk<com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository>(relaxed = true)

    private fun libreta() = PaymentAttemptLedger(dao, settings)

    @Before fun abrir() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.paymentAttemptDao()
        ledger = libreta()
    }

    @After fun cerrar() = db.close()

    /** La falla de disco: toda escritura de «el banco aprobó» (`host_approved = 1`) aborta. El resto de la base sigue sana. */
    private fun rompeLaEscrituraDeLaAprobacion() = db.openHelper.writableDatabase.execSQL(
        "CREATE TRIGGER IF NOT EXISTS aprobacion_rota BEFORE UPDATE OF host_approved ON payment_attempts " +
            "WHEN NEW.host_approved = 1 BEGIN SELECT RAISE(ABORT, 'disco lleno'); END",
    )

    private fun laBaseVuelve() = db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS aprobacion_rota")

    private suspend fun cobroNuevoEntra(id: String, libreta: PaymentAttemptLedger = ledger) =
        libreta.openAttempt(id, venue, PaymentAttemptEntity.PROCESSOR_BLUMON, 5000, 0, "FAST", """{"amount":50.00}""")

    /** Un cobro PAX de $50 que ya salió al banco (AUTORIZANDO), y el banco aprobó, pero la escritura de esa respuesta falló. */
    private suspend fun aprobacionQueNoSeEscribio(id: String = "a") {
        assertThat(cobroNuevoEntra(id)).isTrue()
        assertThat(ledger.markAuthorizing(id)).isTrue()
        rompeLaEscrituraDeLaAprobacion()
        assertWithMessage("control: la escritura de la aprobación sí falla")
            .that(ledger.markHostResponded(id, approved = true, operationId = "op-1", referenceNumber = "ref-1", authCode = "auth-1"))
            .isFalse()
        assertThat(dao.getById(id)!!.state).isEqualTo(PaymentAttemptEntity.STATE_AUTORIZANDO)
        assertThat(dao.getById(id)!!.hostApproved).isNotEqualTo(true)
    }

    @Test fun `P1 la aprobacion que no se pudo escribir es dinero sin escribir - frena el cobro siguiente y el no se cobro`() = runTest {
        aprobacionQueNoSeEscribio()

        assertWithMessage("la pantalla lo sabe: no puede pintar un «Aprobado» limpio").that(ledger.aprobacionSigueSinGuardar("a")).isTrue()
        assertWithMessage("cuenta como dinero sin escribir (lo leen el aviso, la declaración y la recuperación)")
            .that(ledger.tieneEvidenciaSinGuardar("a")).isTrue()
        assertWithMessage("un «no se cobró» sobre ese intento no se escribe")
            .that(ledger.markHostResponded("a", approved = false, operationId = null, referenceNumber = null, authCode = null)).isFalse()
        assertThat(dao.getById("a")!!.state).isEqualTo(PaymentAttemptEntity.STATE_AUTORIZANDO)
        assertWithMessage("ningún cobro nuevo entra mientras el dinero no esté escrito").that(cobroNuevoEntra("b")).isFalse()
    }

    @Test fun `P1 cuando la base vuelve la aprobacion se escribe sola y el cobro siguiente entra`() = runTest {
        aprobacionQueNoSeEscribio()
        laBaseVuelve()

        assertThat(ledger.reintentarEvidenciaSinGuardar()).isEqualTo(0)
        val fila = dao.getById("a")!!
        assertThat(fila.state).isEqualTo(PaymentAttemptEntity.STATE_HOST_RESPONDIO)
        assertThat(fila.hostApproved).isTrue()
        assertThat(fila.referenceNumber).isEqualTo("ref-1")
        assertThat(fila.authCode).isEqualTo("auth-1")
        assertThat(ledger.tieneEvidenciaSinGuardar("a")).isFalse()
        assertThat(ledger.aprobacionSigueSinGuardar("a")).isFalse()

        ledger.markAuthorized("a", "****1234", "VISA", "CONTACTLESS")
        ledger.markRecorded("a")
        assertThat(dao.getById("a")!!.state).isEqualTo(PaymentAttemptEntity.STATE_REGISTRADO)
        assertWithMessage("ya escrito y registrado, la terminal sigue cobrando").that(cobroNuevoEntra("b")).isTrue()
    }

    @Test fun `P1 la puerta del cobro siguiente escribe la aprobacion pendiente si la base ya puede`() = runTest {
        aprobacionQueNoSeEscribio()
        laBaseVuelve()

        // Sin pasada de por medio: la reserva del cobro siguiente pasa por la puerta, que escribe lo pendiente primero.
        cobroNuevoEntra("b")
        assertThat(dao.getById("a")!!.hostApproved).isTrue()
        assertThat(ledger.tieneEvidenciaSinGuardar("a")).isFalse()
    }

    @Test fun `P2 si la fila avanzo a AUTORIZADO mientras tanto se le pone la marca del banco sin cambiar su estado`() = runTest {
        aprobacionQueNoSeEscribio()
        ledger.markAuthorized("a", "****1234", "VISA", "CHIP")   // esta escritura no toca host_approved: sí queda
        assertThat(dao.getById("a")!!.state).isEqualTo(PaymentAttemptEntity.STATE_AUTORIZADO)
        laBaseVuelve()

        assertThat(ledger.aprobacionSigueSinGuardar("a")).isFalse()
        val fila = dao.getById("a")!!
        assertThat(fila.state).isEqualTo(PaymentAttemptEntity.STATE_AUTORIZADO)
        assertThat(fila.hostApproved).isTrue()
        assertThat(fila.referenceNumber).isEqualTo("ref-1")
    }

    @Test fun `P2 un registro fallido sin la marca vuelve a ser candidato de la recuperacion`() = runTest {
        aprobacionQueNoSeEscribio()
        ledger.markAuthorized("a", "****1234", "VISA", "CHIP")
        ledger.markRecordFailed("a", "sin red")
        assertThat(dao.getById("a")!!.state).isEqualTo(PaymentAttemptEntity.STATE_REGISTRO_FALLIDO)
        val antes = dao.getApprovalRecoveryCandidates(venue, Long.MAX_VALUE, Long.MAX_VALUE).map { it.attemptId }
        assertWithMessage("control: sin la marca, el registro fallido no es candidato").that(antes).doesNotContain("a")
        laBaseVuelve()

        assertThat(ledger.reintentarEvidenciaSinGuardar()).isEqualTo(0)
        assertThat(dao.getById("a")!!.hostApproved).isTrue()
        assertThat(dao.getApprovalRecoveryCandidates(venue, Long.MAX_VALUE, Long.MAX_VALUE).map { it.attemptId }).contains("a")
    }

    @Test fun `P1 muerte del proceso con la aprobacion sin escribir - la fila queda en duda y nunca dice no se cobro`() = runTest {
        aprobacionQueNoSeEscribio()
        laBaseVuelve()

        // El proceso muere: la memoria se va con él. El proceso nuevo no abrió ese intento.
        val delProcesoNuevo = libreta()
        assertThat(delProcesoNuevo.cuarentenaDeHuerfanos()).isEqualTo(1)
        val fila = dao.getById("a")!!
        assertWithMessage("en duda: pudo cobrar").that(fila.state).isEqualTo(PaymentAttemptEntity.STATE_INDETERMINADO)
        assertThat(fila.lastError).isEqualTo("proceso_terminado")
        // ⚠️ Residual declarado: la marca del banco se perdió con el proceso (la base no la dejó escribir). Lo único durable es lo
        // que el servidor ya registró; por eso la pantalla nunca lo pintó como «Aprobado» limpio.
        assertThat(fila.hostApproved).isNotEqualTo(true)
        assertThat(delProcesoNuevo.tieneEvidenciaSinGuardar("a")).isFalse()
    }

    @Test fun `control repetir la marca sobre HOST_RESPONDIO no inventa dinero sin escribir`() = runTest {
        assertThat(cobroNuevoEntra("a")).isTrue()
        assertThat(ledger.markAuthorizing("a")).isTrue()
        assertThat(ledger.markHostResponded("a", true, "op-1", "ref-1", "auth-1")).isTrue()
        // La rama sin sesión (D3) la repite a propósito: el CAS no encuentra la fila en su estado y da false, sin falla de disco.
        assertThat(ledger.markHostResponded("a", true, "op-1", "ref-1", "auth-1")).isFalse()

        assertThat(ledger.tieneEvidenciaSinGuardar("a")).isFalse()
        assertThat(ledger.aprobacionSigueSinGuardar("a")).isFalse()
        assertThat(ledger.reintentarEvidenciaSinGuardar()).isEqualTo(0)
    }

    @Test fun `control un rechazo del banco que no se pudo escribir no se recuerda como dinero`() = runTest {
        assertThat(cobroNuevoEntra("a")).isTrue()
        assertThat(ledger.markAuthorizing("a")).isTrue()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER rechazo_roto BEFORE UPDATE OF host_approved ON payment_attempts BEGIN SELECT RAISE(ABORT, 'disco lleno'); END",
        )
        assertThat(ledger.markHostResponded("a", approved = false, operationId = null, referenceNumber = null, authCode = null)).isFalse()
        assertThat(ledger.tieneEvidenciaSinGuardar("a")).isFalse()
        assertThat(ledger.aprobacionSigueSinGuardar("a")).isFalse()
    }
}
